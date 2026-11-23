package com.nasmusic.tv.util

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G3-01 回归门禁：有上限读取必须**循环读满**。
 *
 * ⚠️ 这里刻意用 >8 KiB 的正文：okio 的 `BufferedSource.read` 每次至多搬一个 segment，
 * 只读一次的实现在本用例下会得到截断前缀（并让超限判定恒假）。
 */
class HttpBodyLimitsTest {

    @Test
    fun readsBodyLargerThanOneSegment() {
        val payload = "a".repeat(20 * 1024)
        val source = Buffer().writeUtf8(payload)
        assertEquals(payload, readBodyLimited(source, 64L * 1024))
    }

    @Test
    fun readsExactlyAtLimit() {
        val payload = "b".repeat(8192)
        assertEquals(payload, readBodyLimited(Buffer().writeUtf8(payload), 8192L))
    }

    @Test
    fun rejectsBodyOverLimit() {
        val source = Buffer().writeUtf8("c".repeat(9000))
        assertNull(readBodyLimited(source, 8192L))
    }

    @Test
    fun handlesEmptyBody() {
        assertEquals("", readBodyLimited(Buffer(), 1024L))
    }

    @Test
    fun rejectsNonPositiveLimit() {
        assertNull(readBodyLimited(Buffer().writeUtf8("x"), 0L))
    }
}
