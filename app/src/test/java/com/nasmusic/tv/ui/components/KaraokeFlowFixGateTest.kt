package com.nasmusic.tv.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K 歌页流程修复门禁（源码扫描，v2.39.26 版本内修复）。
 *
 * Robolectric 不执行 Compose 的 LaunchedEffect 协程体，也不渲染按钮文案；
 * 沿用本工程 K 歌门禁既有做法（KaraokeFullscreenGateTest / KaraokePickerTvFocusScanTest）
 * 锁住源码结构。当前锁的是 5 项修复的**回归点**：
 *  - 升降调/变速是会话内效果：冷启动强制原调原速，不读历史值
 *  - exitKaraoke 必须复位升降调与变速（退出即恢复原调原速）
 *  - commit / reset 都不写持久化（不记忆）
 *  - 歌词插值锚点用 val（杜绝在循环里重设锚点的死代码回归）
 *  - 原唱/伴唱按钮显示当前态 + 转换中置灰 + onClick guard
 *
 * ⚠️ 断言一律**语句级**：源码注释里会解释旧实现，全文 `!contains` 会自伤。
 */
class KaraokeFlowFixGateTest {

    @Test
    fun `冷启动强制原调原速且不读历史值`() {
        val source = readSource("com/nasmusic/tv/ui/viewmodel/MainViewModel.kt")
        assertFalse(
            "冷启动不得再调用历史恢复入口（升降调/变速不记忆）",
            "vocalVM.loadPitchSpeedFromPrefs()" in source
        )
        assertTrue(
            "冷启动必须把播放器升降调复位为 0",
            "playerManager.setPitch(0)" in source
        )
        assertTrue(
            "冷启动必须把播放器速度复位为 1.0",
            "playerManager.setSpeed(1.0f)" in source
        )
    }

    @Test
    fun `退出 K 歌页必须复位升降调与变速`() {
        val source = readSource("com/nasmusic/tv/ui/viewmodel/VocalSeparationViewModel.kt")
        val body = sliceBetween(source, "fun exitKaraoke()", "AppLog.d(\"VocalSeparationViewModel\", \"exitKaraoke:")
        assertTrue(
            "exitKaraoke 必须调用 resetPitch() 恢复原调",
            "resetPitch()" in body
        )
        assertTrue(
            "exitKaraoke 必须调用 resetSpeed() 恢复原速",
            "resetSpeed()" in body
        )
    }

    @Test
    fun `升降调与变速的提交和重置都不写持久化`() {
        val source = readSource("com/nasmusic/tv/ui/viewmodel/VocalSeparationViewModel.kt")
        assertFalse(
            "升降调不得写 DataStore（会话内临时效果）",
            "prefs.player.setPitchSemitones(" in source
        )
        assertFalse(
            "变速不得写 DataStore（会话内临时效果）",
            "prefs.player.setPlaybackSpeed(" in source
        )
        assertFalse(
            "VM 不得再提供历史恢复入口",
            "fun loadPitchSpeedFromPrefs()" in source
        )
    }

    @Test
    fun `歌词插值锚点用 val 杜绝死代码回归`() {
        val source = readSource("com/nasmusic/tv/ui/components/KaraokeLyricsView.kt")
        assertTrue(
            "锚点必须声明为 val（编译期禁止在循环里重设）",
            "val anchorProgress = progressMs" in source
        )
        assertFalse(
            "不得再用 var anchorProgress（死代码回归路径）",
            "var anchorProgress" in source
        )
    }

    @Test
    fun `原唱伴唱按钮显示当前态且转换中禁点`() {
        val source = readSource("com/nasmusic/tv/ui/components/KaraokePlaybackScreen.kt")
        // 当前态：vocalRemovalEnabled=true（伴唱中）→ 显示「伴唱」
        assertTrue(
            "按钮 label 必须显示当前态：伴唱中显示 accompaniment",
            "if (vocalRemovalEnabled) stringResource(R.string.karaoke_accompaniment)" in source
        )
        // 转换中置灰 + onClick guard
        assertTrue(
            "转换中 VocalToggleButton 必须置灰",
            "dimmed = isSeparating" in source
        )
        assertTrue(
            "转换中 onClick 必须拦截 toggling",
            "if (!isSeparating) onToggleVocalRemoval()" in source
        )
    }

    private fun sliceBetween(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("在源码中找不到起点 `$from`", start >= 0)
        val end = source.indexOf(to, start)
        assertTrue("在源码中找不到 `$from` 之后的终点 `$to`", end >= 0)
        return source.substring(start, end)
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
            File("../app/src/main/java/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）",
            found,
        )
        return found!!.readText()
    }
}
