package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺术家同名合并 / 合唱拆分的回归测试
 *
 * 覆盖场景：
 * 1. 合唱名 "古天乐/萱萱" 必须拆成两个艺术家，且合唱歌同时计入两人
 * 2. 同名不同写法（首尾空白 / 全角 / 大小写）必须合并为一块
 */
class MusicMergerTest {

    private fun song(id: String, artist: String, album: String = "") =
        Song(id = id, title = "t_$id", artist = artist, album = album)

    @Test
    fun `buildArtistsFromSongs splits collaboration names`() {
        val songs = listOf(
            song("1", "古天乐/萱萱"),
            song("2", "古天乐")
        )
        val artists = MusicMerger.buildArtistsFromSongs(songs, "search_artist_")

        val names = artists.map { it.name }.toSet()
        assertEquals(setOf("古天乐", "萱萱"), names)
        // 合唱歌同时计入两位艺术家
        assertEquals(2, artists.first { it.name == "古天乐" }.songCount)
        assertEquals(1, artists.first { it.name == "萱萱" }.songCount)
        assertTrue(artists.none { it.name.contains("/") })
    }

    @Test
    fun `buildArtistsFromSongs merges same name with different spelling`() {
        val songs = listOf(
            song("1", "古天乐"),
            song("2", "古天乐 "),        // 尾部空白
            song("3", "　古天乐"),        // 全角前导空格
            song("4", "ＡＢＣ"),          // 全角字母
            song("5", "abc")             // 半角字母
        )
        val artists = MusicMerger.buildArtistsFromSongs(songs, "search_artist_")

        assertEquals(2, artists.size)
        assertEquals(3, artists.first { it.name == "古天乐" }.songCount)
        assertTrue(artists.any { it.name == "ＡＢＣ" || it.name == "abc" })
    }

    @Test
    fun `mergeArtists merges nas and local entries by normalized name`() {
        val nas = listOf(
            Artist(id = "nas-1", name = "古天乐", songCount = 5, albumCount = 1),
            Artist(id = "nas-2", name = "古天乐/萱萱", songCount = 2, albumCount = 1)
        )
        val local = listOf(
            Artist(id = "local_artist_古天乐", name = "古天乐 ", songCount = 3, albumCount = 0)
        )

        val merged = MusicMerger.mergeArtists(nasArtists = nas, localArtists = local)

        // "NAS 古天乐" 与 "本地 古天乐 " 合并为一块，合唱条目保持独立（拆分在 loadArtists 阶段完成）
        assertEquals(2, merged.size)
        val gutianle = merged.first { it.name.trim() == "古天乐" }
        assertEquals(8, gutianle.songCount)
    }

    @Test
    fun `buildLocalArtists delegates to split based aggregation`() {
        val songs = listOf(song("1", "周杰伦 feat. 杨瑞代"))
        val artists = MusicMerger.buildLocalArtists(songs)
        assertEquals(setOf("周杰伦", "杨瑞代"), artists.map { it.name }.toSet())
        assertTrue(artists.all { it.id.startsWith("local_artist_") })
    }

    @Test
    fun `mergeLibrarySongs keeps nas order then local then baidu and dedupes by id`() {
        val nas = listOf(song("1", "a"), song("2", "b"))
        val local = listOf(song("local_3", "c"), song("2", "dup"))
        val baidu = listOf(song("ntwk_baidu_4", "d"), song("local_3", "dup"))

        val merged = MusicMerger.mergeLibrarySongs(nas, local, baidu)

        assertEquals(listOf("1", "2", "local_3", "ntwk_baidu_4"), merged.map { it.id })
    }

    @Test
    fun `mergeLibrarySongs returns nas list as-is when other sources are empty`() {
        val nas = listOf(song("1", "a"))

        val merged = MusicMerger.mergeLibrarySongs(nas, emptyList(), emptyList())

        assertSame(nas, merged)
    }

    @Test
    fun `mergeAlbums keeps same-name albums from different artists separate`() {
        val merged = MusicMerger.mergeAlbums(
            nasAlbums = listOf(
                Album(id = "nas_a", name = "爱情", artist = "甲", songCount = 3),
                Album(id = "nas_b", name = "爱情", artist = "乙", songCount = 5)
            ),
            localAlbums = emptyList()
        )

        assertEquals(2, merged.size)
        val byArtist = merged.associateBy { it.artist }
        assertEquals(3, byArtist["甲"]!!.songCount)
        assertEquals(5, byArtist["乙"]!!.songCount)
        assertEquals(setOf("nas_a"), byArtist["甲"]!!.sourceIds.toSet())
        assertEquals(setOf("nas_b"), byArtist["乙"]!!.sourceIds.toSet())
    }

    @Test
    fun `mergeAlbums merges blank-artist source into named group and keeps all source ids`() {
        val merged = MusicMerger.mergeAlbums(
            nasAlbums = listOf(Album(id = "nas_love", name = "爱情", artist = "甲", songCount = 4)),
            localAlbums = emptyList(),
            baiduAlbums = listOf(Album(id = "baidu_love", name = "爱情", artist = "", songCount = 6))
        )

        assertEquals(1, merged.size)
        assertEquals("甲", merged.first().artist)
        assertEquals(10, merged.first().songCount)
        assertEquals(setOf("nas_love", "baidu_love"), merged.first().sourceIds.toSet())
    }

    @Test
    fun `buildLocalAlbums generates distinct ids for same-name different-artist albums`() {
        val albums = MusicMerger.buildLocalAlbums(
            listOf(song("l1", "甲", "爱情"), song("l2", "乙", "爱情"))
        )

        assertEquals(2, albums.size)
        assertEquals(2, albums.map { it.id }.distinct().size)
        assertTrue(albums.all { it.id.startsWith("local_album_") })
    }

    @Test
    fun `buildBaiduAlbums generates distinct ids for same-name different-artist albums`() {
        val albums = MusicMerger.buildBaiduAlbums(
            listOf(
                Song(id = "b1", title = "t1", artist = "甲", path = "/音乐/甲/爱情/01.mp3"),
                Song(id = "b2", title = "t2", artist = "乙", path = "/音乐/乙/爱情/01.mp3")
            )
        )

        assertEquals(2, albums.size)
        assertEquals(2, albums.map { it.id }.distinct().size)
        assertTrue(albums.all { it.id.startsWith("baidu_album_") })
    }
}
