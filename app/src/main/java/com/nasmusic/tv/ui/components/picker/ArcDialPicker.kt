package com.nasmusic.tv.ui.components.picker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 弧面锚点（决定放射朝向）：
 * - [LEFT]  圆心在左、向右辐射——弧向右凸「)」，中央项最靠右、上下两端向左收；用于播放页（按钮在最左）。
 * - [RIGHT] 圆心在右、向左辐射——弧向左凸「(」，中央项最靠左、上下两端向右收；用于搜索页（按钮在最右）。
 */
enum class ArcAnchor { LEFT, RIGHT }

/**
 * 通用半圆拨盘选择器（Quick Dial）。
 *
 * 交互模型：垂直内容流 + 中央水平选择带；弧面固定在屏幕空间，内容在其上滚动，
 * 文字沿弧线**放射状旋转**（越靠边缘倾角越大，方向随 [ArcAnchor] 镜像）。
 * - TOUCH：手指纵向滑动时内容**实时跟手**（scrollPx 逐帧 snapTo），松手吸附最近项；点击任一条目即确认；
 * - D_PAD：遥控器下键→内容上顶→下一条进中央行，OK 确认；
 * - 无标题、无独立确认按钮、无提示文字（保持弹窗干净）；
 * - 条目统一配色（与播放页队列拨盘一致）：中央行 Primary、其余 TextPrimary；
 *   ⛔ 不再按来源着色 —— 曲库候选已统一「只列历史」，没有基础项可比对（方案 §3.2.1）。
 *
 * 位置模型（⛔ 唯一真相源）：
 *   每项绝对槽位 = index * itemPx；scrollPx 为滚动偏移，选中第 k 项时 scrollPx = k * itemPx。
 *   屏幕相对位置 pos = (index * itemPx - scrollPx) / itemPx，0 = 正中，正 = 下方，负 = 上方。
 *   视觉参数（rotation / scale / alpha / 颜色 / 字号）一律基于 pos —— 随手势连续变化，
 *   而不是基于静态的 index - selectedIndex（那样只会在松手时跳变，且导致选中项漂移）。
 *
 * 组件无业务耦合：不认识 Song/Album/搜索，业务层备好 items 与确认回调。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun <T> ArcDialPicker(
    items: List<T>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onConfirm: (Int, T) -> Unit,
    label: (T) -> String,
    key: (Int, T) -> String,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 56.dp,
    visiblePerSide: Int = 3,
    dPadMode: Boolean = false,
    anchor: ArcAnchor = ArcAnchor.LEFT
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val itemPx = with(density) { itemHeight.toPx() }
    val lastIndex = items.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, lastIndex)

    // 放射镜像系数：LEFT 弧向右凸「)」；RIGHT 弧向左凸「(」。
    // 每行仍按 Center 对齐（⛔ 不要贴边：贴边会让上下项内收方向反转、弧面形状错乱）。
    val mirrored = anchor == ArcAnchor.RIGHT
    val radSign = if (mirrored) -1f else 1f
    val xSign = if (mirrored) 1f else -1f

    // scrollPx：内容滚动的绝对像素偏移（选中第 k 项 ⇒ scrollPx = k * itemPx）
    val scrollPx = remember {
        Animatable(initialValue = selectedIndex.coerceIn(0, items.lastIndex) * itemPx)
    }
    val dialFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // 外部 selectedIndex 变化（D-Pad / 初始 / 松手吸附）→ 动画到对应槽位
    LaunchedEffect(selectedIndex, itemPx) {
        scrollPx.animateTo(selectedIndex.coerceIn(0, lastIndex) * itemPx, animationSpec = tween(220))
    }

    // TV 上焦点必须落到拨盘本身，否则 D-Pad 事件不会路由到 onPreviewKeyEvent
    LaunchedEffect(dPadMode) {
        if (dPadMode) runCatching { dialFocusRequester.requestFocus() }
    }

    val move: (Int) -> Unit = { delta ->
        val next = (safeIndex + delta).coerceIn(0, lastIndex)
        if (next != safeIndex) onSelectedIndexChange(next)
    }

    val bandColor = NasMusicColors.Primary.copy(alpha = 0.18f)
    val bandBorder = NasMusicColors.Primary

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * (visiblePerSide * 2 + 1))
                .drawBehind {
                    drawRect(
                        bandColor,
                        topLeft = Offset(0f, size.height / 2f - itemPx / 2f),
                        size = Size(size.width, itemPx)
                    )
                    // 圆心侧半圆装饰弧：圆心锚在该侧边缘，向内容方向张开
                    // LEFT = 圆心在左画右半圆；RIGHT = 圆心在右画左半圆
                    val radius = itemPx * (visiblePerSide + 0.2f)
                    val centerX = if (mirrored) size.width else 0f
                    val centerY = size.height / 2f
                    drawArc(
                        color = bandBorder.copy(alpha = 0.35f),
                        startAngle = if (mirrored) 90f else -90f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(centerX - radius, centerY - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                .then(
                    if (dPadMode) {
                        Modifier
                            .focusRequester(dialFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> { move(1); true }
                                    Key.DirectionUp -> { move(-1); true }
                                    Key.Enter, Key.NumPadEnter -> { onConfirm(safeIndex, items[safeIndex]); true }
                                    else -> false
                                }
                            }
                    } else Modifier
                )
                .pointerInput(itemPx, lastIndex) {
                    // 拖拽期间用本地变量累积位移，避免每帧读取 Animatable.value 的竞态
                    var dragScroll = scrollPx.value
                    detectDragGestures(
                        onDragStart = { dragScroll = scrollPx.value },
                        onDragEnd = {
                            // 松手吸附到最近项，并同步选中索引
                            val target = (dragScroll / itemPx).roundToInt().coerceIn(0, lastIndex)
                            scope.launch {
                                scrollPx.animateTo(target * itemPx, animationSpec = tween(160))
                            }
                            onSelectedIndexChange(target)
                        },
                        onDragCancel = {
                            val target = (dragScroll / itemPx).roundToInt().coerceIn(0, lastIndex)
                            scope.launch {
                                scrollPx.animateTo(target * itemPx, animationSpec = tween(160))
                            }
                            onSelectedIndexChange(target)
                        }
                    ) { _, drag ->
                        // 跟手：手指向下(drag.y>0) → 内容下移 → scrollPx 减小；
                        // 手指向上(drag.y<0) → 内容上顶 → scrollPx 增大（下一条进入中央）
                        val next = (dragScroll - drag.y).coerceIn(0f, lastIndex * itemPx)
                        dragScroll = next
                        // onDrag 非 suspend，用 launch 立即 snapTo（无动画，逐帧跟手）
                        // 捕获 val next 而非 var dragScroll，避免快速拖动时协程读到已被改写的值
                        scope.launch { scrollPx.snapTo(next) }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 中央选择带的上下细线（不遮住文字）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .drawBehind {
                        drawRect(
                            bandBorder.copy(alpha = 0.5f),
                            topLeft = Offset(0f, 0f),
                            size = Size(size.width, 1.5f)
                        )
                        drawRect(
                            bandBorder.copy(alpha = 0.5f),
                            topLeft = Offset(0f, size.height - 1.5f),
                            size = Size(size.width, 1.5f)
                        )
                    }
            )

            items.forEachIndexed { index, item ->
                // pos：相对中央选择带的连续位置（0=正中，正=下方，负=上方）
                val pos = (index * itemPx - scrollPx.value) / itemPx
                if (abs(pos) > visiblePerSide + 0.5f) return@forEachIndexed
                val dist = abs(pos)
                val y = pos * itemPx
                // 半圆放射状：中央水平，上下逐级倾斜；朝向随 anchor 镜像
                val rotation = radSign * pos * 7f
                val xShift = xSign * (dist * dist) * itemPx * 0.10f
                val isCentered = dist < 0.5f
                val animScale by animateFloatAsState(
                    targetValue = if (isCentered) 1.1f else (1f - dist * 0.10f).coerceAtLeast(0.7f),
                    animationSpec = tween(120)
                )
                val alpha = (1f - dist * 0.16f).coerceAtLeast(0.4f)
                // 统一配色（与播放页队列拨盘一致）：中央行 Primary、其余 TextPrimary。
                // ⛔ 不按来源着色 —— 曲库候选已统一「只列历史」，无基础项可比对（方案 §3.2.1）；
                //    所有者 2026-11-23 第 7 轮定稿：既然全是历史，深绿就失去区分意义。
                val textColor = if (isCentered) NasMusicColors.Primary else NasMusicColors.TextPrimary

                key(key(index, item)) {
                    Row(
                        modifier = Modifier
                            .height(itemHeight)
                            .graphicsLayer {
                                translationY = y
                                translationX = xShift
                                rotationZ = rotation
                                scaleX = animScale
                                scaleY = animScale
                                this.alpha = alpha
                            }
                            .clickable { onConfirm(index, item) }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label(item),
                            color = textColor,
                            fontSize = if (isCentered) FontSize.title() else FontSize.body(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
