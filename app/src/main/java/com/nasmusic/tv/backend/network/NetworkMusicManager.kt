package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.cache.CacheLimits
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.player.PlayUrlPrefetchScheduler
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 纯预刷结果；失败时 [url] 可能仍保留未过期旧值，绝不表示已切换播放。 */
data class PrefetchResult(
    val status: PrefetchStatus,
    val url: String? = null,
)

enum class PrefetchStatus {
    UPDATED,
    ALREADY_FRESH,
    FAILED_RETRYABLE_OFFLINE,
    EXPIRED_URL,
    NOT_NETWORK_SONG,
    SERVICE_UNAVAILABLE,
}

/**
 * 网络音乐管理器
 *
 * 多源路由层：管理多个 [NetworkMusicService] 实现，按默认源路由请求。
 *
 * 设计要点：
 * - search 采用 fallback 策略：默认源失败时依次尝试其他源
 * - resolvePlayUrl/resolveLyrics/resolveCoverUrl 不 fallback，按 song.networkSource 精确路由
 * - 默认源可通过 [setDefaultSource] 动态切换，由设置页面驱动
 *
 * v2.2.0 适配：手动 DI（在 NasMusicApp.onCreate 初始化），不使用 getInstance()
 */
class NetworkMusicManager(
    services: Map<String, NetworkMusicService>,
    private val defaultSourceProvider: () -> String,
    /**
     * 全局默认音质档位（同步读取）。由 NasMusicApp 注入
     * `{ appPreferences.getQualityTierSync() }`，与既有 provider 注入风格一致。
     */
    private val qualityTierProvider: () -> Int = { QualityTiers.AUTO },
    /**
     * 单曲音质覆盖查询（多码率方案 §2.3 两级模型）。
     * 返回 null 表示该曲无覆盖、回退全局默认。由 NasMusicApp 注入，
     * 避免 backend/network 反向依赖 data/prefs（见方案 §6.3）。
     */
    private val songQualityOverrideProvider: ((String, String) -> Int?)? = null,
    /** 可注入时钟用于 TTL 与旧 URL 保留测试；生产默认墙钟。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    companion object {
        private const val TAG = "NetworkMusicManager"
        /** 播放链接缓存过期时间（毫秒），5 分钟 */
        private const val PLAY_URL_CACHE_TTL_MS = 5 * 60 * 1000L
        // 艺人全量方案 §3.8 第 1 步：确认过的"假 302"负面判定，与直链缓存同 TTL（避免同一首反复探测）
        private const val DEAD_PROBE_TTL_MS = PLAY_URL_CACHE_TTL_MS

        /**
         * 跨源 fallback 的**稳定**尝试顺序。
         *
         * 只影响 `search` / `searchCoverUrl` / 跨源替换的尝试次序，不改变
         * `resolvePlayUrl` 按 `networkSource` 的精确路由。
         * ⚠️ 值必须与各服务的 `sourceId` 一致（`NetworkSongId.METING_SOURCE` /
         * `BaiduNetdiskService.sourceId` / `JamendoService.sourceId`）；
         * 未列出的新源排在最后并按 sourceId 字典序，保证顺序确定。
         */
        private val FALLBACK_SOURCE_ORDER = listOf(NetworkSongId.METING_SOURCE, "baidu", "jamendo")
    }

    /**
     * 内部可变 services Map（支持运行时注册/注销，例如百度网盘开关切换）。
     * 构造时拷贝传入的不可变 Map。
     */
    // 修复：原为普通 HashMap，主线程 register/unregister 与 IO 线程 resolvePlayUrl/search
    // 并发读写可能抛 ConcurrentModificationException。改用 ConcurrentHashMap。
    private val services: MutableMap<String, NetworkMusicService> =
        java.util.concurrent.ConcurrentHashMap(services)

    /**
     * 播放链接缓存条目
     * @param url 播放链接
     * @param timestamp 缓存时间戳（毫秒）
     */
    private data class CachedPlayUrl(
        val url: String,
        val timestamp: Long,
        /** 实际命中档位（降级时 ≠ 请求档位），随缓存一起复用避免重复降级探测 */
        val actualQuality: Int = QualityTiers.AUTO
    )

    /** 播放链接内存缓存：cacheKey → CachedPlayUrl（线程安全，resolvePlayUrl 在 IO 线程并发访问） */
    private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()

    /**
     * 负面探测缓存：cacheKey → 判定时间戳（§3.8 第 1 步）。
     * key 与 [playUrlKey] 同构（含档位）→ **切档位必然重探**，对齐验收 13。
     */
    private val deadProbeCache = ConcurrentHashMap<String, Long>()

    /** 每 cacheKey 一个在途标记；等待者复用同一次请求，tick 不会重叠。 */
    private val prefetchInFlight = ConcurrentHashMap<String, CompletableDeferred<PrefetchResult>>()

    /**
     * 播放直链缓存 key：`networkSource:networkPlatform:networkId:quality`。
     *
     * 音质参与缓存键，切档位不会串用旧直链（修 G1）。
     * ⚠️ G3-02：**平台也必须参与** —— 同一 `networkSource=meting` 下各平台的 networkId
     * 是平台自己的数字 id，netease/kuwo/migu 撞号即会命中同一条缓存（播放成另一首），
     * 负面探测也会串台把歌判死。取链本身已按 `song.networkPlatform` 快照（:517）。
     * 注意此处用冒号分隔，与下载主键 `ntwk_<source>_<id>:q<quality>`
     * （下划线 + 后缀）属于两个独立命名空间，不要试图统一。
     */
    private fun playUrlKey(song: Song, quality: Int): String =
        "${song.networkSource}:${song.networkPlatform ?: "-"}:${song.networkId}:$quality"

    /**
     * 有效档位解析（两级模型）：单曲覆盖优先，回退全局默认。
     */
    /**
     * 有效档位解析（两级模型）：单曲覆盖优先，回退全局默认。
     *
     * 供播放链路（PlayerViewModel §3.6）与下载面板查询"当前该曲用哪个档"。
     */
    fun effectiveQualityOf(song: Song): Int = effectiveQuality(song)

    private fun effectiveQuality(song: Song): Int {
        val src = song.networkSource ?: return qualityTierProvider()
        val id = song.networkId ?: return qualityTierProvider()
        val override = songQualityOverrideProvider?.invoke(src, id)
        return override ?: qualityTierProvider()
    }

    /** 清空全部播放直链缓存（含负面探测缓存）。档位变化时由 AppPreferences.setQualityTier() 调用（修 G5）。 */
    fun clearPlayUrlCache() {
        val size = playUrlCache.size
        val deadSize = deadProbeCache.size
        playUrlCache.clear()
        deadProbeCache.clear()
        if (size > 0 || deadSize > 0) AppLog.d(TAG, "clearPlayUrlCache: cleared $size url + $deadSize dead entries")
    }

    /** 设置变更/显式维护时收敛；播放 URL 与负面探测共享同一数量预算。 */
    fun maintainPlayUrlCache() {
        maintainPlayUrlCache(nowMs())
    }

    private fun maintainPlayUrlCache(now: Long) {
        playUrlCache.entries.removeAll { (_, v) -> now - v.timestamp >= PLAY_URL_CACHE_TTL_MS }
        deadProbeCache.entries.removeAll { (_, ts) -> now - ts >= DEAD_PROBE_TTL_MS }
        val maxEntries = CacheLimits.current().playUrlMaxEntries
        val overflow = playUrlCache.size + deadProbeCache.size - maxEntries
        if (overflow <= 0) return
        val oldest = playUrlCache.entries.map { Triple(it.key, it.value.timestamp, 0) } +
            deadProbeCache.entries.map { Triple(it.key, it.value, 1) }
        oldest.sortedWith(compareBy<Triple<String, Long, Int>> { it.second }.thenBy { it.first }.thenBy { it.third })
            .take(overflow)
            .forEach { (key, _, kind) ->
                if (kind == 0) playUrlCache.remove(key) else deadProbeCache.remove(key)
            }
    }

    /**
     * 该曲是否被负面探测缓存钉住（判据确认的假 302）。只读，⛔ 不写、不清。
     *
     * key 计算必须与 [resolvePlayUrlDetailed] 用同一条 `playUrlKey(song, effectiveQuality(song))` 路径，
     * 手拼字符串会让调用方永远读不到判定（静默失效）。
     * 非网络歌曲一律 false——`imported_` stub 补全前 isNetworkSong 为 false，不得因此跳过重试。
     */
    fun isProbedDead(song: Song): Boolean =
        song.isNetworkSong && deadProbeCache.containsKey(playUrlKey(song, effectiveQuality(song)))

    /**
     * 获取当前默认源 ID。
     *
     * ⚠️ **这里才是「默认源指向不存在的源」的修复点**（方案 §7.5）：
     * `AppPreferences` 只存字符串，它不知道运行时注册了哪些源 —— 老用户 DataStore 里的
     * `"alapi"` / `"jiosaavn"`（枚举时代留下的 key）会原样流到这里。
     * 原样透传的后果是 `services[defaultSource]` 为 null ⇒ `getPlaylist` / `findArtist`
     * 全返回 null，而搜索照常（只是退化成 Map 迭代顺序）—— 「搜索能用但歌单页空白」。
     *
     * ⛔ 不能简单回退 `"meting"`：meting 也可能被用户关掉（那时它不在 `services` 里）。
     * ⛔ 也不要把它挪进 `AppPreferences`：那是把「运行时注册表」的知识泄漏进持久化层。
     */
    val defaultSource: String
        get() {
            val raw = defaultSourceProvider()
            return if (services.containsKey(raw)) raw
            else services.keys.firstOrNull() ?: raw
        }

    /**
     * 搜索歌曲
     *
     * 策略：先查默认源；若默认源返回空或异常，依次尝试其他源（fallback）。
     * 任一源返回非空结果即返回，不再尝试后续源。
     *
     * @param nativeFirst Q2 的「网易优先」，**只有统一搜索入口**传 true（其余调用点必须保持端点语义）
     */
    suspend fun search(keyword: String, nativeFirst: Boolean = false): List<Song> = withContext(Dispatchers.IO) {
        val currentDefault = defaultSource
        AppLog.d("MetingDiag", "=== NetworkMusicManager.search === keyword='$keyword' defaultSource='$currentDefault'")
        if (keyword.isBlank()) return@withContext emptyList()

        val ordered = orderedServices()
        AppLog.d("MetingDiag", "search: orderedServices=${ordered.map { it.sourceId }}")
        for (svc in ordered) {
            try {
                AppLog.d("MetingDiag", "search: trying source=${svc.sourceId}")
                val results = if (nativeFirst) svc.searchNativeFirst(keyword) else svc.search(keyword)
                if (results.isNotEmpty()) {
                    AppLog.d("MetingDiag", "search '$keyword' hit source=${svc.sourceId} count=${results.size}")
                    return@withContext results
                }
                AppLog.d("MetingDiag", "search '$keyword' empty on source=${svc.sourceId}, trying next")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.d("MetingDiag", "search '$keyword' error on source=${svc.sourceId}: ${e.message}", e)
            }
        }
        AppLog.wThrottled("MetingDiag", "search '$keyword' no results from any source")
        emptyList()
    }

    /**
     * 解析播放链接
     *
     * 按 song.networkSource 精确路由，不 fallback。
     * 网络歌曲的 streamUrl 不持久化，每次播放实时解析。
     *
     * 缓存策略：同一歌曲 5 分钟内复用缓存的播放链接，避免重复网络请求。
     * 播放链接有时效性，缓存过期后重新解析。
     *
     * @param forceRefresh 为 true 时强制绕过缓存、走完整降级链重新解析。
     *        播放失败重试路径必须传 true——否则会命中「已过期但仍未到 5 分钟 TTL」
     *        的旧缓存，导致重试永远拿到失效链接、连锁跳过后续歌曲。
     */
    /** 运行时查询某源是否已注册（供播放前自愈判断） */
    fun isServiceRegistered(sourceId: String): Boolean = services.containsKey(sourceId)

    suspend fun resolvePlayUrl(song: Song, forceRefresh: Boolean = false): String? {
        if (!song.isNetworkSong) return song.streamUrl
        return resolvePlayUrlDetailed(song, effectiveQuality(song), forceRefresh).url
    }

    /**
     * 解析播放链接（指定档位 + 降级信号）。
     *
     * 按 song.networkSource 精确路由，不 fallback。
     * 网络歌曲的 streamUrl 不持久化，每次播放实时解析。
     *
     * 缓存策略：同一歌曲 × 同一档位 5 分钟内复用缓存，避免重复网络请求。
     * 切档位时 key 不同，因此不会命中旧档位直链（修 G1）。
     *
     * @param quality 音质档位（[QualityTiers] 常量）
     * @param forceRefresh 为 true 时强制绕过缓存、走完整降级链重新解析。
     *        播放失败重试路径、档位切换路径必须传 true。
     * @return [ResolveResult]，url 为 null 表示无源可降；
     *         actualQuality ≠ quality 表示发生了静默降级
     */
    suspend fun resolvePlayUrlDetailed(
        song: Song,
        quality: Int,
        forceRefresh: Boolean = false
    ): ResolveResult {
        if (!song.isNetworkSong) return ResolveResult(song.streamUrl, quality)
        val src = song.networkSource ?: run {
            AppLog.e(TAG, "resolvePlayUrl: networkSource 为 null（song id=${song.id} title=${song.title}），无法路由")
            return ResolveResult.failure(quality)
        }
        if (!services.containsKey(src)) {
            AppLog.w(TAG, "resolvePlayUrl: 未注册源 source=$src（song id=${song.id}）；已注册源=${services.keys}")
            return ResolveResult.failure(quality)
        }
        AppLog.d(TAG, "resolvePlayUrl: 路由到 source=$src (song id=${song.id} networkId=${song.networkId}) quality=$quality forceRefresh=$forceRefresh")

        return resolvePlayUrlInternal(song, quality, forceRefresh, preserveOldUrl = false)
    }

    /**
     * 纯预刷：只解析并更新 URL 缓存，不修改 Song、队列、播放器或播放状态。
     *
     * 与 [forceRefresh] 播放重试不同，这里在请求完成前不删除旧值；失败且旧值未过期
     * 时返回旧值供现有播放入口继续使用。每个 key 的真实网络请求 single-flight。
     */
    suspend fun prefetchPlayUrl(song: Song): PrefetchResult {
        if (!song.isNetworkSong) return PrefetchResult(PrefetchStatus.NOT_NETWORK_SONG, song.streamUrl)
        val src = song.networkSource
            ?: return PrefetchResult(PrefetchStatus.SERVICE_UNAVAILABLE)
        if (!services.containsKey(src)) return PrefetchResult(PrefetchStatus.SERVICE_UNAVAILABLE)
        val quality = effectiveQuality(song)
        val cacheKey = playUrlKey(song, quality)

        while (true) {
            var created = false
            val gate = synchronized(prefetchInFlight) {
                prefetchInFlight[cacheKey] ?: CompletableDeferred<PrefetchResult>().also {
                    prefetchInFlight[cacheKey] = it
                    created = true
                }
            }
            if (!created) return gate.await()

            var outcome = PrefetchResult(PrefetchStatus.FAILED_RETRYABLE_OFFLINE)
            return try {
                val before = playUrlCache[cacheKey]
                val now = nowMs()
                if (before != null && !PlayUrlPrefetchScheduler.shouldRefresh(before.timestamp, now)) {
                    PrefetchResult(PrefetchStatus.ALREADY_FRESH, before.url)
                } else {
                    val result = resolvePlayUrlInternal(song, quality, forceRefresh = true, preserveOldUrl = true)
                    val after = playUrlCache[cacheKey]
                    val refreshed = result.isSuccess && after != null && after.timestamp != before?.timestamp
                    val completedAt = nowMs()
                    when {
                        refreshed -> PrefetchResult(PrefetchStatus.UPDATED, result.url)
                        before != null && completedAt - before.timestamp < PLAY_URL_CACHE_TTL_MS ->
                            PrefetchResult(PrefetchStatus.FAILED_RETRYABLE_OFFLINE, before.url)
                        before != null -> PrefetchResult(PrefetchStatus.EXPIRED_URL)
                        else -> PrefetchResult(PrefetchStatus.FAILED_RETRYABLE_OFFLINE)
                    }
                }.also { outcome = it }
            } finally {
                gate.complete(outcome)
                synchronized(prefetchInFlight) { prefetchInFlight.remove(cacheKey) }
            }
        }
    }

    private suspend fun resolvePlayUrlInternal(
        song: Song,
        quality: Int,
        forceRefresh: Boolean,
        preserveOldUrl: Boolean,
    ): ResolveResult {
        val src = song.networkSource ?: return ResolveResult.failure(quality)
        val svc = services[src] ?: return ResolveResult.failure(quality)
        val cacheKey = playUrlKey(song, quality)
        val cached = playUrlCache[cacheKey]
        val now = nowMs()
        maintainPlayUrlCache(now)

        if (!forceRefresh) {
            if (cached != null && now - cached.timestamp < PLAY_URL_CACHE_TTL_MS) {
                return ResolveResult(cached.url, cached.actualQuality)
            }
            if (deadProbeCache.containsKey(cacheKey)) {
                return ResolveResult.failure(quality, probedDead = true)
            }
        } else if (!preserveOldUrl) {
            // 播放重试保持原语义：先删旧值与负面判定，确保完整重探。
            playUrlCache.remove(cacheKey)
            deadProbeCache.remove(cacheKey)
        }

        return try {
            val result = svc.resolvePlayUrlDetailed(song, quality)
            val completedAt = nowMs()
            val stillRegistered = services[src] === svc
            if (result.isSuccess) {
                if (stillRegistered) {
                    playUrlCache[cacheKey] = CachedPlayUrl(result.url!!, completedAt, result.actualQuality)
                    maintainPlayUrlCache(completedAt)
                    AppLog.d(TAG, "resolvePlayUrl: cached new url for key=$cacheKey actual=${result.actualQuality}")
                }
            } else if (preserveOldUrl && cached != null && completedAt - cached.timestamp < PLAY_URL_CACHE_TTL_MS) {
                // 纯预刷失败不得销毁仍可播放的旧 URL。
                return ResolveResult(cached.url, cached.actualQuality)
            } else {
                playUrlCache.remove(cacheKey)
                if (result.probedDead && stillRegistered) {
                    deadProbeCache[cacheKey] = completedAt
                    maintainPlayUrlCache(completedAt)
                }
            }
            result
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.w(TAG, "resolvePlayUrl error: ${e.message}", e)
            if (preserveOldUrl && cached != null && nowMs() - cached.timestamp < PLAY_URL_CACHE_TTL_MS) {
                ResolveResult(cached.url, cached.actualQuality)
            } else {
                playUrlCache.remove(cacheKey)
                ResolveResult.failure(quality)
            }
        }
    }

    /**
     * 获取歌词
     *
     * 按 song.networkSource 精确路由，不 fallback。
     */
    suspend fun resolveLyrics(song: Song): String? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        return try {
            svc.resolveLyrics(song)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.w(TAG, "resolveLyrics error: ${e.message}", e)
            null
        }
    }

    /**
     * 获取封面 URL
     *
     * 按 song.networkSource 精确路由。
     * 若服务返回 null，调用方应使用 song.coverUrl。
     */
    suspend fun resolveCoverUrl(song: Song): String? {
        if (!song.isNetworkSong) return null  // consistent with resolveLyrics; caller falls back to song.coverUrl
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        return try {
            svc.resolveCoverUrl(song)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.w(TAG, "resolveCoverUrl error: ${e.message}", e)
            null
        }
    }

    /**
     * 按标题+艺术家搜索网络封面 URL。
     * 用于 NAS 歌曲切换到"在线歌词"来源时，联动获取网络封面加入轮播候选列表。
     * 仅 MetingApiService 实现该方法；返回 null 表示未找到。
     */
    suspend fun searchCoverUrl(title: String, artist: String): String? {
        // 依次尝试各服务，第一个返回非 null 即采用
        for (svc in orderedServices()) {
            val url = try {
                svc.searchCoverUrl(title, artist)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w(TAG, "searchCoverUrl error: ${e.message}", e)
                null
            }
            if (url != null) return url
        }
        return null
    }

    /**
     * 获取播放列表歌曲列表。
     *
     * 按默认源路由，不 fallback — 播放列表 ID 是来源特定的（如网易云歌单）。
     */
    suspend fun getPlaylist(playlistId: String): List<Song> {
        val svc = services[defaultSource] ?: run {
            AppLog.w(TAG, "getPlaylist: no service for defaultSource=$defaultSource")
            return emptyList()
        }
        return try {
            svc.getPlaylist(playlistId)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.w(TAG, "getPlaylist error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 艺人发现：按**默认源**路由，不 fallback。
     *
     * 与 resolvePlayUrl/resolveLyrics 同语义（fallback 只属于 [search]）：
     * 艺人 id 是来源特定的，拿 plain 源的搜索结果去补一个网易 artistId 会串人。
     */
    suspend fun findArtist(name: String): NetworkArtistInfo? {
        if (name.isBlank()) return null
        val svc = services[defaultSource] ?: return null
        return try {
            svc.findArtist(name)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.e(TAG, "findArtist error: ${e.message}", e)
            null
        }
    }

    /** 艺人作品分页：按默认源路由，不 fallback */
    suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int = 100): ArtistSongsPage? {
        val svc = services[defaultSource] ?: return null
        return try {
            svc.getArtistSongs(artistId, offset, limit)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.e(TAG, "getArtistSongs error: ${e.message}", e)
            null
        }
    }

    /**
     * 通过组合关键词搜索（用于多维度浏览）。
     *
     * 将非空的多个关键词用空格拼接后调用 [search]。
     * 若关键词列表为空，返回空列表。
     */
    suspend fun searchByKeywords(keywords: List<String>): List<Song> {
        val query = keywords.filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .trim()
        if (query.isBlank()) return emptyList()
        AppLog.d("MetingDiag", "searchByKeywords: combined='$query'")
        return search(query)
    }

    /**
     * 获取所有已注册源 ID（用于设置页面展示可选项）
     */
    fun availableSources(): List<String> = services.keys.toList()

    /**
     * 运行时注册网络音乐源（如百度网盘开关开启后）。
     * 已存在同 sourceId 则覆盖。
     */
    fun registerService(service: NetworkMusicService) {
        services[service.sourceId] = service
        AppLog.d(TAG, "registerService: ${service.sourceId} (total=${services.size})")
    }

    /**
     * 运行时注销网络音乐源（如百度网盘开关关闭后）。
     */
    fun unregisterService(sourceId: String) {
        services.remove(sourceId)
        // 清理该源相关的播放缓存。修复（2026-09-22 审查）：playUrlCache 的 key 由
        // playUrlKey() 生成为 "<networkSource>:<networkId>:<quality>"，原过滤串
        // "ntwk_<sourceId>_" 是歌曲 id 的格式，永远匹配不上（注销后旧直链残留至 TTL）。
        playUrlCache.entries.removeAll { it.key.startsWith("${sourceId}:") }
        // M21：deadProbeCache 的 key 同口径（playUrlKey），必须一起清 ——
        // 否则重新注册同一个源之后，残留的 DEAD 判定（TTL 5 分钟）会把本可播的歌
        // 静默判死，取消再打开开关也不恢复。
        val deadCleared = deadProbeCache.keys.count { it.startsWith("${sourceId}:") }
        deadProbeCache.keys.removeAll { it.startsWith("${sourceId}:") }
        AppLog.d(TAG, "unregisterService: $sourceId (total=${services.size}, deadCleared=$deadCleared)")
    }

    /**
     * 构造按优先级排序的服务列表：**修复后的**默认源在前，其余按 [FALLBACK_SOURCE_ORDER]
     * （Meting → 百度 → Jamendo）稳定排序，未列出的源按 sourceId 字典序排在最后。
     *
     * ⚠️ 必须用 [defaultSource] 而不是原始 `defaultSourceProvider()`：
     * 持久化值可能指向已关闭 / 已删除的源，用原值会让 `defSvc == null`，
     * 退化回 `ConcurrentHashMap` 的迭代顺序（非确定），与 `getPlaylist` /
     * `findArtist` 使用的修复值不一致——同一会话里两条链路可能选中不同源。
     */
    private fun orderedServices(): List<NetworkMusicService> {
        if (services.isEmpty()) return emptyList()
        val def = defaultSource
        val orderedKeys = ArrayList<String>(services.size)
        orderedKeys.add(def)
        FALLBACK_SOURCE_ORDER.forEach { key ->
            if (key != def && services.containsKey(key)) orderedKeys.add(key)
        }
        services.keys
            .filter { it != def && it !in FALLBACK_SOURCE_ORDER }
            .sorted()
            .forEach { orderedKeys.add(it) }
        return orderedKeys.mapNotNull { services[it] }
    }

    /**
     * 跨源降级解析结果。
     *
     * @param replacement 替代歌曲（来自其他网络源，带已解析的 playUrl）
     * @param playUrl 可直接播放的链接
     * @param sourceId 命中源 ID（用于日志/提示）
     */
    data class CrossSourceResult(
        val replacement: Song,
        val playUrl: String,
        val sourceId: String
    )

    /**
     * 带跨源降级的播放链接解析。
     *
     * 策略（从轻到重）：
     * 1. 先按原源精确路由解析（含 forceRefresh 语义）。
     * 2. 原源解析失败时，按 [orderedServices] 顺序遍历**排除原源之外**的其他已注册源，
     *    用 `title + artist` 重新搜索，并对候选结果逐个做可播校验
     *    （`resolvePlayUrl` 非空才算可播，避免「搜到但依然播不了」），
     *    取第一个可播的替代曲返回。
     * 3. 全部源失败返回 null（由调用方决定跳下一首）。
     *
     * @param song 原歌曲（networkSource 即其来源）
     * @param forceRefresh 原源解析时是否强制绕过缓存
     * @return 跨源替代结果；无需降级或降级失败返回 null
     */
    suspend fun resolvePlayUrlWithCrossSourceFallback(
        song: Song,
        forceRefresh: Boolean = false
    ): CrossSourceResult? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null

        // 原源解析
        val originalUrl = resolvePlayUrl(song, forceRefresh)
        if (!originalUrl.isNullOrBlank()) return null  // 原源可播，无需降级

        AppLog.d(TAG, "cross-source: 原源 source=$src 解析失败，尝试其他源重搜 title='${song.title}' artist='${song.artist}'")

        // 用 title+artist 构造搜索关键词（artist 为空时仅用 title）
        val keywords = listOf(song.title, song.artist).filter { it.isNotBlank() }
        if (keywords.isEmpty()) {
            AppLog.d(TAG, "cross-source: title 与 artist 均为空，无法重搜")
            return null
        }
        val query = keywords.joinToString(" ").trim()

        // 按优先级顺序尝试其他源（排除原源）
        for (svc in orderedServices()) {
            if (svc.sourceId == src) continue  // 跳过原源
            if (!services.containsKey(svc.sourceId)) continue
            AppLog.d(TAG, "cross-source: 尝试候选源 ${svc.sourceId} 搜索 '$query'")
            val candidates = try {
                svc.search(query)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.d(TAG, "cross-source: ${svc.sourceId} 搜索失败: ${e.message}")
                emptyList()
            }
            if (candidates.isEmpty()) {
                AppLog.d(TAG, "cross-source: ${svc.sourceId} 无搜索结果")
                continue
            }

            // 相关性闸门（修复「跨源兜底播成无关歌」）：
            // 原实现直接取“第一个能解析出 URL 的搜索结果”，而公共端点的模糊搜索
            // 很容易把无关歌曲排在前面——自动替换并播放就是事故。
            // 只接受标题匹配的候选（复用 CandidatePicker.titleMatches：归一化全等，
            // 或“晴天MV / 晴天2”这类拉丁/数字版本后缀）；歌手不设硬闸门
            // （跨源本来就是“同名另一演唱者”的兜底），但同歌手候选优先。
            val relevant = candidates.filter { candidate ->
                candidate.id != song.id && CandidatePicker.titleMatches(candidate.title, song.title)
            }
            if (relevant.isEmpty()) {
                AppLog.d(TAG, "cross-source: ${svc.sourceId} 无标题匹配候选（raw=${candidates.size}）")
                continue
            }
            val originalArtist = ArtistSplitter.normalizeKey(song.artist)
            val orderedCandidates = relevant.sortedByDescending { candidate ->
                originalArtist.isNotEmpty() &&
                    ArtistSplitter.normalizeKey(candidate.artist) == originalArtist
            }

            // 对候选逐个做可播校验，取第一个可播的
            for (candidate in orderedCandidates) {
                val candidateUrl = try {
                    svc.resolvePlayUrl(candidate)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.d(TAG, "cross-source: ${svc.sourceId} 候选 ${candidate.title} 解析异常: ${e.message}")
                    null
                }
                if (!candidateUrl.isNullOrBlank()) {
                    // 把解析好的 URL 写回替代曲，保证替换即可播
                    val replacement = candidate.copy(streamUrl = candidateUrl)
                    AppLog.d(TAG, "cross-source: 命中源 ${svc.sourceId} 替代 '${candidate.title}' -> $candidateUrl")
                    return CrossSourceResult(replacement, candidateUrl, svc.sourceId)
                }
            }
            AppLog.d(TAG, "cross-source: ${svc.sourceId} 候选均不可播")
        }

        AppLog.w(TAG, "cross-source: 所有源均无可播替代曲")
        return null
    }

    /**
     * 同源重搜替换（艺人全量方案 §3.8 第 2 步）。
     *
     * 与 [resolvePlayUrlWithCrossSourceFallback] 的区别是方向：这里**只在原注册源内**取候选
     * （`services[networkSource]`，不走 [orderedServices] 的 fallback），语义是
     * "同一首歌的另一个版本"，而不是"另一位演唱者的同名歌"。
     *
     * 请求量：1 次搜索 + ≤3 次解析（§6 验收 16 ③），解析全部 `forceRefresh=true`
     * ——Task 5 的负面缓存会把原曲钉成 DEAD，候选若不强制刷新就可能整批复用旧判定。
     *
     * @return 替代曲与已解析直链；无可播替代 / 未注册源返回 null
     */
    suspend fun resolveSameSourceReplacement(song: Song): CandidatePicker.Replacement? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        val quality = effectiveQuality(song)
        return CandidatePicker(
            search = { query -> svc.search(query) },
            resolve = { candidate -> resolvePlayUrlDetailed(candidate, quality, forceRefresh = true) }
        ).pick(title = song.title, artist = song.artist, excludeId = song.id)
    }
}
