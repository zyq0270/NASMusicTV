package com.nasmusic.tv.data.model

data class YearlyStats(
    val year: Int,
    val totalPlayMs: Long? = null,
    val playCount: Int? = null,
    val activeDays: Int? = null,
    val activeDayKeys: Set<String> = emptySet(),
    val topSongs: List<SongCount> = emptyList(),
    val topArtists: List<NameCount> = emptyList(),
    val firstPlayAtMs: Long? = null,
    val lastPlayAtMs: Long? = null,
    val complete: Boolean,
    val coverageStartMs: Long? = null,
    val coverageEndMs: Long? = null,
    val schemaVersion: Int = 2,
    val backfilled: Boolean = false,
)

data class SongCount(
    val key: String,
    val title: String,
    val artist: String,
    val count: Int,
)

data class NameCount(
    val name: String,
    val count: Int,
)
