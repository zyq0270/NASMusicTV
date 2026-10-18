package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.util.BackupFileUtils
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 数据管理分区状态 */
data class DataSettingsState(
    val backupFiles: List<BackupFileUtils.BackupFile>,
    val backupMessage: BackupMessage?,
    /** 歌单导入（§4.4.2）：最近导入记录 + 导入结果消息 */
    val playlistImportHistory: List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem> = emptyList(),
    val playlistImportMessage: BackupMessage? = null,
)

/** 数据管理分区动作（删除确认弹窗由宿主持有，经 onDeleteRequested 上抛） */
data class DataSettingsActions(
    val onExportBackup: (() -> Unit)?,
    val onImportBackup: ((android.net.Uri) -> Unit)?,
    val onScanTransferBackup: (() -> Unit)?,
    /** F2-1：打开播放统计面板 */
    val onOpenPlayStats: (() -> Unit)? = null,
    /** 歌单导入入口（SAF OpenDocument，launcher 由 MainActivity 持有） */
    val onImportPlaylistFile: (() -> Unit)? = null,
    /** 打开最近导入的歌单（跳「我的」页） */
    val onOpenImportedPlaylist: ((String) -> Unit)? = null,
    /** 消费导入结果消息（4s 自动） */
    val onConsumePlaylistImportMessage: (() -> Unit)? = null,
)

/**
 * 数据管理分区（原 SettingsScreen DATA 分支，逻辑逐行搬迁；三卡构成见方案 §3.2 / Task 8）。
 *
 * ⚠️ Task 8 只做「裸 `Text` / `SubSectionTitle` 标题 → `SettingsGroupCard` 卡片头」。
 * ⚠️ 播放统计入口按方案 §3.2 从原第三位上移到分区**末尾独立成卡**（①备份与恢复
 * ②歌单导入 ③播放统计），其余行级排布与条件渲染逐行未动。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DataSettingsSection(
    state: DataSettingsState,
    actions: DataSettingsActions,
    onDeleteRequested: (BackupFileUtils.BackupFile) -> Unit
) {
    Column {
        SectionTitle(stringResource(R.string.settings_data))
        Text(
            text = stringResource(R.string.settings_data_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        // 备份结果消息（导出 / 恢复）
        //
        // ⚠️ v2.36.2 从「备份文件列表**下方**」移到分区**最上方**（真机反馈「点恢复静默失败无提示」）：
        // 恢复入口在下方列表的每一行里，消息渲染在列表下方时会被挤到屏幕外
        // —— 加上 4s 自动消费，用户根本看不到（见 SettingsScreen 的 LaunchedEffect）。
        // 现在放在分区第一个元素，并配合 SettingsScreen 的「有新消息就滚到分区顶部」。
        if (state.backupMessage != null) {
            Text(
                text = state.backupMessage.text,
                color = if (state.backupMessage.isError)
                    NasMusicColors.Warning else NasMusicColors.Primary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
        }
        // 卡① 备份与恢复：导出、扫码传输、备份文件列表（方案 §3.2）
        SettingsGroupCard(title = stringResource(R.string.settings_group_backup)) {
            // 导出备份
            if (actions.onExportBackup != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_export_backup),
                    description = stringResource(R.string.settings_export_backup_desc),
                    onClick = { actions.onExportBackup?.invoke() }
                )
            }
            // 扫码传输（手机下载/上传备份）
            if (actions.onScanTransferBackup != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_scan_transfer),
                    description = stringResource(R.string.settings_scan_transfer_desc),
                    onClick = { actions.onScanTransferBackup?.invoke() }
                )
            }
            // 备份文件列表
            Text(
                text = stringResource(R.string.settings_backup_list),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            if (state.backupFiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_backup_empty),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else {
                state.backupFiles.forEach { file ->
                    BackupFileRow(
                        file = file,
                        onRestore = { actions.onImportBackup?.invoke(file.uri) },
                        onDelete = { onDeleteRequested(file) }
                    )
                }
            }
            // 从备份列表恢复（电视无系统文件选择器，恢复入口即上方备份文件列表）
            // ⚠️ 备份结果消息已上移到分区顶部，见上文 —— 不要在这里再加一份。
        }
        // 卡② 歌单导入：导入入口、最近导入记录（方案 §3.2；2026-09-18 新增：§4.4.2）
        SettingsGroupCard(title = stringResource(R.string.settings_group_playlist_import)) {
            if (actions.onImportPlaylistFile != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_import_playlist),
                    description = stringResource(R.string.settings_import_playlist_desc),
                    onClick = { actions.onImportPlaylistFile?.invoke() }
                )
            }
            // 最近导入记录（无删除操作；点击打开 → 「我的」，2026-09-18 用户决策）
            if (state.playlistImportHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SubSectionTitle(stringResource(R.string.settings_playlist_import_history))
                state.playlistImportHistory.forEach { item ->
                    ImportedPlaylistRow(
                        item = item,
                        onOpen = { actions.onOpenImportedPlaylist?.invoke(item.playlistId) }
                    )
                }
            }
            // 导入结果消息
            if (state.playlistImportMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = state.playlistImportMessage.text,
                    color = if (state.playlistImportMessage.isError)
                        NasMusicColors.Warning else NasMusicColors.Primary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        // 卡③ 播放统计：入口（F2-1；方案 §3.2 上移到分区末尾独立成卡）
        if (actions.onOpenPlayStats != null) {
            SettingsGroupCard(title = stringResource(R.string.settings_group_play_stats)) {
                SettingActionButton(
                    label = stringResource(R.string.pstats_title),
                    description = stringResource(R.string.pstats_entry_desc),
                    onClick = { actions.onOpenPlayStats?.invoke() }
                )
            }
        }
    }
}
