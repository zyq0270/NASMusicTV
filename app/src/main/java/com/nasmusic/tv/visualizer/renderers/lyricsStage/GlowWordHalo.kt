package com.nasmusic.tv.visualizer.renderers.lyricsStage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.max

/**
 * 逐字形辉光的**位图缓存**。
 *
 * ## 为什么需要它
 * Folia 的辉光层用合成器滤镜（`drop-shadow()`）—— GPU 上是免费的。
 * Android 没有等价物，最接近的是 `Paint.setShadowLayer`，而它是**逐次绘制的模糊**：
 * 每个字形都要重新模糊一次。
 *
 * Folia 自己在 `wordGlow.ts:10-27` 记录过这件事的代价：text-shadow 会让
 * Chromium 的字形缓存不断铸造 strike，Mac 上实测光栅线程每秒耗掉 160–220 ms。
 * ⇒ **逐字形 `setShadowLayer` 在电视 SoC 上就是卡顿的主因**（一行 30 个字形 ×
 *   外圈 + 内圈两遍 = 每帧 60 次文字模糊）。
 *
 * ## 本类的做法
 * 辉光的**形状**只取决于「字形 + 字号 + 颜色 + 半径」，与时间无关；
 * 随时间变的只有**强度**（Folia 的 peak）。
 * ⇒ 把「字形 + 颜色 + 字号 + 半径」做成**缓存的辉光位图**（模糊只算一次），
 *   逐帧只做一次 `drawBitmap` + alpha ⇒ 从「每帧 N 次模糊」降到「每帧 N 次贴图」。
 *
 * ## 零分配（逐帧路径）
 * ⛔ 缓存键**不能**是 `String`：拼一个键就要一次字符串分配 ⇒ 逐帧每个可见字形
 *   分配一次（60 fps × 20 字形 = 每秒 1200 次）。
 * ⇒ 这里用**数组 + 线性比对**（数值字段先比、字形最后比）⇒ 命中路径**零分配**。
 *   未命中才会真正做一次模糊（每个「字形 + 字号 + 颜色 + 半径」只发生一次）。
 *
 * ## ⛔ 为什么不 `recycle()`
 * 渲染器可能把 `Entry` 存进自己的数组并**跨帧持有**（例如离场动画还在画上一行）。
 * 若这里把被逐出的位图 `recycle()`，那些**陈旧引用**再被 `drawBitmap` 画一次就会抛
 * 「trying to use a recycled bitmap」。
 * ⇒ **只丢引用、不回收**，交给 GC 释放（现代 Android 的 Bitmap 由 GC 释放 native 内存）。
 *   代价是内存回收推迟一点，收益是**任何持有模式都不会崩**。
 *
 * ## 与上游的偏差（如实记录）
 * Folia 的 `drop-shadow(0 0 inner*peak px color)` 里**半径随 peak 缩放**；
 * 缓存位图只能固定半径 ⇒ 这里改成**只随 peak 改 alpha**（半径取 peak=1 的最大值）。
 * 观感接近（Folia 的辉光本来就是「亮起 → 淡出」），但不是逐位相同。
 */
object GlowWordHalo {

    /** 缓存条目上限（每个条目约 (字号+2×半径)² × 4 字节） */
    private const val MAX_ENTRIES = 64

    /** 移植 wordGlow.ts:31 `DROP_SHADOW_INNER_ALPHA`（内圈弱于外圈：外圈也会给它投影） */
    private const val DROP_SHADOW_INNER_ALPHA = 0.7f

    /** 一个字形的辉光位图 */
    class Entry(
        val bitmap: Bitmap,
        /** 位图 (0,0) 相对「字的绘制原点（基线左端）」的偏移 */
        val originX: Float,
        val originY: Float,
        /** 缓存匹配用的键字段（由本对象创建，外部只读） */
        val char: String,
        val textSize: Float,
        val color: Int,
        val innerRadiusPx: Float,
        val outerRadiusPx: Float,
    )

    /**
     * 缓存（数组 + 线性比对 ⇒ 命中路径零分配）。
     *
     * 顺序即插入顺序；超出 [MAX_ENTRIES] 时丢最老的一个。
     */
    private val entries = ArrayList<Entry>(MAX_ENTRIES)

    /**
     * 取某个字形的辉光位图（命中缓存则直接返回）。
     *
     * ⛔ **命中路径零分配**（不拼字符串键、不建临时对象）⇒ 可以**逐帧**调用。
     *
     * @param char 单个字形
     * @param paint 用于测量的画笔（调用方需已设好 textSize / typeface）
     * @param color 辉光颜色（Folia 的 `activeColor`）
     * @param innerRadiusPx 内圈半径（Folia `glowRadii` 的 **inner** × `DROP_SHADOW_RADIUS_SCALE`，取 peak=1）
     * @param outerRadiusPx 外圈半径（Folia `glowRadii` 的 **outer** × `DROP_SHADOW_RADIUS_SCALE`，取 peak=1）
     * @return 缓存条目；尺寸算不出来时返回 null（调用方跳过辉光）
     */
    fun obtain(
        char: String,
        paint: Paint,
        color: Int,
        innerRadiusPx: Float,
        outerRadiusPx: Float,
    ): Entry? {
        if (char.isEmpty() || paint.textSize <= 0f) return null
        val textSize = paint.textSize

        // 命中：先比数值字段（最快），最后比字形
        for (i in entries.indices) {
            val e = entries[i]
            if (e.textSize == textSize &&
                e.color == color &&
                e.innerRadiusPx == innerRadiusPx &&
                e.outerRadiusPx == outerRadiusPx &&
                e.char == char
            ) {
                return e
            }
        }

        // 未命中：量一次、模糊一次
        val metrics = paint.fontMetrics
        val ascent = -metrics.ascent
        val descent = metrics.descent
        val textWidth = paint.measureText(char)
        if (textWidth <= 0f || ascent <= 0f) return null

        // 阴影模糊会向外扩 ⇒ 留足 padding
        val pad = ceil(max(innerRadiusPx, outerRadiusPx) * 1.5f) + 2f
        val width = ceil(textWidth + pad * 2f).toInt().coerceAtLeast(1)
        val height = ceil(ascent + descent + pad * 2f).toInt().coerceAtLeast(1)

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        val canvas = Canvas(bitmap)
        val glowPaint = Paint(paint)
        glowPaint.style = Paint.Style.FILL

        // ⚠️ Folia 的辉光是**两层**（wordGlow.ts:56-57）：
        //   外圈 = 满 alpha、较大半径；内圈 = 0.7 alpha、较小半径。
        //   这里在**建缓存时**把两层都画进**同一张位图** ⇒ 逐帧只需一次 drawBitmap，
        //   而观感仍是两层（外柔内亮）。
        val alpha = (color ushr 24) and 0xFF
        val rgb = color and 0x00FFFFFF

        // 外圈（wordGlow.ts:57 `drop-shadow(0 0 outer * 0.4 * peak px color)`）
        glowPaint.color = color
        glowPaint.setShadowLayer(outerRadiusPx, 0f, 0f, color)
        canvas.drawText(char, pad, pad + ascent, glowPaint)

        // 内圈（wordGlow.ts:56 —— 字形本体透明、只留光晕、透明度 0.7）
        glowPaint.color = rgb
        glowPaint.setShadowLayer(
            innerRadiusPx, 0f, 0f,
            rgb or ((alpha * DROP_SHADOW_INNER_ALPHA).toInt().coerceIn(0, 255) shl 24),
        )
        canvas.drawText(char, pad, pad + ascent, glowPaint)

        glowPaint.setShadowLayer(0f, 0f, 0f, color)

        val entry = Entry(
            bitmap = bitmap,
            originX = -pad,
            originY = -(pad + ascent),
            char = char,
            textSize = textSize,
            color = color,
            innerRadiusPx = innerRadiusPx,
            outerRadiusPx = outerRadiusPx,
        )
        if (entries.size >= MAX_ENTRIES) {
            // ⚠️ **只丢引用、不 recycle**（见类 KDoc「为什么不 recycle()」）
            entries.removeAt(0)
        }
        entries.add(entry)
        return entry
    }

    /** 丢弃全部缓存（切歌 / 退出效果时调用） */
    fun clear() {
        // ⚠️ **不 recycle**：被丢掉的位图可能仍被渲染器跨帧持有（离场动画）
        entries.clear()
    }
}
