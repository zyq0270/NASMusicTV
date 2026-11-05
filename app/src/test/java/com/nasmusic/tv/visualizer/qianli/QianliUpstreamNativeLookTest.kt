package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliUpstreamRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * **真 Android Canvas（Skia）** 渲染「忠于上游」路径 ⇒ **设备级画面校验**。
 *
 * ## 为什么要有这条
 *
 * `QianliUpstreamPreviewTest` 用的是**自写的软件光栅器**（纯 Kotlin）⇒ 它验证的是
 * 「几何与颜色对不对」，**不验证** Android 的 `Canvas` / `Path` / `BitmapShader` 这条真实路径
 * （纸纹平铺、非凸多边形填充、alpha 混合都可能与自写光栅器不同）。
 *
 * ⇒ 本测试用 Robolectric 的 **NATIVE 图形模式**（真 Skia）⇒ 走**生产的同一条绘制路径**，
 *    再用**与 `scripts/e50_phone_look_check.py` 完全同口径**的指标判定画面。
 *
 * ⛔ 判据不写死上游的数字，而是「**画面必须像上游**」这组结构性要求（与真机脚本同一组）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QianliUpstreamNativeLookTest {

    private fun reset() {
        ShanShuiPrng.global.seed(157)
        ShanShuiNoise.noiseSeed(20250101.0)
        ShanShuiPlanner.resetPlanmtx()
    }

    @Test
    fun realAndroidCanvasRendersUpstreamLook() {
        // ⛔ Robolectric 的 NATIVE 图形模式需要**平台原生运行时**
        //   （`robolectric-nativeruntime`，Windows 上是 `native/windows/x86_64/robolectric-nativeruntime.dll`）。
        //   ⇒ **离线环境**（拉不到该 artifact）时本测试**跳过**，而不是让整个单测套件变红。
        //   ⇒ 设备级等价验证在 `app/src/androidTest/.../QianliUpstreamDeviceRenderTest.kt`（真机真 Skia）。
        try {
            org.junit.Assume.assumeTrue("Robolectric NATIVE 运行时可用", nativeRuntimeAvailable())
            renderAndAssert()
        } catch (e: IllegalArgumentException) {
            // ⚠️ ⛔ **不能直接写 `e.message.contains(...)`**：`Throwable.message` 是 **open / 自定义 getter**
            //   ⇒ Kotlin **无法 smart cast** ⇒ 编译报「Smart cast to 'CharSequence' is impossible」。
            //   ⇒ 必须先把值取到局部变量（`val msg = e.message`）再判断。
            val msg = e.message
            if (msg != null && msg.contains("robolectric-nativeruntime")) {
                org.junit.Assume.assumeNoException("Robolectric NATIVE 原生运行时不可用（离线）⇒ 跳过", e)
            }
            throw e
        }
    }

    /** NATIVE 图形模式的原生运行时是否就位（用一次最小绘制探测）。 */
    private fun nativeRuntimeAvailable(): Boolean = try {
        val b = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(0xFF112233.toInt())
        b.getPixel(0, 0)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun renderAndAssert() {
        reset()
        val w = 1920
        val h = 1080
        val renderer = QianliUpstreamRenderer()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // 可见世界宽度 = w / scale = w / (h / 700.525)
        val visibleWorld = w * 700.5253940455342 / h
        renderer.gateDrawFrameForGate(cv, w, h, 0.0, visibleWorld)

        // 落盘（供人眼核对；⚠️ Robolectric 的工作目录是**模块目录** ⇒ 落在 app/output/ 下）
        val out = File("output/e50_upstream_native.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("[native] PNG -> ${out.absolutePath} (${out.length()} B)")

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // ① 纸底基准色 = 四角 9×9 区域的主色（与 metrics 脚本同口径）
        // ⚠️ ⛔ **坐标必须 clamp**（与真机 instrumented 版同一个坑）：
        //    `cx` 最大 8 ⇒ `cx * (w/8)` 可达 `w` ⇒ `w - 1 - cx*(w/8)` 变成 **-1** ⇒ 越界。
        val sx = (w / 8).coerceAtLeast(1)
        val sy = (h / 8).coerceAtLeast(1)
        val corner = HashMap<Int, Int>()
        for (cx in 0 until 9) {
            for (cy in 0 until 9) {
                val xs = listOf(
                    (cx * sx).coerceIn(0, w - 1),
                    (w - 1 - cx * sx).coerceIn(0, w - 1),
                )
                val ys = listOf(
                    (cy * sy).coerceIn(0, h - 1),
                    (h - 1 - cy * sy).coerceIn(0, h - 1),
                )
                for (pxx in xs) {
                    for (pyy in ys) {
                        val c = px[pyy * w + pxx]
                        corner[c] = (corner[c] ?: 0) + 1
                    }
                }
            }
        }
        val paper = corner.entries.maxBy { it.value }.key
        val pr = (paper shr 16) and 0xFF
        val pg = (paper shr 8) and 0xFF
        val pb = paper and 0xFF
        println("[native] paper rgb=($pr,$pg,$pb)")

        // ② 纸底 / 墨 的占比 + 墨平均灰度 + 彩色像素占比 + 纵向分布
        var paperN = 0
        var inkN = 0
        var graySum = 0.0
        var colorN = 0
        val bandInk = IntArray(10)
        val bandN = IntArray(10)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val bi = (y * 10 / h).coerceIn(0, 9)
                bandN[bi]++
                if (Math.abs(r - pr) + Math.abs(g - pg) + Math.abs(b - pb) <= 26) {
                    paperN++
                } else {
                    inkN++
                    graySum += 0.299 * r + 0.587 * g + 0.114 * b
                    if (Math.abs(r - g) > 12 && Math.abs(g - b) > 12) colorN++
                    bandInk[bi]++
                }
            }
        }
        val total = w * h
        val paperRatio = paperN.toDouble() / total
        val inkRatio = inkN.toDouble() / total
        val inkGray = if (inkN > 0) graySum / inkN else 0.0
        val colorRatio = colorN.toDouble() / total
        val topInk = bandInk[0] + bandInk[1]
        val topN = bandN[0] + bandN[1]
        val bottomInk = bandInk[8] + bandInk[9]
        val bottomN = bandN[8] + bandN[9]
        println(
            "[native] paperRatio=%.3f inkRatio=%.3f inkGray=%.1f colorRatio=%.4f top=%.4f bottom=%.4f"
                .format(paperRatio, inkRatio, inkGray, colorRatio, topInk.toDouble() / topN, bottomInk.toDouble() / bottomN)
        )

        // ③ 判定（与 `scripts/e50_phone_look_check.py` **同一组**结构性要求）
        // ⚠️ ⛔ Kotlin **不支持链式比较**（`pr > pg > pb` 会被解析成 `(pr > pg) > pb` ⇒ Boolean > Int ⇒ 编译不过）
        assertTrue(
            "纸底必须是暖米白绢色（实际 ($pr,$pg,$pb)）",
            pr > 230 && pg > 215 && pb > 185 && pr > pg && pg > pb,
        )
        assertTrue("纸底占比须 ≥ 0.45（线描为主，不是色块堆）", paperRatio >= 0.45)
        assertTrue("墨平均灰度须 ≤ 215（浅灰墨，不是纯黑）", inkGray <= 215.0)
        assertTrue("彩色像素占比须 ≤ 0.05（纯灰阶）", colorRatio <= 0.05)
        assertTrue("画面上部必须有山（0–0.25 墨占比 > 0.005）", topInk.toDouble() / topN > 0.005)
        assertTrue("近景岸必须有密纹（0.85–1.0 墨占比 > 0.05）", bottomInk.toDouble() / bottomN > 0.05)

        // ④ 与上游参考图的量化基线对照（纸底色必须落在上游的暖米白区间）
        assertTrue(
            "纸底必须与上游纸色同一族（上游实测 (245,232,208)），实际 ($pr,$pg,$pb)",
            pr in 230..252 && pg in 218..238 && pb in 195..212,
        )
    }
}
