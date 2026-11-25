package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.SearchQuery
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 网盘搜索时本地索引命中的**条数上限**。
 *
 * 拼音输入会先查本地索引（`BaiduFileIndexCache.searchSongs`），一个宽泛的输入（如 `a`）
 * 可能命中上万条；网盘页是一次性列表，200 条足够翻，也避免一次性把大列表塞进 Compose。
 */
private const val NETDISK_SEARCH_LIMIT = 200

/**
 * 网络音乐/百度网盘域 ViewModel（R-1 拆分自 MainViewModel）。
 *
 * W0 冻结：百度组件通过属性委托延迟访问（与 MainViewModel 原实现 :4847-4849 形式一致）。
 */
class NetworkMusicViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    private val baiduOAuth: BaiduOAuthClient get() = nasMusicApp.baiduOAuthClient
    private val baiduApi: BaiduPanApi get() = nasMusicApp.baiduPanApi
    private val baiduIndexCache: BaiduFileIndexCache get() = nasMusicApp.baiduFileIndexCache

    /**
     * ⭐ 百度网盘源当前是否「生效」（音乐源总开关的同步判据）。
     *
     * 取法沿用本类既有的 `prefs`（= `nasMusicApp.appPreferences`）：
     * `prefs.getBaiduActiveSync()`（`AppPreferences.kt:1376`）——它是
     * `CloudDriveConfig.isActive`（`enabled && tokens != null`，即**启用 且 已登录**）
     * 的 `@Volatile` 同步镜像，**与 `NasMusicApp` 里 `registerService` 的判据完全一致**
     * （`NasMusicApp.kt:478` 用的是同一个 `isActive`）。
     * ⚠️ 只回答「用户把百度源开着吗」，**不回答**「它能不能用」——后者是 services map 的事
     *    （与 `AppPreferences.isSourceEnabledSync` 的 KDoc 同一纪律）。
     */
    val isBaiduSourceActive: Boolean get() = prefs.getBaiduActiveSync()

    /** 加入队列动作（经 MainViewModel 路由到 PlayerViewModel） */
    var onAddToQueue: ((List<Song>) -> Unit)? = null
    /** 播放动作（经事件路由到 PlayerViewModel） */
    var onPlayQueue: ((List<Song>, Int) -> Unit)? = null
    /** 消息通道 */
    var showMessage: ((String) -> Unit)? = null

    /** 合并数据联动回调（百度索引变化时刷新曲库） */
    var onMergedDataInvalidated: (() -> Unit)? = null

    // ---- 网络收藏 ----

    private val _networkFavorites = MutableStateFlow<List<NetworkFavoriteItem>>(emptyList())

    init {
        // 监听网络收藏变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.history.networkFavorites.collect { favorites ->
                _networkFavorites.value = favorites
            }
        }
    }

    /** 网络收藏原始列表（供 MainViewModel 派生 networkFavoriteSongs / networkFavoriteIds） */
    val networkFavorites: StateFlow<List<NetworkFavoriteItem>> = _networkFavorites.asStateFlow()

    /**
     * 切换歌曲收藏状态（统一模型）
     *
     * 所有歌曲共用一套收藏：网络/本地歌曲持久化到 DataStore（NetworkFavoriteItem，
     * 本地歌曲 networkSource="local"）。
     */
    fun toggleNetworkFavorite(song: Song, isNasFavorite: Boolean, onNasToggle: (Song, Boolean) -> Unit) {
        if (song.isNetworkSong || song.isLocalSong) {
            // 网络 / 本地歌曲：DataStore 持久化
            viewModelScope.launch {
                val item = NetworkFavoriteItem(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverUrl = song.coverUrl,
                    networkSource = if (song.isLocalSong) "local" else song.networkSource ?: "network",
                    networkId = song.networkId ?: "",
                    // 平台快照随收藏落盘：搜索解析时 Song 已带 networkPlatform，
                    // 回放时据此按原平台取链（洛雪增强器读 Song.networkPlatform）。
                    networkPlatform = song.networkPlatform,
                    // G6-01：已下载歌曲要保住 storageType，否则重建后 sourceType 落回 LOCAL
                    storageType = song.storageType,
                    addedAtMs = System.currentTimeMillis()
                )
                prefs.history.toggleNetworkFavorite(item)
            }
        } else {
            // NAS 歌曲：走后端 adapter
            onNasToggle(song, isNasFavorite)
        }
    }

    /**
     * 判断网络歌曲是否已收藏（同步，用于 UI 快速判断）
     */
    fun isNetworkFavorite(songId: String): Boolean {
        return _networkFavorites.value.any { it.songId == songId }
    }

    /**
     * 播放网络歌曲
     *
     * 网络歌曲的 streamUrl 不持久化，播放前实时解析：
     * 1. 通过 NetworkMusicManager.resolvePlayUrl() 获取直联 URL
     * 2. 将解析后的 URL 填入 song.streamUrl
     * 3. 交给 PlayerManager 播放
     *
     * 解析失败时显示错误提示。
     */
    fun playNetworkSong(song: Song, onPlaySong: (Song) -> Unit) {
        AppLog.d("NetworkMusicViewModel", "playNetworkSong ENTRY: id=${song.id} title=${song.title} networkSource=${song.networkSource} networkId=${song.networkId} isNetworkSong=${song.isNetworkSong}")
        if (!song.isNetworkSong) {
            // 非 network 歌曲，走普通播放流程
            onPlaySong(song)
            return
        }
        // 防御：百度源若因登录时序未注册（浏览用 baiduApi 直连不需要注册，但播放需 services["baidu"]），
        // 播放时自愈注册，避免"能浏览不能播"
        viewModelScope.launch {
            // T2 第一批：注册改 suspend，移入协程内保证"先注册、后解析播放"
            if (song.networkSource == "baidu" && !nasMusicApp.networkMusicManager.isServiceRegistered("baidu")) {
                AppLog.w("NetworkMusicViewModel", "playNetworkSong: 检测到 baidu 服务未注册，尝试自愈注册后播放")
                nasMusicApp.refreshBaiduServiceRegistration()
            }
            try {
                // 已下载优先：本地文件存在则直接播本地（离线可播、省去直链解析），
                // 避免"已下载歌曲在直链过期/断网时仍走网络解析失败"
                val localUri = runCatching {
                    nasMusicApp.downloadRepository.playableLocalUri(song)
                }.getOrNull()
                if (!localUri.isNullOrBlank()) {
                    AppLog.d("NetworkMusicViewModel", "playNetworkSong: '${song.title}' 已下载，直接播本地文件")
                    onPlaySong(song.copy(streamUrl = localUri))
                    return@launch
                }
                val playUrl = nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                if (playUrl.isNullOrBlank()) {
                    if (song.networkSource == "baidu") {
                        AppLog.w("NetworkMusicViewModel", "playNetworkSong: 百度网盘 resolvePlayUrl 返回 null（检查 token 授权 / filemetas dlink / 网络，详见 BaiduStreamFactory 日志）")
                    }
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_failed_retry))
                    return@launch
                }
                val playable = song.copy(streamUrl = playUrl)
                // L21：`playUrl` 是**解析后的直链** —— 百度 dlink、各家临时签名全在 query 里。
                // 全量打日志等于把凭据写进 LogStore（落盘、可在设置页回看/导出）。
                AppLog.d("NetworkMusicViewModel", "playNetworkSong: ${song.title} → ${redactUrl(playUrl)}")
                onPlaySong(playable)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "playNetworkSong failed", e)
                showMessage?.invoke(getApplication<Application>().getString(R.string.play_failed_with_msg, e.message?.take(50)))
            }
        }
    }

    /**
     * L21：日志脱敏 —— 去掉 URL 的 query 与 fragment（签名 / token / dlink 都在那里）。
     *
     * 保留 scheme+host+path，足以判断「打到了哪个源、哪条路径」。
     */
    private fun redactUrl(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        return if (cut > 0) url.substring(0, cut) + "?<redacted>" else url
    }

    // ---- 百度网盘连接状态 ----

    /** 百度网盘连接状态 */
    sealed class BaiduConnectionState {
        object Off : BaiduConnectionState()           // 未开启或未登录
        object Connecting : BaiduConnectionState()     // 设备码轮询中
        object LoggedIn : BaiduConnectionState()      // 已登录
        object DirMissing : BaiduConnectionState()    // 已登录但音乐根目录不存在，需重新设置
        /**
         * M8 修复（2026-10-06，代码审查报告 §4）：新增结构化 [reason]——此前
         * BaiduAuthDialog 用 `message.contains("拒绝"/"超时"/...)` 文案哨兵区分失败
         * 阶段，而 message 本身来自已本地化的 getString(...)，EN locale 下哨兵必然
         * 落空、一律退化为兜底文案。reason 不依赖文案，可自由本地化。
         */
        data class Failed(
            val message: String,
            val reason: FailReason = FailReason.UNKNOWN
        ) : BaiduConnectionState()

        /** 失败原因的结构化标记（文案只作展示，判定一律用 reason） */
        enum class FailReason { SCOPE_MISSING, DECLINED, TIMEOUT, DEVICE_CODE_FAILED, UNKNOWN }
    }

    private val _baiduConnectionState = MutableStateFlow<BaiduConnectionState>(BaiduConnectionState.Off)
    val baiduConnectionState: StateFlow<BaiduConnectionState> = _baiduConnectionState.asStateFlow()

    /** 设备码授权结果（供对话框显示） */
    private val _baiduDeviceCode = MutableStateFlow<BaiduOAuthClient.DeviceCodeResult?>(null)
    val baiduDeviceCode: StateFlow<BaiduOAuthClient.DeviceCodeResult?> = _baiduDeviceCode.asStateFlow()

    /** 网盘目录浏览 */
    private val _netdiskCurrentDir = MutableStateFlow(BaiduNetdiskConfig.APP_DIR)
    val netdiskCurrentDir: StateFlow<String> = _netdiskCurrentDir.asStateFlow()
    private val _netdiskDirFiles = MutableStateFlow<List<BaiduFile>>(emptyList())
    val netdiskDirFiles: StateFlow<List<BaiduFile>> = _netdiskDirFiles.asStateFlow()
    /** 网盘根目录是否已从配置同步过（防止 refreshBaiduConnectionState 每次重置浏览位置） */
    private var netdiskDirSynced = false
    private val _netdiskIsLoading = MutableStateFlow(false)
    val netdiskIsLoading: StateFlow<Boolean> = _netdiskIsLoading.asStateFlow()

    /**
     * 目录浏览是否还有下一页（C37）。
     *
     * 百度 `list` 接口每页 `PAGE_SIZE`(=1000) 条，原实现只取首页就把 `hasMore` 丢掉了
     * ⇒ 超过 1000 项的子目录在浏览页里看不到第 1001 项之后的内容，也没有任何"还有更多"的提示。
     * 这里把 `hasMore` 暴露给 UI，由 [loadMoreBaiduDir] 按 `start` 继续翻页。
     */
    private val _netdiskHasMore = MutableStateFlow(false)
    val netdiskHasMore: StateFlow<Boolean> = _netdiskHasMore.asStateFlow()

    /**
     * 触底追加用的独立 loading 位（C37）。
     *
     * ⛔ 不要复用 [_netdiskIsLoading]：那个位被 UI 用来"整页替换成加载中"，
     * 追加下一页时整页闪空会丢掉用户当前滚动位置。
     */
    private val _netdiskLoadingMore = MutableStateFlow(false)
    val netdiskLoadingMore: StateFlow<Boolean> = _netdiskLoadingMore.asStateFlow()

    /**
     * 目录浏览已经取到的服务端偏移量（C37），即下一页的 `start` 参数。
     *
     * 用「已收到的条数」而非「页号 × PAGE_SIZE」推进：服务端过滤后单页条数可能小于 `limit`，
     * 用页号乘法会跳过条目。
     */
    private var netdiskDirOffset: Int = 0

    /** 网盘搜索 */
    private val _netdiskSearchResults = MutableStateFlow<List<Song>>(emptyList())
    val netdiskSearchResults: StateFlow<List<Song>> = _netdiskSearchResults.asStateFlow()
    private val _netdiskSearchKeyword = MutableStateFlow("")
    val netdiskSearchKeyword: StateFlow<String> = _netdiskSearchKeyword.asStateFlow()

    /** 索引状态 */
    private val _baiduIndexScanned = MutableStateFlow(0)
    val baiduIndexScanned: StateFlow<Int> = _baiduIndexScanned.asStateFlow()
    private val _baiduIndexScanning = MutableStateFlow(false)
    val baiduIndexScanning: StateFlow<Boolean> = _baiduIndexScanning.asStateFlow()
    private val _baiduIndexLastSync = MutableStateFlow(0L)
    val baiduIndexLastSync: StateFlow<Long> = _baiduIndexLastSync.asStateFlow()

    // APIC 后台提取进度
    private val _baiduApicExtracting = MutableStateFlow(false)
    val baiduApicExtracting: StateFlow<Boolean> = _baiduApicExtracting.asStateFlow()
    private val _baiduApicExtracted = MutableStateFlow(0)
    val baiduApicExtracted: StateFlow<Int> = _baiduApicExtracted.asStateFlow()
    private val _baiduApicTotal = MutableStateFlow(0)
    val baiduApicTotal: StateFlow<Int> = _baiduApicTotal.asStateFlow()

    /** API 错误回调注册（由 MainViewModel 在构造期注入 errno=-6 分支） */
    var onBaiduApiError: ((Int, String) -> Unit)? = null

/** 认证失败（errno=-6）：设 Failed 状态（由 MainViewModel 的 onApiError 回调转发） */
    fun onBaiduAuthFailed(desc: String) {
        _baiduConnectionState.value = BaiduConnectionState.Failed(desc, BaiduConnectionState.FailReason.SCOPE_MISSING)
    }

    private var deviceCodePollJob: kotlinx.coroutines.Job? = null

    /**
     * 网盘浏览/搜索的**单飞**守卫（M31）。
     *
     * `listBaiduDir` 与 `searchBaidu` 共用 `_netdiskIsLoading` 这一个 loading 位，
     * 而它们各自 `viewModelScope.launch` 且**不保存 Job**：
     * ① 连点两个目录 ⇒ 两个请求并行，先发的后返回会把新目录的文件覆盖回旧目录；
     * ② 先到的请求先执行 `finally { isLoading = false }` ⇒ 后到的请求还在飞，
     *    转圈却已经停了。
     * 现在：领号 + 取消上一个 + 只有「还是最新世代」才写状态/复位 loading。
     */
    private var netdiskJob: kotlinx.coroutines.Job? = null
    private var netdiskLoadMoreJob: kotlinx.coroutines.Job? = null
    private var netdiskGeneration: Int = 0

    /** 关源时立即使在途请求失效，并清空网盘页的可操作状态。 */
    fun onBaiduSourceDisabled() {
        netdiskGeneration++
        netdiskJob?.cancel()
        netdiskLoadMoreJob?.cancel()
        netdiskJob = null
        netdiskLoadMoreJob = null
        netdiskDirOffset = 0
        _netdiskDirFiles.value = emptyList()
        _netdiskSearchKeyword.value = ""
        _netdiskSearchResults.value = emptyList()
        _netdiskIsLoading.value = false
        _netdiskLoadingMore.value = false
        _netdiskHasMore.value = false
        _baiduConnectionState.value = BaiduConnectionState.Off
    }

    /** 异步刷新连接状态（初始化与开关切换后调用；T2 第一批：getBaiduConfigSync → baiduConfigFlow.first()） */
    suspend fun refreshBaiduConnectionState() {
        val cfg = prefs.baidu.baiduConfigFlow.first()
        val prevState = _baiduConnectionState.value
        _baiduConnectionState.value = when {
            !cfg.isActive -> BaiduConnectionState.Off
            // 当前正在验证、已失败或目录缺失时保留，不被 "tokens 存在" 覆盖回 LoggedIn
            prevState is BaiduConnectionState.Connecting -> prevState
            prevState is BaiduConnectionState.Failed -> prevState
            prevState is BaiduConnectionState.DirMissing -> prevState
            // 有 token 但未验证时，先设 Connecting 再异步验证，不直接设 LoggedIn
            cfg.tokens != null -> BaiduConnectionState.Connecting
            else -> BaiduConnectionState.Off
        }
        AppLog.d("BaiduAuth", "refreshBaiduConnectionState: isActive=${cfg.isActive}, hasTokens=${cfg.tokens != null}, $prevState -> ${_baiduConnectionState.value}")
        if (cfg.isActive) {
            // 仅首次启用/登录时同步根目录到配置值；之后保留用户浏览位置，切换页面不重置
            if (!netdiskDirSynced) {
                _netdiskCurrentDir.value = cfg.musicRootDir.ifBlank { BaiduNetdiskConfig.APP_DIR }
                netdiskDirSynced = true
            }
            _baiduIndexLastSync.value = baiduIndexCache.load()?.lastSyncAt ?: 0L
            // 有 token 且当前是 Connecting（刚从 Off/LoggedIn 转来）→ 异步验证
            if (cfg.tokens != null && _baiduConnectionState.value is BaiduConnectionState.Connecting) {
                verifyBaiduTokenAsync()
            }
        }
        // 百度连接状态变化可能影响合并数据（启用/停用百度源）
        onMergedDataInvalidated?.invoke()
        // 通知 NasMusicApp 运行时注册/注销百度 service
        nasMusicApp.refreshBaiduServiceRegistration()
    }

    /** 异步验证百度 token 有效性：调 listDir(APP_DIR) 检查 errno */
    private fun verifyBaiduTokenAsync() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val result = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                    if (result.errno == -9) {
                        // APP_DIR 不存在，尝试自动创建
                        AppLog.i("BaiduAuth", "verifyBaiduTokenAsync: APP_DIR not found (errno=-9), auto-creating...")
                        val createErrno = baiduApi.createDir(BaiduNetdiskConfig.APP_DIR)
                        if (createErrno == 0 || createErrno == -8) {
                            AppLog.i("BaiduAuth", "verifyBaiduTokenAsync: createDir returned errno=$createErrno, retrying listDir...")
                            val retryResult = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                            if (retryResult.errno != 0) {
                                val desc = BaiduNetdiskConfig.describeErrno(getApplication(), retryResult.errno)
                                _baiduConnectionState.value = BaiduConnectionState.Failed(desc, BaiduConnectionState.FailReason.SCOPE_MISSING)
                                AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: retry after create failed errno=${retryResult.errno}")
                            } else {
                                // APP_DIR 创建成功，检查用户音乐根目录是否存在
                                checkMusicRootDirAfterVerify()
                            }
                        } else {
                            val desc = getApplication<Application>().getString(R.string.baidu_dir_create_failed, createErrno)
                            _baiduConnectionState.value = BaiduConnectionState.Failed(desc, BaiduConnectionState.FailReason.SCOPE_MISSING)
                            AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: createDir failed errno=$createErrno")
                        }
} else if (result.errno == -6) {
                        // access_token 无效
                        val desc = BaiduNetdiskConfig.describeErrno(getApplication(), result.errno)
                        _baiduConnectionState.value = BaiduConnectionState.Failed(desc, BaiduConnectionState.FailReason.SCOPE_MISSING)
                        AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: errno=-6 (auth failed), set state=Failed")
                    } else if (result.errno != 0) {
                        // 其他 API 错误（非认证），token 本身有效，按登录处理
                        val desc = BaiduNetdiskConfig.describeErrno(getApplication(), result.errno)
                        AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: errno=${result.errno} ($desc), not auth-related, treating as logged in")
                        checkMusicRootDirAfterVerify()
                    } else {
                        // APP_DIR 存在，检查用户音乐根目录
                        checkMusicRootDirAfterVerify()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                    AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: network error, fallback to LoggedIn: ${e.message}")
                    triggerBaiduIndexScanIfNeeded()
                }
            }
        }
    }

    /** 验证 token 有效后，检查用户配置的音乐根目录是否存在（T2 第二批：suspend，读取不再阻塞） */
    private suspend fun checkMusicRootDirAfterVerify() {
        val musicRoot = prefs.baidu.getBaiduMusicRootDir()
        // 如果音乐根目录就是 APP_DIR 本身，不需要额外检查
        if (musicRoot == BaiduNetdiskConfig.APP_DIR) {
            onVerifyBaiduSuccess()
            return
        }
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val result = baiduApi.listDir(musicRoot)
                    if (result.errno == -9) {
                        // 音乐根目录不存在：auth 没问题，但目录需要重新设置
                        AppLog.i("BaiduAuth", "checkMusicRootDirAfterVerify: musicRootDir='$musicRoot' not found (errno=-9), set state=DirMissing")
                        _baiduConnectionState.value = BaiduConnectionState.DirMissing
                    } else {
                        onVerifyBaiduSuccess()
                    }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    // 网络错误不影响判定，按登录处理
                    AppLog.w("BaiduAuth", "checkMusicRootDirAfterVerify: network error, fallback to LoggedIn: ${e.message}")
                    onVerifyBaiduSuccess()
                }
            }
        }
    }

    /** 百度验证成功后：设 LoggedIn + 修正旧根目录 + 触发索引扫描（T2 第二批：suspend） */
    private suspend fun onVerifyBaiduSuccess() {
        _baiduConnectionState.value = BaiduConnectionState.LoggedIn
        AppLog.d("BaiduAuth", "onVerifyBaiduSuccess: set state=LoggedIn")
        // 沙箱策略修正：如果用户保存的根目录不在 /apps/NASMusicTV 下，自动修正
        val savedRoot = prefs.baidu.getBaiduMusicRootDir()
        if (!savedRoot.startsWith(BaiduNetdiskConfig.APP_DIR)) {
            AppLog.i("BaiduAuth", "onVerifyBaiduSuccess: musicRootDir='$savedRoot' outside sandbox, resetting to ${BaiduNetdiskConfig.APP_DIR}")
            prefs.baidu.setBaiduMusicRootDir(BaiduNetdiskConfig.APP_DIR)
            _netdiskCurrentDir.value = BaiduNetdiskConfig.APP_DIR
        }
        triggerBaiduIndexScanIfNeeded()
    }

    /** 启动设备码授权流程：请求设备码并开始轮询 */
    fun startBaiduDeviceCodeFlow() {
        AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: called, current state=${_baiduConnectionState.value}")
        viewModelScope.launch {
            _baiduConnectionState.value = BaiduConnectionState.Connecting
            AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: set state=Connecting, requesting device code...")
            val code = baiduOAuth.requestDeviceCode()
            if (code == null) {
                AppLog.w("BaiduAuth", "startBaiduDeviceCodeFlow: requestDeviceCode returned null")
                _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_get_device_code_failed), BaiduConnectionState.FailReason.DEVICE_CODE_FAILED)
                return@launch
            }
            AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: got device code=${code.userCode}, expiresIn=${code.expiresIn}s, starting poll")
            _baiduDeviceCode.value = code
            pollDeviceCode(code)
        }
    }

    /** 取消设备码轮询 */
    fun cancelBaiduDeviceCode() {
        AppLog.d("BaiduAuth", "cancelBaiduDeviceCode: called, current state=${_baiduConnectionState.value}")
        deviceCodePollJob?.cancel()
        deviceCodePollJob = null
        _baiduDeviceCode.value = null
        if (_baiduConnectionState.value is BaiduConnectionState.Connecting) {
            _baiduConnectionState.value = BaiduConnectionState.Off
            AppLog.d("BaiduAuth", "cancelBaiduDeviceCode: was Connecting, set state=Off")
        }
    }

    private fun pollDeviceCode(code: BaiduOAuthClient.DeviceCodeResult) {
        deviceCodePollJob?.cancel()
        deviceCodePollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            AppLog.d("BaiduAuth", "pollDeviceCode: start, deadline=${code.expiresIn}s, interval=${code.interval}s")
            while (System.currentTimeMillis() < deadline && isActive()) {
                when (val r = baiduOAuth.pollDeviceToken(code.deviceCode)) {
                    is BaiduOAuthClient.PollResult.Success -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Success, verifying token before setting LoggedIn...")
                        _baiduDeviceCode.value = null
                        // 先设 Connecting，异步验证 token 后再决定 LoggedIn/Failed
                        _baiduConnectionState.value = BaiduConnectionState.Connecting
                        nasMusicApp.refreshBaiduServiceRegistration()
                        verifyBaiduTokenAsync()
                        return@launch
                    }
                    BaiduOAuthClient.PollResult.Pending -> {
                        kotlinx.coroutines.delay(interval)
                    }
                    BaiduOAuthClient.PollResult.Declined -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Declined -> Failed")
                        _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_user_declined), BaiduConnectionState.FailReason.DECLINED)
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                    is BaiduOAuthClient.PollResult.SlowDown -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: SlowDown, newInterval=${r.newInterval}s")
                        interval = r.newInterval * 1000L
                        kotlinx.coroutines.delay(interval)
                    }
                    is BaiduOAuthClient.PollResult.Failed -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Failed -> ${r.message}")
                        _baiduConnectionState.value = BaiduConnectionState.Failed(r.message)
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                }
            }
            AppLog.d("BaiduAuth", "pollDeviceCode: timeout -> Failed")
            _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_auth_timeout), BaiduConnectionState.FailReason.TIMEOUT)
            _baiduDeviceCode.value = null
        }
    }

    private suspend fun isActive(): Boolean =
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /** 登出 */
    fun logoutBaidu() {
        AppLog.d("BaiduAuth", "logoutBaidu: called, current state=${_baiduConnectionState.value}")
        viewModelScope.launch {
            baiduOAuth.logout()
            nasMusicApp.refreshBaiduServiceRegistration()
            _baiduConnectionState.value = BaiduConnectionState.Off
            AppLog.d("BaiduAuth", "logoutBaidu: set state=Off")
        }
    }

    // ---- 网盘目录浏览 ----

    fun listBaiduDir(dir: String) {
        if (!isBaiduSourceActive) {
            onBaiduSourceDisabled()
            return
        }
        AppLog.d("BaiduAuth", "listBaiduDir: dir=$dir, current state=${_baiduConnectionState.value}")
        val generation = ++netdiskGeneration
        netdiskJob?.cancel()
        _netdiskCurrentDir.value = dir
        _netdiskIsLoading.value = true
        // C37：换目录即重置分页游标与"还有更多"，否则新目录会带着旧目录的 offset 去翻页
        netdiskDirOffset = 0
        _netdiskHasMore.value = false
        _netdiskLoadingMore.value = false
        netdiskJob = viewModelScope.launch {
            try {
                if (!isBaiduSourceActive) {
                    _netdiskDirFiles.value = emptyList()
                    _netdiskHasMore.value = false
                    _baiduConnectionState.value = BaiduConnectionState.Off
                    return@launch
                }
                val result = baiduApi.listDir(dir)
                AppLog.d("BaiduAuth", "listBaiduDir: got ${result.files.size} files, hasMore=${result.hasMore}, errno=${result.errno}")
                if (generation != netdiskGeneration) return@launch
                // API 返回错误时设置 Failed 状态（不再依赖回调）
                if (result.errno != 0) {
                    val desc = BaiduNetdiskConfig.describeErrno(getApplication(), result.errno)
                    AppLog.w("BaiduAuth", "listBaiduDir: errno=${result.errno} ($desc), setting state=Failed")
                    _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                }
                _netdiskDirFiles.value = result.files
                netdiskDirOffset = result.files.size
                _netdiskHasMore.value = result.hasMore
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "listBaiduDir error", e)
                if (generation != netdiskGeneration) return@launch
                showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_load_dir_error, e.message?.take(40)))
                _netdiskDirFiles.value = emptyList()
                netdiskDirOffset = 0
                _netdiskHasMore.value = false
            } finally {
                // M31：只有最新世代才能关掉转圈（旧请求的 finally 会把新请求的 loading 关掉）
                if (generation == netdiskGeneration) _netdiskIsLoading.value = false
            }
        }
    }

    /**
     * 触底追加目录下一页（C37）。
     *
     * 复用 [netdiskGeneration] 而不是另起世代：翻页途中若用户切了目录/发起搜索，
     * 那个动作会 `++netdiskGeneration`，本次追加在写回前就会被判定过期并丢弃，
     * 不会把旧目录的文件拼到新目录列表尾部。
     *
     * 停滞保护：`hasMore=true` 却返回 0 条时直接收敛为 `false`，否则触底会无限重试同一个 `start`。
     */
    fun loadMoreBaiduDir() {
        if (!isBaiduSourceActive) {
            onBaiduSourceDisabled()
            return
        }
        if (!_netdiskHasMore.value || _netdiskLoadingMore.value || _netdiskIsLoading.value) return
        val dir = _netdiskCurrentDir.value
        val generation = netdiskGeneration
        _netdiskLoadingMore.value = true
        netdiskLoadMoreJob = viewModelScope.launch {
            try {
                if (!isBaiduSourceActive) {
                    _netdiskHasMore.value = false
                    return@launch
                }
                val result = baiduApi.listDir(dir, start = netdiskDirOffset)
                if (generation != netdiskGeneration) return@launch
                if (result.errno != 0) {
                    val desc = BaiduNetdiskConfig.describeErrno(getApplication(), result.errno)
                    AppLog.w("BaiduAuth", "loadMoreBaiduDir: errno=${result.errno} ($desc), stop paging")
                    _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                    _netdiskHasMore.value = false
                    return@launch
                }
                if (result.files.isEmpty()) {
                    AppLog.w("BaiduAuth", "loadMoreBaiduDir: empty page at start=$netdiskDirOffset, stop paging")
                    _netdiskHasMore.value = false
                    return@launch
                }
                netdiskDirOffset += result.files.size
                // 去重：LazyVerticalGrid 以 fsId 为 key，重复 key 会直接抛异常
                _netdiskDirFiles.value = (_netdiskDirFiles.value + result.files).distinctBy { it.fsId }
                _netdiskHasMore.value = result.hasMore
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "loadMoreBaiduDir error", e)
                if (generation != netdiskGeneration) return@launch
                // 追加失败不动已有列表，只是停止继续翻页，避免每帧重试打爆接口
                _netdiskHasMore.value = false
            } finally {
                if (generation == netdiskGeneration) _netdiskLoadingMore.value = false
            }
        }
    }

    /**
     * 列出网盘指定路径下的文件（供 [com.nasmusic.tv.ui.components.BaiduDirPickerDialog] 目录树选择使用）。
     * 异常直接上抛，由对话框展示失败态并允许重试。
     */
    suspend fun listBaiduDirs(path: String): List<BaiduFile> =
        if (isBaiduSourceActive) baiduApi.listDir(path).files else emptyList()

    fun navigateBaiduDirUp() {
        val current = _netdiskCurrentDir.value
        if (current == "/" || current.isBlank()) return
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        listBaiduDir(parent)
    }

    fun enterBaiduDir(name: String) {
        val base = _netdiskCurrentDir.value.trimEnd('/')
        listBaiduDir("$base/$name")
    }

    // ---- 网盘搜索 ----

    fun searchBaidu(keyword: String) {
        _netdiskSearchKeyword.value = keyword
        if (keyword.isBlank()) {
            _netdiskSearchResults.value = emptyList()
            return
        }
        if (!isBaiduSourceActive) {
            onBaiduSourceDisabled()
            return
        }
        val generation = ++netdiskGeneration
        netdiskJob?.cancel()
        netdiskJob = viewModelScope.launch {
            if (!isBaiduSourceActive) return@launch
            _netdiskIsLoading.value = true
            // 本地命中要在 catch 里继续可用（服务端失败时不清掉它），故声明在 try 之外
            var localHits: List<Song> = emptyList()
            try {
                val rootDir = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
                // 拼音/首字母输入先查本地索引：`searchAudio` 是服务端字面量匹配，不认拼音，
                // 否则在网盘页搜 `zjl` 恒 0 条（本地索引明明有周杰伦）。
                // 3.8 万条逐条拼音匹配是 CPU 密集活 ⇒ 必须离主线程（同 SearchViewModel 口径）。
                // ⚠️ 按 `rootDir` 前缀过滤：索引只覆盖建索引时的音乐根目录，与服务端 `dir = rootDir`
                //    的范围对齐，避免用户改过根目录后冒出目录外的歌。
                localHits = if (SearchQuery.isPinyinLike(keyword)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        baiduIndexCache.searchSongs(keyword, pinyinMatch = true, limit = NETDISK_SEARCH_LIMIT)
                            .filter { it.path?.startsWith(rootDir) == true }
                    }
                } else {
                    emptyList()
                }
                // 本地先出结果（索引命中通常毫秒级），服务端回来再合并补漏
                if (localHits.isNotEmpty() && generation == netdiskGeneration) {
                    _netdiskSearchResults.value = localHits
                }
                val remote = baiduApi.searchAudio(keyword, dir = rootDir).map { it.toSong() }
                // M31：旧搜索不得覆盖新搜索的结果
                if (generation != netdiskGeneration) return@launch
                _netdiskSearchResults.value = mergeNetdiskSearchResults(localHits, remote)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "searchBaidu error", e)
                if (generation != netdiskGeneration) return@launch
                // 服务端失败时保留本地命中；原本一律置空 ⇒ 白算一遍索引
                _netdiskSearchResults.value = localHits
            } finally {
                if (generation == netdiskGeneration) _netdiskIsLoading.value = false
            }
        }
    }

    /** 本地索引命中 + 服务端命中，按 Song.id（`ntwk_baidu_<fsId>`）去重，本地在前 */
    private fun mergeNetdiskSearchResults(local: List<Song>, remote: List<Song>): List<Song> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local
        val seen = mutableSetOf<String>()
        return (local + remote).filter { seen.add(it.id) }
    }

    fun clearNetdiskSearch() {
        _netdiskSearchKeyword.value = ""
        _netdiskSearchResults.value = emptyList()
    }

    /** 播放全部网盘搜索结果 */
    fun playAllNetdiskSearch() {
        if (!isBaiduSourceActive) {
            showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_status_baidu_not_logged_in))
            return
        }
        val results = _netdiskSearchResults.value
        if (results.isEmpty()) {
            showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_search_no_results))
            return
        }
        onPlayQueue?.invoke(results, 0)
    }

    /**
     * 播放当前目录（含子目录）的全部音频。
     *
     * 优先走本地索引（毫秒级）；索引未覆盖/缺失时回退递归 BFS 扫描网盘。
     *
     * ⭐ 音乐源总开关（方案 §3.2.1 第 20 项 / §5.2 通路 D）：百度源未生效
     * （未启用或未登录）⇒ **不请求、不播放**，直接返回。
     * 为什么必须在这里挡：百度共**四条**通路（方案 §5.2），A（`NetworkMusicManager.search`
     * 走 services map）已随 `unregisterService` 失效，B（`SearchAggregator`）与
     * C（`baiduIndexCache` 本地索引）在别的 lane 收口，**唯独本条（网盘目录播放）此前完全没被门控**。
     * @param onPlayAll 收集完成后回调（播放队列由调用方导航到 NowPlaying）
     */
    fun playAllNetdiskDir(dir: String, onPlayAll: (List<Song>) -> Unit) {
        if (!isBaiduSourceActive) {
            AppLog.i("NetworkMusicViewModel", "playAllNetdiskDir: 百度源未生效（未启用或未登录），已跳过播放")
            showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_status_baidu_not_logged_in))
            return
        }
        val generation = netdiskGeneration
        viewModelScope.launch {
            val songs = collectAudioInDir(dir)
            if (!isBaiduSourceActive || generation != netdiskGeneration) return@launch
            if (songs.isEmpty()) {
                showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_no_audio_files))
            } else {
                onPlayAll(songs)
            }
        }
    }

    /**
     * 收集目录（含子目录）内全部音频：优先索引，回退 API 递归扫描。
     *
     * ⭐ 与 [playAllNetdiskDir] 同判据：百度源未生效 ⇒ 直接返回空列表
     * （**连索引都不读、网盘都不递归**），保证关源后这条路一个请求都不发。
     */
    private suspend fun collectAudioInDir(dir: String): List<Song> {
        if (!isBaiduSourceActive) return emptyList()
        val base = dir.trimEnd('/')
        val index = baiduIndexCache.load()
        val indexed = index?.entries?.filter {
            it.category == BaiduNetdiskConfig.CATEGORY_AUDIO &&
                (it.path == base || it.path.startsWith("$base/"))
        }?.map { it.toSong() }
        if (indexed != null && indexed.isNotEmpty()) return indexed

        // 回退：BFS 递归扫描（索引未建或未覆盖该目录时）
        val songs = mutableListOf<Song>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.addLast(base)
        visited.add(base)
        try {
            while (queue.isNotEmpty()) {
                if (!isBaiduSourceActive) return emptyList()
                val dirPath = queue.removeFirst()
                val result = baiduApi.listDir(dirPath)
                for (f in result.files) {
                    if (f.isDir) {
                        if (visited.add(f.path)) queue.addLast(f.path)
                    } else if (BaiduPanApi.isAudioFile(f.serverFilename, f.category)) {
                        songs.add(f.toSong())
                    }
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            AppLog.w("NetworkMusicViewModel", "collectAudioInDir error", e)
        }
        return songs
    }

    // ---- 索引管理 ----

    suspend fun triggerBaiduIndexScanIfNeeded() {
        val index = baiduIndexCache.load()
        val root = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
        if (index == null || index.rootPath != root) {
            rebuildBaiduIndex()
        } else {
            _baiduIndexScanned.value = index.entries.size
            _baiduIndexLastSync.value = index.lastSyncAt
        }
    }

    fun rebuildBaiduIndex() {
        if (_baiduIndexScanning.value) return
        viewModelScope.launch {
            _baiduIndexScanning.value = true
            _baiduIndexScanned.value = 0
            val root = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
            val callback = object : BaiduFileIndexCache.ProgressCallback {
                override fun onProgress(scanned: Int) { _baiduIndexScanned.value = scanned }
                override fun onComplete(total: Int) {
                    _baiduIndexScanned.value = total
                    _baiduIndexLastSync.value = System.currentTimeMillis()
                    onMergedDataInvalidated?.invoke()
                }
                override fun onFailed(message: String) {
                    showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_index_scan_interrupted, message))
                }
            }
            try {
                val mvDir = prefs.baidu.getBaiduMvDir()
                baiduIndexCache.fullScan(root, baiduApi, mvDir, callback)
                // 扫描成功后，对 coverUrl 为空的音频条目启动 APIC 后台提取。
                // listall+web=1 返回的 thumbs 仅对图片/视频有效，音频文件几乎都为 null。
                val index = baiduIndexCache.load()
                val pendingCovers = index?.entries?.count {
                    it.coverUrl == null && it.category != BaiduNetdiskConfig.CATEGORY_VIDEO
                } ?: 0
                if (pendingCovers > 0) {
                    AppLog.i("NetworkMusicViewModel", "rebuildBaiduIndex: $pendingCovers entries pending cover extraction, starting APIC")
                    startApicExtraction()
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "rebuildBaiduIndex error", e)
            } finally {
                _baiduIndexScanning.value = false
            }
        }
    }

    /** 后台提取 APIC 封面（扫描完成后自动触发，也可手动调用） */
    private fun startApicExtraction() {
        if (_baiduApicExtracting.value) return
        viewModelScope.launch {
            _baiduApicExtracting.value = true
            _baiduApicExtracted.value = 0
            _baiduApicTotal.value = 0
            try {
                val callback = object : BaiduFileIndexCache.ApicProgressCallback {
                    override fun onProgress(extracted: Int, total: Int) {
                        _baiduApicExtracted.value = extracted
                        _baiduApicTotal.value = total
                    }
                    override fun onComplete(totalExtracted: Int) {
                        _baiduApicExtracted.value = totalExtracted
                        _baiduApicTotal.value = totalExtracted
                        if (totalExtracted > 0) onMergedDataInvalidated?.invoke()
                    }
                    override fun onFailed(message: String) {
                        AppLog.e("NetworkMusicViewModel", "APIC extraction failed: $message")
                    }
                }
                baiduIndexCache.extractApicInBackground(
                    coverProvider = nasMusicApp.baiduCoverProvider,
                    concurrency = 5,
                    batchSize = 20,
                    onProgress = callback
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("NetworkMusicViewModel", "startApicExtraction error", e)
            } finally {
                _baiduApicExtracting.value = false
            }
        }
    }

    // ---- 配置项 ----

    fun setBaiduMusicRootDir(dir: String) {
        _netdiskCurrentDir.value = dir
        viewModelScope.launch {
            prefs.baidu.setBaiduMusicRootDir(dir)
            // 根目录变更后旧索引失效，触发重建
            rebuildBaiduIndex()
            // 如果之前是 DirMissing，重新验证新目录
            if (_baiduConnectionState.value is BaiduConnectionState.DirMissing) {
                checkMusicRootDirAfterVerify()
            }
        }
    }

    fun setBaiduMvDir(dir: String?) {
        viewModelScope.launch {
            prefs.baidu.setBaiduMvDir(dir)
        }
    }

    /** 加载索引中的歌曲（供 NetdiskScreen 首页展示已扫描曲库） */
    fun loadBaiduIndexedSongs(): List<Song> =
        baiduIndexCache.load()?.entries?.map { it.toSong() } ?: emptyList()

    /** 启动期恢复百度索引状态（由 MainViewModel 构造期调用，替代原 init 逻辑） */
    fun restoreBaiduIndexOnStart(onIndexLoaded: (com.nasmusic.tv.data.model.BaiduFileIndex) -> Unit) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                // P1#12 修复（2026-09-13）：用 baiduConfigFlow.first() 替代 getBaiduConfigSync()
                // 避免 runBlocking+Dispatchers.IO 在 Default 线程池上阻塞其他解析/计算协程。
                val baiduCfg = prefs.baidu.baiduConfigFlow.first()
                AppLog.d("BaiduAuth", "init: cfg.isActive=${baiduCfg.isActive}, tokens=${baiduCfg.tokens != null}")
                if (baiduCfg.isActive) {
                    val savedIndex = baiduIndexCache.load()
                    if (savedIndex != null && savedIndex.entries.isNotEmpty()) {
                        _baiduIndexScanned.value = savedIndex.entries.size
                        _baiduIndexLastSync.value = savedIndex.lastSyncAt
                    }
                    // 有 token 时先设 Connecting（验证中），避免闪烁"已登录"再变"授权失败"
                    if (baiduCfg.tokens != null) {
                        _baiduConnectionState.value = BaiduConnectionState.Connecting
                        AppLog.d("BaiduAuth", "init: set state=Connecting, verifying token...")
                        try {
val verifyResult = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                            if (verifyResult.errno != 0) {
                                // 直接从结果读取 errno，不依赖回调
                                val desc = BaiduNetdiskConfig.describeErrno(getApplication(), verifyResult.errno)
                                val reason = if (verifyResult.errno == -6) {
                                    BaiduConnectionState.FailReason.SCOPE_MISSING
                                } else {
                                    BaiduConnectionState.FailReason.UNKNOWN
                                }
                                _baiduConnectionState.value = BaiduConnectionState.Failed(desc, reason)
                                AppLog.w("BaiduAuth", "init: verify failed errno=${verifyResult.errno}, set state=Failed($desc)")
                            } else {
                                _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                                AppLog.d("BaiduAuth", "init: verify OK, set state=LoggedIn, ${verifyResult.files.size} items in /")
                            }
                        } catch (e: Exception) {
                            e.rethrowIfCancellation()
                            // 网络异常等，保守设 LoggedIn（可能是临时网络问题，不是 token 失效）
                            _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                            AppLog.w("BaiduAuth", "init: verify network error, fallback to LoggedIn: ${e.message}")
                        }
                    } else {
                        _baiduConnectionState.value = BaiduConnectionState.Off
                        AppLog.d("BaiduAuth", "init: no tokens, set state=Off")
                    }
                    // 百度索引有数据时通知加载
                    if (savedIndex != null && savedIndex.entries.isNotEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onIndexLoaded(savedIndex)
                        }
                    }
                }
            }
        }
    }
}
