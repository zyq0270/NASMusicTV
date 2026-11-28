package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateMergeTest {
    private data class Fixture(
        val first: DuplicateSong,
        val second: DuplicateSong,
        val expected: Int,
    )

    private fun entry(
        id: String,
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 180_000L,
        storage: String? = "INTERNAL",
    ): DuplicateSong {
        val song = Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            path = "file:///music/$id.mp3",
            isLocalSong = true,
            storageType = storage,
        )
        val source = SongSourceIdentity(
            kind = SongSourceKind.LOCAL,
            nativeId = id,
            canonicalUri = song.path,
            storageIdentity = storage,
        )
        return DuplicateSong(song, "key:$id", source)
    }

    private fun fixture(
        id: String,
        firstTitle: String = "Song",
        secondTitle: String = "Song",
        firstArtist: String = "Artist",
        secondArtist: String = "Artist",
        firstAlbum: String = "Album",
        secondAlbum: String = "Album",
        firstDuration: Long = 180_000L,
        secondDuration: Long = 180_000L,
        firstStorage: String? = "INTERNAL",
        secondStorage: String? = "INTERNAL",
        expected: Int,
    ) = Fixture(
        first = entry("${id}a", firstTitle, firstArtist, firstAlbum, firstDuration, firstStorage),
        second = entry("${id}b", secondTitle, secondArtist, secondAlbum, secondDuration, secondStorage),
        expected = expected,
    )

    @Test
    fun `ten positive fixtures follow the eight point table`() {
        val fixtures = listOf(
            fixture("p1", expected = 8),
            fixture("p2", secondDuration = 182_000, expected = 8),
            fixture("p3", secondDuration = 178_001, expected = 8),
            fixture("p4", secondAlbum = "Other", expected = 7),
            fixture("p5", secondStorage = "USB", expected = 7),
            fixture("p6", firstTitle = "Ｓｏｎｇ", expected = 8),
            fixture("p7", secondArtist = " artist ", expected = 8),
            fixture("p8", secondAlbum = " album ", expected = 8),
            fixture("p9", firstTitle = " Song ", secondTitle = "Song", expected = 8),
            fixture("p10", firstDuration = 1L, secondDuration = 2_000L, expected = 8),
        )

        fixtures.forEachIndexed { index, item ->
            assertEquals("positive fixture ${index + 1}", item.expected, DuplicateDetector.score(item.first, item.second))
            assertTrue("positive fixture ${index + 1} missing", DuplicateDetector.detect(listOf(item.first, item.second)).size == 1)
        }
    }

    @Test
    fun `ten negative fixtures never reach threshold`() {
        val fixtures = listOf(
            fixture("n1", secondArtist = "Other", expected = 4),
            fixture("n2", secondTitle = "Different", expected = 4),
            fixture("n3", secondDuration = 182_001, expected = 6),
            fixture("n4", firstAlbum = "", secondAlbum = "", secondDuration = 182_001, expected = 5),
            fixture("n5", firstDuration = 0L, expected = 6),
            fixture("n6", secondDuration = 182_001, secondStorage = "USB", expected = 5),
            fixture("n7", secondArtist = "Other", firstAlbum = "", secondAlbum = "", expected = 3),
            fixture("n8", secondDuration = 182_001, firstAlbum = "", secondAlbum = "", secondStorage = "USB", expected = 4),
            fixture("n9", secondTitle = "Different", secondArtist = "Other", expected = 4),
            fixture("n10", secondDuration = 0L, secondStorage = "USB", expected = 5),
        )

        fixtures.forEachIndexed { index, item ->
            assertEquals("negative fixture ${index + 1}", item.expected, DuplicateDetector.score(item.first, item.second))
            assertTrue("negative fixture ${index + 1} leaked", DuplicateDetector.detect(listOf(item.first, item.second)).isEmpty())
        }
    }

    @Test
    fun `golden ordering is score desc then stable keys asc`() {
        val high = fixture("a", firstTitle = "High", secondTitle = "High", expected = 8)
        val tiedC = fixture("c", firstTitle = "Tied C", secondTitle = "Tied C", secondAlbum = "Other", expected = 7)
        val tiedE = fixture("e", firstTitle = "Tied E", secondTitle = "Tied E", secondStorage = "USB", expected = 7)
        val input = listOf(tiedE.second, tiedC.second, high.second, tiedE.first, tiedC.first, high.first)

        val matches = DuplicateDetector.detect(input)

        assertEquals(listOf(8, 7, 7), matches.map { it.score })
        assertEquals(
            listOf("key:aa" to "key:ab", "key:ca" to "key:cb", "key:ea" to "key:eb"),
            matches.map { it.first.stableKey to it.second.stableKey },
        )
    }
}
