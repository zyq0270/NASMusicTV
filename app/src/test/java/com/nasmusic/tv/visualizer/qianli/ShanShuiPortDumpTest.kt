package com.nasmusic.tv.visualizer.qianli

import com.google.gson.JsonParser
import com.nasmusic.tv.visualizer.qianli.port.ShanShui
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiArch
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import org.junit.Test
import java.io.File

/**
 * **移植层轮廓导出**工具（**不是门禁**）。
 *
 * 目的：把移植层各夹具（arch02/03/04、tree04–08、boat01…）的轮廓**全量导出**成 JSON，
 * 好与上游 node 探针的 dump **逐点对账**（见 `scripts/e50_arch_diff.py`、
 * `scripts/e50_arch_round_diff.py`、`scripts/e50_arch_digest_check.py`）。
 *
 * ⛔ 本测试**不判定任何东西**（没有断言）——它是「测量工具」，与 `QianliScenePreviewTest` 同一定位。
 * ⇒ 真正的门禁在 `ShanShuiArch23GateTest` / `ShanShuiTree45678GateTest` / `ShanShuiBoatManGateTest`。
 *
 * 产物：`output/e50_arch02_port.json`（⚠️ 落在**模块目录** `app/output/` 下 —— Robolectric 的工作目录）。
 */
class ShanShuiPortDumpTest {

    private fun golden(): com.google.gson.JsonObject {
        val f = File("src/test/resources/qianli/shan_shui_arch02_golden.json")
        val alt = File("app/src/test/resources/qianli/shan_shui_arch02_golden.json")
        val target = if (f.isFile) f else alt
        return JsonParser.parseReader(target.reader(Charsets.UTF_8)).asJsonObject
    }

    private fun reset(prngSeed: Int) {
        ShanShuiPrng.global.seed(prngSeed)
        ShanShuiNoise.noiseSeed(20250101.0)
        ShanShuiPlanner.resetPlanmtx()
    }

    private fun callKotlin(kind: String, o: com.google.gson.JsonObject): List<ShanShuiContour> {
        val c = o.getAsJsonObject("call")
        fun d(k: String, dflt: Double) = if (c.has(k)) c.get(k).asDouble else dflt
        fun b(k: String, dflt: Boolean) = if (c.has(k)) c.get(k).asBoolean else dflt
        val xoff = d("xoff", 0.0)
        val yoff = d("yoff", 0.0)
        val seed = d("seed", 0.0)
        val hei = d("hei", 0.0)
        val wid = d("wid", 0.0)
        val rot = d("rot", 0.0)
        val per = d("per", 0.0)
        val sto = d("sto", 0.0)
        val sty = d("sty", 1.0)
        val rai = b("rai", false)
        val tra = b("tra", true)
        val bot = b("bot", true)
        val wei = d("wei", 3.0)
        val seg = if (c.has("seg")) c.get("seg").asInt else 4
        val fro = b("fro", true)
        val cor = d("cor", 5.0)
        val sid = if (c.has("sid")) c.get("sid").asInt else 4
        val pla = if (c.has("pla")) c.get("pla").asInt else 0
        return when (kind) {
            "arch02" -> ShanShuiArch.kind2(xoff, yoff, seed, hei, wid, rot, per, sto, sty, rai)
            "arch03" -> ShanShuiArch.kind3(xoff, yoff, seed, hei, wid, rot, per, sto)
            "arch04" -> ShanShuiArch.kind4(xoff, yoff, seed, hei, wid, rot, per, sto)
            "box" -> ShanShuiArch.box(
                xoff, yoff, hei, wid, rot, per,
                tra = tra, bot = bot, wei = wei,
                dec = { a ->
                    ShanShuiArch.deco(
                        sty.toInt(), a,
                        ShanShuiArch.decoHsp(sty.toInt()),
                        ShanShuiArch.decoVsp(sty.toInt()),
                    )
                },
            )
            "rail" -> ShanShuiArch.rail(
                xoff, yoff, seed, hei, wid, rot, per, seg, wei,
                tra = tra, fro = fro,
            )
            "roof" -> ShanShuiArch.roof(
                xoff, yoff, hei, wid, rot, per, cor, wei,
                pla = if (pla == 1) 1 to "Pizza Hut" else null,
            )
            "pagroof" -> ShanShuiArch.pagroof(xoff, yoff, hei, wid, rot, per, cor, sid, wei)
            else -> emptyList()
        }
    }

    @Test
    fun dumpPortContours() {
        val g = golden()
        val out = StringBuilder()
        out.append("{")
        var first = true
        for (key in g.keySet()) {
            if (key == "meta") continue
            val o = g.getAsJsonObject(key)
            val kind = o.get("kind").asString
            val c = o.getAsJsonObject("call")
            val prngSeed = c.get("prngSeed").asInt
            reset(prngSeed)
            val contours = callKotlin(kind, o)
            if (!first) out.append(",")
            first = false
            out.append("\"").append(key).append("\": [")
            contours.forEachIndexed { i, ct ->
                if (i > 0) out.append(",")
                out.append("{\"i\":").append(i)
                out.append(",\"fill\":").append(ct.fill?.let { "\"${it.r},${it.g},${it.b},${it.a}\"" } ?: "null")
                out.append(",\"stroke\":").append(ct.stroke?.let { "\"${it.r},${it.g},${it.b},${it.a}\"" } ?: "null")
                out.append(",\"wid\":").append(ct.width)
                out.append(",\"n\":").append(ct.pts.size)
                out.append(",\"pts\":[")
                ct.pts.forEachIndexed { j, p ->
                    if (j > 0) out.append(",")
                    out.append("[").append(p[0]).append(",").append(p[1]).append("]")
                }
                out.append("]")
                out.append("}")
            }
            out.append("]")
        }
        out.append("}")
        val target = File("output/e50_arch02_port.json")
        target.parentFile?.mkdirs()
        target.writeText(out.toString(), Charsets.UTF_8)
        println("[diag] port contours dumped -> " + target.absolutePath + " (" + out.length + " bytes)")
    }
}
