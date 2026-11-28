package com.nasmusic.tv.ui.screens.settings

import com.nasmusic.tv.data.cache.CacheLimitKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheLimitUiTest {
    @Test
    fun `三项数量输入只接受范围内整数`() {
        CacheLimitKind.entries.forEach { kind ->
            val range = com.nasmusic.tv.data.cache.CacheLimits.RANGES.getValue(kind)
            assertEquals(range.first, parseCacheLimitInput(kind, " ${range.first} ").getOrThrow())
            assertEquals(range.last, parseCacheLimitInput(kind, range.last.toString()).getOrThrow())
            assertTrue(parseCacheLimitInput(kind, "").isFailure)
            assertTrue(parseCacheLimitInput(kind, "12.5").isFailure)
            assertTrue(parseCacheLimitInput(kind, (range.first - 1).toString()).isFailure)
            assertTrue(parseCacheLimitInput(kind, (range.last + 1).toString()).isFailure)
        }
    }

    @Test
    fun `设置页三项输入与离线开关已接到 dialog 且关闭二维码`() {
        val source = read("ui/screens/settings/CacheSettingsSection.kt")
        val inputSource = read("ui/screens/settings/CacheLimitInputRow.kt")
        CacheLimitKind.entries.forEach { assertTrue(source.contains(it.name)) }
        assertTrue(inputSource.contains("KeyboardType.Number"))
        assertTrue(inputSource.contains("showQrCode = false"))
        assertTrue(source.contains("settings_search_offline_only"))
        assertTrue(source.contains("onSetOfflinePolicy"))
        assertTrue(inputSource.contains("parseCacheLimitInput(kind, raw)"))
    }

    @Test
    fun `TextInputDialog 新参数默认兼容旧调用`() {
        val source = read("ui/screens/TextInputDialog.kt")
        assertTrue(source.contains("showQrCode: Boolean = true"))
        assertTrue(source.contains("keyboardType: KeyboardType = KeyboardType.Text"))
        assertTrue(source.contains("errorText: String? = null"))
        assertTrue(source.contains("color = NasMusicColors.Warning"))
    }

    private fun read(path: String): String {
        val roots = listOf(File("src/main/java/com/nasmusic/tv"), File("app/src/main/java/com/nasmusic/tv"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("source root not found")
        return File(root, path).readText()
    }
}
