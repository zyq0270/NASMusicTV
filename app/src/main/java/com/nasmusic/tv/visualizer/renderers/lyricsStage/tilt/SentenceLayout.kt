package com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 移植自 Folia（AGPL-3.0）: src/utils/lyrics/sentenceLayout.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * 句子级拆分：按「标点 → 括号/引号 → 西文词 → CJK 空格 → 特殊字符」逐级降级，
 * 仍不够则 secondarySplit（词粒度），再多则 mergeSentences。
 */
object SentenceLayout {

    private val CJK_REGEX = Regex("[\\u4e00-\\u9fa5\\u3040-\\u30ff\\uac00-\\ud7af]")

    /** 移植 sentenceLayout.ts:6 `hasCjkText` */
    private fun hasCjkText(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

    /** 移植 sentenceLayout.ts:13-26 `PairedSymbolDef` */
    private data class PairedSymbolDef(
        val open: String? = null,
        val close: String? = null,
        val openStr: String? = null,
        val closeStr: String? = null,
    )

    /** 移植 sentenceLayout.ts:27-32 `OutermostMatch` */
    private data class OutermostMatch(
        val openStart: Int,
        val openEnd: Int,
        val closeStart: Int,
        val closeEnd: Int,
    )

    /** 移植 sentenceLayout.ts:49-85 `splitIntoSentences` */
    fun splitIntoSentences(text: String, targetCount: Int, timeSeed: Double? = null): List<String> {
        if (targetCount <= 1 && targetCount >= 0) return listOf(text)

        val maxLevel = when (targetCount) {
            -1 -> 1
            -2 -> 2
            -3 -> 3
            -4 -> 4
            -5 -> 5
            else -> 5
        }

        var sentences = listOf(text)

        for (level in 1..maxLevel) {
            if (sentences.size >= abs(targetCount) && targetCount > 0) break

            val newSentences = mutableListOf<String>()
            for (sentence in sentences) {
                newSentences.addAll(splitByLevel(sentence, level))
            }
            sentences = newSentences
        }

        var result = sentences

        if (targetCount > 0 && result.size < targetCount) {
            result = secondarySplit(result, targetCount, timeSeed)
        }

        if (result.size > 1 && result.size > targetCount && targetCount > 0) {
            result = mergeSentences(result, targetCount)
        }

        return result
    }

    /** 移植 sentenceLayout.ts:87-102 `splitByLevel` */
    private fun splitByLevel(text: String, level: Int): List<String> = when (level) {
        1 -> splitByPunctuation(text)
        2 -> splitByBracketsQuotes(text)
        3 -> splitByWesternWords(text)
        4 -> splitCJKBySpace(text)
        5 -> splitBySpecialChars(text)
        else -> listOf(text)
    }

    /** 移植 sentenceLayout.ts:104-145 `splitByPunctuation` */
    private fun splitByPunctuation(text: String): List<String> {
        val allPunctRegex = Regex("[，。；！？、…·\\.\\,\\;\\!\\?]+")
        val matches = allPunctRegex.findAll(text).toList()

        if (matches.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        var lastIndex = 0

        for (match in matches) {
            val punctStart = match.range.first
            val punctEnd = match.range.last + 1

            var trailingSpaces = ""
            val afterPunct = text.substring(punctEnd)
            val spaceMatch = Regex("^\\s+").find(afterPunct)
            if (spaceMatch != null) {
                trailingSpaces = spaceMatch.value
            }

            val beforePunct = text.substring(lastIndex, punctStart)
            val punctWithSpaces = match.value + trailingSpaces

            if (beforePunct.isNotEmpty()) {
                result.add(beforePunct + punctWithSpaces)
            } else if (result.isNotEmpty()) {
                result[result.lastIndex] = result[result.lastIndex] + punctWithSpaces
            } else {
                result.add(punctWithSpaces)
            }

            lastIndex = punctEnd + trailingSpaces.length
        }

        if (lastIndex < text.length) {
            result.add(text.substring(lastIndex))
        }

        return result.filter { it.isNotEmpty() }
    }

    /** 移植 sentenceLayout.ts:147-198 `splitByWesternWords` */
    private fun splitByWesternWords(text: String): List<String> {
        if (!hasCjkText(text)) return listOf(text)

        // 与 Folia 的 /[a-zA-Z0-9]+(?:[a-zA-Z0-9'\-]*[a-zA-Z0-9]+)?(?:\s+…)+[.,;:!?。，；：！？]?\s*/g 等价：
        // 「含空格的多词西文块」。
        val westernBlockRegex = Regex(
            "[a-zA-Z0-9]+(?:[a-zA-Z0-9'\\-]*[a-zA-Z0-9]+)?" +
                "(?:\\s+[a-zA-Z0-9]+(?:[a-zA-Z0-9'\\-]*[a-zA-Z0-9]+)?)+" +
                "[.,;:!?。，；：！？]?\\s*"
        )

        var hasMultipleWordBlock = false
        for (match in westernBlockRegex.findAll(text)) {
            val wordCount = Regex("[a-zA-Z0-9]+").findAll(match.value).count()
            if (wordCount > 1) {
                hasMultipleWordBlock = true
                break
            }
        }

        if (!hasMultipleWordBlock) return listOf(text)

        val parts = mutableListOf<String>()
        var lastIndex = 0

        for (match in westernBlockRegex.findAll(text)) {
            val blockStart = match.range.first
            val blockEnd = match.range.last + 1
            val beforeBlock = text.substring(lastIndex, blockStart)

            if (shouldSkipBoundary(text, beforeBlock)) {
                parts.add(beforeBlock + match.value)
            } else if (blockStart > lastIndex) {
                parts.add(beforeBlock)
                parts.add(match.value)
            } else {
                parts.add(match.value)
            }

            lastIndex = blockEnd
        }

        if (lastIndex < text.length) {
            parts.add(text.substring(lastIndex))
        }

        return if (parts.size > 1) parts.filter { it.isNotEmpty() } else listOf(text)
    }

    /** 移植 sentenceLayout.ts:202-211 `getGlobalCount` */
    private fun getGlobalCount(text: String, char: String): Int {
        val escaped = Regex.escape(char)
        return Regex(escaped).findAll(text).count()
    }

    /** 移植 sentenceLayout.ts:213-224 `shouldSkipBoundary` */
    private fun shouldSkipBoundary(fullText: String, beforeBlock: String): Boolean {
        if (beforeBlock.isEmpty()) return false

        val sepChar = beforeBlock[beforeBlock.length - 1]

        if (sepChar == '-') return true
        if (sepChar == ':' || sepChar == '：' || sepChar == '/' ||
            sepChar == '／' || sepChar == '|' || sepChar == '｜'
        ) {
            return getGlobalCount(fullText, sepChar.toString()) >= 2
        }

        return false
    }

    /** 移植 sentenceLayout.ts:239-256 `splitByBracketsQuotes` */
    private fun splitByBracketsQuotes(text: String): List<String> {
        val allPairedSymbols = listOf(
            PairedSymbolDef(open = "「", close = "」"),
            PairedSymbolDef(open = "『", close = "』"),
            PairedSymbolDef(open = "《", close = "》"),
            PairedSymbolDef(open = "【", close = "】"),
            PairedSymbolDef(open = "｛", close = "｝"),
            PairedSymbolDef(open = "［", close = "］"),
            PairedSymbolDef(open = "[", close = "]"),
            PairedSymbolDef(open = "（", close = "）"),
            PairedSymbolDef(open = "(", close = ")"),
            PairedSymbolDef(openStr = "\"", closeStr = "\""),
            PairedSymbolDef(openStr = "'", closeStr = "'"),
        )

        val result = extractOutermostPairs(text, allPairedSymbols)
        return if (result.size > 1) result else listOf(text)
    }

    /** 移植 sentenceLayout.ts:258-282 `extractOutermostPairs` */
    private fun extractOutermostPairs(text: String, defs: List<PairedSymbolDef>): List<String> {
        val best = findOutermostPair(text, defs) ?: return listOf(text)

        val before = text.substring(0, best.openStart)
        val pairedContent = text.substring(best.openStart, best.closeEnd)
        val remainder = text.substring(best.closeEnd)

        val result = mutableListOf<String>()

        if (before.isNotEmpty()) {
            result.addAll(extractOutermostPairs(before, defs))
        }

        result.add(pairedContent)

        if (remainder.isNotEmpty()) {
            result.addAll(extractOutermostPairs(remainder, defs))
        }

        return result.filter { it.isNotEmpty() }
    }

    /** 移植 sentenceLayout.ts:284-316 `findOutermostPair` */
    private fun findOutermostPair(text: String, defs: List<PairedSymbolDef>): OutermostMatch? {
        var best: OutermostMatch? = null

        for (def in defs) {
            val openMatch = if (def.openStr != null) {
                findStringMatch(def.openStr, text, 0)
            } else {
                findRegexMatch(def.open!!, text, 0)
            } ?: continue

            val closeMatch = if (def.closeStr != null) {
                findStringMatch(def.closeStr, text, openMatch.second)
            } else {
                findRegexMatch(def.close!!, text, openMatch.second)
            } ?: continue

            val candidate = OutermostMatch(
                openStart = openMatch.first,
                openEnd = openMatch.second,
                closeStart = closeMatch.first,
                closeEnd = closeMatch.second,
            )

            val current = best
            if (current == null || candidate.openStart < current.openStart) {
                best = candidate
            } else if (candidate.openStart == current.openStart && candidate.closeEnd > current.closeEnd) {
                best = candidate
            }
        }

        return best
    }

    /** 移植 sentenceLayout.ts:27-30 `findRegexMatch` */
    private fun findRegexMatch(regex: String, text: String, fromIndex: Int): Pair<Int, Int>? {
        val match = Regex(regex).find(text, fromIndex) ?: return null
        return match.range.first to (match.range.last + 1)
    }

    /** 移植 sentenceLayout.ts:33-36 `findStringMatch` */
    private fun findStringMatch(str: String, text: String, fromIndex: Int): Pair<Int, Int>? {
        val idx = text.indexOf(str, fromIndex)
        return if (idx == -1) null else idx to (idx + str.length)
    }

    /** 移植 sentenceLayout.ts:318-386 `splitCJKBySpace` */
    private fun splitCJKBySpace(text: String): List<String> {
        if (!hasCjkText(text)) return listOf(text)

        val segments = mutableListOf<String>()
        var currentSegment = ""
        var inCjkBlock = false

        for (char in text) {
            // ⚠️ Kotlin 的 `Char` **不是** `CharSequence` ⇒ 正则匹配要先转成 String。
            val isCjk = CJK_REGEX.matches(char.toString())
            val isWestern = Regex("[a-zA-Z0-9]").matches(char.toString())
            val isSpace = Regex("\\s").matches(char.toString())

            if (isCjk) {
                currentSegment += char
                inCjkBlock = true
            } else if (isWestern) {
                // 西文字符跟随当前块（不做边界分割）
                currentSegment += char
            } else if (isSpace) {
                val isFullWidthSpace = char == '\u3000'

                if (isFullWidthSpace && currentSegment.isNotEmpty()) {
                    currentSegment += char
                    segments.add(currentSegment)
                    currentSegment = ""
                    inCjkBlock = false
                } else if (inCjkBlock && currentSegment.isNotEmpty()) {
                    val lastChar = currentSegment[currentSegment.length - 1]
                    if (CJK_REGEX.matches(lastChar.toString())) {
                        currentSegment += char
                        segments.add(currentSegment)
                        currentSegment = ""
                        inCjkBlock = false
                    } else {
                        currentSegment += char
                    }
                } else if (currentSegment.isEmpty() && segments.isNotEmpty()) {
                    segments[segments.lastIndex] = segments[segments.lastIndex] + char
                } else {
                    currentSegment += char
                }
            } else {
                if (currentSegment.isEmpty() && segments.isNotEmpty()) {
                    segments[segments.lastIndex] = segments[segments.lastIndex] + char
                } else {
                    currentSegment += char
                }
            }
        }

        if (currentSegment.isNotEmpty()) {
            if (segments.isNotEmpty() && Regex("^\\s+$").matches(currentSegment)) {
                segments[segments.lastIndex] = segments[segments.lastIndex] + currentSegment
            } else {
                segments.add(currentSegment)
            }
        }

        return if (segments.size > 1) segments else listOf(text)
    }

    /** 移植 sentenceLayout.ts:388-428 `splitBySpecialChars` */
    private fun splitBySpecialChars(text: String): List<String> {
        val specialCharRegex = Regex("[：:/／\\\\|｜~～]+")
        val parts = specialCharRegex.split(text)

        if (parts.count { it.isNotEmpty() } <= 1) return listOf(text)

        val result = mutableListOf<String>()
        var lastIndex = 0

        for (part in parts) {
            val index = text.indexOf(part, lastIndex)
            if (index > lastIndex) {
                val specialChar = text.substring(lastIndex, index)
                if (result.isNotEmpty()) {
                    result[result.lastIndex] = result[result.lastIndex] + specialChar
                } else {
                    result.add(specialChar)
                }
            }

            if (part.isNotEmpty()) {
                result.add(part)
                lastIndex = index + part.length
            } else if (index != -1) {
                lastIndex = index
            }
        }

        if (lastIndex < text.length) {
            val tail = text.substring(lastIndex)
            if (tail.isNotEmpty() && result.isNotEmpty()) {
                result[result.lastIndex] = result[result.lastIndex] + tail
            } else if (tail.isNotEmpty()) {
                result.add(tail)
            }
        }

        return result.filter { it.isNotEmpty() }
    }

    /** 移植 sentenceLayout.ts:435-521 `secondarySplit`
     *
     *  Folia 用 Intl.Segmenter 的 word 粒度；Android 没有 ⇒ 用等价的词边界正则近似，
     *  再保留 Folia 的伪随机中点兜底。
     */
    private fun secondarySplit(
        sentences: List<String>,
        targetCount: Int,
        timeSeed: Double?,
    ): List<String> {
        val result = sentences.toMutableList()

        while (result.size < targetCount) {
            val candidates = result.withIndex().filter { it.value.length > 2 }
            if (candidates.isEmpty()) break

            // 词边界（近似 Intl.Segmenter 的 word 粒度：词 / 数字 / 标点各自成段）
            val wordBoundaryRegex = Regex("[\\p{L}\\p{N}]+(?:['\\-][\\p{L}\\p{N}]+)*|[^\\s\\p{L}\\p{N}]")
            val semanticCandidates = mutableListOf<Triple<Int, Int, Double>>() // index, splitPos, score

            for ((index, sentence) in candidates) {
                val wordPositions = mutableListOf<Pair<Int, Int>>()
                var offset = 0
                for (seg in wordBoundaryRegex.findAll(sentence)) {
                    if (seg.value.isNotEmpty() && Regex("[\\p{L}\\p{N}]").containsMatchIn(seg.value)) {
                        wordPositions.add(offset to (offset + seg.value.length))
                    }
                    offset += seg.value.length
                }

                if (wordPositions.size >= 2) {
                    val midChar = sentence.length / 2.0
                    var bestGapIdx = 1
                    var bestDist = Double.MAX_VALUE
                    for (g in 1 until wordPositions.size) {
                        val dist = abs(wordPositions[g].first - midChar)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestGapIdx = g
                        }
                    }
                    val splitPos = wordPositions[bestGapIdx].first
                    val balanceScore = 1.0 - abs(splitPos - midChar) / midChar
                    semanticCandidates.add(Triple(index, splitPos, balanceScore * 10 + wordPositions.size))
                }
            }

            if (semanticCandidates.isNotEmpty()) {
                val best = semanticCandidates.maxByOrNull { it.third }!!
                val firstHalf = result[best.first].substring(0, best.second)
                val secondHalf = result[best.first].substring(best.second)
                result[best.first] = firstHalf
                result.add(best.first + 1, secondHalf)
                continue
            }

            // Folia 的伪随机兜底（sentenceLayout.ts:499-516）
            val pseudoRandom = { seed: Double ->
                val x = Math.sin(seed) * 10000.0
                x - Math.floor(x)
            }
            val textHash = result.sumOf { s -> s.sumOf { it.code } }
            val seed = textHash + result.size + (timeSeed ?: 0.0)
            val randomIndex = (pseudoRandom(seed) * candidates.size).toInt()
            val selectedCandidate = candidates[randomIndex.coerceIn(0, candidates.lastIndex)]
            val midPoint = selectedCandidate.value.length / 2
            val firstHalf = selectedCandidate.value.substring(0, midPoint)
            val secondHalf = selectedCandidate.value.substring(midPoint)
            result[selectedCandidate.index] = firstHalf
            result.add(selectedCandidate.index + 1, secondHalf)
        }

        return result
    }

    /** 移植 sentenceLayout.ts:523-544 `mergeSentences` */
    private fun mergeSentences(sentences: List<String>, targetCount: Int): List<String> {
        val result = sentences.toMutableList()

        while (result.size > targetCount) {
            var bestMergeIndex = 0
            var shortestCombinedLength = Int.MAX_VALUE

            for (i in 0 until result.size - 1) {
                val combinedLength = result[i].length + result[i + 1].length
                if (combinedLength < shortestCombinedLength) {
                    shortestCombinedLength = combinedLength
                    bestMergeIndex = i
                }
            }

            val merged = result[bestMergeIndex] + result[bestMergeIndex + 1]
            result[bestMergeIndex] = merged
            result.removeAt(bestMergeIndex + 1)
        }

        return result
    }

    /** 移植 sentenceLayout.ts:226-237 `splitWesternPhrase`（独立西文短语的标点后拆分） */
    fun splitWesternPhrase(text: String): List<String> {
        if (!hasCjkText(text) && text.isNotEmpty()) {
            val parts = text.split(Regex("(?<=\\w[.,;:!?。，；：！？])(?=\\s+\\w)"))
            if (parts.size > 1) return parts.filter { it.isNotEmpty() }
        }
        return listOf(text)
    }

    /** 供其它模块复用的比例夹取 */
    internal fun clampTo(value: Int, min: Int, max: Int): Int = min(max(value, min), max)

    /** 供其它模块复用的向上取整 */
    internal fun ceilToInt(value: Double): Int = ceil(value).toInt()

    /** 供其它模块复用的四舍五入 */
    internal fun roundToInt(value: Double): Int = value.roundToInt()
}
