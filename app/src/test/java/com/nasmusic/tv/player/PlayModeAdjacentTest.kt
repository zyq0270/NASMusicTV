package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [adjacentIndex] 的穷举边界（2026-11-22 队列页修复 P2）。
 *
 * 被测对象与 PlayerManager.next / previous 的模式分支逐条对齐：
 * 预判目标必须与真正执行切歌的目标一致，否则空 URL 解析链会播错歌。
 */
class PlayModeAdjacentTest {

    @Test
    fun `中间项顺序模式走相邻下标`() {
        for (mode in listOf(PlayMode.SEQUENTIAL, PlayMode.REPEAT_ONE, PlayMode.REPEAT_ALL)) {
            assertEquals(3, adjacentIndex(5, 2, mode, previous = false) ?: -1)
            assertEquals(1, adjacentIndex(5, 2, mode, previous = true) ?: -1)
        }
    }

    @Test
    fun `随机模式任何时候都不预判`() {
        assertNull(adjacentIndex(5, 2, PlayMode.SHUFFLE, previous = false))
        assertNull(adjacentIndex(5, 2, PlayMode.SHUFFLE, previous = true))
        assertNull(adjacentIndex(5, 0, PlayMode.SHUFFLE, previous = true))
        assertNull(adjacentIndex(5, 4, PlayMode.SHUFFLE, previous = false))
    }

    @Test
    fun `顺序模式边界不回卷`() {
        assertNull(adjacentIndex(5, 4, PlayMode.SEQUENTIAL, previous = false))
        assertNull(adjacentIndex(5, 0, PlayMode.SEQUENTIAL, previous = true))
    }

    @Test
    fun `列表循环双向回卷`() {
        assertEquals(0, adjacentIndex(5, 4, PlayMode.REPEAT_ALL, previous = false) ?: -1)
        assertEquals(4, adjacentIndex(5, 0, PlayMode.REPEAT_ALL, previous = true) ?: -1)
    }

    @Test
    fun `单曲循环用户按下一首跳下一首队尾回第一首上一首队首无动作`() {
        assertEquals(0, adjacentIndex(5, 4, PlayMode.REPEAT_ONE, previous = false) ?: -1)
        assertNull(adjacentIndex(5, 0, PlayMode.REPEAT_ONE, previous = true))
    }

    @Test
    fun `空队列与越界下标不预判`() {
        assertNull(adjacentIndex(0, 0, PlayMode.SEQUENTIAL, previous = false))
        assertNull(adjacentIndex(5, -1, PlayMode.SEQUENTIAL, previous = false))
        assertNull(adjacentIndex(5, 5, PlayMode.SEQUENTIAL, previous = true))
    }

    @Test
    fun `单曲队列边界按模式区分`() {
        assertNull(adjacentIndex(1, 0, PlayMode.SEQUENTIAL, previous = false))
        assertEquals(0, adjacentIndex(1, 0, PlayMode.REPEAT_ALL, previous = false) ?: -1)
        assertEquals(0, adjacentIndex(1, 0, PlayMode.REPEAT_ONE, previous = false) ?: -1)
        assertEquals(0, adjacentIndex(1, 0, PlayMode.REPEAT_ALL, previous = true) ?: -1)
        assertNull(adjacentIndex(1, 0, PlayMode.REPEAT_ONE, previous = true))
    }
}
