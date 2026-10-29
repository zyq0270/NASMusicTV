package com.nasmusic.tv.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「音乐源开关全覆盖」方案（`docs/archive/music-source-gating-plan.md` v3）§7.2 的**源码文本门**。
 *
 * ## 为什么是文本门
 *
 * 可见性门控点全部是**布尔表达式**（`if (visible(...))`），编译期没有任何类型约束 ——
 * 任何一次「顺手重构」都能无声拆掉它们，而症状是
 * 「关掉开关，界面照样列出该源的条目」：**静默、且极难归因**。
 * ⇒ 用文本门把**形状**钉死（与既有 `LocalMusicGateTest` / `NetworkSourceSwitchGateTest` 同款手法）。
 *
 * ## 门清单（与方案 §7.2 对齐）
 *
 * | # | 钉住的东西 | 漏了会是什么症状 |
 * |---|---|---|
 * | G1 | `SearchAggregator` 的五个分支都有可见性判据 + 结果兜底过滤 | 关掉百度/Jamendo 后聚合搜索照样返回结果（§5.2 最严重的一处逃逸）；关 NAS 后仍搜得到 NAS 歌（v2.39.8 已加门控但**无测试守卫**） |
 * | G2 | VM 侧有 `visibleSongsPaging` + `sourceRevision`；`updateMergedData` 按可见性把 NAS/百度段传空；**关源路径不得清空原始库存流** | 开关翻转后曲库不刷新（v2 的 G1b 根因）；或关源把首页统计数字清零（违反 S2 / §3.0 契约） |
 * | G3 | 关源 ⇒ `clearSourceScopedCaches` + 重算 + 刷首页 + 过滤历史；重开 ⇒ 按源显式重拉 | 关源后旧内容残留（N2）；重开后空曲库 / 随心听空（V16 / V19） |
 * | G4 | `LibraryBranch` 只做透传，不得再合并 | UI 层 `remember` 的三个键在开关翻转时都不变 ⇒ 永不重算（N1） |
 * | G5 | `SmartRadioManager` 注入 NAS 开关；`NetworkMusicViewModel` 用百度的 `isActive` 镜像 | 智能电台批次含 NAS 歌（V13）；百度关掉后网盘目录仍可「播放全部」（V15） |
 * | G6 | `SourceVisibility` 本体存在且判据齐全（含 `DOWNLOAD`） | 唯一真相源被拆散；已下载歌曲被本地开关误杀（§5.6 红线） |
 * | G7 | **负向自证**：破坏样本必须被同一套判据拦下 | 门不得空转 |
 *
 * ## ⛔ 断言纪律
 *
 * - 一律 `assertTrue`（Kotlin 的 `assert` 在测试 JVM 上是空操作）。
 * - 只钉**标识符与形状**，不钉空白 / 换行 / 缩进（判据函数统一先 `flatten`，或用 `\s*` 容错的正则）。
 * - 源码定位**不依赖 CWD**（照抄 `NetworkSourceSwitchGateTest.mainSource` 的候选目录法）。
 * - ⛔ 本文件**只读** `app/src/main/` 目录下的源码，不改实现；发现实现与门禁预期不符 ⇒ 写进报告。
 */
class SourceGatingGateTest {

    // ─────────────────── G1：聚合器五个分支都要有可见性判据 ───────────────────

    @Test
    fun `G1 聚合器的五个分支都要有可见性判据`() {
        val source = mainSource("backend/SearchAggregator.kt")
        // 空转自证：读到的确实是 SearchAggregator
        assertTrue(
            "G1 空转自证：SearchAggregator.kt 里找不到 `class SearchAggregator` —— 可能读错文件",
            source.contains("class SearchAggregator"),
        )
        val problems = aggregatorProblems(source)
        assertTrue(
            "G1：聚合器的可见性判据不合规 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G2：VM 侧合成点 ───────────────────

    @Test
    fun `G2 VM 侧必须有 visibleSongsPaging 派生流与 sourceRevision 信号`() {
        val vm = mainSource("ui/viewmodel/MainViewModel.kt")
        assertTrue(
            "G2 空转自证：MainViewModel.kt 里找不到 `class MainViewModel` —— 可能读错文件",
            vm.contains("class MainViewModel"),
        )
        val problems = vmSynthesisProblems(vm)
        assertTrue(
            "G2：VM 侧合成点不合规 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * ⛔ **负向钉住**：关源路径**不得**清空原始库存流。
     *
     * `_albums` / `_songs` / `_songsPaging` / `_artists` 是 **S2 统计的读数来源**
     * （首页「专辑 N / 歌曲 N / 艺术家 N」展示的是**曲库存量**）⇒ 关源只影响「列出哪些条目」（S1），
     * **不改写「库里有这么多」**（方案 §3.0 契约）。
     *
     * ⚠️ 判据**只针对开关翻转路径**（`setSourceEnabled` + `clearSourceScopedCaches`）：
     * 「NAS 未连接」「手动刷新」「断开连接」是另外三类事件，与「关源」无关，
     * 它们的历史清空行为由 `G2b` 的白名单单独钉住。
     */
    @Test
    fun `G2 关源路径不得清空原始库存流`() {
        val vm = mainSource("ui/viewmodel/MainViewModel.kt")
        val problems = togglePathInventoryClearProblems(vm)
        assertTrue(
            "G2：关源路径清空了原始库存流 —— 违反方案 §3.0 契约（库存流是 S2 统计的读数来源，\n" +
                "关源不得改写「库里有这么多」）。\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * ⛔ 补充钉住：**全文件**的库存流清空点必须全部落在「与开关无关的非开关路径」里。
     *
     * 允许清空的位置（每一条都与「源开关」无关，且都不是关源路径）：
     * - `loadLibrary()`：NAS **未连接**时（不是「被关掉」）；
     * - `retrySongsLoad()`：重试前清掉带 error 的旧状态；
     * - `refreshLibrary()`：用户手动刷新曲库；
     * - `serverVM.onDisconnected`：NAS 连接断开。
     *
     * ⛔ **不在白名单里的任何一处清空都是回归** —— 尤其不得出现在 `setSourceEnabled` /
     * `clearSourceScopedCaches` 里。
     */
    @Test
    fun `G2b 库存流的清空点必须全部落在非开关路径`() {
        val vm = mainSource("ui/viewmodel/MainViewModel.kt")
        val problems = inventoryClearOutsideAllowlistProblems(vm)
        assertTrue(
            "G2b：原始库存流在「与源开关无关」的位置之外被清空 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G3：关源清缓存 + 双向刷新 ───────────────────

    @Test
    fun `G3 关源要清缓存并重算 重开要按源显式重拉`() {
        val vm = mainSource("ui/viewmodel/MainViewModel.kt")
        assertTrue(
            "G3 空转自证：MainViewModel.kt 里找不到 `class MainViewModel` —— 可能读错文件",
            vm.contains("class MainViewModel"),
        )
        val problems = refreshProblems(vm)
        assertTrue(
            "G3：关源/重开的双向刷新不合规 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G4：UI 侧不得再做合并 ───────────────────

    @Test
    fun `G4 UI 侧不得再合并 必须消费 VM 派生流`() {
        val branch = mainSource("ui/components/branches/LibraryBranch.kt")
        assertTrue(
            "G4 空转自证：LibraryBranch.kt 里找不到 `fun LibraryBranch` —— 可能读错文件",
            branch.contains("fun LibraryBranch"),
        )
        val problems = libraryBranchProblems(branch)
        assertTrue(
            "G4：UI 侧的曲库合成点不合规 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G5：后端两处门控 ───────────────────

    @Test
    fun `G5 后端两处门控必须在位`() {
        val radio = mainSource("backend/radio/SmartRadioManager.kt")
        assertTrue(
            "G5 空转自证：SmartRadioManager.kt 里找不到 `class SmartRadioManager` —— 可能读错文件",
            radio.contains("class SmartRadioManager"),
        )
        val netVm = mainSource("ui/viewmodel/NetworkMusicViewModel.kt")
        assertTrue(
            "G5 空转自证：NetworkMusicViewModel.kt 里找不到 `class NetworkMusicViewModel` —— 可能读错文件",
            netVm.contains("class NetworkMusicViewModel"),
        )
        val problems = backendGateProblems(radio, netVm)
        assertTrue(
            "G5：后端门控不合规 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G6：SourceVisibility 本体 ───────────────────

    @Test
    fun `G6 SourceVisibility 本体存在且判据齐全`() {
        val sv = mainSource("backend/SourceVisibility.kt")
        assertTrue(
            "G6 空转自证：SourceVisibility.kt 里找不到 `class SourceVisibility` —— 可能读错文件",
            sv.contains("class SourceVisibility"),
        )
        val problems = sourceVisibilityProblems(sv)
        assertTrue(
            "G6：`SourceVisibility` 判据不齐全 ——\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    // ─────────────────── G7：负向自证（门不得空转） ───────────────────

    /**
     * 负向自证：构造一批**破坏样本**（把门控从源码里拆掉），确认**同一套判据**必须判负。
     *
     * ⛔ 没有这一条，G1–G6 就可能退化成「断言一个恒真的字符串」的空壳。
     */
    @Test
    fun `G7 破坏样本必须被同一套判据拦下`() {
        // ── 1) 聚合器：拆掉百度分支的可见性判据 ──
        val agg = mainSource("backend/SearchAggregator.kt")
        val aggNoBaidu = agg.replace("visible(MusicSourceType.BAIDU_PAN)", "true")
        assertTrue("自证失效：破坏样本 1 没改动到文本（锚串变了，请同步更新）", aggNoBaidu != agg)
        assertTrue(
            "护栏失效：拆掉百度的可见性判据仍被判正",
            aggregatorProblems(aggNoBaidu).isNotEmpty(),
        )

        // ── 2) 聚合器：拆掉 Jamendo 分支的可见性判据 ──
        val aggNoJamendo = agg.replace("visible(MusicSourceType.JAMENDO)", "true")
        assertTrue("自证失效：破坏样本 2 没改动到文本", aggNoJamendo != agg)
        assertTrue(
            "护栏失效：拆掉 Jamendo 的可见性判据仍被判正",
            aggregatorProblems(aggNoJamendo).isNotEmpty(),
        )

        // ── 3) 聚合器：拆掉网络源分支的可见性判据 ──
        val aggNoNetwork = agg.replace("visible(MusicSourceType.NETWORK_MUSIC)", "true")
        assertTrue("自证失效：破坏样本 3 没改动到文本", aggNoNetwork != agg)
        assertTrue(
            "护栏失效：拆掉网络源的可见性判据仍被判正",
            aggregatorProblems(aggNoNetwork).isNotEmpty(),
        )

        // ── 4) 聚合器：拆掉 NAS / LOCAL 的开关门控 ──
        val aggNoNas = agg.replace("nasMusicEnabled()", "true")
        assertTrue("自证失效：破坏样本 4 没改动到文本", aggNoNas != agg)
        assertTrue(
            "护栏失效：拆掉 NAS 的开关门控仍被判正",
            aggregatorProblems(aggNoNas).isNotEmpty(),
        )
        val aggNoLocal = agg.replace("localMusicEnabled()", "true")
        assertTrue("自证失效：破坏样本 5 没改动到文本", aggNoLocal != agg)
        assertTrue(
            "护栏失效：拆掉 LOCAL 的开关门控仍被判正",
            aggregatorProblems(aggNoLocal).isNotEmpty(),
        )

        // ── 6) 聚合器：拆掉结果兜底过滤 ──
        val aggNoResultFilter = agg.replace(
            "merged.filter { visible(it.source) && visible(it.song) }",
            "merged",
        )
        assertTrue("自证失效：破坏样本 6 没改动到文本", aggNoResultFilter != agg)
        assertTrue(
            "护栏失效：拆掉结果兜底过滤仍被判正",
            aggregatorProblems(aggNoResultFilter).isNotEmpty(),
        )

        // ── 7) UI 侧：把 `visibleSongsPaging` 换回旧的原始流 ──
        val branch = mainSource("ui/components/branches/LibraryBranch.kt")
        val branchNoDerived = branch.replace("visibleSongsPaging", "vSongsPaging")
        assertTrue("自证失效：破坏样本 7 没改动到文本", branchNoDerived != branch)
        assertTrue(
            "护栏失效：UI 不消费 VM 派生流仍被判正",
            libraryBranchProblems(branchNoDerived).isNotEmpty(),
        )

        // ── 8) UI 侧：把合并搬回 UI 层 ──
        val branchMerged = branch.replace("visibleSongsPaging", "mergeLibrarySongs")
        assertTrue("自证失效：破坏样本 8 没改动到文本", branchMerged != branch)
        assertTrue(
            "护栏失效：UI 侧重新合并仍被判正",
            libraryBranchProblems(branchMerged).isNotEmpty(),
        )

        // ── 9) VM 侧：在关源路径里清空原始库存流（S2 契约） ──
        val vm = mainSource("ui/viewmodel/MainViewModel.kt")
        val vmClearsInventory = vm.replaceFirst(
            "_genres.value = UiState.Success(emptyList())",
            "_genres.value = UiState.Success(emptyList())\n                _albums.value = UiState.Success(emptyList())",
        )
        assertTrue("自证失效：破坏样本 9 没改动到文本", vmClearsInventory != vm)
        assertTrue(
            "护栏失效：关源路径清空库存流仍被判正",
            togglePathInventoryClearProblems(vmClearsInventory).isNotEmpty(),
        )
        assertTrue(
            "护栏失效：库存流在白名单之外被清空仍被判正",
            inventoryClearOutsideAllowlistProblems(vmClearsInventory).isNotEmpty(),
        )

        // ── 10) VM 侧：`updateMergedData` 不再把 NAS 段置空 ──
        val vmNoNasGate = vm.replace(
            "val nasAlbums = if (nasVisible)",
            "val nasAlbums = _albums.value.dataOrNull() ?: emptyList()",
        )
        assertTrue("自证失效：破坏样本 10 没改动到文本", vmNoNasGate != vm)
        assertTrue(
            "护栏失效：`updateMergedData` 不置空 NAS 段仍被判正",
            vmSynthesisProblems(vmNoNasGate).isNotEmpty(),
        )

        // ── 11) VM 侧：派生流不再挂 `sourceRevision` ──
        val vmNoRevision = vm.replace("_sourceRevision", "_unusedRevision")
        assertTrue("自证失效：破坏样本 11 没改动到文本", vmNoRevision != vm)
        assertTrue(
            "护栏失效：派生流不挂重算信号仍被判正",
            vmSynthesisProblems(vmNoRevision).isNotEmpty(),
        )

        // ── 12) 后端：拆掉智能电台的 NAS 门控 / 百度的 isActive 判据 ──
        val radio = mainSource("backend/radio/SmartRadioManager.kt")
        val radioNoGate = radio.replace("nasMusicEnabled()", "true")
        assertTrue("自证失效：破坏样本 12 没改动到文本", radioNoGate != radio)
        assertTrue(
            "护栏失效：智能电台不判 NAS 开关仍被判正",
            backendGateProblems(radioNoGate, mainSource("ui/viewmodel/NetworkMusicViewModel.kt")).isNotEmpty(),
        )
        val netVm = mainSource("ui/viewmodel/NetworkMusicViewModel.kt")
        val netVmNoBaidu = netVm.replace("getBaiduActiveSync()", "true")
        assertTrue("自证失效：破坏样本 13 没改动到文本", netVmNoBaidu != netVm)
        assertTrue(
            "护栏失效：百度不再用 isActive 镜像仍被判正",
            backendGateProblems(mainSource("backend/radio/SmartRadioManager.kt"), netVmNoBaidu).isNotEmpty(),
        )

        // ── 14) 唯一真相源：把 `DOWNLOAD` 从恒可见里拿掉（§5.6 红线） ──
        val sv = mainSource("backend/SourceVisibility.kt")
        val svNoDownload = sv.replace("MusicSourceType.DOWNLOAD", "MusicSourceType.LOCAL")
        assertTrue("自证失效：破坏样本 14 没改动到文本", svNoDownload != sv)
        assertTrue(
            "护栏失效：`DOWNLOAD` 不再恒可见仍被判正",
            sourceVisibilityProblems(svNoDownload).isNotEmpty(),
        )

        // ── 15) 空文本一律判负（门不得因为「找不到文件就跳过」而空转） ──
        assertTrue("护栏失效：空文本下 G1 仍判正", aggregatorProblems("").isNotEmpty())
        assertTrue("护栏失效：空文本下 G2 仍判正", vmSynthesisProblems("").isNotEmpty())
        assertTrue("护栏失效：空文本下 G4 仍判正", libraryBranchProblems("").isNotEmpty())
        assertTrue("护栏失效：空文本下 G5 仍判正", backendGateProblems("", "").isNotEmpty())
        assertTrue("护栏失效：空文本下 G6 仍判正", sourceVisibilityProblems("").isNotEmpty())
    }

    // ═══════════════════ 判据（纯函数，便于负向自证复用） ═══════════════════

    /**
     * G1 判据：聚合器的五个分支都要有可见性判据 + 结果兜底过滤。
     *
     * 判据形状（不是单纯的 `contains`）：
     * 「`MusicSourceType.X in sources`」与「可见性判定」必须出现在**同一个条件式**里
     * （用 `[^)]{0,160}` 保证不会跨过下一个调用边界）。
     */
    private fun aggregatorProblems(rawText: String): List<String> {
        val text = flatten(rawText)
        val problems = mutableListOf<String>()
        if (!text.contains("class SearchAggregator")) {
            problems += "读到的不是 `SearchAggregator`（可能读错文件）"
        }
        if (!text.contains("fun visible(")) {
            problems += "缺 `visible(` 判定辅助函数（`SourceVisibility` 的封装）"
        }
        // NAS：suspend provider（v2.39.8 已加但**无测试守卫**，方案 §5.1 / §7.2 点名要补）
        if (!NAS_BRANCH_GATE.containsMatchIn(text)) {
            problems += "NAS 分支没有判 `nasMusicEnabled()` —— 关掉 NAS 后聚合搜索仍会搜到 NAS 歌" +
                "（v2.39.8 已加门控但无测试守卫，方案 §5.1 / §7.2）"
        }
        // LOCAL：既有 `LocalMusicGateTest` L3 同款
        if (!LOCAL_BRANCH_GATE.containsMatchIn(text)) {
            problems += "LOCAL 分支没有判 `localMusicEnabled()`"
        }
        // 百度 / Jamendo / 网络源：构造期注入的实例恒非空 ⇒ 必须显式判可见性
        for (type in listOf("BAIDU_PAN", "JAMENDO", "NETWORK_MUSIC")) {
            if (!branchGateFor(type).containsMatchIn(text)) {
                problems += "$type 分支只判实例非空（构造期注入 ⇒ 恒真）⇒ 关源后照样返回结果；" +
                    "必须显式判 `visible(MusicSourceType.$type)`（方案 §5.1 / §5.2）"
            }
        }
        // 结果兜底过滤：防在途请求与 fallback 串源漏进 UI
        if (!MERGED_RESULT_FILTER.containsMatchIn(text)) {
            problems += "缺结果兜底过滤（`merged.filter { visible(it.source) && visible(it.song) }`）—— " +
                "在途请求的结果与 fallback 串源会漏进 UI（方案 §3.3 G2 / §5.7）"
        }
        return problems
    }

    /**
     * G2 判据：VM 侧的合成点。
     *
     * - `visibleSongsPaging` 必须存在（可见歌曲分页的派生流，方案 §3.3 G1）；
     * - 必须挂 `sourceRevision`（可见性读的是 `@Volatile` 同步镜像、**不是 Flow** ⇒
     *   不挂重算信号就永远不会重算，方案 §5.10）；
     * - `updateMergedData` 必须把 NAS 专辑/艺术家、百度索引**按可见性传空**（在**传入之前**过滤，
     *   ⛔ 不能合并完再按 `sourceIds` 剔除，方案 §5.4）。
     */
    private fun vmSynthesisProblems(vm: String): List<String> {
        val problems = mutableListOf<String>()
        if (!vm.contains("class MainViewModel")) {
            problems += "读到的不是 `MainViewModel`（可能读错文件）"
            return problems
        }
        if (!vm.contains("visibleSongsPaging")) {
            problems += "缺 `visibleSongsPaging` —— 可见歌曲分页必须由 VM 派生（方案 §3.3 G1）"
        }
        if (!vm.contains("sourceRevision")) {
            problems += "缺 `sourceRevision` —— 可见性是同步镜像、不是 Flow，" +
                "派生流不挂重算信号就永远不会重算（方案 §5.10）"
        }
        // ── `visibleSongsPaging` 的内部形状 ──
        // ⚠️ 它是**属性型**定义（`= combine(a, b, c, d) { … }`），没有单一的花括号块
        //    ⇒ 用「marker 之后的窗口」取它的完整初始化式（含 `combine(...)` 的实参列表）。
        val visibleBlock = runCatching { windowAfter(vm, "val visibleSongsPaging", 1600) }.getOrNull()
        if (visibleBlock == null) {
            problems += "找不到 `visibleSongsPaging` 的定义（写法变了？）"
        } else {
            if (!visibleBlock.contains("MusicMerger.mergeLibrarySongs(")) {
                problems += "`visibleSongsPaging` 没有调用 `MusicMerger.mergeLibrarySongs(` —— " +
                    "派生流必须自己完成多源合并（方案 §3.3 G1）"
            }
            if (!visibleBlock.contains("sourceVisibility.isVisible(MusicSourceType.NAS)")) {
                problems += "`visibleSongsPaging` 没有按 NAS 可见性过滤"
            }
            if (!visibleBlock.contains("sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)")) {
                problems += "`visibleSongsPaging` 没有按百度可见性过滤"
            }
            if (!visibleBlock.contains("_sourceRevision")) {
                problems += "`visibleSongsPaging` 没有挂 `_sourceRevision` ⇒ 开关翻转后不会重算（方案 §5.10）"
            }
            if (!VISIBLE_PAGING_TOTAL_COUNT.containsMatchIn(visibleBlock)) {
                problems += "`visibleSongsPaging` 的 `totalCount` 口径不对 —— NAS 关时 NAS 的 totalCount " +
                    "不得参与（否则会显示一个不存在的「N 首歌」，方案 §3.3 G1）"
            }
        }
        // ── `updateMergedData` 的内部形状 ──
        val mergedBlock = runCatching { blockBody(vm, "private fun updateMergedData()") }.getOrNull()
        if (mergedBlock == null) {
            problems += "找不到 `updateMergedData` 的定义体（写法变了？）"
        } else {
            if (!mergedBlock.contains("sourceVisibility.isVisible(MusicSourceType.NAS)")) {
                problems += "`updateMergedData` 没有判 NAS 可见性"
            }
            if (!mergedBlock.contains("sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)")) {
                problems += "`updateMergedData` 没有判百度可见性"
            }
            if (!NAS_ALBUMS_GATE.containsMatchIn(mergedBlock)) {
                problems += "`updateMergedData` 的 NAS 专辑段没有按可见性传空（`nasAlbums = if (nasVisible) … else emptyList()`）"
            }
            if (!NAS_ARTISTS_GATE.containsMatchIn(mergedBlock)) {
                problems += "`updateMergedData` 的 NAS 艺术家段没有按可见性传空（`nasArtists = if (nasVisible) … else emptyList()`）"
            }
            if (!BAIDU_SONGS_GATE.containsMatchIn(mergedBlock)) {
                problems += "`updateMergedData` 的百度索引段没有按可见性传空（`baiduSongs = if (baiduVisible) … else emptyList()`）"
            }
        }
        return problems
    }

    /**
     * G2 判据（负向）：关源路径不得清空原始库存流。
     */
    private fun togglePathInventoryClearProblems(vm: String): List<String> {
        val problems = mutableListOf<String>()
        for ((label, marker) in TOGGLE_PATH_MARKERS) {
            val body = runCatching { blockBody(vm, marker) }.getOrNull() ?: continue
            for ((name, pattern) in INVENTORY_STREAMS) {
                if (pattern.containsMatchIn(body)) {
                    problems += "「$label」里清空了 `$name` —— 关源不得改写库存流（方案 §3.0 契约）"
                }
            }
        }
        return problems
    }

    /**
     * G2b 判据（负向）：全文件的库存流清空点必须全部落在白名单（非开关路径）里。
     */
    private fun inventoryClearOutsideAllowlistProblems(vm: String): List<String> {
        val problems = mutableListOf<String>()
        // 白名单区块在**原文件**里的绝对区间（判定「命中点是否落在区块内」而不是「文本是否出现过」，
        // 否则同一段文本在两处出现时会互相掩盖）。
        val allowed = INVENTORY_CLEAR_ALLOWLIST.mapNotNull { (label, marker) ->
            runCatching { blockRange(vm, marker) }.getOrNull()?.let { label to it }
        }
        for ((name, pattern) in INVENTORY_STREAMS) {
            for (m in pattern.findAll(vm)) {
                val line = vm.substring(0, m.range.first).count { it == '\n' } + 1
                val inAllowed = allowed.any { (_, range) -> m.range.first in range }
                if (!inAllowed) {
                    problems += "`$name` 在白名单之外被清空（第 $line 行：`${m.value}`）—— " +
                        "原始库存流只在「NAS 未连接 / 重试 / 手动刷新 / 断开连接」这四种与源开关无关的位置允许清空"
                }
            }
        }
        return problems
    }

    /**
     * G3 判据：关源清缓存 + 双向刷新（方案 §3.5）。
     */
    private fun refreshProblems(vm: String): List<String> {
        val problems = mutableListOf<String>()
        if (!vm.contains("class MainViewModel")) {
            problems += "读到的不是 `MainViewModel`（可能读错文件）"
            return problems
        }
        // 清理入口
        if (!vm.contains("clearSourceScopedCaches")) {
            problems += "缺 `clearSourceScopedCaches` —— 关源时清「只属于该源、无合成点可过滤」的缓存的**唯一**入口（方案 §3.2.2）"
        } else {
            val clearBody = runCatching { blockBody(vm, "private fun clearSourceScopedCaches(source: String)") }.getOrNull()
            if (clearBody == null) {
                problems += "找不到 `clearSourceScopedCaches` 的定义体（写法变了？）"
            } else {
                // 单源无统计流：关源时必须置空（§3.2.2 / §5.8）
                for (flow in listOf("_genres.value", "_years.value", "_playlists.value", "_favoriteSongs.value")) {
                    if (!clearBody.contains(flow)) {
                        problems += "`clearSourceScopedCaches` 里没有清 `$flow` —— 单源无统计流必须在关源时置空（方案 §3.2.2 / §5.8）"
                    }
                }
            }
        }
        val setBody = runCatching { blockBody(vm, "fun setSourceEnabled(key: String, enabled: Boolean)") }.getOrNull()
        if (setBody == null) {
            problems += "找不到 `setSourceEnabled` 的定义体（写法变了？）"
            return problems
        }
        if (!setBody.contains("bumpSourceRevision()")) {
            problems += "`setSourceEnabled` 里没有 `bumpSourceRevision()` —— 可见性是同步镜像、不是 Flow，" +
                "不 bump 重算信号则派生流不会重算（方案 §5.10）"
        }
        // 关源方向
        val offBody = runCatching { blockBody(setBody, "if (!enabled) {") }.getOrNull()
        if (offBody == null) {
            problems += "找不到 `setSourceEnabled` 的关源分支（`if (!enabled) {`）"
        } else {
            for (call in listOf("clearSourceScopedCaches(", "updateMergedData(", "loadHomeDashboard(", "loadRecentSongs(")) {
                if (!offBody.contains(call)) {
                    problems += "关源分支缺少 `$call` —— 关源必须「清缓存 + 重算 + 刷首页 + 过滤历史」（方案 §3.5）"
                }
            }
        }
        // 重开方向
        val onBody = runCatching { blockBody(setBody, "} else {") }.getOrNull()
        if (onBody == null) {
            problems += "找不到 `setSourceEnabled` 的重开分支（`} else {`）"
        } else {
            for (call in listOf("loadLibrary(", "loadYears(", "loadPlaylists(", "loadRandomSongs(")) {
                if (!onBody.contains(call)) {
                    problems += "重开分支缺少 `$call` —— 关源期间该源的流已被清空，重开必须按源显式重拉" +
                        "（方案 §3.5 / V16 / V19）"
                }
            }
        }
        return problems
    }

    /**
     * G4 判据：UI 侧只做透传。
     */
    private fun libraryBranchProblems(rawText: String): List<String> {
        val text = flatten(rawText)
        val problems = mutableListOf<String>()
        if (!text.contains("fun LibraryBranch")) {
            problems += "读到的不是 `LibraryBranch`（可能读错文件）"
        }
        if (!text.contains("visibleSongsPaging")) {
            problems += "缺 `visibleSongsPaging` —— UI 必须消费 VM 派生的可见集（方案 §3.3 G1）"
        }
        if (text.contains("mergeLibrarySongs")) {
            problems += "出现 `mergeLibrarySongs` —— 合并已迁入 VM；UI 侧再合并 = 开关翻转时 `remember` 的键" +
                "全都不变 ⇒ 永不重算 ⇒ NAS 段永远不会被置空（方案 §3.3 G1b / N1）"
        }
        return problems
    }

    /**
     * G5 判据：后端两处门控。
     */
    private fun backendGateProblems(radio: String, netVm: String): List<String> {
        val problems = mutableListOf<String>()
        if (!flatten(radio).contains("class SmartRadioManager")) {
            problems += "读到的不是 `SmartRadioManager`（可能读错文件）"
        }
        if (!radio.contains("nasMusicEnabled()")) {
            problems += "`SmartRadioManager` 没有判 `nasMusicEnabled()` —— 智能电台批次直接读 NAS 分页，" +
                "关 NAS 后首页批次里仍有 NAS 歌（方案 §3.2.4 / V13）"
        }
        if (!flatten(netVm).contains("class NetworkMusicViewModel")) {
            problems += "读到的不是 `NetworkMusicViewModel`（可能读错文件）"
        }
        if (!netVm.contains("getBaiduActiveSync()")) {
            problems += "`NetworkMusicViewModel` 没有用 `getBaiduActiveSync()` —— 百度网盘目录「播放全部」" +
                "必须走与 `registerService` 同源的「启用 **且** 已登录」判据（方案 §3.2.1 #20 / V15）"
        }
        return problems
    }

    /**
     * G6 判据：`SourceVisibility` 本体存在且判据齐全。
     */
    private fun sourceVisibilityProblems(rawText: String): List<String> {
        val text = flatten(rawText)
        val problems = mutableListOf<String>()
        if (!text.contains("class SourceVisibility")) {
            problems += "读到的不是 `SourceVisibility`（可能读错文件）"
            return problems
        }
        if (!text.contains("fun isVisible(type: MusicSourceType)")) {
            problems += "缺 `fun isVisible(type: MusicSourceType)` —— 唯一真相源的唯一入口（方案 §3.1）"
        }
        if (!text.contains("MusicSourceType.JAMENDO")) {
            problems += "缺 `MusicSourceType.JAMENDO` —— Jamendo 是独立开关 + clientId 双条件（方案 §3.1）"
        }
        if (!text.contains("MusicSourceType.DOWNLOAD")) {
            problems += "缺 `MusicSourceType.DOWNLOAD` —— 已下载歌曲恒可见：关本地音乐不得把它一起滤掉" +
                "（方案 §5.6 红线）"
        }
        return problems
    }

    // ═══════════════════ 辅助：正则 / 区域定位 / 读文件 ═══════════════════

    /** 原始库存流：关源时**永不清空**（它们是 S2 统计的读数来源，方案 §3.0）。 */
    private val INVENTORY_STREAMS: List<Pair<String, Regex>> = listOf(
        "_albums" to Regex("""_albums\s*\.\s*value\s*=\s*UiState\.Success\(\s*emptyList\(\)\s*\)"""),
        "_songs" to Regex("""_songs\s*\.\s*value\s*=\s*UiState\.Success\(\s*emptyList\(\)\s*\)"""),
        "_artists" to Regex("""_artists\s*\.\s*value\s*=\s*UiState\.Success\(\s*emptyList\(\)\s*\)"""),
        "_songsPaging" to Regex("""_songsPaging\s*\.\s*value\s*=\s*SongsPagingState\(\s*\)"""),
    )

    /** 开关翻转路径（关源 / 重开的唯一入口）。 */
    private val TOGGLE_PATH_MARKERS: List<Pair<String, String>> = listOf(
        "setSourceEnabled" to "fun setSourceEnabled(key: String, enabled: Boolean)",
        "clearSourceScopedCaches" to "private fun clearSourceScopedCaches(source: String)",
    )

    /** 允许清空原始库存流的位置（每一条都与「源开关」无关）。 */
    private val INVENTORY_CLEAR_ALLOWLIST: List<Pair<String, String>> = listOf(
        "loadLibrary（NAS 未连接）" to "private fun loadLibrary()",
        "retrySongsLoad（重试前清旧 error 状态）" to "fun retrySongsLoad()",
        "refreshLibrary（手动刷新曲库）" to "fun refreshLibrary()",
        "serverVM.onDisconnected（断开连接）" to "serverVM.onDisconnected = {",
    )

    /** NAS 分支：`MusicSourceType.NAS in sources` 与 `nasMusicEnabled()` 必须在同一个条件式里。 */
    private val NAS_BRANCH_GATE = Regex(
        """MusicSourceType\.NAS\s+in\s+sources[^)]{0,160}nasMusicEnabled\(\)"""
    )

    /** LOCAL 分支：同上。 */
    private val LOCAL_BRANCH_GATE = Regex(
        """MusicSourceType\.LOCAL\s+in\s+sources[^)]{0,160}localMusicEnabled\(\)"""
    )

    /** 结果兜底过滤：`merged` 被按「分支标签可见 **且** 歌曲归属可见」过滤一遍。 */
    private val MERGED_RESULT_FILTER = Regex(
        """merged\s*\.\s*filter\s*\{\s*visible\(\s*it\.source\s*\)\s*&&\s*visible\(\s*it\.song\s*\)\s*\}"""
    )

    /** `updateMergedData` 的 NAS 专辑段必须「不可见 ⇒ 传空」。 */
    private val NAS_ALBUMS_GATE = Regex("""nasAlbums\s*=\s*if\s*\(\s*nasVisible\s*\)[\s\S]{0,200}?else\s+emptyList\(\)""")

    /** `updateMergedData` 的 NAS 艺术家段必须「不可见 ⇒ 传空」。 */
    private val NAS_ARTISTS_GATE = Regex("""nasArtists\s*=\s*if\s*\(\s*nasVisible\s*\)[\s\S]{0,200}?else\s+emptyList\(\)""")

    /** `updateMergedData` 的百度索引段必须「不可见 ⇒ 传空」。 */
    private val BAIDU_SONGS_GATE = Regex("""baiduSongs\s*=\s*if\s*\(\s*baiduVisible\s*\)[\s\S]{0,200}?else\s+emptyList\(\)""")

    /** `visibleSongsPaging` 的 `totalCount` 口径：NAS 关时 NAS 的 totalCount 不参与。 */
    private val VISIBLE_PAGING_TOTAL_COUNT = Regex(
        """totalCount\s*=\s*\(\s*if\s*\(\s*nasVisible\s*\)\s*paging\.totalCount\s*else\s*0\s*\)"""
    )

    /** 「`MusicSourceType.X in sources` 与 `visible(MusicSourceType.X)` 在同一个条件式里」。 */
    private fun branchGateFor(type: String): Regex = Regex(
        """MusicSourceType\.${Regex.escape(type)}\s+in\s+sources[^)]{0,160}visible\(\s*MusicSourceType\.${Regex.escape(type)}\s*\)"""
    )

    /** 换行归一 + 空白折叠：重新缩进、CRLF 都不该弄断门。 */
    private fun flatten(text: String): String =
        text.replace("\r\n", "\n").replace(Regex("""\s+"""), " ")

    /**
     * 取 `marker` 之后那个 `{` 起的**花括号块**（不含最外层大括号）。
     *
     * ⛔ 用花括号配对而不是「下一个空行」—— 后者对格式变化极敏感。
     */
    private fun blockBody(text: String, marker: String): String {
        val range = blockRange(text, marker)
        return text.substring(range.first + 1, range.last - 1)
    }

    /** [blockBody] 的区间版本（含最外层大括号），用于「命中位置是否落在某区块内」的判定。 */
    private fun blockRange(text: String, marker: String): IntRange {
        val idx = text.indexOf(marker)
        check(idx >= 0) { "定位失败：找不到 `$marker`" }
        val open = text.indexOf('{', idx)
        check(open >= 0) { "定位失败：`$marker` 之后找不到 `{`" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..(i + 1)
                }
            }
            i++
        }
        error("定位失败：`$marker` 的花括号没有闭合")
    }

    /**
     * 取 `marker` 之后 `length` 个字符的**窗口**。
     *
     * 用途：属性型定义（`val x = combine(a, b) { … }`）没有单一的花括号块
     * —— 它的「实参列表」在花括号**之前** ⇒ 用窗口才拿得到全貌。
     */
    private fun windowAfter(text: String, marker: String, length: Int): String {
        val idx = text.indexOf(marker)
        check(idx >= 0) { "定位失败：找不到 `$marker`" }
        return text.substring(idx, minOf(text.length, idx + length))
    }

    /**
     * 定位 `app/src/main/...` 下的源文件并读出文本。
     *
     * ⛔ **不依赖 CWD**：Gradle 的测试工作目录是模块目录（`app/`），但在 IDE / 本地脚本里
     * 也可能是仓库根 ⇒ 三个候选目录都试一遍（与 `NetworkSourceSwitchGateTest` 同款）。
     */
    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),      // user.dir = app/
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),  // user.dir = 仓库根
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到源码文件：$relativePath；已尝试：${candidates.joinToString { it.absolutePath }}",
            file != null,
        )
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }
}
