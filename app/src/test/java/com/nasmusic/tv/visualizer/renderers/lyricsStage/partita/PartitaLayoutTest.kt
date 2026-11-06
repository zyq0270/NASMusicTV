package com.nasmusic.tv.visualizer.renderers.lyricsStage.partita

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 分栏引导的布局门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/partita/partitaLayout.ts
 */
class PartitaLayoutTest {

    private fun line(text: String, wordCount: Int, start: Double = 0.0, span: Double = 10.0) =
        LyricLine(
            fullText = text,
            startTime = start,
            endTime = start + span,
            words = (0 until wordCount).map { i ->
                LyricWord(
                    text = "w$i",
                    startTime = start + span * i / wordCount,
                    endTime = start + span * (i + 1) / wordCount,
                )
            },
        )

    // ── buildPartitaWordConfigs（partitaLayout.ts:97-125）──────

    @Test
    fun `the word offsets follow the intensity spread table`() {
        val words = listOf(
            LyricWord("a", 0.0, 1.0),
            LyricWord("b", 1.0, 2.0),
        )
        val rowConfig = PartitaLayout.WordLayoutConfig(
            id = "row0", x = 0f, y = 0f, rotate = 0f, scale = 1f,
            marginBottom = "0", alignSelf = "auto", passedRotate = 0f,
        )

        // calm：spread = 0、rotate = 0 ⇒ 偏移与旋转全为 0
        val calm = PartitaLayout.buildPartitaWordConfigs(words, rowConfig, "calm")
        assertTrue(calm.all { it.x == 0f && it.y == 0f && it.rotate == 0f })

        // normal：spread = 6、rotate = 3
        val normal = PartitaLayout.buildPartitaWordConfigs(words, rowConfig, "normal")
        assertTrue(normal.all { kotlin.math.abs(it.x) <= 6f })
        assertTrue(normal.all { kotlin.math.abs(it.rotate) <= 3f })

        // chaotic：spread = 15、rotate = 8
        val chaotic = PartitaLayout.buildPartitaWordConfigs(words, rowConfig, "chaotic")
        assertTrue(chaotic.all { kotlin.math.abs(it.x) <= 15f })
        assertTrue(chaotic.all { kotlin.math.abs(it.rotate) <= 8f })
    }

    @Test
    fun `the word config ids are derived from the row config id`() {
        val words = listOf(LyricWord("a", 0.0, 1.0), LyricWord("b", 1.0, 2.0))
        val rowConfig = PartitaLayout.WordLayoutConfig(
            id = "row3", x = 0f, y = 0f, rotate = 0f, scale = 1f,
            marginBottom = "0", alignSelf = "auto", passedRotate = 0f,
        )
        val configs = PartitaLayout.buildPartitaWordConfigs(words, rowConfig, "normal")
        assertEquals(listOf("row3-w0", "row3-w1"), configs.map { it.id })
    }

    @Test
    fun `the word config carries the row scale and the string typed css fields`() {
        val words = listOf(LyricWord("a", 0.0, 1.0))
        val rowConfig = PartitaLayout.WordLayoutConfig(
            id = "row0", x = 0f, y = 0f, rotate = 0f, scale = 1.25f,
            marginBottom = "0", alignSelf = "auto", passedRotate = 0f,
        )
        val config = PartitaLayout.buildPartitaWordConfigs(words, rowConfig, "normal").single()
        assertEquals(1.25f, config.scale, 1e-6f)
        assertEquals("0", config.marginBottom)
        assertEquals("auto", config.alignSelf)
    }

    // ── buildSequentialColumns（partitaLayout.ts:139-248）──────

    @Test
    fun `the chunk count follows the available height`() {
        // windowHeight 1000 ⇒ availableHeight 650 ⇒ targetRowCount = 6
        val layout = PartitaLayout.buildSequentialColumns(
            line("w0 w1 w2 w3 w4 w5 w6 w7", 8),
            windowHeight = 1000f,
            intensity = "normal",
            staggerMin = 0f,
            staggerMax = 100f,
        )
        assertEquals(1, layout.columns.size)
        assertTrue(layout.columns[0].words.size in 1..8)
    }

    @Test
    fun `every word ends up in exactly one chunk`() {
        val layout = PartitaLayout.buildSequentialColumns(
            line("w0 w1 w2 w3 w4 w5 w6 w7 w8", 9),
            windowHeight = 1000f,
            intensity = "normal",
            staggerMin = 0f,
            staggerMax = 100f,
        )
        val all = layout.columns.flatMap { it.words }.flatMap { it.chunkWords }
        assertEquals(9, all.size)
        assertEquals(9, all.map { it.text }.distinct().size)
    }

    @Test
    fun `the chunks alternate their stagger direction`() {
        val layout = PartitaLayout.buildSequentialColumns(
            line("w0 w1 w2 w3 w4 w5 w6 w7 w8 w9 w10", 11),
            windowHeight = 1000f,
            intensity = "normal",
            staggerMin = 0f,
            staggerMax = 100f,
        )
        val chunks = layout.columns[0].words
        for (i in 1 until chunks.size) {
            val prevX = chunks[i - 1].config.x
            val curX = chunks[i].config.x
            // 偶数行向左、奇数行向右 ⇒ 相邻两行的 x 符号相反
            assertTrue("prev=$prevX cur=$curX", prevX * curX <= 0f)
        }
    }

    @Test
    fun `the calm intensity flattens the row offsets and the rotation`() {
        val layout = PartitaLayout.buildSequentialColumns(
            line("w0 w1 w2 w3 w4 w5", 6),
            windowHeight = 1000f,
            intensity = "calm",
            staggerMin = 0f,
            staggerMax = 100f,
        )
        assertTrue(layout.columns[0].words.all { it.config.y == 0f && it.config.rotate == 0f })
    }

    @Test
    fun `the total grapheme count is at least the word count`() {
        val layout = PartitaLayout.buildSequentialColumns(
            line("w0 w1 w2", 3),
            windowHeight = 1000f,
            intensity = "normal",
            staggerMin = 0f,
            staggerMax = 100f,
        )
        assertTrue(layout.totalGraphemes >= 3)
    }

    @Test
    fun `the column gap and the perspective follow the intensity`() {
        val normal = PartitaLayout.buildSequentialColumns(
            line("w0", 1), 1000f, "normal", 0f, 100f,
        )
        val chaotic = PartitaLayout.buildSequentialColumns(
            line("w0", 1), 1000f, "chaotic", 0f, 100f,
        )
        assertEquals(1000f, normal.lineConfig.perspective, 1e-6f)
        assertEquals(720f, chaotic.lineConfig.perspective, 1e-6f)
    }

    // ── getTargetColumnCount（partitaLayout.ts:131-137）────────

    @Test
    fun `the target column count follows the grapheme table`() {
        assertEquals(1, PartitaLayout.getTargetColumnCount(3, 3))
        assertEquals(1, PartitaLayout.getTargetColumnCount(10, 1))
        assertEquals(2, PartitaLayout.getTargetColumnCount(8, 8))
        assertEquals(3, PartitaLayout.getTargetColumnCount(14, 14))
        assertEquals(4, PartitaLayout.getTargetColumnCount(20, 20))
        assertEquals(5, PartitaLayout.getTargetColumnCount(30, 30))
    }
}
