package com.nasmusic.tv.visualizer.renderers.lyricsStage

/**
 * 移植自 Folia（AGPL-3.0）: src/utils/lyrics/graphemeTiming.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * 「由解析器派生的 grapheme 时序」—— 不拥有视觉器动画曲线。
 */
object GraphemeTimingBuilder {

    /** 移植 graphemeTiming.ts:29-49 `buildEvenGraphemeTimings` */
    private fun buildEvenGraphemeTimings(
        text: String,
        startTime: Double,
        endTime: Double,
        wordIndex: Int? = null,
    ): List<GraphemeTiming> {
        val graphemes = GraphemeSplitter.split(text)
        if (graphemes.isEmpty()) return emptyList()

        val duration = Math.max(endTime - startTime, 0.0)
        val unitDuration = duration / graphemes.size

        return graphemes.mapIndexed { index, char ->
            GraphemeTiming(
                char = char,
                startTime = startTime + unitDuration * index,
                endTime = if (index == graphemes.lastIndex) endTime else startTime + unitDuration * (index + 1),
                wordIndex = wordIndex,
            )
        }
    }

    /** 移植 graphemeTiming.ts:51-59 `buildWordGraphemeTimings` */
    fun buildFor(word: LyricWord, wordIndex: Int? = null): List<GraphemeTiming> {
        val syllables = word.syllables
        if (syllables.isNullOrEmpty()) {
            return buildEvenGraphemeTimings(word.text, word.startTime, word.endTime, wordIndex)
        }
        return syllables.flatMap { syllable ->
            buildEvenGraphemeTimings(syllable.text, syllable.startTime, syllable.endTime, wordIndex)
        }
    }

    /** 移植 graphemeTiming.ts:61-80 `findGraphemeSequence` */
    private fun findGraphemeSequence(
        source: List<String>,
        target: List<String>,
        fromIndex: Int,
    ): Int {
        if (target.isEmpty()) return fromIndex

        for (index in fromIndex..(source.size - target.size)) {
            if (index < 0) continue
            var matched = true
            for (targetIndex in target.indices) {
                if (source[index + targetIndex] != target[targetIndex]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    /** 移植 graphemeTiming.ts:85-154 `buildLineGraphemeTimeline`
     *
     *  把词级时序映射回整行（含空格与标点 —— 它们未必出现在解析器的词里）。
     */
    fun buildLineTimeline(line: LyricLine): List<GraphemeTiming?> {
        val lineGraphemes = GraphemeSplitter.split(line.fullText)
        if (lineGraphemes.isEmpty()) return emptyList()

        if (line.words.isEmpty()) {
            return buildEvenGraphemeTimings(line.fullText, line.startTime, line.endTime)
                .let { List(it.size) { i -> it[i] } }
        }

        val timeline = arrayOfNulls<GraphemeTiming>(lineGraphemes.size)
        var cursor = 0
        var lastResolvedTime = line.startTime

        line.words.forEachIndexed { wordIndex, word ->
            val wordGraphemes = GraphemeSplitter.split(word.text)
            if (wordGraphemes.isEmpty()) return@forEachIndexed

            val matchedStart = findGraphemeSequence(lineGraphemes, wordGraphemes, cursor)
            val start = if (matchedStart >= 0) matchedStart else cursor
            val end = Math.min(start + wordGraphemes.size, lineGraphemes.size)

            for (gapIndex in cursor until start) {
                timeline[gapIndex] = GraphemeTiming(
                    char = lineGraphemes[gapIndex],
                    startTime = word.startTime,
                    endTime = word.startTime,
                )
            }

            val wordTimings = buildFor(word, wordIndex)
            for (localIndex in 0 until (end - start)) {
                val timing = wordTimings.getOrNull(localIndex)
                    ?: buildEvenGraphemeTimings(
                        wordGraphemes.getOrElse(localIndex) { "" },
                        word.startTime,
                        word.endTime,
                        wordIndex,
                    ).firstOrNull()
                    ?: continue

                timeline[start + localIndex] = GraphemeTiming(
                    char = lineGraphemes[start + localIndex],
                    startTime = timing.startTime,
                    endTime = timing.endTime,
                    wordIndex = timing.wordIndex,
                )
                lastResolvedTime = Math.max(lastResolvedTime, timing.endTime)
            }

            cursor = Math.max(cursor, end)
        }

        for (index in timeline.indices) {
            if (timeline[index] != null) continue
            timeline[index] = GraphemeTiming(
                char = lineGraphemes[index],
                startTime = lastResolvedTime,
                endTime = lastResolvedTime,
            )
        }

        return timeline.toList()
    }
}
