package com.nasmusic.tv.appwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.nasmusic.tv.NasMusicApp

class RemotePlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RemotePlaybackWidget.updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val app = context.applicationContext as NasMusicApp
        when (action) {
            ACTION_TOGGLE_PLAY, ACTION_PREVIOUS, ACTION_NEXT, ACTION_TOGGLE_MODE, ACTION_CYCLE_SLEEP_TIMER, ACTION_TOGGLE_FAVORITE -> {
                app.externalPlaybackController.handleExternalAction(action)
                // F-E3：不再直接 updateAll —— 与 IO 协程的收藏/切歌更新共用同一节流器，
                // 避免主线程与后台各写一次 widget。
                app.externalPlaybackController.requestWidgetUpdate()
            }
        }
    }
}

const val ACTION_TOGGLE_PLAY = RemotePlaybackWidget.ACTION_TOGGLE_PLAY
const val ACTION_PREVIOUS = RemotePlaybackWidget.ACTION_PREVIOUS
const val ACTION_NEXT = RemotePlaybackWidget.ACTION_NEXT
const val ACTION_TOGGLE_MODE = RemotePlaybackWidget.ACTION_TOGGLE_MODE
const val ACTION_CYCLE_SLEEP_TIMER = RemotePlaybackWidget.ACTION_CYCLE_SLEEP_TIMER
const val ACTION_TOGGLE_FAVORITE = RemotePlaybackWidget.ACTION_TOGGLE_FAVORITE
