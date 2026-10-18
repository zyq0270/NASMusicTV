package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.player.SeparationMode
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 歌词与 K 歌分区状态（v2.39.0 从 `PlayerSettingsSection` 切出，方案 §3 / Task 3）。
 *
 * 字段与 `PlayerSettingsState` 被切出的那几个**同名同类型**，未重命名 —— 纯搬迁，
 * 便于逐行对照 diff。`settings` 保留（分离模式开关要读 `AppSettings`）。
 */
data class LyricsKaraokeSettingsState(
    val settings: AppSettings,
    val separationMode: SeparationMode,
    val modelDownloaded: Boolean,
    val modelDownloading: Boolean,
    val modelDownloadProgress: Float,
    val modelDownloadedMB: Long,
    val modelTotalMB: Long,
    val modelSizeMB: Double,
    val modelDownloadError: String?,
    val modelPath: String,
    val hqErrorDetail: String?,
)

/** 歌词与 K 歌分区动作（可空性与默认值同 `PlayerSettingsActions` 原样） */
data class LyricsKaraokeActions(
    val onChangeSeparationMode: ((SeparationMode) -> Unit)?,
    val onDownloadModel: (() -> Unit)?,
    val onDeleteModel: (() -> Unit)?,
    val onScanTransferModel: (() -> Unit)?,
)

/**
 * 歌词与 K 歌分区（人声分离模式 + 分离模型管理；歌词显示卡由 T10 追加）。
 *
 * 卡片构成（方案 §3.2，编号此后不再变）：卡②人声分离 / 卡③分离模型；
 * 卡①歌词显示在 T10 直接以 `SettingsGroupCard` 落地。
 *
 * ⚠️ Task 4 只加卡片外壳：卡内行级排布逐行未动（原 `PlayerSettingsSection.kt` 搬迁产物）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LyricsKaraokeSettingsSection(
    state: LyricsKaraokeSettingsState,
    actions: LyricsKaraokeActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_lyrics_karaoke))
        // 卡② 人声分离
        SettingsGroupCard(title = stringResource(R.string.settings_group_separation)) {
            run {
                val isHq = state.separationMode == SeparationMode.HIGH_QUALITY
                val hqLabel = if (state.modelDownloaded) stringResource(R.string.settings_hq_mode_downloaded) else stringResource(R.string.settings_hq_mode_not_downloaded)
                val hqDesc = when {
                    !state.modelDownloaded -> stringResource(R.string.settings_download_model_hint)
                    isHq -> stringResource(R.string.settings_onnx_inference)
                    else -> stringResource(R.string.settings_dsp_inference)
                }
                SettingSwitch(
                    label = hqLabel,
                    description = hqDesc,
                    checked = isHq,
                    enabled = state.modelDownloaded,
                    onClick = { actions.onChangeSeparationMode?.invoke(if (isHq) SeparationMode.FAST else SeparationMode.HIGH_QUALITY) }
                )
            }
        }
        // 卡③ 分离模型（下载中进度 / 已下载路径+删除 / 未下载下载按钮，三态互斥）
        SettingsGroupCard(title = stringResource(R.string.settings_group_model)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (state.modelDownloading) {
                    // 下载中：进度条
                    Text(
                        text = stringResource(R.string.settings_downloading_model) + "：${(state.modelDownloadProgress * 100).toInt()}%  (${state.modelDownloadedMB}MB / ${state.modelTotalMB}MB)",
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.body()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NasMusicColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.modelDownloadProgress.coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(NasMusicColors.Primary, RoundedCornerShape(4.dp))
                        )
                    }
                } else if (state.modelDownloaded) {
                    // 已下载：显示路径 + 大小 + 删除按钮
                    Column {
                        Text(
                            text = stringResource(R.string.settings_model_downloaded_size, state.modelSizeMB),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body()
                        )
                        Text(
                            text = stringResource(R.string.settings_model_path, state.modelPath),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_delete_model),
                                    description = stringResource(R.string.settings_delete_model_desc),
                                    onClick = { actions.onDeleteModel?.invoke() }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_scan_upload_model),
                                    description = stringResource(R.string.settings_scan_upload_model_desc),
                                    onClick = { actions.onScanTransferModel?.invoke() }
                                )
                            }
                        }
                    }
                } else {
                    // 未下载：显示下载按钮 + 路径 + 扫码上传
                    Column {
                        Text(
                            text = stringResource(R.string.settings_network_model_not_downloaded),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.body()
                        )
                        Text(
                            text = stringResource(R.string.settings_storage_path, state.modelPath),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_download_separation_model),
                                    description = stringResource(R.string.settings_download_separation_model_desc),
                                    onClick = { actions.onDownloadModel?.invoke() }
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                SettingActionButton(
                                    label = stringResource(R.string.settings_scan_upload_model),
                                    description = stringResource(R.string.settings_scan_upload_model_desc),
                                    onClick = { actions.onScanTransferModel?.invoke() }
                                )
                            }
                        }
                    }
                }
                state.modelDownloadError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = err, color = NasMusicColors.Danger, fontSize = FontSize.small())
                }
            }
            // ── 高质量分离失败详情（本轮长期留存，供用户对着屏幕截图回传）──
            state.hqErrorDetail?.let { detail ->
                Text(
                    text = stringResource(R.string.settings_hq_failure_detail_title),
                    color = NasMusicColors.Danger,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NasMusicColors.SurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = detail,
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.small()
                    )
                }
            }
        }
    }
}
