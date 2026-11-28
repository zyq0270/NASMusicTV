package com.nasmusic.tv.appwidget

import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song

data class WidgetSnapshot(
    val title: String?,
    val subtitle: String?,
    val isPlaying: Boolean,
    val isFavorite: Boolean,
    val playMode: PlayMode,
    val hasQueue: Boolean,
)

object WidgetState {
    fun snapshot(
        song: Song?,
        isPlaying: Boolean,
        isFavorite: Boolean,
        playMode: PlayMode,
        hasQueue: Boolean,
    ): WidgetSnapshot = WidgetSnapshot(
        title = song?.title,
        subtitle = song?.let { listOfNotNull(it.artist, it.album).filter(String::isNotBlank).joinToString(" · ") },
        isPlaying = isPlaying,
        isFavorite = isFavorite,
        playMode = playMode,
        hasQueue = hasQueue,
    )
}
