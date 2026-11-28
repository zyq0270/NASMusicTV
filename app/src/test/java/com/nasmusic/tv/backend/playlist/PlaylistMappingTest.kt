package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendCapabilities
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlaylistMappingTest {
    private val config = ServerConfig(id = "server-a", backendType = "jellyfin", baseUrl = "http://nas")

    @Test
    fun `pull binds full songs to current server and skips unstable entries`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        val prefs = mock(AppPreferences::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(BackendCapabilities(playlistRead = true))
        `when`(adapter.getPlaylistSongsResult("remote")).thenReturn(
            BackendResult.Success(listOf(Song(id = "a", title = "A", durationMs = 1000), Song(id = "", title = "bad")))
        )
        `when`(prefs.getLocalPlaylists()).thenReturn(emptyList())
        var stored: LocalPlaylist? = null
        val sync = BackendPlaylistSync(
            prefs,
            { adapter },
            { config },
            localStore = object : LocalPlaylistStore {
                override suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist {
                    stored = LocalPlaylist("local", name, songs)
                    return stored!!
                }
            },
        )
        val result = sync.pull("remote", "拉取") as BackendPlaylistSync.Result.Success
        assertEquals(1, result.value.boundCount)
        assertEquals(BackendPlaylistSync.SkipReason.NO_STABLE_ID, result.value.skipped.single().reason)
        val ref = PlaylistSongRef.decodeFromSong(stored!!.songs.single())!!
        assertEquals("server-a", ref.source.serverId)
        assertEquals("jellyfin", ref.source.backendType)
    }

    @Test
    fun `switching backend rejects push before any remote write`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("navidrome")
        `when`(adapter.capabilities).thenReturn(
            BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true)
        )
        val song = Song(id = "same", title = "x")
        val source = SongSourceIdentity(SongSourceKind.NAS, "same", "jellyfin", "server-a")
        val ref = PlaylistSongRef(SongKeys.stableKey(song, source), source)
        val sync = BackendPlaylistSync(mock(AppPreferences::class.java), { adapter }, { config })
        val result = sync.push("x", listOf(ref)) as BackendPlaylistSync.Result.Failure
        assertEquals(BackendPlaylistSync.FailureReason.SOURCE_MISMATCH, result.reason)
        assertEquals(BackendError.CONFLICT, result.error)
    }

    @Test
    fun `subsonic write capability is disabled`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("subsonic")
        `when`(adapter.capabilities).thenReturn(BackendCapabilities(playlistRead = true))
        val sync = BackendPlaylistSync(
            mock(AppPreferences::class.java),
            { adapter },
            { config.copy(backendType = "subsonic") },
        )
        val result = sync.push("x", emptyList()) as BackendPlaylistSync.Result.Failure
        assertEquals(BackendError.UNSUPPORTED, result.error)
        assertEquals(BackendPlaylistSync.FailureReason.UNSUPPORTED, result.reason)
    }
}
