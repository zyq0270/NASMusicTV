package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.photo.MaskCache
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P2 档「变换」族（§14.3）—— 共 9 种
 *
 * | 效果 | 手法 |
 * |---|---|
 * | [ParallaxSlideTransition] | 两层以**不同速度**平移 + 远层放大 |
 * | [PerspectivePushTransition] | 沿灭点推进 / 后退（缩放 + 淡入淡出） |
 * | [DollyZoomTransition] | 前景放大、背景缩小（希区柯克变焦） |
 * | [BloomTransition] | 径向光晕叠加（`Brush` 缓存于 `prepare`） |
 * | [PageFlipTransition] | 旧图压成折叠条 + 折边阴影 |
 * | [MatrixOverlayTransition] | 数字雨竖条下落（整型散列，零分配） |
 * | [NeonTraceTransition] | 放大副本染色 + `BlendMode.Plus` 描边辉光 |
 * | [BassBloomTransition] | 低频能量驱动缩放（音频反应类） |
 * | [WaveWarpTransition] | 横向条带按正弦位移（波浪扭曲的 M3 近似） |
 *
 * ## 零分配
 *
 * 所有需要复用的矩形 / 画笔 / 渐变都在字段或 `prepare()` 里建好，
 * `render()` 内只有整数与浮点运算。
 */
internal abstract class TransformP2Transition : PhotoTransition {

    /** 复用的目标矩形（缩放 / 位移后的落点） */
    protected val tmp = PhotoRect()

    /** 复用的第二块矩形 */
    protected val tmp2 = PhotoRect()
}

/**
 * 视差滑动（P2）—— 两层以**不同速度**横移。
 *
 * 远层（旧图）走 30% 的距离并**同步放大**（保证画面不露边），
 * 近层（新图）走满全程 ⇒ 观感上两层有**深度差**。
 */
internal class ParallaxSlideTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.PARALLAX_SLIDE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 远层：放大 1 → 1.30 并左移 30% 屏宽
        scaleRect(geom.dstA, 1f + FAR_SCALE * p, tmp)
        drawPhotoAt(a, geom.srcA, tmp, offX = -geom.canvasW * FAR_TRAVEL * p, offY = 0f)

        // 近层：新图从右侧满速滑入
        drawPhotoAt(b, geom.srcB, geom.dstB, offX = geom.canvasW * (1f - p), offY = 0f)
    }

    private companion object {
        /** 远层的放大增量 */
        const val FAR_SCALE = 0.30f

        /** 远层的位移比例（近层是 1.0） */
        const val FAR_TRAVEL = 0.30f
    }
}

/**
 * 3D 纵深推拉（P2）—— 沿**灭点**推进 / 后退。
 *
 * 没有真 3D（项目红线：零 OpenGL / 零 AGSL）⇒ 用「缩放 + 偏移 + 透明度」三件套模拟：
 * - 旧图向灭点**后退**（缩小 + 变淡）
 * - 新图从灭点**迎面推来**（放大 + 变实）
 * - 灭点略低于画面中心 ⇒ 推进带一点俯冲感
 */
internal class PerspectivePushTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.PERSPECTIVE_PUSH

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        val cy = geom.canvasH * 0.5f + geom.canvasH * 0.08f

        // 旧图后退：1.00 → 0.55
        val sa = 1f - 0.45f * p
        tmp.set(
            geom.canvasW * 0.5f - geom.canvasW * 0.5f * sa,
            cy - geom.canvasH * 0.5f * sa,
            geom.canvasW * 0.5f + geom.canvasW * 0.5f * sa,
            cy + geom.canvasH * 0.5f * sa,
        )
        drawPhoto(a, geom.srcA, tmp, alpha = (1f - p).coerceIn(0f, 1f))

        // 新图迎面推来：1.22 → 1.00
        val sb = 1.22f - 0.22f * p
        tmp2.set(
            geom.canvasW * 0.5f - geom.canvasW * 0.5f * sb,
            cy - geom.canvasH * 0.5f * sb,
            geom.canvasW * 0.5f + geom.canvasW * 0.5f * sb,
            cy + geom.canvasH * 0.5f * sb,
        )
        drawPhoto(b, geom.srcB, tmp2, alpha = p.coerceIn(0f, 1f))
    }
}

/**
 * 希区柯克变焦（P2）—— 前景放大、背景缩小。
 *
 * 经典「眩晕」效果的 2D 近似：背景越缩越小（相机后退），前景越放越大（变焦），
 * 两者的反向运动制造出「世界在拉伸」的错觉。
 */
internal class DollyZoomTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.DOLLY_ZOOM

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 背景（旧图）缩小：1.00 → 0.62
        scaleRect(geom.dstA, 1f - 0.38f * p, tmp)
        drawPhoto(a, geom.srcA, tmp, alpha = (1f - p).coerceIn(0f, 1f))

        // 前景（新图）放大：1.55 → 1.00
        scaleRect(geom.dstB, 1.55f - 0.55f * p, tmp2)
        drawPhoto(b, geom.srcB, tmp2, alpha = p.coerceIn(0f, 1f))
    }
}

/**
 * 光晕绽放（P2）—— 新图从中心「绽开」，同时有一圈径向辉光。
 *
 * ## 辉光怎么做（不用 shader）
 *
 * 用 `Brush.radialGradient` 画一个**白色 → 透明**的径向渐变，以 `BlendMode.Plus` 叠加。
 * 渐变本身在 `prepare()` 里建好（半径 = 画布对角线），绘制时只把它**画进一个逐帧变大的方框** ——
 * 方框小于渐变半径的部分自然就是「中心亮、边缘暗」的核心 ⇒ 读感是光晕从中心涨开。
 *
 * ⛔ 渐变对象**不能**每帧新建（`Brush.radialGradient` 每次都要建色标列表）。
 */
internal class BloomTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.BLOOM_TRANSITION

    /** 径向辉光（`prepare` 里建好，稳态每帧零分配） */
    private var glow: Brush? = null

    /** 渐变的中心（画布中心） */
    private var glowCenter = Offset.Zero

    /** 渐变半径（画布对角线） */
    private var glowRadius = 0f

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        glowCenter = Offset(geom.canvasW * 0.5f, geom.canvasH * 0.5f)
        glowRadius = geom.diagonalHalf
        glow = Brush.radialGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0x66FFE9C4), Color(0x00FFFFFF)),
            center = glowCenter,
            radius = glowRadius,
        )
    }

    override fun release() {
        glow = null
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        // 新图从 0.88 绽开到 1.00
        scaleRect(geom.dstB, 0.88f + 0.12f * p, tmp)
        drawPhoto(b, geom.srcB, tmp, alpha = p.coerceIn(0f, 1f))

        val brush = glow ?: return
        // 光晕：半径随进度涨开，强度中段最盛
        val r = geom.diagonalHalf * (0.15f + 0.95f * p)
        val intensity = (0.55f * sin(p * PI.toFloat())).coerceAtLeast(0f)
        if (intensity <= 0.01f) return
        drawRect(
            brush = brush,
            topLeft = Offset(glowCenter.x - r, glowCenter.y - r),
            size = Size(r * 2f, r * 2f),
            alpha = intensity,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * 3D 翻页（P2）—— 旧图像一页纸那样**折起来**并向左收拢，露出下面的新图。
 *
 * ## 2D 假翻页
 *
 * 真翻页需要透视变换（项目红线：零 OpenGL）⇒ 这里用「整幅压成一条竖带」模拟：
 * 旧图的目标矩形从**整幅画布**收窄成右侧的一条（宽度 `W·(1-p)`），
 * 同时高度略作收缩（透视缩短），折边处压一条阴影。
 */
internal class PageFlipTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.PAGE_FLIP

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 新图铺底
        drawPhoto(b, geom.srcB, geom.dstB)

        val w = geom.canvasW
        val h = geom.canvasH
        val bandW = w * (1f - p)
        if (bandW <= 0.5f) return

        // 旧图压成右侧的折叠条（高度随折叠略收 ⇒ 透视缩短）
        val shrink = 1f - 0.18f * p
        val cy = h * 0.5f
        val hh = h * 0.5f * shrink
        tmp.set(w - bandW, cy - hh, w, cy + hh)
        drawPhoto(a, geom.srcA, tmp)

        // 折边阴影
        val shadowW = (w * 0.05f).coerceAtLeast(1f)
        drawRect(
            color = Color(0x99000000),
            topLeft = Offset((w - bandW - shadowW).coerceAtLeast(0f), 0f),
            size = Size(shadowW, h),
        )
    }
}

/**
 * 数字雨覆盖（P2）—— 竖直的绿色字符流覆盖在画面上。
 *
 * ## 为什么用「实心竖条」而不是真字符
 *
 * 真字符要逐字绘制（几十个 `drawText` ⇒ 每帧几十次文本布局）⇒ 真机上不可接受。
 * 这里用**实心竖条**抽象出「代码雨」的观感：条的下端是亮头（正在落），上端拖尾渐隐。
 *
 * ⛔ 随机位置用**整型散列**（确定性、零分配），不用 `Random`。
 */
internal class MatrixOverlayTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.MATRIX_OVERLAY

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 新图铺底，旧图淡出
        drawPhoto(b, geom.srcB, geom.dstB)
        drawPhoto(a, geom.srcA, geom.dstA, alpha = (1f - p).coerceIn(0f, 1f))

        val colW = geom.canvasW / COLS
        for (i in 0 until COLS) {
            val seed = hash(i)
            // 落点：随进度下坠并回绕（⇒ 永远有雨在落）
            val head = ((seed + p * (0.7f + 1.1f * seed)) % 1f)
            val len = 0.06f + 0.20f * hash(i * 31 + 7)
            val y0 = head * geom.canvasH
            val tail = y0 - len * geom.canvasH
            if (tail > geom.canvasH) continue
            val alpha = (0.25f + 0.55f * seed) * (1f - p * 0.35f)
            drawRect(
                color = Color(0xFF39FF6A).copy(alpha = alpha.coerceIn(0f, 1f)),
                topLeft = Offset(i * colW, tail.coerceAtLeast(-geom.canvasH * 0.1f)),
                size = Size(colW * 0.8f, len * geom.canvasH),
            )
        }
    }

    /** 整型散列 → `0..1`（确定性、零分配） */
    private fun hash(v: Int): Float {
        var h = v * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private companion object {
        const val COLS = 32
    }
}

/**
 * 霓虹描边（P2）—— 新图先以**放大副本 + 霓虹染色 + 加色混合**画出辉光，
 * 再把清晰的新图盖上去 ⇒ 视觉上就是「描了一圈霓虹」。
 *
 * ## 染色怎么做
 *
 * `ColorFilter.lighting` 把图像按光源色染色（既有 `CHROMATIC_SPLIT` 同款用法）。
 * ⚠️ 滤镜对象在**字段初始化**时创建（构造期分配），绘制期零分配。
 */
internal class NeonTraceTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.NEON_TRACE

    /** 霓虹染色滤镜（字段初始化 = 构造期分配，稳态零分配） */
    private val neonFilter = ColorFilter.lighting(Color(0xFF35F2FF), Color(0xFF001018))

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        // 辉光：放大 6% 的副本 + 霓虹染色 + 加色混合
        scaleRect(geom.dstB, 1.06f, tmp)
        // 闪烁：随进度做高频摆动（霓虹灯管的抖动）
        val flicker = 0.75f + 0.25f * abs(sin(p * 42f))
        drawImage(
            image = b,
            srcOffset = IntOffset(geom.srcB.left.roundToInt(), geom.srcB.top.roundToInt()),
            srcSize = IntSize(geom.srcB.width.roundToInt(), geom.srcB.height.roundToInt()),
            dstOffset = IntOffset(tmp.left.roundToInt(), tmp.top.roundToInt()),
            dstSize = IntSize(tmp.width.roundToInt(), tmp.height.roundToInt()),
            alpha = (p * flicker).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
            colorFilter = neonFilter,
        )

        // 清晰的新图盖上去（霓虹之外还要看得见图）
        drawPhoto(b, geom.srcB, geom.dstB, alpha = p.coerceIn(0f, 1f))
    }
}

/**
 * 低频绽放（P2，**音频反应类**）—— 新图随**低频能量**涨缩。
 *
 * ⚠️ `audioReactive = true` ⇒ **不进随机池**（§5.9）。
 * ⚠️ 取的是 `geom.bass`（`AudioFrame.bass`，**律动通道**）—— 不是频谱的低频段。
 */
internal class BassBloomTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.BASS_BLOOM

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        // 低频把「绽放」的幅度顶上去（0 → 1 之间）
        val boost = geom.bass.coerceIn(0f, 1f)
        val scale = 0.90f + 0.10f * p + 0.10f * boost * (1f - p)
        scaleRect(geom.dstB, scale, tmp)
        drawPhoto(b, geom.srcB, tmp, alpha = p.coerceIn(0f, 1f))
    }
}

/**
 * 液化（P2）—— 横向条带按**线性剪切**错位（越靠下错得越多）。
 *
 * §4.4 的 M5 降级建议里 `LIQUIFY` 没有合理近似（枚举里 `degradeTo = NOISE_DISSOLVE`），
 * 但这里仍给出一版**能跑**的实现：老平台上它会被枚举层换成 `NOISE_DISSOLVE`，
 * 新平台上（若真接上 shader）会用它做过渡前的铺垫。
 *
 * ## 与 [WaveWarpTransition] 的区别
 *
 * - 波浪位移 = **正弦**（上下起伏、来回摆）
 * - 液化 = **线性剪切**（一端固定、另一端位移最大，像被拉扯）
 */
internal class LiquifyTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.LIQUIFY

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val cy = geom.canvasH * 0.5f
        // 剪切量：中段最大（起落各留 15%），顶部与底部反向错开
        val twist = geom.canvasW * TWIST * sin(p * PI.toFloat()).coerceAtLeast(0f)

        val bandH = geom.canvasH / BANDS
        for (i in 0 until BANDS) {
            val y0 = i * bandH
            val offset = (y0 + bandH * 0.5f - cy) / geom.canvasH * twist
            clipRect(0f, y0, geom.canvasW, y0 + bandH) {
                drawPhotoAt(b, geom.srcB, tmp, offX = offset, offY = 0f)
            }
        }
    }

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        // 新图放大 8%，避免剪切把左右边缘拉出画面
        scaleRect(geom.dstB, 1.08f, tmp)
    }

    private companion object {
        const val BANDS = 24
        const val TWIST = 0.12f
    }
}

/**
 * 波浪位移（P2）—— 横向条带按**正弦**做左右位移。
 *
 * §4.4 对 M5 的降级建议：「正弦条带遮罩 + 相位偏移」。
 * 这里用条带**直接位移**（而不是遮罩）⇒ 位移是真的，边界是阶梯状（条数够多就看不出来）。
 *
 * ⚠️ 条带位移会把新图的左右边缘拉出画面 ⇒ 新图整体放大 6% 兜住。
 */
internal class WaveWarpTransition : TransformP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.WAVE_WARP

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        // 位移幅度：中段最大（起落各留 15%）
        val amp = geom.canvasW * MAX_SHIFT * sin(p * PI.toFloat()).coerceAtLeast(0f)

        val bandH = geom.canvasH / BANDS
        for (i in 0 until BANDS) {
            val y0 = i * bandH
            val phase = y0 / geom.canvasH * WAVES * TWO_PI + p * TWO_PI
            val off = amp * sin(phase)
            clipRect(0f, y0, geom.canvasW, y0 + bandH) {
                drawPhotoAt(b, geom.srcB, tmp, offX = off, offY = 0f)
            }
        }
    }

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        // 新图放大 6%，避免位移把左右边缘拉出画面
        scaleRect(geom.dstB, 1.06f, tmp)
    }

    private companion object {
        const val BANDS = 24
        const val WAVES = 2.5f
        const val MAX_SHIFT = 0.06f
        const val TWO_PI = (PI * 2.0).toFloat()
    }
}
