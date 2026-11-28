package com.nasmusic.tv.backend.playlist

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌单导入编排器（docs/archive/playlist-import-feature-plan.md §4.2）。
 *
 * 职责：读流（不落盘）→ 嗅探选解析器 → 取名兜底 → 建歌单 → 去重入库 →
 * 返回 [ImportSummary]；成功后记录导入历史（§4.7.2）。
 *
 * URL 直链（§4.1.8 (2)）：HTTP / file:// / 绝对路径直接落地 [Song.streamUrl]
 * （isNetworkSong=false → [com.nasmusic.tv.data.prefs.AppPreferences] 的
 * stripVolatileStreamUrl 不清理本地歌曲），可立即播放；可达性由外部
 * [UrlReachabilityChecker] 在导入后后台批量测。
 */
class PlaylistImporter(
    private val app: Application,
    private val prefs: AppPreferences,
    @Suppress("unused") private val scope: CoroutineScope,
    private val localStore: LocalPlaylistStore = AppPreferencesLocalPlaylistStore(prefs),
) {
    data class ImportSummary(
        val playlistId: String,
        val playlistName: String,
        val imported: Int,         // 实际入库条目数
        val skipped: Int,          // 文件内重复/无效条目/不支持路径
        val unrecognizedFormat: Boolean = false,
        val parseError: Boolean = false,
    )

    private val parsers: List<PlaylistParser> = listOf(
        M3uPlaylistParser(),
        NeteaseCloudPlaylistParser(),
        JsonPlaylistParser(),
        TextPlaylistParser(),
    )

    /**
     * 从 SAF Uri 导入（§4.2 源文件生命周期：只读流解析，不落盘，用完即关）。
     *
     * @param userConfirmedName 用户输入的歌单名（可空→走取名兜底链）
     * @param onProgress 进度回调（0/25/50/75/100，100 意味着解析+入库全完成）
     */
    suspend fun import(
        uri: Uri,
        userConfirmedName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ImportSummary {
        // C17：读流是**阻塞 IO**（contentResolver.openInputStream + 最多 5MB 的 read）
        // ⇒ 必须离开调用方所在线程（历史上这里直接躺在 Main 上）。
        val fileName = withContext(Dispatchers.IO) {
            queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                app.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            }.getOrNull()
        } ?: return ImportSummary("", "", 0, 0, unrecognizedFormat = true)
        return importBytes(bytes, fileName, userConfirmedName, onProgress)
    }

    /**
     * 核心：字节 → 解析 → 入库。独立暴露便于单测（绕过 Uri）。
     *
     * ⚠️ C17：整段必须跑在 [Dispatchers.IO] 上 —— 解码与 DataStore 写入都是阻塞 IO。
     * 歌曲经完整校验后在一次 DataStore edit 内创建，避免逐首重写整份 JSON 的写放大。
     * 进度回调因此在 IO 线程触发，调用方只应写 StateFlow（线程安全）。
     */
    suspend fun importBytes(
        bytes: ByteArray,
        fileName: String,
        userConfirmedName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ImportSummary = withContext(Dispatchers.IO) {
        onProgress(0)
        if (bytes.isEmpty()) return@withContext ImportSummary("", "", 0, 0, unrecognizedFormat = true)

        val text = PlaylistParsers.decode(bytes)
        val head = bytes.copyOfRange(0, minOf(bytes.size, PlaylistParsers.SNIFF_BYTES))

        // 嗅探选解析器（顺序即优先级；canParse 各自基于首 4KB/扩展名）
        val parser = parsers.firstOrNull { it.canParse(head, fileName) }
            ?: return@withContext ImportSummary("", "", 0, 0, unrecognizedFormat = true)

        val entries = try {
            parser.parse(text, fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("PlaylistImporter", "parse failed for $fileName", e)
            return@withContext ImportSummary("", "", 0, 0, parseError = true)
        }
        if (parser is JsonPlaylistParser && parser.lastParseError) {
            return@withContext ImportSummary("", "", 0, 0, parseError = true)
        }
        val innerName = (parser as? PlaylistNameProvider)?.innerName

        val name = uniqueLocalPlaylistName(resolvePlaylistName(fileName, innerName, userConfirmedName))

        // 先完整解析、校验、去重，再一次性创建；任何条目异常都不会产生半成品歌单。
        val seen = HashSet<String>()
        val songs = mutableListOf<Song>()
        var skipped = 0
        entries.forEachIndexed { entryIndex, raw ->
            val pct = ((entryIndex + 1) * 75 / maxOf(entries.size, 1))
            if (pct >= 25 && pct % 25 == 0) onProgress(pct)
            if (raw.directUrlType == DirectUrlType.RELATIVE_PATH || raw.title.isBlank()) {
                skipped++
                return@forEachIndexed
            }
            val song = raw.song ?: toBareSong(raw)
            val boundSong = if (raw.songRef != null) {
                PlaylistSongRef.bindToSong(song, raw.songRef)
            } else song
            val stableKey = PlaylistSongRef.fromSong(boundSong, raw.songRef?.source)?.stableKey
                ?.takeIf { it.isNotBlank() }
            // Bare stubs carry a random UUID, so file-internal duplicates must be detected
            // on the source identity (ref / URL / normalized title+artist) instead of Song.id.
            val dedupKey = when {
                stableKey != null -> "key:$stableKey"
                raw.songRef != null -> "id:${song.id}"
                raw.directUrl != null -> "url:${raw.directUrl}"
                else -> "meta:" + PlaylistParsers.normalizeKey(raw.title, raw.artist)
            }
            if (song.id.isBlank() || !seen.add(dedupKey)) {
                skipped++
                return@forEachIndexed
            }
            songs += boundSong
        }
        val playlist = localStore.createWithSongs(name, songs)
        val imported = songs.size
        onProgress(100)

        // 歌单已原子落盘；历史只是旁路索引，写失败不能把成功导入误报成失败。
        try {
            prefs.recordPlaylistImport(
                PlaylistImportHistoryItem(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    importedAt = System.currentTimeMillis(),
                    importedCount = imported,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("PlaylistImporter", "recordPlaylistImport failed after playlist creation", e)
        }
        return@withContext ImportSummary(
            playlistId = playlist.id,
            playlistName = playlist.name,
            imported = imported,
            skipped = skipped,
        )
    }

    /** SAF 文件名：优先 DISPLAY_NAME，回退 path 末段。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** 限量读取（≤ MAX_FILE_BYTES），防超大文件 OOM（§4.1.7 (3) 截断语义）。 */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < PlaylistParsers.MAX_FILE_BYTES) {
            val n = input.read(buf)
            if (n < 0) break
            val take = minOf(n, PlaylistParsers.MAX_FILE_BYTES - total)
            out.write(buf, 0, take)
            total += take
            if (take < n) break
        }
        return out.toByteArray()
    }

    /** 同名导入不覆盖：原名可用时沿用，冲突则追加导入日期，仍冲突则追加序号。 */
    private suspend fun uniqueLocalPlaylistName(base: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val existing = prefs.getLocalPlaylists().map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in existing) return base
        val first = "$base (导入 $date)"
        if (first.lowercase(Locale.ROOT) !in existing) return first
        var suffix = 2
        while ("$first $suffix".lowercase(Locale.ROOT) in existing) suffix++
        return "$first $suffix"
    }

    /**
     * 歌单名兜底（§4.2）：userInput → innerName → 文件名（不在无意义白名单）→ 默认名。
     * 不做弹窗：名字取不到时直接沿用默认「导入的歌单 MM-dd HH:mm」。
     */
    fun resolvePlaylistName(
        fileName: String,       // "我的歌单.m3u" / "新建文本文档.txt"
        innerName: String?,     // #PLAYLIST / JSON name / txt 首行注释
        userInput: String?,     // 用户输入框（可空）
    ): String {
        userInput?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        innerName?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        if (fileName.isNotBlank() && !PlaylistParsers.isMeaninglessFileName(fileName)) {
            val base = PlaylistParsers.baseNameOf(fileName)
            if (base.isNotEmpty()) return base
        }
        return "导入的歌单 ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())}"
    }

    /**
     * 裸 Song 构造（§4.1.8 (2)）：
     * - HTTP URL：直接落地 streamUrl（isNetworkSong=false，可安全持久化）
     * - file:// / 绝对路径：同样直接落地 streamUrl
     * - 其他：不含 streamUrl，走补全链
     */
    private fun toBareSong(raw: RawSongEntry): Song {
        val stubId = PlaylistParsers.IMPORTED_ID_PREFIX + UUID.randomUUID()
        return when (raw.directUrlType) {
            DirectUrlType.HTTP -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                streamUrl = raw.directUrl,     // ★ 关键：HTTP URL 直接落地
                isNetworkSong = false,         // 不走 networkMusicManager.resolvePlayUrl
            )
            DirectUrlType.LOCAL_URI, DirectUrlType.ABSOLUTE_PATH -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                streamUrl = raw.directUrl,     // file:// / 绝对路径直接可播
            )
            else -> Song(
                id = stubId,
                title = raw.title.trim(),
                artist = raw.artist.trim(),
                album = raw.album?.trim().orEmpty(),
                durationMs = raw.durationSec?.times(1000L) ?: 0L,
                isNetworkSong = false,
                isLocalSong = false,
            )
        }
    }
}