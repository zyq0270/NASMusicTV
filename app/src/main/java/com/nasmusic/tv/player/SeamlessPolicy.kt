package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.SeamlessMode

/** Pure policy/migration rules; no Android dependencies and exactly one transition engine. */
object SeamlessPolicy {
    fun migrate(seamlessMode: SeamlessMode?, legacyCrossfadeEnabled: Boolean): SeamlessMode =
        seamlessMode ?: if (legacyCrossfadeEnabled) SeamlessMode.CROSSFADE else SeamlessMode.OFF

    fun crossfadeEnabled(mode: SeamlessMode, legacyCrossfadeEnabled: Boolean, durationSec: Int): Boolean =
        when (mode) {
            SeamlessMode.OFF -> legacyCrossfadeEnabled && durationSec > 0
            SeamlessMode.CROSSFADE -> durationSec > 0
            SeamlessMode.GAPLESS -> false
        }

    fun shouldProbeGapless(mode: SeamlessMode): Boolean = mode == SeamlessMode.GAPLESS

    const val SHORT_FADE_MS = 200L
}
