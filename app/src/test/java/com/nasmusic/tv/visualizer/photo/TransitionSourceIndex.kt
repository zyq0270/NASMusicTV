package com.nasmusic.tv.visualizer.photo

import java.io.File

/**
 * 转场实现的**源码索引**（门禁共用，2026-11-11 新增）
 *
 * ## 为什么需要它
 *
 * 「这个效果是用什么机制实现的」这个问题，**不能靠文件粒度回答**：
 *
 * | 反例 | 说明 |
 * |---|---|
 * | P0 的 8 个效果 | `SLIDE_LEFT/RIGHT/UP/DOWN`（`SlideTransition`）、`ZOOM_IN/OUT`（`ZoomTransition`）、`BLINDS_H/V`（`BlindsTransition`）都是**一个实现类 + 构造参数传 id** ⇒ 扫 `override val id = PhotoTransitionId.X` 会**扫不到**它们 |
 * | `ScanlineDissolveTransition` | 与遮罩型效果**同文件**（`DissolveP1Transitions.kt`）⇒ 文件粒度会把它误判成遮罩型 |
 * | `WIPE_CROSS` | 与 `clipPath` 效果**同文件**（`IrisP1Transitions.kt`），但它**用的是 `clipRect` 不是 `clipPath`** ⇒ 文件粒度会把它误判成 clip 类 ⇒ 2026-11-11 就是这么被误登记进 `CLIP_BASED` 的（API 22 电视上一直看不到这个效果） |
 * | P2 的 12 个遮罩效果 | `BlendMode.DstIn` 与 `settleFrom` 都在**基类** `MaskDissolveTransition` 里 ⇒ 只看子类体会判成「几何型」 |
 *
 * ⇒ 必须**按效果**（而不是按文件 / 按类）解析：把「自身类体 + 父类体 + 内嵌类体」合起来看。
 *
 * ## 权威映射来自**注册表**
 *
 * `PhotoTransitionRegistry.impls` 是「id → 实现类」的**唯一真相源**
 * （`PhotoTransitionId` 枚举只是清单，实现可能共用一个类）⇒ 从注册表解析，
 * 而不是从源码里反推「谁声明了这个 id」。
 *
 * ## ⛔ 这是**源码级**索引，不是像素级
 *
 * Windows 上 Robolectric 的 NATIVE 图形模式不可用 ⇒ 单测做不了像素回读
 * ⇒ 只能做「机制归类 + 源码断言」这一层（见 `PhotoTransitionCompletionGateTest`）。
 */
internal class TransitionSourceIndex private constructor(

    /** id 名 → 实现类名（注册表解析结果） */
    val classOfId: Map<String, String>,

    /** 实现类名 → 该类的「有效源码」（自身类体 + 父类体 + 内嵌类体） */
    val effectiveCodeOfClass: Map<String, String>,

    /** 实现类名 → 所在文件名（相对 `transitions/`） */
    val fileOfClass: Map<String, String>,
) {

    /** 注册表里的全部 id 名 */
    val ids: Set<String> get() = classOfId.keys

    /** 某个 id 的有效源码（找不到实现类 ⇒ 空串） */
    fun effectiveCode(idName: String): String =
        classOfId[idName]?.let { effectiveCodeOfClass[it] } ?: ""

    /** 某个 id 的实现所在文件名（找不到 ⇒ null） */
    fun fileOf(idName: String): String? =
        classOfId[idName]?.let { fileOfClass[it] }

    /** 某个 id 是否用**遮罩合成**（`BlendMode.DstIn`）—— 唯一会在 `p = 1` 留旧图的机制 */
    fun isMaskType(idName: String): Boolean = effectiveCode(idName).contains("BlendMode.DstIn")

    /** 某个 id 是否用 `clipPath` 裁剪（API 22 上会 SIGSEGV ⇒ 必须登记进 `CLIP_BASED`） */
    fun usesClipPath(idName: String): Boolean = effectiveCode(idName).contains("clipPath(")

    /** 某个 id 是否有「末段整幅补画」（遮罩型必须有，否则 `p = 1` 仍透出旧图） */
    fun hasSettle(idName: String): Boolean {
        val code = effectiveCode(idName)
        return code.contains("settleFrom") || code.contains("SETTLE_FROM")
    }

    companion object {

        /** 建索引（扫注册表 + `transitions/` 全部实现文件） */
        fun build(): TransitionSourceIndex {
            val photoDir = File(SourceScan.mainSourceRoot(), "com/nasmusic/tv/visualizer/photo")
            val transitionsDir = File(photoDir, "transitions")
            val registryFile = File(photoDir, "PhotoTransitionRegistry.kt")

            // ── ① 注册表：id → 实现类名 ──
            val registryCode = SourceScan.stripComments(registryFile.readText())
            val classOfId = LinkedHashMap<String, String>()
            val entryRe = Regex("""PhotoTransitionId\.([A-Z_]+)\s+to\s+([A-Za-z0-9_]+)\s*\(""")
            for (m in entryRe.findAll(registryCode)) {
                classOfId[m.groupValues[1]] = m.groupValues[2]
            }

            // ── ② 实现文件：类名 → 类体 / 父类 / 内嵌类 ──
            val classes = LinkedHashMap<String, ClassInfo>()
            val files = transitionsDir.listFiles { f -> f.isFile && f.extension == "kt" }
                ?.sortedBy { it.name } ?: emptyList()
            for (f in files) {
                val code = SourceScan.stripComments(f.readText())
                for (info in parseClasses(code, f.name)) {
                    // 同名类（跨文件重复）⇒ 保留先解析到的，登记到「冲突」里由门禁报
                    if (!classes.containsKey(info.name)) classes[info.name] = info
                }
            }

            // ── ③ 有效源码 = 自身 + 父类 + 内嵌类 ──
            val effective = HashMap<String, String>()
            fun effectiveOf(name: String, seen: MutableSet<String>): String {
                effective[name]?.let { return it }
                val info = classes[name] ?: return ""
                if (!seen.add(name)) return ""       // 防御自引用
                val sb = StringBuilder(info.body)
                info.superName?.let { sb.append('\n').append(effectiveOf(it, seen)) }
                for (nested in info.nested) {
                    sb.append('\n').append(effectiveOf(nested, seen))
                }
                seen.remove(name)
                val out = sb.toString()
                // ⚠️ 必须写回缓存 —— 否则整张索引的「有效源码」都是空串
                //   （踩坑：2026-11-11 第一次写时忘了这一行 ⇒ 遮罩型被判成 0 个）
                if (out.isNotEmpty()) effective[name] = out
                return out
            }
            for (name in classes.keys) effectiveOf(name, HashSet())

            val fileOfClass = classes.mapValues { it.value.fileName }
            return TransitionSourceIndex(classOfId, effective, fileOfClass)
        }

        // ────────────────────── 类解析 ──────────────────────

        /** 一个类的结构信息 */
        private data class ClassInfo(
            val name: String,
            val fileName: String,
            val body: String,
            val superName: String?,
            val nested: List<String>,
        )

        private val DECL_RE =
            Regex("""(?:internal\s+|private\s+|public\s+|protected\s+|open\s+|abstract\s+|sealed\s+|final\s+|data\s+|value\s+)*(?:class|object)\s+([A-Za-z0-9_]+)""")

        private val NESTED_RE = Regex("""(?:class|object)\s+([A-Za-z0-9_]+)""")

        /** 解析一个文件里的全部类（含内嵌类） */
        private fun parseClasses(code: String, fileName: String): List<ClassInfo> {
            val out = ArrayList<ClassInfo>()
            for (m in DECL_RE.findAll(code)) {
                val name = m.groupValues[1]
                var i = m.range.last + 1

                // 泛型参数 `<...>`
                i = skipWs(code, i)
                if (i < code.length && code[i] == '<') i = skipBalanced(code, i, '<', '>')

                // 主构造参数 `(...)`
                i = skipWs(code, i)
                if (i < code.length && code[i] == '(') i = skipBalanced(code, i, '(', ')')

                // 父类 `: Super(...)`
                i = skipWs(code, i)
                var superName: String? = null
                if (i < code.length && code[i] == ':') {
                    i = skipWs(code, i + 1)
                    val start = i
                    while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    superName = code.substring(start, i).takeIf { it.isNotEmpty() }
                    i = skipWs(code, i)
                    if (i < code.length && code[i] == '(') i = skipBalanced(code, i, '(', ')')
                }

                // 类体 `{ ... }`
                i = skipWs(code, i)
                if (i >= code.length || code[i] != '{') continue
                val bodyEnd = skipBalanced(code, i, '{', '}')
                if (bodyEnd <= i) continue
                val body = code.substring(i, bodyEnd)

                val nested = NESTED_RE.findAll(body).map { it.groupValues[1] }.toList()
                out += ClassInfo(name, fileName, body, superName, nested)
            }
            return out
        }

        private fun skipWs(text: String, from: Int): Int {
            var i = from
            while (i < text.length && text[i].isWhitespace()) i++
            return i
        }

        /**
         * 从 `text[from]` 处的配对符开始，跳过**整段**配对内容，返回结束位置（不含）。
         *
         * ⚠️ 跳过字符串 / 字符字面量 ⇒ 里面的括号 / 花括号不会把配对计数带偏
         * （Kotlin 模板字符串 `"${x}"` 里也有花括号）。
         */
        private fun skipBalanced(text: String, from: Int, open: Char, close: Char): Int {
            if (from >= text.length || text[from] != open) return from
            var i = from + 1
            var depth = 1
            while (i < text.length) {
                val c = text[i]
                // ⚠️ 字符串 / 字符字面量整段跳过 ⇒ 里面的括号不会把配对计数带偏。
                // ⚠️ 用 `continue` 而不是给 `i` 赋值后再 `i++` —— 那会多吃掉字面量后的一个字符。
                if (c == '"') {
                    i = skipString(text, i)
                    continue
                }
                if (c == '\'') {
                    i = skipChar(text, i)
                    continue
                }
                if (c == open) {
                    depth++
                } else if (c == close) {
                    depth--
                    if (depth == 0) return i + 1
                }
                i++
            }
            return text.length
        }

        /** 跳过字符串字面量（含 `"""` 原始字符串），返回结束位置（不含） */
        private fun skipString(text: String, from: Int): Int {
            if (text.startsWith("\"\"\"", from)) {
                var i = from + 3
                while (i < text.length && !text.startsWith("\"\"\"", i)) i++
                return minOf(i + 3, text.length)
            }
            var i = from + 1
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text[i] == '"') return i + 1
                i++
            }
            return text.length
        }

        /** 跳过字符字面量 */
        private fun skipChar(text: String, from: Int): Int {
            var i = from + 1
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text[i] == '\'') return i + 1
                i++
            }
            return text.length
        }
    }
}
