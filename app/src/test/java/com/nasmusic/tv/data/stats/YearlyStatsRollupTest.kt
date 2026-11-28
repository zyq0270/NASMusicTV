package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.NameCount
import com.nasmusic.tv.data.model.PlayEnded
import com.nasmusic.tv.data.model.PlayStarted
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongCount
import com.nasmusic.tv.data.model.YearlyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class YearlyStatsRollupTest {

    @Test
    fun crossYearLongSongIsCountedOnceInStartYear() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val startedAt = timestamp("2024-12-31 23:59:30", zone)
        val endedAt = timestamp("2025-01-01 00:04:30", zone)
        val now = timestamp("2026-01-02 00:00:00", zone)

        val afterStart = YearlyStatsWriter.applyStarted(
            previous = null,
            event = PlayStarted("song-a", startedAt),
            song = Song(id = "song-a", title = "A", artist = "Artist"),
            nowMs = now,
            zone = zone,
        )
        val afterEnd = YearlyStatsWriter.applyEnded(
            previous = afterStart,
            event = PlayEnded("song-a", startedAt, endedAt, durationPlayedMs = 300_000L),
            nowMs = now,
            zone = zone,
        )

        assertEquals(2024, afterEnd.year)
        assertEquals(1, afterEnd.playCount)
        assertEquals(300_000L, afterEnd.totalPlayMs)
        assertEquals(startedAt, afterEnd.firstPlayAtMs)
        assertEquals(endedAt, afterEnd.lastPlayAtMs)
        assertTrue(afterEnd.complete)
        assertEquals(1, afterEnd.topSongs.single().count)
    }

    @Test
    fun endedOnlyEventDoesNotInventPlayCountAndStaysIncomplete() {
        val zone = TimeZone.getTimeZone("UTC")
        val startedAt = timestamp("2024-06-01 10:00:00", zone)
        val endedAt = startedAt + 60_000L
        val stats = YearlyStatsWriter.applyEnded(
            previous = null,
            event = PlayEnded("song-a", startedAt, endedAt, durationPlayedMs = 60_000L),
            nowMs = timestamp("2026-01-01 00:00:00", zone),
            zone = zone,
        )

        assertNull(stats.playCount)
        assertNull(stats.activeDays)
        assertEquals(60_000L, stats.totalPlayMs)
        assertFalse(stats.complete)
    }

    @Test
    fun leapDayAndDstTransitionUseLocalCalendarDays() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = timestamp("2026-01-01 00:00:00", zone)
        val dates = listOf(
            "2024-02-29 12:00:00",
            "2024-03-09 12:00:00",
            "2024-03-10 12:00:00",
            "2024-03-11 12:00:00",
        )

        val stats = dates.fold(null as YearlyStats?) { acc, value ->
            val at = timestamp(value, zone)
            YearlyStatsWriter.applyStarted(
                previous = acc,
                event = PlayStarted("song-$value", at),
                nowMs = now,
                zone = zone,
            )
        }

        assertEquals(2024, stats!!.year)
        assertEquals(4, stats.playCount)
        assertEquals(4, stats.activeDays)
        assertEquals("2024-02-29", YearlyStatsWriter.dayKey(timestamp(dates.first(), zone), zone))
        assertEquals("2024-03-10", YearlyStatsWriter.dayKey(timestamp(dates[2], zone), zone))
    }

    @Test
    fun yearAndActiveDayFollowDeviceTimeZone() {
        val instant = timestamp("2025-01-01 00:30:00", TimeZone.getTimeZone("UTC"))
        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        val now = timestamp("2026-01-01 00:00:00", TimeZone.getTimeZone("UTC"))

        val inLosAngeles = YearlyStatsWriter.applyStarted(
            previous = null,
            event = PlayStarted("song-a", instant),
            nowMs = now,
            zone = losAngeles,
        )
        val inShanghai = YearlyStatsWriter.applyStarted(
            previous = null,
            event = PlayStarted("song-a", instant),
            nowMs = now,
            zone = shanghai,
        )

        assertEquals(2024, inLosAngeles.year)
        assertEquals(2025, inShanghai.year)
        assertEquals(1, inLosAngeles.activeDays)
        assertEquals(1, inShanghai.activeDays)
    }

    @Test
    fun clockRollbackDoesNotCountSameActiveDayTwice() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = timestamp("2026-01-01 00:00:00", zone)
        val later = timestamp("2024-06-01 18:00:00", zone)
        val earlier = timestamp("2024-06-01 09:00:00", zone)
        val stats = YearlyStatsWriter.applyStarted(
            previous = YearlyStatsWriter.applyStarted(
                previous = null,
                event = PlayStarted("song-a", later),
                nowMs = now,
                zone = zone,
            ),
            event = PlayStarted("song-b", earlier),
            nowMs = now,
            zone = zone,
        )

        assertEquals(2, stats.playCount)
        assertEquals(1, stats.activeDays)
        assertEquals(setOf("2024-06-01"), stats.activeDayKeys)
        assertEquals(earlier, stats.coverageStartMs)
        assertEquals(later, stats.coverageEndMs)
    }

    @Test
    fun top20IsStableAndDoesNotTruncateStoredCounts() {
        val songs = (1..25).map { index ->
            SongCount(
                key = "song-${index.toString().padStart(2, '0')}",
                title = "Song $index",
                artist = "Artist",
                count = if (index <= 5) 10 else index % 10,
            )
        }.shuffled()
        val artists = listOf(
            NameCount("b", 2),
            NameCount("a", 2),
            NameCount("A", 2),
            NameCount("c", 3),
        )
        val stats = YearlyStats(
            year = 2024,
            playCount = songs.sumOf { it.count },
            topSongs = songs,
            topArtists = artists,
            complete = true,
        )

        val topSongs = YearlyStatsWriter.topSongs(stats)
        val topArtists = YearlyStatsWriter.topArtists(stats)

        assertEquals(25, stats.topSongs.size)
        assertEquals(20, topSongs.size)
        assertEquals(10, topSongs.first().count)
        assertEquals(listOf("song-01", "song-02", "song-03", "song-04", "song-05"), topSongs.take(5).map { it.key })
        assertEquals(listOf("c", "A", "a", "b"), topArtists.map { it.name })
    }

    @Test
    fun playCountAndDurationAreConservedAcrossYears() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = timestamp("2026-01-01 00:00:00", zone)
        val byYear = mutableMapOf<Int, YearlyStats>()

        repeat(100) { index ->
            val year = if (index % 2 == 0) 2024 else 2025
            val startedAt = timestamp("$year-06-01 10:00:00", zone) + index * 1_000L
            val key = "song-${index % 7}"
            byYear[year] = YearlyStatsWriter.applyStarted(
                previous = byYear[year],
                event = PlayStarted(key, startedAt),
                song = Song(id = key, title = key, artist = "Artist ${index % 3}"),
                nowMs = now,
                zone = zone,
            )
            byYear[year] = YearlyStatsWriter.applyEnded(
                previous = byYear[year],
                event = PlayEnded(key, startedAt, startedAt + 30_000L, durationPlayedMs = 30_000L),
                nowMs = now,
                zone = zone,
            )
        }

        assertEquals(100, byYear.values.sumOf { it.playCount ?: 0 })
        assertEquals(3_000_000L, byYear.values.sumOf { it.totalPlayMs ?: 0L })
        assertTrue(byYear.values.all { it.complete })
    }

    private fun timestamp(value: String, zone: TimeZone): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
            .parse(value)!!.time
}
