package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.DuplicateResolution
import com.nasmusic.tv.data.model.MetadataOverride
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongReadModelTest {
    private fun local(id: String = "l1") = Song(
        id = id,
        title = "Title-$id",
        artist = "Artist",
        album = "Album",
        year = 2000,
        genre = "Pop",
        coverUrl = "content://old",
        path = "file:///music/$id.mp3",
        isLocalSong = true,
        storageType = "INTERNAL",
    )

    private fun key(song: Song): String {
        val source = requireNotNull(SongKeys.bindSource(song))
        return SongKeys.stableKey(song, source)
    }

    @Test
    fun `local override applies nullable and empty semantics`() = runTest {
        val song = local()
        val stableKey = key(song)
        val model = SongReadModel(
            overrides = flowOf(mapOf(stableKey to MetadataOverride(stableKey = stableKey, artist = "", coverRef = "cover://new"))),
            resolutions = flowOf(emptyMap()),
            scope = backgroundScope,
        )
        model.awaitLoaded()

        val changed = requireNotNull(model.apply(song, SongKeys.bindSource(song)))

        assertEquals("Title-l1", changed.title)
        assertEquals("", changed.artist)
        assertEquals("cover://new", changed.coverUrl)
        assertEquals("content://old", song.coverUrl)
    }

    @Test
    fun `duplicate resolution hides only display copy`() = runTest {
        val song = local()
        val stableKey = key(song)
        val model = SongReadModel(
            overrides = flowOf(emptyMap()),
            resolutions = flowOf(mapOf("keep" to DuplicateResolution("keep", setOf(stableKey)))),
            scope = backgroundScope,
        )
        model.awaitLoaded()

        assertNull(model.apply(song, SongKeys.bindSource(song)))
        assertSame(song, model.applyIncludingHidden(song, SongKeys.bindSource(song)))
    }

    @Test
    fun `explicit snapshot keeps a batch atomic and can retain hidden queue entries`() = runTest {
        val first = local("first")
        val second = local("second")
        val firstKey = key(first)
        val secondKey = key(second)
        val before = SongReadSnapshot(
            loaded = true,
            overrides = mapOf(
                firstKey to MetadataOverride(stableKey = firstKey, title = "Before"),
                secondKey to MetadataOverride(stableKey = secondKey, title = "Before"),
            ),
            hiddenKeys = setOf(secondKey),
        )
        val after = before.copy(
            overrides = mapOf(
                firstKey to MetadataOverride(stableKey = firstKey, title = "After"),
                secondKey to MetadataOverride(stableKey = secondKey, title = "After"),
            ),
        )
        val model = SongReadModel(flowOf(before.overrides), flowOf(emptyMap()), backgroundScope)
        model.awaitLoaded()
        val sourceFor: (Song) -> SongSourceIdentity? = { SongKeys.bindSource(it) }

        assertEquals(
            listOf("Before"),
            model.applyAll(listOf(first, second), sourceFor, before).map { it.title },
        )
        assertEquals(
            listOf("After"),
            model.applyAll(listOf(first, second), sourceFor, after).map { it.title },
        )
        assertEquals(
            listOf("After", "After"),
            model.applyAllIncludingHidden(listOf(first, second), sourceFor, after).map { it.title },
        )
    }

    @Test
    fun `unbound NAS source is returned unchanged`() = runTest {
        val song = Song(id = "same-native-id", title = "NAS", artist = "A")
        val model = SongReadModel(
            overrides = flowOf(mapOf("v1:nas:server:navidrome:same-native-id" to MetadataOverride("v1:nas:server:navidrome:same-native-id", title = "Changed"))),
            resolutions = flowOf(emptyMap()),
            scope = backgroundScope,
        )
        model.awaitLoaded()

        assertSame(song, model.apply(song, SongSourceIdentity(SongSourceKind.NAS, nativeId = song.id)))
    }

    @Test
    fun `bound NAS source uses server backend and native identity`() = runTest {
        val song = Song(id = "native-1", title = "Before", artist = "A")
        val source = SongSourceIdentity(
            kind = SongSourceKind.NAS,
            nativeId = song.id,
            backendType = "navidrome",
            serverId = "server-1",
        )
        val stableKey = SongKeys.stableKey(song, source)
        val model = SongReadModel(
            flowOf(mapOf(stableKey to MetadataOverride(stableKey, title = "After"))),
            flowOf(emptyMap()),
            backgroundScope,
        )
        model.awaitLoaded()

        assertEquals("After", model.apply(song, source)?.title)
        assertSame(song, model.apply(song, null))
    }

    @Test
    fun `network source uses platform identity and reacts to override flow`() = runTest {
        val song = Song(
            id = "network-row",
            title = "Before",
            artist = "A",
            isNetworkSong = true,
            networkId = "native-1",
            networkPlatform = "netease",
        )
        val source = requireNotNull(SongKeys.bindSource(song))
        val stableKey = SongKeys.stableKey(song, source)
        val overrides = MutableStateFlow<Map<String, MetadataOverride>>(emptyMap())
        val model = SongReadModel(overrides, flowOf(emptyMap()), backgroundScope)
        model.awaitLoaded()
        assertEquals("Before", model.apply(song, source)?.title)

        overrides.value = mapOf(stableKey to MetadataOverride(stableKey, title = "After"))
        runCurrent()

        assertEquals("After", model.apply(song, source)?.title)
    }
}
