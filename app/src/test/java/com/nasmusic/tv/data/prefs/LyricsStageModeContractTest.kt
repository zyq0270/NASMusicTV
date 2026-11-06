package com.nasmusic.tv.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E53「歌词舞台」—— 子模式序号取值契约的门禁测试。
 */
class LyricsStageModeContractTest {

    @Test
    fun `the contract covers the five sub modes`() {
        assertEquals(5, LyricsStageModeContract.COUNT)
        assertEquals(5, LyricsStageModeContract.RANGE.count())
    }

    @Test
    fun `the default is the dot matrix sub mode`() {
        assertEquals(0, LyricsStageModeContract.DEFAULT)
        assertTrue(LyricsStageModeContract.DEFAULT in LyricsStageModeContract.RANGE)
    }

    @Test
    fun `legal values pass through unchanged`() {
        for (v in LyricsStageModeContract.RANGE) {
            assertEquals(v, LyricsStageModeContract.normalize(v))
        }
    }

    @Test
    fun `illegal values fall back to the default`() {
        assertEquals(LyricsStageModeContract.DEFAULT, LyricsStageModeContract.normalize(-1))
        assertEquals(LyricsStageModeContract.DEFAULT, LyricsStageModeContract.normalize(5))
        assertEquals(LyricsStageModeContract.DEFAULT, LyricsStageModeContract.normalize(999))
        assertEquals(LyricsStageModeContract.DEFAULT, LyricsStageModeContract.normalize(Int.MIN_VALUE))
        assertEquals(LyricsStageModeContract.DEFAULT, LyricsStageModeContract.normalize(Int.MAX_VALUE))
    }
}
