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
 * ⛔ E23 已于 2026-11-07 下线为独立主题（老用户存的键由 `LEGACY_MAP` 回落到 E53）⇒ 本渲染器
 * **只**作为本子模式被使用 ⇒ 「加密度」是**唯一**生效的档位策略：
 * - 歌词舞台是「整屏主视觉」，文字是唯一的主角 ⇒ 在点阵修复后的**均匀取点**基础上
 *   再把粒子预算抬到 **2 倍**（中/高档 3600 → 7200）。
 *
 * ⚠️ 加密度**不会**顺带放大字号（渲染器侧走 `fontBudgetCap`：只有减点才收缩字号）
 *    ⇒ 粒子落在**同样大小**的字上 ⇒ 覆盖率 34.6% → **63.0%**（TV 1920×1080 / 8 字行实测）。
 * ⛔ **低档不加**：低画质的整条设计就是「降密度换帧率」（见 `LowTierElementBudgetTest` ②），
 *    弱机上多一倍椭圆会把帧率再砍一次 ⇒ 低档维持档位默认（900）。
 *
 * ## ✅ 卡拉OK 扫描改为**逐帧**（2026-11-07）
 *
 * `RenderContext.lyricLineProgress` 来自 `PlayerManager` 的 **1 Hz** 进度 StateFlow
 * ⇒ 逐字扫描**每秒才跳一次**（60 fps 的电视上就是「一格一格地亮」）。
 * 本子模式手里的 [LyricsStagePayload] 是**逐帧**更新的（`timeSec` = 逐帧读到的播放位置）
 * ⇒ 由 [pushPerFrameProgress] 推给委托（**带行索引**配对 ⇒ 委托只在归属行与屏上那一行
 * 一致时才应用，见 `LyricsDotMatrixRenderer.resolveLyricProgress`）。
 */
class DotMatrixSubRenderer : LyricsSubRenderer {

    private val delegate = LyricsDotMatrixRenderer()

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        // ⚠️ 必须在 `delegate.onEnter` **之前**设：委托在 onEnter 里按预算分配粒子数组。
        delegate.setParticleBudget(budgetFor(ctx.quality))
        delegate.onEnter(ctx)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        // ⚠️ 必须在 `delegate.draw` **之前**推：委托在绘制时就要用本帧的行内进度。
        pushPerFrameProgress(lyrics)
        with(delegate) { draw(frame, ctx) }
    }

    /**
     * 把**逐帧**的行内进度推给委托（E23 的渲染器）。
     *
     * ## 为什么必须逐帧（这是本子模式的一个真实缺陷）
     * `RenderContext.lyricLineProgress` 来自 `PlayerManager` 的 **1 Hz** 进度 StateFlow
     * ⇒ 卡拉OK 的逐字扫描**每秒才跳一次**（在 60 fps 的电视上就是「一格一格地亮」）。
     * 而 `LyricsStagePayload` 是**逐帧**更新的（`timeSec` = 逐帧读到的播放位置、
     * `currentIndex` = 逐帧算出的当前行）⇒ 逐帧进度只有这里有。
     *
     * ## ⚠️ 覆盖要**配对到屏上那一行**
     * 委托屏上那一行是它自己的行号（`delegate.singingLineIndex()`），
     * 与本 payload 的 `currentIndex` **可能差一帧**（`ctx.lyricLineIndex` 是 1 Hz 的）
     * ⇒ 这里按**屏上那一行**去 `lines` 里取行首/行尾时间算进度，
     * 并用该行索引作为覆盖的归属行 ⇒ 委托只在归属行与屏上那一行一致时才应用覆盖。
     */
    private fun pushPerFrameProgress(lyrics: LyricsStagePayload) {
        val idx = delegate.singingLineIndex()
        val line = lyrics.lines.getOrNull(idx)
        if (line == null) {
            delegate.clearLyricsProgressOverride()
            return
        }
        val progress = LyricsDotMatrixRenderer.lineProgressOf(
            line.startTime, line.endTime, lyrics.timeSec
        )
        delegate.setLyricsProgressOverride(idx, progress)
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

        /** 中/高档的密度倍数（仅本子模式用） */
        internal const val DENSITY_MULTIPLIER = 2
    }
}
