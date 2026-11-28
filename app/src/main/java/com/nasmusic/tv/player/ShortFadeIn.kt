package com.nasmusic.tv.player

import android.os.Handler
import android.os.Looper
import com.nasmusic.tv.util.AppLog

/** Single-player short fade-in used only when GAPLESS metadata/capability is unsupported. */
class ShortFadeIn(
    private val looper: Looper = Looper.getMainLooper(),
    private val frameMs: Long = 20L,
) {
    private val handler = Handler(looper)
    private var runnable: Runnable? = null

    fun start(
        player: androidx.media3.exoplayer.ExoPlayer,
        writeVolume: (Float) -> Unit,
        durationMs: Long = SeamlessPolicy.SHORT_FADE_MS,
        onComplete: () -> Unit = {},
    ) {
        cancel()
        val duration = durationMs.coerceIn(1L, 2000L)
        val steps = ((duration + frameMs - 1) / frameMs).coerceAtLeast(1)
        var step = 0
        val task = object : Runnable {
            override fun run() {
                step++
                val factor = (step.toFloat() / steps).coerceAtMost(1f)
                try {
                    writeVolume(factor)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "short fade failed", t)
                    runnable = null
                    return
                }
                if (step >= steps) {
                    runnable = null
                    onComplete()
                } else {
                    handler.postDelayed(this, frameMs)
                }
            }
        }
        runnable = task
        writeVolume(0f)
        handler.postDelayed(task, frameMs)
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    companion object {
        private const val TAG = "ShortFadeIn"
    }
}
