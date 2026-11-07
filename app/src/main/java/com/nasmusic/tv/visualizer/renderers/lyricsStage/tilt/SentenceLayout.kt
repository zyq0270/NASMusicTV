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
 *
 * ⚠️ 2026-11-07：修「歌词舞台」**整帧黑屏**。
 *    起因：本文件曾把**字面**括号 `"["` / `"("` 填进 `PairedSymbolDef.open` / `close`
 *    （Folia 侧这两个字段是**正则**）⇒ `Regex("[")` 抛 `PatternSyntaxException`
 *    ⇒ 异常一路逃到 `draw()` ⇒ 宿主 catch 后**跳过整帧** ⇒ 歌词舞台**所有**子模式全黑。
 *    现在有三道闸：
 *    ① 正则元字符的配对（`[`/`]`、`(`/`)`）一律走 `openStr`/`closeStr`（**字面匹配**）；
 *    ② 正则一侧**只编译一次**（构造期存 `Regex`），不再逐行重新编译；
 *    ③ 编译失败**降级为「这条定义不匹配」**，绝不把异常抛进渲染路径。
 */
object SentenceLayout {

    /**
     * 移植 sentenceLayout.ts:13-26 `PairedSymbolDef`
     *
     * ⚠️ `open` / `close` 存的是**已编译的 `Regex`**（不是字符串！）⇒ 给它们赋一个
     *    字符串字面量**根本编译不过** ⇒ 从**类型**上杜绝「把字面括号当正则」的回归。
     *    `openStr` / `closeStr` 是**字面串**（`indexOf` 匹配）⇒ 正则元字符必须走这一侧。
     */
    internal data class PairedSymbolDef(
        val open: Regex? = null,
        val close: Regex? = null,
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

    // ── 全部只编译一次（⛔ 逐行 / 逐字路径上不得再 new Regex）────────────────
    //
    // ⚠️ Kotlin **不允许**在 `object` 声明里再挂 `companion object`
    //    （编译错误：Modifier 'companion' is not applicable inside 'standalone object'）
    //    ⇒ 这里用 `object` 的私有成员：它本身就是**进程级单例**，语义与 companion 常量一致。

    private val CJK_REGEX = Regex("[\\u4e00-\\u9fa5\\u3040-\\u30ff\\uac00-\\ud7af]")
    private val PUNCTUATION_REGEX = Regex("[，。；！？、…·\\.\\,\\;\\!\\?]+")
    private val LEADING_SPACES_REGEX = Regex("^\\s+")
    private val WESTERN_BLOCK_REGEX = Regex(
        "[a-zA-Z0-9]+(?:[a-zA-Z0-9'\\-]*[a-zA-Z0-9]+)?" +
            "(?:\\s+[a-zA-Z0-9]+(?:[a-zA-Z0-9'\\-]*[a-zA-Z0-9]+)?)+" +
            "[.,;:!?。，；：！？]?\\s*"
    )
    private val ASCII_ALNUM_SEQ_REGEX = Regex("[a-zA-Z0-9]+")
    private val ASCII_ALNUM_REGEX = Regex("[a-zA-Z0-9]")
    private val WHITESPACE_REGEX = Regex("\\s")
    private val ONLY_WHITESPACE_REGEX = Regex("^\\s+$")
    private val SPECIAL_CHAR_REGEX = Regex("[：:/／\\\\|｜~～]+")
    private val WORD_BOUNDARY_REGEX =
        Regex("[\\p{L}\\p{N}]+(?:['\\-][\\p{L}\\p{N}]+)*|[^\\s\\p{L}\\p{N}]")
    private val LETTER_OR_NUMBER_REGEX = Regex("[\\p{L}\\p{N}]")
    private val WESTERN_PHRASE_SPLIT_REGEX =
        Regex("(?<=\\w[.,;:!?。，；：！？])(?=\\s+\\w)")

    /**
     * 把一个「可能是坏模式」的模式编译成 `Regex`；**编译失败返回 null**（绝不抛异常）。
     *
     * ⛔ 这是「一个坏正则不能杀掉整帧」的最后一道闸：渲染路径上的每一次正则编译
     *    都必须走它 ⇒ 坏模式最多让**那一条定义不匹配**，而不是让整帧黑屏。
     */
    internal fun compilePatternOrNull(pattern: String): Regex? =
        runCatching { Regex(pattern) }.getOrNull()

    /** 正则配对（构造期编译一次；编译失败 ⇒ 该条定义退化成「不匹配」） */
    private fun pairedRegex(open: String, close: String): PairedSymbolDef =
        PairedSymbolDef(open = compilePatternOrNull(open), close = compilePatternOrNull(close))

    /** 字面配对（`[`/`]`、`(`/`)` 等正则元字符**必须**走这里） */
    private fun pairedLiteral(open: String, close: String): PairedSymbolDef =
        PairedSymbolDef(openStr = open, closeStr = close)

    /**
     * 括号/引号配对表（**构造期一次性构建**；渲染路径上不再重复编译）。
     *
     * ⚠️ 历史 bug：ASCII 的 `[`/`]` 与 `(`/`)` 曾被当成**正则**填进 `open`/`close`
     *    ⇒ `Regex("[")` 抛 `PatternSyntaxException` ⇒ 整帧黑屏。
     *    现在它们走 `pairedLiteral`（`indexOf` 字面匹配）。
     */
    private val PAIRED_SYMBOLS: List<PairedSymbolDef> = listOf(
        pairedRegex("「", "」"),
        pairedRegex("『", "』"),
        pairedRegex("《", "》"),
        pairedRegex("【", "】"),
        pairedRegex("｛", "｝"),
        pairedRegex("［", "］"),
        pairedLiteral("[", "]"),
        pairedRegex("（", "）"),
        pairedLiteral("(", ")"),
        pairedLiteral("\"", "\""),
        pairedLiteral("'", "'"),
    )

    /** 移植 sentenceLayout.ts:6 `hasCjkText` */
    private fun hasCjkText(text: String): Boolean = CJK_REGEX.containsMatchIn(text)

    /** 供门禁测试读取配对表（⛔ 只读快照；不得在生产代码里改它） */
    internal fun pairedSymbolDefinitions(): List<PairedSymbolDef> = PAIRED_SYMBOLS

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
        val matches = PUNCTUATION_REGEX.findAll(text).toList()

        if (matches.isEmpty()) return listOf(text)

        val result = mutableListOf<String>()
        var lastIndex = 0

        for (match in matches) {
            val punctStart = match.range.first
            val punctEnd = match.range.last + 1

            var trailingSpaces = ""
            val afterPunct = text.substring(punctEnd)
            val spaceMatch = LEADING_SPACES_REGEX.find(afterPunct)
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

        // 与 Folia 的西文块正则（/[a-zA-Z0-9]+(?:[a-zA-Z0-9'\-]*[a-zA-Z0-9]+)?(?:\s+…)+[.,;:!?。，；：！？]?\s*，JS 侧带 /g 全局标志）等价：
        // 「含空格的多词西文块」。
        var hasMultipleWordBlock = false
        for (match in WESTERN_BLOCK_REGEX.findAll(text)) {
            val wordCount = ASCII_ALNUM_SEQ_REGEX.findAll(match.value).count()
            if (wordCount > 1) {
                hasMultipleWordBlock = true
                break
            }
        }

        if (!hasMultipleWordBlock) return listOf(text)

        val parts = mutableListOf<String>()
        var lastIndex = 0

        for (match in WESTERN_BLOCK_REGEX.findAll(text)) {
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
        // ⛔ 原实现是 `Regex(Regex.escape(char))` ⇒ 每调一次就编译一个正则；
        //    `Regex.escape` 出来的模式与 `char` **字面等价** ⇒ 直接数出现次数即可（零分配）。
        var count = 0
        var index = text.indexOf(char)
        while (index != -1) {
            count++
            index = text.indexOf(char, index + char.length)
        }
        return count
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
        val result = extractOutermostPairs(text, PAIRED_SYMBOLS)
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
            // ⛔ 任一侧都拿不到匹配器（正则编译失败 ⇒ open/close 为 null）⇒ 跳过这条定义，
            //    绝不让异常逃出渲染路径。
            val openMatch = when {
                def.openStr != null -> findStringMatch(def.openStr, text, 0)
                def.open != null -> findRegexMatch(def.open, text, 0)
                else -> null
            } ?: continue

            val closeMatch = when {
                def.closeStr != null -> findStringMatch(def.closeStr, text, openMatch.second)
                def.close != null -> findRegexMatch(def.close, text, openMatch.second)
                else -> null
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

    /** 移植 sentenceLayout.ts:27-30 `findRegexMatch`
     *
     *  ⚠️ 传入的是**已编译**的 `Regex`（不再每次调用都重新编译一个正则）。
     *  ⛔ 匹配本身也包一层保护 ⇒ 任何异常都只是「这条定义不匹配」。
     */
    private fun findRegexMatch(regex: Regex, text: String, fromIndex: Int): Pair<Int, Int>? {
        val match = runCatching { regex.find(text, fromIndex) }.getOrNull() ?: return null
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
            val isWestern = ASCII_ALNUM_REGEX.matches(char.toString())
            val isSpace = WHITESPACE_REGEX.matches(char.toString())

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
            if (segments.isNotEmpty() && ONLY_WHITESPACE_REGEX.matches(currentSegment)) {
                segments[segments.lastIndex] = segments[segments.lastIndex] + currentSegment
            } else {
                segments.add(currentSegment)
            }
        }

        return if (segments.size > 1) segments else listOf(text)
    }

    /** 移植 sentenceLayout.ts:388-428 `splitBySpecialChars` */
    private fun splitBySpecialChars(text: String): List<String> {
        val parts = SPECIAL_CHAR_REGEX.split(text)

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
            val semanticCandidates = mutableListOf<Triple<Int, Int, Double>>() // index, splitPos, score

            for ((index, sentence) in candidates) {
                val wordPositions = mutableListOf<Pair<Int, Int>>()
                var offset = 0
                for (seg in WORD_BOUNDARY_REGEX.findAll(sentence)) {
                    if (seg.value.isNotEmpty() && LETTER_OR_NUMBER_REGEX.containsMatchIn(seg.value)) {
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
            val parts = text.split(WESTERN_PHRASE_SPLIT_REGEX)
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
