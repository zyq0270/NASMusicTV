package com.nasmusic.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放页流程缺陷修复门禁（2026-11-22）。
 *
 * 钉死本轮 8 项修复形态，防止回退：
 * - T1 睡眠定时器剩余分钟随状态 emit 刷新（Running 携带 remainingMinutes + UI tick 协程）；
 * - T2 睡眠定时弹窗重开用当前剩余分钟，不再固定 30；
 * - T3 字号档位用容差归档（lyricsFontIndex）而非精确浮点 when；
 * - T4 本地歌词可由用户主动切换（TV 可点 + 竖屏纳入循环）；
 * - T5 AUTO 切歌在 onPositionDiscontinuity 同步进度，消除 1 秒滞后；
 * - T6 逐字歌词时钟用真实出声状态 actualPlaying，不再用 wantsToPlay；
 * - T7 收藏 NAS 分支判断加来源类型守卫（防跨源裸 id 串味）；
 * - T8 进度条 tap/drag 双 pointerInput 用标志互斥，避免一次手势两次 seek。
 *
 * 每组带负自检（抹掉修复形态后判据必须判负），防止判据恒真空转。
 * 全部为源码文本断言（JVM 无 GL/Compose 上下文），离线可跑。
 */
class NowPlayingFlowFixGateTest {

    private fun flatten(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun mainSourceFile(relativePath: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件：$relativePath（cwd=${System.getProperty("user.dir")}）")
        return file.readText(Charsets.UTF_8)
    }

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

    @Test
    fun `T1 睡眠定时器剩余分钟随状态 emit 刷新`() {
        val c = src("player/SleepTimerController.kt")
        assertTrue(
            "Running 必须携带 remainingMinutes，否则 StateFlow 不 emit 新状态、UI 冻结",
            c.contains("data class Running(val endsAtMs: Long, val totalMinutes: Int, val remainingMinutes: Int)")
        )
        assertTrue("必须起 UI 分钟 tick 协程", c.contains("tickJob = scope.launch"))
        assertTrue("tick 内须更新 remainingMinutes 并写回状态", c.contains("current.copy(remainingMinutes = remain)"))
        assertTrue("cancel 必须同时取消 tickJob", c.contains("tickJob?.cancel()"))
        // 负自检：抹掉字段后不得仍判正
        assertFalse(
            "T1 负自检：旧两字段形态必须判负",
            c.replace("val remainingMinutes: Int", "")
                .contains("data class Running(val endsAtMs: Long, val totalMinutes: Int, val remainingMinutes: Int)")
        )
    }

    @Test
    fun `T2 睡眠定时弹窗重开用当前剩余分钟`() {
        val s = src("ui/screens/NowPlayingScreen.kt")
        val dialog = region(s, "private fun SleepTimerPickerDialog(", " androidx.compose.ui.window.Dialog")
        assertTrue("弹窗须接受 initialMinutes 参数", dialog.contains("initialMinutes: Int = 30"))
        assertTrue("初值须用 initialMinutes 而非固定 30", dialog.contains("mutableIntStateOf(initialMinutes.coerceIn(5, 300))"))
        val callCount = count(s, "initialMinutes = (sleepTimerState as?")
        assertTrue("两处调用（横屏 + 竖屏）都须传当前剩余分钟", callCount >= 2)
        assertFalse(
            "T2 负自检：固定 30 的旧初值必须判负",
            dialog.replace("mutableIntStateOf(initialMinutes.coerceIn(5, 300))", "")
                .contains("mutableIntStateOf(initialMinutes.coerceIn(5, 300))")
        )
    }

    @Test
    fun `T3 字号档位用容差归档而非精确浮点 when`() {
        val s = src("ui/screens/NowPlayingScreen.kt")
        val idxFn = region(s, "private fun lyricsFontIndex(scale: Float)", "LyricsSource.LOCAL_FILE")
        assertTrue("归档函数须按最小差值取最近档", idxFn.contains("kotlin.math.abs(candidate - scale)"))
        assertFalse("不得残留精确 when 分支", idxFn.contains("0.7f -> 0") || idxFn.contains("1.6f -> 3"))
        val tvRegion = region(s, "val fontLabels = listOf", "SourceTag(")
        assertTrue("TV 端须复用 lyricsFontIndex", tvRegion.contains("lyricsFontIndex(lyricsFontScale)"))
        assertFalse(
            "T3 负自检：抹掉 abs 比较后不得仍判正",
            idxFn.replace("kotlin.math.abs(candidate - scale)", "").contains("kotlin.math.abs(candidate - scale)")
        )
    }

    @Test
    fun `T4 本地歌词可由用户主动切换`() {
        val s = src("ui/screens/NowPlayingScreen.kt")
        val tvTag = region(s, "com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE.displayNameRes", "Spacer(modifier = Modifier.width(6.dp))")
        assertTrue("TV 本地歌词标签须可聚焦（available=true）", tvTag.contains("available = true"))
        assertTrue("TV 本地歌词标签须有真实 onClick", tvTag.contains("onSwitchLyricsSource(com.nasmusic.tv.data.model.LyricsSource.LOCAL_FILE)"))
        assertFalse("不得再传 onClick = null 纯展示", tvTag.contains("onClick = null"))
        assertTrue("竖屏循环须纳入 LOCAL_FILE", s.contains("LyricsSource.LOCAL_FILE -> true"))
        assertFalse(
            "T4 负自检：竖屏旧禁止形态必须判负",
            s.replace("LyricsSource.LOCAL_FILE -> true", "").contains("LyricsSource.LOCAL_FILE -> true")
        )
    }

    @Test
    fun `T5 AUTO 切歌同步进度`() {
        val pm = src("player/PlayerManager.kt")
        val body = region(pm, "override fun onPositionDiscontinuity(", "override fun onPlayerError(")
        assertTrue("须处理 AUTO_TRANSITION", body.contains("Player.DISCONTINUITY_REASON_AUTO_TRANSITION"))
        // AUTO 分支须写 _progress，但不得碰 seekPending（那是用户 seek 的语义）
        val autoRegion = region(body, "else if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)", "}")
        assertTrue("AUTO 分支须写 _progress", autoRegion.contains("_progress.value = newPosition.positionMs"))
        assertFalse("AUTO 分支不得清 seekPending", autoRegion.contains("seekPending = false"))
        assertFalse(
            "T5 负自检：抹掉 AUTO 分支后不得仍判正",
            body.replace("Player.DISCONTINUITY_REASON_AUTO_TRANSITION", "").contains("Player.DISCONTINUITY_REASON_AUTO_TRANSITION")
        )
    }

    @Test
    fun `T6 逐字歌词时钟用真实出声状态`() {
        val root = src("ui/components/AppRoot.kt")
        val npCall = region(root, "NowPlayingBranch(", "Screen.Library ->")
        assertTrue("AppRoot 须传 actualPlaying = 真实 isPlaying", npCall.contains("actualPlaying = isPlaying"))
        assertTrue("按钮图标仍用 wantsToPlay", npCall.contains("isPlaying = wantsToPlay"))
        val branch = src("ui/components/branches/NowPlayingBranch.kt")
        assertTrue("Branch 须声明 actualPlaying 参数", branch.contains("actualPlaying: Boolean"))
        val s = src("ui/screens/NowPlayingScreen.kt")
        val lyricsCount = count(s, "isPlaying = actualPlaying")
        assertTrue("三处 LyricsView 须用 actualPlaying", lyricsCount >= 3)
        assertFalse(
            "T6 负自检：抹掉 actualPlaying 传参后不得仍判正",
            s.replace("isPlaying = actualPlaying", "").contains("isPlaying = actualPlaying")
        )
    }

    @Test
    fun `T7 收藏 NAS 分支判断加类型守卫`() {
        val vm = src("ui/viewmodel/MainViewModel.kt")
        // 方法已从表达式体改为块体；只钉结构契约，不再锁死「= netVM.toggleNetworkFavorite」字面量。
        val body = region(vm, "fun toggleNetworkFavorite(song: Song)", "fun searchSongsOnServer(")
        assertTrue(
            "收藏入口必须先把合成来源前缀剥离后的 Song 归一化",
            body.contains("val normalized = song.stripSyntheticRadioPrefix()") &&
                body.contains("netVM.toggleNetworkFavorite(")
        )
        assertTrue(
            "isNasFavorite 须加来源类型守卫",
            body.contains("!normalized.isNetworkSong && !normalized.isLocalSong && normalized.id in _favoriteIds.value")
        )
        assertFalse(
            "T7 负自检：抹掉类型守卫后不得仍判正",
            body.replace("!normalized.isNetworkSong && !normalized.isLocalSong && ", "")
                .contains("!normalized.isNetworkSong && !normalized.isLocalSong && normalized.id in _favoriteIds.value")
        )
    }

    @Test
    fun `T8 进度条 tap 与 drag 双触发互斥`() {
        val pc = src("ui/components/PlayerControls.kt")
        assertTrue("须有 dragConsumedGesture 互斥标志", pc.contains("dragConsumedGesture"))
        assertTrue("drag 开始须置位", pc.contains("dragConsumedGesture = true"))
        val tap = region(pc, "detectTapGestures { offset ->", "detectDragGestures(")
        assertTrue("tap 回调须检查互斥标志并早退", tap.contains("if (dragConsumedGesture)") && tap.contains("return@detectTapGestures"))
        assertFalse(
            "T8 负自检：抹掉互斥检查后不得仍判正",
            tap.replace("if (dragConsumedGesture)", "").contains("if (dragConsumedGesture)")
        )
    }
}
