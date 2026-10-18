package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel

/**
 * 来源分区状态（v2.39.0 合并 SERVER + LOCAL_MUSIC + NETDISK 三个旧分区，方案 §3 / Task 3）。
 *
 * 字段**逐一沿用旧分区的原名与可空性**（`ServerSettingsState` / `LocalMusicSettingsState` /
 * `BaiduPanSettingsState`），`SettingsScreen` 侧只是把三处构造合成一处。
 *
 * ⚠️ [defaultNetworkSource] 的 UI（卡⑤「网络搜索默认源」）在 **Task 7** 才落地，
 * setter 同期新增；本任务先把字段立住，避免 T7 再改签名。
 */
data class SourcesSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
    val settings: AppSettings,
    val libraryCount: Int,
    val baiduEnabled: Boolean,
    val baiduLoggedIn: Boolean,
    val baiduConnectionState: NetworkMusicViewModel.BaiduConnectionState,
    val baiduMusicRootDirLocal: String,
    val baiduMvDirLocal: String?,
    val baiduIndexScanned: Int,
    val baiduIndexScanning: Boolean,
    val baiduApicExtracting: Boolean,
    val baiduApicExtracted: Int,
    val baiduApicTotal: Int,
    val defaultNetworkSource: NetworkSource,
)

/**
 * 来源分区动作。
 *
 * ⚠️ 可空性**逐字沿用旧分区**（未按方案原文改成非空默认 `{}`）：合并是纯搬迁，
 * 保留可空 ⇒ 「断开连接 / 登录按钮 / 重建索引」三处**条件渲染**的判据不变 ——
 * 尤其 `ServerSettingsSection` 的断开连接是「未连接时整段不渲染」，改成非空会让
 * 判据失效、按钮变成常驻（Task 7 明令禁止的手顺改动）。
 */
data class SourcesActions(
    val onNavigateToServerConnect: (() -> Unit)?,
    val onDisconnect: (() -> Unit)?,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onRescan: () -> Unit = {},
    val onToggleBaiduEnabled: ((Boolean) -> Unit)?,
    val onStartBaiduDeviceCode: (() -> Unit)?,
    val onLogoutBaidu: (() -> Unit)?,
    val onRebuildBaiduIndex: (() -> Unit)?,
)

/** 来源分区对话框触发回调（对话框宿主仍在 `SettingsScreen`，本任务只搬参数名） */
data class SourcesDialogActions(
    val onShowBaiduAuthDialog: () -> Unit,
    val onShowMusicRootDialog: () -> Unit,
    val onShowMvDirDialog: () -> Unit,
)

/**
 * 来源分区：① NAS 服务器 ② 本地曲库 ③ 百度网盘 ④ 索引与封面（§3.2 顺序）。
 *
 * ⚠️ 本文件当前是**纯搬迁**：三块主体逐行来自 `ServerSettingsSection.kt` /
 * `LocalMusicSettingsSection.kt` / `BaiduPanSettingsSection.kt`，
 * 去掉的只有三处重复的 `SectionTitle`（页面级标题由本文件顶部一个承担）；
 * 卡片化与占位行删除在 **Task 7**。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SourcesSettingsSection(
    state: SourcesSettingsState,
    actions: SourcesActions,
    dialogs: SourcesDialogActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_sources))

        // ── ① NAS 服务器（原 ServerSettingsSection 主体）──
        val statusText = if (state.isConnected)
            stringResource(R.string.server_connected, state.serverDisplayName)
        else
            stringResource(R.string.server_connect_desc)
        Text(statusText, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        SettingActionButton(
            label = stringResource(R.string.server_config_title),
            description = if (state.isConnected) stringResource(R.string.server_connected, state.serverDisplayName)
                else stringResource(R.string.server_connect_desc),
            onClick = { actions.onNavigateToServerConnect?.invoke() }
        )
        // ⛔ 未连接时「断开连接」整段不渲染（不是 enabled = false）—— 保留原形态
        if (state.isConnected && actions.onDisconnect != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingActionButton(
                label = stringResource(R.string.server_disconnect),
                description = stringResource(R.string.settings_disconnect_desc),
                onClick = actions.onDisconnect
            )
        }

        // ── ② 本地曲库（原 LocalMusicSettingsSection 主体）──
        // ⛔ 总开关 = 音乐权限的唯一触发点（§5.5）：ViewModel 内部判
        //    「已授权直接开 / 未授权先申请、被拒回弹」，这里只转发目标值。
        val s = state.settings
        Spacer(modifier = Modifier.height(12.dp))
        SettingSwitch(
            label = stringResource(R.string.settings_local_music_source),
            description = stringResource(R.string.settings_local_music_source_desc),
            checked = s.localMusicEnabled,
            onClick = { actions.onSetEnabled(!s.localMusicEnabled) }
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SettingsInfoRow(
                stringResource(R.string.settings_local_music_source),
                stringResource(R.string.settings_local_music_count, state.libraryCount),
            )
        }
        // 开关关闭时置灰（§5.6）：关着开关扫描没有意义 —— 结果也不会出现在曲库
        SettingActionButton(
            label = stringResource(R.string.settings_local_music_rescan),
            description = stringResource(R.string.settings_local_music_rescan_desc),
            onClick = actions.onRescan,
            enabled = s.localMusicEnabled,
        )

        // ── ③ 百度网盘（原 BaiduPanSettingsSection 主体，含 ④ 索引与封面）──
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_netdisk_group_baidu))
        SettingSwitch(label = stringResource(R.string.settings_netdisk_enable), description = stringResource(R.string.settings_netdisk_enable_desc), checked = state.baiduEnabled, onClick = { actions.onToggleBaiduEnabled?.invoke(!state.baiduEnabled) })

        // 授权失败时，在登录按钮上方持续显示失败原因（即使对话框关闭也能看到）
        val baiduFailedState = state.baiduConnectionState as? NetworkMusicViewModel.BaiduConnectionState.Failed
        if (baiduFailedState != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.netdisk_auth_failed),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = baiduFailedState.message,
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small()
                )
            }
        }

        // 已登录但音乐根目录不存在，提示用户重新设置
        val baiduDirMissing = state.baiduConnectionState is NetworkMusicViewModel.BaiduConnectionState.DirMissing
        if (baiduDirMissing) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.netdisk_dir_missing),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.netdisk_dir_missing_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small()
                )
            }
        }

        if (actions.onStartBaiduDeviceCode != null) {
            Spacer(modifier = Modifier.height(16.dp))
            if (state.baiduLoggedIn) {
                SettingActionButton(
                    label = stringResource(R.string.settings_netdisk_logged_in),
                    description = stringResource(R.string.settings_netdisk_logout_desc),
                    onClick = { actions.onLogoutBaidu?.invoke() }
                )
            } else {
                SettingActionButton(
                    label = stringResource(R.string.settings_netdisk_login),
                    description = stringResource(R.string.settings_netdisk_login_desc),
                    onClick = {
                        dialogs.onShowBaiduAuthDialog()
                        actions.onStartBaiduDeviceCode?.invoke()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_netdisk_music_root),
            description = state.baiduMusicRootDirLocal,
            onClick = { dialogs.onShowMusicRootDialog() }
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_netdisk_mv_dir),
            description = state.baiduMvDirLocal?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_netdisk_mv_dir_desc),
            onClick = { dialogs.onShowMvDirDialog() }
        )
        // ── ④ 索引与封面（原 BaiduPanSettingsSection 尾部）──
        if (actions.onRebuildBaiduIndex != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                else stringResource(R.string.settings_netdisk_index_desc, state.baiduIndexScanned),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            // APIC 封面提取进度（放在已扫描歌曲数量下方、重建索引按钮上方）
            if (state.baiduApicExtracting || state.baiduApicTotal > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.baiduApicExtracting) {
                        stringResource(R.string.settings_baidu_apic_extracting, state.baiduApicExtracted, state.baiduApicTotal)
                    } else {
                        stringResource(R.string.settings_baidu_apic_done, state.baiduApicTotal)
                    },
                    color = if (state.baiduApicExtracting) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (state.baiduApicExtracting && state.baiduApicTotal > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NasMusicColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((state.baiduApicExtracted.toFloat() / state.baiduApicTotal).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_index_rebuild),
                description = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                               else stringResource(R.string.settings_netdisk_index_rebuild_desc),
                onClick = { if (!state.baiduIndexScanning) actions.onRebuildBaiduIndex?.invoke() }
            )
        }

        // ── 其他网盘（占位）分组 ──
        Spacer(modifier = Modifier.height(24.dp))
        SubSectionTitle(stringResource(R.string.settings_netdisk_group_others))
        com.nasmusic.tv.data.model.CloudDriveType.PLACEHOLDER.forEach { type ->
            PlaceholderRow(name = stringResource(type.displayNameRes))
        }
    }
}
