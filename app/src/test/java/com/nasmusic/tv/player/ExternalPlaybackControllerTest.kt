package com.nasmusic.tv.player

import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.appwidget.RemotePlaybackWidget
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaybackControllerTest {
    @Test
    fun `external commands mutate shared player without activity`() = runTest {
        val app = mock(NasMusicApp::class.java)
        val prefs = mock(AppPreferences::class.java)
        val registry = mock(BackendRegistry::class.java)
        val player = mock(PlayerManager::class.java)
        `when`(app.applicationScope).thenReturn(backgroundScope)
        `when`(app.appPreferences).thenReturn(prefs)
        `when`(app.backendRegistry).thenReturn(registry)
        `when`(app.playerManager).thenReturn(player)
        `when`(prefs.networkFavorites).thenReturn(flowOf(emptyList()))
        `when`(registry.getAdapter()).thenReturn(null)
        `when`(player.playPause()).thenReturn(true)

        val controller = ExternalPlaybackController(app, backgroundScope)
        controller.setPlayMode(PlayMode.SHUFFLE)
        controller.togglePlayMode()
        controller.togglePlay()
        controller.next()
        controller.previous()

        // SHUFFLE 是枚举末项 ⇒ 切模式回卷到首项 SEQUENTIAL，保持既有 UI 循环顺序。
        assertEquals(PlayMode.SEQUENTIAL, controller.playMode.value)
        verify(player).applyPlayMode(PlayMode.SHUFFLE)
        verify(player).applyPlayMode(PlayMode.SEQUENTIAL)
        verify(player).playPause()
        verify(player).next(PlayMode.SEQUENTIAL)
        verify(player).previous(PlayMode.SEQUENTIAL)
    }

    @Test
    fun `failed backend favorite mutation never reports success`() = runTest {
        val app = mock(NasMusicApp::class.java)
        val prefs = mock(AppPreferences::class.java)
        val registry = mock(BackendRegistry::class.java)
        val player = mock(PlayerManager::class.java)
        val adapter = mock(BackendAdapter::class.java)
        `when`(app.applicationScope).thenReturn(backgroundScope)
        `when`(app.appPreferences).thenReturn(prefs)
        `when`(app.backendRegistry).thenReturn(registry)
        `when`(app.playerManager).thenReturn(player)
        `when`(prefs.networkFavorites).thenReturn(flowOf(emptyList()))
        `when`(registry.getAdapter()).thenReturn(adapter)
        `when`(adapter.getFavorites()).thenReturn(emptyList())
        `when`(adapter.toggleFavorite("nas-1", false)).thenReturn(false)

        val controller = ExternalPlaybackController(app, backgroundScope)
        val result = controller.toggleFavorite(Song(id = "nas-1", title = "Song"))

        assertNull(result)
        verify(adapter).toggleFavorite("nas-1", false)
        verify(player, never()).applyPlayMode(PlayMode.SHUFFLE)
    }

    @Test
    fun `toggleFavorite emits favorite change exactly once`() = runTest {
        val app = mock(NasMusicApp::class.java)
        val prefs = mock(AppPreferences::class.java)
        val registry = mock(BackendRegistry::class.java)
        val player = mock(PlayerManager::class.java)
        val adapter = mock(BackendAdapter::class.java)
        `when`(app.applicationScope).thenReturn(backgroundScope)
        `when`(app.appPreferences).thenReturn(prefs)
        `when`(app.backendRegistry).thenReturn(registry)
        `when`(app.playerManager).thenReturn(player)
        `when`(prefs.networkFavorites).thenReturn(flowOf(emptyList()))
        `when`(registry.getAdapter()).thenReturn(adapter)
        `when`(adapter.getFavorites()).thenReturn(emptyList())
        `when`(adapter.toggleFavorite("nas-1", false)).thenReturn(true)

        val controller = ExternalPlaybackController(app, backgroundScope)
        val song = Song(id = "nas-1", title = "Song")
        val events = mutableListOf<Pair<Song, Boolean>>()
        val collector = backgroundScope.launch { controller.favoriteChanges.collect { events.add(it) } }
        // SharedFlow 无重放：先让收集器真正订阅，再触发收藏变更。
        runCurrent()

        assertEquals(true, controller.toggleFavorite(song))
        runCurrent()
        collector.cancel()
        assertEquals(listOf(song to true), events)
    }

    @Test
    fun `widget favorite action funnels through the same emit source`() = runTest {
        val app = mock(NasMusicApp::class.java)
        val prefs = mock(AppPreferences::class.java)
        val registry = mock(BackendRegistry::class.java)
        val player = mock(PlayerManager::class.java)
        val adapter = mock(BackendAdapter::class.java)
        val song = Song(id = "nas-1", title = "Song")
        `when`(app.applicationScope).thenReturn(backgroundScope)
        `when`(app.appPreferences).thenReturn(prefs)
        `when`(app.backendRegistry).thenReturn(registry)
        `when`(app.playerManager).thenReturn(player)
        `when`(prefs.networkFavorites).thenReturn(flowOf(emptyList()))
        `when`(registry.getAdapter()).thenReturn(adapter)
        `when`(adapter.getFavorites()).thenReturn(emptyList())
        `when`(adapter.toggleFavorite("nas-1", false)).thenReturn(true)
        `when`(player.playerState).thenReturn(MutableStateFlow(PlayerState(currentSong = song)))

        val controller = ExternalPlaybackController(app, backgroundScope)
        val events = mutableListOf<Pair<Song, Boolean>>()
        val collector = backgroundScope.launch { controller.favoriteChanges.collect { events.add(it) } }
        runCurrent()

        controller.handleExternalAction(RemotePlaybackWidget.ACTION_TOGGLE_FAVORITE)
        runCurrent()
        collector.cancel()
        // widget 分支不再自己 emit，事件数必须仍是 1（否则会出现重复同步）。
        assertEquals(listOf(song to true), events)
    }

    @Test
    fun `notification and media card favorite commands share the single emit source`() {
        val controllerSource = sourceOf("app/src/main/java/com/nasmusic/tv/player/ExternalPlaybackController.kt")
        assertEquals(1, Regex("_favoriteChanges\\.emit").findAll(controllerSource).count())
        assertFalse("_favoriteChanges" in controllerSource.substringAfter("fun handleExternalAction"))

        val serviceSource = sourceOf("app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt")
        val handler = serviceSource.substringAfter("private fun handleToggleFavorite()")
            .substringBefore("private fun handleSleepTimerCycle()")
        assertTrue("externalPlaybackController.toggleFavorite(song)" in handler)
        assertFalse("_favoriteChanges" in handler)
    }

    private fun sourceOf(path: String): String = File(path).let {
        if (it.isFile) it else File("../$path")
    }.readText()
}
