package com.nasmusic.tv.player

import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

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
}
