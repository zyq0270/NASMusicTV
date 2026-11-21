package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「加入队列」只增不删契约的纯函数测试。
 *
 * 被测对象 [QueueAddition.compute] 是全仓入队去重的唯一真相源：
 * PlayerManager.addToQueue(List/Song) 都经它，搜索/发现/专辑/歌手/我的/网盘/遥控
 * 因此共享同一口径。纯函数不依赖 ExoPlayer / Context，可在 JVM 直接跑。
 */
class QueueAdditionTest {

    private fun songs(vararg ids: String) = ids.map { Song(id = it, title = it) }

    @Test
    fun `empty candidates produce empty addition`() {
        assertEquals(emptyList<Song>(), QueueAddition.compute(songs("a"), emptyList()))
    }

    @Test
    fun `new songs keep original order`() {
        val result = QueueAddition.compute(songs("a"), songs("b", "c", "d"))
        assertEquals(listOf("b", "c", "d"), result.map { it.id })
    }

    @Test
    fun `songs already in queue are skipped not removed`() {
        val existing = songs("a", "b", "c")
        val result = QueueAddition.compute(existing, songs("b", "c", "d"))
        assertEquals(listOf("d"), result.map { it.id })
        assertEquals("已入队的歌必须保留，批量加入不得反向移除", listOf("a", "b", "c"), existing.map { it.id })
    }

    @Test
    fun `duplicates within candidates keep only first occurrence`() {
        val result = QueueAddition.compute(emptyList(), songs("a", "b", "a", "c", "b"))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `all duplicates of already queued song are skipped`() {
        val result = QueueAddition.compute(songs("a", "b"), songs("b", "b", "c", "c"))
        assertEquals(listOf("c"), result.map { it.id })
    }

    @Test
    fun `compute does not mutate either input list`() {
        val existing = songs("a", "b").toMutableList()
        val candidates = songs("b", "c", "c").toMutableList()
        QueueAddition.compute(existing, candidates)
        assertEquals(listOf("a", "b"), existing.map { it.id })
        assertEquals(listOf("b", "c", "c"), candidates.map { it.id })
    }
}
