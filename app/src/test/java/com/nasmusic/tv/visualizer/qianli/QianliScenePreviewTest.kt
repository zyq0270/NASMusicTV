package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.SHANSHUI_TAG_BODY_BASE
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPath
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliScrollRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * E50 场景离线预览（**诊断用**，不是门禁）。
 *
 * 目的：真机截图暴露「山体上下颠倒 / 分成两行了 / 水纹奇怪 / 远山没颜色 /
 * 山脚白三角 / 半边山 / 底部黑条」之后，⛔ 不能靠读代码猜画面 ——
 * 本测试把渲染器的**真实**层带映射与**真实** chunk 几何画成 PNG，
 * 让人（和后续会话）能直接看图核对构图。
 *
 * ## 三条纪律
 *
 * ① **层带、映射、α 一律读渲染器的门禁缝**（`bandForGate` / `bandMapForGate` /
 *    `farAlphaRangeForGate` …）⇒ 预览永远反映生产常量，⛔ 不在本文件里硬编码一份。
 * ② **必须带各层的 α**（生产 `computeAlphas` 的区间中值）⇒ 否则远景带会被画成
 *    100% 不透明，比真机重得多，会把构图问题看错。
 * ③ **逐层出图** ⇒ 哪一层画出了什么，一眼可辨（不用在合成图里猜）。
 *
 * ## 为什么不用 AWT
 *
 * Android 单元测试的编译 classpath 上没有 `java.awt` / `javax.imageio`
 * ⇒ 光栅化与 PNG 编码走自带的 [PngCanvas]（纯 `java.base`）。
 *
 * ## 输出
 *
 * - `output/e50_preview_fixed.png` —— 修复后的映射（高度项为减）。
 * - `output/e50_preview_before.png` —— 修复前的映射（高度项为加）⇒ 复现真机截图。
 * - `output/e50_layer_{far,mid,water,near}.png` —— 逐层。
 * - `output/e50_compose_{A..F}.png` —— 构图变体（只改远景层带）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliScenePreviewTest {

    @Test
    fun renderBeforeAndAfterScenePreviews() {
        val renderer = QianliScrollRenderer()

        val fixed = renderScene(renderer, fixed = true)
        val before = renderScene(renderer, fixed = false)

        assertTrue("修复后预览必须已写出", fixed.exists() && fixed.length() > 5_000)
        assertTrue("修复前预览必须已写出", before.exists() && before.length() > 5_000)
        println("[E50 预览] 修复后 = ${fixed.absolutePath}（${fixed.length() / 1024} KB）")
        println("[E50 预览] 修复前 = ${before.absolutePath}（${before.length() / 1024} KB）")
    }

    /** 逐层出图 —— 用来定位「哪一层画出了什么」。 */
    @Test
    fun renderPerLayerPreviews() {
        val renderer = QianliScrollRenderer()
        for (plan in layers(renderer)) {
            val out = renderLayer(renderer, plan)
            println("[E50 逐层预览] ${plan.name} = ${out.absolutePath}（${out.length() / 1024} KB）")
            assertTrue("${plan.name} 层预览必须已写出", out.exists() && out.length() > 1_000)
        }
    }

    /** 构图变体 —— 改**远景层带**的位置 / 强度，其余层一律读渲染器常量。 */
    @Test
    fun renderCompositionVariants() {
        val renderer = QianliScrollRenderer()
        val variants = listOf(
            Compose("A", 0.42f, 0.58f, 1.0f, true),
            Compose("B", 0.30f, 0.44f, 1.0f, true),
            Compose("C", 0.36f, 0.52f, 1.0f, true),
            Compose("D", 0.24f, 0.38f, 1.0f, true),
            // E：去掉远景层（只剩「中景山峦 + 江水 + 近景坡石」三段）
            Compose("E", 0.42f, 0.58f, 1.0f, false),
            // F：远景带上移 + 压淡（当「远山雾影」，藏在山体后面）
            Compose("F", 0.34f, 0.50f, 0.45f, true),
        )
        for (v in variants) {
            val out = renderComposite(renderer, v)
            println(
                "[E50 构图变体] ${v.name}（远景带 ${v.farY0}–${v.farY1} α×${v.farAlphaK} " +
                    "${if (v.farOn) "开" else "关"}）= ${out.absolutePath}（${out.length() / 1024} KB）",
            )
            assertTrue("变体 ${v.name} 必须已写出", out.exists() && out.length() > 5_000)
        }
    }

    // ─────────────────────── 画 ───────────────────────

    private fun renderScene(renderer: QianliScrollRenderer, fixed: Boolean): File {
        val canvas = PngCanvas(W, H)
        canvas.fill(0xFF4C5A5E.toInt())
        withSeed {
            for (plan in layers(renderer)) {
                val top = (plan.top * H).toFloat()
                for (chunk in renderer.gateChunksForGate(plan.id)) {
                    val body = bodyFillOf(renderer, chunk.contours)
                    chunk.contours.forEachIndexed { i, c ->
                        drawContour(
                            canvas, c, chunk.baseY, plan.map, fixed, top, plan.alphaK,
                            if (i == body.first) body.second else null,
                        )
                    }
                }
            }
        }
        val out = File(repoRoot(), "output/${if (fixed) "e50_preview_fixed" else "e50_preview_before"}.png")
        canvas.writePng(out)
        return out
    }

    private fun renderLayer(renderer: QianliScrollRenderer, plan: LayerPlan): File {
        val canvas = PngCanvas(W, H)
        canvas.fill(0xFF4C5A5E.toInt())
        withSeed {
            val top = (plan.top * H).toFloat()
            for (chunk in renderer.gateChunksForGate(plan.id)) {
                val body = bodyFillOf(renderer, chunk.contours)
                chunk.contours.forEachIndexed { i, c ->
                    drawContour(
                        canvas, c, chunk.baseY, plan.map, true, top, plan.alphaK,
                        if (i == body.first) body.second else null,
                    )
                }
            }
        }
        val out = File(repoRoot(), "output/e50_layer_${plan.name}.png")
        canvas.writePng(out)
        return out
    }

    /**
     * 山体实色填充的**多边形**（与渲染器 [QianliScrollRenderer] 同款）：
     * 脊线 + 底缘折线（不规则）+ 向下延伸 [QianliScrollRenderer.bodyExtendWorldForGate]。
     *
     * ⛔ 为什么要在这里复刻：上游那条「脊线 + 远点」的三角形白底**在生产里已经不是可见色块**
     *   （渲染器改画山体自身轮廓）⇒ 预览若还画三角形 ⇒ 预览与生产分叉，
     *   而且会把「山脚一排白三角」当成构图问题 ⇒ 必须一起改。
     *
     * @return （山体白底的下标, 填充多边形）；没有底缘派生数据时多边形为 null（画原样）。
     */
    private fun bodyFillOf(
        renderer: QianliScrollRenderer,
        contours: List<ShanShuiContour>,
    ): Pair<Int, List<ShanShuiPath.Point>?> {
        val bodyIdx = renderer.bodyFillIndexForGate(contours)
        val baseIdx = contours.indexOfFirst { it.tag == SHANSHUI_TAG_BODY_BASE }
        if (baseIdx < 0 || bodyIdx < 0) return bodyIdx to null
        val ridge = contours[bodyIdx].pts.dropLast(1)     // 去掉上游那个远点 (0, reso[0]*4)
        val baseYs = contours[baseIdx].pts.map { it[1].toDouble() }
        if (ridge.isEmpty() || baseYs.isEmpty()) return bodyIdx to null
        val n = ridge.size
        val lower = ArrayList<ShanShuiPath.Point>(n)
        for (i in 0 until n) {
            val by = baseYs[(i * baseYs.size) / n] + renderer.bodyExtendWorldForGate()
            lower.add(floatArrayOf(ridge[i][0], by.toFloat()))
        }
        val poly = ArrayList<ShanShuiPath.Point>(n * 2).apply {
            addAll(ridge)
            for (i in lower.indices.reversed()) add(lower[i])
        }
        return bodyIdx to poly
    }

    private fun renderComposite(renderer: QianliScrollRenderer, v: Compose): File {
        val canvas = PngCanvas(W, H)
        canvas.fill(0xFF4C5A5E.toInt())
        withSeed {
            for (plan in compositeLayers(renderer, v)) {
                val top = (plan.top * H).toFloat()
                for (chunk in renderer.gateChunksForGate(plan.id)) {
                    val body = bodyFillOf(renderer, chunk.contours)
                    chunk.contours.forEachIndexed { i, c ->
                        drawContour(
                            canvas, c, chunk.baseY, plan.map, true, top, plan.alphaK,
                            if (i == body.first) body.second else null,
                        )
                    }
                }
            }
        }
        val out = File(repoRoot(), "output/e50_compose_${v.name}.png")
        canvas.writePng(out)
        return out
    }

    /**
     * 生产现状的四层（层带 / 映射 / α 全部读渲染器门禁缝）。
     *
     * ⭐ **顺序 = 上游的层次顺序**（上游 `:3900` 把水的排序键压成 `y − 10000` ⇒ 水画在最前 = 最靠后）：
     * 水 → 远景 → 中景 → 近景 ⇒ **山体白底盖住波线中段**（上游观感）。
     */
    private fun layers(renderer: QianliScrollRenderer): List<LayerPlan> {
        val farA = mid(renderer.farAlphaRangeForGate())
        val waterA = mid(renderer.waterAlphaRangeForGate())
        return listOf(
            plan(renderer, QianliScrollRenderer.GateLayer.WATER, "water", waterA),
            plan(renderer, QianliScrollRenderer.GateLayer.FAR, "far", farA),
            plan(renderer, QianliScrollRenderer.GateLayer.MID, "mid", 1.0f),
            plan(renderer, QianliScrollRenderer.GateLayer.NEAR, "near", 1.0f),
        )
    }

    private fun plan(
        renderer: QianliScrollRenderer,
        id: Int,
        name: String,
        alphaK: Float,
    ): LayerPlan {
        val band = renderer.bandForGate(id)
        return LayerPlan(id, name, band.first, renderer.bandMapForGate(id, H), alphaK)
    }

    /** 变体只动远景带；其余三层与现状一致（映射直接取渲染器那份）。 */
    private fun compositeLayers(renderer: QianliScrollRenderer, v: Compose): List<LayerPlan> {
        val out = ArrayList<LayerPlan>()
        for (p in layers(renderer)) {
            if (p.id == QianliScrollRenderer.GateLayer.FAR) {
                if (!v.farOn) continue
                out.add(
                    p.copy(
                        top = v.farY0,
                        map = p.map.withBand(v.farY0, v.farY1, H),
                        alphaK = p.alphaK * v.farAlphaK,
                    ),
                )
            } else {
                out.add(p)
            }
        }
        return out
    }

    private fun drawContour(
        canvas: PngCanvas,
        c: ShanShuiContour,
        chunkBaseY: Double,
        map: QianliScrollRenderer.BandMap,
        fixed: Boolean,
        bandTop: Float,
        alphaK: Float,
        bodyPoly: List<ShanShuiPath.Point>? = null,
    ) {
        if (c.pts.isEmpty()) return
        val src = bodyPoly ?: c.pts
        val flat = FloatArray(src.size * 2)
        for (i in src.indices) {
            val pt = src[i]
            flat[i * 2] = pt[0]
            flat[i * 2 + 1] = bandY(map, chunkBaseY, pt[1].toDouble(), fixed) + bandTop
        }
        c.fill?.let { canvas.fillPoly(flat, argb(it, alphaK)) }
        c.stroke?.let { canvas.strokePoly(flat, argb(it, alphaK), 1f) }
    }

    /**
     * 层带映射。
     *
     * - `fixed = true`：走渲染器的**真实**实现（高度项为减；水层与中景层共用同一张映射）。
     * - `fixed = false`：复现修复前的实现（高度项为加）⇒ 用来对照真机截图。
     */
    private fun bandY(
        map: QianliScrollRenderer.BandMap,
        chunkBaseY: Double,
        worldY: Double,
        fixed: Boolean,
    ): Float {
        if (fixed) return map.bandY(chunkBaseY, worldY)
        val base = map.bandHeightPx - (chunkBaseY - map.refBaseY) * map.baseSpread
        return (base + (chunkBaseY - worldY) * map.heightScale).toFloat()
    }

    private fun argb(c: ShanShuiColor, alphaK: Float = 1.0f): Int {
        val a = (c.a.coerceIn(0.0, 1.0) * alphaK.coerceIn(0.0f, 1.0f) * 255).toInt()
        return (a shl 24) or (c.r shl 16) or (c.g shl 8) or c.b
    }

    /** α 区间的中值（生产 `computeAlphas` 的中间态）。 */
    private fun mid(range: Pair<Double, Double>): Float =
        ((range.first + range.second) / 2.0).toFloat()

    /** 预热 perlin 表（懒初始化会消费 4096 个 rnd）⇒ 必须在复位种子**之前**。 */
    private fun withSeed(block: () -> Unit) {
        ShanShuiNoise.noise(0.0)
        val saved = ShanShuiPrng.global.s
        ShanShuiPrng.global.s = 1234.0
        try {
            block()
        } finally {
            ShanShuiPrng.global.s = saved
        }
    }

    /** 仓库根（从当前工作目录往上找 `app/src/main`）。 */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            if (File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir"))
    }

    private class Compose(
        val name: String,
        val farY0: Float,
        val farY1: Float,
        val farAlphaK: Float,
        val farOn: Boolean,
    )

    /** 一层的绘制计划（层号 + 带顶 + **渲染器那份映射** + 整层 α）。 */
    private data class LayerPlan(
        val id: Int,
        val name: String,
        val top: Float,
        val map: QianliScrollRenderer.BandMap,
        val alphaK: Float,
    )

    private companion object {
        const val W = 1920
        const val H = 1080
    }
}
