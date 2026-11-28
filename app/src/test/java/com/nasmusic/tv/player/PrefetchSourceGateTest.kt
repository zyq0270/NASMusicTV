package com.nasmusic.tv.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchSourceGateTest {
    @Test
    fun `调度器不得引用播放控制入口`() {
        val source = source("player/PlayUrlPrefetchScheduler.kt")
        listOf(
            "builtinStreamUrlResolver", "replayAt", "resolveAndPlayByIndex",
            "playQueue", "onNeedResolveStreamUrl", "PlaybackService", "PlayerManager",
        ).forEach { forbidden ->
            assertFalse("调度器不得引用 $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `预刷接口只出现在缓存管理层且不修改 Song`() {
        val source = source("backend/network/NetworkMusicManager.kt")
        assertTrue(source.contains("suspend fun prefetchPlayUrl(song: Song): PrefetchResult"))
        assertTrue(source.contains("preserveOldUrl = true"))
        assertFalse("纯预刷不得写 Song.streamUrl", Regex("song\\.streamUrl\\s*=").containsMatchIn(source))
    }

    @Test
    fun `暂停缓冲结束切歌与断网立即取消预刷`() {
        val source = source("player/PlaybackService.kt")
        val listener = source.substringAfter("private val playerListener")
            .substringBefore("private fun beginActivePlay")
        assertTrue("暂停必须取消预刷", listener.contains("pauseActivePlay()\n                cancelPrefetchJobs()"))
        assertTrue("缓冲与结束必须取消预刷", listener.contains("Player.STATE_BUFFERING || playbackState == Player.STATE_ENDED"))
        assertTrue("切歌必须取消预刷", listener.substringAfter("override fun onMediaItemTransition")
            .substringBefore("override fun onPlayerError").contains("cancelPrefetchJobs()"))
        assertTrue("播放错误必须取消预刷", listener.substringAfter("override fun onPlayerError")
            .contains("cancelPrefetchJobs()"))
        assertTrue("断网回调必须直接取消预刷", source.contains("playerManager.onNetworkLost = ::cancelPrefetchJobs"))
    }

    private fun source(path: String): String {
        val roots = listOf(File("src/main/java/com/nasmusic/tv"), File("app/src/main/java/com/nasmusic/tv"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("source root not found")
        return File(root, path).readText()
    }
}
