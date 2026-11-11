package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.photo.MaskCache
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoMaskKind
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * P2 档「遮罩溶解」族（§4.4 的 M3 近似）—— 共 11 种
 *
 * ## 共同的实现套路
 *
 * ```
 * ① 画旧图（全屏，不透明）
 * ② saveLayer 开一个离屏层
 * ③   在层内画新图（全屏）
 * ④   用遮罩以 DstIn 合成 ⇒ 只保留遮罩 alpha 非零的像素
 * ⑤ restore：把该层叠到旧图之上
 * ```
 *
 * 与 `NOISE_DISSOLVE` 同一套离屏画法，差别只在**遮罩的形态**与**推进方式**。
 *
 * ## 为什么这一族能做到「零逐像素成本」
 *
 * 遮罩是**预生成的 256×256 灰度位图**（见 `MaskCache`），运行时只做一次
 * `BlendMode.DstIn` 合成 —— 合成是 GPU/Skia 的硬件路径，不是 Kotlin 层的逐像素循环。
 *
 * ## 「进度」是怎么表达的
 *
 * ⚠️ 遮罩是**静态**的（一次生成、长期复用），所以「随时间推进」必须由
 * **调用方**表达，而不是改遮罩。本族用到的手段：
 *
 * | 手段 | 效果 | 用在 |
 * |---|---|---|
 * | 遮罩整体 `alpha` 递增 | 整幅一起淡入（最弱） | `GRAIN_DISSOLVE` / `WATERCOLOR` |
 * | 遮罩**落点缩放** | 溶解边界从中心推出去 | `INK_SPREAD` / `FROST` / `RIPPLE` |
 * | 遮罩**落点平移** | 溶解边界扫过 | `SAND_DISSOLVE` |
 * | 遮罩**落点竖向拉长**（顶边锚定） | 燃烧前沿自上而下推进 | `BURN` / `COLOR_BURN` / `MELT` |
 * | 遮罩**旋转** | 螺旋臂绕中心甩出 | `SWIRL` |
 * | **收尾补画**（[settleFrom]） | 保证末帧整幅是新图 | `FROST` / `SWIRL` / `RIPPLE` / `MELT` |
 *
 * ⚠️ 「遮罩落点在动」是**把遮罩画到画布上的一个矩形里**实现的 ——
 * 遮罩本身仍是 256×256，只是绘制时的 dst 矩形在动。
 *
 * ## ⛔ 本族**不用** `clipPath`
 *
 * `clipPath` 在 API < 26 的硬件加速路径上会触发 hwui Region 段错误（项目红线 C46），
 * 而目标电视正是 API 22 ⇒ 全部走「遮罩位图 + `DstIn`」，在真机上真的能跑。
 */
internal abstract class MaskDissolveTransition : PhotoTransition {

    /** 本效果用的遮罩种类（由子类给出） */
    internal abstract fun maskKind(): PhotoMaskKind

    /** 遮罩位图（`MaskCache` 全进程共享，**不 release**） */
    private var mask: ImageBitmap? = null

    /** 遮罩的源矩形（整张 256×256） */
    protected val maskSrc = PhotoRect()

    /** 离屏层的边界（画布尺寸） */
    private var layerBounds: Rect = Rect.Zero

    /** 离屏层的画笔（复用，零分配） */
    private val layerPaint = Paint()

    /** 遮罩落点矩形（复用，零分配） */
    protected val maskRect = PhotoRect()

    /** 新图的目标矩形（复用，零分配） */
    protected val imageRect = PhotoRect()

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        mask = masks.mask(maskKind())
        maskSrc.set(0f, 0f, MaskCache.SIZE.toFloat(), MaskCache.SIZE.toFloat())
        layerBounds = Rect(0f, 0f, geom.canvasW, geom.canvasH)
    }

    override fun release() {
        // ⚠️ 只丢引用 —— 遮罩位图由 MaskCache 全进程共享，不能在这里回收
        mask = null
    }

    /**
     * 遮罩在画布上的落点（dst 矩形）—— 子类覆写以实现「平移 / 旋转 / 缩放」。
     *
     * @param out 复用矩形（零分配）
     */
    protected open fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        out.set(0f, 0f, geom.canvasW, geom.canvasH)
    }

    /**
     * 遮罩的整体透明度 —— 子类覆写以实现「随时间淡入」或「随节拍脉冲」。
     *
     * 默认 = `p`（线性跟随进度）。
     *
     * ⚠️ **必须能走到 1**（`p = 1` ⇒ alpha = 1）：任何「乘一个小于 1 的系数」的写法
     * 都会让转场**走不完**（末帧仍有旧图）—— 2026-11-11 电视实测，§10.387。
     */
    internal open fun maskAlpha(p: Float, geom: PhotoGeometry): Float = p

    /** 遮罩旋转角（度，`0` = 不转）—— 子类覆写以实现「旋转推进」 */
    protected open fun maskRotation(p: Float): Float = 0f

    /** 新图的目标矩形（默认照搬几何；子类可加位移 / 缩放） */
    protected open fun imageDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        out.set(geom.dstB.left, geom.dstB.top, geom.dstB.right, geom.dstB.bottom)
    }

    /** 叠加色（可选，`Color.Transparent` = 不叠加） */
    protected open fun overlayColor(): Color = Color.Transparent

    /** 叠加色的强度（`0` = 不叠加） */
    protected open fun overlayAlpha(p: Float): Float = 0f

    /**
     * 「收尾」阈值（`0..1`）：超过它之后**整幅新图**叠加回来。
     *
     * ⛔ 默认 `2f`（= 永不收尾）。凡是遮罩本身**带空洞**的形态（晶界、旋臂、环带、
     * 网点、颗粒）都必须开收尾 —— 否则末帧（新图定格在 HOLD 期）仍有旧图从洞里透出来
     * ⇒ 用户读作「转场没走完」（2026-11-11 电视实测，§10.387）。
     */
    internal open fun settleFrom(): Float = 2f

    /**
     * 画遮罩层（`DstIn`）—— 子类可覆写以改变「遮罩怎么落」（例如平铺）。
     */
    protected open fun DrawScope.drawMaskLayer(
        m: ImageBitmap,
        geom: PhotoGeometry,
        p: Float,
        alpha: Float,
    ) {
        maskDst(geom, p, maskRect)
        val rot = maskRotation(p)
        if (rot != 0f) {
            rotate(rot) {
                drawPhoto(m, maskSrc, maskRect, alpha = alpha, blendMode = BlendMode.DstIn)
            }
        } else {
            drawPhoto(m, maskSrc, maskRect, alpha = alpha, blendMode = BlendMode.DstIn)
        }
    }

    /**
     * 通用绘制：旧图打底 + 遮罩溶解出新图。
     *
     * ⚠️ 本族所有实现共用这一条 `render` ⇒ 子类只管调「形态钩子」，
     * 不各自重写绘制流程（重写就容易漏掉「旧图打底」「收尾补画」这两步）。
     */
    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawMasked(a, b, p, geom)
    }

    /**
     * 通用绘制：旧图打底 + 遮罩溶解出新图。
     */
    protected fun DrawScope.drawMasked(
        a: ImageBitmap,
        b: ImageBitmap,
        p: Float,
        geom: PhotoGeometry,
    ) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val oa = overlayAlpha(p)
        if (oa > 0.01f) {
            drawRect(
                color = overlayColor().copy(alpha = oa),
                topLeft = Offset.Zero,
                size = Size(geom.canvasW, geom.canvasH),
            )
        }

        val m = mask ?: return
        if (p <= 0f || layerBounds.width <= 0f) return

        val alpha = maskAlpha(p, geom)
        if (alpha <= 0f) return

        imageDst(geom, p, imageRect)

        drawIntoCanvas { canvas ->
            canvas.saveLayer(layerBounds, layerPaint)
            drawPhoto(b, geom.srcB, imageRect)
            drawMaskLayer(m, geom, p, alpha)
            canvas.restore()
        }

        // 收尾：末帧必须整幅是新图（遮罩带空洞的形态才需要）
        val settle = settleFrom()
        if (settle <= 1f && p > settle) {
            drawPhoto(b, geom.srcB, imageRect, alpha = (p - settle) / (1f - settle))
        }
    }
}

/**
 * 把 [geom] 的整幅矩形**绕画布中心缩放**再**竖直平移**，写进 [out]（零分配）。
 *
 * ⚠️ 调用方必须保证平移量 ≤ 缩放带来的余量，否则画布边缘会露白。
 */
internal fun scaledShiftedRect(
    geom: PhotoGeometry,
    scale: Float,
    offY: Float,
    out: PhotoRect,
) {
    val cx = geom.canvasW * 0.5f
    val cy = geom.canvasH * 0.5f
    val hw = geom.canvasW * 0.5f * scale
    val hh = geom.canvasH * 0.5f * scale
    out.set(cx - hw, cy - hh + offY, cx + hw, cy + hh + offY)
}

/**
 * 颗粒溶解（P2）—— 细密的白噪点遮罩。
 *
 * 与 `NOISE_DISSOLVE`（fBm 云雾）的区别：这里是**高频白噪声**，颗粒更细更碎。
 *
 * ⚠️ **必须开收尾**：颗粒遮罩的 alpha 是随机的（0..255）⇒ `p = 1` 时仍有旧图
 * 从暗颗粒里透出来 ⇒ 用户读作「转场没走完」（2026-11-11 电视实测，§10.387）。
 */
internal class GrainDissolveTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.GRAIN_DISSOLVE
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.GRAIN

    override fun settleFrom(): Float = 0.85f
}

/**
 * 半调网点（P2）—— 网点遮罩（`MaskCache` 的 `DOTS`：正交排布的圆点）。
 *
 * ## 为什么遮罩要**平铺**而不是整幅拉伸
 *
 * 遮罩是 256×256 的**正方形**，整幅拉伸到 16:9 画布 ⇒ 网点被拉成**椭圆**（横向 7.5 倍、
 * 纵向 4.2 倍），完全不像印刷网点。⇒ 改成把遮罩**平铺**成 4×2 块（每块接近正方形），
 * 网点几乎还是圆的。
 *
 * ## 为什么平铺只用 8 块
 *
 * 每块一次 `DstIn` = 一次合成调用。8 块在 1080p 上是 480×540 的方块，
 * 网点直径约 24 px —— 与真实印刷网点同一量级。
 */
internal class HalftoneTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.HALFTONE
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.DOTS

    /** 平铺列数（每块接近正方形） */
    private val tilesX = 4

    /** 平铺行数 */
    private val tilesY = 2

    /** 平铺用的临时矩形（零分配） */
    private val tileRect = PhotoRect()

    override fun DrawScope.drawMaskLayer(
        m: ImageBitmap,
        geom: PhotoGeometry,
        p: Float,
        alpha: Float,
    ) {
        val tw = geom.canvasW / tilesX
        val th = geom.canvasH / tilesY
        for (row in 0 until tilesY) {
            val y0 = row * th
            for (col in 0 until tilesX) {
                val x0 = col * tw
                tileRect.set(x0, y0, x0 + tw, y0 + th)
                drawPhoto(m, maskSrc, tileRect, alpha = alpha, blendMode = BlendMode.DstIn)
            }
        }
    }

    // 网点之间是真空洞 ⇒ 末帧必须补画整幅新图
    override fun settleFrom(): Float = 0.85f
}

/**
 * 墨迹扩散（P2）—— 大块有机团块遮罩，从中心向外扩散。
 *
 * 实现：团块遮罩 + **放大**落点（团块随进度从小变大 ⇒ 墨迹由中心向外洇开）。
 */
internal class InkSpreadTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.INK_SPREAD
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.BLOBS

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        val s = 0.30f + 0.70f * p
        scaledShiftedRect(geom, s, 0f, out)
    }

    // 团块之间是真空洞 ⇒ 末帧必须补画整幅新图
    override fun settleFrom(): Float = 0.85f
}

/**
 * 水彩晕染（P2）—— 与墨迹同用团块遮罩，但**反向缩放**且更柔和。
 *
 * 反向缩放（1.6 → 1.0）让团块「收拢」而不是扩散，读感上是颜料被水化开。
 */
internal class WatercolorTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.WATERCOLOR
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.BLOBS

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        val s = 1.60f - 0.60f * p
        scaledShiftedRect(geom, s, 0f, out)
    }

    // 团块之间是真空洞 ⇒ 末帧必须补画整幅新图
    override fun settleFrom(): Float = 0.85f
}

/**
 * 沙化（P2）—— 细颗粒遮罩**向下漂移**并略微变粗。
 *
 * 「溶解边界向下扫过」= 遮罩落点整体下移。为了不让画布边缘露白，
 * 落点同时**放大**（放大带来的余量 ≥ 平移量）。
 */
internal class SandDissolveTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.SAND_DISSOLVE
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.GRAIN

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        // 余量 = 0.20H·p ≥ 平移 0.10H·p ⇒ 画布始终被遮罩盖满
        scaledShiftedRect(geom, 1f + 0.40f * p, geom.canvasH * 0.10f * p, out)
    }

    // 颗粒之间是真空洞 ⇒ 末帧必须补画整幅新图
    override fun settleFrom(): Float = 0.85f
}

/**
 * 燃烧（P2）—— 参差燃烧前沿遮罩。
 *
 * ## 「前沿推进」是怎么做的
 *
 * 遮罩里的燃烧前沿是一条**噪声扰动的水平线**，它画死在位图上 ⇒ 要让前沿往下走，
 * 只能**把遮罩竖向拉长**（dst 矩形的顶边锚定在画布顶边，高度按进度拉长）。
 *
 * ⚠️ 拉长会把前沿的噪声一起拉长（末帧约 8 倍）⇒ 正好读作「火舌越烧越大」。
 * 前沿的羽化宽度取得很小（几个像素），拉长后才不至于糊成一片渐变。
 */
internal class BurnTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.BURN
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.BURN

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        // 顶边锚定，高度 1 → 9.5 倍 ⇒ 遮罩里的前沿从 6% 屏高推进到 100%
        out.set(0f, 0f, geom.canvasW, geom.canvasH * (1f + 8.5f * p))
    }

    // 燃烧前沿是**逐列不同**的 ⇒ 末帧仍有几列没烧到，必须补画整幅新图
    override fun settleFrom(): Float = 0.88f
}

/**
 * 色彩烧灼（P2）—— 燃烧遮罩 + **暖色调偏移**。
 *
 * 「色彩烧灼」比单纯的 `BURN` 多一层颜色变化：旧图在烧灼过程中偏暖（像被火光烤过）。
 * 实现：溶解之外，在旧图上叠一层橙色半透明蒙版，随进度淡出。
 */
internal class ColorBurnTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.COLOR_BURN
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.BURN

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        out.set(0f, 0f, geom.canvasW, geom.canvasH * (1f + 8.5f * p))
    }

    // 与燃烧同构：前沿逐列不同 ⇒ 末帧补画整幅新图
    override fun settleFrom(): Float = 0.88f

    override fun overlayColor(): Color = Color(0xFFB3471A)

    override fun overlayAlpha(p: Float): Float {
        // 火光烤过旧图的感觉：中段最浓，两端归零
        val warmth = kotlin.math.sin(p * Math.PI.toFloat())
        return (0.45f * warmth).coerceAtLeast(0f)
    }
}

/**
 * 冰冻结晶（P2）—— 胞晶遮罩。
 *
 * ## 形态
 *
 * `CRYSTAL` 遮罩的 alpha 在**胞晶内部**为 1、**晶界**为 0 ⇒ 新图从一块块「冰壳」
 * 的内部透出来，晶界上留着旧图的裂纹 —— 正是结冰的样子。
 *
 * ## 为什么必须开「收尾」
 *
 * 晶界是**真的洞** ⇒ 末帧若不补画，新图定格后裂纹里仍透出旧图。
 */
internal class FrostTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.FROST
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.CRYSTAL

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        // 0.55 → 1.30：结晶区从中心向四周扩张，末帧盖满画布
        scaledShiftedRect(geom, 0.55f + 0.75f * p, 0f, out)
    }

    override fun settleFrom(): Float = 0.82f
}

/**
 * 脉动溶解（P2，**音频反应类**）—— 遮罩 alpha 随**节拍脉冲**起伏。
 *
 * ⚠️ `audioReactive = true` ⇒ **不进随机池**（§5.9），只能由用户主动选中。
 * ⚠️ 脉冲取自 `geom.pulse`（律动通道，0..1）；无音频时恒 0 ⇒ 退化为普通溶解。
 *
 * ## ⚠️ 脉冲**只能加速、不能减速**（2026-11-11 电视实测修正，§10.387）
 *
 * 初版写成 `boost = 0.35 + pulse` ⇒ **没有音频时（`pulse = 0`）整个转场只能推到 35%**
 * ⇒ 末帧仍有 65% 是旧图 ⇒ 用户读作「转场没走完」。
 * ⇒ 现在改成 `boost = 1 + 0.6 · pulse`：**至少 1 倍**（保证能走完），
 * 有鼓点时最多 1.6 倍（溶解在鼓点上「一顿一顿」地窜出去）。
 */
internal class PulseDissolveTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.PULSE_DISSOLVE
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.GRAIN

    override fun maskAlpha(p: Float, geom: PhotoGeometry): Float {
        // 脉冲只做「加速」：1.0（无音频）~ 1.6（强鼓点）
        val boost = 1f + 0.60f * geom.pulse.coerceIn(0f, 1f)
        return (p * boost).coerceIn(0f, 1f)
    }

    // 颗粒之间是真空洞 ⇒ 末帧必须补画整幅新图
    override fun settleFrom(): Float = 0.85f
}

/**
 * 漩涡（P2）—— 螺旋遮罩 + **旋转**推进。
 *
 * 遮罩绕画布中心转 `p × 360°` ⇒ 螺旋臂从中心甩出、扫过整幅。
 *
 * ⚠️ 螺旋臂之间是**真空洞** ⇒ 必须开「收尾」，否则末帧臂缝里仍透出旧图。
 */
internal class SwirlTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.SWIRL
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.SPIRAL

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        // 遮罩放大到 1.45 倍 ⇒ 旋转时四角仍被遮罩覆盖
        scaledShiftedRect(geom, 1.45f, 0f, out)
    }

    override fun maskRotation(p: Float): Float = p * 360f

    override fun settleFrom(): Float = 0.85f
}

/**
 * 融化流淌（P2）—— 上密下疏的渐变遮罩，向下推进。
 *
 * §4.4 对 M5 的降级建议：「上密下疏渐变遮罩 + 下垂偏移」。
 * 与 [BurnTransition] 同为「顶边锚定的竖向拉长」，但遮罩本身是**渐变**而非前沿。
 */
internal class MeltTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.MELT
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.MELT

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        // 顶边锚定，高度 1 → 10.5 倍 ⇒ 遮罩里的下垂前沿从 4% 屏高推进到 100%
        out.set(0f, 0f, geom.canvasW, geom.canvasH * (1f + 9.5f * p))
    }

    /** 新图额外**下垂 4%** 并竖向拉伸 8% —— 读作「化开后往下坠」 */
    override fun imageDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        val sag = geom.canvasH * 0.04f * (1f - p)
        val stretch = 1f + 0.08f * (1f - p)
        val cy = geom.canvasH * 0.5f
        val hh = geom.canvasH * 0.5f * stretch
        out.set(geom.dstB.left, cy - hh + sag, geom.dstB.right, cy + hh + sag)
    }

    // 前沿逐列不同 ⇒ 末帧补画整幅新图
    override fun settleFrom(): Float = 0.88f
}

/**
 * 波纹扭曲（P2）—— 同心圆环遮罩**由中心向外扩散**。
 *
 * §4.4 对 M5 的降级建议：「同心圆遮罩 + 逐环延迟」。
 * 落点从 0.2 倍放大到 1.25 倍 ⇒ 圆环的半径随进度向外推，读感是涟漪荡开。
 *
 * ⚠️ 环带之间是**真空洞** ⇒ 必须开「收尾」。
 */
internal class RippleTransition : MaskDissolveTransition() {
    override val id: PhotoTransitionId = PhotoTransitionId.RIPPLE
    override fun maskKind(): PhotoMaskKind = PhotoMaskKind.RINGS

    override fun maskDst(geom: PhotoGeometry, p: Float, out: PhotoRect) {
        scaledShiftedRect(geom, 0.20f + 1.05f * p, 0f, out)
    }

    override fun settleFrom(): Float = 0.85f
}
