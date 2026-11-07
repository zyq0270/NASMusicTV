// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCanvasText.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeBlockFrame.FumeBlockTiming
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * fume 的 Canvas 文字绘制：字体、静态块快照、逐字打印的活块。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCanvasText.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物（逐条记录）
 *  - `CanvasRenderingContext2D` ⇒ `android.graphics.Canvas`（通过 Compose 的 `drawIntoCanvas`）。
 *  - `context.font = "640 24px Inter, …"` ⇒ `Paint.textSize` + `Paint.typeface`
 *    （见 [FumeTextDrawer]，字体栈在 Android 上没有等价物 ⇒ 用系统 sans-serif）。
 *  - `context.textBaseline = 'middle'` ⇒ Android 只有基线对齐 ⇒
 *    用 `Paint.getFontMetrics` 把「中间基线」换算成「字母基线」：
 *    `baseline = y - (ascent + descent) / 2`（CSS `middle` 的定义是 em 盒的中线）。
 *  - `context.shadowBlur` / `context.shadowColor` ⇒ `Paint.setShadowLayer(radius, 0, 0, color)`。
 *    ⚠️ 两者都是「模糊半径」的近似等价（CSS 的 shadowBlur ≈ 2σ，Android 的 radius ≈ 1σ），
 *    视觉上辉光略紧一点，其余（颜色、强度曲线）完全照抄。
 *  - `context.filter = 'drop-shadow(...)'`（Linux 专用的量化分支）⇒ Android 无 CSS filter；
 *    辉光一律走 `setShadowLayer`（本文件已逐行照抄其半径与颜色公式）。
 *  - `context.fillRect` ⇒ `Canvas.drawRect(l, t, r, b, paint)`。
 *  - `buildTextStyleKey(...)`（用来判断「同一批字形是否同色」）⇒ Android 改成
 *    **直接比较三个字段**（填充色 / 辉光半径 / 辉光色）⇒ 逐帧零字符串分配。
 *
 * ## 配色：按**语义**对应 Folia 的主题色
 *  本文件**不读 `ctx.palette`**（所有颜色都是调用方以 ARGB `Int` 传进来的）⇒
 *  这里只约定各颜色槽的**角色**，具体取值由调用方按下面的对应关系填：
 *  - `params.primaryColor` = 基础文字色（Folia 的 `theme.primaryColor`，近白）
 *    ⇒ 调用方填 `LyricsStageColors.baseText(ctx)`；用于逐字形的正文底色。
 *  - `params.accentColor` = 高亮色（Folia 的 `theme.accentColor`，鲜艳蓝）
 *    ⇒ 调用方填 `LyricsStageColors.accent(ctx)`；用于活动行的行辉光。
 *  - `fillStyle`（静态快照的填充色）= 基础文字色（同上）。
 *  - `block.activeColors`（逐词激活色）= 高亮色的**回落**色
 *    （Folia：`resolveWordColor(word.text, theme.wordColors, theme.accentColor)`）
 *    ⇒ 本仓没有逐词配色表 ⇒ 恒为 `LyricsStageColors.accent(ctx)`。
 *
 *  ⚠️ 页面底色（`ctx.palette.background`）**不在**本文件的职责内 ——
 *     它由 [com.nasmusic.tv.visualizer.renderers.lyricsStage.FullViewSubRenderer] 填整块底色。
 */
object FumeCanvasText {

    /** 移植 fumeCanvasText.ts:29 `roundShadowBlur` —— 辉光半径取整（整像素） */
    fun roundShadowBlur(px: Float): Float {
        if (!px.isFinite()) return 0f
        return max(0f, kotlin.math.round(px))
    }

    /**
     * 文字绘制器：持有一支可复用的 `Paint`（逐帧零分配）与字体状态。
     *
     * Folia 的 `buildCanvasFont(block, theme)` 返回 CSS 字体串；Android 端改成
     * **就地配置一支 Paint**（见 [applyFont]）。
     */
    class FumeTextDrawer {
        /** 复用的画笔（文字 + 辉光） */
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

        /** 复用的字体度量缓冲（避免 `getFontMetrics()` 每次分配） */
        private val fontMetrics = Paint.FontMetrics()

        /** 当前辉光半径（缓存用，避免每帧重复调 `setShadowLayer`） */
        private var shadowBlur = -1f

        /** 当前辉光色（缓存用） */
        private var shadowColor = 0

        /** 当前字号（缓存用） */
        private var textSize = -1f

        /** 当前字重（缓存用） */
        private var fontWeight = -1

        /**
         * 「中间基线」相对「字母基线」的偏移（= `-(ascent + descent) / 2`）。
         *
         * Folia 用 `context.textBaseline = 'middle'`；Android 的 `Paint` 只有基线对齐。
         */
        var middleBaselineOffset: Float = 0f
            private set

        /**
         * 移植 fumeCanvasText.ts:15-17 `buildCanvasFont`
         *
         * Android 等价：把块���的字号与字重写进这支 Paint。
         */
        fun applyFont(block: FumeTypes.FumeBlock) {
            if (textSize != block.fontPx) {
                paint.textSize = block.fontPx
                textSize = block.fontPx
                paint.getFontMetrics(fontMetrics)
                middleBaselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f
            }
            if (fontWeight != block.fontWeight) {
                paint.typeface = typefaceFor(block.fontWeight)
                fontWeight = block.fontWeight
            }
        }

        /** 设置辉光（Folia 的 `setCanvasTextGlow`） */
        fun setGlow(blur: Float, color: Int) {
            if (blur <= 0f) {
                if (shadowBlur != 0f) {
                    paint.clearShadowLayer()
                    shadowBlur = 0f
                    shadowColor = 0
                }
                return
            }
            if (blur != shadowBlur || color != shadowColor) {
                paint.setShadowLayer(blur, 0f, 0f, color)
                shadowBlur = blur
                shadowColor = color
            }
        }

        /** 清除辉光（Folia 的 `clearCanvasTextGlow`） */
        fun clearGlow() {
            setGlow(0f, 0)
        }
    }

    /**
     * 字重 ⇒ `Typeface`。
     *
     * Android 的 `Typeface`（API 28 以下）只有 normal / bold 两档；
     * Folia 的 body 640 与 hero 780 都落在 bold。
     */
    private fun typefaceFor(weight: Int): Typeface =
        if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    /**
     * 移植 fumeCanvasText.ts:66-109 `createStaticBlockSnapshot`
     *
     * ⚠️ Folia 返回 `{ canvas, padding }`；**Android 返回 `StaticSnapshot(bitmap, padding)`**。
     *
     * @param fillStyle 文字填充色（ARGB，含 alpha）—— 角色是**基础文字色**
     *                   （= Folia 的 `theme.primaryColor`）
     * @param shadowBlur 辉光半径（0 = 无辉光）
     * @param shadowColor 辉光色（ARGB）
     * @param rasterScale 快照的光栅密度（每个世界单位几个像素）
     */
    fun createStaticBlockSnapshot(
        block: FumeTypes.FumeBlock,
        fillStyle: Int,
        shadowBlur: Float,
        shadowColor: Int,
        rasterScale: Float,
    ): FumeTypes.StaticSnapshot? {
        val padding = ceil(
            max(
                max(block.fontPx * 0.32f, shadowBlur + block.fontPx * 0.08f),
                4f,
            ),
        )

        val pixelWidth = max(1, ceil((block.width + padding * 2f) * rasterScale).toInt())
        val pixelHeight = max(1, ceil((block.height + padding * 2f) * rasterScale).toInt())

        val bitmap = try {
            Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            return null
        }
        bitmap.eraseColor(0)

        val canvas = Canvas(bitmap)
        canvas.scale(rasterScale, rasterScale)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.textSize = block.fontPx
        paint.typeface = typefaceFor(block.fontWeight)
        paint.color = fillStyle
        paint.textAlign = Paint.Align.LEFT
        if (shadowBlur > 0f) {
            paint.setShadowLayer(shadowBlur, 0f, 0f, shadowColor)
        }

        val fontMetrics = Paint.FontMetrics()
        paint.getFontMetrics(fontMetrics)
        val middleBaselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f

        // 移植 fumeCanvasText.ts:98-104 —— 逐渲染行画整行文本
        // ⚠️ CJK 判定已在版面期算好（`block.isCjkLine`）⇒ 快照路径也不编译正则。
        val baselineOffset = block.lineHeight * (if (block.isCjkLine) 0.52f else 0.5f)
        for (renderLine in block.renderLines) {
            val baseline = renderLine.top + baselineOffset + padding + middleBaselineOffset
            canvas.drawText(renderLine.text, renderLine.left + padding, baseline, paint)
        }

        if (shadowBlur > 0f) {
            paint.clearShadowLayer()
        }

        return FumeTypes.StaticSnapshot(bitmap, padding)
    }

    /**
     * 移植 fumeCanvasText.ts:31-64 `drawRenderTextRun`
     *
     * 把「一段里连续同色的那批字形」画出来：Folia 用整段文本 + 横向裁剪实现，
     * Android 等价：`Canvas.clipRect` + `drawText`。
     *
     * @param runStart 本批字形在渲染行里的起点（行内坐标）
     * @param runEnd 本批字形的终点（行内坐标，不含）
     */
    fun drawRenderTextRun(
        canvas: Canvas,
        drawer: FumeTextDrawer,
        renderLine: FumeTypes.RenderLineSlice,
        segment: FumeTypes.RenderSegmentSlice,
        runStart: Int,
        runEnd: Int,
        baseX: Float,
        baseY: Float,
    ) {
        if (segment.text.isEmpty() || runEnd <= runStart) return

        val segmentRunStart = max(runStart - segment.localStart, 0)
        val segmentRunEnd = min(runEnd - segment.localStart, segment.measuredGlyphOffsets.size - 1)
        val clipLeft = segment.measuredGlyphOffsets.getOrElse(segmentRunStart) {
            FumeTextMeasure.resolveRenderLineOffset(renderLine, runStart) - segment.x
        }
        val clipRight = segment.measuredGlyphOffsets.getOrElse(segmentRunEnd) {
            FumeTextMeasure.resolveRenderLineOffset(renderLine, runEnd) - segment.x
        }
        val clipWidth = max(clipRight - clipLeft, 0f)
        if (clipWidth <= 0f) return

        // 移植 fumeCanvasText.ts:53-63 —— 裁剪到本批字形的横向范围
        val clipLeftX = baseX + segment.x + clipLeft
        val clipTopY = baseY - max(clipWidth, 1f) - 64f
        val clipHeight = max(128f + clipWidth * 2f, 256f)
        canvas.save()
        canvas.clipRect(clipLeftX, clipTopY, clipLeftX + clipWidth, clipTopY + clipHeight)
        canvas.drawText(segment.text, baseX + segment.x, baseY + drawer.middleBaselineOffset, drawer.paint)
        canvas.restore()
    }

    /**
     * 移植 fumeCanvasText.ts:124-413 `drawFumeLiveBlock`
     *
     * 逐字形打印的那一块：行辉光、逐字形的颜色与辉光、打印印章。
     *
     * @param canvas 已处于世界坐标系下的画布（调用方已套好相机变换）
     * @param params 活块参数 —— 颜色槽的角色：
     *                 `primaryColor` = 基础文字色、`accentColor` = 高亮色
     *                 （取值由调用方按 `LyricsStageColors` 的语义映射填入）
     */
    fun drawFumeLiveBlock(
        canvas: Canvas,
        drawer: FumeTextDrawer,
        block: FumeTypes.FumeBlock,
        timing: FumeBlockTiming,
        params: FumeTypes.FumeLiveBlockParams,
    ) {
        val waitingOpacity = timing.waitingOpacity
        val activeOpacity = timing.activeOpacity
        val transitionPassedStyle = timing.transitionPassedStyle
        val baselineOffset = timing.baselineOffset
        val linePassCutoffTime = timing.linePassCutoffTime
        val hasRevealCompleted = timing.hasRevealCompleted
        val hasPassCutoffReached = timing.hasPassCutoffReached
        val lineDuration = timing.lineDuration
        val colorTrailDuration = timing.colorTrailDuration
        val time = params.time

        val printedCount = FumeReveal.resolvePrintedGraphemeCount(
            block.line,
            block.wordRanges,
            block.graphemes.size,
            time,
        )
        val totalGraphemeCount = block.graphemes.size
        val isHero = block.variant == FumeTypes.VARIANT_HERO

        canvas.save()
        drawer.applyFont(block)

        // ── 行辉光（Folia fumeCanvasText.ts:147-188）─────────────────────────
        val isLineActive = time >= block.line.startTime && time <= linePassCutoffTime
        if (isLineActive) {
            val lineProgress = FumeReveal.resolveVisualProgressWithCutoff(
                block.line.startTime,
                lineDuration,
                time,
                linePassCutoffTime,
            )
            val lineGlowEnvelope = FumeMath.resolveDelayedGlowEnvelope(lineProgress, 0.8f)
            val lineGlowAlpha = (
                (if (isHero) 0.16f else 0.12f) +
                    lineGlowEnvelope * (if (isHero) 0.26f else 0.2f)
                ) * params.glowIntensity
            val lineGlowBlur = (
                (if (isHero) 12f else 8f) +
                    lineGlowEnvelope * (block.fontPx * (if (isHero) 0.7f else 0.52f))
                ) * params.glowIntensity
            val lineGlowColor = FumeTextStyle.colorWithAlpha(params.accentColor, lineGlowAlpha)

            canvas.save()
            drawer.paint.color = lineGlowColor
            drawer.setGlow(
                roundShadowBlur(lineGlowBlur),
                FumeTextStyle.colorWithAlpha(params.accentColor, lineGlowAlpha * 1.35f),
            )

            for (renderLine in block.renderLines) {
                val glowBaseX = block.x + renderLine.left
                val glowBaseY = block.y + renderLine.top + baselineOffset

                for (segment in renderLine.segments) {
                    if (segment.text.isBlank()) continue
                    canvas.drawText(
                        segment.text,
                        glowBaseX + segment.x,
                        glowBaseY + drawer.middleBaselineOffset,
                        drawer.paint,
                    )
                }
            }

            canvas.restore()
        }

        // ── 逐字形（Folia fumeCanvasText.ts:190-410）────────────────────────
        for (renderLine in block.renderLines) {
            val baseX = block.x + renderLine.left
            val baseY = block.y + renderLine.top + baselineOffset

            for (segment in renderLine.segments) {
                // 当前这一批同色字形的状态（对应 Folia 的 runStart / runFillStyle / runShadow*）
                var runStart = -1
                var runFillColor = 0
                var runShadowBlur = 0f
                var runShadowColor = 0

                for (globalOffset in segment.start until segment.end) {
                    val graphemeIndex = globalOffset - renderLine.start
                    val grapheme = renderLine.graphemes.getOrElse(graphemeIndex) { "" }
                    val rangeIndex = block.wordRangeIndexByOffset.getOrElse(globalOffset) { -1 }
                    val range = if (rangeIndex >= 0) block.wordRanges[rangeIndex] else null
                    val colorRangeIndex = block.colorRangeIndexByOffset.getOrElse(globalOffset) { -1 }
                    val colorRange = if (colorRangeIndex >= 0) block.wordRanges[colorRangeIndex] else range
                    val isPrinted = hasRevealCompleted || globalOffset < printedCount
                    val isFrontier = printedCount > 0 &&
                        globalOffset == printedCount &&
                        printedCount < totalGraphemeCount &&
                        !hasRevealCompleted &&
                        !hasPassCutoffReached

                    var alpha = if (isPrinted) activeOpacity else if (isFrontier) 0.82f else waitingOpacity
                    var shadowBlur = 0f
                    var shadowColor = 0
                    var fillColor = FumeTextStyle.colorWithAlpha(params.primaryColor, alpha)

                    if (range != null) {
                        val wordDuration = max(range.word.endTime - range.word.startTime, 0.08)
                        val wordProgress = FumeMath.clamp(
                            ((time - range.word.startTime) / wordDuration).toFloat(),
                            0f,
                            1f,
                        )
                        val glyphCount = max(range.end - range.start, 1)
                        val glyphIndexInRange = globalOffset - range.start
                        val glyphTiming = if (!range.word.syllables.isNullOrEmpty()) {
                            range.graphemeTimings[
                                min(glyphIndexInRange, max(range.graphemeTimings.size - 1, 0))
                            ]
                        } else {
                            null
                        }
                        val glyphStartTime = glyphTiming?.startTime
                            ?: (range.word.startTime + (glyphIndexInRange.toDouble() / glyphCount) * wordDuration)
                        val glyphEndTime = glyphTiming?.endTime
                            ?: (
                                range.word.startTime +
                                    ((glyphIndexInRange + 1).toDouble() / glyphCount) * wordDuration
                                )
                        val glyphDuration = max(glyphEndTime - glyphStartTime, 0.001)
                        val glyphProgress = if (glyphTiming != null) {
                            FumeMath.clamp(
                                ((time - glyphStartTime) / glyphDuration + 0.16).toFloat(),
                                0f,
                                1f,
                            )
                        } else {
                            FumeMath.clamp(
                                wordProgress * glyphCount - glyphIndexInRange + 0.16f,
                                0f,
                                1f,
                            )
                        }
                        val easedGlyphProgress = FumeMath.easeOutCubic(glyphProgress)
                        val activeColor = block.activeColors[
                            if (colorRangeIndex >= 0) colorRangeIndex else rangeIndex
                        ]
                        val glyphTrailStart = glyphStartTime + glyphDuration * 0.18
                        val colorTrailPhase = FumeReveal.resolveVisualProgressWithCutoff(
                            glyphTrailStart,
                            colorTrailDuration.toDouble(),
                            time,
                            linePassCutoffTime,
                        )
                        val colorTrailProgress = colorTrailPhase.toDouble().pow(1.35).toFloat()

                        if (hasPassCutoffReached) {
                            alpha = FumeMath.mix(activeOpacity, transitionPassedStyle.opacity, colorTrailProgress)
                            fillColor = FumeTextStyle.mixColors(
                                activeColor,
                                params.primaryColor,
                                0.18f + colorTrailProgress * 0.82f,
                                alpha,
                            )
                            shadowBlur = (2f + block.fontPx * 0.1f) *
                                (1f - colorTrailProgress * 0.35f) *
                                params.passedGlowBase *
                                transitionPassedStyle.glowMultiplier
                            shadowColor = FumeTextStyle.colorWithAlpha(
                                FumeTextStyle.mixColors(
                                    activeColor,
                                    params.primaryColor,
                                    0.55f + colorTrailProgress * 0.45f,
                                ),
                                transitionPassedStyle.shadowAlphaBase +
                                    (1f - colorTrailProgress) * transitionPassedStyle.shadowAlphaTrail,
                            )
                        } else if (time < range.word.startTime) {
                            alpha = waitingOpacity
                            fillColor = FumeTextStyle.colorWithAlpha(params.primaryColor, alpha)
                        } else if (time <= glyphTrailStart) {
                            alpha = FumeMath.mix(waitingOpacity, activeOpacity, easedGlyphProgress)
                            fillColor = FumeTextStyle.mixColors(
                                params.primaryColor,
                                activeColor,
                                0.22f + easedGlyphProgress * 0.78f,
                                alpha,
                            )
                            shadowBlur = (4f + block.fontPx * 0.22f) *
                                easedGlyphProgress *
                                params.activeGlowBoost
                            shadowColor = FumeTextStyle.colorWithAlpha(
                                activeColor,
                                0.4f + easedGlyphProgress * 0.44f,
                            )
                        } else {
                            alpha = FumeMath.mix(activeOpacity, transitionPassedStyle.opacity, colorTrailProgress)
                            fillColor = FumeTextStyle.mixColors(
                                activeColor,
                                params.primaryColor,
                                0.18f + colorTrailProgress * 0.82f,
                                alpha,
                            )
                            shadowBlur = (2f + block.fontPx * 0.1f) *
                                (1f - colorTrailProgress * 0.35f) *
                                params.passedGlowBase *
                                transitionPassedStyle.glowMultiplier
                            shadowColor = FumeTextStyle.colorWithAlpha(
                                FumeTextStyle.mixColors(
                                    activeColor,
                                    params.primaryColor,
                                    0.55f + colorTrailProgress * 0.45f,
                                ),
                                transitionPassedStyle.shadowAlphaBase +
                                    (1f - colorTrailProgress) * transitionPassedStyle.shadowAlphaTrail,
                            )
                        }

                        // ── 打印印章（Folia fumeCanvasText.ts:305-376）──────────
                        if (params.showPrintStamp && !grapheme.isBlank()) {
                            val glyphWindowDuration = max(wordDuration / glyphCount, 0.04)
                            val activationLeadDuration = FumeMath.clamp(
                                min(glyphWindowDuration * 0.86, lineDuration * 0.16).toFloat(),
                                0.055f,
                                if (isHero) 0.2f else 0.16f,
                            )
                            val activationReleaseDuration = activationLeadDuration * 0.42
                            val activationWindowStart = glyphTrailStart - activationLeadDuration
                            val activationWindowEnd = glyphTrailStart + activationReleaseDuration
                            val glyphAdvance = FumeTextMeasure.resolveSegmentGlyphAdvance(segment, globalOffset)
                            val stampProgress = FumeReveal.resolveVisualProgressWithCutoff(
                                activationWindowStart,
                                activationWindowEnd - activationWindowStart,
                                time,
                                linePassCutoffTime,
                            )

                            if (stampProgress > 0f && stampProgress < 1f) {
                                val stampTrailPhase = FumeReveal.resolveVisualProgressWithCutoff(
                                    glyphTrailStart,
                                    max(activationWindowEnd - glyphTrailStart, 0.001),
                                    time,
                                    linePassCutoffTime,
                                )
                                val isDropping = stampTrailPhase <= 0f
                                val dropProgress = if (isDropping) {
                                    FumeMath.easeOutCubic(
                                        FumeReveal.resolveVisualProgressWithCutoff(
                                            activationWindowStart,
                                            max(glyphTrailStart - activationWindowStart, 0.001),
                                            time,
                                            linePassCutoffTime,
                                        ),
                                    )
                                } else {
                                    1f
                                }
                                val fadeProgress = if (isDropping) 0f else FumeMath.easeInOutCubic(stampTrailPhase)
                                val blockPulse = if (isDropping) {
                                    FumeMath.mix(
                                        0.18f,
                                        1f,
                                        dropProgress.toDouble().pow(0.78).toFloat(),
                                    )
                                } else {
                                    (1f - fadeProgress).toDouble().pow(1.2).toFloat()
                                }
                                val glyphVisualWidth = max(
                                    glyphAdvance * 0.88f,
                                    // 移植 fumeCanvasText.ts:349 —— 逐 grapheme 的 CJK 判定
                                    // ⚠️ 用版面期算好的 `renderLine.graphemeIsCjk`（逐帧调
                                    //    `FumeMath.isCJK` 等于每 grapheme 每帧编译一次正则）。
                                    if (
                                        graphemeIndex in renderLine.graphemeIsCjk.indices &&
                                        renderLine.graphemeIsCjk[graphemeIndex]
                                    ) {
                                        block.fontPx * 0.56f
                                    } else {
                                        block.fontPx * 0.38f
                                    },
                                )
                                val blockCenterX = baseX + segment.x +
                                    FumeTextMeasure.resolveSegmentGlyphOffset(segment, globalOffset) +
                                    glyphAdvance * 0.5f
                                val dropDistance = block.lineHeight * (if (isHero) 0.24f else 0.2f)
                                val activationBlockAlpha = blockPulse * (if (isHero) 0.82f else 0.72f)
                                val activationBlockWidth = glyphVisualWidth +
                                    block.fontPx * (if (isHero) 0.18f else 0.12f)
                                val activationBlockHeight = block.fontPx * (if (isHero) 0.72f else 0.62f)
                                val activationBlockY = baseY -
                                    block.fontPx * 0.38f -
                                    FumeMath.mix(dropDistance, 0f, dropProgress)
                                val activationBlockBlur = (8f + block.fontPx * 0.24f) *
                                    blockPulse *
                                    params.activeGlowBoost

                                if (activationBlockWidth > 0f) {
                                    val blockLeft = blockCenterX - activationBlockWidth * 0.5f
                                    canvas.save()
                                    drawer.paint.color = FumeTextStyle.colorWithAlpha(activeColor, activationBlockAlpha)
                                    drawer.setGlow(
                                        activationBlockBlur,
                                        FumeTextStyle.colorWithAlpha(activeColor, 0.56f * blockPulse),
                                    )
                                    canvas.drawRect(
                                        blockLeft,
                                        activationBlockY - activationBlockHeight * 0.5f,
                                        blockLeft + activationBlockWidth,
                                        activationBlockY + activationBlockHeight * 0.5f,
                                        drawer.paint,
                                    )
                                    canvas.restore()
                                }
                            }
                        }
                    }

                    if (alpha <= 0.002f) {
                        flushRun(
                            canvas, drawer, renderLine, segment, baseX, baseY,
                            runStart, globalOffset, runFillColor, runShadowBlur, runShadowColor,
                        )
                        runStart = -1
                        continue
                    }

                    // 移植 fumeCanvasText.ts:387 —— 辉光半径取整
                    shadowBlur = roundShadowBlur(shadowBlur)

                    if (runStart < 0) {
                        runStart = globalOffset
                        runFillColor = fillColor
                        runShadowBlur = shadowBlur
                        runShadowColor = shadowColor
                        continue
                    }

                    // 移植 fumeCanvasText.ts:398-404 —— 样式变了就断开这一批
                    if (fillColor != runFillColor ||
                        shadowBlur != runShadowBlur ||
                        shadowColor != runShadowColor
                    ) {
                        flushRun(
                            canvas, drawer, renderLine, segment, baseX, baseY,
                            runStart, globalOffset, runFillColor, runShadowBlur, runShadowColor,
                        )
                        runStart = globalOffset
                        runFillColor = fillColor
                        runShadowBlur = shadowBlur
                        runShadowColor = shadowColor
                    }
                }

                flushRun(
                    canvas, drawer, renderLine, segment, baseX, baseY,
                    runStart, segment.end, runFillColor, runShadowBlur, runShadowColor,
                )
                runStart = -1
            }
        }

        canvas.restore()
    }

    /**
     * 移植 fumeCanvasText.ts:201-229 `flushRun`
     *
     * 把「同一批同色字形」一次画掉。
     */
    private fun flushRun(
        canvas: Canvas,
        drawer: FumeTextDrawer,
        renderLine: FumeTypes.RenderLineSlice,
        segment: FumeTypes.RenderSegmentSlice,
        baseX: Float,
        baseY: Float,
        runStart: Int,
        segmentEnd: Int,
        runFillColor: Int,
        runShadowBlur: Float,
        runShadowColor: Int,
    ) {
        if (runStart < 0 || segmentEnd <= runStart) return

        val localStart = runStart - renderLine.start
        val localEnd = segmentEnd - renderLine.start

        drawer.paint.color = runFillColor
        drawer.setGlow(runShadowBlur, runShadowColor)
        drawRenderTextRun(canvas, drawer, renderLine, segment, localStart, localEnd, baseX, baseY)
        drawer.clearGlow()
    }

    /** 保留 `floor` 的引用（供未来按字素数取整的辅助函数使用） */
    @Suppress("unused")
    private fun floorToInt(value: Float): Int = floor(value).toInt()
}
