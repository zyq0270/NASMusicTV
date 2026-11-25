package com.nasmusic.tv.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 快捷选择（Quick Dial）源码文本门禁：防止关键口径回退。
 */
class QuickPickGateTest {

    private fun src(rel: String): String {
        val root = listOf(
            "app/src/main/java/",
            "../app/src/main/java/",
            System.getProperty("user.dir") + "/app/src/main/java/"
        ).map { File(it + rel) }.firstOrNull { it.exists() }
            ?: throw AssertionError("找不到源码文件：$rel")
        return root.readText()
    }

    @Test
    fun `G1 拨盘条目统一配色不再按来源区分`() {
        val s = src("com/nasmusic/tv/ui/components/picker/ArcDialPicker.kt")
        // 所有者 2026-11-23 第 7 轮定稿：曲库候选已统一「只列历史」，全是历史时
        // 深绿失去区分意义 ⇒ 与播放页队列拨盘用同一套配色（中央 Primary / 其余 TextPrimary）。
        // ⛔ 别再引入 PickSource 着色分支、也别忘了绿色/蓝色/角标都不能回来。
        assertTrue(!s.contains("PickSource"))
        assertTrue(!s.contains("isHistory"))
        assertTrue(!s.contains("NasMusicColors.Success"))
        assertTrue(!s.contains("NasMusicColors.Secondary"))
        assertTrue(!s.contains("historyBadgeText"))
        assertTrue(s.contains("if (isCentered) NasMusicColors.Primary else NasMusicColors.TextPrimary"))
        assertTrue(!Regex("Color\\(0x[0-9A-Fa-f]{6,8}\\)").containsMatchIn(s))
    }

    @Test
    fun `G2 TV 分支存在 D-Pad 上下键与焦点请求`() {
        val s = src("com/nasmusic/tv/ui/components/picker/ArcDialPicker.kt")
        assertTrue(s.contains("Key.DirectionDown"))
        assertTrue(s.contains("Key.DirectionUp"))
        assertTrue(s.contains("focusRequester"))
    }

    @Test
    fun `G3 弹窗内用 BackHandler 而非 RegisterDialogBackHandler`() {
        val s = src("com/nasmusic/tv/ui/components/picker/ArcDialDialog.kt")
        assertTrue(s.contains("BackHandler {"))
        // 语句级判定：注释里解释「不能用」不算违规（全文否定会自伤）
        assertTrue(s.lines().none { it.trimStart().startsWith("RegisterDialogBackHandler(") })
    }

    @Test
    fun `G4 队列场景不写快捷选择统计`() {
        val s = src("com/nasmusic/tv/ui/components/branches/NowPlayingBranch.kt")
        assertTrue(!s.contains("recordQuickPick"))
        assertTrue(s.contains("resolveAndPlayByIndex"))
    }

    @Test
    fun `G6 拨盘候选只列历史且不混当前页面数据`() {
        val s = src("com/nasmusic/tv/ui/screens/LibraryScreen.kt")
        // ⛔ 不再把全局 searchHistory 当所有 tab 的搜索热度（跨 tab 污染 + 一次操作算两次）
        assertTrue(!s.contains("searchCounts"))
        // 手动搜索也要计入当前 tab 统计（与拨盘选择共用口径）
        assertTrue(s.contains("onRecordQuickPick(activeTab.quickPickCategory(), query.trim())"))
        // ⛔ 所有者 2026-11-23 定稿：候选只列历史，不再混入页面全量列表 / 搜索结果歌曲名。
        //    base 传空 ⇒ 全部 source = HISTORY（绿色）。
        assertTrue(s.contains("QuickPickSourceMerger.merge(emptyList<String>()"))
        val fnBody = s.substringAfter("private fun buildQuickPickCandidates")
            .substringBefore("\nprivate ")
        assertTrue(!fnBody.contains("albums.map"))
        assertTrue(!fnBody.contains("artists.map"))
        assertTrue(!fnBody.contains("searchResults.map"))
        assertTrue(!fnBody.contains("radioStations.map"))
        assertTrue(!fnBody.contains("discoverSongs.map"))
    }

    @Test
    fun `G7 曲库快选按钮用 QueueMusic 图标而非文字`() {
        val s = src("com/nasmusic/tv/ui/screens/LibraryScreen.kt")
        assertTrue(s.contains("Icons.Filled.QueueMusic"))
        // 按钮块内不得再渲染文字（Text）；文案只作为 contentDescription 保留
        val btn = s.substringAfter("val searchField:").substringBefore("// TAB 切换")
        assertTrue(!btn.contains("Text("))
        assertTrue(btn.contains("contentDescription = stringResource(R.string.qp_button_label)"))
    }

    @Test
    fun `G8 拨盘无历史时打开搜索框而不是静默关闭`() {
        val s = src("com/nasmusic/tv/ui/screens/LibraryScreen.kt")
        // 候选只列历史 ⇒ 冷启动必然为空。静默关闭会让用户以为按钮失灵，
        // 直接打开搜索输入框：搜一次即写入本 tab 桶，下次就能快选。
        val block = s.substringAfter("if (candidates.isEmpty()) {").substringBefore("} else {")
        assertTrue(block.contains("showSearchDialog = true"))
        assertTrue(block.contains("showQuickPick = false"))
    }

    @Test
    fun `G9 流派年代电台都补齐了统计写入点`() {
        val s = src("com/nasmusic/tv/ui/screens/LibraryScreen.kt")
        // 这三类没有走顶部共享 searchField 的常规搜索路径（流派/年代点卡片即查询、
        // 电台用自己的搜索框），不补写入点则对应桶永远为空 ⇒ 拨盘弹不出来。
        assertTrue(s.contains("LibraryTab.GENRES.quickPickCategory()"))
        assertTrue(s.contains("LibraryTab.YEARS.quickPickCategory()"))
        // 电台：只允许在 RadioTab 的 onSearch 包装里出现（onPlayStation 是播放不是查询）
        assertTrue(s.contains("LibraryTab.RADIO.quickPickCategory()"))
        val radio = s.substringAfter("onSearch = { query ->").substringBefore("onPlayStation")
        assertTrue(radio.contains("LibraryTab.RADIO.quickPickCategory()"))
    }

    @Test
    fun `G10 四个 tab 用各自展示方式呈现搜索结果且不跳转`() {
        val lib = src("com/nasmusic/tv/ui/screens/LibraryScreen.kt")
        // 所有者 2026-11-24 第 9 轮：不许把搜索页 UI 整页搬进别的 tab，也 不许跳 tab。
        assertTrue(!lib.contains("onTabSelected(LibraryTab.SEARCH)"))
        assertTrue(!lib.contains("searchResultsContent"))
        // 发现页：沿用 DiscoverTab（维度行 + 歌曲网格），只换数据源
        assertTrue(lib.contains("filteredSongs = if (searching) searchResults else discoverFilteredSongs"))
        // 电台：就地过滤电台列表，不换成歌曲结果
        assertTrue(lib.contains("visibleStations"))
        assertTrue(lib.contains("it.name.contains(radioQuery, ignoreCase = true)"))
        // 流派 / 年代：把结果交给各自网格（本页卡片样式），而不是整页替换
        assertEquals(2, lib.split("searchResults = if (filterQuery.isNotBlank()) searchResults else emptyList()").size - 1)

        val browse = src("com/nasmusic/tv/ui/screens/library/browse/GenreYearLists.kt")
        // 结果区在同一个 LazyVerticalGrid 内、占满整行做小标题，卡片样式与流派卡片一致
        assertEquals(2, browse.split("R.string.library_search_results_count").size - 1)
        assertTrue(browse.contains("GridItemSpan(maxLineSpan)"))
        assertTrue(browse.contains("searchResults: List<Song> = emptyList()"))
    }

    @Test
    fun `G11 弹窗遮罩与底衬随明暗主题而不是恒定黑色`() {
        val dlg = src("com/nasmusic/tv/ui/components/picker/ArcDialDialog.kt")
        // 所有者 2026-11-24：浅色主题下打开快选，整页发灰 —— 恒定 70% 黑遮罩压出来的。
        // 遮罩/底衬必须走随主题的 token。
        assertTrue(!dlg.contains("NasOverlayColors"))
        assertTrue(dlg.contains(".background(NasMusicColors.DialogScrim)"))
        assertTrue(dlg.contains(".background(NasMusicColors.DialogPanel)"))
        // ⛔ 不能再直接拿 Background 蒙一层（浅色下是灰的）
        assertTrue(!dlg.contains("NasMusicColors.Background.copy(alpha = 0.28f)"))

        val theme = src("com/nasmusic/tv/ui/theme/Theme.kt")
        // 浅色分支必须是白色系；深色分支沿用原来的黑遮罩。
        // 浅色 alpha 与深色 0xB3000000 对称取 0xB3（70% 白）：透光 30%，下层页面要看得见。
        assertTrue(
            theme.contains(
                "if (LocalNasPalette.current.isLight) Color(0xB3FFFFFF) else NasOverlayColors.DialogScrim"
            )
        )
        // 反例钉死：90% 白会把下层遮死（2026-11-24 第二轮：「背景透明度不够，下层内容看不到」）。
        // 断言写语句级 `Color(...)`，别用裸字面量做全文否定 —— 注释里一提就自伤。
        assertTrue(!theme.contains("Color(0xE6FFFFFF)"))
        // 浅色底衬必须透明：该层写在 horizontal 20dp 内缩之内，比全屏遮罩窄 40dp，
        // 填任何颜色都会叠出「中间一块 + 两侧两条」的三截灰（第二轮：「背景不是一体」）
        assertTrue(theme.contains("val DialogPanel: Color"))
        assertTrue(theme.contains("Color.Transparent"))
    }

    @Test
    fun `G12 队列拨盘按选中下标播放且重复项 key 含下标`() {
        val s = src("com/nasmusic/tv/ui/components/branches/NowPlayingBranch.kt")
        val queueDial = s.substringAfter("if (showQueueDial && playerState.queue.isNotEmpty())")
            .substringBefore("dPadMode = isTV")
        assertTrue("队列拨盘仍按 id/title 反查首个重复项", !queueDial.contains("indexOfFirst"))
        assertTrue("队列拨盘确认回调未直接使用选中下标", queueDial.contains("onConfirm = { index"))
        assertTrue("队列拨盘 key 未包含下标，重复队列项仍会撞 key", Regex("key\\s*=\\s*\\{\\s*index").containsMatchIn(queueDial))
    }

    @Test
    fun `G5 设置页按类别分别展示统计且不重复放清除按钮`() {
        val s = src("com/nasmusic/tv/ui/screens/settings/DataSettingsSection.kt")
        // 每类一行概览 + 进入明细页；⛔ 不再放「清除本类记录」按钮（明细页已有）
        assertTrue(s.contains("quickPickCategories.forEach"))
        assertTrue(s.contains("onOpenQuickPickStats"))
        assertTrue(!s.contains("onClearQuickPickStats"))
    }
}
