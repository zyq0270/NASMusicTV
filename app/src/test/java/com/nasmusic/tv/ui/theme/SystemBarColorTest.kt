package com.nasmusic.tv.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 系统栏底色门禁（浅色主题方案 §4.5）。
 *
 * 判据两条：底色必须取自调色板的 `surface`（⛔ 不接受硬编码黑/白），
 * 且浅色底只在「系统能把图标刷成深色」时启用，否则退回深色底。
 */
class SystemBarColorTest {

    @Test
    fun `深色主题取深色 surface`() {
        assertEquals(NasPalettes.Dark.surface.toArgb(), systemBarSurfaceColor(true, true).toArgb())
        assertEquals(NasPalettes.Dark.surface.toArgb(), systemBarSurfaceColor(true, false).toArgb())
    }

    @Test
    fun `浅色主题在图标可刷深时取浅色 surface`() {
        assertEquals(NasPalettes.Light.surface.toArgb(), systemBarSurfaceColor(false, true).toArgb())
    }

    @Test
    fun `浅色主题在图标不可刷深时退回深色底`() {
        assertEquals(NasPalettes.Dark.surface.toArgb(), systemBarSurfaceColor(false, false).toArgb())
    }

    @Test
    fun `两套 surface 必须不同色否则该规则失去意义`() {
        assertNotEquals(NasPalettes.Dark.surface.toArgb(), NasPalettes.Light.surface.toArgb())
    }
}
