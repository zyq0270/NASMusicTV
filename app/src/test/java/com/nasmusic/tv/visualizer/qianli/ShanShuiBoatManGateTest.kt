package com.nasmusic.tv.visualizer.qianli

import com.google.gson.JsonObject
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiBoat
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E50 移植层门禁（G-PORT-14）—— **扁舟 `Arch.boat01` + 人物 `Man.man` 与上游逐位一致**。
 *
 * 移植来源：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。
 *
 * ## 判据清单
 * | 判据 | 内容 |
 * |---|---|
 * | G-PORT-14a | `Arch.boat01`（上游 `:3209-3248`）的轮廓条数 / 总点数 / 结构摘要 / 0.1 网格摘要 / 0.01 网格摘要 / PRNG 抽取次数与上游逐位相等 |
 * | G-PORT-14b | `Man.man`（上游 `:3522-3664`）同上 |
 * | G-PORT-14c | `Man.hat01`（上游 `:3406-3448`）同上 |
 * | G-PORT-14d | `Man.man` 的「船上游人」变体（`hat02` + `stick01` + `boat01` 的 `len`）同上 |
 * | G-PORT-14e | `Man.hat02`（`:3450-3485`）/ `Man.stick01`（`:3487-3513`）同上 |
 * | G-PORT-14f | **负向自证**：坏值必须被判据咬住 |
 *
 * ## 期望值的来源（⛔ 不写在测试源码里）
 * 黄金基线 `app/src/test/resources/qianli/shan_shui_boat_man_golden.json`
 * 由 `node scripts/e50_boat_man_upstream_probe.js` **实跑上游原始 JS** 生成
 * （探针对上游只有两处最小改动：perlin 表的初始化 + 链式暴露五个函数；**行号全部保持**）。
 * `node scripts/e50_boat_man_upstream_probe.js --check` 可校验基线是否漂移。
 * ⇒ 本测试只**读**基线并逐位比对，**夹具参数也从基线里读**（与探针天然同步）。
 *
 * ## 为什么坐标用「量化网格摘要」而不是逐位比 Float32
 * V8 与 JVM 的 `Math.cos/sin/atan2` **最多差 1 ULP** ⇒ 同一个浮点式在两侧量化成
 * Float32 后偶尔差 1 个 ULP ⇒ 改比「`Math.round(v * scale)` 后的整数」：
 * - `scale = 10` ⇒ **0.1 网格 = 上游 `poly()` 自己的输出精度**（上游写进 SVG 的就是它）；
 * - `scale = 100` ⇒ 0.01 网格（更紧的指纹）。
 *
 * ⚠️ **两侧必须先量化到 Float32 再算网格** —— port 的 [ShanShuiPath.Point] 是
 *   `FloatArray`（Float32），所以 node 探针在**捕获点列时**就 `Math.fround()`。
 *   （若 node 侧用原始 Float64：当某坐标恰好落在 `xx.005` 边界附近时，Float32 量化
 *   会把它推到另一侧 ⇒ **假失配**。实测 `manBoat` / `boat01` 各有 1 个这样的点。）
 *   实测两侧的 Float32 值**逐位相同**（`man` / `manBoat` / `boat01` 共 2215 点，0 处失配）。
 *
 * ## 全局状态的钉扎
 * port 包有三个全局单例（[ShanShuiPrng.global] 的随机流、[ShanShuiNoise] 的 perlin 表、
 * [ShanShuiPlanner] 的全局 planmtx）⇒ 每条判据前必须先 [resetPortGlobals]。
 * ⚠️ [ShanShuiNoise.noiseSeed] 用 LCG 填表、**不消耗 PRNG** ⇒ 测量窗口内是干净的。
 */
class ShanShuiBoatManGateTest {

    // ── 夹具常量 ──────────────────────────────────────────────

    /** PRNG 种子（Int，⛔ 不是 Double —— `JSON.stringify(157.0)` 会得到 `"157.0"`）。 */
    private val portSeed = 157

    /** perlin 表的钉扎种子（Double）—— 只影响噪声表内容，不消耗 PRNG。 */
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

    // ══════════════════════ G-PORT-14a boat01 ══════════════════════

    /**
     * G-PORT-14a：上游 `Arch.boat01`（`:3209-3248`）必须与上游**逐位**一致。
     *
     * 扁舟 = **渔人**（`Man.man`，19 次抽取 / 15 条轮廓）+ **船身白底**（48 点）
     * + **船身描边**（95 点）⇒ 合计 **20 次抽取 / 17 条轮廓 / 802 点**（上游实跑）。
     */
    @Test
    fun gPort14a_boat01MatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = golden("boat01")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiBoat.boat01(
            callNum(g, "xoff"), callNum(g, "yoff"), callNum(g, "seed"),
            callNum(g, "len"), callNum(g, "sca"), callBool(g, "fli"),
        )
        val draws = ShanShuiPrng.global.draws - before
        assertMatchesUpstream("boat01", g, contours)
        assertDrawCount("boat01", g.draws, draws)
    }

    // ══════════════════════ G-PORT-14b man ══════════════════════

    /**
     * G-PORT-14b：上游 `Man.man`（`:3522-3664`）必须与上游**逐位**一致。
     *
     * 走**全部缺省**：`hat = Man.hat01`、`ite` = 空函数、`ang` = 含 4 次随机的那组。
     * ⇒ **18 次抽取 / 15 条轮廓 / 654 点**（上游实跑）。
     */
    @Test
    fun gPort14b_manMatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = golden("man")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiBoat.man(
            callNum(g, "xoff"), callNum(g, "yoff"),
            sca = callNum(g, "sca"), fli = callBool(g, "fli"),
        )
        val draws = ShanShuiPrng.global.draws - before
        assertMatchesUpstream("man", g, contours)
        assertDrawCount("man", g.draws, draws)
    }

    // ══════════════════════ G-PORT-14c hat01 ══════════════════════

    /**
     * G-PORT-14c：上游 `Man.hat01`（`:3406-3448`）必须与上游**逐位**一致。
     *
     * ⇒ **1 次抽取**（`:3411` 的 `seed` 是**死变量**，但仍消耗一次）/ **2 条轮廓** / **17 点**。
     */
    @Test
    fun gPort14c_hat01MatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = golden("hat01")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiBoat.hat01(
            callPoint(g, "p0"), callPoint(g, "p1"), callBool(g, "fli"),
        )
        val draws = ShanShuiPrng.global.draws - before
        assertMatchesUpstream("hat01", g, contours)
        assertDrawCount("hat01", g.draws, draws)
    }

    // ══════════════════════ G-PORT-14d manBoat ══════════════════════

    /**
     * G-PORT-14d：`Man.man` 的「船上游人」变体（上游 `boat01:3217-3223` 的那次调用）。
     *
     * `ite = Man.stick01`、`hat = Man.hat02`、`sca = 0.5`、
     * `len = [0,30,20,30,10,30,30,30,30]`（第 5 项 = **10**）。
     * ⇒ **19 次抽取 / 15 条轮廓 / 659 点**（上游实跑）。
     */
    @Test
    fun gPort14d_manBoatVariantMatchesUpstreamBitwise() {
        resetPortGlobals()
        val g = golden("manBoat")
        val before = ShanShuiPrng.global.draws
        val contours = ShanShuiBoat.man(
            callNum(g, "xoff"), callNum(g, "yoff"),
            sca = callNum(g, "sca"),
            hat = ShanShuiBoat.Hat.HAT02,
            ite = ShanShuiBoat.Ite.STICK01,
            fli = callBool(g, "fli"),
            len = doubleArrayOf(0.0, 30.0, 20.0, 30.0, 10.0, 30.0, 30.0, 30.0, 30.0),
        )
        val draws = ShanShuiPrng.global.draws - before
        assertMatchesUpstream("manBoat", g, contours)
        assertDrawCount("manBoat", g.draws, draws)
    }

    // ══════════════════════ G-PORT-14e hat02 / stick01 ══════════════════════

    /** G-PORT-14e：`Man.hat02`（`:3450-3485`）与 `Man.stick01`（`:3487-3513`）。 */
    @Test
    fun gPort14e_hat02AndStick01MatchUpstreamBitwise() {
        resetPortGlobals()
        val gh = golden("hat02")
        val beforeH = ShanShuiPrng.global.draws
        val hatContours = ShanShuiBoat.hat02(
            callPoint(gh, "p0"), callPoint(gh, "p1"), callBool(gh, "fli"),
        )
        val hatDraws = ShanShuiPrng.global.draws - beforeH
        assertMatchesUpstream("hat02", gh, hatContours)
        assertDrawCount("hat02", gh.draws, hatDraws)

        resetPortGlobals()
        val gs = golden("stick01")
        val beforeS = ShanShuiPrng.global.draws
        val stickContours = ShanShuiBoat.stick01(
            callPoint(gs, "p0"), callPoint(gs, "p1"), callBool(gs, "fli"),
        )
        val stickDraws = ShanShuiPrng.global.draws - beforeS
        assertMatchesUpstream("stick01", gs, stickContours)
        assertDrawCount("stick01", gs.draws, stickDraws)
    }

    // ══════════════════════ G-PORT-14f 负向自证 ══════════════════════

    /**
     * G-PORT-14f 负向自证：把**故意坏值**喂给正向用例用的**同一份断言** ⇒ 必须抛 AssertionError。
     *
     * 覆盖 6 种坏值：
     * ① **移植前的口径**（人物一条轮廓都不画）⇒ 条数 / 点数 / 三道摘要都要红；
     * ② 坐标被挪动 0.5 ⇒ 坐标摘要要红；
     * ③ 少一条轮廓 ⇒ 条数要红；
     * ④ 白底被改成透明 ⇒ 结构摘要要红；
     * ⑤ 抽取次数少 1 次（漏掉 `ang` 的那 4 次随机里的 1 次）⇒ 抽取次数要红；
     * ⑥ **「不抽 `hat01` 的 `seed`」**的变体 ⇒ 抽取次数要红
     *    （`seed` 是上游的**死变量**，但它**消耗一次抽取** ⇒ 少抽 ⇒ 随机流错位）。
     */
    @Test
    fun gPort14f_negative_brokenValuesBreakGate() {
        resetPortGlobals()
        val g = golden("man")

        // ① 移植前的口径：人物一条轮廓都不画
        val legacy = emptyList<ShanShuiContour>()
        gateMustCatch("没有人物轮廓的旧口径必须被判据咬住") {
            assertMatchesUpstream("man", g, legacy)
        }
        // ② 坐标被挪动 0.5
        resetPortGlobals()
        val good = ShanShuiBoat.man(
            callNum(g, "xoff"), callNum(g, "yoff"),
            sca = callNum(g, "sca"), fli = callBool(g, "fli"),
        )
        val moved = good.mapIndexed { i, c ->
            if (i == 7) {
                ShanShuiContour(
                    c.pts.map { floatArrayOf(it[0] + 0.5f, it[1]) }, c.fill, c.stroke, c.width,
                )
            } else {
                c
            }
        }
        gateMustCatch("坐标被挪动的实现必须被判据咬住") {
            assertMatchesUpstream("man", g, moved)
        }
        // ③ 少一条轮廓
        gateMustCatch("少一条轮廓的实现必须被判据咬住") {
            assertMatchesUpstream("man", g, good.dropLast(1))
        }
        // ④ 白底被改成透明
        val recolored = good.mapIndexed { i, c ->
            if (i == 0) ShanShuiContour(c.pts, null, c.stroke, c.width) else c
        }
        gateMustCatch("白底被改色的实现必须被判据咬住") {
            assertMatchesUpstream("man", g, recolored)
        }
        // ⑤ 抽取次数少 1 次
        gateMustCatch("抽取次数不符的实现必须被判据咬住") {
            assertDrawCount("man", g.draws, g.draws - 1L)
        }
        // ⑥ 「不抽 hat01 的 seed」的变体（上游的 seed 是**死变量**，但**仍消耗一次抽取**）
        //    ⇒ 先证明**真实实现**确实抽了那一次（= 黄金基线的次数），
        //      再证明「少抽一次」的变体会被判据咬住。
        resetPortGlobals()
        val gh = golden("hat01")
        val beforeHat = ShanShuiPrng.global.draws
        ShanShuiBoat.hat01(
            callPoint(gh, "p0"), callPoint(gh, "p1"), callBool(gh, "fli"),
        )
        val realHatDraws = ShanShuiPrng.global.draws - beforeHat
        assertEquals(
            "hat01 必须消耗上游的那一次抽取（`seed` 是死变量，但上游仍抽了一次）",
            gh.draws,
            realHatDraws,
        )
        gateMustCatch("少抽 hat01 的 seed 必须被判据咬住") {
            assertDrawCount("hat01", gh.draws, gh.draws - 1L)
        }
    }

    // ── 黄金基线读取 ──────────────────────────────────────────

    /** 黄金基线里的一个测量项（含 `call` 里的夹具参数）。 */
    private data class GoldenEntry(
        val key: String,
        val call: JsonObject,
        val draws: Long,
        val contourCount: Int,
        val pointCount: Int,
        val structureDigest: Long,
        val rounded10: Long,
        val rounded100: Long,
    )

    /** G-PORT-14 的黄金基线（懒加载 + 缓存）。 */
    private val goldenJson: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(GOLDEN_RESOURCE)
            ?: throw AssertionError(
                "缺少上游黄金基线：app/src/test/resources$GOLDEN_RESOURCE" +
                    "（由 `node scripts/e50_boat_man_upstream_probe.js` 生成）",
            )
        stream.use {
            com.google.gson.JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject
        }
    }

    /** 黄金基线里的一个测量项（含 `call` 里的夹具参数）。 */
    private fun golden(key: String): GoldenEntry {
        val o = goldenJson.getAsJsonObject(key)
        return GoldenEntry(
            key = key,
            call = o.getAsJsonObject("call"),
            draws = o.get("draws").asLong,
            contourCount = o.get("contourCount").asInt,
            pointCount = o.get("pointCount").asInt,
            structureDigest = hexToLong(o.get("structureDigest").asString),
            rounded10 = hexToLong(o.get("roundedDigest10").asString),
            rounded100 = hexToLong(o.get("roundedDigest100").asString),
        )
    }

    /** 夹具里的一个数值参数（从黄金基线的 `call` 里读 ⇒ 与探针天然同步）。 */
    private fun callNum(g: GoldenEntry, name: String): Double =
        g.call.get(name).asDouble

    /** 夹具里的一个布尔参数。 */
    private fun callBool(g: GoldenEntry, name: String): Boolean =
        g.call.get(name).asBoolean

    /** 夹具里的一个点（`[x, y]`）。 */
    private fun callPoint(g: GoldenEntry, name: String): DoubleArray {
        val arr = g.call.getAsJsonArray(name)
        return doubleArrayOf(arr[0].asDouble, arr[1].asDouble)
    }

    /** `0x...` 十六进制串 → Long（按位解释，不做符号转换）。 */
    private fun hexToLong(s: String): Long =
        java.lang.Long.parseUnsignedLong(s.removePrefix("0x"), 16)

    // ── 判据（正向与负向共用） ────────────────────────────────

    /** G-PORT-14 的「与上游一致」判据（正向与负向共用）。 */
    private fun assertMatchesUpstream(
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

    /** G-PORT-14 的「抽取次数」判据（正向与负向共用）。 */
    private fun assertDrawCount(label: String, expected: Long, actual: Long) {
        assertEquals(
            "$label 的 PRNG 抽取次数必须与上游一致" +
                "（`ang` 的 4 次 + 每块 cloth 的 3 次 + 头的 1 次 + 帽子的 1 次）",
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
        /** G-PORT-14 的黄金基线资源路径（由 `scripts/e50_boat_man_upstream_probe.js` 生成）。 */
        private const val GOLDEN_RESOURCE = "/qianli/shan_shui_boat_man_golden.json"

        /** FNV-1a 64 的 offset（与 node 探针同值）。 */
        private const val FNV_OFFSET = -3750763034362895579L
    }
}
