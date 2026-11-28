package com.nasmusic.tv.data.model

data class PlayStarted(
    val songKey: String,
    val startedAtMs: Long,
)

data class PlayEnded(
    val songKey: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationPlayedMs: Long,
)
