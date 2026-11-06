package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.DotMatrixSubRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.FullViewSubRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.GlowLineSubRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageMode
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStagePayload
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsSubRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.PartitaSubRenderer
import com.nasmusic.tv.visualizer.renderers.lyricsStage.TiltPulseSubRenderer

/**
 * E53「歌词舞台」主渲染器。
 *
 * 一个主题内含 5 个**手动切换**的歌词子模式：
 *  0 歌词点阵（NASMusicTV E23 原创，复用 [LyricsDotMatrixRenderer]）
 *  1 逐字行光（移植自 Folia `classic`）
 *  2 全屏纵览（移植自 Folia `fume`）
 *  3 倾斜律动（移植自 Folia `tilt`）
 *  4 分栏引导（移植自 Folia `partita`）
 *
 * ## 切换语义
 * ⛔ **仅手动切换**：不自动定时切换、不随歌曲切换、不随段落/副歌切换。
 * 上下键 / 手势上下滑动 ⇒ [nextMode] / [prevMode]。
 *
 * ## 歌词数据的传递方式
 * ⛔ **不走 `RenderContext`** —— 那个类被本机私有分支的覆盖层遮蔽（同名文件），
 * 改它会让本地构建与 CI 行为分叉。改为由
 * [com.nasmusic.tv.ui.components.VisualizerStage] 每帧调用 [applyLyrics] 直接推给本渲染器。
 */
class LyricsStageRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.LYRICS_STAGE

    /** 当前选中的子模式（FULL_VIEW 在非 HIGH 画质下被降级，见 [effectiveMode]） */
    private var mode: LyricsStageMode = LyricsStageMode.Default

    /** 实际在渲染的子渲染器 */
    private var subRenderer: LyricsSubRenderer? = null

    /** 每帧由舞台推入的歌词数据 */
    private var payload: LyricsStagePayload = LyricsStagePayload.Empty

    /** 最近一次的上下文（供 [setMode] 在没有显式 ctx 时重建子渲染器） */
    private var lastCtx: RenderContext? = null

    /** 最近一次的画质档位（供 [setMode] 判断 FULL_VIEW 是否降级） */
    private var lastQuality: VisualQuality = VisualQuality.Default

    /** 子模式切换淡入的起始时刻（ctx.nowMs 时钟） */
    private var transitionStartMs: Long = 0L
    private var isTransitioning: Boolean = false

    /** 已通知子渲染器的当前行索引（变化 ⇒ 触发 onLineChanged） */
    private var lastLineIndex: Int = Int.MIN_VALUE

    /** 子模式切换淡入用的离屏层画笔（成员复用 ⇒ 逐帧零分配） */
    private val layerPaint = android.graphics.Paint()

    // ── 对外接口（供 VisualizerStage 调用）──────────────────────

    /** 当前选中的子模式 */
    fun currentMode(): LyricsStageMode = mode

    /** 实际渲染的子模式（FULL_VIEW 在非 HIGH 画质下 ⇒ GLOW_LINE） */
    fun effectiveMode(): LyricsStageMode = when (mode) {
        LyricsStageMode.FULL_VIEW ->
            if (lastQuality == VisualQuality.HIGH) LyricsStageMode.FULL_VIEW else LyricsStageMode.GLOW_LINE
        else -> mode
    }

    /** 舞台每帧推入歌词数据 */
    fun applyLyrics(newPayload: LyricsStagePayload) {
        payload = newPayload
    }

    /**
     * 设置子模式（舞台在读到持久化值后调用）。
     *
     * 渲染器每次主题切换都会被 [com.nasmusic.tv.visualizer.RendererSwapper] 重建
     * ⇒ 子模式必须由外部重新告知，不能只存在渲染器里。
     */
    fun setMode(newMode: LyricsStageMode) {
        if (newMode == mode && subRenderer != null) return
        applyMode(newMode, lastCtx)
    }

    /** 下一个子模式（循环） */
    fun nextMode() = setMode(LyricsStageMode.next(mode))

    /** 上一个子模式（循环） */
    fun prevMode() = setMode(LyricsStageMode.prev(mode))

    // ── VisualizerRenderer ─────────────────────────────────────

    override fun onEnter(ctx: RenderContext) {
        lastCtx = ctx
        lastQuality = ctx.quality
        lastLineIndex = Int.MIN_VALUE
        applyMode(mode, ctx)
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val current = subRenderer ?: return

        // 当前行变化 ⇒ 让子渲染器重建与当前行有关的状态
        if (payload.currentIndex != lastLineIndex) {
            lastLineIndex = payload.currentIndex
            current.onLineChanged(ctx, payload)
        }

        var alpha = 1f
        if (isTransitioning) {
            val elapsed = ctx.nowMs - transitionStartMs
            val t = if (TRANSITION_MS <= 0L) {
                1f
            } else {
                (elapsed.toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
            }
            alpha = t
            if (t >= 1f) {
                isTransitioning = false
                alpha = 1f
            }
        }

        if (alpha >= 1f) {
            // ⚠️ `LyricsSubRenderer.draw` 是**成员扩展**（`fun DrawScope.draw(...)`）⇒
            //    Kotlin **不能**写成 `current.draw(...)`：成员扩展要求**两个接收者都是隐式的**
            //    （派发接收者 + 扩展接收者）⇒ 必须用 `with(current) { draw(...) }` 把派发接收者
            //    放进隐式接收者链，扩展接收者由外层的 DrawScope 提供。
            with(current) { draw(frame, ctx, payload) }
        } else {
            // 子模式切换的 300ms 淡入：整层画进一个带 alpha 的图层。
            // ⚠️ 用 `saveLayer(bounds, paint)`（仓内既有写法，见 photo/transitions/*）而不是
            //    `saveLayerAlpha` —— 前者的 alpha 走 Paint，避免任何 API 弃用告警（lint 是阻断门禁）。
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                layerPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                val checkpoint = native.saveLayer(null, layerPaint)
                with(current) { draw(frame, ctx, payload) }
                native.restoreToCount(checkpoint)
            }
        }
    }

    override fun onExit() {
        subRenderer?.onExit()
        subRenderer = null
        lastCtx = null
    }

    // ── 内部 ───────────────────────────────────────────────────

    /**
     * 切到指定子模式：释放旧的、建新的、起 300ms 淡入。
     *
     * @param ctx 非 null 时用该上下文进入新子渲染器；null 表示还没有上下文
     *            （此时只建不进入，等 [onEnter] 补）。
     */
    private fun applyMode(newMode: LyricsStageMode, ctx: RenderContext?) {
        mode = newMode
        subRenderer?.onExit()
        val quality = ctx?.quality ?: lastQuality
        lastQuality = quality
        subRenderer = createSubRenderer(newMode, quality)
        // ⚠️ 刚用**当前 payload** 进入过 ⇒ 当前行已被处理过 ⇒ 不要在首帧再触发一次
        //    `onLineChanged`（那会让子渲染器白白重建一次布局）。
        lastLineIndex = payload.currentIndex
        if (ctx != null) {
            subRenderer?.onEnter(ctx, payload)
            transitionStartMs = ctx.nowMs
            isTransitioning = true
        } else {
            isTransitioning = false
        }
    }

    /**
     * 建子渲染器。
     *
     * ⚠️ FULL_VIEW（整曲版面 + 相机变换）只在 HIGH 画质提供；
     * 非 HIGH 画质降级为 GLOW_LINE（见 `docs/lyrics-stage-plan.md` §13）。
     */
    private fun createSubRenderer(target: LyricsStageMode, quality: VisualQuality): LyricsSubRenderer =
        when (target) {
            LyricsStageMode.DOT_MATRIX -> DotMatrixSubRenderer()
            LyricsStageMode.GLOW_LINE -> GlowLineSubRenderer()
            LyricsStageMode.FULL_VIEW ->
                if (quality == VisualQuality.HIGH) FullViewSubRenderer() else GlowLineSubRenderer()
            LyricsStageMode.TILT_PULSE -> TiltPulseSubRenderer()
            LyricsStageMode.PARTITA -> PartitaSubRenderer()
        }

    companion object {
        /** 子模式切换的淡入时长（ms） */
        const val TRANSITION_MS = 300L
    }
}
