package com.nasmusic.tv.backend.network.lx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 洛雪宿主 T2–T6 的引擎测试（方案 §9.7）。
 *
 * ⛔ **为什么不放 `test/`**：QuickJS 的两个 `.so` 是 Android native 库，
 * Robolectric 跑在桌面 JVM 上，`System.loadLibrary` 必然失败；把这几条塞进单测
 * 只会得到「永远 skip」的假覆盖。所以它们必须在设备上跑：
 * `connectedDebugAndroidTest --tests "*LxScriptHostTest"`。
 *
 * ⚠️ 夹具全部是**自建**的（`androidTest/assets/lx_fixtures/`），不含任何第三方脚本正文
 * （所有者纪律：不把无授权代码放进仓库）。真实脚本（星海 / Huibq / 野花 / 六音）的
 * 期望值见方案 §2.2 的实测记录，在 T19 真机复验里用真脚本走一遍。
 *
 * 夹具到真实脚本的对应关系：
 * - [loaded_basic] ← 星海 / Huibq（多平台 + 多档位 + `inited` 正常）
 * - [no_inited] ← 野花（初始化挂死，既不 inited 也不抛）
 * - [throws_on_eval] ← 六音（正文自带校验，求值即 `throw`）
 */
@RunWith(AndroidJUnit4::class)
class LxScriptHostTest {

    private val hosts = mutableListOf<LxScriptHost>()
    private val http = OkHttpClient()
    private var server: MockWebServer? = null

    @Before
    fun requireEngine() {
        val engine = LxEngineAvailability.state
        if (engine is EngineState.Unavailable) {
            fail("洛雪引擎在本机不可用（T1 ④ 不通过）：${engine.reason}")
        }
    }

    @After
    fun tearDown() {
        hosts.forEach { runCatching { it.close() } }
        hosts.clear()
        server?.let { runCatching { it.shutdown() } }
        server = null
    }

    // ─────────────────────────── T2 骨架 ───────────────────────────

    /** ES6 脚本能求值、`inited` 能解析、async 处理器能返回直链 */
    @Test
    fun es6ScriptLoadsAndReturnsUrl() = runBlocking {
        val host = host("loaded_basic.js")
        val loaded = loadedOf(host.load())

        assertEquals(setOf("wy", "kg"), loaded.sources.keys)
        assertEquals(listOf("128k", "320k", "flac"), loaded.sources.getValue("wy").qualitys)
        assertEquals(listOf("musicUrl", "lyric"), loaded.sources.getValue("wy").actions)
        assertEquals("夹具·基础音源", loaded.name)
        assertEquals("v1.0.0", loaded.version)

        val url = host.getMusicUrl("wy", LxMusicInfo(songmid = "186016"), "320k")
        assertEquals("https://fixture.local/wy/320k/186016", url)
    }

    /** 处理器抛错 ⇒ null（不得把异常带回调用方） */
    @Test
    fun handlerErrorYieldsNull() = runBlocking {
        val host = host("loaded_basic.js")
        host.load()
        assertNull(host.getMusicUrl("wy", LxMusicInfo(songmid = null), "320k"))
    }

    /** 关掉之后不能再取链 */
    @Test
    fun closedHostReturnsNull() = runBlocking {
        val host = host("loaded_basic.js")
        host.load()
        host.close()
        assertNull(host.getMusicUrl("wy", LxMusicInfo(songmid = "1"), "128k"))
    }

    // ─────────────────────────── T4 lx.request ───────────────────────────

    /** MockWebServer 返回 JSON ⇒ JS 侧 `resp.body.url` 可读（⛔ 不探真实端点） */
    @Test
    fun requestBridgeParsesJsonBody() = runBlocking {
        val mock = startServer()
        mock.enqueue(jsonResponse("""{"url":"http://cdn.local/a.mp3"}"""))

        val host = host("request_echo.js")
        host.load()
        val result = host.getMusicUrl(
            "wy",
            LxMusicInfo(songmid = "1", albumName = mock.url("/api").toString()),
            "320k",
        )
        assertEquals("http://cdn.local/a.mp3|200|application/json", result)
    }

    /** 非 JSON 响应 ⇒ `resp.body` 保持字符串（脚本里的文本分支） */
    @Test
    fun requestBridgeKeepsPlainTextBody() = runBlocking {
        val mock = startServer()
        mock.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("plain-body"),
        )
        val host = host("request_echo.js")
        host.load()
        val result = host.getMusicUrl(
            "wy",
            LxMusicInfo(songmid = "1", albumName = mock.url("/api").toString()),
            "320k",
        )
        // body 是字符串 ⇒ `resp.body.url` 为 undefined ⇒ 夹具回 "(no url)"
        assertEquals("(no url)|200|text/plain", result)
    }

    /** `cancelPending()` 必须让在途取链立刻返回 null（播放抢占） */
    @Test
    fun cancelPendingCompletesWithNull() = runBlocking {
        val mock = startServer()
        mock.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"url":"http://cdn.local/slow.mp3"}""")
                .setBodyDelay(5, TimeUnit.SECONDS),
        )
        val host = host("request_echo.js")
        host.load()
        val job = async(Dispatchers.IO) {
            host.getMusicUrl(
                "wy",
                LxMusicInfo(songmid = "1", albumName = mock.url("/slow").toString()),
                "320k",
            )
        }
        delay(500)
        host.cancelPending()
        assertNull(withTimeoutOrNull(4_000) { job.await() } ?: "未在取消后 4 s 内返回")
    }

    // ─────────────────────────── T5 lx.utils ───────────────────────────

    /** crypto / zlib / buffer 的取值与 Java 侧一致 */
    @Test
    fun utilsBridgesMatchJavaSide() = runBlocking {
        val host = host("utils_probe.js")
        host.load()
        val raw = host.getMusicUrl("wy", LxMusicInfo(songmid = "1"), "128k")
        assertNotNull(raw)
        val parts = raw!!.split('|')
        assertEquals(13, parts.size)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", parts[0]) // md5("abc")
        assertEquals("16", parts[1])
        assertEquals("diff", parts[2])
        assertEquals("aGVsbG8g5LiW55WM", parts[3]) // base64("hello 世界")
        assertEquals("hello 世界", parts[4])
        assertEquals("00010f10ff", parts[5])
        assertEquals("616263", parts[6])
        assertEquals("secret", parts[7]) // AES-128-CBC 往返
        assertEquals("zlib-body", parts[8]) // deflate → inflate 往返
        assertEquals("mobile", parts[9])
        assertEquals("2.0.0", parts[10])
        assertEquals("夹具·工具自检", parts[11])
        assertEquals("raw", parts[12])
    }

    // ─────────────────────────── T3 垫片语义 ───────────────────────────

    /** `setTimeout` 到点真的会跑（Java 侧定时器 → JS 回调 → 结果回传） */
    @Test
    fun timerFiresAndResolves() = runBlocking {
        val host = host("timer_probe.js")
        host.load()
        assertEquals("timer-ok", host.getMusicUrl("wy", LxMusicInfo(songmid = "1"), "128k"))
    }

    // ─────────────────────────── T6 失败态 ───────────────────────────

    @Test
    fun missingInitedTimesOut() = runBlocking {
        val host = host("no_inited.js", initTimeoutMs = 1_500)
        assertEquals(LxFailReason.L1_TIMEOUT, failedOf(host.load()).reason)
    }

    @Test
    fun evalThrowReportsScriptError() = runBlocking {
        val host = host("throws_on_eval.js")
        val failed = failedOf(host.load())
        assertEquals(LxFailReason.L1_SCRIPT_ERROR, failed.reason)
        assertTrue("detail 应带脚本原文：${failed.detail}", failed.detail?.contains("加载即失败") == true)
    }

    @Test
    fun statusFalseReportsScriptError() = runBlocking {
        val host = host("status_false.js")
        assertEquals(LxFailReason.L1_SCRIPT_ERROR, failedOf(host.load()).reason)
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun loadedOf(info: LxScriptInfo): LxScriptInfo.Loaded {
        if (info is LxScriptInfo.Loaded) return info
        fail("期望 Loaded，实际：$info")
        throw AssertionError("unreachable")
    }

    private fun failedOf(info: LxScriptInfo): LxScriptInfo.Failed {
        if (info is LxScriptInfo.Failed) return info
        fail("期望 Failed，实际：$info")
        throw AssertionError("unreachable")
    }

    private fun host(fixture: String, initTimeoutMs: Long = 10_000L): LxScriptHost {
        val host = LxScriptHost(
            scriptId = fixture.removeSuffix(".js"),
            scriptText = readFixture(fixture),
            bootScript = readBootScript(),
            httpClient = http,
            initTimeoutMs = initTimeoutMs,
        )
        hosts += host
        return host
    }

    private fun startServer(): MockWebServer =
        MockWebServer().also {
            it.start()
            server = it
        }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun readFixture(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("lx_fixtures/$name")
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readBootScript(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("lx/boot.js")
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
