package com.nasmusic.tv.backend.search

import com.nasmusic.tv.backend.FilterMode
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.SearchType
import com.nasmusic.tv.backend.SourceVisibility
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.SearchAggregatorResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.SearchQuery
import com.nasmusic.tv.util.VoiceQueryNormalizer
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** TV / 手机遥控 / 车机共用的搜索结果，保留聚合器的来源与排序。 */
data class SharedSearchResult(
    val normalizedQuery: String,
    val results: List<RankedSong>,
    val sourceBreakdown: Map<MusicSourceType, Int>,
) {
    val songs: List<Song>
        get() = results.map { it.song }
}

/**
 * 应用级共享搜索入口。
 *
 * 统一职责：语音命令规范化、来源可见性、PRECISE、拼音缓存、聚合去重与历史记录。
 * 具体跨源网络搜索仍由 [SearchAggregator] 完成；本类只负责把各入口的语义收敛成同一份请求。
 */
class SharedSearchService internal constructor(
    private val engine: SharedSearchEngine,
    private val sourceVisibility: SourceVisibility?,
    private val nasLocalSongsProvider: suspend () -> List<Song>,
    private val localDeviceSongsProvider: suspend () -> List<Song>,
    private val baiduLocalSongsProvider: suspend (String) -> List<Song>,
    private val recordSearchHistory: suspend (String) -> Unit,
) {

    constructor(
        searchAggregator: SearchAggregator,
        sourceVisibility: SourceVisibility? = null,
        nasLocalSongsProvider: suspend () -> List<Song> = { emptyList() },
        localDeviceSongsProvider: suspend () -> List<Song> = { emptyList() },
        baiduLocalSongsProvider: suspend (String) -> List<Song> = { emptyList() },
        recordSearchHistory: suspend (String) -> Unit = {},
    ) : this(
        engine = SharedSearchEngine { request ->
            searchAggregator.search(
                keyword = request.query,
                sources = request.sources,
                filterMode = request.filterMode,
                searchType = request.searchType,
                nasLocalSongs = request.nasLocalSongs,
                localDeviceSongs = request.localDeviceSongs,
                baiduLocalSongs = request.baiduLocalSongs,
                enablePinyin = true,
            )
        },
        sourceVisibility = sourceVisibility,
        nasLocalSongsProvider = nasLocalSongsProvider,
        localDeviceSongsProvider = localDeviceSongsProvider,
        baiduLocalSongsProvider = baiduLocalSongsProvider,
        recordSearchHistory = recordSearchHistory,
    )

    /** 共享规范化：命令语法（“播放…”“我想听…”）在所有入口都只处理一次。 */
    fun normalize(rawQuery: String): String = VoiceQueryNormalizer.normalizeDetailed(rawQuery).query

    fun normalizeVoice(rawQuery: String): String = normalize(rawQuery)

    suspend fun search(
        rawQuery: String,
        mode: FilterMode = FilterMode.PRECISE,
        recordHistory: Boolean = true,
        availableSources: Set<MusicSourceType> = MusicSourceType.DEFAULT_SEARCH_SOURCES,
        searchType: SearchType = SearchType.SONG_NAME_OR_ARTIST,
    ): SharedSearchResult = searchNormalized(
        // 所有入口共用同一套命令语法规范化：遥控 / 车机 / TV 语音都不再做第二次处理，
        // 纯命令（如“播放”）规范化后为空，不会触发引擎与历史记录。
        query = normalizeVoice(rawQuery),
        mode = mode,
        recordHistory = recordHistory,
        availableSources = availableSources,
        searchType = searchType,
    )

    /** 已由调用方规范化的入口；用于需要以规范化词作为缓存键的车机搜索。 */
    suspend fun searchNormalized(
        query: String,
        mode: FilterMode = FilterMode.PRECISE,
        recordHistory: Boolean = true,
        availableSources: Set<MusicSourceType> = MusicSourceType.DEFAULT_SEARCH_SOURCES,
        searchType: SearchType = SearchType.SONG_NAME_OR_ARTIST,
    ): SharedSearchResult {
        if (query.isBlank()) {
            return SharedSearchResult(query, emptyList(), emptyMap())
        }

        val sources = availableSources.filterTo(linkedSetOf()) { source ->
            sourceVisibility?.isVisible(source) ?: true
        }
        if (sources.isEmpty()) {
            return SharedSearchResult(query, emptyList(), emptyMap())
        }

        val caches = if (SearchQuery.isPinyinLike(query)) {
            // 百度索引可能很大，拼音筛选必须离开调用方线程（与 SearchViewModel 同口径）。
            withContext(Dispatchers.Default) {
                SearchCaches(
                    nas = nasLocalSongsProvider(),
                    local = localDeviceSongsProvider(),
                    baidu = baiduLocalSongsProvider(query),
                )
            }
        } else {
            SearchCaches.EMPTY
        }

        val aggregated = engine.search(
            SharedSearchRequest(
                query = query,
                sources = sources,
                filterMode = mode,
                searchType = searchType,
                nasLocalSongs = caches.nas,
                localDeviceSongs = caches.local,
                baiduLocalSongs = caches.baidu,
            )
        )

        // 聚合器内部已按可见性过滤；这里再兜一次，保证注入不同实现时语义仍一致。
        val visible = aggregated.allResults.filter { ranked ->
            ranked.source in sources &&
                (sourceVisibility?.isVisible(ranked.source) ?: true) &&
                (sourceVisibility?.isVisible(ranked.song) ?: true)
        }
        val deduped = visible.distinctBy { ranked ->
            "${ranked.source.name}|${normalizeKey(ranked.song.title)}|${normalizeKey(ranked.song.artist)}"
        }
        val sorted = RankedSong.sortByPriority(deduped)
        val result = SharedSearchResult(
            normalizedQuery = query,
            results = sorted,
            sourceBreakdown = sorted.groupBy { it.source }.mapValues { it.value.size },
        )

        if (recordHistory) {
            try {
                recordSearchHistory(query)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w("SharedSearchService", "Failed to record search history", e)
            }
        }
        return result
    }

    private fun normalizeKey(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private data class SearchCaches(
        val nas: List<Song>,
        val local: List<Song>,
        val baidu: List<Song>,
    ) {
        companion object {
            val EMPTY = SearchCaches(emptyList(), emptyList(), emptyList())
        }
    }

    internal data class SharedSearchRequest(
        val query: String,
        val sources: Set<MusicSourceType>,
        val filterMode: FilterMode,
        val searchType: SearchType,
        val nasLocalSongs: List<Song>,
        val localDeviceSongs: List<Song>,
        val baiduLocalSongs: List<Song>,
    )

    internal fun interface SharedSearchEngine {
        suspend fun search(request: SharedSearchRequest): SearchAggregatorResult
    }
}
