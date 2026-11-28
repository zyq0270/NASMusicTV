package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.ReplayGainInfo
import com.nasmusic.tv.data.model.ReplayGainSource
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded tag reader. All offsets/sizes are untrusted and checked before allocation/read. */
object ReplayGainTagReader {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_ID3_TAG = MAX_BYTES - 10
    private const val MAX_FLAC_BLOCK = 1024 * 1024
    private const val MAX_OGG_PACKET = 512 * 1024
    private const val MAX_COMMENT_BYTES = 512 * 1024

    suspend fun read(
        source: AudioHeaderSource,
        request: AudioHeaderRequest,
        maxBytes: Int = MAX_BYTES,
    ): ReplayGainInfo = withContext(Dispatchers.IO) {
        require(maxBytes in 1024..MAX_BYTES)
        runCatching {
            source.open(request).use { response ->
                if (!response.supportsRange) return@use ReplayGainInfo.NONE
                val parsed = parse(BufferedInputStream(response.stream), maxBytes)
                currentCoroutineContext().ensureActive()
                parsed
            }
        }.getOrElse { t ->
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppLog.w("ReplayGain", "header read failed for source=${request.source.kind}")
            ReplayGainInfo.NONE
        }
    }

    /** Internal/test entry point; the caller owns the stream. */
    fun parse(input: InputStream, maxBytes: Int = MAX_BYTES): ReplayGainInfo {
        val bytes = readBounded(input, maxBytes)
        return detectAndParse(bytes)
    }

    internal fun detectAndParse(bytes: ByteArray): ReplayGainInfo {
        if (bytes.size < 12) return ReplayGainInfo.NONE
        if (bytes.startsWithAt("ID3")) {
            val version = bytes[3].toInt() and 0xff
            if (version !in 2..4) return ReplayGainInfo.NONE
            val size = synchsafe(bytes, 6)
            if (size > MAX_ID3_TAG) return ReplayGainInfo.NONE
            val end = 10 + size
            if (end > bytes.size) return ReplayGainInfo.NONE
            val body = bytes.copyOfRange(10, end)
            val frames = if (version == 3 && bytes[5].toInt() and 0x80 != 0) unsynchronise(body) else body
            val tags = parseId3Frames(frames, version)
            return select(tags, ReplayGainSource.TAG_ID3)
        }
        if (bytes.startsWithAt("fLaC")) {
            var offset = 4
            while (offset + 4 <= bytes.size) {
                val type = bytes[offset].toInt() and 0x7f
                val last = bytes[offset].toInt() and 0x80 != 0
                val length = be24(bytes, offset + 1)
                if (length > MAX_FLAC_BLOCK || offset + 4 + length > bytes.size) return ReplayGainInfo.NONE
                if (type == 4) {
                    return select(parseVorbisComments(bytes.copyOfRange(offset + 4, offset + 4 + length)), ReplayGainSource.TAG_FLAC)
                }
                offset += 4 + length
                if (last) break
            }
        }
        if (bytes.startsWithAt("OggS")) {
            val packets = parseOggPackets(bytes)
            val opus = packets.firstOrNull()?.startsWithAt("OpusHead") == true
            val vorbis = packets.firstOrNull()?.startsWithAt("\u0001vorbis") == true
            if (!opus && !vorbis) return ReplayGainInfo.NONE
            val comments = packets.getOrNull(1)
            return if (comments == null) ReplayGainInfo.NONE
            else select(parseVorbisComments(comments), ReplayGainSource.TAG_OGG)
        }
        return ReplayGainInfo.NONE
    }

    private fun parseId3Frames(data: ByteArray, version: Int): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        var offset = when {
            version == 3 && data.size >= 10 && be32(data, 0) in 6..data.size - 4 ->
                4 + be32(data, 0)
            version == 4 && data.size >= 4 && synchsafe(data, 0) in 6..data.size -> synchsafe(data, 0)
            else -> 0
        }
        while (offset + 10 <= data.size) {
            val id = String(data, offset, 4, Charsets.ISO_8859_1)
            if (!id.all { it in ' '..'~' } || id == "\u0000\u0000\u0000\u0000") break
            val rawSize = if (version == 4) synchsafe(data, offset + 4) else be32(data, offset + 4)
            val flags = be16(data, offset + 8)
            if (rawSize < 0 || rawSize > data.size - offset - 10) break
            val payload = data.copyOfRange(offset + 10, offset + 10 + rawSize)
            offset += 10 + rawSize
            // ID3v2.4 frame unsynchronisation; v2.3 whole-tag unsync was already handled below.
            val frame = if (version == 4 && flags and 0x0002 != 0) unsynchronise(payload) else payload
            if (id.equals("TXXX", true)) parseTxxx(frame)?.let { (key, value) -> putTag(tags, key, value) }
        }
        return tags
    }

    private fun parseTxxx(frame: ByteArray): Pair<String, String>? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xff
        if (encoding !in 0..3) return null
        val wide = encoding == 1 || encoding == 2
        val sep = findWideTerminator(frame, 1, encoding)
        if (sep < 0) return null
        val key = decodeText(frame.copyOfRange(1, sep), encoding)
        val valueStart = sep + if (wide) 2 else 1
        // Writers may omit the trailing value terminator; the value then runs to frame end.
        val second = findWideTerminator(frame, valueStart, encoding)
        val value = if (second < 0) decodeText(frame.copyOfRange(valueStart, frame.size), encoding)
        else decodeText(frame.copyOfRange(second + if (wide) 2 else 1, frame.size), encoding)
        return key to value
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        if (data.size < 8 || le32(data, 0) < 3) return tags
        val vendorLength = le32(data, 0)
        if (vendorLength.toLong() + 8 > data.size) return tags
        var offset = 4 + vendorLength
        if (offset + 4 > data.size) return tags
        val count = le32(data, offset)
        offset += 4
        if (count > 100_000) return tags
        repeat(count) {
            if (offset + 4 > data.size) return tags
            val length = le32(data, offset)
            offset += 4
            if (length < 0 || length > MAX_COMMENT_BYTES || offset.toLong() + length > data.size) return tags
            val text = String(data, offset, length, Charsets.UTF_8)
            offset += length
            val eq = text.indexOf('=')
            if (eq > 0) putTag(tags, text.substring(0, eq), text.substring(eq + 1))
        }
        return tags
    }

    private fun putTag(tags: MutableMap<String, String>, key: String, value: String) {
        val normalized = key.trim().uppercase()
        if (normalized.startsWith("REPLAYGAIN_") || normalized.startsWith("R128_")) {
            // First valid value wins; corrupt duplicates must not hide a valid later value.
            if (!tags.containsKey(normalized)) tags[normalized] = value.trim()
        }
    }

    private fun select(tags: Map<String, String>, source: ReplayGainSource): ReplayGainInfo {
        val candidates = listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN")
        for (key in candidates) {
            parseDb(tags[key])?.let { return ReplayGainInfo(it, source) }
        }
        // EBU R128 targets -23 LUFS; ReplayGain targets -18 dBFS, hence +5 dB.
        for (key in listOf("R128_TRACK_GAIN", "R128_ALBUM_GAIN")) {
            tags[key]?.let { raw ->
                val q78 = raw.toDoubleOrNull() ?: raw.removeSuffix(" dB").toDoubleOrNull()
                if (q78 != null && q78.isFinite()) {
                    val db = (q78 / 256.0 + 5.0).toFloat()
                    if (validDb(db)) return ReplayGainInfo(db, source)
                }
            }
        }
        return ReplayGainInfo.NONE
    }

    private fun parseDb(raw: String?): Float? {
        val db = raw?.removeSuffix(" dB")?.trim()?.toFloatOrNull() ?: return null
        return db.takeIf(::validDb)
    }

    private fun validDb(db: Float) = db.isFinite() && db in -24f..24f

    private fun parseOggPackets(data: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val partial = ByteArrayOutputStream()
        var pageStart = 0
        while (pageStart + 27 <= data.size && packets.size < 3) {
            if (!data.startsWithAt("OggS", pageStart)) break
            val segments = data[pageStart + 26].toInt() and 0xff
            val tableStart = pageStart + 27
            var offset = tableStart + segments
            if (offset > data.size) break
            var packetComplete = true
            for (i in 0 until segments) {
                val length = data[tableStart + i].toInt() and 0xff
                if (partial.size() + length > MAX_OGG_PACKET) return packets
                if (offset + length > data.size) return packets
                partial.write(data, offset, length)
                offset += length
                if (length < 255) {
                    packets += partial.toByteArray()
                    partial.reset()
                    packetComplete = true
                } else {
                    packetComplete = false
                }
            }
            pageStart = offset
            if (!packetComplete) continue
        }
        return packets
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
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

    private fun unsynchronise(data: ByteArray): ByteArray {
        val result = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            result.write(data[i].toInt() and 0xff)
            if (data[i].toInt() and 0xff == 0xff && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return result.toByteArray()
    }

    private fun indexOfZero(data: ByteArray, from: Int): Int = (from until data.size).firstOrNull { data[it] == 0.toByte() } ?: -1

    private fun findWideTerminator(data: ByteArray, from: Int, encoding: Int): Int {
        if (encoding == 0 || encoding == 3) return indexOfZero(data, from)
        if (from + 1 >= data.size) return -1
        var i = from
        while (i + 1 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i += 2
        }
        return -1
    }

    private fun decodeText(data: ByteArray, encoding: Int): String = when (encoding) {
        0 -> String(data, Charsets.ISO_8859_1)
        3 -> String(data, Charsets.UTF_8)
        else -> String(data, if (encoding == 1) Charsets.UTF_16 else Charsets.UTF_16BE).removePrefix("\uFEFF")
    }

    private fun ByteArray.startsWithAt(prefix: ByteArray, offset: Int = 0): Boolean =
        offset >= 0 && offset <= size - prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }
    private fun ByteArray.startsWithAt(text: String, offset: Int = 0) = startsWithAt(text.toByteArray(Charsets.ISO_8859_1), offset)
    private fun synchsafe(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return -1
        var n = 0
        for (i in o until o + 4) {
            val v = b[i].toInt() and 0xff
            if (v and 0x80 != 0) return -1
            n = (n shl 7) or v
        }
        return n
    }
    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun be24(b: ByteArray, o: Int) = (be16(b, o) shl 8) or (b[o + 2].toInt() and 0xff)
    private fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
