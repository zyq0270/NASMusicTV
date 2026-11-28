package com.nasmusic.tv.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.backend.local.AudioHeaderRequest
import com.nasmusic.tv.backend.local.AudioHeaderSource
import com.nasmusic.tv.backend.local.Media3AudioHeaderSource
import com.nasmusic.tv.backend.local.ReplayGainTagReader
import com.nasmusic.tv.data.model.ReplayGainInfo
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * Sole ExoPlayer.volume writer. ReplayGain has global zero-dB headroom: negative gain is
 * attenuated exactly, positive gain stays at unity (never clamp-disguised normalization),
 * and untagged songs stay at unity. Settings default OFF.
 */
class ReplayGainController private constructor(
    private val prefs: RoadmapPreferences?,
    private val headerSource: AudioHeaderSource?,
) {
    constructor(
        context: Context,
        prefs: RoadmapPreferences = RoadmapPreferences.getInstance(context),
        headerSource: AudioHeaderSource = Media3AudioHeaderSource(context),
    ) : this(prefs, headerSource)

    /** Legacy CrossfadeController constructors: attenuation-only, no DataStore or header IO. */
    internal constructor() : this(null, null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val gains = mutableMapOf<String, ReplayGainInfo>()
    private val reading = mutableSetOf<String>()
    private val assignments = WeakHashMap<ExoPlayer, Assignment>()

    @Volatile
    var enabled: Boolean = false
        private set

    data class Assignment(val songKey: String, val generation: Long, val appVolume: Float)

    init {
        prefs?.let { prefs ->
            scope.launch {
                prefs.replayGainEnabledFlow().distinctUntilChanged().collectLatest { value ->
                    enabled = value
                    synchronized(lock) {
                        assignments.keys.toList().forEach { player ->
                            val a = assignments[player] ?: return@forEach
                            writeLocked(player, a.appVolume, a.songKey, 1f)
                        }
                    }
                }
            }
            scope.launch {
                prefs.replayGainMapFlow().distinctUntilChanged().collect { persisted ->
                    synchronized(lock) {
                        persisted.forEach { (key, value) -> if (!gains.containsKey(key)) gains[key] = value }
                        assignments.forEach { (player, assignment) ->
                            if (persisted.containsKey(assignment.songKey)) {
                                writeLocked(player, assignment.appVolume, assignment.songKey, 1f)
                            }
                        }
                    }
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        prefs?.let { scope.launch { it.setReplayGainEnabled(value) } }
        synchronized(lock) {
            assignments.forEach { (p, a) -> writeLocked(p, a.appVolume, a.songKey, 1f) }
        }
    }

    /** Read-ahead only: never writes any player volume or commits a current-song key. */
    fun prepareFor(song: Song, source: SongSourceIdentity?) {
        val bound = SongKeys.bindSource(song, source) ?: return
        val headerSource = headerSource ?: return
        val key = runCatching { SongKeys.stableKey(song, bound) }.getOrNull() ?: return
        synchronized(lock) { if (gains.containsKey(key) || !reading.add(key)) return }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ReplayGainTagReader.read(headerSource, AudioHeaderRequest(song, bound, song.streamUrl.orEmpty(), mimeOf(song.streamUrl)))
                }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; ReplayGainInfo.NONE }
            }
            synchronized(lock) {
                reading.remove(key)
                gains[key] = result
                // Only the key already committed to a player may be refreshed. Preparing the
                // next song still cannot alter the current player's key or volume.
                assignments.forEach { (p, a) -> if (a.songKey == key) writeLocked(p, a.appVolume, key, 1f) }
            }
            if (result.source != com.nasmusic.tv.data.model.ReplayGainSource.NONE) {
                prefs?.let {
                    runCatching { it.putReplayGain(key, result) }
                        .onFailure { AppLog.w(TAG, "gain persist failed: ${result.source}") }
                }
            }
        }
    }

    /** Commit only after the media item really becomes current (or is explicitly sought). */
    fun commit(songKey: String, player: ExoPlayer, appVolume: Float) {
        synchronized(lock) {
            val old = assignments[player]
            val next = Assignment(songKey, (old?.generation ?: 0L) + 1, appVolume.coerceIn(0f, 1f))
            assignments[player] = next
            writeLocked(player, appVolume, songKey, 1f)
        }
    }

    fun writeVolume(player: ExoPlayer, appVolume: Float, songKey: String? = null, fadeFactor: Float = 1f) {
        synchronized(lock) {
            val assigned = assignments[player]
            val key = songKey ?: assigned?.songKey.orEmpty()
            if (songKey == null && assigned != null) {
                assignments[player] = assigned.copy(appVolume = appVolume.coerceIn(0f, 1f))
            }
            writeLocked(player, appVolume, key, fadeFactor)
        }
    }

    fun gainFor(songKey: String): Float = synchronized(lock) {
        if (!enabled) 1f else replayGainLinear(gains[songKey]?.dB ?: 0f)
    }

    fun hasPrepared(songKey: String): Boolean = synchronized(lock) { gains.containsKey(songKey) }

    fun detach() {
        synchronized(lock) { assignments.clear() }
    }

    fun release() {
        scope.cancel()
        detach()
        synchronized(lock) { gains.clear(); reading.clear() }
    }

    private fun writeLocked(player: ExoPlayer, appVolume: Float, songKey: String, fadeFactor: Float) {
        val gain = if (enabled) replayGainLinear(gains[songKey]?.dB ?: 0f) else 1f
        val raw = appVolume * gain * fadeFactor
        player.volume = raw.coerceIn(0f, 1f)
        if (enabled && (gains[songKey]?.dB ?: 0f) > 0f) {
            val marker = "$songKey:${(gains[songKey]?.dB ?: 0f)}"
            if (positiveHeadroomNoted.add(marker)) {
                AppLog.w(TAG, "positive ReplayGain ${gains[songKey]?.dB} dB retained at 0 dB (global headroom)")
                if (positiveHeadroomNoted.size > 128) positiveHeadroomNoted.clear()
            }
        }
    }

    companion object {
        private const val TAG = "ReplayGain"
        private val positiveHeadroomNoted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun replayGainLinear(db: Float): Float {
            if (!db.isFinite() || db !in -24f..24f) return 1f
            if (db > 0f) return 1f
            return Math.pow(10.0, db / 20.0).toFloat()
        }

        fun volume(appVolume: Float, linearGain: Float, fadeFactor: Float): Float =
            (appVolume * linearGain * fadeFactor).coerceIn(0f, 1f)

        private fun mimeOf(url: String?): String? = url?.substringBefore('?')?.substringAfterLast('.')?.lowercase()
    }
}
