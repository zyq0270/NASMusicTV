package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * P2 档「遮罩形状」族（§14.3）—— 共 2 种
 *
 * | 效果 | 手法 |
 * |---|---|
 * | [IrisTriangleTransition] | 三角形光圈（复用 `PolygonIrisTransition` 的多边形裁剪） |
 * | [WipeSpiralTransition] | 螺旋扇形擦除（阿基米德螺线 + `clipPath`） |
 *
 * ## ⛔ 本文件**使用** `clipPath` ⇒ 必须在 `PhotoTransitionId.CLIP_BASED` 登记
 *
 * `clipPath` 在 API < 26 的硬件加速路径上会触发 hwui Region 段错误（项目红线 C46），
 * 而目标电视正是 API 22 ⇒ 这两个效果在老平台上由 [PhotoTransitionId.effective]
 * **降级成 `CROSSFADE`**（与 D 组其余 8 个同族效果一致）。
 *
 * ⇒ 也就是说：**API 26+ 上是真正的形状转场，API 22 上是交叉淡化**。
 * 降级发生在枚举层，崩溃前没有任何 Java 层机会（`try/catch(Throwable)` 拦不住 native 崩溃）。
 */
internal class IrisTriangleTransition : PolygonIrisTransition() {

    override val id: PhotoTransitionId = PhotoTransitionId.IRIS_TRIANGLE

    /**
     * 正三角形（顶点朝上），外接圆半径归一化为 1。
     *
     * ⚠️ 终态半径由基类的 [polygonCoverRadius] 按画布算出 ——
     * 三角形盖住矩形所需半径**大于**同半径的圆（圆心到四角的距离恰是对角线一半，
     * 而三角形有凹边的等价问题在 `polygonCoverRadius` 的采样二分里统一处理）。
     */
    override val unitVertices: FloatArray = floatArrayOf(
        0f, -1f,          // 上顶点
        0.8660254f, 0.5f, // 右下
        -0.8660254f, 0.5f, // 左下
    )
}

/**
 * 螺旋擦除（P2）—— 新图沿**阿基米德螺线**扫出。
 *
 * ## 形状
 *
 * 路径 = 圆心 + 螺线采样点（半径随角度线性增长）+ 闭合 ⇒ 填出来的是一把
 * 「扇形扫把」：转过的角度越大、扫过的半径越大。
 *
 * ## 末帧必须盖满整幅
 *
 * 螺线扫 3 圈 ⇒ 任意方向上最远覆盖到 `rMax·(圈数-1)/圈数`。
 * 取 `rMax = 1.8 × 对角线半长` ⇒ 最差方向也有 `1.2 × 对角线半长` > 画布四角距离 ⇒
 * **末帧整幅都是新图**（不需要「收尾补画」）。
 */
internal class WipeSpiralTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.WIPE_SPIRAL

    private val spiral = Path()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val sweep = p * TWO_PI * TURNS
        if (sweep <= 0f) return

        val cx = geom.canvasW * 0.5f
        val cy = geom.canvasH * 0.5f
        val rMax = geom.diagonalHalf * RADIUS_FACTOR

        spiral.reset()
        spiral.moveTo(cx, cy)
        for (i in 0..STEPS) {
            val t = i / STEPS.toFloat()
            val ang = sweep * t
            val r = rMax * t
            spiral.lineTo(cx + cos(ang) * r, cy + sin(ang) * r)
        }
        spiral.close()
        clipPath(spiral) { drawPhoto(b, geom.srcB, geom.dstB) }
    }

    private companion object {
        /** 螺线圈数（3 圈 ⇒ 扇形有明显的「旋」感） */
        const val TURNS = 3f

        /** 末态半径系数（相对对角线半长；见类 KDoc 的覆盖推导） */
        const val RADIUS_FACTOR = 1.8f

        /** 螺线采样点数（3 圈 ⇒ 每圈约 53 个点） */
        const val STEPS = 160

        const val TWO_PI = (PI * 2.0).toFloat()
    }
}
