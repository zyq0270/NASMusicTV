package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置页 · 日志 tab」的接线门禁（运行期错误持久化，可反复查看）。
 *
 * 为什么需要源码门禁：本项目无 compose-ui-test，以下四类断链**编译都能过**，但功能静默失效：
 *  1. 枚举加了 `LOGS` 却没在 `SettingsScreen` 渲染 / 没登记焦点请求器（TV 上按「下」进不去）；
 *  2. `LogStore` 写好了，但 `AppLog.w/e` 没接上 ⇒ 日志文件永远是空的；
 *  3. 崩溃处理器没装 ⇒ 最需要留痕的场景（闪退）一条都留不下；
 *  4. `LogStore` 反向调用 `AppLog` ⇒ 无限递归（写入 → 打印 → 再写入）。
 *
 * 每条谓词都配负向自证（同一份谓词喂反例必须判负），防「门禁空转」。
 */
class LogsTabWiringGateTest {

    // ─────────────────── 1. 分区枚举 + 双语标题 ───────────────────

    @Test
    fun `日志分区必须登记到枚举`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        assertTrue(
            "SettingsSection 里找不到 `LOGS(R.string.…, Icons…)` —— 枚举写法变了或没登记",
            Regex("""^\s{4}LOGS\(R\.string\.\w+,\s*Icons""", RegexOption.MULTILINE).containsMatchIn(enumSrc),
        )
    }

    @Test
    fun `日志分区标题必须中英齐备`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        val m = Regex("""LOGS\(R\.string\.(\w+),""").find(enumSrc)
        assertTrue("没抽到 LOGS 的 titleRes", m != null)
        val key = m!!.groupValues[1]
        for (dir in listOf("values", "values-en")) {
            val xml = resXml(dir)
            assertTrue("$dir/strings.xml 缺 `$key`（编译能过、渲染会崩）", xml.contains("""name="$key""""))
        }
    }

    // ─────────────────── 2. SettingsScreen 渲染 + 焦点登记 ───────────────────

    @Test
    fun `SettingsScreen 必须渲染日志分区并登记焦点请求器`() {
        val screen = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        val hits = Regex("""SettingsSection\.LOGS\b""").findAll(screen).count()
        assertTrue(
            "`SettingsSection.LOGS` 只出现 $hits 次（需 when 分支 + navFocusRequesters 各一处，G3 口径 ≥2）",
            hits >= 2,
        )
        assertTrue(
            "SettingsScreen 没有调用 `LogsSettingsSection(` —— 分支可能渲染了空 item",
            screen.contains("LogsSettingsSection("),
        )
    }

    @Test
    fun `进入日志分区必须刷新一次`() {
        val screen = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        // 与 DATA 分区同款：LaunchedEffect(displaySection) 里按分区触发刷新
        assertTrue(
            "没有在进入 LOGS 分区时调用刷新（日志页会永远显示进页前的旧快照）",
            Regex("""displaySection == SettingsSection\.LOGS[\s\S]{0,160}onRefreshLogs""").containsMatchIn(screen),
        )
    }

    // ─────────────────── 3. SettingsBranch 接线 ───────────────────

    @Test
    fun `SettingsBranch 必须接线日志状态与两个动作`() {
        val branch = stripComments(mainSource("ui/components/branches/SettingsBranch.kt"))
        for (needle in listOf("logs = logs", "onRefreshLogs =", "onClearLogs =")) {
            assertTrue("SettingsBranch 缺 `$needle` —— 日志页拿不到状态或点了没反应", branch.contains(needle))
        }
        assertTrue(
            "SettingsBranch 没有收集 viewModel.logs",
            branch.contains("viewModel.logs.collectAsState"),
        )
    }

    // ─────────────────── 4. AppLog 双写 ───────────────────

    @Test
    fun `AppLog 的 warn 与 error 都必须落盘`() {
        val src = stripComments(mainSource("util/AppLog.kt"))
        for (sig in listOf("fun w(", "fun e(")) {
            assertTrue("AppLog.$sig 方法体里没有 LogStore.append( —— 该类日志不会进日志页", persistedIn(src, sig))
        }
    }

    // ─────────────────── 5. 崩溃落盘 ───────────────────

    @Test
    fun `未捕获异常必须落盘`() {
        val app = stripComments(mainSource("NasMusicApp.kt"))
        assertTrue(
            "NasMusicApp 没有安装 UncaughtExceptionHandler —— 闪退不会留痕",
            app.contains("setDefaultUncaughtExceptionHandler"),
        )
        assertTrue(
            "崩溃处理器里没有 LogStore.appendBlocking( —— 异步写来不及落盘就进程终止了",
            app.contains("LogStore.appendBlocking("),
        )
        assertTrue(
            "NasMusicApp 没有初始化 LogStore（onCreate 里缺 LogStore.init(）",
            app.contains("LogStore.init("),
        )
    }

    // ─────────────────── 6. 防递归 ───────────────────

    @Test
    fun `LogStore 不得反向调用 AppLog`() {
        val src = stripComments(mainSource("util/LogStore.kt"))
        assertTrue(
            "LogStore 里出现了 AppLog. 调用 —— 会形成 写入→打印→再写入 的无限递归",
            !src.contains("AppLog."),
        )
    }

    // ─────────────────── 负向自证（同一份谓词喂反例） ───────────────────

    /** 负向 ①：`AppLog` 不写 LogStore 时，[persistedIn] 必须判负。 */
    @Test
    fun `负向自证 AppLog 未接 LogStore 必须判负`() {
        val sample = """
            object AppLog {
                fun w(tag: String, message: String) {
                    android.util.Log.w(tag, message)
                }
                fun e(tag: String, message: String) {
                    android.util.Log.e(tag, message)
                }
            }
        """.trimIndent()
        assertTrue("护栏空转：没接 LogStore 的样例被误判为已落盘", !persistedIn(sample, "fun w("))
        assertTrue("护栏空转：没接 LogStore 的样例被误判为已落盘", !persistedIn(sample, "fun e("))
    }

    /** 负向 ②：`LogStore` 反向调用 `AppLog` 时必须被检出。 */
    @Test
    fun `负向自证 LogStore 反向调用 AppLog 必须被检出`() {
        val sample = "object LogStore { fun append() { AppLog.e(\"LogStore\", \"x\") } }"
        assertTrue("护栏空转：递归调用没被检出", sample.contains("AppLog."))
    }

    /** 负向 ③：`SettingsScreen` 只加枚举不加渲染时必须判负。 */
    @Test
    fun `负向自证 只登记枚举不渲染必须判负`() {
        val sample = "listOf(SettingsSection.LOGS, SettingsSection.DATA).forEach { }"
        assertTrue("护栏空转：没渲染 LogsSettingsSection 的样例被误判通过", !sample.contains("LogsSettingsSection("))
    }

    /** 负向 ④：崩溃处理器缺 appendBlocking 时必须判负。 */
    @Test
    fun `负向自证 崩溃处理器缺同步落盘必须判负`() {
        val sample = """
            Thread.setDefaultUncaughtExceptionHandler { t, e -> previous?.uncaughtException(t, e) }
        """.trimIndent()
        assertTrue("护栏空转：缺 appendBlocking 的样例被误判通过", !sample.contains("LogStore.appendBlocking("))
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 判定某个方法体内是否调用 `LogStore.append(`（剥注释后、按大括号配对取方法体）。
     *
     * 只认「方法体」而不是全文：`AppLog` 的 KDoc 里也会提到 LogStore，
     * 全文匹配会让注释把门禁骗过去（T14 的 `Desc-exempt` 就栽在这种错位上）。
     */
    private fun persistedIn(src: String, signature: String): Boolean {
        val body = bodyOf(src, signature) ?: return false
        return body.contains("LogStore.append(")
    }

    /** 取 `signature` 起第一个 `{` 到配对 `}` 的正文；找不到返回 null。 */
    private fun bodyOf(src: String, signature: String): String? {
        val start = src.indexOf(signature)
        if (start < 0) return null
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）",
            found != null,
        )
        return found!!.readText(Charsets.UTF_8)
    }

    private fun resXml(valuesDir: String): String {
        val candidates = listOf(
            File("src/main/res/$valuesDir/strings.xml"),
            File("app/src/main/res/$valuesDir/strings.xml"),
            File("../app/src/main/res/$valuesDir/strings.xml"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $valuesDir/strings.xml", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    /**
     * 剥注释（状态机，支持嵌套块注释 / 原始字符串 / 字符字面量）。
     * 与 `SettingsGroupCoverageTest.stripComments` 同一口径的最小实现：
     * 门禁判据必须建立在「真代码」上，否则 KDoc 里的一句话就能把红变绿。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var depth = 0
        var inStr = false
        var inRaw = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; sb.append('\n') } else sb.append(' ')
                    i++
                }
                inBlock -> {
                    if (c == '/' && n == '*') { depth++; sb.append("  "); i += 2 }
                    else if (c == '*' && n == '/') {
                        depth--; sb.append("  "); i += 2
                        if (depth == 0) inBlock = false
                    } else { sb.append(if (c == '\n') '\n' else ' '); i++ }
                }
                inRaw -> {
                    val run = quoteRun(src, i)
                    if (run >= 3) { repeat(run) { sb.append('"') }; inRaw = false; i += run }
                    else { sb.append(c); i++ }
                }
                inStr -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
                    else { if (c == '"') inStr = false; i++ }
                }
                inChar -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
                    else { if (c == '\'') inChar = false; i++ }
                }
                c == '/' && n == '/' -> { inLine = true; sb.append("  "); i += 2 }
                c == '/' && n == '*' -> { inBlock = true; depth = 1; sb.append("  "); i += 2 }
                quoteRun(src, i) >= 3 -> { sb.append("\"\"\""); inRaw = true; i += 3 }
                else -> {
                    if (c == '"') inStr = true
                    if (c == '\'') inChar = true
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }

    private fun quoteRun(s: String, from: Int): Int {
        var k = 0
        while (from + k < s.length && s[from + k] == '"') k++
        return k
    }
}
