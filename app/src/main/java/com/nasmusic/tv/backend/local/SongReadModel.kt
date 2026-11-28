package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.DuplicateResolution
import com.nasmusic.tv.data.model.MetadataOverride
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.util.SongKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/** 一次 override/resolution 合并后的不可变展示快照。 */
data class SongReadSnapshot(
    val loaded: Boolean = false,
    val overrides: Map<String, MetadataOverride> = emptyMap(),
    val hiddenKeys: Set<String> = emptySet(),
)

/**
 * 元数据覆盖与重复项隐藏的唯一展示入口。
 *
 * 持久化 Map 先合并成不可变快照，读取路径只消费快照，避免在筛选/排序途中
 * 读到不同批次的 override。未绑定稳定身份的歌曲原样返回，不能凭标题等
 * 可变字段生成持久化 key。
 */
class SongReadModel(
    overrides: Flow<Map<String, MetadataOverride>>,
    resolutions: Flow<Map<String, DuplicateResolution>>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** 可被曲库 Flow 收集：override/resolution 任一变化都会发布新快照。 */
    val snapshots: StateFlow<SongReadSnapshot> = combine(overrides, resolutions) { overrideMap, resolutionMap ->
        // F-D02：即使持久化数据里出现 A↔B 隐藏环，也绝不把任何 keepKey 隐藏掉，
        // 否则两首歌会同时从曲库消失（统计层的 StatsAliasResolver 救不回来）。
        val keepKeys = resolutionMap.values.asSequence()
            .map { it.keepKey }
            .filter { it.isNotBlank() }
            .toSet()
        SongReadSnapshot(
            loaded = true,
            overrides = overrideMap.toSortedMap(),
            hiddenKeys = resolutionMap.values
                .asSequence()
                .flatMap { resolution -> resolution.hideKeys.asSequence().map { it to resolution.keepKey } }
                .filter { (hiddenKey, keepKey) ->
                    hiddenKey.isNotBlank() && hiddenKey != keepKey && hiddenKey !in keepKeys
                }
                .map { (hiddenKey, _) -> hiddenKey }
                .toSet(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, SongReadSnapshot())

    /** DataStore 首帧到达后再读曲库，避免启动扫描早于覆盖快照。 */
    suspend fun awaitLoaded() {
        snapshots.filter { it.loaded }.first()
    }

    val hasOverrides: Boolean
        get() = snapshots.value.overrides.isNotEmpty()

    fun apply(song: Song, source: SongSourceIdentity?): Song? =
        apply(song, source, snapshots.value)

    fun apply(
        song: Song,
        source: SongSourceIdentity?,
        snapshot: SongReadSnapshot,
        includeHidden: Boolean = false,
    ): Song? {
        val key = runCatching { source?.let { SongKeys.stableKey(song, it) } }.getOrNull()
            ?: return song
        if (!includeHidden && key in snapshot.hiddenKeys) return null
        val override = snapshot.overrides[key] ?: return song
        return song.copy(
            title = override.title ?: song.title,
            artist = override.artist ?: song.artist,
            album = override.album ?: song.album,
            year = override.year ?: song.year,
            genre = override.genre ?: song.genre,
            coverUrl = override.coverRef ?: song.coverUrl,
        )
    }

    /** 已存在的队列/歌单也要显示 override，但其中的重复项仍可播放。 */
    fun applyIncludingHidden(song: Song, source: SongSourceIdentity?): Song? =
        applyIncludingHidden(song, source, snapshots.value)

    fun applyIncludingHidden(
        song: Song,
        source: SongSourceIdentity?,
        snapshot: SongReadSnapshot,
    ): Song? = apply(song, source, snapshot, includeHidden = true)

    fun applyAll(
        songs: List<Song>,
        sourceFor: (Song) -> SongSourceIdentity? = { SongKeys.bindSource(it) },
    ): List<Song> = applyAll(songs, sourceFor, snapshots.value)

    fun applyAll(
        songs: List<Song>,
        sourceFor: (Song) -> SongSourceIdentity?,
        snapshot: SongReadSnapshot,
    ): List<Song> = songs.mapNotNull { apply(it, sourceFor(it), snapshot) }

    fun applyAllIncludingHidden(
        songs: List<Song>,
        sourceFor: (Song) -> SongSourceIdentity?,
        snapshot: SongReadSnapshot = snapshots.value,
    ): List<Song> = songs.mapNotNull { applyIncludingHidden(it, sourceFor(it), snapshot) }
}
