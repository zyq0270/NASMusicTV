package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.local.DuplicateMatch
import com.nasmusic.tv.backend.local.DuplicateSong
import com.nasmusic.tv.backend.local.DuplicateDetector
import com.nasmusic.tv.data.model.DuplicateResolution
import com.nasmusic.tv.data.model.MetadataOverride
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MetadataCleanupEntry(
    val song: Song,
    val stableKey: String,
    val source: SongSourceIdentity,
)

data class MetadataCleanupUiState(
    val loading: Boolean = true,
    val songs: List<MetadataCleanupEntry> = emptyList(),
    val overrides: Map<String, MetadataOverride> = emptyMap(),
    val duplicateMatches: List<DuplicateMatch> = emptyList(),
    val resolutions: Map<String, DuplicateResolution> = emptyMap(),
)

/** 元数据整理页状态源；只写 DataStore 覆盖层，不写 Room、MediaStore 或 NAS。 */
class MetadataCleanupViewModel(app: Application) : AndroidViewModel(app) {
    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.roadmapPreferences
    private val readModel = nasMusicApp.localMusicRepository.readModel
    private val rawSongs = MutableStateFlow<List<MetadataCleanupEntry>>(emptyList())
    private val loading = MutableStateFlow(true)
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadGeneration = 0L

    private val overrides = prefs.metadataOverridesFlow()
    private val resolutions = prefs.duplicateResolutionsFlow()

    val uiState: StateFlow<MetadataCleanupUiState> = combine(
        rawSongs,
        overrides,
        resolutions,
        loading,
        readModel.snapshots,
    ) { songs, overrideMap, resolutionMap, isLoading, snapshot ->
        val duplicateSongs = songs.mapNotNull { entry ->
            val displayed = readModel.apply(entry.song, entry.source, snapshot)
                ?: return@mapNotNull null
            DuplicateSong(displayed, entry.stableKey, entry.source)
        }
        val hiddenKeys = resolutionMap.values.flatMap { it.hideKeys }.toSet()
        MetadataCleanupUiState(
            loading = isLoading,
            songs = songs,
            overrides = overrideMap,
            duplicateMatches = DuplicateDetector.detect(duplicateSongs)
                .filter { match -> match.first.stableKey !in hiddenKeys && match.second.stableKey !in hiddenKeys },
            resolutions = resolutionMap,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MetadataCleanupUiState(),
    )

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            loading.value = true
            try {
                if (generation == loadGeneration) {
                    rawSongs.value = withContext(Dispatchers.IO) {
                    val entries = linkedMapOf<String, MetadataCleanupEntry>()
                    nasMusicApp.localMusicRepository.loadAllRaw().forEach { song ->
                        val source = SongKeys.bindSource(song)?.takeIf { it.kind == SongSourceKind.LOCAL }
                            ?: return@forEach
                        val key = runCatching { SongKeys.stableKey(song, source) }.getOrNull()
                            ?: return@forEach
                        entries[key] = MetadataCleanupEntry(song, key, source)
                    }
                    val connection = nasMusicApp.backendRegistry.getActiveConnection()
                    if (connection != null) {
                        val (adapter, config) = connection
                        var offset = 0
                        val pageSize = 500
                        while (true) {
                            val batch = adapter.getSongs(pageSize, offset)
                            if (batch.isEmpty()) break
                            batch.forEach { original ->
                                val song = original.stripSyntheticRadioPrefix()
                                if (song.isNetworkSong || song.isLocalSong || song.id.startsWith("imported_")) return@forEach
                                val source = SongSourceIdentity(
                                    kind = SongSourceKind.NAS,
                                    nativeId = song.id,
                                    serverId = config.id,
                                    backendType = adapter.backendType,
                                )
                                val key = runCatching { SongKeys.stableKey(song, source) }.getOrNull()
                                    ?: return@forEach
                                entries[key] = MetadataCleanupEntry(song, key, source)
                            }
                            offset += batch.size
                            if (batch.size < pageSize) break
                        }
                    }
                    entries.values.sortedWith(compareBy({ it.song.title.lowercase() }, { it.stableKey }))
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 整理页失败时保留上一批原始数据，下重开页面试。
            } finally {
                if (generation == loadGeneration) loading.value = false
            }
        }
    }

    private fun Song.stripSyntheticRadioPrefix(): Song = copy(
        id = when {
            id.startsWith("nasf_") -> id.removePrefix("nasf_")
            id.startsWith("nas_") -> id.removePrefix("nas_")
            else -> id
        }
    )

    fun saveOverride(
        stableKey: String,
        title: String?,
        artist: String?,
        album: String?,
        year: String?,
        genre: String?,
    ) {
        val value = MetadataOverride(
            stableKey = stableKey,
            title = title,
            artist = artist,
            album = album,
            year = year?.trim()?.toIntOrNull(),
            genre = genre,
        )
        viewModelScope.launch { prefs.putMetadataOverride(value) }
    }

    fun clearOverride(stableKey: String) {
        viewModelScope.launch { prefs.removeMetadataOverride(stableKey) }
    }

    fun keepDuplicate(match: DuplicateMatch, keepFirst: Boolean) {
        val keep = if (keepFirst) match.first.stableKey else match.second.stableKey
        val hide = if (keepFirst) match.second.stableKey else match.first.stableKey
        viewModelScope.launch {
            val current = resolutions.first()
            // 先移除所有“把新 keep 藏起来”的旧边，避免用户反转选择后形成环。
            current.forEach { (existingKeep, resolution) ->
                val hidden = resolution.hideKeys.toMutableSet()
                hidden.remove(keep)
                // existingKeep == keep 的边在下方按最新 hideKeys 一次写回，
                // 不在循环里先写，避免随后又用 current 中的旧集合覆盖它。
                if (hidden != resolution.hideKeys) {
                    if (hidden.isEmpty()) {
                        prefs.removeDuplicateResolution(existingKeep)
                    } else {
                        prefs.putDuplicateResolution(resolution.copy(hideKeys = hidden))
                    }
                }
            }
            val existing = current[keep]
            if (existing != null) {
                prefs.putDuplicateResolution(
                    existing.copy(hideKeys = existing.hideKeys - keep + hide)
                )
            } else {
                prefs.putDuplicateResolution(DuplicateResolution(keepKey = keep, hideKeys = setOf(hide)))
            }
        }
    }

    fun restoreDuplicate(keepKey: String) {
        viewModelScope.launch { prefs.removeDuplicateResolution(keepKey) }
    }
}
