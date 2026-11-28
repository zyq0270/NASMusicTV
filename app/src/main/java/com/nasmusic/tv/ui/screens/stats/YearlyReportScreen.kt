package com.nasmusic.tv.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.NameCount
import com.nasmusic.tv.data.model.SongCount
import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.data.stats.YearlyStatsWriter
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalNasPalette
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.pagePadding
import com.nasmusic.tv.ui.viewmodel.YearlyReportPresentation
import com.nasmusic.tv.ui.viewmodel.YearlyReportStatus
import com.nasmusic.tv.ui.viewmodel.YearlyReportViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 年度听歌报告：仅本地数据展示，不生成图片、不分享、不写文件、不发网络请求。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun YearlyReportScreen(
    onBack: () -> Unit,
    viewModel: YearlyReportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NasMusicColors.Background)
            .padding(pagePadding())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.yearly_report_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title(),
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.loading && state.statsByYear.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.pstats_loading),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body(),
                    )
                }
            }

            state.years.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                if (state.error) R.string.yearly_report_load_failed else R.string.pstats_empty
                            ),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.body(),
                        )
                        if (state.error) {
                            Spacer(modifier = Modifier.height(12.dp))
                            FocusableSurface(onClick = viewModel::retry) {
                                Text(
                                    text = stringResource(R.string.list_retry),
                                    color = NasMusicColors.Primary,
                                    fontSize = FontSize.button(),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                val years = state.years
                val firstYearFocus = remember { FocusRequester() }
                LaunchedEffect(years) {
                    runCatching { firstYearFocus.requestFocus() }
                }

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(years, key = { it }) { year ->
                        YearChip(
                            year = year,
                            selected = year == state.selectedYear,
                            focusRequester = if (year == years.first()) firstYearFocus else null,
                            onClick = { viewModel.selectYear(year) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    val stats = state.selectedStats
                    if (stats != null) {
                        item { StatusCard(stats = stats, currentYear = currentYear()) }
                        item { SummaryCards(stats = stats) }
                        item { FirstLastCard(stats = stats) }
                        item { TopSongsCard(stats = stats) }
                        item { TopArtistsCard(stats = stats) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun YearChip(
    year: Int,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        focusRequester = focusRequester,
        shape = RoundedCornerShape(18.dp),
        containerColor = if (selected) LocalNasPalette.current.primary.copy(alpha = 0.24f) else NasMusicColors.Surface,
        focusedContainerColor = LocalNasPalette.current.primary.copy(alpha = 0.34f),
    ) {
        Text(
            text = year.toString(),
            color = if (selected) LocalNasPalette.current.primary else NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusCard(stats: YearlyStats, currentYear: Int) {
    val status = YearlyReportPresentation.status(stats, currentYear)
    val labelRes = when (status) {
        YearlyReportStatus.COMPLETE -> R.string.yearly_report_complete
        YearlyReportStatus.ONGOING -> R.string.yearly_report_ongoing
        YearlyReportStatus.BACKFILLED -> R.string.yearly_report_backfilled
        YearlyReportStatus.PARTIAL -> R.string.yearly_report_partial
    }

    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1f,
        contentColor = NasMusicColors.TextPrimary,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(labelRes),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                fontWeight = FontWeight.Bold,
            )
            if (status != YearlyReportStatus.COMPLETE) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.yearly_report_incomplete),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.caption(),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SummaryCards(stats: YearlyStats) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1f,
        contentColor = NasMusicColors.TextPrimary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryItem(
                value = durationText(stats.totalPlayMs),
                label = stringResource(R.string.yearly_report_total_time),
                modifier = Modifier.weight(1f),
            )
            SummaryItem(
                value = stats.playCount?.let { stringResource(R.string.pstats_plays_format, it) }
                    ?: stringResource(R.string.yearly_report_unavailable),
                label = stringResource(R.string.pstats_total_plays),
                modifier = Modifier.weight(1f),
            )
            SummaryItem(
                value = stats.activeDays?.let { stringResource(R.string.pstats_heatmap_days_format, it) }
                    ?: stringResource(R.string.yearly_report_unavailable),
                label = stringResource(R.string.pstats_heatmap_active_days),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SummaryItem(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            color = NasMusicColors.Primary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption(),
            maxLines = 2,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FirstLastCard(stats: YearlyStats) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1f,
        contentColor = NasMusicColors.TextPrimary,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            TimestampRow(
                label = stringResource(R.string.yearly_report_first_play),
                timestampMs = stats.firstPlayAtMs,
            )
            Spacer(modifier = Modifier.height(10.dp))
            TimestampRow(
                label = stringResource(R.string.yearly_report_last_play),
                timestampMs = stats.lastPlayAtMs,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TimestampRow(label: String, timestampMs: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption(),
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = timestampText(timestampMs),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.body(),
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopSongsCard(stats: YearlyStats) {
    val songs = YearlyReportPresentation.topSongs(stats)
    SectionCard(title = stringResource(R.string.yearly_report_top_songs)) {
        if (songs.isEmpty()) {
            Text(
                text = stringResource(R.string.yearly_report_no_top_songs),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((60 * songs.size).dp),
                contentPadding = PaddingValues(vertical = 3.dp),
            ) {
                itemsIndexed(songs) { index, song -> SongRankRow(index + 1, song) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TopArtistsCard(stats: YearlyStats) {
    val artists = YearlyReportPresentation.topArtists(stats)
    SectionCard(title = stringResource(R.string.yearly_report_top_artists)) {
        if (artists.isEmpty()) {
            Text(
                text = stringResource(R.string.yearly_report_no_top_artists),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((60 * artists.size).dp),
                contentPadding = PaddingValues(vertical = 3.dp),
            ) {
                itemsIndexed(artists) { index, artist -> ArtistRankRow(index + 1, artist) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        focusedScale = 1f,
        contentColor = NasMusicColors.TextPrimary,
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = title,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.subtitle(),
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRankRow(rank: Int, song: SongCount) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(8.dp),
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedScale = 1f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString(),
                color = NasMusicColors.Primary,
                fontSize = FontSize.caption(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title.ifBlank { song.key },
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.body(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        text = song.artist,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.pstats_plays_format, song.count),
                color = NasMusicColors.Secondary,
                fontSize = FontSize.small(),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistRankRow(rank: Int, artist: NameCount) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(8.dp),
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedScale = 1f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rank.toString(),
                color = NasMusicColors.Primary,
                fontSize = FontSize.caption(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(34.dp),
            )
            Text(
                text = artist.name,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.pstats_plays_format, artist.count),
                color = NasMusicColors.Secondary,
                fontSize = FontSize.small(),
            )
        }
    }
}

@Composable
private fun durationText(durationMs: Long?): String {
    if (durationMs == null) return stringResource(R.string.yearly_report_unavailable)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs.coerceAtLeast(0L))
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return stringResource(R.string.yearly_report_duration_format, hours, minutes)
}

@Composable
private fun timestampText(timestampMs: Long?): String {
    if (timestampMs == null) return stringResource(R.string.yearly_report_unavailable)
    val formatted = remember(timestampMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            .format(Date(timestampMs))
    }
    return formatted
}

@Composable
private fun currentYear(): Int = YearlyStatsWriter.yearOf(System.currentTimeMillis())
