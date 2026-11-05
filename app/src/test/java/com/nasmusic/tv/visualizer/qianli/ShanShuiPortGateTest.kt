package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiArch
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiBrush
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPath
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPoly
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * E50 K2–K5 移植层门禁（G-PORT-1 ~ G-PORT-8）—— **纯 JVM**（port 包零 Android 依赖）。
 *
 * 移植来源：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。
 * 方案：`docs/shan-shui-inf-porting-plan.md` §十 10.1。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 阈值 |
 * |---|---|---|
 * | G-PORT-1 | Prng 黄金向量（seed=1234） | sAfterSeed = 334869857393；前 4 输出逐位相等（容差 0） |
 * | G-PORT-2 | Noise 自相关 | 落在 [0,1]；两次同种子调用逐位相等 |
 * | G-PORT-3 | ribbon 对称性 | 水平线时上下缘关于该线对称（容差 1e-3） |
 * | G-PORT-4 | cun 的返回语义 | 非空；每个子点列点数 > 2 |
 * | G-PORT-5 | mountain 点阵 | 无 NaN；10 行 × 50 列 |
 * | G-PORT-6 | planner | chadd 最小间距 ≥ 10；chunks 按 y 升序 |
 * | G-PORT-7 | 负向自证 | Long 累加 / 漏预热 / Horner 三变体各自必红 |
 * | G-PORT-8 | 结构性保真 | 无 NaN；坐标 \|x\|,\|y\| < 1e5；点数 > 2；同种子两次逐位相等 |
 * | G-PORT-9 | **PRNG 抽取次数与上游一致** | tree01 = 63 次 / 11 条；tree03 = 504 次 / 73 条；皴线颜色逐条随机 |
 * | G-PORT-10 | **远山三角剖分与上游一致** | 轮廓 504 条 = 10 块填色 + 494 三角形；逐块条数 [37,44,44,44,44,52,60,84,60,25]；坐标摘要 0xea8cbf2d27b1bcf6 逐位相等；退化楔形恰好 1 个（block 0 / 第 25 个）；颜色 = getCol(中点)；PRNG 抽取 = 0 |
 *
 * ## 负向自证规矩
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，且**咬在自己的 tag 上**：
 * 负向用例把**故意坏值**喂给**正向用例用的同一份断言** ⇒ 断言必须抛 AssertionError。
 * 绝不许通过放宽阈值让它变绿。
 *
 * ## 关于「与上游逐位一致」
 *
 * 本仓**没有**可直接跑上游 JS 的 harness（上游 index.html 是浏览器 HTML，且在本仓之外）。
 * 按方案 §三.3-② 的论断：**PRNG 黄金向量逐位相等（G-PORT-1）+ 调用点次序逐行照抄**
 * 就是与上游对账的机制（上游把 Math.random 全局覆写成 Prng.next ⇒ 随机流完全由 Prng 决定）。
 * ⇒ G-PORT-8 实现为「结构断言 + 同种子可复现性断言」，
 * **不伪造**「已与上游逐位对账」的断言。
 *
 * ## 全局状态的钉扎
 *
 * port 包的两个全局单例（[ShanShuiPrng.global] 的随机流、[ShanShuiNoise] 的 perlin 表）
 * 会被各函数按调用次序消耗 ⇒ 任何「同种子两次调用」的判据都必须先把它们**复位**，
 * 否则第二次调用拿到的是不同的流。复位方式见 [resetPortGlobals]。
 */
class ShanShuiPortGateTest {

    // ── 夹具常量 ──────────────────────────────────────────────

    /**
     * 黄金向量的种子。
     *
     * ⚠️ **数值型 Int**（⛔ 不是字符串）：上游 `Prng.seed(1234)` 走 `JSON.stringify(1234)`
     * ⇒ 字符串 `"1234"` ⇒ Base64 `"MTIzNA=="`。
     * ⛔ 若传 `1234.0`（Double），`JSON.stringify` 会得到 `"1234.0"` ⇒ Base64 不同 ⇒ 整条流错。
     */
    private val goldenSeed = 1234

    /** seed 之后（= 末尾 10 次预热之后）的 s。 */
    private val goldenSAfterSeed = 334869857393.0

    /** seed 之后的前 4 个输出。 */
    private val goldenOutputs = listOf(
        0.5179214018993742,
        0.19101042199684515,
        0.1481747547047804,
        0.8586372206878526,
    )

    /**
     * 结构判据用的种子（Int）。
     *
     * ⚠️ 取 157 的理由：皴线（cun）每条的长度 = 2·hlen，hlen = floor(rnd·reso1·0.2)
     * ⇒ 随机流不同 ⇒ 长度不同。取 157 时 20 条皴线的长度都 ≥ 4（实测 [16,10,4,18,…]），
     * 满足 G-PORT-4 的「点数 > 2」。⛔ 换种子前必须重新核这条。
     */
    private val portSeed = 157

    /** perlin 表的钉扎种子（Double）—— 只影响噪声表内容，不消耗 PRNG。 */
    private val noiseSeedValue = 20250101.0

    /** 坐标范围上限（世界单位）。 */
    private val coordLimit = 1e5

    // ── 公共夹具动作 ──────────────────────────────────────────

    /**
     * 复位 port 包的全局状态。
     *
     * ① [ShanShuiPrng.global] 的随机流：重新 seed ⇒ 后续调用拿到同一条流。
     * ② [ShanShuiNoise] 的 perlin 表：用 [ShanShuiNoise.noiseSeed] 钉死
     *    （它用固定 LCG 填表，**不消耗 PRNG** ⇒ 表的内容与 PRNG 流解耦 ⇒ 可复现）。
     *
     * ⚠️ 为什么不靠「首次 noise() 调用自动初始化表」：那会吃掉 4096 个 PRNG 值，
     *    且**只发生在第一次** ⇒ 第二次调用就拿到不同的流 ⇒ 复现性判据必然假红。
     */
    private fun resetPortGlobals() {
        ShanShuiPrng.global.seed(portSeed)
        ShanShuiNoise.noiseSeed(noiseSeedValue)
    }

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

    /** 同一份断言，跑两遍 ⇒ 返回 (第一次, 第二次)。 */
    private fun <T> twicePinned(call: () -> T): Pair<T, T> {
        resetPortGlobals()
        val a = call()
        resetPortGlobals()
        val b = call()
        return a to b
    }

    // ══════════════════════ G-PORT-1 Prng 黄金向量 ══════════════════════

    /**
     * G-PORT-1：seed=1234 的 sAfterSeed 与前 4 输出**逐位相等**（容差 0）。
     *
     * 口径：`sAfterSeed` = `seed()` 跑完（末尾 10 次预热之后）的 `prng.s`。
     */
    @Test
    fun gPort1_prngGoldenVector() {
        val prng = ShanShuiPrng()
        prng.seed(goldenSeed)
        assertGoldenVector(prng.s, List(4) { prng.next() })
    }

    /** G-PORT-1 负向自证：换一个种子 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort1_negative_otherSeedBreaksGoldenVector() {
        val prng = ShanShuiPrng()
        prng.seed(goldenSeed + 1)
        gateMustCatch("换种子后黄金向量必须判红") {
            assertGoldenVector(prng.s, List(4) { prng.next() })
        }
    }

    /**
     * G-PORT-1（**Double 种子**变体）：上游 `Prng.seed(31337.0)` 走 `JSON.stringify(31337.0)`，
     * 而 JS 的数值**没有整数/浮点之分** ⇒ 字符串是 `"31337"`（**不是** `"31337.0"`）。
     *
     * 口径来源：node 实跑上游 `shan-shui-inf/index.html` 的原始 JS：
     * `sAfterSeed = 750549835631`，
     * 前 4 输出 `0.19014934171710193 / 0.013262523316151293 / 0.782311991800403 / 0.20656032925176954`。
     *
     * ⚠️ 这条就是 **D16** 的守门：若 [ShanShuiPrng] 的 `jsonStringify` 退回 `Double.toString()`
     * （得到 `"31337.0"`）⇒ Base64 不同 ⇒ 整条流错位 ⇒ 必红。
     */
    @Test
    fun gPort1_prngGoldenVectorWithDoubleSeed() {
        val prng = ShanShuiPrng()
        prng.seed(31337.0)
        assertEquals(
            "Double 种子的 seed 之后 s 必须与上游逐位相等",
            750549835631.0,
            prng.s,
            0.0,
        )
        assertEquals(
            "Double 种子的前 4 个输出必须与上游逐位相等",
            listOf(
                0.19014934171710193,
                0.013262523316151293,
                0.782311991800403,
                0.20656032925176954,
            ),
            List(4) { prng.next() },
        )
    }

    /** 黄金向量断言（G-PORT-1 的唯一判据口径，正向与负向共用）。 */
    private fun assertGoldenVector(sAfterSeed: Double, outs: List<Double>) {
        assertEquals(
            "seed 之后的 s（末尾 10 次预热之后）必须逐位相等",
            goldenSAfterSeed,
            sAfterSeed,
            0.0,
        )
        assertEquals("前 4 个输出必须逐位相等", goldenOutputs, outs)
    }

    // ══════════════════════ G-PORT-2 Noise 自相关 ══════════════════════

    /**
     * G-PORT-2：固定种子后 noise(0..1) 落在 [0,1]；两次同种子调用逐位相等。
     *
     * 采样点：`x = 0.00, 0.01, …, 1.00`（101 点）。
     */
    @Test
    fun gPort2_noiseRangeAndReproducibility() {
        val first = noiseSamples()
        val second = noiseSamples()
        assertNoiseInRange(first)
        assertSameSamples(first, second)
    }

    /** G-PORT-2 负向自证：越界值 / 不相等的两组值 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort2_negative_badNoiseBreaksGate() {
        gateMustCatch("越界噪声必须被判据咬住") {
            assertNoiseInRange(listOf(0.5, 1.5))
        }
        gateMustCatch("两次结果不同必须被判据咬住") {
            assertSameSamples(listOf(0.25), listOf(0.75))
        }
    }

    private fun noiseSamples(): List<Double> {
        ShanShuiNoise.noiseSeed(noiseSeedValue)
        return (0..100).map { ShanShuiNoise.noise(it * 0.01) }
    }

    private fun assertNoiseInRange(samples: List<Double>) {
        for (v in samples) {
            assertTrue("noise 必须落在 [0,1]（实测 $v）", v >= 0.0 && v <= 1.0)
        }
    }

    private fun assertSameSamples(a: List<Double>, b: List<Double>) {
        assertEquals("两次同种子调用必须逐位相等", a, b)
    }

    // ══════════════════════ G-PORT-3 ribbon 对称性 ══════════════════════

    /**
     * G-PORT-3：pts 为水平线时，ribbon 的上下缘关于该线对称（容差 1e-3）。
     *
     * ribbon 的点列布局（上游 stroke 的同构）：
     * `[pts0] + 上缘(n-2) + [pts末] + 下缘倒序(n-2) + [pts0]`，共 `2n-1` 点。
     * 上/下缘同下标配对 ⇒ 两点的 y 和 = 2·y0、x 相同。
     */
    @Test
    fun gPort3_ribbonSymmetricAboutHorizontalLine() {
        val y0 = 100f
        val pts = (0 until 9).map { floatArrayOf(it * 10f, y0) }
        resetPortGlobals()
        val ribbon = ShanShuiBrush.ribbon(
            pts, 6.0, { x -> Math.sin(x * Math.PI) }, 0.5, ShanShuiColor.default,
        )
        assertRibbonSymmetric(ribbon, pts.size, y0)
    }

    /** G-PORT-3 负向自证：斜线（不关于该水平线对称）⇒ 同一份断言必须判红。 */
    @Test
    fun gPort3_negative_slantedLineBreaksSymmetryGate() {
        val pts = (0 until 9).map { floatArrayOf(it * 10f, it * 6f) }
        resetPortGlobals()
        val ribbon = ShanShuiBrush.ribbon(
            pts, 6.0, { x -> Math.sin(x * Math.PI) }, 0.5, ShanShuiColor.default,
        )
        gateMustCatch("斜线的 ribbon 不得被判为对称") {
            assertRibbonSymmetric(ribbon, pts.size, 0f)
        }
    }

    private fun assertRibbonSymmetric(
        ribbon: List<ShanShuiPath.Point>,
        n: Int,
        y0: Float,
    ) {
        assertEquals("ribbon 点数必须为 2n-1", 2 * n - 1, ribbon.size)
        val upper = ribbon.subList(1, n - 1)
        val lower = ribbon.subList(n, 2 * n - 2).reversed()
        assertEquals("上下缘点数必须一致", upper.size, lower.size)
        for (i in upper.indices) {
            val u = upper[i]
            val l = lower[i]
            assertTrue(
                "第 $i 点上下缘必须关于该线对称（实测 y ${u[1]} / ${l[1]}，基准 $y0）",
                Math.abs((u[1] + l[1]) - 2.0 * y0) <= 1e-3,
            )
            assertTrue(
                "第 $i 点上下缘的 x 必须相同（实测 ${u[0]} / ${l[0]}）",
                Math.abs(u[0] - l[0]) <= 1e-3,
            )
        }
    }

    // ══════════════════════ G-PORT-4 cun 的返回语义 ══════════════════════

    /**
     * G-PORT-4：cun 返回 List<List<Point>>：非空，且每个子点列点数 > 2。
     */
    @Test
    fun gPort4_cunReturnsNestedPointLists() {
        val ptlist = mountainPtlist()
        resetPortGlobals()
        val texlist = ShanShuiBrush.cun(ptlist, 20.0, 0.0)
        assertCunShape(texlist)
    }

    /** G-PORT-4 负向自证：退化输入（1 列）⇒ 每条皴线长度为 0 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort4_negative_degenerateInputBreaksCunGate() {
        val thin = List(10) { listOf(floatArrayOf(0f, 0f)) }
        resetPortGlobals()
        val texlist = ShanShuiBrush.cun(thin, 20.0, 0.0)
        gateMustCatch("退化输入的 cun 必须被判据咬住") {
            assertCunShape(texlist)
        }
    }

    private fun assertCunShape(texlist: List<List<ShanShuiPath.Point>>) {
        assertTrue("cun 必须返回非空的点列列表", texlist.isNotEmpty())
        for (i in texlist.indices) {
            assertTrue(
                "第 $i 条皴线点数必须 > 2（实测 ${texlist[i].size}）",
                texlist[i].size > 2,
            )
        }
    }

    // ══════════════════════ G-PORT-5 mountain 点阵 ══════════════════════

    /** G-PORT-5：mountain 点阵无 NaN，且是 10 行 × 50 列。 */
    @Test
    fun gPort5_mountainGridShape() {
        resetPortGlobals()
        val grid = ShanShui.mountainGrid(0.0, 0.0, 7.0, 300.0, 500.0)
        assertMountainGrid(grid)
    }

    /** G-PORT-5 负向自证：行数不对 / 含 NaN 的点阵 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort5_negative_brokenGridBreaksGate() {
        val nineRows = List(9) { List(50) { floatArrayOf(1f, 2f) } }
        gateMustCatch("9 行的点阵必须被判据咬住") { assertMountainGrid(nineRows) }
        val withNan = List(10) { List(50) { floatArrayOf(1f, 2f) } }.toMutableList()
        withNan[3] = withNan[3].toMutableList().also { it[7] = floatArrayOf(Float.NaN, 0f) }
        gateMustCatch("含 NaN 的点阵必须被判据咬住") { assertMountainGrid(withNan) }
    }

    private fun assertMountainGrid(grid: List<List<ShanShuiPath.Point>>) {
        assertEquals("山体点阵必须 10 行", 10, grid.size)
        for (i in grid.indices) {
            assertEquals("第 $i 行必须 50 列", 50, grid[i].size)
            for (p in grid[i]) {
                assertTrue("点不得为 NaN", !p[0].isNaN() && !p[1].isNaN())
            }
        }
    }

    // ══════════════════════ G-PORT-6 planner ══════════════════════

    /**
     * G-PORT-6：`chadd` 的最小间距 ≥ 10；`load` 产出的 chunks 按 y 升序（远→近画家序）。
     */
    @Test
    fun gPort6_plannerMinSpacingAndSortedChunks() {
        resetPortGlobals()
        val planned = ShanShuiPlanner.plan(0.0, 512.0)
        assertMinSpacing(planned, 10.0)

        resetPortGlobals()
        val state = ShanShuiPlanner.State()
        ShanShuiPlanner.load(state, 0.0, 0.0)
        assertChunksSortedByY(state.chunks)
    }

    /** G-PORT-6 负向自证：间距不足 / 顺序颠倒 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort6_negative_badChunksBreakGate() {
        val tooClose = listOf(
            ShanShuiPlanner.Chunk("mount", 0.0, 10.0, 1.0),
            ShanShuiPlanner.Chunk("mount", 5.0, 20.0, 1.0),
        )
        gateMustCatch("间距 5 的两个 chunk 必须被判据咬住") { assertMinSpacing(tooClose, 10.0) }
        val unsorted = listOf(
            ShanShuiPlanner.Chunk("mount", 0.0, 30.0, 1.0),
            ShanShuiPlanner.Chunk("mount", 100.0, 10.0, 1.0),
        )
        gateMustCatch("y 倒序的 chunk 列表必须被判据咬住") { assertChunksSortedByY(unsorted) }
    }

    private fun assertMinSpacing(chunks: List<ShanShuiPlanner.Chunk>, minGap: Double) {
        for (i in chunks.indices) {
            for (j in i + 1 until chunks.size) {
                val gap = Math.abs(chunks[i].x - chunks[j].x)
                assertTrue(
                    "chunk $i 与 $j 的 x 间距 $gap 必须 ≥ $minGap",
                    gap >= minGap,
                )
            }
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

    // ══════════════════════ G-PORT-7 负向自证（三变体） ══════════════════════

    /**
     * G-PORT-7 变体①：hash 的累加与取模改用 BigInteger（精确整数）。
     *
     * ⚠️ 为什么它必红：Double 路径的 hash 值是「精确整数和」四舍五入后的 double
     * （本输入下精确值 34610482573896269 → double 34610482573896268），
     * 随后 `(h + z) % m` 的余数就差 1（797779540141 vs 797779540142）⇒ 整条随机流错位。
     */
    @Test
    fun gPort7_negative_longAccumulationBreaksGoldenVector() {
        val variant = VariantPrng(useBigInteger = true)
        variant.seed(goldenSeed)
        gateMustCatch("BigInteger 累加 ⇒ 黄金向量必须判红") {
            assertGoldenVector(variant.s, List(4) { variant.next() })
        }
    }

    /**
     * G-PORT-7 变体②：漏掉 seed 末尾的 10 次预热。
     *
     * ⚠️ 上游 `:30-32` 的预热是**必需**的：漏掉则 s 停在 y（797779540141），
     * 前 4 个输出整体错位。
     */
    @Test
    fun gPort7_negative_missingWarmupBreaksGoldenVector() {
        val variant = VariantPrng(warmupCount = 0)
        variant.seed(goldenSeed)
        gateMustCatch("漏预热 ⇒ 黄金向量必须判红") {
            assertGoldenVector(variant.s, List(4) { variant.next() })
        }
    }

    /**
     * G-PORT-7 变体③：hash 的累加改成 Horner 形式。
     *
     * ⚠️ 取的是「按字符串顺序 `z = z·128 + c`」这一版（实际会被写出来的优化版）：
     * 它把升序累加 `Σ cᵢ·128ⁱ` 变成 `Σ cᵢ·128ⁿ⁻¹⁻ⁱ` ⇒ 与上游不等 ⇒ 必红。
     * ⚠️ 数学等价的反向 Horner（`Σ cᵢ·128ⁱ` 换一种舍入次序）对本输入恰好舍入到同一个
     * double ⇒ 不会变红 ⇒ **不能**用作负样本（本文件据此选型，不是拍脑袋）。
     */
    @Test
    fun gPort7_negative_hornerBreaksGoldenVector() {
        val variant = VariantPrng(useHorner = true)
        variant.seed(goldenSeed)
        gateMustCatch("Horner 形式 ⇒ 黄金向量必须判红") {
            assertGoldenVector(variant.s, List(4) { variant.next() })
        }
    }

    /**
     * 变体 PRNG —— **只在测试内**（⛔ 不改生产代码）。
     *
     * 与生产 [ShanShuiPrng] 的差别只有三个开关：BigInteger 累加 / Horner 累加 / 预热次数。
     */
    private class VariantPrng(
        private val useBigInteger: Boolean = false,
        private val useHorner: Boolean = false,
        private val warmupCount: Int = 10,
    ) {
        var s: Double = 1234.0
            private set

        private val p: Double = 999979.0
        private val q: Double = 999983.0
        private val m: Double = p * q

        fun seed(x: Any?) {
            if (useBigInteger) {
                seedBigInteger(x)
            } else {
                seedDouble(x)
            }
            repeat(warmupCount) { next() }
        }

        fun next(): Double {
            s = (s * s) % m
            return s / m
        }

        private fun seedDouble(x: Any?) {
            val h = hashDouble(x)
            var y = 0.0
            var z = 0.0
            while (y % p == 0.0 || y % q == 0.0 || y == 0.0 || y == 1.0) {
                y = (h + z) % m
                z += 1.0
            }
            s = y
        }

        private fun seedBigInteger(x: Any?) {
            val mI = BigInteger.valueOf(999979L).multiply(BigInteger.valueOf(999983L))
            val pI = BigInteger.valueOf(999979L)
            val qI = BigInteger.valueOf(999983L)
            val hI = hashBigInteger(x)
            var z = BigInteger.ZERO
            var y = BigInteger.ZERO
            while (true) {
                y = hI.add(z).mod(mI)
                z = z.add(BigInteger.ONE)
                val bad = y.mod(pI).signum() == 0 || y.mod(qI).signum() == 0 ||
                    y.signum() == 0 || y == BigInteger.ONE
                if (!bad) break
            }
            s = y.toDouble()
        }

        private fun hashDouble(x: Any?): Double {
            val b64 = base64(x.toString())
            var z = 0.0
            if (useHorner) {
                for (ch in b64) z = z * 128.0 + ch.code.toDouble()
            } else {
                for (i in b64.indices) {
                    z += b64[i].code.toDouble() * Math.pow(128.0, i.toDouble())
                }
            }
            return z
        }

        private fun hashBigInteger(x: Any?): BigInteger {
            val b64 = base64(x.toString())
            var acc = BigInteger.ZERO
            var pow = BigInteger.ONE
            val step = BigInteger.valueOf(128L)
            for (ch in b64) {
                acc = acc.add(BigInteger.valueOf(ch.code.toLong()).multiply(pow))
                pow = pow.multiply(step)
            }
            return acc
        }

        private fun base64(input: String): String {
            val bytes = input.toByteArray(Charsets.ISO_8859_1)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                sb.append(alphabet[b0 ushr 2])
                sb.append(alphabet[((b0 and 3) shl 4) or (b1 ushr 4)])
                if (i + 1 < bytes.size) {
                    sb.append(alphabet[((b1 and 15) shl 2) or (b2 ushr 6)])
                } else {
                    sb.append('=')
                }
                if (i + 2 < bytes.size) {
                    sb.append(alphabet[b2 and 63])
                } else {
                    sb.append('=')
                }
                i += 3
            }
            return sb.toString()
        }
    }

    // ══════════════════════ G-PORT-9 PRNG 抽取次数与上游一致 ══════════════════════

    /**
     * G-PORT-9：树与皴线的 **PRNG 抽取次数 / 轮廓条数**必须与上游逐条对齐。
     *
     * 口径来源：把上游 `shan-shui-inf/index.html` 的原始 JS 喂进 node，用 `Prng.next`
     * 计数实测（上游 `Math.random` 被全局覆写成 `Prng.next`）：
     * - `Tree.tree01(0, 0, {})` ⇒ **63** 次抽取 / **11** 条 poly
     *   （9 个叶簇 blob + line1 + line2；每个 blob 恒抽 7 次 ⇒ 9×7 = 63）；
     * - `Tree.tree03(0, 0, {})` ⇒ **504** 次抽取 / **73** 条 poly
     *   （72 个叶簇 blob + 1 条树干；72×7 = 504）。
     *
     * ⚠️ 这两个数字**与随机值无关**（叶簇个数与每次 blob 的抽取次数都是常量）⇒ 是稳定的门禁值。
     * ⚠️ 测量窗口之外必须先让 perlin 表就绪（上游首次 `Noise.noise` 要用 4096 个 PRNG 值建表；
     *    port 的 [ShanShuiNoise.noiseSeed] 用 LCG 建表 ⇒ 不消耗 PRNG ⇒ 窗口内干净）。
     */
    @Test
    fun gPort9_treePrngDrawCountMatchesUpstream() {
        resetPortGlobals()
        // 预热：确保 perlin 表已就绪（若尚未初始化，那 4096 次抽取不计入测量窗口）
        ShanShuiNoise.noise(0.0)

        val before1 = ShanShuiPrng.global.draws
        val c1 = ShanShuiTree.kind1(0.0, 0.0, 50.0, 3.0, ShanShuiColor.default, 0.5)
        val d1 = ShanShuiPrng.global.draws - before1
        assertTreeDrawCount("tree01", 63L, d1, 11, c1.size)

        val before3 = ShanShuiPrng.global.draws
        val c3 = ShanShuiTree.kind3(
            0.0, 0.0, 50.0, 5.0, { x -> Math.pow(x * 0.1, 1.0) },
            ShanShuiColor.default, 0.5,
        )
        val d3 = ShanShuiPrng.global.draws - before3
        assertTreeDrawCount("tree03", 504L, d3, 73, c3.size)
    }

    /**
     * G-PORT-9（皴线）：上游 `texture` 的**默认 col 是函数**（`:668-673`）⇒ 每条皴线抽 1 次随机，
     * alpha = `rnd*0.3`（`.toFixed(3)`）。
     */
    @Test
    fun gPort9_cunColorIsPerLineRandom() {
        resetPortGlobals()
        val before = ShanShuiPrng.global.draws
        val cols = List(5) { ShanShuiBrush.defaultCunCol() }
        val d = ShanShuiPrng.global.draws - before
        assertEquals("默认皴线颜色函数每次调用必须抽 1 次随机", 5L, d)
        assertTrue(
            "皴线 alpha 必须逐条随机（不得是固定色）",
            cols.map { it.a }.distinct().size > 1,
        )
        assertTrue(
            "皴线 alpha 必须落在上游的 [0, 0.3] 区间（实测 ${cols.map { it.a }}）",
            cols.all { it.a >= 0.0 && it.a <= 0.3 },
        )
    }

    /**
     * G-PORT-9 负向自证：**改造前**的口径（没有叶簇 ⇒ 0 次抽取、1~2 条轮廓）
     * 喂给正向用例用的同一份断言 ⇒ 必须抛 AssertionError。
     */
    @Test
    fun gPort9_negative_missingLeafClustersBreaksGate() {
        gateMustCatch("tree01 无叶簇的旧口径必须被判据咬住") {
            assertTreeDrawCount("tree01", 63L, 0L, 11, 2)
        }
        gateMustCatch("tree03 无叶簇的旧口径必须被判据咬住") {
            assertTreeDrawCount("tree03", 504L, 0L, 73, 1)
        }
    }

    /** G-PORT-9 的唯一判据口径（正向与负向共用）。 */
    private fun assertTreeDrawCount(
        name: String,
        expectedDraws: Long,
        actualDraws: Long,
        expectedContours: Int,
        actualContours: Int,
    ) {
        assertEquals(
            "$name 的 PRNG 抽取次数必须与上游一致",
            expectedDraws,
            actualDraws,
        )
        assertEquals(
            "$name 的轮廓条数必须与上游一致",
            expectedContours,
            actualContours,
        )
    }

    // ══════════════════════ G-PORT-8 结构性保真 ══════════════════════

    /**
     * G-PORT-8：每个 port 函数的输出——无 NaN、坐标范围合理、点数 > 2，
     * 且**同种子两次调用逐位相等**（随机序列稳定性）。
     */
    @Test
    fun gPort8_structureAndReproducibility() {
        for (case in structuralCases()) {
            val (first, second) = twicePinned(case.call)
            assertPointCloud(case.name, first, case.allowEmptyLists)
            assertEquals(
                "${case.name}：同种子两次调用必须逐位相等",
                pointCloudSignature(first),
                pointCloudSignature(second),
            )
        }
    }

    /** G-PORT-8（planner 部分）：plan / load / render 的结构与可复现性。 */
    @Test
    fun gPort8_plannerStructureAndReproducibility() {
        val (plannedA, plannedB) = twicePinned { ShanShuiPlanner.plan(0.0, 512.0) }
        assertTrue("plan 必须产出 chunk", plannedA.isNotEmpty())
        assertChunksFinite("plan", plannedA)
        assertEquals(
            "plan：同种子两次调用必须逐位相等",
            chunkSignature(plannedA),
            chunkSignature(plannedB),
        )

        val (stateA, stateB) = twicePinned {
            val st = ShanShuiPlanner.State()
            ShanShuiPlanner.load(st, 0.0, 0.0)
            ShanShuiPlanner.render(st, 0.0, 512.0)
            st
        }
        assertTrue("load/render 必须产出轮廓", stateA.canv.isNotEmpty())
        assertPointCloud("load/render", stateA.canv.map { it.pts }, allowEmptyLists = true)
        assertEquals(
            "load/render：同种子两次调用必须逐位相等",
            contourSignature(stateA.canv),
            contourSignature(stateB.canv),
        )
    }

    /** G-PORT-8 负向自证：点数不足 / 含 NaN / 坐标越界的点列 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort8_negative_brokenPointCloudBreaksGate() {
        val tooShort = listOf(listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
        gateMustCatch("点数 ≤ 2 的点列必须被判据咬住") { assertPointCloud("bad", tooShort) }
        val withNan = listOf(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f), floatArrayOf(2f, 2f)),
        )
        gateMustCatch("含 NaN 的点列必须被判据咬住") { assertPointCloud("bad", withNan) }
        val outOfRange = listOf(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f), floatArrayOf(1e6f, 0f)),
        )
        gateMustCatch("坐标越界的点列必须被判据咬住") { assertPointCloud("bad", outOfRange) }
        val empty = listOf(emptyList<ShanShuiPath.Point>())
        gateMustCatch("空点列（直接返回点列的函数不允许）必须被判据咬住") {
            assertPointCloud("bad", empty)
        }
        gateMustCatch("一个点列都不产出必须被判据咬住") {
            assertPointCloud("bad", emptyList())
        }
    }

    /** 一个 port 函数的用例（名字 + 是否允许空点列 + 调用）。 */
    private data class PointCase(
        val name: String,
        val allowEmptyLists: Boolean = false,
        val call: () -> List<List<ShanShuiPath.Point>>,
    )

    private fun structuralCases(): List<PointCase> = listOf(
        PointCase("ribbon") {
            listOf(
                ShanShuiBrush.ribbon(
                    (0 until 9).map { floatArrayOf(it * 10f, 50f) },
                    4.0, { x -> Math.sin(x * Math.PI) }, 0.5, ShanShuiColor.default,
                ),
            )
        },
        PointCase("dab") {
            listOf(ShanShuiBrush.dab(30.0, 40.0, 20.0, 5.0, 0.3, ShanShuiColor.default, 0.5))
        },
        PointCase("cun") {
            val ptlist = mountainPtlist()
            // ⚠️ 点阵的构造本身吃掉 10 个 PRNG 值 ⇒ 皴线之前必须复位（否则随机流错位）。
            resetPortGlobals()
            ShanShuiBrush.cun(ptlist, 20.0, 0.0)
        },
        PointCase("foot") {
            val ptlist = mountainPtlist()
            resetPortGlobals()
            ShanShuiBrush.foot(ptlist, 0.0, 0.0)
        },
        PointCase("mountain", allowEmptyLists = true) {
            ShanShui.mountain(0.0, 0.0, 7.0, 300.0, 500.0, 200.0, true, null).map { it.pts }
        },
        PointCase("flatMount", allowEmptyLists = true) {
            ShanShui.flatMount(0.0, 0.0, 7.0, 100.0, 600.0, 80.0, 0.5).map { it.pts }
        },
        PointCase("distMount", allowEmptyLists = true) {
            ShanShui.distMount(0.0, 0.0, 7.0, 150.0, 1000.0, 5.0).map { it.pts }
        },
        PointCase("rock", allowEmptyLists = true) {
            ShanShui.rock(0.0, 0.0, 7.0, 80.0, 100.0, 40.0, 10.0).map { it.pts }
        },
        PointCase("water", allowEmptyLists = true) {
            ShanShui.water(0.0, 0.0, 7.0, 2.0, 800.0, 10.0).map { it.pts }
        },
        PointCase("hut", allowEmptyLists = true) {
            ShanShuiArch.hut(0.0, 0.0, 40.0, 60.0, 10.0).map { it.pts }
        },
        PointCase("treeKind1", allowEmptyLists = true) {
            ShanShuiTree.kind1(0.0, 0.0, 50.0, 3.0, ShanShuiColor.default, 0.5).map { it.pts }
        },
        PointCase("treeKind2", allowEmptyLists = true) {
            ShanShuiTree.kind2(0.0, 0.0, 16.0, 8.0, 5.0, ShanShuiColor.default, 0.5).map { it.pts }
        },
        PointCase("treeKind3", allowEmptyLists = true) {
            ShanShuiTree.kind3(
                0.0, 0.0, 50.0, 5.0, { x -> Math.pow(x * 0.1, 1.0) },
                ShanShuiColor.default, 0.5,
            ).map { it.pts }
        },
        PointCase("archKind1", allowEmptyLists = true) {
            ShanShuiArch.kind1(0.0, 0.0, 3.0, 40.0, 160.0, 0.7, 8.0).map { it.pts }
        },
        PointCase("archKind2", allowEmptyLists = true) {
            ShanShuiArch.kind2(0.0, 0.0, 3.0, 40.0, 160.0, 0.7, 8.0, 0.0, 1.0, false).map { it.pts }
        },
    )

    private fun mountainPtlist(): List<List<ShanShuiPath.Point>> {
        resetPortGlobals()
        return ShanShui.mountainGrid(0.0, 0.0, 7.0, 300.0, 500.0)
    }

    private fun assertPointCloud(
        name: String,
        clouds: List<List<ShanShuiPath.Point>>,
        allowEmptyLists: Boolean = false,
    ) {
        assertTrue("$name 必须有输出", clouds.isNotEmpty())
        var total = 0
        for (i in clouds.indices) {
            val pts = clouds[i]
            if (pts.isEmpty()) {
                // ⚠️ 上游同款：皴线长度为 0（hlen = 0）时 stroke() 产出**空折线**
                // ⇒ 山体层里确实会出现 0 点的轮廓（上游同样如此，不是移植缺陷）。
                // ⛔ 但「直接返回点列」的函数（ribbon/dab/cun/foot）不允许空点列。
                assertTrue(
                    "$name 第 $i 条点列不得为空（退化输出）",
                    allowEmptyLists,
                )
                continue
            }
            assertTrue("$name 第 $i 条点列点数必须 > 2（实测 ${pts.size}）", pts.size > 2)
            total += pts.size
            for (p in pts) {
                assertTrue("$name 第 $i 条点列含 NaN", !p[0].isNaN() && !p[1].isNaN())
                assertTrue(
                    "$name 第 $i 条点列坐标越界（${p[0]}, ${p[1]}）",
                    Math.abs(p[0].toDouble()) < coordLimit && Math.abs(p[1].toDouble()) < coordLimit,
                )
            }
        }
        assertTrue("$name 必须产出实质几何（实测总点数 $total）", total > 0)
    }

    private fun assertChunksFinite(name: String, chunks: List<ShanShuiPlanner.Chunk>) {
        for (c in chunks) {
            assertTrue("$name 的 chunk x 不得为 NaN", !c.x.isNaN())
            assertTrue("$name 的 chunk y 不得为 NaN", !c.y.isNaN())
            assertTrue("$name 的 chunk x 必须有界（${c.x}）", Math.abs(c.x) < coordLimit)
            assertTrue("$name 的 chunk y 必须有界（${c.y}）", Math.abs(c.y) < coordLimit)
        }
    }

    /** 位精确签名（Float 的原始位 ⇒ 逐位相等）。 */
    private fun pointCloudSignature(clouds: List<List<ShanShuiPath.Point>>): String {
        val sb = StringBuilder()
        for (pts in clouds) {
            sb.append(pts.size).append('|')
            for (p in pts) {
                sb.append(java.lang.Float.floatToRawIntBits(p[0])).append(',')
                sb.append(java.lang.Float.floatToRawIntBits(p[1])).append(';')
            }
            sb.append('/')
        }
        return sb.toString()
    }

    private fun contourSignature(contours: List<ShanShuiContour>): String =
        pointCloudSignature(contours.map { it.pts })

    private fun chunkSignature(chunks: List<ShanShuiPlanner.Chunk>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            sb.append(c.tag).append('@')
            sb.append(java.lang.Double.doubleToRawLongBits(c.x)).append(',')
            sb.append(java.lang.Double.doubleToRawLongBits(c.y)).append(',')
            sb.append(java.lang.Double.doubleToRawLongBits(c.h)).append(';')
        }
        return sb.toString()
    }

    // ══════════════════════ G-PORT-10 distMount 三角剖分 ══════════════════════

    /**
     * G-PORT-10：远山的**三角剖分**必须与上游 `PolyTools.triangulate` 逐条一致。
     *
     * ## 夹具（与 node 探针逐字一致）
     * `distMount(xoff=0, yoff=0, seed=7, hei=150, len=500, seg=5)`
     * ⇒ `len/span/seg = 500/10/5 = 10` 块，每块 = 1 条「块多边形填色」+ N 条「三角形」。
     *
     * ## 上游实测值（node **实跑**上游原始 JS，⛔ 非手抄）
     * 探针：`C:\Users\hxzha\AppData\Local\Temp\opencode\probe_distmount_tri.js`
     * （把上游 `index.html` 的 `<script>` 块原样喂进 node，调用**上游原版**
     * `Mount.distMount` 与 `PolyTools.triangulate`）。
     * - 上游 SVG 的 `<polyline>` 总数 = **504** = 10 块填色 + **494** 个三角形；
     * - 逐块三角形条数 = **[37, 44, 44, 44, 44, 52, 60, 84, 60, 25]**；
     * - 三角形坐标摘要（FNV-1a 64，对 Float32 的原始位；顺序 = 块序 → 三角形序 → 顶点序）
     *   = **0xea8cbf2d27b1bcf6**；
     * - **退化楔形**（面积为 0 的三角形）**恰好 1 个**：第 0 块的第 **25** 个，
     *   顶点 = `[50, -46.9] [0, 0] [0, 0]`（后两点重合）——上游 SVG 原文
     *   `50.0,-46.9 0.0,0.0 0.0,0.0`。⛔ 这是**上游的怪癖**，不许「顺手修正」。
     *
     * ⚠️ 上游坐标是 Float64、port 的是 Float32（D14 既有偏差）⇒ 摘要比对的是
     *   「**上游算法在 Float32 输入上**的输出」（探针用 `Math.fround` 量化后再喂给
     *   上游原版 `PolyTools.triangulate`）⇒ 应当逐位相等。
     *   探针同时验证：Float32 参考与上游 SVG 在 0.1 文本精度上 **0 处**坐标失配、
     *   **0 处**色阶失配 ⇒ 量化在本夹具下**没有**改变任何可见结果。
     */
    @Test
    fun gPort10_distMountTriangleCountAndShapeMatchUpstream() {
        resetPortGlobals()
        assertDistMountTriangles(distMountContours())
    }

    /**
     * G-PORT-10（坐标）：三角形坐标摘要必须与上游**逐位**相等（全局 + 逐块）。
     */
    @Test
    fun gPort10_distMountTriangleGeometryMatchesUpstreamBitwise() {
        resetPortGlobals()
        val contours = distMountContours()
        val triangles = contours.filter { it.stroke != null }
        assertEquals(
            "三角形坐标摘要必须与上游逐位相等",
            upstreamTriangleDigest,
            fnv1a64(triangles),
        )
        // 逐块摘要（node 实跑，⛔ 非手抄）
        val perBlock = ArrayList<Long>()
        var current = ArrayList<ShanShuiContour>()
        for (c in contours) {
            if (c.stroke == null) {
                if (current.isNotEmpty()) {
                    perBlock.add(fnv1a64(current))
                    current = ArrayList()
                }
            } else {
                current.add(c)
            }
        }
        if (current.isNotEmpty()) perBlock.add(fnv1a64(current))
        assertEquals(
            "逐块三角形坐标摘要必须与上游逐位相等",
            upstreamPerBlockDigests,
            perBlock,
        )
    }

    /**
     * G-PORT-10（颜色）：每个三角形的颜色必须**重算**一遍 `getCol(中点)` 并逐位相等。
     *
     * ⚠️ `getCol` 的 `yoff` 是 **distMount 的入参 yoff**（上游 `:2409-2412` 的闭包捕获），
     *   **不是** chunk 的 y ⇒ 重算时用同一个 yoff。
     */
    @Test
    fun gPort10_distMountTriangleColorEqualsGetColOfMidPoint() {
        resetPortGlobals()
        assertTriangleColors(distMountContours(), distYoff)
    }

    /**
     * G-PORT-10（PRNG）：`distMount` 的 **PRNG 抽取次数必须是 0**（移植前后一致）。
     *
     * ⚠️ 上游的 `PolyTools.triangulate` 是**纯几何**（不碰 `Math.random`）⇒ 移植后
     *   抽取次数必须仍是 **0**。
     */
    @Test
    fun gPort10_distMountConsumesNoPrngDraws() {
        resetPortGlobals()
        val before = ShanShuiPrng.global.draws
        val contours = distMountContours()
        assertDistMountDrawCount(0L, ShanShuiPrng.global.draws - before)
        assertEquals("distMount 必须仍然产出轮廓", upstreamContourCount, contours.size)
    }

    /**
     * G-PORT-10 负向自证：把**故意坏值**喂给正向用例用的**同一份断言** ⇒ 必须抛 AssertionError。
     *
     * 覆盖 5 个坏值：
     * ① **移植前的口径**（10 条块填色、没有三角形）⇒ 条数 / 摘要 / 颜色三道断言都要红；
     * ② 三角形只有 2 个点（退化）⇒ 形状断言要红；
     * ③ 三角形 3 点但**共线**（面积为 0）⇒ 形状断言要红；
     * ④ 三角形的颜色不等于 `getCol(中点)` ⇒ 颜色断言要红；
     * ⑤ 「triangulate 偷抽随机数」的变体（在测量窗口内插一次抽取）⇒ 抽取次数断言要红。
     */
    @Test
    fun gPort10_negative_brokenValuesBreakGates() {
        // ① 移植前的口径：10 条块填色、0 条三角形
        val legacy = List(10) {
            ShanShuiContour(
                List(10) { i -> floatArrayOf(i * 10f, i * 2f) },
                ShanShuiColor(200, 200, 200, 1.0), null, 1.0,
            )
        }
        gateMustCatch("没有三角形的旧口径必须被条数判据咬住") {
            assertDistMountTriangles(legacy)
        }
        gateMustCatch("没有三角形的旧口径必须被摘要判据咬住") {
            assertEquals(
                "三角形坐标摘要必须与上游逐位相等",
                upstreamTriangleDigest,
                fnv1a64(legacy),
            )
        }
        gateMustCatch("没有三角形的旧口径必须被颜色判据咬住") {
            assertTriangleColors(legacy, distYoff)
        }
        // ② 2 点的退化三角形
        val twoPoints = ShanShuiContour(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 10f)),
            ShanShuiColor(200, 200, 200, 1.0),
            ShanShuiColor(200, 200, 200, 1.0),
            1.0,
        )
        gateMustCatch("2 点的退化三角形必须被形状判据咬住") {
            assertTriangleShape("坏三角形", twoPoints)
        }
        // ③ 3 点但**全部重合**（空壳三角形）
        val allSame = ShanShuiContour(
            listOf(floatArrayOf(3f, 4f), floatArrayOf(3f, 4f), floatArrayOf(3f, 4f)),
            ShanShuiColor(200, 200, 200, 1.0),
            ShanShuiColor(200, 200, 200, 1.0),
            1.0,
        )
        gateMustCatch("三点全重合的空壳三角形必须被形状判据咬住") {
            assertTriangleShape("坏三角形", allSame)
        }
        // ③' 退化楔形的个数/位置判据：移植前的口径（没有三角形 ⇒ 0 个退化）必须判红
        gateMustCatch("没有三角形的旧口径必须被退化楔形判据咬住") {
            assertEquals(
                "退化三角形（面积为 0）的个数与位置必须与上游一致",
                listOf("block=0 tri=25"),
                degenerateTriangleList(legacy),
            )
        }
        // ③'' 多出一个退化楔形 ⇒ 必须判红
        val extraDegenerate = legacy + ShanShuiContour(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 0f), floatArrayOf(20f, 0f)),
            ShanShuiColor(200, 200, 200, 1.0),
            ShanShuiColor(200, 200, 200, 1.0),
            1.0,
        )
        gateMustCatch("多一个退化楔形必须被退化楔形判据咬住") {
            assertEquals(
                "退化三角形（面积为 0）的个数与位置必须与上游一致",
                listOf("block=0 tri=25"),
                degenerateTriangleList(extraDegenerate),
            )
        }
        // ④ 颜色不等于 getCol(中点)
        val wrongColor = ShanShuiContour(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 20f), floatArrayOf(20f, 0f)),
            ShanShuiColor(1, 2, 3, 1.0),
            ShanShuiColor(1, 2, 3, 1.0),
            1.0,
        )
        gateMustCatch("颜色不等于 getCol(中点) 必须被颜色判据咬住") {
            assertTriangleColors(listOf(wrongColor), 0.0)
        }
        // ⑤ 「triangulate 偷抽随机数」的变体
        gateMustCatch("triangulate 若消耗随机数 ⇒ 抽取次数判据必须判红") {
            val before = ShanShuiPrng.global.draws
            ShanShuiPrng.global.rnd() // 模拟「triangulate 偷抽随机数」
            assertDistMountDrawCount(0L, ShanShuiPrng.global.draws - before)
        }
    }

    // ── G-PORT-10 的夹具与断言（正向与负向共用） ───────────────

    /** 上游实测的逐块三角形条数（node 实跑，⛔ 非手抄）。 */
    private val upstreamTriangleCounts = intArrayOf(37, 44, 44, 44, 44, 52, 60, 84, 60, 25)

    /** 上游实测的轮廓总数（10 块填色 + 494 个三角形）。 */
    private val upstreamContourCount = 504

    /** 上游实测的三角形坐标摘要（FNV-1a 64，对 Float32 原始位）。 */
    private val upstreamTriangleDigest = 0xea8cbf2d27b1bcf6uL.toLong()

    /** 上游实测的**逐块**三角形坐标摘要（node 实跑，⛔ 非手抄）。 */
    private val upstreamPerBlockDigests = listOf(
        0x6be6be0d7a25f419uL.toLong(),
        0x206aa3ebf3356ea5uL.toLong(),
        0x4d90e271c8d6fdd4uL.toLong(),
        0x65fde21e4ff011a8uL.toLong(),
        0x4bdcb13bfd2062cbuL.toLong(),
        0xd07a3d099d157c71uL.toLong(),
        0x23dd4acd0abb5e0cuL.toLong(),
        0x9759dca43bf4d149uL.toLong(),
        0x298955949b184861uL.toLong(),
        0x102c60ba40a1c6b9uL.toLong(),
    )

    /** G-PORT-10 夹具的入参（与 node 探针逐字一致）。 */
    private val distXoff = 0.0
    private val distYoff = 0.0
    private val distSeed = 7.0
    private val distHei = 150.0
    private val distLen = 500.0
    private val distSeg = 5.0

    private fun distMountContours(): List<ShanShuiContour> =
        ShanShui.distMount(distXoff, distYoff, distSeed, distHei, distLen, distSeg)

    /**
     * G-PORT-10 的「块结构 + 逐块三角形条数 + 三角形形状」判据（正向与负向共用）。
     *
     * 按上游的发射顺序切块：**块填色轮廓**（`stroke == null`，上游 `str: "none"`）
     * 之后紧跟该块的**三角形轮廓**（`stroke != null`，上游 `str: co`）。
     */
    private fun assertDistMountTriangles(contours: List<ShanShuiContour>) {
        assertEquals("轮廓总数必须与上游一致", upstreamContourCount, contours.size)
        var block = 0
        var triInBlock = 0
        for (c in contours) {
            if (c.stroke == null) {
                // 块填色轮廓（上游 `:2413-2417`）
                assertEquals("第 $block 块的填色轮廓必须 10 点", 10, c.pts.size)
                assertTrue("第 $block 块的填色轮廓必须有填充色", c.fill != null)
                if (block > 0) {
                    assertEquals(
                        "第 ${block - 1} 块的三角形条数必须与上游一致",
                        upstreamTriangleCounts[block - 1],
                        triInBlock,
                    )
                }
                triInBlock = 0
                block++
            } else {
                triInBlock++
                assertTriangleShape("第 ${block - 1} 块第 $triInBlock 个三角形", c)
            }
        }
        assertEquals("块数必须与上游一致", upstreamTriangleCounts.size, block)
        assertEquals(
            "最后一块的三角形条数必须与上游一致",
            upstreamTriangleCounts[block - 1],
            triInBlock,
        )
    }

    /** G-PORT-10 的「三角形形状」判据（正向与负向共用）。 */
    private fun assertTriangleShape(label: String, tri: ShanShuiContour) {
        assertEquals("$label 必须有 3 个点（上游 poly(T[k]) 就是 3 点）", 3, tri.pts.size)
        assertTrue("$label 必须有填充色（上游 fil: co）", tri.fill != null)
        assertEquals(
            "$label 的描边色必须与填充色相同（上游 fil = str = co）",
            tri.fill,
            tri.stroke,
        )
        assertEquals("$label 的描边宽度必须是 1（上游 wid: 1）", 1.0, tri.width, 0.0)
        // 闭合：3 点围成的三角形必须**非空**（三点不得全部重合 ⇒ 不能是空壳）
        val a = tri.pts[0]
        val b = tri.pts[1]
        val c = tri.pts[2]
        val allSame = a[0] == b[0] && a[1] == b[1] &&
            b[0] == c[0] && b[1] == c[1]
        assertTrue("$label 不得是三点全重合的空三角形", !allSame)
    }

    /**
     * G-PORT-10（退化楔形）：**退化三角形（面积为 0）的个数与位置必须与上游一致**。
     *
     * ⚠️ 上游的 `shatter` 会切出**退化**楔形：切分点 `mid` 取 `[ind, nind]` 的中点
     *   （上游 `:362`），若 `mid` 恰好落在 `[ind, lind]` 上 ⇒ 面积 = 0。
     *   本夹具下上游**恰好 1 个**退化三角形：**第 0 块的第 25 个**（1 基），
     *   顶点 = `[50, -46.9] [0, 0] [0, 0]`（后两点重合）
     *   （上游 SVG 原文：`50.0,-46.9 0.0,0.0 0.0,0.0`）。
     * ⛔ 这是**上游的怪癖**，**不许**「顺手修正」⇒ port 必须原样复现。
     */
    @Test
    fun gPort10_degenerateTriangleCountMatchesUpstream() {
        resetPortGlobals()
        assertEquals(
            "退化三角形（面积为 0）的个数与位置必须与上游一致",
            listOf("block=0 tri=25"),
            degenerateTriangleList(distMountContours()),
        )
    }

    /**
     * 退化三角形清单（块序 → 三角形序，**1 基**）—— 正向与负向共用。
     */
    private fun degenerateTriangleList(contours: List<ShanShuiContour>): List<String> {
        val degenerate = ArrayList<String>()
        var block = 0
        var idx = 0
        for (c in contours) {
            if (c.stroke == null) {
                block++
                idx = 0
                continue
            }
            idx++
            val a = c.pts[0]
            val b = c.pts[1]
            val d = c.pts[2]
            val cross = (b[0].toDouble() - a[0].toDouble()) * (d[1].toDouble() - a[1].toDouble()) -
                (d[0].toDouble() - a[0].toDouble()) * (b[1].toDouble() - a[1].toDouble())
            if (cross == 0.0) degenerate.add("block=${block - 1} tri=$idx")
        }
        return degenerate
    }

    /** G-PORT-10 的「颜色 = getCol(中点)」判据（正向与负向共用）。 */
    private fun assertTriangleColors(contours: List<ShanShuiContour>, yoff: Double) {
        var n = 0
        for (c in contours) {
            if (c.stroke == null) continue
            n++
            assertTriangleColor("第 $n 个三角形", c, yoff)
        }
        assertTrue("必须产出三角形（实测 $n 条）", n > 0)
    }

    /** 单个三角形的颜色重算（上游 `:2409-2412` + `:2425-2427`）。 */
    private fun assertTriangleColor(label: String, tri: ShanShuiContour, yoff: Double) {
        val m = ShanShuiPoly.midPtExact(tri.pts)
        val c = (ShanShuiNoise.noise(m[0] * 0.02, m[1] * 0.02, yoff) * 55 + 200).toInt()
        assertEquals(
            "$label 的颜色必须是 getCol(中点) 的结果",
            ShanShuiColor(c, c, c, 1.0),
            tri.fill,
        )
    }

    /** G-PORT-10 的「PRNG 抽取次数」判据（正向与负向共用）。 */
    private fun assertDistMountDrawCount(expected: Long, actual: Long) {
        assertEquals(
            "distMount 的 PRNG 抽取次数必须是 0（triangulate 是纯几何，不碰随机流）",
            expected,
            actual,
        )
    }

    /**
     * FNV-1a 64（对 Float32 的**原始位**）—— 与 node 探针的 `Digest` 逐位同构。
     *
     * ⚠️ 只喂**三角形**轮廓（块填色轮廓不参与）⇒ 与探针的哈希范围一致。
     */
    private fun fnv1a64(contours: List<ShanShuiContour>): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (c in contours) {
            for (p in c.pts) {
                h = fnvStep(h, java.lang.Float.floatToRawIntBits(p[0]))
                h = fnvStep(h, java.lang.Float.floatToRawIntBits(p[1]))
            }
        }
        return h
    }

    private fun fnvStep(h: Long, v: Int): Long =
        (h xor (v.toLong() and 0xFFFFFFFFL)) * 1099511628211L
}
