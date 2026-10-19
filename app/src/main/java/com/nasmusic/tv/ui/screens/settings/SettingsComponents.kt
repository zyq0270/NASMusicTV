package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * R-2 拆分：设置页共享组件（原 SettingsScreen.kt 私有组件迁出并放开可见性）。
 *
 * 命名注意（W0 约定）：InfoRow 统一命名为 [SettingsInfoRow]，
 * 避免与 SongInfoPanel.InfoRow 同名混淆。
 */

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = FontSize.subtitle(),
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
    )
}

/**
 * 设置分组卡片：卡片头 + 内部留白；行组件保持原样嵌入（方案 §4.1/§4.2）。
 *
 * ⛔ 卡片**不可聚焦**（不加 `FocusableSurface`）：TV 上焦点必须逐行落在真实控件上，
 * 卡片参与聚焦会多出一跳、破坏既有 `navFocusRequesters` 节奏（§4.6）。
 */
@Composable
internal fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = SettingsTokens.cardGap)
            .clip(SettingsTokens.cardShape)
            .background(NasMusicColors.Surface.copy(alpha = 0.45f))
            .border(1.dp, NasMusicColors.Border, SettingsTokens.cardShape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp).height(14.dp)
                    .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = NasMusicColors.Primary,
                fontSize = FontSize.button(),
                fontWeight = FontWeight.Bold
            )
        }
        if (description != null) {
            Text(
                text = description,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 27.dp, end = 16.dp, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsTokens.cardInnerHPadding).padding(bottom = SettingsTokens.cardInnerHPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsTokens.rowGap),
            content = content
        )
    }
}

/** 分区内的分组小标题（用于"网盘"下区分百度/其他） */
@Composable
internal fun SubSectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = FontSize.button(),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp, start = 4.dp, top = 4.dp)
    )
}

/**
 * 设置区**唯一**的可聚焦瓦片（方案 §4.7-②，门禁 G7-B）。
 *
 * 存在理由：同一份 `FocusableSurface` 参数块此前在设置区有 7 份，圆角 4 种、`focusedScale`
 * 6 种、动画时长 4 种、按压缩放 3 种 —— 逐个组件手改参数必然再次漂移。这里收成一份，
 * 取值全部来自 [SettingsTokens]，分区里**不准**再出现裸 `FocusableSurface(`。
 *
 * 语义只有四个开关，覆盖设置页的全部瓦片形态：
 * - [enabled]：置灰。⚠️ `onClick` 里**必须**判一次 —— `FocusableSurface` 仍可聚焦，
 *   单靠颜色挡不住遥控器的「确认」键。
 * - [selected]：「已选中」的**唯一**表达（`Primary@0.18` 底 + Primary 字，§4.7-③ S3）。
 *   ⛔ 设置页不出现实底 Primary —— 那是唯一主 CTA（连接页「连接」按钮）的语义。
 * - [danger]：破坏性动作（§4.7-③ S6）。文字与焦点底都取 `Warning`；
 *   ⚠️ 方案 §4.7-② 的片段里 danger 会落进 `else` 分支拿到 `Primary@0.15` 焦点底，
 *   与 S6「焦点底也取 Warning」冲突 —— 这里以 S6 为准，danger 单列一支。
 * - [chip]：小尺寸可选项（芯片 / ± 按钮）。圆角、缩放、按压、外边距各降一档。
 *
 * ⛔ 别在这里加 `height()`：芯片的高度由调用方给（`portraitTouchTarget(48.dp)`），
 * 行的高度由内容撑开 —— 统一写死会把 `BackupFileRow` 的 64dp 方块也压扁。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    chip: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent = if (danger) NasMusicColors.Warning else NasMusicColors.Primary
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.padding(vertical = if (chip) 0.dp else 4.dp),
        shape = if (chip) SettingsTokens.chipShape else SettingsTokens.rowShape,
        focusedScale = when {
            !enabled -> SettingsTokens.focusScaleDisabled
            chip -> SettingsTokens.focusScaleChip
            else -> SettingsTokens.focusScaleRow
        },
        pressedScale = if (chip) SettingsTokens.pressScaleChip else SettingsTokens.pressScaleRow,
        animationDurationMs = SettingsTokens.animMs,
        containerColor = when {
            !enabled -> NasMusicColors.Surface.copy(alpha = 0.5f)
            selected -> accent.copy(alpha = 0.18f)
            else -> NasMusicColors.Surface
        },
        contentColor = when {
            !enabled -> NasMusicColors.TextSecondary
            selected -> accent
            danger -> NasMusicColors.Warning
            else -> NasMusicColors.TextPrimary
        },
        focusedContainerColor = when {
            !enabled -> NasMusicColors.SurfaceVariant
            selected -> accent.copy(alpha = 0.3f)
            danger -> accent.copy(alpha = 0.18f)
            else -> NasMusicColors.Primary.copy(alpha = 0.15f)
        },
        focusedContentColor = when {
            !enabled -> NasMusicColors.TextSecondary
            danger -> accent
            else -> NasMusicColors.TextPrimary
        },
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f),
        content = content,
    )
}

/**
 * 行瓦片的统一内容排版：标签 + 说明（左，占权重）+ 尾部（右）。方案 §4.7-②。
 *
 * 说明用 `FontSize.small()` + 固定 [NasMusicColors.TextSecondary]（§4.7-④）：此前
 * `SettingSwitch` / `SettingActionButton` 的说明是 `FontSize.body()` + 跟随焦点的
 * `LocalFocusableContentColor`，而 GENERAL 的语言/方向说明是 `small()` + 固定色 ——
 * 同为"行说明"两套规格。变色还会让「标签 + 说明」在聚焦时同时提亮，反而削弱层级差。
 */
@Composable
private fun SettingsTileBody(
    label: String,
    description: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = LocalFocusableContentColor.current, fontSize = FontSize.button())
            if (description != null) {
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = FontSize.small())
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    SettingsTile(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        SettingsTileBody(label = label, description = description) {
            // ⚠️ 尾部这里仍是 WiFi 开关文案（T12 换成 SwitchTrack + settings_on/off）：
            // T11 只收"取值"，diff 里不许出现语义变化（方案 §4.7 与 T12 开头互证）。
            Text(
                text = if (checked) stringResource(R.string.settings_wifi_on) else stringResource(R.string.settings_wifi_off),
                color = if (checked) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                fontSize = FontSize.button()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayModeSelector(current: PlayMode, onSelect: (PlayMode) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.settings_play_mode),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayMode.values().forEach { mode ->
                SettingsChip(
                    label = stringResource(mode.displayNameRes),
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingActionButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    /**
     * v2.35.0 修复：允许外部覆盖布局宽度。
     *
     * 默认仍为 `fillMaxWidth()`（占满整行，设置页纵向列表的既定样式）。
     * 但**放在 `Row` 里时必须传入固定宽度** —— 否则第一个按钮会撑满整行，
     * 后续按钮被挤成 0 宽（表现为"网络源音质只有『自动』一个选项"）。
     * 该缺陷在 v2.35.0 之前就存在（音质档位行原本就是 Row + 4 个此组件）。
     */
    modifier: Modifier = Modifier.fillMaxWidth(),
    /**
     * 阶段 11 新增：置灰（低画质档的人脸检测，§15.3 T11.4）。
     *
     * ⚠️ 与 [SettingSwitch] 的 `enabled` 同一套视觉口径（`Surface.copy(alpha = 0.5f)` +
     * `TextSecondary`），并且 `onClick` 里**必须**判一次 —— `FocusableSurface` 仍可聚焦，
     * 单靠颜色挡不住遥控器的「确认」键。
     */
    enabled: Boolean = true,
) {
    SettingsTile(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        SettingsTileBody(label = label, description = description) {
            Text(
                text = stringResource(R.string.common_confirm),
                color = NasMusicColors.Primary,
                fontSize = FontSize.button()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BackupFileRow(
    file: com.nasmusic.tv.util.BackupFileUtils.BackupFile,
    onRestore: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsTile(
            onClick = onRestore,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NasMusicColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = file.displayName, color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    Text(
                        text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(file.lastModified)),
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body()
                    )
                }
                Text(
                    text = stringResource(R.string.settings_import_backup),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.body()
                )
            }
        }
        if (onDelete != null) {
            // 删除是设置区唯一一处**现状就已经用语义色**的瓦片（§4.7-③ S6 的样本），
            // 收口到 `danger = true` 后其余破坏性按钮在 T12 跟上。
            SettingsTile(
                onClick = onDelete,
                modifier = Modifier.size(64.dp),
                danger = true,
                chip = true,
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(vertical = 20.dp)
                )
            }
        }
    }
}

@Composable
internal fun AboutRow(label: String, value: String) {
    // 修复（2026-09-22 用户反馈）：原「标签 + weight Spacer + 值」结构在值过长时
    // Spacer 塌缩、值紧跟标签（视觉上忽左忽右）。改为固定双栏：标签占左（超长换行），
    // 值恒右对齐（超长换行也按右缘对齐）。手机 / TV 一致。
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding)
    ) {
        SettingsLabelValueRow(
            label = label,
            value = value,
            labelFontSize = FontSize.button(),
            valueFontSize = FontSize.button(),
            valueWeight = FontWeight.Normal,
        )
    }
}

/**
 * 「标签 + 值」的唯一版式（方案 §4.7-④）：标签占 1 份、值占 1.5 份，值恒右对齐。
 *
 * 存在理由：`AboutRow` 与 `SettingsInfoRow` 是同一个版式的两套实现，后者用
 * `Arrangement.SpaceBetween` 且**不限宽** —— 长值会把标签挤没（竖屏实测）。收成一份后
 * 两处都吃这个 1 : 1.5 的双栏。⛔ 不要给值加 `maxLines`：长值必须折行，不许省略号截断。
 */
@Composable
private fun SettingsLabelValueRow(
    label: String,
    value: String,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    valueWeight: FontWeight,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = labelFontSize,
            modifier = Modifier.weight(1f)
        )
        // 竖屏修正（20:40 用户反馈）：值不限定权重时长文本会吃掉整行、把 weight
        // 标签挤没。改为 标签 1 : 值 1.5 的比例分配（约 40%/60%），长值在右栏内
        // 换行且恒右对齐，标签任何形态下都保持可见。
        Text(
            text = value,
            color = NasMusicColors.TextPrimary,
            fontSize = valueFontSize,
            fontWeight = valueWeight,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.5f).padding(start = 16.dp)
        )
    }
}

/**
 * 格式化 [com.nasmusic.tv.data.model.VersionInfo] 为 (label, value) 对。
 *
 * - Static：有版本 → (服务名, 版本号)；有 description → (服务名, 版本号·描述)
 * - Runtime：有版本 → (服务名, 版本号)；无 → (服务名, 未连接)
 * - NoVersion：无版本 → (服务名, "") 仅展示服务名
 * - Disconnected：(服务名, 未连接)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun formatVersionInfo(v: com.nasmusic.tv.data.model.VersionInfo): Pair<String, String> {
    return when (v) {
        is com.nasmusic.tv.data.model.VersionInfo.Static -> {
            if (v.description.isNotBlank()) {
                v.serviceName to "${v.version} · ${v.description}"
            } else {
                v.serviceName to v.version
            }
        }
        is com.nasmusic.tv.data.model.VersionInfo.Runtime -> {
            v.serviceName to v.version
        }
        is com.nasmusic.tv.data.model.VersionInfo.NoVersion -> {
            v.serviceName to ""
        }
        is com.nasmusic.tv.data.model.VersionInfo.Disconnected -> {
            v.serviceName to stringResource(R.string.settings_not_connected)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AdjustButton(text: String, onClick: () -> Unit) {
    SettingsTile(
        onClick = onClick,
        // 48dp 在竖屏只有 39.4 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 48dp，B1）
        // ⚠️ 这个字面量是 `SmallTouchTargetScanTest` 的抓手，⛔ 不要令牌化（§4.7-⑥ 已说明）。
        modifier = Modifier.size(portraitTouchTarget(48.dp)),
        chip = true,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, color = LocalFocusableContentColor.current, fontSize = FontSize.title(), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 设置页共享芯片（单选组用）。
 *
 * 从 `PhotoWallSettingsSection` 的私有 `OptionChip` 提升而来；T11 起**内部委托
 * [SettingsTile]**，选中态因此与行瓦片共用同一种表达（`Primary@0.18` 底 + Primary 字，
 * §4.7-③ S3）—— 此前芯片是**实底 Primary + 黑字**，与行瓦片的 `0.18` 底是两套。
 *
 * ⛔ **宽度必须由内容决定**（文字 + padding），不能用 `fillMaxSize()`：
 * 本组件总是被放在 `Row` 里并排，而 `Row` 给每个 wrap-content 子项的最大宽度是
 * 「本行剩余宽度」——`fillMaxSize()` 会取这个最大值 ⇒ **第一个 chip 撑满整行，
 * 同排后续 chip 被挤成 0 宽**（v2.37.0「画面适配只显示满屏」、v2.35.0「音质只有自动」
 * 两次线上实例，护栏见 `ChipContentWidthScanTest`）。
 * 高度由 `portraitTouchTarget(48.dp)` 给定（同样是那道门的抓手，⛔ 别换写法），故内容只填高。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsTile(
        onClick = onClick,
        modifier = modifier.height(portraitTouchTarget(48.dp)),
        selected = selected,
        chip = true,
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = SettingsTokens.chipHPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                // 芯片恒单行：高度被 `portraitTouchTarget(48.dp)` 钉死，折行会溢出。
                maxLines = 1
            )
        }
    }
}

/**
 * 下载统计行：标签 + 值（W0 约定重命名：原 InfoRow → SettingsInfoRow）。
 *
 * 版式与 `AboutRow` 共用 [SettingsLabelValueRow]（1 : 1.5 双栏、值恒右对齐）——
 * 此前这里用 `Arrangement.SpaceBetween` 且不限宽，长值会把标签挤没（§4.7-④）。
 * 水平边距取 `rowHPadding`，与行瓦片的内边距对齐，标签不再比上面的开关行凸出 20dp。
 *
 * @param description 只读行按 §4.5 规范**默认不带**功能说明；只有该项"删了有代价"
 *   需要警示时才给（T8b 的 9 条缓存代价说明）。`null` = 不占第二行。
 * @param indent 从属行缩进（T8b 占用一览的分组 → 条目两级）；分组行/顶层行保持 `false`。
 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    description: String? = null,
    indent: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = SettingsTokens.rowHPadding + if (indent) 16.dp else 0.dp,
            end = SettingsTokens.rowHPadding,
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SettingsLabelValueRow(
                label = label,
                value = value,
                labelFontSize = FontSize.body(),
                valueFontSize = FontSize.body(),
                valueWeight = FontWeight.Medium,
            )
        }
        if (description != null) {
            Text(
                text = description,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** 格式化字节数为人类可读字符串 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        else -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    }
}
