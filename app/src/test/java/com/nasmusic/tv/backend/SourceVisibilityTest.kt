package com.nasmusic.tv.backend

import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType
import com.nasmusic.tv.data.prefs.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「音乐源开关全覆盖」方案 §7.2 的 `SourceVisibilityTest`（纯单元测试，不依赖 Robolectric）。
 *
 * 被测对象：`backend/SourceVisibility.kt`（只读、无存储的可见性判定）。
 *
 * ## 覆盖的 5 组（方案 §7.2 第一条）
 *
 * | 组 | 内容 | 测试方法 |
 * |---|---|---|
 * | 1 | 五源 × 开/关矩阵（NAS / LOCAL / NETWORK_MUSIC / BAIDU_PAN / JAMENDO） | `nasSourceRespectsSwitch` … `jamendoSourceRespectsSwitch` + `eachSourceSwitchIsIndependent` |
 * | 2 | Jamendo 双条件边界（开关开但 clientId 空 ⇒ 不可见） | `jamendoRequiresSwitchAndNonBlankClientId` |
 * | 3 | 百度「启用但未登录」边界（`baiduActive` 判据与 `registerService` 同源） | `baiduEnabledButNotLoggedInIsInvisible` |
 * | 4 | 非曲库来源恒可见（RADIO / WEATHER_RADIO / DOWNLOAD / IMPORTED） | `nonLibrarySourcesAreAlwaysVisible` + `downloadedSongStaysVisibleWhenLocalSwitchOff` |
 * | 5 | ⭐ `networkSource` 字符串 → `MusicSourceType` 映射对拍 | `networkSourceStringMapsToExpectedMusicSourceType` + `unknownNetworkSourceSilentlyFallsBackToNetworkMusic` + `songVisibilityAgreesWithSourceTypeVisibility` |
 *
 * ## ⭐ 为什么第 5 组是最大风险（方案 §3.1）
 *
 * `Song.sourceType`（`data/model/SourceIdentifier.kt`）对 `networkSource` **字符串不穷尽**：
 * 任何未识别的字符串都会落到 `isNetworkSong -> MusicSourceType.NETWORK_MUSIC` 分支。
 * ⇒ 未来的独立网络源会**莫名受 Meting 开关影响**，而 `when` 对枚举穷尽 ⇒ **不会编译报错**。
 * ⇒ 本测试把现存全部 `networkSource` 字符串逐个对拍，并在 `unknownNetworkSourceSilentlyFallsBackToNetworkMusic`
 * 里把这条「静默归入」的风险**显式记录成断言**（而不是靠注释提醒）。
 *
 * ## 自包含性
 *
 * ⛔ 不依赖任何真实 `AppPreferences` / DataStore / 网络 / `MainViewModel`：
 * 开关值全部由局部 [SwitchState] 里的可变字段 + lambda 提供，
 * `Song` 样本全部是本文件构造的纯数据类实例。
 */
class SourceVisibilityTest {

    // ─────────────────── 组 1：五源 × 开/关矩阵 ───────────────────

    @Test
    fun nasSourceRespectsSwitch() {
        val state = SwitchState()
        val visibility = state.visibility()
        assertTrue("NAS 开关开 ⇒ NAS 可见", visibility.isVisible(MusicSourceType.NAS))
        state.nas = false
        assertFalse("NAS 开关关 ⇒ NAS 不可见", visibility.isVisible(MusicSourceType.NAS))
    }

    @Test
    fun localSourceRespectsSwitch() {
        val state = SwitchState()
        val visibility = state.visibility()
        assertTrue("本地开关开 ⇒ LOCAL 可见", visibility.isVisible(MusicSourceType.LOCAL))
        state.local = false
        assertFalse("本地开关关 ⇒ LOCAL 不可见", visibility.isVisible(MusicSourceType.LOCAL))
    }

    @Test
    fun networkMusicSourceRespectsSwitch() {
        val state = SwitchState()
        val visibility = state.visibility()
        assertTrue("Meting 开关开 ⇒ NETWORK_MUSIC 可见", visibility.isVisible(MusicSourceType.NETWORK_MUSIC))
        state.meting = false
        assertFalse("Meting 开关关 ⇒ NETWORK_MUSIC 不可见", visibility.isVisible(MusicSourceType.NETWORK_MUSIC))
    }

    @Test
    fun baiduPanSourceRespectsSwitch() {
        val state = SwitchState()
        val visibility = state.visibility()
        assertTrue("百度已启用且已登录 ⇒ BAIDU_PAN 可见", visibility.isVisible(MusicSourceType.BAIDU_PAN))
        state.baidu = false
        assertFalse("百度未启用/未登录 ⇒ BAIDU_PAN 不可见", visibility.isVisible(MusicSourceType.BAIDU_PAN))
    }

    @Test
    fun jamendoSourceRespectsSwitch() {
        val state = SwitchState()
        val visibility = state.visibility()
        assertTrue("Jamendo 开关开且 clientId 非空 ⇒ JAMENDO 可见", visibility.isVisible(MusicSourceType.JAMENDO))
        state.jamendo = false
        assertFalse("Jamendo 开关关 ⇒ JAMENDO 不可见", visibility.isVisible(MusicSourceType.JAMENDO))
    }

    /** 五个开关互不串扰：关掉一个不能顺手关掉别的（否则是「一处判据写错、全盘塌方」的形状）。 */
    @Test
    fun eachSourceSwitchIsIndependent() {
        // 受音乐源开关管理的五个源
        val managed = listOf(
            MusicSourceType.NAS,
            MusicSourceType.LOCAL,
            MusicSourceType.NETWORK_MUSIC,
            MusicSourceType.BAIDU_PAN,
            MusicSourceType.JAMENDO,
        )

        // 每次只关一个（用**全新**状态 ⇒ 其它开关仍是开），断言其余四个仍然可见
        for (off in managed) {
            val state = SwitchState()
            when (off) {
                MusicSourceType.NAS -> state.nas = false
                MusicSourceType.LOCAL -> state.local = false
                MusicSourceType.NETWORK_MUSIC -> state.meting = false
                MusicSourceType.BAIDU_PAN -> state.baidu = false
                MusicSourceType.JAMENDO -> state.jamendo = false
                else -> throw IllegalStateException("不可达：$off 不在受管列表里")
            }
            val visibility = state.visibility()

            assertFalse("关 $off ⇒ $off 不可见", visibility.isVisible(off))
            for (other in managed) {
                if (other == off) continue
                assertTrue("关 $off 不应影响 $other（开关判据串味）", visibility.isVisible(other))
            }
        }
    }

    // ─────────────────── 组 2：Jamendo 双条件边界 ───────────────────

    /**
     * Jamendo 的生效条件 = 开关 **且** clientId 非空（与 `NasMusicApp` 的 `registerService` 判据完全一致）。
     * ⛔ 「开关开了但没配 clientId」是最容易被漏掉的一档：此时 Jamendo 根本没注册，
     * 若可见性判据只看开关 ⇒ 界面会列出 Jamendo 页却点不出内容。
     */
    @Test
    fun jamendoRequiresSwitchAndNonBlankClientId() {
        val state = SwitchState(jamendo = true, jamendoClientId = "")
        val visibility = state.visibility()

        assertFalse("开关开但 clientId 为空串 ⇒ JAMENDO 不可见", visibility.isVisible(MusicSourceType.JAMENDO))

        state.jamendoClientId = "   "
        assertFalse("clientId 只有空白字符（isNotBlank 语义）⇒ JAMENDO 不可见", visibility.isVisible(MusicSourceType.JAMENDO))

        state.jamendoClientId = "client-id-123"
        assertTrue("开关开且 clientId 非空 ⇒ JAMENDO 可见", visibility.isVisible(MusicSourceType.JAMENDO))

        state.jamendo = false
        assertFalse("开关关（即使 clientId 非空）⇒ JAMENDO 不可见", visibility.isVisible(MusicSourceType.JAMENDO))
    }

    // ─────────────────── 组 3：百度「启用但未登录」边界 ───────────────────

    /**
     * 百度网盘的可见性判据是 `baiduActive`（= `CloudDriveConfig.isActive`），
     * 与 `NasMusicApp.registerService` 的判据**同源** ⇒ 「已启用但未登录」时百度不可见。
     *
     * ⛔ 方案 §5.2 指出百度有四条通路、只有一条受 service 注销控制 ——
     * 若可见性判据只看「开关」而不看「是否已登录」，关掉百度后仍会留下已登录态的残留条目。
     */
    @Test
    fun baiduEnabledButNotLoggedInIsInvisible() {
        val state = SwitchState()
        val visibility = state.visibility()

        // 已登录（baiduActive = true）
        assertTrue("已启用且已登录 ⇒ BAIDU_PAN 可见", visibility.isVisible(MusicSourceType.BAIDU_PAN))

        // 「启用但未登录」⇒ baiduActive = false ⇒ 不可见
        state.baidu = false
        assertFalse("已启用但未登录（baiduActive = false）⇒ BAIDU_PAN 不可见", visibility.isVisible(MusicSourceType.BAIDU_PAN))

        // 百度不可见时，其它源不受影响（判据没有串到别的源上）
        assertTrue("百度不可见不影响 NAS", visibility.isVisible(MusicSourceType.NAS))
        assertTrue("百度不可见不影响 NETWORK_MUSIC", visibility.isVisible(MusicSourceType.NETWORK_MUSIC))
    }

    // ─────────────────── 组 4：非曲库来源恒可见 ───────────────────

    /** RADIO / WEATHER_RADIO / DOWNLOAD / IMPORTED 不受任何音乐源开关影响（方案 S4：电台是独立功能）。 */
    @Test
    fun nonLibrarySourcesAreAlwaysVisible() {
        val alwaysVisible = listOf(
            MusicSourceType.RADIO,
            MusicSourceType.WEATHER_RADIO,
            MusicSourceType.DOWNLOAD,
            MusicSourceType.IMPORTED,
        )

        val allOn = SwitchState()
        val onVisibility = allOn.visibility()
        for (type in alwaysVisible) {
            assertTrue("$type 在全开时可见", onVisibility.isVisible(type))
        }

        val allOff = SwitchState(
            nas = false,
            local = false,
            meting = false,
            baidu = false,
            jamendo = false,
        )
        val offVisibility = allOff.visibility()
        for (type in alwaysVisible) {
            assertTrue("$type 在全关时仍可见（不受音乐源开关影响）", offVisibility.isVisible(type))
        }

        // 空转自证：全关时受管的五个源确实都不可见，否则上面这组「恒可见」是假阳性。
        for (type in listOf(
            MusicSourceType.NAS,
            MusicSourceType.LOCAL,
            MusicSourceType.NETWORK_MUSIC,
            MusicSourceType.BAIDU_PAN,
            MusicSourceType.JAMENDO,
        )) {
            assertFalse("$type 在全关时确实不可见（自证）", offVisibility.isVisible(type))
        }
    }

    /**
     * 方案 §5.6 红线：**关本地音乐不能把「已下载」一起藏掉**。
     * `Song.sourceType` 先判 `storageType == "DOWNLOAD"` 再判 `isLocalSong` ⇒ 已下载歌曲走 DOWNLOAD 分支。
     */
    @Test
    fun downloadedSongStaysVisibleWhenLocalSwitchOff() {
        val state = SwitchState(local = false)
        val visibility = state.visibility()

        val downloaded = song(id = "dl-song-1", storageType = "DOWNLOAD")
        assertEquals(
            "已下载歌曲的归属必须是 DOWNLOAD（先于 LOCAL / NAS 判定）",
            MusicSourceType.DOWNLOAD,
            downloaded.sourceType,
        )
        assertTrue("关本地音乐后已下载歌曲仍可见（§5.6 红线）", visibility.isVisible(downloaded))
        assertFalse("但未下载的本地音乐随开关隐藏", visibility.isVisible(MusicSourceType.LOCAL))
    }

    // ─────────────────── 组 5：networkSource 字符串 → MusicSourceType 映射对拍 ───────────────────

    /**
     * ⭐ 逐个对拍 `networkSource` 字符串 → `MusicSourceType`（方案 §3.1 点名的最大风险）。
     *
     * 覆盖：`NAS` / `baidu` / `jamendo` / `weather` / `radio` / `meting` / 未知字符串 /
     * `DOWNLOAD` / `LOCAL` / `imported_` 前缀。
     */
    @Test
    fun networkSourceStringMapsToExpectedMusicSourceType() {
        val cases = mappingCases()
        for (case in cases) {
            assertEquals(
                "映射不符：`${case.label}` 期望 ${case.expected}，实际 ${case.song.sourceType}",
                case.expected,
                case.song.sourceType,
            )
        }
    }

    /**
     * 把「未知 `networkSource` 字符串静默归入 `NETWORK_MUSIC`」这条**已知风险显式钉成断言**。
     *
     * 后果：这类歌会被 **Meting 开关**门控 —— 未来接入的独立网络源（在 `AppPreferences` 里
     * 已有 key `lx` = 洛雪，但 `MusicSourceType` 里还没有对应项）会莫名随 Meting 一起消失。
     * ⚠️ 本断言记录的是**当前真实行为**（不是期望行为）：若将来给 `networkSource` 补了映射，
     * 本测试会失败 ⇒ 那时必须同步更新 `SourceIdentifier` 的映射与本断言（方案 §3.1 的要求）。
     */
    @Test
    fun unknownNetworkSourceSilentlyFallsBackToNetworkMusic() {
        val unknown = song(id = "ntwk_future_1", isNetworkSong = true, networkSource = "some_future_source")
        assertEquals(
            "未知 networkSource 字符串当前静默归入 NETWORK_MUSIC（已知风险，须同步补映射）",
            MusicSourceType.NETWORK_MUSIC,
            unknown.sourceType,
        )

        // 后果具体化：它被 Meting 开关门控
        val metingOff = SwitchState(meting = false).visibility()
        assertFalse("未知来源归入 NETWORK_MUSIC ⇒ 受 Meting 开关门控（关 Meting 即消失）", metingOff.isVisible(unknown))
        assertTrue("Meting 开着时它可见", SwitchState().visibility().isVisible(unknown))

        // 偏好里的 `AppPreferences.SOURCE_LX`（= "lx"，洛雪取链通道）同样落进 NETWORK_MUSIC。
        // ⚠️ 洛雪目前**不是独立 service**、而是 Meting 的增强层 ⇒ 归入 NETWORK_MUSIC 恰好语义正确；
        // 但这条断言的价值在于：一旦「lx」将来变成独立 service，必须同步补 `SourceIdentifier` 的映射，
        // 否则它的歌会被 Meting 开关**误杀**（`when` 对字符串不穷尽 ⇒ 不编译报错）。
        val lx = song(id = "ntwk_lx_1", isNetworkSong = true, networkSource = AppPreferences.SOURCE_LX)
        assertEquals(
            "`AppPreferences.SOURCE_LX`（洛雪）当前没有 MusicSourceType 对应项 ⇒ 归入 NETWORK_MUSIC",
            MusicSourceType.NETWORK_MUSIC,
            lx.sourceType,
        )
    }

    /**
     * `isVisible(song)` 与 `isVisible(song.sourceType)` **必须同判**（源码里是同一个判据的两次调用）。
     * ⛔ 若哪天有人给 `isVisible(song)` 另写一套归属判定，两套判定必然漂移 ⇒ 「界面里有但播放解析失败」。
     *
     * 这里在**全部 32 种开关组合** × 全部样本 × 两种 clientId（空 / 非空）上对拍。
     */
    @Test
    fun songVisibilityAgreesWithSourceTypeVisibility() {
        val cases = mappingCases()
        for (mask in 0 until 32) {
            for (blankClientId in listOf(true, false)) {
                val state = SwitchState(
                    nas = mask and 0b00001 != 0,
                    local = mask and 0b00010 != 0,
                    meting = mask and 0b00100 != 0,
                    baidu = mask and 0b01000 != 0,
                    jamendo = mask and 0b10000 != 0,
                    jamendoClientId = if (blankClientId) "" else "client-id",
                )
                val visibility = state.visibility()
                for (case in cases) {
                    assertEquals(
                        "`${case.label}`：isVisible(song) 与 isVisible(sourceType) 必须一致（mask=$mask, blankClientId=$blankClientId）",
                        visibility.isVisible(case.song.sourceType),
                        visibility.isVisible(case.song),
                    )
                }
            }
        }
    }

    // ─────────────────── 样本与辅助 ───────────────────

    /** 映射对拍的全部样本（`label` 只用于断言消息，可读即可）。 */
    private fun mappingCases(): List<MappingCase> = listOf(
        // 非网络歌 ⇒ NAS
        MappingCase("NAS（非网络歌，networkSource 为 null）", song(id = "nas-song-1"), MusicSourceType.NAS),
        // 网络歌 + 具名 networkSource
        MappingCase("baidu", song(id = "ntwk_baidu_1", isNetworkSong = true, networkSource = "baidu"), MusicSourceType.BAIDU_PAN),
        MappingCase("jamendo", song(id = "ntwk_jamendo_1", isNetworkSong = true, networkSource = "jamendo"), MusicSourceType.JAMENDO),
        MappingCase("weather", song(id = "ntwk_weather_1", isNetworkSong = true, networkSource = "weather"), MusicSourceType.WEATHER_RADIO),
        MappingCase(
            "RadioStation.SOURCE_ID（= radio）",
            song(id = "ntwk_radio_1", isNetworkSong = true, networkSource = RadioStation.SOURCE_ID),
            MusicSourceType.RADIO,
        ),
        MappingCase(
            "meting（NetworkSongId.METING_SOURCE）",
            song(id = "ntwk_meting_1", isNetworkSong = true, networkSource = NetworkSongId.METING_SOURCE),
            MusicSourceType.NETWORK_MUSIC,
        ),
        // 未知字符串 ⇒ 静默归入 NETWORK_MUSIC（已知风险）
        MappingCase(
            "未知 networkSource",
            song(id = "ntwk_future_1", isNetworkSong = true, networkSource = "some_future_source"),
            MusicSourceType.NETWORK_MUSIC,
        ),
        // storageType = DOWNLOAD 优先于一切（即使它是网络歌）
        MappingCase(
            "storageType=DOWNLOAD（网络歌）",
            song(id = "ntwk_baidu_2", isNetworkSong = true, networkSource = "baidu", storageType = "DOWNLOAD"),
            MusicSourceType.DOWNLOAD,
        ),
        MappingCase(
            "storageType=DOWNLOAD（非网络歌）",
            song(id = "nas-dl-1", storageType = "DOWNLOAD"),
            MusicSourceType.DOWNLOAD,
        ),
        // 本地音乐
        MappingCase(
            "isLocalSong（storageType=EXTERNAL）",
            song(id = "local_media_store_1", isLocalSong = true, storageType = "EXTERNAL"),
            MusicSourceType.LOCAL,
        ),
        // imported_ 前缀优先于「非网络歌 ⇒ NAS」
        MappingCase("id 以 imported_ 开头", song(id = "imported_stub_1"), MusicSourceType.IMPORTED),
    )

    /** 构造一个 `Song` 样本；字段全部取自 `Song` 的真实构造参数（`isNetworkSong` / `isLocalSong` 是构造参数，不是计算属性）。 */
    private fun song(
        id: String,
        title: String = "测试歌曲",
        isNetworkSong: Boolean = false,
        networkSource: String? = null,
        isLocalSong: Boolean = false,
        storageType: String? = null,
    ): Song = Song(
        id = id,
        title = title,
        artist = "测试歌手",
        album = "测试专辑",
        isNetworkSong = isNetworkSong,
        networkSource = networkSource,
        isLocalSong = isLocalSong,
        storageType = storageType,
    )

    /** 映射对拍样本：`label`（断言消息用）/ `song`（样本）/ `expected`（期望的 `MusicSourceType`）。 */
    private data class MappingCase(
        val label: String,
        val song: Song,
        val expected: MusicSourceType,
    )

    /**
     * 局部开关状态：全部由本文件控制，⛔ 不触碰真实 `AppPreferences` / DataStore。
     * 默认全开（除 [jamendoClientId] 外均为 `true`）。
     */
    private data class SwitchState(
        var nas: Boolean = true,
        var local: Boolean = true,
        var meting: Boolean = true,
        var baidu: Boolean = true,
        var jamendo: Boolean = true,
        var jamendoClientId: String = "test-client-id",
    ) {
        /** 用当前状态构造一个 `SourceVisibility`（lambda 实时读状态 ⇒ 翻转立即生效）。 */
        fun visibility(): SourceVisibility = SourceVisibility(
            nasEnabled = { nas },
            localEnabled = { local },
            metingEnabled = { meting },
            baiduActive = { baidu },
            jamendoEnabled = { jamendo },
            jamendoClientId = { jamendoClientId },
        )
    }
}
