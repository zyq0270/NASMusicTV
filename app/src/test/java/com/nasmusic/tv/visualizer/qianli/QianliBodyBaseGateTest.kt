package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.SHANSHUI_TAG_BODY_BASE
import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **G-BODY-1~4**：E50 中景山体「按山体自身轮廓着色」的不变量门禁。
 *
 * ## 为什么需要这组门禁
 *
 * 真机曾出现**中景山脚一条水平直边**（真机 2712×1220 截图：最强水平边在 y=673 = fracY 0.552
 * = `MID_Y1`，边像素 788，第二名仅 317）。根因链条：
 *
 * ① 上游 `index.html:1875-1881` 的山体白底 = `poly(ptlist[0].concat([[0, reso[0]*4]]), {fil:"white"})`
 *    ⇒ 形状是「脊线 + 一个远点」的**三角形**；
 * ② 上游 `:4028` 整幅 SVG 带 `mix-blend-mode:multiply` ⇒ 纯白 × 米白纸底 = 纸底
 *    ⇒ 这块白底在纸底上**完全隐形** ⇒ **上游的山根本没有「块」，也就没有下缘**；
 * ③ E50 把同一块白底涂成实色（山体本色/青绿）⇒ 它的下缘露出来；
 * ④ 且白底下延 40 世界单位（25.7 px）越过中景带下沿 ⇒ 被**层带位图裁断** ⇒ 水平直边。
 *
 * ⇒ 修法（所有者裁定「方案 A」）：**实色填充按山体自身轮廓（脊线 + 底缘折线）着色**，
 * 底缘由移植层以派生数据 `SHANSHUI_TAG_BODY_BASE` 导出（⛔ 上游那条三角形白底照抄保留，
 * 只是不再当可见色块用）。
 *
 * 本组门禁钉住三件**不许回退**的事：
 * - 底缘 = 上游点阵末行（逐位相等）⇒ 不会被人手抄成别的形状；
 * - 上游三角形白底仍在 ⇒ 移植层语义没被动过；
 * - 底缘**不是水平直线**，且映射后**不出层带底** ⇒ 不会再出现那条直边。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliBodyBaseGateTest {

    private fun mountainContours(): List<ShanShuiContour> {
        resetPortGlobals()
        return ShanShui.mountain(0.0, 300.0, 12.0, 300.0, 500.0, 200.0, true, null)
    }

    /**
     * 复位 port 包的全局状态（与 `ShanShuiPortGateTest` 同口径）。
     *
     * ⚠️ **必须复位**：`mountain()` 与 `mountainGrid()` 都会消费全局 PRNG
     * （`mountainPtlist` 的 `hoff += rnd()*yoff/100`）⇒ 不复位则两次调用拿到**不同的流**
     * ⇒ 「逐位相等」判据必然假红。
     */
    private fun resetPortGlobals() {
        ShanShuiPrng.global.seed(157)
        ShanShuiNoise.noiseSeed(20250101.0)
        ShanShuiPlanner.resetPlanmtx()
    }

    /** G-BODY-1：移植层必须发出「山体底缘」派生轮廓，且与**上游点阵末行逐位相等**。 */
    @Test
    fun gBody1_bodyBaseMatchesUpstreamGridLastRowBitwise() {
        val tagged = mountainContours().filter { it.tag == SHANSHUI_TAG_BODY_BASE }
        assertEquals("必须恰好一条 bodyBase 派生轮廓", 1, tagged.size)
        val base = tagged[0]
        assertEquals("bodyBase = 上游点阵末行（reso1 = 50 点）", 50, base.pts.size)
        assertTrue("bodyBase 是派生数据：不得带填充/描边", base.fill == null && base.stroke == null)

        // ⚠️ **必须复位**：`mountain()` 刚消费过全局 PRNG（`mountainPtlist` 的
        //    `hoff += rnd()*yoff/100`）⇒ 不复位则 `mountainGrid()` 拿到的是**另一条流**
        //    ⇒ 点阵的 `hoff` 不同 ⇒ 「逐位相等」判据必然假红。
        resetPortGlobals()
        val grid = ShanShui.mountainGrid(0.0, 300.0, 12.0, 300.0, 500.0)
        val last = grid[grid.size - 1]
        for (i in base.pts.indices) {
            assertEquals("bodyBase 第 $i 点 x 必须与上游末行逐位相等", last[i][0], base.pts[i][0], 0f)
            assertEquals("bodyBase 第 $i 点 y 必须与上游末行逐位相等", last[i][1], base.pts[i][1], 0f)
        }

        // 负向自证：若误取**首行**（山脊）当底缘，本断言必须判红。
        val first = grid[0]
        var differs = false
        for (i in base.pts.indices) {
            if (first[i][1] != base.pts[i][1]) {
                differs = true
                break
            }
        }
        assertTrue("负向自证：bodyBase ≠ 山脊首行（否则门禁抓不住取错行）", differs)
    }

    /** G-BODY-2：⛔ 上游语义未动 —— 那条「脊线 + 远点」的三角形白底必须照抄保留。 */
    @Test
    fun gBody2_upstreamWhiteBgTriangleStillPresent() {
        val contours = mountainContours()
        val triangle = contours.firstOrNull { c ->
            val f = c.fill
            f != null && f.r >= 235 && f.g >= 235 && f.b >= 235 &&
                c.stroke == null && c.pts.size == 51 &&
                c.pts.last()[0] == 0f && kotlin.math.abs(c.pts.last()[1] - 340f) < 1e-3f
        }
        assertTrue("上游 WHITE BG（脊线 50 点 + 远点 1 点 = 51 点）必须照抄保留", triangle != null)

        // 负向自证：bodyBase 的末点 ≠ 那个远点 ⇒ 两者不是同一条轮廓。
        val base = contours.first { it.tag == SHANSHUI_TAG_BODY_BASE }
        assertNotEquals(
            "bodyBase 不得就是上游那条三角形",
            triangle!!.pts.last()[1],
            base.pts.last()[1],
        )
    }

    /** G-BODY-3：底缘**不是水平直线** ⇒ 着色后的山脚下缘不会是一条尺子线。 */
    @Test
    fun gBody3_bodyBaseEdgeIsNotHorizontal() {
        val base = mountainContours().first { it.tag == SHANSHUI_TAG_BODY_BASE }
        val ys = base.pts.map { it[1] }
        val distinct = ys.distinct().size
        assertTrue("底缘的 y 必须有起伏（distinct=$distinct）⇒ 下缘不是水平直线", distinct > 1)

        // 负向自证：一条「全等 y」的合成直线**必须**不满足同一判据。
        val flat = List(base.pts.size) { base.pts[0].copyOf() }
        assertEquals("负向自证：全等 y 的合成行 distinct 必须为 1", 1, flat.map { it[1] }.distinct().size)
        assertFalse("负向自证：全等 y 的合成行不可通过「底缘非水平」判据", flat.map { it[1] }.distinct().size > 1)
    }

    /**
     * G-BODY-4：真实中景 chunk 的底缘映射进层带后，**不得触到层带底边**
     * （触到 = 被位图裁断 = 那条水平直边）。
     */
    @Test
    fun gBody4_midBodyBaseStaysInsideBand() {
        val renderer = QianliScrollRenderer()
        val chunks = renderer.gateChunksForGate(QianliScrollRenderer.GateLayer.MID)
        assertTrue("中景必须有 chunk", chunks.isNotEmpty())
        val map = renderer.bandMapForGate(QianliScrollRenderer.GateLayer.MID, H)
        val bandBottom = map.bandHeightPx.toDouble()

        var checked = 0
        for (chunk in chunks) {
            val base = chunk.contours.firstOrNull { it.tag == SHANSHUI_TAG_BODY_BASE } ?: continue
            val mapped = base.pts.map { map.bandY(chunk.baseY, it[1].toDouble()).toDouble() }
            val maxY = mapped.max()
            assertTrue(
                "底缘映射后的最大 y（$maxY）必须小于层带底（$bandBottom）⇒ 不会被裁断",
                maxY < bandBottom,
            )
            assertTrue(
                "底缘映射后仍须有起伏 ⇒ 不是水平直线",
                mapped.distinct().size > 1,
            )
            checked++
        }
        assertTrue("至少要检查到一条真实中景底缘", checked > 0)
    }

    /**
     * G-BODY-5：真实中景 chunk 的**映射基线必须有错落**（山脚不能排成一条线）。
     *
     * ⛔ 这条专门盯住一次真实回归：曾把基线写成 `min(带底−余量, 带底+Δ·spread)` 的**钳位**，
     * 而未钳位值恒大于钳位值 ⇒ **所有 chunk 的基线被压成同一个值**（实测极差 = **0.0 px**）
     * ⇒ 山脚排成一把尺子 ⇒ 问题从「被带底切一刀」变成「排成一条直线」，**问题没解决**。
     */
    @Test
    fun gBody5_midFeetAreStaggeredNotOneLine() {
        val renderer = QianliScrollRenderer()
        val chunks = renderer.gateChunksForGate(QianliScrollRenderer.GateLayer.MID)
        assertTrue("中景必须有 chunk", chunks.isNotEmpty())
        val map = renderer.bandMapForGate(QianliScrollRenderer.GateLayer.MID, H)
        val mapped = chunks.map { map.bandY(it.baseY, it.baseY).toDouble() }
        val spread = mapped.max() - mapped.min()
        assertTrue(
            "映射后的山脚基线极差必须 ≥ 20px（实测 $spread）⇒ 山脚不能排成一条直线",
            spread >= 20.0,
        )
    }

    /**
     * G-BODY-6：水层与中景层**必须共用同一套基线错落**（否则「水挂山脚」失效）。
     *
     * 上游的水挂在各自 mount 的基线上 ⇒ 两层必须用**同一个** `baseSpread` + `reservePx`。
     * （曾经水层用 0.45、中景用 0.12 ⇒ 同一座山的水被映射到别的高度。）
     */
    @Test
    fun gBody6_waterAndMidShareTheSameBaseMapping() {
        val renderer = QianliScrollRenderer()
        val mid = renderer.bandMapForGate(QianliScrollRenderer.GateLayer.MID, H)
        val water = renderer.bandMapForGate(QianliScrollRenderer.GateLayer.WATER, H)
        assertEquals(
            "水层与中景层的基线错落系数必须相同（水挂山脚）",
            mid.baseSpread,
            water.baseSpread,
            0.0,
        )
        assertEquals(
            "水层与中景层的带底余量必须相同（同一张映射）",
            mid.reservePx,
            water.reservePx,
            0.0,
        )
        // 同一座山的世界基线 ⇒ 两层映射出的基线必须落在同一处。
        for (baseY in listOf(300.0, 450.0, 600.0)) {
            assertEquals(
                "世界基线 $baseY 处，水与中景的基线必须重合",
                mid.bandY(baseY, baseY),
                water.bandY(baseY, baseY),
                0.01f,
            )
        }
    }

    private companion object {
        const val H = 1080
    }
}
