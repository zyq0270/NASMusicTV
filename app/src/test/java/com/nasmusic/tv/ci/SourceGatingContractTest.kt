package com.nasmusic.tv.ci

import com.nasmusic.tv.backend.SourceVisibility
import com.nasmusic.tv.backend.local.MusicMerger
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.data.model.sourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「音乐源开关全覆盖」方案 §7.2 的**组合契约测试**（纯 JVM，⛔ 不用 Robolectric）。
 *
 * ## 为什么是「组合契约」而不是端到端
 *
 * `MainViewModel` 依赖整个 `NasMusicApp`（playerManager / prefs / 各 Manager），
 * 仓库里**没有任何 `MainViewModel` 的测试宿主**，也没有 fake `BackendAdapter`（方案 §7.2 点名的基建缺口）。
 * ⇒ 端到端地翻转开关在纯 JVM 单测里做不到。
 *
 * ⛔ 但「关源后曲库里还有 NAS 歌」这件事**不必**靠端到端才能守住：它由三个可组合的纯件决定 ——
 * `SourceVisibility`（可见性判据）、`MusicMerger`（合并口径）、`SongsPagingState`（分页口径）。
 * ⇒ 本测试直接组合这三件，把**三条核心不变量**钉成可执行断言；
 * 而「VM 侧确实按这个口径接线」由 [SourceGatingGateTest] 的 G2（源码文本门）负责 ——
 * **文本门 + 组合契约**合起来覆盖方案 §7.2 的「行为测试为主、文本门为辅」。
 *
 * ## 三条不变量
 *
 * | # | 不变量 | 方案出处 |
 * |---|---|---|
 * | 1 | **NAS 关 ⇒ 合并后的歌曲集合不含 NAS 歌**（本地 / 百度照旧） | §0.4 / §3.3 G1 / V1 / V3 |
 * | 2 | **本地开关关 ⇒ 已下载歌曲仍可见**（⛔ 不得把「已下载」一起藏掉） | §5.6 **红线** |
 * | 3 | **NAS 关 ⇒ `totalCount` 口径不含 NAS**（否则显示一个不存在的「N 首歌」） | §3.3 G1 / §5.5 |
 *
 * ## 自包含性
 *
 * ⛔ 不依赖 `AppPreferences` / DataStore / 网络 / Robolectric：
 * 开关值全部由 lambda 提供，`Song` 样本全部是本文件构造的纯数据类实例。
 */
class SourceGatingContractTest {

    // ─────────────────── 不变量 1：NAS 关 ⇒ 合并结果不含 NAS 歌 ───────────────────

    /**
     * 方案 §0.4 / §3.3 G1：**关 NAS ⇒ NAS 段不参与合并**，
     * 但**本地 / 百度照旧**（⛔ 不是「整页清空」—— 那是 v2 的老误解）。
     */
    @Test
    fun `NAS 关时合并后的歌曲集合不含 NAS 歌`() {
        val visibility = visibility(nas = false)
        val nas = nasSong()
        val local = localSong()
        val baidu = baiduSong()

        // ── 可见性判据 ──
        assertFalse("NAS 关 ⇒ NAS 歌不可见", visibility.isVisible(nas))
        assertTrue("NAS 关不影响本地歌", visibility.isVisible(local))
        assertTrue("百度开 ⇒ 百度歌可见", visibility.isVisible(baidu))

        // ── 合并点（照抄 `MainViewModel.visibleSongsPaging` 的 nasSongs 口径：不可见 ⇒ 传空） ──
        val merged = MusicMerger.mergeLibrarySongs(
            nasSongs = emptyList(),
            localSongs = listOf(local),
            baiduSongs = listOf(baidu),
        )
        assertEquals("合并后只剩本地 + 百度（NAS 段为空）", listOf(local, baidu), merged)
        assertTrue("合并结果里不得有 NAS 歌", merged.none { it.sourceType == MusicSourceType.NAS })
        assertTrue("本地歌必须仍在（⛔ 不是整页清空）", merged.any { it.id == local.id })
        assertTrue("百度歌必须仍在（⛔ 不是整页清空）", merged.any { it.id == baidu.id })

        // ── 兜底：即便 NAS 段被误传进来，按可见性过滤也会把它剔除 ──
        val guarded = listOf(nas, local, baidu).filter { visibility.isVisible(it) }
        assertEquals("按可见性过滤后 NAS 歌被剔除", listOf(local, baidu), guarded)
        assertFalse("兜底过滤后仍不得有 NAS 歌", guarded.any { it.sourceType == MusicSourceType.NAS })
    }

    // ─────────────────── 不变量 2：本地开关关 ⇒ 已下载歌曲仍可见（§5.6 红线） ───────────────────

    /**
     * 方案 §5.6 **红线**：本地音乐开关的既有语义是「关 = 只显示已下载」
     * （`storageType == "DOWNLOAD"`，走应用专属目录、与 `READ_MEDIA_AUDIO` 无关）。
     *
     * ⛔ 新代码必须**沿用** `visibleLocalSongsNow()`，不得新写 `filter { }` 把已下载也滤掉。
     * `Song.sourceType` 先判 `storageType == "DOWNLOAD"` 再判 `isLocalSong`
     * ⇒ 已下载歌曲走 `DOWNLOAD` 分支 ⇒ `SourceVisibility` 对其恒返回 `true`。
     */
    @Test
    fun `本地开关关时已下载歌曲仍可见`() {
        val visibility = visibility(local = false)
        val downloaded = downloadedSong()
        val plainLocal = localSong()

        assertEquals(
            "已下载歌曲的归属必须是 DOWNLOAD（先于 LOCAL / NAS 判定）",
            MusicSourceType.DOWNLOAD,
            downloaded.sourceType,
        )
        assertTrue("关本地音乐后已下载歌曲仍可见（方案 §5.6 红线）", visibility.isVisible(downloaded))
        assertEquals(
            "未下载的本地音乐归属是 LOCAL",
            MusicSourceType.LOCAL,
            plainLocal.sourceType,
        )
        assertFalse("但未下载的本地音乐随开关隐藏", visibility.isVisible(plainLocal))

        // ── 合并点（照抄 `MainViewModel.visibleLocalSongs` 的口径：关 ⇒ 只留已下载） ──
        val allLocal = listOf(downloaded, plainLocal)
        val localVisible = allLocal.filter { it.storageType == "DOWNLOAD" }
        val merged = MusicMerger.mergeLibrarySongs(
            nasSongs = emptyList(),
            localSongs = localVisible,
            baiduSongs = emptyList(),
        )
        assertEquals("本地关时合并结果里保留已下载歌曲", listOf(downloaded), merged)
        assertTrue("已下载歌曲必须出现在可见集里", merged.any { it.id == downloaded.id })
        assertFalse("未下载的本地歌曲不得出现在可见集里", merged.any { it.id == plainLocal.id })
    }

    // ─────────────────── 不变量 3：NAS 关 ⇒ totalCount 口径不含 NAS ───────────────────

    /**
     * 方案 §3.3 G1 / §5.5：曲库「歌曲」Tab 的 `totalCount` 是**列表条数**口径。
     *
     * ⚠️ NAS 关时 NAS 的 `totalCount` **不得参与** —— 否则列表里已经没有 NAS 歌了，
     * 数字却还按全量算（用户看到「N 首歌」却翻不出 N 首）。
     *
     * ⚠️ 这条**没有**降级为等价断言：口径本身是可表达的 —— 它只依赖 `SourceVisibility` +
     * `MusicMerger` + `SongsPagingState` 三个纯件。
     * 唯一无法在纯 JVM 里直接调用的是 `MainViewModel.visibleSongsPaging` 本身
     * （`MainViewModel` 依赖整个 `NasMusicApp`，仓库里没有测试宿主），
     * ⇒ 这里把它的口径**平移**成 [vmVisiblePaging]，并由 `SourceGatingGateTest` 的 G2
     * 把 VM 侧的真实文本形状钉住（`totalCount = (if (nasVisible) paging.totalCount else 0) + …`）。
     */
    @Test
    fun `NAS 关时 totalCount 口径不含 NAS`() {
        val nas = nasSong()
        val local = localSong()
        val baidu = baiduSong()
        // 库存里有 100 首 NAS 歌（分页总数），当前页只有 1 首
        val paging = SongsPagingState(songs = listOf(nas), totalCount = 100, currentPage = 1)

        // ── NAS 开：totalCount = NAS 的 100 + 本地 1 + 百度 1 ──
        val on = vmVisiblePaging(paging, listOf(local), listOf(baidu), visibility(nas = true))
        assertEquals("NAS 开时 totalCount 含 NAS 的 100", 102, on.totalCount)
        assertEquals("NAS 开时列表含三段（NAS + 本地 + 百度）", 3, on.songs.size)

        // ── NAS 关：totalCount 只计本地 / 百度 ──
        val off = vmVisiblePaging(paging, listOf(local), listOf(baidu), visibility(nas = false))
        assertEquals(
            "NAS 关时 totalCount 不得计入 NAS 的 100（否则显示一个不存在的「N 首歌」）",
            2,
            off.totalCount,
        )
        assertEquals("NAS 关时列表只剩本地 + 百度", 2, off.songs.size)
        assertTrue("NAS 关时列表里不得有 NAS 歌", off.songs.none { it.id == nas.id })
        assertEquals(
            "totalCount 必须与实际可见条数一致（本地 1 + 百度 1）",
            off.songs.size,
            off.totalCount,
        )
    }

    /**
     * 不变量 3 的**对照**：把「NAS 关时的 totalCount 口径」与「v2 的老口径（直接沿用库存 totalCount）」并排，
     * 钉住两者必须**不同** —— 否则说明平移出来的口径退化成了「照抄库存值」。
     */
    @Test
    fun `NAS 关时 totalCount 必须与沿用库存值的旧口径不同`() {
        val paging = SongsPagingState(songs = listOf(nasSong()), totalCount = 100)
        val off = vmVisiblePaging(paging, listOf(localSong()), emptyList(), visibility(nas = false))
        assertEquals("新口径：只计本地 1", 1, off.totalCount)
        assertTrue("旧口径（直接沿用库存 totalCount）是 100 ⇒ 必须与新口径不同", paging.totalCount != off.totalCount)
    }

    // ─────────────────── 平移：VM 侧 `visibleSongsPaging` 的口径 ───────────────────

    /**
     * `MainViewModel.visibleSongsPaging` 的**纯函数平移**。
     *
     * 对应实现：`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt:510-534`。
     *
     * ```kotlin
     * val merged = MusicMerger.mergeLibrarySongs(
     *     nasSongs   = if (nasVisible)  paging.songs else emptyList(),
     *     localSongs = local.filter { sourceVisibility.isVisible(it) },
     *     baiduSongs = if (baiduVisible) baidu   else emptyList(),
     * )
     * paging.copy(
     *     songs = merged,
     *     totalCount = (if (nasVisible) paging.totalCount else 0) + localSongs.size + baiduSongs.size
     * )
     * ```
     *
     * ⚠️ 与 VM 的差别只有一处：VM 的本地段来自 `visibleLocalSongs`（已实现「关 = 只显示已下载」），
     * 这里由调用方传入同一口径的列表 ⇒ 语义等价。
     */
    private fun vmVisiblePaging(
        paging: SongsPagingState,
        localSongs: List<Song>,
        baiduSongs: List<Song>,
        visibility: SourceVisibility,
    ): SongsPagingState {
        val nasVisible = visibility.isVisible(MusicSourceType.NAS)
        val baiduVisible = visibility.isVisible(MusicSourceType.BAIDU_PAN)
        val nasSongs = if (nasVisible) paging.songs else emptyList()
        val localVisible = localSongs.filter { visibility.isVisible(it) }
        val baiduVisibleSongs = if (baiduVisible) baiduSongs else emptyList()
        val merged = MusicMerger.mergeLibrarySongs(
            nasSongs = nasSongs,
            localSongs = localVisible,
            baiduSongs = baiduVisibleSongs,
        )
        return paging.copy(
            songs = merged,
            totalCount = (if (nasVisible) paging.totalCount else 0) +
                localVisible.size + baiduVisibleSongs.size,
        )
    }

    // ─────────────────── 样本与辅助 ───────────────────

    /**
     * 构造一个 `SourceVisibility`（lambda 实时读状态 ⇒ 翻转立即生效）。
     *
     * ⛔ 不触碰真实 `AppPreferences` / DataStore。
     */
    private fun visibility(
        nas: Boolean = true,
        local: Boolean = true,
        meting: Boolean = true,
        baidu: Boolean = true,
        jamendo: Boolean = true,
        jamendoClientId: String = "test-client-id",
    ): SourceVisibility = SourceVisibility(
        nasEnabled = { nas },
        localEnabled = { local },
        metingEnabled = { meting },
        baiduActive = { baidu },
        jamendoEnabled = { jamendo },
        jamendoClientId = { jamendoClientId },
    )

    /** NAS 歌：非网络歌、非本地 ⇒ `Song.sourceType` 归入 `MusicSourceType.NAS`。 */
    private fun nasSong(id: String = "nas-song-1") = Song(
        id = id,
        title = "NAS 歌",
        artist = "测试歌手",
        album = "测试专辑",
    )

    /** 本地歌（未下载）：`isLocalSong = true`、`storageType != "DOWNLOAD"` ⇒ 归属 `LOCAL`。 */
    private fun localSong(id: String = "local-song-1") = Song(
        id = id,
        title = "本地歌",
        artist = "测试歌手",
        album = "测试专辑",
        isLocalSong = true,
        storageType = "EXTERNAL",
    )

    /** 已下载歌曲：`storageType == "DOWNLOAD"` ⇒ 归属 `DOWNLOAD`（**先于 LOCAL 判定**）。 */
    private fun downloadedSong(id: String = "downloaded-song-1") = Song(
        id = id,
        title = "已下载歌",
        artist = "测试歌手",
        album = "测试专辑",
        isLocalSong = true,
        storageType = "DOWNLOAD",
    )

    /** 百度歌：网络歌 + `networkSource == "baidu"` ⇒ 归属 `BAIDU_PAN`。 */
    private fun baiduSong(id: String = "ntwk_baidu_1") = Song(
        id = id,
        title = "百度歌",
        artist = "测试歌手",
        album = "测试专辑",
        isNetworkSong = true,
        networkSource = "baidu",
    )
}
