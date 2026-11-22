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
 * 锁住源码结构。锁的是 5 项修复的**回归点**：
 *  - 冷启动恢复只走 VM 的 loadPitchSpeedFromPrefs（同步 VM 状态 + 播放器）
 *  - exitKaraoke 不再 resetPitch/resetSpeed（否则抹掉「全局记忆」的偏好）
 *  - 歌词插值锚点用 val（杜绝在循环里重设锚点的死代码回归）
 *  - 原唱/伴唱按钮显示当前态 + 转换中置灰 + onClick guard
 *
 * ⚠️ 断言一律**语句级**：源码注释里会解释旧实现（含被禁字面量），全文 `!contains` 会自伤。
 */
class KaraokeFlowFixGateTest {

    @Test
    fun `冷启动升降调变速恢复只走 VM 入口`() {
        val source = readSource("com/nasmusic/tv/ui/viewmodel/MainViewModel.kt")
        // 语句级：恢复调用必须是 vocalVM.loadPitchSpeedFromPrefs()
        assertTrue(
            "MainViewModel.init 必须调用 vocalVM.loadPitchSpeedFromPrefs() 以同步 VM 状态 + 播放器",
            "vocalVM.loadPitchSpeedFromPrefs()" in source
        )
    }

    @Test
    fun `退出 K 歌页不得重置升降调与变速`() {
        val source = readSource("com/nasmusic/tv/ui/viewmodel/VocalSeparationViewModel.kt")
        val body = sliceBetween(source, "fun exitKaraoke()", "AppLog.d(\"VocalSeparationViewModel\", \"exitKaraoke:")
        // 语句级：函数体内不含 resetPitch() / resetSpeed() 调用（注释里提到的旧名字不算）
        assertFalse(
            "exitKaraoke 不得调用 resetPitch()（会把 0 落盘，抹掉用户的升降调偏好）",
            "resetPitch()" in body
        )
        assertFalse(
            "exitKaraoke 不得调用 resetSpeed()（会把 1.0 落盘，抹掉用户的变速偏好）",
            "resetSpeed()" in body
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
