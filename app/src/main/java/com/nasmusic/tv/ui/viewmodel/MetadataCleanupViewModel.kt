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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * 重复项隐藏边的纯状态变换。RoadmapPreferences 只提供单条 put/remove，
 * 因此先在这里算出「整张目标表」，再串行写出，避免半写状态形成隐藏环。
 */
internal object DuplicateResolutionGuard {
    /** 计算 keep 隐藏 hide 后的目标表；任何指向 keepKey 的旧边都会先被清掉。 */
    fun withKeep(
        current: Map<String, DuplicateResolution>,
        keep: String,
        hide: String,
    ): Map<String, DuplicateResolution>? {
        if (keep.isBlank() || hide.isBlank() || keep == hide) return null
        val next = current.toMutableMap()
        current.forEach { (existingKeep, resolution) ->
            val hidden = resolution.hideKeys - keep
            if (existingKeep == keep) {
                next[existingKeep] = resolution.copy(hideKeys = hidden + hide)
            } else if (hidden.isEmpty()) {
                next.remove(existingKeep)
            } else {
                next[existingKeep] = resolution.copy(hideKeys = hidden)
            }
        }
        if (keep !in next) {
            next[keep] = DuplicateResolution(keepKey = keep, hideKeys = setOf(hide))
        }
        return removeHiddenKeeps(next)
    }

    /** 撤销 keepKey 对应的边，并顺带解除它对其他条目的隐藏。 */
    fun withRestore(
        current: Map<String, DuplicateResolution>,
        keepKey: String,
    ): Map<String, DuplicateResolution>? {
        val next = current.toMutableMap()
        next.remove(keepKey)
        current.forEach { (existingKeep, resolution) ->
            if (keepKey in resolution.hideKeys) {
                val hidden = resolution.hideKeys - keepKey
                if (hidden.isEmpty()) next.remove(existingKeep)
                else next[existingKeep] = resolution.copy(hideKeys = hidden)
            }
        }
        return removeHiddenKeeps(next)
    }

    /** 写前环检测：若存在「被隐藏的 keepKey」，删除所有这类隐藏边后再写。 */
    fun hasHiddenKeep(map: Map<String, DuplicateResolution>): Boolean {
        val keeps = map.values.asSequence().map { it.keepKey }.toSet()
        val hidden = map.values.asSequence().flatMap { it.hideKeys.asSequence() }.toSet()
        return keeps.any { it in hidden }
    }

    private fun removeHiddenKeeps(map: Map<String, DuplicateResolution>): Map<String, DuplicateResolution>? {
        if (!hasHiddenKeep(map)) return map
        val keeps = map.values.asSequence().map { it.keepKey }.toSet()
        val cleaned = map.mapValues { (_, resolution) ->
            resolution.copy(hideKeys = resolution.hideKeys - keeps)
        }.filterValues { it.hideKeys.isNotEmpty() }
        return cleaned.takeUnless { hasHiddenKeep(it) }
    }
}

/** 元数据整理页状态源；只写 DataStore 覆盖层，不写 Room、MediaStore 或 NAS。 */
class MetadataCleanupViewModel(app: Application) : AndroidViewModel(app) {
    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.roadmapPreferences
    private val readModel = nasMusicApp.localMusicRepository.readModel
    private val rawSongs = MutableStateFlow<List<MetadataCleanupEntry>>(emptyList())
    private val loading = MutableStateFlow(true)
    private var loadJob: kotlinx.coroutines.Job? = null
    private var loadGeneration = 0L
    /** 重复项边的所有写操作共用一把锁；put/remove 各自 edit，不能交错。 */
    private val resolutionMutex = Mutex()

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
            resolutionMutex.withLock {
                val current = resolutions.first()
                val next = DuplicateResolutionGuard.withKeep(current, keep, hide) ?: return@withLock
                writeResolutionDiff(current, next)
            }
        }
    }

    fun restoreDuplicate(keepKey: String) {
        viewModelScope.launch {
            resolutionMutex.withLock {
                val current = resolutions.first()
                val next = DuplicateResolutionGuard.withRestore(current, keepKey) ?: return@withLock
                writeResolutionDiff(current, next)
            }
        }
    }

    /** 在持有 [resolutionMutex] 时调用：逐条把 current 变更到 next。 */
    private suspend fun writeResolutionDiff(
        current: Map<String, DuplicateResolution>,
        next: Map<String, DuplicateResolution>,
    ) {
        (current.keys + next.keys).toSet().forEach { key ->
            val before = current[key]
            val after = next[key]
            when {
                before == null && after != null -> prefs.putDuplicateResolution(after)
                before != null && after == null -> prefs.removeDuplicateResolution(key)
                before != null && after != null && after != before -> prefs.putDuplicateResolution(after)
            }
        }
    }
}
