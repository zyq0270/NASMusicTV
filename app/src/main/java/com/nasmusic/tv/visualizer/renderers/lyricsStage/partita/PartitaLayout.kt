package com.nasmusic.tv.visualizer.renderers.lyricsStage.partita

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/partita/partitaLayout.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * 分栏引导的布局：把当前行切成 chunk 排进若干列，算出每个词的偏移 / 旋转 / 缩放。
 */
object PartitaLayout {

    /** 移植 partitaLayout.ts:12-21 `WordLayoutConfig`
     *
     *  ⚠️ `marginBottom` / `alignSelf` 在 Folia 里是 **string**（CSS 值），不是数值。
     */
    data class WordLayoutConfig(
        val id: String,
        val x: Float,
        val y: Float,
        val rotate: Float,
        val scale: Float,
        val marginBottom: String,
        val alignSelf: String,
        val passedRotate: Float,
    )

    /** 移植 partitaLayout.ts:23-28 `LineLayoutConfig` */
    data class LineLayoutConfig(
        val perspective: Float,
        val justifyContent: String,
        val alignItems: String,
        val columnGap: String,
    )

    /** 移植 partitaLayout.ts:30-40 `PartitaColumn` */
    data class PartitaChunk(
        val chunkWords: List<LyricWord>,
        val displayWords: List<LyricWord>,
        val config: WordLayoutConfig,
        val order: Int,
        val rowIndex: Int,
    )

    /** 移植 partitaLayout.ts:30-40 `PartitaColumn` */
    data class PartitaColumn(
        val id: String,
        val words: List<PartitaChunk>,
    )

    /** 移植 partitaLayout.ts:42-46 `PartitaSequentialLayout` */
    data class PartitaSequentialLayout(
        val columns: List<PartitaColumn>,
        val totalGraphemes: Int,
        val lineConfig: LineLayoutConfig,
    )

    /** 移植 partitaLayout.ts:48-57 `EMPTY_PARTITA_LAYOUT` */
    val EMPTY_PARTITA_LAYOUT = PartitaSequentialLayout(
        columns = emptyList(),
        totalGraphemes = 0,
        lineConfig = LineLayoutConfig(
            perspective = 1000f,
            justifyContent = "justify-center",
            alignItems = "items-center",
            columnGap = "1.6rem",
        ),
    )

    /** 移植 partitaLayout.ts:61 `PARTITA_WORD_LAYOUT_STYLE` */
    const val PARTITA_WORD_LAYOUT_STYLE = "0.8rem"

    /** 移植 partitaLayout.ts:79-81 `clampPartitaStagger` */
    fun clampPartitaStagger(value: Float, fallback: Float): Float =
        if (value.isFinite()) min(max(value, 0f), 180f) else fallback

    /** 移植 partitaLayout.ts:97-125 `buildPartitaWordConfigs`
     *
     *  每个显示词自己的小随机偏移、旋转与漂移（chunk 的位置在容器上）。
     */
    fun buildPartitaWordConfigs(
        displayWords: List<LyricWord>,
        rowConfig: WordLayoutConfig,
        intensity: String,
    ): List<WordLayoutConfig> {
        val isCalm = intensity == "calm"
        val isChaotic = intensity == "chaotic"
        val baseSpread = if (isChaotic) 15f else if (isCalm) 0f else 6f
        val baseRotate = if (isChaotic) 8f else if (isCalm) 0f else 3f
        val firstStartTime = displayWords.firstOrNull()?.startTime ?: 0.0

        return displayWords.mapIndexed { idx, _ ->
            val wordSeed = firstStartTime + idx * 7.13
            fun random(offset: Int): Double {
                val x = Math.sin(wordSeed + offset) * 10000.0
                return x - Math.floor(x)
            }

            WordLayoutConfig(
                id = "${rowConfig.id}-w${idx}",
                x = ((random(1) - 0.5) * baseSpread * 2).toFloat(),
                y = ((random(2) - 0.5) * baseSpread * 2).toFloat(),
                rotate = ((random(3) - 0.5) * baseRotate * 2).toFloat(),
                scale = rowConfig.scale,
                marginBottom = "0",
                alignSelf = "auto",
                passedRotate = ((random(8) - 0.5) * 20).toFloat(),
            )
        }
    }

    /** 移植 partitaLayout.ts:131-137 `getTargetColumnCount` */
    fun getTargetColumnCount(totalGraphemes: Int, wordCount: Int): Int = when {
        wordCount <= 2 || totalGraphemes <= 5 -> 1
        totalGraphemes <= 10 -> 2
        totalGraphemes <= 16 -> 3
        totalGraphemes <= 24 -> 4
        else -> 5
    }

    /** 移植 partitaLayout.ts:139-248 `buildSequentialColumns` */
    fun buildSequentialColumns(
        line: LyricLine,
        windowHeight: Float,
        intensity: String,
        staggerMin: Float,
        staggerMax: Float,
    ): PartitaSequentialLayout {
        val isChaotic = intensity == "chaotic"
        val isCalm = intensity == "calm"
        val totalGraphemes = max(
            com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeMath
                .splitGraphemes(line.fullText.replace(Regex("\\s+"), "")).size,
            max(line.words.size, 1),
        )

        val columns = mutableListOf<PartitaColumn>()
        val currentWords = mutableListOf<PartitaChunk>()

        val baseRowHeight = 100f
        val availableHeight = windowHeight * 0.65f
        val targetRowCount = max(1, Math.floor((availableHeight / baseRowHeight).toDouble()).toInt())
        val actualRowCount = min(line.words.size, targetRowCount)

        var seed = line.startTime
        fun random(): Double {
            val x = Math.sin(seed++) * 10000.0
            return x - Math.floor(x)
        }

        // 移植 partitaLayout.ts:165-192 — 分块（长度刻意不均匀）
        val chunks = mutableListOf<List<LyricWord>>()
        var remainingUnits = line.words.size
        var remainingChunks = actualRowCount
        var unitIndex = 0

        for (c in 0 until actualRowCount) {
            val isLastChunk = c == actualRowCount - 1
            val avg = remainingUnits.toDouble() / remainingChunks

            var chunkLength = 1
            if (isLastChunk) {
                chunkLength = remainingUnits
            } else {
                val maxLen = ceil(avg * 1.5).toInt()
                val minLen = 1
                val randVal = random()
                chunkLength = max(minLen, min(maxLen, (avg + (randVal - 0.5) * avg).roundToInt()))
            }

            chunkLength = max(1, min(chunkLength, remainingUnits - (remainingChunks - 1)))

            chunks.add(line.words.subList(unitIndex, unitIndex + chunkLength))
            unitIndex += chunkLength
            remainingUnits -= chunkLength
            remainingChunks--
        }

        // 移植 partitaLayout.ts:194-229
        chunks.forEachIndexed { rowIndex, chunkWords ->
            if (chunkWords.isEmpty()) return@forEachIndexed

            val displayWords = chunkWords
            val mergedTextForConfig = chunkWords.joinToString("") { it.text.trim() }
            val graphemeCount = com.nasmusic.tv.visualizer.renderers.lyricsStage.fume.FumeMath
                .splitGraphemes(mergedTextForConfig.replace(Regex("\\s+"), "")).size
            val rowBias = rowIndex - (actualRowCount - 1) / 2f

            val isStaggeredLeft = rowIndex % 2 == 0

            // 交错是「分栏引导」的核心：块要显得有序列感，但仍读作同一行。
            // ⚠️ Kotlin **没有** `Double * Float` 运算 ⇒ 全程用 Double 算，最后再转回 Float。
            val staggerMagnitude =
                (staggerMin + random() * max(staggerMax - staggerMin, 0f).toDouble()).toFloat()
            val staggerX = if (isStaggeredLeft) -staggerMagnitude else staggerMagnitude

            val staggerScale = if (isCalm) 1f else 0.8f + random().toFloat() * 0.9f

            currentWords.add(
                PartitaChunk(
                    chunkWords = chunkWords,
                    displayWords = displayWords,
                    order = rowIndex,
                    rowIndex = rowIndex,
                    config = WordLayoutConfig(
                        id = "$rowIndex-${chunkWords[0].startTime}",
                        x = staggerX,
                        y = if (isCalm) 0f else rowBias * 2.5f,
                        rotate = if (isChaotic) (if (isStaggeredLeft) -3f else 3f) else 0f,
                        scale = (if (isChaotic) 1f + min(graphemeCount * 0.01f, 0.05f) else 1f) * staggerScale,
                        marginBottom = if (isChaotic) "0.4rem" else "0.6rem",
                        alignSelf = "center",
                        passedRotate = (if (rowIndex % 2 == 0) 1 else -1) * (if (isChaotic) 6f else 3f),
                    ),
                )
            )
        }

        if (currentWords.isNotEmpty()) {
            columns.add(PartitaColumn(id = "column-${line.startTime}-0", words = currentWords))
        }

        return PartitaSequentialLayout(
            columns = columns,
            totalGraphemes = totalGraphemes,
            lineConfig = LineLayoutConfig(
                perspective = if (intensity == "chaotic") 720f else 1000f,
                alignItems = "items-center",
                justifyContent = "justify-center",
                columnGap = "2rem",
            ),
        )
    }
}
