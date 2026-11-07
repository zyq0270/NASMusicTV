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
import com.nasmusic.tv.visualizer.renderers.lyricsStage.GlowWordHalo
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
            // 子模式切换的淡入：整层画进一个带 alpha 的图层。
            // ⚠️ 用 `saveLayer(bounds, paint)`（仓内既有写法，见 photo/transitions/ 下的写法）而不是
            //    `saveLayerAlpha` —— 前者的 alpha 走 Paint，避免任何 API 弃用告警（lint 是阻断门禁）。
            // ⛔ **当前走不到这里**（`TRANSITION_MS = 0L` ⇒ alpha 恒为 1f）⇒ 这段是保留的
            //    「备用淡入实现」：改回 `TRANSITION_MS = 300L` 即可重新启用（代价见该常量的 KDoc）。
            //    ⚠️ `bounds = null` ⇒ 图层是**整块画布** ⇒ 弱电视上切换那几百毫秒会掉到 10–20 fps。
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
        // ⚠️ 辉光位图缓存是**进程级共享**的 ⇒ 退出**主题**时统一回收（否则位图泄漏到进程结束）。
        // ⛔ **子模式切换不得清**：缓存键是 (字形, 字号, 颜色, 内圈半径, 外圈半径) ⇒ 别的子模式的
        //    条目只是**闲置**（缓存本身有 `GlowWordHalo.MAX_ENTRIES` 上限）⇒ 清掉只会让切换后的
        //    首行把每个字形重新模糊一遍（10–15 字形 × 2 次真实高斯模糊，主线程）⇒ 硬卡顿。
        GlowWordHalo.clear()
    }

    // ── 内部 ───────────────────────────────────────────────────

    /**
     * 切到指定子模式：释放旧的、建新的、（若启用淡入）起一段淡入。
     *
     * @param ctx 非 null 时用该上下文进入新子渲染器；null 表示还没有上下文
     *            （此时只建不进入，等 [onEnter] 补）。
     */
    private fun applyMode(newMode: LyricsStageMode, ctx: RenderContext?) {
        mode = newMode
        // ⚠️ 子渲染器的 `onExit` **不得**清辉光缓存（否则切换后的首行要重新模糊每个字形 ⇒ 卡顿）
        //    ⇒ 缓存在**主题**退出时由 [onExit] 统一回收。
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
        /**
         * 子模式切换的淡入时长（ms）。
         *
         * ⛔ **当前是 0 ⇒ 子模式切换是硬切（没有淡入）**。
         *
         * ## 为什么关掉（2026-11-07 电视卡顿实测）
         * 淡入走 `Canvas.saveLayer(null, paint)` ⇒ `bounds == null` ⇒ 图层是**整块画布**：
         *  - 1080×2340 的手机 ⇒ 离屏缓冲 **10 MB**
         *  - 1920×1080 的电视 ⇒ 离屏缓冲 **8.3 MB**
         *
         * 切换那 300 ms 里，每帧都要「清 FBO → 光栅化整屏 → 合成回来」
         * ⇒ 约 **30 MB/帧**的额外 GPU 流量 ⇒ 弱电视上 ≈ **10 ms/帧**
         * ⇒ 切换本身掉到 10–20 fps（读起来像卡死；设备反馈「电视上比手机更卡」正是这条）。
         *
         * 何况子模式切换已经有 **Toast** 提示（舞台里的 `lyricsStageToastText`），
         * 且切换是**用户主动**按上下键触发的 ⇒ 淡入的收益远小于它的代价。
         *
         * ## 怎么重新打开
         * 把下面的常量改回 `300L` 即可 —— 淡入代码路径**完整保留**（只是走不到）。
         * ⚠️ 改回之前请先做两件事之一：
         *  ① 改成**有界**图层（给 `saveLayer` 传一个矩形而不是 `null`）——
         *     ⚠️ 但各子模式的内容范围不同 ⇒ 矩形算错会把淡入裁掉；
         *  ② 或者接受这一次切换会掉帧。
         */
        const val TRANSITION_MS = 0L
    }
}
