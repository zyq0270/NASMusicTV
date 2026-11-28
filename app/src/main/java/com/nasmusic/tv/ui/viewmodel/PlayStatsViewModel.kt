package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.stats.PlayHeatmap
import com.nasmusic.tv.data.stats.PlayHeatmapBuilder
import com.nasmusic.tv.data.stats.PlayStatsAggregator
import com.nasmusic.tv.data.stats.StatsAliasResolver
import com.nasmusic.tv.data.stats.StatsBundle
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.SongKeys
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放统计 ViewModel（F2-1 / F2-5）
 *
 * 数据来源：
 * - 月度：PlayStatsRepository.monthlyStats（当月 songId 次数）
 * - 累计：AppPreferences.playCounts（历史累计，无时间维度）
 * - 按天：PlayStatsRepository.dailyStats（F2-5 热力图，dateKey -> 次数）
 * - 元数据：recentSongObjects（最近 50 首完整 Song 对象，含网络歌曲）
 */
class PlayStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences
    private val readModel = nasMusicApp.localMusicRepository.readModel

    init {
        viewModelScope.launch {
            readModel.snapshots.collect { snapshot ->
                if (snapshot.loaded && _monthlyBundle.value != null) loadStats()
            }
        }
    }

    private val _monthlyBundle = MutableStateFlow<StatsBundle?>(null)
    val monthlyBundle: StateFlow<StatsBundle?> = _monthlyBundle

    private val _allTimeBundle = MutableStateFlow<StatsBundle?>(null)
    val allTimeBundle: StateFlow<StatsBundle?> = _allTimeBundle

    /** 听歌热力图（F2-5）；null = 尚未加载 */
    private val _heatmap = MutableStateFlow<PlayHeatmap?>(null)
    val heatmap: StateFlow<PlayHeatmap?> = _heatmap

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun loadStats() {
        viewModelScope.launch {
            _loading.value = true
            try {
                // M41：下面整段都是持久化读取（DataStore 首读 + Gson/Room 反序列化），
                // 原本跑在 viewModelScope 默认的 Main 上 ⇒ 打开统计页首帧掉帧。
                // 逐项下放到 IO（类型由表达式推断，不做元组打包）。
                withContext(Dispatchers.IO) {
                    // 首次打开时把 play_records 回填进按天统计（幂等，标记置位后零开销）
                    runCatching { prefs.backfillDailyStatsOnce() }
                        .onFailure {
                            it.rethrowIfCancellation()
                            AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it)
                        }
                }

                val month = com.nasmusic.tv.data.stats.PlayStatsRepository.currentMonth()
                val songMeta: List<Song> = withContext(Dispatchers.IO) {
                    prefs.getRecentSongObjects()
                }
                val monthlyCounts = withContext(Dispatchers.IO) {
                    prefs.playStatsRepo.getMonthCounts(month)
                }
                val allTimeCounts = withContext(Dispatchers.IO) {
                    prefs.history.playCounts.firstOrNull() ?: emptyMap()
                }
                val dailyCounts = withContext(Dispatchers.IO) {
                    prefs.playStatsRepo.getDailyCounts()
                }
                val resolutions = withContext(Dispatchers.IO) {
                    nasMusicApp.roadmapPreferences.duplicateResolutionsFlow().first()
                }
                val resolver = StatsAliasResolver(resolutions)
                val snapshot = readModel.snapshots.value
                val stableSongs = songMeta.mapNotNull { song -> sourceFor(song)?.let { song to it } }
                val idToStable = stableSongs.associate { (song, source) ->
                    song.id to runCatching { SongKeys.stableKey(song, source) }.getOrNull()
                }.filterValues { it != null }.mapValues { it.value!! }
                val metadata = songMeta.mapNotNull { song ->
                    val source = sourceFor(song)
                    val key = source?.let { runCatching { SongKeys.stableKey(song, it) }.getOrNull() } ?: song.id
                    val displayed = source?.let { readModel.applyIncludingHidden(song, it, snapshot) } ?: song
                    key to displayed.copy(id = key)
                }.toMap().let(resolver::resolveMetadata)
                val resolvedMonthly = resolver.resolveCounts(stableCounts(monthlyCounts, idToStable), metadata.keys)
                val resolvedAllTime = resolver.resolveCounts(stableCounts(allTimeCounts, idToStable), metadata.keys)

                _monthlyBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate(month, resolvedMonthly, metadata.values.toList())
                }
                _allTimeBundle.value = withContext(Dispatchers.Default) {
                    PlayStatsAggregator.aggregate("all", resolvedAllTime, metadata.values.toList())
                }
                _heatmap.value = withContext(Dispatchers.Default) {
                    PlayHeatmapBuilder.build(dailyCounts)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.e("PlayStatsViewModel", "loadStats failed", e)
            } finally {
                _loading.value = false
            }
        }
    }

    private fun stableCounts(counts: Map<String, Int>, idToStable: Map<String, String>): Map<String, Int> =
        counts.entries.fold(mutableMapOf()) { sums, (id, count) ->
            val key = idToStable[id] ?: id
            sums[key] = (sums[key] ?: 0) + count
            sums
        }

    private fun sourceFor(song: Song): SongSourceIdentity? {
        if (song.networkSource == "weather") return null
        SongKeys.bindSource(song)?.let { return it }
        if (song.sourceType != MusicSourceType.NAS || song.id.startsWith("imported_")) return null
        val config = nasMusicApp.backendRegistry.getConfig() ?: return null
        val adapter = nasMusicApp.backendRegistry.getAdapter() ?: return null
        val nativeId = when {
            song.id.startsWith("nasf_") -> song.id.removePrefix("nasf_")
            song.id.startsWith("nas_") -> song.id.removePrefix("nas_")
            else -> song.id
        }
        return SongSourceIdentity(
            kind = SongSourceKind.NAS,
            nativeId = nativeId,
            serverId = config.id,
            backendType = adapter.backendType,
        )
    }
}
