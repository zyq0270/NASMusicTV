# 洛雪音乐音源支持 · 开发方案

> **⛔ 归档状态：✅ 已落地（2026-10-28，版本 v2.39.8）。** 阶段 0–3.5 的代码与单测全部完成，
> 逐项实现出入、宿主线程模型、`lx.*` 清单、字段 / 音质映射、三级验证判据与锚点表见
> `docs/technical-overview.md` §10.276；用户可感知变更见 `CHANGELOG.md` 的 v2.39.8 段落。
> ⏳ **仍未执行的真机项**：T1 ④（API 22 上 `LxEngineAvailability.state == Available`）、
> T17 §11 验收清单、T19 真机复验 —— 2026-10-28 所有者电视（192.168.0.114）离线，见 §10.276 第八节。
> 本文仅供追溯，⛔ 不再作为执行依据。

> 状态：**已实施（原「待评审」）** ｜ 起草：2026-10-28 ｜ 取证方式：见附录 A（对真实脚本与端点做过实测，非文档推断）

## 0. 结论先行

1. **要做的事**：在 `backend/network/` 增加一个 JS 音源宿主，让 NASMusicTV 能加载**洛雪音乐自定义源脚本**（`.js`），把脚本拿到的播放直链接进现有播放链路。
2. **不做的事**：不重做搜索。洛雪自定义源协议**只有取链能力，没有搜索**（官方协议 `actions` 除 `local` 源外固定为 `['musicUrl']`），搜索继续走 Meting。
3. **核心架构**：洛雪音源是**取链增强层**（`PlayUrlEnhancer`），插在 `MetingApiService.resolvePlayUrlDetailed()` 前面；失败/未启用一律回退原链路，**关闭开关时行为与现在逐字符一致**。
4. **关键前提已端到端跑通**：Meting 搜酷狗拿到的 `id` 直接当 `musicInfo.hash` 喂给洛雪 `kg` 源，返回酷狗 CDN 直链，验活 **206 / audio/mpeg**，三个源独立复现（§2.4.1）。
5. **但当前能播的平台只有酷狗/QQ**：12 个候选源里 5 个真能播（墨澜 / 星海 / 1010 / 星澜 / HYW），全部集中在 `kg` 和 `tx`；**`wy` 当前无一成功**——网易是各脚本普遍放弃的平台。⭐ 这**不是路由限制**：洛雪通道对所有平台一视同仁，一旦出现能取 `wy` 的源就自动生效，App 一行不用改（§7.9）。且端点必须切到 `qijieya`（另两个预设对 kugou/tencent 直接 400）。
6. **源失效是常态**：12 个源里 7 个不可用，其中长青、幻音「能加载、能返回 URL，但地址是死的」。**方案因此按「源可插拔 + 取链后必须验活」设计**（§2.2 的 L1/L2/L3 三层判据），不把可用性押在任何单一源上。
7. **搜索要不要自己实现**：洛雪的搜索是客户端内置各平台 SDK（eapi/weapi 加密直连官方接口），自定义源协议在设计上就不覆盖搜索（§2.5.1）。若将来想让搜索也摆脱 Meting，正确路径是实现 **MusicFree 插件协议**（带 `search`），而不是移植洛雪 SDK。
8. **每个源（含自定义源）都有「验证」按钮**：点一下按 **L1 能加载 / L2 能取链 / L3 能播** 三级实测，**逐平台**出结果（`kg ✅ FLAC · wy ❌ 500`），而不是笼统的「已加载」。源频繁失效是这个生态的现实，所以「用户自己能判断手上的源还能不能用」是必需件，不是锦上添花（§5.4.1 / §7）。
9. **设置页要调整三处**：① **每个音乐源一个总开关**，集中在一张「网络音乐源」卡里（Meting / 百度网盘 / Jamendo），关掉的源彻底不参与搜索与取链；② **洛雪不单独成卡**，而是作为「Meting 的第二条取链通道」并入同一张卡（脚本列表 / 导入 / 验证全在卡内，§7.1）—— 洛雪没有搜索、`Song.networkSource` 恒为 `meting`，单独立卡等于把「一个零件」抬到与「源本身」同级；③ **「索引与封面」并进「百度网盘」卡** —— 已核实那张卡本来就只扫百度网盘（`rebuildBaiduIndex()` 用的是 `baiduApi` + 百度音乐根目录），独立成卡是分区错误。⭐ 这三件**都不依赖 QuickJS**，可以独立先做先发（§7.0 / §10 阶段 0），顺带修掉一个既有 bug：现在「网络搜索默认源」把 Alapi / JioSaavn 也列成候选，但这两个源**没有实现、且永远不会实现**，选了之后歌单页和艺人页会静默空白——本方案**直接删除**这两项连同整个 `NetworkSource` 枚举，默认源改用字符串 key（§7.5）。

---

## 1. 背景与目标

### 1.1 现状

网络音乐层是「搜索 → 取链 → 播放」三段式：

```
NetworkMusicManager.search()          → 默认源优先 + fallback
  └─ MetingApiService.search()        → {BASE}?server=X&type=search&id=关键词
NetworkMusicManager.resolvePlayUrl()  → 按 song.networkSource 精确路由，不 fallback
  └─ MetingApiService.resolvePlayUrlDetailed()
       ├─ GET {BASE}?server=X&type=url&id=<平台原生id>
       ├─ 不跟随重定向拿 302 Location（MAX_REDIRECT_HOPS = 2）
       └─ HttpPlayabilityProbe 跟一跳验 Content-Type → ResolvedResult(url, actualQuality, probedDead)
```

取链完全依赖 Meting 端点自身的可用性与版权状态。端点不提供某首歌 → 播不了，没有第二条路。

### 1.2 目标

| # | 目标 | 验收方式 |
|---|---|---|
| G1 | 能加载洛雪自定义源 `.js` 脚本，解析其声明的源与音质 | 设置页「验证」能显示脚本 `inited` 声明的源 |
| G2 | 播放时优先用脚本取链，失败自动回退 Meting | 关源时行为与现网一致；开源的失败不影响播放 |
| G3 | 音质档位正确映射（128k/320k/flac/flac24bit ↔ 128/192/320/999） | 单测覆盖全档位矩阵 |
| G4 | 源可增删、可从 URL 更新、失败有明确状态 | 设置页可导入/启停/刷新 |
| G5 | 不引入不可接受的风险（体积、页对齐、主线程卡顿、安全） | lint 0 error、单测全绿、真机播放流畅 |
| **G6** | **每个源（含自定义源）一键验证，按 L1/L2/L3 三级实测并逐平台出结论** | 点「验证」后墨澜/星海 `kg` 出 ✅、`wy` 出 ❌；长青 L1/L2 过而 L3 全 ❌（§11.1） |
| **G7** | **每个音乐源一个总开关，集中在一张卡里；关掉的源彻底不参与搜索与取链** | `SOURCES` 卡④ 三个源行开关（+ 洛雪取链通道开关在 Meting 行下方的子块里）；关源后搜索跳过、取链返回 null、缓存被清（§7.5） |

### 1.3 非目标

- 不做洛雪那样的完整音乐客户端（搜索/歌单/排行仍走 Meting）
- 不内置脚本正文（只内置 URL，见 §9.4 合规）
- 不改动 Meting 搜索链路

---

## 2. 实测事实（2026-10-28 取证）

> 全部结论来自对真实脚本的执行与真实端点的请求，取证脚本见附录 A。凡未验证的项均已标注。

### 2.1 洛雪自定义源协议

来源：官方文档 `lxmusic.toside.cn/desktop/custom-source`。

- 脚本是 JS（ES6+，UTF-8），头部必须有注释块：`@name` / `@description` / `@version` / `@author` / `@homepage`
- 宿主向脚本暴露 `globalThis.lx`：`EVENT_NAMES` / `request` / `on` / `send` / `env` / `version` / `currentScriptInfo` / `utils`
- 脚本注册请求处理：`on(EVENT_NAMES.request, ({source, action, info}) => Promise)`
  - `action` 除 `local` 源外**只有 `musicUrl`**
  - `info = {type, musicInfo}`，`type ∈ {128k, 320k, flac, flac24bit}`
  - 必须在 Promise 里返回 HTTP(S) 直链
- 脚本声明能力：`send(EVENT_NAMES.inited, {sources, openDevTools})`
  - `sources[kw|kg|tx|wy|mg] = {name, type:'music', actions:['musicUrl'], qualitys:[...]}`
- `lx.request(url, options, cb)` → 返回 cancel 函数；`cb(err, resp)`，`resp = {statusCode, statusMessage, headers, body}`
  - **`body` 若为 JSON 必须已解析成对象**（脚本直接写 `resp.body.url`）
  - `options` 支持 `method / headers / body / form / formData / timeout`
- `lx.utils`：`buffer.from` / `buffer.bufToString` / `crypto.md5` / `crypto.aesEncrypt` / `crypto.rsaEncrypt` / `crypto.randomBytes` / `zlib.inflate` / `zlib.deflate`

**关键推论**：自定义源是「取链插件」，搜索/歌词/封面都不在协议里。这决定了 §3.2 的架构选择。

### 2.2 十二个候选源的真实状态（2026-10-28 全量实测）

判定分**三层**，缺一层就不能算「可用」——这是本节最重要的方法论点：

| 层 | 含义 | 判据 |
|---|---|---|
| L1 能加载 | 脚本执行完并发 `inited` | 探针收到 `inited` 且解析出 `sources` |
| L2 能取链 | 调 `musicUrl` 拿到 URL 字符串 | 返回 `^https?://` |
| L3 **能播** | 该 URL 真能拉到音频字节 | HTTP 200/206 + `Content-Type: audio/*` + 容器魔数（`fLaC`/`ID3`/`ftyp`） |

**L2 通过而 L3 失败是最常见的假象**——脚本拼了个 URL 出来，地址早已失效。

| 源 | 版本 | 体积 | 声明平台 | 声明音质 | L1 | L2 | L3 能播 |
|---|---|---|---|---|---|---|---|
| **墨澜 Molan** | v2.3.4 | 130 KB | tx/wy/kw/kg/mg | 全平台 flac | ✅ | ✅ | ✅ **`kg` 真 FLAC**、`tx` FLAC |
| **星海 XingHai** | v3.2.15 | 38 KB | wy/tx/kg/kw/mg/qs | 128k~**master**/hires/atmos | ✅ | ✅ | ✅ **`kg` 真 FLAC** |
| **1010 音源** | — | 19 KB | wy/tx/kw/kg/mg | 五大平台聚合 | ✅ | ✅ | ✅ `tx`/`kg` 真 MP3（wy 试 8 条链路全败） |
| **星澜 StellarWave** | v4.0.0 | 48 KB | tx/wy/kw/kg/mg | flac | ✅ | ✅ | ✅ **`kg` 真 FLAC**、`tx` FLAC |
| **HYW 公益版** | v1.2.0 | 12 KB | kw/tx/wy/mg/**qs/bili/ytb/xm**/kg | flac24bit | ✅ | ✅ | ✅ `tx` FLAC、`kg` MP3（9 源，平台最多） |
| 长青 ChangQing | v1.3.0 | 27 KB | kg/tx/wy/kw/mg | 128k/320k/flac | ✅ | ✅ | ❌ 全部 **404** |
| 幻音 HuanYin | v3 | 7 KB | tx/kw/wy/mg | 128k~flac24bit | ✅ | ✅ | ❌ 域名不通 |
| JuheApi | v3 | 1 KB | kg/kw/mg/tx/wy | 128k~flac24bit+master | ⚠️ 时好时坏 | ⚠️ `tx` 曾成功 | 未验到（`init.conf` 间歇失败） |
| 落雪云 lx-cloud | — | 137 KB | — | — | ❌ | — | 脚本语法错误（`Invalid or unexpected token`） |
| Huibq | v1.2.0 | 2.7 KB | kw/kg/tx/wy/mg | 128k/320k | ✅ | ❌ | ❌ |
| Flower 野花 | 1 | 10 KB | kw/kg/tx/wy/mg | **仅 128k** | ❌ | — | — |
| 六音 SixYin | v1.2.1 | 333 KB | — | — | ❌ | — | — |

**12 个源里 5 个真能播**（墨澜 / 星海 / 1010 / 星澜 / HYW），全部集中在 `kg` 和 `tx` 两个平台，**`wy` 无一成功**（详见 §2.4.1）。其余 7 个**当前不可用**——其中 6 个是实测确认失效，JuheApi 属「未验到」（`init.conf` 间歇失败），⛔ 不要把它算成「已确认失效」。

📌 **这张表同时是「验证」按钮的期望值基线**（§5.4.1）：在真机宿主里对墨澜/星海点验证，`kg` 必须出 ✅ FLAC、`wy` 必须出 ❌；对长青点验证，L1/L2 过而 L3 必须全 ❌ 404。**实测与这张表不符，就是宿主实现有 bug**，不要先去怀疑源。

逐条说明（含反直觉项）：

- **星海（`kg` 对未授权用户开放，默认内置源之一）**：`GET https://yy.zddyr.top/lx/api/?source=<src>&name=&singer=&songmid=<id>&quality=<q>`。`kg` 返回酷狗 CDN 直链，实测 `http=206 / content-type=audio/flac / magic=664c6143`——**真 FLAC，能播**。但服务端有**授权体系**：`tx` 回 `403 该音源仅对认证用户开放`、`wy` 回 `503 服务器繁忙，当前仅限授权用户使用`（响应体里带 `auth.trusted=false` / `device_id=untrusted-no-auth`），**只有 `kg` 对未授权用户开放**。它的 `actions` 声明为 `["musicUrl","lyric","pic"]`，超出洛雪协议（非 `local` 源只允许 `musicUrl`），宿主需容忍。
- **长青（反直觉：加载和取链都过，但地址是死的）**：`reqs=[]` —— 取链**不发任何 HTTP**，纯本地拼模板 `http://yinyue.haitangw.net/<平台>/<xxx>.php?...`。该站点根路径 **200**（活着），但三个平台的具体接口路径**全部 404**。即「服务端改了接口、脚本模板没跟上」。用户反馈「长青还在更新」，但实测取链不可用，**以实测为准**。
- **幻音**：`https://music-dl.sayqz.com/api/?source=<src>&id=<id>&type=url&br=<q>`，域名 `fetch failed`（DNS/TLS 不通）。另外它的 `mg` 源 `actions` 写成 `["musicUrl","search"]`，同样超出协议。
- **JuheApi**：明文 1 KB，`GET /init.conf` 拿源声明、`POST /<source>` 取链。**服务端校验 UA**（无 `User-Agent` 直接返回空，带 `lx-music-mobile/2.0.0` 才给 JSON）。`init.conf` 间歇失败（两次实测一次成功一次 `null`）；`tx` 曾返回真实 QQ CDN 直链（`ws.stream.qqmusic.qq.com/...&vkey=`），`kg`/`wy` 回 `get url failed`。
- **Huibq**：`lxmusicapi.onrender.com` 返回 `This service has been suspended by its owner.`（Render 免费实例挂起）。作者仓库 `Huibq/keep-alive` 的 `.github/workflows` 就是为此做的定时唤醒。**pdone 镜像 `huibq/latest.js` 与作者官方 `render_api.js` 逐字节一致**（2727 B / v1.2.0）。
- **Flower**：初始化 `GET registry.npmjs.org/flower-source-info/latest`（回退 `registry.npmmirror.com`），拿到 `vinfo: {"1": {s: "kw|128k&wy|128k&mg|128k&tx|128k&kg|128k", m: "<md5>"}, lv: "1"}`。源声明串里**只有 128k**（与「支持无损」的宣传不符）。两个 registry 都请求到了，仍抛 `服务器异常`（md5 自校验不过）。
- **六音**：333 KB 全字符串 hex 转义，抛 `加载音源脚本失败，请前往 http://www.sixyin.com 下载最新版本！` 且先发 `updateAlert`；`reqs=[]`，纯本地校验失败（已排除 `lx.env` 与 `lx.version` 变量）。
- **LX 独家音源**：能 `inited`（6 源，含 `local`，音质到 flac24bit），但取链需服务端签名：`rconfig` 回 `{"code":1,"msg":"脚本指纹验证失败"}`、直连取链回 `{"code":1,"msg":"时间签名验证失败"}`，脚本内请求回 `block ip`。该源无公开下载地址，**未计入上表 12 个候选源**。

**结论**：

1. **12 个源里 5 个能真播**（墨澜 / 星海 / 1010 / 星澜 / HYW），全部集中在 `kg` / `tx` 两个平台；星海对未授权用户只开放 `kg`。**另 7 个已失效**，其中长青、幻音属于「能加载、能返回 URL，但地址是死的」。
2. 「能加载」和「能播」之间隔着一整层失效空间（长青、幻音就是典型），**宿主必须做 L3 级验证**，不能把 `musicUrl` 返回非空当成成功（§5.4 红线）。
3. 源失效是常态。方案必须把「源」当可替换配置项，不押注任何单一源。

### 2.2.1 星海源的接入要点

它是默认内置的两个源之一（另一个是墨澜，§8），且地址体系最容易踩坑，单独说明：

- **下载页**：`https://zrcdy.dpdns.org/lx/vers.php`（HTML，含版本表与 changelog）
- **最新脚本**：`https://zrcdy.dpdns.org/lx/xinghai-music-sourcev2.3.15.js`
  ⚠️ **文件名是 `v2.3.15`，脚本内 `@version` 是 `v3.2.15`**——命名错位，别按文件名判断版本。另外 `source` 与 `v2.3.15` 之间**没有分隔符**，这是服务器上的真实文件名（实测 200，加横线的 `source-v2.3.15` 是 404），⛔ 不是笔误，别「顺手修正」
- **别名地址** `https://zrcdy.dpdns.org/lx/xinghai-music-source.js` 指向**旧的 v2.3.0（2025-02-15）**，不要用
- 另有 MusicFree 版：`https://zrcdy.dpdns.org/musicfree/<平台>/xinghai-music-source-<平台>.js`（§5.6 第二期可复用同一后端）
- 该源有版本检查（`GET /lx/versionh2.php?ver=<当前版本>`）与 IP 上报（`GET https://yy.zddyr.top/ip.php`），宿主不需要拦截，但要知道它会打这两个请求

### 2.3 字段映射表（实测确认，非推断）

Flower 用探针逐个字段试出来的（给错字段 → URL 里出现 `undefined`）：

| 洛雪 source | 需要的 musicInfo 字段 |
|---|---|
| `tx` | `songmid` |
| `wy` | `songmid` |
| `kw` | `songmid` |
| `kg` | `hash` |
| `mg` | `copyrightId` |

**两家源的字段策略不同**（源码直读）：Flower 按平台分字段（`tx`/`wy`/`kw` 只认 `songmid`，`kg` 只认 `hash`，`mg` 只认 `copyrightId`）；Huibq 则用 `musicInfo.hash ?? musicInfo.songmid` 兜底、不分平台。

⚠️ 由此得出唯一一条实施约束：**宿主必须提供完整的 musicInfo**（`hash` + `songmid` + `copyrightId` + `name` + `singer` 能填的都填），不能只填一个字段——否则换个源就莫名失败。具体请求头 / URL 模板属取证留存，实施时不需要复刻。

### 2.4 id 空间对齐（决定「复用 Meting 搜索」是否成立）

实测 `https://api.qijieya.cn/meting/?server=X&type=search&id=晴天`，返回的 `url` 字段里 `id=` 参数：

| Meting server | 返回 id 实例 | 形态 | 对应洛雪 source / 字段 |
|---|---|---|---|
| `netease` | `2652820720` | 数字 | `wy` / `songmid` |
| `tencent` | `0039MnYb0qxYhV` | 14 位字母数字 | `tx` / `songmid` |
| `kugou` | `b3a52a7a958bf0aed0ebfba2e9a818b7` | 32 位 hex | `kg` / `hash` |

**这三个 id 就是各平台的原生歌曲 id**，与洛雪 `musicInfo` 要求的是同一套 id 空间。而项目 `MetingApiService.parseSongs()` 已经把 `id=` 参数存进 `Song.networkId`（`extractIdFromUrl`），**搜索层零改动即可对接**。

⚠️ 但端点能力有差异（实测，对项目三个 `PRESET_ENDPOINTS` 逐一验证）：

| 端点 | netease | tencent | kugou |
|---|---|---|---|
| `meting.api.redcha.cn`（`DEFAULT_BASE_URL`） | 200 | **400** | **400** |
| `meting.mikus.ink`（预设第 1 项） | 200 | **400** | **400** |
| `api.qijieya.cn/meting` | 200 | 200 | 200 |

即**三个预设里只有 qijieya 支持多平台**，另两个对非网易 server 直接回 `{"status":400,"message":"server 参数不合法"}`。洛雪的多平台能力要真正用上，用户需把端点切到 qijieya。

这是**既有问题**（和洛雪无关，现在切平台搜不到就是这个原因），本方案不修，但要在 UI 上给出提示（§7.1 洛雪子块顶部提示 / §7.3 端点提示），避免用户以为「洛雪源坏了」。

### 2.4.1 端到端验证：「Meting 搜索 + 洛雪播放」到底行不行

**结论：架构可行；当前能播的平台是酷狗/QQ。**（网易取不到链是**这批源当下的能力问题**，不是协议或路由不允许——§7.9 有正确读法。）

完整链路实测（2026-10-28，三个源独立复现，结果一致）：

```
Meting qijieya 搜 kugou "晴天"
  → {"name":"晴天","artist":"周杰伦","id":"b3a52a7a958bf0aed0ebfba2e9a818b7"}
  → 该 id 作为 musicInfo.hash 调洛雪 kg 源
  → http://fsandroid.tx.kugou.com/2026.../ap1005_us2173825779_...mp3
  → 验活：HTTP 206 / content-type=audio/mpeg / magic=49443303 ("ID3")  ✅ 真音频
```

1010 / 墨澜 / 星海三个源**全部跑通**，且 **`hash` 一个字段就够**（不需要 `songmid`）——这点很重要，因为 Meting 只给一个 id，正好对上。

**但 `wy`（网易）在全部 12 个源里无一成功**，这不是配置问题，是**这批源在网易上的取链通道**失效。下表只列 `wy` 的逐源细节（`kg` / `tx` 的结果见 §2.2 的 L3 列，不重复）：

| 源 | `wy` 实测（各脚本内部链路） |
|---|---|
| 1010 | 试了 8 条链路全失败（网易官方 / 星海GD / 溯音 / 笒鬼鬼 / HYW / Bugpk / ikun / 幻音） |
| 墨澜 | 唯一后端 ikun 回 `url:null, code:404` |
| 星澜 | 7 条链路全超时 |
| 星海 | 500 `获取失败`（所有档位，含 128k） |
| HYW | 全部音源失败 |
| 其余 7 个 | 见 §2.2，均不可用 |

**所以「用网易搜索、用洛雪播放」这个特定组合当前跑不通**——不是因为 id 对不上（§2.4 已证明对齐），而是因为**所有脚本的网易取链通道都失效了**。网易是各平台里版权/风控最严的，脚本作者普遍放弃。

**组合实测（A / B 可行；C 是当前不可行的那一个，列出来是为了说明原因）**：

| 组合 | 搜索端点 | 平台 | 播放源 | 实测 |
|---|---|---|---|---|
| **A（推荐）** | `api.qijieya.cn/meting` | 酷狗 | 星海 / 墨澜 / 1010 | ✅ 真音频，墨澜/星海能到 FLAC |
| B | `api.qijieya.cn/meting` | QQ | 1010 / 墨澜 / 星澜 / HYW | ✅ 真音频，墨澜/星澜/HYW 到 FLAC |
| C | 任意（默认 redcha） | 网易 | 无 | ❌ 无可用源 |

⚠️ 组合 A/B 都要求端点切到 **qijieya**（§2.4 的表：redcha/mikus 对 kugou/tencent 直接 400）。也就是说**洛雪音源要真正用上，必须先解决「端点只支持网易」这个既有问题**——否则用户装了源、开了开关，仍然只能搜到网易的歌，**洛雪取链全部失败、逐首回退 Meting**。

⭐ **这里丢的不是播放能力，是洛雪的增益**。Meting 原生通道（`type=url` + 302 跟跳）照常工作，用户**能听**，只是拿不到洛雪给的 FLAC。所以下面这条 UI 要求必须把「播不了」和「走回退」分开说——⛔ 别把后者写成前者，那会让用户以为 App 坏了、甚至被劝去换平台（而他本来不需要换）。

**这条对 UI 是硬要求**（两级提示，条件与文案都不同）：

| 触发条件 | 提示级别 | 文案 |
|---|---|---|
| **静态**：`LxPlatformMap` 有映射，但**所有已启用源都没声明**该 source | 强提示（黄底） | 「当前平台的洛雪通道无可用源」+「换一个声明了该平台的源，或切到酷狗/QQ」 |
| **动态**：有 `lastStatus` 验证结果，且该平台**全部 ❌** | 信息提示（灰字） | 「洛雪通道在该平台无可用源（最近验证 N 小时前），播放将走 Meting 原生通道」+「想用洛雪请切到酷狗/QQ」 |
| 无验证结果 / 该平台有任一 ✅ | **不提示** | — |

⛔ **触发条件不能写成「当前平台 = 网易」**：那等于把「这批源当下的能力」固化成代码里的平台判断，与 §7.9 的「路由规则与平台无关」直接冲突——将来出现能取 `wy` 的源，这个提示会变成错误信息。判据只能是「**声明**里有没有」和「**验证结果**是什么」，两者都与具体是哪个平台无关。

### 2.5 参考项目：MusicFree 插件协议

`maotoumao/MusicFree`（RN 0.76 + Hermes）的插件也是 CommonJS `.js`：

```js
module.exports = {
  platform: '插件名', version: '0.0.0', srcUrl: '更新地址',
  search(keyword, page, type) { ... },
  getMediaSource(musicItem, quality) { return { url } },
  getLyric(musicItem) { ... },
  getAlbumInfo / getMusicSheetInfo / getTopLists / importMusicSheet / getArtistWorks
}
```

**与洛雪的关键差异：MusicFree 插件协议包含 `search`，可以独立成源**，不依赖 Meting。

但它官方实现**把插件跑在宿主自身的 JS 上下文里，无沙箱**（文档自己写了「插件内有可能会有些破坏软件本体执行的逻辑」）。**我们不照抄这一点**——本方案用独立 QuickJS runtime 隔离（§5.1）。

**本方案的位置**：宿主基础设施（JS 引擎、`request`、`utils`、超时、隔离）是协议无关的。第一期只实现洛雪协议；MusicFree 协议作为**同一宿主的第二个协议适配层**留出接口（§5.6），将来可让「搜索」也走插件，彻底摆脱对 Meting 端点的依赖。

### 2.5.1 洛雪这类播放器的搜索是怎么实现的（源码实证）

**结论：洛雪的搜索是客户端内置的，完全不经过自定义源。**

看 `lx-music-desktop` 的 `src/renderer/utils/musicSdk/`：

```
musicSdk/
├── api-source.js   ← API 来源选择器（内置 API / user_api）
├── index.js        ← 汇总入口，searchMusic 在这里
├── kw/ kg/ tx/ wy/ mg/ xm/ bd/   ← 每个平台一整套 SDK（wy 就有 13 个文件）
└── utils.js
```

两条证据足以定性：

**① 每个平台目录里都有 `musicSearch.js`，直接调官方接口并用 eapi 加密**（`wy/musicSearch.js` 原文）：

```js
musicSearch(str, page, limit) {
  return eapiRequest('/api/search/song/list/page', {
    keyword: str, needCorrect: '1', channel: 'typing',
    offset: limit * (page - 1), scene: 'normal', total: page == 1, limit,
  }).promise.then(({ body }) => body)
}
```

**② 自定义源（`user_api`）只接管 `apis[source]`，而 `apis` 只被用于取链**；搜索走的是完全独立的另一条路：

```js
// api-source.js：自定义源的位置
const apis = source => /^user_api/.test(apiSource.value) ? userApi.apis[source] : getAPI(source)

// index.js：搜索永远走内置 SDK，与 user_api 无关
async searchMusic({ name, singer, source: s, limit = 25 }) {
  for (const source of sources.sources) {
    if (!sources[source.id].musicSearch || source.id == s) continue
    tasks.push(sources[source.id].musicSearch.search(...))
  }
}
```

**所以「自定义源不能搜索」不是协议疏漏，是刻意的架构分层**：搜索/歌词/歌单/排行全部内置，自定义源只管取链（取链是唯一需要外部维护、且法律风险最高的环节）。

⚠️ 由此得出的「要不要自己实现搜索」的结论**不在本节重复**，只在 §0-7 说一次：正确路径是实现 MusicFree 插件协议（带 `search`，§2.5 / §5.6），**不是**移植洛雪 SDK（§12 明确不做——代价见上面那棵目录树：7 个平台每个都是一整套 10+ 文件的 SDK）。

### 2.6 宿主引擎选型

| 方案 | 结论 | 理由 |
|---|---|---|
| **QuickJS（`io.github.taoweiji.quickjs:quickjs-android`）** | **采用** | ES2020 完整支持；armv7 两个 `.so` 合计 **571 KB**（`libquickjs.so` 454 KB + `libquickjs-android.so` 118 KB，1.4.6 实测）；内置 Event Queue 支持跨线程投递；可 `close()` 硬中断 |
| WebView + `evaluateJavascript` | 否决 | minSdk 22 的电视 WebView 常停在 39–52，脚本里的 `const`/箭头函数/解构直接语法错误；且必须主线程创建、无法限制执行时间 |
| Rhino / Duktape | 否决 | ES5.1 为主，跑不了这些脚本 |
| 手写 Kotlin 等价实现 | 仅作降级备案 | 取链逻辑简单的源可精确重写（§2.3 已有完整参数），但混淆脚本不可重写，且脚本一更新就腐烂。见 §9.2 Plan B |

⚠️ QuickJS-android 最后提交 **2021-10**，已停更。它**只服务于洛雪脚本取链，与搜索无关**（§9.2）；风险与对策见 §9.1 / §9.2。

---

## 3. 总体架构

### 3.1 分层

```
  backend/network/lx/
  ┌─────────────────────────────────────────────────────┐
  │  LxScriptRepo      脚本存取 / 元数据                │
  │  LxScriptHost      QuickJS 沙箱 + lx.* 桥接         │
  ├─────────────────────────────────────────────────────┤
  │                    ↑ 播放与验证共用同一实例        │
  │  LxPlayUrlEnhancer 音质映射 + 降级（播放链路）      │
  ├─────────────────────────────────────────────────────┤
  │  LxProbeRunner     L1/L2/L3 编排（验证按钮）        │
  │  LxUrlInspector    512 B + 魔数判容器               │
  └─────────────────────────────────────────────────────┘
```

### 3.2 四个关键决策

**决策 1：做成「取链增强层」，不做成新的 `NetworkMusicService`**

- 洛雪协议没有搜索（§2.1），做成独立源会导致 `search()` 无法实现，而 `NetworkMusicManager.orderedServices()` 会把它排进搜索 fallback 队列里空转。
- 挂在 `MetingApiService` 内部，意味着 `Song.networkSource` 仍是 `"meting"`，**收藏/下载/断点续传/直链缓存的主键全部不变**（`ntwk_meting_<id>`），零迁移成本。
- 回退天然：enhancer 返回 null 就走原链路，不需要新增 fallback 逻辑。

**决策 2：用 QuickJS 独立 runtime，不用宿主 JS 上下文**

- 安全：脚本是第三方代码，能在 Android 上跑任意逻辑。MusicFree 无沙箱的做法（§2.5）不采用。
- 可控：可 `close()` 硬中断死循环、可限制单次调用超时。
- 代价：需要自己实现 `lx.*` 桥接（§5.2）。

**决策 3：脚本可插拔，不硬编码任何源**

- §2.2 实测 12 个源里 5 个能播、7 个当前不可用（6 个确认失效 + JuheApi 未验到），且这个结论半年后大概率又变。把源做成「URL + 本地缓存正文 + 元数据」的配置项，加源不用发版。
- 内置的只是**预置 URL**（当前墨澜 + 星海两条，§8），不是脚本正文。

**决策 4：验证是「纯观察者」，且比播放链路更严**

- 验证与播放**共用同一个 `LxScriptHost`**（不新开 runtime，省内存），但验证**不写任何缓存、不改任何状态**——它只回答「现在能不能用」（§5.4.1.7）。
- 为了让验证不阻塞播放，探测期间该源对 enhancer **暂时不可获取**（`probing` 标记），播放直接回退 Meting。宁可这次不用洛雪，也不能让用户按了播放不出声。
- 判据**同源不同严**：播放端用 `PlayabilityProbe`（UNKNOWN 放行，避免误杀并负面缓存好歌），验证端用 `judgeL3`（401/403/429/5xx 一律判失败，UNKNOWN 靠魔数兜底）。理由见 §5.4.1.3。

---

## 4. 数据模型改动

### 4.1 `Song` 新增字段

```kotlin
// data/model/Song.kt
/**
 * 网络歌曲所属平台（"netease"/"tencent"/"kugou"/"kuwo"/"migu"），洛雪音源按此路由。
 * null = 历史数据或未标注，回退到当前全局平台设置（与改造前行为一致）。
 */
val networkPlatform: String? = null,
```

**为什么必须加**：现在 `Song` 只有 `networkSource="meting"` + `networkId`，平台名是全局设置 `getMusicSourceSync()`。用户在网易云搜到歌、切到 QQ 平台后再播这首，platform 信息就丢了，洛雪会拿 `wy` 的 id 去查 `tx`。加字段后每首歌自带平台快照。

**兼容性**：`networkPlatform == null` 时回退 `getMusicSourceSync()`，历史收藏/下载的歌不用迁移。Room 实体（`DownloadSongEntity` 等）**不加**该字段——下载后已有本地文件，不需要再取链。

### 4.2 填充点

`MetingApiService.parseSongs()` 构造 `Song` 时填 `networkPlatform = serverProvider()`（当前平台）。一处改动。

### 4.3 平台 ↔ 洛雪 source 映射（唯一真相源）

```kotlin
// backend/network/lx/LxPlatformMap.kt
internal object LxPlatformMap {
    /** Meting server → (洛雪 source, musicInfo 主键字段名) */
    private val MAP = mapOf(
        "netease" to ("wy" to "songmid"),
        "tencent" to ("tx" to "songmid"),
        "kugou"   to ("kg" to "hash"),
        "kuwo"    to ("kw" to "songmid"),
        "migu"    to ("mg" to "copyrightId"),
    )
    fun toLxSource(platform: String?): String? = platform?.let { MAP[it]?.first }
}
```

⚠️ 这张表是 §2.3 实测 + §2.4 对齐的产物，**改它必须有新的实测依据**。

---

## 5. 模块设计

### 5.1 `LxScriptHost`：QuickJS 宿主

**建 host 之前必须先过引擎可用性门禁**（§7.7 第 7 条）：

```kotlin
// backend/network/lx/LxEngineAvailability.kt
internal sealed interface EngineState {
    data object Available : EngineState
    data class Unavailable(val reason: String) : EngineState
}

internal object LxEngineAvailability {
    // ⛔ 首次求值必须在后台线程：loadLibrary 要读磁盘 + 重定位，主线程会掉帧
    val state: EngineState by lazy {
        try {
            System.loadLibrary("quickjs")
            System.loadLibrary("quickjs-android")
            EngineState.Available
        } catch (e: UnsatisfiedLinkError) {
            EngineState.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }
}
```

⛔ **不要用 `Build.VERSION.SDK_INT` 判断**——该库 `minSdkVersion 18`、`android/*` 引用全是 API 1，API 22 的老电视完全能跑（实测见 §9.2.1）。版本号是相关性不是因果，按它硬卡会既误杀又漏放（理由见 §7.7 第 7 条）。判据只能是「这台设备真的 `dlopen` 成功了吗」。

```kotlin
// backend/network/lx/LxScriptHost.kt
internal class LxScriptHost(
    private val scriptId: String,
    private val scriptText: String,                   // 脚本正文。⛔ 不叫 source —— 本类里 source 专指洛雪平台（wy/kg/tx…），
                                                      //    与 getMusicUrl(source, …) 的形参同名会造成「正文 vs 平台」两义
    private val httpClient: OkHttpClient,             // 复用 BackendRegistry 共享池
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    suspend fun load(): LxScriptInfo                   // 执行脚本 + 等 inited
    suspend fun getMusicUrl(source: String, musicInfo: LxMusicInfo, quality: String): String?
    fun cancelPending()                                // 取消：清未完成 deferred + 打断在途请求（§5.4.1.5）
    override fun close()                               // 销毁 runtime
}
```

⚠️ 所有公开方法**内部互斥**（同一时刻只有一个 JS 调用在跑）：播放取链与「验证」共用这一个 host，靠它天然串行，不额外加锁（§5.4.1.5）。

**线程模型**（关键，容易踩坑）：

- 一个脚本 = 一个 `QuickJS.createRuntimeWithEventQueue()` + 一个 `JSContext`，**独占一个后台线程**
- JS 线程只跑 JS，**绝不在 JS 线程上同步等 OkHttp**（会死锁）
- `lx.request` 的 Java 实现：在 IO 线程发请求 → 回调时用 `quickJS.execute { ... }` **投递回 JS 线程**执行脚本的 callback
- Kotlin 侧 await 用 `CompletableDeferred` 桥接，`withTimeoutOrNull(8_000)`
- 超时或 JS 抛错 → `context.close()` + `runtime.close()`，把该**脚本**标记为 `Broken`（脚本级，所有平台都不可用；某平台取链失败走平台级 `Degraded`，两者不同 —— §5.5）

**加载时序**：

```
1. new runtime + context（后台线程）
2. 注入 boot.js（§5.2）
3. 注册 Java 桥接方法
4. 设置 globalThis.lx.currentScriptInfo = {name, description, version, author, homepage, rawScript}
   ⚠️ rawScript 必须是**原始文件内容**（不 trim、不转义），部分脚本会拿它做校验
5. 执行脚本正文
6. 等 send('inited', ...)（最多 10s）
   ├─ 收到 → LxScriptInfo.Loaded(sources)
   └─ 超时 / 抛错 → LxScriptInfo.Failed(reason)
```

### 5.2 `lx.*` 实现清单

**设计要点**：Java 侧只暴露**少量 primitive**，复杂的协议语义写在 `boot.js`（我们自己的引导脚本）里。理由——桥接层用 Kotlin 写嵌套对象/数组的构造极其啰嗦，而 `lx.request` 的 `body` 解析、`utils` 的语义在 JS 里写是几行的事。

**boot.js 负责（纯 JS，随 APK 打包）**：

| 项 | 实现要点 |
|---|---|
| `console` | `log/info/warn/error/group/groupEnd/debug` 全部要有 —— QuickJS 默认**没有** `console`，Huibq 在 `env != 'mobile'` 分支会调 `console.group`，缺了就 ReferenceError |
| `Buffer` | 全局垫片，转发 `utils.buffer.*` |
| `setTimeout/setInterval/clear*` | 转发 Java 调度器（脚本可能用） |
| `lx.EVENT_NAMES` | `{request:'request', inited:'inited', updateAlert:'updateAlert'}` |
| `lx.env` | `'mobile'` —— **不是 `desktop`**。桌面分支会走 `console.group`，且部分脚本对 `env` 有分支逻辑 |
| `lx.version` | `'2.0.0'`（洛雪自定义源 API 版本，不是 App 版本） |
| `lx.currentScriptInfo` | 由 Java 注入（见 §5.1 第 4 步） |
| `lx.on(ev, fn)` | 存进 `__handlers` |
| `lx.send(ev, data)` | 转 `__nativeSend(ev, JSON.stringify(data))` |
| `lx.request(url, opts, cb)` | 分配递增 id → `__nativeRequest(id, url, JSON.stringify(opts))` → Java 回调 `__onResponse(id, respJson)` → JS 侧还原 `resp`（**body 是 JSON 就 parse 成对象**）→ `cb(null, resp)`；返回 cancel 函数调 `__nativeCancel(id)` |
| `lx.utils.buffer/crypto/zlib` | 转 `__nativeCrypto(op, argsJson)` / `__nativeZlib(op, base64)` |
| `__lx_invoke(source, action, infoJson)` | 供 Java 调用：`handler({source, action, info})` → `.then(__resolve)` / `.catch(__reject)` |

**Java 侧注册的 primitive**：

```
__nativeRequest(id: Int, url: String, optsJson: String)
__nativeCancel(id: Int)
__nativeSend(event: String, dataJson: String)
__nativeLog(level: String, msg: String)
__nativeTimer(kind: String, id: Int, ms: Int)
__nativeCrypto(op: String, argsJson: String): String
__nativeZlib(op: String, base64: String): String      // 同步返回 base64，或转 Promise
```

**`lx.request` 必须走项目共享 OkHttp**（`BackendRegistry.sharedDispatcher` + `sharedConnectionPool`），不新建 client —— 电视 WiFi 栈扛不住多套连接池（既有约定）。

**`resp.body` 三种形态**：
- `Content-Type` 含 `json` 或文本可 `JSON.parse` → **JS 对象**（脚本直接 `body.url` / `body.code`）
- 其他文本 → 字符串
- 二进制（`zlib` 场景）→ `Uint8Array`

### 5.3 `LxScriptRepo`：脚本存取

- 正文存**文件**：`filesDir/lx_sources/<scriptId>.js`（333 KB 放 DataStore 不合适）
- 元数据存 DataStore（§8）：`id / name / url / enabled / version / installedAt / lastStatus`
- 安装/更新流程：`GET url` → 校验头部注释（`@name` 必须有，否则拒绝）→ 写临时文件 → 替换 → 记录版本
- 更新失败**保留旧版**，不破坏可用性

### 5.4 `LxPlayUrlEnhancer`：接入点

```kotlin
// backend/network/lx/LxPlayUrlEnhancer.kt
internal interface PlayUrlEnhancer {
    suspend fun tryResolve(song: Song, quality: Int): ResolveResult?
}
```

`MetingApiService` 构造新增一个参数：

```kotlin
/**
 * 洛雪音源增强层。null = 未启用，行为与改造前逐字符一致
 * （新增路径与既有路径完全隔离，不改动原 302 链路的任何分支）。
 */
private val enhancer: PlayUrlEnhancer? = null,
```

`resolvePlayUrlDetailed()` 开头插入：

```kotlin
// 洛雪增强（新增路径）：命中即返回；null 继续走原 302 链路
enhancer?.tryResolve(song, quality)?.let { return it }
```

**约束（红线）**：

- `tryResolve` **内部必须全包 try/catch**，任何异常返回 null，**绝不外抛**
- 不得在 enhancer 内做重试循环（会拖长播放起播时间）
- 命中结果照常写入 `NetworkMusicManager.playUrlCache`（由上层统一处理，enhancer 不碰缓存）
- ⛔ **`musicUrl` 返回非空 ≠ 可播**（§2.2 的 L2/L3 之别，长青和幻音就是反例）。enhancer 必须复用既有的 `PlayabilityProbe` 做一次跟跳验活（拿最终 `Content-Type`，要求 `audio/*` 或容器魔数匹配），**验活失败按 null 处理**。`MetingApiService` 已经把 probe 注入进来了，直接复用，不新建探测逻辑。
  ⚠️ 注意区分：**播放链路**用既有 `PlayabilityProbe`（保守，UNKNOWN 放行，避免误杀）；**验证按钮**用 §5.4.1.3 的 `LxUrlInspector` + `judgeL3`（更严，要拿魔数给人看）。两者判据同源、严格度不同，**不要合并成一个**——合并任一方向都会出问题（播放端变严 → 好歌被跳过；验证端变宽 → 用户被误导）。
- ⛔ 验活只做**一跳**（与 `HttpPlayabilityProbe` 现有语义一致），不做多跳重试——星海给的酷狗 CDN 直链是 206 + `audio/flac`，一跳即可判定。

### 5.4.1 源验证：三级探测（每个源一个「验证」按钮）

> 需求原文：**每个洛雪音源（包括自定义源）都增加一个验证按钮，点击后按 L1/L2/L3 三级验证该源是否可用。**
>
> 这一节就是该按钮的完整契约：判据 → 锚点 → 编排 → 结果模型 → 与运行时路由的边界。UI 在 §7。

#### 5.4.1.1 为什么必须三级，而不是「能加载就算好」

§2.2 的实测把这件事说死了：长青、幻音两个源 **L1、L2 全过**（脚本正常 `inited`，`musicUrl` 也老老实实返回了一个 `http://` 开头的 URL），但那些 URL **全是死链**（404 / 域名不通）。如果验证按钮只测到 L2，它会诚实地告诉用户「这两个源可用」，然后用户点播放全部失败——**比不提供验证更糟**。

所以按钮的价值 100% 落在 L3。设计上有一条硬约束：

> ⛔ **L3 不可省略、不可降级为「只测一次 200 头」**。任何一个源，只要 L3 没跑，结果就必须显示成「未测」而不是「可用」。

#### 5.4.1.2 三层的精确判据

| 层 | 做什么 | 通过判据 | 不通过归因 |
|---|---|---|---|
| **L1** 能加载 | `host.load()`，等 `inited` | 10 s 内收到 `inited` 且 `sources` 非空 | `L1_TIMEOUT` / `L1_SCRIPT_ERROR`（原文截 200 字）/ `L1_NO_SOURCES` |
| **L2** 能取链 | 对每个声明的 source 调 `musicUrl` | 8 s 内返回字符串，匹配 `^https?://`，且不含 `undefined` / `null` 字面量 | `EMPTY_URL` / `BAD_URL` / `SCRIPT_ERROR` / `TIMEOUT` |
| **L3** 能播 | `Range: bytes=0-511` GET 该 URL | 见 §5.4.1.3 | 见 §5.4.1.3 |

`BAD_URL` 这条不是洁癖：§2.3 记录过「给错字段 → URL 里出现 `undefined`」，而那种 URL 长得完全合法，只有内容不对。

#### 5.4.1.3 L3 判据：复用 `classify()`，但**故意更严**

L3 需要三个信息：状态码、`Content-Type`、**容器魔数**。既有 `HttpPlayabilityProbe` 只给三态枚举、且只请求 `bytes=0-0`（1 字节，读不到魔数）。因此新增一个只读的 `LxUrlInspector`（**不改 `HttpPlayabilityProbe`**，它的语义被播放链路依赖着）：

```kotlin
// backend/network/lx/LxUrlInspector.kt
internal class LxUrlInspector(private val client: OkHttpClient = HttpPlayabilityProbe.defaultClient()) {
    /** 只读 512 字节，够判魔数；⛔ 绝不整首下载（§9.3 带宽预算） */
    fun inspect(url: String): LxL3
}

internal fun judgeL3(code: Int, contentType: String?, magic: String?): LxL3 = when {
    // ① 授权 / 失效 / 限流 / 上游 5xx：播放链路里 classify() 对这些归 UNKNOWN（保守放行，
    //    避免误杀好歌），但验证是给人看的，这四类必须显式判失败，
    //    否则 UI 会显示「存疑」让用户以为还能用。
    code == 401 || code == 403 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_403)
    //    404/410 也必须显式判：否则会落到下面 classify() 的 DEAD → NOT_AUDIO，
    //    UI 文案变成「返回的不是音频」——而真相是「直链已失效、脚本模板过期」
    //    （长青就是这种：站点根 200 活着，具体接口路径全 404，§2.2）。这是两件不同的事。
    code == 404 || code == 410 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_404)
    code == 429                -> LxL3.Dead(code, contentType, LxFailReason.HTTP_429)
    code in 500..599           -> LxL3.Dead(code, contentType, LxFailReason.HTTP_5XX)
    // ② 其余完全复用既有判据 ⇒ Content-Type 语义不与播放链路漂移（这是唯一的真相源）
    else -> when (HttpPlayabilityProbe.classify(code, contentType)) {
        Playability.DEAD     -> LxL3.Dead(code, contentType, LxFailReason.NOT_AUDIO)
        Playability.PLAYABLE -> LxL3.Ok(code, contentType, magic)
        // ③ UNKNOWN：Content-Type 不可靠（酷狗 CDN 有时给 application/octet-stream）
        //    ⇒ 用魔数兜底。魔数对上 = 真音频；对不上 = 存疑，不冒充失败
        Playability.UNKNOWN  -> if (magic != null) LxL3.Ok(code, contentType, magic)
                                else LxL3.Doubtful(code, contentType)
    }
}
```

**为什么验证比播放更严**（这段差异必须留注释，否则后人会「统一」掉它）：`Playability.UNKNOWN` 在播放链路里按可播放行，因为「UNKNOWN 误判成 DEAD 会让本可播的歌被跳过并负面缓存 5 分钟」，代价不可逆；而验证按钮**没有这个代价**——它不写任何缓存（§5.4.1.7），判严了用户再点一次就行。

魔数表（`LxMagic.detect`，取响应体前 8 字节）：

| 魔数（hex） | 偏移 | 容器 | 对应洛雪 quality |
|---|---|---|---|
| `664c6143`（`fLaC`） | 0 | FLAC | `flac` / `flac24bit` |
| `494433`（`ID3`） | 0 | MP3（带 ID3v2 头） | `128k` / `320k` |
| `fffb` / `fff3` / `fff2` | 0 | MP3 帧同步（无 ID3） | `128k` / `320k` |
| `66747970`（`ftyp`） | **4** | M4A / AAC | `128k` / `320k` |
| `4f676753`（`OggS`） | 0 | OGG / Opus | — |

**⭐ 顺手白捡一个高价值信号：音质虚标检测。** 脚本声明的档位是 `flac`，而实际容器是 MP3 ⇒ `qualityMismatch = true`，UI 显示 `⚠️ 标称 FLAC 实为 MP3`。这是 §6.1「`qualitys` 是声明不是能力」唯一的可视化落点，也是用户最容易被骗的地方（选了无损、听着像 128k）。检测本身零成本——魔数已经在手里了。

#### 5.4.1.4 锚点曲目表（验证的样本从哪来）

验证要调 `musicUrl`，就必须喂一个真实的 `musicInfo`。**锚点错了，整个验证的结论就是错的**——所以这张表和 §2.3 / §4.3 一样，改它必须有新的实测依据。

| 洛雪 source | 平台 | 主键字段 | 锚点值 | 来源 | 可靠性 |
|---|---|---|---|---|---|
| `kg` | 酷狗 | `hash` | `b3a52a7a958bf0aed0ebfba2e9a818b7` | qijieya 搜 kugou「晴天」 | ✅ **已端到端验证可播**（§2.4.1：206 / audio/mpeg） |
| `tx` | QQ | `songmid` | `0039MnYb0qxYhV` | qijieya 搜 tencent「晴天」 | ✅ 取链实测成功 |
| `wy` | 网易 | `songmid` | `186016` | 网易「晴天」原始条目（长期稳定） | ⚠️ **id 正确，但 12 个源全部取不到链**（§2.4.1）⇒ 期望结果就是 ❌ |
| `kw` | 酷我 | `songmid` | — | qijieya 搜 kuwo **返回空数组** | ❌ 无锚点 |
| `mg` | 咪咕 | `copyrightId` | — | qijieya 的 migu 搜索实际返回**网易 id**，`type=url` 回 302 空 body | ❌ 无锚点 |

⚠️ `wy` 有**两个**候选 id：本表固定用 `186016`（原始条目，长期不变），而 §2.4 里 qijieya 搜 netease 返回的是 `2652820720`（另一个条目）。**锚点固定用 `186016`**——验证要的是「id 本身长期有效」，不是「和这次搜索结果一致」；⛔ 不要按搜索结果动态替换锚点，否则同一份源在不同时间验证会得出不同结论。

三条硬结论：

1. **锚点必须按 source 分表，不能合成一个 `musicInfo`**。同一首歌在 `tx` 是 `0039MnYb0qxYhV`、在 `wy` 是 `186016`，而两者**共用 `songmid` 这个键名**——合并就必然互相覆盖。
2. **但每条锚点必须携带完整字段集**（`hash` + `songmid` + `name` + `singer` 能填的都填）。§2.3 实测过：Flower 按平台分字段、Huibq 用 `musicInfo.hash ?? musicInfo.songmid` 兜底，**策略不同**，只给一个字段会在某些源上莫名失败。
3. **`kw` / `mg` 没有可靠锚点，就不测，⛔ 不伪造 id**。拿一个编造的 id 去测，得到 404 会被显示成「源不支持酷我」——**把「我们没样本」谎报成「源不行」，是验证功能最坏的失败模式**。

```kotlin
internal data class LxAnchor(
    val source: String,                    // "kg"
    val fields: Map<String, String>,       // {"hash": "...", "name": "晴天", "singer": "周杰伦"}
    val verified: Boolean,                 // false ⇒ 不做 L2/L3，verdict 直接 UNTESTED(NO_ANCHOR)
)

internal object LxAnchorTable {
    val DEFAULT: Map<String, LxAnchor> = mapOf(
        "kg" to LxAnchor("kg", mapOf("hash" to "b3a52a7a958bf0aed0ebfba2e9a818b7", "name" to "晴天", "singer" to "周杰伦"), verified = true),
        "tx" to LxAnchor("tx", mapOf("songmid" to "0039MnYb0qxYhV", "name" to "晴天", "singer" to "周杰伦"), verified = true),
        "wy" to LxAnchor("wy", mapOf("songmid" to "186016", "name" to "晴天", "singer" to "周杰伦"), verified = true),
        "kw" to LxAnchor("kw", emptyMap(), verified = false),
        "mg" to LxAnchor("mg", emptyMap(), verified = false),
    )
}
```

**无锚点平台在 UI 上统一显示 `? 无锚点未测`**（与 §7.3 详情、§11.1 验收同一文案），并附一行说明「未测，不代表不可用」。 自定义源尤其会撞上这个：它可能只声明 `kw`，那验证就只能出 L1 结果——这是诚实的天花板，不要用假数据把它填平。

**锚点来源优先级**（`P2`，可延后）：

1. 内置锚点表（上表）
2. **当前正在播放的网络歌曲**：若 `song.networkPlatform` 能映射到该 source 且 `networkId` 非空，就用它当锚点（这能顺带把 `kw` / `mg` 覆盖上——用户搜到过酷我的歌就有样本了）
3. 都无 ⇒ 该平台标 `NO_ANCHOR`，只出 L1

#### 5.4.1.5 执行编排、并发与预算

```kotlin
// backend/network/lx/LxProbeRunner.kt
internal class LxProbeRunner(
    private val registry: LxSourceRegistry,
    private val inspector: LxUrlInspector,
    private val anchors: Map<String, LxAnchor> = LxAnchorTable.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 逐条吐事件，UI 据此实时刷新（不用等全部跑完） */
    fun verify(scriptId: String, depth: LxProbeDepth = LxProbeDepth.QUICK): Flow<LxProbeEvent>
}

internal enum class LxProbeDepth { SWEEP, QUICK, FULL, MATRIX }   // SWEEP = 全部验证专用（§5.4.1.5）

internal sealed interface LxProbeEvent {
    data class L1Done(val l1: LxL1) : LxProbeEvent
    data class PlatformStart(val source: String, val index: Int, val total: Int) : LxProbeEvent
    data class AttemptDone(val source: String, val attempt: LxAttempt) : LxProbeEvent
    data class PlatformDone(val probe: LxPlatformProbe) : LxProbeEvent
    data class Finished(val report: LxProbeReport) : LxProbeEvent
    data object Cancelled : LxProbeEvent
}
```

**必须复用同一个 `LxScriptHost` 实例，不新建 runtime。** 两个理由：

- 一个 333 KB 脚本 + QuickJS runtime 的常驻内存不小（§9.3：单 runtime **< 15 MB**），探测再开一份直接翻倍，电视上会 OOM。
  ⚠️ 别把这里的 15 MB 和 §11.2 的「`dumpsys meminfo` 增量 < 60 MB」混为一谈：前者是**单个 runtime**，后者是**整个 App 的增量**（含 `.so` 映射、脚本正文、报告对象）。
- `LxScriptHost` 内部单线程串行，天然把「探测」和「播放取链」排队，不会并发跑同一份脚本的 `__handlers` 状态。

**档位探测策略**（决定耗时）：

| depth | 探哪些平台 / 档位 | 估算耗时 | 用途 |
|---|---|---|---|
| **`SWEEP`** | **只探 `kg` 一个平台** × 最高档 1 次 | 10 + 8 + 3 = **21 s/源** | **「全部验证」专用**：12 个源逐个筛「还活着吗」 |
| `QUICK` | 全部已声明平台 × 各自最高档 1 次 | 5×(8+3) + 10 = **65 s/源** | **单源「验证」按钮默认** |
| `FULL` | 全部已声明平台 × 最高档 + 次高档（最多 2 次，**命中即停**） | 最坏 **110 s/源** | 二次确认：逐平台体检 |
| `MATRIX` | 全部已声明平台 × 全部声明档位 | 最坏 **200 s/源** | 二次确认：回答「我到底能不能拿到无损」 |

⭐ **为什么「全部验证」必须有自己的 `SWEEP`，而不是复用 `QUICK`**：两者要回答的问题不同。`QUICK` 回答「**这个源**在**各平台**行不行」（单源体检），`SWEEP` 回答「**这些源里哪些**还活着」（批量筛选）。按 `QUICK` 算，12 源 × 65 s = **13 min**，远超任何合理等待；而 `SWEEP` 12 源 ≈ **4.2 min**，能真的把 12 个源全过一遍。
⛔ 不要为了省事让「全部验证」复用 `QUICK`——那会导致 3 分钟后只测完 2~3 个源，用户以为「后面的源都是坏的」，**这比不给批量验证更糟**。
⛔ `kg` 是唯一所有源都对未授权用户开放的平台（§2.2.1），所以它当筛子最稳；`SWEEP` 不测其它平台，这不是偷懒，是「批量筛选」这个动作本身只需要一个可靠探针。

全档位矩阵对「这个源能不能用」没有增量信息，但耗时 ×4——所以默认不跑。

**预算与超时**（全部可配，下面取的是电视实测合理值）：

| 层级 | 上限 | 超了怎么办 |
|---|---|---|
| L1 | 10 s | 直接判 `L1_TIMEOUT` |
| 单次 `musicUrl` | 8 s | 该次记 `TIMEOUT`，试下一档 |
| 单次 L3 | 3 s | 该次记 `L3.Error(TIMEOUT)` |
| 单平台 | 20 s | 剩余档位记 `BUDGET` |
| 单源总计 | 60 s | 剩余平台记 `未测（超预算）`，`report.truncated = true` |
| **全部验证（`SWEEP`）** | **5 min** | 停止并如实报「已测 N/M」，⛔ 不假装全部测完 |

**预算与深度必须配套看，⛔ 别拿单源 60 s 去套所有深度**：

| depth | 实际耗时 | 与单源 60 s 预算的关系 |
|---|---|---|
| `SWEEP` | 21 s | 远低于预算，**永远测得完**（12 源 ≈ 4.2 min，落在 5 min 内） |
| `QUICK` | 65 s | 略超预算 ⇒ 最后一个平台可能记 `BUDGET`。**这是设计意图**（宁可截断也不让单源等 110 s），UI 需如实显示「未测（超预算）」 |
| `FULL` / `MATRIX` | 110~200 s | **必然被截断** ⇒ 这两个深度**必须二次确认**，且确认文案要写明「会超过 60 s 预算，部分平台可能测不到」 |

**UI 必须实时出结果**，不能让用户盯着一个转圈等一分钟——`LxProbeEvent` 逐平台吐事件正是为此（§5.4.1.5 开头）。

**串行，不并发。** 同一 runtime 本来只能串行；「全部验证」跨源也串行（同时两个 QuickJS runtime 在 1 GB 内存的电视上不可接受）。

**取消必须打断在途请求**，不能只取消 Flow 收集：

```kotlin
// 取消时（离开设置页 / 点「停止」/ 新一次验证覆盖旧的）
host.cancelPending()   // ① 清空所有未完成的 CompletableDeferred
                       // ② 对每个在途 __nativeRequest 调 __nativeCancel
                       // ③ ⛔ 不销毁 runtime（否则下次验证要重新 load 一遍脚本）
```

漏了 ②，脚本会在后台继续跑到 8 s 超时才结束，用户点「停止」后 CPU 还在烧。

#### 5.4.1.6 结果模型

```kotlin
internal data class LxProbeReport(
    val scriptId: String,
    val scriptName: String,
    val version: String?,                 // currentScriptInfo.version（不是文件名，§2.2.1 有过错位）
    val l1: LxL1,
    val platforms: List<LxPlatformProbe>,
    val depth: LxProbeDepth,              // ⛔ 必须带：UI 要据此说明「哪些平台是没测，不是坏了」
    val startedAt: Long,
    val elapsedMs: Long,
    val truncated: Boolean,               // 因预算 / 取消提前结束
)

internal sealed interface LxL1 {
    data class Loaded(val sources: List<String>, val version: String?) : LxL1
    data class Failed(val reason: LxFailReason, val detail: String?) : LxL1
    data object NotInstalled : LxL1
}

internal data class LxPlatformProbe(
    val source: String,                   // "kg"
    val declaredQualities: List<String>,  // 脚本声明（可能虚标，§6.1）
    val attempts: List<LxAttempt>,        // 按尝试顺序
    val verdict: LxPlatformVerdict,
    val bestQuality: String?,             // 实测拿到的最好档位（≠ 声明）
    val actualContainer: LxContainer?,    // 由魔数判定：FLAC / MP3 / M4A / OGG
    val qualityMismatch: Boolean,         // 声明 flac 实为 MP3
    val reason: LxFailReason?,
)

internal enum class LxPlatformVerdict { PLAYABLE, DOUBTFUL, DEAD, UNTESTED }

internal data class LxAttempt(
    val quality: String,
    val l2: LxL2,
    val l3: LxL3?,                        // null = 没走到 L3
)

internal sealed interface LxL2 {
    data class Url(val url: String) : LxL2
    data object Empty : LxL2
    data class Error(val reason: LxFailReason, val detail: String?) : LxL2
}

internal sealed interface LxL3 {
    data class Ok(val code: Int, val contentType: String?, val magic: String?) : LxL3
    data class Doubtful(val code: Int, val contentType: String?) : LxL3
    data class Dead(val code: Int, val contentType: String?, val reason: LxFailReason) : LxL3
    data class Error(val reason: LxFailReason, val detail: String?) : LxL3
}

internal enum class LxFailReason {
    L1_TIMEOUT, L1_SCRIPT_ERROR, L1_NO_SOURCES, L1_NOT_INSTALLED,
    EMPTY_URL, BAD_URL, SCRIPT_ERROR, TIMEOUT,
    HTTP_403, HTTP_404, HTTP_429, HTTP_5XX,
    NOT_AUDIO, NO_ANCHOR, BUDGET, CANCELLED,
    SKIPPED_BY_DEPTH,                     // `SWEEP` 只探 kg ⇒ 其余平台是这个原因，不是「无锚点」也不是「坏了」
}
```

`LxPlatformVerdict` 的判定规则（多档位时取**最好**的那个，不是最后一个）：

| verdict | 条件 |
|---|---|
| `PLAYABLE` | 至少一次 `LxL3.Ok` |
| `DOUBTFUL` | 无 `Ok`，但至少一次 `Doubtful` |
| `DEAD` | 所有尝试都**没**产出 `Ok` 或 `Doubtful` —— 含 L2 就直接失败的尝试（那种 `l3 == null`，既不是 `Dead` 也不是 `Error`，只写「都是 Dead/Error」会把它漏掉） |
| `UNTESTED` | 无锚点、超预算、或 L1 未通过 |

#### 5.4.1.7 与运行时路由的边界（红线）

验证是个**纯观察者**，这四条不能破：

1. ⛔ **验证结果不参与运行时决策**：不自动关源、不写 `Degraded`、不清直链缓存。一次手动测试（可能恰逢网络抖动）不该改变播放行为；`Degraded` 的触发条件严格限定为「同一 `(scriptId, source)` 运行时连续 3 次取链失败」（§5.5），且**只影响该平台**。
2. ⛔ **不复用 `NetworkMusicManager.deadProbeCache`**：那是 `song:quality` 维度的负面缓存，验证是 `source` 维度。写进去会污染真实歌曲的缓存键，让一首好歌被钉死 5 分钟。
3. ⛔ **验证绝不能阻塞播放**。因为共用 host，探测期间播放要取链时会撞上排队。正确取舍：`LxSourceRegistry.tryAcquire(scriptId, source)` 在 `probing[scriptId] == true` 时**直接返回 null**，enhancer 立刻回退 Meting（走原链路，最多慢一点点，但绝不等探测）。验证是后台活动，**它没有资格让用户按了播放却不出声**。
4. ⛔ **失败不能伪装成成功，也不能反过来**：L1 没过就显示 L1 失败，别把平台串全部涂成 `—` 了事；L3 没跑到就显示「未测」，别继承上一次的结果。

**唯一允许写的东西**：`lx_sources[].lastStatus` 里的**紧凑结果**（§8），供下次进设置页时先显示旧结果，避免每次都要重跑。

### 5.5 `LxSourceRegistry`：多源路由 + 健康状态

**健康状态必须分两级，⛔ 不能只按脚本记**——这是本节最容易写错的地方：

| 级别 | 键 | 何时进入 | 影响范围 |
|---|---|---|---|
| **脚本级** `Broken` | `scriptId` | 脚本 load 失败、JS 抛错、runtime 崩溃（§5.1） | 整份脚本，**所有平台** |
| **平台级** `Degraded` | `(scriptId, source)` | 该平台连续 3 次取链失败（超时 / 5xx / 403 / 空 URL） | **只有该平台**，其余平台照常 |

⭐ **为什么必须分两级**：如果只按 `scriptId` 计，用户在网易连播 3 首失败就会把整份墨澜打成 `Degraded`，5 分钟内**酷狗也用不了洛雪**——而酷狗恰恰是墨澜最强的平台。这等于用一个平台的表现否定整份脚本，直接违背「洛雪优先」的原则（§7.9）。

```
状态机（脚本级，每个 scriptId 一份）：
  Idle → Loading → Loaded(sources)
              ↘ Failed(reason)  ──(手动验证 / 定时重试)──→ Loading
  Loaded ──(load 失败 / JS 抛错 / runtime 崩溃)──→ Broken(until = now + 5min)

状态机（平台级，每个 (scriptId, source) 一份）：
  Healthy ──(连续 3 次取链失败)──→ Degraded(until = now + 5min)
  Degraded ──(到期)──→ Healthy（自动重试一次，失败则重新计时）
```

- 多源同时启用时，按**用户排序**依次尝试，第一个返回非空直链即用
- ⭐ **路由规则与平台无关**：`tryAcquire(scriptId, source)` 只查 `(scriptId, source)` 是否 `Healthy`，**⛔ 代码里不得出现「某平台不走洛雪」这类分支**——那等于把「当前这批源的质量」固化成硬编码，将来出现能取网易的好源还得改代码（§7.9 详述）
- 取链失败计数：同一 `(scriptId, source)` 连续 3 次 → 该组合 `Degraded`，5 分钟内不再尝试（避免每次播放都等超时）
- `Degraded` 到期后自动恢复一次尝试；**`Broken` 与 `Degraded` 互不覆盖**（脚本崩了是全局问题，某平台失败是局部问题）
- **`probing` 标记（为「验证」按钮服务）**：`LxProbeRunner` 跑某源时置 `probing[scriptId] = true`，此时 `tryAcquire(scriptId, source)` **返回 null**，enhancer 立刻回退 Meting。理由见 §5.4.1.7 第 3 条——验证与播放共用同一个 host，不这样处理就会让「用户按了播放却不出声」。⛔ 这个标记**只影响可获取性，不改 `Degraded`**（验证失败不等于源坏了）。

### 5.6 预留：MusicFree 协议适配层（第二期）

宿主基础设施（§5.1–5.3）协议无关。第二期加 `MfPluginAdapter`：

- `module.exports` 需要 CommonJS 语义 → boot.js 里加一个极简 `module` / `exports` 垫片
- 协议方法 `search / getMediaSource / getLyric / getAlbumInfo / getMusicSheetInfo / getTopLists / importMusicSheet` → 映射到 `NetworkMusicService` 接口
- 届时可新增一个 `sourceId = "mf"` 的 `NetworkMusicService`，**带搜索**，不再依赖 Meting 端点

**本期不实现**，只在目录结构上留位（`backend/network/lx/protocol/`）。

---

## 6. 音质映射与降级

### 6.1 档位映射

| 项目 `QualityTiers` | 候选洛雪 quality（按优先序） | 说明 |
|---|---|---|
| `AUTO`(0) | 按脚本声明的 `qualitys` 顺序 | 洛雪 `type` 必填、没有「不指定」⇒ 只能「让源自己按优先级给」 |
| `STANDARD`(128) | `128k` | |
| `GOOD`(192) | `320k` → `128k` | 洛雪**没有 192k** |
| `HIGH`(320) | `320k` → `128k` | |
| `LOSSLESS`(999) | `flac24bit` → `flac` → `320k` | 源若声明 `master`/`hires`/`atmos`（星海、墨澜），一并纳入候选队首 |

⚠️ **`AUTO` 不要硬编码成 `320k`**：`AUTO` 在项目里的语义是「**不传 `br`，交给端点决定**」（`QualityTiers` KDoc；`availableTiers` 也刻意不含它），而洛雪协议 `type` 必填、没有「不指定」这一项 ⇒ 语义等价物只能是「让源按它自己的优先级给」。硬编码 320k 既替源做了它本该做的选择，也拿不到源本来会给的 FLAC。同时 `AUTO` **不报降级提示**——它本来就不承诺档位（`fallbackChainOf(AUTO) = listOf(null)`）。

⚠️ **`qualitys` 是「声明」不是「能力」**：星海声明 `wy` 支持到 `master`，实测所有档位都回 500（§2.4.1）。所以音质档位映射只能作为**尝试顺序**，不能作为「这个源能给我无损」的承诺——最终以 L3 验活为准。

### 6.2 降级策略：**只降「同源内」，不跨档位骗人**

```
请求档位 → 候选列表
  ↓
与脚本声明的 qualitys 求交集
  ├─ 空 → 返回 null（走 Meting 原链路）
  └─ 非空 → 取交集第一个 → 调用脚本
              ├─ 成功且验活通过 → ResolveResult(url, actualQuality = 交集档位对应值)
              └─ 失败 / 验活不过 → 试交集下一个档位，全部失败则 null（走 Meting）
```

**为什么「空交集就放弃」**：如果源只声明 128k（Flower 就是这样），用户选了无损还硬给它 128k，就是「选了无损却静默降级」——比不用更糟。此时应该交给 Meting 去试，它可能真能给 FLAC。

⚠️ 注意与 §6.1 的配合：同一档位内可以**逐档下探**（flac24bit → flac → 320k），因为那是脚本自己声明的降级路径；但**跨源**降级不在这里做——那是 `NetworkMusicManager.resolvePlayUrlWithCrossSourceFallback` 的职责，不要重复实现。

`actualQuality` 必须如实上报（`ResolveResult` 已有该字段），供 UI 提示降级。

### 6.3 反向映射（洛雪档位 → 项目档位）

```kotlin
fun fromLx(q: String): Int = when (q) {
    "128k" -> QualityTiers.STANDARD
    "320k" -> QualityTiers.HIGH
    "flac", "flac24bit",
    "master", "hires", "atmos" -> QualityTiers.LOSSLESS   // ⛔ 别漏这三个，理由见下
    else -> QualityTiers.AUTO                              // 未知档位：不冒充无损，也不冒充某个具体档
}
```

⚠️ **`master` / `hires` / `atmos` 必须映射到 `LOSSLESS`，不能落到 `else`**：§6.1 的正向映射已经把这三个值纳入最高档候选队首（星海、墨澜都声明了），若反向掉到 `AUTO`（= 0，「不指定」），会出现「请求的是无损、`actualQuality` 却报 `AUTO`」——UI 的降级提示会失效，用户明明拿到了母带级却显示「未指定档位」。**正反两个方向必须对同一批值给出一致的结论**，否则这类 bug 只会在特定源上偶发。

---

## 7. UI 设计

本章有两件事，**依赖不同、可以分批落地**，别混在一起看：

| 子节 | 内容 | 依赖 QuickJS |
|---|---|---|
| 7.0 | 设置页结构调整（分区归属、卡片顺序、索引卡合并、洛雪归位） | ❌ |
| 7.1–7.3 | 洛雪脚本区块本体（卡④ 内）+ 「验证」按钮 | ✅ |
| 7.4 | 新增字符串（`values` + `values-en` 双份） | ❌（纯资源） |
| **7.5** | **「网络音乐源」卡：每个音乐源一个总开关** | ❌ |
| **7.6** | 「网络搜索默认源」卡改造（顺手修一个既有 bug） | ❌ |
| **7.7（第 1–6 条）** | 关闭源的后果与保护（含洛雪依赖 Meting、清直链缓存） | ❌ |
| **7.7（第 7 条）** | 洛雪子块的可达性门禁（`LxEngineAvailability`） | ✅ |
| **7.8** | 「音乐源」与「网络高级」的关系（源 ↔ 端点对照） | ❌ |
| **7.9** | 洛雪与 Meting 同时开启时到底用哪个 | ❌ |

⭐ **7.0 / 7.4（纯资源）/ 7.5 / 7.6 / 7.7 第 1–6 条 / 7.8 / 7.9 不依赖 QuickJS**，可以独立于洛雪先落地，先拿到价值（§10 阶段 0）。

### 7.0 设置页结构调整（总览）

**现状**（读代码确认，非推测）：

| 分区 | 文件 | 现有卡片 |
|---|---|---|
| `SOURCES`（音乐来源） | `SourcesSettingsSection.kt` | ①NAS 服务器 ②本地曲库 ③百度网盘 ④索引与封面 ⑤网络搜索默认源 |
| `NETWORK`（网络高级） | `NetworkMusicSection.kt` | ①连通性 ②音乐搜索端点 ③MV 端点 ④歌词端点 ⑤天气 Key |

**调整后 `SOURCES` 的卡片顺序**（合并 1 张、新增 1 张、改造 1 张；洛雪并入新增的那张）：

```
①NAS 服务器   ②本地曲库
③ 百度网盘（含「索引与封面」· 合并，见下）
④ 网络音乐源（新增 · §7.5）  ← 各源总开关 + Meting 行下的洛雪子块（§7.1）
⑤ 网络搜索默认源（改造 · §7.6）
```

**合并①：「索引与封面」并进「百度网盘」（用户 2026-10-28 提出，已核实成立）**

核实结果——**这张卡本来就只针对百度网盘**，独立成卡是分区错误：

- `rebuildBaiduIndex()`（`NetworkMusicViewModel:592`）扫的是 `prefs.baidu.getBaiduMusicRootDir()` + `baiduMvDir`，全程用 `baiduApi` 列目录（`baiduIndexCache.fullScan(root, baiduApi, mvDir, cb)`）。
- APIC 封面提取只处理百度索引里 `coverUrl` 为空的**百度条目**（源码注释：「`listall+web=1` 返回的 thumbs 仅对图片/视频有效，音频文件几乎都为 null」）。
- **本地曲库有自己独立的扫描**（卡② 的 `onRescan` → `localMusicRepository`），与这张卡无任何交集。
- 卡级说明「索引决定曲库能否被搜到」里的「曲库」其实专指**百度网盘曲库**，写成「曲库」容易被读成全部曲库 —— 这是文案 bug，合并时一并改掉。

合并后的卡③ 内部顺序（按实际操作顺序排）：

```
① 总开关  →  ② 登录/授权  →  ③ 音乐根目录  →  ④ MV 目录
→  ⑤ 索引状态（已扫描 N 首）+ [重建索引]  →  ⑥ APIC 封面提取进度
```

- ⛔ **显示条件要统一（实测差异，别想当然）**：卡③ 是**无条件渲染**的（`SettingsGroupCard` 外面没有 `if`，`onToggleBaiduEnabled?.invoke()` 只是可空调用）；而原卡④ 整块包在 `if (actions.onRebuildBaiduIndex != null)` 里。合并后索引区块**不要再挂外层 `if`**，跟卡③ 一样恒渲染，内部按钮按 `onRebuildBaiduIndex?.invoke()` 处理 —— 否则会出现「百度网盘卡正常、索引区块凭空消失」。
- 收益：不合并的话新增一张卡会变成 6 张；合并后是 **5 张**。
- 索引/封面的运行时代码**一行不动**，纯 UI 归位。

**合并②：洛雪并入「网络音乐源」，不单独成卡（用户 2026-10-28 指正，已核实成立）**

原设计把洛雪立成独立卡，理由是「用户心智里它就是个音源」。这个理由不成立，因为**功能角色上洛雪与 Meting 是同一类东西，而且层级更低一层**：

| 能力 | Meting | 洛雪脚本 | 百度网盘 | Jamendo |
|---|---|---|---|---|
| 提供曲库（搜索 / 歌单 / 艺人） | ✅ `search` / `getPlaylist` / `findArtist` | ❌ **协议没有**（§2.1 / §2.5.1） | ✅ 自己的索引 | ✅ 自己 API |
| 取链 | ✅ 原生 302 通道 | ✅ **脚本通道** | ✅ `baiduApi` | ✅ `jamendoApi` |
| `Song.networkSource` | `meting` | **也是 `meting`** | `baidu` | `jamendo` |

关键在第三行：洛雪取到的歌，`networkSource` 仍然是 `meting`、主键仍是 `ntwk_meting_<id>`（§3.2 决策 1）。**它不是「第二个源」，是 Meting 这个源的第二条取链通道**——enhancer 就插在 `MetingApiService.resolvePlayUrlDetailed()` 内部。

对照来看：百度网盘用 `baiduApi` 取链、Jamendo 用 `jamendoApi` 取链，**每个源都有自己的取链层**。洛雪只是 Meting 那一个。把它单独立卡，等于把「Meting 的一个零件」抬到与「Meting 本身」同级——这才是真正的分区错误（比原方案担心的「语义错位」更严重）。

并入后的三个收益：

1. **结构自明**：§7.7 第 1 条「关掉 Meting 洛雪就失效」不再需要专门防护，它天然成立（洛雪开关就在 Meting 的下一个分组里，Meting 关掉时置灰）。原方案要靠文案反复解释，就是因为位置错了。
2. **操作就近**：用户开/关一个源和它的取链通道在同一个视野内，不用跨卡对照。
3. **卡片数**：`SOURCES` 从 6 卡回到 **5 卡**，长列表问题反而缓解。

⛔ **仍然不做「管理洛雪 ›」跨分区跳转**（原方案也否决过）：TV 上跨分区跳转要新增导航状态 + 返回栈处理（`navVM.settingsSection` 是单值，跳过去就回不来原位），收益不抵成本。并入同一张卡后这个需求本来也消失了。

**卡④ 内部形态：洛雪子块紧贴 Meting 行，用左侧竖线做视觉缩进**

```
Meting 行                              ← 源开关（§7.5）
  ┊ 洛雪子块                            ← Meting 的第二条取链通道（§7.1）
  ┊   启用洛雪取链 [ 关 ]
  ┊   ● 墨澜 ● 星海 ● 自定义源 …
  ┊   [ 从 URL 导入 ] [ 刷新全部 ] [ 全部验证 ] [ 停止 ]

百度网盘 / Jamendo                    ← 其余源行
```

完整形态（含平台告警行、验证状态、每行图标）见 **§7.1**，此处只定顺序与层级。

- ⭐ **洛雪子块必须紧贴 Meting 行，中间不插其它源行**。这是「它属于 Meting」的唯一视觉证据——一旦中间隔了百度网盘 / Jamendo，竖线就从 Meting 下方隔空开始，「依附」关系立刻读不出来。
- **子块用左侧 2 dp 竖线做视觉缩进**（`Modifier.drawBehind` 画线，⛔ 不用 `Row{Box+Column}` 布局包裹，否则左缘对不齐，理由见 §7.1）。竖线不可聚焦、不改变焦点顺序，纯粹表达「子块从属于 Meting 这一行」。
- ⛔ **不用嵌套卡片**：`SettingsGroupCard` 自带边框 + 背景 + `padding(bottom = cardGap)`，卡内再套一张会变成「框中框」并多出一段 `cardGap` 空白。子块用**竖线 + 小标题**（`SettingsSubGroupLabel`，约 20 行），⛔ 不加可聚焦容器（TV 上焦点必须逐行落在真实控件上，`SettingsGroupCard` 本身也不可聚焦）。
- ⛔ **不做「点 Meting 行展开洛雪」**：TV 嵌套展开要处理焦点回收（展开后焦点落哪、收起后回哪），成本远高于收益。视觉缩进是它的替代方案——**视觉上有层级，交互上仍是一条线性列表**，遥控器上下即可走完。
- ⚠️ **不要再给源行加「— 音乐源 —」这类小标题**：加了就等于把 Meting 和洛雪拆成两个组，与本节结论相反。卡片自带的 description 已经说明「这些是音乐来源」，不需要二次分组。

**三条硬约束**：

1. **源开关与通道开关是两类，各自只有一个控件**。Meting / 百度 / Jamendo 各一个 `SettingSwitch`；洛雪子块里 `settings_lx_enabled` 是洛雪取链通道的总开关，脚本行内的 `[ 开 ]` 是**单脚本开关**（`lx_sources[].enabled`），两者是「与」关系 —— 总开关关着时脚本行置灰但仍可见可编辑（用户可以先配好脚本再开总开关，⛔ 不要 `enabled = false` 到连导入都点不了）。
2. **关源 = `unregisterService(key)`**（复用既有机制，`NetworkMusicManager:404`）。⛔ 不要在 `orderedServices()` 里加过滤 —— 那只覆盖 `search`，覆盖不到 `resolvePlayUrl` 的 `services[src]` 精确路由（`NetworkMusicManager:280`），关掉的源照样能取链。
3. **升级后行为必须与现在逐字节一致** ⇒ 所有新开关默认 `true`（洛雪除外，它默认 `false`）。

### 7.1 洛雪子块结构（紧贴 Meting 行下方，含「验证」按钮）

```
┌─ 网络音乐源 ──────────────────────────────────────────────────────────────┐
│  搜索与点播使用的音乐来源。关闭的源不参与搜索，也不再取链。               │
│                                                                           │
│  Meting（网易/QQ/酷狗…）                                       [ 开 ]     │
│      当前平台：酷狗 · 端点 qijieya · 默认源                               │
│  ┊  洛雪脚本 · Meting 的第二条取链通道                                    │
│  ┊  脚本只提供播放直链，搜索仍由 Meting 端点负责。                        │
│  ┊  启用洛雪取链                                              [ 关 ]      │
│  ┊                                                                        │
│  ┊  ⚠️ 洛雪通道在当前平台无可用源，播放走 Meting                          │
│  ┊     想用洛雪请切到酷狗/QQ（端点改 qijieya）                            │
│  ┊                                                                        │
│  ┊  ● 墨澜 Molan      v2.3.4                          [ 验证 ]  [ 开 ]   │
│  ┊      kg ✅ FLAC · tx ✅ FLAC · wy ❌ 500 · kw ? · mg ?                 │
│  ┊      上次验证：今天 21:04                                              │
│  ┊  ● 星海 XingHai    v3.2.15                         [ 验证 ]  [ 开 ]   │
│  ┊      kg ✅ FLAC · tx ❌ 403 需授权 · wy ❌ 503                         │
│  ┊  ● 自定义源 …                                      [ 验证 ]  [ 开 ]   │
│  ┊                                                                        │
│  ┊  [ 从 URL 导入 ]  [ 刷新全部 ]  [ 全部验证 ]  [ 停止 ]                 │
│                                                                           │
│  Baidu 百度网盘（含索引与封面）                                [ 开 ]     │
│      已登录 · 索引 1,284 首                                               │
│  Jamendo（CC 独立音乐）                                        [ 关 ]     │
│      未配置 Client ID                                                     │
│                                                                           │
│  已关闭源的收藏与歌单将无法播放（重新开启即可恢复）。                     │
└───────────────────────────────────────────────────────────────────────────┘
```

**洛雪子块紧贴 Meting 行**（中间不插任何其它源行）—— 本节最重要的一条：它和 Meting 是一个连续区块，其余源行排在它之后。

**左侧竖线是视觉从属标记**（`┊` 所在位置）：

- 竖线用 `Modifier.drawBehind { drawLine(...) }` 画一条 **2 dp 实线**（`NasMusicColors.Border`，与卡片边框同色但更细），⛔ **不用 `Row { Box(width = 2.dp); Column { … } }`** —— 后者多一层布局节点、子块的行宽会与源行差 2 dp + 间距，左缘对不齐；`drawBehind` 只画不改布局，**子块每行与源行左缘严格对齐**。
- 竖线**起点就在 Meting 行的下一行**（不是从卡片顶部、也不隔空行），终点到子块最后一个按钮，视觉上「包住」整个从属区块。
- **竖线不影响焦点顺序**：它不可聚焦，焦点按「Meting 行 → 子块各行 → 百度 / Jamendo」线性移动（TV 遥控器上下即可）。这是选视觉缩进而非「点 Meting 行展开」的根本理由——展开要处理焦点回收，缩进不需要。
- ⚠️ 竖线是**纯装饰**：`settings_lx_enabled` 置灰时竖线也一并变淡（`Border.copy(alpha = 0.5f)`），但**不隐藏**——隐藏会让下面的源行突然上移。
- **子块顶部提示是硬要求，但判据不是平台名**（§2.4.1 那张两级表）：只有「所有已启用源都**没声明**该平台」或「验证结果显示该平台**全 ❌**」时才提示。⛔ 不要写成「平台 = 网易就提示」——那等于把当前源生态固化成代码（§7.9），将来出现能取 `wy` 的源，这条提示会变成错误信息。
- ⚠️ **提示语气必须匹配真实后果**：洛雪通道不可用时，播放会**回退 Meting 原生通道**（§1.1），用户**能听**，丢的只是洛雪的音质增益。所以文案是「播放将走 Meting」而不是「无法播放」——⛔ 写成后者会让用户以为功能坏了，这才是本方案最容易踩的体验坑。
- **每个源一行一个「验证」按钮，自定义源同样有**（需求原话）。按钮位置固定在行右侧、开关左侧，保证 TV 遥控器**左右一次**就能到达。
- 每行**按平台**展示三级结果，而不是一个笼统的「已加载」——用户真正关心的是「哪些平台能用」。平台串里的图标含义：`✅` L3 能播 / `⚠️` 存疑 / `❌` 失败（带状态码或人话原因）/ `?` 无锚点未测 / `⏳` 正在测。
- 每行右侧显示该源**声明的**最高音质；若验证发现虚标（§5.4.1.3），在平台串后追加 `⚠️ 标称FLAC实为MP3`。
- **Meting 行开关关掉时，整个子块置灰**（不是隐藏）：洛雪是 Meting 的取链通道，源不在通道自然不可用（§7.7 第 1 条）。置灰而非隐藏，是为了让用户看得见「它存在，只是前提没满足」。

### 7.2 「验证」按钮的状态机

| 状态 | 按钮文案 | 行内平台串 | 其它控件 |
|---|---|---|---|
| 从未验证 | `验证` | 全 `?`（灰） | 可点 |
| L1 进行中 | `加载中…` | 不变（灰） | 该行开关/刷新禁用 |
| 逐平台探测 | `验证中 2/3`（当前平台名附在下方） | 已完成的立即出结果，当前平台 `⏳` | 该行开关/刷新禁用 |
| 完成 | `重新验证` | 全量结果 | 恢复 |
| L1 失败 | `重试` | 全 `—`，行下方红字失败原文 | 恢复 |
| 结果过期（>24 h） | `重新验证` | 结果置灰 + `（结果已过期）` | 恢复 |

- 按钮**必须用 `FocusableSurface`**，⛔ 不用 tv-m3 `Button` 的 `onClick`（触摸设备上是死的，见项目根因⑨）；焦点视觉一律 `isFocused && isTVDevice()`。
- **进度必须实时**：`LxProbeRunner` 是 `Flow<LxProbeEvent>`，每个 `PlatformDone` 立刻刷新对应平台，不等全部跑完。单源预算 60 s（§5.4.1.5），让用户盯着转圈等一分钟是不可接受的。
- `全部验证` 串行跑所有**已安装**源（含已关闭的——关着也可能想测），按钮变 `停止`，**洛雪子块顶部**多一行 `正在验证 2/3（墨澜）`。用 **`SWEEP`** 深度（每源只测 `kg` 一个平台 × 最高档，约 21 s/源），总预算 5 min（§5.4.1.5）。
  ⭐ **它回答的问题和单源「验证」不同**：单源「验证」=「这个源在各平台行不行」，`全部验证` =「这些源里哪些还活着」。所以前者测全部平台、后者只测一个可靠探针（`kg`），否则 12 源按 `QUICK` 算要 13 min，根本跑不完。
  ⚠️ 按钮旁固定显示预计耗时（`约 4 分钟`），跑起来显示 `已测 7/12`——**不要让用户对着一分钟的转圈猜还剩多久**。
- **验证期间播放不受影响**：`LxSourceRegistry.tryAcquire(scriptId, source)` 返回 null ⇒ enhancer 直接回退 Meting（§5.4.1.7 第 3 条）。UI 不需要为这事加任何提示——用户按播放就该出声。

### 7.3 详情展开（回答「为什么不行」）

点平台串或行空白处 → **内联展开**（⛔ 不用 `Dialog`：TV 上焦点陷阱严重，且本页已有多个弹窗入口；若确需 `Dialog`，必须 `usePlatformDefaultWidth = false`，见项目约定「`ConfirmDialog` 不是弹窗」）：

```
┌──────┬──────┬─────────┬──────────┬─────────────────┐
│ 平台 │ 档位 │ L2 取链 │ L3 响应  │ 判定            │
├──────┼──────┼─────────┼──────────┼─────────────────┤
│ kg   │ flac │ ✅      │ 206 FLAC │ ✅ 能播         │
│ tx   │ flac │ ✅      │ 206 FLAC │ ✅ 能播         │
│ wy   │ 320k │ ✅      │ 500 —    │ ❌ 上游服务异常 │
│ wy   │ 128k │ ✅      │ 500 —    │ ❌ 上游服务异常 │
│ kw   │ —    │ —       │ —        │ ? 无锚点未测    │
└──────┴──────┴─────────┴──────────┴─────────────────┘
```

**失败原因必须给人话 + 可执行建议**（对应 §11.1 最后一条「授权类失败要和网络失败分开」）。
⛔ **`NO_ANCHOR` 与 `SKIPPED_BY_DEPTH` 是两件事，别合并成「未测」**：前者是「我们没样本」（单源验证也会出现），后者是「这次批量跑根本没轮到它」（点单源验证就有了）。合并会让用户以为批量验证已经验过 `kw`/`mg` 且结论是「测不了」，实际只是没测。

| `LxFailReason` | 文案 |
|---|---|
| `HTTP_403`（含 401） | 「该平台需作者授权」 |
| `HTTP_404`（含 410） | 「直链已失效，脚本模板可能过期」 |
| `HTTP_429` | 「请求过于频繁，稍后再试」 |
| `HTTP_5XX` | 「上游服务异常」 |
| `NOT_AUDIO` | 「返回的不是音频（可能是网页或错误页）」 |
| `EMPTY_URL` / `BAD_URL` | 「脚本没能拼出有效直链」 |
| `TIMEOUT` / `BUDGET` | 「超时，未测完」 |
| `NO_ANCHOR` | 「无测试样本，未测（不代表不可用）」 |
| `SKIPPED_BY_DEPTH` | 「批量验证未覆盖该平台，点本行『验证』可单独测」 |
| `L1_*` | 原样显示脚本报错文本（截 200 字） |

- `wy` 失败时**固定追加一行**「网易平台在当前这批脚本里普遍取不到链，建议改用酷狗/QQ（§2.4.1）」。⛔ 别写成「网易永远不行」——那是当前源生态的观测，不是路由规则（§7.9）。把已经验证过的结论直接送到用户眼前，比让他自己搜半天强得多——这也是本方案唯一能把「平台选错了」和「源坏了」区分开的界面。
- 端点提示：当端点只支持 netease（§2.4）时，**洛雪子块底部**加一行灰字「当前端点仅支持网易云，其他平台无法搜索」。

### 7.4 新增字符串（`values` + `values-en` 双份）

**洛雪（7.1–7.3）**：
`lx_verify` / `lx_verifying` / `lx_verify_again` / `lx_verify_all` / `lx_verify_stop` / `lx_verify_progress` / `lx_verify_detail_title` / `lx_verify_elapsed` / `lx_verify_stale` / `lx_verify_last_at` / `lx_l1_ok` / `lx_l1_fail` / `lx_platform_playable` / `lx_platform_doubtful` / `lx_platform_dead` / `lx_platform_untested` / `lx_quality_mismatch` / `lx_reason_auth` / `lx_reason_not_found` / `lx_reason_rate` / `lx_reason_upstream` / `lx_reason_not_audio` / `lx_reason_bad_url` / `lx_reason_timeout` / `lx_reason_no_anchor` / **`lx_reason_skipped_depth`** / **`lx_verify_all_eta`**（「约 4 分钟」）/ `lx_hint_wy_dead`

**源总开关（7.5–7.7）**：
`settings_group_music_sources` / `settings_music_sources_desc` / **`settings_subgroup_lx`**（洛雪子块的小标题「洛雪脚本 · Meting 的第二条取链通道」）/ `settings_source_meting` / `settings_source_baidu` / `settings_source_jamendo` / `settings_source_lx` / `settings_source_is_default` / `settings_source_off_playback_warn` / `settings_source_lx_needs_meting` / `settings_source_default_switched` / `settings_no_music_source` / `settings_no_music_source_desc` / `settings_group_default_source`（**复用**，仅改文案）/ `settings_default_source_desc`（**复用**，仅改文案）

⚠️ 两份都要加，否则 `lintDebug` 报 `MissingTranslation`（§11.2 要求 0 Error）。

### 7.5 卡④ 的源行：每个源一个总开关

需求原话：**「各个音乐源都要增加总体开关」**。

**先说一个必须顺手修的既有问题。** 「网络搜索默认源」卡现在遍历 `NetworkSource.entries`（`METING` / `ALAPI` / `JIOSAAVN`），但后两个**只有枚举、没有实现**——`backend/network/` 下没有任何 `alapi` / `jiosaavn` 的 `NetworkMusicService`；`NasMusicApp:374` 的 services map 只塞了 `meting`，`baidu` / `jamendo` 是条件注册。于是用户把默认源选成 Alapi 之后：

- `services[defaultSource]` → `null` ⇒ `getPlaylist` / `findArtist` / `getArtistSongs` 全部返回 `null`
- `orderedServices()` 里默认源不在列表 ⇒ 搜索顺序退化成 Map 迭代顺序
- 表现是「搜索还能用，但歌单页和艺人页空白」——**最难排查的那类 bug**

⚠️ **更深一层：`NetworkSource` 枚举根本不是「有哪些源」的真相源。** 枚举只有 3 项（`METING` / `ALAPI` / `JIOSAAVN`），而实际有实现的源是 **`meting` / `baidu` / `jamendo`** ——两边**完全错位**：枚举里有但没实现 2 个（Alapi / JioSaavn），实现了但不在枚举里 2 个（百度网盘 / Jamendo，它们直接以 key 字符串注册进 `services` map，`NasMusicApp:623` / `MainViewModel:3869`）。
所以「有哪些源」的真相源是 **`services` map 的 key**，不是 `NetworkSource.entries`。⇒ 卡④ 的 `MusicSourceRow` 必须按 **services map 的实际内容**生成，⛔ 不要图省事继续遍历枚举——那会同时**漏掉百度/Jamendo、又凭空多出两个死项**。

**决策（2026-10-28 用户拍板）：`Alapi` / `JioSaavn` 永远不会有实现，直接删除，不留灰行。**

原方案打算把它们渲染成「未接入（本版本无实现）」灰行，理由是「用户在这里见过这三项，突然消失会以为功能被砍」。**这个理由在本场景不成立**——它预设了「以后可能会实现」，而实际结论是**永远不会**。留一条永远灰着的行，等于在设置页常驻一个「这里有个洞」的提示，比让它消失更糟。

**删除范围（⛔ 不是只删 UI 那两行）**：

| 文件 | 改什么 |
|---|---|
| `data/model/NetworkSource.kt` | **整个枚举删除**（理由见下 ⭐） |
| `data/model/AppSettings.kt:42` | `defaultNetworkSource: NetworkSource` → **`String`**（存 services map 的 key） |
| `data/prefs/AppPreferences.kt` | `:879` 反序列化去掉枚举转换（原样返回字符串）；`:976` setter 收 `String`；`:2117` 序列化直接存 key；`:228` 镜像**保持原样**（它只缓存字符串，不负责校验） |
| `backend/network/NetworkMusicManager.kt:124` | **`defaultSource` getter 加有效性校验**（见下方 ⚠️，这里才是真正的修复点） |
| `ui/viewmodel/MainViewModel.kt:643` | `updateDefaultNetworkSource(String)` |
| `ui/screens/settings/SourcesSettingsSection.kt` | `:52` 字段类型改 `String`；`:311` 卡⑤ 候选改从 `musicSources` 取（§7.6） |
| `ui/screens/SettingsScreen.kt:719` | 传参类型跟着变 |
| `backend/network/NetworkMusicService.kt:8 / :20` | 注释里的 `AlAPI` / `JioSaavn` 换成 `baidu` / `jamendo` |
| `Song.kt:9` / `NetworkFavoriteItem.kt:15` / `SourceIdentifier.kt:65` | 注释里的 `"alapi"/"jiosaavn"` 换成 `"baidu"/"jamendo"` |
| `NetworkSongId.kt` | KDoc 里「`NetworkSource.METING` 枚举 key 属 UI 侧」这句删掉（枚举没了） |
| 测试 `DefaultNetworkSourcePrefTest` / `NetworkArtistSongIdContractTest` | 改用字符串 key（§11.2） |

⭐ **为什么连枚举一起删，而不是只删两项**：删完只剩 `METING` 一个值，单值枚举没有任何类型价值。而它原本的唯一用途（UI 侧默认源选择）在候选改成 `meting` / `baidu` / `jamendo` 之后**本来就必须**用字符串 key 表达——枚举里根本没有 baidu / jamendo。留一个只剩一个值的枚举，只会让下一个人再问一遍「这个枚举为什么存在」。

⚠️ **真正的修复点不在 `AppPreferences`，在 `NetworkMusicManager.defaultSource` 的 getter**（`:124`）。

它现在是 `get() = defaultSourceProvider()` —— 把持久化字符串**原样透传**出去。删枚举后，老用户 DataStore 里的 `"alapi"` 照样流出，`services["alapi"]` 仍是 `null`，**bug 一点没修**。

`AppPreferences` 修不了这件事：它不知道运行时注册了哪些源（`services` map 在 `NetworkMusicManager` 里）。所以必须改 getter：

```kotlin
val defaultSource: String
    get() {
        val raw = defaultSourceProvider()
        return if (services.containsKey(raw)) raw
               else services.keys.firstOrNull() ?: raw   // 无效 ⇒ 第一个已注册源
    }
```

⛔ 不能简单回退 `"meting"`——meting 也可能被用户关掉（那时它不在 `services` 里）。⛔ 也不要把校验塞进 `AppPreferences`，那是把「运行时注册表」的知识泄漏进持久化层。

**实际可启停的对象**（= 有实现类的 + 一条取链通道）：

| 对象 | key | 实现类 | 现在的「开关」是什么 | 本次 |
|---|---|---|---|---|
| Meting（源行） | `meting` | `MetingApiService` | **恒注册**（构造期塞进 map） | 新增 `settings_meting_enabled`，默认 `true` |
| 百度网盘（源行） | `baidu` | `BaiduNetdiskService` | `baiduConfig.isActive`（卡③ 已有开关） | **复用同一份**，卡④ 的百度行只代理显示 |
| Jamendo（源行） | `jamendo` | `JamendoService` | **隐式**：clientId 非空才注册 | 新增 `settings_jamendo_enabled`，默认 `true`；生效 = 开关 **且** clientId 非空 |
| 洛雪取链通道（**子块**，非源行） | `lx`（增强层，不是 service） | `LxPlayUrlEnhancer` | 无 | `settings_lx_enabled`（§8），默认 `false`；**开关在洛雪子块**（§7.1），⛔ 不与源开关并列 |

⚠️ 「源行」与「子块」是两种形态，不是两个分组：源行是 `SettingSwitch` 整行可点（§7.5 下文），洛雪子块是 Meting 行下方的竖线缩进区（§7.1）。⛔ 不要把它们读成「组 A / 组 B」——那个术语已废弃，它会导致把洛雪排到最后（§7.0 合并②）。

⛔ **不新建一个统一的「源开关 map」**。每个源各自已经有「该不该活着」的既有语义（构造期塞 map / `baiduConfig.isActive` / clientId 判空），硬塞进一个 map 会立刻产生两套真相，第一次忘同步就是「开关开着但不生效」。UI 层用**只读视图模型**统一形态就够了（⛔ 不新增存储）：

```kotlin
// SourcesSettingsState 新增一个字段（源行用；洛雪子块的开关单独走 settings_lx_enabled）
val musicSources: List<MusicSourceRow>,

data class MusicSourceRow(
    val key: String,             // "meting" / "baidu" / "jamendo"（= services map 的 key）
    val title: String,
    val status: String,          // "当前平台：酷狗 · 默认源" / "已登录 · 索引 1,284 首" / "未配置 Client ID"
    val enabled: Boolean,
)
// ⛔ 不要加 toggleable / blockedReason：它们原本只为「未接入的 Alapi / JioSaavn」服务，
//    那两项已删除（见上），留着就是没人用的字段。
```

**源行的形态见 §7.1 的卡片图**（那里是完整卡片，源行与洛雪子块在同一张图里，不重复画）。顺序固定为：

```
Meting 行  →  洛雪子块  →  百度网盘  →  Jamendo
```

- ⭐ **洛雪子块插在 Meting 与百度网盘之间**——这是唯一能表达「它属于 Meting」的排法。⛔ 不要排成「所有源行在前、洛雪在最后」，那样竖线会从 Meting 下方隔空开始，依附关系读不出来。
- ⛔ **不要再加「— 音乐源 —」这类组小标题**：加了就等于把 Meting 和洛雪拆成两个组，与本节结论相反。

- **洛雪子块的竖线紧贴 Meting 行下方开始**（中间不插其它源行），因为依附关系只对 Meting 成立。
  ⚠️ **别读成「百度网盘 / Jamendo 没有取链层」**——它们**各有自己内置的取链层**（`baiduApi` / `jamendoApi`，§7.0 合并② 那张表）。区别在于：那两条取链层是**写死在各自 Service 里**的，没有「可插拔的第二条通道」这种结构，所以它们**没有子块可挂**。洛雪之所以有子块，正是因为它是 Meting 的**外挂**通道（enhancer 从外面插进来），不是 Meting 自带的。
- 每行一个 `SettingSwitch`（**整行可点 = 切开关**）。⛔ 不要自造「行点击 = 设默认源 + 尾部开关 = 启停」的双动作行：TV 上焦点歧义，且项目里没有这种组件。
- 「默认源」只在 description 里做**只读标注**（`· 默认源`），切换动作留在卡⑤（§7.6）——两个动作放两个控件，别挤一行。
- 卡④ 底部固定一行灰字：「已关闭源的收藏与歌单将无法播放（重新开启即可恢复）」。

**为什么用 `unregisterService` 而不是「查询时过滤」**：

- 一条机制覆盖全部路径：`search`（`orderedServices()` 拿不到它）、`resolvePlayUrl`（`services[src]` 为 null ⇒ 返回 null）、`getPlaylist` / `findArtist`。⛔ 在 `orderedServices()` 里过滤只覆盖第一条。
- `unregisterService` 已经会清该源的直链缓存（`playUrlCache.entries.removeAll { it.key.startsWith("$sourceId:") }`，2026-09-22 审查修过的那个 bug）—— 正是关源需要的副作用，⛔ 不要再写一遍。
- 与既有做法一致：百度网盘开关就是 `registerService` / `unregisterService`（`NasMusicApp:623`），Jamendo 也是（`MainViewModel:3869`）。

### 7.6 「网络搜索默认源」卡改造

现状（`SourcesSettingsSection:311`）：`NetworkSource.entries.forEach { SettingActionButton(...) }`，点一行即设为默认。两个问题：候选含两个无实现的源（Alapi / JioSaavn，**已删除**，§7.5）；关闭的源仍能被选成默认。

改造后：

1. 候选 = **已实现且已启用**的源。数据来源是 `state.musicSources.filter { it.enabled }`（§7.5 的 `MusicSourceRow`），⛔ **不是** `NetworkSource.entries`——那个枚举已删除，而且它从来就不含 baidu / jamendo（§7.5 的错位说明）。
2. 关闭的源从候选消失；若当前默认源被关闭 ⇒ **自动切到第一个已启用的源**，并在卡④ 对应行下方显示一次性提示「默认源已自动切换为 X」。⛔ 不允许留下「默认源指向已关闭 / 不存在源」的状态。
3. 全部关闭 ⇒ 不渲染候选，显示红字「无可用音乐源，请在上方开启至少一个」。
4. 只有 1 个可用源 ⇒ 仍然渲染（让用户看见当前是哪个），点它无副作用。
5. **默认源的类型是 `String`（services map 的 key）**，不再是 `NetworkSource`（§7.5）。⛔ 读取时必须校验 `key in services`，无效则回退第一个已启用源——这是「老用户 DataStore 里残留 `"alapi"` 也不会坏」的唯一保证。

### 7.7 关闭源的后果与保护

| # | 场景 | 必须的行为 |
|---|---|---|
| 1 | **洛雪依赖 Meting** | enhancer 插在 `MetingApiService.resolvePlayUrlDetailed()` 内部（§5.4），meting 被 unregister 后**根本走不到**。并入卡④ 后这条**结构上已经成立**（洛雪子块就紧贴在 Meting 行下方）：Meting 关掉 ⇒ 子块整体置灰，`settings_lx_enabled` 显示为灰但保留原值，description 写「依赖 Meting 取链通道，请先启用 Meting」。⛔ 绝不允许「洛雪开关开着但不生效」——那是最难查的一类 bug |
| 2 | 关源后该源的收藏 / 歌单 | `resolvePlayUrl` → `services[src] ?: return null` ⇒ 播放失败。卡④ 底部固定灰字提示（§7.5） |
| 3 | 全部关闭 | 网络音乐页显示引导「未启用任何音乐源，请到 设置 → 音乐来源 开启」，⛔ 不要显示「搜索无结果」——用户会以为网络坏了 |
| 4 | 重启一致性 | `NasMusicApp` 构造期**按开关决定是否注册**（现有 baidu / jamendo 就是这么做的）。⛔ 不要「先全注册再注销」——中间会有一瞬所有源都在 |
| 5 | 切开关时 | 立即 `registerService` / `unregisterService`（缓存由后者自动清）；若关闭的是当前默认源，同时写新的默认源（§7.6 第 2 条） |
| 6 | **洛雪总开关切换** | **必须显式 `clearPlayUrlCache()`**。洛雪不是 service，切它**不会触发任何 `unregisterService`**；而它取到的直链缓存 key 是 `meting:<networkId>:<quality>`（`Song.networkSource` 仍是 `meting`）⇒ 不清就会「关了洛雪还在播洛雪给的链」，直到 5 分钟 TTL 到期 |
| 7 | **洛雪子块的可达性门禁** | 判据是**运行时能力探测，不是 `Build.VERSION.SDK_INT`**：`LxEngineAvailability.state` 求值时依次 `System.loadLibrary("quickjs")` / `System.loadLibrary("quickjs-android")`，任一抛 `UnsatisfiedLinkError` ⇒ 状态 `Unavailable(reason)`，洛雪子块整体置灰、总开关不可开、description 写「本机无法加载 JS 引擎，洛雪通道不可用」**并带上具体原因**（⛔ 不要只显示「不可用」）。探测结果 `by lazy` 缓存一次；⛔ 探测必须放**后台线程**——首次 `loadLibrary` 要读磁盘 + 重定位，主线程会掉帧 |

第 6 条最容易漏，写代码时必须留注释说明「为什么这里要单独清一次」。

**第 7 条为什么不按 Android 版本判断**：实测该库 `minSdkVersion 18`、`android/*` 引用全是 API 1（§9.2.1），**API 22 的老电视完全能跑**。版本号只是**相关性不是因果**——同一版 Android 上，ABI 缺失、ROM 裁剪 native 支持、可用内存不足都会导致加载失败；反过来也有低版本设备能跑。按版本号硬卡会**既误杀又漏放**：把能用的老电视置灰（用户投诉「凭什么不让我开」），却把真跑不起来的新设备放开（点开就崩）。

⚠️ **「技术不能跑」与「跑起来不划算」要分开**。如果产品上确实想让弱设备默认不折腾洛雪，正确做法是**软策略**而非硬门禁：首次进设置页时若 `ActivityManager.isLowRamDevice == true`（或 `memoryClass < 192`），把 `settings_lx_enabled` 的**默认值**置 `false`，并在子块顶部提示「本机内存较小，洛雪通道默认关闭；开启可能影响播放流畅度」——用户**仍可手动开启**。⛔ 不要做成置灰不可开：那是把性能建议伪装成技术限制，一旦这台设备其实能跑，用户会觉得被无理由限制。

### 7.8 「音乐源」与「网络高级」是什么关系（用户 2026-10-28 提问）

结论一句话：**源 = 谁提供曲库和直链；网络高级 = 那些链路内部用哪个服务器地址 / 哪个 Key。两者是「东西」和「东西的参数」，不是并列的两组开关。**

> 洛雪并入卡④ 后，「源 ↔ 端点」这层关系在界面上已经直接看得见（洛雪就在 Meting 的下一个分组里，一眼能看出它依附 Meting）。下面这张表的作用因此只剩一个：**说明哪些端点与源无关**，避免用户改完端点发现 Jamendo 没变化又以为坏了。

| 网络高级里的项 | 服务于谁 | 与「源」的关系 | 关了源之后 |
|---|---|---|---|
| **音乐搜索端点**（Meting `baseUrl`） | **只有 `meting` 源**（`MetingApiService.baseUrlProvider`） | **源的内脏**：换它 = 换 Meting 的后端，不改变「用不用 Meting」。`baidu` / `jamendo` / 洛雪**完全不用它** | 无意义（端点配置留着，重新开 meting 就恢复） |
| **MV 端点**（Bilibili） | `MvSearchManager` | **无关**：MV 链路独立于 `NetworkMusicManager`，是另一套 service 列表 | 不受影响 |
| **歌词端点**（酷狗 / 网易） | `LyricsNetworkProvider` | **无关但互补**：网络歌的歌词**先**走 `networkMusicManager.resolveLyrics(song)`（= Meting 的 lyrics 接口），失败才用这个端点；本地歌**只能**用这个端点（`LyricsManager:204-228`） | 网络歌歌词会退化到「只能靠歌词端点模糊匹配」 |
| **天气 Key** | 天气模块 | **完全无关** | 不受影响 |

⚠️ 由此带出两个可执行结论：

1. **天气 Key 放错分区了**。`SettingsSection.NETWORK` 的定义是「端点与凭据配置」，天气 Key 虽是凭据，但与音乐链路毫无关系。建议移到 `GENERAL`（或另开「天气」卡），本方案不强制改，但既然要动设置页，顺手挪比以后单开一次改动便宜。
2. **「音乐搜索端点」只服务 Meting 一个源** ⇒ 它其实是 Meting 行的属性。因此卡④「网络音乐源」里 Meting 那行的 description 要写成
   `当前平台：酷狗 · 端点 qijieya · 默认源`
   —— 让「源 ↔ 端点」的关系在界面上**看得见**，用户就不用猜为什么改了端点没影响 Jamendo。

**为什么不把端点选择直接塞进卡④（点 Meting 行展开）**：TV 上嵌套展开要处理焦点回收（展开后焦点落在哪、收起后回哪），成本远高于收益；折中是「卡④ 只显示当前端点名，改还是去网络高级改」。

### 7.9 洛雪与 Meting 同时开启时，到底用哪个（用户 2026-10-28 提问）

**一句话：搜索永远走 Meting**（洛雪协议没有搜索能力，源码实证见 §2.5.1）；**取链无条件先试洛雪**，四条判据全过就用，否则静默回退 Meting 原生通道。

⭐ **路由规则只有一个，与平台无关**：代码里**不存在**「网易永远走 Meting」这类分支。所有平台走完全相同的路径 ——
`networkPlatform` → `LxPlatformMap` 查洛雪 source → 试洛雪 → 失败回退 Meting。
下面那张表里的平台差异，是**当前这批源的质量投影**，不是 if-else。

如果明天出现一个能取 `wy` 的好源，**不需要改任何代码**，它会自动生效；反之如果酷狗的源全挂了，酷狗也会自动回退 Meting。这就是「源可插拔」的全部意义——**把「哪个平台能播」交给源决定，而不是写死在 App 里**。

> 并入卡④ 后这个问题的性质变了：它不是「两个源抢一首歌」，而是**同一个源（Meting）的两条取链通道之间的优先顺序**（§7.0 合并②）。下面按平台列的结果，本质上是在说「洛雪这条通道**当前**在哪些平台比 Meting 原生通道更强」。

命中条件四条（各自判据已在 §4.3 / §6.2 / §5.4 定过，这里只做索引）：

```
resolvePlayUrlDetailed(song, quality)
  ├─① enhancer?.tryResolve(song, quality)     ← 洛雪（§5.4）
  │     a. networkPlatform 能映射到 lx source（§4.3）
  │     b. 请求档位 ∩ 声明 qualitys ≠ 空（§6.2，空交集直接放弃）
  │     c. musicUrl 返回 ^https?:// 且不含 undefined
  │     d. PlayabilityProbe 一跳验活通过（L3）
  │     四条全过 ──► 直接返回（洛雪赢，不再走 ②）
  └─② 原 302 跟跳 + 验活链路（Meting）        ← 洛雪失败/未启用时
```

**当前源生态下的观测结果**（依据 §2.4.1 实测）—— ⛔ **读法很重要：这是「现在会发生什么」，不是「以后都这样」，更不是路由规则**：

| 平台 | **当前**实际用谁 | 为什么会这样（**源侧原因，与路由逻辑无关**） |
|---|---|---|
| `kg` 酷狗 | 洛雪（墨澜/星海/星澜给真 FLAC） | 这批源在 kg 上的通道健康 |
| `tx` QQ | 看**用哪个源**：墨澜/星澜/HYW 走洛雪；星海 403 ⇒ 回退 Meting | 星海 `tx` 需作者授权（源侧限制，换个源就能走洛雪） |
| `wy` 网易 | **当前必然回退 Meting** | 12 个源**都取不到链**（§2.4.1）——是**这批源的能力问题**，不是协议或路由不允许 |
| `kw` / `mg` | 未验证：洛雪先试，失败即回退 | 无锚点，不知道通道好坏（§5.4.1.4）；⛔ 不要按「普遍不健康」写死结论 |

⚠️ **`wy` 那行的正确读法**：它说的是「用现在这 12 个源、在 2026-10-28 这天，`wy` 会回退」——**不**意味着「`wy` 被排除在洛雪通道之外」。代码里 `wy` 与其他平台走完全相同的路径，`LxPlatformMap` 也照常把 `netease` 映射成 `"wy"`（§4.3）。网易取链难是**脚本作者们的共同选择**（版权与风控最严，做不出来就放弃），一旦有人做出能取的源，这条通道立刻通，App 一行都不用改。
⇒ 所以本方案**不做**「按平台预判跳过洛雪」的优化：省下的那点时间，代价是把源生态的变化挡在门外。

**两条必须知道的后果**：

1. **洛雪赢的时候音质可能更好**：Meting 只给 320k 时洛雪能返真 FLAC，`actualQuality` 如实上报 `LOSSLESS`（§6.2）。所以「都开着」在酷狗上通常是升级，不是替换。
2. **洛雪输的时候要付时间成本**：最多 8 s 取链 + 3 s 验活。这就是 §5.5 要「连续 3 次失败进 `Degraded`，5 分钟内不再尝试」的原因——否则每首歌都白等。
   ⭐ **注意 `Degraded` 的粒度是 `(scriptId, source)` 而不是 `scriptId`**（§5.5）：`wy` 上连败 3 次只让「墨澜 × wy」这条组合暂停，**不会连累墨澜在 `kg` 上继续出 FLAC**。粒度错了会把「一个平台不行」放大成「整个源不行」，那才是真正该避免的。

> 「Meting 关了洛雪一起失效」不再单列：洛雪是 Meting 的取链通道，并入卡④ 后结构上已自明（§7.7 第 1 条）。

**验证按钮能直接回答这个问题**：点墨澜的「验证」，`kg ✅ FLAC` 意味着该平台上洛雪会赢；`wy ❌ 500` 意味着**用这个源**在该平台会回退 Meting。这也是 §5.4.1 把「逐平台出结果」定为硬要求的实际用途——它回答的是「**这个源**现在能不能用」，而不是「这个平台行不行」。

---

## 8. 持久化

`AppPreferences` 新增 `lx` 分组（沿用 `NetworkMusicPrefs` 门面风格）：

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `settings_lx_enabled` | Boolean | **false** | 洛雪取链通道总开关（卡④ Meting 行下的**洛雪子块**，§7.1），默认关（合规 + 不影响现网）。Meting 关掉时灰显但保留原值 |
| `settings_lx_sources` | String(JSON) | **墨澜 + 星海两条**（§2.2 唯二 `kg` 免费且能出 FLAC 的源） | `[{id,name,url,enabled,version,installedAt,lastStatus}]` |
| `settings_meting_enabled` | Boolean | **true** | Meting 总开关（§7.5）。默认 true ⇒ 升级后行为不变 |
| `settings_jamendo_enabled` | Boolean | **true** | Jamendo 总开关。**生效 = 本开关 && clientId 非空**（两条件都要） |

> key 前缀统一 `settings_`，与该文件既有的 `settings_default_network_source` / `settings_jamendo_client_id` 一致。

百度网盘的开关**不新增 key**，继续用 `baiduConfig.isActive`（§7.5：⛔ 不制造两套真相）。

正文不入 DataStore（§5.3）。脚本文件路径 `filesDir/lx_sources/`，随 App 卸载清理。

**开关切换必须清直链缓存**：Meting / Jamendo / 百度走 `unregisterService` 自带的按源清理；**洛雪必须显式调一次 `clearPlayUrlCache()`**（§7.7 第 6 条：洛雪不是 service，切它不触发任何注销）。

### 8.1 验证结果的持久化形态

**不新增 key**，复用 `settings_lx_sources` 里已有的 `lastStatus` 字段存**紧凑结果**——完整 `LxProbeReport`（含每次尝试的 URL、响应头）只在内存里供详情面板展开，进程重启后丢；持久化只留「下次进设置页先显示什么」这一层：

```json
"lastStatus": {
  "ts": 1793200000000,
  "l1": "ok",
  "version": "2.3.4",
  "truncated": false,
  "platforms": {
    "kg": { "v": "ok",   "q": "flac", "c": "FLAC" },
    "tx": { "v": "ok",   "q": "flac", "c": "FLAC", "mismatch": false },
    "wy": { "v": "dead", "r": "HTTP_5XX" },
    "kw": { "v": "untested", "r": "NO_ANCHOR" }
  }
}
```

- 约 200 B / 源，塞进现有 JSON 完全无压力；⛔ **别把完整报告序列化进去**（含 URL 与响应头，动辄几 KB，且下次启动时那些 URL 早已过期，存着只会误导）。
- 读取规则：进设置页先渲染 `lastStatus`（带时间戳）；`ts` 超过 **24 h** 则整串置灰并标「结果已过期」，但**不清除**——「昨天 kg 还是好的」本身是有用信息。
- ⛔ 验证结果**只用于展示**，加载时不得据此改 `enabled` 或喂给 `LxSourceRegistry`（§5.4.1.7 第 1 条）。

---

## 9. 风险与对策

### 9.1 风险矩阵

| # | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| R1 | **QuickJS-android 的 `.so` 未做 16KB 页对齐**（实测 8 个库全 `0x1000`） | 高 | lint Warning（现 256 个，不 fail）；targetSdk 35 后变 Error。**只影响 Android 15+ 的 16 KB 页设备**——4 KB 页老电视（含基准机 5.1.1）不受影响 | 见 §9.2 / §9.2.1 |
| R2 | **QuickJS-android 停更（2021-10）** | 已发生 | 无安全更新、新架构不跟进 | 见 §9.2 Plan B |
| R3 | **音源随时失效** | 已发生（§2.2） | 功能形同虚设 | 源可插拔 + 状态可见 + 不押注单一源 |
| R4 | **合规**：脚本绕过版权限制、批量下载封 IP | 高 | 法律风险 / 用户被 Ban | 见 §9.4 |
| R5 | 脚本是任意 JS，可死循环 / 吃内存 | 中 | ANR / OOM | 独立 runtime + 8s 超时 + `close()` 硬中断 |
| R6 | 333 KB 脚本 + runtime 内存占用 | 中 | 电视（1~2 GB RAM）吃紧 | 懒加载（首次用到才 load）；**驻留 ≤2 个 runtime（LRU），但并发执行恒为 1**——验证跨源串行，播放与验证共用同一 host（§5.4.1.5） |
| R7 | 洛雪 `musicInfo` 字段与 Meting id 在某些平台不一致 | 中 | 取链失败 | 已实测 3 个平台（§2.4）；其余平台首次使用即验证，失败自动回退 |
| R8 | 脚本更新后 API 变化 | 中 | 需改宿主 | `lx.version` 声明 + 加载失败提示「脚本需要更新的 App 版本」 |
| R9 | **验证结果被误读**：把「无锚点未测」当「不可用」，或把「存疑」当「能用」 | 中 | 用户误删好源 / 误信坏源 | 四档 verdict 分开显示（✅/⚠️/❌/?）；无锚点固定附「不代表不可用」；失败原因给人话不给人状态码（§5.4.1.4 / §7.3） |
| R10 | **验证阻塞播放**（共用 host 导致取链排队） | 中 | 按了播放不出声，比功能失效更伤 | `probing` 标记 + `tryAcquire` 返回 null ⇒ 直接走 Meting（§5.4.1.7 第 3 条） |
| R11 | **关源后状态自相矛盾**：默认源指向已关闭的源、收藏的歌静默播不了 | 中 | 用户以为 App 坏了 | 默认源随关源自动切换 + 卡④ 底部固定提示 + 网络音乐页全关引导（§7.6 / §7.7） |
| R12 | **洛雪开关「开着但不生效」**（Meting 被关） | 中 | 最难排查的一类 bug | 洛雪开关依赖 Meting，未满足时置灰并说明原因（§7.7 第 1 条） |
| R13 | **个别设备加载不了 QuickJS native 库**（ABI 缺失 / ROM 裁剪 native 支持 / 内存不足） | 中 | 洛雪通道不可用——但**不影响 App 其他任何功能** | 运行时能力探测，失败则子块置灰（§7.7 第 7 条 / §9.2.1） |

### 9.2 洛雪脚本通道的退出策略（Plan B）

⚠️ **先消一个误解**：QuickJS 和**搜索**没有任何关系——搜索永远走 Meting 的 HTTP 端点（§2.1 / §2.5.1 / §7.9），从来没打算用 QuickJS 做。QuickJS 的唯一职责是 `LxScriptHost`（§5.1）执行第三方洛雪 `.js` 脚本的取链（`getMusicUrl`）。所以本节讨论的不是「搜索的备胎」，而是**「洛雪这条取链通道还能不能维持」**。

**触发条件**：升 `targetSdk 35`、或 lint 因 `.so` 对齐报 Error、或 QuickJS-android 出现无法绕过的问题。**当前 `targetSdk 34`，不触发**——本节是提前备案，不是待办。

| 档位 | 方案 | 代价 |
|---|---|---|
| B1 | 自编译 quickjs（`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`）产出对齐的 `.so` | 一次性工程；之后自己维护 NDK 构建 |
| B2 | 换 WebView（要求 WebView ≥ 60） | minSdk 22 的老电视上 WebView 版本普遍过低，**实际可用面比 B1 窄**；且有主线程约束 |
| B3 | **纯 Kotlin 实现内置源**（放弃通用性） | 只能覆盖取链逻辑简单的源（星海 `kg` / JuheApi 的 `tx`，§2.2 参数齐全）；脚本一更新就腐烂，且拿不到新源。**等价于放弃洛雪通道**，届时须同步改写 §12 与 §7.1 的洛雪子块 |

**建议**：先按 B1 预留——依赖引入时就检查 `.so` 对齐情况（`llvm-readelf -l` 看 `LOAD` 段 `Align` 是否 ≥ 0x4000），若未对齐，先评估是否值得自编译。**这是实施第一步的验收项（T1）。** 一条命令的成本，但它决定后面 T2–T11 值不值得做，所以必须在 T1 做完、⛔ 不能留到出问题再查。

#### 9.2.1 老设备兼容性实测（2026-10-28，结论：minSdk 22 无冲突）

一个很自然的担心是「用洛雪 ⇒ 必须 QuickJS ⇒ `minSdk 22` 的老电视用不了」。直接解包 `quickjs-android-1.4.6.aar` 实测，**这条推理链每一环都不成立**，而且最后一环的方向是**反的**。

| 检查项 | 实测结果 | 对 API 22（Android 5.1.1）的影响 |
|---|---|---|
| AAR `minSdkVersion` | **18**（manifest 与源码一致） | ✅ 无冲突。22 > 18，manifest merger 不会失败 |
| ABI 覆盖 | `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64`，共 8 个 `.so` | ✅ 老电视（v7a 或 arm64）都有对应库 |
| `android/*` 引用 | 仅 `Handler` / `HandlerThread` / `Looper` / `Log`，**全部 API 1** | ✅ 无高版本 API |
| `java/lang/invoke/MethodHandles` | 只出现在 lambda 的 `BootstrapMethods` 签名里（`LambdaMetafactory`） | ✅ D8 会 desugar，运行时不加载这个 API 26+ 的类 |
| `.so` 的 `PT_LOAD p_align` | **8 个库全部 `0x1000`（未对齐）** | ✅ **4 KB 页的老电视正好匹配，`dlopen` 正常**；❌ 只在 16 KB 页设备（Android 15+）上加载失败 |

⭐ **最后一行是本方案最容易被搞反的地方**：`0x1000` 未对齐**不是「老设备跑不了」的原因，恰恰是「将来新设备跑不了」的原因**。老电视页大小就是 4 KB，`p_align = 0x1000` 正是它要的值。所以 §9.1 R1 的风险方向是「升 `targetSdk 35` 后 Android 15+ 装不上」，与老电视完全无关。

**唯一真实成本是 APK 体积**：全 ABI +2.1 MB；按本项目 `abiFilters`（`arm64-v8a` / `armeabi-v7a` / `x86_64`，不含 `x86`）约 **+1.9 MB**。

⇒ **「关掉洛雪就不用 QuickJS」在两个层面结论不同**：
- **内存层面成立**：`.so` 只在 `System.loadLibrary()` 时才映射进进程，不开启洛雪就永远不加载，老电视（1 GB RAM）不会被它占掉 15 MB。
- **APK 体积层面不成立**：`.so` 是**无条件打包**的，关开关不会让它消失。要连体积一起去掉只能拆 flavor 出两个包——电视 sideload 场景让用户「按设备选包」不现实，**不建议**。

**所以门禁的判据不该是「Android 版本」，而是「这台设备到底能不能加载」**——见 §7.7 第 7 条。

### 9.3 内存与性能预算

| 项 | 预算 |
|---|---|
| 单脚本 runtime 常驻 | < 15 MB |
| 同时驻留 runtime | ≤ 2（LRU 淘汰） |
| 单次 `getMusicUrl` 超时 | 8 s（含脚本内 HTTP） |
| 首次加载（含下载脚本） | < 10 s，后台线程，不阻塞 UI |
| 播放起播额外延迟 | < 300 ms（源已加载时，纯本地判定 + 一次 HTTP） |
| **验证：单次 L3 读取字节数** | **512 B**（`Range: bytes=0-511`，⛔ 绝不整首下载） |
| **验证：单源总耗时** | ≤ 60 s（含 L1；超预算即截断并如实报「已测 N/M」） |
| **验证：单源典型耗时** | `SWEEP` 21 s / `QUICK` 65 s / `FULL` 110 s / `MATRIX` 200 s（§5.4.1.5） |
| **验证：全部验证（`SWEEP`）** | **≤ 5 min**（12 源 × 21 s ≈ 4.2 min），超时如实报「已测 N/M」 |
| **验证：额外 runtime** | **0**（复用同一 `LxScriptHost`，不新开 —— §5.4.1.5） |

### 9.4 合规与免责（必须落到代码与 UI）

- 脚本**不随 APK 分发**，只内置 URL；由用户主动开启
- 总开关**默认关闭**
- 设置页固定文案：「音源脚本由第三方提供，仅供个人学习研究；请勿批量下载，否则可能被服务方封禁 IP」
- 遵循各源作者明示的使用约束（§2.2 / 附录 B.4）：星海未授权仅 `kg` 可用、Huibq 最高 320k、均禁止批量下载、避免频繁切歌，否则封 IP
- **下载功能不接洛雪源**：`StreamUrlResolver` 保持只走 Meting，避免「批量下载」触发封禁

---

## 10. 实施计划

> 依赖关系：T1 是门禁——它是唯一引入 QuickJS 的一步，不通过则**阶段 1 之后的洛雪通道整体转 §9.2 的 B1/B2/B3**（**阶段 0 不受 T1 影响**，可先行；搜索链路任何情况下都不受影响）。
> 编号说明：阶段 3.5（T20–T23）是后追加的「验证」功能，为不打乱既有 T1–T19 而顺延编号 ⇒ **执行顺序按阶段走，不按 T 编号大小走**。

### 阶段 0：设置页调整（1.5~2 天，**不依赖 QuickJS**）

> 这一段是 §7.0 / §7.5 / §7.6 / §7.8 的落地，外加 §7.7 的**第 1–6 条**（关源后果与保护），**和洛雪脚本无关**，可以先做、先发版。
> ⚠️ §7.7 **第 7 条**（引擎可用性门禁）**不在本阶段**——它要 `LxEngineAvailability`，随 T1 一起做。
> 收益独立：**删除 Alapi / JioSaavn 与整个 `NetworkSource` 枚举**（§7.5 决策），修掉「默认源可选到无实现的源」这个既有 bug，并把「索引与封面」归位。
> ⚠️ 本阶段只建卡④ 的**源行**（Meting / 百度 / Jamendo 三行）+ `SettingsSubGroupLabel` 组件；**洛雪子块在阶段 3 的 T14** —— 源行先发版时卡④ 里没有洛雪，不会出现半截的子块。

| # | 任务 | 产物 / 验收 |
|---|---|---|
| S1 | `AppPreferences` 新增 `settings_meting_enabled` / `settings_jamendo_enabled`（默认 **true**）+ 门面方法 | 单测：默认值；`isSourceEnabledSync` 对 baidu 走 `baiduConfig.isActive`、对 lx 走 `settings_lx_enabled`（此时 lx 恒 false） |
| S2 | `NasMusicApp` 构造期按开关决定注册；`MainViewModel` 新增 `setSourceEnabled(key, enabled)`（注册/注销 + 默认源自动切换） | 单测：关默认源后默认源切到第一个已启用源；全关时返回 null 且不崩 |
| S3 | `SourcesSettingsSection` 新增卡④「网络音乐源」**源行**（Meting / 百度 / Jamendo 三行开关）；卡⑤ 候选改为「已实现且已启用」；新增 `SettingsSubGroupLabel`（竖线 + 小标题，`drawBehind` 画线，⛔ 非卡片、非可聚焦） | 真机：**三行开关、没有「未接入」行**（Alapi / JioSaavn 已删，§7.5）；关源后该行立刻反映 |
| **S4** | **「索引与封面」并入卡③ 百度网盘**（§7.0 合并①）：原卡④ 整段删掉，索引状态 / 重建索引 / APIC 进度搬到卡③ 的路径配置之后；⛔ **索引区块不要再挂外层 `if`**（卡③ 本身无条件渲染，`onToggleBaiduEnabled?.invoke()` 只是可空调用）；卡级说明改成「索引决定百度网盘曲库能否被搜到」 | 真机：`SOURCES` 只剩 5 张卡；索引进度与重建按钮在百度网盘卡内，且与卡③ 同进同出、⛔ 不会单独凭空消失。运行时索引代码零改动 |
| S5 | 网络音乐页「未启用任何音乐源」引导 | 真机：全关后进网络音乐页显示引导，⛔ 不是「搜索无结果」 |
| S6 | 卡④ 的 Meting 行 description 加「端点 qijieya」（§7.8 结论 2） | 真机：改端点后卡④ 显示跟着变，`jamendo` 行不受影响 |
| S7 | 多语言字符串（7.4 第二组）+ lint | `lintDebug` 无 `MissingTranslation` |

> 「天气 Key 挪出网络高级」建议同批做（§7.8 结论 1），但不阻塞本阶段——它只涉及 `NetworkMusicSection` 删一块 + `GeneralSettingsSection` 加一块。

### 阶段 1：宿主基础设施（3~4 天）

| # | 任务 | 产物 / 验收 |
|---|---|---|
| T1 | 引入 QuickJS 依赖，检查 `.so` 对齐与 ABI 覆盖；实现 `LxEngineAvailability`（§5.1） | ① `assembleDebug` 通过，**manifest merger 不报 minSdk 冲突**（实测库为 18，低于项目 22）；② `llvm-readelf -l` 确认 3 个 ABI 的 `Align`（预期全 `0x1000` = §9.2 的已知项，**不是失败**）；③ 记录 lint 增量；④ **真机（创维 5.1.1 / API 22）跑一次 `LxEngineAvailability.state` 必须为 `Available`**——这是「老电视能不能用」的最终判据，⛔ 不接受「推测应该能跑」 |
| T2 | `LxScriptHost` 骨架：runtime/context 生命周期、后台线程、`close()` | 单测：加载一段 ES6 脚本能跑通并返回值 |
| T3 | `boot.js`：console / Buffer / timer / `lx.*` 垫片 | 单测：`lx.EVENT_NAMES`、`on`、`send` 语义正确 |
| T4 | `lx.request` 桥接（OkHttp + 异步回调 + cancel + JSON body 自动解析） | 单测：MockWebServer 返回 JSON → JS 侧 `resp.body.url` 可读 |
| T5 | `lx.utils` 桥接（buffer / crypto.md5·aes·rsa·randomBytes / zlib） | 单测：md5 与 Java 侧 `MessageDigest` 一致 |
| T6 | `inited` 解析 → `LxScriptInfo`；超时与失败态 | 单测：用**五个**真实脚本做夹具——**星海 / Huibq 应 Loaded，Flower / 六音应 Failed**（§2.2 实测期望值）。⚠️ **JuheApi 不进夹具**：它 `init.conf` 间歇失败（§2.2「时好时坏」），期望值本身不稳定，塞进门禁就是把单测变成 flaky；它只在 T19 真机复验时观察 |

### 阶段 2：业务接入（2~3 天）

| # | 任务 | 产物 / 验收 |
|---|---|---|
| T7 | `Song.networkPlatform` + `parseSongs` 填充 | 单测：搜索结果的 platform 正确；null 回退路径 |
| T8 | `LxPlatformMap` + `LxMusicInfo` 构造 | 单测：5 平台映射表全覆盖 + 非法平台返回 null |
| T9 | `LxQualityMapper`（双向 + 交集判定） | 单测：5 档位 × 4 洛雪档位矩阵，覆盖「空交集 → null」 |
| T10 | `LxPlayUrlEnhancer` + 注入 `MetingApiService` | 单测：enhancer 抛异常时不影响原链路 |
| T11 | `LxSourceRegistry` 状态机 + **两级健康状态** + 多源排序 | 单测：① **核心自证**——`(scriptId, wy)` 连败 3 次进 `Degraded`，同时 `(scriptId, kg)` 仍 `Healthy`（粒度错了这条必红）；② 到期恢复一次；③ 脚本级 `Broken` 与平台级 `Degraded` 互不覆盖 |

### 阶段 3：持久化与 UI（2~3 天）

| # | 任务 | 产物 / 验收 |
|---|---|---|
| T12 | `LxScriptRepo`（文件正文 + DataStore 元数据 + 安装/更新/回滚） | 单测：更新失败保留旧版 |
| T13 | `AppPreferences.lx` 分组 + 门面方法 | 单测：默认值、开关联动清缓存 |
| T14 | **`SourcesSettingsSection` 卡④ 内新增**洛雪子块**（紧贴 Meting 行下方）**（`SettingsSubGroupLabel` + 脚本列表 / 导入 / 刷新 / 状态）；⛔ 不是 `NetworkMusicSection`、⛔ 不是独立卡片（§7.0 合并②） | 真机：子块紧贴 Meting 行下方、同一张卡内；无「框中框」 |
| T15 | 多语言字符串（`values` + `values-en`，§7.4 **第一组**：洛雪） | lint 无 MissingTranslation |

### 阶段 3.5：「验证」按钮（1.5~2 天）

| # | 任务 | 产物 / 验收 |
|---|---|---|
| T20 | `LxUrlInspector` + `LxMagic` + `judgeL3`（含「比 `classify()` 更严」的差异注释） | 单测：5 类魔数 × 3 类 `Content-Type` × 10 个状态码的判定矩阵；**另加一条自证**：`HttpPlayabilityProbe.classify` 的既有用例全部保持通过（证明没改它） |
| T21 | `LxProbeRunner` + `LxAnchorTable` + 预算/取消 + `Flow<LxProbeEvent>` | 单测（假 host 注入，不打网络）：L1 超时 / `EMPTY_URL` / `HTTP_403` / `HTTP_5XX` / `NO_ANCHOR` / 预算耗尽 / 取消 7 条路径；`tryAcquire` 在 `probing` 期间返回 null。**另加四档深度的自证**：`SWEEP` 只产出 `kg` 的尝试、其余平台 reason 必须是 `SKIPPED_BY_DEPTH`（⛔ 不是 `NO_ANCHOR`）；`QUICK` 产出全部已声明平台；`FULL`/`MATRIX` 触发截断时 `truncated == true` |
| T22 | UI：「验证」按钮 + 状态机 + 实时进度 + 内联详情 + 原因文案 | 真机：点击→逐平台出结果；探测期间按播放**立刻出声**（走 Meting，不排队）；TV 遥控器可达、焦点视觉正确 |
| T23 | 洛雪子块的 `settings_lx_enabled` 总开关接上（§7.1），并实现「Meting 关掉 ⇒ 子块整体置灰」（§7.7 第 1 条） | 真机：关掉 Meting 后子块置灰并说明原因；切洛雪总开关会清直链缓存（关掉后不再走洛雪链） |

> T20/T21 是本方案**唯一有真实夹具期望值**的部分：用 §2.2 的实测表当断言——墨澜 `kg` 必须 ✅、`wy` 必须 ❌、长青必须 L1/L2 过而 L3 全 ❌ 404。这三个脚本因此同时是「验证功能的回归测试」和「宿主实现的自证」。

### 阶段 4：验证与收尾（2 天）

| # | 任务 | 产物 / 验收 |
|---|---|---|
| T16 | 全量单测 + lint | `testDebugUnitTest` 全绿（基线 2203 例，新增用例不得回归）；`lintDebug` 0 Error |
| T17 | 真机验证（电视 192.168.0.114，SDK 22 / Android 5.1.1） | 见 §11 验收清单 |
| T18 | 文档：`technical-overview.md` §10.N + `CHANGELOG.md` | CHANGELOG 只写用户可感知的「做了什么」 |
| T19 | 墨澜 / 星海真机复验 | 真机 + 真宿主下重跑 L1/L2/L3，确认 `kg` 仍可播、`wy` 失败提示正确；结论回写 §2.2 |

**总计约 12~16 人日**（含阶段 0 的 1.5~2 天；阶段 0 可独立先行发布）。

---

## 11. 验收标准

### 11.1 功能

**设置页 · 源总开关（阶段 0，独立于洛雪）**

- [ ] `SOURCES` 分区出现「网络音乐源」卡，Meting / 百度网盘 / Jamendo 各一行开关，**整行可点**切开关
- [ ] **洛雪子块紧贴 Meting 行下方**（中间不插百度网盘 / Jamendo），不是独立卡片；有**左侧 2 dp 竖线**（`drawBehind` 画），⛔ 不是「框中框」
- [ ] 竖线**不影响焦点顺序**：遥控器从 Meting 行往下，依次经过子块的开关 / 各脚本行 / 底部按钮，再往后是百度网盘，⛔ 不会卡在竖线上
- [ ] 竖线与源行**左缘对齐**（子块只缩进内容，行宽不因竖线变窄）
- [ ] **Alapi / JioSaavn 已从代码中彻底删除**：`NetworkSource` 枚举不再存在；卡④ 无「未接入」行；卡⑤ 候选只有已实现的源；旧 DataStore 值 `"alapi"` / `"jiosaavn"` 自动回退到**第一个可用源**且不崩（⛔ 不是简单回退 meting——meting 也可能被关掉）
- [ ] 关闭某源后：该源不参与搜索、`resolvePlayUrl` 返回 null、该源直链缓存被清（`unregisterService` 自带）
- [ ] 关闭**当前默认源**时，默认源**自动切到第一个已启用源**，并在行下方提示切换结果；⛔ 不留「默认源指向已关闭源」
- [ ] 关闭**全部**源后，网络音乐页显示「未启用任何音乐源」引导，⛔ 不是「搜索无结果」
- [ ] 重启 App 后开关状态保持，且构造期按开关注册（⛔ 不是「先全注册再注销」）
- [ ] 关闭 **Meting** 后，**洛雪子块整体置灰**（`settings_lx_enabled` 灰显但保留原值）并说明「依赖 Meting 取链通道」（⛔ 不允许「洛雪开着但不生效」，⛔ 也不是整块隐藏）
- [ ] 切洛雪总开关会清直链缓存（关掉后不再播洛雪给的链，§7.7 第 6 条）
- [ ] 升级（从旧版本）后首次启动：行为与旧版**完全一致**（`settings_meting_enabled` / `settings_jamendo_enabled` 默认 true）

**设置页 · 卡片归位（阶段 0，独立于洛雪）**

- [ ] **「索引与封面」并入「百度网盘」卡**：`SOURCES` 只剩 **5 张卡**（①NAS ②本地曲库 ③百度网盘(含索引) ④网络音乐源(含洛雪) ⑤网络搜索默认源）；索引状态 / 重建索引 / APIC 进度都在百度网盘卡内
- [ ] 合并后索引区块**不再有独立的外层 `if`**（与卡③ 一致恒渲染）；`onRebuildBaiduIndex` 为 null 时按钮只是点了没反应，⛔ 不会整块消失
- [ ] 卡级说明文案改成「索引决定**百度网盘曲库**能否被搜到」（原来写「曲库」，会被读成全部曲库）
- [ ] 卡④ 的 Meting 行显示「端点 qijieya」，改端点后跟着变；Jamendo / 百度网盘行**不受端点影响**（§7.8）

**验证按钮（洛雪核心交互）**

- [ ] **每个源（含自定义源）行上都有「验证」按钮**，点击后按 L1→L2→L3 三级实测，**逐平台**出结果（`kg ✅ FLAC · wy ❌ 500`）
- [ ] 对墨澜/星海点验证，结果与 §2.2 实测表一致：`kg` ✅、`wy` ❌；对长青点验证，L1/L2 过而 L3 全 ❌ 404（**这两条是「宿主没写错」的判据**）
- [ ] 无锚点的平台（`kw` / `mg`）显示 `? 无锚点未测`，**⛔ 绝不伪造结果**
- [ ] 自定义源只声明 `kw` 时，验证只出 L1 结果，并说明「无测试样本，未测（不代表不可用）」
- [ ] 源声明 `flac` 而实际容器是 MP3 时，显示 `⚠️ 标称 FLAC 实为 MP3`
- [ ] 进度**实时**：已完成的平台立刻出结果，当前平台显示 `⏳`，不等全部跑完
- [ ] **`全部验证` 用 `SWEEP` 深度**：12 个源在 **5 min 内**跑完并逐源给出结论（≈21 s/源）；⛔ 不是「3 分钟后只测完 2~3 个源」（§5.4.1.5）
- [ ] **`SWEEP` 未覆盖的平台显示「批量验证未覆盖该平台」**（`SKIPPED_BY_DEPTH`），⛔ 不显示成「无锚点」或「不可用」——两者含义不同（§7.3）
- [ ] 单源「验证」默认 `QUICK`（65 s，可能最后一个平台记「未测（超预算）」）；`FULL` / `MATRIX` 需二次确认且文案写明会超预算
- [ ] **降级粒度按平台**：在网易连播 3 首失败后切到酷狗，**仍然走洛雪取链**（日志 `lx: hit source=kg`）——⛔ 不会因为网易失败就整体停用洛雪（§5.5）
- [ ] **验证期间按播放立刻出声**（`tryAcquire` 返回 null ⇒ 走 Meting，不排队等探测）
- [ ] 验证结果**不改变**源启用状态、不写 `Degraded`、不清直链缓存、不写 `deadProbeCache`
- [ ] 点「停止」/ 离开设置页后，在途请求被 `__nativeCancel` 掉（脚本不会在后台继续跑到 8 s 超时）
- [ ] 结果持久化后可重进设置页直接看到（带时间戳）；超过 24 h 显示「结果已过期」但不清除

**主链路**

- [ ] **平台提示按「声明 + 验证结果」触发，⛔ 不按平台名**（§2.4.1）：① 所有已启用源都**没声明**当前 source ⇒ 强提示「洛雪通道无可用源」；② 有 `lastStatus` 且该平台**全 ❌** ⇒ 灰字「播放将走 Meting 原生通道」；③ 无验证结果 / 有任一 ✅ ⇒ **不提示**
- [ ] **「走回退」不被显示成「播不了」**：网易平台上洛雪全失败时，**播放仍然出声**（Meting 原生通道接管），提示是信息级而非错误级；⛔ 不会劝用户换平台当成唯一出路
- [ ] 总开关关闭时，网络音乐播放行为与现网**逐字节一致**（对比日志与直链）
- [ ] 开关打开后，酷狗平台歌曲播放走洛雪取链（日志有 `lx: hit source=kg`）且**验活通过**
- [ ] 取链返回 URL 但验活失败时（模拟长青那种 404），**判定为失败并回退 Meting**，不把死链交给 ExoPlayer
- [ ] 洛雪取链失败时自动回退 Meting，用户侧无感（最多多等 8s 超时）
- [ ] 选「无损」且源只声明 128k 时，**不静默降级**，直接走 Meting
- [ ] 脚本死循环 / 抛错不影响 App（8s 超时后回退，UI 显示「源异常」）
- [ ] 源回 `403 未授权` 时，UI 提示「该平台需作者授权」而不是笼统的「播放失败」

### 11.2 工程

- [ ] `assembleRelease` 通过
- [ ] `testDebugUnitTest` 全绿，新增用例覆盖 §10 各 T 项
- [ ] `lintDebug` **0 Error**；新增 Warning 需在 PR 说明里列出并给出理由（重点看 `Aligned16KB`）
- [ ] APK 体积增量 < 2 MB（实测预估 **+1.9 MB**：`abiFilters` 三个 ABI × 2 个 `.so`，§9.2.1；若超限优先砍 `x86_64` 或改 ABI split）
- [ ] 真机（Android 5.1.1）播放流畅，无 ANR；`dumpsys meminfo` 增量 < 60 MB
- [ ] **`NetworkSource` 删除的连带验收**：`DefaultNetworkSourcePrefTest` 改用字符串 key 并新增「旧值 `"alapi"` / `"jiosaavn"` 回退第一个可用源」用例；`NetworkArtistSongIdContractTest` 里 `NetworkSource.METING.key` 的断言改成字面量 `"meting"`（枚举没了，但红线 2 的断言不能跟着消失）
- [ ] `LxEngineAvailability.state` 在真机（Android 5.1.1）为 `Available`；**负向自证**：把 `.so` 改名后为 `Unavailable`，洛雪子块置灰、点击不崩、其余功能不受影响（§7.7 第 7 条）

### 11.3 文档

- [ ] `technical-overview.md` 记录：宿主线程模型、`lx.*` 实现清单、字段映射表、音质映射、**三级验证判据与锚点表（含「验证比 `classify()` 更严」的差异说明）**
- [ ] `CHANGELOG.md` 一条：「新增洛雪音源支持，可在设置中导入第三方音源脚本获取播放直链」+ 一条「每个音源支持一键验证可用性（逐平台显示能否播放）」
- [ ] 本文档归档到 `docs/archive/` 并加归档状态头

---

## 12. 明确不做

| 不做 | 原因 |
|---|---|
| 用洛雪脚本做搜索 | 协议不支持（§2.1 / §2.5.1）；要自带搜索请走 MusicFree 协议（§5.6） |
| 移植洛雪 musicSdk 的 eapi/weapi 实现 | 7 平台 × 每个 10+ 文件的持续维护无底洞（§2.5.1 目录结构 + §9.2 B3） |
| 下载走洛雪源 | 会触发批量下载封禁（§9.4） |
| 自动从网络发现/订阅音源列表 | 合规风险；只做「用户手动填 URL」 |
| 脚本市场 / 评分 / 评论 | 超出范围 |
| **验证失败自动停用源 / 定时自动验证** | 「源可插拔」的前提是源能自己回来：作者修好后源就恢复了，自动停用会让用户莫名其妙播不了，且恢复不会自动开（§5.4.1.7）。验证只做**手动触发 + 结果展示** |
| **为 `kw` / `mg` 编造测试样本** | 假 id 得到的 404 会被读成「源不支持该平台」，把「我们没样本」谎报成「源不行」（§5.4.1.4） |
| 修改 `NetworkMusicManager` 的 fallback 语义 | 现有语义（search 才 fallback，resolve 精确路由）是刻意设计 |
| **新建「四源开关 map」统一存状态** | 四个源各有既有语义（构造期塞 map / `baiduConfig.isActive` / clientId 判空），统一存必然两套真相（§7.5） |
| **给 Alapi / JioSaavn 造开关** | 它们没有实现类，且**已连同 `NetworkSource` 枚举一起删除**（§7.5 决策）——不是「本期暂不做」，是永远不会做 |
| **「管理洛雪 ›」跨分区跳转** | 洛雪已并入卡④（§7.0 合并②），本需求消失；且 TV 上跨分区跳转要新增导航状态 + 返回栈，`navVM.settingsSection` 是单值，跳过去回不到原位 |
| 内置脚本正文 | 版权与合规（§9.4） |

---

## 附录 A：取证脚本与数据

探针位于 `logs_temp/lxprobe/`（临时目录，不入库）：

| 文件 | 用途 |
|---|---|
| `probe.js` | 完整宿主模拟：真实 HTTP、`lx.*` 全量 stub、逐字段探测 musicInfo 依赖 |
| `direct.js` | 定点调用 `handler({source, action:'musicUrl', ...})`，抓请求 URL 与 header |
| `v2/probe3.js` | 批量版：逐源跑 L1/L2，带 UA（juhe/lx 会校验 UA） |
| `v2/probe4.js` | **L3 版**：取链后立刻请求该 URL，验 `Content-Type` 与容器魔数 —— **这就是 `LxUrlInspector` + `judgeL3` 的原型**（§5.4.1.3），实现时照着它搬，别重新发明判据 |
| `v2/e2e.js` | **端到端**：Meting 搜酷狗 → hash → 洛雪 kg 源 → 验活（§2.4.1 的证据） |
| `v2/kgfield.js` | 精确测定某个源依赖 musicInfo 的哪个字段（只给 hash / 只给 songmid 对比） |
| `v2/collect.js` | 批量抓取 GitHub 候选仓库里的 `.js` 下载地址 |
| `v2/*.js` / `v2/batch2/*.js` | 12 个源原文（huibq 官方与 pdone 镜像逐字节一致） |
| `v2/out*.txt` / `v2/e2e-out.txt` / `v2/kgfield-out.txt` | 各轮探测输出 |

**Flower 字段映射的取证方法**（可复现）：对每个 source，分别只给 `songmid` / `hash` / `copyrightId` 之一，观察构造出的 URL——给错字段时 URL 里出现 `undefined`。结果见 §2.3。

⚠️ 复现提醒：

- 探针输出曾被 stdout 吞掉（同一命令复跑才现），**判据要写文件再读**，不要只看终端。
- 本机 curl 有代理注入（`HTTP/1.1 200 Connection Established`），测第三方端点要加 `--noproxy '*'`，否则拿到的是代理的 CONNECT 响应而不是真实 body。
- 部分源（juhe / lx）**校验 UA**，请求必须带 `User-Agent: lx-music-mobile/2.0.0`，否则返回空。

## 附录 B：候选音源清单（2026-10-28 实测）

### B.1 脚本地址清单

> 每个源的 L1/L2/L3 实测结论、版本、体积、声明平台与音质见 **§2.2**，此处只留地址，避免两处判定口径各自漂移。

| 源 | 脚本地址 |
|---|---|
| **墨澜 Molan** | `https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/墨澜2.3.4.js` |
| **星海 XingHai** | `https://zrcdy.dpdns.org/lx/xinghai-music-sourcev2.3.15.js` |
| **星澜 StellarWave** | `https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/星澜4.0.0.js` |
| **1010 音源** | `https://raw.githubusercontent.com/Lingeng27/lx-music-1010-source/main/1010.js` |
| **HYW 公益版** | `https://raw.githubusercontent.com/shiliu6924/lx-music-source/main/HYW1.2.0.js` |
| 长青 ChangQing | `https://raw.githubusercontent.com/pdone/lx-music-source/main/changqing/latest.js` |
| 幻音 HuanYin | `https://raw.githubusercontent.com/pdone/lx-music-source/main/huanyin/latest.js` |
| JuheApi | `https://raw.githubusercontent.com/pdone/lx-music-source/main/juhe/latest.js` |
| 落雪云 lx-cloud | `https://raw.githubusercontent.com/moxi5445/lx-music-cloud/main/lx-cloud.js` |
| Huibq | `https://fastly.jsdelivr.net/gh/Huibq/keep-alive/render_api.js` |
| Flower 野花 | `https://raw.githubusercontent.com/pdone/lx-music-source/main/flower/latest.js` |
| 六音 SixYin | `https://raw.githubusercontent.com/pdone/lx-music-source/main/sixyin/latest.js` |

⚠️ 中文文件名需要 URL 编码，直接粘贴会 404。建议用 GitHub 页面点「Raw」取实际地址。

**内置建议**：默认只装**墨澜**（音质最好：kg 真 FLAC）+ **星海**（kg 也免费，双保险）。1010 的价值在它的 8 条链路 fallback，可作为第三个备选。

### B.2 星海源的具体地址

见 **§2.2.1**（下载页、最新脚本地址、版本错位、旧别名、MusicFree 版、版本检查与 IP 上报），此处不再重复。

### B.3 第三方可用性看板

`https://awaw.cc/post/lx-music-source`（pdone 仓库的官方说明页）自带一张检测表，2026-10-19 21:39 的结论与本次实测**基本一致**：

| 该页判定 | 源 | 本次实测 |
|---|---|---|
| 🟢 可用 | ChangQing / HuanYin / QDY / XingHai | 星海 ✅ 吻合；长青/幻音 ❌ **不吻合**（该页只看 L1/L2，没验 L3） |
| 🟡 异常 | Huibq / JuheApi | 吻合 |
| 🔴 失效 | Flower / Grass / ikun / LX | 吻合 |

⚠️ **这张表只能当参考，不能当判据**——它把「能返回 URL」当成可用，所以长青和幻音被误判成 🟢。这正是 §2.2 要求 L3 级验证的原因。

### B.4 使用约束（写进 UI 提示）

- 星海未授权用户仅 `kg` 可用，其余平台回 `403/503`，UI 要如实提示「该平台需作者授权」而不是「播放失败」
- Huibq 作者明示：最高 320k、不支持数字专辑、禁止批量下载、避免频繁切歌，否则封 IP（需切飞行模式换 IP）
- 所有源都依赖第三方服务，随时可能失效；本方案不承诺任何源长期可用
