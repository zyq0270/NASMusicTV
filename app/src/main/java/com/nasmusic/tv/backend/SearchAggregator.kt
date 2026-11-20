package com.nasmusic.tv.backend

import com.nasmusic.tv.backend.local.LocalMusicRepository
import com.nasmusic.tv.backend.network.JamendoService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskService
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.SearchAggregatorResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongWithPinyin
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.util.SearchQuery
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 搜索结果过滤模式
 *
 * - [PRECISE]：精细过滤——只保留标题/歌手/文件名包含关键词的歌曲（搜索页用）
 * - [NONE]：不过滤——各源返回什么就展示什么，仅做同名同歌手去重（发现页用）
 */
enum class FilterMode {
    PRECISE,
    NONE
}

/**
 * 搜索维度类型——决定搜索匹配的字段范围
 *
 * - [SONG_NAME_OR_ARTIST]：歌名 OR 艺术家（搜索页用，最宽）
 * - [ALBUM]：专辑名 OR 专辑艺术家（专辑页用）
 * - [ARTIST]：艺术家名（艺术家页用）
 * - [SONG_NAME_ONLY]：仅歌名（歌曲页用，不含艺术家）
 */
enum class SearchType {
    SONG_NAME_OR_ARTIST,
    ALBUM,
    ARTIST,
    SONG_NAME_ONLY
}

/**
 * 跨源搜索聚合器
 *
 * 并行搜索 NAS、网络音乐、百度网盘、Jamendo、本地音乐五个数据源，
 * 合并去重后返回统一结果。单个源超时或异常不影响其他源。
 *
     * 超时策略：NAS 15s、网络音乐 8s、百度网盘 8s、Jamendo 5s、本地 2s
     */
    class SearchAggregator(
        private val backendRegistry: BackendRegistry?,
        private val networkMusicManager: NetworkMusicManager?,
        private val baiduService: BaiduNetdiskService?,
        private val jamendoService: JamendoService?,
        private val localMusicRepository: LocalMusicRepository? = null,
        private val isTVDevice: Boolean = false,
        /**
         * M7（§5.2）：本地音乐总开关。**suspend provider** 形态（D4：不能传构造时
         * 快照，开关翻转后下一次搜索立即生效）；每次搜索实时读 DataStore
         * （有内存缓存，开销可忽略）。
         */
        private val localMusicEnabled: suspend () -> Boolean = { true },
        /**
         * v2.39.8：NAS 服务器作为音乐源的开关（用户 2026-10-28：NAS 缺独立开关）。
         *
         * 与 [localMusicEnabled] 同形 —— **suspend provider**，开关翻转后下一次搜索立即生效。
         * ⛔ 不要改成构造期快照：`SearchAggregator` 在 `onCreate` 建一次，快照会让开关永远停在
         * 启动时的值（这正是 `LocalMusicGateTest` 当初钉住的同一类缺陷）。
         */
        private val nasMusicEnabled: suspend () -> Boolean = { true },
        /**
         * P1a（方案 §3.3 G2）：音乐源可见性判定器（唯一真相源，见 `backend/SourceVisibility.kt`）。
         *
         * ⛔ **默认 `null`** ⇒ `null` 视为「全部可见」，保持旧行为 ⇒ 不破坏既有调用方与测试。
         *
         * 为什么需要它（方案 §5.2）：百度 / Jamendo / 网络源三个分支此前**只看实例非空**
         * （`baiduService != null` / `jamendoService != null` / `networkMusicManager != null`），
         * 而这三个实例是**构造期注入**的、恒非空 ⇒ 关掉源后聚合搜索照样返回它们的结果。
         */
        private val sourceVisibility: SourceVisibility? = null
    ) {
        companion object {
            private const val TAG = "SearchAggregator"
            private const val NAS_TIMEOUT = 15_000L
            // 艺人全量方案 §10.3：网易优先 2.5s 子闸门 + Meting 端点链 p95 上界 ≈4.85s（§2 L 组）
            // → 原 5s 会在"网易先跑满再走端点"的串行链上直接超时。
            // ⚠️ 放宽到 8s **不拖累整页**：分支 async 后逐个 await（`:291-298`），总上界取 max，
            //   而 NAS_TIMEOUT = 15_000L（`:74`）仍更大——见 §9.1 T3。
            private const val NETWORK_TIMEOUT = 8_000L
            private const val BAIDU_TIMEOUT = 8_000L
            private const val JAMENDO_TIMEOUT = 5_000L
            private const val LOCAL_TIMEOUT = 2_000L

            /** L09：连续空白（含全角空格 U+3000 与不换行空格 U+00A0）折叠用 */
            private val WHITESPACE_RUN = Regex("[\\s\\u00A0\\u3000]+")
    }

    /**
     * P2 修复（2026-09-22 审查）：承载可能长时间阻塞的源搜索的独立 scope。
     * withTimeoutOrNull 的取消中断不了阻塞 socket 读（runInterruptible 对
     * suspend 调用不成立、全局 callTimeout 影响面大），改为「限时等待独立
     * 执行的结果」：超时后主聚合立即返回 null，孤儿搜索由 OkHttp readTimeout
     * 自然收尾、结果丢弃（连接随即释放）。
     */
    private val escapeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 某个来源此刻是否「可见」（用户开没开）。
     *
     * `sourceVisibility == null`（未注入，e.g. 既有单测）⇒ 一律视为可见，保持旧行为。
     */
    private fun visible(type: MusicSourceType): Boolean =
        sourceVisibility?.isVisible(type) ?: true

    /**
     * 按歌曲的**实际归属**判可见（历史 / 收藏这类「已存在的条目」，S3：只过滤展示、不删数据）。
     *
     * `sourceVisibility == null` ⇒ 一律视为可见，保持旧行为。
     */
    private fun visible(song: Song): Boolean =
        sourceVisibility?.isVisible(song) ?: true

    /**
     * 硬超时：在 [escapeScope] 中执行 [block]，主协程限时等待结果。
     * 超时返回 null（与 withTimeoutOrNull 同形，调用点替换零成本）。
     *
     * ⚠️ 孤儿请求取舍（P2 修复 2026-09-22 定稿，P3 复核 2026-11-19 维持）：
     * 阻塞 socket 读不可被协程取消中断，故这里刻意采用「限时等待 + 丢弃」——
     * 超时后主聚合立即返回 null，孤儿由 OkHttp readTimeout 自然收尾、连接随即释放。
     * ⛔ 不要改成真取消或 cancel 孤儿 job：既中断不了阻塞读，又会扰动四个源共用
     *    的超时语义；堆积风险由各 service 的 readTimeout 上限兜底。
     */
    private suspend fun <T> withHardTimeout(timeoutMs: Long, block: suspend () -> T): T? =
        withTimeoutOrNull(timeoutMs) {
            val result = CompletableDeferred<T>()
            escapeScope.launch {
                try {
                    result.complete(block())
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                }
            }
            result.await()
        }

    /**
     * 并行搜索所有选定源，合并去重后返回结果
     *
     * @param keyword 搜索关键词（给网络/NAS/Jamendo 用）
     * @param sources 要搜索的源集合（默认全部）
     * @param directoryMode 百度源是否用"目录感知"搜索（发现页用 true，搜索页用 false）
     * @param baiduKeyword 百度源专用关键词（null 时用 keyword）。
     *                      发现页传维度标签"粤语"，搜索页传 null（用用户输入）
     * @param filterMode 结果过滤模式：PRECISE=精细过滤（搜索页），NONE=不过滤（发现页）
     * @param searchType 搜索维度：SONG_NAME_OR_ARTIST=歌名+艺术家，ALBUM=专辑，ARTIST=艺术家，SONG_NAME_ONLY=仅歌名
     * @param nasLocalSongs 已加载的 NAS 歌曲本地缓存（搜索页传入，用于拼音搜索时客户端过滤）
     * @param localDeviceSongs 已加载的本地音乐歌曲缓存（搜索页传入，用于拼音搜索时客户端过滤）
     * @param baiduLocalSongs 百度网盘本地索引歌曲（搜索页传入，用于拼音搜索时客户端过滤）
     * @return 聚合搜索结果
     *
     * 主线程安全（P3 修复 2026-11-19）：调用方是 `viewModelScope.launch`（Main）。
     * 若用 `coroutineScope`，`await()` 之后的拼音缓存构建 / 逐条 PRECISE 匹配 /
     * 去重 / 排序，与三个拼音分支对本地缓存的 `filter`，都会继承 Main 执行 ——
     * TV 大曲库下会掉帧。改用 `withContext(Dispatchers.Default)`：CPU 密集的
     * 后处理全部落到后台线程池；网络 / Room IO 仍由各 service 内部的
     * `withContext(IO)` 与 `escapeScope` 负责，不受影响。
     * ⚠️ `withContext` 的 lambda receiver 同为 `CoroutineScope`，`async` / 结构化并发
     * 等待语义与 `coroutineScope` 等价，故仅换构造器与 `return@` 标签。
     */
    suspend fun search(
        keyword: String,
        sources: Set<MusicSourceType> = MusicSourceType.entries.toSet(),
        directoryMode: Boolean = false,
        baiduKeyword: String? = null,
        filterMode: FilterMode = FilterMode.NONE,
        searchType: SearchType = SearchType.SONG_NAME_OR_ARTIST,
        nasLocalSongs: List<Song> = emptyList(),
        localDeviceSongs: List<Song> = emptyList(),
        baiduLocalSongs: List<Song> = emptyList()
    ): SearchAggregatorResult = withContext(Dispatchers.Default) {
        val baiduKw = baiduKeyword ?: keyword
        AppLog.d(TAG, "search: keyword='$keyword' baiduKeyword='$baiduKw' sources=${sources.map { it.name }} directoryMode=$directoryMode filterMode=$filterMode")

        if (keyword.isBlank()) {
            return@withContext SearchAggregatorResult(
                allResults = emptyList(),
                sourceBreakdown = emptyMap()
            )
        }

        // 检测关键词是否是「拼音/英文」形态（不含中文）
        // NAS / 百度 / 本地的服务端搜索只做字面量匹配，不认拼音 ⇒ 这类输入先走本地缓存客户端过滤。
        // ⚠️ 判据与 `SearchViewModel` / `BaiduFileIndexCache` 共用 [SearchQuery.isPinyinLike]，
        //    ⛔ 不要在这里再内联一份（口径漂移 ⇒ 本地筛了、服务端没搜 = 静默 0 条）。
        val isPinyinQuery = SearchQuery.isPinyinLike(keyword)

        // 为每个源创建独立协程，并行搜索
        val nasDeferred = async {
            // 实时从 registry 获取当前 adapter：SearchAggregator 在 onCreate 时创建，
            // 此时 NAS 可能未连接（adapter=null）；用户后续连接后需实时拿到新 adapter
            val backendAdapter = backendRegistry?.getAdapter()
            // v2.39.8（用户 2026-10-28）：NAS 缺独立开关 ⇒ 与 LOCAL 同形实时判开关。
            // ⛔ 不只是 UI 层隐藏：关掉后搜索聚合器必须真的不查 NAS，
            //    否则「关了还在结果里出现」是最难排查的一类静默失效。
            if (MusicSourceType.NAS in sources && backendAdapter != null && nasMusicEnabled()) {
                try {
                    // 服务端搜索（中文/混合关键词的主路径，也是拼音分支的兜底）
                    val searchServer: suspend () -> List<RankedSong> = {
                        withHardTimeout(NAS_TIMEOUT) {
                            backendAdapter.searchSongs(keyword)
                                .filter { it.title.isNotBlank() }
                                .map { song ->
                                    RankedSong(song = song, source = MusicSourceType.NAS)
                                }
                        } ?: run {
                            AppLog.w(TAG, "NAS search timed out")
                            emptyList()
                        }
                    }
                    if (isPinyinQuery && nasLocalSongs.isNotEmpty() && isTVDevice) {
                        // 拼音搜索：NAS 服务端不认拼音，改用本地缓存 + 客户端拼音匹配
                        val localHits = nasLocalSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.map { song ->
                            RankedSong(song = song, source = MusicSourceType.NAS)
                        }
                        // 本地零命中 ⇒ 回退服务端：英文词（`adele`）与「缓存尚未加载完」的首搜
                        // 都落在这里。⛔ 旧实现在这条分支上直接返回本地结果（空），等于把 NAS 源静默关掉。
                        if (localHits.isNotEmpty()) localHits else searchServer()
                    } else {
                        // 中文/混合关键词：走服务端搜索
                        searchServer()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e(TAG, "NAS search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val networkDeferred = async {
            // P1a（方案 §3.3 G2 / §5.2）：网络源分支此前只判 `networkMusicManager != null`
            // —— 实例恒非空 ⇒ 靠 services map **间接**受控。现在改为**显式**判可见性：
            // 关掉 Meting 后本分支不再查询（即便网络源里还注册着别的服务）。
            if (MusicSourceType.NETWORK_MUSIC in sources && networkMusicManager != null &&
                visible(MusicSourceType.NETWORK_MUSIC)
            ) {
                try {
                    withHardTimeout(NETWORK_TIMEOUT) {
                        // Q2：「网易优先」只属于统一搜索入口（F1），⛔ 不得下沉到 MetingApiService.search()
                        networkMusicManager.search(keyword, nativeFirst = true)
                            // 只保留**真正来自 Meting** 的结果：manager.search 在默认源无结果时
                            // 会 fallback 到百度 / Jamendo，而那些歌在各自独立分支里已经查过一次。
                            // 若原样收进 NETWORK_MUSIC，同一首歌会以「网络 / 百度」两个标签重复出现，
                            // 且同一关键词被请求两次（v2.39.26 审查：跨标签重复）。
                            .filter { it.networkSource == NetworkSongId.METING_SOURCE }
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.NETWORK_MUSIC)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Network search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e(TAG, "Network search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val baiduDeferred = async {
            // P1a（方案 §3.3 G2 / §5.2）：百度分支此前只判 `baiduService != null`
            // —— 实例是**构造期注入**的、恒非空 ⇒ 与 services map 完全无关，
            // 关掉百度后聚合搜索**照样**返回百度结果（方案 §0 的「最严重的一处逃逸」）。
            // 现在显式判可见性（= 启用 **且** 已登录）。
            if (MusicSourceType.BAIDU_PAN in sources && baiduService != null &&
                visible(MusicSourceType.BAIDU_PAN)
            ) {
                try {
                    val searchServer: suspend () -> List<RankedSong> = {
                        withHardTimeout(BAIDU_TIMEOUT) {
                            val baiduSongs = if (directoryMode) {
                                baiduService.searchByDirectory(baiduKw)
                            } else {
                                baiduService.search(baiduKw)
                            }
                            baiduSongs
                                .filter { it.title.isNotBlank() }
                                .map { song ->
                                    RankedSong(song = song, source = MusicSourceType.BAIDU_PAN)
                                }
                        } ?: run {
                            AppLog.w(TAG, "Baidu search timed out")
                            emptyList()
                        }
                    }
                    if (isPinyinQuery && isTVDevice) {
                        // 拼音搜索：百度网盘服务端不认拼音，改用本地索引缓存 + 客户端拼音匹配
                        val localHits = baiduLocalSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.map { song ->
                            RankedSong(song = song, source = MusicSourceType.BAIDU_PAN)
                        }
                        // 本地索引为空/零命中 ⇒ 回退服务端。旧实现在这里直接 `emptyList()`：
                        // 「没建索引就搜英文」等于百度源完全消失，且没有任何提示。
                        if (localHits.isNotEmpty()) localHits else searchServer()
                    } else {
                        searchServer()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e(TAG, "Baidu search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val jamendoDeferred = async {
            // P1a（方案 §3.3 G2 / §5.2）：Jamendo 分支此前只判 `jamendoService != null`
            // —— 同样是构造期注入的恒非空实例 ⇒ 关掉 Jamendo 后照样返回结果。
            // 现在显式判可见性（= 开关开 **且** clientId 非空，与 registerService 判据一致）。
            if (MusicSourceType.JAMENDO in sources && jamendoService != null &&
                visible(MusicSourceType.JAMENDO)
            ) {
                try {
                    withHardTimeout(JAMENDO_TIMEOUT) {
                        jamendoService.search(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.JAMENDO)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Jamendo search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e(TAG, "Jamendo search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 本地音乐搜索
        val localDeferred = async {
            // ⚠️ 旧条件为 `MusicSourceType.LOCAL in sources && localMusicRepository != null && localMusicEnabled()`；
            // 那样会把「关闭本地」变成「已下载也消失」，与 SourceVisibility 的
            //「关 = 只显示已下载」自相矛盾。现在分支恒进、由 localOn 决定保留范围：
            // 关闭时只保留 storageType=DOWNLOAD，并把它标成 DOWNLOAD 分支，
            // 让结果层的 `visible(it.source)` 仍成立（不必放宽全局过滤）。
            if (MusicSourceType.LOCAL in sources && localMusicRepository != null) {
                val localOn = localMusicEnabled()
                try {
                    val rank: (Song) -> RankedSong = { song ->
                        RankedSong(
                            song = song,
                            source = if (!localOn && song.storageType == "DOWNLOAD") {
                                MusicSourceType.DOWNLOAD
                            } else {
                                MusicSourceType.LOCAL
                            }
                        )
                    }
                    val searchServer: suspend () -> List<RankedSong> = {
                        // 与其他源同口径：硬超时（Room 查询可取消，超时后不再等）
                        withHardTimeout(LOCAL_TIMEOUT) {
                            localMusicRepository.search(keyword)
                                .filter { it.title.isNotBlank() }
                                .filter { localOn || it.storageType == "DOWNLOAD" }
                                .map(rank)
                        } ?: run {
                            AppLog.w(TAG, "Local search timed out")
                            emptyList()
                        }
                    }
                    if (isPinyinQuery && localDeviceSongs.isNotEmpty() && isTVDevice) {
                        // 拼音搜索：本地源 Room LIKE 查询不认拼音，改用客户端过滤
                        val localHits = localDeviceSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.filter { localOn || it.storageType == "DOWNLOAD" }
                            .map(rank)
                        if (localHits.isNotEmpty()) localHits else searchServer()
                    } else {
                        searchServer()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e(TAG, "Local search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 等待所有协程完成
        val nasResults = nasDeferred.await()
        val networkResults = networkDeferred.await()
        val baiduResults = baiduDeferred.await()
        val jamendoResults = jamendoDeferred.await()
        val localResults = localDeferred.await()

        // 合并所有结果
        val merged = nasResults + networkResults + baiduResults + jamendoResults + localResults

        // P1a（方案 §3.3 G2）：结果层兜底过滤 —— 按可见性再判一次。
        //
        // 为什么需要这一道（阶段一挡不住的三种情况，方案 §0.5 / §5.7）：
        //  ① 采集期分支已经判过开关，但**在途请求**的结果会漏进 UI（关源瞬间已发出的请求）；
        //  ② `fallback` 串源：网络源分支会把**别的源**的歌曲带回来并标成 NETWORK_MUSIC
        //     （`networkMusicManager.search()` 搜的是 services map 里的全部服务）——
        //     只看分支标签 NETWORK_MUSIC 判不出来，必须看**歌曲实际归属**；
        //  ③ 分支标签与归属判定不一致（`Song.sourceType` 的 fallback 分支）。
        //
        // 判据 = 「分支标签可见」**且**「歌曲实际归属可见」：
        //  · 分支标签（`RankedSong.source`）= 这条结果从哪个分支来的；
        //  · 歌曲归属（`Song.sourceType`）= 这首歌**实际上**属于谁。
        //
        // ⚠️ 已下载歌曲（`sourceType == DOWNLOAD`）恒可见 —— 本地开关的语义是
        //    「关 = 只显示已下载」，⛔ 不得把下载歌曲一起滤掉（方案 §5.6 红线）。
        val allResults = merged.filter { visible(it.source) && visible(it.song) }

        // 精细过滤（搜索页）：按 searchType 维度匹配
        // 中文输入走子串匹配，拼音输入走拼音匹配，互不干扰
        // 仅 TV 端启用拼音匹配（手机端触屏输入汉字方便，无需拼音）
        val filtered = if (filterMode == FilterMode.PRECISE) {
            val k = keyword.trim().lowercase()

            // 仅 TV 设备且需要拼音匹配时，提前生成拼音缓存
            val pinyinCache = if (isTVDevice) {
                SongWithPinyin.fromSongs(allResults.map { it.song })
            } else {
                emptyMap()
            }

            allResults.filter { ranked ->
                matchesBySearchTypePrecise(
                    song = ranked.song,
                    keyword = k,
                    searchType = searchType,
                    isTVDevice = isTVDevice,
                    pinyinCache = pinyinCache
                )
            }
        } else {
            allResults
        }

        // 同源内去重：相同 title+artist 只保留第一个
        val deduped = deduplicateWithinSource(filtered)

        // 按来源优先级 + 匹配分排序
        val sorted = RankedSong.sortByPriority(deduped)

        // 各源命中数统计
        val sourceBreakdown = sorted.groupBy { it.source }.mapValues { it.value.size }

        AppLog.d(TAG, "search complete: ${sorted.size} results, breakdown=$sourceBreakdown")

        SearchAggregatorResult(
            allResults = sorted,
            sourceBreakdown = sourceBreakdown
        )
    }

    /**
     * 同源内去重：相同 title+artist 归一化后只保留第一个
     * 跨源不去重（不同源的同一首歌保留多条，各自标注来源）
     */
    private fun deduplicateWithinSource(ranked: List<RankedSong>): List<RankedSong> {
        val seen = mutableSetOf<String>()
        return ranked.filter { item ->
            val key = "${item.source.name}:${normalizeKey(item.song.title, item.song.artist)}"
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    /**
     * 归一化歌曲标识：小写 + 去除首尾空白 + 合并连续空格
     *
     * L09：KDoc 一直承诺「合并连续空格」，实现却没做 —— 同一首歌在不同源里常常是
     * `"Song  Title"` / `"Song Title"`（多个空格来自标签里的对齐填充），
     * 归一化没折掉就判成两首，同源去重失效（列表里出现两条一模一样的歌）。
     */
    private fun normalizeKey(title: String, artist: String): String {
        return "${squeezeSpaces(title)}|${squeezeSpaces(artist)}"
    }

    /** 小写 + trim + 连续空白折叠成单个空格（L09） */
    private fun squeezeSpaces(raw: String): String =
        raw.trim().lowercase().replace(WHITESPACE_RUN, " ")

    /**
     * 按 searchType 维度匹配歌曲（客户端拼音过滤用，子串 + 全拼 + 首字母）
     *
     * 多词语义：关键词按空白切分后**每个词都要命中**（`周杰伦 晴天` = 歌手命中 + 歌名命中），
     * 与 [matchesBySearchTypePrecise] 同一套口径 —— 两者不一致就会出现
     * 「采集期筛掉的歌，精细过滤阶段根本看不到」，这正是全拼搜索失效的成因。
     */
    private fun matchesBySearchType(song: Song, keyword: String, searchType: SearchType): Boolean {
        val tokens = SearchQuery.tokens(keyword)
        if (tokens.isEmpty()) return false
        return tokens.all { token -> matchesTokenBySearchType(song, token, searchType) }
    }

    /** 单词语义：按 searchType 维度，子串 OR 全拼 OR 首字母命中 */
    private fun matchesTokenBySearchType(song: Song, token: String, searchType: SearchType): Boolean =
        when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                PinyinUtils.matches(song.title, token) ||
                        PinyinUtils.matches(song.artist, token)
            SearchType.ALBUM ->
                PinyinUtils.matches(song.album, token) ||
                        PinyinUtils.matches(song.artist, token)
            SearchType.ARTIST ->
                PinyinUtils.matches(song.artist, token)
            SearchType.SONG_NAME_ONLY ->
                PinyinUtils.matches(song.title, token)
        }

    /**
     * 按 searchType 维度精细匹配（PRECISE 过滤用，支持子串 + 拼音全拼 + 首字母）
     *
     * 多词：每个词都要命中（可分别落在不同字段上）。
     */
    private fun matchesBySearchTypePrecise(
        song: Song,
        keyword: String,
        searchType: SearchType,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>
    ): Boolean {
        val tokens = SearchQuery.tokens(keyword)
        if (tokens.isEmpty()) return false
        return tokens.all { token ->
            matchesTokenPrecise(song, token, searchType, isTVDevice, pinyinCache)
        }
    }

    /** 精简单词匹配：子串优先；TV 端再追加拼音全拼 + 首字母 */
    private fun matchesTokenPrecise(
        song: Song,
        token: String,
        searchType: SearchType,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>
    ): Boolean {
        // 1. 按 searchType 维度的子串匹配
        if (matchesSubstringBySearchType(song, token, searchType)) return true

        // 2. 非 TV 设备：仅子串匹配
        if (!isTVDevice) return false

        // 3. 拼音匹配（按 searchType 维度）
        val pinyin = pinyinCache[song.id] ?: SongWithPinyin(song)
        return matchesPinyinBySearchType(pinyin, token, searchType)
    }

    /**
     * 按 searchType 维度的子串匹配
     */
    private fun matchesSubstringBySearchType(song: Song, keyword: String, searchType: SearchType): Boolean {
        return when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                song.title.lowercase().contains(keyword) ||
                        song.artist.lowercase().contains(keyword) ||
                        song.path?.lowercase()?.contains(keyword) == true
            SearchType.ALBUM ->
                song.album.lowercase().contains(keyword) ||
                        song.artist.lowercase().contains(keyword)
            SearchType.ARTIST ->
                song.artist.lowercase().contains(keyword)
            SearchType.SONG_NAME_ONLY ->
                song.title.lowercase().contains(keyword)
        }
    }

    /**
     * 按 searchType 维度的拼音匹配（全拼 + 首字母）
     */
    private fun matchesPinyinBySearchType(pinyin: SongWithPinyin, keyword: String, searchType: SearchType): Boolean {
        return when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                pinyin.pinyin.contains(keyword) ||
                        pinyin.artistPinyin.contains(keyword) ||
                        pinyin.initials.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.ALBUM ->
                pinyin.albumPinyin.contains(keyword) ||
                        pinyin.artistPinyin.contains(keyword) ||
                        pinyin.albumInitials.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.ARTIST ->
                pinyin.artistPinyin.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.SONG_NAME_ONLY ->
                pinyin.pinyin.contains(keyword) ||
                        pinyin.initials.contains(keyword)
        }
    }
}
