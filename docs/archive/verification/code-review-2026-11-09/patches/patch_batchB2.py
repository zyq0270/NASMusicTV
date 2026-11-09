# -*- coding: utf-8 -*-
"""Batch B 续：M05 / M07 / M08 / M12 / M13 / M14 / M41（M04 已落地）。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = ""
CRLF = False


def load(rel):
    global S, CRLF
    p = os.path.join(ROOT, rel)
    S = io.open(p, "r", encoding="utf-8", newline="").read()
    CRLF = "\r\n" in S
    return p


def rep(a, b, tag, expect=1):
    global S
    a2 = a.replace("\n", "\r\n") if CRLF else a
    b2 = b.replace("\n", "\r\n") if CRLF else b
    n = S.count(a2)
    if n != expect:
        print("FAIL [%s] count=%d (expect %d)" % (tag, n, expect))
        sys.exit(1)
    S = S.replace(a2, b2)


def save(p):
    io.open(p, "w", encoding="utf-8", newline="").write(S)


M05_NEW = """            // M05：\u26d4 不能只写索引 —— PlayerState 的契约是「queue / currentIndex /
            // currentSong 同帧发布」，只改索引会让 UI 读到「新索引 + 旧歌名」的错帧
            // （随机播放的 next / onPlaybackEnded 都走这里）。
            _playerState.update {
                it.copy(
                    currentIndex = randomIndex,
                    currentSong = it.queue.getOrNull(randomIndex) ?: it.currentSong
                )
            }"""

# ── M05 ──
p = load("app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt")
rep(
    """            _playerState.update { it.copy(currentIndex = randomIndex) }
            p.seekTo(randomIndex, 0)
            p.play()
            return""",
    M05_NEW + """
            p.seekTo(randomIndex, 0)
            p.play()
            return""",
    "m05-a",
)
rep(
    """        val randomIndex = available.random()
        shuffleHistory.add(randomIndex)
        _playerState.update { it.copy(currentIndex = randomIndex) }
        p.seekTo(randomIndex, 0)""",
    """        val randomIndex = available.random()
        shuffleHistory.add(randomIndex)
""" + M05_NEW.replace("            ", "        ") + """
        p.seekTo(randomIndex, 0)""",
    "m05-b",
)
save(p)
print("OK PlayerManager (M05)")

# ── M07 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/export/SongExporter.kt")
rep(
    """            is ExportRoot.Saf -> {
                val doc = resolveChildDoc(root.dir, task.relPath) ?: return true""",
    """            is ExportRoot.Saf -> {
                // M07：\u26d4 这里只是「问一下要不要覆盖」，不能用 resolveChildDoc ——
                // 它在判断过程中就会 createDirectory/createFile，用户取消导出后
                // U 盘上会残留一堆空目录和 0 字节文件。改用纯查询版。
                val doc = findChildDoc(root.dir, task.relPath) ?: return true""",
    "m07-use",
)
rep(
    """    private fun resolveChildDoc(root: DocumentFile, relPath: String): DocumentFile? {""",
    """    /**
     * M07：**纯查询**版子文档解析（\u26d4 绝不 create）。
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

    private fun resolveChildDoc(root: DocumentFile, relPath: String): DocumentFile? {""",
    "m07-find",
)
save(p)
print("OK SongExporter (M07)")

# ── M08 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/download/SongDownloadManager.kt")
rep(
    """                val hit = albumDir.listFiles { f -> f.isFile && pattern.matches(f.name) }
                    ?.maxByOrNull { it.lastModified() }""",
    """                val hit = albumDir.listFiles { f -> f.isFile && pattern.matches(f.name) }
                    // M08：\u26d4 只按文件名正则匹配不够 —— 崩溃/断电可能在最终路径上留下**半截文件**，
                    // 被误标 COMPLETED 后 playableLocalUri 指向坏文件且永不重下。
                    // 追加两道校验：非空；DB 有 fileSize 记录时必须一致。
                    ?.filter { it.length() > 0L }
                    ?.filter { entity.fileSize <= 0L || it.length() == entity.fileSize }
                    ?.maxByOrNull { it.lastModified() }""",
    "m08",
)
save(p)
print("OK SongDownloadManager (M08)")

# ── M12 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/playlist/JsonPlaylistParser.kt")
rep(
    """        } catch (e: JsonParseException) {
            return emptyList()
        }
        val version = root.get("formatVersion")""",
    """        } catch (e: JsonParseException) {
            return emptyList()
        } catch (e: IllegalStateException) {
            // M12：根是数组/标量时 asJsonObject 抛 IllegalStateException（不是 JsonParseException）
            // ⇒ 原实现让它一路抛到上层，一张格式不对的文件就毁掉整次导入。
            AppLog.w(TAG, "parse: root is not a JSON object (${e.message})")
            return emptyList()
        }
        val version = root.get("formatVersion")""",
    "m12-root",
)
rep(
    """        val songs = root.getAsJsonArray("songs") ?: return emptyList()""",
    """        // M12：`getAsJsonArray` 在字段存在但类型不符（对象/字符串）时抛 IllegalStateException；
        // 安全转换后类型不符等价于「没有歌曲」。
        val songs = (root.get("songs") as? JsonArray) ?: return emptyList()""",
    "m12-songs",
)
save(p)
print("OK JsonPlaylistParser (M12)")

# ── M13 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/photo/YuNetFaceDetector.kt")
rep(
    """        val bytes = try {
            source.openStream(ref)?.use { it.readBytes() }
        } catch (t: Throwable) {""",
    """        // M13：\u26d4 不能 readBytes() —— inSampleSize 只缩小**解码后**的 Bitmap，
        // 原图字节仍然整份进内存（几十 MB 的照片 × 扫上千张 ⇒ OOM）。
        // 改为有界读取：超过 MAX_SOURCE_BYTES 直接当失败，扫描跳过这张。
        val bytes = try {
            source.openStream(ref)?.use { readBounded(it, MAX_SOURCE_BYTES) }
        } catch (t: Throwable) {""",
    "m13-use",
)
rep(
    """    private fun decodeBitmap(ref: PhotoRef, bytes: ByteArray): Bitmap? {""",
    """    /** M13：有界读取（超限返回 null，绝不把整份大图读进内存） */
    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 1 shl 20))
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decodeBitmap(ref: PhotoRef, bytes: ByteArray): Bitmap? {""",
    "m13-fn",
)
save(p)
print("OK YuNetFaceDetector (M13)")

# ── M14 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/network/mv/MvPersistentCache.kt")
rep(
    """    /**
     * 标记某首歌的某支 MV 播放完成（用户认可这个版本）。""",
    """    /**
     * M14：落盘串行执行器。
     *
     * [markCompleted] 在**主线程**被调用（MV 播完切歌路径），而 [save] 是
     * 「最多 `MAX_ENTRIES` 条 Gson 序列化 + 写文件 + rename」的同步磁盘写
     * ⇒ 直接调用会让切歌掉帧。单线程保证投递顺序 = 落盘顺序。
     */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mv-cache-save").apply { isDaemon = true }
    }

    /** 异步落盘（见 [saveExecutor]）：失败只留痕，不影响播放 */
    private fun saveAsync() {
        saveExecutor.execute {
            try {
                save()
            } catch (t: Throwable) {
                AppLog.e(TAG, "saveAsync failed: ${t.message}", t)
            }
        }
    }

    /**
     * 标记某首歌的某支 MV 播放完成（用户认可这个版本）。""",
    "m14-fn",
)
rep(
    """        cache[songId] = entry
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
        AppLog.d(TAG, "markCompleted: '$songTitle' -> bvid=$bvid playCount=${entry.playCount}")""",
    """        cache[songId] = entry
        if (cache.size > MAX_ENTRIES) evictOldest()
        // M14：主线程调用点 ⇒ 必须异步落盘
        saveAsync()
        AppLog.d(TAG, "markCompleted: '$songTitle' -> bvid=$bvid playCount=${entry.playCount}")""",
    "m14-use",
)
save(p)
print("OK MvPersistentCache (M14)")

# ── M41 ──
p = load("app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlayStatsViewModel.kt")
rep(
    """                // 首次打开时把 play_records 回填进按天统计（幂等，标记置位后零开销）
                runCatching { prefs.backfillDailyStatsOnce() }
                    .onFailure {
                        it.rethrowIfCancellation()
                        AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it)
                    }

                val songMeta: List<Song> = prefs.getRecentSongObjects()
                val month = com.nasmusic.tv.data.stats.PlayStatsRepository.currentMonth()

                val monthlyCounts = prefs.playStatsRepo.getMonthCounts(month)
                val allTimeCounts = prefs.history.playCounts.firstOrNull() ?: emptyMap()
                val dailyCounts = prefs.playStatsRepo.getDailyCounts()""",
    """                // M41：下面整段都是持久化读取（DataStore 首读 + Gson/Room 反序列化），
                // 原本跑在 viewModelScope 默认的 Main 上 ⇒ 打开统计页首帧掉帧。
                // 逐项下放到 IO（类型由表达式推断，不做元组打包）。
                withContext(Dispatchers.IO) {
                    // 首次打开时把 play_records 回填进按天统计（幂等，标记置位后零开销）
                    runCatching { prefs.backfillDailyStatsOnce() }
                        .onFailure {
                            it.rethrowIfCancellation()
                            AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it)
                        }
                }

                val month = com.nasmusic.tv.data.stats.PlayStatsRepository.currentMonth()
                val songMeta: List<Song> = withContext(Dispatchers.IO) {
                    prefs.getRecentSongObjects()
                }
                val monthlyCounts = withContext(Dispatchers.IO) {
                    prefs.playStatsRepo.getMonthCounts(month)
                }
                val allTimeCounts = withContext(Dispatchers.IO) {
                    prefs.history.playCounts.firstOrNull() ?: emptyMap()
                }
                val dailyCounts = withContext(Dispatchers.IO) {
                    prefs.playStatsRepo.getDailyCounts()
                }""",
    "m41",
)
save(p)
print("OK PlayStatsViewModel (M41)")

print("ALL DONE")
