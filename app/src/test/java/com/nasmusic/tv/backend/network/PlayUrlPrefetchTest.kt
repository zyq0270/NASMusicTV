package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.cache.CacheLimitKind
import com.nasmusic.tv.data.cache.CacheLimits
import com.nasmusic.tv.data.cache.CacheLimitsSnapshot
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.RoadmapPreferences
import com.nasmusic.tv.player.PlayUrlPrefetchScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayUrlPrefetchTest {
    private class FakeService : NetworkMusicService {
        override val sourceId = "meting"
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        var handler: suspend (Song) -> ResolveResult = { ResolveResult("https://cdn/new.mp3", 320) }

        override suspend fun search(keyword: String, limit: Int): List<Song> = emptyList()
        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()
        override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
            calls.incrementAndGet()
            val running = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, running) }
            return try { handler(song) } finally { active.decrementAndGet() }
        }
    }

    private lateinit var prefs: RoadmapPreferences
    private lateinit var service: FakeService
    private lateinit var manager: NetworkMusicManager
    private var now = 10_000_000L

    private fun song(id: String = "1") = Song(
        id = "ntwk_meting_$id", title = "Song $id", artist = "Artist",
        isNetworkSong = true, networkSource = "meting", networkId = id,
    )

    @Before
    fun setUp() = runBlocking {
        prefs = RoadmapPreferences.getInstance(RuntimeEnvironment.getApplication())
        CacheLimits.set(prefs, CacheLimitKind.PLAY_URL_ENTRIES, CacheLimitsSnapshot().playUrlMaxEntries).getOrThrow()
        service = FakeService()
        manager = NetworkMusicManager(
            services = mapOf("meting" to service),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 },
            nowMs = { now },
        )
    }

    @After
    fun tearDown() = runBlocking {
        CacheLimits.set(prefs, CacheLimitKind.PLAY_URL_ENTRIES, CacheLimitsSnapshot().playUrlMaxEntries).getOrThrow()
    }

    @Test
    fun `窗口前不请求 窗口内刷新且无播放副作用`() = runBlocking {
        val original = song()
        service.handler = { ResolveResult("https://cdn/old.mp3", 320) }
        manager.resolvePlayUrl(original)
        val snapshot = original.copy()

        now += PlayUrlPrefetchScheduler.TTL_MS - PlayUrlPrefetchScheduler.REFRESH_WINDOW_MS - 1
        assertEquals(PrefetchStatus.ALREADY_FRESH, manager.prefetchPlayUrl(original).status)
        assertEquals(1, service.calls.get())

        now += 1
        service.handler = { ResolveResult("https://cdn/new.mp3", 320) }
        assertEquals(PrefetchStatus.UPDATED, manager.prefetchPlayUrl(original).status)
        assertEquals(2, service.calls.get())
        assertEquals("https://cdn/new.mp3", manager.resolvePlayUrl(original))
        assertEquals(snapshot, original)
    }

    @Test
    fun `刷新失败保留未过期旧 URL 过期后明确失败`() = runBlocking {
        val original = song()
        service.handler = { ResolveResult("https://cdn/old.mp3", 320) }
        manager.resolvePlayUrl(original)
        service.handler = { ResolveResult.failure(320) }

        now += PlayUrlPrefetchScheduler.TTL_MS - PlayUrlPrefetchScheduler.REFRESH_WINDOW_MS
        val retained = manager.prefetchPlayUrl(original)
        assertEquals(PrefetchStatus.FAILED_RETRYABLE_OFFLINE, retained.status)
        assertEquals("https://cdn/old.mp3", retained.url)
        assertEquals("https://cdn/old.mp3", manager.resolvePlayUrl(original))

        now += PlayUrlPrefetchScheduler.TTL_MS
        assertEquals(PrefetchStatus.EXPIRED_URL, manager.prefetchPlayUrl(original).status)
        assertNull(manager.resolvePlayUrl(original))
    }

    @Test
    fun `同 key 并发预刷 single flight`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        service.handler = {
            entered.complete(Unit)
            release.await()
            ResolveResult("https://cdn/new.mp3", 320)
        }
        val first = async { manager.prefetchPlayUrl(song()) }
        entered.await()
        val second = async { manager.prefetchPlayUrl(song()) }
        repeat(10) { yield() }
        delay(20)
        release.complete(Unit)
        val results = awaitAll(first, second)
        assertTrue(results.all { it.status == PrefetchStatus.UPDATED })
        assertEquals(1, service.calls.get())
        assertEquals(1, service.maxActive.get())
    }

    @Test
    fun `非网络歌曲与未注册源不发请求`() = runBlocking {
        assertEquals(PrefetchStatus.NOT_NETWORK_SONG, manager.prefetchPlayUrl(Song("local", "L")).status)
        val unknown = song().copy(networkSource = "unknown")
        assertEquals(PrefetchStatus.SERVICE_UNAVAILABLE, manager.prefetchPlayUrl(unknown).status)
        assertEquals(0, service.calls.get())
        assertFalse(manager.isProbedDead(song()))
    }
}
