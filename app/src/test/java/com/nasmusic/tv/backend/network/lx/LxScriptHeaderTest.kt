package com.nasmusic.tv.backend.network.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 脚本头注释解析（方案 §5.3「`@name` 缺失即非法音源」的判据来源）。
 *
 * 这段逻辑不碰 native，可以留在 JVM 单测里 —— 引擎相关的测试在
 * `androidTest/…/LxScriptHostTest.kt`。
 *
 * ⚠️ 必须挂 Robolectric：`LxScriptHeader.toJson()` / 断言里的 `org.json.JSONObject`
 * 在纯 JVM 单测下落到 android.jar 桩实现，`toString()` 返回 null 直接 NPE。
 */
@RunWith(RobolectricTestRunner::class)
class LxScriptHeaderTest {

    @Test
    fun parsesAllKnownFields() {
        val header = LxScriptHeader.parse(
            """
            /*!
             * @name         星海音乐源
             * @description  GDAPI | 聚合
             * @version      v3.2.15
             * @author       万去了了 / 科学大魔王
             * @homepage     https://zddyr.top/
             * @lastUpdate   2026-10-02
             */
            const a = 1
            """.trimIndent(),
        )
        assertEquals("星海音乐源", header.name)
        assertEquals("GDAPI | 聚合", header.description)
        assertEquals("v3.2.15", header.version)
        assertEquals("万去了了 / 科学大魔王", header.author)
        assertEquals("https://zddyr.top/", header.homepage)
    }

    @Test
    fun missingNameYieldsNull() {
        val header = LxScriptHeader.parse("/* @version 1 */\nlx.on('request', () => {})")
        assertNull(header.name)
        assertEquals("1", header.version)
    }

    @Test
    fun onlyFirstBlockCounts() {
        val header = LxScriptHeader.parse(
            """
            /*!
             * @name 第一个
             */
            /*
             * @name 第二个
             */
            """.trimIndent(),
        )
        assertEquals("第一个", header.name)
    }

    @Test
    fun doesNotEatBlockTerminator() {
        val header = LxScriptHeader.parse("/*\n * @name 无尾换行\n */\nlx.send('inited', {})")
        assertEquals("无尾换行", header.name)
    }

    @Test
    fun handlesCrlf() {
        val header = LxScriptHeader.parse("/*!\r\n * @name CRLF 源\r\n * @version v2\r\n */\r\n")
        assertEquals("CRLF 源", header.name)
        assertEquals("v2", header.version)
    }

    @Test
    fun ignoresUnknownAtFields() {
        val header = LxScriptHeader.parse("/*\n * @see http://x\n * @name 只认白名单\n */")
        assertEquals("只认白名单", header.name)
        // 键序不是契约（android 的 JSONObject 不保证保序），按字段断言
        val json = org.json.JSONObject(header.toJson())
        assertEquals(5, json.length())
        assertEquals("只认白名单", json.getString("name"))
        assertEquals("", json.getString("version"))
    }
}
