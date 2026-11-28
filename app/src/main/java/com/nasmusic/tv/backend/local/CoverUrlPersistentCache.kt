package com.nasmusic.tv.backend.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.cache.CacheLimits
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 封面 URL 持久缓存（跨会话复用专辑/艺术家封面，避免重复网络搜索）
 *
 * 存储 key -> coverUrl 映射，只存稳定 URL（iTunes/网易云/酷狗等 HTTP 封面，非动态 dlink/APIC）。
 * 专辑 key 格式 `album:{id}`，艺术家 key 格式 `artist:{id}`，id 跨会话稳定（后端 GUID / local_* / baidu_*）。
 *
 * - 写入时机：`MainViewModel.resolveAlbumCoversAsync` / `resolveArtistCoversAsync` 解析到稳定 HTTP 封面 URL 时
 * - 读取时机：App 启动 / updateMergedData 时预加载到内存 resolvedAlbumCovers/resolvedArtistCovers，
 *   已有封面不再重复网络解析
 * - 淘汰：超过 [CacheLimits] 快照中的封面上限时按**写入时间**淘汰最早写入的（写入时间 = 最近一次 put；
 *   不随读取刷新，因此是 FIFO-by-write 而非严格 LRU——详见 [evictOldest]）
 *
 * 文件格式：JSON Map<String, String>，存 app filesDir/cover_url_cache.json
 */
class CoverUrlPersistentCache(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * P3-3（2026-09-16）：key → 最近一次写入时间（毫秒），仅用于淘汰排序。
     *
     * 原 [evictOldest] 直接在 ConcurrentHashMap 上 `keys.take(n)`——CHM 的迭代序与插入序
     * **无关**，实际等价于随机淘汰，与类注释宣称的"删最早写入的"不符：可能把刚刚解析到的
     * 封面立刻淘汰掉（下次启动又要重新联网解析），而很早的冷条目反而留下。
     */
    private val writtenAt = ConcurrentHashMap<String, Long>()
    private val gson = Gson()
    private val file by lazy { File(context.filesDir, "cover_url_cache.json") }

    @Volatile private var dirty = false
    @Volatile private var generation = 0L
    /** saveLock 保护的写入版本：put/maintain 每次递增，保存时快照，清 dirty 时做 CAS。 */
    private var mutationVersion = 0L
    /** 防抖时长；仅测试可缩短，生产保持 2 秒。 */
    internal var debounceMs: Long = 2_000L
    /** 仅测试注入：保存完成后、清 dirty 前触发，用于构造并发写入窗口。 */
    @Volatile internal var afterSaveHook: (() -> Unit)? = null
    /** 仅测试：是否仍有未落盘的写入（F-D01 的回归断言点）。 */
    internal val hasPendingWrites: Boolean get() = dirty
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private val saveLock = Any()

    companion object {
        private const val TAG = "CoverUrlPersistentCache"
        private const val KEY_ALBUM = "album:"
        private const val KEY_ARTIST = "artist:"
    }

    init {
        load()
    }

    fun getAlbumCover(albumId: String): String? = cache[KEY_ALBUM + albumId]

    fun getArtistCover(artistId: String): String? = cache[KEY_ARTIST + artistId]

    fun putAlbumCover(albumId: String, coverUrl: String) {
        put(KEY_ALBUM + albumId, coverUrl)
    }

    fun putArtistCover(artistId: String, coverUrl: String) {
        put(KEY_ARTIST + artistId, coverUrl)
    }

    private fun put(key: String, url: String) {
        if (url.isBlank()) return
        synchronized(saveLock) {
            cache[key] = url
            writtenAt[key] = nowMs()
            if (cache.size > CacheLimits.current().coverUrlMaxEntries) evictOldest()
            // F-D01：dirty 置位、版本递增与重排 job 必须在同一把锁内，否则并发 put
            // 会落在旧 job 的 saveLocked() 与清位之间，被无条件 dirty=false 吞掉。
            mutationVersion++
            dirty = true
            scheduleSaveLocked()
        }
    }

    /** 延迟落盘（默认 2 秒 debounce），避免频繁 put 时每次都写文件 */
    private fun scheduleSaveLocked() {
        saveJob?.cancel()
        val expectedGeneration = generation
        saveJob = saveScope.launch {
            delay(debounceMs)
            synchronized(saveLock) {
                if (generation != expectedGeneration || !dirty) return@synchronized
                // 快照版本后落盘；保存期间到达的 put 会递增版本并另排 job，
                // 此时不得清 dirty，否则新一轮 job 看到 dirty=false 会跳过写盘。
                val savedVersion = mutationVersion
                saveLocked()
                afterSaveHook?.invoke()
                if (mutationVersion == savedVersion && saveJob === coroutineContext[Job]) {
                    dirty = false
                }
            }
        }
    }

    /** 关闭缓存，取消延迟落盘协程并强制写盘。供 Application.onTerminate / onTrimMemory 调用 */
    fun close() {
        saveJob?.cancel()
        synchronized(saveLock) {
            if (dirty) {
                saveLocked()
                dirty = false
            }
        }
        saveScope.cancel()
    }

    /** 清空全部持久缓存（内存 + 磁盘文件）。供设置页"缓存管理"手动清除 */
    fun clear() {
        saveJob?.cancel()
        synchronized(saveLock) {
            generation++
            dirty = false
            cache.clear()
            writtenAt.clear()
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                AppLog.e(TAG, "clear failed: ${e.message}", e)
            }
        }
        AppLog.d(TAG, "cleared")
    }

    /** 导出全部条目（供备份用） */
    fun exportAll(): Map<String, String> = cache.toMap()

    /** 导入条目（恢复备份用，覆盖现有数据） */
    fun importAll(entries: Map<String, String>) {
        saveJob?.cancel()
        synchronized(saveLock) {
            generation++
            cache.clear()
            writtenAt.clear()
            val now = nowMs()
            entries.forEach { (k, v) ->
                if (v.isNotBlank()) {
                    cache[k] = v
                    writtenAt[k] = now
                }
            }
            if (cache.size > CacheLimits.current().coverUrlMaxEntries) evictOldest()
            saveLocked()
            dirty = false
        }
        AppLog.d(TAG, "importAll: ${cache.size} entries")
    }

    /** 显式维护入口：仅按 FIFO-by-write 收敛，不因读取改变顺序。 */
    fun maintain() {
        synchronized(saveLock) {
            if (cache.size > CacheLimits.current().coverUrlMaxEntries) {
                evictOldest()
                mutationVersion++
                dirty = true
                scheduleSaveLocked()
            }
        }
    }

    /**
     * 超限淘汰：按写入时间从早到晚删，直到回到 [CacheLimits.current] 的封面上限。
     *
     * P3-3 修复（2026-09-16）：原实现在 ConcurrentHashMap 上 `keys.take(n)`，
     * 迭代序与插入序无关 → 等价随机淘汰（详见 [writtenAt] 注释）。
     * 只在超限时触发，排序开销可忽略。
     */
    private fun evictOldest() {
        val overflow = cache.size - CacheLimits.current().coverUrlMaxEntries
        if (overflow <= 0) return
        val toRemove = writtenAt.entries
            .sortedBy { it.value }
            .take(overflow)
            .map { it.key }
        toRemove.forEach {
            cache.remove(it)
            writtenAt.remove(it)
        }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${cache.size}")
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded = gson.fromJson<Map<String, String>>(json, type) ?: return
            // S04-03：save() 已按 writtenAt 升序写出，文件顺序即真实写入顺序，
            // 按序赋递增时间戳，使「加载后立即超限淘汰」也按真实新旧顺序淘汰。
            // 注：Map 没有 forEachIndexed，必须走 entries。
            val base = nowMs()
            loaded.entries.forEachIndexed { i, e ->
                cache[e.key] = e.value
                writtenAt[e.key] = base + i
            }
            if (cache.size > CacheLimits.current().coverUrlMaxEntries) {
                evictOldest()
                save()
            }
            AppLog.d(TAG, "loaded ${cache.size} entries")
        } catch (e: Exception) {
            AppLog.e(TAG, "load failed: ${e.message}", e)
        }
    }

    private fun save() {
        synchronized(saveLock) { saveLocked() }
    }

    private fun saveLocked() {
        val tmpFile = File(file.path + ".tmp")
        try {
            // S04-03：ConcurrentHashMap.toMap() 是哈希桶序、不是写入时间序；
            // 按 writtenAt 升序写成 LinkedHashMap，让文件顺序 == 写入顺序。
            // 文件格式仍是 Map<String,String>，旧缓存文件可正常读取（Gson 反序列化为
            // LinkedHashMap，保插入序），无需迁移。
            val ordered = LinkedHashMap<String, String>(cache.size)
            cache.entries
                .sortedBy { writtenAt[it.key] ?: 0L }
                .forEach { ordered[it.key] = it.value }
            val json = gson.toJson(ordered)
            tmpFile.writeText(json)
            // 原子 rename：避免写文件中途崩溃导致缓存损坏
            if (!tmpFile.renameTo(file)) {
                // L13：rename 只在「跨卷 / 目标被占用」时失败。原实现这里直接
                // `file.writeText(json)` —— 等于放弃原子性（写一半被杀就损坏整份缓存），
                // 而且 `.tmp` 上残留的旧内容从此再不会被清理，下次 save 又被覆盖但没人管。
                // 改成「写第二份临时文件再 rename」重试一次；仍然失败就放弃本次保存
                //（缓存可重建，宁可不写也不能写坏），并在 finally 里清掉两份临时文件。
                val retryTmp = File(file.path + ".tmp2")
                try {
                    retryTmp.writeText(json)
                    if (!retryTmp.renameTo(file)) {
                        AppLog.w(TAG, "save: rename failed twice, keeping previous cache file")
                    }
                } finally {
                    if (retryTmp.exists()) retryTmp.delete()
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "save failed: ${e.message}", e)
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }
}