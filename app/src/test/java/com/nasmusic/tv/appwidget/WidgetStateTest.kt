package com.nasmusic.tv.appwidget

import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    @Test
    fun `cold start snapshot has placeholders and disables song actions`() {
        val snapshot = WidgetState.snapshot(null, false, false, PlayMode.SEQUENTIAL, false)

        assertNull(snapshot.title)
        assertNull(snapshot.subtitle)
        assertFalse(snapshot.hasQueue)
        assertFalse(snapshot.isPlaying)
        assertFalse(snapshot.isFavorite)
    }

    @Test
    fun `long metadata stays intact for RemoteViews to ellipsize`() {
        val title = "很长的歌曲标题".repeat(40)
        val song = Song(id = "1", title = title, artist = "艺术家", album = "专辑")

        val snapshot = WidgetState.snapshot(song, true, true, PlayMode.REPEAT_ONE, true)

        assertEquals(title, snapshot.title)
        assertEquals("艺术家 · 专辑", snapshot.subtitle)
        assertTrue(snapshot.hasQueue)
        assertTrue(snapshot.isPlaying)
        assertTrue(snapshot.isFavorite)
    }

    @Test
    fun `blank artist and album produce null subtitle for Elvis fallback`() {
        val song = Song(id = "1", title = "Song", artist = "", album = "   ")

        val snapshot = WidgetState.snapshot(song, false, false, PlayMode.SEQUENTIAL, true)

        assertEquals("Song", snapshot.title)
        assertNull(snapshot.subtitle)
    }

    @Test
    fun `only nonblank metadata appears in subtitle`() {
        val song = Song(id = "1", title = "Song", artist = "Artist", album = "  ")

        val snapshot = WidgetState.snapshot(song, true, false, PlayMode.SEQUENTIAL, true)

        assertEquals("Artist", snapshot.subtitle)
    }

    @Test
    fun `playing and favorite combinations remain independent`() {
        val song = Song(id = "1", title = "Song", artist = "Artist")
        for (playing in listOf(false, true)) {
            for (favorite in listOf(false, true)) {
                val snapshot = WidgetState.snapshot(song, playing, favorite, PlayMode.SHUFFLE, true)
                assertEquals(playing, snapshot.isPlaying)
                assertEquals(favorite, snapshot.isFavorite)
                assertEquals(PlayMode.SHUFFLE, snapshot.playMode)
            }
        }
    }
}
