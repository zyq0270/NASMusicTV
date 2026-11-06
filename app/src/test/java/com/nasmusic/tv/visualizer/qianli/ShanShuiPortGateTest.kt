package com.nasmusic.tv.visualizer.qianli

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
 * 方案：`docs/archive/shan-shui-inf-porting-plan.md` §十 10.1。
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
 * | G-PORT-10 | **远山三角剖分与上游一致** | 轮廓 504 条 = 10 块填色 + 494 三角形；逐块条数 [37,44,44,44,44,52,60,84,60,25]；坐标摘要 0xea8cbf2d27b1bcf6 逐位相等；退化楔形恰好 1 个（block 0 / 第 25 个）；颜色 = getCol(中点)**的浓淡映射到前景山墨色族**（⭐ **按所有者裁定偏离上游的灰阶 [200,255]**，见 G-PORT-10′）；PRNG 抽取 = 0 |
 * | G-PORT-10′ | **远山墨色属前景山同族**（2026-11-06 裁定） | 全部远山轮廓的墨色 ∈ [100, 140]、三通道相等、且**有浓淡变化**（max > min） |
 * | G-PORT-11 | **rock 几何与上游逐位一致** | 4 个夹具的 WHITE BG 轮廓 51 点逐位相等（Float32 原始位）；样式 = 白填充/无描边/线宽 0；OUTLINE = 灰 0.3 + 线宽 1 |
 * | G-PORT-12 | **plan 与上游逐条一致 + planmtx 全局性** | 三组夹具（不重叠 / 有 mount / 重叠）的两段规划逐条一致；PRNG 抽取次数 = 上游扣掉 boat 后的次数；「局部 planmtx」口径必须与上游不同（判据有牙） |
 *
 * ## 负向自证规矩
 *
 * 每条判据都配一条「故意破坏真实值」的负向用例，且**咬在自己的 tag 上**：
 * 负向用例把**故意坏值**喂给**正向用例用的同一份断言** ⇒ 断言必须抛 AssertionError。
 * 绝不许通过放宽阈值让它变绿。
 *
 * ## 关于「与上游逐位一致」
 *
 * G-PORT-1 / G-PORT-9 / G-PORT-10 用**黄金向量**（PRNG / 抽取次数 / 摘要）对账；
 * **G-PORT-11 / G-PORT-12 则直接跑上游**：node 把上游 `shan-shui-inf/index.html` 的原始 JS
 * 喂进 node（只读），实跑 `Mount.rock` 与 `mountplanner`，产出黄金基线
 * `app/src/test/resources/qianli/shan_shui_upstream_golden.json`
 * （生成器 `scripts/qianli_upstream_ref.js`；`--check` 可校验是否漂移）。
 * ⇒ 这两条判据的**期望值不在测试源码里**（⛔ 不许手抄上游数字），
 * 测试只**读**基线文件并逐位比对。
 *
 * ## 全局状态的钉扎
 *
 * port 包有三个全局单例（[ShanShuiPrng.global] 的随机流、[ShanShuiNoise] 的 perlin 表、
 * [ShanShuiPlanner] 的**全局 planmtx**）会被各函数按调用次序消耗
 * ⇒ 任何「同种子两次调用」的判据都必须先把它们**复位**，否则第二次调用拿到的是不同的流。
 * 复位方式见 [resetPortGlobals]。
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
        // ⚠️ `MEM.planmtx` 在上游是**模块级全局**（跨 mountplanner 调用累积）⇒
        //    移植层也改成全局 ⇒ 必须一起复位，否则「同种子两次调用」不成立
        //    （上游等价物 = 刷新页面 ⇒ 整个 MEM 都是新的）。
        ShanShuiPlanner.resetPlanmtx()
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
            // ⚠️ `sto` = 上游的**层数**（`for (i = 0; i < sto; i++)`）⇒ 必须 ≥ 1 才有几何。
            ShanShuiArch.kind2(0.0, 0.0, 3.0, 40.0, 160.0, 0.7, 8.0, 2.0, 1.0, false).map { it.pts }
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
     *
     * ⭐ **按所有者裁定偏离上游**（2026-11-06，用户原话：「远山还是白色的，应该也按前景山颜色一样」）：
     * 上游 `getCol` 给的是**灰阶 [200, 255]**（极淡 ⇒ 画面上远山像一片白）。
     * 本仓改成**与前景山同族的墨色** ⇒ 判据随之变成
     * 「**先算上游 `getCol(中点)`，再按它的相对浓淡映射到新墨色**」。
     *
     * ⛔ 判据**不能**放宽成「随便什么颜色都行」—— 那会让这条门禁变成空壳；
     *   下面的负向自证专门喂「上游的灰阶原值」⇒ 必须判红 ⇒ 证明门禁真的绑在新映射上。
     */
    @Test
    fun gPort10_distMountTriangleColorEqualsGetColOfMidPoint() {
        resetPortGlobals()
        assertTriangleColors(distMountContours(), distYoff)
    }

    /**
     * G-PORT-10′（远山墨晕）：远山必须是「**前景山同墨色 + 半透明**」的墨晕。
     *
     * ⭐ 这是「按所有者裁定偏离上游」这条决策的**直接门禁**（三稿演进后的终稿）：
     * · 墨色 ∈ **[100, 140]**（前景山同族）—— 上游是灰阶 [200,255]（⇒ 白）⇒ 退回上游必红；
     * · alpha = **0.25**（墨晕）—— 实心（1.0）⇒ 远山变画面最重的一块，必红；
     *   全透明（0.0）⇒ 又退回「白」，必红；
     * · 三角剖分的**行底**必须仍是**纯白哨兵**（渲染器据此铺纸色）⇒ 不得被改成墨色。
     */
    @Test
    fun gPort10_farMountainInkIsInForegroundInkFamily() {
        resetPortGlobals()
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var tris = 0
        var rows = 0
        for (c in distMountContours()) {
            val f = c.fill ?: continue
            if (c.stroke == null) {
                // 行底（上游 `:2413-2417` 的块多边形）⇒ 本仓用「纯白哨兵」铺纸色。
                rows++
                assertEquals("远山行底必须是纯白哨兵 rgb=(${f.r},${f.g},${f.b})", 255, f.r)
                assertEquals("远山行底必须是纯白哨兵 rgb=(${f.r},${f.g},${f.b})", 255, f.g)
                assertEquals("远山行底必须是纯白哨兵 rgb=(${f.r},${f.g},${f.b})", 255, f.b)
                assertEquals("远山行底必须不透明（它要盖住身后的一切）", 1.0, f.a, 0.0)
                continue
            }
            tris++
            if (f.r < min) min = f.r
            if (f.r > max) max = f.r
            assertEquals("远山墨色必须三通道相等（实测 rgb=${f.r},${f.g},${f.b}）", f.r, f.g)
            assertEquals("远山墨色必须三通道相等（实测 rgb=${f.r},${f.g},${f.b}）", f.r, f.b)
            assertEquals(
                "远山刻面必须是半透明墨晕（实测 alpha=${f.a}）",
                ShanShui.FAR_MOUNTAIN_INK_ALPHA,
                f.a,
                1e-9,
            )
            // ⚠️ 描边：上游是 `str: co`（与填充同色）⇒ 不透明时**不可见** ⇒ 视觉上等于「只有填充」。
            //   本仓填充是半透明墨晕 ⇒ 描边若也带透明度就会与填充**叠加** ⇒ 出现渔网网格 ⇒ 必须 alpha = 0。
            val s = c.stroke
            assertTrue("远山刻面必须带描边（上游 `str: co`）", s != null)
            assertEquals("远山刻面的描边颜色必须与填充同色（上游 str: co）", f.r, s!!.r)
            assertEquals("远山刻面的描边颜色必须与填充同色（上游 str: co）", f.g, s.g)
            assertEquals("远山刻面的描边颜色必须与填充同色（上游 str: co）", f.b, s.b)
            assertEquals(
                "远山刻面的描边必须 alpha = 0（否则与半透明填充叠加 ⇒ 远山变成渔网，实测 ${s.a}）",
                0.0,
                s.a,
                0.0,
            )
        }
        assertTrue("必须产出远山轮廓（实测三角 $tris 条 + 行底 $rows 条）", tris > 0 && rows > 0)
        assertTrue("远山墨色必须 ≤ 140（实测最大 $max）—— 超过即退回上游的极淡灰", max <= 140)
        assertTrue("远山墨色必须 ≥ 100（实测最小 $min）—— 低于即比前景山更重", min >= 100)
        assertTrue(
            "远山墨色必须有浓淡变化（实测区间 [$min, $max]）—— 全同值说明噪声浓淡丢了",
            max > min,
        )
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
     * ④ 三角形的颜色不等于「getCol(中点) 映射到新墨色」⇒ 颜色断言要红；
     *    （⚠️ 特意喂**上游的灰阶原值 231** ⇒ 它在旧门禁下是"正确的"⇒ 证明新判据有牙）；
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
        //    ⚠️ 填充/描边用**合法值**（墨色 122 / alpha 0.25 / 描边 alpha 0）
        //    ⇒ 这样只有「点数」这一条判据会红 ⇒ 负向自证才真的证明「点数判据有牙」。
        val twoPoints = ShanShuiContour(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 10f)),
            ShanShuiColor(122, 122, 122, ShanShui.FAR_MOUNTAIN_INK_ALPHA),
            ShanShuiColor(122, 122, 122, 0.0),
            1.0,
        )
        gateMustCatch("2 点的退化三角形必须被形状判据咬住") {
            assertTriangleShape("坏三角形", twoPoints)
        }
        // ③ 3 点但**全部重合**（空壳三角形）
        val allSame = ShanShuiContour(
            listOf(floatArrayOf(3f, 4f), floatArrayOf(3f, 4f), floatArrayOf(3f, 4f)),
            ShanShuiColor(122, 122, 122, ShanShui.FAR_MOUNTAIN_INK_ALPHA),
            ShanShuiColor(122, 122, 122, 0.0),
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
        // ④ 颜色不等于「getCol(中点) 映射到新墨色」
        //    ⚠️ 特意喂**上游的灰阶原值**（231 = 上游 getCol 的典型值）⇒
        //    它在**旧门禁**下是"正确的" ⇒ 若新门禁不咬住它，就说明门禁被放宽成空壳了。
        val upstreamGrayColor = ShanShuiContour(
            listOf(floatArrayOf(0f, 0f), floatArrayOf(10f, 20f), floatArrayOf(20f, 0f)),
            ShanShuiColor(231, 231, 231, 1.0),
            ShanShuiColor(231, 231, 231, 1.0),
            1.0,
        )
        gateMustCatch("上游的灰阶原值（已按裁定偏离）必须被颜色判据咬住") {
            assertTriangleColors(listOf(upstreamGrayColor), 0.0)
        }
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
        // ⚠️ 上游是 `poly(T[k], {fil: co, str: co, wid: 1})` ⇒ 描边与填充**同色**。
        //   ⇒ 判据拆成两半：
        //   ① **RGB 必须相同**（颜色本身是上游的 `co`）—— 这是上游的结构性要求；
        //   ② **alpha 允许不同**：本仓的填充是**半透明墨晕**（0.25），若描边也带 0.25
        //      就会与填充叠加 ⇒ 三角剖分的每条边变**深色网格线** ⇒ 远山像渔网 ⇒
        //      描边的 alpha 必须是 **0**（视觉上与上游等价：上游填充不透明 ⇒ 描边不可见）。
        val f = tri.fill!!
        val s = tri.stroke
        assertTrue("$label 必须有描边（上游 str: co）", s != null)
        assertEquals("$label 的描边 RGB 必须与填充相同（上游 fil = str = co）", f.r, s!!.r)
        assertEquals("$label 的描边 RGB 必须与填充相同（上游 fil = str = co）", f.g, s.g)
        assertEquals("$label 的描边 RGB 必须与填充相同（上游 fil = str = co）", f.b, s.b)
        assertEquals(
            "$label 的描边 alpha 必须是 0（否则与半透明填充叠加 ⇒ 出现渔网网格，实测 ${s.a}）",
            0.0,
            s.a,
            0.0,
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

    /**
     * 单个三角形的颜色重算（上游 `:2409-2412` + `:2425-2427`，**再按所有者裁定映射到新墨晕**）。
     *
     * ⚠️ 映射公式与生产侧 [ShanShui.distMount] 里的 `farMountainInk` **必须逐位一致**：
     * `t = (gray − 200) / 55`（钳到 [0,1]）⇒ `ink = (100 + t × 40).toInt()`。
     * ⚠️ alpha 必须是 **0.25**（墨晕）—— 所有者裁定 2026-11-06 的第 ③ 稿。
     */
    private fun assertTriangleColor(label: String, tri: ShanShuiContour, yoff: Double) {
        val m = ShanShuiPoly.midPtExact(tri.pts)
        val gray = (ShanShuiNoise.noise(m[0] * 0.02, m[1] * 0.02, yoff) * 55 + 200).toInt()
        val t = ((gray - 200.0) / 55.0).coerceIn(0.0, 1.0)
        val c = (100 + t * 40).toInt()
        assertEquals(
            "$label 的颜色必须是 getCol(中点)（灰阶 $gray）映射到前景山墨色族后的结果",
            ShanShuiColor(c, c, c, ShanShui.FAR_MOUNTAIN_INK_ALPHA),
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

    // ══════════════════════ G-PORT-11 / G-PORT-12 上游逐位对账 ══════════════════════
    //
    // 这两条判据的期望值**不写在测试里** ⇒ 全部来自 node 实跑上游 `shan-shui-inf` 生成的
    // 黄金基线 `app/src/test/resources/qianli/shan_shui_upstream_golden.json`
    // （生成器：`scripts/qianli_upstream_ref.js`；`node scripts/qianli_upstream_ref.js --check`
    //  可校验基线是否与上游实跑结果漂移）。
    //
    // ⚠️ 对齐口径（与生成器逐字一致）：
    //   · PRNG：上游 `Math.random` 被全局覆写成 `Prng.next` ⇒ 种子 = 157（= [portSeed]）。
    //   · perlin 表：用上游**自带**的 `Noise.noiseSeed(20250101)`（上游 `:177-202` 的 LCG 建表）
    //     ⇒ 与 [ShanShuiNoise.noiseSeed] 逐字一致，且**不消耗 PRNG**。
    //   · 上游的 boat 分支（`:3806-3811`）本仓**未移植**（已接受偏差 O7）⇒
    //     端口不抽那几次随机 ⇒ 测试在两段规划之间**补抽同样次数**（黄金基线里的 boatDraws）
    //     把两端的随机流重新对齐。

    /** 黄金基线（懒加载 + 缓存）。 */
    private val golden: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(GOLDEN_RESOURCE)
            ?: throw AssertionError(
                "缺少上游黄金基线：app/src/test/resources$GOLDEN_RESOURCE" +
                    "（由 `node scripts/qianli_upstream_ref.js` 生成）",
            )
        stream.use { JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject }
    }

    /**
     * G-PORT-11：`Mount.rock` 的 **WHITE BG 轮廓点列**必须与上游**逐位相等**。
     *
     * 口径：上游 `rock`（`:2484-2490`）发的是
     * `poly(ptlist[0].concat([[0, 0]]), {xof, yof, fil:"white", str:"none"})`
     * ⇒ 51 点（`ptlist[0]` 的 50 点 + 追加的 `[0,0]`；`poly` 再把 (xof,yof) 加上去）。
     * 端口侧对应 [ShanShui.rock] 的**第 1 条轮廓**（fill=白 / stroke=null / width=0）。
     *
     * ⚠️ 比对用 **Float32 的原始位**（上游是 Float64，端口是 Float32 —— D14 既有偏差）：
     *   把上游的 Float64 量化成 Float32 后逐位比较（等价于 `Math.fround`）。
     * ⚠️ xoff/yoff 取 0 ⇒ 端口不引入「先量化再平移」的二次舍入。
     */
    @Test
    fun gPort11_rockWhiteBgMatchesUpstreamBitwise() {
        resetPortGlobals()
        for (f in golden.getAsJsonArray("rock")) {
            val o = f.asJsonObject
            val name = o.get("name").asString
            val contours = ShanShui.rock(
                0.0,
                0.0,
                o.get("seed").asDouble,
                o.get("hei").asDouble,
                o.get("wid").asDouble,
                o.get("tex").asDouble,
                o.get("sha").asDouble,
            )
            assertTrue("rock[$name] 必须产出轮廓", contours.isNotEmpty())
            val whiteBg = contours[0]
            assertRockWhiteBgStyle("rock[$name]", whiteBg)
            val expected = o.getAsJsonArray("whiteBg").map { it.asJsonArray }
            assertRockWhiteBgPoints("rock[$name]", expected, whiteBg)
        }
    }

    /**
     * G-PORT-11（轮廓样式）：WHITE BG 必须是「白填充 / 无描边 / 线宽 0」，
     * OUTLINE 必须是「灰 0.3 填充 + 同色描边 / 线宽 1」（上游 `stroke` 的 `out` 缺省 1）。
     */
    @Test
    fun gPort11_rockContourStylesMatchUpstream() {
        resetPortGlobals()
        val contours = ShanShui.rock(0.0, 0.0, 7.0, 80.0, 100.0, 40.0, 10.0)
        assertTrue("rock 必须产出轮廓", contours.size >= 2)
        assertRockWhiteBgStyle("rock", contours[0])
        val outline = contours[1]
        assertEquals("OUTLINE 的填充色必须是灰 0.3", ShanShuiColor.outline, outline.fill)
        assertEquals("OUTLINE 的描边色必须与填充色相同", ShanShuiColor.outline, outline.stroke)
        assertEquals("OUTLINE 的线宽必须是上游 stroke 的 out=1", 1.0, outline.width, 0.0)
    }

    /**
     * G-PORT-11 负向自证：把**故意改坏**的期望点喂给正向用例用的**同一份断言**
     * ⇒ 必须抛 AssertionError。
     */
    @Test
    fun gPort11_negative_perturbedPointsBreakGate() {
        resetPortGlobals()
        val o = golden.getAsJsonArray("rock").first().asJsonObject
        val contours = ShanShui.rock(
            0.0, 0.0,
            o.get("seed").asDouble, o.get("hei").asDouble, o.get("wid").asDouble,
            o.get("tex").asDouble, o.get("sha").asDouble,
        )
        // 改动第 7 个点的 x（1e-4 ⇒ 远大于 Float32 的 1 ULP ⇒ 必被咬住）
        val expected = o.getAsJsonArray("whiteBg").map { it.asJsonArray }.toMutableList()
        val p7 = expected[7].map { it.asDouble }.toMutableList()
        p7[0] = p7[0] + 1e-4
        expected[7] = JsonArray().apply { add(p7[0]); add(p7[1]) }
        gateMustCatch("被改动的点必须被判据咬住") {
            assertRockWhiteBgPoints("rock(坏值)", expected, contours[0])
        }
    }

    /**
     * G-PORT-12：`plan` 的输出必须与上游 `mountplanner` **逐条一致**，
     * 且**跨调用**保持一致（`MEM.planmtx` 是全局累积的）。
     *
     * 三组夹具（黄金基线里的 `plan.segments` / `plan.mountSegments` / `plan.overlapSegments`）：
     * - `segments`：任务书指定的 `[0,500)` + `[500,1000)`；
     * - `mountSegments`：`[2000,2500)` + `[2500,3000)` —— 该区间**会产出 mount**
     *   ⇒ `planmtx` 真的被标记过 ⇒ 第二段的 flatmount 判定受「跨调用累积」影响
     *   （**有区分力**：局部数组口径会得到完全不同的结果）；
     * - `overlapSegments`：`[2000,2500)` 连调两次。
     *
     * 额外断言：端口 `plan()` 的 **PRNG 抽取次数**必须等于上游该次调用扣除 boat 后的抽取数。
     */
    @Test
    fun gPort12_planMatchesUpstreamAcrossCalls() {
        for (fixture in planFixtures()) {
            resetPortGlobals()
            // 第一段
            val before0 = ShanShuiPrng.global.draws
            val seg0 = ShanShuiPlanner.plan(fixture.segments[0][0], fixture.segments[0][1])
            val draws0 = ShanShuiPrng.global.draws - before0
            // ⚠️ 2026-11-06：**不再扣掉 boatDraws** —— 端口现在**自己生成舟**
            //   （上游 `mountplanner` 的舟段 `:3806-3811` 已移植）⇒ 舟的那部分随机
            //   **已经在 plan() 内部消耗掉了** ⇒ 总抽取次数必须与上游的 `draws` **逐次相等**。
            assertEquals(
                "${fixture.label} 第 1 段的 PRNG 抽取次数必须与上游一致（含舟段）",
                fixture.results[0].draws,
                draws0,
            )
            assertPlanSegment(
                "${fixture.label} 第 1 段（非舟）",
                fixture.results[0].entries,
                seg0.filter { it.tag != "boat" },
            )
            assertPlanSegment(
                "${fixture.label} 第 1 段（舟）",
                fixture.results[0].boatEntries,
                seg0.filter { it.tag == "boat" },
            )
            // 第二段
            val before1 = ShanShuiPrng.global.draws
            val seg1 = ShanShuiPlanner.plan(fixture.segments[1][0], fixture.segments[1][1])
            val draws1 = ShanShuiPrng.global.draws - before1
            assertEquals(
                "${fixture.label} 第 2 段的 PRNG 抽取次数必须与上游一致（含舟段）",
                fixture.results[1].draws,
                draws1,
            )
            assertPlanSegment(
                "${fixture.label} 第 2 段（非舟）",
                fixture.results[1].entries,
                seg1.filter { it.tag != "boat" },
            )
            assertPlanSegment(
                "${fixture.label} 第 2 段（舟）",
                fixture.results[1].boatEntries,
                seg1.filter { it.tag == "boat" },
            )
        }
    }

    /**
     * G-PORT-12（**全局性有牙**）：上游的 `MEM.planmtx` 是模块级全局 ⇒ 跨调用累积。
     * 本仓移植层**修改前**用「每次调用新建的局部数组」⇒ 结果与上游不同。
     *
     * 黄金基线里的 `localEntries` 就是「每次调用清空 planmtx」口径下的上游实跑结果
     * ⇒ 它必须**不等于**端口（全局口径）的结果 ⇒ 否则这条判据没有区分力。
     */
    @Test
    fun gPort12_localPlanmtxWouldDifferFromUpstream() {
        val fixture = planFixtures().first { it.label == "mount" }
        // 上游：全局口径 ≠ 局部口径（该夹具有区分力）
        assertTrue(
            "「mount」夹具必须能区分全局/局部 planmtx（否则判据无牙）",
            fixture.results[1].entries != fixture.results[1].localEntries,
        )
        // 端口（全局口径）必须落在上游的**全局**口径上
        resetPortGlobals()
        ShanShuiPlanner.plan(fixture.segments[0][0], fixture.segments[0][1])
        val seg1 = ShanShuiPlanner.plan(fixture.segments[1][0], fixture.segments[1][1])
        assertPlanSegment(
            "mount 第 2 段（非舟）",
            fixture.results[1].entries,
            seg1.filter { it.tag != "boat" },
        )
    }

    /**
     * G-PORT-12 负向自证：把**局部 planmtx 口径**的上游结果喂给正向用例用的**同一份断言**
     * ⇒ 必须抛 AssertionError（⇒ 端口若退回局部数组，这条判据必红）。
     *
     * ⚠️ 只喂**非舟**子集：舟条目与 planmtx 无关 ⇒ 若把舟也喂进去，
     *    断言会因为「舟多了一条」而失败 ⇒ 判据就**因为错误的原因**变红（失效）。
     */
    @Test
    fun gPort12_negative_localPlanmtxBreaksGate() {
        val fixture = planFixtures().first { it.label == "mount" }
        resetPortGlobals()
        ShanShuiPlanner.plan(fixture.segments[0][0], fixture.segments[0][1])
        val seg1 = ShanShuiPlanner.plan(fixture.segments[1][0], fixture.segments[1][1])
        val seg1NonBoat = seg1.filter { it.tag != "boat" }
        // 负向自证的前提：喂进去的「局部口径」必须与端口的**全局口径**确实不同
        assertTrue(
            "负向自证的前提：局部口径的条目必须与全局口径不同（否则判据无牙）",
            fixture.results[1].localEntries != fixture.results[1].entries,
        )
        gateMustCatch("局部 planmtx 的口径必须被判据咬住") {
            assertPlanSegment("mount 第 2 段（局部口径）", fixture.results[1].localEntries, seg1NonBoat)
        }
    }

    // ── G-PORT-11 / G-PORT-12 的夹具与断言（正向与负向共用） ───────────

    /** 黄金基线里的规划夹具。 */
    private data class PlanFixture(
        val label: String,
        val segments: List<DoubleArray>,
        val results: List<PlanCallResult>,
    )

    /** 黄金基线里的一次 `mountplanner` 调用结果。 */
    private data class PlanCallResult(
        val draws: Long,
        val boatDraws: Long,
        val entries: List<PlanEntry>,
        val boatEntries: List<PlanEntry>,
        val localEntries: List<PlanEntry>,
    )

    /** 黄金基线里的一条规划项。 */
    private data class PlanEntry(
        val tag: String,
        val x: Double,
        val y: Double,
        val h: Double,
    )

    private fun planFixtures(): List<PlanFixture> {
        val plan = golden.getAsJsonObject("plan")
        val out = ArrayList<PlanFixture>()
        fun add(label: String, segKey: String, resKey: String) {
            val segments = plan.getAsJsonArray(segKey).map { s ->
                val o = s.asJsonObject
                doubleArrayOf(o.get("xmin").asDouble, o.get("xmax").asDouble)
            }
            val results = plan.getAsJsonArray(resKey).map { r ->
                val o = r.asJsonObject
                PlanCallResult(
                    draws = o.get("draws").asLong,
                    boatDraws = o.get("boatDraws").asLong,
                    entries = parsePlanEntries(o.getAsJsonArray("entries")),
                    boatEntries = parsePlanEntries(o.getAsJsonArray("boatEntries")),
                    localEntries = parsePlanEntries(o.getAsJsonArray("localEntries")),
                )
            }
            out.add(PlanFixture(label, segments, results))
        }
        add("segments", "segments", "results")
        add("mount", "mountSegments", "mountResults")
        add("overlap", "overlapSegments", "overlapResults")
        return out
    }

    private fun parsePlanEntries(arr: JsonArray): List<PlanEntry> =
        arr.map { e ->
            val o = e.asJsonObject
            // ⚠️ **上游的 boat 条目没有 `h` 字段**（`{tag,x,y}`）⇒ 必须容错，
            //    否则 `o.get("h")` 返回 null ⇒ `NullPointerException`（2026-11-06 真踩了）。
            //    缺省 0.0 与端口的 `Chunk("boat", …, 0.0, …)` 一致（上游的 `h` 从不被读）。
            PlanEntry(
                o.get("tag").asString,
                o.get("x").asDouble,
                o.get("y").asDouble,
                if (o.has("h")) o.get("h").asDouble else 0.0,
            )
        }

    /** rock 的 WHITE BG 样式断言（上游 `:2484-2490`：`fil:"white", str:"none"`）。 */
    private fun assertRockWhiteBgStyle(label: String, contour: ShanShuiContour) {
        assertEquals("$label 的 WHITE BG 必须是白填充", ShanShuiColor(255, 255, 255, 1.0), contour.fill)
        assertEquals("$label 的 WHITE BG 必须无描边（上游 str:\"none\"）", null, contour.stroke)
        assertEquals("$label 的 WHITE BG 线宽必须是 0（上游未给 wid）", 0.0, contour.width, 0.0)
    }

    /**
     * rock 的 WHITE BG **点列**断言（正向与负向共用）。
     *
     * @param expected 黄金基线里的上游点列（Float64）
     */
    private fun assertRockWhiteBgPoints(
        label: String,
        expected: List<JsonArray>,
        contour: ShanShuiContour,
    ) {
        assertEquals("$label 的 WHITE BG 必须是 51 点（50 + 追加的 [0,0]）", 51, contour.pts.size)
        assertEquals("$label 的期望点列长度必须一致", 51, expected.size)
        for (i in expected.indices) {
            val e = expected[i].asJsonArray
            val ex = e[0].asDouble.toFloat()
            val ey = e[1].asDouble.toFloat()
            assertEquals(
                "$label 第 $i 点的 x 必须与上游逐位相等",
                java.lang.Float.floatToRawIntBits(ex),
                java.lang.Float.floatToRawIntBits(contour.pts[i][0]),
            )
            assertEquals(
                "$label 第 $i 点的 y 必须与上游逐位相等",
                java.lang.Float.floatToRawIntBits(ey),
                java.lang.Float.floatToRawIntBits(contour.pts[i][1]),
            )
        }
    }

    /**
     * 规划项的逐条比对断言（正向与负向共用）。
     *
     * ⚠️ `x`/`y` 必须**逐位相等**（纯算术，无三角函数）。
     * ⚠️ `h` 是噪声值（上游 `ns(i,j)`）⇒ Java 的 `Math.cos` 与 V8 的 `Math.cos`
     *   可能差 1 ULP（实测 64 个采样点里 3 个）⇒ `h` 用 **1e-9 的绝对容差**。
     */
    private fun assertPlanSegment(
        label: String,
        expected: List<PlanEntry>,
        actual: List<ShanShuiPlanner.Chunk>,
    ) {
        assertEquals("$label 的条目数必须与上游一致", expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            assertEquals("$label 第 $i 条的 tag 必须与上游一致", e.tag, a.tag)
            assertEquals(
                "$label 第 $i 条的 x 必须与上游逐位相等",
                java.lang.Double.doubleToRawLongBits(e.x),
                java.lang.Double.doubleToRawLongBits(a.x),
            )
            assertEquals(
                "$label 第 $i 条的 y 必须与上游逐位相等",
                java.lang.Double.doubleToRawLongBits(e.y),
                java.lang.Double.doubleToRawLongBits(a.y),
            )
            assertTrue(
                "$label 第 $i 条的 h 必须与上游一致（实测 ${a.h} vs ${e.h}）",
                Math.abs(a.h - e.h) <= 1e-9,
            )
        }
    }

    // ══════════════════════ G-PORT-13 arch01 / hut 逐位对照上游 ══════════════════════

    /**
     * G-PORT-13：E50 的 `Arch.arch01`（草屋 hut + 桥身 box + 栏杆 rail×2 + 渔人 `Man.man`）
     * 与上游**逐位**一致。
     *
     * ## 口径
     * - 黄金基线：`app/src/test/resources/qianli/shan_shui_arch01_golden.json`
     *   （由 `node scripts/e50_arch01_upstream_probe.js` 实跑上游原始 JS 生成；
     *   `--check` 可校验是否漂移）。⛔ **期望值不在测试源码里**。
     * - PRNG 种子 157（= [portSeed]）；perlin 表用 LCG 种子 20250101（= [noiseSeedValue]）
     *   ⇒ 与 port 的 [ShanShuiNoise.noiseSeed] 逐字一致、**不消耗 PRNG**。
     * - 上游的渔人（`Man.man`）**本仓不渲染**（改造-②），但**抽取次数照上游消耗**
     *   ⇒ 抽取次数差值 = **0**（已知且被记录的常量）。
     *
     * ## 为什么坐标用「量化网格摘要」而不是逐位比 Float32
     * V8 与 JVM 的 `Math.cos/sin/atan2` **最多差 1 ULP** ⇒ 同一个浮点式在两侧量化成
     * Float32 后偶尔差 1 个 ULP（实测 951 点里 115 点、最大 7.63e-6 = 1 个 Float32 ULP）。
     * ⇒ 坐标改比「`Math.round(v * scale)` 后的整数」：
     * - `scale = 10` ⇒ **0.1 网格 = 上游 `poly()` 自己的输出精度**（上游写进 SVG 的就是它）
     *   ⇒ 「port 的点落在上游的同一个 0.1 网格上」；
     * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
     * 两个 scale 在本夹具下**都是零失配**（node 侧逐点核对过 951×2 个坐标）。
     */
    @Test
    fun gPort13_arch01GeometryMatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = archGolden("arch01")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiArch.kind1(
            g.xoff, g.yoff, g.seed, g.hei, g.wid, archRotUnusedByUpstream, g.per,
        )
        val draws = ShanShuiPrng.global.draws - before
        assertArchMatchesUpstream("arch01", g, contours)
        assertArchDrawCount("arch01", g.draws, draws)
    }

    /**
     * G-PORT-13（草屋）：上游 `hut`（`:2542-2607`）的 10×10 网格 + 一轮皴必须逐位一致。
     *
     * ⚠️ `tex` 的语义是上游的**皴线数**（⛔ 不是「弧顶采样段数」）
     * ⇒ 实测 10 条皴线里只有 7 条长度非零（上游 `stroke()` 对空点列返回 `""`）
     * ⇒ 上游发 3 + 7 = **10** 条轮廓。
     */
    @Test
    fun gPort13_hutGeometryMatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = archGolden("hut")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiArch.hut(g.xoff, g.yoff, g.hei, g.wid, g.tex)
        val draws = ShanShuiPrng.global.draws - before
        assertArchMatchesUpstream("hut", g, contours)
        assertArchDrawCount("hut", g.draws, draws)
    }

    /**
     * G-PORT-13 负向自证：把**故意写错**的实现喂给正向用例用的**同一份断言** ⇒ 必须抛 AssertionError。
     *
     * 覆盖 5 种坏值：
     * ① **移植前的旧实现**（一条闭合的半圆拱轮廓）⇒ 条数/摘要/坐标三道断言都要红；
     * ② 坐标被挪动 0.5 ⇒ 坐标摘要要红；
     * ③ 少一条轮廓 ⇒ 条数要红；
     * ④ 白底被改成透明 ⇒ 结构摘要要红；
     * ⑤ 抽取次数少了 121 次（= 漏掉「零长度皴线也要抽的那一次颜色随机」）⇒ 抽取次数要红。
     */
    @Test
    fun gPort13_negative_brokenArch01BreaksGate() {
        resetPortGlobals()
        val g = archGolden("arch01")
        val good = ShanShuiArch.kind1(
            g.xoff, g.yoff, g.seed, g.hei, g.wid, archRotUnusedByUpstream, g.per,
        )
        // ① 移植前的旧实现（一条闭合轮廓）
        val legacy = listOf(
            ShanShuiContour(
                (0..16).map { i -> floatArrayOf(i * 10f, i * 3f - 40f) },
                ShanShuiColor(255, 255, 255, 1.0),
                ShanShuiColor.rgba(100, 100, 100, 0.4),
                1.5,
            ),
        )
        gateMustCatch("移植前的旧实现必须被判据咬住") { assertArchMatchesUpstream("arch01", g, legacy) }
        // ② 坐标被挪动 0.5
        val moved = good.mapIndexed { i, c ->
            if (i == 7) {
                ShanShuiContour(
                    c.pts.map { floatArrayOf(it[0] + 0.5f, it[1]) }, c.fill, c.stroke, c.width,
                )
            } else {
                c
            }
        }
        gateMustCatch("坐标被挪动的实现必须被判据咬住") { assertArchMatchesUpstream("arch01", g, moved) }
        // ③ 少一条轮廓
        gateMustCatch("少一条轮廓的实现必须被判据咬住") {
            assertArchMatchesUpstream("arch01", g, good.dropLast(1))
        }
        // ④ 白底被改成透明
        val recolored = good.mapIndexed { i, c ->
            if (i == 0) ShanShuiContour(c.pts, null, c.stroke, c.width) else c
        }
        gateMustCatch("白底被改色的实现必须被判据咬住") {
            assertArchMatchesUpstream("arch01", g, recolored)
        }
        // ⑤ 抽取次数少 121 次（漏掉「零长度皴线也要抽的那一次颜色随机」）
        gateMustCatch("抽取次数不符的实现必须被判据咬住") {
            assertArchDrawCount("arch01", g.draws, g.draws - 121L)
        }
    }

    /** G-PORT-13 负向自证（草屋）：`hut` 的旧简化实现（一条闭合弧顶）必须判红。 */
    @Test
    fun gPort13_negative_brokenHutBreaksGate() {
        resetPortGlobals()
        val g = archGolden("hut")
        val legacyHut = listOf(
            ShanShuiContour(
                (0..12).map { i -> floatArrayOf(i * 5f, 20f - i * 2f) },
                ShanShuiColor(255, 255, 255, 1.0),
                ShanShuiColor.rgba(100, 100, 100, 0.4),
                1.5,
            ),
        )
        gateMustCatch("hut 的旧简化实现必须被判据咬住") {
            assertArchMatchesUpstream("hut", g, legacyHut)
        }
    }

    // ── G-PORT-13 的夹具与断言（正向与负向共用） ───────────────

    /** 黄金基线里的一个测量项（arch01 或 hut）。 */
    private data class ArchGoldenEntry(
        val xoff: Double,
        val yoff: Double,
        val seed: Double,
        val hei: Double,
        val wid: Double,
        val per: Double,
        val tex: Double,
        val draws: Long,
        val contourCount: Int,
        val pointCount: Int,
        val structureDigest: Long,
        val rounded10: Long,
        val rounded100: Long,
    )

    /** 上游 `arch01` 的 `rot` **从不被使用**（见 [ShanShuiArch] 的文件头说明）⇒ 门禁里也传 0.7。 */
    private val archRotUnusedByUpstream = 0.7

    /** 黄金基线里的 arch01 / hut 测量项。 */
    private fun archGolden(key: String): ArchGoldenEntry {
        val o = archGoldenJson.getAsJsonObject(key)
        val call = o.getAsJsonObject("call")
        return ArchGoldenEntry(
            xoff = call.get("xoff").asDouble,
            yoff = call.get("yoff").asDouble,
            seed = if (call.has("archSeed")) call.get("archSeed").asDouble else 0.0,
            hei = call.get("hei").asDouble,
            wid = call.get("wid").asDouble,
            per = if (call.has("per")) call.get("per").asDouble else 0.0,
            tex = if (call.has("tex")) call.get("tex").asDouble else 0.0,
            draws = o.get("draws").asLong,
            contourCount = o.get("contourCount").asInt,
            pointCount = o.get("pointCount").asInt,
            structureDigest = hexToLong(o.get("structureDigest").asString),
            rounded10 = hexToLong(o.get("roundedDigest10").asString),
            rounded100 = hexToLong(o.get("roundedDigest100").asString),
        )
    }

    /** `0x...` 十六进制串 → Long（按位解释，不做符号转换）。 */
    private fun hexToLong(s: String): Long =
        java.lang.Long.parseUnsignedLong(s.removePrefix("0x"), 16)

    /** G-PORT-13 的黄金基线（懒加载 + 缓存）。 */
    private val archGoldenJson: com.google.gson.JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(ARCH_GOLDEN_RESOURCE)
            ?: throw AssertionError(
                "缺少上游黄金基线：app/src/test/resources$ARCH_GOLDEN_RESOURCE" +
                    "（由 `node scripts/e50_arch01_upstream_probe.js` 生成）",
            )
        stream.use {
            com.google.gson.JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject
        }
    }

    /** G-PORT-13 的「与上游一致」判据（正向与负向共用）。 */
    private fun assertArchMatchesUpstream(
        label: String,
        g: ArchGoldenEntry,
        contours: List<ShanShuiContour>,
    ) {
        assertEquals("$label 的轮廓条数必须与上游一致", g.contourCount, contours.size)
        assertEquals(
            "$label 的总点数必须与上游一致",
            g.pointCount,
            contours.sumOf { it.pts.size },
        )
        assertEquals(
            "$label 的结构摘要（填充/描边/线宽/点数）必须与上游逐位相等",
            g.structureDigest,
            archStructureDigest(contours),
        )
        assertEquals(
            "$label 的 0.1 网格坐标摘要（= 上游 poly() 的输出精度）必须与上游逐位相等",
            g.rounded10,
            archRoundedDigest(contours, 10),
        )
        assertEquals(
            "$label 的 0.01 网格坐标摘要必须与上游逐位相等",
            g.rounded100,
            archRoundedDigest(contours, 100),
        )
    }

    /** G-PORT-13 的「抽取次数」判据（正向与负向共用）。 */
    private fun assertArchDrawCount(label: String, expected: Long, actual: Long) {
        assertEquals(
            "$label 的 PRNG 抽取次数必须与上游一致（渔人的抽取也一并消耗 ⇒ 差值 = 0）",
            expected,
            actual,
        )
    }

    /** 颜色的摘要编码（与 node 探针同口径：`null` 一个标记，其余 5 个数）。 */
    private fun archColorSteps(c: ShanShuiColor?): List<Int> =
        if (c == null) {
            listOf(0xFFFFFFFF.toInt())
        } else {
            listOf(
                0x00000000,
                c.r, c.g, c.b,
                java.lang.Float.floatToRawIntBits(c.a.toFloat()),
            )
        }

    /** 结构摘要：逐条轮廓的 填充 / 描边 / 线宽 / 点数（**不含坐标**）。 */
    private fun archStructureDigest(contours: List<ShanShuiContour>): Long {
        var h = FNV_OFFSET
        for (c in contours) {
            for (v in archColorSteps(c.fill)) h = fnvStep(h, v)
            for (v in archColorSteps(c.stroke)) h = fnvStep(h, v)
            h = fnvStep(h, java.lang.Float.floatToRawIntBits(c.width.toFloat()))
            h = fnvStep(h, c.pts.size)
        }
        return h
    }

    /** 量化网格摘要：`Math.round(v * scale)` 后的整数坐标。 */
    private fun archRoundedDigest(contours: List<ShanShuiContour>, scale: Int): Long {
        var h = FNV_OFFSET
        for (c in contours) {
            h = fnvStep(h, c.pts.size)
            for (p in c.pts) {
                h = fnvStep(h, Math.round(p[0].toDouble() * scale).toInt())
                h = fnvStep(h, Math.round(p[1].toDouble() * scale).toInt())
            }
        }
        return h
    }

    companion object {
        /** 黄金基线的资源路径（由 `scripts/qianli_upstream_ref.js` 生成）。 */
        private const val GOLDEN_RESOURCE = "/qianli/shan_shui_upstream_golden.json"

        /** G-PORT-13 的黄金基线资源路径（由 `scripts/e50_arch01_upstream_probe.js` 生成）。 */
        private const val ARCH_GOLDEN_RESOURCE = "/qianli/shan_shui_arch01_golden.json"

        /** FNV-1a 64 的 offset（与 node 探针同值）。 */
        private const val FNV_OFFSET = -3750763034362895579L
    }
}
