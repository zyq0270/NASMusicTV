package com.nasmusic.tv.lyrics

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.cache.CacheLimits
import com.nasmusic.tv.data.model.LyricsCacheEntry
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络歌词持久化缓存（跨会话复用，避免重复请求网络歌词 API）
 *
 * 索引文件 + 独立 LRC 文件，避免单 JSON 体积过大：
 * - `filesDir/lyrics_cache.json` → 索引（仅 metadata，不含 lrcText）
 * - `filesDir/lyrics_cache/{songId}.lrc` → 纯 LRC 文本
 *
 * 写入时机：用户主动切换到网络歌词来源（[MainViewModel.switchLyricsSource]）
 * LRU 淘汰：超过 [CacheLimits] 快照中的歌词上限时删 lastPlayedAt 最旧的，同时删除对应的 .lrc 文件
 *
 * ## 进程级单例（C24）
 *
 * 索引是「**内存 Map + 整文件覆盖写**」的形态，因此**两个实例就是两个真源**：
 * 各自 load 一份索引、各自 `saveIndex()` 全量覆盖同一个 `lyrics_cache.json`
 * ⇒ 后写的把先写的条目整体抹掉（被抹掉条目的 `.lrc` 变成磁盘孤儿）。
 * 而 `LyricsManager` 在仓里确实被构造了两次（播放链路 / 备份链路）。
 *
 * ⇒ 构造改为私有，统一走 [get] 取**同一份**实例：一份索引、一把写锁、一个真源。
 */
class LyricsPersistentCache private constructor(context: Context) {

    /** 索引条目（不包含 LRC 文本，仅用于索引） */
    private data class IndexEntry(
        val songId: String,
        val songTitle: String,
        val songArtist: String,
        val lastPlayedAt: Long = 0L
    )

    private val index = ConcurrentHashMap<String, IndexEntry>()

    /** 命中计数：每 [TOUCH_SAVE_INTERVAL] 次命中落盘一次索引，避免每次 get 都写文件 */
    private val touchCounter = java.util.concurrent.atomic.AtomicInteger()
    private val gson = Gson()
    private val indexFile by lazy { File(context.filesDir, "lyrics_cache.json") }
    private val lrcDir by lazy { File(context.filesDir, "lyrics_cache").apply { mkdirs() } }

    companion object {
        private const val TAG = "LyricsPersistentCache"

        /** C24：进程级单例（见类 KDoc） */
        @Volatile
        private var instance: LyricsPersistentCache? = null

        /**
         * 取进程内唯一的缓存实例。
         *
         * @param context 任意 Context（内部转 `applicationContext`，不会持有 Activity）
         */
        fun get(context: Context): LyricsPersistentCache =
            instance ?: synchronized(this) {
                instance ?: LyricsPersistentCache(context.applicationContext).also { instance = it }
            }
        /** get 命中后累积多少次再落盘一次索引（真 LRU 访问时间刷新） */
        private const val TOUCH_SAVE_INTERVAL = 16
    }

    init {
        loadIndex()
    }

    /** 按 songId 查询缓存，返回完整条目（含 LRC 文本） */
    fun get(songId: String): LyricsCacheEntry? = synchronized(saveLock) {
        // G5-04：设置页文案是「匹配到的歌词**自动保存**到本地」——开关只应门控写。
        // 关闭后仍读已有缓存（否则弱网/断网明明有缓存却显示无歌词，每次播放都重新请求）。
        val idx = index[songId]
        if (idx == null) {
            null
        } else {
            val lrcFile = lrcFile(songId)
            if (!lrcFile.exists()) {
                // .lrc 文件丢失，清理索引（关闭自动缓存时不在读路径落盘）
                index.remove(songId)
                if (LyricsCachePolicy.enabled) saveIndex()
                null
            } else {
                val lrcText = lrcFile.readText()
                // 真 LRU：命中即刷新访问时间。原实现只在 put 时写 lastPlayedAt，
                // 导致「高频读取但从未重写」的旧条目反而先被淘汰，退化为 FIFO。
                val touched = idx.copy(lastPlayedAt = System.currentTimeMillis())
                index[songId] = touched
                maybePersistTouch()
                LyricsCacheEntry(
                    songId = touched.songId,
                    songTitle = touched.songTitle,
                    songArtist = touched.songArtist,
                    lrcText = lrcText,
                    lastPlayedAt = touched.lastPlayedAt
                )
            }
        }
    }

    /** 命中若干次后落盘一次索引（内存中的 lastPlayedAt 已即时更新，此处只做持久化节流） */
    private fun maybePersistTouch() {
        // G5-04：关闭「自动缓存歌词」时读路径不落盘（读仍可用，只是不刷新 LRU 时间）
        if (!LyricsCachePolicy.enabled) return
        if (touchCounter.incrementAndGet() >= TOUCH_SAVE_INTERVAL) {
            touchCounter.set(0)
            saveIndex()
        }
    }

    /**
     * 写入缓存条目（更新 lastPlayedAt 时间戳）。
     * 超过 [CacheLimits.current] 的歌词上限时自动淘汰最旧的条目，同时删除对应的 .lrc 文件。
     */
    fun put(entry: LyricsCacheEntry) {
        // 设置页「自动缓存歌词」关闭 ⇒ 不写盘（见 LyricsCachePolicy）。
        if (!LyricsCachePolicy.enabled) return
        synchronized(saveLock) {
            val now = System.currentTimeMillis()
            // 写 LRC 文件
            val lrcFile = lrcFile(entry.songId)
            // 修复（2026-09-22 审查）：正文与索引同口径原子写（tmp+rename），
            // 防写盘中途被杀产生半截 LRC（损坏条目 get 时 0 行 fall-through 自愈，但体验差）。
            val tmp = File(lrcFile.parentFile, lrcFile.name + ".tmp")
            tmp.writeText(entry.lrcText)
            if (!tmp.renameTo(lrcFile)) {
                lrcFile.writeText(entry.lrcText)
                tmp.delete()
            }
            // 更新索引
            index[entry.songId] = IndexEntry(
                songId = entry.songId,
                songTitle = entry.songTitle,
                songArtist = entry.songArtist,
                lastPlayedAt = now
            )
            if (index.size > CacheLimits.current().lyricsMaxEntries) evictOldest()
            saveIndex()
            AppLog.d(TAG, "put: '${entry.songTitle}' by ${entry.songArtist}, id=${entry.songId}")
        }
    }

    /** 删除某首歌的缓存（索引 + .lrc 文件） */
    fun remove(songId: String) {
        synchronized(saveLock) {
            index.remove(songId)
            lrcFile(songId).delete()
            saveIndex()
        }
    }

    /** 清除所有缓存 */
    fun clear() {
        synchronized(saveLock) {
            index.clear()
            lrcDir.listFiles()?.forEach { it.delete() }
            indexFile.delete()
            AppLog.d(TAG, "cleared all entries")
        }
    }

    /** 缓存条目数 */
    fun size(): Int = index.size

    /** 导出全部条目（供备份用，从 .lrc 文件读取完整数据） */
    fun exportAll(): List<LyricsCacheEntry> = synchronized(saveLock) {
        index.values.mapNotNull { idx ->
            val lrcFile = lrcFile(idx.songId)
            if (!lrcFile.exists()) {
                index.remove(idx.songId)
                null
            } else {
                LyricsCacheEntry(
                    songId = idx.songId,
                    songTitle = idx.songTitle,
                    songArtist = idx.songArtist,
                    lrcText = lrcFile.readText(),
                    lastPlayedAt = idx.lastPlayedAt
                )
            }
        }.also { saveIndex() }
    }

    /** 导入条目（恢复备份用，覆盖现有数据） */
    fun importAll(entries: List<LyricsCacheEntry>) {
        synchronized(saveLock) {
            index.clear()
            lrcDir.listFiles()?.forEach { it.delete() }
            entries.forEach { entry ->
                lrcFile(entry.songId).writeText(entry.lrcText)
                index[entry.songId] = IndexEntry(
                    songId = entry.songId,
                    songTitle = entry.songTitle,
                    songArtist = entry.songArtist,
                    lastPlayedAt = entry.lastPlayedAt
                )
            }
            if (index.size > CacheLimits.current().lyricsMaxEntries) evictOldest()
            saveIndex()
            AppLog.d(TAG, "importAll: ${index.size} entries")
        }
    }

    /** 显式维护入口：设置变更后按当前快照收敛，供设置/备份流程调用。 */
    fun maintain() = synchronized(saveLock) {
        if (index.size > CacheLimits.current().lyricsMaxEntries) {
            evictOldest()
            saveIndex()
        }
    }

    /** 淘汰最久未访问的条目，同时删除对应的 .lrc 文件 */
    private fun evictOldest() {
        val sorted = index.entries.sortedBy { it.value.lastPlayedAt }
        val toRemove = sorted.take(index.size - CacheLimits.current().lyricsMaxEntries)
        toRemove.forEach { (songId, _) ->
            lrcFile(songId).delete()
            index.remove(songId)
        }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${index.size}")
    }

    /** 加载索引文件 */
    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val json = indexFile.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, IndexEntry>>() {}.type
            val loaded = gson.fromJson<Map<String, IndexEntry>>(json, type) ?: return
            index.putAll(loaded)
            if (index.size > CacheLimits.current().lyricsMaxEntries) {
                evictOldest()
                saveIndex()
            }
            AppLog.d(TAG, "loaded ${index.size} entries")
        } catch (e: Exception) {
            AppLog.e(TAG, "loadIndex failed: ${e.message}", e)
        }
    }

    /**
     * F-5 / S02-05：整份缓存状态的互斥锁（Java monitor，**可重入**）。
     *
     * 只锁 saveIndex() 不够：index 变更、`.lrc` 文件删写、索引落盘必须作为一个原子单元，
     * 否则 put（切歌后台协程）与 clear（设置页）/ importAll（备份恢复）并发交错会出现
     * 「清掉的条目被写回复活」「index 里留下无对应文件的孤儿」。
     * 故把互斥范围从「仅 saveIndex」提升到 get/put/remove/clear/exportAll/importAll
     * 的读改写序列；saveIndex() 内复用同一把可重入锁，嵌套加锁不会死锁。
     */
    private val saveLock = Any()

    /** 保存索引文件（F-5：synchronized 互斥 + H-5 原子写盘语义保持） */
    private fun saveIndex() {
        synchronized(saveLock) {
            try {
                val json = gson.toJson(index.toMap())
                indexFile.writeText(json)
            } catch (e: Exception) {
                AppLog.e(TAG, "saveIndex failed: ${e.message}", e)
            }
        }
    }

    /** 获取 songId 对应的 .lrc 文件路径 */
    private fun lrcFile(songId: String): File {
        val safeName = songId.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(lrcDir, "$safeName.lrc")
    }
}
