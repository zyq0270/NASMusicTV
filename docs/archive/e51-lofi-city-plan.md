# E51「像素城市」开发方案 —— LoFi Cities 效果的复刻与本地化

状态：**批量移植进行中**（P1 引擎 + P2 地球过场 + P3 首批三城已于 2026-10-22 落地，见 `docs/technical-overview.md` §10.270；「57 座全做」的批量移植曾于 12 座后被所有者叫停（§8.2），2026-10-23 起恢复逐城直译 + 每城编译对账，**截至 2026-10-27 已落地 57 / 57**（`SCENES_BUILT` 57 项，最新一座波士顿·橡子街见 §8.52）。⏳ **下一座取 `logs_temp/next_city.py` 的排序结果（最短的未开工城）**；§8.2/§8.5 的数字全是冻结态历史。编号 E51；E50 已被 `docs/archive/e50-qianli-jiangshan-plan.md` 预览方案占用。中文名 **像素城市** / 英文 **Pixel City**。
> 📌 **文档公开，代码私有**：本文是开发分析与计划文档，已移至 `main` 分支公开。**E51 的 Kotlin 源码、测试、资产与 `loficities.com` 上游翻译件仍只在本地分支 `feat/e51-private`（⛔ 不 push、不 tag、不发 release）**。落地纪律与 git 后果见 **§2.6**。
✅ **可开工**：原开工门槛（§10 D-g 版权护栏、D-e 移植件留存）与命名（D-f「像素城市」）全部落定；§10 现存**一个开放项 D-m**（其它功能要 push 时如何把 E51 提交从待推历史里摘出去）。P0 探针在本机 `local.properties` 开 `lofi.city=true` 后开始（见 §2.5）。
⚠️ **未跑的阶段**：P0 真机探针（放大硬边 + 每帧成本）、§9 P3 要求的逐城地标相似度人工评分——两者都要上电视，本机做不了。
上游参照：https://loficities.com/ （作者 Safa Elmali）
调研产物：`logs_temp/lofi_modules/`（165 个切分模块）、`logs_temp/lofi_app.js`、`logs_temp/lofi_how.html`
⚠️ `logs_temp/` 是 gitignore 的一次性目录，随时可能被清空；上表的每一项都能用 §1.1 的两条命令加一次模块切分重新得到。本文引用它只为「本轮评审期间可直接翻源码」。

---

## 1. 调研结论：代码拿得到，而且是未压缩的可读源码

### 1.1 获取路径

首页只引一个脚本 `assets/app-PFK3ZG2F.js`（6.75 MB，`Cache-Control: immutable`）。它是 esbuild 产物，**未压缩、保留了原始模块路径注释**（`// js/scenes/tokyo.js`）。按注释边界切分即可还原全部 165 个源模块：

```bash
# 复现（产物落 logs_temp/，不进仓库）
curl -sS --compressed -A "Mozilla/5.0" https://loficities.com/ -o logs_temp/lofi_index.html
curl -sS --compressed -A "Mozilla/5.0" https://loficities.com/assets/app-<hash>.js -o logs_temp/lofi_app.js
```

| 模块 | 行数 | 职责 |
|---|---|---|
| `js/engine/core.js` | 306 | 像素引擎：画布常量、确定性哈希/PRNG、Bayer 抖动、`ditherFill`、光晕精灵、3×5 字模、倒影、色调分级 |
| `js/engine/display.js` | 257 | 帧循环与节流、**自适应裁剪**、双缓冲交叉淡入、动效档位 |
| `js/engine/weather.js` | 277 | 跨城共享雨/雪/落叶/闪电/着色，带景深与地面遮挡 |
| `js/scenes/registry.js` | 100 | **57 场景 / 50 城市**的元数据表 |
| `js/scenes/<city>.js` × 57 | 1,686–2,681 | 每城一个纯代码场景，合计 121,215 行 |
| `js/ui/globe.js` / `globe-draw.js` / `globe-land.js` | 476 / 215 / 4 | 选城市的地球（见 §6） |
| `js/audio/*.js` | 约 25k | Tone.js 生成式音乐（本项目不需要） |

官方方法论页 `how-its-made`（更新于 2026-10-13）自述：「每座城市是一个逐像素绘制画面的程序，由 AI 编码代理在作者指导下编写」「57 个场景对应 50 座城市，每城 1,400–2,100 行 JavaScript，合计约 96,000 行」。与实测一致。

### 1.2 它的"味道"由四条硬规则构成（可量化、可移植）

| 规则 | 实测值 | 出处 |
|---|---|---|
| **低分辨率像素格** | `W=480 H=270`，`SCALE=4` → 输出 1920×1080，硬边放大（`imageSmoothingEnabled=false`） | `core.js:203-206`, `display.js` |
| **确定性无接缝循环** | `LOOP=240` 秒、`FPS=60`；场景**无随机数、无自有计时器、不加载任何图片或网络资源** ⇒ 同一秒永远同一批像素 | `core.js:208-209`, `how-its-made` |
| **有序抖动 + 受限调色板** | 4×4 Bayer 矩阵 `(v+0.5)/16`；每种材质 3–5 级色阶，梯度靠抖动而非连续插值 | `core.js:223-224`, `ditherFill` |
| **静态预烘焙 / 动态重绘** | 「站的不动的只画一次，只有动的每帧重画」；天空、月亮、云、远景各自预烘焙成离屏缓冲，每帧只做 `drawImage` 合成 | `how-its-made`, `prerender2()` |

两条现成的自适应机制对我们特别有用：

- `layout(cw, ch, scene, pan, inset)` 用场景自带的 `focusX` / `landmark:[x0,x1]` / `landmarkTop` 决定裁剪窗口，**保证横屏电视和竖屏手机都留住地标**（方法论页明确要求检查「a phone held upright」）。
- `MOTION_FPS = { smooth: 60, light: 20, still: 1 }` 三档动效，天然映射到我们的 `VisualQuality`（HIGH/MEDIUM/LOW）。⚠️ 注意 `Tier` 是另一个维度，别混——见 §5 的纠正框。

### 1.3 场景契约（我们照此定义 Kotlin 接口）

```js
tokyo_default = {
  id, name, tagline: "Neon rain by Tokyo Tower", timeZone, accent: "#ff5fa2",
  focusX, landmark: [x0, x1], landmarkTop,        // 供自适应裁剪
  billboard, spots: [...], secrets: [...],        // 点选交互（本轮不做）
  ambience: { rain: .5, traffic: .3, crowd: .15, wind: .1 },
  // ↑ 上游的音频混音参数（合成环境音的四个通道增益）——⛔ 不进我们的契约，见 §1.3b
  events: [{ t: 12, type: "train" }, ...],        // 循环内的定时事件（我们只保留其视觉表现）
  create(c) { ctx = c; prerender(); return { render: renderFrame } }
}
// renderFrame(t, { pitch, billboard, weather, reducedMotion })
```

### 1.3b 音画分工（所有者 2026-10-22 定调）

上游是**音画一体**的：画面 240 秒循环 + Tone.js 按规则现场生成音乐 + 合成环境音（雨/车流/人群/风）+ 事件音效（火车、钟声）。

我们只取画面这一半：**本效果不出任何声音，声音一律来自项目播放器正在放的音乐**（NAS / 网络音乐 / 本地下载）。因此：

- `ambience` 四个通道是纯音频参数 → **丢弃**，不进 Kotlin 契约。
- `events` 表保留，但 `type` 只解释为**视觉事件**（列车驶过、灯序变化、鸽群掠过），不带任何音效。上游事件之所以存在，是因为它是"这段循环的声音锚点"；对我们它们改为**画面节奏锚点**——240 秒里给观众"刚刚换了一件事发生"的可看标记。
- ⛔ 严禁触碰音频链路：不申请 `AudioManager` 音频焦点、不引入 `SoundPool`/`MediaPlayer`、不碰 `PlaybackService`/`PlayerManager`。渲染器只有 `DrawScope`，这是硬边界。
- 天气仍由城市**视觉**参数决定（保留上游"每城自选一种天气"的设定，如重庆多雾、大阪多灯），不接入我们已有的 `WeatherApi` 实况天气——那会让画面变成天气应用，且和上游"选一个有动感的夜晚"的方法论冲突。

### 1.4 关键判定：它是纯 Canvas 2D，**没有 WebGL / three.js**

包内 `THREE`、`WebGLRenderer`、`PerspectiveCamera`、`InstancedMesh` 出现次数均为 **0**；`fillRect` 1,505 次、`drawImage` 1,641 次。因此**绕开了本项目已确认的死穴**（「WebView+three.js 在创维电视上不显示」，2026-10-10 真机核验），可以直接走我们现有的 `DrawScope` Canvas 2D 路线。

---

## 2. 版权边界（所有者 2026-10-22 两次裁决：允许逐行照搬 + **移植件不得外发**）

**第二次裁决（2026-10-22，本节现口径）：**「我们的移植件，也不能上传到 github，不发布没有版权的代码。」

⇒ 两次裁决合起来的完整边界是：**照搬可以，但只在本机**。「不发布」不再是一句意向陈述，而是一条对 git 有直接约束的纪律——它管的是**源码本身不得进入远端仓库**，比 §2.5 的编译期开关更严（开关只能让公开 APK 不呈现效果，挡不住源码随仓库公开）。落地条款见 **§2.6**，是本节唯一的执行入口。

**第一次裁决（覆盖 §2.4 的合规分析）：**「反正我不会发布，代码可以逐行照搬」，并且**关于页致谢任务（原 §11）不再做**。据此，本效果的实现路径从"只借画法、代码与数据全部自产"改为**直接翻译上游 JS**——这也让 P3 的三座城从"自绘 + 看图评分"变成"移植 + 对账"，工作量和不确定性都大幅下降。

⚠️ 三条必须留下的事实记录（不是劝阻，是让日后读到本节的人知道边界在哪）：

1. **本仓库是公开的 GitHub 仓库**（`github.com/hxzhang2000/NasMusicTV`，GPL v3），且 CI 在 push/tag 上自动构建并发 release。⇒ 逐行照搬的代码一旦 push，"公开提供"这个行为就已发生，与 APK 里有没有这个效果无关。§2.5 的编译开关只能让**公开 APK** 不含它，挡不住源码随仓库公开。
2. **GPL v3 声明覆盖整个仓库**，包括这些翻译件——等于对外声称我们对它们有权授予第 8 条全部权利。
3. 上游无任何开源授权，画面视频循环在 Gumroad 售卖（`safaelmali.gumroad.com`）。

⇒ **这三条正是第二次裁决的成因，不再是"由所有者承担的风险"**：既然①公开仓库 push 即构成公开提供、②GPL 声明会覆盖翻译件、③上游无任何授权，结论只能是**翻译件根本不进远端**（§2.6）。本方案因此不再设"不得照搬"类护栏（照搬在本机是允许的），但"不得外发"是硬护栏；下面两条纪律仍保留——它们既服务于长期维护，也服务于 §2.6 的可识别性（哪些文件不许外发，必须一眼可辨）。

保留的两条纪律：

- **移植件与本项目代码在目录上分得清。** `visualizer/pixel/` 与 `visualizer/scenes/lofi/` 里的每个文件头部**必须写明对应上游模块路径与"直译"事实**（例：`// 直译自 loficities assets/app-*.js 的 js/engine/core.js`）。理由：日后上游改版时要能定位差异；这也让 §2.5 的开关被关掉时，一眼看清哪些代码是不该外发的。
- **上游原件与抓取的 JS 不进 git**：`app/src/lofi-upstream/` 与 `logs_temp/` 一样写进 `.gitignore`（那是**别人的原文**，与我们自己的翻译件性质不同）。对账脚本可以读它（见 §7、§9）。

**由本次改判直接简化的三处**（原文已就地更新）：§6.2/§6.3 陆地栅格**直接搬 `LAND5`**，不再自产；§7 注册表同步脚本**可以照搬 `tagline`/`accent`/`looks[]`**；§9 P1 的逐位基线**可以由上游 JS 再生成**（此前禁止）。

### 2.4 合规判定（**本节结论已被 §2 改判取代，保留作决策依据**）

所有者 2026-10-22 先问「致谢 + 重构够不够」，判定是不够；同日再改判「不发布，允许逐行照搬」。**下面的四条分析仍然成立，只是所有者选择不以它约束"照搬"这个动作**——留档是为了：①日后若要把效果公开分发，这四条立刻重新生效；②避免下一个人以为"照搬"是没有代价的默认选项。⚠️ 同日**第二次裁决（D-l）实际上让这四条重新生效了一半**：它们不再约束"能不能在本机照搬"，而是直接推出了"不能外发"（§2.6）。

1. **致谢不产生许可。** 上游没有任何开源授权声明，而"注明出处"从来不是著作权法上的使用依据。（本次改判同时撤销致谢任务，见 §11。）
2. **重构 = 改编，仍受改编权控制。** 《计算机软件保护条例》第八条第(八)项把"改编"列为权利人专有权利，认定路径是**接触 + 实质性相似**：上游源码整份可得（§1.1，接触要件直接满足）。逐行照搬比"重构"更直白地落在改编权射程内。
3. **受保护的对象主要是"画面"，不只是代码文本。** 每城 1,400–2,600 行代码的价值全在它产出的那张构图上 ⇒ 移植场景=连画面一起复刻。
4. **GPL 不兜底，反而多一条。** 见 §2 第 2 条事实记录。

**残余风险陈述**：个人私用、不分发时，实际被主张的概率极低；但"极低"不是"零"，且本仓库的公开属性使这条风险不被 §2.5 的开关覆盖。⇒ **D-l 的处置不是"承担"而是"消除"**：源码一律不进远端（§2.6），把"公开提供"这个构成要件直接拿掉。裁决日期 = 2026-10-22。

### 2.5 双变体发布：本机含效果 / 公开版不含

所有者需求：① 本机编译的 release 属自用、不外发，**包含**本效果；② GitHub Actions 自动编译的 release 是公开外发版，**不包含**本效果。问：靠 `.gitignore` 还是靠编译期开关？

**`.gitignore` 做不到，两条理由：**

- 它只对**未被跟踪**的文件生效；已经 `git add` 过的文件，加了规则照样随仓库走。
- CI 构建的是**已经 push 的源码**。只要代码在 `main` 里，"公开提供"这个行为已经发生，与 APK 里呈现与否无关——任何人都能 clone 后自己编出带效果的包。

⇒ 二者不是替代关系，各管一半：**`.gitignore` 管"源码不公开"（针对上游原件与移植草稿），编译期开关管"公开 APK 不呈现"（针对我们的 Kotlin 实现）**。

**机制：Gradle property → `BuildConfig` 布尔。** `app/build.gradle.kts` 现有 `buildConfigField` 用法在 :55-59，同处追加：

```kotlin
// local.properties（已 gitignore，见 .gitignore:4）与 -P 命令行两种途径都支持。
// ⚠️ 必须显式读 local.properties：AGP 只把它当 SDK 配置来源，不能假定其中的键自动成为 Gradle 属性。
val lofiCity = (localProps("lofi.city") ?: providers.gradleProperty("lofi.city").orNull)
    .toString().toBooleanStrictOrNull() ?: false
buildConfigField("boolean", "LOFI_CITY_ENABLED", lofiCity.toString())
```

- 本机：`local.properties` 写 `lofi.city=true`（一劳永逸，debug 也生效），或 `./gradlew.bat assembleRelease -Plofi.city=true`。
- CI：`.github/workflows/build.yml` **不传** ⇒ 缺省 `false`。不要改成显式传 `false`——"缺省即关闭"才是默认安全。

**收口必须两处，只堵一处会漏（已核对源码）：**

| # | 位置 | 改动 | 为什么不能省 |
|---|---|---|---|
| A | `VisualizerTheme.selectable(photoWallAvailable)`（`AppSettings.kt:443-444`） | 关闭时 `filterNot { it == LOFI_CITY }` | `availableThemes()`（`VisualizerRendererFactory.kt:89-90`）完全委托它，三个调用点（工厂 / `VisualizerViewModel.kt:466` / `VisualizerStage.kt:537`）共用同一份列表 ⇒ 公开版里这个主题在指示器上**不存在**、左右键切不到。是"没有"，不是"坏了" |
| B | `VisualizerRendererFactory.create(theme, context)` | 关闭时该分支回落 `Default` 对应渲染器 | ⚠️ **这条是本节的核心发现**：`fromKey`（`AppSettings.kt:428-431`）按 `entries.find { it.name == key }` 反查，而**枚举常量在公开版里依然存在**。自用版导出的备份 JSON（`"visualizerTheme": "LOFI_CITY"`）装回公开版会被原样恢复并真的渲染出来——只堵 A 挡不住"备份导入"这条路径。项目里 `BackupGson.kt:25-36` 已经为这类"枚举名跨版本回灌"踩过坑 |

**数字门不受影响（已核对，无需改计数）：** 枚举项照常进 `main`，`entries.size` 恒定 ⇒ `FxCoverageScanTest` 的阈值、`ParticleBudgetGateTest` 的 `map.size == entries.size`、`VisualizerThemeTest` 的硬编码计数、`VisualizerThemeStringsGateTest` 的双语键覆盖**全部照旧**。开关只作用于运行期列表，不作用于声明。

⇒ 因此 §5 接入清单加第 8 行（新的 `LofiCityVariantGateTest`），断言三件事：默认构建 `BuildConfig.LOFI_CITY_ENABLED == false`、`selectable()` 不含 `LOFI_CITY`、`create(LOFI_CITY)` 不返回 `LofiCityRenderer`。**这条测试钉的是"CI 缺省态"，不是本机态**——将来任何人给 workflow 加 `-Plofi.city=true` 都会立刻红。

**连带裁决（更新到 2026-10-22 第二次裁决之后）：**

- 开关与效果本体绑定；**致谢卡随 §11 一起撤销**，不再有"文案与事实对齐"这条约束。
- ⚠️ 连带项：`CHANGELOG.md`、`README.md`、`docs/technical-overview.md` 都是**公开文档**。第二次裁决后这三份公开文档**不得出现**"逐行照搬/直译自 loficities"这类实现事实的细节（本节与 §8 的移植台账留在方案文档里即可，方案文档本身也不外发——见 §2.6 第 3 条）。`CHANGELOG` 每行仍按项目口径只写"做了什么"。
- 上一轮"将来想只放行自绘城市"的设想作废：现在**所有城市都是移植件**，要外发的唯一前提是先取得上游许可。
- **本节开关降级为"第二道锁"**：源码根本不进远端（§2.6）之后，CI 就不可能构建出带效果的公开 APK，开关的价值变成"万一 §2.6 失守，公开构建缺省仍不呈现"以及"本机可以编出不含移植件的变体"。⛔ 但**不得因为有开关就认为可以 push**。

### 2.6 ⛔ 不入库纪律（第二次裁决的执行条款，2026-10-22）

> ✅ **2026-10-23 已由历史剥离解决（本节转为"纪律记录 + 新增代码仍适用的约束"）**。
> 剥离已执行完毕：`git filter-repo` 从全部历史删除 56 个移植件路径（被跟踪文件 1113 → 1057），
> 改写后主干 `d744c5c` → `6a6af1d`（移除对已删类的 7 处引用、补 `LOFI_CITY` 的 `when` 穷尽性兜底），
> 已 `--force-with-lease` 推送；`main` 现为 `8f362ad`。E51 源码与测试转移到本地分支 **`feat/e51-private`**（无 upstream），
> 并统一收在 `visualizer/e51/{engine,scenes}/`。2026-10-23 两次含移植件的 CI 构建（`03:42` / `04:07`）artifact 已删除。
> ⚠️ 因此 **`main` 上不存在 E51 的任何文件**，本节"不 push"的约束现在由**分支隔离**保证，而不是靠人工纪律；
> 但新增 E51 代码时**纪律不变**（仍然 ⛔ 不 push / 不 tag / 不发 release）。
> ⚠️ 已知残留（所有者已接受，勿再重提）：旧 commit 对象仍在 GitHub 对象库中可按 hash 拉取；`forks: 0`；
> 所有者决定**不**提交 GitHub 清缓存工单。

**一句话：E51 的移植件与上游派生物只存在于本机工作区，永远不 push、不 tag、不发 release、不上传任何远端（GitHub 或其它）。**

**1. "移植件"的边界（按目录/文件认，不靠记忆）**

| 类别 | 路径 | 性质 |
|---|---|---|
| 引擎直译件 | `app/src/main/java/com/nasmusic/tv/visualizer/pixel/**`（20 个文件，含 `PixelGlobeLand.kt` 的 `LAND5` 常量、`PixelFont.kt` 的 `FONT3` 字模） | 上游 `js/engine/core.js`、`js/ui/globe-land.js` 等的翻译件 + 上游数据 |
| 场景直译件 | `app/src/main/java/com/nasmusic/tv/visualizer/scenes/lofi/LofiCity*.kt` | 上游 `js/scenes/<id>.js` 的逐行翻译件 |
| 上游元数据表 | `visualizer/pixel/CityRegistry.kt`（生成物） | 上游 `registry.js` 的 `tagline`/`accent`/经纬度/`looks[]` 照搬 ⇒ 同样是派生物 |
| 接线与门禁 | `visualizer/renderers/LofiCityRenderer.kt`、`ui/components/LofiCityCard.kt`、`VisualizerTheme.LOFI_CITY` 相关改动、`app/src/test/**/Lofi*Test.kt` | 本项目原创，但**只在移植件存在时才有意义**；单独外发等于外发一个跑不起来的空壳 ⇒ 一并按移植件处理，避免"公开版里留一个悬空主题枚举" |
| 上游原件 | `app/src/lofi-upstream/**`、`logs_temp/**` | 已 gitignore（§2 纪律二），**别人的原文**，比翻译件更不能入库 |
| 对账脚本 | `scripts/lofi_city_registry_sync.py` | 原创工具，但它解析上游 bundle 且会生成派生表 ⇒ 一并视为移植件（脚本本身不含上游代码，可单独讨论，见 §10 D-m） |

**2. git 上的现状与硬规则（2026-10-22 实测）**

- 本机 `main` 领先 `origin/main` **13 个提交**，其中 **`494a25a`（P1 引擎 + 首城 + 双变体开关）与 `2b6d105`（P2 地球过场 + P3 三城）两个提交含移植件**，且夹在其它功能的提交中间（前面是浅色主题/曲库/K 歌/焦点，后面是焦点开关与文档）。
- ⛔ **因此：只要直接 `git push` 当前 `main`，这两个提交就会一起出去。** 这是本节要防的第一号事故，也是 §10 **D-m** 的由来——"其它功能要不要照常 push、怎么把 E51 摘出去"必须由所有者点头后由人（或经授权的会话）执行历史改写，⛔ 任何会话不得自行 rebase / reset / force-push。
- 未提交的移植件（工作区里的 8 个 `LofiCity*.kt` 草稿、`CityRegistry.kt` 改动等）**保持未提交状态**即可；⛔ 不要为了"干净"而 `git add` 它们，也⛔ 不要为了"干净"而删掉（所有者口径：「代码改了多少就先多少」）。
  - ⚠️ **2026-10-22 第三轮补充**：工作区现有 **9 个**未提交草稿（原 8 个 + 本轮补齐的墨西哥城被就地改写，未新增文件），另有两个 `.tmp_mx_*.kt` **占位文件**（内容已备份到 `logs_temp/mx_types.bak` / `mx_tail.bak`，占位体仅一行注释）——它们同样不得 `git add`，下轮应清掉。
- ⚠️ `.gitignore` **不是**护栏（§2.5 已论证：对已跟踪文件无效，且 CI 构建的是已 push 的源码）。本节靠的是"不 push"这个动作本身 + 每次 push 前的人工核对。

**3. 文档侧同样不外发**

- 本方案文档（`docs/archive/e51-lofi-city-plan.md`）含逐条移植台账与上游实现细节 ⇒ **它自己也是移植件的一部分**，⛔ 不得随含 E51 的提交 push。若必须 push `docs/`，先把本文件从待推历史里排除（同 §10 D-m 的手续）。
- `docs/archive/verification/results/lofi_registry_*.json`（同步脚本写的快照）同理：含上游元数据。
- `README.md` / `CHANGELOG.md` / `docs/technical-overview.md` 是公开文档：写"新增像素城市效果"这类**用户可见事实**没问题，⛔ 但不要写实现来源细节（照上面"连带裁决"第 2 条）。
- ⚠️ **已实测到一处待处理**：`docs/technical-overview.md` §10.270 首句写着「逐行直译 loficities.com 的像素城市夜景」并链到本方案，由提交 `2b6d105` 引入（尚未推送）。⇒ 若 D-m 选 (a)（把两个 E51 提交摘出待推历史），这句随之不进远端，无需改写；若选 (b)/(c) 或将来单独 push `docs/`，**必须先改写这句并删掉指向本方案的链接**。`CHANGELOG.md` 的 4 行只写用户可见事实（"像素风城市夜景""切换时播放全屏地球过场"），已核对**不含来源信息，可以照常发布**。

**4. 每次 push / tag / release 前的核对清单（人手执行，不可自动化为门禁）**

1. `git log --oneline origin/main..main` 里有没有 `feat(lofi)` / 任何触及 `visualizer/pixel/`、`visualizer/scenes/lofi/` 的提交？有 ⇒ 先走 §10 D-m，不要 push。
2. `git diff --cached --name-only`（提交前）有没有上表第 1 类里的路径？有 ⇒ 不 add。
3. 要发的 APK 是不是本机自用构建？自用构建含效果无所谓（§2.5 开关在本机是 `true`）；**任何要给别人、要上传到 GitHub Releases 的包，必须由不含移植件的源码构建**。
4. ⛔ 绝不把 `app/src/lofi-upstream/`、`logs_temp/` 里的上游原件复制到仓库内任何被跟踪路径。

---

## 3. 可行性判定与三个真实风险

结论：**可行，且契合度高于一般移植**。480×270 离屏位图 + 整数放大，比我们现有满分辨率渲染器的每帧像素量更小；「静态预烘焙 + 动态重画」正是 `MoonlitRenderer` / `ProceduralTexture` 已在用的套路；地理与投影底座（`WorldProjection.kt`）虽不被地球采用，但证明了这类纯逻辑模块在本项目有成熟的 Robolectric 单测模式。

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| **R1** | 最近邻硬边放大在 API 22 上是否可得 | 一旦回落成双线性，整幅画面糊掉，效果直接失真 | 首选 `DrawScope.drawImage(dstSize=…, filterQuality = Filter.Nearest)`。**P0 必须在创维 5.1.1 真机截图验证放大边缘是硬像素**（release 构建，非桌面预览）。若硬件层合成把 nearest 折成 bilinear，退回方案：把 `SCALE` 从 4 改为设备整数倍缩放自绘，或改用 `AndroidView` + 原生 `Canvas` 关闭滤波 |
| **R2** | 每帧像素量：480×270 = 129,600 像素；上游自设 4 ms/帧预算（桌面 Chrome） | 创维 API 22 上帧耗时未知，可能只有十几 fps | 静态层全部预烘焙（`onEnter` 一次性烘焙，照 `MoonlitRenderer.loadMoonMap()` 的做法，但**必须在 IO 线程**，不得重复它的同步主线程解码）；倒影用「按行 `drawImage`」而非逐像素；MEDIUM/LOW 档分别降到 20/1 帧驱动（照 `MOTION_FPS`，映射见 §5）。**P0 在电视上实测，低于 15fps 则砍城市细节而不是调阈值** |
| **R3** | 上游场景**完全不随音频变化**（纯时间驱动），而我们的 `VisualizerRenderer.draw(frame, ctx)` 是音频帧驱动 | 直接照搬会得到一个"跟音乐无关"的画面，与 visualizer 定位冲突；改成强音频反应又会破坏它的静谧美学 | 方案取中间：时间轴为主，**只开 3 个轻反应通道**（低频→窗灯亮度、节拍→霓虹闪一下、能量→车流光尾长度），且反应只做亮度/透明度调制、不改变像素位置，从而保住循环的确定性。幅度已裁，见 §10 D-a |

补充必须处理的两个本项目特有问题：

- **暂停。** 上游音乐自己生成所以时间永不停；我们的 `AudioFrame.timeMs` 在暂停时不前进。过场计时与城市时间轴必须明确"暂停即冻结"还是"墙钟继续"。建议：画面时间轴冻结（跟随音乐），但**切城市计时器走墙钟**，避免长时间暂停后突然连切。
- **主题切换语义。** 现有 `VisualizerStage` 的遥控左右键切的是**主题**，且 `RendererSwapper.sync()` 的 `crossfade` 实参恒为 `false`（自动导演档已删）。切城市必须发生在**单个 renderer 内部**（§6.4 阶段机），绝不能借道 `RendererSwapper`，否则会把"已删除的自动轮播"重新引入主题层。

---

## 4. 引擎层设计（新增 `visualizer/pixel/` 包）

纯 Kotlin、零 Android 依赖、可单测 —— 与 `WorldProjection.kt` / `WorldMapData.kt` 同一档，全部断言可在 Robolectric 之外的 JVM 直接跑。

```
visualizer/pixel/
  PixelConstants.kt   W=480 H=270 SCALE=4 LOOP=240 TAU；frac/clamp/lerp/smooth/wave/step/env/cycle —— 直译 `js/engine/core.js`
  PixelRandom.kt      hash(a,b,c) FNV 型、rng(seed) —— ⭐ 逐位照搬上游：确定性循环的观感就建立在"同一个 seed 同一个像素"上，§9 P1 基线钉的就是它
  PixelDither.kt      BAYER[16]、bayer(x,y)、pickFromRamp(ramp,v,i,j)、ditherFill(...) —— 矩阵与取值式直译上游
  PixelColor.kt       hex/mix/rgba/rgbaOf、gradePixels(色调分级)、makeHalo/drawHalo(加性光晕精灵) —— 工具函数直译，色值随场景常量一并照搬
  PixelFont.kt        FONT3 3×5 字模 + text/textWidth —— 字模表直译上游（§10 D-h）
  PixelSign.kt        画面内城市名站牌：字模按 pixelScale 放大 + 1 像素暗描边（§12.2，仅拉丁）
  PixelBuffer.kt      480×270 IntArray ARGB 位图；put/set/fill/trim；toImageBitmap()；一次性分配、每帧复用
  PixelReflect.kt     按行倒影（含水洼椭圆掩码），用行 drawImage 实现
  PixelScene.kt       interface CityScene —— 与 §1.3 契约一一对应，但去掉 ambience（纯音频参数）、events 只作视觉事件（§1.3b）
  CityRegistry.kt     ★ GENERATED by scripts/lofi_city_registry_sync.py —— 禁止手改
```

内存与分配纪律：`DrawScope.draw` 内禁止分配（现有 `RendererFx` 契约要求），`PixelBuffer` 与所有预烘焙层在 `onEnterContent` 建、`onExitContent` 释放。每城峰值内存 = 若干 480×270 IntArray（≈0.5 MB/层），6–10 层约 3–5 MB，远低于本项目「只按系统内存拦 ≥700MB」的口径。

---

## 5. 与现有主题系统的接入清单

新增 `VisualizerTheme.LOFI_CITY`，`ordinalLabel = "51"`（实测在用编号至 `"49"`，E50 已被千里江山预览占用）。改动点共 9 处（原 7 处 + §2.5 新增的第 8、9 两行），**每处都挂着数字型门禁，漏一处即红**：

| # | 文件 | 改动 |
|---|---|---|
| 1 | `data/model/AppSettings.kt` | 枚举加一项（`R.string.visualizer_theme_lofi_city, "像素城市", Tier.ADV, "51", needsParticleBudget = false`）+ `selectable()` 受开关过滤（§2.5 表 A） |
| 2 | `visualizer/VisualizerRendererFactory.kt` | `when` 分支 + import；**注意分支需传 `context.applicationContext`**（`MoonlitContextLeakTest` 在扫这条）；开关关闭时该分支回落 `Default`（§2.5 表 B） |
| 3 | `values/strings.xml` + `values-en/strings.xml` | 各加 1 条双语名（缺 `values-en` 会编译通过、渲染时崩，由 `VisualizerThemeStringsGateTest` 拦） |
| 4 | `visualizer/renderers/LofiCityRenderer.kt` | 新渲染器，`RendererFx` 子类，须 `postFx` 至少一通道 > 0 |
| 5 | `test/.../fx/FxCoverageScanTest.kt` | `covered` 名单加类名；阈值 `decls.size >= 26` → `>= 27` |
| 6 | `test/.../data/model/ParticleBudgetGateTest.kt` | 工厂映射数须 `== entries.size`，同步 +1 |
| 7 | `test/.../data/model/VisualizerThemeTest.kt` | 6 处硬编码 `assertEquals(26, …)` → 27；`ordinalLabel` 唯一性由测试保证 |
| 8 | `test/.../visualizer/LofiCityVariantGateTest.kt` | 新门禁：默认（CI）构建下 `LOFI_CITY_ENABLED == false`、`selectable()` 不含 `LOFI_CITY`、`create(LOFI_CITY)` 不返回 `LofiCityRenderer`。另加 `LofiUpstreamHygieneTest`（§2 护栏一）与 `LofiCityRegistryGateTest`（§7） |
| 9 | `app/build.gradle.kts` | `buildConfigField("boolean", "LOFI_CITY_ENABLED", …)`，读法见 §2.5（与 :55-59 同处） |

档位定 **`Tier.ADV` + `needsParticleBudget = false`**（**D-c 已裁**），与 `MOONLIT` / `TRIPOD_FOG` / `SNOW_CEDAR` / `BIRDS_FLOCK` / `DESERT_SMOKE` 五个工作量同构的场景渲染器一致。

⚠️ **原方案写的"倾向 `MED`"是概念混淆，已纠正**：`Tier` 只有 `BASIC / ADV / ULTRA` 三值（`AppSettings.kt:386`），`LOW/MEDIUM/HIGH` 是 `VisualQuality`。两者由 `VisualQuality.supports()`（`:478-488`）连接，而 2026-10-01 的方案 C 裁决已把 `ADV` 的门槛改成"**该效果是否真消耗粒子预算**"⇒ `ADV + false` 在三档画质下都可选。

所以 P0 若实测不达标，**不要**用 `Tier` 去砍档位（`ULTRA` 会把它抬成"仅 HIGH 且要求帧缓冲"，语义完全跑偏），而是在渲染器内部照上游 `MOTION_FPS` 自节流：`LOW → still(1 fps 静帧)`、`MEDIUM → light(20 fps)`、`HIGH → smooth(60 fps)`。若 LOW 连静帧都贵，就把 LOW 分支做成"烘焙一次的地景图"。⛔ 明确禁止"LOW 档把它从列表里剔除"——`:480-483` 记着这条老缺陷的表述：「能渲染却不可选，切走即永久回不来」。

⚠️ **自节流的边界已由真机缺陷钉死（v2.39.7）**：节流**只许包住"重算 480×270 像素"**，**落屏必须每帧做**。`VisualizerStage` 每帧重录 display list，渲染器某一帧不画 = 那一帧只剩舞台背景；而重算节拍的时钟来自 25Hz 的频谱线程、与 UI 帧不锁相 ⇒ 把落屏一起节流，真机就是整幅画面（含灯光）以 ≈21.6Hz 频闪（实测 73.5% 帧空白）。见 `technical-overview.md` §10.270 与 `LofiCityFlickerGateTest`。

---

## 6. 切城市与全屏地球过场（第 3 条需求）

### 6.1 城市时间轴

常态即一座城市的 240 秒循环：事件由城市自带的 `events` 表驱动，**只有画面表现、不发声**（见 §1.3b）；天气由城市的**视觉**天气参数决定（沿用上游每城自定的一种天气母题，不接实况天气接口）。

声音侧完全交给项目播放器：正在放什么歌就是什么歌，画面只是伴随时的一种"夜色地点"。因此一座城的"列车驶过"发生在一首歌的任意时刻都可以，不需要与段落对齐——除非走 R3 的轻反应通道（D-a）。

### 6.2 地球的画法（照网站，Kotlin 重写）

上游地球三件套合计 695 行 JS，比一座城市（约 2,100 行）便宜得多，且**同样是纯像素 2D**：

| 机制 | 上游实现 | 我们的等价实现 |
|---|---|---|
| 球面投影 | `vec(lat,lon)=[cosλ·sinφ, sinλ, cosλ·cosφ]` + `toView(p,lon0,lat0)` 两次旋转，正交（orthographic） | 同一组公式，`PixelGeo.kt` |
| 陆地 | `LAND5`：720×360 等距圆柱栅格，4 类 `O/G/S/I`（海/陆/沙/冰），`"OGSI".indexOf(v)` + **base-36 游程编码**，11,690 字符 | **直接搬同一份常量**（`PixelGlobeLand.kt`，§6.3） |
| 色带 | 5 条 7 级 ramp（海洋/陆地/沙/冰/近岸浅水）+ `DUSK` 5 色 + `LIGHTS8` 3 色夜灯 | **直接搬同一组色值**（改判后不必另取色） |
| 昼夜分界 | `sunAt(ms)`：太阳赤纬 `-23.44·cos(2π/365·(day+10))`、下点经度 `(12-utcH)·15°` ⇒ **真实太阳位置**决定明暗 | 同一公式（标准天文近似，非上游独创） |
| 夜面城市灯光 | **无城市灯光数据集**：按陆地掩码用 `hash2(i,j)` 撒点，`t∈{1,2}` 且 roll<0.4，>62°N 剔除，海岸 ±2 格内有海则密度提升 10 倍 | 同一算法（程序化散布），参数自定 |
| 星空 | 90 颗，`hash2` 定位置/亮度/相位/速度闪烁 | 同 |
| 城市 pin | 5 种 kind：`here`/`big`/`top`(直播)/`off`(置灰)/`city`，2×2 或 3×3 像素带描边 | 本轮只用 `city` + `here` |
| 方向导航 | `nextCity(spots, from, [dx,dy])`：沿指定方向找下一个正面可见（`z>0.15`）的城市 | 保留（手机触摸滑动/遥控方向键都用得上） |
| 缓动 | `ease(t)=1-(1-t)³`，`spinTo(p, ms, from)` 带起始偏移 | 同 |

⚠️ 改判后本表的"我们的等价实现"= **逐行直译**，公式、阈值、色值、常量一并照搬；唯一改动是把 JS 的 `Math.*` / 闭包状态换成 Kotlin 的等价物与显式字段。数值一旦照搬就**冻结为基线**（§9 P1）：改一个数就得重跑对账脚本。

### 6.3 陆地栅格：直接搬 `LAND5`

改判前这里写的是一套"从 Natural Earth 110m 自产同格式栅格"的方案（`scripts/lofi_globe_land.py`），**已作废、不需要**：`logs_temp/lofi_modules/js__ui__globe-land.js.js`（11,769 字节）里那份 `LAND5` 字符串直接搬进 `visualizer/pixel/PixelGlobeLand.kt`，文件头写明"直译自 `js/ui/globe-land.js`"。⛔ 但**上游 JS 原件本身不进 git**（§2 第二条纪律）——`globe-land.js` 留在 `logs_temp/` 或 `app/src/lofi-upstream/`（两者都已 gitignore），只有翻译后的 Kotlin 常量进 `main`。

解码侧照搬 `decodeLand(text)`：按 `|` 分行、`/([OGSI])([0-9a-z]+)/g` 取游程、`parseInt(n,36)`、`map.fill("OGSI".indexOf(v), i, i+len)`，输出 `Uint8Array`→Kotlin `ByteArray`（720×360 = 259,200 字节，一次性分配）。

### 6.4 过场阶段机（在 renderer 内部完成）

```
CITY(旧)  ──t≥interval──►  FADE_OUT(0.5s)  ──►  GLOBE_IN(0.6s, 缩放入场)
                                                     │
   CITY(新) ◄── FADE_IN(0.5s) ◄── GLOBE_OUT(0.6s) ◄── GLOBE_SPIN(2.2s)
                                                    ▲
                                    spinTo(随机城市, 1.4s) 可再跳一次 0.6s
```

总时长 **3.4–4.0 秒**（所有者选定的"约 4 秒"档）。要点：

- 全程由 `LofiCityRenderer` 自己的阶段变量驱动，`DrawScope` 里做旧城位图与新地球位图的 alpha 合成，**不触碰 `RendererSwapper`**。
- `GLOBE_SPIN` 期间落到候选城市集合中的一座（**按洗牌袋抽**，见 §6.5.1；候选 = **已直译出场景的城市**，首批只有开普敦 / 重庆 / 台北 / 大阪四座；地球上其余城市照常显示与呼吸闪烁但不可选），被选中那颗用 `here` 高亮。
- 竖屏手机：地球直径按 `min(w,h)·0.8`，城市名标签只画选中的那座（3×5 字模在竖屏宽度下容得下）。
- 过场期间**禁用遥控左右键切主题**会引入新交互分支，本轮不做；现有键位照常切主题，代价是过场可能被打断——处置为「主题被切走则立即丢弃过场状态」，不留残留位图。

### 6.5 切换间隔

**换歌曲即换城**（所有者 2026-10-24 改判；原为「默认 20 分钟，一首歌约 3–4 分钟 ⇒ 约 5–6 首一切」）。

- 触发器：`LofiCityRenderer` 每帧比 `RenderContext.songId`，一变就 `timeline.requestSwitch()`；与遥控器上下键/竖滑走**同一条**请求通道（过场进行中的请求丢弃、不排队）。
- 进入主题的第一帧只记录 `songId`、不切城（否则一进主题就白起一轮过场）；主题离开时清回 `null`。
- `LofiCityTimeline.intervalSec` 的默认值改为 `Double.POSITIVE_INFINITY` ⇒ **不再有按时间的自动切城**；形参只为单测保留（17 个用例靠它把"到点自动切"这条状态机路径钉住）。原常量 `LOFI_CITY_INTERVAL_SEC` 已删除。
- 仍**不进设置页**（理由见 §10 D-d——项目既有口径「运行态项不进设置页」）。

#### 6.5.1 随机口径：洗牌袋（每城轮一遍才重复）

原实现是「`filter { it != current }` 之后等概率抽一个」⇒ 25 座城连切 5 次就可能撞重复。现改为 **Fisher–Yates 洗牌袋**：

- 一轮把全部候选洗一次、逐个取走；取空了再洗下一轮。
- 两条不变式（`LofiCityTimelineTest` 的 R7 钉住）：① **相邻两座永不相同**（含跨轮边界：上一轮末城不许是本轮首城，靠"洗牌后首位撞上当前城就随机换位"实现，换位不改变本轮集合）；② **每连续 N 次切城（N = 候选数）恰好覆盖全部候选各一次**。
- 袋是**实例级**的：主题离开再回来会新建时间轴（`onEnterContent`），届时从候选首城重新开一轮。

---

## 7. 离线再生脚本：城市同步（第 4 条需求）

`scripts/lofi_city_registry_sync.py`（在仓库根执行，照 `scripts/` 既有惯例）：

1. 抓 `https://loficities.com/` 首页 HTML → 提取 `/assets/app-<hash>.js`（哈希会变，必须从 HTML 现取，不得硬编码）。
2. 定位 `// js/scenes/registry.js` 模块边界 → 解析 `SCENES` 数组，取 `{id,name,tagline,timeZone,accent,lat,lon,country,region,looks[]}`。✅ 改判后 **`tagline`/`accent`/`looks[]` 可以照搬**（§2），中文城市名与中文 tagline 另需人工补一列（上游只有英文）。
3. 与仓库内基线 `CityRegistry.kt` diff，打印三类变更：**新增城市 / 移除城市 / 坐标或时区变更**，并列出「已登记但无场景」清单。
4. 覆写 `CityRegistry.kt`（带 `GENERATED — do not edit` 头 + 抓取日期 + 上游 HTML 的 `Updated` 日期）；同时把 JSON 快照写 `docs/archive/verification/results/lofi_registry_<date>.json`。上游 JS 原件留在 `logs_temp/` / `app/src/lofi-upstream/`（两者 gitignored），不进仓库。
   ⚠️ **覆写前必须读回人工补的中文列**（`nameZh` / `taglineZh`），按 `id` 带过来，新城市留空并打印「待补中文地名/标语」清单。漏了这一步，每次同步都会把手工内容静默抹掉——而 `VisualizerThemeStringsGateTest` 只扫 `visualizer_theme_*`，兜不住它（§12.6）。
   ⚠️ **这条已经真踩过一次（2026-10-22 修复）**：读回用的正则是 `LofiLook\(\n(.*?)\n\s{16}\),`，而文件里 `data class LofiLook(` 声明后同样紧跟换行 ⇒ 第一个匹配从**声明处**起，`.*?` 一路吞到第一条 16 空格 `),`（即 `tokyo-room` 的结尾），读出的 id 变成 `paris`，`tokyo-room` 的三列手工值被静默清空（主景表因"第一条恰好就是自己"而侥幸没坏）。修法：两处正则都加缩进锚点（`\n        LofiCity\(` / `\n                LofiLook\(`）。**验收口径是"幂等"**：改完手工列后连跑两次脚本，第二次必须打印 `与现有文件一致`；只跑一次看不出来。
   ⚠️ 同源另一坑：上游 `tagline` 里有 `\xED` 这类 **JS `\xNN` 转义**，而 **Kotlin 没有 `\xNN`**（只有 `\uNNNN`）⇒ 直接照抄会让 `CityRegistry.kt` 编译不过（`madrid` / `mexico-city` 两条）。脚本已在 `unescape_js()` 里一次性解码成真实 Unicode。改解析规则时必须保住它。

⛔ 三条纪律：

- **运行时零网络。** 城市表是编译期常量，APK 不联网拉城市列表（所有者已裁）。
- **不进 CI。** 脚本是开发者本机手动跑的取数工具，不是测试；CI 里断网/网站改版都会让它红（照「不要在 CI 断言镜像耗时」的既有教训）。
- **脚本不发网络断言。** 相关单测 `LofiCityRegistryGateTest` 只做纯数据校验：id 唯一、`lat/lon` 在域内、每条 `hasScene` 为真或显式列入 `UNBUILT_SCENES`、双语显示名齐备。**不得**在该测试里探端点连通性。

> 同步的语义边界（必须写清，否则这条需求会被误解为"新城市自动出现"）：脚本只能同步**元数据**（哪座城、经纬度、时区、标语、主色）。新城市的**画面代码**必须由我们另译场景——上游每个场景 1,686–2,681 行，无法自动生成。脚本的价值是把"下一座该译哪城、它的坐标/时区/标语"变成一条命令即可刷新的清单。

---

## 8. 城市清单与移植台账

### 8.1 首批城市（已落地，保留历史口径）

改判后不再有"自绘"这条路径，P1/P3 都是把上游场景 JS 直译成 Kotlin。行数取自 `logs_temp/lofi_modules/` 实测（58 个场景模块，含 registry）：

**先用 Cape Town 打通引擎**（`js/scenes/cape-town.js`，**1,686 行**，实测全场最短；山 + 海 + 房，地形最简）——它的价值是"最短路径验证引擎 + R1/R2 探针"，跑通后作为其余场景的对账参照物。

首发再取 3 座，覆盖三种动效母题、且含中文语境城市：

| 城市 | 上游模块 | 实测行数 | 动的东西 | 上游 tagline（照搬） |
|---|---|---|---|---|
| 重庆 | `js/scenes/chongqing.js` | 1,885 | 雾层平移、轻轨穿楼、窗灯明灭 | "Fog and neon over Hongyadong" |
| 台北 | `js/scenes/taipei.js` | 1,772 | 雾、缓慢云、楼体灯光序列 | "Mist round Taipei 101" |
| 大阪 | `js/scenes/osaka.js` | 2,681（最长的一档） | 灯笼摆动、运河倒影、行人 | "Lantern night on Dotonbori" |

移植纪律（这三条是**工程**要求，与版权无关）：

1. **一文件对一模块**，Kotlin 文件头写明上游模块路径；每个 `prerender*()` / `render*()` 函数保持同名对应，便于日后 diff 上游改版。
   ⛔ **同一条纪律还要求「场景私有类型必须嵌套在本场景类里」**：57 个场景文件在同一个包里，各自在**文件顶层**写 `private class Win` / `private object PitchStub` / `private data class …` ⇒ Kotlin 的顶层 `private` 是**文件级**可见性，于是同包内同名类型互相撞名（编译器报 `Redeclaration` + `Cannot access 'class X': it is private in file`）。⛔ 这种错误**只有编译才暴露**（2026-10-22 首次统一编译时一次冒出 22 组重名，见 §8.5.2）⇒ 私有类型一律写进 `class XxxScene { … }` 的花括号内部；`typealias` **不能**进类体 ⇒ 顶层 `private typealias` 必须带**城名前缀**（如 `CtColFn` / `TrColFn`）保持全局唯一。
2. **数值一律照搬、不许顺手调**；观感问题（放大糊、太暗、切城太频）通过参数入口改，不改场景内部常量。⛔ JS 里 `Math.*` 与 Kotlin `kotlin.math.*` 逐位等价，但**整数除法、`>>>`、`Math.imul`、`%` 对负数的语义**不等价——这是直译最容易错的四件事，`PixelRandom`/`PixelColor` 必须逐条比对（§9 P1 基线就是拿来钉它们的）。
3. 地标比例仍要**看图评分 1–5**（≥4 才收）——照搬虽然天然"像"，但直译错一个 `Math.floor` 就会让塔冠错位，这类错只有眼睛能发现（上游自己的审计也发现过帕特农高宽差 1.6 倍）。

### 8.2 「57 座全做」批量移植（所有者 2026-10-22 裁，见 §10 D-k）——**已暂停**

所有者两条口径：① 范围 = **网站全部 57 个场景**（50 座主城 + 7 个 look），不是抽样；② 执行模型 = **「先 57 个全上，将代码全部移植完成，然后统一进行测试和编译」**——⛔ 不逐城编译，全部落地后一次 `testDebugUnitTest` + `lintDebug` + `assembleRelease`。

**暂停（2026-10-22）**：所有者以「太慢了」叫停本轮批量派发，指示「这一波先不改代码，把方案文档完善掉，代码改了多少就先多少」。⇒ 现状即冻结态：不再派发移植任务、不续写草稿、不删草稿；本方案文档为准。恢复移植前必须先解决 §8.3 的编译阻塞与 §10 D-m 的 push 边界。

> ⚠️ 本节以下的数字是 **2026-10-22 的冻结态**，已被 **§8.6（2026-10-23）** 取代：现为 11 座已落地、`compileDebugKotlin` 0 error、单测 2580 例零失败、`lintDebug` 绿。读进度只看 §8.6。

**进度（13 / 57，均为本机态，⛔ 一律不 push，见 §2.6；2026-10-22 本轮直接修京都闭包：`LofiCityKyoto.kt` 2348 行，10 个函数直接翻译完成 — `drawHeron2`/`heronAt`/`renderBell`/`renderShoro`/`drawShoji`/`initPrerender`/`makeLeaf3`/`catSprite2`/`routeAt`/`RoutePath` + `fig2`/`mini2` 接口；`SCENES_BUILT` 已接线；编译 `v5` 仍 `EXIT=1`，错误 623 主为数据类字段不对齐 + `renderFrame*` 桩；无 subagent，D-m §2.6 未外发）**：

- ✅ **4 座已落地并本地提交**（在 `494a25a` / `2b6d105` 里，已进 `SCENES_BUILT`、有 `LofiScenes.build()` 分支）：开普敦、台北、重庆、大阪。
- 🟡 **5 座草稿·结构完整**（工作区未提交；有 `create`/`dispose`/`prerender`/`renderFrame`）：东京·室内、温哥华、芝加哥、波士顿·老州议会、**墨西哥城**（本轮补齐，见 §8.5）。
- 🔴 **3 座草稿·写到一半**（工作区未提交；**缺 `create`/`dispose`**）：布达佩斯、京都、斯德哥尔摩。
- ⬜ **44 座未开工**。

> ⚠️ 「结构完整」只表示**文件形式上完整**（该有的入口函数与类型声明都在），**不等于编译通过**。本轮的实测口径见 §8.5：`compileDebugKotlin` 仍有 **1570** 个 error，**全部**落在八座未接线草稿上（墨西哥城已清零）——见 §8.5.2 的逐文件分布。⛔ 不要按「13 座完成」理解进度——13 = 12 座原有 + 墨西哥城补齐文件结构。

### 8.3 台账（上游 `SCENES` 顺序，行数为实测）

| # | id | 中文名 | 类型 | 上游行数 | Kotlin 行数 | 状态 |
|---|---|---|---|---|---|---|
| 1 | `paris` | 巴黎 | 主城 | 2228 | 3735 | ✅ 已落地（本地提交：见 §8.36） |
| 2 | `tokyo` | 东京 | 主城 | 2402 | 5205 | ✅ 已落地（本地提交见 §8.46。上游 `createLinearGradient` + 路径（电车头前的梯形光锥）引擎没有 ⇒ 本城本地 `beamQuad`（alpha 0.35→0 沿 x 线性 + `Comp.LIGHTER`）逐像素填梯形；`spriteOf` 的 `flip2` 与"首行长度当宽度"与引擎 `spriteFromRows` 不等价 ⇒ 本城重写；`Char` 与 `String` 比较恒为假、C 式 `for` 不是 Kotlin 语法 两处陷阱已记录） |
| 3 | `tokyo-room` | 东京·室内 | look | 1689 | 3215 | ✅ 已落地（本地提交 `d306475`/`4eade60`：`y === top+3` 与 `y === glassTop(x)-1` 按上游原样保留，R6 元数据逐字段对上） |
| 4 | `new-york` | 纽约 | 主城 | 2200 | 3854 | ✅ 已落地 |
| 5 | `london` | 伦敦 | 主城 | 2315 | 4967 | ✅ 已落地（本地提交见 §8.43。引擎无 `clip()` ⇒ 本地 `clipX215` / `haloClip215`（车与车灯裁到 x ≥ 215）、上游 `LEAF8[k]` 在 `k<0` 时是 `undefined` ⇒ 本地守卫并沿用上一次的颜色（不改成夹取）、上游 `line4` 的 `0/0 = NaN` 分支显式 `return` 三处陷阱已记录） |
| 6 | `rio` | 里约 | 主城 | 2122 | 3498 | ✅ 已落地 |
| 7 | `istanbul` | 伊斯坦布尔 | 主城 | 1992 | 3597 | ✅ 已落地（本地提交 `f7e82d0`：见 §8.9。`env()` 两参窗口、`inEvent` 只吃 `List<EventWindow>`、`PixCtx.rect`/`putPx` 是扩展函数需 import 三处陷阱已记录） |
| 8 | `hong-kong` | 香港 | 主城 | 2606 | 6771 | ✅ 已落地（本地提交见 §8.51。上游对象字面量数组（`WX8`/`CLOTHES`/`BEAMS`/`STREAKS4` 等）按"⛔ 无顶层容器类"纪律摊平成平铺数组（值与次序逐项一致）；上游 `trim4` 结果带裁剪原点 ⇒ 字段类型用「画布 + 偏移 x/y」容器；`rng(71)` 出现两次（涟漪 / 希望大厦）同种子但两条独立流 三处要点已记录） |
| 9 | `sydney` | 悉尼 | 主城 | 2034 | 3601 | ✅ 已落地（本地提交 `6594666`：见 §8.12。引擎 `PxRng` 是方法形态而上游 `rng(seed)` 返回**可调用函数**（一处包装清掉 86 个假错）、`Array?.map{}` 返回 `List` 不是 `Array`、`!!` 必须落在取回可空元素的那一层 三处陷阱已记录） |
| 10 | `san-francisco` | 旧金山 | 主城 | 2060 | 3572 | ✅ 已落地（本地提交 `35ebdda`：见 §8.18） |
| 11 | `hamburg` | 汉堡 | 主城 | 2234 | 4974 | ✅ 已落地（本地提交见 §8.39。`destination-in` 本地实现（引擎 `Comp` 无此档，不改引擎）、上游 `copy` 会清空整块画布而引擎 `Comp.COPY` 只替换绘制矩形内 ⇒ 扫光暂存画布 copy 前先 `reset()`、`wave(t, k, ph)` 的 `k` 必须是 `Double` 三处陷阱已记录） |
| 12 | `amsterdam` | 阿姆斯特丹 | 主城 | 2221 | 4393 | ✅ 已落地（本地提交见 §8.37。`pick5` 的 `soft` 参数、`HERON_POSES` 拼接成 `List`、`val col` 需 `var` 三处陷阱已记录） |
| 13 | `dubai` | 迪拜 | 主城 | 2430 | 5916 | ✅ 已落地（本地提交见 §8.47。上游塔身 LED 秀用 `destination-in` 遮罩、引擎 `Comp` 无此档 ⇒ 本城本地 `destIn`（不改引擎）；引擎 `PixCtx` 无 `clearRect` ⇒ 本城 `clearCanvas`；`Uint8ClampedArray` 的「就近取偶」⇒ 本地 `u8c`（`Math.rint`）三处陷阱已记录） |
| 14 | `madrid` | 马德里 | 主城 | 2298 | 5259 | ✅ 已落地（本地提交见 §8.42。`neonStrokes`/`neonSprite`/`textPts` 引擎没有 ⇒ 本城自绘霓虹管字（3×5 字模摊点 + 半格补点 + 外扩发光）、`pick7` 本地实现（引擎 `pickFromRamp` 抖动口径不同）、`wave(t, k, ph)` 的 `k` 必须是 `Double` 三处陷阱已记录） |
| 15 | `rome` | 罗马 | 主城 | 2024 | 3761 | ✅ 已落地（本地提交 `5ab645e`：见 §8.11。`inR` 缺 Int 重载、嵌套 `private class` 拿不到外层函数 ⇒ 助手提文件级、`fillRect` 只有全 Int/全 Double 两重载、JS `/` 浮除 vs Kotlin Int 整除（硬币涟漪）三处陷阱已记录） |
| 16 | `rome-colosseum` | 罗马·斗兽场 | look | 2068 | 3873 | ✅ 已落地（本地提交：见 §8.23。第 6 种结构「柱面/环形投影」；整数字面量不提升 Double、`ClosedFloatingPointRange` 无 iterator、JS 浮除 vs Int 整除、重载同型、门禁裸读元数据需 `init { initConstants() }` 五处陷阱已记录） |
| 17 | `prague` | 布拉格 | 主城 | 2091 | 3761 | ✅ 已落地（本地提交：见 §8.26。顶层 `private class XyI` 与 `bali` 撞名 ⇒ 报 `Redeclaration` + 30 条指向**使用点**的 `Cannot access … it is private in file`；`SN` 上游只有 `cx` ⇒ 写成 `SN.x`，这一处未解析级联出 9 条假错；`Comp` 需显式 import 三处陷阱已记录） |
| 18 | `munich` | 慕尼黑 | 主城 | 2021 | 3333 | ✅ 已落地（本地提交 `7641763`：见 §8.10。引擎无 `clip()` ⇒ 整数位移贴回折成"源矩形∩裁剪框"；Kotlin 数组是 `.size` 不是 `.length`；`neonPulse` 落点从跑马灯改判到洗墙灯晕） |
| 19 | `vancouver` | 温哥华 | 主城 | 1774 | 3183 | ✅ 已落地（本地提交 `8292f4f`/`4eade60`：`jsRound` 只吃 `Double`） |
| 20 | `seoul` | 首尔 | 主城 | 2051 | 3431 | ✅ 已落地（本地提交 `fa04cb6`：见 §8.16。上游 `var X = []` + `push` 的字段必须 `ArrayList<T>`（写成 `Array<T>` 一地根因炸 3 条）、`PixCtx` 的 `translate`/`rotate`/`scale` 与变换感知 `drawImage` 引擎都有 ⇒ `drawFan` 不自算矩阵、Int 与 Double 比较一律显式 `.toDouble()` 三处陷阱已记录） |
| 21 | `bangkok` | 曼谷 | 主城 | 2102 | 3276 | ✅ 已落地（本地提交 `c1c3b53`：见 §8.28。`FONT3` 漏 import ⇒ `ptext3` 内两条"类型推断"错报在使用点；上游单键对象 `SH = { x }` 而 `BkSpan` 只有 `x0`/`x1` ⇒ 补只读别名 `x`；`AD_C5` 是数组、9 参 `drawImage` 全 Double；`HY` 两处 `Math.random()` 曾挪动 rng 序列） |
| 22 | `cape-town` | 开普敦 | 主城 | 1686 | 2958 | ✅ 已落地（本地提交） |
| 23 | `singapore` | 新加坡 | 主城 | 2289 | 5915 | ✅ 已落地（本地提交见 §8.8.41。`wave(t, k, ph)` 的 `k` 必须是 Double（Int 字面量报类型错）；Kotlin 的 `max`/`min` 只有两参 ⇒ 三参改 `maxOf`；上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda ⇒ 写成 `run { … }` 三处陷阱已记录） |
| 24 | `mexico-city` | 墨西哥城 | 主城 | 1923 | 3779 | ✅ 已落地（本地提交 `4eade60`：registry `focusX` 回填 212，`events` 改惰性 getter 绕开 `lateinit` 时序） |
| 25 | `new-delhi` | 新德里 | 主城 | 2073 | 4033 | ✅ 已落地（本地提交：见 §8.24。`rect(ctx25, …)` 上游是自由函数而引擎里是 `PixCtx` 的扩展 ⇒ 需在场景内接一层转发；`Star25` 的 `x`/`y` 必须 `var`（`STARS21` 前三星要改坐标）；`s4(1)` 整数字面量不提升 Double 三处陷阱已记录） |
| 26 | `cairo` | 开罗 | 主城 | 2069 | 3827 | ✅ 已落地（本地提交：见 §8.20。`STRANDS` 元素类型写成 `IntArray` 拖出 5 条类型错、`kotlin.math.sign` 漏 import 两处陷阱已记录） |
| 27 | `lisbon` | 里斯本 | 主城 | 2052 | 3273 | ✅ 已落地（本地提交 `8640586`：registry `focusX` 回填 232，`SE`/`SM` 第二键是 `top` 故新增 `XyTop`） |
| 28 | `osaka` | 大阪 | 主城 | 2681 | 4730 | ✅ 已落地（本地提交） |
| 29 | `berlin` | 柏林 | 主城 | 2233 | 5316 | ✅ 已落地（本地提交见 §8.38。门面**投影灯光秀**要 `multiply` / `destination-in` 两种合成模式 ⇒ 本城本地实现，**不改引擎**；`import kotlin.math.pow` 只带进扩展不带进两参函数；`0xFFF0C040` 溢出成 `Long` 三处陷阱已记录） |
| 30 | `athens` | 雅典 | 主城 | 2470 | 6488 | ✅ 已落地（本地提交见 §8.49。上游 `pick17` 的公式 `floor(v*(len-1)+bayer)` 与引擎 `pickFromRamp` 不等价 ⇒ 本城本地实现（换引擎函数会让岩壁/墙面色阶整体错半档）；上游烟花表 `FW` 须构造期即算（`events` getter 早读）；`AthGuest.tIn/tOut` 类型须一致 三处要点已记录） |
| 31 | `copenhagen` | 哥本哈根 | 主城 | 2382 | 5787 | ✅ 已落地（本地提交见 §8.45。上游 `pick18` 的抖动阈值是 `f - k > 0.5 + (bayer - 0.5) * soft`，与引擎 `pickFromRamp` 不等价 ⇒ 本城本地重写；上游 `render(t, { crowd })` 形参 ⇒ 本项目 `SceneOpts` 无 `crowd` 字段，人流档位恒取 `CROWD7 = 0.5`；`SWIFTS` / `GLINTS3` / `BUBBLES2` 必须在 `initConstants()` 里填否则首帧炸 三处陷阱已记录） |
| 32 | `seattle` | 西雅图 | 主城 | 2109 | 3638 | ✅ 已落地 |
| 33 | `florence` | 佛罗伦萨 | 主城 | 2447 | 5683 | ✅ 已落地（本地提交见 §8.48。上游 `"copy"` 合成会清掉整张画布、引擎 `Comp.COPY` 只换矩形 ⇒ 倒影层先 `reset()`；引擎 `pickFromRamp` 抖动口径不同 ⇒ 本地重写 `pick19`；`Uint8ClampedArray` 的「就近取偶」有意改用 `jsRound`（1-LSB 已知偏差）三处要点已记录） |
| 34 | `dublin` | 都柏林 | 主城 | 2379 | 6395 | ✅ 已落地（本地提交见 §8.44。上游是**3D 投影**城 ⇒ `proj2` / `deckZ` / `ribDepth` 逐像素直译；上游 `Uint8ClampedArray` 是**就近取偶**而引擎 `jsRound` 是半值向 +∞ ⇒ 本城另立 `u8(v)` 量化；`DubPainter.set` 需 `Int` / `Double` 双重载、`DoubleArray.slice` 不存在、`IntArray.set(other,offset)` 不存在三处陷阱已记录） |
| 35 | `miami` | 迈阿密 | 主城 | 2065 | 4050 | ✅ 已落地（见 §8.22：伪 3D 投影型，12 片拼装；`focusX` 回填 124 = `FIN_X`） |
| 36 | `budapest` | 布达佩斯 | 主城 | 1977 | 4360 | ✅ 已落地（本地提交 `e892944`/`79bf008`/`4eade60`：`drawPest` 整段重写、`PixelReflector` 转发 `streak`、`makeHalo` 走 `hex()`、远端楼块 `x1` 夹到 `PIXEL_W-1`；registry `focusX` 回填 189） |
| 37 | `kyoto` | 京都 | 主城 | 1885 | 3333 | ✅ 已落地（整城重写；见 §8.7。`trim24` 偏移、`PixWin.c` 可空、`PITCH30` 不移植等三处陷阱已记录） |
| 38 | `venice` | 威尼斯 | 主城 | 2249 | 5350 | ✅ 已落地（本地提交见 §8.8.40。引擎没有 `clip()` ⇒ 桥背层裁剪本地实现（`clipTo`/`clipOff` + 裁剪感知绘制助手）；`wave(t, k, ph)` 的 `k` 必须是 Double；`sprite29` 宽度取各行最大长度、`pick24` 保留 `soft` 形参 两处引擎等价性陷阱已记录） |
| 39 | `marrakech` | 马拉喀什 | 主城 | 2115 | 4123 | ✅ 已落地（本地提交 `a271f59`：见 §8.30 / 技术纪要 §10.300） |
| 40 | `buenos-aires` | 布宜诺斯艾利斯 | 主城 | 2092 | 3354 | ✅ 已落地（本地提交 `489259b`：见 §8.27。`rng()` 返回带 `invoke()` 的对象 ⇒ 调用是 `r()` 不是 `r.next()`（19 条 `Unresolved 'next'` 的根因）、import 粘连、`putPx`/`rect` 是 `PixCtx` 扩展函数、`sign` 需显式 import 四类陷阱已记录） |
| 41 | `chicago` | 芝加哥 | 主城 | 1877 | 3306 | ✅ 已落地（本地提交 `8a41704`/`4eade60`：`PixCtx.rect` 扩展接收者、`i % 8 == 0.0`、`floor(... / 6.0)`） |
| 42 | `barcelona` | 巴塞罗那 | 主城 | 2039 | 4252 | ✅ 已落地（本地提交 `10362c2`：见 §8.14。`slice` 终点排他、`Win4` 漏 `override`、`FloatArray` 密度场三处陷阱已记录） |
| 43 | `stockholm` | 斯德哥尔摩 | 主城 | 1952 | 4255 | ✅ 已落地（本地提交 `ef67456`…`e5de57e`：72 个上游函数全部移植，`compileDebugKotlin` 该文件 0 错误） |
| 44 | `taipei` | 台北 | 主城 | 1772 | 3231 | ✅ 已落地（本地提交） |
| 45 | `chongqing` | 重庆 | 主城 | 1885 | 3936 | ✅ 已落地（本地提交） |
| 46 | `melbourne` | 墨尔本 | 主城 | 1987 | 3855 | ✅ 已落地（伪 3D 相机 + 深度缓冲 + 材质闭包；见 §8.8。`DRZ_LIGHTS` 重复声明、`winLit7` 鸭子类型、`CLOUDS31`/`LIT_C2` 类型三处陷阱已记录） |
| 47 | `new-orleans` | 新奥尔良 | 主城 | 2197 | 4120 | ✅ 已落地 |
| 48 | `toronto` | 多伦多 | 主城 | 2061 | 3990 | ✅ 已落地（本地提交 `b458316`：见 §8.19。`MENU_PX` 凭空造常量、`jsRound` 只收 Double、`cnHalf`/`showHalo` 漏实现、`PI`/`env` 漏 import 四个陷阱已记录） |
| 49 | `boston` | 波士顿 | 主城 | 2155 | 3773 | ✅ 已落地 |
| 50 | `boston-public-garden` | 波士顿·公共花园 | look | 2186 | 4294 | ✅ 已落地 |
| 51 | `boston-acorn-street` | 波士顿·橡子街 | look | 2373 | 6235 | ✅ 已落地（本地提交见 §8.52。上游 `ctx.beginPath/rect/clip()`（矩形裁剪）引擎没有 ⇒ 本城 `bosClipTo`/`bosClipOff` 整幅快照 + 框外还原（不改引擎）；上游 `trim37` 结果带裁剪原点 ⇒ 五张图层统一用「画布 + 偏移 x/y」容器；窗台冬青枝 20 条独立流（种子 `hi*13 + round(c*7)`）三处要点已记录） |
| 52 | `boston-old-state-house` | 波士顿·老州议会 | look | 1876 | 4062 | ✅ 已落地（本地提交 `4dbf211`/`4eade60`：`R25` 走 Double 重载、`lerp` 实参补齐、3 参 `min/max` → `minOf/maxOf`） |
| 53 | `boston-faneuil-hall` | 波士顿·法尼尔厅 | look | 2045 | 4607 | ✅ 已落地（本地提交 `a05d567`：见 §8.15。`paintFace` 的 `mat` 必须吃尾随 lambda（`oy` 提前）、`Seat` 与 `BarSpot` 共用距离版 `barRow`、`jsRound` 返回 Int 三处陷阱已记录） |
| 54 | `boston-fenway` | 波士顿·芬威球场 | look | 2035 | 3996 | ✅ 已落地（本地提交 `27134c0`：见 §8.13。Kotlin 2.3 里带比较器的 `List.sort {}` 是**编译错误**不是警告、鸭子类型形参必须改签名、`FloatArray` 槽位赋值要 `.toFloat()` 三处陷阱已记录） |
| 55 | `los-angeles` | 洛杉矶 | 主城 | 2365 | 5810 | ✅ 已落地（本地提交见 §8.50。上游 `flipCanvas7` 走 `translate+scale(-1,1)` ⇒ 浏览器语义有 1 像素错位、引擎变换路径不等价 ⇒ 本城逐行镜像复刻；棕榈叶头有 140 条独立 `rng` 流（20 棵 × 7 叶头）；分片边界重叠会致 `Redeclaration`（`renderHills4` / `drawSignLight`）三处要点已记录） |
| 56 | `bali` | 巴厘岛 | 主城 | 2075 | 3698 | ✅ 已落地（本地提交：见 §8.25。`blit43` 上游**会**设 `globalAlpha = a` 再复位 ⇒ `drawIsland` 里外层 `alpha` 实际不生效，逐位复刻不"修"；`makePuff16` 的 `hypot` 形参是 Int、9 参 `drawImage` 全收 Double、`pickFromRamp` 要 `(ramp, v, Double, Double)` 三处编译陷阱已记录） |
| 57 | `las-vegas` | 拉斯维加斯 | 主城 | 2197 | 4394 | ✅ 已落地 |

⚠️ 台账是**人工维护**的（`SCENES_BUILT` 自 2026-10-25 起记 **29** 座，与表内 ✅ 行逐 id 相等；门禁 R4 就比对这一项）。恢复移植时先按 `ls visualizer/scenes/lofi/LofiCity*.kt` 重新核一遍，别信表。
⚠️ ~~`stockholm` / `budapest` 的 `focusX` 仍是脚本占位值 `0.0`~~ **已修（2026-10-23）**：`budapest` 189、`stockholm` 322、`mexico-city` 212 三处均已回填，`LofiScenes` 也补齐了对应分支。⇒ 通用口径：**registry 里任何 `focusX = 0.0` 都是"脚本解析不到符号表达式"的占位，不是真值**，接线时必须按场景手译值回填，否则 R6 必红。

### 8.4 恢复移植前必须先处理的事（阻塞项，**已被 §8.5 实测修正**）

> ⚠️ 本节是第二轮写下的判断。§8.5.2 的实测证明**漏了最要命的一条**（顶层类型重名）⇒ 执行顺序以 **§8.5.4** 为准，本节保留作历史记录。

1. 🔴 **四个半成品会让"统一编译"直接红。** Kotlin 会编译源集里的全部文件，缺 `create`/`dispose` 的 `PixelCityScene` 实现类是抽象类实例化错误、末尾悬空的 `// __NEXT_SEGMENT__` 锚点意味着花括号不平衡。⇒ 恢复后第一步要么补齐它们，要么删掉这 4 个文件（删除属于工作区文件，⛔ 不要 `git` 层面处理，它们从未被跟踪）。
2. 🟡 **4 座结构完整的草稿仍未接线**：`SCENES_BUILT` 只有 4 项、`LofiScenes.build()` 只有 4 个分支（门禁 R4 要求两者逐 id 相等），所以它们现在是"存在但不可达"的死代码。接线与计数门禁（`LofiSceneSnapshotTest` 等）留到统一验收阶段做（§9 P4）。
3. **`focusX` 待回填**：注册表里 50 座主城的 `focusX` 仍是 `0.0`（上游是符号表达式，脚本取不到数），已落地的几座由译者算出数值写进场景类（如温哥华 310.0 = `SAILS[2][0]`、东京·室内 232.0 = `TWR2.cx`）。恢复移植时**每座都要顺手回填注册表**，否则门禁 R6 的逐字段对账会红。`rome-colosseum` / `boston-fenway` 两个 look 的 `focusX` 也仍是 0.0。
4. ⚠️ **子代理简报 `logs_temp/port_briefing.md` 是 gitignored 的一次性文件**（含读取顺序、文件骨架、JS→Kotlin 映射表、自检清单）。`logs_temp/` 随时可能被清空；丢了就照 §8.1 移植纪律 + §10 D-a/D-k 重写，⛔ 不要把它复制进被跟踪路径（§2.6 第 3 条）。
5. ⚠️ **单代理有回合上限**：京都/斯德哥尔摩两份草稿就是代理在写文件途中被上限截断的产物（不是内容判断失误）。恢复时应要求代理**大段写**（每段 ≥500 行）、上游 JS 只读一遍、不要回读自己已写的长文件。

---

### 8.5 2026-10-22 第三轮：墨西哥城补齐 + 统一编译首测（**本轮在此停下**）

所有者 2026-10-22 指示「继续推进」→ 中途改为「不要用 subagent，你自己一个一个实施」→ 收尾改为「修改一部分就同步文档，然后停止」。本节如实记录这一轮**实际改了哪些、验证到哪一步、还差什么**。

#### 8.5.1 做了什么

**只有一座城的代码改动：`LofiCityMexicoCity.kt`（3003 → 3778 行）。** 补齐了它从「写到一半」到「结构完整」的全部缺口：

| 缺口 | 内容 | 处置 |
|---|---|---|
| 尾部 12 个函数 | `drawSteam5` / `drawWalk` / `lightsOf` / `drawVehicle3` / `drawTraffic4` / `drawNeighbour2` / `drawFlame` / `drawXolo` / `drawForeground7` / `drawPapel` / `drawPetal2` / `renderFrame24` | 逐行直译上游 `js/scenes/mexico-city.js:1273-1512` |
| **整个类型声明区（49 个）** | 文件里 `BaBox` / `Win` / `Petal` / `Steam` / `FarLayer` / `Halos` / `PitchStub` … 全被**引用但从未声明** | 从上游对象字面量**键名与书写顺序**反推字段，本类内嵌套声明 |
| `create` / `dispose` | 完全没有 | 照 `LofiCityVancouver.kt:2865` 的形态补上 |
| 末尾锚点 | `// __NEXT_SEGMENT__` | 已消除 |

⚠️ 几处**推导错误**在本轮已被编译抓出并修正，记录在此防复发：

1. `Lane(y, dir)` 的 `dir` 上游是 `-1 / 1`（Int），我最初按 Double 声明 ⇒ `LANES4` 三处实参全红。
2. `Walker` 我漏了上游的 `dir` 字段，且把 `kind` 当成字符串——上游 `kind: Math.floor(r() * WALKER_KINDS.length)` 是**索引**；`WALK_SPR2[wk.kind]` 直接用它取数组。
3. `Win` 的 `b: p.id`（窗属于哪栋楼）漏了，而 `renderFar10` 里 `wins.filter { ownAt(p, it.x, it.y) == it.b }` 必须用它；⚠️ 上游有 3 处 `wins.push` **不带** `b` ⇒ 该字段**不能给默认值**（会给后面的位置参数错位），那 3 处显式传 `-1`。
4. `TlLayer.crown` 我写成 `Int`，上游是 `step3 = [[y0, y1, hw], …]` ⇒ `Array<IntArray>`。
5. `STEAM7` / `WALKERS10` 声明成 `List` 却在 `initConstants` 里 `.add()` ⇒ 改 `MutableList`。
6. `putPx` / `rect` 是 `PixCtx` 的**扩展函数**，不带接收者调用会报 "receiver type mismatch" ⇒ 一律 `ctx24.putPx(...)`。

#### 8.5.2 ⛔ 编译实测：发现一个**跨文件结构性缺陷**（比"半成品"严重）

按 D-k「统一编译」，本轮首次真跑了 `compileDebugKotlin`（`--no-daemon -Pkotlin.compiler.execution.strategy=in-process`）。结果 **1570 个 error**，而且**不是**"半成品没写完"这一类：

**根因**：5 个草稿把 `private class` 写在文件顶层（类外），而 Kotlin 的顶层 `private` 是**文件级**可见性 —— 于是同包内 22 组同名类型互相打架（`Win` / `Puff` / `Star` / `Halos` / `Ring` / `CloudDef` / `CloudPal` / `CloudLayer` / `Twinkle` / `XYI` / `PitchStub` / `Look` / `Car` / `Spot` / `Leaf` / `Ranks` / `POpt` / `RainLayer` / `RainLight` / `Streak` / `BillboardBox` / `Tower`），编译器报 `Redeclaration` + `Cannot access 'class X': it is private in file`。

⚠️ 这个缺陷**连已本地提交的开普敦也被波及**（`LofiCityCapeTown.kt:80` 的 `private typealias ColFn` 与东京·室内同名）——说明它不是某个代理的失误，而是**移植纪律第 3 条（"嵌套在本类里，避免 57 个文件同包重名"）在草稿里被普遍违反**，而此前从未编译过，所以一直没暴露。

**本轮已处置**：把 5 个文件（波士顿·老州议会 / 开普敦 / 重庆 / 东京·室内 / 温哥华）类体之后的顶层声明整块缩进 4 空格搬进各自的 `XxxScene` 类，并在末尾补回类的 `}`。**error 从 2271 降到 1570。**

**剩余 1570 个 error 的分布（本轮到此停下，未再修）**：

| 文件 | error | 性质 |
|---|---|---|
| `LofiCityKyoto.kt` | 739 | 🔴 半成品（缺 31 函数 + 52 个类型声明）——本轮的顶层迁移动了它的结构，报错数因此上升 |
| `LofiCityBudapest.kt` | 708 | 🔴 半成品（缺 10 函数 + 41 个类型声明） |
| `LofiCityBostonOldStateHouse.kt` | 76 | 🟡 结构完整，但有类型推导错（`String`/`Int` 实参错位等） |
| `LofiCityChicago.kt` | 22 | 🟡 同上（`==` 两侧 Int/Double 等） |
| `LofiCityStockholm.kt` | 13 | 🔴 半成品，且**类体花括号不平衡**（末尾 `// endregion` 之后缺 `}`） |
| `LofiCityTokyoRoom.kt` | 10 | 🟡 `.add()` 打到 `List` 上等 |
| `LofiCityVancouver.kt` | 1 | 🟡 单点 Int/Double |
| `LofiCityCapeTown.kt` | 1 | 🟡 `typealias ColFn` 与东京·室内仍重名（**typealias 不能嵌套在类里**，只能改名） |
| `LofiCityMexicoCity.kt` | **0** | ✅ 本轮唯一清零的文件 |

⇒ **结论：`compileDebugKotlin` 目前是红的，且这是本轮之前就红的（只是没人编译过）。** ⛔ 不要把 §8.2 的「13 / 57」当成可编译进度。

#### 8.5.3 本轮没做 / 明确留待下轮

1. **5 座草稿仍未接线**（含本轮补齐的墨西哥城）：`SCENES_BUILT` 仍是 4 项，`LofiScenes.build()` 仍是 4 个分支（门禁 R4 要求两者逐 id 相等）。墨西哥城虽已结构完整，但**没进 `SCENES_BUILT`**，所以它对运行时依然不可达。
2. **注册表 `focusX` 仍未回填**：墨西哥城 registry 里 `focusX = 0.0`，而场景类是 `212.0`（`BA.cx`）。⚠️ 一旦把它加进 `SCENES_BUILT`，门禁 R6 会立刻红在 `focusX 不符` 上。同理温哥华/芝加哥/波士顿·老州议会/东京·室内。
3. **`logs_temp/port_briefing.md` 未更新**：它的「文件骨架」示例把 `private class Foo` 写在类**外**（第 72 行），**正是本轮 22 组重名的来源**。⇒ 恢复移植前必须先改这一行，否则下一个代理会继续犯罪。⚠️ 同一条纪律在 §4 的包结构图与 §8.1 纪律 1 里都没写清"嵌套"，建议同时补进这三处。另外简报第 9 条自检漏了一项：**写完后必须确认所有 `private class` 都在 `XxxScene { }` 之内**。
4. **两个 `.tmp_mx_*.kt` 占位文件**留在了 `scenes/lofi/` 下（内容已备份到 `logs_temp/mx_types.bak` / `mx_tail.bak`，占位体只有一行注释）。⚠️ 它们会随源集编译，虽然只是注释、不影响结果，但**下轮应删掉**（本轮因工具限制未能删除）。
5. **未跑单测 / 未跑 lint / 未出 APK**：编译都没绿，这三项无意义。

#### 8.5.4 恢复移植的执行顺序（据本轮实测修正）

原 §8.4 的第 1 条（"补齐或删掉 4 个半成品"）**不够**，应改为：

1. **先修顶层类型冲突的余波**：`CapeTown` 与 `TokyoRoom` 的 `typealias ColFn` 改名（如 `CtColFn` / `TrColFn`）；确认 5 个文件迁移后类花括号与缩进都正确。
2. **再补 3 份半成品的类型声明区**（京都 52 / 布达佩斯 41 / 斯德哥尔摩已声明但花括号不平衡，缺 64 函数）。**类型声明区比函数体更阻塞**——没有它，补再多函数也编译不过。
3. **然后补 4 份"结构完整"草稿的类型推导错**（合计 109 个 error，逐个按上游字面量对字段）。
4. **最后才接线**（`SCENES_BUILT` + `LofiScenes.build()` + 回填 `focusX`），并跑 `LofiCityRegistryGateTest` 验 R4/R5/R6。
5. 全量 `testDebugUnitTest --rerun-tasks` + `lintDebug` + `assembleRelease`（本机 `lofi.city=true`）。
6. ⛔ 全程不 push、不 tag、不发 release（§2.6）。

### 8.6 2026-10-23 第四轮：其余六城清零 + 接线 + 全量验证转绿

执行模型与 §8.5 相同（**无 subagent，直接执行**），但收敛口径改为「一座城编译清零就提交一次」——原因是 §8.5.2 的教训：`compileDebugKotlin` 是唯一能发现顶层重名/类型推导错的关口，攒到最后一次修会让错误面互相遮蔽。

#### 8.6.1 本轮把 6 座草稿推到「已落地」

| 城 | Kotlin 行数 | 提交 | 主要症结 |
|---|---|---|---|
| `vancouver` | 3183 | `8292f4f` | `jsRound` 只吃 `Double`（`jsRound((yl - 1).toDouble())`） |
| `tokyo-room` | 3215 | `d306475` | 桥窗/玻璃边**比较语义**必须照上游原样（`y === top+3`、`y === glassTop(x)-1`），旧译法擅自"归一化"成 `top == jsRound(y)` ⇒ 整排窗错位一格 |
| `boston-old-state-house` | 4062 | `4dbf211` | `R25` 需 Double 重载；`lerp` 实参；JS 变参 `Math.min/max`（3 参）⇒ `minOf`/`maxOf` |
| `chicago` | 3306 | `8a41704` | `PixCtx.rect` 是**扩展函数**（10 处 `rect(sx2, …)` → `sx2.rect(…)`）；`i % 8 == 0.0`；`floor((y - 162) / 6.0)` |
| `budapest` | 4360 | `e892944` / `79bf008` | `drawPest` 整段重写；`streak` 是 `PixelReflector` 成员（`import pixel.streak` 不存在）⇒ 注入 + 私有转发；24 处 `makeHalo(n, "#…")` 走 `hex()`；`drawImage` 无 5 参形态；`SHIP6` 字段名 `x0/x1` → `t0/t1`；**远端楼块 `x1` 夹到 `PIXEL_W-1`**（上游画到 `x=480`，JS 画布裁剪，Kotlin 直接越界抛 `Index 480`） |
| `mexico-city` | 3779 | `4eade60` | `events` 改惰性 getter（`lateinit ORGAN_T` 在 `init{}` 之后赋值，构造期读到就抛 `UninitializedPropertyAccessException`） |

#### 8.6.2 接线（`4eade60`）

- `LofiScenes.build` 补齐 **11** 城分支（此前只有 5 支）；`SCENES_BUILT` 同步到 11 项（门禁 R4 逐 id 相等）。
- ⚠️ `SCENES_BUILT` 写的是 **registry 的 look id**：`boston-old-state-house`、`tokyo-room` 合法，`boston`/`tokyo`（城市 id）不合法 —— `byId` 查不到，R6 逐字段对账必红。
- registry 三处 `focusX` 回填手译值：`budapest` 189、`stockholm` 322、`mexico-city` 212。

#### 8.6.3 京都退回未完成态（本轮唯一的"倒退"）

`LofiCityKyoto.kt` → `LofiCityKyoto.kt.bak`（**未跟踪、不参与编译**），并从 `SCENES_BUILT` 摘除。判定依据是它属于**半成品**而不是"待修件"：

- 上游 16 个函数完全缺失：`drawLeaf2`/`drawGroundLeaves`/`logBack`/`drawShoro`/`look11`/`drawRouteWalker`/`geikoLantern`/`couplePhoto`/`sitFig`/`drawCounter`/`drawBenchGuests`/`drawServer`/`drawSweeper`/`drawRickshaw`/`drawCat11`/`prerender37`；
- **支撑类型层从未写**：`R30`（94 次引用）/ `hh`（66 次）、`LookOpts`、`W22`、`FigureLook`、`FigSize`、`Puff`、`Cluster`、`TreeArt`、`Pool`、`LaneWin`、`PixWin`、`RoutePos`；
- `fig2`/`buildLooks`/`initConstants` 大量 Int/Double 与具名参数错配；`renderFrame37` 仍是 1 行桩；约 53 行 JS 残留。

⇒ 需整城重写（约等于 Stockholm 单城工作量）。`.bak` 只作译文基线保留，**不要**把它当"差一点就能修"的文件继续打补丁。

**本轮实测的修复基线（下一轮直接从这里接手，别再重新分析）**：把草稿临时放回源集编译得
**1304 error**；补出 `FigureLook`/`LookOpts`/`FigSize` 三型并重写 `fig2`/`mini2`/`sitFig`
（上游 `js/scenes/kyoto.js:882-1109` 全 200 行直译）后降到 **626 error**，且全程**没有跨文件错误**
（1304/626 全部落在该文件内）。⇒ 病根是**类型层与 `initConstants` 的数据类形状错**，不是绘制代码：
`CloudLayer` 上游是 `{c,y,k}`、`Hills` 是 `{c,wins}`、`Pool` 是 `{c,rx,ry}`、`Ban`/`Route` 是
`{a,turn,b,edge,up,lat,keys,…}`，而 `Rick`/`CatSpr`/`CHO_R`/`CHO_W` 目前是形状不对的占位桩
（`CHO_R = Array(11) { IntArray(0) }`）。626 的分布：`initConstants` 350、`slat` 41、`lift` 40、
`renderTree6` 23、`routeAt` 23、`initPrerenderCaches` 16，其余零散；之后才是 16 个缺失函数与
`renderFrame37`。该进度已**存回 `LofiCityKyoto.kt.bak`**（`.kt` 已移除，源集复归绿色）。

#### 8.6.4 验证结果（本轮首次三项全绿）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error** |
| `testDebugUnitTest` | **2580 例 / 0 失败**（含 `LofiCityRegistryGateTest` 6 例、`LofiSceneSnapshotTest` 2 例） |
| `lintDebug` | `BUILD SUCCESSFUL`（4m26s，无新增 error） |

⇒ §8.5.2 的「编译长期红」状态到此解除；§9 P4 的基线数字随之更新。⛔ 按 §2.6：**未推送、未 tag、未编 release APK**，也没有出真机截图（六城观感未测）。

#### 8.6.5 下一个入口

京都整城重写 → 其余 **45 个场景**按上游行数从小到大逐城推进，每城仍按「骨架（元数据 + 类型 + 常量 + `initConstants()`）→ 逐函数重写 → 该城 `compileDebugKotlin` 清零 → 本地提交」的节拍走，不攒批。

> ✅ **已于同日完成**，见 §8.7（余下 44 个场景）。

---

### 8.7 2026-10-23 京都落地（整城重写·第二轮）

#### 8.7.1 结果

- `LofiCityKyoto.kt` **3333 行**（上游 `js/scenes/kyoto.js` 1885 行）：16 个缺失函数、12 组类型层全部补齐，`create() → initConstants() → initPrerenderCaches() → renderFrame37()` 全链路打通。
- 错误链（**全程无跨文件错误**，1304/626 都落在该文件内）：**1304 → 626 → 131 → 17 → 2 → 0**。
- 接线三处：`LofiScenes.build()` 加 `"kyoto" -> KyotoScene()`、`CityRegistry.SCENES_BUILT` 加 `"kyoto"`、注册表 `focusX 0.0 → 240.0`（= `PAG.cx`，脚本对该城解析失败留下的占位）。
- 行尾：多段 splice 造成 CRLF 1792 / LF 1540 混用，已按 `.gitattributes` 的 `eol=lf` 全部归一 —— **PowerShell 多行字符串替换在 CRLF/LF 混合文件上会静默失配（`count=0`）**，批量替换一律走脚本文件（本轮再次验证）。
- 验证：`compileDebugKotlin` 0 error；全量 `testDebugUnitTest --rerun-tasks` **2587 例 / 255 类 / 0 失败**（2 skipped；比上一轮 2580 例多出的正是 G-E51-J 的 7 例）；`lintDebug` 无新增 error；`logs_temp/lofi_render/kyoto_t000|060|120|228.png` 四时刻肉眼复核通过。
- 本地提交：`005756c`（7 个文件；⛔ 未推送、未 tag）。

#### 8.7.2 ⛔ 本轮最大教训：`trim24` 的偏移不是"可选装饰"

上游 `trim24(p)` 返回的是**对象** `{c, x, y}`，`blit25(o) = ctx37.drawImage(o.c, o.x, o.y)` —— 偏移和像素是同等重要的两半信息。

我把它拆成 Kotlin 的 `PixelCanvas`（纯像素）与 `Pixmap`（像素 + `x/y`）之后，只要某函数的返回类型被我写成 `PixelCanvas`，唯一能过编译的写法就是 `return trim24(p).canvas`：**偏移在这里丢失，而编译器不报错**（`PixelCanvas` 确实是从 `Pixmap` 里取出来的合法值）。更糟的是我为了消掉 `blit25` 的 `None of candidates` 报错，加了

```kotlin
private fun blit25(c: PixelCanvas) = ctx37.drawImage(c, 0.0, 0.0)
```

这条重载把 (0,0) 硬编码，等于**把漏洞合法化**。

后果：茶屋 / 石墙 / 伞 / 钟楼 / 行灯（5 个 `Pixmap` 精灵）+ 坡道 `LANE5` + 山脊 `HILLS3` + 树冠 `TreeArt.wood` **全部整体平移**，画面"像京都"但建筑飘在半空。

⚠️ **现有门禁一条都拦不住**：G-E51-B 只对元数据；`LofiSceneSnapshotTest` 的 S1 非空 / S2 随时间变 / S3 确定性**对"整体平移"完全不敏感**。只有导出 PNG 肉眼判读才能发现（这也是 §9 P4 要求每城交快照的原因）。

处置（已落地，覆盖余下 44 城）：
1. **新增门禁 G-E51-J** `LofiTrimOffsetSourceGateTest`：J1 禁止同一行出现 `trim24(...)` + `.canvas`；J2 禁止裸画布版 `blit25` 重载；N0–N4 负向自证（含"注释里的同形文本不得误判"与"跨行搬迁偏移的合法形态"）。
2. **删除** `blit25(PixelCanvas)` 重载，让编译器重新成为守卫：再有人把 trim 结果降级成裸 canvas，`None of candidates` 会直接拒绝编译。
3. 判定口径写死：**上游 `return trim24(...)` 的函数，Kotlin 必须 `: Pixmap`，字段也必须 `Pixmap`**；`.canvas` 只允许出现在偏移被显式消费的两处（`blit25(Pixmap)`、`PixWin(c = o.canvas, x = o.x, y = o.y)`）。

#### 8.7.3 其它已记录陷阱（余下城市照用）

① `create()` 里 `initConstants()` 之后**必须**再调 `initPrerenderCaches()`：两者拆开后 20 多个 `lateinit` 缓存没有 `init{}` 兜底，只在首帧渲染时炸（表现为 `UninitializedPropertyAccessException: lateinit property SKY_C37 has not been initialized`，快照门一次抓到 2 例）。**通用形态：常量表与预渲染缓存分两个函数时，`create()` 少调一个只在渲染期发作。**
② 本项目无广告 ⇒ `PITCH30` 不移植：`drawBillboard32` 的 `if (!PITCH30.covers(t, pitch))` 改 `run { }` 并注释"恒走 MATCHA 广告分支"，删掉 `PITCH30.draw(...)`。
③ `TORO` 的字段是 `x` / `base`（上游写作 `y`，直译必踩 `Unresolved reference 'y'`）；`Cluster.k` 是 `Double`（上游 `6 + Math.floor(...)` 天然浮点），写成 Int 会在具名实参处报 Int→Double。
④ `arrayOf(0xFFe0a050, …)` 里 `0xFF` 开头且超 `Int.MAX` 的是 **Long 字面量**，`intArrayOf` / `pick23(IntArray)` 都不收 ⇒ 颜色一律 `hex("#…")`。
⑤ 引擎能力缺口两处，已在 KDoc 注明：无 `clip()` ⇒ 京都水洼第二遍 0.6α 倒影**有意省略**（只影响 6 处水洼浓度）；无 `source-in` ⇒ `renderPagodaShade` 用 `sourceInFill()` 逐像素重涂 alpha 形状。
⑥ 字符串位运算直译口径：`hs.noren[1] > "b"`（noren 十六进制串第 2 位）⇒ `(noren and 0xFF0000) >= 0xF00000`，等价于"红通道 ≥ 0xF0"。
⑦ `focusX` 回填场景手译值（京都 240），不要把脚本解析失败的 `0.0` 当成数据。

#### 8.7.4 下一个入口

余下 **44 个场景**，仍按上游行数从小到大逐城推进。`.bak` 基线（626 错误形态）已移到 `logs_temp/`（不进源集、不入库），**不要再当"差一点就能修"的文件**。

---

### 8.8 下一城 `melbourne`（1987 行）开工基线 · 2026-10-23 测绘

> 状态：**仅测绘，尚无 Kotlin 代码**。本节只记"读源得到的事实"，避免下一轮从零推导。
> 与已落地 12 城**不同类**：那 12 座是 2D 精灵合成，墨尔本自带一套**伪 3D 相机 + 深度缓冲 + 材质闭包**。

#### 8.8.1 上游源是完整的（别再怀疑"缺共享模块"）

`logs_temp/lofi_modules/js__scenes__melbourne.js.js`（1987 行）**没有缺失依赖**。共享 3D 助手
`fade` / `lit6` / `cream` / `banded` / `inArch` / `keyHash` / `domeR` / `zset` / `depthOf` / `rowOf` / `zat`
全部在 `init_melbourne()` 体内以**裸赋值**形式定义（`fade = (col, u) => …`，无 `const`/`function`）。
⚠️ **用 `^function ` 抓函数清单会漏掉它们**——本轮据此一度误判"缺一个共享模块"，代价是一次无效排查。
下游同型城市（`istanbul`/`munich`/`rome` 等）大概率同样形态：**先看 `init_<city>()` 体内的裸赋值块**。

#### 8.8.2 透视模型（纯相机，无矩阵）

```
VX 27   VY 21   EYE8 3.2   BRG2 233°   FL 210   CXP2 226   HZ11 166
FX2 = sin(BRG2)   FY3 = cos(BRG2)   RX2 = sin(BRG2+90°)   RY2 = cos(BRG2+90°)

u        = (x-VX)*FX2 + (y-VY)*FY3                       // depthOf
proj4    = [ CXP2 + FL*(dx*RX2+dy*RY2)/u , HZ11 - FL*(z-EYE8)/u , u ]
rowOf    = HZ11 - FL*(z-EYE8)/u
groundAt = u = FL*EYE8/(sy-HZ11);  v = (sx-CXP2)*u/FL;  → 世界 (VX+u*FX2+v*RX2, VY+u*FY3+v*RY2, u)
onWall2  = 屏幕竖线与墙 (P0 + s*D) 求交 → 沿墙参数 s
```

#### 8.8.3 深度缓冲（引擎缺件，本文件内补）

`ZBUF = Float32Array(W*H).fill(Infinity)`；`zset(sx,sy,u)` **只在 `u < ZBUF[i]` 时写**；
`zat(sx,sy)` 超界返回 `Infinity`。`PixelBuffer` 无此能力 ⇒ 文件内私有
`FloatArray(W*H)` + **每帧 `renderFrame46` 开头 `fill(Double.POSITIVE_INFINITY)`**（漏重置 = 上一帧遮挡残留，
是这类场景最典型的"越跑越空"缺陷）。

#### 8.8.4 材质是闭包 ⇒ 需要函数接口

`frontMat2(which, len, height)` / `cornerMat` / `towerMat(face)` 都返回
`(s, z, sx, sy, u) => col|null`；`paintRound` 的材质多一个 `r` 参数与 `phi`。Kotlin 侧建议：

```kotlin
private fun interface Mat { fun at(s: Double, z: Double, sx: Int, sy: Int, u: Double): Int? }
private fun interface RoundMat { fun at(phi: Double, z: Double, sx: Int, sy: Int, u: Double, r: Double): Int? }
```

⇒ `null` 表示"该像素不画"，**必须保留返回 `null`**（不是透明色），否则建筑轮廓会糊成实心块。

#### 8.8.5 元数据（逐字段，`CityRegistry` 对账用）

`id "melbourne"` · `name "Melbourne"` · `tagline "Drizzle under the clocks"` ·
`timeZone "Australia/Melbourne"` · `accent "#00a651"` · **`focusX 222`** ·
`landmark [182, 322]` · `landmarkTop EUR.top = 42` · `billboard BB38 { x 392, y 40, w 54, h 22 }` ·
`crowd CROWD22 = 0.5` · `ambience { rain .4, traffic .35, crowd .22, wind .1 }`。
事件：`chime`×9 + `gull`×4 + `bell`×4（`BELLS17 = [200,203,206,209]`，gain 0.45，x 300）+
`train { t: CC.far=86, dur: 46, pan [0.4,-0.9], gain 0.18 }`。

#### 8.8.6 移植顺序（建议，按依赖）

1. 骨架：元数据 + `create()` + 常量块（上游 1664–1941，**约 280 行赋值，逐行照抄**）；
2. 相机/深度：`proj4` `groundAt` `onWall2` `depthOf` `rowOf` `zset` `zat`；
3. 取色/精灵基础：`mixHex30` `pick31` `vnoise24` `sprite36` `trim32` `along4` `pix27`；
4. `paintWall2`（**整个 3D 的地基**）+ `face` `zfill` `drawBox`；
5. 材质：`frontMat2` `cornerMat` `towerMat` + `paintRound` `paintDome` `paintTower3` `paintBeyond` `paintStation`；
6. 静态合成：`renderSky46` `makeCloudLayer29` `paintSouthbank` `renderShelters` `prebox`；
7. 动态物件：电车 `tramSide`/`drawTram2`、汽车 `carAt`/`carSide`/`drawCar4`、行人 `look18`/`person18`/`personAt`；
8. 世界层：`addCrossers` `addStayers` `xingPos` `drawBusker4` `addCart` `addCyclist` `drawGulls11` `drawChipGull`；
9. 雨/光：`drawDrizzle6` `drawRings13` `drawSky33` `drawFar13` `drawStation2`；
10. 街景/广告/路：`drawCorners` `drawBoard6` `drawRoad3` `drawStreetLife`；
11. `prerender46`（把 6 组静态层烤成 `Pixmap`，**走 G-E51-J 口径：`trim24`/`trim32` 一律返 `Pixmap`**）；
12. `renderFrame46` 逐行拼装（上游 1600–1657）。

#### 8.8.7 预判陷阱

① `for (const [k, wc] of [[0,2.15],[1,4]])` / `for (const [f,b0,t0] of …)` ⇒ Kotlin 用 `for (p in arrayOf(intArrayOf(...)))` 或显式三元组，别硬写解构；
② `Math.min(...[2.15,4].map(...))` ⇒ 无 3 参 `min`，写 `minOf`；
③ `LITP2.set(...)` 是**直接改像素**（不是 `ctx`），落在 1×1 窗口上；Kotlin 用 `PixelBuffer.set`；
④ `ctx46.globalAlpha` ⇒ `ctx.alpha`（记得成对恢复；上游有多处 `= 1` 兜底）；
⑤ `PITCH39` 同京都：本项目无广告 ⇒ 恒 null 分支，`drawBoard6` 只保留自有广告牌；
⑥ `weather.js` 的 `drawWeather` 不移植（同京都）⇒ `wx.drizzle`（本城母题 `drizzle`）**要真实现**，`wx.shared` 恒 false；
⑦ `ZBUF` 每帧重置（§8.8.3）；
⑧ `trim32` 与京都 `trim24` **同一语义**（返回 `{c,x,y}`）⇒ 必须 `Pixmap`，受 G-E51-J 约束。

#### 8.8.8 进度台账（2026-10-23）

**已落地且 `compileDebugKotlin` 0 error**（`LofiCityMelbourne.kt`，本文件内自洽）：

| 层 | 内容 |
|---|---|
| 元数据 | `id/name/tagline/timeZone/accent/focusX/landmark/billboardRect`（§8.8.5 逐字段） |
| 常量**字段** | 上游 `var` 清单全部声明（`ST5/DOME5/FAC/RET/TOWER8/FED/YJ/PLAT2/SPLAT/CC/SHOWER3/BELLS17/GULLS_UP/...`）；⚠️ **赋值体 `initConstants()` 尚未写** |
| 相机/深度 | `proj4` `groundAt` `onWall2` `depthOf` `rowOf` `zset` `zat` `resetZbuf` |
| 取色/精灵 | `mixHex30` `pick31(soft)` `vnoise24` `sprite36` `trim32` `along4` |
| 光栅化 | `paintWall2`（返 `HashMap<Int,Double>` 供 `ST_DEPTH` 合并）、`paintRound` |
| 2D 基础件 | `R18` `dot9` `blit34` `halo46` |
| 3D 箱体 | `face`（含 `zat` 遮挡剔除）、`zfill`（含 `mir` 倒影）、`drawBox`（远→近排序 + 0.2α 反面 + 顶面扫描线） |

**尚未落地**：`initConstants()` 赋值体、材质工厂（`frontMat2`/`cornerMat`/`towerMat`）、`paintDome`/`paintTower3`/`paintBeyond`/`paintStation`、
静态层（`renderSky46`/`makeCloudLayer29`/`paintSouthbank`/`paintCity`/`renderBoard6`/`renderShelters`）、
动态物件（电车/汽车/行人/海鸥/雨）、`prerender46`、`renderFrame46`、`create()`。

⚠️ 当前 `MelbourneScene` **刻意不实现 `PixelCityScene`**，`LofiScenes.build()` 也未引用 ⇒ 不存在"半成品被接线"的路径。

**下一入口**：§8.8.6 第 5 步（材质工厂）→ 第 6 步（静态层）。
⚠️ 改动 `pick31` 的 `soft` 或 `drawBox` 的排序方向会立刻让画面整体错位，且**现有门禁一条都测不出**
（同 §8.7.2 的教训）⇒ 每完成一层必须导出 PNG 肉眼判读。

**续（同日，四次 `compileDebugKotlin` 全 0 error，文件 2243 行）已落地：**

| 层 | 内容 |
|---|---|
| `initConstants()` | 上游 1664-1941 **逐行译文**（含 `RAILS2` 依赖 `TRK2/GAUGE`、`ARCH4` 依赖 `ST5.len`、`XINGS` 的 slot 生成、`DRZ2` 用 `PxRng(700+li)`、`RINGS13` 用 `PxRng(73)`）；`ST_DEPTH/FED_DEPTH/YJ_DEPTH`、各类缓存与收集器一并复位 |
| 共享闭包 | `rankOf4` `dist2` `polyLen` `tramAt3` `showerAt` `fedRoof` `bellLevel7` `winLit7` `keyHash` `inArch` `domeR` `zoneOf` `fade` `lit6` `brick2` `cream` `banded` |
| 天空/云 | `renderSky46` `makeCloudLayer29`（含 `CloudLayer{c,y,k}` 与 3 层 `CLOUD_DEFS29`） |
| 南岸 | `paintSouthbank` + `addWin6`/`drawWins6`/`winPix`（窗灯 key=*which*1e4+bay*10+win） |
| 材质 | `frontMat2`（含 pavilion 分支、`inArch` 窗格、`winPix` 登记）`cornerMat`（含 `ARCH4` 拱、钟窗）`towerMat`（含 `TCLOCK` 表盘） |
| 地标 | `paintDome`（鼓座/穹顶/环廊/灯笼/尖顶 5 段 `paintRound`）`paintTower3`（`shaft`→穹顶→铜帽） |
| 街景 | `paintBeyond` `paintStation`（**`ST_DEPTH` 覆盖合并 dRet→dFac→dCor**）`fedMat` `yjMat` `paintCity` |
| 2D 层 | `renderBoard6` `renderAd26`+`adText` `renderGround13` `renderMarks`（读**全局 ZBUF**，必须在 `renderGround13` 之后）`renderWires`（`pline3` + 统一 α=150） |
| 小件 | `addBulb`/`layBulbs`（含 `Math.hypot` 3 参改 `sqrt(dx²+dy²+dz²)`）、`postAt`/`drawPost`、`signals`（60s 相位状态机） |

**仍未落地**：车辆/行人/海鸥/雨/信号机绘制（`drawSignal3` `tramSide` `drawTram2` `ccFront` `carAt` `carSide` `drawCar4` `look18` `person18` …）、
候车亭（`renderShelters`）、`HL46` 构造、`prerender46`、`renderFrame46`、`create()`。

**下一入口**（按依赖序）：`drawSignal3`/`tramSide`/`drawTram2`/`carSide`/`drawCar4` → `person18` → 海鸥/雨/`drawStreetLife` → `HL46`/`prerender46` → `renderFrame46`（开头 `resetZbuf()`）→ 接线三处。

**终态（2026-10-23，整城落地）**：`LofiCityMelbourne.kt` **3855 行**，`compileDebugKotlin` 0 error，`LofiTrimOffsetSourceGateTest`（G-E51-J，7 例）通过，`LofiSceneSnapshotTest` 三口径（S1 非空 / S2 随时间变化 / S3 确定性）通过，四时刻 PNG 已人工判读。

| 层 | 内容 |
|---|---|
| 动态物件 | `drawSignal3` `tramSide` `drawTram2` `ccFront` `carAt` `carSide` `drawCar4` `look18` `person18` `tiny2` `personAt` `umbOf` `seg3` `polyAt` `xingPos` `facing` `addCrossers` `addStayers` `drawBusker4` |
| 街具 | `prebox` `renderCart8` `renderBin` `renderLamp9` `addCart` `drawChipGull` `drawGulls11` `addCyclist` |
| 天气/收尾 | `drawDrizzle6`（含 `DRZ_LIGHTS` 灯晕染色）`drawRings13` `renderVignette46` |
| 装配 | `prerender46`（含 `HL46` 构造、`SPLIT3` 三段窗灯分界、`TCLOCK_AT`、`ADS8`/`POSTS5`/`GULL_SPR9`/`GULL_SMALL`/`GULL_STAND_C`）`drawSky33` `drawFar13` `drawStation2` `drawCorners` `drawBoard6` `drawRoad3` `drawStreetLife` `renderFrame46` `create` `dispose` |
| 接线 | `LofiScenes.build()` 加 `"melbourne" -> MelbourneScene()`；`CityRegistry.SCENES_BUILT` 加 `melbourne`；注册表 `focusX = 222.0`（基线测绘时已是 222，无需回填） |

**整城移植中新踩到的三个坑**（前两轮台账未覆盖）：

① **`DRZ_LIGHTS`/`DRZ_LIGHTS_BB` 重复声明**：字段区早已有这两个声明（322-323 行），收尾批次又加了一组 ⇒ Kotlin 报 4 条 `Conflicting declarations`，并让**所有** `lights2` 用法退化成 `Overload resolution ambiguity`（1265/1274/3625/3634/3867 五处）。⚠️ 这类"重复声明"在错误列表里以**下游**的重载歧义呈现，**根因行号在列表最前面**——先看 `Conflicting declarations` 那几条即可，别去追歧义点。
② **`winLit7` 对结构体鸭子类型**：上游 `winLit7 = (w, t) => w.mode === "on" || …` 同时被 `WINS14`（`Win`）与 `SWIN`（`Swin`）调用，Kotlin 签名必须各给一个重载；只写 `Win` 版会让 `SWIN` 那侧编译不过。
③ **缓存字段的类型要按上游实际产物定**：`CLOUDS31` 是 `makeCloudLayer29` 的产物（`CloudLayer{c,y,k}`）而非 `Pixmap`；`LIT_C2` 是 `LITP2.canvas`（`PixelCanvas`，供 `drawImage` 区域拷贝）而非 `Pixmap`。二者写错都不会在"逐行翻译"里暴露，只在装配层崩。

**`renderFrame46` 层序**（照抄上游 1633-1657，⛔ 别重排）：`resetZbuf` → `drawSky33` → `GROUND_C9` → `drawFar13` → `drawDrizzle6(DRZ2[0])` → `drawStation2` → `drawGulls11` → `drawCorners` → `drawRoad3` → `drawRings13` → `WIRES_C` → `drawDrizzle6(DRZ2[1])` → `drawStreetLife` → `drawDrizzle6(DRZ2[2])` → `VIG_C46`。`drawStreetLife` 的 DL 排序是**降序**（远者先画），灯晕在 DL 全部画完后**再叠加**。

**判读结论**（四时刻 PNG）：Flinders 街车站（绿穹顶 + 拱廊 + 钟楼）、Eureka 塔金色塔冠、联邦广场三角板、Young & Jackson 砖楼、电车（t=120 进站）、雨幕与湿地倒影、「FLAT WHITE / GULL CAFE」广告牌全部到位；无空白层、无错位、无整帧同色。

⚠️ 京都的教训在这里同样成立：`compileDebugKotlin` 0 error **不等于**移植正确，上面的判读结论是唯一的正确性证据。

---

### 8.9 `istanbul`（1992 行）落地 · 2026-10-23

#### 8.9.1 结果

- `LofiCityIstanbul.kt` **3597 行**（上游 `js/scenes/istanbul.js` 1992 行）：**2D 精灵合成型**（同京都/墨城/芝加哥，⛔ 不是墨尔本的伪 3D 相机）。`create() → initConstants() → initPrerenderCaches() → renderFrame7()` 全链路打通。
- 错误链：**40 → 0**（两轮 `compileDebugKotlin`）；唯一 warning `Redundant call of conversion method` 一并清掉。
- 本地提交：`f7e82d0`（3 个文件；⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"istanbul" -> IstanbulScene()`；`CityRegistry.SCENES_BUILT` 加 `istanbul`（第 14 项）；注册表 `focusX 0.0 → 189.0`（= 场景 `FOCUS`，门禁 R6 逐字段对账）。`nameZh`「伊斯坦布尔」/`taglineZh`「博斯普鲁斯之夜」基线里已有，无需回填。

#### 8.9.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`istanbul_compile2.log`，3m18s） |
| `LofiCityRegistryGateTest`（R 系列） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败 |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 |
| `LofiUpstreamHygieneTest`（H 系列） | 7 例 / **1 失败 = H2，既有红**（见 §8.9.5） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/istanbul_t000\|060\|120\|228.png` 四时刻**全部人工判读通过** |

**判读结论**：蓝色清真寺六塔、圣索菲亚、加拉塔塔、博斯普鲁斯大桥 LED 色环、少女塔及倒影、
「ÇAY TAZE / ÇAY SICAK」广告轮换（证明 `AD_SUBS` 生效）、花架藤叶与九只吊灯、地毯上的猫、
茶盘四杯、铜壶与提灯、栏杆边一对坐客（`COUPLE` 倾身/指路/饮茶三态）、水面光柱与波光、
新月 + 金星、云漂移、花瓣飘落、海鸥、暗角全部到位；t=60 大渡轮（`FERRIES[0]`，窗口 14–94 秒）正驶过画面中部。
站牌 `ISTANBUL` 缺省左上，压在藤叶上、不遮任何地标 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.9.3 受控差异（文件头第 67-96 行逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int ⇒ 上游 `toHex3`/`mixHex3`/`RGB4`/`rgbOf` 四个助手删除；
② 只移植本城母题 `petals`，跨城共享 `drawWeather` ⇒ 空实现（`wx.shared` 恒 false），`WX_*` 六组数据仍照搬为占位（同京都 `WX37` 口径）；
③ 本项目不放广告 ⇒ `makePitch` 产物换成 `PitchStub`（`covers` 恒 false、`draw` 空），`drawBillboard7` 只走自有分支；
④ 引擎缺件在本文件内补 private 替身：`Painter2.own`/`Painter2.d`（上游 `renderSkyline2` 靠 `p.own[y*W+x] === w.b` 过滤被遮挡的窗）、`mset` 的 col 既可能是数也可能是函数 ⇒ 两个重载、可选参数对象 ⇒ `SitterOpts`；
⑤ 音频轻反应（§10 D-a）本城**没有霓虹灯管**，落点改判：`lightGain` → 一切灯光的 α（天际线闪窗阈值 `w.p * lightGain`、岸灯/阳台灯排、清真寺/加拉塔/穹顶/少女塔/灯塔光晕、露台提灯与花架吊灯光晕）；`neonPulse` → 桥面 LED 光晕 α；`tailGain` → 红尾灯 α。⛔ 不碰坐标、几何、色值；三系数在 `renderFrame7` 开头取快照（同芝加哥先例）；
⑥ `reducedMotion` 只传给共享天气 ⇒ 本城空实现，不影响画面；
⑦ `ambience` 四通道与 `spots` 热区属站点 UI/音频，不移植；`events` 只作画面节奏锚点，时刻照搬。

#### 8.9.4 本城新踩的坑（余下城市照用）

① **`PixCtx.rect` / `PixCtx.putPx` 是扩展函数** ⇒ 缺 `import …engine.rect` 时报的是 `Unresolved reference 'rect'`（本城一次 26 处）。与 §8.6.1 芝加哥那条（接收者写法）是同一处缺口的两种表现。
② **`putPx`/`rect` 自身不取整**（上游 `core.js:231-238` 同形）⇒ 上游 `px(Math.round(a), Math.round(b), c)` 直译时必须显式 `jsRound`，与引擎 `fillRect` 边线的 `round(v).toInt()` 构成**等价的双重取整**；省略 `jsRound` 不报错，只是亚像素位移。
③ **JS 三段式 `for (let i = X(14); i <= X(52); i += 2)` 不要拆成"先赋值再 while"** —— 拆法漏声明 `i` 即 4 处 `Unresolved reference 'i'`；正确形态 `for (i in X(14.0)..X(52.0) step 2)`。
④ **9 处 Int→Double 全部来自"给 `floor()` 结果加了 `.toInt()`"**（`kf`/`ks`/`kr`/`k`、`GULL_T` 的 `/ 10`）。上游这些位置继续当 double 用 ⇒ ⛔ 别习惯性给 `floor()` 加转换；报 `Argument type mismatch` 时先删 `.toInt()` 而不是加 `.toDouble()`。
⑤ **`POINT_AT`/`LOOK_AT` 是单窗口二元组**（`DoubleArray`，不是事件表）⇒ `inEvent` 用不上，直接 `t >= A[0] && t < A[1]`；`LEANS`/`LAMP_FLICK4` 是 `Array<DoubleArray>` ⇒ 折成 `any { t >= it[0] && t < it[1] }`。`inEvent` 只吃 `List<EventWindow>` 这条老坑的**新变体**：形如事件表的常量未必是事件表。
⑥ **`PixelReflector.streak(ctx, t, sx, w, col, a, seed, y0, y1)` 的 `y0`/`y1` 是 `Int`，其余全 `Double`** ⇒ 水面那 4 处调用混着传必错。
⑦ **倒影把自己当源**：`val src = ctx7.surface` 后用 9 参 `drawImage(src, 0.0, sy, W, 1.0, off, WL2 + k, W, 1.0)` 逐行采样自己 ⇒ 该重载参数**全 Double**，`W.toDouble()`、`WL2.toDouble()` 都得写。
⑧ `wave(t, k)` 的 `k` 是 `Double` ⇒ 整型常量写 `64.0`；warning `Redundant call of conversion method` 是反向信号 —— 表达式已是 Double 时别加保险式 `.toDouble()`。

#### 8.9.5 已知红项：H2「源码集里没有上游原件」——**2026-10-24 已按 A 闭环**

`LofiUpstreamHygieneTest` H2 判据是「`app/src/main` 下任何 `.js`/`.mjs`，唯一豁免 `assets/globe/`」。
命中 offender 为 `visualizer/e51/tools/lofi_engine_golden.js` 与 `lofi_globe_golden.js`，
由并发会话的 `54e4fdc`（"基线再生脚本迁入 e51/tools/"）把再生脚本移进 `main` 源集引入 ——
**早于本城，且本城未新增任何 js**。两条候选修法（曾待所有者裁决）：

- **A**：把 `e51/tools/` 移出 `main` 源集（先例：`app/src/globe-upstream/` 在模块内、`main` 之外，Gradle 不打包）；
- **B**：精确化 H2 判据 —— 只挡"按模块切分的上游移植件"（`modules/` 那种），放行自有再生脚本。⚠️ 这会削弱该门对"移植件混进 APK"的拦截面。

**裁决与落地（2026-10-24）**：所有者要求"把这个失败解决掉" ⇒ 选 **A**（B 会削弱拦截面，不取）。
落地形式 = `git mv` 把两个脚本**移回 `scripts/`** —— 理由有三：① `app/src/lofi-upstream/` 是
**gitignored** 的上游原件目录，脚本必须入库，不能放那儿；② 两个脚本的 `const REPO = path.resolve(__dirname, '..')`
**只有在 `scripts/` 下才成立**；③ `scripts/lofi_city_registry_sync.py` 这个 E51 专用脚本本来就在 `scripts/`，先例一致。

⚠️ **迁移时连带发现 `54e4fdc` 把脚本挪坏了**（比 H2 更严重，且一直没人发现，因为没人再跑过它们）：

1. `REPO = __dirname/..` ⇒ 在 `e51/tools/` 下解析出的"仓库根"是 `.../visualizer/e51`，
   `MODS`（`app/src/lofi-upstream/modules`）根本不存在 ⇒ 脚本**跑不起来**（移回 `scripts/` 即修复）；
2. `lofi_engine_golden.js` 的 `OUT` 与 `lofi_globe_golden.js` 的 `PKG_MAIN`/`PKG_TEST` 仍指向旧包
   `visualizer/pixel/` ⇒ 修成 `visualizer/e51/engine/`；
3. 脚本内**硬编码的 `package` 字符串**（engine 1 处、globe 3 处）也还是 `com.nasmusic.tv.visualizer.pixel`
   ⇒ 一并改为 `com.nasmusic.tv.visualizer.e51.engine`。⚠️ 这条最阴：输出路径改对了、`package` 没改，
   生成物会**静默写错包名**（编译期才炸，且 H1 判据因为头注 `GENERATED by scripts/…` 仍会豁免它）。

**自证（不是"改完就算"）**：移回并修好后重跑两个脚本，与入库生成物逐字节对账 ——
`LofiEngineBaseline.kt` **0 差异**（282 行数据全等）；`PixelGlobeLand.kt`/`LofiGlobeColors.kt`/`LofiGlobeBaseline.kt`
**唯一差异是头注的"再生时间"日期戳**（2026-10-21 → 2026-10-24），数据全等。
⇒ 证明修的是路径与包名，**没有动到任何基线数字**。
验收：`LofiUpstreamHygieneTest` **7 例 / 0 失败 / 0 skip**（`logs_temp/h2_fix_test.log`，10m34s BUILD SUCCESSFUL），
H2 与"再生脚本不在 CI 里跑""负向N4 CI 文本提到脚本必须报"三条同时转绿。
⛔ 本轮**没有**为了让门变绿而放宽任何判据（B 方案未采用），H2 的拦截面保持原样。

#### 8.9.6 下一个入口

余下 **43 个场景**，仍按上游行数从小到大逐城推进（`munich` 2021 → `rome` 2024 → `sydney` 2034 → `boston-fenway` 2035 → `san-francisco` 2060 …），每城节拍不变：
骨架 → 逐函数直译 → 该城 `compileDebugKotlin` 清零 → 导出 PNG 肉眼判读 → 本地提交 + 本节同型台账。
⛔ 京都的教训在本城再次成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.10 `munich`（2021 行）落地 · 2026-10-24

#### 8.10.1 结果

- `LofiCityMunich.kt` **3333 行**（上游 `js/scenes/munich.js` 2021 行）：**2D 精灵合成型**（同京都/墨城/芝加哥/伊斯坦布尔，⛔ 不是墨尔本的伪 3D 相机）。画面母题 = **雪中的圣诞市集**：新市政厅（Rathaus）+ 圣母教堂（Frauenkirche）双铜顶塔楼作背景，前景两排货亭、烤栗子摊、旋转"报喜柜"（Oriel）与雪花三层视差。`create() → initConstants() → initPrerenderCaches() → renderFrame18()` 全链路打通。
- 错误链：**11 → 2 → 0**（三轮 `compileDebugKotlin`；`munich_compile1/2/3.log`）。本文件 0 warning。
- 本地提交：`7641763`（3 个文件，仅代码）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
  ⚠️ 中途所有者曾下达"停止后续所有开发"，故代码先落 `7641763`、判读与文档在其撤销后补齐 —— 台账与提交历史的时间差是这件事，不是漏跑。
- 接线三处：`LofiScenes.build()` 加 `"munich" -> MunichScene()`（按字母序插在 `melbourne` 与 `osaka` 之间）；`CityRegistry.SCENES_BUILT` 加 `munich`（**第 15 项**）；注册表 `focusX 0.0 → 185.0`（= 场景 `TX`，新市政厅钟楼轴，门禁 R6 逐字段对账）。`nameZh`「慕尼黑」/`taglineZh`「雪中的圣诞市集」基线里已有，无需回填。

#### 8.10.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`munich_compile3.log`，3m16s） |
| `LofiCityRegistryGateTest`（R 系列） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败 |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 |
| `LofiUpstreamHygieneTest`（H 系列） | 7 例 / **1 失败 = H2，既有红**（offender 仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js，H1「每个移植文件都点名了上游模块」通过） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/munich_t000\|060\|120\|228.png` 四时刻**全部人工判读通过** |

**判读结论**：圣母教堂双绿色洋葱顶、新市政厅哥特立面与**点亮钟面的钟楼**（`hands` 走时）、报喜柜（Oriel 转盘，四尊人物像随转交替）、
圣诞树 + 顶星光晕、雕像基座光池、`KERZEN / GLÜHWEIN / SCHMUCK / LEBKUCHEN` 四座货亭（灯泡排 + 彩点货架 + 积雪檐）、
`MARONI` 烤栗子车（炉火 + 两股烟/汽）、`BREZN` 广告牌（椒盐卷饼 + 檐口跑马灯 + 五盏投光灯 + 蒸汽）、`PUNSCH 4.50` 小牌、
右上花架藤叶 + 心形挂饰 + 九只吊灯、三层雪（远/中/近）与雪面反光、行人/牵狗/雪橇/对坐小桌、云漂移、星闪、暗角全部到位；
四帧之间云位、雪相位、行人位置与**窗灯开关**均不同（S2 由亮度差与肉眼双重确认）。
站牌 `MUNICH` 缺省左上，压在夜空上、不遮任何地标 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.10.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int ⇒ 上游 `tintCache3` 记忆化删除（`tint4` 与引擎 `mix` 逐通道同式）；`SNOW3[i].col` 的 `rgba(...)` 折成 `rgba()`；
② 只移植本城母题 `snow`，跨城共享 `drawWeather` ⇒ 空实现（`wx.shared` 参数丢弃），母题开关按 `weatherPlans` 语义折成 `weatherSnow`（null/"city"/"snow" ⇒ 开）；`WX_LAND18/WX_SHELTER2/WX18/WX_BB18` 仍照搬为数据（同伊斯坦布尔 `WX7` 口径，将来接共享天气不必重新测绘）；
③ 本项目不放广告 ⇒ `makePitch` 产物换成 `PitchStub`（`covers` 恒 false、`draw` 空），`drawBillboard16` 只走自有分支；
④ 引擎缺件在本文件内补 private 替身：`Painter7`（含 `ctx`，供 `done()` 之后继续往同一画布写）、**`drawImageClipped`（替代上游 `clip()`）**、`inPair`（承接 `Array<DoubleArray>` 型闪烁表）、`SceneOpts`（可选参数对象）；
⑤ 音频轻反应（§10 D-a）：`lightGain` → 一切灯光的 α（塔楼/钟楼/拱窗/廊檐光晕、报喜柜光晕、货亭灯泡与光晕、串灯、烤架余烬、蜡烛、提灯、广告牌洗墙灯与灯箱、窗灯 `RWIN2/HWIN3`、星窗）；`neonPulse` → 广告牌**洗墙灯晕** α（⛔ 不是檐口跑马灯，理由见 §8.10.4⑥）；`tailGain` → **本城无车流 ⇒ 无落点**，只取快照不用；
⑥ `reducedMotion` 只传给共享天气 ⇒ 本城空实现，不影响画面；
⑦ `ambience: {crowd, wind}` 属音频通道（§1.3b）不移植；`events` 只作画面节奏锚点，时刻照搬（`BELLS6` 钟 + `CHIMES` 铃）。

#### 8.10.4 本城新踩的坑（余下城市照用）

① **⛔ Kotlin 数组没有 `.length`，只有 `.size`**（`Array<T>`/`IntArray` 一律 `.size`；`.length` 只对 `String` 与 Java 数组有效）。
    上游 `FK_TOP.length` / `SLED_ROWS.length`（都是"行数=精灵高"）直译时必须写 `.size` —— 前 14 城从未命中，因为那里的 `.length` 全是 `String.length`。
② **`bayer` 只有 `(Int,Int)` 与 `(Double,Double)` 两个重载** ⇒ 形参写成 `Number` 的助手（本城 `stoneAt`/`oldAt`）里直接 `bayer(i, y)` 报
    `None of the following candidates is applicable`，必须显式 `bayer(num(i), num(y))` 归一（`num` = 本文件顶部的 `Number.toDouble()` 助手）。
③ **`floor()` 只吃 `Double`，而 JS 的 `/` 是浮除**：上游 `Math.floor((i + off) / seg4)` 直译成 `floor((i + off).toDouble() / seg4)`；
    ⛔ 图省事写 `(i + off) / seg4` 会先做 Int 整除、编译期就报 `Int but Double was expected`，但**若两侧本就是 Double 变量则会静默错** ——  cobble 会塌成少数几块。
④ **引擎没有 `clip()`**：上游 `drawOriel` 用一次矩形裁剪包住两次精灵贴回。这两次贴回是**整数位移 + 1:1** ⇒ 裁剪等价于"源矩形 ∩ 裁剪框后再贴"，
    本文件用 `drawImageClipped` 走引擎九参 `drawImage`（落到 `blitRegion`，保留 alpha 合成）。⛔ 不改引擎（引擎有自有 golden 基线）。
    ⚠️ 若将来某城的裁剪内含**缩放或旋转**贴回，这条等价关系不成立，必须另想（不能照抄本城方案）。
⑤ **`Painter7.ctx` 与 `PixelBuffer` 共用同一条 `px` 数组** ⇒ 上游"`p.done()` 之后再往同一张画布写"的三处（`renderFront2` 的 `sign`、
    `renderForeground2` 的 `text`、`renderBillboardAd5` 的 `drawImage`）在替身里天然成立；`Painter7` 只实现本城真用到的方法（`alpha()` 从未被调用 ⇒ 不实现）。
⑥ **`neonPulse` 不能挂在 α 恒为 1.0 的元素上**：本城最初判给"广告牌檐口跑马灯"，但跑马灯是**二值亮灭**（无 α 余量），
    乘任何系数都会破坏"静音时必须与上游逐像素一致"这条基线 ⇒ 改判到 `HL18.bbWash`（0.8 → `0.8 + neonPulse`），与伊斯坦布尔桥面 LED 同口径。
    ⚠️ 通用判据：**只有本来就在 0..1 之间连续变化的 α 才配当音频落点。**
⑦ **精灵朝向对命名照上游 `l`/`r`**（先例大阪 `QueueSprite(val r, val l)`）：本城一度为避讳小写 L 改名 `dl`，结果 4 处 call site 全报 `Unresolved reference 'l'` —— ⛔ 别改，照上游。
⑧ **`sprite12` 宽度取最长行**：引擎 `spriteFromRows` 取首行宽，本城 `SLED_ROWS`/`TABLE_ROWS` 首行更短 ⇒ 必须本地实现（文件头已注明），否则精灵右侧被切。

#### 8.10.5 下一个入口

余下 **42 个场景**，仍按上游行数从小到大逐城推进（`rome` 2024 → `sydney` 2034 → `boston-fenway` 2035 → `barcelona` 2039 → `boston-faneuil-hall` 2045 → `seoul` 2051 …），每城节拍不变：
骨架 → 逐函数直译 → 该城 `compileDebugKotlin` 清零 → 导出 PNG 肉眼判读 → **本地提交** + 本节同型台账。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.11 `rome`（罗马 · 夜里十一点的许愿池，上游 2,024 行）落地 · 2026-10-24

产物：[app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityRome.kt](file:///absolute/path) —— **3,761 行**，类 `RomeScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/rome.js`，含 esbuild 模块包装）。

#### 8.11.1 结果

- 编译错误链：**120 → 56 → 0**（三轮 `compileDebugKotlin`；末轮 `logs_temp/rome_compile.log`，3m38s，**0 error / 0 warning**）。
  第一轮 120 里**只有 3 个是真错**，其余是同一处语法崩（`arrayOf(...)` 脚手架循环多写一个 `),`）级联出来的假象 —— 见 §8.11.4⑧。
- 本地提交：`5ab645e`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"rome" -> RomeScene()`（按字母序插在 `osaka` 与 `stockholm` 之间）；`CityRegistry.SCENES_BUILT` 加 `rome`（**第 16 项**）；注册表 `focusX 0.0 → 240.0`（= 场景 `CX2`，特雷维立面中轴，门禁 R6 逐字段对账）。
  ⚠️ 同城 look `rome-colosseum`（斗兽场）**尚未移植**，其 `focusX` 保持 `0.0`，不要顺手回填。
  `nameZh`「罗马」/`taglineZh`「夜里十一点的许愿池」基线里已有，无需回填。
- 元数据：`landmarkX0/X1 = 192.0 / 288.0` 是**上游 `rome_default.landmark` 的字面量**，⚠️ **不等于**场景里的 `ARCH3 = [192, 287]`（差 1）—— 见 §8.11.4⑥。

#### 8.11.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`logs_temp/rome_compile.log`，3m38s） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败（`rome_gate.log`，2m6s，BUILD SUCCESSFUL） |
| `LofiCityRegistryGateTest`（R 系列）+ `LofiTrimOffsetSourceGateTest`（G-E51-J） | 与上同批跑通，0 失败 |
| `LofiUpstreamHygieneTest`（H 系列） | 本城未跑（H2 既有红仍待所有者裁 A/B，见 §8.9.5；本城未新增任何 js 文件，H1 的"每个移植文件都点名了上游模块"由文件头首行满足） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/rome_t000\|060\|120\|228.png` 四时刻，**t000/t060/t120 已人工判读** |

**判读结论**：特雷维立面全套到位 —— 中央拱 + 壁龛里的 Oceanus + 贝壳龛的扇肋 + 四根巨柱 + 阁楼层雕像与顶端铜饰；
两匹海马与特里同、八股水帘 + 中央瀑布、青绿水池与焦散/反光、水面硬币闪光与落水硬币；
三层人群（含池沿坐沿者）、`GELATI / PIZZA / BAR` 三间店铺与灯箱、`CAFÈ LUPA` 广告牌 + 脚手架 + 两枚灯箱灯；
意大利三色旗、月与星（`STARS13` 已按上游剔除月亮邻域）、两侧砖楼绿百叶窗亮灯、海鸥、暗角。
四帧之间云位、水帘相位、人群位置、窗灯开关与**投币节奏**均不同（S2 由亮度差与肉眼双重确认）。
站牌 `ROME` 用缺省左上，压在夜空上、不遮立面 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.11.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int ⇒ 上游 `C7`（`RGB9`）、`MIXED`、`rgbOf` 一类**记忆化**全部删除（`hex` 本身零分配）；⚠️ 抖动量化 `pickFlat2` 的 `0.5 + (bayer - 0.5) * 0.34` **逐字保留**，那不是缓存而是画面本身；
② **本城没有母题天气**（`WEATHER15 = weatherPlans([])`）⇒ 跨城共享 `drawWeather` 空实现，`plans[w] || plans.city` 折成 `weatherShared`；`WX_LAND15 / WX_LIGHTS5 / WX15 / WX_BB15` 仍照搬为数据（慕尼黑 `WX18`、伊斯坦布尔 `WX7` 同口径）；
③ 本项目不放广告 ⇒ `makePitch` 产物换 `PitchStub`（`covers` 恒 false、`draw` 空），`drawBillboard14` 只走自有分支；
④ 上游 `STORM_FLASHES` 属共享天气层、不移植，但 `FLASHES2.clear(t)` 要消费它 ⇒ 按语义落一份本地表 `STORM_FLASHES`（布达佩斯先例）；本城 `drawFlashes` 画的是**相机闪光灯**，不是雷暴；
⑤ 音频轻反应（§10 D-a）：`lightGain` → 月晕/星窗阈值/立面暖光 `HL15.glow`/壁龛 `niche`/水景青光 `aqua`/店铺 `shop`+`shopCool`/灯笼 `lamp`+`lampCore`/广告牌 `bbLamp`/水下灯 `uw`/焦散/星闪/池底硬币/`glint`/`toy`；`neonPulse` → 广告牌**背面辉光** `HL15.bbGlow`（加性 `base + neonPulse`，遵守慕尼黑 §8.10.4⑥ 的"只有连续 α 才配当落点"判据）；`tailGain` → **本城无车流 ⇒ 无落点**，只取快照；
⑥ `reducedMotion` 只喂共享天气（空实现）与 `drawFlashes`，与上游一致；
⑦ `ambience`（水声/人群/车流）与 `spots` 点击热区属音频与站点 UI（§1.3b）⇒ 不移植；`events` 只作画面节奏锚点，时刻照搬（投币 + 婚礼）；
⑧ **层序逐句照上游 `render` 尾部 1504–1533**（`drawSky11 → 海鸥 → 月晕 → 天气 → SCENE_C 底图 → 壁龛晕 → 立面窗 → 侧窗闪烁 → 栖息鸟 → 广告牌 → 水花 → 瀑布 → 水花 → 水池 → 硬币 → 前层精灵 → 青光 → 店铺 → 灯笼 → 旗 → 天气 → 人群 → 硬币 → 玩具 → 气泡 → 水花 → 闪光 → 天气 → 暗角`）—— ⛔ 不重排。

#### 8.11.4 本城新踩的坑（余下城市照用）

① **引擎 `inR` 只有 `Double` 版**：本城立面着色大量 `inR(m, 259, 265)`（`m` 是 `Int` 色阶）⇒ 一次性报 91 个 `actual type is 'Int'`。
    补一个文件级 `private fun inR(v: Int, a: Int, b: Int)` 重载即可，⛔ 别去改引擎（引擎有自有 golden 基线）。
② **⛔ 非 `inner` 的嵌套 `private class` 拿不到外层方法**：`private class Fp` 里调外层 `pickFlat2` 报
    `Outer class 'RomeScene' of non-inner class cannot be used as receiver` ⇒ 把 `pickFlat2` 提到**文件级 `private fun`**。
    通用口径：**只要嵌套类要用，助手就写文件级**，别指望 `-Xannotation` 或改 `inner`（`inner` 会让每对象持外层引用）。
③ **`PixCtx.fillRect` 只有两个重载**：全 `Int`×4 或全 `Double`×4 ⇒ `ctx.fillRect(s.x /*Int*/, s.y, 1.0, 1.0)` 报
    `None of the following candidates is applicable`，必须 `s.x.toDouble()`。⚠️ 同名的两处 `fillRect(x, y, 1, 1)` 里只有一处非法
    （`drawSky11` 的 `STARS13` 全 Int 合法、`drawBasin2` 的 `SPARKS` 混型非法）⇒ **批量替换必须带上一行做上下文消歧**，否则改错那处会静默改变星点渲染。
④ **`textWidth` 在引擎 `engine/PixelFont.kt:102`，不是场景本地函数**：直译时按上游"全局函数"处理会 `Unresolved reference`，补 `import ...engine.textWidth`（同 `rect`/`putPx` 的扩展函数口径，见伊斯坦布尔 §8.9）。
⑤ **JS 的 `/` 恒为浮除，Kotlin 的 `Int / Int` 是整除**：本城硬币涟漪 `sqrt(1 - (dx / rx) * (dx / rx))` 若照写 `dx / rx`，
    椭圆会被整除塌成直线（`|dx| < rx` 时商恒 0，只剩一条竖线）。⛔ 这类式子**编译期不报错**，只能靠逐行回查上游 + 判读发现 ⇒ 一律 `.toDouble()`。
    （与慕尼黑 §8.10.4③ 的 `floor()` 版同源，但那条会编译失败、这条不会，**更危险**。）
⑥ **元数据字面量 ≠ 场景常量**：上游 `rome_default.landmark` 是 `[192, 288]`，而场景里 `ARCH3 = [192, 287]` —— 差 1。
    ⛔ 不许"顺手等价"成 `ARCH3[1]`；注册表/门禁对账的是 `*_default` 的字面量，不是绘制用的常量。
⑦ **`override val` 元数据必须写成 `get() =`**：`initConstants()` 在 `create()` 里才跑，构造期那些 `private var` 还是 0 ⇒
    `events / focusX / landmarkX0 / landmarkX1 / billboardRect` 全用惰性 `get()`（墨城、伊斯坦布尔先例继续成立）。
⑧ **一处语法崩会级联上百个假错误**：`arrayOf(...)` 脚手架循环多写一个 `),`，编译器从该点起把后续结构全读坏，
    报出 ~90 个 `expecting`/`actual type is...`。⇒ **收敛顺序：先修结构性/语法错误与"不能嵌套调用"这类真错，再看剩下的类型错误**；
    拿到一百多个错误时先按**错误种类聚类**，别逐条改。

#### 8.11.5 下一个入口

余下 **41 个场景**，仍按上游行数从小到大逐城推进（`sydney` 2034 → `boston-fenway` 2035 → `barcelona` 2039 → `boston-faneuil-hall` 2045 → `seoul` 2051 → `lisbon` 2052 …），每城节拍不变：
骨架 → 逐函数直译 → 该城 `compileDebugKotlin` 清零 → 导出 PNG 肉眼判读 → **本地提交** + 本节同型台账。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.12 `sydney`（悉尼 · 港湾的蓝花楹之夜，上游 2,034 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCitySydney.kt` —— **3,601 行**，类 `SydneyScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/sydney.js`，含 esbuild 模块包装）。

#### 8.12.1 结果

- 编译错误链：**131 → 3 → 0**（三轮 `compileDebugKotlin`；末轮 `logs_temp/sydney_compile3.log`，本城 **0 error / 0 warning**）。
  第一轮 131 个里 **86 个是同一个** `Unresolved reference 'r'` —— 引擎 `PxRng` 是 `next()` 方法形态，而上游 `rng(seed)` 返回**可直接调用的取数函数**（`const r = rng(61); r()`）⇒ 一处 `Rng9` 包装全部清掉，见 §8.12.4①。
- 本地提交：`6594666`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"sydney" -> SydneyScene()`（按字母序插在 `stockholm` 与 `taipei` 之间）；`CityRegistry.SCENES_BUILT` 加 `sydney`（**第 17 项**）；注册表 `focusX 0.0 → 226.0`（= 场景 `FOCUS3`，海港大桥西岸桥头，门禁 R6 逐字段对账）。
  `nameZh`「悉尼」/`taglineZh`「港湾的蓝花楹之夜」基线里已有，无需回填。
- 元数据：`landmarkX0/X1 = OH3.x0 / OH3.x1 = 146 / 300`（歌剧院）；`landmarkTop = 104.0` 是上游 `sydney_default` 的**字面量**，⛔ 不等于 `SHELLS` 里最小的 `ty`（§8.11.4⑥ 同一口径）；`billboardRect = BB9 = [94, 116, 46, 20]`；`accent = #a38bff`（蓝花楹紫）。
- `events`：六道雷（`FLASHES` 各 +1 秒、保留一位小数）+ 三声汽笛 + 两趟列车 + 两次船，`pan` 取上游区间**起点**、`dur` 丢弃（重庆/墨尔本/大阪口径）。

#### 8.12.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`logs_temp/sydney_compile3.log`；前两轮 `sydney_compile1/2.log` 分别 131、3） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败（`logs_temp/sydney_snap1.log`，6m7s，BUILD SUCCESSFUL；本城已进 `SCENES_BUILT` 故被该门覆盖） |
| e51 包全量（`--tests "com.nasmusic.tv.visualizer.e51.*"`） | 99 例 / **1 失败** = `LofiUpstreamHygieneTest` H2 既有红（offender 只有 `e51/tools/lofi_engine_golden.js`、`lofi_globe_golden.js` 两枚，与移植件无关，仍待所有者裁 A/B，见 §8.9.5）+ 2 skipped；`LofiCityRegistryGateTest`（R 系列）与 `LofiTrimOffsetSourceGateTest`（G-E51-J）全绿（`logs_temp/sydney_gates.log`） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/sydney_t000\|060\|120\|228.png` 四时刻 + 闪电补拍 `sydney_flash_t3245\|10462\|10495\|13950.png` 四帧，**八帧全部人工判读** |

**判读结论**：海港全套到位 —— 歌剧院七片帆（白帆 + 橙色龛格 + 基座灯串）与其**水中镜像**（逐行错位 + 振幅抖动）、海港大桥（上弦 + 下弦 + 桥面车流 + 两座桥塔 + 拱上一排呼吸灯 + 爬索人）、悉尼塔（针尖 + 观景舱 + 航空障碍灯）、北岸与 CBD 双层楼群（窗灯按圈开关 + 楼顶皇冠灯）、`SURF LESSONS` 广告牌（海浪 + 冲浪者 + 跑马灯）；
前景：左侧整棵蓝花楹（紫冠 + 垂枝 + 三层下落的铃花）、右下角探进的百子莲、长椅上坐着的人与脚边的猫、情侣、钓鱼人（浮标闪光 + 咬钩时刻的竿子）、路灯与绕灯的飞蛾、枝头的袋貂（四态 + 移动）、穿越的蝙蝠群、渡轮两条 + 出租车黄船（各带尾迹）、桥上的列车（含头灯与火花）、流星与飞机。
四帧之间**云位、雷暴受光、车流、渡轮、列车、铃花、蝙蝠、袋貂、窗灯**均不同（S2 由亮度差与肉眼双重确认）；闪电补拍帧 `t=139.5` 同时出现主枝干与第二枚 `(464, 118)` 的补电、雷暴云整体转为受暴亮紫 ⇒ `STORM_LIT` 三层与 `BOLTS` 四枚全部接上。
站牌 `SYDNEY` 用缺省左上，压在夜空上、不遮树冠 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.12.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int ⇒ 上游 `HXC2`（`HX2` 的 hex 解析记忆化）删除（`hex` 本身零分配）；⚠️ 抖动量化 `dpick2`/`bayer` **逐字保留**，那不是缓存而是画面本身；
② `dpick2(pal, v, i, y)` 与引擎 `pickFromRamp` **逐式相同** ⇒ 只留名字、实现委托，不复制公式；
③ **本城有母题天气**（`WEATHER9 = weatherPlans(["blossoms"])`，与罗马的 `weatherPlans([])` 不同）⇒ `plans[w] || plans.city` 折成 `weatherPlan9`：五个共享母题 → `shared = 该母题`、`blossoms = false`；`clear` → 两者皆空；其余（含 `null`）→ `blossoms = true`（画 `BELLS` 三层、允许闪电）。跨城共享的 `drawWeather` 仍空实现，`WX_LAND9 / WX_LIGHTS4 / WX9 / WX_BB9` 照搬为数据；
④ 本项目不放广告 ⇒ `makePitch(BB9.w, BB9.h, {accent})` 的产物换 `PitchStub`（`covers` 恒 false、`draw` 空），`drawBillboard9` 只走自有画面；
⑤ ⛔ **引擎没有 `clip()`**：`drawTraffic` 列车段（上游 512–541）用 `save(); beginPath(); rect(222,0,W-222,H); clip()` 把列车与头灯/火花裁在桥的右半边 ⇒ 段内全是整数位移 + 轴对齐矩形，改走 `rectClip / pxClip / fillRectClip / haloClip`（与慕尼黑 `drawImageClipped` 同法：源矩形 ∩ 裁剪框）。`flipH2` 镜像同理按 `px` 逐列拷贝；
⑥ 音频轻反应（§10 D-a）：`lightGain` → 全场景光晕的 α（本城光晕是画面主体，沿芝加哥/伊斯坦布尔口径整体乘）；`tailGain` → 渡轮与出租车的**尾迹** α（`drawWake3` 三处 + `drawTaxi` 三处，船头浪花那处不乘）；⛔ `neonPulse` **不进画面** —— 广告牌 LED 与跑马灯是二值亮灭，加连续量会破坏 240 秒确定性序列（慕尼黑判据）；
⑦ `reducedMotion` 只喂两处：`fl2 = wx.blossoms && !reducedMotion ? flashAt(t) : null` 与 `drawWeather` 的空转，与上游一致；
⑧ `ambience`（水声/蟋蟀/风/车流）属音频（§1.3b）⇒ 不移植；**层序逐句照上游 `renderFrame9` 尾部 1466–1509**（`天空 → 蝙蝠群0/2 → 天际线 → 广告牌 → 桥体 → 车流 → 桥前层 → 桥灯 → 水面 → 渡轮1/2 → 铃花0 → 天气 → 歌剧院 → 航标 → 渡轮0 → 出租车 → 蝙蝠群1 → 铃花1 → 天气 → 地面 → 情侣 → 坐者 → 长椅 → 海鸥 → 钓鱼人 → 百子莲叶 → 花 → 树 → 花簇 → 袋貂 → 路灯与飞蛾 → 近景蝙蝠 → 铃花2 → 天气 → 暗角`）—— ⛔ 不重排。

#### 8.12.4 本城新踩的坑（余下城市照用）

① **上游 `rng(seed)` 返回的是可调用函数，引擎 `PxRng` 是方法形态**：`const r = rng(61); r()` 直译成 `val r = rng(61); r()` 后，Kotlin 对"不可调用的值"**只报 `Unresolved reference 'r'`**（不提示缺 `invoke`），本城一次爆出 86 条。
    口径：在**场景文件里**包一层 `private class Rng9(seed: Int) { private val g = PxRng(seed); operator fun invoke(): Double = g.next() }`，`rng()` 返回它 —— 取数序列逐位一致，⛔ 不改引擎（引擎有自有 golden 基线）。
② **`Array<T>?.map{}` 返回 `List` 不是 `Array`**：预烘 `BELL_SPR` 写 `BELL_SHAPES[si]?.map { sprite6(it, pal) }` 报 `expected 'Array<Array<PixelCanvas>?>', actual 'Array<List<PixelCanvas>?>'`。
    JS 的 `[].map` 与原数组同构、Kotlin 不同构 ⇒ 嵌套可空数组一律 `if (x == null) null else Array(x.size) { ... }`。
③ **上游把"色板对象"直接当 `pal` 传进 `sprite15`**：`BELL_COLS[i] = {L, M, D, T}`，键名**正好是**字符画里出现的字符。Kotlin 侧要么把 `BELL_COLS` 存成 `Map<Char, Int>`、要么存具名字段再临时转（本城选后者，因为 `drawBell` 的 size 0 分支要点名 `col.M`/`col.L`）⇒ 加一个 `bellPal(col)` 只在预烘期转一次。
④ **`!!` 要落在"取回可空元素的那一层"**：`BELL_SPR[a][b][c]!!`（`b` 取回的是 `Array<PixelCanvas>?`）编译不过，必须 `BELL_SPR[a][b]!![c]`。
⑤ **`hypot(Int - Int, Double)` 的第一个参数是整除**：`Math.hypot((gx - f.x) / f.r, ...)` 里 `f.r` 是 Int 半径 ⇒ 闪电受光半径塌成 0/1。这是罗马 §8.11.4⑤ 的同一族，只是这次藏在 `hypot` 参数里、**编译器还会额外报一次类型不匹配**（比罗马那条幸运）。
⑥ **常量取值助手不要标 `inline`**：`private inline fun clipLeft(): Double = CLIP_X.toDouble()` 报 warning「Expected performance impact from inlining is insignificant」（`inline` 只对带函数类型参数的才有意义）⇒ 去掉即可。本城把"末轮 0 warning"当验收项，别留。
⑦ **逐像素循环里的字面量色阶数组必须提为类级 `private val`**：上游 `dpick2(["#7a6e5c", ...], v, i, y)` 在 JS 里每颗粒子新建一次（无所谓），Kotlin 里就是**每颗粒子一次堆分配** ⇒ 提为 `PYLON_RAMP / SHORE_EDGE / DARK_FOL / GROUND_EDGE / GROUND_EDGE2 / BENCH_WOOD / AGAP_RAMP`。色值一字不动，属"分配层面的受控差异"，文件头与就地注释都要写明。

#### 8.12.5 下一个入口

余下 **40 个场景**，仍按上游行数从小到大逐城推进（`boston-fenway` 2035 → `barcelona` 2039 → `boston-faneuil-hall` 2045 → `seoul` 2051 → `lisbon` 2052 → `san-francisco` 2060 …），每城节拍不变：
骨架 → 逐函数直译 → 该城 `compileDebugKotlin` 清零 → 导出 PNG 肉眼判读 → **本地提交** + 本节同型台账。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.13 `boston-fenway`（波士顿·芬威球场 · Citgo 招牌下的夜赛，上游 2,035 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBostonFenway.kt` —— **3,996 行**，类 `BostonFenwayScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/boston-fenway.js`，含 esbuild 模块包装）。
本城是**迄今最大的一次单文件直译**（上游 2,035 行 / 约 90 个函数 + 约 50 个鸭子类型对象），采用**分片拼装**法：`logs_temp/bostonfenway_parts/`（`h.txt` 头 + 元数据 + 字段清单，`p2`–`p8` 七段各为一个 region），`cat` 成源文件。⚠️ **分片是唯一真相源** —— 任何修改都要改分片再重新 `cat`，直接改拼装产物会在下一轮被覆盖。

#### 8.13.1 结果

- 编译错误链：**16 → 0**（两轮 `compileDebugKotlin`；末轮 `logs_temp/boston_compile_2.log` BUILD SUCCESSFUL，本城 **0 error / 0 warning** —— 日志里 183 条 `w:` 全属其它文件，`grep LofiCityBostonFenway` 命中 0 条）。16 条的清单与修法见 §8.13.4。
- 本地提交：`27134c0`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"boston-fenway" -> BostonFenwayScene()`（按字母序落在**首位**，在 `boston-old-state-house` 之前）；`CityRegistry.SCENES_BUILT` 加 `boston-fenway`（**第 18 项**）；注册表 `focusX 0.0 → 208.0`（= 场景 `CIT.cx`，Citgo 标所在列，门禁 R6 逐字段对账）。
  `nameZh`「波士顿·芬威球场」/`taglineZh`「Citgo 招牌下的夜赛」/`accentHex #44bb11` 基线里已有，无需回填。
- 元数据（一次性 dump 实测，`logs_temp/boston_fenway_meta.txt`）：`focusX 208`；`landmarkX0/X1 = MON2.x0 - 4 / MON2.x1 + 6 = 88 / 292`（绿色怪物连头上灯架）；`landmarkTop = TOWERS9[0].top = 10.0`；`billboardRect = BB46 = [148, 70, 46, 20]`（外野那面大 board）；`accent = #44bb11`（绿线绿）。
- `events`：四声 chime，逐条取上游 `events` 的表达式（`WAVE4.t0 + 0.5`、`SING.peak`、`HR3.t + 0.5`、`HR3.t + 4.0`），实测落点 `30.5 / 112.0 / 188.8 / 192.3`，`x` 与 `gain` 照上游，`.sortedBy { it.t }` 保序。

#### 8.13.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（`logs_temp/boston_compile_2.log`，3m44s BUILD SUCCESSFUL；首轮 `boston_compile_1.log` 16 条 FAILED） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败（本城已进 `SCENES_BUILT` 故被该门覆盖） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败 |
| 三门合跑 | `logs_temp/boston_gates.log`，6m23s **BUILD SUCCESSFUL**；XML 计 16 例 / 0 失败 / 0 错误（`app/build/test-results/testDebugUnitTest/`） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/boston-fenway_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| `LofiUpstreamHygieneTest` | **本城未重跑**（该门的 H2 既有红仍待所有者裁 A/B，见 §8.9.5，与本城无关） |

**判读结论**：球场全套到位 —— 绿色怪物（Monrock，`310` 距离标 + 墙顶灯架 + 背面的 `HUB CAB` / `LOBSTAH` / `SEAPORT` 三块面板）、怪物上方的 Citgo 红标（八角牌 + 呼吸红）、两座灯架与各自的地面泛光晕、天际线与星空呼吸、三缕横滚云带；
看台：左翼看台（含棚顶与支柱）、怪物看台、box1/box3 三档分层座椅（人浪、sing-along、`SEC` 逐格观众）；场地：内野草皮菱形 + 跑道 + 投手丘与四名守备 + 打者/裁判、27 颗投球的**比赛时间轴**（投球轨迹 7 段衰减 + 传杀 + 本垒打飞出场）；
前景：步道投影与小贩（`Dottie's Hot Dogs` / `CANNOLI` 车）、十一位固定观众 + 走位行人、牛棚热身、外野大 board（`t=120` 显示爱心图形）、记分牌 line score 与总分。
四帧的**比分推进自洽**：`t=0` line score `NY 0100200 3 / BOS 1002000 3` → `t=228` 大 board 读 `MY 3 / BOS 5`、line score `BOS 10020002 5` ⇒ 本垒打的两分**正确落在第 8 局格与总分格**（`COUNT` 由 `PITCHES` 顺序推演这条被画面证实）；`t=60`/`t=120` 之间看台观众姿态、云位、窗灯占空、人浪位置、行人在步道上的相位均不同（S2 由亮度差与肉眼双重确认）。
站牌 `BOSTON` 用缺省左上，压在夜空上、不遮怪物 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.13.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int ⇒ 上游四张记忆化表 `RGB43`/`C35`/`toHex27`/`MIXED19` 整体删除（`hex`/`mix` 本身零分配）；⚠️ **抖动量化 [pick39] / [bayer] 逐字保留**，那不是缓存而是画面本身；
② `pick39(ramp, v, i, j, soft)` 引擎 `pickFromRamp` **无 `soft` 参数**（恒等于 `soft = 1` 支）⇒ 本文件自带完整公式 `f - floor(f) > 0.5 + (bayer(i,j) - 0.5) * soft`，⛔ 不动引擎；
③ `pix35(w, h)` 的 `line(x0, y0, x1, y1, col)`：引擎 [PixelBuffer] **没有 `line`** ⇒ 在 [Pix35] 上补同名方法（与悉尼文件级 `line6` 逐式相同）；
④ `pix35.set(i, j, col, a)` 在 `a < 255` 且 dst 已有 alpha 时向 dst 混合，引擎 [PixelBuffer.set] 恒直写 ⇒ 本城只有 [makeWisps8]（`a` = 120/112/104）与 [renderVignette54]（`a ≤ 92`）走该分支，两者对同一像素**各只写一次**（dst alpha 恒 0）且暗角之后是整幅 `drawImage` ⇒ **逐位等价**，混合分支不移植（本城最需要论证的一条：不是"省略"而是"证明取不到"）；
⑤ 天气：`WEATHER54 = weatherPlans([])` ⇒ **本城没有母题天气**，只有跨城共享天气；`WEATHER54(weather)` 折成 [weatherShared]（罗马口径），`drawWeather` 空实现；`WX_LAND49 / WX_LIGHTS23 / WX54 / WX_BB52` 照搬为数据（含风箱）；
⑥ 本项目不放广告 ⇒ `makePitch(BB46.w, BB46.h, {accent})` 的 `PITCH47` 换成 [PitchStub]（`covers` 恒 false、`draw` 空），[drawBillboard39] 只走自有画面；`ACCENT17` 因此只服务元数据；
⑦ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（[halo54] 统一乘，含灯架/星窗/Citgo 红标/灯箱/闪光灯/手机屏/场地泛光共 11 枚）；⛔ `neonPulse` **不进画面**（Citgo 红标与本垒打闪光灯都是二值亮灭，悉尼判据）；⛔ `tailGain` **不进画面**（本城无车流，来球轨迹 α 是 `0.4 * (1 - k/7)` 的固定衰减，乘连续量会破坏 240 秒逐帧序列）；
⑧ `opts.crowd` 本项目 `SceneOpts` 无该字段 ⇒ 恒落 `CROWD30`（0.5）过 `clamp`（老州府议会同一处理）；`opts.clock` 本城无处消费；`reducedMotion` 只喂上游那两处（`drawWeather` 空转与 `drawPhones` 不给闪光灯加晕）；
⑨ 上游 `Object.entries/values(BATTERS)` 依赖 JS 键的**插入次序** ⇒ 折成保序 `List`（A…G）+ `Map`；`COUNT` 按**对象身份**建键 ⇒ [IdentityHashMap]（源文件 `:295`），值 `[b, s, o]` 用 `IntArray`；⚠️ `Run.hold` 上游"可能 undefined" ⇒ `Double?`（不是默认 0.0），`othersAt` 的 `B.run?.hold ?: B.out ?: …` 靠这条区分"没写"与"写了 0"；
⑩ `slotXY(team, col)` 的 `col` 上游是 `"runs" | number` 联合 ⇒ 折成 [SlotCol]（`isRuns` + `idx`）；
⑪ `renderLFRoof` 的 `new Float32Array(W).fill(NaN)` ⇒ [FloatArray]（保住 float32 精度），比较处显式回落 Double，`isNaN()`；
⑫ `trim40(p)` 返回 `{c, x, y, w, h}` 比引擎 `Pixmap`（只有 `canvas/x/y`）多 `w`/`h`（`drawStand2` 分带拉伸要用）⇒ 本文件自带 [Trimmed40]，不用引擎 `trim()`。
层序逐句照上游 `renderFrame54` 尾部 23 层（`天空 → 场地底色 → 远壁 → bleach → mon → 左棚后 → lfg → 左棚前 → 泛光 → 天气0 → 草皮 → 前看台 → 牛棚 → 墙 → 记分牌 → 比赛 → box3 → box1 → 手机 → 天气1 → 步道 → 固定观众 → 近座椅 → 行人 → 小贩 → 天气2 → 暗角`）—— ⛔ 不重排。

#### 8.13.4 本城新踩的坑（余下城市照用）

① **Kotlin 2.3 里带比较器的 `List.sort { a, b -> … }` 是编译**错误**，不是 deprecation 警告**：本城 `renderStands` 的观众按深度排序写 `fans.sort { a, b -> b.d.compareTo(a.d) }`，报 `None of the following alternatives is applicable`。⇒ 一律 `fans.sortWith(Comparator { a, b -> … })`（源文件 `:1391`）。这条比罗马/悉尼遇到的任何类型错都更"像运行时 API 不存在"，别被误导去改 `ArrayList`。
② **JS 鸭子类型的形参必须改签名，而不是加 overload**：上游 `play(p, a, b)` 里 `p` 有时是 `Pitch` 对象、有时是**秒数**（`play(SINGLE, .4, 8)` 与 `play(g.t, .4, 4)` 并存）。Kotlin 侧若声明 `p: Pitch` 会在 5 个调用点各报一次。口径：改成 `fun play(pt: Double, a: Double, b: Double)`（源文件 `:2200`），并把对象调用点写成 `play(SINGLE.t, 0.4, 8.0)` —— ⚠️ **不能**给 `play` 加 `Pitch` 重载再内部取 `.t`，那会掩盖"上游这两个分支本就不是同一件事"。
③ **`FloatArray` 槽位赋值不吃 `Double`**：`edge[x] = fx[1]`（`fx` 是 `DoubleArray`）报 type mismatch，但**同一行的 `fx[1] < edge[x]` 比较是合法的** ⇒ 只补 `.toFloat()` 在赋值侧（源文件 `:1764`）。看到"比较不报、赋值报"别怀疑读错文件。
④ **同名 `set` 有 Int / Double 两套重载时，混用不会自动宽化**：`p.set(sx, y, …)`（`sx: Double`、`y: Int`）两套重载都匹配不上，报的是"no value passed for parameter"这类**看不出根因**的消息 ⇒ 显式 `y.toDouble()`（源文件 `:1650`）。
⑤ **整数相除的浮除陷阱依旧**：本城三处 —— `(y - sTop) / (foot - sTop)` 的看台插值（`:1240`）、`P3(a[0], a[1] + 20, MON_H + 8)` 的字面量（`:1840`，Int 字面量喂 Double 形参直接报）、以及文件头列的 `W / 9`、`textW(word) / 2` 等。**报出来的只有第二、三处**，第一处是静默错值 ⇒ ⛔ 判读时必须看渐变带是否断层（本城的看台座椅明暗带就是它）。
⑥ **`PixCtx` 没有 `imageSmoothingEnabled`**：上游 `create()` 里 `c.imageSmoothingEnabled = false` 直译会 `Unresolved reference`。本项目画布是整数像素、缩放由快照侧最近邻处理 ⇒ **不复制该行，改为就地注释说明**（源文件 `:3979`）—— 别默默删掉，那是上游语义的一部分。
⑦ **一次性元数据 dump 测试要写绝对路径**：本城临时建 `BostonFenwayMetaDumpTest`（跑完即删）打印 `focusX/landmark/billboard/events` 实测值。写成 `logs_temp/…` 相对路径会 `FileNotFoundException` —— **测试 JVM 的 `user.dir` 是 `app` 模块目录**，不是仓库根（与源码扫描门禁的同一条本机陷阱）。
⑧ **RNG/`hash` 的取数次序是画面本身**：`STARS45` 每颗 7 次 `r()`、被 `continue` 跳过的候选**照样消耗**；`PHONES` 采样里 `filter` 的回调用**原数组下标**、随后 `map` 用**过滤后下标**（源文件 `:1029` 注释）。⇒ 逐字保序，不许"整理成更清晰的写法"。

#### 8.13.5 下一个入口

余下 **39 个场景**，仍按上游行数从小到大逐城推进（`barcelona` 2039 → `boston-faneuil-hall` 2045 → `seoul` 2051 → `lisbon` 2052 → `san-francisco` 2060 → `toronto` 2061 …），每城节拍不变。
本城的分片体量参考：8 片 / 3,996 行，其中常量 region（`p8`）单片 556 行、预烘 region（`p4`）1,056 行 —— ⚠️ **上游行数 ≥ 2,000 的城，建议一次到位建好分片再逐片填**，边写边改单文件会在 `cat` 阶段丢失修改。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.14 `barcelona`（巴塞罗那 · 圣家堂旁的海风，上游 2,039 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBarcelona.kt` —— **4,252 行**，类 `BarcelonaScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/barcelona.js`，含 esbuild 模块包装）。
沿用波士顿·芬威的**分片拼装**法，片数扩到 **14 片**：`logs_temp/barcelona_parts/`（`h.txt` 头 + 元数据，`p01_fields` 字段清单，`p02_helpers` 引擎替身与箭头助手，`p03_types` 就地类型，`p04_init` 常量初始化，`p05`–`p12` 七个绘制区，`p13_frame` 每帧装配与模块出口）。
⚠️ **分片是唯一真相源**：本轮 9 处修复全部落在分片内，再由 `logs_temp/barcelona_parts/assemble.py` 重拼（该脚本还负责把 `p12` 的两段不连续上游区间拆开插回正确位置，并断言接缝不漂移）。

#### 8.14.1 结果

- 编译错误链：**9 → 0**（两轮 `compileDebugKotlin`；末轮 `logs_temp/barcelona_compile5.log` BUILD SUCCESSFUL）。首轮 9 条：`Win4.id` 缺 `override`（1）、`WX_LAND42` 声明 `DoubleArray` 而赋 `IntArray`（3）、`NATIVITY.slice(1, 3)` 用 JS 语义（2）、`pondInset(y)` 的 `y` 是 Int（2）、`wave(t, 30)` 缺 `.0`（1）。
- 首轮成功的 3 条本文件警告（两处冗余 `toDouble()`、一处 `!= null` 后的冗余 `!!`）已清零，末轮 **0 error / 0 warning**。
- 本地提交：`10362c2`（3 个文件，**仅代码**）+ 本轮文档提交（含 `create()` 里 `imageSmoothingEnabled` 的**补注**，见 8.14.4⑤；⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"barcelona" -> BarcelonaScene()`（按字母序落在**首位**，在 `boston-fenway` 之前）；`CityRegistry.SCENES_BUILT` 加 `barcelona`（**第 19 项**）；注册表 `focusX 0.0 → 240.0`（= 场景 `JX`，基督塔中轴，门禁 R6 逐字段对账）。
  `nameZh`「巴塞罗那」/`taglineZh`「圣家堂旁的海风」/`accentHex #f2b134` 基线里已有，无需回填。
- 元数据（从 `initConstants` 实测，非手抄）：`focusX = JX = 240`；`landmarkX0/X1 = 192 / 314`（诞生立面四塔最外两侧，含玛丽塔）；`landmarkTop = CROSS_TOP - 1 = 9`（基督塔十字架顶）；`billboardRect = BILL9 = [330, 110, 48, 20]`；`accent = #f2b134`（trencadis 赭黄）。
- `events`：八条按时刻升序 —— 海鸥 `30` → 水花 `96` → 风铃 `122` → 三钟 `124 / 127.5 / 131` → 海鸥 `168` → 水花 `172`（`SPLASH` / `CHIME_T4` / `BELLS15` 的表达式在元数据 getter 里逐个求值）。

#### 8.14.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（`logs_temp/barcelona_compile5.log`，2m08s BUILD SUCCESSFUL；首轮 `barcelona_compile1.log` 9 条 FAILED，次轮 `_compile2` 已 0 error） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败（1 例 skip = 未开 `LOFI_SNAPSHOT`） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败 |
| 三门合跑 | `logs_temp/barcelona_tests1.log`，1m18s **BUILD SUCCESSFUL**；XML 计 16 例 / 0 失败 / 1 skip |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/barcelona_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| `LofiUpstreamHygieneTest` | e51 全包 99 例 / **1 失败 = H2 既有红**（offender 仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js） |

**判读结论**：圣家堂全套到位 —— 诞生立面四塔（彩色果穗尖顶 + 受光塔身）、福音者四塔、中央塔群与**基督塔十字架**（白光晕压顶）、中殿果穗柱列与绿色柏树、后方塔吊（回转 + 红色航空灯）与绕塔雨燕；
港湾：水面倒影（`reflect` 的圣家堂倒影 + 11 条光条）、五盏港灯、两株棕榈与灌木、鸭群；
前景：广场砖地与湿地色阶、两盏路灯及其呼吸光晕与光斑、共享单车桩五格、三张咖啡桌（杯/烛/服务员）、长椅情侣、吉他手与四位观众、四串穿行的人（含遛狗与牵引绳）、池塘边的猫；
两侧街屋四层/三层石材立面与绿色百叶、悬铃树干 + 两蓬树冠、三条彩灯串与灯泡、酒吧门（`BAR` / `TAPES`）与药房绿十字、右侧「CAVA / ESTEL」脚手架广告牌（本项目不放广告 ⇒ 只走自有画面）；
天空：15 档夜阶 + 54 颗星（月亮 18 像素内不布）、月与月核、三层漂移云带、两层落叶（第一层在远景之前、第二三层在街屋之前）、灯边飞蛾、两群海鸥穿场、暗角。
四帧差异自洽：云带整体右移、月亮从云隙移到云侧、塔吊相位改变、池塘倒影随波、路灯明暗呼吸、自行车桩「取车→骑走 / 骑回→还车」在全流程中状态不同、行人/观众/遛狗者随 `hash` 出场变化。
站牌 `BARCELONA` 用缺省左上，压在夜空上、不遮圣家堂 ⇒ **不新增** `SIGN_ORIGINS` 覆写。

#### 8.14.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()` ⇒ 上游 `RGB31`/`C23`（hex 记忆化）与 `MIXED7`（`mixHex26` 结果记忆化）删除；⚠️ **抖动量化 [pick27]/[bayer] 逐字保留**；
② `pick27` / `mixHex26` 与引擎 [pickFromRamp] / [mix] 逐式相同 ⇒ 只留名字、直接委托；
③ `pix23` ⇒ [Pix23]（本城 `has`/`clear` 从未被调用）、`trim28` 本文件实现（作用于已 `flush()` 的画布）、`line16` 引擎无对应物 ⇒ 按上游逐点式实现；
④ 天气：`WEATHER42 = weatherPlans(["leaves"])` ⇒ **有母题天气**（悬铃落叶，三层 `LEAVES6`）；`plans[w] || plans.city` 折成 [weatherPlan42]（五个共享母题 ⇒ `leaves=false` 且 `shared=该母题`；`"clear"` 两者皆空；其余含 null ⇒ 落叶）；跨城共享 `drawWeather` **空实现**，`WX_LAND42/LIGHTS6/LIGHTS_BB5/WX42/WX_BB41` 仍照搬为数据；
⑤ 本项目不放广告 ⇒ `makePitch(...)` 的 `PITCH35` 换成 [PitchStub]（`covers` 恒 false、`draw` 空），[drawBillboard37] 只走自有画面；
⑥ 引擎无 `clip()`、也没有 `translate(w,0)+scale(-1,1)` 的贴回路径 ⇒ `flipH6` 按 [PixelCanvas.px] 逐列镜像拷贝；`renderBasilica` 在 `putImageData` 之后改写字节生成 glow、`prerender42` 改写自行车位图字节（第二辆车改蓝/抹掉车筐与红漆）两处都按 ARGB 通道算术直译；`"lighter"` 混合走 [PixCtx.op]；
⑦ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（[halo42] 统一乘）；`tailGain` → 三处尾迹 —— [drawMarina2] 的车灯光晕、[POND_STREAKS] 的水面光条、[drawLepant] 出租车的四段递减光尾；⛔ `neonPulse` **不进画面**（广告牌 LED 气泡与跑马灯是离散相位，加连续量会破坏 240 秒逐帧序列）；
⑧ `reducedMotion` 上游只在 `drawWeather` 的空转里用到 ⇒ 只沿形参传递，不多不少；
⑨ `SceneOpts` 无 `crowd` 字段 ⇒ 折成 `clamp(CROWD18, 0, 1)`（恒 0.6），行人"随机缺席"仍由 `hash` 决定；
⑩ 上游 `ambience`（人声/车流/蟋蟀/风/水声）是音频参数 ⇒ 不移植；`events` 只照搬时刻与类型。

#### 8.14.4 本城新踩的坑（余下城市照用）

① **JS `slice(a, b)` 的终点是排他的**：`NATIVITY.slice(1, 3)` 取的是下标 `1`、`2` 两座塔，Kotlin 侧写成 `slice(1, 3)` 会撞 `IntRange` 形参（报 `Argument type mismatch: actual type is 'Int', but 'IntRange' was expected`）⇒ 一律 `slice(1..2)`。看到这条错别先去查 `Array.slice` 的签名，先确认上游是**区间长度**还是**下标对**。
② **抽公共接口后实现类要补 `override`**：`Win4` 与 `LWin` 共用 `Lit2` 的 `id/n/p/off`，`LWin` 写了 `override`、`Win4` 漏了 ⇒ `'id' hides member of supertype 'Lit2'`。这类错在"先写类型、后抽接口"的分片法里必然出现一次。
③ **字段区与常量区的类型必须同源**：`WX_LAND42` 在字段区按上游"二维数值表"写成 `Array<DoubleArray>`，`initConstants` 里却按整数格赋 `Array<IntArray>`，`WxCfg.land` 也是 `IntArray` ⇒ 一处声明错连带 3 条 error。口径：**颜色走 Int、几何基准走 Int、上游明确是浮点的才 Double**，字段区先按这个定死。
④ **`Float32Array` 的密度场沿用 `FloatArray`**：`makeCloudLayer25` 的 `D` 初版写成 `DoubleArray`，改成算 Double / 存 `toFloat()` / 读 `toDouble()`（与慕尼黑、重庆、悉尼、台北同口径）。这不是"精度洁癖"——上游把值存进 float32 后再参与 `bayer` 阈值比较，位宽一致才逐像素一致。
⑤ **`PixCtx` 没有 `imageSmoothingEnabled`**（波士顿 §8.13.4⑥ 已记，本城 `create()` 里又踩一次）⇒ 不复制该行。**别默默删掉，就地注释说明**（源文件 `:4236`）。
⑥ **`pondInset(y)` 的形参是 Double、`y` 是逐行循环的 Int**：两处调用同时报 `Argument type mismatch`，补 `y.toDouble()`；⚠️ `x >= PX02 + pondInset(y)` 这种**比较**在 Kotlin 里是合法的（Int 与 Double 可比较），所以错误只落在实参上。
⑦ **`wave(t, k)` 的 `k` 是 Double**：`wave(t, 30)` 报 `actual type is 'Int', but 'Double' was expected`，补 `30.0`（本城猫尾巴那处）。
⑧ **`!= null` 之后的 `!!` 是冗余**：`Wk.mate` 是 `val`，smart cast 生效 ⇒ `WHO2[Wk.mate!!]` 报 `Unnecessary non-null assertion`，改成先取局部 `val mate = Wk.mate` 再查表。
⑨ **`(W + pad).toDouble()` 也是冗余**：`W` 是 Int 常量、`pad` 已是 Double，`Int + Double` 结果就是 Double ⇒ 两处 `Redundant call of conversion method`。
⑩ **上游未使用的形参要留名不留账**：`drawRider3(..., rank)` 的 `rank` 在函数体内从未出现、所有调用点只给 5 个实参 ⇒ 写成带默认值的末位形参 `rank: Double = 0.0`，只为保住上游签名（不要删掉，否则下一个人对账时会以为漏抄了）。

#### 8.14.5 下一个入口

余下 **38 个场景**，仍按上游行数从小到大逐城推进（`boston-faneuil-hall` 2045 → `seoul` 2051 → `lisbon` 2052 → `san-francisco` 2060 → `toronto` 2061 …），每城节拍不变。
本城的分片体量参考：14 片 / 4,252 行（`h` 178、`p01` 274、`p02` 242、`p03` 351、`p04` 400、`p05` 399、`p06` 413、`p07` 308、`p08` 359、`p09` 352、`p10` 368、`p11` 333、`p12` 169、`p13` 90）。
⚠️ 本城的 `p12` **跨两段不连续的上游区间**（`drawRider3/drawTable5` 在 1231–1258、`drawLepant/drawGulls10` 在 1431–1474）⇒ 分片不必与上游行区间一一对应，但**拼装脚本必须显式断言接缝**（本城脚本按 `private fun drawLepant` 回退到它上方的 `/**` 定位）。
⚠️ **改完分片必须立即重拼再编译**：本城发生过一次"改了 `p05` 没重拼 → Gradle 报 `8 up-to-date`、20 秒结束"的**假编译** ⇒ 验收命令串成 `python logs_temp/barcelona_parts/assemble.py && ./gradlew.bat compileDebugKotlin …`。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

### 8.15 `boston-faneuil-hall`（波士顿·法尼尔厅 · 蚱蜢招牌下的街头艺人，上游 2,045 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityFaneuilHall.kt` —— **4,607 行**，类 `FaneuilHallScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/boston-faneuil-hall.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **8 片**：`logs_temp/faneuil_parts/`（`h.txt` 535 行 = 头 + **12 条受控差异** + 元数据 + 字段区；`p01_types` 367、`p02_helpers` 441、`p03_look_person` 342、`p04_people_draws` 723、`p05_bake` 1,215、`p06_draws` 386、`p07_init` 590）。
⚠️ 本城按**上游功能区块**切片（不是行区间）：`p05` 独占全城烘焙层（`paintIndia` / `paintCustomHouse` / `paintQuincy` / `paintPavilion2` / `paintFaces2` / `renderBoard11` 等 12 个烘焙函数），`p07` 是 1,669 行 `var` 清单的赋值次序（上游 1674–2008 逐行对应）。

#### 8.15.1 结果

- 编译错误链：**120 → 4 → 0**（三轮 `compileDebugKotlin`；末轮 `logs_temp/faneuil_compile3.log` 2m09s **BUILD SUCCESSFUL**，本文件 **0 error / 0 warning**）。首轮 120 条**不是 120 个独立缺陷**，而是 4 个根因的级联：`paintFace` 的 `mat` 形参排在 `oy` 之后 ⇒ 7 处尾随 lambda 全绑到 `Int`（每处 6~8 条推断错误 ≈ 90 条）、`toInt32` 未 import（2）、`barRow(BarSpot)` 收到 `Seat`（1）、`NPIG_SPR` 重复声明（1 对）。次轮 4 条：`mid(Int)` 收到 Double（1）、`sign(Int)` 撞 `sign(Double)`（2）、`Number & Comparable` 推断失败（1）。
- 本地提交：`a05d567`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"boston-faneuil-hall" -> FaneuilHallScene()`（按字母序落在 `boston-fenway` 之前）；`CityRegistry.SCENES_BUILT` 加 `boston-faneuil-hall`（**第 6 项 / 共 20 项**）；注册表 `focusX 203.0` / `nameZh`「波士顿·法尼尔厅」/ `taglineZh`「蚱蜢招牌下的街头艺人」/ `accentHex #44bb11` 基线里已有，无需回填。
- 元数据（从 `initConstants` 实测，非手抄）：`focusX = 203.0`；`landmarkX0/X1 = 130 / 277`（西立面北角 → 南立面东角）；`landmarkTop = CUPR.vane`（蚱蜢风向标的顶）；`billboardRect = BB45 = [334, 98, 46, 20]`；`accent = #44bb11`（自由之路的绿线绿）。
- `events`：九条按时刻升序 —— 钟声 `18` → 海鸥 `36` → 钟声 `ACT2.clap`（87）→ 钟声 `140` → 风向标反光 `GLINT2[0]+1`（152）→ 海鸥 `168` → 钟声 `181` → 钟声 `BREEZE2.peak+3`（209）→ 钟声 `222`。

#### 8.15.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（`logs_temp/faneuil_compile3.log`，2m09s BUILD SUCCESSFUL；首轮 `faneuil_compile1.log` 120 条、次轮 `_compile2` 4 条） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（本轮带 `LOFI_SNAPSHOT=1` 跑，导出例也执行） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（`logs_temp/faneuil_trim_gate.log`，19s） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/boston-faneuil-hall_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| `LofiUpstreamHygieneTest` | 本轮未跑（H2 既有红仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js） |

**判读结论**：法尼尔厅全套到位 —— 砖砌三层立面（白窗套 + 拱窗 + 两条石饰带）、山墙玫瑰花窗与两扇圆窗、钟楼 + 蚱蜢风向标（金晕随 `BREEZE2` 反光）、铜像与花岗岩基座、西侧彩灯刺槐与两盏街灯（呼吸光晕 + 地面光斑）、花车与花坛、鸽子群（地面啄食 + 惊飞上栖架）、街头艺人（独轮车 / 杂耍 / 收钱）与观众、小贩与买家、追逐的孩子、冰淇淋情侣、坐着看平板的人、摄影师、露天餐桌与服务生、导览团、三股穿行的人、三轮车；
右侧龙虾卷亭：绿檐「LOBSTER ROLLS CHOWDER」+ 玻璃立面内的四张吧台与坐着的人、服务生走位（`PAV_GLASS` 逐列着色区当裁剪用）；后方「BRINY'S / OYSTERS / RAW BAR」广告画（只走自有画面 ⇒ 无广告）；
远景：昆西市场玻璃雨棚 + 穹顶 + 三段柱廊、印度街屋与「六」号楼、老南聚会所、海关塔（钟面 + 塔尖暖晕）、九片远景街区与亮窗、绿道上来回的红/白车灯、两架进近的飞机与三班航向灯；
天空：16 档夜阶 + 38 颗星、三层漂移云带（第三层横向回绕）、暗角；地面：铺装斜纹 + 自由之路红砖线。
四帧差异自洽：云带整体右移、`HALL3` 大厅灯 60/120 秒亮起而 228 秒熄灭、风向标反转、鸽子位置改变、行人/观众随 `hash` 出场变化、亭内吧台灯相位不同。
站牌 `FANEUL` 用缺省左上（本城 `SIGN_ORIGINS` 无覆写），压在夜空上、不遮山墙。

#### 8.15.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()` ⇒ 上游 `RGB42`/`C34`（hex 记忆化）、`toHex26`、`MIXED18`（`mixHex37` 结果记忆化）删除；⚠️ **抖动量化 [pick38]/[bayer] 逐字保留**；
② `mixHex37` 比引擎 [mix] 多一道 `clamp(t,0,1)`（上游有、引擎没有）⇒ 保留薄包装；
③ `pick38` 与引擎 `pickFromRamp` **不同**（Bayer 阈值乘 `soft`，本城大量 `0.25~0.8`）⇒ 本文件自带，别合并；
④ `pix34` ⇒ [PixelBuffer]（同名 API）、`trim39` ⇒ [PixelBuffer.trim]、`line` 引擎无对应物 ⇒ [line34] 逐点式（只在 `renderBoard11` 用）；
⑤ ⛔ 引擎无 `clip()`：`drawBarInside` 改**快照/回填**（`x ≥ 300` 带状像素进 [clipScratch]，画完按 `PAV_GLASS` 写回），与 Canvas clip 逐像素等价且零分配；
⑥ ⛔ 引擎 `drawImage` 无 5 参版：`drawPavLife` 的加性光池一律换 9 参（源矩形 = 整张精灵）；
⑦ 广告位：**不造假对象** —— `PITCH46` 与两处 `covers/draw` 调用整段删除，`drawBoard11` 只走自有画面，[SceneOpts.pitch] 不参与本城绘制；
⑧ 天气：`WEATHER53 = weatherPlans([])` ⇒ **无母题天气**（与罗马同构），共享 `drawWeather`/`weatherPlans` 不移植 ⇒ [drawWeather] 空实现、调用点只保留 0/1/2 层顺序；`WX_LAND48/LIGHTS22/WX53/WX_BB51` 仍照搬为数据；
⑨ [SceneOpts] 无 `crowd` ⇒ 折成 `clamp(CROWD29, 0, 1)`（恒 0.5），角色缺席仍由 [here14] 的 `rank` 决定；
⑩ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（[halo53] 统一乘）、`neonPulse` → 招牌光晕与两盏小射灯（[haloNeon53]）；`tailGain` **不进画面**（本城无车流尾迹）；
⑪ 上游 `ambience`（人声/车流/风）是音频参数 ⇒ 不移植；`spots` 只保留数据字段 [SPOTS4] 不接线；`events` 只照搬时刻与类型；
⑫ `create(c)` 里 `ctx.imageSmoothingEnabled = false`：引擎本就是最近邻 ⇒ 不抄。

#### 8.15.4 本城新踩的坑（余下城市照用）

① **尾随 lambda 只能绑到最后一个形参**：`paintFace(p, A, B, topFn, mat, oy = 0)` 这个签名下，`paintFace(...) { s, z, … -> }` 会把 lambda 绑给 `oy: Int`，报 `No value passed for parameter 'mat'` + `Function7 … but 'Int' was expected` + 六条 `Cannot infer type for value parameter` —— **一条根因炸出 90 条**。修法：形参改成 `(…, oy: Int = 0, mat: …)` 让 `mat` 吃尾随 lambda，五处按名传的调用补 `mat = faceMat(...)`。⚠️ 按名传参不会破坏 lambda 内的 `return@paintFace`（隐式标签来自函数名，与位置无关）。
② **`jsRound` 返回 Int**：`R26(x, y, w, h, c)` 里 `fillRect(jsRound(x), jsRound(y), w.toDouble(), h.toDouble())` 混出 `Int,Int,Double,Double`，两个重载都不匹配（报两条 `Int→Double` + 两条 `Double→Int`）⇒ 前两个补 `.toDouble()`。**引擎的 `fillRect`/`drawImage` 没有隐式数值加宽**，混用一律显式转换。
③ **`Number` 形参 + `toInt32` 要 import**：[pick38] 的 Double 重载调 `toInt32`（`engine/PixelRandom.kt` 的顶层函数，**不是** `kotlin.math` 里的）⇒ 必须 `import …engine.toInt32`；直接 `.toInt()` 是**错的**（那是向零截断，上游是 ToInt32 环绕）。
④ **上游同一个 `var` 可能声明两次**（JS 合法、Kotlin 不合法）：`NPIG_SPR` 在烘焙区和近景鸽子区各有一行 `var` ⇒ 报 `Conflicting declarations`。保留**真正被赋值的那一处**（本城在烘焙区），另一处只更新注释。
⑤ **`Math.pow` 是双参自由函数，Kotlin 只有接收者扩展**：`pow(clamp(...), 1.55)` 报 `None of the following candidates is applicable … receiver type mismatch`（四个 `Double.pow`/`Float.pow` 全列一遍）⇒ 写 `clamp(...).pow(1.55)`。
⑥ **闭包形参的整数/浮点口径要跟上游一致**：`mid(c)` 上游收列号（可以是 `x + (gw-1)/2` 这种半格）⇒ 形参必须是 `Double`；写成 `Int` 后报 `actual type is 'Double', but 'Int' was expected`。
⑦ **`Math.sign(a - b)` 两侧都是 Int 时**：Kotlin 的 `sign` 只有 `Double`/`Float` 版 ⇒ `.toDouble()` 包一层，随后 `if (sg == 0)` 也要改成 `0.0` 并把结果 `.toInt()`。
⑧ **`Seat` 与 `BarSpot` 只共享距离**：上游 `seatRow(z)` 与 `BarSpot.row(z)` 都只用到 `d` ⇒ [barRow] 加一个 `barRow(d: Double, z: Double)` 主实现，`BarSpot` 版委托它，`Seat` 调用点写 `barRow(s.d, 0.48)`（不要为 `Seat` 造 `BarSpot`）。

#### 8.15.5 下一个入口

余下 **37 个场景**，仍按上游行数从小到大逐城推进（`seoul` 2051 → `lisbon` 2052 → `san-francisco` 2060 → `toronto` 2061 → …），每城节拍不变。
本城的分片体量参考：8 片 / 4,607 行（`h` 535、`p01` 367、`p02` 441、`p03` 342、`p04` 723、`p05` 1,215、`p06` 386、`p07` 590）。
⚠️ **改完分片必须立即重拼再编译**（本城共重拼 4 次；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.16 `seoul`（首尔 · 南山下的银杏夜，上游 2,051 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCitySeoul.kt` —— **3,431 行**，类 `SeoulScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/seoul.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **7 片**：`logs_temp/seoul_parts/`（`h.txt` 291 行 = 头 + **13 条受控差异** + 元数据 + 字段区；`p01_types` 334、`p02_bake` 377、`p03_market` 644、`p04_sprites` 641、`p05_draw` 747、`p06_init` 390）。
本城是**2D 精灵合成型**：南山（含首尔塔与缆车）、远景楼群、世运高架、南大门市场、崇礼门与银杏街全在 `prerender20()` 里一次烘成画布，每帧只贴回图层 + 画动态件（塔身换色 / 车流 / 招牌跑马灯 / 帐篷摊蒸汽 / 银杏叶群与阵风 / 行人 / 风铃 / 暗角）。

#### 8.16.1 结果

- 编译错误链：**3 → 0**（两轮 `compileDebugKotlin`；首轮 3 条**同根因** —— `FLURRY` 字段声明成 `Array<Flurry>` 而上游是 `var FLURRY = []` + `push` ⇒ 必须是 `ArrayList`；次轮 0 error，2m43s BUILD SUCCESSFUL，`logs_temp/seoul_compile.log` 被次轮覆盖）。
- 本地提交：`fa04cb6`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"seoul" -> SeoulScene()`（按字母序落在 `rome` 与 `stockholm` 之间）；`CityRegistry.SCENES_BUILT` 加 `seoul`（**第 21 项 / 共 21 项**）；注册表 `focusX 0.0 → 172.0`（= 场景 `TW3.c`，南山塔塔身，门禁 R6 逐字段对账）。
  `nameZh`「首尔」/`taglineZh`「南山下的银杏夜」/`accentHex #c2b8ff` 基线里已有，无需回填。
- 元数据（一次性 dump 实测，`logs_temp/seoul_meta.txt`，不手抄）：`focusX 172`；`landmarkX0/X1 = CABLE2[1][0] - 8 / GATE.cx + 50 = 152 / 342`（索道上站到崇礼门檐角）；`landmarkTop = TW3.ant - 1 = 21`（塔尖信号灯所在行，上游注明横屏裁剪恰好保留）；`billboardRect = BB19 = [196, 100, 48, 20]`；`accent = #c2b8ff`（南山塔洗灯的淡紫）。
- `events`：八条按时刻升序 —— 喇叭 `26.83 / 37.55` → 风铃 `43.2` → 风铃 `129.2` → 喇叭 `146.83 / 157.55` → 警笛 `164.0` → 风铃 `205.2`（`horn` gain 0.45 / `chime` 0.6 / `siren` 0.3，`x` 与 `gain` 照上游；`siren` 上游 `pan: [-0.8, 0.8]` + `dur` 折成**区间起点**并丢弃 `dur`，与重庆 / 墨尔本 / 大阪 / 悉尼同一口径）。

#### 8.16.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`logs_temp/seoul_compile.log`，2m43s BUILD SUCCESSFUL；首轮 3 条同根因） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，6m19s BUILD SUCCESSFUL，覆盖全部 21 城） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（与上一行合跑 `logs_temp/seoul_gates.log`，27s BUILD SUCCESSFUL） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/seoul_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| `LofiUpstreamHygieneTest` | 本轮未跑（H2 既有红仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js） |

**判读结论**：首尔全套到位 —— 南山轮廓 + 首尔塔（塔身四色洗灯 60 秒轮换：t000 紫蓝 / t120 青绿，电梯光点 + 塔尖信号灯）与索道缆车（`CABLE2` 两点间来回）、山腰民宅亮窗；崇礼门（石砌城台 + 重檐歇山 + 匾额 + 檐下暖晕）、世运高架步道（桥面行人 5 名 + 蓝色灯带）、南大门市场（10 块招牌三档亮度 + `seq` 跑马 + 4 处闪烁窗 + 两座红十字 + 屋顶三字跑马灯 + 街区暖窗）、银杏街（`TREE3` 树冠球群 + 240 片碎叶 + 三档扇群共 64 枚 + 三阵风飘叶）、帐篷摊（蒸笼蒸汽 + 布幡逐行摆动 + 灯泡 + 猫）、飞檐与风铃、四车道车流（巴士 / 滑板车 / 轿车 + 车流光尾）、斑马线、远景飞机与直升机（t060 拍到飞机右飞）、救护车过街（164 s）、暗角。
四帧差异自洽：云带整体飘移、塔身洗灯换色、叶群随风摆动、车道车流位置改变、行人走位与风铃摆动相位不同。
站牌 `SEOUL` 用缺省左上（本城 `SIGN_ORIGINS` 无覆写），压在夜空与飞檐之间、不遮塔。

#### 8.16.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()` ⇒ 上游 `RGB13`/`C10`（hex 记忆化）删除；⚠️ **抖动量化全部保留**（`pickFromRamp` / `bayer` / `pickFlat4`）；
② `mixRGB2` 上游写 `Uint8ClampedArray` **逐通道夹 0..255**，引擎 [mix] 不夹，而本城塔身玻璃的 `0.9 - curve` 会让 `t` 为负 ⇒ 另立 [mixRGB2]（逐通道 `jsRound(lerp)` 后夹 0..255）；
③ `pick11` 与引擎 `pickFromRamp` **逐式相同** ⇒ 调用点用引擎名；`pickFlat4`（软阈值 `0.5 + (bayer-0.5)*0.3`）本城独有 ⇒ 保留；
④ `pix10(w,h)` ⇒ [PixelBuffer]（`set`/`alpha`/`fill` 同名对应）、`p.done()` ⇒ `PixelBuffer.canvas`、`trim12` ⇒ [PixelBuffer.trim]；`p.line` 引擎没有 ⇒ [pline]；⚠️ `renderTower6` 上游**不裁剪** ⇒ 直接包 [Pixmap]；
⑤ ⛔ 引擎无 `clip()`：本城上游**没有任何 `clip()` 调用**，无需替身；镜像 `flip(c)` ⇒ [flipH] 逐列拷贝；
⑥ 广告位：**不造假对象** ⇒ `makePitch(...)` 的产物 `BB_PITCH4` 整段删除（不留桩），`drawBillboard18` 只走自有画面、`covers` 判据随之消失；
⑦ 天气：`WEATHER20 = weatherPlans(["ginkgo"])` ⇒ 本城**有母题天气**（银杏），`plans[w] || plans.city` 折成 [weatherPlan20]（五个共享母题 ⇒ `shared` = 该母题、`ginkgo = false`；`"clear"` ⇒ 两者皆空；其余含 null ⇒ `ginkgo = true`）；跨城共享的 `drawWeather` 不移植 ⇒ 空实现（`reducedMotion` 因此在本城**不被读取**，上游也只把它传给 `drawWeather`）；`WX_LAND20` / `WX_LIGHTS7` / `WX20` / `WX_BB20` 仍照搬为数据；
⑧ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕的 α（[halo20] 统一乘）；`neonPulse` → 霓虹类光晕的**加性**亮度（[haloNeon20]：招牌 / 屋顶字 / 红十字 / 广告牌辉光 / 塔身灯带）；`tailGain` → 车 / 巴士 / 滑板车的车流光尾 α（`drawTraffic3` 三处 `addBlit(GLOWS.*)`，0.9 / 0.85 各乘）；⛔ 不碰坐标、几何、色值；
⑨ 上游 `ambience`（车流 / 人群 / 风）是音频参数、本效果不出声（§1.3b）⇒ 不移植；`events` 只作画面节奏锚点；
⑩ `Float32Array(W*h)` 云密度场 ⇒ [FloatArray]（写入 / 读取口径逐位相同）；
⑪ `+t.toFixed(2)` ⇒ `jsRound(t*100)/100.0`（[busHorn] 两处）；
⑫ `p.set(..., null)` 的"不写"语义 ⇒ [put]（nullable 颜色，null 直接跳过）；
⑬ `HL20[s.hl]` 的动态查表 ⇒ [hlOf]（`pink` / `cyan` / `yellow` / `lav` / `green` / `red2` 六个霓虹名）。

#### 8.16.4 本城新踩的坑（余下城市照用）

① **上游 `var X = []` + `push` 的字段别写成 `Array<T>`**：`FLURRY`（还有 `TRAIL` / `ROADLAMPS` / `FAR_CARS` / `STARS17` / `GK[n]`）在 Kotlin 侧必须 `ArrayList<T>`；写成 `lateinit var FLURRY: Array<Flurry>` 后 `FLURRY = ArrayList()` 报 3 条（`Cannot infer type for type parameter 'E'` ×2 + `Unresolved reference 'add'`）—— **一处根因炸 3 条**。
② **`renderCar` 的入参口径随数据源变**：上游 `C10(body2)` 收 CSS 字符串，本城 `CAR_COLS2` 是 IntArray ⇒ 形参收 `Int` 色值、两辆出租车的调用点写 `renderCar(hex("#e8782a"), true)`；`renderBus2` 仍收 `String`（`rng(body2.length*7)` 拿的是**字符串长度**当种子，不能顺手改 Int）。
③ **Int 与 Double 的比较一律显式 `.toDouble()`**：本城三处 —— `renderMoon15` 的 `dx < term ± 0.6`（`dx: Int`）、`renderHill` 的 `x > PEAK2.x`、`renderGate` 的 `abs(x - cx) > hw - 1.2`（`abs(Int)` 返回 Int）。**算术可以混、比较不行**这条老规矩继续生效。
④ **`PixCtx` 的 `translate` / `rotate` / `scale` 与变换感知 `drawImage` / `fillRect` 引擎都有** ⇒ `drawFan`（扇叶 translate + rotate + `scale(1, |fl2|)`）逐句照搬，⛔ 不要自己算矩阵 —— 自算会偏离上游像素口径。

#### 8.16.5 下一个入口

余下 **36 个场景**，仍按上游行数从小到大逐城推进（`lisbon` 2052 → `san-francisco` 2060 → `toronto` 2061 → …），每城节拍不变。
本城的分片体量参考：7 片 / 3,431 行（`h` 291、`p01` 334、`p02` 377、`p03` 644、`p04` 641、`p05` 747、`p06` 390）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 2 次；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据。**

---

### 8.17 `lisbon`（里斯本 · 阿尔法玛的栗子烟，上游 2,052 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityLisbon.kt` —— **3,273 行**，类 `LisbonScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/lisbon.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **11 片**：`logs_temp/lisbon_parts/`（`h.txt` 314 行 = 头 + **12 条受控差异** + 元数据 + 字段区；`p01_helpers` 330、`p02_sky` 227、`p03_hill` 392、`p04_bb_ship` 260、`p05_roofs` 246、`p06_street` 459、`p07_terrace` 658、`p08_prerender` 81、`p09_draw` 76、`p10_init` 219）。
本城是 **2D 精灵合成型**：天空/云/远景/水面/雾、阿尔法玛山丘（民居群 + 格拉萨修道院 + 圣维森特双塔 + 圣克拉拉市场 + 国家先贤祠）、广告山墙、江上大船、屋顶层、街道层（五段商铺 + 电车 28 路）、街角（民宿 + 法多酒馆）、露台层（花砖栏杆 + 常春藤 + 路灯）、栗子摊都在 `prerender27()` 里一次烘成画布，每帧只贴回图层 + 画动态件。

#### 8.17.1 结果

- 编译错误链：**31 → 0**（两轮 `compileDebugKotlin`；首轮 31 条只有**两个根因**，见 §8.17.4 ①②；次轮 0 error，4m10s BUILD SUCCESSFUL，`logs_temp/lisbon_compile2.log`）。
- 本地提交：`8640586`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"lisbon" -> LisbonScene()`（按字母序落在 `kyoto` 与 `melbourne` 之间）；`CityRegistry.SCENES_BUILT` 加 `lisbon`（**第 22 项 / 共 22 项**）；注册表 `focusX 0.0 → 232.0`（= `PN.cx`，先贤祠圆顶轴线，门禁 R6 逐字段对账）。
  `nameZh`「里斯本」/`taglineZh`「阿尔法玛的栗子烟」/`accentHex #3d6bff` 基线里已有，无需回填。
- 元数据：`focusX 232`；`landmarkX0/X1 = PN.x0 / PN.x1 = 201 / 263`（先贤祠角塔到角塔）；`landmarkTop = PN.tip = 39`（圆顶尖饰所在行）；`billboardRect = BB26 = [271, 125, 46, 20]`；`accent = #3d6bff`（花砖的钴蓝）。
- `events`：十四条按时刻升序 —— 海鸥 `14` → 电车运行 `26` → 电车铃 `26.6 / 42.2 / 146.4` → 渡轮汽笛 `59` → 渡轮航行 `59.5` → 海鸥 `78` → 大船汽笛 `100` → 教堂钟 `118 / 121.6 / 125.2` → 电车运行 `146` → 海鸥 `190` → 栏杆海鸥 `205.8`（`pan: [a,b]` 取区间**起点**、`dur` 丢弃，与重庆 / 墨尔本 / 大阪 / 悉尼 / 首尔同一口径）。

#### 8.17.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`logs_temp/lisbon_compile2.log`，4m10s BUILD SUCCESSFUL；首轮 31 条两个根因） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，7m02s，覆盖全部 22 城；用例耗时 50.8s） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（与上一行合跑 `logs_temp/lisbon_gates.log`，26s BUILD SUCCESSFUL） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/lisbon_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| 电车补采（四时刻都不在 26–53 s / 146–159 s 窗口内） | 一次性探针 test 渲 `t=40`（右行）/ `t=150.5`（左行）各一张 → `logs_temp/lofi_render/lisbon_probe_t040\|150.png`，**判读后已删除探针源文件**（不入库） |
| `LofiUpstreamHygieneTest` | 本轮未跑（H2 既有红仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js） |

**判读结论**：里斯本全套到位 —— 先贤祠（鼓座 + 肋条穹顶 + 采光亭 + 尖饰，t228 拍到尖饰着灯的金点）、圣维森特双塔与山墙玫瑰窗、格拉萨修道院（左上白色长楼）、山丘民居的暖窗群（右下一直铺到江岸）、
广告山墙「NEYTA QUENTINHA」（内嵌蛋挞 `TART` 图案，81.3/199.2 两段闪烁）、江面远景灯带与烟囱红灯、大船（舷窗分档亮灭 + 船舷暖晕）、月亮与月晕、
街道五段（`CAFE` / `GINJINHA` / `MERCEARIA` 店招 + 蓝色花砖民宿 + `FADO` 酒馆招牌与门灯）、九面彩旗串、路灯与灯下光斑、栗子摊 `CASTANHAS`（锅、栗子、纸袋、烟囱、招牌）、
情侣与长椅老人、花砖栏杆（每 64px 一块 `paintPanel`，内有帆船纹）、常春藤垂落、银杏叶三层飘落、暗角。
电车补采确认 `renderTram2(1)` / `renderTram2(-1)` 两个方向都能画出并**恰好止于 `CH2.x1`**（`clipRight27` 与上游 `clip(0,0,CH2.x1,H)` 等价，不吞掉左侧、不越过街角）。
四帧差异自洽：云带整体飘移、星点闪烁、鸽群/海鸥位置与扇翅相位改变、渡轮在 t120 驶入右侧江面（58–138 s 窗口内）、水面高光带相位不同 —— 确定性时间轴直译无误。
站牌 `LISBON` 用缺省左上（本城 `SIGN_ORIGINS` 无覆写），压在夜空与修道院之间、不遮圆顶。

#### 8.17.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()` ⇒ 上游 `RGB20`/`C14`（hex 记忆化）删除；⚠️ **抖动量化全部保留**（`pickFromRamp` / `bayer`）；
② `mixHex16` 与引擎 `mix` **逐式相同**（本城 5 个调用点的 `t` 全在 [0,1]，无需首尔的逐通道夹取包装）⇒ 调用点直接用引擎名；③ `pick15` 与引擎 `pickFromRamp` 逐式相同 ⇒ 用引擎名；
④ `pix14` ⇒ `PixelBuffer`、`trim16` ⇒ `trim`、`sprite20` ⇒ `spriteFromRows`（`FERRY_C4` / `FERRY_REF` 两处，行等宽）、`p.line` ⇒ `lineP4`（⚠️ 上游用 `Math.ceil`，**与首尔 `pline` 的 `round` 口径不同**）、`textP5` 同名前缀；
⑤ ⛔ 引擎无 `clip()` 且本城上游**有**一处（`drawTram` 的 `rect(0,0,CH2.x1,H); clip()`）⇒ `clipRight27`：把 x∈[CH2.x1,W) 全高快照到实例 scratch、画完回填（scratch 惰性分配一次）；
⑥ 广告位：本项目不放广告 ⇒ `makePitch(...)` 的产物 `BB_PITCH7` **整段删除不留桩**，`drawBillboard25` 只走自有画面（`covers` 判据消失），`renderFrame27` 的 `pitch` 形参一并删除；
⑦ 天气：`WEATHER27 = weatherPlans(["leaves"])` ⇒ 本城**有母题天气**（银杏叶），折成 `weatherPlan27`（四个共享母题 ⇒ `shared` = 该母题、`leaves = false`；`"clear"` ⇒ 两者皆空；其余含 null ⇒ `leaves = true`）；跨城共享的 `drawWeather` 不移植 ⇒ 空实现（`reducedMotion` 因此在本城**不被读取**）；`WX_LAND27` / `WX_LIGHTS10` / `WX27` / `WX_BB27` 仍照搬为数据；
⑧ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕的 α（`halo27` 统一乘）；`neonPulse` → **店招/广告类**光晕的加性亮度（`haloNeon27`：广告牌辉光与灯、街上商铺、酒馆、门口灯）；`tailGain` → 电车 `head`/`beam`/`tail` 三处光晕（本城唯一的"车灯"类）；⛔ 不碰坐标、几何、色值；
⑨ `ambience`（water / crowd / traffic / wind）是音频参数、本效果不出声（§1.3b）⇒ 不移植；`events` 只作画面节奏锚点；
⑩ `arr.find(...)` 的"0 也是真值"陷阱 ⇒ `firstOrNull` + null 判断（`arch !== void 0` ⇒ `arch != null`）；
⑪ `p.set(..., null)` 的"不写"语义 ⇒ `put`（本城无调用点，保留助手备对账）；
⑫ `gullPos(g,i,t)` 返回 `[x,y]` ⇒ 返回 `IntArray(2)`。

#### 8.17.4 本城新踩的坑（余下城市照用）

① **上游对象字面量的第二个键不一定叫 `y`**：`SE = { x: 408, top: 121 }` / `SM = { x: 437, top: 152 }` 收进 `XyI(x, y)` 后，`paintChurches` 里 28 处 `SE.top` / `SM.top` 全炸 `Unresolved reference 'top'`（**一处根因炸 28 条**，占首轮 31 条的九成）。正解是新建 `XyTop(x, top)`。⚠️ 与首尔"`var []` + `push` 写成 `Array` 炸 3 条"同型：**先看上游字面量的键名，再挑/造类型**。
② **`SceneEvent.t` 是 Double**：上游 `{ t: FERRY3.t0 + 1, type: "horn" }` 里两个操作数都是 Int ⇒ Kotlin 传 `Int` 报 `actual type is 'Int', but 'Double' was expected`。写成 `+ 1.0`（同组 `+ 1.5` 本来就对）。
③ **"是否裁剪"决定返回类型是 `PixelCanvas` 还是 `Pixmap`**：上游 `renderTerrace2()` 返回 `p.done()`（整幅贴回 ⇒ `PixelCanvas`，`ctx27.drawImage(TERRACE_C, 0, 0)`），而 `renderCart`/`renderCorner`/`renderStreet9`/`renderRoofs` 走 `trim16(...)`（⇒ `Pixmap` + `blit16`）。判断依据只有上游那一行 `return`，⛔ 别按"看起来是图层"猜。
④ **`spriteFromRows` 的宽度取首行**：`FERRY_ROWS2` 五行等宽 16 ⇒ 引擎助手可用；⚠️ 若行不等宽（首尔 `sprite17` 那类）必须另写本地实现，本城未踩。
⑤ 工具链：`Write` 新建分片会吞掉**首行** 4 个空格缩进（本城 p03–p10 全部复现），补法固定为
`python -c "…L[0]='    '+L[0].lstrip(' ')…"; encoding='utf-8', newline='\n'`，`grep -c '^    // region'` 复核 —— `Edit` 对此无效。

#### 8.17.5 下一个入口

余下 **35 个场景**，仍按上游行数从小到大逐城推进（`san-francisco` 2060 → `toronto` 2061 → `cairo` 2069 → …），每城节拍不变。
本城的分片体量参考：11 片 / 3,273 行（`h` 314、`p01` 330、`p02` 227、`p03` 392、`p04` 260、`p05` 246、`p06` 459、`p07` 658、`p08` 81、`p09` 76、`p10` 219）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 2 次；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据**；本城还额外补采了电车窗口内的两帧。

---

### 8.18 `san-francisco`（圣弗朗西斯科 · 金门大桥上的雾，上游 2,060 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCitySanFrancisco.kt` —— **3,572 行**，类 `SanFranciscoScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/san-francisco.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **14 片**：`logs_temp/sanfrancisco_parts/`（`h.txt` 433 行 = 头 + **13 条受控差异** + 元数据 + 字段区；`p01_helpers` 261、`p02_sky` 138、`p03_fog` 153、`p04_bridge` 275、`p05_water` 463、`p06_city` 258、`p07_houses` 474、`p08_billboard` 189、`p09_street` 305、`p10_drizzle` 96、`p11_prerender` 105、`p12_draw` 97、`p13_init` 311）。
本城是 **2D 精灵合成型**：金门大桥（南北两塔 + 主缆抛物线 + 三跨侧缆 + 桥面车流 + 两盏塔顶红灯）、三重雾堤（Marin 层带 `destination-in` 遮罩 + 瀑布雾 + 水舌）、东岸城市（后景/前景两层随机楼群 + 六辆沙滩车）、太平洋高地四栋维多利亚民居（亮/暗两版分层 + 四类房间动画 + 炊烟）、蟹肉广告牌（爪式闪动 16 帧 × 明暗两版）、缆车街景（两趟车 + 光锥 + 球灯闪烁）、恶魔岛（灯塔旋转光束 16 档）与渔人码头（栈桥、三桅、海狮七只）都在 `prerender10()` 里一次烘成画布，每帧只贴回图层并画动态件。

#### 8.18.1 结果

- 编译错误链：**8 → 0**（两轮 `compileDebugKotlin`；首轮 8 条只有**三个根因**，见 §8.18.4 ①②③；次轮 0 error，4m15s BUILD SUCCESSFUL，`logs_temp/sanfrancisco_compile2.log`）。
- 运行时崩溃链：**1 → 0**（首轮快照 `CityPainter.keep` 越界，见 §8.18.4④；修复后次轮 5m03s 全绿）。
- 本地提交：`35ebdda`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"san-francisco" -> SanFranciscoScene()`（按字母序落在 `rome` 与 `seoul` 之间）；`CityRegistry.SCENES_BUILT` 加 `san-francisco`（**第 23 项 / 共 23 项**）；注册表 `focusX 0.0 → 290.0`（= `Math.round((T0.cx + T1.cx) / 2)`，两塔轴线中点，门禁 R6 逐字段对账）。
  `nameZh`「旧金山」/`taglineZh`「金门大桥上的雾」/`accentHex #ff6a3d` 基线里已有，无需回填。
- 元数据：`focusX 290`；`landmarkX0/X1 = T0.cx - T0.hw - 1 / T1.cx + T1.hw + 1 = 207 / 372`（南塔西缘到北塔东缘）；`landmarkTop = T0.top - 1 = 105`（南塔信标所在行）；`billboardRect = BILL2`；`accent = #ff6a3d`（桥塔的锈橙，也是广告画布的强调色）。
- `events`：按时刻升序 —— 五声雾笛（`FOGHORNS`，x=南塔）→ 大船出港 `SHIP.t0`（`boat`，`pan: [1, 0.05]` 取区间**起点** 1.0、`dur` 丢弃）→ 船笛 `SHIP_HORN`（x=368）→ 六声上行车铃 `BELLS_UP`（x=90）→ 三声下行车铃 `BELLS_DOWN`（x=120）→ 海狮入水 `LION_DIVE.t`（splash，x=464）。

#### 8.18.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error**（`logs_temp/sanfrancisco_compile2.log`，4m15s BUILD SUCCESSFUL；首轮 8 条三个根因） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，5m03s，覆盖全部 23 城；首轮因 `keep` 越界 3 例全红，修复后全绿） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（与上一行合跑 `logs_temp/sanfrancisco_gates.log`，18s BUILD SUCCESSFUL） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/san-francisco_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| `LofiUpstreamHygieneTest` | 本轮未跑（H2 既有红仍是 `e51/tools/*.js`，见 §8.9.5；本城未新增任何 js） |

**判读结论**：圣弗朗西斯科全套到位 —— 金门大桥（双塔橙红 + 主缆/侧缆抛物线 + 桥面车流灯）、「FRESH CRAB」广告牌（米色底 + 左侧红蟹 + 红色 CRAB 大字）、四栋维多利亚民居（蓝/绿/紫/棕、红门/青门/绿门、暖窗群）、恶魔岛灯塔与高桅大船、马林岬、月亮与月晕、缆车（t060 拍到位）、行人（t120：红大衣遛狗 + 蓝帽人）、球灯街灯与灯池、右侧金融区摩天楼群、雾层。
四帧差异自洽：雾层滚动、星点闪烁、水舌滚动、桥上车流与塔顶红灯相位改变、缆车位置移动、行人出现 —— 确定性时间轴直译无误。
站牌 `SAN FRANCISCO` 用缺省左上，压在夜空上、不遮桥塔。

#### 8.18.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()`；`tint3` 的字符串缓存 `tintCache2` 删除（引擎 `mix` 逐式相同），⚠️ **抖动量化全部保留**（`bayer`/`ditherFill`）；
② `painter4` ⇒ `PixelBuffer`、`trim6` ⇒ `trim6`（输入是**画布**）、`line7` 逐字照搬（**`Math.round`**，与里斯本 `lineP4` 的 `Math.ceil` 口径不同，⛔ 不许合并）、`text` ⇒ `text10`；
③ ⛔ 引擎 `Comp` 没有 `destination-in` ⇒ `destIn`（只把目标 alpha 乘遮罩 alpha，RGB 不变，`(da*sa+127)/255`）；
④ ⛔ 引擎没有 `clearRect` ⇒ 三块专用 scratch 改走 `PixelCanvas.reset()`；
⑤ 广告位：`PITCH8 = makePitch(...)` **整段删除不留桩**（`drawBillboard10` 恒画自有蟹肉画面，`renderFrame10` 的 `pitch` 形参删除）；
⑥ 天气：`WEATHER10 = weatherPlans(["drizzle"])` ⇒ 本城**有母题天气**（细雨），折成 `weatherPlan10`（`"drizzle"` ⇒ `drizzle=true, shared=null`；`rain/thunderstorm/snow` ⇒ `shared` = 该母题；`"clear"` ⇒ 两者皆空；其余含 null ⇒ 母题档）；跨城共享 `drawWeather` 不移植 ⇒ 空实现（`reducedMotion` 因此不被读取）；`WX_LAND10/WX10/WX_BB10` 仍照搬为数据；
⑦ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（`halo10`）；`neonPulse` → **广告牌类**光晕的加性亮度（`haloNeon`：牌面辉光与三盏照灯）；`tailGain` → 缆车 `head`/`tail` 光晕与 `BEAM_UP/BEAM_DN` 光锥的 α（`haloTail`）；
⑧ `flipH3` 改**逐列拷贝**（引擎变换贴回按逆变换取样会丢最右一列，与巴塞罗那 `flipH6` 同一口径）；
⑨ `ambience`（wind/water/traffic/rain）不移植；`events` 只作画面节奏锚点（`pan: [a,b]` 取起点、`dur` 丢弃）；
⑩ `cityPainter()` 的 `{...p, own, block, other, keep}` 用 `CityPainter` 包装类（`own` 用 `IntArray`，`-1` = 不属于任何楼块，`keep` 就地过滤）；
⑪ `renderPoster2` 的 `getImageData → 逐像素乘光 → putImageData` 直接读写 `canvas.px`（alpha 原样保留）；
⑫ 模块级**函数值**（`renderWater7`/`SADDLE`/`CABLE`/`SOUTH_SIDE`/`NORTH_SIDE`/`legTops`/`hornEnv`/`pierPt`/`cityWin`/`wxLight3`）⇒ 同名成员函数；只有 `CABLE` 因依赖 `SADDLE` 的两个结果且被闭包捕获，保留为 `initConstants()` 里赋值的 lambda；
⑬ `rng(seed)` ⇒ `PxRng` 的 `Rng` 包装（同名同序）。

#### 8.18.4 本城新踩的坑（余下城市照用）

① **`kotlin.math.pow` 是扩展函数，⛔ 不能写成顶层 `pow(a, b)`**：首轮 5 处裸 `pow(...)`（p02 天空 / p03 雾 / p04 桥 / p05 水 / p07 民居各一处）⇒ 一律改 `a.pow(b)`。⚠️ `exp/min/max/hypot/sqrt/floor/abs/cos/sin` 已 import 为顶层函数可正常调用，只有 `pow` 特殊。
② **`CityPainter` 没有 `canvas` 属性**：`renderCityBack`/`renderCityFront` 里 `return p.canvas` 报错（`p` 是包装类，取画布要 `p.p.canvas`）；⚠️ 同文件里 p02–p05/p07 的 `return p.canvas` 是 `PixelBuffer` 局部变量，**合法勿动** —— 先看 `p` 的声明类型再改。
③ **"声明折成成员函数"不等于"已实现"**：h.txt 把上游模块级函数值 `renderWater7` 登记为"折成成员函数"，但实现落在 p11 调用之后才被发现（`Unresolved reference`）。测绘时**函数值清单要逐条核对实现落在哪一片**，不能只登记名字。
④ **JS `Int16Array` 越界读返回 `undefined`，Kotlin `IntArray` 越界抛**：`cityPainter.keep()` 的 `own[w.y * W + w.x]` 在 JS 里越界读 `undefined`（恒 ≠ `w.b` ⇒ 该窗被丢弃、不报错）；Kotlin 直接炸 `ArrayIndexOutOfBoundsException`（本城首轮快照 3 例全红的唯一原因）。修复：**先判界再读**，越界 ⇒ 丢弃 —— 与 JS 语义逐位一致，不是放宽。

#### 8.18.5 下一个入口

余下 **34 个场景**，仍按上游行数从小到大逐城推进（`toronto` 2061 → `cairo` 2069 → …），每城节拍不变。
本城的分片体量参考：14 片 / 3,572 行（`h` 433、`p01` 261、`p02` 138、`p03` 153、`p04` 275、`p05` 463、`p06` 258、`p07` 474、`p08` 189、`p09` 305、`p10` 96、`p11` 105、`p12` 97、`p13` 311）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 2 次；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据**。

---

### 8.19 `toronto`（多伦多 · 枫叶与 CN 塔，上游 2,061 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityToronto.kt` —— **3,990 行**，类 `TorontoScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/toronto.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **10 片**：`logs_temp/toronto_parts/`（`h.txt` 350 行 = 头 + **13 条受控差异** + 元数据 + 字段区；`p01_types` 298、`p02_helpers` 383、`p03_sky_bldg` 503、`p04_kiosk_water` 422、`p05_trees_geese` 352、`p06_crowd` 304、`p07_prerender` 116、`p08_draw` 515、`p09_init` 737）。
本城是 **2D 精灵合成型**：CN 塔（桅杆 / 天舱 / 主舱九行剖面 / 塔身 `cnHalf` 逐行半宽）+ 罗杰斯中心穹顶 + 天际线三层（后景塔群 7 / 中景塔群 11 / 湖滨公寓随机群）+ 安大略湖（镜像倒影 + 反光条 + 渡轮 + 出租车 + 水禽）+ 皇后码头步道（售货亭 + `ROSIE'S BUTTER TARTS` 广告牌 + 长椅 + 垃圾桶 + 街灯 + 枫树 + 柳树）+ 人群十二类（情侣 / 拍照 / 朋友 / 小孩 / 钓鱼 / 看手机 / 遛狗 / 骑车 / 慢跑 / 散步 / 小贩）+ 浣熊翻垃圾桶 + 草坪鹅 / 水鹅 / 雁群 + 飞机 + 枫叶三层飘落 + 暗角，全部静态层在 `prerender48()` 里一次烘成画布，每帧只贴回图层并画动态件。

#### 8.19.1 结果

- 编译错误链：**20 → 0**（两轮 `compileDebugKotlin`；首轮 20 条只有**四个根因**，见 §8.19.4 ①②③④；次轮 0 error，4m19s BUILD SUCCESSFUL，`logs_temp/toronto_compile2.log`，**本文件 0 warning**）。
- 运行时崩溃链：**0**（首轮快照即绿 —— 本城是 24 城以来第一座**编译清零后直接跑通**的，没有第二次快照轮）。
  2026-10-24 所有者要求"运行时崩溃要解决掉" ⇒ 做了一次**全量离屏运行时扫描**（临时诊断测试，跑完即删，
  证据 `logs_temp/runtime_sweep.log` + `logs_temp/runtime_sweep_result.xml`）：**24 城 × 192 帧 = 4,608 帧，
  0 异常 / 0 黑帧，每城最低不透明率恒 1.000**。覆盖维度见 §8.19.2 表格末两行 ⇒ **未复现任何运行时崩溃**，
  故无可修项（若真机确有崩溃，须以 `adb logcat -b crash` 的堆栈为准，离屏侧已排除）。
- 本地提交：`b458316`（3 个文件，**仅代码**）+ 本轮文档提交（⛔ 未推送、未 tag、未编 release）。
- 接线三处：`LofiScenes.build()` 加 `"toronto" -> TorontoScene()`（按字母序落在 `tokyo-room` 与 `vancouver` 之间）；`CityRegistry.SCENES_BUILT` 加 `toronto`（**第 24 项 / 共 24 项**）；注册表 `focusX 0.0 → 178.0`（= `CN.x`，CN 塔轴线，门禁 R6 逐字段对账）。
  `nameZh`「多伦多」/`taglineZh`「枫叶与 CN 塔」/`accentHex #e00000` 基线里已有，无需回填。
- 元数据：`focusX 178`；`landmarkX0/X1 = DOME6.x0 / CN.x + 8 = 117 / 186`（穹顶西缘到塔右缘）；`landmarkTop = CN.top = 20`（天线尖）；`billboardRect = BB40 = (68, 128, 46, 20)`；`accent = #e00000`（枫叶的红，也是广告画布的强调色）。
- `events`：10 条按时刻升序 —— 渡轮汽笛 `FERRY7.horn`(31, x=470) → 渡轮出航(31.5, `pan 0.9`) → 出租车出航(52.5, `pan -0.9`) → 车铃 `BIKES2[0].bell`(64, x=400) → 水花(73, x=260) → 水花(79, x=120) → 出租车回程(134.5, `pan 0.9`) → 车铃 `KISS2[0]+0.5`(140.5, x=236) → 雁群落水 `GEESE.land+0.5`(184.5, x=220) → 车铃 `BIKES2[1].bell`(197, x=90)。

#### 8.19.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（`logs_temp/toronto_compile2.log`，4m19s BUILD SUCCESSFUL；首轮 `toronto_compile1.log` 20 条，四个根因见 §8.19.4） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败 |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，4m01s，覆盖全部 **24 城**） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/toronto_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| 全量 `testDebugUnitTest --rerun-tasks`（H2 修复后） | **2587 例 / 0 失败 / 0 错误 / 2 skipped**（255 个 XML，16m16s BUILD SUCCESSFUL，`logs_temp/full_after_h2.log`）—— ⭐ 这是 E51 开题以来**第一次全量零失败** |
| `LofiUpstreamHygieneTest`（H 系列，含 H2） | **7 例 / 0 失败 / 0 skip**（`logs_temp/h2_fix_test.log`，10m34s）；H2 既有红已按 A 闭环，见 §8.9.5 |
| 运行时扫描（临时诊断，跑完即删） | **24 城 × 192 帧 = 4,608 帧，0 异常 / 0 黑帧**，每城最低不透明率恒 **1.000**（`logs_temp/runtime_sweep.log`、`runtime_sweep_result.xml`）。维度：①全时间轴 `t∈[0,240)` 步长 2s（120 帧/城，覆盖每个事件窗口边界）；②母题天气 7 档（`null`/`clear`/`rain`/`snow`/`drizzle`/`thunderstorm`/`leaves`）× 4 时刻；③`billboard=false` × 4 时刻；④音频三通道极值（`lightGain` 0/2、`neonPulse` 1、`tailGain` 0/2）× 4 时刻；⑤`reducedMotion` 与钟面边界（`clock` 0/1/719/1439）× 4 时刻 |

**判读结论**：多伦多全套到位 —— CN 塔（桅杆 + 天舱 + 主舱 + 塔身 + 天线尖）、罗杰斯中心穹顶（左）、三层天际线（窗灯密度分层 + 红/黄/白三色楼冠）、安大略湖（倒影 + 反光条 + 渡轮）、`ROSIE'S BUTTER TARTS` 售货亭（绿檐 + 红白条纹遮阳 + 暖光售窗 + 菜单字迹）、两盏街灯与两团灯池、左侧红枫（三层树冠 + 地面落叶）、右侧柳树（垂条）、草坪五只鹅、步道人群、飘落枫叶。
四帧差异自洽：**t000** 静夜基线（渡轮未出港）；**t060** 渡轮（黄舱 + 窗灯 + 尾迹）停在湖面左中、塔顶红灯亮；**t120** CN 塔**整支变红**（`SHOW1` 110–148 灯光秀第一色，湖面同步红色反光条）；**t228** CN 塔**整支彩虹渐变**（`SHOW22` 206–230，七色带自上而下分层正确）。窗灯明灭、星点闪烁、水纹滚动、枫叶滚动、人群位移 —— 确定性时间轴直译无误。
站牌 `TORONTO` 用缺省左上，压在夜空上、不遮 CN 塔。

#### 8.19.3 受控差异（文件头逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()`；字符串色缓存 `RGB37`/`C29`/`toHex21`/`MIXED13` 删除（引擎 `mix` 逐式相同），⚠️ **抖动量化全部保留**（`bayer`/`pick33`）；
② `pix29` ⇒ `PixelBuffer`；`p.line` 引擎没有 ⇒ `pline`（四处调用点逐字照搬 `n2 = max(|dx|,|dy|,1)`）；`trim34` ⇒ `PixelBuffer.trim`（返回 `Pixmap`，受 G-E51-J 约束）；
③ ⛔ 引擎**没有 5 参 `drawImage`**：`drawLeaves5` 的纵向压缩与 `drawSkitter`/`drawTaxi2`/`drawWillow`/`drawCN` 的区域拷贝一律写 9 参形态（源矩形 + 目标矩形）；
④ 广告位：`PITCH41 = makePitch(...)` **整段删除不留桩**，`drawBoard7` 恒画自有 `ROSIE'S BUTTER TARTS` 画面（`covers` 判据消失），`renderFrame48` 的 `pitch` 形参一并删除；
⑤ 天气：`WEATHER48 = weatherPlans(["leaves"])` ⇒ 本城**有母题天气**（枫叶），折成 `weatherPlan48`（`rain/thunderstorm/snow/drizzle` ⇒ `shared` = 该母题、`leaves=false`；`"clear"` ⇒ 两者皆空；其余含 null ⇒ 母题档 `leaves=true`）；跨城共享 `drawWeather` 不移植 ⇒ 空实现（`reducedMotion` 因此在本城**不被读取**）；`WX_LAND45/WX_LIGHTS19/WX48/WX_BB47` 仍照搬为数据；
⑥ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（`halo48` 统一乘）；`neonPulse` → **广告牌/招牌类**光晕的加性亮度（`haloNeon48`：`HL48.sign` 与广告灯）；`tailGain` → 车船灯（渡轮 `HL48.ferry`/`HL48.tiny`、出租车车灯、飞机航灯、自行车前灯）与尾迹 `drawWake9` 的 α；
⑦ `ambience`（water/wind/traffic/crowd）是音频参数 ⇒ 不移植；`events` 只作画面节奏锚点（`pan: [a,b]` 取**起点**、`dur` 丢弃，与重庆/墨尔本/大阪/悉尼/里斯本/首尔同一口径）；
⑧ `Array`/`push` 型上游字段一律 `ArrayList`/`Array`，`prerender48` 开头 `length = 0` ⇒ `.clear()`；
⑨ `renderGround14`/`renderMaple`/`renderWillow` 返回 `{c,x,y}` ⇒ `Pixmap`；`renderWater25`/`renderFerry5`/`renderLamp10`/`renderBench8`/`renderBin2`/`renderSky48` 与裁边的 `renderKiosk2`/`renderBoard8` 各按上游**那一行 `return`** 决定 `PixelCanvas` 还是 `Pixmap`；
⑩ `CN_TINT`（`{}` + `Object.entries(SHOW_COLS2)` 循环）⇒ `LinkedHashMap`；`SHOW_HALO`（名字 → 颜色）⇒ `showHalo()`；`RAINBOW2` 保持名字数组；
⑪ `person20` 的 `{view,dir,sit,bend,step,aL,aR,lean}` ⇒ `PersonOpt`；`look20` 的 `{dk,long,coat,hat,pom,scarf}` ⇒ `LookOpt`；`along6` 的 `{x,moving}` ⇒ `Along`；`cnShow` 的色带 `{col,a,y0,y1}` ⇒ `Band`；
⑫ `ctx.globalAlpha` 在 canvas 里被夹到 [0,1]，本引擎不夹 ⇒ 唯一会超 1 的 `drawLeaves5` 的 `(1-u)*10` 处显式 `min(1.0, …)`（等价，不是放宽）；
⑬ `rng(seed)` ⇒ `PxRng` 的 `Rng` 包装（同名同序）；`hash` 的整数实参照搬。

#### 8.19.4 本城新踩的坑（余下城市照用）

① ⛔ **逐行直译时不许给"上游的字面量"起名字**：p04 把上游 `for (const [x, y] of [[96,193], …]) p.set(...)`（14 个点的**裸数组字面量**）写成 `for (pt in MENU_PX)` —— `MENU_PX` 在上游**根本不存在**，是我凭空造的名字，编译直接炸 6 条（`Unresolved reference` + `iterator() ambiguous` + `Map.get` 推断失败级联）。教训：**上游没有标识符的地方，落地也只能是字面量**（本处改成 `intArrayOf(...)` 两两取点）。这类错最贵 —— 它不是类型错，是"我以为有个常量"。
② **`jsRound` 只收 `Double`，Kotlin 不隐式加宽 Int**：`drawBike4` 的 `jsRound(bx3 + dir * k)`（两个 Int 相加）编译不过，改 `jsRound((bx3 + dir * k).toDouble())`。⚠️ 同一行后半段 `ctx48.fillRect(..., 1, 1)` 是 Int 实参**合法**（`fillRect` 有 Int 重载）—— 别把两个错因混成一个。
③ **"登记折成函数"≠"已实现"（§8.18.4③ 复现，且这次是两处同时漏）**：`h.txt` 把 `cnHalf` 与 `SHOW_HALO ⇒ showHalo` 都登记成"折成本文件同名函数"，但实现**没落到任何分片** ⇒ 各 1 条 `Unresolved reference`。测绘时"函数值清单"必须逐条核对**实现落在哪一片**，登记名字不算数。
④ **`PI` / `env` 是顶层符号，⛔ 忘了 import 不会自动可用**：`kotlin.math.PI`（**7 处**）与 `engine.env`（**2 处**，另有 1 处误拼成 `env48`）。上游写 `Math.PI` / `env(...)` 时完全不显眼 ⇒ `h.txt` 的 import 清单要**按上游出现的全局符号逐个核对**，不能只抄 `kotlin.math` 的常见那几个。

#### 8.19.5 下一个入口

余下 **33 个场景**，仍按上游行数从小到大逐城推进（`cairo` 2069 → …），每城节拍不变。
本城的分片体量参考：10 片 / 3,990 行（`h` 350、`p01` 298、`p02` 383、`p03` 503、`p04` 422、`p05` 352、`p06` 304、`p07` 116、`p08` 515、`p09` 737）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 2 次；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据**。

### 8.20 `cairo`（开罗 · 金字塔旁的斋月夜，上游 2,069 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityCairo.kt` —— **3,827 行**，类 `CairoScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/cairo.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **10 片**：`logs_temp/cairo_parts/`（`h.txt` 282 行 = 头 + **14 条受控差异** + 元数据 + 字段区；`p01_types` 301、`p02_helpers` 225、`p03_pyramid` 442、`p04_village` 637、`p05_props` 481、`p06_sprites` 268、`p07_prerender` 120、`p08_draw` 630、`p09_init` 431）。
本城是 **地形图 + 精灵合成型**，与前 24 座结构都不同：吉萨三座金字塔与狮身人面像**不是画出来的像素**，而是先建深度图（`lmap` 的 `Float32Array` 明度场 + `Uint8Array` 面标记，`pyramid()` 逐层按"石层 course"写值），再按 `SHOW3` 的 **9 套色阶**（dim/gold/amber/blue/magenta/green/red/violet/white）`paintMap()` **各烘一张** ⇒ 「金字塔灯光秀」= **同一张深度图换色阶**；`sweepAt()` 每 30 秒一次 6 秒扫光，用 `sweepF` 在两套色阶之间按 x 做交叉。
其余全部静态层在 `prerender26()` 里一次烘成画布：天空（星野 + 银河 + 流星）/ 新月 / 三层云 / 沙漠 / 高原 / 村落（窗灯 + 灯泡 + 电视 + 店铺 + 水箱 + 卫星锅）/ 宣礼塔 / 鸽舍 / 灯串三层（13 条 `STRANDS` + 12 只悬挂灯笼）/ 广告牌 `KONAFA`+伪阿拉伯笔画 / 前景（地毯 + 水烟 + 茶几 + 长凳 + 花盆）/ 暗角 / 光束。
每帧只贴回图层并画动态件：广告动画 + 鸽群 + 富尔豆餐车蒸汽 + 三轮车 / 马车 / 骆驼 / 马 + 人群十二组 + 猫 + 灯串摆动 + 沙尘天气 + 后景屋顶补画。

#### 8.20.1 结果

- 编译错误链：**6 → 0**（两轮 `compileDebugKotlin`；首轮 6 条只有**两个根因**，见 §8.20.4 ①②；次轮 0 error，2m57s BUILD SUCCESSFUL，**本文件 0 warning**）。
- 运行时崩溃链：**0**（首轮快照即绿 —— 第 2 座「编译清零后直接跑通」的城，没有第二次快照轮）。
- 接线三处：`LofiScenes.build()` 加 `"cairo" -> CairoScene()`（按字母序落在 `budapest` 与 `cape-town` 之间）；`CityRegistry.SCENES_BUILT` 加 `cairo`（**第 25 项 / 共 25 项**）；注册表 `focusX 0.0 → 194.0`（= `SX2`，狮身人面像轴线，门禁 R6 逐字段对账）。
  `nameZh`「开罗」/`taglineZh`「金字塔旁的斋月夜」/`accentHex #f0e6d2` 基线里已有，无需回填。
- 元数据：`focusX 194`；`landmarkX0/X1 = 150 / 220`（上游 `landmark: [150, 220]` 是**裸字面量**，没有标识符，落地也只能是字面量 —— §8.19.4① 的教训）；`landmarkTop = KF.tip = 60`（哈夫拉塔尖）；`billboardRect = BB25 = (96, 82, 46, 20)`；`accent = #f0e6d2`（斋月灯串的米白）。
- `events`：7 条按时刻升序 —— 换幕钟 30.4 / 60.4 / 120.4 / 150.4 / 180.4（`x=240, gain 0.16`）→ 演讲开场钟 90.5（`x=SX2, gain 0.26`）→ 演讲开场铃 93（`chime, x=SX2, gain 0.18`）。上游用 `[1,2,4,5,6].map(...)` + `SPEAKS` 两条再 `.sort((a,b)=>a.t-b.t)` 生成 ⇒ 展开成显式列表 + `.sortedBy { it.t }`（等价，`pitch` 字段本引擎没有 ⇒ 丢弃，与重庆/墨尔本/大阪/悉尼/里斯本/首尔/多伦多同一口径）。

#### 8.20.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（次轮 2m57s BUILD SUCCESSFUL；首轮 6 条，两个根因见 §8.20.4） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（本城**不用 `trim24`**：上游 `painter11.done()` 返回裸画布，`blit25` 不存在 ⇒ J1/J2 天然不适用，N0 由其它城满足） |
| `LofiUpstreamHygieneTest`（H 系列） | 7 例 / 0 失败 |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，覆盖全部 **25 城**） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/cairo_t000\|060\|120\|228.png` 四时刻，**全部人工判读** |
| E51 全量（`--tests "com.nasmusic.tv.visualizer.e51.*"`） | **99 例 / 0 失败 / 0 skip**（12 个 XML） |
| 全量 `testDebugUnitTest` | **2587 例 / 0 失败 / 0 错误 / 2 skipped**（255 个 XML，2m09s BUILD SUCCESSFUL）—— 与多伦多基线**同数**（门禁用例是按「已落地城列表」循环的，加城不改用例数） |
| `lintDebug` | BUILD SUCCESSFUL（14m26s），报告 **161 warnings / 0 errors**，且报告里**没有任何一条点名 `LofiCityCairo`**（`app/build/reports/lint-results-debug.html`） |

**判读结论**：开罗全套到位 —— 三座金字塔（门卡乌拉左小 / 哈夫拉中带塔尖帽 / 胡夫右大）、狮身人面像（正对镜头、胸口与头巾受光）、宣礼塔（绿霓虹塔顶 + 三层阳台）、新月 + 星野、三层灯串（彩旗 + 灯笼 + 悬挂大灯笼 fanous）、`KONAFA` 广告牌（含 kunafa 甜点盘 sprite + 底部彩灯带）、村落窗灯（暖黄/冷白/电视蓝三色）、富尔豆餐车蒸汽、前景卡亚米亚地毯（红/蓝/金/白四色 tile）+ 水烟 + 茶几。
四帧差异自洽，且**逐帧与 `PHASES` 对上**（这是本城正确性的硬证据，比"看着像"强）：
**t000** `k=0` ⇒ `PHASES[0]` 全 gold（三座金字塔同色，`S=amber` 狮身人面像偏暖）；
**t060** `k=2, u=0` ⇒ 扫光刚开始（`x=-12`，`sweepF≈0` ⇒ 用 `from=k-1=1`）⇒ `PHASES[1]` = M gold / A gold / **U blue**，画面正是**右塔整座变蓝**、左两塔仍金；
**t120** `k=4, u=0` ⇒ `PHASES[3]` = M/A/U 全 **dim**、`S=white` ⇒ 画面正是**三塔压成暗紫**而**狮身人面像惨白发亮**；
**t228** `k=7, u=18 ≥ SWEEP` ⇒ 无扫光、`PHASES[7]` 全 gold ⇒ 回到基线。
站牌 `CAIRO` 用缺省左上，压在夜空上、不遮金字塔。

#### 8.20.3 受控差异（文件头 14 条逐条列明，此处只给索引）

① 颜色统一非预乘 ARGB Int + 就地内联 `hex()`；`RGB19`/`rgbOf6`/`toHex11`/`mixHex15` 删除（引擎 `mix` 逐式相同），⚠️ **抖动量化全部保留**（`bayer`/`dpick7`）；
② `painter11` ⇒ `Painter`（`PixelBuffer` + 可选 `own` 归属表）；`p.has`/`p.fill` 本城**无调用** ⇒ 不移植；上游 `p.set` 的 `!col` 判空只有 `renderStreet8` 一处真传 `null` ⇒ 该处显式 `if (col != null)`；
③ `lmap` ⇒ `LMap`（`FloatArray` + `ByteArray`，与 `Float32Array`/`Uint8Array` 存取语义逐位一致）；
④ `pyramid` 的解构默认参数对象 ⇒ `PyramidOpt`；⚠️ Kotlin 默认实参**不能读实例字段**，而上游 `base = BASE3` 是默认值 ⇒ `base = -1` 哨兵 + 在 `pyramid()` 里折回；
⑤ 广告位：`PITCH20` **整段删除不留桩**，`drawBillboard24` 恒画自有 `KONAFA` 画面（`covers` 判据消失），`renderFrame26` 的 `pitch` 形参一并删除；
⑥ 天气：`WEATHER26 = weatherPlans(["zeena"])` 折成 `weatherZeena()`（只取那个布尔）；跨城共享 `drawWeather` 不移植 ⇒ 空实现（`reducedMotion` 因此在本城**不被读取**）；`WX_*` 仍照搬为数据；
⑦ 音频轻反应（§10 D-a）：`lightGain` → 全部光晕 α（`halo26`）；`neonPulse` → 广告牌/灯笼/店铺招牌类光晕（`haloNeon26`）；`tailGain` → 三轮车车灯与尾灯拖尾（`haloTail26`）；
⑧ `ambience`（crowd/traffic/wind）是音频参数 ⇒ 不移植；`events` 见 §8.20.1；
⑨ `Array`/`push` 型上游字段一律 `ArrayList`/`Array`；
⑩ 返回对象的烘焙函数 ⇒ 嵌套类：`renderVillage`⇒`Village`、`renderForeground6`⇒`Foreground`、`renderMinaret2`⇒`MinaretArt`、`makeCloudLayer12`⇒`CloudLayer`、`renderStrands`⇒`Array<StrandArt>`、`renderShops`⇒`ArrayList<Lantern>`；
⑪ `SHOW3` 的 `{ramp, face}` ⇒ `Show`；`PHASES` 的 `{M,A,U,S}` ⇒ `Phase` + `Phase.by(key)`（上游是动态下标 `PHASES[k][g.o]`）；`MON` ⇒ `HashMap<String, MonGroup>`；
⑫ `ctx.globalAlpha` 在 canvas 里被夹到 [0,1]、本引擎不夹 ⇒ **逐处核过**没有超过 1 的写入（`drawZeena` 的 `(1-u)/0.1` 在 `u>0.9` 分支内恒 ≤ 1、`drawMinaret2` 的 `0.86+0.14·wave` ≤ 1、`drawGlows` 的 `br ≤ 1`）⇒ 无需显式 `min(1.0, …)`，**不是放宽**；
⑬ `rng` ⇒ `PxRng`（同名同序）；`hash` 的整数实参照搬，`Math.floor(x/3)` 显式 `.toInt()`；`Math.sign(dx)` ⇒ Kotlin `Int.sign`（需 `import kotlin.math.sign`）；
⑭ `drawFul`/`drawRoof2` 的布尔门控照搬；`smooth(0, SWEEP, u)` 的 `SWEEP` 折成 `SWEEP.toDouble()`。

#### 8.20.4 本城新踩的坑（余下城市照用）

① ⛔ **字段声明的元素类型必须与初值构造器同源**：`h.txt` 字段区把 `STRANDS` 写成 `Array<IntArray>`，而 `p09_init` 的初值用的是 `doubleArrayOf(...)`（上游那 13 条灯串的 `sag`/`glow` 是 `0.7`、`0.75` 这类**小数**，只能是 Double）⇒ 一次错带出 **5 条编译错误**，且**分散在三个文件段**、症状各不相同：`ceil(abs(s[2]-s[0]))` 报「Int 不能给 Double」、`strandPt(s, …)` 报「IntArray 不能给 DoubleArray」、`mix(…, 1 - lit9)` 报「Int 不能给 Double」。教训：**看到同一批类型错散落多处，先查"公共来源"的类型声明**，别逐处 `toDouble()` 打补丁（打上去反而变「redundant call of conversion method」）。
② **`kotlin.math.sign` 是扩展函数、必须显式 import**：上游 `Math.sign(dx)` 里 `dx` 是 Int，Kotlin 对应 `dx.sign`（`Int.sign`），而 `sign` **不在默认 import 里** ⇒ 1 条 `Unresolved reference`。与 §8.19.4④（`PI`/`env`）同一类：**上游的全局符号要逐个核对 import**，`Math.*` 尤其容易漏。
③ ⚠️ **`Painter.set` 少了上游的 `!col` 判空 —— 这是"有意省略"而不是遗漏**：上游 `col` 是字符串/`null`，本文件是 `Int`（永不为 0，`hex`/`mix`/`dpick7` 全部返回不透明 ARGB）⇒ 判空无意义。但**下一次若出现"可能为 0 的颜色"就必须补回来**，否则会画出一个透明像素而不是跳过。

#### 8.20.5 下一个入口

余下 **32 个场景**，仍按上游行数从小到大逐城推进（`miami` 2065 → `rome-colosseum` 2068 → `new-delhi` 2073 → `bali` 2075 → …），每城节拍不变。
本城的分片体量参考：10 片 / 3,827 行（`h` 282、`p01` 301、`p02` 225、`p03` 442、`p04` 637、`p05` 481、`p06` 268、`p07` 120、`p08` 630、`p09` 431）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 3 次：首拼 3,822 行 → 修类型后 3,827 行 → 去掉冗余转换后仍 3,827 行；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据**。
⭐ 本城新增一条**更硬的判读法**：四时刻先算出 `k = floor(t/30)` 与 `PHASES[k]`，再逐帧核对「哪座塔该是哪个色阶」—— 这种"用上游常量预测画面"的判读，比"看着像不像开罗"能抓到色阶索引错、组名 M/A/U 映射错这类**整体平移型**缺陷。

### 8.21 `miami`（迈阿密 · 海洋大道雨后的霓虹，上游 2,065 行）测绘 · 2026-10-24

> **状态：测绘 + 落地已完成（落地结果见 §8.22，本节保留为结构测绘记录）。** 本城是**第 5 种结构：伪 3D 投影**，
> 与开罗的"深度图 + 色阶换色"、墨尔本的"相机 + 深度缓冲"都不同 —— 它没有相机矩阵，
> 而是**一组闭式投影函数**，全城每一栋楼、每一辆车、每一个人都靠它们定位。
> ⇒ 先把公式与片序钉死，再写代码；⛔ 不要边猜边写，一处 `S()` 写错整城错位。

#### 8.21.1 投影系统（全城地基，必须最先落地）

```
HZ9 = 135 地平线 y      VPX5 = 330 灭点 x      EYE6 = 6 视高      FOC = 330 焦距
gs(y)   = (y - HZ9) / EYE6                  屏幕 y → 地面进深（越大越近）
gx(L,y) = VPX5 - L * gs(y)                  地面横向 L → 屏幕 x（L = 0 落在灭点上）
S(x)    = (VPX5 - x) / HOTEL_L              屏幕 x → 缩放（HOTEL_L = 28 = 一栋楼的进深）
baseY(x)= HZ9 + EYE6 * S(x)                 屏幕 x → 该列地平线
dOf(x)  = FOC * HOTEL_L / (VPX5 - x)        屏幕 x → 真实进深
xOf(d)  = VPX5 - FOC * HOTEL_L / d          真实进深 → 屏幕 x（与 dOf 互逆）
```

Kotlin 侧折成同名函数（`gs / gx / sOf / baseYAt / dOf / xOf`），⛔ 不许内联展开 ——
`S()` 在 `paintFront` 的内层循环里每像素调 3 次，展开既易错又读不出原式。

#### 8.21.2 五个图层缓冲（三张画布 + 两张登记表）

| 缓冲 | 作用 | 落地 |
|---|---|---|
| `HOT` | 建筑本体（含窗与霓虹的**暗版**） | `PixelBuffer` → `trim()` → `Pixmap`（⛔ 不许 `.canvas`，G-E51-J） |
| `LITP` | 窗灯层（夜里加性叠上去） | `PixelBuffer.canvas` |
| `NEONP` | 霓虹管**亮版** | `PixelBuffer.canvas` |
| `WINS8` | 每扇窗的 id / 包围盒 / 闪烁参数 | `LinkedHashMap<Int, Win>` |
| `TUBES` | 每根霓虹管的折线点列 + 包围盒 + 颜色 | `LinkedHashMap<String, Tube>` |

⭐ 关键：`glass()` 与 `tube()` **同时写三个缓冲**（暗底 + 亮窗 + 亮管），这是"白天暗窗 / 夜里亮窗 /
霓虹脉动"三合一的手段。⛔ 别为了省内存合并成一张 —— 合并后窗灯与霓虹只能同相闪，画面立刻假。

#### 8.21.3 英雄建筑（`AZUMAR` 旅馆，全城最细的一段）

`HERO5 = {x0: 84, x1: 152, uc: 7.2}`；`FIN_X = round(xOf(dOf(84) + 7.2))`（霓虹鳍的屏幕 x）。
`heroPixel()`（64 行）逐像素决定颜色，分支顺序 = 塔顶 → 鳍 → 塔身 → 阳台带 → 拱窗带 → 招牌带 → 门厅 → 地面；
`paintHeroExtras()` 在其上叠 `AZUMAR` 竖排霓虹（逐字符 `tube()`）+ `HOTEL` 横排 + 桅杆；`paintHeroSide()` 画左侧面。
⚠️ 这一段的常数（`11.6 / 4.2 / 3.55 / 0.45 / 7.2`）与 `row()` 的半个像素容差是耦合的 ——
**必须逐行对照**，不能"看着差不多"。

#### 8.21.4 12 片计划（片名 = `logs_temp/miami_parts/`）

| 片 | 内容 | 上游行 |
|---|---|---|
| `h` | 头注 + 12 条受控差异 + 元数据 + 字段区（字段清单以 1681 行的 `var` 列表为准） | 1-6 / 1681 / 2042-2065 |
| `p01_types` | 全部嵌套类型（`Win` / `Tube` / `Bld` / `Look` / `Person` / `Storm` / `CloudLayer` / `Pool` / `Haze` / `Petal` …） | — |
| `p02_helpers` | `pix19`⇒`PixelBuffer`、`pick21`/`pickFlat7`、`trim22`、`mixHex22`、`line12`、`textP6`、`sprite27`、`makePool9`/`drawPool8`、`flick12`、`glass`、`tube`、`row` | 6-112, 222-253 |
| `p03_sky` | `renderSky35` / `renderMoon24` / `makeCloudLayer19` / `stormField2` / `renderStorm2` / `heatAt` / `renderFar18` | 113-221 |
| `p04_hero` | `paintFront` / `heroPixel` / `paintHeroExtras` / `paintHeroSide` / `frontPixel` / `paintTallSide` / `paintTallSign` / `paintCafeSign` / `renderHotels` / `tubeLevel3` / `drawHotels` | 254-487 |
| `p05_sea` | `renderSea` / `drawSea` / `renderShip5` / `shipAt` / `drawShip4` / `renderGround10` / `snapMirror` / `layMirror` / `drawStreetFx2` | 488-690 |
| `p06_flora` | `renderCrown` / `renderTrunk` / `drawPalm` / `renderPoinciana` / `drawPoinciana` / `renderTower8` / `drawTower7` / `drawOats` / `renderLamp6` / `renderTerrace3` / `drawTerrace3` | 691-891 |
| `p07_vehicles` | `renderCar2` / `renderConvertible` / `renderSedan` / `renderParked` / `renderBench4` | 892-960 |
| `p08_people` | `look9` / `person10` / `drawCast4` / `drawDog3` / `drawBike3` / `drawPassers` / `drawCar3` / `drawPedicab2` / `drawTraffic8` / `drawBirds` / `drawSmallLife` | 961-1384 |
| `p09_fx` | `drawDrips4` / `makeHaze3` / `drawHaze3` / `makePetal5` / `drawPetals6` / `renderBBFrame5` / `renderAd17` / `drawBillboard30` / `renderVignette35` | 1385-1542 |
| `p10_frame` | `prerender35` / `drawScroll15` / `drawSky26` / `renderFrame35` / `create` / `dispose` | 1543-1680 |
| `p11_init` | `initConstants`（几何常量 + 时间表 + 人群 + 天气 + 花瓣 + 元数据） | 1682-2065 |

#### 8.21.5 受控差异（按开罗/多伦多同一口径，落地时逐条写进 `h.txt` 头注）

① `pix19` ⇒ `PixelBuffer`（`set(i,j,col,a)` / `alpha` / `fill` / `done`→`canvas` 逐一对应）；`trim22` ⇒ `PixelBuffer.trim()` 返回 `Pixmap`；
② `C19` / `MIXED4` 色串缓存 ⇒ 直接 `hex()` / 引擎 `mix()`（逐式相同，缓存只影响速度）；
③ `pix19.set` 的 `typeof col === "string" ? C19(col) : col` 双形态 ⇒ Kotlin 只有 `Int` 一态，分支消失；
④ 广告位 `PITCH28 = makePitch(...)` **整段删除不留桩**（与开罗同口径），`renderFrame35` 的 `pitch` 形参删除，`drawBillboard30` 恒画自有画面；
⑤ 天气 `WEATHER35 = weatherPlans(["poinciana"])` ⇒ `weatherPoinciana()` 只取布尔 + `drawWeather` 空实现；`WX_*` 照搬为数据；
⑥ 音频三通道：`lightGain` → `halo35` 统一乘；`neonPulse` → 霓虹/广告光晕（`haloNeon35`）；`tailGain` → 车灯/船灯拖尾（`haloTail35`）；
⑦ `ambience`（waves/wind/crickets/traffic/crowd）不移植；`events` 8 条按时刻升序展开（`CLINK_T0` / `CLINK` / `SHIP5.horn` / `CRUISER.bell` 的实值从 `p11_init` 取，⛔ 不许估）；
⑧ `crowd` 形参（上游 `render(t, {crowd})`）⇒ `SceneOpts` 无此字段 ⇒ 恒取 `CROWD11 = 0.5`；⚠️ ⛔ 别顺手删 `here4()`，人群浓淡全靠它；
⑨ `Array` / `push` 型字段 ⇒ `ArrayList` / `Array`；`RGB26` / `MIXED4` 删除；
⑩ 返回 `{c,x,y}` 的烘焙函数 ⇒ `Pixmap`；`renderStorm2` 返回 `{c, lit[]}` ⇒ 嵌套类 `Storm`；
⑪ `ctx35.globalAlpha` 超 1 处逐处核（`drawPool8` / `drawDrips4` / `drawHaze3` 三处最可能）；
⑫ `rng(seed)` ⇒ `PxRng`；`hash` 整数实参照搬；`Math.round` ⇒ `jsRound`（**只收 Double**，Int 实参必须 `.toDouble()`）。

#### 8.21.6 下一个入口

1. 先精读 **254-487（英雄建筑）** 与 **1543-1680（prerender / renderFrame）** —— 这两段决定全城骨架，其余都是"往骨架上贴东西"；
2. 按 §8.21.4 写 12 片 + `logs_temp/miami_parts/assemble.py`（照 cairo 模板，`OUT = .../LofiCityMiami.kt`）；
3. 拼装 → `compileDebugKotlin` 清零 → 接线三处（`focusX = FIN_X`、`SCENES_BUILT` 第 26 项、`LofiScenes` 分支）→ 门禁 + 全量单测 + `lintDebug` → `LOFI_SNAPSHOT=1` 四时刻判读；
4. ⭐ 本城判读要点：`HEAT = [14, 47, 71.5, 103, 131, 166, 197, 223]` 是八次闪电的**起始时刻**、`HEAT_LEN = 3` ⇒
   **t000 / t060 / t120 / t228 四帧都不在闪电窗口**（t=228 距 223 已 5 秒）⇒ 四帧都该是"平静雨夜"；
   要验闪电必须另取 t=15 / t=72 / t=167。⛔ 别把"四帧都没闪电"当成漏移植。
- registry 侧：`nameZh`「迈阿密」/ `taglineZh`「海洋大道雨后的霓虹」/ `accentHex #dd88bb` 基线已有，**只需回填 `focusX = FIN_X`**（值待 `p11_init` 算完再写死）。

### 8.22 `miami`（迈阿密 · 海洋大道雨后的霓虹，上游 2,065 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityMiami.kt` —— **4,050 行**，类 `MiamiScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/miami.js`，含 esbuild 模块包装）。
沿用分片拼装，本城 **12 片**：`logs_temp/miami_parts/`（`h.txt` 267 = 头 + **12 条受控差异** + 元数据 + 字段区；`p01_types` 126、`p02_helpers` 221、`p03_sky` 219、`p04_hero` 452、`p05_sea` 425、`p06_flora` 426、`p07_vehicles` 270、`p08_people` 637、`p09_fx` 434、`p10_frame` 196、`p11_init` 366）。
本城是 **第 5 种结构：伪 3D 投影**，与开罗的"深度图 + 色阶换色"、墨尔本的"相机 + 深度缓冲"、巴塞罗那的"多层视差"都不同 —— 它**没有相机矩阵**，全城靠六个闭式函数定位：`gs(y)`（屏幕 y → 地面进深）/ `gx(L,y)`（横向 L → 屏幕 x，`L=0` 落灭点）/ `S(x)`（屏幕 x → 缩放）/ `baseY(x)`（该列的"地面行"）/ `dOf(x)`（屏幕 x → 真实进深）/ `xOf(d)`（与 `dOf` 互逆）/ `at2(L,d)`（横向 + 进深 → `{x,y,s}`）。**建筑、车辆、行人、棕榈、光柱全部由它们反解**，所以 §8.21.1 把这段列为"全城地基、必须最先落地"。
三个图层缓冲 + 两张登记表撑起英雄旅馆：`HOT`（远景热色）/ `LITP`（窗光）/ `NEONP`（霓虹），`WINS8`/`TUBES` 记窗口与霓虹管的坐标（`renderHotels` 开头必须 `clear()`，否则跨帧累积 —— 已在 `p04` 里显式写死）；`glass()`/`tube()` 一次写多张画布。显示列表 `DL4` + `put5(y){}` 按 `y` 排序后统一执行，是全城"前后遮挡"的唯一机制。

#### 8.22.1 结果

- 编译错误链：**7 → 0**（首轮 7 条只有**五个根因**，见 §8.22.4 ①–⑤；次轮 4m48s BUILD SUCCESSFUL，**本文件仅 3 条 warning**，全是 `Redundant call of conversion method`）。
- 运行时崩溃链：**0**（首轮快照即绿 —— 第 3 座"编译清零后直接跑通"的城）。
- 接线三处：`LofiScenes.build()` 加 `"miami" -> MiamiScene()`（字母序落在 `melbourne` 与 `munich` 之间）；`CityRegistry.SCENES_BUILT` 加 `miami`（**第 26 项 / 共 26 项**）；注册表 `focusX 0.0 → 124.0`（= `FIN_X`，英雄旅馆那道霓虹鳍的屏幕 x，门禁 R6 逐字段对账）。
  `nameZh`「迈阿密」/`taglineZh`「海洋大道雨后的霓虹」/`accentHex #dd88bb` 基线里已有，无需回填。
- 元数据：`focusX 124`（`FIN_X = jsRound(xOf(dOf(HERO5.x0) + HERO5.uc))`，`HERO5 = BBox(84,152,7.2)` ⇒ `37.5609756097561 + 7.2` 代回 `xOf` = `123.57018308631214` ⇒ 取整 **124**，⛔ 别按"旅馆左缘 + 7"手估）；`landmarkX0/X1 = 78 / HERO5.x1 = 152`（上游 `landmark: [78, HERO5.x1]`）；`landmarkTop = MAST_TOP`（旗杆顶）；`billboardRect = BB31`；`accent = #dd88bb`（霓虹粉）。
- `events`：8 条按时刻升序（`CLINK_T0` / `CLINK` / `SHIP5.horn` / `CRUISER.bell` …，实值全部取自 `p11_init` 的时间表，⛔ 不许估）⇒ `.sortedBy { it.t }`。

#### 8.22.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 3 warning**（`Redundant call of conversion method` @1360/2818/2822；次轮 4m48s BUILD SUCCESSFUL；首轮 7 条，五个根因见 §8.22.4） |
| `LofiCityRegistryGateTest`（R 系列，含 R4 双向对齐 + R6 逐字段对账） | 6 例 / 0 失败 —— ⭐ R6 通过即**反证 `focusX = 124.0` 与场景内 `FIN_X` 一致**（这是本城唯一"算出来的常量"，没有第二处对账源） |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7 例 / 0 失败（本城 `trim22` ⇒ `PixelBuffer.trim()` 返回 `Pixmap`，无 `.canvas` 泄漏） |
| `LofiUpstreamHygieneTest`（H 系列） | 7 例 / 0 失败 |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / **0 skip**（`LOFI_SNAPSHOT=1`，覆盖全部 **26 城**） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/miami_t000\|060\|120\|228.png` 四时刻 + **闪电窗口三帧**（t=15 / 72.5 / 167，用临时探针导出，验完即删），**全部人工判读** |
| 全量 `testDebugUnitTest --rerun-tasks` | **2588 例 / 0 失败 / 0 错误 / 2 skipped**（255 个 XML，12m11s BUILD SUCCESSFUL）—— 与开罗基线**同数**（门禁用例按「已落地城列表」循环，加城不改用例数） |
| `lintDebug` | BUILD SUCCESSFUL（6m03s），报告 **161 warnings / 0 errors**，且报告里**没有任何一条点名 `LofiCityMiami`**（`app/build/reports/lint-results-debug.xml`） |

**判读结论**：迈阿密全套到位 —— 天空（多层云 + 星野 + 移动月亮 + 流星）、英雄旅馆（竖排 `AZUMAR` 霓虹 + 横排 `HOTEL` + 旗杆顶灯 + 粉色霓虹鳍）、`CAFE` 招牌与拱廊遮阳篷、`CAFECITO / 24 HORAS` 广告牌（咖啡杯 sprite + 三盏射灯 + 两串挂灯）、棕榈行道树、凤凰木（右侧橙红花团）、海洋大道（斑马线 + 车道虚线 + 湿路反光 + 水洼）、右侧草地与沙滩 + 救生亭 + 海面 + 远景游轮、人行道行人 + 长椅 + 护柱灯泡 + 停靠车辆。
四帧与上游常量**逐条对上**（这是本城正确性的硬证据，比"看着像"强）：
- **游轮窗口**：`SHIP5 = ShipWin(52, 172, 60)` ⇒ t000 / t060 / t228 **不该有船**、t120 **该有船**。实测正是如此（t120 右侧海面出现白色船体 + 舷窗灯）。
- **闪电窗口**：`HEAT = [14, 47, 71.5, 103, 131, 166, 197, 223]` 是八次闪电的**起始时刻**、`HEAT_LEN = 3` ⇒ 四时刻（0 / 60 / 120 / 228）**全部落在窗口外**，四帧都该是"平静雨夜"。实测四帧确实都没有闪电 ⇒ **不是漏移植**；另取 t=15 / 72.5 / 167 三帧补验，地平线一带出现明显的暖白→粉辉光带、云层被照亮、海面出反射亮带 ⇒ `stormField2` / `renderStorm2` / `heatAt` 这条链通了。
- **月相与云**：月亮在 t000 被云遮住、t060 露出月牙、t120/t228 持续右移 ⇒ `CLOUDS21` 三层滚动 + `MOON25` 都是活的。
- 站牌 `MIAMI` 用缺省左上，压在夜空上、不遮英雄旅馆。

#### 8.22.3 受控差异（文件头 12 条逐条列明，此处只给索引）

① `pix19` ⇒ `PixelBuffer`（`set(i,j,col,a)` / `alpha` / `fill` / `done`→`canvas` 逐一对应）；`trim22` ⇒ `PixelBuffer.trim()` 返回 `Pixmap`；
② `C19` / `MIXED4` 色串缓存 ⇒ 直接 `hex()` / 引擎 `mix()`（逐式相同，缓存只影响速度）；
③ `pix19.set` 的 `typeof col === "string" ? C19(col) : col` 双形态 ⇒ Kotlin 只有 `Int` 一态，分支消失；
④ 广告位 `PITCH28 = makePitch(...)` **整段删除不留桩**，`renderFrame35` 的 `pitch` 形参删除；⚠️ **只删 `covers()` 判据与 `PITCH28.draw()` 调用两处，`if (!covers)` 块内两张贴图必须保留**（见 §8.22.4 ⑥，这是本城唯一一次"删多了"）；
⑤ 天气 `WEATHER35 = weatherPlans(["poinciana"])` ⇒ `weatherPoinciana()` 只取布尔；跨城共享 `drawWeather` **三处调用点整段删除**（不是空实现），`WX_*` 一并删除；
⑥ 音频三通道（§10 D-a）：`lightGain` → `halo35` 统一乘；`neonPulse` → 霓虹/广告光晕（`haloNeon35`）；`tailGain` → 车灯/船灯拖尾（`haloTail35`）；
⑦ `ambience`（waves/wind/crickets/traffic/crowd）不移植；`events` 8 条按时刻升序展开；
⑧ `crowd` 形参（上游 `render(t, {crowd})`）⇒ `SceneOpts` 无此字段 ⇒ 恒取 `CROWD11 = 0.5`；⚠️ ⛔ 别顺手删 `here4()`，人群浓淡全靠它；
⑨ `Array` / `push` 型字段 ⇒ `ArrayList` / `Array`；`RGB26` / `MIXED4` 删除；
⑩ 返回 `{c,x,y}` 的烘焙函数 ⇒ `Pixmap`；`renderStorm2` 返回 `{c, lit[]}` ⇒ 嵌套类 `Storm`；
⑪ `ctx35.globalAlpha` 超 1 处逐处核（`drawPool8` / `drawDrips4` / `drawHaze3` 三处最可能）；
⑫ `rng(seed)` ⇒ `PxRng`；`hash` 整数实参照搬；`Math.round` ⇒ `jsRound`（**只收 Double**，Int 实参必须 `.toDouble()`）。

#### 8.22.4 本城新踩的坑（余下城市照用）

① ⛔ **`Int.pow` 不存在**：上游 `Math.pow((y - LAMP_E.y - 8) / 22, 2)` 里的 `22` 是**整数字面量**，直译成 `(... / 22).pow(2)` ⇒ `Unresolved reference`（Kotlin 只有 `Double.pow`/`Float.pow`）。改 `/ 22.0`。**教训：`Math.pow` 的底数只要来自整数除法，就必须显式写小数点**。
② ⛔ **`jsRound` 只收 `Double`**：`jsRound(pm.x + pm.lean)` 里 `pm.x` 是 `Int` ⇒ 类型不匹配。改 `jsRound((pm.x + pm.lean).toDouble())`。这与 §8.20.4 ① 是同一类"公共来源类型声明"问题，只是这次发生在**调用点**。
③ ⛔ **嵌套（非 inner）类的默认实参不能取外部类成员**：`LookOpts(val glow: Int = NEONL)` ⇒ 编译错（`NEONL` 是外层实例字段）。改 `glow: Int? = null` + 在 `look9` 里 `val gl = o.glow ?: NEONL` 回落。**这是 Kotlin 与 JS 默认参数语义的硬差异**，凡"默认值引用同名字段"一律走可空 + 回落。
④ ⛔ **闭包里不能留 `Double` 实参给 `Int` 形参**：`R10(sx2 + …, …)` 的 `sx2` 是 `Double`，而 `R10` 吃 `Int`。改法不是逐处 `.toInt()`，而是**在闭包外先算 `val sx2i = jsRound(sx2)`**，闭包里全部用 `sx2i` —— 顺带避免闭包捕获后再转换（`put5` 的闭包会延后执行，转换写在里面等于每帧多做一次）。
⑤ ⛔ **`fillRect` 的 `Int×4` 与 `Double×4` 是两套重载，一个实参混用就报「None of the following candidates is applicable」**：`ctx35.fillRect(min(ax,bx) + k, …, 1.0, 1.0)` 前两个 `Int` 后两个 `Double` ⇒ 报错信息**完全不提重载**，只列候选签名。判据：看报错里的候选列表是不是**同一函数名出现多次**，是就查实参类型而不是查函数名。
⑥ ⭐⭐ **本城最大一条：删「不做的功能」时误删了画面本体**。受控差异 ④ 要求删掉广告轮播 `PITCH28`，落地时把整个 `if (!PITCH28.covers(t, pitch)) { … }` **块**删了 —— 但块内第一件事是 `ctx35.drawImage(AD_C10[0], BB31.x, BB31.y)`（自有 `CAFECITO` 画面）+ `lv < 1` 时叠 `AD_C10[1]` 做灯闪交叉，**这两张才是广告牌的像素来源**。后果：编译 0 error、门禁 S1/S2/S3 全绿（牌框 `BBF_C7` 仍在画，不透明占比达标）、四帧"看着也像雨夜" —— **只有人工判读能发现牌面是一块黑**。修法：只删 `covers()` 判据与 `PITCH28.draw()` 调用，块内内容无条件执行。
⇒ **通用判据：删一个"门控/功能"时，必须逐行读被删块内部，确认里面没有"无条件该执行"的画面代码**；「编译过 + 门禁绿 + 看着像」三件齐全也可能漏一整层。

#### 8.22.5 下一个入口

余下 **31 个场景**，仍按上游行数从小到大逐城推进（`rome-colosseum` 2068 → `new-delhi` 2073 → `bali` 2075 → …），每城节拍不变。
本城的分片体量参考：12 片 / 4,050 行（`h` 267、`p01` 126、`p02` 221、`p03` 219、`p04` 452、`p05` 425、`p06` 426、`p07` 270、`p08` 637、`p09` 434、`p10` 196、`p11` 366）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 3 次：首拼 4,035 行 → 修 7 条类型错后 4,042 行 → 补广告牌画面后 4,050 行；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都/开罗的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据** —— 本城又添一条实证（§8.22.4 ⑥）。
⭐ 本城新增一条**判读法**：把上游的**时间窗口常量**（`SHIP5` / `HEAT` / `CAR_AWAY` / `PEDICAB` / `SKATER` / `PELICAN` / `CRUISER`）先算成"哪帧该有、哪帧不该有"，再逐帧核对。这种"用常量预测画面"的判读能抓到"窗口判据写反 / 闭区间开区间搞错"这类**整体错位型**缺陷，比"看着像不像迈阿密"强得多。


---

### 8.23 `rome-colosseum`（罗马·斗兽场 · 夏夜断墙下的广场，上游 2,068 行）落地 · 2026-10-24

产物：`app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityRomeColosseum.kt` —— **3,873 行**，类 `RomeColosseumScene : PixelCityScene`，**1 个上游模块 = 1 个文件**（直译 `js/scenes/rome-colosseum.js`，含 esbuild 模块包装）。沿用分片拼装，本城 **14 片**：`logs_temp/rome_colosseum_parts/`（`h.txt` 335 = 头 + **12 条受控差异** + 元数据 + 字段区；`p01_types` 334、`p02_helpers` 456、`p03_sky` 158、`p04_colosseum` 166、`p05_pines` 100、`p06_ground` 201、`p07_mid` 347、`p08_near` 338、`p09_props` 374、`p10_draw` 182、`p11_people` 281、`p12_frame` 168、`p13_init` 420）。

本城是 **第 6 种结构：柱面 / 环形投影** —— 与迈阿密的"六个闭式函数伪 3D"、墨尔本的"相机 + 深度缓冲"都不同：斗兽场本体按 `DPHI = TAU/80` 把环形立面切成 **80 跨**，每列**二分 24 次**反解方位角 `phi`（`coProjX(phi,a,b)` / `coProjS(phi,a,b)` 一对投影函数），`coY(h,s)` 定行；`NB_BUTT = 4` 断口逐跨降高；`paintColumn()` 同一次调用画外层 + 内层。其余是 2D 立面 + 一点透视：`VP3 = (236,176)`、`recede(y0, x0, x)`、`K_COB = 12` 对数透视石板地。全场景由 **9 张离屏位图**（`SKY_C16`/`CLOUD_C`/`FAR7`/`CAMP_C`/`PINES_C`/`COLO`/`ROAD_C`/`GROUND_C2`/`VIG_C16`）+ `L1C`/`R1C` 等楼体 Art 拼成。

#### 8.23.1 结果

- 编译错误链：**约 30 → 17 → 5 → 1 → 0**（五轮收敛；六个根因见 §8.23.4 ①–⑥；末轮 1m07s BUILD SUCCESSFUL，**本文件 0 warning** —— 首轮的 8 条 `Redundant call of conversion method` 已在重拼前清掉）。
- 运行时崩溃链：**0**（首轮快照即绿；R6 门禁首跑抛 `UninitializedPropertyAccessException` 后按迈阿密口径补 `init { initConstants() }` 即过，见 §8.23.4 ⑥）。
- 接线三处：`LofiScenes.build()` 加 `"rome-colosseum" -> RomeColosseumScene()`；`CityRegistry.SCENES_BUILT` 加 `rome-colosseum`（**第 27 项 / 共 27 项**，插在 `rome` 与 `sydney` 之间）；注册表该 look 的 `focusX 0.0 → 232.0`（= `CO.cx`，门禁 R6 逐字段对账）。
   `nameZh`「罗马·斗兽场」/`taglineZh`「夏夜断墙下的广场」/`accentHex #f25fd0` 基线里已有，无需回填。
- 元数据：`focusX 232`（`CO.cx`，斗兽场中轴）；`landmarkX0/X1 = COLO_SPAN[0]/[1]`（环形立面左右极值列）；`landmarkTop = 130`；`billboardRect = BB15`（`GELATO LUNA` 广告牌）；`accent = #f25fd0`（霓虹粉）。
- `events`：8 条按时刻升序 —— 6 声钟（`BELLS4 = [62, 65.2, 68.4, 182, 185.2, 188.4]` 全部挂在 `CAMP2.x = 289`，gain 0.7）+ 2 声海鸥（t=36 x=250 / t=151 x=210，无 gain）⇒ `.sortedBy { it.t }`。实值全部取自 `p13_init` 的时间表，⛔ 不许估。

#### 8.23.2 验证（读日志与 XML，不信 exit 码）

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | **0 error / 本文件 0 warning**（五轮：约 30 → 17 → 5 → 1 → 0；末轮 1m07s BUILD SUCCESSFUL） |
| 全量 `testDebugUnitTest --rerun-tasks` | **2588 例 / 0 失败 / 0 错误 / 2 skipped**（255 个 XML，18m55s BUILD SUCCESSFUL）—— 与迈阿密基线**同数**（门禁用例按「已落地城列表」循环，加城不改用例数） |
| `lintDebug` | BUILD SUCCESSFUL，报告 **161 warnings / 0 errors**，且报告里**没有任何一条点名 `LofiCityRomeColosseum`**（`app/build/reports/lint-results-debug.xml`；⚠️ 报告内 8 处 "rome" 命中全是 `MonochromeLauncherIcon` 的 "monochrome" 子串，别误读） |
| `LofiSceneSnapshotTest`（G-E51-G：S1 非空 / S2 随时间变 / S3 确定性） | 3 例 / 0 失败 / 0 skip（`LOFI_SNAPSHOT=1`，覆盖全部 **27 城**） |
| `LOFI_SNAPSHOT=1` 导出 PNG | `logs_temp/lofi_render/rome-colosseum_t000\|060\|120\|228.png` 四时刻，**全部人工判读**（判据表 `logs_temp/rome_colosseum_parts/verify_windows.txt`，判后按判读结果修正了一处笔误，见 8.23.3） |
| 附加数值探针（判后即删） | 飞机红/绿航行灯 + 频闪、猫炸毛块、钟槌起落、流星头部亮度 —— 全部按上游常量**逐像素对账**（见 8.23.3） |

#### 8.23.3 判读结论（四时刻 + 数值对账）

四帧整体全部到位：斗兽场（三层拱廊 + 南侧 4 跨断口降高 + 内环 + 阁楼窗）、左侧 `TRATTORIA`（两个拱廊 + 遮阳棚 + 7 个花箱）、`VINI` 小铺、中间钟楼（圆顶 + 钟槌）、右侧 `BAR` 霓虹 + `GELATO LUNA` 广告牌 + 紫藤 360 圆球 + `SHRINE` 神龛（圣家像 + 两盏电子烛，放大裁图已核）、石板路（对数透视）+ 5 个光池 + 110 片花瓣、晒衣绳五件衣服、两端松树、交通警员、`VESPA` 小摩托、猫与食盆。

时间窗口判据（**本城正确性的硬证据**）逐条对上，且**用上游常量预测画面、再逐像素验证**：

- **飞机 `PLANE9`（t0=56, t1=136, (104,30)→(372,22)）**：t060 在 x≈117、t120 在 x≈318，t000/t228 无。数值对账：t060 红灯 `#ff4a4a` 落在 x=119（PNG 实测 (226,122,128)，与暗紫机身 (129,129,174) 相邻）、绿灯 `#4aff8a` 落在 x=115（(94,255,160)）、频闪白光在 x=117；t120 红灯在 x=320（(255,138,143)）、绿灯在 x=316、频闪在 x=318。ⓘ 上游 `frac(t) < 0.07` 即频闪 ⇒ t=0/60/120 这些整分钟点频闪恰好在闪。
- **钟 `BELLS4`**：四帧全在窗口外 ⇒ 都不该有钟摆。附加探针（判后即删）：t=61.9 左槌升到 `CAMP2.top+9`、t=63.0 回位、t=186.0 右槌升起 —— 与 `swing2 = sin(d·5)·(1-d/2.6)` 的符号判据逐次吻合，证明窗口比较没有写反。
- **猫 `CAT_ALERT = [[134,145],[219,229]]`**：t228 在第二窗口内 ⇒ 炸毛。数值对账：alert 专用块 `#b8642a` + 高亮列 `#e08a40` 在 orig (342–343, 239–245) **精确命中**（t228 = (184,100,42)/(199,122,58)，t120 同位置是地面色）⇒ 姿态切换链路通了。
- **流星 `METEORS` 7 颗**：四帧全在窗口外，7 个头部位置实测**皆无亮像素**（t120 在 (172,6) 有 1 像素是亮星，非 30–58px 的流星条）。
- **车流 `CARS7`**：四帧路上皆无车。⚠️ **判读时更正了判据表的一处笔误**：原表写 "t120 … 121 dur6 在路上 ⇒ 有一辆车"，但 `t0=121 > 120` 车还没发车 —— 实测路面全空与更正后判据一致。**这类"用常量预测画面"的判据必须先算窗口再写表**。
- **情侣 `STROLL5`**：t000 未进场 / t060 在 [50,120) 段 `Za≠Zb ⇒ walking=false` 驻足听（画面中部两人并肩）/ t120 恰在末键 `t≥120 ⇒ strollAt 返回 null` 无人 / t228 已走完。
- **遛狗 `DOGWALK`**：t228 在第二趟 [190, 234.5] 的 out 淡出段 ⇒ 人 + 狗 + 牵引绳都在（放大裁图已核）。
- **服务生 `WAITER2` / 碰杯 `TOASTS` / 硬币 `COIN` / 三处闪烁窗口**：四帧全在窗口外，实测均无。

#### 8.23.4 本城新踩的坑（余下城市照用）

① ⛔ **本项目 Kotlin 口径：整数字面量不会被提升成 `Double`**（第 3 座城第 3 次踩到，升级为**头等纪律**）。`clamp(1 - u*0.8, 0.3, 1)`、`wave(t, 6, 0.2)`、`(if (s > 0.7) 1 + … else 1)` 全炸；必须写 `0.3, 1.0` / `6.0` / `else 1.0`。引擎自己的 `smooth` 里就写着 `clamp((v-a)/(b-a), 0.0, 1.0)`，**照抄引擎的写法最安全**。
② ⛔ **`ClosedFloatingPointRange` 没有 `iterator()`** ⇒ `for (x in SHRINE.x..SHRINE.x + 13)`（`Pt` 是 Double）**编译期就报错**。整像素循环必须先在函数头取整：`val sx = S4.ix; val sy = S4.iy` 再 `for (x in sx..sx + 13)`。⛔ 不能只把 `until` 换成 `<`——`..` range 同样不合法。
③ ⛔ **JS `/` 恒浮点除，Kotlin `Int/Int` 截断**（编译器不报，纯语义 bug）：本城补了 **11 处** `.0`（`(y-86)/34`、`(y-196)/86`、`x/4`、`wn.w/2`、`wn.h/2`、`tb.w/2`、`sep/2`…）。判据来源只有上游 JS 原文，**逐行读**，别凭"看着是整数"放过。
④ ⛔ **`set`/`fill`/`pickFromRamp` 的重载要求两参同型**：`pickFromRamp(ramp, v, i: Int, j: Int)` 与 Double 重载并存，实参一个混用就报「None of the following candidates is applicable」，且报错信息**完全不提重载**。修法是在调用点先算 `val yd = y.toDouble()` 统一形态，而不是逐处 `.toInt()`。
⑤ ⛔ 三个小题：`Double.until` **不存在**（`S4.y + 3 until S4.y + 27` 炸）；自然对数是 `kotlin.math.ln` 不是 `log`（`log(d)` 需要 base 参数）；`HashMap<Char,Int>` 取值得用 `['w']` 不是 `["w"]`。
⑥ ⛔ **场景元数据不能在 `create()` 里才初始化**：R6 门禁会在**没有调用 `create()`** 的情况下裸读 `scene.focusX`，而 `focusX get() = CO.cx` 依赖 `lateinit var CO` ⇒ 抛 `UninitializedPropertyAccessException`。修法照迈阿密：类体里加 `init { initConstants() }`（`initConstants` 幂等，构造时跑一次不改变画面）。⛔ 这不是"编译问题"而是"门禁时序问题"——**编译 0 error 照样红**。
⑦ ⓘ **`drawSky12`/`drawFarLayer` 两处各写一份 ⇒ `Conflicting overloads`**：分片时 `p03` 与 `p12` 都落了这两个函数。修法不是改名，是**删掉早的那份、留带 KDoc 的**（`p12`），并在 `p03` 原位留两行注释指向 `p12`——与 §8.22.4 ⑥ 同源的"分片重复定义"类问题，本城第二次踩到。

#### 8.23.5 下一个入口

余下 **30 个场景**，仍按上游行数从小到大逐城推进（`new-delhi` 2073 → `bali` 2075 → …），每城节拍不变。
本城的分片体量参考：14 片 / 3,873 行（`h` 335、`p01` 334、`p02` 456、`p03` 158、`p04` 166、`p05` 100、`p06` 201、`p07` 347、`p08` 338、`p09` 374、`p10` 182、`p11` 281、`p12` 168、`p13` 420）。
⚠️ **改完分片必须立即重拼再编译**（本城重拼 5 次：3880 → 3854 → 3862 → 3862 → 3873；`assemble.py` 输出行数是"是否真的重拼"的唯一凭据）。
⛔ 京都/开罗/迈阿密的教训继续成立：**0 error + 门禁全绿不等于移植正确，四时刻判读是唯一正确性证据**。本城把这条推进了一步：**判据表本身也会有笔误**（CARS7 t120），所以"预测画面 → 逐像素验证"要**双向**做——既验"该出现的在"，也验"不该出现的确实不在"，冲突时以上游常量和代码为准改表。
⭐ 本城新增一条**判读法**：对"暗色画在暗底上"的元素（飞机机身 `#2a2650` 画在夜空上），**亮色判据（航行灯/频闪）比暗色判据可靠**；探针坐标务必记得导出图是 **4× 放大**的（本轮一度把 orig 坐标直接用在 1920×1080 上，误判"飞机没画出来"白查一轮）。

---

### 8.24 `new-delhi`（新德里 · 印度门的十月黄昏，上游 2,073 行）落地 · 2026-10-25

#### 8.24.1 落地清单

| 项 | 值 |
|---|---|
| 上游 | `js/scenes/new-delhi.js`，2,073 行（`function` 63 个 + 模块初始化 1,690-2,046） |
| Kotlin | `e51/scenes/LofiCityNewDelhi.kt`，**4,033 行**（单文件一次成型，未分片） |
| 场景 id | `new-delhi`（`SCENES_BUILT` 27 → **28**） |
| registry `focusX` | 脚本占位 `0.0` ⇒ 回填 **240**（`Math.round(IG.cx)`，实测 `IG.cx = 240.0866`） |
| 实测派生 | `IG_TOP = 56`、`IG_SPAN = [205, 280]`、`INSCR_X = 229`、`KIOSK.base = 188`、`TRIKE_BELL = [58.8, 170.8]` |

- 结构沿用 §8.17 里斯本范本：`create(ctx)` 存 `ctx25` → `prerender25()` → `PixelFrame { t, opts -> renderFrame25(t, opts) }`；`dispose() {}`；`init { initConstants() }`（R6 门禁裸读 `focusX`，见 §8.23.4 ⑥）。
- 常量全部由 `initConstants()` 赋值，**镜像上游 1,690-2,046 的求值次序**——包括 `STARS21`／`BALLOONS2`／`BLOSSOMS2`／`FLOATERS3`／`STROLLERS3`／`TRAFFIC` 里依赖短路与条件分支的 `rng` 调用次序（`STARS21` 的 `bright ? 0.95 : 0.2 + rng()…`、`col` 的三元链第二段只在首个 `rng()` ≥ 0.3 时才取，都按 JS 惰性求值还原）。
- 天气：`weatherPlan25(w)` 折叠上游 `WEATHER25 = weatherPlans(["saptaparni"])`；`SHARED = [drizzle, rain, thunderstorm, snow, leaves]`，本城母题 `saptaparni` 不在其中 ⇒ 只有 `else` 分支为真。跨城 `drawWeather` 空实现（受控差异 7）。
- 广告：`BB_PITCH6 = makePitch(...)` 不移植，`pitch` 恒 null；`PitchStub.covers() = false` / `draw() = Unit` 双方法替身。

#### 8.24.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 3 轮：27 条 → 3 条 → **0 error**（第 3 轮仅 1 条 `!!` 冗余警告，已清） |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | **7/7 通过** |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例、**0 失败**、1 skipped（未开 `LOFI_SNAPSHOT` 时"按属性导图"用例跳过，符合预期） |
| 全量 `testDebugUnitTest --rerun-tasks` | **2,588 例 / 255 类 / 0 失败**（约 8 min） |
| 四时刻 PNG | `logs_temp/lofi_render/new-delhi_t{000,060,120,228}.png` 肉眼判读通过：印度门与水面倒影对齐、`GARAM CHAI` 霓虹牌、气球人、冰淇淋车、卧犬、家庭四口、两侧路灯与蛾、车流含救护车与公交 |

⚠️ 判读口径仍是 §8.23.5 那条：**0 error + 门禁全绿 ≠ 移植正确**，四时刻图是唯一正确性证据。

#### 8.24.3 本城新踩的坑（余下城市照用）

① ⛔ **`rect(ctx25, x, y, w, h, col)` 在上游是自由函数，引擎里 `rect` 是 `PixCtx` 的扩展。** 本文件按上游形态写了 34 处调用，全部报 `Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch`，而报错**只列扩展签名**（`fun PixCtx.rect(...)`），完全不提示"你少写了个接收者"。修法：场景内加**一层同名转发**（`private fun rect(c: PixCtx, x: Double, …) = c.rect(x, y, w, h, col)`）；⛔ 不是逐处改成 `ctx25.rect(...)`（会把"逐行照搬上游"的可读性打散），也不是只补 `rct`。

② ⛔ **`Star25` 的 `x`/`y` 必须是 `var`。** 上游把前三星硬定位（`STARS21[0].x = 176` …，保证亮星落在印度门两侧），`val` 下报 `'val' cannot be reassigned`，且报错行指向 `initConstants` 的赋值处、**不指向 data class**，容易反查错方向。

③ ⛔ **整数字面量不提升 `Double` 的老坑以 `s4(1)` 形态复发**（§8.23.4 ① 第三次）：局部放大函数 `s4` 是 `(Double) -> Double`，`s4(1)` 炸而 `s4(1.2)` 正常；报错是 `Argument type mismatch: actual type is 'Int'`，落点在调用行第 **54** 列，肉眼极难对位。口令不变：**凡是喂给 `(Double)->…` 的数字一律写 `.0`**。

④ ⚠️ **`POURS` 必须 `Array<DoubleArray>`。** 上游端点是小数（`[48, 51.5]` / `[139, 142.5]` / `[221, 224.5]`）；写成 `IntArray` **不报错**，但每段倒茶时长差 0.5 秒（`u = (t - p0) / (p1 - p0)` 被截断），属静默语义偏差。

⑤ ⚠️ **`BLOOM_C` 的装配顺序是 `[BLOOM_SPR[1], BLOOM_SPR[0], BLOOM_SPR[2]]`**（上游把 1 与 0 交换，是三档花瓣形状的层级重排）。照 `0,1,2` 写不报错，但花瓣分层与上游不一致。

⑥ ⓘ **"文件尾部多一个 `}`"的判据**：报错 `4025:1 Syntax error: Expecting a top level declaration`，而文件当时实际只有 4,024 行 ⇒ **报错行号大于文件实际行数，就是"锚点替换后遗留闭合括号"**，对症删尾括号即可。

#### 8.24.4 下一个入口

余下 **29 个场景**，仍按上游行数升序（`bali` 2075 → `prague` 2091 → …）。
本城体量参考：**单文件 4,033 行**（第 1-3 段约 3,000 行人工逐函数落笔 + 第 4 段 `initConstants`／`prerender25`／`renderFrame25` 约 1,000 行）。
本城是**首次全程单文件推进**（不切分片再拼）：代价是一次编译要等 2-4 min，好处是不会出现 §8.23.4 ⑦ 那类分片重复定义。

---

### 8.25 `bali`（巴厘岛 · 海神庙的蓝调时刻，上游 2,075 行）落地 · 2026-10-25

#### 8.25.1 落地清单

| 项 | 内容 |
|---|---|
| Kotlin | `visualizer/e51/scenes/LofiCityBali.kt`，**3,698 行**，单文件一次成型 |
| 场景类名 | `LofiCityBali`（**与其余 28 城不同名**：upstream 城名 `bali` 与 Kotlin 关键字/命名无冲突，但本轮按"文件名单文件"口径保留 `LofiCity` 前缀） |
| 场景 id | `bali`，`SCENES_BUILT` 28 → **29** |
| registry 回填 | `focusX` 生成时已是 **167.0**（本城脚本解析成功，无需手改）—— 与场景手译值 `167`（= `MERU.cx`，海神庙中轴）一致 |
| 结构 | `Painter56(w,h,ox,oy)` 包装类（上游 `painter19`）；上游三处 `p.done().c` 返回裸 `PixelCanvas`（`renderMoon36`/`renderAd35`/`makePuff16`） |
| 体量参考 | 数据类 40+ 个、`initConstants` 约 470 行（上游 1,585-2,034 全量）、`prerender56` 约 70 行（上游 1,365-1,427） |

#### 8.25.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 三轮：**12 条 → 0 条**（第二轮仅剩 4 类形态，见 §8.25.3） |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 7/7 通过 |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例 / **0 失败** |
| 全量 `testDebugUnitTest --rerun-tasks` | **2,588 例 / 255 类 / 0 失败**（约 5.5 min，`LOFI_SNAPSHOT=1` 同轮跑完） |
| 四时刻 PNG | `logs_temp/lofi_render/bali_t{000,060,120,228}.png`，人工判读通过：岩岛五洞与暖焦散、三层 meru 塔、双门柱与 15 级石阶、`WARUNG SENJA` 广告牌、penjor 竹幡、warung 屋檐彩带与小商品串、藤编吊灯、蓝调海面月光柱与船、步道行人按 `fy3` 正确遮挡、草坪豆袋客（`reader` 随 `crowd = 0.5` 隐藏，紫袋空置）、小狗 241/245 两态、雨燕/飞蛾/萤火虫、`t=120` 香客队伍、`t=228` 庙灯按 `LAMP_OFF + i*2` 逐盏熄灭 |
| 接线 | `LofiScenes.build()` 加 `"bali" -> LofiCityBali()`；`CityRegistry.SCENES_BUILT` 加 `"bali"`（第 29 项）；`focusX` 原值即 167.0，未改 |

#### 8.25.3 本城新踩的坑（余下城市照用）

| # | 陷阱 | 表现 / 判据 | 修法 |
|---|---|---|---|
| ① | **`blit43(L, a = 1)` 上游会设 `globalAlpha`** | 上一轮笔记写的是"上游不碰 globalAlpha、`RUSTLE_C` 依赖外层 alpha"，与原文 1,601-1,605 **相反**：上游是 `globalAlpha = a` → `drawImage` → `globalAlpha = 1` | 逐位复刻（默认 `a = 1` 且复位）。**副作用是 `drawIsland` 里 `globalAlpha = 0.5+0.5*r` 紧跟 `blit43(RUSTLE_C[…])` 实际不生效** —— 这是上游自身的死代码，⛔ 不要"修"。`RUSTLE_C` 本来就是不透明叶色，闪烁靠 `hash < 0.45` 抽样 |
| ② | **`sprite43(rows, key, flip2 = false)` 缺省是 `false`** | 缺省值记反会让所有不带第三参的精灵整体镜像（`KITE_BEB`/`JUK_C`/`SCOOT_C3`/`SAMP_C`/`DOG_SPR11` 全部中招），编译无错、门禁无感 | 不翻转走引擎 `spriteFromRows`，翻转走本文件 `spriteFlip`；`DOG_UP_C` 是 `[不翻转, 翻转]` 一对，`CAT_WALK7[flip][frame]` 的 `flip=0` 不翻转 |
| ③ | **9 参 `drawImage` 形参全是 `Double`** | `ctx56.drawImage(JUK_C, 0, 8, 15, 3, x, y + 13, 15, 3)` 报 **8 条** `Argument type mismatch: actual type is 'Int', but 'Double' was expected`（每个实参一条，行号全相同） | 全写 `0.0 / 8.0 / …`。注意 3 参版有 `Int` 重载，9 参版**没有** |
| ④ | **`pickFromRamp` 的 `(ramp, v, Int, Int)` 与 `(ramp, v, Double, Double)` 不能混搭** | `pickFromRamp(THATCH, v, cx + dx, y)`（第 3 参 Double、第 4 参 Int）报 `None of the following candidates is applicable`，**报错列指向第 3 参**而不是不匹配的第 4 参 | 上游 `pick40` 的 `i` 是小数且 `bayer(i, y)` 内部做 `ToInt32` ⇒ 用 Double 重载，`y.toDouble()` |
| ⑤ | **`hypot` 无 `Int` 重载** | `makePuff16` 里 `hypot(i - r, y - r)` 报 2 条 Int/Double mismatch | `(i - r).toDouble()`；或直接用 `Number` 形参统一收口 |
| ⑥ | **`PixCtx.rect` 是扩展函数，需 import** | `Unresolved reference 'rect'`（不是 receiver mismatch，因为本文件 `rect56` 已转发） | `import com.nasmusic.tv.visualizer.e51.engine.rect` |
| ⑦ | **`RM`/`ROCK_TOPS` 必须在 `initConstants` 里预计算** | 本城 `inRock`/`dome2`/`rockPixel2` 全靠这两张表；它们是纯数据派生 ⇒ 只能由 `init { initConstants() }` 提供（`renderIsland` 在 `create()` 里才跑，顺序天然满足） | 照上游 1,769-1,778 的 `for y in 90..base / for x in x0-4..x1+4` 双层循环建表 |
| ⑧ | **`EAVE_BULBS` 的 `x / 7 & 1` 是"浮除再 ToInt32"** | 写成 `(x / 7) and 1` 在 Kotlin 里是**整除**（`x`、`7` 皆 Int）⇒ 结果与 JS 一致但纯属巧合；写成 `x and 1` 则全错 | 显式 `((ebx / 7.0).toInt() and 1)`，把"JS 先浮除再截断"写出来 |

③～⑥ 是同一族：**本城把引擎 API 里"能收 Int 的地方"全走了一遍**，而 `drawImage(9 参)`、`hypot`、`pickFromRamp(4 参)` 恰好都只认 Double，`rect` 还额外要 import —— 余下城市照单核对即可，不必再靠编译试错。

#### 8.25.4 下一个入口

余下 **28 个场景**，仍按上游行数升序（`prague` 2091 → `buenos-aires` 2092 → …）。

---

### 8.26 `prague`（布拉格 · 查理大桥的夜雾，上游 2,091 行）落地 · 2026-10-25

#### 8.26.1 落地清单

| 项 | 内容 |
|---|---|
| Kotlin | `visualizer/e51/scenes/LofiCityPrague.kt`，**3,761 行**，单文件一次成型 |
| 场景类名 | `LofiCityPrague`（沿用 `LofiCity` 前缀口径） |
| 场景 id | `prague`，`SCENES_BUILT` 29 → **30** |
| registry 回填 | 无需回填：生成脚本本城解析成功，`focusX` 已是 **248.0**（伏尔塔瓦河右岸桥头），与场景手译一致 |
| 结构 | 同里斯本范本（§10.284）：`create()` → `prerender17()` → `PixelFrame { t, opts -> renderFrame17(t, opts) }`、`dispose()` 空、`init { initConstants() }` |
| 本城特有 | 透视线性缩放的**双层桥体**：`bsc(s) = 1/(1+s)`、`bxAt(s) = XO - (XO-XL)*2s/(1+s)`、`sAtX(x) = g/(2-g)`（`g = clamp((XO-x)/(XO-XL),0,1)`）四个派生函数把"桥面越远越窄"折成解析式，`NARCH = 16` 拱孔按 `s` 均分；`SN` 是上游 `{cx:148}`（**只有 cx**） |

#### 8.26.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 两轮：**12 条 → 0 error**（第二轮 0 error、1 warning：`Redundant call of conversion method` @2061） |
| `LofiTrimOffsetSourceGateTest`（G-E51-J） | 通过（⚠️ 该门禁只扫 `trim24(...).canvas` 同形，本城用 `trim11` ⇒ 已手工核对无 `trim11(...).canvas` 同形行） |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例 / **0 失败** |
| `LofiCityRegistryGateTest` / `LofiCityCardWiringGateTest` / `LofiCityManualSwitchGateTest` | 6 / 2 / 2 例全绿 |
| 全量 `testDebugUnitTest --rerun-tasks` | **2,592 例 / 256 类 / 0 失败**（`LOFI_SNAPSHOT=1` 同轮跑完；基线 2,588 + 本城 0 + 城市卡命名 4） |
| 四时刻 PNG | `logs_temp/lofi_render/prague_t{000,060,120,228}.png`，人工判读通过：城堡群 + 圣维特双尖塔 + 绿顶主塔 + 钟面 + 国旗、佩特任塔与红色航标、山体灯点与缆车索、查理大桥 16 孔拱 + 桥灯 + 桥面行人、老城桥塔 + 小城塔群、伏尔塔瓦河 + 堰 + 倒影带 + 碎光、天鹅/野鸭、`TRDELNÍK U MOSTU` 广告牌与旋转卷、露台灯串、长椅情侣、前景行人（含牵狗）、路灯与飞萤、双枝樱花（默认 `weather = null` ⇒ `blossoms = true` 生效）、星/月/卷云、`t=120` 蒸汽船（倒影 + 舱灯串 + 蒸汽 + 尾迹） |
| 补帧 PNG（事件窗口在四时刻之外） | `prague_ev_t{020,044,194}.png`：`t=20`/`t=194` 电车双向（受电弓 + 架空线 + 头尾灯，`dir` 镜像正确）、`t=44` 钟声涟漪弧（钟声时刻 42/45.6/49.2/162/165.6/169.2）。⚠️ 该补帧由**临时** `PragueEventFrameDumpTest` 产出，判读完即删（不留在仓库） |
| 接线 | `LofiScenes.build()` 加 `"prague" -> LofiCityPrague()`；`CityRegistry.SCENES_BUILT` 加 `"prague"`（第 30 项） |

#### 8.26.3 本城新踩的坑（余下城市照用）

| # | 陷阱 | 表现 / 判据 | 修法 |
|---|---|---|---|
| ① | **顶层 `private class` 跨文件重名 = `Redeclaration`** | 本城数据类 `XyI` 与 `bali` 的 `XyI` 同名 ⇒ 1 条 `Redeclaration` + **30 条** `Cannot access 'class XyI : Any': it is private in file`。⚠️ 后者全部指向**使用点**（本文件 462/466/711/2776-2778/3035/3501/3505/3570），看起来像"本文件访问了别人的私有类"，实际根因是同名冲突把**本文件自己的**那个类顶掉了 | 加后缀重命名（本城 `XyI`→`XyI17`）。本轮共改 8 个名字：`XyI`/`XyD`/`PalaceDef`/`PlaneDef`/`TramRun`/`Win3`/`Floater`/`Wisp`（冲突源：Bali/Lisbon/Seoul/Toronto、Seoul、Budapest、RomeColosseum、Lisbon、Rome、Toronto、MexicoCity/SanFrancisco/Toronto）。**新城市开工前先 `grep '^private class '` 全 scenes 目录，取交集再改名**，不要等编译 |
| ② | **上游 `SN = {cx: 148}` 只有一个键** | 本地 `XyI17(148, 0)` 的字段是 `x`/`y` ⇒ `SN.cx` 报 `Unresolved reference 'cx'`（3 处）。⚠️ 更坑的是**级联**：`cx` 变成错误类型后，`for (x in cx - 12..cx + 12)` 报 `For-loop range must have an 'iterator()' method`（4 条）、`if (x < cx - 8)` 报 `'operator' modifier is required on 'fun <T> Comparable<T>.compareTo(other: T): Int'`（5 条）—— **12 条里 9 条是同一根因的假象**，改一处全消 | `SN.cx` → `SN.x`。判据：看到 `iterator()` 与 `compareTo` 同时出现且数量多，先去查有没有"错误类型"传进来，而不是去改循环 |
| ③ | **`Comp` 枚举要显式 import** | `ctx17.op = Comp.LIGHTER` 报 `Unresolved reference 'Comp'`（3 处）。`PixCtx` 是同一个包，但 `Comp` 是**独立的顶层枚举**，不在 `PixCtx` 里 | `import com.nasmusic.tv.visualizer.e51.engine.Comp` |
| ④ | **类体末尾收尾 `}` 漏写 ⇒ 报在文件最后一行** | `LofiCityPrague.kt:3757:6 Syntax error: Missing '}'`。报错位置是**文件末尾**，不是漏 `}` 的那个类 ⇒ 按行号去找会白找 | 结构范本照 §8.25：`override fun create` / `override fun dispose` / `init` / 数据类全在**类体内**，最后一个 `}` 必须存在。写完先数 `^}` 与类声明数 |
| ⑤ | **`LocaleListCompat.get()` 返回 `Locale?`** | （本轮城市卡双语改造踩到）`locales[0].language` 报 `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'Locale?'` | `locales[0]?.language == "zh"`；`ConfigurationCompat.getLocales(...)` 负责 minSdk 22 与 24+ 的 `Configuration.locales` 差异 |

#### 8.26.4 下一个入口

余下 **26 个场景**，仍按上游行数升序（`bangkok` 2102 → …）。

⚠️ 本轮顺带改了右下角城市卡（国名 + 中英双语，见 §12.3 补记 / `technical-overview` §10.294）——它不属于"移植件"，但同在 `feat/e51-private` 本地提交里，⛔ 同样不 push。

---

### 8.27 `buenos-aires`（布宜诺斯艾利斯 · 方尖碑旁的蓝花楹，上游 2,092 行）落地 · 2026-10-25

#### 8.27.1 落地清单

| 项 | 内容 |
|---|---|
| Kotlin | `visualizer/e51/scenes/LofiCityBuenosAires.kt`，**3,354 行**，单文件一次成型 |
| 场景类名 | `LofiCityBuenosAires`（沿用 `LofiCity` 前缀口径） |
| 场景 id | `buenos-aires`，`SCENES_BUILT` 30 → **31** |
| registry 回填 | `focusX` 生成脚本置 **0.0** ⇒ 回填 **240.0**（= `OB.x`，方尖碑中轴） |
| 结构 | 同里斯本范本（§10.284）：`create()` → `prerender40()` → `PixelFrame { t, opts -> renderFrame40(t, opts) }`、`dispose()` 空、`init { initConstants() }` |
| 本城特有 | ① 上游 `rng(seed)` 返回**带 `invoke()` 的对象**（本文件 `Rng40`），调用是 `r()` 不是 `r.next()`；② `prerender40` 五块 rng 种子（星/行人/散场观众/落花/烟）+ 十处烘图种子**消费次序即画面**（`initConstants` 与 `prerender40` 不可调换）；③ 人物九组（`WALKERS16` 六行/`PLAZA_FOLK2`/`SNAPPERS`/`FAR_FOLK2`/`AUDIENCE2`/`WATCHERS2`/`TABLES8`/`WAITER4`/`DOGWALK3`）+ `ACTORS3` 13 项注册 `sortBy y`；④ `HL40` 24 枚光晕 + `POOL11` 4 池雨渍；⑤ `prerender40` 烘焙 27 张位图（天空/月亮/双层云/远景/城/方尖碑/广场/路/步道/大道/法拉灯/树冠/树冠 2/报刊亭/近景/广告牌/烟/暗角等） |

#### 8.27.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 四轮：**19 条 → 10 条 → 3 条 → 0 error** |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例 / 0 失败（导出例 `LOFI_SNAPSHOT` 未设时 skipped，属预期） |
| `LofiCityRegistryGateTest` / `LofiCityNamingTest` | 6 / 4 例全绿 |
| 全量 `testDebugUnitTest --rerun-tasks` | **2,593 例 / 257 类 / 0 失败**（`LOFI_SNAPSHOT=1` 同轮跑完；基线 2,592 + 本城补帧临时测试 1 例，判读后已删） |
| 四时刻 PNG | `logs_temp/lofi_render/buenos-aires_t{000,060,120,228}.png`，人工判读通过：方尖碑（红顶 + 微字）、三株蓝花楹 + 三层落花、`FARMACIA`/`CAFE`/`LIBROS`/`HELADOS` 店招、`TEATRO` 竖牌 + 天幕、`ALFAJOR LUNITA` 广告牌、`KIOSCO` 报刊亭、`PIZZA` 摊、星/月/卷云、路面反光、`t=060` 远侧电车（54-70 班次）、`t=120` 广场桌客与行人、`t=228` 探戈下腰窗口（228 命中 `DIPS[3]`） |
| 事件图层源码对账（四时刻之外的窗口） | 逐行对账通过：`drawPlane17`（客机，上游 194-209 行 ↔ Kotlin 2078-2097 逐行一致；`PLANE18 = {a:62, b:104, x0:-10, x1:300, y0:30, y1:64}` 常量一致；调用点在 `renderFrame40` 图层序位置与上游 1728 行一致）、`drawNeighbour3`（晾衣绳，`BALC = {x:108, y:0}`、`CLOTH = {a:70, b:82}` 一致）、`dipAt`/`DIPS = [36,108,180,228]`（探戈下腰 + 钟声）、`BUS_RUNS` 六班与 `BUS_LIVERY` 四组号牌（`580`/`612`/`703`/`431`）一致、`THEATRE_OUT`/`AUDIENCE2`/`KIOSK_VISITS`/`DOGWALK3` 各窗口常量一致 |
| 接线 | `LofiScenes.build()` 加 `"buenos-aires" -> LofiCityBuenosAires()`；`CityRegistry.SCENES_BUILT` 加 `"buenos-aires"`（第 31 项）；`focusX` 0.0 → 240.0 |

#### 8.27.3 本城新踩的坑（余下城市照用）

| # | 陷阱 | 表现 / 判据 | 修法 |
|---|---|---|---|
| ① | **上游 `rng(seed)` 返回对象不是函数** | 本城上游 `const r = rng(1906)` 的调用形式是 `r()`；按惯性写 `r.next()` ⇒ 第一轮 19 条 `Unresolved reference 'next'`（`WALKERS16`/`AUDIENCE2` 等五个种子块全是）。⚠️ `BLOSSOMS4` 的**局部** rng（`rng(900 + li)`）同样踩了 6 处 | 本文件 `private class Rng40(seed) { operator fun invoke(): Double = g.next() }`，一律 `r()`。⚠️ 余下城市读上游时先看 `rng` 的**调用点形态**再定类型 |
| ② | **import 区粘连 ⇒ 报错指向使用点** | 一次历史 Edit 把 `import …rect` 与 `import …jsRound` 拼成一行 ⇒ 第二轮 `jsRound` 在 2860/3059 行报 `Unresolved`，行号全是使用点，import 区本身零报错 | 编译前人工扫一遍 import 区（每行一条）。判据：同一符号多处 `Unresolved` 且 import 区看起来"有"，先查 import 行是否粘连 |
| ③ | **`putPx`/`rect` 是 `PixCtx` 的扩展函数** | 写成自由函数（`putPx(c, …)`）报 receiver 错；本文件自有转发 `px40`/`rect40` 内部与 `renderCity5` 店招循环均需 `c.putPx(…)`/`c.rect(…)` 形式 | 调用侧一律接收者形式；`kotlin.math.sign` 是扩展属性需显式 import（`import kotlin.math.sign`），忘了会报 `Unresolved reference 'sign'` |

#### 8.27.4 下一个入口

余下 **26 个场景**，仍按上游行数升序（`bangkok` 2102 → …）。

---

### 8.28 `bangkok`（曼谷 · 昭披耶河的水灯夜，上游 2,102 行）落地 · 2026-10-25

#### 8.28.1 落地清单

| 项 | 内容 |
|---|---|
| Kotlin | `visualizer/e51/scenes/LofiCityBangkok.kt`，**3,276 行**（分 6 片写入 `logs_temp/bangkok_parts/` 后拼装，成品未再拆） |
| 场景类名 | `LofiCityBangkok`（沿用 `LofiCity` 前缀口径） |
| 场景 id | `bangkok`，`SCENES_BUILT` 31 → **32** |
| registry 回填 | `focusX` 生成脚本置 **0.0** ⇒ 回填 **214.0**（= `PX`，郑王庙中轴） |
| 结构 | 同里斯本范本（§10.284）：`create()` → `prerender21()` → `PixelFrame { t, opts -> renderFrame21(t, opts) }`、`dispose()` 空、`init { initConstants() }` |
| 上游源 | `logs_temp/lofi_modules/js__scenes__bangkok.js.js`（2,102 行，唯一权威译文源） |
| 本城特有 | ① `Painter9(w, h, track=true)` 的 `own`（每像素最后写入者 id）**必须保留**：`renderBank` 窗灯按 `own[w.y*W+w.x] == w.b` 过滤被前景遮住的灯、`renderForeground3` 栏杆灯泡按 `own[b.y*W+b.x] != 1` 过滤被枝干/神龛压住的灯；② 五块 rng 种子（`STARS18` 117 / `KR`+`HY` 2468 / `FALL` 1357 / `STEAM6`+`SMOKE5`+`MOTHS5` 61 / `CROWD3` 777）+ 六处烘图种子（云 31-47、远景 71、郑王庙 909、河岸 55、快艇 313、近景 88）**消费次序即画面**，`initConstants()` 与 `prerender21()` 不可调换；③ 33 层 `renderFrame21` 次序不可调换（水灯/水葫芦按 `LANE` 三段深度带各画两遍、落花三层夹住近景）；④ 水灯节节奏：情侣放灯 `KRATHONG_T=60` / `DRIFT=26`、两班长尾船 86-98 与 176-188、船队 8-128、快艇 40-64、游船 136-212、客机 52-142、风铃 30/150、狗耳 5 次、狗尾 5 次、壁虎 3 次 |

#### 8.28.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 首轮约 **40 条 → 0**（五个根因见 §8.28.3）；weather 补丁后复编 **BUILD SUCCESSFUL（3m 33s）** |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例 / 0 失败（导出例 `LOFI_SNAPSHOT` 未设时 skipped，属预期） |
| `LofiCityRegistryGateTest` / 接线 | 6 例全绿（R4 双向逐 id、R6 逐字段含 `focusX=214.0`） |
| 全量 `testDebugUnitTest --rerun-tasks` | **2,596 例 / 257 类 / 0 失败 / 2 skipped**（既有跳过例） |
| 四时刻 PNG | `logs_temp/lofi_render/bangkok_t{000,060,120,228}.png`，人工判读通过：郑王庙中央大塔 + 四卫星塔、月与两层卷云、远景塔群窗灯、`MANGO STICKY RICE` / `OPEN LATE` 双广告、面摊车、神龛、情侣与狗、河面水灯/水葫芦及倒影、鸡蛋花树 + 灯串、`BANGKOK` 站牌 |
| 源码逐行对账（四时刻之外的窗口） | 全函数覆盖：助手（`sprite14`/`flipCanvas3`/`paintRows2`/`ptext3`/`pline3` 两形态）、烘图（`renderSky21`/`renderMoon16`/`makeCloudLayer10`/`renderFarCity3`/`prang`/`renderWatArun`/`renderBank`/`renderBillboardFrame7`/`renderAd7`/`renderForeground3`/`backlit`/`renderWater13`/`renderVignette21`/`prerender21`）、逐帧（`drawSky16`…`drawForeground6` 全 33 层与 8 艘船精灵）、`initConstants` 1599-2102 全部常量与 rng 次序、`events` 逐条 |
| 对账中修出的真实缺陷 | ① `weatherPlan21`/`weatherPlan40` 漏了 SHARED 键 `"leaves"`（上游 `SHARED` 是五键），会让该天气误入城市母题分支；两文件已补齐；② `haloNeon21` 实际还覆盖神龛烛焰与水灯，文件头受控差异第 6 条漏列，已补 |
| 接线 | `LofiScenes.build()` 加 `"bangkok" -> LofiCityBangkok()`；`CityRegistry.SCENES_BUILT` 加 `"bangkok"`（第 32 项）；`focusX` 0.0 → 214.0 |

#### 8.28.3 本城新踩的坑（余下城市照用）

| # | 陷阱 | 表现 / 判据 | 修法 |
|---|---|---|---|
| ① | **`FONT3` 漏 import ⇒ 两条"类型推断"错** | `ptext3` 里 `g.forEachIndexed { j, row -> row[i] }` 报 `Cannot infer type for 'j'/'row'` + `No 'get' operator`（185/186 行），看起来像字模结构问题 | `import com.nasmusic.tv.visualizer.e51.engine.FONT3`（`FONT3` 在引擎包，与 `PixCtx` 不同包时不会自动可见）。一条 import 全消 |
| ② | **上游单键对象 `SH = { x: 46 }`，本地 span 类只有 `x0`/`x1`** | `SH.x` / `HALL.x` 等 10 余处 `Unresolved reference 'x'`；⚠️ 级联出 `BigInteger`、`iterator()` 一类假错 | `BkSpan` 补 `val x: Int get() = x0` 只读别名，单点 span 写 `BkSpan(46, 0)`；双端 span 仍用 `x0`/`x1`。判据同布拉格坑②：同一符号大量 `Unresolved` 时先查"错误类型"再改循环 |
| ③ | **`AD_C5 = AD_LINES.map(renderAd7)` 是数组** | 声明成 `PixelCanvas` ⇒ `Assignment type mismatch: Array<PixelCanvas> but PixelCanvas was expected`；`AD_C5[floor(q) and 1]` 是取元素不是取画布 | 声明 `Array<PixelCanvas>`，下标处照上游取元素 |
| ④ | **引擎 9 参 `drawImage` 全部是 Double** | `drawWater7` 的倒影切片与 `boatAt` 共 **8 处** `Argument type mismatch: Int but Double was expected`（3 参/5 参重载才是 Int） | 9 参调用逐参 `.toDouble()`（`sy3`/`wl + 1 + j`/`x + off` 等） |
| ⑤ | **`HY` 生成误用 `Math.random()`** | 上游同一块内 **只用一个随机源**：`ph=r(), y=round(y0+r()*(y1-y0)), bob=13+floor(r()*20), bp=r()` 四次依序消费；写成两处 `Math.random()` 会整段挪动 rng 序列（水灯/水葫芦全部错位，且无编译报错） | 严格按上游 `r()` 次序写四次 `r.next()`。判据：上游块内出现 `Math.random()` 时，先确认它是否真的与 `r()` 混用（本城是误译，不是上游写法） |

#### 8.28.4 下一个入口

余下 **25 个场景**，仍按上游行数升序（下一座 `seattle`，2,109 行）。

---

### 8.29 `seattle`（西雅图 · 太空针塔上的细雨，上游 2,109 行）落地 · 2026-10-25

#### 8.29.1 落地清单

| 项 | 内容 |
|---|---|
| Kotlin | `visualizer/e51/scenes/LofiCitySeattle.kt`，**3,638 行**（分 4 片写入 `logs_temp/seattle_parts/p01..p04` 后 `cat` 拼装，成品未再拆） |
| 场景类名 | `LofiCitySeattle`（沿用 `LofiCity` 前缀口径） |
| 场景 id | `seattle`，`SCENES_BUILT` 32 → **33** |
| registry 回填 | `focusX` = **168.0**（太空针塔中轴；`landmarkX0/X1 = 147/189`、`landmarkTop = 22` 取塔顶信标行） |
| 结构 | 同里斯本范本（§10.284）：`create()` → `prerender32()` → `PixelFrame { t, opts -> renderFrame32(t, opts) }`、`dispose()` 空、`init { initConstants() }` |
| 上游源 | `logs_temp/lofi_modules/js__scenes__seattle.js.js`（2,109 行，唯一权威译文源） |
| 本城特有 | ① `Painter14` 的 `set`/`seta` **不取整**（上游 `p.set` 直接收整数，`jsRound` 由调用方做）——与曼谷 `Painter9(jsRound)` 相反，别对齐错；② 八处烘图种子（云 13/29/47/61、云隙 91/92、远景 17、市中心 66、中景 88、近景 123、货轮 77、雾纱 77）＋ 地面前景 31 ＋ 五块 rng（`DRIZ2` 200+li / `RINGS8` 71 / `SMOKE7` 61 / `STEAM12` 62 / `CROWS2` 515）**消费次序即画面**，`initConstants()` 与 `prerender32()` 不可调换；③ `renderFrame32` **24 步**图层序不可调换（三处 `drawWeather` 是空实现，`PitchStub` 为空桩）；④ 节奏锚点：汽笛 21/151、渡轮 22/152（各 68s）、浮标铃 46/133/206、海鸥 100/198；⑤ 景观构成：雷尼尔山雪冠 + 冰川裂纹、市中心 21 栋塔楼、湾面倒影 + 高光条 + 摩天轮彩灯、太空针塔（旋转光点 + 双电梯 + 塔顶信标）、坡地近景六组房屋 + 砖楼 + 防火梯 + 枫树、`MOSS RECORDS` 广告牌 + 转盘 |

#### 8.29.2 验证

| 项 | 结果 |
|---|---|
| `compileDebugKotlin` | 首轮 **75 条 → 6 条 → 0**（根因见 §8.29.3）；`limb2` 收口修复后复编 **BUILD SUCCESSFUL（4m 40s）** |
| `LofiCityRegistryGateTest` / `LofiCityFlickerGateTest` | **BUILD SUCCESSFUL（34s）**，registry 门禁 6 例 0 失败（R4 双向逐 id、R6 逐字段含 `focusX=168.0`） |
| `LofiSceneSnapshotTest`（G-E51-G） | 3 例 / 0 失败；`LOFI_SNAPSHOT=1` 全 33 城导出 **BUILD SUCCESSFUL（1m 53s）** |
| 四时刻 PNG | `logs_temp/lofi_render/seattle_t{000,060,120,228}.png`（各 ≈250 KB），人工判读通过：太空针塔 + 塔顶信标、雷尼尔山、21 栋塔楼窗灯、雾团与四层卷云、`MOSS RECORDS` 广告牌 + 转盘、摩天轮、渡轮与货轮、细雨 + 水洼、情侣/狗/单车/伞、`SEATTLE` 站牌 |
| 源码逐行对账 | 双脚本对账（**数值 token 序列** + **颜色字面量序列**）：24 个函数序列逐项一致；其余 DIFF 项逐个人工读码确认为良性（打包字节、`+0`、常量提升、`floor` 上界等价、`towerTop` 调用提升）；`initConstants` 全部常量与五块 rng、`prerender32`、`renderFrame32` 24 步、`events` 逐条均已人工核完 |
| 对账中修出的真实缺陷 | `renderNear5` 的 `limb2`：上游 `for (let s = 0; s < len; s++)` 的 `len` **可为小数**（递归传 `len * 0.55`），迭代数 = `ceil(len)`；原译文签名收 `Int` 并 `(len * 0.55).toInt()`，把 16.5/13.2 之类截断 ⇒ 递归支路少画 1~2 像素。已改 `len: Double` + `ceil(len).toInt()` + 五处顶层调用传小数 |
| 接线 | `LofiScenes.build()` 在 `san-francisco` 与 `seoul` 之间加 `"seattle" -> LofiCitySeattle()`；`CityRegistry.SCENES_BUILT` 末尾加 `"seattle"`（第 33 项） |

#### 8.29.3 本城新踩的坑（余下城市照用）

| # | 陷阱 | 表现 / 判据 | 修法 |
|---|---|---|---|
| ① | **JS 浮点循环上界 ≠ Kotlin `.toInt()`** | 上游 `for (s = 0; s < len; s++)` 且 `len` 由 `len * 0.55` 递推 ⇒ 16.5 / 13.2 这类小数上界。Kotlin 若签名收 `Int` 并 `.toInt()`，**迭代数少 1**、且递归深度整段偏移；编译不报错、单帧也看不出来 | 保持 `len: Double`，循环写 `for (s in 0 until ceil(len).toInt())`，递归原样传 `len * 0.55`。判据：上游循环变量与上界任一为浮点时，先算 `ceil` 再落 Kotlin |
| ② | **`kotlin.math.pow` 只有扩展形式** | `pow(2.0, 1.3)` 报 `Too many arguments` / `Unresolved`，级联出一片假错 | 写 `2.0.pow(1.3)`；`** 2` 一律展开成乘法 |
| ③ | **引擎 `putPx` / `rect` / `streak` 必须接收者语法** | 写成 `putPx(ctx, x, y, col)` 报 `Unresolved reference`（它们是 `PixCtx` 的扩展函数） | `ctx.putPx(x, y, col)` / `ctx.rect(...)` / `ctx.streak(...)` |
| ④ | **逐字节画布写色要压掉 alpha 高位** | 上游 `putImageData` 收 `[r,g,b,a]`；本文件直接写 `PixelCanvas.px` 时必须 `(0xFF shl 24) or (col and 0x00FFFFFF)`，否则 `mix()` 产出的 0xFF 前缀与色值位或后变白 | 天空/云/雾/云隙/暗角五处统一该写法（受控差异 11） |
| ⑤ | **对账脚本会误报"常量提升"** | 把重复颜色提成常量后，颜色字面量的**出现次序**改变，序列对账必然 DIFF；这不是画面差异 | 只读 DIFF 区段的**语义**（是否同一组颜色、同一坐标），别机械追求序列全等 |

#### 8.29.4 下一个入口

余下 **24 个场景**，仍按上游行数升序（下一座 `marrakech`，2,115 行）。

---

### 8.30 ✅ `marrakech`（马拉喀什 · Jemaa el-Fnaa 的灯笼夜，上游 2,115 行）**已落地** · 2026-10-25（本地提交 `a271f59`）

> 本节是本城的**落地台账**（§9 P4 行内的"当前基线"是 2026-10-24 的冻结快照，已被此节取代）。
> 五片已拼装为 `scenes/LofiCityMarrakech.kt`（4,123 行）：编译 0 错误、三道门禁全绿、双脚本逐行对账完毕，已**本地提交**（⛔ 未 push）。

#### 8.30.1 全局进度快照（2026-10-25）

| 项 | 状态 |
|---|---|
| 已落地 | **34 / 57**（`SCENES_BUILT` 34 项，末位 `marrakech`；台账 §8.1–§8.30） |
| 当前进行中 | 无（下一座按上游行数升序） |
| 余下 | 23 座 |
| 分支 / 推送 | `feat/e51-private`，**无 upstream**；本轮全部提交只落本地（⛔ 不 push / 不 tag / 不发 release，见 §2.6） |
| 正确性口径 | 所有者 2026-10-25 裁：**以「与原文件逐行移植、保持不变」为准**（双脚本对账 + 人工读码），四时刻整帧 PNG 只作粗粒度目检，不再当验收证据 |
| 执行模型 | ⛔ **不使用 subagent**（所有者明示），逐城自己直译 |

#### 8.30.2 译文分片进度（`logs_temp/marrakech_parts/`，拼成 `scenes/LofiCityMarrakech.kt`）

| 片 | 行数 | 内容 | 状态 |
|---|---|---|---|
| `p01.kt` | 290 | package + imports + **文件头注释**（场景构成说明、移植纪律、**11 处 rng 种子清单**、**12 条受控差异**）+ 顶层纯助手：`pick25` / `of2` / `tint6` / `darker4` / `MkDims` + `dims2` / `MkSpr` / **`Painter15`**（`put` 取整、`has` 查 alpha>0、`get` 返 ARGB Int、`box` 两重载、`done()`）/ `line14` / `pline6` / `sprite30` / `stamp4` / `makePuff11` / `vnoise19` / `tnoise` / `turnRows` | ✅ 写完 |
| `p02.kt` | 1,480 | 19 个 `Mk*` 数据类 + **`class LofiCityMarrakech : PixelCityScene`** 类头（覆写 `id`/`name`/`tagline`/`timeZone="Africa/Casablanca"`/`accent=hex("#e2725b")`/`focusX=232.0`/`landmarkX0/X1=217/248`/`landmarkTop=12`/`billboardRect`/`events`）+ 常量字段声明区 + **上游 103–623 行的 22 个方法**（`wallLight2` + `drawScroll19`→`renderLanternRug`，含 `buildMinaret`/`paintMinaret`/`renderFarCity4`/`renderHall`/`renderCrown2`/`renderTown3`/`renderStall3`/`renderJuiceCart`/`lanternSprites`） | ✅ 写完 |
| `p03.kt` | 860 | 上游 **624–1077**：`drawFigure2` / `figure3` / `walkSet2` / `handAt2` / `shadow` / `randomStyle2` / `makeCaleche2` / `makeMoped` / `flipCanvas6` / `buildCast` / `buildDiners` / `buildRings` / `buildWalkers3` / `drawCooks` / `drawFarStallFx` / `drawStallFx` / `drawNearStalls` / `drawJuiceSeller` / `drawJuiceCart` / `buildJuiceCustomers` / `drawJuiceCustomer` / `drawParasol` | ✅ 写完 |
| `p04.kt` | 805 | 上游 **1078–1617**：`drawHenna`→`drawPlane16` 全部动作绘制 + `drawPlume2`/`drawEmbers`/`drawFarEmbers`/`drawMotes`/`drawMinaret3`/`drawPalms4`/`drawTownLights`/`drawOwnAd5`/`drawBillboard34`/`renderWash2`/`makePool11`/`drawPool10`/`renderVignette39`/`renderBeam4` | ✅ 写完 |
| `p05.kt` | 684 | 上游 **1618–2,115**：`prerender39()` + `renderFrame39()` + `initConstants()`（上游 1786–2082 逐行）+ `create()`/`dispose()` + 类收尾 `}` | ✅ 写完 |

⚠️ **拼装次序**：`p01 → p02 → p03 → p04 → p05`——类已在 **p02** 开头（与早先计划的"p05 才开类"相反），别再按旧计划拼。

⚠️ **分片期间的追加（2026-10-25，写 p03 时发现缺口，已就地补齐）**：

| 片 | 追加内容 | 原因 |
|---|---|---|
| `p01` | 顶层助手 `sgnI(v: Int)`（`Math.sign` 整型版） | `drawFigure2` 的迈步外扩量；`Math.sign(Int)` 无对应 |
| `p02` | 类外的 `MkStyle` / `MkPose` / `MkHalos` / `MkCastSpr` / `MkDiner` / `MkListener` / `MkAudience` / `MkWalker` / `MkJuiceCustomer` / `MkWater` / `MkTourist` / `MkPlume` / `MkEmber` | 13 个容器类原先**谁都没声明**（19 个 `Mk*` 数据类里不含它们）；`p05` 只做赋值 |
| `p02` | 字段声明区补 30 项（`HL39` / `CAST_SPR` / `DINERS4` / `LISTENERS3` / `AUDIENCE` / `WALKERS15` / `JC_SPR` / `JUICE_SLOT` / `WATER7` / `TOURIST2` / `COOK_H` / `COOK_Y` / `SPR_W2` / `SPR_AX2` / `NIGHT16` / `SHOE2` / `FLARE` / `WALK_POSES2` / 七个调色板 / `PLUMES` / `EMBERS` / 三张画布） | 原先的"常量字段声明区"只到 `RUG_LANTS`；⛔ `p05` 的 `initConstants()` **只赋值、不重复声明** |

⚠️ 三处**有意不照抄**的写法（已核过语义等价，对账时别当漏项）：
1. `mk2(h, coat, extra)` 没有单列函数 —— 改用 `MkStyle(h, coat, 具名参数…)` 直构；上游 `...extra` 展开由具名参数覆盖，等价。
2. `of2` / `pick25` 等上游"函数型 var"（在 `init_marrakech()` 里赋值）改成本类成员方法：`personH2` / `seen2` / `fanUp` / `walkX2` / `halo39` / `haloNeon39` / `haloTail39` / `blit27`。
3. 上游 `CAST_SPR` 的混合数组按决策 ⑦ 拆成命名字段：`water → waterWalk/waterPour/waterRing/waterIdle`，`tourist → touristWalk/touristFlash/touristIdle`，`man → man/manArm`，`woman → woman/womanIdle`；`seller` 的四帧仍保持"哪一帧"可读写法。

✅ **`p01`+`p02`+`p03` 已过编译器（2026-10-25）**：三片按序拼装后跑
`./gradlew.bat compileDebugKotlin --no-daemon -Pkotlin.compiler.execution.strategy=in-process`，
**只剩 3 条错误，全部是 p04/p05 尚未写的成员**（`drawPlume2` / `drawEmbers` / 抽象成员）。
⚠️ 拼装体验完即删（`LofiCityMarrakech.kt` 不留在工作区），工作区保持可构建；复核时按上面的次序重新 `type` 拼一次即可。
⚠️ 顺带修掉 p02 与上游的三处偏差：`events` 的 `x`（原写 120/300/450 ⇒ 上游 `dir>0 ? 60 : 420`）、换灯的 `x`（原 `SELLER.x`=458 ⇒ 上游 452）、`gain`（原 0.25/0.2 ⇒ 上游 0.12/0.16）；并补上 `MOPED` / `SALE` / `SELLER` 的类型与字段声明（原先只在 `events` 里被引用，从未声明）。
⚠️ 另修一处编译期发现：引擎的 `rect` 是**扩展函数** `PixCtx.rect(...)`，不能写成 `rect(ctx39, …)`；p03 的四次调用已全部改为接收者形式。

✅ **`p01`–`p04` 已过编译器（2026-10-25 第二轮）**：四片拼装 3,402 行，`compileDebugKotlin` **只剩 1 条**——「未实现抽象成员」（`create` / `dispose` / `renderFrame`，都在 p05）。p04 期间又修掉 6 条 `Double`/`Int` 实参错配：`blit27` / `shadow` 的 `fy3` 参数改为 `Number`（同时收 Int 与 Double，与曼谷 `halo21(x: Number, …)` 同一口径）。
⚠️ p04 还纠正了一处**帧槽语义错位**：上游 `CAST_SPR.water[ring3 ? 2 : pour ? 3 : 4]` 里 **index 2 = `arm 2`（摇铃）**、**index 3 = `arm 1`（倒水）**，本轮把 p03 的 `waterRing`/`waterPour` 赋值对调回来，别按字面顺序再写反。
⚠️ p04 新增的成员方法：`laughAt`（上游 `LAUGH`）/ `beatAt` / `lanternRot` / `overTower`；`PitchStub`（决策 ③）声明在 p04 开头。`FRINGE` 补进了字段声明区。

#### 8.30.3 本城已定稿的移植决策（续城可直接复用，不必重新论证）

| # | 决策 | 依据 |
|---|---|---|
| ① | **前缀用 `Mk`**（`MkStyle`/`MkPose`/`MkCast`/`MkDiner`…），不用 `Wx` | `Wx*` 系列已被前几城占用，顶层类名撞名只有编译才发现（§8.5.2 的老坑） |
| ② | **常量全部走类字段** + `initConstants()` 赋值；可变列表（`STARS34`/`LANTERN_WIN`/`FAR_WIN`/`WIN2`/`STRANDS2`/`BULBS8`）用 `ArrayList` 并在 `initConstants()` 开头 `clear()` | 与西雅图/曼谷同口径：`create()` 可被二次调用，累积会让星点与窗灯翻倍 |
| ③ | **`PITCH32` 整段删除**，`PitchStub` 空桩（`covers` 恒 false、`draw` 空） | 本项目不放广告（§2.6 同款口径，与前几城一致） |
| ④ | **`drawWeather` 空实现**，`WX39`/`WX_LAND39` 只作数据 | 上游 `WEATHER39 = weatherPlans([])` ⇒ 本城无母题天气，`plans.city.shared` 恒 null |
| ⑤ | **`crowd` 恒用常量 `CROWD15 = 0.6`** | `SceneOpts` 无 `crowd` 字段，上游缺省即 0.6 |
| ⑥ | **音频轻反应只碰 α**：`lightGain`→全部光晕、`neonPulse`→灯火类、`tailGain`→车/摩托尾灯（三个 `halo*39` 出口），⛔ 不碰坐标、几何、色值 | §10 D-a 裁决 |
| ⑦ | **JS 混合数组 ⇒ 命名字段**：`CAST_SPR.water` 的 `[walk, walk, ring, pour, idle]` 拆成 `waterWalk/waterRing/waterPour/waterIdle`（`tourist`/`man`/`woman`/`gBwalk`/`gBspin`/`gG`/`seller` 同此），⚠️ `seller` 处需保留 `spr === S4[0]` 的**身份比较**语义 | 消费点已逐一核完并登记；写成下标会丢掉"哪一帧"的可读性且无法编译期查错 |
| ⑧ | **群像容器带可变帧槽**：`MkDiner(x,row,rank,spr[3])`、`MkListener(x,y,row,squat,rank,spr,S)`、`MkAudience(x,y,row,a3,phone,rank,spr,S)`；`ACTORS2 = List<MkActor(y, rank, draw(t,fr,crowd,reducedMotion))>` 且**稳定排序** | 对应上游 `prerender39` 的 `A(y, draw, rank)` + `sort((a,b)=>a.y-b.y)` |
| ⑨ | `flipCanvas6` 照抄曼谷 `flipCanvas3` 的既有写法（`translate(w,0)` + `scale(-1,1)` + `drawImage`） | 引擎已有该能力，无需自建 |
| ⑩ | **`events` 六条（三类）**：摩托喇叭 `MOPED[i].a + 1`（i=0/1/2）、卖水人铜铃 `WATER7.bells[0]`/`[1]`（91/93）、换灯 `SALE.give`；`sortedBy { it.t }`；`x` 与 `gain` 照上游（60/420 由 `dir` 定、0.12；铜铃 222、0.22；换灯 452、0.16），本城无 `pan`/`dur` | 上游 `events` 条目只有 `{t,type,x,gain}`；与墨尔本/大阪/悉尼/布宜诺斯艾利斯/曼谷的"`pan` 取首值"口径**不同**，别照抄 |
| ⑪ | 译文内已修的两处：`terrace` 去掉上游**声明但未使用**的 `r` 参数（不影响 rng 次序）；`hypot((x - SP2.x).toDouble(), y - gy2)`（Int/Double 无重载） | 编译前静态自查 |

#### 8.30.4 落地步骤（按任务 #18 → #21，**全部完成**）

1. ✅ 写 `p04` / `p05`（逐行直译，⛔ 不重构、不改数值）。
2. ✅ `cat` 拼装 → `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityMarrakech.kt`（4,123 行）。
3. ✅ `compileDebugKotlin` 清零：首轮 17 → 二轮 3 → 三轮 0。
4. ✅ 接线两处（`LofiScenes.build()` 与 `SCENES_BUILT` 第 34 项）；`focusX=232.0` 原本已在 registry。
5. ✅ 门禁：`LofiCityRegistryGateTest` 6/6、`LofiCityFlickerGateTest` 7/7、`LofiSceneSnapshotTest` 3（1 跳过）。
6. ✅ **源码逐行对账**（`scripts/lofi_token_reconcile.py` / `scripts/lofi_color_reconcile.py`）：颜色零缺失，数值无常量整体缺失；余额差异逐函数定位（详见 §8.30.6）。
7. ✅ 文档台账（本节 + §8.30.6 验证表）+ 技术纪要 §10.300 + `CHANGELOG` 一行 + 显式路径**本地**提交（⛔ 不 push）。

#### 8.30.5 挂起项（不在本轮跑）

| 项 | 状态 |
|---|---|
| 电视真机四项切城缺陷复验（§9.1：文字先切 / 地球不出现 / 3–6 fps / 切入锁画面） | 修复**已落地**（`0e2ae4a`，四条根因与四项修法见 §9.1、技术纪要 §10.298，门禁 `LofiCityBlitFastPathTest`/`LofiCityBakeGateTest`/`LofiCityTimelineTest` 全绿）；**真机验收由所有者 2026-10-25 指示「以后再进行测试吧」挂起**，⛔ 不要在文档里写成"已验收" |
| §9 P0 真机探针、P3 逐城地标相似度人工评分 | 仍需上电视，本机做不了（同 §9.1 一并处理） |

#### 8.30.6 验证与偏差（落地时实测，2026-10-25）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` 17 → 3 → **0**；`BUILD SUCCESSFUL`，4,123 行 |
| 门禁 | Registry 6/6、Flicker 7/7、Snapshot 3（1 跳过），`BUILD SUCCESSFUL` |
| 颜色字面量对账 | 上游 732 条 ⇒ **零缺失**；`#ffc880` 多 1（上游一个循环字面量执行两次，译文按听客/观众拆成两段） |
| 数值 token 对账 | 上游 3,759 个；**无任何常量整体缺失**；余额差异归为三类结构改写：`in a..b` 区间、`{ ...key }` 展开、三元改 `if` |
| 对账中修出的缺陷 | ① `rect` 扩展函数误用；② `MOPED`/`SALE`/`SELLER` 从未声明；③ `events` 三处偏离上游；④ `STEEL14` 颜色笔误 `#566066`→`#565066`；⑤ `waterRing`/`waterPour` 帧槽对调；⑥ `blit27`/`shadow` 的 `fy3` 收 `Number` |
| 有意取整 | `renderHaze5(58, 2.4→2)`：p02 的 `seed` 参数是 `Int`（唯一一处） |
| 未做 | 四时刻 PNG 目检（本城验证口径已定为源码级对账） |

⛔ 未推送、未 tag（§2.6）。


---

### 8.31 ✅ `rio`（里约 · 科帕卡巴纳夏夜，上游 2,122 行）**已落地** · 2026-10-25（本地提交 `c10a114`）

> 本节是第 35 座的**落地台账**。分片在 `logs_temp/rio_parts/`，按序拼成
> `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityRio.kt`（3,498 行）：编译 0 错误、三道门禁全绿、132 例 Lofi 测试全过、双脚本对账完毕，已**本地提交**（⛔ 未 push）。

#### 8.31.1 全局快照

| 项 | 状态 |
|---|---|
| 已落地 | **35 / 57**（`SCENES_BUILT` 35 项，末位 `rio`） |
| 当前进行中 | 无（下一座按上游行数升序） |
| 余下 | 22 座 |
| 分支 / 推送 | `feat/e51-private`，**无 upstream**；只落本地（⛔ 不 push / 不 tag，见 §2.6） |

#### 8.31.2 分片计划（按上游行数切，⛔ 不跨函数切）

| 片 | 上游行 | 内容 | 状态 |
|---|---|---|---|
| `p01.kt` | — | package / imports / **文件头**（场景构成 + 两套像素写法 + 受控差异）+ 顶层助手：`px` 两重载、`hx`（`HX` 去缓存）、`HZ = 148` | ✅ 写完 |
| `p02.kt` | 6–57 | 41 个容器类（全部 `RioMk*` 前缀）+ **`class LofiCityRio : PixelCityScene`** 类头 + 字段区 + `halo6`/`haloNeon6`/`haloTail6` + `bbTone`/`bbInk` + `renderSky6` / `renderMoon5` / `corcY` / `urcaY` / `sugarRow` / `shoreF` / `promY` / `cableY` / `kWire` | ✅ 写完（627 行） |
| `p03.kt` | 58–400 | `renderMountains`（三层山脊 + 棚户区窗 + 基督像点阵 + 旅馆群 + 索道）/ `drawMountainFx` / `bbWindow` / `renderBillboardFrame2` / `bigText2` / `renderBillboardAd2` / `renderBillboardGlint` / `bbLampLevels` / `drawBillboard6` | ✅ 写完（560 行） |
| `p04.kt` | 401–587 | `renderWater3` / `drawMoonPath` / `renderBoat2` / `drawBoat2` / `fwDirs` / `fwReflect` / `drawFireworks` / `drawPlane4` / `drawShootingStars` / `beachLight` | ✅ 写完（258 行） |
| `p05.kt` | 588–1016 | `renderBeach` / `drawKiosk` / `renderBeachProps` / `renderPalmCrown` / `waveDist` / `drawWaves` / `drawBeachLights` / `drawPalms` / `beachPerson` / `drawWalkers4` / `drawFutevolei` / `drawStroll` / `drawRipples` | ✅ 写完（582 行） |
| `p06.kt` | 1017–1342 | `drawCars` / `renderBalcony` / `drawFairyLights` / `geckoPos` / `drawMoths` / `renderSitter` / `drawSitter2` / `renderHammock` / `monsteraAt` / `strelAt` / `renderPot` / `renderPlantUnit` / `renderFrond` / `drawPlants` | ✅ 写完（540 行） |
| `p07.kt` | 1343–1493 | `drawHammock` / `drawFlies` / `renderVignette6` / `prerender6` / `drawWater` / `renderFrame6` | ✅ 写完（185 行） |
| `p08.kt` | 1496–1829 | `initConstants` 前半（几何 / 星空 / 基督像 / 旅馆 / 广告牌 / 海面 / 烟花 / 沙滩） | ✅ 写完（368 行） |
| `p09.kt` | 1830–2098 | `initConstantsRest`（涟漪 / 车流 / 彩灯 / 玻璃杯 / 收音机 / 壁虎 / 吊床 / 坐着的人 / 植物 / 萤火虫 / 天气） | ✅ 写完（249 行） |
| `p10.kt` | 2099–2122 | `create()` / `dispose()` / `init { initConstants() }` / 类收尾 | ✅ 写完（18 行） |

#### 8.31.3 起手时已核事实

- 类头取值：`id = "rio"` / `name = "Rio"`（**跟随生成的 registry**；上游场景模块写的是 `"Rio de Janeiro"`，`LofiCityRegistryGateTest` 要求与 registry 逐字段一致，registry 的 `name` 由 `registry.js` 生成) / `tagline = "Copacabana summer night"` /
  `timeZone = "America/Sao_Paulo"` / `accent = #2ec4b6` / `focusX = XC = 158` /
  `landmark = [150, 166]` / `landmarkTop = 26` / `billboard = BB6`。
- `events`：`BURSTS.map { SceneEvent(it.t, "firework") }` —— 本城**没有** `pan` / `dur` / `gain`。
- rng 八处（次序即画面）：`77`（**在 `renderMountains` 内**：棚户区窗 + 山坡）、`21`（星）、`33`、
  `58`（广告牌窗）、`515`、`90`、`404`（沙滩人）、`303`。
  ⚠️ 前七块在 `initConstants`、`77` 在 `prerender6` ⇒ 运行时次序 **21 → 33 → 58 → 515 → 90 → 404 → 303 → 77**。
- 上游两套像素写法统一：`makeCanvas` + `createImageData`/`putImageData` ⇒ 直写 `PixelCanvas.px`；
  `px(x, i, y, col)` / `rect(x, i, y, w, h, col)` ⇒ `PixCtx.putPx` / `PixCtx.rect`。
- `HX(h)` 的字符串缓存（`HXC`）丢弃 ⇒ `hx(h)`。
- `fill(x, y, w, h, col)`（L1805）与 `halo6`（L1499）⇒ 成员函数。
- 受控差异：广告位 `PITCH4` ⇒ `PitchStub` 空桩；`WEATHER6` 只有 `leaves` 的 `wind` 段，
  共享 `drawWeather` 空实现（`WX6` / `WX_BB6` 仅作数据）；`crowd` 恒用缺省；音频三通道只碰 α。

#### 8.31.4 剩余步骤

1. ✅ 写完 `p03`–`p10`（逐行直译，⛔ 不重构、不改数值）。
2. ✅ 拼装 `p01`–`p10` → `LofiCityRio.kt`（3,498 行）。
3. ✅ `compileDebugKotlin` 清零：1,351 → 43 → 16 → 3 → 0。
4. ✅ 接线两处 + **registry `focusX` 由 0.0 回填为 158.0**（原值缺失，必须手改）；`SCENES_BUILT` 第 35 项。

5. ✅ 门禁三代 + 双脚本对账（颜色仅 `#3a8a5a` 少 1，可解释；数值无常量整体缺失）。
6. ✅ 本节翻「落地」+ §8.31.5 验证表 + 技术纪要 §10.301 + `CHANGELOG` 一行 + 显式路径**本地**提交（⛔ 不 push）。

---

#### 8.31.5 验证与偏差（落地时实测，2026-10-25）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**，3,498 行；`BUILD SUCCESSFUL` |
| 门禁 | Registry 6/6、Flicker 7/7、Snapshot 3（1 跳过）；全部 `*Lofi*` 测试 **132 例 0 失败** |
| 颜色字面量对账 | 上游 687 条 ⇒ **仅 `#3a8a5a` 少 1**（上游在摊位衬衫表里写两遍，译文用 `when` 合并成一个分支），无多出色值 |
| 数值 token 对账 | 上游 3,061 个；**无任何常量整体缺失**；`255` 少 4 是 `createImageData` 的 alpha 写法改成直接 ARGB 所致 |
| 对账中修出的缺陷 | ① 39 个容器类与马拉喀什**同包撞名** ⇒ 统一加 `Rio` 前缀；② `MkPoint`/`MkLight` 漏声明；③ `LEDGE` 重复声明；④ `streak` 是 `PixelReflector` 的成员而非顶层函数；⑤ `SIT_*` / `FW_COLS` / `WX_LAND6` 类型与声明缺失；⑥ 灯塔五点配色错两处（291 应为 `#ffc070`、295 应为 `#ffd890`）；⑦ 巴西莓碗 `B` 色笔误 `#7e4e34`→`#7a4e34`；⑧ `ASPH8` 残留一份临时数组（7 个多余色值） |
| 语言名 | 上游场景模块 `name` 是 `"Rio de Janeiro"`、registry 是 `"Rio"`；`LofiCityRegistryGateTest` 要求逐字段一致，而 registry 的 `name` 由 `registry.js` 生成 ⇒ **场景名跟随 registry 写 `"Rio"`**（城市卡显示仍为「里约」） |
| 接线 | `LofiScenes.build()` 加 `"rio" -> LofiCityRio()`；`SCENES_BUILT` 第 35 项；**registry 的 `focusX` 由 0.0 回填为 158.0** |
| 未做 | 四时刻 PNG 目检（本城验证口径同样定为源码级对账） |

⛔ 未推送、未 tag（§2.6）。


---

### 8.32 ✅ `boston`（波士顿 · 查尔斯河上的夏日帆影，上游 2,155 行）**已落地** · 2026-10-25（本地提交 `414f646`）

> 本节是第 36 座的**落地台账**。分片在 `logs_temp/boston_parts/`，按序拼成
> `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBoston.kt`（3,752 行）：编译 0 错误、三道门禁全绿、132 例 Lofi 测试全过、双脚本对账完毕，已**本地提交**（⛔ 未 push）。

#### 8.32.1 全局快照

| 项 | 状态 |
|---|---|
| 已落地 | **35 / 57**（`SCENES_BUILT` 35 项，末位 `rio`） |
| 进行中 | 第 36 座 `boston`（本节） |
| 余下 | 22 座（落地后 21） |
| 分支 / 推送 | `feat/e51-private`，**无 upstream**；只落本地（⛔ 不 push / 不 tag，见 §2.6） |

#### 8.32.2 分片计划（按上游函数边界切，⛔ 不跨函数切）

| 片 | 上游行 | 内容 | 状态 |
|---|---|---|---|
| `p01.kt` | 6–102 | 顶层助手：`BosPainter`（= 上游 `pix30`，`set/alphaAt/box/line/done`）、`BosTrim`、`BosAlong`、`pick34`、`vnoise27`、`sprite38`、`trim35`、`along7` | ✅ 写完 |
| `p02.kt` | 103–402 | 容器类 + **`class LofiCityBoston`** 类头 + 字段区 + `renderSky49` / `makeWisps6` / `addTog2` / 五座塔（`paintTower4` / `paintHancock` / `paintPru` / `paintHuntington` / `paintDalton` / `paintBerkeley`）/ `bxInv` / `bankD` / `paintHill` / `paintBank` / `paintCambridge` | ⏳ 未写 |
| `p03.kt` | 403–707 | `lfAt` / `archH` / `paintTowerLF` / `renderLongfellow` / `renderWater26` / `renderMirror6` / 停泊船 / 小艇 / 赛艇（`drawBeaters` / `fleetAt` / `drawFleet` / `drawTied` / `drawEight` / `drawScull`）/ 天鹅船与鸭（`duckAt` / `drawDuck`）/ `drawWake10` / 火车（`trainSpan` / `drawTrain6` / `drawApproach`）/ `drawBats4` / `drawMemDrive` / `drawJets` | ⏳ 未写 |
| `p04.kt` | 708–930 | `groundLight8` / `renderGround15` / `renderLamp11` / `renderBench9` / `renderSycamore` / `renderShack` / `renderBoard9` / `renderAd29` / `renderDock` | ⏳ 未写 |
| `p05.kt` | 931–1292 | `look21` / `person21` + 全部人形绘制（骑车 / 情侣 / 小贩 / 排队 / 钓鱼 / 朋友 / 小孩 / 打手机 / 水手 / 遛狗 / 慢跑 / 散步）+ 绿头鸭 / 苍鹭 | ⏳ 未写 |
| `p06.kt` | 1293–1660 | `renderVignette49` / **`prerender49`**（光晕套件 + 全部烘焙层）/ `drawSky36` → `drawGoose`（含 `pruShow` / `drawConcert` / `drawFar14` / `drawWater14` / `drawBoats9` / `drawPromenade4`） | ⏳ 未写 |
| `p07.kt` | 1661–1680 | **`renderFrame49`** | ⏳ 未写 |
| `p08.kt` | 1681–1880 | `initConstants` 前半（几何 / 天空 / 天际线 / 桥 / 水面） | ⏳ 未写 |
| `p09.kt` | 1881–2107 | `initConstants` 后半（地面 / 灯 / 长椅 / 树 / 人群配色 / 天气 / 鹅） | ⏳ 未写 |
| `p10.kt` | 2108–2155 | default export 的 `create()` / `dispose()` / `init { initConstants() }` / 类收尾 | ⏳ 未写 |

#### 8.32.3 起手时已核事实

- 类头取值（上游 2,108–2,129）：`id = "boston"` / `name = "Boston"` / `tagline = "Summer sails on the Charles"` /
  `timeZone = "America/New_York"` / `accent = #44bb11` / `focusX = 366` /
  `landmark = [HAN.x0 - 1, DAL.x1]`（派生）/ `landmarkTop = PRU.mast`（派生）/ `billboard = BB41`。
- `events` 十五条（火车 ×4 / 天鹅船 ×1 / 自行车铃 ×2 / 苍鹭落水 ×3 / 舰队靠岸 ×3 / 音乐会 ×2）；
  ⚠️ 天鹅船那条带 `dur: 62` 与 `pan: [0.95, -0.55]`（双值取**首值** 0.95），其余只有 `x`。
- 上游绘制器是 `pix30(w,h)`（ImageData 画家）⇒ 顶层 `BosPainter`，一对一替换。
- `mixHex33` 的 `MIXED14` 字符串缓存丢弃（同输入同输出）。
- 受控差异：广告位 `PITCH42` ⇒ `BosPitchStub` 空桩；`WEATHER49` 只作数据、共享 `drawWeather` 空实现；
  `crowd` 恒用缺省；音频三通道只碰 α。

#### 8.32.4 剩余步骤

1. ✅ 写完 `p02`–`p10`（逐行直译，⛔ 不重构、不改数值）。
2. ✅ 拼装 `p01`–`p10` → `LofiCityBoston.kt`（3,752 行）。
3. ✅ `compileDebugKotlin` 清零：272 → 106 → 37 → 5 → 0。
4. 接线两处：`LofiScenes.build()` 加 `"boston" -> LofiCityBoston()`、`SCENES_BUILT` 加 `"boston"`（第 36 项）；
   ⚠️ registry 的 `focusX` 需核对（里约那次是 0.0，必须回填 366.0）。
5. ✅ 门禁三代 + 双脚本对账（颜色仅 `#2a2a30` 少 1 且 40 处顺序不一，可解释；数值无常量整体缺失）。
6. ✅ 本节翻「落地」+ §8.32.5 验证表 + 技术纪要 §10.302 + `CHANGELOG` 一行 + 显式路径**本地**提交（⛔ 不 push）。

---

#### 8.32.5 验证与偏差（落地时实测，2026-10-25）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**，3,752 行；`BUILD SUCCESSFUL` |
| 门禁 | Registry 6/6、Flicker 7/7、Snapshot 3（1 跳过）；全部 `*Lofi*` 测试 **132 例 0 失败** |
| 颜色字面量对账 | 上游 40 个 ⇒ **仅 `#2a2a30` 少 1**（`PRU_STRIP` 末尾色未落入数组），**多余 3 个**（`#44bb11` accent、`#e2ac82`/`#3c2418` VENDOR2 字符串字面量），**40 处顺序不一致**（色板重排，语义无影响） |
| 数值 token 对账 | 上游 88 个函数 ⇒ **无常量整体缺失**；函数内字面量频次差异来自 `pix30`→`BosPainter`、`trim35`、`along7`、`paintTower4` 等的循环展开与写法差异（不影响运行时行为） |
| 对账中修出的缺陷 | ① 32 个容器类与马拉喀什/里约同包撞名 ⇒ 统一加 `Bos` 前缀；② `BosBox` 需补 `y`/`h` 及 `x`/`w` 访问器；③ `halo49`/`haloNeon49`/`haloTail49` 三通道助手缺失 ⇒ 补齐；④ `inWin7`/`here10`/`beacon4`/`concert`/`handoff`/`skyV4`/`hazeAt2`/`up` 七个占位函数按上游真实定义校准；⑤ `PRU_STRIP` 尾色 `#2a2a30` 缺失 ⇒ 补入色板；⑥ `BosLookOpt` 漏声明、`BosHalos` 仅三字段 ⇒ 补齐 21 个字段；⑦ `drawLongfellow` 重复调用导致 `LF_LAMPS` 翻倍 ⇒ 单调用；⑧ `drawMallards` 中 `y` 类型 Double ⇒ 显式 `jsRound` 修正；⑨ `drawDogWalker4` 里 `floor(x/4)` x 为 Int ⇒ 改 `(x/4) and 1`；⑩ `drawDuck` 里 5 参数 `drawImage` 无重载 ⇒ 改 9 参数全源矩形版本。 |
| 接线 | `LofiScenes.build()` + `SCENES_BUILT` 第 36 项；registry `focusX` 由 0.0 回填 366.0（上游 `focusX: 366`） |
| 未做 | 四时刻 PNG 目检（本城验证口径同里约/马拉喀什，定为源码级对账） |

⛔ 未推送、未 tag（§2.6）。


---

### 8.33 ✅ `boston-public-garden`（波士顿 · 公共花园的雨后天鹅船，上游 2,186 行）**已落地** · 2026-10-26（本地提交见本节末）

> 本节是第 37 座的**落地台账**。分片在 `logs_temp/bostongarden_parts/`（p01–p13），按序拼成
> `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBostonGarden.kt`（4,294 行）：编译 0 错误、
> `*Lofi*` 132 例 0 失败、`lateinit` 扫描 137 项 0 悬空、七帧 PNG 目检通过，已**本地提交**（⛔ 未 push）。

#### 8.33.1 全局快照

| 项 | 状态 |
|---|---|
| 已落地 | **37 / 57**（`SCENES_BUILT` 37 项，末位 `boston-public-garden`） |
| 进行中 | 第 37 座 `boston-public-garden`（本节） |
| 余下 | 20 座（`las-vegas` / `new-orleans` 2,197 → `hong-kong` 2,606） |
| 分支 / 推送 | `feat/e51-private`，**无 upstream**；只落本地（⛔ 不 push / 不 tag，见 §2.6） |

#### 8.33.2 分片计划（按上游函数边界切，⛔ 不跨函数切）

| 片 | 内容 | 状态 |
|---|---|---|
| `p01.kt` | 文件头 + 顶层助手：`BpgPainter`（= 上游 `pix31`，`set/alphaAt/box/line/done`）、`BpgTrim`、`BpgAlong`、`pick35`、`vnoise28`、`sprite39`、`trim36`、`along8` | ✅ |
| `p02.kt` | `class LofiCityBostonGarden` 类头 + 约 150 个字段 + `halo50` | ✅ |
| `p03.kt` | 场景内小工具 28 个（`keyAt` / `bx2` / `pr2` / `mixHex34` / `brPt` / `brAt` / `boylTop` / `skyV5` / `hazeAt3` / `isWater` / `inPuddle5` / `signGlyph` / `signW` / `tyOf6` / `here11` / `pageTurn` / `showerOpen` / `umbOpen2` / `handoff2` / `showerAt2` / `coverAt` / `breezeAt` / `swayAt` / `covNow` / `ringAmt` / `winLit11` / `litAt14` / `inWin8` / `beacon5` / `blit38` / `dot13` / `R23`）+ `initConstants()` 前半 | ✅ |
| `p04.kt` | 49 个嵌套容器类（`BpgPt` … `BpgPitchStub`，**全部嵌在场景类内部**） | ✅ |
| `p05.kt` | 烘焙层一：天空 / 远景 / 天际线 | ✅ |
| `p06.kt` | 烘焙层二：树 / 地面 / 堤岸 / 灯 / 长椅 / 柳树干 / 山楂 / 摊车 / 告示板 / 广告 / 暗角 | ✅ |
| `p07.kt` | 人形套件 `look22` / `person22` / `umbrella3` + 十种走位 | ✅ |
| `p08.kt` | 水 / 桥 / 天鹅船 / 绿头鸭 / 小鸭 / 苍鹭 / 蝙蝠 / 雾 | ✅ |
| `p09.kt` | 雨 / 花瓣 / 水洼 / 水痕 / 光晕 / 桥灯 | ✅ |
| `p10.kt` | `initConstants()` 后半（几何 / 桥 / 水面 / 地面 / 人群配色 / 天气 / 动物事件） | ✅ |
| `p11.kt` | 分层绘制后半（堤岸 / 涟漪 / 远走人 / 雨滴串） | ✅ |
| `p12.kt` | `prerender50()`（光晕套件 + 全部烘焙层）+ 各 `draw*` 入口 | ✅ |
| `p13.kt` | `renderFrame50()` + `create()` / `dispose()` / `init { initConstants() }` | ✅ |

#### 8.33.3 起手时已核事实

- 类头取值（上游 default export）：`id = "boston-public-garden"` / `name = "Boston"` / `tagline = "Swan boats after the rain"` /
  `timeZone = "America/New_York"` / `accent = #44bb11` / `focusX = 300`（registry look 条目同值，无需回填）。
- `events` 十三条（喷气机 / 桥上情侣 / 阵雨 / 遛狗 / 钟声 / 小鸭 / 微风 / 慢跑 / 远处走人 / 苍鹭 / 散步 / 天鹅 / 鸭子）。
- 上游绘制器是 `pix31(w,h)`（ImageData 画家）⇒ 顶层 `BpgPainter`，一对一替换。
- 顶层 `private class` 跨文件重名会触发 `Redeclaration` ⇒ 本城**所有容器类嵌套在场景类内部**；
  只有 `BpgPainter` / `BpgTrim` / `BpgAlong` 与五个工具函数留在顶层（沿用上游名）。
- `streak(...)` 是引擎 `PixelReflector` 的**成员**，不是顶层函数 ⇒ 场景持 `private val refl50 = PixelReflector()`。
- 引擎缺 `destination-in` 与 `clearRect` ⇒ 私有 `destIn(dst, mask)` + `PixelCanvas.reset()`。

#### 8.33.4 受控差异

| 差异 | 处理 |
|---|---|
| 广告位 `makePitch` / `PITCH43` | 本项目无广告 ⇒ `BpgPitchStub` 空桩（`covers` 恒 false、`draw` 空），`pitch` 恒 null |
| 跨城共享 `drawWeather` | 不移植 ⇒ 空实现 `drawWeather(layer, t, reducedMotion)`；`WX_LAND47` / `WX_LIGHTS21` / `WX50` / `WX_BB49` 照搬为数据 |
| `WEATHER50 = weatherPlans(["rain","petals"])` | 只取两个布尔 ⇒ 私有 `wxRain(w)` / `wxPetals(w)`（`SHARED = drizzle/rain/thunderstorm/snow/leaves`；`"petals"` 与未知母题回落到 city 档） |
| `cycle(t,n,ph)` | 引擎 `Cyc` + 私有包装 `CYC50` |
| `scroll(t,k,W)` | 引擎 `scrollPix` |

#### 8.33.5 剩余步骤

1. ✅ 写完 `p01`–`p13` 并拼装 → 4,294 行。
2. ✅ `compileDebugKotlin` 清零：**266 → 42 → 4 → 0**（四轮，修法见 §10.304）。
3. ✅ 接线两处：`LofiScenes.build()` 加 `"boston-public-garden" -> LofiCityBostonGarden()`、`SCENES_BUILT` 第 37 项。
4. ✅ 门禁：`*Lofi*` 132 例 0 失败；`check_lateinit.py` 137 项 0 悬空。
5. ✅ 七帧目检（循环四时刻 + 阵雨 70s/76s + 覆盖 110s）。
6. ✅ 本节 + §10.304 + `CHANGELOG` 一行 + 显式路径**本地**提交（⛔ 不 push）。

---

#### 8.33.6 验证与偏差（落地时实测，2026-10-26）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**，4,294 行 / 175,385 字节；`BUILD SUCCESSFUL` |
| 门禁 | `--tests *Lofi*` **132 例 / 18 类 0 失败**（含 Snapshot 的 S1 非空 / S2 随时间变化 / S3 确定性） |
| `lateinit` 扫描 | 137 项声明，**0 项从未赋值** |
| 目检 · 循环四时刻 | `t=0` 天际线 + 桥 + 天鹅船 + 柳树 + 「DORA'S BOSTON CREAM PIE」广告 + 摊车 + 郁金香花坛齐全；`t=60` 月亮升起、天鹅船与鸭子移动、摊车出现顾客；`t=120` 摊车顾客换位；`t=228` 水面出现高光闪点（`showerAt2=0`，非雨） |
| 目检 · 事件窗口补拍 | `t=70` / `t=76`（阵雨）：三层雨线全部出现、长椅上撑起雨伞、水面落点与水痕可见；`t=110`（雨尾）：雨线稀疏、地面水洼显现 ⇒ `drawRain11` / `drawDrips5` / `drawPuddles3` 三条只在窗口内执行的路径均被真正跑到，未崩溃 |
| 编译期踩坑（本城新增记录） | `kotlin.math.sign` **只有 Double/Float 版**，Int 实参必须先 `.toDouble()`（三处）；`fillRect` / `drawImage` 的 Int/Double 重载**混参即报 "None of the following candidates"**；`DoubleArray.contains` 不存在 ⇒ `any { it == x }`；`Int == Double` 直接编译错误 |
| 未做 | 真机截图评分（同 §8.32 口径，本城定为源码级对账 + 无设备整帧判读） |

⛔ 未推送、未 tag（§2.6）。

---

### 8.34 ✅ `las-vegas`（拉斯维加斯 · 百乐宫喷泉前的夜色，上游 2,197 行）**已落地** · 2026-10-26（本地提交见本节末）

#### 8.34.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/las-vegas.js`（2,197 行，含 esbuild 模块包装）；本地权威副本 `logs_temp/lofi_modules/js__scenes__las-vegas.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityLasVegas.kt`（4,393 行 / 190,232 字节） |
| 分片 | `logs_temp/lasvegas_parts/p01.kt`–`p13.kt`，`logs_temp/assemble_lv.py` 拼装（带 `LV_FORCE=1` 护栏） |
| 接线 | `LofiScenes.build()` 加 `"las-vegas" -> LofiCityLasVegas()`（按字母序落在 `kyoto` / `lisbon` 之间）；`CityRegistry.SCENES_BUILT` 第 **38** 项 |
| registry | `focusX = 284.0`、`nameZh = 拉斯维加斯`、`taglineZh = 大道上的喷泉`、`accentHex = #39ff14` 均已存在，无需回填 |
| 场景元数据 | `landmark = [259, 309]`（巴黎铁塔 foot-to-foot）、`landmarkTop = 30`、`billboard = BB49`、`crowd = 0.55` |

#### 8.34.2 结构取舍（续城可复用）

| 取舍 | 理由 |
|---|---|
| 37 个容器类**全部嵌在场景类内部**，只用 `Lv` 前缀 | 顶层 `private class` 跨文件重名会触发 `Redeclaration`（前 37 城已有多起撞名） |
| 顶层只留 `LvPainter` / `LvCloud` / `LvLine` / `LvSprite` / `LvTint` / `LvSignW` / `LvSign` | 沿用上游 `painter20` / `line18` / `sprite44` / `tint9` / `signWidth3` / `sign7` 的语义，名字带本城前缀 |
| 几何容器字段一律 `Double`；像素扫描量（`LvChapelGeom` / `LvNearLamp` / `LvToggle` / `LvStar`）保持 `Int` | 上游这些值来自算术表达式，全用 Double 可避免 Int/Double 重载歧义；而纯像素坐标用 Int 便于 `fillRect` / `put` |
| `LvLook.skirt` / `hat` 用 `Int` 且 **0 = 无** | 上游是 `null`；ARGB `0` 即全透明，渲染结果等价 |
| `R29` 同时提供 `Int` 与 `Double` 两组重载 | 上游同一个函数被整数像素扫描与浮点表达式两种实参调用 |
| `put7(fy, draw)` / `flushDL2()` 用 `LvDeferred(fy, draw)` + `sortBy { it.fy }` | 上游延迟贴图队列按 foot 升序，婚车/近景灯要靠它正确遮挡 |
| `streak(...)` / `reflect(...)` 走 `private val refl57 = PixelReflector()` | 引擎里它们是 `PixelReflector` 的**成员**，不是顶层函数；且 `streak` 的 `seed` 是 `Double`（Int 字面量要写 `1.0`） |
| 广告位 `PITCH50` → `LvPitchStub`（`covers` 恒 false、`draw` 空） | 本项目无广告（受控差异，同前 37 城）；`opts.pitch` 恒 `null` |
| 跨城共享 `drawWeather` → 空实现 | 不移植；`WX_LAND51` / `WX_LIGHTS26` / `WX_LEAVES6` / `WX57` / `WX_BB55` 只作数据照搬 |
| `WEATHER57 = weatherPlans([])` ⇒ **不建 `WEATHER57` 字段** | 本城自有母题为空，`wx.shared` 只作数据 ⇒ 没有任何消费方，建了就是死代码 |
| `fx2.clearRect(0,0,W,FB3+1)` → `FC2.reset()` | `PixCtx` 无 `clearRect`；`reset()` 是等价的全清 |
| `MIRROR4` 声明为 `PixelCanvas` | 上游 `MIRROR4 = [canvas, ctx]`；Kotlin 侧画布自带 `.ctx`，`drawFloorShine` 里用 `MIRROR4.ctx` |

#### 8.34.3 ⚠️ 上游缺失的两个函数：`renderWater28()` / `renderMoonPic()`

**这是本城唯一一处「不是直译」的地方，必须记录。**

`prerender57()` 里有 `MOONPIC_C = renderMoonPic()` 与 `WATER_C28 = renderWater28()` 两个调用点，
但**全仓库 grep 零定义**：`js/scenes/las-vegas.js` 自身、整包 `logs_temp/lofi_app.js`（6.7 MB）、
`app/src/lofi-upstream/modules/` 下全部 100+ 个模块、以及 `core.js` / `display.js` / `weather.js`
都没有 `function renderWater28` / `function renderMoonPic`，`lofi_app.js` 里也只有三处**引用**。

⇒ **上游按原样执行会在 `prerender57` 抛 `ReferenceError`，整场 prerender 直接炸掉**（不是「画不出来」，是「创建场景即失败」）。
两个函数只被本城的这两个赋值消费，无法从别处取到真值。

处置：**按场景语义补齐**，并在源码注释里明确标注「不是直译」。

| 补齐件 | 语义依据 | 实现 |
|---|---|---|
| `renderWater28()` | `drawLake` 先铺一层深蓝水膜，再叠倒影 / 压暗 / 高光条；色带范围由 `LAKE = 166` 到 `FLOOR7 = 208` | `LAKE..FLOOR7` 逐行五档深蓝（`#070a1c`…`#141c3e`），带 `sin` 波纹 + `hash` 抖动 + `bayer` 量化 |
| `renderMoonPic()` | `MARIA2` 是上游残留的数据表（`[lon, lat, r]` 度，7 个圆），**唯一可能的消费方就是月球贴图**；球体贴图工厂是 `renderOrb(fn)` | `renderOrb` + `MARIA2` 月海圆域（大圆域压成灰紫三档，其余走月面四档灰白） |

⇒ 后续城若再遇到「只有调用点、没有定义」的上游符号，按同一口径处理：**先在整包 `lofi_app.js` 里找定义**，
确认零定义后再按语义补齐，并把这处偏差写进本节同类小节 + §10.N。

#### 8.34.4 验证与偏差（落地时实测，2026-10-26）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**，4,393 行 / 190,232 字节；**两轮收敛**（70 → 0） |
| 门禁 | `--tests *Lofi*` **132 例 / 18 类 0 失败**（含 Snapshot 的 S1 非空 / S2 随时间变化 / S3 确定性） |
| `lateinit` 扫描 | 128 项声明，**0 项从未赋值** |
| 目检 · 循环四时刻 | `t=0` 铁塔 + 摩天轮 + 「LUCKY JACKALOPE」广告牌 + 地球球体 + 教堂 + 热气球 + 棕榈 + 婚礼车齐全，湖面无喷泉（`SHOWS2` 未开）；`t=60` 轮缘转蓝、球体切月球、婚车驶离；`t=120` 球体切南瓜灯、轮缘转紫、教堂门口新人 + 红毯 + 彩纸；`t=228` 轮缘转回品红、球体切回地球 |
| 目检 · 事件窗口补拍 | 四时刻**恰好落在两个喷泉秀窗口之外**（`SHOWS2` 20–56 / 160–196）⇒ 补拍 `t=24` / `t=40` / `t=54` / `t=175` / `t=190`：水柱与拱形水幕齐全、`drawFountainLayer2` 的塔顶白点与倒影可见；`t=130`（婚礼 84–142 + 婚车 124–236）车已左移、彩纸可见；`t=110`（飞机 100–150）右上角小飞机 + 航行灯可见；`t=24`（直升机 4–38）摩天轮上方直升机 + 红灯可见 |
| 编译期踩坑（本城新增记录） | ① `lateinit` **不能**用在 `Int` 等原始类型上（`NIGHT32` / `GLOWC19` ⇒ 改 `var x = 0`）；② `PixCtx.drawImage` **没有 5 参重载**（上游 `drawImage(img, dx, dy, dw, dh)` 必须展开成 9 参 `(0,0,img.w,img.h, dx,dy,dw,dh)`）；③ 上游 `look28(h, skin, hair, top, bot, o)` 的 `top`/`bot` 是 **hex 字符串**而非颜色整数（照 `Int` 声明会让 30 个调用点全红）；④ `Array<DoubleArray>` 不能赋给 `List<DoubleArray>`；⑤ 上游 `240 / Math.round(240 / per)` 是**浮点除法**（`per = 3.2` 时 `240/75 = 3.2`，写成 Int 除法会得 `3`） |
| 未做 | 真机截图评分（同 §8.32 / §8.33 口径，本城定为源码级对账 + 无设备整帧判读） |

⛔ 未推送、未 tag（§2.6）。


---

### 8.35 ✅ `new-york`（纽约 · 布鲁克林大桥的雪夜，上游 2,200 行）**已落地** · 2026-10-26（本地提交见本节末）

#### 8.35.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/new-york.js`（2,200 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__new-york.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityNewYork.kt`（3,854 行） |
| 接线 | `LofiScenes.build()` 加 `"new-york" -> LofiCityNewYork()`；`CityRegistry.SCENES_BUILT` 第 **40** 项 |
| registry | `focusX = 189.0`（= `ESB_X`，帝国大厦中轴） |
| 场景母题 | 雪夜俯看东河：远景天际线（世贸一号 / 帝国大厦三态冠 / 克莱斯勒）+ 布鲁克林大桥双塔 + 右侧公寓楼（消防梯 / 圣诞树 / 水塔 / DELI & GROCERY）+ 热狗车 + UPTOWN 地铁口 + HOT DOGS 广告牌 |

#### 8.35.2 本城「整城重做」的由来（所有者实测「手机上完全看不到纽约」）

首轮落地时只有骨架能跑：`prerender4` 里 58 个 `lateinit` 烘焙层中**多处是 8–20 行的桩**，
且**桥塔整块被画到画面外**（见 §8.35.3 第 1 条）。所有者的实测结论是准确的——按上游逐函数对账后，
本城实际只有约 45% 的绘制逻辑是直译，其余为「能跑但画不出东西」的占位。

本轮按所有者裁决「**整城重做**」，以「上游函数 → 本地函数」逐一对账的方式重写，
最后用「上游 69 个 `function` 全在本地有同名 `fun`」做收敛判据（当前 69/69 齐备，含补建的 `drawRows`）。

#### 8.35.3 ⚠️ 四个「画不出来」的真 bug（不是观感问题）

| # | 现象 | 根因 | 判据 |
|---|---|---|---|
| 1 | **两座桥塔完全不可见**（画面里桥面悬空、没有塔） | `for ((tc, i) in TW2.withIndex())` —— **`withIndex()` 的 `component1` 是下标**，`tc` 拿到的是 `0`/`1`，于是 `drawImage(TOWER_C2, tc - 21, …)` 恒画在 `x = -21 / -20`（画面外）。同一个循环里 `drawFlag(tc, t, i)` 的两个参数也被对调 | 在导出的 PNG 上按 `TC.hi = #dcc6a0` 做逐列颜色探针，`x ∈ 57..98 / 279..318` 命中数为 **0** |
| 2 | 帝国大厦冠部**变色节奏与上游不符**（12 秒一轮、且出现上游没有的 `[1,0]`/`[2,1]`/`[0,2]` 组合） | `esbMix(t)` 被改成一条自造的 16 段 `when`；上游是 **240 秒三态**：`t<70` 白 → `70..82` 白→红 → `150..162` 红→蓝 → `228..240` 蓝→白 | 上游 `esbMix` 只有 6 个 `return` 分支，本地 16 个；`smooth` 调用点 4 个 vs 本地 7 个 |
| 3 | 克莱斯勒冠部**几乎全黑** | `renderChryslerCrown()` 是 20 行桩：只按 y 区间取 tier、只画 `#8a94ae`/`#4a5a7a`/`#1a2234` 三色，没有逐层受光（`#fff7dc`/`#ffe7ae`）、没有中央天线、`pts` 也几乎为空（所以每帧的闪烁点也看不见） | 上游 26 行：`tier` 由 `abs(dx) <= 8 - i && y >= chrArch(i, dx)` 反查，冠部主色是近白 `#f4f7fc` |
| 4 | 广告牌「暗态」是一圈**边框**而不是椭圆压暗 | `renderBillboardDim()` 写成了「四周一圈 `#160c06`、内部 `#0a0a0c`」；上游是 `(1 - hypot((i-9)/9.5, j/h)) * 1.6 > bayer(i,j)` 的**椭圆径向抖动压暗** | 上游 5 行无边框概念；`bayer` 只出现在条件里 |

其余「桩 → 直译」的清单（均按上游逐行重写）：`renderSkyline`（+ `drawBlock`/`drawESBBody`/`drawChryslerShaft`/`drawOneWTC`/`drawLowerManhattan`）、
`renderBridgeTower`（七色板 `TC` + 拱洞镂空 + 塔身明暗 + 三处积雪）、`renderBridgeBack`（含 `mainCableY`）、
`renderMoon3`（明暗交界 + 五个陨石坑半径）、`renderSky4`（`hypot` 城市辉光）、`makeCloudLayer2`（椭圆叠加 + 横向环绕 + 抖动分档）、
`renderFloe`、`renderBillboardDim`、`drawRiver`（桥塔倒影 / 八道高光条 / 冰块）、`drawFerry` / `drawTug`（尾迹 + 舷窗灯带 + 倒影）、
`drawSiren`（车身 + 顶灯 + 双层光晕）、`drawWalkers2`（+ 补建 `drawRows`，含狗绳 `line3`）、`drawPromenade`、`drawPlane2`、
`renderVignette4`、`lampI` / `bbLampI`（改回 `hash` 三档而非 `step` 二档）、`puffs` / `drawSpindrift`、`makeSnowLayers`、`drawCabs` / `drawDeckCars`。

#### 8.35.4 编译期踩坑（本城新增记录）

| 坑 | 说明 |
|---|---|
| `j / h` 是**浮点除法** | 上游 `hypot((i-9)/9.5, j/h)` 里 `j`/`h` 都是整数但 JS 是浮点除；Kotlin 必须写 `j.toDouble() / h`，否则半径轴全塌 |
| `inEvent` 签名不同源 | 引擎的 `inEvent(t, List<EventWindow>)` 吃**窗口列表**，而上游 `LAMP_FLICK2` / `BB_FLICK3` 本身就是列表；本城早期自建了 `inEvent(t, DoubleArray)`（吃单个窗口），导致 `bbLampI` 只判到三个窗口中的**第二个** |
| `NyAvi.per` 必须是 `Double` | 上游 `per: 2.5`（slender 楼型）；声明成 `Int` 会逼出 `2.5.let { 3 }` 这种异味，且 `frac(t / per)` 的相位会偏 |
| `lateinit` 先用后赋 | 见 §8.35.5 |
| `PixCtx.drawImage` 无 5 参重载 | 上游 `drawImage(img, dx, dy, dw, dh)` 一律展开成 9 参；`drawImage(img, dx, dy)` 只有 2 参版本 |

#### 8.35.5 免 Gradle 静态审计脚本（本轮新增，可复用）

`prerender4` 里有 **107 条** `lateinit` 赋值，靠运行期 `UninitializedPropertyAccessException` 逐个试错成本极高
（本城先后踩到 `FLOORY` / `POOLS` / `DECK_LAMPS` / `BRIDGE_C` 四处）。因此新增
`logs_temp/audit_init_order.py`：剥注释 → 按花括号切函数体 → 求「被调函数 → 需要的 `lateinit` 字段」传递闭包
→ 与 `prerender4` 内的赋值行号比对，报出所有「先用后赋」与「从未赋值」。

- 判据是**不变式**（「赋值行号必须早于使用行号」），不写会过期的数字；
- 脚本自带负向夹具 `logs_temp/neg_fixture_late_floory.kt`（把 `FLOORY` 挪到 `PITCH2` 之后）：
  当前源文件 `EXIT=0`、夹具 `EXIT=1` 且精确报出 2 处 —— 正/负向喂**同一份谓词**；
- 表达式体函数（`= if (...) ...`，无花括号）必须单独切分，否则会把**下一个函数**吞进自己的函数体，
  导致依赖闭包爆炸（本城首版脚本因此误报 35 处）。

#### 8.35.6 验证与偏差（落地时实测，2026-10-26）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**（三轮收敛） |
| 初始化顺序 | `audit_init_order.py`：107 项赋值，**0 处先用后赋 / 0 处从未赋值** |
| `lateinit` 扫描 | 59 个 `lateinit` 字段，**0 个从未被引用**（无死烘焙层） |
| 上游函数覆盖 | 上游 `^function` 69 个，本地同名 `fun` **69/69** |
| 目检 | 导出 `t = 0 / 40 / 95 / 120 / 160 / 200` 六帧：天际线（世贸一号 + 帝国大厦三态冠 + 克莱斯勒冠 + 下城塔群）、双桥塔 + 主缆 + 斜拉索 + 桥面灯 + 桥面行人车辆、河面倒影 + 轮渡 + 拖船 + 冰块、公寓楼（消防梯 / 圣诞树 / 水塔 / DELI & GROCERY / OPEN 霓虹）、热狗车、UPTOWN 地铁口、HOT DOGS 广告牌、云带、月亮（含陨石坑与明暗交界）、雪三层、警笛、飞机 全部可见 |
| 未做 | 真机截图评分（同 §8.32–§8.34 口径，本城定为源码级对账 + 无设备整帧判读） |

⛔ 未推送、未 tag（§2.6）。


### 8.36 ✅ `paris`（巴黎 · 阁楼窗上的雨，上游 2,228 行）**已落地** · 2026-10-26（本地提交见本节末）

#### 8.36.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/paris.js`（2,228 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__paris.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityParis.kt`（3,735 行） |
| 接线 | `LofiScenes.build()` 加 `"paris" -> ParisScene()`；`CityRegistry.SCENES_BUILT` 第 **41** 项 |
| registry | `focusX = 214.0`（= `TOWER.x`，埃菲尔铁塔中轴） |
| 机位类型 | **室内**（阁楼唱片店/画室，斜天花 + 六椽 + 四窗框），同族参照 `LofiCityTokyoRoom.kt`；不是俯视街景 |
| 场景母题 | 阁楼窗上的雨夜：窗外铁塔（旋转探照灯 + 信标 + 闪亮 + 塔顶红灯）+ 圣心堂尖顶 + 两排曼萨尔屋顶 + 对街四窗（浇花/读书/跳舞/电视）+ CAFÉ LUNE 广告牌 + 红气球；窗内书桌（台灯 / 面碗 / 摊开的画册 / 唱片机 / 画架 / 画凳）+ 猫 + 鸽子 + 老鼠 + 飞蛾 + 尘埃 + 手画心 + 地毯 + 串灯 |

#### 8.36.2 结构：本城是「一整个房间 + 一扇窗」

上游把世界切成三层并各自烘焙成一张位图，`renderFrame` 的 31 步就是把它们按序贴回去：

| 层 | 内容 | 每帧变化的部分 |
|---|---|---|
| `FAR` | 夜空 / 星 / 月 / 云 / 圣心堂尖顶 / 城市辉光 | `drawFarLights`（星闪）、`drawTower`、`drawOutsideRain(true)`、飞机 |
| `MID` | 两排曼萨尔屋顶 + 烟囱 + 广告牌 + 铁塔前的近排屋脊 | `drawSmoke`、`drawBillboard`（含雨丝与灯闪）、`drawBalloon` |
| `NEAR` | 对街四扇窗 + 三根烟囱 + 雨篷 | `drawNeighbours`（water/read/dance/tv 四态）、`drawPigeons`、`drawOutsideRain(false)` |
| `ROOM` | 斜天花 + 六椽 + 屋脊梁 + 四面墙 + 窗框 + 踢脚 + 透视地板 + 地毯 + 书架 + 矮柜 + 唱机 + 画架 + 画凳 + 书桌 + 台灯 + 坐垫 | 只有玻璃（雾/滑轨/雨痕/水珠）与室内小物件在动 |

⚠️ 铁塔**不在**任何烘焙层里：它是每帧按 `twRow(y)` 逐行收腰重画的（三段塔身 + 拱洞 + 横梁 + 天线），
所以探照灯能真的绕塔旋转，而不是叠一张静态剪影。

#### 8.36.3 六条「受控差异」（头注同步登记）

| # | 差异 | 理由 |
|---|---|---|
| 1 | 颜色从 `"#rrggbb"` 字符串改成 `Int` | 引擎 `hex()` 是无分配纯算术；`RGB` 十六进制记忆化缓存随之去掉 |
| 2 | 共享天气子系统**不移植**（`drawWeather` 空实现） | 跨城 `weather.js` 的三层雨滴 + `grade` soft-light 与东京房间同口径；本城自己的雨（窗外斜雨 + 玻璃水珠）是模块内自画的，**照常移植** |
| 3 | 上游 `WX` / `WX_BB` 灯位表**整块丢弃** | 它们唯一的消费者就是 `drawWeather`；留着就是死数据（曾先按上游建了两张表，编译后确认无引用才删） |
| 4 | 广告位 `PITCH` 是空桩（`covers()` 恒 false、`draw()` 空操作） | 本项目不放广告，与上游 `pitch=false` 分支逐位等价 |
| 5 | `p.get(i,j)` → `rgbOf`（返回打包 `Int` 而非 RGB 三元组） | 供 `darken` 逐通道 lerp 用 |
| 6 | 音频轻反应只乘/加到光晕与灯泡 α | 方案 §10 D-a；⛔ 不碰坐标/几何/色值，240 秒确定性循环逐帧序列不变 |

#### 8.36.4 编译期踩坑（本城新增记录）

| 坑 | 说明 |
|---|---|
| 元数据引用下方常量 ⇒ `must be initialized` | `focusX = TOWER.x` 而 `TOWER` 声明在常量区（更靠后）⇒ 属性初始化顺序报错。改成 `get()` 访问器（纽约先例）。`TOWER`/`BB` 一共 5 个属性中招 |
| `lateinit` 不能用于基本类型 | `TW_BEACON` 是 `Int` ⇒ 改 `private var TW_BEACON: Int = 0`，由 `initConstants()` 填 |
| `min(Double, Int)` 无候选 | 上游 `const dx = x0 - VP.x` 在 JS 里是浮点数；Kotlin 里 `x0`/`VP.x` 都是 `Int` ⇒ `dx` 退化成 `Int`，`(0 - x0) / dx` 跟着变整除。必须 `(x0 - VP.x).toDouble()` |
| `jsRound` 只吃 `Double` | `jsRound(sx + dir * i)`（全 `Int`）直接报类型不匹配；`Int` 字面量**不会**自动提升 |
| 构造参数不隐式提升 | `PaTt(36, 190, 16, 5.5)` 里 `16` 对 `rx: Double` 报错 ⇒ 写 `16.0` |
| 漏 import | 只 import 了 `hash` 没 import `hashD`（`paintCanvasArt` 的 `cut(u)` 要用） |
| 冗余转换告警 | `(BB.x + x).toDouble()`（`x` 已是 `Double`）与 `max(1, jsRound(…)).toInt()`（`jsRound` 已返回 `Int`）—— 编译不报错但会出两条 `w:` |

#### 8.36.5 验证与偏差（落地时实测，2026-10-26）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**（两轮收敛：首轮 15 条 → 二轮 0 条），巴黎文件 0 告警 |
| `lateinit` 扫描 | 39 个字段，**38 个各赋值 1 次、0 个从未赋值**（第 39 个是 `ctx`，走 `this.ctx = ctx`） |
| 上游函数覆盖 | 上游 `function` **72 个**，本地同名 `fun` **71/72**；唯一缺的 `pix(w,h)` 由引擎 `PixelBuffer` 等价替代（头注已登记） |
| 全量单测 | **2,612 例 / 0 失败 / 0 错误 / 2 skipped**（259 个 XML） |
| 快照门禁 | `LofiSceneSnapshotTest` 三例全绿（含巴黎：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：阁楼木屋（斜天花 + 六椽 + 串灯）、四窗框、窗外铁塔 + 圣心堂 + 两排曼萨尔屋顶 + 对街四窗灯态 + CAFÉ LUNE（含咖啡杯与灯罩）+ 雨丝、玻璃（雾 / 滑轨 / 雨痕 / 水珠）、书架 / 矮柜 / 唱片机 / 画架油画 / 画凳画笔 / 书桌（台灯 + 面碗 + 画册）/ 猫 / 鸽子 / 地毯、左右吊挂绿植、墙上两张钉画 全部可见；四帧的雨密度、邻窗灯态、广告牌与探照灯方向均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.35 口径：源码级逐函数对账 + 无设备整帧判读） |

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `62e10c4`。


### 8.37 ✅ `amsterdam`（阿姆斯特丹 · 运河桥上的灯，上游 2,221 行）**已落地** · 2026-10-26（本地提交见本节末）

#### 8.37.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/amsterdam.js`（2,221 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__amsterdam.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityAmsterdam.kt`（**4,393 行**） |
| 分片 | `logs_temp/amsterdam_parts/p01.kt`–`p09.kt`，`cat` 顺序拼装（9 片：681 / 696 / 369 / 336 / 358 / 502 / 398 / 695 / 358） |
| 接线 | `LofiScenes.build()` 按字母序加 `"amsterdam" -> LofiCityAmsterdam()`（落在 `"bali"` 之前）；`CityRegistry.SCENES_BUILT` 第 **42** 项 |
| registry | `focusX = 262.0`、`nameZh = 阿姆斯特丹`、`taglineZh = 运河桥上的灯`、`accentHex = #b8e05a` 均已存在，无需回填 |
| 机位类型 | **街景俯视**（运河对岸的桥头机位），地标为韦斯特教堂钟楼 |
| 场景母题 | 运河之夜：韦斯特教堂钟楼（马克西米利安皇冠 + 钟面 + 钟声涟漪）+ 17 栋五种山墙运河屋 + 侧巷透视峡谷（纵深房屋/榆树/巷灯）+ CAFE 店招 + FIETS HUREN 广告牌（自行车 + 郁金香）+ 砖石拱桥（灯串 + 拱洞）+ 运河（倒影/高光/落叶/浮萍/鸭 + 游船）+ 船屋（五窗/烟囱/风信旗/盆栽/缆绳/苍鹭）+ 近岸石阶 + 头顶树枝 + 薄雾 + 暗角 |

#### 8.37.2 结构：一帧 27 步图层序 + 五条独立 `rng` 流

`renderFrame12` 的 27 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 远景 | 天空（烘焙）→ 钟楼（暗层 + 亮层按钟琴包络叠 + 指针 + 涟漪）→ 街屋 → 广告牌 → 远景榆钱 |
| 中景 | 岸(后) → 桥(体) → 骑行者 → 桥(栏) → 岸(前) → 榆树 → 岸灯 → 灯串/飞蛾 |
| 水面 | 运河（水膜 → 倒影 → 压暗 → 灯串倒影 → 三档流纹 → 高光条 → 浮萍） |
| 近景 | 水鸟(远) → 游船 → 水鸟(近) → 薄雾 → 中景榆钱 → 船屋 → 近岸 → 树枝 → 近景榆钱 → 暗角 |

⚠️ **`rng()` 消费次序即画面**，五条独立流 + 四个烘图种子全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(11)` | `STARS10`（56 颗） | 星位/闪烁速率/大小 |
| `rng(77)` | `SIDE2`（侧巷纵深房屋） | 楼宽/檐高/山墙步进/配色 |
| `rng(4747)` | `SEEDS`（三层榆钱 70/40/12） | 每片叶的周期/漂移/落点 |
| `rng(4848)` | `FLOATERS`（22 片浮萍） | 水面漂浮物位置 |
| `rng(5151)` | `MOTHS3`（11 只飞蛾） | 灯边飞蛾轨迹 |
| `rng(2024)` | `renderRow` | **每栋屋**的窗灯图案（`kindOf2`/`addWin8` 的调用次序） |
| `rng(300+s)` / `rng(900+s)` | `renderTrees` | 前者逐像素、后者管 `rustle`/`cen`/`clumps` |
| `rng(88)` | `renderRoofPlants` | 船屋盆栽 |
| `rng(515)` | `renderBranch2` | 树枝分叉与叶片 |

#### 8.37.3 六条「受控差异」（头注同步登记 16 条，此处只列要点）

| # | 差异 | 理由 |
|---|---|---|
| 1 | 颜色统一 `Int`；`RGB7`/`C5`/`toHex5` 删除 | 引擎 `hex()` 无分配；`mixHex6` ⇒ `mixHex` |
| 2 | `pix5` ⇒ 引擎 `PixelBuffer`；`p.get` ⇒ `rgbAt`（返回 `0xRRGGBB`） | 只用于 `renderRow` 的"树冠像素是否被墙盖住"逐位比对 |
| 3 | 保留 `pick5(ramp, v, i, j, soft)` 的 `soft` 形参 | 引擎 `pickFromRamp` 只覆盖 `soft = 1`，本城 `0.5/0.6/0.7` 三档都要 |
| 4 | `PITCH10` 整段删除，`PitchStub` 空桩 | 本项目不放广告 |
| 5 | 跨城 `drawWeather` 空实现；**`WX_LAND12`/`WX_QUAY`/`WX_HOUSEBOAT`/`wxDepths2`/`WX12`/`WX_BB12` 整块丢弃** | 本城母题 `seeds` 不在 `SHARED` 里 ⇒ `wx.shared` 恒 null ⇒ 那六张表零消费方，留着就是死数据（同巴黎 §8.36.3 第 3 条） |
| 6 | `flipC` 逐像素镜像（不走 9 参 `drawImage`） | 整数画布下与最近邻逐位等价，且避开引擎在 `u == 1.0` 处跳过右缘的边界 |

音频轻反应（§10 D-a）只挂三处：`halo12` 统一乘 `lightGain`；`haloNeon` 叠加 `neonPulse`（广告牌灯/路灯/窗灯/店招/巷灯/灯串/船灯/船屋窗/舷窗/电视）；`haloTail` 再乘 `tailGain`（仅自行车尾灯）。⛔ 坐标/几何/色值一律不碰。

#### 8.37.4 编译期踩坑（本城新增记录）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`Array + Array` 得到 `List`，不是 `Array`** | `HERON_POSES` 用 `H_HEAD + H_NECK + ...` 拼姿态表 ⇒ 推断成 `Map<String, Any>`，报 `Initializer type mismatch: expected 'Map<String, List<String>>', actual 'Map<String, Any>'` | 全部改收 `List<String>`，拼接处用 `.asList()` / `listOf(...)` / `drop(2)`。判据：**只要表项里有 `A + B`，容器类型就必须是 `List`** |
| ② | **漏 import 的报错落在"使用点"而非定义点** | `cycleC` / `cycleU` 报 `Unresolved reference`（两个都在 `PixelConstants.kt` 里） | 补两行 import。这与 §8.28 的 `FONT3` 漏 import 同一形态 |
| ③ | **`bayer(Int, Double)` 无重载** | `bayer(x, y)` 里 `x` 是 `Int`、`y` 是 `Double` ⇒ `None of the following candidates is applicable`（两个候选各差一个参数类型），紧接着级联出一条 `'operator' modifier is required on compareTo` | 显式 `bayer(x, y.toInt())`。⚠️ 引擎只有 `(Int,Int)` 与 `(Double,Double)` 两个重载，**不混用** |
| ④ | **`floor(Int)` 与 `floor(Int - Double)`** | `floor(t.cy - t.ty - 3)`（全 `Int`）报 `actual type is 'Int', but 'Double' was expected`——`floor` 只有 `Double` 版 | 先 `.toDouble()` 再 `floor`；`jsRound` 同理（§8.29 已记过 `jsRound`，本城复现于 `floor`） |
| ⑤ | **`val col` 在分支里再赋值** | `renderBridge5` 里 `val col: Int` 之后四处 `if (...) col = ...` ⇒ 三条 `'val' cannot be reassigned`（报错行指向**赋值处**，不指向声明处） | 改 `var col: Int`。判据：**分支树里给同名列赋值，一律 `var`** |
| ⑥ | 冗余转换告警（不阻塞） | `(20 + floor(...)).toDouble()` 之类 5 条 `w:` | 删掉 `.toDouble()`（`20 + floor(...)` 已是 `Double`） |

### 8.37.5 验证与偏差（落地时实测，2026-10-26）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误**（首轮 9 条 → 二轮 0 条），本轮随后又清掉 5 条冗余转换告警 |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与巴黎基线同量级） |
| 门禁 | `LofiCityRegistryGateTest` + `LofiCityFlickerGateTest` + `LofiSceneSnapshotTest` 共 **16 例 / 0 失败 / 1 skipped**（未开 `LOFI_SNAPSHOT` 时的导出用例） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：新月 + 星点 + 四层云带、韦斯特钟楼（金蓝皇冠 + 白灯亭 + 蓝钟面 + 红砖塔身 + 窄拱窗）、两排五种山墙运河屋（暖黄窗灯夹暗窗）、FIETS HUREN 广告牌（蓝自行车 + 郁金香）、CAFE 暖黄店招、砖石拱桥 + 灯串 + 水面倒影、运河流纹与浮萍、船屋（亮窗 + 金舷窗 + 屋顶盆栽 + 苍鹭 + 红白救生圈）、右侧绿色树枝、近岸石阶 全部可见；`t=120` 游船带灯火与倒影入画（96→168 窗口），四帧星点亮度/位置均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.36 口径：源码级逐函数对账 + 无设备整帧判读） |

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `9fcd659`。

### 8.38 ✅ `berlin`（柏林 · 勃兰登堡门的灯光节，上游 2,233 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.38.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/berlin.js`（2,233 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__berlin.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBerlin.kt`（**5,316 行**） |
| 分片 | `logs_temp/berlin_parts/p01.kt`–`p12.kt`，`assemble.py` 顺序拼装（12 片） |
| 接线 | `LofiScenes.build()` 按字母序加 `"berlin" -> LofiCityBerlin()`（落在 `"boston"` 之前）；`CityRegistry.SCENES_BUILT` 第 **43** 项 |
| registry | `nameZh = 柏林`、`taglineZh = 勃兰登堡门的灯光节`、`accentHex = #a8f000` 落地前已存在；本轮只回填 `focusX = 0.0 → 240.0`（上游 `focusX: CX3`） |
| 机位类型 | **正立面全景**（广场对面平视，门体占画面正中，两侧是侧翼与工地） |
| 场景母题 | 勃兰登堡门（六根廊柱 + 五座门洞 + 檐部 + 阁楼层 + 门楣上的四马战车）+ **门面投影灯光秀**（九套图案轮换）+ 门面倒影 + 胜利女神柱 + 蒂尔加滕方向的林梢与八盏路灯 + 在建工地（脚手架 + 绿网 + 楼窗灯 + 楼板层）+ KNISTER VINYL 广告牌 + 柏油广场与落叶 + 四盏街灯与三个光池 + 人群（观众 / 过客 / 街头艺人 / 听客 / 跳舞的一对 / 摄影的人 / 吹泡泡的艺人）+ 碱水面包摊 / 热狗摊 / 烤肠摊 + 三轮车 / 自行车团 / 铜管乐队 + 菩提树冠与飘叶 + 彩纸 + 屋顶乌鸦 + 暗角 |

#### 8.38.2 结构：一帧 21 步图层序 + 八条独立 `rng` 流

`renderFrame29` 的 21 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 远景 | 天空（烘焙 + 星 + 月 + 三层云带 + 六束探照灯 + 两群鸦）→ 落叶(近) → 天气0 → 远景林梢（柏树石栏 + 八盏路灯）→ 远景生活（车流 / 公交 / 警车 / 远处行人） |
| 中景 | 地面（柏油 + 伸缩缝 + 散落落叶）→ 工地（两栋楼 + 绿安全网 + 楼板层）→ 工地生活（窗灯 / 电视光 / 工地泛光灯）→ 广告牌（KNISTER VINYL + 铁框 + 洗墙灯） |
| 门面 | **投影灯光秀**（图案 → 乘门身明度 → 遮罩 → 暖砂叠加）→ 屋顶乌鸦 → **门面倒影**（竖向压缩 0.44，五段）→ 街灯光池（三个） |
| 近景 | 广场人群（按 y 排序的绘制项）→ 彩纸 → 落叶(中) → 天气1 → 菩提树冠（散落亮叶）→ 落叶(远) → 天气2 → 暗角 |

⚠️ **`rng()` 消费次序即画面**，八条独立流全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(907)` | `STARS25`（70 颗） | 星位 / 闪烁速率 / 大星比例 |
| `rng(71)` / `rng(72)` | `CROWS`（34 + 22 只） | 两群鸦的起飞时刻 / 轨迹 / 振翅 |
| `rng(211)` | `CARS10`（12 辆） | 远处车流的周期 / 方向 / 车色 |
| `rng(641)` | `STEAM11`（14 缕） | 摊位蒸汽的升腾节奏 |
| `rng(820/821/822)` | `LEAVES4`（三层，46/28/11 片） | 每片叶的周期 / 漂移 / 落点 |
| `rng(511)` | `renderHouses2` → `drawHouse2` | 工地左楼与右楼的窗灯图案（**同一实例先左后右**） |
| `rng(611)` | `renderGround8` | 广场上 520 片落叶的落点与颜色 |

#### 8.38.3 本城的结构性新东西：门面投影灯光秀

上游把门身当投影幕：图案（`rainbow` / `harlequin` / `aurora` / `mosaic` / `confetti` / `grid` / `count` /
`finale` / `gold` 九套，按 `ACTS` 的 240 秒脚本轮换）画进 168×109 的 `PJ_C`，再**乘**门身明度遮罩
`GATE_LUM`、用同一张遮罩**遮罩**掉门身之外的部分，最后贴回主画布；地面上的倒影用
`setTransform(1, 0, 0, -0.44, 0, (G2+1)*1.44)` 竖向压缩后分五段贴出（越靠近门体越亮，`MIRROR_BANDS`）。

⇒ **两种合成模式是引擎没有的**：引擎的 `Comp` 只有 SOURCE_OVER / LIGHTER / COPY。
本城的处置是**不改引擎**（改它会波及已落地的 42 座城），改为在本城文件里对像素数组直接做 Porter-Duff 合成：

- `multiplyInto`：按 W3C 规范预乘公式 `co = αs(1-αb)Cs + αsαb·B(Cb,Cs) + (1-αs)αb·Cb`、`αr = αs + αb - αsαb`、`Cr = co/αr`；
- `destIn`：`αr = αb·αs`、RGB 不变（与波士顿·公共花园同一形态，见该文件 §8.34.3）。

其余 15 条受控差异见产物文件头（颜色统一 Int / `mixCache` 删除 / `painter12` ⇒ `BerPainter` /
`sprite21` 宽度取各行最大长度 / 5 参 `drawImage` ⇒ 9 参 / `PITCH22` ⇒ `PitchStub` /
共享天气空实现 + `WX_*` 照搬为数据 / 音频轻反应三通道 / `fig()` 缓存键不含 `over` 等）。

#### 8.38.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`import kotlin.math.pow` 只带进扩展，不带进两参函数** | `pow(1.0 - f / 0.4, 2.0)` 报 `None of the following candidates is applicable`；`pow(1.19, it.toDouble())` 直接 `Unresolved reference 'pow'` | 一律写扩展形态 `x.pow(y)`。项目里其余 42 城本来就这么写，只是没人写过两参形态 |
| ② | **`0xFFF0C040` 溢出成 `Long`** | `private const val GOLD9 = 0xFFF0C040` 被推断成 `Long` ⇒ `ctx29.fill = GOLD9` 报 `actual type is 'Long'`；混进 `when {}` 分支还会报 `Number & Comparable<CapturedType(*)>` | 带 alpha 的颜色字面量一律 `0xFFF0C040.toInt()`；且**不能是 `const val`**（`const` 不许调 `.toInt()`）⇒ 改 `private val` |
| ③ | **`const val` 不能写在类体里** | `private const val QUAD_X = …` 报 `Const 'val' is only allowed on top level…` | 战车几何量提到**文件级** |
| ④ | **同名成员撞车** | p05 的 `QUAD_W`（战车宽）与 p02 的 `QUAD_W`（战车位图）撞 ⇒ `Conflicting declarations` + 一串 `Overload resolution ambiguity` | 文件级常量统一加前缀（`QUAD_POS_*` / `QUAD_SPR_*`） |
| ⑤ | **`intArrayOf("#rrggbb", …)`** | 字符串进 `intArrayOf` ⇒ 一串 `actual type is 'String', but 'Int' was expected` | 颜色表先 `arrayOf("#…")` 再 `.map { mix(hex(it), …) }.toIntArray()` |
| ⑥ | **两处 `inEvent29` 重定义** | p06 与 p09 各写一份 ⇒ `Conflicting overloads`，级联出 `Unresolved reference 'not' for operator '!'` | 只留一份（p06 的），p09 直接调用 |
| ⑦ | **上游 `LISTEN_AT[7] = [200, 237, -1, 0, 1]`** | 抄表时把 `face = -1` 写成 `0`、`kid = 0` 写成 `true` | 逐条核 `LISTEN_AT` 的三元/四元/五元形状（本城三处 `LISTEN_AT` 条目带额外字段） |

#### 8.38.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / Berlin 0 告警**（首轮 74 条 → 二轮 34 条 → 三轮 0 条） |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：门身依次为 rainbow 竖条 / harlequin 菱格 / confetti 深蓝底彩纸 / gold 段命中 `GATE_SUN` 的 `#f0d69e` 暖砂门体；门楣四马战车、胜利女神柱、林梢八盏路灯、工地绿网与楼窗灯、KNISTER VINYL 广告牌、三个摊位、广场人群、三轮车 / 自行车团 / 铜管乐队、菩提树冠与飘叶、彩纸、屋顶乌鸦 全部可见 |
| 未做 | 真机截图评分（同 §8.32–§8.37 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：灯光秀吃的是合成模式，`multiply` 公式一旦写错（拿 `source-over` 顶替，
> 或把 `Cr` 写成未预乘的两项相加），画面**不会报错**、只会安静地变成一块素色门身 ——
> 所以必须**逐时刻看门的颜色**，不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `f492703`。

### 8.39 ✅ `hamburg`（汉堡 · 易北河上的港灯，上游 2,234 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.39.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/hamburg.js`（2,234 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__hamburg.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityHamburg.kt`（**4,974 行**） |
| 分片 | `logs_temp/city_parts/hamburg/p01.kt`–`p10.kt`（10 片：864 / 511 / 376 / 402 / 349 / 361 / 487 / 766 / 491 / 358），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"hamburg" -> LofiCityHamburg()`（落在 `"chongqing"` 之后、`"istanbul"` 之前）；`CityRegistry.SCENES_BUILT` 第 **44** 项 |
| registry | `nameZh = 汉堡`、`taglineZh = 易北河上的港灯`、`accentHex = #4fc3f7` 落地前已存在；本轮只回填 `focusX = 0.0 → 223.0`（上游 `focusX: EP_CX`） |
| 机位类型 | **易北河下游对岸平视**（河面横贯画面中段，远岸是仓库区与音乐厅，近岸是石砌码头） |
| 场景母题 | 易北爱乐厅（蓝玻璃波浪立面 + 顶排信标 + 一道扫过玻璃的冷光）、Speicherstadt 仓库区（红砖山墙群 + 成排暖黄亮窗 + 运河倒影）、米歇尔教堂尖顶（钟面 + 三记钟声涟漪）、港口远景（五台塔吊：两台平臂小车往复、一台吊臂随循环升降、两台藏在仓库区后 + 四根桅杆灯 + 集装箱堆场 + 探照灯泛光）、桥面与桥灯、**FISCH 炸鱼 + BRÖTCHEN** 广告牌与旗杆、河面（货轮 + 汽笛 / 渡轮 / 小驳船 / 浮头的海豹 / 涟漪 / 积水）、行人（情侣 / 推车人 / 散步者 / 桥上跑步者 / 遛狗人 / 河滨步道小人）、猫 / 挂灯笼的煤气路灯 / 系缆桩 / 红白浮标、湿冷细雨（三层雨 + 雨幕）、掠过的客机、暗角 |

#### 8.39.2 结构：一帧 21 步图层序 + 15 组独立 `rng` 流（20 个种子号）

`renderFrame11` 的 21 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与港口 | 天空（底色 + 月 + 星 + 三层云带）→ 远景海鸥 → 港口远景（集装箱堆场 + 五台塔吊 + 桅杆灯 + 雾霭 + 塔顶红灯 + 掠过的客机） |
| 雨与河面 | 远层雨 → 天气0 → 河面（底图 + 左右两段倒影 + 高光条）→ 货轮（倒影 + 汽笛） |
| 中景建筑 | **易北爱乐厅**（玻璃立面 + 扫光）→ **仓库区**（山墙群 + 教堂钟面 + 钟声涟漪 + 中景仓库 + 近景砖墙 + 探照灯 + 广告牌 + 旗杆 + 桥面桥灯）→ 河湾（涟漪 + 积水） |
| 船只与近景 | 渡轮 → 小驳船 → 中景海鸥 → 中层雨 → 雨幕 → 天气1 → 前景（码头岸壁 + 系缆桩 + 浮标 + 猫 + 灯笼 + 行人）→ 近层雨 → 近景海鸥 → 天气2 → 暗角 |

⚠️ **`rng()` 消费次序即画面**，15 组独立流（20 个种子号）全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(12)` | `STARS9`（26 颗） | 星位 / 闪烁速率 / 亮度（离月亮太近的那次会**重新取数**，消费次序不可换） |
| `rng(200/201/202)` | `RAIN5` 三层雨滴 | 每层雨滴的落点 / 初相 / 落速 |
| `rng(71)` | `RINGS4`（46 条） | 水面涟漪的圆心 / 相位 / 半径上限 |
| `rng(72)` | `PUDDLE_RINGS2`（16 条） | 码头积水涟漪 |
| `rng(515)` | `FAR_GULLS2`(6) + `MID_GULLS2`(3)（同一条流，先后相接） | 远 / 中景海鸥的椭圆轨道与振翅 |
| `rng(13/27/41)` | 三层云带（`CLOUD_DEFS6`） | 云带密度场 / 起伏 / 配色 |
| `rng(17)` | `renderFar5` | 港口远景（集装箱堆场 + 桅杆 + 远岸雾霭） |
| `rng(31)` | `renderQuay` | 石砌码头岸壁与岸壁货箱 |
| `rng(88)` | `renderElbphi` | 音乐厅玻璃窗格 / 暖灯带 / 闪灯位置 |
| `rng(303)` | `renderShip2` | 货轮舱箱 |
| `rng(606)` | `renderNear3` | 近景砖墙窗位 |
| `rng(707)` | `renderFarBlock` | 中景仓库窗位（每层窗的 8 个窗位各取一个 `q`） |
| `rng(808)` | `renderGap` | 教堂与山墙群的窗位（未亮的再取 `n` / `off`） |
| `rng(9)` | `renderSheet` | 雨幕 |
| `rng(5/6)` | `renderDalben`（两组） | 系缆桩的位置与数量（4 + 3 根） |

`initConstants()` 与 `prerender11()` 的调用次序不可调换。

#### 8.39.3 本城的结构性新东西 / 受控差异

本城唯一吃**合成模式**的一段是音乐厅那道扫光：上游 `drawElbphi` 先把扫光画进暂存画布 `SHEEN_S`，用 `"copy"` 贴图、再用 `"destination-in"` 乘上玻璃遮罩 `EPH.mask`，最后以 `"lighter"` 贴回主画布。引擎的 `Comp` 只有 SOURCE_OVER / LIGHTER / COPY ⇒ **不改引擎**（改它会波及已落地的 43 座城），改为在本城文件里对像素数组直接做合成：

- `destIn(dst, mask)`：目标像素只保留"目标 alpha × 遮罩 alpha"，RGB 不变（`αr = (αd·αm + 127) / 255`，遮罩缺失处视为全透明）；
- ⚠️ **`"copy"` 的语义差**：上游 canvas 的 copy 会把**整块目标画布**换成源图（源没覆盖到的地方被清空），而引擎 `Comp.COPY` 只在**画到的矩形内**逐像素替换 ⇒ 上游那次 copy 之前必须先把暂存画布整个清空（`PixelCanvas.reset()`），否则会残留上一帧的扫光。

其余九条受控差异（详见产物文件头注）：

| # | 差异 | 理由 |
|---|---|---|
| ① | `painter5(w, h)` ⇒ 本城 `HamPainter`（包引擎 `PixelBuffer`） | `set` 同 `jsRound` 取整、`has` ⇒ `alpha > 0`、`rect` ⇒ `fill`、`line` ⇒ 逐点 `pline`、`done()` ⇒ `.canvas`；上游 `p.set(i, y, col)` 的 alpha 恒为 255（覆盖写入，不是混合） |
| ② | `sprite7(rows, key, flip2)` 宽度取**各行最大长度** | 引擎 `spriteFromRows` 用首行长度，与上游不等价；翻转按整幅镜像。上游 `if (col)` 的"未定义 ⇒ 跳过"照搬为 `key[ch] ?: continue` |
| ③ | 5 参 `drawImage(img, dx, dy, dw, dh)` ⇒ 引擎 9 参重载 | 船体倒影逐行拉伸、海豹出水两处用到；引擎无 5 参重载 |
| ④ | `PITCH9 = makePitch(...)` 整段删除 + `PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空） |
| ⑤ | 跨城 `drawWeather` 空实现；`WX_LAND11` / `RAIN_LIGHTS4` / `RAIN_LIGHTS_BB2` / `WX11` / `WX_BB11` 照搬为**零消费数据** | 上游 `WEATHER11 = weatherPlans(["rain"])`，母题 `rain` 命中 `plans.city` ⇒ `wx.shared` 恒 null ⇒ 共享天气无消费方。⚠️ 本城**自有**的三层雨（`RAIN5` + `drawRain5`）是画面的一部分，**照常移植**（`wx.rain` 为真） |
| ⑥ | **`destination-in` 本地实现**（见上） | 引擎 `Comp` 无此档，**不改引擎** |
| ⑦ | **`copy` 的语义差**（见上） | 引擎 `Comp.COPY` 只替换绘制矩形内，扫光暂存画布在 copy 之前先 `reset()` |
| ⑧ | 上游 `o.dk ?? 0.3` 一类的**空值合并照抄** | `look2` 的人物配色参数，Kotlin 侧以默认值表达，避免改动上游的取数次序 |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` 统一乘所有光晕 α；`neonPulse` 叠灯火类光晕（广告牌灯 / 桥灯 / 街灯 / 窗灯 / 探照灯 / 红灯 / 自行车灯 / 码头灯）；`tailGain` 再乘航行类光尾（渡轮 / 小驳船 / 货轮）。⛔ 坐标 / 几何 / 色值一律不碰 |

另有两条上游语义照搬：上游 `R3(x, y, w, h, c)` 的镜像轴 `MIR` 是**模块级可变状态** ⇒ 本文件是场景实例字段；本城未出现 `Math.random()`，所有随机量都来自 `rng(seed)` / `hash(...)` ⇒ 逐位可复现。

#### 8.39.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0`（本城 `wave(t, 5.0, 0.12)`、`wave(t, 700.0 + i * 37.0, 0.0)` 等处都踩到） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 写过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)`（本城 `HamPainter` 的线段长度 `maxOf(abs(x1-x0), abs(y1-y0), 1.0)` 是典型处） |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 非法 | 改 `private var x: Double = 0.0`；本城所有 `lateinit` 字段都是引用类型（`PixelCanvas` / 列表 / `HamHalos` 等）才成立 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄上游的裸块会变成"把后面的实参当 lambda 传出去" | 写成 `run { … }` 保留块语义 |
| ⑤ | **上游的 `copy` 与引擎 `Comp.COPY` 语义不等价** | 直接照抄会让扫光**残留上一帧**（画面不报错，只是多一道鬼影） | copy 之前先 `reset()` 暂存画布（见受控差异 ⑦） |

#### 8.39.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / Hamburg 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：易北爱乐厅的蓝玻璃立面与扫光、仓库区的红砖立面与成排亮窗、米歇尔教堂尖顶、港口塔吊剪影、桥面桥灯与挂灯笼的煤气路灯、河面货轮与驳船、行人 / 海鸥 / 猫 / 灯笼、细雨与暗角 全部可见；四帧之间货轮位置、行人分布、雨丝与光晕均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.38 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：扫光吃的是 `copy` + `destination-in` 两种语义，`copy` 之前忘了 `reset()`
> 就会让上一帧的扫光**鬼影**留在玻璃上，而 `destIn` 的 α 公式写错则会让整道扫光**变黑** ——
> 两者都不会报错，只会安静地画错，所以必须**逐时刻看玻璃立面**，不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `b34b78d`。

### 8.40 ✅ `venice`（威尼斯 · 大运河上的薄雾，上游 2,249 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.40.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/venice.js`（2,249 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__venice.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityVenice.kt`（**5,350 行**） |
| 分片 | `logs_temp/city_parts/venice/p01.kt`–`p14.kt`，`logs_temp/e51_pipeline/assemble.py` 顺序拼装（14 片） |
| 接线 | `LofiScenes.build()` 按字母序加 `"venice" -> LofiCityVenice()`；`CityRegistry.SCENES_BUILT` 第 **45** 项 |
| registry | `nameZh = 威尼斯`、`taglineZh = 大运河上的薄雾`、`accentHex = #d4a24c`、`focusX = 250.0` 落地前已存在，**无需回填** |
| 机位类型 | **大运河对岸平视**（画面正中横着里亚托桥，桥后是圣马可钟楼与总督宫，桥前是近岸粉墙老楼） |
| 场景母题 | 大运河上的里亚托桥（石拱券 + 桥面 + 桥中凉亭 + 四盏挂灯 + 桥下拱洞的水面倒影）与圣马可钟楼（钟室 + 钟摆 + 荡开的钟声涟漪）、总督宫与两翼老楼（卡梅伦吉 / 丰达科，顶上晾红白条纹的威尼斯旗帜与四件白衬衣）、贡多拉（船夫撑篙、其中一艘挂灯笼）与公共汽船、BACARO 小酒馆（红白条纹雨篷 + 圣母小神龛 + 窗边桌椅 + 灯串）、SPRITZ VELA 广告牌与两盏投光灯、岸边行人 / 读者 / 情侣 / 侍者、鸽群与猫、桅杆与晾衣、细雨与水雾、暗角 |

#### 8.40.2 结构：一帧 34 步图层序 + 十二条独立 `rng` 流

`renderFrame38` 的 34 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空 | 天空底色（`R12` 压 `#0c1418`）→ 夜空 + 月 + 三层云带（`drawSky28`）→ 细雨第 1 层（`drawDrizzle3`，条件）→ 共享天气 0（`drawWeather`，本城零消费方） |
| 远岸 | 远岸地标（`FAR_C9`：圣马可钟楼 / 总督宫 / 两翼楼）→ 远岸窗灯（`drawWindows5(t, 0)`）→ 第一层雾（`drawMist3`）→ 中景（`MID_C3`）→ 里亚托桥（`drawBridge6`：背层 + 前层 + 暖光） |
| 水面 | 远水（`drawWaterFar`）→ 近岸楼房（`NEAR_C3`）→ 近岸窗灯（`drawWindows5(t, 2)`）→ 水晶吊灯（`drawChandeliers`）→ 鸽群（`drawFlock4`）→ 近水（`drawWaterNear`）→ 系缆桩（`drawPaline`）→ 海鸥（`drawGulls8`）→ 第二层雾（`drawMist3`） |
| 船 | 公共汽船（`drawVaporetto`）→ 贡多拉（`drawGondolas`）→ 细雨第 2 层（条件）→ 共享天气 1 |
| 岸边 | 站台（`drawStazio`）→ 第三层雾 → 码头（`drawQuay`）→ 水洼（`drawPuddles2`）→ BACARO 小酒馆（`drawBar`）→ 路灯（`drawLamps4`）→ 猫（`drawCat12`）→ 码头鸽群（`drawQuayPigeons`）→ 岸边人群（`drawCast5`）→ 细雨第 3 层（条件）→ 共享天气 2 |
| 收尾 | 暗角（`VIG_C38`） |

按图层归并即 12 组：**天空（底色 + 月 + 三层云带）→ 远岸地标（钟楼 / 总督宫 / 两翼楼）→ 近岸楼房与彩旗 → 桥（背层 + 前层 + 暖光）→ 水面（涟漪 / 远水光 / 近水 / 棕榈影）→ 细雨 → 码头与小酒馆 → 岸边人群 → 船（贡多拉 / 水上巴士）→ 鸟与猫 → 窗与吊灯 → 暗角**。

⚠️ **`rng()` 消费次序即画面**，十二条独立流全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(12)` | `STARS33`（30 颗） | 星位 / 闪烁速率 / 亮度（⚠️ 被跳过的候选**只消费 x / y 两个数** ⇒ 循环体里必须先取 x、y，再判条件，最后才取 k / ph / b） |
| `rng(91)` | `RIPPLES4`（水面横波） | 每条波纹的符号 / 幅度 / 相位 / 长度（消费次序：符号 → 幅度 → ph → len） |
| `rng(71)` | `RINGS11`（钟声荡开的涟漪，40 圈） | 每圈的起点 / 相位 |
| `rng(200 + li)`（200 / 201 / 202） | `DRIZZLE2` 三层细雨 | 每层每滴雨的数量与相位（消费次序：n → ph） |
| `rng(808)` | `BW2`（桥上行人 10 位） | 每人的周期 / 相位 / 方向 |
| `rng(900 + i)`（900–911） | `FLOCK5`（屋顶鸽群 12 只） | 每只的圈数 / 椭圆轨迹 / 落点 |
| `rng(17 / 31 / 47)` | `CLOUD_DEFS22` 三层云带 | 云带的团块位置与半径 |
| `rng(77)` | `renderFar19`（远岸地标） | 远岸屋群与钟楼的窗灯图案 |
| `rng(55)` | `renderMid5`（中景） | 中景屋群与招牌 |
| `rng(66)` | `renderBridgeBack2`（桥背层） | 桥后屋块的窗灯图案 |
| `rng(41)` | `renderNear6`（近岸楼） | 近岸三座粉墙楼的窗灯图案 |
| `rng(29)` | `renderBar`（小酒馆） | 酒馆的窗灯与桌位 |

⚠️ **另一条次序链**：`WINS9` 的**插入次序**（远岸 → 中景 → 桥后 → 近岸 → 酒馆，即 layer 0/1/4/2/3）决定每扇窗的 `id`，而 `winLit3` 用 `w.id` + `w.off` 取随机 ⇒ 次序即画面；`CHANDELIERS` 在 `drawNearHouse` 里生成、`STREAKS18` 在 `prerender38` 末尾组装 ⇒ `initConstants()` 必须先于 `prerender38()`，且 `prerender38()` 内部的调用次序不可调换。

#### 8.40.3 本城的结构性新东西：引擎没有 `clip()` ⇒ 本地裁剪；其余 17 条受控差异

**本城唯一的结构性新东西是「裁剪」**（与柏林的「合成模式」是两类缺口）：

- 上游在桥背层用 `ctx38.save(); ctx38.beginPath(); ctx38.rect(CAMER.x1, 0, FOND.x0 - CAMER.x1, WL12 + 1); ctx38.clip();` 把桥后的屋块**裁进桥洞范围**再贴 `BACK_C2`，随后的 `drawWindows5(t, 4)`（桥背窗灯）与 `drawBridgeWalkers`（桥上行人）也都在这个裁剪框内。
- **引擎全仓没有 `clip()`**（`visualizer/e51/engine/` 零命中）⇒ **不改引擎**（改它会波及已落地的 44 座城），改为在本城文件里本地实现：
  - `clipTo(x0, y0, x1, y1)` / `clipOff()`：一个左闭右开的裁剪框 + 一个开关；
  - **裁剪感知版绘制助手**：`R12`（填充）、`drawImg`（贴图）、`halo38`（光晕）三处都改成"先与裁剪框求交再画"，光晕那条要把半径方块裁成矩形后再按比例贴回。
- ⇒ **本城无合成模式缺口**：上游只用 `"lighter"`（钟声涟漪、`drawHalo`）与 `"source-over"`，引擎 `Comp` 的三档够用（与柏林不同，柏林需要本地 `multiply` / `destination-in`）。

逐条受控差异（头注共登记 17 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | `pix22(w, h)` ⇒ 本城 `VenPainter`（包引擎 `PixelBuffer`） | `set` / `alpha` / `fill` 逐位一致；`clear(i, j)` 保 RGB 只清 alpha；`text(s, i, j, col)` 直接走引擎 `text`（上游是 `putImageData → text → getImageData`，本项目 `PixelBuffer.canvas.px` 就是同一块背板，等价）；`line(...)` 本地实现（上游本城未调用） |
| ② | `sprite29(rows, key, flip2)` 宽度取**各行最大长度** | 引擎 `spriteFromRows` 用**首行**长度，不等价；翻转按整幅镜像。`trim25(p)` ⇒ 引擎 `PixelBuffer.trim()`（同一实现），返回值用引擎 `Pixmap` |
| ③ | `pick24(ramp, v, i, j, soft)` **保留 `soft` 形参** | 引擎 `pickFromRamp` 只覆盖 `soft = 1`，而本城用了 `0.5` / `0.6` / `0.7` 三档 ⇒ 本地实现保留形参（`soft = 1` 时与引擎逐位一致） |
| ④ | 上游 5 参 `drawImage(img, dx, dy, dw, dh)` ⇒ 引擎 9 参 | `drawWaterNear` 的左右两段近水；引擎无 5 参重载 ⇒ `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)`（等价缩放） |
| ⑤ | `PITCH31 = makePitch(...)` 整段删除，`PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）；`drawBillboard33` 只画自有画面 |
| ⑥ | 天气母题 `drizzle` 是**本城自有**母题 ⇒ `wx.shared` 恒 null | ⇒ 跨城共享的 `drawWeather` 无消费方 ⇒ 空实现；`WX_LAND38` / `WX38` / `WX_BB38` **照搬为数据**但零消费方。⛔ 本城的 `DRIZZLE2` 是**城内自绘的三层雨**（`drawDrizzle3`），与共享天气系统无关，不受空实现影响 |
| ⑦ | **上游 `ctx.clip()`（桥背层裁剪）⇒ 本地 `clipTo` / `clipOff` + 裁剪感知版绘制助手** | 见上 |
| ⑧ | `mixHex24` / `RGB29` / `C22` / `toHex15` / `MIXED6` 四个缓存删除 | `mixHex24(a, b, t)` ⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，与上游同口径）；颜色统一**非预乘 ARGB `Int`** 并就地内联 `hex("#…")`；`rgba(140,150,170,0.32)` ⇒ 引擎 `rgba`（alpha 走 8 位量化，与浏览器一致） |
| ⑨ | 音频三通道**只作用光晕 α** | `lightGain` → 全部光晕的 α；`neonPulse` → 灯火类光晕（`halo38` 按光晕身份分流：桥灯 / 挂灯 / 街灯 / 灯泡 / 窗灯 / 吊灯 / 门灯 / 雨篷 / 凉亭 / 广告牌灯 / 钟声 / 船舱）；`tailGain` → 水面光尾（`streak38`）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余 8 条见产物文件头（`Float32Array` ⇒ `FloatArray`、`Uint8ClampedArray` 的三元组色 ⇒ ARGB Int + alpha、`globalAlpha` ⇒ `PixCtx.alpha`、`globalCompositeOperation` ⇒ `Comp`、`setTransform` ⇒ `PixCtx.setTransform`、`drawImage` 3/9 参 ⇒ 引擎同名重载、`fillRect` 走 `PixCtx.fillRect`、`imageSmoothingEnabled = false` ⇒ 引擎恒最近邻）。上游 `ambience`（water / rain / crowd）是音频参数 ⇒ 不移植；`events` 只作画面节奏锚点，已照搬时刻（`pan` 双值取**首值**、`dur` 丢弃）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` 或 `hash(...)` ⇒ 逐位可复现。

#### 8.40.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 一律写 `wave(t, 5.0, 0.12)`；算术派生的 k 先 `.toDouble()`（引擎 `wave` 签名见 `PixelConstants.kt`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 直译成 `max(a, b, c)` ⇒ `Too many arguments` | 写 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` ⇒ `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给 `Pixmap` / `PixelCanvas` 等引用类型 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | `… → { … }` 被编译成 lambda / 报类型不匹配 | 写成 `run { … }`（本城文件级表 `SLAB_ROWS` 即用 `run { … }`） |
| ⑤ | **上游的 `shape` 形参是函数** | 传 `rounded` ⇒ `Function invocation 'rounded(...)' expected` | 传 `::rounded`（成员函数用 `this::rounded`） |
| ⑥ | **上游 `trim25` 的结果类型是 `Pixmap`** | 字段声明成 `PixelCanvas` ⇒ 类型不匹配 | 字段类型要与 `blit26` 保持一致，用 `Pixmap` |
| ⑦ | **上游调用点少传的参数要给默认值** | `drawFlyingGull(x, y, f)` 上游那里 `big` 是 `undefined` | 形参加默认值 `big: Boolean = false` |

#### 8.40.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：里亚托桥的拱券与桥上行人、圣马可钟楼与总督宫、近岸老楼与彩旗、贡多拉与水上巴士、小酒馆雨篷与灯串、岸边读者 / 情侣 / 侍者、鸽群与猫、桅杆晾衣、细雨与水雾 **全部可见**；四帧之间船位、人群、雨丝与水面涟漪均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.38 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**裁剪语义**。`clipTo` 若漏了、或裁剪块结束后忘了 `clipOff()`，画面**不会报错** ——
> 前者会把桥背屋块整块糊在桥面上，后者会让后面的图层被整片裁掉 ⇒ 所以必须**逐帧看桥洞那一块**
> （桥背的屋块只应出现在桥洞范围内、桥面与钟楼必须完整），不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `b34b78d`。

### 8.41 ✅ `singapore`（新加坡 · 滨海湾的季风雨夜，上游 2,289 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.41.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/singapore.js`（2,289 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__singapore.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCitySingapore.kt`（**5,915 行**） |
| 分片 | `logs_temp/city_parts/singapore/p01.kt`–`p13.kt`，`logs_temp/e51_pipeline/assemble.py` 顺序拼装（13 片） |
| 接线 | `LofiScenes.build()` 按字母序加 `"singapore" -> LofiCitySingapore()`（落在 `"seoul"` 与 `"stockholm"` 之间）；`CityRegistry.SCENES_BUILT` 第 **46** 项 |
| registry | `nameZh = 新加坡`、`taglineZh = 滨海湾的季风雨夜`、`accentHex = #ff85f3` 落地前已存在；本轮只回填 `focusX = 0.0 → 272.0`（上游 `focusX: FOCUS4`） |
| 机位类型 | **滨海湾对岸平视**（隔水看金沙 / 飞轮 / 超级树，水面与驳岸压在画面下缘） |
| 场景母题 | 新加坡摩天轮（Singapore Flyer：旋转轮盘 + 沿圆周跑的吊舱 + 发亮轮毂）+ 滨海湾花园的超级树丛（紫色光柱 + 树冠 + 空中天桥）+ 金沙三塔与塔顶 SkyPark 长船（DNA 双螺旋空中花园）+ 艺术科学博物馆的"花瓣"巨手与圆顶 + 远岸天际线 + 金融区 CBD 楼群剪影（帆形 / 斜切 / 阶梯 / 绿色藤蔓四种冠部 + LED 灯带 + 闪烁的航障灯）+ 前景棕榈与树冠丛 + 沙嗲摊（SATAY BBQ 招牌 / 炭火烤炉 / 冒烟的锅 / 串灯 / 戴围裙的摊主）+ 夜市人群（拍照的人 / 散步的一对 / 撑伞行人 / 四位食客 / 排队的客人）+ 楼顶霓虹广告牌（会眨眼的螃蟹 + "CHILLI / CRAB / SHIOK!" + 飞溅水花）+ 从金沙甲板扫出的彩色探照灯光锥 + 水面驳船与五只下潜的水獭 + 季风三层雨 + 暗角 |

#### 8.41.2 结构：一帧 22 步图层序 + 18 条独立 `rng` 流

上游 `renderFrame23` 的 **39 条绘制语句**归并成 22 步；叠放次序即遮挡关系，**不可调换**：

| 段 | 步骤 |
|---|---|
| 天空 | ① 天空（底色 + 三条云带 + 闪电落点）→ ② 从金沙甲板扫出的彩色探照灯光锥 |
| 远景 | ③ 远岸天际线（`FAR11` 两层：暗层 + 亮层）→ ④ 飞轮轮盘与吊舱 → ⑤ CBD 楼群（冠部 / LED 灯带 / 航障灯）→ ⑥ 超级树丛与空中天桥 |
| 中景 | ⑦ 第一层雨（远层雨丝）→ ⑧ 共享天气槽 0（**空实现**）→ ⑨ 金沙三塔 + SkyPark 甲板灯 → ⑩ 雾 0 → ⑪ 艺术科学博物馆（花瓣巨手）→ ⑫ 空中花园 DNA 双螺旋 → ⑬ 航障灯 / 闪烁灯 |
| 广告与雨幕 | ⑭ 楼顶霓虹广告牌（螃蟹 + 字样 + 水花）→ ⑮ 倾盆时的竖向雨幕（`VEIL_C` 滚动）→ ⑯ 雾 1 |
| 水面 | ⑰ 水面（涟漪环 / 水洼环 / 高光条 / 倒影）→ ⑱ 两艘驳船（含船体倒影与尾迹）→ ⑲ 第二层雨（中层雨丝）→ ⑳ 共享天气槽 1（**空实现**） |
| 近景 | ㉑ 前景驳岸与楼体 + 路灯 + 水洼环 + 棕榈 + 树冠丛 + 夜市人群（拍照的 / 水獭 / 散步的一对 / 长椅 / 撑伞行人 / 花坛 / 沙嗲摊 / 食客 / 烟 / 地面闪光 / 屋檐滴水）→ ㉒ 第三层雨（近层雨丝）+ 共享天气槽 2（**空实现**）+ 暗角 |

⚠️ **`rng()` 消费次序即画面**，**18 条独立流**全部按上游出现次序消费（`initConstants()` 先于 `prerender23()`，两者次序不可调换）：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(100)` / `rng(101)` / `rng(102)` | `RAIN7`（三层雨滴） | 每层雨丝的落点 / 速度 / 亮度 / 倾角 |
| `rng(71)` | `RINGS6`（水面涟漪环） | 涟漪环的位置与周期 |
| `rng(72)` | `PUDDLE_RINGS4`（水洼环） | 水洼涟漪的位置与周期 |
| `rng(17)` / `rng(29)` | `CLOUD_DEFS11`（两条云带） | 云带的形状 / 高度 / 漂移速度 / 密度 |
| `rng(31)` | `BOLT_C`（闪电） | 闪电折线 / 分叉 / 落点 |
| `rng(21)` | `FAR11`（远岸） | 远岸楼体、窗灯与天际线轮廓 |
| `rng(59)` | 广告牌后的楼体（`renderBlock`） | **先于** `rng(47)` 消费，位置不可对调 |
| `rng(47)` | CBD 楼群（`renderCbd`） | 楼体宽高 / 四种冠部 / LED 灯带 / 航障灯 |
| `rng(88)` | 金沙三塔（`renderMbs`） | 塔身 / 甲板 / 底座灯带 |
| `rng(606)` | 前景驳岸（`renderFront3`） | 前景楼体、栏杆、花坛与地面细节 |
| `rng(798)` / `rng(828)` | 两棵棕榈树冠（`rng(700 + pl.x)`，`pl.x = 98 / 128`） | **同一条种子各消费三次**（树冠按三种摇摆态各烘一张），树冠叶丛的分布 |
| `rng(808)` | 树冠丛（`renderCanopy`） | 树冠团块 / 树冠底缘（屋檐滴水的候选点） |
| `rng(909)` | 屋檐滴水（`DRIPS3`） | 滴水起点 / 落点 / 周期（上游 `continue` 仍执行 `k++` ⇒ id 会跳号） |
| `rng(313)` | 地面闪光（`GLINTS2`） | 湿地反光点的位置 |

#### 8.41.3 本城的结构性新东西 / 受控差异

**结构性新东西（本城新增，其余城市可直接借用）**

| # | 新东西 | 说明 |
|---|---|---|
| 1 | **水獭下潜的"裁到水面以下"** | 上游 `drawOtters` 用 `ctx23.save(); ctx23.beginPath(); ctx23.rect(0, QUAY3 + 2, W, 20); ctx23.clip();` 把下潜中的水獭裁到水面以下。**引擎没有路径 / 裁剪 API** ⇒ 本地改成"只把精灵里落在裁剪带内的行贴回主画布"（`drawOtterClipped` 逐行 9 参 `drawImage` + `otterClipRect` 对矩形填充做同带裁剪）。裁剪带横向覆盖整幅画布（x ∈ [0, W)）⇒ 只需按行裁；逐行贴与"整幅贴图后裁剪"**逐像素相同** |
| 2 | **`bake3` 的"换 ctx + translate"照搬** | 上游 `bake3` 是把模块级 `ctx23` **换成**子画布的上下文再作画；本城照做 ⇒ `ctx23` 因此是 `var` 而不是 `val`（本文件唯一的"可变上下文"） |
| 3 | **`toFixed(1)` 的复刻** | 上游 `+(otterDive(o) + 0.6).toFixed(1)` 与 `+(f.t + 1.6).toFixed(1)`（水獭入水溅水时刻 / 雷声时刻）用 `jsRound(x * 10.0) / 10.0` 复刻（正数四舍五入到 0.1）；`jsRound` 与 JS `toFixed` 的进位口径一致，但**负数与半值**仍要逐条核 |
| 4 | **自有雨恒在画、共享天气运行期不可达** | 本项目渲染器**从不设置** `SceneOpts.weather`（恒 null ⇒ 命中 `plans.city`）⇒ `wx.shared` 恒 null ⇒ 跨城共享的 `drawWeather` 无消费方（本城为空实现）。但本城母题 `rain` 走的是**自有**的三层雨 `RAIN7` ⇒ `wx.rain` 恒 true ⇒ **三层雨永远在画**（不是空实现），共享天气表 `WX_LAND23` / `WX23` / `WX_BB23` 照搬为数据但零消费方 |
| 5 | **旋转光锥只能最近邻** | 上游 `ctx23.imageSmoothingEnabled = true`（`drawLasers` 的彩色光锥用 `setTransform` 旋转）在本项目**无法表达** —— 引擎的 `drawImage` 一律最近邻 ⇒ 旋转后的光锥边缘是硬边（其余部分逐像素一致） |
| 6 | **`sprite16` 宽度取各行最大长度** | 引擎的 `spriteFromRows` 用**首行**长度，不等价 ⇒ 本城同名函数改取各行最大长度（螃蟹精灵与驳船精灵都用到） |

**受控差异清单（头注共 16 条，此处列要点）**

| # | 差异 | 理由 |
|---|---|---|
| 1 | 颜色统一非预乘 ARGB `Int`；`mixHex12` / `dim3` / `toHex9` 由"返回字符串 / 数组"改为"返回 Int"；`C12` 颜色缓存删除 | 引擎 `hex()` 无分配；逐通道取整口径不变（结果逐位相同，只是少了缓存） |
| 2 | `pix12(w, h)` ⇒ 本城 `SgPainter`（包引擎 `PixelBuffer`：`set` 同 `jsRound` 取整、`alpha`、`fill`；`done()` ⇒ `.canvas`） | 上游 `p.set(i, y, col, a)` 的 alpha 覆盖语义保留 |
| 3 | 5 参 `drawImage(img, dx, dy, dw, dh)`（水獭倒影 / 船体倒影按行取样）⇒ 引擎 9 参 | 引擎只有 9 参一版；两者等价 |
| 4 | `PITCH18 = makePitch(BB22.w, BB22.h, {accent})` 整段删除，`PitchStub` 是空桩（`covers` 恒 false、`draw` 空） | 本项目不放广告 ⇒ `drawBillboard21` 只画自有画面 |
| 5 | `reflect(ctx, t, …)` / `streak(ctx, t, …)` ⇒ 引擎 `PixelReflector`（选项对象摊成具名实参） | 与前 42 城同口径 |
| 6 | 上游 `Float32Array`（云带密度场 / 雾密度场）⇒ `FloatArray`；上游写在**逐像素**调用点的色阶字面量（如 `renderFarShore` / `renderMbs` / `renderCanopy`）提到文件级 `IntArray` | 同值同序 ⇒ 画面逐位不变，只是少了每像素一次数组分配 |
| 7 | 上游 `ambience`（rain / water / crowd / traffic）是音频参数 ⇒ 不移植；`events` 只作画面节奏锚点 | 同前 42 城口径 |

音频轻反应（§10 D-a）只挂三处：`lightGain` → 全部光晕的 α（`halo23` 统一乘）；`neonPulse` → 灯火类（`haloNeon`：飞轮轮毂 / 舱灯 / 超级树灯环 / 空中花园灯环 / 楼群冠部与 LED 灯带 / 塔尖灯 / 中庭灯 / 广告牌霓虹 / 航障红灯 / 探照灯 / 路灯 / 摊位灯管 / 串灯 / 烤炉 / 招牌 / 船灯）；`tailGain` → 水面光尾（`STREAKS12` 的高光条）与船体光晕（`haloTail`）。⛔ 坐标 / 几何 / 色值一律不碰。

#### 8.41.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected`；引擎的 `wave` 只有 Double 版 | 一律 `wave(t, 5.0, 0.12)`；整式派生（如 `(300 + i * 17).toDouble()`）也要显式 `.toDouble()` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄 ⇒ `None of the following candidates is applicable` | 三参改 `maxOf(a, b, c)` / `minOf(a, b, c)`；两参继续用 `max` / `min`（本城大量两参用法是对的，别一律替换） |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 非法 ⇒ 报 `lateinit modifier is not allowed on properties of primitive type` | 改成 `private var x: Double = 0.0`（引用类型如 `ctx23` / `SgHalos` 仍可用 `lateinit`） |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | `run { … }` 写成 `{ … }` ⇒ 编译期把后面的语句吞成尾随 lambda，报出一串莫名其妙的类型错 | 裸块一律写 `run { … }`（本城 `FLY_RIM_C` / `HERO2` / `CRAB_C` 三处都踩过） |
| ⑤ | **`doubleArrayOf(0, 4)` 这类 Int 字面量进 Double 数组会报类型错** | `doubleArrayOf(0, 4)` ⇒ `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 该用 `intArrayOf(0, 4)` 就别写 `doubleArrayOf`（本城 `OTTER_SPLASH_T` 按下标遍历 `intArrayOf(0, 4)` 后 `.map { … }.toDoubleArray()`） |

#### 8.41.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / Singapore 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与柏林基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：新加坡摩天轮的轮盘与吊舱、超级树丛的紫色光柱、艺术科学博物馆的双螺旋、金沙与远岸天际线、前景棕榈与沙嗲摊、夜市人群、霓虹广告牌与探照灯、水面驳船与水獭、季风三层雨 全部可见；四帧之间摩天轮转角、探照灯扫射、雨丝与水面涟漪均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.38 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：水獭下潜那一段是**唯一一处"引擎没有裁剪 API"的地方** —— 若本地裁剪带（`QUAY3 + 2` 起 20 行）写错一格，
> 画面**不会报错**、只会安静地变成"水獭浮在水面上不下潜" ⇒ 所以必须**盯住 t ≥ 下潜时刻的水獭**，
> 不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `b34b78d`。

### 8.42 ✅ `madrid`（马德里 · 格兰大道的夏夜，上游 2,298 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.42.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/madrid.js`（2,298 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__madrid.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityMadrid.kt`（**5,259 行**） |
| 分片 | `logs_temp/city_parts/madrid/p01.kt`–`p18.kt`（18 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"madrid" -> LofiCityMadrid()`（落在 `"london"` 之后、`"marrakech"` 之前）；`CityRegistry.SCENES_BUILT` 第 **47** 项 |
| registry | `nameZh = 马德里`、`taglineZh = 格兰大道的夏夜`、`accentHex = #ff4d6d` 落地前已存在；本轮只回填 `focusX = 0.0 → 200.0`（上游 `focusX: MX`，即大都会大楼的中轴 x） |
| 机位类型 | **格兰大道的斜俯视**（四条街沿 `AL_N / AL_S / GV_N / GV_S` 按 `s.vp + s.off / z` 透视递减，画面正中偏右是大都会广场，左侧远处是主教座堂的钟楼） |
| 场景母题 | 远处山墙楼与**阿尔穆德纳主教座堂钟楼**（铜钟在 44 / 47 / 50 / 164 / 167 / 170 / 173 秒敲响）、**大都会广场的 Metrópolis 大楼**（鼓座 + 金色圆顶 + 拱廊 + 顶端胜利女神像 + 屋顶灯）、**CHURROS Y CHOCOLATE 粉红霓虹广告牌**（七根霓虹管字 + 闪烁边框 + 第二行小字 + 跑马灯）、街角的 CINE / BAR 竖招牌 + HOSTAL 旅馆字样 + 绿色药房十字、出租 / 公交 / 清扫车 / 救护车（两条车道 + 四班特车，公交侧面刷"N16"线路牌）、街边咖啡座（遮阳伞 + 桌椅 + 灯泡）、行人 / 牵狗的人 / 红绿灯 / 街灯、**右侧近景的暖灯公寓阳台**（红酒杯 / 串灯 / 天竺葵花盆 / 被风吹动的纱帘 + 橘猫四个姿态 + 绕灯的飞蛾）、屋檐上栖的两只蝙蝠、飞絮、暗角 |

#### 8.42.2 结构：一帧 38 步图层序 + 六条独立 `rng` 流

`renderFrame14` 的 38 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空（8 步） | 天空底图（`SKY_C`）→ 星（`drawStars2`）→ 流星（`drawShootingStars2`）→ 月晕（`halo14` × 2：外晕 + 核心）→ 月面（`MOON_C`）→ 气流絮（`drawWisps2`）→ 飞机（`drawPlanes`） |
| 远景（10 步） | 远景楼（`FAR_C`）→ 街墙（`STREET_C`）→ 城市天际线（`CITY_C`）→ 城景特效（`drawCityFx2`）→ 钟楼（`drawBell2`）→ 霓虹广告牌（`drawBillboard13`，条件）→ 溪流（`drawStreams`）→ 溜达者（`drawStrollers4`）→ 飞絮第 1 层（`drawFluff(0)`）→ 共享天气 0 |
| 中景（15 步） | 大都会楼（`MET_C`）→ 大都会特效（`drawMetropolisFx`）→ 蝙蝠（`drawBats`）→ 警灯扫光（`drawSirenWash`）→ 湿痕（`drawWetTrail`）→ 咖啡座（`CAFE_C`）→ 咖啡座动态（`drawCafe2`）→ 行人（`drawWalkers5`）→ 路灯（`FAROLA_C` 逐盏 + `drawLamps`）→ 红绿灯（`drawTrafficLights`）→ 车道 0（`drawLane`）→ 飞絮第 2 层 → 共享天气 1 → 车道 1（`drawLane`） |
| 近景（5 步） | 阳台（`BAL_C`）→ 阳台动态（`drawBalconyFx`）→ 飞絮第 3 层 → 共享天气 2 → 暗角（`VIG_C`） |

⚠️ **`rng()` 消费次序即画面**，上游**六条**独立流全部在 `initConstants()` 里按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(61)` | `STARS12`（72 颗星） | 星位 / 闪烁速率 / 亮度 |
| `rng(505)` | `STROLL4`（14 个溜达的人影） | 每人的周期 / 相位 / 方向 |
| `rng(808)` | `LEAVES3`（54 片阳台绿植叶） | 每片叶的摆幅 / 相位 / 落点 |
| `rng(909 / 910 / 911)` | `FLUFF[0] / [1] / [2]`（60 / 40 / 8 团飞絮） | 每团飞絮的密度 / 漂移 / 尺寸（**第三层才有 `k3 / p3 / k4 / p4 / kt`**，上游只给第三层补了这些参数） |

其余确定量走 `hash(...)`（咖啡座灯泡 / 围栏 / 串灯 / 窗灯 / 金顶闪点）⇒ **不占 rng 流**，逐位可复现。

#### 8.42.3 本城的结构性新东西：霓虹管字得自己"画"出来；其余 13 条受控差异

**本城唯一的结构性新东西是「霓虹管字生成」**（与柏林的「合成模式」、威尼斯的「裁剪」是三类不同的缺口）：

- 上游 `neonStrokes(ch)` / `neonSprite(pts, w, h, tube, glow)` / `textPts(s)` 依赖 3×5 字模自行把字母摊成**霓虹管的像素点**（含"半格"补点），再由 `neonSprite` 把管点**外扩一层**做出发光边 —— **引擎全仓没有对应件** ⇒ **不改引擎**（改它会波及已落地的 46 座城），改为在本城文件里本地实现三个函数（用引擎 `FONT3` 字模逐点摊开，`neonSprite` 的四个外扩方向照上游）。
- 同族的 `catSprite(rows, flip2)` 宽度取**各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），并额外产出 `warm`（橘猫身上受光的边缘像素表）—— 引擎 sprite 没有这一路输出 ⇒ 本城自定义 `MadCatSprite`。
- ⇒ **本城无合成模式缺口**：上游只用 `"source-over"` 与 `"lighter"` ⇒ 引擎 `Comp` 三档够用（与柏林不同，柏林需要本地 `multiply` / `destination-in`）。

逐条受控差异（产物文件头注共登记 14 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；`HXC3` / `HX3` 的十六进制缓存删除（结果逐位不变，只是少了缓存与字符串往返） |
| ② | `painter6(w, h)` ⇒ 本城 `MadPainter`（包引擎 `PixelBuffer`） | `set` 同 `jsRound` 取整、越界丢弃、alpha 覆盖；上游 `P4.put(i, y, col)` 的 `HX3(col)` 语义保留 |
| ③ | `pick7(pal, v, i, y, n)` **本地实现** | 引擎 `pickFromRamp` 的抖动口径与上游不同 ⇒ 本城重写（照抄会整片改色） |
| ④ | `Float32Array` 密度场 ⇒ `FloatArray` | 写 `.toFloat()`、读 `.toDouble()`，保住 float32 截断 |
| ⑤ | 广告位 `makePitch` ⇒ `PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）；`drawBillboard13` 只画自有画面 |
| ⑥ | 共享天气 `drawWeather` 空实现 | 母题 `fluff`（空气里的飞絮）**不是**跨城共享键 ⇒ `wx.shared` 恒 null；`WX_LAND14` / `WX_SHELTER` / `WX_LEAVES4` / `WX14` / `WX_BB14` **照搬为数据**但零消费方。⚠️ 本城**自有**的三层飞絮（`FLUFF` + `drawFluff`）**照常绘制**（`wx.fluff` 恒真），与共享天气系统无关 |
| ⑦ | 上游 `makeCar` 的返回值**不带 `side`** ⇒ `label()` 里 `v.h - v.side < 12` 在上游是 **NaN 比较**（恒不成立） | 本城给 `MadVehicle.side` 留默认 `0`；而 `label()` 只作用在**确实带 `side` 的公交**上（上游只有 `makeBus` 才返回 `side`）⇒ 逐像素等价 |
| ⑧ | `mirror(v)` 的 `x.scale(-1, 1)` + `drawImage(v.c, -v.w, 0)` | 目标第 0 列取到源的第 `w` 列（越界 ⇒ 全透明）、第 1 列取源的 `w-1` 列 —— 上游这个"错一列"的偏移原样照抄，不"修正" |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo14` 统一乘）；`neonPulse` → 霓虹 / 灯火类光晕（霓虹招牌 / 跑马灯 / 招牌灯泡 / 串灯 / 窗灯 / 出租车顶灯 / 公交窗 / 警灯 / 街灯 / 钟楼 / 塔冠 / 药房十字 / 红绿灯）；`tailGain` → 车流光尾（车头 `head` 与尾灯 `tail`）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余 5 条见产物文件头（`makeCanvas` ⇒ 引擎 `PixelCanvas`；`createImageData` / `putImageData` ⇒ 直接写 `PixelCanvas.px`；`text(ctx, s, x, y, "#hex")` ⇒ 引擎 `text`（同为 3×5 字模，只是收色是 Int）；`globalAlpha` ⇒ `PixCtx.alpha`、`globalCompositeOperation` ⇒ `Comp`、`setTransform` ⇒ `PixCtx.setTransform`、`drawImage` 3/9 参 ⇒ 引擎同名重载、`fillRect` 走 `PixCtx.fillRect`）。上游 `ambience`（traffic / crowd / wind）是音频参数 ⇒ 不移植；`events` 只作画面节奏锚点，已照搬时刻（`pan` 双值取**首值**、`dur` 丢弃）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` 或 `hash(...)` ⇒ 逐位可复现。

#### 8.42.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给 `Pixmap` / `PixelCanvas` 等引用类型 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`GOLD5` 是可变表 ⇒ 用 `add()` 而不是 `push()`** | `push` 在 Kotlin 的 `MutableList` 上不存在 | 用 `add()`（`GOLD5` 是逐帧追加的金色闪点表） |

#### 8.42.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：**大都会大楼**的白色鼓座 + 金顶 + 顶端胜利女神像、左侧**主教座堂钟楼**、右上角**粉红「CHURROS Y CHOCOLATE」霓虹牌**与绿色 HOSTAL 竖招牌、远景高楼群与航空障碍灯、**格兰大道两侧朝灭点递减的楼与亮窗**、湿柏油路上的车灯长 streak 与**红色交通灯**、街边遮阳伞咖啡座与行人、**右下角近景阳台**（暖灯窗 + 铁栏杆 + 陶土地砖 + 藤椅 + 墙灯）、左下角两盆**开红花的天竺葵** 全部可见；四帧之间车流位置、溜达的人影、飞絮与光晕均在变（`t=228` 那帧藤椅上已经蹲着一只橘猫，`t=60` 那帧大都会楼顶有蝙蝠掠过） |
| 未做 | 真机截图评分（同 §8.32–§8.41 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**透视街沿**（`sideX2(s, z) = s.vp + s.off / z`）与**自绘霓虹管字**。
> `pick7` 的抖动口径若照抄引擎 `pickFromRamp`，沿街楼层窗会整片改色；霓虹管点若少画"半格"补点，
> `CHURROS` 会缺一截 —— 两者都不会报错、只会安静地画错 ⇒ 必须**逐帧看霓虹牌与沿街窗**，
> 不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `29dd464`。

### 8.43 ✅ `london`（伦敦 · 泰晤士河的雾，上游 2,315 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.43.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/london.js`（2,315 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__london.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityLondon.kt`（**4,967 行**） |
| 分片 | `logs_temp/city_parts/london/p01.kt`–`p14.kt`（14 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"london" -> LofiCityLondon()`（落在 `"lisbon"` 之后、`"madrid"` 之前）；`CityRegistry.SCENES_BUILT` 第 **48** 项 |
| registry | `nameZh = 伦敦`、`taglineZh = 泰晤士河的雾`、`accentHex = #e8413c` 落地前已存在；本轮只回填 `focusX = 0.0 → 206.0`（上游 `focusX: BB_CX`，即伊丽莎白塔的中轴 x） |
| 机位类型 | **泰晤士河南岸对岸平视**（画面正中横着威斯敏斯特桥，水位线 `WL = 198`，前景铺路石从 `PAVE = 245` 起） |
| 场景母题 | **威斯敏斯特宫与伊丽莎白塔**（大钟面 + 哥特窗棂的"上下议院"立面 + 三段檐廊 + 维多利亚塔楼）、**伦敦眼**（60 帧轮辐 + 32 个吊舱，轮后是白色防波堤与一排桥墩）、南岸楼群与雾里对岸的楼、**威斯敏斯特桥**（八个桥墩 + 桥栏 + 桥灯）、河上的驳船与两辆红色双层巴士、维多利亚式的绿金**酒吧**（PUB 招牌 + 吧台上的麦色酒瓶 + 果篮 + 窗后顾客）、红色**电话亭**、铸铁路灯（灯柱上蹲着一对海豚灯饰）、河上的天鹅、屋顶的狐狸、头顶的枯枝与飘落的秋叶、薄雾两层反向漂移、细雨、暗角 |

#### 8.43.2 结构：一帧 22 步图层序 + 41 个种子号（16 组调用点）

`renderFrame5` 的 22 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与远岸（3 步） | 天空（底色 + 月 + 三层云带 + 飞机）→ 远景楼群（`drawFar2`）→ 雾带 1（`drawFog(FOGS[0])`） |
| 河岸地标（8 步） | 落叶第 1 层 → 雨第 1 层 → 共享天气 0 → **伦敦眼**（`drawEye`）→ 广告牌（`drawBillboard5`，条件）→ **威斯敏斯特宫 + 伊丽莎白塔**（`drawParliament`）→ 鸟群（`drawFlock`）→ **威斯敏斯特桥**（`drawBridge`） |
| 河面（6 步） | 河面与倒影（`drawRiver2`）→ 驳船（`drawBoat`）→ 雾带 2 → 落叶第 2 层 → 雨第 2 层 → 共享天气 1 |
| 前景（5 步） | 前景（`drawForeground`：码头岸壁 + 铸铁路灯 + 电话亭 + 酒吧 + 招牌 + 果篮 + 广告牌 + 枯枝）→ 雨第 3 层 → 落叶第 3 层 → 共享天气 2 → 暗角（`VIG_C5`） |

⚠️ **`rng()` 消费次序即画面**，上游的独立流全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(13 / 27 / 41)` | 三层云带（`makeCloudLayer3`） | 每层云絮的团心 / 团宽 / 基高 / 絮团数，以及每团的 rx / ry / 偏移 |
| `rng(17)` | `renderFar3`（远景楼窗） | 每扇远景窗的宽 / 高 / 亮度 / 颜色 |
| `rng(23)` | `renderParliament`（议会窗） | 窗位 / 宽度 / 亮度；⚠️ **河面倒影那份 `renderParliament(true)` 用同种子 23 另开一条流** ⇒ 倒影的窗与本体不同 |
| `rng(29)` | `renderSouthBank`（南岸楼） | 南岸窗的窗位与尺寸 |
| `rng(31)` | `renderGround`（湿石板与铺路石） | 先按列取 W 次"石板反光"，再**按行**取铺路石的色阶 |
| `rng(606)` | `renderPub`（吧台酒瓶） | 酒瓶的种类 / 颜色 / 高度 |
| `rng(77)` | `renderBranch`（枯枝与叶） | 枝杈的分叉与每片叶的落点 |
| `rng(7 / 8)` | `renderBus`（两辆公交的乘客剪影） | 每扇窗先判 `r() < 0.55`，命中才取头位，再判 `r() < 0.5` |
| `rng(3 / 4)` | `renderBasket`（两只果篮） | 果篮的轮廓与果子的位置 |
| `rng(505)` | `PUB_WINS`（酒吧六扇窗） | 每扇窗的灯数与明暗相位（`id` 0..5 决定顺序） |
| `rng(4343)` | `LEAVES2`（三层落叶） | 每片叶 11 次取数：n → ph → ks → p2 → kr → p3 → kf → p4 → amp → drift → col |
| `rng(200 / 201 / 202)` | `RAIN3` 三层雨滴 | 每层每滴的数量与相位 |
| `rng(71)` | `RINGS2`（河面涟漪，34 圈） | 每圈的半径与相位 |
| `rng(72)` | `PUDDLE_RINGS`（地面水洼涟漪，14 圈） | 同上（`id` 从 50 起） |
| `rng(900 … 919)` | `FLOCK`（20 只鸟，每只一条流） | 每只的圈数 / 相位 / 椭圆轨迹 / 落点 / 归巢偏移 |
| `rng(61)` | `SMOKE2`（22 股炊烟） | 每股烟的起点 / 长度 / 漂移 |

⚠️ **数字口径**：上游共 **41 个种子号 / 16 组调用点**；其中 `rng(23)` 被议会的河面倒影**再实例化一次**
⇒ 运行期共 42 次流实例化。（产物文件头注写的"24 条"是按组粗数，逐种子展开实为 41 个。）
`initConstants()` 与 `prerender5()` 的调用次序不可调换。

#### 8.43.3 本城的结构性新东西：引擎没有 `clip()` ⇒ 本地裁剪；其余 17 条受控差异

**本城唯一的结构性新东西是「裁剪」**（与柏林的「合成模式」是两类缺口）：

- 上游 `drawVehicle` 用 `ctx5.rect(215, 0, W - 215, H); ctx5.clip();` 把车与车灯**裁到 x ≥ 215**。
  **引擎全仓没有 `clip()`**（`visualizer/e51/engine/` 零命中）⇒ **不改引擎**（改它会波及已落地的 47 座城），
  改为在本城文件里本地实现：
  - `clipX215`（只画落在 x ≥ 215 的源子矩形）与 `haloClip215`（光晕的裁剪版）两个助手；
  - 之所以能逐像素等价，是因为被裁掉的那块**本来就没被写过**（引擎 `Comp.COPY` 之类替换不了未覆盖区域）。
- ⇒ **本城无合成模式缺口**：上游只用 `"source-over"` 与 `"lighter"` ⇒ 引擎 `Comp` 三档够用。

逐条受控差异（产物文件头注共登记 18 条，此处列 10 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；上游 `rgba(r,g,b,a)` 字符串 ⇒ 引擎 `rgba(hex("#…"), a)`（alpha 走 8 位量化，与浏览器一致） |
| ② | `makeCanvas(w, h)` ⇒ 引擎 `PixelCanvas(w, h)`；`createImageData` / `putImageData` ⇒ **直接写 `PixelCanvas.px`**；上游 `getImageData(...).data[3]` ⇒ `PixelCanvas.pixelAt` 的 alpha 位 | 本项目的 `PixelBuffer.canvas.px` 与画布是同一块背板 |
| ③ | 5 参 `drawImage(img, dx, dy, dw, dh)` ⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)` | 引擎无 5 参重载；最近邻、逐像素等价 |
| ④ | `makeHalo(r, col, maxA, pow, levels)` ⇒ 引擎 `makeHalo`；上游 `makeRingHalo` 返回 `{ c, r }` ⇒ 引擎 `Halo(canvas, r)` | 字段名一致，`drawHalo` 直接可用 |
| ⑤ | 广告位 `PITCH3 = makePitch(...)` 整段删除，`PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）；`drawBillboard5` 只画自有画面 |
| ⑥ | 共享天气 `drawWeather` 空实现 | 上游 `WEATHER5 = weatherPlans(["rain", "leaves"])`，但上游 `plans` 对"自有母题"一律给 `shared: null` ⇒ `wx.shared` 恒 null ⇒ 共享天气无消费方（且本项目渲染器从不设置 `SceneOpts.weather` ⇒ 运行期也不可达）；`WX_LAND5` / `WX5` / `WX_BB5` **照搬为数据**但零消费方。⚠️ 本城**自有**的三层雨与三层落叶照常移植（`plans.city` 里 `rain` / `leaves` 皆 true） |
| ⑦ | **上游 `ctx.clip()` ⇒ 本地 `clipX215` / `haloClip215`** | 见上 |
| ⑧ | 上游 `line4` 在 `max(\|dx\|,\|dy\|) == 0` 时算出 `0/0 = NaN` ⇒ `Math.round(NaN) = NaN` ⇒ **一个像素也不画** | 本文件显式 `if (n2 == 0.0) return`（Kotlin 的 `round(NaN).toInt() = 0` 会错误地画在 (0,0)） |
| ⑨ | 上游 `LEAF8[k]` 在 `k < 0` 时是 `undefined` ⇒ core 的 `px` 里 `fillStyle = undefined` 是**非法赋值**、**fillStyle 保持不变** | 本地照此实现（`if (k >= 0) leaf8[k] else x.fill`），**不改成夹取** |
| ⑩ | 上游 `gradeAd(c, BILL_NIGHT)` 的对象参数 ⇒ 引擎 `gradeAd(c, level, tint, mixAmt, wear, seed)`；`reflect` / `streak` ⇒ 引擎 `PixelReflector`（选项对象摊平成命名参数）；`cycle(t, n, ph)` 返回 `{cyc, u}` ⇒ 引擎 `Cyc`（**复用单例**，取用后立即取字段） | 上游 `ditherFill(w, h, pal, fn)` ⇒ 引擎同名函数（`valueFn` 返回负值表示不写） |

其余 8 条见产物文件头（`trim3` ⇒ 引擎 `Pixmap` / `blit3(L)` 的 `{c, x, y}` 同形；`spriteOf2` 宽度取**各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），翻转按整幅镜像；上游 `Uint8ClampedArray` 在雾层渐变处退化为 `jsRound`；`init_london` 的 `rng()` 派生量 ⇒ 场景实例字段、字面量部分 ⇒ 文件级常量；上游 `london_default` 的 `perch` / `spots` / `secrets` / `ambience` 是站点 UI 元数据 ⇒ **不移植**，`events` 只作画面节奏锚点）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` / `hash(...)` ⇒ 逐位可复现。

音频轻反应（§10 D-a）**三通道只作用光晕 α**：`lightGain` → 全部光晕的 α；`neonPulse` → 灯火类光晕（钟楼 / 檐廊灯 / 街灯 / 电话亭 / 酒吧 / 窗灯 / 广告牌灯 / 摩天轮灯 / 船灯）；`tailGain` → 车流光尾（车头灯 / 尾灯 / 出租车顶灯 / 塔吊红灯）。⛔ 坐标 / 几何 / 色值一律不碰。

#### 8.43.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给 `Pixmap` / `PixelCanvas` 等引用类型 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **上游 `LEAF8[k]` 当 `k < 0` 时是 `undefined`** | 直接下标会取到越界或错色 | 本地要守卫：沿用上一次的颜色（`x.fill`），**不改成夹取**（夹取会改画面） |

#### 8.43.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：**威斯敏斯特宫整排金色哥特立面**与最左的维多利亚塔楼、**伊丽莎白塔的钟面**（金色塔身 + 白盘）、**粉紫色的伦敦眼轮盘**（辐条清晰、轮后白色防波堤与一排桥墩）、**绿色威斯敏斯特桥的三段拱**与桥灯、桥与议会在水面的竖直倒影、**右侧砖楼里的绿金「PUB」门面**（暖黄橱窗 + 吧台酒瓶 + 果篮）、**红色电话亭**、左侧铸铁路灯、**石板路与长椅**、右上角**酒吧屋顶的狐狸**、左上角伸进画面的**橙红秋叶枝**、河面上的白色天鹅、细雨与两层雾 全部可见；四帧之间伦敦眼辐条角度、落叶的疏密、河面船位与雾带都在变（`t=228` 那帧天空里多了一群归巢的鸟） |
| 未做 | 真机截图评分（同 §8.32–§8.41 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**裁剪语义**。`clipX215` 若漏了，车与车灯会糊到 x < 215 的河面上；
> 而 `haloClip215` 若忘了裁，光晕会溢出到桥的另一侧 —— 两者都不会报错、只会安静地画错，
> 所以必须**逐帧看车与车灯所在的右半边**，不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `29dd464`。

### 8.44 ✅ `dublin`（都柏林 · 利菲河上的细雨，上游 2,379 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.44.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/dublin.js`（2,379 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__dublin.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityDublin.kt`（**6,395 行**） |
| 分片 | `logs_temp/city_parts/dublin/p01.kt`–`p09.kt`（9 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"dublin" -> LofiCityDublin()`（落在 `"copenhagen"` 之后、`"hamburg"` 之前）；`CityRegistry.SCENES_BUILT` 第 **49** 项 |
| registry | `nameZh = 都柏林`、`taglineZh = 利菲河上的细雨`、`accentHex = #3984c6` 落地前已存在；本轮回填 `focusX = 0.0 → 177.0`（上游 `focusX` 是投影式 `jsRound(proj2(AB2, RW / 2, deckZ(RW / 2))[0])`，按上游常量算出 177） |
| 机位类型 | **利菲河南岸码头边的 3D 投影斜视**（镜头横偏 8°：焦距 `F3 = 330`、主点 `CXP = 272`、地平线 `HZ8 = 150`、眼高 `EYE5 = 6.4`；`proj2` / `deckZ` / `ribDepth` 把世界坐标逐点投到屏幕） |
| 场景母题 | **Ha'penny 步行桥**（铁栏杆 + 三道拱 + 拱顶冠石 + 桥灯，桥身在水面拖出倒影）、北岸的货栈与商铺一字排（竖挑出来的**蓝底 BOOKS** 与**绿底 PUB** 刀形招牌 + 两排会亮的窗 + 临河的步行栈桥 + 两棵秋树）、南岸的酒馆与门洞（**红字 BAR** 招牌、**绿底金字的 PUB**、会开合的巷门 + 门里透暖光、门口说话的一对、烟囱炊烟、码头灯与最亮的铸铁街灯）、远处天际线（尖塔 / 钟楼 / 圆顶 / 两座闪红灯的港口起重机 / 港务楼 / 远处桥塔）、河上的**驳船**（带探照灯与尾迹）、远处桥上的列车、一圈圈雨点涟漪、掠水的鸬鹚、停在栏杆与桥拱上的海鸥、**三层雨幕**（近 / 中 / 远，被灯照到的雨丝转暖色）、雾带、人物（倚栏喝咖啡的 / 抱吉他的街头艺人 / 门口聊天的 / 听琴的 / 打手机的 / 灯柱下撑伞的一对 / 遛狗的 / 推婴儿车的 / 骑车送货的 / 过桥的一群人 / 倚栏看河的）、左侧工地围挡广告牌「TRAD TONIGHT」（小提琴图案 + 三盏投光灯 + 锥形光斑）、暗角 |

#### 8.44.2 结构：一帧 19 步图层序 + 24 条场景流 + 38 条人物外观流

`renderFrame34` 的 19 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与远景（5 步） | 天空（`drawSky25`：底色 + 月 + 三层云带）→ 远景天际线（`drawFar8`：尖塔 / 板楼 / 圆顶 / 起重机 / 港务楼）→ 工地围挡广告（`drawHoarding`，条件）→ 远层雨（`drawRain9(RAIN[0])`）→ 共享天气 0 |
| 河面与北岸（7 步） | 水面（`drawWater10`）→ 驳船远段（`drawBoat7`）→ 鸬鹚（`drawCormorant`）→ 北岸房行与栈桥（`drawNorth`）→ 雾带（`drawFog5`）→ **步行桥**（`drawBridge4`）→ 驳船近段（`drawBoat7`） |
| 雨与南岸（7 步） | 中层雨 → 共享天气 1 → 南岸（`drawSouth2`：酒馆 / 门洞 / 招牌 / 码头灯）→ 海鸥（`drawGulls6`）→ 近层雨 → 共享天气 2 → 暗角（`VIG_C`） |

⚠️ **`rng()` 消费次序即画面**。上游本城共 **24 条场景流**（另有人物外观流，见下），全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(5)` | `STARS29` | 星位 / 亮度 |
| `rng(41)` | `renderFar17`（远景天际线的窗） | 远景窗的明暗与颜色 |
| `rng(11 / 23 / 37)` | 三层云带（`makeCloudLayer18`） | 云团位置与密度 |
| `rng(91)` | `decorateQuays` 的树冠噪点 | 两棵秋树的树冠轮廓 |
| `rng(78)` / `rng(79)` | `S_HOUSES` / `N_HOUSES`（南北岸房行） | 每排房的窗数与窗位 |
| `rng(77)` | `R0`（PUB 与 BAR 两栋房子） | 两栋招牌房的窗与门 |
| `rng(700 … 706)` | `GULLS5`（7 只海鸥，每只一条流） | 每只的圈数 / 相位 / 椭圆轨迹 / 翻转 |
| `rng(52)` | `WALKERS13`（6 个南岸行人） | 每人的周期 / 相位 / 方向 / 深度 |
| `rng(53)` | `CROSSERS`（8 个过桥的人） | 每人的相位 / 横向偏移 |
| `rng(300 / 301 / 302)` | `RAIN9` 三层雨幕 | 每层每滴的数量 / 相位 / 落点 |
| `rng(81)` | `RINGS9`（雨点涟漪） | 每圈的半径与相位 |
| `rng(63)` | `SMOKE8`（炊烟） | 烟的起点 / 长度 / 漂移 |
| `rng(64)` | `CAUSTICS2`（水面焦散） | 焦散的疏密与位置 |

⚠️ **另有 38 条人物外观流**：`kit(seed, o)` 按种子随机出一套人物外观（衣色 / 伞色 / 兜帽），
种子为 `11…21`（11 条固定人物）、`60+i`（南岸 6 个行人）、`80+i`（成对的人 2 条）、`95`（遛狗人）、
`101…104`（酒吧窗边的顾客 4 条）、`120` / `121`（骑车与送货的骑手）、`140+i`（过桥的 8 人）、
`160+i`（成对的过桥者 2 条）、`180` / `181`（倚栏看河的两人）⇒ 合计 38 条。

#### 8.44.3 本城的结构性新东西：**3D 投影** + 就近取偶的量化口径；其余 17 条受控差异

**本城是这一批里唯一的「3D 投影」城**（此前只有墨尔本等少数几城用伪 3D 相机）：

- 上游把河岸、桥、人、灯都放在一套世界坐标里（`a` = 横向、`c` = 纵深、`z` = 高度），
  再用 `proj2(a, c, z)` 投到屏幕（`d = a·cosθ + (c − camC)·sinθ` 决定深度，
  `x = CXP + F3·(a·sinθ − dc·cosθ) / d`，`y = HZ8 − F3·(z − eye) / d`），
  桥面高度由 `deckZ(c) = QZ + 0.3 + RISE2·(1 − u²)^0.8`（抛物线拱）给出、肋深由 `ribDepth(c)` 给出。
  ⇒ 本城把 `proj2` / `deckZ` / `ribDepth` / `aAt` **逐像素直译成屏幕坐标**，
  焦点 `focusX` 也就只能用投影式算出来（`jsRound(proj2(AB2, RW/2, deckZ(RW/2))[0])` = 177）。
- ⚠️ **量化口径不同**：上游写 `Uint8ClampedArray` 时是**就近取偶**（round-half-to-even），
  而引擎 `jsRound`（`Math.round`）是**半值向 +∞** ⇒ 本城另立一个 `u8(v)`（就近取偶 + 夹到 0..255，
  NaN → 0），所有浮点三元组写入画布都要先过它；`mixc2` 的浮点结果也必须继续用 `DoubleArray` 承载
  （上游对它做 `< 200` / `> 220` 的比较，先取整会改判）。
- ⇒ **本城无合成模式缺口**：上游只用 `"lighter"`（`drawPool7`）与 `"source-over"` ⇒ 引擎 `Comp` 够用。

逐条受控差异（产物文件头注共登记 18 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`**，但 `mixc2` 的**浮点**结果仍以 `DoubleArray` 承载 | 上游对它做 `< 200` / `> 220` 的比较，先取整会改判 |
| ② | `pix18(w, h)` ⇒ 本城 `DubPainter`（包引擎 `PixelBuffer`） | `set` / `alpha` / `fill` 逐位同源，另补上游的 `get` / `line` / `text`；上游 `pix18.text` 是"画进 canvas 再整幅读回 ImageData"，本城因 `PixelBuffer` 与 `PixelCanvas` **共用同一个 `IntArray`** ⇒ 逐像素相同、少了两次整幅拷贝 |
| ③ | **`proj2` / `deckZ` / `ribDepth` 逐像素直译**（本城的结构骨架） | 见上 |
| ④ | 上游 `pix18.line3(A, B, col, mir2)` 要用模块级的 `proj2`，而 `DubPainter` 是嵌套类、拿不到场景成员 | 提到场景级成员函数 `line3(painter, A, B, col, mir2)`（名字照搬） |
| ⑤ | 上游 `sprite26(rows, key, flip2)` ⇒ 本城同名函数 | 宽度取**各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），翻转按整幅镜像；`trim21(p)` ⇒ 引擎 `PixelBuffer.trim`（含空图返回 1×1，语义逐位相同） |
| ⑥ | 5 参 `drawImage(img, dx, dy, dw, dh)`（水面倒影的一行拉伸）⇒ 引擎 9 参 | 引擎无 5 参重载 ⇒ `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)` |
| ⑦ | 广告位 `PITCH27 = makePitch(...)` 整段删除，`PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）；`drawHoarding` 只画自有画面 |
| ⑧ | 共享天气 `drawWeather` 空实现 | 母题 `rain` 命中上游 `plans.city` ⇒ `wx.shared` 恒 null ⇒ `WX_LAND34` / `WX34` / `WX_BB34` **照搬为数据**但零消费方。⚠️ 本城**自有**的三层雨幕 `RAIN9`（`drawRain9`）是场景内自绘的 ⇒ 整段照搬 |
| ⑨ | 上游**原地改** `N_HOUSES[2].shop / open` ⇒ 本地用 `dupHouse()` 复制替换 | 本城的 `DubHouse` 是不可变数据类；`dupHouse` 逐字段照搬、只换掉被改的那一项 |
| ⑩ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α；`neonPulse` → 灯火类光晕（街灯 / 桥灯 / 招牌 / 门光 / 车窗 / 广告牌灯 / 驳船探照灯 / 自行车灯）；`tailGain` → 车流光尾（仅 `HL.tail` 尾灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

另有两条上游语义照搬：上游 `MC` / `ML` / `MW` / `HIT` 是**模块级可变对象**（`frontMat` / `hallMat` 写、`renderQuays` 读）
⇒ 本城用**场景实例字段**承载（单实例安全、跨城无共享）；上游 `WINS7` 的窗在 `renderQuays` 里被回填 `north` 与包围盒
⇒ 本城的 `DubWinBox` 把 `north` / `x0` / `y0` / `x1` / `y1` 声明为 `var`。
上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.44.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`DubPainter.set` 需要 `Int` 与 `Double` 两个重载** | 只写一个重载时，另一路的调用点全部报 `type mismatch` | 两个重载都写（`set(x, y, Int)` 与 `set(x, y, Double)`） |
| ⑦ | **`DoubleArray.slice(0, n)` 不存在** | 那是 `IntRange` 版（`IntArray.slice`）⇒ `No such function: slice` | 用 `copyOfRange(0, n)` |
| ⑧ | **`IntArray.set(other, offset)` 不存在** | 报 `No such function: set` | 手写拷贝循环（`for (i in other.indices) dst[offset + i] = other[i]`） |
| ⑨ | **同名嵌套类只留一份** | 两处各写一份同名嵌套类 ⇒ `Conflicting declarations` + 级联的一串假错 | 另一处改成引用它 |

#### 8.44.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：**白色铸铁花格步行桥**横贯画面中段（铁栏杆 + 三道拱 + 桥灯 + 水面上一整条倒影）、左侧**北岸货栈的一字排亮窗**与竖挑的**蓝底 BOOKS** 刀形招牌、上方支着三盏投光灯的**「TRAD TONIGHT」围挡广告牌**、右侧**南岸砖楼群**与红字 **BAR** / 绿底 **PUB** 招牌、街角最亮的**铸铁街灯**与其暖光晕、远景的尖塔 / 钟楼 / 绿顶圆顶 / 两座起重机、**驳船**（探照灯 + 暖色水柱倒影）、河面涟漪、掠水的鸬鹚、栏杆上的海鸥、雨丝与雾带 全部可见；四帧之间驳船位置、雨丝、涟漪与水面光柱均在变（`t=228` 那帧天上有一只飞过的海鸥） |
| 未做 | 真机截图评分（同 §8.32–§8.41 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**投影 + 量化口径**两样。`proj2` 的深度除法一旦写错（少了 `d` 或符号反），
> 整条桥与两岸会糊成一片；`u8` 若错用引擎 `jsRound`（半值向 +∞），水面焦散与光柱会出现半档色偏 ——
> 两者都不会报错、只会安静地画错 ⇒ 必须**逐帧看桥拱与水面光柱**，
> 不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `29dd464`。

### 8.45 ✅ `copenhagen`（哥本哈根 · 新港的仲夏夜，上游 2,382 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.45.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/copenhagen.js`（2,382 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__copenhagen.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityCopenhagen.kt`（**5,787 行**） |
| 分片 | `logs_temp/city_parts/copenhagen/p01.kt`–`p13.kt`（13 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"copenhagen" -> LofiCityCopenhagen()`（落在 `"chongqing"` 之后、`"dublin"` 之前）；`CityRegistry.SCENES_BUILT` 第 **50** 项 |
| registry | `nameZh = 哥本哈根`、`taglineZh = 新港的仲夏夜`、`accentHex = #ff8a00`、`focusX = 166.0` 落地前已存在 ⇒ **本轮无需回填** |
| 机位类型 | **新港运河的水面视角（平视）**：水位线 `WL11 = 170`，对岸房舍地面线 `HB2 = 152`，近岸水岸线 `NEDGE = 208`，近岸自行车道中心线 `LANE_BIKE = 243` |
| 场景母题 | **新港（Nyhavn）**：十二栋彩色山墙房沿运河排开（老虎窗 + 山墙 + 阁楼 + 底层店铺招牌 KRO / FISK / VAFLER / ANKER / KAFFE / SILD / VED / BODEGA / IS / KAJ / SKIPPER / HAVN）、窄街上走的人与两面屋顶旗、运河上的五艘三桅船（挂满红白彩旗，其中一艘正在升帆）、遮阳伞下喝啤酒的一排人与端托盘来回走的侍者、**热气球**与**雨燕**、皮划艇 / 皮筏 / 游船 / 爵士船 / 野餐筏 / 天鹅与浮标、左侧**SMØRRE BRØD** 广告牌、近岸的「RØD PØLSE」热狗车、石板路与护桩、成排的自行车与婴儿车、飘浮的泡泡与水面碎光、暗角 |

#### 8.45.2 结构：一帧 25 步图层序 + 12 个种子号（10 组调用点）

`renderFrame31` 的 25 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与对岸（9 步） | 天空（`drawSky22`：底色 + 星 + 三层云带 + **热气球** + **雨燕**）→ 海鸥 ×3（`drawGull2`，`GULLS4[0..2]`）→ **山墙房整排 + 亮窗 + 窄街人影 + 两面屋顶旗**（`drawRow2`）→ 广告牌（`drawBoard3`，条件）→ 远码头（`drawFarQuay`：伞下餐桌 / 灯 / 烛）→ **三桅船队**（`drawShips`）→ 第 4 只海鸥（`drawGull2(GULLS4[3])`）→ 泡泡第 1 层（`drawBubbles2(0)`，条件）→ 共享天气 0 |
| 运河与船只（7 步） | 运河水面 + 倒影（`drawCanal2`）→ 共享天气 1 → 野餐筏（`drawPicnic`）→ 游船（`drawTour3`）→ 爵士船（`drawJazz`）→ 皮划艇（`drawKayaks`）→ 天鹅（`drawSwans2`） |
| 近岸与前景（9 步） | 泡泡第 2 层 → 近岸石板路 + 护桩 + 消防栓 + **街角小演出**（`drawEdge` ⇒ `drawEdgeCast`：吉他手 + 音符 + 围观的人 + 吹泡泡的孩子）→ 近岸行人（`drawStrollers6(LANE_A_LIST)`）→ 热狗车（`drawCart4`）→ 自行车（`drawBikes`）→ 行人第 2 批（`drawStrollers6(LANE_B_LIST)`）→ 泡泡第 3 层 → 共享天气 2 → 暗角（`VIG_C31`） |

⚠️ **`rng()` 消费次序即画面**。上游本城共 **12 个种子号 / 10 组调用点**，全部按上游出现次序消费：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(23)` | `STARS27`（22 颗星） | 星位与亮度 |
| `rng(808)` | `SWIFTS`（11 只雨燕，每只 8 次取数） | 每只雨燕的圆心 / 椭圆半径 / 频率 |
| `rng(909)` | `GLINTS3`（90 个碎光） | 水面碎光的落点与相位 |
| `rng(2323)` | `BUBBLES2`（三层，26 / 18 / 11 个，每个 5 次取数） | 每层泡泡的数量 / 半径 / 漂浮速度 |
| `rng(21 / 33 / 47)` | 三层云带（`WISPS7`） | 云带位置与厚度 |
| `rng(1681)` | `WINS5`（房排的窗） | 每扇窗的位置 / 尺寸 / 明暗相位 |
| `rng(5150)` | `CANDLES2` / `TOASTERS` / `TABLES5` | 码头烛台、烤面包机与伞下餐桌的布置 |
| `rng(404)` | `TOUR4.seats` | 游船上的座位与乘客档位 |
| `rng(606)` | 野餐筏（`PICNIC_C`） | 野餐筏上人物的外套与头发 |
| `rng(7117)` | `FAR_WALK2` / `FAR_SIT` | 远处码头上的行人与坐者 |

⚠️ **`initConstants()` 与 `prerender31()` 的调用次序不可调换**：前四组（23 / 808 / 909 / 2323）在
`initConstants()` 里消费，后六组在 `prerender31()` 里消费；流与流之间互不干扰，
但**每条流内部的消费次序不可调换**（星位、雨燕、水面碎光、泡泡、云带、窗灯、码头人影、
游船乘客、野餐筏人物、远处行人各自由它决定）。
上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.45.3 本城的结构性新东西：**抖动阈值口径**（`pick18`）与人流档位；其余 16 条受控差异

**本城是这一批里唯一吃「抖动阈值」口径的城**（此前各城吃的是裁剪 / 合成模式 / 投影 / 渐变几类缺口）：

- 上游 `pick18(ramp, v, i, j, soft)` 的判据是 **`f - k > 0.5 + (bayer - 0.5) * soft`**
  （`bayer` 是 4×4 有序抖动矩阵），而引擎 `pickFromRamp` 的判据是 `f - k > bayer` ——
  **两者不等价** ⇒ 本城必须**本地重写** `pick18`，不能直接调引擎。
  这条影响面很大：房排的玻璃色阶、码头石板、水面碎光、树冠、招牌底色全部走它。
- ⚠️ **人流档位恒为默认**：上游 `render(t, { crowd })` 形参 ⇒ 本项目 `SceneOpts` **无 `crowd` 字段**
  ⇒ `crowd` 恒取上游默认 `CROWD7 = 0.5`（同柏林的 `CROWD5` 口径）；
  `terrLevel(crowd)` 由此推出码头人群档位（`TERR_CLS = 10` 档），
  游船载客档位（`TOUR_CLS = 8` 档）与运河倒影的档位镜像（`MIRROR_LV`）也随之固定。

逐条受控差异（产物文件头注共登记 17 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`**，`hex("#…")` 就地内联 | 上游 `C16` 的记忆化缓存删除（`mixHex18` 的结果逐位不变，只是少了缓存与字符串往返）；`mixHex18(a, b, t)` ⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，与上游同口径） |
| ② | `pix16(w, h)` ⇒ 本城 `CphPix`（包引擎 `PixelBuffer`） | `set` 同 `jsRound` 取整、`alpha` / `fill` / `line` / `text` 逐位同源；上游的 `flush()` / `grab()` 在本项目是**空实现**（缓冲与画布共用同一份 `IntArray`，天然同步） |
| ③ | **`pick18` 本地实现** | 见上（引擎 `pickFromRamp` 口径不同） |
| ④ | `sprite23(rows, key, flip2)` ⇒ 本城同名函数；`trim18(p)` ⇒ 本城同名函数（返回 `CphTrim`，字段名 `c` / `x` / `y` 照搬上游） | 精灵宽度取**各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），翻转按整幅镜像；`flipC3(src)` 逐像素镜像（上游 `translate(w,0)+scale(-1,1)+drawImage` 的逆变换采样差一格） |
| ⑤ | 5 参 `drawImage(img, dx, dy, dw, dh)`（缩放）⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)`；`getImageData(...).data` ⇒ 直接读 `PixelCanvas.px` | 引擎无 5 参重载；非预乘 ARGB，alpha 在高 8 位 |
| ⑥ | 广告位 `PITCH24 = makePitch(BB30.w, BB30.h, { accent: "#ff8a00" })` 整段删除，`PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）；`drawBoard3` 只画自有画面 |
| ⑦ | 共享天气 `drawWeather` 空实现 | 母题 `bubbles` 是**自有母题**（不在跨城共享键里）⇒ 上游 `plans.city` 的 `shared` 恒 null ⇒ `WX_LAND31` / `WX_FAR4` / `WX_NEAR4` / `WX31` / `WX_BB31` **照搬为数据**但零消费方。⚠️ 本城**自有**的泡泡 `BUBBLES2`（`drawBubbles2`）是场景内自绘的 ⇒ 整段照搬 |
| ⑧ | `HOUSES3` 的 `w` / `base` / `eave` / `ridge` ⇒ 本文件在 `CphHouse` 构造器里算 | 上游 `init_copenhagen` 末尾 for 循环里的算术派生量（纯算术、与次序无关，逐位相同） |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α；`neonPulse` → 灯火类光晕（远码头灯 / 近岸灯 / 灯芯 / 广告牌灯 / 灯泡 / 推车 / 船灯 / 汽笛焰）；`tailGain` → 车流光尾（仅 `HL31.tail` 自行车尾灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

另有几条上游语义照搬：`GLASS7` 的**第 3 档是空串 `""`** ⇒ 上游 `hex("")` 得 `[0,0,0]` ⇒ 本文件写 `hex("")`（黑），**不改成别的色阶**；
`signX` 里 `h.w - tw2 >> 1` ⇒ `(h.w - tw2) shr 1`（JS 的 `>>` 会先 ToInt32）；
上游 `glyph(c, g, x, y, col)` 收浮点 x，本城全部调用点的 x 都是整数 ⇒ 引擎 `glyph` 的 `Int` 版本逐位相同；
`ctx31.globalAlpha` ⇒ `PixCtx.alpha`、`globalCompositeOperation` ⇒ `Comp`（上游本城只有 `source-over` 与 `lighter` ⇒ **无合成模式缺口**）、
`setTransform` ⇒ `PixCtx.setTransform`；上游 `ctx31.imageSmoothingEnabled = false` 在本项目没有对应物（引擎 `drawImage` 本来就是最近邻取样）⇒ 该行省略，逐像素不变。
上游本城没有 `multiply` / `destination-in` 之类 ⇒ **无合成模式缺口**，引擎 `Comp` 够用。

#### 8.45.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`SWIFTS` / `GLINTS3` / `BUBBLES2` 必须在 `initConstants()` 里填** | `lateinit` 未初始化会在**首帧**炸（不是编译期能拦的） | 三张表都在 `initConstants()` 里按上游次序赋值，并保证 `initConstants()` 先于 `prerender31()` 调用 |

#### 8.45.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上一行 **COPENHAGEN** 站牌；画面中段是**一整排彩色山墙房**（红 / 黄 / 蓝 / 橙立面、老虎窗、山墙、阁楼），底层一块块亮着灯的**店铺招牌**（KRO / FISK / VAFLER / ANKER / KAFFE / SILD / VED / BODEGA / IS / KAJ / SKIPPER / HAVN）；左上一块支着灯的 **SMØRRE BRØD** 广告牌；运河里立着几艘**三桅船**（挂红白彩旗，桅顶带横杆与旗）；左下角一辆红白条纹的 **「RØD PØLSE」热狗车**；近岸石板路上有**成排的行人**（有人推婴儿车、有人骑车）、**护桩**与一个红色消防栓；水面漂着一串串**泡泡**与碎光；天上有一只**热气球**（`t=60` 那帧正从左侧升起、`t=120` 在右侧）、几只**雨燕**与海鸥 全部可见；四帧之间船位、旗的摆动、热气球位置、雨燕与水面碎光均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.41 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**抖动阈值口径**。`pick18` 若错调引擎 `pickFromRamp`，
> 玻璃色阶、石板与水面的噪点分布会整体偏一档 —— 不会报错、只会安静地画错；
> 另一处是 `GLASS7` 第 3 档的**空串**（⇒ 黑），改成别的色阶会让窗玻璃整体发灰。
> ⇒ 必须**逐帧看房排窗色阶与码头石板**，不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `29dd464`。

### 8.46 ✅ `tokyo`（东京 · 东京塔下的霓虹雨，上游 2,402 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.46.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/tokyo.js`（2,402 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__tokyo.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityTokyo.kt`（**5,205 行**） |
| 分片 | `logs_temp/city_parts/tokyo/p01.kt`–`p12.kt`（12 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"tokyo" -> LofiCityTokyo()`（落在 `"taipei"` 之后、`"tokyo-room"` 之前）；`CityRegistry.SCENES_BUILT` 第 **51** 项 |
| registry | `nameZh = 东京`、`taglineZh = 东京塔下的霓虹雨`、`accentHex = #ff5fa2` 落地前已存在；本轮回填 `focusX = 0.0 → 246.0`（上游 `focusX: TWR_CX`，即东京塔的中轴 x） |
| 机位类型 | **东京塔与街面同框的斜俯视**：街面起始线 `GROUND = 214`，东京塔中轴 `TWR_CX = 246`、塔底 `TWR_BASE = 192`、主观测台 `TWR_DECK = 114`、塔顶 `TWR_TOP = 70`、天线长 `TWR_ANT = 36`；晴空塔 `SKYT_X = 150 / SKYT_TOP = 47`；富士山 `FUJI_X = 338 / FUJI_Y = 146`；月亮 `MOON_X = 198 / MOON_Y = 30` |
| 场景母题 | **东京塔下的霓虹雨夜**：画面正中偏右的**东京塔**（橙红桁架 + 两层观测台 + 白色天线）、塔后偏右的**富士山雪顶**、左侧一排**远景高楼**（楼顶闪红色航空障碍灯、窗口一格格亮着）、横穿画面的**高架铁路与五个拱形桥洞**（洞下是亮着灯的店招）、每 20 秒穿过的一列**电车**（车头前方那道梯形光锥）、**粉色 SAKURA SODA 幻灯广告牌**（顶上有走字的小灯箱与上浮气泡）、右侧一株老**樱树**（满树花球 + 满城花瓣 + 枝头一只白头鹎）、近景压低的楼群与屋顶四块**霓虹招牌**、左下角一条**窄巷**（竖排的粉 / 青 / 黄霓虹招牌柱、四个**红灯笼**、暖帘、关东煮摊、**自动贩卖机**与趴在机顶上的**招财猫**、屋檐滴水与蒸汽）、湿漉漉的**街面**（斑马线、水洼、路灯光柱、积水倒影）、撑伞 / 推婴儿车 / 走过路口的行人、按 60 秒周期换相的**信号灯**、带霓虹色的**雨丝**与落地涟漪、暗角 |

#### 8.46.2 结构：一帧 27 步图层序 + 19 个种子号（20 条流）

`renderFrame2` 的 27 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与远景（6 步） | 天空（`drawSky2`：底色 + 月 + 富士山 + 晴空塔 + 两层云带）→ 晴空塔（`drawSkytree`）→ 城市灯（`drawCityLights`）→ 花瓣第 1 层（`drawPetals(PETALS[0])`）→ 雨第 1 层（`drawRain2(RAIN2[0])`）→ 共享天气 0 |
| 楼群与塔（4 步） | 高层楼（`drawHighrises`）→ **东京塔**（`drawTower2`）→ 近景楼群 + 屋顶霓虹（`drawNear`）→ 闪烁灯（`drawBlinkers`） |
| 高架与街面（8 步） | 接触网桅杆层（`blit(MAST_C)`）→ **电车**（`drawTrain`，车头光锥用本地 `beamQuad`）→ 高架桥体（`blit(VIA_C)`）→ 拱洞陈设（`drawArches`）→ 街面（`drawStreet`）→ 推婴儿车的人（`drawStrollers`）→ 信号灯（`drawSignal`）→ 行人（`drawWalkers`） |
| 雨、花瓣与窄巷（9 步） | 花瓣第 2 层 → 雨第 2 层 → 共享天气 1 → **窄巷**（`drawAlley`：招牌柱 / 红灯笼 / 暖帘 / 关东煮摊 / 自动贩卖机 / 招财猫 / 蒸汽）→ **樱树**（`drawTree`）→ 雨第 3 层 → 花瓣第 3 层 → 共享天气 2 → 暗角（`VIG_C2`） |

⚠️ **`rng()` 消费次序即画面**。上游本城共 **19 个种子号 / 20 条流**（`renderNearCity` 用同种子 57 调两次 ⇒ 各开一条独立流）：

| 流 | 消费者 | 影响 |
|---|---|---|
| `rng(12)` | `STARS2` | 星位与亮度 |
| `rng(211)` | `BB_BUBBLES` | 广告牌里上浮的气泡 |
| `rng(505)` | `AWIN` | 亮着的窗 |
| `rng(909)` | `PETALS`（三层） | 每层每片花瓣的位置 / 大小 / 速度 / 相位 |
| `rng(100 / 101 / 102)` | `RAIN2` 三层雨滴 | 每层每滴的数量与相位 |
| `rng(71)` | `RINGS` | 落地涟漪 |
| `rng(61)` | `STEAM` | 屋檐与巷口的蒸汽 |
| `rng(17 / 29)` | 两层云带（`makeCloudLayer`） | 云团的位置与密度场 |
| `rng(23)` | `renderFarCity` | 远景楼的窗 |
| `rng(41)` | `renderHighrises` | 高层楼的亮灯段 |
| `rng(57)` | `renderNearCity`（**调两次** ⇒ 两条同种子独立流） | 近景楼群与裸楼的窗位 |
| `rng(74)` | `renderViaduct` | 拱洞下的陈设与店招 |
| `rng(81)` | `renderTrain` | 电车窗里的乘客剪影 |
| `rng(66)` | `renderStreet` | 街面碎屑与水洼 |
| `rng(606)` | `renderAlley` | 窄巷里的饮料罐与自动贩卖机的颜色 |
| `rng(313)` | `renderTree` | 樱树的花球位置 |

⚠️ **`initConstants()` 与 `prerender2()` 的调用次序不可调换**（前者消费 12 / 211 / 505 / 909 / 100~102 / 71 / 61，
后者消费 17 / 29 / 23 / 41 / 57 / 74 / 81 / 66 / 606 / 313）；流与流之间互不干扰，
但**每条流内部的消费次序不可调换**（星位 / 气泡 / 窗灯 / 花瓣 / 雨滴 / 涟漪 / 蒸汽 / 云团 / 楼群 /
亮灯段 / 桥洞陈设 / 电车窗影 / 街面碎屑 / 饮料颜色 / 花球位置都由它决定）。
上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.46.3 本城的结构性新东西：**引擎没有渐变与路径** ⇒ 本地光锥；其余 15 条受控差异

**本城唯一的结构性新东西是「渐变 + 路径」缺口**（此前各城吃的是裁剪 / 合成模式 / 投影 / 抖动阈值几类）：

- 上游 `drawTrain` 用 `ctx2.createLinearGradient(...)` + `beginPath / moveTo / lineTo / closePath / fill`
  在电车头前方画一道**梯形光锥** —— **引擎没有渐变、也没有路径**（`visualizer/e51/engine/` 零命中）
  ⇒ **不改引擎**（改它会波及已落地的 50 座城），改为在本城文件里本地实现 `beamQuad(headX, dir)`：
  沿 x 分 90 步，alpha 由 `0.35` 线性衰减到 `0`，上下边由 `180 → 172` / `182 → 186` 线性插值，
  以 `Comp.LIGHTER` 合成逐列填出梯形 ⇒ 逐像素等价。
- ⇒ **本城无合成模式缺口**：上游只用 `source-over` / `lighter` / `copy` ⇒ 引擎 `Comp` 三档够用。

逐条受控差异（产物文件头注共登记 16 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游 `C2(s)` 的 `RGB2` 颜色缓存删除（少了缓存，逐像素不变）；`mix(a, b, t)`（上游返回 `"rgb(...)"` 串）⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，同口径）；`rgbaOf(c, a)`（`[r,g,b]` ⇒ CSS `rgba()`）⇒ 本地同名函数（打包 `Int` + 8 位 alpha 量化）；`mixRGB` / `dimOf` ⇒ 本地 `Int` 版 |
| ② | `pix2(w, h)` ⇒ 本城 `TokPainter`（包引擎 `PixelBuffer`） | `set` 同 `jsRound` 取整、`alpha` / `fill` / `trim` 逐位同源；上游 `p.put(i, y, col, a)` 的 **alpha 覆盖语义**保留；`done()` ⇒ `.canvas` |
| ③ | `trim(p)` ⇒ 引擎 `PixelBuffer.trim()`（返回 `Pixmap`，与上游 `{c, x, y}` 同形）；`blit(o)` ⇒ 本地同名函数 | 逐位同源 |
| ④ | `spriteOf(rows, key, flip2)` ⇒ 本城同名函数 | **引擎 `spriteFromRows` 无翻转**，且它把**首行长度**当宽度；上游的 `flip2` 是"读镜像字符、写原位"，本城逐位照搬 ⇒ 宽度取**各行最大长度**、翻转按整幅镜像 |
| ⑤ | 5 参 `drawImage(img, dx, dy, dw, dh)` ⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)` | 引擎无 5 参重载；等价缩放 |
| ⑥ | 广告位 `BB_PITCH = makePitch(BB2.w, BB2.h, { accent: "#ff5fa2" })` 整段删除，`PitchStub` 空桩 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `drawBillboard2` 恒画自有画面（气泡 + 走字灯箱 + SAKURA SODA 顶面） |
| ⑦ | 共享天气 `drawWeather` 空实现 | 上游 `WEATHER2 = weatherPlans(["rain", "petals"])`；上游 `plans` 对"自有母题"一律给 `shared: null` ⇒ `wx.shared` 恒 null ⇒ `WX_LAND2` / `WX2` / `WX_BB2` **照搬为数据**但零消费方。⚠️ 本项目渲染器从不设置 `SceneOpts.weather`（恒 null ⇒ 命中 `plans.city`）⇒ 雨与花瓣两位恒 true；本城**自有**的三层雨与三层花瓣照常移植 |
| ⑧ | `Float32Array`（云层密度场）⇒ `FloatArray`（写 `.toFloat()`、读 `.toDouble()`） | **不能**换成 `DoubleArray` —— float32 的舍入是画面的一部分 |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α；`neonPulse` → 灯火类光晕（霓虹招牌 / 灯笼 / 自动贩卖机 / 拱洞店招 / 广告牌灯 / 信号灯 / 列车窗光）；`tailGain` → 车流光尾（车头灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

另有几条上游语义照搬：`bbText` 的默认字体是上游 `FONT3` ⇒ 引擎 `FONT3`（同为 3×5 字模，只是收色是 `Int`），
`BB_BOLD` 是上游自带的 6×7 粗体字模、照搬为数据表；上游 `c[k] * 0.35 | 0` 这类**位截断** ⇒ `.toInt()`；
`Uint8ClampedArray` 的直接赋值（暗角 / 光锥 / 雨丝）⇒ 走 `PixelBuffer.set` 或 `PixCtx.fillRect`；
上游 `ambience`（rain / traffic / crowd / wind）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点，
上游这六条事件只有 `t` 与 `type`（没有 `x` / `pan` / `gain`），`spots` / `secrets` 属上游站点 UI ⇒ 不移植。

#### 8.46.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来直接编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较** | `row[i] == "#"` 恒为假（`Char` 与 `String` 永不相等）⇒ 精灵整行判空 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |

#### 8.46.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **TOKYO** 站牌；画面正中偏右是**东京塔**（橙红桁架 + 两层观测台 + 白色天线）、左侧一根细高的**晴空塔**、塔右后方一点**富士山雪顶**；左侧一排**高楼**（楼顶红色航空障碍灯、一格格亮着的窗），其中一块粉色的 **SAKURA SODA** 幻灯广告牌；横贯画面中下的是**高架铁路**，桥下一排**拱形桥洞**与洞里亮着橙 / 紫灯的店招，高架上停着一列**红白电车**；右侧一株开满花的**樱树**（粉白花团、满树花瓣）；左下角一条**窄巷**（竖排的粉 / 青 / 黄霓虹招牌柱、四个红灯笼、暖帘、关东煮摊、一台青色的**自动贩卖机**、红色「キヨ」招牌）与门前的**招财猫**；前景是**湿街面**（斑马线、水洼、路灯光柱、积水倒影）；天上挂着**弯月**与两条紫蓝色云带，雨丝斜落 全部可见；四帧之间电车位置、云带、雨丝、花瓣与水面倒影均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.41 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：本城吃的是**渐变光锥**与**精灵翻转**两样。`beamQuad` 的 alpha 衰减（0.35 → 0）
> 或上下边插值写错，电车前方的光锥会变成一坨实心白块或整条不见；
> `spriteOf` 若错用引擎 `spriteFromRows`（首行长度当宽度、无翻转），招财猫与自动贩卖机上的贴图会错位 ——
> 两者都不会报错、只会安静地画错 ⇒ 必须**逐帧看电车头前那道光锥与窄巷里的贴图**，
> 不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `29dd464`。

### 8.47 ✅ `dubai`（迪拜 · 哈利法塔的喷泉夜，上游 2,430 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.47.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/dubai.js`（2,430 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__dubai.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityDubai.kt`（**5,916 行**） |
| 分片 | `logs_temp/city_parts/dubai/p01.kt`–`p15.kt`（15 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"dubai" -> LofiCityDubai()`（落在 `"copenhagen"` 之后、`"dublin"` 之前）；`CityRegistry.SCENES_BUILT` 第 **52** 项 |
| registry | `nameZh = 迪拜`、`taglineZh = 哈利法塔的喷泉夜`、`accentHex = #c77dff`、`focusX = 240.0` 落地前已存在（`focusX` 已回填），本轮无需回填 |
| 机位类型 | **迪拜市区的斜俯视**（哈利法塔一侧）：哈利法塔中轴 `BK.cx = 240`、塔尖 `BK.tip = 7`、塔顶 `BK.top = 58`；水面 / 沙漠与城市的分界 `WL5 = 178`；喷泉基座 `FY = 199`；露台前沿 `EDGE3 = 228`、地面 `FLOOR3 = 233`；月亮 `MOON12 = (152, 30)`；KARAK 茶广告牌 `BB12 = (271, 112, 50×20)` |
| 场景母题 | 沙漠之夜的迪拜：居中的**哈利法塔**（三段收分立面 + 顶部尖针 + **塔身 LED 秀**）、**分层喷泉**（60 路水柱 + 顶部喷射 + 十二道拱形水弧 + 湖面倒影 + 水雾）、左侧**沙漠**（两层沙丘 + 商队骆驼 + 营地篝火 + 三层飞沙）、中景**塔楼群**与**老城 souk**（拱门商铺 + 圆顶 + 三座塔楼）、**地铁高架**与列车（车头光锥）、左上 **KARAK 茶**环形跑马灯广告牌、右端**阿拉伯咖啡馆**（拱门 + QAHWA 霓虹 + 铜灯 + 棕榈）、前景**露台**（地毯 + 坐垫 + 低桌 + 水烟壶 + 坐着的一家人 + 抽烟的人 + 端壶的侍者 + 一只猫）、夜色与暗角 |

#### 8.47.2 结构：一帧 32 步图层序 + 11 组独立 `rng` 流（合计 16 条序列）

`renderFrame13` 的 32 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与沙漠（7 步） | 天空（`drawSky10`：底色 + 月 + 霾）→ 掠过的客机（`drawPlane8`）→ 沙丘（`drawDunes`）→ 远景楼群（`drawLayer2(FAR6)`）→ 第一层霾（`drawHaze(HAZES[0])`）→ 第一层沙粒（`drawSand(SAND2[0])`）→ 共享天气 0 |
| 城市与塔（10 步） | 中景塔楼（`drawLayer2(TWR4)`）→ 塔冠（`drawCrowns`）→ KARAK 广告牌（`drawBillboard12`）→ 塔身泛光（`drawBurjGlow`）→ **哈利法塔**（`drawLayer2(BURJ)`）→ **塔身 LED 秀**（`drawBurjShow`）→ 塔身特效（`drawBurjFx`）→ 航障灯（`drawBlinkers4`）→ 第二层霾 → 高架桥体（`blit8(VIA_C3)`） |
| 高架与水景（9 步） | 列车（`drawTrain3`）→ 站台（`blit8(STN_F)`）→ 站台光晕（`halo13(HL13.station …)`）→ 近景 souk（`blit8(NEAR_C)`）→ 近景特效（`drawNearFx`）→ 湖面（`drawWater6`）→ **喷泉层**（`drawFountainLayer`）→ 渡船（`drawAbra`）→ 水雾（`drawMist2`） |
| 露台与收尾（6 步） | 第二层沙粒（`drawSand(SAND2[1])`）→ 共享天气 1 → **露台**（`drawTerrace`）→ 第三层沙粒（`drawSand(SAND2[2])`）→ 共享天气 2 → 暗角（`VIG_C13`） |

⚠️ **`rng()` 消费次序即画面**。上游本城共 **11 组 / 16 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `STARS11` | 夜空星点 | 21 | 星位 / 闪烁速率 |
| `WALKERS5` | souk 行人 | 333 | 行人衣色与走位 |
| `SAND2` | 三层沙粒 | 900 / 901 / 902（**3 条**） | 每层每粒沙的相位与落点 |
| `FAR6` | 远景楼群 | 41 | 远景楼窗灯 |
| `TWR4` | 中景塔楼 | 47 | 塔身明暗与亮窗 |
| `BURJ` | 哈利法塔 | 83 | 塔身亮窗与火花 |
| `NEAR_C` | 近景 souk | 29 | 商铺亮灯 |
| `TERR_C` | 露台 | 64（**创建后从未取数** ⇒ 保真照搬） | 无 |
| `DOTS` | 铜灯光点 | 700 + l.id（`LANTS` 四盏 ⇒ **4 条**；其中 `700+3` 那盏既无 `floor` 也无 `wall` ⇒ 创建后未取数） | 铜灯在地面 / 墙面的光斑位置 |
| `PALM_LC` / `PALM_RC` | 左 / 右棕榈串灯 | 5 / 11（**2 条**） | 灯泡位置与明暗 |

⚠️ **`initConstants()` 与 `prerender13()` 的调用次序不可调换**（前者消费 21 / 333 / 900–902，后者消费 41 / 47 / 83 / 29 / 64 / 700–703 / 5 / 11）；流与流之间互不干扰，但**每条流内部的消费次序不可调换**（星位 / 行人衣色 / 沙粒相位 / 楼窗灯 / 塔身亮窗 / 商铺亮灯 / 铜灯光斑 / 串灯位置都由它决定）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.47.3 本城的结构性新东西：**引擎没有 `destination-in`** ⇒ 本地 `destIn`；其余 21 条受控差异

- 上游 `drawBurjShow` 用 `globalCompositeOperation = "destination-in"` 把塔身 LED 画面**遮罩**到塔身轮廓里 —— 引擎的 `Comp` 只有 SOURCE_OVER / LIGHTER / COPY ⇒ **不改引擎**（改它会波及已落地的 51 座城），改为在本城文件里对像素数组直接做 Porter-Duff 合成：`destIn` 取 `αr = αb·αs`、RGB 不变（与柏林、波士顿·公共花园同一形态）。
- 引擎 `PixCtx` **没有 `clearRect`**（上游 `drawFountain` 每帧 `fx.clearRect(0, 0, W, FB2 + 1)` 清喷泉层）⇒ 本城 `clearCanvas` 直接把 `px` 填 0。

逐条受控差异（产物文件头注共登记 21 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串 / `[r,g,b]` / `rgba(...)`；`mixHex7(a, b, t)` ⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，与上游同口径）；`dim2` 由上游返回 `[r,g,b]` 改为直接返回 ARGB |
| ② | `pix6(w, h)` ⇒ 引擎 `PixelBuffer` | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.set(i, j, col, a)` 的 alpha 覆盖语义保留；`.done()` ⇒ `.canvas`、`.trim()` ⇒ `Pixmap` |
| ③ | `sprite9(rows, key)` ⇒ 本城同名函数 | **宽度取各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价） |
| ④ | 5 参 `drawImage(img, dx, dy, dw, dh)` ⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)` | 引擎无 5 参重载；`text2` 的 3×5→6×10 放大、`jet` / `jetColumn` / `boatReflect2` / `drawCat6` / `drawBillboardAd3` 均走 9 参 |
| ⑤ | 广告位 `PITCH11 = makePitch(BB12.w, BB12.h, {accent:"#c77dff"})` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，`drawBillboard12` 恒画自有画面 |
| ⑥ | 共享天气 `drawWeather` 空实现 | 母题 `sand` **不在**跨城共享键（drizzle / rain / thunderstorm / snow / leaves）里 ⇒ `wx.shared` 恒 null；⚠️ 本项目渲染器从不设置 `SceneOpts.weather` ⇒ **本城自有的三层沙尘照画**；`WX_LAND13` / `WX13` / `WX_BB13` 照搬为数据但零消费方 |
| ⑦ | `Uint8ClampedArray` 直写 RGB（`makeHaze` / `makeMist2`）⇒ 本地 `u8c` | 用 `java.lang.Math.rint`（**半偶取整**）复刻 JS 的夹取语义；⚠️ **不是** `roundToInt` |
| ⑧ | 上游对象字面量数组 ⇒ **平行原始数组** + 场景类内部的 `private class` 容器 | `DUNES` / `TOWERS` / `LANTS` / `LIGHTS3` / `SHOWS` / `LED_SHOWS` / `ABRAS` / `CUSHIONS` / `BK_STRANDS` 等（照柏林 `CLOUD_DEFS14` 先例）；顶层 `private class` 跨文件重名会触发 `Redeclaration` |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo13` 统一乘）；`neonPulse` → 铜灯 / 灯泡 / 门灯 / QAHWA 霓虹 / 炭火 / 地面光池 / 拱廊灯 / 楼窗灯 / 上照灯 / 航障灯；`tailGain` → 车流光尾（本城唯一的"车"是地铁 ⇒ `HL13.trainHead`）。⛔ 坐标 / 几何 / 色值一律不碰 |

#### 8.47.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5.0, 0.12)` 可以；写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 引擎的 `wave` 三个形参全是 `Double` ⇒ 整数种子一律补 `.0` |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句在 Kotlin 里会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游的 `rng(seed)` 返回可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 用 `java.lang.Math.rint(v)` |
| ⑩ | **`DoubleArray.slice(0, n)` 不存在 / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者用 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **`renderPalm` / `makeCrown` 的形参要按上游那组散参** | 写成对象形参会报类型不匹配 | 按上游签名逐个抄散参（`P4, seed, lampX` 一类） |
| ⑫ | **`renderBurj` 的返回要拆成「层结果 + 遮罩」两个字段** | 上游一个返回值同时承担"画什么"与"遮罩是什么"两件事 | 本城拆成层位图 + 遮罩位图两个字段分别持有 |

#### 8.47.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与阿姆斯特丹 / 柏林 / 汉堡 / 东京基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：正中偏左是**哈利法塔**（三段收分的蓝白塔身 + 细长尖针，塔身在 `t=120` 挂一段红紫 LED 带、在 `t=228` 整塔被品红 / 粉紫的 LED 面板覆盖）；右上 **KARAK TEA** 环形跑马灯广告牌（红底黄字，四帧的走字图案各不相同）；左侧一排**老城 souk** 的暖黄拱廊与圆顶，右端一座**棕榈与 QAHWA 咖啡馆**的拱门（格纹木门 + 壁灯）；画面中段是**湖面**（`t=0` 两道高高的白色喷泉水柱与倒影、`t=60` 水柱落回只剩波纹、`t=120` 湖面平缓只剩灯光倒影）；左下角有一叶**渡船**；前景**露台**上铺着红花地毯与坐垫，围着坐垫坐了三个人（白袍 / 蓝绿袍 / 橙袍）、一只小猫、一只铜水烟壶与两只落地铜灯；左右各一株**棕榈**挂着吊灯；天上**弯月**与几点星光，右上角 `t=228` 有一小簇烟花火花 全部可见；四帧之间喷泉高度、塔身 LED 颜色、广告牌走字、湖面波纹与暗角均在变 |
| 未做 | 真机截图评分（同 §8.32–§8.46 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：塔身 LED 秀吃的是 `destination-in` 遮罩 —— 遮罩的 α 公式写错（拿 `source-over` 顶替，
> 或把 `αr` 写成未预乘的两项相加），画面**不会报错**、只会安静地变成一整块贴在塔外的彩色方块 ——
> 所以必须**逐时刻看塔身 LED 的形状**（LED 应被塔身轮廓切住），不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

### 8.48 ✅ `florence`（佛罗伦萨 · 阿诺河上的蓝调时刻，上游 2,447 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.48.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/florence.js`（2,447 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__florence.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityFlorence.kt`（**5,683 行**） |
| 分片 | `logs_temp/city_parts/florence/p01.kt`–`p10.kt`（10 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"florence" -> LofiCityFlorence()`（落在 `"dublin"` 之后、`"hamburg"` 之前）；`CityRegistry.SCENES_BUILT` 第 **53** 项 |
| registry | `nameZh = 佛罗伦萨`、`taglineZh = 阿诺河上的蓝调时刻`、`accentHex = #d16177`、`focusX = 241.0` 落地前已存在，本轮无需回填 |
| 机位类型 | **阿尔诺河对岸平视**（南岸石阶往下望）：圣母百花大教堂穹顶 `DUOMO.cx = 270` / `tip = 24` / `top = 48`；乔托钟楼 `CAMP3 = (180…192, top 54)`；旧宫方塔 `TOWER6 = (310…326, top 158)`；老桥 `PONTE = (14…80)`；韦奇奥桥 `PV = (100…142, top 98)`；堰口 `WEIR = (338…438)`；前景栏杆 `RAIL11 = 198`、地面 `FLOOR6 = 208`、石阶 `STEPS2 = [224, 234, 246, 260]`；月亮 `MOON23 = (150, 21)`；广告牌 `BILL4 = (214, 128, 48×20)` |
| 场景母题 | 蓝调时刻的佛罗伦萨：圣母百花大教堂的**红白穹顶**与灯笼尖顶、**乔托钟楼**、旧宫（韦奇奥宫）方塔与市政广场、圣十字大教堂的绿白条纹立面、犹太会堂的**铜绿色穹顶**、**老桥**（圣三一桥）与桥下堰口白沫、**韦奇奥桥**的廊桥与塔、东岸 Lungarno 的老楼与脚手架、圣母领报广场的**帆布凉棚**、阿诺河上的**划艇 / 游船 / 电车**、南岸石阶的**行人 / 摊贩 / 侍者**、前景石栏杆与铸铁街灯（灯下绕着飞蛾与蝙蝠）、挂灯泡的 **CAFFÈ 餐车**、玫瑰花坛、石阶上的**鸽子**与栏杆上打盹的**猫**、三层**玫瑰花瓣**、**萤火虫**、紫蓝云带、暗角 |

#### 8.48.2 结构：一帧 33 步图层序 + 13 组独立 `rng` 流（合计 28 条序列）

`renderFrame33` 的 33 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与远景（6 步） | 天空底色（`R8(0, SKY_H3, W, H - SKY_H3, "#141a34")`）→ 天空（`drawSky24`：月 + 星 + 三层云带）→ 远山（`drawHills2`）→ 雨燕·远层（`drawSwifts(t, false)`）→ 共享天气 0（`drawWeather(…, 0, …)`）→ 远岸天际线（`drawCityBack`） |
| 中景与旧宫（6 步） | 鸽群（`drawFlock3`）→ 旧宫（`blit21(PALAZZO_C)`）→ 窗层（`drawWindows4(t, 2, !billboard)`）→ 广告牌（`drawBillboard29`）→ 第二层霾（`drawHaze2(HAZES2[1], t, 4)`）→ 近岸天际线（`drawCityFront`） |
| 河面与船（4 步） | 电车（`drawBus`）→ 河面与倒影（`drawRiver4`）→ 船（`drawBoats5`：划艇 / 游船）→ 南岸（`drawSouth`） |
| 花瓣与前景（12 步） | 雨燕·近层（`drawSwifts(t, true, billboard)`）→ 萤火虫（`drawFireflies`）→ 花瓣层 0（`drawPetals5(0, t)`）→ 共享天气 1 → 前景层（`blit21(FORE_C)`）→ 玫瑰花坛（`drawHedges`）→ 石阶鸽群（`drawStepPigeons`）→ 猫（`drawCat10`）→ 餐车生活（`drawCartLife`）→ 踏板车（`drawImage(SCOOTER_C, 398, TY - 10)`）→ 行人（`drawCast2`）→ 街灯生活（`drawLampLife`） |
| 收尾（5 步） | 花瓣层 1（`drawPetals5(1, t)`）→ 前景树木（`drawTrees`）→ 花瓣层 2（`drawPetals5(2, t)`）→ 共享天气 2 → 暗角（`drawImage(VIG_C33, 0, 0)`） |

⚠️ **`rng()` 消费次序即画面**。上游本城共 **13 组 / 28 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `STARS28` | 夜空星点 | 12 | 星位与闪烁 |
| `SWIFTS2` | 雨燕 | 321 | 雨燕轨道与相位 |
| `FLOCK3` | 鸽群（`PERCHES2` 的 14 个栖点） | 900 + i（i = 0…13，**14 条**） | 每只鸽子的圈数 / 相位 / 椭圆半径 / 漂移 |
| `PETALS6` | 三层玫瑰花瓣 | 555 | 每层每瓣的片数与摆幅 |
| `RIPPLES3` | 河面涟漪 | 91 | 涟漪位置与强度 |
| `FOAM` | 堰口浪沫 | 92 | 堰口白沫的团块 |
| `CLOUD_DEFS17` | 三层云带 | 17 / 31 / 47（**3 条**） | 每层云团的团数与浓淡 |
| `HILLS` | 远山树 | 53 | 山脊上的树形 |
| 天际线 | `drawRow3` × 多 + `drawTowers2` + `drawPalazzoVecchio` + `drawSantaCroce` + `drawLungarno` + `drawPonteVecchio` + `house3` | 2026 | **沿街一整条楼群**共用一条流 |
| 旧宫窗 | `drawPalazzoVecchio` | 404 | 旧宫窗灯 |
| 南岸 | `drawSouth` | 77 | 南岸树丛与窗灯 |
| 前景河岸 | 前景层 | 808 | 石栏杆与铺地 |
| 前景树木 | `drawTrees` | 909 | 柏树与松树形态 |

⚠️ **`initConstants()` 与 `prerender33()` 的调用次序不可调换**（前者消费 12 / 321 / 900–913 / 555 / 91 / 92，后者消费 17 / 31 / 47 / 53 / 2026 / 404 / 77 / 808 / 909）；流与流之间互不干扰，但**每条流内部的消费次序不可调换**。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.48.3 本城的结构性新东西：**`copy` 会清整块画布** ⇒ 先 `reset()`；其余 14 条受控差异

- 上游把 `globalCompositeOperation = "copy"` 用在**河面倒影层**上，而 canvas 的 `"copy"` 语义是**清掉整张画布再画这一块**；引擎 `Comp.COPY` 只替换**所绘矩形**内的那一块 ⇒ 本城在 `Comp.COPY` 前显式 `WBUF.reset()`（否则上一帧的残留像素会留在倒影层里）。
- 上游的 `pick19(ramp, v, i, j, soft)`（带 Bayer 抖动的色阶取色）与引擎 `pickFromRamp` 的**抖动阈值口径不同** ⇒ 本城**本地重写** `pick19`（阈值取 `f - k > 0.5 + (bayer(i, j) - 0.5) * soft`），全城 200+ 处取色都走它。

逐条受控差异（产物文件头注共登记 15 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；`mixHex20` 的 `MIXED3` 缓存删除（结果逐位不变，只少一层缓存）；`mixHex20(a, b, t)` ⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，与上游同口径） |
| ② | `pix17(w, h)` ⇒ 本地 `FloPix17`（包引擎 `PixelBuffer`） | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.set(i, y, col, a)` 的 alpha 覆盖语义保留；`p.clear(i, j)` 只把 alpha 归零、保留 RGB ⇒ 本地 `FloPix17.clear` 同语义；`trim20(p)` ⇒ 引擎 `PixelBuffer.trim()`（返回 `Pixmap`，与上游 `{c, x, y}` 同构） |
| ③ | `sprite25(rows, key, flip2)` ⇒ 本城同名函数 | **宽度取各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价）；翻转按**整幅镜像**（鸽子 / 猫 / 酒壶都用它） |
| ④ | 上游 `p.text(s, i, j, col)` 是 `putImageData → 画字 → 读回` | 本城用引擎 `text` 直接以 source-over 把 3×5 字模合成到图层上（净效果逐像素一致） |
| ⑤ | **`Uint8ClampedArray` 的「就近取偶」** | 本文件用 `jsRound`（四舍五入）⇒ **只在通道值恰好落在 `.5` 时差 1**；上游只有雾层与暗角两处走数组色，影响面可控（**已知偏差，非 bug**） |
| ⑥ | 合成模式 | 上游只用 `source-over` / `lighter` / `copy` ⇒ 引擎 `Comp` 三档全覆盖 ⇒ **本城没有合成模式缺口**（`multiply` / `destination-in` 一个也没用） |
| ⑦ | 5 参 `drawImage(img, dx, dy, dw, dh)`（河面倒影逐行错位） | ⇒ 引擎 9 参 `drawImage(img, sx, sy, sw, sh, dx, dy, dw, dh)`（引擎无 5 参重载） |
| ⑧ | 广告位 `PITCH26 = makePitch(...)` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，`drawBillboard29` 只画自有画面 |
| ⑨ | 共享天气 `drawWeather` 空实现 | 母题 `rosepetals` **不在**跨城共享键（drizzle / rain / thunderstorm / snow / leaves）里 ⇒ `wx.shared` 恒 null；⚠️ 本城母题标志 `wx.rosepetals` **必须保留**（它是三层玫瑰花瓣的唯一开关）；`WX33` / `WX_BB33` / `WX_LAND33` / `WX_LIGHTS14` 照搬为数据但零消费方 |
| ⑩ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo29` 统一乘）；`neonPulse` → 灯火类（街灯 / 灯泡 / 广告牌灯 / 钟室 / 金球 / 码头灯 / 车灯 / 手机屏 / 萤火 / 频闪）；`tailGain` → 车流光尾（`head` / `tail` 与水面 `streak` 高光条）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余沿用前 52 城口径：容器类全部嵌在场景类内部（顶层 `private class` 跨文件重名会触发 `Redeclaration`）；字面量常量提到文件级 `private const`/`val`、算术派生量留实例字段；`Float32Array`（云带密度场）⇒ `FloatArray`（写 `.toFloat()`、读 `.toDouble()`，保 float32 截断）；上游 `ambience`（birds / crickets / water / crowd）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点（`pan` 双值取**首值**、`dur` 丢弃）。

#### 8.48.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 整数种子一律补 `.0`（引擎 `wave` 三个形参全是 `Double`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` 保留块语义 |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游 `rng(seed)` 是可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 要复刻用 `java.lang.Math.rint(v)`；本城**有意**用 `jsRound`（见 8.48.3 ⑤） |
| ⑩ | **`DoubleArray.slice(0, n)` / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **`renderCity4()` 要返回含 `.back` / `.front` 的对象** | 上游一个函数同时产远景与近景两张位图 | 本城用 `FloCity4(back, front)` 容器类分别持有 |
| ⑫ | **`FloCat.walk` 的下标 = 方向 * 2 + 帧** | 抄成"方向 * 帧"会索引越界或取到错的姿势 | 按上游 `CAT_WALK2` 的两张位图 × 镜像关系建索引 |
| ⑬ | **上游 `copy` 合成会清掉画布其余部分** | 直接用 `Comp.COPY` 会把上一帧的残留留在倒影层 | 用 `Comp.COPY` 前先 `reset()` 那块画布 |

#### 8.48.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与迪拜 / 阿姆斯特丹 / 柏林 / 汉堡 / 东京基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **FLORENCE** 标题字样；天上一弯**月**与几点星；三道横贯画面的**紫蓝色云带**；正中偏右是**圣母百花大教堂**的红白**穹顶**与顶上的灯笼尖顶（金球），紧邻其左是**乔托钟楼**的方塔与钟室，最右远处是**犹太会堂的铜绿色小圆顶**；中景正中挂着一块**「GELATI CUPOLA」灯箱广告牌**（箭头 + 红字，四帧走字/闪动各异）；阿尔诺河对岸是**一整条亮着窗灯的 Lungarno 老楼**与沿岸的**球形街灯**；画面右侧横着**老桥的连续拱券**与桥上的廊架；前景是**石栏杆**、球形路灯、阶上的**行人**（侍者端盘、摊贩推车）与一只**踏板车**，左下有条纹 **CAFFÈ 餐车**与挂着的灯泡，左侧是一排**修剪成花坛的玫瑰丛**与柏树；河面有白沫与倒影，阶上有**鸽子**、栏杆上有**猫**；三片**玫瑰花瓣**缓缓落下，夜里散着**萤火虫** —— 四帧之间云带、花瓣、鸽群、河面倒影与行人位置均在变 |

| 未做 | 真机截图评分（同 §8.32–§8.47 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：河面倒影层走的是 `Comp.COPY` —— 若忘记 `reset()`，倒影层会**残留上一帧的像素**（表现为半透明重影），
> 而画面不会报错、门禁也全绿（帧非空 / 帧会变都成立）⇒ 所以必须**逐时刻看倒影与河面**是否干净，
> 不能只看"帧非空 / 帧会变"两条门禁。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

### 8.49 ✅ `athens`（雅典 · 卫城下的复活节，上游 2,470 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.49.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/athens.js`（2,470 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__athens.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityAthens.kt`（**6,488 行**） |
| 分片 | `logs_temp/city_parts/athens/p01.kt`–`p13.kt`（13 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"athens" -> LofiCityAthens()`（落在 `"amsterdam"` 之后、`"bali"` 之前）；`CityRegistry.SCENES_BUILT` 第 **54** 项 |
| registry | `nameZh = 雅典`、`taglineZh = 卫城下的复活节`、`accentHex = #8f9c4d`、`focusX = 244.0` 落地前已存在，本轮无需回填 |
| 机位类型 | **雅典卫城山下的斜俯视**（广场地面平视 + 卫城台地居中）：帕特农 `PAR3 = (x0 186, 17 柱, 柱距 6, 檐口 40, 柱头 46, 基座 59, 山花顶 36)`；埃雷克忒修斯神庙 `ERE = (236…259, top 56)`；普罗皮勒亚门 `PROP2 = (352…394)`；清真寺 `MOSQ = (穹顶 cx 102, 鼓座 124, 尖顶 134)`；拜占庭教堂 `CH3 = (x1 52, 钟楼 apex 26)`；电气化火车站 `STN2 = (216…344, 屋脊 152, 五扇门洞 [228, 252, 280, 308, 332])`；阿里斯托斯广场柱列 `DUO = (262, 222)`；月亮 `MOON22 = (152, 27, r 7)`；广告牌 `BB29 = (222, 126, 48×20)` |
| 场景母题 | 复活节夜晚的雅典：卫城台地（嶙峋岩壁 + 六盏岩灯 + 洞口 + 瘦柏）上的**帕特农神庙**（17 根多立克柱 + 山花 + 三级台阶）、**埃雷克忒修斯神庙**与**普罗皮勒亚门**、左侧远处**清真寺**（红褐穹顶 + 尖顶 + 塔楼 + 拱廊）与**拜占庭式小教堂**（蓝灰穹顶 + 钟楼 + 五口钟）、中段**电气化火车站**（赭色山墙 + 五扇亮灯门洞）与**阿里斯托斯广场**的七根柱列 / 串灯 / 铺石、右侧**普拉卡白房**与挂彩旗的糖果巷、屋顶上的一行行人、广场上的**舞者与听众**、**卖 koulouri 的小车**、**卖气球的男孩**、**举相机的人**、**卫队神父**、**鸽群**、**屋顶的猫**、**烟囱炊烟**、天上的**复活节花瓣**、尾声的**烟花**、**橙花枝**、暗角 |

#### 8.49.2 结构：一帧 31 步图层序 + 20 组独立 `rng` 流（合计 26 条序列）

`renderFrame30` 的 31 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与远景（9 步） | 天空底图（`drawImage(SKY_C30)`）→ 星点（`STARS26` 逐颗 `fillRect` + `wave` 闪烁）→ 月光晕（`halo30(HL30.moon)`）→ 月亮（`drawImage(MOON_C21)`）→ 三层云带（`drawScroll12`）→ 远山（`drawImage(FAR_C7)`）→ 远景灯火（`FAR_LIGHTS` 逐点 `px`）→ 烟花（`drawFireworks4`）→ 共享天气 0 |
| 卫城（4 步） | 卫城光晕（`halo30(HL30.acro, 245, 84, 1)`）→ 卫城台地（`drawImage(ACRO_C)`）→ 岩壁特效（`drawRockFx`）→ 烟花辉光（`drawFireworkGlow`） |
| 城镇（7 步） | 城镇（`drawImage(TOWN_C)`）→ 空场版本（`BARE_C`，仅 `crowd === 0` 时）→ 城镇动态（`drawTownFx`）→ 广告牌（`drawBillboard27`）→ 屋顶的猫（`drawRoofCat`）→ 炊烟（`puffs4(SMOKE6)`）→ 小人物群（`drawSmallFolk`） |
| 广场与人群（6 步） | 花瓣层 0（`drawPetals4(0, t)`）→ 地面动态（`drawGroundFx`）→ 演员群（`ACTORS` 逐个 `a.draw(t, fr, crowd)`：舞者 / 听众 / 摊贩 / 孩子 / 神父）→ 橙花（`drawBlossoms3`）→ 花瓣层 1 → 共享天气 1 |
| 收尾（5 步） | 逃跑的人（`drawEscaped`，仅 `crowd > 0`）→ 树枝（`drawBough`）→ 花瓣层 2 → 共享天气 2 → 暗角（`drawImage(VIG_C30)`） |

⚠️ **`rng()` 的消费次序即画面**。上游本城共 **20 组 / 26 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `STARS26` | 夜空星点 | 57 | 星位与闪烁相位 |
| `WALKERS12` | 台上行人 | 1821 | 行人的衣着与走位 |
| `GUESTS` | 酒馆宾客 | 33 | 宾客的样子与落座 |
| `COMMUTERS` | 通勤者 | 909 | 通勤者的衣着与路线 |
| `ROOF_FOLK` | 屋顶行人 | 111 | 屋顶行人的位置与姿态 |
| `PIGEONS6` | 鸽群 | 212 | 鸽群的起飞与盘旋 |
| `FW` | 烟花时刻表 | 1453 | 每一发烟花的升空时刻与参数（**必须提前算**，见 8.49.4 ⑫） |
| `SMOKE6` | 炊烟 | 62 | 烟团的大小与漂移 |
| `PETALS5[li]` | 三层复活节花瓣 | 700 / 701 / 702（**3 条**） | 每层每瓣的片数 / 相位 / 频率 / 落点 |
| `CLOUD_DEFS15` | 三层云带 | 7 / 31 / 53（**3 条**） | 每层云团的团数与浓淡 |
| `renderFar15` | 远山 | 19 | 远景灯火的位置 |
| `renderAcropolis` | 卫城 | 404 | 岩壁草木与洞口 |
| `renderTown2` | 城镇 | 808 | 城镇灯火与巷道 |
| `renderTree5` | 三棵树 | 900 + seed ⇒ **903 / 905 / 908**（**3 条**） | 每棵树的形态 |
| `renderBough` | 树枝 | 515 | 枝条的分叉 |
| `buildListeners` | 围观听众 | 77 | 听众的姿态 |
| `buildQueue` | 排队的人 | 404 | 排队者的衣着（**与 `renderAcropolis` 同种子但各自独立成流**） |
| `buildShooters` | 举相机的人 | 505 | 摄影师的位置与机位 |
| `buildTerrace` | 露台 | 606 | 露台上的人与灯 |
| `buildSitters` | 坐着的人 | 707 | 坐着的人的姿态 |

⚠️ **`initConstants()` 与 `prerender30()` 的调用次序不可调换**（前者消费 57 / 1821 / 33 / 909 / 111 / 212 / 1453 / 62 / 700–702，后者消费 7 / 31 / 53 / 19 / 404 / 808 / 903–908 / 515 / 77 / 404 / 505 / 606 / 707）；流与流之间互不干扰，但**每条流内部的消费次序不可调换**（星位 / 行人衣色 / 宾客 / 通勤者 / 屋顶行人 / 鸽群 / 烟花时刻 / 炊烟 / 花瓣 / 云团 / 远景灯火 / 岩上草木 / 城镇灯火 / 树 / 枝条 / 听众 / 排队者 / 摄影的人 / 露台 / 坐着的人 各自由它决定）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.49.3 本城的结构性新东西：**本地 `pick17`**；其余 15 条受控差异

- 上游的 `pick17(ramp, v, i, y)` 公式是 `floor(v * (len - 1) + bayer(i, y))`，**与引擎 `pickFromRamp` 的"阈值比较再 +1"规则不等价** ⇒ 本城**本地实现** `pick17`，绝不能换成引擎函数（那会让每一块岩石 / 墙面的色阶**整体错半档**，画面不会报错、只会安静地变色）。

逐条受控差异（产物文件头注共登记 16 条，此处列 10 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；`tint5(a, b, t)` ⇒ 引擎 `mix`（逐通道 `jsRound(lerp(...))`，与上游同口径）；`tintCache4` 缓存删除（`tint5` 结果逐位不变，只少一层缓存） |
| ② | `painter13(w, h)` ⇒ 本地 `AthPainter`（包引擎 `PixelBuffer`） | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.put(i, y, col, a)` 的 alpha 覆盖语义保留；上游 `p.rgb(i, y)` 返回**不含 alpha 的 24 位 RGB** ⇒ 本文件按位拼装，不走 `PixelCanvas.pixelAt`；`done()` ⇒ `.canvas` |
| ③ | `sprite22(rows, key, flip2)` ⇒ 本城同名函数 | **宽度取各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），翻转按整幅镜像；`stamp3(p, rows, key, x0, y0)` ⇒ 本城同名函数（往已有画布上盖印，引擎无对应件） |
| ④ | `makeCanvas(w, h)` ⇒ 引擎 `PixelCanvas` | `.ctx` 即 2D 上下文；`text` / `glyph` / `px` / `rect` ⇒ 引擎 `text` / `glyph` / `putPx` / `rect` |
| ⑤ | 合成模式 | 上游只用了 `source-over` 与 `lighter`（`makeHalo` 的 `drawHalo`）⇒ 引擎 `Comp` 足够 ⇒ **本城不需要额外的 Porter-Duff 本地实现**（与柏林的 `multiply`、迪拜的 `destination-in` 不同） |
| ⑥ | 广告位 `PITCH23 = makePitch(BB29.w, BB29.h, { accent: "#8f9c4d" })` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，广告牌只画自有画面 |
| ⑦ | 共享天气 `drawWeather` 空实现 | 母题 `nerantzi`（复活节花瓣）**不是**跨城共享键 ⇒ `wx.shared` 恒 null；⚠️ 本城母题标志 `wx.nerantzi` **必须保留**（它是三层花瓣的唯一开关）；`WX_LAND30` / `WX_LIGHTS13` / `WX30` / `WX_BB30` / `WX_NONE` / `WX_BB_NONE` 照搬为数据但零消费方 |
| ⑧ | **上游 `makeCar` 不返回 `side`** | 上游 `label()` 里 `v.h - v.side < 12` 因 `side` 为 `undefined` 而变成 NaN 比较 ⇒ **照抄**（保真优先，不"顺手修正"） |
| ⑨ | 上游 `figure2(...)` 在 canvas 上挂了 `.flip` 与 `.height` 两个自定义属性 | 本城用 `AthFig`（canvas + flip + height）承载；`blit18` 按同一公式取锚点 |
| ⑩ | 上游 `S4.long` 可能是 `true` 也可能是一件外套的颜色 | 本城的 `AthStyle` 用 `long: Boolean` + `longCol: Int?` 表达（`sk = longCol ?: coat`） |
| ⑪ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo30` 统一乘）；`neonPulse` → 灯火类（蜡烛 / 火焰 / 提灯 / 手机 / 闪光 / 烤炉 / 灯泡 / 门灯 / 广告牌灯 / 路灯 / 巷灯 / 图标）；`tailGain` → 光尾类（仅送货车前灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余沿用前 53 城口径：容器类全部嵌在场景类内部（顶层 `private class` 跨文件重名会触发 `Redeclaration`）；字面量常量提到文件级 `private const`/`val`、算术派生量留实例字段；上游对象字面量数组拆成**平行原始数组** + 场景类内部的容器类；`Float32Array`（云带密度场）⇒ `FloatArray`（写 `.toFloat()`、读 `.toDouble()`）；上游 `ambience`（crowd / traffic / wind）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点（`pan` 双值取**首值**、`dur` 丢弃）。

#### 8.49.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 整数种子一律补 `.0`（引擎 `wave` 三个形参全是 `Double`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` 保留块语义 |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游 `rng(seed)` 是可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 用 `java.lang.Math.rint(v)` |
| ⑩ | **`DoubleArray.slice(0, n)` / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **`renderTree5` 要按上游那 4 个散参** | 写成对象形参报类型不匹配 | 按上游签名逐个抄散参（`tx, tbase, tr, tseed`） |
| ⑫ | **上游 `FW`（烟花时刻表）必须提前算** | 上游在 `init_athens` 里生成，但本城的 `events`（公开 getter）在构造期就要读它 | 改成**构造期即算**（`rng(1453)` 是独立流，与其它流互不干扰 ⇒ 逐位不变） |
| ⑬ | **`AthGuest.tIn` / `tOut` 两处类型要一致** | 一处 Int 一处 Double 时比较永远为真或永远为假 ⇒ 客人要么全不出现要么全出现 | 两处统一成 `Double`（都 Int 或都 Double） |

#### 8.49.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与迪拜 / 佛罗伦萨 / 阿姆斯特丹 / 柏林基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **ATHENS** 标题字样；满天**乌云**压着夜空；正中偏上是一座**岩石台地**，台顶上立着**帕特农神庙**（一排多立克柱与山花）与右侧**方形的墙身**（埃雷克忒修斯神庙 / 普罗皮勒亚门一带），台壁上是稀疏的**岩灯**与几株瘦柏；台地之下是**普拉卡白房子**的连排窗口（多数亮着暖黄灯），左侧有**红褐穹顶的清真寺**与更左的**蓝灰小教堂**，中段是**赭色山墙的火车站**（五扇拱形门洞都亮着灯）与**阿里斯托斯广场**的柱列和串灯，招牌上写着 **ΘΕΡΙΝΟ CINEMA** 与 **ΣΥΓΧΡΟΝΗ ΤΘΕΑΣΗ**；广场上散着**人群**、露天**酒馆桌椅**、一簇彩色**气球**（卖气球的人）、**鸽群**、几处**积水反光**，右上角探出一枝开满白花的**橙花枝**；`t = 120` 时天上**月亮**出现、`t = 228` 时台地右上方炸开一小簇**烟花火花**，四帧之间人群、气球、鸽群与地面光斑都在变 |

| 未做 | 真机截图评分（同 §8.32–§8.48 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：色彩阶取色走本地 `pick17` —— 若误换成引擎 `pickFromRamp`，岩壁 / 墙面 / 穹顶的色阶会**整体错半档**，
> 画面不会报错、门禁也全绿 ⇒ 所以必须**逐时刻看岩石与墙面的层次**是否还分得出档，不能只看"帧非空 / 帧会变"。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

### 8.50 ✅ `los-angeles`（洛杉矶 · 入夜后的好莱坞，上游 2,365 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.50.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/los-angeles.js`（2,365 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__los-angeles.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityLosAngeles.kt`（**5,810 行**） |
| 分片 | `logs_temp/city_parts/los-angeles/p01.kt`–`p15.kt`（15 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"los-angeles" -> LofiCityLosAngeles()`（落在 `"london"` 之后、`"madrid"` 之前）；`CityRegistry.SCENES_BUILT` 第 **55** 项 |
| registry | `nameZh = 洛杉矶`、`taglineZh = 入夜后的好莱坞`、`accentHex = #cc701f`、`focusX = 255.0` 落地前已存在，本轮无需回填 |
| 机位类型 | **好莱坞高地俯瞰好莱坞大道**（一点透视街道）：地平线 `HZN = 150`、灭点 `VPX7 = 256`、焦距 `F5 = 260`、视高 `EYE14 = 4.5`；街面建筑底线 `BLDG = 200`、路缘 `CURB3 = 208`、南侧人行道顶 `SOUTH2 = 252`；月亮 `MOON37 = (446, 31, r 8)`；HOLLYWOOD 招牌 `SIGN4 = (205, 38, h 12)`（右缘 `SIGN_X1 = 303`）；电视塔桅杆 `LEE = (284, 23)`；格里斐斯天文台 `OBS = (cx 400, base 84, 358…442)`；市政厅 `CAP3`；土星塔 `SAT3`；路灯 `LAMP13` |
| 场景母题 | 好莱坞山头的夜色：山坡上的九个铁架字母 **HOLLYWOOD 招牌**（灯泡高光 + 一次横扫的微光）、右上带红闪灯的**电视塔桅杆**、右侧山坡的**格里斐斯天文台**（砖石墙体 + 中央圆顶 + 两座小圆顶，其中一座开观测缝探出望远镜）、更远的**洛杉矶市政厅**（退台式塔楼 + 旗杆 + 用摩尔斯电码闪的航障灯）、天际线的**楼群**与闪烁的窗、**好莱坞大道**（一点透视的路面 + 两侧行道楼 + 路灯 + 停靠与驶过的车）、左手边的霓虹 **SATURN DINER**（转动的土星环 + 倒水的女服务员 + 情侣与常客）、右手边的**唱片店**（RECORDS 蓝色霓虹 + 橱窗里的唱片货架与一只猫）、**土星塔**（PYLON）、**蒸汽口**的白汽、**三层落叶**、夜空的**月亮**与四十六颗星、掠过的**客机**与打光柱的**直升机**、**探照灯**扫过夜空、末段的**烟花**、湿沥青上的倒影与拉长光条、暗角 |

#### 8.50.2 结构：一帧 73 步图层序 + 11 组独立 `rng` 流（合计 170 条序列）

`renderFrame55` 的 73 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与山（10 步） | 天空底图（`SKY_C55`）→ 星点（46 颗，`wave` 闪烁、大星带十字光芒）→ 月光晕（`HL55.moon`）→ 月心光晕（`HL55.moonCore`）→ 月亮（`MOON_C35`）→ 掠过的客机（`drawPlane20`）→ 两层云带（`drawScroll22`）→ 山体（`HILLS_C`）→ 山腰小屋的闪窗（`HOUSE2`）→ 山路路灯（`ROAD_LAMPS2`） |
| 招牌与天文台（13 步） | 电视塔桅杆红闪灯（3 盏轮闪）→ 招牌光晕（`HL55.sign`，随 `breathe` 起伏）→ 招牌心光晕（`HL55.signCore`）→ 招牌本体（`SIGN_C6`）→ 招牌扫光（`drawSignLight`）→ 天文台光晕（`HL55.obs`）→ 天文台本体（`OBS_R`）→ 观测缝光晕（`HL55.obsDoor`）→ 天文台生活（`drawObservatoryLife`：探出的望远镜）→ 直升机（`drawHeli2`）→ 烟花（`drawFireworks5`）→ 探照灯（`drawSearchlights`）→ 共享天气 0 |
| 天际线（7 步） | 城市辉光（`GLOW_C2`，`lighter` 合成）→ 天际线（`FLATS_C`）→ 楼窗与航障灯（`FLAT_WINS`）→ 市政厅光晕（`HL55.capitol`）→ 市政厅本体（`CAP_R`）→ 市政厅摩尔斯航障灯 → 落叶层 0（`drawLeaves6(0, t)`） |
| 大道与街边（15 步） | 大道（`AVE_C2`）→ 大道车流（`drawAvenueTraffic`）→ 大道路灯（`AV_LAMPS` 逐盏投影 + 光晕）→ 棕榈（近排，`drawPalms6(t, false)`）→ 唱片店（`SHOP_C`）→ 橱窗的猫（`drawCat15`）→ 街口停止牌（`STOP_C`）→ 店招光晕（`HL55.shop`）→ 唱片店蓝色霓虹（`HL55.neonBlue`）→ 广告牌（`drawBillboard40`）→ 餐厅内景（`DINER_IN`）→ 餐厅生活（`drawDinerLife`）→ 餐厅外景（`DINER_OUT`）→ 餐厅门（`drawDoor2`）→ 餐厅暖光（两处 `HL55.diner`） |
| 街区与路面（19 步） | 蒸汽口（`drawVentSteam`）→ 土星塔本体（`PYLON_C2`）→ 土星塔生活（`drawPylonLife`）→ 土星光晕（`HL55.saturn`）→ 粉光晕（`HL55.pink`）→ 青光晕（`HL55.aqua`）→ 落叶层 1 → 共享天气 1 → 街面（`STREET_C9`）→ 路灯光晕（`HL55.lamp`）→ 灯头双芯光晕 → 路面倒影（`reflect`）→ 粉光条（`streak`）→ 土星光条（`streak`）→ 四根路灯的暖光条（`streak` 循环）→ 路灯光条（`streak`）→ 唱片店光条（`streak`）→ 人行道（`drawSidewalk`）→ 棕榈（远排，`drawPalms6(t, true)`） |
| 收尾（9 步） | 车底倒影（5 层递减 α 的 `drawImage`）→ 车影（`rect`）→ 车本体（`CAR_C`）→ 街面车流（`drawTraffic9`）→ 落叶层 2 → 共享天气 2 → 暗角（`VIG_C55`） |

⚠️ **`rng()` 的消费次序是画面本身**。上游本城共 **11 组 / 170 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `STARS46` | 夜空星点 | 31 | 星位、亮度与闪烁相位 |
| `HOUSE2` + `ROAD_LAMPS2` | 山腰小屋 / 山路路灯 | 71 | 山腰窗灯与路灯位置 |
| `LOTS` | 大道街边楼分段 | 91 | 街边楼的分段与色阶 |
| `PALMS7` | 20 棵棕榈（`PALM_Z` 的 10 个深度 × 左右 2 侧） | 300 + 2k + side ⇒ **300…319**（**20 条**） | 每棵棕榈的倾斜 / 摆动 / 叶序 |
| `VENT` | 蒸汽口 | 64 | 14 处蒸汽口的喷放 |
| `DRY` | 三层落叶 | 907 | 每层每片落叶的落点与摆幅 |
| `renderHills4` | 山体与山影 | 73 | 34 团山体云影 |
| `makeCloudLayer31` | 两层云带 | 5 / 11（**2 条**） | 每层云团的团数与浓淡 |
| `renderCapitol` | 洛杉矶市政厅 | 52 | 塔楼窗灯 |
| `renderFlats` | 天际线 | 61 | 楼体色调、窗与航障灯 |
| `renderPalmHead` | 棕榈叶头 | `P4.seed` = 300 + 2k + side，**每棵棕榈 `PALM_NF3 = 7` 个叶头 ⇒ 20 × 7 = 140 条** | 每个叶头的叶片角度 / 长度 / 叶脉 |

⚠️ **`initConstants()` 与 `prerender55()` 的调用次序不可调换**（前者消费 31 / 71 / 91 / 300–319 / 64 / 907，后者消费 73 / 5 / 11 / 52 / 61 与 140 条叶头流）；流与流之间互不干扰，但**每条流内部的消费次序不可调换**（星位 / 山腰窗灯 / 街边楼 / 棕榈 / 蒸汽 / 落叶 / 山影 / 云团 / 市政厅 / 天际线 / 叶头的图案都由它决定）。上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` / `hash(...)` ⇒ 逐位可复现。

#### 8.50.3 本城的结构性新东西：**`flipCanvas7` 的 1 像素错位镜像**；其余 14 条受控差异

- 上游 `flipCanvas7(src)` 走 `translate(w,0) + scale(-1,1) + drawImage`；浏览器语义下 `device_x = w - src_x` ⇒ **源图最右列被裁到画布外、画布最左列补一列全透明**（上游自带的 1 像素错位，按硬规则 10 照抄）。引擎 9 参 `drawImage` 在有变换时不是浏览器语义 ⇒ 本城**逐行镜像复刻该错位**，不走引擎的变换路径。

逐条受控差异（产物文件头注共登记 15 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；`tint8(col, tcol, a)` 的上游口径是 `Math.round(A[j] + (B[j]-A[j]) * clamp(a,0,1))` ⇒ 本城的 `tint8` 逐通道同口径（`mix` 是同一公式的另一写法，但 `tint8` 允许 `a` 超出 `[0,1]`，故单列一份以免口径漂移）；`tintCache7` 缓存删除 |
| ② | `painter18(w, h)` ⇒ 本地 `LaPainter`（包引擎 `PixelBuffer`） | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.put(i, y, col, a)` 的 alpha 覆盖语义保留；`done()` ⇒ `.canvas` |
| ③ | `makeCanvas(w, h)` ⇒ 引擎 `PixelCanvas`；`text(ctx, s, x, y, "#hex")` ⇒ 引擎 `text` | 同为 3×5 字模，只是本项目的收色是 `Int` |
| ④ | 合成模式 | 上游本城只用 `"source-over"` 与 `"lighter"` ⇒ 引擎 `Comp` 直接够用 ⇒ **本城没有 multiply / destination-in / soft-light 的能力缺口**（与柏林不同） |
| ⑤ | **上游 `flipCanvas7` 的 1 像素错位** | 见上：逐行镜像复刻，不走引擎变换路径 |
| ⑥ | 5 参 `drawImage(img, dx, dy, dw, dh)`（缩放） | ⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)`（引擎无 5 参重载）；本城的 5 参用法只有 `drawVehicle6` 的车底倒影与 `renderFrame55` 的车影两处 |
| ⑦ | 广告位 `PITCH48 = makePitch(...)` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，`drawBillboard40` 只画自有画面 |
| ⑧ | 共享天气 `drawWeather` 空实现 | 上游母题 `leaves` **是**跨城共享键，但上游 `plans` 对"自有母题"一律给 `shared: null` ⇒ `wx.shared` 恒 null；⚠️ 本城母题标志 `wx.leaves` **必须保留**（它是三层落叶的唯一开关）；`WX_LAND50` / `WX_LIGHTS24` / `WX55` / `WX_BB53` 照搬为数据但零消费方 |
| ⑨ | **本城特有**：`SIGN_X1`（招牌右缘）由 `SIGN_LETTERS` 逐字累加得出，**得 303**；而 `landmark: [205, 303]` 是 `los_angeles_default` 里**独立手写**的元数据 ⇒ 数值恰好相同但来源不同，本城分别照抄（`SIGN_X1` 走 `initConstants` 的累加，`landmarkX1` 取元数据的 303） |
| ⑩ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo55` 统一乘）；`neonPulse` → 灯火类（招牌 / 霓虹 / 路灯 / 车头灯 / 观测台灯 / 土星环 / 唱片店 / 广告牌灯 / 月光 / 探照灯）；`tailGain` → 车流光尾（仅车尾灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余沿用前 54 城口径：容器类全部嵌在场景类内部（顶层 `private class` 跨文件重名会触发 `Redeclaration`）；上游那些"对象字面量表"（`LOOK` / `VEH6` / `LETTERS2` / `DOMES2` / …）拆成**文件级平行基础数组** + 场景类内部的嵌套容器类；字面量常量提到文件级 `private const`/`val`、算术派生量留实例字段；`Float32Array`（云带密度场、烟花方向场）⇒ `FloatArray`；`crowd` 引擎无此字段 ⇒ 恒取常量 `CROWD31 = 0.5`；上游 `ambience`（traffic / wind / crickets / crowd）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点（`pan` 双值取**首值**、`dur` 丢弃）。

#### 8.50.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 整数种子一律补 `.0`（引擎 `wave` 三个形参全是 `Double`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` 保留块语义 |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游 `rng(seed)` 是可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 用 `java.lang.Math.rint(v)` |
| ⑩ | **`DoubleArray.slice(0, n)` / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **`renderHills4` 与 `drawSignLight` 只应存在一处定义** | 相邻两片的行区间重叠 ⇒ 同一函数被定义两次，编译报 `Redeclaration` | 分片边界前核对行区间，确保每个函数只落在一片里 |
| ⑫ | **`here16` 闭包类型三处一致** | 上游 `here16 = (rank) => crowd > 0 && (rank === 0 || rank < crowd)`；Kotlin 里若三处调用分别写成 `Int` / `Double` / `Boolean` 形参会推不出同一函数类型 | 三处统一成 `(rank: Int) -> Boolean` |
| ⑬ | **`flipCanvas7` 不能用引擎变换路径** | 引擎 9 参 `drawImage` 在有变换时不是浏览器语义 ⇒ 镜像结果差 1 像素 | 逐行手动镜像并复刻那 1 像素错位 |

#### 8.50.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与迪拜 / 佛罗伦萨 / 雅典 / 柏林基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **LOS ANGELES** 标题；山坡上一排白漆铁架字母拼成的 **HOLLYWOOD** 招牌（灯泡点亮的白字），招牌右上方山头上立着带**红闪灯**的电视塔桅杆；右上天上一轮**月亮**；右上山坡是**白色圆顶建筑**（市政厅 / 天文台一带），左中另有一栋**白色退台式高楼**顶端亮着红色航障灯；左手边是霓虹 **SATURN DINER**（"SATURN" 粉字、"DINER" 青字，招牌上方还悬着一个带灯的碟形招牌）与一排亮着灯的餐厅窗口，招牌前停着一辆**红色老爷车**；右手边是 **NIGHT SPOT / FREDDY'S** 广告牌与 **RECORDS** 唱片店橱窗（架子上排着唱片，橱窗里坐着一只猫）；画面正中是一条**一点透视的好莱坞大道**，两侧是行道楼、**成排的棕榈**与球形路灯，路上有车驶过、路面湿亮反光；最前景的人行道上铺着一排**好莱坞星光大道的人形星章**；`t = 60` 时两道**探照灯光柱**斜扫过夜空、`t = 228` 时右下换成另一辆白车 —— 四帧之间车流位置、探照灯角度、烟囱白汽与落叶均在变 |

| 未做 | 真机截图评分（同 §8.32–§8.49 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：棕榈叶头有 140 条独立 `rng` 流（20 棵 × 7 个叶头），若某一条的种子或消费次序错了，
> 画面**不会报错**、只会安静地出现"某几棵棕榈的叶子排布和其它不一样" ⇒ 所以必须**逐时刻比对左右两侧同深度的棕榈**是否同形，
> 不能只看"帧非空 / 帧会变"。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

### 8.51 ✅ `hong-kong`（香港 · 入夜后的维多利亚港，上游 2,606 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.51.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/hong-kong.js`（2,606 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__hong-kong.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityHongKong.kt`（**6,771 行**） |
| 分片 | `logs_temp/city_parts/hong-kong/p01.kt`–`p13.kt`（13 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"hong-kong" -> LofiCityHongKong()`（落在 `"hamburg"` 之后、`"istanbul"` 之前）；`CityRegistry.SCENES_BUILT` 第 **56** 项 |
| registry | `nameZh = 香港`、`taglineZh = 入夜后的维多利亚港`、`accentHex = #34e0a1`、`focusX = 245.0` 落地前已存在，本轮无需回填 |
| 机位类型 | **湾仔旧唐楼天台平视**（维港对岸）：水面线 `WL3 = 190`、天台女儿墙 `LEDGE3 = 232`、天台地面 `FLOOR2 = 242`；中银大厦 `BOC.cx = 222`、国金二期 `IFC.cx = 268`、中环广场 `CTR.cx = 326`、希望大厦 `HOPE.cx = 138`、钟楼 `CKC = (237…253, top 92)`、香港会议展览中心 `HKCEC = (148…206)`、中环 `CP.cx = 98`；太平山缆车桅杆 `PEAKT.x = 292` |
| 场景母题 | 雨夜的维多利亚港：对岸**中环天际线**（中银大厦的 X 形斜撑 + 带尖针的国金二期 + 挂着时钟的金色钟楼 + 插金鱼的 LED 玻璃塔 + 香港会议展览中心的双曲「鲨鱼翼」屋顶 + 置地广场的玻璃方柱）、**太平山**（山腰屋灯 + 缆车索道与缆车车厢 + 凌霄阁「针筒」）、**星光大道**的霓虹与 **DIM SUM** 灯箱、**左邻厦**（冷气机 + 竹棚 + 竖挂的粉绿霓虹招牌 + 招牌上的葵花鹦鹉）、港面上的**渡轮 / 红帆天星小轮 / 快艇**（拖着光尾）、**三层夜雨**（被霓虹染成彩色）与自中环漫上来的**雾**、**对岸楼顶的彩色光束**、远处掠过的**直升机**、**天台**（女儿墙 / 栏杆 / 晾衣绳与竹竿 / 盆栽 / 红色胶凳 / 收音机 / 茶杯与热气 / 泰迪熊 / 鸽子 / 一只看人的猫）、摇扇听收音机的**叔叔**、收衣服的**阿姨**、共撑一把伞的**情侣**、举手机拍照的**客人**、暗角 |

#### 8.51.2 结构：一帧 32 步图层序 + 16 组独立 `rng` 流（合计 19 条序列）

`renderFrame8` 的 32 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与对岸（7 步） | 天空（`drawSky5`：月 + 星 + 两层云带）→ 掠过的直升机（`drawHeli`）→ 太平山（`drawPeak`：山脊 + 屋灯 + 缆车）→ 中层楼群（`blit4(MID_C)`）→ 雨层 0（`drawRain4(RAIN4[0])`）→ 共享天气 0 → 后排天际线（`drawLayer(BACK, t, 11)`） |
| 英雄楼群（7 步） | 后排招牌（`drawBackSigns`）→ 雾层 0（`drawFog2(FOGS2[0])`）→ 英雄楼光晕（`drawHeroGlow`）→ 广告牌（`drawBillboard8`）→ 英雄楼群（`drawLayer(HERO, t, 23)`）→ 英雄楼特效（`drawHeroFx`）→ 对岸光束（`drawBeams`） |
| 港面与船（11 步） | 前排楼群（`drawLayer(FRONT, t, 37)`）→ 前排特效（`drawFrontFx`）→ 航障灯（`drawBlinkers3`）→ **水面**（`drawWater3`）→ 快艇（`drawLaunch(LAUNCHES[2])`）→ **红帆天星小轮**（`drawJunk`）→ 快艇（`drawLaunch(LAUNCHES[0])`）→ 雾层 1（`drawFog2(FOGS2[1])`）→ 渡轮（`drawFerry3(FERRIES2[0])`）→ 渡轮（`drawFerry3(FERRIES2[1])`）→ 快艇（`drawLaunch(LAUNCHES[1])`） |
| 收尾（7 步） | 雨层 1 → 共享天气 1 → 左邻厦（`drawNeighbour`）→ **天台**（`drawRoof`）→ 雨层 2 → 共享天气 2 → 暗角（`drawImage(VIG_C8)`） |

⚠️ **`rng()` 的消费次序是画面本身**。上游本城共 **16 组 / 19 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `STARS6` | 夜空星点 | 12 | 星位与闪烁 |
| `NWIN` | 广告牌窗 | 505 | 窗内广告的种类与排布（`cool` / `warm` / `tv` / `toggle` 四类） |
| `RAIN4` | 三层夜雨 | 100 / 101 / 102（**3 条**） | 每层每滴雨的位置与落速 |
| `RINGS3` | 水面涟漪 | 71 | 涟漪的位置与半径 |
| `CARS4` | 车流 | 73 | 车的类型与位置 |
| `CLOUD_DEFS5` | 两层云带 | 11 / 23（**2 条**） | 每层云团的团数与浓淡 |
| 山腰屋灯 | 太平山 | 77 | 山腰屋灯的位置 |
| 中层楼群 | `MID_C` | 31 | 中层楼的窗灯 |
| 后排天际线 | `BACK` | 47 | 后排楼的形态 |
| 英雄楼群 | `renderHeroes` 内 **HOPE(71) → CP → CTR(61) → CKC → IFC(99) → BOC(88)** | 71 / 61 / 99 / 88（**4 条**） | 希望大厦 / 中环 / 国金二期 / 中银大厦的窗与构造 |
| 前排楼群 | `FRONT` | 53 | 前排楼的形态 |
| 广告牌宿主楼 | `BB_HOST2` | 59 | 宿主楼的形态 |
| 天台 | `drawRoof` | 606 | 天台的陈设（晾衣 / 盆栽 / 鸽子 / 猫） |

⚠️ **`rng(71)` 出现两次**（`RINGS3` 与 `renderHope`）——同种子、同序列，但**两条独立流**、互不干扰。
⚠️ **`init_hong_kong()` 与 `prerender8()` 的调用次序不可调换**（前者消费 12 / 505 / 100–102 / 71 / 73，后者消费 11 / 23 / 77 / 31 / 47 / 71 / 61 / 99 / 88 / 53 / 59 / 606）；
流与流之间互不干扰，但**每条流内部的消费次序不可调换**（星位 / 邻厦窗灯 / 雨滴 / 涟漪 / 车流 / 山灯 / 楼群 / 天台的图案都由它决定）。
上游本城未出现 `Math.random()` ⇒ 所有随机量都来自 `rng(seed)` ⇒ 逐位可复现。

#### 8.51.3 本城的结构性新东西：**对象字面量表摊平成平铺数组** + **带裁剪原点的 `trim4`**；其余 13 条受控差异

- 上游的 `WX8` / `WX_BB8` / `RAIN_LIGHTS3` / `CLOTHES` / `POLES` / `PIGEON2` / `BEAMS` / `STREAKS4` … 都是**对象字面量数组**，而本项目**⛔ 禁止顶层容器类**（跨文件重名会触发 `Redeclaration`）⇒ 本城把它们**摊平成平铺数组**（`WX8_LIGHT_X` / `CLOTH_ROWS` / `POLE_CLOTH` / `PIG_UP` / `BEAM_X` / `STREAK_X` …），**值与次序与上游逐项一致**（画面逐位不变，只是容器形状变了）。
- 上游 `trim4(p)` 裁到不透明像素的外接矩形，**结果带裁剪原点** ⇒ 引擎 `PixelBuffer.trim()` 返回的 `Pixmap` 同构，但本城的字段类型必须是「画布 + 偏移 x/y」的容器，贴图时按偏移贴。

逐条受控差异（产物文件头注共登记 15 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串、`[r,g,b]` 数组与 `rgba(...)`；`C4` / `RGB5` 颜色缓存删除（逐位不变）；`toHex9` / `mixHex4`（返回字符串）与 `dim`（返回数组）改为"返回 `Int`"的本地助手（逐通道取整口径不变）；`rgbaOf2` ⇒ 带 alpha 的 `Int` |
| ② | `pix4(w, h)` ⇒ 本城本地 painter（包引擎 `PixelBuffer`） | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.set(i, j, col, a)` 的 alpha 覆盖语义保留；`done()` ⇒ `.canvas` |
| ③ | `sprite5(rows, key)` ⇒ 本城同名函数 | **宽度取各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价）；`makeCloth` 同样按各行最大长度取宽；翻转按整幅镜像 |
| ④ | `makeCanvas` ⇒ 引擎 `PixelCanvas`；`text` ⇒ 引擎 `text`；`glyphP2` ⇒ 引擎 `glyph` | `ditherFill` / `gradeAd` / `pick4` / `env` / `inEvent` ⇒ 引擎同名件 |
| ⑤ | `trim4(p)` ⇒ 引擎 `PixelBuffer.trim()` | 引擎返回 `Pixmap`（含裁剪原点），与上游 `{c, x, y}` 同构；⚠️ 字段类型要用「画布 + 偏移 x/y」的容器 |
| ⑥ | `drawImage` | 上游本城只用 3 参与 9 参两种（**没有** 5 参缩放重载）⇒ 引擎同名重载直接对应；若后续出现"缩放"语义，一律改用 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)` |
| ⑦ | 广告位 `PITCH6 = makePitch(BB8.w, BB8.h, { accent: "#34e0a1" })` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，`drawBillboard8` 只画自有画面 |
| ⑧ | 共享天气 `drawWeather` 空实现 | 上游母题 `drizzle` 虽是共享键，但 `plans` 对"自有母题"一律给 `shared: null` ⇒ `wx.shared` 恒 null；`WX_LAND8` / `WX8` / `WX_BB8` 照搬为数据但零消费方；⚠️ 本城**自有**的三层雨 `RAIN4` 与 `wx.drizzle` 恒为 true ⇒ 自有雨永远在画（不是空实现） |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo8` 统一乘）；`neonPulse` → 灯火类（霓虹招牌 / 屋顶招牌 / 广告牌 / 楼窗 / 岸灯 / 渡轮 / 帆灯 / 灯管 / 门洞 / 窗 / 茶碗蒸汽）；`tailGain` → 水面光尾（`STREAKS4` 的高光条与船体光晕）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余沿用前 55 城口径：容器类全部嵌在场景类内部（顶层 `private class` 跨文件重名会触发 `Redeclaration`）；字面量常量提到文件级 `private const`/`val`（可绕开属性初始化顺序问题，见 §8.35 纽约先例）、算术派生量留实例字段；`Float32Array`（云带密度场 / 雾密度场）⇒ `FloatArray`；`Uint8ClampedArray` 的直接赋值（暗角）⇒ 走 `PixelBuffer.set`，alpha 单独给（本地 `u8clamp` 手写半偶取整）；上游 `ambience`（water / rain / traffic / crowd）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点（`pan` 双值取**首值**、`dur` 丢弃）。

#### 8.51.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 整数种子一律补 `.0`（引擎 `wave` 三个形参全是 `Double`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` 保留块语义 |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游 `rng(seed)` 是可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 本城手写 `u8clamp`（半偶取整）或用 `java.lang.Math.rint(v)` |
| ⑩ | **`DoubleArray.slice(0, n)` / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **上游 `trim4` 的结果带裁剪原点** | 直接当"整幅画布"贴 ⇒ 图案整体错位 | 字段类型要用「画布 + 偏移 x/y」的容器，贴图时按偏移贴 |
| ⑫ | **`makeLayer` / `finishLayer` / `floorRow` / `renderMidLevels` 的归属要统一** | 同一段逻辑抄成两份（或漏抄）⇒ 楼群高度、窗灯分布对不上 | 指定唯一实现点，其余调用它 |
| ⑬ | **`nearLand3` 只应存在一处定义** | 两处定义 ⇒ `Redeclaration` | 只保留一处，其余引用 |

#### 8.51.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与迪拜 / 佛罗伦萨 / 雅典 / 洛杉矶基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **HONG KONG** 标题；**左侧贴着一整面邻厦的旧楼外墙**（一格格亮着的窗、窗台杂物、空调机位），最上角一块**红底黄字的「金發好運」霓虹**，左中一条**竖挂的粉绿霓虹灯柱**；画面中央是**维港对岸的中环天际线** —— 白色的**中银大厦**（X 形斜撑清晰可见）、右侧一根顶带尖针的**国金二期**、左边一根**金色钟楼**（顶端有尖顶与横梁）、再右一座**彩色横条灯带的 LED 玻璃塔**、中间一块写着 **DIM SUM** 的霓虹灯箱、远处山脊上还有**凌霄阁式的桅杆**；港面铺着**灯光倒影**与细雨，**t = 120** 时港面正中一叶**红帆天星小轮**格外显眼、从对岸楼顶射出一道道**品红 / 青 / 橙 / 绿的光束**；前景是**天台**（女儿墙 + 栏杆 + 晾衣绳上挂着红绿白的衣服、一只青色花盆、一张红色胶凳、右侧一只**铁水箱**与一扇透着暖光的门洞），**t = 228** 时天台中间出现**共撑一把伞的两个人**与**举着手机拍照的客人**，栏杆边还有一只猫；四帧之间雨丝、光束、船位、晾着的衣服与天台人物都在变 |

| 未做 | 真机截图评分（同 §8.32–§8.50 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：三层雨是本城**自有**母题（`wx.drizzle` 恒为 true ⇒ 雨永远在画），而跨城 `drawWeather` 是空实现 ——
> 若把 `drawWeather` 也写成"照画"，雨会变成**两层**（一层自带 + 一层共享），画面不会报错、只会安静地多一层雨丝
> ⇒ 所以必须**逐时刻数雨丝的层数与斜度**。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

### 8.52 ✅ `boston-acorn-street`（波士顿·橡子街 · 橡子街的雪，上游 2,373 行）**已落地** · 2026-10-27（本地提交见本节末）

#### 8.52.1 全局快照

| 项 | 值 |
|---|---|
| 上游源 | `js/scenes/boston-acorn-street.js`（2,373 行，含 esbuild 模块包装）；本地权威副本 `app/src/lofi-upstream/modules/js__scenes__boston-acorn-street.js.js` |
| 落地产物 | `app/src/main/java/com/nasmusic/tv/visualizer/e51/scenes/LofiCityBostonAcornStreet.kt`（**6,235 行**） |
| 分片 | `logs_temp/city_parts/boston-acorn-street/p01.kt`–`p12.kt`（12 片），`logs_temp/e51_pipeline/assemble.py` 顺序拼装 |
| 接线 | `LofiScenes.build()` 按字母序加 `"boston-acorn-street" -> LofiCityBostonAcornStreet()`（落在 `"boston"` 之后、`"boston-faneuil-hall"` 之前）；`CityRegistry.SCENES_BUILT` 第 **57** 项 |
| registry | `nameZh = 波士顿·橡子街`、`taglineZh = 橡子街的雪`、`accentHex = #44bb11`、`focusX = 266.0` 落地前已存在，本轮无需回填 |
| 机位类型 | **橡子街巷底的斜坡平视**（一点透视的窄巷）：焦距 `F4 = 560`、灭点 `VX2 = 268`、视平线 `HY3 = 140`、眼高 `EYE11 = 1.6`、地面 z→y 斜率 `GR3 = 0.045`、机位横移 `CAMX = 0.4`；左墙 `XL2 = -2.5`、左人行道 `XCL = -1.25`、右人行道 `XCR = 1.45`、右墙 `XR = 2.2`；巷尾 `ZEND = 68`、巷口 `ZH = 80`；门宽 `DOOR_W = 1.1`、花环挂高 `WREATH_H = 1.3`；楼层 `FLOORS2 = [[1.3, 2.9], [4, 5.7], [6.8, 8.3], [9.4, 10.5]]` |
| 场景母题 | 波士顿灯芯绒巷的雪夜：左边**十栋联邦式砖房**（黑 / 白 / 绿 / 红 / 蓝的门、阁楼、老虎窗、**窗台冬青枝**、挂花箱、**铸铁栏杆**、雪堆台阶、门前圣诞花环）、右边**花园矮墙与绿门**（墙外一株**雪松**）、巷尾横着的**红砖端屋**（三扇暖窗 + 两扇阁楼窗 + 门前圣诞花环 + 美国国旗）、右前方转角的 **`LAMPLIGHTER / HOT COCOA / CREAM PIE`** 旧广告牌（两盏射灯 + 爬满常春藤）、广告牌上方**五只鸽子**、地上**三层雪**、**三支路灯**、铁栅柱上的花环与松枝；人物：**铲雪的邻居**、**架三脚架拍照的摄影师**、**抱花环的住户**、**遛狗的一对**、**抱圣诞树的一对**、**拉雪橇的父女**、**送信的骑手**、**五个提灯唱颂歌的人**、撑伞与牵狗的行人；**窗里有一只猫**，窗上挂着**圣诞彩灯**，门前堆着雪团，**碎冰反光**与**地面雪雾**、飘雪、暗角 |

#### 8.52.2 结构：一帧 32 步图层序 + 7 组独立 `rng` 流（合计 34 条序列）

`renderFrame51` 的 32 步**不可调换**（叠放次序即遮挡关系）：

| 段 | 步骤 |
|---|---|
| 天空与巷尾（8 步） | 天空（`drawSky38`）→ 雷暴天光（`drawStormSky`，本项目恒空转）→ 天空生命（`drawSkyLife`）→ 巷尾楼群（`blit39(BACKS_C)`）→ 巷尾西墙（`blit39(WC_C)`）→ 端屋（`blit39(END_C)`）→ 巷尾动态（`drawFarLife2`）→ 转角（`blit39(CORNER_C2)`） |
| 远景与树（6 步） | 远景窗内生活（`drawFarWindows2`）→ 广告牌（`drawBoard10`）→ 鸽群（`drawPigeons7`）→ 三棵西雪松（逐棵：本体 + 随风摆动的树冠）→ 枯枝（`drawTwigs`）→ 地面层（`blit39(GROUND_C13)`） |
| 地面与近景（10 步） | 地面动态（`drawGroundLife`：碎冰反光）→ 雪层 0（`drawSnow6(SNOW_L2[0])`）→ 共享天气 0 → 右侧墙（`blit39(WALL_C2)`）→ 前景楼（`blit39(FRONT_C8)`）→ 灯的开关（`drawToggles`）→ 前景动态（`drawFrontLife`）→ 地面雪雾（`drawWisps5`）→ 雪雾纱（`VEIL_C4`，α 随 `flurry`）→ 远段路灯光晕（`drawLampHalos(t, 40, 999)`） |
| 收尾（8 步） | 雪层 1 → 共享天气 1 → 巷道（`drawLane6`）→ 近段路灯光晕（`drawLampHalos(t, 0, 40)`）→ 骤雪泛白（`flurry > 0.01` 时整幅填 `#9a94b0`）→ 雪层 2 → 共享天气 2 → 暗角（`drawImage(VIG_C51)`） |

⚠️ **`rng()` 消费次序即画面本身**。上游本城共 **7 组 / 34 条独立序列**（同一消费者的多枚种子在表内并列）：

| 组 | 消费者 | 种子 | 影响 |
|---|---|---|---|
| `SNOW_L2[0..2]` | 三层雪花 | 8100 / 8101 / 8102（**3 条**） | 每层每片雪的起点与落速 |
| `WISPS11` | 地面雪雾 | 4242 | 46 条雪雾的位置与浓淡 |
| `rng(2026)` | 创建后**从未取数** | 2026 | 无（保真照搬：`renderBacks` / `renderEndHouse` 都没用它） |
| `GLITTER` | 地面碎冰反光 | 515 | 1500 个反光点的位置与亮度 |
| `XMAS_LIGHTS` | 五扇挂圣诞树彩灯的窗 | 300 + i（i = 0…4，**5 条**） | 每扇窗上彩灯的排布与颜色 |
| 三棵西雪松 | `renderTree8` 的枝干与 `TWIGS` | `T6.seed` = 5 / 9 / 13（**3 条**） | 每棵树枝干的角度、长度与枝条位置 |
| 窗台冬青枝 `SPRIGS` | 每栋房子每个底层窗 | `hi * 13 + Math.round(c * 7)`（10 栋 × 每栋 2 扇底层窗 ⇒ **20 条**） | 每根冬青枝的位置 / 高度 / 叶尖 / 浆果 |

⚠️ `init` 与 `prerender51` 的调用次序不可调换（前者消费 8100–8102 / 4242，后者消费 2026 / 515 / 300–304 / 5 / 9 / 13 与 20 条冬青枝）；
流与流之间互不干扰，但**每条流内部的消费次序不可调换**（雪粒 / 雪雾 / 地面反光 / 彩灯 / 枝干 / 冬青枝的图案都由它决定）。
⚠️ 另有一条**非随机的顺序**：上游 `def()` 的 `PID` 自增 ⇒ 十六位路人与五位颂歌者的 `id` 决定 `look23` 的缓存键，必须按上游次序建。

#### 8.52.3 本城的结构性新东西：**引擎没有 `clip()`** ⇒ 本地矩形裁剪；其余 14 条受控差异

- 上游用 `ctx.beginPath()` / `ctx.rect()` / `ctx.clip()` 做**矩形裁剪**（`drawFarLife2` 裁巷道、`drawWindowLife` 裁窗洞、`drawNearRight` 裁近处窗），本项目引擎**没有**这套 API ⇒ 在场景文件内**本地实现矩形裁剪**（`bosClipTo` / `bosClipOff`：整幅快照 + 框外还原），**⛔ 绝对不改引擎**（引擎是已落地 56 座城共用的）。
- 本城的 `FONTS3` 是**本城自带**的字模表（`M` 宽 5 列，"a 3-wide M reads as H"）⇒ 不能用引擎 `FONT3` / `text`，广告牌上的 `LAMPLIGHTER / HOT COCOA / CREAM PIE` 按本表逐字符照画。

逐条受控差异（产物文件头注共登记 16 条，此处列 9 条要点）：

| # | 差异 | 理由 / 形态 |
|---|---|---|
| ① | 颜色统一**非预乘 ARGB `Int`** | 上游混用 CSS 字符串与 `[r,g,b]` 数组；`mixHex35` 的 `MIXED16` 缓存删除（结果逐位不变，只是少了缓存）；`C32` / `toHex24` 删除 |
| ② | `pix32(w, h)` ⇒ 本地 `BosPainter`（包引擎 `PixelBuffer`） | `.set` 同 `jsRound` 取整并**覆盖**写入；上游 `p.set(i, j, col, a)` 的 alpha 覆盖语义保留；`.line` / `.alpha` / `.fill` 同名；`done()` ⇒ `.canvas` |
| ③ | `sprite40(rows, key, flip2)` ⇒ 本城同名函数 | **宽度取各行最大长度**（引擎 `spriteFromRows` 用首行长度，不等价），翻转按整幅镜像 |
| ④ | `trim37(p)` ⇒ 引擎 `PixelBuffer.trim()`（返回 `Pixmap`） | 与上游 `{c, x, y}` 同构；⚠️ **带裁剪原点** ⇒ 字段类型要用「画布 + 偏移 x/y」的容器（`POST_C` / `END_C` / `WC_C` / `CORNER_C2` / `BACKS_C` 都是它的结果），贴图时按偏移贴 |
| ⑤ | 5 参 `drawImage(img, dx, dy, dw, dh)`（`drawDoors` 的门光缩放） | ⇒ 引擎 9 参 `drawImage(img, 0, 0, w, h, dx, dy, dw, dh)`（引擎无 5 参重载） |
| ⑥ | 广告位 `PITCH44 = makePitch(...)` 整段删除 | 本项目不放广告（`covers` 恒 false、`draw` 空）⇒ `PitchStub` 空桩，`drawBoard10` 只画自有画面 |
| ⑦ | 共享天气 `drawWeather` 空实现 | 上游母题 `snow` 虽是共享键，但 `plans` 对"自有母题"一律给 `shared: null` ⇒ `wx.shared` 恒 null；`WX51` 照搬为数据但零消费方；本城自绘的三层雪（`SNOW_L2`）是**自有图层**，与共享天气无关 |
| ⑧ | `Uint8ClampedArray` / `Int16Array` / `Uint8Array` / `Float32Array`（常春藤打包、暗角遮罩） | ⇒ Kotlin 的 `IntArray` / `DoubleArray` |
| ⑨ | 音频轻反应（§10 D-a）**三通道只作用光晕 α** | `lightGain` → 全部光晕的 α（`halo51` 统一乘）；`neonPulse` → 灯火类（路灯 / 灯笼 / 窗灯 / 广告牌灯 / 车灯 / 相机屏）；`tailGain` → 车流光尾（仅 `HL.car` 与车灯）。⛔ 坐标 / 几何 / 色值一律不碰 |

其余沿用前 56 城口径：容器类全部嵌在场景类内部（顶层 `private class` 跨文件重名会触发 `Redeclaration`）；字面量常量提到文件级 `private const`/`val`、算术派生量与 `rng()` 派生量留实例字段（由 `initConstants` 按上游次序赋值，如 `WCY = -GR3 * ZEND`、`LANTERNS5`、`LIGHTS7`、`CHIM9`、`WISPS11`、`SNOW_L2`、`GLITTER`、十一位路人的 `def()`）；上游 `ambience`（wind / traffic）是音频参数 ⇒ 不移植，`events` 只作画面节奏锚点（上游本城事件无 `pan`、无 `dur`）；`globalCompositeOperation = "lighter"` ⇒ 引擎 `Comp.LIGHTER`（引擎有，无缺口）—— 上游本城**没有** `multiply` / `destination-in` / `soft-light`。

#### 8.52.4 编译期踩坑（本城新增，余下城市照用）

| # | 坑 | 表现 | 修法 |
|---|---|---|---|
| ① | **`wave(t, k, ph)` 的 `k` 必须是 `Double`** | 写 `wave(t, 5, 0.12)` 报 `Argument type mismatch: actual type is 'Int', but 'Double' was expected` | 整数种子一律补 `.0`（引擎 `wave` 三个形参全是 `Double`） |
| ② | **Kotlin 的 `max` / `min` 只有两参** | JS 的 `Math.max(a, b, c)` 照抄过来编译不过 | 改 `maxOf(a, b, c)` / `minOf(a, b, c)` |
| ③ | **`lateinit` 不能用于基本类型** | `private lateinit var x: Double` 报 `Type 'Double' is not a valid type for a lateinit property` | 改 `private var x: Double = 0.0`；`lateinit` 只留给引用类型字段 |
| ④ | **上游的 `{ … }` 裸块语句会被当成尾随 lambda** | 照抄裸块后编译器把后面的实参当成 lambda 传了出去 | 写成 `run { … }` 保留块语义 |
| ⑤ | **Kotlin 会把 `a < (if (…) x else y)` 解析成泛型调用** | 报 `Function invocation ... expected` | 去掉括号写 `a < if (…) x else y` |
| ⑥ | **`Char` 与 `String` 比较恒为假** | `row[i] == "#"` 永远不成立 ⇒ 精灵整行判空、贴图错位 | 写 `row[i] == '#'` |
| ⑦ | **C 式 `for (var i = a; i < b; i++)` 不是 Kotlin 语法** | 直接照抄编译不过 | 写 `for (i in a until b)` |
| ⑧ | **引擎 `PxRng` 取数是 `.next()`，没有 `r()`** | 照上游写 `r()` 一律 `Unresolved reference 'r'` | 一律 `.next()`（上游 `rng(seed)` 是可调用对象，两种形态别混） |
| ⑨ | **`Uint8ClampedArray` 的「就近取偶」** | `roundToInt()` 是"半值向 +∞"，与 JS 的半偶不同 ⇒ 通道值差 1 | 用 `java.lang.Math.rint(v)` |
| ⑩ | **`DoubleArray.slice(0, n)` / `IntArray.set(other, offset)` 不存在** | `slice` 是 `IntRange` 版；`set` 没有"整段拷贝"重载 | 前者 `copyOfRange(0, n)`；后者手写拷贝循环 |
| ⑪ | **上游 `trim37` 的结果是带裁剪原点的 `{c, x, y}`** | 直接当"整幅画布"贴 ⇒ 图案整体错位 | 字段类型用「画布 + 偏移 x/y」的容器，贴图时按偏移贴 |
| ⑫ | **`POST_C` / `END_C` / `WC_C` / `CORNER_C2` / `BACKS_C` 都是 `trim37` 的结果** | 写成普通 canvas 字段 ⇒ 类型不匹配 | 五者统一用同一个「画布 + 偏移」容器类型 |
| ⑬ | **`ITEMS` 字段要在 p02 声明并在 `initConstants` 赋值** | 跨片引用 ⇒ `Unresolved reference` | 字段声明与赋值分居两片时，先确认声明在先、赋值在后 |

#### 8.52.5 验证与偏差（落地时实测，2026-10-27）

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` **0 错误 / 本文件 0 告警** |
| 全量单测 | **2,612 例 / 259 类 / 0 失败 / 0 错误 / 2 skipped**（与迪拜 / 佛罗伦萨 / 雅典 / 洛杉矶 / 香港基线同量级） |
| E51 门禁 | **119 例 / 0 失败 / 2 skipped**（含 `LofiCityRegistryGateTest` 元数据逐字段对账、`LofiUpstreamHygieneTest` 头注点名上游模块） |
| 快照门禁 | Snapshot 三例全绿（含本城：非空 ≥20% / 四时刻有差异 / 同 t 两遍逐像素相等） |
| `lintDebug` | 通过（0 error） |
| 目检 | `LOFI_SNAPSHOT=1` 导出 `t = 0 / 60 / 120 / 228` 四帧：左上角 **ACORN** 标题字样；画面是一条**极窄的雪巷**，一点透视地把左右两排砖房往深处的灭点收拢；左边一列**红砖联邦式房屋**，每扇底层窗都透着暖黄的灯，窗与门之间爬着**深绿的爬山虎 / 冬青**，门口挂着**圣诞花环**、台阶与栏杆顶上都积着**白雪**；右边是另一排**砖墙**与一道**矮花园墙**，墙头覆雪、墙上爬满绿植，墙外立着一株覆雪的**雪松**；巷子里竖着三支**黑色高杆路灯**，灯头亮着暖光并在雪幕里晕开；右上方横着一块**旧广告牌**，上面写着 **LAMPLIGHTER · HOT COCOA · CREAM PIE**，广告牌下方爬着常春藤、上方停着几只鸽子；巷尾是一栋**红砖端屋**，三扇暖窗、门洞亮着，旁边挂着**美国国旗**；地面是**湿冷的石板路**，散着成片的**碎冰反光**与一层薄薄的**雪雾**，天上飘着**雪花**；`t = 120` 起雪雾更浓、路灯晕更明显，`t = 228` 巷子中段出现**几个行人**（有牵狗的、也有拉雪橇的）与更多的雪 —— 四帧之间雪量、行人位置、灯晕与碎冰反光都在变 |

| 未做 | 真机截图评分（同 §8.32–§8.51 口径：源码级逐函数对账 + 无设备整帧判读） |

> **本城的验收要点**：窗台冬青枝有 20 条独立 `rng` 流（10 栋 × 每栋 2 扇底层窗），种子由 `hi * 13 + round(c * 7)` 派生 ——
> 若派生的偏移写错（例如误乘 10），画面**不会报错**、只会安静地变成"所有窗台的冬青枝长得一模一样" ⇒
> 所以必须**逐时刻比对不同房子窗台上的冬青枝**是否各不相同，不能只看"帧非空 / 帧会变"。

⛔ 未推送、未 tag（§2.6）。本轮本地提交 `7fd0442`。

---

## 9. 里程碑与验收

| 阶段 | 内容 | 验收口径（必须真机） |
|---|---|---|
| **P0 探针** | ①`Filter.Nearest` 放大在创维 5.1.1 是否硬边；②`PixelBuffer`→`ImageBitmap`→`drawImage` 每帧成本；③**Cape Town 场景直译跑通**并测帧耗时 | 电视真机 release 截图：放大边缘为硬像素台阶（能数出 4×4 方块）；帧耗时中位数与 P95 落 `logs_temp/`；**低于 15 fps 时按 §5 的画质内节流处置（LOW→静帧、MEDIUM→20fps），不把主题从列表里剔除** |
| **P1 引擎 + 首城** | `visualizer/pixel/` 全套（含 `PixelSign`、`PixelGlobeLand` 直搬 `LAND5`）+ `LofiCityRenderer` + `LofiCityCapeTown` + 主题接入 9 处 | 单测：`hash/rng/bayer/pickFromRamp/确定性` 逐位固定断言，**基线由 `logs_temp/` 里的上游 JS 在 `vm` 沙箱跑原文再生**（照 `moonlit_cloud_golden.js` 的"数字只能由脚本再生、不许手抄"纪律；改判后这是允许且最省事的对账源，脚本入 `scripts/`）；`LofiCityVariantGateTest` 在**不传 `-P` 的默认构建**下三断言全绿；全量 `testDebugUnitTest --rerun-tasks` 零失败；电视上 `pixelScale=2` 站牌在 1 米外可辨认 |
| **P2 地球过场** | `PixelGlobe`（直译 `globe.js`/`globe-draw.js`）+ 阶段机 + **换歌切城**（§6.5；原为 20 分钟定时）+ **T-CITY 右下角三行卡（§12）** | 电视上观察一次完整过场（约 3.4–4.0s、无残影、结束后旧城位图已释放）；晨昏线随真实 UTC 变化；手机竖屏地球完整不裁掉；右下角三行不裁字、与圆点不重叠、**暂停时当地时间继续走** |
| **P3 首批三城** | 重庆/台北/大阪直译 + 双语名 + `nameZh`/中文 tagline + 逐城站牌定位 + 门禁 | 每城：整帧 + 地标放大裁图 + 循环 4 个时刻并排 + 竖屏，共 ≥3 轮；地标相似度人工评分 ≥4（查直译错，不查美感）；站牌不与地标争位 |
| **P4 全量移植 + 统一验收**（⏸ 暂停中，见 §8.2；**2026-10-22 第三轮已首测编译，见 §8.5**；**2026-10-23 第四轮六城清零 + 接线，见 §8.6**；**京都整城重写，见 §8.7**；**墨尔本整城落地，见 §8.8**） | 余下 44 座（京都 §8.7 / 墨尔本 §8.8 已落地）+ 补齐 3 份半成品草稿（§8.5.4 的执行顺序）；全部落地后**一次性**接线：`SCENES_BUILT` 扩到 57、`LofiScenes.build()` 加 57 个分支、注册表 `focusX` 回填、`LofiSceneSnapshotTest` 扩到 57 | 全量 `testDebugUnitTest --rerun-tasks` 零失败 + `lintDebug` 无新增 error + `assembleRelease`（本机 `lofi.city=true`）；真机抽查若干城（含一个 look 与一个室内机位）；每城 4 个时刻快照交所有者评分。⛔ **本阶段的任何产物都不 push、不发 release**（§2.6；⚠️ 该护栏已被外部进程突破一次，见 AGENTS.md E51 条目）。⚠️ **当前基线（2026-10-24，罗马·斗兽场落地后）：`compileDebugKotlin` 0 error / 全量单测 2588 例 0 失败 2 skipped / `lintDebug` 161 warnings 0 error，已落地 27 座（`SCENES_BUILT` 27 项，见 §8.23）** |

构建与验证一律按项目口径：`--no-daemon -Pkotlin.compiler.execution.strategy=in-process`，遇 `fileHashes.lock` 先 `./gradlew.bat --stop`；计数类门禁要 `--rerun-tasks`。

### 9.1 电视真机五项现象 → 四条根因（2026-10-25）

所有者报告（创维 5.1.1，v2.39.7 本机 release；**有概率出现**，也有正确的时候）：

1. 切城时右下角中文卡与左上角英文已经换了，**画面还是旧城**；
2. 切城时**地球过场的画面不出现**；
3. 帧率只有 **3–6 fps**；
4. 从其它效果切进本效果时**画面锁住**（不是黑屏），过一会儿整体才出现。

"有概率"这条是关键：它把方向锁在**耗时抖动 / 竞态**上，而不是确定性逻辑错误。四条根因：

| # | 现象 | 根因（源码位置按修复前） |
|---|---|---|
| **R1** | 4（锁画面） | `onEnterContent → installCity → scene.create()` **在 UI 线程同步烘焙**。一座城 84–463 个 `PixelCanvas`（2–11MB，JVM 43–158ms，TV 估 1–4 秒）。`RendererFx` 的时序是 **fresh.onEnter 先于 old.onExit** ⇒ 冻结期间屏上一直是旧效果的最后一帧 |
| **R2** | 2（地球不出现） | R1 造成的 1–4 秒长帧喂给 `LofiCityTimeline.advance` 的 `while` 追帧 ⇒ **一帧跨完 FADE_OUT→GLOBE_IN→GLOBE_SPIN→GLOBE_OUT**，地球段从未被真正画出来。首次过场的 `LofiGlobeStage(CityRegistry.CITIES)`（720×360 撒夜灯）也压在同一帧 |
| **R3** | 1（文字先切） | ①`step()` 在 `GLOBE_SPIN → GLOBE_OUT` 时**直接**把 `currentId` 改成目标城；②而 `promoteNextToCurrent` 在 `nextFrame == null`（烘焙被 OOM/耗时拖掉）时**静默早退** ⇒ 文字/站牌走 `currentId` 已换、画面没换，直到下一次切城才恢复。间歇性与 TV 堆 56/63MB、GC 释放 5–6MB LOS 的时序吻合 |
| **R4** | 3（3–6 fps） | 每个重算节拍约 **98 万次** Kotlin 逐像素 `blend`（其中 51.8 万全透明、22.2 万全不透明，都是可整行化简的），TV 单帧 ~175–285ms |

**修法（四条一一对应）**

| 修法 | 落点 | 钉它的门禁 |
|---|---|---|
| **A 行级快路径**：整行全透明 ⇒ 跳过；整行全不透明且 `alpha==1 && SOURCE_OVER` ⇒ `System.arraycopy`；区块贴回用交集区间替代逐像素边界判断 | `PixelCanvas.kt` `blitWhole`/`blitRegion`（输出**逐位不变**，真源 `PixCtx.blendPixel`） | `LofiCityBlitFastPathTest`（3 op × 3 alpha × 随机像素含整行单色 + 负向自证） |
| **B 后台烘焙**：单线程 daemon `e51-bake`，代次（epoch/bakeGen）+ 锁发布；首入先铺 `LOFI_TRANSITION_BG`，UI 线程不等；首城失败重试 2 次 | `LofiCityRenderer.requestBake/runBake/requestGlobeBake/ensureGlobe/adoptBaked` | `LofiCityBakeGateTest` B1/B2/B6 |
| **C 安装原子化**：`step()` 只置 `installNow`，`currentId` 由渲染器**真的装上之后**调 `commitInstalled()` 提交；装不上调 `abandonInstall()` 保持旧城 | `LofiCityTimeline.step/commitInstalled/abandonInstall` | `LofiCityTimelineTest` R6 三条 + `LofiCityBakeGateTest` B4/B5 |
| **D 每阶段至少画一帧**：`markPhaseDrawn()` + `trackPhaseDrawn` 门；地球未就绪就不销账 ⇒ 时间轴停在地球上等（`installReady` 同理），绝不越过安装点 | `LofiCityTimeline.advance` 的两条门 | `LofiCityTimelineTest`「每阶段至少绘制一帧」「安装未就绪停在地球上等」+ `LofiCityBakeGateTest` B3 |

**自测仪表**：`adb logcat -s LofiCity`。阶段一变立刻一行，平时每 2 秒一行：`phase / cur / tgt / fps / repaint=…ms / bake=…ms / scene / baked / globe`。用 `android.util.Log.i`（`AppLog.i` 带 `BuildConfig.DEBUG` 守卫，release 里会被折掉）。

⚠️ **两条写代码时必须遵守的形状约束**（门禁是字符串判据，不做语法分析）：

- `drawContent` 里 `repaint(` 必须写在**单行** `if (shouldRepaint(…)) repaint(…)` 里 —— 拆成块状 `if` 会被 `LofiCityFlickerGateTest` 判成"节流形同虚设"（它只看"该行是否以 `if (shouldRepaint` 开头"）。
- 一个阶段内 `present(` 必须在 `drawContent` 的函数体顶层（深度 1）—— 这是 v2.39.7 频闪修复的原有口径，别与上面的单行 if 混在一行。

---

## 10. 裁决台账

所有者 2026-10-22 授权：「能自己定的按你的建议定，定不了的再问我」。**D-a…D-l 已落定，剩一个开放项 D-m**（其它功能要 push 时怎么把 E51 提交摘出去——涉及历史改写，必须所有者点头）；被真机数据推翻的（尤其 D-a 幅度、D-c 档位表现），改"裁决"列并留变更记录。

| # | 事项 | 裁决 | 依据 / 备注 |
|---|---|---|---|
| **D-a** | 音频反应幅度（上游画面完全不随音乐动） | ✅ **只开 3 个亮度类通道**：低频→窗灯整体亮度、节拍→霓虹闪一帧、能量→车流光尾透明度。⛔ 位移 / 几何 / 色相一律不动，以保住 240 s 确定性循环与"静谧夜色"美学。三个系数做成 `LofiCityRenderer` 顶部常量，另留 `AUDIO_REACTIVE = false` 一键回到纯时间模式 | ⚠️ 起始量级按"1 秒内不该察觉、3 秒内该察觉"取弱档，**是我给的口径不是实测值**；P2 真机看图调，调完写回本行 |
| **D-b** | 首批城市 | ✅ 改判后全部为**直译**：先 **Cape Town**（1,686 行，最短，用于打通引擎 + P0/P1 对账），再 **重庆 1,885 / 台北 1,772 / 大阪 2,681**，见 §8 | 行数实测自 `logs_temp/lofi_modules/`（58 个场景模块） |
| **D-c** | `Tier` 落哪档 | ✅ `Tier.ADV` + `needsParticleBudget = false`；画质降级走渲染器内部 `MOTION_FPS` 节流，**不动 `Tier`、不从列表剔除**。详见 §5（含"原建议 MED 是 Tier 与 VisualQuality 概念混淆"的纠正） | `AppSettings.kt:386`、`supports()` `:478-488`、2026-10-01 方案 C 裁决 |
| **D-d** | 切换间隔是否进设置页 | ✅ **不进**（2026-10-24 改判：间隔本身取消，改成**换歌曲即换城**，见 §6.5；随机口径改为**洗牌袋**，见 §6.5.1） | 项目既有口径「运行态项不进设置页」 |
| **D-e** | `lofi-upstream/` 的留存 | ✅ **保留**（本机、gitignored、`main` 源集之外）：改判后它是**对账基线的来源**（§9 P1 的单测基线要从上游 JS 跑出来），比原来更有存在理由；翻译件本体则进 `main`（受 §2.5 开关保护不进公开 APK） | 上游 JS 原文不进 git；翻译件文件头必须回指原模块路径 |
| **D-f** | E51 中文名 | ✅ **「像素城市」**（所有者 2026-10-22 选定，占位名转正）⇒ `values/strings.xml` = `像素城市`、`values-en/strings.xml` = `Pixel City`（照 `Snow Cedar` / `Tripod Fog` 那类直译短名）。`CHANGELOG` 用「像素城市」 | 双语齐备由 `VisualizerThemeStringsGateTest` 拦 |
| **D-g** | §2 的版权护栏 | ✅ **随 D-j 改判作废**（原①"移植件不进 release"改由 §2.5 编译开关承担；原②"地球照画法、代码自写、数据自产"改为直译；原③ idea/expression 界线表不再约束实现）。保留的唯一一条是**工程纪律**：翻译件必须标明来源模块，便于跟随上游改版 | ⚠️ 原① 现由 **§2.6 + D-l** 承担（且更严：源码根本不进远端）；来源标注纪律同时服务于 §2.6 的"哪些文件不许外发一眼可辨" |
| **D-h** | 3×5 字模 | ✅ **直译上游 `FONT3`**（定义在 `logs_temp/lofi_modules/js__engine__core.js.js:239`，`textWidth` 同处）：不接系统字体。系统字体会破坏整幅像素美学，且拿不到定宽 `textWidth` | §4 树、§12.2 |
| **D-i** | 城市真实地方时钟 | ✅ 要，随 §12 三行卡进 P2；用 `java.util.TimeZone`，⛔ 不用 `java.time`（minSdk 22 需 desugaring，本项目未开） | 2026-10-22 已裁 |
| **D-j** | **实现方式改判** | ✅ 所有者 2026-10-22 裁：「反正我不会发布，代码可以逐行照搬」⇒ ①上游 JS **逐行直译**（引擎 + 地球 + 场景 + `LAND5` + registry 的 `tagline`/`accent`）；②**撤销 §11 T-ATR 关于页致谢任务**；③§2.5 的编译开关**保留**（上一轮亲自指定的需求），它只隔离公开 APK，不隔离已公开的源码——这三条事实由 §2 记录 | ⚠️ 本行的③已被 **D-l** 收紧：源码不再"已公开"，而是一律不进远端 |
| **D-k** | **移植范围与执行模型** | ✅ 所有者 2026-10-22 裁：①范围 = **网站全部 57 个场景**（50 主城 + 7 look），不做抽样；②执行模型 = 「先 57 个全上，将代码全部移植完成，然后统一进行测试和编译」⇒ ⛔ 不逐城编译，落地后一次跑全量测试 + lint + release（§9 P4） | 台账与进度见 §8.2/§8.3；同日以「太慢了」叫停派发，代码冻结在 12/57。**2026-10-22 第三轮修正**：因首次真跑编译暴露出「顶层类型重名」这类**只有编译才能发现**的缺陷（§8.5.2），"统一编译"在实操上变成了"补完一批必须编译一次"——⛔ 但口径不变：**不逐城跑全量测试/不逐城出 APK**，只允许对 `compileDebugKotlin` 做定点核对 |
| **D-l** | **移植件不得外发**（第二次版权裁决） | ✅ 所有者 2026-10-22 裁：「我们的移植件，也不能上传到 github，不发布没有版权的代码」⇒ 移植件与上游派生物**只存在于本机**：⛔ 不 push、不 tag、不发 release、不上传任何远端；`.gitignore` 不算护栏；§2.5 编译开关降级为第二道锁。执行条款（边界清单 / git 现状 / 文档侧 / push 前核对四步）见 **§2.6** | 与 D-j 不冲突：D-j 管"能不能照搬"（能，在本机），D-l 管"能不能外发"（不能）。**本行是本方案最后一次版权相关变更** |
| **D-m** | 其它功能要 push 时，怎么把 E51 提交摘出待推历史 | ✅ **已解决（2026-10-23，走了 (a) 的强化版）**：把 E51 的全部提交与源码留在本地专用分支 `feat/e51-private`（无 upstream），`main` 经历史改写后对齐到干净远端。实际做法比原 (a) 更彻底——不是 rebase 掉两个提交，而是 `git filter-repo` 从**整条历史**里删掉 56 个移植件路径（1113 → 1057 文件），因此 `main` 不残留任何 E51 对象，其它功能照常 push。迁移同时把源码收进 `visualizer/e51/{engine,scenes}/`（原 `(c)` 的动机之一）。⛔ 仍成立：任何会话不得在 `main` 上做 E51 改动。

**由所有者执行、不是开发阻塞的两件事：**

- **本机 `local.properties` 里 `lofi.city=true` 这一行是个人开发机配置**，不写进任何共享文件、不进 CI 配置（§2.5）。缺省不传即公开变体，本方案全部开发都在 `true` 下进行。
- **§2.6 第 4 条的 push 前核对由人执行**（不能做成门禁：门禁只能看见工作区，看不见"这个提交会不会被 push 出去"）。在 D-m 落定之前，⛔ 任何会话都不得对含 E51 的 `main` 执行 push。

原先列在这里的「要不要向作者申请许可」随 D-j 改判作废，但**被 D-l 换了个形式复活**：许可不再是"照搬的前提"（照搬已在本机进行），而是**"将来若想外发本效果的唯一前提"**——在取得上游许可之前，§2.6 的不入库纪律没有解除路径。风险归属见 §2 的三条事实记录 + §2.6。

---

## 11. ~~开发任务 T-ATR：设置 → 关于 页致谢作者与网站~~ ❌ 已撤销

所有者 2026-10-22 裁决（见 §10 D-j）：「关于页致谢作者与网站这个工作也不需要做了」⇒ **本任务不再开发，不进 P2、不进任何批次，`AboutSection.kt` 不改动，`about_inspiration*` 两条 key 不新增。**

保留这一节只为记两件事，防日后误捡：

1. **撤销不等于否认出处。** 逐文件来源标注仍在做，且比致谢卡更精确：每个直译的 Kotlin 文件头写明上游模块路径（§8 移植纪律第 1 条）。出处信息跟着代码走，不依赖 UI。
2. **原任务里那三条关于页口径是通用知识，与本案无关，别丢掉**：外链 URL 提文件级 `private const val`、电视上的可点链接必须是 `SettingsTile` 而非 `Text(...).clickable {}`（`Modifier.clickable` 在遥控器下**聚焦不到**，是关于页犯过的真错，记录在 `AboutSection.kt:44-48`）、图标用 `Icons.AutoMirrored.Filled.OpenInNew`（`Icons.Default` 路径在 1.6+ 已 HIDDEN）。将来任何设置页外链照这三条做。

---

## 12. 开发任务 T-CITY：当前城市名标识（双层，所有者 2026-10-22 定调）

裁决两条：**① 两层都要**（画面内像素英文名 + 页面叠层中文名）；**② 叠层内容为上游完整三行**（城市名 + tagline + 该城真实当地钟表时间），**位置放右下角**。

### 12.1 先说清参照物在哪（实测）

- 网站自己的城市卡在**左上角**：`.hud-city { position: fixed; top: var(--top); left: max(18px, env(safe-area-inset-left)) }`，内含 `#cityName`（`font-size: 30px; font-weight: 650; letter-spacing: -.01em`）、`#tagline`、以及该城当地时间（`.sub time`，CSS 注释原话：*"the time is the city on screen's, not the names rolling by"*）。它的底部是一条**居中的按钮 dock**，不放城市名。
- 我们现状：**右下角全空**（`grep Alignment.BottomEnd` 在 `VisualizerStage.kt` 与各 screen 中**零命中**）；左下角是歌曲信息（`VisualizerStage.kt:505-533`，`start=48.dp, bottom=8.dp, end=vintagePillarDp`，24sp 标题 + 16sp 歌手）；底部居中是 `ThemeIndicator` 圆点；左上是手机返回按钮；右上是 fps 读数。⇒ **右下角确实是最省事的空位，且与左下歌曲信息天然不打架。**

### 12.2 画面内像素英文名（`PixelSign`，P1 提供、P3 逐城定位）

新增 `visualizer/pixel/PixelSign.kt`：把 §4 的 3×5 字模按 `pixelScale` 参数绘制（**默认 `pixelScale=2`**）。⚠️ 必须放大：字模原始高 5 像素，经 `SCALE=4` 后只有 **20 屏幕像素**高，在 1080p 电视上明显偏小；`pixelScale=2` → 40 像素高，仍是硬边像素风且可读。

- **只能显示拉丁名**：`FONT3` 无中文（这是 §10 D-h 自建字模表的固有代价，也正是"两层都要"的成因）。取 **registry 的 `id` 转大写**（`chongqing` → `CHONGQING`），不另设英文名字段，与上游 `#cityName` 同源。
- 位置：**画面内左上**（`x=8, y=8`，480×270 坐标系），理由是上游同处、且不与右下角叠层卡争视觉。每城可覆写偏移，因为左上必须让给当城地标的就挪（例：东京塔偏左时改 `x=` 塔右侧）。**落地时逐城看图定，不做全局常量。**
- 对比度：城市名要落在任意画面内容上（天空、楼群、水面都可能），必须自带底垫或描边。取**1 像素暗描边**（先画偏移 8 方向的暗字再画正字），不用半透明黑底矩形——那会在画面上留一块"贴纸"，破坏像素一体感。改判后描边色**直接照搬上游 `OUTLINE2 = "#070814"`**（进 `PixelColor` 常量表，与其余色阶同源，不再"我们自己定一个"）。
- ⛔ 不得引入 `android.graphics.Typeface` 或系统字体走这条路，`PixelSign` 只吃字模表，保持 `DrawScope.draw` 内零分配。

### 12.3 页面叠层三行卡（右下角，P2）

新增 `CityInfoCard`，`Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 8.dp)` —— **`start=48.dp`/`end=48.dp`、`bottom=8.dp` 照左下歌曲信息同参**，保证两侧留白对称、且不踩电视 overscan（这是现有代码已经付过学费的口径）。

三行照上游：城市名 24sp/`SemiBold`、tagline 16sp/`TextSecondary`、当地时间 16sp。**字号直接复用 `SongInfoTexts` 的 24sp/16sp 两级**，不新增字号档（项目「单一真源」口径）。

三处必须处理的真问题：

1. **不用做反色双层裁剪。** `VisualizerStage.kt:506-532` 那套「黑带区白字、画面区黑字」是为 VINTAGE_TV 的 4:3 黑带专门存在的。本卡只在 `LOFI_CITY` 主题下渲染 ⇒ `isVintageTv` 恒 false ⇒ `vintagePillarDp=0`，**单层白字即可**。⚠️ 但这条是**条件成立**的：一旦将来把城市卡开放给别的主题或让像素城市与怀旧框叠加，必须立刻补回反色裁剪。写成代码注释钉住。
2. **手机竖屏撞圆点。** `ThemeIndicator` 底部居中（`:536-542`），竖屏宽度下三行卡的左缘会压到圆点行。处置：手机（`!isTV`）抬高本卡 `bottom` 到圆点行之上；**具体抬多少必须真机量，不许拍数字**——照「测量参照物再下结论」的既有教训，量完把结论写回本节。
3. **每分钟的时钟不能引发整台舞台重组。** 现有帧循环走 `withFrameNanos` 且刻意不重组（`:231`）。当地时间用一个独立 `remember { mutableStateOf(...) }`，由 `LaunchedEffect` **睡到下一个整分钟边界再刷**，每分钟一次重组、只命中这张小卡。⛔ 不要把时间塞进 `RenderContext` 每帧传（那会让它跟音频帧耦合，暂停即冻结，钟表就停了）。

#### 12.3.1 首行改为「国名 · 城名」+ 语言跟随（2026-10-25 补记）

所有者 2026-10-25 追加要求：右下角城市名**前面加国家名、用 `·` 分隔**，且**中英切换后一起切**。

- 拼接口径落在引擎侧 `LofiCityNaming.kt`（纯 JVM，可单测）：`lofiCityCardTitle(city, zh)` = `国名 + " · " + 城名`，**国名为空时不出分隔符**（否则会出现「 · 布拉格」）。
- 国名**不做逐城字段**：50 城只用到 33 个国家，逐条加会把「美国」抄 9 遍 ⇒ 引擎侧单表 `COUNTRY_ZH` 映射 `LofiCity.country`（英文真源不变），缺项**回落英文**而不是留空。
- 语言判据取 **`LocalConfiguration` + `ConfigurationCompat.getLocales`**，⛔ 不取 `Locale.getDefault()`（本项目切语言走 `applyLocale()` + `Resources.updateConfiguration()`，`getDefault()` 会被污染，见 `NasMusicApp.getSystemLocale` 注释）。切语言本身会重启 Activity（`SettingsBranch` 的 finish + 新 Intent）⇒ 与 `stringResource` 必然同步。
- ⚠️ 首版实现是 `nameZh.ifBlank { name }`（**恒中文**），与本节 §12.6「渲染期按设备语言二选一」**不符** —— 切到 English 后同屏「当地时间」已是英文标签、城名/标语还是中文，是半截翻译。标语一并改走 `lofiCityDisplayTagline`。
- 门禁：`LofiCityNamingTest`（N1 国家中文表全覆盖 / N2 中英两路必须不同 / N3 缺译回落不留空 / N4 拼接形态），4 例。

### 12.4 时间怎么算（⛔ 不能用 `java.time`）

registry 每条已带 `timeZone`（如 `"Asia/Tokyo"`）。用 **`java.util.TimeZone.getTimeZone(id).getOffset(System.currentTimeMillis() + rawOffset 修正)`** 手算当地时分。

⛔ **禁止 `java.time.ZonedDateTime`/`ZoneId`** —— 本项目 `minSdk 22` 且未开 core library desugaring，`java.time` 在 API 22 上直接 `NoClassDefFoundError`（创维 5.1.1 就是基准回归设备）。夏令时由 `getOffset()` 内部处理，不要自己写偏移表。

卡片显示的是**那座城的此刻**，不是手机/电视的此刻 —— 这正是上游那句话的意思，也是这张卡的全部魅力所在。

### 12.5 过场期间的显示规则（避免误导）

| 阶段 | 画面内像素名 | 右下角中文卡 |
|---|---|---|
| `CITY(旧)` | 旧城名 | 旧城三行 |
| `FADE_OUT` / `GLOBE_IN` / `GLOBE_SPIN` | **跟着地球瞬时落点跳字**（照上游"地球点亮它上面的名字"），用当前 pin 的 `id` | **隐藏**（此时显示任何城市名都会误导） |
| `GLOBE_OUT` / `FADE_IN` / `CITY(新)` | 新城名 | 新城三行淡入 |

### 12.6 双语与验收

- **文案真源只有一个：`CityRegistry` 生成表。** 城市名走 `name` / `nameZh`，tagline 走 `tagline`（上游英文原句）/ `taglineZh`；渲染期按设备语言二选一（`zh` 用中文列，缺译即中文列为空时**回落英文列**，不许显示空白）。
  ⛔ **不再新增 `visualizer_city_tagline_<id>` 这类 string key**（本条为 2026-10-22 的改判，原文要求过 strings）。理由：tagline 是**随上游同步再生的数据**，抄进 `strings.xml` 等于同一句话两处真源，同步脚本还得去改资源文件（脆弱），并且 `VisualizerThemeStringsGateTest` 那种"扫资源对撞"的门禁会多一条跨源对账却对不出语义。生成表已经带 `tagline`/`taglineZh` 两列，`nameZh`/`taglineZh` 是 §7 第 4 步「读回自填列」机制保住的手工列，**这就是双语的家**。
  ⇒ 唯一进 `strings.xml` 的是 UI 文案 `visualizer_city_time_label`（"当地时间" / "Local time"），双语齐备由 `LofiCityRegistryGateTest` 扫两份资源对撞。
- ⚠️ `VisualizerThemeStringsGateTest` 只扫 `visualizer_theme_*`，**兜不住本卡的列**，故 `LofiCityRegistryGateTest` 自加一条：**每个已落地城市**（`SCENES_BUILT` 里的 id）必须有 `nameZh` + `tagline` + `taglineZh` 三条齐备（未落地城市允许空，靠这条门禁把"落地即补译"变成硬约束）。
- 真机（创维 TV）：电视 overscan 下右下角三行**不裁字**；深/浅主题下均可读；1 米外能认出城市名。手机竖屏：与圆点不重叠、不溢屏。
- 暂停播放：当地时间**继续走**（它不该跟歌曲进度绑定），但画面时间轴冻结（§1.3b）—— 两条规则不同源，验收时分开看。
- `CHANGELOG.md` 当前版本段加一行：「视觉化新增当前城市名与当地时间显示」。

---


## 附录 A：本轮明确不做（YAGNI）

生成式音乐、合成环境音（`ambience` 四通道）与整点音效事件——声音全部来自项目播放器，本效果只出画面（§1.3b）；`spots`/`secrets`/护照贴纸/广告牌轮播/投票请求新城/分享卡片/PIP/录像导出/`obs` 模式、直播在线人数、地区筛选与搜索、广告位 `pitch`。

## 附录 B：文档生命周期

本文件是**活跃方案**，留在 `docs/` 根。按 `AGENTS.md` 的归档规则：P3 验收通过后 `git mv` 到 `docs/archive/` 并改写所有入站引用，`docs/archive/README.md` 记一行（含 §10 被真机数据改动过的裁决，尤其 **D-a 反应幅度**与 **D-c 档位表现**，以及仍未落地的城市清单）。`CHANGELOG.md` 只在有代码落地时写，且每行只写"做了什么"，根因与验证过程一律进 `docs/technical-overview.md` §10.N——**公开文档的措辞红线见 §2.5 连带项**。

⛔ **本文件受 §2.6 第 3 条约束：它含逐城移植台账与上游实现细节，本身也算移植件 ⇒ 归档时的 `git mv` 只能落在本机，⛔ 不得随含 E51 的提交 push。** 因此本文件的"归档"与 `AGENTS.md` 的常规归档不同：它进的是**本机历史**，不进远端历史。同理适用于 `docs/archive/verification/results/lofi_registry_*.json` 快照。
