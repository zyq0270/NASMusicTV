package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放页顶部空挡门禁（源码扫描）。
 *
 * ## 为什么需要
 *
 * 用户真机反馈：「首页 / 正在播放 / 曲库…」菜单行与「歌曲名 + 内嵌 / 本地 / 在线」按钮行
 * 之间有一条空挡，**在电视上更大、更显眼**，这些空间本应显示歌词。
 *
 * 这条空挡由**三段**内边距叠成，分散在两个文件里，任何一段单独看都不刺眼：
 *
 * | 来源 | 位置 | 改前 | 改后 |
 * |---|---|---|---|
 * | ① 顶栏下内边距 | `AppRoot.kt` `TvTopNavBar` | 16dp | 播放页 4dp |
 * | ② `NavItem` 内容内边距 | `AppRoot.kt` `NavItem` | 12dp | 12dp（D-Pad 热区，不动）|
 * | ③ 播放页顶部内边距 | `NowPlayingScreen.kt` | TV **40dp** / 横屏 0 | TV 8dp / 横屏 4dp |
 *
 * ⇒ TV 改前 68dp（≈1080p 屏高 12.6%），横屏 28dp。用户「电视上更显眼」不是观感差异，
 * 而是**同一公式里第③段取值不同**。
 *
 * ## 判定规则
 *
 * - ③ 必须按形态分三档，且 TV 非沉浸分支 ≤ 8dp；
 * - ① 必须按页面区分，播放页收紧、其余页 16dp；上内边距恒 16dp（否则 6 个页面一起位移）；
 * - ② 不许缩 —— 它是 D-Pad 热区；
 * - 沉浸模式不跟着缩：该模式下 `AppRoot` 隐藏顶栏，40dp 是唯一的顶部留白。
 *
 * Robolectric 不验证 Compose 布局结果，故按本工程既有做法
 * （`KaraokeFullscreenGateTest` / `LibraryBrowseCardStyleGateTest` 同路径）锁住源码结构。
 */
class NowPlayingTopGapGateTest {

    private val nowPlayingPath = "com/nasmusic/tv/ui/screens/NowPlayingScreen.kt"
    private val appRootPath = "com/nasmusic/tv/ui/components/AppRoot.kt"

    @Test
    fun `播放页顶部内边距必须按形态分三档且 TV 不超过 8dp`() {
        val source = readSource(nowPlayingPath)
        val block = sliceBetween(source, "val nowPlayingTopPadding = when {", "Column(")

        assertTrue(
            "找不到 `nowPlayingTopPadding` 的三档取值 —— 被改回单个常量了？" +
                "该值必须按 手机横屏 / 沉浸 / TV普通 三态分别给出",
            "isPhoneLandscape" in block && "isImmersiveMode" in block,
        )
        // 抽出 else 分支（TV 普通）的字面量并断言上限
        val tvBranch = block.lineSequence()
            .firstOrNull { "else ->" in it }
            ?.substringAfter("else ->")
        assertTrue("TV 普通分支缺失（`else -> N.dp`）", tvBranch != null)
        val dpValue = Regex("""(\d+(?:\.\d+)?)\.dp""")
            .find(tvBranch!!)
            ?.groupValues?.get(1)
            ?.toFloatOrNull()
        assertTrue("TV 分支的顶部内边距不是 dp 字面量：$tvBranch", dpValue != null)
        assertTrue(
            "TV 普通模式的顶部内边距是 ${dpValue}dp，超过 8dp 上限 ⇒ " +
                "与顶栏 16dp 下内边距叠成 ${16f + dpValue!!}dp 空挡，电视上会重新出现大片留白",
            dpValue!! <= 8f,
        )
    }

    @Test
    fun `沉浸模式必须保留原有顶部留白`() {
        val source = readSource(nowPlayingPath)
        val block = sliceBetween(source, "val nowPlayingTopPadding = when {", "Column(")
        val immersiveBranch = block.lineSequence()
            .firstOrNull { "isImmersiveMode ->" in it }
        assertTrue("沉浸分支缺失", immersiveBranch != null)
        assertTrue(
            "沉浸模式的顶部留白被一起缩小了：$immersiveBranch。" +
                "该模式顶栏被 AppRoot 隐藏，这段留白是唯一的顶部呼吸位，必须保持 40dp",
            "40.dp" in immersiveBranch!!,
        )
    }

    @Test
    fun `顶栏下内边距在播放页必须收紧`() {
        val source = readSource(appRootPath)
        val block = sliceBetween(source, "private fun TvTopNavBar(", "NavItem(")
        assertTrue(
            "TvTopNavBar 的 bottom 内边距没有按页面收窄 —— 应写成 " +
                "`if (currentScreen == Screen.NowPlaying) 4.dp else 16.dp`",
            "Screen.NowPlaying) 4.dp else 16.dp" in block,
        )
        // 上内边距必须保持 16dp：导航行自身位置不能动，否则 6 个页面的菜单行会一起上跳
        assertTrue(
            "上内边距被一起改了 —— 那会把导航行自身也往上顶，6 个页面全部位移；" +
                "本次只允许收窄**下方**空档",
            "top = 16.dp" in block,
        )
    }

    @Test
    fun `NavItem 内容内边距不许为这条空挡缩小`() {
        val source = readSource(appRootPath)
        // 切到下一个 composable 声明处为止 —— `vertical = 12.dp` 位于 FocusableSurface
        // 的 content lambda 内，终点若取 `FocusableSurface(` 会把它切掉（门禁自己假绿/假红）。
        val block = sliceBetween(source, "private fun NavItem(", "全屏可视化舞台覆盖层")
        assertTrue(
            "NavItem 的内容内边距（vertical = 12dp）被缩了 —— 它同时是 D-Pad 的按压热区，" +
                "缩小会让遥控器更难选中，空挡只能从顶栏下内边距与播放页 top padding 里收",
            "vertical = 12.dp" in block,
        )
    }

    /** 取 `from` 到其后第一个 `to` 之间的源码；定位失败直接失败，避免门禁被静默禁用。 */
    private fun sliceBetween(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("在源码中找不到起点 `$from`", start >= 0)
        val end = source.indexOf(to, start)
        assertTrue("在源码中找不到 `$from` 之后的终点 `$to`", end >= 0)
        return source.substring(start, end)
    }

    /** AGP 单测的 `user.dir` 是模块目录（`…/app`），同时尝试仓库根与上级。 */
    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("../app/src/main/java/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!.readText()
    }
}
