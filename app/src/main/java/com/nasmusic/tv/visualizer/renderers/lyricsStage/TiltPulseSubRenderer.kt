package com.nasmusic.tv.visualizer.renderers.lyricsStage

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt.TiltLayout
import kotlin.math.max
import kotlin.math.min

// 移植自 Folia (AGPL-3.0): src/components/visualizer/tilt/VisualizerTilt.tsx — Project Folia version 0.5.27-a16525c
//
// 「倾斜律动」子模式：把当前行拆成 1–4 段，其中**恰好一段**是倾斜体（大号斜体、字符上下交错），
// 其余是常规行；段按时间顺序依次揭示，字符随逐字时序做脉冲缩放。
//
// 浏览器 → Android 的替换（全部记在此处）：
//  ① CSS `clamp(a rem, b vw, c rem)` → 直接换算成 px（rem = 16px，vw = 视口宽度 / 100）。
//  ② CSS `font-style: italic` → `Typeface.ITALIC`；Android 上默认字族没有真斜体，
//     系统合成斜体（skew）**不改变 advance 宽度** ⇒ 与 Folia 用 italic 字重测量等价。
//  ③ CSS `font-weight: 300 / 400` → Android minSdk 22 无字体轴（`Typeface.create(family, weight, italic)`
//     是 API 28）⇒ 统一用 `Typeface.DEFAULT`（常规）；字重差是本仓已知偏差。
//  ④ CSS `transition` / framer-motion 的 variants → 本文件显式实现（进入进度 + cubic-bezier 缓动）。
//  ⑤ CSS `transform: scale()` 的 `transform-origin: 50% 50%` → 以字形盒中心为原点做 canvas 变换。
//  ⑥ Folia 的 `lyricsFontScale` / `theme.animationIntensity` / `theme.wordColors` 在 NASMusicTV 无对应设置。
class TiltPulseSubRenderer : LyricsSubRenderer {

    // ── Folia 常量 ────────────────────────────────────────────────────────────

    private companion object {
        /** VisualizerTilt.tsx:566 `DEFAULT_TILT_TUNING.splitProbability` */
        const val SPLIT_PROBABILITY = 0.75

        /** VisualizerTilt.tsx:567 `DEFAULT_TILT_TUNING.tiltStyleProbability` */
        const val TILT_STYLE_PROBABILITY = 0.35

        /**
         * Folia 的 `lyricsFontScale`；NASMusicTV 没有「歌词字号」设置 ⇒ 恒为 1。
         */
        const val LYRICS_FONT_SCALE = 1f

        /** VisualizerTilt.tsx:49 `REM_PX` */
        const val REM_PX = 16f

        /** VisualizerTilt.tsx:351 `clamp(3.125rem, 6.875vw, 5.625rem)` */
        const val FONT_MIN_REM = 3.125f
        const val FONT_PREFERRED_VW = 6.875f
        const val FONT_MAX_REM = 5.625f

        /** VisualizerTilt.tsx:441 常规行 `lineHeight` */
        const val NORMAL_LINE_HEIGHT = 1.35f

        /** VisualizerTilt.tsx:493 倾斜行 `lineHeight` */
        const val TILT_LINE_HEIGHT = 1.25f

        /** VisualizerTilt.tsx:445 常规行 `letterSpacing: '0.08em'` */
        const val NORMAL_LETTER_SPACING_EM = 0.08f

        /** VisualizerTilt.tsx:497 倾斜行 `letterSpacing: '0.15em'` */
        const val TILT_LETTER_SPACING_EM = 0.15f

        /** VisualizerTilt.tsx:471 常规行空格 `minWidth: '0.25em'` */
        const val NORMAL_SPACE_MIN_EM = 0.25f

        /** VisualizerTilt.tsx:529 倾斜行空格 `minWidth: '0.35em'` */
        const val TILT_SPACE_MIN_EM = 0.35f

        /** VisualizerTilt.tsx:423 常规行脉冲系数 `intensity * 0.15` */
        const val NORMAL_PULSE_GAIN = 0.15f

        /** VisualizerTilt.tsx:423 倾斜行脉冲系数 `intensity * 0.18` */
        const val TILT_PULSE_GAIN = 0.18f

        /** VisualizerTilt.tsx:437 常规行容器入场时长（秒） */
        const val NORMAL_ENTER_DURATION = 0.55

        /** VisualizerTilt.tsx:487 倾斜行容器入场时长（秒） */
        const val TILT_ENTER_DURATION = 0.6

        /** VisualizerTilt.tsx:464 / :521 单字符入场时长（秒） */
        const val CHAR_ENTER_DURATION = 0.5

        /** VisualizerTilt.tsx:464 常规行单字符入场延迟步长（秒 / 字符） */
        const val NORMAL_CHAR_DELAY_STEP = 0.04

        /** VisualizerTilt.tsx:521 倾斜行单字符入场延迟步长（秒 / 字符） */
        const val TILT_CHAR_DELAY_STEP = 0.05

        /** VisualizerTilt.tsx:434 常规行容器入场位移（px） */
        const val NORMAL_ENTER_Y = 20f

        /** VisualizerTilt.tsx:484 倾斜行容器入场位移（px） */
        const val TILT_ENTER_Y = 24f

        /** VisualizerTilt.tsx:484 倾斜行容器入场缩放 */
        const val TILT_ENTER_SCALE = 0.92f

        /** VisualizerTilt.tsx:436 常规行容器离场位移（px） */
        const val NORMAL_EXIT_Y = -12f

        /** VisualizerTilt.tsx:486 倾斜行容器离场位移（px） */
        const val TILT_EXIT_Y = -16f

        /** VisualizerTilt.tsx:486 倾斜行容器离场缩放 */
        const val TILT_EXIT_SCALE = 0.95f

        /** VisualizerTilt.tsx:470 / :528 `transition: transform 0.06s ease-out` */
        const val CHAR_SCALE_TRANSITION = 0.06

        /** VisualizerTilt.tsx:613 段可见提前量（秒） */
        const val SEGMENT_VISIBLE_LEAD = 0.25

        /** VisualizerTilt.tsx:618 行尚未开始时的隐藏提前量（秒） */
        const val LINE_HIDE_LEAD = 0.1

        /** VisualizerTilt.tsx:645 外层容器离场时长（秒） */
        const val OUTER_EXIT_DURATION = 0.45

        /**
         * 外层容器入场时长。
         * Folia 的外层 `motion.div` 没写 transition ⇒ 用 framer-motion 的默认 tween（0.3s）。
         */
        const val OUTER_ENTER_DURATION = 0.3

        /** Tailwind `sm:` 断点：视口宽度 ≥ 640px 时用 `gap-y-4` */
        const val SM_BREAKPOINT_PX = 640f

        /** Tailwind `gap-y-3` = 0.75rem = 12px */
        const val GAP_Y_3 = 12f

        /** Tailwind `gap-y-4` = 1rem = 16px */
        const val GAP_Y_4 = 16f

        /** VisualizerTilt.tsx:474 / :532 空格渲染为不换行空格 */
        const val NBSP = "\u00A0"
    }

    // ── 绘制资源（全部常驻，draw() 内零分配）─────────────────────────────────

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 常规字重（对应 Folia 的 400） */
    private val typefaceNormal = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    /** 合成斜体（对应 Folia 的 italic + 300） */
    private val typefaceTilt = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)

    /** 复用的字体度量（`Paint.fontMetrics` 每次取值都会 new ⇒ 用带参重载） */
    private val fontMetrics = Paint.FontMetrics()

    // ── 运行时状态 ────────────────────────────────────────────────────────────

    /** 当前行的版面 */
    private var activeState: TiltState? = null

    /** 正在离场的上一行版面 */
    private var exitingState: TiltState? = null

    /** 当前行索引（用于检测换行） */
    private var activeLineIndex = -1

    /** 当前行的开始时间（用于检测换行） */
    private var activeLineStart = Double.NaN

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
        // 版面在 draw() 里按当前行惰性构建（换行时重建）——onEnter 只做状态复位。
        activeState = null
        exitingState = null
        activeLineIndex = -1
        activeLineStart = Double.NaN
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload) {
        val timeSec = lyrics.timeSec
        syncLine(lyrics, ctx, timeSec)

        val active = activeState
        var exiting = exitingState
        // 离场播完就丢弃（避免旧版面常驻）
        if (exiting != null && timeSec - exiting.exitStart >= OUTER_EXIT_DURATION) {
            exitingState = null
            exiting = null
        }
        if (active == null && exiting == null) return

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // 活动组先画；离场组后画（与 AnimatePresence 的 DOM 顺序一致：离场元素在上）。
            if (active != null) {
                val groupAlpha = outerEnterAlpha(active.groupStart, timeSec)
                drawTiltGroup(nc, active, timeSec, groupAlpha, ctx)
            }
            if (exiting != null) {
                val groupAlpha = 1f - outerExitAlpha(exiting.exitStart, timeSec)
                drawTiltGroup(nc, exiting, timeSec, groupAlpha, ctx)
            }
        }
    }

    override fun onExit() {
        activeState = null
        exitingState = null
        activeLineIndex = -1
        activeLineStart = Double.NaN
    }

    // ── 换行检测与版面构建 ────────────────────────────────────────────────────

    /**
     * 检测换行；换行时把旧版面转入离场状态并构建新版面。
     *
     * 移植 VisualizerTilt.tsx:641-646（`AnimatePresence` + `key={tilt-${startTime}}`）。
     */
    private fun syncLine(lyrics: LyricsStagePayload, ctx: RenderContext, timeSec: Double) {
        val line = lyrics.current
        val index = lyrics.currentIndex

        val changed = if (line == null) {
            activeState != null
        } else {
            activeLineIndex != index || activeLineStart != line.startTime || activeState == null
        }
        if (!changed) return

        // 旧版面转入离场（外层 0.45s 淡出，内部各段再走自己的 exit 变体）
        val old = activeState
        if (old != null) {
            old.exitStart = timeSec
            exitingState = old
        }

        activeState = line?.let { buildState(it, ctx, timeSec) }
        activeLineIndex = index
        activeLineStart = line?.startTime ?: Double.NaN
    }

    /**
     * 构建一行的版面（移植 VisualizerTilt.tsx:211-332 + :575-601）。
     */
    private fun buildState(line: LyricLine, ctx: RenderContext, nowSec: Double): TiltState {
        val state = TiltState()
        state.groupStart = nowSec
        state.exitStart = -1.0

        val viewportWidth = ctx.canvasSize.width
        val availableWidth = TiltLayout.getAvailableWidth(viewportWidth)

        val result = TiltLayout.buildTiltLayout(
            fullText = line.fullText,
            lineSeed = line.startTime,
            splitProbability = SPLIT_PROBABILITY,
            tiltStyleProbability = TILT_STYLE_PROBABILITY,
            fontScale = LYRICS_FONT_SCALE,
            availableWidth = availableWidth,
            measure = { text, px -> TiltLayout.measureAtSize(text, px, ::measureWithPaint) },
            viewportWidth = viewportWidth,
        )

        state.scaleMultiplier = result.scaleMultiplier
        val baseFontScale = LYRICS_FONT_SCALE * result.scaleMultiplier

        // VisualizerTilt.tsx:349 `tiltFontPx`
        val tiltFontPx = min(
            viewportWidth * 0.06875f * baseFontScale,
            FONT_MAX_REM * REM_PX * baseFontScale,
        )

        val lineRenderEnd = LyricRenderHints.getLineRenderEndTime(line)
        var totalHeight = 0f
        val segments = ArrayList<TiltSegmentState>(result.segments.size)

        for (segment in result.segments) {
            // ── 段的时间范围（VisualizerTilt.tsx:580-601 `segmentTimings`）──
            val (startWordIndex, endWordIndex) = TiltLayout.findSegmentWordRange(
                segment.charOffset,
                segment.text,
                line.fullText,
                line.words,
            )
            val segWords = if (startWordIndex < endWordIndex) {
                line.words.subList(startWordIndex, endWordIndex)
            } else {
                emptyList()
            }
            val segStart = if (segWords.isNotEmpty()) segWords[0].startTime else line.startTime
            val segEnd = if (segWords.isNotEmpty()) {
                segWords[segWords.size - 1].endTime
            } else {
                lineRenderEnd
            }

            val seg = TiltSegmentState()
            seg.segment = segment
            seg.isTilt = segment.isTilt
            seg.visibleSince = -1.0
            seg.segStart = segStart
            seg.segEnd = segEnd

            // VisualizerTilt.tsx:347-352
            val shortLastBoost = if (segment.isShortLastLine) 1.18f else 1f
            val fontScale = if (segment.isTilt) baseFontScale else baseFontScale * shortLastBoost
            seg.fontSizePx = cssClampFontSize(
                FONT_MIN_REM,
                FONT_PREFERRED_VW,
                FONT_MAX_REM,
                fontScale,
                viewportWidth,
            )
            seg.letterSpacingEm = if (segment.isTilt) TILT_LETTER_SPACING_EM else NORMAL_LETTER_SPACING_EM
            seg.spaceMinWidthEm = if (segment.isTilt) TILT_SPACE_MIN_EM else NORMAL_SPACE_MIN_EM
            seg.lineHeight = if (segment.isTilt) TILT_LINE_HEIGHT else NORMAL_LINE_HEIGHT
            seg.yOffsetPx = tiltFontPx / 6f

            // ── 逐字素（VisualizerTilt.tsx:372-395）──
            val glyphs = GraphemeSplitter.split(segment.text)
            val n = glyphs.size
            val widths = FloatArray(n)
            val advances = FloatArray(n)
            val ciIndex = IntArray(n)
            val spaceFlags = BooleanArray(n)

            paintText.typeface = if (segment.isTilt) typefaceTilt else typefaceNormal
            paintText.textSize = seg.fontSizePx
            paintText.letterSpacing = 0f
            val letterSpacingPx = seg.letterSpacingEm * seg.fontSizePx
            val spaceMinWidthPx = seg.spaceMinWidthEm * seg.fontSizePx

            var ci = 0
            var totalWidth = 0f
            for (gi in 0 until n) {
                val glyph = glyphs[gi]
                // Folia 用 `/^\s+$/` 判空格；本仓统一用 `isBlank()`（与 TiltLayout 的判定保持一致）。
                val isSpace = glyph.isBlank()
                spaceFlags[gi] = isSpace
                ciIndex[gi] = ci
                val drawGlyph = if (isSpace) NBSP else glyph
                var w = paintText.measureText(drawGlyph)
                if (isSpace && w < spaceMinWidthPx) w = spaceMinWidthPx
                widths[gi] = w
                advances[gi] = w + letterSpacingPx
                totalWidth += advances[gi]
                if (!isSpace) ci++
            }

            seg.glyphs = glyphs.toTypedArray()
            seg.glyphWidths = widths
            seg.advances = advances
            seg.ciIndex = ciIndex
            seg.spaceFlags = spaceFlags
            seg.totalWidth = totalWidth

            // 字符时序（VisualizerTilt.tsx:378-381 → TiltLayout.buildCharTimings）
            seg.charTimings = TiltLayout.buildCharTimings(
                segment.charOffset,
                segment.text,
                segStart,
                segEnd,
                line,
            )

            // 行盒度量
            paintText.getFontMetrics(fontMetrics)
            seg.ascent = fontMetrics.ascent
            seg.descent = fontMetrics.descent
            seg.lineBoxHeight = seg.lineHeight * seg.fontSizePx

            // 运行时数组
            seg.scaleCur = FloatArray(n) { 1f }
            seg.scaleFrom = FloatArray(n) { 1f }
            seg.scaleTarget = FloatArray(n) { 1f }
            seg.scaleStart = DoubleArray(n)

            totalHeight += seg.lineBoxHeight
            segments.add(seg)
        }

        // 段之间的间隙（VisualizerTilt.tsx:646 `gap-y-3 sm:gap-y-4`）
        val gap = if (viewportWidth >= SM_BREAKPOINT_PX) GAP_Y_4 else GAP_Y_3
        if (segments.size > 1) totalHeight += gap * (segments.size - 1)

        state.segments = segments.toTypedArray()
        state.gap = gap
        state.totalHeight = totalHeight
        state.segmentStarts = DoubleArray(segments.size)
        state.segmentEnds = DoubleArray(segments.size)
        for (i in segments.indices) {
            state.segmentStarts[i] = segments[i].segStart
            state.segmentEnds[i] = segments[i].segEnd
        }
        state.line = line
        return state
    }

    /** 文字测量（Folia 用 @chenglou/pretext；Android 等价是 Paint.measureText）。 */
    private fun measureWithPaint(text: String, pxSize: Float): Float {
        paintText.typeface = typefaceNormal
        paintText.textSize = pxSize
        paintText.letterSpacing = 0f
        return paintText.measureText(text)
    }

    /**
     * CSS `clamp(min, preferred, max)` 的 px 换算（rem = 16px，vw = 视口宽度 / 100）。
     */
    private fun cssClampFontSize(
        minRem: Float,
        preferredVw: Float,
        maxRem: Float,
        scale: Float,
        viewportWidth: Float,
    ): Float {
        val minPx = minRem * REM_PX * scale
        val preferredPx = preferredVw * viewportWidth * scale / 100f
        val maxPx = maxRem * REM_PX * scale
        return max(minPx, min(preferredPx, maxPx))
    }

    // ── 段可见性（VisualizerTilt.tsx:604-627 `visibleSegmentIndex`）──────────

    /**
     * 计算当前应显示到第几段：段按时间顺序依次揭示。
     */
    private fun computeVisibleIndex(state: TiltState, timeSec: Double): Int {
        val line = state.line ?: return -1
        var target = -1
        for (i in state.segments.indices) {
            if (timeSec >= state.segmentStarts[i] - SEGMENT_VISIBLE_LEAD) target = i
        }
        val lineRenderEnd = LyricRenderHints.getLineRenderEndTime(line)
        if (timeSec < line.startTime - LINE_HIDE_LEAD || timeSec > lineRenderEnd) target = -1
        return target
    }

    // ── 绘制 ─────────────────────────────────────────────────────────────────

    private fun drawTiltGroup(
        nc: Canvas,
        state: TiltState,
        timeSec: Double,
        groupAlpha: Float,
        ctx: RenderContext,
    ) {
        if (groupAlpha <= 0.001f) return
        val segments = state.segments
        if (segments.isEmpty()) return

        val centerX = ctx.canvasSize.width / 2f
        val centerY = ctx.canvasSize.height / 2f

        // 段可见性推进（记录每段「变可见的时刻」，入场动画从此刻起算）
        if (!state.isExiting) {
            val visibleIndex = computeVisibleIndex(state, timeSec)
            for (i in segments.indices) {
                val seg = segments[i]
                if (i <= visibleIndex) {
                    if (seg.visibleSince < 0.0) seg.visibleSince = timeSec
                } else if (seg.visibleSince >= 0.0) {
                    seg.visibleSince = -1.0
                }
            }
        }

        // VisualizerTilt.tsx:646 —— flex-col / items-center / justify-center
        var segTop = centerY - state.totalHeight / 2f
        // 移植 VisualizerTilt.tsx:357-368 `getColors()` 的**默认**分支：
        //   `{ normal: theme.primaryColor, tilt: theme.accentColor }`
        // ⇒ `normal`（常规行正文）= 基础文字色；`tilt`（倾斜行高亮）= 高亮色。
        // ⚠️ 配色按**语义**对应（Folia 的 `theme.primaryColor` 是近白的基础文字色，
        //    `theme.accentColor` 才是鲜艳蓝的高亮色）⇒ 见 LyricsStageColors。
        val normalArgb = LyricsStageColors.baseText(ctx)
        val tiltArgb = LyricsStageColors.accent(ctx)

        for (i in segments.indices) {
            val seg = segments[i]
            drawSegment(nc, seg, timeSec, groupAlpha, centerX, segTop, normalArgb, tiltArgb, state.exitStart)
            segTop += seg.lineBoxHeight + state.gap
        }
    }

    private fun drawSegment(
        nc: Canvas,
        seg: TiltSegmentState,
        timeSec: Double,
        groupAlpha: Float,
        centerX: Float,
        segTop: Float,
        normalArgb: Int,
        tiltArgb: Int,
        exitStart: Double,
    ) {
        val n = seg.glyphs.size
        if (n == 0) return

        val enterDuration = if (seg.isTilt) TILT_ENTER_DURATION else NORMAL_ENTER_DURATION
        val visible = seg.visibleSince >= 0.0
        val enterProgress = if (visible) {
            clamp01(((timeSec - seg.visibleSince) / enterDuration).toFloat())
        } else {
            0f
        }
        val enterEased = TiltCssEasing.tiltEase(enterProgress)

        var containerAlpha = enterEased
        var containerY = (1f - enterEased) * (if (seg.isTilt) TILT_ENTER_Y else NORMAL_ENTER_Y)
        var containerScale = if (seg.isTilt) {
            TILT_ENTER_SCALE + (1f - TILT_ENTER_SCALE) * enterEased
        } else {
            1f
        }

        // 行容器离场（VisualizerTilt.tsx:436 / :486 的 exit 变体）
        if (exitStart >= 0.0) {
            val exitProgress = clamp01(((timeSec - exitStart) / enterDuration).toFloat())
            val exitEased = TiltCssEasing.tiltEase(exitProgress)
            containerAlpha = containerAlpha * (1f - exitEased)
            val exitY = if (seg.isTilt) TILT_EXIT_Y else NORMAL_EXIT_Y
            containerY = containerY + (exitY - containerY) * exitEased
            if (seg.isTilt) containerScale = containerScale + (TILT_EXIT_SCALE - containerScale) * exitEased
        }

        val alpha = containerAlpha * groupAlpha
        if (alpha <= 0.002f) return

        // 行盒内的基线
        val contentHeight = -seg.ascent + seg.descent
        val halfLeading = (seg.lineBoxHeight - contentHeight) / 2f
        val baselineY = segTop + halfLeading - seg.ascent
        val containerOriginY = segTop + seg.lineBoxHeight / 2f

        paintText.typeface = if (seg.isTilt) typefaceTilt else typefaceNormal
        paintText.textSize = seg.fontSizePx
        paintText.letterSpacing = seg.letterSpacingEm
        val colorArgb = if (seg.isTilt) tiltArgb else normalArgb

        val delayStep = if (seg.isTilt) TILT_CHAR_DELAY_STEP else NORMAL_CHAR_DELAY_STEP
        var x = centerX - seg.totalWidth / 2f

        for (gi in 0 until n) {
            val glyph = seg.glyphs[gi]
            val isSpace = seg.spaceFlags[gi]
            val ci = seg.ciIndex[gi]

            // ── 脉冲缩放（VisualizerTilt.tsx:409-424）──
            var targetScale = 1f
            if (!isSpace) {
                val timing = if (ci >= 0 && ci < seg.charTimings.size) seg.charTimings[ci] else null
                if (timing != null) {
                    val intensity = TiltLayout.getCharPulseIntensity(timeSec, timing)
                    targetScale = 1f + intensity * (if (seg.isTilt) TILT_PULSE_GAIN else NORMAL_PULSE_GAIN)
                }
            }
            // CSS `transition: transform 0.06s ease-out`（VisualizerTilt.tsx:470 / :528）
            if (targetScale != seg.scaleTarget[gi]) {
                seg.scaleTarget[gi] = targetScale
                seg.scaleFrom[gi] = seg.scaleCur[gi]
                seg.scaleStart[gi] = timeSec
            }
            val scaleProgress = clamp01(((timeSec - seg.scaleStart[gi]) / CHAR_SCALE_TRANSITION).toFloat())
            val scaleEased = TiltCssEasing.easeOut(scaleProgress)
            val scale = seg.scaleFrom[gi] + (targetScale - seg.scaleFrom[gi]) * scaleEased
            seg.scaleCur[gi] = scale

            // ── 单字入场（VisualizerTilt.tsx:454-472 / :509-534）──
            val delay = if (isSpace) 0.0 else ci * delayStep
            val charProgress = if (visible) {
                clamp01(((timeSec - seg.visibleSince - delay) / CHAR_ENTER_DURATION).toFloat())
            } else {
                0f
            }
            val charEased = TiltCssEasing.tiltEase(charProgress)
            val glyphAlpha = charEased * alpha
            if (glyphAlpha > 0.002f) {
                // 倾斜行的上下交错（VisualizerTilt.tsx:501-518）
                var glyphY = 0f
                if (seg.isTilt && !isSpace) {
                    val yStagger = if (ci % 2 == 0) -1f else 1f
                    glyphY = yStagger * seg.yOffsetPx * (2f - charEased)
                }

                val boxWidth = seg.glyphWidths[gi]
                val originX = x + boxWidth / 2f
                val originY = baselineY + (seg.ascent + seg.descent) / 2f

                nc.save()
                // 容器变换（CSS: translateY + scale，origin = 50% 50%）
                nc.translate(centerX, containerOriginY)
                nc.translate(0f, containerY)
                nc.scale(containerScale, containerScale)
                nc.translate(-centerX, -containerOriginY)
                // 字符变换（CSS: translateY + scale，origin = 50% 50%）
                nc.translate(originX, originY)
                nc.translate(0f, glyphY)
                nc.scale(scale, scale)
                nc.translate(-originX, -originY)

                applyColor(paintText, colorArgb, glyphAlpha)
                nc.drawText(if (isSpace) NBSP else glyph, x, baselineY, paintText)
                nc.restore()
            }

            x += seg.advances[gi]
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private fun outerEnterAlpha(groupStart: Double, timeSec: Double): Float =
        TiltCssEasing.easeInOut(clamp01(((timeSec - groupStart) / OUTER_ENTER_DURATION).toFloat()))

    private fun outerExitAlpha(exitStart: Double, timeSec: Double): Float =
        TiltCssEasing.easeInOut(clamp01(((timeSec - exitStart) / OUTER_EXIT_DURATION).toFloat()))

    /** 把调色板颜色与动画 alpha 合成为 Paint 的 ARGB（alpha 相乘，不相乘平方）。 */
    private fun applyColor(paint: Paint, argb: Int, alpha: Float) {
        val baseAlpha = (argb ushr 24) and 0xFF
        val a = (baseAlpha * alpha).toInt().coerceIn(0, 255)
        paint.color = (argb and 0x00FFFFFF) or (a shl 24)
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    // ── 版面状态 ─────────────────────────────────────────────────────────────

    private class TiltSegmentState {
        var segment: TiltLayout.TiltSegment? = null
        var isTilt = false
        var glyphs: Array<String> = emptyArray()
        var glyphWidths: FloatArray = FloatArray(0)
        var advances: FloatArray = FloatArray(0)
        var ciIndex: IntArray = IntArray(0)
        var spaceFlags: BooleanArray = BooleanArray(0)
        var charTimings: List<TiltLayout.CharTiming> = emptyList()
        var fontSizePx = 0f
        var letterSpacingEm = 0f
        var spaceMinWidthEm = 0f
        var lineHeight = 1f
        var yOffsetPx = 0f
        var ascent = 0f
        var descent = 0f
        var lineBoxHeight = 0f
        var totalWidth = 0f
        var segStart = 0.0
        var segEnd = 0.0

        // 运行时
        var visibleSince = -1.0
        var scaleCur = FloatArray(0)
        var scaleFrom = FloatArray(0)
        var scaleTarget = FloatArray(0)
        var scaleStart = DoubleArray(0)
    }

    private class TiltState {
        var line: LyricLine? = null
        var segments: Array<TiltSegmentState> = emptyArray()
        var segmentStarts = DoubleArray(0)
        var segmentEnds = DoubleArray(0)
        var totalHeight = 0f
        var gap = GAP_Y_3
        var groupStart = 0.0
        var exitStart = -1.0
        var scaleMultiplier = 1f

        val isExiting: Boolean get() = exitStart >= 0.0
    }
}

/**
 * CSS `cubic-bezier()` 求值（无分配；二分法 24 次迭代，精度 2^-24）。
 *
 * 用到的三条曲线：
 *  - `ease`    = (0.25, 0.46, 0.45, 0.94) —— Folia 的倾斜模式统一缓动
 *  - `ease-out`= (0, 0, 0.58, 1)             —— CSS `ease-out`（字符缩放过渡）
 *  - `ease-in-out` = (0.42, 0, 0.58, 1)      —— CSS `easeInOut`（外层容器淡入淡出）
 */
private object TiltCssEasing {

    fun tiltEase(t: Float): Float = cubicBezier(0.25f, 0.46f, 0.45f, 0.94f, t)

    fun easeOut(t: Float): Float = cubicBezier(0f, 0f, 0.58f, 1f, t)

    fun easeInOut(t: Float): Float = cubicBezier(0.42f, 0f, 0.58f, 1f, t)

    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var u = t
        var i = 0
        while (i < 24) {
            if (axis(x1, x2, u) < t) lo = u else hi = u
            u = 0.5f * (lo + hi)
            i++
        }
        return axis(y1, y2, u)
    }

    /** 三次贝塞尔的单轴取值 */
    private fun axis(a: Float, b: Float, u: Float): Float {
        val iu = 1f - u
        return 3f * iu * iu * u * a + 3f * iu * u * u * b + u * u * u
    }
}
