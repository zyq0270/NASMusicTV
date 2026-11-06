// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeReveal.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import com.nasmusic.tv.visualizer.renderers.lyricsStage.GraphemeTimingBuilder
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricRenderHints

/**
 * fume 的歌词揭示时序：词区间、已打印的 grapheme 数、一行「算唱过」的截止时刻。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeReveal.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物
 *  - `buildWordGraphemeTimings(word)` → 本仓已有的 `GraphemeTimingBuilder.buildFor(word)`
 *  - `getLineRenderEndTime(line)` → 本仓已有的 `LyricRenderHints.getLineRenderEndTime(line)`
 *  - `splitGraphemes(text)` → `FumeMath.splitGraphemes(text)`（本仓 `GraphemeSplitter`）
 */
object FumeReveal {

    /**
     * 移植 fumeReveal.ts:10-48 `buildWordRangesFromWords`
     *
     * Folia 里叫 `buildWordRangesFromWords`；本仓沿用同一逻辑，命名为 `buildWordRanges`。
     *
     * ⚠️ 词文本里可能没有行文本中的空格（Folia 注释：部分歌词负载的 `word.text` 不含空格，
     * 而 `fullText` 里有）⇒ 这里把紧随其后的空白**并进当前词区间**，
     * 让 grapheme 流保持连续，后面的词不会整体左移。
     */
    fun buildWordRanges(line: LyricLine, graphemes: List<String>): List<FumeTypes.WordRange> {
        if (line.words.isEmpty() || graphemes.isEmpty()) return emptyList()

        // 移植 fumeReveal.ts:15-18 —— 丢掉没有 grapheme 的词
        val rangedWords = line.words.filter { FumeMath.splitGraphemes(it.text).isNotEmpty() }
        if (rangedWords.isEmpty()) return emptyList()

        val ranges = ArrayList<FumeTypes.WordRange>(rangedWords.size)
        var cursor = 0

        for (wordIndex in rangedWords.indices) {
            val word = rangedWords[wordIndex]
            val wordGraphemes = FumeMath.splitGraphemes(word.text)
            val start = FumeMath.clamp(cursor.toFloat(), 0f, graphemes.size.toFloat()).toInt()
            var end = FumeMath.clamp(
                (start + wordGraphemes.size).toFloat(),
                start.toFloat(),
                graphemes.size.toFloat(),
            ).toInt()

            // 移植 fumeReveal.ts:31-33 —— 把后面的空白并进当前词
            while (end < graphemes.size && graphemes[end].isNotEmpty() && graphemes[end].first().isWhitespace()) {
                end += 1
            }

            ranges.add(
                FumeTypes.WordRange(
                    wordIndex = wordIndex,
                    word = word,
                    start = start,
                    end = end,
                    colorStart = start,
                    colorEnd = end,
                    graphemeTimings = GraphemeTimingBuilder.buildFor(word),
                ),
            )
            cursor = end
        }

        return ranges
    }

    /** 移植 fumeReveal.ts:50-60 `resolveWordRevealProgress` */
    private fun resolveWordRevealProgress(range: FumeTypes.WordRange, currentTimeValue: Double): Float {
        if (range.word.endTime <= range.word.startTime) {
            return if (currentTimeValue >= range.word.endTime) 1f else 0f
        }

        val duration = Math.max(range.word.endTime - range.word.startTime, 0.08)
        return FumeMath.clamp(((currentTimeValue - range.word.startTime) / duration).toFloat(), 0f, 1f)
    }

    /**
     * 移植 fumeReveal.ts:62-100 `resolvePrintedGlyphsInRange`
     *
     * 有 syllable 时序 ⇒ 逐 grapheme 的 `startTime` 判定；
     * 没有 ⇒ 按词的揭示进度均分（`floor(progress * length + 0.2)`）。
     */
    fun resolvePrintedGlyphsInRange(range: FumeTypes.WordRange, currentTimeValue: Double): Int {
        val length = Math.max(range.end - range.start, 0)
        if (length == 0) return 0

        if (currentTimeValue < range.word.startTime) return 0

        // 移植 fumeReveal.ts:75
        val timedGlyphCount = if (!range.word.syllables.isNullOrEmpty()) {
            Math.min(range.graphemeTimings.size, length)
        } else {
            0
        }

        if (timedGlyphCount > 0) {
            if (currentTimeValue >= range.word.endTime) return length

            var printed = 0
            for (index in 0 until timedGlyphCount) {
                val timing = range.graphemeTimings[index]
                if (currentTimeValue >= timing.startTime) {
                    printed = index + 1
                }
            }
            return FumeMath.clamp(printed.toFloat(), 0f, length.toFloat()).toInt()
        }

        val progress = resolveWordRevealProgress(range, currentTimeValue)
        if (progress >= 1f) return length

        return FumeMath.clamp(
            Math.floor(progress * length + 0.2).toFloat(),
            if (progress > 0f) 1f else 0f,
            length.toFloat(),
        ).toInt()
    }

    /** 移植 fumeReveal.ts:102-105 `hasRevealCompletedByLineEnd` */
    private fun hasRevealCompletedByLineEnd(line: LyricLine, currentTimeValue: Double): Boolean =
        currentTimeValue >= line.endTime

    /**
     * 移植 fumeReveal.ts:107-117 `resolveLinePassCutoffTime`
     *
     * @param nextLineStartTime 下一行的开始时间；null / 非有限值 ⇒ 只用本行的渲染结束时间。
     */
    fun resolveLinePassCutoffTime(line: LyricLine, nextLineStartTime: Double?): Double {
        val renderEndTime = LyricRenderHints.getLineRenderEndTime(line)
        if (nextLineStartTime == null || !nextLineStartTime.isFinite()) {
            return renderEndTime
        }
        return Math.min(renderEndTime, nextLineStartTime)
    }

    /**
     * 移植 fumeReveal.ts:119-136 `resolveVisualProgressWithCutoff`
     *
     * 进度被「截止时刻」截断：行唱过之后视觉进度不再往前走。
     */
    fun resolveVisualProgressWithCutoff(
        startedAt: Double,
        duration: Double,
        currentTimeValue: Double,
        cutoffTime: Double,
    ): Float {
        val nominalEndTime = startedAt + Math.max(duration, 0.001)
        val effectiveEndTime = Math.max(
            startedAt + 0.001,
            Math.min(nominalEndTime, cutoffTime),
        )

        return FumeMath.clamp(
            ((currentTimeValue - startedAt) / Math.max(effectiveEndTime - startedAt, 0.001)).toFloat(),
            0f,
            1f,
        )
    }

    /**
     * 移植 fumeReveal.ts:138-174 `resolvePrintedGraphemeCount`
     *
     * 整行已打印的 grapheme 数（整数）。
     */
    fun resolvePrintedGraphemeCount(
        line: LyricLine,
        wordRanges: List<FumeTypes.WordRange>,
        graphemeCount: Int,
        currentTimeValue: Double,
    ): Int {
        if (graphemeCount == 0) return 0

        if (currentTimeValue < line.startTime) return 0

        if (hasRevealCompletedByLineEnd(line, currentTimeValue)) return graphemeCount

        if (wordRanges.isEmpty()) {
            val duration = Math.max(line.endTime - line.startTime, 0.12)
            val progress = FumeMath.clamp(((currentTimeValue - line.startTime) / duration).toFloat(), 0f, 1f)
            return FumeMath.clamp(
                Math.floor((progress * graphemeCount + if (progress > 0f) 1f else 0f).toDouble()).toFloat(),
                0f,
                graphemeCount.toFloat(),
            ).toInt()
        }

        var printed = 0
        for (index in wordRanges.indices) {
            val range = wordRanges[index]
            val partial = resolvePrintedGlyphsInRange(range, currentTimeValue)
            printed = range.start + partial

            if (partial < range.end - range.start) {
                return FumeMath.clamp(printed.toFloat(), 0f, graphemeCount.toFloat()).toInt()
            }
        }

        return FumeMath.clamp(printed.toFloat(), 0f, graphemeCount.toFloat()).toInt()
    }

    /**
     * 移植 fumeReveal.ts:176-217 `resolvePrintedGraphemeProgress`
     *
     * 整行已打印的 grapheme **数**（小数）—— 相机平滑跟随用。
     */
    fun resolvePrintedGraphemeProgress(
        line: LyricLine,
        wordRanges: List<FumeTypes.WordRange>,
        graphemeCount: Int,
        currentTimeValue: Double,
    ): Float {
        if (graphemeCount == 0) return 0f

        if (currentTimeValue < line.startTime) return 0f

        if (hasRevealCompletedByLineEnd(line, currentTimeValue)) return graphemeCount.toFloat()

        if (wordRanges.isEmpty()) {
            val duration = Math.max(line.endTime - line.startTime, 0.12)
            val progress = FumeMath.clamp(((currentTimeValue - line.startTime) / duration).toFloat(), 0f, 1f)
            return FumeMath.clamp(progress * graphemeCount, 0f, graphemeCount.toFloat())
        }

        var printed = 0f
        for (index in wordRanges.indices) {
            val range = wordRanges[index]
            if (currentTimeValue < range.word.startTime) {
                return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
            }

            val progress = resolveWordRevealProgress(range, currentTimeValue)
            val length = Math.max(range.end - range.start, 0)
            printed = range.start + progress * length

            if (progress < 1f) {
                return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
            }
        }

        return FumeMath.clamp(printed, 0f, graphemeCount.toFloat())
    }
}
