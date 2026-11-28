package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.backend.playlist.PlaylistPushCheckpoint
import com.nasmusic.tv.data.cache.CacheLimitKind
import com.nasmusic.tv.data.cache.CacheLimitsSnapshot
import com.nasmusic.tv.data.model.DuplicateResolution
import com.nasmusic.tv.data.model.MetadataOverride
import com.nasmusic.tv.data.model.OfflinePolicy
import com.nasmusic.tv.data.model.ReplayGainInfo
import com.nasmusic.tv.data.model.SeamlessMode
import com.nasmusic.tv.data.model.YearlyStats
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.roadmapDataStore by preferencesDataStore(
    name = "roadmap_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class RoadmapPreferences internal constructor(private val context: Context) {
    private val dataStore: DataStore<Preferences> by lazy { context.roadmapDataStore }
    private val gson = Gson()

    fun replayGainEnabledFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEY_REPLAY_GAIN_ENABLED] ?: false }

    suspend fun setReplayGainEnabled(value: Boolean) {
        dataStore.edit { it[KEY_REPLAY_GAIN_ENABLED] = value }
    }

    fun replayGainMapFlow(): Flow<Map<String, ReplayGainInfo>> =
        dataStore.data.map { prefs ->
            parseMap<ReplayGainInfo>(prefs[KEY_REPLAY_GAIN_MAP], ReplayGainInfo::class.java)
        }

    suspend fun putReplayGain(key: String, info: ReplayGainInfo) {
        dataStore.edit { prefs ->
            val current = parseMapStrict<ReplayGainInfo>(prefs[KEY_REPLAY_GAIN_MAP], ReplayGainInfo::class.java)
                .toMutableMap()
            current[key] = info
            prefs[KEY_REPLAY_GAIN_MAP] = gson.toJson(current)
        }
    }

    fun seamlessModeFlow(): Flow<SeamlessMode> = dataStore.data.map { prefs ->
        runCatching { SeamlessMode.valueOf(prefs[KEY_SEAMLESS_MODE] ?: SeamlessMode.OFF.name) }
            .getOrDefault(SeamlessMode.OFF)
    }

    suspend fun setSeamlessMode(mode: SeamlessMode) {
        dataStore.edit { it[KEY_SEAMLESS_MODE] = mode.name }
    }

    fun cacheLimitsFlow(): Flow<CacheLimitsSnapshot> = dataStore.data.map { prefs ->
        CacheLimitsSnapshot(
            lyricsMaxEntries = prefs[KEY_CACHE_LYRICS] ?: CacheLimitsSnapshot().lyricsMaxEntries,
            coverUrlMaxEntries = prefs[KEY_CACHE_COVERS] ?: CacheLimitsSnapshot().coverUrlMaxEntries,
            playUrlMaxEntries = prefs[KEY_CACHE_PLAY_URLS] ?: CacheLimitsSnapshot().playUrlMaxEntries,
        )
    }

    suspend fun setCacheLimit(kind: CacheLimitKind, value: Int): Result<Unit> {
        val range = CacheLimitsSnapshot.RANGES.getValue(kind)
        if (value !in range) return Result.failure(IllegalArgumentException("$value not in $range"))
        val key = when (kind) {
            CacheLimitKind.LYRICS_ENTRIES -> KEY_CACHE_LYRICS
            CacheLimitKind.COVER_URL_ENTRIES -> KEY_CACHE_COVERS
            CacheLimitKind.PLAY_URL_ENTRIES -> KEY_CACHE_PLAY_URLS
        }
        dataStore.edit { it[key] = value }
        return Result.success(Unit)
    }

    fun offlinePolicyFlow(): Flow<OfflinePolicy> = dataStore.data.map { prefs ->
        runCatching { OfflinePolicy.valueOf(prefs[KEY_OFFLINE_POLICY] ?: OfflinePolicy.OFF.name) }
            .getOrDefault(OfflinePolicy.OFF)
    }

    suspend fun setOfflinePolicy(policy: OfflinePolicy) {
        dataStore.edit { it[KEY_OFFLINE_POLICY] = policy.name }
    }

    fun metadataOverridesFlow(): Flow<Map<String, MetadataOverride>> =
        dataStore.data.map { prefs ->
            parseMap(prefs[KEY_METADATA_OVERRIDES], MetadataOverride::class.java)
        }

    suspend fun putMetadataOverride(value: MetadataOverride) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(prefs[KEY_METADATA_OVERRIDES], MetadataOverride::class.java).toMutableMap()
            current[value.stableKey] = value
            prefs[KEY_METADATA_OVERRIDES] = gson.toJson(current)
        }
    }

    suspend fun removeMetadataOverride(key: String) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(prefs[KEY_METADATA_OVERRIDES], MetadataOverride::class.java).toMutableMap()
            current.remove(key)
            prefs[KEY_METADATA_OVERRIDES] = gson.toJson(current)
        }
    }

    fun duplicateResolutionsFlow(): Flow<Map<String, DuplicateResolution>> =
        dataStore.data.map { prefs ->
            parseMap(prefs[KEY_DUPLICATE_RESOLUTIONS], DuplicateResolution::class.java)
        }

    suspend fun putDuplicateResolution(value: DuplicateResolution) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(prefs[KEY_DUPLICATE_RESOLUTIONS], DuplicateResolution::class.java).toMutableMap()
            current[value.keepKey] = value
            prefs[KEY_DUPLICATE_RESOLUTIONS] = gson.toJson(current)
        }
    }

    suspend fun removeDuplicateResolution(keepKey: String) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(prefs[KEY_DUPLICATE_RESOLUTIONS], DuplicateResolution::class.java).toMutableMap()
            current.remove(keepKey)
            prefs[KEY_DUPLICATE_RESOLUTIONS] = gson.toJson(current)
        }
    }

    fun yearlyStatsFlow(): Flow<Map<Int, YearlyStats>> = dataStore.data.map { prefs ->
        loadYearlyStats(prefs)
    }

    suspend fun putYearlyStats(value: YearlyStats) {
        dataStore.edit { prefs ->
            prefs[yearlyKey(value.year)] = gson.toJson(value)
        }
    }

    suspend fun updateYearlyStats(transform: (Map<Int, YearlyStats>) -> Map<Int, YearlyStats>) {
        dataStore.edit { prefs ->
            writeYearlyStats(prefs, transform(loadYearlyStats(prefs)))
        }
    }

    suspend fun commitYearlyStatsAndMarkDone(stats: Map<Int, YearlyStats>) {
        dataStore.edit { prefs ->
            writeYearlyStats(prefs, stats)
            prefs[KEY_YEARLY_BACKFILL_DONE] = true
        }
    }

    fun yearlyBackfillDoneFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEY_YEARLY_BACKFILL_DONE] ?: false }

    suspend fun setYearlyBackfillDone(value: Boolean) {
        dataStore.edit { it[KEY_YEARLY_BACKFILL_DONE] = value }
    }

    fun playlistPushCheckpointsFlow(): Flow<Map<String, PlaylistPushCheckpoint>> =
        dataStore.data.map { prefs ->
            parseMap(prefs[KEY_PLAYLIST_PUSH_CHECKPOINTS], PlaylistPushCheckpoint::class.java)
        }

    suspend fun putPlaylistPushCheckpoint(key: String, checkpoint: PlaylistPushCheckpoint) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(
                prefs[KEY_PLAYLIST_PUSH_CHECKPOINTS],
                PlaylistPushCheckpoint::class.java,
            ).toMutableMap()
            current[key] = checkpoint
            prefs[KEY_PLAYLIST_PUSH_CHECKPOINTS] = gson.toJson(current)
        }
    }

    suspend fun removePlaylistPushCheckpoint(key: String) {
        dataStore.edit { prefs ->
            val current = parseMapStrict(
                prefs[KEY_PLAYLIST_PUSH_CHECKPOINTS],
                PlaylistPushCheckpoint::class.java,
            ).toMutableMap()
            current.remove(key)
            prefs[KEY_PLAYLIST_PUSH_CHECKPOINTS] = gson.toJson(current)
        }
    }

    private fun loadYearlyStats(prefs: Preferences): Map<Int, YearlyStats> {
        val legacy = parseMap(prefs[KEY_YEARLY_STATS], YearlyStats::class.java)
            .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value } }
            .toMap()
        val isolated = mutableMapOf<Int, YearlyStats>()
        for ((prefKey, raw) in prefs.asMap()) {
            if (!prefKey.name.startsWith(YEARLY_KEY_PREFIX)) continue
            val year = prefKey.name.removePrefix(YEARLY_KEY_PREFIX).toIntOrNull() ?: continue
            val json = raw as? String ?: continue
            val value = try {
                gson.fromJson(json, YearlyStats::class.java)?.copy(year = year)
            } catch (e: Exception) {
                AppLog.e(TAG, "Invalid yearly stats for $year", e)
                null
            }
            if (value != null) isolated[year] = value
        }
        return legacy + isolated
    }

    private fun writeYearlyStats(prefs: MutablePreferences, stats: Map<Int, YearlyStats>) {
        val previous = loadYearlyStats(prefs)
        (previous.keys - stats.keys).forEach { prefs.remove(yearlyKey(it)) }
        for ((year, value) in stats) {
            if (previous[year] != value) prefs[yearlyKey(year)] = gson.toJson(value.copy(year = year))
        }
        if (prefs[KEY_YEARLY_STATS] != null) {
            parseMapStrict<YearlyStats>(prefs[KEY_YEARLY_STATS], YearlyStats::class.java)
            prefs.remove(KEY_YEARLY_STATS)
        }
    }

    private fun yearlyKey(year: Int) = stringPreferencesKey(YEARLY_KEY_PREFIX + year)

    private fun <T> parseMapStrict(json: String?, clazz: Class<T>): Map<String, T> {
        if (json.isNullOrBlank()) return emptyMap()
        val type = TypeToken.getParameterized(Map::class.java, String::class.java, clazz).type
        return gson.fromJson<Map<String, T>>(json, type) ?: emptyMap()
    }

    private fun <T> parseMap(json: String?, clazz: Class<T>): Map<String, T> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = TypeToken.getParameterized(Map::class.java, String::class.java, clazz).type
            gson.fromJson<Map<String, T>>(json, type) ?: emptyMap()
        } catch (e: Exception) {
            AppLog.e("RoadmapPreferences", "Invalid JSON map", e)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "RoadmapPreferences"
        private val KEY_REPLAY_GAIN_ENABLED = booleanPreferencesKey("roadmap_replay_gain_enabled")
        private val KEY_REPLAY_GAIN_MAP = stringPreferencesKey("roadmap_replay_gain_map")
        private val KEY_SEAMLESS_MODE = stringPreferencesKey("roadmap_seamless_mode")
        private val KEY_CACHE_LYRICS = intPreferencesKey("roadmap_cache_lyrics")
        private val KEY_CACHE_COVERS = intPreferencesKey("roadmap_cache_covers")
        private val KEY_CACHE_PLAY_URLS = intPreferencesKey("roadmap_cache_play_urls")
        private val KEY_OFFLINE_POLICY = stringPreferencesKey("roadmap_offline_policy")
        private val KEY_METADATA_OVERRIDES = stringPreferencesKey("roadmap_metadata_overrides")
        private val KEY_DUPLICATE_RESOLUTIONS = stringPreferencesKey("roadmap_duplicate_resolutions")
        private val KEY_YEARLY_STATS = stringPreferencesKey("roadmap_yearly_stats")
        private const val YEARLY_KEY_PREFIX = "roadmap_yearly_stats_"
        private val KEY_YEARLY_BACKFILL_DONE = booleanPreferencesKey("roadmap_yearly_backfill_done")
        private val KEY_PLAYLIST_PUSH_CHECKPOINTS = stringPreferencesKey("roadmap_playlist_push_checkpoints")

        @Volatile
        private var instance: RoadmapPreferences? = null

        fun getInstance(context: Context): RoadmapPreferences =
            instance ?: synchronized(this) {
                instance ?: RoadmapPreferences(context.applicationContext).also { instance = it }
            }
    }
}
