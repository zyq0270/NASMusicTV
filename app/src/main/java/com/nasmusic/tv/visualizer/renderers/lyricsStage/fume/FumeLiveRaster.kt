// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeLiveRaster.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeBlockFrame.FumeBlockTiming
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 把「正在打印」的块画进一张离屏位图，再贴回舞台上。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeLiveRaster.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 为什么要这一层（Folia 的原意）
 *  Folia 的这块是为 Chromium 的字形缓存 fd 泄漏做的（Linux 专用开关）。
 *  Android 上没有那个泄漏，但**同一个根因还在**：相机连续缩放时，文字每帧都以新的
 *  设备尺寸重新光栅化。把这块先画到一张**固定光栅密度**的位图上，相机缩放只影响
 *  贴图，不再影响文字光栅化 ⇒ 与 Folia 同样的取舍（最多缩 1.5%）。
 *
 * ## 浏览器 API → Android 等价物
 *  - `HTMLCanvasElement` ⇒ `android.graphics.Bitmap` + `android.graphics.Canvas`。
 *  - `canvas.width = n`（重设尺寸会清空画布）⇒ Android 的 `Bitmap` 不可原地改尺寸 ⇒
 *    需要放大时**新建**一张位图并回收旧的（见 [ensure]）。
 *  - `context.clearRect` ⇒ `Canvas.drawRect(..., PorterDuff.Mode.CLEAR)`。
 *  - `context.setTransform(scale, 0, 0, scale, -left*scale, -top*scale)`
 *    ⇒ `Canvas.scale` + `Canvas.translate`（同序 ⇒ 同结果）。
 */
class FumeLiveRaster {

    /** 移植 fumeLiveRaster.ts:28-35 `LiveCanvas` */
    private class LiveCanvas(
        val bitmap: Bitmap,
        val canvas: Canvas,
        /** 上一次画到的设备像素尺寸（用来只清理用过的区域） */
        var usedWidth: Int = 0,
        var usedHeight: Int = 0,
        var lastUsed: Long = 0L,
    )

    /** 每个块的离屏画布（按块 id） */
    private val canvases = HashMap<String, LiveCanvas>()

    /** 移植 fumeLiveRaster.ts:17 */
    val liveIdleMs: Long = LIVE_IDLE_MS

    /**
     * 移植 fumeLiveRaster.ts:58-90 `draw`
     *
     * @param now 当前时刻（毫秒），用于空闲回收
     * @param out 结果的持有者（复用对象，避免逐帧分配）
     * @return true = 画好了；false = 这一块不在可见范围内。
     */
    fun draw(
        block: FumeTypes.FumeBlock,
        timing: FumeBlockTiming,
        params: FumeTypes.FumeLiveBlockParams,
        frame: FumeTypes.FumeLiveRasterFrame,
        drawer: FumeCanvasText.FumeTextDrawer,
        now: Long,
        out: FumeTypes.FumeLiveRasterImage,
    ): Boolean {
        // 每个世界单位多少纹素
        val scale = resolveLiveRasterScale(frame.deviceScale)

        // 辉光的余量（辉光的模糊半径是设备像素）与打印印章（从行上方落下）
        val glowReach = (12f + block.fontPx * 0.7f) * max(params.glowIntensity, 1f) * 1.5f
        val pad = ceil(block.lineHeight * 0.5f + glowReach / max(scale, 0.01f))

        val left = max(block.x - pad, frame.visibleLeft)
        val top = max(block.y - pad, frame.visibleTop)
        val right = min(block.x + block.width + pad, frame.visibleRight)
        val bottom = min(block.y + block.height + pad, frame.visibleBottom)
        if (right <= left || bottom <= top) return false

        val width = max(1, ceil((right - left) * scale).toInt())
        val height = max(1, ceil((bottom - top) * scale).toInt())

        val live = ensure(block.id, width, height)
        val canvas = live.canvas

        // 移植 fumeLiveRaster.ts:81-82 —— 清掉上次画过的区域
        canvas.drawRect(
            0f,
            0f,
            max(live.usedWidth, width).toFloat(),
            max(live.usedHeight, height).toFloat(),
            clearPaint,
        )

        // 移植 fumeLiveRaster.ts:83 —— 把世界坐标搬进这块位图
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(-left, -top)
        FumeCanvasText.drawFumeLiveBlock(canvas, drawer, block, timing, params)
        canvas.restore()

        live.usedWidth = width
        live.usedHeight = height
        live.lastUsed = now

        out.bitmap = live.bitmap
        out.width = width
        out.height = height
        out.x = left
        out.y = top
        out.worldWidth = width / scale
        out.worldHeight = height / scale
        return true
    }

    /** 复用的「清空」画笔 */
    private val clearPaint = android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /**
     * 移植 fumeLiveRaster.ts:92-104 `ensure`
     *
     * 位图按 [LIVE_CANVAS_GRANULE] 步长增长（缩放时不会每帧重新分配）。
     * Android 的 `Bitmap` 不能原地改尺寸 ⇒ 需要放大时新建一张并回收旧的。
     */
    private fun ensure(id: String, width: Int, height: Int): LiveCanvas {
        val current = canvases[id]
        if (current != null &&
            current.bitmap.width >= width &&
            current.bitmap.height >= height
        ) {
            return current
        }

        val currentWidth = current?.bitmap?.width ?: 0
        val currentHeight = current?.bitmap?.height ?: 0
        val targetWidth = ceil(max(width, currentWidth).toFloat() / LIVE_CANVAS_GRANULE) * LIVE_CANVAS_GRANULE
        val targetHeight = ceil(max(height, currentHeight).toFloat() / LIVE_CANVAS_GRANULE) * LIVE_CANVAS_GRANULE

        val bitmap = try {
            Bitmap.createBitmap(targetWidth.toInt(), targetHeight.toInt(), Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            // 内存不够：退回到恰好够大的尺寸
            Bitmap.createBitmap(
                max(1, width),
                max(1, height),
                Bitmap.Config.ARGB_8888,
            )
        }
        bitmap.eraseColor(0)

        current?.bitmap?.recycle()

        val live = LiveCanvas(bitmap, Canvas(bitmap))
        canvases[id] = live
        return live
    }

    /** 移植 fumeLiveRaster.ts:107-114 `sweep` —— 回收空闲太久的位图 */
    fun sweep(now: Long) {
        val iterator = canvases.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastUsed <= LIVE_IDLE_MS) continue
            entry.value.bitmap.recycle()
            iterator.remove()
        }
    }

    /** 移植 fumeLiveRaster.ts:116-118 `clear` —— 全部回收 */
    fun clear() {
        for (live in canvases.values) {
            live.bitmap.recycle()
        }
        canvases.clear()
    }

    companion object {
        /** 移植 fumeLiveRaster.ts:17 `LIVE_IDLE_MS` */
        const val LIVE_IDLE_MS = 5_000L

        /** 移植 fumeLiveRaster.ts:19 `LIVE_CANVAS_GRANULE` */
        const val LIVE_CANVAS_GRANULE = 256

        /** 移植 fumeLiveRaster.ts:21 `LIVE_RASTER_LEVELS_PER_OCTAVE` */
        const val LIVE_RASTER_LEVELS_PER_OCTAVE = 48

        /**
         * 移植 fumeLiveRaster.ts:24-26 `resolveLiveRasterScale`
         *
         * 不小于 `scale` 的最小光栅档位；文字最多被缩小 2^(1/48) ≈ 1.5%。
         */
        fun resolveLiveRasterScale(scale: Float): Float {
            val log2 = (Math.log(max(scale, 1e-3f).toDouble()) / Math.log(2.0))
            val levels = Math.ceil(log2 * LIVE_RASTER_LEVELS_PER_OCTAVE - 1e-9)
            return Math.pow(2.0, levels / LIVE_RASTER_LEVELS_PER_OCTAVE).toFloat()
        }
    }
}
