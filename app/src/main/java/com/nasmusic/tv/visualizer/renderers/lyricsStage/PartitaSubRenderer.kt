package com.nasmusic.tv.visualizer.renderers.lyricsStage

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.renderers.lyricsStage.partita.PartitaLayout
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// 移植自 Folia (AGPL-3.0): src/components/visualizer/partita/PartitaChunk.tsx — Project Folia version 0.5.27-a16525c
//
// 另外还移植了：
//   - src/components/visualizer/partita/partitaLayout.ts（分栏布局，已在 PartitaLayout.kt）
//   - src/components/visualizer/glowWordVariants.ts（词级容器 / 正文两套变体）
//
// 「分栏引导」子模式：把当前行切成若干 chunk（长度刻意不均匀）竖直排成一列，
// 每个 chunk 带一条 L 形引导线，chunk 内的词走 classic GlowWord 的 waiting / active / passed 三态。
//
// 浏览器 → Android 的替换（全部记在此处）：
//  ① framer-motion 的 variants → 本文件显式实现：容器（opacity / scale / x / y / rotate）与正文（颜色 / 模糊）。
//  ② framer-motion 的 `spring(stiffness 200, damping 20)` → 解析解（ζ = 0.7071、ω_d = 10）。
//  ③ CSS `filter: blur(Npx)` → `BlurMaskFilter`（半径量化到 1px 档位，避免逐帧分配）。
//     本仓已验证：`BlurMaskFilter` 只在**软件画布**上生效（见 QianliScrollRenderer 的注记），
//     硬件画布上可能退化为无模糊 —— 这是已知偏差，不影响可读性。
//  ④ CSS `box-shadow: 0 0 10px color` → `Paint.setShadowLayer(10, 0, 0, color)`。
//  ⑤ Folia 的 `theme.animationIntensity` → NASMusicTV 无此设置，按画质档映射
//     （LOW→calm / HIGH→chaotic / 其它→normal）。
//  ⑥ Folia 的 `theme.wordColors`（关键词着色表）→ NASMusicTV 没有 ⇒ 传空列表，
//     `getActiveColor` 恒等于 `theme.accentColor`（= `ctx.palette.secondary`）。
//  ⑦ Folia 的 `theme.primaryColor` / `theme.accentColor` → `ctx.palette.accent` / `ctx.palette.secondary`。
class PartitaSubRenderer : LyricsSubRenderer {

    // ── Folia 常量 ────────────────────────────────────────────────────────────

    private companion object {
        /** partitaLayout.ts:495-496 `DEFAULT_PARTITA_TUNING.staggerMin / staggerMax` */
        const val STAGGER_MIN = 20f
        const val STAGGER_MAX = 100f

        /** partitaLayout.ts:61 `PARTITA_WORD_LAYOUT_STYLE` = `marginInlineEnd: '0.8rem'` */
        const val WORD_MARGIN_END_REM = 0.8f

        /** PartitaChunk.tsx:74 `padding: '0.2rem 0.5rem'` */
        const val CHUNK_PADDING_Y_REM = 0.2f
        const val CHUNK_PADDING_X_REM = 0.5f

        /** CSS 根字号（浏览器默认 16px） */
        const val ROOT_REM_PX = 16f

        /** glowWordOverlap.ts:8 `GLOW_WORD_LINE_HEIGHT`（词盒的行高） */
        const val GLOW_WORD_LINE_HEIGHT = 1.22f

        /** VisualizerPartita.tsx:146 `mainFontSize` 的 clamp 三元组 */
        const val FONT_MIN_REM = 2.5f
        const val FONT_PREFERRED_VW = 5.5f
        const val FONT_MAX_REM = 4.5f

        /** VisualizerPartita.tsx:145 超过 40 个字素时整体缩到 0.8 */
        const val DENSE_GRAPHEME_THRESHOLD = 40
        const val DENSE_SCALE = 0.8f

        /** VisualizerPartita.tsx:155-162 歌词容器的漂浮动画参数（按 intensity） */
        const val FLOAT_DISTANCE_CALM = 10f
        const val FLOAT_DURATION_CALM = 8.5
        const val FLOAT_DISTANCE_NORMAL = 14f
        const val FLOAT_DURATION_NORMAL = 7.0
        const val FLOAT_DISTANCE_CHAOTIC = 18f
        const val FLOAT_DURATION_CHAOTIC = 5.8

        /** PartitaChunk.tsx:79-81 等待态的横向偏移 */
        const val CHUNK_WAITING_OFFSET = 40f

        /** PartitaChunk.tsx:78 等待态的缩放 */
        const val CHUNK_WAITING_SCALE = 0.85f

        /** PartitaChunk.tsx:90-92 非 active 态的过渡 */
        const val CHUNK_TRANSITION_DURATION = 0.4

        /** PartitaChunk.tsx:89 active 态里 opacity 单独走 0.1s 的 tween */
        const val CHUNK_OPACITY_DURATION = 0.1

        /** PartitaChunk.tsx:107-113 / :127-133 引导线的过渡 */
        const val GUIDE_TRANSITION_DURATION = 0.4

        /** PartitaChunk.tsx:101-103 垂直引导线几何（px） */
        const val GUIDE_VERTICAL_HEIGHT = 32f
        const val GUIDE_VERTICAL_BOTTOM = 16f
        const val GUIDE_VERTICAL_OFFSET = 8f

        /** PartitaChunk.tsx:120-122 水平引导线几何（px） */
        const val GUIDE_HORIZONTAL_WIDTH_EXTRA = 36f
        const val GUIDE_HORIZONTAL_BOTTOM = 8f
        const val GUIDE_HORIZONTAL_OFFSET = 16f

        /** PartitaChunk.tsx:105 / :125 `box-shadow: 0 0 10px ...` */
        const val GUIDE_SHADOW_RADIUS = 10f

        /** PartitaChunk.tsx:105 垂直引导线阴影的 alpha 后缀 `45`（十六进制） */
        const val GUIDE_SHADOW_ALPHA_VERTICAL = 0x45

        /** PartitaChunk.tsx:125 水平引导线阴影的 alpha 后缀 `35`（十六进制） */
        const val GUIDE_SHADOW_ALPHA_HORIZONTAL = 0x35

        /** PartitaChunk.tsx:104 引导线的底色（已过 / 等待） */
        const val GUIDE_PASSED_ALPHA = 0.22f
        const val GUIDE_WAITING_ALPHA = 0.14f

        /** glowWordVariants.ts:37-43 词的 waiting 变体 */
        const val WORD_WAITING_SCALE = 0.5f
        const val WORD_WAITING_ROTATE_EXTRA = 20f
        const val WORD_WAITING_DRIFT_X = 100f
        const val WORD_WAITING_DRIFT_Y = 50f

        /** glowWordVariants.ts:26 active 缩放 = `config.scale * GLOW_WORD_ACTIVE_SCALE` */
        const val WORD_ACTIVE_SCALE = 1.4f

        /** glowWordVariants.ts:38 passed 态的不透明度 */
        const val WORD_PASSED_ALPHA_NORMAL = 0.82f
        const val WORD_PASSED_ALPHA_CHAOTIC = 0.9f

        /** glowWordVariants.ts:19 / :49 词的过渡时长 */
        const val WORD_WAITING_TRANSITION = 0.4
        const val WORD_PASSED_TRANSITION = 0.5

        /** glowWordVariants.ts:34 active 态里 opacity 单独走 0.1s 的 tween */
        const val WORD_BODY_OPACITY_DURATION = 0.1

        /** glowWordVariants.ts:47 passed 态的旋转过渡时长（linear） */
        const val WORD_PASSED_ROTATE_DURATION = 5.0

        /** glowWordVariants.ts:65 active 态正文的颜色过渡时长（缺省） */
        const val WORD_BODY_COLOR_DURATION_DEFAULT = 0.2

        /** glowWordVariants.ts:66 active 态正文的模糊过渡时长（按 wordRevealMode） */
        const val WORD_BODY_BLUR_INSTANT = 0.08
        const val WORD_BODY_BLUR_FAST = 0.12
        const val WORD_BODY_BLUR_NORMAL = 0.2

        /** glowWordVariants.ts:58 正文 waiting 态的模糊半径（px） */
        const val WORD_BODY_WAITING_BLUR = 10f

        /** glowWordVariants.ts:76 正文 passed 态的颜色过渡时长（按 wordRevealMode） */
        const val WORD_BODY_PASSED_COLOR_INSTANT = 0.12
        const val WORD_BODY_PASSED_COLOR_FAST = 0.24
        const val WORD_BODY_PASSED_COLOR_NORMAL = 0.8

        /** glowWordVariants.ts:77 正文 passed 态的模糊过渡时长（按 wordRevealMode） */
        const val WORD_BODY_PASSED_BLUR_INSTANT = 0.12
        const val WORD_BODY_PASSED_BLUR_FAST = 0.2
        const val WORD_BODY_PASSED_BLUR_NORMAL = 0.5

        /** 模糊半径的量化档位（避免逐帧 new BlurMaskFilter） */
        const val BLUR_STEPS = 11

        /** VisualizerPartita.tsx:213 列容器的最小高度 / 最小宽度 / 内边距 */
        const val COLUMN_MIN_HEIGHT = 384f
        const val COLUMN_MIN_WIDTH = 60.8f
        const val COLUMN_PADDING_X = 12f

        /** framer-motion 默认 tween 时长（未显式指定 transition 时） */
        const val DEFAULT_TWEEN_DURATION = 0.3
    }

    // ── 绘制资源 ──────────────────────────────────────────────────────────────

    private val paintWord = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGuide = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── 逐字形辉光层（移植 GlowWord.tsx:86-119 + wordGlow.ts）──
    // 外圈 = 全透明度阴影；内圈 = 字形本体透明、只留光晕（半径按 DROP_SHADOW_RADIUS_SCALE 缩）。
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 给 ARGB 颜色换透明度（不改 RGB） */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** 词用粗体（Folia 的 `resolveThemeFontWeight(theme, 700)`） */
    private val typefaceBold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    /** 复用的字体度量（`Paint.fontMetrics` 每次取值都会 new ⇒ 逐帧路径必须用带参重载） */
    private val fontMetrics = Paint.FontMetrics()

    /** 量化的模糊滤镜（半径 0..10px，每档一个） */
    private val blurFilters: Array<BlurMaskFilter?> = Array(BLUR_STEPS) { index ->
        if (index <= 0) null else BlurMaskFilter(index.toFloat(), BlurMaskFilter.Blur.NORMAL)
    }

    // ── 运行时状态 ────────────────────────────────────────────────────────────

    private var activeState: PartitaState? = null
    private var exitingState: PartitaState? = null
    private var activeLineIndex = -1
    private var activeLineStart = Double.NaN

    override fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload) {
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
        if (exiting != null) {
            val motion = exiting.profile?.let { GlowWordCore.lineContainerMotion(it) }
            val exitDuration = motion?.exitDurationSec ?: DEFAULT_TWEEN_DURATION
            if (timeSec - exiting.exitStart >= exitDuration) {
                exitingState = null
                exiting = null
            }
        }
        if (active == null && exiting == null) return

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (active != null) drawPartitaGroup(nc, active, timeSec, ctx)
            if (exiting != null) drawPartitaGroup(nc, exiting, timeSec, ctx)
        }
    }

    override fun onExit() {
        activeState = null
        exitingState = null
        activeLineIndex = -1
        activeLineStart = Double.NaN
    }

    // ── 换行检测与版面构建 ────────────────────────────────────────────────────

    private fun syncLine(lyrics: LyricsStagePayload, ctx: RenderContext, timeSec: Double) {
        val line = lyrics.current
        val index = lyrics.currentIndex

        val changed = if (line == null) {
            activeState != null
        } else {
            activeLineIndex != index || activeLineStart != line.startTime || activeState == null
        }
        if (!changed) return

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
     * 构建一行的版面。
     *
     * 移植 partitaLayout.ts:139-248 `buildSequentialColumns` + VisualizerPartita.tsx:91-151。
     */
    private fun buildState(line: LyricLine, ctx: RenderContext, nowSec: Double): PartitaState {
        val state = PartitaState()
        state.groupStart = nowSec
        state.exitStart = -1.0
        state.intensity = intensityOf(ctx.quality)
        state.baseColor = ctx.palette.accent.toArgb()
        state.activeColor = ctx.palette.secondary.toArgb()

        val result = PartitaLayout.buildSequentialColumns(
            line,
            ctx.canvasSize.height,
            state.intensity,
            STAGGER_MIN,
            STAGGER_MAX,
        )
        state.totalGraphemes = result.totalGraphemes

        val viewportWidth = ctx.canvasSize.width
        // VisualizerPartita.tsx:145-146
        val densityScale = if (result.totalGraphemes > DENSE_GRAPHEME_THRESHOLD) DENSE_SCALE else 1f
        state.fontSizePx = cssClampFontSize(
            FONT_MIN_REM,
            FONT_PREFERRED_VW,
            FONT_MAX_REM,
            densityScale,
            viewportWidth,
        )

        val column = result.columns.firstOrNull()
        val chunks = ArrayList<PartitaChunkState>()

        paintWord.typeface = typefaceBold
        paintWord.textSize = state.fontSizePx
        paintWord.letterSpacing = 0f
        paintWord.getFontMetrics(fontMetrics)
        state.ascent = fontMetrics.ascent
        state.descent = fontMetrics.descent

        if (column != null) {
            for (chunk in column.words) {
                val chunkState = PartitaChunkState()
                chunkState.chunk = chunk
                chunkState.isLeftGuide = chunk.rowIndex % 2 == 0
                chunkState.marginBottomPx = remToPx(chunk.config.marginBottom)

                // 移植 partitaLayout.ts:97-125 `buildPartitaWordConfigs`
                val wordConfigs = PartitaLayout.buildPartitaWordConfigs(
                    chunk.displayWords,
                    chunk.config,
                    state.intensity,
                )

                val words = ArrayList<PartitaWordState>(chunk.displayWords.size)
                var cursor = 0f
                for (i in chunk.displayWords.indices) {
                    val word = chunk.displayWords[i]
                    val wordState = PartitaWordState()
                    wordState.word = word
                    wordState.config = wordConfigs[i]
                    wordState.text = word.text
                    val w = paintWord.measureText(word.text)
                    wordState.width = w
                    wordState.relX = cursor
                    // ── 逐字形辉光层的数据（移植 GlowWord.tsx:90-109）──
                    // Folia 的辉光层是**逐字形**的 span（每个字形单独一个 span）⇒
                    // 每个字形都要单独定位。量在 buildState 里做（逐帧量会慢且分配）。
                    val graphemes = GraphemeSplitter.split(word.text)
                    val offsets = FloatArray(graphemes.size)
                    var acc = 0f
                    for (g in graphemes.indices) {
                        offsets[g] = acc
                        acc += paintWord.measureText(graphemes[g])
                    }
                    wordState.graphemeTexts = graphemes.toTypedArray()
                    wordState.graphemeOffsets = offsets
                    wordState.graphemeTimings = GraphemeTimingBuilder.buildFor(word)
                    cursor += w + remToPx(WORD_MARGIN_END_REM)
                    words.add(wordState)
                }
                chunkState.words = words.toTypedArray()
                chunkState.contentWidth = cursor
                chunkState.width = cursor + remToPx(CHUNK_PADDING_X_REM) * 2f
                chunkState.height =
                    GLOW_WORD_LINE_HEIGHT * state.fontSizePx + remToPx(CHUNK_PADDING_Y_REM) * 2f

                // 初始状态 = framer 的 `initial="waiting"`（glowWordVariants.ts:16-23 / PartitaChunk.tsx:76-83）
                chunkState.baseX = chunk.config.x
                chunkState.baseY = chunk.config.y
                chunkState.baseRotate = chunk.config.rotate
                chunkState.initWaiting()

                for (wordState in words) {
                    wordState.initWaiting(state.baseColor)
                }

                chunks.add(chunkState)
            }
        }

        state.chunks = chunks.toTypedArray()

        // ── 列的排版（VisualizerPartita.tsx:201-236）──
        var innerWidth = 0f
        var innerHeight = 0f
        for (chunk in chunks) {
            if (chunk.width > innerWidth) innerWidth = chunk.width
            innerHeight += chunk.height + chunk.marginBottomPx
        }
        state.innerWidth = innerWidth
        state.innerHeight = innerHeight
        state.columnWidth = max(COLUMN_MIN_WIDTH, innerWidth + COLUMN_PADDING_X * 2f)
        state.columnHeight = max(COLUMN_MIN_HEIGHT, innerHeight)

        // 漂浮动画参数（VisualizerPartita.tsx:154-174）
        when (state.intensity) {
            "calm" -> {
                state.floatDistance = FLOAT_DISTANCE_CALM
                state.floatDuration = FLOAT_DURATION_CALM
            }
            "chaotic" -> {
                state.floatDistance = FLOAT_DISTANCE_CHAOTIC
                state.floatDuration = FLOAT_DURATION_CHAOTIC
            }
            else -> {
                state.floatDistance = FLOAT_DISTANCE_NORMAL
                state.floatDuration = FLOAT_DURATION_NORMAL
            }
        }

        state.line = line
        state.profile = GlowWordCore.resolveProfile(line)
        return state
    }

    /** Folia 的 `theme.animationIntensity` → NASMusicTV 的画质档 */
    private fun intensityOf(quality: VisualQuality): String = when (quality) {
        VisualQuality.LOW -> "calm"
        VisualQuality.HIGH -> "chaotic"
        else -> "normal"
    }

    /** CSS `rem` → px（根字号 16px） */
    private fun remToPx(rem: String): Float {
        val value = rem.removeSuffix("rem").toFloatOrNull() ?: 0f
        return value * ROOT_REM_PX
    }

    /** CSS `rem` → px（根字号 16px） */
    private fun remToPx(rem: Float): Float = rem * ROOT_REM_PX

    /** CSS `clamp(min, preferred, max)` 的 px 换算 */
    private fun cssClampFontSize(
        minRem: Float,
        preferredVw: Float,
        maxRem: Float,
        scale: Float,
        viewportWidth: Float,
    ): Float {
        val minPx = minRem * ROOT_REM_PX * scale
        val preferredPx = preferredVw * viewportWidth * scale / 100f
        val maxPx = maxRem * ROOT_REM_PX * scale
        return max(minPx, min(preferredPx, maxPx))
    }

    // ── 绘制 ─────────────────────────────────────────────────────────────────

    private fun drawPartitaGroup(
        nc: Canvas,
        state: PartitaState,
        timeSec: Double,
        ctx: RenderContext,
    ) {
        val chunks = state.chunks
        if (chunks.isEmpty()) return

        val profile = state.profile ?: return
        val motion = GlowWordCore.lineContainerMotion(profile)

        // ── 行容器的进出（glowWordTiming.ts:58-91 `getGlowWordLineContainerMotion`）──
        var groupAlpha = 1f
        var groupScale = 1f
        if (state.exitStart >= 0.0) {
            val exitProgress = PartitaUtil.clamp01(((timeSec - state.exitStart) / motion.exitDurationSec).toFloat())
            val exitEased = PartitaCssEasing.easeOut(exitProgress)
            groupAlpha = 1f - exitEased
            groupScale = 1f + (motion.exitScale - 1f) * exitEased
        } else {
            val enterDuration = when (profile.lineTransitionMode) {
                "none" -> 0.0
                "fast" -> motion.enterDurationSec
                else -> DEFAULT_TWEEN_DURATION
            }
            if (enterDuration > 0.0) {
                val enterProgress = PartitaUtil.clamp01(((timeSec - state.groupStart) / enterDuration).toFloat())
                val enterEased = PartitaCssEasing.easeOut(enterProgress)
                groupAlpha = motion.initialOpacity + (1f - motion.initialOpacity) * enterEased
                groupScale = motion.initialScale + (1f - motion.initialScale) * enterEased
            }
        }
        if (groupAlpha <= 0.002f) return

        // ── 漂浮动画（VisualizerPartita.tsx:154-174 / :183-190）──
        val floatPhase = floatPhase(state, timeSec)
        val floatY = floatKeyframe(floatPhase, 0f, -state.floatDistance, 0f, state.floatDistance * 0.45f, 0f)
        val floatScale = floatKeyframe(floatPhase, 1f, 1.01f, 1f, 0.995f, 1f)

        val centerX = ctx.canvasSize.width / 2f
        val centerY = ctx.canvasSize.height / 2f

        val columnLeft = centerX - state.columnWidth / 2f
        val columnTop = centerY - state.columnHeight / 2f
        val innerLeft = columnLeft + (state.columnWidth - state.innerWidth) / 2f
        val innerTop = columnTop + (state.columnHeight - state.innerHeight) / 2f

        val baseColor = state.baseColor
        val activeColor = state.activeColor

        var cursorTop = innerTop
        for (chunk in chunks) {
            val chunkLeft = innerLeft + (state.innerWidth - chunk.width) / 2f
            drawChunk(
                nc = nc,
                chunk = chunk,
                profile = profile,
                state = state,
                timeSec = timeSec,
                chunkLeft = chunkLeft,
                chunkTop = cursorTop,
                groupAlpha = groupAlpha,
                groupScale = groupScale,
                groupOriginX = centerX,
                groupOriginY = centerY,
                floatY = floatY,
                floatScale = floatScale,
                floatOriginX = centerX,
                floatOriginY = centerY,
                baseColor = baseColor,
                activeColor = activeColor,
            )
            cursorTop += chunk.height + chunk.marginBottomPx
        }
    }

    /**
     * 绘制一个 chunk（移植 PartitaChunk.tsx:60-205）。
     */
    private fun drawChunk(
        nc: Canvas,
        chunk: PartitaChunkState,
        profile: GlowWordProfile,
        state: PartitaState,
        timeSec: Double,
        chunkLeft: Float,
        chunkTop: Float,
        groupAlpha: Float,
        groupScale: Float,
        groupOriginX: Float,
        groupOriginY: Float,
        floatY: Float,
        floatScale: Float,
        floatOriginX: Float,
        floatOriginY: Float,
        baseColor: Int,
        activeColor: Int,
    ) {
        val chunkWords = chunk.chunk?.chunkWords ?: return
        if (chunkWords.isEmpty()) return

        // ── chunk 状态机（PartitaChunk.tsx:35-48）──
        val chunkStartTime = chunkWords[0].startTime
        val chunkEndTime = GlowWordCore.activeEndTime(chunkWords[chunkWords.size - 1], profile)
        val status = when {
            timeSec >= chunkStartTime - profile.wordLookahead && timeSec <= chunkEndTime ->
                GlowWordStatus.ACTIVE
            timeSec > chunkEndTime -> GlowWordStatus.PASSED
            else -> GlowWordStatus.WAITING
        }
        if (chunk.status != status) {
            chunk.status = status
            chunk.beginTransition(status, timeSec)
        }
        chunk.advance(timeSec)

        val alpha = chunk.opacity * groupAlpha
        if (alpha <= 0.002f) return

        val chunkWidth = chunk.width
        val chunkHeight = chunk.height
        // chunk 的 transform-origin = 盒子中心（CSS 默认 50% 50%）
        val originX = chunkWidth / 2f
        val originY = chunkHeight / 2f

        nc.save()
        // ── 漂浮变换（歌词容器，VisualizerPartita.tsx:183-190）──
        nc.translate(floatOriginX, floatOriginY)
        nc.translate(0f, floatY)
        nc.scale(floatScale, floatScale)
        nc.translate(-floatOriginX, -floatOriginY)
        // ── 行容器变换（glowWordTiming.ts:58-91 的 initial/animate 的 scale）──
        nc.translate(groupOriginX, groupOriginY)
        nc.scale(groupScale, groupScale)
        nc.translate(-groupOriginX, -groupOriginY)
        // ── chunk 的排版位置（flex 列布局算出的位置）──
        nc.translate(chunkLeft, chunkTop)
        // ── chunk 容器变换（CSS: translate + rotate + scale）──
        nc.translate(originX, originY)
        nc.translate(chunk.x, chunk.y)
        nc.rotate(chunk.rotate)
        nc.scale(chunk.scale, chunk.scale)
        nc.translate(-originX, -originY)

        // ── 引导线（PartitaChunk.tsx:95-182，chunk 的绝对定位子元素）──
        drawGuideLines(
            nc = nc,
            chunk = chunk,
            chunkWidth = chunkWidth,
            chunkHeight = chunkHeight,
            alpha = alpha,
            activeColor = activeColor,
        )

        // ── 词（PartitaChunk.tsx:184-204，classic GlowWord 动画）──
        val paddingX = remToPx(CHUNK_PADDING_X_REM)
        val paddingY = remToPx(CHUNK_PADDING_Y_REM)
        for (wordState in chunk.words) {
            drawWord(
                nc = nc,
                wordState = wordState,
                profile = profile,
                state = state,
                intensity = state.intensity,
                timeSec = timeSec,
                wordLeft = paddingX + wordState.relX,
                wordTop = paddingY,
                groupAlpha = alpha,
                baseColor = baseColor,
                activeColor = activeColor,
            )
        }
        nc.restore()
    }

    /**
     * L 形引导线（PartitaChunk.tsx:95-182）：一条竖线 + 一条横线。
     *
     * 坐标是 **chunk 局部坐标**（引导线是 chunk 的绝对定位子元素）。
     */
    private fun drawGuideLines(
        nc: Canvas,
        chunk: PartitaChunkState,
        chunkWidth: Float,
        chunkHeight: Float,
        alpha: Float,
        activeColor: Int,
    ) {
        // 引导线的伸展 / 淡入进度（waiting ⇒ 0，否则 1；过渡 0.4s easeOut）
        val guideScale = chunk.guideScale
        val guideAlpha = chunk.guideAlpha
        if (guideAlpha <= 0.002f) return

        // 底色：active ⇒ activeColor；passed ⇒ 白 0.22；waiting ⇒ 白 0.14
        var guideRgb = 0xFFFFFF
        var guideColorAlpha = (255f * GUIDE_WAITING_ALPHA).toInt()
        if (chunk.status == GlowWordStatus.ACTIVE) {
            guideRgb = activeColor and 0x00FFFFFF
            guideColorAlpha = (activeColor ushr 24) and 0xFF
        } else if (chunk.status == GlowWordStatus.PASSED) {
            guideColorAlpha = (255f * GUIDE_PASSED_ALPHA).toInt()
        }

        paintGuide.style = Paint.Style.STROKE
        paintGuide.strokeWidth = 1f
        paintGuide.maskFilter = null
        paintGuide.color = guideRgb or
            ((guideColorAlpha * alpha * guideAlpha).toInt().coerceIn(0, 255) shl 24)

        // 垂直引导线：`left: -8px` / `right: -8px`、`bottom: -16px`、`height: 32px`
        val verticalX = if (chunk.isLeftGuide) {
            -GUIDE_VERTICAL_OFFSET
        } else {
            chunkWidth + GUIDE_VERTICAL_OFFSET
        }
        val verticalBottom = chunkHeight + GUIDE_VERTICAL_BOTTOM
        val verticalTop = verticalBottom - GUIDE_VERTICAL_HEIGHT * guideScale

        // 水平引导线：`left: -16px` / `right: -16px`、`bottom: -8px`、`width: calc(100% + 36px)`
        val horizontalY = chunkHeight + GUIDE_HORIZONTAL_BOTTOM
        val horizontalWidth = chunkWidth + GUIDE_HORIZONTAL_WIDTH_EXTRA
        val horizontalLeft = if (chunk.isLeftGuide) {
            -GUIDE_HORIZONTAL_OFFSET
        } else {
            chunkWidth + GUIDE_HORIZONTAL_OFFSET - horizontalWidth
        }

        // 垂直引导线的阴影（PartitaChunk.tsx:105，alpha 后缀 45）
        // ⚠️ CSS 里 box-shadow 属于元素的一部分 ⇒ 元素的 opacity 同样作用在阴影上。
        if (chunk.status == GlowWordStatus.ACTIVE) {
            val shadowA = ((activeColor ushr 24) and 0xFF) * GUIDE_SHADOW_ALPHA_VERTICAL / 255f * alpha * guideAlpha
            val shadowColor = (activeColor and 0x00FFFFFF) or (shadowA.toInt().coerceIn(0, 255) shl 24)
            paintGuide.setShadowLayer(GUIDE_SHADOW_RADIUS, 0f, 0f, shadowColor)
        } else {
            paintGuide.clearShadowLayer()
        }
        nc.drawLine(verticalX, verticalTop, verticalX, verticalBottom, paintGuide)

        // 水平引导线的阴影（PartitaChunk.tsx:125，alpha 后缀 35 而不是 45）
        if (chunk.status == GlowWordStatus.ACTIVE) {
            val shadowA = ((activeColor ushr 24) and 0xFF) * GUIDE_SHADOW_ALPHA_HORIZONTAL / 255f * alpha * guideAlpha
            val shadowColor = (activeColor and 0x00FFFFFF) or (shadowA.toInt().coerceIn(0, 255) shl 24)
            paintGuide.setShadowLayer(GUIDE_SHADOW_RADIUS, 0f, 0f, shadowColor)
        } else {
            paintGuide.clearShadowLayer()
        }
        nc.drawLine(horizontalLeft, horizontalY, horizontalLeft + horizontalWidth * guideScale, horizontalY, paintGuide)
        paintGuide.clearShadowLayer()
    }

    /**
     * 绘制一个词（classic GlowWord：容器变体 + 正文变体）。
     *
     * 移植 glowWordVariants.ts:12-83 + GlowWord.tsx:44-129。
     *
     * 坐标是 **chunk 局部坐标**（词是 chunk 的 flex 子项）。
     */
    private fun drawWord(
        nc: Canvas,
        wordState: PartitaWordState,
        profile: GlowWordProfile,
        state: PartitaState,
        intensity: String,
        timeSec: Double,
        wordLeft: Float,
        wordTop: Float,
        groupAlpha: Float,
        baseColor: Int,
        activeColor: Int,
    ) {
        val word = wordState.word ?: return

        // ── 词状态机（GlowWord.tsx:53-61）──
        val status = GlowWordCore.statusOf(word, timeSec, profile)
        if (wordState.status != status) {
            wordState.status = status
            // 辉光曲线的时间锚点（移植 wordGlow.ts 的 active / passed 起点）
            if (status == GlowWordStatus.ACTIVE) wordState.activeStart = timeSec
            if (status == GlowWordStatus.PASSED) wordState.passedAt = timeSec
            wordState.beginTransition(status, timeSec, profile, intensity, baseColor, activeColor)
        }
        wordState.advance(timeSec)

        val alpha = wordState.opacity * groupAlpha
        if (alpha <= 0.002f) return

        val wordBoxHeight = GLOW_WORD_LINE_HEIGHT * state.fontSizePx
        val wordOriginX = wordLeft + wordState.width / 2f
        val wordOriginY = wordTop + wordBoxHeight / 2f
        // 词的行盒基线（度量在 buildState 时取好 ⇒ 逐帧零分配）
        val contentHeight = -state.ascent + state.descent
        val halfLeading = (wordBoxHeight - contentHeight) / 2f
        val baselineY = wordTop + halfLeading - state.ascent

        paintWord.typeface = typefaceBold
        paintWord.textSize = state.fontSizePx
        paintWord.letterSpacing = 0f
        paintWord.maskFilter = blurFilters[blurStepIndex(wordState.bodyBlur)]
        val bodyAlpha = ((wordState.bodyColor ushr 24) and 0xFF) * alpha
        paintWord.color = (wordState.bodyColor and 0x00FFFFFF) or
            (bodyAlpha.toInt().coerceIn(0, 255) shl 24)

        nc.save()
        // 词容器变换（CSS: translate + rotate + scale，origin = 50% 50%）
        nc.translate(wordOriginX, wordOriginY)
        nc.translate(wordState.x, wordState.y)
        nc.rotate(wordState.rotate)
        nc.scale(wordState.scale, wordState.scale)
        nc.translate(-wordOriginX, -wordOriginY)

        // ── 辉光层（移植 GlowWord.tsx:86-119 + wordGlow.ts:94-167）──
        // ⚠️ 必须画在**正文层下面**：Folia 的 DOM 顺序是 glow span 在前、body span 在后
        //    （body 层带 `z-10`）⇒ 辉光是垫在字下面的一圈光晕。
        drawGlowLayer(
            nc, wordState, profile, baselineY, wordLeft, groupAlpha, activeColor, timeSec,
            state.fontSizePx,
        )

        nc.drawText(wordState.text, wordLeft, baselineY, paintWord)
        nc.restore()
        paintWord.maskFilter = null
    }

    /**
     * 移植 GlowWord.tsx:86-119 + wordGlow.ts:94-167 —— **逐字形辉光层**。
     *
     * Folia 的这层与 `classic` 共用（两者都渲染 `<GlowWord>`）⇒ 本仓也共用
     * （数学部分见 `GlowWordGlow`）。
     */
    private fun drawGlowLayer(
        nc: Canvas,
        wordState: PartitaWordState,
        profile: GlowWordProfile,
        baselineY: Float,
        wordLeft: Float,
        groupAlpha: Float,
        activeColor: Int,
        timeSec: Double,
        fontSizePx: Float,
    ) {
        val texts = wordState.graphemeTexts
        if (texts.isEmpty()) return
        val word = wordState.word ?: return
        val timings = wordState.graphemeTimings
        val count = minOf(texts.size, timings.size)
        if (count == 0) return

        val radii = GlowWordGlow.radiiFor(profile.wordRevealMode)
        val displayDuration = GlowWordCore.displayDuration(word, profile)

        glowOuterPaint.typeface = typefaceBold
        glowOuterPaint.textSize = fontSizePx
        glowInnerPaint.typeface = typefaceBold
        glowInnerPaint.textSize = fontSizePx

        for (g in 0 until count) {
            val timing = timings[g]
            val peak = GlowWordGlow.peakForGrapheme(
                status = wordState.status,
                wordRevealMode = profile.wordRevealMode,
                displayDuration = displayDuration,
                graphemeCount = texts.size,
                graphemeStart = timing.startTime,
                graphemeEnd = timing.endTime,
                wordStartTime = word.startTime,
                activeStart = wordState.activeStart,
                passedAt = wordState.passedAt,
                nowSec = timeSec,
            )
            if (!GlowWordGlow.isVisible(peak)) continue

            val alpha = GlowWordGlow.alphaOf(peak, groupAlpha)
            val gx = wordLeft + wordState.graphemeOffsets[g]

            // 外圈（移植 wordGlow.ts:57 `drop-shadow(0 0 outer * 0.4 * peak px color)`）
            glowOuterPaint.color = withAlpha(activeColor, alpha)
            glowOuterPaint.setShadowLayer(
                radii.second * GlowWordGlow.DROP_SHADOW_RADIUS_SCALE * peak,
                0f,
                0f,
                withAlpha(activeColor, alpha),
            )
            nc.drawText(texts[g], gx, baselineY, glowOuterPaint)

            // 内圈（字形本体透明 ⇒ 只留光晕；移植 wordGlow.ts:56 的 @0.7 透明度）
            glowInnerPaint.color = withAlpha(activeColor, 0)
            glowInnerPaint.setShadowLayer(
                radii.first * GlowWordGlow.DROP_SHADOW_RADIUS_SCALE * peak,
                0f,
                0f,
                withAlpha(
                    activeColor,
                    (alpha * GlowWordGlow.DROP_SHADOW_INNER_ALPHA).toInt().coerceIn(0, 255),
                ),
            )
            nc.drawText(texts[g], gx, baselineY, glowInnerPaint)
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    private fun blurStepIndex(blurPx: Float): Int {
        if (blurPx <= 0.05f) return 0
        val idx = Math.round(blurPx).toInt()
        return if (idx >= BLUR_STEPS) BLUR_STEPS - 1 else idx
    }

    /** 漂浮动画的循环相位（0..1） */
    private fun floatPhase(state: PartitaState, timeSec: Double): Float {
        val duration = state.floatDuration
        if (duration <= 0.0) return 0f
        val elapsed = timeSec - state.groupStart
        var phase = (elapsed / duration) % 1.0
        if (phase < 0.0) phase += 1.0
        return phase.toFloat()
    }

    /** 5 点关键帧的插值（段内用 easeInOut，VisualizerPartita.tsx:154-174） */
    private fun floatKeyframe(phase: Float, k0: Float, k1: Float, k2: Float, k3: Float, k4: Float): Float {
        val scaled = phase * 4f
        val index = floor(scaled).toInt().coerceIn(0, 3)
        val local = PartitaCssEasing.easeInOut(scaled - index)
        return when (index) {
            0 -> k0 + (k1 - k0) * local
            1 -> k1 + (k2 - k1) * local
            2 -> k2 + (k3 - k2) * local
            else -> k3 + (k4 - k3) * local
        }
    }

    // ── 状态容器 ─────────────────────────────────────────────────────────────

    private class PartitaState {
        var line: LyricLine? = null
        var profile: GlowWordProfile? = null
        var chunks: Array<PartitaChunkState> = emptyArray()
        var intensity = "normal"
        var fontSizePx = 0f
        var ascent = 0f
        var descent = 0f
        var baseColor = 0
        var activeColor = 0
        var totalGraphemes = 0
        var innerWidth = 0f
        var innerHeight = 0f
        var columnWidth = 0f
        var columnHeight = 0f
        var floatDistance = FLOAT_DISTANCE_NORMAL
        var floatDuration = FLOAT_DURATION_NORMAL
        var groupStart = 0.0
        var exitStart = -1.0
    }

    private class PartitaChunkState {
        var chunk: PartitaLayout.PartitaChunk? = null
        var words: Array<PartitaWordState> = emptyArray()
        var isLeftGuide = true
        var marginBottomPx = 0f
        var width = 0f
        var height = 0f
        var contentWidth = 0f

        var status = GlowWordStatus.WAITING
        var opacity = 0f
        var scale = CHUNK_WAITING_SCALE
        var x = 0f
        var y = 0f
        var rotate = 0f
        var baseX = 0f
        var baseY = 0f
        var baseRotate = 0f

        var fromOpacity = 0f
        var fromScale = CHUNK_WAITING_SCALE
        var fromX = 0f
        var fromY = 0f
        var fromRotate = 0f
        var toOpacity = 0f
        var toScale = CHUNK_WAITING_SCALE
        var toX = 0f
        var toY = 0f
        var toRotate = 0f
        var transitionStart = 0.0
        var transitionSpring = false
        var transitionDuration = CHUNK_TRANSITION_DURATION

        var opacityFrom = 0f
        var opacityTo = 0f
        var opacityStart = 0.0
        var opacityDuration = CHUNK_TRANSITION_DURATION

        var guideScale = 0f
        var guideScaleFrom = 0f
        var guideScaleTo = 0f
        var guideScaleStart = 0.0
        var guideAlpha = 0f
        var guideAlphaFrom = 0f
        var guideAlphaTo = 0f
        var guideAlphaStart = 0.0

        /** 初始状态 = framer 的 `initial="waiting"`（PartitaChunk.tsx:76-83） */
        fun initWaiting() {
            status = GlowWordStatus.WAITING
            opacity = 0f
            scale = CHUNK_WAITING_SCALE
            x = baseX + (if (isLeftGuide) -CHUNK_WAITING_OFFSET else CHUNK_WAITING_OFFSET)
            y = baseY
            rotate = baseRotate
            fromOpacity = opacity
            fromScale = scale
            fromX = x
            fromY = y
            fromRotate = rotate
            toOpacity = opacity
            toScale = scale
            toX = x
            toY = y
            toRotate = rotate
            guideScale = 0f
            guideScaleFrom = 0f
            guideScaleTo = 0f
            guideAlpha = 0f
            guideAlphaFrom = 0f
            guideAlphaTo = 0f
        }

        /** PartitaChunk.tsx:76-93 — 状态切换时定格当前值并选定过渡 */
        fun beginTransition(newStatus: GlowWordStatus, nowSec: Double) {
            fromOpacity = opacity
            fromScale = scale
            fromX = x
            fromY = y
            fromRotate = rotate
            opacityFrom = opacity

            when (newStatus) {
                GlowWordStatus.ACTIVE -> {
                    toOpacity = 1f
                    toScale = 1f
                    toX = baseX
                    toY = baseY
                    toRotate = baseRotate
                    transitionSpring = true
                    transitionDuration = CHUNK_TRANSITION_DURATION
                    opacityTo = 1f
                    opacityDuration = CHUNK_OPACITY_DURATION
                }
                GlowWordStatus.PASSED -> {
                    toOpacity = 1f
                    toScale = 1f
                    toX = baseX
                    toY = baseY
                    toRotate = baseRotate
                    transitionSpring = false
                    transitionDuration = CHUNK_TRANSITION_DURATION
                    opacityTo = 1f
                    opacityDuration = CHUNK_TRANSITION_DURATION
                }
                else -> {
                    toOpacity = 0f
                    toScale = CHUNK_WAITING_SCALE
                    toX = baseX + (if (isLeftGuide) -CHUNK_WAITING_OFFSET else CHUNK_WAITING_OFFSET)
                    toY = baseY
                    toRotate = baseRotate
                    transitionSpring = false
                    transitionDuration = CHUNK_TRANSITION_DURATION
                    opacityTo = 0f
                    opacityDuration = CHUNK_TRANSITION_DURATION
                }
            }
            transitionStart = nowSec
            opacityStart = nowSec

            // 引导线：waiting ⇒ scale 0 / opacity 0，否则 ⇒ 1（PartitaChunk.tsx:107-113 / :127-133）
            guideScaleFrom = guideScale
            guideScaleTo = if (newStatus == GlowWordStatus.WAITING) 0f else 1f
            guideScaleStart = nowSec
            guideAlphaFrom = guideAlpha
            guideAlphaTo = if (newStatus == GlowWordStatus.WAITING) 0f else 1f
            guideAlphaStart = nowSec
        }

        /** 每帧推进所有过渡 */
        fun advance(nowSec: Double) {
            val t = nowSec - transitionStart
            val raw = PartitaUtil.clamp01((t / transitionDuration).toFloat())
            // active ⇒ spring（PartitaChunk.tsx:86-89）；其余 ⇒ duration 0.4 + easeOut（:90-92）
            val progress = if (transitionSpring) {
                PartitaSpring.progress(t)
            } else {
                PartitaCssEasing.easeOut(raw)
            }
            opacity = fromOpacity + (toOpacity - fromOpacity) * progress
            scale = fromScale + (toScale - fromScale) * progress
            x = fromX + (toX - fromX) * progress
            y = fromY + (toY - fromY) * progress
            rotate = fromRotate + (toRotate - fromRotate) * progress

            // spring 态下 opacity 单独走 0.1s 的 tween（PartitaChunk.tsx:89）
            if (transitionSpring) {
                val opacityProgress = PartitaCssEasing.easeOut(
                    PartitaUtil.clamp01(((nowSec - opacityStart) / opacityDuration).toFloat()),
                )
                opacity = opacityFrom + (opacityTo - opacityFrom) * opacityProgress
            }

            val guideProgress = PartitaCssEasing.easeOut(
                PartitaUtil.clamp01(((nowSec - guideScaleStart) / GUIDE_TRANSITION_DURATION).toFloat()),
            )
            guideScale = guideScaleFrom + (guideScaleTo - guideScaleFrom) * guideProgress
            val guideAlphaProgress = PartitaCssEasing.easeOut(
                PartitaUtil.clamp01(((nowSec - guideAlphaStart) / GUIDE_TRANSITION_DURATION).toFloat()),
            )
            guideAlpha = guideAlphaFrom + (guideAlphaTo - guideAlphaFrom) * guideAlphaProgress
        }
    }

    private class PartitaWordState {
        var word: LyricWord? = null
        var config: PartitaLayout.WordLayoutConfig? = null
        var text = ""
        var width = 0f
        var relX = 0f

        // ── 逐字形辉光层（移植 GlowWord.tsx:86-119 + wordGlow.ts）──
        /** 该词的字形文本（逐字拆开） */
        var graphemeTexts: Array<String> = emptyArray()
        /** 每个字形相对词左边缘的 x 偏移（度量在 buildState 里取好 ⇒ 逐帧零分配） */
        var graphemeOffsets: FloatArray = FloatArray(0)
        /** 每个字形的时序（移植 graphemeTiming.ts） */
        var graphemeTimings: List<GraphemeTiming> = emptyList()
        /** 进入 active 的时刻（秒）—— 辉光曲线的时间锚点 */
        var activeStart: Double = 0.0
        /** 进入 passed 的时刻（秒）—— 离场淡出的起点 */
        var passedAt: Double = 0.0

        var status = GlowWordStatus.WAITING
        var opacity = 0f
        var scale = WORD_WAITING_SCALE
        var x = 0f
        var y = 0f
        var rotate = 0f

        var fromOpacity = 0f
        var fromScale = WORD_WAITING_SCALE
        var fromX = 0f
        var fromY = 0f
        var fromRotate = 0f
        var toOpacity = 0f
        var toScale = WORD_WAITING_SCALE
        var toX = 0f
        var toY = 0f
        var toRotate = 0f
        var transitionStart = 0.0
        var transitionSpring = false
        var transitionDuration = WORD_WAITING_TRANSITION

        var rotateFrom = 0f
        var rotateTo = 0f
        var rotateStart = 0.0
        var rotateSpring = false
        var rotateLinear = false
        var rotateDuration = WORD_WAITING_TRANSITION

        var opacityFrom = 0f
        var opacityTo = 0f
        var opacityStart = 0.0
        var opacityDuration = 0.1

        var bodyColor = 0
        var bodyColorFrom = 0
        var bodyColorTo = 0
        var bodyColorStart = 0.0
        var bodyColorDuration = 0.2
        /** 0 = 默认（easeOut）/ 1 = linear / 2 = easeInOut */
        var bodyColorEase = 0
        var bodyBlur = 0f
        var bodyBlurFrom = 0f
        var bodyBlurTo = 0f
        var bodyBlurStart = 0.0
        var bodyBlurDuration = 0.2

        /** 初始状态 = framer 的 `initial="waiting"`（glowWordVariants.ts:16-23 / :56-59） */
        fun initWaiting(baseColor: Int) {
            status = GlowWordStatus.WAITING
            val cfg = config
            val baseX = cfg?.x ?: 0f
            val baseY = cfg?.y ?: 0f
            opacity = 0f
            scale = WORD_WAITING_SCALE
            x = baseX + sin(baseY) * WORD_WAITING_DRIFT_X
            y = baseY + cos(baseX) * WORD_WAITING_DRIFT_Y
            rotate = (cfg?.rotate ?: 0f) + WORD_WAITING_ROTATE_EXTRA
            fromOpacity = opacity
            fromScale = scale
            fromX = x
            fromY = y
            fromRotate = rotate
            toOpacity = opacity
            toScale = scale
            toX = x
            toY = y
            toRotate = rotate
            rotateFrom = rotate
            rotateTo = rotate
            bodyColor = baseColor
            bodyColorFrom = baseColor
            bodyColorTo = baseColor
            bodyBlur = WORD_BODY_WAITING_BLUR
            bodyBlurFrom = WORD_BODY_WAITING_BLUR
            bodyBlurTo = WORD_BODY_WAITING_BLUR
        }

        /** glowWordVariants.ts:12-51 — 词容器变体 + :55-83 正文变体 */
        fun beginTransition(
            newStatus: GlowWordStatus,
            nowSec: Double,
            profile: GlowWordProfile,
            intensity: String,
            baseColor: Int,
            activeColor: Int,
        ) {
            fromOpacity = opacity
            fromScale = scale
            fromX = x
            fromY = y
            fromRotate = rotate
            rotateFrom = rotate
            opacityFrom = opacity

            val cfg = config
            when (newStatus) {
                GlowWordStatus.ACTIVE -> {
                    toOpacity = 1f
                    toScale = (cfg?.scale ?: 1f) * WORD_ACTIVE_SCALE
                    toX = cfg?.x ?: 0f
                    toY = cfg?.y ?: 0f
                    toRotate = cfg?.rotate ?: 0f
                    transitionSpring = true
                    transitionDuration = WORD_WAITING_TRANSITION
                    rotateSpring = true
                    rotateLinear = false
                    rotateDuration = WORD_WAITING_TRANSITION
                    opacityTo = 1f
                    opacityDuration = WORD_BODY_OPACITY_DURATION
                }
                GlowWordStatus.PASSED -> {
                    toOpacity = if (intensity == "chaotic") {
                        WORD_PASSED_ALPHA_CHAOTIC
                    } else {
                        WORD_PASSED_ALPHA_NORMAL
                    }
                    toScale = cfg?.scale ?: 1f
                    toX = cfg?.x ?: 0f
                    toY = cfg?.y ?: 0f
                    toRotate = (cfg?.rotate ?: 0f) + (cfg?.passedRotate ?: 0f)
                    transitionSpring = false
                    transitionDuration = WORD_PASSED_TRANSITION
                    rotateSpring = false
                    rotateLinear = true
                    rotateDuration = WORD_PASSED_ROTATE_DURATION
                    opacityTo = toOpacity
                    opacityDuration = WORD_PASSED_TRANSITION
                }
                else -> {
                    toOpacity = 0f
                    toScale = WORD_WAITING_SCALE
                    val baseX = cfg?.x ?: 0f
                    val baseY = cfg?.y ?: 0f
                    toX = baseX + sin(baseY) * WORD_WAITING_DRIFT_X
                    toY = baseY + cos(baseX) * WORD_WAITING_DRIFT_Y
                    toRotate = (cfg?.rotate ?: 0f) + WORD_WAITING_ROTATE_EXTRA
                    transitionSpring = false
                    transitionDuration = WORD_WAITING_TRANSITION
                    rotateSpring = false
                    rotateLinear = false
                    rotateDuration = WORD_WAITING_TRANSITION
                    opacityTo = 0f
                    opacityDuration = WORD_WAITING_TRANSITION
                }
            }
            transitionStart = nowSec
            rotateStart = nowSec
            opacityStart = nowSec

            // ── 正文变体（glowWordVariants.ts:55-83）──
            val duration = word?.let { GlowWordCore.displayDuration(it, profile) } ?: 0.0
            bodyColorFrom = bodyColor
            bodyBlurFrom = bodyBlur
            when (newStatus) {
                GlowWordStatus.ACTIVE -> {
                    bodyColorTo = activeColor
                    bodyColorEase = 1
                    bodyColorDuration = if (duration > 0.0) duration else WORD_BODY_COLOR_DURATION_DEFAULT
                    bodyBlurTo = 0f
                    bodyBlurDuration = when (profile.wordRevealMode) {
                        "instant" -> WORD_BODY_BLUR_INSTANT
                        "fast" -> WORD_BODY_BLUR_FAST
                        else -> WORD_BODY_BLUR_NORMAL
                    }
                }
                GlowWordStatus.PASSED -> {
                    bodyColorTo = baseColor
                    bodyColorEase = 2
                    bodyColorDuration = when (profile.wordRevealMode) {
                        "instant" -> WORD_BODY_PASSED_COLOR_INSTANT
                        "fast" -> WORD_BODY_PASSED_COLOR_FAST
                        else -> WORD_BODY_PASSED_COLOR_NORMAL
                    }
                    bodyBlurTo = 0f
                    bodyBlurDuration = when (profile.wordRevealMode) {
                        "instant" -> WORD_BODY_PASSED_BLUR_INSTANT
                        "fast" -> WORD_BODY_PASSED_BLUR_FAST
                        else -> WORD_BODY_PASSED_BLUR_NORMAL
                    }
                }
                else -> {
                    bodyColorTo = baseColor
                    bodyColorEase = 0
                    bodyColorDuration = WORD_BODY_COLOR_DURATION_DEFAULT
                    bodyBlurTo = WORD_BODY_WAITING_BLUR
                    bodyBlurDuration = WORD_BODY_COLOR_DURATION_DEFAULT
                }
            }
            bodyColorStart = nowSec
            bodyBlurStart = nowSec
        }

        /** 每帧推进所有过渡 */
        fun advance(nowSec: Double) {
            val t = nowSec - transitionStart
            val raw = PartitaUtil.clamp01((t / transitionDuration).toFloat())
            // active ⇒ spring（glowWordVariants.ts:26-35）；其余 ⇒ duration + 默认缓动
            val progress = if (transitionSpring) {
                PartitaSpring.progress(t)
            } else {
                PartitaCssEasing.easeOut(raw)
            }
            opacity = fromOpacity + (toOpacity - fromOpacity) * progress
            scale = fromScale + (toScale - fromScale) * progress
            x = fromX + (toX - fromX) * progress
            y = fromY + (toY - fromY) * progress

            // spring 态下 opacity 单独走 0.1s 的 tween（glowWordVariants.ts:34）
            if (transitionSpring) {
                val opacityProgress = PartitaCssEasing.easeOut(
                    PartitaUtil.clamp01(((nowSec - opacityStart) / opacityDuration).toFloat()),
                )
                opacity = opacityFrom + (opacityTo - opacityFrom) * opacityProgress
            }

            // 旋转：active ⇒ spring；passed ⇒ 5s linear（glowWordVariants.ts:47-49）；waiting ⇒ 默认缓动
            val rotT = nowSec - rotateStart
            val rotProgress = if (rotateSpring) {
                PartitaSpring.progress(rotT)
            } else if (rotateLinear) {
                PartitaUtil.clamp01((rotT / rotateDuration).toFloat())
            } else {
                PartitaCssEasing.easeOut(PartitaUtil.clamp01((rotT / rotateDuration).toFloat()))
            }
            rotate = rotateFrom + (rotateTo - rotateFrom) * rotProgress

            // 正文颜色（glowWordVariants.ts:65 / :76）
            val colorRaw = PartitaUtil.clamp01(((nowSec - bodyColorStart) / bodyColorDuration).toFloat())
            val colorProgress = when (bodyColorEase) {
                1 -> colorRaw
                2 -> PartitaCssEasing.easeInOut(colorRaw)
                else -> PartitaCssEasing.easeOut(colorRaw)
            }
            bodyColor = PartitaUtil.lerpColor(bodyColorFrom, bodyColorTo, colorProgress)

            // 正文模糊（glowWordVariants.ts:66 / :77）
            val blurRaw = PartitaUtil.clamp01(((nowSec - bodyBlurStart) / bodyBlurDuration).toFloat())
            bodyBlur = bodyBlurFrom + (bodyBlurTo - bodyBlurFrom) * PartitaCssEasing.easeOut(blurRaw)
        }
    }
}

/**
 * 无状态小工具（放在顶层，避免嵌套类访问外部类的私有成员）。
 */
private object PartitaUtil {

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** 逐通道插值 ARGB（framer-motion 对颜色字符串也是逐通道插值） */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val fr = (from ushr 16) and 0xFF
        val fg = (from ushr 8) and 0xFF
        val fb = from and 0xFF
        val fa = (from ushr 24) and 0xFF
        val tr = (to ushr 16) and 0xFF
        val tg = (to ushr 8) and 0xFF
        val tb = to and 0xFF
        val ta = (to ushr 24) and 0xFF
        val r = (fr + (tr - fr) * t).toInt().coerceIn(0, 255)
        val g = (fg + (tg - fg) * t).toInt().coerceIn(0, 255)
        val b = (fb + (tb - fb) * t).toInt().coerceIn(0, 255)
        val a = (fa + (ta - fa) * t).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/**
 * framer-motion 的 `spring(stiffness 200, damping 20, mass 1)` 的解析解。
 *
 * ω = sqrt(k/m) = 14.1421；ζ = c / (2*sqrt(k*m)) = 0.70711；ω_d = 10；ζω = 10
 * x(t) = 1 - e^(-ζωt) * ( cos(ω_d t) + (ζω/ω_d) sin(ω_d t) )
 */
private object PartitaSpring {
    fun progress(t: Double): Float {
        if (t <= 0.0) return 0f
        val x = 10.0 * t
        return (1.0 - Math.exp(-x) * (Math.cos(x) + Math.sin(x))).toFloat()
    }
}

/**
 * CSS `cubic-bezier()` 求值（无分配）。
 *
 *  - `ease-out` = (0, 0, 0.58, 1) —— framer-motion 的默认 tween 缓动（推定）
 *  - `ease-in-out` = (0.42, 0, 0.58, 1)
 */
private object PartitaCssEasing {

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

    private fun axis(a: Float, b: Float, u: Float): Float {
        val iu = 1f - u
        return 3f * iu * iu * u * a + 3f * iu * u * u * b + u * u * u
    }
}
