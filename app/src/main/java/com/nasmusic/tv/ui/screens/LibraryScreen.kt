package com.nasmusic.tv.ui.screens

import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.components.AlbumSkeletonGrid
import com.nasmusic.tv.ui.components.ArtistSkeletonGrid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.QuickPickStat
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.components.picker.ArcDialDialog
import com.nasmusic.tv.ui.components.picker.ArcAnchor
import com.nasmusic.tv.ui.components.songGridColumns
import com.nasmusic.tv.data.stats.QuickPickCandidate
import com.nasmusic.tv.data.stats.QuickPickRanker
import com.nasmusic.tv.data.stats.QuickPickSourceMerger
import com.nasmusic.tv.ui.screens.library.DiscoverTab
import com.nasmusic.tv.ui.screens.library.RadioTab
import com.nasmusic.tv.ui.screens.library.SearchTab
import com.nasmusic.tv.ui.screens.library.browse.AlbumsTab
import com.nasmusic.tv.ui.screens.library.browse.ArtistsTab
import com.nasmusic.tv.ui.screens.library.browse.ButtonChip
import com.nasmusic.tv.ui.screens.library.browse.EmptyHint
import com.nasmusic.tv.ui.screens.library.browse.GenresTab
import com.nasmusic.tv.ui.screens.library.browse.SongsTab
import com.nasmusic.tv.ui.screens.library.browse.YearsTab
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.pagePadding
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.PinyinUtils
import com.nasmusic.tv.backend.local.MusicMerger

enum class LibraryTab(val titleRes: Int) {
    SEARCH(R.string.library_search),
    DISCOVER(R.string.library_discover),
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists_alt),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    YEARS(R.string.library_years),
    RADIO(R.string.library_radio)
}

/**
 * 曲库子 TAB 横推动画时长（v2.36.2）：与竖屏播放页的模式切换保持一致的手感。
 */
private const val LIBRARY_TAB_SLIDE_ANIM_MS = 260

/**
 * 快速填写拨盘的类别名（与 LibraryTab.name 对齐，用于 quick_pick_stats 分桶）。
 */
private fun LibraryTab.quickPickCategory(): String = this.name

/**
 * 按 tab 构造拨盘候选：**只列历史记录**，不混入当前页面全量数据。
 *
 * 所有者 2026-11-23 定稿：各 tab 快选一律只列「历史查询数据」，且**全部标 HISTORY**（绿色），
 * 不再把页面上的专辑/艺术家/歌曲列表塞进候选，也不再追加搜索结果里的歌曲名。
 * - SEARCH tab：历史 = 搜索历史词（`search_history`），并合并 `quick_pick_stats[SEARCH]`；
 * - 其它 tab：历史 = 该 category 的 `quick_pick_stats` 记录。
 *
 * 历史为空 ⇒ 候选为空 ⇒ 上层直接不弹窗（不显示一个空拨盘）。
 */
private fun buildQuickPickCandidates(
    tab: LibraryTab,
    historyItems: List<SearchHistoryItem>,
    stats: List<QuickPickStat>
): List<QuickPickCandidate<String>> {
    val category = tab.quickPickCategory()
    val categoryStats = stats.filter { it.category == category }
    // 全部候选都来自「历史」：base 传空 ⇒ 每项 source = HISTORY（显示绿色）
    val historyStats: List<QuickPickStat> = if (tab == LibraryTab.SEARCH) {
        // SEARCH tab 的「历史查询」= 用户搜过的词；与拨盘选择记录按归一化 key 合并，
        // count 取两者较大值（同一次搜索会同时写两处，相加会翻倍）
        val fromHistory = historyItems.map {
            QuickPickStat(
                category = category,
                value = it.query,
                count = it.count,
                lastPickedAt = it.lastSearchedAt
            )
        }
        (fromHistory + categoryStats)
            .groupBy { ArtistSplitter.normalizeKey(it.value) }
            .values
            .map { group -> group.maxByOrNull { it.count }!! }
    } else {
        categoryStats
    }
    val merged = QuickPickSourceMerger.merge(emptyList<String>(), valueOf = { it }, stats = historyStats)
    return QuickPickRanker.rank(merged, historyStats, limit = 7)
}

/**
 * 滚动位置**写回**的防抖窗口（C26）。
 *
 * `rememberLazyGridState` 只在**构造时**读初值，所以写回不需要逐帧进行 ——
 * 等滚动停下 300ms 再写一次，既能保住"记忆位置"的语义，又不至于让滚动期间
 * 每帧都去写 StateFlow 并反向触发页面重组。
 */
private const val SCROLL_SAVE_DEBOUNCE_MS = 300L

/**
 * 曲库子 TAB：在**内容区**左右滑切换 TAB（v2.36.1，用户明确要求「手机端横竖屏都要支持」）。
 *
 * v2.36.4 起：切换改为**真 pager**（跟手 + 相邻页同时平移，见内容区注释），
 * 本 modifier 只负责识别水平拖拽并把位移/松手事件转给外层。
 *
 * ⚠️ 挂在**内容区** `Box(weight(1f))` 上，**不要**挂到 TAB 行上 —— TAB 行自身是横滑条
 * （窄屏必须滑才能看全 8 个 TAB），两者手势会打架。
 *
 * ✅ **空数据也能滑**：内容区 `Box` 的尺寸由 `weight(1f)` 决定，与内部有没有列表无关；
 * 且 `pointerInput` 的命中测试看的是**布局边界**（不看有没有绘制出内容），
 * 故「暂无数据」空态照样接收滑动。⛔ 反过来讲，**别**把这个 modifier 挂到某个具体
 * TAB 的列表上 —— 空态时那个列表可能压根不存在，滑不动。
 *
 * ⚠️ 只识别**水平**拖拽：`detectHorizontalDragGestures` 需先越过水平 touch slop，
 * 且方向判定更偏水平，故列表**竖直**滚动不受影响；内容区里已有的横向滚动子节点
 * （如 Chip 横排、DISCOVER 的维度选择行）位于更深的节点、会**先**消费事件，
 * 因此在那类控件上滑动仍是它们自己响应 —— 这是有意为之。
 *
 * ⚠️ 回调必须用 `rememberUpdatedState` 包裹：`pointerInput` 的 key 是 `enabled`，
 * 只要开关不变协程就不重启，若直接捕获 lambda，会读到**首次组合**那一刻的
 * `activeTab` / `tabPageFraction`（陈旧值）→ 表现为「只能在前两个 TAB 之间来回」。
 * 这是 Compose 里 `pointerInput(Unit)` + 状态闭包的经典陷阱。
 *
 * v2.36.4 真 pager 回调：[onDragStart] 记录起始页索引并清零累积位移，[onDragDelta]
 * 实时上报位移（外层驱动 `tabPageFraction` 让当前页划走、相邻页跟进），
 * [onSettle] 在松手/取消时触发（外层按最近页吸附）。
 */
@Composable
private fun Modifier.libraryTabSwipe(
    enabled: Boolean,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onSettle: () -> Unit,
): Modifier {
    val dragStart by rememberUpdatedState(onDragStart)
    val dragDelta by rememberUpdatedState(onDragDelta)
    val settle by rememberUpdatedState(onSettle)
    return this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                accumulated = 0f
                dragStart()
            },
            onDragEnd = {
                settle()
                accumulated = 0f
            },
            onDragCancel = {
                settle()
                accumulated = 0f
            },
        ) { _, dragAmount ->
            accumulated += dragAmount
            dragDelta(dragAmount)
        }
    }
}

/**
 * 曲库主屏幕（R-3 拆分：ALBUMS/ARTISTS/SONGS/GENRES/YEARS 五个 NAS 浏览 Tab 已迁至
 * ui/screens/library/browse/ 子包；SEARCH/DISCOVER/RADIO 网络音乐 Tab 维持原 library/ 包不动；
 * 详情页复用现役 AlbumDetailScreen/ArtistDetailScreen，本文件仅保留 Tab 容器、
 * 搜索栏与数据过滤派生逻辑，参数签名不变——AppRoot 引用零改动）。
 */
@Composable
fun LibraryScreen(
    albums: List<Album>,
    songs: List<Song>,
    isLoading: Boolean,
    isConnected: Boolean = false,
    genres: List<Genre> = emptyList(),
    favoriteIds: Set<String> = emptySet(),
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    artists: List<Artist> = emptyList(),
    years: List<Int> = emptyList(),
    songsPaging: SongsPagingState = SongsPagingState(),
    searchResults: List<Song> = emptyList(),
    isSearching: Boolean = false,
    /** v2.39.8：全部音乐源（NAS / 本地 / 网络三源）关闭（搜索页显示开启引导，方案 §7.7 第 3 条） */
    noMusicSourceEnabled: Boolean = false,
    // 艺人全量方案：搜索页的打标集合与艺人续页状态（全部带默认值）
    searchUnplayableIds: Set<String> = emptySet(),
    artistPaging: SongsPagingState = SongsPagingState(),
    onLoadMoreArtistSongs: () -> Unit = {},
    onPlayAlbum: (Album) -> Unit,
    onPlaySong: (Song) -> Unit,
    onPlaySongs: (List<Song>) -> Unit,
    onPlayAllSongs: (List<Song>) -> Unit,
    // 队列切换
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    // 本地收藏切换（用于 SongsTab 等本地歌曲列表）
    onToggleFavorite: (Song) -> Unit = {},
    // 加入歌单（弹出歌单选择弹窗）
    onAddToPlaylist: (Song) -> Unit = {},
    onOpenAlbumDetail: ((Album) -> Unit)? = null,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onLoadSongsFirstPage: () -> Unit = {},
    onLoadSongsNextPage: () -> Unit = {},
    /** v2.39.3：歌曲加载失败后的「重试」回调（空态展示错误时使用） */
    onRetrySongsLoad: () -> Unit = {},
    onLoadArtists: () -> Unit = {},
    /** ALBUMS Tab 独立加载态：getAlbums 可能耗时数十秒，isLibraryLoading 只覆盖派发瞬间 */
    isAlbumsLoading: Boolean = false,
    /** ALBUMS 加载失败文案（非空时空态展示原因与重试按钮） */
    albumsErrorText: String? = null,
    onRetryAlbumsLoad: (() -> Unit)? = null,
    /** ARTISTS Tab 独立加载态： artists 走独立 API，不能靠 songs 的 isLoading 表达 */
    isArtistsLoading: Boolean = false,
    /** ARTISTS Tab 加载失败文案（非空时空态展示原因与重试按钮） */
    artistErrorText: String? = null,
    onRetryArtistsLoad: (() -> Unit)? = null,
    onLoadYears: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    historyItems: List<SearchHistoryItem> = emptyList(),
    /** 快捷选择统计（按类别分桶），用于拨盘候选的历史回填与热度排序 */
    quickPickStats: List<QuickPickStat> = emptyList(),
    /** 选中快捷项时写入统计：(category, value) */
    onRecordQuickPick: (String, String) -> Unit = { _, _ -> },
    // 子 Tab 跨导航记忆（由 ViewModel 驱动）
    activeTab: LibraryTab = LibraryTab.ALBUMS,
    onTabSelected: (LibraryTab) -> Unit = {},
    // 搜索关键词（跨导航记忆，由 ViewModel 驱动）
    filterQuery: String = "",
    onFilterQueryChange: (String) -> Unit = {},
    // 搜索来源点亮状态（由 ViewModel 驱动）
    enabledSearchSources: Set<MusicSourceType> = emptySet(),
    /**
     * 设置里**被关闭**的搜索源（方案 §5.3：源总开关与「点亮 chip」是**与**关系）。
     * 这些源此刻不参与搜索 ⇒ chip 不能显示为点亮。
     */
    disabledSearchSources: Set<MusicSourceType> = emptySet(),
    onToggleSearchSource: (MusicSourceType) -> Unit = {},
    onEnableAllSearchSources: () -> Unit = {},
    // ── SEARCH Tab ──
    onSearchTabPlayAll: () -> Unit = {},
    onSearchTabAddAllToQueue: () -> Unit = {},
// ── DISCOVER Tab ──
    discoverDimensions: List<com.nasmusic.tv.ui.screens.library.BrowseDimension> = emptyList(),
    discoverFilteredSongs: List<Song> = emptyList(),
    discoverIsLoading: Boolean = false,
    discoverCurrentDimensionValues: Map<Int, Int> = emptyMap(),
    onDiscoverDimensionChanged: (Int, Int) -> Unit = { _, _ -> },
    onDiscoverPlayAll: () -> Unit = {},
    onDiscoverAddAllToQueue: () -> Unit = {},
    onDiscoverShuffle: () -> Unit = {},
    onDiscoverEnsureLoaded: () -> Unit = {},
    /** 发现页错误消息（非空时展示错误态 + 重试按钮，替代空白列表） */
    discoverError: String? = null,
    onDiscoverRetry: () -> Unit = {},
    // ── RADIO Tab ──
    radioStations: UiState<List<com.nasmusic.tv.data.model.RadioStation>> = UiState.Success(emptyList()),
    radioActiveTag: String? = null,
    radioActiveQuery: String = "",
    onLoadRadioDefault: () -> Unit = {},
    onLoadRadioTag: (String) -> Unit = {},
    onSearchRadio: (String) -> Unit = {},
    onPlayRadioStation: (com.nasmusic.tv.data.model.RadioStation) -> Unit = {},
    // Task 8: 滚动位置记忆（跨 Tab 切换保留）
    albumScrollIndex: Int = 0,
    albumScrollOffset: Int = 0,
    artistScrollIndex: Int = 0,
    artistScrollOffset: Int = 0,
    songScrollIndex: Int = 0,
    songScrollOffset: Int = 0,
    onAlbumScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    onArtistScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    onSongScrollPositionChange: (Int, Int) -> Unit = { _, _ -> },
    // ── 歌曲下载状态 ──
    downloadStates: Map<String, DownloadState> = emptyMap(),
    onDownloadSong: (Song) -> Unit = {},
    onDeleteDownloadSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    var showQuickPick by remember { mutableStateOf(false) }
    var quickPickIndex by remember { mutableIntStateOf(0) }
    // v2.36.4 跟手 pager：tabPageFraction 是**连续页索引**（0..N-1），拖动中由手指位移
    // 驱动，相邻页同时组合并按 (页索引 − fraction) × 页宽 平移 —— 当前页划走、相邻页
    // 跟进，与竖屏播放页「封面 ⟷ 歌词」的跟手效果一致（真机反馈 v2.36.3 的
    // 「先滑出再推入」两段式观感不如播放页，本版对齐）。
    val tabScope = rememberCoroutineScope()
    val contentWidthPx = remember { mutableIntStateOf(0) }
    val tabPageFraction = remember { Animatable(LibraryTab.entries.indexOf(activeTab).toFloat()) }
    var tabDragging by remember { mutableStateOf(false) }
    var tabDragBaseIdx by remember { mutableIntStateOf(0) }
    var tabDragAccumPx by remember { mutableFloatStateOf(0f) }

    // 外部跳转（如搜索弹窗确认后跳 SEARCH tab）：瞬时对齐（弹窗遮挡屏幕，无需动画）。
    // 自己 settle 完成的 onTabSelected 不触发此分支（fraction 已等于目标索引）。
    LaunchedEffect(activeTab) {
        val idx = LibraryTab.entries.indexOf(activeTab).toFloat()
        if (tabPageFraction.targetValue != idx) tabPageFraction.snapTo(idx)
    }

    // 松手 / 取消的统一出口：按「四舍五入到最近页」吸附（拖过半页 → 切换，不足半页 → 弹回）。
    // snapTo 先对齐当前跟手位置（无跳变），再 animateTo 吸附；到位后才 onTabSelected，
    // 期间目标页已由 composedRange 组合，视觉连续。
    val settleTabs: () -> Unit = {
        val w = contentWidthPx.intValue.coerceAtLeast(1).toFloat()
        val current = (tabDragBaseIdx - tabDragAccumPx / w)
            .coerceIn(0f, (LibraryTab.entries.size - 1).toFloat())
        val targetIdx = current.roundToInt().coerceIn(0, LibraryTab.entries.size - 1)
        tabScope.launch {
            tabPageFraction.snapTo(current)
            tabDragging = false
            tabPageFraction.animateTo(targetIdx.toFloat(), tween(LIBRARY_TAB_SLIDE_ANIM_MS))
            onTabSelected(LibraryTab.entries[targetIdx])
        }
    }

    // Task 14: 首次曲库快捷键提示（3s 自动消失）
    val context = LocalContext.current
    val appPrefs = remember { com.nasmusic.tv.data.prefs.AppPreferences.getInstance(context) }
    val showShortcutHint by appPrefs.showLibraryShortcutHint.collectAsState(initial = true)
    var shortcutHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (showShortcutHint) {
            shortcutHintVisible = true
            kotlinx.coroutines.delay(3000)
            shortcutHintVisible = false
            appPrefs.setShowLibraryShortcutHint(false)
        }
    }

    // Task 8: 滚动位置记忆 — 从 ViewModel 恢复初始位置
    val albumListState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = albumScrollIndex,
        initialFirstVisibleItemScrollOffset = albumScrollOffset
    )
    val artistListState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = artistScrollIndex,
        initialFirstVisibleItemScrollOffset = artistScrollOffset
    )
    val songListState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(
        initialFirstVisibleItemIndex = songScrollIndex,
        initialFirstVisibleItemScrollOffset = songScrollOffset
    )

    // 滚动位置变化时保存到 ViewModel。
    //
    // ⚠️ C26：原实现只有一句注释「节流：仅在滚动停止后保存」，代码里**没有任何节流** ——
    // `snapshotFlow` 每帧都会因为 firstVisibleItemIndex/Offset 变化而发射，于是
    // 滚动期间每帧写一次 ViewModel 的 StateFlow，再反向触发页面重组。
    // 现在用 `distinctUntilChanged()`（过滤「值没变」的重复发射）+ `collectLatest { delay }`
    // （新值到达即取消上一次等待 ⇒ 只有滚动**停下** 300ms 后才真正写回）。
    LaunchedEffect(albumListState) {
        snapshotFlow { albumListState.firstVisibleItemIndex to albumListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { pair: Pair<Int, Int> ->
                delay(SCROLL_SAVE_DEBOUNCE_MS)
                onAlbumScrollPositionChange(pair.first, pair.second)
            }
    }
    LaunchedEffect(artistListState) {
        snapshotFlow { artistListState.firstVisibleItemIndex to artistListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { pair: Pair<Int, Int> ->
                delay(SCROLL_SAVE_DEBOUNCE_MS)
                onArtistScrollPositionChange(pair.first, pair.second)
            }
    }
    LaunchedEffect(songListState) {
        snapshotFlow { songListState.firstVisibleItemIndex to songListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { pair: Pair<Int, Int> ->
                delay(SCROLL_SAVE_DEBOUNCE_MS)
                onSongScrollPositionChange(pair.first, pair.second)
            }
    }

    // Tab 切换时触发按需加载
    LaunchedEffect(activeTab) {
        when (activeTab) {
            LibraryTab.SONGS -> onLoadSongsFirstPage()
            LibraryTab.ARTISTS -> onLoadArtists()
            LibraryTab.YEARS -> onLoadYears()
            LibraryTab.RADIO -> onLoadRadioDefault()
            // DISCOVER：幂等加载（有暂存结果/加载中则跳过，切页不重搜）
            LibraryTab.DISCOVER -> onDiscoverEnsureLoaded()
            LibraryTab.SEARCH -> {}  // SearchTab handles its own loading
            else -> {}
        }
    }

    // 搜索时触发服务端搜索
    LaunchedEffect(filterQuery) {
        if (filterQuery.isNotBlank()) {
            onSearch(filterQuery)
        } else {
            onClearSearch()
        }
    }

    // 按当前 tab 类型过滤数据
    // ALBUMS Tab：搜索时用多源搜索结果按专辑聚合；无搜索时用本地加载的专辑
    val filteredAlbums by remember(filterQuery, albums, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) albums
            else {
                // 多源搜索结果中提取专辑（按专辑名 + 艺术家去重聚合，与 MusicMerger 同口径）
                val searchAlbums = searchResults
                    .filter { it.album.isNotBlank() }
                    // ⛔ 与 MusicMerger / T5 同口径：按「专辑名 + 艺术家」聚合，
                    // 同名不同歌手必须是两张卡，否则点进去会把两人的歌混在一起。
                    .groupBy { ArtistSplitter.albumKey(it.album, it.artist) }
                    .map { (key, songs) ->
                        val first = songs.first()
                        // 搜索态专辑由 UI 临时合成：用独立前缀 id + sourceIds 标明来源，
                        // 详情/播放据此从当前搜索结果反查；⛔ 原实现用 albumId 当 id，
                        // 会被 collectAlbumSongsMultiSource 当成 NAS 专辑 id 去请求后端。
                        val searchAlbumId = "search_album_${key.replace(Regex("[\\s:]+"), "_")}"
                        Album(
                            id = searchAlbumId,
                            name = first.album,
                            artist = first.artist,
                            // 搜索结果自带封面时带进详情，避免合成卡永久显示占位符
                            coverUrl = songs.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
                            songCount = songs.size,
                            year = first.year,
                            sourceIds = listOf(searchAlbumId)
                        )
                    }
                // 合并本地过滤的专辑（可能有多源搜索未覆盖的本地专辑）
                // 匹配口径与聚合器一致：子串 + 全拼 + 首字母（`PinyinUtils` 是唯一真相源）
                val localFiltered = albums.filter {
                    PinyinUtils.matchesQuery(it.name, filterQuery) ||
                        PinyinUtils.matchesQuery(it.artist, filterQuery)
                }
                // 去重合并：与 MusicMerger 共用同一口径（专辑名 + 艺术家归一化键），
                // 同名不同歌手保留两张卡，避免搜索态与无搜索态展示数量不一致。
                val seen = mutableSetOf<String>()
                (localFiltered + searchAlbums).filter { album ->
                    seen.add(ArtistSplitter.albumKey(album.name, album.artist))
                }
            }
        }
    }
    // SONGS Tab：有搜索结果时用搜索结果（SearchType.SONG_NAME_ONLY 过滤后的），否则用分页数据
    val displaySongs by remember(filterQuery, songsPaging.songs, searchResults) {
        derivedStateOf {
            if (filterQuery.isNotBlank()) searchResults
            else songsPaging.songs
        }
    }
    // ARTISTS Tab：搜索时用多源搜索结果按艺术家聚合；无搜索时用本地加载的艺术家
    val filteredArtists by remember(filterQuery, artists, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) {
                // 无搜索时也按 normalizeKey 去重，防止数据源因不可见字符导致重复
                val seen = mutableSetOf<String>()
                artists.filter { seen.add(ArtistSplitter.normalizeKey(it.name)) }
            } else {
                // 多源搜索结果中提取艺术家：
                // 必须先按 ArtistSplitter 拆分合唱名（"古天乐/萱萱" → 古天乐、萱萱），
                // 否则合唱名会变成一个独立艺术家块，且详情页按整串匹配永远查不到歌
                //
                // ⚠️ 这里必须再 filter 一次：聚合结果按**整串歌手名**命中，拆出来的第二个名字
                //    （上例的「萱萱」）并不匹配关键词，不过滤就会冒出一堆无关艺术家。
                //    匹配口径统一走 `PinyinUtils`（全拼 + 首字母），⛔ 别再另写一套判据 ——
                //    旧版这里缺全拼，`zhoujielun` 能搜到歌却搜不到「周杰伦」，艺术家网格全空。
                val searchArtists = MusicMerger.buildArtistsFromSongs(searchResults, "search_artist_")
                    .filter { PinyinUtils.matchesQuery(it.name, filterQuery) }
                // 合并本地过滤的艺术家
                val localFiltered = artists.filter {
                    PinyinUtils.matchesQuery(it.name, filterQuery)
                }
                // 归一化去重（NFKC + trim + 小写），同名不同写法只保留一块
                val seen = mutableSetOf<String>()
                (localFiltered + searchArtists).filter { artist ->
                    seen.add(ArtistSplitter.normalizeKey(artist.name))
                }.distinctBy { ArtistSplitter.normalizeKey(it.name) }
            }
        }
    }

    // 艺术家搜索时的歌曲映射：多源搜索结果按「拆分后的艺术家名」分组，与本地 artistSongsMap 合并
    val displayArtistSongsMap by remember(filterQuery, searchResults, artistSongsMap) {
        derivedStateOf {
            if (filterQuery.isBlank()) artistSongsMap
            else {
                val merged = artistSongsMap.toMutableMap()
                for (song in searchResults) {
                    for (name in ArtistSplitter.split(song.artist)) {
                        val key = ArtistSplitter.normalizeKey(name)
                        if (key.isBlank()) continue
                        val existing = merged[key].orEmpty()
                        // 去重合并
                        if (song.id !in existing.map { it.id }.toSet()) {
                            merged[key] = existing + song
                        }
                    }
                }
                merged
            }
        }
    }

    // 播放全部按钮的歌曲列表：按当前 Tab + 搜索状态动态计算
    val playAllSongs by remember(activeTab, filterQuery, songs, displaySongs, searchResults, filteredAlbums, filteredArtists, displayArtistSongsMap) {
        derivedStateOf {
            when (activeTab) {
                LibraryTab.ALBUMS -> {
                    if (filterQuery.isNotBlank()) {
                        val filteredAlbumIds = filteredAlbums.map { it.id }.toSet()
                        val albumSongs = songs.filter { it.albumId in filteredAlbumIds }
                        if (albumSongs.isNotEmpty()) albumSongs else searchResults
                    } else {
                        songs
                    }
                }
                LibraryTab.ARTISTS -> {
                    val listed = filteredArtists.flatMap { displayArtistSongsMap[ArtistSplitter.normalizeKey(it.name)].orEmpty() }
                    if (listed.isNotEmpty()) listed
                    else if (searchResults.isNotEmpty()) searchResults
                    else songs
                }
                LibraryTab.SONGS -> displaySongs
                LibraryTab.SEARCH, LibraryTab.DISCOVER, LibraryTab.RADIO -> songs
                else -> songs  // GENRES, YEARS
            }
        }
    }
    val showPlayAll = activeTab != LibraryTab.SEARCH && activeTab != LibraryTab.DISCOVER && activeTab != LibraryTab.RADIO && playAllSongs.isNotEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        // v2.36.0 竖屏（方案 §4.3 / P0-14）：页 padding 32→16；标题/搜索框/TAB 由「一行」改「三行」
        val uiMode = LocalUiMode.current
        val isPhonePortrait = uiMode == UiMode.PhonePortrait
        Column(
            modifier = Modifier.fillMaxSize().padding(
                pagePadding()
            )
        ) {
            // 顶部标题 + TAB + 播放全部
            val libraryTitle: @Composable (Modifier) -> Unit = { m ->
                Text(
                    text = stringResource(R.string.nav_library),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.display(),
                    modifier = m
                )
            }
            val playAllChip: @Composable () -> Unit = {
                if (showPlayAll) {
                    Box(modifier = Modifier.widthIn(min = 80.dp)) {
                        ButtonChip(
                            text = stringResource(R.string.common_play_all),
                            onClick = { onPlayAllSongs(playAllSongs) }
                        )
                    }
                }
            }
            val searchField: @Composable (Modifier) -> Unit = { m ->
                Row(modifier = m, verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        query = filterQuery,
                        placeholder = stringResource(R.string.library_search_placeholder),
                        onOpenSearch = { showSearchDialog = true },
                        onClear = { onFilterQueryChange("") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FocusableSurface(
                        onClick = {
                            quickPickIndex = 0
                            showQuickPick = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                        contentColor = NasMusicColors.Primary,
                        focusedContentColor = NasMusicColors.Primary
                    ) {
                        // 图标与播放页「快捷切换歌曲」按钮一致（Icons.Filled.QueueMusic）
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = stringResource(R.string.qp_button_label),
                            tint = NasMusicColors.Primary,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .size(20.dp)
                        )
                    }
                }
            }
            // TAB 切换（可横向滑动——手机窄屏滑动浏览全部 tab）
            val tabsRow: @Composable (Modifier) -> Unit = { m ->
                Row(
                    modifier = m,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryTab.entries.forEach { tab ->
                        val selected = tab == activeTab
                        FocusableSurface(
                            onClick = {
                                // 点击 TAB 行：动画吸附到目标页（与手势松手同一条动画路径）
                                val targetIdx = LibraryTab.entries.indexOf(tab)
                                if (targetIdx >= 0) {
                                    tabScope.launch {
                                        tabPageFraction.animateTo(
                                            targetIdx.toFloat(),
                                            tween(LIBRARY_TAB_SLIDE_ANIM_MS),
                                        )
                                        onTabSelected(tab)
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            focusedScale = 1.05f,
                            animationDurationMs = 200,
                            containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.2f) else Color.Transparent,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                            contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                            focusedContentColor = NasMusicColors.Primary
                        ) {
                            Text(
                                text = stringResource(tab.titleRes),
                                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            if (isPhonePortrait) {
                // 竖屏：标题行（标题 + 播放全部）→ 搜索框整行 → TAB 横滑行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    libraryTitle(Modifier.weight(1f))
                    playAllChip()
                }
                Spacer(modifier = Modifier.height(8.dp))
                searchField(Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                tabsRow(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    libraryTitle(Modifier.padding(end = 24.dp))

                    tabsRow(Modifier.weight(1f).horizontalScroll(rememberScrollState()))

                    // 搜索栏 + 播放全部（固定宽度区，避免挤压可滚动 TAB）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        searchField(Modifier.width(240.dp))

                        Spacer(modifier = Modifier.width(12.dp))

                        playAllChip()
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 内容区域（weight(1f) 限制高度，让内部可滚动列表正常工作）
            //
            // v2.36.1：手机（竖屏 + 横屏）在**内容区**左右滑切换子 TAB —— 用户明确要求
            // 「手机端横竖屏都要支持」，故此处是**手机专属**能力。
            // ⚠️ 按 `docs/conventions-adaptive-ui.md` 的 B1 硬规则，这里**显式读 `UiMode.TV`**
            // 并写明理由（而不是写 `!= UiMode.PhonePortrait`）：需求覆盖的是「竖屏 + 横屏」
            // 两个手机形态，TV 端**不加**手势，保持逐字不变（遥控器也没有横向滑动手势）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { contentWidthPx.intValue = it.width }
                    // v2.36.4 真 pager：拖动中当前页划走、相邻页跟进（见上方说明）
                    .libraryTabSwipe(
                        enabled = uiMode == UiMode.PhonePortrait || uiMode == UiMode.PhoneLandscape,
                        onDragStart = {
                            tabDragging = true
                            tabDragBaseIdx = tabPageFraction.value.roundToInt()
                                .coerceIn(0, LibraryTab.entries.size - 1)
                            tabDragAccumPx = 0f
                        },
                        onDragDelta = { delta ->
                            tabDragAccumPx += delta
                        },
                        onSettle = { settleTabs() },
                    )
            ) {
            // v2.36.4：子 TAB 切换改为**真 pager**（对齐竖屏播放页「封面 ⟷ 歌词」的跟手效果）：
            // 页面位置由连续索引 `tabPageFraction` 唯一决定 —— 拖动中当前页与相邻页
            // **同时组合**并实时平移（当前页划走、相邻页跟进），松手后吸附到最近页。
            // ⚠️ 相邻页常驻组合的代价：最多同时组合 2 个 Tab 子树。各 Tab 的
            // LaunchedEffect 加载均幂等（loadRadioDefault / onDiscoverEnsureLoaded
            // 有暂存跳过；SONGS/ARTISTS/YEARS 首页加载可重复触发）。
            // ⚠️ 页内容 lambda 必须用 `LibraryTab.entries[pageIdx]`（连续索引）而非
            // 外层 `activeTab`，否则拖动中相邻页会渲染成当前页内容。
            // fraction 读取器：拖动中 = 起始页索引 − 累积位移/页宽（左滑增大）；非拖动 = Animatable 当前值。
            // ⚠️ 只在 draw 相位（graphicsLayer）与 derivedStateOf 中读取 —— 不在组合期直接读，
            // 拖动/吸附动画期间仅 draw 失效，不触发逐帧重组。
            val pagerFractionReader: () -> Float = {
                if (tabDragging) {
                    (tabDragBaseIdx - tabDragAccumPx / contentWidthPx.intValue.coerceAtLeast(1))
                        .coerceIn(0f, (LibraryTab.entries.size - 1).toFloat())
                } else {
                    tabPageFraction.value
                }
            }
            // 参与组合的页：fraction 覆盖到的页（当前页 + 拖动方向相邻页）。
            // derivedStateOf：仅当页区间变化（跨页）时才重组，拖动中逐帧位移不重组。
            val composedRange by remember {
                derivedStateOf {
                    val f = pagerFractionReader()
                    maxOf(0, floor(f).toInt())..minOf(LibraryTab.entries.size - 1, ceil(f).toInt())
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                for (pageIdx in composedRange) {
                    val targetTab = LibraryTab.entries[pageIdx]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // v2.36.4 修复：平移方向必须是 (页索引 − fraction) ——
                            // 手指左划 → fraction 增大 → 当前页向左划走、右邻页从右侧跟入
                            // （写成 (fraction − 页索引) 会整体反向，真机反馈已纠正）。
                            .graphicsLayer {
                                translationX = (pageIdx - pagerFractionReader()) * size.width
                            },
                    ) {
                        // SEARCH, DISCOVER, RADIO tabs handle their own loading/empty states
                        when (targetTab) {
                LibraryTab.SEARCH -> {
                    SearchTab(
                        searchKeyword = filterQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        noMusicSourceEnabled = noMusicSourceEnabled,
                        unplayableIds = searchUnplayableIds,
                        artistPaging = artistPaging,
                        onLoadMoreArtistSongs = onLoadMoreArtistSongs,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        enabledSources = enabledSearchSources,
                        disabledSearchSources = disabledSearchSources,
                        onToggleSource = onToggleSearchSource,
                        onEnableAllSources = onEnableAllSearchSources,
                        onPlaySong = onPlaySong,
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        onPlayAll = onSearchTabPlayAll,
                        onAddAllToQueue = onSearchTabAddAllToQueue,
                        downloadStates = downloadStates,
                        onDownloadSong = onDownloadSong
                    )
                }
                LibraryTab.DISCOVER -> {
                    // 搜索时就地出结果，但**完全沿用发现页自己的展示方式**（语种/维度行 + 歌曲网格），
                    // 只把网格的数据源换成搜索结果 —— ⛔ 不再整页换成搜索页的 UI（所有者 2026-11-24）
                    val searching = filterQuery.isNotBlank()
                    DiscoverTab(
                        dimensions = discoverDimensions,
                        filteredSongs = if (searching) searchResults else discoverFilteredSongs,
                        // 搜索期间不要被发现页自身的加载态盖掉结果
                        isLoading = if (searching) false else discoverIsLoading,
                        favoriteIds = favoriteIds,
                        queueSongIds = queueSongIds,
                        currentDimensionValues = discoverCurrentDimensionValues,
                        onDimensionChanged = onDiscoverDimensionChanged,
                        // 播放全部 / 随机播放在搜索时都作用于搜索结果
                        onPlayAll = if (searching) ({ onPlaySongs(searchResults) }) else onDiscoverPlayAll,
                        onShuffle = if (searching) ({ onPlaySongs(searchResults.shuffled()) }) else onDiscoverShuffle,
                        onAddAllToQueue = onDiscoverAddAllToQueue,
                        errorMessage = if (searching) null else discoverError,
                        onRetry = onDiscoverRetry,
                        // 发现页点歌曲播放也计入「发现」类别历史
                        //（所有者 2026-11-23 第 8 轮裁定：点歌曲就计入）
                        onPlaySong = { song ->
                            onRecordQuickPick(
                                LibraryTab.DISCOVER.quickPickCategory(),
                                song.title
                            )
                            onPlaySong(song)
                        },
                        onToggleFavorite = onToggleFavorite,
                        onToggleQueue = onToggleQueue,
                        onAddToPlaylist = onAddToPlaylist,
                        downloadStates = downloadStates,
                        onDownloadSong = onDownloadSong,
                        // 搜索无结果时不能沿用「选择筛选条件」的文案
                        emptyHintRes = if (searching) {
                            R.string.library_no_search_results
                        } else {
                            R.string.discover_pick_dimension
                        }
                    )
                }
                LibraryTab.RADIO -> {
                    // 顶部搜索框有词时就地过滤电台列表 —— **沿用电台页自己的展示方式**，
                    // ⛔ 不用搜索页 UI 替换电台页（所有者 2026-11-24）。
                    // 电台自身搜索框（onSearchRadio）仍独立工作，会重新拉取电台列表。
                    val radioQuery = filterQuery.trim()
                    val visibleStations = if (radioQuery.isBlank()) {
                        radioStations
                    } else {
                        when (val cur = radioStations) {
                            is UiState.Success -> UiState.Success(
                                cur.data.filter { it.name.contains(radioQuery, ignoreCase = true) }
                            )
                            else -> cur
                        }
                    }
                    RadioTab(
                        radioStations = visibleStations,
                        radioActiveTag = radioActiveTag,
                        radioActiveQuery = radioActiveQuery,
                        onLoadDefault = onLoadRadioDefault,
                        onLoadTag = onLoadRadioTag,
                        // 电台 tab 自带搜索框（不走顶部共享 searchField）⇒ 必须在此处补录，
                        // 否则 RADIO 桶永远为空
                        onSearch = { query ->
                            if (query.isNotBlank()) {
                                onRecordQuickPick(
                                    LibraryTab.RADIO.quickPickCategory(),
                                    query.trim()
                                )
                            }
                            onSearchRadio(query)
                        },
                        onPlayStation = onPlayRadioStation
                    )
                }
                else -> {
                    // 专辑/艺术家/歌曲 tab 搜索时有结果直接展示（多源搜索，不依赖 NAS 连接）
                    val hasSearchResults = filterQuery.isNotBlank() && searchResults.isNotEmpty()
                    val showSearchContent = hasSearchResults && (
                        targetTab == LibraryTab.ALBUMS ||
                        targetTab == LibraryTab.ARTISTS ||
                        targetTab == LibraryTab.SONGS
                    )
                    if (showSearchContent) {
                        // 搜索模式：直接展示搜索结果，跳过加载/连接/空状态检查
                        when (targetTab) {
                            LibraryTab.ALBUMS -> AlbumsTab(
                                albums = filteredAlbums,
                                onPlayAlbum = onPlayAlbum,
                                onOpenAlbumDetail = onOpenAlbumDetail,
                                listState = albumListState
                            )
                            LibraryTab.ARTISTS -> ArtistsTab(
                                artists = filteredArtists,
                                artistSongsMap = displayArtistSongsMap,
                                onPlaySongs = onPlaySongs,
                                onOpenArtistDetail = onOpenArtistDetail,
                                listState = artistListState
                            )
                            LibraryTab.SONGS -> SongsTab(
                                songs = displaySongs,
                                favoriteIds = favoriteIds,
                                songsPaging = songsPaging,
                                isSearching = isSearching,
                                // 搜索结果数 ≠ NAS 全库数，标题只报结果数，不拼 totalCount
                                showTotalCount = false,
                                onLoadMore = onLoadSongsNextPage,
                                listState = songListState,
                                onPlaySong = onPlaySong,
                                queueSongIds = queueSongIds,
                                onToggleQueue = onToggleQueue,
                                onToggleFavorite = onToggleFavorite,
                                onAddToPlaylist = onAddToPlaylist,
                                downloadStates = downloadStates,
                                onDownloadSong = onDownloadSong,
                                onDeleteDownloadSong = onDeleteDownloadSong
                            )
                            else -> {}
                        }
                    } else if (isLoading ||
                        (targetTab == LibraryTab.ALBUMS && isAlbumsLoading) ||
                        (targetTab == LibraryTab.ARTISTS && isArtistsLoading)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 顶部骨架标题条
                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NasMusicColors.SurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // 按 Tab 类型显示对应骨架
                            when (targetTab) {
                                LibraryTab.ALBUMS -> AlbumSkeletonGrid()
                                LibraryTab.ARTISTS -> ArtistSkeletonGrid()
                                else -> AlbumSkeletonGrid()  // 默认专辑骨架
                            }
                        }

                    } else {
                        // 有数据时直接展示（无论 NAS 是否连接，本地/百度/网络数据同样展示）
                        when (targetTab) {
                            LibraryTab.ALBUMS -> if (filteredAlbums.isEmpty()) {
                                EmptyHint(
                                    isConnected = isConnected,
                                    tab = LibraryTab.ALBUMS,
                                    errorText = albumsErrorText,
                                    onRetry = onRetryAlbumsLoad,
                                    errorTitleRes = R.string.load_albums_error
                                )
                            } else {
                                AlbumsTab(
                                    albums = filteredAlbums,
                                    onPlayAlbum = onPlayAlbum,
                                    onOpenAlbumDetail = onOpenAlbumDetail,
                                    listState = albumListState
                                )
                            }
                            LibraryTab.ARTISTS -> if (filteredArtists.isEmpty()) {
                                EmptyHint(
                                    isConnected = isConnected,
                                    tab = LibraryTab.ARTISTS,
                                    errorText = artistErrorText,
                                    onRetry = onRetryArtistsLoad,
                                    errorTitleRes = R.string.library_artists_load_failed
                                )
                            } else {
                                ArtistsTab(
                                    artists = filteredArtists,
                                    artistSongsMap = displayArtistSongsMap,
                                    onPlaySongs = onPlaySongs,
                                    onOpenArtistDetail = onOpenArtistDetail,
                                    listState = artistListState
                                )
                            }
                            LibraryTab.SONGS -> if (displaySongs.isEmpty() && songsPaging.songs.isEmpty()) {
                                // v2.39.3：把加载失败的具体原因（服务端 code/msg 或解析失败）
                                // 带到空态上，并提供「重试」入口；未失败时行为与之前完全一致。
                                EmptyHint(
                                    isConnected = isConnected,
                                    tab = LibraryTab.SONGS,
                                    errorText = songsPaging.error,
                                    onRetry = onRetrySongsLoad
                                )
                            } else {
                                SongsTab(
                                    songs = displaySongs,
                                    favoriteIds = favoriteIds,
                                    songsPaging = songsPaging,
                                    isSearching = isSearching,
                                    // G8-02：有搜索词时结果数 ≠ NAS 全库数。另一个搜索分支
                                    // 已传 false，这里漏传导致「共 0 / 5000 首」的误导标题。
                                    showTotalCount = filterQuery.isBlank(),
                                    onLoadMore = onLoadSongsNextPage,
                                    listState = songListState,
                                    onPlaySong = onPlaySong,
                                    queueSongIds = queueSongIds,
                                    onToggleQueue = onToggleQueue,
                                    onToggleFavorite = onToggleFavorite,
                                    onAddToPlaylist = onAddToPlaylist,
                                    downloadStates = downloadStates,
                                    onDownloadSong = onDownloadSong,
                                    onDeleteDownloadSong = onDeleteDownloadSong
                                )
                            }
                            LibraryTab.GENRES -> GenresTab(
                                genres = genres,
                                // 点流派 = 发起一次「按流派查歌曲」的查询 ⇒ 计入 GENRES 历史。
                                // ⛔ 不补这个写入点，GENRES 桶只能靠顶部搜索框填充，实际几乎
                                //    永远为空 ⇒ 拨盘无内容可列。
                                onSongsByGenre = onSongsByGenre?.let { original ->
                                    { name: String, onResult: (List<Song>) -> Unit ->
                                        onRecordQuickPick(LibraryTab.GENRES.quickPickCategory(), name)
                                        original(name, onResult)
                                    }
                                },
                                onPlaySongs = onPlaySongs,
                                // 搜索时就地出结果，用流派页自己的卡片网格样式追加在卡片之后
                                //（⛔ 不整页换成搜索页 UI，所有者 2026-11-24）
                                searchResults = if (filterQuery.isNotBlank()) searchResults else emptyList(),
                                onPlaySong = onPlaySong
                            )
                            LibraryTab.YEARS -> YearsTab(
                                years = years,
                                // 同上：点年代也是一次查询
                                onSongsByYear = onSongsByYear?.let { original ->
                                    { year: Int, yearEnd: Int, onResult: (List<Song>) -> Unit ->
                                        onRecordQuickPick(
                                            LibraryTab.YEARS.quickPickCategory(),
                                            year.toString()
                                        )
                                        original(year, yearEnd, onResult)
                                    }
                                },
                                onPlaySongs = onPlaySongs,
                                // 同上：年代页保留年份卡片，结果用本页卡片样式追加
                                searchResults = if (filterQuery.isNotBlank()) searchResults else emptyList(),
                                onPlaySong = onPlaySong
                            )
                            else -> {}  // SEARCH, DISCOVER, RADIO handled above
                        }
                    }
                }
                        }   // when (targetTab)
                    }   // 页面 graphicsLayer Box
                }   // for (pageIdx in composedRange)
            }   // pager 容器 Box

                // Task 14: 遥控器快捷键提示浮层（3s 自动消失）
                androidx.compose.animation.AnimatedVisibility(
                    visible = shortcutHintVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NasMusicColors.SurfaceVariant)
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_remote_hint),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button(),
                            maxLines = 1
                        )
                    }
                }
            }   // AnimatedVisibility

        }   // 内容区域 Box

        // 搜索键盘弹窗
        if (showSearchDialog) {
            TextInputDialog(
                title = stringResource(R.string.common_search),
                hint = stringResource(R.string.library_search_hint),
                initialValue = filterQuery,
                onConfirm = { query ->
                    onFilterQueryChange(query)
                    // 手动搜索同样计入当前 tab 的快捷选择统计（与拨盘选择共用同一热度口径）
                    if (query.isNotBlank()) onRecordQuickPick(activeTab.quickPickCategory(), query.trim())
                    showSearchDialog = false
                },
                onDismiss = { showSearchDialog = false },
                showQrCode = true,
                showHistory = true,
                historyItems = historyItems,
                onHistorySelect = { query ->
                    onFilterQueryChange(query)
                    if (query.isNotBlank()) onRecordQuickPick(activeTab.quickPickCategory(), query.trim())
                    showSearchDialog = false
                }
            )
        }

        // 快捷选择拨盘（搜索快速填写）
        if (showQuickPick) {
            val candidates = buildQuickPickCandidates(
                tab = activeTab,
                historyItems = historyItems,
                stats = quickPickStats
            )
            if (candidates.isEmpty()) {
                // 本 tab 还没有任何历史查询记录 ⇒ 拨盘无内容可列（⛔ 不回退去列当前页面数据）。
                // 与其静默关闭让用户以为按钮失灵，直接打开搜索输入框：这次用键盘搜一次，
                // 确认时会写入本 tab 的 quick_pick_stats，下次点按钮就有快选内容了。
                showQuickPick = false
                showSearchDialog = true
            } else {
                ArcDialDialog(
                    items = candidates,
                    selectedIndex = quickPickIndex.coerceIn(0, candidates.size - 1),
                    onSelectedIndexChange = { quickPickIndex = it },
                    onConfirm = { _, candidate ->
                        val value = candidate.value
                        onFilterQueryChange(value)
                        onRecordQuickPick(activeTab.quickPickCategory(), value)
                    },
                    onDismiss = { showQuickPick = false },
                    label = { it.value },
                    key = { index, candidate -> "$index:${candidate.value}" },
                    dPadMode = LocalUiMode.current == UiMode.TV,
                    anchor = ArcAnchor.RIGHT
                )
            }
        }

    }
}
