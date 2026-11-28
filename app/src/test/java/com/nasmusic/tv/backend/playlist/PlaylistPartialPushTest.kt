package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendCapabilities
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Playlist
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
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaylistPartialPushTest {
    private val config = ServerConfig(id = "server-a", backendType = "jellyfin", baseUrl = "http://nas")

    private fun nasRef(id: String): PlaylistSongRef {
        val song = Song(id = id, title = id)
        val source = SongSourceIdentity(SongSourceKind.NAS, id, "jellyfin", "server-a")
        return PlaylistSongRef(SongKeys.stableKey(song, source), source)
    }

    @Test
    fun `partial failure resumes after last successful index without duplicate add`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        val prefs = mock(AppPreferences::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true))
        `when`(adapter.getPlaylistsResult()).thenReturn(BackendResult.Success(emptyList<Playlist>()))
        `when`(adapter.createPlaylistResult("混合")).thenReturn(BackendResult.Success(Playlist("remote-1", "混合")))
        `when`(adapter.addToPlaylistResult("remote-1", "a")).thenReturn(BackendResult.Success(Unit))
        `when`(adapter.addToPlaylistResult("remote-1", "b"))
            .thenReturn(BackendResult.Failure(BackendError.SERVER), BackendResult.Success(Unit))
        `when`(adapter.addToPlaylistResult("remote-1", "c")).thenReturn(BackendResult.Success(Unit))
        `when`(adapter.getPlaylistSongsResult("remote-1")).thenReturn(BackendResult.Success(emptyList()))

        val sync = BackendPlaylistSync(
            prefs = prefs,
            adapterProvider = { adapter },
            configProvider = { config },
            localStore = object : LocalPlaylistStore {
                override suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist =
                    LocalPlaylist("local", name, songs)
            },
        )
        val network = PlaylistSongRef(
            "v1:net:meting:n1",
            SongSourceIdentity(SongSourceKind.NETWORK, "n1", platform = "meting"),
        )
        val local = PlaylistSongRef(
            "v1:local:USB:file%3A%2F%2Fmusic",
            SongSourceIdentity(SongSourceKind.LOCAL, "l1", canonicalUri = "file://music"),
        )
        val refs = listOf(nasRef("a"), network, nasRef("b"), local, nasRef("c"))

        val first = sync.push("混合", refs) as BackendPlaylistSync.Result.Failure
        assertEquals(BackendError.SERVER, first.error)
        assertEquals(1, first.checkpoint!!.nextIndex)
        assertEquals(
            mapOf(
                0 to BackendPlaylistSync.PushItemStatus.ADDED,
                1 to BackendPlaylistSync.PushItemStatus.SKIPPED,
                2 to BackendPlaylistSync.PushItemStatus.FAILED,
                3 to BackendPlaylistSync.PushItemStatus.SKIPPED,
                4 to BackendPlaylistSync.PushItemStatus.PENDING,
            ),
            first.itemResults.associate { it.index to it.status },
        )
        assertEquals(BackendError.SERVER, first.itemResults.single { it.index == 2 }.error)

        val resumed = sync.resumePush("混合", refs) as BackendPlaylistSync.Result.Success
        assertEquals(2, resumed.value.addedCount)
        assertEquals(
            setOf(BackendPlaylistSync.SkipReason.NETWORK_SONG, BackendPlaylistSync.SkipReason.LOCAL_FILE),
            resumed.value.skipped.map { it.reason }.toSet(),
        )
        verify(adapter).addToPlaylistResult("remote-1", "a")
        verify(adapter, org.mockito.Mockito.times(2)).addToPlaylistResult("remote-1", "b")
        verify(adapter).addToPlaylistResult("remote-1", "c")
        Unit
    }

    @Test
    fun `resume rejects a changed source list before adding songs`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(
            BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true),
        )
        `when`(adapter.getPlaylistsResult()).thenReturn(BackendResult.Success(emptyList<Playlist>()))
        `when`(adapter.createPlaylistResult("变化")).thenReturn(BackendResult.Success(Playlist("remote-1", "变化")))
        `when`(adapter.addToPlaylistResult("remote-1", "a")).thenReturn(BackendResult.Failure(BackendError.SERVER))
        `when`(adapter.addToPlaylistResult("remote-1", "b")).thenReturn(BackendResult.Success(Unit))
        val sync = BackendPlaylistSync(mock(AppPreferences::class.java), { adapter }, { config })

        sync.push("变化", listOf(nasRef("a"), nasRef("b")))
        val changed = sync.resumePush("变化", listOf(nasRef("replacement"), nasRef("b")))
            as BackendPlaylistSync.Result.Failure
        assertEquals(BackendPlaylistSync.FailureReason.CHECKPOINT_MISMATCH, changed.reason)
        assertEquals("remote-1", changed.checkpoint!!.remotePlaylistId)
        verify(adapter, org.mockito.Mockito.never()).addToPlaylistResult("remote-1", "replacement")
        verify(adapter, org.mockito.Mockito.never()).addToPlaylistResult("remote-1", "b")
        Unit
    }

    @Test
    fun `duplicate stable identity is skipped rather than added twice`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(
            BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true),
        )
        `when`(adapter.getPlaylistsResult()).thenReturn(BackendResult.Success(emptyList<Playlist>()))
        `when`(adapter.createPlaylistResult("去重")).thenReturn(BackendResult.Success(Playlist("remote-1", "去重")))
        `when`(adapter.addToPlaylistResult("remote-1", "a")).thenReturn(BackendResult.Success(Unit))
        val sync = BackendPlaylistSync(mock(AppPreferences::class.java), { adapter }, { config })
        val result = sync.push("去重", listOf(nasRef("a"), nasRef("a")))
            as BackendPlaylistSync.Result.Success
        assertEquals(1, result.value.addedCount)
        assertEquals(BackendPlaylistSync.SkipReason.DUPLICATE, result.value.skipped.single().reason)
        verify(adapter).addToPlaylistResult("remote-1", "a")
        Unit
    }

    @Test
    fun `same remote name is a conflict and never creates`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true))
        `when`(adapter.getPlaylistsResult()).thenReturn(
            BackendResult.Success(listOf(Playlist("existing", "同名")))
        )
        val sync = BackendPlaylistSync(mock(AppPreferences::class.java), { adapter }, { config })
        val result = sync.push("同名", listOf(nasRef("a")))
        assertTrue(result is BackendPlaylistSync.Result.Failure)
        assertEquals(
            BackendPlaylistSync.FailureReason.NAME_CONFLICT,
            (result as BackendPlaylistSync.Result.Failure).reason,
        )
        verify(adapter, org.mockito.Mockito.never()).createPlaylistResult("同名")
        Unit
    }
}
