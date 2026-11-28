package com.nasmusic.tv.player

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.data.model.ReplayGainInfo
import com.nasmusic.tv.data.model.ReplayGainSource
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayGainStateTest {
    private fun fixture(): Pair<ReplayGainController, ExoPlayer> {
        val prefs = Mockito.mock(RoadmapPreferences::class.java)
        Mockito.`when`(prefs.replayGainEnabledFlow()).thenReturn(MutableStateFlow(false))
        Mockito.`when`(prefs.replayGainMapFlow()).thenReturn(MutableStateFlow(emptyMap()))
        Mockito.`when`(prefs.seamlessModeFlow()).thenReturn(MutableStateFlow(com.nasmusic.tv.data.model.SeamlessMode.OFF))
        val controller = ReplayGainController(RuntimeEnvironment.getApplication(), prefs)
        return controller to Mockito.mock(ExoPlayer::class.java)
    }

    @Test fun `prepare next never commits or writes current player`() {
        val (controller, player) = fixture()
        controller.commit("current", player, 1f)
        Mockito.clearInvocations(player)
        controller.prepareFor(song("next"), identity("next"))
        Mockito.verifyNoInteractions(player)
        assertEquals("current", controller.javaClass.getDeclaredField("assignments").let {
            it.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (it.get(controller) as java.util.WeakHashMap<ExoPlayer, ReplayGainController.Assignment>)[player]?.songKey
        })
        controller.release()
    }

    @Test fun `per player keys retain independent gains`() {
        val (controller, main) = fixture()
        val secondary = Mockito.mock(ExoPlayer::class.java)
        val enabled = ReplayGainController::class.java.getDeclaredField("enabled")
        enabled.isAccessible = true
        enabled.setBoolean(controller, true)
        val field = ReplayGainController::class.java.getDeclaredField("gains")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val gains = field.get(controller) as MutableMap<String, ReplayGainInfo>
        gains["quiet"] = ReplayGainInfo(-6f, ReplayGainSource.TAG_ID3)
        gains["loud"] = ReplayGainInfo(0f, ReplayGainSource.TAG_ID3)
        controller.commit("quiet", main, 1f)
        controller.commit("loud", secondary, 1f)
        val captor = ArgumentCaptor.forClass(Float::class.java)
        Mockito.verify(main).volume = captor.capture()
        assertEquals(0.5011872f, captor.value, 0.0001f)
        Mockito.verify(secondary).volume = 1f
        controller.release()
    }

    @Test fun `seek replacement and rebuild commit fresh generation`() {
        val (controller, player) = fixture()
        controller.commit("a", player, 1f)
        controller.commit("b", player, 1f)
        controller.writeVolume(player, 0.5f, fadeFactor = 0.5f)
        val captor = ArgumentCaptor.forClass(Float::class.java)
        Mockito.verify(player, Mockito.atLeastOnce()).volume = captor.capture()
        assertEquals(0.25f, captor.value, 0f)
        controller.release()
    }

    @Test fun `short fade uses single player and completes at 200ms`() {
        val main = Mockito.mock(ExoPlayer::class.java)
        val fade = ShortFadeIn()
        var completed = false
        val captor = ArgumentCaptor.forClass(Float::class.java)
        fade.start(main, { f -> main.volume = f }, onComplete = { completed = true })
        // Mockito 的 getter 不反映 setter ⇒ 用捕获的 setVolume 实参判定。
        Mockito.verify(main).volume = captor.capture()
        assertEquals(0f, captor.value, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        Mockito.verify(main, Mockito.atLeast(2)).volume = captor.capture()
        assertEquals(1f, captor.value, 0f)
        assertTrue(completed)
        fade.cancel()
    }

    private fun song(id: String) = Song(id = "local_$id", title = id, path = "/music/$id.mp3", streamUrl = "/music/$id.mp3", isLocalSong = true)
    private fun identity(id: String) = SongSourceIdentity(SongSourceKind.LOCAL, id, canonicalUri = "/music/$id.mp3")
}
