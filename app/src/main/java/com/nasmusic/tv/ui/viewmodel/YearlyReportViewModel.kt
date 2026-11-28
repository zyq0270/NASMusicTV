package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.NameCount
import com.nasmusic.tv.data.model.SongCount
import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.data.stats.RoadmapYearlyStatsBackfillStore
import com.nasmusic.tv.data.stats.YearlyBackfillInput
import com.nasmusic.tv.data.stats.YearlyStatsBackfill
import com.nasmusic.tv.data.stats.YearlyStatsWriter
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.TimeZone

data class YearlyReportUiState(
    val statsByYear: Map<Int, YearlyStats> = emptyMap(),
    val years: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val selectedStats: YearlyStats? = null,
    val loading: Boolean = true,
    val error: Boolean = false,
)

enum class YearlyReportStatus {
    COMPLETE,
    ONGOING,
    BACKFILLED,
    PARTIAL,
}

/** 与 Compose 解耦的展示规则，供 UI 单测直接覆盖。 */
object YearlyReportPresentation {
    fun yearsDescending(statsByYear: Map<Int, YearlyStats>): List<Int> =
        statsByYear.keys.sortedDescending()

    fun initialYear(statsByYear: Map<Int, YearlyStats>, currentYear: Int): Int? =
        yearsDescending(statsByYear).firstOrNull { it == currentYear }
            ?: yearsDescending(statsByYear).firstOrNull()

    fun status(stats: YearlyStats, currentYear: Int): YearlyReportStatus = when {
        stats.complete -> YearlyReportStatus.COMPLETE
        stats.year == currentYear -> YearlyReportStatus.ONGOING
        stats.totalPlayMs == null &&
            stats.firstPlayAtMs == null &&
            stats.topSongs.isEmpty() &&
            stats.topArtists.isEmpty() -> YearlyReportStatus.BACKFILLED
        else -> YearlyReportStatus.PARTIAL
    }

    fun topSongs(stats: YearlyStats): List<SongCount> =
        YearlyStatsWriter.topSongs(stats, TOP_LIMIT)

    fun topArtists(stats: YearlyStats): List<NameCount> =
        YearlyStatsWriter.topArtists(stats, TOP_LIMIT)

    fun selectYear(state: YearlyReportUiState, year: Int): YearlyReportUiState {
        val selected = state.statsByYear[year] ?: return state
        return state.copy(selectedYear = year, selectedStats = selected)
    }

    const val TOP_LIMIT = 20
}

class YearlyReportViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val roadmapPreferences = nasMusicApp.roadmapPreferences
    private val playStatsRepository = nasMusicApp.appPreferences.playStatsRepo
    private val backfill = YearlyStatsBackfill(RoadmapYearlyStatsBackfillStore(roadmapPreferences))

    private val _uiState = MutableStateFlow(YearlyReportUiState())
    val uiState: StateFlow<YearlyReportUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun selectYear(year: Int) {
        _uiState.update { state -> YearlyReportPresentation.selectYear(state, year) }
    }

    fun retry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = false) }
            runCatching { ensureBackfill() }
                .onFailure {
                    it.rethrowIfCancellation()
                    AppLog.e("YearlyReportViewModel", "yearly backfill failed", it)
                    _uiState.update { state -> state.copy(error = true) }
                }

            val currentYear = YearlyStatsWriter.yearOf(
                System.currentTimeMillis(),
                TimeZone.getDefault(),
            )
            combine(
                roadmapPreferences.yearlyStatsFlow(),
                roadmapPreferences.yearlyBackfillDoneFlow(),
            ) { stats, _ -> stats }
                .collect { stats ->
                    val years = YearlyReportPresentation.yearsDescending(stats)
                    val selectedYear = _uiState.value.selectedYear
                        ?.takeIf { stats.containsKey(it) }
                        ?: YearlyReportPresentation.initialYear(stats, currentYear)
                    _uiState.value = YearlyReportUiState(
                        statsByYear = stats,
                        years = years,
                        selectedYear = selectedYear,
                        selectedStats = selectedYear?.let(stats::get),
                        loading = false,
                        error = _uiState.value.error,
                    )
                }
        }
    }

    private suspend fun ensureBackfill() {
        if (roadmapPreferences.yearlyBackfillDoneFlow().first()) return
        val monthly = playStatsRepository.monthlyStats.first()
        val daily = playStatsRepository.dailyStats.first()
        backfill.run(
            YearlyBackfillInput(
                monthlyStats = monthly,
                dailyStats = daily,
            )
        )
    }
}
