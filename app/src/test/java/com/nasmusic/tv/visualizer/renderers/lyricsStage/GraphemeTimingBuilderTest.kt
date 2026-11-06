package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— grapheme 时序构建与 renderHints 推导的门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/utils/lyrics/graphemeTiming.ts + renderHints.ts
 */
class GraphemeTimingBuilderTest {

    @Test
    fun `buildFor splits the word evenly across its span`() {
        val timings = GraphemeTimingBuilder.buildFor(
            LyricWord(text = "abc", startTime = 1.0, endTime = 2.0)
        )
        assertEquals(3, timings.size)
        assertEquals("a", timings[0].char)
        assertEquals("c", timings[2].char)
        assertEquals(1.0, timings[0].startTime, 1e-9)
        assertEquals(2.0, timings[2].endTime, 1e-9)
        // 均分：1/3 步长
        assertEquals(1.0 + 1.0 / 3.0, timings[1].startTime, 1e-9)
    }

    @Test
    fun `buildFor uses the syllable timings when present`() {
        val timings = GraphemeTimingBuilder.buildFor(
            LyricWord(
                text = "ab",
                startTime = 1.0,
                endTime = 2.0,
                syllables = listOf(
                    Syllable("a", 1.0, 1.25),
                    Syllable("b", 1.75, 2.0),
                ),
            )
        )
        assertEquals(2, timings.size)
        assertEquals(1.0, timings[0].startTime, 1e-9)
        assertEquals(1.25, timings[0].endTime, 1e-9)
        assertEquals(1.75, timings[1].startTime, 1e-9)
    }

    @Test
    fun `buildFor of an empty word is empty`() {
        assertTrue(
            GraphemeTimingBuilder.buildFor(LyricWord(text = "", startTime = 1.0, endTime = 2.0)).isEmpty()
        )
    }

    @Test
    fun `buildFor carries the word index`() {
        val timings = GraphemeTimingBuilder.buildFor(
            LyricWord(text = "a", startTime = 1.0, endTime = 2.0),
            wordIndex = 7,
        )
        assertEquals(7, timings.single().wordIndex)
    }

    // ── renderHints（renderHints.ts）─────────────────────────────

    @Test
    fun `a micro line gets the none transition and the instant reveal`() {
        val hints = LyricRenderHints.buildLineRenderHints(
            LyricLine(
                fullText = "啊",
                startTime = 1.0,
                endTime = 1.05,
                words = listOf(LyricWord("啊", 1.0, 1.05)),
            )
        )
        assertEquals("micro", hints.timingClass)
        assertEquals("none", hints.lineTransitionMode)
        assertEquals("instant", hints.wordRevealMode)
    }

    @Test
    fun `a short line gets the fast transition and the fast reveal`() {
        val hints = LyricRenderHints.buildLineRenderHints(
            LyricLine(
                fullText = "啊",
                startTime = 1.0,
                endTime = 1.15,
                words = listOf(LyricWord("啊", 1.0, 1.15)),
            )
        )
        assertEquals("short", hints.timingClass)
        assertEquals("fast", hints.lineTransitionMode)
        assertEquals("fast", hints.wordRevealMode)
    }

    @Test
    fun `a normal line gets the normal transition and the normal reveal`() {
        val hints = LyricRenderHints.buildLineRenderHints(
            LyricLine(
                fullText = "啊",
                startTime = 1.0,
                endTime = 5.0,
                words = listOf(LyricWord("啊", 1.0, 5.0)),
            )
        )
        assertEquals("normal", hints.timingClass)
        assertEquals("normal", hints.lineTransitionMode)
        assertEquals("normal", hints.wordRevealMode)
        assertEquals(4.0, hints.rawDuration, 1e-9)
    }

    @Test
    fun `the render end time is never earlier than the line end`() {
        val hints = LyricRenderHints.buildLineRenderHints(
            LyricLine(
                fullText = "啊",
                startTime = 1.0,
                endTime = 5.0,
                words = listOf(LyricWord("啊", 1.0, 5.0)),
            )
        )
        assertTrue(hints.renderEndTime >= 5.0)
    }

    @Test
    fun `an existing hints object wins over the derived one`() {
        val preset = LineRenderHints(
            rawDuration = 1.0,
            timingClass = "normal",
            renderEndTime = 42.0,
            lineTransitionMode = "fast",
            wordRevealMode = "fast",
        )
        val line = LyricLine(
            fullText = "啊",
            startTime = 1.0,
            endTime = 5.0,
            words = listOf(LyricWord("啊", 1.0, 5.0)),
            renderHints = preset,
        )
        assertEquals(42.0, LyricRenderHints.getLineRenderEndTime(line), 1e-9)
        assertEquals("fast", LyricRenderHints.getLineRenderHints(line)!!.lineTransitionMode)
    }
}
