package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 词状态机与时序的门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/glowWordTiming.ts + GlowWord.tsx
 */
class GlowWordCoreTest {

    private fun word(
        text: String = "词",
        start: Double = 10.0,
        end: Double = 11.0,
        syllables: List<Syllable>? = null,
    ) = LyricWord(text = text, startTime = start, endTime = end, syllables = syllables)

    private fun line(
        start: Double = 10.0,
        end: Double = 14.0,
        words: List<LyricWord> = listOf(word()),
        hints: LineRenderHints? = null,
    ) = LyricLine(
        fullText = "词",
        startTime = start,
        endTime = end,
        words = words,
        renderHints = hints,
    )

    private fun profile(
        wordRevealMode: String,
        lineTransitionMode: String = "normal",
        lineRenderEndTime: Double = 14.0,
    ) = GlowWordProfile(
        renderHints = null,
        lineRenderEndTime = lineRenderEndTime,
        lineTransitionMode = lineTransitionMode,
        wordRevealMode = wordRevealMode,
        wordLookahead = when (wordRevealMode) {
            "instant" -> 0.03
            "fast" -> 0.08
            else -> 0.15
        },
    )

    // ── statusOf（GlowWord.tsx:53-61）──────────────────────────

    @Test
    fun `statusOf waiting before the lookahead window`() {
        val p = profile("normal")
        val w = word(start = 10.0, end = 11.0)
        // 10.0 - 0.15 = 9.85 ⇒ 9.0 早于窗口 ⇒ WAITING
        assertEquals(GlowWordStatus.WAITING, GlowWordCore.statusOf(w, 9.0, p))
    }

    @Test
    fun `statusOf active inside the lookahead window`() {
        val p = profile("normal")
        val w = word(start = 10.0, end = 11.0)
        // 9.9 ≥ 10.0 - 0.15 ⇒ 已进入窗口
        assertEquals(GlowWordStatus.ACTIVE, GlowWordCore.statusOf(w, 9.9, p))
        assertEquals(GlowWordStatus.ACTIVE, GlowWordCore.statusOf(w, 10.5, p))
    }

    @Test
    fun `statusOf passed after the active end`() {
        val p = profile("normal")
        val w = word(start = 10.0, end = 11.0)
        assertEquals(GlowWordStatus.PASSED, GlowWordCore.statusOf(w, 11.1, p))
    }

    @Test
    fun `statusOf active at the exact active end boundary`() {
        val p = profile("normal")
        val w = word(start = 10.0, end = 11.0)
        assertEquals(GlowWordStatus.ACTIVE, GlowWordCore.statusOf(w, 11.0, p))
    }

    // ── activeEndTime（glowWordTiming.ts:35-45）────────────────

    @Test
    fun `activeEndTime instant uses the line render end`() {
        val p = profile("instant", lineRenderEndTime = 14.0)
        assertEquals(14.0, GlowWordCore.activeEndTime(word(start = 10.0, end = 11.0), p), 1e-9)
    }

    @Test
    fun `activeEndTime fast clamps to at least 012s after the word start`() {
        val p = profile("fast", lineRenderEndTime = 14.0)
        // word 10.0→10.05 ⇒ max(end, start+0.12) = 10.12
        assertEquals(
            10.12,
            GlowWordCore.activeEndTime(word(start = 10.0, end = 10.05), p),
            1e-9,
        )
    }

    @Test
    fun `activeEndTime fast is capped by the line render end`() {
        val p = profile("fast", lineRenderEndTime = 10.05)
        assertEquals(
            10.05,
            GlowWordCore.activeEndTime(word(start = 10.0, end = 11.0), p),
            1e-9,
        )
    }

    @Test
    fun `activeEndTime normal uses the word end`() {
        val p = profile("normal")
        assertEquals(
            11.0,
            GlowWordCore.activeEndTime(word(start = 10.0, end = 11.0), p),
            1e-9,
        )
    }

    // ── displayDuration（glowWordTiming.ts:47-56）──────────────

    @Test
    fun `displayDuration respects the per-reveal-mode minimum`() {
        // normal：最小 0.1
        assertTrue(
            GlowWordCore.displayDuration(word(start = 10.0, end = 10.0), profile("normal")) >= 0.1
        )
        // fast：最小 0.12
        assertTrue(
            GlowWordCore.displayDuration(word(start = 10.0, end = 10.0), profile("fast")) >= 0.12
        )
        // instant：最小 0.08
        assertTrue(
            GlowWordCore.displayDuration(word(start = 10.0, end = 10.0), profile("instant")) >= 0.08
        )
    }

    @Test
    fun `displayDuration is the active span when it is longer than the minimum`() {
        val p = profile("normal")
        assertEquals(
            1.5,
            GlowWordCore.displayDuration(word(start = 10.0, end = 11.5), p),
            1e-9,
        )
    }

    // ── resolveProfile（glowWordTiming.ts:16-33）────────────────

    @Test
    fun `resolveProfile maps the reveal mode to the lookahead`() {
        // 无 hints ⇒ 缺省 "normal" ⇒ 0.15（不是 0.03）
        assertEquals(0.15, GlowWordCore.resolveProfile(line())!!.wordLookahead, 1e-9)
        assertEquals(
            0.03,
            GlowWordCore.resolveProfile(line(hints = hints("instant")))!!.wordLookahead,
            1e-9,
        )
        assertEquals(
            0.08,
            GlowWordCore.resolveProfile(line(hints = hints("fast")))!!.wordLookahead,
            1e-9,
        )
        assertEquals(
            0.15,
            GlowWordCore.resolveProfile(line(hints = hints("normal")))!!.wordLookahead,
            1e-9,
        )
    }

    @Test
    fun `resolveProfile of a null line is null`() {
        assertNull(GlowWordCore.resolveProfile(null))
    }

    @Test
    fun `resolveProfile reads the transition mode and the line render end from the hints`() {
        val p = GlowWordCore.resolveProfile(line(hints = hints("fast", "none")))!!
        assertNotNull(p)
        assertEquals("none", p.lineTransitionMode)
        assertEquals("fast", p.wordRevealMode)
        assertEquals(99.0, p.lineRenderEndTime, 1e-9)
    }

    // ── lineContainerMotion（glowWordTiming.ts:58-91）───────────

    @Test
    fun `line container motion none mode starts fully visible`() {
        val m = GlowWordCore.lineContainerMotion(profile("instant", "none"))
        assertEquals(1f, m.initialOpacity, 1e-6f)
        assertEquals(1f, m.initialScale, 1e-6f)
        assertEquals(0f, m.initialBlurPx, 1e-6f)
        assertEquals(0.12, m.exitDurationSec, 1e-9)
    }

    @Test
    fun `line container motion fast mode starts at opacity 035 scale 096 blur 4`() {
        val m = GlowWordCore.lineContainerMotion(profile("fast", "fast"))
        assertEquals(0.35f, m.initialOpacity, 1e-6f)
        assertEquals(0.96f, m.initialScale, 1e-6f)
        assertEquals(4f, m.initialBlurPx, 1e-6f)
        assertEquals(1.04f, m.exitScale, 1e-6f)
        assertEquals(10f, m.exitBlurPx, 1e-6f)
    }

    @Test
    fun `line container motion normal mode starts at opacity 0 scale 09 blur 10`() {
        val m = GlowWordCore.lineContainerMotion(profile("normal", "normal"))
        assertEquals(0f, m.initialOpacity, 1e-6f)
        assertEquals(0.9f, m.initialScale, 1e-6f)
        assertEquals(10f, m.initialBlurPx, 1e-6f)
        assertEquals(1.1f, m.exitScale, 1e-6f)
        assertEquals(20f, m.exitBlurPx, 1e-6f)
        assertEquals(0.3, m.exitDurationSec, 1e-9)
    }

    private fun hints(reveal: String, transition: String = "normal") = LineRenderHints(
        rawDuration = 4.0,
        timingClass = "normal",
        renderEndTime = 99.0,
        lineTransitionMode = transition,
        wordRevealMode = reveal,
    )
}
