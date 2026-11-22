package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import com.google.gson.Gson
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.FilterMode
import com.nasmusic.tv.backend.SearchType
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.SourceVisibility
import com.nasmusic.tv.backend.download.AutoDownloadController
import com.nasmusic.tv.backend.download.DownloadStats
import com.nasmusic.tv.backend.download.SongDownloadManager
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.download.model.isDownloadableSong
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.export.ExportState
import com.nasmusic.tv.backend.local.EmbeddedCoverExtractor
import com.nasmusic.tv.backend.local.MusicMerger
import com.nasmusic.tv.backend.local.StorageMonitor
import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.backend.network.NetworkArtistDetailFetcher
import com.nasmusic.tv.backend.network.QualityScope
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.StorageDevice
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.data.model.BrowseDimension
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.LyricsAvailability
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.data.model.LyricsSource
import com.nasmusic.tv.lyrics.LocalLyricsProvider
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.PlayRecord
import com.nasmusic.tv.data.model.PlayStatistics
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.MusicSource
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.sourceType
import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.data.model.isRadioSong
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.backend.weather.WeatherApi
import com.nasmusic.tv.backend.weather.WeatherRadioManager
import com.nasmusic.tv.backend.radio.SmartRadioManager
import com.nasmusic.tv.backend.playlist.PlaylistEnricher
import com.nasmusic.tv.backend.playlist.PlaylistParsers
import com.nasmusic.tv.backend.playlist.UrlReachabilityChecker
import com.nasmusic.tv.lyrics.LyricsManager
import com.nasmusic.tv.lyrics.LrcParser
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.player.ModelDownloadManager
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.LogSnapshot
import com.nasmusic.tv.util.LogStore
import com.nasmusic.tv.util.PermissionHelper
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.util.SearchQuery
import com.nasmusic.tv.net.RemoteCallbacks
import com.nasmusic.tv.net.RemoteControlServer
import com.nasmusic.tv.net.RemoteSearchResult
import android.os.Handler
import android.os.Looper
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.BackupFileUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * MTV（音乐视频）可用状态。
 * 切歌时自动搜索，UI 消费 [MainViewModel.mvState] 决定 MTV 按钮亮/暗。
 */
sealed interface MvAvailability {
    object Idle : MvAvailability            // 无歌曲/不需要搜索
    object Searching : MvAvailability
    data class Ready(val mv: MvInfo, val alternatives: List<MvCandidate> = emptyList()) : MvAvailability
    object NotFound : MvAvailability
}

/**
 * 本地音乐授权提示（§5.5，形状照抄 `VisualizerViewModel.PhotoAccessNotice`）。
 * 数据层不产出面向用户的文案 —— [MainViewModel] collect 后才 `getString(messageRes)`。
 */
enum class LocalMusicNotice(@StringRes val messageRes: Int) {
    /** 用户在系统权限对话框里点了「不允许」⇒ 开关回弹为关 */
    DENIED(R.string.local_music_denied),

    /** 授权在系统设置里被撤销，回到应用后开关被回弹（§5.5 回弹判据） */
    REVOKED(R.string.local_music_revoked),
}

/**
 * 等源开关的**同步镜像**收敛的最长等待（毫秒）。
 *
 * ⚠️ 可见性判定读的是 `AppPreferences` 的 `@Volatile` 镜像，镜像是 DataStore collector
 * **异步**写的 ⇒ `dataStore.edit{}` 返回时镜像未必已更新（方案 §5.9）。
 */
private const val SOURCE_MIRROR_AWAIT_TIMEOUT_MS = 1000L

/** 等镜像收敛时的轮询间隔（毫秒）。 */
private const val SOURCE_MIRROR_AWAIT_POLL_MS = 25L

/**
 * 电台拼音兜底时的**客户端扫描条数**。
 *
 * radio-browser 的 `name=` 只做字面量匹配，不认拼音 ⇒ 拼音/首字母输入必须自己抓一份
 * 热门榜（`order=votes&reverse=true`，见 `RadioBrowserClient.searchStations`）再客户端筛。
 * 300 条是「覆盖面 / 响应体积」的折中：再多就变成一次几 MB 的列表拉取。
 */
private const val RADIO_PINYIN_SCAN_LIMIT = 300

/**
 * 应用主 ViewModel
 * 管理播放器、歌曲队列、曲库数据、设置等
 */
class MainViewModel(app: Application) : AndroidViewModel(app), RemoteCallbacks {

    private val nasMusicApp = app as NasMusicApp
    private val playerManager = nasMusicApp.playerManager
    private val mvSearchManager = nasMusicApp.mvSearchManager
    val prefs = nasMusicApp.appPreferences
    /** 搜索历史（最近输入 + 最多搜索） */
    val searchHistory = prefs.history.searchHistory

    /**
     * 音乐源可见性判定（方案 §3.1 · 唯一真相源）。
     *
     * ⛔ 只回答「用户开没开」，不回答「它能不能用」—— 后者是 `backendRegistry` /
     * `NetworkMusicManager.services` 的事，两者混在一起会让「NAS 未连接」被当成
     * 「NAS 被关掉」（用户明明开着的源整片消失）。
     *
     * 供 UI 侧合成点使用（曲库 / 「我的」页等「关掉就不出现」的地方）。
     */
    val sourceVisibility: SourceVisibility get() = nasMusicApp.sourceVisibility

    // ⛔⛔ 以下三个 `by lazy` 声明**必须留在类顶部**（v2.39.3 补丁，2026-10-20）。
    //
    // 背景：它们原本声明在 1700+ 行，而构造期 init 块接线（`restoreBaiduIndexOnStart`
    // 等）会在**主线程还没跑完构造**时触发 Default 协程去读它们 ⇒ 读到 null 委托字段
    // ⇒ release 启动 NPE（`SynchronizedLazyImpl.getValue` on null，混淆栈的类名还会被
    // R8 的 lambda 水平合并张冠李戴成不相关的 lambda）。v2.37.0 用 [constructorReady]
    // 守卫挡住了大部分路径；2026-10-20 真机崩溃证明**守卫可被跳过**：R8 把多个挂起
    // lambda 合并进同一个类、共享状态标签，链上前一个挂起点把标签置 1，合并后本
    // lambda 的守卫派发读到陈旧值，`await()` 被绕过直插函数体。
    //
    // 修法从根上改：声明提前到类顶部 ⇒ 构造器最前面几条就赋值委托字段，之后任何
    // 协程（哪怕守卫被 R8 合并绕过）读到的都不是 null。
    // ⚠️ 新增「只被协程/回调读取」的字段时一律在此处声明，⛔ 不要挪回文件后部。
    // 该顺序由 `MainViewModelConstructionOrderTest` 门禁把守。

    /** 已解析的专辑封面缓存（albumId → coverUrl），跨 updateMergedData 保持 */
    private val resolvedAlbumCovers: MutableMap<String, String> by lazy {
        // 从持久缓存预加载已解析的专辑封面（跨会话复用，避免重复网络搜索）
        // 2026-09-25 审查修复（7-1）：IO 线程解析回调写入 + Default 线程 updateMergedData 读取，
        // 普通 HashMap 无 happens-before（坏情况扩容死循环），换 ConcurrentHashMap。
        val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        nasMusicApp.coverUrlPersistentCache.exportAll().forEach { (k, v) ->
            if (k.startsWith("album:")) m[k.removePrefix("album:")] = v
        }
        m
    }

    /** 已解析的艺术家封面缓存（artistId → coverUrl），跨 updateMergedData 保持 */
    private val resolvedArtistCovers: MutableMap<String, String> by lazy {
        // 从持久缓存预加载已解析的艺术家封面（跨会话复用，避免重复网络搜索）
        // 2026-09-25 审查修复（7-1）：同 resolvedAlbumCovers，换 ConcurrentHashMap。
        val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        nasMusicApp.coverUrlPersistentCache.exportAll().forEach { (k, v) ->
            if (k.startsWith("artist:")) m[k.removePrefix("artist:")] = v
        }
        m
    }

    // --- 导出功能（§8.8）---
    private val exportCoordinator by lazy {
        (getApplication<NasMusicApp>()).exportCoordinator
    }
    // --- 封面滤镜设置 ---
    private val _coverFilterEnabled = MutableStateFlow(false)
    val coverFilterEnabled: StateFlow<Boolean> = _coverFilterEnabled.asStateFlow()
    private val _coverFilterBlurRadius = MutableStateFlow(8f)
    val coverFilterBlurRadius: StateFlow<Float> = _coverFilterBlurRadius.asStateFlow()
    private val _coverFilterDarkOverlay = MutableStateFlow(0.3f)
    val coverFilterDarkOverlay: StateFlow<Float> = _coverFilterDarkOverlay.asStateFlow()
    private val backendRegistry = nasMusicApp.backendRegistry

    // --- 歌单导入补全（阶段4，docs/archive/playlist-import-feature-plan.md §4.3）---
    /** 播放触发的元数据补全器（stub → NAS/网络真 Song）—— 使用 NasMusicApp 共享实例 */
    private val playlistEnricher = nasMusicApp.playlistEnricher
    /** URL 可达性检查（播放失败回退复测） */
    private val urlReachabilityChecker = UrlReachabilityChecker()
    /** 补全并发限流（§4.3.4：4 个并发网络查询） */
    private val enrichSemaphore = Semaphore(4)
    /** 补全中 stub 去重（同 id 不重复触发；API 22 兼容，见 L588 同款修复） */
    private val enrichInFlight = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>()
    )
    private val lyricsManager = LyricsManager(
        app, backendRegistry, nasMusicApp.networkMusicManager,
        // F-3：改 provider（读 @Volatile 镜像）——构造期零 IO，设置页改歌词源即时生效
        kugouBaseUrlProvider = { prefs.lyrics.getLyricsKugouBaseUrlSync() },
        neteaseBaseUrlProvider = { prefs.lyrics.getLyricsNeteaseBaseUrlSync() }
    )

    // --- 手机遥控服务器 ---
    private val remoteControlServer = RemoteControlServer(app)
    private val _remoteControlUrl = MutableStateFlow<String?>(null)
    val remoteControlUrl: StateFlow<String?> = _remoteControlUrl.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 按需启动遥控服务器（进入 K 歌/MTV 模式时调用，避免常驻浪费资源） */
    fun ensureRemoteControlStarted() {
        if (_remoteControlUrl.value == null) {
            _remoteControlUrl.value = remoteControlServer.start(this)
        }
    }

    // --- 天气电台（R-1：已拆分至 WeatherRadioViewModel，此处为兼容转发）---
    val weatherRadioVM = WeatherRadioViewModel(app, playerManager, nasMusicApp.networkMusicManager)

    fun playWeatherRadioAll() {
        val songs = weatherRadioVM.weatherRadioQueue.value?.songs ?: return
        if (songs.isEmpty()) return
        // 网络歌曲需要解析 streamUrl，但这里统一走 playQueue 的逻辑
        playQueue(songs, 0)
        // 导航到播放页
        navVM.navigateTo(Screen.NowPlaying)
    }

    // =====================================================================
    // R-1 第三步拆分：Server / Search / NetworkMusic 已迁至子 ViewModel。
    // =====================================================================
    val serverVM = ServerViewModel(app, backendRegistry)
    val searchVM = SearchViewModel(app, backendRegistry, nasMusicApp.searchAggregator)
    val netVM = NetworkMusicViewModel(app)

    /**
     * 构造完成信号（v2.37.0 启动崩溃修复，2026-09-24）。
     *
     * ⛔ 背景：本类把大量回调（netVM.onMergedDataInvalidated 等）接到了**外 VM 自己的
     * 调度线程**上，其中 `updateMergedData()` 直接 `launch(Dispatchers.Default)`。
     * 这些协程可能在主线程**还没跑完本类构造**时就开始执行（构造器在 197 行附近的 init
     * 块就接线，而 `resolvedAlbumCovers` / `resolvedArtistCovers` 的 `by lazy` 委托字段
     * 到 1700+ 行才初始化）→ Default worker 读到 null 委托 → NPE 启动即崩
     * （release 日志：`SynchronizedLazyImpl.getValue` on null，DefaultDispatcher-worker）。
     *
     * 修复：类体**最后一个** init 块 `complete()` 本信号；`updateMergedData()` 的
     * Default 协程体第一行 `await()`，保证合并逻辑只在构造完成后执行。
     * 构造期触发的调用一律挂起排队，不丢不重。
     *
     * ⚠️ v2.39.3 补丁（2026-10-20 真机 release 崩溃，崩因同上）：**本信号不是万能的**。
     * R8 会把多个挂起 lambda 水平合并进同一个类、共用状态标签；合并后链上前一个
     * 挂起点（`restoreBaiduIndexOnStart` 内的 `refreshBaiduConnectionState`）先把
     * 标签置 1，本协程的守卫派发读到陈旧值 ⇒ `await()` 被绕过直插函数体 ⇒ 读到
     * 尚未初始化的 `by lazy` 委托字段 ⇒ NPE（debug 包无 R8 合并故不复现）。
     * 根上的修法是「易被并发读的字段声明提前到类顶部」（见类顶部三个 lazy 的注释），
     * 让守卫失效时读到的也不是 null；本信号保留作纵深防御。
     */
    private val constructorReady = CompletableDeferred<Unit>()

    // ⛔ H2 修复（2026-10-06，代码审查报告 §3）：构造期接线原先位于此处的 init 块
    //    （init 在 214 行、navVM 等字段在 262 行之后才声明），init 块内直接解引用
    //    navVM / _homeDashboardData / _albums / _playlists 等 15 个后声明字段——
    //    Kotlin 属性按声明顺序初始化，一旦任何接线在构造期被同步触发即启动 NPE。
    //    修复：接线整体后移到「类体最后一个 init 块」（文件尾部、全部字段之后），
    //    并由 constructorReady 保证后移后每个被引用字段都已完成初始化。

    // --- 导航状态（R-1 第四步：已迁至 NavigationViewModel，此处转发）---
    val navVM = NavigationViewModel(app)

    // --- 首页仪表盘数据 ---
    private val _homeDashboardData = MutableStateFlow(HomeDashboardData())
    val homeDashboardData: StateFlow<HomeDashboardData> = _homeDashboardData.asStateFlow()

    /**
     * 加载首页仪表盘数据
     *
     * ### ⚠️ NAS 关时是「换数据源」，不是「整段跳过」（方案 §3.2.3）
     *
     * `adapter == null`（「没连 NAS」）的早退**保留**——那是既有语义。
     * 但 **NAS 源被用户关掉**时**不能**照抄这个写法：照抄会让首页「最新添加专辑」
     * 「收藏歌曲」**保留上一次的 NAS 内容不刷新** ⇒ 直接违反 S1（关掉就不出现）。
     *
     * ### 四个 total 的口径（S2：统计数字**不因关源而变**）
     *
     * | 字段 | 取值来源 | 关 NAS 时 |
     * |---|---|---|
     * | `totalAlbums` | `_albums`（**原始库存流**） | 保持（库存流永不清空） |
     * | `totalSongs` | `_songs`（**原始库存流**） | 保持 |
     * | `totalArtists` | `_artists`（**原始库存流**） | 保持 |
     * | `totalPlaylists` | 上一次的值（**不发请求**） | 保持（唯一例外） |
     *
     * `totalPlaylists` 是唯一需要特判的：NAS 关时**不发 `adapter.getPlaylists()` 请求**
     * （省一次请求），此时沿用 `_homeDashboardData.value.totalPlaylists`，
     * ⛔ **不要写 0**（写了就是「扣减」，与 S2 冲突）。
     */
    fun loadHomeDashboard() {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                _homeDashboardData.value = HomeDashboardData()
                return@launch
            }
            try {
                // ⚠️ 三个 total 读**原始库存流**（_albums/_songs/_artists）——它们永不清空，
                //    所以关源后数字天然保持关前值（S2），⛔ 不要按可见性过滤它们。
                val albums = _albums.value.dataOrNull() ?: emptyList()
                val songs = _songs.value.dataOrNull() ?: emptyList()
                val artists = _artists.value.dataOrNull() ?: emptyList()
                val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)

                // totalPlaylists：唯一例外 —— NAS 关时不发 getPlaylists() 请求，沿用上一次的值
                val totalPlaylists = if (nasVisible) {
                    adapter.getPlaylists().size
                } else {
                    _homeDashboardData.value.totalPlaylists
                }

                // 最新添加专辑（按年份降序排列）
                // ⚠️ NAS 关 ⇒ 置空（S1：轮播不能残留 NAS 内容）
                val recentlyAdded = if (nasVisible) {
                    albums
                        .filter { it.year != null }
                        .sortedByDescending { it.year }
                        .take(12)
                        .ifEmpty { albums.take(12) }
                } else {
                    emptyList()
                }

                // 收藏歌曲：NAS 关 ⇒ 置空；再按可见性过滤一次（双重保险）
                val favoriteSongs = if (nasVisible) {
                    (_favoriteSongs.value.dataOrNull() ?: emptyList())
                        .filter { sourceVisibility.isVisible(it) }
                } else {
                    emptyList()
                }

                _homeDashboardData.value = HomeDashboardData(
                    totalAlbums = albums.size,
                    totalSongs = songs.size,
                    totalArtists = artists.size,
                    totalPlaylists = totalPlaylists,
                    recentlyAddedAlbums = recentlyAdded,
                    favoriteSongs = favoriteSongs.take(20)
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                com.nasmusic.tv.util.AppLog.e("MainViewModel", "loadHomeDashboard failed", e)
            }
        }
    }

    // --- 曲库数据（B-12: UiState 统一异步状态）---
    private val _albums = MutableStateFlow<UiState<List<Album>>>(UiState.Loading)
    val albums: StateFlow<UiState<List<Album>>> = _albums.asStateFlow()

    private val _songs = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val songs: StateFlow<UiState<List<Song>>> = _songs.asStateFlow()

    // --- 按需加载：歌曲分页状态 ---
    private val _songsPaging = MutableStateFlow(SongsPagingState())
    val songsPaging: StateFlow<SongsPagingState> = _songsPaging.asStateFlow()

    /**
     * 百度网盘索引歌曲（与专辑/艺术家合并共用同一份 [BaiduFileIndexCache.allSongs] 结果）。
     * 由 [updateMergedData] 在索引扫描完成 / 启动恢复 / 封面回填后刷新，
     * 供曲库「歌曲」Tab 与 NAS / 本地歌曲一起展示。
     */
    private val _baiduIndexedSongs = MutableStateFlow<List<Song>>(emptyList())
    val baiduIndexedSongs: StateFlow<List<Song>> = _baiduIndexedSongs.asStateFlow()
    private val pageSize = 500
    /** 网络音乐"播放全部"单次加入队列的上限（去重后取前 N 首） */
    private val maxNetworkBatchPlayCount = 30

    /**
     * 后台全量加载是否正在进行中
     */
    private val _isBackgroundLoadingAll = AtomicBoolean(false)

    // --- 随心听：随机歌曲 ---
    private val _randomSongs = MutableStateFlow<List<Song>>(emptyList())
    val randomSongs: StateFlow<List<Song>> = _randomSongs.asStateFlow()
    private var shuffleRefillJob: kotlinx.coroutines.Job? = null

    // --- 按需加载：艺术家列表（独立 API）---
    private val _artists = MutableStateFlow<UiState<List<Artist>>>(UiState.Success(emptyList()))
    val artists: StateFlow<UiState<List<Artist>>> = _artists.asStateFlow()

    // ArtistSplitter 拆分前的原始艺术家列表，用于 Navidrome 多 ID 查询合作歌曲
    private var _rawArtistList: List<Artist> = emptyList()

    // --- 本地音乐（USB / 设备存储，独立数据源）---
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()

    /**
     * 本地音乐总开关（settings.localMusicEnabled）的运行期镜像（§5.2）。
     *
     * 由启动块的开关观察者协程在 collect 回调里同步写入（MutableStateFlow.value
     * 即写即读、跨线程可见），供 [visibleLocalSongsNow] 的**同步**读取路径使用——
     * 读 settings flow（stateIn 调度传播）保证不了与观察者写入的 happens-before。
     */
    private val _localMusicEnabled = MutableStateFlow(true)

    /**
     * UI 可见的本地歌曲（§5.4「UI 展示」维度，U8）：
     * 开 → Room 全表；关 → 仅已下载曲目（storageType == "DOWNLOAD"，走应用专属
     * 目录，与 READ_MEDIA_AUDIO 无关——D3：勿波及下载）。
     * ⛔ [_localSongs]（Room 全表）本身永不过滤、永不清空（D2）。
     */
    val visibleLocalSongs: StateFlow<List<Song>> =
        combine(_localSongs, _localMusicEnabled) { songs, enabled ->
            if (enabled) songs else songs.filter { it.storageType == "DOWNLOAD" }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 源可见性变更的**重算信号**（方案 §5.10）。
     *
     * ⚠️ 可见性本身读的是 `AppPreferences` 的 `@Volatile` **同步镜像**，**不是 Flow** ⇒
     * 开关翻转时**不会**自动触发任何下游流重算。这正是 v2 的 G1b 失败根因：
     * UI 层的 `remember(songsPaging, localSongsList, baiduSongsList)` 三个键在开关翻转时
     * 都不变 ⇒ 永不重算 ⇒ NAS 段永远不会被置空。
     *
     * ⇒ 凡是「可见性影响结果」的派生数据，一律挂这个信号；
     * `bumpSourceRevision()` 在开关翻转时调用（方案 §3.5）。
     */
    private val _sourceRevision = MutableStateFlow(0L)

    /** 递增源可见性重算信号（方案 §5.10）。 */
    private fun bumpSourceRevision() {
        _sourceRevision.value += 1
    }

    /**
     * **可见歌曲分页**（方案 §3.3 G1 · 曲库「歌曲」Tab 的可见集）。
     *
     * = NAS 分页 + 本地歌曲 + 百度索引歌曲，按可见性过滤后合并。
     *
     * ### 为什么在 VM 侧合成（而不是 UI 侧 `remember`）
     *
     * v2 把合并钉在 `LibraryBranch.kt:61-71` 的 `remember(songsPaging, localSongsList,
     * baiduSongsList)` —— 开关翻转时三个键都不变 ⇒ `remember` 永不重算 ⇒ NAS 段永远不会被
     * 置空（方案 §3.3 G1 的「⭐ 关键改动」）。合并必须搬到 VM 侧。
     *
     * ### 各段的可见性口径
     *
     * | 段 | 可见时 | 不可见时 |
     * |---|---|---|
     * | NAS | `paging.songs` 原样 | **空**（关 NAS ⇒ NAS 歌一条都不列，但本地/百度照旧） |
     * | 本地 | `visibleLocalSongs`（已含「关 = 只显示已下载」语义） | 同左 |
     * | 百度 | `_baiduIndexedSongs` 原样 | **空**（关百度 ⇒ 百度条目一条都不列） |
     *
     * ### `totalCount` 口径
     *
     * ⚠️ **NAS 关时 NAS 的 `totalCount` 不参与** —— 否则会显示一个不存在的「N 首歌」
     * （列表里已经没有 NAS 歌了，数字却还按全量算）。
     *
     * ⛔ 不要照抄 `LibraryBranch.kt:61-71` 那个「本地与百度都为空就直接返回 paging」的早退分支
     * —— 那正是「NAS 关时 totalCount 仍是 NAS 旧值」的来源。
     */
    val visibleSongsPaging: StateFlow<SongsPagingState> =
        combine(
            _songsPaging,
            visibleLocalSongs,
            _baiduIndexedSongs,
            _sourceRevision
        ) { paging, local, baidu, _ ->
            val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
            val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
            val nasSongs = if (nasVisible) paging.songs else emptyList()
            // 本地段：visibleLocalSongs 已实现「关 = 只显示已下载」，
            // 这里只做**归属兜底**（⛔ 不得把已下载歌曲滤掉，见 `SourceVisibility` 的 DOWNLOAD 分支）。
            val localSongs = local.filter { sourceVisibility.isVisible(it) }
            val baiduSongs = if (baiduVisible) baidu else emptyList()
            val merged = MusicMerger.mergeLibrarySongs(
                nasSongs = nasSongs,
                localSongs = localSongs,
                baiduSongs = baiduSongs
            )
            paging.copy(
                songs = merged,
                totalCount = (if (nasVisible) paging.totalCount else 0) +
                    localSongs.size + baiduSongs.size,
                // NAS 关时 NAS 段已置空，hasMore 不能再沿用 NAS 的旧值：
                // 本地/百度是一次性全量载入（无分页），此时没有「下一页」可拉。
                // 否则 SongsTab.shouldLoadMore 会在滚到底时空触发 loadSongsNextPage
                // （该函数虽有 NAS 关守卫会 return，但属无意义的重复调用）。
                hasMore = nasVisible && paging.hasMore
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _songsPaging.value)


    // --- 本地音乐权限接线（§5.5，照抄照片墙模板）---

    /** 当前本地音乐权限态（Boolean 二态：READ_MEDIA_AUDIO 无「部分授权」概念）；初值现查，不读缓存 */
    private val _localMusicPermissionState =
        MutableStateFlow(PermissionHelper.hasLocalMusicPermission(nasMusicApp))
    val localMusicPermissionState: StateFlow<Boolean> = _localMusicPermissionState.asStateFlow()

    /**
     * 授权相关提示（单向事件流）。`replay = 0`：提示是事件不是状态；
     * `extraBufferCapacity` 是 tryEmit 能成功的前提（0 缓冲时无订阅者必失败）。
     */
    private val _localMusicNotice = MutableSharedFlow<LocalMusicNotice>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val localMusicNotice: SharedFlow<LocalMusicNotice> = _localMusicNotice.asSharedFlow()

    /**
     * 系统权限对话框启动器（由 `MainActivity` 注入）。
     * ViewModel 无法自己 registerForActivityResult；未注入（测试环境）⇒
     * 视为拿不到授权，不静默把开关打开。
     */
    var localMusicPermissionLauncher: (() -> Unit)? = null

    /**
     * 转移式回弹判据（§5.5，与照片墙的**状态式**刻意不同）：上一次权限查询是否
     * 已授权（null = 进程内未查过）。⛔ 不能用「开关开 && 现在未授权」的状态式
     * 判据 —— 本地音乐默认开（D1），状态式会让全新装机首启 onResume 就把开关
     * 翻成关 + 弹提示（违反 U7 零回归）；只有「曾经授权 → 被撤销」的转移才回弹。
     */
    private var lastLocalMusicGranted: Boolean? = null

    /** 回弹防重：回弹写是异步落盘，落盘前重复 onResume 会重复提示（照抄 galleryRollbackPending） */
    private var localMusicRollbackPending = false

    /** 合并后的专辑（NAS + 本地，按 albumName 去重） */
    private val _mergedAlbums = MutableStateFlow<List<Album>>(emptyList())
    val mergedAlbums: StateFlow<List<Album>> = _mergedAlbums.asStateFlow()

    /** 合并后的艺术家（NAS + 本地，按 artistName 去重） */
    private val _mergedArtists = MutableStateFlow<List<Artist>>(emptyList())
    val mergedArtists: StateFlow<List<Artist>> = _mergedArtists.asStateFlow()

    // --- 按需加载：年份列表（独立 API）---
    private val _years = MutableStateFlow<UiState<List<Int>>>(UiState.Success(emptyList()))
    val years: StateFlow<UiState<List<Int>>> = _years.asStateFlow()

    // --- 按需加载：最近播放歌曲（按需批量查询）---
    private val _recentSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val recentSongs: StateFlow<UiState<List<Song>>> = _recentSongs.asStateFlow()

    // --- 搜索域（R-1：已迁至 SearchViewModel，仅保留跨导航暂存的状态镜像）---

    /** 浏览换一批已展示过的歌曲（歌手, 歌名）集合：跨批次去重，筛选条件变化时重置 */
    private val browseSeenKeys = mutableSetOf<Pair<String, String>>()

    /**
     * 发现页刷新并发闸门：保存最近一次刷新 Job，新刷新启动时先取消旧 Job。
     * 配合 [browseRefreshGeneration] 保证「旧结果覆盖新结果」「旧 Job finally 提前清 loading」不会发生。
     */
    private var browseRefreshJob: Job? = null

    /** 刷新代数：每次刷新自增，写结果/清 loading 前比对，仅当前代生效 */
    private var browseRefreshGeneration = 0L

    /** 发现页是否已执行过首次加载：避免全 ALL（空结果）时切回 Tab 反复触发刷新 */
    private var browseLoadedOnce = false

    /** 换一批单次点击最多尝试的后缀数量（避免搜索结果长期重复时空转） */
    private val maxShuffleAttemptsPerClick = 6

    /** 换一批时新歌数量达到该值才展示（否则继续尝试下一后缀） */
    private val minNewResultsForShuffle = 5

    /**
     * 统一的「换一批」核心逻辑（多维度浏览专用；网络搜索/天气电台版已随域迁出）。
     *
     * 反复调用 [produce] 生成候选（最多 [maxShuffleAttemptsPerClick] 次），用 [songsOf]
     * 取出其中的歌曲列表，过滤掉 [seenKeys] 中已展示过的歌曲，返回新歌最多的候选与
     * 新歌列表；新歌数量达到 [minNewResultsForShuffle] 即提前停止。
     *
     * 若所有候选都没有新歌（已见集合饱和），清空 [seenKeys] 后重新生成一次候选并返回
     * （从头再来，保证每次点击都有内容）。返回前把本次真正展示的新歌记入 [seenKeys]
     * （未展示的候选歌曲保留，之后批次仍可出现）。
     *
     * 调用方负责在「上下文变化」（新筛选）时清空对应的 [seenKeys]。
     */
    private suspend fun <T> pickBestFreshBatch(
        seenKeys: MutableSet<Pair<String, String>>,
        maxAttempts: Int = maxShuffleAttemptsPerClick,
        minNewResults: Int = minNewResultsForShuffle,
        produce: suspend () -> T,
        songsOf: (T) -> List<Song>
    ): Pair<T, List<Song>> {
        var best: T? = null
        var bestFresh: List<Song> = emptyList()
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = produce()
            val fresh = songsOf(candidate).filterNot { (it.artist.trim() to it.title.trim()) in seenKeys }
            if (fresh.size > bestFresh.size) {
                best = candidate
                bestFresh = fresh
            }
            if (fresh.size >= minNewResults) break
        }
        val chosen = best
        val result = if (chosen == null || bestFresh.isEmpty()) {
            // 所有候选都没有新歌：已见集合饱和，从头再来一批
            seenKeys.clear()
            val freshProduce = produce()
            freshProduce to songsOf(freshProduce)
        } else {
            chosen to bestFresh
        }
        // 修复（M-9）：硬上限防长期挂机场景集合无限增长（饱和 clear 之外的双保险）
        if (seenKeys.size >= 4000) seenKeys.clear()
        result.second.forEach { seenKeys.add(it.artist.trim() to it.title.trim()) }
        return result
    }

    // --- 统一收藏（R-1：收藏数据迁至 NetworkMusicViewModel，此处派生只读视图）---
    // 供 UI 使用：转换为 Song 对象列表（根据 source 标记 isLocalSong / isNetworkSong 字段）
    //
    // ⚠️ 源可见性过滤（S1）：与 NAS 收藏的 `loadFavorites` 同口径——关闭的源不列出其收藏，
    //    但**只过滤展示、不删 DataStore 数据**（S3，重开即恢复）。此前这里未过滤，
    //    导致关掉网络/本地源后收藏仍留在「我的」页，与 NAS 收藏口径不一致。
    //
    // ⚠️ 必须 combine 两个重算信号（可见性读 @Volatile 镜像、非 Flow，翻转不自动触发下游）：
    //    - `_sourceRevision`：setSourceEnabled 覆盖 NAS/Meting/百度/Jamendo，等镜像收敛后再 bump；
    //    - `_localMusicEnabled`：本地开关走独立观察者（不在 setSourceEnabled 内），单独挂上才能重算
    //      networkSource=="local" 的收藏项。
    val networkFavoriteSongs: StateFlow<List<Song>> =
        combine(netVM.networkFavorites, _sourceRevision, _localMusicEnabled) { favorites, _, _ ->
            favorites.mapNotNull { item ->
                val isLocal = item.networkSource == "local"
                val song = Song(
                    id = item.songId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    coverUrl = item.coverUrl,
                    isNetworkSong = !isLocal,
                    networkSource = item.networkSource.takeIf { !isLocal },
                    networkId = item.networkId,
                    // 平台快照回填：收藏时存下的平台在回放时交给洛雪增强器按原平台取链，
                    // 避免切换全局平台后收藏歌曲解析失败（老数据为 null ⇒ 回退当前平台）。
                    networkPlatform = item.networkPlatform,
                    isLocalSong = isLocal
                )
                song.takeIf { sourceVisibility.isVisible(it) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 网络收藏 ID 集合（用于快速判断是否已收藏）。
    // ⚠️ 由过滤后的 networkFavoriteSongs 派生而非原始 DataStore：保证「展示的收藏」与
    //    「心形态」同一口径（§SourceVisibility 边界：两套判定必然漂移）。
    val networkFavoriteIds: StateFlow<Set<String>> = networkFavoriteSongs.map { songs ->
        songs.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    // --- 本地歌单（「我的」Tab，DataStore 持久化，可混合 NAS/网络歌曲）---
    // （R-1：状态与操作已迁至 PlaylistViewModel，见上方转发区）

    private val _searchNetworkPlatform = MutableStateFlow("netease")
    val searchNetworkPlatform: StateFlow<String> = _searchNetworkPlatform.asStateFlow()

    /**
     * 设置网络搜索平台
     */
    fun setSearchNetworkPlatform(platform: String) {
        _searchNetworkPlatform.value = platform
    }

    // --- 曲库页子 Tab 状态（跨导航记忆） ---
    private val _libraryActiveTab = MutableStateFlow(LibraryTab.ALBUMS)
    val libraryActiveTab: StateFlow<LibraryTab> = _libraryActiveTab.asStateFlow()

    fun selectLibraryTab(tab: LibraryTab) {
        val changed = _libraryActiveTab.value != tab
        _libraryActiveTab.value = tab
        if (!changed) return
        // 搜索维度跟着 Tab 变（同一关键词在「专辑/艺术家/歌曲」三个维度命中集合不同）：
        // 旧实现只改状态、不重搜，`LibraryScreen` 的 `LaunchedEffect` 又只盯 filterQuery，
        // 于是「在专辑页输 zjl 再切到歌曲页」看到的仍是专辑维度的结果，歌名命中永远补不进来。
        val keyword = _librarySearchKeyword.value
        if (keyword.isBlank()) return
        when (tab) {
            LibraryTab.SEARCH, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.SONGS ->
                // ⚠️ 不传 force：`searchSongsOnServer` 的缓存键本来就含 searchType
                // （同词 + 同维度才命中），换维度会自动重搜，同维度切换则免费命中缓存。
                // ⚠️ 不记搜索历史：切 Tab 是换维度，不是用户"搜了一次"。
                searchVM.searchSongsOnServer(keyword, recordHistory = false)
            // GENRES / YEARS / DISCOVER / RADIO 不用服务端搜索结果，切过去不触发重搜
            else -> Unit
        }
    }

    // --- 曲库页滚动位置记忆（切换 Tab 后恢复上次滚动位置） ---
    private val _albumScrollIndex = MutableStateFlow(0)
    val albumScrollIndex: StateFlow<Int> = _albumScrollIndex.asStateFlow()

    private val _albumScrollOffset = MutableStateFlow(0)
    val albumScrollOffset: StateFlow<Int> = _albumScrollOffset.asStateFlow()

    private val _artistScrollIndex = MutableStateFlow(0)
    val artistScrollIndex: StateFlow<Int> = _artistScrollIndex.asStateFlow()

    private val _artistScrollOffset = MutableStateFlow(0)
    val artistScrollOffset: StateFlow<Int> = _artistScrollOffset.asStateFlow()

    private val _songScrollIndex = MutableStateFlow(0)
    val songScrollIndex: StateFlow<Int> = _songScrollIndex.asStateFlow()

    private val _songScrollOffset = MutableStateFlow(0)
    val songScrollOffset: StateFlow<Int> = _songScrollOffset.asStateFlow()

    fun saveAlbumScrollPosition(index: Int, offset: Int) {
        _albumScrollIndex.value = index
        _albumScrollOffset.value = offset
    }

    fun saveArtistScrollPosition(index: Int, offset: Int) {
        _artistScrollIndex.value = index
        _artistScrollOffset.value = offset
    }

    fun saveSongScrollPosition(index: Int, offset: Int) {
        _songScrollIndex.value = index
        _songScrollOffset.value = offset
    }

    // --- 曲库页搜索关键词（跨导航记忆，切换页面后保留搜索框内容与结果） ---
    private val _librarySearchKeyword = MutableStateFlow("")
    val librarySearchKeyword: StateFlow<String> = _librarySearchKeyword.asStateFlow()

    fun setLibrarySearchKeyword(keyword: String) {
        if (_librarySearchKeyword.value != keyword) {
            _librarySearchKeyword.value = keyword
        }
    }

    fun clearLibrarySearchKeyword() {
        if (_librarySearchKeyword.value.isNotBlank()) {
            _librarySearchKeyword.value = ""
        }
    }

    // （R-1：搜索来源点亮状态已迁至 SearchViewModel，转发见下方搜索转发区）

    // --- 网络音乐平台来源 ---
    private val _currentMusicSource = MutableStateFlow(MusicSource.NETEASE)
    val currentMusicSource: StateFlow<MusicSource> = _currentMusicSource.asStateFlow()

    /**
     * 初始化音乐来源（从持久化存储读取）
     */
    private fun initMusicSource() {
        val savedKey = prefs.network.getMusicSourceSync()
        val source = MusicSource.fromApiKey(savedKey)
        _currentMusicSource.value = source
        _searchNetworkPlatform.value = source.apiKey
    }

    /**
     * 切换音乐平台来源
     *
     * 持久化到 DataStore，同时更新旧的 searchNetworkPlatform 状态保持兼容，
     * 如果有搜索关键词则自动重新搜索。
     */
    fun selectMusicSource(source: MusicSource) {
        if (_currentMusicSource.value == source) return
        _currentMusicSource.value = source
        _searchNetworkPlatform.value = source.apiKey
        viewModelScope.launch {
            prefs.network.setMusicSource(source.apiKey)
            // P4 修复 2026-11-19：原此处有 `searchVM.searchNetworkSongs(kw)` 自动重搜。
            // 该链写入的 _networkSearchResults 在当前 UI 无任何 Composable 收集
            // （playAllSearchResults / addAllSearchResultsToQueue / shuffleNetworkSearch
            //  在 ui/ 下均无调用点），切平台时跑完整网络搜索纯属浪费带宽与端点配额。
            // ⛔ 网络音乐的实际搜索入口是曲库页的 SearchViewModel.searchSongsOnServer，
            //    由聚合器统一处理；平台切换只做持久化，不再主动发请求。
        }
    }

    /**
     * 改**网络搜索默认源**（方案 §6-10 / Task 7：设置页 SOURCES 卡⑤暴露）。
     *
     * 与 [selectMusicSource]（当前选中的音乐平台，旧 `searchNetworkPlatform` 兼容态）
     * 是两回事：本项写 `keyDefaultNetworkSource` ⇒ `appSettings.defaultNetworkSource`
     * Flow 实时刷新，搜索侧经 `defaultSourceProvider`（NasMusicApp:343）读取。
     * 判据只认 `appSettings` Flow（`DefaultNetworkSourcePrefTest`），⛔ 不断言
     * `getDefaultNetworkSourceSync()` 的收敛时序（ProviderMirrorTest 5997380 教训）。
     *
     * v2.39.8：类型由枚举改为 `String`（= `NetworkMusicManager.services` 的 key，方案 §7.5）。
     */
    fun updateDefaultNetworkSource(source: String) {
        viewModelScope.launch { prefs.setDefaultNetworkSource(source) }
    }

    /**
     * 启停一个网络音乐源（方案 §7.5 / §7.7 第 5 条）。
     *
     * 机制一律是 `registerService` / `unregisterService`，⛔ **不要**改成在
     * `orderedServices()` 里过滤——那只覆盖 `search`，`resolvePlayUrl` 用
     * `services[src]` 精确路由，关掉的源照样能取链。
     *
     * 三个源的「活着」语义各不相同（构造期塞 map / `baiduConfig.isActive` / clientId 判空），
     * 所以这里分支处理，不引入统一开关 map。
     */
    fun setSourceEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch {
            when (key) {
                NetworkSongId.METING_SOURCE -> {
                    prefs.setMetingEnabled(enabled)
                    if (enabled) nasMusicApp.networkMusicManager.registerService(nasMusicApp.metingApiService)
                    else nasMusicApp.networkMusicManager.unregisterService(key)
                }
                // v2.39.8（用户 2026-10-28）：NAS 是**独立音乐源**，此前没有自己的开关。
                // ⚠️ 它不是 `NetworkMusicManager` 的 service（走 `backendRegistry`），所以无需注册/注销：
                //    门控在 `SearchAggregator` 的 NAS 分支（实时读开关）与浏览/歌单入口。
                // ⛔ 关掉**不清连接、不清凭据** —— 那会让「临时不想搜 NAS」变成「要重新输口令」。
                AppPreferences.SOURCE_NAS -> {
                    prefs.setNasMusicEnabled(enabled)
                }
                AppPreferences.SOURCE_BAIDU -> {
                    prefs.baidu.setBaiduEnabled(enabled)
                    nasMusicApp.refreshBaiduServiceRegistration()
                    netVM.refreshBaiduConnectionState()
                }
                AppPreferences.SOURCE_JAMENDO -> {
                    prefs.setJamendoEnabled(enabled)
                    nasMusicApp.refreshJamendoServiceRegistration()
                }
                else -> return@launch
            }
            // 关源 ⇒ 当前默认源可能已失效，立刻自愈（§7.6 第 2 条）
            val switched = nasMusicApp.repairDefaultNetworkSource()
            if (switched != null) {
                showError(
                    getApplication<Application>()
                        .getString(R.string.settings_source_default_switched, sourceTitle(switched))
                )
            }
            // 源可见性变了 ⇒ 递增重算信号，让依赖可见性的派生流（visibleSongsPaging 等）重算。
            // ⚠️ 可见性读的是 @Volatile **同步镜像**、不是 Flow ⇒ 翻转时不会自动触发下游重算
            // （方案 §5.10：凡是「可见性影响结果」的派生数据，一律挂 sourceRevision）。
            //
            // ⛔ 必须**先等镜像收敛再 bump**：镜像是 DataStore collector 异步写的，
            // `dataStore.edit{}` 返回时镜像未必已更新 ⇒ 此刻就 bump 会让派生流拿**旧**可见性
            // 重算，而此后没有新的上游发射 ⇒ 派生流停在旧值上（用户看到「开关没生效」）。
            //
            // 单独起协程：等待**不得**阻塞上面的默认源自愈与提示。
            viewModelScope.launch {
                awaitSourceMirror(key, enabled)
                bumpSourceRevision()
                if (!enabled) {
                    // ================= 关源（方案 §3.5）=================
                    // 1) 清该源独占缓存 —— **唯一**清理入口（见 clearSourceScopedCaches）
                    clearSourceScopedCaches(key)
                    // 2) 重算合并数据：专辑 / 艺术家是**合成期**数据，其可见性由 updateMergedData 判，
                    //    不随原始库存流变化 ⇒ 关源后必须显式重算，否则 NAS 专辑仍留在列表里。
                    updateMergedData()
                    // 3) 首页：轮播/收藏按源清空（S1），四个统计读原始库存流保持不变（S2）
                    loadHomeDashboard()
                    // 4) 历史按 song.sourceType 过滤（只过滤展示，不删库，S3）
                    loadRecentSongs(showLoading = false)
                    // 5) 智能电台批次：关源**前**已缓存的批次里的该源歌也要被滤掉（方案 §3.3 G1c）
                    _smartRadioBatch.value = _smartRadioBatch.value.filter { sourceVisibility.isVisible(it) }
                } else {
                    // ================= 重开（方案 §3.5）=================
                    // ⚠️ 重开方向**必须**按源显式重拉：关源期间该源的流已被清空，
                    //    光靠 registerService 不会恢复（NAS 走 backendRegistry，Meting 走 services map）。
                    when (key) {
                        AppPreferences.SOURCE_NAS -> {
                            // loadLibrary 内部含 loadGenres / loadFavorites / loadRandomSongs / loadAllSongsBackground
                            loadLibrary()
                            // ⚠️ loadYears / loadPlaylists 是**独立入口**，loadLibrary 不触发它们
                            loadYears()
                            loadPlaylists()
                        }
                        NetworkSongId.METING_SOURCE -> {
                            // 恢复随心听的网络段（loadRandomSongs 没有其它调用者）
                            loadRandomSongs()
                        }
                        // 百度 / Jamendo：服务注册已由上方的 refresh*ServiceRegistration 处理；
                        // 百度的本地索引缓存已在 clearSourceScopedCaches 中清掉，
                        // 重开后由百度索引扫描流程重新填充。
                        AppPreferences.SOURCE_BAIDU, AppPreferences.SOURCE_JAMENDO -> Unit
                    }
                }
            }
        }
    }

    /**
     * 关掉某个源时，清理「只属于该源、且没有合成点可过滤」的缓存（方案 §3.2.2）。
     *
     * ⛔ **不动原始库存流**（`_albums` / `_songs` / `_songsPaging` / `_artists`）——
     * 它们是 S2 统计（首页「专辑 N / 歌曲 N / 艺术家 N」）的**读数来源**，
     * 关源只影响「列出哪些条目」（S1），不改写「库里有这么多」。
     *
     * ### ⚠️ 这是**唯一**的清理入口
     *
     * v2 方案把清理寄托在「各 `load*` 的短路分支顺手清」——**那是死代码**：
     * 这些 `load*` 在开关翻转时**根本没有调用者**（`loadGenres` 只在 `loadLibrary` 内被调；
     * `loadYears` / `loadPlaylists` 只有 UI 的 retry 回调会触发）。
     * ⇒ 必须由 [setSourceEnabled] 直接调用本函数。
     *
     * ### 各流的实际类型（置空时必须写同类型的值）
     *
     * | 流 | 类型 | 处置 |
     * |---|---|---|
     * | `_genres` | `UiState<List<Genre>>` | 置空 `UiState.Success(emptyList())` |
     * | `_years` | `UiState<List<Int>>` | 置空 `UiState.Success(emptyList())` |
     * | `_playlists` | `UiState<List<Playlist>>` | 置空 `UiState.Success(emptyList())` |
     * | `_favoriteSongs` | `UiState<List<Song>>` | 置空 `UiState.Success(emptyList())` |
     * | `_favoriteIds` | `Set<String>` | 置空（内容全部来自 NAS 收藏） |
     * | `_randomSongs` | `List<Song>` | 按 `sourceType` 过滤（保留另一源的部分） |
     * | `_baiduIndexedSongs` | `List<Song>` | 置空 `emptyList()` |
     * | `_artistSongsMap` | `Map<String, List<Song>>` | 按 `sourceType` 过滤（`mapValues`） |
     */
    private fun clearSourceScopedCaches(source: String) {
        when (source) {
            AppPreferences.SOURCE_NAS -> {
                // 这四个流**只由 NAS 填充**、**无统计消费者**、**没有可用的合成点**（UI 直读）
                // ⇒ 置空是唯一可行处置（方案 §5.8）。
                _genres.value = UiState.Success(emptyList())
                _years.value = UiState.Success(emptyList())
                _playlists.value = UiState.Success(emptyList())
                _favoriteSongs.value = UiState.Success(emptyList())
                // 心形 UI 态同步清理：_favoriteIds 的内容**全部**来自 NAS 收藏
                // （网络/本地收藏走 networkFavoriteIds，不在这个流里）
                _favoriteIds.value = emptySet()
                // 混装流（NAS + Meting）：按 sourceType 过滤，**保留** Meting 段（⛔ 不要整条清空）
                _randomSongs.value = _randomSongs.value.filter { it.sourceType != MusicSourceType.NAS }
                // 艺术家 TAB 的曲目映射：由 NAS 歌增量构建 ⇒ 去掉 NAS 歌。
                // ⚠️ 只**过滤**结果，不改动增量构建逻辑本身（buildArtistMapsIncremental）。
                _artistSongsMap.value = _artistSongsMap.value.mapValues { (_, songs) ->
                    songs.filter { it.sourceType != MusicSourceType.NAS }
                }
            }
            AppPreferences.SOURCE_BAIDU -> {
                // 百度独有，仅由 baiduIndexCache 填充，UI 直读
                _baiduIndexedSongs.value = emptyList()
            }
            NetworkSongId.METING_SOURCE -> {
                // 同上：只去掉网络歌，保留 NAS 段
                _randomSongs.value = _randomSongs.value.filter {
                    it.sourceType != MusicSourceType.NETWORK_MUSIC
                }
                // 艺术家详情缓存按名字存的是五源混合结果，关源后必须去掉网络段，
                // 否则「关了网络源再进赵传详情」仍显示关源前的旧网络歌（openArtistDetail 命中
                // containsKey 直接短路、不会再加载）。用 filterValues 去掉变空的 key，让下次
                // 进入重新走门控后的加载；非空 key 保留 NAS/本地/百度段。
                _artistDetailSongsCache.value = _artistDetailSongsCache.value
                    .mapValues { (_, songs) ->
                        songs.filterNot { it.networkSource == NetworkSongId.METING_SOURCE }
                    }
                    .filterValues { it.isNotEmpty() }
            }
            // Jamendo 没有独立的缓存流（条目走 services map + 聚合器）
            AppPreferences.SOURCE_JAMENDO -> Unit
            else -> Unit
        }
    }

    /**
     * 等某个源开关的**同步镜像**收敛到目标值（方案 §5.9 的镜像窗口）。
     *
     * ⚠️ 为什么必须等：可见性判定读的是 `AppPreferences` 的 `@Volatile` 镜像，而镜像是
     * DataStore collector **异步**写的 —— `dataStore.edit{}` 返回时镜像未必已更新。
     * 此刻就 bump 重算信号 ⇒ 派生流拿**旧**可见性重算 ⇒ 而此后没有新的上游发射
     * ⇒ 派生流会**停在旧值**上（用户看到「开关没生效」）。
     *
     * 有界等待（最多 ~1s）：超时也放行 —— 宁可慢一拍，不能卡死。
     */
    private suspend fun awaitSourceMirror(key: String, enabled: Boolean) {
        // 百度：镜像 = 「启用 **且** 已登录」（`CloudDriveConfig.isActive`）⇒ 目标值取决于登录态，
        // 这里先读一次当前配置定出期望值（登录态不会因开关翻转而改变）。
        val expectedBaiduActive = if (key == AppPreferences.SOURCE_BAIDU) {
            enabled && prefs.baidu.baiduConfigFlow.first().tokens != null
        } else {
            enabled
        }
        val deadline = System.currentTimeMillis() + SOURCE_MIRROR_AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val converged = when (key) {
                AppPreferences.SOURCE_BAIDU ->
                    prefs.getBaiduActiveSync() == expectedBaiduActive
                AppPreferences.SOURCE_JAMENDO ->
                    prefs.getJamendoEnabledSync() == enabled
                NetworkSongId.METING_SOURCE ->
                    prefs.getMetingEnabledSync() == enabled
                AppPreferences.SOURCE_NAS ->
                    prefs.getNasMusicEnabledSync() == enabled
                else -> true
            }
            if (converged) return
            delay(SOURCE_MIRROR_AWAIT_POLL_MS)
        }
    }

    /** 源 key → 界面名（一次性提示里用，与设置页源行标题同源）。 */
    private fun sourceTitle(key: String): String {
        val res = when (key) {
            NetworkSongId.METING_SOURCE -> R.string.settings_source_meting
            AppPreferences.SOURCE_BAIDU -> R.string.settings_source_baidu
            AppPreferences.SOURCE_JAMENDO -> R.string.settings_source_jamendo
            AppPreferences.SOURCE_LX -> R.string.settings_source_lx
            else -> return key
        }
        return getApplication<Application>().getString(res)
    }

    // ================= 洛雪（LX）取链（方案 §7.1–§7.3） =================

    /**
     * 引擎可达性：null = 后台求值中（UI 不置灰，避免启动瞬间闪一下灰）。
     * ⛔ 判据是「真的 `dlopen` 成功了吗」，不是 SDK 版本（`LxEngineAvailability`）。
     */
    private val _lxEngineAvailable = MutableStateFlow<Boolean?>(null)
    internal val lxEngineAvailable: StateFlow<Boolean?> = _lxEngineAvailable.asStateFlow()

    /** 引擎不可用时的原因（给子块那行灰字用） */
    private val _lxEngineUnavailableReason = MutableStateFlow<String?>(null)
    internal val lxEngineUnavailableReason: StateFlow<String?> = _lxEngineUnavailableReason.asStateFlow()

    private val _lxSessions = MutableStateFlow<Map<String, LxScriptView>>(emptyMap())
    private val _lxVerifyAll = MutableStateFlow<LxVerifyAllState?>(null)
    internal val lxVerifyAll: StateFlow<LxVerifyAllState?> = _lxVerifyAll.asStateFlow()

    private var lxVerifyJob: Job? = null

    /**
     * 脚本行列表 = 落盘元数据 + 本次会话的验证状态。
     *
     * ⚠️ 不能在 ViewModel 构造期直接读 `lastStatus` 之外的东西：脚本正文与 runtime 都在
     * `NasMusicApp`，这里的 View 只是**快照**，真正加载发生在 `refreshLxRuntime()`。
     */
    internal val lxScriptViews: StateFlow<List<LxScriptView>> = combine(
        nasMusicApp.lxScriptRepo.entries,
        _lxSessions,
    ) { entries, sessions ->
        entries.map { entry ->
            val session = sessions[entry.id]
            LxScriptView(
                entry = entry,
                running = session?.running == true,
                stage = session?.stage ?: LxVerifyStage.IDLE,
                currentSource = session?.currentSource,
                index = session?.index ?: 0,
                total = session?.total ?: 0,
                l1 = session?.l1,
                livePlatforms = session?.livePlatforms.orEmpty(),
                result = session?.result ?: com.nasmusic.tv.backend.network.lx.LxStatusCodec.decode(entry.lastStatus),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 洛雪取链总开关（§7.7 第 6 条）。
     *
     * ⛔ **必须显式清直链缓存**：洛雪不是 service，切它不触发任何 `unregisterService`，
     * 而它取到的直链在缓存里仍记为 `meting:<id>` —— 不清就会「关了洛雪还在播洛雪给的链」。
     */
    fun setLxEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.lx.setEnabled(enabled)
            nasMusicApp.networkMusicManager.clearPlayUrlCache()
            nasMusicApp.refreshLxRuntime()
        }
    }

    /** 单脚本启停（与总开关是「与」关系，§7.1 硬约束 1） */
    fun setLxScriptEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            nasMusicApp.lxScriptRepo.setEnabled(id, enabled)
            nasMusicApp.refreshLxRuntime()
        }
    }

    /** 从 URL 导入 / 更新一个脚本（失败保留旧版，`LxScriptRepo` 保证） */
    fun importLxSource(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            nasMusicApp.lxScriptRepo.installFromUrl(trimmed)
                .onSuccess { entry ->
                    nasMusicApp.refreshLxRuntime()
                    showError(getApplication<Application>().getString(R.string.lx_import_ok, entry.name))
                }
                .onFailure { t ->
                    showError(
                        getApplication<Application>().getString(
                            R.string.lx_import_failed,
                            t.message ?: t.javaClass.simpleName,
                        )
                    )
                }
        }
    }

    /** 「刷新全部」：按登记的 URL 重下一遍正文，再重建 host（mtime 变了才重建） */
    fun refreshLxScripts() {
        viewModelScope.launch {
            val entries = nasMusicApp.lxScriptRepo.snapshot().filter { it.url.isNotBlank() }
            var failed = 0
            for (entry in entries) {
                if (nasMusicApp.lxScriptRepo.installFromUrl(entry.url).isFailure) failed++
            }
            nasMusicApp.refreshLxRuntime()
            showError(
                if (failed == 0) {
                    getApplication<Application>().getString(R.string.lx_refresh_ok)
                } else {
                    getApplication<Application>().getString(R.string.lx_refresh_partial, failed)
                }
            )
        }
    }

    /**
     * 单源「验证」：默认 `QUICK`（全部已声明平台 × 各自最高档）。
     *
     * 与「全部验证」的区别见 §7.2 的脚注：这里回答「这个源在各平台行不行」。
     */
    internal fun verifyLxScript(id: String, depth: com.nasmusic.tv.backend.network.lx.LxProbeDepth =
        com.nasmusic.tv.backend.network.lx.LxProbeDepth.QUICK) {
        // ⛔ 原来的静默 `return` 是「点了验证没反应」的一半原因：另一个验证正在跑时，
        //    用户完全无从得知（按钮没变、页面没提示）。这里必须给一句人话。
        if (lxVerifyJob?.isActive == true) {
            showError(getApplication<Application>().getString(R.string.lx_verify_busy))
            return
        }
        lxVerifyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runOneLxVerify(id, depth)
            } finally {
                clearLxRunningFlags()
            }
        }
    }

    /**
     * 「全部验证」：串行跑所有**可用**源（含已关闭的 —— 关着也可能想测），`SWEEP` 深度。
     *
     * ⚠️ 串行是硬要求：每个脚本一个 QuickJS runtime、内部单线程，并发既不加速也吃内存。
     *
     * ⚠️ v2.39.8：判据由 `it.installed` 放宽为「已安装 **或** 有下载地址」。
     * 出厂内置的源**只登记 URL、不内置正文**（§9.4），按老判据它们全被跳过 ——
     * 于是「全部验证」在全新安装上永远报「还没有可验证的脚本」，而这恰恰是用户
     * 最想一键体检的时候。正文由 [runOneLxVerify] 按需补下载。
     */
    fun verifyAllLxScripts() {
        if (lxVerifyJob?.isActive == true) {
            showError(getApplication<Application>().getString(R.string.lx_verify_busy))
            return
        }
        lxVerifyJob = viewModelScope.launch(Dispatchers.IO) {
            val verifiable = nasMusicApp.lxScriptRepo.snapshot()
                .filter { it.installed || it.url.isNotBlank() }
            if (verifiable.isEmpty()) {
                showError(getApplication<Application>().getString(R.string.lx_verify_no_scripts))
                return@launch
            }
            _lxVerifyAll.value = LxVerifyAllState(running = true, done = 0, total = verifiable.size, currentName = null)
            try {
                verifiable.forEachIndexed { index, entry ->
                    // 用户在「全部验证」跑到一半点了停止 ⇒ 立刻收手，不要继续下一个源。
                    // ⚠️ 这里用 `lxVerifyJob.isActive` 而不是 `ensureActive()`：本文件没有
                    //    `kotlinx.coroutines.ensureActive` 的 import，而 `stopLxVerify` 取消的
                    //    正是 `lxVerifyJob` ⇒ 判据等价且不引入新的依赖。
                    if (lxVerifyJob?.isActive != true) return@launch
                    _lxVerifyAll.value = LxVerifyAllState(true, index, verifiable.size, entry.name)
                    runOneLxVerify(
                        entry.id,
                        com.nasmusic.tv.backend.network.lx.LxProbeDepth.SWEEP,
                    )
                }
            } finally {
                _lxVerifyAll.value = null
                clearLxRunningFlags()
            }
        }
    }

    /** 「停止」（§7.2）：取消在途验证；`LxProbeRunner` 的 finally 会打断脚本请求 */
    fun stopLxVerify() {
        lxVerifyJob?.cancel()
        _lxVerifyAll.value = null
        clearLxRunningFlags()
    }

    private suspend fun runOneLxVerify(id: String, depth: com.nasmusic.tv.backend.network.lx.LxProbeDepth) {
        updateLxSession(id) {
            LxScriptView(
                entry = it?.entry ?: com.nasmusic.tv.backend.network.lx.LxSourceEntry(
                    id = id, name = id, url = "", enabled = false,
                    version = null, installedAt = 0, lastStatus = null,
                ),
                running = true,
                stage = LxVerifyStage.LOADING,
                result = it?.result,
            )
        }
        try {
            // ── v2.39.8 修复：内置源出厂只登记 URL、正文要按需下载（§9.4 不内置脚本）──
            //
            // ⛔ 这里是「点验证只闪一下」的真正根因：正文缺失时 `hostProvider` 返回 null，
            //    `LxProbeRunner` 立刻吐 `L1_NOT_INSTALLED`，整轮在几十毫秒内结束 ——
            //    界面只从「验证」跳到「重试」，中间没有任何可读信息。
            //    正确做法：**先补下载**（有 URL 才能下），失败才如实报下载错误。
            val entry = nasMusicApp.lxScriptRepo.snapshot().firstOrNull { it.id == id }
            if (entry != null && !entry.installed && entry.url.isNotBlank()) {
                updateLxSession(id) { view -> view?.copy(stage = LxVerifyStage.DOWNLOADING) }
                val install = nasMusicApp.lxScriptRepo.installFromUrl(entry.url)
                install.onFailure { t ->
                    AppLog.w("LxVerify", "[$id] 正文下载失败：${t.javaClass.simpleName}: ${t.message}")
                    updateLxSession(id) { view ->
                        view?.copy(
                            running = false,
                            stage = LxVerifyStage.FAILED,
                            l1 = com.nasmusic.tv.backend.network.lx.LxL1.Failed(
                                com.nasmusic.tv.backend.network.lx.LxFailReason.L1_SCRIPT_ERROR,
                                t.message ?: t.javaClass.simpleName,
                            ),
                        )
                    }
                }
                if (install.isFailure) return
                // 正文到位 ⇒ 重建 host（`LxHostPool.hostFor` 按文件存在与否取 host）
                nasMusicApp.refreshLxRuntime()
                updateLxSession(id) { view -> view?.copy(stage = LxVerifyStage.LOADING) }
            }

            nasMusicApp.lxProbeRunner.verify(id, depth).collect { event ->
                when (event) {
                    is com.nasmusic.tv.backend.network.lx.LxProbeEvent.L1Done -> updateLxSession(id) { view ->
                        view?.copy(
                            stage = if (event.l1 is com.nasmusic.tv.backend.network.lx.LxL1.Loaded)
                                LxVerifyStage.PROBING else LxVerifyStage.FAILED,
                            l1 = event.l1,
                            total = (event.l1 as? com.nasmusic.tv.backend.network.lx.LxL1.Loaded)
                                ?.sources?.size ?: 0,
                        )
                    }
                    is com.nasmusic.tv.backend.network.lx.LxProbeEvent.PlatformStart -> updateLxSession(id) { view ->
                        view?.copy(currentSource = event.source, index = event.index, total = event.total)
                    }
                    is com.nasmusic.tv.backend.network.lx.LxProbeEvent.PlatformDone -> updateLxSession(id) { view ->
                        view?.copy(livePlatforms = view.livePlatforms + (event.probe.source to event.probe))
                    }
                    is com.nasmusic.tv.backend.network.lx.LxProbeEvent.Finished -> {
                        val json = com.nasmusic.tv.backend.network.lx.LxStatusCodec.encode(event.report)
                        nasMusicApp.lxScriptRepo.saveLastStatus(id, json)
                        updateLxSession(id) { view ->
                            view?.copy(
                                running = false,
                                stage = if (event.report.l1 is com.nasmusic.tv.backend.network.lx.LxL1.Loaded)
                                    LxVerifyStage.DONE else LxVerifyStage.FAILED,
                                currentSource = null,
                                result = com.nasmusic.tv.backend.network.lx.LxStatusCodec.decode(json),
                                // ⚠️ livePlatforms 必须留着：异步明细（L2/L3 逐档尝试）只存在这里，
                                //  `lastStatus` 只有压缩后的结论。清空会让刚跑完的验证立刻显示「无明细」。
                            )
                        }
                    }
                    com.nasmusic.tv.backend.network.lx.LxProbeEvent.Cancelled -> updateLxSession(id) { view ->
                        view?.copy(running = false, stage = LxVerifyStage.IDLE)
                    }
                    is com.nasmusic.tv.backend.network.lx.LxProbeEvent.AttemptDone -> Unit
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            updateLxSession(id) { view -> view?.copy(running = false, stage = LxVerifyStage.IDLE) }
            throw cancelled
        } catch (t: Throwable) {
            AppLog.w("LxVerify", "[$id] 验证异常：${t.javaClass.simpleName}: ${t.message}")
            updateLxSession(id) { view -> view?.copy(running = false, stage = LxVerifyStage.FAILED) }
        }
    }

    private fun updateLxSession(id: String, transform: (LxScriptView?) -> LxScriptView?) {
        _lxSessions.update { current ->
            val next = transform(current[id]) ?: return@update current
            current + (id to next)
        }
    }

    private fun clearLxRunningFlags() {
        _lxSessions.update { current ->
            current.mapValues { (_, view) -> if (view.running) view.copy(running = false) else view }
        }
    }


    // ================= 天气电台（R-1：逻辑已迁至 WeatherRadioViewModel，以上为转发） =================

    // --- 详情页状态 ---
    private val _selectedAlbum = MutableStateFlow<Album?>(null)
    val selectedAlbum: StateFlow<Album?> = _selectedAlbum.asStateFlow()

    private val _selectedArtistName = MutableStateFlow<String?>(null)
    val selectedArtistName: StateFlow<String?> = _selectedArtistName.asStateFlow()

    private val _albumSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val albumSongsCache: StateFlow<Map<String, List<Song>>> = _albumSongsCache.asStateFlow()

    /** 正在加载曲目的专辑 id 集合（按 id 区分，快速切换专辑时互不串扰）。 */
    private val _albumDetailLoading = MutableStateFlow<Set<String>>(emptySet())
    val albumDetailLoading: StateFlow<Set<String>> = _albumDetailLoading.asStateFlow()

    /** 专辑曲目加载错误（按 id 存，UI 据此展示错误态 + 重试）。 */
    private val _albumDetailErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val albumDetailErrors: StateFlow<Map<String, String>> = _albumDetailErrors.asStateFlow()

    // --- 歌唱家拆分映射 ---
    // songId → 拆分后的歌唱家列表（不含去重中间状态，直接展开后的结果）
    private val _songArtistMap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val songArtistMap: StateFlow<Map<String, List<String>>> = _songArtistMap.asStateFlow()
    // 歌唱家 → 对应的歌曲列表
    private val _artistSongsMap = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistSongsMap: StateFlow<Map<String, List<Song>>> = _artistSongsMap.asStateFlow()

    // --- B-1 收藏（B-12: UiState）---
    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * 统一收藏 ID 集合（NAS + 网络/本地），UI 只读这一个，无需自行拼装。
     * - NAS 收藏：由 adapter.getFavorites() 填充 _favoriteIds（服务端存储）
     * - 网络/本地收藏：由 DataStore NetworkFavoriteItem 填充 networkFavoriteIds（本机存储）
     *
     * _favoriteIds 仍保留私有，供 toggleNetworkFavorite 的 NAS 分支读取"当前是否已收藏"。
     */
    // 2026-11-22：id 命名空间隔离契约——网络歌 id 恒以 "ntwk_" 开头（NetworkSongId.forNetworkSong），
    // 本地歌以 "local_" 开头（LocalMusicRepository），NAS id 不带这两种前缀。
    // 故 nas + net 按裸 id 合并不会串味；toggleNetworkFavorite 另加类型守卫双重保险。
    val favoriteIds: StateFlow<Set<String>> = combine(_favoriteIds, networkFavoriteIds) { nas, net ->
        nas + net
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private val _favoriteSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val favoriteSongs: StateFlow<UiState<List<Song>>> = _favoriteSongs.asStateFlow()

    // --- A-3 流派（B-12: UiState）---
    private val _genres = MutableStateFlow<UiState<List<Genre>>>(UiState.Success(emptyList()))
    val genres: StateFlow<UiState<List<Genre>>> = _genres.asStateFlow()

    // --- D-2 网络状态 ---
    private val _isNetworkAvailable = MutableStateFlow(true)
    val isNetworkAvailable: StateFlow<Boolean> = _isNetworkAvailable.asStateFlow()

    // --- 加载状态（R-1：连接加载态归 ServerViewModel，本地镜像合并供 AppRoot 单点订阅）---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // 合并 ServerViewModel 的加载态到本地通道
        viewModelScope.launch {
            serverVM.isLoading.collect { _isLoading.value = it }
        }
        // 洛雪引擎可达性：`loadLibrary` 要读磁盘 + 重定位，⛔ 必须在后台线程求值。
        // 判据只用「真的 dlopen 成功了吗」，不按 SDK 版本硬卡（方案 §7.7 第 7 条）。
        viewModelScope.launch(Dispatchers.IO) {
            when (val engine = com.nasmusic.tv.backend.network.lx.LxEngineAvailability.state) {
                is com.nasmusic.tv.backend.network.lx.EngineState.Available -> {
                    _lxEngineAvailable.value = true
                    _lxEngineUnavailableReason.value = null
                }
                is com.nasmusic.tv.backend.network.lx.EngineState.Unavailable -> {
                    _lxEngineAvailable.value = false
                    _lxEngineUnavailableReason.value = engine.reason
                }
            }
        }
    }

    // --- 歌词 ---
    private val _currentLyrics = MutableStateFlow<Lyrics?>(null)
    val currentLyrics: StateFlow<Lyrics?> = _currentLyrics.asStateFlow()

    private val _lyricsAvailability = MutableStateFlow(LyricsAvailability())
    val lyricsAvailability: StateFlow<LyricsAvailability> = _lyricsAvailability.asStateFlow()

    /**
     * 用户手动为这些歌曲选择了「在线歌词」。
     * 加载歌词时后端歌词优先于持久化缓存，但不得覆盖用户的显式选择。仅内存态。
     */
    // NewApi 修复（2026-09-14）：ConcurrentHashMap.newKeySet 需 API 24（minSdk 22）。
    // 改用 Collections.newSetFromMap（API 9+），语义等价：并发安全的可变 Set，
    // 同时消除由此产生的 KeySetView#contains/add/remove 三处 NewApi。
    private val userNetworkLyricsOverride: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // 歌词高亮模式 — 提升到 ViewModel，跨页面切换保留用户选择
    private val _lyricsHighlightMode = MutableStateFlow(LyricsHighlightMode.LINE_BY_LINE)
    val lyricsHighlightMode: StateFlow<LyricsHighlightMode> = _lyricsHighlightMode.asStateFlow()

    // --- 网络封面 URL（NAS 歌曲切到在线歌词时获取，参与封面轮播）---
    private val _networkCoverUrl = MutableStateFlow<String?>(null)
    val networkCoverUrl: StateFlow<String?> = _networkCoverUrl.asStateFlow()

    // --- 歌曲技术信息（编码格式、比特率等）---
    private val _songTechnicalInfo = MutableStateFlow<com.nasmusic.tv.data.model.SongTechnicalInfo?>(null)
    val songTechnicalInfo: StateFlow<com.nasmusic.tv.data.model.SongTechnicalInfo?> = _songTechnicalInfo.asStateFlow()

    // --- 播放统计（R-1 第四步：已迁至 PlayHistoryViewModel，此处转发）---
    val playHistoryVM = PlayHistoryViewModel(app)

    fun recordPlayEvent(song: Song, durationPlayedMs: Long) =
        playHistoryVM.recordPlayEvent(song, durationPlayedMs)


    /**
     * 异步获取当前歌曲的技术信息
     */
    fun loadSongTechnicalInfo() {
        viewModelScope.launch {
            val song = playerVM.playerState.value.currentSong ?: return@launch
            if (song.isNetworkSong) {
                _songTechnicalInfo.value = null
                return@launch
            }
            try {
                val adapter = backendRegistry.getAdapter()
                val info = adapter?.getSongTechnicalInfo(song.id)
                _songTechnicalInfo.value = info
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("MainViewModel", "loadSongTechnicalInfo failed", e)
                _songTechnicalInfo.value = null
            }
        }
    }

    // --- B-13: 播放器状态（R-1 第四步：已迁至 PlayerViewModel，此处转发）---
    val playerVM = PlayerViewModel(app, playerManager)

    // B-13: playMode 由 PlayerViewModel 拥有（UI/设置状态，不归 PlayerManager）

    // --- 连接状态（R-1：已迁至 ServerViewModel，保留本地 connectMessage 通道供多域共用）---
    private val _isLibraryLoading = MutableStateFlow(false)
    val isLibraryLoading: StateFlow<Boolean> = _isLibraryLoading.asStateFlow()

    // --- 连接结果提示消息（显示几秒后自动清除；主通道归 ServerViewModel，此处镜像合并）---
    private val _connectMessage = MutableStateFlow<String?>(null)
    val connectMessage: StateFlow<String?> = _connectMessage.asStateFlow()

    init {
        // 合并 ServerViewModel 的连接消息到本地通道（AppRoot 只订阅一处）
        viewModelScope.launch {
            serverVM.connectMessage.collect { _connectMessage.value = it }
        }
    }

    // --- D-3 常规错误消息（数据加载失败、操作失败等）---
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // --- 连接类瞬态消息（M6 修复：本地直写站点的统一入口）---
    // MainViewModel 内 8 处「写 _connectMessage + delay + 置 null」曾各自持有独立
    // 定时器（2s/5s 不等），后一条消息会被前一条的定时器提前清掉（M6）。
    // 统一走单调版本号清除；serverVM.postConnectMessage 已另行修复。
    private var connectMessageVersion: Long = 0L

    private fun postLocalConnectMessage(msg: String?, clearAfterMs: Long = 2000) {
        val token = ++connectMessageVersion
        _connectMessage.value = msg
        viewModelScope.launch {
            delay(clearAfterMs)
            if (token == connectMessageVersion) _connectMessage.value = null
        }
    }

    /** v2.35.0 多码率：对外暴露的轻量提示入口（UI 层需要主动报错时使用） */
    fun showMessage(msg: String) = showError(msg)

    /** M6 修复（2026-10-06）：消息单调版本号——旧定时器只允许清掉自己写入的那条消息 */
    private var errorMessageVersion: Long = 0L

    private fun showError(msg: String) {
        val token = ++errorMessageVersion
        _errorMessage.value = msg
        viewModelScope.launch {
            delay(5000)
            // M6 修复：仅当没有更新的消息写入时才清空——旧实现后一条消息会被
            // 前一条的 5s 定时器提前清掉（「提示闪现即逝」）
            if (token == errorMessageVersion) _errorMessage.value = null
        }
    }

    // --- 应用设置 ---
    val appSettings: StateFlow<AppSettings> = prefs.appSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    /**
     * v2.39.8（方案 §7.7 第 3 条）：**全部音乐源**关闭（NAS / 本地 / 网络三源）。
     *
     * 搜索页据此把「搜索无结果」换成「未启用任何音乐源」——
     * 前者会让用户以为网络坏了。判据必须覆盖聚合器实际参与搜索的五个源，
     * ⛔ 只算网络三源会在「NAS 开着但没结果」时误报（2026-11-21 审查 P1-4）。
     */
    val noMusicSourceEnabled: StateFlow<Boolean> = combine(
        prefs.appSettings,
        prefs.baidu.baiduConfigFlow,
        prefs.network.jamendoClientIdFlow,
    ) { s, baiduCfg, jamendoClientId ->
        !s.nasMusicEnabled && !s.localMusicEnabled &&
            !s.metingEnabled && !baiduCfg.isActive &&
            !(s.jamendoEnabled && jamendoClientId.isNotBlank())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    // 服务器配置
    val serverConfig: StateFlow<ServerConfig> = prefs.serverConfig.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ServerConfig.Empty
    )

    private var lyricsLoadJob: Job? = null
    /** 最近一次发起歌词加载的歌曲 id：同歌重复进入加载时用于复用已显示歌词 */
    private var lastLyricsLoadSongId: String? = null
    // 记录上一首歌的 ID，用于在切歌时统计播放记录
    private var lastRecordedSongId: String? = null
    private var lastRecordedSong: Song? = null
    /** H3 修复（2026-10-06）：30s 兜底快照。切歌记账已改为事件点直读 progress，
     *  此字段仅由兜底轮询维护，不再作为 recordPlayEvent 的数据源。 */
    private var lastRecordedPositionMs: Long = 0L
    /** 上一首歌的歌词来源，用于在播放完成时判断是否提交网络歌词到持久化缓存 */
    private var lastRecordedLyricsSource: LyricsSource? = null

    // =====================================================================
    // R-1 第二步拆分：Download / MvSearch / VocalSeparation / Visualizer 子 ViewModel。
    //
    // ⚠️ v2.36.2 崩溃修复：这四个声明原先位于文件尾部（原 2378 行）、在下方各 init 块
    // **之后**才初始化。而 init@688 的 currentSong 收集器运行在 Dispatchers.Main.immediate
    // 上 —— `viewModelScope.launch` 在主线程构造期间**同步**执行，StateFlow 首个值
    // 也同步送达，收集体首帧就会解引用 `mvVM`（song==null → resetIdle）/ `downloadVM`
    // （song!=null → onSongChanged）→ 属性尚为 null → 启动即 NPE 崩溃。
    // 声明必须先于所有引用它的 init 块（Kotlin 按文件顺序初始化）。
    // =====================================================================
    val downloadVM = DownloadViewModel(
        app,
        nasMusicApp.songDownloadManager,
        nasMusicApp.modelDownloadManager,
        nasMusicApp.autoDownloadController
    )
    val vocalVM = VocalSeparationViewModel(app, playerManager)
    val mvVM = MvSearchViewModel(app, mvSearchManager, playerManager)
    /** 全屏可视化舞台（20 套效果） */
    val visualizerVM = VisualizerViewModel(app, playerManager, prefs)

    init {
        viewModelScope.launch {
            // 初始化播放模式（B-13: 从预设置恢复）——R-1 后由 PlayerViewModel 持有
            val settings = prefs.appSettings.first()
            playerVM.initPlayModeFromSettings(settings.defaultPlayMode)

            // 升降调/变速是 K 歌会话内的临时效果（退出 K 歌页复位）：冷启动强制原调原速，
            // 不从持久化恢复历史值，VM 初值本身也是 0 / 1.0。这里复位播放器，避免老版本
            // 残留在存储里的调/速意外生效。
            playerManager.setPitch(0)
            playerManager.setSpeed(1.0f)
            AppLog.d("MainViewModel", "init: pitch=0, speed=1.0 (session-only, no restore)")
            // ⛔ 连接提示只在接线区 checkSavedConfigOnStart() 触发一次；这里不再读 serverConfig
            // （旧代码读完后是空 if 块，既不触发连接也误导读者以为有第二处入口）。
        }

        // API 版本号聚合只在接线区 serverVM.refreshApiVersionsAsync() 触发一次。
        // 旧实现这里另有一条 withContext(IO) { refreshApiVersions() } ⇒ 冷启动双份网络请求。

        // 百度网盘启动恢复（R-1：已迁至 NetworkMusicViewModel.restoreBaiduIndexOnStart，
        // 接线区已调用；API 错误回调在此注册，指向子 VM 状态）
        nasMusicApp.baiduPanApi.onApiError = { errno, desc ->
            if (errno == -6) {
                AppLog.w("BaiduAuth", "onApiError: errno=-6 (auth failed), setting state=Failed")
                netVM.onBaiduAuthFailed(desc)
            } else {
                AppLog.d("BaiduAuth", "onApiError: errno=$errno ($desc), not auth-related, ignored")
            }
        }

        // 监听 currentSong 变化，自动切歌时重新加载歌词，并记录播放历史
        viewModelScope.launch {
            playerVM.playerState.map { it.currentSong }.collect { song ->
                // 记录上一首歌的播放
                val previousSong = lastRecordedSong
                // H3 修复（2026-10-06，代码审查报告 §3）：切歌事件点直接读取实时进度，
                // 不再依赖 30s 轮询快照（旧实现下短歌被记成上一首的残留时长，
                // 且每首歌开头 30s 内快照恒为 0 → 完全不被记账）。
                // 时序依据：progress 由 PlayerManager 的 1s 轮询在主线程更新，切歌
                // （自然过渡 / next / previous / playSong）都不会先把 progress 置 0，
                // 故此处读到的正是上一首歌的最后位置（至多滞后 1s）。
                val previousPosition = playerVM.progress.value
                if (previousSong != null && previousPosition > 5000L
                    && previousSong.id != song?.id) {
                    recordPlayEvent(previousSong, previousPosition)
                    // 上一首播放完成 → 如果歌词来源是网络歌词，提交到持久化缓存
                    if (lastRecordedLyricsSource == LyricsSource.NETWORK) {
                        lyricsManager.commitPendingNetworkLyrics(previousSong)
                    }
                }

                if (song != null) {
                    // 自动下载判定链（总开关/可下载源/已下载/配额/空间）——切歌即触发，不等播放进度
                    downloadVM.onSongChanged(song)
                    // 记录当前歌词来源（在 loadLyricsForCurrentSong 清除 _currentLyrics 之前）
                    lastRecordedLyricsSource = _currentLyrics.value?.source
                    loadLyricsForCurrentSong()
                    // MTV 连播静默推进索引时跳过搜索（预搜结果已直接设为 Ready）
                    if (!mvVM.shouldSkipNextSearch()) {
                        mvVM.triggerMvSearch(song)
                    }
                    // 记录当前歌的开始
                    lastRecordedSong = song
                    lastRecordedSongId = song.id
                    // 记录到最近播放列表（自动切歌时也需要更新）
                    recordPlay(song)
                } else {
                    // 无当前歌曲（清空队列等）→ 重置 MV 状态
                    mvVM.resetIdle()
                }
            }
        }

        // 每 30 秒刷新一次播放位置快照（H3 修复后的兜底：正常记账已在切歌事件点
        // 直接读取实时 progress，此快照仅在长时间单曲循环/极端场景下兜底）。
        // ⚠️ 无需再维护 lastRecordedPositionMs 供切歌读取——已删除该字段。
        viewModelScope.launch {
            while (true) {
                delay(30000)
                if (playerVM.playerState.value.currentSong != null) {
                    lastRecordedPositionMs = playerVM.progress.value
                }
            }
        }

        // 监听下载完成：songDownloadStates 中 Completed 数量增加时即时刷新本地曲库
        // （SongDownloadManager.onCompleted 已把 ScannedSong 插入 local_songs，这里只需 reload）
        // P1-19: distinctUntilChanged 避免相同 count 值触发无谓的 reload（Downloading 高频进度更新时）
        viewModelScope.launch {
            var lastCompletedCount = 0
            downloadVM.songDownloadStates
                .map { states -> states.values.count { it is DownloadState.Completed } }
                .distinctUntilChanged()
                .collect { completedCount ->
                    if (completedCount > lastCompletedCount) {
                        lastCompletedCount = completedCount
                        _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                        updateMergedData()
                        // 刷新下载统计
                        downloadVM.refreshDownloadStats()
                    }
                }
        }

        // 监听网络收藏变化（R-1：已由 NetworkMusicViewModel 内部 collect，MainViewModel 派生视图直接读子 VM）

        // 监听本地歌单变化（已由 PlaylistViewModel 内部 collect）

        // 恢复上次播放队列（仅恢复 UI 状态，不自动播放）— 协程异步读取 DataStore，避免阻塞主线程
        viewModelScope.launch {
            restoreLastQueue()
        }

        // 监听队列变化，自动持久化到 DataStore
        viewModelScope.launch {
            playerVM.playerState.map { it.queue to it.currentIndex }.collect { (songs, index) ->
                if (songs.isNotEmpty()) {
                    prefs.queue.saveLastQueue(songs, index)
                }
            }
        }

        // ExoPlayer 自动过渡到 streamUrl 为空的歌曲时（如恢复队列中的网络歌曲），
        // 解析 streamUrl 后重新播放
        playerManager.onNeedResolveStreamUrl = { index ->
            playerVM.resolveAndPlayByIndex(index)
        }

        // 歌单导入 stub（URL 直链）播放失败 → 复测可达性 → 不可达走补全链（§4.1.8 (5)）
        playerManager.onPlaybackFailed = { song ->
            playbackFailure(song)
        }

        // 播放模式变化同步给 MvSearchViewModel 的唯一入口在下方接线区 init（原 3743 行附近）：
        // 这里曾有一条同样的 playMode.collect，两处都写同一个普通 var，属冗余双订阅。

        // 初始化网络音乐平台来源（从持久化存储读取）
        initMusicSource()

        // 当曲库数据加载完成时自动刷新首页仪表盘
        viewModelScope.launch {
            combine(_albums, _songs) { a, s ->
                a.isSuccess && s.isSuccess
            }.collect { loaded ->
                if (loaded && navVM.currentScreen.value == Screen.Home) {
                    loadHomeDashboard()
                }
            }
        }

        // 加载播放记录
        playHistoryVM.loadPlayRecords()

        // 本地音乐初始化（§5.2 门控，M1 修正版）：缓存加载**不设开关门**——
        // 读自家 Room 免权限，表内含已下载曲目（D3：已下载歌曲走应用专属目录，
        // 与 READ_MEDIA_AUDIO 无关，开关关闭时仍需出现在曲库）。
        // 增量扫描（M2）与 USB 监听（M3）受开关门控，由下方观察者按边沿 start/stop。
        viewModelScope.launch {
            try {
                // 1. 立即从缓存加载（毫秒级）
                _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                updateMergedData()
                AppLog.d("MainViewModel", "local music cache loaded: ${_localSongs.value.size} songs")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("MainViewModel", "load local music cache failed: ${e.message}", e)
            }
        }

        // 本地音乐总开关观察者（§5.4）：维护 _localMusicEnabled 镜像 + 按边沿驱动
        // 运行期（M2/M3）+ 开关翻转时重算合并数据（U8：本地曲目从曲库即时消失/出现）。
        // ⚠️ 首个值也触发 updateMergedData：冷启动时缓存加载可能先于 DataStore 首读完成，
        // 若上次开关为关，这里负责把已进入 merged 数据的本地部分修正掉。
        //
        // P1a（方案 §3.3 G1）：本观察者同时是**应用级本地开关镜像的唯一写入者**
        // （`NasMusicApp.cachedLocalMusicEnabled`）—— 见那里的「单一写入者」说明：
        // 两个镜像必须由同一处翻转，否则会短暂分叉 ⇒ 派生流按旧值算完且不再重算。
        viewModelScope.launch {
            var wasEnabled: Boolean? = null
            prefs.appSettings
                .map { it.localMusicEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    // ⛔ 顺序：先应用镜像、再 VM 镜像 ⇒ 两者在同一瞬间翻转
                    nasMusicApp.setLocalMusicEnabledMirror(enabled)
                    _localMusicEnabled.value = enabled
                    if (enabled) startLocalMusicRuntime() else stopLocalMusicRuntime()
                    if (wasEnabled != enabled) updateMergedData()
                    wasEnabled = enabled
                }
        }

        // 监听 NAS 专辑/艺术家加载完成，重新合并（本地+NAS 去重）
        viewModelScope.launch {
            _albums.collect {
                updateMergedData()
            }
        }
        viewModelScope.launch {
            _artists.collect {
                updateMergedData()
            }
        }
    }

    // --- 导航 ---

    /**
     * 恢复队列与 streamUrl 更新已迁至 PlayerViewModel（restoreLastQueue / updateRestoredQueueStreamUrls）。
     */
    private suspend fun restoreLastQueue() = playerVM.restoreLastQueue()
    private fun updateRestoredQueueStreamUrls() = playerVM.updateRestoredQueueStreamUrls()

    // --- 连接 ---
    /**
     * 连接域已迁至 ServerViewModel（R-1 第三步）。以下为兼容转发。
     */

    suspend fun connectToServer(config: ServerConfig): Boolean = serverVM.connectToServer(config)
    suspend fun refreshApiVersions() = serverVM.refreshApiVersions()

    /**
     * 增量构建艺术家映射（避免每次分页都全量重建）
     * @param newSongs 新增的歌曲列表
     */
    private fun buildArtistMapsIncremental(newSongs: List<Song>) {
        if (newSongs.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val songMap = _songArtistMap.value.toMutableMap()
            val artistMap = _artistSongsMap.value.mapValues { it.value.toMutableList() }.toMutableMap()

            for (song in newSongs) {
                val artists = ArtistSplitter.split(song.artist)
                songMap[song.id] = artists
                for (name in artists) {
                    // 归一化键：同一个人的不同写法（全角/空白/大小写）合并到一块
                    val key = ArtistSplitter.normalizeKey(name)
                    if (key.isBlank()) continue
                    artistMap.getOrPut(key) { mutableListOf() }.add(song)
                }
            }
            _songArtistMap.value = songMap
            _artistSongsMap.value = artistMap
        }
    }

    /**
     * 加载收藏状态（B-1, B-12: UiState）
     *
     * P3（方案 §3.4）：按 `Song` 的实际归属过滤 —— 关闭的源不列出其收藏（S1）。
     * ⛔ **只过滤展示，不删数据**（S3）：重开开关即恢复。
     * ⚠️ 过滤点定死在这里（VM 内一次）—— UI 侧不得再写第二份 filter。
     */
    private suspend fun loadFavorites(adapter: BackendAdapter) {
        // ⚠️ NAS 关 ⇒ **不请求**（方案 §3.2.1 #5）。
        // ⛔ 原始库存流不动；本流的清理由 clearSourceScopedCaches 负责（关源方向唯一入口）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadFavorites: NAS 已关闭，跳过请求")
            return
        }
        try {
            val favorites = adapter.getFavorites().filter { sourceVisibility.isVisible(it) }
            _favoriteSongs.value = UiState.Success(favorites)
            _favoriteIds.value = favorites.map { it.id }.toSet()
            AppLog.d("NASMusic", "loadFavorites: ${favorites.size} favorites")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.e("NASMusic", "loadFavorites failed", e)
            _favoriteSongs.value = UiState.Error(
                message = getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50))
            )
        }
    }

    /**
     * 加载流派列表（A-3, B-12: UiState）
     */
    private suspend fun loadGenres(adapter: BackendAdapter) {
        // ⚠️ NAS 关 ⇒ **不请求**（方案 §3.2.1 #4）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadGenres: NAS 已关闭，跳过请求")
            return
        }
        try {
            _genres.value = UiState.Success(adapter.getGenres())
            val data = _genres.value.dataOrNull()
            AppLog.d("NASMusic", "loadGenres: ${data?.size} genres")
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.e("NASMusic", "loadGenres failed", e)
            _genres.value = UiState.Error(
                message = getApplication<Application>().getString(R.string.load_genres_error, e.message?.take(50))
            )
        }
    }

    // --- 曲库（B-12: UiState）---
    private fun loadLibrary() {
        // ⚠️ NAS 关 ⇒ **不拉 NAS**（方案 §3.2.1 #1）。
        // ⛔ **不清空 `_albums` / `_songs`** —— 它们是原始库存流，是 S2 统计的读数来源（§3.0 契约）。
        // ✅ 但**仍要调 updateMergedData()**：本地 / 百度照常显示（合并时 NAS 段由可见性置空）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadLibrary: NAS 已关闭，跳过 NAS 拉取（保留本地/百度）")
            _isLibraryLoading.value = false
            updateMergedData()
            return
        }
        _isLibraryLoading.value = true
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                // 无 NAS 连接时，仍允许本地 + 百度数据展示
                _albums.value = UiState.Success(emptyList())
                _songs.value = UiState.Success(emptyList())
                _isLibraryLoading.value = false
                updateMergedData()
                return@launch
            }

            // 专辑、流派、收藏并行异步加载——不互相阻塞，也不阻塞首页就绪状态
            // 大曲库（3万+首）下 getAlbums 可能需要数十秒，不应阻塞 _isLibraryLoading
            launch {
                try {
                    AppLog.d("NASMusic", "loadLibrary: loading albums...")
                    val loadedAlbums = adapter.getAlbums()
                    _albums.value = UiState.Success(loadedAlbums)
                    AppLog.d("NASMusic", "loadLibrary: ${loadedAlbums.size} albums loaded")
                    loadHomeDashboard()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e("NASMusic", "loadLibrary albums failed", e)
                    _albums.value = UiState.Error(
                        message = getApplication<Application>().getString(R.string.load_albums_error, e.message?.take(50)),
                        retry = { loadLibrary() }
                    )
                }
            }

            launch { loadGenres(adapter) }
            launch { loadFavorites(adapter) }

            // 艺术家列表异步加载（不阻塞首页就绪）
            launch { loadArtists() }

            // 后台逐步加载全量歌曲：每加载一页立即显示，继续加载直到全部完成
            _songsPaging.value = SongsPagingState()
            loadAllSongsBackground()

            // 随心听随机歌曲异步加载（不阻塞首页就绪状态）
            launch { loadRandomSongs(adapter) }

            // 首页立即就绪——各数据源异步完成后自动更新 UI
            _isLibraryLoading.value = false
            AppLog.d("NASMusic", "loadLibrary: dispatched all async loads, home ready")
        }
    }

    /**
     * SONGS Tab 首次激活时加载第一页
     *
     * ⚠️ NAS 关 ⇒ **不拉首页**（方案 §3.2.1 #22）。
     * `totalCount` 的关源口径由派生的 [visibleSongsPaging] 处理（NAS 段不计入），
     * ⛔ 本函数**不写** totalCount，也不清空 `_songsPaging`（原始库存流）。
     */
    fun loadSongsFirstPage() {
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadSongsFirstPage: NAS 已关闭，跳过请求")
            return
        }
        if (_songsPaging.value.songs.isNotEmpty() || _songsPaging.value.isLoading) return
        loadSongsNextPage()
    }

    /**
     * 加载下一页歌曲（滚动到底部时触发）
     *
     * ⚠️ NAS 关 ⇒ **不拉分页**（方案 §3.2.1 #7）；**写回处再判一次**——
     * 关源瞬间正在飞的请求，回来后必须丢弃（方案 §5.7），否则旧 NAS 歌会写回分页状态。
     */
    fun loadSongsNextPage() {
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadSongsNextPage: NAS 已关闭，跳过请求")
            return
        }
        val state = _songsPaging.value
        if (state.isLoading || !state.hasMore || _isBackgroundLoadingAll.get()) return
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            _songsPaging.value = state.copy(isLoading = true)
            try {
                val offset = state.songs.size
                val batch = adapter.getSongs(pageSize, offset)
                // ⚠️ §5.7：在途请求的结果要丢弃（关源瞬间发出的请求不能写回）
                if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                    _songsPaging.value = _songsPaging.value.copy(isLoading = false)
                    AppLog.d("NASMusic", "loadSongsNextPage: NAS 已关闭，丢弃在途结果")
                    return@launch
                }
                val totalCount = if (state.totalCount == 0) adapter.getSongsTotalCount() else state.totalCount
                // v2.39.3（T7b）：区分「真没歌」与「加载失败」。
                // 判定逻辑抽到 SongsPagingState.resolveError（纯函数，便于单测覆盖）。
                // 要点：诊断优先于 totalCount —— 服务端 code!=0 时 total 也是 0。
                val loadError = SongsPagingState.resolveError(
                    batchSize = batch.size,
                    totalCount = totalCount,
                    diagnostic = backendRegistry.currentErrorDetail(),
                    parseFailedFallback = getApplication<Application>()
                        .getString(R.string.library_songs_parse_failed)
                )
                val newState = SongsPagingState(
                    songs = state.songs + batch,
                    totalCount = totalCount,
                    isLoading = false,
                    hasMore = batch.size == pageSize,
                    currentPage = state.currentPage + 1,
                    error = loadError
                )
                _songsPaging.value = newState
                // 同步更新 _songs（兼容现有 UI 依赖）
                _songs.value = UiState.Success(newState.songs)
                // 增量构建艺术家映射（仅处理新加载的批次，避免全量重建）
                buildArtistMapsIncremental(batch)
                if (loadError != null) {
                    AppLog.w("NASMusic", "loadSongsNextPage: empty page but total=$totalCount, error=$loadError")
                }
                AppLog.d("NASMusic", "loadSongsNextPage: loaded ${batch.size}, total ${newState.songs.size}/$totalCount")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadSongsNextPage failed", e)
                _songsPaging.value = state.copy(isLoading = false, error = e.message?.take(120))
                showError(getApplication<Application>().getString(R.string.load_songs_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 重试加载歌曲（v2.39.3）。
     *
     * 由空态里的「重试」按钮触发：先清空含 error 的旧状态（否则
     * `loadSongsFirstPage` 会因 `songs` 非空或 `isLoading` 直接 return），
     * 再重新拉第一页。
     */
    fun retrySongsLoad() {
        _songsPaging.value = SongsPagingState()
        _songs.value = UiState.Success(emptyList())
        loadSongsFirstPage()
    }

    /**
     * 后台全量加载歌曲：分页加载，每页立即显示，继续加载直到全部完成。
     * 与 loadSongsNextPage 共享 _songsPaging 状态，互斥运行。
     *
     * ⚠️ NAS 关 ⇒ **不发起全量拉取**（方案 §3.2.1 #8）。
     * 循环内**再判一次**：关源瞬间在途的批次要丢弃（方案 §5.7）。
     */
    private fun loadAllSongsBackground() {
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadAllSongsBackground: NAS 已关闭，跳过全量拉取")
            return
        }
        if (!_isBackgroundLoadingAll.compareAndSet(false, true)) return
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _isBackgroundLoadingAll.set(false)
                return@launch
            }
            try {
                var offset = 0
                while (true) {
                    val batch = adapter.getSongs(pageSize, offset)
                    if (batch.isEmpty()) break
                    // ⚠️ §5.7：在途请求的结果要丢弃（关源瞬间发出的批次不能写回）
                    if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                        AppLog.d("NASMusic", "loadAllSongsBackground: NAS 已关闭，丢弃在途批次")
                        break
                    }
                    val current = _songsPaging.value
                    val newState = SongsPagingState(
                        songs = current.songs + batch,
                        isLoading = false,
                        hasMore = batch.size == pageSize,
                        currentPage = current.currentPage + 1
                    )
                    _songsPaging.value = newState
                    _songs.value = UiState.Success(newState.songs)
                    buildArtistMapsIncremental(batch)
                    offset += batch.size
                    if (batch.size < pageSize) break
                }
                AppLog.d("NASMusic", "loadAllSongsBackground: done, ${_songsPaging.value.songs.size} songs")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadAllSongsBackground failed", e)
            } finally {
                _isBackgroundLoadingAll.set(false)
            }
        }
    }

    /**
     * 加载随机歌曲（随心听）：混合 NAS 后端 + 网络歌曲，首页展示 20 首。
     * - 已连 NAS：从后端拉取随机歌曲
     * - 网络音乐可用：从预设歌单随机抽取
     * - 两者混合，凑满 20 首
     * - 都不可用时 _randomSongs 保持空，首页不显示区块
     *
     * ### ⚠️ 采集期短路（方案 §3.2.1 #9 + §5.7b）
     *
     * - NAS 关 ⇒ **不取** NAS 随机曲（不请求）
     * - Meting 关 ⇒ **不取**预设歌单（不请求）
     * - ⚠️ 短路时**不要走**「失败保旧值」分支：那个分支只在「确实请求过但失败」时成立，
     *   短路走它会把旧 NAS 随机曲留在首页 ⇒ 改为按可见性过滤旧值。
     */
    private suspend fun loadRandomSongs(adapter: BackendAdapter?) {
        val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
        val metingVisible = sourceVisibility.isVisible(MusicSourceType.NETWORK_MUSIC)
        val allSongs = mutableListOf<Song>()
        // 1. NAS 后端随机歌曲（多拉一些确保够用）——NAS 关 ⇒ 不请求
        if (adapter != null && nasVisible) {
            try {
                val nasSongs = adapter.getRandomSongs(50)
                AppLog.d("NASMusic", "loadRandomSongs: got ${nasSongs.size} NAS songs")
                allSongs.addAll(nasSongs)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadRandomSongs NAS failed", e)
            }
        }
        // 2. 网络歌曲：从多个预设歌单随机抽取，凑够 20 首
        // ⚠️ Meting 关 ⇒ **不请求**（方案 §3.2.1 #9）：歌单列表直接置空，循环体一次都不进
        val shuffledPlaylists = if (metingVisible) preconfiguredPlaylists.shuffled() else emptyList()
        for ((playlistId, playlistName, _) in shuffledPlaylists) {
            if (allSongs.size >= 20) break
            try {
                val networkSongs = nasMusicApp.networkMusicManager.getPlaylist(playlistId)
                if (networkSongs.isNotEmpty()) {
                    AppLog.d("NASMusic", "loadRandomSongs: got ${networkSongs.size} songs from '${playlistName}'")
                    val tagged = networkSongs.map { it.copy(isNetworkSong = true, networkSource = NetworkSongId.METING_SOURCE) }
                    allSongs.addAll(tagged)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadRandomSongs network failed for '$playlistName'", e)
            }
        }
        // 3. 写回
        if (allSongs.isNotEmpty()) {
            _randomSongs.value = allSongs.shuffled().take(20)
        } else if (!nasVisible || !metingVisible) {
            // ⚠️ §5.7b：短路（关源）**不走**「失败保旧值」分支 —— 旧值里含已关源的歌，
            //    必须按可见性过滤掉（保留另一源的部分）。
            _randomSongs.value = _randomSongs.value.filter { sourceVisibility.isVisible(it) }
        } else if (_randomSongs.value.isEmpty() && adapter != null) {
            // 有后端但没拉到任何歌曲，可能是临时网络问题，下次刷新会重试
            _randomSongs.value = emptyList()
        }
        // 有后端但已有数据的不清空，保持区块可见
        AppLog.d("NASMusic", "loadRandomSongs: total ${_randomSongs.value.size} songs")
    }

    fun loadRandomSongs() {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter()
            loadRandomSongs(adapter)
        }
    }

    /**
     * 播放随机歌曲（随心听），启动自动续播。
     */
    fun playRandomSongs(songs: List<Song>, startIndex: Int) {
        // P0 修复（2026-11-22）：随心听续播标志必须在 playQueue **之后**置位。
        // playQueue() 内部（L3702-3703）无条件 `_isShufflePlaying = false` + 取消 refill job；
        // 旧实现先置 true 再调 playQueue ⇒ 标志被立刻复位 ⇒ startShuffleRefill 的
        // `while (_isShufflePlaying)` 一次都不进 ⇒ 自动续播整段是死代码。
        // ⛔ 不要再把置位挪回 playQueue 之前，也不要在 playQueue 里"顺手"保留 shuffle
        //   ——那会让普通点歌也意外开启续播。顺序即语义。
        playQueue(songs, startIndex)
        navVM.navigateTo(Screen.NowPlaying)
        _isShufflePlaying = true
        startShuffleRefill()
    }

    private var _isShufflePlaying = false

    /**
     * 随心听自动续播。
     *
     * ⚠️ NAS 关 ⇒ **不续播 NAS 随机曲**（方案 §3.2.1 #10）：不请求。
     * ⚠️ 关源期间本协程**继续活着**（用户可能已开启本地/网络段的播放），
     *    所以这里只是「本轮不请求」，⛔ 不要 break 掉整个续播循环。
     */
    private fun startShuffleRefill() {
        shuffleRefillJob?.cancel()
        shuffleRefillJob = viewModelScope.launch {
            while (_isShufflePlaying) {
                val queue = playerManager.playerState.value.queue
                val currentIdx = playerManager.playerState.value.currentIndex
                val remaining = queue.size - currentIdx
                if (remaining <= 5) {
                    if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                        // NAS 关 ⇒ 不续播 NAS 随机曲（不请求）
                        delay(5000)
                        continue
                    }
                    val adapter = backendRegistry.getAdapter() ?: break
                    try {
                        val newSongs = adapter.getRandomSongs(20)
                        if (newSongs.isNotEmpty()) {
                            playerManager.addToQueue(newSongs)
                        }
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        AppLog.e("NASMusic", "shuffleRefill failed", e)
                        delay(30000) // 失败后等 30 秒再试
                        continue
                    }
                }
                delay(5000)
            }
        }
    }

    /**
     * ARTISTS Tab 首次激活时加载艺术家列表（独立 API）
     */
    fun loadArtists() {
        // ⚠️ NAS 关 ⇒ **不拉 NAS 艺术家**（方案 §3.2.1 #2）。
        // ⛔ 原始库存流 `_artists` 不动（S2 统计读它）；合并侧的 NAS 段由 updateMergedData 置空。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadArtists: NAS 已关闭，跳过请求")
            // ⛔ 必须落到 Success(emptyList())：否则 _artists 永远停在 Loading，
            // LibraryBranch 据此显示的骨架屏再也不会消失（关 NAS 时本地/百度艺术家
            // 仍由 updateMergedData 合成，NAS 段传空即可，统计读原始流也不受影响）。
            if (_artists.value !is UiState.Success) {
                _artists.value = UiState.Success(emptyList())
            }
            return
        }
        if (_artists.value is UiState.Success && (_artists.value as UiState.Success).data.isNotEmpty()) return
        _artists.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _artists.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                val artistsList = adapter.getArtists()
                // 保存原始艺术家列表（拆分前），用于 Navidrome 多 ID 联合查询合作歌曲
                _rawArtistList = artistsList
                // 对合作歌曲的艺术家名进行拆分（如 "AAA & BBB" → "AAA", "BBB"）
                // 使每个参与者都独立出现在艺术家列表中
                val splitArtists = artistsList.flatMap { artist ->
                    val names = ArtistSplitter.split(artist.name)
                    if (names.size > 1) {
                        names.map { name ->
                            artist.copy(
                                id = "${artist.id}|$name",  // 唯一 ID，用于 Grid key
                                name = name
                            )
                        }
                    } else {
                        listOf(artist)
                    }
                }
                // 合并重复艺术家（同一名字可能来自独立条目和拆分条目）
                // 归一化去重：NFKC + trim + 折叠空白 + 小写，
                // 避免 "古天乐" 与 "古天乐 " 这类肉眼同名、字符串不同的条目变成两块
                val mergedMap = linkedMapOf<String, Artist>()
                for (item in splitArtists) {
                    val key = ArtistSplitter.normalizeKey(item.name)
                    if (key.isBlank()) continue
                    val existing = mergedMap[key]
                    mergedMap[key] = if (existing == null) item else existing.copy(
                        songCount = maxOf(existing.songCount, item.songCount),
                        albumCount = existing.albumCount + item.albumCount,
                        coverUrl = existing.coverUrl ?: item.coverUrl
                    )
                }
                val merged = mergedMap.values.toList()
                _artists.value = UiState.Success(merged)
                AppLog.d("NASMusic", "loadArtists: ${artistsList.size} raw → ${merged.size} after splitting")
                // 触发合并（updateMergedData 末尾会解析 NAS + 百度 + 本地缺失的艺术家封面）
                updateMergedData()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadArtists failed", e)
                _artists.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.library_artists_load_failed, e.message?.take(50)),
                    retry = { loadArtists() }
                )
            }
        }
    }

    /**
     * YEARS Tab 首次激活时加载年份列表（独立 API）
     */
    fun loadYears() {
        // ⚠️ NAS 关 ⇒ **不拉 NAS 年份**（方案 §3.2.1 #3）。
        // ⛔ 不动 `_years` 的清空语义（那是 clearSourceScopedCaches 的职责）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadYears: NAS 已关闭，跳过请求")
            return
        }
        if (_years.value is UiState.Success && (_years.value as UiState.Success).data.isNotEmpty()) return
        _years.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _years.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                val yearsList = adapter.getYears()
                _years.value = UiState.Success(yearsList)
                AppLog.d("NASMusic", "loadYears: ${yearsList.size} years loaded")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadYears failed", e)
                _years.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_year_songs_error, e.message?.take(50)),
                    retry = { loadYears() }
                )
            }
        }
    }

    /**
     * RECENT Tab 首次激活时按需批量查询最近播放歌曲
     *
     * P3（方案 §3.4）：按 `Song` 的实际归属过滤 —— 关闭的源不列出其历史（S1）。
     * ⛔ **只过滤展示，不删库**（S3）：数据库里的记录仍在，重开开关即恢复（验收 V8）。
     * ⚠️ 过滤点定死在这里（VM 内一次）—— UI 侧不得再写第二份 filter。
     */
    // P2 修复（2026-11-22）：并发代数。HomeBranch/MineBranch/refreshLibrary 都可能并发调
    // loadRecentSongs，旧实现后到的旧结果会覆盖新结果；代数闸门只接受最后一次调用的结果。
    private var recentSongsGeneration = 0

    fun loadRecentSongs(showLoading: Boolean = true) {
        val generation = ++recentSongsGeneration
        if (showLoading) _recentSongs.value = UiState.Loading
        viewModelScope.launch {
            try {
                // 读取持久化的最近播放完整歌曲对象（含网络歌曲，不依赖 NAS 连接）
                val recent = prefs.history.getRecentSongObjects().filter { sourceVisibility.isVisible(it) }
                if (generation == recentSongsGeneration) _recentSongs.value = UiState.Success(recent)
                AppLog.d("NASMusic", "loadRecentSongs: ${recent.size} recent songs loaded (gen=$generation)")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadRecentSongs failed", e)
                if (generation == recentSongsGeneration) {
                    _recentSongs.value = UiState.Error(
                        message = getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50)),
                        retry = { loadRecentSongs() }
                    )
                }
            }
        }
    }

    // --- 多维度浏览（语种/纯音乐/年代/情怀/风格） ---

    /** 各维度当前选中的选项索引，默认全是 0（"所有"） */
    private val _browseSelections = MutableStateFlow(
        BrowseDimension.entries.map { 0 }
    )
    val browseSelections: StateFlow<List<Int>> = _browseSelections.asStateFlow()

    /** 浏览搜索结果 */
    private val _browseResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val browseResults: StateFlow<UiState<List<Song>>> = _browseResults.asStateFlow()

    /** 当前是否正在搜索 */
    private val _isBrowseSearching = MutableStateFlow(false)
    val isBrowseSearching: StateFlow<Boolean> = _isBrowseSearching.asStateFlow()

    /**
     * 设置某个维度的选中选项并自动刷新。
     * @param dimensionIndex BrowseDimension.entries 中的索引
     * @param optionIndex 该维度 options 列表中的索引
     */
    fun selectBrowseOption(dimensionIndex: Int, optionIndex: Int) {
        val current = _browseSelections.value.toMutableList()
        if (dimensionIndex !in current.indices) return
        // 仅选中项真正变化时才重置去重集合并发起搜索；重复点同一 chip 不再触发跨源查找
        if (current[dimensionIndex] == optionIndex) return
        // 筛选条件变化 = 新上下文，重置浏览已见集合（跨批次去重从头开始）
        browseSeenKeys.clear()
        current[dimensionIndex] = optionIndex
        _browseSelections.value = current
        refreshBrowseSongs()
    }

    /**
     * 确保浏览结果已加载（幂等）：首次加载执行过即跳过。
     *
     * 供"重新进入发现页面"触发：主tab/子tab切换回来时不重复搜索——与搜索页的
     * lastSearchedKeyword 缓存逻辑一致，缓存判断放在 ViewModel 层而非依赖 UI
     * collectAsState 初始值。用 [browseLoadedOnce] 而非"结果非空"判定，避免全 ALL
     * 空结果时每次切回都重跑。失败态由 UI 的重试按钮显式触发，不走此入口。
     */
    fun ensureBrowseLoaded() {
        // 首次加载已执行过（含全 ALL 空结果）→ 切页不再重复搜索。
        // 空结果此前被误判为"未加载"，导致每次切回发现页都重跑一轮跨源搜索。
        if (browseLoadedOnce) return
        // 正在搜索中 → 不重复触发
        if (_browseResults.value is UiState.Loading) return
        browseLoadedOnce = true
        refreshBrowseSongs()
    }

    /**
     * 刷新浏览结果：收集非"所有"选项的关键词，随机各取一个，组合搜索。
     *
     * 与网络搜索「换一批」共用同一套跨批次去重逻辑：在多个随机关键词组合中
     * 挑选新歌最多的批次展示，保证每次「换一批」只出新歌。
     */
    fun refreshBrowseSongs() {
        // 并发闸门（问题2）：新刷新先取消在途刷新，代数自增使旧 Job 的写结果/清标志失效。
        // 快速连点"换一批"或连续切维度时，只认最后一次请求的结果与 loading 状态。
        browseRefreshJob?.cancel()
        val generation = ++browseRefreshGeneration

        val dimensions = BrowseDimension.entries
        val selections = _browseSelections.value

        // 收集非 ALL 选项的关键词
        val keywordList = mutableListOf<String>()
        for (i in dimensions.indices) {
            val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                ?: continue
            if (opt.isAll) continue  // M8 修复：结构化标志替代文案哨兵，label 本地化不影响判定
            if (opt.keywords.isEmpty()) continue
            // 从该选项的关键词列表中随机选一个
            keywordList.add(opt.keywords.random())
        }

        if (keywordList.isEmpty()) {
            // 无任何筛选：清空结果，同时确保 loading 标志复位（旧 Job 的 finally 因代数失配不会清）
            _isBrowseSearching.value = false
            _browseResults.value = UiState.Success(emptyList())
            return
        }

        _isBrowseSearching.value = true
        _browseResults.value = UiState.Loading

        browseRefreshJob = viewModelScope.launch {
            try {
                // 构建组合关键词（与"换一批"逻辑一致），但跨源聚合搜索
                // 构建组合关键词（展开词，给网络/NAS/Jamendo 用，支持换一批多样性）
                fun buildCombo(): String {
                    val combo = mutableListOf<String>()
                    for (i in dimensions.indices) {
                        val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                            ?: continue
                        if (opt.isAll) continue  // M8 修复：结构化标志替代文案哨兵，label 本地化不影响判定
                        if (opt.keywords.isEmpty()) continue
                        combo.add(opt.keywords.random())
                    }
                    return combo.filter { it.isNotBlank() }.joinToString(" ").trim()
                }

                // 构建维度标签组合（给百度用，目录+API 效果好）
                fun buildLabelCombo(): String {
                    val labels = mutableListOf<String>()
                    for (i in dimensions.indices) {
                        val opt = dimensions[i].options.getOrNull(selections.getOrNull(i) ?: 0)
                            ?: continue
                        if (opt.isAll) continue  // M8 修复：结构化标志替代文案哨兵，label 本地化不影响判定
                        labels.add(opt.label)
                    }
                    return labels.filter { it.isNotBlank() }.joinToString(" ").trim()
                }

                // 构造一次聚合器（produce 内多次调用复用同一实例）
                val aggregator = nasMusicApp.searchAggregator
                // 用**有效点亮集**（用户点亮 ∩ 设置里开着的源）——
                // 设置里已关闭的源不能参与发现页搜索（方案 §5.3：chip 显示 / 结果 / 集合三者一致）
                val enabledSources = searchVM.effectiveEnabledSearchSources.value
                val baiduLabelCombo = buildLabelCombo()

                val (_, shown) = pickBestFreshBatch(
                    seenKeys = browseSeenKeys,
                    produce = {
                        // 跨源聚合器：按当前点亮来源并行搜索（NAS/网络/百度/Jamendo）
                        // 网络/NAS/Jamendo 用展开词（支持换一批多样性）
                        // 百度用维度标签（目录+API 效果好）
                        // filterMode=NONE：发现页宽泛，各源返回什么就展示
                        val keyword = buildCombo()
                        if (keyword.isBlank()) return@pickBestFreshBatch emptyList()
                        aggregator.search(
                            keyword,
                            sources = enabledSources,
                            directoryMode = true,
                            baiduKeyword = baiduLabelCombo,
                            filterMode = FilterMode.NONE
                        ).allResults.map { it.song }
                    },
                    songsOf = { it }
                )
                // 仅当前代写入结果（旧请求即使跑完也不得覆盖新请求）
                if (generation == browseRefreshGeneration) {
                    _browseResults.value = UiState.Success(shown)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                if (generation == browseRefreshGeneration) {
                    AppLog.e("NASMusic", "refreshBrowseSongs failed: ${e.message}", e)
                    _browseResults.value = UiState.Error(
                        message = getApplication<Application>().getString(R.string.browse_search_failed, e.message?.take(50))
                    )
                }
            } finally {
                // 仅当前代清理 loading（旧请求被取消时不得提前结束新请求的加载态）
                if (generation == browseRefreshGeneration) {
                    _isBrowseSearching.value = false
                }
            }
        }
    }

    /**
     * 播放全部浏览结果。
     * 加入队列前按（歌手, 歌曲名）去重、最多取 [maxNetworkBatchPlayCount] 首——
     * 与搜索页 [playAllSearchResults] 完全同口径（问题5：跨源同曲多版本 + 无限量）。
     * 不触发导航，由调用方（AppRoot）处理 navigateTo(NowPlaying)。
     */
    fun playAllBrowseSongs() {
        val results = _browseResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val deduped = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .take(maxNetworkBatchPlayCount)
        if (deduped.isEmpty()) return
        playNetworkBatch(deduped, 0)
    }

    /**
     * 播放全部搜索结果。
     *
     * 加入队列前按（歌手, 歌曲名）去重；去重后最多取 30 首，
     * 不足 30 首时有多少加多少。不触发导航，由调用方（AppRoot）处理 navigateTo(NowPlaying)。
     */
    fun playAllSearchResults() {
        val results = searchVM.networkSearchResults.value.dataOrNull() ?: return
        if (results.isEmpty()) return
        val deduped = results
            .distinctBy { it.artist.trim() to it.title.trim() }
            .take(maxNetworkBatchPlayCount)
        if (deduped.isEmpty()) return
        playNetworkBatch(deduped, 0)
    }

    /**
     * 批量播放网络歌曲。
     *
     * 性能优化：不再预先串行解析全部歌曲的播放链接（最多 30 首串行网络请求，
     * 会导致"全部播放"后等待很久才更新队列并开始播放）。
     * 改为只即时解析 [startIndex] 处第一首，立即更新队列并开始播放；
     * 后续歌曲沿用已有的 onNeedResolveStreamUrl → resolveAndPlayByIndex 懒加载机制，
     * 在播放到该曲（onMediaItemTransition AUTO）或切歌时按需解析，
     * 与单首网络歌曲及"恢复队列"的播放路径一致。
     * 首首串行解析的总延迟由 30×RTT 降至 1×RTT。
     */
    private fun playNetworkBatch(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val safeStart = startIndex.coerceIn(0, songs.lastIndex)
        viewModelScope.launch {
            val first = songs[safeStart]
            // 已下载优先：首曲存在本地文件则直接播本地（离线可播）；
            // 后续歌曲仍走 resolveAndPlayByIndex 懒加载（PlayerViewModel 内含同样的已下载优先逻辑）
            val localFirstUrl = runCatching {
                nasMusicApp.downloadRepository.playableLocalUri(first)
            }.getOrNull()
            val resolvedFirst = when {
                localFirstUrl != null -> first.copy(streamUrl = localFirstUrl)
                first.streamUrl.isNullOrBlank() -> {
                    try {
                        val url = nasMusicApp.networkMusicManager.resolvePlayUrl(first)
                        if (!url.isNullOrBlank()) first.copy(streamUrl = url) else first
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        AppLog.e("NASMusic", "playNetworkBatch: resolve first failed for ${first.title}", e)
                        first
                    }
                }
                else -> first
            }
            val queue = songs.toMutableList().apply { this[safeStart] = resolvedFirst }
            playQueue(queue, safeStart)
        }
    }

    // ================= N-1 保留的胶水转发（非纯透传，含跨域参数拼接） =================
    fun connectToSavedServer(silent: Boolean = false) = serverVM.connectToSavedServer(silent)
    /**
     * P1 修复（2026-11-22）：新增 [onPlayed] 成功回调。
     *
     * 网络歌的直链解析（resolvePlayUrl / 已下载本地优先）是异步的；旧实现各分支在调用本方法后
     * **立即** navigate 到播放页，解析失败时只剩一个 toast，用户被困在"没歌在播"的播放页。
     * 现在把导航交给调用方放进 [onPlayed]（仅在 onPlaySong 真正触发、即播放开始时回调），
     * 失败则不导航——用户停留在原页面并看到错误提示。默认 null 保持其它调用点源码兼容。
     *
     * ⚠️ onPlayed 在 netVM 的 onPlaySong 里同步回调；非网络歌该回调也是立即触发，语义不变。
     */
    fun playNetworkSong(song: Song, onPlayed: (() -> Unit)? = null) {
        // 导入 stub 单曲播放：播放前触发后台补全（不阻塞，命中后写回歌单）
        triggerEnrichForStubs(listOf(song))
        netVM.playNetworkSong(song, onPlaySong = { playable ->
            playerVM.playSong(playable)
            onPlayed?.invoke()
        })
    }
    fun toggleNetworkFavorite(song: Song) {
        // 天气电台对 NAS 歌曲打了 nas_/nasf_ 合成前缀（命中/补齐分类依赖前缀），
        // 若按合成 id 记账，收藏集合里会存一个永远匹配不上展示id（`strip` 后）的幽灵条目，
        // 心形点了也不亮。与 recordPlay 同口径在此入口剥离，全路径统一真实 id。
        val normalized = song.stripSyntheticRadioPrefix()
        netVM.toggleNetworkFavorite(
            normalized,
            // 2026-11-22：加来源类型守卫。网络/本地歌曲的 id 有独占前缀（ntwk_/local_），
            // 绝不应按裸 id 去 NAS 收藏集合里判定"当前是否已收藏"——即便 NAS 后端未来返回
            // 同形 id 也不会误走 NAS 分支。非网络非本地才查 NAS 集合。
            isNasFavorite = !normalized.isNetworkSong && !normalized.isLocalSong && normalized.id in _favoriteIds.value,
            onNasToggle = { nasSong, isFav -> toggleNasFavorite(nasSong, isFav) }
        )
    }
    fun searchSongsOnServer(query: String, force: Boolean = false) = searchVM.searchSongsOnServer(query, force)
    fun addAllSearchResultsToQueue() = searchVM.addAllSearchResultsToQueue(
        existingQueueKeysProvider = {
            playerManager.playerState.value.queue.map { it.artist.trim() to it.title.trim() }.toSet()
        }
    )
    fun deleteModel() = downloadVM.deleteModel(onModeFallback = { vocalVM.onModelDeleted() })
    fun onMvPlaybackError() = mvVM.onMvPlaybackError(playerVM.playerState.value.currentSong)
    fun onMvPlaybackEnded() = mvVM.onMvPlaybackEnded(playerVM.playerState.value.currentSong, playerVM.playMode.value)
    fun onMvPrevious() = mvVM.onMvPrevious(playerVM.playMode.value)
    fun onMvNext() = mvVM.onMvNext(playerVM.playMode.value)
    fun onSwitchOrResearch() = mvVM.onSwitchOrResearch(playerVM.playerState.value.currentSong)
    fun onSearchBilibili() = mvVM.onSearchBilibili(playerVM.playerState.value.currentSong)

    /** NAS 收藏分支（留在 MainViewModel：依赖本类 _favoriteIds/_favoriteSongs 状态） */
    private fun toggleNasFavorite(song: Song, isCurrentlyFavorite: Boolean) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.toggleFavorite(song.id, isCurrentlyFavorite)
                if (success) {
                    val newIds = _favoriteIds.value.toMutableSet()
                    if (isCurrentlyFavorite) {
                        newIds.remove(song.id)
                        val currentFavs = _favoriteSongs.value.dataOrNull() ?: emptyList()
                        _favoriteSongs.value = UiState.Success(currentFavs.filter { it.id != song.id })
                    } else {
                        newIds.add(song.id)
                        val currentFavs = _favoriteSongs.value.dataOrNull() ?: emptyList()
                        _favoriteSongs.value = UiState.Success(currentFavs + song)
                    }
                    _favoriteIds.value = newIds
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "toggleFavorite failed", e)
showError(getApplication<Application>().getString(R.string.toggle_favorite_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 判断网络歌曲是否已收藏（同步，用于 UI 快速判断）
     */

    private val preconfiguredPlaylists = listOf(
        Triple("3778678", "热歌榜", "netease"),
        Triple("3779629", "新歌榜", "netease"),
        Triple("19723756", "飙升榜", "netease"),
        Triple("3136952023", "华语流行", "netease"),
        Triple("60198", "欧美流行", "netease"),
        Triple("377165088", "抖音热门", "netease"),
        Triple("2211745987", "经典老歌", "netease"),
        Triple("2884035", "原创音乐榜", "netease"),
        Triple("377733686", "ACG 音乐榜", "netease"),
        Triple("3117263287", "纯音乐榜", "netease"),
        Triple("377237077", "古风榜", "netease"),
        Triple("5390174047", "日语流行", "netease"),
        Triple("5059631514", "K-POP 热榜", "netease"),
        Triple("3248421784", "说唱榜", "netease"),
    )

    fun refreshLibrary() {
        _albums.value = UiState.Loading
        _songs.value = UiState.Loading
        _songsPaging.value = SongsPagingState()
        _artists.value = UiState.Success(emptyList())
        _years.value = UiState.Success(emptyList())
        _recentSongs.value = UiState.Success(emptyList())
        searchVM.clearSearch()
        loadLibrary()
    }

    // =====================================================================
    // R-1 第三步转发区：Search / NetworkMusic（百度网盘）
    // =====================================================================

    // ---- SearchViewModel 转发 ----

    // ---- NetworkMusicViewModel（百度网盘）转发 ----
    /** 百度索引缓存（曲库合并/详情取数仍由 MainViewModel 使用） */
    private val baiduIndexCache: BaiduFileIndexCache get() = nasMusicApp.baiduFileIndexCache

    val baiduDeviceCode get() = netVM.baiduDeviceCode
    val netdiskCurrentDir get() = netVM.netdiskCurrentDir
    val netdiskDirFiles get() = netVM.netdiskDirFiles
    val netdiskIsLoading get() = netVM.netdiskIsLoading
    val netdiskSearchResults get() = netVM.netdiskSearchResults
    val netdiskSearchKeyword get() = netVM.netdiskSearchKeyword
    val baiduIndexScanned get() = netVM.baiduIndexScanned
    val baiduIndexScanning get() = netVM.baiduIndexScanning
    val baiduIndexLastSync get() = netVM.baiduIndexLastSync
    val baiduApicExtracting get() = netVM.baiduApicExtracting
    val baiduApicExtracted get() = netVM.baiduApicExtracted
    val baiduApicTotal get() = netVM.baiduApicTotal

    suspend fun listBaiduDirs(path: String) = netVM.listBaiduDirs(path)
    fun playAllNetdiskDir(dir: String, onPlayAll: (List<Song>) -> Unit) = netVM.playAllNetdiskDir(dir, onPlayAll)

    /**
     * 刷新合并后的专辑 / 艺术家列表
      * NAS 数据（albums/artists） + 本地歌曲（localSongs）+ 百度网盘索引 按 name 去重合并
      */
    private fun updateMergedData() {
        // C35：20+ 个入口都会调到这里（库存变化、设置开关、网盘索引刷新、扫描完成…）。
        // 旧实现每次新起一个 Default 协程，既取消不了旧的、也没有代数闸门 ⇒
        // 多个协程并发扫库存 + 回写 _mergedAlbums/_mergedArtists，最后写入的可能反而是
        // 用**旧快照**算出来的结果。这里保存 Job 并取消上一轮（本轮总是以最新库存为准，
        // 取消旧轮不会丢数据：新一轮会重新读 StateFlow 的当前值）。
        mergeJob?.cancel()
        mergeJob = viewModelScope.launch(Dispatchers.Default) {
            // ⛔ 构造期竞态防护：构造未完成（by lazy 委托未初始化）前一律挂起等待，
            // 详见 constructorReady 的 KDoc（v2.37.0 启动 NPE 修复）
            constructorReady.await()
            // P1a（方案 §3.3 G1）：按可见性过滤后再合并 —— 关 NAS ⇒ NAS 专辑/艺术家传空，
            // 关百度 ⇒ 百度索引不读、传空（**当前最严重的一处逃逸**），本地照旧。
            //
            // ⛔ **不清空 `_albums` / `_songs` / `_songsPaging` / `_artists`** —— 它们是**原始库存流**，
            // 是 S2 统计（首页「专辑 N / 歌曲 N」）的读数来源（方案 §3.0 契约）。
            // 过滤只发生在**合成期**，统计数字天然不受影响。
            //
            // ⚠️ 过滤必须**在传入之前**做：⛔ 不能合并完再按 `sourceIds` 剔除 —— 那会把
            // 「NAS 与百度同名专辑」整条删掉，连同 NAS 那一份（方案 §5.4）。
            val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
            val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
            val nasAlbums = if (nasVisible) (_albums.value.dataOrNull() ?: emptyList()) else emptyList()
            val nasArtists = if (nasVisible) (_artists.value.dataOrNull() ?: emptyList()) else emptyList()
            val localSongs = visibleLocalSongsNow()
            // NAS 歌曲段（关 NAS 时为空）：供 updateArtistSongCounts 统计曲目数
            val nasSongs = if (nasVisible) _songsPaging.value.songs else emptyList()
            // 百度段：关源时**不读索引**（省一次磁盘解析）+ 传空
            val baiduSongs = if (baiduVisible) baiduIndexCache.allSongs() else emptyList()
            // 歌曲 Tab 与专辑/艺术家共用这份索引结果，避免 UI 侧再触发一次磁盘解析
            // ⚠️ 百度关时置空 ⇒ UI 直读 `_baiduIndexedSongs` 的地方（曲库歌曲 Tab）也一并清掉
            _baiduIndexedSongs.value = baiduSongs

            var mergedAlbums = MusicMerger.mergeAlbums(
                nasAlbums = nasAlbums,
                localAlbums = MusicMerger.buildLocalAlbums(localSongs),
                baiduAlbums = MusicMerger.buildBaiduAlbums(baiduSongs)
            )
            // 应用已解析的封面缓存，避免每次重建丢失 coverUrl
            if (resolvedAlbumCovers.isNotEmpty()) {
                mergedAlbums = mergedAlbums.map { album ->
                    val cached = resolvedAlbumCovers[album.id]
                    if (cached != null && album.coverUrl == null) album.copy(coverUrl = cached) else album
                }
            }
            _mergedAlbums.value = mergedAlbums

            var mergedArtists = MusicMerger.mergeArtists(
                nasArtists = nasArtists,
                localArtists = MusicMerger.buildLocalArtists(localSongs),
                baiduArtists = MusicMerger.buildBaiduArtists(baiduSongs)
            )
            // 应用已解析的艺术家封面缓存
            if (resolvedArtistCovers.isNotEmpty()) {
                mergedArtists = mergedArtists.map { artist ->
                    val cached = resolvedArtistCovers[artist.id]
                    if (cached != null && artist.coverUrl == null) artist.copy(coverUrl = cached) else artist
                }
            }
            _mergedArtists.value = mergedArtists

            // 用全量歌曲反向统计艺术家 songCount（复用已加载的 baiduSongs，避免再次 allSongs()）
            // ⚠️ NAS 段传**过滤后**的歌曲：关 NAS 后本地/百度艺术家卡片的曲目数不得虚高
            // （原先内部自读 `_songsPaging.value.songs` ⇒ 读到未过滤的原始库存）。
            updateArtistSongCounts(nasSongs, baiduSongs)

            // 异步解析缺失封面（百度侧车/APIC → iTunes → 本地ID3 → 歌曲 coverUrl 兜底）
            resolveAlbumCoversAsync(baiduSongs)
            // 异步解析缺失的艺术家封面（iTunes → 百度音乐）。
            // 读 _mergedArtists 覆盖 NAS + 本地 + 百度三个来源；
            // 百度/本地艺术家在 updateMergedData 生成，此前只靠 loadArtists（NAS 连接）触发导致漏解析。
            resolveArtistCoversAsync(baiduSongs)
        }
    }

    /**
     * 用全量歌曲（NAS + 本地 + 百度）反向统计每个艺术家的歌曲数，
     * 使艺术家卡片立即显示正确的 songCount，不必等到详情页加载。
     *
     * @param nasSongs 已按可见性过滤的 NAS 歌曲列表（由 updateMergedData 传入）。
     *        ⛔ 不再内部自读 `_songsPaging.value.songs` —— 那是**未过滤的原始库存**，
     *        关 NAS 后本地/百度艺术家卡片的曲目数会虚高（方案 §2.2 C 段 / §3.2.1 #21）。
     * @param baiduSongs 已加载的百度歌曲列表（由 updateMergedData 传入，避免重复 allSongs()）
     *        ⛔ **没有默认值**：默认值若是 `baiduIndexCache.allSongs()`，就是**未过滤的百度索引**
     *        ⇒ 将来任何新增的单参调用都会让关百度后的艺术家曲目数虚高（方案 §3.3 G1）。
     *        强制调用方显式传入**已按可见性过滤**的列表。
     */
    private fun updateArtistSongCounts(nasSongs: List<Song>, baiduSongs: List<Song>) {
        val allSongs = nasSongs + visibleLocalSongsNow() + baiduSongs
        // 按 ArtistSplitter 拆分后的艺术家名统计歌曲数
        val artistSongCounts = mutableMapOf<String, Int>()
        for (song in allSongs) {
            if (song.artist.isBlank()) continue
            val names = ArtistSplitter.split(song.artist)
            for (name in names) {
                val key = ArtistSplitter.normalizeKey(name)
                if (key.isNotBlank()) {
                    artistSongCounts[key] = (artistSongCounts[key] ?: 0) + 1
                }
            }
        }
        // 更新 mergedArtists 列表中的 songCount
        // C35：读-改-写改为 update{}，避免与 :2635 的整表写入交错时丢掉对方的更新
        if (artistSongCounts.isNotEmpty()) {
            _mergedArtists.update { artists ->
                artists.map { artist ->
                    val key = ArtistSplitter.normalizeKey(artist.name)
                    val countedSongs = artistSongCounts[key]
                    if (countedSongs != null && countedSongs > 0) {
                        artist.copy(songCount = countedSongs)
                    } else {
                        artist
                    }
                }
            }
        }
    }

    /** C35：合并协程句柄，新的一轮会取消上一轮 */
    private var mergeJob: Job? = null

    private var coverResolveJob: kotlinx.coroutines.Job? = null
    private var artistCoverResolveJob: kotlinx.coroutines.Job? = null

    // ⛔ `resolvedAlbumCovers` / `resolvedArtistCovers` 声明已移至**类顶部**
    // （v2.39.3 补丁：构造期 null 委托 NPE 修复，R8 lambda 合并可绕过 constructorReady
    // 守卫，理由见类顶部注释）。⛔ 勿挪回本处。

    /**
     * 专辑封面解析尝试次数（albumId → 次数）。
     *
     * 收敛护栏：`updateMergedData()` 末尾会无条件调用 `resolveAlbumCoversAsync()`，
     * 而后者解析结束后又回调 `updateMergedData()`，二者形成**无中断条件**的后台循环
     * （持续 merge + 统计 songCount + 对解析不出的专辑重复发网络请求，造成 CPU/电量与流量开销）。
     * 以「每个专辑最多尝试 [albumCoverMaxAttempts] 次」让该链条必然收敛；
     * 新出现的专辑不在表中，仍会被正常解析。
     */
    private val albumCoverAttempts = mutableMapOf<String, Int>()

    /** 单个专辑最多解析封面的次数（含首次），保留 1 次重试以容忍瞬时网络失败 */
    private val albumCoverMaxAttempts = 2

    // ⛔ `resolvedArtistCovers` 声明已移至**类顶部**（v2.39.3 补丁，同上）。勿挪回。

    /**
     * 艺术家封面解析尝试次数。收敛护栏：`updateMergedData()` 末尾会调 `resolveArtistCoversAsync()`，
     * 后者解析结束若解析到新封面又回调 `updateMergedData()`，可能形成后台循环。
     * 以「每个艺术家最多尝试 [artistCoverMaxAttempts] 次」让链条必然收敛。
     */
    private val artistCoverAttempts = mutableMapOf<String, Int>()

    /** 单个艺术家最多解析封面的次数（含首次），保留 1 次重试以容忍瞬时网络失败 */
    private val artistCoverMaxAttempts = 2

    private fun resolveAlbumCoversAsync(cachedBaiduSongs: List<Song>? = null) {
        // 收敛护栏：只对「仍缺封面」且「尝试次数未达上限」的专辑发起解析。
        // 全部已解析或已达上限时直接返回，切断 updateMergedData ↔ 本方法 的循环。
        val pending = _mergedAlbums.value.filter {
            it.coverUrl == null && (albumCoverAttempts[it.id] ?: 0) < albumCoverMaxAttempts
        }
        if (pending.isEmpty()) return
        // 已有解析 job 在运行 → 不取消、不重启，直接复用。
        // （updateMergedData 每次末尾都会调本方法，若这里 cancel 上一个 job，
        //   解析会被连续触发重建的 updateMergedData 反复取消，永远跑不完，
        //   resolvedAlbumCovers 不增长 → UI 一直无封面。）
        if (coverResolveJob?.isActive == true) return

        coverResolveJob = viewModelScope.launch {
            // P1a（方案 §3.3 G1）：封面匹配的**输入**也必须按源可见性过滤 ——
            // `_songsPaging.value.songs` 是**未过滤的原始 NAS 库存**，百度索引也不能在
            // 百度关源时继续参与匹配（否则被关掉的源会经封面「回流」到同名条目）。
            // ⛔ 只过滤**输入**：`pending` 本身取自已过滤的 `_mergedAlbums`，本地条目仍需解析封面。
            // ⛔ `visibleLocalSongsNow()` 原样保留（本地「关 = 只显示已下载」语义，方案 §5.6 红线）。
            val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
            val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
            val nasSongs = if (nasVisible) _songsPaging.value.songs else emptyList()
            val baiduSongs = if (baiduVisible) (cachedBaiduSongs ?: baiduIndexCache.allSongs()) else emptyList()
            val allSongs = nasSongs + visibleLocalSongsNow() + baiduSongs
            for (album in pending) {
                albumCoverAttempts[album.id] = (albumCoverAttempts[album.id] ?: 0) + 1
            }
            nasMusicApp.albumCoverResolver.resolveCovers(pending, allSongs) { updated ->
                // 仅更新封面缓存，不直接写 _mergedAlbums（避免与 updateMergedData 竞争覆盖）
                var added = 0
                for (album in updated) {
                    if (album.coverUrl != null) {
                        resolvedAlbumCovers[album.id] = album.coverUrl
                        // 仅持久化稳定 HTTP 封面 URL（动态 dlink / data URI 不适合落盘）
                        if (album.coverUrl.startsWith("http")) {
                            nasMusicApp.coverUrlPersistentCache.putAlbumCover(album.id, album.coverUrl)
                        }
                        added++
                    }
                }
                // 每批回调后立即刷新 UI（不必等全部专辑解析完）。
                // 否则 1732 个专辑串行解析要几分钟，期间封面解析了但 UI 一直不刷新。
                if (added > 0) {
                    viewModelScope.launch(Dispatchers.Main) {
                        updateMergedData()
                    }
                }
            }
            // 全部解析完成后，若仍有新封面未刷新（理论上 onUpdated 已刷新），兜底刷新一次
            withContext(Dispatchers.Main) {
                if (resolvedAlbumCovers.isNotEmpty()) updateMergedData()
            }
        }
    }

    private fun resolveArtistCoversAsync(cachedBaiduSongs: List<Song>? = null) {
        // 收敛护栏：只对「仍缺封面」且「尝试次数未达上限」的艺术家发起解析。
        // 读取合并后的 _mergedArtists（NAS + 本地 + 百度），确保百度/本地艺术家也被覆盖。
        // 全部已解析或已达上限时直接返回，切断 updateMergedData ↔ 本方法 的循环。
        val pending = _mergedArtists.value.filter {
            it.coverUrl == null && (artistCoverAttempts[it.id] ?: 0) < artistCoverMaxAttempts
        }
        // P4-only 候选：在线源已耗尽（attempts >= max）但封面仍为 null，
        // 歌曲库后续可能加载了更多歌曲 → 每次歌曲更新后重新用 P4 歌曲封面兜底
        val p4OnlyCandidates = _mergedArtists.value.filter {
            it.coverUrl == null && (artistCoverAttempts[it.id] ?: 0) >= artistCoverMaxAttempts
        }
        if (pending.isEmpty() && p4OnlyCandidates.isEmpty()) return
        // 已有解析在跑 → 不取消（避免重复触发时打断）
        if (artistCoverResolveJob?.isActive == true) return
        artistCoverResolveJob = viewModelScope.launch {
            val before = resolvedArtistCovers.size
            for (artist in pending) {
                artistCoverAttempts[artist.id] = (artistCoverAttempts[artist.id] ?: 0) + 1
            }
            // 全量歌曲（NAS + 本地 + 百度），用于 P4 歌曲封面兜底
            // P1a（方案 §3.3 G1）：同 resolveAlbumCoversAsync —— **输入**按源可见性过滤
            // （NAS 关 ⇒ 不读原始库存；百度关 ⇒ 不读索引、不用 cachedBaiduSongs）。
            val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
            val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
            val nasSongs = if (nasVisible) _songsPaging.value.songs else emptyList()
            val baiduSongs = if (baiduVisible) (cachedBaiduSongs ?: baiduIndexCache.allSongs()) else emptyList()
            val allSongs = nasSongs + visibleLocalSongsNow() + baiduSongs

            // 1. 主解析：P1(网易云) → P2(酷狗) → P3(iTunes) → P4(歌曲封面)
            if (pending.isNotEmpty()) {
                nasMusicApp.artistCoverResolver.resolveCovers(pending, allSongs) { updated ->
                    // 仅更新封面缓存，不直接写 _mergedArtists（避免与 updateMergedData 竞争覆盖）
                    var added = 0
                    for (artist in updated) {
                        if (artist.coverUrl != null) {
                            resolvedArtistCovers[artist.id] = artist.coverUrl
                            // 仅持久化稳定 HTTP 封面 URL（P4 歌曲封面可能是 data URI / content://，不适合落盘）
                            if (artist.coverUrl.startsWith("http")) {
                                nasMusicApp.coverUrlPersistentCache.putArtistCover(artist.id, artist.coverUrl)
                            }
                            added++
                        }
                    }
                    // 每批解析到新封面立即刷新 UI（不必等全部艺术家解析完）。
                    // updateMergedData 内部已 launch(Dispatchers.Default)，此处直接调用即可。
                    if (added > 0 && resolvedArtistCovers.size > before) {
                        updateMergedData()
                    }
                }
            }

            // 2. P4-only 补充：在线源已耗尽，但歌曲库可能已加载更多数据。
            //    不消耗在线源尝试次数，纯本地 O(artists) 查找。
            if (p4OnlyCandidates.isNotEmpty()) {
                nasMusicApp.artistCoverResolver.resolveSongCoversOnly(p4OnlyCandidates, allSongs) { updated ->
                    var added = 0
                    for (artist in updated) {
                        if (artist.coverUrl != null) {
                            resolvedArtistCovers[artist.id] = artist.coverUrl
                            if (artist.coverUrl.startsWith("http")) {
                                nasMusicApp.coverUrlPersistentCache.putArtistCover(artist.id, artist.coverUrl)
                            }
                            added++
                        }
                    }
                    if (added > 0) {
                        updateMergedData()
                    }
                }
            }
        }
    }

    // --- 本地音乐门控与运行期（§5.2 M2/M3、§5.4）---

    /**
     * 同步读当前 UI 可见本地歌曲（过滤公式与 [visibleLocalSongs] 流一致）。
     *
     * 供 updateMergedData / 浏览路径 / M6 provider 等**同步**消费点使用：
     * 它们在写入 _localSongs 后立即重算，等流传播（stateIn 调度）会读到旧值。
     */
    private fun visibleLocalSongsNow(): List<Song> {
        val songs = _localSongs.value
        return if (_localMusicEnabled.value) songs
        else songs.filter { it.storageType == "DOWNLOAD" }
    }

    /** 增量扫描 + USB 监听的宿主 Job（开关观察者按边沿 start/stop） */
    private var localMusicRuntimeJob: Job? = null

    /**
     * 启动本地音乐运行期：M2 后台增量扫描 + M3 USB 插拔监听。
     * 防重入：已在运行直接返回——观察者只在边沿调用，但冷启动首值与用户
     * 反复切开关时仍可能连续触发（防收集器叠加，§十一风险表）。
     */
    private fun startLocalMusicRuntime() {
        if (localMusicRuntimeJob?.isActive == true) return
        localMusicRuntimeJob = viewModelScope.launch {
            // 后台增量扫描（不阻塞 UI）
            launch(Dispatchers.IO) {
                try {
                    val result = nasMusicApp.localMusicRepository.incrementalScan()
                    if (result.hasChanges()) {
                        _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                        updateMergedData()
                        AppLog.i("MainViewModel", "local scan: +${result.newSongs.size} new, -${result.deletedPaths.size} deleted")
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.e("MainViewModel", "local incremental scan failed: ${e.message}", e)
                }
            }

            // 监听 USB 设备插拔
            nasMusicApp.storageMonitor.onDeviceMounted
                .collect { device ->
                    try {
                        val result = nasMusicApp.localMusicRepository.scanUsbDevice(device.path)
                        if (result.hasChanges()) {
                            _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                            updateMergedData()
                            AppLog.i("MainViewModel", "USB mounted scan: ${result.newSongs.size} songs")
                        }
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        AppLog.e("MainViewModel", "USB mount scan failed: ${e.message}", e)
                    }
                }
        }
    }

    /**
     * 停止本地音乐运行期（开关关→USB 插拔不再触发扫描，M3 关向语义）。
     * ⛔ 不清 _localSongs / Room（D2）：已入库曲目与已下载曲目保持原样。
     */
    private fun stopLocalMusicRuntime() {
        localMusicRuntimeJob?.cancel()
        localMusicRuntimeJob = null
    }

    // --- 本地音乐权限入口（§5.5，签名与行为照抄照片墙三件套）---

    /**
     * 设置页「本地音乐」总开关入口。
     * - 关 → 直接落盘 false（Room/已下载曲目不受影响，D2/D3）
     * - 开且已授权 → 直接落盘 true（观察者关→开边沿自动启动扫描，M2/M3）
     * - 开且未授权 → 拉起系统对话框，结果由 [onLocalMusicPermissionResult] 处理
     *
     * ⚠️ 无论哪条路都不写任何「已授权」标志（权限态只现查，§5.5）。
     */
    fun setLocalMusicEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { prefs.setLocalMusicEnabled(false) }
            return
        }
        val granted = PermissionHelper.hasLocalMusicPermission(nasMusicApp)
        _localMusicPermissionState.value = granted
        if (granted) {
            viewModelScope.launch { prefs.setLocalMusicEnabled(true) }
            return
        }
        requestLocalMusicPermission()
    }

    /** 拉起系统权限对话框；launcher 未注入（测试环境）⇒ 提示且**绝不静默把开关打开** */
    fun requestLocalMusicPermission() {
        val launcher = localMusicPermissionLauncher
        if (launcher == null) {
            _localMusicNotice.tryEmit(LocalMusicNotice.DENIED)
            return
        }
        launcher.invoke()
    }

    /**
     * 系统权限对话框返回。
     *
     * ⛔ **不看回调给的 `Map<String, Boolean>`，而是重新读一次权限态**
     * （照抄照片墙：回调值可能撒谎，一律以重读为准）。
     * 已授权 → 落盘 true（扫描由开关观察者的关→开边沿自动启动）；
     * 拒绝 → 落盘 false（开关回弹为关）+ 提示。
     */
    fun onLocalMusicPermissionResult() {
        val granted = PermissionHelper.hasLocalMusicPermission(nasMusicApp)
        _localMusicPermissionState.value = granted
        lastLocalMusicGranted = granted
        if (granted) {
            viewModelScope.launch { prefs.setLocalMusicEnabled(true) }
        } else {
            viewModelScope.launch { prefs.setLocalMusicEnabled(false) }
            _localMusicNotice.tryEmit(LocalMusicNotice.DENIED)
        }
    }

    /**
     * 回到前台时刷新授权状态（onResume 调用，照抄 refreshPhotoAccess 的位置语义）。
     *
     * 回弹判据是**转移式**（[lastLocalMusicGranted] 从 true 变 false 且开关开着），
     * 与照片墙的状态式刻意不同——理由见 [lastLocalMusicGranted] 的 KDoc。
     * 已知取舍：撤销授权 + 进程死亡后重启不回弹（进程内无「上一次」记录），可接受
     * —— 真正的修复发生在用户下次关→开时（系统对话框会重新弹出）。
     */
    fun refreshLocalMusicAccess() {
        val granted = PermissionHelper.hasLocalMusicPermission(nasMusicApp)
        val previouslyGranted = lastLocalMusicGranted
        lastLocalMusicGranted = granted
        _localMusicPermissionState.value = granted
        if (!_localMusicEnabled.value) {
            localMusicRollbackPending = false
            return
        }
        if (previouslyGranted == true && !granted) {
            if (localMusicRollbackPending) return
            localMusicRollbackPending = true
            viewModelScope.launch { prefs.setLocalMusicEnabled(false) }
            _localMusicNotice.tryEmit(LocalMusicNotice.REVOKED)
        }
    }

    /** 手动刷新本地音乐库（全量重扫） */
    fun refreshLocalMusic() {
        viewModelScope.launch {
            try {
                val scanned = nasMusicApp.localMusicRepository.fullScan()
                _localSongs.value = scanned
                updateMergedData()
                showError(getApplication<Application>().getString(R.string.local_music_refreshed, scanned.size))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("MainViewModel", "full local scan failed: ${e.message}", e)
                showError(getApplication<Application>().getString(R.string.refresh_local_music_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 按专辑名（含百度 path 倒数第二段目录名）从候选歌曲中筛选同名专辑歌曲。
     * 复用于本地 / 百度 / 无 NAS 三种来源，统一匹配逻辑。
     *
     * ⚠️ 同名不同歌手的专辑已在列表层拆成两张卡，详情/播放也必须按 artist 再过滤，
     * 否则点《爱情》(甲) 仍会把《爱情》(乙) 的歌一起播了。
     * artist 为空的候选不能反证「不是这张专辑」（百度/本地老数据常缺 artist），予以保留。
     */
    private fun filterSongsByAlbumName(
        albumName: String,
        candidates: List<Song>,
        albumArtist: String? = null
    ): List<Song> {
        val name = albumName.lowercase().trim()
        if (name.isBlank()) return emptyList()
        val artistKey = albumArtist?.let { ArtistSplitter.normalizeKey(it) }.orEmpty()
        return candidates.filter { song ->
            val songAlbum = song.album.lowercase().trim()
            val matchAlbum = songAlbum == name
            val matchPath = !matchAlbum && songAlbum.isBlank() && song.path != null && run {
                val segments = song.path.trim('/').split("/")
                segments.getOrNull(segments.size - 2)?.lowercase()?.trim() == name
            }
            matchAlbum || matchPath
        }.filter { song ->
            artistKey.isBlank() || song.artist.isBlank() ||
                ArtistSplitter.containsArtistWithKey(song.artist, artistKey)
        }
    }

    /**
     * 加载专辑详情歌曲。
     *
     * 多源取数：合并专辑（[Album.sourceIds] 携带 NAS / 本地 / 百度全部来源 id）
     * 分别取数后拼接并跨源去重；非合并专辑回退到单源 albumId，行为与旧版一致。
     * 从根上解决 B20：合并后只取 NAS 歌导致本地/百度同名歌在详情页不可见的问题。
     */
    /**
     * 按 [Album.sourceIds] 的前缀分派，跨源收集专辑曲目（方案 §3.2.1 #11）。
     *
     * 三支**各自**判可见性再决定取不取：
     * - `baidu_album_*` ⇒ 百度可见才读本地索引（否则**连索引都不读**）
     * - `local_album_*` ⇒ 本地段用 [visibleLocalSongsNow]（已含「关 = 只显示已下载」语义，§5.6）；
     *   附带的同名百度段按百度的可见性决定
     * - 其余 ⇒ NAS 专辑 id：NAS 可见才请求；无 NAS 连接时按名兜底
     *
     * @return 去重后的曲目 + NAS 段的异常（由调用方决定提示方式）
     */
    private suspend fun collectAlbumSongsMultiSource(
        album: Album?,
        albumId: String
    ): Pair<List<Song>, Exception?> {
        val albumName = album?.name?.lowercase()?.trim().orEmpty()
        val albumArtist = album?.artist
        // 合并专辑携带多源 id；单源（旧数据/未合并）回退到 albumId，向后兼容
        val sources = album?.sourceIds?.takeIf { it.isNotEmpty() } ?: listOf(albumId)
        val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
        val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)

        val collected = mutableListOf<Song>()
        var nasError: Exception? = null
        val adapter = backendRegistry.getAdapter()

        for (src in sources) {
            when {
                src.startsWith("local_album_") -> {
                    // 本地专辑详情：同时列出本地 + 百度同名歌；按 artist 排除同名不同歌手
                    collected += filterSongsByAlbumName(albumName, visibleLocalSongsNow(), albumArtist)
                    if (baiduVisible) {
                        collected += baiduIndexCache.songsByAlbumName(albumName, albumArtist)
                    }
                }
                src.startsWith("baidu_album_") -> {
                    if (baiduVisible) {
                        collected += baiduIndexCache.songsByAlbumName(albumName, albumArtist)
                    }
                }
                src.startsWith("search_album_") -> {
                    // 搜索态专辑是 UI 临时合成的卡片，曲目只能来自当前多源搜索结果。
                    // ⛔ 不能拿 search_album_ 前缀 id 去请求 NAS 适配器 —— 那是网络曲的 id 口径。
                    val candidates = searchVM.searchResults.value.dataOrNull()
                        ?.filter { sourceVisibility.isVisible(it) }
                        .orEmpty()
                    collected += filterSongsByAlbumName(albumName, candidates, albumArtist)
                }
                else -> {
                    if (!nasVisible) {
                        // NAS 关 ⇒ 不请求该专辑（方案 §3.2.1 #11）
                        continue
                    }
                    if (adapter != null) {
                        try {
                            collected += adapter.getAlbumSongs(src)
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            nasError = e
                        }
                    } else {
                        // 无 NAS 连接：用已加载歌曲按名兜底（保留原无 NAS 行为）
                        val baiduFallback = if (baiduVisible) {
                            baiduIndexCache.songsByAlbumName(albumName, albumArtist)
                        } else emptyList()
                        val candidates = visibleLocalSongsNow() + baiduFallback + _songsPaging.value.songs
                        collected += filterSongsByAlbumName(albumName, candidates, albumArtist)
                    }
                }
            }
        }

        // 跨源去重：同一首歌可能同时命中 NAS 与本地/百度（id 不同但曲目标识相同）
        val seen = mutableSetOf<String>()
        val result = collected.filter { song ->
            seen.add("${song.title.lowercase().trim()}|${song.artist.lowercase().trim()}|${song.durationMs}")
        }
        return result to nasError
    }

    fun loadAlbumSongs(album: Album) {
        // ⛔ albumId 必须在协程外捕获：旧实现在协程执行时才读全局选中专辑快照，
        // 快速进出两张专辑时先发的请求会读到新快照，把 B 的歌写进 A 的缓存键。
        val albumId = album.id
        _albumDetailLoading.value = _albumDetailLoading.value + albumId
        _albumDetailErrors.value = _albumDetailErrors.value - albumId
        viewModelScope.launch {
            try {
                val (result, nasError) = collectAlbumSongsMultiSource(album, albumId)
                val cache = _albumSongsCache.value.toMutableMap()
                cache[albumId] = result
                _albumSongsCache.value = cache
                AppLog.d("NASMusic", "loadAlbumSongs multi-source: album=${album.name}, ${result.size} songs")
                if (nasError != null) {
                    AppLog.e("NASMusic", "loadAlbumSongs NAS source failed", nasError)
                    val message = getApplication<Application>().getString(
                        R.string.load_album_songs_error, nasError.message?.take(50)
                    )
                    _albumDetailErrors.value = _albumDetailErrors.value + (albumId to message)
                    showError(message)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadAlbumSongs failed", e)
                val message = getApplication<Application>().getString(
                    R.string.load_album_songs_error, e.message?.take(50)
                )
                _albumDetailErrors.value = _albumDetailErrors.value + (albumId to message)
                showError(message)
            } finally {
                _albumDetailLoading.value = _albumDetailLoading.value - albumId
            }
        }
    }

    fun getAlbumSongsCache(albumId: String): List<Song> =
        _albumSongsCache.value[albumId] ?: emptyList()

    /**
     * 播放整张专辑（多源）。合并专辑按 [Album.sourceIds] 分别取数并跨源去重，
     * 与 [loadAlbumSongs] 共用 [filterSongsByAlbumName]，从根上解决 B20 播放路径只取 NAS 歌的问题。
     */
    fun playAlbumMultiSource(album: Album) {
        viewModelScope.launch {
            // 与 loadAlbumSongs 共用同一份分派 + 可见性判据（方案 §3.2.1 #11）
            val (result, nasError) = collectAlbumSongsMultiSource(album, album.id)
            if (nasError != null) {
                AppLog.e("NASMusic", "playAlbumMultiSource NAS failed", nasError)
                showError(getApplication<Application>().getString(R.string.load_album_songs_error, nasError.message?.take(50)))
            }
            if (result.isNotEmpty()) {
                playQueue(result)
                navVM.navigateTo(Screen.NowPlaying)
            }
        }
    }

    // --- 详情页导航（A-1, A-2）---
    fun openAlbumDetail(album: Album) {
        _selectedAlbum.value = album
        // P2 修复（2026-11-22）：与 openArtistDetail 对齐的缓存短路。
        // 原先每次进详情都重跑五源取数（含 NAS/网络请求），快速反复进出会并发多次；
        // ⚠️ 本签名的字面量 "fun openAlbumDetail(album: Album)" 被 AlbumDetailFlowGateTest T2
        //    用作 region 起点，必须逐字保留，不要加默认参数或改参数名。
        // loadAlbumSongs(album) 字面量同样被该门禁断言，条件内调用即可满足。
        if (!_albumSongsCache.value.containsKey(album.id)) {
            loadAlbumSongs(album)
        }
        navVM.navigateTo(Screen.AlbumDetail)
    }

    fun openArtistDetail(artistName: String, forceReload: Boolean = false) {
        _selectedArtistName.value = artistName
        // ⛔ 缓存命中直接短路：原先每次进入详情都重发五源（含 10-15s Meting），
        // 快速反复进出会并发多次。下拉/重试由 onRetry 传 forceReload=true 显式刷新。
        if (forceReload || !_artistDetailSongsCache.value.containsKey(artistName)) {
            loadArtistSongs(artistName)
        }
        navVM.navigateTo(Screen.ArtistDetail)
    }

    /**
     * 统一的「点歌手名」入口：曲库里存在的歌手进详情页；网络歌手等曲库外的名字仍走搜索。
     * NowPlaying 原先无论哪种歌手都只跳搜索 Tab，与曲库卡片点击进详情的语义不一致。
     */
    fun openArtistFromName(name: String) {
        if (name.isBlank()) return
        val key = ArtistSplitter.normalizeKey(name)
        val inLibrary = _rawArtistList.any { ArtistSplitter.containsArtist(it.name, name) } ||
            mergedArtists.value.any { ArtistSplitter.normalizeKey(it.name) == key }
        if (inLibrary) {
            openArtistDetail(name)
        } else {
            selectLibraryTab(LibraryTab.SEARCH)
            setLibrarySearchKeyword(name)
            navVM.navigateTo(Screen.Library)
        }
    }

    /**
     * 网络源艺人详情取数器：netease 走艺人全量 API，其他平台 / 未命中回退关键词搜索。
     * ⛔ 无状态、按名字索引由本 VM 的 _artistDetailSongsCache 负责，与 SearchViewModel 的
     *    NetworkArtistExpansion（单艺人状态件）互不复用。
     */
    private val networkArtistDetailFetcher = NetworkArtistDetailFetcher(
        findArtist = { name -> nasMusicApp.networkMusicManager.findArtist(name) },
        getArtistSongs = { id, offset, limit ->
            nasMusicApp.networkMusicManager.getArtistSongs(id, offset, limit)
        },
        search = { keyword -> nasMusicApp.networkMusicManager.search(keyword) }
    )

    private val _artistDetailSongsCache = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    val artistDetailSongsCache: StateFlow<Map<String, List<Song>>> = _artistDetailSongsCache.asStateFlow()

    /** 正在加载详情歌曲的艺术家名集合（按名字区分，支持快速切换）。 */
    private val _artistDetailLoading = MutableStateFlow<Set<String>>(emptySet())
    val artistDetailLoading: StateFlow<Set<String>> = _artistDetailLoading.asStateFlow()

    /** 详情歌曲加载错误（按名字存，UI 据此展示错误态 + 重试）。 */
    private val _artistDetailErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val artistDetailErrors: StateFlow<Map<String, String>> = _artistDetailErrors.asStateFlow()

    /** 五个音乐源并行查找的临时容器 */
    private data class FiveSources(
        val nas: List<Song>,
        val paging: List<Song>,
        val local: List<Song>,
        val baidu: List<Song>,
        val network: List<Song>
    )

    fun loadArtistSongs(artistName: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _artistDetailLoading.value = _artistDetailLoading.value + artistName
            _artistDetailErrors.value = _artistDetailErrors.value - artistName
            // ⚠️ NAS 关 ⇒ 不取 NAS 曲目；百度关 ⇒ 不取百度曲目（方案 §3.2.1 #12）。
            // ⛔ **不清空** `_artistDetailSongsCache`：旧数据保持显示，直到新数据就绪后覆盖。
            val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
            val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
            // 注意：不在加载开始时清空 _artistDetailSongsCache，否则在重新加载期间 UI 会显示空列表。
            // 旧数据保持显示，直到新数据就绪后直接覆盖。
            // artistSongsMap 以归一化名为键，此处理由同上，但 artistSongsMap 仅被 LibraryScreen 使用，
            // 用户在 ArtistDetail 页时 LibraryScreen 未组合，不会看到中间空窗，所以可以安全清除。
            _artistSongsMap.value = _artistSongsMap.value.toMutableMap().apply {
                remove(ArtistSplitter.normalizeKey(artistName))
            }

            try {
                // ---- 所有音乐源真正并行查找 ----
                // 快源（内存缓存）几乎瞬时返回，慢源（NAS API、Meting 搜索）并行不阻塞彼此
                val adapter = backendRegistry.getAdapter()
                val (nasSongs, pagingSongs, localDeviceSongs, baiduSongs, networkSongs) = coroutineScope {
                    // 快源：纯内存读取，不放在 async 里直接同步取
                    val fastPaging = async(Dispatchers.Default) { if (nasVisible) _songsPaging.value.songs else emptyList() }
                    val fastLocal = async(Dispatchers.Default) { visibleLocalSongsNow() }
                    // 百度索引：按艺术家直接在 raw entry 上过滤，不创建全部 Song 对象
                    // ⚠️ 百度关 ⇒ 不读索引（方案 §3.2.1 #12）
                    val fastBaidu = async(Dispatchers.Default) {
                        if (baiduVisible) baiduIndexCache.songsByArtist(artistName) else emptyList()
                    }

                    // 慢源1: NAS 后端（NAS 关 ⇒ 不请求）
                    val nasDeferred = async(Dispatchers.IO) {
                        if (adapter != null && nasVisible) {
                            val artistKey = ArtistSplitter.normalizeKey(artistName)
                            val rawMatchingIds = _rawArtistList
                                .filter { ArtistSplitter.containsArtist(it.name, artistName) }
                                .map { it.id }
                                .distinct()
                                .ifEmpty {
                                    val artists = _artists.value.dataOrNull() ?: emptyList()
                                    val artist = artists.find { ArtistSplitter.normalizeKey(it.name) == artistKey }
                                    if (artist != null) listOf(artist.id.substringBefore("|", artist.id)) else emptyList()
                                }
                            AppLog.d("NASMusic", "loadArtistSongs('$artistName') NAS rawMatchingIds=${rawMatchingIds.size}")
                            // 按名字查询的后端（Jellyfin）忽略 artistId：传多个 ID 会发出 N 条完全相同的请求。
                            // 只取第一个（允许为空串，name 仍会传）；按 ID 查询的后端保留全部原始 ID。
                            val idsToQuery = if (adapter.queriesArtistSongsByName) {
                                listOf(rawMatchingIds.firstOrNull().orEmpty())
                            } else rawMatchingIds
                            idsToQuery.flatMap { id ->
                                try {
                                    adapter.getArtistSongs(id, artistName)
                                } catch (e: Exception) {
                                    e.rethrowIfCancellation()
                                    AppLog.w("NASMusic", "loadArtistSongs: NAS ID=$id query failed: ${e.message?.take(50)}")
                                    emptyList()
                                }
                            }
                        } else {
                            emptyList()
                        }
                    }

                    // 慢源2: 网络音乐源（Meting-API 等，最慢，10-15s）
                    // ⛔ 显式判 NETWORK_MUSIC 可见性：与 SearchAggregator 网络分支同口径。
                    //    关掉 Meting 后本分支不再发请求（即便 services map 里还留着别的服务），
                    //    且 fetcher 内部只保留 networkSource==meting，避免百度/Jamendo 跨标签重复。
                    val networkDeferred = async(Dispatchers.IO) {
                        if (!sourceVisibility.isVisible(MusicSourceType.NETWORK_MUSIC)) {
                            emptyList()
                        } else {
                            try {
                                networkArtistDetailFetcher.fetch(artistName)
                            } catch (e: Exception) {
                                e.rethrowIfCancellation()
                                AppLog.w("NASMusic", "loadArtistSongs: network fetch failed: ${e.message?.take(50)}")
                                emptyList()
                            }
                        }
                    }

                    FiveSources(
                        nas = nasDeferred.await(),
                        paging = fastPaging.await(),
                        local = fastLocal.await(),
                        baidu = fastBaidu.await(),
                        network = networkDeferred.await()
                    )
                }

                // 合并所有源，按 ArtistSplitter 拆分后只取包含该艺术家的歌曲
                // 去重用 dedupeKey（标题+艺术家规范化），而非 id（不同源同一首歌 id 不同）
                // 优先级：NAS > 本地设备 > 百度 > 网络（本地源音质更稳定，优先保留）
                // 注意：baiduSongs 已通过 songsByArtist 预过滤，这里只过滤其余源
                val artistKey = ArtistSplitter.normalizeKey(artistName)
                val allCandidates = (nasSongs + pagingSongs + localDeviceSongs + baiduSongs + networkSongs)
                    .filter { ArtistSplitter.containsArtistWithKey(it.artist, artistKey) }
                val seenKeys = mutableSetOf<String>()
                val deduped = allCandidates.filter { seenKeys.add(it.dedupeKey) }
                // ⛔ 统一排序：各后端排序口径不同（Jellyfin=SortName、Subsonic=专辑序），
                // 多源拼接后顺序混乱。统一为 专辑 > 碟号 > 音轨号 > 标题；无专辑名排最后。
                val finalSongs = deduped.sortedWith(
                    compareBy<Song> { it.album.ifBlank { "\uFFFF" } }
                        .thenBy { it.discNumber }
                        .thenBy { it.trackNumber }
                        .thenBy { it.title }
                )

                AppLog.d("NASMusic", "loadArtistSongs('$artistName') final=${finalSongs.size} (nas=${nasSongs.size}, paging=${pagingSongs.size}, local=${localDeviceSongs.size}, baidu=${baiduSongs.size}, network=${networkSongs.size}, beforeDedup=${allCandidates.size})")
                // 诊断：打印重复 dedupeKey 的歌曲，定位去重失败原因
                val duplicates = allCandidates.groupBy { it.dedupeKey }.filter { it.value.size > 1 }
                duplicates.entries.take(5).forEach { (key, songs) ->
                    AppLog.d("NASMusic", "  DUP key='$key' count=${songs.size}: ${songs.joinToString { "[${it.networkSource ?: "local"}/${it.id}] '${it.title}'/'${it.artist}'" }}")
                }
                finalSongs.take(3).forEach { s ->
                    AppLog.d("NASMusic", "  song artist='${s.artist}' title='${s.title}' album='${s.album}'")
                }
                _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                    put(artistName, finalSongs)
                }
                // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                buildArtistMapsIncremental(finalSongs)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadArtistSongs failed", e)
                _artistDetailErrors.value = _artistDetailErrors.value +
                    (artistName to (e.message?.take(80) ?: "未知错误"))
                showError(getApplication<Application>().getString(R.string.load_favorites_error, e.message?.take(50)))
            } finally {
                _artistDetailLoading.value = _artistDetailLoading.value - artistName
            }
        }
    }

    // --- B-1 收藏控制 ---
    /**
     * 判断歌曲是否已收藏（同步，用于 UI 快速判断）。
     * 同时查 NAS 收藏（_favoriteIds）与网络/本地收藏（networkFavoriteIds）。
     * 推荐优先用 [favoriteIds] StateFlow 订阅以获得即时刷新；本方法仅用于一次性查询。
     */
    fun isFavorite(songId: String): Boolean =
        songId in _favoriteIds.value || songId in networkFavoriteIds.value

    // --- 本地歌单操作（R-1：已拆分至 PlaylistViewModel，此处为兼容转发）---
    val playlistVM = PlaylistViewModel(app)


    /**
     * 播放整个本地歌单（含网络歌曲时自动解析 streamUrl）
     */
    fun playLocalPlaylist(playlist: LocalPlaylist) {
        if (playlist.songs.isEmpty()) return
        playQueue(playlist.songs, 0)
        navVM.navigateTo(Screen.NowPlaying)
    }

    // --- 数据备份（R-1：已拆分至 BackupViewModel，此处为兼容转发）---
    val backupVM = BackupViewModel(app)

    // --- 歌单导入（阶段5：PlaylistImportViewModel，复用补全与可达性实例）---
    val playlistImportVM = PlaylistImportViewModel(app, playlistEnricher, urlReachabilityChecker)

    suspend fun restoreBackupFromJson(json: String): Boolean = backupVM.restoreBackupFromJson(json)

    // --- B-2 最近播放 & 播放次数 ---
    fun recordPlay(song: Song) {
        viewModelScope.launch {
            // 单次 DataStore edit 同时更新 id 列表 + 播放次数 + 完整歌曲对象。
            // P2 修复（2026-09-22 审查）：天气电台对 NAS 歌曲打 nas_/nasf_ 前缀
            //（命中/补齐分类依赖前缀，见 WeatherRadioManager），播放统计若按合成
            // id 记账，同一首歌会以两个 id 出现（口径分裂）。在此入口剥离合成
            // 前缀还原真实歌曲 id，其余路径不变。
            prefs.history.recordPlayWithSong(song.stripSyntheticRadioPrefix())
            // 刷新最近播放列表，不显示 loading 以避免闪烁
            loadRecentSongs(showLoading = false)
        }
    }

    /**
     * 剥离天气电台构建时附加的合成 id 前缀（nasf_ 命中补齐 / nas_ 心情命中），
     * 还原真实歌曲 id。前缀常量在 WeatherRadioManager 为 private，此处按同法
     * 本地同步（改前缀时两处同步）。
     */
    private fun Song.stripSyntheticRadioPrefix(): Song {
        val stripped = when {
            id.startsWith("nasf_") -> id.removePrefix("nasf_")
            id.startsWith("nas_") -> id.removePrefix("nas_")
            else -> return this
        }
        return copy(id = stripped)
    }

    val recentSongIds = prefs.history.recentSongIds
    val playCounts = prefs.history.playCounts


    // --- A-3 流派/年代歌曲加载 ---
    fun getSongsByGenre(genre: String, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            // 本地歌曲按 genre 过滤（不依赖 NAS）
            val localMatches = visibleLocalSongsNow().filter { it.genre?.equals(genre, ignoreCase = true) == true }
            // ⚠️ NAS 关 ⇒ NAS 段为空、本地照旧（方案 §3.2.1 #16）：不请求
            if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                onResult(localMatches)
                return@launch
            }
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                onResult(localMatches)
                return@launch
            }
            try {
                val nasSongs = adapter.getSongsByGenre(genre)
                onResult((nasSongs + localMatches).distinctBy { it.id })
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "getSongsByGenre failed", e)
                showError(getApplication<Application>().getString(R.string.load_genre_songs_error, e.message?.take(50)))
                onResult(localMatches)
            }
        }
    }

    fun getSongsByYearRange(fromYear: Int, toYear: Int, onResult: (List<Song>) -> Unit) {
        viewModelScope.launch {
            // 本地歌曲按 year 过滤（不依赖 NAS）
            val localMatches = visibleLocalSongsNow().filter { song ->
                song.year != null && song.year in fromYear..toYear
            }
            // ⚠️ NAS 关 ⇒ NAS 段为空、本地照旧（方案 §3.2.1 #17）：不请求
            if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                onResult(localMatches)
                return@launch
            }
            val adapter = backendRegistry.getAdapter()
            if (adapter == null) {
                onResult(localMatches)
                return@launch
            }
            try {
                val nasSongs = adapter.getSongsByYearRange(fromYear, toYear)
                onResult((nasSongs + localMatches).distinctBy { it.id })
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "getSongsByYearRange failed", e)
                showError(getApplication<Application>().getString(R.string.load_year_songs_error, e.message?.take(50)))
                onResult(localMatches)
            }
        }
    }

    // --- D-2 网络状态自动重连 ---
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3
    /** F2-4：断网恢复续播 job（新恢复事件取消旧的等待） */
    private var networkRestoreJob: kotlinx.coroutines.Job? = null

    fun onNetworkAvailable() {
        if (_isNetworkAvailable.value) return // 已是可用状态，跳过（防止 NetworkMonitor 重复回调）
        _isNetworkAvailable.value = true
        // MTV 模式下不弹提示（MV 视频流请求可能导致网络抖动，频繁弹"网络已恢复"打扰观看）
        if (!mvVM.showMv.value) {
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_network_restored), 2000)
        }
        // F2-4：断线续播——取回断点，2 秒去抖后重解析当前歌并 seek 回断点
        val resumePoint = playerManager.onNetworkRestored()
        if (resumePoint != null) {
            networkRestoreJob?.cancel()
            networkRestoreJob = viewModelScope.launch {
                delay(2000) // 网络栈稳定去抖（WiFi 切换可能再抖）
                // 队列仍在且索引有效才续播（断网期间用户可能清了队列）
                val queue = playerManager.getQueueSnapshot()
                if (resumePoint.index in queue.indices) {
                    AppLog.d("NASMusic", "onNetworkAvailable: resuming playback at index=${resumePoint.index}, pos=${resumePoint.positionMs}")
                    // wasPlaying=false 只加载不播；网络歌曲直链必过期 → 统一走重解析路径
                    if (resumePoint.wasPlaying) {
                        playerVM.resolveAndPlayByIndex(resumePoint.index)
                        // 重解析 playQueue 从 0 开始，seek 回断点由进度恢复逻辑兜底
                    }
                }
            }
        }
        // 自动重连
        if (!serverVM.isConnected.value && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            AppLog.d("NASMusic", "onNetworkAvailable: reconnecting (attempt $reconnectAttempts/$maxReconnectAttempts)")
            serverVM.connectToSavedServer(silent = true)
        }
    }

    fun onNetworkLost() {
        _isNetworkAvailable.value = false
        reconnectAttempts = 0
        // F2-4：标记断网（PlayerManager 冻结错误跳歌，等待恢复续播）
        playerManager.onNetworkGone()
        if (!mvVM.showMv.value) {
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_network_disconnected), 5000)
        }
    }

    // --- 播放控制（R-1 第四步：已迁至 PlayerViewModel，此处转发）---
    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        // P1 修复（2026-11-22）：空列表早退。下游 PlayerViewModel.playQueue / PlayerManager.playQueue
        // 都有 isEmpty() 早退，唯独此处缺失——空表的 lastIndex 是 -1，起始下标钳制会抛
        // IllegalArgumentException。当前首页调用方都做了非空保护，但 playQueue 是 public，
        // 任何新调用点传空表都会崩。
        // ⚠️ 注释里不要写出被钳制表达式的字面量，否则源码门禁的顺序判据会被注释自伤。
        if (songs.isEmpty()) return
        // 非随心听播放时停止自动续播（随心听状态仍由 MainViewModel 的浏览/发现域持有）
        _isShufflePlaying = false
        shuffleRefillJob?.cancel()
        // 第一首若为 imported stub，PlayerViewModel.playQueue() 会同步补全（NAS/网络搜索），
        // 此处只补全剩余 stub，避免与 playQueue 的同步补全竞争。
        val firstIndex = startIndex.coerceIn(0, songs.lastIndex)
        playerVM.playQueue(songs, startIndex)
        // 导入 stub 补全（§4.3.1 路径 A）：播放时触发，后台补全不阻塞播放
        // 跳过第一首（已在 playQueue 中同步补全），避免重复搜索
        triggerEnrichForStubs(songs.filterIndexed { idx, _ -> idx != firstIndex })
    }

    /**
     * 导入 stub 后台补全（§4.3.1 路径 A：仅播放时触发，不排定时任务）。
     * 只处理 id 以 [PlaylistParsers.IMPORTED_ID_PREFIX] 开头的裸条目；
     * Semaphore(4) 限流 + enrichInFlight 同 id 去重，命中后写回所有含该 stub 的歌单。
     */
    private fun triggerEnrichForStubs(songs: List<Song>) {
        for (song in songs) {
            if (!song.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) continue
            if (!enrichInFlight.add(song.id)) continue // 已在补全中
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    enrichSemaphore.acquire()
                    try {
                        val enriched = playlistEnricher.enrichAndPersistEverywhere(song)
                        // 更新 PlayerManager 内存队列，使 UI source badge 立即刷新
                        if (enriched != null) {
                            playerManager.replaceSongInQueue(song.id, enriched)
                        }
                    } finally {
                        enrichSemaphore.release()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.w("MainViewModel", "triggerEnrichForStubs failed '${song.title}': ${e.message}")
                } finally {
                    enrichInFlight.remove(song.id)
                }
            }
        }
    }

    /**
     * 播放失败回退（§4.1.8 (5) 算法 B）：PlayerManager 在最终失败即将跳下一首前回调。
     * 复测 URL 可达性 → 真不可达（NOT_FOUND/DNS_FAILED/REDIRECT_LOOP）走补全链写回；
     * 暂时不可达（TIMEOUT/SERVER_ERROR）只标记 Unreachable，留待 24h 判定窗口自动复测。
     */
    fun playbackFailure(song: Song) {
        if (!song.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX)) return
        if (song.streamUrl?.startsWith("http") != true) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                enrichSemaphore.acquire()
                try {
                    playlistEnricher.onPlaybackFailure(song, urlReachabilityChecker)
                } finally {
                    enrichSemaphore.release()
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w("MainViewModel", "playbackFailure failed '${song.title}': ${e.message}")
            }
        }
    }

    // --- F2-3 智能电台 ---
    /** 智能电台状态（生成中/播放中/耗尽），内部逻辑用 */
    val smartRadioState = nasMusicApp.smartRadioManager.state

    /** F2-3 首页列表化：当前浏览批次（随心听式，空 = 未生成/隐藏区块） */
    private val _smartRadioBatch = kotlinx.coroutines.flow.MutableStateFlow<List<Song>>(emptyList())
    val smartRadioBatch: kotlinx.coroutines.flow.StateFlow<List<Song>> = _smartRadioBatch

    /**
     * F2-3 首页列表化：生成/换一批（只生成不播放），结果写入 [smartRadioBatch]。
     *
     * P0 修复（2026-11-22）：重入判据改用 SmartRadioManager.state（StateFlow、线程安全），
     * 废弃原布尔重入标志——它只在成功回调里复位，而 Manager 在
     * 空库/耗尽/异常等 6 条路径提前 return 不回调 ⇒ 布尔永久卡 true ⇒ "换一批"会话内失效；
     * 且布尔在 IO 线程写、主线程读，非 volatile 有可见性竞态。
     *
     * Manager 的 state 天然覆盖两条语义：
     * - Generating ⇒ 正在生成，忽略重复点击（防重入）；
     * - 任意失败路径都会置 Exhausted、成功置 Playing ⇒ 标志必然解除，不会卡死。
     * 取消（scope 关闭）后 state 不再是 Generating，同样不阻塞后续点击。
     */
    fun loadSmartRadioBatch() {
        if (nasMusicApp.smartRadioManager.state.value is SmartRadioManager.State.Generating) return
        nasMusicApp.smartRadioManager.generateOnly { batch, _ ->
            // ⚠️ 批次侧（方案 §3.3 G1c）：按可见性过滤后再写入。
            // 关源**瞬间已在生成中**的批次、以及关源**前**缓存的批次里的该源歌，都要被滤掉。
            // （采集侧「不拉 NAS 样本」已由 SmartRadioManager 的 nasMusicEnabled provider 实现。）
            _smartRadioBatch.value = batch.filter { sourceVisibility.isVisible(it) }
        }
    }

    /**
     * 播放浏览批次中第 [index] 首（点卡片播歌）：
     * 整批入队并从该首播起，批次播完即止（与旧入口行为一致）。
     */
    fun playSmartRadioBatchAt(index: Int) {
        val batch = _smartRadioBatch.value
        if (index !in batch.indices) return
        playQueue(batch, index)
    }

    fun addSongToQueue(song: Song) = playerManager.addToQueue(song)

    // =====================================================================
    // R-1 第二步拆分：Download / MvSearch / VocalSeparation 已迁至子 ViewModel。
    // 声明位置已前移到 init@688 之前（v2.36.2 崩溃修复，见上方说明）。
    // 以下为兼容转发层（AppRoot 的既有引用不变）。
    // =====================================================================

    init {
        // 下载域刷新本地歌曲时联动合并数据
        // P1-2 修复（2026-09-16）：收集下载/自动下载提示 → errorMessage（UI 有消费方）。
        // NasMusicApp.downloadNotifyMessage 是进程级单例通道（replay=1），
        // MainViewModel 每次创建都会重新订阅，需要防重复弹同一条：replay 的那条只在
        // 订阅建立时出现一次，用时间戳去重不必要——errorMessage 本身就是最后一条覆盖语义。
        viewModelScope.launch {
            nasMusicApp.downloadNotifyMessage.collect { msg ->
                showError(msg)
            }
        }
        downloadVM.onLocalSongsChanged = {
            viewModelScope.launch {
                _localSongs.value = nasMusicApp.localMusicRepository.loadFromCache()
                updateMergedData()
            }
        }
        // v2.35.0 手机端修复：把下载域的操作反馈转发到 errorMessage 通道。
        // 此前 `downloadVM.message` **无人消费** —— 用户在码率面板点「下载」后
        // 看不到任何反馈（表现为"点击确定没反应"）。
        // 复用上面 downloadNotifyMessage 的同一条路径（UI 已消费），
        // 与既有的"已下载：X"/"下载失败：X"提示保持一致的呈现方式。
        viewModelScope.launch {
            downloadVM.message.collect { msg ->
                if (msg != null) showError(msg)
            }
        }
        // 阶段 9（§9）：照片墙授权提示 → errorMessage 通道。
        // 复用上面同一条路径的原因与下载域一致：顶部提示已有 UI 消费方，
        // 不必为一个「权限被撤销」的提示再造一套 UI。
        // ⚠️ 取文案在这里做（`VisualizerViewModel` 只发枚举）—— 数据层不产出面向用户的文案。
        viewModelScope.launch {
            visualizerVM.photoAccessNotice.collect { notice ->
                showError(getApplication<Application>().getString(notice.messageRes))
            }
        }
        // 本地音乐授权提示 → errorMessage（§5.5，与照片墙 notice 同一条路径：
        // 数据层只发枚举，文案在这里取）。
        viewModelScope.launch {
            localMusicNotice.collect { notice ->
                showError(getApplication<Application>().getString(notice.messageRes))
            }
        }
        // 模型下载状态同步给人声分离域（模式切换门槛判断）
        viewModelScope.launch {
            downloadVM.modelDownloaded.collect { downloaded -> vocalVM.setModelDownloaded(downloaded) }
        }
        // K 歌进入时启动遥控服务器
        vocalVM.onEnsureRemoteControlStarted = { ensureRemoteControlStarted() }
        // MV 播放模式同步（playMode 由 MainViewModel 拥有，作为方法参数下发）
        viewModelScope.launch {
            playerVM.playMode.collect { mvVM.currentPlayMode = it }
        }
    }

    // ---- DownloadViewModel 转发 ----
    val songDownloadStates: StateFlow<Map<String, com.nasmusic.tv.backend.download.model.DownloadState>>
        get() = downloadVM.songDownloadStates


    // ---- VocalSeparationViewModel 转发 ----

    /**
     * 清除全部分离产物与临时残留（设置页「清除分离缓存」入口；方案 §1.14 / T8b）。
     *
     * 取代旧 `clearAccompanimentCache()`：后者只删三种后缀、提示还是文件数口径
     * （"清了 3 个、占用却掉了 40MB"的两套数）。现在统一按 `formatBytes` 报释放字节数。
     */
    fun clearSeparationArtifacts() {
        viewModelScope.launch {
            val freed = vocalVM.clearAllSeparationArtifacts()
            if (vocalVM.separating.value) {
                // 分离中跳过删除（返回 0 与"本来就没有"同值，必须另判状态，否则用户以为清过了）
                postLocalConnectMessage(
                    getApplication<Application>().getString(R.string.status_separation_busy))
            } else {
                postLocalConnectMessage(
                    getApplication<Application>().getString(
                        R.string.status_accompaniment_cache_cleared,
                        com.nasmusic.tv.ui.screens.settings.formatBytes(freed)))
            }
        }
    }

    // ---- MvSearchViewModel 转发 ----

    fun enterMvMode() {
        ensureRemoteControlStarted()
        mvVM.enterMvMode()
    }
    fun clearMvPersistentCache() {
        mvVM.clearPersistentCache {
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_mv_cache_cleared))
        }
    }

    // ---- RemoteCallbacks 实现（手机遥控服务器回调）----

    override fun onCleared() {
        remoteControlServer.stop()
        // 2026-09-25 审查修复（#10 子 ViewModel 生命周期）：15 个子 VM 是本类的普通属性、
        // 不在任何 ViewModelStore 中，它们的 onCleared() 永不被框架调用 —— VisualizerViewModel
        // 的照片墙/人脸扫描 ORT session 释放与 launcher 闭包清理因此从未执行。统一在此驱动。
        runCatching { visualizerVM.dispose() }
            .onFailure { AppLog.e("MainViewModel", "visualizerVM.dispose failed", it) }
        // C34：dispose() 只覆盖了 visualizerVM，其余 14 个子 VM 的 viewModelScope 仍然永存，
        // 它们在 init / stateIn 里起的无限 collect 会一直跑。这里统一取消。
        disposeChildViewModels(
            weatherRadioVM, serverVM, searchVM, netVM, navVM, playHistoryVM, playerVM,
            downloadVM, vocalVM, mvVM, playlistVM, backupVM, playlistImportVM,
            visualizerVM, // S07-01：dispose() 未取消其 viewModelScope，在此兜底取消
        )
        super.onCleared()
    }

    override fun getQueue(): List<Song> = playerManager.playerState.value.queue
    override fun getCurrentIndex(): Int = playerManager.playerState.value.currentIndex
    override fun isPlaying(): Boolean = playerManager.isPlaying.value
    override fun getProgressMs(): Long = playerManager.progress.value
    override fun getDurationMs(): Long = playerManager.duration.value

    override fun playAt(index: Int) {
        mainHandler.post { playerManager.playAt(index) }
    }

    override fun moveQueueItem(from: Int, to: Int) {
        mainHandler.post { playerManager.moveItem(from, to) }
    }

    override fun addToQueue(song: Song) {
        mainHandler.post { playerManager.addToQueue(song) }
    }

    override suspend fun search(keyword: String): RemoteSearchResult = coroutineScope {
        val nasDeferred = async(Dispatchers.IO) {
            // ⚠️ NAS 关 ⇒ 不查 NAS（方案 §3.2.1 #13）。
            // 网络侧走 services map（关源即失效），这里只挡 NAS 这一支。
            if (!sourceVisibility.isVisible(MusicSourceType.NAS)) return@async emptyList()
            try {
                backendRegistry.getAdapter()?.searchSongs(keyword) ?: emptyList()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w("NASMusic", "remote search NAS failed: ${e.message}")
                emptyList()
            }
        }
        val netDeferred = async(Dispatchers.IO) {
            try {
                nasMusicApp.networkMusicManager.search(keyword)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w("NASMusic", "remote search network failed: ${e.message}")
                emptyList()
            }
        }
        RemoteSearchResult(nasDeferred.await(), netDeferred.await())
    }

    override fun removeFromQueue(index: Int) {
        mainHandler.post { playerManager.removeFromQueue(index) }
    }

    // （addSongToQueue/addSongsToQueue/toggleQueueSong 已迁至 PlayerViewModel，见播放控制转发区）

    /**
     * 队列中所有歌曲 id 的集合（供 UI 快速判断某首歌是否在队列中）
     */
    val queueSongIds: StateFlow<Set<String>> = playerVM.playerState
        .map { st -> st.queue.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * 队列中的网络歌曲（用于「继续听」区域）
     *
     * 从当前队列中筛选出网络歌曲（isNetworkSong=true），
     * 不包含当前正在播放的歌曲，最多保留 5 首，
     * 按队列顺序排列（最近即将播放的在前）。
     */
    val recentNetworkSongs: StateFlow<List<Song>> = playerVM.playerState.map { st ->
        st.queue.filterIndexed { i, s -> s.isNetworkSong && i != st.currentIndex }
            .take(5)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 当前播放的网络歌曲（用于「继续听」区域的"正在播放"）
     */
    val currentNetworkSong: StateFlow<Song?> = playerVM.playerState.map { st ->
        st.currentSong?.takeIf { it.isNetworkSong }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clearQueue() {
        playerManager.clearQueue()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 清除持久化的上次播放队列
        viewModelScope.launch { prefs.queue.clearLastQueue() }
    }

    private fun loadLyricsForCurrentSong() {
        val song = playerVM.playerState.value.currentSong
        // 同一首歌因播放直链重解析 / 队列回填导致 playerState 再次发射时，歌词已加载完成 ⇒
        // 直接复用，避免重复网络请求与「清空 → 重新渲染」的闪烁；用户手动切换的来源也得以保留。
        if (song != null && song.id == lastLyricsLoadSongId && _currentLyrics.value != null) {
            AppLog.d("NASMusic", "loadLyrics: reuse loaded lyrics for ${song.title}")
            return
        }
        lastLyricsLoadSongId = song?.id
        lyricsLoadJob?.cancel()
        // 切歌时清空候选缓存和轮次状态
        lyricsManager.clearCachedCandidates()
        _currentLyrics.value = null
        _lyricsAvailability.value = LyricsAvailability()
        // 重置网络封面（切歌时清除上一首的网络封面）
        _networkCoverUrl.value = null
        if (song == null) return
        AppLog.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
        lyricsLoadJob = viewModelScope.launch {
            try {
                // 0. 本地已下载歌词（最高优先级，不走网络）
                val dlState = songDownloadStates.value[song.downloadKey]
                AppLog.d("NASMusic", "loadLyrics: dlState=${dlState?.javaClass?.simpleName}, key=${song.downloadKey}")
                // 确定本地音频路径：优先用下载状态，否则用 song.path（下载后以 LOCAL 源出现，downloadKey 不匹配）
                val localAudioPath: String? = when {
                    dlState is DownloadState.Completed && dlState.path.isNotBlank() -> dlState.path
                    song.isLocalSong && !song.path.isNullOrBlank() -> {
                        AppLog.d("NASMusic", "loadLyrics: isLocalSong fallback, song.path=${song.path}")
                        song.path
                    }
                    else -> null
                }
                if (localAudioPath != null) {
                    val localLyricPath = (dlState as? DownloadState.Completed)?.lyricPath
                    AppLog.d("NASMusic", "loadLyrics: localAudioPath=$localAudioPath, lyricPath=$localLyricPath")
                    val localLrc = LocalLyricsProvider.getLyricsFromPath(
                        localAudioPath, localLyricPath
                    )
                    AppLog.d("NASMusic", "loadLyrics: localLrc=${localLrc != null}, validLrc=${localLrc?.let { LrcParser.isValidLrc(it) }}")
                    if (localLrc != null && LrcParser.isValidLrc(localLrc)) {
                        val lyrics = LrcParser.parse(localLrc, song.id)
                            .copy(source = LyricsSource.LOCAL_FILE)
                        _currentLyrics.value = lyrics
                        if (lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                            _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                        }
                        AppLog.d("NASMusic", "loadLyrics: local downloaded lyrics, ${lyrics.lines.size} lines")
                        // 本地歌词已加载，仍异步检查后端/网络可用性（供用户手动切换）
                        val availability = lyricsManager.checkAvailability(song)
                        _lyricsAvailability.value = availability.copy(cached = lyrics)
                        // 自动搜索封面（如果本地无旁路封面且无后端封面）
                        if (getCoverCandidates(song).isEmpty()) {
                            val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                            if (networkCover != null) {
                                _networkCoverUrl.value = networkCover
                            }
                        }
                        return@launch
                    }
                }

                // 1. 先查持久化缓存——快速读取，不阻塞歌词显示
                val cachedLyrics = lyricsManager.getCachedNetworkLyrics(song)
                if (cachedLyrics != null) {
                    _currentLyrics.value = cachedLyrics
                    _lyricsAvailability.value = LyricsAvailability(cached = cachedLyrics)
                    if (cachedLyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                        _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                    }
                    AppLog.d("NASMusic", "loadLyrics: cached hit, shown immediately")
                }

                // 2. 后台检查后端 + 网络可用来源（更新标签状态，不影响已显示的歌词）
                val availability = lyricsManager.checkAvailability(song)
                // 保留已有的缓存状态
                _lyricsAvailability.value = availability.copy(cached = _lyricsAvailability.value.cached)
                AppLog.d("NASMusic", "loadLyrics: cached=${cachedLyrics != null}, backend=${availability.hasBackend}, network=${availability.hasNetwork}")

                // 3. 显示歌词
                //    后端歌词优先于持久化缓存——缓存中的网络歌词可能是误匹配结果，不应遮蔽权威后端歌词；
                //    但用户手动为这首歌选过「在线歌词」时，尊重用户选择（不覆盖）。
                val userForcedNetwork = song.id in userNetworkLyricsOverride
                val backendLyrics = availability.backend
                if (backendLyrics != null && !userForcedNetwork) {
                    _currentLyrics.value = backendLyrics
                    if (backendLyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                        _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                    }
                    AppLog.d(
                        "NASMusic",
                        "loadLyrics: backend preferred over cache(cached=${cachedLyrics != null}), lines=${backendLyrics.lines.size}"
                    )
                } else if (cachedLyrics == null) {
                    val lyrics = availability.network
                    if (lyrics != null) {
                        _currentLyrics.value = lyrics
                        if (lyrics.lines.any { it.wordTimestamps.isNotEmpty() }) {
                            _lyricsHighlightMode.value = LyricsHighlightMode.WORD_BY_WORD
                        }
                        // 自动匹配到的网络歌词：暂存以便播放完成后持久化
                        if (lyrics.source == LyricsSource.NETWORK) {
                            val lrcText = LrcParser.toLrcText(lyrics)
                            if (lrcText.isNotBlank()) {
                                lyricsManager.savePendingNetworkLyrics(song, lrcText)
                            }
                        }
                    }
                    AppLog.d("NASMusic", "loadLyrics: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")
                }

                // 4. 无封面时自动搜索网络封面（所有源歌曲通用）
                if (getCoverCandidates(song).isEmpty()) {
                    val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                    if (networkCover != null) {
                        _networkCoverUrl.value = networkCover
                        AppLog.d("NASMusic", "loadLyrics: auto-search cover found: ${networkCover.take(60)}")
                    } else {
                        AppLog.d("NASMusic", "loadLyrics: auto-search cover: no result")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadLyrics failed", e)
                showError(getApplication<Application>().getString(R.string.load_lyrics_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 获取歌曲的候选封面 URL 列表（统一入口，不区分 NAS/网络歌曲）。
     * NAS 歌曲：后端 3 类封面（歌曲/专辑/艺术家）+ 网络封面（切在线歌词时追加）
     * 网络歌曲：1 张 pic 封面
     */
    fun getCoverCandidates(song: Song): List<String> {
        val candidates = mutableListOf<String>()

        // 1. 已下载歌曲：优先内嵌封面 → 旁路封面
        val dlState = songDownloadStates.value[song.downloadKey]
        // 确定本地音频路径：优先用下载状态，否则用 song.path（下载后以 LOCAL 源出现，downloadKey 不匹配）
        val localAudioPath: String? = when {
            dlState is DownloadState.Completed && dlState.path.isNotBlank() -> dlState.path
            song.isLocalSong && !song.path.isNullOrBlank() -> song.path
            else -> null
        }
        if (localAudioPath != null) {
            // 1a. 内嵌封面（APIC 帧）→ 提取到缓存文件供 Coil 加载
            val embeddedUri = EmbeddedCoverExtractor.extractCoverUri(
                localAudioPath,
                getApplication<Application>().cacheDir,
                getApplication<Application>()
            )
            if (embeddedUri != null) {
                candidates.add(embeddedUri)
            }
            // 1b. 旁路封面 .jpg 文件（内嵌失败时才有）
            (dlState as? DownloadState.Completed)?.coverPath?.let { cp ->
                if (cp.isNotBlank() && java.io.File(cp).exists()) {
                    candidates.add("file://$cp")
                }
            }
        }

        // 2. 网络/后端封面
        if (song.isNetworkSong) {
            song.coverUrl?.let { candidates.add(it) }
            _networkCoverUrl.value?.let { candidates.add(it) }
        } else {
            val adapter = backendRegistry.getAdapter()
            if (adapter != null) {
                candidates.addAll(adapter.getCoverUrlCandidates(song))
            }
            _networkCoverUrl.value?.let { candidates.add(it) }
        }
        return candidates.distinct().filter { it.isNotBlank() }
    }

    /**
     * 设置歌词高亮模式（用户手动切换逐行/逐字时调用）
     */
    fun setLyricsHighlightMode(mode: LyricsHighlightMode) {
        _lyricsHighlightMode.value = mode
    }

    // 网络歌词候选索引：再次按下"在线歌词"按钮时递增，切换不同候选
    private var networkLyricsCandidateIndex = 0
    private var networkLyricsSongId: String? = null

    /**
     * 切换歌词来源
     * 切换到在线歌词时联动获取网络封面，切回内嵌时清除网络封面
     * 如果当前已显示网络歌词，再次按下"在线歌词"按钮 → 取下一个候选歌词
     */
    fun switchLyricsSource(source: LyricsSource) {
        val song = playerVM.playerState.value.currentSong ?: return
        val currentSource = _currentLyrics.value?.source
        AppLog.d("NASMusic", "switchLyricsSource: $source, currentSource=$currentSource")

        // 记录用户显式来源选择：选过网络歌词后，后续加载不被后端歌词覆盖；选回内嵌/本地则撤销
        when (source) {
            LyricsSource.NETWORK -> userNetworkLyricsOverride.add(song.id)
            LyricsSource.EMBEDDED, LyricsSource.LOCAL_FILE -> userNetworkLyricsOverride.remove(song.id)
            else -> { }
        }

        // 切歌时重置候选索引
        if (song.id != networkLyricsSongId) {
            networkLyricsCandidateIndex = 0
            networkLyricsSongId = song.id
        }

        // 已显示网络歌词 + 再次按下在线歌词按钮 → 尝试下一个候选
        if (source == LyricsSource.NETWORK && currentSource == LyricsSource.NETWORK) {
            networkLyricsCandidateIndex++
            AppLog.d("NASMusic", "switchLyricsSource: increment candidateIndex to $networkLyricsCandidateIndex")
        } else {
            networkLyricsCandidateIndex = 0
        }

        viewModelScope.launch {
            try {
                val lyrics = lyricsManager.getLyricsFromSource(song, source, networkLyricsCandidateIndex)
                _currentLyrics.value = lyrics
                AppLog.d("NASMusic", "switchLyricsSource: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")

                // 联动网络封面：切换到在线歌词时获取，切回内嵌时清除
                if (source == LyricsSource.NETWORK && lyrics != null && !song.isNetworkSong) {
                    val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                    _networkCoverUrl.value = networkCover
                    AppLog.d("NASMusic", "switchLyricsSource: 网络封面=${networkCover?.take(60)}")
                } else {
                    // 切回内嵌/本地文件来源，清除网络封面
                    _networkCoverUrl.value = null
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "switchLyricsSource failed", e)
                showError(getApplication<Application>().getString(R.string.switch_lyrics_source_error, e.message?.take(50)))
            }
        }
    }

    // --- 设置 ---
    fun updateThemeMode(mode: com.nasmusic.tv.data.model.ThemeMode) = viewModelScope.launch {
        prefs.player.setThemeMode(mode)
    }

    /**
     * v2.39.7：「焦点高亮」开关（设备本地偏好，默认开）。
     *
     * 这里只写盘 —— 组合期读的进程级快照由 `NasMusicApp.onCreate` 起的常驻收集刷新，
     * 所以拨完这一格焦点环/缩放立刻重算，不需要重启。
     */
    fun updateFocusHighlightEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.display.setFocusHighlightEnabled(enabled)
    }

    fun updateAutoPlayNext(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setAutoPlayNext(enabled)
    }

    fun updateDefaultPlayMode(mode: PlayMode) = viewModelScope.launch {
        prefs.player.setDefaultPlayMode(mode)
        playerVM.setPlayMode(mode)
    }

    fun updateCacheLyrics(enabled: Boolean) = viewModelScope.launch {
        prefs.lyrics.setCacheLyrics(enabled)
    }

    fun updateCacheCover(enabled: Boolean) = viewModelScope.launch {
        prefs.lyrics.setCacheCover(enabled)
    }

    fun updateLyricsFontScale(scale: Float) = viewModelScope.launch {
        prefs.lyrics.setLyricsFontScale(scale)
    }

    /**
     * 保存分离模型的自定义下载源（方案 §6-11 / T10）。
     *
     * 校验**不在这一层**：`AppPreferences.setModelDownloadUrl` 已经过 `normalizeEndpointUrl`
     * （scheme 必须 http/https、长度 ≤ 500、主机必须有点/端口/localhost），非法值会被它
     * 静默拒绝并保留旧值 —— 在这里再写一套校验只会让两处口径漂移。
     * 空串 = 恢复内置默认候选。
     */
    fun saveCustomModelUrl(url: String) = viewModelScope.launch {
        prefs.setModelDownloadUrl(url)
    }

    suspend fun updateLanguage(lang: String) {
        prefs.languagePrefs.setLanguage(lang)
        // 立即应用语言变更（重启 Activity 以重新加载所有资源）
        (getApplication() as? NasMusicApp)?.applyLocale(lang)
    }

    fun updateCoverFilterEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterEnabled(enabled)
    }

    fun updateCoverFilterBlurRadius(radius: Float) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterBlurRadius(radius)
    }

    fun updateCoverFilterDarkOverlay(overlay: Float) = viewModelScope.launch {
        prefs.visualizer.setCoverFilterDarkOverlay(overlay)
    }

    /**
     * 更新 Meting-API 端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMetingApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.network.setMetingApiBaseUrl(com.nasmusic.tv.backend.network.MetingApiService.DEFAULT_BASE_URL)
        } else {
            prefs.network.setMetingApiBaseUrl(normalized)
        }
    }

    // F2-5：crossfade 设置（PlayerManager 的 volatile 字段由 PlayerViewModel init 收集注入）
    fun setCrossfadeEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.player.setCrossfadeEnabled(enabled)
    }

    fun setCrossfadeDurationSec(sec: Int) = viewModelScope.launch {
        prefs.player.setCrossfadeDurationSec(sec)
    }

    // F2-6：音质档位（Meting br 参数；AUTO 由端点默认/带宽决策）
    fun setQualityTier(tier: Int) = viewModelScope.launch {
        prefs.player.setQualityTier(tier)
    }

    /**
     * v2.35.0 多码率：设置音质档位（方案 §5.1「全部歌曲 / 仅本次播放」两级范围）。
     *
     * - [scope] = ALL → 写全局默认 + 清直链缓存（由 AppPreferences 回调触发）+ 重播当前曲
     * - [scope] = THIS_SONG → 写单曲覆盖，仅当前曲生效，不污染全局默认
     *
     * 档位切换必须 `forceRefresh=true` 重解析：否则会命中"已过期但仍在 5min TTL 内"
     * 的旧档位直链（与播放失败重试同源问题，见 NetworkMusicManager 注释）。
     */
    fun setQualityTier(tier: Int, scope: QualityScope) = viewModelScope.launch {
        val song = playerVM.playerState.value.currentSong
        when (scope) {
            QualityScope.ALL -> {
                prefs.player.setQualityTier(tier)
            }
            QualityScope.THIS_SONG -> {
                val src = song?.networkSource
                val id = song?.networkId
                if (src != null && id != null) {
                    nasMusicApp.qualityOverrides.put(src, id, tier)
                } else {
                    // 非网络歌曲无单曲覆盖概念 → 退化为全局设置
                    prefs.player.setQualityTier(tier)
                }
            }
        }
        // 重播当前网络歌曲（强制绕过缓存，走新档位解析）
        if (song != null && song.isNetworkSong) {
            replayCurrentWithQuality(tier)
        }
    }

    /**
     * 以指定档位重播当前曲，并在降级时提示用户（方案 §2.6：播放侧一次性提示）。
     */
    private suspend fun replayCurrentWithQuality(tier: Int) {
        val song = playerVM.playerState.value.currentSong ?: return
        val mgr = nasMusicApp.networkMusicManager
        val result = mgr.resolvePlayUrlDetailed(song, tier, forceRefresh = true)
        if (result.url == null) {
            showError(getApplication<Application>().getString(R.string.quality_probe_none))
            return
        }
        // 静默降级 → 一次性提示（不阻断播放）
        if (result.isDowngradedFrom(tier)) {
            val reqLabel = getApplication<Application>().getString(QualityTiers.labelResOf(tier))
            val actLabel = getApplication<Application>().getString(QualityTiers.labelResOf(result.actualQuality))
            showError(getApplication<Application>().getString(R.string.quality_downgrade_notice, reqLabel, actLabel))
        }
        // 2026-09-26 修复（High #7 同族）：异步质量档重解析期间用户可能已切歌，
        // 无条件用入口 song 快照回写会把旧歌整队回滚、播放被拽回旧歌。
        // 回写前重读当前播放歌曲比对 id，不一致则丢弃本次回写。
        val current = playerVM.playerState.value.currentSong
        if (current == null || current.id != song.id) {
            AppLog.d("MainViewModel", "replayCurrentWithQuality: current song changed (${song.id} → ${current?.id}), drop quality replay")
            return
        }
        playerVM.playSong(current.copy(streamUrl = result.url, resolvedQuality = result.actualQuality))
    }

    /** v2.35.0 多码率：清除全部单曲音质覆盖（设置页入口） */
    fun clearAllQualityOverrides() = viewModelScope.launch {
        nasMusicApp.qualityOverrides.clearAll()
    }

    /** 单曲音质覆盖条目数（设置页展示用） */
    fun qualityOverrideCount(): Int = nasMusicApp.qualityOverrides.size()

    /**
     * 查询某曲当前生效的音质档位（单曲覆盖 ?: 全局默认）。
     *
     * 供 UI 展示（NowPlaying 音质按钮标签、下载面板默认选中项）使用。
     * 同步读内存镜像，可在 Composable 的 remember 中安全调用。
     */
    fun effectiveQualityFor(song: Song): Int {
        val src = song.networkSource
        val id = song.networkId
        if (src == null || id == null) return prefs.player.getQualityTierSyncSafe()
        return nasMusicApp.qualityOverrides.tierOf(src, id) ?: prefs.player.getQualityTierSyncSafe()
    }

    /**
     * 更新 MTV 视频搜索端点 URL（网络搜索配置）
     * 传入空串则恢复默认端点
     */
    fun updateMvApiBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.network.setMvApiBaseUrl(com.nasmusic.tv.backend.network.mv.BilibiliMvService.DEFAULT_BASE_URL)
        } else {
            prefs.network.setMvApiBaseUrl(normalized)
        }
    }

    fun updateLyricsKugouBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.lyrics.setLyricsKugouBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL)
        } else {
            prefs.lyrics.setLyricsKugouBaseUrl(normalized)
        }
    }

    fun updateLyricsNeteaseBaseUrl(url: String) = viewModelScope.launch {
        val normalized = url.trim()
        if (normalized.isEmpty()) {
            prefs.lyrics.setLyricsNeteaseBaseUrl(com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL)
        } else {
            prefs.lyrics.setLyricsNeteaseBaseUrl(normalized)
        }
    }

    /**
     * 更新 OpenWeatherMap API Key
     */
    fun updateWeatherApiKey(key: String) = viewModelScope.launch {
        prefs.weather.setWeatherApiKey(key.trim())
    }

    /** 更新腾讯位置服务 key（§10.409）：留空 ⇒ 删除键，IP 定位走 ip.sb */
    fun updateTencentMapKey(key: String) = viewModelScope.launch {
        prefs.weather.setTencentMapKey(key.trim())
    }

    // ===================== T16（方案 §5.4）：网络连通性探针 =====================
    // 从 SettingsScreen 的 onRunNetworkTest 内联 lambda 下沉（原为 UI 层持有
    // HttpURLConnection I/O，且 rememberCoroutineScope 会让离开设置页的探针被取消）。
    // 探针 URL / 超时 / HEAD 方法 / 结果文案逐行照搬原 lambda，行为不变；
    // 副作用差异只有一条：探针改挂 viewModelScope，离开设置页不再被取消（改进）。

    private val _networkProbeRunning = MutableStateFlow(false)

    /** 探针进行中（UI 据此禁用按钮并显示「测试中」） */
    val networkProbeRunning: StateFlow<Boolean> = _networkProbeRunning.asStateFlow()

    private val _networkProbeStatus = MutableStateFlow("")

    /**
     * 探针结果，带 `success:` / `error:` 前缀（`NetworkMusicSection` 按前缀着色，口径不变）；
     * 空串 = 尚无结果。
     */
    val networkProbeStatus: StateFlow<String> = _networkProbeStatus.asStateFlow()

    /** 发起一次连通性探针（HEAD https://www.baidu.com，5s 超时）；进行中重复调用被忽略（防重入）。 */
    fun runNetworkProbe() {
        if (_networkProbeRunning.value) return
        _networkProbeRunning.value = true
        _networkProbeStatus.value = ""
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://www.baidu.com")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) "success:${app.getString(R.string.settings_network_test_success, code)}"
                    else "error:${app.getString(R.string.settings_network_test_http_error, code)}"
                } catch (e: java.net.SocketTimeoutException) {
                    "error:${app.getString(R.string.settings_network_test_timeout)}"
                } catch (e: java.net.UnknownHostException) {
                    "error:${app.getString(R.string.settings_network_test_dns_error)}"
                } catch (e: java.net.ConnectException) {
                    "error:${app.getString(R.string.settings_network_test_connection_refused)}"
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    "error:${app.getString(R.string.settings_network_test_error, e.message ?: e.javaClass.simpleName)}"
                }
            }
            _networkProbeStatus.value = result
            _networkProbeRunning.value = false
        }
    }

    // ===================== v2.39.4：运行日志（设置页「日志」tab） =====================
    // 数据源是 LogStore 落盘的 filesDir/logs/app.log（AppLog.w/e 与崩溃处理器写入）。
    // 这里只负责「读快照 / 清空」，写入路径不经过 ViewModel —— 日志产生点遍布全应用，
    // 不能反过来依赖 UI 层。

    private val _logs = MutableStateFlow(LogSnapshot())

    /** 最近日志（时间倒序）+ 文件占用；进入 LOGS 分区或点「刷新」时更新 */
    val logs: StateFlow<LogSnapshot> = _logs.asStateFlow()

    /** 日志页级别过滤（持久化的设备本地偏好；默认 D=全部） */
    private val _logsMinLevel = MutableStateFlow(prefs.getLogsMinLevelSync())
    val logsMinLevel: StateFlow<String> = _logsMinLevel.asStateFlow()

    init {
        // 冷启动时 @Volatile 镜像可能还没收到 DataStore 的首次发射，用 Flow 补齐
        viewModelScope.launch {
            prefs.logsMinLevel.collect { _logsMinLevel.value = it }
        }
    }

    fun setLogsMinLevel(level: String) {
        val normalized = LogStore.normalizeLevel(level)
        _logsMinLevel.value = normalized
        viewModelScope.launch { prefs.setLogsMinLevel(normalized) }
    }

    /** 重新读取日志文件（进入分区 / 手动刷新）。 */
    fun refreshLogs() {
        viewModelScope.launch {
            // 读满整个保留窗口（轮转只留 KEEP_LINES 行）：级别过滤要在全窗口里找，
            // 否则「只看错误」会被「最近 100 条恰好全是警告」骗成空列表。
            _logs.value = withContext(Dispatchers.IO) { LogStore.snapshot(limit = LogStore.KEEP_LINES) }
        }
    }

    /** 清空日志文件并立即刷新快照（清空后页面立刻变空，不等下次进页）。 */
    fun clearLogs() {
        viewModelScope.launch {
            _logs.value = withContext(Dispatchers.IO) {
                LogStore.clear()
                LogStore.snapshot(limit = LogStore.KEEP_LINES)
            }
        }
    }

    fun updateVisualizerQuality(quality: com.nasmusic.tv.data.model.VisualQuality) = viewModelScope.launch {
        prefs.setVisualizerQuality(quality)
    }

    fun updateVisualizerTheme(theme: com.nasmusic.tv.data.model.VisualizerTheme) = viewModelScope.launch {
        prefs.visualizer.setVisualizerTheme(theme)
    }

    // --- 歌曲离线下载设置 ---
    fun updateDownloadEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.download.setDownloadEnabled(enabled)
    }

    fun updateAutoDownloadOnPlay(enabled: Boolean) = viewModelScope.launch {
        prefs.download.setAutoDownloadOnPlay(enabled)
    }

    fun updateAutoDownloadLimit(limit: Int) = viewModelScope.launch {
        prefs.download.setAutoDownloadLimit(limit)
    }

    fun updateDownloadLocation(location: String) = viewModelScope.launch {
        prefs.download.setDownloadLocation(location)
    }

    /**
     * §10.411：设置页展示「外置卷是否可用 / 当前实际写入根」。
     *
     * 不用 DataStore Flow 直接算：外置卷挂载状态不是持久化设置，重组时现查即可；
     * 调用方用 remember(settings.downloadLocation) 缓存，避免每次重组做 StatFs。
     * @return Pair(外部存储可用, 当前根目录绝对路径)
     */
    fun downloadRootInfo(): Pair<Boolean, String> {
        val app = getApplication<android.app.Application>()
        val resolver = com.nasmusic.tv.backend.download.DownloadRootResolver
        val location = prefs.getDownloadLocationSync()
        return resolver.isCustomAvailable(app) to resolver.currentRoot(app, location).absolutePath
    }

    fun updateFontAdjustment(adjustment: Int) = viewModelScope.launch {
        prefs.visualizer.setFontAdjustment(adjustment)
    }

    /** 手动下载单曲（歌曲行 ⬇ 按钮）——已迁至 DownloadViewModel.downloadSong */

    // --- 导出功能（§8.8）---
    // ⛔ `exportCoordinator` 声明已移至**类顶部**（v2.39.3 补丁，构造期竞态防护，同上）。勿挪回。
    // 注意：下方 `exportState` 读取该 lazy 会在此处（构造器内）触发其初始化，安全——
    // 声明提前后委托字段早已赋值。

    /** 导出状态（供设置页 UI 订阅） */
    val exportState: StateFlow<ExportState> = exportCoordinator.state

    /** 导出设备列表（刷新后更新） */
    private val _exportDevices = MutableStateFlow<List<StorageDevice>>(emptyList())
    val exportDevices: StateFlow<List<StorageDevice>> = _exportDevices.asStateFlow()

    /** 设备选择弹窗显隐 */
    private val _showExportDeviceDialog = MutableStateFlow(false)
    val showExportDeviceDialog: StateFlow<Boolean> = _showExportDeviceDialog.asStateFlow()

    /** 显示设备选择弹窗（刷新设备列表后弹出） */
    fun showExportDeviceDialog() {
        viewModelScope.launch {
            val devices = nasMusicApp.storageMonitor.storageDevices.value
            exportCoordinator.refreshDevices(devices)
            _exportDevices.value = exportCoordinator.requestExportDevices()
            _showExportDeviceDialog.value = true
        }
    }

    /** 用户选择设备后触发导出 */
    fun onExportDeviceSelected(device: StorageDevice) {
        _showExportDeviceDialog.value = false
        exportCoordinator.exportTo(device)
    }

    /** 用户取消导出 */
    fun cancelExport() {
        exportCoordinator.cancel()
    }

    /** 重置导出状态（完成后/关闭弹窗后） */
    fun resetExportState() {
        exportCoordinator.reset()
    }

    // --- E-4 缓存管理 ---

    /**
     * 剩余空间（设置页 CACHE「占用与存储」首行，方案 §1.14 / T8b）。
     *
     * 直接转发 [com.nasmusic.tv.backend.download.StorageGuard.availableBytes] —— 该类
     * 已用「双 StatFs 取 min + 30s 轮询 + 广播」维护这个值，设置页**不得**再起第二个
     * `StatFs`，否则下载队列与设置页会看到两个不同的"可用空间"。
     */
    val storageAvailableBytes: kotlinx.coroutines.flow.StateFlow<Long> =
        nasMusicApp.storageGuard.availableBytes

    fun clearLyricsCache() {
        viewModelScope.launch {
            lyricsManager.clearCache()
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_lyrics_cache_cleared))
        }
    }

    /**
     * 清除封面地址表（`filesDir/cover_url_cache.json`，方案 §1.14 / T8b）。
     *
     * 该清除函数（`CoverUrlPersistentCache.clear()`）早写好了却从未接线，KDoc 里
     * "供设置页缓存管理手动清除"的承诺到本轮才兑现。清后需重新联网解析封面地址。
     */
    fun clearCoverUrlCache() {
        viewModelScope.launch {
            nasMusicApp.coverUrlPersistentCache.clear()
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_cover_url_cache_cleared))
        }
    }

    /**
     * 清除解包封面缓存：`cacheDir` 根下的 `cover_cache_*` 散文件（内嵌 APIC 提取结果）
     * + `cacheDir/album_covers/`（本机曲库封面缩略图）。两者都由本方法一次清掉。
     */
    fun clearExtractedCoverCache() {
        viewModelScope.launch {
            val context = getApplication<android.app.Application>()
            val freed = com.nasmusic.tv.backend.local.EmbeddedCoverExtractor
                .clearDiskCache(context.cacheDir) +
                com.nasmusic.tv.backend.local.LocalCoverExtractor(context).clearDiskCache()
            AppLog.d("MainViewModel", "clearExtractedCoverCache: freed $freed bytes")
            postLocalConnectMessage(
                getApplication<Application>().getString(
                    R.string.status_cover_cache_freed,
                    com.nasmusic.tv.ui.screens.settings.formatBytes(freed)))
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearCoverCache() {
        viewModelScope.launch {
            val context = getApplication<android.app.Application>()
            // 取 Coil 全局 ImageLoader（与 PlaybackService/CoilBitmapLoader 同一实例）。
            // 此前用 coil.ImageLoader(context) 工厂函数会创建全新无配置实例，
            // 其 memoryCache/diskCache 与 UI 实际使用的缓存不是同一个，清除不生效。
            val imageLoader = Coil.imageLoader(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
            AppLog.d("MainViewModel", "clearCoverCache: cache cleared")
            postLocalConnectMessage(
                getApplication<Application>().getString(R.string.status_cover_cache_cleared))
        }
    }

    // （clearMvPersistentCache / clearAllSeparationArtifacts 已迁至子 ViewModel 转发区，勿重复定义）

    // --- B-4 均衡器 ---
    val equalizerPreset: StateFlow<EqualizerPreset> = prefs.visualizer.equalizerPreset.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = EqualizerPreset.NORMAL
    )

    val equalizerBands: StateFlow<List<Float>> = prefs.visualizer.equalizerBands.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    fun setEqualizerPreset(preset: EqualizerPreset) {
        viewModelScope.launch {
            prefs.visualizer.setEqualizerPreset(preset)
            // 同时持久化频段数据到 DataStore，确保 UI 能正确显示 currentBands
            prefs.visualizer.setEqualizerBands(preset.bandGains)
            // 应用频段到 PlayerManager
            playerManager.setEqualizerBands(preset.bandGains)
        }
    }

    fun setEqualizerBand(index: Int, value: Float) {
        viewModelScope.launch {
            prefs.visualizer.setEqualizerBand(index, value)
            // Apply to PlayerManager audio engine
            playerManager.setEqualizerBand(index, value)
        }
    }

    // --- F-1 播放列表（B-12: UiState）---
    private val _playlists = MutableStateFlow<UiState<List<Playlist>>>(UiState.Success(emptyList()))
    val playlists: StateFlow<UiState<List<Playlist>>> = _playlists.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
    val selectedPlaylistSongs: StateFlow<UiState<List<Song>>> = _selectedPlaylistSongs.asStateFlow()

    fun loadPlaylists() {
        // ⚠️ NAS 关 ⇒ 不拉 NAS 歌单（方案 §3.2.1 #6）。
        // ⛔ `_playlists` 的清空由 clearSourceScopedCaches 负责（关源方向唯一入口）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "loadPlaylists: NAS 已关闭，跳过请求")
            return
        }
        _playlists.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _playlists.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                _playlists.value = UiState.Success(adapter.getPlaylists())
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "loadPlaylists failed", e)
                _playlists.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_playlists_error, e.message?.take(50)),
                    retry = { loadPlaylists() }
                )
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        // ⚠️ NAS 关 ⇒ 不请求歌单曲目（方案 §3.2.1 #18）。
        if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
            AppLog.d("NASMusic", "selectPlaylist: NAS 已关闭，跳过请求")
            _selectedPlaylistSongs.value = UiState.Success(emptyList())
            return
        }
        _selectedPlaylistSongs.value = UiState.Loading
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: run {
                _selectedPlaylistSongs.value = UiState.Error(getApplication<Application>().getString(R.string.backend_not_connected))
                return@launch
            }
            try {
                val songs = adapter.getPlaylistSongs(playlist.id)
                _selectedPlaylistSongs.value = UiState.Success(songs)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "selectPlaylist songs failed", e)
                _selectedPlaylistSongs.value = UiState.Error(
                    message = getApplication<Application>().getString(R.string.load_playlist_songs_error, e.message?.take(50)),
                    retry = { selectPlaylist(playlist) }
                )
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val result = adapter.createPlaylist(name)
                if (result != null) {
                    val current = _playlists.value.dataOrNull() ?: emptyList()
                    _playlists.value = UiState.Success(current + result)
                    postLocalConnectMessage(getApplication<Application>().getString(R.string.playlist_created))
                } else {
                    postLocalConnectMessage(getApplication<Application>().getString(R.string.create_failed))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                postLocalConnectMessage(getApplication<Application>().getString(R.string.create_failed_with_msg, e.message))
            }
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.deletePlaylist(playlist.id)
                if (success) {
                    val current = _playlists.value.dataOrNull() ?: emptyList()
                    _playlists.value = UiState.Success(current.filter { it.id != playlist.id })
                    val selSongs = _selectedPlaylistSongs.value.dataOrNull()
                    if (selSongs != null && selSongs.any { it.albumId == playlist.id }) {
                        _selectedPlaylistSongs.value = UiState.Success(emptyList())
                    }
                    postLocalConnectMessage(getApplication<Application>().getString(R.string.playlist_deleted))
                } else {
                    postLocalConnectMessage(getApplication<Application>().getString(R.string.delete_failed))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                postLocalConnectMessage(getApplication<Application>().getString(R.string.delete_failed_with_msg, e.message))
            }
        }
    }

    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            // ⚠️ NAS 关 ⇒ 不请求歌单曲目、也不播放（方案 §3.2.1 #18）
            if (!sourceVisibility.isVisible(MusicSourceType.NAS)) {
                AppLog.d("NASMusic", "playPlaylist: NAS 已关闭，跳过请求")
                return@launch
            }
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val songs = adapter.getPlaylistSongs(playlist.id)
                if (songs.isNotEmpty()) {
                    playQueue(songs)
                    navVM.navigateTo(Screen.NowPlaying)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "playPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.play_playlist_error, e.message?.take(50)))
            }
        }
    }

    fun removeFromPlaylist(songId: String) {
        viewModelScope.launch {
            val currentSongs = _selectedPlaylistSongs.value.dataOrNull() ?: return@launch
            val playlistId = currentSongs.firstOrNull { it.id == songId }?.albumId ?: return@launch
            val adapter = backendRegistry.getAdapter() ?: return@launch
            try {
                val success = adapter.removeFromPlaylist(playlistId, songId)
                if (success) {
                    _selectedPlaylistSongs.value = UiState.Success(currentSongs.filter { it.id != songId })
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NASMusic", "removeFromPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.remove_from_playlist_error, e.message?.take(50)))
            }
        }
    }

    // ===================== 电台（radio-browser）& Jamendo（CC 独立音乐） =====================

    // --- 电台 ---
    private val _radioStations = MutableStateFlow<UiState<List<RadioStation>>>(UiState.Success(emptyList()))
    val radioStations: StateFlow<UiState<List<RadioStation>>> = _radioStations.asStateFlow()
    private val _radioActiveTag = MutableStateFlow<String?>(null)
    val radioActiveTag: StateFlow<String?> = _radioActiveTag.asStateFlow()
    private val _radioActiveQuery = MutableStateFlow("")
    val radioActiveQuery: StateFlow<String> = _radioActiveQuery.asStateFlow()

    /**
     * 加载默认电台列表（中文电台热门）。幂等：当前无筛选且已有数据则跳过。
     */
    fun loadRadioDefault() {
        val tag = _radioActiveTag.value
        val query = _radioActiveQuery.value
        if (tag == null && query.isBlank() && _radioStations.value.dataOrNull()?.isNotEmpty() == true) return
        _radioActiveTag.value = null
        _radioActiveQuery.value = ""
        loadRadioStations(tag = null, query = null, countryCode = "CN")
    }

    /** 按标签加载电台 */
    fun loadRadioTag(tag: String) {
        _radioActiveTag.value = tag
        _radioActiveQuery.value = ""
        loadRadioStations(tag = tag, query = null, countryCode = null)
    }

    /** 搜索电台 */
    fun searchRadio(keyword: String) {
        val kw = keyword.trim()
        _radioActiveTag.value = null
        _radioActiveQuery.value = kw
        if (kw.isBlank()) {
            loadRadioStations(tag = null, query = null, countryCode = null)
            return
        }
        loadRadioStations(tag = null, query = kw, countryCode = null, pinyinFallback = true)
    }

    /**
     * @param pinyinFallback 客户端拼音兜底：radio-browser 的 `name=` 参数只做**字面量**匹配，
     *        拼音/首字母（`zjl`）在服务端必然 0 条。为 true 且关键词是拼音形态时，
     *        额外抓一份热门榜在客户端按同一套拼音口径筛，再与直搜结果合并去重。
     */
    private fun loadRadioStations(
        tag: String?,
        query: String?,
        countryCode: String?,
        pinyinFallback: Boolean = false,
        limit: Int = 50
    ) {
        viewModelScope.launch {
            _radioStations.value = UiState.Loading
            try {
                val client = nasMusicApp.radioBrowserClient
                val direct = client.searchStations(
                    query = query, tag = tag, countryCode = countryCode, limit = limit
                )
                val fallback = if (pinyinFallback && query != null && SearchQuery.isPinyinLike(query)) {
                    // 热门榜覆盖不到全部电台（radio-browser 有数万条），但足以覆盖「用户会用
                    // 首字母去找的那批」；真机验证口径见 §10.417。
                    val broad = client.searchStations(limit = RADIO_PINYIN_SCAN_LIMIT)
                    withContext(Dispatchers.Default) {
                        broad.filter { station -> PinyinUtils.matchesQuery(station.name, query) }
                    }
                } else {
                    emptyList()
                }
                _radioStations.value = UiState.Success((direct + fallback).distinctBy { it.uuid })
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("Radio", "loadRadioStations failed: ${e.message}", e)
                _radioStations.value = UiState.Error(message = getApplication<Application>().getString(R.string.radio_load_failed))
            }
        }
    }

    /** 播放电台（即点即播直播流，进入播放页显示"直播"态） */
    fun playRadioStation(station: RadioStation) {
        viewModelScope.launch {
            try {
                nasMusicApp.radioBrowserClient.reportClick(station)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // 上报失败不影响播放
            }
            playQueue(listOf(station.toSong()))
            navVM.navigateTo(Screen.NowPlaying)
        }
    }

    // --- Jamendo ---
    // 独立 JamendoTab（UI + 榜单/标签/搜索 VM 方法）已删除：该 Tab 全链零调用。
    // ⭐ Jamendo **作为搜索源仍然活着**：注册进 NetworkMusicManager.services 并参与
    //    SearchAggregator 五源聚合，受 SOURCE_JAMENDO 开关 + clientId 非空控制。
    //    这里只保留设置页仍在用的 Client ID 更新入口。

    /** 更新 Jamendo Client ID 并动态注册/注销服务（生效 = 开关 **且** clientId 非空，§7.5） */
    fun updateJamendoClientId(id: String) {
        viewModelScope.launch {
            prefs.network.setJamendoClientId(id)
            nasMusicApp.refreshJamendoServiceRegistration()
        }
    }

    // =====================================================================
    // H2 修复（2026-10-06，代码审查报告 §3）：构造期接线后置。
    //
    // 原先这些接线位于文件头部第一个 init 块（早于 navVM/_homeDashboardData/
    // _albums/_playlists 等 15 个字段声明）。Kotlin 属性按声明顺序初始化，
    // 回调闭包虽在构造完成后才触发，但 `onConnected` 等任何一条若将来出现
    // 同步触发点（或子 VM 在赋值回调前就读取 provider），都会读到未初始化字段
    // → 启动 NPE（v2.37.0 曾因同类问题崩溃过一次）。
    //
    // 现接线区位于**全部字段声明之后**（本 init 块紧贴文件尾部的 constructorReady
    // init 块之前），任何被引用字段在接线执行时都已初始化完毕。
    // =====================================================================
    init {
        // ---- ServerViewModel 接线 ----
        serverVM.onConnected = {
            loadLibrary()
            // 更新恢复队列中 NAS 歌曲的 streamUrl
            updateRestoredQueueStreamUrls()
            // 导航到首页
            navVM.navigateTo(Screen.Home)
            loadHomeDashboard()
            // 2026-11-12：NAS 连上后**自动扫**照片墙（Jellyfin 来源开着才扫）
            // ⛔ 连接成功这条路不碰照片墙设置 ⇒ 编排器不会自己判出「要重扫」⇒ 必须显式补
            visualizerVM.onNasConnected()
        }
        serverVM.onDisconnected = {
            _albums.value = UiState.Loading
            _songs.value = UiState.Loading
            _songsPaging.value = SongsPagingState()
            _artists.value = UiState.Success(emptyList())
            _years.value = UiState.Success(emptyList())
            _recentSongs.value = UiState.Success(emptyList())
            searchVM.clearSearch()
            _genres.value = UiState.Success(emptyList())
            _favoriteSongs.value = UiState.Success(emptyList())
            _playlists.value = UiState.Success(emptyList())
        }
        serverVM.checkSavedConfigOnStart()
        serverVM.refreshApiVersionsAsync()

        // ---- SearchViewModel 接线 ----
        searchVM.libraryActiveTabProvider = { _libraryActiveTab.value }
        searchVM.librarySearchKeywordProvider = { _librarySearchKeyword.value }
        // 拼音搜索用的 NAS 本地缓存：NAS 关时交空（防御性——当前 `SearchAggregator` 的 NAS 分支
        // 整体被门控、根本不会读它；但若聚合器门控口径变化，未过滤的原始库存会直接进搜索结果）
        searchVM.nasLocalSongsProvider = {
            if (sourceVisibility.isVisible(MusicSourceType.NAS)) _songsPaging.value.songs
            else emptyList()
        }
        searchVM.localDeviceSongsProvider = { visibleLocalSongsNow() }
        searchVM.onAddToQueue = { playerManager.addToQueue(it) }
        searchVM.onPlayBatch = { songs, startIndex -> playNetworkBatch(songs, startIndex) }
        searchVM.showMessage = { showError(it) }
        searchVM.showMessageFor = { added, skipped, _ ->
            serverVM.postConnectMessage(
                getApplication<Application>().getString(R.string.added_to_queue_with_skipped, added, skipped)
            )
        }

        // M30：PlaylistViewModel 的歌单 CRUD 失败也要能到达用户（此前只写无人订阅的 StateFlow）
        playlistVM.showMessage = { showError(it) }

        // ---- NetworkMusicViewModel 接线 ----
        netVM.onPlayQueue = { songs, startIndex -> playQueue(songs, startIndex) }
        netVM.showMessage = { showError(it) }
        netVM.onMergedDataInvalidated = { updateMergedData() }
        // 启动期恢复百度索引状态 + 触发合并
        netVM.restoreBaiduIndexOnStart { _ -> updateMergedData() }
    }

    /**
     * ⛔ 类体**最后一个** init 块：执行到此处时，本类所有属性（含此前 1700+ 行才声明的
     * `by lazy` 委托）均已初始化完毕。放行 [constructorReady]，构造期排队等待的
     * `updateMergedData()` 协程从此处开始执行。
     * ⚠️ 不要把任何属性声明挪到本 init 块之后（会重新打开构造期竞态窗口）。
     * ⛔ v2.39.3 补丁：**尤其不要把 `by lazy` 放后面**——R8 lambda 合并可让守卫失效，
     * 晚声明的 lazy 委托字段会被并发协程读到 null。一律声明在类顶部，
     * 由 `MainViewModelConstructionOrderTest` 门禁把守。
     */
    init {
        constructorReady.complete(Unit)
    }
}
