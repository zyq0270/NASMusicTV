package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nasmusic.tv.ui.components.picker.ArcDialDialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.components.MvPlaybackScreen
import com.nasmusic.tv.ui.components.MvSearchingScreen
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*
/**
 * F2-2b：NowPlaying 分支提取（AppRoot 方法过大 MethodTooLargeException 修复）。
 * 原分支体逐行搬迁，仅把外层共享状态改为参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NowPlayingBranch(
    viewModel: MainViewModel,
    isTV: Boolean,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    currentSong: Song?,
    qualityTier: Int,
    /** 播放按钮图标真相源（用户意图，BUFFERING 时仍 true） */
    isPlaying: Boolean,
    /** 真实出声状态（BUFFERING / IDLE 时为 false）；仅供逐字歌词插值时钟使用，避免缓冲期空转 */
    actualPlaying: Boolean,
    playMode: com.nasmusic.tv.data.model.PlayMode,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    coverFilterDarkOverlay: Float,
    settings: com.nasmusic.tv.data.model.AppSettings,
    showMv: Boolean,
    mvState: com.nasmusic.tv.ui.viewmodel.MvAvailability?,
    isConnected: Boolean
) {
                    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
                    val lyricsAvailability by viewModel.lyricsAvailability.collectAsState(initial = com.nasmusic.tv.data.model.LyricsAvailability())
                    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState(initial = com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE)
                    val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
                    val lyricsFontScale by viewModel.prefs.lyrics.lyricsFontScale.collectAsState(initial = 1.0f)
                    val vocalRemovalEnabled by viewModel.vocalVM.vocalRemovalEnabled.collectAsState()
                    val pitchSemitones by viewModel.vocalVM.pitchSemitones.collectAsState()
                    val playbackSpeed by viewModel.vocalVM.playbackSpeed.collectAsState()
                    val separationMode by viewModel.vocalVM.separationMode.collectAsState()
                    val separating by viewModel.vocalVM.separating.collectAsState()
                    val separationProgress by viewModel.vocalVM.separationProgress.collectAsState()
                    val hqError by viewModel.vocalVM.hqError.collectAsState()
                    val hqErrorDetail by viewModel.vocalVM.hqErrorDetail.collectAsState()
                    val hqSuccess by viewModel.vocalVM.hqSuccess.collectAsState()
                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    // C7 修复：收藏状态建立订阅，点收藏后星标即时刷新。
                    // 原实现直读 isFavorite(song.id) 不订阅，点收藏后需切歌才刷新。
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val mvReady = mvState as? com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready
                    val mvSearching = mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.Searching
                    if (showMv && mvReady != null) {
                        // MTV 音乐视频全屏页（独立播放器，退出时 MainViewModel 恢复主播放器）
                        MvPlaybackScreen(
                            mv = mvReady.mv,
                            lyrics = lyrics,
                            alternatives = mvReady.alternatives,
                            onExit = { viewModel.mvVM.exitMvMode() },
                            onPlaybackError = { viewModel.onMvPlaybackError() },
                            onPlaybackEnded = { viewModel.onMvPlaybackEnded() },
                            onSwitchOrResearch = { viewModel.onSwitchOrResearch() },
                            onSearchBilibili = { viewModel.onSearchBilibili() },
                            onPreviousMv = { viewModel.onMvPrevious() },
                            onNextMv = { viewModel.onMvNext() },
                            repeatOne = playMode == com.nasmusic.tv.data.model.PlayMode.REPEAT_ONE,
                            mvMessage = viewModel.mvVM.mvMessage.collectAsState().value,
                            // 手机端无需"手机遥控"二维码（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null
                        )
                    } else if (showMv && mvSearching) {
                        // Searching 态保持 MV 全屏形态：主播放器已被 suppressPlayback 暂停，
                        // 回落到 NowPlayingScreen 会出现「无声的播放页」闪屏。搜索完成后
                        // 由 Ready 切回播放页 / NotFound 由 AppRoot 自动退出。
                        MvSearchingScreen(onExit = { viewModel.mvVM.exitMvMode() })
                    } else {
                        // F-2：progress/duration 在本分支内收集，播放期间的每秒重组
                        // 只影响 NowPlayingScreen，不再驱动 AppRoot 全树
                        // M26：progress 以 **State** 形式原样下传（⛔ 不要在这里解包 `by`）——
                        // 一旦在本分支读值，1Hz 的进度 tick 会把整个分支（含 NowPlayingScreen
                        // 全部参数）重新求值；由叶子自己读 .value，重组范围就只剩那条进度线。
                        val progressState = viewModel.playerVM.progress.collectAsState(initial = 0L)
                        val duration by viewModel.playerVM.duration.collectAsState(initial = 0L)
                        // 队列快速切歌：本分支持有弹窗状态，队列取自 playerState
                        val playerState by viewModel.playerVM.playerState.collectAsState()
                        var showQueueDial by remember { mutableStateOf(false) }
                        var queueDialIndex by remember { mutableIntStateOf(0) }
                        NowPlayingScreen(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            actualPlaying = actualPlaying,
                            playMode = playMode,
                            progressMs = progressState,
                            durationMs = duration,
                            lyrics = lyrics,
                            lyricsAvailability = lyricsAvailability,
                            coverCandidates = coverCandidates,
                            highlightMode = lyricsHighlightMode,
                            lyricsFontScale = lyricsFontScale,
                            onLyricsFontScaleChange = { viewModel.updateLyricsFontScale(it) },
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            // 统一用合并后的 favoriteIds（NAS + 网络/本地），无需按歌曲类型分流
                            isFavorite = currentSong?.let { song -> song.id in favoriteIds } ?: false,
                            isImmersiveMode = isImmersiveMode.value,
                            onToggleImmersive = { isImmersiveMode.value = !isImmersiveMode.value },
                            onPlayPause = { viewModel.playerVM.playPause() },
                            onNext = { viewModel.playerVM.next() },
                            onPrevious = { viewModel.playerVM.previous() },
                            onTogglePlayMode = { viewModel.playerVM.togglePlayMode() },
                            showQuickSwitchButton = playerState.queue.isNotEmpty(),
                            onOpenQuickSwitch = {
                                queueDialIndex = playerState.currentIndex
                                showQueueDial = true
                            },
                            // === KARAOKE 人声消除 ===
                            vocalRemovalEnabled = vocalRemovalEnabled,
                            onToggleVocalRemoval = { viewModel.vocalVM.toggleVocalRemoval() },
                            // === K 歌页面状态 ===
                            showKaraoke = showKaraoke,
                            onEnterVisualizer = { viewModel.visualizerVM.enterVisualizer() },
                            onEnterKaraoke = { viewModel.vocalVM.enterKaraoke() },
                            onExitKaraoke = { viewModel.vocalVM.exitKaraoke() },
                            // === MTV 音乐视频 ===
                            mvAvailable = mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready,
                            onEnterMv = { viewModel.enterMvMode() },
                            // 手机端不启动 HTTP 遥控服务（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null,
                            onSeek = { viewModel.playerVM.seekTo(it) },
                            onSwitchLyricsSource = { viewModel.switchLyricsSource(it) },
                            onChangeHighlightMode = { viewModel.setLyricsHighlightMode(it) },
                            // 统一走 toggleNetworkFavorite：内部按歌曲类型分流（NAS→adapter，其他→DataStore）
                            onToggleFavorite = currentSong?.let { song ->
                                { viewModel.toggleNetworkFavorite(song) }
                            },
                            technicalInfo = viewModel.songTechnicalInfo.collectAsState(initial = null).value,
                            onLoadTechnicalInfo = { viewModel.loadSongTechnicalInfo() },
                            onSearchArtist = { keyword ->
                                // 统一入口：曲库内歌手进详情页；网络歌手等曲库外的名字仍走跨源搜索。
                                viewModel.openArtistFromName(keyword)
                            },
                            onSearchSong = { keyword ->
                                viewModel.selectLibraryTab(LibraryTab.SEARCH)
                                viewModel.setLibrarySearchKeyword(keyword)
                                viewModel.navVM.navigateTo(Screen.Library)
                            },
                            // === v2.35.0 多码率：音质档位（方案 §5.1） ===
                            qualityTier = qualityTier,
                            onChangeQuality = { tier, scope -> viewModel.setQualityTier(tier, scope) },                            // === K 歌页面：升降调 / 变速 ===
                            pitchSemitones = pitchSemitones,
                            playbackSpeed = playbackSpeed,
                            onPreviewPitch = { viewModel.vocalVM.previewPitch(it) },
                            onCommitPitch = { viewModel.vocalVM.commitPitch() },
                            onPreviewSpeed = { viewModel.vocalVM.previewSpeed(it) },
                            onCommitSpeed = { viewModel.vocalVM.commitSpeed() },
                            onResetPitch = { viewModel.vocalVM.resetPitch() },
                            onResetSpeed = { viewModel.vocalVM.resetSpeed() },
                            // === 分离模式（快速/高质量） ===
                            isHighQualityMode = separationMode == com.nasmusic.tv.player.SeparationMode.HIGH_QUALITY,
                            isSeparating = separating,
                            separationProgress = separationProgress,
                            // 横幅优先显示带设备诊断的完整详情（第一行就是失败原因）。
                            // ⚠️ 判空必须看 hqError 而不是详情：详情是长期留存的、不会随 5 秒关闭消失，
                            // 用它判空会让横幅再也关不掉。
                            hqError = if (hqError != null) (hqErrorDetail ?: hqError) else null,
                            onToggleSeparationMode = { viewModel.vocalVM.toggleSeparationMode() },
                            onClearHqError = { viewModel.vocalVM.clearHqError() },
                            hqSuccess = hqSuccess,
                            onClearHqSuccess = { viewModel.vocalVM.clearHqSuccess() },
                            // 高质量分离模型是否已下载（未下载时 K 歌页禁用高质量切换）
                            modelDownloaded = modelDownloaded,
                            // === F2-2b 睡眠定时器（常驻按钮：未启动"-"，运行中剩余分钟，点击弹档位） ===
                            sleepTimerState = sleepTimerSt,
                            onSleepTimerStart = { viewModel.playerVM.startSleepTimer(it) },
                            onSleepTimerCancel = { viewModel.playerVM.cancelSleepTimer() },
                            // === v2.36.0 竖屏：顶栏「收起」回首页（播放继续，由 MiniPlayer 承载）===
                            onCollapse = { viewModel.navVM.navigateTo(Screen.Home) },
                            onOpenQueue = { viewModel.navVM.navigateTo(Screen.Queue) },
                            // === 应用内音量（应用级增益，独立于系统音量）===
                            appVolume = viewModel.playerVM.appVolume.collectAsState(initial = 1f).value,
                            onChangeAppVolume = { viewModel.playerVM.setAppVolume(it) },
                        )

                        // 队列快速切歌拨盘：纯队列顺序，不读任何热度统计
                        if (showQueueDial && playerState.queue.isNotEmpty()) {
                            ArcDialDialog(
                                items = playerState.queue,
                                selectedIndex = queueDialIndex.coerceIn(0, playerState.queue.size - 1),
                                onSelectedIndexChange = { queueDialIndex = it },
                                onConfirm = { index, _ ->
                                    if (index in playerState.queue.indices) {
                                        viewModel.playerVM.resolveAndPlayByIndex(index)
                                    }
                                },
                                onDismiss = { showQueueDial = false },
                                label = { it.title },
                                key = { index, song -> "$index:${song.id}:${song.title}" },
                                dPadMode = isTV
                            )
                        }
                    }
}
