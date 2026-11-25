package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 专辑详情「封面 + 曲目列表」链路五项修复源码门禁。
 *
 * UI Composable 行为测试本仓库跑不了，沿用 AlbumsListFlowGateTest 的源码文本门；
 * 每组带负自检（把修复形态抹掉后断言必须判负），防止判据恒真空转。
 */
class AlbumDetailFlowGateTest {

    /** when 分支条件可用逗号或 `||`；只认两个详情屏都路由到 backToDetailOrigin。 */
    private val detailBackBranches = listOf(
        Regex(
            """currentScreen\s*==\s*Screen\.AlbumDetail\s*(?:\|\||,)\s*currentScreen\s*==\s*Screen\.ArtistDetail\s*->\s*backToDetailOrigin"""
        ),
        Regex(
            """currentScreen\s*==\s*Screen\.ArtistDetail\s*(?:\|\||,)\s*currentScreen\s*==\s*Screen\.AlbumDetail\s*->\s*backToDetailOrigin"""
        )
    )

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun src(relativePath: String): String = flatten(mainSourceFile(relativePath))

    private fun region(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("找不到起始签名：$start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("找不到结束签名：$end", to > from)
        return source.substring(from, to)
    }

    @Test
    fun `T1 专辑详情硬件 BACK 必须回来源或曲库而不是首页`() {
        val source = src("ui/components/AppRoot.kt")
        assertTrue(
            "缺详情页 BACK 分支 ⇒ 落到 else → Screen.Home",
            detailBackBranches.any { it.containsMatchIn(source) }
        )
        assertTrue(
            "navigateLibrary 未定义或目标错误",
            source.contains("val navigateLibrary: () -> Unit = { viewModel.navVM.navigateTo(Screen.Library) }")
        )
        // G7-01：详情有首页/播放页两个非曲库入口，BACK 要回来源；无来源时仍回曲库兜底
        assertTrue(
            "backToDetailOrigin 未定义或缺「无来源回曲库」兜底",
            source.contains("val backToDetailOrigin: () -> Unit = {") &&
                source.contains("else navigateLibrary()")
        )
        val mutated = source.replace(Regex("""->\s*backToDetailOrigin"""), "-> navigateHome")
        assertTrue("T1 护栏失效：把详情分支改去首页仍判正", detailBackBranches.none { it.containsMatchIn(mutated) })
    }

    @Test
    fun `T2 取歌必须在协程外捕获 album 快照，禁止协程内读全局 selectedAlbum`() {
        val source = src("ui/viewmodel/MainViewModel.kt")
        assertTrue("loadAlbumSongs 签名没改成接收 Album", source.contains("fun loadAlbumSongs(album: Album)"))
        val body = region(source, "fun loadAlbumSongs(album: Album)", "fun getAlbumSongsCache(")
        assertTrue("albumId 未在协程外捕获", body.contains("val albumId = album.id"))
        assertTrue(
            "协程内仍读 _selectedAlbum.value ⇒ 快速切换专辑会串歌",
            !body.contains("_selectedAlbum.value")
        )
        val open = region(source, "fun openAlbumDetail(album: Album)", "fun openArtistDetail(")
        assertTrue("openAlbumDetail 没把 album 传进去", open.contains("loadAlbumSongs(album)"))
        assertTrue(
            "T2 护栏失效：删掉捕获仍判正",
            !body.replace("val albumId = album.id", "").contains("val albumId = album.id")
        )
    }

    @Test
    fun `T3 专辑详情必须有加载错误空三态并接到分支与屏幕`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        for (fragment in listOf(
            "val albumDetailLoading: StateFlow<Set<String>>",
            "val albumDetailErrors: StateFlow<Map<String, String>>"
        )) {
            assertTrue("T3（VM）：缺状态 $fragment", vm.contains(fragment))
        }
        val load = region(vm, "fun loadAlbumSongs(album: Album)", "fun getAlbumSongsCache(")
        for (fragment in listOf(
            "_albumDetailLoading.value = _albumDetailLoading.value + albumId",
            "_albumDetailErrors.value = _albumDetailErrors.value - albumId",
            "try {",
            "catch (e: Exception)",
            "finally {",
            "_albumDetailLoading.value = _albumDetailLoading.value - albumId"
        )) {
            assertTrue("T3（加载）：缺片段 $fragment", load.contains(fragment))
        }
        val branch = src("ui/components/branches/AlbumDetailBranch.kt")
        for (fragment in listOf(
            "viewModel.albumDetailLoading.collectAsState",
            "viewModel.albumDetailErrors.collectAsState",
            "isLoading = isDetailLoading",
            "errorMessage = detailError",
            "onRetry ="
        )) {
            assertTrue("T3（分支）：缺片段 $fragment", branch.contains(fragment))
        }
        val screen = src("ui/screens/AlbumDetailScreen.kt")
        for (fragment in listOf(
            "isLoading: Boolean = false",
            "errorMessage: String? = null",
            "LoadingIndicator(",
            "ErrorDisplay(",
            "EmptyState("
        )) {
            assertTrue("T3（屏幕）：缺片段 $fragment", screen.contains(fragment))
        }
    }

    @Test
    fun `T4 搜索态专辑必须自带封面与来源并在取歌时走搜索结果`() {
        val screen = src("ui/screens/LibraryScreen.kt")
        val searchRegion = region(screen, "val searchAlbums = searchResults", "// 合并本地过滤的专辑")
        assertTrue(
            "仍按 albumId 聚合 ⇒ 网络搜索的 id 被当成 NAS id",
            !searchRegion.contains("groupBy { it.albumId ?:")
        )
        assertTrue("搜索聚合没与 MusicMerger 同口径", searchRegion.contains("ArtistSplitter.albumKey(it.album, it.artist)"))
        assertTrue("未带独立前缀 id", searchRegion.contains("search_album_"))
        assertTrue("未带封面 ⇒ 合成卡永久显示占位符", searchRegion.contains("coverUrl = songs.firstOrNull"))
        assertTrue("未回填 sourceIds ⇒ 详情仍走单源兜底", searchRegion.contains("sourceIds = listOf(searchAlbumId)"))

        val vm = src("ui/viewmodel/MainViewModel.kt")
        val collect = region(vm, "private suspend fun collectAlbumSongsMultiSource(", "fun loadAlbumSongs(")
        assertTrue("VM 未识别 search_album_ 前缀", collect.contains("src.startsWith(\"search_album_\")"))
        assertTrue("未读当前搜索结果", collect.contains("searchVM.searchResults.value.dataOrNull()"))
        assertTrue("未做源可见性过滤", collect.contains("sourceVisibility.isVisible(it)"))
        assertTrue("未按专辑名+艺术家过滤", collect.contains("filterSongsByAlbumName(albumName, candidates, albumArtist)"))
        assertTrue(
            "T4 护栏失效：删掉搜索分支仍判正",
            !collect.replace("src.startsWith(\"search_album_\")", "").contains("src.startsWith(\"search_album_\")")
        )
    }

    @Test
    fun `T5 专辑卡不得再画点了不生效的播放文案`() {
        val components = src("ui/screens/library/browse/BrowseComponents.kt")
        val card = region(components, "internal fun AlbumCard(", "internal fun ArtistCard(")
        assertTrue("卡片残留 player_play 文案", !card.contains("player_play"))
        assertTrue("卡片残留 ▶ 播放符号", !card.contains("▶"))
        assertTrue("数量徽标被误删", card.contains("CoverCountBadge("))
        assertTrue("说明注释缺失，容易被人照旧加回去", card.contains("整卡进详情"))
        val sabotaged = card + " stringResource(R.string.player_play)"
        assertTrue("T5 护栏失效：塞回播放文案仍判正", sabotaged.contains("player_play"))
    }

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码为空：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到源码目录（user.dir=${File("").absolutePath}）", root != null)
        return root!!
    }
}
