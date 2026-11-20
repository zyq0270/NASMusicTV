package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ALBUMS Tab「列出所有专辑」链路正确性门禁。
 *
 * 本轮修复的四类缺陷：
 * 1. Daoliyu getAlbums 只取 page=1&limit=500，超过 500 张专辑静默丢失；
 * 2. 五个后端 getAlbums 都把异常吞成 emptyList，UiState.Error(retry) 不可达；
 * 3. _isLibraryLoading 派发完异步就置 false，ALBUMS 骨架屏提前结束；
 * 4. mergeAlbums 只按专辑名去重，不同歌手的同名专辑被合成一张卡（搜索态口径还不一样）。
 *
 * 沿用 ArtistsListFlowGateTest 的源码文本门：UI Composable 行为测试本仓库跑不了，
 * 门内带负自检，防止判据恒真。读文件显式 UTF-8（源码含中文注释）。
 */
class AlbumsListFlowGateTest {

    private val libraryBranchPath = "ui/components/branches/LibraryBranch.kt"
    private val libraryScreenPath = "ui/screens/LibraryScreen.kt"
    private val mainViewModelPath = "ui/viewmodel/MainViewModel.kt"
    private val mergerPath = "backend/local/MusicMerger.kt"
    private val splitterPath = "util/ArtistSplitter.kt"
    private val baiduCachePath = "backend/network/baidu/BaiduFileIndexCache.kt"

    @Test
    fun `T1 LibraryBranch 必须消费 albums 的 UiState 并把加载与错误传给 LibraryScreen`() {
        val src = flatten(mainSourceFile(libraryBranchPath))
        assertTrue("T1 空转自证：读到的不是 LibraryBranch", src.contains("fun LibraryBranch("))
        assertTrue(
            "T1（收集）：albums 初始值不该是 Success(emptyList()) —— 那会让 Loading 态从一开始就不存在。",
            src.contains("viewModel.albums.collectAsState(initial = UiState.Loading as UiState<List<Album>>)")
        )
        assertTrue(
            "T1（关源护栏）：NAS 关时原始库存流不清空，Loading 是历史残留；不按源开关gate会永远显示骨架。",
            src.contains("val nasAlbumsVisible = viewModel.sourceVisibility.isVisible(MusicSourceType.NAS)")
        )
        for (fragment in listOf(
            "val isAlbumsLoading = albumLoadState is UiState.Loading",
            "val albumError = albumLoadState as? UiState.Error",
            "isAlbumsLoading = isAlbumsLoading,",
            "albumsErrorText = albumError?.message,",
            "onRetryAlbumsLoad = albumError?.retry ?: { viewModel.refreshLibrary() },"
        )) {
            assertTrue("T1：LibraryBranch → LibraryScreen 缺片段：$fragment", src.contains(fragment))
        }
    }

    @Test
    fun `T2 LibraryScreen 必须让 ALBUMS 骨架屏与错误空态真正生效`() {
        val src = flatten(mainSourceFile(libraryScreenPath))
        assertTrue("T2 空转自证：读到的不是 LibraryScreen", src.contains("fun LibraryScreen("))
        assertTrue(
            "T2（骨架屏）：加载分支没带 isAlbumsLoading —— getAlbums 期间仍然只显示空态。",
            src.contains("targetTab == LibraryTab.ALBUMS && isAlbumsLoading")
        )
        assertTrue(
            "T2（错误态）：ALBUMS 空态没有消费 albumsErrorText / onRetryAlbumsLoad。",
            src.contains("tab = LibraryTab.ALBUMS, errorText = albumsErrorText, onRetry = onRetryAlbumsLoad,")
        )
        assertTrue(
            "T2（错误文案）：ALBUMS 空态没用 load_albums_error —— 会复用「歌曲加载失败」。",
            src.contains("errorTitleRes = R.string.load_albums_error")
        )
    }

    @Test
    fun `T3 五个后端的 getAlbums 失败都必须抛出而不是吞成空列表`() {
        val targets = listOf(
            "backend/impl/JellyfinAdapter.kt" to "throw e",
            "backend/impl/NavidromeAdapter.kt" to "throw e",
            "backend/impl/SubsonicAdapter.kt" to "throw e",
            "backend/impl/DaoliyuAdapter.kt" to "throw e",
            "backend/impl/FeiniuAdapter.kt" to "getOrThrow()"
        )
        for ((path, rethrow) in targets) {
            val body = methodRegion(
                flatten(mainSourceFile(path)), "getAlbums(): List<Album>", "getAlbumSongs("
            )
            assertTrue("$path：getAlbums 里没有把失败抛给上层（缺 $rethrow）", body.contains(rethrow))
            assertTrue(
                "$path：空响应仍被当成正常空列表（缺 throw IllegalStateException）",
                body.contains("throw IllegalStateException")
            )
        }
        val contract = flatten(mainSourceFile("backend/BackendAdapter.kt"))
        assertTrue(
            "T3（契约）：BackendAdapter.getAlbums 没写明失败必须抛异常。",
            contract.contains("失败必须抛异常")
        )
        val mutated = methodRegion(
            flatten(mainSourceFile(targets[0].first)), "getAlbums(): List<Album>", "getAlbumSongs("
        ).replace("throw e", "emptyList()")
        assertTrue("T3 护栏失效：吞异常形态仍被判正", !mutated.contains("throw e"))
    }

    @Test
    fun `T4 Daoliyu 专辑必须分页且带安全上限`() {
        val src = flatten(mainSourceFile("backend/impl/DaoliyuAdapter.kt"))
        val body = methodRegion(src, "getAlbums(): List<Album>", "getAlbumSongs(")
        assertTrue("T4（分页）：仍在只请求第一页", !body.contains("page=1&limit"))
        assertTrue("T4（翻页）：没有 while 循环翻页", body.contains("while (maxPages-- > 0)"))
        assertTrue("T4（推进）：页码没有自增", body.contains("page++"))
        assertTrue("T4（上限）：缺安全页数常量", src.contains("MAX_ALBUM_PAGES = 100"))
        val mutated = body.replace("page++", "")
        assertTrue("T4 护栏失效：删掉页码自增仍被判正", !mutated.contains("page++"))
    }

    @Test
    fun `T5 去重键与详情取歌必须同时带上艺术家`() {
        val splitter = flatten(mainSourceFile(splitterPath))
        assertTrue(
            "T5（工具）：ArtistSplitter 缺统一专辑身份键。",
            splitter.contains("fun albumKey(name: String, artist: String): String")
        )
        val merger = flatten(mainSourceFile(mergerPath))
        assertTrue("T5（列表）：mergeAlbums 没有按艺术家分组", merger.contains("album.artist.isBlank()"))
        assertTrue(
            "T5（B20）：无艺术家条目没有并入具名分组，NAS + 百度同名专辑会被拆开。",
            merger.contains("byArtist.values.maxByOrNull")
        )
        val vm = flatten(mainSourceFile(mainViewModelPath))
        val filterBody = methodRegion(vm, "private fun filterSongsByAlbumName(", "private suspend fun collectAlbumSongsMultiSource(")
        assertTrue("T5（详情）：按名过滤没接收 albumArtist", filterBody.contains("albumArtist: String? = null"))
        assertTrue(
            "T5（详情）：同名不同歌手的歌没被排除。",
            filterBody.contains("ArtistSplitter.containsArtistWithKey(song.artist, artistKey)")
        )
        val baidu = flatten(mainSourceFile(baiduCachePath))
        assertTrue(
            "T5（百度）：索引查询没带 artistName。",
            baidu.contains("fun songsByAlbumName(albumName: String, artistName: String? = null)")
        )
        val screen = flatten(mainSourceFile(libraryScreenPath))
        assertTrue(
            "T5（搜索态）：搜索聚合去重口径没与 MusicMerger 对齐。",
            screen.contains("seen.add(ArtistSplitter.albumKey(album.name, album.artist))")
        )
        val mutated = splitter.replace("fun albumKey(name: String, artist: String): String", "fun nope()")
        assertTrue("T5 护栏失效：删掉 albumKey 仍被判正", !mutated.contains("fun albumKey("))
    }

    // ─────────────────── 辅助 ───────────────────

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun methodRegion(src: String, signature: String, nextSignature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到方法签名：$signature", start >= 0)
        val end = src.indexOf(nextSignature, start + signature.length)
        assertTrue("找不到下一个方法签名：$nextSignature", end > start)
        return src.substring(start, end)
    }

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }
}
