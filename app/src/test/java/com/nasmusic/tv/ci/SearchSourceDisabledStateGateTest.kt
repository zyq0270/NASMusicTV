package com.nasmusic.tv.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 搜索页「来源点亮 chip」与「设置里的音乐源总开关」同步的源码接线门（G1–G3）。
 *
 * 背景：设置里把某个源关掉后，曲库 → 搜索页的来源 chip 仍显示「● 点亮」，
 * 而实际那个源**根本不参与搜索**（聚合器按可见性取交集）⇒ chip 对用户说谎；
 * 且「全部点亮」会把已关闭的源点亮回来（违反方案 §5.3）。
 *
 * 本门禁钉住三件事：
 * | # | 钉住的东西 | 漏了会是什么症状 |
 * |---|---|---|
 * | G1 | `SearchViewModel` 用 `SourceVisibility` 算出「设置里关掉的源」，并派生出「有效点亮集 = 点亮 ∩ 开着的源」 | chip 与实际搜索范围分叉（显示点亮却搜不到） |
 * | G2 | 点亮/熄灭与「全部点亮」都以**有效点亮集**为准；被关掉的源点击改为提示而非静默无效 | 用户点了没反应，以为遥控器坏了；「全部点亮」把已关源点亮回来 |
 * | G3 | `SearchTab` 渲染第三态（灰显 + 「已关」小标 + 仍可聚焦可点） | 第三态丢失，灰显状态无法被读出 |
 *
 * ⚠️ 判据前把空白折叠为单个空格；断言一律 `assertTrue`；读文件显式 UTF-8；源码定位不依赖 CWD。
 */
class SearchSourceDisabledStateGateTest {

    private val searchVmPath = "ui/viewmodel/SearchViewModel.kt"
    private val searchTabPath = "ui/screens/library/SearchTab.kt"

    @Test
    fun `G1 SearchViewModel derives disabled and effective source sets`() {
        val src = flatten(mainSourceFile(searchVmPath))
        assertTrue("G1 空转自证：读到的确实是 SearchViewModel", src.contains("class SearchViewModel"))

        // 判据必须走 SourceVisibility（与聚合器内部同一份语义），不得各自拼布尔
        assertTrue(
            "G1：`SearchViewModel` 没有用 `SourceVisibility` 计算可见性 —— 与聚合器判据分叉，chip 会说谎。",
            src.contains("SourceVisibility")
        )
        assertTrue(
            "G1：缺少 `disabledSearchSources`（设置里被关闭的搜索源）。",
            src.contains("val disabledSearchSources: StateFlow<Set<MusicSourceType>>")
        )
        assertTrue(
            "G1：缺少 `effectiveEnabledSearchSources`（点亮集 ∩ 设置里开着的源）。",
            src.contains("val effectiveEnabledSearchSources: StateFlow<Set<MusicSourceType>>")
        )
        assertTrue(
            "G1：有效点亮集必须由「点亮集减掉被关掉的源」得出。",
            src.contains("combine(_enabledSearchSources, disabledSearchSources)")
        )
    }

    @Test
    fun `G2 toggling and enable-all both respect the effective set`() {
        val src = flatten(mainSourceFile(searchVmPath))

        // 被设置关掉的源：点击改为提示，不再静默切换
        assertTrue(
            "G2：`toggleSearchSource` 没有拦下「设置里已关闭」的源 —— 点击会静默无效，用户以为遥控器坏了。",
            src.contains("if (source in disabledSearchSources.value)")
        )
        assertTrue(
            "G2：拦截后必须给出可执行提示（`search_source_disabled_hint`）。",
            src.contains("R.string.search_source_disabled_hint")
        )

        // 「全部点亮」不得把已关闭的源点亮回来（方案 §5.3）
        assertTrue(
            "G2：「全部点亮」没有减去 `disabledSearchSources` —— 会把设置里已关闭的源点亮回来（方案 §5.3）。",
            src.contains("MusicSourceType.DEFAULT_SEARCH_SOURCES - disabledSearchSources.value")
        )

        // 聚合器入参必须用有效点亮集
        assertTrue(
            "G2：聚合器入参没有用 `effectiveEnabledSearchSources.value`。",
            src.contains("sources = effectiveEnabledSearchSources.value")
        )
        assertTrue(
            "G2：聚合器入参仍在用原始点亮集 `_enabledSearchSources.value` —— chip 与实际搜索范围会分叉。",
            !src.contains("sources = _enabledSearchSources.value")
        )

        // 负向自证：判据必须能判负
        val breakEnableAll = src.replace(
            "MusicSourceType.DEFAULT_SEARCH_SOURCES - disabledSearchSources.value",
            "MusicSourceType.DEFAULT_SEARCH_SOURCES"
        )
        assertTrue(
            "G2 护栏失效：把「全部点亮」的目标改回默认集后，判据仍判正",
            wiringProblems(breakEnableAll, flatten(mainSourceFile(searchTabPath))).isNotEmpty()
        )
    }

    @Test
    fun `G3 SearchTab renders the third state for settings-disabled sources`() {
        val src = flatten(mainSourceFile(searchTabPath))
        assertTrue("G3 空转自证：读到的确实是 SearchTab", src.contains("fun SearchTab("))

        assertTrue(
            "G3：`SearchTab` 没有接收 `disabledSearchSources` —— 第三态无从渲染。",
            src.contains("disabledSearchSources: Set<MusicSourceType> = emptySet()")
        )
        assertTrue(
            "G3：缺少「设置里已关闭」的判定（`isOffInSettings`）。",
            src.contains("isOffInSettings")
        )
        assertTrue(
            "G3：点亮态必须排除被设置关掉的源（第三态优先于点亮态）。",
            src.contains("!isOffInSettings")
        )
        assertTrue(
            "G3：缺少「已关」小标（`search_source_disabled_badge`）。",
            src.contains("R.string.search_source_disabled_badge")
        )
        // 第三态必须用**中性灰**而非主色淡底，否则与「未点亮」分不开
        assertTrue(
            "G3：第三态没有用中性灰容器 —— 会与「未点亮」的主色淡底难以区分。",
            src.contains("NasMusicColors.TextSecondary.copy(alpha = 0.15f)")
        )
        // 灰显 ≠ 不可点：点击仍要交给上层判定
        assertTrue(
            "G3：chip 点击没有交给上层判定 —— 设置关的源会变成静默无效。",
            src.contains("onClick = { onToggleSource(source) }")
        )
    }

    /** 结构自证用的统一判据（负向自证用） */
    private fun wiringProblems(vm: String, tab: String): List<String> = buildList {
        if (!vm.contains("MusicSourceType.DEFAULT_SEARCH_SOURCES - disabledSearchSources.value")) {
            add("「全部点亮」没有减去 disabledSearchSources")
        }
        if (!vm.contains("sources = effectiveEnabledSearchSources.value")) {
            add("聚合器入参没有用 effectiveEnabledSearchSources")
        }
        if (!tab.contains("isOffInSettings")) {
            add("SearchTab 没有第三态判定")
        }
    }

    // ─────────────────── 辅助（照抄 LibrarySongsSourceWiringGateTest） ───────────────────

    private fun flatten(text: String): String = text.replace(WHITESPACE, " ")

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),        // user.dir = app/
            File("app/src/main/java/com/nasmusic/tv"),    // user.dir = 仓库根
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
