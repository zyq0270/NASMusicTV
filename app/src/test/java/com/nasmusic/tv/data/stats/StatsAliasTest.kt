package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.DuplicateResolution
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsAliasTest {
    @Test
    fun `chain resolves to canonical and conserves total`() {
        val resolver = StatsAliasResolver(
            mapOf(
                "A" to DuplicateResolution("A", setOf("B")),
                "B" to DuplicateResolution("B", setOf("C")),
            )
        )

        val resolved = resolver.resolveCounts(mapOf("A" to 2, "B" to 3, "C" to 4))

        assertEquals(mapOf("A" to 9), resolved)
        assertEquals(9, resolved.values.sum())
    }

    @Test
    fun `cycle chooses lexicographically smallest key and conserves total`() {
        val resolver = StatsAliasResolver(
            mapOf(
                "B" to DuplicateResolution("B", setOf("A")),
                "A" to DuplicateResolution("A", setOf("B")),
                "C" to DuplicateResolution("C", setOf("A")),
            )
        )

        val resolved = resolver.resolveCounts(mapOf("A" to 2, "B" to 3, "C" to 4))

        assertEquals(mapOf("A" to 9), resolved)
        assertEquals("A", resolver.resolveKey("C"))
    }

    @Test
    fun `missing canonical falls back to original visible key`() {
        val resolver = StatsAliasResolver(mapOf("A" to DuplicateResolution("A", setOf("B"))))

        assertEquals(mapOf("B" to 3), resolver.resolveCounts(mapOf("B" to 3)))
        assertEquals(mapOf("B" to 3, "C" to 1), resolver.resolveCounts(mapOf("B" to 3, "C" to 1)))
    }

    @Test
    fun `available canonical metadata receives hidden plays even with zero period plays`() {
        val resolver = StatsAliasResolver(mapOf("A" to DuplicateResolution("A", setOf("B"))))

        assertEquals(
            mapOf("A" to 3),
            resolver.resolveCounts(mapOf("B" to 3), availableKeys = setOf("A", "B")),
        )
    }

    @Test
    fun `monthly and daily maps use one alias policy`() {
        val resolver = StatsAliasResolver(mapOf("A" to DuplicateResolution("A", setOf("B"))))
        val monthly = mapOf(
            "2026-01" to mapOf("A" to 1, "B" to 2),
            "2026-02" to mapOf("B" to 4),
        )
        val dailyBySong = mapOf("2026-01-01" to mapOf("A" to 2, "B" to 3))
        val dailyTotals = mapOf("2026-01-01" to 5, "2026-01-02" to 2)

        assertEquals(mapOf("2026-01" to mapOf("A" to 3), "2026-02" to mapOf("B" to 4)), resolver.resolveMonthly(monthly))
        assertEquals(mapOf("2026-01-01" to mapOf("A" to 5)), resolver.resolveDailyBySong(dailyBySong))
        assertEquals(dailyTotals, resolver.resolveDaily(dailyTotals))
        assertEquals(7, resolver.resolveDaily(dailyTotals).values.sum())
    }

    @Test
    fun `metadata prefers canonical and recovery removes aliases`() {
        val resolutions = mapOf("A" to DuplicateResolution("A", setOf("B")))
        val resolver = StatsAliasResolver(resolutions)
        val metadata = mapOf("A" to "canonical", "B" to "hidden")

        assertEquals(mapOf("A" to "canonical"), resolver.resolveMetadata(metadata))
        assertEquals(metadata, StatsAliasResolver(emptyMap()).resolveMetadata(metadata))
        assertEquals(mapOf("A" to 2, "B" to 3), StatsAliasResolver(emptyMap()).resolveCounts(mapOf("A" to 2, "B" to 3)))
    }

    @Test
    fun `self hide is ignored`() {
        val resolver = StatsAliasResolver(mapOf("A" to DuplicateResolution("A", setOf("A", "B"))))

        assertEquals(mapOf("A" to 5), resolver.resolveCounts(mapOf("A" to 2, "B" to 3)))
    }
}
