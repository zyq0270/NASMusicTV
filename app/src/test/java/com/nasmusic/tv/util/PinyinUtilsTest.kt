package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * E-2: PinyinUtils 单元测试
 *
 * 覆盖三种匹配能力：子串、拼音全拼、拼音首字母，以及多词查询 [PinyinUtils.matchesQuery]。
 *
 * ⚠️ 拼音转换依赖 TinyPinyin 的字典。纯 JVM（Robolectric 之外）环境若能正常加载字典，
 * 字典缺失时 `toPinyin` 会退化成原字符 —— 这类环境用 `assumeTrue` 跳过拼音断言，
 * 避免「环境问题」被误判成「匹配逻辑坏了」。中文/ASCII 子串断言不受影响，任何环境都跑。
 */
class PinyinUtilsTest {

    /** 拼音字典是否真的可用（不可用时跳过依赖它的断言） */
    private fun pinyinAvailable(): Boolean {
        val py = PinyinUtils.toPinyin("周杰伦")
        return py.isNotEmpty() && py != "周杰伦" && py.all { it in 'a'..'z' }
    }

    @Test
    fun `matches with empty query returns true`() {
        assertTrue(PinyinUtils.matches("周杰伦", ""))
        assertTrue(PinyinUtils.matches("Taylor", ""))
    }

    @Test
    fun `matches with direct substring matching`() {
        assertTrue(PinyinUtils.matches("周杰伦", "周杰伦"))
        assertTrue(PinyinUtils.matches("Taylor Swift", "taylor"))
        assertTrue(PinyinUtils.matches("Taylor Swift", "SWIFT"))
    }

    @Test
    fun `matches returns false for non-matching query`() {
        assertFalse(PinyinUtils.matches("周杰伦", "林俊杰"))
        assertFalse(PinyinUtils.matches("Taylor Swift", "Adele"))
    }

    @Test
    fun `matches with case insensitive matching`() {
        assertTrue(PinyinUtils.matches("Adele", "adele"))
        assertTrue(PinyinUtils.matches("adele", "ADELE"))
    }

    @Test
    fun `matches handles blank text`() {
        assertFalse(PinyinUtils.matches("", "a"))
    }

    @Test
    fun `matches supports pinyin initials`() {
        assumeTrue("TinyPinyin 字典在当前环境不可用，跳过拼音断言", pinyinAvailable())
        assertEquals("zjl", PinyinUtils.toPinyinInitials("周杰伦"))
        assertTrue(PinyinUtils.matches("周杰伦", "zjl"))
        // 首字母可以出现在中间，不要求整串相等
        assertTrue(PinyinUtils.matches("周杰伦", "jl"))
        assertFalse(PinyinUtils.matches("周杰伦", "ljj"))
    }

    @Test
    fun `matches supports full pinyin`() {
        assumeTrue("TinyPinyin 字典在当前环境不可用，跳过拼音断言", pinyinAvailable())
        assertEquals("zhoujielun", PinyinUtils.toPinyin("周杰伦"))
        // P1 回归：`matches` 曾只做子串 + 首字母 ⇒ 全拼在采集阶段就被裁掉
        assertTrue(PinyinUtils.matches("周杰伦", "zhoujielun"))
        assertTrue(PinyinUtils.matches("周杰伦", "zhoujie"))
        assertFalse(PinyinUtils.matches("周杰伦", "zhoujielong"))
    }

    @Test
    fun `matches ignores pinyin path for chinese query`() {
        assumeTrue("TinyPinyin 字典在当前环境不可用，跳过拼音断言", pinyinAvailable())
        // 中文查询不该走拼音分支（拼音串里没有汉字），只需保证不误判成命中
        assertFalse(PinyinUtils.matches("周杰伦", "晴天"))
    }

    @Test
    fun `matchesQuery requires every token to hit the same text`() {
        // 纯 ASCII，不依赖拼音字典
        assertTrue(PinyinUtils.matchesQuery("Hello World", "hello world"))
        assertTrue(PinyinUtils.matchesQuery("Hello World", "  HELLO   world "))
        assertTrue(PinyinUtils.matchesQuery("Hello World", "world"))
        assertFalse(PinyinUtils.matchesQuery("Hello", "hello world"))
        // 空查询与 matches 的宽口径一致 = 全部命中
        assertTrue(PinyinUtils.matchesQuery("Hello", "   "))
    }

    @Test
    fun `matchesQuery supports multi word pinyin`() {
        assumeTrue("TinyPinyin 字典在当前环境不可用，跳过拼音断言", pinyinAvailable())
        // "zjl 周杰" 是 pinyin-search-plan 里点名的场景（原 `matchesMultipleWords` 死代码）
        assertTrue(PinyinUtils.matchesQuery("周杰伦", "zjl 周杰"))
        assertTrue(PinyinUtils.matchesQuery("周杰伦", "zjl zhoujie"))
        assertFalse(PinyinUtils.matchesQuery("周杰伦", "zjl 林俊杰"))
    }

    @Test
    fun `group letter uses pinyin initial`() {
        assumeTrue("TinyPinyin 字典在当前环境不可用，跳过拼音断言", pinyinAvailable())
        assertEquals('Z', PinyinUtils.getGroupLetter("周杰伦"))
        assertEquals('T', PinyinUtils.getGroupLetter("Taylor Swift"))
        assertEquals('#', PinyinUtils.getGroupLetter("123"))
        assertEquals('#', PinyinUtils.getGroupLetter(""))
    }
}
