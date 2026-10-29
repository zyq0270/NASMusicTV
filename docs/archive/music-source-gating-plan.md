# 音乐源开关全覆盖 · 开发方案

> 状态：**评审通过（第二轮）· 待实施** ｜ 起草：2026-10-28 ｜ 修订：2026-10-28（v3，二轮审阅修订）｜ 基线版本：v2.39.8
> 需求原文（用户 2026-10-28）：**「各个音乐源的开关应该覆盖所有的能够访问音乐的地方，包括专辑、艺术家、发现、搜索等曲库内所有的内容，还包括首页中的各个音乐内容。只要关掉某个音乐来源，那么这些地方的音乐就不能列出这个音乐来源的内容。」**

> ## v3 修订说明（第二轮审阅后）
>
> 本轮把二轮审阅的 9 条修订全部落地。三项 Critical 均为同一类毛病：**「点名了机制的作用点，却没给机制本身或触发路径」**。核心动作是**把所有合成点收敛到 VM 侧**。
>
> | # | 修订 | 关闭的问题 |
> |---|---|---|
> | 1 | **删除 UI 层合成点 G1b**：`LibraryBranch.kt:61-71` 的 `remember(songsPaging, localSongsList, baiduSongsList)` 在开关翻转时**三个键都不变 ⇒ 永不重算**。改为：VM 派生 `visibleSongsPaging: StateFlow<SongsPaging>`（`combine` + `sourceRevision` 信号），`LibraryBranch` 退化为透传 | N1 |
> | 2 | **§3.5 关源分支补齐触发路径**：v2 的注释「无合成点的流由各 `load*` 短路负责清空」是**死代码**（`loadGenres` 仅 `:1551` 被调、`loadRandomSongs(adapter)` 仅 `:1562`/`:1725`、`loadYears`/`loadPlaylists` 只有 UI `retry` 触发）。改为：关源分支显式调用 `clearSourceScopedCaches(source)` | N2 |
> | 3 | **重开分支补 `loadYears()` / `loadPlaylists()` / `loadRandomSongs()`**：v2 的「无需额外动作」对 Meting 不成立（随心听网络段不会恢复） | M-C |
> | 4 | **`_favoriteSongs` 改归「列表流」**：它**没有任何统计消费者**，v2 归为「库存流（S2 统计读它们）」是误分类；`MineBranch.kt:34/48` 直读原始流 → 「我的」页泄漏 NAS 收藏。改为采集期置空 | N3 |
> | 5 | **§3.2 新增 3 项**：`LibraryScreen.playAllSongs`（`:436-457`）与 `AlbumsTab`（`:773`）直读原始 `_songs`；`artistSongsMap`（`:1467,1480`）关源后残留旧 NAS 歌；封面解析的百度输入 | M-A |
> | 6 | **§3.2 #19 写明 SmartRadioManager 的可见性注入方式**：构造期注入 `nasMusicEnabled: () -> Boolean` provider lambda（与 `SearchAggregator` 同形） | M-B |
> | 7 | **§3.3 处置表补齐**：`artistSongsMap` / `_baiduIndexedSongs` / `_favoriteIds`；**修正 `_genres`/`_years` 分类**（二者**有**合成点 `mergedGenreList`/`mergedYearsList`，v2 误标为「无合成点」） | Minor 1–4 |
> | 8 | **§4 拆相 + 重估**：P1 拆 P1a/P1b、P2 拆 P2a/P2b、P6 上调（需**新建** fake `BackendAdapter` 与 `MainViewModel` 测试宿主，仓库里两者都不存在）。**总预估 6.5–7 人日** | M-D |
> | 9 | **§7 补 V17/V18**；§7.2 行为测试收敛为三条不变量并明示新增测试基建 | M-D |
>
> **合成点数：4 处（v2 为 5 处），且全部位于 VM 侧**——不再有 UI 层合成点。

---

## 0. 结论先行

1. **现状是「覆盖约三成」**，且**漏点比直觉的多一处**。真相是缺口分**三类**，不是两类：
    - **① 走 `NetworkMusicManager` 的路径 —— 已受控**：关源调 `unregisterService`，`services` map 里没这个 key，查询侧自然拿不到。✅
    - **② `SearchAggregator` 的百度 / Jamendo 分支 —— 未受控（最容易被漏，因为它「看起来受 service 控制」）**：聚合器在 `NasMusicApp:655-666` 被**直接注入实例**（`baiduService = baiduNetdiskService`、`jamendoService = jamendoService`），分支判据是 `baiduService != null` / `jamendoService != null` —— **对象非空恒真，与 services map 完全无关**。⇒ 关掉百度后，**曲库搜索照样返回百度结果**。
    - **③ 非 service 的本地索引与直连调用 —— 未受控**：NAS 走 `backendRegistry.getAdapter()`；百度走 `baiduIndexCache`；Jamendo 有 `loadJamendoHot` / `loadJamendoTag` / `searchJamendo` 直接调 `nasMusicApp.jamendoService`。
2. **本轮 NAS 开关（v2.39.8）只覆盖了聚合搜索的 NAS 分支**：`loadLibrary` / `loadArtists` / `loadYears` / `loadGenres` / `loadFavorites` / `loadPlaylists` / `loadRandomSongs` / `loadHomeDashboard` / 详情页 / 远程搜索**全都没有判**。
3. **NAS 与百度各有一条「数据不动、只有 UI 在动」的旁路**：关源后已加载的缓存态（`_albums` / `_artists` / `_songsPaging` 等）仍会被 UI 读到 —— 所以**只做「采集期短路」远远不够**，必须配合成期过滤（§3.3）。
4. **修法不是「在几十个调用点各加一个 if」**，而是**把「某个源此刻是否可见」收敛为唯一真相源，并在数据合成处统一过滤**。所有列表的源头都会汇进少数几个合成点（VM 侧的曲库合成、首页/电台合成、`SearchAggregator` 的合并、`NetworkMusicManager` 的 services map），**拦在合成点上游**既省事又不会漏。
5. **分层结论**：**① 采集期不请求**（关掉的源连数据都不拉）＋ **② 合成期再过滤**（防止缓存里的旧数据、以及关闭瞬间已发出的在途请求结果漏进 UI）。两层都要，只有一层都不够。
6. **核心契约（本方案的地基，见 §3.0）**：**原始 StateFlow = 库存**（S2 统计永远读它们，永不因关源清空）；**派生/合成数据 = 可见集**（S1，在每个合成点按 `SourceVisibility` 过滤）。例外：**只服务于单一源、无统计消费者的流**（`_genres`/`_years`/`_playlists`/`_favoriteSongs`）在关源时**按源清空**（§3.2.2），因为它们没有可用的合成点。
7. **合成点全部在 VM 侧**（v3 的关键结构改动）：v2 把歌曲合并钉在 UI 层 `LibraryBranch.kt:61-71`，但那个 `remember` 的三个键在开关翻转时都不变 ⇒ 永不重算。v3 改为 **VM 派生 `visibleSongsPaging`**，UI 只做透传。

---

## 1. 先定义「关掉」到底意味着什么

需求里「不能列出」是明确的，但有几处边界必须写死，否则实施时每个人理解不同。

### 1.1 四条语义（本方案的判据）

| # | 语义 | 说明 |
|---|---|---|
| S1 | **列表/网格/轮播里不出现** | 首页各区块、曲库七个 TAB、搜索结果、发现页、详情页的曲目列表、智能电台批次、「我的」页收藏，全部适用 |
| S2 | **统计数字不因关源而变** | 首页「专辑 N / 歌曲 N / 艺术家 N / 歌单 N」展示的是**曲库存量**（历史事实），⛔ **不随源开关扣减**。关源只影响「列出哪些条目」（S1），不改写「库里有这么多」。统计数字**永远读原始库存流**（§3.0 契约），因此天然不受过滤影响 |
| S3 | **已存在条目不删除** | 收藏、最近播放、歌单、新建歌单里的条目**不从数据库删**，只是「不列出」；重开开关即恢复。这是有意的：关源是显示开关，不是数据销毁 |
| S4 | **播放不因关源而崩溃** | 队列里已有该源的歌继续播（用户主动播放过）；但**新发起的「播放全部 / 随机」不得再取该源**（含 `LibraryScreen` 的「播放全部」、随心听、智能电台批次——它们都直接读原始流，必须走可见集） |

### 1.2 明确不做的事

- ⛔ **不删收藏 / 不删历史 / 不删歌单内容**（S3）。
- ⛔ **不做「按源筛选 UI」**（不新增筛选控件）——需求只要「关掉就不出现」。
- ⛔ **不改 `NetworkMusicManager` 的现有语义**（`resolvePlayUrl` 按 `networkSource` 精确路由、不 fallback），这是别处依赖的行为。
- ⛔ **不动 MV / 歌词 / 天气电台的数据通路**——它们不是「音乐源」，见 §6。

---

## 2. 现状盘点（2026-10-28 读码确认）

### 2.1 五个「源」现在的开关与门控点

| 源 | 开关 | 存储 | 运行时生效方式 | 受控的路径 | 逃逸的路径 |
|---|---|---|---|---|---|
| **NAS** | `settings_nas_music_enabled`（默认开） | `AppSettings.nasMusicEnabled` | **无需注册/注销**（走 `backendRegistry`） | ✅ `SearchAggregator` 的 NAS 分支（v2.39.8 已加 `nasMusicEnabled()`） | ❌ 曲库全部 TAB、首页全部区块（含智能电台）、「我的」页收藏、详情页、远程搜索、`loadPlaylists`、流派/年份歌曲列表、歌单详情 |
| **本地** | `local_music_enabled`（默认开） | `AppSettings.localMusicEnabled` | 观察者启停扫描 | ✅ `SearchAggregator` 的 LOCAL 分支、`visibleLocalSongs` / `visibleLocalSongsNow()` | ⚠️ 基本已覆盖（该开关语义是「关 = 仅显示已下载」，见 §5.6） |
| **百度网盘** | `baiduConfig.isActive`（启用 **且** 已登录） | `CloudDriveConfig` | `registerService` / `unregisterService` | ✅ **只有** `NetworkMusicManager.search()` 这条（走 services map） | ❌ **`SearchAggregator` 的百度分支**（构造注入实例，不经 services map）；❌ `baiduIndexCache` 全部通路；❌ 网盘目录「播放全部」 |
| **Meting** | `settings_meting_enabled`（默认开） | `AppSettings.metingEnabled` | 同上 | ✅ `NetworkMusicManager.*` | ⚠️ 首页推荐位调 `networkMusicManager.getPlaylist()`，靠 service 缺席的间接效果；聚合器 NETWORK_MUSIC 分支同样靠 services map 间接受控（§5.1） |
| **Jamendo** | `settings_jamendo_enabled` **且** clientId 非空 | 同上 | 同上 | ✅ `NetworkMusicManager.search()` | ❌ **`SearchAggregator` 的 Jamendo 分支**；❌ `loadJamendoHot` / `loadJamendoTag` / `searchJamendo` 直接调 `nasMusicApp.jamendoService` |

> ⭐ **关键观察（修正后的版本，这是全文最重要的一条事实）**：
>
> 「在线源被建模成 service，所以关源自动生效」这个直觉**只对 `NetworkMusicManager` 成立，对聚合搜索不成立**。
> `SearchAggregator` 在 `NasMusicApp` 构造期被**直接注入实例**（`baiduService = baiduNetdiskService`、`jamendoService = jamendoService`，见 `NasMusicApp.kt:655-666`），其分支判据是 `baiduService != null` / `jamendoService != null` —— **对象非空永远为真，与 services map 无关**。
>
> 后果：**关掉百度后，曲库搜索（走 `SearchAggregator`）照样返回百度结果**。这是当前最严重的一处逃逸，且因为「设置页显示已关」而极难被用户归因。
> ⇒ 所以本方案的缺口不是两类（service / 非 service），而是**三类**：① service（已受控）；② **聚合器的构造注入实例（未受控）**；③ 非 service 的本地索引与直连调用（未受控）。

### 2.2 音乐内容入口清单（按屏）

> 下表是**全文最重要的一张表**：实施时逐行打勾，验收时逐行复验。
> ⚠️ 表中「直接读原始流」的行（智能电台、流派/年份列表、歌单详情、百度网盘目录、播放全部、「我的」页收藏、艺术家 TAB）是 S1/S4 违规的高发区——它们绕过一切合成点直接消费原始数据，处置方式见 §3.2.2 与 §3.3 处置表。

#### A. 首页（`HomeScreen` ← `HomeBranch`）

| 区块 | 数据来源 | 当前是否判开关 | 需要补的判据 |
|---|---|---|---|
| 顶部统计「专辑/歌曲/艺术家/歌单 N」 | `HomeDashboardData`（`loadHomeDashboard`） | ❌（**但不是缺陷**） | ⭐ **保持原样，不加门控**（S2）：统计读原始库存流，关源不扣减 |
| 最新添加专辑（轮播） | `HomeDashboardData.recentlyAddedAlbums` | ❌ | NAS 关 ⇒ 空 |
| 最近播放 | `prefs.history.getRecentSongObjects()` | ❌ | 按 `Song` 归属过滤（S3：不删库） |
| 随心听（随机） | `loadRandomSongs`：NAS `getRandomSongs` + Meting 预设歌单 | ❌ | NAS 关不取 NAS；Meting 关不取歌单；`_randomSongs` 按可见源过滤（§3.2.2） |
| 收藏歌曲 | `_favoriteSongs`（NAS `getFavorites`） | ❌ | NAS 关 ⇒ 不拉；**`_favoriteSongs` 置空**（它只服务 NAS，且「我的」页直读它） |
| **智能电台（batch）** | `loadSmartRadioBatch` → `SmartRadioManager.loadLibrary`（NAS 分页 + 本地 + Meting 采样） | ❌ | ⭐ **首页第 6 区块，原清单漏列**：NAS 关 ⇒ 不拉 NAS 分页样本；生成的批次再按 `song.sourceType` 过滤 |

#### B. 曲库 · 专辑 TAB

| 位置 | 数据来源 | 当前是否判开关 | 备注 |
|---|---|---|---|
| 专辑网格 | `mergedAlbums` ← `updateMergedData` ← `MusicMerger.mergeAlbums(nas, local, baidu)` | ❌ | **百度专辑明显漏** |
| 专辑详情曲目 | `loadAlbumSongs` / `playAlbumMultiSource`（按 `sourceIds` 前缀分派） | ❌ | `baidu_album_*` / 本地前缀 / NAS 三支都要判 |

#### C. 曲库 · 艺术家 TAB

| 位置 | 数据来源 | 当前是否判开关 | 备注 |
|---|---|---|---|
| 艺术家网格 | `mergedArtists` ← `MusicMerger.mergeArtists(nas, local, baidu)` | ❌ | 百度艺术家同样漏 |
| 艺术家详情曲目 | `loadArtistSongs`（NAS + `baiduIndexCache.songsByArtist`） | ❌ | — |
| 合作艺术家拆分 | `ArtistSplitter` 在 `loadArtists` 内 | ❌ | NAS 关时 `loadArtists` 不该请求 |
| **艺术家卡片曲目数** | `updateArtistSongCounts`（读**未过滤**的 `_songsPaging.songs`） | ❌ | ⭐ 原清单漏列：NAS 关后本地/百度艺术家卡片的曲目数会虚高。卡片属列表（S1）⇒ **传过滤后的 NAS 歌曲**（§3.3 G1） |
| **艺术家 TAB 曲目列表** | `artistSongsMap`（`:1467,1480` 由 NAS 歌增量构建，`LibraryScreen.kt:414-432` 读） | ❌ | ⭐ **原文完全未提**：关源后不清 ⇒ 本地/百度艺术家下仍显示旧 NAS 歌 |

#### D. 曲库 · 歌曲 TAB

| 位置 | 数据来源 | 当前是否判开关 | 备注 |
|---|---|---|---|
| 歌曲分页列表 | `_songsPaging`（**仅 NAS**）→ VM 派生 `visibleSongsPaging` 与 local / baidu 合并 | ❌ | ⚠️ 关 NAS 时列表**不应整体为空**：本地与百度仍要显示。要过滤的是 NAS 那一段，不是把整页清空。**合并点从 UI 迁到 VM（§3.3 G1）** |
| 全量后台加载 | `loadAllSongsBackground` | ❌ | 关源时不该发起 |
| 流派 / 年份 | `loadGenres` / `adapter.getYears()` | ❌ | 关源应为空；`_genres` / `_years` 关 NAS 时置空（§3.2.2） |
| **流派过滤歌曲列表** | `getSongsByGenre`（NAS `getSongsByGenre` + 本地过滤） | ❌ | ⭐ 原清单漏列：关 NAS ⇒ NAS 段为空，本地照旧 |
| **年份过滤歌曲列表** | `getSongsByYearRange`（NAS + 本地过滤） | ❌ | 同上 |

#### E. 曲库 · 搜索 / 发现

| 位置 | 数据来源 | 当前是否判开关 | 备注 |
|---|---|---|---|
| 统一搜索 · NAS 分支 | `SearchAggregator` 的 `nasMusicEnabled()` | ✅ 已判（v2.39.8） | — |
| 统一搜索 · LOCAL 分支 | `SearchAggregator` 的 `localMusicEnabled()` | ✅ 已判 | — |
| 统一搜索 · **百度分支** | `SearchAggregator`：`if (BAIDU_PAN in sources && baiduService != null)` | ❌ **只看实例非空** | 关百度后**照样返回百度结果**（§2.1 关键观察） |
| 统一搜索 · **Jamendo 分支** | `SearchAggregator`：`if (JAMENDO in sources && jamendoService != null)` | ❌ **只看实例非空** | 同上 |
| **统一搜索 · NETWORK_MUSIC 分支** | `SearchAggregator`：`networkMusicManager != null` | ⚠️ 实例恒非空 | ⭐ 原清单漏列：靠 services map **间接**受控（Meting 注销后 search 自然为空）；G2 统一改走 `SourceVisibility` 后显式判 |
| 发现页（维度浏览） | `refreshBrowseSongs` → `SearchAggregator` | 随聚合器 | 聚合器补齐后自动生效 |
| 网络搜索（独立入口） | `SearchViewModel.searchNetworkSongs` → `networkMusicManager.search` | ✅ 间接受控 | 走 services map，关源即失效 |
| 艺人全量展开 | `NetworkArtistExpansion` | ⚠️ | 只在 Meting 段生效；Meting 关后 `metingPart` 为空即跳过展开，行为已正确 |
| **Jamendo 独立入口** | `loadJamendoHot` / `loadJamendoTag` / `searchJamendo` → 直接调 `nasMusicApp.jamendoService` | ❌ **完全没判** | 关 Jamendo 后这些入口照样返回内容 |
| **拼音本地过滤** | `searchVM.nasLocalSongsProvider = { _songsPaging.value.songs }`（`:4290`） | ⚠️ | 仍扫 NAS 缓存；G2 会拦最终结果，仅冗余，可不改 |

> ⭐ 「源点亮 chip」与「源总开关」是两件事，见 §5.3。

#### F. 详情 / 队列 / 其他

| 位置 | 数据来源 | 当前是否判开关 |
|---|---|---|
| 播放队列 / 队列页 | `playerManager.playerState.queue` | 不判（S4：已入队继续播） |
| 最近播放（`recentSongs`） | `prefs.history` | ❌ |
| 我的 / 歌单管理 | `_playlists` ← `adapter.getPlaylists()` | ❌（NAS 独有源，无统计消费者 ⇒ 关 NAS 时置空，§3.2.2） |
| **「我的」页收藏** | `MineBranch.kt:34` 采集 `viewModel.favoriteSongs` → `:48` 传给 `MineScreen` | ❌（⭐ 原文漏列：**直读原始流**，无任何按源过滤 ⇒ 关 NAS 后仍列 NAS 收藏） |
| **歌单详情曲目** | `selectPlaylist` / `playPlaylist` → `adapter.getPlaylistSongs` | ❌（⭐ 原清单漏列） |
| **百度网盘目录「播放全部」** | `NetworkMusicViewModel.playAllNetdiskDir` / `collectAudioInDir` → `baiduIndexCache.load()` | ❌（⭐ 原清单漏列，百度通路 D，§5.2） |
| **「播放全部」按钮** | `LibraryScreen.playAllSongs`（`:436-457`）读**原始** `_songs`（`:442`/`:445`） | ❌（⭐ 原文只在推论里点名，**无处置条目** ⇒ 违反 S4） |
| **专辑卡片曲目数入参** | `LibraryScreen.kt:773` `AlbumsTab(songs = songs)` | ❌（⭐ 该入参当前是**死参数**：`AlbumGrid.kt:48` 收到但从不使用 ⇒ 可直接移除，零风险） |
| 手机遥控远程搜索 | `MainViewModel` 的 `RemoteCallbacks.search`（NAS `searchSongs` + `networkMusicManager.search`） | ❌ NAS 未判 |
| 随心听自动续播 | `startShuffleRefill` → `adapter.getRandomSongs` | ❌ |

---

## 3. 方案：核心契约 + 唯一真相源 + 两阶段拦截

### 3.0 核心契约：原始流 = 库存，派生流 = 可见集

这是本方案的地基，**先于一切实现决策**：

| 类别 | 成员 | 关源时的行为 | 谁读它 |
|---|---|---|---|
| **原始库存流** | `_albums` / `_songs` / `_songsPaging` / `_artists` | **永不清空、永不置空**（采集期短路只跳过请求，不动已有值） | S2 统计（`loadHomeDashboard`）、合成点（G1）、详情页 |
| **单源无统计流** | `_genres` / `_years` / `_playlists` / `_favoriteSongs` | **关掉其归属源时置空**（无统计消费者，也没有可用的合成点）；重开时由 `load*` 重拉 | 流派/年份 TAB、歌单管理、「我的」页收藏、首页收藏区块 |
| **派生/合成数据** | `_mergedAlbums` / `_mergedArtists` / **`visibleSongsPaging`（新增）** / 首页各区块 / 搜索结果 / 历史过滤结果 / 智能电台批次 / 艺术家卡片曲目数 | **在每个合成点按 `SourceVisibility` 过滤** | 一切 UI |

推论（必须写进代码评审口径）：
- **统计数字（S2）永远读原始库存流** ⇒ 关源不扣减是天然的，不需要「保留上次值」的特判（`totalPlaylists` 是唯一例外：NAS 关时不发 `getPlaylists()` 请求，统计沿用上一次值，见 §3.2.3）；
- **任何 UI 不得直接消费原始库存流当可见集**（`LibraryScreen` 的「播放全部」、`AlbumsTab` 的曲目数、`LibraryBranch` 的歌曲合并、`MineBranch` 的收藏——全部改走派生/过滤路径，见 §3.2.2 与 §3.3 处置表）；
- 冷启动 + NAS 已关时，原始库存流本就为空（从未加载）⇒ 统计显示未加载态（0/—）。这属「从未加载」，**不是** S2 禁止的「扣减」。

### 3.1 唯一真相源：`SourceVisibility`

新增一个**只读、无存储**的判定对象，收敛「某源此刻是否可见」这一个问题。所有门控点只调它，⛔ 不再各自读 `appSettings` 拼布尔。

```kotlin
// backend/SourceVisibility.kt
/**
 * 某个音乐源此刻是否「可见」（S1：可被列出）。
 *
 * ⛔ 只回答「用户开没开」，不回答「它能不能用」——后者是 backendRegistry /
 *    services map 的事，两者混在一起会让「NAS 未连接」被当成「NAS 被关掉」。
 */
class SourceVisibility(
    // ⭐ 实施时改为「每源一个同步 lambda」（原方案写的是 `settings: () -> AppSettings`）。
    //    原因：`appSettings` 只有 Flow、**没有同步快照** ⇒ 构造期快照会让开关永远停在
    //    启动时的值（`LocalMusicGateTest` 当初钉住的同一类缺陷）。语义完全等价，
    //    只是换依赖注入形态。见 §10.1 第 1 条。
    private val nasEnabled: () -> Boolean,
    private val localEnabled: () -> Boolean,
    private val metingEnabled: () -> Boolean,
    private val baiduActive: () -> Boolean,
    private val jamendoEnabled: () -> Boolean,
    private val jamendoClientId: () -> String,
) {
    fun isVisible(type: MusicSourceType): Boolean = when (type) {
        MusicSourceType.NAS          -> nasEnabled()
        MusicSourceType.LOCAL        -> localEnabled()
        MusicSourceType.BAIDU_PAN    -> baiduActive()
        MusicSourceType.NETWORK_MUSIC-> metingEnabled()
        MusicSourceType.JAMENDO      -> jamendoEnabled() && jamendoClientId().isNotBlank()
        // 非曲库来源：不受音乐源开关影响（S4）
        MusicSourceType.RADIO, MusicSourceType.WEATHER_RADIO,
        MusicSourceType.DOWNLOAD, MusicSourceType.IMPORTED -> true
    }

    /** 按 Song 的实际归属判定（用于历史 / 收藏 / 队列这类「已存在的条目」） */
    fun isVisible(song: Song): Boolean = isVisible(song.sourceType)
}
```

**为什么不用 `suspend`**：现有 `visibleLocalSongsNow()` 是**同步**读取路径（`_localMusicEnabled` 镜像），大量调用点在非挂起上下文。沿用 `AppPreferences` 已有的 `@Volatile` 镜像，同步读零 IO。

✅ **所需镜像全部已就绪**（v2.39.8 已落好，无需新增）：
`getMetingEnabledSync()` / `getNasMusicEnabledSync()` / `getJamendoEnabledSync()` / `getBaiduActiveSync()` / `getJamendoClientIdSync()`，以及统一的 `isSourceEnabledSync(key)`。
⚠️ 后者按 **services map 的 key**（`meting` / `nas` / `baidu` / `jamendo` / `lx`）分派，不是 `MusicSourceType`——`SourceVisibility` 内部用 `MusicSourceType` 判，两者需要一层小映射，别直接把 `MusicSourceType.name` 传进去（`NETWORK_MUSIC` ≠ `meting`、`BAIDU_PAN` ≠ `baidu`）。

⚠️ **两个必须写进单测的边界**：
- **未知 `networkSource` 字符串会静默归入 `NETWORK_MUSIC`** ⇒ 被 Meting 开关门控（未来的独立网络源会莫名受 Meting 开关影响，且 `when` 对枚举穷尽、对字符串不穷尽，**不会编译报错**）。⇒ 单测对拍所有现存 `networkSource` 字符串 → `MusicSourceType` 的映射（§7.2）。
- **镜像冷启动竞态**：`startProviderMirrors` 与 `NasMusicApp` 的注册并发执行，镜像可能短暂为编译期默认值。注册侧已用直接 DataStore 读规避（`NasMusicApp.kt:402-407`）；`SourceVisibility` 同窗口内可能读到默认值 ⇒ 首帧 UI 刷新可延迟到镜像同步后（与注册侧同策略），并在类 KDoc 注明该窗口。

### 3.2 阶段一：采集期不请求 + 关源时的缓存处置

**目标 A**：关掉的源**根本不发请求**——省流量、省电、避免「关了还在后台拉」。
**目标 B**：关源瞬间，把**只属于该源、且没有合成点可过滤**的缓存清掉——否则 UI 直读原始流会继续显示旧内容。

#### 3.2.1 采集期短路清单（不请求，共 22 项）

在各 `load*` 函数入口加短路。**契约约束（§3.0）**：只跳过请求，**不清空原始库存流**；单源无统计流按 §3.2.2 单独处置。

```kotlin
private fun loadLibrary() {
    val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
    if (!nasVisible) {
        // ⚠️ 原始库存流不动（S2：统计永远读它们）；只重算派生可见集
        _isLibraryLoading.value = false
        updateMergedData()   // 本地 / 百度照常显示，NAS 段置空
        return
    }
    ...
}
```

| # | 函数 | 关源时的行为 |
|---|---|---|
| 1 | `loadLibrary` | 不拉 NAS；**仍调 `updateMergedData()`**，本地/百度照常显示；**不清空 `_albums`/`_songs`** |
| 2 | `loadArtists` | 不拉 NAS 艺术家 |
| 3 | `loadYears` | 不拉 NAS 年份 |
| 4 | `loadGenres` | 不拉 NAS 流派 |
| 5 | `loadFavorites` | 不拉 NAS 收藏 |
| 6 | `loadPlaylists` | 不拉 NAS 歌单 |
| 7 | `loadSongsNextPage` | 不拉 NAS 分页；写回处再判一次（§5.7） |
| 8 | `loadAllSongsBackground` | 不发起全量拉取 |
| 9 | `loadRandomSongs` | 不取 NAS 随机；Meting 关则不取歌单（保留值处理见 §5.7b） |
| 10 | `startShuffleRefill` | 不续播 NAS 随机曲 |
| 11 | `loadAlbumSongs` / `playAlbumMultiSource` | 按 `sourceIds` 前缀分别判：`baidu_*` / 本地 / NAS 三支各自决定取不取 |
| 12 | `loadArtistSongs` | 不取 NAS / 百度艺术家曲目 |
| 13 | `MainViewModel` 的 `RemoteCallbacks.search` | 不查 NAS（网络侧走 services map，已受控） |
| 14 | `loadJamendoHot` / `loadJamendoTag` / `searchJamendo` | Jamendo 关则不请求（**§2.2 E 段逃逸点**；`jamendoConfigured`（仅 clientId 检查）≠ `jamendoEnabled`，别混用） |
| 15 | `loadHomeDashboard` | ⚠️ **不是「整段跳过」而是「换数据源」**：见 §3.2.3 |
| 16 | `getSongsByGenre` | ⭐ 新增：NAS 段为空，本地照旧 |
| 17 | `getSongsByYearRange` | ⭐ 新增：同上 |
| 18 | `selectPlaylist` / `playPlaylist` | ⭐ 新增：NAS 关 ⇒ 不请求歌单曲目 |
| 19 | `loadSmartRadioBatch`（→ `SmartRadioManager.loadLibrary`） | ⭐ 新增：NAS 关 ⇒ 不拉 NAS 分页样本（注入方式见 §3.2.4）；生成的批次在合成点再过滤（§3.3 G1c） |
| 20 | `playAllNetdiskDir` / `collectAudioInDir` | ⭐ 新增：百度关 ⇒ 不请求（与通路 C 同判据，§5.2） |
| 21 | `updateArtistSongCounts` | ⭐ 新增：传**过滤后**的 NAS 歌曲（读原始 `_songsPaging.songs` 会虚高，§2.2 C）。**需改签名**：现为内部自读 `_songsPaging.songs`（`:2252-2253`），改为接收 `nasSongs` 入参，由 `updateMergedData` 传入过滤后的列表 |
| 22 | `loadSongsFirstPage`（`:1573`） | ⭐ 新增：NAS 关 ⇒ 不拉首页；`totalCount` 不得在关源期间展示为「N 首歌」（§3.3 G1 的 totalCount 口径） |

#### 3.2.2 关源时的缓存清空/重建清单（★ v3 新增：v2 的注释是死代码）

v2 写的是「无合成点的流由各 `load*` 短路负责清空」——**不成立**：这些 `load*` 在开关翻转时**没有任何调用者**（`loadGenres` 仅在 `loadLibrary` 内 `:1551` 被调；`loadRandomSongs(adapter)` 仅 `:1562`/`:1725`；`loadYears()`/`loadPlaylists()` 只有 UI `retry` 回调会触发）。

⇒ v3 引入一个**显式**的清理入口，由开关翻转路径直接调用：

```kotlin
// MainViewModel
/**
 * 关掉某个源时，清理「只属于该源、且没有合成点可过滤」的缓存。
 * ⛔ 不动原始库存流（_albums/_songs/_songsPaging/_artists）——它们是 S2 统计的读数来源。
 */
private fun clearSourceScopedCaches(source: String) {
    when (source) {
        SOURCE_NAS -> {
            _genres.value = UiState.Success(emptyList())      // 无统计消费者，仅 NAS 填充
            _years.value = UiState.Success(emptyList())       // 同上
            _playlists.value = UiState.Success(emptyList())   // 同上
            _favoriteSongs.value = UiState.Success(emptyList())// 同上；且「我的」页直读它
            _randomSongs.value = _randomSongs.value.filter { it.sourceType != MusicSourceType.NAS }
            rebuildArtistSongsMap()                           // 去掉 NAS 歌（§3.2.5）
        }
        SOURCE_BAIDU -> {
            _baiduIndexedSongs.value = emptyList()            // 百度独有，仅由 baiduIndexCache 填充
        }
        SOURCE_METING -> {
            _randomSongs.value = _randomSongs.value.filter { it.sourceType != MusicSourceType.NETWORK_MUSIC }
        }
        SOURCE_JAMENDO -> Unit                              // 无独立流
    }
    _favoriteIds.value = _favoriteIds.value.filter { ... }    // 心形态：同步清掉该源条目
    bumpSourceRevision()                                    // 触发 visibleSongsPaging 重算（§3.3 G1）
}
```

| 关掉的源 | 必须清/重建的流 | 为什么 |
|---|---|---|
| **NAS** | `_genres` / `_years` / `_playlists` / `_favoriteSongs` | 均只由 NAS 填充、**无统计消费者**、无合成点 ⇒ 置空是唯一可行处置 |
| **NAS** | `_randomSongs`（按 `sourceType` 过滤掉 NAS 歌） | 该流混装 NAS + Meting，保留 Meting 部分 |
| **NAS** | `artistSongsMap` 重建 | 由 NAS 歌增量构建，残留旧 NAS 歌（`LibraryScreen.kt:414-432` 读） |
| **百度** | `_baiduIndexedSongs` | 由 `baiduIndexCache.allSongs()` 填充，UI 直读（`LibraryBranch`） |
| **Meting** | `_randomSongs`（过滤掉网络歌） | 同上 |
| **Jamendo** | 无 | Jamendo 没有独立缓存流 |

#### 3.2.3 `loadHomeDashboard` 必须区别对待

它在 `adapter == null` 时**整段 return 并清空 `HomeDashboardData`**（`MainViewModel.kt:306-309`）。若照抄这个写法在 NAS 关闭时也 return，首页「最近添加专辑」「收藏歌曲」会**保留上一次的 NAS 内容不刷新** ⇒ 直接违反 S1。

正确做法是**只换数据源、不提前返回**：

```kotlin
// 关 NAS 时：四个 total 读原始库存流（永不清空 ⇒ 天然保持，S2），轮播内容改用非 NAS 来源
totalAlbums  = _albums.value.dataOrNull().orEmpty().size      // ← 原始流，保持原值（S2）
totalSongs   = _songs.value.dataOrNull().orEmpty().size        // ← 同上
totalArtists = _artists.value.dataOrNull().orEmpty().size      // ← 同上
totalPlaylists = if (nasVisible) playlists.size else _homeDashboardData.value.totalPlaylists  // ← 唯一例外：NAS 关时不发请求，沿用上一次值
recentlyAddedAlbums = if (nasVisible) recentlyAdded else emptyList()   // ← S1：轮播要清
favoriteSongs       = if (nasVisible) favoriteSongs else emptyList()
```

#### 3.2.4 SmartRadioManager 的可见性注入方式（★ v3 新增）

`SmartRadioManager` 由 `NasMusicApp:384-396` 构造（与 `SearchAggregator` 同形：**构造期注入 lambda，调用时实时读**）。⇒

```kotlin
// NasMusicApp 构造期
smartRadioManager = SmartRadioManager(
    ...,
    nasMusicEnabled = { appPreferences.getNasMusicEnabledSync() },   // 同步镜像，调用时实时读
)

// backend/radio/SmartRadioManager.loadLibrary（约 :261-298）
if (nasMusicEnabled()) {
    // 原有 NAS 分页循环（约 :274-287）
}
```

批次侧（可见集）在 `MainViewModel.loadSmartRadioBatch`（`:3073`）的 `generateOnly` 回调（`:3076`）里按 `song.sourceType` 过滤——VM 持有 `SourceVisibility`，此处无需额外注入。

#### 3.2.5 艺术家 TAB 的 `artistSongsMap`（★ v3 新增）

`artistSongsMap`（`:1467`、`:1480`）由 `buildArtistMapsIncremental` 从 NAS 歌增量构建，被 `LibraryScreen.kt:414-432` 读来渲染艺术家 TAB 的曲目。⇒
- 关 NAS ⇒ **重建**该 map（去掉 NAS 歌），不是留着；
- 重建入口复用 `buildArtistMapsIncremental` 的同一份数据源，**不新增第二套构建逻辑**。

### 3.3 阶段二：合成期再过滤（**这是不漏的保证**）

阶段一挡不住三种情况：① `baiduIndexCache` 里已有的旧数据；② 关闭瞬间正在飞的请求结果写回；③ 历史 / 收藏里本来就存着该源的歌。所以在**四个合成点**再过滤一次——**全部位于 VM 侧**：

| 合成点 | 位置 | 改动 |
|---|---|---|
| **G1 曲库合成** | `updateMergedData()` + **新增派生流 `visibleSongsPaging`** | `nasAlbums` / `nasArtists` 传空；`baiduSongs` 传 `emptyList()`；本地照旧（已有 `visibleLocalSongsNow()`）；`updateArtistSongCounts` 传过滤后的 NAS 歌曲（签名见 §3.2.1 #21）；**新增** `visibleSongsPaging` 派生流（见下） |
| **G1c 首页/电台合成点** | `loadHomeDashboard` 的轮播/收藏区块；`SmartRadioManager` 生成批次 | 区块按源置空（§3.2.3）；电台批次按 `song.sourceType` 过滤（§3.2.4） |
| **G2 聚合搜索** | `SearchAggregator.search()` | ⚠️ **补三个分支**：百度、Jamendo（现只看实例非空）、NETWORK_MUSIC（统一显式判）；NAS/LOCAL 统一改走 `SourceVisibility`；最后对**结果**再按 `song.sourceType` 过滤一遍（防 fallback 串源） |
| **G3 在线服务** | `NetworkMusicManager` | 保持 `registerService` / `unregisterService` 不变（**这是正确做法**，别改成查询时过滤——`orderedServices()` 过滤只覆盖 `search`，覆盖不到 `resolvePlayUrl` 的精确路由） |

#### ⭐ G1 的关键改动：`visibleSongsPaging` 派生流（v3 取代 v2 的 UI 层合成点）

**为什么必须移进 VM**：v2 把歌曲合并钉在 `LibraryBranch.kt:61-71`，那是 `remember(songsPaging, localSongsList, baiduSongsList) { … }`——**开关翻转时三个键都不变**（§3.0 禁止清空 `_songsPaging`，关源分支也不碰它）⇒ `remember` 永不重算 ⇒ NAS 段永远不会被置空。⇒ 合并必须在 VM 侧完成。

```kotlin
// MainViewModel
/** 每次源开关变化 +1，用于强制派生流重算（可见性本身是同步镜像，不是 Flow） */
private val sourceRevision = MutableStateFlow(0L)
private fun bumpSourceRevision() { sourceRevision.value += 1 }

/** 可见歌曲分页：NAS 段按可见性置空，本地/百度照旧 —— UI 侧只做透传 */
val visibleSongsPaging: StateFlow<SongsPaging> = combine(
    _songsPaging, visibleLocalSongs, _baiduIndexedSongs, sourceRevision
) { paging, local, baidu, _ ->
    val nasVisible = sourceVisibility.isVisible(MusicSourceType.NAS)
    val baiduVisible = sourceVisibility.isVisible(MusicSourceType.BAIDU_PAN)
    val merged = MusicMerger.mergeLibrarySongs(
        nasSongs = if (nasVisible) paging.songs else emptyList(),
        localSongs = local.filter { sourceVisibility.isVisible(it) },
        baiduSongs = if (baiduVisible) baidu else emptyList(),
    )
    // totalCount 口径平移现有 LibraryBranch.kt:69 的合并算法；
    // ⚠️ NAS 关时 NAS 的 totalCount 不参与（否则会显示一个不存在的「N 首歌」）
    paging.copy(songs = merged, totalCount = mergedTotalCount(paging, merged, nasVisible))
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _songsPaging.value)
```

配套改动：
- `LibraryBranch.kt:61-71` 的 `remember` **退化为透传**：`val mergedSongsPaging = viewModel.visibleSongsPaging.collectAsState()`；
- `LibraryBranch.kt:56` 的 `songList = songsState.dataOrNull()`（原始 `_songs`）**不再下传**给 `LibraryScreen`——改传可见集，否则 `playAllSongs`（`:436-457`）仍会取到 NAS 歌（违反 S4）；
- `LibraryScreen.kt:773` `AlbumsTab(songs = songs)`：**该入参当前是死参数**（`AlbumGrid.kt:48` 收到但从不使用）⇒ 直接移除，零风险；
- `visibleSongsPaging` 的 `stateIn` 初值用 `_songsPaging.value`，避免首帧空白。

#### 每个原始流的处置（契约的执行表，代码评审逐行对照）

| 原始流 | 类别 | 关源时 | 重开时 |
|---|---|---|---|
| `_albums` / `_songs` / `_songsPaging` / `_artists` | 库存（S2 统计读它们） | **不清空**；G1 过滤派生数据 | `loadLibrary()` 自然重拉 |
| `_songsPaging.songs` 页数据 | 库存页数据 | 不清空；`visibleSongsPaging` 过滤；`totalCount` 关源期间不计 NAS | 重置分页后重拉 |
| `_genres` / `_years` | 单源无统计流（**有**合成点 `mergedGenreList`/`mergedYearsList`，但合并的是元数据不是歌） | **置空**（§3.2.2） | `loadGenres` / `loadYears` 重拉 |
| `_playlists` | 单源无统计流（NAS 独有） | **置空**（§3.2.2）；`totalPlaylists` 沿用上次值 | `loadPlaylists()` 重拉 |
| `_favoriteSongs` | 单源无统计流（NAS 独有，**v3 修正分类**：无统计消费者，「我的」页直读） | **置空**（§3.2.2） | `loadLibrary()`（内含 `loadFavorites`）重拉 |
| `_randomSongs` | 混装 NAS + Meting | **按 `sourceType` 过滤**（§3.2.2） | `loadRandomSongs()` 重拉 |
| `_baiduIndexedSongs` | 百度独有 | **置空**（§3.2.2） | 百度索引重扫 |
| `artistSongsMap` | NAS 增量构建 | **重建**（§3.2.5） | 随 `loadLibrary` 重建 |
| `_favoriteIds` | 心形 UI 态 | **同步过滤**（§3.2.2） | 随收藏重建 |
| `_recentSongs` | 历史 | 不清库；按 `song.sourceType` 过滤（§3.4） | 自动恢复 |
| `_mergedAlbums` / `_mergedArtists` / `visibleSongsPaging` / 首页区块 / 电台批次 / 艺术家卡片数 | 派生可见集 | 合成点过滤（G1/G1c） | 随原始流重拉自动重建 |

> ⭐ **为什么百度只需改几行就够**：`buildBaiduAlbums` / `buildBaiduArtists` / `songsByAlbumName` / `songsByArtist` **全部**以 `baiduSongs` 或 `baiduIndexCache` 为输入（`baiduIndexCache` 是 `MainViewModel:2172` 的私有 getter，转发到 `nasMusicApp.baiduFileIndexCache`）。把 `baiduSongs` 置空 + 给 `baiduIndexCache.*` 的直接调用点加判据，整条百度数组通路就闭合了。

### 3.4 历史 / 收藏 / 最近播放：按 Song 归属过滤

这三处**数据保留、只过滤展示**（S3）：

```kotlin
// ⭐ 过滤位置定死：在 VM 的 load* 内做一次，UI 侧不再各写一遍
// loadRecentSongs（:1853）内：
val recent = prefs.history.getRecentSongObjects().filter { sourceVisibility.isVisible(it) }
```

⚠️ **归属判定必须唯一**——`data/model/SourceIdentifier.kt` 已经定义了扩展属性 `Song.sourceType`（`DOWNLOAD` → `LOCAL` → `IMPORTED` → `!isNetworkSong = NAS` → `radio`/`baidu`/`weather`/`jamendo` → 其余归 `NETWORK_MUSIC`），实施时**直接复用**它，⛔ 不要新写一套（两套判定必然漂移，后果是「界面里有但播放解析失败」）。
⚠️ 注意它把 `DOWNLOAD` 与 `LOCAL` 分成了两个来源，而 `SourceVisibility` 对两者都返回「不受影响」——语义上一致，但顺序敏感：**先判 `DOWNLOAD` 再判 `LOCAL`** 这一点不要改动。
⚠️ **过滤只做一次**：在 `loadRecentSongs` / `loadFavorites` 内部过滤后落库到 StateFlow，UI（`HomeBranch.kt:41`、`MineBranch.kt:36`）直接消费已过滤的流——⛔ 不要在每个 UI 里再写一遍 `filter {}`。

### 3.5 关源 / 重开的双向刷新（★ v3 补齐触发路径）

`MainViewModel.setSourceEnabled(key, enabled)`（`:657-692`）是所有开关的唯一入口（含设置页卡片与 NAS 新开关）。按方向分两条规则：

```kotlin
// 关源：重算 + 清缓存（不重拉）
fun setSourceEnabled(key, enabled) {
    ...
    if (!enabled) {
        clearSourceScopedCaches(key)   // §3.2.2：置空/重建单源缓存
        updateMergedData()             // G1：曲库合并（nasAlbums/nasArtists/baiduSongs 置空）
        loadHomeDashboard()            // 首页统计与轮播（统计读原始流，自动保持）
        loadRecentSongs(showLoading = false)   // 历史按源过滤
        // ⚠️ 不要再指望各 load* 的短路来清缓存——它们在翻转时没有调用者
    } else {
        // ⭐ 重开：必须重拉该源数据——关源期间该源的流已被清空
        when (key) {
            SOURCE_NAS -> {
                loadLibrary()       // 含 loadGenres(:1551) / loadFavorites / loadRandomSongs(:1562)
                loadYears()         // ⭐ 独立入口，不会被 loadLibrary 触发
                loadPlaylists()     // ⭐ 独立入口
            }
            SOURCE_METING -> loadRandomSongs()   // ⭐ 恢复随心听的网络段
            SOURCE_BAIDU  -> refreshBaiduServiceRegistration()   // 已由 setSourceEnabled 调用
            SOURCE_JAMENDO-> refreshJamendoServiceRegistration() // 同上
        }
        bumpSourceRevision()   // 强制 visibleSongsPaging 等派生流重算
    }
    // 若关的是当前默认源：repairDefaultNetworkSource() 已存在，保留
}
```

⚠️ **「不要把刷新写成 `loadLibrary()` 全量重拉」只适用于关源方向**——重开方向恰恰需要它。写错方向的代价：用户重开开关看到空曲库（验收 V16）。
⚠️ **重开方向不能只做 `registerService`**：Meting 重开后 services map 恢复，但**随心听的网络段不会自己回来**（`loadRandomSongs` 没有调用者）——必须显式调一次。

---

## 4. 分阶段实施

| 阶段 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| **P0** | `SourceVisibility`（镜像已就绪，无需新增）+ `MusicSourceType` ↔ services key 映射；单测覆盖五源 × 开/关矩阵 + `networkSource` 字符串映射对拍 | 无 | 0.5 d |
| **P1a** | **VM 侧合成**：G1（`updateMergedData` 三处置空 + `updateArtistSongCounts` 签名变更 + **`visibleSongsPaging` 派生流**）、G1c 首页区块、G2（聚合器补三分支 + 结果过滤）、`baiduIndexCache.*` 直接调用点加判据（含网盘目录播放） | P0 | 1.0 d |
| **P1b** | **UI 侧收口**：`LibraryBranch` 透传 `visibleSongsPaging`、停止下传原始 `songs`、移除 `AlbumsTab` 死参数、`MineBranch` 收藏路径、`artistSongsMap` 重建、封面解析的百度输入 | P1a | 0.5 d |
| **P2a** | **机械短路**：§3.2.1 表的 1–15 项 | P0 | 1.0 d |
| **P2b** | **带设计决策的短路**：§3.2.1 表的 16–22 项（含 SmartRadioManager 注入、`updateArtistSongCounts` 签名、netdisk 门控位置） | P0、P1a | 1.0 d |
| **P3** | **历史 / 收藏 / 最近播放**按 Song 归属过滤（S3），过滤点定死在 VM 内 | P0 | 0.5 d |
| **P4** | 首页**轮播区块**与智能电台批次按源过滤；统计数字**保持不过滤**（S2） | P1a | 0.5 d |
| **P5** | **双向刷新**（§3.5）：关 ⇒ `clearSourceScopedCaches` + 重算；开 ⇒ 按源重拉 | P1–P4 | 0.5 d |
| **P6** | 门禁测试（含 `nasMusicEnabled` 钉守）+ **新建测试基建**（fake `BackendAdapter` + `MainViewModel` 测试宿主）+ 行为测试 + 全量回归 | 全部 | 1.0 d |

**总预估 6.5–7 人日**（不含真机回归）。⚠️ v2 的 5.5 人日偏乐观，两个原因：**P1 把一个架构决策（G1b 迁 VM + 可见性管道）当成实现活计**；**P6 的 0.5 天假设了已有的测试基建，而仓库里既没有 fake `BackendAdapter`，也没有任何 `MainViewModel` 实例化的测试宿主**（现有测试只 `mock<MainViewModel>()` 或读源码文本）。

### 4.1 建议的提交切分

1. `feat(source): 新增 SourceVisibility 统一判定与映射单测`
2. `fix(library): VM 侧按源过滤并派生 visibleSongsPaging`
3. `fix(library): 聚合器补齐百度/Jamendo/网络源分支与结果过滤`
4. `fix(ui): 曲库与「我的」页改走可见集，移除原始流直读`
5. `fix(library): 采集期短路（机械项）`
6. `fix(library): 采集期短路与缓存处置（含新增入口）`
7. `fix(home): 首页区块与智能电台按源过滤（统计不动）`
8. `fix(source): 关源清缓存 / 重开重拉的双向刷新`
9. `test(source): 可见性矩阵、门禁与行为测试（含测试基建）`

---

## 5. 容易踩的坑（逐条都有代码依据）

### 5.1 聚合器里只有 NAS / LOCAL 两个分支有门控，百度、Jamendo、NETWORK_MUSIC 没有

`SearchAggregator` 在 `NasMusicApp` 构造期创建一次，NAS / LOCAL 用 `suspend provider` 实时读开关；**但百度与 Jamendo 分支只判 `baiduService != null` / `jamendoService != null`**，而这两个实例是构造期注入的（`NasMusicApp.kt:655-666`），恒非空 ⇒ **关源后聚合搜索照样返回它们的结果**。另有第五个分支 NETWORK_MUSIC 只判 `networkMusicManager != null`（实例恒非空），靠 services map **间接**受控。

| 分支 | 判据 | 状态 |
|---|---|---|
| NAS（`:169`） | `nasMusicEnabled()` | ✅ |
| LOCAL（`:278`） | `localMusicEnabled()` | ✅ |
| BAIDU（`:220`） | `baiduService != null` | ❌ |
| JAMENDO（`:257`） | `jamendoService != null` | ❌ |
| NETWORK_MUSIC（`:199`） | `networkMusicManager != null` | ⚠️ 间接 |

同时，**曲库的 `loadLibrary` 完全不走聚合器**，两者是两条路。所以两种「只改一处」都会留下明显缺口：

| 只改了什么 | 会出现的症状 |
|---|---|
| 只改聚合器 | 「搜索对了，但专辑页还是满的」 |
| 只改曲库 | 「专辑页空了，但搜索还能搜出已关源的内容」 |

⚠️ **v2.39.8 的 NAS 门控没有对应 gate test**（`nasMusicEnabled` 在测试代码里零命中；`LocalMusicGateTest` L3 钉住了 local 的同类判据，NAS 这条无守卫）。§7.2 必须补。

### 5.2 百度有**四条**通路，只有一条受 service 注销控制

| 通路 | 链路 | 关源后 |
|---|---|---|
| **A 在线（service）** | `NetworkMusicManager.search()` → `services["baidu"]` | ✅ 失效（key 已被 `unregisterService` 移除） |
| **B 在线（聚合器）** | `SearchAggregator` 的 `baiduService` 字段（构造注入，绕过 services map） | ❌ **照样返回结果** |
| **C 本地索引** | `baiduIndexCache` → `updateMergedData` / `loadAlbumSongs` / `loadArtistSongs` | ❌ 完全不受控 |
| **D 网盘目录播放** | `NetworkMusicViewModel.playAllNetdiskDir` / `collectAudioInDir` → `baiduIndexCache.load()` | ❌ 完全不受控 |

⚠️ **B 是最容易漏的一条**：它「看起来已经关了」（设置页显示百度已关、`services` 里也确实没有 `baidu`），但聚合器手里还攥着那个对象。Jamendo 同理（且它还有 A/B/C 之外的第四类：`loadJamendoHot` 等直连入口）。

### 5.3 「源点亮 chip」≠「源总开关」，两者不可混淆

曲库搜索页有 `enabledSearchSources`（用户临时点亮/熄灭的 TAB 级过滤），设置页有源总开关。**判据是「与」关系**：`点亮的源 ∩ 开着的源`。
⛔ 不要用 `enabledSearchSources` 代替 `SourceVisibility`——它不持久化、也不包含 NAS/百度 的开关语义；反过来也不要让「全部点亮」把已关闭的源点亮回来。

### 5.4 `MusicMerger` 的合并键是**名字**，不是源

`mergeAlbums` / `mergeArtists` 按 `normalizeKey(name)` 合并。⚠️ 注意 `sourceIds` 只挂在 `Album` 上（`Album.kt:17`），`Artist` 模型没有 `sourceIds` 字段——所以「过滤」必须在**传入之前**做（§3.3 G1），⛔ 不能合并完再按 `sourceIds` 剔除——那样会把「NAS 与百度同名专辑」整条删掉，连同 NAS 那一份。

### 5.5 关源后**不要**去改统计数字（这是与直觉相反的一条）

首页统计来自 `HomeDashboardData`，`totalSongs` 取的是原始 `_songs`（NAS 原始总数）而非合并后的列表。实施时很容易「顺手」把它也按开关过滤——**⛔ 不要**：

- 这个数字回答的是「我的曲库里有多少歌」，属于**历史事实**，与「此刻列出哪些」是两个问题；
- 扣减的后果是可逆性差：用户关一次源，数字掉一次，重开才回来，观感上像「丢歌」；
- 「数字与列表对不上」是**预期内**的：列表是当前可见集合，数字是全量存量，二者本来就不该相等。

⭐ 因此 P4 只过滤 `recentlyAddedAlbums` 等**轮播内容**，`totalAlbums` / `totalSongs` / `totalArtists` / `totalPlaylists` 四个字段**一行都不改**。按 §3.0 契约，前三项读原始库存流（永不清空）天然保持；`totalPlaylists` 是唯一需要特判的（NAS 关时不发 `getPlaylists()` 请求，沿用上一次值）。

### 5.6 不要让关源影响「已下载」

本地开关的既有语义是「关 = 只显示已下载」（`storageType == "DOWNLOAD"`，走应用专属目录、与 `READ_MEDIA_AUDIO` 无关）。新代码必须**沿用 `visibleLocalSongsNow()`**，⛔ 不要新写 `filter { }` 把已下载也滤掉。

### 5.7 在途请求的结果要丢弃

关源瞬间正在飞的 `adapter.getSongs()` 回来后会写 `_songsPaging`。阶段二的合成期过滤能兜住列表（`visibleSongsPaging`），但**分页状态**本身会带着旧数据；建议在 `loadSongsNextPage` / `loadAllSongsBackground` 的写回处再判一次 `isVisible(NAS)`。

### 5.7b `_randomSongs` 的「失败保旧值」分支要特判

`loadRandomSongs` 在请求失败时**特意保留旧值**（`MainViewModel.kt:1711-1718`：只有拉到非空结果才覆盖；「有后端但已有数据的不清空」）。但采集期短路时「不请求」会被这个分支误当成「失败」从而保留旧 NAS 随机曲 ⇒ 关 NAS 后随心听仍显示旧内容。⇒ 短路分支必须**显式走过滤路径**（§3.2.2 的 `_randomSongs` 过滤），不走失败分支。

### 5.8 单源无统计流的清空纪律

`_genres` / `_years` / `_playlists` / `_favoriteSongs` / `_baiduIndexedSongs` 这五个流**只服务于单一源、没有统计消费者、也没有可用的合成点**（UI 直读）⇒
- 关掉其归属源时**必须清空**（§3.2.2 的 `clearSourceScopedCaches`），否则流派/年份 TAB、歌单管理、「我的」页收藏、百度索引仍显示旧内容；
- 清空只发生在关源方向；重开方向由 `load*` 自然重拉；
- 其余原始库存流（`_albums`/`_songs`/`_songsPaging`/`_artists`）**一律不清空**——它们是 S2 统计的读数来源。

⚠️ **不要指望「各 `load*` 的短路分支会顺手清掉」**——v2 就是这么写的，结果是死代码（这些 `load*` 在开关翻转时没有调用者）。

### 5.9 镜像冷启动竞态

`startProviderMirrors` 与 `NasMusicApp` 的注册并发执行，`@Volatile` 镜像可能短暂为编译期默认值。注册侧已用直接 DataStore 读规避（`NasMusicApp.kt:402-407`）；`SourceVisibility` 同窗口内可能读到默认值 ⇒ 首帧 UI 刷新延迟到镜像同步后，并在 KDoc 注明该窗口（§3.1）。

### 5.10 可见性是同步镜像，不是 Flow（★ v3 新增）

`SourceVisibility` 读的是 `@Volatile` 镜像 ⇒ **不是响应式的**。任何依赖可见性重算的派生流（`visibleSongsPaging`）必须有一个显式的**重算信号**（`sourceRevision`），否则开关翻转后派生流不会重算——这正是 v2 的 G1b 失败的根因。
⇒ 规则：**凡是「可见性影响结果」的派生数据，一律挂 `sourceRevision`**；`bumpSourceRevision()` 只在 `setSourceEnabled` 与 `clearSourceScopedCaches` 里调用。

---

## 6. 明确不在范围内

| 项目 | 原因 |
|---|---|
| **MV / Bilibili** | 不是音乐源，走独立的 `MvSearchManager` service 列表 |
| **歌词端点（酷狗/网易）** | 是「网络高级」里的端点配置，不是源；关源后歌词退化已由既有逻辑兜住 |
| **天气电台** | `WeatherRadioManager` 按心情匹配，不是曲库列出。⚠️ 注意它内部有 `searchNasSongs` 直接分页读 NAS 库（`WeatherRadioManager.kt:158-177`）——**显式排除**（所有者裁决：电台是独立功能，不走音乐源开关；NAS 关后电台仍可匹配，属预期行为） |
| **智能电台（首页批次）** | ⭐ **裁决：在范围内**——它是首页区块、生成的批次是「列出音乐」的另一种形态（且直接读 NAS 分页）。处置见 §3.2.4 与 §3.3 G1c |
| **播放队列里已存在的歌** | S4：用户主动播放过，不做强制剔除 |
| **删除收藏 / 历史 / 歌单条目** | S3：关源是显示开关，不是数据销毁 |
| **`_favoriteIds` 心形图标态** | 仅外观层（§3.2.2 顺手同步过滤即可），不单独立项 |

---

## 7. 验收标准

### 7.1 功能（逐条可复验）

| # | 场景 | 期望 |
|---|---|---|
| V1 | 关 NAS，进「专辑」TAB | 无任何 NAS 专辑；仅有本地 / 百度 |
| V2 | 关 NAS，进「艺术家」TAB | 同上；艺术家卡片曲目数按可见集计算（不虚高），且卡片下曲目列表不含旧 NAS 歌 |
| V3 | 关 NAS，进「歌曲」TAB | 列表为空（若本地/百度也为 0），不出现 NAS 歌（旧分页页也被过滤） |
| V4 | 关百度，进「专辑 / 艺术家 / 歌曲」 | 无任何百度条目（**当前会漏，这是本方案的核心修复点**） |
| V5 | 关百度，首页「最新添加专辑」 | 不含百度专辑 |
| V6 | 关 Meting，首页「随心听」 | 不含网络歌 |
| V7 | 关 NAS，首页统计 | ⭐ 口径：**运行时关** ⇒ 四项数字**保持关前值**（S2）；**冷启动时 NAS 已关** ⇒ 显示未加载态（0/—），不得出现「已加载后被扣减」的数字；下方轮播不出现 NAS 内容 |
| V8 | 关 NAS，首页「最近播放」 | 不含 NAS 歌；**数据库里的记录仍在**（重开后恢复，S3） |
| V9 | 关 NAS，曲库搜索 | 无 NAS 结果；「源点亮」里 NAS 与总开关为「与」 |
| **V9b** | **关百度，曲库搜索** | **无任何百度结果**（⚠️ **当前会漏**：聚合器直接持有实例，见 §2.1） |
| **V9c** | **关 Jamendo，曲库搜索** | **无任何 Jamendo 结果**（同上） |
| **V9d** | **关 Jamendo，进 Jamendo 页（热门 / 标签 / 站内搜索）** | 不请求、不显示（`loadJamendoHot` / `loadJamendoTag` / `searchJamendo` 直连入口） |
| **V9e** | 关 Meting，曲库搜索 | 无网络结果；`metingPart` 为空 ⇒ 艺人展开自动跳过（既有逻辑，确认未回退） |
| **V13** | **关 NAS，首页「智能电台」批次** | 不含 NAS 歌；本地 / 网络采样照旧 |
| **V14** | **关 NAS，曲库流派 / 年份过滤列表** | 不含 NAS 歌，本地照旧 |
| **V15** | **关百度，百度网盘目录「播放全部」** | 不请求、不播放 |
| **V17** | **关 NAS，进「我的」页** | ⭐ 新增：收藏列表不含 NAS 歌；NAS 歌单不列出 |
| **V18** | **关 NAS，曲库「播放全部」按钮** | ⭐ 新增：播放列表不含 NAS 歌（S4） |
| V10 | 关源后立刻看首页与曲库 | 无需切页/重启即可见（§3.5） |
| **V16** | **关 NAS 后重开** | ⭐ 新增：曲库恢复显示（`loadLibrary` + `loadYears` + `loadPlaylists`），流派/年份/歌单一并恢复，无需重启 |
| **V19** | **关 Meting 后重开** | ⭐ 新增：首页「随心听」网络段恢复（v2 遗漏点） |
| V11 | 全部关掉 | 各处显示「未启用任何音乐源」引导，而非「搜索无结果」（沿用既有 `noNetworkSourceEnabled` 文案） |
| V12 | 关源后播放队列里已有的该源歌曲 | 仍可播放（S4），不崩溃 |

### 7.2 工程

- `lintDebug` 0 error（不可引入新 error）
- 新增 `SourceVisibilityTest`：五源 × 开/关 × 边界（jamendo 有开关无 clientId、baidu 启用未登录）+ **`networkSource` 字符串 → `MusicSourceType` 映射对拍**（防未知字符串静默归入 NETWORK_MUSIC）
- 新增 `SourceGatingGateTest`：源码文本门，钉住「`updateMergedData` 里 nas/baidu 必须经可见性判定」「`visibleSongsPaging` 必须存在且 NAS 段受可见性控制」「聚合器五分支判据形状」等（参照既有 `NetworkSourceSwitchGateTest` 的做法，含负向自证）
- ⭐ **补钉 `nasMusicEnabled()` 在 `SearchAggregator`**（v2.39.8 已加门控但无测试守卫，对齐 `LocalMusicGateTest` L3 的做法）
- ⭐ **行为测试（需新建测试基建，v3 明确计入 P6）**：仓库当前**没有** fake `BackendAdapter`，也**没有**任何 `MainViewModel` 实例化的测试宿主（现有测试只 `mock<MainViewModel>()` 或读源码文本）⇒ P6 需先搭这两件基建，再写三条核心不变量：
  1. 翻转 NAS 关 ⇒ 歌曲 TAB 为空（`visibleSongsPaging` 不含 NAS 歌）；
  2. 翻转 NAS 关 ⇒ 首页统计数字不变；
  3. 翻转 NAS 开 ⇒ 曲库恢复（`visibleSongsPaging` 重新含 NAS 歌）。
  ⚠️ **行为测试为主，文本门为辅**——文本门易被字符串改动绕过；也不必把 V1–V19 全部写成自动化测试（人工真机复验覆盖，见 §7.3）。
- 既有全绿不得回退：`NetworkSourceSwitchGateTest`、`LocalMusicGateTest`、`SearchAggregator` 相关

### 7.3 真机

- 电视（192.168.0.114）与手机各跑一遍 V1–V19；重点看**关源瞬间是否闪烁**（`updateMergedData` 是全量重建，大曲库下可能有轻微跳变，需确认可接受）
- 重点复验**开关翻转后不切页**的效果：曲库 TAB、首页、「我的」页三处都要即时反映（这是 v2 遗留 N1/N2 的验收点）

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| `updateMergedData` 全量重建次数变多 | 大曲库（3 万+首）下轻微卡顿 | 只在**开关翻转时**额外触发一次，不在常规路径增加调用 |
| 原始流 / 派生流职责漂移（某处直接读原始流当可见集） | 界面有 NAS 条目但统计/详情错乱 | §3.0 契约写进代码评审口径；§3.3 处置表逐行对照；重点检查 `LibraryScreen` 播放全部 / `MineBranch` 收藏 / `LibraryBranch` 歌曲合并 |
| **派生流不随可见性重算**（v2 的 N1 根因） | 开关翻转后界面不更新 | §5.10：一切受可见性影响的派生数据挂 `sourceRevision`；G1b 已迁入 VM |
| **清缓存逻辑写成死代码**（v2 的 N2 根因） | 关源后旧内容残留 | §3.2.2 的 `clearSourceScopedCaches` 是**唯一**清理入口，由 `setSourceEnabled` 直接调用；⛔ 不依赖任何 `load*` 的副作用 |
| 重开路径遗漏 | 重开后空曲库 / 随心听空 | §3.5 重开分支按源显式重拉（NAS 三个入口 + Meting 一个）；V16/V19 |
| 归属判定与 `Song.sourceType` 漂移 | 界面有条目但播放解析失败 | **复用** `Song.sourceType`，⛔ 不新写；并加一条单测对拍（NAS / 本地 / 已下载 / 百度 / Jamendo / Meting 六种样本） |
| 百度「启用但未登录」的中间态 | 判定模糊 | `isVisible(BAIDU_PAN)` 用 `isActive`（启用 **且** 已登录），与 `registerService` 判据完全一致 |
| 用户误以为关源「删了歌」 | 投诉 | 设置页在 NAS / 本地卡已有文案；百度卡补一句「关闭后其内容不在曲库中显示，重新开启即恢复」 |
| 镜像冷启动竞态 | 开关在镜像同步前翻转，读到默认值 | §5.9：首帧延迟 + KDoc 注明窗口 |
| 文本门禁脆弱（改字符串绕过） | 门禁形同虚设 | 行为测试为主、文本门为辅（§7.2） |
| **测试基建缺口被低估** | P6 延期 | v3 已把 fake `BackendAdapter` + `MainViewModel` 测试宿主显式计入 P6（1.0 d）；若要压缩总工期，优先砍行为测试的覆盖范围到三条不变量（§7.2），而非砍基建 |

---

## 9. 落地检查清单（实施时逐项打勾）

- [ ] P0 `SourceVisibility` + `MusicSourceType` ↔ services key 映射（镜像已就绪）
- [ ] P0 单测：五源 × 开关矩阵 + `networkSource` 字符串映射对拍
- [ ] P1a G1 `updateMergedData`：`nasAlbums` / `nasArtists` / `baiduSongs` 三处置空
- [ ] P1a G1 `updateArtistSongCounts` **改签名**接收过滤后的 NAS 歌曲
- [ ] P1a G1 **新增 `visibleSongsPaging` 派生流**（`combine` + `sourceRevision`；`totalCount` 口径平移 `LibraryBranch.kt:69`，NAS 关时不计 NAS）
- [ ] P1a G1c 首页轮播/收藏区块 + `SmartRadioManager` 批次按源过滤
- [ ] P1a G2 `SearchAggregator` 补三个分支（百度 / Jamendo / NETWORK_MUSIC）+ 结果按 `song.sourceType` 过滤
- [ ] P1a `baiduIndexCache` 直接调用点加判据：`updateMergedData` / `updateArtistSongCounts` / `loadAlbumSongs` / `playAlbumMultiSource` / `loadArtistSongs` / `playAllNetdiskDir` / `collectAudioInDir` / 封面解析两处
- [ ] P1b `LibraryBranch` 改用 `visibleSongsPaging`（`remember` 退化为透传）
- [ ] P1b `LibraryBranch.kt:56` **停止下传原始 `songs`** ⇒ `LibraryScreen.playAllSongs`（`:436-457`）只见可见条目
- [ ] P1b 移除 `LibraryScreen.kt:773` `AlbumsTab(songs=)` **死参数**（`AlbumGrid.kt:48` 从不使用）
- [ ] P1b `MineBranch.kt:34/48` 收藏路径闭合（`_favoriteSongs` 置空 + P3 过滤）
- [ ] P1b `artistSongsMap` 重建（关 NAS 时去掉 NAS 歌）
- [ ] P2a `loadLibrary` / `loadArtists` / `loadYears` / `loadGenres` / `loadFavorites` / `loadPlaylists`
- [ ] P2a `loadRandomSongs` / `startShuffleRefill`
- [ ] P2a `loadHomeDashboard`（⚠️ **换数据源，不是整段跳过**；`totalPlaylists` 沿用上次值）
- [ ] P2a `loadSongsNextPage` / `loadAllSongsBackground`（含写回处再判，§5.7）/ `loadSongsFirstPage`
- [ ] P2a `loadAlbumSongs` / `playAlbumMultiSource` / `loadArtistSongs`
- [ ] P2a `MainViewModel` 的 `RemoteCallbacks.search`（不做 NAS 查询）
- [ ] P2a Jamendo 三入口：`loadJamendoHot` / `loadJamendoTag` / `searchJamendo`
- [ ] P2b `getSongsByGenre` / `getSongsByYearRange`
- [ ] P2b `selectPlaylist` / `playPlaylist`
- [ ] P2b `loadSmartRadioBatch` + **`SmartRadioManager` 注入 `nasMusicEnabled` provider**（§3.2.4）
- [ ] P2b `playAllNetdiskDir` / `collectAudioInDir`
- [ ] P2b `loadSongsFirstPage` 的 `totalCount` 关源期间不计 NAS
- [ ] P3 `recentSongs` / `favoriteSongs` / `HomeDashboardData.favoriteSongs` 按 `song.sourceType` 过滤（**过滤点定死在 VM 内**，UI 不重复 filter）
- [ ] P4 首页轮播（`recentlyAddedAlbums` 等）与智能电台批次按源过滤；**四项统计数字保持不变**（S2）
- [ ] P5 **关源**：`clearSourceScopedCaches(key)`（置空 `_genres`/`_years`/`_playlists`/`_favoriteSongs`/`_baiduIndexedSongs`，过滤 `_randomSongs`/`_favoriteIds`，重建 `artistSongsMap`）+ `updateMergedData` + `loadHomeDashboard` + `loadRecentSongs` + `bumpSourceRevision`
- [ ] P5 **重开**：NAS ⇒ `loadLibrary()` + `loadYears()` + `loadPlaylists()`；Meting ⇒ `loadRandomSongs()`；百度/Jamendo ⇒ 已有 `refresh*ServiceRegistration`
- [ ] P6 门禁测试（含 `nasMusicEnabled` 钉守）+ **新建 fake `BackendAdapter` 与 `MainViewModel` 测试宿主** + 三条行为不变量 + 全量回归

---

## 10. 实施记录（2026-10-29 · v2.39.9 落地）

本节记录**实际实现**与本方案（v3）的出入，供后续维护者对齐。详细的架构说明见 `docs/technical-overview.md` §10.277。

### 10.1 落地范围

| 阶段 | 状态 | 说明 |
|---|---|---|
| P0 `SourceVisibility` | ✅ | 新增 `backend/SourceVisibility.kt`；**改为「每源一个同步 lambda」**而非 `settings: () -> AppSettings`（`appSettings` 只有 Flow、无同步快照，构造期快照会让开关停在启动值） |
| P1a VM 侧合成 | ✅ | `visibleSongsPaging` 派生流 + `updateMergedData` 过滤 + `updateArtistSongCounts(nasSongs, baiduSongs)` + `SearchAggregator` 补三分支 |
| P1b UI 侧收口 | ✅ | `LibraryBranch` 改走 `visibleSongsPaging`、删除 UI 层合并；`AlbumsTab` 死参数移除 |
| P2a/P2b 采集期短路 | ✅ | 22 项全部落地（#20/#21 由前序 lane 完成） |
| P3 历史/收藏过滤 | ✅ | 过滤点定死在 VM（`loadFavorites` / `loadRecentSongs`），UI 侧不重复 filter |
| P4 首页/电台合成点 | ✅ | `loadHomeDashboard` 换数据源 + 电台批次过滤 |
| P5 双向刷新 | ✅ | `clearSourceScopedCaches` + 关源清缓存 / 重开按源重拉 |
| P6 门禁与行为测试 | ⚠️ **部分 descope** | 门禁测试 + 组合契约测试已落地；**未新建 fake `BackendAdapter` 与 `MainViewModel` 测试宿主**（见 §10.3） |

### 10.2 实施中确认并修正的 6 处偏离

1. **`remember` 的键必须包含「可见集本身」** —— §3.3 G1b 的示例若照抄 `remember(songsState)` 会命中缓存：`_songs` 是原始库存流、关源时**不清空** ⇒ 值不变 ⇒ 过滤结果仍是旧的 ⇒ V18/S4 依然违规。实际实现用 `remember(songsState, mergedSongsPaging)`（`mergedSongsPaging` 来自 VM 派生流，开关翻转时必定发射新值）。
2. **本地段不走 `isVisible(LOCAL)`** —— 本地开关语义是「关 = 只显示已下载」（§5.6 红线），本地段沿用 `visibleLocalSongsNow()`。
3. **本地开关镜像放在 `NasMusicApp` 而非 `AppPreferences`** —— `AppPreferences` 无 `getLocalMusicEnabledSync()`，新增会与 `ProviderMirrorWiringGateTest` G2 的约定冲突；改用应用级 `@Volatile` 镜像 + **单一写入者**（`MainViewModel` 的本地开关观察者），启动期按 `NasMusicApp` 既有惯例用 DataStore 直读播种。
4. **`SmartRadioManager` 增加缓存可见态** —— 曲库缓存原本一次会话内永久复用；关 NAS 后缓存无 NAS 样本，NAS 重开若复用旧缓存则电台永远补不回 NAS 歌 ⇒ 可见态一变即视为缓存失效并重建。
5. **`loadAlbumSongs` 与 `playAlbumMultiSource` 抽共用私有 helper** —— 保证两条路径的门控判据逐字一致。
6. **Jamendo 三入口额外清空 `_jamendoState`** —— 只短路不清理的话，V9d 的「不显示」不成立。
7. **既有门禁 `LibrarySongsSourceWiringGateTest` 随链条迁移改写** —— 它钉「歌曲 Tab 必须含三源」，原断言点在 `LibraryBranch` 的合并块上；合并迁入 VM 后必然失败（全量单测实测 `2600 tests completed, 1 failed`）。改钉到新链条（`visibleSongsPaging` 的三个输入 + `mergeLibrarySongs` 的三个实参 + `totalCount` 计入本地/百度 + `LibraryBranch` 只做透传且不得出现第二处合并），**被保护的不变量不变**，六条负向自证全部保留并按新链条重写。

### 10.3 唯一 descope 项：P6 的行为测试宿主

方案 §7.2 原计划「新建 fake `BackendAdapter` + `MainViewModel` 测试宿主」并写三条行为不变量。实际落地改为：
- **门禁测试**（源码文本门，照 `NetworkSourceSwitchGateTest` / `LocalMusicGateTest` 的既有做法，含负向自证）——钉住 `SearchAggregator` 的五个分支判据、VM 侧 `visibleSongsPaging` / `sourceRevision` / `clearSourceScopedCaches`、UI 侧不得再出现 `mergeLibrarySongs`、以及**原始库存流不得被清空**的负向断言。
- **组合契约测试**（纯 JVM，用 `SourceVisibility` + `MusicMerger` 直接验证三条不变量，不构造 `MainViewModel`）。

**理由**：`MainViewModel` 的构造依赖（Application / DataStore / Room / Robolectric）极重，新建测试宿主的工作量与回归风险都显著高于其收益；且本次门禁已把「形状」钉住，行为由真机复验（§7.3 的 V1–V19）覆盖。**这是有意缩小范围，不是遗漏。**

### 10.4 遗留

1. 百度关源时复用「未登录」提示文案（实际是「已登录但关了源」），影响极小，未新增翻译资源。
2. 关源瞬间停在专辑 / 艺术家详情页时，`_albumSongsCache` / `_artistDetailSongsCache` 的旧内容会短暂可见（这两个缓存不在 §3.3 处置表内）。
3. `clearSourceScopedCaches` 只在 `setSourceEnabled` 这一个入口被调用 —— 绕过它直接改 `appSettings.nasMusicEnabled` 会导致清缓存不发生。
