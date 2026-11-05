package com.nasmusic.tv.visualizer.qianli

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * 纯 `java.base` 的极简光栅器 + PNG 写出器（**测试专用**）。
 *
 * ## 为什么不用 AWT
 *
 * Android 单元测试的编译 classpath 只有 `android.jar` + 受限的 JDK 视图 ——
 * `java.awt.*` 与 `javax.imageio.*` **不在其中**（实测：`import java.awt.BasicStroke`
 * 编译期报 `Unresolved reference`）。
 * ⇒ 只能自己实现光栅化与 PNG 编码。
 * 可用的是 `java.util.zip.Deflater`（android.jar 里有）—— 它输出的正是 PNG IDAT 需要的 zlib 流。
 *
 * ## 能力
 *
 * - 24 位 RGB（非预乘），行序自上而下。
 * - 多边形填充：**非零环绕规则**（与 Android `Canvas` 的默认 winding 一致）。
 * - 折线描边：逐段画粗线。
 * - 颜色按 source-over 混合（alpha 叠加）。
 */
internal class PngCanvas(val width: Int, val height: Int) {

    /** 0xAARRGGBB。 */
    private val px = IntArray(width * height)

    fun fill(argb: Int) {
        px.fill(argb)
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
        // 逐通道：out = (src*sa + dst*da*(255-sa)/255) / outA
        val nr = if (oa == 0) 0 else (sr * sa + dr * da * (255 - sa) / 255) / oa
        val ng = if (oa == 0) 0 else (sg * sa + dg * da * (255 - sa) / 255) / oa
        val nb = if (oa == 0) 0 else (sb * sa + db * da * (255 - sa) / 255) / oa
        px[i] = (oa shl 24) or
            (nr.coerceIn(0, 255) shl 16) or
            (ng.coerceIn(0, 255) shl 8) or
            nb.coerceIn(0, 255)
    }

    /**
     * 多边形填充（**非零环绕规则**，与 Android `Canvas` 默认 winding 一致）。
     *
     * @param pts 扁平坐标 `[x0,y0,x1,y1,…]`
     */
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
            // 按交点 x 排序（带上方向，用于非零规则）
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

    /** 折线描边：逐段画 [width] 像素粗的线。 */
    fun strokePoly(pts: FloatArray, argb: Int, width: Float) {
        val n = pts.size / 2
        if (n < 2) return
        for (i in 0 until n - 1) {
            line(
                pts[i * 2], pts[i * 2 + 1],
                pts[i * 2 + 2], pts[i * 2 + 3],
                argb, width,
            )
        }
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, width: Float) {
        // ⛔ 预览用 1px Bresenham：粗线会让皴线（每座山 200 条 × 数百点）的开销爆炸。
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
