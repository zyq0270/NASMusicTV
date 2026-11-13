package com.nasmusic.tv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 页内边距口径门禁（二维：**取值** + **不许有副本**）。
 *
 * ## 为什么需要
 *
 * 2026-11-13 排查「播放页顶栏与歌词按钮行之间的空挡，在电视上更大」时发现：这条空挡
 * 由三段内边距叠成，而其中一段（页面 `top` padding）在 **8 个页面里各写了一份三元表达式**：
 *
 * ```kotlin
 * horizontal = if (isPhoneLandscape) 16.dp else if (isPhonePortrait) 16.dp else 32.dp
 * ```
 *
 * 同一个判断 6 份副本的直接后果是**漏改**：首页只判了 `isPhonePortrait`，
 * 手机横屏于是落进 `else` 取 32dp，而其余 6 个页面横屏是 16dp —— 同一台手机横过来，
 * 首页左右各比曲库多 16dp。这不是某个人抄错，是「有副本就迟早会分叉」的必然结果。
 *
 * 因此本门禁锁两件事：
 * 1. **取值**：`pagePadding()` 的三档必须是指定值（防止有人"顺手"把横屏纵向调回 12dp，
 *    那是 2026-09-22 `d0d732b` 两轮真机反馈已否决的方案）
 * 2. **不许有副本**：页面文件里不许再出现内联的形态判断式 padding
 *
 * ## 为什么是源码扫描而不是 Kotlin 单测
 *
 * 第 1 条可以纯 JVM 测（`pagePadding` 是 `@Composable`，但三档值可抽纯函数）；第 2 条
 * **必须**扫源码 —— Compose 的 `padding(…)` 调用在运行期等价，测不出「有没有第二份副本」。
 * 沿用本工程既有做法（`KaraokeFullscreenGateTest` / `ScreenUiModeCoverageTest` 同路径）。
 */
class PagePaddingContractTest {

    private val screensDir = "com/nasmusic/tv/ui/screens"

    // ───────────────────────── 取值契约 ─────────────────────────

    @Test
    fun `三档横向值必须是指定基线`() {
        assertEquals("TV 横向基线", 32, pageHorizontalDp(UiMode.TV))
        assertEquals("手机横屏横向基线", 16, pageHorizontalDp(UiMode.PhoneLandscape))
        assertEquals("手机竖屏横向基线", 16, pageHorizontalDp(UiMode.PhonePortrait))
    }

    @Test
    fun `三档纵向值必须是指定基线`() {
        assertEquals("TV 纵向基线", 20, pageVerticalDp(UiMode.TV))
        // ⚠️ 横屏 0 是刻意的（2026-09-22 d0d732b：12dp 无感，要求干脆去掉）
        assertEquals("手机横屏纵向必须为 0（不是 12）", 0, pageVerticalDp(UiMode.PhoneLandscape))
        assertEquals("手机竖屏纵向基线", 12, pageVerticalDp(UiMode.PhonePortrait))
    }

    @Test
    fun `uniform 时纵向必须与横向同值`() {
        UiMode.entries.forEach { mode ->
            val h = pageHorizontalDp(mode)
            assertEquals("$mode 的 uniform 纵向应等于横向 $h", h, pageVerticalDp(mode, uniform = true))
        }
    }

    // ───────────────────── 不许有副本（源码扫描） ─────────────────────

    @Test
    fun `页面根容器不许内联形态判断式 padding`() {
        val offenders = mutableListOf<String>()
        val pageFiles = listOf(
            "HomeScreen", "LibraryScreen", "MineScreen", "QueueScreen", "SettingsScreen",
            "AlbumDetailScreen", "ArtistDetailScreen", "EqualizerScreen",
        )
        for (name in pageFiles) {
            val src = readSource("$screensDir/$name.kt")
            // 只认**根容器**：`.fillMaxSize()` 之后紧跟的那一个 `.padding(...)`。
            // 页面内部布局（如设置页侧栏的 `padding(start = …)`）属于局部间距，
            // 不是页内边距，不在本门禁范围内 —— 判据必须窄，否则门禁会逼着人去
            // 「统一」那些本来该按组件走的间距。
            val root = Regex(
                """fillMaxSize\(\)\s*\.padding\(\s*[^)]*?""" +
                    """(?:UiMode\.(?:TV|PhoneLandscape|PhonePortrait)|isPhoneLandscape|isPhonePortrait|isPortraitPhone)""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(src)
            if (root != null) {
                offenders += "$name.kt: ${root.value.replace(Regex("\\s+"), " ").take(140)}"
            }
        }
        assertTrue(
            "以下页面的**根容器**仍在手抄形态判断式 padding —— 应改为 `padding(pagePadding())`" +
                "（四边等距用 `pagePadding(uniform = true)`，自己用 spacedBy 的用 `pagePadding(vertical = 0.dp)`）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `pagePadding 必须是公开 API 且八个页面都在用`() {
        val theme = readSource("com/nasmusic/tv/ui/theme/Theme.kt")
        assertTrue("pagePadding 不见了", "fun pagePadding(" in theme)

        val pageFiles = listOf(
            "HomeScreen", "LibraryScreen", "MineScreen", "QueueScreen", "SettingsScreen",
            "AlbumDetailScreen", "ArtistDetailScreen", "EqualizerScreen",
        )
        val missing = pageFiles.filter { name ->
            !readSource("$screensDir/$name.kt").contains("pagePadding(")
        }
        assertTrue(
            "以下页面还没接入 pagePadding()：${missing.joinToString()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `文档必须记录页面内边距基线`() {
        val doc = File("docs/conventions-adaptive-ui.md").let {
            if (it.isFile) it else File("../docs/conventions-adaptive-ui.md")
        }
        assertTrue("找不到 conventions-adaptive-ui.md", doc.isFile)
        val text = doc.readText()
        assertTrue("文档缺 §3.2 页面内边距基线", "3.2" in text && "pagePadding" in text)
        assertTrue("文档缺单位口径说明（Android dp ≠ Compose dp）", "Android dp" in text && "Compose dp" in text)
        // 旧的过期基线必须已被替换
        assertTrue(
            "文档仍在写「非竖屏保持原值（当前基线是 32.dp）」这条过期口径",
            "非竖屏保持原值" !in text,
        )
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * 从 `Theme.kt` 的 `pagePadding` 里读出基线值（dp 整数）。
     *
     * 直接扫源码而不是调用函数：`pagePadding` 是 `@Composable`，JVM 单测无法调用；
     * 而把三档值抽成纯函数又会引入「纯函数与 Composable 各存一份」的新副本 ——
     * 与本门禁要防的问题同型。扫源码是这里唯一自洽的做法。
     */
    private fun pageHorizontalDp(mode: UiMode): Int {
        val body = pagePaddingBody()
        // when (mode) { UiMode.TV -> 32.dp ; ... } 按顺序取
        val m = Regex("""UiMode\.${mode.name}\s*->\s*(\d+)\.dp""").find(body)
        assertTrue("pagePadding 里找不到 ${mode.name} 的横向基线", m != null)
        return m!!.groupValues[1].toInt()
    }

    private fun pageVerticalDp(mode: UiMode, uniform: Boolean = false): Int {
        val body = pagePaddingBody()
        if (uniform) return pageHorizontalDp(mode)
        // 纵向是 when { uniform -> base ; mode == UiMode.TV -> 20.dp ; ... }
        val pattern = when (mode) {
            UiMode.TV -> """mode == UiMode\.TV\s*->\s*(\d+)\.dp"""
            UiMode.PhoneLandscape -> """mode == UiMode\.PhoneLandscape\s*->\s*(\d+)\.dp"""
            UiMode.PhonePortrait -> """else\s*->\s*(\d+)\.dp"""
        }
        val m = Regex(pattern).find(body)
        assertTrue("pagePadding 里找不到 ${mode.name} 的纵向基线", m != null)
        return m!!.groupValues[1].toInt()
    }

    private fun pagePaddingBody(): String {
        val src = readSource("com/nasmusic/tv/ui/theme/Theme.kt")
        val start = src.indexOf("fun pagePadding(")
        assertTrue("找不到 fun pagePadding(", start >= 0)
        val end = src.indexOf("\n}", start)
        assertTrue("找不到 pagePadding 的函数体结尾", end >= 0)
        return src.substring(start, end)
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("../app/src/main/java/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）",
            found != null,
        )
        return found!!.readText()
    }
}
