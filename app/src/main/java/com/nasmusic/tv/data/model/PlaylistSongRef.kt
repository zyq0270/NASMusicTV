package com.nasmusic.tv.data.model

import com.nasmusic.tv.util.SongKeys
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 歌单条目的稳定来源身份。
 *
 * 稳定 key 由 [SongKeys] 生成，因此仅裸 ID 相同、但服务器或后端不同的歌曲不会碰撞。
 * [binding] 为 LEGACY_UNBOUND 时只保留显示信息，不能推回 NAS。
 */
data class PlaylistSongRef(
    val stableKey: String,
    val source: SongSourceIdentity,
    val binding: Binding = Binding.BOUND,
) {
    enum class Binding { BOUND, LEGACY_UNBOUND }

    val isPushableToNas: Boolean
        get() = binding == Binding.BOUND &&
            source.kind == SongSourceKind.NAS &&
            source.nativeId.isNotBlank() &&
            !source.serverId.isNullOrBlank() &&
            !source.backendType.isNullOrBlank()

    fun isBoundTo(serverId: String, backendType: String): Boolean =
        isPushableToNas && source.serverId == serverId && source.backendType == backendType

    /** 将来源身份编码进非网络 Song 的保留字段，避免修改 Song/DataStore  schema。 */
    fun encodeForLocalStorage(): PlaylistSongRef {
        val safeSource = source.copy(
            canonicalUri = source.canonicalUri?.let(::sanitizeUri),
        )
        val safeKey = if (source.kind == SongSourceKind.LOCAL && stableKey.isNotBlank()) {
            runCatching {
                SongKeys.stableKey(
                    Song(id = safeSource.nativeId, title = "", storageType = safeSource.storageIdentity),
                    safeSource,
                )
            }.getOrNull().orEmpty()
        } else stableKey
        return copy(stableKey = safeKey, source = safeSource)
    }

    companion object {
        private const val LOCAL_SOURCE_PREFIX = "nasmusic-playlist-source-v1:"
        private const val LOCAL_KEY_PREFIX = "nasmusic-playlist-key-v1:"
        private const val LEGACY_MARKER = "LEGACY_UNBOUND"
        private const val FIELD_SEPARATOR = "|"

        fun fromSong(
            song: Song,
            source: SongSourceIdentity? = null,
            serverId: String? = null,
            backendType: String? = null,
        ): PlaylistSongRef? {
            decodeFromSong(song)?.encodeForLocalStorage()?.let { return it }
            val bound = when {
                source != null -> source.copy(nativeId = source.nativeId.ifBlank { song.id })
                serverId != null && backendType != null && !song.isNetworkSong && !song.isLocalSong ->
                    SongSourceIdentity(
                        kind = SongSourceKind.NAS,
                        nativeId = song.id,
                        backendType = backendType,
                        serverId = serverId,
                    )
                else -> SongKeys.bindSource(song)
            } ?: return null
            val uri = if (bound.kind == SongSourceKind.LOCAL) {
                bound.canonicalUri ?: song.path ?: song.streamUrl
            } else bound.canonicalUri
            val safeBound = bound.copy(canonicalUri = uri?.let(::sanitizeUri))
            if (safeBound.nativeId.isBlank()) return null
            val key = runCatching { SongKeys.stableKey(song, safeBound) }.getOrNull() ?: return null
            return PlaylistSongRef(key, safeBound).encodeForLocalStorage()
        }

        fun legacy(song: Song): PlaylistSongRef = PlaylistSongRef(
            stableKey = "",
            source = SongSourceIdentity(
                kind = if (song.isNetworkSong) SongSourceKind.NETWORK else SongSourceKind.LOCAL,
                nativeId = song.id,
                platform = song.networkPlatform ?: song.networkSource,
                canonicalUri = (song.path ?: song.streamUrl)?.let(::sanitizeUri),
                storageIdentity = song.storageType,
            ),
            binding = Binding.LEGACY_UNBOUND,
        )

        /** 写入本地歌单前调用；返回带保留字段的 Song 副本。 */
        fun bindToSong(song: Song, ref: PlaylistSongRef?): Song {
            if (ref == null) return song
            if (song.isNetworkSong && ref.source.kind == SongSourceKind.NETWORK) return song
            val safeRef = ref.encodeForLocalStorage()
            return song.copy(
                networkSource = LOCAL_SOURCE_PREFIX + encodeSource(safeRef.source),
                networkId = LOCAL_KEY_PREFIX + encode(safeRef.stableKey) +
                    FIELD_SEPARATOR + safeRef.binding.name,
            )
        }

        fun decodeFromSong(song: Song): PlaylistSongRef? {
            val encodedSource = song.networkSource?.takeIf { it.startsWith(LOCAL_SOURCE_PREFIX) }
                ?: return null
            val encodedKey = song.networkId?.takeIf { it.startsWith(LOCAL_KEY_PREFIX) }
                ?: return null
            val source = runCatching { decodeSource(encodedSource.removePrefix(LOCAL_SOURCE_PREFIX)) }
                .getOrNull() ?: return null
            val keyAndBinding = encodedKey.removePrefix(LOCAL_KEY_PREFIX)
            val separator = keyAndBinding.lastIndexOf(FIELD_SEPARATOR)
            if (separator < 0) return null
            val binding = runCatching { Binding.valueOf(keyAndBinding.substring(separator + 1)) }
                .getOrNull() ?: return null
            val key = runCatching { decode(keyAndBinding.substring(0, separator)) }.getOrNull()
                ?: return null
            // 保留的稳定 key 自身带百分号编码；解出后仍是非法转义（如 bad%ZZ）说明字段被篡改。
            if (hasInvalidPercentEscape(key)) return null
            // v2 旧格式没有稳定 key，但 LEGACY_UNBOUND 必须保留，否则会被误判为可推送条目。
            if (key.isBlank() && binding != Binding.LEGACY_UNBOUND) return null
            return PlaylistSongRef(key, source, binding).encodeForLocalStorage()
        }

        /**
         * JSON v3 恢复为可播放/可再次导出的 Song；volatile URL 不恢复。
         * [bindToStorage] = false 供解析器使用：写回本地存储前由导入器统一绑定。
         */
        fun restoreSong(ref: PlaylistSongRef?, song: Song, bindToStorage: Boolean = true): Song {
            val restored = when (ref?.source?.kind) {
                SongSourceKind.NAS -> song.copy(
                    id = ref.source.nativeId,
                    isNetworkSong = false,
                    isLocalSong = false,
                    networkSource = null,
                    networkId = null,
                    networkPlatform = null,
                    path = null,
                    streamUrl = null,
                    coverUrl = null,
                )
                SongSourceKind.NETWORK -> song.copy(
                    id = ref.source.nativeId,
                    isNetworkSong = true,
                    isLocalSong = false,
                    networkSource = ref.source.platform ?: song.networkSource,
                    networkId = ref.source.nativeId,
                    networkPlatform = ref.source.platform,
                    path = null,
                    streamUrl = null,
                    coverUrl = null,
                )
                SongSourceKind.LOCAL -> song.copy(
                    id = ref.source.nativeId,
                    isNetworkSong = false,
                    isLocalSong = true,
                    networkSource = null,
                    networkId = null,
                    networkPlatform = null,
                    path = ref.source.canonicalUri ?: song.path,
                    streamUrl = ref.source.canonicalUri ?: song.streamUrl,
                    coverUrl = null,
                )
                null -> song.copy(streamUrl = null, coverUrl = null)
            }
            return if (bindToStorage) bindToSong(restored, ref) else restored
        }

        private fun hasInvalidPercentEscape(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                if (value[i] == '%') {
                    if (i + 2 >= value.length ||
                        value[i + 1].digitToIntOrNull(16) == null ||
                        value[i + 2].digitToIntOrNull(16) == null
                    ) return true
                    i += 3
                } else {
                    i++
                }
            }
            return false
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun decode(value: String): String =
            URLDecoder.decode(value, Charsets.UTF_8.name())

        private fun encodeSource(source: SongSourceIdentity): String = listOf(
            source.kind.name,
            source.nativeId,
            source.backendType.orEmpty(),
            source.serverId.orEmpty(),
            source.platform.orEmpty(),
            source.canonicalUri.orEmpty(),
            source.storageIdentity.orEmpty(),
        ).joinToString(FIELD_SEPARATOR, transform = ::encode)

        private fun decodeSource(value: String): SongSourceIdentity {
            val parts = value.split(FIELD_SEPARATOR).map(::decode)
            require(parts.size == 7) { "Invalid encoded playlist source" }
            return SongSourceIdentity(
                kind = SongSourceKind.valueOf(parts[0]),
                nativeId = parts[1],
                backendType = parts[2].ifEmpty { null },
                serverId = parts[3].ifEmpty { null },
                platform = parts[4].ifEmpty { null },
                canonicalUri = parts[5].ifEmpty { null },
                storageIdentity = parts[6].ifEmpty { null },
            )
        }

        private fun sanitizeUri(value: String): String {
            val queryIndex = value.indexOf('?')
            val fragmentIndex = value.indexOf('#', queryIndex + 1)
            val base = if (queryIndex >= 0) value.substring(0, queryIndex) else value
            val query = if (queryIndex >= 0) {
                value.substring(queryIndex + 1, if (fragmentIndex >= 0) fragmentIndex else value.length)
            } else ""
            val fragment = if (fragmentIndex >= 0) value.substring(fragmentIndex) else ""
            val safeBase = redactUserInfo(base)
            val safeQuery = query.split('&').joinToString("&") { pair ->
                if (pair.isBlank()) return@joinToString pair
                val rawName = pair.substringBefore('=')
                val name = runCatching { URLDecoder.decode(rawName, Charsets.UTF_8.name()) }
                    .getOrDefault(rawName).lowercase()
                if (name in SENSITIVE_PARAMS || name.startsWith("x-amz-")) {
                    "$rawName=__REDACTED__"
                } else pair
            }
            val safeFragment = if (fragment.isNotEmpty()) "#__REDACTED__" else ""
            return safeBase + (if (queryIndex >= 0) "?$safeQuery" else "") + safeFragment
        }

        private fun redactUserInfo(value: String): String {
            val scheme = value.indexOf("://")
            if (scheme < 0) return value
            val authorityStart = scheme + 3
            val pathStart = value.indexOf('/', authorityStart).let { if (it < 0) value.length else it }
            val authority = value.substring(authorityStart, pathStart)
            val at = authority.lastIndexOf('@')
            if (at < 0) return value
            return value.substring(0, authorityStart) + "__REDACTED__@" +
                authority.substring(at + 1) + value.substring(pathStart)
        }

        private val SENSITIVE_PARAMS = setOf(
            "token", "api_key", "apikey", "access_token", "auth", "authorization",
            "password", "passwd", "p", "t", "s", "u", "key", "sig", "signature",
            "credential", "credentials", "session", "sessionid", "sid",
        )
    }
}
