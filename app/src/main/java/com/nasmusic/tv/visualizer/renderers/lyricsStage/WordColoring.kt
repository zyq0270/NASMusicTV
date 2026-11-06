package com.nasmusic.tv.visualizer.renderers.lyricsStage

/**
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/wordColoring.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * 基于「范围」的关键词着色辅助，供渲染定时词元的视觉器使用。
 */

/**
 * 移植 wordColoring.ts:53-56 `rangesOverlap` 的**入参形状**：
 * Folia 用 `Pick<WordColorRange, 'startOffset' | 'endOffset'>`，
 * 即「任何带这两个字段的对象」⇒ 这里用接口表达。
 */
interface WordColorOffsetRange {
    val startOffset: Int
    val endOffset: Int
}

/** 移植 wordColoring.ts:6-11 `WordColorToken` */
data class WordColorToken(
    val key: String,
    val timed: Boolean,
    override val startOffset: Int,
    override val endOffset: Int,
) : WordColorOffsetRange

/** 移植 wordColoring.ts:13-20 `WordColorRange` */
data class WordColorRange(
    override val startOffset: Int,
    override val endOffset: Int,
    val color: String,
    val priority: Int,
) : WordColorOffsetRange

/** 移植 wordColoring.ts:22-26 `WordColorMatcher` */
data class WordColorMatcher(
    val color: String,
    val cjkPhrases: List<String>,
    val englishWords: List<String>,
    val priority: Int,
)

/** Folia 的 `theme.wordColors` 条目形状（word + color） */
data class WordColorEntry(val word: String, val color: String)

/** 移植 wordColoring.ts:29-33 */
private val CJK_REGEX = Regex("[\\u4e00-\\u9fa5\\u3040-\\u30ff\\uac00-\\ud7af]")

private fun isCJK(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

/** 移植 wordColoring.ts:33 */
private fun normalizeWordColorToken(text: String): String =
    text.lowercase().replace(Regex("[^\\w]"), "")

object WordColoring {

    /** 移植 wordColoring.ts:53-56 `rangesOverlap` */
    fun rangesOverlap(a: WordColorOffsetRange, b: WordColorOffsetRange): Boolean =
        a.startOffset < b.endOffset && b.startOffset < a.endOffset

    /** 移植 wordColoring.ts:57-72 `selectNonOverlappingRanges` */
    fun selectNonOverlappingRanges(ranges: List<WordColorRange>): List<WordColorRange> {
        val selected = mutableListOf<WordColorRange>()
        val prioritySorted = ranges.sortedWith(
            compareByDescending<WordColorRange> { it.priority }
                .thenBy { it.startOffset }
                .thenBy { it.endOffset }
        )

        for (range in prioritySorted) {
            if (selected.none { rangesOverlap(it, range) }) {
                selected.add(range)
            }
        }

        return selected.sortedWith(compareBy({ it.startOffset }, { it.endOffset }))
    }

    /** 移植 wordColoring.ts:74-118 `prepareWordColorMatchers` */
    fun prepareWordColorMatchers(
        wordColors: List<WordColorEntry>,
        keywordColoringEnabled: Boolean = true,
    ): List<WordColorMatcher> {
        if (!keywordColoringEnabled || wordColors.isEmpty()) return emptyList()

        val matchers = mutableListOf<WordColorMatcher>()
        for (entry in wordColors) {
            val target = entry.word.trim()
            if (target.isEmpty()) continue
            val color = entry.color
            if (color.isEmpty()) continue

            if (isCJK(target)) {
                matchers.add(
                    WordColorMatcher(
                        color = color,
                        cjkPhrases = listOf(target),
                        englishWords = emptyList(),
                        priority = target.length,
                    )
                )
                continue
            }

            val englishWords = target.split(Regex("\\s+"))
                .map { normalizeWordColorToken(it) }
                .filter { it.isNotEmpty() }

            if (englishWords.isEmpty()) continue

            matchers.add(
                WordColorMatcher(
                    color = color,
                    cjkPhrases = emptyList(),
                    englishWords = englishWords,
                    priority = englishWords.maxOf { it.length },
                )
            )
        }
        return matchers
    }

    /** 移植 wordColoring.ts:121-176 `buildWordColorRangesFromMatchers` */
    fun buildWordColorRangesFromMatchers(
        lineText: String,
        matchers: List<WordColorMatcher>,
    ): List<WordColorRange> {
        if (lineText.isEmpty() || matchers.isEmpty()) return emptyList()

        val ranges = mutableListOf<WordColorRange>()
        val englishColorByWord = LinkedHashMap<String, WordColorRange>()

        for (matcher in matchers) {
            for (target in matcher.cjkPhrases) {
                var cursor = 0
                while (cursor < lineText.length) {
                    val startOffset = lineText.indexOf(target, cursor)
                    if (startOffset < 0) break
                    ranges.add(
                        WordColorRange(
                            startOffset = startOffset,
                            endOffset = startOffset + target.length,
                            color = matcher.color,
                            priority = matcher.priority,
                        )
                    )
                    cursor = startOffset + Math.max(target.length, 1)
                }
            }

            for (word in matcher.englishWords) {
                val current = englishColorByWord[word]
                if (current == null || matcher.priority > current.priority) {
                    englishColorByWord[word] = WordColorRange(
                        startOffset = 0,
                        endOffset = 0,
                        color = matcher.color,
                        priority = matcher.priority,
                    )
                }
            }
        }

        if (englishColorByWord.isNotEmpty()) {
            val wordRegex = Regex("\\w+")
            for (match in wordRegex.findAll(lineText)) {
                val wordColor = englishColorByWord[normalizeWordColorToken(match.value)]
                if (wordColor != null) {
                    ranges.add(
                        WordColorRange(
                            startOffset = match.range.first,
                            endOffset = match.range.last + 1,
                            color = wordColor.color,
                            priority = wordColor.priority,
                        )
                    )
                }
            }
        }

        return selectNonOverlappingRanges(ranges)
    }

    /** 移植 wordColoring.ts:178-187 `buildWordColorRanges` */
    fun buildWordColorRanges(
        lineText: String,
        wordColors: List<WordColorEntry>,
        keywordColoringEnabled: Boolean = true,
    ): List<WordColorRange> = buildWordColorRangesFromMatchers(
        lineText,
        prepareWordColorMatchers(wordColors, keywordColoringEnabled),
    )

    /** 移植 wordColoring.ts:189-235 `resolveWordColor`
     *
     *  逐词配色 —— 这是「逐字行光」的核心视觉特征，不能简化为统一的 accentColor。
     */
    fun resolveWordColor(
        wordText: String,
        wordColors: List<WordColorEntry>,
        fallbackColor: String,
        keywordColoringEnabled: Boolean = true,
        cjkMatchMode: String = "target-contains-token",
    ): String {
        if (!keywordColoringEnabled || wordColors.isEmpty()) return fallbackColor

        val cleanCurrent = wordText.trim()
        if (cleanCurrent.isEmpty()) return fallbackColor

        val matched = wordColors.firstOrNull { entry ->
            val target = entry.word.trim()
            if (target.isEmpty()) return@firstOrNull false

            if (isCJK(cleanCurrent)) {
                when (cjkMatchMode) {
                    "exact" -> target == cleanCurrent
                    "bidirectional-contains" -> target.contains(cleanCurrent) || cleanCurrent.contains(target)
                    else -> target.contains(cleanCurrent)
                }
            } else {
                val targetWords = target.split(Regex("\\s+"))
                    .map { normalizeWordColorToken(it) }
                    .filter { it.isNotEmpty() }
                val normalizedCurrent = normalizeWordColorToken(cleanCurrent)
                normalizedCurrent.isNotEmpty() && targetWords.contains(normalizedCurrent)
            }
        }

        return matched?.color?.takeIf { it.isNotEmpty() } ?: fallbackColor
    }

    /** 移植 wordColoring.ts:237-260 `resolveTokenColorMap` */
    fun resolveTokenColorMap(
        tokens: List<WordColorToken>,
        ranges: List<WordColorRange>,
    ): Map<String, String> {
        val colors = LinkedHashMap<String, String>()
        var rangeIndex = 0

        for (token in tokens) {
            if (!token.timed) continue

            while (rangeIndex < ranges.size && ranges[rangeIndex].endOffset <= token.startOffset) {
                rangeIndex += 1
            }

            val range = ranges.getOrNull(rangeIndex)
            if (range != null && rangesOverlap(range, token)) {
                colors[token.key] = range.color
            }
        }

        return colors
    }
}
