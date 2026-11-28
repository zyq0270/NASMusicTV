package com.nasmusic.tv.backend.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataOverlayGateTest {
    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relative"),
            File("app/src/main/java/com/nasmusic/tv/$relative"),
            File("../app/src/main/java/com/nasmusic/tv/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relative", file != null)
        return file!!.readText(Charsets.UTF_8)
    }

    @Test
    fun `overlay implementation has no persistent media mutation`() {
        val files = listOf(
            "backend/local/SongReadModel.kt",
            "backend/local/DuplicateDetector.kt",
            "data/stats/StatsAliasResolver.kt",
            "ui/viewmodel/MetadataCleanupViewModel.kt",
            "ui/screens/library/MetadataCleanupScreen.kt",
        )
        files.forEach { path ->
            val source = src(path)
            assertFalse("$path 不得包含 DAO UPDATE", source.contains("@Update"))
            assertFalse("$path 不得写 MediaStore", source.contains("MediaStore") && source.contains("insert("))
            assertFalse("$path 不得删除文件", source.contains(".delete(") || source.contains("deleteRecursively("))
        }
    }

    @Test
    fun `negative control proves mutation tokens are detectable`() {
        val forbidden = "dao.delete(file)"
        assertTrue(forbidden.contains(".delete("))
    }
}
