package com.nasmusic.tv.player

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class AccompanimentCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun cache(root: File = temp.newFolder("cache")): AccompanimentCache {
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(root)
        return AccompanimentCache(context)
    }

    @Test
    fun `歌曲 ID 包含父目录跳转时三种缓存文件仍留在缓存目录内`() {
        val root = temp.newFolder("cache")
        val cache = cache(root)
        val songId = "../outside"

        val files = listOf(
            cache.getAccompanimentFile(songId),
            cache.getVocalsFile(songId),
            cache.getOriginalFile(songId),
        )

        files.forEach { file ->
            assertTrue(
                "缓存文件逃出 cacheDir：${file.canonicalPath}",
                file.canonicalPath.startsWith(root.canonicalPath + File.separator),
            )
        }
    }

    @Test
    fun `绝对路径样式 songId 不会被当作文件路径`() {
        val root = temp.newFolder("cache")
        val cache = cache(root)

        listOf("/tmp/outside", "C:\\temp\\outside", "..\\outside").forEach { songId ->
            cache.getAccompanimentFile(songId).let { file ->
                assertTrue(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                assertTrue("文件名不应含路径分隔符：${file.name}", file.name.none { it == '/' || it == '\\' })
            }
        }
    }

    @Test
    fun `重复 ID 稳定映射到固定 SHA-256 文件名且不同类型不冲突`() {
        val cache = cache()
        val expectedHash = "90953515094fab847573782b7d46a84c265ba9b723076d0c63be00936165ccbe"

        val first = cache.getAccompanimentFile("track-1")
        val repeated = cache.getAccompanimentFile("track-1")
        val vocals = cache.getVocalsFile("track-1")
        val original = cache.getOriginalFile("track-1")

        assertEquals("${expectedHash}_accompaniment.wav", first.name)
        assertEquals(first.canonicalPath, repeated.canonicalPath)
        assertEquals("${expectedHash}_vocals.wav", vocals.name)
        assertEquals("${expectedHash}_original.wav", original.name)
        assertEquals(3, listOf(first, vocals, original).map { it.name }.toSet().size)
    }
}
