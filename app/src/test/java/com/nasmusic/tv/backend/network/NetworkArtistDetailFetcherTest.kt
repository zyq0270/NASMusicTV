package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺术家详情页网络取数器的纯 JVM 单测（无 Android 依赖，不加 Robolectric）。
 *
 * 钉死 v2.39.26「赵传卡片 46 专辑/100 首但详情 0 首」的核心契约：
 * netease 走艺人全量分页；非 netease / 未命中 / 取数失败 → 关键词搜索兜底，绝不退化成 0。
 */
class NetworkArtistDetailFetcherTest {

    private fun song(id: String, artist: String = "赵传", source: String = "meting") = Song(
        id = if (source == "meting") "ntwk_meting_$id" else "ntwk_${source}_$id",
        title = "t$id",
        artist = artist,
        isNetworkSong = true,
        networkSource = source,
        networkId = id
    )

    private fun page(ids: List<String>, more: Boolean, total: Int, offset: Int) = ArtistSongsPage(
        songs = ids.map { song("${offset + it.toInt()}") },
        total = total,
        hasMore = more,
        unplayableIds = emptySet()
    )

    @Test
    fun `T1 netease 命中艺人 分页拉全量并按 offset 推进`() = runBlocking {
        val fetches = mutableListOf<Int>()
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传", 150) },
            getArtistSongs = { _, offset, limit ->
                fetches += offset
                when (offset) {
                    0 -> page((1..100).map { "$it" }, more = true, total = 150, offset = 0)
                    100 -> page((1..50).map { "$it" }, more = false, total = 150, offset = 100)
                    else -> null
                }
            },
            search = { error("命中艺人不应再走关键词搜索") },
            pageSize = 100
        )
        val out = f.fetch("赵传")
        assertEquals(150, out.size)
        assertEquals(listOf(0, 100), fetches)
    }

    @Test
    fun `T2 findArtist 返回 null 非 netease 回退关键词搜索且只留 meting`() = runBlocking {
        val f = NetworkArtistDetailFetcher(
            findArtist = { null },
            getArtistSongs = { _, _, _ -> error("非 netease 不应取作品") },
            search = { listOf(song("1"), song("2", source = "baidu")) }
        )
        val out = f.fetch("赵传")
        assertEquals(1, out.size)
        assertEquals("ntwk_meting_1", out.first().id)
    }

    @Test
    fun `T3 名字不一致不信任 findArtist 回退搜索`() = runBlocking {
        var searched = 0
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("1", "赵薇", 100) },
            getArtistSongs = { _, _, _ -> error("名字不一致不应取作品") },
            search = { searched++; listOf(song("9")) }
        )
        val out = f.fetch("赵传")
        assertEquals(1, searched)
        assertEquals(1, out.size)
    }

    @Test
    fun `T3b 有界后缀视为同名 仍走艺人全量`() = runBlocking {
        var searched = 0
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传(Live)", 1) },
            getArtistSongs = { _, offset, _ ->
                if (offset == 0) page(listOf("1"), more = false, total = 1, offset = 0)
                else null
            },
            search = { searched++; emptyList() }
        )
        assertEquals(1, f.fetch("赵传").size)
        assertEquals(0, searched)
    }

    @Test
    fun `T4 取作品返回 null 回退搜索 不返回空列表`() = runBlocking {
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传", 100) },
            getArtistSongs = { _, _, _ -> null },
            search = { listOf(song("7")) }
        )
        assertEquals(1, f.fetch("赵传").size)
    }

    @Test
    fun `T5 命中艺人但首页零行 回退搜索`() = runBlocking {
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传", 0) },
            getArtistSongs = { _, _, _ -> ArtistSongsPage(emptyList(), 0, false, emptySet()) },
            search = { listOf(song("8")) }
        )
        assertEquals(1, f.fetch("赵传").size)
    }

    @Test
    fun `T6 分页内重复 id 只保留一首`() = runBlocking {
        // ⛔ 这里不能用 page() 辅助：它会按 offset 重算 netId（第二页会变成 101），
        //    造不出「跨页同 id」的场景。直接构造两页都含 ntwk_meting_2 的数据。
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传", 3) },
            getArtistSongs = { _, offset, _ ->
                if (offset == 0) {
                    ArtistSongsPage(listOf(song("1"), song("1"), song("2")), 3, true, emptySet())
                } else {
                    ArtistSongsPage(listOf(song("2")), 3, false, emptySet())
                }
            },
            search = { error("不应回退") }
        )
        val out = f.fetch("赵传")
        assertEquals(2, out.size)
        assertEquals(2, out.map { it.id }.toSet().size)
    }

    @Test
    fun `T7 total 达标提前停止 不发多余页`() = runBlocking {
        var calls = 0
        val f = NetworkArtistDetailFetcher(
            findArtist = { NetworkArtistInfo("6452", "赵传", 1) },
            getArtistSongs = { _, offset, _ ->
                calls++
                page(listOf("1"), more = true, total = 1, offset = offset)
            },
            search = { error("不应回退") }
        )
        assertEquals(1, f.fetch("赵传").size)
        assertEquals(1, calls)
    }

    @Test
    fun `T8 空歌手名直接返回空 不发任何请求`() = runBlocking {
        var called = 0
        val f = NetworkArtistDetailFetcher(
            findArtist = { called++; null },
            getArtistSongs = { _, _, _ -> called++; null },
            search = { called++; emptyList() }
        )
        assertTrue(f.fetch("  ").isEmpty())
        assertEquals(0, called)
    }
}
