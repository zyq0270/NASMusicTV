package com.nasmusic.tv.data.prefs

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.network.NetworkSongId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「设置页新增的默认网络源卡」写的是哪条事实源（方案 §6-10 / Task 7；v2.39.8 改为字符串 key）。
 *
 * ⚠️ 断言只走 `appSettings` Flow（读 `keyDefaultNetworkSource`），**绝不**断言
 * `getDefaultNetworkSourceSync()`（`@Volatile` 内存镜像）——「写完立刻读到新值」是时序量，
 * `ProviderMirrorTest` 的 4 条镜像计时用例已因此于 5997380 整体删除，镜像的**接线与形状**
 * 由 `ProviderMirrorWiringGateTest` G2 自动枚举 `cached*` 字段钉住。
 *
 * v2.39.8：`NetworkSource` 枚举已删除（它只含 `meting`/`alapi`/`jiosaavn`），默认源改为
 * `NetworkMusicManager.services` 的 key（`meting` / `baidu` / `jamendo`，方案 §7.5）。
 * 因此本测试同时钉住**旧数据兼容**：老 DataStore / 老备份里存的枚举名 `"METING"`
 * 必须经 `normalizeNetworkSourceKey()` 折回 key。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNetworkSourcePrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `setDefaultNetworkSource 必须落到 appSettings 的 defaultNetworkSource`() = runBlocking {
        prefs.setDefaultNetworkSource(AppPreferences.SOURCE_BAIDU)
        assertEquals(AppPreferences.SOURCE_BAIDU, prefs.appSettings.first().defaultNetworkSource)
    }

    @Test
    fun `未设置时必须回落 meting`() = runBlocking {
        assertEquals(NetworkSongId.METING_SOURCE, prefs.appSettings.first().defaultNetworkSource)
    }

    /** 负向自证：写入必须真的改变事实源（防止 setter 空转）。 */
    @Test
    fun `改到另一个源后事实源必须跟着变`() = runBlocking {
        prefs.setDefaultNetworkSource(AppPreferences.SOURCE_BAIDU)
        prefs.setDefaultNetworkSource(AppPreferences.SOURCE_JAMENDO)
        assertEquals(AppPreferences.SOURCE_JAMENDO, prefs.appSettings.first().defaultNetworkSource)
    }

    /** 旧备份 JSON 的 `defaultNetworkSource` 存的是枚举**名**（`"METING"`）⇒ 必须折回 key。 */
    @Test
    fun `旧枚举名必须折回 key`() = runBlocking {
        prefs.setDefaultNetworkSource("METING")
        assertEquals(NetworkSongId.METING_SOURCE, prefs.appSettings.first().defaultNetworkSource)
    }

    /** 已删除源的旧 key 原样保留（运行时 `defaultSource` getter 负责回退，不在这里改写）。 */
    @Test
    fun `已删除源的旧 key 原样保留`() = runBlocking {
        prefs.setDefaultNetworkSource("alapi")
        assertEquals("alapi", prefs.appSettings.first().defaultNetworkSource)
    }

    // ─────────── T7 Step 3b：UI 接线的源码文本门（防止"卡片加了但读的是另一个 key"） ───────────

    private fun settingsSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件：$relativePath", found != null)
        return found!!.readText()
    }

    @Test
    fun `默认源卡必须读写同一个字段`() {
        val section = settingsSource("ui/screens/settings/SourcesSettingsSection.kt")
        assertTrue(
            "SourcesSettingsSection 必须同时出现 state.defaultNetworkSource（读）与 " +
                "actions.onChangeDefaultNetworkSource（写）",
            section.contains("state.defaultNetworkSource") &&
                section.contains("actions.onChangeDefaultNetworkSource")
        )
    }

    @Test
    fun `默认源候选必须来自已启用的源而不是枚举`() {
        val section = settingsSource("ui/screens/settings/SourcesSettingsSection.kt")
        // v2.39.8：候选 = 已启用 **且** 提供目录能力（providesCatalog）。
        // 后者是本轮新增的判据：百度网盘 / Jamendo 的 getPlaylist / findArtist 返回空，
        // 列进候选就是「选了必然空白」。
        assertTrue(
            "卡⑥ 候选必须过滤「已启用 且 提供目录能力」（方案 §7.6 第 1 条）",
            section.contains("state.musicSources.filter { it.enabled && it.providesCatalog }")
        )
        assertTrue(
            "NetworkSource 枚举已删除，源码里不得再出现 NetworkSource.entries",
            !section.contains("NetworkSource.entries")
        )
    }

    @Test
    fun `SettingsBranch 必须把默认源接到 setter`() {
        val branch = settingsSource("ui/components/branches/SettingsBranch.kt")
        assertTrue(
            "SettingsBranch 必须出现 DefaultNetworkSource 的 setter 调用" +
                "（updateDefaultNetworkSource 或其门面形式）",
            branch.contains("updateDefaultNetworkSource")
        )
    }

    /** 负向自证：把写回调从样例文本里去掉，读写判据必须判负。 */
    @Test
    fun `默认源卡判据不是空转`() {
        val sample = "state.defaultNetworkSource = x, actions.onChangeDefaultNetworkSource = { }"
        assertTrue(
            "样例本身应通过读写判据",
            sample.contains("state.defaultNetworkSource") &&
                sample.contains("actions.onChangeDefaultNetworkSource")
        )
        val noWrite = sample.replace("actions.onChangeDefaultNetworkSource = { }", "")
        assertTrue(
            "负向用例失效：去掉写回调后判据仍通过",
            !(noWrite.contains("actions.onChangeDefaultNetworkSource"))
        )
        val noRead = sample.replace("state.defaultNetworkSource = x, ", "")
        assertTrue(
            "负向用例失效：去掉读侧后判据仍通过",
            !(noRead.contains("state.defaultNetworkSource"))
        )
    }
}
