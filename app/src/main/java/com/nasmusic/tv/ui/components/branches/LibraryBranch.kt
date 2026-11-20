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
 * Library 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LibraryBranch(
    viewModel: MainViewModel,
    songsState: UiState<List<Song>>,
    isLoading: Boolean,
    isLibraryLoading: Boolean,
    isConnected: Boolean,
    onPickSongForPlaylist: (Song) -> Unit
) {
                    // M7 修复（2026-10-06，代码审查报告 §4）：下载进度流下沉到分支内收集——
                    // 旧实现在 AppRoot 顶层收集、作为参数下传，512KB 步长的高频更新会
                    // 驱动 AppRoot 全分支范围读取位置重组；各分支自收后互不影响。
                    val songDownloadStates by viewModel.songDownloadStates.collectAsState(initial = emptyMap())
                    val albums by viewModel.albums.collectAsState(initial = UiState.Loading as UiState<List<Album>>)
                    val genres by viewModel.genres.collectAsState(initial = UiState.Success(emptyList()))
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val artistsState by viewModel.artists.collectAsState(initial = UiState.Loading as UiState<List<Artist>>)
                    val mergedAlbumList by viewModel.mergedAlbums.collectAsState(initial = emptyList())
                    val mergedArtistsList by viewModel.mergedArtists.collectAsState(initial = emptyList())
                    val yearsState by viewModel.years.collectAsState(initial = UiState.Success(emptyList()))
                    // ALBUMS Tab 的骨架屏/错误态必须读 albums 自己的 UiState：
                    // _isLibraryLoading 只覆盖「派发异步」的瞬间，getAlbums 可能耗时数十秒。
                    // ⚠️ NAS 关时原始库存流不清空（S2 统计口径），Loading 是历史残留，不能据此显示骨架。
                    val nasAlbumsVisible = viewModel.sourceVisibility.isVisible(MusicSourceType.NAS)
                    val albumLoadState = albums.takeIf { nasAlbumsVisible }
                    val isAlbumsLoading = albumLoadState is UiState.Loading
                    val albumError = albumLoadState as? UiState.Error
                    // 本地音乐总开关（§5.4）：visibleLocalSongs 在开关关闭时仅含已下载曲目
                    val localSongsList by viewModel.visibleLocalSongs.collectAsState(initial = emptyList())
                    val searchResultsState by viewModel.searchVM.searchResults.collectAsState(initial = UiState.Success(emptyList()))
                    val albumList = albums.dataOrNull() ?: emptyList()
                    val genreList = genres.dataOrNull() ?: emptyList()
                    // ARTISTS Tab 的骨架屏/错误态必须读 artists 自己的 UiState：
                    // isLoading 与 isLibraryLoading 只描述 songs，艺术家请求期间两者都是 false。
                    val isArtistsLoading = artistsState is UiState.Loading
                    val artistError = artistsState as? UiState.Error
                    val yearsList = yearsState.dataOrNull() ?: emptyList()
                    // 可见歌曲分页（方案 §3.3 G1 · 合成点已迁入 VM）：**合并在 VM 侧完成**，UI 只做透传。
                    // ⛔ 不要在 UI 层再做一次歌曲合并 —— v2 把合并钉在本文件的一个 `remember(...)` 里，
                    //    而它的三个键在**源开关翻转时都不变** ⇒ 永不重算 ⇒ NAS 段永远不会被置空
                    //    （方案 §3.3 G1 的「⭐ 关键改动」）。
                    //    现在由 VM 的 visibleSongsPaging（combine + sourceRevision）负责重算与合并。
                    val mergedSongsPaging by viewModel.visibleSongsPaging.collectAsState(initial = com.nasmusic.tv.data.model.SongsPagingState())
                    // 「播放全部」下传给 LibraryScreen 的歌曲集合：必须按**可见性**过滤（方案 §3.3 · S4 / V18）。
                    // ⛔ 只过滤可见性，不改「播放全部」的其它口径（按 filteredAlbumIds 过滤等语义保持原样）。
                    // ⛔ 不要换成 mergedSongsPaging.songs —— 那会改变既有口径（原来只播 NAS 段并按专辑过滤）。
                    // ⚠️ remember 的键**必须**包含「可见集本身」：只写 remember(songsState) 会在开关翻转时
                    //    命中缓存（_songs 是原始库存流，关源时按 §3.0 契约不清空）⇒ 与 v2 的 G1b 同类缺陷。
                    val songList = remember(songsState, mergedSongsPaging) {
                        (songsState.dataOrNull() ?: emptyList()).filter { viewModel.sourceVisibility.isVisible(it) }
                    }
                    // 合并 NAS 流派 + 本地歌曲流派（去重）
                    val mergedGenreList = remember(genreList, localSongsList) {
                        val localGenres = localSongsList.mapNotNull { it.genre?.takeIf { g -> g.isNotBlank() } }
                            .groupBy { it }.map { (name, songs) ->
                                com.nasmusic.tv.data.model.Genre(id = "local_$name", name = name, songCount = songs.size)
                            }
                        if (localGenres.isEmpty()) genreList
                        else (genreList + localGenres).distinctBy { it.name }
                    }
                    // 合并 NAS 年代 + 本地歌曲年代（去重）
                    val mergedYearsList = remember(yearsList, localSongsList) {
                        val localYears = localSongsList.mapNotNull { it.year?.takeIf { y -> y > 0 } }.distinct()
                        (yearsList + localYears).distinct().sortedDescending()
                    }
                    val searchResultsList = searchResultsState.dataOrNull() ?: emptyList()
                    val isSearching = searchResultsState is UiState.Loading
                    val libraryActiveTab by viewModel.libraryActiveTab.collectAsState()
                    val librarySearchKeyword by viewModel.librarySearchKeyword.collectAsState()
                    val enabledSearchSources by viewModel.searchVM.enabledSearchSources.collectAsState()
                    // 方案 §5.3：设置里被关闭的搜索源（此刻不参与搜索）—— chip 需据此显示为「已关」
                    val disabledSearchSources by viewModel.searchVM.disabledSearchSources.collectAsState()
                    // v2.39.8（方案 §7.7 第 3 条）：全部网络音乐源关闭时搜索页显示引导而非「无结果」
                    val noNetworkSourceEnabled by viewModel.noNetworkSourceEnabled.collectAsState()
                    // 艺人全量方案 §3.5/§3.6：搜索页打标集合 + 艺人续页状态
                    val searchUnplayableIds by viewModel.searchVM.searchUnplayableIds.collectAsState()
                    val artistPaging by viewModel.searchVM.artistPaging.collectAsState()
                    val searchHistory by viewModel.searchHistory.collectAsState(initial = emptyList())

                    // ── RADIO Tab state ──
                    val radioStations by viewModel.radioStations.collectAsState(initial = UiState.Success(emptyList()))
                    val radioActiveTag by viewModel.radioActiveTag.collectAsState(initial = null)
                    val radioActiveQuery by viewModel.radioActiveQuery.collectAsState(initial = "")
                    // ── Task 8: 滚动位置记忆 ──
                    val albumScrollIndex by viewModel.albumScrollIndex.collectAsState()
                    val albumScrollOffset by viewModel.albumScrollOffset.collectAsState()
                    val artistScrollIndex by viewModel.artistScrollIndex.collectAsState()
                    val artistScrollOffset by viewModel.artistScrollOffset.collectAsState()
                    val songScrollIndex by viewModel.songScrollIndex.collectAsState()
                    val songScrollOffset by viewModel.songScrollOffset.collectAsState()
                    // ── DISCOVER Tab state ──
                    val browseSelections by viewModel.browseSelections.collectAsState(initial = emptyList())
                    val browseResultsState by viewModel.browseResults.collectAsState(initial = UiState.Success(emptyList()))
                    val browseIsLoading by viewModel.isBrowseSearching.collectAsState(initial = false)
                    val browseResultsList = browseResultsState.dataOrNull() ?: emptyList()
// Build DiscoverTab-compatible dimensions from BrowseDimension enum
                    // (不带字符串，只带资源 ID——remember 块内不能调 composable stringResource，
                    // 渲染在 DiscoverTab 组合作用域做)
                    val discoverDimensions = remember {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.mapIndexed { dimIdx, dim ->
                            com.nasmusic.tv.ui.screens.library.BrowseDimension(
                                dimIndex = dimIdx,
                                labelRes = dim.displayNameRes,
                                options = dim.options.mapIndexed { optIdx, opt ->
                                    com.nasmusic.tv.ui.screens.library.BrowseOption(
                                        optionIndex = optIdx,
                                        labelRes = opt.labelRes
                                    )
                                }
                            )
                        }
                    }
val discoverCurrentDimensionValues = remember(browseSelections) {
                        com.nasmusic.tv.data.model.BrowseDimension.entries.mapIndexed { dimIdx, dim ->
                            val selectedIdx = browseSelections.getOrElse(dimIdx) { 0 }
                            dimIdx to selectedIdx.coerceIn(0, dim.options.lastIndex)
                        }.toMap()
                    }
                    LibraryScreen(
                        albums = mergedAlbumList,
                        songs = songList,
                        isLoading = isLoading || isLibraryLoading,
                        isConnected = isConnected,
                        genres = mergedGenreList,
                        favoriteIds = favoriteIds,
                        artistSongsMap = viewModel.artistSongsMap.value,
                        artists = mergedArtistsList,
                        years = mergedYearsList,
                        songsPaging = mergedSongsPaging,
                        searchResults = searchResultsList,
                        isSearching = isSearching,
                        noNetworkSourceEnabled = noNetworkSourceEnabled,
                        searchUnplayableIds = searchUnplayableIds,
                        artistPaging = artistPaging,
                        onLoadMoreArtistSongs = { viewModel.searchVM.loadMoreArtistSongs() },
                        onPlayAlbum = { album ->
                            viewModel.playAlbumMultiSource(album)
                        },
                        onPlaySong = { song ->
                            // 网络歌曲需要先解析播放链接，本地歌曲直接播放
                            if (song.isNetworkSong) {
                                viewModel.playNetworkSong(song)
                            } else {
                                viewModel.playQueue(listOf(song))
                            }
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlaySongs = { songListParam ->
                            viewModel.playQueue(songListParam)
                            viewModel.navVM.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllSongs = { songs ->
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        queueSongIds = viewModel.queueSongIds.collectAsState(initial = emptySet()).value,
                        onToggleQueue = { song -> viewModel.playerVM.toggleQueueSong(song) },
                        onToggleFavorite = { song -> viewModel.toggleNetworkFavorite(song) },
                        onAddToPlaylist = { song -> onPickSongForPlaylist(song) },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onOpenArtistDetail = { artist -> viewModel.openArtistDetail(artist) },
                        onSongsByGenre = { genre, callback -> viewModel.getSongsByGenre(genre, callback) },
                        onSongsByYear = { from, to, callback -> viewModel.getSongsByYearRange(from, to, callback) },
                        onLoadSongsFirstPage = { viewModel.loadSongsFirstPage() },
                        onLoadSongsNextPage = { viewModel.loadSongsNextPage() },
                        onRetrySongsLoad = { viewModel.retrySongsLoad() },
                        onLoadArtists = { viewModel.loadArtists() },
                        isAlbumsLoading = isAlbumsLoading,
                        albumsErrorText = albumError?.message,
                        onRetryAlbumsLoad = albumError?.retry ?: { viewModel.refreshLibrary() },
                        isArtistsLoading = isArtistsLoading,
                        artistErrorText = artistError?.message,
                        onRetryArtistsLoad = artistError?.retry ?: { viewModel.loadArtists() },
                        onLoadYears = { viewModel.loadYears() },
                        onSearch = { query -> viewModel.searchSongsOnServer(query) },
                        onClearSearch = { viewModel.searchVM.clearSearch() },
                        historyItems = searchHistory,
                        activeTab = libraryActiveTab,
                        onTabSelected = { tab -> viewModel.selectLibraryTab(tab) },
                        filterQuery = librarySearchKeyword,
                        onFilterQueryChange = { keyword -> viewModel.setLibrarySearchKeyword(keyword) },
                        enabledSearchSources = enabledSearchSources,
                        disabledSearchSources = disabledSearchSources,
                        onToggleSearchSource = { source -> viewModel.searchVM.toggleSearchSource(source) },
                        onEnableAllSearchSources = { viewModel.searchVM.enableAllSearchSources() },
                        // ── SEARCH Tab ──
                        onSearchTabPlayAll = {
                            val allSongs = searchResultsList
                            if (allSongs.isNotEmpty()) {
                                viewModel.playQueue(allSongs)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onSearchTabAddAllToQueue = {
                            // 只加入队列，不播放（修复 M-8：改用只增不删的批量加入，
                            // 原 toggle 语义会把已在队列中的歌曲反向移除）
                            viewModel.playerVM.addSongsToQueue(searchResultsList)
                        },
                        // ── DISCOVER Tab ──
                        discoverDimensions = discoverDimensions,
                        discoverFilteredSongs = browseResultsList,
                        discoverIsLoading = browseIsLoading,
                        discoverCurrentDimensionValues = discoverCurrentDimensionValues,
onDiscoverDimensionChanged = { dimIdx, optIdx ->
                            viewModel.selectBrowseOption(dimIdx, optIdx)
                        },
                        onDiscoverPlayAll = {
                            if (browseResultsList.isNotEmpty()) {
                                viewModel.playQueue(browseResultsList)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onDiscoverAddAllToQueue = {
                            // 只加入队列，不播放（修复 M-8，同上）
                            viewModel.playerVM.addSongsToQueue(browseResultsList)
                        },
                        onDiscoverShuffle = {
                            viewModel.refreshBrowseSongs()
                        },
                        onDiscoverEnsureLoaded = {
                            viewModel.ensureBrowseLoaded()
                        },
                        // ── RADIO Tab ──
                        radioStations = radioStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadRadioDefault = { viewModel.loadRadioDefault() },
                        onLoadRadioTag = { tag -> viewModel.loadRadioTag(tag) },
                        onSearchRadio = { keyword -> viewModel.searchRadio(keyword) },
                        onPlayRadioStation = { station -> viewModel.playRadioStation(station) },
                        // ── Task 8: 滚动位置记忆 ──
                        albumScrollIndex = albumScrollIndex,
                        albumScrollOffset = albumScrollOffset,
                        artistScrollIndex = artistScrollIndex,
                        artistScrollOffset = artistScrollOffset,
                        songScrollIndex = songScrollIndex,
                        songScrollOffset = songScrollOffset,
                        onAlbumScrollPositionChange = { index, offset -> viewModel.saveAlbumScrollPosition(index, offset) },
                        onArtistScrollPositionChange = { index, offset -> viewModel.saveArtistScrollPosition(index, offset) },
                        onSongScrollPositionChange = { index, offset -> viewModel.saveSongScrollPosition(index, offset) },
                        // ── 歌曲下载状态 ──
                        downloadStates = songDownloadStates,
                        onDownloadSong = { song -> viewModel.downloadVM.downloadSong(song) },
                        onDeleteDownloadSong = { song -> viewModel.downloadVM.deleteDownload(song) }
                    )
}
