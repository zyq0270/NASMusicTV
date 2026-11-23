package com.nasmusic.tv.ui.screens.library.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.adaptiveColumns
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/** 流派网格 Tab（原 LibraryScreen GenresTab，逻辑逐行搬迁） */
@Composable
internal fun GenresTab(
    genres: List<Genre>,
    onSongsByGenre: ((String, (List<Song>) -> Unit) -> Unit)? = null,
    onPlaySongs: (List<Song>) -> Unit,
    /** 搜索结果：非空时在本页卡片网格下方以同一套卡片样式追加（⛔ 不换搜索页 UI） */
    searchResults: List<Song> = emptyList(),
    onPlaySong: ((Song) -> Unit)? = null
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current
    // 空结果反馈：取歌为空时给一条可见提示，不再静默无反应
    var emptyHint by remember { mutableStateOf(false) }

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

    Column {
        Text(
            text = stringResource(R.string.library_genres_count, genres.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = BrowseTokens.headerCountGap)
        )
        if (emptyHint) {
            Text(
                text = stringResource(R.string.library_style_no_songs),
                color = NasMusicColors.Warning,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(bottom = BrowseTokens.headerCountGap)
            )
        }
        if (genres.isEmpty() && searchResults.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_genres),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(adaptiveColumns(4, 2, 3)),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap),
                verticalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap)
            ) {
                itemsIndexed(genres, key = { _, it -> it.name }) { index, genre ->
                    MediaCardShell(
                        onClick = {
                            if (onSongsByGenre != null) {
                                onSongsByGenre(genre.name) { songs ->
                                    if (songs.isNotEmpty()) {
                                        emptyHint = false
                                        onPlaySongs(songs)
                                    } else {
                                        emptyHint = true
                                    }
                                }
                            }
                        },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = genre.name,
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.library_song_count_suffix, genre.songCount),
                                color = LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                    }
                }

                // 搜索结果（顶部搜索框有词时由 LibraryScreen 传入）：用**本页卡片网格的样式**
                // 追加在流派卡片之后 —— ⛔ 不整页换成搜索页的展示方式（所有者 2026-11-24）
                if (searchResults.isNotEmpty()) {
                    item(key = "search_header", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(
                                R.string.library_search_results_count,
                                searchResults.size
                            ),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.subtitle(),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(searchResults, key = { _, song -> "s_${song.id}" }) { _, song ->
                        MediaCardShell(onClick = { onPlaySong?.invoke(song) }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = song.title,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = FontSize.button(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = song.artist,
                                    color = LocalFocusableContentColor.current,
                                    fontSize = FontSize.body(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 年代网格 Tab（原 LibraryScreen YearsTab，逻辑逐行搬迁） */
@Composable
internal fun YearsTab(
    years: List<Int>,
    onSongsByYear: ((Int, Int, (List<Song>) -> Unit) -> Unit)? = null,
    onPlaySongs: (List<Song>) -> Unit,
    /** 搜索结果：非空时在本页卡片网格下方以同一套卡片样式追加（⛔ 不换搜索页 UI） */
    searchResults: List<Song> = emptyList(),
    onPlaySong: ((Song) -> Unit)? = null
) {
    val listState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current
    // 空结果反馈：取歌为空时给一条可见提示，不再静默无反应
    var emptyHint by remember { mutableStateOf(false) }

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

    Column {
        Text(
            text = stringResource(R.string.library_years_count, years.size),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = BrowseTokens.headerCountGap)
        )
        if (emptyHint) {
            Text(
                text = stringResource(R.string.library_style_no_songs),
                color = NasMusicColors.Warning,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(bottom = BrowseTokens.headerCountGap)
            )
        }
        if (years.isEmpty() && searchResults.isEmpty()) {
            Text(
                text = stringResource(R.string.library_no_years),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                state = listState,
                columns = GridCells.Fixed(adaptiveColumns(5, 2, 3)),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap),
                verticalArrangement = Arrangement.spacedBy(BrowseTokens.gridGap)
            ) {
                itemsIndexed(years, key = { _, it -> it }) { index, year ->
                    MediaCardShell(
                        onClick = {
                            // 点击年份时按需加载该年份歌曲
                            if (onSongsByYear != null) {
                                onSongsByYear(year, year) { songs ->
                                    if (songs.isNotEmpty()) {
                                        emptyHint = false
                                        onPlaySongs(songs)
                                    } else {
                                        emptyHint = true
                                    }
                                }
                            }
                        },
                        focusRequester = if (index == 0) firstItemFocusRequester else null
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$year",
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.title()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.library_tap_to_play),
                                color = LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                    }
                }

                // 搜索结果（顶部搜索框有词时由 LibraryScreen 传入）：用**本页卡片网格的样式**
                // 追加在流派卡片之后 —— ⛔ 不整页换成搜索页的展示方式（所有者 2026-11-24）
                if (searchResults.isNotEmpty()) {
                    item(key = "search_header", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(
                                R.string.library_search_results_count,
                                searchResults.size
                            ),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.subtitle(),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(searchResults, key = { _, song -> "s_${song.id}" }) { _, song ->
                        MediaCardShell(onClick = { onPlaySong?.invoke(song) }) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = song.title,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = FontSize.button(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = song.artist,
                                    color = LocalFocusableContentColor.current,
                                    fontSize = FontSize.body(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
