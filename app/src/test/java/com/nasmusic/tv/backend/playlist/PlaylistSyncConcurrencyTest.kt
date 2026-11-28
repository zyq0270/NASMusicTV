package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendCapabilities
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlaylistSyncConcurrencyTest {
    @Test
    fun `second concurrent push returns busy immediately`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(
            BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true)
        )
        `when`(adapter.getPlaylistsResult()).thenReturn(BackendResult.Success(emptyList<Playlist>()))
        `when`(adapter.createPlaylistResult("并发")).thenAnswer {
            runBlocking {
                entered.complete(Unit)
                release.await()
            }
            BackendResult.Success(Playlist("remote", "并发"))
        }
        `when`(adapter.addToPlaylistResult("remote", "a")).thenReturn(BackendResult.Success(Unit))
        val sync = BackendPlaylistSync(
            mock(AppPreferences::class.java),
            { adapter },
            { ServerConfig(id = "s", backendType = "jellyfin", baseUrl = "http://nas") },
        )

        // F-L01 起空 pushable 会短路，不再调用 createPlaylistResult；用一首可推送歌曲
        // 才能进入创建分支并卡住 mutex，维持本用例的并发竞态前提。
        val song = Song(id = "a", title = "a")
        val source = SongSourceIdentity(SongSourceKind.NAS, "a", "jellyfin", "s")
        val refs = listOf(PlaylistSongRef(SongKeys.stableKey(song, source), source))
        val first = async(Dispatchers.Default) { sync.push("并发", refs) }
        entered.await()
        val second = sync.push("并发", refs)
        assertTrue(second is BackendPlaylistSync.Result.Failure)
        assertEquals(
            BackendPlaylistSync.FailureReason.BUSY,
            (second as BackendPlaylistSync.Result.Failure).reason,
        )
        release.complete(Unit)
        assertTrue(first.await() is BackendPlaylistSync.Result.Success)
    }
}
