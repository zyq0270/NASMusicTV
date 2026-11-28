package com.nasmusic.tv.backend.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongReadModelCoverageTest {
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
    fun `all local display reads pass through SongReadModel`() {
        val repo = src("backend/local/LocalMusicRepository.kt")
        val readPaths = listOf(
            "suspend fun loadFromCache()",
            "suspend fun incrementalScan()",
            "suspend fun search(query: String)",
            "suspend fun fullScan()",
            "suspend fun scanUsbDevice(devicePath: String)",
            "suspend fun loadAll()",
        )
        readPaths.forEach { signature ->
            val start = repo.indexOf(signature)
            assertTrue("缺少读取路径 $signature", start >= 0)
            val end = repo.indexOf("\n    /**", start + signature.length).let {
                if (it < 0) repo.indexOf("\n    private ", start + signature.length) else it
            }
            val block = repo.substring(start, if (end < 0) repo.length else end)
            assertTrue("$signature 未应用展示读取模型", block.contains(".display()") || block.contains("loadFromCache()"))
        }
    }

    @Test
    fun `search safely broadens candidates when overrides exist`() {
        val repo = src("backend/local/LocalMusicRepository.kt")
        assertTrue("override 命中需要全量候选后过滤", repo.contains("val broadenCandidates = readModel.hasOverrides"))
        assertTrue("候选策略必须一次读取，避免中途 override 变化造成分支不一致", repo.contains("if (broadenCandidates)"))
        assertTrue("缺少展示字段过滤", repo.contains("song.title.contains(trimmed, ignoreCase = true)"))
    }

    @Test
    fun `cleanup raw read is intentionally excluded`() {
        val repo = src("backend/local/LocalMusicRepository.kt")
        val start = repo.indexOf("suspend fun loadAllRaw()")
        assertTrue("整理页需要原始索引", start >= 0)
        val block = repo.substring(start, repo.indexOf("\n    private fun List<Song>.display", start))
        assertFalse("原始读取不得应用 override/隐藏", block.contains(".display()"))
    }

    @Test
    fun `read model publishes overlay changes for flow invalidation`() {
        val model = src("backend/local/SongReadModel.kt")
        assertTrue("override/resolution 变化必须可被上游 Flow 收集", model.contains("val snapshots: StateFlow<SongReadSnapshot>"))
        assertTrue("快照必须由两个持久化 Flow 合并", model.contains("combine(overrides, resolutions)"))
    }

    @Test
    fun `merger applies read model to final song result`() {
        val merger = src("backend/local/MusicMerger.kt")
        assertTrue("合并结果未应用读取模型", merger.contains("readModel?.applyAll(merged)"))
        assertTrue("本地专辑未使用覆盖后的元数据", merger.contains("readModel?.applyAll(localSongs) ?: localSongs"))
        assertTrue("本地艺术家未使用覆盖后的元数据", merger.contains("songs = displayedSongs"))
    }
}
