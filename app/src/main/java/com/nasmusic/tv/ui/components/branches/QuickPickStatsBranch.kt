package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.screens.stats.QuickPickStatsScreen
import com.nasmusic.tv.ui.viewmodel.MainViewModel

/**
 * 快捷选择统计明细页分支：只展示当前类别（MainViewModel.quickPickStatsCategory）的统计。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QuickPickStatsBranch(viewModel: MainViewModel) {
    val category by viewModel.quickPickStatsCategory.collectAsState()
    val allStats by viewModel.quickPickStats.collectAsState(initial = emptyList())
    val stats = allStats.filter { it.category == category }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 类别名映射回曲库 tab 的本地化标题（未知类别直接显示类别字符串）
    val categoryLabel = runCatching { LibraryTab.valueOf(category).titleRes }
        .getOrNull()?.let { stringResource(it) } ?: category

    QuickPickStatsScreen(
        categoryLabel = categoryLabel,
        stats = stats,
        onClear = { showClearConfirm = true },
        onBack = { viewModel.navVM.navigateTo(com.nasmusic.tv.data.model.Screen.Settings) }
    )

    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.qp_clear_confirm_title),
            message = stringResource(R.string.qp_clear_confirm_message, categoryLabel),
            confirmLabel = stringResource(R.string.qp_clear),
            cancelLabel = stringResource(R.string.qp_back),
            destructive = true,
            onConfirm = {
                viewModel.clearQuickPickStats(category)
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }
}
