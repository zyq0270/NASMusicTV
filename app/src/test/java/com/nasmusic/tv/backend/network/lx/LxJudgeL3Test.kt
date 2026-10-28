package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.HttpPlayabilityProbe
import com.nasmusic.tv.backend.network.Playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T20：L3 判据矩阵 + 魔数判定。
 *
 * 与 `HttpPlayabilityProbe.classify()` 的差异是**刻意的「更严」**（方案 §5.4.1.3）：
 * 播放链路把不确定归 UNKNOWN 并放行（怕误杀好歌 + 负面缓存 5 分钟），
 * 验证按钮不写缓存、判严了用户再点一次就行，所以 401/403/404/410/429/5xx 必须显式判死，
 * 否则 UI 会显示「存疑」，让用户以为「还能用，只是没测准」。
 */
class LxJudgeL3Test {

    // ─────────────────────── 状态码矩阵 ───────────────────────

    @Test
    fun authAndRateAndServerErrorsAreExplicitlyDead() {
        val cases = mapOf(
            401 to LxFailReason.HTTP_403,
            403 to LxFailReason.HTTP_403,
            404 to LxFailReason.HTTP_404,
            410 to LxFailReason.HTTP_404,
            429 to LxFailReason.HTTP_429,
            500 to LxFailReason.HTTP_5XX,
            502 to LxFailReason.HTTP_5XX,
            599 to LxFailReason.HTTP_5XX,
        )
        for ((code, reason) in cases) {
            for (contentType in listOf("audio/mpeg", "application/octet-stream", null)) {
                for (magic in listOf(LxContainer.FLAC, LxContainer.MP3, null)) {
                    val verdict = judgeL3(code, contentType, magic)
                    assertTrue("code=$code ct=$contentType magic=$magic 应判死", verdict is LxL3.Dead)
                    assertEquals(reason, (verdict as LxL3.Dead).reason)
                    assertEquals(code, verdict.code)
                }
            }
        }
    }

    @Test
    fun audioContentTypeIsOkForSuccessCodesAndRecordsContainer() {
        for (code in listOf(200, 206)) {
            val verdict = judgeL3(code, "audio/mpeg", LxContainer.MP3)
            assertTrue(verdict is LxL3.Ok)
            assertEquals("MP3", (verdict as LxL3.Ok).magic)
            assertEquals(code, verdict.code)
        }
    }

    @Test
    fun htmlResponseIsDeadAsNotAudio() {
        for (contentType in listOf("text/html; charset=utf-8", "application/json", "text/plain")) {
            val verdict = judgeL3(200, contentType, null)
            assertTrue("ct=$contentType 应判 NOT_AUDIO", verdict is LxL3.Dead)
            assertEquals(LxFailReason.NOT_AUDIO, (verdict as LxL3.Dead).reason)
        }
    }

    @Test
    fun unreliableContentTypeFallsBackToMagicThenToDoubtful() {
        // 酷狗 CDN 有时给 application/octet-stream：content-type 不可靠 ⇒ 魔数说了算
        val withMagic = judgeL3(200, "application/octet-stream", LxContainer.FLAC)
        assertTrue(withMagic is LxL3.Ok)
        assertEquals("FLAC", (withMagic as LxL3.Ok).magic)

        val noMagic = judgeL3(200, "application/octet-stream", null)
        assertTrue("魔数也没有 ⇒ 存疑，⛔ 不冒充失败", noMagic is LxL3.Doubtful)
    }

    @Test
    fun redirectLeftUnfollowedIsDoubtfulNotDead() {
        // 3xx 没跟到底：不确定，不是失败
        for (code in listOf(301, 302, 307)) {
            assertTrue(judgeL3(code, null, null) is LxL3.Doubtful)
        }
    }

    // ─────────────────────── 魔数 ───────────────────────

    @Test
    fun magicDetectsAllSupportedContainers() {
        assertEquals(LxContainer.FLAC, LxMagic.detect("fLaC\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(LxContainer.OGG, LxMagic.detect("OggS\u0000\u0002".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(LxContainer.MP3, LxMagic.detect("ID3\u0004".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(LxContainer.MP3, LxMagic.detect(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)))
        assertEquals(
            LxContainer.M4A,
            LxMagic.detect(byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())),
        )
    }

    @Test
    fun magicRejectsShortOrUnknownSamples() {
        // ⛔ 不猜：样本太短或对不上任何签名一律 null
        assertEquals(null, LxMagic.detect(ByteArray(0)))
        assertEquals(null, LxMagic.detect(byteArrayOf(0x49, 0x44)))
        assertEquals(null, LxMagic.detect("<html><body>404</body></html>".toByteArray()))
        // 只有 1 个字节是 0xFF 不够：要看 11 位帧同步
        assertEquals(null, LxMagic.detect(byteArrayOf(0xFF.toByte(), 0x00)))
    }

    @Test
    fun containerOfMapsReportStringBackToEnum() {
        assertEquals(LxContainer.FLAC, LxMagic.containerOf("FLAC"))
        assertEquals(null, LxMagic.containerOf("WAV"))
        assertEquals(null, LxMagic.containerOf(null))
    }

    // ─────────────────────── 自证：classify 没被改 ───────────────────────

    @Test
    fun playabilityClassifyIsUnchanged() {
        // 播放链路的判据保持原样：⛔ 合并两种判据会让「跳过好歌 + 负面缓存 5 分钟」复发
        assertEquals(Playability.DEAD, HttpPlayabilityProbe.classify(404, null))
        assertEquals(Playability.DEAD, HttpPlayabilityProbe.classify(403, "audio/mpeg"))
        assertEquals(Playability.DEAD, HttpPlayabilityProbe.classify(451, null))
        assertEquals(Playability.PLAYABLE, HttpPlayabilityProbe.classify(200, "audio/mpeg"))
        assertEquals(Playability.PLAYABLE, HttpPlayabilityProbe.classify(206, "video/mp4"))
        assertEquals(Playability.UNKNOWN, HttpPlayabilityProbe.classify(200, null))
        assertEquals(Playability.UNKNOWN, HttpPlayabilityProbe.classify(200, "application/octet-stream"))
        assertEquals(Playability.UNKNOWN, HttpPlayabilityProbe.classify(302, null))
        assertEquals(Playability.UNKNOWN, HttpPlayabilityProbe.classify(429, null))
        assertEquals(Playability.UNKNOWN, HttpPlayabilityProbe.classify(500, "audio/mpeg"))
    }
}
