package com.nasmusic.tv.appwidget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WidgetUpdateThrottle(
    context: Context?,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 800L,
    private val updater: () -> Unit = { context?.let { RemotePlaybackWidget.updateAll(it) } },
) {
    private var pendingJob: Job? = null

    fun requestUpdate() {
        if (pendingJob?.isActive == true) return
        pendingJob = scope.launch {
            delay(intervalMs)
            updater()
        }
    }
}

object RemotePlaybackWidget {
    const val ACTION_TOGGLE_PLAY = "com.nasmusic.tv.widget.TOGGLE_PLAY"
    const val ACTION_PREVIOUS = "com.nasmusic.tv.widget.PREVIOUS"
    const val ACTION_NEXT = "com.nasmusic.tv.widget.NEXT"
    const val ACTION_TOGGLE_MODE = "com.nasmusic.tv.widget.TOGGLE_MODE"
    const val ACTION_CYCLE_SLEEP_TIMER = "com.nasmusic.tv.widget.CYCLE_SLEEP_TIMER"
    const val ACTION_TOGGLE_FAVORITE = "com.nasmusic.tv.widget.TOGGLE_FAVORITE"

    fun updateAll(context: Context) {
        val app = context.applicationContext as NasMusicApp
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RemotePlaybackWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = app.playerManager.playerState.value
        val song = state.currentSong
        val isPlaying = app.playerManager.isPlaying.value
        val mode = app.externalPlaybackController.playMode.value
        val favorite = song != null && app.externalPlaybackController.isFavoriteSync(song)
        val snapshot = WidgetState.snapshot(song, isPlaying, favorite, mode, state.queue.isNotEmpty())
        val views = android.widget.RemoteViews(context.packageName, R.layout.widget_remote_4x2).apply {
            setTextViewText(R.id.widget_title, snapshot.title ?: context.getString(R.string.widget_no_song))
            setTextViewText(R.id.widget_subtitle, snapshot.subtitle ?: context.getString(R.string.widget_idle))
            setImageViewResource(
                R.id.widget_play,
                if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            setImageViewResource(
                R.id.widget_favorite,
                if (snapshot.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            setTextViewText(R.id.widget_mode, when (snapshot.playMode) {
                PlayMode.SEQUENTIAL -> context.getString(R.string.play_mode_sequential)
                PlayMode.SHUFFLE -> context.getString(R.string.play_mode_shuffle)
                PlayMode.REPEAT_ONE -> context.getString(R.string.play_mode_repeat_one)
                PlayMode.REPEAT_ALL -> context.getString(R.string.play_mode_repeat_all)
            })
            setOnClickPendingIntent(R.id.widget_previous, action(context, ACTION_PREVIOUS))
            setOnClickPendingIntent(R.id.widget_play, action(context, ACTION_TOGGLE_PLAY))
            setOnClickPendingIntent(R.id.widget_next, action(context, ACTION_NEXT))
            setOnClickPendingIntent(R.id.widget_mode, action(context, ACTION_TOGGLE_MODE))
            setOnClickPendingIntent(R.id.widget_sleep, action(context, ACTION_CYCLE_SLEEP_TIMER))
            setOnClickPendingIntent(R.id.widget_favorite, action(context, ACTION_TOGGLE_FAVORITE))
            setBoolean(R.id.widget_play, "setEnabled", snapshot.hasQueue)
            setBoolean(R.id.widget_previous, "setEnabled", snapshot.hasQueue)
            setBoolean(R.id.widget_next, "setEnabled", snapshot.hasQueue)
            setBoolean(R.id.widget_favorite, "setEnabled", snapshot.title != null)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, RemotePlaybackWidgetProvider::class.java).apply {
            this.action = action
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            flags
        )
    }
}
