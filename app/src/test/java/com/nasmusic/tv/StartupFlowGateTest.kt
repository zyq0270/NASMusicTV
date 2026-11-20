package com.nasmusic.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动流程六项缺陷修复门禁（v2.39.23，2026-11-20）。
 *
 * 1. saved-config 提示在手动连接 / 网络恢复静默重连两条路径都未关闭 ⇒ 已连上仍弹窗、
 *    点确认重复 initialize。
 * 2. 启动期 API 版本聚合跑两遍（MainViewModel init + 接线区）。
 * 3. playMode.collect 重复订阅同一个普通 var。
 * 4. NasMusicApp.onCreate 三次串行 runBlocking 读 DataStore。
 * 5. MainViewModel 读 serverConfig 后是空 if 块的死代码。
 * 6. PlaybackService serviceScope 无异常处理器、sleepTimer 收集无 try/catch、
 *    startForeground 失败仅 WARN 不留 stopSelf。
 *
 * 沿用源码文本门 + 负自检（先断言读到目标文件，再断言语句存在，防判据恒真）。
 */
class StartupFlowGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

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

    private fun source(relativePath: String): String {
        val file = sourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun count(text: String, token: String): Int =
        if (token.isEmpty()) 0 else text.windowed(token.length).count { it == token }

    @Test
    fun `T1 手动连接与静默重连都必须关闭启动连接提示`() {
        val src = flatten(source("ui/viewmodel/ServerViewModel.kt"))
        assertTrue("T1 空转自证：读到的不是 ServerViewModel", src.contains("class ServerViewModel("))
        assertTrue("T1（提示源）：checkSavedConfigOnStart 不再置位。", src.contains("_showConnectPrompt.value = true"))

        val manual = src.substringAfter("suspend fun connectToServer(")
            .substringBefore("fun disconnect(")
        assertTrue(
            "T1（手动连接）：connectToServer 未关闭连接提示。",
            manual.contains("_showConnectPrompt.value = false")
        )

        val saved = src.substringAfter("fun connectToSavedServer(")
        assertTrue(
            "T1（静默重连）：connectToSavedServer 未无条件关闭连接提示。",
            saved.contains("_showConnectPrompt.value = false") &&
                !saved.contains("if (!silent) { _showConnectPrompt.value = false }")
        )
    }

    @Test
    fun `T2 API 聚合与 playMode 同步各只有一处且空 if 死代码已删`() {
        val raw = source("ui/viewmodel/MainViewModel.kt")
        val src = flatten(raw)
        assertTrue("T2 空转自证：读到的不是 MainViewModel", src.contains("class MainViewModel("))
        assertTrue(
            "T2（重复订阅）：playMode.collect 应只剩接线区一处，实际 ${count(src, "playerVM.playMode.collect")} 处。",
            count(src, "playerVM.playMode.collect") == 1
        )
        assertTrue(
            "T2（重复网络）：启动期 refreshApiVersionsAsync 应只有接线区一条语句（注释提及不算）。",
            Regex("""(?m)^\s*serverVM\.refreshApiVersionsAsync\(\)""").findAll(raw).count() == 1
        )
        assertTrue(
            "T2（重复网络回退）：init 区仍留有 withContext(IO) { refreshApiVersions() }。",
            !src.contains("withContext(Dispatchers.IO) { refreshApiVersions() }")
        )
        assertTrue(
            "T2（死代码）：读后不用的 serverConfig.first() 空 if 块仍在。",
            !src.contains("val config = prefs.serverConfig.first()")
        )
    }

    @Test
    fun `T3 冷启动只读一次合并快照`() {
        val src = flatten(source("NasMusicApp.kt"))
        assertTrue("T3 空转自证：读到的不是 NasMusicApp", src.contains("class NasMusicApp"))
        assertTrue(
            "T3（合并读）：未改用 appPreferences.readBootSnapshot()。",
            src.contains("runBlocking { appPreferences.readBootSnapshot() }")
        )
        assertTrue(
            "T3（残留）：appSettings / baiduConfigFlow 仍有独立 runBlocking 读。",
            !src.contains("runBlocking { appPreferences.appSettings.first() }") &&
                !src.contains("runBlocking { appPreferences.baidu.baiduConfigFlow.first() }")
        )
        val blockingCount = count(src, "runBlocking {")
        assertTrue(
            "T3（次数）：runBlocking 应只剩合并读一次，实际 $blockingCount 次。",
            blockingCount == 1
        )
    }

    @Test
    fun `T4 BootSnapshot 保留事实源口径且合并三条 Flow`() {
        val src = flatten(source("data/prefs/AppPreferences.kt"))
        assertTrue("T4 空转自证：读到的不是 AppPreferences", src.contains("class AppPreferences internal constructor"))
        assertTrue("T4（快照类型）：BootSnapshot 未定义。", src.contains("data class BootSnapshot("))
        assertTrue("T4（入口）：readBootSnapshot() 未定义。", src.contains("suspend fun readBootSnapshot()"))
        assertTrue("T4（合并）：未用 combine 收敛三条 Flow。", src.contains("kotlinx.coroutines.flow.combine("))
        assertTrue("T4（百度口径）：百度开关未从 baiduConfig.isActive 派生。", src.contains("baiduConfig.isActive"))
    }

    @Test
    fun `T5 播放服务协程与前台化失败有兜底`() {
        val src = flatten(source("player/PlaybackService.kt"))
        assertTrue("T5 空转自证：读到的不是 PlaybackService", src.contains("class PlaybackService"))
        assertTrue("T5（scope）：serviceScope 未装 CoroutineExceptionHandler。", src.contains("CoroutineExceptionHandler"))
        assertTrue(
            "T5（scope 日志）：未捕获异常没有 E 级留痕。",
            src.contains("serviceScope 未捕获异常")
        )
        assertTrue(
            "T5（sleepTimer）：定时状态收集仍无 try/catch 兜底。",
            src.contains("try { (application as NasMusicApp).playerManager.sleepTimer.state.collect") &&
                src.contains("sleepTimer state collect failed")
        )
        assertTrue(
            "T5（前台化）：startForeground 失败未 stopSelf，仍可能拖到 5 秒超时 ANR。",
            src.contains("stopSelf(startId)")
        )
    }

    @Test
    fun `T6 自动重连与提示链路仍保留`() {
        val vm = flatten(source("ui/viewmodel/MainViewModel.kt"))
        val monitor = flatten(source("util/NetworkMonitor.kt"))
        assertTrue(
            "T6（自动重连）：MainViewModel 的 silent 重连入口被误删。",
            vm.contains("connectToSavedServer(silent = true)")
        )
        assertTrue(
            "T6（网络回调）：NetworkMonitor 的去抖 onAvailable 被误删。",
            monitor.contains("if (!lastHasInternet)") && monitor.contains("onNetworkAvailable()")
        )
    }
}
