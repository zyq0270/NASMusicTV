package com.nasmusic.tv.ui.components.branches

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.*

/**
 * Mine 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun MineBranch(
    viewModel: MainViewModel
) {
    var showPlaylistPush by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.nasmusic.tv.NasMusicApp
    val playlistSyncVM: PlaylistSyncViewModel = viewModel(
        factory = PlaylistSyncViewModel.factory(context)
    )
    val playlistSyncState by playlistSyncVM.state.collectAsState()
    val localPlaylists by viewModel.playlistVM.localPlaylists.collectAsState(initial = emptyList())
    val activeConnection = app.backendRegistry.getActiveConnection()
    val capabilities = activeConnection?.first?.capabilities
    val canPushPlaylists = capabilities?.playlistCreate == true && capabilities.playlistAdd
    val pushUnavailableReason = if (activeConnection != null && !canPushPlaylists) {
        stringResource(R.string.playlist_sync_push_unsupported)
    } else null
    Box(modifier = Modifier.fillMaxSize()) {
                    val songDownloadStates by viewModel.songDownloadStates.collectAsState(initial = emptyMap())
                    val favoriteSongsState by viewModel.favoriteSongs.collectAsState(initial = UiState.Success(emptyList()))
                    val networkFavoriteSongs by viewModel.networkFavoriteSongs.collectAsState(initial = emptyList())
                    val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
                    // 歌单导入（阶段5）：URL 失效标记 / 补全进度（「我的」页徽标与按钮数据源）
                    val songReachability by viewModel.playlistImportVM.songReachability.collectAsState(initial = emptyMap())
                    val enrichProgress by viewModel.playlistImportVM.enrichProgress.collectAsState(initial = null)
                    MineScreen(
                        favoriteSongsState = favoriteSongsState,
                        networkFavoriteSongs = networkFavoriteSongs,
                        localPlaylists = localPlaylists,
                        queueSongIds = queueSongIds,
                        onPlaySong = { song ->
                            // 网络歌曲先解析播放链接（成功后才跳页），本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song) {
                                    viewModel.navVM.navigateTo(Screen.NowPlaying)
                                }
                            } else {
                                viewModel.playQueue(listOf(song))
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onPlayAll = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onToggleQueue = { song -> viewModel.playerVM.toggleQueueSong(song) },
                        // 批量「加入队列」只增不删；逐首 ☰/✓ 才走 toggleQueueSong。
                        onAddSongsToQueue = { songs -> viewModel.playerVM.addSongsToQueue(songs) },
                    onCreatePlaylist = { name -> viewModel.playlistVM.createLocalPlaylist(name) },
                    onOpenServerPlaylists = {
                        // G8-06：⛔ 不要在这里拉歌单 —— PlaylistManagementBranch 的
                        // LaunchedEffect(Unit) 已覆盖所有入口，两处都调会发两个相同请求，
                        // 第二次的 Loading 还会把已到的列表瞬时压成「暂无歌单」。
                        viewModel.navVM.navigateTo(Screen.PlaylistManagement)
                    },
                    onRenamePlaylist = { id, newName -> viewModel.playlistVM.renameLocalPlaylist(id, newName) },
                        onDeletePlaylist = { id ->
                            viewModel.playlistVM.deleteLocalPlaylist(id)
                            // 歌单删除联动清理导入历史（2026-09-18 决策：历史行无删除入口）
                            viewModel.playlistImportVM.consumeHistoryIfDeleted(id)
                        },
                        onPlayPlaylist = { playlist -> viewModel.playLocalPlaylist(playlist) },
                        onRemoveSongFromPlaylist = { playlistId, songId -> viewModel.playlistVM.removeSongFromPlaylist(playlistId, songId) },
                        onAddSongToPlaylist = { playlistId, song -> viewModel.playlistVM.addSongToPlaylist(playlistId, song) },
                        // 功能入口（手机端底部导航未覆盖：队列 / 网盘 / 设置）
                        onOpenQueue = { viewModel.navVM.navigateTo(Screen.Queue) },
                        onOpenNetdisk = { viewModel.navVM.navigateTo(Screen.Netdisk) },
                        onOpenSettings = { viewModel.navVM.navigateTo(Screen.Settings) },
                        onPushLocalPlaylists = if (canPushPlaylists) ({
                            if (!playlistSyncState.busy) {
                                playlistSyncVM.prepare()
                                showPlaylistPush = true
                            }
                        }) else null,
                        pushUnavailableReason = pushUnavailableReason,
                        // 歌曲下载状态
                        downloadStates = songDownloadStates,
                        onDownloadSong = { song -> viewModel.downloadVM.downloadSong(song) },
                        onDeleteDownloadSong = { song -> viewModel.downloadVM.deleteDownload(song) },
                        // 歌单导入（阶段5）
                        songReachability = songReachability,
                        enrichProgress = enrichProgress,
                        onEnrichPlaylist = { playlistId -> viewModel.playlistImportVM.enrichPlaylist(playlistId) }
                    )
        FocusableSurface(
            onClick = { viewModel.navVM.navigateTo(Screen.MetadataCleanup) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 24.dp),
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.05f,
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary,
        ) {
            Text(
                text = stringResource(R.string.metadata_cleanup_title),
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    if (showPlaylistPush) {
        PlaylistSyncDialog(
            mode = PlaylistSyncDialogMode.PUSH,
            state = playlistSyncState,
            localPlaylists = localPlaylists,
            onExport = { _, _ -> },
            onPull = {},
            onPush = playlistSyncVM::push,
            onResume = playlistSyncVM::resumePush,
            onRefreshRemote = {},
            onDismiss = {
                if (!playlistSyncState.busy) {
                    showPlaylistPush = false
                    playlistSyncVM.consumeTerminalEvent()
                }
            },
        )
    }

}
