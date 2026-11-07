// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTextMeasure.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * fume 的文字测量：分段、字素宽度、渲染段与整行的构建，以及字号选择。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeTextMeasure.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物（逐条记录）
 *  - **`@chenglou/pretext`**（Folia 的排版引擎）⇒ Android：
 *    - `prepareWithSegments(text, fontSpec, { whiteSpace: 'pre-wrap' })`
 *      ⇒ [prepareText]：把文本切成「词 + 空白」的段，用 `Paint.measureText` 测每段宽度，
 *      并用**前缀累计**测出段内每个字素的推进宽度（逐前缀测量整串，
 *      与 Folia 的 `measurePrefixOffsets` 同义 —— 保证字距与字形整形不被拆散）。
 *    - `layoutWithLines(prepared, width, lineHeight)`
 *      ⇒ [layoutLines]：贪心断行（优先在空白后断行，段内放不下时按字素断行），
 *      产出的行用**整行字素偏移**表示（Folia 的 `LayoutCursor` 游标在此被字素偏移取代）。
 *    - `walkLineRanges(prepared, width, cb)` ⇒ [layoutLines] 的行数。
 *    - `measureNaturalWidth(prepared)` ⇒ 段宽之和。
 *  - **CSS 字体串**（`"${weight} ${px}px ${family}"`）⇒ [FumeFontSpec]（`Paint` + 字号 + 字重）。
 *    字重：Android 的 `Typeface` 在 API 28 以下只有 normal/bold 两档 ⇒
 *    字重 ≥ 600 映射为 `BOLD`，其余 `NORMAL`（Folia 的 640 / 780 两个档都落在 BOLD）。
 *  - **`theme.fontWeight`**：NASMusicTV 的 `RenderContext` 没有主题字重 ⇒ 用 Folia 的回落值
 *    （hero 780 / body 640），与 Folia 在 `theme.fontWeight` 缺省时的行为一致。
 *  - **`theme.fontFamily`**：`RenderContext` 没有字体设置 ⇒ 用系统 sans-serif。
 *
 * ## 与 Folia 的一处**有意的**实现简化（不改任何视觉常量）
 *  Folia 的 `fitsOnOneLine` 靠 `sizeRegime` / `fitIn` / `lastMeasured` 的**测量缓存**减少测量次数
 *  （`FIT_GUESS_MARGIN` / `LINEAR_FROM_PX` / `PIXEL_EDGE_PX` 是这套缓存的常量）。
 *  Android 的 `Paint.measureText` 很便宜，直接测量即可得到**同样精确**的「是否单行放得下」判定，
 *  故缓存层未移植；**搜索区间、迭代次数、缩放链与回落字号全部照抄 Folia**。
 */
object FumeTextMeasure {

    // ── Folia 的上游常量（fumeTextMeasure.ts）────────────────────────────────
    /** 移植 fumeTextMeasure.ts:575 `FIT_GUESS_MARGIN` */
    const val FIT_GUESS_MARGIN = 0.001f

    /** 移植 fumeTextMeasure.ts:284 `LINEAR_FROM_PX` */
    const val LINEAR_FROM_PX = 24f

    /** 移植 fumeTextMeasure.ts:290 `PIXEL_EDGE_PX` */
    const val PIXEL_EDGE_PX = 0.02f

    /** 移植 fontStacks.ts:3-6（字重归一） */
    const val MIN_FONT_WEIGHT = 100
    const val MAX_FONT_WEIGHT = 900
    const val FONT_WEIGHT_STEP = 10

    // ── Android 侧的测量产物 ──────────────────────────────────────────────────

    /**
     * 移植 fumeTextMeasure.ts 中 `@chenglou/pretext` 的 `PreparedTextWithSegments`
     * （Folia 的 `segments` / `widths` / `breakableFitAdvances` 三个字段一一对应）。
     */
    class FumePreparedText(
        /** 切分后的文本段（词与空白交替），拼接起来等于原文 */
        val segments: List<String>,
        /** 每段的自然宽度（px） */
        val widths: FloatArray,
        /** 每段内逐字素的推进宽度（px）—— Folia 的 `breakableFitAdvances` */
        val advances: Array<FloatArray>,
        /** 整段文本逐字素的推进宽度（按段顺序拼接） */
        val graphemeAdvances: FloatArray,
        /** 整段文本逐字素是否为空白 */
        val graphemeIsSpace: BooleanArray,
    )

    /** 移植 fumeTextMeasure.ts:13-30 `buildSegmentMetas` 的返回结构 */
    class FumeSegmentMetas(
        /** 整行的字素序列 */
        val graphemes: List<String>,
        /** 每段在字素序列里的区间 */
        val segmentMetas: List<FumeTypes.SegmentMeta>,
    )

    /** 移植 fumeTextMeasure.ts 中 `layoutWithLines` 的行（Folia 用 `LayoutCursor`，此处用字素偏移） */
    class FumeLayoutLine(
        /** 本行覆盖的整行字素起点 */
        val start: Int,
        /** 本行覆盖的整行字素终点（不含） */
        val end: Int,
        val text: String,
        val width: Float,
    )

    /** 移植 fumeTextMeasure.ts 中 `layoutWithLines` 的返回结构 */
    class FumeTextLayout(val lines: List<FumeLayoutLine>)

    /**
     * 移植 fumeTextMeasure.ts:173-181 `buildFontSpec` 的 Android 等价物
     * （CSS 字体串 ⇒ `Paint` + 字号 + 字重）。
     */
    class FumeFontSpec(
        val paint: Paint,
        val fontPx: Float,
        val weight: Int,
    )

    /** 移植 fumeTextMeasure.ts:242-245 `FumeLineMeasures`（按字号缓存测量结果） */
    class FumeLineMeasures(
        val text: String,
        val variant: String,
        val fontFamily: String?,
        val fontWeight: Int?,
    ) {
        /** 按字号缓存的测量结果（键 = 字号 × 100 的整数） */
        val preparedByFontPx = HashMap<Int, FumePreparedText>()
    }

    /** 移植 fumeTextMeasure.ts:391-429 `chooseSingleLineFontPx` 的返回结构 */
    data class FumeFontChoice(val fontPx: Float, val lineCount: Int)

    // ── 字体 ────────────────────────────────────────────────────────────────

    /**
     * 移植 fontStacks.ts:7-12 `normalizeFontWeight`
     */
    fun normalizeFontWeight(fontWeight: Int?): Int? {
        if (fontWeight == null) return null
        val clamped = min(MAX_FONT_WEIGHT, max(MIN_FONT_WEIGHT, fontWeight))
        return (clamped / FONT_WEIGHT_STEP) * FONT_WEIGHT_STEP
    }

    /**
     * 移植 fumeTextMeasure.ts:173-181 `buildFontSpec`
     *
     * @param fontWeight 主题字重；null ⇒ 用 Folia 的回落值（hero 780 / body 640）。
     */
    fun buildFontSpec(
        fontPx: Float,
        variant: String,
        fontFamily: String?,
        fontWeight: Int?,
    ): FumeFontSpec {
        val fallback = if (variant == FumeTypes.VARIANT_HERO) 780 else 640
        val weight = normalizeFontWeight(fontWeight) ?: fallback
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.textSize = fontPx
        paint.typeface = typefaceFor(weight, fontFamily)
        return FumeFontSpec(paint, fontPx, weight)
    }

    /**
     * 字重 ⇒ `Typeface`。
     *
     * Android 的 `Typeface` 在 API 28 以下只有 normal / bold 两档（Folia 的 640 / 780 都落在 bold）。
     */
    private fun typefaceFor(weight: Int, fontFamily: String?): Typeface {
        val style = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
        val family = fontFamily?.takeIf { it.isNotBlank() } ?: "sans-serif"
        return try {
            Typeface.create(family, style)
        } catch (_: Throwable) {
            Typeface.defaultFromStyle(style)
        }
    }

    // ── 测量 ────────────────────────────────────────────────────────────────

    /**
     * 移植 `@chenglou/pretext` 的 `prepareWithSegments` ⇒ Android 等价物。
     *
     * 分段规则：**词与空白交替成段**（Folia 的 `whiteSpace: 'pre-wrap'` 允许在空白后断行，
     * 且空白本身要被保留）。
     */
    fun prepareText(text: String, fontSpec: FumeFontSpec): FumePreparedText {
        val graphemes = FumeMath.splitGraphemes(text)

        val segments = ArrayList<String>()
        val segmentGraphemes = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var currentIsSpace = false
        for (grapheme in graphemes) {
            val isSpace = grapheme.isNotEmpty() && grapheme.first().isWhitespace()
            if (current.isNotEmpty() && isSpace != currentIsSpace) {
                segments.add(current.joinToString(""))
                segmentGraphemes.add(current)
                current = ArrayList()
            }
            currentIsSpace = isSpace
            current.add(grapheme)
        }
        if (current.isNotEmpty()) {
            segments.add(current.joinToString(""))
            segmentGraphemes.add(current)
        }

        val widths = FloatArray(segments.size)
        val advances = Array(segments.size) { FloatArray(0) }
        val allAdvances = ArrayList<Float>()
        val allIsSpace = ArrayList<Boolean>()

        for (index in segments.indices) {
            val segmentGraphemes = segmentGraphemes[index]
            val segmentAdvances = measureSegmentAdvances(segmentGraphemes, fontSpec)
            advances[index] = segmentAdvances
            widths[index] = segmentAdvances.sum()
            for (local in segmentAdvances.indices) {
                allAdvances.add(segmentAdvances[local])
                allIsSpace.add(
                    segmentGraphemes[local].isNotEmpty() &&
                        segmentGraphemes[local].first().isWhitespace(),
                )
            }
        }

        return FumePreparedText(
            segments = segments,
            widths = widths,
            advances = advances,
            graphemeAdvances = allAdvances.toFloatArray(),
            graphemeIsSpace = allIsSpace.toBooleanArray(),
        )
    }

    /**
     * 移植 fumeTextMeasure.ts:188-217 `measureSegmentGlyphOffsets` 的**推进宽度**部分
     * （Folia 存的是前缀宽度，本仓存相邻前缀之差 —— 等价，且更省一次查表）。
     */
    private fun measureSegmentAdvances(graphemes: List<String>, fontSpec: FumeFontSpec): FloatArray {
        if (graphemes.isEmpty()) return FloatArray(0)
        val advances = FloatArray(graphemes.size)
        val builder = StringBuilder()
        var previous = 0f
        for (index in graphemes.indices) {
            builder.append(graphemes[index])
            val current = fontSpec.paint.measureText(builder.toString())
            advances[index] = current - previous
            previous = current
        }
        return advances
    }

    /**
     * 移植 fumeTextMeasure.ts:13-30 `buildSegmentMetas`
     */
    fun buildSegmentMetas(prepared: FumePreparedText): FumeSegmentMetas {
        val segmentMetas = ArrayList<FumeTypes.SegmentMeta>(prepared.segments.size)
        val graphemes = ArrayList<String>()
        var graphemeCursor = 0

        for (segment in prepared.segments) {
            val segmentGraphemes = FumeMath.splitGraphemes(segment)
            segmentMetas.add(
                FumeTypes.SegmentMeta(
                    graphemeStart = graphemeCursor,
                    graphemeEnd = graphemeCursor + segmentGraphemes.size,
                    graphemeCount = segmentGraphemes.size,
                ),
            )
            graphemes.addAll(segmentGraphemes)
            graphemeCursor += segmentGraphemes.size
        }

        return FumeSegmentMetas(graphemes, segmentMetas)
    }

    /** 移植 fumeTextMeasure.ts:43-70 `getPartialSegmentWidth` */
    private fun getPartialSegmentWidth(
        prepared: FumePreparedText,
        segmentIndex: Int,
        segmentMeta: FumeTypes.SegmentMeta,
        startOffset: Int,
        endOffset: Int,
    ): Float {
        val localStart = startOffset - segmentMeta.graphemeStart
        val localEnd = endOffset - segmentMeta.graphemeStart

        if (localEnd <= localStart) return 0f
        if (localStart == 0 && localEnd == segmentMeta.graphemeCount) {
            return prepared.widths[segmentIndex]
        }

        val advances = prepared.advances[segmentIndex]
        if (advances.isNotEmpty()) {
            var width = 0f
            for (index in localStart until localEnd) {
                width += advances[index]
            }
            return width
        }

        val fullWidth = prepared.widths[segmentIndex]
        if (segmentMeta.graphemeCount == 0) return fullWidth
        return fullWidth * ((localEnd - localStart).toFloat() / segmentMeta.graphemeCount.toFloat())
    }

    /**
     * 移植 fumeTextMeasure.ts:72-93 `widthBetweenOffsets`
     */
    fun widthBetweenOffsets(
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
        startOffset: Int,
        endOffset: Int,
    ): Float {
        if (endOffset <= startOffset) return 0f

        var width = 0f
        for (segmentIndex in segmentMetas.indices) {
            val meta = segmentMetas[segmentIndex]
            if (endOffset <= meta.graphemeStart) break
            if (startOffset >= meta.graphemeEnd) continue

            val sliceStart = max(startOffset, meta.graphemeStart)
            val sliceEnd = min(endOffset, meta.graphemeEnd)
            width += getPartialSegmentWidth(prepared, segmentIndex, meta, sliceStart, sliceEnd)
        }

        return width
    }

    /**
     * 移植 fumeTextMeasure.ts:95-111 `buildGlyphOffsets`
     *
     * Folia 存的是「从 startOffset 起的前缀宽度」；本仓存同样的前缀宽度数组。
     */
    fun buildGlyphOffsets(
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
        startOffset: Int,
        graphemeCount: Int,
    ): FloatArray {
        val offsets = FloatArray(graphemeCount)
        for (index in 0 until graphemeCount) {
            offsets[index] = widthBetweenOffsets(prepared, segmentMetas, startOffset, startOffset + index)
        }
        return offsets
    }

    /**
     * 移植 fumeTextMeasure.ts:113-122 `resolveGlyphAdvance`
     */
    fun resolveGlyphAdvance(renderLine: FumeTypes.RenderLineSlice, graphemeIndex: Int): Float {
        val currentOffset = if (graphemeIndex < renderLine.glyphOffsets.size) {
            renderLine.glyphOffsets[graphemeIndex]
        } else {
            0f
        }
        val nextOffset = if (graphemeIndex < renderLine.graphemes.size - 1) {
            if (graphemeIndex + 1 < renderLine.glyphOffsets.size) {
                renderLine.glyphOffsets[graphemeIndex + 1]
            } else {
                renderLine.width
            }
        } else {
            renderLine.width
        }
        return max(nextOffset - currentOffset, 0f)
    }

    /**
     * 移植 fumeTextMeasure.ts:124-171 `buildRenderSegments`
     */
    fun buildRenderSegments(
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
        lineStart: Int,
        lineEnd: Int,
        fontSpec: FumeFontSpec,
    ): List<FumeTypes.RenderSegmentSlice> {
        val segments = ArrayList<FumeTypes.RenderSegmentSlice>()

        for (segmentIndex in segmentMetas.indices) {
            val meta = segmentMetas[segmentIndex]
            if (lineEnd <= meta.graphemeStart) break
            if (lineStart >= meta.graphemeEnd) continue

            val start = max(lineStart, meta.graphemeStart)
            val end = min(lineEnd, meta.graphemeEnd)
            if (end <= start) continue

            val localStart = start - lineStart
            val localEnd = end - lineStart
            val segmentText = if (segmentIndex < prepared.segments.size) prepared.segments[segmentIndex] else ""
            val isFullSegment = start == meta.graphemeStart && end == meta.graphemeEnd
            val text = if (isFullSegment) {
                segmentText
            } else {
                val segmentGraphemes = FumeMath.splitGraphemes(segmentText)
                segmentGraphemes
                    .slice(start - meta.graphemeStart until end - meta.graphemeStart)
                    .joinToString("")
            }
            val measuredGlyphOffsets = measureSegmentGlyphOffsets(text, fontSpec)

            segments.add(
                FumeTypes.RenderSegmentSlice(
                    text = text,
                    start = start,
                    end = end,
                    localStart = localStart,
                    localEnd = localEnd,
                    x = widthBetweenOffsets(prepared, segmentMetas, lineStart, start),
                    width = widthBetweenOffsets(prepared, segmentMetas, start, end),
                    isFullSegment = isFullSegment,
                    measuredGlyphOffsets = measuredGlyphOffsets,
                ),
            )
        }

        return segments
    }

    /**
     * 移植 fumeTextMeasure.ts:188-217 `measureSegmentGlyphOffsets`
     * （Folia 用缓存；本仓每次现测 —— 只在版面构建时调用，不在绘制期）。
     */
    fun measureSegmentGlyphOffsets(text: String, fontSpec: FumeFontSpec): FloatArray {
        val graphemes = FumeMath.splitGraphemes(text)
        val offsets = FloatArray(graphemes.size + 1)
        if (graphemes.isEmpty()) return offsets
        val builder = StringBuilder()
        for (index in graphemes.indices) {
            builder.append(graphemes[index])
            offsets[index + 1] = fontSpec.paint.measureText(builder.toString())
        }
        return offsets
    }

    /**
     * 移植 fumeTextMeasure.ts:219-234 `buildWordRangeIndexByOffset`
     */
    fun buildWordRangeIndexByOffset(
        graphemeCount: Int,
        wordRanges: List<FumeTypes.WordRange>,
        rangeKind: String = "timing",
    ): IntArray {
        val indices = IntArray(graphemeCount) { -1 }
        for (rangeIndex in wordRanges.indices) {
            val range = wordRanges[rangeIndex]
            val start = if (rangeKind == "color") range.colorStart else range.start
            val end = if (rangeKind == "color") range.colorEnd else range.end
            var offset = start
            while (offset < end && offset < graphemeCount) {
                indices[offset] = rangeIndex
                offset += 1
            }
        }
        return indices
    }

    /** 移植 fumeTextMeasure.ts:236-238 `countRenderableGraphemes` */
    fun countRenderableGraphemes(text: String): Int {
        var count = 0
        for (grapheme in FumeMath.splitGraphemes(text)) {
            if (grapheme.trim().isNotEmpty()) count += 1
        }
        return count
    }

    // ── 断行（`layoutWithLines` 的 Android 等价物）────────────────────────────

    /**
     * `@chenglou/pretext` 的 `layoutWithLines(prepared, width, lineHeight)` 的 Android 等价物。
     *
     * 贪心断行：**优先在空白后断行**（`whiteSpace: 'pre-wrap'` 的语义，空白留在行尾），
     * 一段放不下时按**字素**断行（CJK 无空白时的行为）。
     */
    fun layoutLines(
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
        graphemes: List<String>,
        maxWidth: Float,
    ): FumeTextLayout {
        val lines = ArrayList<FumeLayoutLine>()
        val total = graphemes.size
        if (total == 0) return FumeTextLayout(lines)
        if (maxWidth <= 0f) {
            lines.add(
                FumeLayoutLine(
                    start = 0,
                    end = total,
                    text = graphemes.joinToString(""),
                    width = widthBetweenOffsets(prepared, segmentMetas, 0, total),
                ),
            )
            return FumeTextLayout(lines)
        }

        var lineStart = 0
        var lineWidth = 0f
        // 最近一个「空白之后」的可断点（空白本身留在上一行行尾）
        var breakAt = -1
        var breakWidth = 0f

        for (index in 0 until total) {
            val advance = if (index < prepared.graphemeAdvances.size) {
                prepared.graphemeAdvances[index]
            } else {
                0f
            }
            val grapheme = graphemes[index]
            val isSpace = index < prepared.graphemeIsSpace.size && prepared.graphemeIsSpace[index]

            if (lineWidth + advance > maxWidth && index > lineStart) {
                val cut = if (breakAt > lineStart) breakAt else index
                val cutWidth = if (breakAt > lineStart) breakWidth else lineWidth
                lines.add(makeLine(graphemes, lineStart, cut, prepared, segmentMetas))
                lineStart = cut
                lineWidth = lineWidth - cutWidth
                breakAt = -1
                breakWidth = lineWidth
            }

            lineWidth += advance

            if (isSpace) {
                breakAt = index + 1
                breakWidth = lineWidth
            }
        }

        if (lineStart < total) {
            lines.add(makeLine(graphemes, lineStart, total, prepared, segmentMetas))
        }

        return FumeTextLayout(lines)
    }

    private fun makeLine(
        graphemes: List<String>,
        start: Int,
        end: Int,
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
    ): FumeLayoutLine {
        val text = graphemes.subList(start, end).joinToString("")
        return FumeLayoutLine(
            start = start,
            end = end,
            text = text,
            width = widthBetweenOffsets(prepared, segmentMetas, start, end),
        )
    }

    /** 移植 fumeTextMeasure.ts 的 `countLines`（`walkLineRanges` 的计数） */
    fun countLines(
        prepared: FumePreparedText,
        segmentMetas: List<FumeTypes.SegmentMeta>,
        graphemes: List<String>,
        width: Float,
    ): Int = layoutLines(prepared, segmentMetas, graphemes, width).lines.size

    // ── 字号选择 ────────────────────────────────────────────────────────────

    /** 移植 fumeTextMeasure.ts:335-348 `prepareLineAt`（按字号缓存测量结果） */
    fun prepareLineAt(measures: FumeLineMeasures, fontPx: Float): FumePreparedText {
        val key = (fontPx * 100f).roundToInt()
        val cached = measures.preparedByFontPx[key]
        if (cached != null) return cached
        val fontSpec = buildFontSpec(fontPx, measures.variant, measures.fontFamily, measures.fontWeight)
        val prepared = prepareText(measures.text, fontSpec)
        measures.preparedByFontPx[key] = prepared
        return prepared
    }

    /**
     * 移植 fumeTextMeasure.ts:363-384 `fitsOnOneLine`
     *
     * Folia 靠「测量缓存 + 猜边界」减少测量次数；Android 直接测量（同样精确，见文件头说明）。
     */
    fun fitsOnOneLine(measures: FumeLineMeasures, fontPx: Float, width: Float): Boolean {
        val prepared = prepareLineAt(measures, fontPx)
        val metas = buildSegmentMetas(prepared)
        return countLines(prepared, metas.segmentMetas, metas.graphemes, width) <= 1
    }

    /**
     * 移植 fumeTextMeasure.ts:391-429 `chooseSingleLineFontPx`
     *
     * 8 次二分；候选字号 = 区间中点 × lyricsFontScale × densityScale ×（hero 时再乘 heroScale）；
     * 找不到放得下的字号 ⇒ 回落到区间下端 × 同样的缩放。
     *
     * ⚠️ **单位**：`width`（= 列宽）与返回的 `fontPx` 都以**世界单位**计
     *     （本仓 = 画布设备像素）。二分区间上界（body 30 / hero 58）是**绝对**常量
     *     ⇒ 世界单位一旦被缩小（早前用 dp ⇒ 视口 = 画布 / `density`），
     *     同样的 `fontPx` 相对屏幕就大 `density` 倍 ⇒ 整版文字过大、行宽跑出画面。
     *     详见 `FullViewSubRenderer` 的文件头。
     */
    fun chooseSingleLineFontPx(
        measures: FumeLineMeasures,
        width: Float,
        lyricsFontScale: Float,
        densityScale: Float,
        heroScale: Float,
    ): FumeFontChoice {
        val isHero = measures.variant == FumeTypes.VARIANT_HERO
        var low = if (isHero) 18f else 10f
        var high = if (isHero) 58f else 30f
        var best: Float? = null

        // 移植 fumeTextMeasure.ts:405-417 —— 8 次二分
        for (iteration in 0 until 8) {
            val candidateFontPx = ((low + high) / 2f) *
                lyricsFontScale *
                densityScale *
                (if (isHero) heroScale else 1f)

            if (fitsOnOneLine(measures, candidateFontPx, width)) {
                best = candidateFontPx
                low = (low + high) / 2f
            } else {
                high = (low + high) / 2f
            }
        }

        if (best != null) {
            val prepared = prepareLineAt(measures, best)
            val metas = buildSegmentMetas(prepared)
            return FumeFontChoice(best, countLines(prepared, metas.segmentMetas, metas.graphemes, width))
        }

        // 移植 fumeTextMeasure.ts:424-428
        val fallbackFontPx = (if (isHero) 18f else 10f) *
            lyricsFontScale *
            densityScale *
            (if (isHero) heroScale else 1f)
        val prepared = prepareLineAt(measures, fallbackFontPx)
        val metas = buildSegmentMetas(prepared)
        return FumeFontChoice(
            fallbackFontPx,
            countLines(prepared, metas.segmentMetas, metas.graphemes, width),
        )
    }

    // ── 渲染行的偏移查询 ────────────────────────────────────────────────────

    /** 移植 fumeTextMeasure.ts:431-442 `resolveRenderLineOffset` */
    fun resolveRenderLineOffset(renderLine: FumeTypes.RenderLineSlice, localOffset: Int): Float {
        if (localOffset <= 0) return 0f
        if (localOffset >= renderLine.graphemes.size) return renderLine.width
        return if (localOffset < renderLine.glyphOffsets.size) {
            renderLine.glyphOffsets[localOffset]
        } else {
            renderLine.width
        }
    }

    /** 移植 fumeTextMeasure.ts:444-450 `resolveSegmentGlyphOffset` */
    fun resolveSegmentGlyphOffset(segment: FumeTypes.RenderSegmentSlice, globalOffset: Int): Float {
        val localOffset = (globalOffset - segment.start).coerceIn(0, segment.measuredGlyphOffsets.size - 1)
        return segment.measuredGlyphOffsets[localOffset]
    }

    /** 移植 fumeTextMeasure.ts:452-460 `resolveSegmentGlyphAdvance` */
    fun resolveSegmentGlyphAdvance(segment: FumeTypes.RenderSegmentSlice, globalOffset: Int): Float {
        val localOffset = (globalOffset - segment.start).coerceIn(0, segment.measuredGlyphOffsets.size - 2)
        val current = segment.measuredGlyphOffsets[localOffset]
        val next = if (localOffset + 1 < segment.measuredGlyphOffsets.size) {
            segment.measuredGlyphOffsets[localOffset + 1]
        } else {
            current
        }
        return max(next - current, 0f)
    }
}
