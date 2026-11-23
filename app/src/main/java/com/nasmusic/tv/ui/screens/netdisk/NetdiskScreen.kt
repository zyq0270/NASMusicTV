package com.nasmusic.tv.ui.screens.netdisk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.common.ActionBar
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.ui.screens.PlaylistPickerDialog
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel
import com.nasmusic.tv.backend.download.model.stateOfSong
import kotlinx.coroutines.flow.distinctUntilChanged

/** C37：距列表底部还剩多少项时预取下一页（含 footer 项，留一屏余量） */
private const val NETDISK_PREFETCH_ITEMS = 8

/**
 * 网盘 Tab：目录浏览 + 关键词搜索 + 文件操作（播放/加队列/加歌单/查看 MV）
 *
 * 独立 Screen.Netdisk，不并入 Network Tab（浏览优先 vs 搜索优先，两种交互范式不可混用）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetdiskScreen(
    viewModel: MainViewModel,
    onPlaySong: (Song) -> Unit,
    onPlayAllSongs: (List<Song>) -> Unit,
    onBack: () -> Unit
) {
    val connectionState by viewModel.netVM.baiduConnectionState.collectAsState()
    val currentDir by viewModel.netdiskCurrentDir.collectAsState()
    val dirFiles by viewModel.netdiskDirFiles.collectAsState()
    val isLoading by viewModel.netdiskIsLoading.collectAsState()
    val searchKeyword by viewModel.netdiskSearchKeyword.collectAsState()
    val searchResults by viewModel.netdiskSearchResults.collectAsState()
    val localPlaylists by viewModel.playlistVM.localPlaylists.collectAsState(initial = emptyList())
val favoriteIds by viewModel.networkFavoriteIds.collectAsState(initial = emptySet())
val queueSongIds by viewModel.queueSongIds.collectAsState(initial = emptySet())
val downloadStates by viewModel.songDownloadStates.collectAsState(initial = emptyMap())

    var showSearchDialog by remember { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    // 首次进入：刷新连接状态并加载当前目录（不依赖时序上的首次连接状态，避免空列表）
    LaunchedEffect(Unit) {
        viewModel.netVM.refreshBaiduConnectionState()
        viewModel.netVM.listBaiduDir(viewModel.netdiskCurrentDir.value)
    }

    // v2.36.0 竖屏（方案 §4.5 / P0-19）：页 padding 32→16；搜索框 420dp → 整行（原头部三件套一行必溢出）
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    // L25：两个网格的状态都必须**跨 when 分支存活**。原先搜索网格根本没传 state、
    // 目录网格在分支内部 remember ⇒ 清空搜索词 / 进出目录时状态机被销毁，
    // 滚动位置与 D-Pad 焦点一起回到顶部（长目录里等于每次都要重新翻）。
    // 两个网格分开保存，各自记住自己的位置。
    val searchGridState = rememberLazyGridState()
    val dirGridState = rememberLazyGridState()
    Column(
        modifier = Modifier.fillMaxSize().padding(if (isPhonePortrait) 16.dp else 32.dp)
    ) {
        val backAndTitle: @Composable (Modifier) -> Unit = { m ->
            Row(modifier = m, verticalAlignment = Alignment.CenterVertically) {
                FocusableSurface(
                    onClick = onBack,
                    // 48dp 在竖屏只有 39.4 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 48dp，B1）
                    modifier = Modifier.size(portraitTouchTarget(48.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.netdisk_back), tint = NasMusicColors.TextPrimary)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(stringResource(R.string.netdisk_title), color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
            }
        }
        val searchBox: @Composable (Modifier) -> Unit = { m ->
            SearchField(
                query = searchKeyword,
                placeholder = stringResource(R.string.netdisk_search_placeholder),
                onOpenSearch = { showSearchDialog = true },
                onClear = { viewModel.netVM.clearNetdiskSearch() },
                modifier = m
            )
        }

        if (isPhonePortrait) {
            // 竖屏：返回+标题 一行，搜索框另起一行撑满
            backAndTitle(Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))
            searchBox(Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            // 顶部：返回 + 标题 + 搜索框
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
            ) {
                backAndTitle(Modifier)
                Spacer(modifier = Modifier.width(32.dp))
                // 搜索框（统一样式：点击弹出输入对话框，无独立搜索按钮）
                searchBox(Modifier.width(420.dp))
            }
        }

        when {
            connectionState is NetworkMusicViewModel.BaiduConnectionState.DirMissing -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.netdisk_dir_missing),
                            color = NasMusicColors.Warning,
                            fontSize = FontSize.button(),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.netdisk_dir_missing_desc),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                    }
                }
            }
            connectionState !is NetworkMusicViewModel.BaiduConnectionState.LoggedIn -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.netdisk_status_baidu_not_logged_in),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button()
                    )
                }
            }
            // 搜索结果优先展示
            searchKeyword.isNotBlank() -> {
                // B2-01：搜索期间必须显示加载态，不能直接报「未找到相关结果」
                // （弱网下会被当成搜不到；目录分支一直是这么处理的）。
                if (isLoading && searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.netdisk_loading_files), color = NasMusicColors.TextSecondary, fontSize = FontSize.button())
                    }
                } else if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.search_no_results), color = NasMusicColors.TextSecondary, fontSize = FontSize.button())
                    }
                } else {
                    LazyVerticalGrid(
                        columns = songGridColumns(),
                        state = searchGridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 操作栏：播放全部 + 加入队列
                        item(key = "netdisk_search_action", span = { GridItemSpan(maxLineSpan) }) {
                            ActionBar(
                                songCount = searchResults.size,
                                onPlayAll = { onPlayAllSongs(searchResults) },
                                // C38：必须用「只增不删」的批量入口。
                                // 旧实现逐首 toggleQueueSong，而 toggle 的语义是「在队列则移除」
                                // ⇒ 已经入队的歌会被反向踢出队列（PlayerViewModel 的 KDoc 点名过此坑，
                                // LibraryBranch 早已改掉，唯独网盘搜索页漏改）。
                                onAddAllToQueue = { viewModel.playerVM.addSongsToQueue(searchResults) }
                            )
                        }
                        itemsIndexed(searchResults, key = { _, s -> "${s.networkId}_${s.title}" }) { index, song ->
                            UnifiedSongRow(
                                song = song,
                                onClick = { onPlaySong(song) },
                                mode = SongRowMode.MODE_ROW,
                                index = index,
                                isFavorited = song.id in favoriteIds,
                                onToggleFavorite = { viewModel.toggleNetworkFavorite(song) },
                                isInQueue = song.id in queueSongIds,
                                onToggleQueue = { viewModel.playerVM.toggleQueueSong(song) },
                                onAddToPlaylist = { actionSong = song },
                                downloadState = downloadStates.stateOfSong(song),
                                onDownload = { viewModel.downloadVM.downloadSong(song) }
                            )
                        }
                    }
                }
            }
            else -> {
                // 目录浏览
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(stringResource(R.string.netdisk_path_label), color = NasMusicColors.TextSecondary, fontSize = FontSize.body())
                    Text(currentDir, color = NasMusicColors.Primary, fontSize = FontSize.body(), modifier = Modifier.weight(1f))
                    // 全部播放（含子目录）
                    FocusableSurface(
                        onClick = { viewModel.playAllNetdiskDir(currentDir, onPlayAllSongs) },
                        modifier = Modifier.padding(end = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = NasMusicColors.OnPrimary,
                        focusedContentColor = NasMusicColors.OnPrimary
                    ) {
                        Text(stringResource(R.string.netdisk_play_all), color = NasMusicColors.OnPrimary, fontSize = FontSize.small(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    if (currentDir != "/" && currentDir.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FocusableSurface(
                            onClick = { viewModel.netVM.navigateBaiduDirUp() },
                            modifier = Modifier.padding(end = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.netdisk_parent_dir), color = NasMusicColors.TextPrimary, fontSize = FontSize.small(), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.netdisk_loading_files), color = NasMusicColors.TextSecondary, fontSize = FontSize.body())
                    }
                } else if (dirFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.netdisk_empty_dir), color = NasMusicColors.TextSecondary, fontSize = FontSize.body())
                    }
                } else {
                    // C37：首页只取 PAGE_SIZE(=1000) 条，超出部分靠触底预取追加
                    val hasMore by viewModel.netVM.netdiskHasMore.collectAsState()
                    val loadingMore by viewModel.netVM.netdiskLoadingMore.collectAsState()
                    val gridState = dirGridState
                    LaunchedEffect(gridState, hasMore, dirFiles.size) {
                        if (!hasMore) return@LaunchedEffect
                        snapshotFlow {
                            val info = gridState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                            lastVisible >= info.totalItemsCount - NETDISK_PREFETCH_ITEMS
                        }.distinctUntilChanged().collect { nearEnd ->
                            if (nearEnd) viewModel.netVM.loadMoreBaiduDir()
                        }
                    }
                    LazyVerticalGrid(
                        columns = songGridColumns(),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(
                            items = dirFiles,
                            key = { _, f -> "f_${f.fsId}" },
                            span = { _, f -> if (f.isDir) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                        ) { index, file ->
                            if (file.isDir) {
                                FileRow(
                                    file = file,
                                    onClick = { viewModel.netVM.enterBaiduDir(file.serverFilename) },
                                    onMore = {}
                                )
                            } else if (BaiduPanApi.isAudioFile(file.serverFilename, file.category)) {
                                val song = file.toSong()
                                UnifiedSongRow(
                                    song = song,
                                    onClick = { onPlaySong(song) },
                                    mode = SongRowMode.MODE_ROW,
                                    index = index,
                                    isFavorited = song.id in favoriteIds,
                                    onToggleFavorite = { viewModel.toggleNetworkFavorite(song) },
                                    isInQueue = song.id in queueSongIds,
                                    onToggleQueue = { viewModel.playerVM.toggleQueueSong(song) },
                                    onAddToPlaylist = { actionSong = song },
                                    downloadState = downloadStates.stateOfSong(song),
                                    onDownload = { viewModel.downloadVM.downloadSong(song) }
                                )
                            }
                        }
                        if (hasMore || loadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(R.string.netdisk_loading_files),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = FontSize.small()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 网盘搜索输入对话框
    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.netdisk_search_title),
            hint = stringResource(R.string.netdisk_search_hint),
            initialValue = searchKeyword,
            onConfirm = { input ->
                val kw = input.trim()
                if (kw.isNotBlank()) {
                    viewModel.netVM.searchBaidu(kw)
                    showSearchDialog = false
                }
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    // 加入歌单选择弹窗
    actionSong?.let { song ->
        PlaylistPickerDialog(
            playlists = localPlaylists,
            onPick = { playlist ->
                viewModel.playlistVM.addSongToPlaylist(playlist.id, song)
                actionSong = null
            },
            onCreate = { name -> if (name.isNotBlank()) viewModel.playlistVM.createLocalPlaylist(name) },
            onDismiss = { actionSong = null }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FileRow(file: BaiduFile, onClick: () -> Unit, onMore: () -> Unit) {
    val isAudio = !file.isDir && BaiduPanApi.isAudioFile(file.serverFilename, file.category)
    FocusableSurface(
        onClick = onClick,
        onLongClick = onMore,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val icon = if (file.isDir) Icons.Default.Folder else Icons.Default.MusicNote
            val tint = if (file.isDir) NasMusicColors.Primary else
                if (isAudio) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                file.serverFilename,
                color = if (file.isDir || isAudio) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.weight(1f)
            )
            if (isAudio) {
                Text(formatSize(file.size), color = LocalFocusableContentColor.current, fontSize = FontSize.small())
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
