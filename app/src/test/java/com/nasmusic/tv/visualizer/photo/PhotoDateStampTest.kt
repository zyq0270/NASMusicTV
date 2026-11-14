package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.nasmusic.tv.backend.photo.PhotoCaptureDate
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.RenderContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * **门禁：照片墙右下角的拍摄日期水印**（2026-11-13）
 *
 * ## 需求原文（所有者）
 *
 * > 1. 在每一幅照片**切换完成，完整显示出来后**，在照片的**右下角**显示拍摄日期的年月日时分秒
 * > 2. 显示格式为：`YYYY/MM/DD hh:mm:ss`
 * > 3. 用**红色**的文字显示
 *
 * 三条各自对应下面的用例；另有一条**源码门禁**把「渲染器真的调了水印绘制」钉住 ——
 * 因为「画不画」是绘制路径上的事，纯 JVM 断言只能验判定函数，验不了调用点。
 *
 * ## 为什么要 Robolectric
 *
 * `PhotoDateStamp.format` 用 `java.util.Calendar`（纯 JVM 可跑），但 `draw` 用
 * `Paint.measureText` / `nativeCanvas.drawText` ⇒ 需要一个 Android 运行时。
 * ⚠️ 这里**不做像素回读**（Windows 上 Robolectric 的 NATIVE 图形模式不可用，
 * 见 `PhotoTransitionP2Test` 的说明）⇒ 「红色」用 `Paint` 的颜色常量断言，而不是取色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoDateStampTest {

    // ────────────────────── ① 格式：YYYY/MM/DD hh:mm:ss ──────────────────────

    @Test
    fun `格式必须是 YYYY MM DD hh mm ss 且补零`() {
        val stamp = PhotoDateStamp()
        // 2021-09-03 07:05:09（+08:00）= 1630623909
        val epoch = PhotoCaptureDate.parse("2021:09:03 07:05:09", TimeZone.getTimeZone("GMT+08:00"))
        val text = inZone("GMT+08:00") { stamp.format(epoch) }
        assertEquals("必须逐字符等于指定格式（月 / 日 / 时 / 分 / 秒都要补零）", "2021/09/03 07:05:09", text)
        assertEquals("定长 19 字符", 19, text.length)
    }

    @Test
    fun `时间是 24 小时制不用 AM PM`() {
        val stamp = PhotoDateStamp()
        val epoch = PhotoCaptureDate.parse("2021:12:31 23:59:59", TimeZone.getTimeZone("GMT+08:00"))
        assertEquals(
            "23 点必须显示 23（12 小时制会显示 11）",
            "2021/12/31 23:59:59",
            inZone("GMT+08:00") { stamp.format(epoch) },
        )
    }

    @Test
    fun `同一秒重复格式化必须命中缓存且结果不变`() {
        val stamp = PhotoDateStamp()
        val epoch = PhotoCaptureDate.parse("2023:11:14 22:13:20", TimeZone.getTimeZone("GMT+08:00"))
        val first = inZone("GMT+08:00") { stamp.format(epoch) }
        val second = inZone("GMT+08:00") { stamp.format(epoch) }
        assertEquals("缓存键相同 ⇒ 直接返回上一次的字符串", first, second)
        // 换一秒必须重算（缓存失效）
        val next = inZone("GMT+08:00") { stamp.format(epoch + 1) }
        assertEquals("下一秒必须重新格式化", "2023/11/14 22:13:21", next)
    }

    // ────────────────────── ② 颜色：红色 ──────────────────────

    @Test
    fun `文字颜色必须是红色`() {
        // #FF3B30：红色（R 最高、G/B 低）
        val color = PhotoDateStamp.TEXT_COLOR
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        assertTrue("R 通道必须是最高且接近饱和（实际 R=$r）", r >= 200)
        assertTrue("G 通道必须很低（实际 G=$g）", g <= 80)
        assertTrue("B 通道必须很低（实际 B=$b）", b <= 80)
        assertTrue("R 必须显著高于 G/B", r - maxOf(g, b) >= 150)
        assertEquals("必须不透明（透明度由 Paint.alpha 单独控制）", 0xFF, (color ushr 24) and 0xFF)
    }

    // ────────────────────── ③ 显示时机：切换完成之后 ──────────────────────

    @Test
    fun `转场进行中不显示水印`() {
        for (p in listOf(0f, 0.01f, 0.5f, 0.99f)) {
            assertFalse("p=$p 时转场还没走完 ⇒ 不能出现「切换完成」的水印", shouldShowDateStamp(1_600_000_000L, p))
        }
    }

    @Test
    fun `转场走完才显示水印`() {
        assertTrue("p=1 是「完整显示出来后」⇒ 必须显示", shouldShowDateStamp(1_600_000_000L, 1f))
    }

    @Test
    fun `日期未知时不显示水印`() {
        assertFalse("没有可用日期 ⇒ 不能画一个空框 / 1970 年", shouldShowDateStamp(0L, 1f))
        assertFalse("负数同样视为未知", shouldShowDateStamp(-5L, 1f))
    }

    // ────────────────────── ④ 位置：照片自己的右下角 ──────────────────────

    @Test
    fun `水印矩形就是照片自己的矩形而不是整块屏幕`() {
        val dst = PhotoRect().apply { set(200f, 100f, 1700f, 900f) }   // FIT 的内接矩形
        val out = PhotoRect()
        dateStampRect(dst, PhotoScaleMode.FIT, out)
        assertEquals(
            "FIT 下水印必须贴照片内接矩形的右下角（贴屏幕右下角会落在黑边上）",
            "PhotoRect(200.0, 100.0, 1700.0, 900.0)",
            out.toString(),
        )
        dateStampRect(dst, PhotoScaleMode.CROP, out)
        assertEquals("CROP 下照片铺满画布 ⇒ 同样沿用 dst", dst.toString(), out.toString())
    }

    @Test
    fun `真实绘制路径不抛异常且不越界绘制`() {
        val stamp = PhotoDateStamp()
        val text = inZone("GMT+08:00") {
            stamp.format(PhotoCaptureDate.parse("2021:09:03 07:05:09", TimeZone.getTimeZone("GMT+08:00")))
        }
        // 正常 16:9 画布
        drawOn(stamp, text, 0f, 0f, 1920f, 1080f)
        // 极扁的矩形（FIT 下横版长图）—— 不崩、不画（宽度占比超限）
        drawOn(stamp, text, 0f, 500f, 1920f, 540f)
        // 极小矩形 —— 不崩
        drawOn(stamp, text, 0f, 0f, 40f, 20f)
        // alpha = 0 —— 不画
        drawOn(stamp, text, 0f, 0f, 1920f, 1080f, alpha = 0f)
    }

    // ────────────────────── ⑤ 源码门禁：渲染器必须真的画 ──────────────────────

    @Test
    fun `PhotoRenderer 必须在画完之后调用水印绘制`() {
        val src = sourceFile("com/nasmusic/tv/visualizer/photo/PhotoRenderer.kt")
        val clean = SourceScan.stripComments(src)
        assertTrue(
            "PhotoRenderer.draw 里必须调用 drawDateStamp(...) —— 否则水印永远不会出现",
            Regex("""drawDateStamp\(""").containsMatchIn(clean),
        )
        // 调用点必须在转场 render 之后（叠在最上层），否则会被转场覆盖
        val renderIdx = clean.indexOf("t.run { render(")
        val stampIdx = clean.lastIndexOf("drawDateStamp(ctx,")
        assertTrue("找不到转场 render 调用点", renderIdx >= 0)
        assertTrue("找不到 drawDateStamp 调用点", stampIdx >= 0)
        // A 的水印在 render 之前（它只在转场窗口内显示）、B 的水印在 render 之后；
        // 「B 在 render 之后」由最后一个 drawDateStamp 的位置保证
        assertTrue(
            "B 的水印必须在转场 render **之后**绘制（顺序反了会被转场盖掉）",
            stampIdx > renderIdx,
        )
        assertTrue(
            "A 的水印必须在 `progress < 1` 时才画 —— 否则 HOLD 期两张的水印会在右下角重叠",
            clean.contains("inTransition") && clean.contains("if (inTransition) drawDateStamp(ctx, aCapturedAt, atA = true)"),
        )
        assertTrue(
            "FIT 终帧「只画新图」的分支也要画新图的水印（否则 FIT 模式下水印只有在转场那一帧出现）",
            clean.contains("drawDateStamp(ctx, bCapturedAt, atA = false)"),
        )
    }

    @Test
    fun `控制器必须把拍摄时间写进 RenderContext`() {
        val clean = SourceScan.stripComments(
            sourceFile("com/nasmusic/tv/visualizer/photo/PhotoWallController.kt"),
        )
        assertTrue("photoACapturedAt 必须由 applyTo 写入", clean.contains("ctx.photoACapturedAt ="))
        assertTrue("photoBCapturedAt 必须由 applyTo 写入", clean.contains("ctx.photoBCapturedAt ="))
    }

    // ────────────────────── 内部实现 ──────────────────────

    private fun inZone(id: String, block: () -> String): String {
        val original = TimeZone.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /** 在真实 `DrawScope` 上跑一次绘制（Robolectric 提供 Canvas；不做像素回读） */
    private fun drawOn(
        stamp: PhotoDateStamp,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        alpha: Float = 1f,
    ) {
        val w = right.toInt().coerceAtLeast(1)
        val h = (bottom - top).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val size = Size(right - left, bottom - top)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap.asImageBitmap()), size) {
            val dst = PhotoRect().apply { set(left, top, right, bottom) }
            stamp.draw(this, text, dst, right, bottom, alpha)
        }
        bitmap.recycle()
    }

    private fun sourceFile(rel: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/$rel"),
            java.io.File("app/src/main/java/$rel"),
            java.io.File("../app/src/main/java/$rel"),
        )
        val f = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件 $rel（user.dir=${java.io.File("").absolutePath}）", f != null)
        return f!!.readText()
    }
}
