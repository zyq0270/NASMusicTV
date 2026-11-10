package com.nasmusic.tv.visualizer.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入环形队列单测（方案 §4.8 / §4.9 W9）。
 *
 * 钉住三条语义：
 * 1. 单写单读、不丢事件（未满时）；
 * 2. 满了**丢最旧**（用户刚按的那一下才是意图）；
 * 3. `drainStart()` 一次取空（GL 线程每 tick 只取一次）。
 */
class ArcadeInputQueueTest {

    @Test
    fun `未满时事件按序取出`() {
        val q = ArcadeInputQueue(8)
        q.offer(ArcadeInputQueue.EVENT_START)
        assertTrue(q.drainStart())
        // 取空后没有残留
        assertFalse(q.drainStart())
        assertEquals(0, q.size())
    }

    @Test
    fun `满了丢弃最旧的事件`() {
        val q = ArcadeInputQueue(4) // 环形队列实际可用 3 格
        q.offer(ArcadeInputQueue.EVENT_START)
        q.offer(2)
        q.offer(3)
        q.offer(4) // ⇒ 最旧的 START 被挤掉
        assertFalse("最旧的事件应被丢弃", q.drainStart())
        assertEquals(0, q.size())
    }

    @Test
    fun `clear 之后状态归零`() {
        val q = ArcadeInputQueue(8)
        repeat(5) { q.offer(9) }
        q.clear()
        assertEquals(0, q.size())
        assertFalse(q.drainStart())
    }

    @Test
    fun `容量下限被夹到 2 且不会崩`() {
        val q = ArcadeInputQueue(1)
        repeat(10) { q.offer(9) }
        // 容量被夹到 2（实际可存 1 条）⇒ 只有最后一条存活；它不是 START
        assertFalse(q.drainStart())
        assertEquals("一次只能存 1 条 ⇒ 取出后为空", 0, q.size())
    }
}
