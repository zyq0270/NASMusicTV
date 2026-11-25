package com.nasmusic.tv.ui.components.picker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 半圆拨盘弹窗外壳：标准 Dialog + 内部 BackHandler。
 *
 * 视觉：半透明遮罩铺满全屏，**无白色卡片、无标题**——拨盘直接浮在遮罩上，
 * 保留底层页面可辨识（所有者 2026-11-23 截图反馈定稿）。
 * 遮罩/底衬都走 [NasMusicColors.DialogScrim] / [NasMusicColors.DialogPanel]：
 * 深色=黑遮罩、浅色=**白遮罩**（⛔ 浅色下用黑遮罩会把整页压灰，文字发闷）。
 *
 * 真 Dialog 是独立窗口，Activity dispatcher 收不到按键，必须在 Dialog 内容里用 BackHandler；
 * 不能用 RegisterDialogBackHandler（那只适用于 Box 覆盖层）。
 *
 * [anchor] 决定弧面朝向：搜索页（按钮在右）传 [ArcAnchor.RIGHT] 圆心在右、向左辐射；
 * 播放页（按钮在左）用默认 [ArcAnchor.LEFT] 圆心在左、向右辐射。
 */
@Composable
fun <T> ArcDialDialog(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onConfirm: (Int, T) -> Unit,
    onDismiss: () -> Unit,
    label: (T) -> String,
    key: (Int, T) -> String,
    dPadMode: Boolean,
    anchor: ArcAnchor = ArcAnchor.LEFT
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler { onDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NasMusicColors.DialogScrim),
            contentAlignment = Alignment.Center
        ) {
            // 底衬：深色半透黑（在遮罩之上再压暗一层）；**浅色透明**。
            // ⛔ 浅色下这一层必须透明：它带了 horizontal 20dp 内缩，比全屏遮罩窄 40dp，
            // 填任何颜色都会出现「中间一块 + 两侧两条」的错位色块（背景不一体）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .background(NasMusicColors.DialogPanel)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ArcDialPicker(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    onConfirm = { index, value ->
                        onConfirm(index, value)
                        onDismiss()
                    },
                    label = label,
                    key = key,
                    dPadMode = dPadMode,
                    anchor = anchor
                )
            }
        }
    }
}
