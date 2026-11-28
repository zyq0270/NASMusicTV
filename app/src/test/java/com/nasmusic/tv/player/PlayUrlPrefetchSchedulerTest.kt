package com.nasmusic.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayUrlPrefetchSchedulerTest {
    @Test
    fun `暂停或无网络时不运行`() {
        assertTrue(PlayUrlPrefetchScheduler.shouldRun(statePlaying = true, networkAvailable = true))
        assertFalse(PlayUrlPrefetchScheduler.shouldRun(statePlaying = false, networkAvailable = true))
        assertFalse(PlayUrlPrefetchScheduler.shouldRun(statePlaying = true, networkAvailable = false))
        assertFalse(PlayUrlPrefetchScheduler.shouldRun(statePlaying = false, networkAvailable = false))
    }

    @Test
    fun `刷新窗口边界与已过期项`() {
        val cachedAt = 1_000_000L
        assertFalse(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt + PlayUrlPrefetchScheduler.REFRESH_WINDOW_MS))
        assertFalse(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt + PlayUrlPrefetchScheduler.TTL_MS - PlayUrlPrefetchScheduler.REFRESH_WINDOW_MS - 1))
        assertTrue(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt + PlayUrlPrefetchScheduler.TTL_MS - PlayUrlPrefetchScheduler.REFRESH_WINDOW_MS))
        assertTrue(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt + PlayUrlPrefetchScheduler.TTL_MS))
        assertTrue(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt + PlayUrlPrefetchScheduler.TTL_MS + 1))
        assertFalse(PlayUrlPrefetchScheduler.shouldRefresh(cachedAt, cachedAt - 1))
    }

    @Test
    fun `重试退避有上限`() {
        assertEquals(5_000L, PlayUrlPrefetchScheduler.nextDelayMs(-1))
        assertEquals(5_000L, PlayUrlPrefetchScheduler.nextDelayMs(0))
        assertEquals(10_000L, PlayUrlPrefetchScheduler.nextDelayMs(1))
        assertEquals(20_000L, PlayUrlPrefetchScheduler.nextDelayMs(2))
        assertEquals(20_000L, PlayUrlPrefetchScheduler.nextDelayMs(99))
    }

    @Test
    fun `targets 只覆盖后续三首且不越界`() {
        assertEquals(listOf(1, 2, 3), PlayUrlPrefetchScheduler.targets(5, 0))
        assertEquals(listOf(4), PlayUrlPrefetchScheduler.targets(5, 3))
        assertEquals(emptyList<Int>(), PlayUrlPrefetchScheduler.targets(5, 4))
        assertEquals(emptyList<Int>(), PlayUrlPrefetchScheduler.targets(0, 0))
        assertEquals(emptyList<Int>(), PlayUrlPrefetchScheduler.targets(5, -1))
        assertEquals(emptyList<Int>(), PlayUrlPrefetchScheduler.targets(5, 5))
    }
}
