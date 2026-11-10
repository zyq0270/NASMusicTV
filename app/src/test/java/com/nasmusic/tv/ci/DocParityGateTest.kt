package com.nasmusic.tv.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⛔ **两分支 `docs/` 逐字节一致性门禁**（2026-11-10 新增；所有者裁决
 * 「两分支 文档类的内容，应该保持一致，例如：technical-overview.md，应该都一样才行」）。
 *
 * ## 为什么要它
 *
 * 两个分支各自独立追加 `docs/technical-overview.md` 的 §10 ⇒ **同号不同内容**
 * （§10.374–§10.378 五节各有两版：main = 代码审查 + main 侧落地 + 名册约定 + 编号定案 + 公开分发裁决；
 * 私有分支 = 街机合集三轮 + 编号定案 + 公开分发裁决）⇒ 文档在两分支**对不上**。
 *
 * AGENTS.md 给的处置是「**写一份 → 覆盖到私有分支**」—— 但「靠自觉」守不住，
 * 一轮改文档就可能又岔开 ⇒ 本门禁直接调用 `scripts/check_doc_parity.py`
 * （同一套逐字节比对逻辑，源 = 两个分支 ref 的 `docs/` 树）：
 *
 * * 退出码 **1（有差异）⇒ 判红**，并给出修法；
 * * 退出码 0（一致）/ 2（无法比较：本机没有私有分支 ref —— CI 即如此）⇒ 通过。
 *
 * ## 白名单
 *
 * 脚本内置 7 项**例外**（E51/E52 的**上游衍生件**：原型 HTML 内含移植件本体、
 * 上游城市注册表、上游算法的逐位数值向量与对账明细）⇒ 它们**只在私有分支**、⛔ 不进公开仓库。
 * 判据见 AGENTS.md「文档类资料是全局可公开的」：文档**不因为「只在某个分支存在」就自动私有** ——
 * 判据是「是否含上游代码 / 是上游代码的翻译件或派生物本体」；我们自己的调研与研究**可以**公共发布。
 *
 * ⛔ 同步用 `python scripts/check_doc_parity.py`（仓库根跑）。
 */
class DocParityGateTest {

    /** 仓库根（含 `scripts/check_doc_parity.py` 的那一层） */
    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/check_doc_parity.py").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** 能用的 python 解释器（Windows 是 `python`，CI 是 `python3`） */
    private fun pythonCommand(): String? {
        for (cmd in listOf("python", "python3")) {
            try {
                val p = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd
            } catch (_: Exception) {
                // 试下一个
            }
        }
        return null
    }

    @Test
    fun 两分支文档必须逐字节一致() {
        val root = repoRoot() ?: return          // 找不到脚本（异常环境）⇒ 跳过
        val py = pythonCommand() ?: return       // 没有 python（CI 的某些 runner）⇒ 跳过

        val proc = try {
            ProcessBuilder(py, "scripts/check_doc_parity.py", "--check")
                .directory(root)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return
        }
        val out = try {
            proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            ""
        }
        val finished = proc.waitFor(180, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return
        }
        // 2 = 无法比较（没有私有分支 ref）⇒ 不适用本门禁
        if (proc.exitValue() == 2) return

        assertTrue(
            "⛔ 两分支的 docs/ 不一致（退出码 ${proc.exitValue()}）：\n" +
                out.trim() + "\n\n" +
                "⇒ 修法（AGENTS.md「写一份 → 覆盖到私有分支」，⛔ 不要 cherry-pick + 顺延编号）：\n" +
                "   1) 在权威侧改好那一份；2) `git checkout <另一分支> -- <路径>` 覆盖过去；\n" +
                "   3) 复跑 `python scripts/check_doc_parity.py` ⇒ 必须 0 差异。",
            proc.exitValue() == 0
        )
    }
}
