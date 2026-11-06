package com.nasmusic.tv.visualizer.renderers.lyricsStage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 逐字形辉光层的数学门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/wordGlow.ts
 *
 * ⛔ 这层在 Folia 里被 `classic` 与 `partita` **共用** ⇒ 本仓也共用
 * （`GlowWordGlow`）⇒ 它的参数错一次就同时影响两个子模式。
 */
class GlowWordGlowTest {

    // ── 半径（wordGlow.ts:61-63）────────────────────────────────

    @Test
    fun `the glow radii follow the reveal mode`() {
        assertEquals(14f to 24f, GlowWordGlow.radiiFor("instant"))
        assertEquals(18f to 32f, GlowWordGlow.radiiFor("fast"))
        assertEquals(20f to 40f, GlowWordGlow.radiiFor("normal"))
    }

    // ── 淡出时长（wordGlow.ts:157）──────────────────────────────

    @Test
    fun `the passed fade duration follows the reveal mode`() {
        assertEquals(0.12, GlowWordGlow.fadeDuration("instant"), 1e-9)
        assertEquals(0.22, GlowWordGlow.fadeDuration("fast"), 1e-9)
        assertEquals(0.9, GlowWordGlow.fadeDuration("normal"), 1e-9)
    }

    // ── 峰值曲线 ────────────────────────────────────────────────

    @Test
    fun `a waiting word has no glow`() {
        assertEquals(
            0f,
            GlowWordGlow.peakForGrapheme(
                status = GlowWordStatus.WAITING,
                wordRevealMode = "normal",
                displayDuration = 1.0,
                graphemeCount = 1,
                graphemeStart = 0.0,
                graphemeEnd = 1.0,
                wordStartTime = 0.0,
                activeStart = 0.0,
                passedAt = 0.0,
                nowSec = 0.5,
            ),
            1e-6f,
        )
    }

    @Test
    fun `the instant mode peaks at 035 of its duration`() {
        // duration = min(displayDuration, 0.12)；times [0, 0.35, 1]；peaks [0, 1, 0]
        val duration = 0.12
        val peak = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "instant",
            displayDuration = 1.0,
            graphemeCount = 1,
            graphemeStart = 0.0,
            graphemeEnd = 1.0,
            wordStartTime = 0.0,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = duration * 0.35,
        )
        assertEquals(1f, peak, 1e-4f)
    }

    @Test
    fun `the instant mode ends at zero`() {
        val peak = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "instant",
            displayDuration = 1.0,
            graphemeCount = 1,
            graphemeStart = 0.0,
            graphemeEnd = 1.0,
            wordStartTime = 0.0,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = 1.0,
        )
        assertEquals(0f, peak, 1e-6f)
    }

    @Test
    fun `a single grapheme word keeps the glow lit`() {
        // normal 档单字形：duration = displayDuration；peaks [0, 1, 1] ⇒ 末尾仍是 1
        val peak = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 1,
            graphemeStart = 0.0,
            graphemeEnd = 1.0,
            wordStartTime = 0.0,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = 0.99,
        )
        assertEquals(1f, peak, 1e-3f)
    }

    @Test
    fun `a multi grapheme word sweeps the glow letter by letter`() {
        // normal 档多字形：duration = charDuration × 6；delay = charStart − wordStart
        val charDuration = 0.1
        val charStart = 0.5
        val wordStart = 0.0
        val duration = charDuration * 6.0

        // 延迟之前 ⇒ 没有辉光
        val before = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 3,
            graphemeStart = charStart,
            graphemeEnd = charStart + charDuration,
            wordStartTime = wordStart,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = charStart - 0.01,
        )
        assertEquals(0f, before, 1e-6f)

        // 延迟之后、峰值处 ⇒ 1
        val atPeak = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 3,
            graphemeStart = charStart,
            graphemeEnd = charStart + charDuration,
            wordStartTime = wordStart,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = charStart + duration * 0.3,
        )
        assertEquals(1f, atPeak, 1e-3f)

        // 扫过之后 ⇒ 0（peaks [0, 1, 0]）
        val after = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.ACTIVE,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 3,
            graphemeStart = charStart,
            graphemeEnd = charStart + charDuration,
            wordStartTime = wordStart,
            activeStart = 0.0,
            passedAt = 0.0,
            nowSec = charStart + duration * 1.5,
        )
        assertEquals(0f, after, 1e-6f)
    }

    @Test
    fun `a passed word fades the glow out from the peak it had`() {
        // 单字形持续曲线：peaks [0, 1, 1]、times [0, 0.9, 1]
        // ⇒ 曲线的峰值在 t = 0.9（displayDuration = 1.0 ⇒ 绝对时刻 0.9s）
        // 离场瞬间（t=0）⇒ 仍是 active 时的峰值
        val atPass = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.PASSED,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 1,
            graphemeStart = 0.0,
            graphemeEnd = 1.0,
            wordStartTime = 0.0,
            activeStart = 0.0,
            passedAt = 0.9,
            nowSec = 0.9,
        )
        assertTrue("离场瞬间峰值应接近 1，实际 $atPass", atPass > 0.9f)

        // 淡出结束 ⇒ 0
        val gone = GlowWordGlow.peakForGrapheme(
            status = GlowWordStatus.PASSED,
            wordRevealMode = "normal",
            displayDuration = 1.0,
            graphemeCount = 1,
            graphemeStart = 0.0,
            graphemeEnd = 1.0,
            wordStartTime = 0.0,
            activeStart = 0.0,
            passedAt = 0.9,
            nowSec = 0.9 + 0.9,
        )
        assertEquals(0f, gone, 1e-6f)
    }

    @Test
    fun `the peak never leaves the unit interval`() {
        for (mode in listOf("instant", "fast", "normal")) {
            for (t in 0..20) {
                val peak = GlowWordGlow.peakForGrapheme(
                    status = GlowWordStatus.ACTIVE,
                    wordRevealMode = mode,
                    displayDuration = 1.0,
                    graphemeCount = 2,
                    graphemeStart = 0.1,
                    graphemeEnd = 0.6,
                    wordStartTime = 0.0,
                    activeStart = 0.0,
                    passedAt = 0.0,
                    nowSec = t * 0.05,
                )
                assertTrue("mode=$mode t=$t peak=$peak", peak in 0f..1f)
            }
        }
    }

    // ── 缓动 ───────────────────────────────────────────────────

    @Test
    fun `the easings pin both endpoints`() {
        assertEquals(0f, GlowWordGlow.easeOut(0f), 1e-6f)
        assertEquals(1f, GlowWordGlow.easeOut(1f), 1e-6f)
        assertEquals(0f, GlowWordGlow.easeInOut(0f), 1e-6f)
        assertEquals(1f, GlowWordGlow.easeInOut(1f), 1e-6f)
    }

    @Test
    fun `the ease in out is symmetric around the midpoint`() {
        val a = GlowWordGlow.easeInOut(0.25f)
        val b = GlowWordGlow.easeInOut(0.75f)
        assertEquals(1f, a + b, 1e-4f)
    }

    // ── 辅助 ───────────────────────────────────────────────────

    @Test
    fun `the alpha conversion stays in range`() {
        assertEquals(255, GlowWordGlow.alphaOf(1f, 1f))
        assertEquals(0, GlowWordGlow.alphaOf(0f, 1f))
        // 0.5 × 255 = 127.5 ⇒ 截断成 127
        assertEquals(127, GlowWordGlow.alphaOf(0.5f, 1f))
    }

    @Test
    fun `the visibility threshold matches the port`() {
        assertTrue(GlowWordGlow.isVisible(0.01f))
        assertTrue(!GlowWordGlow.isVisible(0f))
        assertTrue(!GlowWordGlow.isVisible(Float.NaN))
    }
}
