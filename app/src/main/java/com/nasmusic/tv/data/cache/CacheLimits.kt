package com.nasmusic.tv.data.cache

import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.OfflinePolicy
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import kotlinx.coroutines.flow.first

/**
 * 缓存数量上限的进程内快照。
 *
 * 淘汰热路径只读 [current] 的不可变快照，不访问 DataStore。启动时由 Application
 * 调用 [loadFrom]，设置保存成功后由 [set] 原子更新；非法输入在写盘前拒绝。
 */
object CacheLimits {
    val RANGES: Map<CacheLimitKind, IntRange> = CacheLimitsSnapshot.RANGES

    @Volatile
    private var snapshot: CacheLimitsSnapshot = CacheLimitsSnapshot()

    fun current(): CacheLimitsSnapshot = snapshot

    /** 防御性收敛持久化值；加载是 IO 路径，不进入缓存淘汰热路径。 */
    suspend fun loadFrom(prefs: RoadmapPreferences) {
        val loaded = prefs.cacheLimitsFlow().first()
        snapshot = CacheLimitsSnapshot(
            lyricsMaxEntries = clamp(CacheLimitKind.LYRICS_ENTRIES, loaded.lyricsMaxEntries),
            coverUrlMaxEntries = clamp(CacheLimitKind.COVER_URL_ENTRIES, loaded.coverUrlMaxEntries),
            playUrlMaxEntries = clamp(CacheLimitKind.PLAY_URL_ENTRIES, loaded.playUrlMaxEntries),
        )
    }

    /** 只有合法值才落盘，成功后立即更新快照；不静默修正用户输入。 */
    suspend fun set(prefs: RoadmapPreferences, kind: CacheLimitKind, value: Int): Result<Unit> {
        if (value !in RANGES.getValue(kind)) {
            return Result.failure(IllegalArgumentException("$value not in ${RANGES.getValue(kind)}"))
        }
        return prefs.setCacheLimit(kind, value).onSuccess { updateInMemory(kind, value) }
    }

    fun clamp(kind: CacheLimitKind, value: Int): Int = value.coerceIn(RANGES.getValue(kind))

    private fun updateInMemory(kind: CacheLimitKind, value: Int) {
        val old = snapshot
        snapshot = when (kind) {
            CacheLimitKind.LYRICS_ENTRIES -> old.copy(lyricsMaxEntries = value)
            CacheLimitKind.COVER_URL_ENTRIES -> old.copy(coverUrlMaxEntries = value)
            CacheLimitKind.PLAY_URL_ENTRIES -> old.copy(playUrlMaxEntries = value)
        }
    }
}

/** §7.5：搜索离线优先只收窄跨源搜索，不改变播放、歌词、封面等链路的网络权限。 */
fun effectiveSources(
    policy: OfflinePolicy,
    enabled: Boolean,
    allSources: Set<MusicSourceType>,
): Set<MusicSourceType> = when (policy) {
    OfflinePolicy.OFF -> allSources
    OfflinePolicy.LOCAL_ONLY -> if (enabled) {
        allSources.filterTo(linkedSetOf()) { it == MusicSourceType.LOCAL || it == MusicSourceType.DOWNLOAD }
    } else {
        emptySet()
    }
}
