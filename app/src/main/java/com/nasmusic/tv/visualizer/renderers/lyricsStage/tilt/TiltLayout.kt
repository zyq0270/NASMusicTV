package com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt

import com.nasmusic.tv.visualizer.renderers.lyricsStage.GraphemeSplitter
import com.nasmusic.tv.visualizer.renderers.lyricsStage.GraphemeTimingBuilder
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/tilt/VisualizerTilt.tsx
 * Folia 版本: 0.5.27-a16525c
 *
 * 倾斜律动的布局与时序：把一行拆成 1–4 段，其中恰好一段是「倾斜体」
 * （大号 italic、字符上下交错），其余是常规行。
 */
object TiltLayout {

    /** 移植 VisualizerTilt.tsx:16 */
    const val CHAR_REF_LENGTH = 20
    /** 移植 VisualizerTilt.tsx:17 */
    const val LOG_OFFSET = 4
    /** 移植 VisualizerTilt.tsx:18 */
    val LINE_THRESHOLDS = floatArrayOf(0.45f, 1.05f, 1.7f)

    /** 移植 VisualizerTilt.tsx:49 */
    const val REM_PX = 16f
    /** 移植 VisualizerTilt.tsx:50 */
    const val RESPLIT_THRESHOLD = 1.6f
    /** 移植 VisualizerTilt.tsx:51 */
    const val SCALE_FLOOR_NORMAL = 0.55f
    /** 移植 VisualizerTilt.tsx:52 */
    const val SCALE_FLOOR_TILT = 0.5f

    /** 移植 VisualizerTilt.tsx:37-42 `TiltSegment` */
    data class TiltSegment(
        val text: String,
        val isTilt: Boolean,
        val isShortLastLine: Boolean,
        /** 在整行 fullText 中的字符偏移 */
        val charOffset: Int,
    )

    /** 移植 VisualizerTilt.tsx:44-47 `TiltLayout` */
    data class TiltLayoutResult(
        val segments: List<TiltSegment>,
        val scaleMultiplier: Float,
    )

    /** 移植 VisualizerTilt.tsx:61-65 `CharTiming` */
    data class CharTiming(
        val charIndex: Int,
        val startTime: Double,
        val endTime: Double,
    )

    /** 移植 VisualizerTilt.tsx:20-28 `determineLineCount` */
    fun determineLineCount(charCount: Int, seed: Double, splitProbability: Double): Int {
        val normalized = Math.log(charCount + LOG_OFFSET.toDouble()) /
            Math.log((CHAR_REF_LENGTH + LOG_OFFSET).toDouble())
        val jitter = seededRandom(seed, 1) * 0.6 + 0.7
        val score = normalized * jitter * splitProbability
        return when {
            score < LINE_THRESHOLDS[0] -> 1
            score < LINE_THRESHOLDS[1] -> 2
            score < LINE_THRESHOLDS[2] -> 3
            else -> 4
        }
    }

    /** 移植 VisualizerTilt.tsx:206-209 `seededRandom` */
    fun seededRandom(seed: Double, offset: Int): Double {
        val x = Math.sin(seed * 1000 + offset) * 10000.0
        return x - Math.floor(x)
    }

    /** 移植 VisualizerTilt.tsx:200-204 `measureAtSize`
     *
     *  Folia 用 @chenglou/pretext 的 prepareWithSegments + layoutWithLines；
     *  Android 等价用 Paint.measureText。
     */
    fun measureAtSize(text: String, pxSize: Float, measure: (String, Float) -> Float): Float =
        measure(text, pxSize)

    /** 移植 VisualizerTilt.tsx:180-198 `getCharPulseIntensity` */
    fun getCharPulseIntensity(currentTime: Double, charTiming: CharTiming): Float {
        val startTime = charTiming.startTime
        val endTime = charTiming.endTime
        val rawDuration = max(endTime - startTime, 0.05)
        val duration = minOf(maxOf(rawDuration, 0.2), 0.9)
        val elapsed = currentTime - startTime

        if (elapsed < 0) return 0f

        if (elapsed <= duration) {
            val progress = elapsed / duration
            return Math.sin(progress * Math.PI).toFloat()
        }

        val afterElapsed = elapsed - duration
        val afterglowRamp = duration * 1.2
        if (afterElapsed >= afterglowRamp) return 0.25f

        return (0.25 * (afterElapsed / afterglowRamp)).toFloat()
    }

    /** 移植 VisualizerTilt.tsx:67-101 `findSegmentWordRange` */
    fun findSegmentWordRange(
        charOffset: Int,
        segmentText: String,
        fullText: String,
        words: List<LyricWord>,
    ): Pair<Int, Int> {
        val segmentStart = charOffset
        if (segmentStart < 0 || segmentStart >= fullText.length) return 0 to words.size

        val segmentEnd = segmentStart + segmentText.length

        var fullTextPos = 0
        var startWordIndex = 0
        var endWordIndex = words.size

        for (wi in words.indices) {
            val wordText = words[wi].text
            val wordFullStart = fullText.indexOf(wordText, fullTextPos)

            if (wordFullStart == -1) {
                fullTextPos += wordText.length
                continue
            }

            val wordFullEnd = wordFullStart + wordText.length

            if (wordFullStart <= segmentStart && wordFullEnd > segmentStart) {
                startWordIndex = wi
            }

            if (wordFullStart < segmentEnd && wordFullEnd >= segmentEnd) {
                endWordIndex = wi + 1
                break
            }

            fullTextPos = wordFullEnd
        }

        return startWordIndex to endWordIndex
    }

    /** 移植 VisualizerTilt.tsx:103-178 `buildCharTimings` */
    fun buildCharTimings(
        charOffset: Int,
        segmentText: String,
        segmentStartTime: Double,
        segmentEndTime: Double,
        activeLine: LyricLine?,
    ): List<CharTiming> {
        val graphemes = GraphemeSplitter.split(segmentText)
        if (activeLine == null || graphemes.isEmpty()) return emptyList()

        val nonSpaceGraphemes = graphemes.filter { !it.isBlank() }
        if (nonSpaceGraphemes.isEmpty()) return emptyList()

        val totalDuration = max(segmentEndTime - segmentStartTime, 0.3)
        val (startWordIndex, endWordIndex) =
            findSegmentWordRange(charOffset, segmentText, activeLine.fullText, activeLine.words)
        val segmentWords = activeLine.words.subList(startWordIndex, endWordIndex)

        var currentCharIndex = 0
        val timings = mutableListOf<CharTiming>()

        if (segmentWords.isNotEmpty()) {
            for (word in segmentWords) {
                if (!word.syllables.isNullOrEmpty()) {
                    val wordTimings = GraphemeTimingBuilder.buildFor(word).filter { !it.char.isBlank() }
                    for (timing in wordTimings) {
                        if (currentCharIndex >= nonSpaceGraphemes.size) break
                        timings.add(
                            CharTiming(currentCharIndex, timing.startTime, timing.endTime)
                        )
                        currentCharIndex++
                    }
                    continue
                }

                val wordGraphemes = GraphemeSplitter.split(word.text)
                val wordNonSpaceCount = wordGraphemes.count { !it.isBlank() }
                if (wordNonSpaceCount == 0) continue

                val wordDuration = max(word.endTime - word.startTime, 0.05)
                val charDuration = wordDuration / wordNonSpaceCount
                var nonSpaceCi = 0

                for (grapheme in wordGraphemes) {
                    if (currentCharIndex >= nonSpaceGraphemes.size) break
                    if (grapheme.isBlank()) continue

                    timings.add(
                        CharTiming(
                            currentCharIndex,
                            word.startTime + nonSpaceCi * charDuration,
                            word.startTime + (nonSpaceCi + 1) * charDuration,
                        )
                    )

                    currentCharIndex++
                    nonSpaceCi++
                }
            }
        }

        if (timings.isEmpty() || timings.size != nonSpaceGraphemes.size) {
            val avgCharDuration = totalDuration / nonSpaceGraphemes.size
            timings.clear()
            currentCharIndex = 0

            for (grapheme in graphemes) {
                if (grapheme.isBlank()) continue
                timings.add(
                    CharTiming(
                        currentCharIndex,
                        segmentStartTime + currentCharIndex * avgCharDuration,
                        segmentStartTime + (currentCharIndex + 1) * avgCharDuration,
                    )
                )
                currentCharIndex++
            }
        }

        return timings
    }

    /** 移植 VisualizerTilt.tsx:272-281 `markShortLastLine` */
    fun markShortLastLine(segments: List<TiltSegment>): List<TiltSegment> {
        if (segments.size < 2) return segments
        val last = segments[segments.lastIndex]
        if (last.isTilt || last.text.trim().length > 2) return segments
        val prev = segments[segments.size - 2]
        if (last.text.trim().length * 2 <= prev.text.length) {
            val updated = segments.toMutableList()
            updated[updated.lastIndex] = last.copy(isShortLastLine = true)
            return updated
        }
        return segments
    }

    /** 移植 VisualizerTilt.tsx:211-332 `buildTiltLayout`
     *
     *  @param availableWidth 可用宽度（Folia 的 `getAvailableWidth()` = max(320, vw) * 0.85）
     *  @param measure 文字测量回调（Android: Paint.measureText）
     *  @param viewportWidth 视口宽度（Folia 用 `window.innerWidth`）——
     *    ⚠️ 字号上限用它算（`viewportWidth * 0.06875`），**不是**用可用宽度。
     *    省略时由可用宽度反推（vw >= 320 时精确；更窄的视口只有 < 12% 的字号差异）。
     */
    fun buildTiltLayout(
        fullText: String,
        lineSeed: Double,
        splitProbability: Double,
        tiltStyleProbability: Double,
        fontScale: Float,
        availableWidth: Float,
        measure: (String, Float) -> Float,
        viewportWidth: Float = availableWidth / 0.85f,
    ): TiltLayoutResult {
        val measureMaxPx = max(
            viewportWidth * 0.06875f,
            5.625f * REM_PX * fontScale,
        )
        val normalBasePx = measureMaxPx
        val tiltBasePx = measureMaxPx

        val charCount = fullText.trim().length
        val isEllipsisOnly = Regex("^[\\s.…·。]+$").matches(fullText.trim())

        val mergedSegments: List<String>
        if (isEllipsisOnly) {
            mergedSegments = listOf(fullText)
        } else {
            val numLines = determineLineCount(charCount, lineSeed, splitProbability)
            mergedSegments = SentenceLayout.splitIntoSentences(fullText, numLines, lineSeed)
        }

        // 移植 VisualizerTilt.tsx:233-242 — 倾斜段选择
        val candidates = mutableListOf<Int>()
        mergedSegments.forEachIndexed { i, _ ->
            val lineRoll = seededRandom(lineSeed, 100 + i)
            if (lineRoll < tiltStyleProbability) candidates.add(i)
        }

        var finalTiltIndex = -1
        if (candidates.isNotEmpty()) {
            finalTiltIndex = candidates[(seededRandom(lineSeed, 200) * candidates.size).toInt()]
        }

        var offsetAccum = 0
        val segments = mergedSegments.mapIndexed { i, text ->
            val trimmed = text.trim()
            val leadingSpaces = text.length - text.trimStart().length
            val seg = TiltSegment(
                text = trimmed,
                isTilt = i == finalTiltIndex,
                isShortLastLine = false,
                charOffset = offsetAccum + leadingSpaces,
            )
            offsetAccum += text.length
            seg
        }

        var scaleMultiplier = 1f
        val tiltWithWidth = segments.filter { it.isTilt }
            .map { it.text to measure(it.text, tiltBasePx) }
        val normalWithWidth = segments.filter { !it.isTilt }
            .map { it.text to measure(it.text, normalBasePx) }

        val widestTiltEntry = tiltWithWidth.maxByOrNull { it.second }
        val widestNormalEntry = normalWithWidth.maxByOrNull { it.second }

        val tiltWidth = widestTiltEntry?.second ?: 0f
        val normalWidth = widestNormalEntry?.second ?: 0f

        val tiltOverflow = if (tiltWidth > 0f) tiltWidth / availableWidth else 0f
        val normalOverflow = if (normalWidth > 0f) normalWidth / availableWidth else 0f
        val maxOverflow = max(tiltOverflow, normalOverflow)

        if (maxOverflow > 1f) {
            if (maxOverflow >= RESPLIT_THRESHOLD) {
                // 移植 VisualizerTilt.tsx:284-321 — resplit
                val targetWidth = availableWidth * (RESPLIT_THRESHOLD - 0.15f)
                val totalEstWidth = measure(fullText, normalBasePx)
                val extraSplitsNeeded = minOf(
                    4,
                    maxOf(
                        segments.size + 1,
                        ceil(totalEstWidth / targetWidth).toInt(),
                    ),
                )
                if (extraSplitsNeeded > segments.size) {
                    val reSplitUnits = SentenceLayout.splitIntoSentences(fullText, extraSplitsNeeded, lineSeed)
                    var reSplitOffset = 0
                    val newSegments = reSplitUnits.map { u ->
                        val trimmed = u.trim()
                        val leadingSpaces = u.length - u.trimStart().length
                        val seg = TiltSegment(trimmed, false, false, reSplitOffset + leadingSpaces)
                        reSplitOffset += u.length
                        seg
                    }.toMutableList()

                    if (newSegments.isNotEmpty()) {
                        val resplitTiltRoll = seededRandom(lineSeed, 300)
                        if (resplitTiltRoll < tiltStyleProbability) {
                            val picked = (seededRandom(lineSeed, 301) * newSegments.size).toInt()
                                .coerceIn(0, newSegments.lastIndex)
                            newSegments[picked] = newSegments[picked].copy(isTilt = true)
                        }
                    }

                    val postWidestTilt = newSegments.firstOrNull { it.isTilt }
                    val postNormalWithWidth = newSegments.filter { !it.isTilt }
                        .map { it.text to measure(it.text, normalBasePx) }
                    val postWidestNormal = postNormalWithWidth.maxByOrNull { it.second }

                    var postScale = 1f
                    if (postWidestTilt != null) {
                        val w = measure(postWidestTilt.text, tiltBasePx)
                        if (w > availableWidth) postScale = max(SCALE_FLOOR_TILT, availableWidth / w)
                    } else if (postWidestNormal != null) {
                        val w = measure(postWidestNormal.first, normalBasePx)
                        if (w > availableWidth) postScale = max(SCALE_FLOOR_NORMAL, availableWidth / w)
                    }

                    return TiltLayoutResult(markShortLastLine(newSegments), postScale)
                }
            }

            // 移植 VisualizerTilt.tsx:324-328 — 仅缩放
            if (tiltOverflow >= normalOverflow && widestTiltEntry != null) {
                scaleMultiplier = max(SCALE_FLOOR_TILT, availableWidth / tiltWidth)
            } else if (widestNormalEntry != null) {
                scaleMultiplier = max(SCALE_FLOOR_NORMAL, availableWidth / normalWidth)
            }
        }

        return TiltLayoutResult(markShortLastLine(segments), scaleMultiplier)
    }

    /** 移植 VisualizerTilt.tsx:54 `getAvailableWidth` */
    fun getAvailableWidth(viewportWidth: Float): Float = max(320f, viewportWidth) * 0.85f

    /** 供 roundToInt 保持一致（Folia 的 Math.round） */
    internal fun roundHalfUp(value: Double): Int = value.roundToInt()
}
