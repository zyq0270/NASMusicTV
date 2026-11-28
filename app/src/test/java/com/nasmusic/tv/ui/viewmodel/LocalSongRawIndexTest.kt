package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.SongKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LocalSongRawIndexTest {
    /** 统计迭代次数：原实现逐首 firstOrNull 会迭代 n 次，索引只应迭代一次。 */
    private class CountingList<T>(private val delegate: List<T>) : List<T> by delegate {
        var scans = 0
            private set

        override fun iterator(): Iterator<T> {
            scans++
            return delegate.iterator()
        }
    }

    private fun local(id: String) = Song(
        id = id,
        title = "Title-$id",
        path = "file:///music/$id.mp3",
        isLocalSong = true,
        storageType = "INTERNAL",
    )

    @Test
    fun `large library lookups scan the list once and stay correct`() {
        val raw = (0 until 5_000).map { local("local_$it") }
        val counted = CountingList(raw)
        val index = LocalSongRawIndex { counted }

        raw.forEach { song ->
            val found = index.rawFor(song.id)
            assertNotNull(found)
            assertSame(song, found)
        }
        assertEquals("5000 次查找不应再逐首扫表", 1, counted.scans)
        assertNull(index.rawFor("missing"))
        assertEquals(1, counted.scans)
    }

    @Test
    fun `index refreshes on new list and keeps first duplicate id`() {
        val first = local("dup")
        val second = first.copy(title = "later")
        val index = LocalSongRawIndex { listOf(first, second) }
        assertSame(first, index.rawFor("dup"))

        val replacement = local("dup").copy(title = "replacement")
        val counted = CountingList(listOf(replacement))
        val refreshing = LocalSongRawIndex { counted }
        assertSame(replacement, refreshing.rawFor("dup"))
        assertEquals(1, counted.scans)
        assertSame(replacement, refreshing.rawFor("dup"))
        assertEquals(1, counted.scans)
    }

    @Test
    fun `metadata override does not change local stable key`() {
        val raw = local("local_1")
        val displayed = raw.copy(title = "new", artist = "new", coverUrl = "https://cdn/c.jpg")
        val rawKey = SongKeys.stableKey(raw, requireNotNull(SongKeys.bindSource(raw)))
        val displayedKey = SongKeys.stableKey(displayed, requireNotNull(SongKeys.bindSource(displayed)))
        assertEquals(rawKey, displayedKey)
    }
}
