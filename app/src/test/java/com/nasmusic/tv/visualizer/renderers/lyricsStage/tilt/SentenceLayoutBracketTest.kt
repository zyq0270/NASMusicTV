package com.nasmusic.tv.visualizer.renderers.lyricsStage.tilt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * E53「歌词舞台」—— `SentenceLayout` 的括号 / 引号配对门禁。
 *
 * 起因（2026-11-07 真机 logcat，**ground truth**）：
 * ```
 * W/VisualizerStage: renderer draw failed, skipped frame: LyricsStageRenderer
 * W/VisualizerStage: java.util.regex.PatternSyntaxException: Missing closing bracket in character class near index 1
 *     at ...SentenceLayout.findRegexMatch(...)
 *     at ...SentenceLayout.findOutermostPair(...)
 *     at ...SentenceLayout.extractOutermostPairs(...)
 *     at ...SentenceLayout.splitByBracketsQuotes(...)
 *     at ...SentenceLayout.splitByLevel(...)
 *     at ...SentenceLayout.splitIntoSentences(...)
 * ```
 * 宿主 catch 住异常后**跳过整帧** ⇒ 「歌词舞台」**所有**子模式全黑（不只是 TILT）。
 *
 * 根因：Folia 侧 `PairedSymbolDef.open` / `close` 是**正则**，本仓曾把**字面**的
 * `"["` / `"("` 填进去 ⇒ `Regex("[")` 抛 `PatternSyntaxException`。
 *
 * 本门禁钉三件事：
 * ① **不再崩**：含 `[`/`]`/`(`/`)` 的行照常拆分；
 * ② **不再能崩**：坏正则**降级**成「这条定义不匹配」，绝不逃进 `draw()`；
 * ③ **没有变空转**：旧的（坏的）定义集确实会抛 ⇒ 门禁不是摆设。
 */
class SentenceLayoutBracketTest {

    // ── ① 崩溃回归 ────────────────────────────────────────────────────────────

    @Test
    fun `a line with square brackets and parentheses does not crash the splitter`() {
        val text = "他说[这样]就好(真的)"
        val result = SentenceLayout.splitIntoSentences(text, 2, 0.0)

        assertTrue("拆分结果为空：$text", result.isNotEmpty())
        assertTrue("拆分出了空片段：$result", result.all { it.isNotEmpty() })
        assertEquals("拆分丢了字：$text", text, result.joinToString(""))
    }

    @Test
    fun `an unpaired parenthesis does not crash the splitter`() {
        assertSplitPreservesText("他说(你好", 2)
        assertSplitPreservesText("他说(你好然后离开", 3)
    }

    @Test
    fun `an unpaired square bracket does not crash the splitter`() {
        assertSplitPreservesText("他说[你好", 2)
        assertSplitPreservesText("他说[你好然后离开", 3)
    }

    @Test
    fun `a line containing only a square bracket does not crash the splitter`() {
        assertSplitPreservesText("[", 2)
        assertSplitPreservesText("他[说", 2)
    }

    @Test
    fun `a line containing only a parenthesis does not crash the splitter`() {
        assertSplitPreservesText("(", 2)
        assertSplitPreservesText("他(说", 2)
    }

    /**
     * 端到端：`TiltPulseSubRenderer` 的真实调用链是
     * `TiltLayout.buildTiltLayout` → `SentenceLayout.splitIntoSentences`。
     * ⛔ 这里必须**不抛异常** —— 抛出去就是整帧黑屏。
     */
    @Test
    fun `the production call path of the tilt layout survives brackets`() {
        val text = "他说[这样]就好(真的)其实我一直都明白"
        val result = TiltLayout.buildTiltLayout(
            fullText = text,
            lineSeed = 1.0,
            splitProbability = 1.0,
            tiltStyleProbability = 0.5,
            fontScale = 1f,
            availableWidth = 1920f,
            measure = { s, _ -> s.length * 40f },
        )
        assertTrue("没有产出任何分段", result.segments.isNotEmpty())
        assertTrue(
            "分段里有空文本：${result.segments.map { it.text }}",
            result.segments.all { it.text.isNotEmpty() },
        )
    }

    // ── ② 配对语义：ASCII 括号现在与 CJK 括号一样成对 ──────────────────────────

    @Test
    fun `square brackets are matched as a literal pair`() {
        val result = SentenceLayout.splitIntoSentences("他说[你好]然后离开", 3, 0.0)
        assertEquals(listOf("他说", "[你好]", "然后离开"), result)
    }

    @Test
    fun `parentheses are matched as a literal pair`() {
        val result = SentenceLayout.splitIntoSentences("他说(你好)然后离开", 3, 0.0)
        assertEquals(listOf("他说", "(你好)", "然后离开"), result)
    }

    /**
     * 回归钉子：CJK 引号对的拆分行为**不能**被这次修复改坏。
     * （`「」『』《》【】｛｝［］` 都走正则侧 ⇒ 必须仍然是「成对切开」。）
     */
    @Test
    fun `the CJK quote pairs still split as before`() {
        val pairs = listOf("「」", "『』", "《》", "【】", "｛｝", "［］")
        for (pair in pairs) {
            val open = pair.substring(0, 1)
            val close = pair.substring(1, 2)
            val text = "他说${open}你好${close}然后离开"
            val result = SentenceLayout.splitIntoSentences(text, 3, 0.0)
            assertEquals(
                "配对 $pair 的拆分结果变了",
                listOf("他说", "${open}你好${close}", "然后离开"),
                result,
            )
        }
    }

    // ── ③ 门禁不是空转：旧的（坏的）定义集确实会抛 ──────────────────────────────

    @Test
    fun `the old definition set would have thrown a PatternSyntaxException`() {
        // ⛔ 旧实现：`PairedSymbolDef(open = "[", close = "]")` ⇒ 编译期就炸。
        //    触发点正是**开**符号：`[` = 「未闭合的字符类」、`(` = 「未闭合的分组」。
        //    （闭符号 `]` 单独作为正则其实是合法的字面量 ⇒ 崩的是开符号那一侧。）
        assertPatternSyntaxException("[")
        assertPatternSyntaxException("(")
    }

    @Test
    fun `the production source no longer assigns a raw string to the regex side`() {
        val source = stripComments(productionSource())
        // ⛔ `open` / `close` 现在是 `Regex?` ⇒ **代码里**不可能再出现 `open = "…"`。
        assertFalse("源码里仍有 `open = \"…\"`", source.contains("open = \""))
        assertFalse("源码里仍有 `close = \"…\"`", source.contains("close = \""))
        // 历史事故的字面量，逐条钉死：
        assertFalse(source.contains("open = \"[\""))
        assertFalse(source.contains("open = \"(\""))
        assertFalse(source.contains("close = \"]\""))
        assertFalse(source.contains("close = \")\""))
    }

    @Test
    fun `the paired symbol table keeps the brackets on the literal side`() {
        val defs = SentenceLayout.pairedSymbolDefinitions()

        assertEquals("配对表条目数变了", 11, defs.size)

        // ASCII 括号必须走**字面**侧
        assertTrue(
            "配对表里没有 `[`/`]` 的字面定义",
            defs.any { it.openStr == "[" && it.closeStr == "]" },
        )
        assertTrue(
            "配对表里没有 `(`/`)` 的字面定义",
            defs.any { it.openStr == "(" && it.closeStr == ")" },
        )

        for (def in defs) {
            // 正则侧只允许「单字符字面量」⇒ 结构上不可能再出现「裸括号当正则」
            val openPattern = def.open?.pattern
            val closePattern = def.close?.pattern
            for (pattern in listOfNotNull(openPattern, closePattern)) {
                assertTrue("正则侧的模式必须是单字符字面量：$pattern", pattern.length == 1)
                assertTrue(
                    "正则侧的模式不能是正则元字符：$pattern",
                    pattern !in "[]({}|*+?.^$\\",
                )
            }
            // 每条定义都要有可用的开 / 闭匹配器
            assertTrue("定义缺少开括号匹配器", def.open != null || def.openStr != null)
            assertTrue("定义缺少闭括号匹配器", def.close != null || def.closeStr != null)
            // 两侧不能同时存在（否则语义含糊）
            assertFalse("定义同时有正则侧与字面侧", def.open != null && def.openStr != null)
            assertFalse("定义同时有正则侧与字面侧", def.close != null && def.closeStr != null)
        }
    }

    @Test
    fun `a malformed pattern degrades to no match instead of throwing`() {
        // ⛔ 这就是「一个坏正则不能杀掉整帧」的最后一道闸。
        assertNull("坏模式必须降级成 null", SentenceLayout.compilePatternOrNull("["))
        assertNull("坏模式必须降级成 null", SentenceLayout.compilePatternOrNull("("))
        assertNull("坏模式必须降级成 null", SentenceLayout.compilePatternOrNull("[a-"))
        assertNull("坏模式必须降级成 null", SentenceLayout.compilePatternOrNull("(?<"))
        assertNotNull(SentenceLayout.compilePatternOrNull("「"))
        assertNotNull(SentenceLayout.compilePatternOrNull("."))
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun assertSplitPreservesText(text: String, targetCount: Int) {
        val result = SentenceLayout.splitIntoSentences(text, targetCount, 0.0)
        assertTrue("拆分结果为空：$text", result.isNotEmpty())
        assertTrue("拆分出了空片段：$result", result.all { it.isNotEmpty() })
        assertEquals("拆分丢了字：$text", text, result.joinToString(""))
    }

    private fun assertPatternSyntaxException(pattern: String) {
        var thrown = false
        try {
            Regex(pattern)
        } catch (e: PatternSyntaxException) {
            thrown = true
        }
        assertTrue(
            "`Regex(\"$pattern\")` 竟然没抛 PatternSyntaxException ⇒ 自证失效（门禁空转）",
            thrown,
        )
    }

    /** 定位生产源码（照抄 CaravanOcclusionGateTest 的 mainSourceRoot 手法）。 */
    private fun productionSource(): String {
        var dir = File(System.getProperty("user.dir") ?: "")
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer/renderers/lyricsStage/tilt")
            if (cand.isDirectory) {
                val f = File(cand, "SentenceLayout.kt")
                assertTrue("找不到 SentenceLayout.kt：${f.absolutePath}", f.isFile)
                return f.readText(Charsets.UTF_8)
            }
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 SentenceLayout.kt 源码目录：user.dir = ${System.getProperty("user.dir")}")
    }

    /**
     * 剥掉块注释（可嵌套）与行注释；**保持行数不变**
     * （照抄 CaravanOcclusionGateTest / PerfBudgetContractTest 的同一实现）。
     */
    private fun stripComments(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '*') {
                        depth++
                        i += 2
                        continue
                    }
                    if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') {
                        depth--
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                while (i < s.length && s[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
