package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** T8：平台映射表全覆盖 + 非法平台返回 null（方案 §4.3 / §9.7） */
class LxPlatformMapTest {

    @Test
    fun mapsAllFivePlatforms() {
        assertEquals("wy", LxPlatformMap.toLxSource("netease"))
        assertEquals("tx", LxPlatformMap.toLxSource("tencent"))
        assertEquals("kg", LxPlatformMap.toLxSource("kugou"))
        assertEquals("kw", LxPlatformMap.toLxSource("kuwo"))
        assertEquals("mg", LxPlatformMap.toLxSource("migu"))
        assertEquals(listOf("wy", "tx", "kg", "kw", "mg"), LxPlatformMap.lxSources)
    }

    @Test
    fun unknownPlatformYieldsNull() {
        assertNull(LxPlatformMap.toLxSource(null))
        assertNull(LxPlatformMap.toLxSource(""))
        assertNull(LxPlatformMap.toLxSource("spotify"))
        assertNull(LxPlatformMap.musicInfoOf(song(), "spotify"))
    }

    @Test
    fun reverseMappingCoversAllSources() {
        assertEquals("netease", LxPlatformMap.toPlatform("wy"))
        assertEquals("tencent", LxPlatformMap.toPlatform("tx"))
        assertEquals("kugou", LxPlatformMap.toPlatform("kg"))
        assertEquals("kuwo", LxPlatformMap.toPlatform("kw"))
        assertEquals("migu", LxPlatformMap.toPlatform("mg"))
        assertNull(LxPlatformMap.toPlatform("qs"))
    }

    @Test
    fun keyFieldsMatchMeasuredContract() {
        assertEquals("songmid", LxPlatformMap.keyFieldOf("wy"))
        assertEquals("songmid", LxPlatformMap.keyFieldOf("tx"))
        assertEquals("hash", LxPlatformMap.keyFieldOf("kg"))
        assertEquals("songmid", LxPlatformMap.keyFieldOf("kw"))
        assertEquals("copyrightId", LxPlatformMap.keyFieldOf("mg"))
        assertNull(LxPlatformMap.keyFieldOf("qs"))
    }

    @Test
    fun musicInfoPutsIdInTheRightField() {
        val wy = LxPlatformMap.musicInfoOf(song(networkId = "186016"), "netease")!!
        assertEquals("186016", wy.songmid)
        assertNull(wy.hash)
        assertNull(wy.copyrightId)

        val kg = LxPlatformMap.musicInfoOf(song(networkId = "b3a52a7a"), "kugou")!!
        assertEquals("b3a52a7a", kg.hash)
        assertNull(kg.songmid)

        val mg = LxPlatformMap.musicInfoOf(song(networkId = "600547"), "migu")!!
        assertEquals("600547", mg.copyrightId)
        assertNull(mg.songmid)
    }

    @Test
    fun musicInfoCarriesFullFieldSet() {
        val info = LxPlatformMap.musicInfoOf(
            song(networkId = "186016", durationMs = 269_000),
            "netease",
        )!!
        assertEquals("晴天", info.name)
        assertEquals("周杰伦", info.singer)
        assertEquals("叶惠美", info.albumName)
        assertEquals("04:29", info.interval)
        // ⚠️ 只给一个字段会在某些源上莫名失败（Flower 按平台分字段、Huibq 用 hash ?: songmid 兜底）
        assertNull(info.hash)
    }

    @Test
    fun missingNetworkIdYieldsNull() {
        assertNull(LxPlatformMap.musicInfoOf(song(networkId = null), "netease"))
        assertNull(LxPlatformMap.musicInfoOf(song(networkId = ""), "netease"))
    }

    @Test
    fun intervalFormatsAsMinutesAndSeconds() {
        assertEquals("00:05", LxPlatformMap.intervalOf(5_000))
        assertEquals("04:29", LxPlatformMap.intervalOf(269_000))
        assertEquals("61:01", LxPlatformMap.intervalOf(3_661_000))
        assertNull(LxPlatformMap.intervalOf(0))
    }

    private fun song(
        networkId: String? = "1",
        durationMs: Long = 0,
    ) = Song(
        id = "ntwk_meting_$networkId",
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = networkId,
        networkPlatform = "netease",
        durationMs = durationMs,
    )
}
