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
import com.nasmusic.tv.ui.theme.NasOverlayColors

/**
 * 半圆拨盘弹窗外壳：标准 Dialog + 内部 BackHandler。
 *
 * 视觉：半透明深色遮罩铺满全屏，**无白色卡片、无标题**——拨盘直接浮在遮罩上，
 * 保留底层页面可辨识（所有者 2026-11-23 截图反馈定稿）。
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
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
    label: (T) -> String,
    key: (T) -> String,
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
                .background(NasOverlayColors.DialogScrim),
            contentAlignment = Alignment.Center
        ) {
            // 轻薄半透明底衬：让文字在深浅主题下都可辨识，同时能看见底层页面
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .background(NasMusicColors.Background.copy(alpha = 0.28f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ArcDialPicker(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    onConfirm = { value ->
                        onConfirm(value)
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
