package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.QuickPickStat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QuickPickRanker 单测：热度=快选次数+搜索次数 倒序、同热度按名称、冷启动、limit、队列不参与。
 */
class QuickPickRankerTest {

    private fun candidate(value: String, source: PickSource = PickSource.BASE) =
        QuickPickCandidate(item = null, value = value, source = source)

    private fun stat(value: String, count: Int) =
        QuickPickStat(category = "ALBUMS", value = value, count = count)

    @Test
    fun `heat is pick count plus search count descending`() {
        val candidates = listOf(candidate("A"), candidate("B"), candidate("C"))
        val result = QuickPickRanker.rank(
            candidates = candidates,
            pickStats = listOf(stat("A", 2), stat("B", 5)),
            searchCounts = mapOf("A" to 3, "C" to 1)  // A=5, B=5, C=1
        )
        assertEquals(listOf("A", "B", "C"), result.map { it.value })  // A,B tie -> name asc
    }

    @Test
    fun `tie broken by name natural order case insensitive`() {
        val candidates = listOf(candidate("banana"), candidate("Apple"), candidate("apple"))
        val result = QuickPickRanker.rank(candidates, emptyList())
        assertEquals(listOf("Apple", "apple", "banana"), result.map { it.value })
    }

    @Test
    fun `cold start degrades to name order`() {
        val candidates = listOf(candidate("Z"), candidate("A"), candidate("M"))
        val result = QuickPickRanker.rank(candidates, emptyList())
        assertEquals(listOf("A", "M", "Z"), result.map { it.value })
    }

    @Test
    fun `limit respected and history source preserved`() {
        val candidates = listOf(
            candidate("D"), candidate("B", PickSource.HISTORY), candidate("A"), candidate("C")
        )
        val result = QuickPickRanker.rank(candidates, emptyList(), limit = 2)
        assertEquals(listOf("A", "B"), result.map { it.value })
        assertEquals(PickSource.HISTORY, result[1].source)
    }

    @Test
    fun `empty candidates safe`() {
        assertEquals(
            emptyList<QuickPickCandidate<String>>(),
            QuickPickRanker.rank(emptyList<QuickPickCandidate<String>>(), emptyList())
        )
    }
}
