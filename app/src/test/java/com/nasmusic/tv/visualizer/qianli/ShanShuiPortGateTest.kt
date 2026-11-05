package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiArch
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiBrush
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPath
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
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
        val texlist = ShanShuiBrush.cun(ptlist, 20.0, 0.0, ShanShuiColor.default)
        assertCunShape(texlist)
    }

    /** G-PORT-4 负向自证：退化输入（1 列）⇒ 每条皴线长度为 0 ⇒ 同一份断言必须判红。 */
    @Test
    fun gPort4_negative_degenerateInputBreaksCunGate() {
        val thin = List(10) { listOf(floatArrayOf(0f, 0f)) }
        resetPortGlobals()
        val texlist = ShanShuiBrush.cun(thin, 20.0, 0.0, ShanShuiColor.default)
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
            ShanShuiBrush.cun(ptlist, 20.0, 0.0, ShanShuiColor.default)
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
}
