package com.nasmusic.tv.ui.screens.stats

import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.ui.viewmodel.YearlyReportPresentation
import com.nasmusic.tv.ui.viewmodel.YearlyReportStatus
import com.nasmusic.tv.ui.viewmodel.YearlyReportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YearlyReportUiTest {

    @Test
    fun yearChipsAreDescending() {
        val stats = mapOf(
            2023 to stats(2023),
            2026 to stats(2026),
            2024 to stats(2024),
            2025 to stats(2025),
        )

        assertEquals(listOf(2026, 2025, 2024, 2023), YearlyReportPresentation.yearsDescending(stats))
    }

    @Test
    fun currentYearWithSingleRecordIsSelectedAndShown() {
        val stats = mapOf(2026 to stats(2026, complete = false, playCount = 1))

        val selected = YearlyReportPresentation.initialYear(stats, currentYear = 2026)
        val state = YearlyReportPresentation.selectYear(
            YearlyReportUiState(statsByYear = stats, years = listOf(2026)),
            selected!!,
        )

        assertEquals(2026, selected)
        assertEquals(2026, state.selectedYear)
        assertEquals(1, state.selectedStats?.playCount)
        assertEquals(YearlyReportStatus.ONGOING, YearlyReportPresentation.status(stats.getValue(2026), 2026))
    }

    @Test
    fun switchingYearUpdatesSelectedStatsAndUnknownYearIsIgnored() {
        val stats = mapOf(2024 to stats(2024), 2025 to stats(2025, playCount = 7))
        val initial = YearlyReportUiState(
            statsByYear = stats,
            years = listOf(2025, 2024),
            selectedYear = 2025,
            selectedStats = stats[2025],
        )

        val switched = YearlyReportPresentation.selectYear(initial, 2024)
        val ignored = YearlyReportPresentation.selectYear(switched, 1999)

        assertEquals(2024, switched.selectedYear)
        assertEquals(stats[2024], switched.selectedStats)
        assertEquals(switched, ignored)
    }

    @Test
    fun incompleteStatesAreDistinctAndNeverFiltered() {
        val current = stats(2026, complete = false, playCount = 1)
        val backfilled = stats(2024, complete = false, playCount = 3, activeDays = 2, totalPlayMs = null)
        val partial = stats(2023, complete = false, totalPlayMs = 10_000L, playCount = 1)

        assertEquals(YearlyReportStatus.ONGOING, YearlyReportPresentation.status(current, 2026))
        assertEquals(YearlyReportStatus.BACKFILLED, YearlyReportPresentation.status(backfilled, 2026))
        assertEquals(YearlyReportStatus.PARTIAL, YearlyReportPresentation.status(partial, 2026))
        assertEquals(
            listOf(2026, 2024, 2023),
            YearlyReportPresentation.yearsDescending(mapOf(2024 to backfilled, 2023 to partial, 2026 to current)),
        )
    }

    @Test
    fun yearlyScreenHasScrollableFocusableYearChipsAndTop20Lists() {
        val source = source("ui/screens/stats/YearlyReportScreen.kt")
        assertTrue(source.contains("LazyColumn"))
        assertTrue(source.contains("LazyRow"))
        assertTrue(source.contains("FocusableSurface"))
        assertTrue(source.contains("focusRequester"))
        assertTrue(source.contains("YearlyReportPresentation.topSongs"))
        assertTrue(source.contains("YearlyReportPresentation.topArtists"))
        assertTrue(source.contains("R.string.yearly_report_incomplete"))
    }

    @Test
    fun playStatsEntryIsFocusableAndContentCanScroll() {
        val source = source("ui/screens/stats/PlayStatsScreen.kt")
        assertTrue(source.contains("R.string.yearly_report_entry"))
        assertTrue(source.contains("showYearlyReport"))
        assertTrue(source.contains("BackHandler"))
        assertTrue(source.contains("LazyColumn"))
        assertTrue(source.contains("LazyRow"))
        assertTrue(source.contains("FocusableSurface"))
    }

    @Test
    fun noExportOrNetworkApisAfterCommentsAreStripped() {
        val files = listOf(
            "ui/screens/stats/YearlyReportScreen.kt",
            "ui/viewmodel/YearlyReportViewModel.kt",
            "data/stats/YearlyStatsWriter.kt",
            "data/stats/YearlyStatsBackfill.kt",
        )
        val forbidden = files.flatMap { path ->
            findForbidden(stripComments(source(path))).map { "$path: $it" }
        }

        assertTrue(forbidden.toString(), forbidden.isEmpty())
    }

    @Test
    fun exportGateStripsCommentsAndDetectsMutationFixtures() {
        assertTrue(findForbidden(stripComments("/* Bitmap.createBitmap */ val ok = 1")).isEmpty())
        assertTrue(findForbidden("val image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)").isNotEmpty())
        assertTrue(findForbidden("MediaStore.Images.Media.insertImage").isNotEmpty())
        assertTrue(findForbidden("context.startActivity(Intent(Intent.ACTION_SEND))").isNotEmpty())
    }

    private fun stats(
        year: Int,
        complete: Boolean = true,
        playCount: Int? = 2,
        activeDays: Int? = 1,
        totalPlayMs: Long? = 60_000L,
    ) = YearlyStats(
        year = year,
        totalPlayMs = totalPlayMs,
        playCount = playCount,
        activeDays = activeDays,
        complete = complete,
    )

    private fun findForbidden(source: String): List<String> = FORBIDDEN.filter { source.contains(it, ignoreCase = true) }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""//[^\r\n]*"""), " ")

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("Source not found: $relativePath")
    }

    private companion object {
        val FORBIDDEN = listOf(
            "Bitmap",
            "MediaStore",
            "Downloads",
            "DownloadManager",
            "FileProvider",
            "ACTION_SEND",
            "ShareCompat",
            "FileOutputStream",
            "openFileOutput",
            "writeText",
            "OkHttp",
            "Retrofit",
            "HttpURLConnection",
            "URL(",
        )
    }
}
