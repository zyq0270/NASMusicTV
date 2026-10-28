package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel

/**
 * 「网络音乐源」卡④ 的一行（方案 §7.5）。
 *
 * ⚠️ 这是**只读视图模型**，不新增存储：每个源「该不该活着」的真相源各不相同
 * （meting = 构造期塞 map，baidu = `baiduConfig.isActive`，jamendo = 开关且 clientId 非空），
 * 硬塞进一个 map 会立刻产生两套真相。
 *
 * ⛔ 不要加 `toggleable` / `blockedReason`：它们原本只为「未接入的 Alapi / JioSaavn」服务，
 * 那两项已删除（§7.5）。
 *
 * ⚠️ v2.39.8（用户 2026-10-28 反馈）：本行列表**只剩 Meting**。百度网盘与 Jamendo
 * 各有自己的卡片（卡③ / 卡⑤），再在这里列一遍就是**同一件事两个开关**
 * —— 用户明确指出的「百度开关重复」。凡是有独立卡片的源，⛔ 不要再回到这张卡里。
 */
data class MusicSourceRow(
    /** = `NetworkMusicManager.services` 的 key（当前只有 `meting`） */
    val key: String,
    val title: String,
    /** 行说明：「当前平台：酷狗 · 端点 qijieya · 默认源」 */
    val status: String,
    val enabled: Boolean,
    /**
     * 是否提供歌单 / 艺人 / 封面目录能力（卡⑥ 的候选判据）。
     *
     * 当前只有 Meting 覆盖了 `getPlaylist` / `findArtist` / `getArtistSongs`
     * （百度网盘与 Jamendo 的实现直接返回空）。卡⑥ 只列能真正干这件事的源，
     * ⛔ 不要再把「不提供目录」的源列进去让用户选了一个必然空白的选项。
     */
    val providesCatalog: Boolean = false,
)

/**
 * 来源分区状态（v2.39.0 合并 SERVER + LOCAL_MUSIC + NETDISK 三个旧分区，方案 §3 / Task 3）。
 *
 * 字段**逐一沿用旧分区的原名与可空性**（`ServerSettingsState` / `LocalMusicSettingsState` /
 * `BaiduPanSettingsState`），`SettingsScreen` 侧只是把三处构造合成一处。
 *
 * [defaultNetworkSource] 的 UI（卡⑤「网络搜索默认源」）由 **Task 7** 落地，
 * 经 `MainViewModel.updateDefaultNetworkSource` → `AppPreferences.setDefaultNetworkSource`
 * 写 DataStore（§6-10）。
 */
data class SourcesSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
    val settings: AppSettings,
    val libraryCount: Int,
    val baiduEnabled: Boolean,
    val baiduLoggedIn: Boolean,
    val baiduConnectionState: NetworkMusicViewModel.BaiduConnectionState,
    val baiduMusicRootDirLocal: String,
    val baiduMvDirLocal: String?,
    val baiduIndexScanned: Int,
    val baiduIndexScanning: Boolean,
    val baiduApicExtracting: Boolean,
    val baiduApicExtracted: Int,
    val baiduApicTotal: Int,
    /** 歌单/艺人数据源的 key（原「网络搜索默认源」） */
    val defaultNetworkSource: String,
    /**
     * 卡④ 的源行。v2.39.8 起**只剩 Meting 一行**：百度网盘（卡③）与 Jamendo（卡⑤）
     * 各有自己的卡片与开关，不再在这里重复一遍。
     */
    val musicSources: List<MusicSourceRow> = emptyList(),
    /** v2.39.8：Jamendo 独立卡（用户 2026-10-28：Jamendo 应独立） */
    val jamendoClientId: String = "",
)

/**
 * 来源分区动作。
 *
 * ⚠️ 可空性**逐字沿用旧分区**（未按方案原文改成非空默认 `{}`）：合并是纯搬迁，
 * 保留可空 ⇒ 「断开连接 / 登录按钮 / 重建索引」三处**条件渲染**的判据不变 ——
 * 尤其 `ServerSettingsSection` 的断开连接是「未连接时整段不渲染」，改成非空会让
 * 判据失效、按钮变成常驻（Task 7 明令禁止的手顺改动）。
 */
data class SourcesActions(
    val onNavigateToServerConnect: (() -> Unit)?,
    val onDisconnect: (() -> Unit)?,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onRescan: () -> Unit = {},
    val onToggleBaiduEnabled: ((Boolean) -> Unit)?,
    val onStartBaiduDeviceCode: (() -> Unit)?,
    val onLogoutBaidu: (() -> Unit)?,
    val onRebuildBaiduIndex: (() -> Unit)?,
    /** 改歌单/艺人数据源（写 DataStore，见 `DefaultNetworkSourcePrefTest`） */
    val onChangeDefaultNetworkSource: (String) -> Unit = {},
    /** 卡④ / 卡① / 卡⑤：启停一个源（key = `AppPreferences.SOURCE_*`） */
    val onToggleSourceEnabled: (String, Boolean) -> Unit = { _, _ -> },
    /** 卡⑤ Jamendo：打开 Client ID 编辑框（对话框宿主在 `SettingsScreen`） */
    val onEditJamendoClientId: (() -> Unit)? = null,
)

/** 来源分区对话框触发回调（对话框宿主仍在 `SettingsScreen`，本任务只搬参数名） */
data class SourcesDialogActions(
    val onShowBaiduAuthDialog: () -> Unit,
    val onShowMusicRootDialog: () -> Unit,
    val onShowMvDirDialog: () -> Unit,
)

/**
 * 来源分区：① NAS 服务器 ② 本地曲库 ③ 百度网盘（含索引与封面）
 * ④ 网络音乐源（Meting + 洛雪取链通道） ⑤ Jamendo ⑥ 歌单与艺人数据源。
 *
 * ⚠️ 行级排布逐行未动（T3 纯搬迁产物）：断开连接是**条件渲染**（未连接整段不渲染，
 * ⛔ 不是 `enabled = false`）；占位网盘分组（`CloudDriveType.PLACEHOLDER`）已按
 * §6-4（用户 2026-10-18 确认删除）连同 `PlaceholderRow` 一并移除。
 *
 * ⭐ v2.39.8（用户 2026-10-28 四条反馈）的分区纪律 —— **每个源恰好一个开关，且都在自己的卡里**：
 *
 * | 源 | 开关所在卡 | ⛔ 不要再出现的位置 |
 * |---|---|---|
 * | NAS | 卡① 顶部 | 卡④（曾是「源行」但漏了开关） |
 * | 本地曲库 | 卡② | — |
 * | 百度网盘 | 卡③ 顶部 | 卡④（曾是**第二个**百度开关，用户明确指出的重复） |
 * | Meting | 卡④ | — |
 * | 洛雪取链 | 卡④ Meting 行下方子块（**它是通道不是源**，§7.0 合并②） | 独立卡 |
 * | Jamendo | 卡⑤（独立卡，用户要求） | 卡④ |
 *
 * 「网络音乐源」这个卡名保留给**真正并列的在线曲库源**；一旦某个源有了自己的配置卡
 * （NAS 地址 / 百度授权与目录 / Jamendo Client ID），它的开关就跟着那张卡走，
 * ⛔ 不要再回到卡④ 里当一行 —— 那会让同一件事出现两个开关，且用户改一个、看另一个。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SourcesSettingsSection(
    state: SourcesSettingsState,
    actions: SourcesActions,
    dialogs: SourcesDialogActions,
    lxState: LxSubBlockState = LxSubBlockState(),
    lxActions: LxSubBlockActions = LxSubBlockActions(),
) {
    Column {
        SectionTitle(stringResource(R.string.settings_sources))

        // ── ① NAS 服务器 ──
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_nas_server),
            description = stringResource(R.string.settings_group_nas_server_desc),
        ) {
            // v2.39.8（用户 2026-10-28）：NAS 缺独立开关 —— 此前只能靠「断开连接」，
            // 而断开是要重新输口令的破坏性动作。「临时不想让 NAS 进搜索结果」不需要断开。
            // ⛔ 本开关**不清连接、不清凭据**，重新打开即恢复。
            SettingSwitch(
                label = stringResource(R.string.settings_source_nas),
                description = stringResource(R.string.settings_source_nas_desc),
                checked = state.settings.nasMusicEnabled,
                onClick = {
                    actions.onToggleSourceEnabled(
                        com.nasmusic.tv.data.prefs.AppPreferences.SOURCE_NAS,
                        !state.settings.nasMusicEnabled,
                    )
                },
            )
            val statusText = if (state.isConnected)
                stringResource(R.string.server_connected, state.serverDisplayName)
            else
                stringResource(R.string.server_connect_desc)
            Text(statusText, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            SettingActionButton(
                label = stringResource(R.string.server_config_title),
                description = if (state.isConnected) stringResource(R.string.server_connected, state.serverDisplayName)
                    else stringResource(R.string.server_connect_desc),
                onClick = { actions.onNavigateToServerConnect?.invoke() },
                // 跳到连接页（下一级）
                trailing = Trail.Arrow,
            )
            // ⛔ 未连接时「断开连接」整段不渲染（不是 enabled = false）—— 保留原形态
            if (state.isConnected && actions.onDisconnect != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingActionButton(
                    label = stringResource(R.string.server_disconnect),
                    description = stringResource(R.string.settings_disconnect_desc),
                    onClick = actions.onDisconnect,
                    // §4.7-③ S6：断开后要重新走连接流程（找回服务器地址 / 输口令）
                    danger = true,
                )
            }
        }

        // ── ② 本地曲库 ──
        SettingsGroupCard(title = stringResource(R.string.settings_group_local_music)) {
            // ⛔ 总开关 = 音乐权限的唯一触发点（§5.5）：ViewModel 内部判
            //    「已授权直接开 / 未授权先申请、被拒回弹」，这里只转发目标值。
            val s = state.settings
            SettingSwitch(
                label = stringResource(R.string.settings_local_music_source),
                description = stringResource(R.string.settings_local_music_source_desc),
                checked = s.localMusicEnabled,
                onClick = { actions.onSetEnabled(!s.localMusicEnabled) }
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                SettingsInfoRow(
                    stringResource(R.string.settings_local_music_source),
                    stringResource(R.string.settings_local_music_count, state.libraryCount),
                )
            }
            // 开关关闭时置灰（§5.6）：关着开关扫描没有意义 —— 结果也不会出现在曲库
            SettingActionButton(
                label = stringResource(R.string.settings_local_music_rescan),
                description = stringResource(R.string.settings_local_music_rescan_desc),
                onClick = actions.onRescan,
                enabled = s.localMusicEnabled,
            )
        }

        // ── ③ 百度网盘（含「索引与封面」，方案 §7.0 合并①） ──
        // 卡级说明改成「索引 / 封面提取」两件事的代价；两处都**无条件渲染**，
        // ⛔ 不要再给索引区块挂外层 `if` —— 那会出现「百度网盘卡正常、索引区块凭空消失」。
        SettingsGroupCard(
            title = stringResource(R.string.settings_netdisk_group_baidu),
            description = stringResource(R.string.settings_netdisk_index_desc2),
        ) {
            SettingSwitch(label = stringResource(R.string.settings_netdisk_enable), description = stringResource(R.string.settings_netdisk_enable_desc), checked = state.baiduEnabled, onClick = { actions.onToggleBaiduEnabled?.invoke(!state.baiduEnabled) })

            // 授权失败时，在登录按钮上方持续显示失败原因（即使对话框关闭也能看到）
            val baiduFailedState = state.baiduConnectionState as? NetworkMusicViewModel.BaiduConnectionState.Failed
            if (baiduFailedState != null) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.netdisk_auth_failed),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.body(),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = baiduFailedState.message,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                }
            }

            // 已登录但音乐根目录不存在，提示用户重新设置
            val baiduDirMissing = state.baiduConnectionState is NetworkMusicViewModel.BaiduConnectionState.DirMissing
            if (baiduDirMissing) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.netdisk_dir_missing),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.body(),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.netdisk_dir_missing_desc),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                }
            }

            if (actions.onStartBaiduDeviceCode != null) {
                Spacer(modifier = Modifier.height(8.dp))
                if (state.baiduLoggedIn) {
                    SettingActionButton(
                        label = stringResource(R.string.settings_netdisk_logged_in),
                        description = stringResource(R.string.settings_netdisk_logout_desc),
                        onClick = { actions.onLogoutBaidu?.invoke() }
                    )
                } else {
                    SettingActionButton(
                        label = stringResource(R.string.settings_netdisk_login),
                        description = stringResource(R.string.settings_netdisk_login_desc),
                        onClick = {
                            dialogs.onShowBaiduAuthDialog()
                            actions.onStartBaiduDeviceCode?.invoke()
                        },
                        // 打开设备码授权对话框（下一级）
                        trailing = Trail.Arrow,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // §4.5 硬要求 3：路径是**当前值**，归 SettingsInfoRow 的值位；说明归 description。
            // 此前路径直接塞进按钮的 description 里 —— 同一行既像说明又像值，是既有违例。
            SettingsInfoRow(stringResource(R.string.settings_netdisk_music_root), state.baiduMusicRootDirLocal)
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_music_root_edit),
                description = stringResource(R.string.settings_netdisk_music_root_desc),
                onClick = { dialogs.onShowMusicRootDialog() },
                // 打开路径编辑对话框（下一级）
                trailing = Trail.Arrow,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                stringResource(R.string.settings_netdisk_mv_dir),
                state.baiduMvDirLocal?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.common_not_set),
            )
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_mv_dir_edit),
                description = stringResource(R.string.settings_netdisk_mv_dir_desc),
                onClick = { dialogs.onShowMvDirDialog() },
                // 打开路径编辑对话框（下一级）
                trailing = Trail.Arrow,
            )

            // ⑤ 索引状态 + [重建索引]（原卡④，合并进本卡：它本来就只针对百度网盘）
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                else stringResource(R.string.settings_netdisk_index_desc, state.baiduIndexScanned),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            // APIC 封面提取进度（放在已扫描歌曲数量下方、重建索引按钮上方）
            if (state.baiduApicExtracting || state.baiduApicTotal > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.baiduApicExtracting) {
                        stringResource(R.string.settings_baidu_apic_extracting, state.baiduApicExtracted, state.baiduApicTotal)
                    } else {
                        stringResource(R.string.settings_baidu_apic_done, state.baiduApicTotal)
                    },
                    color = if (state.baiduApicExtracting) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (state.baiduApicExtracting && state.baiduApicTotal > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NasMusicColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((state.baiduApicExtracted.toFloat() / state.baiduApicTotal).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_index_rebuild),
                description = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                               else stringResource(R.string.settings_netdisk_index_rebuild_desc),
                onClick = { if (!state.baiduIndexScanning) actions.onRebuildBaiduIndex?.invoke() }
            )
        }

        // ── ④ 网络音乐源（Meting + 洛雪取链通道） ──
        //
        // ⛔ v2.39.8 起这里**只放 Meting 一行**（用户 2026-10-28 反馈）：
        //    - 百度网盘曾在这里也有一行开关 ⇒ 与卡③ 的开关重复，已删除；
        //    - Jamendo 曾在这里 ⇒ 已独立成卡⑤。
        // 洛雪子块紧贴 Meting 行下方（§7.1：它是 Meting 的第二条取链通道，不是第二个源）。
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_music_sources),
            description = stringResource(R.string.settings_music_sources_desc),
        ) {
            state.musicSources.forEach { row ->
                SettingSwitch(
                    label = row.title,
                    description = row.status,
                    checked = row.enabled,
                    onClick = { actions.onToggleSourceEnabled(row.key, !row.enabled) },
                )
                // 洛雪子块**紧贴 Meting 行下方**（§7.1：中间不插任何其它源行 ——
                // 这是「它属于 Meting」的唯一视觉证据）
                if (row.key == com.nasmusic.tv.backend.network.NetworkSongId.METING_SOURCE) {
                    LxScriptsSubBlock(state = lxState, actions = lxActions)
                }
            }
            // 固定灰字（§7.5）：关源后该源的收藏 / 歌单播不了，先讲清后果
            SettingsHint(stringResource(R.string.settings_source_off_playback_warn))
        }

        // ── ⑤ Jamendo（v2.39.8 独立成卡：用户 2026-10-28「Jamendo 应独立」）──
        //
        // 为什么独立：它有自己的凭据（Client ID）和独立的启用条件（开关 且 clientId 非空），
        // 混在「网络音乐源」卡里时，那行开关其实**同时**受两个因素影响（开关关了 / ID 没配
        // 都显示为关），用户改一个开关看不出是哪一个在起作用。
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_jamendo),
            description = stringResource(R.string.settings_group_jamendo_desc),
        ) {
            val jamendoReady = state.settings.jamendoEnabled && state.jamendoClientId.isNotBlank()
            SettingSwitch(
                label = stringResource(R.string.settings_source_jamendo),
                // 生效 = 开关 **且** Client ID 非空（§7.5）：未配 ID 时如实说明为什么是关的
                description = if (state.jamendoClientId.isBlank())
                    stringResource(R.string.settings_source_jamendo_no_client)
                else stringResource(R.string.settings_source_jamendo_ready),
                checked = jamendoReady,
                onClick = {
                    // 用户点「开」但 ID 没配 ⇒ 直接打开 Client ID 编辑框，而不是把开关翻上去却静默不生效
                    if (!jamendoReady && state.jamendoClientId.isBlank()) {
                        actions.onEditJamendoClientId?.invoke()
                    } else {
                        actions.onToggleSourceEnabled(
                            com.nasmusic.tv.data.prefs.AppPreferences.SOURCE_JAMENDO,
                            !state.settings.jamendoEnabled,
                        )
                    }
                },
            )
            if (actions.onEditJamendoClientId != null) {
                SettingsInfoRow(
                    label = stringResource(R.string.settings_jamendo_client_id),
                    value = if (state.jamendoClientId.isBlank())
                        stringResource(R.string.common_not_set)
                    else state.jamendoClientId.take(24) +
                        if (state.jamendoClientId.length > 24) "…" else "",
                )
                SettingActionButton(
                    label = stringResource(R.string.settings_jamendo_client_id),
                    description = stringResource(R.string.settings_jamendo_client_id_desc),
                    onClick = { actions.onEditJamendoClientId?.invoke() },
                    trailing = Trail.Arrow,
                )
            }
        }

        // ── ⑥ 歌单与艺人数据源（原「网络搜索默认源」，v2.39.8 改名并澄清语义）──
        //
        // ⛔ 改名理由（用户 2026-10-28 的原话质疑：「网络搜索默认源这个设置有什么用？
        //    网络歌曲和百度网盘歌曲是并行都搜索的，同时列出歌曲」）——**质疑完全成立**：
        //    `SearchAggregator` 对 NAS / 网络 / 百度 / Jamendo / 本地是**并行**搜索后合并去重的，
        //    没有任何「默认源优先」。这个值真正影响的是**按源精确路由的那几处**：
        //    `getPlaylist` / `findArtist` / `getArtistSongs`（`NetworkMusicManager` 用
        //    `services[defaultSource]`，不 fallback），以及 `NetworkMusicManager.search`
        //    内部的**源间 fallback 顺序**（默认源先试，空结果才换下一个）。
        //    所以它叫「默认源」会让人以为会改变搜索结果构成 —— 必须改名并写清后果。
        //
        // 候选也只列**真的提供目录能力**的源（`providesCatalog`）：百度网盘与 Jamendo 的
        //    `getPlaylist` / `findArtist` 实现直接返回空，列出来就是「选了必然空白」。
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_default_source),
            description = stringResource(R.string.settings_default_source_desc),
        ) {
            val candidates = state.musicSources.filter { it.enabled && it.providesCatalog }
            if (candidates.isEmpty()) {
                // ⚠️ 两种「空」原因不同，文案不能共用：
                //   - Meting 被关 ⇒ 说「歌单页与艺人页将没有数据」（这是用户自己关的）
                //   - 其它原因 ⇒ 说「无可用音乐源，请在上方开启」
                // 混用会让用户去开启百度/Jamendo，而它们本来就不提供目录。
                Text(
                    text = if (!state.settings.metingEnabled)
                        stringResource(R.string.settings_catalog_source_disabled)
                    else stringResource(R.string.settings_no_music_source),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                )
            } else {
                if (candidates.size == 1) {
                    // 只有一项时仍然渲染：让用户看得见「现在是哪个」，点它无副作用
                    SettingsHint(stringResource(R.string.settings_catalog_source_only_meting))
                }
                candidates.forEach { row ->
                    val selected = state.defaultNetworkSource == row.key
                    SettingActionButton(
                        label = if (selected) "▶ ${row.title}" else row.title,
                        description = row.status,
                        onClick = { actions.onChangeDefaultNetworkSource(row.key) }
                    )
                }
            }
        }
    }
}
