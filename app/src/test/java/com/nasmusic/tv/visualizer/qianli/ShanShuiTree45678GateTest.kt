package com.nasmusic.tv.visualizer.qianli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E50「千里江山」**tree04…tree08 移植层门禁** —— **纯 JVM**（port 包零 Android 依赖）。
 *
 * 移植来源：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。
 *
 * ## 判据清单（G-TREE-1 ~ G-TREE-4）
 *
 * | 判据 | 内容 | 阈值 |
 * |---|---|---|
 * | G-TREE-1 | tree04…tree08 的 **轮廓条数 / 总点数** 与上游逐条相等 | 逐位相等 |
 * | G-TREE-2 | tree04…tree08 的 **structureDigest**（填充/描边/线宽/点数）与上游逐位相等 | 逐位相等 |
 * | G-TREE-3 | tree04…tree08 的 **roundedDigest10 / roundedDigest100**（0.1 / 0.01 网格坐标摘要）与上游逐位相等 | 逐位相等 |
 * | G-TREE-4 | tree04…tree08 的 **PRNG 抽取次数**与上游逐位相等 | 逐位相等（差值 = 0） |
 * | G-TREE-5 | **负向自证** | 坏值必须被判据咬住；且 tree05 ≠ tree06（摘要必须不同） |
 *
 * ## 口径（与 `scripts/e50_tree45678_upstream_probe.js` 逐字一致）
 *
 * - 黄金基线：`app/src/test/resources/qianli/shan_shui_tree45678_golden.json`
 *   （由 `node scripts/e50_tree45678_upstream_probe.js` 实跑上游 `shan-shui-inf/index.html`
 *   的**原始 JS** 生成；`--check` 可校验是否漂移）。
 *   ⛔ **期望值不在测试源码里**（只读基线文件）。
 * - PRNG 种子 **157**（Int）；perlin 表用 LCG 种子 **20250101.0**
 *   ⇒ 与 [ShanShuiNoise.noiseSeed] 逐字一致、**不消耗 PRNG**。
 * - 五棵树都用**上游自己的默认参数**（`args = {}`）：
 *   tree04（hei=300,wid=6）/ tree05（hei=300,wid=5）/ tree06（hei=100,wid=6）/
 *   tree07（hei=60,wid=4,ben=√x·0.2）/ tree08（hei=80,wid=1），调用坐标 (0, 0)。
 * - 轮廓顺序 = **上游 SVG 的拼接序**（主干白底 → 主干描边 → 树皮 → 细枝），
 *   探针用 `poly()` 返回值里的唯一标记还原（见探针文件头）。
 *
 * ## 为什么坐标用「量化网格摘要」而不是逐位比 Float32
 *
 * V8 与 JVM 的 `Math.cos/sin/atan2` **最多差 1 ULP** ⇒ 同一个浮点式在两侧量化成
 * Float32 后偶尔差 1 个 ULP。⇒ 坐标改比「`Math.round(v * scale)` 后的整数」：
 * - `scale = 10` ⇒ **0.1 网格 = 上游 `poly()` 自己的输出精度**；
 * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
 *
 * ## 全局状态的钉扎
 *
 * port 包有三个全局单例（[ShanShuiPrng.global] 的随机流、[ShanShuiNoise] 的 perlin 表、
 * [ShanShuiPlanner] 的**全局 planmtx**）会被按调用次序消耗 ⇒ 每个用例前必须复位。
 */
class ShanShuiTree45678GateTest {

    // ── 夹具常量 ──────────────────────────────────────────────

    /** PRNG 种子（Int）——与上游 `Prng.seed(157)` 同口径。 */
    private val portSeed = 157

    /** perlin 表的钉扎种子（Double）——只影响噪声表内容，不消耗 PRNG。 */
    private val noiseSeedValue = 20250101.0

    // ── 公共夹具动作 ──────────────────────────────────────────

    /** 复位 port 包的全局状态（随机流 / perlin 表 / planmtx）。 */
    private fun resetPortGlobals() {
        ShanShuiPrng.global.seed(portSeed)
        ShanShuiNoise.noiseSeed(noiseSeedValue)
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

    // ══════════════════════ G-TREE-1 ~ G-TREE-4 ══════════════════════

    /**
     * G-TREE-1 ~ G-TREE-4：tree04…tree08 的轮廓条数 / 总点数 / 三种摘要 / PRNG 抽取次数
     * 必须与上游**逐位相等**。
     */
    @Test
    fun gTree45678_allFiveTreesMatchUpstream() {
        for (case in treeCases()) {
            resetPortGlobals()
            // 预热：确保 perlin 表已就绪（ShanShuiNoise.noiseSeed 已建表 ⇒ 不消耗 PRNG）
            ShanShuiNoise.noise(0.0)
            val before = ShanShuiPrng.global.draws
            val contours = case.call()
            val draws = ShanShuiPrng.global.draws - before
            val g = goldenEntry(case.key)
            assertTreeMatchesUpstream(case.key, g, contours)
            assertTreeDrawCount(case.key, g.draws, draws)
        }
    }

    /**
     * G-TREE-5：**负向自证**。
     *
     * ① tree05 与 tree06 的结构摘要**必须不同**（否则「五棵树其实是同一棵」也能过门禁）；
     * ② 把**故意改坏**的轮廓喂给正向用例用的**同一份断言** ⇒ 必须抛 AssertionError。
     */
    @Test
    fun gTree45678_negative_brokenValuesBreakGates() {
        // ① tree05 ≠ tree06
        resetPortGlobals()
        val c05 = ShanShuiTree.kind5(0.0, 0.0, 300.0, 5.0, ShanShuiColor.default, 0.5)
        resetPortGlobals()
        val c06 = ShanShuiTree.kind6(0.0, 0.0, 100.0, 6.0, ShanShuiColor.default, 0.5)
        assertNotEquals(
            "tree05 与 tree06 的结构摘要必须不同（否则门禁没有区分力）",
            structureDigest(c05),
            structureDigest(c06),
        )
        assertNotEquals(
            "tree05 与 tree06 的 0.01 网格摘要必须不同",
            roundedDigest(c06, 100),
            roundedDigest(c05, 100),
        )

        // ② 坏值：少一条轮廓 ⇒ 条数/摘要判据都要红
        resetPortGlobals()
        val good = ShanShuiTree.kind4(0.0, 0.0, 300.0, 6.0, ShanShuiColor.default, 0.5)
        val g = goldenEntry("tree04")
        gateMustCatch("少一条轮廓的实现必须被判据咬住") {
            assertTreeMatchesUpstream("tree04", g, good.dropLast(1))
        }
        // ③ 坏值：坐标被挪动 0.5 ⇒ 坐标摘要判据要红
        val moved = good.mapIndexed { i, c ->
            if (i == 3) {
                ShanShuiContour(
                    c.pts.map { floatArrayOf(it[0] + 0.5f, it[1]) },
                    c.fill, c.stroke, c.width,
                )
            } else {
                c
            }
        }
        gateMustCatch("坐标被挪动的实现必须被判据咬住") {
            assertTreeMatchesUpstream("tree04", g, moved)
        }
        // ④ 坏值：白底被改成透明 ⇒ 结构摘要判据要红
        val recolored = good.mapIndexed { i, c ->
            if (i == 0) ShanShuiContour(c.pts, null, c.stroke, c.width) else c
        }
        gateMustCatch("白底被改色的实现必须被判据咬住") {
            assertTreeMatchesUpstream("tree04", g, recolored)
        }
        // ⑤ 坏值：抽取次数少 1 次 ⇒ 抽取次数判据要红
        gateMustCatch("抽取次数不符的实现必须被判据咬住") {
            assertTreeDrawCount("tree04", g.draws, g.draws - 1L)
        }
    }

    /**
     * G-TREE-1 ~ G-TREE-4（**基线自身的漂移守门**）：黄金基线的口径元数据必须与本测试一致。
     *
     * ⛔ 期望值**不在测试源码里** ⇒ 这里只校验「口径」（种子 / 噪声种子 / 生成器），
     *    具体的期望数字全部来自基线文件。
     */
    @Test
    fun gTree45678_goldenBaselineMetaMatchesFixture() {
        val meta = golden.getAsJsonObject("meta")
        assertEquals(
            "黄金基线必须由 e50_tree45678_upstream_probe.js 生成",
            "scripts/e50_tree45678_upstream_probe.js",
            meta.get("generator").asString,
        )
        assertEquals(
            "黄金基线的 PRNG 种子必须与本测试一致",
            portSeed.toDouble(),
            meta.get("seed").asDouble,
            0.0,
        )
        assertEquals(
            "黄金基线的 perlin 表种子必须与本测试一致",
            noiseSeedValue,
            meta.get("noiseSeed").asDouble,
            0.0,
        )
    }

    // ── 夹具 ──────────────────────────────────────────────────

    /** 一棵树的用例（键名 + 调用）。 */
    private data class TreeCase(val key: String, val call: () -> List<ShanShuiContour>)

    /**
     * 五棵树的用例（参数 = **上游自己的默认值**，`args = {}`）。
     *
     * ⚠️ `kind7` 的 `ben` 缺省是 `function(x){ return Math.sqrt(x) * 0.2; }`（上游 `:1497-1499`）。
     */
    private fun treeCases(): List<TreeCase> = listOf(
        TreeCase("tree04") {
            ShanShuiTree.kind4(0.0, 0.0, 300.0, 6.0, ShanShuiColor.default, 0.5)
        },
        TreeCase("tree05") {
            ShanShuiTree.kind5(0.0, 0.0, 300.0, 5.0, ShanShuiColor.default, 0.5)
        },
        TreeCase("tree06") {
            ShanShuiTree.kind6(0.0, 0.0, 100.0, 6.0, ShanShuiColor.default, 0.5)
        },
        TreeCase("tree07") {
            ShanShuiTree.kind7(
                0.0, 0.0, 60.0, 4.0,
                { x -> Math.sqrt(x) * 0.2 },
                ShanShuiColor(100, 100, 100, 1.0),
                0.5,
            )
        },
        TreeCase("tree08") {
            ShanShuiTree.kind8(0.0, 0.0, 80.0, 1.0, ShanShuiColor.default, 0.5)
        },
    )

    // ── 黄金基线 ──────────────────────────────────────────────

    /** 黄金基线里的一个测量项。 */
    private data class GoldenEntry(
        val draws: Long,
        val contourCount: Int,
        val pointCount: Int,
        val structureDigest: Long,
        val rounded10: Long,
        val rounded100: Long,
    )

    /** 黄金基线（懒加载 + 缓存）。 */
    private val golden: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(GOLDEN_RESOURCE)
            ?: throw AssertionError(
                "缺少上游黄金基线：app/src/test/resources$GOLDEN_RESOURCE" +
                    "（由 `node scripts/e50_tree45678_upstream_probe.js` 生成）",
            )
        stream.use { JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject }
    }

    /** 黄金基线里某一棵树的测量项。 */
    private fun goldenEntry(key: String): GoldenEntry {
        val o = golden.getAsJsonObject(key)
        return GoldenEntry(
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

    // ── 判据（正向与负向共用） ─────────────────────────────────

    /** G-TREE-1 ~ G-TREE-3 的「与上游一致」判据（正向与负向共用）。 */
    private fun assertTreeMatchesUpstream(
        label: String,
        g: GoldenEntry,
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
            structureDigest(contours),
        )
        assertEquals(
            "$label 的 0.1 网格坐标摘要（= 上游 poly() 的输出精度）必须与上游逐位相等",
            g.rounded10,
            roundedDigest(contours, 10),
        )
        assertEquals(
            "$label 的 0.01 网格坐标摘要必须与上游逐位相等",
            g.rounded100,
            roundedDigest(contours, 100),
        )
    }

    /** G-TREE-4 的「抽取次数」判据（正向与负向共用）。 */
    private fun assertTreeDrawCount(label: String, expected: Long, actual: Long) {
        assertEquals(
            "$label 的 PRNG 抽取次数必须与上游一致",
            expected,
            actual,
        )
    }

    /** 颜色的摘要编码（与 node 探针同口径：`null` 一个标记，其余 5 个数）。 */
    private fun colorSteps(c: ShanShuiColor?): List<Int> =
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
    private fun structureDigest(contours: List<ShanShuiContour>): Long {
        var h = FNV_OFFSET
        for (c in contours) {
            for (v in colorSteps(c.fill)) h = fnvStep(h, v)
            for (v in colorSteps(c.stroke)) h = fnvStep(h, v)
            h = fnvStep(h, java.lang.Float.floatToRawIntBits(c.width.toFloat()))
            h = fnvStep(h, c.pts.size)
        }
        return h
    }

    /** 量化网格摘要：`Math.round(v * scale)` 后的整数坐标。 */
    private fun roundedDigest(contours: List<ShanShuiContour>, scale: Int): Long {
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

    private fun fnvStep(h: Long, v: Int): Long =
        (h xor (v.toLong() and 0xFFFFFFFFL)) * 1099511628211L

    companion object {
        /** 黄金基线的资源路径（由 `scripts/e50_tree45678_upstream_probe.js` 生成）。 */
        private const val GOLDEN_RESOURCE = "/qianli/shan_shui_tree45678_golden.json"

        /** FNV-1a 64 的 offset（与 node 探针同值）。 */
        private const val FNV_OFFSET = -3750763034362895579L
    }
}
