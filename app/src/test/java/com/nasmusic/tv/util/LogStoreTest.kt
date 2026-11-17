package com.nasmusic.tv.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 运行日志落盘存储（`LogStore`）的纯 JVM 单测。
 *
 * 设计约束（见 `LogStore` KDoc）：
 *  - **不依赖 Android 类型**（只收 `File`），因此能在免 Gradle 通道里直接跑；
 *  - 写入走单线程执行器，测试用 [LogStore.appendBlocking] 保证顺序与可见性；
 *  - 格式 = TSV + 转义（`\\` / `\t` / `\n` / `\r`），畸形行必须被跳过而不是整文件报废；
 *  - 超限轮转必须**保留最新**（宁可丢老日志，也不能丢刚发生的崩溃现场）。
 */
class LogStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("logstore-test").toFile()
        LogStore.init(root)
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    // ───────────────────── 编解码 ─────────────────────

    @Test
    fun `编码解码必须原样往返特殊字符`() {
        val message = "含制表符\t与换行\n以及反斜杠\\和回车\r结束"
        val stack = "java.lang.IllegalStateException: boom\n\tat a.b.C(D.kt:1)"
        val line = LogStore.encode(LogStore.LEVEL_ERROR, "Tag\tX", message, stack)

        // 单行协议：编码结果里不得出现真实的 \t / \n（否则解码会错位）
        assertEquals("编码后不得含真实换行", 0, line.count { it == '\n' })
        assertEquals("编码后字段分隔符之外不得含真实制表符", 4, line.count { it == '\t' })

        val entry = LogStore.decode(line)
        assertNotNull("合法行必须能解码", entry)
        entry!!
        assertEquals(LogStore.LEVEL_ERROR, entry.level)
        assertEquals("Tag\tX", entry.tag)
        assertEquals(message, entry.message)
        assertEquals(stack, entry.stackTrace)
    }

    @Test
    fun `无堆栈时 stackTrace 必须为空`() {
        val entry = LogStore.decode(LogStore.encode(LogStore.LEVEL_WARN, "T", "m", null))
        assertNotNull(entry)
        assertNull("没有异常就不该凭空造堆栈", entry!!.stackTrace)
    }

    @Test
    fun `畸形行必须被跳过而不是崩溃`() {
        assertNull("空行", LogStore.decode(""))
        assertNull("字段不足", LogStore.decode("123\tE\tTAG"))
        assertNull("时间戳非法", LogStore.decode("abc\tE\tTAG\tmsg"))
        assertNull("级别非法", LogStore.decode("123\tZ\tTAG\tmsg"))
    }

    @Test
    fun `所有级别都必须能落盘并解码（含 X 调试）`() {
        // 写入顺序固定，才能断言倒序结果（ALL_LEVELS 是 Set，别拿它的迭代次序当写入次序）
        val written = listOf(
            LogStore.LEVEL_DEBUG,
            LogStore.LEVEL_INFO,
            LogStore.LEVEL_WARN,
            LogStore.LEVEL_ERROR,
            LogStore.LEVEL_DIAG,
        )
        written.forEach { LogStore.appendBlocking(it, "L", "msg-$it", null) }
        val levels = LogStore.snapshot().entries.map { it.level }
        assertEquals(
            "任何一级都不得被 decode 丢掉（X 一旦漏进 ALL_LEVELS，整行会被无声丢弃）",
            written.asReversed(),
            levels,
        )
        assertEquals("ALL_LEVELS 必须覆盖上面写入的每一级", written.toSet(), LogStore.ALL_LEVELS)
    }

    @Test
    fun `级别过滤必须按严重度包含更高级别`() {
        val entries = listOf(
            LogStore.LEVEL_DEBUG,
            LogStore.LEVEL_INFO,
            LogStore.LEVEL_WARN,
            LogStore.LEVEL_ERROR,
        ).map { LogStore.decode(LogStore.encode(it, "T", "m", null))!! }

        assertEquals("全部（D）必须一条不少", 4, LogStore.filterAtLeast(entries, LogStore.LEVEL_DEBUG).size)
        assertEquals("I 及以上", 3, LogStore.filterAtLeast(entries, LogStore.LEVEL_INFO).size)
        assertEquals("W 及以上", 2, LogStore.filterAtLeast(entries, LogStore.LEVEL_WARN).size)
        val errors = LogStore.filterAtLeast(entries, LogStore.LEVEL_ERROR)
        assertEquals("E 只留错误", 1, errors.size)
        assertEquals(LogStore.LEVEL_ERROR, errors[0].level)
        assertEquals(
            "过滤不得改变原顺序",
            listOf(LogStore.LEVEL_WARN, LogStore.LEVEL_ERROR),
            LogStore.filterAtLeast(entries, LogStore.LEVEL_WARN).map { it.level },
        )
    }

    @Test
    fun `未知过滤级别必须回落全部而不是滤空`() {
        // ⚠️ 脏值用 "Z" 不用 "X"：X 从 v2.39.6 起是**合法**级别（调试读数），
        // 拿它当"未知级别"会让这条用例悄悄变成空转。
        assertEquals(LogStore.LEVEL_DEBUG, LogStore.normalizeLevel("Z"))
        assertEquals(LogStore.LEVEL_DIAG, LogStore.normalizeLevel(LogStore.LEVEL_DIAG))
        assertEquals(LogStore.LEVEL_WARN, LogStore.normalizeLevel(LogStore.LEVEL_WARN))
        val entries = listOf("D", "W").map { LogStore.decode(LogStore.encode(it, "T", "m", null))!! }
        assertEquals(
            "持久化的脏值不能把日志页滤成空白",
            entries.size,
            LogStore.filterAtLeast(entries, "Z").size,
        )
    }

    /**
     * 「调试」档（X）的过滤语义 —— 它是**另一类**而不是阶梯上的一级：
     *  - 选「全部」(D) 含 X，X 不会凭空消失；
     *  - 选 I/W/E **不含** X，否则用户想看告警时会被一堆设备读数挤掉现场；
     *  - 选 X **只有** X，这是不连 adb 回传焦点/设备读数的唯一视图。
     */
    @Test
    fun `调试档必须单列而不参与严重度阶梯`() {
        val entries = listOf(
            LogStore.LEVEL_DEBUG,
            LogStore.LEVEL_INFO,
            LogStore.LEVEL_WARN,
            LogStore.LEVEL_ERROR,
            LogStore.LEVEL_DIAG,
        ).map { LogStore.decode(LogStore.encode(it, "T", "m-$it", null))!! }

        assertEquals(
            "「全部」必须含 X（否则用户以为设备读数没产生）",
            listOf("D", "I", "W", "E", "X"),
            LogStore.filterForSelection(entries, LogStore.LEVEL_DEBUG).map { it.level },
        )
        assertEquals(
            "I/W/E 档必须排除 X（调试读数不得挤掉告警现场）",
            listOf("W", "E"),
            LogStore.filterForSelection(entries, LogStore.LEVEL_WARN).map { it.level },
        )
        assertEquals(
            "选中 X 只看 X",
            listOf("X"),
            LogStore.filterForSelection(entries, LogStore.LEVEL_DIAG).map { it.level },
        )
        assertEquals(
            "E 档不得混入 X",
            listOf("E"),
            LogStore.filterForSelection(entries, LogStore.LEVEL_ERROR).map { it.level },
        )
    }

    /** 负向自证：若 X 被误当成「更高级别」塞进阶梯，上面那条会全绿而这条会红。 */
    @Test
    fun `负向自证 X 的严重度不得高于 W 或 E`() {
        assertTrue(
            "X 必须与 D 同级（0）：否则 I/W/E 档会把设备读数当成告警显示出来",
            LogStore.severityOf(LogStore.LEVEL_DIAG) == 0,
        )
        assertTrue(
            "X 与 D 同级 ⇒ filterAtLeast 的「全部」自然含 X，无需特判",
            LogStore.filterAtLeast(
                listOf(LogStore.decode(LogStore.encode(LogStore.LEVEL_DIAG, "T", "m", null))!!),
                LogStore.LEVEL_DEBUG,
            ).isNotEmpty(),
        )
    }

    // ───────────────────── 读写顺序 ─────────────────────

    @Test
    fun `读取必须按时间倒序并受 limit 约束`() {
        repeat(5) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "T", "msg-$i", null)
        }
        val snap = LogStore.snapshot(limit = 3)
        assertEquals("limit 必须生效", 3, snap.entries.size)
        assertEquals("最新一条必须在最前", "msg-4", snap.entries[0].message)
        assertEquals("其次", "msg-3", snap.entries[1].message)
        assertEquals("再次", "msg-2", snap.entries[2].message)
        assertTrue("占用字节数应为正", snap.fileSizeBytes > 0)
    }

    @Test
    fun `清空后必须一条不剩`() {
        repeat(3) { LogStore.appendBlocking(LogStore.LEVEL_ERROR, "T", "e$it", null) }
        assertTrue(LogStore.snapshot().entries.isNotEmpty())
        LogStore.clear()
        assertEquals("clear 后必须为空", 0, LogStore.snapshot().entries.size)
        assertEquals("clear 后文件必须归零", 0L, LogStore.fileSizeBytes())
    }

    // ───────────────────── 轮转 ─────────────────────

    @Test
    fun `超过上限必须轮转且保留最新记录`() {
        // 每条约 120 字节 ⇒ 4000 条 ≈ 480KB，必然越过 256KB 上限
        val payload = "x".repeat(100)
        repeat(4000) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "ROT", "$i-$payload", null)
        }
        val size = LogStore.fileSizeBytes()
        assertTrue("轮转后文件必须小于上限（实际 $size）", size <= LogStore.MAX_BYTES)
        val entries = LogStore.snapshot(limit = LogStore.KEEP_LINES).entries
        assertTrue("轮转后必须仍有记录", entries.isNotEmpty())
        assertEquals("最新一条必须保住", "3999-$payload", entries[0].message)
        assertTrue(
            "最老一条（0-…）必须已被淘汰，否则说明轮转没生效",
            entries.none { it.message.startsWith("0-") },
        )
    }

    /** 负向自证：上限常量必须真的比"触发轮转的量"小，否则上一条会空转。 */
    @Test
    fun `轮转用例不是空转`() {
        assertTrue("MAX_BYTES 必须小于 4000×120 字节，否则轮转用例测不到东西", LogStore.MAX_BYTES < 4000L * 120L)
        assertTrue("KEEP_LINES 必须小于写入条数", LogStore.KEEP_LINES < 4000)
    }

    // ───────────────────── 详情「复制」全文（v2.39.2） ─────────────────────

    /**
     * 真机反馈「报错信息文本无法长按选择进行复制」⇒ 加了复制按钮。
     * 复制内容必须是**全文**：级别 + 时间 + tag + 正文 + **完整堆栈**。
     * 只要漏了堆栈，粘出去的日志就没法定位问题，等于白复制。
     */
    @Test
    fun `复制全文必须包含级别 时间 tag 正文与完整堆栈`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat a.b.C(D.kt:1)\n\tat e.f.G(H.kt:2)"
        val entry = LogEntry(
            timestamp = 1_766_000_000_000L,
            level = LogStore.LEVEL_ERROR,
            tag = "MetingDiag",
            message = "parseSongs: no songs",
            stackTrace = stack,
        )
        val text = entry.toClipboardText()
        assertTrue("缺级别", text.contains("E"))
        assertTrue("缺 tag", text.contains("MetingDiag"))
        assertTrue("缺正文", text.contains("parseSongs: no songs"))
        assertTrue("缺堆栈首行", text.contains("IllegalStateException: boom"))
        assertTrue("缺堆栈末行（必须完整，不能只截首行）", text.contains("at e.f.G(H.kt:2)"))
        assertTrue(
            "时间戳必须是可 grep 的固定格式：实际 ${text.lineSequence().first()}",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").containsMatchIn(text),
        )
    }

    @Test
    fun `无堆栈的日志复制全文不得出现空堆栈段`() {
        val entry = LogEntry(0L, LogStore.LEVEL_WARN, "T", "只有正文", null)
        val text = entry.toClipboardText()
        assertTrue("正文必须保留", text.contains("只有正文"))
        assertEquals("无堆栈时只能是 2 行（头 + 正文）", 2, text.lines().size)
    }

    // ───────────────────── 完整性与行数自证（v2.39.2 真机「复制不全」） ─────────────────────

    /**
     * 真机反馈「复制粘出来本身就少」⇒ 需要能**量出来**完整性的判据。
     *
     * 这里走完整链路：`encode` → 落盘 → `snapshot()` → `toClipboardText()`，逐字符比对。
     * 只要有一个环节吃掉内容（转义、分行、读取、拼接），这条就红。
     * ⚠️ 不用 `Throwable` 造堆栈：`stackTraceToString()` 会加首行和帧，无法逐字符比对，
     * 真实异常路径由下一条用例单独锁。
     */
    @Test
    fun `超长堆栈必须无损往返`() {
        val stack = buildString {
            append("java.lang.IllegalStateException: boom\t含制表符\r含回车\n")
            for (i in 1..80) append("\tat com.example.C$i.m$i(F$i.kt:$i)\n")
            append("Caused by: java.io.IOException: 反斜杠\\路径\\x\n")
            append("\t... 12 more")
        }
        val message = "消息\t带制表符\n第二行\\结尾"
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        file.writeText(
            LogStore.encode(LogStore.LEVEL_ERROR, "无损\tTAG", message, stack) + "\n",
            Charsets.UTF_8,
        )

        val entry = LogStore.snapshot().entries.first()
        assertEquals("堆栈必须逐字符一致", stack, entry.stackTrace)
        assertEquals("正文必须逐字符一致", message, entry.message)
        assertEquals("tag 必须逐字符一致", "无损\tTAG", entry.tag)
        assertTrue("完整写入的记录不得被标记为截断", !entry.truncated)
        assertTrue("复制全文必须以完整堆栈结尾", entry.toClipboardText().endsWith(stack))
        assertEquals(
            "行数必须与实际一致（正文 2 行 + 堆栈 ${stack.count { it == '\n' } + 1} 行）",
            2 + stack.count { it == '\n' } + 1,
            entry.totalLines,
        )
    }

    /** 真实异常路径（`appendBlocking` + `stackTraceToString()`）也必须整段留下来。 */
    @Test
    fun `真实异常堆栈经同步写入后必须完整`() {
        val throwable = IllegalStateException("boom")
        LogStore.appendBlocking(LogStore.LEVEL_ERROR, "REAL", "出错了", throwable)
        val entry = LogStore.snapshot().entries.first()
        assertEquals(throwable.stackTraceToString(), entry.stackTrace)
        assertTrue("首行异常类型不能丢", entry.stackTrace!!.startsWith("java.lang.IllegalStateException: boom"))
    }

    /** 纯函数：行数/字符数是「全不全」的唯一客观判据，必须单独锁住。 */
    @Test
    fun `行数与字符数按正文加堆栈合计`() {
        val entry = LogEntry(0L, LogStore.LEVEL_ERROR, "T", "a\nb", "c\nd\ne")
        assertEquals(5, entry.totalLines)
        assertEquals(8, entry.totalChars)
        val noStack = LogEntry(0L, LogStore.LEVEL_WARN, "T", "单行", null)
        assertEquals(1, noStack.totalLines)
        assertEquals(2, noStack.totalChars)
        val empty = LogEntry(0L, LogStore.LEVEL_WARN, "T", "", null)
        assertEquals("空正文不得算成 1 行", 0, empty.totalLines)
    }

    /**
     * **写入被打断**的最后一条必须被标记：文件末尾没有换行 ⇒ 那次 `writeLine` 没写完
     * （进程被杀 / 断电 / 存储满）。不标记的话，用户只会看到「复制出来的比预期的少」。
     */
    @Test
    fun `末尾没有换行的最后一条必须标记为截断`() {
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        val l1 = LogStore.encode(LogStore.LEVEL_WARN, "T1", "第一条", null)
        val l2 = LogStore.encode(LogStore.LEVEL_ERROR, "T2", "第二条", "完整堆栈\nat a.b(C.kt:1)")
        val partial = LogStore.encode(LogStore.LEVEL_ERROR, "T3", "第三条", "堆栈前半截")
        // ⚠️ 末尾故意不写 \n —— 模拟写入被中断
        file.writeText("$l1\n$l2\n$partial", Charsets.UTF_8)

        val entries = LogStore.snapshot().entries
        assertEquals(3, entries.size)
        assertEquals("T3", entries[0].tag)
        assertTrue("被打断的最后一条必须标记 truncated", entries[0].truncated)
        assertTrue("前面写完整的记录不得被误标", !entries[1].truncated && !entries[2].truncated)
    }

    /** 负向自证：正常以换行结尾的文件，任何一条都不得被判成截断。 */
    @Test
    fun `负向自证 完整文件不得误判截断`() {
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        val l1 = LogStore.encode(LogStore.LEVEL_WARN, "T1", "第一条", null)
        file.writeText("$l1\n", Charsets.UTF_8)
        val entries = LogStore.snapshot().entries
        assertEquals(1, entries.size)
        assertTrue("护栏空转：完整文件被误判成截断", !entries[0].truncated)
    }

    // ───────────────────── §10.401 分级配额轮转 ─────────────────────

    /**
     * §10.401：高频 W 级噪音不得把 E 级崩溃现场挤出保留窗口。
     * 写入大量 W 级 + 少量 E 级，触发轮转后 E 级必须全部保留。
     */
    @Test
    fun `分级配额 高频W噪音不得挤掉E级崩溃现场`() {
        // 用较长的行减少写入次数（fd.sync 是主要耗时），同时保证触发多次轮转
        val payload = "x".repeat(600)
        // 阶段 1：写入大量 W 级噪音，触发多次轮转
        repeat(600) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "NOISE", "$i-$payload", null)
        }
        // 阶段 2：写入 E 级崩溃现场
        repeat(50) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_ERROR, "CRASH", "crash-$i", null)
        }
        // 诊断：写入 E 级后，文件中应能读到 50 条 E 级
        val afterE = LogStore.snapshot(limit = LogStore.KEEP_LINES).entries
        val eCountBefore = afterE.filter { it.level == LogStore.LEVEL_ERROR }.size
        assertEquals(
            "写入 E 级后应能看到 50 条 E 级（实际 ${eCountBefore} 条）",
            50,
            eCountBefore,
        )
        // 阶段 3：再刷一批 W 级把文件推过上限、触发多次轮转
        repeat(600) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "NOISE2", "$i-$payload", null)
        }
        // 诊断：rotate 必须把文件压回上限内（否则说明 rotate 未生效）
        val size = LogStore.fileSizeBytes()
        assertTrue(
            "阶段3后文件必须 <= MAX_BYTES（实际 $size）——超过说明 rotate 未生效",
            size <= LogStore.MAX_BYTES,
        )
        val entries = LogStore.snapshot(limit = LogStore.KEEP_LINES).entries
        val errors = entries.filter { it.level == LogStore.LEVEL_ERROR }
        assertEquals(
            "E 级崩溃现场不得被 W 级噪音挤掉（实际保留 ${errors.size} 条）",
            50,
            errors.size,
        )
        assertTrue(
            "最新 E 级必须在窗口内",
            errors.any { it.message == "crash-49" },
        )
    }

    /**
     * §10.401：分级配额语义——E 独立、W/X 合占、D/I 独立。
     *
     * ⚠️ 不能靠「写 4000 条 W 后立刻断言 W ≤ 配额」来测：rotate 只在文件超过
     * [LogStore.MAX_BYTES] 时才触发，两次 rotate 之间新写入的 W 会累积，
     * 断言「任意时刻 W ≤ 200」必然失败。这里改为**确定性构造一个已超限的文件**，
     * 再追加一条触发 rotate，然后验证各桶配额。
     */
    @Test
    fun `分级配额 W与X合占配额 D与I不占配额`() {
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        // 构造远超 256KB 的混合级别文件：3000 W + 300 X + 300 D + 300 E
        repeat(3000) { i ->
            sb.append(LogStore.encode(LogStore.LEVEL_WARN, "W", "w-$i-${"x".repeat(100)}", null)).append('\n')
        }
        repeat(300) { i ->
            sb.append(LogStore.encode(LogStore.LEVEL_DIAG, "X", "x-$i", null)).append('\n')
        }
        repeat(300) { i ->
            sb.append(LogStore.encode(LogStore.LEVEL_DEBUG, "D", "d-$i", null)).append('\n')
        }
        repeat(300) { i ->
            sb.append(LogStore.encode(LogStore.LEVEL_ERROR, "E", "e-$i", null)).append('\n')
        }
        file.writeText(sb.toString(), Charsets.UTF_8)
        assertTrue("构造的文件必须超过 MAX_BYTES 才能触发 rotate", file.length() > LogStore.MAX_BYTES)

        // 追加一条 W 触发 rotate（writeLine 写完后检查大小并 rotate）
        LogStore.appendBlocking(LogStore.LEVEL_WARN, "W", "trigger-${"x".repeat(100)}", null)

        val entries = LogStore.snapshot(limit = LogStore.KEEP_LINES).entries
        val errors = entries.filter { it.level == LogStore.LEVEL_ERROR }
        val warns = entries.filter { it.level == LogStore.LEVEL_WARN }
        val diags = entries.filter { it.level == LogStore.LEVEL_DIAG }
        val debugs = entries.filter { it.level == LogStore.LEVEL_DEBUG }

        assertTrue(
            "E 级独立配额：保留应 ≤ ERROR_KEEP_LINES（实际 ${errors.size}）",
            errors.size <= LogStore.ERROR_KEEP_LINES,
        )
        assertTrue(
            "W/X 合占配额：保留应 ≤ WARN_KEEP_LINES（实际 ${warns.size + diags.size}）",
            (warns.size + diags.size) <= LogStore.WARN_KEEP_LINES,
        )
        assertTrue(
            "D/I 独立配额：保留应 ≤ DIAG_KEEP_LINES（实际 ${debugs.size}）",
            debugs.size <= LogStore.DIAG_KEEP_LINES,
        )
        // 三桶都有保留 ⇒ 配额不是把某一类全丢弃
        assertTrue("E 级必须被保留（不能被 W 挤掉）", errors.isNotEmpty())
        assertTrue("W/X 级必须被保留", warns.isNotEmpty() || diags.isNotEmpty())
        assertTrue("D 级必须被保留", debugs.isNotEmpty())
    }

    /** §10.401 负向自证：配额常量必须合理。 */
    @Test
    fun `分级配额常量必须合理`() {
        assertTrue("E 级配额必须为正", LogStore.ERROR_KEEP_LINES > 0)
        assertTrue("W/X 配额必须为正", LogStore.WARN_KEEP_LINES > 0)
        assertTrue("D/I 配额必须为正", LogStore.DIAG_KEEP_LINES > 0)
        assertEquals(
            "总配额 = 三级之和",
            LogStore.ERROR_KEEP_LINES + LogStore.WARN_KEEP_LINES + LogStore.DIAG_KEEP_LINES,
            LogStore.KEEP_LINES,
        )
    }

    // ───────────────────── §10.401 限流去重 ─────────────────────

    /**
     * §10.401：同 tag + 同 message 的连续 W 级重复，首条完整写入，
     * 后续只写周期性摘要（每 5 次一条），其余跳过。
     */
    @Test
    fun `限流去重 连续重复只写首条与周期性摘要`() {
        val msg = "search: all endpoints failed or empty"
        // 1 条首条 + 4 条重复 = 5 条调用 → 首条 + 第 5 次摘要 = 2 条落盘
        for (i in 1..5) {
            LogStore.appendThrottled(LogStore.LEVEL_WARN, "MetingDiag", msg)
        }
        val entries = LogStore.snapshot().entries
        assertEquals("5 条调用应只落盘 2 条（首条 + 第 5 次摘要）", 2, entries.size)
        assertEquals("首条完整写入", msg, entries[1].message)
        assertTrue(
            "第 5 次写紧凑摘要",
            entries[0].message.contains("（重复 5 次）"),
        )
    }

    /** §10.401：E 级日志**绝不**被限流 —— 每条都必须落盘。 */
    @Test
    fun `限流去重 E级日志不得被限流`() {
        for (i in 1..10) {
            LogStore.appendThrottled(LogStore.LEVEL_ERROR, "CRASH", "crash-$i")
        }
        val entries = LogStore.snapshot().entries
        assertEquals("E 级每条都必须落盘，不得被限流", 10, entries.size)
        for (i in 1..10) {
            assertTrue("第 $i 条 E 级必须存在", entries.any { it.message == "crash-$i" })
        }
    }

    /**
     * §10.401：消息内容变化视为新类 —— 每条新消息都写全量，
     * 旧消息的未写出计数在变化时冲刷。
     */
    @Test
    fun `限流去重 消息内容变化时视为新类重新计数`() {
        // 写入 3 条相同 W（首条 + 2 重复，不触发阈值 5）
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "MetingDiag", "msg-A")
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "MetingDiag", "msg-A")
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "MetingDiag", "msg-A")
        // 消息变化：应冲刷旧计数摘要 + 写新消息首条
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "MetingDiag", "msg-B")
        val entries = LogStore.snapshot().entries
        // 应有：msg-A 首条 + msg-A（重复 3 次）摘要 + msg-B 首条 = 3 条
        assertEquals("3 条相同 + 1 条新消息 → 首条 + 摘要 + 新首条", 3, entries.size)
        assertTrue("msg-A 首条必须存在", entries.any { it.message == "msg-A" })
        assertTrue(
            "msg-A 未触发阈值(5)的计数应在变化时冲刷",
            entries.any { it.message.contains("msg-A") && it.message.contains("重复 3 次") },
        )
        assertTrue("msg-B 首条必须存在", entries.any { it.message == "msg-B" })
    }

    /** §10.401 负向自证：不同 tag 的相同消息互不干扰（各自计数）。 */
    @Test
    fun `限流去重 不同tag的相同消息互不干扰`() {
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "TagA", "same-msg")
        LogStore.appendThrottled(LogStore.LEVEL_WARN, "TagB", "same-msg")
        val entries = LogStore.snapshot().entries
        assertEquals("不同 tag 各自写首条", 2, entries.size)
        assertTrue(entries.any { it.tag == "TagA" && it.message == "same-msg" })
        assertTrue(entries.any { it.tag == "TagB" && it.message == "same-msg" })
    }
}
