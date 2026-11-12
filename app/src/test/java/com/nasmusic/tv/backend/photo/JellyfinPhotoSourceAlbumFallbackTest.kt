package com.nasmusic.tv.backend.photo

import com.nasmusic.tv.backend.impl.JellyfinAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **门禁：Jellyfin 照片来源的「相册级回退」**（§10.393）
 *
 * ## 它防的是什么
 *
 * 2026-11-12 电视实测：NAS（Jellyfin）已连上、照片墙开关已开、点「重新扫描」⇒ **0 张**。
 *
 * ⇒ 服务器直查取证（Jellyfin 10.11.5）：照片**确实被索引了**（挂在 `PhotoAlbum` 下），
 * 但**全局递归查询一张都摸不到**（全局集合 51592 条按类型求和 = 51590，Photo = 0）。
 * ⇒ 应用侧必须能**按相册逐个查**。
 *
 * 本测试用 MockWebServer 把三条查询形状的差别固化成门禁：
 * - 全局递归（无 `ParentId`）⇒ 0 张 ⇒ **必须**回退
 * - 媒体库根（`Recursive=false` 且无 `ParentId`）⇒ `CollectionFolder`
 * - 相册（`ParentId=<根>` 且无 `IncludeItemTypes`）⇒ `PhotoAlbum` / `Folder`
 * - 相册内照片（`IncludeItemTypes=Photo` 且有 `ParentId`）⇒ 照片
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JellyfinPhotoSourceAlbumFallbackTest {

    private lateinit var server: MockWebServer

    /** 记录所有 `/Items` 请求的查询串（断言「有没有多打请求」） */
    private val itemsQueries = ArrayList<String>()

    /** 全局递归查询的响应体（各用例自己给） */
    private var globalBody: String = """{"TotalRecordCount":0,"Items":[]}"""

    /** 相册 id → 照片响应体 */
    private var albumBodies: Map<String, String> = emptyMap()

    /** 媒体库根的响应体 */
    private var rootBody: String =
        """{"TotalRecordCount":1,"Items":[{"Id":"lib-1","Type":"CollectionFolder"}]}"""

    /** 媒体库根下的子项响应体（相册 / 文件夹 / 非相册类） */
    private var containerBody: String = """
        {"TotalRecordCount":3,"Items":[
          {"Id":"album-1","Type":"PhotoAlbum"},
          {"Id":"folder-1","Type":"Folder"},
          {"Id":"movie-1","Type":"Movie"}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        itemsQueries.clear()
        installDispatcher()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── Mock 服务 ────────────────────────────────────────────────────────

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val url = request.requestUrl
                return when {
                    path.startsWith("/Users/AuthenticateByName") ->
                        json(
                            """{"AccessToken":"tok","User":{"Id":"user-1"},""" +
                                """"ServerInfo":{"ServerName":"TestNAS"}}""",
                        )

                    path.startsWith("/System/Info/Public") ->
                        json("""{"Version":"10.11.5"}""")

                    path.startsWith("/Items") -> {
                        val query = url?.query.orEmpty()
                        itemsQueries += query
                        val parent = url?.queryParameter("ParentId")
                        val types = url?.queryParameter("IncludeItemTypes")
                        when {
                            // 相册内照片：带 ParentId + IncludeItemTypes=Photo
                            types == "Photo" && parent != null ->
                                json(albumBodies[parent] ?: """{"TotalRecordCount":0,"Items":[]}""")

                            // 全局递归查询：无 ParentId
                            types == "Photo" -> json(globalBody)

                            // 媒体库根下的子项：带 ParentId
                            parent != null -> json(containerBody)

                            // 顶层媒体库根：无 ParentId 且无类型过滤
                            else -> json(rootBody)
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    /**
     * 造一个连着 MockWebServer 的来源。
     *
     * ⚠️ **不要在这里再套一层 `runBlocking`** —— 用例本身就是 `runBlocking { … }`，
     * 嵌套 `runBlocking` 会**死锁**（内层等着外层的事件循环，外层等着内层返回）。
     */
    private suspend fun newSource(): JellyfinPhotoSource {
        val adapter = JellyfinAdapter()
        assertTrue(
            "测试用适配器初始化失败",
            adapter.initialize(server.url("/").toString(), "", "user", "pass"),
        )
        return JellyfinPhotoSource({ adapter })
    }

    // ── 用例 ────────────────────────────────────────────────────────────

    @Test
    fun `全局查询 0 张时按相册回退拿到照片`() = runBlocking {
        albumBodies = mapOf(
            "album-1" to """
                {"TotalRecordCount":2,"Items":[
                  {"Id":"p1","Name":"全家福","Type":"Photo","Width":1570,"Height":1184,"Path":"G:\\photo\\照片\\老照片\\全家福.jpg"},
                  {"Id":"p2","Name":"0921","Type":"Photo","Width":1483,"Height":1156,"Path":"G:\\photo\\照片\\老照片\\0921.png"}
                ]}
            """.trimIndent(),
            "folder-1" to """
                {"TotalRecordCount":1,"Items":[
                  {"Id":"p3","Name":"cat","Type":"Photo","Width":800,"Height":600,"Path":"G:\\photo\\照片\\宠物\\cat.jpg"}
                ]}
            """.trimIndent(),
        )

        val photos = newSource().listPhotos()

        assertEquals("相册 + 普通文件夹里的照片都要拿到", 3, photos.size)
        assertEquals(
            listOf("jellyfin:p1", "jellyfin:p2", "jellyfin:p3"),
            photos.map { it.id },
        )
        assertEquals("宽高必须填真实值（§14.2.1 宽高契约）", 1570, photos[0].width)
        assertEquals(1184, photos[0].height)
    }

    @Test
    fun `全局查询 0 张时必须先走相册级回退`() = runBlocking {
        albumBodies = mapOf(
            "album-1" to """{"TotalRecordCount":1,"Items":[{"Id":"p1","Type":"Photo","Path":"G:\\a.jpg"}]}""",
        )

        newSource().listPhotos()

        assertTrue(
            "必须发出带 ParentId 的相册级查询",
            itemsQueries.any { it.contains("ParentId=album-1") },
        )
        assertTrue(
            "必须先列顶层媒体库根（Recursive=false 且不带 ParentId）",
            itemsQueries.any { it.contains("Recursive=false") && !it.contains("ParentId") },
        )
    }

    @Test
    fun `非相册类条目不得被当成相册去查`() = runBlocking {
        albumBodies = mapOf(
            "album-1" to """{"TotalRecordCount":1,"Items":[{"Id":"p1","Type":"Photo","Path":"G:\\a.jpg"}]}""",
        )

        newSource().listPhotos()

        assertFalse(
            "Movie 不是相册级容器，不该对它发照片查询",
            itemsQueries.any { it.contains("ParentId=movie-1") },
        )
    }

    @Test
    fun `全局查询拿到照片时不再发相册请求`() = runBlocking {
        globalBody = """
            {"TotalRecordCount":1,"Items":[
              {"Id":"g1","Type":"Photo","Width":100,"Height":100,"Path":"G:\\photo\\g1.jpg"}
            ]}
        """.trimIndent()

        val photos = newSource().listPhotos()

        assertEquals("全局路径应直接返回", 1, photos.size)
        assertEquals("jellyfin:g1", photos[0].id)
        assertFalse(
            "全局路径成功时不得再发任何带 ParentId 的请求（相册级回退）",
            itemsQueries.any { it.contains("ParentId=") },
        )
        assertFalse(
            "全局路径成功时不得再列媒体库根",
            itemsQueries.any { it.contains("Recursive=false") && !it.contains("ParentId") },
        )
    }

    @Test
    fun `服务器不可达时不得发出成百上千个相册请求`() = runBlocking {
        // 媒体库根查询直接 500 ⇒ 回退必须**立刻收手**
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/Items")) {
                    itemsQueries += request.requestUrl?.query.orEmpty()
                    return MockResponse().setResponseCode(500)
                }
                return when {
                    path.startsWith("/Users/AuthenticateByName") ->
                        json(
                            """{"AccessToken":"tok","User":{"Id":"user-1"},""" +
                                """"ServerInfo":{"ServerName":"TestNAS"}}""",
                        )

                    path.startsWith("/System/Info/Public") ->
                        json("""{"Version":"10.11.5"}""")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val photos = newSource().listPhotos()

        assertTrue("服务器不可达 ⇒ 返回空表", photos.isEmpty())
        assertTrue(
            "回退必须自限：最多 2 次请求（全局 + 媒体库根），实际 ${itemsQueries.size} 次",
            itemsQueries.size <= 2,
        )
    }

    @Test
    fun `电视解不了的扩展名必须被过滤掉`() = runBlocking {
        albumBodies = mapOf(
            "album-1" to """
                {"TotalRecordCount":3,"Items":[
                  {"Id":"p1","Type":"Photo","Path":"G:\\a.jpg"},
                  {"Id":"p2","Type":"Photo","Path":"G:\\b.heic"},
                  {"Id":"p3","Type":"Photo","Path":"G:\\c.dng"}
                ]}
            """.trimIndent(),
        )

        val photos = newSource().listPhotos()

        assertEquals("HEIC / RAW 电视解不了，必须滤掉", listOf("jellyfin:p1"), photos.map { it.id })
    }

    @Test
    fun `Name 不带扩展名时按 Path 判定`() = runBlocking {
        albumBodies = mapOf(
            "album-1" to """
                {"TotalRecordCount":1,"Items":[
                  {"Id":"p1","Name":"？","Type":"Photo","Path":"G:\\photo\\？.png"}
                ]}
            """.trimIndent(),
        )

        val photos = newSource().listPhotos()

        assertEquals("扩展名只在 Path 里（v2.37.4 的老坑）", 1, photos.size)
        assertEquals("jellyfin:p1", photos[0].id)
    }
}
