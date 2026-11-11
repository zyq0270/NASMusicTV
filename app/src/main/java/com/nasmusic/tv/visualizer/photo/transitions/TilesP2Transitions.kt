package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.photo.MaskCache
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * P2 档「分块」族（§14.3）—— 共 8 种
 *
 * | 效果 | 块数上限 | 手法 |
 * |---|---|---|
 * | [GridFlipTransition] | 8×5 | 每块绕竖轴翻转（旧图 → 新图） |
 * | [MosaicTransition] | 10×6 | 每块从中心放大弹出 |
 * | [PixelateTransition] | 10×6 | 每块取**源图 1 个像素**铺成色块，再收成清晰图 |
 * | [PuzzleTransition] | 8×5 | 每块沿自己的方向滑入归位 |
 * | [ShatterTransition] | 10×6 | 旧图碎片向外飞散，露出新图 |
 * | [VoronoiTransition] | 8×5 | 每块朝自己的「发生点」生长（伪 Voronoi） |
 * | [FilmRollTransition] | 1 带 | 竖向卷动 + 片孔齿边 |
 * | [ComicPanelTransition] | 3×2 | 漫画分格 + 粗黑边框 |
 *
 * ## ⛔ M4 红线：总块数 ≤ 84（12×7，§4.2）
 *
 * 每个效果在 `prepare()` 里按图片实际尺寸反推网格并**封顶**（见各类的 `MAX_COLS` / `MAX_ROWS`）。
 *
 * ## ⛔ 绘制路径零分配
 *
 * 每块的源矩形都用**同一个复用实例**算出来（[sourceRegion]），不在循环里 new 对象。
 */

/** 整型散列 → `0..1`（确定性、零分配；不用 `Random`） */
internal fun tileHash(v: Int): Float {
    var h = v * 374761393 + 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777215f
}

/**
 * 把 [dstFull] 里位于 `(x, y, w, h)` 的子区域换算成**源矩形**（写进 [out]，零分配）。
 *
 * ## 为什么需要它
 *
 * 分块效果要画「某一块里的那张图」⇒ 源矩形必须与目标块**对应**，
 * 不能整幅拉伸（否则每块都是完整的缩略图，不是局部）。
 *
 * ⚠️ [dstFull] 必须是**那张图实际占据的矩形**（CROP = 整幅画布；FIT = 内接矩形）——
 * 拿画布尺寸去算，FIT 模式下会算出**越出源图**的源矩形。
 */
internal fun sourceRegion(
    srcFull: PhotoRect,
    dstFull: PhotoRect,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    out: PhotoRect,
) {
    if (dstFull.width <= 0f || dstFull.height <= 0f) {
        out.reset()
        return
    }
    val sx = srcFull.width / dstFull.width
    val sy = srcFull.height / dstFull.height
    out.set(
        srcFull.left + (x - dstFull.left) * sx,
        srcFull.top + (y - dstFull.top) * sy,
        srcFull.left + (x - dstFull.left + w) * sx,
        srcFull.top + (y - dstFull.top + h) * sy,
    )
}

/**
 * 分块类 P2 的公共底座：网格按**图片实际占据的矩形**反推，并封顶。
 *
 * ## 为什么按「图片矩形」而不是「画布」
 *
 * `PhotoScaleMode.CROP`（默认）下图片铺满画布 ⇒ 两者相同；
 * 但 `FIT` 下图片只占**内接矩形**（两侧留黑边）⇒ 按画布分块会算出**越出源图**的源矩形。
 *
 * ⇒ 取 A / B 两个矩形的**交集**（都是居中的 ⇒ 交集就是较小那个），
 * 它同时落在两张图里 ⇒ 两边取源都不会越界 ✓。
 */
internal abstract class TileGridP2Transition : PhotoTransition {

    protected var cols = 1
    protected var rows = 1
    protected var tileW = 0f
    protected var tileH = 0f

    /** 分块所依据的矩形（A / B 实际占据区的交集） */
    protected val area = PhotoRect()

    /** 列数上限（M4 红线） */
    protected abstract fun maxCols(): Int

    /** 行数上限（M4 红线） */
    protected abstract fun maxRows(): Int

    /** 目标块边长（px）—— 决定网格密度 */
    protected abstract fun tilePx(): Float

    /**
     * 实际列数 —— 默认按 [tilePx] 从 [area] 反推并封顶。
     *
     * ⚠️ 固定格数的效果（如漫画分格 3×2）覆写这里。
     */
    protected open fun gridCols(): Int = (area.width / tilePx()).toInt().coerceIn(1, maxCols())

    /** 实际行数（默认同 [gridCols]） */
    protected open fun gridRows(): Int = (area.height / tilePx()).toInt().coerceIn(1, maxRows())

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        val a = geom.dstA
        val b = geom.dstB
        area.set(
            maxOf(a.left, b.left),
            maxOf(a.top, b.top),
            minOf(a.right, b.right),
            minOf(a.bottom, b.bottom),
        )
        cols = gridCols()
        rows = gridRows()
        tileW = area.width / cols
        tileH = area.height / rows
    }
}

/**
 * 网格 3D 翻转（P2）—— 每块绕**竖轴**翻过去。
 *
 * ## 假 3D
 *
 * 真 3D 需要透视变换（红线：零 OpenGL）⇒ 这里用「水平方向压扁」模拟转轴：
 * 前半程画**旧图**的该块（宽度 `1 → 0`），后半程画**新图**的该块（宽度 `0 → 1`）。
 */
internal class GridFlipTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.GRID_FLIP

    /** 复用的源矩形 */
    private val srcTmp = PhotoRect()

    /** 复用的目标矩形 */
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MIN_COLS

    override fun maxRows(): Int = MIN_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - STAGGER
        for (row in 0 until rows) {
            val y0 = area.top + row * tileH
            for (col in 0 until cols) {
                val diag = (col.toFloat() / cols + row.toFloat() / rows) * 0.5f
                val local = ((p - diag * STAGGER) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue

                val x0 = area.left + col * tileW
                val cx = x0 + tileW * 0.5f
                // 0 → 0.5：旧图压扁；0.5 → 1：新图压扁（另一半）
                val squeeze = if (local < 0.5f) 1f - 2f * local else 2f * local - 1f
                val halfW = tileW * 0.5f * squeeze
                if (halfW <= 0.5f) continue

                val img = if (local < 0.5f) a else b
                val src = if (local < 0.5f) geom.srcA else geom.srcB
                val dstFull = if (local < 0.5f) geom.dstA else geom.dstB

                sourceRegion(src, dstFull, x0, y0, tileW, tileH, srcTmp)
                dstTmp.set(cx - halfW, y0, cx + halfW, y0 + tileH)
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(img, srcTmp, dstTmp)
                }
            }
        }
    }

    private companion object {
        /** 目标块数（8×5 = 40，远低于 M4 红线 84） */
        const val MIN_COLS = 8
        const val MIN_ROWS = 5
        const val TILE_PX = 220f
        const val STAGGER = 0.45f
    }
}

/**
 * 马赛克渐显（P2）—— 每块从自己的中心**放大弹出**。
 */
internal class MosaicTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.MOSAIC

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MAX_COLS

    override fun maxRows(): Int = MAX_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val n = cols * rows
        val visible = p * (n + STAGGER * n)
        for (idx in 0 until n) {
            val col = idx % cols
            val row = idx / cols
            val local = ((visible - idx) / SOFT_EDGE).coerceIn(0f, 1f)
            if (local <= 0f) continue

            val x0 = area.left + col * tileW
            val y0 = area.top + row * tileH
            val cx = x0 + tileW * 0.5f
            val cy = y0 + tileH * 0.5f
            // 从 0.35 倍弹出到 1 倍
            val s = 0.35f + 0.65f * local
            sourceRegion(geom.srcB, geom.dstB, x0, y0, tileW, tileH, srcTmp)
            dstTmp.set(
                cx - tileW * 0.5f * s,
                cy - tileH * 0.5f * s,
                cx + tileW * 0.5f * s,
                cy + tileH * 0.5f * s,
            )
            clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                drawPhoto(b, srcTmp, dstTmp)
            }
        }
    }

    private companion object {
        const val MAX_COLS = 10
        const val MAX_ROWS = 6
        const val TILE_PX = 200f
        const val STAGGER = 0.35f
        const val SOFT_EDGE = 2.5f
    }
}

/**
 * 像素化过渡（P2）—— 新图先以**大色块**（每块取源图 1 个像素）显现，
 * 再在末段**收束**成清晰图。
 *
 * ## 「取 1 个像素」怎么做到
 *
 * `drawImage` 的源矩形可以只有 1×1 ⇒ 目标块被涂成那一个像素的颜色 ⇒
 * 这就是**真正的像素化**（不需要逐像素读回，也不需要新建位图）。
 */
internal class PixelateTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.PIXELATE

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MAX_COLS

    override fun maxRows(): Int = MAX_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        // 色块阶段：块越大越「像素化」⇒ 块内取源图中心那一个像素
        val blocks = cols * rows
        val visible = p * blocks
        for (idx in 0 until blocks) {
            val col = idx % cols
            val row = idx / cols
            if (idx > visible) break
            val x0 = area.left + col * tileW
            val y0 = area.top + row * tileH
            // 取该块**中心**对应的源像素
            val px = geom.srcB.left + (x0 + tileW * 0.5f - geom.dstB.left) *
                (geom.srcB.width / geom.dstB.width)
            val py = geom.srcB.top + (y0 + tileH * 0.5f - geom.dstB.top) *
                (geom.srcB.height / geom.dstB.height)
            srcTmp.set(px, py, px + 1f, py + 1f)
            dstTmp.set(x0, y0, x0 + tileW, y0 + tileH)
            drawPhoto(b, srcTmp, dstTmp)
        }

        // 收束：末段把清晰的新图叠回来（末帧必须是清晰图）
        if (p > RESOLVE_FROM) {
            val k = (p - RESOLVE_FROM) / (1f - RESOLVE_FROM)
            drawPhoto(b, geom.srcB, geom.dstB, alpha = k.coerceIn(0f, 1f))
        }
    }

    private companion object {
        const val MAX_COLS = 10
        const val MAX_ROWS = 6
        const val TILE_PX = 200f

        /** 从这个进度开始「收束」成清晰图 */
        const val RESOLVE_FROM = 0.55f
    }
}

/**
 * 拼图碎片（P2）—— 每块沿**自己的方向**滑入归位。
 *
 * 方向由散列决定（确定性、零分配），错落由对角波次决定。
 */
internal class PuzzleTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.PUZZLE

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MAX_COLS

    override fun maxRows(): Int = MAX_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - STAGGER
        for (row in 0 until rows) {
            val y0 = area.top + row * tileH
            for (col in 0 until cols) {
                val idx = row * cols + col
                val diag = (col.toFloat() / cols + row.toFloat() / rows) * 0.5f
                val local = ((p - diag * STAGGER) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue

                // 每块的方向（0..7 八个方向）
                val dir = (tileHash(idx * 17 + 3) * 8f).toInt().coerceIn(0, 7)
                val ang = dir * (Math.PI.toFloat() / 4f)
                val dist = (1f - local) * TRAVEL
                val offX = cos(ang) * dist * geom.canvasW
                val offY = sin(ang) * dist * geom.canvasH

                val x0 = area.left + col * tileW
                sourceRegion(geom.srcB, geom.dstB, x0, y0, tileW, tileH, srcTmp)
                dstTmp.set(
                    geom.dstB.left + offX,
                    geom.dstB.top + offY,
                    geom.dstB.right + offX,
                    geom.dstB.bottom + offY,
                )
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(b, srcTmp, dstTmp)
                }
            }
        }
    }

    private companion object {
        const val MAX_COLS = 8
        const val MAX_ROWS = 5
        const val TILE_PX = 240f
        const val STAGGER = 0.40f

        /** 滑入距离（相对画布尺寸） */
        const val TRAVEL = 0.35f
    }
}

/**
 * 玻璃破碎（P2）—— 旧图的碎片**向外飞散**，露出下面的新图。
 */
internal class ShatterTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.SHATTER

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MAX_COLS

    override fun maxRows(): Int = MAX_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 新图铺底
        drawPhoto(b, geom.srcB, geom.dstB)
        if (p <= 0f) return

        val cx = geom.canvasW * 0.5f
        val cy = geom.canvasH * 0.5f
        val alpha = (1f - p).coerceIn(0f, 1f)

        for (row in 0 until rows) {
            val y0 = area.top + row * tileH
            for (col in 0 until cols) {
                val idx = row * cols + col
                val x0 = area.left + col * tileW
                val bx = x0 + tileW * 0.5f
                val by = y0 + tileH * 0.5f
                // 碎片方向 = 从画面中心向外（加一点散列扰动，避免完全规则）
                val jitter = (tileHash(idx * 29 + 11) - 0.5f) * 0.6f
                var dx = bx - cx
                var dy = by - cy
                val len = sqrt(dx * dx + dy * dy)
                if (len < 0.001f) {
                    dx = tileHash(idx) - 0.5f
                    dy = tileHash(idx + 5) - 0.5f
                } else {
                    dx /= len
                    dy /= len
                }
                dx += jitter * 0.4f
                dy += jitter * 0.4f
                val travel = p * FLY
                val offX = dx * travel * geom.canvasW
                val offY = dy * travel * geom.canvasH

                sourceRegion(geom.srcA, geom.dstA, x0, y0, tileW, tileH, srcTmp)
                dstTmp.set(
                    geom.dstA.left + offX,
                    geom.dstA.top + offY,
                    geom.dstA.right + offX,
                    geom.dstA.bottom + offY,
                )
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(a, srcTmp, dstTmp, alpha = alpha)
                }
            }
        }
    }

    private companion object {
        const val MAX_COLS = 10
        const val MAX_ROWS = 6
        const val TILE_PX = 200f

        /** 飞散距离（相对画布尺寸） */
        const val FLY = 0.55f
    }
}

/**
 * Voronoi 碎片化（P2）—— 每块朝自己的「发生点」**生长**。
 *
 * ## 伪 Voronoi
 *
 * 真正的 Voronoi 需要按最近发生点切分多边形（要 `clipPath`）⇒ 这里用**矩形网格**
 * 近似：给画布撒 12 个发生点，每块取**最近**的那个 ⇒ 每块的生长方向指向它
 * （而真 Voronoi 里每个胞的「发生点」也在胞内、生长方向指向胞心）。
 * 观感上是一堆不规则碎块依次张开 ✓。
 */
internal class VoronoiTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.VORONOI

    /** 发生点（归一化坐标，固定布局 ⇒ 可复现） */
    private val seedsX = FloatArray(SEEDS) { tileHash(it * 13 + 5) }
    private val seedsY = FloatArray(SEEDS) { tileHash(it * 13 + 900) }

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = MAX_COLS

    override fun maxRows(): Int = MAX_ROWS

    override fun tilePx(): Float = TILE_PX

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - STAGGER
        for (row in 0 until rows) {
            val y0 = area.top + row * tileH
            for (col in 0 until cols) {
                val idx = row * cols + col
                val local = ((p - tileHash(idx * 7 + 1) * STAGGER) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue

                val x0 = area.left + col * tileW
                val cx = x0 + tileW * 0.5f
                val cy = y0 + tileH * 0.5f

                // 最近的发生点
                var best = 0
                var bestD = Float.MAX_VALUE
                for (s in 0 until SEEDS) {
                    val dx = seedsX[s] * geom.canvasW - cx
                    val dy = seedsY[s] * geom.canvasH - cy
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d
                        best = s
                    }
                }
                // 生长方向 = 从发生点指向块心
                var dx = cx - seedsX[best] * geom.canvasW
                var dy = cy - seedsY[best] * geom.canvasH
                val len = sqrt(dx * dx + dy * dy)
                if (len > 0.001f) {
                    dx /= len
                    dy /= len
                }

                // 块从发生点方向「长」出来
                val grow = 0.15f + 0.85f * local
                val halfW = tileW * 0.5f * grow
                val halfH = tileH * 0.5f * grow
                val ox = dx * tileW * 0.5f * (1f - grow)
                val oy = dy * tileH * 0.5f * (1f - grow)

                sourceRegion(geom.srcB, geom.dstB, x0, y0, tileW, tileH, srcTmp)
                dstTmp.set(
                    cx - halfW + ox,
                    cy - halfH + oy,
                    cx + halfW + ox,
                    cy + halfH + oy,
                )
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(b, srcTmp, dstTmp)
                }
            }
        }
    }

    private companion object {
        const val MAX_COLS = 8
        const val MAX_ROWS = 5
        const val TILE_PX = 240f
        const val STAGGER = 0.50f
        const val SEEDS = 12
    }
}

/**
 * 胶片卷动（P2）—— 画面像一卷胶片一样**竖向卷过**，
 * 边界处是一条带**片孔齿**的黑边。
 */
internal class FilmRollTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.FILM_ROLL

    /** 旧图 / 新图的放大落点（放大 12% 免得卷动时露边） */
    private val tmpA = PhotoRect()
    private val tmpB = PhotoRect()

    override fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {
        scaleRect(geom.dstA, 1.12f, tmpA)
        scaleRect(geom.dstB, 1.12f, tmpB)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        val h = geom.canvasH
        val w = geom.canvasW

        // 胶片往上卷：旧图上移，新图从下方跟进
        val edge = h * (1f - p)
        drawPhotoAt(a, geom.srcA, tmpA, offX = 0f, offY = -h * p)
        drawPhotoAt(b, geom.srcB, tmpB, offX = 0f, offY = h * (1f - p))

        // 卷边：一条黑带 + 一排片孔
        val band = (h * 0.055f).coerceAtLeast(2f)
        drawRect(
            color = Color(0xFF101010),
            topLeft = Offset(0f, edge - band),
            size = Size(w, band),
        )
        val holeW = band * 0.45f
        val holeH = band * 0.55f
        var hx = band * 0.35f
        while (hx + holeW < w) {
            drawRect(
                color = Color(0xFF2A2A2A),
                topLeft = Offset(hx, edge - band + (band - holeH) * 0.5f),
                size = Size(holeW, holeH),
            )
            hx += band * 1.6f
        }
    }
}

/**
 * 漫画分格（P2）—— 新图被切成几个**大分格**依次显现，分格之间是粗黑边框。
 */
internal class ComicPanelTransition : TileGridP2Transition() {

    override val id: PhotoTransitionId = PhotoTransitionId.COMIC_PANEL

    private val srcTmp = PhotoRect()
    private val dstTmp = PhotoRect()

    override fun maxCols(): Int = PANEL_COLS

    override fun maxRows(): Int = PANEL_ROWS

    override fun tilePx(): Float = PANEL_PX

    /** 漫画分格**固定 3×2**（不按画布反推 —— 漫画版式是设计死的） */
    override fun gridCols(): Int = PANEL_COLS

    override fun gridRows(): Int = PANEL_ROWS

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val denom = 1f - STAGGER
        for (row in 0 until rows) {
            val y0 = area.top + row * tileH
            for (col in 0 until cols) {
                val idx = row * cols + col
                val local = ((p - tileHash(idx * 23) * STAGGER) / denom).coerceIn(0f, 1f)
                if (local <= 0f) continue

                val x0 = area.left + col * tileW
                val cx = x0 + tileW * 0.5f
                val cy = y0 + tileH * 0.5f
                // 从 0.6 倍弹到 1 倍（漫画格「啪」地张开）
                val s = 0.60f + 0.40f * local
                sourceRegion(geom.srcB, geom.dstB, x0, y0, tileW, tileH, srcTmp)
                dstTmp.set(
                    cx - tileW * 0.5f * s,
                    cy - tileH * 0.5f * s,
                    cx + tileW * 0.5f * s,
                    cy + tileH * 0.5f * s,
                )
                clipRect(x0, y0, x0 + tileW, y0 + tileH) {
                    drawPhoto(b, srcTmp, dstTmp)
                }
            }
        }

        // 粗黑边框（压在最上层）
        val bw = (area.width * 0.006f).coerceAtLeast(2f)
        var x = area.left + bw
        while (x < area.right - bw * 0.5f) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(x - bw * 0.5f, area.top),
                size = Size(bw, area.height),
            )
            x += tileW
        }
        var y = area.top + bw
        while (y < area.bottom - bw * 0.5f) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(area.left, y - bw * 0.5f),
                size = Size(area.width, bw),
            )
            y += tileH
        }
    }

    private companion object {
        /** 漫画分格：3 列 × 2 行 */
        const val PANEL_COLS = 3
        const val PANEL_ROWS = 2

        /** 分格目标边长（px）—— 1920 宽下正好 3 列 */
        const val PANEL_PX = 640f

        const val STAGGER = 0.55f
    }
}
