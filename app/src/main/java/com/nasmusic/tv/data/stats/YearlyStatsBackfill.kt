package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 年度实时写入与首次回填共用的进程内写门。
 *
 * 主会话接入 PlayStarted/PlayEnded 时，应把「读取旧年度值 → YearlyStatsWriter 归约 → 写回」
 * 放在 [mutex] 内，或实现为 DataStore 单次 edit。否则首次回填与实时事件并发时可能互相覆盖。
 */
object YearlyStatsWriteGate {
    val mutex = Mutex()
}

data class YearlyBackfillInput(
    val monthlyStats: Map<String, Map<String, Int>>,
    val dailyStats: Map<String, Int>,
    val zone: TimeZone = TimeZone.getDefault(),
)

data class YearlyBackfillResult(
    val skipped: Boolean,
    val changedYears: List<Int>,
    val stats: Map<Int, YearlyStats>,
)

/** 回填持久化接口；commit 实现应同时写入年度数据与 migration 标记。 */
interface YearlyStatsBackfillStore {
    suspend fun isBackfillDone(): Boolean
    suspend fun loadYearlyStats(): Map<Int, YearlyStats>
    suspend fun commitYearlyStats(stats: Map<Int, YearlyStats>)
}

/** 基于现有 RoadmapPreferences API 的适配器。 */
class RoadmapYearlyStatsBackfillStore(
    private val preferences: RoadmapPreferences,
) : YearlyStatsBackfillStore {
    override suspend fun isBackfillDone(): Boolean =
        preferences.yearlyBackfillDoneFlow().first()

    override suspend fun loadYearlyStats(): Map<Int, YearlyStats> =
        preferences.yearlyStatsFlow().first()

    override suspend fun commitYearlyStats(stats: Map<Int, YearlyStats>) {
        preferences.commitYearlyStatsAndMarkDone(stats)
    }
}

/**
 * 年度历史回填：只读取仍存在的 monthly/daily 桶，不把两者相加。
 *
 * - monthly 只能恢复播放次数；
 * - daily 只能恢复活跃天数与可见覆盖日期；
 * - 总时长、首末播放、歌曲榜、歌手榜不可恢复，保持 null/空列表；
 * - 所有回填年 complete=false；已有完整实时年份不被降级。
 */
class YearlyStatsBackfill(
    private val store: YearlyStatsBackfillStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(input: YearlyBackfillInput): YearlyBackfillResult =
        withContext(dispatcher) {
            YearlyStatsWriteGate.mutex.withLock {
                if (store.isBackfillDone()) {
                    YearlyBackfillResult(
                        skipped = true,
                        changedYears = emptyList(),
                        stats = store.loadYearlyStats(),
                    )
                } else {
                    val existing = store.loadYearlyStats()
                    val merged = merge(existing, input)
                    val changedYears = merged.keys
                        .filter { existing[it] != merged[it] }
                        .sorted()
                    // 即使没有可恢复年份也要提交，以便把 migration 标记置位。
                    store.commitYearlyStats(merged)
                    YearlyBackfillResult(
                        skipped = false,
                        changedYears = changedYears,
                        stats = merged,
                    )
                }
            }
        }

    companion object {
        val MONTH_KEY = Regex("""\d{4}-(0[1-9]|1[0-2])""")
        val DAY_KEY = Regex("""\d{4}-\d{2}-\d{2}""")

        fun merge(
            existing: Map<Int, YearlyStats>,
            input: YearlyBackfillInput,
        ): Map<Int, YearlyStats> {
            val recovered = recover(input)
            val result = existing.toMutableMap()
            for ((year, backfilled) in recovered) {
                val current = result[year]
                result[year] = if (current == null) {
                    backfilled
                } else if (current.complete) {
                    current
                } else {
                    val activeDayKeys = current.activeDayKeys + backfilled.activeDayKeys
                    current.copy(
                        playCount = listOfNotNull(current.playCount, backfilled.playCount).maxOrNull(),
                        activeDays = listOfNotNull(
                            current.activeDays,
                            backfilled.activeDays,
                            activeDayKeys.size.takeIf { it > 0 },
                        ).maxOrNull(),
                        activeDayKeys = activeDayKeys,
                        complete = false,
                        backfilled = true,
                        coverageStartMs = listOfNotNull(
                            current.coverageStartMs,
                            backfilled.coverageStartMs,
                        ).minOrNull(),
                        coverageEndMs = listOfNotNull(
                            current.coverageEndMs,
                            backfilled.coverageEndMs,
                        ).maxOrNull(),
                        schemaVersion = maxOf(current.schemaVersion, backfilled.schemaVersion),
                    )
                }
            }
            return result
        }

        private fun recover(input: YearlyBackfillInput): Map<Int, YearlyStats> {
            data class MonthlyAgg(var plays: Int = 0, var hasBucket: Boolean = false)
            data class DailyAgg(
                var activeDays: Int = 0,
                val dayKeys: MutableSet<String> = mutableSetOf(),
                var firstDayStartMs: Long? = null,
                var lastDayEndMs: Long? = null,
            )

            val monthly = mutableMapOf<Int, MonthlyAgg>()
            for ((monthKey, songCounts) in input.monthlyStats) {
                val parts = parseMonth(monthKey, input.zone) ?: continue
                val agg = monthly.getOrPut(parts.first) { MonthlyAgg() }
                agg.hasBucket = true
                for (count in songCounts.values) {
                    if (count > 0) agg.plays = saturatingAdd(agg.plays, count)
                }
            }

            val daily = mutableMapOf<Int, DailyAgg>()
            for ((dayKey, count) in input.dailyStats) {
                if (count <= 0) continue
                val parts = parseDay(dayKey, input.zone) ?: continue
                val agg = daily.getOrPut(parts.first) { DailyAgg() }
                agg.activeDays = saturatingAdd(agg.activeDays, 1)
                agg.dayKeys += dayKey
                agg.firstDayStartMs = minTimestamp(agg.firstDayStartMs, parts.second)
                agg.lastDayEndMs = maxTimestamp(agg.lastDayEndMs, parts.third)
            }

            val years = (monthly.keys + daily.keys).toMutableSet()
            return years.associateWith { year ->
                val monthlyAgg = monthly[year]
                val dailyAgg = daily[year]
                YearlyStats(
                    year = year,
                    playCount = monthlyAgg?.takeIf { it.hasBucket }?.plays,
                    activeDays = dailyAgg?.activeDays,
                    activeDayKeys = dailyAgg?.dayKeys.orEmpty(),
                    topSongs = emptyList(),
                    topArtists = emptyList(),
                    firstPlayAtMs = null,
                    lastPlayAtMs = null,
                    complete = false,
                    backfilled = true,
                    coverageStartMs = dailyAgg?.firstDayStartMs,
                    coverageEndMs = dailyAgg?.lastDayEndMs,
                    schemaVersion = YearlyStatsWriter.SCHEMA_VERSION,
                )
            }
        }

        private fun parseMonth(key: String, zone: TimeZone): Pair<Int, Long>? {
            if (!MONTH_KEY.matches(key)) return null
            val calendar = Calendar.getInstance(zone).apply {
                isLenient = false
                clear()
                set(Calendar.YEAR, key.substring(0, 4).toInt())
                set(Calendar.MONTH, key.substring(5, 7).toInt() - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            return calendar.get(Calendar.YEAR) to calendar.timeInMillis
        }

        private fun parseDay(key: String, zone: TimeZone): Triple<Int, Long, Long>? {
            if (!DAY_KEY.matches(key)) return null
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = zone
                isLenient = false
            }
            val position = ParsePosition(0)
            val parsed = format.parse(key, position) ?: return null
            if (position.index != key.length) return null
            val calendar = Calendar.getInstance(zone).apply {
                timeInMillis = parsed.time
            }
            val year = calendar.get(Calendar.YEAR)
            val start = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            return Triple(year, start, calendar.timeInMillis)
        }

        private fun saturatingAdd(current: Int, delta: Int): Int =
            if (delta > 0 && current > Int.MAX_VALUE - delta) Int.MAX_VALUE else current + delta

        private fun minTimestamp(current: Long?, candidate: Long): Long =
            if (current == null || candidate < current) candidate else current

        private fun maxTimestamp(current: Long?, candidate: Long): Long =
            if (current == null || candidate > current) candidate else current
    }
}
