package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.ReplayGainInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ReplayGainTagReaderTest {
    @Test fun `id3 TXXX track beats album and R128 converts plus five dB`() {
        val body = txxx("REPLAYGAIN_TRACK_GAIN", "-6.2 dB") + txxx("REPLAYGAIN_ALBUM_GAIN", "-10 dB")
        val parsed = ReplayGainTagReader.detectAndParse(id3(body))
        assertEquals(-6.2f, parsed.dB, 0.001f)
        assertEquals(com.nasmusic.tv.data.model.ReplayGainSource.TAG_ID3, parsed.source)
        val r128 = ReplayGainTagReader.detectAndParse(id3(txxx("R128_TRACK_GAIN", "-5120")))
        assertEquals(-15f, r128.dB, 0.001f)
    }

    @Test fun `invalid ID3 size and truncated tags fail closed`() {
        val broken = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0x7f, 0x7f, 0x7f, 0x7f)
        assertFalse(ReplayGainTagReader.detectAndParse(broken).source != com.nasmusic.tv.data.model.ReplayGainSource.NONE)
        assertEquals(ReplayGainInfo.NONE, ReplayGainTagReader.detectAndParse(id3(txxx("REPLAYGAIN_TRACK_GAIN", "NaN"))))
    }

    @Test fun `FLAC Vorbis comment album gain is supported`() {
        val comment = vorbisComment("REPLAYGAIN_ALBUM_GAIN=-9.5 dB")
        val bytes = byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0x84.toByte()) + be24(comment.size) + comment
        val parsed = ReplayGainTagReader.detectAndParse(bytes)
        assertEquals(-9.5f, parsed.dB, 0.001f)
        assertEquals(com.nasmusic.tv.data.model.ReplayGainSource.TAG_FLAC, parsed.source)
    }

    @Test fun `OpusTags and Vorbis comments are recognized`() {
        val opusId = ByteArray(19).also { "OpusHead".toByteArray().copyInto(it) }
        val vorbisId = ByteArray(30).also { "\u0001vorbis".toByteArray().copyInto(it) }
        for (id in listOf(opusId, vorbisId)) {
            val ogg = oggPage(listOf(id, vorbisComment("REPLAYGAIN_TRACK_GAIN=-3 dB")))
            val parsed = ReplayGainTagReader.detectAndParse(ogg)
            assertEquals(-3f, parsed.dB, 0.001f)
            assertEquals(com.nasmusic.tv.data.model.ReplayGainSource.TAG_OGG, parsed.source)
        }
    }

    @Test fun `damaged Vorbis length and oversize budgets return none`() {
        val bad = byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f) + ByteArray(8)
        assertTrue(ReplayGainTagReader.detectAndParse(byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0x84.toByte()) + be24(bad.size) + bad).dB == 0f)
        val oversized = ByteArray(ReplayGainTagReader.MAX_BYTES + 1) { 0x49 }
        assertEquals(0f, ReplayGainTagReader.parse(oversized.inputStream()).dB, 0f)
    }

    private fun id3(frames: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0))
        out.write(synchsafe(frames.size))
        out.write(frames)
        return out.toByteArray()
    }

    private fun txxx(key: String, value: String): ByteArray {
        val payload = byteArrayOf(3) + key.toByteArray() + byteArrayOf(0) + value.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("TXXX".toByteArray())
        out.write(be32(payload.size))
        out.write(byteArrayOf(0, 0))
        out.write(payload)
        return out.toByteArray()
    }

    private fun vorbisComment(text: String): ByteArray {
        val vendor = "test".toByteArray()
        val value = text.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(le32(vendor.size)); out.write(vendor); out.write(le32(1)); out.write(le32(value.size)); out.write(value)
        return out.toByteArray()
    }

    private fun oggPage(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val lacing = packets.flatMap { p -> List(p.size / 255) { 255 } + (p.size % 255) }
        out.write("OggS".toByteArray()); out.write(ByteArray(22)); out.write(lacing.size); out.write(lacing.map(Int::toByte).toByteArray())
        packets.forEach(out::write)
        return out.toByteArray()
    }

    private fun synchsafe(n: Int) = byteArrayOf((n shr 21 and 127).toByte(), (n shr 14 and 127).toByte(), (n shr 7 and 127).toByte(), (n and 127).toByte())
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun be24(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
}
