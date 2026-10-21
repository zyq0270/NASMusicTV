package com.nasmusic.tv.ui.theme

import com.nasmusic.tv.data.model.ThemeMode
import com.nasmusic.tv.data.model.resolveDarkTheme
import com.nasmusic.tv.data.model.resolveThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三态主题模式的纯函数门禁（浅色主题方案 §4 / §7）。
 *
 * 覆盖两条解析链的**全矩阵**：
 * - [resolveThemeMode]：存储值 + 旧布尔 → 有效模式（DataStore 迁移与备份导入共用）
 * - [resolveDarkTheme]：模式 × 设备 × 系统明暗 → 是否深色（TV 恒深色是所有者裁决 D5）
 *
 * 纯 JVM，无 Robolectric。
 */
class ThemeModeTest {

    // ─────────────────── resolveThemeMode ───────────────────

    @Test
    fun `新键存在时以新键为准`() {
        ThemeMode.entries.forEach { stored ->
            assertEquals(stored, resolveThemeMode(stored, legacyDark = true))
            assertEquals(stored, resolveThemeMode(stored, legacyDark = false))
            assertEquals(stored, resolveThemeMode(stored, legacyDark = null))
        }
    }

    @Test
    fun `新键缺失时旧布尔三态迁移`() {
        assertEquals(ThemeMode.DARK, resolveThemeMode(null, legacyDark = true))
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(null, legacyDark = false))
        assertEquals(ThemeMode.SYSTEM, resolveThemeMode(null, legacyDark = null))
    }

    /**
     * 回归护栏：调用方**不能**先 `ThemeMode.fromKey(stored)` 再传进来。
     *
     * `fromKey(null)` 会回落 SYSTEM，把「旧键 false（用户显式选过浅色）」误判成跟随系统。
     * 本用例把这两条路的差异钉死，`resolveThemeMode` 的 KDoc 依赖它。
     */
    @Test
    fun `先 fromKey 再传会丢掉旧浅色选择（禁止的调用方式）`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(null, legacyDark = false))
        assertNotEquals(ThemeMode.fromKey(null), resolveThemeMode(null, legacyDark = false))
    }

    // ─────────────────── ThemeMode.fromKey / 默认值 ───────────────────

    @Test
    fun `fromKey 映射三个存储值并忽略大小写`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("system"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("DARK"))
    }

    @Test
    fun `fromKey 对 null 与未知值回落 Default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("sepia"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.Default)
    }

    /**
     * `backupGson` 的容错枚举适配器在遇到未知名字时回落到**首个常量**。
     * ThemeMode 的首个常量必须是 SYSTEM（新装默认值），否则老备份/脏数据会静默变成浅色或深色。
     */
    @Test
    fun `SYSTEM 必须是枚举首个常量`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.entries.first())
        assertEquals("system", ThemeMode.SYSTEM.key)
        assertEquals("dark", ThemeMode.DARK.key)
        assertEquals("light", ThemeMode.LIGHT.key)
    }

    // ─────────────────── resolveDarkTheme：3 模式 × 2 设备 × 2 系统 ───────────────────

    @Test
    fun `显式深色在任何设备任何系统下都是深色`() {
        for (isTv in listOf(true, false)) {
            for (systemDark in listOf(true, false)) {
                assertTrue(
                    "DARK / isTv=$isTv / systemDark=$systemDark",
                    resolveDarkTheme(ThemeMode.DARK, systemDark, isTv)
                )
            }
        }
    }

    @Test
    fun `显式浅色在任何设备任何系统下都是浅色`() {
        for (isTv in listOf(true, false)) {
            for (systemDark in listOf(true, false)) {
                assertFalse(
                    "LIGHT / isTv=$isTv / systemDark=$systemDark",
                    resolveDarkTheme(ThemeMode.LIGHT, systemDark, isTv)
                )
            }
        }
    }

    @Test
    fun `SYSTEM 在手机上跟随系统`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true, isTv = false))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false, isTv = false))
    }

    @Test
    fun `SYSTEM 在电视上恒为深色（D5）`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true, isTv = true))
        assertTrue(
            "电视 night-mode 上报不可靠，历史行为恒深色；此处若出现 false 说明 D5 裁决被改动",
            resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false, isTv = true)
        )
    }
}
