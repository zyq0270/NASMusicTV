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
        assertNull("级别非法", LogStore.decode("123\tX\tTAG\tmsg"))
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
}
