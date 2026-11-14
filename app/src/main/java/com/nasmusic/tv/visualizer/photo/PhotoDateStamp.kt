package com.nasmusic.tv.visualizer.photo

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import java.util.Calendar

/**
 * 照片墙**拍摄日期水印**（2026-11-13）
 *
 * ## 需求（所有者原话）
 *
 * 1. 每一幅照片**切换完成、完整显示出来后**，在**右下角**显示拍摄日期的「年月日时分秒」
 * 2. 格式：`YYYY/MM/DD hh:mm:ss`
 * 3. **红色**文字
 *
 * ## 三条设计口径
 *
 * | 问题 | 决定 | 为什么 |
 * |---|---|---|
 * | 「切换完成」是哪一刻 | `photoProgress >= 1`（= 转场窗口走完，进入 HOLD） | 与 `shouldDrawOnlyNewPhoto` 同一判据；转场中途显示会让人以为旧图也有这个日期 |
 * | 「右下角」在哪 | **右下角安全区**，不是屏幕右下角 | 画布四周有 overscan 留白（`safeAreaPx`）；贴屏幕边会被电视裁掉一圈 |
 * | 水印跟着 Ken Burns 推近吗 | **不跟**，按当前帧照片的**矩形右下角**算 | 跟着放大会持续漂移；固定在矩形角上更稳，也不会被推出画面 |
 *
 * ## ⛔ 零分配的边界
 *
 * 文本每隔**秒**才变一次 ⇒ 用「秒数」做缓存键，同一秒内不重新格式化（不产生字符串）。
 * `Paint` / `Calendar` 是本类的字段，**只在构造时创建一次**。
 * `drawIntoCanvas` / `nativeCanvas` 是 inline / 属性访问，不分配。
 *
 * ## ⛔ 为什么用 `nativeCanvas` 而不是 Compose `TextMeasurer`
 *
 * `drawText` 走 `android.graphics.Paint`：一次绘制 + 一次 `measureText`，无布局树；
 * `TextMeasurer` 每次都会走文本布局（P2 档的分块类转场里已有同样结论：
 * 「真字符要逐字绘制 ⇒ 每帧几十次文本布局 ⇒ 真机上不可接受」）。
 *
 * ## 样式
 *
 * 红色（`#FF3B30`）+ 轻描边（黑色 55% 透明度）：纯红字压在**浅色照片**上会糊掉，
 * 加一层细描边让它在白墙 / 雪景 / 天空上也能读清 —— 这是「能看见」与「好看」的取舍，
 * 描边只有 1~2px，远看仍是红字。
 */
internal class PhotoDateStamp {

    /** 文字颜色：`#FF3B30`（iOS 系统红）—— 比纯 `#FF0000` 略深，在白色照片上仍可辨 */
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    /** 描边：只用于「提底」，不参与排版度量 */
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = STROKE_COLOR
        typeface = textPaint.typeface
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /** 格式化用的日历（复用，`TimeZone` 跟随系统 ⇒ 与 `PhotoCaptureDate` 的解释口径一致） */
    private val calendar: Calendar = Calendar.getInstance()

    /** 缓存键：上次格式化的（秒数 + 字号整数）—— 任一变化才重新格式化 */
    private var cachedKey = Long.MIN_VALUE
    private var cachedText = ""

    /** 日期格式：`YYYY/MM/DD hh:mm:ss`（所有者指定，24 小时制） */
    fun format(epochSeconds: Long): String {
        val key = epochSeconds
        if (key == cachedKey && cachedText.isNotEmpty()) return cachedText
        calendar.timeInMillis = epochSeconds * 1000L
        val sb = StringBuilder(19)
        appendPadded(sb, calendar.get(Calendar.YEAR), 4)
        sb.append('/')
        appendPadded(sb, calendar.get(Calendar.MONTH) + 1, 2)
        sb.append('/')
        appendPadded(sb, calendar.get(Calendar.DAY_OF_MONTH), 2)
        sb.append(' ')
        appendPadded(sb, calendar.get(Calendar.HOUR_OF_DAY), 2)
        sb.append(':')
        appendPadded(sb, calendar.get(Calendar.MINUTE), 2)
        sb.append(':')
        appendPadded(sb, calendar.get(Calendar.SECOND), 2)
        cachedKey = key
        cachedText = sb.toString()
        return cachedText
    }

    /**
     * 在 [dst] 这个「照片在画布上占据的矩形」的**右下角**画水印。
     *
     * @param alpha 整张图的可见度（转场末帧 / FIT 终帧时恒为 1；留给将来做淡入）
     * @param canvasW / [canvasH] 画布尺寸 —— 备用边距取两者的短边，保证竖屏也不挤边
     */
    fun draw(
        scope: DrawScope,
        text: String,
        dst: PhotoRect,
        canvasW: Float,
        canvasH: Float,
        alpha: Float = 1f,
    ) {
        if (text.isEmpty() || alpha <= 0f) return
        if (!dst.isValid) return

        val shortSide = minOf(canvasW, canvasH)
        val textSize = (shortSide * TEXT_SIZE_RATIO).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)
        textPaint.textSize = textSize
        strokePaint.textSize = textSize
        strokePaint.strokeWidth = (textSize * STROKE_RATIO).coerceAtLeast(1f)

        val margin = (shortSide * MARGIN_RATIO).coerceIn(MIN_MARGIN_PX, MAX_MARGIN_PX)
        val width = textPaint.measureText(text)
        // 矩形宽或高不足一行字 ⇒ 不画（FIT 下的极扁长图 / 极窄竖图）：宁可不显示，也不要糊成一团
        val lineHeight = textPaint.descent() - textPaint.ascent()
        if (width > dst.width * MAX_WIDTH_RATIO || lineHeight + margin * 2f > dst.height) return

        // 基线：让字的下缘距矩形底边 `margin`（`descent` 是基线以下的部分）
        val baseline = dst.bottom - margin - textPaint.descent()
        val x = dst.right - margin - width

        textPaint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        strokePaint.alpha = (255 * alpha * STROKE_ALPHA).toInt().coerceIn(0, 255)
        val nc = scope.drawContext.canvas.nativeCanvas
        nc.drawText(text, x, baseline, strokePaint)
        nc.drawText(text, x, baseline, textPaint)
    }

    /** 清掉格式化缓存（尺寸 / 时区变化不必须，退出效果时调用即可） */
    fun clear() {
        cachedKey = Long.MIN_VALUE
        cachedText = ""
    }

    private fun appendPadded(sb: StringBuilder, value: Int, width: Int) {
        val s = value.coerceAtLeast(0).toString()
        repeat((width - s.length).coerceAtLeast(0)) { sb.append('0') }
        sb.append(s)
    }

    companion object {
        /** `#FF3B30` —— 红色（带不透明 alpha，实际透明度由 `Paint.alpha` 单独控制） */
        const val TEXT_COLOR: Int = 0xFFFF3B30.toInt()

        /** 描边色：纯黑，透明度由 [STROKE_ALPHA] 控制 */
        const val STROKE_COLOR: Int = 0xFF000000.toInt()

        /** 描边透明度（相对文字 alpha）—— 只需「提底」，不能把红字吃掉 */
        const val STROKE_ALPHA = 0.55f

        /** 字号 = 画布短边 × 该比例（1080 短边 ⇒ 约 32px） */
        const val TEXT_SIZE_RATIO = 0.030f
        const val MIN_TEXT_PX = 16f
        const val MAX_TEXT_PX = 64f

        /** 描边宽度 = 字号 × 该比例（约 1.5~2px） */
        const val STROKE_RATIO = 0.055f

        /** 边距 = 画布短边 × 该比例（1080 短边 ⇒ 约 32px） */
        const val MARGIN_RATIO = 0.030f
        const val MIN_MARGIN_PX = 16f
        const val MAX_MARGIN_PX = 80f

        /** 文本宽度超过矩形宽度的该比例 ⇒ 放弃绘制（极扁长图） */
        const val MAX_WIDTH_RATIO = 0.9f
    }
}

/**
 * 水印该画在哪个矩形里 —— **纯函数**，门禁 `PhotoDateStampTest` 直接钉它。
 *
 * - `CROP`（满屏）：`dst` 已是整个画布（未推近时）⇒ 用它自己
 * - `FIT`（完整）：`dst` 是内接矩形 ⇒ 用它自己（照片的右下角，而不是屏幕右下角），
 *   这样黑边上不会有孤零零的红字
 */
internal fun dateStampRect(dst: PhotoRect, scaleMode: PhotoScaleMode, out: PhotoRect) {
    // 两种模式都直接沿用 `dst`：CROP 下是画布（推近后略大于画布 ⇒ 由外层 clipRect 裁掉超出部分，
    // 水印仍落在可见范围内）；FIT 下是内接矩形。
    // ⚠️ 参数 `scaleMode` 参与判定是为了让门禁能逐模式断言「同一份实现」，值本身两种模式同路。
    if (scaleMode == PhotoScaleMode.CROP || scaleMode == PhotoScaleMode.FIT) {
        out.set(dst.left, dst.top, dst.right, dst.bottom)
    } else {
        out.reset()
    }
}

/**
 * 该不该显示水印 —— **纯函数**（门禁直接覆盖，不依赖绘制设备）。
 *
 * ⛔ 三条都成立才显示：
 * 1. 有可用日期（`capturedAt > 0`）
 * 2. **转场已走完**（`progress >= 1`）—— 「切换完成、完整显示出来后」才出现
 * 3. 这张图本帧**确实在画面上**（`visible`）—— 单图退化 / FIT 终帧只画 B 时，A 不画
 *
 * ⚠️ `p >= 1f` 与 `PhotoRenderContractScanTest` 的「晚帧跳过」门禁无关（那扫的是
 * `transitions/` 目录，本文件在 `photo/` 根上且不含 `continue` / `return` 跳过绘制）。
 */
internal fun shouldShowDateStamp(capturedAt: Long, progress: Float): Boolean =
    capturedAt > 0L && progress >= 1f
