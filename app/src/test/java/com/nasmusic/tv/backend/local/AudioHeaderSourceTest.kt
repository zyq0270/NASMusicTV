package com.nasmusic.tv.backend.local

import com.nasmusic.tv.backend.BackendAuthHeaders
import com.nasmusic.tv.data.model.ReplayGainInfo
import com.nasmusic.tv.data.model.ReplayGainSource
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.data.model.SongSourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioHeaderSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: Media3AudioHeaderSource
    private val song = Song(id = "local_1", title = "fixture")
    private val identity = SongSourceIdentity(SongSourceKind.NAS, "1", "jellyfin", "srv")

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        source = Media3AudioHeaderSource(RuntimeEnvironment.getApplication())
        BackendAuthHeaders.update({ mapOf("Authorization" to "test-token") }, server.hostName)
    }

    @After fun tearDown() {
        BackendAuthHeaders.clear(); server.shutdown()
    }

    @Test fun `HTTP 200 without range is rejected`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ID3"))
        val result = runCatching { source.open(request()) }
        assertTrue(result.isFailure)
    }

    @Test fun `HTTP 206 uses auth header and bounds body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-9/10").setBody("0123456789"))
        source.open(request()).use { response ->
            assertTrue(response.supportsRange)
            assertEquals(206, response.statusCode)
            assertEquals(10, response.stream.readBytes().size)
        }
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("bytes=0-${Media3AudioHeaderSource.DEFAULT_HEADER_BUDGET - 1}", recorded.getHeader("Range"))
        assertEquals("test-token", recorded.getHeader("Authorization"))
    }

    @Test fun `fake source reports no range and reader returns none`() = runBlocking {
        val fake = object : AudioHeaderSource {
            override suspend fun open(request: AudioHeaderRequest) =
                AudioHeaderResponse(ByteArrayInputStream(byteArrayOf(0x49, 0x44, 0x33)), 200, false)
        }
        assertEquals(ReplayGainInfo.NONE, ReplayGainTagReader.read(fake, request()))
    }

    @Test fun `fake read exceeding budget is bounded`() = runBlocking {
        val fake = object : AudioHeaderSource {
            override suspend fun open(request: AudioHeaderRequest) =
                AudioHeaderResponse(ByteArrayInputStream(ByteArray(2048)), null, true)
        }
        assertEquals(ReplayGainInfo.NONE, ReplayGainTagReader.read(fake, request(), maxBytes = 1024))
    }

    @Test fun `cancelled pending open cancels request`() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = AtomicReference(false)
        val fake = object : AudioHeaderSource {
            override suspend fun open(request: AudioHeaderRequest): AudioHeaderResponse {
                entered.countDown()
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
        }
        val job = launch(Dispatchers.IO) { ReplayGainTagReader.read(fake, request()) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        job.join()
        assertTrue(cancelled.get())
        assertTrue(job.isCancelled)
    }

    private fun request() = AudioHeaderRequest(song, identity, server.url("/audio.mp3").toString(), "mp3")
}
