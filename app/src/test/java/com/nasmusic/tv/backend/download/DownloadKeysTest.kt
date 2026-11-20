package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.isDownloadableSong
import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载源判定 / 跨源去重键的纯逻辑测试（无 Robolectric、不打网络）。
 *
 * 回归点：
 * - 在线电台（`radio`）与天气电台（`weather`）都不可下载；
 * - `dedupeKey` 只折叠空白，不再把 "A B" 与 "AB" 判成同一首。
 */
class DownloadKeysTest {

    private fun song(
        title: String = "南方姑娘",
        artist: String = "赵雷",
        isLocal: Boolean = false,
        networkSource: String? = "meting",
        networkId: String? = "1"
    ): Song = Song(
        id = if (isLocal) "local_1" else "ntwk_${networkSource}_${networkId}_1",
        title = title,
        artist = artist,
        isLocalSong = isLocal,
        isNetworkSong = !isLocal,
        networkSource = networkSource,
        networkId = networkId
    )

    @Test
    fun `在线电台不可下载`() {
        assertFalse(isDownloadableSong(song(networkSource = "radio", networkId = "r1")))
    }

    @Test
    fun `天气电台不可下载`() {
        assertFalse(isDownloadableSong(song(networkSource = "weather", networkId = "w1")))
    }

    @Test
    fun `本地歌曲不可下载`() {
        assertFalse(isDownloadableSong(song(isLocal = true)))
    }

    @Test
    fun `网络源歌曲可下载`() {
        assertTrue(isDownloadableSong(song(networkSource = "meting")))
        assertTrue(isDownloadableSong(song(networkSource = "baidu")))
        assertTrue(isDownloadableSong(song(networkSource = "jamendo")))
    }

    @Test
    fun `dedupeKey 折叠空白但保留词边界`() {
        val normal = song(title = "A B", artist = "C D")
        val extraSpaces = song(title = "A   B", artist = " C  D ")
        assertEquals("连续空白应折叠成单空格", normal.dedupeKey, extraSpaces.dedupeKey)

        val noSpace = song(title = "AB", artist = "CD")
        assertNotEquals("删除空白会把两首不同的歌判成同一首", normal.dedupeKey, noSpace.dedupeKey)
    }
}