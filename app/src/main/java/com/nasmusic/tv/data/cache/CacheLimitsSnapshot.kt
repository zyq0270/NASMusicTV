package com.nasmusic.tv.data.cache

enum class CacheLimitKind { LYRICS_ENTRIES, COVER_URL_ENTRIES, PLAY_URL_ENTRIES }

data class CacheLimitsSnapshot(
    val lyricsMaxEntries: Int = 2_000,
    val coverUrlMaxEntries: Int = 10_000,
    val playUrlMaxEntries: Int = 500,
) {
    companion object {
        val RANGES = mapOf(
            CacheLimitKind.LYRICS_ENTRIES to 100..20_000,
            CacheLimitKind.COVER_URL_ENTRIES to 100..50_000,
            CacheLimitKind.PLAY_URL_ENTRIES to 50..5_000,
        )

        fun defaultValue(kind: CacheLimitKind): Int = when (kind) {
            CacheLimitKind.LYRICS_ENTRIES -> 2_000
            CacheLimitKind.COVER_URL_ENTRIES -> 10_000
            CacheLimitKind.PLAY_URL_ENTRIES -> 500
        }

        fun clamp(kind: CacheLimitKind, value: Int): Int =
            value.coerceIn(RANGES.getValue(kind))
    }
}
