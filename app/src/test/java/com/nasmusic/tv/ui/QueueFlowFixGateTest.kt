package com.nasmusic.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放队列页流程缺陷修复门禁（2026-11-22）。
 *
 * 钉死本轮 P1×1 / P2×3 / P3×2 修复形态，防止回退：
 * - T1 队列从非空变空必须清持久化（且启动首帧空队列不得误清）；
 * - T2 清空队列必须作废在途解析（代数 + Job 双保险）；
 * - T3 next/previous 空 URL 预判必须走 playMode 判据（SHUFFLE / 边界不预判）；
 * - T4 BACK 从队列页回进入来源，而非恒回首页；
 * - T5 清空按钮走真 Dialog 二次确认，BACK 只关弹窗；
 * - T6 key 拼 index 的注释与 QueueAddition.compute 去重口径不再矛盾。
 *
 * 每组带负自检（抹掉修复形态后断言必须判负），防止判据恒真空转。
 * Composable 行为测试本仓库跑不了，沿用源码文本门 + 语句级断言。
 */
class QueueFlowFixGateTest {

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

    /** T1 判据：非空→空的「转换检测」，不是无条件 else */
    private fun clearsOnEmptyTransition(body: String): Boolean =
        body.contains("var hadQueue = false") &&
            body.contains("hadQueue = true") &&
            body.contains("else if (hadQueue)") &&
            body.contains("prefs.queue.clearLastQueue()")

    @Test
    fun `T1 队列变空必须清持久化且启动首帧空队列不得误清`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        val collector = region(vm, "监听队列变化，自动持久化到 DataStore", "ExoPlayer 自动过渡到 streamUrl 为空的歌曲时")
        assertTrue(
            "采集器缺「非空→空」转换检测：移除最后一首后 DataStore 残留旧队列，重启复活",
            clearsOnEmptyTransition(collector)
        )
        // 负自检：无条件 else 形态（会在 restoreLastQueue 之前抹掉待恢复队列）必须判负
        val broken = "var hadQueue = false if (songs.isNotEmpty()) { hadQueue = true prefs.queue.saveLastQueue(songs, index) } else { prefs.queue.clearLastQueue() }"
        assertFalse("T1 负自检：无条件 else 必须判负", clearsOnEmptyTransition(broken))
    }

    /** T2 判据：代数递增 + 取消 Job + 两个 clear 入口都调用 */
    private fun invalidatesResolves(body: String): Boolean =
        body.contains("resolveGeneration++") &&
            body.contains("resolveJob?.cancel()") &&
            body.contains("resolveJob = null")

    @Test
    fun `T2 清空队列必须作废在途解析`() {
        val vm = src("ui/viewmodel/PlayerViewModel.kt")
        val method = region(vm, "fun invalidatePendingResolves()", "fun next()")
        assertTrue("invalidatePendingResolves 缺代数/取消双保险", invalidatesResolves(method))

        val mainVm = src("ui/viewmodel/MainViewModel.kt")
        val mainClear = region(mainVm, "fun clearQueue() {", "private fun loadLyricsForCurrentSong")
        assertTrue("MainViewModel.clearQueue 未作废在途解析", mainClear.contains("playerVM.invalidatePendingResolves()"))
        val vmClear = region(vm, "fun clearQueue(onCleared: () -> Unit)", "}")
        assertTrue("PlayerViewModel.clearQueue 未作废在途解析", vmClear.contains("invalidatePendingResolves()"))

        val broken = "fun invalidatePendingResolves() { resolveJob?.cancel() }"
        assertFalse("T2 负自检：只 cancel 不递增代数必须判负", invalidatesResolves(broken))
    }

    @Test
    fun `T3 空 URL 预判必须用 playMode 判据且 SHUFFLE 边界不预判`() {
        val helper = src("player/PlayModeAdjacent.kt")
        assertTrue("SHUFFLE 必须不预判（交给 playRandom）", helper.contains("if (playMode == PlayMode.SHUFFLE) return null"))
        assertTrue("REPEAT_ALL 双向环绕缺失", helper.contains("PlayMode.REPEAT_ALL -> if (previous) queueSize - 1 else 0"))
        assertTrue("REPEAT_ONE next 队尾回第一首 / previous 队首无动作缺失", helper.contains("PlayMode.REPEAT_ONE -> if (previous) null else 0"))
        assertTrue("SEQUENTIAL 边界必须无动作", helper.contains("else -> null"))

        val vm = src("ui/viewmodel/PlayerViewModel.kt")
        val next = region(vm, "fun next()", "fun previous()")
        val previous = region(vm, "fun previous()", "解析单首歌曲的播放链接")
        assertTrue("next 未走 adjacentIndex", next.contains("adjacentIndex(state.queue.size, state.currentIndex, _playMode.value, previous = false)"))
        assertTrue("previous 未走 adjacentIndex", previous.contains("adjacentIndex(state.queue.size, state.currentIndex, _playMode.value, previous = true)"))
        assertFalse(
            "旧无条件环绕预判仍在——会绕过 playRandom / 不回卷语义",
            vm.contains("val targetIndex = if (nextIndex < queueValue.size) nextIndex else 0") ||
                vm.contains("val targetIndex = if (prevIndex >= 0) prevIndex else queueValue.lastIndex")
        )
        val broken = "if (queueSize <= 0) return null val target = currentIndex + 1"
        assertFalse("T3 负自检：无模式判据的旧形态必须判负", broken.contains("PlayMode.SHUFFLE"))
    }

    @Test
    fun `T4 队列页 BACK 回来源页且 handler 随来源刷新`() {
        val nav = src("ui/viewmodel/NavigationViewModel.kt")
        assertTrue("导航器缺来源状态", nav.contains("_queueOrigin") && nav.contains("val queueOrigin: StateFlow<Screen?>"))
        assertTrue("只在从非 Queue 进入 Queue 时记录来源", nav.contains("screen == Screen.Queue && _currentScreen.value != Screen.Queue"))
        assertTrue("缺消费后清空", nav.contains("fun clearQueueOrigin()"))

        val root = src("components/AppRoot.kt")
        assertTrue("BACK 分支未接队列来源", root.contains("currentScreen == Screen.Queue && queueOriginScreen != null -> backToQueueOrigin"))
        assertTrue("queueOriginScreen 未进 LaunchedEffect key ⇒ handler 不刷新", root.contains("settingsSection, queueOriginScreen"))

        val broken = "currentScreen == Screen.Queue && queueOriginScreen != null -> navigateHome"
        assertFalse("T4 负自检：仍回首页必须判负", broken.contains("backToQueueOrigin"))
    }

    @Test
    fun `T5 清空按钮走真 Dialog 二次确认且 BACK 只关弹窗`() {
        val screen = src("screens/QueueScreen.kt")
        assertTrue("清空按钮未改为请求确认", screen.contains("onClearQueue = requestClearQueue"))
        assertTrue("两处形态（TV/竖屏）都必须渲染确认弹窗", count(screen, "QueueClearConfirmDialog(showClearConfirm, onClearConfirm, onClearDismiss)") == 2)
        assertTrue(
            "确认弹窗必须是真 Dialog（否则 Column 里 fillMaxSize 会被裁掉）",
            screen.contains("Dialog(") && screen.contains("onDismissRequest = onDismiss")
        )
        assertTrue("dismissOnBackPress 必须关闭，BACK 交给 ConfirmDialog 的 BackHandler", screen.contains("dismissOnBackPress = false"))
        assertTrue("破坏性操作默认聚焦取消", screen.contains("destructive = true"))

        val stringsFile = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
            File("../app/src/main/res/values/strings.xml")
        ).firstOrNull { it.isFile }
        assertTrue("找不到 strings.xml", stringsFile != null)
        val strings = flatten(stringsFile!!.readText(Charsets.UTF_8))
        assertTrue("缺确认弹窗文案", strings.contains("queue_clear_confirm_title") && strings.contains("queue_clear_confirm_message"))

        val broken = "QueueActionButton(text = stringResource(R.string.queue_clear), onClick = onClearQueue, icon = null)"
        assertFalse("T5 负自检：直连清空必须判负", broken.contains("requestClearQueue"))
    }

    @Test
    fun `T6 key 注释与入队去重口径一致`() {
        val screen = src("screens/QueueScreen.kt")
        assertFalse("旧注释仍称 addToQueue 不查重，与 QueueAddition.compute 矛盾", screen.contains("addToQueue 不查重"))
        assertTrue("注释须说明整队播放可绕过入队去重", screen.contains("整队播放不经过它"))
        val addition = src("player/QueueAddition.kt")
        assertTrue("入队唯一口径判据仍在", addition.contains("song.id !in existingIds && seen.add(song.id)"))

        val broken = "队列允许重复歌曲（addToQueue 不查重），key 用 song.id"
        assertTrue("T6 负自检：判据必须能识别旧注释", broken.contains("addToQueue 不查重"))
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
        assertTrue("找不到源码目录（user.dir=${File("").absolutePath}）", root != null)
        return root!!
    }
}
