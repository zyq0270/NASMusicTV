package com.nasmusic.tv.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoiceSearchGateTest {

    @Test
    fun `manifest declares only precise speech recognition query and no microphone permission`() {
        val manifest = source("AndroidManifest.xml", "app/src/main")
        val query = Regex(
            """<intent>\s*<action android:name="android\.speech\.action\.RECOGNIZE_SPEECH"\s*/>\s*<category android:name="android\.intent\.category\.DEFAULT"\s*/>\s*</intent>"""
        )
        assertTrue("缺少精确的 RECOGNIZE_SPEECH queries 声明", query.containsMatchIn(manifest))
        assertFalse("不得声明录音权限", manifest.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `h5 gates microphone by secure context and browser speech support`() {
        val html = source("RemoteControlHtml.kt")
        assertTrue(html.contains("window.isSecureContext"))
        assertTrue(html.contains("window.SpeechRecognition || window.webkitSpeechRecognition"))
        assertTrue(html.contains("id=\"voice-search-btn\" style=\"display:none\""))
        assertTrue(html.contains("if (!window.isSecureContext || !Recognition)"))
        assertTrue(html.contains("not-allowed"))
        assertTrue(html.contains("service-not-allowed"))
        assertTrue(html.contains("audio-capture"))
        assertTrue(html.contains("no-speech"))
        assertTrue(html.contains("network"))
        assertTrue("HTTP 下输入框必须保留", html.contains("id=\"search-input\""))
    }

    @Test
    fun `h5 checks response status and distinguishes busy timeout and empty states`() {
        val html = source("RemoteControlHtml.kt")
        assertTrue(html.contains("if (!r.ok)"))
        assertTrue(html.contains("error.status === 503"))
        assertTrue(html.contains("error.status === 504"))
        assertTrue(html.contains("error.code === 'search_timeout'"))
        assertTrue(html.contains("STR.searchEmptyQuery"))
        assertTrue(html.contains("STR.noResults"))
        assertTrue(html.contains("new AbortController()"))
    }

    @Test
    fun `tv entry probes recognizer catches launch failures and normalizes result`() {
        val branch = source("LibraryBranch.kt", "app/src/main/java/com/nasmusic/tv/ui/components/branches")
        assertTrue(branch.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"))
        assertTrue(branch.contains("context.packageManager.resolveActivity(speechIntent, 0)"))
        assertTrue(branch.contains("catch (e: ActivityNotFoundException)"))
        assertTrue(branch.contains("catch (e: Exception)"))
        assertFalse("电视语音入口不得预先规范化，避免双重处理", branch.contains("VoiceQueryNormalizer.normalize(raw)"))
        assertTrue(branch.contains("val query = raw"))
        assertTrue(branch.contains("viewModel.searchSongsOnServer(query)"))
        assertTrue(branch.contains("isTVDevice()"))
    }

    @Test
    fun `remote endpoint uses shared callback and never logs query text or token url`() {
        val server = source("RemoteControlServer.kt")
        assertTrue(server.contains("callbacks.searchShared(query)"))
        assertTrue(server.contains("suspend fun searchShared(keyword: String): SharedSearchResult"))
        assertTrue(server.contains("Response.Status.SERVICE_UNAVAILABLE"))
        assertTrue(server.contains("getRequestStatus(): Int = 504"))
        assertFalse(server.contains("q=\${query.take(50)}"))
        assertFalse(server.contains("url=\$serverUrl"))
    }

    @Test
    fun `shared service pins precise pinyin normalization and visibility`() {
        val service = source("SharedSearchService.kt", "app/src/main/java/com/nasmusic/tv/backend/search")
        assertTrue(service.contains("normalizeDetailed(rawQuery)"))
        assertTrue(service.contains("suspend fun searchNormalized("))
        assertTrue(service.contains("FilterMode.PRECISE"))
        assertTrue(service.contains("enablePinyin = true"))
        assertTrue(service.contains("sourceVisibility?.isVisible(ranked.source)"))
        assertTrue(service.contains("recordSearchHistory(query)"))
    }

    @Test
    fun `android auto and voice playback share normalized search and preserve queue on empty`() {
        val service = source("PlaybackService.kt", "app/src/main/java/com/nasmusic/tv/player")
        assertTrue(service.contains("sharedSearchService.normalize(rawQuery)"))
        assertTrue(service.contains("sharedSearchService.searchNormalized("))
        assertTrue(service.contains("offlinePolicyFlow().first()"))
        assertFalse(service.contains("mediaLibraryTree.search(q)"))
        assertTrue(service.contains("Search returned no playable result"))
    }

    private fun source(relative: String, base: String = "app/src/main/java/com/nasmusic/tv/net"): String {
        val root = listOf(
            File(base, relative),
            File("../$base", relative),
            File(System.getProperty("user.dir", ""), "$base/$relative"),
        ).firstOrNull { it.isFile }
            ?: throw AssertionError("找不到源码：$base/$relative")
        return root.readText(Charsets.UTF_8)
    }
}
