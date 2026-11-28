package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.util.SongKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistJsonRoundTripTest {
    @Test
    fun `v3 round trip preserves identity and all non volatile song fields`() {
        val song = Song(
            id = "native-1",
            title = "海阔天空",
            artist = "Beyond",
            artistId = "artist-1",
            album = "乐与怒",
            albumId = "album-1",
            coverUrl = "https://secret.example/cover?token=cover-secret",
            streamUrl = "https://secret.example/stream?token=stream-secret",
            durationMs = 301_000L,
            trackNumber = 3,
            discNumber = 2,
            year = 1993,
            genre = "摇滚",
            bitrate = 320,
            resolvedQuality = 999,
            isNetworkSong = false,
            path = null,
            isLocalSong = false,
            storageType = null,
        )
        val source = SongSourceIdentity(
            kind = SongSourceKind.NAS,
            nativeId = song.id,
            backendType = "jellyfin",
            serverId = "server-a",
        )
        val ref = PlaylistSongRef(SongKeys.stableKey(song, source), source)
        val parser = JsonPlaylistParser()
        val json = parser.serializeSongs("歌单", listOf(song), sourceResolver = { ref })

        assertFalse(json.contains("stream-secret"))
        assertFalse(json.contains("cover-secret"))
        assertFalse(json.contains("streamUrl"))
        assertFalse(json.contains("coverUrl"))

        val raw = parser.parse(json, "export.json").single()
        val restored = raw.song!!
        assertEquals(ref, raw.songRef)
        assertEquals(song.copy(streamUrl = null, coverUrl = null), restored)
    }

    @Test
    fun `v2 remains readable but is legacy unbound`() {
        val json = """{"formatVersion":"nasmusic-playlist-v2","playlistName":"旧歌单","songs":[
            {"title":"A","artist":"a","durationSec":12}
        ]}"""
        val raw = JsonPlaylistParser().parse(json, "old.json").single()
        assertEquals(PlaylistSongRef.Binding.LEGACY_UNBOUND, raw.songRef!!.binding)
        assertFalse(raw.songRef!!.isPushableToNas)
        assertNull(raw.song)
    }

    @Test
    fun `explicit legacy ref is honored by compatibility serialize API`() {
        val song = Song(id = "legacy-id", title = "旧条目")
        val ref = PlaylistSongRef.legacy(song)
        val json = JsonPlaylistParser().serialize(
            "旧歌单",
            listOf(SongExportEntry(title = song.title, song = song, ref = ref)),
        )
        val raw = JsonPlaylistParser().parse(json, "legacy.json").single()
        assertEquals(ref, raw.songRef)
        assertEquals(PlaylistSongRef.Binding.LEGACY_UNBOUND, raw.songRef!!.binding)
    }

    @Test
    fun `missing binding in v3 is not trusted as bound`() {
        val json = """{"formatVersion":"nasmusic-playlist-v3","playlistName":"x","songs":[
            {"id":"a","title":"A","stableKey":"v1:nas:s:jellyfin:a",
             "source":{"kind":"NAS","nativeId":"a","serverId":"s","backendType":"jellyfin"}}
        ]}"""
        val raw = JsonPlaylistParser().parse(json, "missing-binding.json").single()
        assertEquals(PlaylistSongRef.Binding.LEGACY_UNBOUND, raw.songRef!!.binding)
        assertFalse(raw.songRef!!.isPushableToNas)
    }

    @Test
    fun `invalid binding value falls back to legacy unbound`() {
        val json = """{"formatVersion":"nasmusic-playlist-v3","playlistName":"x","songs":[
            {"id":"a","title":"A","binding":"TRUST_ME","stableKey":"v1:nas:s:jellyfin:a",
             "source":{"kind":"NAS","nativeId":"a","serverId":"s","backendType":"jellyfin"}}
        ]}"""
        val raw = JsonPlaylistParser().parse(json, "invalid-binding.json").single()
        assertEquals(PlaylistSongRef.Binding.LEGACY_UNBOUND, raw.songRef!!.binding)
    }
}

class PlaylistSourceIdentityTest {
    @Test
    fun `same native id on two servers has different stable keys`() {
        val song = Song(id = "same", title = "x")
        fun ref(server: String, backend: String): PlaylistSongRef {
            val source = SongSourceIdentity(SongSourceKind.NAS, "same", backend, server)
            return PlaylistSongRef(SongKeys.stableKey(song, source), source)
        }
        val a = ref("server-a", "jellyfin")
        val b = ref("server-b", "jellyfin")
        assertNotEquals(a.stableKey, b.stableKey)
        assertTrue(a.isBoundTo("server-a", "jellyfin"))
        assertFalse(a.isBoundTo("server-b", "jellyfin"))
        assertFalse(a.isBoundTo("server-a", "navidrome"))
    }

    @Test
    fun `local URI credentials are removed from both source and stable key`() {
        val song = Song(
            id = "local-secret",
            title = "本地",
            isLocalSong = true,
            path = "https://alice:password@example.com/music?sig=signature-secret&x=1#access_token=fragment-secret",
            storageType = "USB",
        )
        val ref = PlaylistSongRef.fromSong(song)!!
        assertFalse(ref.source.canonicalUri!!.contains("password"))
        assertFalse(ref.source.canonicalUri!!.contains("signature-secret"))
        assertFalse(ref.source.canonicalUri!!.contains("fragment-secret"))
        assertFalse(ref.stableKey.contains("password"))
        assertFalse(ref.stableKey.contains("signature-secret"))
        assertFalse(ref.stableKey.contains("fragment-secret"))
        assertTrue(ref.stableKey.contains("__REDACTED__"))

        val json = JsonPlaylistParser().serializeSongs("本地", listOf(song))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("signature-secret"))
        assertFalse(json.contains("fragment-secret"))
        val decoded = PlaylistSongRef.decodeFromSong(
            PlaylistSongRef.restoreSong(JsonPlaylistParser().parse(json, "local.json").single().songRef, song),
        )!!
        assertEquals(ref.stableKey, decoded.stableKey)
    }

    @Test
    fun `legacy empty stable key survives local binding round trip`() {
        val song = Song(id = "legacy-id", title = "old")
        val ref = PlaylistSongRef.legacy(song)
        val decoded = PlaylistSongRef.decodeFromSong(PlaylistSongRef.bindToSong(song, ref))
        assertEquals(ref, decoded)
        assertEquals(PlaylistSongRef.Binding.LEGACY_UNBOUND, decoded!!.binding)
        assertFalse(decoded.isPushableToNas)
    }

    @Test
    fun `network binding preserves native playback resolution fields`() {
        val song = Song(
            id = "network-song",
            title = "网络歌曲",
            isNetworkSong = true,
            networkSource = "meting",
            networkId = "native-network-id",
            networkPlatform = "netease",
        )
        val ref = PlaylistSongRef.fromSong(song)!!

        val bound = PlaylistSongRef.bindToSong(song, ref)

        assertEquals(song.networkSource, bound.networkSource)
        assertEquals(song.networkId, bound.networkId)
        assertEquals(song.networkPlatform, bound.networkPlatform)
        assertEquals(ref, PlaylistSongRef.fromSong(bound))
    }

    @Test
    fun `malformed percent escape in reserved stable key is rejected safely`() {
        val source = SongSourceIdentity(SongSourceKind.NAS, "a", "jellyfin", "server")
        val song = PlaylistSongRef.bindToSong(Song(id = "a", title = "A"), PlaylistSongRef("bad%", source))
            .let { it.copy(networkId = "nasmusic-playlist-key-v1:bad%25ZZ|BOUND") }
        assertNull(PlaylistSongRef.decodeFromSong(song))
    }
}
