package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.LinkUtils
import com.nasmusic.tv.util.LogEntry
import com.nasmusic.tv.util.toClipboardText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志分区状态（运行期事实，不落盘、不进备份） */
data class LogsSettingsState(
    /** 最近若干条（时间倒序，最新在前） */
    val entries: List<LogEntry> = emptyList(),
    /** 日志文件占用字节数 */
    val fileSizeBytes: Long = 0L,
)

/** 日志分区动作（清空确认弹窗由本分区自己持有） */
data class LogsSettingsActions(
    val onRefresh: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/**
 * 日志分区（v2.40.0）：运行期警告/错误的持久化回看。
 *
 * 数据源是 [com.nasmusic.tv.util.LogStore] 落盘的 `filesDir/logs/app.log`，
 * 所以**重启后仍在**；写入发生在 `AppLog.w/e` 与崩溃处理器里，本页只读 + 清空。
 *
 * 排版取舍：
 *  - 卡片里先给一行摘要（条数 + 占用），再给「刷新 / 清空」两枚芯片，
 *    最后是日志行 —— 手机上 2×2/等宽芯片与时长档同一原语（`SettingsChipRow`）。
 *  - 日志行**可聚焦**：遥控器确认 → 弹出详情，看**完整消息 + 完整堆栈**
 *    （列表里只放 3 行正文 + 堆栈首行，否则一屏放不下两条）。
 *    ⚠️ 行本身是「就地查看」，不是「进下一级」⇒ 不加 `Trail.Arrow`。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LogsSettingsSection(
    state: LogsSettingsState,
    actions: LogsSettingsActions,
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    // 非空 = 正在查看某条日志的详情
    var selectedEntry by remember { mutableStateOf<LogEntry?>(null) }
    Column {
        SectionTitle(stringResource(R.string.settings_logs))
        Text(
            text = stringResource(R.string.settings_logs_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_runtime_logs),
            description = stringResource(R.string.settings_group_runtime_logs_desc),
        ) {
            // 摘要：条数 + 文件占用
            Text(
                text = stringResource(
                    R.string.settings_logs_summary,
                    state.entries.size,
                    formatLogSize(state.fileSizeBytes),
                ),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            SettingsChipRow(
                items = listOf(
                    LogsAction(
                        label = stringResource(R.string.settings_logs_refresh),
                        description = stringResource(R.string.settings_logs_refresh_desc),
                        onClick = actions.onRefresh,
                    ),
                    LogsAction(
                        label = stringResource(R.string.settings_logs_clear),
                        description = stringResource(R.string.settings_logs_clear_desc),
                        onClick = { showClearConfirm = true },
                        danger = true,
                    ),
                ),
                tv = 2,
                phonePortrait = 1,
                equalWidth = true,
            ) { item ->
                SettingActionButton(
                    label = item.label,
                    description = item.description,
                    onClick = item.onClick,
                    danger = item.danger,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (state.entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_logs_empty),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            } else {
                state.entries.forEach { entry ->
                    LogEntryRow(entry = entry, onOpen = { selectedEntry = entry })
                }
            }
        }
    }
    selectedEntry?.let { entry ->
        LogDetailDialog(entry = entry, onDismiss = { selectedEntry = null })
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logs_clear_confirm_title),
            message = stringResource(R.string.settings_logs_clear_confirm_msg),
            onConfirm = {
                showClearConfirm = false
                actions.onClear()
            },
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }
}

/**
 * 芯片数据载体（与 `LyricsKaraokeSettingsSection` 的 `ModelAction` 同构）。
 * ⚠️ 用私有 data class 而不是 `Triple`：`danger` 是第四种语义。
 */
private data class LogsAction(
    val label: String,
    val description: String,
    val onClick: () -> Unit,
    val danger: Boolean = false,
)

/**
 * 单条日志（列表里的**摘要**）：第一行 = 级别 + 时间 + TAG，下面是正文（最多 3 行），
 * 再下面是异常首行。遥控器确认 → [LogDetailDialog] 看全文。
 *
 * ⚠️ 完整堆栈动辄几十行，列表里只放首行；要全文点开详情，⛔ 不要在列表里铺整段堆栈
 * （100 条 × 50 行会让整页组合开销爆炸）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogEntryRow(entry: LogEntry, onOpen: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    // ⚠️ 必须走 SettingsTile：分区里手写 FocusableSurface 会被
    //    `SettingsGroupCoverageTest` 的「裸瓦片」门禁拦下（形状/缩放/动画令牌
    //    只在 SettingsComponents 里定义一次）。
    SettingsTile(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level,
                    color = if (entry.isError) NasMusicColors.Danger else NasMusicColors.Warning,
                    fontSize = FontSize.small(),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.small(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = entry.message,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            entry.stackTrace?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { firstLine ->
                Text(
                    text = firstLine,
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 日志详情对话框：**完整**消息 + **完整**堆栈，可滚动，可一键复制。
 *
 * ## 为什么是对话框而不是在列表里展开
 * 堆栈长度不可控（几十行），展开会让「找下一条日志」变成滚动噩梦；
 * 详情是「看这一条」的专注场景。
 *
 * ## 三处真机反馈（v2.39.3）
 * ① **「文本无法长按选择复制」** —— 电视上根本没有长按选择这回事（遥控器没有指针）。
 *    ⇒ 给一枚「复制」芯片，把 [LogEntry.toClipboardText] 的全文（含堆栈）写进剪贴板，
 *    复用 `LinkUtils.copyToClipboard`（自带「已复制」Toast，与链接弹窗同一路径）。
 * ② **「还未显示完全」** —— 根因是 `responsiveDialogSize` 在**非竖屏**只返回
 *    `Modifier.width(…)`：`scrollable` 参数在 TV 上被静默忽略 ⇒ 长堆栈直接被屏幕裁掉。
 *    这里不再依赖它分叉，而是自己补一条**与形态无关**的 `heightIn` 上限 +
 *    正文区 `verticalScroll`，页头与按钮固定不动。
 * ③ **「遥控器怎么滚」** —— 纯文本没有子焦点，`BringIntoView` 不会替我们滚。
 *    ⇒ 在对话框根节点挂 `onKeyEvent`，上下键直接翻页。键盘事件从获得焦点的芯片
 *    **冒泡**到根节点，所以不需要额外造一个可聚焦节点（也就不会把焦点困在正文里）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogDetailDialog(entry: LogEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val scrollState = rememberScrollState()
    val pagePx = with(LocalDensity.current) { 200.dp.toPx() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = responsiveDialogSize(landscapeWidth = 760.dp)
                // 竖屏 `responsiveDialogSize` 自带 80% 屏高；TV / 横屏该函数只返回 width
                // ⇒ 必须自己兜一条高度上限，否则长堆栈直接顶出屏幕（真机反馈 ②）。
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NasMusicColors.Surface)
                .padding(20.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val delta = when (event.key) {
                        Key.DirectionUp -> -pagePx
                        Key.DirectionDown -> pagePx
                        else -> return@onKeyEvent false
                    }
                    // 恒消费上下键：越界时交给默认焦点搜索会**跑出弹窗**落到背后页面上
                    // （v2.38.10 的四个方向钉死就是踩过这个坑）。
                    scrollState.dispatchRawDelta(delta)
                    true
                }
        ) {
            Text(
                text = "${entry.level} · ${timeFormat.format(Date(entry.timestamp))} · ${entry.tag}",
                color = if (entry.isError) NasMusicColors.Danger else NasMusicColors.Warning,
                fontSize = FontSize.button(),
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // fill = false：内容短时不撑满，避免底部按钮被推到屏幕边缘
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
            ) {
                // 触摸设备（手机投屏 / 触屏电视）上仍然保留「长按选择」——
                // 真机反馈说的就是这个能力缺失；遥控器场景由下面的「复制」按钮兜底。
                SelectionContainer {
                    Column {
                        Text(
                            text = entry.message,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body(),
                        )
                        entry.stackTrace?.let { stack ->
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.settings_logs_stack_label),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stack,
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                            )
                        }
                    }
                }
            }
            if (scrollState.canScrollForward) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_logs_detail_scroll_hint),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsChip(
                    label = stringResource(R.string.settings_logs_detail_copy),
                    selected = false,
                    // 复制的是**全文**（含完整堆栈），不是屏幕上能看见的那几行
                    onClick = {
                        LinkUtils.copyToClipboard(context, "NASMusicTV-log", entry.toClipboardText())
                    },
                )
                SettingsChip(
                    label = stringResource(R.string.settings_logs_detail_close),
                    selected = false,
                    onClick = onDismiss,
                )
            }
        }
    }
}

/** 字节数格式化（日志文件通常几百 KB，MB 档只在轮转边界出现）。 */
private fun formatLogSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}
