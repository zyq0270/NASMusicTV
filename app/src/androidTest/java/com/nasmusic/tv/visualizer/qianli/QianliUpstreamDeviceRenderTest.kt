package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiNoise
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPlanner
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import com.nasmusic.tv.visualizer.renderers.QianliUpstreamRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **真机**渲染「忠于上游」路径 ⇒ **设备级画面校验**（不需要解锁屏幕）。
 *
 * ## 为什么需要这条
 *
 * ① `QianliUpstreamPreviewTest` 用的是**自写的软件光栅器** ⇒ 验证「几何与颜色」，
 *    **不验证** Android 的 `Canvas` / `Path` / `BitmapShader` 这条真实路径。
 * ② `QianliUpstreamNativeLookTest`（Robolectric NATIVE）需要平台原生运行时，
 *    **离线环境拉不到** ⇒ 不可用。
 * ③ 真机**截图**路线要求手机解锁（PIN 锁时截不到 App 画面）。
 *
 * ⇒ 本测试在**真机**上把上游路径渲染进 `Bitmap`（真 Skia、真 `BitmapShader` 纸纹），
 *    写出 PNG 供 `adb pull` 取回，并用**与 `scripts/e50_phone_look_check.py` 同口径**的指标判定画面。
 *
 * ⛔ 判据只写「画面必须像上游」的**结构性要求**，不写死上游的绝对数字。
 */
@RunWith(AndroidJUnit4::class)
class QianliUpstreamDeviceRenderTest {

    @Test
    fun renderUpstreamPathOnDevice() {
        ShanShuiPrng.global.seed(157)
        ShanShuiNoise.noiseSeed(20250101.0)
        ShanShuiPlanner.resetPlanmtx()

        val w = 1920
        val h = 1080
        val renderer = QianliUpstreamRenderer()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // 可见世界宽度 = w / scale（scale = h / 700.5253940455342）
        val visibleWorld = w * 700.5253940455342 / h
        renderer.gateDrawFrameForGate(cv, w, h, 0.0, visibleWorld)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val png = File(dir, "e50_upstream_device.png")
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("[device] PNG -> ${png.absolutePath} (${png.length()} B)")

        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // ① 纸底基准色 = 四角 9×9 区域的主色（与 `scripts/e50_upstream_metrics.py` 同口径）
        // ⚠️ ⛔ **坐标必须 clamp**（2026-11-06 真机实测踩到）：`cx` 最大 8 ⇒ `cx * (w/8)` 可达 `w`
        //    ⇒ `w - 1 - cx*(w/8)` 变成 **-1** ⇒ `px[-w + ...]` 越界 ⇒
        //    `ArrayIndexOutOfBoundsException: length=2073600; index=2073600`。
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
        val topInk = (bandInk[0] + bandInk[1]).toDouble() / (bandN[0] + bandN[1])
        val bottomInk = (bandInk[8] + bandInk[9]).toDouble() / (bandN[8] + bandN[9])
        println(
            "[device] paper=($pr,$pg,$pb) paperRatio=%.3f inkRatio=%.3f inkGray=%.1f colorRatio=%.4f top=%.4f bottom=%.4f"
                .format(paperRatio, inkRatio, inkGray, colorRatio, topInk, bottomInk)
        )

        // ② 判定（与 `scripts/e50_phone_look_check.py` **同一组**结构性要求）
        assertTrue("纸底必须是暖米白绢色（实际 ($pr,$pg,$pb)）", pr > 230 && pg > 215 && pb > 185 && pr > pg && pg > pb)
        assertTrue("纸底占比须 ≥ 0.45（线描为主，不是色块堆）", paperRatio >= 0.45)
        assertTrue("墨平均灰度须 ≤ 215（浅灰墨，不是纯黑）", inkGray <= 215.0)
        assertTrue("彩色像素占比须 ≤ 0.05（纯灰阶）", colorRatio <= 0.05)
        assertTrue("画面上部必须有山（0–0.25 墨占比 > 0.005，实际 $topInk）", topInk > 0.005)
        assertTrue("近景岸必须有密纹（0.85–1.0 墨占比 > 0.05，实际 $bottomInk）", bottomInk > 0.05)
    }
}
