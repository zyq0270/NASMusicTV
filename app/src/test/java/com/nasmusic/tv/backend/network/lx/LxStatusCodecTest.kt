package com.nasmusic.tv.backend.network.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `lastStatus` 紧凑编解码（方案 §8.1）。
 *
 * 落盘的是「下次进设置页先显示什么」：不带 URL（直链带签名，落盘既无意义也易泄漏），
 * 坏 JSON 一律判 null（⛔ 坏数据不该让设置页打不开）。
 */
@RunWith(RobolectricTestRunner::class)
class LxStatusCodecTest {

    private fun probe(
        source: String,
        verdict: LxPlatformVerdict,
        reason: LxFailReason? = null,
        quality: String? = null,
        container: LxContainer? = null,
        mismatch: Boolean = false,
    ) = LxPlatformProbe(
        source = source,
        declaredQualities = listOf("320k"),
        attempts = emptyList(),
        verdict = verdict,
        bestQuality = quality,
        actualContainer = container,
        qualityMismatch = mismatch,
        reason = reason,
    )

    private fun report(
        l1: LxL1 = LxL1.Loaded(listOf("kg", "wy"), "1.2.3"),
        platforms: List<LxPlatformProbe> = listOf(
            probe("kg", LxPlatformVerdict.PLAYABLE, quality = "flac", container = LxContainer.FLAC),
            probe("wy", LxPlatformVerdict.DEAD, reason = LxFailReason.HTTP_404),
        ),
        truncated: Boolean = false,
    ) = LxProbeReport(
        scriptId = "s1",
        scriptName = "夹具脚本",
        version = "1.2.3",
        l1 = l1,
        platforms = platforms,
        depth = LxProbeDepth.QUICK,
        startedAt = 1_700_000_000_000,
        elapsedMs = 1234,
        truncated = truncated,
    )

    @Test
    fun roundTripPreservesVerdictsAndMetadata() {
        val decoded = LxStatusCodec.decode(LxStatusCodec.encode(report()))!!
        assertEquals(1_700_000_000_000, decoded.ts)
        assertTrue(decoded.l1Ok)
        assertEquals("1.2.3", decoded.version)
        assertEquals("夹具脚本", decoded.name)
        assertFalse(decoded.truncated)
        assertEquals(setOf("kg", "wy"), decoded.platforms.keys)

        val kg = decoded.platforms["kg"]!!
        assertEquals(LxPlatformVerdict.PLAYABLE, kg.verdict)
        assertEquals("flac", kg.quality)
        assertEquals(LxContainer.FLAC, kg.container)

        val wy = decoded.platforms["wy"]!!
        assertEquals(LxPlatformVerdict.DEAD, wy.verdict)
        assertEquals(LxFailReason.HTTP_404, wy.reason)
    }

    @Test
    fun l1FailureKeepsReasonAndDetail() {
        val json = LxStatusCodec.encode(
            report(l1 = LxL1.Failed(LxFailReason.L1_TIMEOUT, "超时 10s"), platforms = emptyList())
        )
        val decoded = LxStatusCodec.decode(json)!!
        assertFalse(decoded.l1Ok)
        assertEquals(LxFailReason.L1_TIMEOUT, decoded.l1Reason)
        assertEquals("超时 10s", decoded.l1Detail)
        assertTrue(decoded.platforms.isEmpty())
    }

    @Test
    fun longL1DetailIsTruncatedTo200Chars() {
        val json = LxStatusCodec.encode(
            report(l1 = LxL1.Failed(LxFailReason.L1_SCRIPT_ERROR, "x".repeat(500)))
        )
        assertEquals(200, LxStatusCodec.decode(json)!!.l1Detail!!.length)
    }

    @Test
    fun skippedByDepthAndUntestedSurviveRoundTrip() {
        val json = LxStatusCodec.encode(
            report(
                platforms = listOf(
                    probe("kg", LxPlatformVerdict.PLAYABLE, quality = "320k"),
                    probe("kw", LxPlatformVerdict.UNTESTED, reason = LxFailReason.SKIPPED_BY_DEPTH),
                    probe("mg", LxPlatformVerdict.UNTESTED, reason = LxFailReason.NO_ANCHOR),
                )
            )
        )
        val decoded = LxStatusCodec.decode(json)!!
        // ⛔ SKIPPED_BY_DEPTH 与 NO_ANCHOR 必须能区分开（文案与修复动作完全不同）
        assertEquals(LxFailReason.SKIPPED_BY_DEPTH, decoded.platforms["kw"]!!.reason)
        assertEquals(LxFailReason.NO_ANCHOR, decoded.platforms["mg"]!!.reason)
        assertEquals(LxPlatformVerdict.UNTESTED, decoded.platforms["kw"]!!.verdict)
    }

    @Test
    fun mismatchFlagSurvivesRoundTrip() {
        val json = LxStatusCodec.encode(
            report(
                platforms = listOf(
                    probe("kg", LxPlatformVerdict.PLAYABLE, quality = "flac", container = LxContainer.MP3, mismatch = true)
                )
            )
        )
        assertTrue(LxStatusCodec.decode(json)!!.platforms["kg"]!!.mismatch)
    }

    @Test
    fun truncatedSurvivesRoundTrip() {
        assertTrue(LxStatusCodec.decode(LxStatusCodec.encode(report(truncated = true)))!!.truncated)
    }

    @Test
    fun noUrlIsPersisted() {
        // 直链带签名：落盘既无意义也易泄漏 ⇒ lastStatus 里不允许出现任何 http(s) 串
        val json = LxStatusCodec.encode(report())
        assertTrue(!json.contains("http://") && !json.contains("https://"))
    }

    @Test
    fun corruptJsonDecodesToNullInsteadOfThrowing() {
        assertNull(LxStatusCodec.decode("{不是 json"))
        assertNull(LxStatusCodec.decode(null))
        assertNull(LxStatusCodec.decode(""))
        assertNull(LxStatusCodec.decode("   "))
    }

    @Test
    fun unknownReasonNameDecodesToNullReason() {
        val decoded = LxStatusCodec.decode("""{"ts":1,"l1":"ok","platforms":{"kg":{"v":"bad","r":"NOT_A_REASON"}}}""")!!
        // 不认识的原因串不让整条记录作废，只丢原因
        assertNull(decoded.platforms["kg"]!!.reason)
    }

    @Test
    fun staleBoundaryIsExactly24Hours() {
        val ts = 1_700_000_000_000
        assertFalse(LxStatusCodec.isStale(ts, ts + LxStatusCodec.STALE_AFTER_MS))
        assertTrue(LxStatusCodec.isStale(ts, ts + LxStatusCodec.STALE_AFTER_MS + 1))
    }
}
