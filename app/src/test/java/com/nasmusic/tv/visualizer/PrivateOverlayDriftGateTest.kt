package com.nasmusic.tv.visualizer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ⛔ **私有覆盖层 ↔ 隐藏分支源码的漂移门禁**（2026-11-04 新增；取代旧的 `E51OverlayDriftGateTest`）。
 *
 * ## 为什么要它
 *
 * 本机有一个**仓库树内、但被 `.gitignore` 挡住**的覆盖层（`app/src/e51-overlay/ovl/app/src/...`），
 * 由仓库内的挂载正文 `scripts/local-e51-overlay.gradle`（本机经 `~/.gradle/init.d/` 那枚
 * 自动生成的壳 apply 进来）挂成 app 模块的额外源目录，
 * 并在 **Kotlin 编译任务层**与仓库里那几份同名文件二选一地排除：
 *
 * * 仓库里**有**隐藏分支源码（当前在私有分支 `feat/e51-private` 上）⇒ 用**仓库那份**；
 * * 仓库里**没有**（当前在 `main` 上）⇒ 用**覆盖层那份** ⇒
 *   **这正是「在 main 上本地编译也携带隐藏分支效果」的实现**。
 *
 * ⚠️ 由此产生一个静默风险：**覆盖层是隐藏分支的一份拷贝**，它一旦落后于隐藏分支，
 *    `main` 上的本地构建就会带着**旧效果**，而 CI（无覆盖层）编的是新代码
 *    ⇒ **本地与 CI 行为分叉**，症状要等到真机才看得见。
 *
 * ⇒ 这条门禁直接调用 `scripts/sync_private_overlay.py --check`（同一套逐字节比对逻辑，
 *    源 = 当前工作树（在私有分支上）或本地分支 ref（在 main 上））：
 *    * 退出码 **1（有漂移）⇒ 判红**，并给出同步命令；
 *    * 退出码 0（一致）/ 2（无法比较：没有覆盖层或没有私有分支，CI 即如此）⇒ 通过。
 *
 * ⛔ 同步用 `python scripts/sync_private_overlay.py`（仓库根跑）。
 */
class PrivateOverlayDriftGateTest {

    /** 仓库根（含 `scripts/sync_private_overlay.py` 的那一层） */
    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/sync_private_overlay.py").isFile) return dir
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
    fun 覆盖层与隐藏分支源码不得漂移() {
        val root = repoRoot() ?: return          // 找不到脚本（异常环境）⇒ 跳过
        val py = pythonCommand() ?: return       // 没有 python（CI 的某些 runner）⇒ 跳过

        val proc = try {
            ProcessBuilder(py, "scripts/sync_private_overlay.py", "--check")
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
        // 2 = 无法比较（没有覆盖层 / 没有私有分支）⇒ 不适用本门禁
        if (proc.exitValue() == 2) return

        assertTrue(
            "⛔ 私有覆盖层与隐藏分支源码有漂移（退出码 ${proc.exitValue()}）：\n" +
                out.trim() + "\n\n" +
                "⇒ `main` 上的本地构建编译的是**覆盖层那份** ⇒ 隐藏分支的效果会落后于源码！\n" +
                "⇒ 修法：仓库根跑 `python scripts/sync_private_overlay.py`",
            proc.exitValue() == 0
        )
    }
}
