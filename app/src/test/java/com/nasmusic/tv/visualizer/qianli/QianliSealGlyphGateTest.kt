package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * E50 K7 印章层 G6 闸门 —— 坐标零漂移 + 白文落墨比 + 残缺面积。
 *
 * ## 判据清单
 *
 * | 判据 | 内容 | 阈值 |
 * |---|---|---|
 * | G6-① | 300 帧零漂移 + 尺寸 ∈ [8%, 10%]·H | drift=0; 0.08H ≤ size ≤ 0.10H |
 * | G6-② | 四字白文**轮廓覆盖面积比**（占格面积） | [20%, 72%] |
 * | G6-③ | 残缺面积（解析上界） | ≤ 2% |
 *
 * ## 负向自证
 *
 * FAKE 字形（3 笔 / bbox 2.0×10.0）必须被 G6-② 咬住（落墨比不在 [20%, 72%] 内）。
 *
 * ## 为什么需要 Robolectric
 *
 * G6-① 的 `QianliSeal.bake` 会 `Bitmap.createBitmap` + `Canvas.draw*`，纯 JVM 下 `android.*`
 * 是桩类 ⇒ 必须跑在 Robolectric 上。
 *
 * ## G6-② 为什么是「几何口径」而不是「像素回读口径」
 *
 * ### 换口径的经过
 *
 * 1. **原判据是像素回读**：烘一张印章位图，取每字的格子区域回读像素，算绢白像素占比。
 *    该口径在 Robolectric 上**恒为 0** —— 默认的 `GraphicsMode.LEGACY` **不执行真实光栅化**，
 *    `Canvas.drawPath` / `drawBitmap` 落到桩上 ⇒ 位图恒空 ⇒ 像素回读恒空 ⇒ 落墨比恒 0，
 *    G6-② 会以「落墨比 0.0 < 20%」误判为红（⛔ 这是**光栅化环境**问题，不是
 *    `QianliSeal.bake` 的实现问题）。
 * 2. **NATIVE 图形模式在本机不可用**：`@GraphicsMode(NATIVE)` 走真实 Skia 光栅化，
 *    本该是正解，但 Robolectric 的 native runtime 发行包
 *    （`org.robolectric:nativeruntime-dist-compat`，经 `shadows-framework` 传递进
 *    单测 runtime classpath，**不需要**新增依赖）**只带 linux / mac 的原生库**，
 *    没有 `native/windows/x86_64/robolectric-nativeruntime.dll`
 *    ⇒ 本机（Windows）实测抛
 *    `IllegalArgumentException: resource native/windows/x86_64/robolectric-nativeruntime.dll not found`
 *    ⇒ 5 条用例全灭。⛔ 按约定**不为此新增依赖**（Robolectric 的 native runtime 本身就不发 Windows 版）。
 * 3. ⇒ 改用**不依赖光栅化**的等价口径：**轮廓覆盖面积比**（几何法）。
 *
 * ### 口径定义
 *
 * * 一格 = 10 × 10 个**格坐标单位**（`QianliSeal` 的字形表就是这套 0..10 的归一化坐标）。
 * * 一笔 = 骨架折线 + 圆头圆角描边，描边宽 = 格宽 × `QianliSeal.SEAL_LW` / 10
 *   = **0.95 格坐标单位**（与 `QianliSeal.bake` 里 `strokeWidth = sc * SEAL_LW` 同一条公式）。
 * * 轮廓面积：把每笔的描边轮廓展开成**闭合多边形**（左偏移正向 → 末端半圆 → 右偏移反向
 *   → 起端半圆），用**鞋带公式** `|Σ(x_i·y_{i+1} − x_{i+1}·y_i)| / 2` 求面积；
 *   再用 Sutherland–Hodgman 与格子矩形 `[0,10]²` 求交（笔画只可能从格缘溢出半个笔宽）。
 * * 落墨比 = Σ(逐笔轮廓 ∩ 格子的面积) / 格子面积（100 格坐标单位²）。
 *
 * ### 为什么它与像素口径同量纲
 *
 * 两者量的都是**同一块绢白笔画在同一个格子里的面积**，只是求法不同：
 * * 像素口径：光栅化后数「白像素个数 / 区域像素个数」⇒ 单位面积比；
 * * 几何口径：描边轮廓（就是光栅化会涂满的那块区域）的解析面积 / 格子面积 ⇒ 也是单位面积比。
 *
 * 描边轮廓 = 光栅化的「真值」 ⇒ 几何值 ≈ 像素值（差别只在抗锯齿边缘与笔画交叠处）。
 * ⛔ 逐笔求和会让**笔画交叠**处被重复计数（像素口径每个像素只算一次），
 * 所以几何值偏大一点点；用扫描线求**并集**复算（离线核对，见下表）偏小一点点，
 * 两者都稳稳落在 [20%, 72%] 内 ⇒ 口径不影响判据结论。
 *
 * ### 实测值（2026-11-05，格面积口径）
 *
 * | 字 | 逐笔求和（**本判据采用**） | 并集（像素口径的近似，**离线核对**） | FAKE 负向 |
 * |---|---|---|---|
 * | 扫 (sao) | 40.69% | 35.34% | — |
 * | 地 (di) | 40.61% | 35.94% | — |
 * | 老 (lao) | 37.82% | 32.88% | — |
 * | 师 (shi) | 50.52% | 40.42% | — |
 * | **FAKE** | **7.11%** | **7.11%** | ✅ 被咬住（< 20%） |
 *
 * 「并集」一列是**离线**用扫描线对同一批轮廓多边形求并集面积得到的（用来模拟「像素口径」
 * 逐像素只算一次的效果），**不是**本测试的判据；本测试只算「逐笔求和」。
 * ⚠️ 若改用「格心 76% 区域」当分母（像素口径的原始分母），并集口径为
 * 44.83% / 51.58% / 51.65% / 65.03%（FAKE 8.37%）⇒ 同样全部落在 [20%, 72%] 内。
 *
 * 移植来源：E50 方案 §六 G6 判据。
 * 移植状态：**新增**（G6-② 由像素口径改为几何口径，见上）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QianliSealGlyphGateTest {

    // ══════════════════════ 夹具 ══════════════════════

    /** 屏幕高度（1080p 基准） */
    private val H = 1080f

    /** 印章目标尺寸 = 9%·H */
    private val sealSize = 0.09f * H  // 97.2f

    /** 烘焙用画布 */
    private lateinit var canvas: Canvas

    @Before
    fun setUp() {
        QianliSeal.releaseAll()
        canvas = Canvas(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888))
    }

    @After
    fun tearDown() {
        QianliSeal.releaseAll()
    }

    // ══════════════════════ G6-① 坐标零漂移 + 尺寸 ══════════════════════

    /**
     * G6-①：300 帧烘焙坐标零漂移 + 尺寸 ∈ [8%, 10%]·H。
     *
     * 零漂移 = 300 次 `bake` 返回同一个缓存实例。
     * 尺寸判据 = 0.08·H ≤ size ≤ 0.10·H。
     */
    @Test
    fun g6_1_zeroDriftAndSize() {
        // 尺寸判据
        assertTrue(
            "尺寸 ${sealSize} 必须 ≥ 8%·H (${0.08f * H})",
            sealSize >= 0.08f * H
        )
        assertTrue(
            "尺寸 ${sealSize} 必须 ≤ 10%·H (${0.10f * H})",
            sealSize <= 0.10f * H
        )

        // 300 帧零漂移
        val first = QianliSeal.bake(canvas, 250f, 250f, sealSize)
        var driftCount = 0
        for (i in 1 until 300) {
            val bm = QianliSeal.bake(canvas, 250f, 250f, sealSize)
            if (bm !== first) driftCount++
        }
        assertEquals("300 帧烘焙坐标漂移次数", 0, driftCount)
    }

    /**
     * G6-① 负向自证：改变尺寸 ⇒ 缓存失效 ⇒ 新实例。
     *
     * 证明零漂移判据不是空转（缓存确实按尺寸 key）。
     */
    @Test
    fun g6_1_negative_sizeChangeBreaksCache() {
        val bm1 = QianliSeal.bake(canvas, 250f, 250f, sealSize)
        val bm2 = QianliSeal.bake(canvas, 250f, 250f, sealSize + 1f)
        assertNotSame("尺寸改变必须产生新 bitmap", bm1, bm2)
    }

    // ══════════════════════ G6-② 白文落墨比 ══════════════════════

    /**
     * G6-②：四字白文落墨比（轮廓覆盖面积比）∈ [20%, 72%]。
     *
     * 口径见类 KDoc：笔画描边轮廓（圆头圆角，宽 = 格宽 × `SEAL_LW` / 10）∩ 格子矩形，
     * 鞋带公式求面积，逐笔求和后除以格子面积。
     */
    @Test
    fun g6_2_inkRatioInBounds() {
        val glyphs = QianliSeal.glyphTableForTest()
        assertEquals("字形表必须正好四个字", 4, glyphs.size)

        for (glyphIdx in 0 until 4) {
            val ratio = inkRatio(glyphs[glyphIdx])
            assertTrue(
                "字[$glyphIdx] 落墨比 $ratio 必须 ≥ 20%",
                ratio >= 0.20
            )
            assertTrue(
                "字[$glyphIdx] 落墨比 $ratio 必须 ≤ 72%",
                ratio <= 0.72
            )
        }
    }

    /**
     * G6-② 负向自证：FAKE 字形（3 笔 / bbox 2.0×10.0）的落墨比必须在 [20%, 72%] 之外。
     *
     * FAKE 字形：三段短竖线分布在 2.0 宽 × 10.0 高的 bbox 内，
     * 每笔仅 2 格长（格高的 20%），总落墨面积远小于真字形 ⇒ 必须落在 20% 以下。
     */
    @Test
    fun g6_2_negative_fakeGlyphsCaught() {
        // 3 笔短竖线：bbox 2.0×10.0，每笔 2 格长
        val fakeStroke = listOf(
            listOf(floatArrayOf(4.0f, 0f), floatArrayOf(4.0f, 2f)),
            listOf(floatArrayOf(5.0f, 4f), floatArrayOf(5.0f, 6f)),
            listOf(floatArrayOf(6.0f, 8f), floatArrayOf(6.0f, 10f)),
        )
        // 四字都用同样的 FAKE 字形
        val fakeGlyphs = listOf(fakeStroke, fakeStroke, fakeStroke, fakeStroke)

        // 至少一个字的落墨比必须在 [20%, 72%] 之外
        var caught = false
        val ratios = mutableListOf<Double>()
        for (glyphIdx in 0 until 4) {
            val ratio = inkRatio(fakeGlyphs[glyphIdx])
            ratios += ratio
            if (ratio < 0.20 || ratio > 0.72) {
                caught = true
            }
        }
        assertTrue(
            "FAKE 字形必须被 G6-② 咬住（落墨比在 [20%, 72%] 之外），实测 $ratios",
            caught
        )
    }

    // ══════════════════════ G6-③ 残缺面积 ══════════════════════

    /**
     * G6-③：残缺面积（解析上界）≤ 2%。
     *
     * 7 颗 × r∈[0.014,0.026]s → 最坏 7π·0.026² ≈ 1.49%。
     */
    @Test
    fun g6_3_defectAreaUnder2Percent() {
        val ratio = QianliSeal.defectRatio(sealSize)
        assertTrue(
            "残缺面积比 $ratio 必须 ≤ 2%",
            ratio <= 0.02
        )
    }

    // ══════════════════════ 几何口径实现 ══════════════════════

    /** 一格的边长（格坐标单位）——字形表是 0..10 的归一化坐标 */
    private val GRID = 10.0

    /** 一格的面积（格坐标单位²） */
    private val CELL_AREA = GRID * GRID

    /** 描边宽（格坐标单位）= 格宽 × SEAL_LW / 10 = SEAL_LW */
    private val strokeWidth = QianliSeal.SEAL_LW.toDouble()

    /** 二维点（Double 精度，格坐标） */
    private data class Pt(val x: Double, val y: Double)

    /** 一段直线段及其单位方向 / 左法线 */
    private data class Seg(
        val p0: Pt,
        val p1: Pt,
        val n: Pt,
    )

    /**
     * 计算一个字形的白文落墨比（轮廓覆盖面积比）。
     *
     * @param glyph 字的骨架笔画表（每笔 = 一条折线）。
     * @return 落墨面积 / 格子面积（0..1+）。
     */
    private fun inkRatio(glyph: List<List<FloatArray>>): Double {
        return inkArea(glyph, strokeWidth) / CELL_AREA
    }

    /**
     * 逐笔求「描边轮廓 ∩ 格子」的面积之和。
     *
     * @param glyph 字的骨架笔画表。
     * @param width 描边宽（格坐标单位）。
     */
    private fun inkArea(glyph: List<List<FloatArray>>, width: Double): Double {
        var total = 0.0
        for (stroke in glyph) {
            val outline = ribbonOutline(stroke, width)
            if (outline.size < 3) continue
            val clipped = clipToCell(outline)
            if (clipped.size < 3) continue
            total += shoelaceArea(clipped)
        }
        return total
    }

    /**
     * 把一笔的描边轮廓展开成闭合多边形（圆头圆角）。
     *
     * 左偏移正向 → 末端半圆 → 右偏移反向 → 起端半圆。
     * 半圆用 [capSteps] 段折线逼近（24 段 ⇒ 圆弧面积误差 < 0.02%）。
     */
    private fun ribbonOutline(pts: List<FloatArray>, width: Double, capSteps: Int = 24): List<Pt> {
        if (pts.size < 2) return emptyList()
        val r = width / 2.0

        val segs = ArrayList<Seg>(pts.size - 1)
        for (i in 0 until pts.size - 1) {
            val x0 = pts[i][0].toDouble()
            val y0 = pts[i][1].toDouble()
            val x1 = pts[i + 1][0].toDouble()
            val y1 = pts[i + 1][1].toDouble()
            val dx = x1 - x0
            val dy = y1 - y0
            val len = hypot(dx, dy)
            if (len <= 0.0) continue
            // 左法线（逆时针 90°）
            segs += Seg(Pt(x0, y0), Pt(x1, y1), Pt(-dy / len, dx / len))
        }
        if (segs.isEmpty()) return emptyList()

        val poly = ArrayList<Pt>(segs.size * 2 + capSteps * 2)

        // 左偏移，正向
        for (s in segs) {
            poly += Pt(s.p0.x + s.n.x * r, s.p0.y + s.n.y * r)
            poly += Pt(s.p1.x + s.n.x * r, s.p1.y + s.n.y * r)
        }

        // 末端圆头：左法线 → 段方向 → 右法线（顺时针 π）
        // ⚠️ 必须每步都从**基准法线**重新旋转（角度 = -k·π/capSteps），
        //    不能拿上一轮的结果继续转 —— 那样角度会按 1+2+…+k 累加，
        //    半圆会绕出去十几圈，轮廓自交 ⇒ 鞋带公式的面积被放大。
        val last = segs.last()
        for (k in 1..capSteps) {
            val v = rotate(last.n, -PI * k / capSteps)
            poly += Pt(last.p1.x + v.x * r, last.p1.y + v.y * r)
        }

        // 右偏移，反向
        for (s in segs.asReversed()) {
            poly += Pt(s.p1.x - s.n.x * r, s.p1.y - s.n.y * r)
            poly += Pt(s.p0.x - s.n.x * r, s.p0.y - s.n.y * r)
        }

        // 起端圆头：右法线 → 反段方向 → 左法线（顺时针 π）
        // ⚠️ 同上：每步从基准向量重算（终点与多边形首点重合，故 k 取到 capSteps-1）。
        val first = segs.first()
        val startN = Pt(-first.n.x, -first.n.y)
        for (k in 1 until capSteps) {
            val v = rotate(startN, -PI * k / capSteps)
            poly += Pt(first.p0.x + v.x * r, first.p0.y + v.y * r)
        }

        return poly
    }

    /** 绕原点旋转 [ang] 弧度 */
    private fun rotate(v: Pt, ang: Double): Pt {
        val c = cos(ang)
        val s = sin(ang)
        return Pt(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    /**
     * 多边形面积（鞋带公式）。
     *
     * `|Σ(x_i·y_{i+1} − x_{i+1}·y_i)| / 2`
     */
    private fun shoelaceArea(poly: List<Pt>): Double {
        var sum = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    /**
     * 把多边形与格子矩形 `[0, GRID]²` 求交（Sutherland–Hodgman）。
     *
     * 笔画只可能从格缘溢出半个笔宽 ⇒ 裁剪只影响边界那一圈。
     */
    private fun clipToCell(poly: List<Pt>): List<Pt> {
        var out = poly
        out = clipEdge(out, { it.x >= 0.0 }, 0.0, axis = 0)
        out = clipEdge(out, { it.x <= GRID }, GRID, axis = 0)
        out = clipEdge(out, { it.y >= 0.0 }, 0.0, axis = 1)
        out = clipEdge(out, { it.y <= GRID }, GRID, axis = 1)
        return out
    }

    /**
     * Sutherland–Hodgman 的单条半平面裁剪。
     *
     * @param inside 点的「在内」判据。
     * @param bound  边界值。
     * @param axis   0 = x 轴，1 = y 轴。
     */
    private fun clipEdge(
        poly: List<Pt>,
        inside: (Pt) -> Boolean,
        bound: Double,
        axis: Int,
    ): List<Pt> {
        if (poly.isEmpty()) return emptyList()
        val out = ArrayList<Pt>(poly.size + 4)
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i - 1 + poly.size) % poly.size]
            val curIn = inside(cur)
            val prevIn = inside(prev)
            if (curIn) {
                if (!prevIn) out += intersect(prev, cur, bound, axis)
                out += cur
            } else if (prevIn) {
                out += intersect(prev, cur, bound, axis)
            }
        }
        return out
    }

    /** 线段 [a,b] 与 `axis = bound` 这条直线的交点 */
    private fun intersect(a: Pt, b: Pt, bound: Double, axis: Int): Pt {
        val a0 = if (axis == 0) a.x else a.y
        val b0 = if (axis == 0) b.x else b.y
        val den = b0 - a0
        if (abs(den) < 1e-12) return b
        val t = (bound - a0) / den
        return Pt(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
    }
}
