package com.nasmusic.tv.backend

import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType

/**
 * 某个音乐源此刻是否「可见」（方案 §3.1 · S1：可被列出）。
 *
 * ## 它只回答一个问题：用户开没开
 *
 * ⛔ **只回答「用户把这个源开着吗」，不回答「它现在能不能用」**——
 * 「能不能用」是 `backendRegistry`（NAS 是否已连接）/ `NetworkMusicManager.services`
 * （service 是否已注册）的事，两者混在一起会让「NAS 未连接」被当成「NAS 被关掉」，
 * 于是 UI 会把一个**用户明明开着**的源整片藏起来。
 *
 * ## 为什么用「每源一个同步 lambda」而不是 `settings: () -> AppSettings`
 *
 * 方案原文写的是 `settings: () -> AppSettings`，但 `AppSettings` 只有 **Flow**（`appSettings`），
 * 没有同步快照 ⇒ 构造期快照会让开关永远停在启动时的值（这正是 `LocalMusicGateTest`
 * 当初钉住的同一类缺陷：搜索聚合器在 `onCreate` 建一次，快照 ⇒ 开关翻转后不生效）。
 *
 * 改成「每源一个同步 lambda」后语义完全等价（仍然都是**调用时实时读**），
 * 只是换了个依赖注入形态：
 * - NAS / Meting / Jamendo / 百度 ⇒ 读 `AppPreferences` 已有的 `@Volatile` 同步镜像；
 * - 本地 ⇒ 读 `AppPreferences` 的 `local_music_enabled` 运行期镜像。
 *
 * ## ⚠️ 边界一：未知 `networkSource` 字符串会静默归入 `NETWORK_MUSIC`
 *
 * 归属判定复用 `Song.sourceType`（`data/model/SourceIdentifier.kt`）：它对**字符串**不穷尽，
 * 任何未知的 `networkSource` 都会落到 `isNetworkSong -> MusicSourceType.NETWORK_MUSIC` 分支。
 * ⇒ 未来的独立网络源会**莫名受 Meting 开关影响**（`when` 对枚举穷尽 ⇒ 不会编译报错）。
 * ⇒ 新增网络源时必须同步更新 `SourceIdentifier` 的映射，并对拍所有现存 `networkSource` 字符串。
 *
 * ## ⚠️ 边界二：镜像冷启动窗口
 *
 * 底层是 `@Volatile` 内存镜像，`startProviderMirrors` 与 `NasMusicApp` 的注册并发执行 ⇒
 * 启动极短窗口内镜像可能仍是编译期默认值。首帧 UI 刷新可延迟到镜像同步后（与注册侧同策略）。
 *
 * ## ⚠️ 边界三：`LOCAL` 与 `DOWNLOAD` 是两个来源，但都不受「下载」语义影响
 *
 * 本地音乐开关的既有语义是「关 = 只显示已下载」（`storageType == "DOWNLOAD"`）。
 * ⛔ **不要因为 `LOCAL` 分支判了 `localEnabled()` 就把已下载歌曲一起滤掉**：
 * `Song.sourceType` 先判 `DOWNLOAD` 再判 `LOCAL`，所以已下载歌曲走 `DOWNLOAD` 分支 ⇒ 恒可见。
 */
class SourceVisibility(
    /** NAS 服务器作为音乐源的总开关（`settings_nas_music_enabled`）。 */
    private val nasEnabled: () -> Boolean,
    /** 本地音乐总开关（`local_music_enabled`）。 */
    private val localEnabled: () -> Boolean,
    /** Meting（网络音乐）总开关（`settings_meting_enabled`）。 */
    private val metingEnabled: () -> Boolean,
    /** 百度网盘是否「启用 **且** 已登录」（= `CloudDriveConfig.isActive`，与 `registerService` 判据一致）。 */
    private val baiduActive: () -> Boolean,
    /** Jamendo 总开关（`settings_jamendo_enabled`）。 */
    private val jamendoEnabled: () -> Boolean,
    /** Jamendo Client ID（空 ⇒ Jamendo 不可用 ⇒ 不可见）。 */
    private val jamendoClientId: () -> String,
) {

    /**
     * 按来源类型判定「此刻是否可见」。
     *
     * @param type 来源类型（`MusicSourceType`，与 `Song.sourceType` 同一套枚举）
     */
    fun isVisible(type: MusicSourceType): Boolean = when (type) {
        MusicSourceType.NAS -> nasEnabled()
        MusicSourceType.LOCAL -> localEnabled()
        MusicSourceType.BAIDU_PAN -> baiduActive()
        MusicSourceType.NETWORK_MUSIC -> metingEnabled()
        // Jamendo 的生效条件 = 开关 **且** clientId 非空（与 `registerService` 判据完全一致）
        MusicSourceType.JAMENDO -> jamendoEnabled() && jamendoClientId().isNotBlank()
        // 非曲库来源：不受音乐源开关影响（S4：电台 / 天气电台是独立功能；已下载 / 导入同理）
        MusicSourceType.RADIO,
        MusicSourceType.WEATHER_RADIO,
        MusicSourceType.DOWNLOAD,
        MusicSourceType.IMPORTED -> true
    }

    /**
     * 按 Song 的实际归属判定（用于历史 / 收藏这类「已存在的条目」，S3：只过滤展示、不删数据）。
     *
     * ⚠️ 归属判定**必须**复用 `Song.sourceType`，⛔ 不要新写一套 —— 两套判定必然漂移，
     * 后果是「界面里有但播放解析失败」。
     */
    fun isVisible(song: Song): Boolean = isVisible(song.sourceType)
}
