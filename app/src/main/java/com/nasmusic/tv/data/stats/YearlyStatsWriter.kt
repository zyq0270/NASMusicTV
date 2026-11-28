package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.NameCount
import com.nasmusic.tv.data.model.PlayEnded
import com.nasmusic.tv.data.model.PlayStarted
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongCount
import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.util.ArtistSplitter
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * 年度统计的纯归约器。
 *
 * 事件口径（docs/archive/feature-roadmap-dev-plan-2026-11-27.md §0.4）：
 * - 播放次数、歌曲榜、歌手榜、活跃天数记在 `PlayStarted.startedAtMs` 所在本地年；
 * - 总时长与首末时间记在 `PlayEnded`，但仍归属 `startedAtMs` 所在本地年。
 *   这样跨年长曲不会被拆成两条播放，也不会在结束年凭空多出一次播放。
 *
 * 本对象不读写 DataStore、不持有 Android 引用。调用方应保证每个 Start/End 会话只归约一次，
 * 并在同一次数据事务中读取旧值、调用归约器并写回，避免并发事件丢失更新。
 */
object YearlyStatsWriter {

    const val SCHEMA_VERSION = 2

    fun applyStarted(
        previous: YearlyStats?,
        event: PlayStarted,
        song: Song? = null,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): YearlyStats {
        val year = yearOf(event.startedAtMs, zone)
        val base = previous ?: empty(year)
        val playCount = saturatingAdd(base.playCount ?: 0, 1)
        val topSongs = incrementSong(base.topSongs, event.songKey, song)
        val topArtists = incrementArtists(base.topArtists, song)
        val (activeDays, activeDayKeys) = nextActiveDays(base, event.startedAtMs, zone)

        return base.copy(
            playCount = playCount,
            activeDays = activeDays,
            activeDayKeys = activeDayKeys,
            topSongs = topSongs,
            topArtists = topArtists,
            // 只有实时 Start 事件创建出来的历史年份才可视为完整；当前年与回填年恒为 false。
            complete = !base.backfilled && year != yearOf(nowMs, zone),
            backfilled = base.backfilled,
            coverageStartMs = minTimestamp(base.coverageStartMs, event.startedAtMs),
            coverageEndMs = maxTimestamp(base.coverageEndMs, event.startedAtMs),
            schemaVersion = maxOf(base.schemaVersion, SCHEMA_VERSION),
        )
    }

    fun applyEnded(
        previous: YearlyStats?,
        event: PlayEnded,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): YearlyStats {
        val year = yearOf(event.startedAtMs, zone)
        val base = previous ?: empty(year)
        val effectiveEndMs = max(event.startedAtMs, event.endedAtMs)
        val durationMs = event.durationPlayedMs.coerceAtLeast(0L)

        return base.copy(
            totalPlayMs = saturatingAdd(base.totalPlayMs, durationMs),
            firstPlayAtMs = minTimestamp(base.firstPlayAtMs, event.startedAtMs),
            lastPlayAtMs = maxTimestamp(base.lastPlayAtMs, effectiveEndMs),
            // 仅收到 End、没有对应 Start 的年份不是完整年份；当前年也始终标记为进行中。
            complete = !base.backfilled && base.playCount != null && year != yearOf(nowMs, zone),
            backfilled = base.backfilled,
            coverageStartMs = minTimestamp(base.coverageStartMs, event.startedAtMs),
            coverageEndMs = maxTimestamp(base.coverageEndMs, effectiveEndMs),
            schemaVersion = maxOf(base.schemaVersion, SCHEMA_VERSION),
        )
    }

    /** 展示用 Top20；存储中的 [YearlyStats.topSongs] 始终保持完整计数。 */
    fun topSongs(stats: YearlyStats, limit: Int = 20): List<SongCount> {
        if (limit <= 0) return emptyList()
        return stats.topSongs
            .sortedWith(
                compareByDescending<SongCount> { it.count }
                    .thenBy { normalizeForSort(it.key) }
                    .thenBy { it.key }
            )
            .take(limit)
    }

    /** 展示用 Top20；次数降序，同次数按规范化名称、原名称升序，保证跨设备结果稳定。 */
    fun topArtists(stats: YearlyStats, limit: Int = 20): List<NameCount> {
        if (limit <= 0) return emptyList()
        return stats.topArtists
            .sortedWith(
                compareByDescending<NameCount> { it.count }
                    .thenBy { normalizeForSort(it.name) }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    fun empty(year: Int): YearlyStats = YearlyStats(
        year = year,
        complete = false,
        schemaVersion = SCHEMA_VERSION,
    )

    fun yearOf(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Calendar.getInstance(zone).apply { timeInMillis = timestampMs }.get(Calendar.YEAR)

    fun dayKey(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timestampMs }
        return buildString {
            append(calendar.get(Calendar.YEAR).toString().padStart(4, '0'))
            append('-')
            append((calendar.get(Calendar.MONTH) + 1).toString().padStart(2, '0'))
            append('-')
            append(calendar.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'))
        }
    }

    private fun incrementSong(
        songs: List<SongCount>,
        songKey: String,
        song: Song?,
    ): List<SongCount> {
        val index = songs.indexOfFirst { it.key == songKey }
        val old = songs.getOrNull(index)
        val updated = SongCount(
            key = songKey,
            title = song?.title?.takeIf { it.isNotBlank() } ?: old?.title ?: songKey,
            artist = song?.artist?.takeIf { it.isNotBlank() } ?: old?.artist.orEmpty(),
            count = saturatingAdd(old?.count ?: 0, 1),
        )
        return if (index >= 0) {
            songs.toMutableList().apply { this[index] = updated }
        } else {
            songs + updated
        }
    }

    private fun incrementArtists(artists: List<NameCount>, song: Song?): List<NameCount> {
        if (song == null || song.artist.isBlank()) return artists
        val names = ArtistSplitter.split(song.artist)
        if (names.isEmpty()) return artists

        val result = artists.toMutableList()
        for (name in names) {
            val normalized = ArtistSplitter.normalizeKey(name)
            val index = result.indexOfFirst { ArtistSplitter.normalizeKey(it.name) == normalized }
            if (index >= 0) {
                result[index] = result[index].copy(count = saturatingAdd(result[index].count, 1))
            } else {
                result += NameCount(name = name, count = 1)
            }
        }
        return result
    }

    private fun nextActiveDays(
        previous: YearlyStats,
        startedAtMs: Long,
        zone: TimeZone,
    ): Pair<Int, Set<String>> {
        val day = dayKey(startedAtMs, zone)
        if (day in previous.activeDayKeys) {
            return (previous.activeDays ?: previous.activeDayKeys.size) to previous.activeDayKeys
        }
        val knownDays = previous.activeDayKeys + day
        val count = if (previous.activeDayKeys.isEmpty() && previous.activeDays != null) {
            saturatingAdd(previous.activeDays, 1)
        } else {
            saturatingAdd(previous.activeDays ?: previous.activeDayKeys.size, 1)
        }
        return count to knownDays
    }

    private fun minTimestamp(current: Long?, candidate: Long): Long =
        if (current == null || candidate < current) candidate else current

    private fun maxTimestamp(current: Long?, candidate: Long): Long =
        if (current == null || candidate > current) candidate else current

    private fun saturatingAdd(current: Long?, delta: Long): Long {
        val base = current ?: 0L
        if (delta > 0L && base > Long.MAX_VALUE - delta) return Long.MAX_VALUE
        if (delta < 0L && base < Long.MIN_VALUE - delta) return Long.MIN_VALUE
        return base + delta
    }

    private fun saturatingAdd(current: Int, delta: Int): Int {
        if (delta > 0 && current > Int.MAX_VALUE - delta) return Int.MAX_VALUE
        if (delta < 0 && current < Int.MIN_VALUE - delta) return Int.MIN_VALUE
        return current + delta
    }

    private fun normalizeForSort(value: String): String =
        ArtistSplitter.normalizeKey(value).ifBlank { value.lowercase(Locale.ROOT) }
}
