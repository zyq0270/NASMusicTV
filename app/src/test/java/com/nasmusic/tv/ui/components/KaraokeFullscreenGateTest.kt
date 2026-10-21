package com.nasmusic.tv.ui.components

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K 歌页全屏门禁（源码扫描）。
 *
 * 所有者反馈「K 歌页面要做成全屏模式，TV 端和手机端都要修改」。K 歌页自身一直是
 * `fillMaxSize`，缺的是 AppRoot 顶部导航栏：它只排除了沉浸模式 / MTV / 可视化，
 * 漏了 K 歌 ⇒ TV 与手机横屏都会在歌词上方留一条导航栏。
 *
 * K 歌页不是 AppRoot 覆盖层（是 NowPlayingScreen 内部分支），它的全屏不变量分散在
 * 三处条件里：顶部栏、手机竖屏底部栏、手机系统栏。Robolectric 不验证 Compose 布局结果，
 * 故按本工程既有做法（`KaraokePickerTvFocusScanTest` / `ScreenUiModeCoverageTest` 同路径）
 * 锁住源码结构 —— 任一处丢掉 showKaraoke 即红。
 */
class KaraokeFullscreenGateTest {

    @Test
    fun `顶部导航栏在 K 歌页必须隐藏`() {
        val source = readSource("com/nasmusic/tv/ui/components/AppRoot.kt")
        val block = sliceBetween(source, "// 顶部导航栏", "TvTopNavBar(")
        assertTrue(
            "AppRoot 顶部栏条件缺少 `!showKaraoke` ⇒ TV / 手机横屏会在 K 歌页上方留一条导航栏",
            "!showKaraoke" in block
        )
    }

    @Test
    fun `手机竖屏底部栏在 K 歌页必须隐藏`() {
        val source = readSource("com/nasmusic/tv/ui/components/AppRoot.kt")
        val block = sliceBetween(source, "// 手机竖屏底部", "PhoneNavBar(")
        assertTrue(
            "手机竖屏底部栏（MiniPlayer + PhoneNavBar）条件缺少 `!showKaraoke`",
            "!showKaraoke" in block
        )
    }

    @Test
    fun `手机系统栏在 K 歌页必须隐藏`() {
        val source = readSource("com/nasmusic/tv/ui/MainActivity.kt")
        val block = sliceBetween(source, "val showBars =", "if (showBars)")
        assertTrue(
            "MainActivity 的 showBars 条件缺少 `!showKaraoke` ⇒ 手机端 K 歌页仍显示状态栏 / 导航栏",
            "!showKaraoke" in block
        )
    }

    /**
     * 取 `from` 到其后第一个 `to` 之间的源码。
     * 定位失败**直接失败**而不是跳过 —— 否则这道门禁会被静默禁用。
     */
    private fun sliceBetween(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("在源码中找不到起点 `$from`", start >= 0)
        val end = source.indexOf(to, start)
        assertTrue("在源码中找不到 `$from` 之后的终点 `$to`", end >= 0)
        return source.substring(start, end)
    }

    /**
     * 定位源码文件。AGP 单测的 `user.dir` 是模块目录（`…/app`），同时尝试仓库根与上级。
     */
    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("../app/src/main/java/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!.readText()
    }
}
