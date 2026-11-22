package com.nasmusic.tv.ui.screens.library.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.adaptiveColumns
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.PinyinUtils
import kotlinx.coroutines.launch

/** 艺术家网格 Tab（原 LibraryScreen ArtistsTab，逻辑逐行搬迁） */
@Composable
internal fun ArtistsTab(
    artists: List<Artist>,
    artistSongsMap: Map<String, List<Song>> = emptyMap(),
    onPlaySongs: (List<Song>) -> Unit,
    onOpenArtistDetail: ((String) -> Unit)? = null,
    listState: LazyGridState = rememberLazyGridState()
) {
    // 渲染层兜底：必须与上游同一口径（normalizeKey）。按 id 去重挡不住
    // 「同一个人来自 NAS / 本地 / 百度且 id 不同」的重复，只是看上去像兜底的空转。
    val dedupedArtists = remember(artists) { artists.distinctBy { ArtistSplitter.normalizeKey(it.name) } }
    val firstItemFocusRequester = remember { FocusRequester() }
    val letterFocusRequester = remember { FocusRequester() }
    var focusedGridIndex by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // A-Z 分组：按首字母分组，保留组内排序
    val groupedItems = remember(dedupedArtists) {
        val items = mutableListOf<Pair<Char?, Artist>>() // null = header
        dedupedArtists.groupBy { PinyinUtils.getGroupLetter(it.name) }
            .toSortedMap(compareBy { if (it == '#') '{' else it })
            .forEach { (letter, groupArtists) ->
                items.add(letter to groupArtists.first()) // header 标记
                groupArtists.forEach { artist ->
                    items.add(null to artist) // null key = 数据行
                }
            }
        items
    }

    // 分组 header 的 index 映射（letter → grid index），供侧边索引用
    val groupHeaderIndices = remember(groupedItems) {
        val map = mutableMapOf<Char, Int>()
        var gridIndex = 0
        groupedItems.forEach { (letter, artist) ->
            if (letter != null) {
                map[letter] = gridIndex
            }
            gridIndex++
        }
        map
    }

    Column {
        Text(
            text = stringResource(R.string.library_artists_count, dedupedArtists.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = BrowseTokens.headerCountGap)
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            val items = listState.layoutInfo.visibleItemsInfo
                            val info = items.firstOrNull { it.index == focusedGridIndex }
                            val lastColumn = items.maxOfOrNull { it.column } ?: 0
                            if (info != null && info.column == lastColumn) {
                                letterFocusRequester.requestFocus()
                                true
                            } else false
                        } else false
                    }
            ) {
                LazyVerticalGrid(
                    state = listState,
                    columns = GridCells.Fixed(adaptiveColumns(6, 3, 6)),
                    modifier = Modifier.fillMaxSize().padding(end = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap),
                    verticalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap)
                ) {
                    groupedItems.forEachIndexed { index, (letter, artist) ->
                        if (letter != null) {
                            // 分组 header：横跨整行
                            item(key = "header_$letter", span = { GridItemSpan(maxLineSpan) }) {
                                LetterGroupHeader(letter)
                            }
                        } else {
                            // 数据行（key 加 index 防重名）
                            item(key = "artist_${index}_${artist.id}", span = { GridItemSpan(1) }) {
                                val artistSongs = artistSongsMap[ArtistSplitter.normalizeKey(artist.name)] ?: emptyList()
                                // 曲目数唯一口径：artist.songCount 由 updateArtistSongCounts
                                // 用 NAS + 本地 + 百度全量歌曲反向统计。artistSongsMap 只含
                                // NAS 已分页加载的歌曲，直接取 size 会让本地/百度艺术家恒显 0。
                                // map 仍用于「是否有歌可播」和主要流派计算。
                                val songCount = artist.songCount
                                // Task 10: 计算专辑数和主要流派
                                val albumCountForArtist = artist.albumCount
                                val primaryGenreForArtist = remember(artistSongs) {
                                    artistSongs.mapNotNull { it.genre }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                                }
                                Box(Modifier.onFocusChanged { if (it.isFocused) focusedGridIndex = index }) {
                                    ArtistCard(
                                        artist = artist.name,
                                        // 封面兜底：网络源合成卡片（search_artist_*）的 coverUrl 恒为 null，
                                        // 但名下歌曲（艺人全量展开 getArtistSongs）自带封面。与专辑卡
                                        // （LibraryScreen.kt:430）同口径，就地取第一首有封面歌的封面。
                                        coverUrl = artist.coverUrl
                                            ?: artistSongs.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl,
                                        songCount = songCount,
                                        albumCount = albumCountForArtist,
                                        primaryGenre = primaryGenreForArtist,
                                        onClick = {
                                            if (onOpenArtistDetail != null) {
                                                onOpenArtistDetail(artist.name)
                                            } else if (artistSongs.isNotEmpty()) {
                                                onPlaySongs(artistSongs)
                                            }
                                        },
                                        // C-2 撤回：原 {{ }} 写法在 if 分支中按“块+尾部 lambda”解析，本就可用；
                                        // 改用括号包裹的 lambda 表达式，语义相同且更清晰
                                        focusRequester = if (index == 1) firstItemFocusRequester else null
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 侧边 A-Z 索引条
            val activeLetters = remember(groupedItems) {
                groupedItems.mapNotNull { (letter, _) -> letter }.toSet()
            }
            // 当前字母 = 可见区域内「最后一个」分组头：滚到某组中部时该组的 header
            // 可能已划出屏幕，只取第一个可见 item 会让高亮变 null。
            val currentLetter by remember {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { info ->
                            (info.key as? String)?.takeIf { it.startsWith("header_") }
                                ?.removePrefix("header_")?.firstOrNull()
                        }
                        .lastOrNull()
                }
            }
            SideLetterIndex(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                currentLetter = currentLetter,
                activeLetters = activeLetters,
                contentFocusRequester = firstItemFocusRequester,
                letterFocusRequester = letterFocusRequester,
                onLetterSelect = { letter ->
                    groupHeaderIndices[letter]?.let { idx ->
                        scope.launch { listState.scrollToItem(idx) }
                    }
                }
            )
        }
    }
}
