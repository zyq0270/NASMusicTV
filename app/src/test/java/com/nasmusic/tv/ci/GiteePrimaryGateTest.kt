package com.nasmusic.tv.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「以 Gitee 为主、GitHub 冻结」仓库策略的源码门禁（§10.408，2026-11-17 所有者裁定）。
 *
 * ## 为什么必须有
 *
 * 2026-11-16 的 `sync_gitee_mirror.ps1` 默认 -Action Trigger：触发后 Gitee 会从 GitHub 拉取并
 * **覆盖**分支与标签。2026-11-17 实测 Gitee main=`a0d47c8`、GitHub main=`f83df51`（落后 6 提交），
 * Gitee 还有 `v2.39.20` tag + Release/APK ⇒ 一旦误触，较新的代码和发行版关联会被较旧的 GitHub 覆盖，
 * 属数据损失级操作。本门禁把「默认只读 + 覆盖动作需显式解锁 + Gitee 发布通道存在」钉在脚本文本上。
 *
 * ## 断言（全部语句级，不靠全文否定，避免注释自伤）
 *
 * | # | 内容 |
 * |---|---|
 * | L1 | 默认 Action 必须是 `Verify`（只读），不是 `Trigger` |
 * | L2 | `Trigger` / `CreateMirror` / `Push` 三个覆盖动作必须受 `-AllowLegacyGitHubSync` 护栏控制 |
 * | L3 | 必须存在本地 → Gitee 的 `PublishLocal` 动作，且它不得反向推 GitHub |
 * | L4 | `Verify` 必须把「Gitee==本地、GitHub 落后」判为预期状态 |
 * | L5 | `gitee-mirror-setup.md` 与两个 README 的下载入口必须以 Gitee 为准 |
 * | L6 | 负向自证：喂给同一谓词一段旧脚本文本必须判负，防止断言空转 |
 * | L7 | 任何本地 push/tag 前必须校验脚本所属仓库是干净、非 detached 的 main |
 * | L8 | 发布脚本行为测试（私有分支/detached/脏树/APK/哈希/私有内容）必须通过 |
 *
 * ⚠️ L1–L7 只读文本，不执行 PowerShell、不访问网络；L8 调用 Python 行为测试，
 * 测试自身使用临时仓库与假 Git/网络边界，不接触真实远端。
 */
class GiteePrimaryGateTest {

    @Test
    fun `默认动作必须是只读Verify而不是触发覆盖`() {
        val script = mirrorScript()
        assertTrue("脚本里找不到 ValidateSet —— 可能读错文件", script.contains("ValidateSet"))
        assertTrue(
            "默认 Action 不再是 Verify。\n" +
                "后果：不带参数运行脚本就会触发 GitHub → Gitee 镜像，用落后 6 个提交的 GitHub 覆盖 Gitee。\n" +
                "正确口径见 docs/technical-overview.md §10.408。",
            script.lines().any { it.trim() == "[string] \$Action = 'Verify'," },
        )
        assertFalse(
            "默认 Action 仍是 Trigger —— 数据损失级回归",
            script.lines().any { it.trim() == "[string] \$Action = 'Trigger'," },
        )
    }

    @Test
    fun `三个覆盖动作必须受显式解锁开关控制`() {
        val script = mirrorScript()
        assertTrue("缺少 AllowLegacyGitHubSync 开关定义", script.contains("[switch] \$AllowLegacyGitHubSync"))
        assertTrue(
            "覆盖动作护栏消失。Trigger/CreateMirror/Push 会用 GitHub 旧内容覆盖 Gitee，必须显式解锁。",
            script.contains("@('Trigger', 'CreateMirror', 'Push') -contains \$Action"),
        )
        assertTrue(
            "护栏必须检查 -not \$AllowLegacyGitHubSync，否则等于没有护栏",
            script.contains("-not \$AllowLegacyGitHubSync -and"),
        )
        assertTrue("护栏必须实际抛错（throw），不能只写注释", script.contains("throw \"策略护栏"))
    }

    @Test
    fun `必须存在本地到Gitee的发布动作且不反向推GitHub`() {
        val script = mirrorScript()
        assertTrue("缺少 PublishLocal 动作 —— Gitee 为主后没有发布通道", script.contains("'PublishLocal'"))
        assertTrue(
            "PublishLocal 必须推送 preflight 锁定的精确 SHA，不得推送可变分支 ref 或任意 HEAD",
            script.contains("push gitee \"\${publishHead}:refs/heads/main\""),
        )
        assertFalse(
            "危险回归：PublishLocal 又恢复了 HEAD:refs/heads/main，可能把 detached/私有分支内容推到公开 main",
            script.contains("HEAD:refs/heads/main"),
        )
        assertTrue(
            "PublishLocal 必须声明不碰 GitHub（注释或输出均可）",
            script.contains("不访问 GitHub") || script.contains("不改 GitHub") || script.contains("未被触碰"),
        )
        assertFalse(
            "PublishLocal 块里不得出现 push origin —— 那会违反「暂时不推 GitHub」",
            publishLocalBlock(script).contains("push origin"),
        )
    }

    @Test
    fun `Verify必须把Gitee领先GitHub判为预期状态`() {
        val script = mirrorScript()
        val verify = verifyBlock(script)
        assertTrue("Verify 块里必须比对本地 HEAD", verify.contains("rev-parse"))
        assertTrue("Verify 块必须打印三端 SHA", verify.contains("GitHub") && verify.contains("Gitee") && verify.contains("本地"))
        assertTrue(
            "Gitee==本地且 GitHub 落后必须判为预期状态，不得提示触发同步",
            verify.contains("预期状态"),
        )
        assertFalse(
            "Verify 块不得再提示触发镜像同步 —— 那会覆盖 Gitee",
            verify.contains("触发一次同步"),
        )
    }

    @Test
    fun `文档与README的下载入口必须以Gitee为准`() {
        val guide = File("../docs/gitee-mirror-setup.md").takeIf { it.isFile }
            ?: File("docs/gitee-mirror-setup.md")
        assertTrue("找不到 docs/gitee-mirror-setup.md", guide.isFile)
        val guideText = guide.readText()
        assertTrue("发布指引必须写明所有者裁定", guideText.contains("以 Gitee 为主"))
        assertTrue("发布指引必须写明 GitHub 冻结", guideText.contains("GitHub") && guideText.contains("冻结"))
        assertTrue("发布指引必须给出 PublishLocal 流程", guideText.contains("-Action PublishLocal"))
        assertTrue("发布指引必须警告不得点「更新导入」", guideText.contains("更新导入"))

        for (name in listOf("README.md", "README_EN.md")) {
            val readme = File("../$name").takeIf { it.isFile } ?: File(name)
            assertTrue("找不到 $name", readme.isFile)
            val text = readme.readText()
            assertTrue(
                "$name 的下载地址/徽章仍指向 GitHub Releases —— 应改为 Gitee",
                text.contains("gitee.com/hxzhang2000/NASMusicTV/releases"),
            )
            assertFalse(
                "$name 仍把 GitHub Releases 作为下载入口",
                text.contains("https://github.com/hxzhang2000/NASMusicTV/releases"),
            )
        }
    }

    @Test
    fun `负向自证旧脚本必须被判负`() {
        // 同一谓词喂给 2026-11-16 的旧脚本形态（默认 Trigger、无 PublishLocal、无「预期状态」）⇒ 必须判负。
        val legacy = """
            param(
              [ValidateSet('Trigger','Status','Verify','CreateMirror','CheckToken','Push')]
              [string] ${'$'}Action = 'Trigger'
            )
            switch (${'$'}Action) {
              'Trigger' { Invoke-GiteeApi -Method 'POST' -Path '/remote_mirror/pull' }
              'Verify' { Write-Host '不一致：Gitee 端还没同步到最新'; Write-Host '触发一次同步' }
            }
        """.trimIndent()
        val predicate = { text: String ->
            text.lines().any { it.trim() == "[string] \$Action = 'Verify'," } &&
                text.contains("'PublishLocal'") &&
                text.contains("预期状态")
        }
        assertFalse(
            "负向自证失败：旧脚本竟被判为合规，说明 L1/L3/L4 谓词空转",
            predicate(legacy),
        )
        // 正向自证：当前脚本喂同一谓词必须为真
        assertTrue("正向自证失败：当前脚本未被判为合规", predicate(mirrorScript()))
    }

    @Test
    fun `任何本地推送标签前都必须先校验干净非detached的main`() {
        val script = mirrorScript()
        assertTrue("缺少发布前置校验函数", script.contains("function Assert-PublishSafeMain"))
        assertTrue(
            "PublishLocal 与显式解锁的遗留 Push 都必须先过发布前置校验",
            script.contains("if (@('PublishLocal', 'Push') -contains \$Action) {") &&
                script.contains("    \$publishHead = Assert-PublishSafeMain"),
        )
        assertTrue("必须拒绝 detached HEAD", script.contains("git symbolic-ref --quiet --short HEAD"))
        assertTrue("必须检查 HEAD 与 refs/heads/main 一致", script.contains("git rev-parse --verify refs/heads/main"))
        assertTrue("必须检查含未跟踪文件的工作树洁净度", script.contains("git status --porcelain=v1 --untracked-files=all"))
        assertTrue(
            "必须校验脚本所属仓库，不能误校验调用者 CWD 的另一个仓库",
            script.contains("Push-Location \$repoRoot"),
        )
        assertTrue("标签必须只从 preflight 锁定的精确 SHA 选取", script.contains("git tag --merged \$publishHead"))
        assertFalse("不得按可变分支 ref 选择发布标签", script.contains("git tag --merged refs/heads/main"))
        assertFalse("不得再按 GitHub 的 origin/main 选择发布标签", script.contains("git tag --merged \$base"))
    }

    @Test
    fun `发布安全行为测试必须通过`() {
        val root = checkNotNull(repoRoot()) { "找不到仓库根，发布安全行为测试不能静默跳过" }
        val python = checkNotNull(pythonCommand()) { "找不到 Python，发布安全行为测试不能静默跳过" }
        val process = try {
            ProcessBuilder(
                python, "-m", "unittest", "discover",
                "-s", "scripts/tests", "-p", "test_release_safety.py", "-v",
            )
                .directory(root)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw AssertionError("发布安全行为测试无法启动", e)
        }
        val output = try {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            throw AssertionError("发布安全行为测试输出无法读取", e)
        }
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue("发布安全行为测试超时（180 秒）", false)
        }
        assertTrue(
            "发布安全行为测试失败（退出码 ${process.exitValue()}）：\n" +
                output.trim() + "\n\n" +
                "⇒ 复跑：python -m unittest discover -s scripts/tests -p test_release_safety.py -v",
            process.exitValue() == 0,
        )
    }

    /** 仓库根（同时含发布脚本与 Python 行为测试的那一层）。 */
    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "scripts/tests/test_release_safety.py").isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** Windows 用 python，CI runner 常用 python3。 */
    private fun pythonCommand(): String? {
        for (command in listOf("python", "python3")) {
            try {
                val process = ProcessBuilder(command, "--version").redirectErrorStream(true).start()
                if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) return command
            } catch (_: Exception) {
                // 试下一个解释器
            }
        }
        return null
    }

    private fun mirrorScript(): String {
        val f = File("../scripts/sync_gitee_mirror.ps1").takeIf { it.isFile }
            ?: File("scripts/sync_gitee_mirror.ps1")
        assertTrue(
            "找不到 scripts/sync_gitee_mirror.ps1（user.dir=${File("").absolutePath}）",
            f.isFile,
        )
        return f.readText()
    }

    /** 取 switch 中某个动作块到下一个动作标签之间的文本（语句级扫描，避免注释自伤）。 */
    private fun blockOf(text: String, label: String): String {
        val start = text.indexOf("    '$label' {")
        if (start < 0) return ""
        val rest = text.substring(start + 1)
        val next = Regex("\n    '[^']+' \\{").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, next)
    }

    private fun publishLocalBlock(text: String): String = blockOf(text, "PublishLocal")

    private fun verifyBlock(text: String): String = blockOf(text, "Verify")
}
