package com.nasmusic.tv.visualizer.renderers.lyricsStage

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualQuality
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
 *
 * ## ✅ 点阵加密度（2026-11-07，用户反馈「要再加多粒子，现在都不够组成文字」）
 *
 * E23 独立主题与本子模式共用同一个渲染器，但**观感诉求不同**：
 * - E23 独立主题：整屏只放两行字，预算档位（3600 / 900）是它自己的性能契约 ⇒ **不动**；
 * - 本子模式：歌词舞台是「整屏主视觉」，文字是唯一的主角 ⇒ 在 E23 修复后的**均匀取点**基础上
 *   再把粒子预算抬到 **2 倍**（中/高档 3600 → 7200）。
 *
 * ⚠️ 加密度**不会**顺带放大字号（渲染器侧走 `fontBudgetCap`：只有减点才收缩字号）
 *    ⇒ 粒子落在**同样大小**的字上 ⇒ 覆盖率 34.6% → **63.0%**（TV 1920×1080 / 8 字行实测）。
 * ⛔ **低档不加**：低画质的整条设计就是「降密度换帧率」（见 `LowTierElementBudgetTest` ②），
 *    弱机上多一倍椭圆会把帧率再砍一次 ⇒ 低档维持档位默认（900）。
 */
class DotMatrixSubRenderer : LyricsSubRenderer {

    private val delegate = LyricsDotMatrixRenderer()

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        // ⚠️ 必须在 `delegate.onEnter` **之前**设：委托在 onEnter 里按预算分配粒子数组。
        delegate.setParticleBudget(budgetFor(ctx.quality))
        delegate.onEnter(ctx)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        with(delegate) { draw(frame, ctx) }
    }

    override fun onExit() {
        delegate.onExit()
    }

    internal companion object {
        /**
         * 歌词舞台子模式 0 的每行粒子上限（**纯函数**，门禁可直接调）。
         *
         * - 中/高档：E23 的 2 倍（3600 → 7200）⇒ 文字真正「组成」起来；
         * - 低档：维持档位默认（900）—— 弱机优先帧率。
         */
        internal fun budgetFor(quality: VisualQuality): Int = when (quality) {
            VisualQuality.LOW -> LyricsDotMatrixRenderer.capacityForTier(VisualQuality.LOW)
            else -> LyricsDotMatrixRenderer.capacityForTier(quality) * DENSITY_MULTIPLIER
        }

        /** 中/高档的密度倍数（E23 独立主题不适用，仅本子模式用） */
        internal const val DENSITY_MULTIPLIER = 2
    }
}
