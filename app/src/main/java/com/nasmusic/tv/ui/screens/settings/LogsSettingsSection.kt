package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.LogEntry
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
 *  - 日志行**不放进焦点序列**（纯文本，不可点）：TV 上焦点只落在两枚芯片上，
 *    避免几十条日志把遥控器导航拖成"按 40 次下才能到底"。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LogsSettingsSection(
    state: LogsSettingsState,
    actions: LogsSettingsActions,
) {
    var showClearConfirm by remember { mutableStateOf(false) }
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
                state.entries.forEach { entry -> LogEntryRow(entry) }
            }
        }
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
 * 单条日志：第一行 = 级别 + 时间 + TAG，下面是正文，再下面是异常首行（如 `java.lang.X: …`）。
 *
 * ⚠️ 堆栈只显示**首行**：完整堆栈动辄几十行，日志页是"扫一眼定位问题"，
 * 需要全文时应导出文件（后续可加），⛔ 不要在这里铺整段堆栈。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
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

/** 字节数格式化（日志文件通常几百 KB，MB 档只在轮转边界出现）。 */
private fun formatLogSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}
