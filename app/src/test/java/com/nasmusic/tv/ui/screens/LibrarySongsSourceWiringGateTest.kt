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
 * lint 过、全绿，故用源码文本门钉住：ViewModel 产出 → **VM 合成** → UI 透传。
 *
 * | # | 跳 | 不变量 |
 * |---|---|---|
 * | G1 | 产出 | `MainViewModel` 声明 `baiduIndexedSongs`，且在 `updateMergedData` 里写入 `_baiduIndexedSongs.value = baiduSongs` |
 * | G2 | 收集 | `MainViewModel` 的 `visibleSongsPaging` 派生流同时吃 `_songsPaging` / `visibleLocalSongs` / `_baiduIndexedSongs` 三个源；`LibraryBranch` 消费 `viewModel.visibleSongsPaging` 且**不再自行合并** |
 * | G3 | 消费 | `MusicMerger.mergeLibrarySongs(...)` 三个实参齐全（nas/local/baidu），`totalCount` 计入百度数量 |
 * | G4 | 扫描自证 | 实际扫到的 `.kt` 文件数 > 100，防「文件没找到却全绿」 |
 *
 * ⚠️ **2026-10-29（v2.39.9）链条迁移**：合并点从 `LibraryBranch` 的
 * `remember(songsPaging, localSongsList, baiduSongsList)` 迁到了 `MainViewModel` 的
 * `visibleSongsPaging` 派生流 —— 因为那个 `remember` 的三个键在**源开关翻转时都不变**
 * ⇒ 永不重算 ⇒ NAS 段永远不会被置空。本门禁随之改钉新链条；
 * **被保护的不变量（三源都必须进歌曲 Tab）没有变**。
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
    fun `G2 G3 VM synthesises all three sources and LibraryBranch only passes it through`() {
        val vm = flatten(mainSourceFile(mainViewModelPath))
        val lib = flatten(mainSourceFile(libraryBranchPath))
        assertTrue("G2 空转自证：读到的确实是 LibraryBranch", lib.contains("fun LibraryBranch("))
        assertTrue(
            "G2/G3 结构自证：判据必须能判负",
            wiringProblems("", "").isNotEmpty() && wiringProblems(vm, lib).isEmpty()
        )

        // 负自检 1：把百度实参退回空列表，判据必须判负
        val emptyBaidu = vm.replace("baiduSongs = baiduSongs", "baiduSongs = emptyList()")
        assertTrue("G3 护栏失效：百度实参换成 emptyList() 后判据仍判正", wiringProblems(emptyBaidu, lib).isNotEmpty())

        // 负自检 2：把本地实参退回空列表，判据必须判负
        val emptyLocal = vm.replace("localSongs = localSongs", "localSongs = emptyList()")
        assertTrue("G3 护栏失效：本地实参换成 emptyList() 后判据仍判正", wiringProblems(emptyLocal, lib).isNotEmpty())

        // 负自检 3：totalCount 不计本地/百度数量，判据必须判负
        val droppedCount = vm.replace("localSongs.size + baiduSongs.size", "0")
        assertTrue("G3 护栏失效：totalCount 不计百度数量后判据仍判正", wiringProblems(droppedCount, lib).isNotEmpty())

        // 负自检 4：派生流不再吃百度索引，判据必须判负
        val droppedBaiduInput = vm.replace("_baiduIndexedSongs", "_noBaiduSource")
        assertTrue("G2 护栏失效：派生流不吃百度索引后判据仍判正", wiringProblems(droppedBaiduInput, lib).isNotEmpty())

        // 负自检 5：UI 退回原始分页流（绕过可见集），判据必须判负
        val bypassVisible = lib.replace("viewModel.visibleSongsPaging", "viewModel.songsPaging")
        assertTrue("G2 护栏失效：LibraryBranch 绕过 visibleSongsPaging 后判据仍判正", wiringProblems(vm, bypassVisible).isNotEmpty())

        // 负自检 6：UI 侧重新自行合并（合并点应当唯一），判据必须判负
        val doubleMerge = lib.replace("viewModel.visibleSongsPaging", "viewModel.visibleSongsPaging /* MusicMerger.mergeLibrarySongs( */")
        assertTrue("G2 护栏失效：UI 侧出现第二处合并后判据仍判正", wiringProblems(vm, doubleMerge).isNotEmpty())
    }

    /**
     * 接线判据：任何一条不满足都返回一条问题描述。
     *
     * @param vm  已折叠空白的 `MainViewModel.kt`
     * @param lib 已折叠空白的 `LibraryBranch.kt`
     */
    private fun wiringProblems(vm: String, lib: String): List<String> = buildList {
        val block = visibleSongsBlock(vm)
        if (block.isEmpty()) {
            add("MainViewModel 里找不到 `val visibleSongsPaging` 派生流（或它的 stateIn 结束标记）")
            return@buildList
        }
        // 三个源都必须进派生流
        for (token in listOf("_songsPaging", "visibleLocalSongs", "_baiduIndexedSongs")) {
            if (!block.contains(token)) add("`visibleSongsPaging` 的输入缺少 $token —— 歌曲 Tab 会缺一个源")
        }
        // 合并调用必须存在，且三个实参齐全
        val mergeCall = mergeCallOf(block)
        if (mergeCall.isEmpty()) {
            add("缺少 `MusicMerger.mergeLibrarySongs(` 调用")
        } else {
            for (arg in listOf("nasSongs = nasSongs", "localSongs = localSongs", "baiduSongs = baiduSongs")) {
                if (!mergeCall.contains(arg)) add("`mergeLibrarySongs` 缺少实参 `$arg`")
            }
        }
        // totalCount 必须计入本地/百度数量
        if (!block.contains("localSongs.size + baiduSongs.size")) {
            add("`totalCount` 未计入本地/百度数量")
        }
        // UI 侧只做透传
        if (!lib.contains("viewModel.visibleSongsPaging")) {
            add("`LibraryBranch` 没有消费 `viewModel.visibleSongsPaging` —— 歌曲 Tab 会退回未过滤的原始流")
        }
        if (lib.contains("MusicMerger.mergeLibrarySongs(")) {
            add("`LibraryBranch` 又出现了 `MusicMerger.mergeLibrarySongs(` —— 合并点应当唯一（VM 侧）")
        }
    }

    /** 取出 `val visibleSongsPaging` … `stateIn(viewModelScope` 之间的文本 */
    private fun visibleSongsBlock(vm: String): String {
        val start = vm.indexOf("val visibleSongsPaging")
        if (start < 0) return ""
        val end = vm.indexOf("stateIn(viewModelScope", start)
        return if (end < 0) "" else vm.substring(start, end)
    }

    /** 取出 `MusicMerger.mergeLibrarySongs(` 的实参文本（到第一个右括号为止） */
    private fun mergeCallOf(block: String): String {
        val start = block.indexOf("MusicMerger.mergeLibrarySongs(")
        if (start < 0) return ""
        val end = block.indexOf(")", start)
        return if (end < 0) "" else block.substring(start, end)
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
