package com.nasmusic.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **门禁：设置页「标签 + 值」的值列必须按比例分配，不得写死宽度封顶**（2026-11-12 真机反馈修正）
 *
 * ## 它防的是什么
 *
 * 真机反馈：「设置的日志页面…这些文字都堆在最后面，字符多了就分了三行，
 * 但前面其实有很大的空间，应该扩充到前面，一行显示更多的文字。
 * 而且整个设置所有的页面中还有很多类似的情况，你都查查」。
 *
 * ⇒ 实测（电视 1920×1080 / 240dpi）照片墙设置页：
 * `照片数量` 那行的值「图库 0 · 外接 0 · Jellyfin 20000」被挤在 **x=1575..1821**
 * 的窄条里**折成 3 行**，而左侧标签区空着 1000 多像素。
 *
 * ⇒ 根因：值列宽度被**写死封顶** 180dp（`SettingsTokens.infoValueWidth`，§4.8 A4），
 * 而标签列是 `weight(1f)`（占满剩余）⇒ 长句无处可去，只能在 180dp 里折行。
 *
 * ## 三条判据
 *
 * ① 值列必须**按比例分配**（`weight(...)`）—— 不得是固定宽度 / 固定封顶；
 * ② 值列**不许**再出现 `widthIn(max = valueMaxWidth)`（180dp 一刀切就是病灶本身）；
 * ③ 标签列必须 `weight(1f)` ⇒ §4.8 A4「长值不许把标签挤没」这条天然成立。
 */
class SettingsValueWidthGateTest {

    @Test
    fun `值列必须按比例分配而不是写死封顶`() {
        val body = labelValueRowBody()

        assertFalse(
            "值列不许再写死 `widthIn(max = valueMaxWidth)` —— 那正是「文字堆在最后面、折成三行」的病灶",
            body.contains("widthIn(max = valueMaxWidth)"),
        )
        assertFalse(
            "值列不许写死像素宽度（`width(`）—— 固定宽度在电视上必折行",
            body.contains("Modifier.width("),
        )
        assertTrue(
            "值列必须按比例分配（weight）⇒ 长句一次多出 4 倍宽度、一行显示",
            body.contains("Modifier.weight("),
        )
        assertTrue(
            "标签列必须 `weight(1f)` ⇒ 长值也挤不没标签（§4.8 A4）",
            Regex("""Modifier\.weight\(1f\)""").containsMatchIn(body),
        )
    }

    /** 负向自证：写死封顶的老写法必须被判负（防止门禁空转）。 */
    @Test
    fun `负向自证 写死封顶的值列必须判负`() {
        val sample = """
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    modifier = if (valueMaxWidth != null) {
                        Modifier.widthIn(max = valueMaxWidth).padding(start = 16.dp)
                    } else {
                        Modifier.weight(1.5f).padding(start = 16.dp)
                    }
                )
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：写死封顶的样例被误判通过",
            !isProportionalValueWidth(sample),
        )
    }

    /** 负向自证：标签没有 weight ⇒ 标签可能被长值挤没，必须判负。 */
    @Test
    fun `负向自证 标签没有权重必须判负`() {
        val sample = """
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = label)
                Text(text = value, modifier = Modifier.weight(1.5f))
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：标签无权重的样例被误判通过",
            !isProportionalValueWidth(sample),
        )
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 判定「值列是否按比例分配 + 标签是否拿得住」。
     *
     * ① 值列**不得**是固定宽度 / 固定封顶；
     * ② 值列**必须**用 `weight(`；
     * ③ 标签**必须**用 `weight(1f)`。
     */
    private fun isProportionalValueWidth(body: String): Boolean {
        if (body.contains("widthIn(max = valueMaxWidth)")) return false
        if (body.contains("Modifier.width(")) return false
        if (!body.contains("Modifier.weight(")) return false
        return Regex("""Modifier\.weight\(1f\)""").containsMatchIn(body)
    }

    /** 取出 `SettingsLabelValueRow` 的函数体（大括号配对） */
    private fun labelValueRowBody(): String {
        val code = mainSource("ui/screens/settings/SettingsComponents.kt")
        val start = code.indexOf("private fun SettingsLabelValueRow(")
        assertTrue("找不到 SettingsLabelValueRow（函数改名了？）", start >= 0)
        val open = code.indexOf('{', start)
        assertTrue("SettingsLabelValueRow 没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
            i++
        }
        return code.substring(open + 1)
    }

    private fun mainSource(relPath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（已尝试：${candidates.joinToString { it.absolutePath }}）",
            root != null,
        )
        val file = File(root!!, relPath)
        assertTrue("找不到源文件：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }
}
