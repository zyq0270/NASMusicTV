package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 曲库「歌曲」Tab 多源展示的源码接线门（G1–G4）。
 *
 * 背景：百度索引此前只并入专辑/艺术家与搜索聚合，没进「歌曲」Tab，
 * 但空态提示却写着「扫描本地音乐或索引百度网盘」——用户索引完仍看不到歌。
 * UI Composable 的行为测试在本仓库跑不了，链条上任何一跳漏传都会编译过、
 * lint 过、全绿，故用源码文本门钉住：ViewModel 产出 → 分支收集 → 合并函数消费。
 *
 * | # | 跳 | 不变量 |
 * |---|---|---|
 * | G1 | 产出 | `MainViewModel` 声明 `baiduIndexedSongs`，且在 `updateMergedData` 里写入 `_baiduIndexedSongs.value = baiduSongs` |
 * | G2 | 收集 | `LibraryBranch` 收集 `viewModel.baiduIndexedSongs` |
 * | G3 | 消费 | `MusicMerger.mergeLibrarySongs(...)` 三个实参齐全（nas/local/baidu），`totalCount` 计入百度数量 |
 * | G4 | 扫描自证 | 实际扫到的 `.kt` 文件数 > 100，防「文件没找到却全绿」 |
 *
 * ⚠️ 判据前把空白折叠为单个空格；断言一律 `assertTrue`；读文件显式 UTF-8。
 */
class LibrarySongsSourceWiringGateTest {

    private val mainViewModelPath = "ui/viewmodel/MainViewModel.kt"
    private val libraryBranchPath = "ui/components/branches/LibraryBranch.kt"

    @Test
    fun `G1 MainViewModel exposes baiduIndexedSongs and refreshes it in updateMergedData`() {
        val src = flatten(mainSourceFile(mainViewModelPath))
        assertTrue("G1 空转自证：读到的确实是 MainViewModel", src.contains("class MainViewModel"))
        assertTrue(
            "G1（产出）：缺少 `val baiduIndexedSongs` 暴露 —— 歌曲 Tab 拿不到百度索引歌曲。",
            src.contains("val baiduIndexedSongs: StateFlow<List<Song>>")
        )
        assertTrue(
            "G1（刷新）：`updateMergedData` 没有写入 `_baiduIndexedSongs.value = baiduSongs` ——\n" +
                "索引扫描/启动恢复/封面回填后歌曲列表不会更新。",
            src.contains("_baiduIndexedSongs.value = baiduSongs")
        )
    }

    @Test
    fun `G2 G3 LibraryBranch collects baidu songs and feeds all three sources to merge`() {
        val src = flatten(mainSourceFile(libraryBranchPath))
        assertTrue("G2 空转自证：读到的确实是 LibraryBranch", src.contains("fun LibraryBranch("))
        assertTrue("G2/G3 结构自证：判据必须能判负", branchProblems("").isNotEmpty() && branchProblems(src).isEmpty())

        // 负自检：把百度实参退回空列表 / 删掉收集点，判据必须判负
        val emptyBaidu = src.replace("baiduSongs = baiduSongsList", "baiduSongs = emptyList()")
        assertTrue("G3 护栏失效：百度实参换成 emptyList() 后判据仍判正", branchProblems(emptyBaidu).isNotEmpty())
        val droppedCollect = src.replace("viewModel.baiduIndexedSongs.collectAsState", "emptyList<Song>().let { it }")
        assertTrue("G2 护栏失效：删掉收集点后判据仍判正", branchProblems(droppedCollect).isNotEmpty())
        val droppedCount = src.replace("totalCount = songsPaging.totalCount + localSongsList.size + baiduSongsList.size", "totalCount = songsPaging.totalCount")
        assertTrue("G3 护栏失效：totalCount 不计百度数量后判据仍判正", branchProblems(droppedCount).isNotEmpty())
    }

    private fun branchProblems(flat: String): List<String> = buildList {
        if (!flat.contains("viewModel.baiduIndexedSongs.collectAsState")) {
            add("缺少 `viewModel.baiduIndexedSongs.collectAsState` 收集")
        }
        if (!flat.contains("MusicMerger.mergeLibrarySongs(")) {
            add("缺少 `MusicMerger.mergeLibrarySongs(` 调用")
        }
        if (!flat.contains("nasSongs = songsPaging.songs")) add("缺少 `nasSongs = songsPaging.songs` 实参")
        if (!flat.contains("localSongs = localSongsList")) add("缺少 `localSongs = localSongsList` 实参")
        if (!flat.contains("baiduSongs = baiduSongsList")) add("缺少 `baiduSongs = baiduSongsList` 实参")
        if (!flat.contains("songsPaging.totalCount + localSongsList.size + baiduSongsList.size")) {
            add("`totalCount` 未计入本地/百度数量")
        }
    }

    @Test
    fun `G4 main source scan self proof`() {
        val root = mainSourceRoot()
        val count = root.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue("G4：只扫到 $count 个 .kt 文件，源码根疑似找错", count > 100)
    }

    // ─────────────────── 辅助（照抄 SearchPagingWiringGateTest） ───────────────────

    private fun flatten(text: String): String = text.replace(WHITESPACE, " ")

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),        // user.dir = app/
            File("app/src/main/java/com/nasmusic/tv"),    // user.dir = 仓库根
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
