package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.ArtistSplitter
import kotlin.coroutines.cancellation.CancellationException

/**
 * 艺术家详情页的「网络源取数器」。
 *
 * 与 [NetworkArtistExpansion] 的关键区别：那个是**单艺人状态件**（`artistId` / `fetched` /
 * `more` / `loadingMore`，换词即 reset），只服务于统一搜索页；本类是**无状态函数**，
 * 每次调用只依赖入参，结果由调用方写进按名字索引的 `artistDetailSongsCache`。
 * ⛔ 不要把它们合二为一：详情页会同时缓存多个歌手，状态件做不到。
 *
 * ### 多平台语义（⛔ 别改成只走艺人 API）
 *
 * `findArtist` / `getArtistSongs` 在 `MetingApiService` 里有平台闸门，非 netease
 * （tencent/kugou/kuwo/migu）以及 baidu/jamendo 这些默认源会直接返回 null，且
 * `NetworkMusicManager` 对这两个方法**不做 fallback**。因此这里的策略是：
 *
 * 1. 先试 `findArtist(name)`（非 netease 时在闸门处零网络请求地返回 null）；
 * 2. 命中且名字归一化后一致，才 `getArtistSongs` 分页拉全量（每页上限 100）；
 * 3. **未命中 / 取数失败 / 一首都取不到 ⇒ 回退 `search(name)` 关键词搜索**。
 *
 * 这是「追加能力」而非「替代通道」：非 netease 平台的行为与修复前逐字节一致，
 * 不会因为接入艺人 API 而退化成 0 首（v2.39.26 网络艺人详情 bug 的教训）。
 *
 * 结果只保留 `networkSource == METING_SOURCE`：manager 的 search 会在默认源空时
 * fallback 到百度 / Jamendo，那些歌在各自分支已查过，收进来会跨标签重复
 * （与 `SearchAggregator` 网络分支 same口径）。
 */
class NetworkArtistDetailFetcher(
    private val findArtist: suspend (name: String) -> NetworkArtistInfo?,
    private val getArtistSongs: suspend (artistId: String, offset: Int, limit: Int) -> ArtistSongsPage?,
    private val search: suspend (keyword: String) -> List<Song>,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = MAX_PAGES
) {
    /**
     * 取某艺人的网络歌曲。恒不抛异常（取消除外）：所有远程失败都降级为关键词搜索。
     */
    suspend fun fetch(artistName: String): List<Song> {
        if (artistName.isBlank()) return emptyList()

        // 关键词兜底：只认 Meting 家族，⛔ 不做 isNetworkSong 判定（百度/Jamendo 也置该位）。
        suspend fun keywordFallback(): List<Song> =
            search(artistName).filter { it.networkSource == NetworkSongId.METING_SOURCE }

        val info = try {
            findArtist(artistName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return keywordFallback()

        // findArtist 是模糊搜索：名字必须归一化后一致才信它，否则「赵传」会被别人顶掉。
        // 用 matchKey（有界后缀归一化）：「赵传(Live)」与「赵传」视为同一人，但「周杰伦」不会。
        if (ArtistSplitter.matchKey(info.name) != ArtistSplitter.matchKey(artistName)) {
            return keywordFallback()
        }

        val all = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        var offset = 0
        var hasMore = true
        var pages = 0
        while (hasMore && pages < maxPages) {
            pages++
            val page = try {
                getArtistSongs(info.id, offset, pageSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: break
            if (page.songs.isEmpty()) break
            for (song in page.songs) {
                if (seenIds.add(song.id)) all.add(song)
            }
            // ⛔ offset 按请求步长推进，不用 page.songs.size —— 末页条数不足时接口给的
            //    offset 语义以 limit 为准，用实际条数会让最后一页之前提前错位。
            offset += pageSize
            hasMore = page.hasMore
            if (page.total > 0 && all.size >= page.total) break
        }

        // 命中艺人但一首都取不到 ⇒ 交回关键词搜索的结果，绝不比修复前更差。
        return if (all.isEmpty()) keywordFallback() else all
    }

    companion object {
        /** 与 NeteaseOpenApi.MAX_LIMIT 一致：limit>100 时 total 会抖动（§2 D）。 */
        const val DEFAULT_PAGE_SIZE = 100
        /** 5000 首硬上界；正常歌手 566 首约 6 页。 */
        const val MAX_PAGES = 50
    }
}
