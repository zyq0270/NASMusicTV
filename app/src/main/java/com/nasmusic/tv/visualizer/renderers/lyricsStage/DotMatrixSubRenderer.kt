package com.nasmusic.tv.visualizer.renderers.lyricsStage

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.renderers.LyricsDotMatrixRenderer

/**
 * 子模式 0：歌词点阵 —— **NASMusicTV 原创**（E23），非 Folia 移植。
 *
 * 直接复用现有 [LyricsDotMatrixRenderer]（含它的暗角 0.44 + 颗粒 0.026 后处理），
 * ⛔ 不重写一遍粒子系统。
 *
 * ⚠️ `LyricsDotMatrixRenderer.draw` 是 `RendererFx` 的 **final** 成员扩展
 * （`fun DrawScope.draw(...)`），调用时需要**同时**给出两个接收者：
 * 派发接收者 = 委托实例，扩展接收者 = 当前 DrawScope ⇒ 必须写成 `with(delegate) { draw(...) }`。
 */
class DotMatrixSubRenderer : LyricsSubRenderer {

    private val delegate = LyricsDotMatrixRenderer()

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        delegate.onEnter(ctx)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        with(delegate) { draw(frame, ctx) }
    }

    override fun onExit() {
        delegate.onExit()
    }
}
