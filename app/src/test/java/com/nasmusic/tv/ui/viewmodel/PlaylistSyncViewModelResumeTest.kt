package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendCapabilities
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.backend.playlist.BackendPlaylistSync
import com.nasmusic.tv.backend.playlist.InMemoryPlaylistPushCheckpointStore
import com.nasmusic.tv.backend.playlist.PlaylistExporter
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F-L02：检查点必须按歌单名 + 连接回填，关闭重开对话框后仍可续传。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistSyncViewModelResumeTest {
    private val config = ServerConfig(id = "server-a", backendType = "jellyfin", baseUrl = "http://nas")

    private fun nasRef(id: String): PlaylistSongRef {
        val song = Song(id = id, title = id)
        val source = SongSourceIdentity(SongSourceKind.NAS, id, "jellyfin", "server-a")
        return PlaylistSongRef(SongKeys.stableKey(song, source), source)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `legacy song is bound to the current connection before push`() {
        // F-L05：升级前的存量条目没有编码来源身份，推送入口必须按当前连接补全，
        // 否则只能退化成裸 ID（NO_STABLE_ID），跨服同 ID 会互相碰撞。
        val vm = PlaylistSyncViewModel(
            mock(PlaylistExporter::class.java),
            mock(BackendPlaylistSync::class.java),
        ) { song -> SongSourceIdentity(SongSourceKind.NAS, song.id, "jellyfin", "server-a") }

        val ref = requireNotNull(vm.refForSong(Song(id = "legacy-1", title = "存量")))
        assertEquals(SongSourceKind.NAS, ref.source.kind)
        assertEquals("legacy-1", ref.source.nativeId)
        assertEquals("jellyfin", ref.source.backendType)
        assertEquals("server-a", ref.source.serverId)
        assertTrue(ref.stableKey.isNotBlank())
    }

    @Test
    fun `checkpoint is restored after dialog close and reopen`() = runBlocking {
        val adapter = mock(BackendAdapter::class.java)
        `when`(adapter.backendType).thenReturn("jellyfin")
        `when`(adapter.capabilities).thenReturn(
            BackendCapabilities(playlistRead = true, playlistCreate = true, playlistAdd = true),
        )
        `when`(adapter.getPlaylistsResult()).thenReturn(BackendResult.Success(emptyList<Playlist>()))
        `when`(adapter.createPlaylistResult("断点")).thenReturn(BackendResult.Success(Playlist("remote-1", "断点")))
        `when`(adapter.getPlaylistSongsResult("remote-1")).thenReturn(BackendResult.Success(emptyList()))
        `when`(adapter.addToPlaylistResult("remote-1", "a"))
            .thenReturn(BackendResult.Failure(BackendError.SERVER), BackendResult.Success(Unit))
        `when`(adapter.addToPlaylistResult("remote-1", "b")).thenReturn(BackendResult.Success(Unit))
        val sync = BackendPlaylistSync(
            mock(AppPreferences::class.java),
            { adapter },
            { config },
            checkpointStore = InMemoryPlaylistPushCheckpointStore(),
        )
        val refs = listOf(nasRef("a"), nasRef("b"))
        val vm = PlaylistSyncViewModel(mock(PlaylistExporter::class.java), sync)

        vm.push("断点", refs)
        yield()
        assertTrue(vm.state.value.canResume)
        assertEquals("remote-1", vm.state.value.remotePlaylistId)

        // 关闭对话框：prepare() 只按 pendingCheckpointName 回填，同名歌单仍可续
        vm.prepare()
        yield()
        assertTrue(vm.state.value.canResume)
        assertEquals("断点", vm.state.value.playlistName)

        // 重开（新 VM，模拟跨入口）：给定歌单名即可从 store 回填
        val reopened = PlaylistSyncViewModel(mock(PlaylistExporter::class.java), sync)
        reopened.prepareForPush("断点")
        yield()
        assertTrue(reopened.state.value.canResume)
        assertEquals("remote-1", reopened.state.value.remotePlaylistId)

        // 没有推送历史的 prepare() 不把无关终态恢复成可续
        val unrelated = PlaylistSyncViewModel(mock(PlaylistExporter::class.java), sync)
        unrelated.prepare()
        yield()
        assertFalse(unrelated.state.value.canResume)

        // 续传真正生效：失败点 a 补推，b 不重复添加
        reopened.resumePush("断点", refs)
        yield()
        assertFalse(reopened.state.value.busy)
        assertTrue(reopened.state.value.completed)
        assertEquals(2, reopened.state.value.addedCount)
    }
}
