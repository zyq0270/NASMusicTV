package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索关键词形态判定 / 分词口径（`SearchQuery`）单元测试。
 *
 * 这两个函数是「本地拼音过滤 vs 服务端搜索」的路由判据，判错会静默丢结果
 * （拼音输入只查本地 ⇒ 没缓存就 0 条；英文单词被当成纯拼音 ⇒ 关掉整个源），
 * 所以边界要钉住。
 */
class SearchQueryTest {

    @Test
    fun `isPinyinLike accepts initials full pinyin and english words`() {
        assertTrue(SearchQuery.isPinyinLike("zjl"))
        assertTrue(SearchQuery.isPinyinLike("zhoujielun"))
        assertTrue(SearchQuery.isPinyinLike("zhou jielun"))
        assertTrue(SearchQuery.isPinyinLike("  Adele  "))
        assertTrue(SearchQuery.isPinyinLike("hello world"))
        assertTrue(SearchQuery.isPinyinLike("taylor2"))
    }

    @Test
    fun `isPinyinLike rejects chinese`() {
        assertFalse(SearchQuery.isPinyinLike("周杰伦"))
        assertFalse(SearchQuery.isPinyinLike("周杰伦 zjl"))
        assertFalse(SearchQuery.isPinyinLike("晴天 周杰伦"))
    }

    @Test
    fun `isPinyinLike rejects empty symbol only and digits only`() {
        assertFalse(SearchQuery.isPinyinLike(""))
        assertFalse(SearchQuery.isPinyinLike("   "))
        assertFalse(SearchQuery.isPinyinLike("!!!"))
        assertFalse(SearchQuery.isPinyinLike("123"))
        // 含标点的混合串不算：拼音串只由字母/数字/空白组成
        assertFalse(SearchQuery.isPinyinLike("a-b"))
    }

    @Test
    fun `tokens splits on ascii full width and nbsp spaces`() {
        assertEquals(listOf("zhou", "jielun"), SearchQuery.tokens("  Zhou\u3000Jielun  "))
        assertEquals(listOf("a", "b"), SearchQuery.tokens("A\u00A0B"))
        assertEquals(listOf("周杰伦", "晴天"), SearchQuery.tokens("周杰伦   晴天"))
    }

    @Test
    fun `tokens returns empty list for blank query`() {
        assertTrue(SearchQuery.tokens("").isEmpty())
        assertTrue(SearchQuery.tokens("   ").isEmpty())
    }
}
