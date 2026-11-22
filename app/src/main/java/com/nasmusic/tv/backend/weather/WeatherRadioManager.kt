package com.nasmusic.tv.backend.weather

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.rethrowIfCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 天气电台管理器
 *
 * 根据 WeatherData 计算 mood，然后构建歌曲队列。
 * 队列来源：
 * 1. NAS 后端本地歌曲（按 mood 关键词模糊搜索曲库）
 * 2. 网络歌曲（通过 NetworkMusicManager 搜索 mood 关键词）
 */
class WeatherRadioManager(
    /** NAS 后端适配器（可能为 null — 未连接时从网络端匹配歌曲） */
    @Volatile private var backendAdapter: BackendAdapter?,
    private val networkMusicManager: NetworkMusicManager
) {
    /**
     * P2 修复（2026-11-22）：适配器快照固化。
     *
     * Manager 采用"可空延迟创建"——若首次进首页时还没连 NAS，构造时拿到 null，
     * 之后连上 NAS 也不会更新 ⇒ 天气电台永远缺 NAS 段。改为可变字段 + 每次构建前
     * 由 VM 用实时 registry 刷新（不违反 W0 的"延后创建、允许无后端工作"契约）。
     */
    fun updateAdapter(adapter: BackendAdapter?) {
        backendAdapter = adapter
    }

    companion object {
        private const val TAG = "WeatherRadioManager"
        /** 天气电台每次构建的歌曲总数 */
        private const val TARGET_SONG_COUNT = 20
        /** NAS 来源比例上限 */
        private const val NAS_MAX_RATIO = 0.5f
        /** NAS 心情命中歌曲的 id 前缀（用于区分来源） */
        private const val NAS_PREFIX = "nas_"
        /** NAS 随机补齐歌曲的 id 前缀（与心情命中区分，避免被误判为命中） */
        private const val NAS_FILLER_PREFIX = "nasf_"
        /** 命中不足时的补齐池大小：只拉第一页，不再为补齐分页拉满 5000 首 */
        private const val NAS_FILLER_POOL_SIZE = 200
    }

    /** NAS 搜索结果：命中心情关键词的歌曲 + 随机补齐歌曲（分别标记，避免混淆） */
    private data class NasSearchResult(
        val matched: List<Song> = emptyList(),
        val filler: List<Song> = emptyList()
    )

    /**
     * 合并 NAS / 网络结果并计算计数。
     *
     * 修复：随机补齐的 NAS 歌曲不再被当作「心情命中」计入 nasCount。
     * - nasCount：队列中所有 NAS 来源歌曲（命中 + 补齐）
     * - networkCount：队列中网络来源歌曲
     * - moodMatchedCount：真正由心情关键词命中的歌曲数（NAS 命中 + 网络结果）
     */
    private fun mergeAndPack(
        nasResult: NasSearchResult,
        networkSongs: List<Song>,
        limit: Int,
        mood: WeatherMood,
        queries: List<String>
    ): WeatherRadioQueue {
        val seen = mutableSetOf<String>()
        val matched = mutableListOf<Song>()
        val filler = mutableListOf<Song>()
        // 优先：真正命中的（NAS 命中 + 网络结果）；网络结果本身即由心情关键词搜出
        for (s in nasResult.matched) if (seen.add(s.id)) matched.add(s)
        for (s in networkSongs) if (seen.add(s.id)) matched.add(s)
        // 其次：NAS 随机补齐
        for (s in nasResult.filler) if (seen.add(s.id)) filler.add(s)

        val finalSongs = (matched + filler).take(limit)
        val matchedIds = matched.mapTo(mutableSetOf()) { it.id }
        return WeatherRadioQueue(
            songs = finalSongs,
            mood = mood,
            queries = queries,
            nasCount = finalSongs.count {
                it.id.startsWith(NAS_PREFIX) || it.id.startsWith(NAS_FILLER_PREFIX)
            },
            networkCount = finalSongs.count {
                !it.id.startsWith(NAS_PREFIX) && !it.id.startsWith(NAS_FILLER_PREFIX)
            },
            moodMatchedCount = finalSongs.count { it.id in matchedIds }
        )
    }

    /**
     * 根据当前天气构建电台队列
     *
     * @param weather 当前天气数据
     * @param limit 期望的歌曲总数（默认 20）
     */
    suspend fun buildRadio(weather: WeatherData, limit: Int = TARGET_SONG_COUNT): WeatherRadioQueue {
        val mood = WeatherMood.fromWeather(weather)
        val queries = mood.searchQueries

        val nasLimit = (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5)

        return coroutineScope {
            val nasDeferred = async {
                try {
                    searchNasSongs(queries, nasLimit)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.w(TAG, "NAS search failed: ${e.message}")
                    NasSearchResult()
                }
            }
            val networkDeferred = async {
                try {
                    searchNetworkSongs(queries, limit - nasLimit)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    AppLog.w(TAG, "Network search failed: ${e.message}")
                    emptyList()
                }
            }

            val nasResult = nasDeferred.await()
            val networkSongs = networkDeferred.await()
            mergeAndPack(nasResult, networkSongs, limit, mood, queries)
        }
    }

    /**
     * 切换 mood 并重新构建电台
     */
    suspend fun buildRadioWithMood(
        targetMood: WeatherMood,
        weather: WeatherData?,
        limit: Int = TARGET_SONG_COUNT
    ): WeatherRadioQueue {
        val queries = targetMood.searchQueries

        return coroutineScope {
            val nasDeferred = async {
                try {
                    searchNasSongs(queries, (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5))
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    NasSearchResult()
                }
            }
            val networkDeferred = async {
                try {
                    searchNetworkSongs(queries, limit - (limit * NAS_MAX_RATIO).toInt().coerceAtLeast(5))
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    emptyList()
                }
            }

            val nasResult = nasDeferred.await()
            val networkSongs = networkDeferred.await()
            mergeAndPack(nasResult, networkSongs, limit, targetMood, queries)
        }
    }

    /**
     * 从 NAS 后端搜索匹配 mood 的歌曲。
     *
     * §10.410（2026-11-17）：改用 [BackendAdapter.searchSongs] 服务端搜索。
     * 原实现分页拉满 5000 首再在内存里 `contains` 关键词，有两个硬伤：
     * ① 曲库 > 5000 首时后面的歌永远命中不了；② 每次构建白拉 10 页全量。
     * 五个适配器（Jellyfin / Navidrome / Subsonic / 飞牛 / 道理鱼）都已实现 searchSongs。
     *
     * 保留的客户端语义：关键词去重、随机补齐、结果打乱、`nas_` / `nasf_` 前缀分类。
     * ⚠️ 遗留 P3：`copy(id = "nas_"+id)` 仍会让收藏/播放历史按合成 id 记账，
     * 摘前缀要连带改 [mergeAndPack] 的命中/补齐分类，属独立专项。
     */
    private suspend fun searchNasSongs(queries: List<String>, maxCount: Int): NasSearchResult {
        // 无后端连接时跳过 NAS 搜索
        val adapter = backendAdapter ?: return NasSearchResult()
        if (maxCount <= 0) return NasSearchResult()

        val matched = mutableSetOf<Song>()
        for (query in queries) {
            if (matched.size >= maxCount) break
            val q = query.trim()
            if (q.isEmpty()) continue
            try {
                // 服务端已按自己的规则命中（标题/歌手/专辑），客户端只去重 + 限流，不再二次 contains
                val results = adapter.searchSongs(q)
                matched.addAll(results.take(maxCount - matched.size))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w(TAG, "NAS search for '$q' failed: ${e.message}")
            }
        }

        // 命中不足 → 随机补齐。补齐池只取第一页（与旧实现的 10 页全量拉取相比是净减少）。
        val filler = if (matched.size < maxCount) {
            try {
                adapter.getSongs(NAS_FILLER_POOL_SIZE, 0).orEmpty()
                    .shuffled()
                    .filterNot { it in matched }
                    .take(maxCount - matched.size)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                AppLog.w(TAG, "NAS filler fetch failed: ${e.message}")
                emptyList()
            }
        } else emptyList()

        // 打乱命中结果，让每次构建的电台基础集合不同（支持「换一批」跨构建去重）
        val matchedTagged = matched.toList().shuffled().take(maxCount).map { it.copy(id = "${NAS_PREFIX}${it.id}") }
        val fillerTagged = filler.shuffled().map { it.copy(id = "${NAS_FILLER_PREFIX}${it.id}") }
        return NasSearchResult(matched = matchedTagged, filler = fillerTagged)
    }

    /**
     * 通过网络音乐管理器搜索匹配 mood 的歌曲
     */
    private suspend fun searchNetworkSongs(queries: List<String>, maxCount: Int): List<Song> {
        if (maxCount <= 0) return emptyList()

        val results = mutableSetOf<Song>()
        for (query in queries) {
            if (results.size >= maxCount) break
            if (query.isBlank()) continue
            try {
                val songs = networkMusicManager.search(query)
                results.addAll(songs.take(maxCount - results.size))
            } catch (e: Exception) {
                AppLog.w(TAG, "Network search for '$query' failed: ${e.message}")
            }
        }
        // 打乱结果，让每次构建的电台基础集合不同（支持「换一批」跨构建去重）
        return results.shuffled().take(maxCount).toList()
    }
}
