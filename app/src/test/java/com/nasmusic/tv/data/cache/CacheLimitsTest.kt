package com.nasmusic.tv.data.cache

import com.nasmusic.tv.backend.local.CoverUrlPersistentCache
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.NetworkMusicService
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.LyricsCacheEntry
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.OfflinePolicy
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import com.nasmusic.tv.lyrics.LyricsPersistentCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheLimitsTest {
    private lateinit var prefs: RoadmapPreferences
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = runBlocking {
        prefs = RoadmapPreferences.getInstance(context)
        CacheLimitsSnapshot.RANGES.forEach { (kind, range) ->
            CacheLimits.set(prefs, kind, range.first).getOrThrow()
        }
    }

    @After
    fun tearDown() = runBlocking {
        CacheLimitsSnapshot.RANGES.forEach { (kind, _) ->
            CacheLimits.set(prefs, kind, CacheLimitsSnapshot.defaultValue(kind)).getOrThrow()
        }
        LyricsPersistentCache.get(context).clear()
    }

    @Test
    fun `默认值 范围与非法输入不落盘`() = runBlocking {
        val defaults = CacheLimitsSnapshot()
        CacheLimitsSnapshot.RANGES.forEach { (kind, _) ->
            CacheLimits.set(prefs, kind, CacheLimitsSnapshot.defaultValue(kind)).getOrThrow()
        }
        CacheLimits.loadFrom(prefs)
        assertEquals(defaults, CacheLimits.current())
        assertTrue(CacheLimits.set(prefs, CacheLimitKind.LYRICS_ENTRIES, 99).isFailure)
        assertEquals(defaults.lyricsMaxEntries, prefs.cacheLimitsFlow().first().lyricsMaxEntries)
    }

    @Test
    fun `保存后快照更新且重新加载保留`() = runBlocking {
        assertTrue(CacheLimits.set(prefs, CacheLimitKind.LYRICS_ENTRIES, 321).isSuccess)
        assertEquals(321, CacheLimits.current().lyricsMaxEntries)
        CacheLimits.loadFrom(RoadmapPreferences(context))
        assertEquals(321, CacheLimits.current().lyricsMaxEntries)
        // 播放链接下限是 50（路线图 §7.4：50–5000），不是歌词的 100。
        assertEquals(50, CacheLimits.clamp(CacheLimitKind.PLAY_URL_ENTRIES, 1))
    }

    @Test
    fun `歌词按 LRU 淘汰`() = runBlocking {
        CacheLimits.set(prefs, CacheLimitKind.LYRICS_ENTRIES, 100).getOrThrow()
        val cache = LyricsPersistentCache.get(context)
        cache.clear()
        repeat(100) { i ->
            Thread.sleep(1)
            cache.put(LyricsCacheEntry("s$i", "S$i", "A", "[00:00]x", i.toLong()))
        }
        assertNotNull(cache.get("s0")) // 命中刷新 LRU 时间
        Thread.sleep(1)
        cache.put(LyricsCacheEntry("s100", "S100", "A", "[00:00]x", 1000L))
        assertEquals(100, cache.size())
        assertNotNull(cache.get("s0"))
        assertNull(cache.get("s1"))
    }

    @Test
    fun `封面按 FIFO by write 淘汰读取不刷新`() = runBlocking {
        CacheLimits.set(prefs, CacheLimitKind.COVER_URL_ENTRIES, 100).getOrThrow()
        var now = 1_000L
        val cache = CoverUrlPersistentCache(context) { now++ }
        repeat(100) { cache.putAlbumCover("a$it", "https://cdn/$it.jpg") }
        assertNotNull(cache.getAlbumCover("a0"))
        cache.putAlbumCover("a100", "https://cdn/100.jpg")
        assertEquals(100, cache.exportAll().size)
        assertNull(cache.getAlbumCover("a0"))
        assertNotNull(cache.getAlbumCover("a1"))
        cache.close()
    }

    @Test
    fun `播放 URL 与 dead probe 共享时间顺序上限`() = runBlocking {
        CacheLimits.set(prefs, CacheLimitKind.PLAY_URL_ENTRIES, 50).getOrThrow()
        var now = 1_000L
        var calls = 0
        val service = object : NetworkMusicService {
            override val sourceId = "fake"
            override suspend fun search(keyword: String, limit: Int) = emptyList<Song>()
            override suspend fun resolvePlayUrl(song: Song): String? = "https://cdn/${song.networkId}.mp3"
            override suspend fun resolveLyrics(song: Song): String? = null
            override suspend fun getPlaylist(playlistId: String) = emptyList<Song>()
            override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
                calls++
                return if (song.networkId == "dead") ResolveResult.failure(quality, probedDead = true)
                else ResolveResult("https://cdn/${song.networkId}.mp3", quality)
            }
        }
        val manager = NetworkMusicManager(mapOf("fake" to service), { "fake" }, nowMs = { now })
        repeat(50) { i ->
            now++
            manager.resolvePlayUrl(song("fake", "$i"))
        }
        now++
        manager.resolvePlayUrl(song("fake", "dead"))
        // dead 请求会把一个最旧 URL 挤掉；再次读取该 URL 必须真实重解析。
        assertNotNull(manager.resolvePlayUrl(song("fake", "0")))
        assertEquals(52, calls)
        assertTrue(manager.isProbedDead(song("fake", "dead")))
    }

    @Test
    fun `搜索离线真值表`() {
        val all = setOf(MusicSourceType.NAS, MusicSourceType.NETWORK_MUSIC, MusicSourceType.LOCAL, MusicSourceType.DOWNLOAD)
        assertEquals(all, effectiveSources(OfflinePolicy.OFF, true, all))
        assertEquals(setOf(MusicSourceType.LOCAL, MusicSourceType.DOWNLOAD), effectiveSources(OfflinePolicy.LOCAL_ONLY, true, all))
        assertTrue(effectiveSources(OfflinePolicy.LOCAL_ONLY, false, all).isEmpty())
        assertFalse(effectiveSources(OfflinePolicy.LOCAL_ONLY, true, all).contains(MusicSourceType.NAS))
    }

    private fun song(source: String, id: String) = Song(
        id = "ntwk_${source}_$id", title = id, isNetworkSong = true,
        networkSource = source, networkId = id,
    )
}
