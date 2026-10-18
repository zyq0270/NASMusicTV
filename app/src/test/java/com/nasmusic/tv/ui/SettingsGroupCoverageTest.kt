package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「设置分区的每个可操作行必须待在 `SettingsGroupCard` 里」（方案 §5.7 G1）。
 *
 * 与 ChipContentWidthScanTest / SmallTouchTargetScanTest 同范式：项目无 compose-ui-test，
 * 「子项没有分组边界」这类**纯视觉缺陷**只能靠源码结构门禁锁住。
 *
 * 判定：`SettingSwitch(` / `SettingActionButton(` / `SettingsInfoRow(` 调用点必须落在
 * 同文件某个 `SettingsGroupCard(` 的大括号配对区内；豁免标记 `Group-exempt: <理由>`；
 * `//` 与 `/* … */` 注释先剥离。
 *
 * ⚠️ MIGRATED 是**增量白名单的反向**：只有已完成卡片化的文件才被扫描，逐任务扩大，
 * 全部迁完（T15）后改为扫描 `ui/screens/settings/` 全部 `*Section.kt`。
 */
class SettingsGroupCoverageTest {

    private val migrated = listOf(
        "GeneralSettingsSection.kt",
        "PlayerSettingsSection.kt",
        "LyricsKaraokeSettingsSection.kt",
        "VisualEffectsSettingsSection.kt",
        "PhotoWallSettingsSection.kt",
    )

    private val rowCalls = listOf("SettingSwitch(", "SettingActionButton(", "SettingsInfoRow(")

    @Test
    fun `已迁移分区的每个设置行都必须位于 SettingsGroupCard 内`() {
        val root = settingsRoot()
        var scanned = 0
        val violations = mutableListOf<String>()
        for (name in migrated) {
            val text = stripComments(root.resolve(name).readText())
            val cardRanges = groupCardSpans(text)
            assertTrue("$name 已列入迁移名单却找不到 SettingsGroupCard 调用", cardRanges.isNotEmpty())
            text.lines().forEachIndexed { idx, line ->
                val call = rowCalls.firstOrNull { line.contains(it) } ?: return@forEachIndexed
                if (line.contains("Group-exempt:")) return@forEachIndexed
                if (line.contains("internal fun ") || line.contains("private fun ")) return@forEachIndexed // 组件自身定义
                val offset = text.lineOffset(idx)
                scanned++
                if (cardRanges.none { offset in it }) violations += "$name:${idx + 1} $call"
            }
        }
        assertTrue("扫描到 0 个调用点 —— 门禁在空转", scanned > 0)
        if (violations.isNotEmpty()) fail("以下设置行不在任何分组卡片内：\n" + violations.joinToString("\n"))
    }

    /** 负向自证：把一行挪到卡片外，必须被判违规。 */
    @Test
    fun `卡片外的设置行必须判负`() {
        val text = """
            fun a() {
                SettingsGroupCard(title = "x") {
                    SettingSwitch(label = "1")
                }
                SettingSwitch(label = "2")
            }
        """.trimIndent()
        val spans = groupCardSpans(text)
        val outside = text.lineOffset(4)
        assertTrue("负向用例失效：卡片外的行仍被算作卡片内", spans.none { outside in it })
    }

    /** 注释里举例的写法不得被误判（剥离注释生效）。 */
    @Test
    fun `注释中的设置行调用不算违规`() {
        val text = "// SettingSwitch(label = \"x\")\nval y = 1\n"
        assertTrue(stripComments(text).contains("SettingSwitch") == false)
    }

    // ─────────── 辅助 ───────────

    /** 返回每个 `SettingsGroupCard(` 尾随 lambda 体（大括号配对）的字符区间。 */
    private fun groupCardSpans(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            val at = text.indexOf("SettingsGroupCard(", startIndex = i)
            if (at < 0) break
            val brace = text.indexOf('{', at)
            if (brace < 0) break
            var depth = 0
            var end = -1
            var j = brace
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end > brace) out += (brace + 1)..(end - 1)
            i = at + 1
        }
        return out
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun String.lineOffset(lineIndex: Int): Int {
        var o = 0
        for (k in 0 until lineIndex) o += lines()[k].length + 1
        return o
    }

    private fun settingsRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到设置源码目录（user.dir=${File("").absolutePath}）", found != null)
        return found!!
    }
}
