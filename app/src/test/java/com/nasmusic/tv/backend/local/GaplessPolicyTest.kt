package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.SongSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GaplessPolicyTest {
    @Test fun `MP3 Xing without LAME delay falls back`() {
        assertFalse(GaplessProbe.probeBytes(mp3(lame = false)).supported)
    }

    @Test fun `MP3 Xing LAME delay metadata is supported`() {
        val result = GaplessProbe.probeBytes(mp3())
        assertTrue(result.supported)
        assertEquals(GaplessAudioFormat.MP3, result.format)
    }

    @Test fun `MP3 without Xing falls back`() {
        val bytes = mp3().also { "Xing".toByteArray().copyInto(it, 36); it.fill(0, 36, 60) }
        assertFalse(GaplessProbe.probeBytes(bytes).supported)
    }

    @Test fun `FLAC streaminfo supports gapless but unsafe device fuses it`() {
        val bytes = flac()
        assertTrue(GaplessProbe.probeBytes(bytes, false).supported)
        val unsafe = GaplessProbe.probeBytes(bytes, true)
        assertFalse(unsafe.supported)
        assertEquals("device_flac_fuse", unsafe.reason)
    }

    @Test fun `Opus and Vorbis headers are supported`() {
        assertTrue(GaplessProbe.probeBytes(oggOpus()).supported)
        assertTrue(GaplessProbe.probeBytes(oggVorbis()).supported)
        assertFalse(GaplessProbe.probeBytes(oggOpus().copyOf(15)).supported)
    }

    @Test fun `corrupt unknown and unconfirmed network sources fall back`() {
        assertFalse(GaplessProbe.probeBytes(ByteArray(100)).supported)
        assertFalse(GaplessProbe.probeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0x7f, 0x7f, 0x7f, 0x7f)).supported)
        assertFalse(GaplessProbe.supportsProbe(SongSourceKind.NETWORK))
        assertTrue(GaplessProbe.supportsProbe(SongSourceKind.LOCAL))
        assertTrue(GaplessProbe.supportsProbe(SongSourceKind.NAS))
    }

    private fun mp3(lame: Boolean = true): ByteArray {
        val b = ByteArray(300)
        b[0] = 0xff.toByte(); b[1] = 0xfb.toByte(); b[2] = 0x90.toByte(); b[3] = 0x64
        "Xing".toByteArray().copyInto(b, 36)
        b[43] = 1.toByte() // frames flag (big-endian 32-bit field at 40..43)
        b[44] = 0; b[45] = 0; b[46] = 0x0e; b[47] = 0x10.toByte() // 3600 frames
        if (lame) {
            "LAME3.99a".toByteArray().copyInto(b, 60)
            b[81] = 0x05; b[82] = 0x50; b[83] = 0x01 // delay 576 / padding 1105
        }
        return b
    }

    private fun flac(): ByteArray {
        val info = ByteArray(34)
        info[0] = 0x10; info[1] = 0x10 // min block 4096
        info[2] = 0x10; info[3] = 0x10 // max block 4096
        info[10] = 0; info[11] = 0x0a; info[12] = 0xbb.toByte(); info[13] = 0x80.toByte() // 44100 Hz
        // total samples 44100 (36-bit field)
        info[17] = 0; info[18] = 0; info[19] = 0; info[20] = 0x0a; info[21] = 0xc4.toByte(); info[22] = 0x40.toByte()
        return byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0x80.toByte(), 0, 0, 34) + info
    }

    private fun oggOpus(): ByteArray {
        val id = ByteArray(19).also { "OpusHead".toByteArray().copyInto(it); it[10] = 0x01; it[11] = 0x2c }
        val tags = ByteArray(12)
        return ogg(id, tags)
    }

    private fun oggVorbis(): ByteArray {
        val id = ByteArray(30).also { "\u0001vorbis".toByteArray().copyInto(it); it[11] = 0x01; it[12] = 0x40 }
        return ogg(id, byteArrayOf(3) + "vorbis".toByteArray() + ByteArray(16))
    }

    private fun ogg(id: ByteArray, tags: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val lacing = listOf(id, tags).flatMap { p -> List(p.size / 255) { 255 } + (p.size % 255) }
        out.write("OggS".toByteArray()); out.write(ByteArray(22)); out.write(lacing.size)
        out.write(lacing.map(Int::toByte).toByteArray())
        out.write(id); out.write(tags)
        return out.toByteArray()
    }
}
