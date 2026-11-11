package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * P2 档「音频反应」族（§4.2 I 类）—— 本文件 1 种
 *
 * | 效果 | 手法 |
 * |---|---|
 * | [WaveformWipeTransition] | 擦除边界跟随**时域波形**起伏 |
 *
 * ⚠️ 本族所有效果 `audioReactive = true` ⇒ **不进随机池**（§5.9）：
 * 形态由音频数据驱动，随机抽到会让人以为「效果坏了」。只能由用户主动选中。
 *
 * ⚠️ 波形取自 `geom.waveform`（`AudioFrame.waveform`，**时域**信号 `-1..1`）——
 * 不是频谱。两者语义不同：频谱是能量分布，波形是时间轴上的振幅。
 */
internal class WaveformWipeTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.WAVEFORM_WIPE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)

        val w = geom.canvasW
        val h = geom.canvasH
        val wave = geom.waveform

        if (wave == null || wave.isEmpty()) {
            // 无音频数据 ⇒ 退化成自下而上的线性擦除
            val top = h * (1f - p)
            clipRect(0f, top, w, h) { drawPhoto(b, geom.srcB, geom.dstB) }
            return
        }

        val colW = w / COLS
        val step = wave.size.toFloat() / COLS
        for (i in 0 until COLS) {
            // 波形采样（`-1..1`）⇒ 边界起伏
            val s = wave[(i * step).toInt().coerceIn(0, wave.size - 1)]
            // 基础边界自下而上推进；抖动随「剩余进度」收敛 ⇒ 末帧边界归零、整幅是新图
            val base = h * (1f - p)
            val wobble = h * AMPLITUDE * s * (1f - p)
            val top = (base + wobble).coerceIn(0f, h)
            val x = i * colW
            clipRect(x, top, x + colW, h) {
                drawPhoto(b, geom.srcB, geom.dstB)
            }
        }
    }

    private companion object {
        /** 列数（48 列 ≈ 1080p 下每列 40 px） */
        const val COLS = 48

        /** 边界起伏幅度（相对屏高） */
        const val AMPLITUDE = 0.22f
    }
}
