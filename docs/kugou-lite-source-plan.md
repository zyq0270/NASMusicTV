# 酷狗音乐概念版（KuGouMusicApi）作为数据源 · 可行性评估与开发方案

> 状态：**评估方案，未开工**（2026-10-31）· 分支：`main`（公开仓库）
> 需求原文：「评估是否可以增加一个数据源：酷狗概念版 API」
> 关联文档：`docs/archive/lx-music-source-plan.md`（洛雪音源，✅ v2.39.8 已落地）| `docs/archive/radio-and-jamendo-source-plan.md`（新源方案体例先例）| `docs/archive/music-source-gating-plan.md`（源开关全覆盖）| `docs/technical-overview.md` §10.276（洛雪落地）· §10.277（源开关全覆盖）· §10.229（电视端证书与端点实测表）
> 上游项目：<https://github.com/MakcRe/KuGouMusicApi>

---

## 0. 结论先行

1. **技术上可以加，但不是「必须加」**。`NetworkMusicService` 这条抽象已经把接入成本压得很低：13 个成员里**只有 4 个必须实装**（`search` `:31`、`resolvePlayUrl` `:75`、`resolveLyrics` `:106`、`getPlaylist` `:134`），其余 8 个都有默认实现（`backend/network/NetworkMusicService.kt:18-135`）；路由 / 直链缓存 / 档位降级 / 失败替换链全部现成复用（§5）。真正的成本在**取证 + 接线全覆盖 + UI + 测试 + 长期跟随上游变更**。
2. **本仓已经有三条酷狗通路，而且第三条是 v2.39.8（2026-10-28）刚交付的**（细分是 **7 条独立通路**：1 条多平台 Meting + 5 个洛雪脚本 + 1 条官方歌词直连，见附录 C 矩阵——酷狗是五个平台里支撑最厚的一个）：
   - Meting 的 `server=kugou` 平台档（`MusicSource.KUGOU("kugou", "酷狗")`，`data/model/MusicSource.kt:12`）；
   - 洛雪 `kg` 通道的酷狗取链增强（`backend/network/lx/LxPlatformMap.kt:18` 的 `"kugou" to ("kg" to "hash")`）；
   - 酷狗官方**歌词**直连（`lyrics/LyricsNetworkProvider.kt:39-40` 的 `mobilecdn.kugou.com` + `krcs.kugou.com`，含 KRC→LRC 解码）。
   ⛔ **任何方案都必须先回答「相对这三条多出了什么」**，否则就是第四套酷狗链路。
3. **上游的形态是「面向酷狗官方接口已改写的 Node 代理」**：MIT 许可、Node 12+、132 个端点模块，靠 `.env` 的 `platform=lite` 切到概念版，部署形态是本地 `npm run dev` / Docker / Vercel。直接把它当数据源 = **要求电视用户自己部署一个 Node 服务**，这是本项目现有全部源里最重的接入前提。
4. **它在「取链」上基本没有增量、甚至是倒退**：概念版对外宣称 ≤320kbps（来自需求描述，⚠️ 未取证），而**洛雪 `kg` 实测已是真 FLAC**——墨澜 / 星海 / 星澜 三家对 `kg` 返回真 FLAC，1010 / HYW 返回 MP3（`docs/archive/lx-music-source-plan.md` §2.2，2026-10-28 实测表）。档位表上 320kbps 只是 `QualityTiers.HIGH`（`backend/network/QualityTiers.kt:29`），低于已在用的 `LOSSLESS=999`。
5. **真正独占的价值是酷狗官方目录**：榜单 / 每日推荐 / 私人 FM / 艺人作品与专辑 / 歌单 / MV / 评论。这些是 Meting 与洛雪两条通路都给不了的（洛雪的搜索与榜单都在服务端做完，客户端只发 `musicUrl`，§2.2 取证）。⚠️ 但**本仓对「网络源的榜单/推荐形态」没有任何消费方**（`NetworkMusicService.getPlaylist` 是 135 行接口里最薄的一格），做目录就得连 UI 一起做。
6. **推荐路线：先做 S0 一天的取证探针，再在 A / B / C 三条里择优**（§4）。其中 **A = 不移植 Node 服务，照上游的口径用 Kotlin 原生直连酷狗官方匿名接口**——已取证到「搜索匿名可用」「取链模块不读登录 token」（§2.1 / §2.2），这条如果跑通，收益/成本比远好于自建服务。
7. **法律与合规需要所有者明确裁决**：上游 MIT 代码移植进 GPL v3 本仓在许可上可行（需保留版权声明），但 MIT 授予的是**代码**许可，不授予访问酷狗服务端权利的许可；上游自带「本项目仅供学习使用」声明，而**本仓库是 PUBLIC**（2026-10-23 转公开）。这与 E51/E52「无授权移植件只留本地分支」是**不同类别**（那批连许可证都没有），但同一类风险敞口，需按既有口径确认（§10）。
8. **验收口径有三条硬红线**：① 入口一律常显、失败可见，⛔ 不做能力探测式隐藏（自建服务没起时不能让用户找不到入口）；② 单测不探端点连通性，URL 只当字符串数据处理（MockWebServer / 假域名）；③ 若新取链通道把现有 `kg` FLAC 降为 320k，就是**净倒退**，必须以「档位不下降」为门禁。

---

## 1. 背景与目标

### 1.1 现状（已复核到代码位）

酷狗在本仓**不是一张空位**，而是三条已上线的通路：

| # | 通路 | 干什么用 | 代码位（main） | 实测状态 |
|---|---|---|---|---|
| 1 | Meting `server=kugou` | 搜索 + 302 取链 | `backend/network/MetingApiService.kt:148-152`（三预设端点）、`:161-164`（`NETEASE_ONLY_ENDPOINTS`） | ⚠️ **只有 qijieya 端点支持 kugou**，mikus / redcha 对 `kg`/`tx` 直接回 400；且 mikus 用的是 Let's Encrypt 证书，**创维电视（API 22）不信任该链**——`docs/technical-overview.md` §10.229 实测表 |
| 2 | 洛雪 `kg` 通道 | 把 Meting 解析直链升级成「先问脚本」 | `lx/LxPlatformMap.kt:18`、`lx/LxPlayUrlEnhancer.kt:17-19`（`interface PlayUrlEnhancer { suspend fun tryResolve(song, quality): ResolveResult? }`） | ✅ 5 个源实测可播，`kg` 能出**真 FLAC**（lx 方案 §2.2）；`lxEnabled` 默认 false（`data/model/AppSettings.kt:78`） |
| 3 | 酷狗歌词直连 | 歌词匹配 | `lyrics/LyricsNetworkProvider.kt:39`（`http://mobilecdn.kugou.com`，注释 `:37` 明写 HTTPS 证书 hostname 不匹配所以走 http）、`:40`（`https://krcs.kugou.com`） | ✅ 在用；用户可在设置里覆盖基址（`AppSettings.kt:84 lyricsKugouBaseUrl`） |

**结论**：本 app 当前**已经能搜到酷狗的歌、能取到酷狗的直链、能拿到酷狗的歌词**，缺的是「酷狗自己的目录能力（榜单/推荐/艺人/歌单/MV）」与「不依赖社区洛雪源的稳定取链」。

### 1.2 目标

- **G1** 给出「能不能加 / 该不该加」的可核验判断，而不是一句「可以」。
- **G2** 若做，明确接入层级、必须同步的接线点全集、UI 落位与验收门禁，使工程阶段可直接据此开工。
- **G3** 优先方案应当**不新增用户部署负担**（这也是 A 路线相对 B 路线的核心优势）。
- **G4** 保持既有不变量：源开关全覆盖、未知 `networkSource` 不得静默串味、档位不下降。

### 1.3 非目标

- ❌ **不实现酷狗账号登录体系**（密码 / 验证码 / 扫码 / token 刷新）——132 个端点里登录类占了 13 个模块，且需要在本机长期保管第三方账号凭据，是独立的一整期工程（§7.2）。
- ❌ **不下载/转码/分发音频**，不碰版权红线。
- ❌ **不为了酷狗重构 `NetworkMusicManager`**——抽象已经够用。
- ❌ 不做 VIP 相关能力（上游「领取 VIP」这类接口不在评估范围，见 §10 合规）。

---

## 2. 上游事实取证（2026-10-31）

### 2.1 取证条件（⛔ 先读这段再引用下面的结论）

- **本机直连 GitHub raw 被网络拦截**：`curl` 到 `raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 均为 `code=000`（TLS `Recv failure: Connection was aborted`），带 `127.0.0.1:31181` / `7890` 代理同样失败。
- 因此下列结论来自**仓库页与 blob 页的抽取摘要**，⛔ **没有逐字节读源码**，也**没有实跑过任何一个端点**。⇒ 全部标为「待 S0 复核」，不得当既成事实写进代码注释。
- 需要 verbatim 的部分（签名参数拼装顺序、响应字段名、错误码语义）在 S0 里用可复现的 `curl` 重新取证，并把结果落到 `docs/archive/verification/`。

### 2.2 取证到的事实

| 项 | 内容 | 来源 |
|---|---|---|
| 形态 | Node.js 12+ 的 API 代理，132 个端点模块（`module/*.js` 一文件一端点），`npm run dev` 默认 3000 端口 | 仓库 README |
| 许可 | **MIT**；README 同时声明「本项目仅供学习使用……禁止在违反当地法律法规的情况下使用本项目」 | 仓库 README / LICENSE |
| 概念版开关 | `.env` 的 `platform`：空 = 手机版，`lite` = 酷狗概念版；README 注明不同平台的 token 不通用 | `.env.example` |
| 其他环境项 | `PORT`、`KUGOU_API_PROXY`（HTTP 代理）、`KUGOU_API_GUID` / `_DEV` / `_MAC` / `_WEBGL`（设备指纹，注释建议 uuidv4 / 10 位串 / 固定 webgl 指纹） | `.env.example` |
| 搜索 | `complexsearch.kugou.com` 的 `/v2/search/song`（及 `/v1/search/{type}`），参数 `keyword` / `page` / `pagesize` / `platform` / `tag`；**匿名可用**（摘要未显示需要 token） | `module/search.js` |
| 取链 | `trackercdn.kugou.com`，路径含 `/v5/url`；参数 `hash`、`album_id`、`quality`（映射为 `magic_*` 形态）、`page_id`/`ppage_id`/`pid`（来自 lite 配置常量）、`dfid`（随机 24 位）；**摘要显示该模块不读本地登录 token** | `module/song_url.js` |
| 加密件 | `util/crypto.js` 有 `cryptoAesEncrypt` / `cryptoRSAEncrypt` / `playlistAesDecrypt`，含两枚硬编码 RSA 公钥、用 MD5 派生密钥；用途更像**登录口令加密与歌单解密**，⛔ 摘要里没出现「取链签名」逻辑 | `util/crypto.js` |
| 部署 | 本地 Node / Docker / Vercel（fork 后导入，设 `platform=lite`） | 仓库 README |

### 2.3 ⚠️ 未取证清单（S0 必须逐条补上）

1. 概念版 `lite` 对**未登录**客户端实际开放到哪个码率（320k？128k？会不会直接回空 `url`）。
2. `magic_*` 质量串与 `QualityTiers`（`AUTO/128/192/320/999`，`backend/network/QualityTiers.kt:20-32`）的对应关系，以及请求 999 时的真实行为。
3. 搜索结果的**主键字段**：酷狗侧 `hash` 是否就是洛雪 `kg` 用的那个 32 位 hex（lx 方案 §2.4 实测 qijieya 搜索回的 `id=` 是 32 位 hex，映射为 `kg`/`hash`）。
4. 取链返回的 URL 是否带 `vkey`/`timestamp` 一类时效参数、有效期多长（直接影响 5 分钟直链缓存是否够用，`NetworkMusicManager.kt:40`）。
5. 风控与限速：单 IP / 单设备指纹的 QPS、`dfid` 随机化会不会触发拦截。
6. 端点是否需要 `User-Agent` 伪装、是否需要 Referer（上游 README 自称靠 "CSRF 与 header 伪装"）。
7. Vercel / 公共镜像形态的可用性（本项目已有「公共镜像随时集体失效」的教训：`docs/technical-overview.md` §10.229 三预设端点各以一种方式失效）。

---

## 3. 与现有三条酷狗通路的重复度

| 能力 | 现在 | 酷狗概念版路线能给 | 增量判定 |
|---|---|---|---|
| 搜索 | Meting `server=kugou`（仅 qijieya 端点可用） | 官方匿名搜索，无端点依赖 | 🟡 **稳定性增量**，非能力增量 |
| 取链 | 洛雪 `kg`，实测真 FLAC | ≤320k（待取证） | 🔴 **负增量**（档位可能下降） |
| 歌词 | 已有官方直连 + KRC 解码 | 同一能力 | ⛔ **零增量**，重复建设 |
| 封面 | 由 Meting 搜索结果 `pic` 端点走 Coil 302 | 官方图片端点 | 🟡 小增量 |
| 榜单 / 每日推荐 / 私人 FM | **无** | ✅ 有 | 🟢 **独占增量** |
| 艺人专辑 / 艺人作品目录 | 网络侧只有「艺人发现 + 作品分页」契约（`NetworkMusicService.kt:57/:64`），Meting 实装 | 更完整的官方目录 | 🟡 中增量 |
| 歌单 | 契约有（`:134 getPlaylist`），实现薄 | ✅ 官方歌单全集 | 🟡 中增量 |
| MV | 已有独立 `mv/` 子包走 Bilibili + 自定义端点（`ui/screens/settings/NetworkMusicSection.kt:163-169`） | 酷狗 MV | 🟡 需与既有 MV 源合并考虑 |

> 📌 一句话：**如果只做「搜索 + 取链」，这份方案不值得开工**；它的立项理由只能是「酷狗官方目录」或「不依赖社区源的稳定取链」，两者必须选一个并写进验收标准。
> 📊 本节是**逐通路定性**；把 5 个平台 × 12 条通路铺开成矩阵、并算出各平台通路数的统计，见**附录 C**（结论：酷狗 7 条最厚、酷我/咪咕各 1 条最薄 ⇒ 新源加在酷狗上边际收益最低）。

---

## 4. 三条路线

### A. Kotlin 原生直连酷狗官方匿名接口（**推荐先验证这条**）

不移植 Node 服务，只把上游 `module/search.js` / `module/song_url.js` 的**参数口径**用 Kotlin 重写，接成 `NetworkMusicService`。

- ✅ **用户零部署**（与 Jamendo / Meting 公共端点同级的使用门槛）；
- ✅ 复用既有 OkHttp + Gson + 302 处理 + 验活探针链路（`PlayabilityProbe`，Meting 已用）；
- ✅ 无新增运行时依赖（不引 QuickJS、不起本地服务、不引 Node）；
- ⚠️ 前提：概念版取链**确实无需登录 token**（§2.3-1 待取证）；若需要，路线 A 当场降级为 B；
- ⛔ 代价：签名/参数跟着酷狗改版走，**维护责任从上游社区转到本仓**（上游一改我们就要重取一次证）。

### B. 自建 KuGouMusicApi 服务 + App 做 HTTP 客户端

用户在 NAS / Docker / Vercel 起服务，App 里填 baseUrl。

- ✅ 一次覆盖 132 端点，上游跟随官方变更；
- ✅ 端点契约稳定（就是本仓 Meting 自定义端点的同一套使用习惯，先例见 `NetworkMusicSection.kt:119-125` 的「自定义端点」输入）；
- ⛔ **部署门槛是本项目迄今最高**：目标用户是电视 + 家庭 NAS；
- ⛔ `.env` 里必须钉死 `platform=lite`，不同 `platform` 的 token 不通用 ⇒ **用户配错就是"看起来能连、什么都取不到"**，这类状态必须自诊断上屏（§12.3）；
- ⚠️ **电视证书陷阱**：Vercel 形态是 Let's Encrypt 证书链，创维（API 22）的信任库里⛔ 没有 `ISRG Root X1` 也没有 `DST Root CA X3`，实测 `--cacert tv_bundle` 直接 exit 60（`docs/technical-overview.md` §10.229）。⇒ 自建服务在电视侧要么走 **http**（`usesCleartextTraffic=true` 已全局放开，局域网可接受），要么用用户自己的可信证书，⛔ 不要把 Vercel 当默认推荐。

### C. 不新增源

- ✅ 成本为零，零合规敞口；
- 若所有者判定「酷狗目录能力不在路线图上」，**这是合法默认选项**；本文档按 `docs/archive/` 的生命周期规则归档（结论是 ❌ 永久放弃，不是 ⏳ 未排期）。

### 决策树

```
S0 探针（1 天，一次性取证脚本）
  ├─ 匿名取链成功 && 码率 ≥ 320k ──→ A：原生直连，定位为「酷狗取链兜底 + 官方搜索」
  ├─ 匿名取链成功但只有 128k ──────→ A′：只做目录与搜索，⛔ 不接取链（保住 kg FLAC）
  ├─ 必须登录 token ───────────────→ B 或 C（自建服务的部署门槛值不值得，由所有者裁）
  └─ 风控/不稳定 ─────────────────→ C，并把探针结论写进 technical-overview
```

---

## 5. 架构落位（若走 A 或 B）

新源**注册为 `NetworkMusicService`**，不写进 Meting 内部：

- `backend/network/kugou/KugouLiteService.kt`（新子包，对齐 `baidu/`、`lx/`、`mv/`、`netease/` 的既有分层习惯）；
- `sourceId` 用新常量，仿 `NetworkSongId.METING_SOURCE`（`backend/network/NetworkSongId.kt:20`）把主键构造收在唯一位置（`forNetworkSong(sourceId, netId)`，`:23`）；
- 路由 / 直链缓存 / 降级全部免费复用：`NetworkMusicManager` 的 `registerService`（`:411`）、`unregisterService`（`:419`）、`availableSources`（`:405`）、`resolvePlayUrlWithCrossSourceFallback`（`:466`）、`resolveSameSourceReplacement`（`:539`）；缓存键 `networkSource:networkId:quality`（`:84-85`）、TTL 5 分钟（`:40`）、容量 500（`:42`）。

> ⚠️ **`sourceId` 不要用裸 `"kugou"`**。`"kugou"` 这个词在本仓**已经有两个含义**：Meting 的平台名（`MusicSource.kt:12` 的 `apiKey`，落在 `AppPreferences.getMusicSourceSync()`）与洛雪的 source 短名 `kg`（`LxPlatformMap.kt:18`）。新的**服务注册键**若再叫 `"kugou"`，会让「平台」与「服务」两层语义挤进同一个字符串，而这两层分别有各自的路由代码（服务键走 `services` map，平台名走 `serverProvider`）。建议 `SOURCE_KUGOU_LITE = "kugou-lite"`，与 `AppPreferences.kt:112-118` 现有四个常量同处声明。

**为什么不做成洛雪那样的「增强层」**：`PlayUrlEnhancer`（`lx/LxPlayUrlEnhancer.kt:17-19`）的语义是「同一首歌再试一次取链」；本方案若要的是官方目录（榜单/推荐/艺人），增强层承载不了，必须是注册源。⚠️ 但**如果 S0 的结论是「只做取链兜底」**，那么正确落位反而是 `PlayUrlEnhancer` 的第二实现（`MetingApiService` 的 `resolvePlayUrlDetailed` 已经给了插入口，`:417-427`），成本立刻降一档。⇒ 这是 S0 之后的第二个分叉，值得单独裁。

---

## 6. 必须同步的接线点全集

⛔ 漏任何一条都不会编译报错，只会**静默串味**——`SourceVisibility.kt` 的 KDoc 把这条写成了红线：「任何未知的 `networkSource` 都会落到 `isNetworkSong -> MusicSourceType.NETWORK_MUSIC` 分支 ⇒ 未来的独立网络源会莫名受 Meting 开关影响（`when` 对枚举穷尽 ⇒ 不会编译报错）」。

| # | 位置（main） | 改什么 | 漏掉的后果 |
|---|---|---|---|
| 1 | `data/model/SourceIdentifier.kt:51` 起的 `val Song.sourceType` | 新增 `networkSource == "kugou-lite"` 分支 | 新源歌曲被算成 NETWORK_MUSIC ⇒ 受 Meting 开关支配，关掉 Meting 就把酷狗独立源一起藏了 |
| 2 | `backend/SourceVisibility.kt:46` 构造参数 + 归属判定 | 加一个 `kugouLiteEnabled: () -> Boolean` 同步 lambda（⛔ 不许构造期快照，KDoc 已记该缺陷） | 设置页开关翻转不生效 |
| 3 | `data/prefs/AppPreferences.kt:112-125` | 新 `SOURCE_KUGOU_LITE` 常量并入 `NETWORK_SOURCE_PRIORITY`；`:1364-1370` 同步判据分发加一支 | 默认源自愈逻辑（`NasMusicApp.kt` 的按优先级回退）不认识新源 |
| 4 | `data/model/AppSettings.kt`（main 现状：`:51 defaultNetworkSource` / `:58 metingEnabled` / `:71 jamendoEnabled` / `:78 lxEnabled` / `:84 lyricsKugouBaseUrl`） | 新增 `kugouLiteEnabled`（默认 **false**，与 `lxEnabled` 同为新源惯例）+ baseUrl / 设备标识配置项 | 开关无处持久化 |
| 5 | `NasMusicApp.kt:521-561` 装配段 + `:781-802 refresh*ServiceRegistration` | 构造服务、启动期**按开关决定初始 map**（⛔ 不许"先全注册再注销"，该处注释已明写）、新增 `refreshKugouLiteRegistration()` | 开关与注册状态漂移 |
| 6 | `ui/viewmodel/MainViewModel.kt:791 setSourceEnabled` 的 `when` 分支 | 加一支新源 | 设置页拨了没反应 |
| 7 | `backend/download/StreamUrlResolver.kt:87-94 sourceTypeOf` | 加一支 `networkSource == SOURCE -> "KUGOU_LITE"` | 下载落库分类错，UI 分组与「已下载优先」判定失真 |
| 8 | `backend/SearchAggregator.kt:58`（五源并行：NAS 15s / 网络 8s / 百度 8s / Jamendo 5s / 本地 2s，`:92-100`） | 归属决定：并入 NETWORK_MUSIC 分支可不动；单列分支必须给子预算 | 统一搜索超时预算被拖长 |
| 9 | `ui/screens/settings/SourcesSettingsSection.kt`（SOURCES 卡） | 源行 + 开关 + 自建服务态时加「自定义端点」输入，仿 `NetworkMusicSection.kt:119-125` 的 Meting 自定义端点 | 用户无法启用/配置 |
| 10 | `values/strings.xml` + `values-en/strings.xml` **双份** | 全部新文案 | 编译过、**渲染时崩**（既有护栏 `VisualizerThemeStringsGateTest` 钉的就是这一类） |
| 11 | 语言镜像 / 源接线门禁 | `LanguageMirrorTest`、`ProviderMirrorWiringGateTest`（源开关全覆盖那期立的）必须跑绿 | 全覆盖红线被破 |

---

## 7. 能力映射与取舍

### 7.1 132 端点 → 本项目契约（第一期只碰这 4 个）

| `NetworkMusicService` 方法 | 对应上游模块 | 第一期是否做 |
|---|---|---|
| `search(keyword, limit)` `:31` | `module/search.js`（匿名、分页参数已取证） | ✅ |
| `resolvePlayUrlDetailed(song, quality)` `:96` | `module/song_url.js`（`hash` + 质量映射） | ⚠️ 仅当 §2.3-1/2 取证显示「码率不下降」 |
| `resolveLyrics(song)` `:106` | `module/lyric.js` | ⛔ **不做**——已有官方直连更成熟（§1.1 通路 3） |
| `resolveCoverUrl / searchCoverUrl` `:115/:125` | `module/images.js` | 🟡 顺手可做，非必需 |
| `findArtist / getArtistSongs` `:57/:64` | `artist_audios*.js` / `artist_albums.js` | 🟢 第二期（真增量） |
| `getPlaylist(playlistId)` `:134` | `playlist_track_all*.js` | 🟢 第二期 |
| 榜单 / 每日推荐 / 私人 FM | `everyday_recommend.js` / `fm_songs.js` / 榜单模块 | 🟢 第二期，⛔ **需要先有消费方 UI** |
| MV / 评论 / 一起听 / AI 推荐 / 车载音效 | `kmr_audio_mv.js`、`comment_*.js`、`listen_together_*.js`、`ai_recommend*.js`、`effects_car_*.js` | ❌ 明确非目标 |

### 7.2 为什么第一期不碰登录

上游登录侧有 13 个模块（`login.js`、`login_cellphone.js`、`login_qr_*`、`login_wx_*`、`login_token.js`、`captcha_sent.js`、`get_verify_info.js`…），`util/crypto.js` 的 AES/RSA 正是为其服务。接登录意味着：本仓要**长期保管第三方账号凭据**（现有先例只有 NAS 后端凭据 AES-GCM 加密与百度 OAuth），要处理验证码、token 过期、设备指纹漂移，还要在电视这个输入极其难受的设备上做扫码授权。⛔ 第一期不做，且**不允许**「为了取到 320k 顺手加个登录」这种范围蔓延。

---

## 8. 数据模型 / 主键 / 音质

- **主键**：`Song.id` 必须走 `NetworkSongId.forNetworkSong(sourceId, netId)`（`NetworkSongId.kt:23`）。⛔ 不得复用 Meting 的 `ntwk_meting_<hash>` 作键——注册键精确路由**不 fallback**（`NetworkMusicManager.kt:16` 的总述 + `:210 resolvePlayUrlDetailed`），同 id 跨源会让失败替换链在同一首歌上原地打转。
- **跨通路对齐**：酷狗 `hash` 与 Meting-kugou 的 `id`、洛雪 `kg` 的 `musicInfo.hash` 是否同一个 id 空间，是 §2.3-3 的取证项。若同源同键，则「A 源搜到、洛雪取链、新源兜底」可以互补；若不同键，第一期就**不要宣称**跨通路兜底。
- **音质**：档位以 `QualityTiers`（`:20-32`）为唯一真相源，降级链走 `fallbackChainOf`（`:85`，999→320→192→128）。⛔ 概念版档位不得进 `LOSSLESS=999`；UI 上如果实际只拿到 320k，必须让 `ResolveResult.actualQuality` 如实回传降级信号（接口 KDoc `:88-97` 就是这个语义）。

---

## 9. 配置与 UI 落位

1. **SOURCES 卡新增一行**「酷狗概念版」开关，默认关闭；与洛雪行的关系要一句话说清（谁取链、谁兜底），`lx` 子块底部已有「端点残废」灰字说明的先例（`MetingApiService.kt:166-170 isNeteaseOnlyEndpoint` 就是为它服务的）。
2. **路线 B 才需要 baseUrl**：形态照「自定义端点」（`NetworkMusicSection.kt:119-125`），并带风险警示。
3. ⛔ **入口一律常显**（所有者既有裁决：能力探测的假阴性会让功能在用户眼里"不存在"）。服务没起/token 过期时用**可见的错误态**表达（照百度 `BaiduConnectionState.Failed` 的消息展示先例，`SourcesSettingsSection.kt:242-253`），不要用「探测失败就隐藏该行」来处理。
4. **文案长度约束**：说明文案 ≤26 字（设置页既定的排版约束）。

---

## 10. 合规评估

| 维度 | 判定 |
|---|---|
| **上游代码许可** | MIT ⇒ 与 GPL v3 **兼容**，移植需保留版权声明与许可全文。这一点**与 E51/E52 不同类**（那批是无任何授权的翻译件，故只留本地分支、永不推送）。 |
| **服务端访问** | ⚠️ 非官方接口、靠 header 伪装绕过，上游自带「仅供学习使用」声明。MIT 授的是代码许可，⛔ 不授访问服务端/再分发内容的权利。 |
| **仓库公开** | 本仓 2026-10-23 已转 **PUBLIC** ⇒ 新增代码直接进公开历史。**这是本方案最需要所有者裁的一条**：同类先例已经存在（Meting 公共端点、洛雪社区源、酷狗歌词直连、Bilibili MV），所以本方案**不是引入新类别的风险**，而是**在同一类别上加一格**。 |
| **内容边界** | 只做「取直链交给 ExoPlayer 播」与目录展示，⛔ 不下载第三方版权音频入库、不分发、不做「VIP 破解」类接口（§1.3）。 |
| **凭据** | 第一期不含登录 ⇒ 不引入第三方账号凭据存储；若日后接登录，必须先定凭据方案（照 AES-GCM 既有基线）。 |

---

## 11. 测试与验收

### 11.1 ⛔ 不写连通性测试

既有口径：URL 只当**字符串数据**处理，用假域名 / `MockWebServer` / mock `OkHttpClient`；端点连通性属于生产功能，不进单测。⇒ 本文 §2.3 的所有取证一律走**仓库外的脚本**（放 `scripts/e52` 同级的新 `scripts/kugou/`，跑完把结果落到 `docs/archive/verification/`），⛔ 不落到 `*Test.kt` 里。

### 11.2 门禁清单

| 门禁 | 断言 | 依据 |
|---|---|---|
| 接线全覆盖 | 断言新源在 `SourceVisibility` / `SourceIdentifier` / `AppPreferences.NETWORK_SOURCE_PRIORITY` / `setSourceEnabled` / `StreamUrlResolver.sourceTypeOf` 五处**全覆盖**，漏一处即红 | 落在既有 `NetworkSourceSwitchGateTest` 的矩阵里（见下一行），⛔ 不另起独立测试类 |
| 主键契约 | `id` 必须由 `NetworkSongId.forNetworkSong` 生成，且 `sourceId != "meting"` | `NetworkSongId.kt` 红线 1/2 |
| 档位不下降 | 对同一首 `kg` 歌，启用新源后的 `actualQuality` **不得低于**洛雪通道的实测档位 | 本文 §3 表 + `QualityTiers.kt:20-32` |
| `LanguageMirrorTest` | 中英文案齐备 | 既有 |
| `VisualizerThemeStringsGateTest` 同类教训 | `values-en` 缺 key 编译不报错、渲染才崩 ⇒ 新增字符串必须有镜像测试 | AGENTS.md 明载 |
| 源开关全覆盖 | ⛔ **优先扩既有门禁**：main 上已有 `data/prefs/NetworkSourceSwitchGateTest.kt` + `ProviderMirrorWiringGateTest.kt` + `LanguageMirrorTest.kt`，新源必须**加进这三张表**而不是另起一个测试类 | v2.39.9「源开关全覆盖」（`docs/technical-overview.md` §10.277）；新写的独立门禁会漏掉既有源 |
| 全量单测 | `testDebugUnitTest --rerun-tasks` 零失败（近期基线 **236 类 / 2428 例**，`docs/technical-overview.md:17344`；⚠️ `AGENTS.md` 构建段写的 184 类 / 2035 例是 2026-10-11 的旧数，增量跑不重执行测试 ⇒ 验数必须带 `--rerun-tasks`） | 既有 |

### 11.3 自诊断上屏（所有者既有要求）

失败不能只留 logcat：新源必须把「**为什么没取到**」写到用户屏上——服务地址不可达 / 返回非 JSON / hash 取不到直链 / 风控码 / 档位被降，每类一句人话 + 一个「验证」按钮（照洛雪 §5.4.1 的 L1 能加载 / L2 能取链 / L3 能播三级判据，UI 先例 `SourcesSettingsSection.kt:394 LxScriptsSubBlock`）。

---

## 12. 分阶段实施

| 阶段 | 内容 | 产出 | 成本 |
|---|---|---|---|
| **S0** | **取证探针**（决策门，不可跳）：§2.3 七项逐条实测——lite 码率、`magic_*` 映射、hash 是否同 id 空间、URL 时效、UA/header 依赖、风控表现 | `scripts/kugou/probe.sh` + `docs/archive/verification/kugou_lite_s0.txt` | **1 天** |
| **P1** | 最小可用：`KugouLiteService` 实现 `sourceId` / `search` / `resolvePlayUrlDetailed`（或退化为 `PlayUrlEnhancer` 第二实现，见 §5 末），§6 接线点 **1–7 全覆盖**，设置页一行开关，门禁 §11.2 全绿 | 可开关的搜索/取链通路 | 3–5 天 |
| **P2** | 目录消费方 UI：榜单 + 每日推荐 + 艺人作品（`findArtist` / `getArtistSongs`）+ 歌单 | 网络音乐页新子 Tab（照 `radio-and-jamendo-source-plan.md` §2.4 / §3.4 的子 Tab 体例） | 5–8 天 |
| **P3** | 稳定性：健康状态 / 失效退避（照 `LxSourceRegistry` 的「连败 3 次停 5 分钟」）、验活探针复用 `PlayabilityProbe`、负面缓存 | 长期可用性 | 3–5 天 |
| — | 文档同步：`CHANGELOG.md` 一行「做了什么」（⛔ 不写根因与实现细节）、`docs/technical-overview.md` 新增 **§10.278** 记根因与验证、本方案随落地状态更新 | — | 0.5 天 |

---

## 13. 风险与缓解

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 概念版取链**实际需要登录** | 路线 A 直接破产 | S0 第一天就测；失败即转 B/C 决策，不追加投入 |
| R2 | 只有 128k/320k，低于洛雪 `kg` 的真 FLAC | **净倒退**，用户体感变差 | 门禁「档位不下降」（§11.2）；必要时只接搜索与目录、⛔ 不接取链（§4 分叉 A′） |
| R3 | 自建服务部署门槛 | 功能实际无人可用 | 优先路线 A；B 必须给 Docker 一键 + 明确的失败自诊断 |
| R4 | 电视不信任 LE 证书 ⇒ Vercel 形态在创维上不可用 | 手机能播、电视不播 | 推荐 NAS + http（局域网，`usesCleartextTraffic` 已放开）或自有可信证书；把这条写进设置页说明 |
| R5 | 第三方账号凭据 / token 保管 | 泄露面 | 第一期不接登录（§7.2） |
| R6 | **上游或酷狗改版** | 每次都要重取证 | 参数与签名收在**单一常量/构造函数**里（仿 `LxPlatformMap.kt` 的「唯一真相源」做法），配一个「验证」按钮做现场取证 |
| R7 | 与 Meting-kugou / 洛雪 kg 重复 | 生态位模糊、双份维护 | 用 §3 的表**主动声明**各自分工，写进 `AGENTS.md`；否则不立项 |
| R8 | 合规敞口（公开仓库） | 版本发布 / 仓库层面的麻烦 | 提交前由所有者裁（§14-D3）；代码与文案不出现「破解 VIP」语义 |
| R9 | 接线点漏一处（静默串味） | 关掉别的源把新源一起藏了 / 下载分类错 | 接线全集表（§6）+ 专项门禁（§11.2 第 1 行） |

---

## 14. 需要所有者裁决的开放项

| # | 待裁问题 | 我的建议 |
|---|---|---|
| **D1** | 这份方案的**目的**是「稳定兜底取链」还是「酷狗官方目录」？ | 目录（取链上 A 是负增量，§3） |
| **D2** | 是否接受「新增一格非官方通路」进公开 main？ | 与 Meting / 洛雪同类，可接受，但需要明说 |
| **D3** | 路线 A / B / C 择一 | **S0 之后择**；现在最诚实的答案是 C（不新增）也能过 |
| **D4** | 若做 B：是否要把「自建 Node 服务」写进官方部署文档（NAS Docker） | 只在所有者愿意维护文档时做 |
| **D5** | 榜单/推荐 UI 是否值得为单一平台做（Jamendo / 电台已有子 Tab 先例，酷狗做的话其他平台会不会要求同等待遇） | 若要做得做成**平台无关的目录卡**，不能只给酷狗 |

---

## 附录 A：概念版取链请求字段（照上游 `module/song_url.js` 口径）

| 字段 | 来源 | 备注 |
|---|---|---|
| `hash` | 调用方（32 位 hex） | 与 Meting-kugou 搜索的 `id=`、洛雪 `kg` 的 `musicInfo.hash` 是否同空间 = **§2.3-3 待取证** |
| `album_id` | 调用方（搜索/详情返回） | — |
| `quality` | 输入映射为 `magic_*` 形态 | 映射表 = §2.3-2 待取证 |
| `page_id` / `ppage_id` / `pid` | `.env` 的 `platform=lite` 常量 | **概念版与手机版的差异就在这里**，移植时是固定常量而非用户配置 |
| `dfid` | `randomString(24)` | 每次随机会不会触发风控 = §2.3-5 待取证 |
| token / cookie | ⛔ 摘要显示该模块不读 | **R1 的关键测点** |

## 附录 B：本文引用的代码位（均为 main 现状，已逐条打开核对）

`backend/network/NetworkMusicService.kt:18-135`（`:31/:57/:64/:96/:106/:115/:125/:134` 逐方法）· `NetworkMusicManager.kt:16`（不 fallback 总述）/`:40`/`:42`/`:84-85`/`:210`/`:405`/`:411`/`:419`/`:466`/`:539` · `MetingApiService.kt:136/148-152/161-164/166-170/417-427` · `NetworkSongId.kt:20/23` · `QualityTiers.kt:20-32/85` · `PlayabilityProbe.kt:11-17`（PLAYABLE / DEAD / UNKNOWN，`:16` 明写「⛔ 绝不按 DEAD 处理」）· `lx/LxPlatformMap.kt:12-28` · `lx/LxPlayUrlEnhancer.kt:17-19` · `lx/LxSourceRegistry.kt:10/13/60`（平台级连败 3 次退避）· `backend/SourceVisibility.kt:46` 及其 KDoc 边界一 · `data/model/SourceIdentifier.kt:51` 起 · `data/model/MusicSource.kt:9-21` · `data/model/AppSettings.kt:51/58/71/78/84`（main 版）· `data/prefs/AppPreferences.kt:112-125/1364-1370` · `NasMusicApp.kt:521-561/781-802` · `backend/download/StreamUrlResolver.kt:87-94` · `backend/SearchAggregator.kt:58` 与超时常量 `:92`/`:97`/`:98`/`:99`/`:100` · `ui/viewmodel/MainViewModel.kt:752/777/791` · `ui/screens/settings/SourcesSettingsSection.kt:242-253/394` · `ui/screens/settings/LxScriptsSubBlock.kt:101` · `ui/screens/settings/NetworkMusicSection.kt:119-125/163-169` · `lyrics/LyricsNetworkProvider.kt:37-40` · 既有门禁 `app/src/test/.../data/prefs/NetworkSourceSwitchGateTest.kt` / `ProviderMirrorWiringGateTest.kt` / `LanguageMirrorTest.kt` · `docs/archive/lx-music-source-plan.md` §0/§2.2/§2.4/§5.4.1 · `docs/technical-overview.md` §10.229 / §10.276 / §10.277 与 `:17344`（全量 236 类 / 2428 例基线）

---

## 附录 C：平台 × 通路 支撑矩阵（2026-10-31 统计）

### C.1 先把两个容易混淆的分类轴钉住

- **平台轴（5 值）**：`data/model/MusicSource.kt:9-14` 的 `netease`/`tencent`/`kugou`/`kuwo`/`migu`，即 Meting 的 `server=` 参数；洛雪侧有对应短名 `wy`/`tx`/`kg`/`kw`/`mg`（`lx/LxPlatformMap.kt:15-21`，展开表见 `docs/technical-overview.md` §10.276）。
- **通路轴**：谁去把这首歌的数据取回来。**同一平台可被多条通路支撑**，本文统计的就是这一维。
- ⚠️ `AppSettings.kt:51` 的 `defaultNetworkSource`（值域 `"meting"`/`"baidu"`/`"jamendo"`）是**服务注册键**，`MusicSource.apiKey` 是**平台名**——不是一个轴（撞名警告见 §5）。

### C.2 通路清单（现存 12 条 + 本方案候选 1 条）

| # | 通路 | 形态 | 覆盖平台 | 能力 | 证据（已打开核对） | 实测态 |
|---|---|---|---|---|---|---|
| T1 | Meting `api.qijieya.cn/meting` | 社区公共代理 | 协议 5，**实测 3**（wy/tx/kg 全 200） | 搜索 + 302 取链 + 歌词端点 + 封面 + 歌单 | `MetingApiService.kt:151`、lx 方案 §2.4 表 | 🟢 电视可校验其证书、服务活（§10.229） |
| T2 | Meting `meting.mikus.ink` | 同上 | **仅 wy**（其余回 400） | 同 T1 | `MetingApiService.kt:161-164`、lx §2.4 | 🔴 创维电视不信任其 LE 证书链 ⇒ 该设备**恒失败**（§10.229） |
| T3 | Meting `meting.api.redcha.cn` | 同上，且是**代码里的默认值**（`MetingApiService.kt:136`） | **仅 wy** | 同 T1 | 同上 | 🔴 **站点已死** ⇒ 出厂默认值本身就是坏的 |
| T4 | 自建 / 自定义 Meting 端点 | 用户部署 | 取决于部署 | 同 T1 | `MetingApiService.kt:146` 注释；UI 先例 `NetworkMusicSection.kt:119-125` | ⚪ 不纳入统计 |
| T5 | 洛雪 墨澜 Molan | 社区脚本 + 其后端 | 声明 5，L3 实测 **kg FLAC / tx FLAC** | 取链（协议只有 `musicUrl`） | lx §2.2 表；内置入口 `lx/LxSourceEntry.kt:105-126` | 🟢 PC 侧 |
| T6 | 洛雪 星海 XingHai | 同上 | 声明 6，未授权**只开 kg** | 取链 | lx §2.2 逐条说明（`tx` 403 / `wy` 503 / `auth.trusted=false`） | 🟢 仅 kg |
| T7 | 洛雪 1010 音源 | 同上 | 声明 5，L3 tx/kg 真 MP3 | 取链 | lx §2.2 | 🟢 |
| T8 | 洛雪 星澜 StellarWave | 同上 | 声明 5，L3 kg FLAC / tx FLAC | 取链 | lx §2.2 | 🟢 |
| T9 | 洛雪 HYW 公益版 | 同上 | 声明 9（含 qs/bili/ytb/xm），L3 tx FLAC / kg MP3 | 取链 | lx §2.2（平台面最广） | 🟢 |
| T10 | 网易原生 `music.163.com` | **官方免鉴权** | 仅 wy | 搜索 + **艺人目录**（`findArtist`/`getArtistSongs`） | `netease/NeteaseOpenApi.kt:19-31`；插入口 `MetingApiService.kt:202-229` | 🟡 随时可下线，失败回落 Meting |
| T11 | 酷狗歌词直连 `mobilecdn` + `krcs` | **官方** | 仅 kg | 歌词（含 KRC→LRC）+ 定位用搜索 | `lyrics/LyricsNetworkProvider.kt:39-40`（`:37` 注释：HTTPS 证书 hostname 不匹配 ⇒ 默认走 http） | 🟢 在用 |
| T12 | 网易歌词直连 | **官方** | 仅 wy | 歌词 | 同上 `:41` | 🟢 在用 |
| — | MV：Bilibili + 自定义端点 | 官方/社区 | 不按平台 | MV 搜索 | `backend/network/mv/`、`NetworkMusicSection.kt:163-169` | 独立域，不参与本矩阵 |
| 候选 | **酷狗概念版** | 官方接口（需签名参数） | 仅 kg | 目录为主 | 本文 §2 | ⚪ 未取证 |

### C.3 按平台汇总

| 平台 | 搜索通路 | 取链通路 | 歌词通路 | 官方直连 | 目录能力 | **去重通路数** | 结构判定 |
|---|---|---|---|---|---|---|---|
| **酷狗 kg** | T1 | T1 + T5/T6/T7/T8/T9 | T11 | T11 | ⛔ | **7** | 🟢 支撑**最厚**，且是唯一「未授权也能拿到 FLAC」的平台 |
| **QQ tx** | T1 | T1 + T5/T7/T8/T9 | ⛔（仅 T1 的 lrc 端点） | ⛔ | ⛔ | **5** | 🟢 取链厚、歌词无兜底 |
| **网易 wy** | T1/T2/T3/T10 | **只有 T1** | T1 + T12 | T10/T12 | T10 ✅ | **4** | 🟡 **看着多、取链是单点**：脚本对 wy 无一成功（lx §2.2/§2.4.1），T2 在电视上恒失败、T3 站点已死 |
| **酷我 kw** | T1（kw **未实测**） | T1 + 脚本声明（L3 未验） | ⛔ | ⛔ | ⛔ | **1** | 🔴 事实单点 |
| **咪咕 mg** | T1（mg **未实测**） | T1 + 脚本声明（L3 未验，且主键字段是 `copyrightId`，填错会拿到「长得合法但内容不对」的链，`LxPlatformMap.kt:8-10`） | ⛔ | ⛔ | ⛔ | **1** | 🔴 事实单点 |

> 口径：通路数 = 能独立支撑该平台的能力来源数（不含 T4）；「未实测」= 协议声明支持但 lx 方案 §2.2/§2.4 里没有 L3 或 HTTP 证据。

### C.4 统计给出的三个判断（与直觉相反处已标出）

1. ⛔ **酷狗是支撑最厚的平台（7 条），因此也是最不需要新源的平台**。正文 §3 的「第四套酷狗链路」担忧在这里量化成立：给酷狗再加一条是 **7→8**，而酷我/咪咕是 **1→2**。若目标是「提升整体稳定性」，这份矩阵会把优先级指向 kw/mg/tx，而不是 kg。
2. ⚠️ **网易是「通路数量看着多、取链实际是单点」的伪装**。4 条里 3 条只在 wy 成立，且其中两条在目标电视上是坏的 ⇒ **一旦 qijieya 失效，wy 取链直接归零**。这个风险的紧迫度高于「酷狗要不要再加一条」。
3. 📌 **能力维度的不均衡 > 平台维度的不均衡**：歌词兜底只有 kg/wy 两条官方直连（T11/T12），tx/kw/mg 完全押在 Meting 的 lrc 端点上；目录/榜单能力只有 wy 有原生通路（T10）。⇒ 酷狗概念版真正的独占增量正好落在第 2 个缺口上（**除 wy 外任何平台都没有目录能力**），这比「再加一条取链」更有立项理由——但也必须先补消费方 UI（P2）。

### C.5 非平台型源（不进上表）

NAS（5 个适配器）/ 百度网盘 / Jamendo CC / 电台 radio-browser / 天气电台 / 本地导入 / 已下载：走服务注册键与 `NETWORK_SOURCE_PRIORITY`（`data/prefs/AppPreferences.kt:112-125`、`NasMusicApp.kt:537-560`），不落在平台轴上。它们不受酷狗方案影响，但会被 §6 的接线点全覆盖门禁影响。

### C.6 ⚠️ 本统计的证据边界

- 所有 L3「能播」判据来自 **2026-10-28 PC 侧探针**；真机项（尤其 API 22 电视上的 QuickJS 可用性 T1④、验收清单 T17、复验 T19）**未跑**（`docs/technical-overview.md` §10.276 末尾明写 ⏳，且该条自己声明「⛔ 本条不写『应该能跑』」）。
- **kw / mg 在 Meting 上没有 HTTP 实测记录**（§2.4 只测了 netease/tencent/kugou 三个 server）⇒ C.3 里这两行的「1 条」是**上界**。
- 社区源失效是常态（12 个候选里 7 个当前不可用）⇒ 这张矩阵**半衰期以周计**。引用它必须重跑 L3 探针，⛔ 不要把通路数抄进代码注释当长期事实。

---

## 版本说明

### V1.0 (2026-10-31)

初版评估。上游事实来自 GitHub 仓库页 / blob 页 / API 目录清单的抽取摘要（本机直连 raw 与 jsdelivr 被网络拦截，未逐字节读源码、未实跑端点），全部标注为待 S0 复核；本仓现状与接线点均已逐条打开核对（附录 B）。

### V1.1 (2026-10-31)

应「按平台分类统计各有哪些来源支撑」追加**附录 C**：平台轴 × 通路轴的定义、12 条现存通路 + 1 条候选的清单与实测态、5 个平台的通路数汇总（酷狗 7 / QQ 5 / 网易 4 / 酷我 1 / 咪咕 1）、三条统计判断，以及本统计的证据边界（PC 侧取证、kw/mg 无实测、矩阵半衰期以周计）。正文 §3 加了指向附录 C 的交叉引用。
