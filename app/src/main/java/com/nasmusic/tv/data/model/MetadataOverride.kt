package com.nasmusic.tv.data.model

data class MetadataOverride(
    val stableKey: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverRef: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class DuplicateResolution(
    val keepKey: String,
    val hideKeys: Set<String>,
    val createdAtMs: Long = System.currentTimeMillis(),
)
