package com.nasmusic.tv.ui.viewmodel

import com.nasmusic.tv.backend.local.SongReadModel
import com.nasmusic.tv.data.model.DuplicateResolution
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateResolutionGuardTest {
    private fun local(id: String) = Song(
        id = id,
        title = "Title-$id",
        path = "file:///music/$id.mp3",
        isLocalSong = true,
        storageType = "INTERNAL",
    )

    private fun key(song: Song): String {
        val source = requireNotNull(SongKeys.bindSource(song))
        return SongKeys.stableKey(song, source)
    }

    @Test
    fun `opposite keep choices never form a hidden cycle`() = runTest {
        val first = requireNotNull(DuplicateResolutionGuard.withKeep(emptyMap(), "A", "B"))
        assertFalse(DuplicateResolutionGuard.hasHiddenKeep(first))
        val second = requireNotNull(DuplicateResolutionGuard.withKeep(first, "B", "A"))
        assertFalse(DuplicateResolutionGuard.hasHiddenKeep(second))
        assertEquals(setOf("B"), second.keys)
        assertEquals(setOf("A"), second["B"]!!.hideKeys)
    }

    @Test
    fun `existing cycle is repaired before write`() = runTest {
        val cyclic = mapOf(
            "A" to DuplicateResolution("A", setOf("B")),
            "B" to DuplicateResolution("B", setOf("A")),
        )
        assertTrue(DuplicateResolutionGuard.hasHiddenKeep(cyclic))
        val repaired = requireNotNull(DuplicateResolutionGuard.withKeep(cyclic, "A", "B"))
        assertFalse(DuplicateResolutionGuard.hasHiddenKeep(repaired))
        assertEquals(setOf("A"), repaired.keys)
        assertEquals(setOf("B"), repaired["A"]!!.hideKeys)
    }

    @Test
    fun `cycle in persisted resolutions keeps both songs visible`() = runTest {
        val a = local("lA")
        val b = local("lB")
        val keyA = key(a)
        val keyB = key(b)
        val resolutions = MutableStateFlow(
            mapOf(
                keyA to DuplicateResolution(keyA, setOf(keyB)),
                keyB to DuplicateResolution(keyB, setOf(keyA)),
            )
        )
        val model = SongReadModel(flowOf(emptyMap()), resolutions, backgroundScope)
        model.awaitLoaded()
        val snapshot = model.snapshots.value
        assertFalse(keyA in snapshot.hiddenKeys)
        assertFalse(keyB in snapshot.hiddenKeys)

        val visible = model.applyAll(listOf(a, b), { SongKeys.bindSource(it) }, snapshot)
        assertEquals(2, visible.size)
        assertNotNull(visible.firstOrNull { it.id == a.id })
        assertNotNull(visible.firstOrNull { it.id == b.id })
    }
}
