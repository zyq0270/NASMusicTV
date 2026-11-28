package com.nasmusic.tv.ui.screens.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.stats.ArtistStat
import com.nasmusic.tv.data.stats.GenreStat
import com.nasmusic.tv.data.stats.PlayHeatmap
import com.nasmusic.tv.data.stats.StatsBundle
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.theme.pagePadding

/**
 * 播放统计页面（F2-1 / F2-5）
 *
 * 结构：返回+标题+Tab（本月 / 累计 / 热力图）→ KPI 行（播放次数/歌曲数）→ 最爱歌手 Top10 横向列表
 * → 流派分布条形图；热力图 Tab 则展示按日期聚合的听歌热力图 + 活跃度摘要。
 *
 * Tab 与年度报告入口使用可滚动焦点行；常规统计与热力图内容可纵向滚动。
 * 年度报告从入口进入，返回后回到原 Tab。
 *
 * D-Pad 焦点：返回 → Tab → 歌手行（横向可滚动）。热力图为纯展示，不入焦点链。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayStatsScreen(
    onBack: () -> Unit,
    viewModel: com.nasmusic.tv.ui.viewmodel.PlayStatsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val monthly by viewModel.monthlyBundle.collectAsState()
    val allTime by viewModel.allTimeBundle.collectAsState()
    val heatmap by viewModel.heatmap.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var tab by remember { mutableStateOf(StatsTab.MONTHLY) }
    var showYearlyReport by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadStats() }

    if (showYearlyReport) {
        BackHandler(onBack = { showYearlyReport = false })
        YearlyReportScreen(onBack = { showYearlyReport = false })
        return
    }

    // v2.36.0 竖屏：页 padding 32→16
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePadding())
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.pstats_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tab 与年度报告入口放在可横向滚动焦点行，小屏也不会把入口裁掉。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(StatsTab.entries.size) { index ->
                val item = StatsTab.entries[index]
                StatsTabButton(
                    text = stringResource(item.labelRes),
                    selected = tab == item,
                    onClick = { tab = item }
                )
            }
            item {
                StatsTabButton(
                    text = stringResource(R.string.yearly_report_entry),
                    selected = false,
                    onClick = { showYearlyReport = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (tab == StatsTab.HEATMAP) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { HeatmapContent(heatmap = heatmap, loading = loading) }
                }
            } else {
                val bundle = if (tab == StatsTab.ALL_TIME) allTime else monthly
                when {
                    loading && bundle == null -> StatsPlaceholder(R.string.pstats_loading)
                    bundle == null || (bundle.totalPlays == 0 && bundle.topArtists.isEmpty()) ->
                        StatsPlaceholder(R.string.pstats_empty)
                    else -> StatsContent(bundle)
                }
            }
        }
    }
}

/** 统计页 Tab；[labelRes] 为文案资源，顺序即显示顺序 */
private enum class StatsTab(val labelRes: Int) {
    MONTHLY(R.string.pstats_tab_monthly),
    ALL_TIME(R.string.pstats_tab_alltime),
    HEATMAP(R.string.pstats_tab_heatmap)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsPlaceholder(textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(textRes),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body()
        )
    }
}

/**
 * 热力图 Tab 内容：标题 + 日期范围 + 网格 + 活跃度摘要。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeatmapContent(heatmap: PlayHeatmap?, loading: Boolean) {
    if (heatmap == null) {
        StatsPlaceholder(if (loading) R.string.pstats_loading else R.string.pstats_empty)
        return
    }
    if (heatmap.isEmpty) {
        StatsPlaceholder(R.string.pstats_heatmap_empty)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.pstats_heatmap_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.subtitle(),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.pstats_heatmap_range,
                    heatmap.startDateKey,
                    heatmap.endDateKey
                ),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        PlayHeatmapChart(
            heatmap = heatmap,
            // v2.36.0（方案 §9 P2-35）：仅手机竖屏开启放大（53 列塞进 360dp 时格子仅 3~6dp）；
            // TV / 横屏保持原有「自动缩到刚好铺满、不滚动」行为。
            enableZoom = LocalUiMode.current == UiMode.PhonePortrait,
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MiniStat(
                value = stringResource(R.string.pstats_heatmap_days_format, heatmap.activeDays),
                label = stringResource(R.string.pstats_heatmap_active_days),
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                value = stringResource(R.string.pstats_heatmap_days_format, heatmap.longestStreak),
                label = stringResource(R.string.pstats_heatmap_longest_streak),
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                value = heatmap.bestDay?.dateKey ?: "-",
                label = stringResource(R.string.pstats_heatmap_best_day),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(NasMusicColors.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            color = NasMusicColors.Primary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption()
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsContent(bundle: StatsBundle) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                KpiCard(
                    value = stringResource(R.string.pstats_plays_format, bundle.totalPlays),
                    label = stringResource(R.string.pstats_total_plays),
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    value = stringResource(R.string.pstats_songs_format, bundle.distinctSongs),
                    label = stringResource(R.string.pstats_distinct_songs),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (bundle.topArtists.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.pstats_top_artists),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(bundle.topArtists, key = { it.artist }) { artist ->
                        ArtistCard(artist)
                    }
                }
            }
        }

        if (bundle.genreDistribution.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.pstats_genre_distribution),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                GenreBarChart(
                    genres = bundle.genreDistribution,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KpiCard(value: String, label: String, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                color = NasMusicColors.Primary,
                fontSize = FontSize.title(),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistCard(artist: ArtistStat) {
    FocusableSurface(
        onClick = {},
        shape = RoundedCornerShape(12.dp),
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.16f),
        modifier = Modifier.width(120.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(6.dp)
        ) {
            // 封面占位（圆形，未加载图片——首期用色块，避免引入图片加载依赖）
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(NasMusicColors.Primary.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = artist.artist.take(1).ifBlank { "?" },
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.title(),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = artist.artist,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.caption(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.pstats_plays_format, artist.playCount),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small()
            )
        }
    }
}

/** 流派条形图最多展示的行数（M45：超出的长尾折叠成一行「其他」） */
private const val GENRE_CHART_MAX_ROWS = 8

/**
 * 把 Top-N 之后的长尾折叠成一行「其他 (N 个流派)」。
 *
 * M45：原实现直接把全量 `genreDistribution` 铺进一个固定封顶 320dp 的 `Column`，
 * 流派数一多，多出来的部分就**被裁掉且无从查看**（Column 不滚动、TV 上也不可聚焦）。
 * 折叠成 Top-(N-1) + 其他既能保留全部计数（播放次数求和），又保证高度收敛。
 */
private fun foldGenres(genres: List<GenreStat>, maxRows: Int, othersLabel: String): List<GenreStat> {
    if (genres.size <= maxRows) return genres
    val head = genres.take(maxRows - 1)
    val tail = genres.drop(maxRows - 1)
    val othersPlays = tail.sumOf { it.playCount }
    val othersSongs = tail.sumOf { it.songCount }
    return head + GenreStat(genre = othersLabel, playCount = othersPlays, songCount = othersSongs)
}

/** 流派分布横向条形图（原生 Canvas，不引第三方图表库） */
@Composable
private fun GenreBarChart(genres: List<GenreStat>, modifier: Modifier = Modifier) {
    val othersLabel = stringResource(R.string.pstats_genre_others, genres.size - (GENRE_CHART_MAX_ROWS - 1))
    val rows = remember(genres, othersLabel) { foldGenres(genres, GENRE_CHART_MAX_ROWS, othersLabel) }
    val maxCount = rows.maxOf { it.playCount }.coerceAtLeast(1)
    val accent = NasMusicColors.Primary
    val trackColor = NasMusicColors.Surface
    val labelColor = NasMusicColors.TextPrimary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { g ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = g.genre,
                        color = labelColor,
                        fontSize = FontSize.caption(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${g.playCount}",
                        color = labelColor,
                        fontSize = FontSize.caption()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        size = Size(size.width, size.height)
                    )
                    drawRoundRect(
                        color = accent,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        topLeft = Offset.Zero,
                        size = Size(size.width * g.playCount / maxCount, size.height)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsTabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = text,
            color = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
