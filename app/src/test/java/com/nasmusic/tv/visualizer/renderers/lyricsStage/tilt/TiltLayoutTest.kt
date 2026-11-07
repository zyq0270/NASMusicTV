package com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt

import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * E53「歌词舞台」—— 倾斜律动的布局与时序门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/tilt/VisualizerTilt.tsx
 */
class TiltLayoutTest {

    // ── determineLineCount（VisualizerTilt.tsx:20-28）──────────

    @Test
    fun `determineLineCount uses the logarithmic normalisation`() {
        // 公式：normalized = log(charCount + 4) / log(24)
        val expected = Math.log(20.0 + 4.0) / Math.log(24.0)
        val jitter = TiltLayout.seededRandom(1.0, 1) * 0.6 + 0.7
        val score = expected * jitter
        val expectedCount = when {
            score < 0.45 -> 1
            score < 1.05 -> 2
            score < 1.7 -> 3
            else -> 4
        }
        assertEquals(expectedCount, TiltLayout.determineLineCount(20, 1.0, 1.0))
    }

    @Test
    fun `determineLineCount never leaves the one to four range`() {
        for (chars in 0..400) {
            val count = TiltLayout.determineLineCount(chars, 3.7, 1.0)
            assertTrue("chars=$chars count=$count", count in 1..4)
        }
    }

    @Test
    fun `a very short line is one line and a very long one is more than one`() {
        // ⚠️ 行数由「对数归一 × jitter × 概率」决定 ⇒ 极短的行也可能是 2 行
        //    （normalized = log(5)/log(24) ≈ 0.506，jitter ∈ [0.7, 1.3] ⇒ score ∈ [0.35, 0.66]）
        val short = TiltLayout.determineLineCount(1, 2.0, 1.0)
        assertTrue("short=$short", short in 1..2)
        // 长行 ⇒ 至少 2 行
        assertTrue(TiltLayout.determineLineCount(300, 2.0, 1.0) >= 2)
    }

    @Test
    fun `the split probability scales the score down`() {
        val full = TiltLayout.determineLineCount(40, 5.0, 1.0)
        val half = TiltLayout.determineLineCount(40, 5.0, 0.5)
        assertTrue("full=$full half=$half", half <= full)
    }

    // ── seededRandom（VisualizerTilt.tsx:206-209）──────────────

    @Test
    fun `seededRandom uses the seed times one thousand formula`() {
        val expected = { seed: Double, offset: Int ->
            val x = sin(seed * 1000 + offset) * 10000.0
            x - Math.floor(x)
        }
        for (seed in listOf(0.0, 1.0, 12.5, -3.25)) {
            for (offset in listOf(1, 2, 8, 100, 300)) {
                assertEquals(
                    expected(seed, offset),
                    TiltLayout.seededRandom(seed, offset),
                    1e-12,
                )
            }
        }
    }

    @Test
    fun `seededRandom always lands in the unit interval`() {
        for (seed in listOf(0.0, 1.0, 7.5, 100.25)) {
            for (offset in 1..8) {
                val r = TiltLayout.seededRandom(seed, offset)
                assertTrue("r=$r", r >= 0.0 && r < 1.0)
            }
        }
    }

    // ── getCharPulseIntensity（VisualizerTilt.tsx:180-198）──────

    @Test
    fun `the pulse is zero before the character starts`() {
        val timing = TiltLayout.CharTiming(0, startTime = 10.0, endTime = 10.5)
        assertEquals(0f, TiltLayout.getCharPulseIntensity(9.9, timing), 1e-6f)
    }

    @Test
    fun `the pulse peaks in the middle of the character`() {
        val timing = TiltLayout.CharTiming(0, startTime = 10.0, endTime = 10.5)
        val peak = TiltLayout.getCharPulseIntensity(10.25, timing)
        assertEquals(1f, peak, 1e-5f)
    }

    @Test
    fun `the pulse falls back to a quarter afterglow`() {
        val timing = TiltLayout.CharTiming(0, startTime = 10.0, endTime = 10.5)
        // 脉冲时长被钳在 [0.2, 0.9] ⇒ 这里 duration = 0.5，afterglowRamp = 0.6
        assertEquals(0.25f, TiltLayout.getCharPulseIntensity(20.0, timing), 1e-6f)
    }

    @Test
    fun `the pulse duration is clamped to the 02 and 09 window`() {
        // 极短字符（原始 0.01s）：duration 被抬到下限 0.2 ⇒ 中点在 0.1s ⇒ 峰值 1
        val short = TiltLayout.CharTiming(0, startTime = 10.0, endTime = 10.01)
        assertEquals(1f, TiltLayout.getCharPulseIntensity(10.1, short), 1e-5f)

        // 极长字符（原始 10s）：duration 被压到上限 0.9 ⇒ 中点在 0.45s ⇒ 峰值 1
        val long = TiltLayout.CharTiming(0, startTime = 10.0, endTime = 20.0)
        assertEquals(1f, TiltLayout.getCharPulseIntensity(10.45, long), 1e-5f)

        // ⛔ 钳上限的证据：若不钳，峰值会落在原始中点 5s 处；
        //    钳到 0.9 之后 1s 处就已经进入余晖衰减 ⇒ 远小于 1。
        val afterClamp = TiltLayout.getCharPulseIntensity(11.0, long)
        assertTrue("afterClamp=$afterClamp", afterClamp < 1f)
    }

    // ── findSegmentWordRange（VisualizerTilt.tsx:67-101）────────

    @Test
    fun `findSegmentWordRange maps a segment offset back onto the words`() {
        val words = listOf(
            LyricWord("你", 0.0, 1.0),
            LyricWord("好", 1.0, 2.0),
            LyricWord("吗", 2.0, 3.0),
        )
        val full = "你好吗"
        val (start, end) = TiltLayout.findSegmentWordRange(1, "好", full, words)
        assertEquals(1, start)
        assertEquals(2, end)
    }

    @Test
    fun `findSegmentWordRange of an out of range offset falls back to the whole line`() {
        val words = listOf(LyricWord("你", 0.0, 1.0))
        val (start, end) = TiltLayout.findSegmentWordRange(99, "x", "你", words)
        assertEquals(0, start)
        assertEquals(1, end)
    }

    /**
     * 门禁：**每一行都必须至少有一段是倾斜段**。
     *
     * ⛔ 上游的挑选没有兜底（`finalTiltIndex = -1`）⇒ 候选为空时整行没有倾斜段
     * ⇒ 那行既不上下错落、也不变蓝 ⇒ 看起来就是**一行常规白字**
     * （真机反馈，2026-11-07）。本仓改为**兜底保证每行至少一段倾斜**。
     */
    @Test
    fun `every line has at least one tilted segment`() {
        val texts = listOf(
            "曾经真的以为人生就这样了",
            "是命运的安排也好",
            "鬼迷心窍",
            "我曾经跨过山和大海",
            "编曲：Jenny Chin",
            "这是一句很长很长的歌词用来触发多段拆分的场景看看效果对不对",
        )
        var checked = 0
        for (text in texts) {
            for (seed in 0L..40L) {
                val result = TiltLayout.buildTiltLayout(
                    fullText = text,
                    lineSeed = seed.toDouble(),
                    splitProbability = 0.75,
                    tiltStyleProbability = 0.35,
                    fontScale = 1f,
                    availableWidth = 1200f,
                    measure = { s, _ -> s.length * 40f },
                )
                assertTrue(
                    "「$text」 seed=$seed 没有任何倾斜段 ⇒ 该行不会错落、也不会变蓝",
                    result.segments.any { it.isTilt },
                )
                checked++
            }
        }
        assertTrue("checked=$checked", checked > 100)
    }

    /**
     * 负向自证：**上游那套选择逻辑下，确实有很多行没有倾斜段**
     * ⇒ 否则上面那条门禁就没有区分力（会阻止回退到无兜底的状态）。
     */
    @Test
    fun `negative self proof - upstream logic would leave many lines without a tilted segment`() {
        var linesWithoutTilt = 0
        var total = 0
        for (seed in 0L..200L) {
            val segCount = TiltLayout.determineLineCount(13, seed.toDouble(), 0.75)
            var candidates = 0
            for (i in 0 until segCount) {
                if (TiltLayout.seededRandom(seed.toDouble(), 100 + i) < 0.35) candidates++
            }
            total++
            if (candidates == 0) linesWithoutTilt++
        }
        assertTrue(
            "上游逻辑下无倾斜段的行数应很多（实测 $linesWithoutTilt/$total），" +
                "否则上面那条门禁没有区分力",
            linesWithoutTilt > 20,
        )
    }
}
