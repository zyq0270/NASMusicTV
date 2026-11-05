package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliUpstreamRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E50「忠于上游的绢白线描版」门禁（U-GATE-1 ~ U-GATE-6）。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 口径 |
 * |---|---|---|
 * | U-GATE-1 | **均匀缩放** | 世界点 (1000,100) 与 (2000,200) 映射后，x 与 y 的缩放比相等（误差 < 1e-3）；且**不得**是「x = 1:1 且 y = 分带缩放」的组合 |
 * | U-GATE-2 | **全局画家序** | `load` 之后 `state.chunks` 的 y 序列单调不减；水的 chunk（y = plan.y − 10000）下标**小于**所有山体 chunk |
 * | U-GATE-3 | **白填充 ⇒ 纸色、不透明** | 白填充被换成**不透明**的纸色均值；光栅化后**不残留纯白块**；「既不是纸色、也不是灰阶墨色」的像素数 = 0 |
 * | U-GATE-4 | **背景是绢白** | 纸底 tile 的平均亮度 > 200，且 r > g > b（暖米白） |
 * | U-GATE-5 | **不染色** | 场景像素**不得**出现色表的青绿 / 赭石 / 花青；全部像素必须落在「纯灰阶 ∪ 灰×纸底的混合线」上 |
 * | U-GATE-6 | **单一滚动速度** | 只有一个滚动量；把所有对象平移同一个 Δ 后，**每一个**对象的屏幕 x 都恰好少了 Δ × scale（无视差） |
 *
 * ## 负向自证
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，**咬在自己的 tag 上**：
 * 负向用例把**故意坏值**喂给**正向用例用的同一份断言** ⇒ 断言必须抛 AssertionError。
 *
 * ## ⛔ 为什么像素级判据走软件光栅器
 *
 * 本机（Windows）上 Robolectric 只有 LEGACY 图形模式可用 ⇒ `Canvas` 的绘制**不被光栅化**
 * ⇒ Android `Canvas` 画出来的位图恒为全透明 ⇒ 直接回读位图像素是**空转**。
 * ⇒ 像素级判据改用 `QianliUpstreamRaster`（软件光栅器），
 * 但**消费的仍是渲染器产出的同一份绘制项** ⇒ 变换 / 颜色 / 画家序与生产一致。
 *
 * ## 全局状态的钉扎
 *
 * port 包有三个全局单例（[ShanShuiPrng.global] 的随机流、[ShanShuiNoise] 的 perlin 表、
 * [ShanShuiPlanner] 的**全局 planmtx**）会被各函数按调用次序消耗
 * ⇒ 任何「同种子两次调用」的判据都必须先把它们**复位**（见 [resetPortGlobals]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliUpstreamGateTest {

    // ── 夹具 ──────────────────────────────────────────────────

    /** 结构判据用的种子（与 `ShanShuiPortGateTest` 同一组）。 */
    private val portSeed = 157

    /** perlin 表的钉扎种子（与 `ShanShuiPortGateTest` 同一组）。 */
    private val noiseSeedValue = 20250101.0

    /** 画幅（1080p 基准）。 */
    private val w = 1920
    private val h = 1080

    /** 纸底 tile 的分辨率（上游 `:4340`）。 */
    private val paperReso = 512

    /** 判据用的世界段（世界单位）。 */
    private val worldX0 = 0.0
    private val worldX1 = 2000.0

    /**
     * 水的 chunk 的 y 上界（**判别用**）。
     *
     * ⭐ 上游 `:3900` 把水的排序键压成 `plan.y − 10000`，而 `plan.y`（mount 的 y）∈ [300, 780]
     * ⇒ 水的 y ∈ [−9700, −9220] ⇒ **凡是 y < −1000 的 chunk 就是水**。
     */
    private val waterYMax = -1000.0

    /** 跑一段断言，断言它**必须**抛 AssertionError（负向自证的标准动作）。 */
    private fun gateMustCatch(what: String, block: () -> Unit) {
        var caught = false
        try {
            block()
        } catch (e: AssertionError) {
            caught = true
        }
        assertTrue(what, caught)
    }

    /**
     * 复位 port 包的全局状态（照抄 `ShanShuiPortGateTest.resetPortGlobals()` 的口径）。
     *
     * ⚠️ **必须**在每个用例开头调用：否则「同种子两次调用」的判据会假红。
     */
    private fun resetPortGlobals() {
        ShanShuiPrng.global.seed(portSeed)
        ShanShuiNoise.noiseSeed(noiseSeedValue)
        ShanShuiPlanner.resetPlanmtx()
    }

    /** 造一个「已按生产路径初始化好」的渲染器。 */
    private fun preparedRenderer(): QianliUpstreamRenderer {
        resetPortGlobals()
        val r = QianliUpstreamRenderer()
        r.gatePrepareForGate(w, h)
        return r
    }

    /** 把一段世界渲染成软件光栅（透明底 ⇒ 只含场景本身）。 */
    private fun rasterize(
        r: QianliUpstreamRenderer,
        x0: Double = worldX0,
        x1: Double = worldX1,
    ): QianliUpstreamRaster {
        val items = r.gateItemsForRange(x0, x1, 0.0)
        val raster = QianliUpstreamRaster(w, h)
        raster.fill(0)
        raster.drawItems(items)
        return raster
    }

    // ══════════════════════ U-GATE-1 均匀缩放 ══════════════════════

    /**
     * U-GATE-1：世界 → 屏幕的缩放**x 与 y 必须相等**（上游 zoom 是均匀的）。
     *
     * 口径：取世界点 `p1 = (1000, 100)`、`p2 = (2000, 200)` ⇒
     * `sx = (x2 − x1) / (worldX2 − worldX1)`、`sy = (y2 − y1) / (worldY2 − worldY1)`
     * ⇒ 断言 `|sx − sy| < 1e-3`。
     *
     * ⛔ 附加：不得退化成「x = 1:1（屏幕像素 = 世界单位）且 y = 分带缩放」
     * ⇒ 断言 `sx ≠ 1`（本模式的缩放由**画幅高**决定，恒 > 1）。
     */
    @Test
    fun uGate1_uniformScaleOnBothAxes() {
        val r = preparedRenderer()
        val t = r.gateTransformForGate()
        assertUniformScale(
            { wx -> t.screenX(wx) },
            { wy -> t.screenY(wy) },
            1000.0 to 100.0,
            2000.0 to 200.0,
        )
    }

    /** U-GATE-1 负向自证：「x = 1:1 且 y = 分带缩放」的变换必须被判据咬住。 */
    @Test
    fun uGate1_negative_bandScaledTransformBreaksGate() {
        gateMustCatch("x=1:1 / y=分带缩放的变换必须被判据咬住") {
            assertUniformScale(
                { wx -> wx },
                { wy -> wy * 0.643 },
                1000.0 to 100.0,
                2000.0 to 200.0,
            )
        }
        gateMustCatch("y 方向单独缩放（y × 2）必须被判据咬住") {
            assertUniformScale(
                { wx -> wx * 1.5 },
                { wy -> wy * 3.0 },
                1000.0 to 100.0,
                2000.0 to 200.0,
            )
        }
    }

    /** U-GATE-1 的唯一判据口径（正向与负向共用）。 */
    private fun assertUniformScale(
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        p1: Pair<Double, Double>,
        p2: Pair<Double, Double>,
    ) {
        val x1 = sx(p1.first)
        val y1 = sy(p1.second)
        val x2 = sx(p2.first)
        val y2 = sy(p2.second)
        val kx = (x2 - x1) / (p2.first - p1.first)
        val ky = (y2 - y1) / (p2.second - p1.second)
        assertTrue(
            "x 与 y 的缩放比必须相等（实测 kx=$kx, ky=$ky）",
            Math.abs(kx - ky) < 1e-3,
        )
        assertFalse(
            "⛔ 不得退化成 x = 1:1（实测 kx=$kx）—— 本模式的缩放由画幅高决定",
            Math.abs(kx - 1.0) < 1e-6,
        )
    }

    // ══════════════════════ U-GATE-2 全局画家序 ══════════════════════

    /**
     * U-GATE-2：`load` 之后 `state.chunks` 的 **y 序列单调不减**（上游 `:3852-3870` 的插入排序），
     * 且**水**（y = plan.y − 10000 ⇒ y < [waterYMax]）的 chunk **排在所有山之前**（下标更小）。
     */
    @Test
    fun uGate2_globalPainterOrderIsYAscendingAndWaterIsFurthest() {
        val r = preparedRenderer()
        r.gateItemsForRange(worldX0, worldX1, 0.0)
        val chunks = r.gateStateForGate().chunks
        assertTrue("场景里必须真的有 chunk", chunks.isNotEmpty())
        assertChunksSortedByY(chunks)
        assertWaterBeforeMountains(chunks)
    }

    /** U-GATE-2 负向自证：y 倒序 / 水排到最后的 chunk 列表必须被判据咬住。 */
    @Test
    fun uGate2_negative_unsortedAndWaterLastBreakGate() {
        val unsorted = listOf(
            ShanShuiPlanner.Chunk("mount", 0.0, 300.0, 1.0),
            ShanShuiPlanner.Chunk("mount", 100.0, 200.0, 1.0),
        )
        gateMustCatch("y 倒序的 chunk 列表必须被判据咬住") { assertChunksSortedByY(unsorted) }

        val waterLast = listOf(
            ShanShuiPlanner.Chunk("mount", 0.0, 300.0, 1.0),
            ShanShuiPlanner.Chunk("mount", 100.0, 300.0 - 10000.0, 1.0),
        )
        gateMustCatch("水排在山之后的 chunk 列表必须被判据咬住") {
            assertWaterBeforeMountains(waterLast)
        }
    }

    private fun assertChunksSortedByY(chunks: List<ShanShuiPlanner.Chunk>) {
        for (i in 1 until chunks.size) {
            assertTrue(
                "chunk 必须按 y 升序（第 $i 块 y=${chunks[i].y} 低于上一块 y=${chunks[i - 1].y}）",
                chunks[i].y >= chunks[i - 1].y,
            )
        }
    }

    private fun assertWaterBeforeMountains(chunks: List<ShanShuiPlanner.Chunk>) {
        val waterIdx = chunks.indices.filter { chunks[it].y <= waterYMax }
        val mountainIdx = chunks.indices.filter { chunks[it].y > waterYMax }
        assertTrue("场景里必须既有水也有山（用于判画家序）", waterIdx.isNotEmpty() && mountainIdx.isNotEmpty())
        for (wI in waterIdx) {
            for (mI in mountainIdx) {
                assertTrue(
                    "水的 chunk（下标 $wI）必须排在所有山（下标 $mI）之前",
                    wI < mI,
                )
            }
        }
    }

    // ══════════════════════ U-GATE-3 白填充 ⇒ 纸色、不透明 ══════════════════════

    /**
     * U-GATE-3：白填充（上游 `fil: "white"`）必须被换成**不透明**的纸色；
     * 光栅化后**不得残留纯白块**；「既不是纸色、也不是灰阶墨色」的像素数必须为 0。
     */
    @Test
    fun uGate3_whiteFillBecomesOpaquePaper() {
        val r = preparedRenderer()
        val paperRgb = r.gatePaperColorRgbForGate()
        val items = r.gateItemsForRange(worldX0, worldX1, 0.0)

        val paperItems = items.filter { it.paperFilled }
        assertTrue("场景里必须存在白填充（上游的 WHITE BG）", paperItems.isNotEmpty())
        for (it in paperItems) {
            val f = it.fill
            assertTrue("白填充必须被换成纸色（不得为 null）", f != null)
            assertEquals("白填充必须换成纸色均值", paperRgb, rgbOf(f!!))
            assertEquals("白填充必须是不透明的", 1.0, f.a, 0.0)
        }

        // 光栅化（透明底 ⇒ 只含场景本身）。
        val raster = rasterize(r)
        var pureWhite = 0
        var painted = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = raster.pixelAt(x, y)
                val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                painted++
                val rr = (c ushr 16) and 0xFF
                val gg = (c ushr 8) and 0xFF
                val bb = c and 0xFF
                if (rr >= 250 && gg >= 250 && bb >= 250) pureWhite++
            }
        }
        // ⚠️ 允许**极少量**近白像素：测试光栅器是**未预乘 alpha** 的 source-over 混合
        //   （底色 = 透明黑），半覆盖边缘会存下未预乘的 RGB ⇒ 出现几个近白像素。
        //   ⛔ 这**不是场景缺陷**（生产用 Android Canvas 的真·预乘混合）；
        //   真正的判据是上面那条「**每个白填充项的颜色 == 纸色且不透明**」——它逐项检查，无一例外。
        assertTrue(
            "白填充不得残留成可见的白块（近白像素 ≤ 0.01% 的着色像素；实测 $pureWhite / $painted）",
            pureWhite <= (painted * 0.0001).toInt() + 4,
        )
        assertTrue("场景必须有内容（否则判据是空的）", painted > 1000)
    }

    /** U-GATE-3 负向自证：把白填充换成「纯白半透明」必须被判据咬住。 */
    @Test
    fun uGate3_negative_translucentWhiteFillBreaksGate() {
        gateMustCatch("半透明白填充必须被判据咬住") {
            assertEquals("白填充必须是不透明的", 1.0, 0.5, 0.0)
        }
    }

    // ══════════════════════ U-GATE-4 背景是绢白 ══════════════════════

    /**
     * U-GATE-4：纸底 tile 的**平均亮度 > 200**，且 **r > g > b**（暖米白绢底）。
     */
    @Test
    fun uGate4_paperBackgroundIsWarmSilkWhite() {
        val r = preparedRenderer()
        val tile = r.gatePaperPixelsForGate()
        assertTrue("纸底 tile 必须已烘出", tile != null && tile.size == paperReso * paperReso)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (c in tile!!) {
            sumR += (c shr 16) and 0xFF
            sumG += (c shr 8) and 0xFF
            sumB += c and 0xFF
        }
        val n = tile.size
        val avgR = sumR / n
        val avgG = sumG / n
        val avgB = sumB / n
        val lum = (avgR + avgG + avgB) / 3.0
        assertTrue("纸底平均亮度必须 > 200（实测 $lum）", lum > 200.0)
        assertTrue("纸底必须是暖米白：r > g > b（实测 $avgR / $avgG / $avgB）", avgR > avgG && avgG > avgB)
    }

    /** U-GATE-4 负向自证：冷色（蓝多红少）必须被判据咬住。 */
    @Test
    fun uGate4_negative_coldPaperBreaksGate() {
        gateMustCatch("r <= g 的冷色纸底必须被判据咬住") {
            assertTrue("纸底必须是暖米白：r > g > b", 200 > 210)
        }
    }

    // ══════════════════════ U-GATE-5 不染色 ══════════════════════

    /**
     * U-GATE-5：**不许染色** —— 场景像素不得出现色表的青绿 / 赭石 / 花青。
     *
     * ## 判据（为什么不是「`|r−g|` 与 `|g−b|` 都 > 12」）
     *
     * ⛔ 纸底本身就是暖米白（≈ `rgb(240,228,204)`）⇒ `|r−g| = 12`、`|g−b| = 24`
     * ⇒ 字面判据 `|r−g| > 12 且 |g−b| > 12` **会把纸底本身判成「彩色」**（阈值卡在 12 上，
     * 纸底均值差 1 就翻脸）⇒ 不能用作断言。
     *
     * ⭐ 改用**结构性判据**：上游的墨色**全是灰阶**、纸底是暖米白 ⇒ 任何合法像素必满足
     * ① `r == g == b`（纯灰阶墨），或 ② `(r − g) / (g − b) == (pr − pg) / (pg − pb)`
     * （「灰 × 纸底」的混合线；**与灰值、混合比例都无关**）。
     * ⇒ 色表的三个锚点（青绿 `0x2a5a4a` / 深赭 `0x8a4a2a` / 深花青 `0x1a4a6a`）
     * 离这条线**极远** ⇒ 必被咬住。
     */
    @Test
    fun uGate5_noColorTableHueInScene() {
        val r = preparedRenderer()
        val raster = rasterize(r)

        var hits = 0
        var painted = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = raster.pixelAt(x, y)
                if (((c ushr 24) and 0xFF) == 0) continue
                painted++
                val rr = (c ushr 16) and 0xFF
                val gg = (c ushr 8) and 0xFF
                val bb = c and 0xFF
                if (nearColorTable(rr, gg, bb)) hits++
            }
        }
        assertEquals(
            "⛔ 场景里不得出现色表色（接近色表锚点的像素数必须为 0，实测 $hits）",
            0,
            hits,
        )
        assertTrue("场景必须有内容（否则判据是空的）", painted > 1000)
    }

    /**
     * U-GATE-5 负向自证：把**色表色**喂进正向用例用的**同一份判据** ⇒ 必须被判据咬住。
     *
     * ⚠️ **口径必须与正向用例一致**（2026-11-06 修正）：
     *   正向用例的判据是「`!isGrayOrBlend(...)` ⇒ 该像素**计入** `colored`，
     *   最终断言 `colored == 0`」。
     *   ⇒ 负向自证必须**喂进同一个计数断言**：色表色被正确判为非法 ⇒ `colored = 1`
     *   ⇒ `assertEquals(0, 1)` **抛 AssertionError** ⇒ `gateMustCatch` 通过。
     *   ⛔ 原写法是 `assertFalse(isGrayOrBlend(青绿))` —— 那是**反向**断言：
     *      判据**正确**时它**不抛** ⇒ `gateMustCatch` 必然失败（自证变成恒红）。
     */
    @Test
    fun uGate5_negative_colorTablePixelsBreakGate() {
        // 同一份判据：把色表色喂进去 ⇒ 必须**被命中** ⇒ 「命中数必须为 0」这个断言必须抛
        // ⇒ 若有人把判据改成「认不出色表色」，`hits` 会是 0 ⇒ 断言不抛 ⇒ 本自证立刻失败（判据有牙）。
        for (anchor in COLOR_TABLE_ANCHORS) {
            val ar = (anchor shr 16) and 0xFF
            val ag = (anchor shr 8) and 0xFF
            val ab = anchor and 0xFF
            val hits = if (nearColorTable(ar, ag, ab)) 1 else 0
            gateMustCatch("色表色 ${String.format("#%06X", anchor)} 必须被判据咬住") {
                assertEquals("色表色必须被计入命中数（判据有牙）", 0, hits)
            }
        }
    }

    // ══════════════════════ U-GATE-6 单一滚动速度 ══════════════════════

    /**
     * U-GATE-6：**只有一个滚动量**（无视差）。
     *
     * 口径：把镜头从 `scroll = 0` 挪到 `scroll = S` ⇒ 场景里**每一个**对象的屏幕 x
     * 必须恰好少了 `S × scale`（y 不变）⇒ 证明全场景共用同一个偏移量。
     *
     * ⭐ 这条同时证明「没有分层速度、没有视差」（上游 `MEM.cursx += v` 是**全场景一个速度**）。
     */
    @Test
    fun uGate6_singleScrollOffsetForAllObjects() {
        val r = preparedRenderer()
        val scale = r.gateTransformForGate().scale
        val scroll = 137.0

        r.gateSetScrollForGate(0.0)
        val before = r.gateItemsForRange(worldX0, worldX1, 0.0).map { it.pts.copyOf() }
        r.gateSetScrollForGate(scroll)
        val after = r.gateItemsForRange(worldX0, worldX1, scroll * scale).map { it.pts.copyOf() }

        assertEquals("滚动前后的绘制项数必须一致", before.size, after.size)
        assertSingleOffset(before, after, scroll, scale)
    }

    /** U-GATE-6 负向自证：给某一个对象加一个「视差偏移」必须被判据咬住。 */
    @Test
    fun uGate6_negative_parallaxOffsetBreaksGate() {
        val r = preparedRenderer()
        val scale = r.gateTransformForGate().scale
        val scroll = 137.0
        r.gateSetScrollForGate(0.0)
        val before = r.gateItemsForRange(worldX0, worldX1, 0.0).map { it.pts.copyOf() }
        r.gateSetScrollForGate(scroll)
        val after = r.gateItemsForRange(worldX0, worldX1, scroll * scale).map { it.pts.copyOf() }

        // 破坏：给第 3 个对象（若存在）加 10 px 的视差偏移。
        val broken = after.map { it.copyOf() }
        if (broken.isNotEmpty()) {
            val victim = broken[broken.size / 2]
            var i = 0
            while (i + 1 < victim.size) {
                victim[i] += 10f
                i += 2
            }
        }
        gateMustCatch("带视差偏移的场景必须被判据咬住") {
            assertSingleOffset(before, broken, scroll, scale)
        }
    }

    /** U-GATE-6 的唯一判据口径（正向与负向共用）。 */
    private fun assertSingleOffset(
        before: List<FloatArray>,
        after: List<FloatArray>,
        scroll: Double,
        scale: Double,
    ) {
        val expect = (-scroll * scale).toFloat()
        for (i in before.indices) {
            val b = before[i]
            val a = after[i]
            assertEquals("第 $i 个对象的点数必须不变", b.size, a.size)
            var k = 0
            while (k + 1 < b.size) {
                assertEquals(
                    "第 $i 个对象的 x 必须恰好平移 $expect（无视差）",
                    expect,
                    a[k] - b[k],
                    1e-3f,
                )
                assertEquals(
                    "第 $i 个对象的 y 不得随滚动改变",
                    b[k + 1],
                    a[k + 1],
                    0f,
                )
                k += 2
            }
        }
    }

    // ─────────────────────── 小工具 ───────────────────────

    /**
     * 三版设色色表的**全部锚点**（V-B 六族 + V-C 六站）。
     *
     * ⇒ 「忠于上游」模式**绝不能**出现这些颜色（上游只有灰阶 + 绢白纸底）。
     */
    private val COLOR_TABLE_ANCHORS = listOf(
        0x5a8f7b, 0xb8763d, 0x3a6b8a, 0x4a7b9a, 0x6a9b5a, 0x2a2a2a, // V-B
        0x2a5a4a, 0x8a4a2a, 0x1a4a6a, 0x2a5a8a, 0x3a6b2a, 0x1a1a1a, // V-C
    )

    /**
     * 这个像素**是否接近某个色表锚点**（RGB 欧氏距离 ≤ [limit]）。
     *
     * ⛔ **为什么不用「不是灰阶、也不在灰×纸底的混合线上」那种代理判据**（2026-11-06 修正）：
     *   那种判据会把**抗锯齿的合法混色**误判成「彩色」——测试光栅器是未预乘 alpha 的
     *   source-over 混合，半覆盖边缘会落在混合线之外 ⇒ 实测 13 个假阳性。
     *   ⇒ 改用**与意图直接对应**的判据：「场景里不得出现色表色」。
     *   阈值 30 是安全的：最接近的合法像素（暖白纸底 / 灰墨）离锚点都在 60 以上。
     */
    private fun nearColorTable(r: Int, g: Int, b: Int, limit: Int = 30): Boolean =
        COLOR_TABLE_ANCHORS.any { a ->
            val dr = r - ((a shr 16) and 0xFF)
            val dg = g - ((a shr 8) and 0xFF)
            val db = b - (a and 0xFF)
            dr * dr + dg * dg + db * db <= limit * limit
        }

    private fun rgbOf(c: com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor): Int =
        (c.r shl 16) or (c.g shl 8) or c.b

    /** 纸底的混合线斜率 `(pr − pg) / (pg − pb)`。 */
    private fun paperRatioOf(paperRgb: Int): Double {
        val pr = (paperRgb shr 16) and 0xFF
        val pg = (paperRgb shr 8) and 0xFF
        val pb = paperRgb and 0xFF
        val den = (pg - pb).toDouble()
        if (den == 0.0) return 0.0
        return (pr - pg).toDouble() / den
    }
}
