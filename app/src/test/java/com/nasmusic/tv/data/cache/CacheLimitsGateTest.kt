package com.nasmusic.tv.data.cache

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheLimitsGateTest {
    @Test
    fun `三个缓存都从内存快照读取同一数量上限`() {
        val lyrics = read("lyrics/LyricsPersistentCache.kt")
        val covers = read("backend/local/CoverUrlPersistentCache.kt")
        val playUrls = read("backend/network/NetworkMusicManager.kt")
        assertTrue(lyrics.contains("CacheLimits.current().lyricsMaxEntries"))
        assertTrue(covers.contains("CacheLimits.current().coverUrlMaxEntries"))
        assertTrue(playUrls.contains("CacheLimits.current().playUrlMaxEntries"))
        listOf(lyrics, covers, playUrls).forEach { source ->
            assertFalse(source.contains("MAX_ENTRIES"))
            assertFalse(source.contains("PLAY_URL_CACHE_MAX"))
        }
    }

    @Test
    fun `写路径加载路径与显式维护路径都执行淘汰`() {
        val lyrics = read("lyrics/LyricsPersistentCache.kt")
        val covers = read("backend/local/CoverUrlPersistentCache.kt")
        val playUrls = read("backend/network/NetworkMusicManager.kt")
        listOf(lyrics, covers).forEach { source ->
            assertTrue(source.contains("fun maintain()"))
            assertTrue(source.contains("evictOldest()"))
            assertTrue(source.contains("private fun load"))
        }
        assertTrue(playUrls.contains("fun maintainPlayUrlCache()"))
        assertTrue(playUrls.contains("maintainPlayUrlCache(completedAt)"))
    }

    private fun read(path: String): String {
        val roots = listOf(File("src/main/java/com/nasmusic/tv"), File("app/src/main/java/com/nasmusic/tv"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("source root not found")
        return File(root, path).readText()
    }
}
