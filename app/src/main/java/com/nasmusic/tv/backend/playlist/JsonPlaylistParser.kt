package com.nasmusic.tv.backend.playlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.util.AppLog

/**
 * 本应用自导 JSON 解析器。
 *
 * v3 保存稳定 key、来源身份与关键 Song 字段；v2 仍可读，但导入后标记为
 * LEGACY_UNBOUND，不能直接推回 NAS。streamUrl / coverUrl 等易失效字段不保存。
 */
class JsonPlaylistParser : PlaylistParser, PlaylistNameProvider {

    companion object {
        const val FORMAT_VERSION = "nasmusic-playlist-v3"
        const val LEGACY_FORMAT_VERSION = "nasmusic-playlist-v2"
        private const val TAG = "JsonPlaylistParser"
    }

    override var innerName: String? = null
        private set

    var lastParseError: Boolean = false
        private set

    /** 兼容旧调用方；输出始终升级为 v3。 */
    fun serialize(
        playlistName: String,
        songs: List<SongExportEntry>,
        createdAt: Long = System.currentTimeMillis(),
    ): String = serializeEntries(
        playlistName = playlistName,
        entries = songs.map { entry ->
            val song = entry.toSong()
            song to (entry.ref ?: PlaylistSongRef.fromSong(song))
        },
        createdAt = createdAt,
    )

    fun serializeSongs(
        playlistName: String,
        songs: List<Song>,
        sourceResolver: (Song) -> PlaylistSongRef? = { PlaylistSongRef.fromSong(it) },
        createdAt: Long = System.currentTimeMillis(),
    ): String = serializeEntries(
        playlistName = playlistName,
        entries = songs.map { it to sourceResolver(it) },
        createdAt = createdAt,
    )

    private fun serializeEntries(
        playlistName: String,
        entries: List<Pair<Song, PlaylistSongRef?>>,
        createdAt: Long,
    ): String {
        val root = JsonObject()
        root.addProperty("formatVersion", FORMAT_VERSION)
        root.addProperty("playlistName", playlistName)
        root.addProperty("createdAt", createdAt)
        val arr = JsonArray()
        entries.forEach { (song, ref) -> arr.add(toJson(song, ref)) }
        root.add("songs", arr)
        return root.toString()
    }

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        if (headBytes.isEmpty() || !fileName.endsWith(".json", ignoreCase = true)) return false
        val head = PlaylistParsers.decode(headBytes)
        return head.contains("\"formatVersion\"") &&
            (head.contains(FORMAT_VERSION) || head.contains(LEGACY_FORMAT_VERSION))
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        lastParseError = false
        val root: JsonObject = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: JsonParseException) {
            lastParseError = true
            return emptyList()
        } catch (e: IllegalStateException) {
            lastParseError = true
            AppLog.w(TAG, "parse: root is not a JSON object (${e.message})")
            return emptyList()
        }
        val version = root.string("formatVersion")
        if (version != FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) {
            lastParseError = true
            return emptyList()
        }
        root.string("playlistName")?.trim()?.takeIf { it.isNotEmpty() }?.let { innerName = it }

        val songs = root.get("songs") as? JsonArray ?: return emptyList()
        val entries = mutableListOf<RawSongEntry>()
        var index = 0
        for (el in songs) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val title = obj.string("title")?.trim().orEmpty()
            if (title.isEmpty()) continue
            val artist = obj.string("artist")?.trim().orEmpty()
            val album = obj.string("album")?.trim()?.takeIf { it.isNotEmpty() }
            val durationSec = obj.int("durationSec")
            val base = Song(
                id = obj.string("id") ?: obj.string("nativeId") ?: "",
                title = title,
                artist = artist,
                artistId = obj.string("artistId"),
                album = album.orEmpty(),
                albumId = obj.string("albumId"),
                durationMs = obj.long("durationMs") ?: ((durationSec ?: 0) * 1000L),
                trackNumber = obj.int("trackNumber") ?: 0,
                discNumber = obj.int("discNumber") ?: 1,
                year = obj.int("year"),
                genre = obj.string("genre"),
                bitrate = obj.int("bitrate") ?: 0,
                resolvedQuality = obj.int("resolvedQuality") ?: 0,
                isNetworkSong = obj.boolean("isNetworkSong") ?: false,
                networkSource = obj.string("networkSource"),
                networkId = obj.string("networkId"),
                networkPlatform = obj.string("networkPlatform"),
                path = obj.string("path") ?: obj.string("portableUri"),
                isLocalSong = obj.boolean("isLocalSong") ?: false,
                storageType = obj.string("storageType"),
            )
            if (version == LEGACY_FORMAT_VERSION) {
                entries += RawSongEntry(
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    originalIndex = index++,
                    songRef = PlaylistSongRef.legacy(base.copy(id = base.id.ifBlank { "legacy_$index" })),
                )
                continue
            }

            val sourceElement = obj.get("source")
            val source = parseSource(sourceElement as? JsonObject)
            if (sourceElement != null && !sourceElement.isJsonObject) lastParseError = true
            val stableKey = obj.string("stableKey").orEmpty()
            val rawBinding = obj.string("binding")
            val binding = runCatching {
                PlaylistSongRef.Binding.valueOf(
                    rawBinding ?: PlaylistSongRef.Binding.LEGACY_UNBOUND.name,
                )
            }.getOrElse { PlaylistSongRef.Binding.LEGACY_UNBOUND }
            if ((source != null && stableKey.isBlank() && binding == PlaylistSongRef.Binding.BOUND) ||
                (source == null && stableKey.isNotBlank())
            ) lastParseError = true
            val ref = if (source != null && stableKey.isNotBlank()) {
                PlaylistSongRef(stableKey, source, binding).encodeForLocalStorage()
            } else null
            val restored = PlaylistSongRef.restoreSong(ref, base, bindToStorage = false)
            entries += RawSongEntry(
                title = restored.title,
                artist = restored.artist,
                album = restored.album.ifEmpty { null },
                durationSec = durationSec ?: (restored.durationMs / 1000L).takeIf { it > 0 }?.toInt(),
                sourceHint = when (source?.kind) {
                    SongSourceKind.NETWORK -> SongSourceHint.NETWORK_METING
                    SongSourceKind.LOCAL -> SongSourceHint.LOCAL_FILE
                    SongSourceKind.NAS -> SongSourceHint.NAS
                    null -> SongSourceHint.NONE
                },
                originalIndex = index++,
                song = restored,
                songRef = ref ?: PlaylistSongRef.legacy(restored),
            )
        }
        if (lastParseError) return emptyList()
        return entries
    }

    private fun toJson(song: Song, ref: PlaylistSongRef?): JsonObject {
        val obj = JsonObject()
        val safeRef = ref?.encodeForLocalStorage()
        obj.addProperty("stableKey", safeRef?.stableKey.orEmpty())
        safeRef?.source?.let { obj.add("source", sourceToJson(it)) }
        obj.addProperty("binding", safeRef?.binding?.name ?: PlaylistSongRef.Binding.LEGACY_UNBOUND.name)
        obj.addProperty("id", song.id)
        obj.addProperty("title", song.title)
        if (song.artist.isNotBlank()) obj.addProperty("artist", song.artist)
        song.artistId?.let { obj.addProperty("artistId", it) }
        obj.addProperty("album", song.album)
        song.albumId?.let { obj.addProperty("albumId", it) }
        obj.addProperty("durationMs", song.durationMs)
        obj.addProperty("durationSec", (song.durationMs / 1000L).toInt())
        obj.addProperty("trackNumber", song.trackNumber)
        obj.addProperty("discNumber", song.discNumber)
        song.year?.let { obj.addProperty("year", it) }
        song.genre?.let { obj.addProperty("genre", it) }
        obj.addProperty("bitrate", song.bitrate)
        obj.addProperty("resolvedQuality", song.resolvedQuality)
        obj.addProperty("isNetworkSong", song.isNetworkSong)
        song.networkSource?.takeUnless { it.startsWith("nasmusic-playlist-source-v1:") }
            ?.let { obj.addProperty("networkSource", it) }
        song.networkId?.takeUnless { it.startsWith("nasmusic-playlist-key-v1:") }
            ?.let { obj.addProperty("networkId", it) }
        song.networkPlatform?.let { obj.addProperty("networkPlatform", it) }
        song.storageType?.let { obj.addProperty("storageType", it) }
        val localUri = safeRef?.source?.canonicalUri
            ?: song.path.takeIf { song.isLocalSong }
        localUri?.let { uri ->
            val sanitized = M3uPlaylistSerializer().sanitizeUri(uri).first
            obj.addProperty("path", sanitized)
            obj.addProperty("portableUri", sanitized)
        }
        return obj
    }

    private fun sourceToJson(source: SongSourceIdentity): JsonObject = JsonObject().apply {
        addProperty("kind", source.kind.name)
        addProperty("nativeId", source.nativeId)
        source.backendType?.let { addProperty("backendType", it) }
        source.serverId?.let { addProperty("serverId", it) }
        source.platform?.let { addProperty("platform", it) }
        source.canonicalUri?.let { addProperty("canonicalUri", it) }
        source.storageIdentity?.let { addProperty("storageIdentity", it) }
    }

    private fun parseSource(obj: JsonObject?): SongSourceIdentity? {
        obj ?: return null
        val kind = runCatching { SongSourceKind.valueOf(obj.string("kind").orEmpty()) }.getOrNull()
        if (kind == null) {
            lastParseError = true
            return null
        }
        val nativeId = obj.string("nativeId")?.takeIf { it.isNotBlank() }
        if (nativeId == null) {
            lastParseError = true
            return null
        }
        return SongSourceIdentity(
            kind = kind,
            nativeId = nativeId,
            backendType = obj.string("backendType"),
            serverId = obj.string("serverId"),
            platform = obj.string("platform"),
            canonicalUri = obj.string("canonicalUri"),
            storageIdentity = obj.string("storageIdentity"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        string(name)?.toIntOrNull()

    private fun JsonObject.long(name: String): Long? =
        string(name)?.toLongOrNull()

    private fun JsonObject.boolean(name: String): Boolean? =
        string(name)?.toBooleanStrictOrNull()
}

/** 导出条目；旧字段保留以兼容既有测试与调用方。 */
data class SongExportEntry(
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int? = null,
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,
    val networkId: String? = null,
    val song: Song? = null,
    val ref: PlaylistSongRef? = null,
) {
    fun toSong(): Song = song ?: Song(
        id = networkId ?: "export_${title.hashCode()}_${artist.hashCode()}",
        title = title,
        artist = artist,
        album = album.orEmpty(),
        durationMs = (durationSec ?: 0) * 1000L,
        isNetworkSong = isNetworkSong,
        networkSource = networkSource,
        networkId = networkId,
    )
}
