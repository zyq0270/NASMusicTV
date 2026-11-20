package com.nasmusic.tv.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放窗口点击播放/暂停链路正确性门禁（v2.39.24）。
 *
 * 本轮四项缺陷：
 * 1. playPause() 用 isPlaying 判据 —— BUFFERING 时 isPlaying=false，二次点击再次 play()，缓冲中无法暂停；
 * 2. 播放按钮图标读 isPlaying —— 与判据同源但该源在缓冲期失真；需改用 playWhenReady 意图态；
 * 3. playPause() 未检查 suppressPlayback —— MTV 模式媒体键可能恢复主播放器造成混音；
 * 4. KEYCODE_MEDIA_STOP 映射到 playPause —— STOP 语义应是停止并回曲首。
 *
 * 沿用仓库源码文本门禁惯例（UI/ExoPlayer 行为测试本机跑不了）：门内带负自检，
 * 防止判据恒真。读文件显式 UTF-8（源码含中文注释）。
 */
class PlayPauseFlowGateTest {

    private val managerPath = "player/PlayerManager.kt"
    private val vmPath = "ui/viewmodel/PlayerViewModel.kt"
    private val keyHandlerPath = "util/MediaKeyHandler.kt"
    private val appRootPath = "ui/components/AppRoot.kt"
    private val stringsPath = "values/strings.xml"

    @Test
    fun `T1 playPause 判据必须是用户意图 playWhenReady 而非 isPlaying`() {
        val region = methodRegion(
            flatten(mainSourceFile(managerPath)),
            "fun playPause(): Boolean",
            "fun isPlayerReady()"
        )
        assertTrue("T1 空转自证：未定位到 playPause 方法体", region.contains("playWhenReady"))
        assertTrue(
            "T1：判据必须取 playWhenReady（用户意图），否则 BUFFERING 时无法暂停。",
            region.contains("val shouldPause = p.playWhenReady")
        )
        assertTrue(
            "T1：不得用 p.isPlaying 作为 toggle 判据（日志里打印可，赋值判据不可）。",
            !region.contains("val wasPlaying = p.isPlaying") &&
                !region.contains("val shouldPause = p.isPlaying")
        )
        // 负自检：把判据改回 isPlaying 后必须判红
        val mutated = region.replace("val shouldPause = p.playWhenReady", "val shouldPause = p.isPlaying")
        assertTrue("T1 护栏失效：判据换成 isPlaying 仍被判正", !mutated.contains("val shouldPause = p.playWhenReady"))
    }

    @Test
    fun `T2 playPause 必须检查 suppressPlayback 且 player 空时返回 false`() {
        val region = methodRegion(
            flatten(mainSourceFile(managerPath)),
            "fun playPause(): Boolean",
            "fun isPlayerReady()"
        )
        assertTrue(
            "T2（MTV 防混音）：playPause 缺 suppressPlayback 护栏。",
            region.contains("if (suppressPlayback)") && region.contains("return false")
        )
        assertTrue(
            "T2（空反馈）：player 为空时必须返回 false 供上层提示，不能 player?.let 静默。",
            region.contains("val p = player ?: run") && region.contains("return false")
        )
    }

    @Test
    fun `T3 图标真相源 wantsToPlay 必须由 onPlayWhenReadyChanged 驱动`() {
        val src = flatten(mainSourceFile(managerPath))
        assertTrue("T3 空转自证：未找到 wantsToPlay 状态流", src.contains("val wantsToPlay: StateFlow<Boolean> = _wantsToPlay"))
        assertTrue(
            "T3：wantsToPlay 必须由 onPlayWhenReadyChanged 写，不能用 isPlaying 回调写。",
            src.contains("override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int)") &&
                src.contains("_wantsToPlay.value = playWhenReady")
        )
        assertTrue(
            "T3：isPlaying 回调不得再写 _wantsToPlay（否则缓冲期图标再次失真）。",
            !src.contains("_wantsToPlay.value = isPlaying")
        )
        assertTrue(
            "T3：isPlaying 仍需保留供真实出声消费者（可视化动画 / MainViewModel.isPlaying()）。",
            src.contains("_isPlaying.value = isPlaying")
        )
    }

    @Test
    fun `T4 UI 按钮消费者必须用 wantsToPlay，可视化动画保留 isPlaying`() {
        val src = flatten(mainSourceFile(appRootPath))
        assertTrue(
            "T4：AppRoot 必须收集 wantsToPlay。",
            src.contains("viewModel.playerVM.wantsToPlay.collectAsState")
        )
        // 三个按钮/图标消费者须传 wantsToPlay
        assertTrue("T4：NowPlayingBranch 图标未用 wantsToPlay", src.contains("isPlaying = wantsToPlay,"))
        assertTrue(
            "T4：VisualizerOverlay 必须保留真实出声态 isPlaying（动画需要）。",
            src.contains("VisualizerOverlay(") && src.contains("isPlaying = isPlaying")
        )
        val vm = flatten(mainSourceFile(vmPath))
        assertTrue("T4：PlayerViewModel 必须暴露 wantsToPlay", vm.contains("val wantsToPlay: StateFlow<Boolean> = playerManager.wantsToPlay"))
    }

    @Test
    fun `T5 MEDIA_STOP 必须路由到 stop 而非 playPause`() {
        val src = flatten(mainSourceFile(keyHandlerPath))
        assertTrue("T5 空转自证：未找到 MEDIA_STOP 分支", src.contains("KEYCODE_MEDIA_STOP"))
        val stopBranch = methodRegion(src, "KeyEvent.KEYCODE_MEDIA_STOP ->", "KeyEvent.KEYCODE_DPAD_CENTER")
        assertTrue(
            "T5：STOP 语义是停止并回曲首，必须调 stop()。",
            stopBranch.contains("viewModel.playerVM.stop()") && !stopBranch.contains("viewModel.playerVM.playPause()")
        )
        val vm = flatten(mainSourceFile(vmPath))
        assertTrue("T5：PlayerViewModel 缺 stop()", vm.contains("fun stop() {") && vm.contains("playerManager.stop()"))
        val mgr = flatten(mainSourceFile(managerPath))
        assertTrue(
            "T5：PlayerManager.stop() 必须 pause + seekTo(0)，不用 ExoPlayer.stop()(会置 IDLE 无法直接恢复)。",
            mgr.contains("fun stop() {") && mgr.contains("p.seekTo(0L)") && mgr.contains("_progress.value = 0L")
        )
    }

    @Test
    fun `T6 播放未就绪必须有可见提示而非静默`() {
        val vm = flatten(mainSourceFile(vmPath))
        assertTrue(
            "T6：PlayerViewModel.playPause 未做 isPlayerReady 检查。",
            vm.contains("if (!playerManager.isPlayerReady())") && vm.contains("R.string.player_not_ready")
        )
        val strings = mainSourceFile(stringsPath)
        assertTrue("T6：缺 player_not_ready 文案资源", strings.contains("name=\"player_not_ready\""))
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
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv").resolve(relativePath),
            File("app/src/main/java/com/nasmusic/tv").resolve(relativePath),
            File("app/src/main/res").resolve(relativePath)
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件；已尝试：${candidates.joinToString { it.absolutePath }}", file != null)
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }
}
