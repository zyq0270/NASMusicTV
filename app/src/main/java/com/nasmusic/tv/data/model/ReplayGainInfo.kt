package com.nasmusic.tv.data.model

enum class ReplayGainSource { TAG_ID3, TAG_FLAC, TAG_OGG, NONE }

data class ReplayGainInfo(
    val dB: Float = 0f,
    val source: ReplayGainSource = ReplayGainSource.NONE,
) {
    companion object {
        val NONE = ReplayGainInfo()
    }
}
