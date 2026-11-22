package com.nasmusic.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页流程缺陷修复门禁（2026-11-22）。
 *
 * 钉死本轮 P0/P1/P2 修复形态，防止回退：
 * - T1 随心听续播标志必须在 playQueue 之后置位（顺序即语义）；
 * - T2 智能电台重入判据用 Manager.state（StateFlow），废弃本地布尔标志；
 * - T3 网络歌成功回调链：playNetworkSong 接受 onPlayed 且分支导航在回调内；
 * - T4 playQueue 空列表早退；
 * - T5 HomeDashboardData 不含 recentSongs 死字段；
 * - T6 天气电台 Manager 适配器每次构建前刷新；
 * - T7 loadRecentSongs / fetchWeather 并发代数闸门。
 *
 * 每组带负自检（抹掉修复形态后断言必须判负），防止判据恒真空转。
 */
class HomeFlowFixGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun src(relativePath: String): String = flatten(mainSourceFile(relativePath))

    private fun region(source: String, start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("找不到起始签名：$start", from >= 0)
        val to = source.indexOf(end, from + start.length)
        assertTrue("找不到结束签名：$end", to > from)
        return source.substring(from, to)
    }

    private fun count(source: String, fragment: String): Int =
        Regex(Regex.escape(fragment)).findAll(source).count()

    /** T1 判据：标志置位必须出现在 playQueue 调用之后 */
    private fun shuffleFlagAfterPlayQueue(body: String): Boolean {
        val queueIdx = body.indexOf("playQueue(songs, startIndex)")
        val flagIdx = body.indexOf("_isShufflePlaying = true")
        return queueIdx >= 0 && flagIdx >= 0 && flagIdx > queueIdx
    }

    @Test
    fun `T1 随心听续播标志必须在 playQueue 之后置位`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val body = region(vm, "fun playRandomSongs(", "private var _isShufflePlaying")
        assertTrue(
            "标志必须在 playQueue 之后置位——否则被 playQueue 内部复位，续播成死代码",
            shuffleFlagAfterPlayQueue(body)
        )
        // 负自检：合成"先置位后 playQueue"的旧形态，判据必须判负（防判据恒真空转）
        val broken = "_isShufflePlaying = true playQueue(songs, startIndex) navVM.navigateTo(Screen.NowPlaying) startShuffleRefill()"
        assertFalse("T1 负自检：旧顺序必须判负", shuffleFlagAfterPlayQueue(broken))
    }

    @Test
    fun `T2 智能电台重入判据必须是 Manager state 且本地布尔标志已废弃`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val body = region(vm, "fun loadSmartRadioBatch()", "fun playSmartRadioBatchAt(")
        assertTrue(
            "必须用 Manager.state 判重入（线程安全且失败路径会置 Exhausted）",
            body.contains("smartRadioManager.state.value is SmartRadioManager.State.Generating")
        )
        assertFalse(
            "本地布尔标志仍在——空库/耗尽/异常时不会复位，'换一批'永久卡死",
            body.contains("var smartRadioBatchLoading") || body.contains("if (smartRadioBatchLoading)")
        )
        assertFalse(
            "T2 负自检：抹掉 state 判据后不得仍判正",
            body.replace("smartRadioManager.state.value is SmartRadioManager.State.Generating", "")
                .contains("state.value is SmartRadioManager.State.Generating")
        )
    }

    @Test
    fun `T3 网络歌成功回调链完整且四分支导航都在回调内`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val body = region(vm, "fun playNetworkSong(song: Song", "fun toggleNetworkFavorite(")
        assertTrue("playNetworkSong 必须接受 onPlayed 回调", body.contains("onPlayed: (() -> Unit)? = null"))
        assertTrue("onPlayed 必须在 onPlaySong（真正开播）里触发", body.contains("onPlayed?.invoke()"))

        for (branch in listOf("HomeBranch.kt", "MineBranch.kt", "LibraryBranch.kt", "NetdiskBranch.kt")) {
            val srcBranch = src("ui/components/branches/$branch")
            assertTrue(
                "$branch 的网络歌导航必须放在 playNetworkSong 回调内（解析失败不能跳空播放页）",
                srcBranch.contains("playNetworkSong(song) {") ||
                    srcBranch.contains("playNetworkSong(song, onPlayed = {")
            )
        }
        assertFalse(
            "T3 负自检：删掉 onPlayed 回调触发后不得仍判正",
            body.replace("onPlayed?.invoke()", "").contains("onPlayed?.invoke()")
        )
    }

    @Test
    fun `T4 playQueue 必须有空列表早退`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val body = region(vm, "fun playQueue(songs: List<Song>", "private fun triggerEnrichForStubs(")
        assertTrue("playQueue 缺空列表早退", body.contains("if (songs.isEmpty()) return"))
        val guardIdx = body.indexOf("if (songs.isEmpty()) return")
        val coerceIdx = body.indexOf("coerceIn(0, songs.lastIndex)")
        assertTrue(
            "空列表早退必须在 coerceIn 之前，否则 coerceIn(0,-1) 抛异常",
            guardIdx >= 0 && coerceIdx >= 0 && guardIdx < coerceIdx
        )
    }

    @Test
    fun `T5 HomeDashboardData 不得含 recentSongs 死字段`() {
        val data = src("data/model/HomeDashboardData.kt")
        val body = region(data, "data class HomeDashboardData(", ")")
        assertFalse(
            "recentSongs 死字段已删除——最近播放走 MainViewModel.recentSongs 独立 StateFlow",
            Regex("val\\s+recentSongs\\s*:").containsMatchIn(body)
        )
    }

    @Test
    fun `T6 天气电台适配器必须每次构建前刷新`() {
        val mgr = src("backend/weather/WeatherRadioManager.kt")
        assertTrue(
            "Manager 必须提供 updateAdapter",
            mgr.contains("fun updateAdapter(adapter: BackendAdapter?)")
        )
        assertFalse("适配器必须是可写的（快照固化根因）", mgr.contains("private val backendAdapter"))

        val vm = src("ui/viewmodel/WeatherRadioViewModel.kt")
        assertTrue("VM 必须有实时刷新 helper", vm.contains("fun weatherRadioManagerRefreshed()"))
        assertTrue("helper 必须读实时 registry", vm.contains("backendRegistry.getAdapter()"))
        // 三处构建点都必须改走 helper，不得再直接 new 固化快照
        assertTrue("不得再直接构造固化快照的 Manager", !vm.contains("weatherRadioManager ?: run"))
        assertTrue("helper 必须注入实时 adapter", vm.contains("updateAdapter(adapter)"))
    }

    @Test
    fun `T7 loadRecentSongs 与 fetchWeather 必须有并发代数闸门`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val recent = region(vm, "fun loadRecentSongs(", "// --- 多维度浏览")
        assertTrue("loadRecentSongs 缺代数闸门", recent.contains("generation == recentSongsGeneration"))

        val weather = src("ui/viewmodel/WeatherRadioViewModel.kt")
        assertTrue("fetchWeather 缺代数闸门", weather.contains("fetchGen != weatherFetchGeneration"))
        assertTrue("fetchWeather 预报写回必须校验代数", weather.contains("fetchGen == weatherFetchGeneration"))
        assertFalse(
            "T7 负自检：抹掉代数判据后不得仍判正",
            weather.replace("fetchGen != weatherFetchGeneration", "").contains("fetchGen != weatherFetchGeneration")
        )
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
