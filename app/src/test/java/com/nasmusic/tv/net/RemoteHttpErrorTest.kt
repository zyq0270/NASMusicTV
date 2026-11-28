package com.nasmusic.tv.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.search.SharedSearchResult
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteHttpErrorTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `shared search success returns ordered unified and legacy groups`() {
        val server = RemoteControlServer(app, freePort())
        val token = authToken(server)
        val callbacks = TestCallbacks { query ->
            SharedSearchResult(
                normalizedQuery = query,
                results = listOf(
                    RankedSong(Song("local", "稻香", "周杰伦"), MusicSourceType.LOCAL),
                    RankedSong(Song("nas", "稻香", "周杰伦"), MusicSourceType.NAS),
                ),
                sourceBreakdown = mapOf(MusicSourceType.LOCAL to 1, MusicSourceType.NAS to 1),
            )
        }
        server.start(callbacks)
        try {
            val response = request(server, token, "q=%E7%A8%BB%E9%A6%99")
            assertTrue(response.startsWith("HTTP 200"))
            val json = JsonParser.parseString(response.substringAfter("\r\n\r\n")).asJsonObject
            assertEquals("稻香", json["normalizedQuery"].asString)
            assertEquals("local", json.getAsJsonArray("results")[0].asJsonObject["id"].asString)
            assertEquals("nas", json.getAsJsonArray("results")[1].asJsonObject["id"].asString)
            assertEquals("LOCAL", json.getAsJsonArray("results")[0].asJsonObject["source"].asString)
            assertEquals(1, json.getAsJsonArray("nasResults").size())
            assertEquals(1, json.getAsJsonArray("networkResults").size())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `shared search exception returns 500 with stable error code`() {
        val server = RemoteControlServer(app, freePort())
        val token = authToken(server)
        server.start(TestCallbacks { error("boom") })
        try {
            val response = request(server, token, "q=test")
            assertTrue(response.startsWith("HTTP 500"))
            assertTrue(response.contains("\"error\":\"search_failed\""))
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 20_000)
    fun `shared search timeout returns 504 instead of generic 500`() {
        val server = RemoteControlServer(app, freePort())
        val token = authToken(server)
        server.start(TestCallbacks { delay(30_000); emptyResult() })
        try {
            val response = request(server, token, "q=slow", readTimeoutMs = 15_000)
            assertTrue(response.startsWith("HTTP 504"))
            assertTrue(response.contains("\"error\":\"search_timeout\""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `third concurrent search is rejected with 503`() {
        val server = RemoteControlServer(app, freePort())
        val token = authToken(server)
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val callbacks = TestCallbacks {
            entered.countDown()
            release.await(15, TimeUnit.SECONDS)
            emptyResult()
        }
        server.start(callbacks)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit<String> { request(server, token, "q=first") }
            val second = pool.submit<String> { request(server, token, "q=second") }
            assertTrue("两个搜索未进入回调", entered.await(5, TimeUnit.SECONDS))
            val third = pool.submit<String> { request(server, token, "q=third") }
            val response = third.get(5, TimeUnit.SECONDS)
            assertTrue(response.startsWith("HTTP 503"))
            assertTrue(response.contains("\"error\":\"search busy, retry later\""))
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).startsWith("HTTP 200"))
            assertTrue(second.get(5, TimeUnit.SECONDS).startsWith("HTTP 200"))
        } finally {
            release.countDown()
            pool.shutdownNow()
            server.stop()
        }
    }

    @Test
    fun `missing q returns 400`() {
        val server = RemoteControlServer(app, freePort())
        val token = authToken(server)
        server.start(TestCallbacks { emptyResult() })
        try {
            val response = request(server, token, "")
            assertTrue(response.startsWith("HTTP 400"))
            assertTrue(response.contains("\"error\":\"missing q\""))
        } finally {
            server.stop()
        }
    }

    private fun request(
        server: RemoteControlServer,
        token: String,
        query: String,
        readTimeoutMs: Int = 5_000,
    ): String {
        val port = serverPort(server)
        val suffix = if (query.isEmpty()) "?t=$token" else "?$query&t=$token"
        val connection = URL("http://127.0.0.1:$port/api/search$suffix")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = readTimeoutMs
        return try {
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            "HTTP $code\r\n\r\n$body"
        } finally {
            connection.disconnect()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun authToken(server: RemoteControlServer): String =
        server.javaClass.getDeclaredField("authToken").apply { isAccessible = true }
            .get(server) as String

    private fun serverPort(server: RemoteControlServer): Int =
        server.javaClass.getDeclaredField("port").apply { isAccessible = true }
            .getInt(server)

    private fun emptyResult() = SharedSearchResult("", emptyList(), emptyMap())

    private class TestCallbacks(
        private val sharedSearch: suspend (String) -> SharedSearchResult,
    ) : RemoteCallbacks {
        override fun getQueue(): List<Song> = emptyList()
        override fun getCurrentIndex(): Int = 0
        override fun isPlaying(): Boolean = false
        override fun getProgressMs(): Long = 0L
        override fun getDurationMs(): Long = 0L
        override fun playAt(index: Int): Boolean = true
        override fun moveQueueItem(from: Int, to: Int): Boolean = true
        override fun removeFromQueue(index: Int): Boolean = true
        override fun addToQueue(song: Song): Boolean = true
        override suspend fun search(keyword: String): RemoteSearchResult = error("legacy search must not be called")
        override suspend fun searchShared(keyword: String): SharedSearchResult = sharedSearch(keyword)
    }
}
