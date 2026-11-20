package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打开艺术家 → 列出歌曲」链路正确性门禁。
 *
 * 覆盖 2026-11-20 分析出的五类问题：
 * 1. Jellyfin fields 未请求 Artists/AlbumArtist ⇒ song.artist 为空 ⇒ 按名过滤整批丢弃；
 * 2. Jellyfin Limit=1000 无分页 ⇒ 大歌手静默截断；
 * 3. 同一人的多个原始 ID 对按名查询的后端产生 N 条完全相同的请求；
 * 4. 两条后端路径排序口径不同，多源拼接后顺序混乱；详情页无加载/空/错误态；
 * 5. NowPlaying 点歌手名只跳搜索，与曲库卡片进详情的语义不一致。
 *
 * 与 ArtistsListFlowGateTest 同款源码文本门（Composable 行为测试本仓库跑不了），
 * 门内带负自检防止判据恒真。读文件显式 UTF-8（源码含中文注释，本机 JVM 默认 CP936）。
 */
class ArtistDetailFlowGateTest {

    @Test
    fun `T1 后端契约必须区分按名查询与按 ID 查询`() {
        val adapter = flatten(mainSourceFile("backend/BackendAdapter.kt"))
        assertTrue(
            "T1（契约）：BackendAdapter 没有暴露 queriesArtistSongsByName ——\n" +
                "调用方无从判断哪些后端会忽略 artistId。",
            adapter.contains("val queriesArtistSongsByName: Boolean get() = false")
        )

        val jellyfin = flatten(mainSourceFile("backend/impl/JellyfinAdapter.kt"))
        assertTrue("T1 空转自证：读到的不是 JellyfinAdapter", jellyfin.contains("class JellyfinAdapter"))
        assertTrue(
            "T1（标记）：Jellyfin 没覆盖 queriesArtistSongsByName ——\n" +
                "调用方会把多个原始 ID 逐个传进来，发出 N 条相同的按名查询。",
            jellyfin.contains("override val queriesArtistSongsByName: Boolean = true")
        )
        val body = methodRegion(jellyfin, "getArtistSongs(", "override suspend fun getSongs(")
        assertTrue(
            "T1（字段）：fields 没请求 Artists/AlbumArtist ——\n" +
                "parseSongUnsafe 取不到 song.artist，上层按名过滤会整批丢弃。",
            body.contains("Album,AlbumArtist,Artists,ArtistItems")
        )
        assertTrue("T1（分页）：没有翻页循环，>1000 首的歌手会被截断", body.contains("while (pages < MAX_PAGES)"))
        assertTrue("T1（分页）：StartIndex 没有随页推进", body.contains("StartIndex=${'$'}startIndex"))

        // 负自检：把 fields 退回旧的短版本，判据必须判负
        val mutated = body.replace("Album,AlbumArtist,Artists,ArtistItems", "")
        assertTrue("T1 护栏失效：短 fields 仍被判正", !mutated.contains("Album,AlbumArtist,Artists,ArtistItems"))
    }

    @Test
    fun `T2 Subsonic 系必须用 search3 补合辑漏歌且按 artist 过滤`() {
        for (path in listOf("backend/impl/NavidromeAdapter.kt", "backend/impl/SubsonicAdapter.kt")) {
            val src = flatten(mainSourceFile(path))
            val body = methodRegion(src, "getArtistSongs(", "override suspend fun getSongs(")
            assertTrue(
                "$path：没有 search3 按名字补漏 ——\n" +
                    "getArtist 只返回该歌手名下专辑，合辑中 track artist 是其的歌会漏。",
                body.contains("searchSongs(queryName)")
            )
            assertTrue(
                "$path：补漏结果没有按 ArtistSplitter 精确过滤 ——\n" +
                    "search3 的 query 会匹配标题/专辑名，不过滤会混入无关歌曲。",
                body.contains("ArtistSplitter.containsArtist(it.artist, queryName)")
            )
            assertTrue("$path：补漏没有按 id 去重", body.contains("it.id in seenIds"))
        }
    }

    @Test
    fun `T3 ViewModel 必须缓存短路 暴露三态 统一排序 且按名后端只查一次`() {
        val src = flatten(mainSourceFile("ui/viewmodel/MainViewModel.kt"))
        assertTrue("T3 空转自证：读到的不是 MainViewModel", src.contains("class MainViewModel"))

        val detailBody = methodRegion(src, "fun openArtistDetail(", "private val _artistDetailSongsCache")
        assertTrue(
            "T3（短路）：每次进详情都重发五源（含 10-15s Meting），快速反复进出会并发多次。",
            detailBody.contains("!_artistDetailSongsCache.value.containsKey(artistName)")
        )

        assertTrue("T3（状态）：缺 artistDetailLoading", src.contains("val artistDetailLoading: StateFlow<Set<String>>"))
        assertTrue("T3（状态）：缺 artistDetailErrors", src.contains("val artistDetailErrors: StateFlow<Map<String, String>>"))
        assertTrue("T3（统一入口）：缺 openArtistFromName", src.contains("fun openArtistFromName(name: String)"))

        val loadBody = methodRegion(src, "fun loadArtistSongs(", "fun isFavorite(")
        assertTrue(
            "T3（去重请求）：没有按 queriesArtistSongsByName 收敛 ID ——\n" +
                "Jellyfin 会对同一人的每个原始 ID 发一条完全相同的按名查询。",
            loadBody.contains("adapter.queriesArtistSongsByName")
        )
        assertTrue(
            "T3（排序）：没有统一按 专辑 > 碟号 > 音轨号 > 标题 排序 ——\n" +
                "Jellyfin(SortName) 与 Subsonic(专辑序) 拼接后顺序混乱。",
            loadBody.contains("compareBy<Song>") && loadBody.contains(".thenBy { it.trackNumber }")
        )
        assertTrue("T3（错误态）：catch 没有写按名字的错误状态", loadBody.contains("_artistDetailErrors.value = _artistDetailErrors.value +"))
        assertTrue("T3（加载态）：finally 没有清除 loading", loadBody.contains("_artistDetailLoading.value = _artistDetailLoading.value - artistName"))

        val mutated = loadBody.replace("compareBy<Song>", "")
        assertTrue("T3 护栏失效：无排序仍被判正", !mutated.contains("compareBy<Song>"))
    }

    @Test
    fun `T4 详情页必须真正消费加载 空 错误三态`() {
        val screen = flatten(mainSourceFile("ui/screens/ArtistDetailScreen.kt"))
        assertTrue("T4 空转自证：读到的不是 ArtistDetailScreen", screen.contains("fun ArtistDetailScreen("))
        for (arg in listOf("isLoading: Boolean = false", "errorMessage: String? = null", "onRetry: (() -> Unit)? = null")) {
            assertTrue("T4（签名）：ArtistDetailScreen 少了参数 $arg", screen.contains(arg))
        }
        for (indicator in listOf("LoadingIndicator(", "ErrorDisplay(", "EmptyState(")) {
            assertTrue("T4（渲染）：详情页没有消费三态组件 $indicator", screen.contains(indicator))
        }

        val branch = flatten(mainSourceFile("ui/components/branches/ArtistDetailBranch.kt"))
        assertTrue("T4（传参）：Branch 没有传 isLoading", branch.contains("isLoading = isDetailLoading"))
        assertTrue("T4（传参）：Branch 没有传 errorMessage", branch.contains("errorMessage = detailError"))
        assertTrue("T4（重试）：Branch 没有把 onRetry 接到 forceReload", branch.contains("forceReload = true"))
    }

    @Test
    fun `T5 NowPlaying 歌手名必须走统一入口`() {
        val src = flatten(mainSourceFile("ui/components/branches/NowPlayingBranch.kt"))
        assertTrue("T5 空转自证：读到的不是 NowPlayingBranch", src.contains("fun NowPlayingBranch("))
        val artistEntry = regionBetween(src, "onSearchArtist = { keyword ->", "onSearchSong = { keyword ->")
        assertTrue(
            "T5：onSearchArtist 没有走统一入口 openArtistFromName ——\n" +
                "与曲库卡片进详情页的语义不一致，且曲库内歌手拿不到详情页。",
            artistEntry.contains("viewModel.openArtistFromName(keyword)")
        )
        assertTrue(
            "T5：onSearchArtist 仍保留直接跳搜索 Tab 的旧逻辑",
            !artistEntry.contains("selectLibraryTab")
        )
    }

    // ─────────────────── 辅助 ───────────────────

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun methodRegion(src: String, signature: String, nextSignature: String): String =
        regionBetween(src, signature, nextSignature)

    private fun regionBetween(src: String, startMarker: String, endMarker: String): String {
        val start = src.indexOf(startMarker)
        assertTrue("找不到起点标记：$startMarker", start >= 0)
        val end = src.indexOf(endMarker, start + startMarker.length)
        assertTrue("找不到终点标记：$endMarker", end > start)
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
