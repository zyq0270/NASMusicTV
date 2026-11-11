package com.nasmusic.tv.visualizer.photo

import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.RenderContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **门禁：FIT（完整）模式下切到宽高比不同的图，旧图不得有残留**（2026-11-11 电视实测）
 *
 * ## 用户实测现象
 *
 * > 「照片墙设置中，画面适配选择完整，横板图切换到竖版图时，前图不能完全覆盖后图，
 * > 并且后图没有清除，导致有部分后图还显示出来。应该将后图都清除掉。」
 *
 * ## 根因（几何层面，可纯 JVM 断言）
 *
 * `PhotoScaleMode.FIT` 的语义是「整图缩放放得下，不足处留黑边」⇒
 * 每张照片在画布上只占一个**内接矩形**（`dstA` / `dstB`）。
 *
 * ⇒ 两张图**宽高比不同**时，两个内接矩形**大小不同**：
 * 横版图（2000×1000）切竖版图（1000×2000）时，
 * 旧图占「又宽又矮」的矩形、新图占「又窄又高」的矩形
 * ⇒ **旧图有一大片落在新图之外** ⇒ 转场画完（`p = 1`）那一片仍然可见。
 *
 * ⇒ 对策（`PhotoRenderer`）：**FIT + 转场结束后 ⇒ 画面上只画新图**，
 * 且整幅先铺黑底（FIT 的定义就是「留黑边」）。
 *
 * ⚠️ CROP（默认）**不受影响**：那里 `dstA == dstB == 画布`，旧图天然被完全覆盖。
 *
 * ⛔ 本门禁**不需要 Robolectric**：只读 `RenderContext.canvasSize` 与纯几何
 * （`PhotoRect` 刻意用 `Float`，见其 KDoc）。
 */
class PhotoFitResidualGateTest {

    // ────────────────────── ① 几何事实：残留确实存在 ──────────────────────

    /**
     * **前置条件**：FIT 下宽高比不同的两张图，旧图的矩形**大于**新图的矩形
     * ⇒ 旧图有一片「新图覆盖不到」的区域 ⇒ 残留是**真实存在**的，不是错觉。
     */
    @Test
    fun `FIT 下宽高比不同的两张图会留下新图覆盖不到的区域`() {
        val geom = geometry().also {
            it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, aW = 2000, aH = 1000, bW = 1000, bH = 2000)
        }
        assertTrue(
            "前置条件：旧图（横）的 dst 应比新图（竖）的 dst 宽，实际 old=${geom.dstA} new=${geom.dstB}",
            geom.dstA.width > geom.dstB.width + EPS,
        )
        assertTrue(
            "旧图落在新图之外的面积必须大于 0（否则这条门禁没有判别力）",
            residualArea(geom.dstA, geom.dstB) > EPS,
        )
    }

    /** CROP 下两张图的 dst 完全相同 ⇒ **没有**残留（⇒ 不需要「只画新图」的规则） */
    @Test
    fun `CROP 下两张图的 dst 相同因而无残留`() {
        val geom = geometry().also {
            it.update(ctx(1920f, 1080f), PhotoScaleMode.CROP, aW = 2000, aH = 1000, bW = 1000, bH = 2000)
        }
        assertTrue(
            "CROP 的两个 dst 必须都铺满画布：old=${geom.dstA} new=${geom.dstB}",
            abs(geom.dstA.width - 1920f) < EPS && abs(geom.dstB.width - 1920f) < EPS,
        )
        assertEquals(
            "CROP 下旧图完全被新图覆盖 ⇒ 残留面积必须是 0",
            0f,
            residualArea(geom.dstA, geom.dstB),
            EPS,
        )
    }

    /** 宽高比**相同**时，两个内接矩形一致 ⇒ FIT 下也没有残留 */
    @Test
    fun `FIT 下宽高比相同的两张图没有残留`() {
        val geom = geometry().also {
            it.update(ctx(1920f, 1080f), PhotoScaleMode.FIT, aW = 2000, aH = 1000, bW = 1600, bH = 800)
        }
        assertEquals(
            "同宽高比 ⇒ 两个内接矩形相同 ⇒ 残留面积必须是 0",
            0f,
            residualArea(geom.dstA, geom.dstB),
            EPS,
        )
    }

    // ────────────────────── ② 规则本身 ──────────────────────

    @Test
    fun `FIT 且转场结束后只画新图`() {
        assertTrue(
            "FIT + 转场结束 ⇒ 必须只画新图（旧图残留就是用户反馈的那个现象）",
            shouldDrawOnlyNewPhoto(PhotoScaleMode.FIT, 1f),
        )
    }

    @Test
    fun `转场进行中不提前切到只画新图`() {
        for (p in listOf(0f, 0.01f, 0.5f, 0.99f)) {
            assertFalse(
                "转场进行中（p=$p）必须照常走转场逻辑，不能提前只剩新图",
                shouldDrawOnlyNewPhoto(PhotoScaleMode.FIT, p),
            )
        }
    }

    @Test
    fun `CROP 下永远不走只画新图`() {
        assertFalse(
            "CROP 的 dstA == dstB == 画布 ⇒ 旧图天然被覆盖 ⇒ 不需要这条规则（避免影响默认档）",
            shouldDrawOnlyNewPhoto(PhotoScaleMode.CROP, 1f),
        )
    }

    // ────────────────────── 内部实现 ──────────────────────

    /**
     * 「旧图矩形里、新图矩形外」的面积。
     *
     * ⚠️ **不能**用 `(旧宽−新宽) × (旧高−新高)`：两个内接矩形是**各自居中**的，
     * 横版旧图（1920×960）与竖版新图（540×1080）**一宽一高**（旧图更宽、旧图更矮）
     * ⇒ 差值不是矩形、其中一项还是负数。
     * ⇒ 正确口径：**旧图面积 − 交集面积**（交集 = 两矩形的重叠部分）。
     */
    private fun residualArea(old: PhotoRect, new: PhotoRect): Float {
        val iw = (minOf(old.right, new.right) - maxOf(old.left, new.left)).coerceAtLeast(0f)
        val ih = (minOf(old.bottom, new.bottom) - maxOf(old.top, new.top)).coerceAtLeast(0f)
        return old.width * old.height - iw * ih
    }

    private fun ctx(w: Float, h: Float): RenderContext = RenderContext().apply {
        canvasSize = Size(w, h)
        safeAreaPx = 0f
    }

    private fun geometry(): PhotoGeometry = PhotoGeometry()

    private companion object {
        const val EPS = 1e-3f
    }
}
