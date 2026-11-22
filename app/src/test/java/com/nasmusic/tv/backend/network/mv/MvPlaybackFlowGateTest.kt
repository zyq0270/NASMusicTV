package com.nasmusic.tv.backend.network.mv

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MV 播放及切换流程门禁（源码文本扫描）。
 *
 * 锁定本轮修复的四个不变量，防止后续改动无意回退：
 * 1. 本地源空 videoUrl 首搜即按 bvid 解析真实直链（百度首播不再必失败一次）；
 * 2. 内存缓存 key 带来源维度（同名歌不同来源不串味）；
 * 3. 队列末尾静默推进失败时不再置 mvAdvanced=true（退出不重播最后一首音乐）；
 * 4. Searching 态保持 MV 全屏占位、REPEAT_ONE 自动播完重播当前、无残留死 UA 常量。
 *
 * 源码扫描按本工程既有做法（`ScreenUiModeCoverageTest` / `KaraokeFullscreenGateTest` 同路径）。
 * 断言一律语句级（不用 `!contains(禁用字面量)` 的全文否定），避免注释解释自身导致自伤。
 */
class MvPlaybackFlowGateTest {

    @Test
    fun `MvSearchManager 空直链首搜即 resolve`() {
        val src = readSource("com/nasmusic/tv/backend/network/mv/MvSearchManager.kt")
        assertTrue(
            "缺少按 videoUrl.isBlank 判定需要解析的分支",
            "result.mv.videoUrl.isBlank()" in src
        )
        assertTrue(
            "空直链分支缺少 resolveMv(result.mv.bvid) 调用",
            "resolveMv(result.mv.bvid)" in src
        )
        assertTrue(
            "解析结果未用 copy(mv = resolved) 替换原空直链 mv",
            "result.copy(mv = resolved)" in src
        )
    }

    @Test
    fun `MvSearchManager 缓存 key 含来源维度`() {
        val src = readSource("com/nasmusic/tv/backend/network/mv/MvSearchManager.kt")
        assertTrue(
            "searchMvFor 调用 buildCacheKey 时未传 song.networkSource",
            "buildCacheKey(song.title, song.artist, song.networkSource)" in src
        )
        assertTrue(
            "buildCacheKey 未按来源追加缓存 key 维度",
            "\"\$base|\${source.trim().lowercase()}\"" in src
        )
    }

    @Test
    fun `队尾静默推进失败时不得置 mvAdvanced`() {
        val src = readSource("com/nasmusic/tv/ui/viewmodel/MvSearchViewModel.kt")
        val start = src.indexOf("fun onMvPlaybackEnded")
        assertTrue("找不到 onMvPlaybackEnded", start >= 0)
        val end = src.indexOf("fun onMvPrevious", start)
        assertTrue("找不到 onMvPlaybackEnded 结束边界", end > start)
        val block = src.substring(start, end)
        assertTrue(
            "onMvPlaybackEnded 未接收 advanceIndexSilently 返回值",
            "val advancedSong = playerManager.advanceIndexSilently(playMode)" in block
        )
        assertTrue(
            "推进失败分支缺少 null 判定",
            "advancedSong == null" in block
        )
        assertTrue(
            "队尾分支缺少 mvAdvanced = false（退出会重播最后一首音乐）",
            "mvAdvanced = false" in block
        )
    }

    @Test
    fun `Searching 态保持 MV 全屏占位而非回落播放页`() {
        val src = readSource("com/nasmusic/tv/ui/components/branches/NowPlayingBranch.kt")
        assertTrue(
            "NowPlayingBranch 未识别 Searching 态",
            "MvAvailability.Searching" in src
        )
        assertTrue(
            "Searching 态未切换到 MvSearchingScreen 占位",
            "MvSearchingScreen(onExit" in src
        )
    }

    @Test
    fun `REPEAT_ONE 自动播完重播当前而非推进队列`() {
        val src = readSource("com/nasmusic/tv/ui/components/MvPlaybackScreen.kt")
        val start = src.indexOf("if (playbackState == Player.STATE_ENDED")
        assertTrue("找不到 STATE_ENDED 监听", start >= 0)
        val block = src.substring(start, (start + 900).coerceAtMost(src.length))
        assertTrue("STATE_ENDED 分支缺少 repeatOne 判定", "if (repeatOne)" in block)
        assertTrue("repeatOne 分支未 seekTo(0L) 重播", "exoPlayer.seekTo(0L)" in block)
    }

    @Test
    fun `MvPlaybackScreen 无残留未使用 UA 常量`() {
        val src = readSource("com/nasmusic/tv/ui/components/MvPlaybackScreen.kt")
        assertTrue(
            "MvPlaybackScreen 仍有 BILIBILI_UA 死常量（请求头实际由 BaiduHttpDataSourceFactory 注入）",
            !src.contains("private const val BILIBILI_UA")
        )
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("../app/src/main/java/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!.readText()
    }
}
