package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⛔ **覆盖层挂载清单一致性门禁**（2026-11-10 新增）。
 *
 * ## 为什么要它
 *
 * 覆盖层挂载靠**两份清单**同时描述同一批隐藏分支文件：
 *
 * * `scripts/local-e51-overlay.gradle` 的 `E51_TRACKED` —— Gradle 侧：决定在 Kotlin 编译任务层
 *   **排除哪几份同名文件**（二选一：仓库那份 vs 覆盖层那份）；
 * * `scripts/sync_private_overlay.py` 的 `SHADOWED_JAVA` —— Python 侧：决定**同步哪几份文件**
 *   到覆盖层。
 *
 * ⚠️ 两份清单只要有一份多了一项 / 少了一项 ⇒ 覆盖层内容与排除规则就对不上：
 *
 * * 少了 ⇒ 覆盖层里那份文件**同时**进 Kotlin 编译 ⇒ `Redeclaration`（构建直接炸，能立刻发现）；
 * * 多了 ⇒ 覆盖层同步了一份**不该同步**的文件（可能把上游衍生源码带进本该只有自建件的地方）。
 *
 * ⇒ 这条门禁把「两份清单必须逐项一致」从**注释里的口头约定**变成**机器守住的不变量**。
 *
 * ⛔ 改其中一份必须改另一份，否则本门禁判红。
 */
class OverlaySourceListGateTest {

    /** 仓库根（含 `scripts/sync_private_overlay.py` 的那一层） */
    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/sync_private_overlay.py").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** 读文本文件（UTF-8） */
    private fun readText(file: File): String? = try {
        file.readText(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /** 从 Gradle 正文里取出 `E51_TRACKED` 的条目（单引号字符串） */
    private fun parseGradleTracked(text: String): List<String> {
        val start = text.indexOf("def E51_TRACKED = [")
        assertTrue("scripts/local-e51-overlay.gradle 里找不到 `def E51_TRACKED = [`", start >= 0)
        val end = text.indexOf("]", start)
        assertTrue("scripts/local-e51-overlay.gradle 的 E51_TRACKED 列表没有闭合", end > start)
        val body = text.substring(start, end)
        return Regex("'([^']+)'").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** 从 Python 同步脚本里取出 `SHADOWED_JAVA` 的条目（相对 app/src/main/java/com/nasmusic/tv） */
    private fun parsePythonShadowed(text: String): List<String> {
        val start = text.indexOf("SHADOWED_JAVA = [")
        assertTrue("scripts/sync_private_overlay.py 里找不到 `SHADOWED_JAVA = [`", start >= 0)
        val end = text.indexOf("]", start)
        assertTrue("scripts/sync_private_overlay.py 的 SHADOWED_JAVA 列表没有闭合", end > start)
        val body = text.substring(start, end)
        return Regex("\\(\\s*'([^']+)'\\s*,\\s*'(dir|file)'\\s*\\)")
            .findAll(body)
            .map { m ->
                val rel = m.groupValues[1]
                if (m.groupValues[2] == "dir") "com/nasmusic/tv/$rel/**" else "com/nasmusic/tv/$rel"
            }
            .toList()
    }

    @Test
    fun 覆盖层挂载清单两处必须一致() {
        val root = repoRoot() ?: return   // 找不到仓库根（异常环境）⇒ 跳过

        val gradleFile = File(root, "scripts/local-e51-overlay.gradle")
        val pythonFile = File(root, "scripts/sync_private_overlay.py")
        assertTrue("仓库里缺少 scripts/local-e51-overlay.gradle（覆盖层挂载正文）", gradleFile.isFile)
        assertTrue("仓库里缺少 scripts/sync_private_overlay.py", pythonFile.isFile)

        val gradleText = readText(gradleFile) ?: return
        val pythonText = readText(pythonFile) ?: return

        val fromGradle = parseGradleTracked(gradleText)
        val fromPython = parsePythonShadowed(pythonText)

        assertTrue("scripts/local-e51-overlay.gradle 的 E51_TRACKED 是空的", fromGradle.isNotEmpty())
        assertTrue("scripts/sync_private_overlay.py 的 SHADOWED_JAVA 是空的", fromPython.isNotEmpty())

        assertEquals(
            "⛔ 覆盖层挂载清单两处不一致！\n" +
                "  · scripts/local-e51-overlay.gradle 的 E51_TRACKED = ${fromGradle.sorted()}\n" +
                "  · scripts/sync_private_overlay.py 的 SHADOWED_JAVA = ${fromPython.sorted()}\n" +
                "⇒ 改其中一份必须改另一份（否则覆盖层内容与 Kotlin 任务层的排除规则对不上）。",
            fromGradle.sorted(),
            fromPython.sorted()
        )
    }

    @Test
    fun 覆盖层挂载正文不得被git忽略() {
        val root = repoRoot() ?: return
        val rel = "scripts/local-e51-overlay.gradle"

        // `git check-ignore` 有输出 = 被忽略 ⇒ 门禁正文会随公开仓库丢失 ⇒ 判红
        val proc = try {
            ProcessBuilder("git", "check-ignore", "-q", rel)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return    // 没有 git（某些 runner）⇒ 跳过
        }
        val finished = proc.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return
        }
        assertTrue(
            "⛔ $rel 被 .gitignore 挡住了 ⇒ 覆盖层挂载正文不会随仓库发布，" +
                "换机器 / 换克隆就再也挂不上覆盖层。",
            proc.exitValue() != 0
        )
    }
}
