package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiContour
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
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
 * 目的：真机截图暴露「山体上下颠倒 / 分成两行 / 中间多一条波纹」之后，
 * ⛔ 不能靠读代码猜画面 —— 本测试把渲染器的**真实**层带映射与**真实** chunk 几何
 * 画成 PNG，让人（和后续会话）能直接看图核对构图。
 *
 * ## 两条纪律
 *
 * ① **层带与 α 一律读渲染器的门禁缝**（`bandForGate` / `farAlphaRangeForGate` …）
 *    ⇒ 预览永远反映生产常量，⛔ 不许在本文件里硬编码一份 ⇒ 不会与生产分叉。
 * ② **必须带各层的 α**（生产 `computeAlphas` 的区间中值）⇒ 否则远景带会被画成
 *    100% 不透明，比真机重得多，会把构图问题看错。
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

    /** 逐层出图 —— 用来定位「哪一层画出了那排锯齿」。 */
    @Test
    fun renderPerLayerPreviews() {
        val renderer = QianliScrollRenderer()
        for (spec in layers(renderer)) {
            val out = renderLayer(renderer, spec)
            println("[E50 逐层预览] ${spec.name} = ${out.absolutePath}（${out.length() / 1024} KB）")
            assertTrue("${spec.name} 层预览必须已写出", out.exists() && out.length() > 1_000)
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
            for (spec in layers(renderer)) {
                val map = renderer.bandMapForGate(spec.id, H)
                val top = (spec.y0 * H).toFloat()
                for (chunk in renderer.gateChunksForGate(spec.id)) {
                    for (c in chunk.contours) {
                        drawContour(canvas, c, chunk.baseY, map, fixed, top, spec.alphaK)
                    }
                }
            }
        }
        val out = File(repoRoot(), "output/${if (fixed) "e50_preview_fixed" else "e50_preview_before"}.png")
        canvas.writePng(out)
        return out
    }

    private fun renderLayer(renderer: QianliScrollRenderer, spec: BandSpec): File {
        val canvas = PngCanvas(W, H)
        canvas.fill(0xFF4C5A5E.toInt())
        withSeed {
            val map = renderer.bandMapForGate(spec.id, H)
            val top = (spec.y0 * H).toFloat()
            for (chunk in renderer.gateChunksForGate(spec.id)) {
                for (c in chunk.contours) {
                    drawContour(canvas, c, chunk.baseY, map, true, top, spec.alphaK)
                }
            }
        }
        val out = File(repoRoot(), "output/e50_layer_${spec.name}.png")
        canvas.writePng(out)
        return out
    }

    private fun renderComposite(renderer: QianliScrollRenderer, v: Compose): File {
        val canvas = PngCanvas(W, H)
        canvas.fill(0xFF4C5A5E.toInt())
        withSeed {
            for (spec in compositeLayers(renderer, v)) {
                val map = spec.buildMap(H)
                val top = (spec.y0 * H).toFloat()
                for (chunk in renderer.gateChunksForGate(spec.id)) {
                    for (c in chunk.contours) {
                        drawContour(canvas, c, chunk.baseY, map, true, top, spec.alphaK)
                    }
                }
            }
        }
        val out = File(repoRoot(), "output/e50_compose_${v.name}.png")
        canvas.writePng(out)
        return out
    }

    /** 生产现状的四层（层带与 α 全部读渲染器门禁缝）。 */
    private fun layers(renderer: QianliScrollRenderer): List<BandSpec> {
        val far = renderer.bandForGate(QianliScrollRenderer.GateLayer.FAR)
        val mid = renderer.bandForGate(QianliScrollRenderer.GateLayer.MID)
        val water = renderer.bandForGate(QianliScrollRenderer.GateLayer.WATER)
        val near = renderer.bandForGate(QianliScrollRenderer.GateLayer.NEAR)
        val farA = renderer.farAlphaRangeForGate()
        val waterA = renderer.waterAlphaRangeForGate()
        return listOf(
            BandSpec(QianliScrollRenderer.GateLayer.FAR, "far", far.first, far.second, 255.0, 0.20, 0.47, 0.0, 0.0, mid(farA)),
            BandSpec(QianliScrollRenderer.GateLayer.MID, "mid", mid.first, mid.second, 540.0, 0.12, 0.643, 0.0, 0.0, 1.0f),
            BandSpec(QianliScrollRenderer.GateLayer.WATER, "water", water.first, water.second, 0.0, 0.0, 0.0, 80.0, 20.0, mid(waterA)),
            BandSpec(QianliScrollRenderer.GateLayer.NEAR, "near", near.first, near.second, 650.0, 0.15, 1.31, 0.0, 0.0, 1.0f),
        )
    }

    /** 变体只动远景带；其余三层与现状一致。 */
    private fun compositeLayers(renderer: QianliScrollRenderer, v: Compose): List<BandSpec> {
        val base = layers(renderer)
        val out = ArrayList<BandSpec>()
        for (spec in base) {
            if (spec.name == "far") {
                if (!v.farOn) continue
                out.add(spec.copy(y0 = v.farY0, y1 = v.farY1, alphaK = spec.alphaK * v.farAlphaK))
            } else {
                out.add(spec)
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
    ) {
        if (c.pts.isEmpty()) return
        val flat = FloatArray(c.pts.size * 2)
        for (i in c.pts.indices) {
            val pt = c.pts[i]
            flat[i * 2] = pt[0]
            flat[i * 2 + 1] = bandY(map, chunkBaseY, pt[1].toDouble(), fixed) + bandTop
        }
        c.fill?.let { canvas.fillPoly(flat, argb(it, alphaK)) }
        c.stroke?.let { canvas.strokePoly(flat, argb(it, alphaK), 1f) }
    }

    /**
     * 层带映射。
     *
     * - `fixed = true`：走渲染器的**真实**实现（高度项为减）。
     * - `fixed = false`：复现修复前的实现（高度项为加）⇒ 用来对照真机截图。
     */
    private fun bandY(
        map: QianliScrollRenderer.BandMap,
        chunkBaseY: Double,
        worldY: Double,
        fixed: Boolean,
    ): Float {
        if (map.waterSpan > 0.0) {
            val t = (worldY - chunkBaseY + map.waterOffset) / map.waterSpan
            return (t * map.bandHeightPx).toFloat()
        }
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

    /** 一层的层带描述（层号 + 带顶/带底 + 映射常量 + 整层 α 系数）。 */
    private data class BandSpec(
        val id: Int,
        val name: String,
        val y0: Float,
        val y1: Float,
        val refBaseY: Double,
        val baseSpread: Double,
        val heightScale: Double,
        val waterSpan: Double,
        val waterOffset: Double,
        /** 整层 α 系数（= 生产里 `computeAlphas` 的中值；⛔ 不填 1.0 会把远景带画得过重）。 */
        val alphaK: Float,
    ) {
        fun buildMap(h: Int): QianliScrollRenderer.BandMap = QianliScrollRenderer.BandMap(
            maxOf(1f, (y1 - y0) * h),
            refBaseY,
            baseSpread,
            heightScale,
            waterSpan,
            waterOffset,
        )
    }

    private companion object {
        const val W = 1920
        const val H = 1080
    }
}
