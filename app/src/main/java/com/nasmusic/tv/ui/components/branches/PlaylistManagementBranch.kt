package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*

/**
 * PlaylistManagement 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlaylistManagementBranch(
    viewModel: MainViewModel,
) {
                    val playlistsState by viewModel.playlists.collectAsState(initial = UiState.Success(emptyList()))
                    val selectedPlaylistSongsState by viewModel.selectedPlaylistSongs.collectAsState(initial = UiState.Success(emptyList()))
                    // 进入页面即拉一次服务端歌单：loadPlaylists() 原本只在重开 NAS 源时调用，
                    // 首次连接 / 直接导航进本页都不会触发 ⇒ 歌单列表恒空（导航入口缺失是另一半问题）。
                    LaunchedEffect(Unit) { viewModel.loadPlaylists() }
                    PlaylistManagementScreen(
                        playlists = playlistsState.dataOrNull() ?: emptyList(),
                        selectedPlaylistSongs = selectedPlaylistSongsState.dataOrNull() ?: emptyList(),
                        // B2-03：按真实 UiState 传值 —— 旧实现硬编码 false 让 loading 分支成为
                        // 死代码，并把 Error 折成空列表（用户只看到「暂无歌单」、无重试入口）。
                        isLoading = playlistsState is UiState.Loading,
                        errorMessage = (playlistsState as? UiState.Error)?.message,
                        songsErrorMessage = (selectedPlaylistSongsState as? UiState.Error)?.message,
                        onRetry = { viewModel.loadPlaylists() },
                        onSelectPlaylist = { viewModel.selectPlaylist(it) },
                        onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onPlayPlaylist = { playlist ->
                            viewModel.playPlaylist(playlist)
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onRemoveSong = { songId -> viewModel.removeFromPlaylist(songId) },
                        // 入口在「我的」页（MineBranch 的"服务器歌单"按钮）⇒ 返回也应回到「我的」。
                        onBack = { viewModel.navVM.navigateTo(Screen.Mine) }
                    )
}
