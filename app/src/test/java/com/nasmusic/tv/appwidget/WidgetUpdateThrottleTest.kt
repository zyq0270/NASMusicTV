package com.nasmusic.tv.appwidget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdateThrottleTest {
    @Test
    fun burstsAreCoalescedAndTrailingStateIsPublished() = runTest {
        var updates = 0
        val throttle = WidgetUpdateThrottle(null, backgroundScope, intervalMs = 800L) { updates++ }

        repeat(20) { throttle.requestUpdate() }
        advanceTimeBy(800L)
        runCurrent()

        assertEquals(1, updates)
    }

    @Test
    fun updatesRespectMinimumInterval() = runTest {
        var updates = 0
        val throttle = WidgetUpdateThrottle(null, backgroundScope, intervalMs = 800L) { updates++ }

        throttle.requestUpdate()
        advanceTimeBy(800L)
        runCurrent()
        throttle.requestUpdate()
        advanceTimeBy(799L)
        runCurrent()
        assertEquals(1, updates)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, updates)
    }
}
