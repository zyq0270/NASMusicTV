package com.nasmusic.tv.backend.network.mv

import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCacheEntry
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * MV 搜索管理器
 *
 * 统一入口：MainViewModel 切歌时调用 [searchMvFor]，UI 消费结果。
 * - 多源 fallback：依次尝试 [services] 中的每个源（v1 仅 Bilibili），任一返回非空即停
 * - 内存缓存：`ConcurrentHashMap` + TTL（直链有有效期，默认 45 分钟），命中直接返回
 * - 不缓存空结果（NotFound 不缓存，下次切歌/进入页面时重搜）
 *
 * 对应 docs/archive/mv-karaoke-feature-proposal.md Step 1
 */
class MvSearchManager(
    private val services: List<MvSearchService>,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val persistentCache: MvPersistentCache? = null
) {

    private val cache = ConcurrentHashMap<String, CachedResult>()

    /**
     * 按歌曲搜索 MV。
     *
     * 组合关键词为 `歌曲名 歌手`，先查缓存，未命中则依次访问每个源。
     *
     * @return 匹配到的 MV；null 表示未找到或全部源失败（UI 置暗）
     */
    suspend fun searchMvFor(
        song: Song,
        forceRefresh: Boolean = false,
        excludeBvids: Set<String> = emptySet(),
        minSimilarity: Float = 0.5f
    ): MvSearchResult? {
        val key = buildCacheKey(song.title, song.artist, song.networkSource)
        // 清理过期条目
        val now = System.currentTimeMillis()
        cache.entries.removeAll { (_, v) -> now - v.timestamp >= cacheTtlMs }

        if (!forceRefresh) {
            // 1. 内存缓存命中（45min TTL，含直链）
            cache[key]?.let {
                AppLog.d(TAG, "searchMvFor: memory cache hit for '$key'")
                return it.result
            }

            // 2. 持久缓存命中 -> 按 bvid 拿新鲜直链（省搜索请求）
            persistentCache?.get(song.id)?.let { entry ->
                AppLog.d(TAG, "searchMvFor: persistent cache hit, resolving bvid=${entry.bvid}")
                val mv = resolveMv(entry.bvid)
                if (mv != null) {
                    val result = MvSearchResult(mv, emptyList())
                    cache[key] = CachedResult(result, now)
                    return result
                }
                // resolveMv 失败（视频被删/风控）-> 删旧条目，继续走搜索
                AppLog.d(TAG, "searchMvFor: persistent bvid=${entry.bvid} resolve failed, removing")
                persistentCache.remove(song.id)
            }
        }

        // 3. 搜索 API（forceRefresh=true 时跳过缓存直接搜）
        return withContext(Dispatchers.IO) {
            for (svc in services) {
                try {
                    AppLog.d(TAG, "searchMvFor: trying service ${svc::class.java.simpleName} for '$key'")
                    val result = svc.searchMv(song.title, song.artist, excludeBvids, minSimilarity, song)
                    if (result != null) {
                        // 本地源（如百度网盘 MV）首搜只给稳定的 bvid，videoUrl 留空占位，
                        // 真实直链（dlink + access_token，有时效）必须按 bvid 现取。
                        // 在返回前补齐，避免把空 URL 交给播放器导致首播必失败一次、
                        // 只能靠 onMvPlaybackError 重搜兜底；解析不到则视该源不可用，继续 fallback。
                        val playableResult = if (result.mv.videoUrl.isBlank()) {
                            val resolved = resolveMv(result.mv.bvid)
                            if (resolved != null) {
                                AppLog.d(TAG, "searchMvFor: resolved blank videoUrl for bvid=${result.mv.bvid}")
                                result.copy(mv = resolved)
                            } else {
                                AppLog.d(TAG, "searchMvFor: blank videoUrl unresolved for bvid=${result.mv.bvid}, try next service")
                                null
                            }
                        } else result
                        if (playableResult != null) {
                            cache[key] = CachedResult(playableResult, now)
                            // 存 bvid 到持久缓存（不存直链，直链会过期）
                            persistentCache?.put(MvCacheEntry(
                                songId = song.id, songTitle = song.title, songArtist = song.artist,
                                bvid = playableResult.mv.bvid, mvTitle = playableResult.mv.title,
                                lastPlayedAt = now, playCount = 0
                            ))
                            AppLog.d(TAG, "searchMvFor: success for '$key' -> ${playableResult.mv.title} + ${playableResult.alternatives.size} alternatives")
                            return@withContext playableResult
                        }
                    }
                } catch (e: Exception) {
                    // 单个源失败不阻断其他源
                    AppLog.d(TAG, "searchMvFor: service ${svc::class.java.simpleName} error: ${e.message}", e)
                }
            }
            AppLog.d(TAG, "searchMvFor: no MV found for '$key'")
            null
        }
    }

    /**
     * 标记某首歌的 MV 播放完成（用户认可这个版本）。
     * 持久缓存中 playCount++ + 更新 bvid/lastPlayedAt，下次播这首歌优先用这个 bvid。
     */
    fun markCompleted(songId: String, songTitle: String, songArtist: String, bvid: String, mvTitle: String) {
        persistentCache?.markCompleted(songId, songTitle, songArtist, bvid, mvTitle)
    }

    /** 导出 MV 缓存（供备份用） */
    fun exportMvCache(): List<com.nasmusic.tv.data.model.MvCacheEntry> =
        persistentCache?.exportAll() ?: emptyList()

    /** 导入 MV 缓存（恢复备份用） */
    fun importMvCache(entries: List<com.nasmusic.tv.data.model.MvCacheEntry>) {
        persistentCache?.importAll(entries)
    }

    /** 清空 MV 持久缓存（供设置页"缓存管理"手动清除） */
    fun clearPersistentCache() {
        persistentCache?.clear()
    }

    /**
     * 按需解析指定 bvid 的直链（MTV 页面切换视频时调用）。
     * 不走缓存（直链有时效，切换时需重新解析）。
     */
    suspend fun resolveMv(bvid: String): MvInfo? {
        return withContext(Dispatchers.IO) {
            for (svc in services) {
                try {
                    val mv = svc.resolveMv(bvid)
                    if (mv != null) return@withContext mv
                } catch (e: Exception) {
                    AppLog.d(TAG, "resolveMv: service error: ${e.message}", e)
                }
            }
            null
        }
    }

    /**
     * 强制从非百度源搜索（MTV 页面「搜B站」按钮）。
     *
     * 当前 MV 来自百度（本地网盘文件）但用户不满意时调用：跳过 `source == "baidu"`
     * 的结果，迭代 services 取首个非百度源结果（B 站）。
     * 与 [searchMvFor] 的区别：不写缓存（用户主动触发，结果即时呈现，下次切歌仍走
     * 常规优先序）。
     */
    suspend fun searchBilibiliFallback(song: Song): MvSearchResult? {
        return withContext(Dispatchers.IO) {
            for (svc in services) {
                try {
                    val result = svc.searchMv(song.title, song.artist, emptySet(), 0.5f, song)
                    if (result != null && result.mv.source != "baidu") {
                        AppLog.d(TAG, "searchBilibiliFallback: found '${result.mv.title}' via ${svc::class.java.simpleName}")
                        return@withContext result
                    }
                } catch (e: Exception) {
                    AppLog.d(TAG, "searchBilibiliFallback: service ${svc::class.java.simpleName} error: ${e.message}", e)
                }
            }
            AppLog.d(TAG, "searchBilibiliFallback: no non-baidu MV found for '${song.title}'")
            null
        }
    }

    /** 清空缓存（播放失败时调用，强制重搜一次） */
    fun clearCache() = cache.clear()

    companion object {
        private const val TAG = "MvSearchManager"
        /** 缓存过期时间（毫秒），45 分钟 */
        private const val DEFAULT_CACHE_TTL_MS = 45 * 60 * 1000L

        /**
         * 构造缓存 key：`歌曲名|首唱者|来源`，小写归一化。
         *
         * 第三维 [source] 区分同一首歌在不同来源（NAS / 百度网盘 / meting）下的 MV：
         * 不同来源可能配到不同的 MV，混用一个 key 会串味。默认空串保持旧两参数调用
         * 与既有单测断言不变（仅在未提供来源时退化为旧格式）。
         *
         * 供单测验证 key 组合逻辑。
         */
        fun buildCacheKey(title: String, artist: String, source: String? = null): String {
            val base = "${title.trim().lowercase()}|${artist.trim().split('/', '、', ',', '，', '&')[0].trim().lowercase()}"
            return if (source.isNullOrBlank()) base else "$base|${source.trim().lowercase()}"
        }
    }

    private data class CachedResult(val result: MvSearchResult, val timestamp: Long)
}