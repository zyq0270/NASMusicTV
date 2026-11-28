package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.util.DeviceAudioCaps
import java.io.ByteArrayOutputStream
import java.io.InputStream

enum class GaplessAudioFormat { MP3, FLAC, OPUS, VORBIS, UNKNOWN }

data class GaplessProbeResult(
    val supported: Boolean,
    val format: GaplessAudioFormat = GaplessAudioFormat.UNKNOWN,
    val reason: String? = null,
)

/** LAME 标签中的 12 位 delay 与 12 位 padding，供测试直接断言解析数值。 */
internal data class LameGaplessInfo(val delay: Int, val padding: Int)

/** 从 LAME 标识起点解析 delay/padding；布局为 8+4 位 delay、4+8 位 padding。 */
internal fun parseLameGaplessInfo(bytes: ByteArray, lameOffset: Int): LameGaplessInfo? {
    if (lameOffset < 0 || lameOffset + 23 >= bytes.size) return null
    val b21 = bytes[lameOffset + 21].toInt() and 0xFF
    val b22 = bytes[lameOffset + 22].toInt() and 0xFF
    val b23 = bytes[lameOffset + 23].toInt() and 0xFF
    return LameGaplessInfo(
        delay = (b21 shl 4) or (b22 shr 4),
        padding = ((b22 and 0x0F) shl 8) or b23,
    )
}

/** Probes container gapless metadata only; never decodes audio. Unknown means fallback. */
object GaplessProbe {
    const val MAX_HEADER_BYTES = 64 * 1024

    fun probe(
        input: InputStream,
        isFlacDecodeUnsafe: Boolean = DeviceAudioCaps.isFlacDecodeUnsafe(),
    ): GaplessProbeResult {
        val bytes = readBounded(input, MAX_HEADER_BYTES + 1)
        return probeBytes(bytes, isFlacDecodeUnsafe)
    }

    internal fun probeBytes(bytes: ByteArray, isFlacDecodeUnsafe: Boolean = false): GaplessProbeResult {
        if (bytes.size < 12) return GaplessProbeResult(false, reason = "truncated")
        var audio = bytes
        if (audio.startsWith("ID3")) {
            val size = synchsafe(audio, 6)
            if (size < 0 || size > MAX_HEADER_BYTES) return GaplessProbeResult(false, format = GaplessAudioFormat.MP3, reason = "invalid_id3")
            val skip = 10 + size
            if (skip >= audio.size) return GaplessProbeResult(false, format = GaplessAudioFormat.MP3, reason = "no_audio_frame")
            audio = audio.copyOfRange(skip, audio.size)
        }
        return when {
            audio.startsWith("fLaC") -> probeFlac(audio, isFlacDecodeUnsafe)
            audio.startsWith("OggS") -> probeOgg(audio)
            isMp3Sync(audio) -> probeMp3(audio)
            else -> GaplessProbeResult(false, reason = "unknown_format")
        }
    }

    private fun probeMp3(a: ByteArray): GaplessProbeResult {
        if (a.size < 36) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "truncated")
        val versionBits = (a[1].toInt() and 0xff) shr 3 and 3
        val layerBits = (a[1].toInt() and 0xff) shr 1 and 3
        if (versionBits == 1 || layerBits == 0) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "invalid_frame")
        val bitrateIndex = a[2].toInt() and 0xff shr 4
        val sampleIndex = a[2].toInt() and 0xff shr 2 and 3
        if (bitrateIndex in 0..14 && sampleIndex in 0..2) {
            // First MPEG audio frame is required; allowing arbitrary bytes here would mistake
            // an ID3 payload or cover art for a gapless stream.
            val samples = if (versionBits == 3) 1152 else 576
            val table = if (versionBits == 3) intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            else intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
            val tableV1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            val kbps = when {
                versionBits == 3 && layerBits == 3 -> tableV1.getOrElse(bitrateIndex) { 0 }
                versionBits == 3 -> table.getOrElse(bitrateIndex) { 0 }
                layerBits == 3 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256).getOrElse(bitrateIndex) { 0 }
                else -> table.getOrElse(bitrateIndex) { 0 }
            }
            val rates = if (versionBits == 3) intArrayOf(44100, 48000, 32000) else intArrayOf(44100, 48000, 32000)
            if (kbps == 0) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "invalid_bitrate")
            val frameLength = when (layerBits == 3) {
                true -> (12000 * kbps / rates[sampleIndex] + (if (((a[1].toInt() and 0xff) shr 1 and 1) == 0) 0 else 1)) * 4
                false -> samples / 8 * kbps * 1000 / rates[sampleIndex] + 0
            }
            val xing = indexOf(a, "Xing", 4, minOf(a.size, frameLength + 36))
            val info = indexOf(a, "Info", 4, minOf(a.size, frameLength + 36))
            val marker = if (xing >= 0) xing else info
            if (marker < 0) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "no_xing")
            if (marker + 12 > a.size) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "truncated_xing")
            val flags = be32(a, marker + 4)
            if (flags and 1 == 0) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "no_frame_count")
            var cursor = marker + 8
            if (flags and 1 != 0) cursor += 4
            if (flags and 2 != 0) cursor += 4
            if (flags and 4 != 0) cursor += 100
            if (flags and 8 != 0) cursor += 4
            if (cursor > a.size) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "truncated_xing")
            val lameOffset = indexOf(a, "LAME", cursor, minOf(a.size, cursor + 80))
            if (lameOffset < 0) return GaplessProbeResult(false, GaplessAudioFormat.MP3, "no_lame_delay")
            val lameInfo = parseLameGaplessInfo(a, lameOffset)
                ?: return GaplessProbeResult(false, GaplessAudioFormat.MP3, "no_lame_delay")
            // F-P02：delay = b21<<4 | b22>>4；padding = (b22 & 0x0F)<<8 | b23。
            // 旧实现漏掉 0x0F 掩码且把 padding 当成 <<4|>>4，系统性偏小。
            val valid = lameInfo.delay in 1..2304 && lameInfo.padding <= 2304 &&
                (lameInfo.delay + lameInfo.padding) <= samples * 2
            return GaplessProbeResult(valid, GaplessAudioFormat.MP3, if (valid) null else "invalid_lame_delay")
        }
        return GaplessProbeResult(false, GaplessAudioFormat.MP3, "invalid_frame")
    }

    private fun probeFlac(a: ByteArray, unsafe: Boolean): GaplessProbeResult {
        if (unsafe) return GaplessProbeResult(false, GaplessAudioFormat.FLAC, "device_flac_fuse")
        if (a.size < 8) return GaplessProbeResult(false, GaplessAudioFormat.FLAC, "truncated")
        val firstLength = be24(a, 5)
        if (a[4].toInt() and 0x7f != 0 || firstLength != 34 || a.size < 42) {
            return GaplessProbeResult(false, GaplessAudioFormat.FLAC, "invalid_streaminfo")
        }
        val packed = byteArrayOf(a[18], a[19], a[20], a[21], a[22], a[23], a[24], a[25])
        var total = 0L
        for (b in packed) total = (total shl 8) or (b.toLong() and 0xff)
        val minBlock = (a[8].toInt() and 0xff) * 256 + (a[9].toInt() and 0xff)
        val maxBlock = (a[10].toInt() and 0xff) * 256 + (a[11].toInt() and 0xff)
        val valid = total > 0 && minBlock >= 16 && maxBlock >= minBlock
        return GaplessProbeResult(valid, GaplessAudioFormat.FLAC, if (valid) null else "no_total_samples")
    }

    private fun probeOgg(a: ByteArray): GaplessProbeResult {
        val packets = mutableListOf<ByteArray>()
        val partial = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset + 27 <= a.size && packets.size < 2) {
            if (!a.startsWith("OggS", offset)) return GaplessProbeResult(false, GaplessAudioFormat.UNKNOWN, "invalid_ogg_page")
            val count = a[offset + 26].toInt() and 0xff
            if (offset + 27 + count > a.size) return GaplessProbeResult(false, GaplessAudioFormat.UNKNOWN, "truncated_page")
            var dataOffset = offset + 27 + count
            for (i in 0 until count) {
                val length = a[offset + 27 + i].toInt() and 0xff
                if (dataOffset + length > a.size) return GaplessProbeResult(false, GaplessAudioFormat.UNKNOWN, "truncated_packet")
                partial.write(a, dataOffset, length)
                dataOffset += length
                if (length < 255) {
                    packets += partial.toByteArray()
                    partial.reset()
                }
            }
            offset = dataOffset
        }
        val id = packets.getOrNull(0) ?: return GaplessProbeResult(false, GaplessAudioFormat.UNKNOWN, "no_identification")
        val format = when {
            id.startsWith("OpusHead") -> GaplessAudioFormat.OPUS
            id.startsWith("\u0001vorbis") -> GaplessAudioFormat.VORBIS
            else -> return GaplessProbeResult(false, GaplessAudioFormat.UNKNOWN, "unknown_codec")
        }
        val tags = packets.getOrNull(1) ?: return GaplessProbeResult(false, format, "no_comment_packet")
        val valid = when (format) {
            GaplessAudioFormat.OPUS -> id.size >= 19 && tags.size >= 8 &&
                ((id[10].toInt() and 0xff) * 256 + (id[11].toInt() and 0xff)) in 0..65535
            GaplessAudioFormat.VORBIS -> id.size >= 30 && tags.startsWith("\u0003vorbis") &&
                ((id[12].toInt() and 0xff) or ((id[11].toInt() and 0xff) shl 8)) > 0
            else -> false
        }
        return GaplessProbeResult(valid, format, if (valid) null else "invalid_gapless_header")
    }

    /** NAS HTTP requires confirmed metadata; NETWORK is never probed. Local content can be probed. */
    fun supportsProbe(kind: SongSourceKind): Boolean = kind != SongSourceKind.NETWORK

    private fun isMp3Sync(a: ByteArray) = a.size > 1 && a[0].toInt() and 0xff == 0xff && a[1].toInt() and 0xe0 == 0xe0
    private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
        offset >= 0 && offset <= size - prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ByteArray.startsWith(text: String, offset: Int = 0): Boolean =
        startsWith(text.toByteArray(Charsets.ISO_8859_1), offset)

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(16 * 1024)
        var emptyReads = 0
        while (out.size() < maxBytes) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (n < 0) break
            if (n == 0) {
                if (++emptyReads > 100) throw java.io.IOException("Header stream made no progress")
                continue
            }
            emptyReads = 0
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun synchsafe(b: ByteArray, o: Int): Int {
        var n = 0
        for (i in o until o + 4) {
            val v = b[i].toInt() and 0xff
            if (v and 0x80 != 0) return -1
            n = (n shl 7) or v
        }
        return n
    }
    private fun be24(b: ByteArray, o: Int) = (b[o].toInt() and 0xff shl 16) or (b[o + 1].toInt() and 0xff shl 8) or (b[o + 2].toInt() and 0xff)
    private fun be32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)
    private fun indexOf(b: ByteArray, text: String, from: Int, to: Int): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        if (from < 0 || to - from < needle.size) return -1
        for (i in from..to - needle.size) {
            if (needle.indices.all { b[i + it] == needle[it] }) return i
        }
        return -1
    }
}
