package com.nasmusic.tv.util

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import java.net.URLEncoder

object SongKeys {
    const val SCHEMA_VERSION = 1

    fun stableKey(song: Song, source: SongSourceIdentity): String {
        require(source.nativeId.isNotBlank()) { "nativeId must not be blank" }
        return when (source.kind) {
            SongSourceKind.LOCAL -> {
                val uri = source.canonicalUri ?: song.path ?: song.streamUrl
                require(!uri.isNullOrBlank()) { "local song requires canonicalUri/path/streamUrl" }
                val storage = source.storageIdentity ?: song.storageType ?: "unknown"
                "v$SCHEMA_VERSION:local:${encode(storage)}:${encode(uri)}"
            }
            SongSourceKind.NAS -> sourcePrefix(source) + encode(source.nativeId)
            SongSourceKind.NETWORK -> {
                val platform = source.platform ?: song.networkPlatform ?: song.networkSource ?: "unknown"
                "v$SCHEMA_VERSION:net:${encode(platform)}:${encode(source.nativeId)}"
            }
        }
    }

    fun bindSource(song: Song, source: SongSourceIdentity? = null): SongSourceIdentity? {
        val bound = if (source != null) {
            if (source.nativeId.isBlank()) source.copy(nativeId = song.id) else source
        } else {
            when {
                song.isLocalSong -> {
                    val uri = song.path ?: song.streamUrl ?: return null
                    SongSourceIdentity(
                        kind = SongSourceKind.LOCAL,
                        nativeId = song.id,
                        canonicalUri = uri,
                        storageIdentity = song.storageType,
                    )
                }
                song.isNetworkSong -> SongSourceIdentity(
                    kind = SongSourceKind.NETWORK,
                    nativeId = song.networkId ?: song.id,
                    platform = song.networkPlatform ?: song.networkSource,
                )
                else -> null
            }
        }
        return bound?.takeIf { it.nativeId.isNotBlank() }
    }

    /** NAS 身份前缀，供尚未加载完成的分页计算该服务器隐藏项数量。 */
    fun nasSourcePrefix(serverId: String, backendType: String): String {
        require(serverId.isNotBlank()) { "NAS song requires serverId" }
        require(backendType.isNotBlank()) { "NAS song requires backendType" }
        return "v$SCHEMA_VERSION:nas:${encode(serverId)}:${encode(backendType)}:"
    }

    private fun sourcePrefix(source: SongSourceIdentity): String {
        require(!source.serverId.isNullOrBlank()) { "NAS song requires serverId" }
        require(!source.backendType.isNullOrBlank()) { "NAS song requires backendType" }
        return nasSourcePrefix(source.serverId, source.backendType)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
