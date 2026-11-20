package com.nasmusic.tv.lyrics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 歌词同歌复用门禁：播放直链重解析 / 队列回填导致 playerState 再次发射时，
 * 已加载歌词必须直接复用，不得清空后重新请求（重复网络 + UI 闪烁）。
 *
 * 沿用仓库源码文本门禁惯例；门内带负自检，读文件显式 UTF-8（源码含中文注释）。
 */
class LyricsReuseFlowGateTest {

    private val vmPath = "ui/viewmodel/MainViewModel.kt"

    @Test
    fun `T1 同歌短路判据必须存在且位于清空歌词之前`() {
        val region = loadRegion()
        val guard = "song.id == lastLyricsLoadSongId && _currentLyrics.value != null"
        assertTrue("T1 空转自证：未定位到歌词加载方法体", region.contains("lyricsLoadJob = viewModelScope.launch"))
        assertTrue("T1：缺少同歌复用短路判据，播放重解析仍会重复拉歌词。", region.contains(guard))
        val guardAt = region.indexOf(guard)
        val clearAt = region.indexOf("_currentLyrics.value = null")
        assertTrue("T1：未找到原有的清空歌词语句，门禁需重审。", clearAt >= 0)
        assertTrue("T1：短路必须在清空 _currentLyrics 之前，否则复用无从谈起。", guardAt in 0 until clearAt)
        // 负自检：判据改成「歌词为空才短路」后必须判红
        val mutated = region.replace(guard, "song.id == lastLyricsLoadSongId && _currentLyrics.value == null")
        assertTrue("T1 护栏失效：错误判据仍被判正", !mutated.contains(guard))
    }

    @Test
    fun `T2 必须在每次真正发起加载时记录歌曲 id`() {
        val src = flatten(mainSourceFile(vmPath))
        assertTrue(
            "T2：缺 lastLyricsLoadSongId 字段声明。",
            src.contains("private var lastLyricsLoadSongId: String? = null")
        )
        val region = loadRegion()
        assertTrue(
            "T2：未在加载入口记录当前歌曲 id，同歌短路将永不命中。",
            region.contains("lastLyricsLoadSongId = song?.id")
        )
        assertTrue(
            "T2：记录动作必须在短路判据之后，否则每次都会先覆盖 id 导致短路失效。",
            region.indexOf("lastLyricsLoadSongId = song?.id") > region.indexOf("song.id == lastLyricsLoadSongId")
        )
    }

    @Test
    fun `T3 无当前歌曲时仍须清空歌词状态`() {
        val region = loadRegion()
        val clearAt = region.indexOf("_currentLyrics.value = null")
        val nullReturnAt = region.indexOf("if (song == null) return")
        assertTrue("T3：未找到原有的清空歌词语句。", clearAt >= 0)
        assertTrue(
            "T3：currentSong 为空时必须沿用旧的清空语义，不能因短路改造而保留上一首歌词。",
            nullReturnAt > clearAt
        )
        assertTrue(
            "T3：不得在 song 为空时写 lastLyricsLoadSongId 为非空值。",
            region.contains("lastLyricsLoadSongId = song?.id")
        )
    }

    @Test
    fun `T4 真切歌仍走完整加载与可用性检查`() {
        val region = loadRegion()
        assertTrue("T4：真切歌时必须取消在途加载任务。", region.contains("lyricsLoadJob?.cancel()"))
        assertTrue("T4：真切歌时必须清空网络候选缓存。", region.contains("lyricsManager.clearCachedCandidates()"))
        assertTrue("T4：真切歌时必须重新启动加载协程。", region.contains("lyricsLoadJob = viewModelScope.launch"))
        assertTrue(
            "T4：真切歌时仍须检查后端/网络可用来源。",
            region.contains("lyricsManager.checkAvailability(song)")
        )
    }

    // ─────────────────── 辅助 ───────────────────

    private fun loadRegion(): String = methodRegion(
        flatten(mainSourceFile(vmPath)),
        "private fun loadLyricsForCurrentSong() {",
        "网络歌词候选索引"
    )

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun methodRegion(src: String, signature: String, nextSignature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到方法签名：$signature", start >= 0)
        val end = src.indexOf(nextSignature, start + signature.length)
        assertTrue("找不到下一个方法签名：$nextSignature", end > start)
        return src.substring(start, end)
    }

    private fun mainSourceFile(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv").resolve(relativePath),
            File("app/src/main/java/com/nasmusic/tv").resolve(relativePath)
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件；已尝试：${candidates.joinToString { it.absolutePath }}", file != null)
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }
}
