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
import androidx.compose.ui.graphics.Color
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        animationDurationMs = 250,
        containerColor = if (enabled) NasMusicColors.Surface else NasMusicColors.Surface.copy(alpha = 0.5f),
        contentColor = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
        focusedContainerColor = if (enabled) NasMusicColors.Primary.copy(alpha = 0.15f) else NasMusicColors.SurfaceVariant,
        focusedContentColor = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
        pressedScale = 0.98f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary, fontSize = FontSize.button())
                Text(text = description, color = LocalFocusableContentColor.current, fontSize = FontSize.body())
            }
            // Switch indicator
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
                val selected = current == mode
                FocusableSurface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 250,
                    containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
                    contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
                    focusedContentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
                    pressedScale = 0.95f
                ) {
                    Text(text = stringResource(mode.displayNameRes), color = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
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
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = if (enabled) 1.03f else 1f,
        animationDurationMs = 250,
        containerColor = if (enabled) NasMusicColors.Surface else NasMusicColors.Surface.copy(alpha = 0.5f),
        contentColor = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
        focusedContainerColor = if (enabled) NasMusicColors.Primary.copy(alpha = 0.15f) else NasMusicColors.SurfaceVariant,
        focusedContentColor = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
        pressedScale = 0.98f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, color = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary, fontSize = FontSize.button())
                Text(text = description, color = LocalFocusableContentColor.current, fontSize = FontSize.body())
            }
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
        FocusableSurface(
            onClick = onRestore,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.03f,
            animationDurationMs = 250,
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
            focusedContentColor = NasMusicColors.TextPrimary,
            pressedScale = 0.98f,
            focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
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
            FocusableSurface(
                onClick = onDelete,
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.06f,
                animationDurationMs = 200,
                containerColor = NasMusicColors.Surface,
                contentColor = NasMusicColors.Warning,
                focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.18f),
                focusedContentColor = NasMusicColors.Warning,
                pressedScale = 0.96f,
                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.weight(1f)
        )
        // 竖屏修正（20:40 用户反馈）：值不限定权重时长文本会吃掉整行、把 weight
        // 标签挤没。改为 标签 1 : 值 1.5 的比例分配（约 40%/60%），长值在右栏内
        // 换行且恒右对齐，标签任何形态下都保持可见。
        Text(
            text = value,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
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
    FocusableSurface(
        onClick = onClick,
        // 48dp 在竖屏只有 39.4 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 48dp，B1）
        modifier = Modifier.size(portraitTouchTarget(48.dp)),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.1f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.95f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, color = NasMusicColors.TextPrimary, fontSize = FontSize.title(), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 设置页共享芯片（单选组用）：选中态是**唯一一种表达**（实底 Primary + 黑字）。
 *
 * 从 `PhotoWallSettingsSection` 的私有 `OptionChip` 提升而来（T10 先用，T11 把
 * 照片墙的三处调用点一起换过来）。
 *
 * ⛔ **宽度必须由内容决定**（文字 + padding），不能用 `fillMaxSize()`：
 * 本组件总是被放在 `Row` 里并排，而 `Row` 给每个 wrap-content 子项的最大宽度是
 * 「本行剩余宽度」——`fillMaxSize()` 会取这个最大值 ⇒ **第一个 chip 撑满整行，
 * 同排后续 chip 被挤成 0 宽**（v2.37.0「画面适配只显示满屏」、v2.35.0「音质只有自动」
 * 两次线上实例，护栏见 `ChipContentWidthScanTest`）。
 * 高度由外层 `Modifier.height(portraitTouchTarget(48.dp))` 给定，故内容只填高。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.height(portraitTouchTarget(48.dp)),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
        contentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,
        focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
        focusedContentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,
        pressedScale = 0.96f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) Color.Black else LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

/**
 * 下载统计行：标签 + 值（W0 约定重命名：原 InfoRow → SettingsInfoRow）。
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
        modifier = Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body()
            )
            Text(
                text = value,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.body(),
                fontWeight = FontWeight.Medium
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
