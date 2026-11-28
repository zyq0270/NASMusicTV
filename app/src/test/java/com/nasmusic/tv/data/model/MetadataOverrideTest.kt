package com.nasmusic.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataOverrideTest {
    @Test
    fun `null keeps original while empty string explicitly clears`() {
        val original = Song(id = "l1", title = "Old", artist = "Artist", album = "Album")
        val override = MetadataOverride(
            stableKey = "v1:local:test",
            title = null,
            artist = "",
            album = "New Album",
        )

        val changed = original.copy(
            title = override.title ?: original.title,
            artist = override.artist ?: original.artist,
            album = override.album ?: original.album,
        )

        assertEquals("Old", changed.title)
        assertEquals("", changed.artist)
        assertEquals("New Album", changed.album)
        assertEquals("Artist", original.artist)
    }

    @Test
    fun `data class copy does not mutate persisted value`() {
        val override = MetadataOverride(stableKey = "k", title = "A")
        val changed = override.copy(title = "B")

        assertNotSame(override, changed)
        assertEquals("A", override.title)
        assertEquals("B", changed.title)
        assertNull(override.year)
    }
}
