package com.nasmusic.tv.visualizer.qianli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.visualizer.qianli.port.SHANSHUI_TAG_ARCH_TEXT
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiArch
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * G-PORT-14 —— E50「千里江山」`Arch.arch02` / `arch03` / `arch04`（多层楼阁 / 塔 / 拱桥）
 * 与上游**逐位**对账。
 *
 * 移植来源：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。
 *
 * ## 口径
 * - 黄金基线：`app/src/test/resources/qianli/shan_shui_arch02_golden.json`
 *   （由 `node scripts/e50_arch02_upstream_probe.js` 实跑上游原始 JS 生成；
 *   `--check` 可校验是否漂移）。⛔ **期望值不在测试源码里**。
 * - PRNG 种子：黄金基线里逐条记录 `prngSeed`（绝大多数夹具 = **157**；
 *   `arch02Pizza` 夹具用 **12**，好让上游的「Pizza Hut」题字**真的触发**）。
 * - perlin 表用 LCG 种子 **20250101.0**（= [ShanShuiNoise.noiseSeed]）
 *   ⇒ 与上游逐字一致、**不消耗 PRNG**。
 *
 * ## 断言什么
 * | 量 | 口径 |
 * |---|---|
 * | 轮廓条数 | 上游 `poly()` 发射次数（+ `<text>` 题字算 1 条） |
 * | 总点数 | 逐条轮廓的点数之和 |
 * | structureDigest | 逐条轮廓的 填充 / 描边 / 线宽 / 点数（**不含坐标**） |
 * | roundedDigest10 | `Math.round(v*10)` 后的整数坐标（**0.1 网格 = 上游 `poly()` 的输出精度**） |
 * | roundedDigest100 | `Math.round(v*100)` 后的整数坐标（0.01 网格） |
 * | PRNG 抽取次数 | 上游 `Math.random()` 的实际调用次数 |
 *
 * ⚠️ **坐标不能逐位比 Float32**：V8 与 JVM 的 `Math.cos/sin/atan2` 最多差 1 ULP
 *   ⇒ 量化成 Float32 后偶尔差 1 个 ULP ⇒ 改比量化网格摘要（与 G-PORT-13 同款做法）。
 *
 * ## 负向自证
 * 见 [gPort14_negative_*]：① 误把 arch03 当 arch02；② 移植前的旧简化实现；
 * ③ 抽取次数不符；④ 题字轮廓被丢掉。
 */
class ShanShuiArch23GateTest {

    // ── 夹具常量 ──────────────────────────────────────────────

    /** perlin 表的钉扎种子（Double）—— 只影响噪声表内容，不消耗 PRNG。 */
    private val noiseSeedValue = 20250101.0

    /** 黄金基线里的一个测量项。 */
    private data class Fixture(
        val key: String,
        val kind: String,
        val prngSeed: Int,
        val xoff: Double,
        val yoff: Double,
        val seed: Double,
        val hei: Double,
        val wid: Double,
        val rot: Double,
        val per: Double,
        val sto: Double,
        val sty: Double,
        val rai: Boolean,
        val tra: Boolean,
        val bot: Boolean,
        val wei: Double,
        val seg: Int,
        val fro: Boolean,
        val cor: Double,
        val sid: Int,
        val pla: Int,
        val draws: Long,
        val contourCount: Int,
        val textCount: Int,
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
                    "（由 `node scripts/e50_arch02_upstream_probe.js` 生成）",
            )
        stream.use { JsonParser.parseReader(it.reader(Charsets.UTF_8)).asJsonObject }
    }

    /** 黄金基线里的全部夹具（顺序 = 基线文件里的顺序）。 */
    private fun fixtures(): List<Fixture> {
        val out = ArrayList<Fixture>()
        for (key in golden.keySet()) {
            if (key == "meta") continue
            val o = golden.getAsJsonObject(key)
            val c = o.getAsJsonObject("call")
            out.add(
                Fixture(
                    key = key,
                    kind = o.get("kind").asString,
                    prngSeed = c.get("prngSeed").asInt,
                    xoff = c.get("xoff").asDouble,
                    yoff = c.get("yoff").asDouble,
                    // ⚠️ **必须容错**：不同夹具的 `call` 字段集不同 ——
                    //   `box`/`roof`/`pagroof` 没有 `seed`，`box`/`rail`/`roof`/`pagroof` 没有 `sto`。
                    //   ⛔ 无条件读会拿到 null ⇒ `NullPointerException`（2026-11-06 真踩了）。
                    //   缺省值取**上游的缺省值**（`index.html`：`seed` 缺省 0）。
                    seed = if (c.has("seed")) c.get("seed").asDouble else 0.0,
                    hei = if (c.has("hei")) c.get("hei").asDouble else 0.0,
                    wid = if (c.has("wid")) c.get("wid").asDouble else 0.0,
                    rot = if (c.has("rot")) c.get("rot").asDouble else 0.0,
                    per = if (c.has("per")) c.get("per").asDouble else 0.0,
                    sto = if (c.has("sto")) c.get("sto").asDouble else 0.0,
                    sty = if (c.has("sty")) c.get("sty").asDouble else 1.0,
                    rai = if (c.has("rai")) c.get("rai").asBoolean else false,
                    tra = if (c.has("tra")) c.get("tra").asBoolean else true,
                    bot = if (c.has("bot")) c.get("bot").asBoolean else true,
                    wei = if (c.has("wei")) c.get("wei").asDouble else 3.0,
                    seg = if (c.has("seg")) c.get("seg").asInt else 4,
                    fro = if (c.has("fro")) c.get("fro").asBoolean else true,
                    cor = if (c.has("cor")) c.get("cor").asDouble else 5.0,
                    sid = if (c.has("sid")) c.get("sid").asInt else 4,
                    pla = if (c.has("pla")) c.get("pla").asInt else 0,
                    draws = o.get("draws").asLong,
                    contourCount = o.get("contourCount").asInt,
                    textCount = o.get("textCount").asInt,
                    pointCount = o.get("pointCount").asInt,
                    structureDigest = hexToLong(o.get("structureDigest").asString),
                    rounded10 = hexToLong(o.get("roundedDigest10").asString),
                    rounded100 = hexToLong(o.get("roundedDigest100").asString),
                )
            )
        }
        return out
    }

    /** `0x...` 十六进制串 → Long（按位解释，不做符号转换）。 */
    private fun hexToLong(s: String): Long =
        java.lang.Long.parseUnsignedLong(s.removePrefix("0x"), 16)

    // ── 公共夹具动作 ──────────────────────────────────────────

    /**
     * 复位 port 包的全局状态。
     *
     * ① [ShanShuiPrng.global] 的随机流：按夹具记录的 `prngSeed` 重新 seed。
     * ② [ShanShuiNoise] 的 perlin 表：用 [ShanShuiNoise.noiseSeed] 钉死
     *    （它用固定 LCG 填表，**不消耗 PRNG** ⇒ 表的内容与 PRNG 流解耦 ⇒ 可复现）。
     * ③ [ShanShuiPlanner] 的全局 planmtx。
     */
    private fun resetPortGlobals(prngSeed: Int) {
        ShanShuiPrng.global.seed(prngSeed)
        ShanShuiNoise.noiseSeed(noiseSeedValue)
        ShanShuiPlanner.resetPlanmtx()
    }

    /** 按夹具的 `kind` 调对应的 Kotlin 入口（与上游一一对应）。 */
    private fun callKotlin(f: Fixture): List<ShanShuiContour> = when (f.kind) {
        "arch02" -> ShanShuiArch.kind2(
            f.xoff, f.yoff, f.seed, f.hei, f.wid, f.rot, f.per, f.sto, f.sty, f.rai,
        )
        "arch03" -> ShanShuiArch.kind3(
            f.xoff, f.yoff, f.seed, f.hei, f.wid, f.rot, f.per, f.sto,
        )
        "arch04" -> ShanShuiArch.kind4(
            f.xoff, f.yoff, f.seed, f.hei, f.wid, f.rot, f.per, f.sto,
        )
        "box" -> ShanShuiArch.box(
            f.xoff, f.yoff, f.hei, f.wid, f.rot, f.per,
            tra = f.tra, bot = f.bot, wei = f.wei,
            dec = { a ->
                ShanShuiArch.deco(
                    f.sty.toInt(), a,
                    ShanShuiArch.decoHsp(f.sty.toInt()),
                    ShanShuiArch.decoVsp(f.sty.toInt()),
                )
            },
        )
        "rail" -> ShanShuiArch.rail(
            f.xoff, f.yoff, f.seed, f.hei, f.wid, f.rot, f.per, f.seg, f.wei,
            tra = f.tra, fro = f.fro,
        )
        "roof" -> ShanShuiArch.roof(
            f.xoff, f.yoff, f.hei, f.wid, f.rot, f.per, f.cor, f.wei,
            pla = if (f.pla == 1) 1 to "Pizza Hut" else null,
        )
        "pagroof" -> ShanShuiArch.pagroof(
            f.xoff, f.yoff, f.hei, f.wid, f.rot, f.per, f.cor, f.sid, f.wei,
        )
        else -> throw AssertionError("未知的夹具类型：${f.kind}")
    }

    // ══════════════════════ G-PORT-14 正向 ══════════════════════

    /**
     * G-PORT-14：每个夹具的**轮廓条数 / 总点数 / 结构摘要 / 0.1 网格摘要 / 0.01 网格摘要 /
     * PRNG 抽取次数**都必须与上游**逐位相等**。
     */
    @Test
    fun gPort14_arch23GeometryMatchesUpstream() {
        val fs = fixtures()
        assertTrue("黄金基线里必须有夹具", fs.isNotEmpty())
        for (f in fs) {
            resetPortGlobals(f.prngSeed)
            val before = ShanShuiPrng.global.draws
            val contours = callKotlin(f)
            val draws = ShanShuiPrng.global.draws - before
            assertArchMatchesUpstream(f, contours)
            assertArchDrawCount(f, draws)
        }
    }

    /**
     * G-PORT-14（题字）：上游 `roof` 的 `<text>`（内容「Pizza Hut」）在 port 里表现为
     * 一条带 [SHANSHUI_TAG_ARCH_TEXT] 标记的轮廓 ⇒ **条数**与**位置**必须与上游一致。
     *
     * ⚠️ 题字轮廓是上游 `<text>` 元素在 port 轮廓模型里的**唯一表达**
     *   （见 `SHANSHUI_TAG_ARCH_TEXT` 的 KDoc）⇒ 它的**条数**必须等于上游的 `<text>` 数，
     *   且必须**落在同一条轮廓的位置上**（上游 `<text>` 在 `roof()` 的末尾 ⇒
     *   在该层的最后一条）。
     */
    @Test
    fun gPort14_textContourMatchesUpstream() {
        for (f in fixtures()) {
            resetPortGlobals(f.prngSeed)
            val contours = callKotlin(f)
            val tagged = contours.filter { it.tag == SHANSHUI_TAG_ARCH_TEXT }
            assertEquals(
                "${f.key} 的题字轮廓条数必须与上游的 <text> 数一致",
                f.textCount,
                tagged.size,
            )
            if (f.textCount > 0) {
                val last = contours[contours.size - 1]
                assertEquals(
                    "${f.key} 的题字必须落在该层的最后一条轮廓上（上游 roof() 的末尾）",
                    SHANSHUI_TAG_ARCH_TEXT,
                    last.tag,
                )
                assertEquals("${f.key} 的题字轮廓必须只有一个锚点", 1, last.pts.size)
                assertTrue(
                    "${f.key} 的题字必须有填充色（上游 fill:rgba(100,100,100,0.9)）",
                    last.fill != null,
                )
                assertTrue("${f.key} 的题字必须无描边（上游 <text> 无 stroke）", last.stroke == null)
            }
        }
    }

    // ══════════════════════ G-PORT-14 负向自证 ══════════════════════

    /**
     * G-PORT-14 负向自证①：**误把 arch03 当 arch02** ⇒ 摘要必须不等。
     *
     * ⚠️ 这条同时证明「arch02 / arch03 确实是两个不同的东西」——
     *   若两者实现被写成了同一个（复制粘贴漏改），这条必红。
     */
    @Test
    fun gPort14_negative_arch03IsNotArch02() {
        val arch02 = fixtures().first { it.key == "arch02" }
        val arch03 = fixtures().first { it.key == "arch03" }
        assertTrue(
            "arch02 与 arch03 的结构摘要必须不同（否则判据无牙）",
            arch02.structureDigest != arch03.structureDigest,
        )
        assertTrue(
            "arch02 与 arch03 的 0.1 网格摘要必须不同",
            arch02.rounded10 != arch03.rounded10,
        )
        // 把 arch03 的输出喂给 arch02 的断言 ⇒ 必须抛 AssertionError
        resetPortGlobals(arch03.prngSeed)
        val wrong = callKotlin(arch03)
        gateMustCatch("把 arch03 当 arch02 必须被判据咬住") {
            assertArchMatchesUpstream(arch02, wrong)
        }
    }

    /**
     * G-PORT-14 负向自证②：**移植前的旧简化实现**（一条闭合的半圆拱轮廓）必须判红。
     */
    @Test
    fun gPort14_negative_legacySimplifiedArchBreaksGate() {
        val f = fixtures().first { it.key == "arch02" }
        val legacy = listOf(
            ShanShuiContour(
                (0..16).map { i -> floatArrayOf(i * 10f, i * 3f - 40f) },
                ShanShuiColor(255, 255, 255, 1.0),
                ShanShuiColor.rgba(100, 100, 100, 0.4),
                1.5,
            ),
        )
        gateMustCatch("移植前的旧简化实现必须被判据咬住") {
            assertArchMatchesUpstream(f, legacy)
        }
    }

    /**
     * G-PORT-14 负向自证③：**抽取次数**不符（例如漏掉 `sto == 1` 时那次题字判定随机）
     * ⇒ 必须判红。
     */
    @Test
    fun gPort14_negative_wrongDrawCountBreaksGate() {
        val f = fixtures().first { it.key == "arch02Pizza" }
        gateMustCatch("抽取次数不符的实现必须被判据咬住") {
            assertArchDrawCount(f, f.draws - 1L)
        }
        gateMustCatch("抽取次数多抽一次也必须被判据咬住") {
            assertArchDrawCount(f, f.draws + 1L)
        }
    }

    /**
     * G-PORT-14 负向自证④：**题字轮廓被丢掉** ⇒ 必须判红。
     */
    @Test
    fun gPort14_negative_droppedTextContourBreaksGate() {
        val f = fixtures().first { it.key == "arch02Pizza" }
        resetPortGlobals(f.prngSeed)
        val good = callKotlin(f)
        val withoutText = good.filter { it.tag != SHANSHUI_TAG_ARCH_TEXT }
        gateMustCatch("丢掉题字轮廓的实现必须被判据咬住") {
            assertArchMatchesUpstream(f, withoutText)
        }
    }

    /**
     * G-PORT-14 负向自证⑤：**坐标被挪动**（0.5）⇒ 0.1 / 0.01 网格摘要必须判红。
     */
    @Test
    fun gPort14_negative_shiftedCoordinatesBreakGate() {
        val f = fixtures().first { it.key == "arch04" }
        resetPortGlobals(f.prngSeed)
        val good = callKotlin(f)
        val moved = good.mapIndexed { i, c ->
            if (i == 3) {
                ShanShuiContour(
                    c.pts.map { floatArrayOf(it[0] + 0.5f, it[1]) },
                    c.fill, c.stroke, c.width, c.tag,
                )
            } else {
                c
            }
        }
        gateMustCatch("坐标被挪动的实现必须被判据咬住") {
            assertArchMatchesUpstream(f, moved)
        }
    }

    // ══════════════════════ 基线未漂移（node 探针 --check） ══════════════════════

    /**
     * G-PORT-14（基线未漂移）：跑 `node scripts/e50_arch02_upstream_probe.js --check`
     * ⇒ 重新生成并与黄金基线**逐字**比对 ⇒ 漂移则退出码 1 ⇒ 本用例判红。
     *
     * ⚠️ **需要 node 与上游原文件**：CI 的 runner 上既没有 node 的显式 setup，
     *   也没有上游原文件（`D:/hxzhang/...` 是本机路径）⇒ 这条用
     *   [org.junit.Assume.assumeTrue] **跳过**（JVM 4 会报「skipped」，不是 failed）。
     *   本机上它**必须真的跑过**（见回报）。
     */
    @Test
    fun gPort14_probeBaselineNotDrifted() {
        val upstream = File(golden.getAsJsonObject("meta").get("upstream").asString)
        assumeTrue("上游原文件不在本机（CI）⇒ 跳过 node 探针检查", upstream.isFile)
        val probe = repoRoot().resolve("scripts/e50_arch02_upstream_probe.js")
        assumeTrue("探针脚本不存在 ⇒ 跳过", probe.isFile)
        val node = findNode()
        assumeTrue("本机没有 node ⇒ 跳过 node 探针检查", node != null)
        val proc = ProcessBuilder(
            node!!, probe.absolutePath, "--check", "--upstream=" + upstream.absolutePath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        assertEquals(
            "node 探针 --check 必须通过（黄金基线未漂移）：\n" + out,
            0,
            code,
        )
    }

    /** 仓库根目录（从当前工作目录往上找带 `settings.gradle.kts` 的那一层）。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根目录（settings.gradle.kts）")
    }

    /** 找 `node`（PATH 上，或本机常见的安装位置）。 */
    private fun findNode(): String? {
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        for (d in pathDirs) {
            if (d.isBlank()) continue
            val f = File(d, "node.exe")
            if (f.isFile) return f.absolutePath
            val f2 = File(d, "node")
            if (f2.isFile) return f2.absolutePath
        }
        for (p in listOf(
            "C:/Program Files/nodejs/node.exe",
            "C:/Program Files (x86)/nodejs/node.exe",
        )) {
            val f = File(p)
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    // ── 断言（正向与负向共用） ─────────────────────────────────

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

    /** G-PORT-14 的「与上游一致」判据（正向与负向共用）。 */
    private fun assertArchMatchesUpstream(f: Fixture, contours: List<ShanShuiContour>) {
        assertEquals("${f.key} 的轮廓条数必须与上游一致", f.contourCount, contours.size)
        assertEquals(
            "${f.key} 的总点数必须与上游一致",
            f.pointCount,
            contours.sumOf { it.pts.size },
        )
        assertEquals(
            "${f.key} 的结构摘要（填充/描边/线宽/点数）必须与上游逐位相等",
            f.structureDigest,
            structureDigest(contours),
        )
        assertEquals(
            "${f.key} 的 0.1 网格坐标摘要（= 上游 poly() 的输出精度）必须与上游逐位相等",
            f.rounded10,
            roundedDigest(contours, 10),
        )
        assertEquals(
            "${f.key} 的 0.01 网格坐标摘要必须与上游逐位相等",
            f.rounded100,
            roundedDigest(contours, 100),
        )
    }

    /** G-PORT-14 的「抽取次数」判据（正向与负向共用）。 */
    private fun assertArchDrawCount(f: Fixture, actual: Long) {
        assertEquals(
            "${f.key} 的 PRNG 抽取次数必须与上游一致（多抽/少抽都算错）",
            f.draws,
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
        /** G-PORT-14 的黄金基线资源路径（由 `scripts/e50_arch02_upstream_probe.js` 生成）。 */
        private const val GOLDEN_RESOURCE = "/qianli/shan_shui_arch02_golden.json"

        /** FNV-1a 64 的 offset（与 node 探针同值）。 */
        private const val FNV_OFFSET = -3750763034362895579L
    }
}
