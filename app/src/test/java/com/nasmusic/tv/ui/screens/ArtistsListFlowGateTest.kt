package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ARTISTS Tab「列出艺术家」链路正确性门禁。
 *
 * 背景：这条链路曾有六处断链/口径问题——
 * 1. `LibraryBranch` 收集了 `artistsState` 却只取 `dataOrNull()`（且结果没人用），
 *    `UiState.Loading/Error` 与 UI 完全断连；
 * 2. `LibraryScreen` 的骨架屏只看 songs 的 `isLoading`，艺术家请求期间不显示；
 * 3. ARTISTS 空态没传 `errorText/onRetry`，`UiState.Error.retry` 无人消费；
 * 4. 适配器 `getArtists()` 吞异常返回 `emptyList()`，失败伪装成「曲库没有艺术家」；
 * 5. 卡片曲目数取 `artistSongsMap`（只有 NAS 已分页歌曲），本地/百度艺术家恒显 0；
 * 6. 渲染层按 `id` 去重与上游 `normalizeKey` 口径不一致；A-Z 侧栏只在首个可见项
 *    恰好是 header 时才高亮。
 *
 * UI Composable 的行为测试本仓库跑不了（见 SearchPagingWiringGateTest 的说明），
 * 故沿用同款源码文本门：手动逐行读无意义的重复检查交给门，且门内带负自检，
 * 防止判据恒真。读文件显式 UTF-8（源码含中文注释，本机 JVM 默认 CP936）。
 */
class ArtistsListFlowGateTest {

    private val libraryBranchPath = "ui/components/branches/LibraryBranch.kt"
    private val libraryScreenPath = "ui/screens/LibraryScreen.kt"
    private val artistListPath = "ui/screens/library/browse/ArtistList.kt"
    private val mainViewModelPath = "ui/viewmodel/MainViewModel.kt"

    @Test
    fun `T1 LibraryBranch 必须消费 artists 的 UiState 并把加载与错误传给 LibraryScreen`() {
        val src = flatten(mainSourceFile(libraryBranchPath))
        assertTrue("T1 空转自证：读到的不是 LibraryBranch", src.contains("fun LibraryBranch("))
        assertTrue(
            "T1（① 收集）：artistsState 的初始值不该是 Success(emptyList()) ——\n" +
                "那会让 artists 的 Loading 态从一开始就不存在，骨架屏永远不出现。",
            src.contains("viewModel.artists.collectAsState(initial = UiState.Loading as UiState<List<Artist>>)")
        )
        assertTrue(
            "T1（② 派生）：没有从 artistsState 派生 isArtistsLoading ——\n" +
                "songs 的 isLoading/isLibraryLoading 在艺术家请求期间都是 false。",
            src.contains("val isArtistsLoading = artistsState is UiState.Loading")
        )
        assertTrue(
            "T1（③ 派生）：没有从 artistsState 派生错误闭包 —— retry 会无人消费。",
            src.contains("val artistError = artistsState as? UiState.Error")
        )
        for (arg in listOf(
            "isArtistsLoading = isArtistsLoading,",
            "artistErrorText = artistError?.message,",
            "onRetryArtistsLoad = artistError?.retry ?: { viewModel.loadArtists() },"
        )) {
            assertTrue("T1（④ 传参）：LibraryBranch → LibraryScreen 少了实参：$arg", src.contains(arg))
        }
    }

    @Test
    fun `T2 LibraryScreen 必须让 ARTISTS 骨架屏与错误空态真正生效`() {
        val src = flatten(mainSourceFile(libraryScreenPath))
        assertTrue("T2 空转自证：读到的不是 LibraryScreen", src.contains("fun LibraryScreen("))
        assertTrue(
            "T2（骨架屏）：加载分支没带 isArtistsLoading ——\n" +
                "艺术家请求期间仍然只显示空态。",
            src.contains("targetTab == LibraryTab.ARTISTS && isArtistsLoading")
        )
        assertTrue(
            "T2（错误态）：ARTISTS 空态没有消费 artistErrorText ——\n" +
                "失败与「真没艺术家」无法区分，用户无从反馈。",
            src.contains("tab = LibraryTab.ARTISTS, errorText = artistErrorText, onRetry = onRetryArtistsLoad,")
        )
        assertTrue(
            "T2（错误文案）：ARTISTS 空态没用艺术家专用文案 ——\n" +
                "会复用 EmptyHint 默认的「歌曲加载失败」。",
            src.contains("errorTitleRes = R.string.library_artists_load_failed")
        )
    }

    @Test
    fun `T3 loadArtists 关 NAS 时必须落 Success 且失败用艺术家专用文案`() {
        val src = flatten(mainSourceFile(mainViewModelPath))
        val body = methodRegion(src, "fun loadArtists()", "fun loadYears()")
        assertTrue("T3 空转自证：没抽到 loadArtists 方法体", body.contains("_artists.value = UiState.Loading"))
        assertTrue(
            "T3（关源）：NAS 关闭时没有把 _artists 落为 Success(emptyList()) ——\n" +
                "它会永远停在 Loading，骨架屏不再消失。",
            body.contains("_artists.value = UiState.Success(emptyList())")
        )
        assertTrue(
            "T3（文案）：错误文案还是「加载收藏失败」——艺术家失败会说错对象。",
            body.contains("R.string.library_artists_load_failed")
        )
    }

    @Test
    fun `T4 五个后端的 getArtists 失败都必须抛出而不是吞成空列表`() {
        val targets = listOf(
            "backend/impl/JellyfinAdapter.kt" to "throw e",
            "backend/impl/NavidromeAdapter.kt" to "throw e",
            "backend/impl/SubsonicAdapter.kt" to "throw e",
            "backend/impl/DaoliyuAdapter.kt" to "throw e",
            "backend/impl/FeiniuAdapter.kt" to "getOrThrow()"
        )
        for ((path, rethrow) in targets) {
            val src = flatten(mainSourceFile(path))
            val body = methodRegion(src, "getArtists(): List<Artist>", "getArtistSongs(")
            assertTrue("$path：getArtists 里没有把失败抛给上层（缺 $rethrow）", body.contains(rethrow))
            assertTrue(
                "$path：getArtists 的空响应仍被当成正常空列表（缺 throw IllegalStateException）",
                body.contains("throw IllegalStateException")
            )
        }
        // 负自检：把 Jellyfin 的 rethrow 改回吞异常形态，判据必须判负
        val jellyfin = flatten(mainSourceFile(targets[0].first))
        val mutated = methodRegion(jellyfin, "getArtists(): List<Artist>", "getArtistSongs(")
            .replace("throw e", "emptyList()")
        assertTrue("T4 护栏失效：吞异常形态仍被判正", !mutated.contains("throw e"))
    }

    @Test
    fun `T5 渲染层去重口径与 A-Z 高亮取值必须正确`() {
        val src = flatten(mainSourceFile(artistListPath))
        assertTrue(
            "T5（去重）：渲染层又退回按 id 去重 ——\n" +
                "挡不住「同一人来自 NAS / 本地 / 百度且 id 不同」的重复。",
            src.contains("artists.distinctBy { ArtistSplitter.normalizeKey(it.name) }")
        )
        assertTrue(
            "T5（高亮）：侧栏高亮只看第一个可见 item ——\n" +
                "滚到分组中部 header 划出屏幕后高亮会消失。",
            src.contains(".lastOrNull()")
        )
        assertTrue(
            "T5（计数）：卡片曲目数又退回 artistSongsMap.size ——\n" +
                "该 map 只含 NAS 已分页歌曲，本地/百度艺术家恒显 0。",
            src.contains("val songCount = artist.songCount")
        )
    }

    @Test
    fun `T6 Jellyfin 必须解析专辑数供艺术家卡片展示`() {
        val src = flatten(mainSourceFile("backend/impl/JellyfinAdapter.kt"))
        val body = methodRegion(src, "getArtists(): List<Artist>", "getArtistSongs(")
        assertTrue(
            "T6：Jellyfin 构造 Artist 时没传 albumCount ——\n" +
                "该源的艺术家卡片专辑数恒为 0，与 Navidrome/Subsonic 不一致。",
            body.contains("albumCount = albumCount")
        )
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
