package com.nasmusic.tv.backend.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.backend.download.db.DownloadStatus
import com.nasmusic.tv.backend.download.model.DownloadSettings
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.Song
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongDownloadManagerIncompleteMarkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: DownloadDatabase
    private lateinit var repo: DownloadRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var root: File

    private val song = Song(
        id = "ntwk_test_marker_1",
        title = "标记保护测试",
        artist = "测试歌手",
        album = "测试专辑",
        isNetworkSong = true,
        networkSource = "test",
        networkId = "marker_1",
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = DownloadRepository(context, db.downloadSongDao())
        root = temp.newFolder("downloads")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.method == "HEAD") {
                    MockResponse().setResponseCode(200).setHeader("Content-Length", "8")
                } else {
                    MockResponse().setResponseCode(200).setBody("audio123")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        db.close()
    }

    @Test
    fun `内嵌完成标记创建失败时不得修改最终文件或标记下载完成`() = runBlocking {
        val paths = DownloadPathBuilder { root }
        val expected = paths.build(song, "mp3", QualityTiers.AUTO)
        val marker = File(expected.albumDir, expected.finalFile.name + ".incomplete")
        // 用同名目录模拟 writeText 失败，无需注入或改写生产文件操作。
        assertTrue(marker.mkdirs())

        val storage = mock(StorageGuard::class.java)
        `when`(storage.hasRoomFor(anyLong())).thenReturn(true)
        val coverWriter = mock(CoverFileWriter::class.java)
        val resolver = StreamUrlResolver(
            adapter = { null },
            network = { null },
            networkDetailed = { _, _ ->
                ResolveResult(server.url("/audio.mp3").toString(), QualityTiers.AUTO)
            },
        )
        val completedCalls = AtomicInteger(0)
        val manager = SongDownloadManager(
            context = ApplicationProvider.getApplicationContext(),
            scope = scope,
            repo = repo,
            storage = storage,
            paths = DownloadPathBuilder { root },
            rootProvider = { root },
            allRootsProvider = { listOf(root) },
            resolver = resolver,
            tagWriter = MediaTagWriter,
            coverWriter = coverWriter,
            lyricsProvider = { null },
            settings = { DownloadSettings(true, false, 0, "INTERNAL") },
            onNotify = {},
            onCompleted = { completedCalls.incrementAndGet() },
        )

        manager.enqueue(song, auto = false, quality = QualityTiers.AUTO)
        withTimeout(10_000L) {
            while (true) {
                val state = manager.snapshotStates()[song.downloadKey]
                if (state is DownloadState.Failed || state is DownloadState.Completed) break
                delay(10L)
            }
        }

        assertFalse("标记创建失败不得把临时音频改名到 finalFile", expected.finalFile.exists())
        assertEquals(0, completedCalls.get())
        val record = repo.get(song.downloadKey)
        assertEquals(DownloadStatus.FAILED.name, record?.status)
        assertNull(record?.audioPath)
        assertFalse(manager.snapshotStates()[song.downloadKey] is DownloadState.Completed)
    }
}
