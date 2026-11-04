package com.nasmusic.tv.visualizer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⛔ **公开仓库的「上游原件」卫生门禁**（2026-11-04 新增）。
 *
 * ## 为什么要有这条门
 *
 * 本仓库是 **PUBLIC**。`app/src/main` 里一旦混进上游项目的 `.js` / `.mjs` **原件**，
 * 它会被 `mergeDebugAssets` / `mergeReleaseAssets` **原样打进 APK**
 * ⇒ 上游原件随公开包外发（而上游无任何开源授权）。
 * （`globe-upstream` 那次教训：依赖装错目录，APK 凭空多了 23 MB —— 见 AGENTS.md。）
 *
 * 2026-10-23 的历史剥离把已入库的 56 个移植件路径从全部历史里删掉了，
 * 但**没有留下任何门禁**来保证它们不会再进来 ⇒ 本门禁补上这一段。
 *
 * ## 规则
 *
 * `app/src/main` 里的 `.js` / `.mjs` 只允许两类**自有**脚本：
 *
 * | 允许 | 是什么 |
 * |---|---|
 * | `assets/globe/` | E41 地球仪的运行时资产（three.js 的 ES5 产物，§11 既有事实） |
 * | `assets/lx/` | 洛雪自定义源宿主的 JS 垫片 —— **本项目自己写的**协议层（与 loficities.com 无关） |
 *
 * 其余一律判为「上游原件混入」⇒ **构建失败**。
 *
 * ⛔ 私有分支 `feat/e51-private` 上 E51 的上游原件基线在 `app/src/lofi-upstream/`
 *    （gitignored，**不在** `app/src/main`）⇒ 本门禁在两个分支上都成立。
 */
class UpstreamOriginalsHygieneTest {

    private fun mainSourceDir(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("找不到 src/main（user.dir=" + File("").absolutePath + "）")

    @Test
    fun `app_src_main 里没有上游原件`() {
        val leaked = mainSourceDir().walkTopDown()
            .filter { it.isFile && it.extension in setOf("js", "mjs") }
            .map { it.invariantSeparatorsPath }
            .toList()
        val allowed = listOf(
            Regex("""assets[/\\]globe[/\\]"""),
            Regex("""assets[/\\]lx[/\\]"""),
        )
        val offenders = leaked.filterNot { p -> allowed.any { it.containsMatchIn(p) } }
        assertTrue(
            "上游原件混进了 app/src/main（会被打进 APK）:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
