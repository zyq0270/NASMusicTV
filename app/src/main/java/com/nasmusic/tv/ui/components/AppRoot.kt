package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.ui.viewmodel.DownloadViewModel
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.data.model.HomeDashboardData
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.LocalNavigateBackHandler
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.HomeScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.screens.MineScreen

import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.screens.BackupTransferDialog
import com.nasmusic.tv.ui.screens.ModelTransferDialog
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.ScreenOrientationPref
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.components.branches.*
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.Screen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isTV = remember {
        // 与 MainActivity/NasMusicApp/TextInputDialog 一致：很多非认证 TV 盒子
        // 只上报 android.hardware.type.television 而无 leanback 特性，
        // 单查 leanback 会把 TV 误判为手机（v2.20.0 手机端支持引入的回归，
        // 曾导致 K 歌/MTV 页手机遥控二维码因 remoteControlUrl 被置 null 而消失）
        context.packageManager.hasSystemFeature("android.software.leanback") ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")
    }
    val currentScreen by viewModel.navVM.currentScreen.collectAsState(initial = Screen.Home)
    // v2.36.0 形态因子：**只有 PhonePortrait** 走竖屏新界面；TV 与手机横屏一律走现状代码路径
    // （方案 §3.1 B1 硬规则 —— 分支谓词不能写成 `== / != UiMode.TV`）
    val uiMode = LocalUiMode.current
    val isPhonePortrait = uiMode == UiMode.PhonePortrait
    // L1 全局屏幕方向策略（顶部栏 L2 按钮显示其真值 + 单击写回，方案 D1）
    val orientationPref by viewModel.prefs.display.screenOrientation.collectAsState(initial = "auto")
    val orientationScope = rememberCoroutineScope()
    // 竖屏设置二级页状态（归 navVM，BACK handler 需要读它，方案 §6.2 / K2）
    val settingsSection by viewModel.navVM.settingsSection.collectAsState(initial = null)
    // M38：顶层**只订 currentSong**。原先整体收集 playerState（queue / currentIndex /
    // currentSong 三元组），而 AppRoot 只用得到 currentSong ⇒ 往队列里加一首歌、播完一首换
    // currentIndex，都会驱动整棵 UI 树（导航栏 + 各 Screen 分支）重组。三元组本身仍保持
    // 「同帧原子发布」（见 PlayerState 的 KDoc），这里只是在消费端做投影 + 去重。
    val currentSong by remember(viewModel) {
        viewModel.playerVM.playerState
            .map { it.currentSong }
            .distinctUntilChanged()
    }.collectAsState(initial = viewModel.playerVM.playerState.value.currentSong)
    // v2.35.0 多码率：当前生效档位 = 单曲覆盖 ?: 全局默认（方案 §2.3 两级模型）
    val globalQualityTier by viewModel.prefs.player.qualityTier.collectAsState(initial = 0)
    val qualityTier = remember(currentSong, globalQualityTier) {
        currentSong?.let { viewModel.effectiveQualityFor(it) } ?: globalQualityTier
    }
    val isPlaying by viewModel.playerVM.isPlaying.collectAsState(initial = false)
    val playMode by viewModel.playerVM.playMode.collectAsState(initial = com.nasmusic.tv.data.model.PlayMode.SEQUENTIAL)
    // F-2（修复）：progress/duration 不再顶层收集——PlayerManager 的进度由 1000ms
    // Handler 轮询驱动，顶层收集会每秒驱动 AppRoot 全树重组（含 LazyColumn 状态与
    // D-Pad 焦点搜索）；下沉到 NowPlayingScreen 分支内收集（与 H-3 频谱流下沉同向）。
    val networkCoverUrl by viewModel.networkCoverUrl.collectAsState(initial = null)
    // M7 修复（2026-10-06，代码审查报告 §4）：songDownloadStates 不再顶层收集——
    // 512KB 步长的高频下载进度会驱动 AppRoot 顶层读取位置重组。已下沉到
    // Library/Mine/AlbumDetail/ArtistDetail/WeatherRadio 五个分支内自行收集。
    val songs by viewModel.songs.collectAsState(initial = UiState.Loading as UiState<List<Song>>)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)
    val isLibraryLoading by viewModel.isLibraryLoading.collectAsState(initial = false)
    val isConnected by viewModel.serverVM.isConnected.collectAsState(initial = false)
    val serverDisplayName by viewModel.serverVM.serverDisplayName.collectAsState(initial = "")
    val serverConfig by viewModel.serverConfig.collectAsState(initial = ServerConfig.Empty)
    val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
    // 封面滤镜状态（跨屏幕共享，用于 NowPlaying + Settings）
    val coverFilterEnabled by viewModel.prefs.visualizer.coverFilterEnabled.collectAsState(initial = false)
    val coverFilterBlurRadius by viewModel.prefs.visualizer.coverFilterBlurRadius.collectAsState(initial = 8f)
    val coverFilterDarkOverlay by viewModel.prefs.visualizer.coverFilterDarkOverlay.collectAsState(initial = 0.3f)

    // 加入歌单弹窗目标歌曲（提升到 AppRoot 顶层，供 Library / 专辑详情 / 艺术家详情 共用）
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    // 天气 API Key
    // 百度网盘状态（设置页网盘分区）
    // K 歌页面显隐（切 Tab 时保持，退出 K 歌页时清除）
    // MTV 页面显隐（进入 MTV 全屏页时为 true）
    val showMv by viewModel.mvVM.showMv.collectAsState(initial = false)
    // K 歌全屏页显隐（修复：BACK 键需在 K 歌页优先退出 K 歌，而非触发应用退出确认）
    val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
    // 全屏可视化舞台显隐（BACK 需在舞台页优先退出，而非触发应用退出确认）
    val showVisualizer by viewModel.visualizerVM.showVisualizer.collectAsState(initial = false)
    // MTV 搜索状态（顶层收集，供 NotFound 自动退出保护与 NowPlaying 分支共用）
    val mvState by viewModel.mvVM.mvState.collectAsState()
    // 安全兜底：切歌后新歌无 MV（NotFound）时自动退出 MTV 全屏，避免卡在无导航栏的播放页
    LaunchedEffect(mvState, showMv) {
        if (showMv && mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.NotFound) {
            viewModel.mvVM.exitMvMode()
        }
    }
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value, showMv, showKaraoke, showVisualizer, settingsSection) {
        // C-2 修正说明：初版审查把 when/if-else 分支里的 {{ ... }} 误判为 no-op（lambda 内 lambda）。
        // 实测编译行为：when/if 分支的 { } 按“块”解析，{{ X }} = 块 + 尾部 lambda 表达式，
        // 分支值就是可用的 lambda——原实现功能正常，并非 bug。此处改用具名 lambda 仅作可读性清理。
        val navigateHome: () -> Unit = { viewModel.navVM.navigateTo(Screen.Home) }
        val exitImmersive: () -> Unit = { isImmersiveMode.value = false }
        val exitMv: () -> Unit = { viewModel.mvVM.exitMvMode() }
        val exitKaraoke: () -> Unit = { viewModel.vocalVM.exitKaraoke() }
        val exitVisualizer: () -> Unit = { viewModel.visualizerVM.exitVisualizer() }
        val navSettings: () -> Unit = { viewModel.navVM.navigateTo(Screen.Settings) }
        val navigateMine: () -> Unit = { viewModel.navVM.navigateTo(Screen.Mine) }
        val closeSettingsSection: () -> Unit = { viewModel.navVM.closeSettingsSection() }
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> exitImmersive
            // 全屏可视化舞台：BACK 退出舞台，而非应用退出确认
            showVisualizer -> exitVisualizer
            // K 歌页：BACK 退出 K 歌（切回普通 NOW PLAYING），而非应用退出确认
            showKaraoke -> exitKaraoke
            showMv -> exitMv
            // v2.36.0 竖屏设置二级页：BACK 回设置列表（**必须放在 Screen.Settings 之前**，方案 §6.2）
            currentScreen == Screen.Settings && settingsSection != null -> closeSettingsSection
            currentScreen == Screen.NowPlaying -> if (isTV) null else navigateHome
            currentScreen == Screen.Home -> null
            // ── 设置域子页面：BACK 返回设置主菜单（入口唯一：SettingsBranch）──
            // 修复：均衡器/播放统计此前落入 else 回首页，遥控器返回键无法回到设置
            currentScreen == Screen.ServerConnect -> navSettings
            currentScreen == Screen.Equalizer -> navSettings
            currentScreen == Screen.PlayStats -> navSettings
            // ── "我的"域子页面：BACK 返回我的页（网盘 / 服务端歌单入口唯一：MineBranch）──
            // WeatherRadio 入口在首页，由 else 回首页覆盖，无需专门分支
            currentScreen == Screen.Netdisk -> navigateMine
            currentScreen == Screen.PlaylistManagement -> navigateMine
            else -> navigateHome
        }
        navBackHandler.value = handler
    }

    // v2.36.0 D10：**不加任何过渡**（裸 Column，硬切）—— 引入 AnimatedContent/Crossfade
    // 会在过渡期同时组合新旧子树，带回 B3 的三个副作用（handler 置空 / 重复拉数据 / 滚动位丢失）。
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏（沉浸模式 / MTV、K 歌、可视化全屏页时隐藏）
        // ⚠️ v1.4 B1 硬规则：只有 PhonePortrait 用新栏；TV 与手机横屏都走现状（TvTopNavBar）
        // ⚠️ K 歌页虽不是 AppRoot 覆盖层（是 NowPlayingScreen 内部分支），但它是强制横屏的
        //    全屏页 —— 条件里必须含 showKaraoke，否则 TV / 手机横屏都会在歌词上方留一条导航栏。
        if (!isImmersiveMode.value && !showMv && !showKaraoke && !showVisualizer) {
            if (isPhonePortrait) {
                // D9：竖屏显示系统栏 → statusBarsPadding()/displayCutoutPadding() 生效
                PhoneTopBar(
                    orientationPref = orientationPref,
                    onToggleOrientation = {
                        // D1：单击在「竖屏 ⟷ 横屏」二态间循环 + 立即写 pref（无长按，D8）
                        val next = ScreenOrientationPref.nextOnToggle(orientationPref)
                        orientationScope.launch { viewModel.prefs.display.setScreenOrientation(next) }
                    },
                    onNavigateToSearch = {
                        // 与 HomeBranch / NowPlayingBranch 的「搜索」按钮行为一致
                        viewModel.selectLibraryTab(LibraryTab.SEARCH)
                        viewModel.navVM.navigateTo(Screen.Library)
                    },
                    onNavigateToSettings = {
                        // v2.36.1：设置入口从底部导航（PhoneNavBar）上移到顶栏齿轮按钮，
                        // 底部导航只剩 5 个主功能。
                        // ⚠️ `navigateTo` **只改 Screen，不清 `settingsSection`**（见 NavigationViewModel）：
                        // 若用户此前进过某个设置二级页（如「通用设置」），状态会一直留着，
                        // 此时点齿轮会「看起来没反应」（仍停在那个二级页）→ 必须显式先关掉二级页。
                        // ✅ 不影响 TV：`settingsSection` 只在竖屏两级设置里被写入，TV 端恒为 null，
                        // 清一次是无副作用的空操作（TV 顶部导航的「设置」项因此无需这一步）。
                        viewModel.navVM.closeSettingsSection()
                        viewModel.navVM.navigateTo(Screen.Settings)
                    }
                )
            } else {
                TvTopNavBar(
                    currentScreen = currentScreen,
                    onNavigate = { viewModel.navVM.navigateTo(it) },
                    // ⚠️ 仅手机横屏下发方向按钮：横屏走 TV 布局后 PhoneTopBar 不再渲染，
                    // 若不补一个，用户从竖屏切进横屏后就再也切不回竖屏（只能靠系统旋转）。
                    // ⛔ 显式判 PhoneLandscape 而非 `!= PhonePortrait` —— 后者会让 TV 也长出按钮。
                    showOrientationToggle = uiMode == UiMode.PhoneLandscape,
                    orientationPref = orientationPref,
                    onToggleOrientation = {
                        // 与 PhoneTopBar 同一套语义：竖/横二态循环 + 立即写 pref（D1 / D8）
                        val next = ScreenOrientationPref.nextOnToggle(orientationPref)
                        orientationScope.launch { viewModel.prefs.display.setScreenOrientation(next) }
                    },
                )
            }
        }

        // 内容区域
        // 封面候选列表（跨屏幕复用，确保首页和 NowPlaying 使用相同的封面解析逻辑）
        val coverCandidates = remember(currentSong?.id, networkCoverUrl) {
            currentSong?.let { viewModel.getCoverCandidates(it) } ?: emptyList()
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (currentScreen) {
                Screen.Home -> HomeBranch(
                    viewModel = viewModel,
                    isConnected = isConnected,
                    isLibraryLoading = isLibraryLoading,
                    serverDisplayName = serverDisplayName,
                    currentSong = currentSong,
                    coverCandidates = coverCandidates
                )
                Screen.NowPlaying -> {
                    val nowPlaying: @Composable () -> Unit = {
                        NowPlayingBranch(
                            viewModel = viewModel,
                            isTV = isTV,
                            isImmersiveMode = isImmersiveMode,
                            currentSong = currentSong,
                            // v2.35.0 多码率：当前生效档位（单曲覆盖 ?: 全局默认，方案 §5.1）
                            qualityTier = qualityTier,
                            isPlaying = isPlaying,
                            playMode = playMode,
                            coverCandidates = coverCandidates,
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            settings = settings,
                            showMv = showMv,
                            mvState = mvState,
                            isConnected = isConnected
                        )
                    }
                    // 沉浸播放页是纯黑画布（封面下沿渐黑 + 歌词区纯黑）⇒ 强制深色调色板，
                    // 否则浅色主题的深色文字/深青主色会压在黑底上不可读。
                    if (isImmersiveMode.value) {
                        com.nasmusic.tv.ui.theme.ForceDarkPalette(nowPlaying)
                    } else {
                        nowPlaying()
                    }
                }
                Screen.Library -> LibraryBranch(
                    viewModel = viewModel,
                    songsState = songs,
                    isLoading = isLoading,
                    isLibraryLoading = isLibraryLoading,
                    isConnected = isConnected,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.Mine -> MineBranch(
                    viewModel = viewModel,
                )
                Screen.Queue -> QueueBranch(
                    viewModel = viewModel,
                    currentSong = currentSong,
                    coverCandidates = coverCandidates,
                    isPlaying = isPlaying,
                    playMode = playMode
                )
                Screen.Settings -> SettingsBranch(
                    viewModel = viewModel,
                    settings = settings,
                    isLoading = isLoading,
                    isConnected = isConnected,
                    serverDisplayName = serverDisplayName,
                    serverConfig = serverConfig,
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    context = context,
                    coroutineScope = coroutineScope,
                    onConnect = onConnect,
                )
                Screen.ServerConnect -> ServerConnectBranch(
                    viewModel = viewModel,
                    serverConfig = serverConfig,
                    isConnected = isConnected,
                    serverDisplayName = serverDisplayName,
                    isLoading = isLoading,
                    onConnect = onConnect
                )
                Screen.AlbumDetail -> AlbumDetailBranch(
                    viewModel = viewModel,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.ArtistDetail -> ArtistDetailBranch(
                    viewModel = viewModel,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.Equalizer -> EqualizerBranch(
                    viewModel = viewModel,
                    settings = settings
                )
                Screen.PlaylistManagement -> PlaylistManagementBranch(
                    viewModel = viewModel
                )
                Screen.Netdisk -> NetdiskBranch(
                    viewModel = viewModel
                )
                Screen.WeatherRadio -> WeatherRadioBranch(
                    viewModel = viewModel,
                    onPickSongForPlaylist = { song -> pickerSong = song }
                )
                Screen.PlayStats -> PlayStatsBranch(
                    viewModel = viewModel
                )
            }
        }

        // 手机竖屏底部：MiniPlayer + 底部导航（方案 §4.0 / §8.6）
        // ⚠️ 位置必须在 Box(weight(1f)) 之后、VisualizerOverlay 之前 —— 这样可视化舞台仍能盖住底部栏
        // ⚠️ K 歌页不是 AppRoot 覆盖层（是 NowPlayingScreen 内部分支），故条件里必须含 showKaraoke
        if (isPhonePortrait &&
            !isImmersiveMode.value && !showMv && !showKaraoke && !showVisualizer
        ) {
            // M38：currentSong 是 `by` 委托属性（collectAsState 的 State 委托），
            // 直接传参过不了智能转换（"Smart cast to 'Song' is impossible"）⇒ 先取本地 val。
            val miniPlayerSong = currentSong
            if (currentScreen != Screen.NowPlaying && miniPlayerSong != null) {
                // 播放页自身即播放器，不重复显示 MiniPlayer
                MiniPlayer(
                    song = miniPlayerSong,
                    coverCandidates = coverCandidates,
                    progressFlow = viewModel.playerVM.progress,
                    durationFlow = viewModel.playerVM.duration,
                    isPlaying = isPlaying,
                    onExpand = { viewModel.navVM.navigateTo(Screen.NowPlaying) },
                    onPlayPause = { viewModel.playerVM.playPause() },
                    onNext = { viewModel.playerVM.next() },
                )
            }
            // PhoneNavBar 内部已用 navigationBarsPadding()
            PhoneNavBar(
                currentScreen = currentScreen,
                onNavigate = { viewModel.navVM.navigateTo(it) }
            )
        }

        // 全屏可视化舞台（20 套效果）—— 覆盖层，不新增 Screen 枚举
        if (showVisualizer) {
            VisualizerOverlay(
                viewModel = viewModel,
                isTV = isTV,
                currentSong = currentSong,
                isPlaying = isPlaying
            )
        }

    // 加入歌单弹窗（Library / 专辑详情 / 艺术家详情 共用，提升到 AppRoot 顶层跨页面生效）
    pickerSong?.let { song ->
        val dlgPlaylists by viewModel.playlistVM.localPlaylists.collectAsState(initial = emptyList())
        PlaylistPickerDialog(
            playlists = dlgPlaylists,
            onPick = { playlist ->
                viewModel.playlistVM.addSongToPlaylist(playlist.id, song)
                pickerSong = null
            },
            onCreate = { name ->
                if (name.isNotBlank()) {
                    viewModel.playlistVM.createLocalPlaylist(name)
                }
            },
            onDismiss = { pickerSong = null }
        )
    }

    // v2.35.0 多码率：单曲下载的码率选择面板（探测到多档可用时由 DownloadViewModel 弹出）
    val qpState by viewModel.downloadVM.qualityPicker.collectAsState()
    when (val qp = qpState) {
        is DownloadViewModel.QualityPickerState.Probing -> {
            QualityProbingDialog(songTitle = qp.song.title)
        }
        is DownloadViewModel.QualityPickerState.Pick -> {
            DownloadQualityPickerDialog(
                songTitle = qp.song.title,
                available = qp.available,
                downloaded = qp.downloaded,
                onConfirm = { tier -> viewModel.downloadVM.downloadWithQuality(qp.song, tier) },
                onDismiss = { viewModel.downloadVM.dismissQualityPicker() }
            )
        }
        is DownloadViewModel.QualityPickerState.None -> {
            // 零档可用 → 错误提示，不入队（自动关闭）
            LaunchedEffect(qp.song.id) {
                viewModel.showMessage(context.getString(R.string.quality_probe_none))
                viewModel.downloadVM.dismissQualityPicker()
            }
        }
        null -> Unit
    }

    }

}
/**
 * 现状顶部导航栏（TV + 手机横屏共用）。
 *
 * v2.36.0 由 `AppRoot` 内联代码原样抽为具名 composable（方案 §8.6 骨架要求），
 * **零行为变化** —— 6 项导航（首页/播放/曲库/我的/队列/设置）+ 窄屏横向滚动。
 *
 * ⚠️ 补（2026-09-20，真机反馈）：**手机横屏**时最右侧额外挂一个 [OrientationToggleButton]。
 * 原因：横屏走 TV 布局后 `PhoneTopBar` 不再渲染 → 竖屏那个方向按钮随之消失 →
 * 用户从竖屏点进横屏后就**再也切不回竖屏**（只能靠系统旋转）。
 * 位置选最右侧，与竖屏 `PhoneTopBar` 的按钮位置（右上角）**一致**，肌肉记忆无需重建。
 *
 * ⛔ 判据必须是 `uiMode == UiMode.PhoneLandscape`，**不能写成 `!= UiMode.PhonePortrait`**
 * —— 后者会把按钮一并发给 TV，破坏「TV 端零变化」。`showOrientationToggle = false` 时
 * **不产生任何 Spacer / padding**，TV 布局与改动前逐字一致（B1）。
 *
 * ## 顶部空挡（2026-11-13）
 *
 * 「首页 / 正在播放 / 曲库…」菜单行与播放页的「歌曲名 + 内嵌 / 本地 / 在线」按钮行之间
 * 的空挡，由**三段**独立内边距叠成，任一段单看都不刺眼：
 *
 * | 来源 | 值 | 说明 |
 * |---|---|---|
 * | 本栏下内边距 | 16dp → 播放页 **4dp** | 本文件 |
 * | `NavItem` 内容内边距 | 12dp | 按钮自身的按压/Focus 热区，**不能缩**（缩了就削 D-Pad 热区） |
 * | 播放页顶部内边距 | TV 40dp → **8dp** / 横屏 0dp | `NowPlayingScreen` |
 *
 * 改前 TV 合计 68dp（≈1080p 屏高的 12.6%），横屏 28dp ⇒ 用户「电视上更大更显眼」的
 * 观感差异其实就是**同一个公式里第三段取值不同**。改后 TV 24dp、横屏 20dp；
 * 省下的 44dp（TV）全部交给播放页右侧歌词区（`weight(1f)` 自动吃满）⇒ 约多露 0.8 行歌词。
 *
 * ⚠️ 只对 `Screen.NowPlaying` 收窄：其余页面正文有自己的标题行/卡片间距，
 * 一起收会让首页/曲库/我的全部上挤，超出本次反馈范围。
 * 本栏下内边距收紧**不会移动导航行自身**（上内边距不变），只会把下方内容拉近。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvTopNavBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    showOrientationToggle: Boolean = false,
    orientationPref: String = ScreenOrientationPref.AUTO,
    onToggleOrientation: () -> Unit = {},
) {
    // 播放页把**下**内边距收窄到 4dp：见上方「顶部空挡」表 —— 该页正文紧贴导航行，
    // 其余页面保持 16dp（它们头部自带标题行，不需要贴这么近）。
    // ⚠️ 上内边距恒为 16dp：导航行自身的纵向位置因此不变，收紧的只是它下方的空档。
    val bottomPadding = if (currentScreen == Screen.NowPlaying) 4.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .background(
                        NasMusicColors.Primary,
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "\u266A", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "NAS Music", color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
        }

        // 导航项（外层固定宽度右对齐；内层可横向滑动——手机窄屏滚动浏览全部 tab）
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem(
                    label = stringResource(R.string.nav_home),
                    selected = currentScreen == Screen.Home,
                    onClick = { onNavigate(Screen.Home) }
                )
                NavItem(
                    label = stringResource(R.string.nav_now_playing),
                    selected = currentScreen == Screen.NowPlaying,
                    onClick = { onNavigate(Screen.NowPlaying) }
                )
                NavItem(
                    label = stringResource(R.string.nav_library),
                    selected = currentScreen == Screen.Library,
                    onClick = { onNavigate(Screen.Library) }
                )
                NavItem(
                    label = stringResource(R.string.nav_mine),
                    selected = currentScreen == Screen.Mine,
                    onClick = { onNavigate(Screen.Mine) }
                )
                NavItem(
                    label = stringResource(R.string.nav_queue),
                    selected = currentScreen == Screen.Queue,
                    onClick = { onNavigate(Screen.Queue) }
                )
                NavItem(
                    label = stringResource(R.string.nav_settings),
                    selected = currentScreen == Screen.Settings,
                    onClick = { onNavigate(Screen.Settings) }
                )
            }
        }

        // 仅手机横屏：右上角方向切换（与竖屏 PhoneTopBar 的按钮位置一致）
        if (showOrientationToggle) {
            Spacer(modifier = Modifier.width(8.dp))
            OrientationToggleButton(
                orientationPref = orientationPref,
                onToggle = onToggleOrientation,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .defaultMinSize(minHeight = 48.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.12f,
        animationDurationMs = 250,
        containerColor = Color.Transparent,
        focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f)
                                else NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.96f
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = if (selected) FontSize.subtitle() else FontSize.button(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary
            )
        }
    }
}

/**
 * 全屏可视化舞台覆盖层（20 套效果）。
 *
 * 歌词 / 进度在**此处**收集而非 AppRoot 顶层——播放期间进度每秒变化，
 * 若在顶层订阅会驱动整棵 UI 树重组（同 H-3 修复的教训）。
 */
@Composable
private fun VisualizerOverlay(
    viewModel: com.nasmusic.tv.ui.viewmodel.MainViewModel,
    isTV: Boolean,
    currentSong: com.nasmusic.tv.data.model.Song?,
    isPlaying: Boolean
) {
    val vm = viewModel.visualizerVM
    val theme by vm.theme.collectAsState()
    val quality by vm.quality.collectAsState()
    val photoWallAvailable by vm.photoWallAvailable.collectAsState()
    val cover by vm.cover.collectAsState()
    val palette by vm.palette.collectAsState()
    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    // M38：这条 1 Hz 的 progress **必须**在组合期收集 —— 它唯一的用途是在组合期
    // `computeLyricInfo(lyrics, progressMs, …)` 里定位「当前唱到第几行」/ 行内进度，
    // 属于必须驱动重组的输入（不是可以挪进 draw 阶段的动画量，那类量走 `positionMs` provider）。
    // 唯一的收敛点是把订阅范围收到本 overlay（已是如此），不再上游到 AppRoot 顶层。
    val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)

    // 无自动导演档：用户选中的主题恒定显示，无需低频重估
    val effectiveTheme = theme

    // 封面加载 + 取色（切歌时一次，异步不阻塞）
    LaunchedEffect(currentSong?.id) {
        val url = currentSong?.let { viewModel.getCoverCandidates(it).firstOrNull() }
        vm.loadCover(url, currentSong?.id)
    }

    // 频谱舞台（含照片墙）**恒为深色**：浅色主题不得漏入舞台与转场绘制。
    // 所有舞台内的 NasMusicColors / MaterialTheme 读取都被这里覆盖为深色调色板。
    com.nasmusic.tv.ui.theme.ForceDarkPalette {
        VisualizerStage(
            song = currentSong,
            // T6 双缓冲：传 provider 而非快照引用——绘制循环每帧求值，拿到当前 front
            frame = { vm.frame },
            // 逐帧的播放位置（`vm.progress` 是 1 Hz 的 StateFlow ⇒ 动画会每秒跳一次）
            positionMs = { vm.positionMs },
            cover = cover,
            palette = palette,
            lyrics = lyrics?.lines,
            progressMs = progress,
            theme = effectiveTheme,
            quality = quality,
            // 照片墙编排器（阶段 10）：控制器在 ViewModel 里（跨重组存活），本组件只接线。
            // ⚠️ 访问 `vm.photoWall` 会触发 `by lazy` 构造 —— 成本只等于几个纯 Kotlin 对象。
            photoWall = vm.photoWall,
            // 三来源开关之「或」—— 与 `VisualizerViewModel.step()` 用的是**同一个** StateFlow
            // ⇒ 指示器与左右键看到的是同一份列表（不会出现「指示器上没有、却切得到」）。
            photoWallAvailable = photoWallAvailable,
            isTV = isTV,
            onExit = { vm.exitVisualizer() },
            onNextTheme = { vm.nextTheme() },
            onPrevTheme = { vm.prevTheme() }
        )
    }
}
