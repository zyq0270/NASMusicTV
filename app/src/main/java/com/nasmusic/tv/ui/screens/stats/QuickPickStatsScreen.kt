package com.nasmusic.tv.ui.screens.stats

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.QuickPickStat
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.settings.SettingsTokens
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.theme.pagePadding

/**
 * 单类别快捷选择统计明细页。
 *
 * 只展示传入 category 的历史选择数据（与曲库搜索 tab 对应），不设统一汇总 tab。
 * 排序口径与拨盘一致：次数倒序、同次数按名称。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QuickPickStatsScreen(
    categoryLabel: String,
    stats: List<QuickPickStat>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sorted = rememberSortedStats(stats)
    val totalPicks = sorted.sumOf { it.count }
    // 自适应布局：手机竖屏收窄 KPI 卡间距与行内边距，TV/横屏维持宽松版式
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    val rowVertical = if (isPhonePortrait) 8.dp else 10.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NasMusicColors.Background)
            .padding(pagePadding())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(
                text = stringResource(R.string.qp_stats_title, categoryLabel),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title()
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiCard(value = sorted.size.toString(), label = stringResource(R.string.qp_stats_count))
            KpiCard(value = totalPicks.toString(), label = stringResource(R.string.qp_stats_total))
        }
        Spacer(Modifier.height(12.dp))

        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.qp_stats_empty),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sorted, key = { it.value }) { stat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NasMusicColors.Surface, SettingsTokens.rowShape)
                            .padding(horizontal = 14.dp, vertical = rowVertical),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stat.value,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.qp_stats_picked, stat.count),
                            color = NasMusicColors.Secondary,
                            fontSize = FontSize.small()
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FocusableSurface(
            onClick = onClear,
            shape = SettingsTokens.rowShape
        ) {
            Text(
                text = stringResource(R.string.qp_clear),
                color = NasMusicColors.Danger,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun rememberSortedStats(stats: List<QuickPickStat>): List<QuickPickStat> =
    androidx.compose.runtime.remember(stats) {
        stats.sortedWith(
            compareByDescending<QuickPickStat> { it.count }
                .thenBy { it.value.lowercase(java.util.Locale.ROOT) }
                .thenBy { it.value }
        )
    }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KpiCard(value: String, label: String) {
    Column(
        modifier = Modifier
            .background(NasMusicColors.Surface, SettingsTokens.cardShape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, color = NasMusicColors.Primary, fontSize = FontSize.title())
        Text(text = label, color = NasMusicColors.TextSecondary, fontSize = FontSize.small())
    }
}
