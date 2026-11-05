package com.nasmusic.tv.visualizer.qianli

import com.nasmusic.tv.visualizer.qianli.port.ShanShuiColor
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliUpstreamRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.math.max

/**
 * E50「忠于上游的绢白线描版」离线预览（**诊断用**，不是门禁）。
 *
 * ## 为什么自带一个软件光栅器
 *
 * ⛔ **本机（Windows）上 Robolectric 只有 LEGACY 图形模式可用** —— NATIVE 模式所需的
 * `nativeruntime-dist-compat` 不带 Windows 原生库（见 `QianliSealGlyphGateTest` 的记录）
 * ⇒ `Canvas.drawPath` / `drawBitmap` **不被光栅化** ⇒ Android `Canvas` 画出来的位图恒为全透明。
 *
 * ⇒ 预览与门禁都改用**软件光栅器**（[QianliUpstreamRaster]），
 * 但**消费的仍是渲染器产出的同一份绘制项**（`QianliUpstreamRenderer.DrawItem`）
 * ⇒ 变换、颜色、画家序与生产**逐字一致**，只有「谁来画」不同。
 *
 * ## 三条纪律
 *
 * ① **变换与绘制项一律读渲染器的门禁缝**（`gateTransformForGate` / `gateItemsForRange`）
 *    ⇒ 预览永远反映生产常量，⛔ 不在本文件里硬编码一份。
 * ② **不手抄上游数字**：纸底、缩放、纸色、块宽全部来自渲染器。
 * ③ **随机流要复位**（[resetPortGlobals]）⇒ 两次跑出来的图一样。
 *
 * ## 输出
 *
 * - `output/e50_upstream_mode.png` —— 整幅（1920×1080）：纸底 + 场景。
 * - `output/e50_upstream_layer.png` —— 单块（块宽 × 屏高）：同一段世界的一个块。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliUpstreamPreviewTest {

    @Test
    fun renderUpstreamModePreview() {
        val renderer = QianliUpstreamRenderer()
        resetPortGlobals()
        renderer.gatePrepareForGate(W, H)

        val transform = renderer.gateTransformForGate()
        val scale = transform.scale
        // 可见世界宽 = 屏宽 / scale（与上游 viewBox 的可见宽同口径）。
        val worldW = W / scale

        // 镜头在世界原点（scroll = 0）⇒ 绘制项的原点取 `cursx × scale` = 0 ⇒ 坐标即屏幕坐标。
        renderer.gateSetScrollForGate(0.0)
        val items = renderer.gateItemsForRange(0.0, worldW, 0.0)

        val raster = QianliUpstreamRaster(W, H)
        raster.fillTiled(renderer.gatePaperPixelsForGate()!!, PAPER_RESO, PAPER_RESO)
        raster.drawItems(items)

        val out = File(repoRoot(), "output/e50_upstream_mode.png")
        raster.writePng(out)

        val stats = raster.stats()
        println("[E50 上游预览] 整幅 = ${out.absolutePath}（${out.length() / 1024} KB）")
        println("[E50 上游预览] scale = $scale，可见世界 = ${fmt(worldW)} × ${fmt(H / scale)} 世界单位")
        println("[E50 上游预览] 绘制项 = ${items.size} 条（其中白填充⇒纸色 ${items.count { it.paperFilled }} 条）")
        println("[E50 上游预览] 非背景像素占比 = ${fmt(stats.opaqueRatio)}，灰阶像素 = ${stats.grayCount}，" +
            "彩色像素 = ${stats.coloredCount}，最亮的 3 个颜色 = ${stats.brightest3}")

        assertTrue("整幅预览必须已写出", out.exists() && out.length() > 5_000)
        assertTrue("场景里必须真的有内容", items.isNotEmpty())
    }

    @Test
    fun renderUpstreamLayerPreview() {
        val renderer = QianliUpstreamRenderer()
        resetPortGlobals()
        renderer.gatePrepareForGate(W, H)

        val scale = renderer.gateTransformForGate().scale
        val blockW = renderer.gateBlockWidthForGate()
        val blockWorldW = blockW / scale

        // 单块：世界原点那一块（块内坐标 ⇒ 原点取 0）。
        val items = renderer.gateItemsForRange(0.0, blockWorldW, 0.0)

        val raster = QianliUpstreamRaster(blockW, H)
        raster.fillTiled(renderer.gatePaperPixelsForGate()!!, PAPER_RESO, PAPER_RESO)
        raster.drawItems(items)

        val out = File(repoRoot(), "output/e50_upstream_layer.png")
        raster.writePng(out)

        println("[E50 上游预览] 单块 = ${out.absolutePath}（${out.length() / 1024} KB，${blockW}×$H）")
        println("[E50 上游预览] 单块绘制项 = ${items.size} 条")

        assertTrue("单块预览必须已写出", out.exists() && out.length() > 5_000)
    }

    // ─────────────────────── 夹具 ───────────────────────

    /**
     * 复位 port 包的全局状态（照抄 `ShanShuiPortGateTest.resetPortGlobals()` 的口径）。
     *
     * ① PRNG：重新 seed ⇒ 后续调用拿到同一条流。
     * ② perlin 表：用 `noiseSeed` 钉死（**不消耗 PRNG**）⇒ 表的内容与随机流解耦 ⇒ 可复现。
     * ③ `MEM.planmtx`（上游的模块级全局）：一起复位。
     */
    private fun resetPortGlobals() {
        ShanShuiPrng.global.seed(PORT_SEED)
        ShanShuiNoise.noiseSeed(NOISE_SEED_VALUE)
        ShanShuiPlanner.resetPlanmtx()
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

    private fun fmt(v: Double): String = String.format("%.3f", v)

    private companion object {
        const val W = 1920
        const val H = 1080
        const val PAPER_RESO = 512

        /** 结构判据用的种子（与 `ShanShuiPortGateTest` 同一组）。 */
        const val PORT_SEED = 157

        /** perlin 表的钉扎种子（与 `ShanShuiPortGateTest` 同一组）。 */
        const val NOISE_SEED_VALUE = 20250101.0
    }
}

/**
 * 纯 `java.base` 的软件光栅器（**测试专用**）—— E50 上游版的预览 / 门禁共用。
 *
 * ## 为什么不用 AWT / Android Canvas
 *
 * - `java.awt.*` / `javax.imageio.*` **不在** Android 单测的编译 classpath 上。
 * - Android `Canvas` 在本机 Robolectric 的 LEGACY 图形模式下**不执行光栅化** ⇒ 位图恒空。
 *
 * ⇒ 自己实现：多边形填充（非零环绕规则，与 Android `Canvas` 默认 winding 一致）、
 * 折线描边、source-over 混合、PNG 写出。
 *
 * ## 与生产的关系
 *
 * 它**只负责「把像素画出来」**；画什么（坐标 / 颜色 / 次序）由
 * `QianliUpstreamRenderer.DrawItem` 决定 ⇒ 与生产**同一条变换与同一条绘制路径**。
 */
internal class QianliUpstreamRaster(val width: Int, val height: Int) {

    /** 0xAARRGGBB。 */
    private val px = IntArray(width * height)

    /** 整幅填一个颜色。 */
    fun fill(argb: Int) {
        px.fill(argb)
    }

    /** 平铺一张 tile（纸底）。 */
    fun fillTiled(tile: IntArray, tileW: Int, tileH: Int) {
        for (y in 0 until height) {
            val sy = y % tileH
            val row = y * width
            val trow = sy * tileW
            for (x in 0 until width) {
                px[row + x] = tile[trow + (x % tileW)]
            }
        }
    }

    /** source-over 混合一个像素。 */
    fun blend(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val sa = (argb ushr 24) and 0xFF
        if (sa == 0) return
        val i = y * width + x
        if (sa == 255) {
            px[i] = argb
            return
        }
        val da = (px[i] ushr 24) and 0xFF
        val sr = (argb ushr 16) and 0xFF
        val sg = (argb ushr 8) and 0xFF
        val sb = argb and 0xFF
        val dr = (px[i] ushr 16) and 0xFF
        val dg = (px[i] ushr 8) and 0xFF
        val db = px[i] and 0xFF
        val oa = sa + da * (255 - sa) / 255
        val nr = if (oa == 0) 0 else (sr * sa + dr * da * (255 - sa) / 255) / oa
        val ng = if (oa == 0) 0 else (sg * sa + dg * da * (255 - sa) / 255) / oa
        val nb = if (oa == 0) 0 else (sb * sa + db * da * (255 - sa) / 255) / oa
        px[i] = (oa shl 24) or
            (nr.coerceIn(0, 255) shl 16) or
            (ng.coerceIn(0, 255) shl 8) or
            nb.coerceIn(0, 255)
    }

    /** 多边形填充（**非零环绕规则**，与 Android `Canvas` 默认 winding 一致）。 */
    fun fillPoly(pts: FloatArray, argb: Int) {
        val n = pts.size / 2
        if (n < 3) return
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val y = pts[i * 2 + 1]
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val y0 = maxOf(0, kotlin.math.floor(minY).toInt())
        val y1 = minOf(height - 1, kotlin.math.ceil(maxY).toInt())
        if (y1 < y0) return
        val xs = FloatArray(n)
        val dirs = IntArray(n)
        for (y in y0..y1) {
            val cy = y + 0.5f
            var count = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ay = pts[i * 2 + 1]
                val by = pts[j * 2 + 1]
                if ((ay <= cy && by > cy) || (by <= cy && ay > cy)) {
                    val t = (cy - ay) / (by - ay)
                    xs[count] = pts[i * 2] + t * (pts[j * 2] - pts[i * 2])
                    dirs[count] = if (by > ay) 1 else -1
                    count++
                }
            }
            if (count < 2) continue
            val idx = (0 until count).sortedBy { xs[it] }
            var winding = 0
            for (k in 0 until idx.size - 1) {
                winding += dirs[idx[k]]
                if (winding == 0) continue
                val xa = kotlin.math.ceil(xs[idx[k]] - 0.5f).toInt()
                val xb = kotlin.math.floor(xs[idx[k + 1]] - 0.5f).toInt()
                for (x in maxOf(0, xa)..minOf(width - 1, xb)) {
                    blend(x, y, argb)
                }
            }
        }
    }

    /**
     * 折线描边。
     *
     * ⚠️ [width] 只在 **> 1** 时才生效（多画几条平行线）——
     * 上游的墨线宽度恒为 1（`wid` 都被烘进 ribbon 几何里）⇒ 1px 足够。
     */
    fun strokePoly(pts: FloatArray, argb: Int, width: Float) {
        val n = pts.size / 2
        if (n < 2) return
        for (i in 0 until n - 1) {
            val x0 = pts[i * 2]
            val y0 = pts[i * 2 + 1]
            val x1 = pts[i * 2 + 2]
            val y1 = pts[i * 2 + 3]
            line(x0, y0, x1, y1, argb)
            if (width > 1.5f) {
                val dx = x1 - x0
                val dy = y1 - y0
                val len = kotlin.math.hypot(dx, dy)
                if (len > 0.001f) {
                    val nx = -dy / len * (width - 1f) / 2f
                    val ny = dx / len * (width - 1f) / 2f
                    line(x0 + nx, y0 + ny, x1 + nx, y1 + ny, argb)
                    line(x0 - nx, y0 - ny, x1 - nx, y1 - ny, argb)
                }
            }
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        var ax = x0.toInt()
        var ay = y0.toInt()
        val bx = x1.toInt()
        val by = y1.toInt()
        val dx = kotlin.math.abs(bx - ax)
        val dy = -kotlin.math.abs(by - ay)
        val sx = if (ax < bx) 1 else -1
        val sy = if (ay < by) 1 else -1
        var err = dx + dy
        while (true) {
            blend(ax, ay, argb)
            if (ax == bx && ay == by) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                ax += sx
            }
            if (e2 <= dx) {
                err += dx
                ay += sy
            }
        }
    }

    /** 回读一个像素（0xAARRGGBB）。 */
    fun pixelAt(x: Int, y: Int): Int = px[y * width + x]

    /** 画一串绘制项（与生产同序：逐条「先填后描」）。 */
    fun drawItems(items: List<QianliUpstreamRenderer.DrawItem>) {
        for (it in items) {
            val fill = it.fill
            if (fill != null) fillPoly(it.pts, argbOf(fill))
            val stroke = it.stroke
            if (stroke != null) strokePoly(it.pts, argbOf(stroke), max(1f, it.width.toFloat()))
        }
    }

    private fun argbOf(c: ShanShuiColor): Int =
        (c.a.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255).let {
            (it shl 24) or (c.r shl 16) or (c.g shl 8) or c.b
        }

    // ─────────────────────── 统计 ───────────────────────

    /** 一屏的像素统计（给回报用的客观数字）。 */
    class Stats(
        val opaqueRatio: Double,
        val grayCount: Int,
        val coloredCount: Int,
        val brightest3: List<String>,
    )

    /**
     * 统计：非背景（不透明）像素占比、灰阶 / 彩色像素数、最亮的 3 个颜色。
     *
     * ⚠️ 「彩色」的判据 = **既不是纯灰阶、也不落在「灰 × 纸底」的混合线上**
     * （判据细节见 `QianliUpstreamGateTest` 的 U-GATE-5）。
     */
    fun stats(paperRatio: Double = 0.5): Stats {
        var opaque = 0
        var gray = 0
        var colored = 0
        val hist = HashMap<Int, Int>()
        for (i in px.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            if (a == 0) continue
            opaque++
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            if (isGrayOrBlend(r, g, b, paperRatio)) gray++ else colored++
            val key = (r shl 16) or (g shl 8) or b
            hist[key] = (hist[key] ?: 0) + 1
        }
        val brightest = hist.keys.sortedByDescending { (it shr 16) and 0xFF }.take(3)
        return Stats(
            opaqueRatio = if (px.isEmpty()) 0.0 else opaque.toDouble() / px.size,
            grayCount = gray,
            coloredCount = colored,
            brightest3 = brightest.map { String.format("#%06X", it) },
        )
    }

    // ─────────────────────── PNG ───────────────────────

    fun writePng(file: File) {
        file.parentFile?.mkdirs()
        val raw = ByteArray(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter type 0（None）
            for (x in 0 until width) {
                val c = px[y * width + x]
                raw[p++] = ((c ushr 16) and 0xFF).toByte()
                raw[p++] = ((c ushr 8) and 0xFF).toByte()
                raw[p++] = (c and 0xFF).toByte()
            }
        }
        val idat = zlib(raw)

        val out = ByteArrayOutputStream()
        out.write(0x89); out.write(0x50); out.write(0x4E); out.write(0x47)
        out.write(0x0D); out.write(0x0A); out.write(0x1A); out.write(0x0A)

        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, width)
        writeInt(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // color type = truecolor RGB
        ihdr[10] = 0  // compression
        ihdr[11] = 0  // filter
        ihdr[12] = 0  // interlace
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", idat)
        chunk(out, "IEND", ByteArray(0))

        file.outputStream().use { it.write(out.toByteArray()) }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeInt(len, 0, data.size)
        out.write(len)
        val body = ByteArray(4 + data.size)
        for (i in 0 until 4) body[i] = type[i].code.toByte()
        System.arraycopy(data, 0, body, 4, data.size)
        out.write(body)
        val crc = ByteArray(4)
        writeInt(crc, 0, crc32(body))
        out.write(crc)
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun zlib(data: ByteArray): ByteArray {
        val def = Deflater()
        try {
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1 shl 16)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            def.end()
        }
    }

    private companion object {
        val crcTable = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            c
        }

        fun crc32(bytes: ByteArray): Int {
            var c = 0xFFFFFFFF.toInt()
            for (b in bytes) {
                c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
            }
            return c xor 0xFFFFFFFF.toInt()
        }
    }
}

/**
 * 「这个像素合法吗」的判据 —— **纯灰阶** 或 **落在「灰 × 纸底」的混合线上**。
 *
 * ⭐ 上游的墨色**全是灰阶**（`rgba(100,100,100,α)` / `rgba(120,120,120,α)` /
 * `rgba(180,180,180,α)` / `getCol` 的 `rgb(c,c,c)`），纸底是**暖米白** ⇒
 * 任何合法像素必满足二者之一：
 * 1. `r == g == b`（纯灰阶墨）；或
 * 2. `r = t·k + (1−t)·pr`、`g = t·k + (1−t)·pg`、`b = t·k + (1−t)·pb`
 *    ⇒ `(r − g) / (g − b) = (pr − pg) / (pg − pb)`（**与 k、t 无关**）。
 *
 * ⛔ 这条线**不含**任何饱和色 ⇒ 色表的青绿 / 赭石 / 花青必被咬住。
 */
internal fun isGrayOrBlend(r: Int, g: Int, b: Int, paperRatio: Double): Boolean {
    if (Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2) return true
    val dg = g - b
    if (dg == 0) return false
    val expected = paperRatio * dg
    return Math.abs((r - g) - expected) <= 3.0
}
