# NASMusicTV 后续功能开发方案（2026-11-28 · v2.3 已实施）

> **状态**：v2.3「已实施」——七项功能已按本文落地，版本 `2.39.29` / `versionCode = 214`（2026-11-28）。R1–R6 按各项「推荐 / 未裁定默认」实施，最终裁定仍以所有者验收为准；R7、R8 已裁定并落地。
> **基线**：2026-11-28 源码静态核查，分支 `main`，版本 `2.39.28` / `versionCode = 213`；实施记录见文末「十一、实施记录」。
> **已裁定**：R7＝年度报告只做数据页、年份可选、不完整年份可展示、不生成 PNG；R8＝缓存数量上限逐项直接配置、不做三档/MB 总预算。R1–R6 实施口径见各项结论行。
> **范围**：P0 三项（响度归一/gapless、歌单双向同步导出、外部控制补齐）、P1 语音点歌、P2 三项（元数据整理、年度听歌报告、弱网离线策略）。
> **不包含**：语音识别打分、听歌识曲、自研 AI 推荐、新增音乐源、K 歌麦克风评分。
> **导航**：§0 纪律与公共底座 → §1–§7 七项功能 → §8 实施顺序 → §9 R1–R8 → §10 冻结项与开工条件。

---

## 〇、执行纪律、现状修正与公共底座

### 0.1 硬约束

| 项 | 口径 |
|---|---|
| 分支 | 单目录双分支。本文在 `main` 上维护；`feat/e51-private` 永不 push/tag/release。文档提交后运行 `python scripts/check_doc_parity.py --check`；该脚本比较已提交 git trees，新增文档必须同步提交/覆盖到私有分支后才检查。 |
| 版本 | 功能定稿后的再改动先升号。每项独立功能建议单独占一个版本，具体版本号由所有者确认；联动 `app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md` §10 与相关门禁。 |
| 出包 | 本地构建必带 `-Plofi.city=true`、`--no-daemon`、`-Pkotlin.compiler.execution.strategy=in-process`。APK 复制到 `output/apk/`；同号多次出包报告字节数、修改时间、SHA256 前 12 位。 |
| 设备 | 代理不操作电视、不启动应用、不取画面；听感、画面、遥控器验收由所有者执行。手机仅可用于所有者授权的取证。 |
| 权限 | 不新增 `RECORD_AUDIO`、不读写公共媒体库、不新增第三方 SDK。语音能力探测只允许必要的 `<queries>`，不得使用 `QUERY_ALL_PACKAGES`。 |
| 兼容 | 核心功能支持 API 22；新增效果或重功能必须在老电视可用或明确降级。不得因本方案回退权限瘦身与 FLAC 解码熔断。 |
| 字符串 | 用户可见文案进入 `res/values/strings.xml` 与 `res/values-en/strings.xml`，不得在 Composable 中硬编码中文。 |
| 设置 UI | 复用 `SettingSwitch`、`SettingsChipRow`、`SettingActionButton`、`SettingsGroupCard`、`TextInputDialog`；不新造控件体系。 |
| 数据安全 | 导出、日志、广播 extra、小组件不得泄漏 token、密码、服务器凭据。URL 脱敏必须覆盖查询参数、userinfo 与 fragment。 |
| 门禁 | 行为测试优先，源码扫描为辅；每个负向门禁必须用变异夹具自证“确实能判红”。测试基线以实施前实际结果为准，不预设固定失败数量。 |
| 源码修改 | 脚本改 `.kt` 只允许完整多行原文 `str.replace` 并断言命中次数；同一文件多处改动放同一脚本，统一 `write_bytes` 保 LF。改完 Kotlin 先跑 `python scripts/kotlin_comment_balance.py`。 |

### 0.2 对 v2.1 的关键现状修正

| 主题 | v2.1 的问题 | v2.2 口径 |
|---|---|---|
| URL 预刷 | 允许调用 `builtinStreamUrlResolver` | 禁止。该 resolver 会 `replayAt(index)` 并强制切歌；预刷必须使用无播放副作用的纯解析 API。 |
| ReplayGain | 单一 `current` 增益、`+6 dB` 直接 clamp | 增益按歌曲/播放器分离；正增益策略必须由 R1 裁定，不能把 clamp 当成归一。 |
| 音量出口 | 只列部分 `player.volume` 写入 | 全仓收敛所有 `.volume =` / `setVolume(...)`，包括 player 注入、crossfade 初始化与恢复。 |
| gapless 回落 | 0.2 秒复用现有 crossfade | 现有 crossfade 为整数秒、秒级触发、双播放器；需另做毫秒级短淡入或完整改造。 |
| 歌单身份 | 直接使用裸 `Song.id` | 必须先绑定 `backendType + serverId + nativeSongId`；不匹配当前后端时拒绝推送。 |
| 歌单格式 | JSON/M3U 被视为无损 | JSON 做版本化全保真格式；M3U 只承诺可移植显示信息与 URI。 |
| Subsonic | 被认为六个写 API 齐备 | 当前只有读取实现；写能力完成前必须显式禁用 Subsonic 推送。 |
| 外部控制 | 在 Service 中直接切换收藏并返回成功 | 先建立 Service/Application 级控制状态源与真实异步结果，再暴露按钮。 |
| 手机语音 | 默认 HTTP H5 可调用 Web Speech API | 局域网 HTTP 不是安全上下文；必须先解决 HTTPS，或降级为系统输入法语音。 |
| 搜索一致性 | 认为遥控 `/api/search` 已走聚合器 | 当前遥控直接调用 NAS 与 NetworkMusicManager；需抽共享搜索服务。 |
| 稳定键 | `nas:id`、网络平台可空、标题 fallback | 稳定键必须版本化并包含来源身份；禁止用可变标题/艺术家作为正式 key。 |
| 元数据覆盖 | 只改曲库读取点 | 必须集中到统一展示读取模型，覆盖搜索、收藏、历史、歌单、队列和遥控。 |
| 年度统计 | 挂在 `addPlayRecord`，由 daily/monthly 精确回填 | 改为唯一播放事件模型；历史只能低保真回填，缺失字段不得显示为 0。 |
| Top20 | 每次写入截断 | 截断会永久丢计数；改为年度完整计数、展示时取前 20。 |
| 缓存上限 | “立即生效”与“下次写入才淘汰”混用 | 配置立即更新；已有条目在后续写入/加载/维护时收敛。 |
| 离线模式 | 收窄搜索 `sources` 被称为全局离线 | 本项只实现“搜索离线优先”；全局离线需传输层闸门，另立范围。 |

### 0.3 公共底座：歌曲来源身份与稳定键

#### 0.3.1 来源身份

`Song` 当前没有后端/服务器身份字段，不能仅凭 `Song` 构造跨后端稳定键。新增：

```kotlin
data class SongSourceIdentity(
    val kind: SongSourceKind,
    val backendType: String? = null,
    val serverId: String? = null,
    val platform: String? = null,
    val nativeId: String,
    val canonicalUri: String? = null,
)

enum class SongSourceKind { LOCAL, NAS, NETWORK }
```

- `serverId` 使用 `ServerConfig.id` 等稳定配置 ID，不使用显示名或可变的 base URL。
- 网络来源先按 `isNetworkSong` 判定，不能以 `networkPlatform != null` 作为前提。
- 本地 URI 保持大小写；只做协议和路径的规范化，不得直接 `lowercase()`。
- 没有来源身份的旧数据标记为 `UNBOUND`，不得生成用于 override、年度聚合或重复隐藏的持久化正式 key。

#### 0.3.2 稳定键契约

```kotlin
object SongKeys {
    const val SCHEMA_VERSION = 1

    fun stableKey(song: Song, source: SongSourceIdentity): String
    fun bindSource(song: Song, source: SongSourceIdentity): SongSourceIdentity?
}
```

规则：

```text
LOCAL   -> "v1:local:" + storageIdentity + ":" + canonicalUri
NAS     -> "v1:nas:" + serverId + ":" + backendType + ":" + nativeId
NETWORK -> "v1:net:" + sourceFamily + ":" + platform + ":" + nativeId
```

- `title`、`artist`、`album` 不参与正式 key。
- 后端原生 ID 仅作为请求参数，不能单独充当跨后端身份。
- key 升级必须提供迁移表；旧 override、resolution、年度计数不能因算法变化静默失效。

### 0.4 公共底座：播放事件与统计口径

新增统一事件模型，避免年度统计继续挂在播放历史记录上：

```kotlin
data class PlayStarted(
    val songKey: String,
    val startedAtMs: Long,
)

data class PlayEnded(
    val songKey: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationPlayedMs: Long,
)
```

- `playCount`、月度/每日播放次数消费 `PlayStarted`。
- 年度总时长、首末播放时间消费 `PlayEnded`。
- `startedAtMs` 必须来自真实播放启动点，不得在切歌补记时重新取当前时间。
- 年度、月度、每日、累计统计共享同一事件口径；`addPlayRecord` 仅负责历史明细，不再作为统计唯一入口。
- 系统时间调整、跨年长曲、时区变化必须纳入测试。

### 0.5 公共底座：后端能力与错误

拟新增能力声明，避免用“是否 override 默认方法”推断支持情况：

```kotlin
data class BackendCapabilities(
    val playlistRead: Boolean,
    val playlistCreate: Boolean,
    val playlistDelete: Boolean,
    val playlistAdd: Boolean,
    val playlistRemove: Boolean,
)

sealed interface BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>
    data class Failure(val error: BackendError) : BackendResult<Nothing>
}

enum class BackendError {
    UNSUPPORTED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    NETWORK, TIMEOUT, SERVER, PROTOCOL, UNKNOWN
}
```

- 现有 Boolean 接口可在适配器内部继续使用，但对外同步/控制层必须转换为结构化结果。
- 401/403/500/断网不得再被静默转成空歌单或普通 `false`。
- Subsonic 写歌单能力完成前，`playlistCreate/Add/Delete/Remove` 均为 false，UI 禁用推送。

### 0.6 通用代码锚点

| 用途 | 当前锚点 |
|---|---|
| 歌曲模型 | `data/model/Song.kt` |
| 服务器身份 | `data/model/ServerConfig.kt` |
| 应用偏好 | `data/prefs/AppPreferences.kt`，既有 `getInstance` 与 `NasMusicApp.appPreferences` 均可使用 |
| 播放管理 | `player/PlayerManager.kt` |
| 主/副播放器与 crossfade | `player/CrossfadeController.kt` |
| 媒体会话、通知、无 UI URL resolver | `player/PlaybackService.kt` |
| 歌单解析 | `backend/playlist/` |
| 后端接口 | `backend/BackendAdapter.kt` 与 `backend/impl/` |
| 搜索聚合 | `backend/SearchAggregator.kt`、`util/SearchQuery.kt`、`util/PinyinUtils.kt` |
| 本地索引 | `backend/local/` |
| 播放统计 | `data/stats/PlayStatsRepository.kt`、`data/stats/PlayStatsAggregator.kt` |
| 缓存 | `lyrics/LyricsPersistentCache.kt`、`backend/local/CoverUrlPersistentCache.kt`、`backend/network/NetworkMusicManager.kt` |

---

## 一、P0-1 响度归一（ReplayGain）与无缝播放

### 1.1 目标 / 非目标

- **目标**：同一队列跨来源、跨编码切歌时主观响度不跳变；支持且设备可解码的格式首尾无静音间隙。
- **非目标**：不提升音质、不做母带级动态处理、不在 ARMv7 老电视现场全曲解码计算增益、不替代 crossfade。
- **依赖**：R1（标签覆盖率与正增益策略）、R2（默认衔接模式）。

### 1.2 文件结构

| 文件 | 状态 | 职责 |
|---|---|---|
| `data/model/ReplayGainInfo.kt` | 新 | `ReplayGainSource`、`ReplayGainInfo`、`NONE` |
| `data/model/SeamlessMode.kt` | 新 | `OFF`、`GAPLESS`、`CROSSFADE` |
| `util/SongKeys.kt` | 新 | §0.3 的来源稳定键 |
| `backend/local/ReplayGainTagReader.kt` | 新 | 解析 ID3/FLAC/Vorbis comment/OpusTags 中的 gain 标签 |
| `backend/local/GaplessProbe.kt` | 新 | 判定 gapless 元数据与设备可解码性 |
| `player/ReplayGainController.kt` | 新 | 全仓唯一播放器音量出口，按 player/songKey 管理增益 |
| `player/SeamlessPolicy.kt` | 新 | 纯 JVM 三态策略与回落决策 |
| `player/ShortFadeIn.kt` | 新 | 单播放器毫秒级短淡入，不依赖 crossfade 副播放器 |
| `data/prefs/AppPreferences.kt` | 改 | ReplayGain 开关、增益缓存、衔接模式 |
| `player/PlayerManager.kt` | 改 | 注入 controller；队列变化/播放转换/重建时提交正确增益 |
| `player/CrossfadeController.kt` | 改 | 所有主/副播放器音量写入收编；显式接收两曲增益 |
| `ui/screens/settings/PlayerSettingsSection.kt` | 改 | 响度开关与三态芯片，移除/迁移旧 crossfade 双真相源 |
| `ui/viewmodel/PlayerViewModel.kt`、`SettingsBranch.kt`、`MainViewModel.kt` | 改 | 设置 Flow 与既有播放偏好的完整接线 |
| `res/values*/strings.xml` | 改 | 开关、三档标签、回退与格式不支持提示 |

### 1.3 标签读取与来源

```kotlin
data class AudioHeaderRequest(
    val song: Song,
    val source: SongSourceIdentity,
    val uri: String,
    val mimeOrExt: String?,
)

interface AudioHeaderSource {
    suspend fun open(request: AudioHeaderRequest): AudioHeaderResponse
}

data class AudioHeaderResponse(
    val stream: InputStream,
    val statusCode: Int?,
    val supportsRange: Boolean,
)
```

- 读取必须走现有认证 DataSource/`BackendAuthHeaders` 链路，不能直接用无认证 `URL.openStream()`。
- NAS 只接受 HTTP 206；非 206、超时、无 Range、超过读取预算均返回 `NONE`，不阻塞播放。
- 智能读取 ID3 头：按 tag 长度补读，设总字节上限；不能固定 256 KiB 后假定 TXXX 一定存在。
- 明确支持矩阵：ID3v2.3/2.4 TXXX、FLAC Vorbis comments、Ogg Vorbis comments、OpusTags；不支持的格式返回 `NONE`。
- 优先级：track gain → album gain；R128 与 ReplayGain 的换算规则写入单测。
- 所有 IO 在 `Dispatchers.IO`，读取失败只记录脱敏后的 songKey。

### 1.4 增益策略

数学换算：

```text
linearGain = 10^(dB / 20)
output = clamp(appVolume × linearGain × fadeFactor, 0, 1)
```

边界：

- NaN、无穷值、`|dB| > 24` 视为 `NONE`。
- `NONE` 是否仍乘全局 headroom 由 R1 明确；不得出现“所有无标签歌曲自动降低 1 dB”而未告知用户。
- 正增益不能靠 clamp 假装归一。R1 必须选择：
  1. 全局预留足够 headroom；
  2. 增加真正 limiter/DSP；
  3. 明确不支持正增益，只做衰减归一。

### 1.5 按播放器管理增益

```kotlin
class ReplayGainController(private val prefs: AppPreferences) {
    fun setEnabled(value: Boolean)
    suspend fun prepareFor(song: Song, source: SongSourceIdentity)
    fun commit(songKey: String, player: ExoPlayer, appVolume: Float)
    fun writeVolume(
        player: ExoPlayer,
        appVolume: Float,
        songKey: String,
        fadeFactor: Float = 1f,
    )
    fun gainFor(songKey: String): Float
}
```

- `prepareFor` 只预读，不修改当前播放器增益。
- `commit` 在媒体项真正成为当前项时调用。
- crossfade 主播放器使用当前曲 key，副播放器使用下一曲 key。
- seek、手动切歌、恢复队列、解析失败、player 重建后必须重新提交正确增益。
- 人声消除当前是 `replaceMediaItem`，不是重建 player；替换后明确继承原曲增益或重读伴奏标签。
- 全仓唯一允许写 `ExoPlayer.volume` 的位置是 `ReplayGainController`。

### 1.6 三态衔接

```kotlin
enum class SeamlessMode { OFF, GAPLESS, CROSSFADE }
```

- `OFF`：保持既有 crossfade 设置，语义名称建议改为“跟随原设置”，避免用户误解为关闭所有衔接。
- `CROSSFADE`：启用既有 crossfade，保留用户时长设置。
- `GAPLESS`：关闭 crossfade，依赖 Media3 原生 gapless。
- GAPLESS 不支持时：使用 `ShortFadeIn`，默认 200ms；不得启动 crossfade 副播放器。
- gapless 判定必须同时考虑：
  - 容器/元数据支持；
  - `DeviceAudioCaps.isFlacDecodeUnsafe()` 等设备熔断；
  - 当前来源是否可解析；
  - 下一项是否已完成缓冲。
- “网络流一律不支持”改为“当前版本只对可探测且可确认元数据的来源启用”；NAS HTTP 流与网络音乐不能仅按 `isNetworkSong` 混淆。

### 1.7 任务拆解

| 编号 | 内容 |
|---|---|
| A1 | `SongKeys`、model、DataStore 键、纯逻辑测试 |
| A2 | 标签读取、Range/认证/格式测试、覆盖率日志 |
| A3 | controller、全仓音量收编、per-player 增益 |
| A4 | `SeamlessPolicy`、`GaplessProbe`、`ShortFadeIn`、设置迁移 |
| A5 | K 歌、人声消除、通知/蓝牙音量、重建回归 |
| A6 | 出包与所有者听感验收 |

### 1.8 门禁与验收

- `ReplayGainMathTest`：`-12/-6/0/+6/NaN/+30 dB`、headroom 策略、appVolume/fadeFactor、无标签行为。
- `ReplayGainStateTest`：预读下一首不改变当前曲；crossfade 两曲增益独立；seek/重建/替换后状态正确。
- `ReplayGainGateTest`：扫描全部 `.kt` 的 `.volume =` 与 `.setVolume(`，唯一写入点允许存在；变异夹具必须判红。
- `GaplessPolicyTest`：MP3 Xing/LAME、无 Xing、FLAC、Opus、Vorbis、损坏头、网络来源、FLAC 老电视熔断。
- `SeamlessMutexTest`：GAPLESS 与 crossfade 不同时启用；unsupported 只走短淡入。
- `AudioHeaderSourceTest`：200/206、无 Range、认证头、超时、超限、取消。

所有者验收：

- ☐ 响度差明显曲目开关前后对比，正/负增益策略符合 R1。
- ☐ ARMv7 老电视无卡顿、音量突跳、解码熔断。
- ☐ OFF/CROSSFADE/GAPLESS 三态符合定义；不支持格式有日志且短淡入。
- ☐ 系统音量与 appVolume 关系明确；系统调音量不重置 appVolume。
- ☐ 人声消除/升降调/切歌后无静音或错误增益。

**回滚**：关闭 ReplayGain，衔接模式设为 OFF；新增 DataStore 键可保留不读。

---

## 二、P0-2 歌单同步、导出与导入

### 2.1 目标 / 非目标

- **目标**：本地歌单导出 M3U/JSON 并导入；NAS 歌单显式拉取到本地；本地歌单显式推送到 NAS。
- **非目标**：不做云账号、自动后台同步、新歌单数据库；不覆盖同名远端歌单；不承诺 M3U 保存 NAS 身份。
- **依赖**：R3（写权限、冲突与部分失败策略）。

### 2.2 文件结构

| 文件 | 状态 | 职责 |
|---|---|---|
| `backend/playlist/PlaylistExporter.kt` | 新 | SAF 写入 JSON/M3U、脱敏、错误分类 |
| `backend/playlist/M3uPlaylistSerializer.kt` | 新 | 可移植 M3U 序列化 |
| `backend/playlist/JsonPlaylistParser.kt` | 改 | 版本化全保真 JSON；旧版只读兼容 |
| `backend/playlist/BackendPlaylistSync.kt` | 新 | 拉取/推送、来源校验、能力检查、部分失败 |
| `data/model/PlaylistSongRef.kt` | 新 | 歌单条目的来源身份与稳定 key |
| `data/prefs/AppPreferences.kt` | 改 | 新歌单格式持久化与旧格式迁移；不新建数据库 |
| `ui/viewmodel/PlaylistSyncViewModel.kt` | 新 | 互斥操作、进度、结构化错误 |
| `ui/screens/settings/DataSettingsSection.kt` | 改 | 导出/拉取入口 |
| `ui/components/branches/MineBranch.kt` | 改 | 推送入口与能力禁用态 |

### 2.3 JSON 与 M3U 契约

#### JSON

- 新增 `formatVersion`，不覆盖旧版格式。
- 保存：stable key、来源身份、标题、艺术家、专辑、时长、曲目号、年份、流派、本地/网络标志、路径或可移植 URI。
- `streamUrl`、`coverUrl` 等易失效字段不保证恢复；凭据字段永不保存。
- 旧 JSON 继续可读；导入后标记为 `LEGACY_UNBOUND`，不能直接推回 NAS。

#### M3U

- 输出 `#EXTM3U`、`#EXTINF`、可移植 URI/路径。
- 含 token 的 URL 脱敏为占位符，并返回 `containsUrlPlaceholder=true`。
- 网络歌曲无 URL 时写注释标记，不写死过期直链。
- M3U 只承诺显示信息与可移植条目，不承诺 source identity 无损恢复。

### 2.4 同步映射

| 来源 | 推送 NAS | 拉取本地 | 导出 |
|---|---|---|---|
| NAS | 仅当 serverId/backendType 与当前 adapter 完全匹配时调用 `addToPlaylist` | 保存完整 `Song` 与来源身份 | JSON 保存身份；M3U 写脱敏 URL |
| 本地/USB | `LOCAL_FILE` 跳过 | — | 写原始路径/URI；不可移植时标记 |
| 网络/百度/Jamendo | `NETWORK_SONG` 跳过 | — | JSON 保存平台与 nativeId；M3U 写当前可用 URL 或需重解析标记 |
| 无稳定身份 | `NO_STABLE_ID` 跳过 | 标记为待补全 | 可导出显示信息，但不得声称可无损恢复 |

### 2.5 冲突、权限与部分失败

- 同名本地歌单：`原名 (导入 yyyyMMdd)`；如仍冲突，追加序号。
- 同名 NAS 歌单：默认不覆盖。若所有者选择询问模式，只能“改名/取消”，不得默认删除重建。
- 推送前检查 capability；不支持时 UI 禁用并说明。
- 创建远端歌单成功后逐曲添加，持久化 `remotePlaylistId + nextIndex`，支持失败续传。
- 添加失败不删除已创建歌单；返回逐曲结果，由用户决定重试或保留部分结果。
- 本地导入先完整解析并校验，再一次 DataStore edit 原子创建，避免半成品歌单。

### 2.6 任务与门禁

| 编号 | 内容 |
|---|---|
| B1 | JSON vNext、M3U serializer、golden 与旧格式兼容 |
| B2 | SAF 导出、脱敏、IO/权限错误分类 |
| B3 | 来源身份迁移、拉取、映射测试 |
| B4 | capability、推送、部分失败与续传 |
| B5 | ViewModel、两处 UI、中英文案 |
| B6 | 出包与所有者双端验收 |

门禁：

- `PlaylistJsonRoundTripTest`：真实 `Song` 全字段对账，仅 volatile URL 字段允许不同。
- `PlaylistM3uPortableTest`：中文、逗号、换行、空歌单、无 URL、token 脱敏。
- `PlaylistSourceIdentityTest`：两个服务器相同裸 ID 不碰撞；切换后端拒绝推送。
- `PlaylistMappingTest`：四类来源 × 三方向，所有跳过均有 reason。
- `BackendPlaylistErrorTest`：401/403/404/500/断网/unsupported 可区分。
- `PlaylistPartialPushTest`：创建成功、第二首失败、续传不重复添加。
- `PlaylistSyncConcurrencyTest`：并发 push 第二个立即 `BUSY`。

**回滚**：移除 UI 入口；旧歌单格式保持可读；新增数据不修改原歌单，误导入歌单用既有删除入口处理。

---

## 三、P0-3 外部控制补齐

### 3.1 目标 / 非目标

- **目标**：系统媒体卡片与桌面小组件可控制播放/暂停、上一首/下一首、播放模式、睡眠定时、喜欢。
- **非目标**：不重写媒体树、不引 Glance、不改 minSdk 22、不做锁屏歌词。
- **依赖**：R4（小组件范围与 TV 桌面能力）。

### 3.2 先决条件：统一外部控制状态源

新增 Application/Service 级 `ExternalPlaybackController`：

```kotlin
class ExternalPlaybackController {
    val state: StateFlow<ExternalControlState>
    suspend fun togglePlayPause()
    suspend fun next()
    suspend fun previous()
    suspend fun togglePlayMode()
    suspend fun cycleSleepTimer()
    suspend fun toggleFavorite(): BackendResult<Unit>
}
```

- 播放模式从 `PlayerViewModel` 迁到共享状态源；UI、媒体会话、小组件订阅同一状态。
- NAS 收藏复用既有 adapter 能力；网络收藏复用 `AppPreferences.toggleNetworkFavorite`，不得依赖 Activity ViewModel 存活。
- 命令返回真实结果：成功、无当前歌曲、未连接、无权限、网络失败。
- 定义冷启动语义：进程死亡后显示占位并禁用依赖歌曲的操作；若要求恢复队列，另做 Service 级启动协调器。

### 3.3 文件结构

| 文件 | 状态 | 职责 |
|---|---|---|
| `player/ExternalPlaybackController.kt` | 新 | 外部控制唯一状态与 mutation 入口 |
| `player/MediaSessionAccessPolicy.kt` | 改 | 新增 `ACTION_TOGGLE_FAVORITE` |
| `player/PlaybackService.kt` | 改 | custom layout、命令分发、通知与小组件刷新 |
| `appwidget/RemotePlaybackWidget.kt` | 新 | `AppWidgetProvider` |
| `appwidget/WidgetState.kt` | 新 | 轻量 snapshot 与 RemoteViews 构建 |
| `appwidget/WidgetUpdateThrottle.kt` | 新 | 刷新节流与零组件短路 |
| `AndroidManifest.xml` | 改 | 静态 widget receiver，仅 `APPWIDGET_UPDATE` |
| `res/xml/remote_playback_widget_info.xml` | 新 | 4×2 配置 |
| `res/layout/widget_remote_4x2.xml` | 新 | 4×2 布局 |
| `res/layout/widget_remote_2x2.xml` | 新 | 仅 R4 选 B 时实现 |

### 3.4 安全与兼容

- widget receiver 的 intent-filter 只允许 `APPWIDGET_UPDATE`。
- 自定义点击使用显式组件 PendingIntent；receiver 校验 action、extra、widget ID。
- API ≥23：`FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT`；API 22 只用 `FLAG_UPDATE_CURRENT`。
- 不在广播 extra 中传 token、服务器地址或可伪造的收藏目标。
- `RemoteViews` 不使用 `EditText`/`WebView`，不显示歌词、地址、token。
- 小组件只持久化歌曲 source key 与封面标识；Bitmap 异步加载，不做大对象持久化。
- 系统媒体卡片提供第三项 custom action，但不保证所有 SystemUI 都显示为独立按钮；需保留可达的通知/溢出入口。

### 3.5 刷新策略

- 播放状态、元数据、收藏变化立即刷新。
- 进度 tick 每 ≥1 秒最多刷新一次。
- 组件数为 0 时跳过全部刷新工作。
- 进程死亡、无当前歌曲、无封面均有占位状态。

### 3.6 任务与门禁

| 编号 | 内容 |
|---|---|
| C1 | 统一控制状态源、收藏真实结果 |
| C2 | 媒体卡片 action 与命令分发 |
| C3 | 4×2 小组件、Manifest、PendingIntent |
| C4 | 2×2（仅 R4-B） |
| C5 | 节流、冷启动、安全与 UI 验收 |

门禁：

- `ExternalControlStateTest`：无 Activity 时命令仍走统一状态源；失败不返回成功。
- `MediaControlGateTest`：action 常量、custom layout、命令分发成对出现；变异自证。
- `WidgetSecurityGateTest`：Manifest action 白名单、PendingIntent flags、RemoteViews 禁用视图。
- `WidgetStateTest`：长标题、无封面、null 歌曲、playing/favorite 四组合。
- `WidgetColdStartTest`：进程重建、无 MediaSession、无当前歌曲不崩溃。
- PendingIntent 实际投递测试，不只扫描 flags。

**回滚**：移除 Manifest receiver 与按钮；统一控制器可保留供媒体会话使用。

---

## 四、P1 语音点歌

### 4.1 入口路径

| 路径 | 做法 | 约束 |
|---|---|---|
| 手机遥控（条件具备时主路径） | H5 麦克风 → 浏览器识别 → `/api/search` | 必须 HTTPS 安全上下文；检查 API、权限与隐私说明 |
| 手机遥控（默认降级） | 系统键盘/输入法语音 → `/api/search` | HTTP 环境下默认可用，不依赖 Web Speech API |
| TV 系统语音 | `RecognizerIntent` → 搜索页 | 增加精确 `<queries>`；启动仍捕获不可用异常 |
| Android Auto/车机 | 既有 `MEDIA_PLAY_FROM_SEARCH` | 共享 normalizer 与搜索服务；车机保持自动播放语义 |

### 4.2 共享搜索服务

当前 `/api/search` 绕过 `SearchAggregator`，不能直接声称三端同口径。先新增应用级 `SharedSearchService`：

```kotlin
class SharedSearchService {
    suspend fun search(
        rawQuery: String,
        mode: FilterMode = FilterMode.PRECISE,
        recordHistory: Boolean = true,
        availableSources: Set<MusicSourceType>,
    ): SharedSearchResult
}
```

- `SearchViewModel`、`RemoteControlServer`、车机文本入口共用。
- 统一 source visibility、normalizer、聚合、PRECISE、拼音、去重与历史。
- H5 只负责传文本，不在前端另造搜索逻辑。

### 4.3 Normalizer

```kotlin
object VoiceQueryNormalizer {
    fun normalize(raw: String): String
    fun normalizeDetailed(raw: String): Result
    data class Result(val query: String, val matchedPrefix: String?, val raw: String)
}
```

必须先定义中文命令语法，再写 golden：

- `"播放周杰伦的稻香"` → `"周杰伦 稻香"`；
- `"我想听晴天"` → `"晴天"`；
- `"play 稻香"` → `"稻香"`；
- `"zjl dx"` 保持原样；
- 全角空格、标点、英文引号、多个前缀只去一次、80 字截断、空串。

禁止简单前后缀删除后声称支持“艺术家的歌曲”结构。

### 4.4 文件结构

| 文件 | 状态 | 职责 |
|---|---|---|
| `util/VoiceQueryNormalizer.kt` | 新 | 纯 JVM 命令文本规范化 |
| `backend/search/SharedSearchService.kt` | 新 | 三端共享搜索语义 |
| `player/PlaybackService.kt` | 改 | 车机入口接 normalizer/共享服务 |
| `ui/components/branches/LibraryBranch.kt` | 改 | TV 语音入口与能力探测 |
| `net/RemoteControlServer.kt`、`RemoteControlHtml.kt` | 改 | 共享搜索、HTTP 错误、语音降级 |
| `AndroidManifest.xml` | 改 | 仅新增语音 intent `<queries>`，不新增权限 |

### 4.5 错误、隐私与行为矩阵

- 空识别：不发起搜索，提示为空输入。
- 503：显示“搜索繁忙，请稍后重试”。
- 超时/500：与并发拒绝分开提示。
- H5 必须检查 `r.ok`，不能把错误 JSON 当无结果。
- Web Speech API 可能向浏览器厂商服务发送音频；界面需明确提示。不能宣称音频始终本地处理。
- TV 搜索页与手机遥控默认只展示结果；Android Auto 保持媒体搜索自动播放。
- 遥控日志对 token 与搜索文本脱敏。

### 4.6 任务拆解

| 编号 | 内容 |
|---|---|
| D1 | `VoiceQueryNormalizer` 与 golden |
| D2 | `SharedSearchService`、TV/遥控/车机等价接入 |
| D3 | 遥控 H5 安全上下文检测、语音降级与 HTTP 错误处理 |
| D4 | TV 语音图标、精确 `<queries>` 与异常降级 |
| D5 | 出包与所有者手机/TV 验收 |

### 4.7 门禁与验收

- `VoiceNormalizerTest`：至少 20 组固定输入输出。
- `SharedSearchEquivalenceTest`：同一文本在 TV、遥控、车机获得相同候选与排序。
- `VoiceSearchGateTest`：无 `RECORD_AUDIO`、无 `QUERY_ALL_PACKAGES`、有精确 query、异常降级存在。
- `RemoteSearchConcurrencyTest`：并发 3 次，第 3 次 503，不扩大并发槽。
- `RemoteHttpErrorTest`：503/500/超时/空结果 UI 状态正确。

所有者验收：

- ☐ HTTP 环境下系统输入法语音可用。
- ☐ HTTPS 具备时 H5 语音可用，拒绝权限/无 API 时降级。
- ☐ TV 无识别器时图标隐藏，键盘搜索可用。
- ☐ Android Auto 与手机遥控对同一 normalizer 结果一致。
- ☐ 权限列表无 `RECORD_AUDIO`，隐私提示可见。

**回滚**：隐藏 H5 麦克风与 TV 图标；共享 normalizer 不影响既有搜索。

---

## 五、P2-1 元数据整理与重复项处理

### 5.1 范围与原则

- 覆盖层默认覆盖本地 + NAS 的**本地展示**，不写回 MediaStore/NAS（R6 待最终确认）。
- 重复项只隐藏与统计别名，不删除文件、索引行或歌单条目。
- 不修改 Room schema；所有修改可逆。
- 必须先完成 §0.3 稳定键，再启用 override/resolution。

### 5.2 集中式展示读取模型

仅在 `LocalMusicRepository`/`MusicMerger` 注入不足以覆盖搜索、收藏、历史和遥控。新增：

```kotlin
class SongReadModel(
    private val overrides: Flow<Map<String, MetadataOverride>>,
    private val resolutions: Flow<Map<String, DuplicateResolution>>,
) {
    fun apply(song: Song, source: SongSourceIdentity): Song?
    fun applyAll(songs: List<Song>): List<Song>
}
```

要求：

- 曲库、搜索、发现、详情、收藏、历史、歌单展示、队列物化、遥控 API 统一经过该层。
- override 变化触发相关 Flow 失效。
- Room SQL 预筛选不能漏掉“override 后标题可命中”的记录；必要时先取候选，再在展示层过滤。
- 已存在队列/歌单中的隐藏歌曲默认仍可播放，但不改写其持久化 JSON。
- NAS 来源未绑定时不生成 override key。

### 5.3 数据模型

```kotlin
data class MetadataOverride(
    val stableKey: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverRef: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class DuplicateResolution(
    val keepKey: String,
    val hideKeys: Set<String>,
    val createdAtMs: Long = System.currentTimeMillis(),
)
```

- `null` 表示不覆盖；空串表示显式清空。
- `coverRef` 使用统一封面引用，不把本地路径与 NAS URL 混为一谈。
- DataStore JSON 增加 `schemaVersion`、逐条校验、坏记录隔离；解析失败不得静默当成用户清空。

### 5.4 重复检测

评分总分 8、阈值 7：

| 条件 | 分数 |
|---|---:|
| 规范化标题 + 艺术家一致 | +4 |
| 时长差 ≤2000ms，且两边时长有效 | +2 |
| 同专辑，且非空 | +1 |
| 同来源平台 | +1 |

- 同标题不同艺术家：不得仅靠标题进入候选。
- 同标题同艺术家但专辑/时长不同：按完整 fixture 固定期望，不写死“一定 4 分”。
- 不同音质版本可进入候选，但必须用户确认。
- 排序固定为分数降序、稳定 key 升序。

### 5.5 合并语义

1. 写入 `DuplicateResolution(keepKey=A, hideKeys={B})`。
2. B 从展示列表隐藏，仍保留在 Room、歌单、下载记录中。
3. 累计、月度、每日统计读取时统一应用 alias，再使用 canonical metadata 聚合。
4. 定义 alias 链、环、canonical 不存在时的回退；总播放次数必须守恒。
5. 恢复时删除 resolution，隐藏与别名同时失效。

### 5.6 文件与任务

| 文件 | 职责 |
|---|---|
| `data/model/MetadataOverride.kt`、`DuplicateResolution.kt` | 模型 |
| `backend/local/SongReadModel.kt` | override/隐藏唯一展示入口 |
| `backend/local/DuplicateDetector.kt` | 打分与候选 |
| `data/stats/StatsAliasResolver.kt` | 统计读取期别名 |
| `data/prefs/AppPreferences.kt` | schemaVersion JSON 持久化 |
| `ui/screens/library/MetadataCleanupScreen.kt` | 编辑与重复候选 |
| `ui/components/branches/MineBranch.kt` | 整理入口 |

任务：E1 模型与存储 → E2 统一读取模型 → E3 重复检测 → E4 统计 alias 与恢复 → E5 备份兼容与验收。

### 5.7 门禁与验收

- `MetadataOverrideTest`：null/空串、三类来源、不可变性、坏 JSON、旧 schema。
- `SongReadModelCoverageTest`：参数化枚举曲库、搜索、收藏、历史、歌单、队列、遥控入口。
- `DuplicateMergeTest`：正负各 10 组完整 fixture；阈值与排序固定。
- `StatsAliasTest`：环、链、缺失 canonical、总数守恒、恢复。
- `MetadataOverlayGateTest`：无 DAO UPDATE、无 MediaStore 写入、无文件删除。

**备份**：新增字段进入 `BackupData`，明确“合并恢复/替换恢复”；旧备份缺字段按空处理，空 map 的语义写入测试。

**回滚**：清空两个 DataStore 键；无文件、索引、NAS 副作用。

---

## 六、P2-2 年度听歌报告（仅数据页）

### 6.1 已裁定范围

- 只做年度数据页面，年份可选。
- 不完整年份照常展示并注明。
- 不生成 PNG、不分享、不写下载目录、不新增 FileProvider。
- 零文件导出、零网络请求。

### 6.2 数据模型与事件

```kotlin
data class YearlyStats(
    val year: Int,
    val totalPlayMs: Long? = null,
    val playCount: Int? = null,
    val activeDays: Int? = null,
    val topSongs: List<SongCount> = emptyList(),
    val topArtists: List<NameCount> = emptyList(),
    val firstPlayAtMs: Long? = null,
    val lastPlayAtMs: Long? = null,
    val complete: Boolean,
    val coverageStartMs: Long? = null,
    val coverageEndMs: Long? = null,
    val schemaVersion: Int = 1,
)
```

- `null` 表示历史数据无法恢复，UI 显示“—”，不得显示 0。
- 消费 §0.4 的 `PlayStarted`/`PlayEnded`，不挂 `addPlayRecord`。
- 年份按事件发生时的设备本地时区计算。
- 当前进行中年份 `complete=false`，但正常展示。

### 6.3 存储与精确 Top20

- 每年一个 DataStore key，另存年份索引；避免每次播放重写所有年份。
- 保存年度完整歌曲/歌手计数，**不在写入时截断 Top20**。
- 展示时排序：次数降序、稳定 key/规范化名称升序，取前 20。
- 单年设置明确容量预算；超过预算时记录错误并保留原数据，不得静默截断或覆盖。
- 年度数据进入备份；“清空播放记录”是否清年度汇总必须单独明确并在 UI 说明。

### 6.4 历史回填

回填只使用仍存在的 daily/monthly 桶：

- 可从月度桶计算的播放次数填入，并标记来源；
- 可从 daily 桶计算的活跃天数填入；
- 总时长、首末时间、歌手榜等不可恢复字段保持 `null`；
- 不从 daily 与 monthly 相加，避免同一播放重复计数；
- 回填使用独立 migration 标记，并在同一 `dataStore.edit` 中合并旧年度数据与标记；
- 实时写入与首次回填并发时不得重复计数。

所有回填年 `complete=false`，UI 提示：“该年数据不完整，仅恢复现存历史桶可支持的统计。”

### 6.5 UI

- `PlayStatsScreen` 增加“年度报告”入口，并确保小屏可滚动、焦点可达。
- `YearlyReportScreen`：年份芯片倒序、总时长、播放次数、活跃天数、Top5 歌曲、Top5 歌手、首次/末次时间。
- 当前年即使只有 1 条数据也显示。
- 不完整年份不过滤、不置灰。
- 页面明确进行中、历史回填、部分覆盖三种状态。

### 6.6 任务与门禁

| 编号 | 内容 |
|---|---|
| F1 | 事件模型接入、年度存储、原子写入 |
| F2 | 低保真回填、migration 标记、并发测试 |
| F3 | 年度页、ViewModel、年份芯片、中英文案 |
| F4 | 出包与所有者验收 |

门禁：

- `YearlyStatsRollupTest`：跨年长曲、闰年、DST、时区、Top20 排序、播放次数守恒。
- `YearlyBackfillTest`：缺失字段为 null、幂等、并发回填、损坏桶隔离。
- `YearlyReportUiTest`：年份倒序、切换、不完整提示、当前年单条数据、滚动与焦点。
- `YearlyAtomicityGateTest`：统计事件与年度写入同事务；逻辑异常不得静默部分成功。
- `NoExportGateTest`：仅扫描年度 Screen/ViewModel 及直接依赖，禁止 Bitmap 导出、MediaStore、Download、FileProvider；先剥注释并用变异夹具自证。

**回滚**：隐藏入口；年度数据保留，不影响播放。

---

## 七、P2-3 弱网离线策略与缓存数量上限

### 7.1 范围

- 三个缓存数量上限逐项直接配置。
- 播放链接到期前纯预刷。
- “离线优先”本版本仅指**搜索离线优先**，不是全局断网模式。
- 不做全量离线下载，不新增音乐源，不改下载字节配额。

### 7.2 纯预刷 API

```kotlin
object PlayUrlPrefetchScheduler {
    const val TTL_MS = 5 * 60_000L
    const val REFRESH_WINDOW_MS = 60_000L
    const val MAX_RETRY = 3
    const val TICK_MS = 5_000L

    fun shouldRefresh(cachedAtMs: Long, nowMs: Long): Boolean
    fun nextDelayMs(attempt: Int): Long
    fun targets(queueSize: Int, currentIndex: Int): List<Int>
}
```

`NetworkMusicManager` 新增：

```kotlin
suspend fun prefetchPlayUrl(song: Song): PrefetchResult
```

要求：

- 只更新 URL 缓存，不调用任何播放/切歌 API。
- 队列回写校验 index、stableKey、queue generation。
- 每 key single-flight；tick 不重叠。
- 只在 `STATE_PLAYING` 且网络可用时运行；pause/buffering/idle/destroy 取消。
- 新增 Service 级调度器；当前 `PlaybackService` 没有可直接复用的秒级 tick。
- 网络监听不能依赖 Activity 存活；`NET_CAPABILITY_INTERNET` 只表示能力，不等于互联网可达。

### 7.3 保留旧 URL 的刷新语义

当前 `forceRefresh=true` 会先删除旧值，不能直接用于预刷。新增独立刷新流程：

1. 读取旧 `CachedPlayUrl`；
2. 请求完成前不删除旧值；
3. 成功替换；
4. 失败且旧值未过期则继续返回旧值；
5. 旧值确实过期后返回 `RETRYABLE_OFFLINE` 或 `EXPIRED_URL`。

新增可操作的解析失败停留态，由 Service 通知 UI；不得复用会自动跳歌的 `resolveAndPlayByIndex`。

### 7.4 缓存数量上限

```kotlin
enum class CacheLimitKind { LYRICS_ENTRIES, COVER_URL_ENTRIES, PLAY_URL_ENTRIES }

data class CacheLimitsSnapshot(
    val lyricsMaxEntries: Int = 2_000,
    val coverUrlMaxEntries: Int = 10_000,
    val playUrlMaxEntries: Int = 500,
)

object CacheLimits {
    val RANGES = mapOf(
        CacheLimitKind.LYRICS_ENTRIES to 100..20_000,
        CacheLimitKind.COVER_URL_ENTRIES to 100..50_000,
        CacheLimitKind.PLAY_URL_ENTRIES to 50..5_000,
    )

    fun current(): CacheLimitsSnapshot
    suspend fun loadFrom(prefs: AppPreferences)
    suspend fun set(prefs: AppPreferences, kind: CacheLimitKind, value: Int): Result<Unit>
    fun clamp(kind: CacheLimitKind, value: Int): Int
}
```

- 内存快照由 `NasMusicApp` 启动加载，设置保存后更新；淘汰路径零 IO。
- UI 输入非整数或越界：提示允许范围，不写 DataStore。
- `clamp` 仅用于防御性读取/迁移，不用于悄悄修正用户输入。
- 配置立即更新；已有条目在后续写入、加载或显式维护时收敛。
- 歌词保持 LRU；封面保持 FIFO-by-write；播放 URL/deadProbe 保持时间顺序。
- `playUrlCache` 与 `deadProbeCache` 共用上限。
- 目录型缓存仍按字节清点，不改为数量上限。

### 7.5 搜索离线优先

```kotlin
enum class OfflinePolicy { OFF, LOCAL_ONLY }

fun effectiveSources(
    policy: OfflinePolicy,
    enabled: Boolean,
    allSources: Set<MusicSourceType>,
): Set<MusicSourceType>
```

- `OFF` 返回全部来源。
- `LOCAL_ONLY` 只保留本地/已下载来源。
- 仅在共享搜索调用点收窄 `sources`，不删除聚合器零命中回退代码。
- 验收只承诺跨源搜索不发网络请求；播放、歌词、封面、收藏等不在本版本全局离线范围。

### 7.6 文件、任务与门禁

| 文件 | 职责 |
|---|---|
| `player/PlayUrlPrefetchScheduler.kt` | 纯调度策略 |
| `player/PlaybackService.kt` | Service 级 tick、取消、失败事件 |
| `backend/network/NetworkMusicManager.kt` | 纯预刷、保留旧值刷新 |
| `data/cache/CacheLimits.kt` | 三项数量上限快照 |
| 三个缓存类 | 淘汰阈值读取快照 |
| `ui/screens/settings/CacheSettingsSection.kt` | 三项输入与离线开关 |
| `ui/screens/TextInputDialog.kt` | 数字键盘、错误文本、设置场景关闭二维码 |

任务：G1 调度器 → G2 纯预刷接入 → G3 缓存上限 → G4 搜索离线 → G5 弱网验收。

门禁：

- `PlayUrlPrefetchTest`：TTL 边界、暂停、无网络、重试、targets 不越界。
- `PrefetchNoPlaybackSideEffectTest`：预刷前后 index、position、play/pause 完全不变。
- `PrefetchSourceGateTest`：调度器不得引用 `builtinStreamUrlResolver`、`replayAt`、`resolveAndPlayByIndex`、`playQueue`、`onNeedResolveStreamUrl`。
- `CacheLimitsTest`：默认值、范围、非法输入、保存后快照更新、三种淘汰语义。
- `CacheLimitsGateTest`：三个读取点全部替换，加载/写入/维护路径一致；变异自证。
- `CacheLimitUiTest`：合法落盘、非法提示且不落盘、重启保留。
- `OfflineModeGateTest`：真值表、sources 收窄、默认回退代码仍存在。
- `WeakNetworkIntegrationTest`：20 分钟队列、旧 URL 保留、3 次失败后停留提示；按稳定 key 统计 single-flight 与实际尝试次数。

**回滚**：关闭搜索离线与预刷；缓存上限恢复默认；不删除缓存数据。

---

## 八、实施顺序与最小闭环

| 顺序 | 功能 | 任务 | 前置 |
|---|---|---|---|
| 0 | 公共底座 | 来源身份、稳定键、播放事件、后端错误模型 | 无 |
| 1 | ReplayGain + gapless | A1–A6 | R1、R2 |
| 2 | 歌单同步与导出 | B1–B6 | R3 |
| 3 | 外部控制补齐 | C1–C5 | R4 |
| 4 | 语音点歌 | D1–D5 | R5 |
| 5 | 元数据整理 | E1–E5 | R6 |
| 6 | 年度报告 | F1–F4 | R7 已裁定 |
| 7 | 弱网与缓存 | G1–G5 | R8 已裁定 |

每项最小闭环：

```text
实现 → 行为测试/门禁全绿 → 版本号 + CHANGELOG + technical-overview §10
→ docs 两分支一致 → assembleRelease → output/apk + 指纹 → 所有者实机验收
```

版本仍以 `2.39.28 / 213` 为基线；每项独立版本或合并发版由所有者确认。

---

## 九、八个评审问题（R1–R8）

> R1–R6 未裁定前，只允许做不改变默认行为、不接真实播放/后端/UI 的纯逻辑与格式准备。R7、R8 已裁定。

### R1 — ReplayGain 覆盖率与正增益策略

- **选项 A（推荐）**：只消费已有标签，不后台计算；正增益统一预留足够 headroom，保证全队列可衰减到统一目标。
- **选项 B**：只消费已有标签；不支持正增益，超过 0 dB 的标签按 0 dB 处理并记录。
- **选项 C**：引入 limiter/DSP 支持正增益；增加音量链路复杂度与真机验证。
- **选项 D**：后续充电 + Wi-Fi + 空闲时后台计算，独立版本。
- **推荐**：A 或 B，先获取 A2 覆盖率再决定 D。
- **未裁定默认**：只做标签读取与纯数学测试，不接默认播放音量。
- **实施口径（2026-11-28）**：采用 B——只消费标签，正增益统一保留在 0 dB（全局余量），并记录一次告警；D 不做。
- **结论**：☐ A　☑ B　☐ C　☐ D　☐ 其他：________

### R2 — 默认衔接模式

- **A（推荐）**：默认 OFF/跟随原设置，ReplayGain 默认关闭；用户手动开启。
- **B**：默认 CROSSFADE。
- **C**：默认 GAPLESS，不支持时 200ms 短淡入。
- **推荐**：A。
- **未裁定默认**：不改变既有 crossfade 默认值。
- **实施口径（2026-11-28）**：采用 A——ReplayGain 与无缝模式默认关闭，沿用既有 crossfade 默认值。
- **结论**：☑ A　☐ B　☐ C　☐ 其他：________

### R3 — NAS 写权限、同名冲突与部分失败

- **A（推荐）**：同名一律改名；创建成功后部分添加失败保留远端歌单并支持续传。
- **B**：每次询问改名/取消，不提供覆盖。
- **C**：允许覆盖，但必须二次确认；不得默认删除重建。
- **推荐**：A。
- **未裁定默认**：导出可做；推送默认不覆盖。
- **实施口径（2026-11-28）**：采用 A——同名冲突返回冲突且不覆盖，部分失败保留检查点并可续传。
- **结论**：☑ A　☐ B　☐ C　☐ 其他：________

### R4 — 小组件范围

- **A（推荐）**：手机 4×2；TV 不做 2×2。
- **B**：手机 4×2 + TV 2×2，需所有者确认启动器支持。
- **C**：只做系统媒体卡片，不做小组件。
- **推荐**：A。
- **未裁定默认**：C1/C2 可先做；小组件等裁定。
- **实施口径（2026-11-28）**：采用 A——手机 4×2 小组件；TV 不做 2×2。
- **结论**：☑ A　☐ B　☐ C　☐ 其他：________

### R5 — 语音入口与录音权限

- **A（推荐）**：手机输入法语音 + 条件具备时 H5 语音；TV 系统语音；App 零录音权限。
- **B**：只做手机输入法语音与遥控文本，不做 TV/H5 麦克风。
- **C**：App 内申请 `RECORD_AUDIO` 并接入识别；需显式推翻权限瘦身裁定，单独评审。
- **推荐**：A；不建议 C。
- **未裁定默认**：只做 normalizer 与共享搜索准备，不新增入口。
- **实施口径（2026-11-28）**：采用「未裁定默认」——完成 normalizer 与共享搜索，未新增录音入口与权限。
- **结论**：☐ A　☐ B　☐ C　☑ 其他：不新增入口（未裁定默认）　________

### R6 — 元数据覆盖范围

- **A（推荐）**：覆盖本地 + NAS 的本地展示，不写回；重复项只隐藏 + 统计别名。
- **B**：只覆盖本地；NAS 只读原样。
- **C**：允许写回 NAS 或物理删除文件；高风险，不在本方案实施。
- **推荐**：A；不建议 C。
- **未裁定默认**：只做模型、稳定键与纯读取层测试，不接 UI。
- **实施口径（2026-11-28）**：采用 A——覆盖本地与 NAS 的本地展示，不写回；重复项只隐藏并做统计别名。
- **结论**：☑ A　☐ B　☐ C　☐ 其他：________

### R7 — 年度报告呈现形式 ✅ 已裁定

- **所有者裁定（2026-11-28）**：只统计年数据并做页面展示；年份可选；不完整年份可展示；不生成 PNG。
- **落地**：F3 纯数据页；F4 纯出包验收；禁止图片导出、MediaStore、FileProvider、下载目录写入。
- **决策留痕**：原 PNG 保存、PNG + 分享、隐藏不完整年份方案均不采纳。

### R8 — 缓存上限配置方式 ✅ 已裁定

- **所有者裁定（2026-11-28）**：直接配置各缓存数量上限；不做三档、不做 MB 总预算。
- **默认**：歌词 2000、封面 URL 10000、播放链接 500。
- **范围**：歌词 100–20000、封面 100–50000、播放链接 50–5000。
- **落地**：三项 `SettingActionButton` + 数字输入；内存快照；淘汰路径零 IO。
- **决策留痕**：三档配额、并入音乐源开关、只做链接预刷均不采纳。

---

## 十、冻结项与开工条件

1. **公共底座先冻结**：`SongSourceIdentity`、`SongKeys`、播放事件、后端 capability/error model。
2. **默认行为需裁定**：R1–R6 对应功能未裁定前，不修改默认音量、衔接、Manifest 权限、设置默认值、Room schema 或文件删除逻辑。
3. **可先行纯逻辑**：
   - A1/A2 的 model、稳定键、标签解析与数学测试；
   - B1 的 JSON/M3U 格式与 golden；
   - D1 的 normalizer 与共享搜索接口准备；
   - E1 的 override/resolution 模型与坏 JSON 测试；
   - F1/F2 的播放事件、年度 schema 与回填纯函数；
   - G1 的预刷调度器与 fake clock 测试。
4. **禁止先行接线**：在公共底座与对应 R 项完成前，不把上述代码接入真实播放器、后端写操作、媒体会话、搜索 UI 或设置默认值。
5. **门禁先行**：行为测试与负向变异夹具先于实现；不得用固定“5 个既有红门禁”作为长期豁免。
6. **文档联动**：实现后更新本文状态，并在 `docs/technical-overview.md` §10 记录已验证改动；文档提交后运行 parity 检查。

---

## 十一、实施记录（2026-11-28 · v2.39.29）

> 本节只记录落地结果与门禁，不重写上文方案。

| 功能 | 落地内容 | 关键门禁 |
|---|---|---|
| P0-1 响度归一 / 无缝播放 | `ReplayGainTagReader`（ID3v2.3/2.4 TXXX、FLAC/Ogg Vorbis）、`ReplayGainController`（唯一 `player.volume` 出口、逐播放器代次）、`GaplessProbe`/`GaplessProber`、`ShortFadeIn`、`SeamlessPolicy` | `ReplayGainTagReaderTest`、`ReplayGainMathTest`、`ReplayGainStateTest`、`ReplayGainGateTest`、`GaplessPolicyTest`、`SeamlessMutexTest`、`AudioHeaderSourceTest` |
| P0-2 歌单同步 / 导出 | JSON v3 与可移植 M3U、`PlaylistImporter`/`PlaylistExporter`、来源身份去重、`BackendPlaylistSync` 检查点续传 | `Playlist*Test`（JSON/M3U/导入/导出/冲突/部分推送到续传/并发） |
| P0-3 外部控制 | `ExternalPlaybackController` 统一播放模式与收藏状态、`MediaSessionAccessPolicy` 命令、4×2 小组件与刷新节流 | `ExternalPlaybackControllerTest`、`Widget*Test`、`MediaSessionAccessPolicyTest` |
| P1 语音点歌 / 共享搜索 | `VoiceQueryNormalizer` 统一规范化、`SharedSearchService` 供界面/遥控/语音共用 | `VoiceQueryNormalizerTest`、`SharedSearchEquivalenceTest`、`VoiceSearchGateTest`、`RemoteHttpErrorTest` |
| P2-1 元数据整理 | `MetadataOverride`/`DuplicateResolution`、`SongReadModel` 统一展示读取、`DuplicateDetector`、`StatsAliasResolver`、整理页 | `MetadataOverrideTest`、`SongReadModelTest`、`SongReadModelCoverageTest`、`DuplicateMergeTest`、`StatsAliasTest`、`MetadataOverlayGateTest` |
| P2-2 年度报告 | 播放开始/结束事件、`YearlyStats` 完整计数与展示前 20、历史回填、年份可选数据页 | `YearlyStatsRollupTest`、`YearlyBackfillTest`、`YearlyReportUiTest` |
| P2-3 弱网策略 | `PlayUrlPrefetchScheduler` 无播放副作用预刷、可操作解析失败停留态、`CacheLimits` 三项上限与设置入口 | `PlayUrlPrefetchTest`、`PlayUrlPrefetchSchedulerTest`、`PrefetchSourceGateTest`、`CacheLimitsTest`、`CacheLimitsGateTest`、`CacheLimitUiTest` |

- **默认值**：ReplayGain 与无缝模式默认关闭；缓存上限默认歌词 2000、封面 URL 10000、播放链接 500。
- **兼容**：未新增 `RECORD_AUDIO`、媒体库权限或第三方 SDK；小组件 PendingIntent 在 API 22 走 `FLAG_UPDATE_CURRENT`，M 以上追加 `FLAG_IMMUTABLE`。
- **版本**：`versionCode = 214` / `versionName = "2.39.29"`；CHANGELOG 与本表同步。
- **验收边界**：编译、单元测试、lint 与本地 release 出包由本机完成；电视上的画面、遥控与听感由所有者执行。
