package com.nasmusic.tv.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.39.8「网络音乐源总开关」（方案 §7.5 / §7.6 / §7.7）的**确定性接线门**（源码文本，零时序）。
 *
 * 为什么不用行为测试：这几条判据的真实故障形态都是「编译过、lint 过、全仓其余测试全绿」的
 * 静默失效 —— 例如构造期忘了判开关（关掉的源照样注册）、默认源被关后没人自愈
 * （搜索能用但歌单页空空）。用源码文本把**形状**钉住，比等真机发现便宜得多。
 *
 * | # | 钉住的东西 | 漏了会是什么症状 |
 * |---|---|---|
 * | G1 | `NasMusicApp` 构造期按 `bootSettings` 决定注册（meting / jamendo），且**不读**同步镜像 | 冷启动竞态下镜像还是默认值 ⇒ 关掉的源被注册回来 |
 * | G2 | `repairDefaultNetworkSource`：无源时**先返回 null 再写盘**，有源时按优先级切 | 全关时把默认源写成空 / 写了一个不存在的源 |
 * | G3 | 卡⑤ 候选 = `musicSources.filter { enabled }`；全关时给红字引导 | 关掉的源还能被选成默认源；全关时显示空白 |
 * | G4 | 卡③ 的索引区块**不再挂外层 `if`** | 「百度网盘卡正常、索引区块凭空消失」 |
 * | G5 | 负向自证：同一套判据在破坏样本上必须判负 | 门不得空转 |
 */
class NetworkSourceSwitchGateTest {

    // ─────────────────── G1：构造期按开关注册 ───────────────────

    @Test
    fun `G1 构造期必须按开关决定注册`() {
        val app = flatten(mainSource("NasMusicApp.kt"))
        assertTrue("G1 空转自证：读到的确实是 NasMusicApp", app.contains("class NasMusicApp"))
        assertTrue(
            "G1：meting 必须由启动读取的偏好决定是否注册 ——\n" + registrationProblems(app).joinToString("\n"),
            registrationProblems(app).isEmpty()
        )
    }

    // ─────────────────── G2：默认源自愈 ───────────────────

    @Test
    fun `G2 默认源自愈必须无源先返回再写盘`() {
        val app = flatten(mainSource("NasMusicApp.kt"))
        assertTrue(
            "G2：默认源自愈形状不合规 ——\n" + repairProblems(app).joinToString("\n"),
            repairProblems(app).isEmpty()
        )
    }

    // ─────────────────── G3：两份卡片的形状 ───────────────────

    @Test
    fun `G3 卡四卡五必须按开关与启用态渲染`() {
        val section = flatten(mainSource("ui/screens/settings/SourcesSettingsSection.kt"))
        assertTrue("G3 空转自证：读到的确实是 SourcesSettingsSection",
            section.contains("fun SourcesSettingsSection"))
        assertTrue(
            "G3：卡片形状不合规 ——\n" + sectionProblems(section).joinToString("\n"),
            sectionProblems(section).isEmpty()
        )
    }

    // ─────────────────── G4：索引区块不挂外层 if ───────────────────

    @Test
    fun `G4 索引区块不得再挂外层 if`() {
        val section = flatten(mainSource("ui/screens/settings/SourcesSettingsSection.kt"))
        assertTrue(
            "G4：索引区块又被包进 `if (actions.onRebuildBaiduIndex != null)` ⇒ " +
                "回调为 null 时整块消失（方案 §7.0 合并①）。按钮改成 `?.invoke()`。",
            !section.contains("if (actions.onRebuildBaiduIndex != null)")
        )
        assertTrue("G4：重建索引按钮必须仍可点（回调空安全）",
            section.contains("actions.onRebuildBaiduIndex?.invoke()"))
    }

    // ─────────────────── G5：负向自证（门不得空转） ───────────────────

    @Test
    fun `G5 破坏样本必须被同一套判据拦下`() {
        val app = flatten(mainSource("NasMusicApp.kt"))
        val section = flatten(mainSource("ui/screens/settings/SourcesSettingsSection.kt"))

        // 破坏 1：meting 改为无条件注册（开关失效）
        val unconditional = metingConditional.replace(app, "mapOf(NetworkSongId.METING_SOURCE to metingApiService)")
        assertTrue("自证失效：破坏样本 1 没改动到文本（写法变了，请同步更新锚串）", unconditional != app)
        assertTrue("护栏失效：无条件注册仍被判正", registrationProblems(unconditional).isNotEmpty())

        // 破坏 2：改用同步镜像决定注册（冷启动竞态）
        val mirrorDecided = app.replace(
            "if (bootSettings.metingEnabled) {",
            "if (appPreferences.getMetingEnabledSync()) {"
        )
        assertTrue("自证失效：破坏样本 2 没改动到文本", mirrorDecided != app)
        assertTrue("护栏失效：用同步镜像决定注册仍被判正",
            registrationProblems(mirrorDecided).isNotEmpty())

        // 破坏 3：自愈改成「无源也写盘」
        val writeAnyway = app.replace(
            "if (registered.isEmpty()) return null",
            "if (registered.isEmpty()) { appPreferences.setDefaultNetworkSource(\"\") }"
        )
        assertTrue("自证失效：破坏样本 3 没改动到文本", writeAnyway != app)
        assertTrue("护栏失效：无源写盘仍被判正", repairProblems(writeAnyway).isNotEmpty())

        // 破坏 4：卡⑥ 候选改回不过滤启用态 / 目录能力
        val unfiltered = section.replace(
            "state.musicSources.filter { it.enabled && it.providesCatalog }",
            "state.musicSources"
        )
        assertTrue("自证失效：破坏样本 4 没改动到文本", unfiltered != section)
        assertTrue("护栏失效：未过滤启用态仍被判正", sectionProblems(unfiltered).isNotEmpty())

        // 破坏 5：空文本一律判负
        assertTrue("护栏失效：空文本下 G1 仍判正", registrationProblems("").isNotEmpty())
        assertTrue("护栏失效：空文本下 G3 仍判正", sectionProblems("").isNotEmpty())
    }

    // ─────────────────── 判据（纯函数，便于负向自证复用） ───────────────────

    /**
     * meting 的条件注册形状：`if (bootSettings.metingEnabled) { mapOf(…) } else { emptyMap() }`。
     * 大括号可选 —— `flatten` 之后写法差异只剩 `{ }`，不该让门因为多一对括号判负。
     */
    private val metingConditional = Regex(
        """if \(bootSettings\.metingEnabled\)\s*\{?\s*mapOf\(NetworkSongId\.METING_SOURCE to metingApiService\)\s*\}?\s*else\s*\{?\s*emptyMap\(\)\s*\}?"""
    )

    private fun registrationProblems(app: String): List<String> {
        val problems = mutableListOf<String>()
        if (!metingConditional.containsMatchIn(app)) {
            problems += "meting 注册没有由 `bootSettings.metingEnabled` 决定（缺 if/else 条件注册）"
        }
        if (!app.contains("val bootSettings = runBlocking { appPreferences.appSettings.first() }")) {
            problems += "没有在构造期直接读 DataStore（`bootSettings`）—— 同步镜像在冷启动时尚未收敛"
        }
        if (app.contains("appPreferences.getMetingEnabledSync()")) {
            problems += "注册决策读了同步镜像 getMetingEnabledSync()（冷启动竞态，方案 §7.7 第 4 条）"
        }
        if (!app.contains("if (bootSettings.jamendoEnabled && bootJamendoClientId.isNotBlank())")) {
            problems += "jamendo 生效条件必须是「开关 且 clientId 非空」"
        }
        return problems
    }

    private fun repairProblems(app: String): List<String> {
        val problems = mutableListOf<String>()
        val emptyGuard = "if (registered.isEmpty()) return null"
        val priorityPick = "AppPreferences.NETWORK_SOURCE_PRIORITY.firstOrNull { it in registered }"
        val write = "appPreferences.setDefaultNetworkSource(next)"
        if (!app.contains(emptyGuard)) {
            problems += "缺「一个源都没注册 ⇒ 返回 null 且不写盘」（`$emptyGuard`）"
        }
        if (!app.contains(priorityPick)) {
            problems += "切换目标不是按 `NETWORK_SOURCE_PRIORITY` 挑第一个已注册源"
        }
        if (!app.contains(write)) problems += "自愈没有真正写盘（`$write`）"
        val guardAt = app.indexOf(emptyGuard)
        val writeAt = app.indexOf(write)
        if (guardAt >= 0 && writeAt >= 0 && writeAt < guardAt) {
            problems += "写盘出现在「无源」判据之前 ⇒ 全关时会把默认源写成空"
        }
        return problems
    }

    private fun sectionProblems(section: String): List<String> {
        val problems = mutableListOf<String>()
        // v2.39.8：候选 = 已启用 **且提供目录能力** 的源。加 `providesCatalog` 是因为
        // 百度网盘 / Jamendo 的 getPlaylist / findArtist 实现直接返回空 ——
        // 把它们列成候选 = 让用户选了一个必然空白的选项（用户 2026-10-28 质疑的正是这张卡的语义）。
        if (!section.contains("state.musicSources.filter { it.enabled && it.providesCatalog }")) {
            problems += "卡⑥ 候选没有过滤「已启用 且 提供目录能力」（§7.6 第 1 条）"
        }
        if (!section.contains("R.string.settings_no_music_source)")) {
            problems += "全关时没有渲染「无可用音乐源」红字（§7.6 第 3 条）"
        }
        if (!section.contains("actions.onToggleSourceEnabled(row.key, !row.enabled)")) {
            problems += "卡④ 源行没有接到启停回调（§7.5）"
        }
        if (!section.contains("R.string.settings_source_off_playback_warn")) {
            problems += "卡④ 缺固定灰字「已关闭源的收藏与歌单将无法播放」（§7.5）"
        }
        // v2.39.8：每个源恰好一个开关 —— 百度 / Jamendo 不得再回到卡④ 的源行列表里
        if (section.contains("AppPreferences.SOURCE_BAIDU,") &&
            section.contains("MusicSourceRow(\n                            key = com.nasmusic.tv.data.prefs.AppPreferences.SOURCE_BAIDU")
        ) {
            problems += "百度网盘又出现在卡④ 的源行列表里 ⇒ 与卡③ 的开关重复（v2.39.8 已删）"
        }
        return problems
    }

    // ─────────────────── 辅助：定位源码 / 读文本 ───────────────────

    /** 换行归一 + 空白折叠：重新缩进、CRLF 都不该弄断门。 */
    private fun flatten(text: String): String =
        text.replace("\r\n", "\n").replace(Regex("""\s+"""), " ")

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),      // user.dir = app/
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),  // user.dir = 仓库根
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件：$relativePath；已尝试：${candidates.joinToString { it.absolutePath }}", file != null)
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }
}
