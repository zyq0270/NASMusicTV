package com.nasmusic.tv.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⭐ 「舞台必须**主题无关**」的门禁（2026-11-07 架构改造）。
 *
 * ## 为什么要有它
 *
 * 舞台（`ui/components/VisualizerStage.kt`）本来**硬编码**了逐主题的特例
 * （`theme == VisualizerTheme.VINTAGE_TV` / `theme == VisualizerTheme.LYRICS_STAGE`）。
 * ⇒ **新增一套效果就得改舞台** —— 而舞台文件被本机私有分支的覆盖层遮蔽
 * （`app/src/e51-overlay/…`）⇒ 改它会让本地构建与 CI 行为分叉、还要重新同步覆盖层。
 *
 * ⇒ 改造后：效果自己声明 [EffectFurniture]，舞台只按声明渲染 ⇒
 *    **新增效果永远不需要碰舞台**。
 *
 * ⭐ 本门禁住在**测试树**里（测试树**不被**覆盖层遮蔽）⇒ **两个分支同时受保护**。
 *
 * ## 判据
 *
 * 1. 舞台里**不得**出现 `VisualizerTheme.VINTAGE_TV`（怀旧特例已迁到渲染器）；
 * 2. 舞台里出现的**主题枚举常量**必须 ⊆ [ALLOWED_THEME_CONSTANTS]
 *    （那三个都是**与叠加层家具无关**的耦合：照片墙生命周期 / E53 子模式切换 / E50 设色版切换；
 *    任何**新增**的主题常量 ⇒ 说明又有人把逐主题特例写回舞台 ⇒ 判红）；
 * 3. 老的三处硬编码标识符（`isVintageTv` / `hideLyricTopBar` / 柱宽与分界点那两个）必须消失；
 * 4. 舞台必须**从渲染器**读家具（含 `EffectFurniture` 与 `.furniture`）；
 * 5. 舞台**不得**自己写死内容区比例（4:3 只允许在渲染器里定义一次）。
 *
 * ## ⚠️ 为什么不是「一个字都不能提 VisualizerTheme」
 *
 * 舞台还有三处**必须**知道具体主题的逻辑，它们**不是**「叠加层家具」：
 * 照片墙的生命周期（进/出主题）、E53「歌词舞台」的子模式切换、E50「千里江山」的设色版切换。
 * 其中 E50 那处还被既有门禁 `QianliColorVersionSwitchTest` 钉住
 * （断言舞台源码里含 `VisualizerTheme.QIANLI_SCROLL`），E53 那处被
 * `LyricsStageManualSwitchTest` 钉住 ⇒ **不能**为了字面好看而删掉。
 * ⇒ 判据改成「**只允许这三个**」，并**逐条登记理由** ⇒ 既保住既有门禁，
 * 又挡住「又写回一个逐主题特例」。
 */
class VisualizerStageThemeAgnosticGateTest {

    // ── 允许出现在舞台里的主题常量（逐条登记理由）──────────────
    private val ALLOWED_THEME_CONSTANTS = setOf(
        // 照片墙的生命周期（进/出主题时启停扫描与时钟）—— 与叠加层家具无关。
        "PHOTO_WALL",
        // E53「歌词舞台」的子模式切换（上下键 / 上下滑）—— 与叠加层家具无关。
        "LYRICS_STAGE",
        // E50「千里江山」的设色版切换（上下键 / 上下滑）—— 与叠加层家具无关；
        // 且既有门禁 `QianliColorVersionSwitchTest` 直接断言舞台源码含这个常量。
        "QIANLI_SCROLL",
    )

    /** 老的三处硬编码标识符：改造后必须**全部消失**。 */
    private val LEGACY_IDENTIFIERS = listOf(
        "isVintageTv",
        "hideLyricTopBar",
        "vintagePillarDp",
        "vintageBandEndPx",
    )

    // ── 源码定位（照抄 PerfBudgetContractTest 的做法）────────────

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    private fun stageSource(): String =
        mainSourceRoot()
            .resolve("com/nasmusic/tv/ui/components/VisualizerStage.kt")
            .readText()
            .replace("\r\n", "\n")

    // ── 判据 ────────────────────────────────────────────────────

    /** 舞台里出现的**主题枚举常量**（只认大写常量名 ⇒ `VisualizerTheme.selectable` 这类函数调用不算）。 */
    private fun themeConstants(src: String): Set<String> =
        Regex("""\bVisualizerTheme\.([A-Z][A-Z0-9_]*)""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toSet()

    /** 全部违规项（空 = 通过）。负向自证把**坏样本**喂给同一个函数。 */
    private fun violations(src: String): List<String> {
        val clean = stripComments(src)
        val v = mutableListOf<String>()

        val consts = themeConstants(clean)
        if ("VINTAGE_TV" in consts) {
            v += "舞台里不得出现 VisualizerTheme.VINTAGE_TV（怀旧特例已迁到 VintageTvRenderer.furniture）"
        }
        val unexpected = consts - ALLOWED_THEME_CONSTANTS
        if (unexpected.isNotEmpty()) {
            v += "舞台里出现了未登记的主题常量 $unexpected（新增逐主题特例 ⇒ 判红）"
        }
        for (legacy in LEGACY_IDENTIFIERS) {
            if (Regex("""\b$legacy\b""").containsMatchIn(clean)) {
                v += "舞台里不得再出现硬编码标识符 $legacy"
            }
        }
        if (!Regex("""\bEffectFurniture\b""").containsMatchIn(clean)) {
            v += "舞台必须认识 EffectFurniture（叠加层家具由效果声明）"
        }
        if (!Regex("""\.furniture\b""").containsMatchIn(clean)) {
            v += "舞台必须从渲染器读家具（.furniture）"
        }
        if (Regex("""4f\s*/\s*3f""").containsMatchIn(clean)) {
            v += "舞台不得自己写死 4:3 比例（比例只在渲染器里定义一次）"
        }
        return v
    }

    // ── 正向断言 ────────────────────────────────────────────────

    @Test
    fun `舞台必须主题无关 只保留与家具无关的三处主题耦合`() {
        val v = violations(stageSource())
        assertTrue("舞台出现逐主题特例：\n${v.joinToString("\n")}", v.isEmpty())
    }

    @Test
    fun `空转自证 舞台源码非空且确实含被扫描的内容`() {
        val src = stageSource()
        assertTrue("舞台源码不应为空", src.length > 1000)
        assertTrue("舞台源码里应能扫到主题常量（扫描器自证）", themeConstants(stripComments(src)).isNotEmpty())
        // ⛔ 扫描器必须真的看得见主题常量（否则「没有违规」是空转）
        assertTrue(
            "扫描器自证：应能扫到 PHOTO_WALL / LYRICS_STAGE / QIANLI_SCROLL",
            themeConstants(stripComments(src)).containsAll(ALLOWED_THEME_CONSTANTS),
        )
    }

    // ── 负向自证：坏样本喂给**同一个**判据 ⇒ 必须挂 ─────────────

    @Test
    fun `负向N1 舞台里出现怀旧特例必须被判违规`() {
        val bad = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val isVintageTv = theme == VisualizerTheme.VINTAGE_TV
            }
        """.trimIndent()
        val v = violations(bad)
        assertTrue("怀旧特例必须被判违规", v.any { it.contains("VINTAGE_TV") })
    }

    @Test
    fun `负向N2 舞台里出现未登记的主题常量必须被判违规`() {
        val bad = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val x = theme == VisualizerTheme.SEASIDE
            }
        """.trimIndent()
        val v = violations(bad)
        assertTrue("未登记的主题常量必须被判违规", v.any { it.contains("SEASIDE") })
    }

    @Test
    fun `负向N3 注释里写主题常量不算违规 剥离后必须消失`() {
        // ⛔ 本仓被「判据匹配到自己的 KDoc」坑过 5 次 ⇒ 注释必须先剥掉再判。
        val withComment = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val f: EffectFurniture? = renderer.furniture.value
                // 历史说明：以前写的是 VisualizerTheme.VINTAGE_TV
                val x = 1
            }
        """.trimIndent()
        val v = violations(withComment)
        assertTrue("注释里的主题常量不得算违规：$v", v.isEmpty())
    }

    @Test
    fun `负向N4 硬编码标识符与写死的 4x3 比例必须被判违规`() {
        val bad = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val isVintageTv = theme == VisualizerTheme.PHOTO_WALL
                val hideLyricTopBar = isVintageTv
                val vintagePillarDp = 0.dp
                val vintageBandEndPx = 0f
                val ratio = 4f / 3f
            }
        """.trimIndent()
        val v = violations(bad)
        assertTrue("四个旧标识符都必须被判违规：$v", LEGACY_IDENTIFIERS.all { id -> v.any { it.contains(id) } })
        assertTrue("写死的 4:3 必须被判违规：$v", v.any { it.contains("4:3") })
    }

    @Test
    fun `负向N5 不读渲染器的家具必须被判违规`() {
        val bad = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val x = 1
            }
        """.trimIndent()
        val v = violations(bad)
        assertTrue("不读 EffectFurniture 必须被判违规：$v", v.any { it.contains("EffectFurniture") })
        assertTrue("不读 .furniture 必须被判违规：$v", v.any { it.contains(".furniture") })
    }

    @Test
    fun `负向N6 合法的舞台必须零违规`() {
        val good = """
            @Composable fun VisualizerStage(theme: VisualizerTheme) {
                val f: EffectFurniture? = renderer.furniture.value
                val x = if (theme == VisualizerTheme.PHOTO_WALL) 1 else 0
                val y = if (theme == VisualizerTheme.LYRICS_STAGE) 1 else 0
                val z = if (theme == VisualizerTheme.QIANLI_SCROLL) 1 else 0
            }
        """.trimIndent()
        assertEquals("合法的舞台必须零违规", emptyList<String>(), violations(good))
    }

    // ── 去注释（**嵌套块注释** + **字符串感知**）─────────────────
    //
    // ⚠️ 不能用「非贪婪正则匹配块注释」的写法：Kotlin 的块注释**可嵌套**，
    // 非贪婪正则会**在遇到的第一个块注释结束符处收尾**，把其后的真实代码一并吞掉；
    // 字符串里的双斜杠（如 URL）也会被误删。
    // ⚠️ 块注释内的换行**保留** ⇒ 行号与原文件对齐。
    // ⚠️ 本函数的注释里**不得出现块注释的起始符或结束符字面量**（会提前闭合本注释）。
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { depth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { depth--; i += 2 }
                    else { if (src[i] == '\n') sb.append('\n'); i++ }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
