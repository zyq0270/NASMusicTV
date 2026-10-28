package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.lx.LxFailReason
import com.nasmusic.tv.backend.network.lx.LxL1
import com.nasmusic.tv.backend.network.lx.LxL2
import com.nasmusic.tv.backend.network.lx.LxL3
import com.nasmusic.tv.backend.network.lx.LxPlatformMap
import com.nasmusic.tv.backend.network.lx.LxPlatformVerdict
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.LxScriptView
import com.nasmusic.tv.ui.viewmodel.LxVerifyAllState
import com.nasmusic.tv.ui.viewmodel.LxVerifyStage

/**
 * 洛雪子块的视图状态（`SourcesSettingsSection` 卡④ 内，紧贴 Meting 行下方）。
 *
 * 为什么单独成一个 data class：卡④ 的 `musicSources` 是**只读源行**模型（§7.5），
 * 洛雪这里要表达的是「一条取链通道 + N 个脚本 + 一个正在跑的验证」，塞进源行会
 * 立刻产生两套真相。
 */
internal data class LxSubBlockState(
    /** null = 引擎判据还在后台求值（不置灰，避免启动瞬间闪一下灰） */
    val engineAvailable: Boolean? = null,
    val engineReason: String? = null,
    val metingEnabled: Boolean = true,
    val lxEnabled: Boolean = false,
    val scripts: List<LxScriptView> = emptyList(),
    val verifyAll: LxVerifyAllState? = null,
    val platformHint: LxPlatformHint? = null,
    /** 端点只支持网易云时，底部那行灰字（§7.3 最后一条） */
    val neteaseOnlyEndpoint: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

/**
 * 平台提示（§2.4.1 两级表）。⛔ 判据是「声明里有没有」+「验证结果是什么」，
 * **不是当前平台是不是网易** —— 后者会把「这批源当下的能力」固化成代码里的平台判断。
 */
internal sealed interface LxPlatformHint {
    /** 静态：已启用源里没有一份声明当前平台 ⇒ 强提示（警示色） */
    data object NoDeclaredSource : LxPlatformHint

    /** 动态：有验证结果且该平台全部 ❌ ⇒ 信息提示（灰字），语气是「播放将走 Meting」 */
    data class AllFailed(val hoursAgo: Long) : LxPlatformHint
}

internal data class LxSubBlockActions(
    val onToggleEnabled: (Boolean) -> Unit = {},
    val onToggleScript: (String, Boolean) -> Unit = { _, _ -> },
    val onVerify: (String) -> Unit = {},
    val onVerifyAll: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRefreshAll: () -> Unit = {},
    /** 「从 URL 导入」按钮：打开输入框（对话框宿主在 `SettingsScreen`） */
    val onImport: () -> Unit = {},
    /** 输入框确认后的真实导入（`MainViewModel.importLxSource`） */
    val onImportUrl: (String) -> Unit = {},
)

/**
 * 洛雪脚本子块（方案 §7.1）。
 *
 * ⛔ 三条布局红线：
 * 1. **不用嵌套卡片**（会变成「框中框」并多出一段 `cardGap` 空白）；
 * 2. 左侧 2 dp 竖线用 `drawBehind` 画，**不用 `Row{Box+Column}`** —— 后者多一层布局节点，
 *    子块左缘与源行差 2dp + 间距，对不齐；
 * 3. 竖线不可聚焦、不隐藏（置灰时只变淡），焦点按「Meting 行 → 子块各行 → 百度 / Jamendo」线性走。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LxScriptsSubBlock(
    state: LxSubBlockState,
    actions: LxSubBlockActions,
) {
    val engineOk = state.engineAvailable != false
    // Meting 关掉 ⇒ 整块视觉置灰；⛔ 仍可编辑（用户可以先配好脚本，再开 Meting / 洛雪）
    val dimmed = !state.metingEnabled
    val lineColor = NasMusicColors.Border

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = lineColor.copy(alpha = if (dimmed) 0.25f else 0.9f),
                    start = Offset(stroke / 2f, 0f),
                    end = Offset(stroke / 2f, size.height),
                    strokeWidth = stroke,
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .alpha(if (dimmed) 0.6f else 1f),
        ) {
            SettingsSubGroupLabel(stringResource(R.string.settings_subgroup_lx))
            SettingsHint(stringResource(R.string.lx_subgroup_desc))

            SettingSwitch(
                label = stringResource(R.string.lx_enable),
                description = stringResource(R.string.lx_enable_desc),
                checked = state.lxEnabled,
                // 引擎不可用 / Meting 关闭时灰显（保留原值，切回来立刻生效）
                enabled = engineOk && state.metingEnabled,
                onClick = { actions.onToggleEnabled(!state.lxEnabled) },
            )

            if (!state.metingEnabled) SettingsHint(stringResource(R.string.lx_needs_meting))
            if (state.engineAvailable == false) {
                Text(
                    text = stringResource(R.string.lx_engine_unavailable, state.engineReason.orEmpty()),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.small(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }

            // 平台告警（§7.1 / §2.4.1 两级表）：语气必须是「播放将走 Meting」而不是「无法播放」——
            // 洛雪不可用时用户**能听**，丢的只是音质增益
            when (val hint = state.platformHint) {
                LxPlatformHint.NoDeclaredSource -> Text(
                    text = stringResource(R.string.lx_hint_no_declared_source),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.small(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                is LxPlatformHint.AllFailed -> Text(
                    text = stringResource(R.string.lx_hint_platform_all_failed, hint.hoursAgo),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
                null -> Unit
            }

            state.verifyAll?.let { all ->
                if (all.running) {
                    Text(
                        text = stringResource(
                            R.string.lx_verify_all_progress,
                            all.done,
                            all.total,
                            all.currentName.orEmpty(),
                        ),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.small(),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
            }

            if (state.scripts.isEmpty()) {
                SettingsHint(stringResource(R.string.lx_no_scripts))
            } else {
                state.scripts.forEach { view ->
                    LxScriptRow(
                        view = view,
                        engineOk = engineOk,
                        now = state.now,
                        actions = actions,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            val busy = state.scripts.any { it.running }
            val chips = if (busy) LxAction.entries else LxAction.entries.filter { it != LxAction.STOP }
            SettingsChipRow(items = chips, tv = 4, phonePortrait = 2) { action ->
                SettingsChip(
                    label = stringResource(action.labelRes),
                    selected = false,
                    onClick = {
                        when (action) {
                            LxAction.IMPORT -> actions.onImport()
                            LxAction.REFRESH -> actions.onRefreshAll()
                            LxAction.VERIFY_ALL -> actions.onVerifyAll()
                            LxAction.STOP -> actions.onStop()
                        }
                    },
                )
            }
            SettingsHint(
                if (busy) stringResource(R.string.lx_verify_running_hint)
                else stringResource(R.string.lx_verify_all_eta)
            )
            // v2.39.8：内置清单从 2 个扩到 5 个（方案 §2.2 里 L3 实测能播的全部），
            // 每个启用的脚本常驻一个 QuickJS runtime（§9.3 单 runtime < 15 MB）⇒ 必须提示。
            SettingsHint(stringResource(R.string.lx_scripts_memory_hint))
            if (state.neteaseOnlyEndpoint) SettingsHint(stringResource(R.string.lx_endpoint_netease_only))
            SettingsHint(stringResource(R.string.lx_compliance))
        }
    }
}

private enum class LxAction(val labelRes: Int) {
    IMPORT(R.string.lx_import),
    REFRESH(R.string.lx_refresh_all),
    VERIFY_ALL(R.string.lx_verify_all),
    STOP(R.string.lx_verify_stop),
}

/** 子块小标题（约 20 行；§7.1 要求「竖线 + 小标题」，⛔ 不加可聚焦容器） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsSubGroupLabel(text: String) {
    Text(
        text = text,
        color = NasMusicColors.TextPrimary,
        fontSize = FontSize.small(),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun LxScriptRow(
    view: LxScriptView,
    engineOk: Boolean,
    now: Long,
    actions: LxSubBlockActions,
) {
    var expanded by remember(view.entry.id) { mutableStateOf(false) }
    val result = view.result
    val stale = result != null && com.nasmusic.tv.backend.network.lx.LxStatusCodec.isStale(result.ts, now)
    val offSuffix = stringResource(R.string.settings_state_off)
    val title = buildString {
        append(view.entry.name)
        view.entry.version?.takeIf { it.isNotBlank() }?.let { append(" · v").append(it) }
        if (!view.entry.enabled) append(" · ").append(offSuffix)
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FocusableSurface(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.SurfaceVariant,
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.TextPrimary,
                focusedScale = SettingsTokens.focusScaleDisabled,
                showFocusBorder = false,
            ) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    Text(title, color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    Text(
                        text = platformLine(view, stale),
                        color = if (stale) NasMusicColors.TextSecondary.copy(alpha = 0.6f)
                        else NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                    )
                    result?.let { status ->
                        val whenText = if (status.ts > 0) {
                            android.text.format.DateFormat.getTimeFormat(context)
                                .format(java.util.Date(status.ts)).toString()
                        } else ""
                        val suffix = if (stale) " " + stringResource(R.string.lx_verify_stale) else ""
                        Text(
                            text = stringResource(R.string.lx_verify_last_at, whenText) + suffix,
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.caption(),
                        )
                    }
                }
            }
            LxSmallButton(
                label = verifyLabel(view),
                enabled = engineOk && !view.running,
                onClick = { actions.onVerify(view.entry.id) },
            )
            Spacer(modifier = Modifier.width(10.dp))
            LxSmallSwitch(
                label = view.entry.name,
                checked = view.entry.enabled,
                enabled = engineOk && !view.running,
                onClick = { actions.onToggleScript(view.entry.id, !view.entry.enabled) },
            )
        }

        // L1 失败：行下方红字原文（§7.2 状态机「L1 失败」那一行）
        val l1 = view.l1
        if (l1 is LxL1.Failed) {
            Text(
                text = stringResource(R.string.lx_l1_fail, (l1.detail ?: l1.reason.name).take(200)),
                color = NasMusicColors.Danger,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        // v2.39.8：`NotInstalled` 此前**没有任何 UI**（只渲染 `Failed`）⇒ 正文缺失时点「验证」
        // 只看到按钮闪一下变「重试」，行下方空无一字。这是「点了没反应」最典型的一种。
        // 正常路径下 `MainViewModel.runOneLxVerify` 会先补下载，所以这里只在
        // 「没有 URL 可下」或「下载后仍未装上」时出现。
        if (l1 is LxL1.NotInstalled) {
            Text(
                text = stringResource(R.string.lx_l1_not_installed),
                color = NasMusicColors.Warning,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }

        if (expanded) {
            LxScriptDetail(view = view, stale = stale)
        }
    }
}

@Composable
private fun verifyLabel(view: LxScriptView): String = when {
    // v2.39.8：内置源出厂只登记 URL ⇒ 首次验证要先下正文，这一阶段必须有可见文案，
    // 否则用户看到的就是「点一下、闪一下、什么都没变」
    view.running && view.stage == LxVerifyStage.DOWNLOADING -> stringResource(R.string.lx_verify_downloading)
    view.running && view.stage == LxVerifyStage.LOADING -> stringResource(R.string.lx_verify_loading)
    view.running && view.stage == LxVerifyStage.PROBING ->
        stringResource(R.string.lx_verify_progress, view.index + 1, view.total)
    view.stage == LxVerifyStage.FAILED -> stringResource(R.string.lx_verify_retry)
    view.result != null -> stringResource(R.string.lx_verify_again)
    else -> stringResource(R.string.lx_verify)
}

/** 平台串：`kg ✅ FLAC · tx ✅ FLAC · wy ❌ · kw ? · mg ?`（§7.1） */
@Composable
private fun platformLine(view: LxScriptView, stale: Boolean): String {
    val loaded = view.l1 as? LxL1.Loaded
    val declared: List<String>? = loaded?.sources
    return LxPlatformMap.lxSources
        .filter { source -> declared == null || source in declared }
        .joinToString(" · ") { source ->
            val status = view.platformOf(source)
            val token = when {
                view.running && view.currentSource == source -> "⏳"
                status == null -> "?"
                status.verdict == LxPlatformVerdict.PLAYABLE ->
                    "✅ " + (status.container?.name ?: status.quality ?: "")
                status.verdict == LxPlatformVerdict.DOUBTFUL -> "⚠️"
                status.verdict == LxPlatformVerdict.DEAD -> "❌"
                else -> when (status.reason) {
                    LxFailReason.SKIPPED_BY_DEPTH -> "–"
                    LxFailReason.BUDGET -> "⏳"
                    else -> "?"
                }
            }
            "$source $token".trim()
        }
}

/** 内联展开的实测明细（§7.3）：只在本会话跑过验证时有内容 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LxScriptDetail(view: LxScriptView, stale: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.lx_verify_detail_title),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.small(),
            fontWeight = FontWeight.Bold,
        )
        if (view.livePlatforms.isEmpty()) {
            Text(
                text = stringResource(R.string.lx_verify_no_detail),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption(),
            )
        } else {
            LxPlatformMap.lxSources.forEach { source ->
                val probe = view.livePlatforms[source] ?: return@forEach
                if (probe.attempts.isEmpty()) {
                    Text(
                        text = "$source · " + reasonText(probe.reason),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.caption(),
                    )
                } else {
                    probe.attempts.forEach { attempt ->
                        Text(
                            text = stringResource(
                                R.string.lx_detail_row,
                                source,
                                attempt.quality,
                                l2Text(attempt.l2),
                                l3Text(attempt.l3),
                                verdictText(probe.verdict),
                            ),
                            color = if (stale) NasMusicColors.TextSecondary.copy(alpha = 0.6f)
                            else NasMusicColors.TextSecondary,
                            fontSize = FontSize.caption(),
                        )
                    }
                }
                if (probe.qualityMismatch) {
                    Text(
                        text = stringResource(R.string.lx_quality_mismatch),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.caption(),
                    )
                }
            }
        }
        // wy 失败时的固定建议（§7.3）：这是把「平台选错了」和「源坏了」区分开的唯一界面
        val wyFailed = view.platformOf("wy")?.verdict == LxPlatformVerdict.DEAD
        if (wyFailed) {
            Text(
                text = stringResource(R.string.lx_hint_wy_dead),
                color = NasMusicColors.Warning,
                fontSize = FontSize.caption(),
            )
        }
    }
}

@Composable
private fun l2Text(l2: LxL2): String = when (l2) {
    is LxL2.Url -> "✅"
    LxL2.Empty -> "—"
    is LxL2.Error -> "❌"
}

@Composable
private fun l3Text(l3: LxL3?): String = when (l3) {
    null -> "—"
    is LxL3.Ok -> "${l3.code} " + (l3.magic ?: l3.contentType?.substringBefore(';') ?: "")
    is LxL3.Doubtful -> "${l3.code} ?"
    is LxL3.Dead -> "${l3.code} " + reasonText(l3.reason)
    is LxL3.Error -> reasonText(l3.reason)
}

@Composable
private fun verdictText(verdict: LxPlatformVerdict): String = when (verdict) {
    LxPlatformVerdict.PLAYABLE -> "✅ " + stringResource(R.string.lx_platform_playable)
    LxPlatformVerdict.DOUBTFUL -> "⚠️ " + stringResource(R.string.lx_platform_doubtful)
    LxPlatformVerdict.DEAD -> "❌ " + stringResource(R.string.lx_platform_dead)
    LxPlatformVerdict.UNTESTED -> "? " + stringResource(R.string.lx_platform_untested)
}

/** 失败原因的人话（§7.3 的表；⛔ `NO_ANCHOR` 与 `SKIPPED_BY_DEPTH` 不合并） */
@Composable
internal fun reasonText(reason: LxFailReason?): String = when (reason) {
    LxFailReason.HTTP_403 -> stringResource(R.string.lx_reason_auth)
    LxFailReason.HTTP_404 -> stringResource(R.string.lx_reason_not_found)
    LxFailReason.HTTP_429 -> stringResource(R.string.lx_reason_rate)
    LxFailReason.HTTP_5XX -> stringResource(R.string.lx_reason_upstream)
    LxFailReason.NOT_AUDIO -> stringResource(R.string.lx_reason_not_audio)
    LxFailReason.EMPTY_URL, LxFailReason.BAD_URL, LxFailReason.SCRIPT_ERROR ->
        stringResource(R.string.lx_reason_bad_url)
    LxFailReason.TIMEOUT, LxFailReason.BUDGET, LxFailReason.CANCELLED ->
        stringResource(R.string.lx_reason_timeout)
    LxFailReason.NO_ANCHOR -> stringResource(R.string.lx_reason_no_anchor)
    LxFailReason.SKIPPED_BY_DEPTH -> stringResource(R.string.lx_reason_skipped_depth)
    LxFailReason.L1_TIMEOUT, LxFailReason.L1_SCRIPT_ERROR, LxFailReason.L1_NO_SOURCES,
    LxFailReason.L1_NOT_INSTALLED -> stringResource(R.string.lx_l1_generic)
    null -> ""
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LxSmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .height(portraitTouchTarget(36.dp))
            .semantics { contentDescription = label },
        containerColor = NasMusicColors.SurfaceVariant,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        shape = SettingsTokens.chipShape,
        showFocusBorder = false,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (enabled) NasMusicColors.TextPrimary
                else NasMusicColors.TextSecondary.copy(alpha = 0.5f),
                fontSize = FontSize.small(),
                maxLines = 1,
            )
        }
    }
}

/** 行内小开关：复用 `SettingSwitch` 的轨道视觉，但只有轨道大小（方案 §7.1 的 `[ 开 ]`） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LxSmallSwitch(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stateText = stringResource(if (checked) R.string.settings_state_on else R.string.settings_state_off)
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.semantics { contentDescription = "$label，$stateText" },
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        shape = SettingsTokens.chipShape,
        focusedScale = SettingsTokens.focusScaleDisabled,
    ) {
        Box(modifier = Modifier.padding(4.dp)) {
            SwitchTrack(checked = checked, enabled = enabled)
        }
    }
}
