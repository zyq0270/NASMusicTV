package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.QuickPickStat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QuickPickSourceMerger 单测：基础数据 + 历史回填、去重与来源标记。
 */
class QuickPickSourceMergerTest {

    private fun stat(value: String, count: Int = 1) =
        QuickPickStat(category = "ALBUMS", value = value, count = count)

    @Test
    fun `empty base yields all history candidates`() {
        val result = QuickPickSourceMerger.merge(
            base = emptyList<String>(),
            valueOf = { it },
            stats = listOf(stat(" album A "), stat("Album B"))
        )
        assertEquals(2, result.size)
        assertEquals(listOf("album A", "Album B"), result.map { it.value })
        assertEquals(listOf(PickSource.HISTORY, PickSource.HISTORY), result.map { it.source })
        assertEquals(listOf(null, null), result.map { it.item })
    }

    @Test
    fun `base and history merged with dedupe keeping base`() {
        val result = QuickPickSourceMerger.merge(
            base = listOf("Album A", "Album C"),
            valueOf = { it },
            stats = listOf(stat("album a"), stat("Album B"), stat("ALBUM C"))
        )
        assertEquals(3, result.size)  // base 先、history 后（排序由 Ranker 负责）
        assertEquals("Album A", result[0].value)
        assertEquals(PickSource.BASE, result[0].source)
        assertEquals("Album C", result[1].value)
        assertEquals(PickSource.BASE, result[1].source)
        assertEquals("Album B", result[2].value)
        assertEquals(PickSource.HISTORY, result[2].source)
    }

    @Test
    fun `base non-empty still includes history items`() {
        val result = QuickPickSourceMerger.merge(
            base = listOf("A"),
            valueOf = { it },
            stats = listOf(stat("B"), stat("C"))
        )
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.source == PickSource.HISTORY })
    }

    @Test
    fun `blank values are ignored`() {
        val result = QuickPickSourceMerger.merge(
            base = listOf("", "   "),
            valueOf = { it },
            stats = listOf(stat(" "), stat("Valid"))
        )
        assertEquals(1, result.size)
        assertEquals("Valid", result[0].value)
    }
}
