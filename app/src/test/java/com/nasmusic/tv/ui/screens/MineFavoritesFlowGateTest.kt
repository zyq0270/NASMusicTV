package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「我的」页歌单/收藏链路三项缺陷修复门禁。
 *
 * 背景（2026-11-20 流程分析，所有者裁定"保持单后端，只修 3 个真缺陷"）：
 * 1. NAS 服务端歌单页 `PlaylistManagementScreen` 有渲染分支却**无任何导航入口**——
 *    5 个后端 getPlaylists() 全实现了，用户永远看不到；且 loadPlaylists() 只在重开 NAS 源时调用，
 *    首次进入页面即便可达也是空列表。
 * 2. `NetworkFavoriteItem` 不持久化 `networkPlatform`，跨平台收藏切换全局平台后按错平台解析直链。
 * 3. `networkFavoriteSongs/Ids` 未按 sourceVisibility 过滤，关源后收藏仍留在「我的」页，
 *    与 NAS 收藏口径不一致。
 *
 * UI Composable 行为测试本仓库跑不了（见 ArtistsListFlowGateTest 说明），沿用源码文本门 +
 * 负自检（先断言读到的确是目标文件，再断言关键语句存在，防判据恒真）。
 */
class MineFavoritesFlowGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(relativePath: String): String {
        val file = sourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到源码目录；已尝试：${candidates.joinToString { it.absolutePath }}", root != null)
        return root!!
    }

    private fun resRoot(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到资源目录；已尝试：${candidates.joinToString { it.absolutePath }}", root != null)
        return root!!
    }

    // ── 缺陷 1：服务端歌单页可达 + 进入即加载 + 返回回「我的」 ──

    @Test
    fun `T1 MineScreen 必须有服务端歌单入口回调并在两种布局都渲染按钮`() {
        val src = flatten(source("ui/screens/MineScreen.kt"))
        assertTrue("T1 空转自证：读到的不是 MineScreen", src.contains("fun MineScreen("))
        assertTrue(
            "T1（回调声明）：MineScreen 缺 onOpenServerPlaylists 参数——无法从「我的」页进服务端歌单页。",
            src.contains("onOpenServerPlaylists: () -> Unit")
        )
        // 手机竖屏标题行 + TV 横屏 PlaylistsPane 两处都必须有入口按钮
        assertTrue(
            "T1（手机布局）：竖屏歌单标题行未绑定 onOpenServerPlaylists。",
            src.contains("onClick = onOpenServerPlaylists")
        )
        assertTrue(
            "T1（TV 布局）：PlaylistsPane 未接收并渲染 onOpenServerPlaylists。",
            src.contains("onOpenServerPlaylists = onOpenServerPlaylists") &&
                src.contains("onClick = onOpenServerPlaylists")
        )
        assertTrue(
            "T1（字符串）：入口按钮文案资源 mine_server_playlists 未使用。",
            src.contains("R.string.mine_server_playlists")
        )
    }

    @Test
    fun `T2 MineBranch 入口必须拉歌单并导航 PlaylistManagement`() {
        val src = flatten(source("ui/components/branches/MineBranch.kt"))
        assertTrue("T2 空转自证：读到的不是 MineBranch", src.contains("fun MineBranch("))
        assertTrue(
            "T2（入口回调）：MineBranch 未传 onOpenServerPlaylists。",
            src.contains("onOpenServerPlaylists = {")
        )
        // G8-06：拉数据统一由 PlaylistManagementBranch 的 LaunchedEffect 负责；
        // ⛔ MineBranch 不得再直接调 —— 两处都调会发两个相同请求，第二次的 Loading
        // 还会把已到的列表瞬时压成「暂无歌单」。
        assertTrue(
            "T2（重复拉取）：MineBranch 不应再直接 loadPlaylists（分支已负责）。",
            !src.contains("viewModel.loadPlaylists()")
        )
        assertTrue(
            "T2（导航）：未跳 Screen.PlaylistManagement ⇒ 服务端歌单页仍不可达。",
            src.contains("navigateTo(Screen.PlaylistManagement)")
        )
    }

    @Test
    fun `T3 PlaylistManagement 分支进入即加载且返回回我的页`() {
        val src = flatten(source("ui/components/branches/PlaylistManagementBranch.kt"))
        assertTrue("T3 空转自证：读到的不是 PlaylistManagementBranch", src.contains("fun PlaylistManagementBranch("))
        assertTrue(
            "T3（进入加载）：分支未 LaunchedEffect 调 loadPlaylists。",
            src.contains("LaunchedEffect(Unit) { viewModel.loadPlaylists() }")
        )
        assertTrue(
            "T3（返回导航）：onBack 仍回 Library —— 入口已改在「我的」页，须回 Mine。",
            src.contains("onBack = { viewModel.navVM.navigateTo(Screen.Mine) }")
        )
        val appRoot = flatten(source("ui/components/AppRoot.kt"))
        assertTrue(
            "T3（系统 BACK）：AppRoot 未把 PlaylistManagement 的 BACK 导向「我的」页。",
            appRoot.contains("currentScreen == Screen.PlaylistManagement -> navigateMine")
        )
    }

    @Test
    fun `T4 中英字符串资源都有 mine_server_playlists`() {
        for (locale in listOf("values", "values-en")) {
            val text = resRoot().resolve("$locale/strings.xml").readText(Charsets.UTF_8)
            assertTrue(
                "$locale/strings.xml 缺 mine_server_playlists（中英须同步）。",
                text.contains("name=\"mine_server_playlists\"")
            )
        }
    }

    // ── 缺陷 2：networkPlatform 端到端持久化 ──

    @Test
    fun `T5 NetworkFavoriteItem 含可空 networkPlatform 字段`() {
        val src = flatten(source("data/model/NetworkFavoriteItem.kt"))
        assertTrue("T5 空转自证：读到的不是 NetworkFavoriteItem", src.contains("data class NetworkFavoriteItem("))
        assertTrue(
            "T5（字段）：缺 networkPlatform，收藏时无法存平台快照。",
            src.contains("val networkPlatform: String? = null")
        )
    }

    @Test
    fun `T6 收藏写入与派生回填 networkPlatform`() {
        val netVM = flatten(source("ui/viewmodel/NetworkMusicViewModel.kt"))
        assertTrue(
            "T6（写入）：toggleNetworkFavorite 未存 song.networkPlatform。",
            netVM.contains("networkPlatform = song.networkPlatform")
        )
        val mainVM = flatten(source("ui/viewmodel/MainViewModel.kt"))
        assertTrue(
            "T6（回填）：networkFavoriteSongs 派生 Song 未回填 item.networkPlatform ⇒ 回放平台快照丢失。",
            mainVM.contains("networkPlatform = item.networkPlatform")
        )
    }

    @Test
    fun `T7 Meting 取链与取词按歌曲平台快照优先`() {
        val src = source("backend/network/MetingApiService.kt")
        // 语句级断言（注释里出现同一字面量不算）
        val resolve = src.substring(
            src.indexOf("override suspend fun resolvePlayUrlDetailed"),
            src.indexOf("override suspend fun resolveLyrics")
        ).let { flatten(it) }
        val lyrics = src.substring(src.indexOf("override suspend fun resolveLyrics")).let { flatten(it) }
        assertTrue("T7 空转自证：找不到 resolvePlayUrlDetailed", resolve.contains("netId"))
        assertTrue(
            "T7（取链）：resolvePlayUrlDetailed 未优先用 song.networkPlatform ⇒ 跨平台收藏回放仍失败。",
            resolve.contains("val server = song.networkPlatform ?: serverProvider()")
        )
        assertTrue(
            "T7（取词）：resolveLyrics 未同口径按平台快照取词。",
            lyrics.contains("val server = song.networkPlatform ?: serverProvider()")
        )
    }

    // ── 缺陷 3：网络收藏按源可见性过滤 ──

    @Test
    fun `T8 networkFavoriteSongs 挂两个重算信号并按可见性过滤`() {
        val src = flatten(source("ui/viewmodel/MainViewModel.kt"))
        assertTrue(
            "T8（重算信号）：未 combine _sourceRevision / _localMusicEnabled ⇒ 开关翻转后派生流不重算。",
            src.contains("combine(netVM.networkFavorites, _sourceRevision, _localMusicEnabled")
        )
        assertTrue(
            "T8（可见性过滤）：未用 sourceVisibility.isVisible 过滤收藏项 ⇒ 关源后收藏仍在「我的」页。",
            src.contains("song.takeIf { sourceVisibility.isVisible(it) }")
        )
        assertTrue(
            "T8（ID 口径）：networkFavoriteIds 未从过滤后的 networkFavoriteSongs 派生 ⇒ 展示与心形判定漂移。",
            src.contains("val networkFavoriteIds: StateFlow<Set<String>> = networkFavoriteSongs.map")
        )
    }
}
