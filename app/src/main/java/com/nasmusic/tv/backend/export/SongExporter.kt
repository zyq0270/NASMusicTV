package com.nasmusic.tv.backend.export

import android.content.Context
import android.media.MediaScannerConnection
import androidx.documentfile.provider.DocumentFile
import com.nasmusic.tv.backend.download.DownloadRepository
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.backend.download.db.ExportRecordEntity
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 导出到外接设备（USB/SD）执行器（见方案 §8.8）
 *
 * - 导出 downloads.db 中 state=COMPLETED 的全部歌曲：音频 + artist.jpg + cover.jpg + 旁路 .lrc/.jpg
 * - 字节复制，不重新编码（源文件下载时已内嵌封面/歌词）
 * - 增量过滤：export_records 中 relPath 已存在 且 srcSize 未变 → skipped
 * - 可取消 / 可续跑：每首歌结束后保存已完成记录，取消后重插 U 盘可继续
 * - SAF 与 File 两种根都**先写 `<name>.part`、整份成功后**才动目标文件（C15 修复）；
 *   SAF 无 rename 语义，故再复制一遍到目标，失败时保留 .part 完整副本。
 */
class SongExporter(
    private val context: Context,
    private val repo: DownloadRepository,
    private val exportRecordDao: com.nasmusic.tv.backend.download.db.ExportRecordDao,
    private val prefs: com.nasmusic.tv.data.prefs.AppPreferences,
    /**
     * M11：导出完成后重建该设备的曲库索引（可选注入，默认 null = 不重建）。
     *
     * 由 `ExportCoordinator` 注入 `LocalMusicRepository::scanUsbDevice` ——
     * 导出器自己不该知道本地曲库的存在，否则「导出」这个纯文件职责就被污染了。
     */
    private val usbIndexRefresh: (suspend (String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SongExporter"
        private const val BUFFER = 8192
        private const val SPACE_CHECK_INTERVAL = 50L * 1024 * 1024   // 每 50MB 复检
        private const val RESERVED_BYTES = 100L * 1024 * 1024         // 目标设备也留 100MB 余量
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /**
     * 2026-09-25 审查修复（#6 导出状态机断裂）：ExportCoordinator 此前只在成功终态经
     * onCompleted 更新自己的 state，本类私有 _state 的 Running 进度与 NO_SPACE /
     * NOTHING_TO_EXPORT 失败永远到不了 UI（设置页表现为"点了没反应"）。
     * 新增状态外发回调，由 coordinator 在导出期间订阅转发。
     */
    @Volatile var onStateChanged: ((ExportState) -> Unit)? = null

    private fun setState(s: ExportState) {
        _state.value = s
        onStateChanged?.invoke(s)
    }

    @Volatile private var isActive = false
    @Volatile private var cancelRequested = false
    private var currentRoot: ExportRoot = ExportRoot.Unavailable

    /**
     * 导出互斥锁（下载 #M-7 修复）：单例无并发保护时重复触发会并发写同一目标、
     * 且两个任务互相踩 cancelRequested。后者直接忽略（返回 false）而不是排队。
     */
    private val exportMutex = kotlinx.coroutines.sync.Mutex()
    private val exportActive = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancel() {
        cancelRequested = true
        setState(ExportState.Cancelled)
    }

    /**
     * 执行导出。
     *
     * @param root 导出根（SAF 或文件系统）
     * @param volumeId 卷标识（用于 export_records 增量）
     * @param devicePath 设备路径（用于判定导出目录在应用专属目录时的可见性）
     * @param onCompleted 完成回调（更新本地曲库让新导出的歌出现在 USB 列表）
     * @return true = 本次拿到互斥锁并真正发起导出；false = 已有导出在跑，本次被忽略（未发起）
     */
    suspend fun export(root: ExportRoot, volumeId: String, devicePath: String, onCompleted: (Int, Int, Int) -> Unit): Boolean {
        // 并发守卫：重复触发直接忽略（避免并发写同一目标 + cancelRequested 互相干扰）
        if (!exportMutex.tryLock()) {
            AppLog.w(TAG, "export: another export is running, ignore duplicate trigger")
            return false
        }
        try {
            exportLocked(root, volumeId, devicePath, onCompleted)
        } finally {
            exportMutex.unlock()
        }
        return true
    }

    private suspend fun exportLocked(
        root: ExportRoot,
        volumeId: String,
        devicePath: String,
        onCompleted: (Int, Int, Int) -> Unit
    ) {
        if (root is ExportRoot.Unavailable) {
            setState(ExportState.Failed(ExportError.NO_PERMISSION))
            return
        }
        val completedSongs = repo.getCompleted().filter { it.audioPath != null }
        if (completedSongs.isEmpty()) {
            setState(ExportState.Failed(ExportError.NOTHING_TO_EXPORT))
            return
        }

        // 1. 枚举待导出文件任务（音频 + 封面/歌词，同专辑封面去重）
        val tasks = mutableListOf<FileTask>()
        val exportedCovers = HashSet<String>()   // "artist/album" → 已导出封面
        completedSongs.forEach { song ->
            val audio = song.audioPath?.let { File(it) }
            if (audio == null || !audio.exists()) return@forEach   // 源文件缺失，跳过
            tasks.add(FileTask(audio, relAudioPath(audio)))
            // 封面（同专辑去重）
            audio.parentFile?.let { albumDir ->
                val key = albumDir.absolutePath
                if (exportedCovers.add(key)) {
                    val albumName = albumDir.name
                    val artistName = albumDir.parentFile?.name ?: "未知歌手"
                    File(albumDir, "cover.jpg").takeIf { it.exists() }?.let {
                        tasks.add(FileTask(it, "$artistName/$albumName/cover.jpg"))
                    }
                    albumDir.parentFile?.let { artistDir ->
                        File(artistDir, "artist.jpg").takeIf { it.exists() }?.let {
                            tasks.add(FileTask(it, "$artistName/artist.jpg"))
                        }
                    }
                }
            }
            // 旁路 .lrc / .jpg
            song.lyricPath?.let { File(it).takeIf { f -> f.exists() }?.let { f ->
                tasks.add(FileTask(f, relSidecarPath(f)))
            } }
            song.coverPath?.let { File(it).takeIf { f -> f.exists() }?.let { f ->
                tasks.add(FileTask(f, relSidecarPath(f)))
            } }
        }
        if (tasks.isEmpty()) {
            setState(ExportState.Failed(ExportError.NOTHING_TO_EXPORT))
            return
        }

        // 2. 空间校验（sum 待导字节 > 设备可用 - 100MB → NO_SPACE）
        val totalBytes = tasks.sumOf { it.src.length() }
        val available = com.nasmusic.tv.util.StorageUtils.availableBytesAt(File(devicePath))
        if (available > 0 && totalBytes > available - RESERVED_BYTES) {
            setState(ExportState.Failed(ExportError.NO_SPACE))
            return
        }

        // 3. 增量过滤（查 export_records(volumeId)）
        val existing = runCatching { exportRecordDao.byVolume(volumeId) }.getOrDefault(emptyList())
        val existingMap = existing.associateBy { it.relPath }
        var skipped = 0
        var done = 0
        var failed = 0
        var writtenSinceCheck = 0L

        setState(ExportState.Running(0, tasks.size, "", skipped, failed))
        currentRoot = root
        isActive = true
        cancelRequested = false

        for (task in tasks) {
            if (cancelRequested) {
                setState(ExportState.Cancelled)
                isActive = false
                return
            }
            setState(ExportState.Running(done, tasks.size, task.src.name, skipped, failed))

            // 增量：relPath 已存在 且 srcSize 未变 → 跳过
            val rec = existingMap[task.relPath]
            if (rec != null && rec.srcSize == task.src.length() && !shouldOverwrite(task)) {
                skipped++
                continue
            }

            val ok = try {
                copyToRoot(root, task)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                // 每完成一个文件 upsert 一条 record
                runCatching {
                    exportRecordDao.upsert(
                        ExportRecordEntity(
                            volumeId = volumeId,
                            relPath = task.relPath,
                            srcPath = task.src.absolutePath,
                            // L13 修复：size 死列已移除（与 srcSize 写入完全相同的值）
                            srcSize = task.src.length(),
                            exportedAt = System.currentTimeMillis()
                        )
                    )
                }
                done++
                writtenSinceCheck += task.src.length()
                // 每 50MB 复检空间，跌破即中止
                if (writtenSinceCheck >= SPACE_CHECK_INTERVAL) {
                    writtenSinceCheck = 0
                    val nowAvail = com.nasmusic.tv.util.StorageUtils.availableBytesAt(File(devicePath))
                    if (nowAvail <= RESERVED_BYTES) {
                        setState(ExportState.Failed(ExportError.NO_SPACE))
                        isActive = false
                        return
                    }
                }
            } else {
                failed++
            }
            setState(ExportState.Running(done, tasks.size, task.src.name, skipped, failed))
        }

        isActive = false
        // 完成 → 触发媒体扫描 + 更新本地曲库
        runCatching { mediaScan(devicePath) }
        setState(ExportState.Completed(done, skipped, failed))
        onCompleted(done, skipped, failed)
    }

    /**
     * 是否覆盖目标已有文件：目标存在且长度一致 → false（跳过）；否则 true（覆盖）。
     * P1-6 修复（2026-09-16）：原实现 targetFile 只支持 File 根，SAF 分支恒返回 null
     * → shouldOverwrite 对 SAF 恒 true，增量判定只剩 export_records，
     * 清掉导出记录后重导会无条件覆盖 USB 上同名不同长的文件。
     * 现在 SAF 分支用 resolveChildDoc 反查目标 DocumentFile 的长度对比。
     */
    private fun shouldOverwrite(task: FileTask): Boolean {
        val srcLen = task.src.length()
        val srcMtime = task.src.lastModified()
        return when (val root = currentRoot) {
            is ExportRoot.File -> {
                val target = File(root.dir, task.relPath)
                if (!target.exists()) return true
                // N06：长度相同但源文件比目标更新时间 ⇒ 重新下载过（同名同长、内容不同），
                // 需要重导。mtime 不可用（0）时退回纯长度判定。
                val targetMtime = target.lastModified()
                target.length() != srcLen ||
                    (srcMtime > 0L && targetMtime > 0L && srcMtime > targetMtime)
            }
            is ExportRoot.Saf -> {
                // M07：⛔ 这里只是「问一下要不要覆盖」，不能用 resolveChildDoc ——
                // 它在判断过程中就会 createDirectory/createFile，用户取消导出后
                // U 盘上会残留一堆空目录和 0 字节文件。改用纯查询版。
                val doc = findChildDoc(root.dir, task.relPath) ?: return true
                val docMtime = runCatching { doc.lastModified() }.getOrDefault(0L)
                doc.length() != srcLen ||
                    (srcMtime > 0L && docMtime > 0L && srcMtime > docMtime)
            }
            ExportRoot.Unavailable -> true
        }
    }

    private suspend fun copyToRoot(root: ExportRoot, task: FileTask): Boolean = withContext(Dispatchers.IO) {
        when (root) {
            is ExportRoot.Saf -> {
                val parent = root.dir
                val doc = resolveChildDoc(parent, task.relPath) ?: return@withContext false
                // ── C15 修复（2026-11-09）──────────────────────────────────────
                // 旧实现直接 openOutputStream(doc.uri)（SAF 下 "w" 先截断），catch 里再
                // doc.delete() ⇒ 中途失败会把**用户上一次导出的完好文件**连半截一起删掉，
                // 而 SAF 没有回收站、无法恢复。
                // 现在与 File 分支对齐：先写同目录的 `<name>.part`，整份写成功后才动目标。
                // 任何失败只删 .part，目标文件保持原样。
                val partName = (doc.name ?: task.relPath.substringAfterLast('/')) + ".part"
                val part = parent.findFile(partName) ?: parent.createFile(mimeTypeFor(partName), partName)
                if (part == null) {
                    AppLog.w(TAG, "export(SAF): 无法创建临时文件 $partName")
                    return@withContext false
                }
                // 目标是否已被截断开始写：失败时据此决定「清掉 .part」还是「保留 .part 副本」
                var targetCopyStarted = false
                try {
                    val partOut = context.contentResolver.openOutputStream(part.uri)
                        ?: return@withContext false
                    partOut.use { out ->
                        task.src.inputStream().use { input -> copyStream(input, out) }
                    }
                    // 整份已落盘，才覆盖目标
                    val targetOut = context.contentResolver.openOutputStream(doc.uri, "wt")
                        ?: run {
                            runCatching { part.delete() }
                            return@withContext false
                        }
                    targetCopyStarted = true
                    val written = targetOut.use { out ->
                        // S04-02：目标已被 "wt" 截断，openInputStream 返回 null 不能静默跳过，
                        // 否则会删掉唯一完整 .part 副本却返回成功。此处保留副本并记失败。
                        val input = context.contentResolver.openInputStream(part.uri)
                            ?: run {
                                AppLog.e(TAG, "export(SAF): openInputStream(part) null after target truncated; keep ${part.name}")
                                return@withContext false
                            }
                        input.use { copyStream(it, out) }
                    }
                    // S04-02：覆盖后校验实际写入字节数，不一致则保留 .part 副本并返回失败
                    if (written != task.src.length()) {
                        AppLog.e(TAG, "export(SAF): copied $written != src ${task.src.length()}; keep ${part.name}")
                        return@withContext false
                    }
                    runCatching { part.delete() }
                    true
                } catch (e: Exception) {
                    if (!targetCopyStarted) {
                        // 目标还没被碰过 ⇒ 只清掉半截临时文件，用户的旧文件完好无损
                        AppLog.w(TAG, "export(SAF): 写入临时文件失败，目标文件未改动", e)
                        runCatching { part.delete() }
                    } else {
                        // 目标已被截断，但 .part 里有一份完整副本 ⇒ 保留它，便于下次覆盖或人工恢复
                        AppLog.w(TAG, "export(SAF): 覆盖目标时失败，已保留 ${part.name} 完整副本", e)
                    }
                    false
                }
            }
            is ExportRoot.File -> {
                val target = File(root.dir, task.relPath)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.part")
                try {
                    tmp.outputStream().use { out ->
                        task.src.inputStream().use { input -> copyStream(input, out) }
                    }
                    if (!tmp.renameTo(target)) {
                        // rename 失败：复制到同目录 .part2 再 rename 原子替换。
                        // S04-01：绝不 tmp.copyTo(target, overwrite=true) 非原子直写，
                        // 否则中途失败会截断用户原有文件且留下半截。
                        val part2 = File(target.parentFile, "${target.name}.part2")
                        try {
                            tmp.copyTo(part2, overwrite = true)
                            if (!part2.renameTo(target)) error("rename part2 to target failed")
                        } finally {
                            // I3-02：成功路径被 rename 替换的是 part2，复制源 tmp（.part）此前从未删除，
                            // 只在 catch 异常分支才清理 ⇒ 成功导出后目标目录残留等大 .part 副本。
                            // 这里一并幂等清理 tmp（rename 失败时 catch 已删过，再删无副作用）。
                            runCatching { part2.delete() }
                            runCatching { tmp.delete() }
                        }
                    }
                    true
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    false
                }
            }
            ExportRoot.Unavailable -> false
        }
    }

    /** @return 实际写入的字节数（S04-02：供 SAF 覆盖后做完整性校验） */
    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

/** SAF 嵌套目录解析：逐级 findFile/createDirectory 定位到叶子目录，再 createFile 文件名 */
    /**
     * M07：**纯查询**版子文档解析（⛔ 绝不 create）。
     *
     * 与 [resolveChildDoc] 的区别只在「找不到就返回 null」而不顺手建出来 ——
     * 供 [shouldOverwrite] 这类只做判断的路径使用。
     */
    private fun findChildDoc(root: DocumentFile, relPath: String): DocumentFile? {
        val segments = relPath.split("/").toMutableList()
        val fileName = segments.removeAt(segments.lastIndex)
        var current = root
        for (dir in segments) {
            current = current.findFile(dir) ?: return null
        }
        return current.findFile(fileName)
    }

    private fun resolveChildDoc(root: DocumentFile, relPath: String): DocumentFile? {
        val segments = relPath.split("/").toMutableList()
        // NewApi 修复（2026-09-14）：removeLast() 在 API 35 会被 java.util.SequencedCollection
        // 的同名方法遮蔽，重新编译后会在低版本上 NoSuchMethodError；改用 removeAt(lastIndex)。
        val fileName = segments.removeAt(segments.lastIndex) // 最后一段是文件名
        var current = root
        for (dir in segments) {
            current = current.findFile(dir)
                ?: current.createDirectory(dir)
                ?: return null
        }
        return current.findFile(fileName) ?: current.createFile(mimeTypeFor(fileName), fileName)
    }

    /**
     * L9 修复（2026-10-06，代码审查报告 §5）：SAF 分支按扩展名映射 MIME——
     * 旧实现对所有文件（含封面 jpg / 歌词 lrc）硬编码 audio 通配 MIME，导出的
     * 封面/歌词在部分 DocumentsProvider 下被识别为音频。（注：createFile 的
     * mimeType 对多数 Provider 只是建议值，真实类型由扩展名推导；本修复消除
     * 依赖建议值的场景。）
     * ⚠️ 注释内不得出现斜杠+星号字面（Kotlin 块注释可嵌套，会吞掉后续代码）。
     */
    private fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "lrc", "txt" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/*"
        }
    }

    /** 音频相对路径（歌手/专辑/文件名）。原 volumeId/song 为未使用的死参数，易误导「按卷隔离」 */
    private fun relAudioPath(audio: File): String {
        val parent = audio.parentFile
        val album = parent?.name ?: "单曲"
        val artist = parent?.parentFile?.name ?: "未知歌手"
        return "$artist/$album/${audio.name}"
    }

    /** 旁路文件相对路径（歌手/专辑/文件名）。原 volumeId/song/ext 为未使用的死参数 */
    private fun relSidecarPath(f: File): String {
        val parent = f.parentFile
        val album = parent?.name ?: "单曲"
        val artist = parent?.parentFile?.name ?: "未知歌手"
        return "$artist/$album/${f.name}"
    }

    /** 媒体扫描（best-effort；应用专属目录在 Android/data 下多数 ROM 不扫，调用方改用 scanUsbDevice 重建索引） */
    /**
     * 导出完成后的可见性收尾（M11）。
     *
     * ① MediaStore：让系统媒体库看到**已下载源文件**（历史行为，保持不动）；
     * ② 本地曲库索引：⛔ 原实现把 [devicePath] 收下却完全没用（扫的是源文件路径），
     *    导出到 U 盘后不重建索引 ⇒ 新歌要拔插一次才在曲库里出现。
     *    现在通过注入回调真正用上 [devicePath]。
     */
    private suspend fun mediaScan(devicePath: String) {
        runCatching {
            val paths = repo.getCompleted().mapNotNull { it.audioPath }
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
        // 构造器 val 上的可空函数类型不做智能转换 ⇒ 先取本地 val（同一帧内引用稳定）
        val refresh = usbIndexRefresh
        if (devicePath.isNotBlank() && refresh != null) {
            runCatching { refresh.invoke(devicePath) }
                .onFailure { AppLog.w(TAG, "mediaScan: usb index refresh failed: ${it.message}") }
        }
    }

    private data class FileTask(val src: File, val relPath: String)
}

/** 导出状态机 */
sealed interface ExportState {
    object Idle : ExportState
    data class Preparing(val total: Int) : ExportState
    data class Running(
        val done: Int,
        val total: Int,
        val current: String,
        val skipped: Int = 0,
        val failed: Int = 0
    ) : ExportState
    data class Completed(val done: Int, val skipped: Int, val failed: Int) : ExportState
    data class Failed(val reason: ExportError) : ExportState
    object Cancelled : ExportState
}

enum class ExportError { NO_DEVICE, NO_PERMISSION, NO_SPACE, DEVICE_REMOVED, IO, NOTHING_TO_EXPORT }