package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.YearlyStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class YearlyBackfillTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun recoversOnlySupportedFieldsAndDoesNotAddDailyToMonthly() {
        val input = YearlyBackfillInput(
            monthlyStats = mapOf(
                "2024-01" to mapOf("song-a" to 2, "song-b" to 1),
            ),
            dailyStats = mapOf(
                "2024-01-01" to 2,
                "2024-01-02" to 1,
            ),
            zone = zone,
        )

        val stats = YearlyStatsBackfill.merge(emptyMap(), input).getValue(2024)

        assertEquals(3, stats.playCount)
        assertEquals(2, stats.activeDays)
        assertNull(stats.totalPlayMs)
        assertNull(stats.firstPlayAtMs)
        assertNull(stats.lastPlayAtMs)
        assertTrue(stats.topSongs.isEmpty())
        assertTrue(stats.topArtists.isEmpty())
        assertFalse(stats.complete)
    }

    @Test
    fun missingMonthlyOrDailyBucketLeavesFieldNull() {
        val monthlyOnly = YearlyStatsBackfill.merge(
            emptyMap(),
            YearlyBackfillInput(
                monthlyStats = mapOf("2023-12" to mapOf("song-a" to 4)),
                dailyStats = emptyMap(),
                zone = zone,
            ),
        ).getValue(2023)

        val dailyOnly = YearlyStatsBackfill.merge(
            emptyMap(),
            YearlyBackfillInput(
                monthlyStats = emptyMap(),
                dailyStats = mapOf("2023-12-31" to 1),
                zone = zone,
            ),
        ).getValue(2023)

        assertEquals(4, monthlyOnly.playCount)
        assertNull(monthlyOnly.activeDays)
        assertNull(monthlyOnly.coverageStartMs)
        assertNull(dailyOnly.playCount)
        assertEquals(1, dailyOnly.activeDays)
        assertNotNull(dailyOnly.coverageStartMs)
        assertNotNull(dailyOnly.coverageEndMs)
    }

    @Test
    fun mergeIsIdempotentAndPreservesCompleteRealtimeYear() {
        val input = YearlyBackfillInput(
            monthlyStats = mapOf("2024-01" to mapOf("song-a" to 2)),
            dailyStats = mapOf("2024-01-01" to 2),
            zone = zone,
        )
        val complete = YearlyStats(
            year = 2024,
            totalPlayMs = 120_000L,
            playCount = 2,
            activeDays = 1,
            complete = true,
        )

        val first = YearlyStatsBackfill.merge(mapOf(2024 to complete), input)
        val second = YearlyStatsBackfill.merge(first, input)

        assertEquals(first, second)
        assertEquals(complete, first[2024])
    }

    @Test
    fun mergeFillsNullsAndKeepsOverlapSafePlayCount() {
        val realtime = YearlyStats(
            year = 2024,
            totalPlayMs = 50_000L,
            playCount = 1,
            activeDays = null,
            complete = false,
        )
        val input = YearlyBackfillInput(
            monthlyStats = mapOf("2024-01" to mapOf("song-a" to 9)),
            dailyStats = mapOf("2024-01-01" to 9, "2024-01-02" to 1),
            zone = zone,
        )

        val merged = YearlyStatsBackfill.merge(mapOf(2024 to realtime), input).getValue(2024)

        assertEquals(9, merged.playCount)
        assertEquals(2, merged.activeDays)
        assertEquals(50_000L, merged.totalPlayMs)
        assertFalse(merged.complete)
        assertTrue(merged.backfilled)
    }

    @Test
    fun realtimeYearCanLaterBecomeCompleteButBackfilledYearCannot() {
        val realtime = YearlyStats(year = 2025, playCount = 1, complete = false, backfilled = false)
        val promoted = YearlyStatsWriter.applyStarted(
            previous = realtime,
            event = com.nasmusic.tv.data.model.PlayStarted("song-a", 1_735_689_600_000L),
            nowMs = 1_775_000_000_000L,
            zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertTrue(promoted.complete)

        val backfilled = realtime.copy(backfilled = true)
        val stillIncomplete = YearlyStatsWriter.applyStarted(
            previous = backfilled,
            event = com.nasmusic.tv.data.model.PlayStarted("song-a", 1_735_689_600_000L),
            nowMs = 1_775_000_000_000L,
            zone = java.util.TimeZone.getTimeZone("UTC"),
        )
        assertFalse(stillIncomplete.complete)
    }

    @Test
    fun corruptedBucketsAreIsolated() {
        val merged = YearlyStatsBackfill.merge(
            emptyMap(),
            YearlyBackfillInput(
                monthlyStats = mapOf(
                    "2024-13" to mapOf("bad" to 100),
                    "not-a-month" to mapOf("bad" to 100),
                    "2024-03" to mapOf("good" to 2),
                ),
                dailyStats = mapOf(
                    "2024-02-30" to 100,
                    "bad-day" to 100,
                    "2024-03-01" to 2,
                ),
                zone = zone,
            ),
        )

        assertEquals(setOf(2024), merged.keys)
        assertEquals(2, merged.getValue(2024).playCount)
        assertEquals(1, merged.getValue(2024).activeDays)
    }

    @Test
    fun runnerMarksDoneAndSecondRunSkipsCommit() = runTest {
        val store = FakeStore()
        val runner = YearlyStatsBackfill(store, UnconfinedTestDispatcher(testScheduler))
        val input = YearlyBackfillInput(
            monthlyStats = mapOf("2024-01" to mapOf("song-a" to 1)),
            dailyStats = mapOf("2024-01-01" to 1),
            zone = zone,
        )

        val first = runner.run(input)
        val second = runner.run(input)

        assertFalse(first.skipped)
        assertTrue(second.skipped)
        assertEquals(1, store.commitCount)
        assertTrue(store.done)
    }

    @Test
    fun concurrentRunsCommitOnlyOnce() = runTest {
        val store = FakeStore()
        val runner = YearlyStatsBackfill(store, UnconfinedTestDispatcher(testScheduler))
        val input = YearlyBackfillInput(
            monthlyStats = mapOf("2024-01" to mapOf("song-a" to 1)),
            dailyStats = mapOf("2024-01-01" to 1),
            zone = zone,
        )

        val results = (1..16).map {
            async { runner.run(input) }
        }.awaitAll()

        assertEquals(1, results.count { !it.skipped })
        assertEquals(1, store.commitCount)
        assertEquals(1, store.stats.size)
    }

    private class FakeStore(
        initial: Map<Int, YearlyStats> = emptyMap(),
    ) : YearlyStatsBackfillStore {
        val stats = initial.toMutableMap()
        var done = false
        var commitCount = 0

        override suspend fun isBackfillDone(): Boolean = done

        override suspend fun loadYearlyStats(): Map<Int, YearlyStats> = stats.toMap()

        override suspend fun commitYearlyStats(stats: Map<Int, YearlyStats>) {
            commitCount += 1
            this.stats.clear()
            this.stats.putAll(stats)
            done = true
        }
    }
}
