package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 照片墙转场 —— **`clipPath` 红线**门禁（C46 + N11）。
 *
 * ## 红线
 *
 * minSdk 22 上，`DrawScope.clipPath` 走硬件加速的 hwui Region 路径，本项目在创维真机上
 * 复现过段错误（native 崩溃，`try/catch(Throwable)` 拦不住）。因此：
 *
 * 1. `PhotoTransitionId.CLIP_BASED` 里的效果在 `sdkInt < CLIP_MIN_SDK`（26）时必须在**枚举层**
 *    降级成 `CROSSFADE`（[PhotoTransitionId.effective]）—— 崩溃前没有任何 Java 层机会。
 * 2. 该集合必须与**真实实现**严格同步：新增一个用 `clipPath` 的转场却忘了登记 ⇒ 老平台直接崩。
 * 3. `clipPath` 只允许出现在**已登记**的实现文件里；其余位置出现即为漏网（N11：
 *    既有门禁全是单文件扫描，`photo/transitions/` 从来没人守）。
 *
 * ⚠️ 白名单里 `DesertSmokeBakes` 的 `cv.clipPath(path)` 是**软件画布**
 * （`Bitmap` + `android.graphics.Canvas`，烘焙期一次性执行），不经过 hwui
 * ⇒ 与红线无关，但必须写明理由，避免后人「反正有人用」地扩大范围。
 */
class PhotoTransitionClipGateTest {

    // ── 源码定位（沿用仓内既有写法：RenderedTest 的 user.dir 双候选）──

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (true) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) return candidate
            val alt = File(dir, "src/main/java")
            if (alt.isDirectory) return alt
            dir = dir.parentFile ?: error("找不到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
        }
    }

    private fun src(rel: String): File = File(mainSourceRoot(), rel)

    /** 全仓 `visualizer/` 下的 Kotlin 源文件（递归）。 */
    private fun visualizerFiles(): List<File> {
        val root = File(mainSourceRoot(), "com/nasmusic/tv/visualizer")
        assertTrue("找不到 visualizer 目录：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * 剥掉注释（块注释可嵌套 + 行注释 + 字符串/字符字面量），只留可执行代码。
     *
     * ⚠️ 必须保留**原始换行**（断言行号要能对上），所以逐字符重建而不是 `lines().joinToString`。
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var depth = 0
        var inStr = false
        var inChar = false
        var inRawStr = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                depth > 0 -> {
                    if (c == '/' && next == '*') { depth++; i++; sb.append(' ') }
                    else if (c == '*' && next == '/') { depth--; i++; sb.append(' ') }
                    else sb.append(if (c == '\n') '\n' else ' ')
                }
                inRawStr -> {
                    sb.append(c)
                    if (c == '"' && text.startsWith("\"\"\"", i)) {
                        sb.append("\"\"")
                        i += 2
                        inRawStr = false
                    }
                }
                inStr -> {
                    sb.append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> { sb.append(text[i + 1]); i++ }
                        c == '"' -> inStr = false
                    }
                }
                inChar -> {
                    sb.append(c)
                    when {
                        c == '\\' && i + 1 < text.length -> { sb.append(text[i + 1]); i++ }
                        c == '\'' -> inChar = false
                    }
                }
                c == '/' && next == '*' -> { depth = 1; i++; sb.append("  ") }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') { sb.append(' '); i++ }
                    if (i < text.length) sb.append('\n')
                    i++
                    continue
                }
                text.startsWith("\"\"\"", i) -> { sb.append("\"\"\""); i += 2; inRawStr = true }
                c == '"' -> { inStr = true; sb.append(c) }
                c == '\'' -> { inChar = true; sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // ── 门禁 ①：全仓 clipPath 只许出现在白名单 ────────────────────

    /**
     * 允许出现 `clipPath` 的文件 → 理由。
     *
     * 新增条目必须写明**为什么不会命中 hwui 红线**，不能只写「已确认安全」。
     */
    private val allowedClipPathFiles: Map<String, String> = mapOf(
        "photo/transitions/IrisTransitions.kt" to
            "IRIS_CIRCLE / WIPE_LINEAR —— 已在 CLIP_BASED 登记，sdkInt<26 由 effective() 降级",
        "photo/transitions/IrisP1Transitions.kt" to
            "IRIS_DIAMOND/HEXAGON/STAR/SHAPE_RANDOM/WIPE_CLOCK/WIPE_CROSS —— 已全部登记在 CLIP_BASED",
        "renderers/DesertSmokeBakes.kt" to
            "cv.clipPath 作用在 Bitmap 的**软件** Canvas 上（烘焙期一次性），不经 hwui",
        "photo/PhotoTransitionId.kt" to
            "只有 KDoc 提到 clipPath；本文件不含任何绘制代码",
    )

    @Test
    fun `clipPath 只允许出现在白名单文件的可执行代码里`() {
        val offenders = ArrayList<String>()
        for (f in visualizerFiles()) {
            val rel = f.relativeTo(File(mainSourceRoot(), "com/nasmusic/tv/visualizer")).invariantSeparatorsPath
            val code = stripComments(f.readText())
            if (!code.contains("clipPath")) continue
            if (rel !in allowedClipPathFiles) offenders.add(rel)
        }
        assertTrue(
            "以下文件出现了 clipPath 但不在白名单里（API 22 hwui SIGSEGV 红线）：$offenders\n" +
                "⇒ 要么改用「矩形裁剪 / 遮罩位图 / 逐列门控」表达，要么加进 allowedClipPathFiles 并写明为何安全",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `白名单里的理由不得为空且文件必须存在`() {
        for ((rel, reason) in allowedClipPathFiles) {
            assertTrue("白名单文件不存在：$rel", src("com/nasmusic/tv/visualizer/$rel").isFile)
            assertTrue("白名单条目 $rel 缺少理由", reason.isNotBlank())
        }
    }

    // ── 门禁 ②：CLIP_BASED 必须与真实实现严格同步 ────────────────

    /** 源码里每个「实现了某个转场 id」的声明 → 所在文件。 */
    private fun declaredIdsByFile(): Map<String, List<String>> {
        val re = Regex("""override\s+val\s+id\s*:\s*PhotoTransitionId\s*=\s*PhotoTransitionId\.([A-Z_]+)""")
        val out = LinkedHashMap<String, MutableList<String>>()
        for (f in visualizerFiles()) {
            val rel = f.relativeTo(File(mainSourceRoot(), "com/nasmusic/tv/visualizer")).invariantSeparatorsPath
            for (m in re.findAll(stripComments(f.readText()))) {
                out.getOrPut(rel) { ArrayList() }.add(m.groupValues[1])
            }
        }
        return out
    }

    @Test
    fun `凡在含 clipPath 的文件里实现的转场都必须登记进 CLIP_BASED`() {
        val declared = declaredIdsByFile()
        val missing = ArrayList<String>()
        for ((rel, ids) in declared) {
            val code = stripComments(src("com/nasmusic/tv/visualizer/$rel").readText())
            if (!code.contains("clipPath")) continue
            for (name in ids.distinct()) {
                if (PhotoTransitionId.entries.none { it.name == name }) continue
                val id = PhotoTransitionId.valueOf(name)
                if (id !in PhotoTransitionId.CLIP_BASED) missing.add("$rel → $name")
            }
        }
        assertTrue(
            "以下实现用了 clipPath 却没进 CLIP_BASED（sdkInt<26 不会降级 ⇒ 老平台崩溃）：$missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `CLIP_BASED 里不得有已无 clipPath 使用的陈旧条目`() {
        val declared = declaredIdsByFile()
        val clipFiles = declared.keys.filter {
            stripComments(src("com/nasmusic/tv/visualizer/$it").readText()).contains("clipPath")
        }
        val stillClip = clipFiles.flatMap { declared[it] ?: emptyList() }.toSet()
        val stale = PhotoTransitionId.CLIP_BASED.filter { it.name !in stillClip }
        assertTrue(
            "CLIP_BASED 里的 $stale 已找不到 clipPath 实现（要么实现改法了，要么登记写错了）",
            stale.isEmpty(),
        )
    }

    // ── 门禁 ③：降级行为本身 ──────────────────────────────────────

    @Test
    fun `sdkInt 小于 26 时 CLIP_BASED 一律降级为 CROSSFADE`() {
        assertTrue("CLIP_BASED 不应为空", PhotoTransitionId.CLIP_BASED.isNotEmpty())
        for (id in PhotoTransitionId.CLIP_BASED) {
            for (sdk in 22 until PhotoTransitionId.CLIP_MIN_SDK) {
                assertEquals(
                    "$id 在 API $sdk 必须降级成 CROSSFADE（否则 hwui 段错误）",
                    PhotoTransitionId.CROSSFADE,
                    PhotoTransitionId.effective(id, sdk),
                )
            }
        }
    }

    @Test
    fun `sdkInt 大于等于 26 时 CLIP_BASED 不再被 clip 降级`() {
        for (id in PhotoTransitionId.CLIP_BASED) {
            for (sdk in PhotoTransitionId.CLIP_MIN_SDK..40) {
                // 这些效果都没有 shader 降级（degradeTo == null）⇒ 应原样返回
                assertEquals(
                    "$id 在 API $sdk 不应被 clip 降级",
                    id,
                    PhotoTransitionId.effective(id, sdk),
                )
            }
        }
    }

    @Test
    fun `CROSSFADE 自身绝不在 CLIP_BASED 里`() {
        // 否则降级会变成死循环（effective(CROSSFADE) 还是 CROSSFADE，没问题；但语义上不该登记）
        assertFalse(
            "CROSSFADE 是降级目标，不得登记为 clip 类",
            PhotoTransitionId.CROSSFADE in PhotoTransitionId.CLIP_BASED,
        )
    }

    // ── 门禁 ④：取转场的那条路径必须过 effective ────────────────

    @Test
    fun `PhotoWallController 取转场时两条分支都必须过 effective`() {
        val code = stripComments(
            src("com/nasmusic/tv/visualizer/photo/PhotoWallController.kt").readText()
        )
        val start = code.indexOf("private fun pickTransition(")
        assertTrue("找不到 pickTransition()", start >= 0)
        val end = code.indexOf("private fun ", start + 10).let { if (it < 0) code.length else it }
        val body = code.substring(start, end)
        val calls = Regex("""PhotoTransitionId\.effective\(""").findAll(body).count()
        assertTrue(
            "pickTransition() 里 effective() 至少要覆盖「固定档 + 随机池兜底」两条路径，实际 $calls 处",
            calls >= 2,
        )
        assertTrue(
            "固定档必须再校验 available()（C47：未实现的枚举会让照片墙永久黑屏）",
            body.contains("PhotoTransitionRegistry.available()"),
        )
    }
}
