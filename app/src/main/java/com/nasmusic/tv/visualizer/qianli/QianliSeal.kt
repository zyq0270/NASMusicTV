package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.nasmusic.tv.visualizer.qianli.port.ShanShuiPrng
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * E50「千里江山」K7 印章层 —— 四字缪篆白文印的烘焙。
 *
 * ## 移植来源
 * - v1.x 原型 HTML：`docs/archive/e50-qianli-jiangshan-preview.html` 行 545-667
 * - E50 方案 §六（印章层）
 *
 * ## 移植状态：**照抄**
 * 逐行移植原型 `bakeSeal` 的烘焙逻辑；颜色值照抄（从 HTML `C.cin1/C.cin2/C.silkHi` 转换）。
 * ⛔ 不用字体/位图/生成式图像；⛔ PRNG 固定种子 31337。
 *
 * ## 逐帧零分配
 * `bake` 结果缓存（`cachedBitmap` + `cachedSize`），运行时只 blit。
 */
object QianliSeal {

    // ══════════════════════ 常量 ══════════════════════

    /** 笔画宽 = 格宽 × SEAL_LW / 10 */
    const val SEAL_LW = 0.95f

    /** 烘焙超采样倍数 */
    const val SUPER_SAMPLE = 2

    /** 做旧残缺数量 */
    const val DEFECT_COUNT = 7

    /** 残缺圆半径下限（比例 s） */
    const val DEFECT_R_MIN = 0.014f

    /** 残缺圆半径上限（比例 s） */
    const val DEFECT_R_MAX = 0.026f

    // 朱砂红底（cin1）
    private const val CIN1_R = 166; private const val CIN1_G = 58; private const val CIN1_B = 43
    // 印泥不均亮区（cin2）
    private const val CIN2_R = 186; private const val CIN2_G = 78; private const val CIN2_B = 63
    // 绢白字（silkHi）
    private const val SILK_HI_R = 240; private const val SILK_HI_G = 236; private const val SILK_HI_B = 224

    // ══════════════════════ 字形数据 ══════════════════════

    /**
     * 四字缪篆顶点表 —— 手工描摹（R-C 路线，方案 §6.1）。
     *
     * 每字 = List<stroke>，每 stroke = List<Point>，Point = FloatArray(2) = [x, y]
     * （归一化 0..10 格）。
     *
     * 印序：右上「扫」→ 右下「地」→ 左上「老」→ 左下「师」（传统读序）。
     *
     * ⚠️ 上一版表是凭印象编的，放大判读出来是四条不相干的弯钩；本版逐字对照
     * 结构参考重描（`logs_temp/e50_glyph_ref.mjs` 把系统字面降采样成 34×34 ASCII
     * 占据图，据此手写坐标）。
     * ⚠️ 笔画宽 0.95：1.15 时竖列上 3~4 笔之间的留白被填满（放大判读成糊块），
     * 先退到 0.95 再看；这个数和字形表一起归所有者判读。
     */
    private val glyphs: List<List<List<FloatArray>>> = listOf(
        // 扫(掃) sao —— 扌：竖钩 + 短横 + 提；帚侧：三横一竖
        listOf(
            listOf(floatArrayOf(1.8f, 0.9f), floatArrayOf(1.8f, 7.4f), floatArrayOf(1.7f, 8.3f), floatArrayOf(0.9f, 7.7f)),
            listOf(floatArrayOf(0.4f, 3.3f), floatArrayOf(3.2f, 3.2f)),
            listOf(floatArrayOf(0.5f, 8.4f), floatArrayOf(3.2f, 7.0f)),
            listOf(floatArrayOf(4.0f, 2.3f), floatArrayOf(9.4f, 2.2f)),
            listOf(floatArrayOf(4.2f, 4.9f), floatArrayOf(9.4f, 4.8f)),
            listOf(floatArrayOf(4.0f, 7.5f), floatArrayOf(9.5f, 7.4f)),
            listOf(floatArrayOf(8.9f, 2.2f), floatArrayOf(8.9f, 9.5f)),
        ),
        // 地 di —— 土旁：竖 + 短横 + 提；也：横折钩 + 短竖 + 竖弯钩
        listOf(
            listOf(floatArrayOf(1.7f, 1.0f), floatArrayOf(1.7f, 7.6f)),
            listOf(floatArrayOf(0.4f, 3.6f), floatArrayOf(3.3f, 3.5f)),
            listOf(floatArrayOf(0.4f, 8.7f), floatArrayOf(3.4f, 7.2f)),
            listOf(floatArrayOf(3.7f, 3.4f), floatArrayOf(8.6f, 3.3f), floatArrayOf(8.6f, 6.8f), floatArrayOf(7.8f, 6.2f)),
            listOf(floatArrayOf(4.4f, 2.0f), floatArrayOf(4.4f, 6.8f)),
            listOf(floatArrayOf(5.9f, 1.0f), floatArrayOf(5.9f, 7.2f), floatArrayOf(6.7f, 8.2f), floatArrayOf(9.6f, 8.2f), floatArrayOf(9.7f, 7.1f)),
        ),
        // 老 lao —— 横 + 竖 + 横 + 长撇 + 匕（短撇 + 竖弯钩）
        listOf(
            listOf(floatArrayOf(2.4f, 2.7f), floatArrayOf(7.4f, 2.6f)),
            listOf(floatArrayOf(4.7f, 1.2f), floatArrayOf(4.7f, 4.6f)),
            listOf(floatArrayOf(1.0f, 4.4f), floatArrayOf(9.0f, 4.3f)),
            listOf(floatArrayOf(7.6f, 2.2f), floatArrayOf(6.2f, 4.0f), floatArrayOf(4.2f, 5.9f), floatArrayOf(1.2f, 8.2f)),
            listOf(floatArrayOf(7.2f, 5.2f), floatArrayOf(5.6f, 6.8f), floatArrayOf(4.8f, 7.4f)),
            listOf(floatArrayOf(3.8f, 6.8f), floatArrayOf(3.7f, 8.5f), floatArrayOf(6.0f, 8.8f), floatArrayOf(7.3f, 8.5f), floatArrayOf(7.4f, 7.0f)),
        ),
        // 师(師) shi —— 𠂤：撇 + 方框；帀：长横 + 巾
        listOf(
            listOf(floatArrayOf(2.9f, 1.0f), floatArrayOf(2.0f, 2.6f)),
            listOf(floatArrayOf(1.6f, 2.7f), floatArrayOf(1.6f, 8.1f)),
            listOf(floatArrayOf(1.6f, 2.7f), floatArrayOf(4.2f, 2.7f), floatArrayOf(4.2f, 8.1f)),
            listOf(floatArrayOf(1.6f, 5.4f), floatArrayOf(4.2f, 5.4f)),
            listOf(floatArrayOf(1.6f, 8.1f), floatArrayOf(4.2f, 8.1f)),
            listOf(floatArrayOf(4.7f, 2.3f), floatArrayOf(9.3f, 2.2f)),
            listOf(floatArrayOf(5.2f, 3.8f), floatArrayOf(5.2f, 7.4f)),
            listOf(floatArrayOf(5.2f, 3.8f), floatArrayOf(8.4f, 3.7f), floatArrayOf(8.4f, 7.4f)),
            listOf(floatArrayOf(5.2f, 7.4f), floatArrayOf(8.4f, 7.4f)),
            listOf(floatArrayOf(6.8f, 2.2f), floatArrayOf(6.8f, 9.2f)),
        ),
    )

    /**
     * 印序：[扫(右上,col=1,row=0), 地(右下,col=1,row=1), 老(左上,col=0,row=0), 师(左下,col=0,row=1)]。
     * col=1 右列, col=0 左列; row=0 上行, row=1 下行。
     */
    private val SEAL_LAYOUT = listOf(
        Triple(0, 1, 0),  // sao
        Triple(1, 1, 1),  // di
        Triple(2, 0, 0),  // lao
        Triple(3, 0, 1),  // shi
    )

    // ══════════════════════ 缓存 ══════════════════════

    private var cachedBitmap: Bitmap? = null
    private var cachedSize: Int = 0

    // ══════════════════════ 烘焙 ══════════════════════

    /**
     * 烘焙印章到 [canvas] 的 [centerX], [centerY] 位置，最终尺寸 [size]。
     *
     * 内部用 [SUPER_SAMPLE] 超采样后缩小（抗锯齿）。
     *
     * @param canvas   目标画布。
     * @param centerX  印章中心 X。
     * @param centerY  印章中心 Y。
     * @param size     印章边长（最终尺寸，非超采样）。
     * @return 烘焙后的 [Bitmap]（缓存复用）。
     *
     * 移植来源：v1.x 原型 HTML 行 607-667 `bakeSeal`。
     * 移植状态：**照抄**。
     */
    fun bake(canvas: Canvas, centerX: Float, centerY: Float, size: Float): Bitmap {
        val sizePx = size.roundToInt().coerceAtLeast(1)
        val bitmap = checkCache(sizePx) ?: doBake(sizePx)
        val left = centerX - sizePx / 2f
        val top = centerY - sizePx / 2f
        canvas.drawBitmap(bitmap, left, top, null)
        return bitmap
    }

    /**
     * 释放缓存。
     *
     * 移植来源：E50 方案 §六。
     * 移植状态：**新增**（Kotlin 端缓存管理）。
     */
    fun releaseAll() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedSize = 0
    }

    /**
     * 供测试用：获取残缺面积比（解析上界）。
     *
     * 返回 `lostArea / (size * size)`，其中 `lostArea = Σ π·r²`。
     * 7 颗 × r∈[0.014,0.026]s → 最坏 7π·0.026² ≈ 1.49%。
     *
     * 固定种子 ⇒ 确定值（用 [ShanShuiPrng] seed=31337）。
     *
     * 移植来源：v1.x 原型 HTML 行 652-664 残缺计算。
     * 移植状态：**照抄**（解析上界，与原型注释一致）。
     */
    fun defectRatio(size: Float): Double {
        var lost = 0.0
        val rng = ShanShuiPrng()
        rng.seed(31337.0)
        for (i in 0 until DEFECT_COUNT) {
            val rr = size * (DEFECT_R_MIN + rng.rnd() * (DEFECT_R_MAX - DEFECT_R_MIN))
            lost += PI * rr * rr
        }
        return lost / (size * size)
    }

    // ══════════════════════ 测试钩子 ══════════════════════

    /**
     * 供测试用：烘焙自定义字形的印章（不走缓存）。
     *
     * @param customGlyphs 四字顶点表（与 [glyphs] 同构）。
     * @param sizePx       印章边长（最终尺寸）。
     * @return 烘焙后的 [Bitmap]。
     *
     * 移植来源：E50 G6 负向自证需求。
     * 移植状态：**新增**（测试钩子）。
     */
    internal fun bakeWithGlyphs(customGlyphs: List<List<List<FloatArray>>>, sizePx: Int): Bitmap {
        return doBakeWithGlyphs(customGlyphs, sizePx)
    }

    /**
     * 供测试用：获取四字缪篆顶点表（只读副本）。
     *
     * G6-② 的「白文落墨比」在 Robolectric 上**不能**用像素回读算（默认 LEGACY 模式不
     * 光栅化 ⇒ 位图恒空），改用「轮廓覆盖面积比」的几何口径 ⇒ 需要拿到字形骨架。
     * 本钩子是**只读**的：返回深拷贝，调用方改动副本不会污染 [glyphs]。
     *
     * ⛔ 只为测试钩子；生产代码不得调用。
     * ⛔ **不改字形表本身，也不改 [SEAL_LW]** —— 本函数只是把现有的 [glyphs] 暴露出来。
     *
     * 移植来源：E50 G6 负向自证与几何口径需求。
     * 移植状态：**新增**（测试钩子）。
     */
    internal fun glyphTableForTest(): List<List<List<FloatArray>>> =
        glyphs.map { glyph -> glyph.map { stroke -> stroke.map { p -> floatArrayOf(p[0], p[1]) } } }

    /**
     * 供测试用：获取第 [glyphIdx] 个字在最终尺寸下的格子矩形。
     *
     * @param size     印章边长（最终尺寸）。
     * @param glyphIdx 字形索引（0=扫, 1=地, 2=老, 3=师）。
     * @return 格子矩形（最终 bitmap 坐标空间）。
     *
     * 移植来源：E50 G6-② 像素回读需求。
     * 移植状态：**新增**（测试钩子）。
     */
    internal fun cellBounds(size: Float, glyphIdx: Int): RectF {
        val sizePx = size.roundToInt().coerceAtLeast(1)
        val SS = SUPER_SAMPLE
        val s = (sizePx * SS).coerceAtLeast(1)
        val pad = s * 0.03f
        val cell = (s - 2 * pad) * 0.46f
        val gapX = (s - 2 * pad) * 0.5f
        val gapY = (s - 2 * pad) * 0.5f
        val (_, col, row) = SEAL_LAYOUT[glyphIdx]
        val gx = pad + gapX * col + gapX * 0.5f
        val gy = pad + gapY * row + gapY * 0.5f
        val x = (gx - cell / 2f) / SS
        val y = (gy - cell / 2f) / SS
        val w = cell / SS
        val h = cell / SS
        return RectF(x, y, x + w, y + h)
    }

    // ══════════════════════ 内部实现 ══════════════════════

    private fun checkCache(sizePx: Int): Bitmap? {
        if (cachedBitmap != null && cachedSize == sizePx && !cachedBitmap!!.isRecycled) {
            return cachedBitmap
        }
        return null
    }

    private fun doBake(sizePx: Int): Bitmap = doBakeInternal(glyphs, sizePx, cacheResult = true)

    private fun doBakeWithGlyphs(customGlyphs: List<List<List<FloatArray>>>, sizePx: Int): Bitmap =
        doBakeInternal(customGlyphs, sizePx, cacheResult = false)

    private fun doBakeInternal(glyphSet: List<List<List<FloatArray>>>, sizePx: Int, cacheResult: Boolean): Bitmap {
        val SS = SUPER_SAMPLE
        val s = (sizePx * SS).coerceAtLeast(1)

        val ssBitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(ssBitmap)

        val rng = ShanShuiPrng()
        rng.seed(31337.0)

        // ── 1. 朱砂底（边缘微抖 22 段 path）──────────────────────
        val pad = s * 0.03f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(CIN1_R, CIN1_G, CIN1_B)
        }
        val bgPath = Path()
        val seg = 22
        val jit = { ((rng.rnd() - 0.5f) * s * 0.012f).toFloat() }
        // 上边：左→右
        for (i in 0..seg) {
            val t = i.toFloat() / seg
            val x = pad + (s - 2 * pad) * t
            val y = pad + jit()
            if (i == 0) bgPath.moveTo(x, y) else bgPath.lineTo(x, y)
        }
        // 右边：上→下
        for (i in 0..seg) {
            val t = i.toFloat() / seg
            bgPath.lineTo(s - pad + jit(), pad + (s - 2 * pad) * t)
        }
        // 下边：右→左
        for (i in 0..seg) {
            val t = i.toFloat() / seg
            bgPath.lineTo(s - pad - (s - 2 * pad) * t, s - pad + jit())
        }
        // 左边：下→上
        for (i in 0..seg) {
            val t = i.toFloat() / seg
            bgPath.lineTo(pad + jit(), s - pad - (s - 2 * pad) * t)
        }
        bgPath.close()
        c.drawPath(bgPath, bgPaint)

        // ── 2. 印泥不均（12 处径向渐变）────────────────────────
        val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        for (i in 0 until 12) {
            val cx = (rng.rnd() * s).toFloat()
            val cy = (rng.rnd() * s).toFloat()
            val rr = (s * (0.06f + rng.rnd() * 0.18f)).toFloat()
            val alpha = (0.16 + rng.rnd() * 0.16).toFloat()
            val centerColor = Color.argb((alpha * 255).toInt(), CIN2_R, CIN2_G, CIN2_B)
            val edgeColor = Color.argb(0, CIN1_R, CIN1_G, CIN1_B)
            val shader = RadialGradient(
                cx, cy, rr,
                intArrayOf(centerColor, edgeColor),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            gradPaint.shader = shader
            c.drawCircle(cx, cy, rr, gradPaint)
        }
        gradPaint.shader = null

        // ── 3. 四字白文（绢白笔画）─────────────────────────────
        val cell = (s - 2 * pad) * 0.46f
        val gapX = (s - 2 * pad) * 0.5f
        val gapY = (s - 2 * pad) * 0.5f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb((0.95f * 255).toInt(), SILK_HI_R, SILK_HI_G, SILK_HI_B)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val strokePath = Path()

        for ((glyphIdx, col, row) in SEAL_LAYOUT) {
            val gx = pad + gapX * col + gapX * 0.5f
            val gy = pad + gapY * row + gapY * 0.5f
            val sc = cell / 10f
            strokePaint.strokeWidth = max(1f, sc * SEAL_LW)

            for (pts in glyphSet[glyphIdx]) {
                // 坐标转换：格坐标 → SS 像素坐标
                strokePath.reset()
                buildSmoothPath(strokePath, pts, gx - cell / 2f, gy - cell / 2f, sc)
                c.drawPath(strokePath, strokePaint)
            }
        }

        // ── 4. 井栏（十字界格）────────────────────────────────
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb((0.55f * 255).toInt(), SILK_HI_R, SILK_HI_G, SILK_HI_B)
            strokeWidth = max(1f, s * 0.012f)
            strokeCap = Paint.Cap.ROUND
        }
        val crossPath = Path()
        crossPath.moveTo(s / 2f, pad * 1.4f)
        crossPath.lineTo(s / 2f, s - pad * 1.4f)
        crossPath.moveTo(pad * 1.4f, s / 2f)
        crossPath.lineTo(s - pad * 1.4f, s / 2f)
        c.drawPath(crossPath, crossPaint)

        // ── 5. 残缺（7 颗 destination-out 圆）─────────────────
        val defectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        for (i in 0 until DEFECT_COUNT) {
            val edge = rng.rnd()
            val rr = (s * (DEFECT_R_MIN + rng.rnd() * (DEFECT_R_MAX - DEFECT_R_MIN))).toFloat()
            val cx: Float
            val cy: Float
            if (edge < 0.25) {
                cx = pad
                cy = (rng.rnd() * s).toFloat()
            } else if (edge < 0.5) {
                cx = s - pad
                cy = (rng.rnd() * s).toFloat()
            } else if (edge < 0.75) {
                cx = (rng.rnd() * s).toFloat()
                cy = pad
            } else {
                cx = (rng.rnd() * s).toFloat()
                cy = s - pad
            }
            c.drawCircle(cx, cy, rr, defectPaint)
        }
        defectPaint.xfermode = null

        // ── 6. 超采样缩小到目标尺寸 ───────────────────────────
        val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(result)
        val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, s, s)
        val dstRect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        resultCanvas.drawBitmap(ssBitmap, srcRect, dstRect, filterPaint)

        ssBitmap.recycle()

        // ── 7. 缓存 ───────────────────────────────────────────
        if (cacheResult) {
            cachedBitmap?.recycle()
            cachedBitmap = result
            cachedSize = sizePx
        }
        return result
    }

    /**
     * 构建平滑折线路径（quadraticCurveTo 中点法，与原型 `strokePts` 一致）。
     *
     * 不产生自交 ⇒ 烘焙期实心 path 安全。
     */
    private fun buildSmoothPath(
        path: Path,
        pts: List<FloatArray>,
        offsetX: Float,
        offsetY: Float,
        sc: Float,
    ) {
        if (pts.size < 2) return
        // 转换坐标：格坐标 → 像素坐标
        val px = FloatArray(pts.size)
        val py = FloatArray(pts.size)
        for (i in pts.indices) {
            px[i] = offsetX + pts[i][0] * sc
            py[i] = offsetY + pts[i][1] * sc
        }
        path.moveTo(px[0], py[0])
        for (i in 1 until pts.size - 1) {
            val mx = (px[i] + px[i + 1]) / 2f
            val my = (py[i] + py[i + 1]) / 2f
            path.quadTo(px[i], py[i], mx, my)
        }
        path.lineTo(px[pts.size - 1], py[pts.size - 1])
    }
}
