package com.nasmusic.tv.visualizer.qianli

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.VisualizerRendererFactory
import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.CircularRingRenderer
import com.nasmusic.tv.visualizer.renderers.FxFrame
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer.GateMidBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * E50 K6 渲染器级门禁（G-REND-1 ~ G-REND-6）。
 *
 * 方案：`docs/shan-shui-inf-porting-plan.md` §十 10.2（K6 验收）。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 口径 |
 * |---|---|---|
 * | G-REND-1 | 工厂能创建 | `VisualizerRendererFactory.create(QIANLI_SCROLL, ctx)` 的产物是 `QianliScrollRenderer`（⛔ 不是 `CircularRingRenderer` 兜底），且 `theme` 自洽 |
 * | G-REND-2 | 主题身份自洽 | `QianliScrollRenderer().theme == QIANLI_SCROLL`，且该主题出现在 `VisualizerTheme.selectable(...)` 里 |
 * | G-REND-3 | 轮廓推断不退化 | 对 port 包 `ShanShui.mountain(...)` 的真实输出，「山体填充」与「山脊描边」两条轮廓都挑得到（索引 ≠ −1），且是两条不同的轮廓 |
 * | G-REND-4 | 分块生成可复现 | 同一块（同一 `x0`）在同一随机种子下生成两次 ⇒ 位图像素逐点相同，且内容指纹源逐点相同 |
 * | G-REND-5 | 颜色版本可切换 | `V_A`/`V_B`/`V_C` 对同一 `(rowJ, totalRows)` 都返回非 null 且 α ∈ [0,1] 的颜色；`V_A` 与 `V_B`/`V_C` 的颜色不相同 |
 * | G-REND-6 | 三段式 grade 形态 | `postFx` 的暗角是裸字面量 `PostFx(vignette = 0.10f)`（写成常量即红），且 `vignetteOverride` 恒返回 `0f` |
 *
 * ## 负向自证规矩
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，且咬在自己的 tag 上：
 * 负向用例把故意坏值喂给正向用例用的同一份断言 ⇒ 断言必须抛 AssertionError。
 * 绝不许通过放宽阈值让它变绿。
 *
 * ## ⚠️ G-REND-4 的口径说明（本机可验证的那一份）
 *
 * 判据原文是「位图逐像素相同」。⚠️ 本机（Windows）上 Robolectric 4.11.1 只有 LEGACY
 * 图形模式可用：NATIVE 模式所需的 `nativeruntime-dist-compat` 只带
 * `native/linux/` 与 `native/mac/` 两个平台，没有 Windows 的原生库
 * ⇒ Canvas 的绘制不被栅格化 ⇒ 位图恒为全透明 ⇒ 单看像素是空转。
 *
 * ⇒ 本门禁把 G-REND-4 拆成两层同时断言：
 * ① 位图层：两张位图的像素数组逐点相同（口径与判据原文一致）；
 * ② 内容层：两张位图的内容指纹源（该世界每个 chunk 的轮廓点列 / 填色 / 描边 / 笔宽）
 *    逐点相同。
 *
 * ② 是 ① 的更强条件：渲染器的 `drawChunk` 是纯函数（同样的轮廓 + 同样的层带映射
 * ⇒ 同样的像素）⇒ 内容层相同 ⇒ 位图层必然相同；反过来不成立。
 * 而「烘焙期 rnd 消费次序不确定 ⇒ 内容随机跳变」这个风险恰恰落在内容层
 * （几何生成走 `ShanShuiPrng.global`，像素只是它的下游）⇒ ② 才是这条判据真正的抓手。
 *
 * ## 门禁缝
 *
 * G-REND-3 / G-REND-4 / G-REND-6 需要观测渲染器内部状态 ⇒ 渲染器里开了只读的门禁缝
 * （`bodyFillIndexForGate` / `ridgeIndexForGate` / `vignetteOverrideForGate` /
 * `gateBakeMidBlockForGate`），⛔ 不改变任何渲染行为。
 *
 * ## 为什么需要 Robolectric
 *
 * `QianliScrollRenderer` 的构造会创建 `android.graphics.Paint` / `Path`，
 * 工厂分支里还有 `Context` ⇒ 纯 JVM 下是桩类。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliScrollRendererTest {

    // ── 夹具 ──────────────────────────────────────────────────

    /** 判据用的固定随机种子（port 包的几何生成全走 `ShanShuiPrng.global`）。 */
    private val prngSeed = 1234.0

    /** 块宽（世界像素）：门禁用一块够宽的块，能装下一整座山体。 */
    private val blockW = 512

    /** 块高（= 屏高）：生产里块高就是屏高（`bakeBlock(..., blockW, h)`）。 */
    private val blockH = 1080

    /** 跑一段断言，断言它必须抛 AssertionError（负向自证的标准动作）。 */
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
     * 预热 perlin 表。
     *
     * ⚠️ [ShanShuiNoise] 的 perlin 表是懒初始化的 ⇒ 第一次 `noise()` 会消费
     * 4096 个 rnd。⛔ 必须发生在测量区之外：否则第一次烘焙比第二次多消费一轮 rnd
     * ⇒ 两次结果必然不同 ⇒ 判据变成「永远红」。
     */
    private fun warmUpNoiseTable() {
        ShanShuiNoise.noise(0.0)
    }

    /** 把全局 PRNG 复位到固定种子（门禁的可复现性前提）。 */
    private fun resetPrng(seed: Double = prngSeed) {
        ShanShuiPrng.global.s = seed
    }

    // ══════════════════════ G-REND-1 工厂能创建 ══════════════════════

    /**
     * G-REND-1：工厂必须造出 [QianliScrollRenderer]（⛔ 不是 `CircularRingRenderer` 兜底）。
     *
     * ⚠️ 这一条防的是「E50 的源码在，但工厂里没注册 / 被兜底分支吃掉」——
     * 那样画面上永远是默认的圆环，而所有单测都绿。
     */
    @Test
    fun gRend1_factoryCreatesTheQianliScrollRenderer() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val r = VisualizerRendererFactory.create(VisualizerTheme.QIANLI_SCROLL, ctx)
        assertTrue(
            "工厂必须造出 QianliScrollRenderer（实测 ${r.javaClass.name}）",
            r is QianliScrollRenderer,
        )
        assertEquals("主题必须自洽", VisualizerTheme.QIANLI_SCROLL, r.theme)
        assertFalse("⛔ 不得落到 CircularRingRenderer 兜底", r is CircularRingRenderer)
    }

    /**
     * G-REND-1 负向自证：把「别的渲染器」喂给正向用例用的同一份断言 ⇒ 必须判红。
     *
     * ⚠️ 这里断言的是反向命题（兜底渲染器不是 QianliScrollRenderer）——
     * 正向断言一旦被放宽成「随便什么都行」，这条负向用例就会红 ⇒ 它是判据的护栏。
     */
    @Test
    fun gRend1_negative_fallbackRendererIsRejectedByTheGate() {
        // ⚠️ 声明成 Any：直接写 `CircularRingRenderer() is QianliScrollRenderer`
        //    会被编译器判成「恒 false」（警告 KTLC-365）⇒ 这里显式走 Any。
        val fallback: Any = CircularRingRenderer()
        gateMustCatch("兜底渲染器必须被判据咬住（否则正向判据被放宽了）") {
            assertTrue(
                "工厂必须造出 QianliScrollRenderer",
                fallback is QianliScrollRenderer,
            )
        }
    }

    // ══════════════════════ G-REND-2 主题身份自洽 ══════════════════════

    /**
     * G-REND-2：渲染器自报的主题 = `QIANLI_SCROLL`，且该主题在可选列表里。
     *
     * ⚠️ 「在 selectable 里」这一半很关键：主题若被收口成「不可选」，
     * 工厂照样能造出渲染器，但用户在指示器上永远切不到它 ⇒ 等于没上线。
     */
    @Test
    fun gRend2_themeIdentityIsSelfConsistentAndSelectable() {
        val r = QianliScrollRenderer()
        assertEquals(
            "渲染器自报的主题必须是 QIANLI_SCROLL",
            VisualizerTheme.QIANLI_SCROLL,
            r.theme,
        )
        assertTrue(
            "QIANLI_SCROLL 必须在 selectable(true) 里",
            VisualizerTheme.selectable(true).contains(VisualizerTheme.QIANLI_SCROLL),
        )
        assertTrue(
            "QIANLI_SCROLL 必须在 selectable(false) 里（相片墙开不开都不影响它）",
            VisualizerTheme.selectable(false).contains(VisualizerTheme.QIANLI_SCROLL),
        )
    }

    /** G-REND-2 负向自证：主题不符 ⇒ 同一份断言必须判红。 */
    @Test
    fun gRend2_negative_wrongThemeIsRejectedByTheGate() {
        gateMustCatch("主题不符必须被判据咬住") {
            assertEquals(
                "渲染器自报的主题必须是 QIANLI_SCROLL",
                VisualizerTheme.QIANLI_SCROLL,
                VisualizerTheme.CIRCULAR_RING,
            )
        }
        gateMustCatch("不可选的主题必须被判据咬住") {
            // ⚠️ 负样本用 PHOTO_WALL：相片墙三来源全关时它**不在** selectable(false) 里
            //    （CIRCULAR_RING 是可选的，拿它当负样本会永远绿 ⇒ 这条自证就空转了）。
            assertTrue(
                "QIANLI_SCROLL 必须在 selectable(false) 里",
                VisualizerTheme.selectable(false).contains(VisualizerTheme.PHOTO_WALL),
            )
        }
    }

    // ══════════════════════ G-REND-3 轮廓推断不退化 ══════════════════════

    /**
     * G-REND-3：对 port 包 `ShanShui.mountain(...)` 的真实输出，
     * 「山体填充」与「山脊描边」两条轮廓都挑得到。
     *
     * ⚠️ 这是本门禁的重点：渲染器用启发式挑这两条轮廓
     * （横向跨度最大的白填充 = 山体底；横向跨度最大的描边 = 山脊）。
     * 一旦挑不到：
     * · 山脊挑不到 ⇒ L2′ 轮廓光整层为空；
     * · 山体底挑不到 ⇒ 中景山体退化成绢白（而不是青绿）。
     *
     * ⚠️ 「两条轮廓必须是不同的」：山体底那条的 `stroke` 是 null（不描边），
     * 山脊那条的 `fill` 是灰（不是白）⇒ 二者天然互斥 ⇒ 断言是恒真的结构约束。
     */
    @Test
    fun gRend3_bothContourHeuristicsPickSomethingOnARealMountain() {
        val r = QianliScrollRenderer()
        val contours = withFixedSeed {
            ShanShui.mountain(0.0, 300.0, 12.0, 300.0, 500.0, 200.0, true, null)
        }
        assertTrue("真实山体的轮廓列表不能为空", contours.isNotEmpty())

        val bodyIdx = r.bodyFillIndexForGate(contours)
        val ridgeIdx = r.ridgeIndexForGate(contours)

        // 记录实测基线（门禁报告要引用的那两个下标；⛔ 不把它写成断言，避免上游几何一变就红）。
        println("[E50 G-REND-3] 轮廓总数=${contours.size} 山体填充索引=$bodyIdx 山脊描边索引=$ridgeIdx")

        assertTrue("山体填充轮廓必须挑得到（实测索引 $bodyIdx）", bodyIdx >= 0)
        assertTrue("山脊描边轮廓必须挑得到（实测索引 $ridgeIdx）", ridgeIdx >= 0)
        assertTrue(
            "山体填充与山脊描边必须是两条不同的轮廓（实测 $bodyIdx / $ridgeIdx）",
            bodyIdx != ridgeIdx,
        )
        // 被挑中的两条轮廓必须各司其职：山体底只填不描、山脊只描不填白。
        assertTrue("山体填充那条必须有填色", contours[bodyIdx].fill != null)
        assertTrue("山脊描边那条必须有描边色", contours[ridgeIdx].stroke != null)
    }

    /**
     * G-REND-3 负向自证：空轮廓列表 ⇒ 两个启发式都必须返回 −1（「挑不到」可被识别）。
     *
     * ⛔ 同时把 −1 喂给正向用例用的同一份断言 ⇒ 断言必须判红。
     */
    @Test
    fun gRend3_negative_emptyContourListIsDetectedAsNotFound() {
        val r = QianliScrollRenderer()
        val empty = emptyList<ShanShuiContour>()

        assertEquals("空轮廓列表 ⇒ 山体填充必须挑不到（−1）", -1, r.bodyFillIndexForGate(empty))
        assertEquals("空轮廓列表 ⇒ 山脊描边必须挑不到（−1）", -1, r.ridgeIndexForGate(empty))

        gateMustCatch("挑不到（−1）必须被正向判据咬住") {
            assertTrue("山体填充轮廓必须挑得到", r.bodyFillIndexForGate(empty) >= 0)
        }
        gateMustCatch("挑不到（−1）必须被正向判据咬住") {
            assertTrue("山脊描边轮廓必须挑得到", r.ridgeIndexForGate(empty) >= 0)
        }
    }

    // ══════════════════════ G-REND-4 分块生成可复现 ══════════════════════

    /**
     * G-REND-4：同一块（同一 `x0`）在同一随机种子下生成两次 ⇒ 完全相同。
     *
     * ⚠️ 这条防的是「烘焙期 rnd 消费次序不确定 ⇒ 同一块内容随机跳变」
     * （画面上表现为山体在滚动时突然换形、闪烁）。
     */
    @Test
    fun gRend4_sameBlockBakedTwiceIsIdentical() {
        val r = QianliScrollRenderer()
        warmUpNoiseTable()

        val saved = ShanShuiPrng.global.s
        val a: GateMidBlock
        val b: GateMidBlock
        try {
            resetPrng()
            a = r.gateBakeMidBlockForGate(0.0, blockW, blockH)
            resetPrng()
            b = r.gateBakeMidBlockForGate(0.0, blockW, blockH)
        } finally {
            ShanShuiPrng.global.s = saved
        }

        assertTrue(
            "块里必须有内容（⛔ 不许空块，否则这条判据空转）",
            a.contours.sumOf { it.size } > 0,
        )
        println(
            "[E50 G-REND-4] chunk 数=${a.contours.size} " +
                "轮廓总数=${a.contours.sumOf { it.size }} " +
                "点总数=${a.contours.sumOf { s -> s.sumOf { it.pts.size } }} " +
                "块尺寸=${a.bitmap.width}x${a.bitmap.height}",
        )
        assertBlocksIdentical("同一 x0、同一种子的两次烘焙", a, b)

        a.bitmap.recycle()
        b.bitmap.recycle()
    }

    /**
     * G-REND-4 负向自证：换种子 ⇒ 两次烘焙必然不同 ⇒ 同一份断言必须判红。
     *
     * ⚠️ 这条负向用例同时证明了正向用例不是空转：在 LEGACY 图形模式下位图层恒等
     * （两边都全透明），是内容层把这条差异咬住的 ⇒ 内容层确实是有效抓手。
     */
    @Test
    fun gRend4_negative_differentSeedBreaksTheReproducibilityGate() {
        val r = QianliScrollRenderer()
        warmUpNoiseTable()

        val saved = ShanShuiPrng.global.s
        val a: GateMidBlock
        val b: GateMidBlock
        try {
            resetPrng(prngSeed)
            a = r.gateBakeMidBlockForGate(0.0, blockW, blockH)
            resetPrng(prngSeed + 1.0)
            b = r.gateBakeMidBlockForGate(0.0, blockW, blockH)
        } finally {
            ShanShuiPrng.global.s = saved
        }

        gateMustCatch("换种子后两次烘焙必然不同 ⇒ 同一份断言必须判红") {
            assertBlocksIdentical("负样本", a, b)
        }

        a.bitmap.recycle()
        b.bitmap.recycle()
    }

    /**
     * G-REND-4 的断言本体（正向与负向共用同一份）。
     *
     * ① 位图层：像素数组逐点相同；
     * ② 内容层：轮廓指纹源逐点相同（⛔ 这一层才是「rnd 消费次序」风险的抓手）。
     */
    private fun assertBlocksIdentical(what: String, a: GateMidBlock, b: GateMidBlock) {
        assertArrayEquals(
            "$what：位图像素必须逐点相同",
            pixelsOf(a.bitmap),
            pixelsOf(b.bitmap),
        )
        assertSameContours("$what：内容指纹源", a.contours, b.contours)
    }

    /** 位图的像素数组（0xAARRGGBB）。 */
    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px
    }

    /** 两组轮廓指纹源必须逐点相同（点坐标按位模式比 ⇒ 逐位相同）。 */
    private fun assertSameContours(
        what: String,
        a: List<List<ShanShuiContour>>,
        b: List<List<ShanShuiContour>>,
    ) {
        assertEquals("$what：chunk 数必须相同", a.size, b.size)
        for (i in a.indices) {
            val ca = a[i]
            val cb = b[i]
            assertEquals("$what：第 $i 个 chunk 的轮廓数必须相同", ca.size, cb.size)
            for (j in ca.indices) {
                assertSameContour("$what：第 $i 个 chunk 的第 $j 条轮廓", ca[j], cb[j])
            }
        }
    }

    /** 一条轮廓必须逐字段相同（⛔ 点坐标是 `FloatArray` ⇒ `==` 比的是引用，必须逐位比）。 */
    private fun assertSameContour(what: String, a: ShanShuiContour, b: ShanShuiContour) {
        assertEquals("$what：填色必须相同", a.fill, b.fill)
        assertEquals("$what：描边色必须相同", a.stroke, b.stroke)
        assertEquals("$what：笔宽必须相同", a.width, b.width, 0.0)
        assertEquals("$what：点数必须相同", a.pts.size, b.pts.size)
        for (k in a.pts.indices) {
            assertEquals(
                "$what：第 $k 个点的 x 必须逐位相同",
                a.pts[k][0].toRawBits(),
                b.pts[k][0].toRawBits(),
            )
            assertEquals(
                "$what：第 $k 个点的 y 必须逐位相同",
                a.pts[k][1].toRawBits(),
                b.pts[k][1].toRawBits(),
            )
        }
    }

    /** 在固定随机种子下做一件事（结束后恢复种子）。 */
    private fun <T> withFixedSeed(block: () -> T): T {
        val saved = ShanShuiPrng.global.s
        try {
            resetPrng()
            return block()
        } finally {
            ShanShuiPrng.global.s = saved
        }
    }

    // ══════════════════════ G-REND-5 颜色版本可切换 ══════════════════════

    /**
     * G-REND-5：三个染色版本对同一 `(rowJ, totalRows)` 都返回可用的颜色，
     * 且 `V_A` 与 `V_B`/`V_C` 不相同。
     *
     * ⚠️ 「不相同」这一半防的是「染色层空转」：三版全返回同一个颜色 ⇒
     * 切版本没有任何画面差别 ⇒ 等于没做这个功能。
     */
    @Test
    fun gRend5_allColorVersionsAreUsableAndMutuallyDistinct() {
        val totalRows = 10
        for (rowJ in 0 until totalRows) {
            for (v in QianliColorTable.Version.entries) {
                val c: ShanShuiColor? = QianliColorTable.colorFor(v, rowJ, totalRows)
                assertNotNull("$v 的第 $rowJ 行必须有颜色", c)
                val color = c!!
                assertTrue("$v 的第 $rowJ 行 α 必须在 [0,1]（实测 ${color.a}）", color.a in 0.0..1.0)
                assertTrue("$v 的第 $rowJ 行 r 必须在 [0,255]", color.r in 0..255)
                assertTrue("$v 的第 $rowJ 行 g 必须在 [0,255]", color.g in 0..255)
                assertTrue("$v 的第 $rowJ 行 b 必须在 [0,255]", color.b in 0..255)
            }
            val vA = QianliColorTable.colorFor(QianliColorTable.Version.V_A, rowJ, totalRows)
            val vB = QianliColorTable.colorFor(QianliColorTable.Version.V_B, rowJ, totalRows)
            val vC = QianliColorTable.colorFor(QianliColorTable.Version.V_C, rowJ, totalRows)
            assertTrue(
                "第 $rowJ 行：V_A 与 V_B 必须不同（否则染色层空转），实测 $vA / $vB",
                vA != vB,
            )
            assertTrue(
                "第 $rowJ 行：V_A 与 V_C 必须不同（否则染色层空转），实测 $vA / $vC",
                vA != vC,
            )
        }
    }

    /** G-REND-5 负向自证：两个版本返回同色 / α 越界 ⇒ 同一份断言必须判红。 */
    @Test
    fun gRend5_negative_identicalVersionsAndBadAlphaAreRejected() {
        gateMustCatch("两版同色必须被判据咬住") {
            assertTrue(
                "V_A 与 V_B 必须不同",
                QianliColorTable.colorFor(QianliColorTable.Version.V_A, 3, 10) !=
                    QianliColorTable.colorFor(QianliColorTable.Version.V_A, 3, 10),
            )
        }
        gateMustCatch("α 越界必须被判据咬住") {
            val bad = ShanShuiColor(10, 20, 30, 1.5)
            assertTrue("α 必须在 [0,1]", bad.a in 0.0..1.0)
        }
    }

    // ══════════════════════ G-REND-6 三段式 grade 形态 ══════════════════════

    /**
     * G-REND-6：`postFx` 的暗角必须是裸字面量 `PostFx(vignette = 0.10f)`，
     * 且 `vignetteOverride` 恒返回 `0f`。
     *
     * ⚠️ 为什么必须是裸字面量：这个数是 `RendererFx.needsDamageCoalescer` 的输入
     * （脏区合并的账）⇒ 一旦被写成具名常量（哪怕值一样），门禁就再也认不出
     * 「本效果走了基类后处理」⇒ 这条判据会随重构悄悄失效。
     *
     * ⚠️ 为什么 `vignetteOverride` 必须是 `0f`：暗角与颗粒已烘在同一张 grade 位图里
     * ⇒ 基类那一次全屏暗角必须关掉（否则四角压到死黑 + 两层噪点互咬）。
     */
    @Test
    fun gRend6_postFxKeepsTheBareVignetteLiteralAndTheOverrideIsOff() {
        val src = strippedRenderer()
        assertTrue(
            "postFx 必须是裸字面量 $POSTFX_MARKER",
            src.contains(POSTFX_MARKER),
        )

        val r = QianliScrollRenderer()
        assertEquals("postFx.vignette 必须是 0.10f", 0.10f, r.postFx.vignette, 0f)
        assertEquals(
            "vignetteOverride 必须恒为 0f（暗角由烘焙位图承担）",
            0f,
            r.vignetteOverrideForGate(FxFrame()),
            0f,
        )
    }

    /**
     * G-REND-6 负向自证：把裸字面量换成具名常量的源码变体 ⇒ 同一份判据必须判红。
     */
    @Test
    fun gRend6_negative_constantizedVignetteBreaksTheLiteralGate() {
        val src = strippedRenderer()
        val broken = src.replace(POSTFX_MARKER, "override val postFx = PostFx(vignette = GRADE_VIGNETTE_A)")
        assertTrue("负样本必须真的被改过（否则这条自证空转）", broken != src)
        assertFalse(
            "负样本：暗角写成具名常量 ⇒ 同一份判据必须判红",
            broken.contains(POSTFX_MARKER),
        )
    }

    private companion object {
        /** `postFx` 的裸字面量写法（源码里必须逐字存在）。 */
        const val POSTFX_MARKER = "override val postFx = PostFx(vignette = 0.10f)"
    }

    // ── 源码扫描（照抄 QianliScrollGateTest / FxCoverageScanTest 的做法） ────────────────

    /** 渲染器源码根目录。 */
    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    /** 剥掉注释后的渲染器源码。 */
    private fun strippedRenderer(): String = stripComments(
        File(mainSourceRoot(), "renderers/QianliScrollRenderer.kt").readText(),
    )

    /**
     * 剥掉块注释（Kotlin 块注释可嵌套）与行注释。⚠️ 必须保持行数与原文件一致。
     */
    private fun stripComments(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '*') {
                        depth++
                        i += 2
                        continue
                    }
                    if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') {
                        depth--
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                while (i < s.length && s[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
