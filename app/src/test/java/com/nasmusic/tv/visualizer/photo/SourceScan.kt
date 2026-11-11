package com.nasmusic.tv.visualizer.photo

import java.io.File

/**
 * 源码扫描的**共享工具**（门禁用）
 *
 * ## 为什么单独抽出来
 *
 * 「剥掉注释再扫」这件事有两处门禁都要用：
 * - [PhotoTransitionClipGateTest]：`clipPath` 红线
 * - [PhotoTransitionP2Test]：零 shader 红线
 *
 * 两边各抄一份 60 行的剥注释器 ⇒ 改一处漏一处 ⇒ 抽到这里共用。
 */
internal object SourceScan {

    /**
     * 剥掉注释（块注释可嵌套 + 行注释 + 字符串 / 字符字面量），只留可执行代码。
     *
     * ⚠️ 必须保留**原始换行**（断言行号要能对上），所以逐字符重建而不是 `lines().joinToString`。
     */
    fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var depth = 0
        var inStr = false
        var inChar = false
        var inRawStr = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                depth > 0 -> {
                    if (c == '/' && next == '*') { depth++; i++; sb.append(' ') }
                    else if (c == '*' && next == '/') { depth--; i++; sb.append(' ') }
                    else sb.append(if (c == '\n') '\n' else ' ')
                }
                inRawStr -> {
                    sb.append(c)
                    if (c == '"' && text.startsWith("\"\"\"", i)) {
                        sb.append("\"\"")
                        i += 2
                        inRawStr = false
                    }
                }
                inStr -> {
                    sb.append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> { sb.append(text[i + 1]); i++ }
                        c == '"' -> inStr = false
                    }
                }
                inChar -> {
                    sb.append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> { sb.append(text[i + 1]); i++ }
                        c == '\'' -> inChar = false
                    }
                }
                c == '/' && next == '*' -> { depth = 1; i++; sb.append("  ") }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { sb.append(' '); i++ }
                    if (i < text.length) sb.append('\n')
                    i++
                    continue
                }
                text.startsWith("\"\"\"", i) -> { sb.append("\"\"\""); i += 2; inRawStr = true }
                c == '"' -> { inStr = true; sb.append(c) }
                c == '\'' -> { inChar = true; sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 定位 `app/src/main/java`（沿用仓内既有写法：AGP 单测的 `user.dir` 是模块目录）。
     *
     * ⚠️ 定位失败时**直接失败**而不是跳过 —— 否则门禁会被静默禁用。
     */
    fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (true) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            val alt = File(dir, "src/main/java")
            if (alt.isDirectory) return alt
            dir = dir.parentFile ?: error("找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
        }
    }

    /** `com/nasmusic/tv/visualizer` 下的全部 Kotlin 源文件（递归） */
    fun visualizerFiles(): List<File> {
        val root = File(mainSourceRoot(), "com/nasmusic/tv/visualizer")
        check(root.isDirectory) { "找不到 visualizer 目录：${root.absolutePath}" }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** 相对 `com/nasmusic/tv/visualizer` 的路径（用 `/` 分隔） */
    fun relPath(file: File): String =
        file.relativeTo(File(mainSourceRoot(), "com/nasmusic/tv/visualizer")).invariantSeparatorsPath
}
