package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.PlayEnded
import com.nasmusic.tv.data.model.PlayStarted
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class YearlyStatsRecorder(
    private val preferences: RoadmapPreferences,
    scope: CoroutineScope,
) {
    private sealed interface Event {
        data class Started(val event: PlayStarted, val song: Song?) : Event
        data class Ended(val event: PlayEnded) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    YearlyStatsWriteGate.mutex.lock()
                    try {
                        preferences.updateYearlyStats { current ->
                            when (event) {
                                is Event.Started -> {
                                    val year = YearlyStatsWriter.yearOf(event.event.startedAtMs)
                                    current + (year to YearlyStatsWriter.applyStarted(
                                        current[year],
                                        event.event,
                                        event.song,
                                    ))
                                }
                                is Event.Ended -> {
                                    val year = YearlyStatsWriter.yearOf(event.event.startedAtMs)
                                    current + (year to YearlyStatsWriter.applyEnded(
                                        current[year],
                                        event.event,
                                    ))
                                }
                            }
                        }
                    } finally {
                        YearlyStatsWriteGate.mutex.unlock()
                    }
                } catch (t: Throwable) {
                    t.rethrowIfCancellation()
                    AppLog.e("YearlyStatsRecorder", " yearly event persist failed", t)
                }
            }
        }
    }

    fun recordStarted(event: PlayStarted, song: Song?) {
        events.trySend(Event.Started(event, song))
    }

    fun recordEnded(event: PlayEnded) {
        events.trySend(Event.Ended(event))
    }
}
