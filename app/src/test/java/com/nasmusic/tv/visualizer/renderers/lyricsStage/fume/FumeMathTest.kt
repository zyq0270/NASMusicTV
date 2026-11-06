package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 全屏纵览（fume）的数学工具门禁测试。
 *
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/fume/fumeMath.ts
 */
class FumeMathTest {

    // ── clamp / mix ────────────────────────────────────────────

    @Test
    fun `clamp keeps the value inside the range`() {
        assertEquals(5f, FumeMath.clamp(5f, 0f, 10f), 1e-6f)
        assertEquals(0f, FumeMath.clamp(-3f, 0f, 10f), 1e-6f)
        assertEquals(10f, FumeMath.clamp(42f, 0f, 10f), 1e-6f)
    }

    @Test
    fun `mix interpolates linearly`() {
        assertEquals(0f, FumeMath.mix(0f, 10f, 0f), 1e-6f)
        assertEquals(10f, FumeMath.mix(0f, 10f, 1f), 1e-6f)
        assertEquals(5f, FumeMath.mix(0f, 10f, 0.5f), 1e-6f)
    }

    // ── 缓动 ────────────────────────────────────────────────────

    @Test
    fun `easeOutCubic pins both endpoints`() {
        assertEquals(0f, FumeMath.easeOutCubic(0f), 1e-6f)
        assertEquals(1f, FumeMath.easeOutCubic(1f), 1e-6f)
    }

    @Test
    fun `easeInCubic pins both endpoints`() {
        assertEquals(0f, FumeMath.easeInCubic(0f), 1e-6f)
        assertEquals(1f, FumeMath.easeInCubic(1f), 1e-6f)
    }

    @Test
    fun `easeInOutCubic is symmetric around the midpoint`() {
        assertEquals(0f, FumeMath.easeInOutCubic(0f), 1e-6f)
        assertEquals(1f, FumeMath.easeInOutCubic(1f), 1e-6f)
        assertEquals(0.5f, FumeMath.easeInOutCubic(0.5f), 1e-6f)
        val a = FumeMath.easeInOutCubic(0.25f)
        val b = FumeMath.easeInOutCubic(0.75f)
        assertEquals(1f, a + b, 1e-5f)
    }

    @Test
    fun `easeOutCubic clamps out of range inputs`() {
        assertEquals(0f, FumeMath.easeOutCubic(-1f), 1e-6f)
        assertEquals(1f, FumeMath.easeOutCubic(2f), 1e-6f)
    }

    // ── quadraticBezier ────────────────────────────────────────

    @Test
    fun `the quadratic bezier hits both endpoints`() {
        assertEquals(0f, FumeMath.quadraticBezier(0f, 5f, 10f, 0f), 1e-6f)
        assertEquals(10f, FumeMath.quadraticBezier(0f, 5f, 10f, 1f), 1e-6f)
    }

    @Test
    fun `the quadratic bezier midpoint follows the control point`() {
        // t = 0.5 ⇒ 0.25*from + 0.5*control + 0.25*to
        assertEquals(
            0.25f * 0f + 0.5f * 8f + 0.25f * 0f,
            FumeMath.quadraticBezier(0f, 8f, 0f, 0.5f),
            1e-6f,
        )
    }

    // ── resolveDelayedGlowEnvelope ──────────────────────────────

    @Test
    fun `the glow envelope peaks at the peak progress`() {
        val peak = FumeMath.resolveDelayedGlowEnvelope(0.8f, 0.8f)
        assertEquals(1f, peak, 1e-5f)
    }

    @Test
    fun `the glow envelope starts at zero and ends at zero`() {
        assertEquals(0f, FumeMath.resolveDelayedGlowEnvelope(0f, 0.8f), 1e-5f)
        assertEquals(0f, FumeMath.resolveDelayedGlowEnvelope(1f, 0.8f), 1e-5f)
    }

    @Test
    fun `the glow envelope never exceeds one`() {
        for (i in 0..100) {
            val v = FumeMath.resolveDelayedGlowEnvelope(i / 100f, 0.8f)
            assertTrue("v=$v", v in 0f..1.0001f)
        }
    }

    // ── isCJK ───────────────────────────────────────────────────

    @Test
    fun `isCJK detects the cjk ranges`() {
        assertTrue(FumeMath.isCJK("你"))
        assertTrue(FumeMath.isCJK("こんにちは"))
        assertTrue(FumeMath.isCJK("안녕"))
        assertFalse(FumeMath.isCJK("hello"))
        assertFalse(FumeMath.isCJK(""))
    }

    // ── hashString / seeded ─────────────────────────────────────

    @Test
    fun `hashString is the FNV one a hash`() {
        // ⚠️ Folia 的 `hash >>> 0` 是**无符号** 32 位值；Kotlin 的 Int 是有符号的
        //    ⇒ 断言走无符号变体。
        assertEquals(2166136261L, FumeMath.hashStringUnsigned(""))
        // 稳定：同输入同输出
        assertEquals(FumeMath.hashString("abc"), FumeMath.hashString("abc"))
        // 不同输入不同输出
        assertTrue(FumeMath.hashString("abc") != FumeMath.hashString("abd"))
        // 结果落在无符号 32 位区间
        val h = FumeMath.hashStringUnsigned("歌词舞台")
        assertTrue("h=$h", h in 0L..0xFFFFFFFFL)
    }

    @Test
    fun `seeded follows the folia formula on the unsigned hash`() {
        for (s in listOf("", "a", "abc", "歌词舞台", "0123456789")) {
            val expected = (FumeMath.hashStringUnsigned(s) % 10000L) / 10000.0
            assertEquals(expected, FumeMath.seeded(s), 1e-12)
        }
    }

    @Test
    fun `seeded lands in the unit interval`() {
        for (s in listOf("", "a", "abc", "歌词舞台", "0123456789")) {
            val v = FumeMath.seeded(s)
            assertTrue("s=$s v=$v", v >= 0.0 && v < 1.0)
        }
    }

    @Test
    fun `seeded is stable for the same seed`() {
        assertEquals(FumeMath.seeded("abc"), FumeMath.seeded("abc"), 1e-12)
    }

    // ── splitGraphemes ──────────────────────────────────────────

    @Test
    fun `splitGraphemes breaks the text into grapheme clusters`() {
        assertEquals(listOf("a", "b", "c"), FumeMath.splitGraphemes("abc"))
        assertEquals(listOf("你", "好"), FumeMath.splitGraphemes("你好"))
        assertTrue(FumeMath.splitGraphemes("").isEmpty())
    }
}
