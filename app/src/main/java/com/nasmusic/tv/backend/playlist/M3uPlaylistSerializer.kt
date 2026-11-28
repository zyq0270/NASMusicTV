package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceKind

/** 可移植 M3U 序列化：只输出显示信息与脱敏后的 URI/路径。 */
class M3uPlaylistSerializer {

    data class Result(
        val text: String,
        val containsUrlPlaceholder: Boolean,
        val unresolvedCount: Int,
    )

    fun serialize(
        playlistName: String,
        songs: List<Song>,
        sourceResolver: (Song) -> PlaylistSongRef? = { PlaylistSongRef.fromSong(it) },
    ): Result {
        val out = StringBuilder()
        out.append("#EXTM3U\n")
        out.append("#PLAYLIST:").append(singleLine(playlistName)).append('\n')
        var containsPlaceholder = false
        var unresolved = 0

        songs.forEach { song ->
            val ref = sourceResolver(song)
            val durationSec = (song.durationMs / 1000L).coerceAtLeast(0L)
            out.append("#EXTINF:").append(durationSec).append(',')
                .append(singleLine(song.artist)).append(" - ")
                .append(singleLine(song.title)).append('\n')

            val rawUri = portableUri(song, ref)
            if (rawUri.isNullOrBlank()) {
                unresolved++
                out.append("#NASMUSIC:UNRESOLVED\n")
            } else {
                val sanitized = sanitizeUri(rawUri)
                containsPlaceholder = containsPlaceholder || sanitized.second
                out.append("#NASMUSIC:PORTABILITY=").append(portability(song, ref)).append('\n')
                out.append(sanitized.first).append('\n')
            }
        }
        return Result(out.toString(), containsPlaceholder, unresolved)
    }

    private fun portableUri(song: Song, ref: PlaylistSongRef?): String? = when (ref?.source?.kind) {
        SongSourceKind.LOCAL -> ref.source.canonicalUri ?: song.path ?: song.streamUrl
        SongSourceKind.NETWORK -> song.streamUrl
        SongSourceKind.NAS -> song.streamUrl
        null -> if (song.isLocalSong) song.path ?: song.streamUrl else song.streamUrl
    }

    private fun portability(song: Song, ref: PlaylistSongRef?): String = when {
        ref?.source?.kind == SongSourceKind.NETWORK -> "REQUIRES_RESOLUTION"
        song.isLocalSong || ref?.source?.kind == SongSourceKind.LOCAL -> "DEVICE_LOCAL"
        else -> "VOLATILE_URL"
    }

    /** 返回脱敏后的 URI 与是否发生占位替换；永不输出 userinfo 或凭据参数值。 */
    internal fun sanitizeUri(raw: String): Pair<String, Boolean> {
        val value = raw.trim()
        val queryIndex = value.indexOf('?')
        val fragmentIndex = value.indexOf('#', queryIndex + 1)
        val base = if (queryIndex >= 0) value.substring(0, queryIndex) else value
        val query = if (queryIndex >= 0) {
            value.substring(queryIndex + 1, if (fragmentIndex >= 0) fragmentIndex else value.length)
        } else ""
        val fragment = if (fragmentIndex >= 0) value.substring(fragmentIndex) else ""

        val safeBase = redactUserInfo(base)
        var replaced = safeBase != base || fragment.isNotEmpty()
        val safeQuery = query.split('&').joinToString("&") { pair ->
            if (pair.isBlank()) return@joinToString pair
            val rawName = pair.substringBefore('=')
            val name = runCatching { java.net.URLDecoder.decode(rawName, Charsets.UTF_8.name()) }
                .getOrDefault(rawName).lowercase()
            if (name in SENSITIVE_PARAMS || name.startsWith("x-amz-")) {
                replaced = true
                rawName + "=" + URL_PLACEHOLDER
            } else pair
        }
        val safeFragment = if (fragment.isNotEmpty()) "#$URL_PLACEHOLDER" else ""
        return safeBase + (if (queryIndex >= 0) "?$safeQuery" else "") + safeFragment to replaced
    }

    private fun redactUserInfo(value: String): String {
        val scheme = value.indexOf("://")
        if (scheme < 0) return value
        val authorityStart = scheme + 3
        val pathStart = value.indexOf('/', authorityStart).let { if (it < 0) value.length else it }
        val authority = value.substring(authorityStart, pathStart)
        val at = authority.lastIndexOf('@')
        if (at < 0) return value
        return value.substring(0, authorityStart) + URL_PLACEHOLDER + "@" +
            authority.substring(at + 1) + value.substring(pathStart)
    }

    private fun singleLine(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()

    companion object {
        const val URL_PLACEHOLDER = "__REDACTED__"
        private val SENSITIVE_PARAMS = setOf(
            "token", "api_key", "apikey", "access_token", "auth", "authorization",
            "password", "passwd", "p", "t", "s", "u", "key", "sig", "signature",
            "credential", "credentials", "session", "sessionid", "sid",
        )
    }
}
