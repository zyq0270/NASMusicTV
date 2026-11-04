# E52「旷野公路」视觉化效果开发方案（Slow Roads CPU 核移植 · Canvas 2D）

> **状态**：v1.4 / 2026-10-29 / **路线已改为原生 OpenGL ES（见 `docs/archive/e52-highway-opengl-plan.md`）** —— 本方案的 D1「载体 = 纯 Canvas 2D」被所有者推翻；**事实层被新方案继承**（§1.4 / §3.2 / §3.3 / §4.6 / §4.8 / §六 / §七 / §八）
>
> ⚠️ **本文的 D1「载体 = 纯 Canvas 2D / ⛔ GLSL」已被所有者 2026-10-29 推翻** ⇒ 路线改为**原生 OpenGL ES**，
> 见 **`docs/archive/e52-highway-opengl-plan.md`**（v1.0）。本文的**事实层仍然有效并被新方案继承**：
> §1.4 参数表、§3.2 数学核清单、§3.3 `tileReady` 契约、§4.6 音乐映射、§4.8 环境集与 **D19–D25** 裁决、
> §六 PRNG、§七 门禁体例、§八 **K1–K10**（K9→GLES 方案 P7，K10→P6）。
> **被推翻的只有**：D1（载体）、§1.3（着色层 CPU 重写）、§3.4、§4.1（远景改预烘焙位图）、§4.2（色带合批）、§4.5（三灯 CPU 化）。
> 理由：Canvas 2D 的 `fill()` 是「一条路径 → 一个纯色」，没有片元着色器/纹理采样/法线贴图/逐像素光照；
> 而上游地形片元实测 **15 次 `texture2D` / 8 张贴图**、每帧约 **3,000 万次纹理采样**（@1080p）
> ⇒ **低多边形观感是表达能力的物理上限，不是实现取舍**。新方案的实测依据见 GLES 方案 §一 / §二。
>
> **目标**：在频谱效果里新增第 28 套「旷野公路」——**纯 Canvas 2D 渲染**，
> 车沿一条在丘陵间起伏的公路上一直向前开，永不重复、永不结束。
> **参照物 + 代码底座**：`Slow Roads`（`RobertTBS/OLD-OLD-OLD-slowroads-OLD-OLD-OLD`，CRA 生产构建产物）。
> **移植原则**（所有者 2026-10-29 裁定）：**自用不发布 ⇒ 尽可能逐行移植**；
> 数学核 1:1 转写，**只有网格密度必须降约 100×**（§三），着色层按上游 GLSL 原文逐行转写、只换采样率。
>
> **阅读顺序**：§一（许可已裁定 + 产物形态）→ §二（为什么 WebView 路线死透了）→
> §三（⭐ 逐行搬什么、必须改什么）→ §四（降采样策略，⭐ 性能全在这里）→ §五（预算）→ §七（原型门禁）→ §十一（决策/开放项）。
> ⚠️ 如果你只读一段：读 **§四.2 合批** —— 这一套成败全在它。

### 文档版本跟踪

| 版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.0 | 2026-10-29 | 初稿。两份上游事实卡（Slow Roads 生产构建产物 / javascript-racer）逐条核过源码后成文；确立「Canvas 2D 分段伪三维 + MIT 代码底座 + 程序化美术」路线 | 已被 v1.1 取代 |
| v1.1 | 2026-10-29 | **底座改判**：所有者裁定「自用不发布 ⇒ 无版权问题，尽可能逐行移植」⇒ 代码底座从 `javascript-racer` 改为 **Slow Roads 的 CPU 核**（jracer 降为投影/合批参考）。渲染侧事实卡量化出上游 **≈442,000 三角/帧**（远景 245,000 + lod0 125,440 + lod1 70,656 + 路面 972）⇒ 新增 §三「逐行搬什么/必须改什么」、§四「降采样策略」；原「全程序化美术」作废（上游 PNG 可直接用），内存账 5.9 → 16.9 MiB | 已被 v1.2 取代 |
| v1.2 | 2026-10-29 | **五项开放项裁定完毕**：显示名「旷野公路」（D19）、FOV 68° 照上游（D18）、交通车 0 辆（D20）、雾色纯白 0xFFFFFF（D21）、纹理 512²（D22）。文档标题随之改名；§4.6 补音乐映射；O 列表从 7 项收窄到 O3/O5 | 已被 v1.3 取代 |
| v1.4 | 2026-10-29 | **路线改为原生 OpenGL ES**（所有者裁定：红线里的 WebView/WebGL/GLSL/AGSL 指浏览器那一套，原生 GLES 不在内）⇒ 新方案见 `docs/archive/e52-highway-opengl-plan.md`；本方案的 D1 与 §1.3/§3.4/§4.1/§4.2/§4.5 被推翻，**事实层被继承**。**修正一处事实错误**：§4.8.1 的 Planet 地形档原先标 `:10518-10561`，恰好截在 `flat`（`:10570-10580`）之前 ⇒ 地形档 **7 → 8**（Hills 4 + Planet 4）、环境配置总数 **75 → 80 种**；连带修 G17 / `HighwayEnvCatalogGateTest` / `HighwayPlanetGateTest` 的判据数字与 `:10518-10580` 引用 | 当前 |
| v1.3 | 2026-10-29 | **环境集与切换契机裁定**（D23/D24/D25，§4.8）：照抄上游全集 `2 场景 / 7 地形档 / 6 skin / 10 天气`、⛔ 不自造（沙丘砍掉）、换歌即换环境（对齐 E51）、`Planet` 必移植但单列 K9；O3 关闭，仅剩 O5。**原型实测回填**：§5.1 三角/ops 表从"目标值"改为脚本再生值（BASIC 1,332 / ADV 2,892 / ULTRA 3,292 三角，13 fill / 12 beginPath），新增 §7.2 的 G13/G15–G17 与 R1 的阶段性答案 | 待评审 |

---

## 〇、三份文档的关系（⛔ 先读这段，否则会走错路）

本仓关于「公路 / 开车」已经有过两份文档，**E52 与它们不是同一件事**：

| 文档 | 主张 | 现在的状态 |
|---|---|---|
| `docs/archive/slow-roads-visualizer-plan.md`（v1.8，1,034 行） | 效果层是**全屏 WebView** 加载 `slow-roads.pages.dev`，遥控经 `dispatchKeyEvent` 转发 | ❌ **路线作废**（§二）。它的 v1.8 结论「合成发键无效、必须走系统输入管线」仍然有效，但**整条 WebView 前提**在 E41 真机与 deskworlds 裁定下已不成立 |
| `docs/archive/deskworlds-port-feasibility.md` | WebView 原样移植的三重阻断 | ✅ **仍然有效**，是 E52 改走 Canvas 2D 的直接依据（§二） |
| **本文（E52）** | **把 Slow Roads 的 CPU 核尽可能逐行移植**，着色层用 CPU 版重写（同一函数、更低采样率），光栅化用 **Canvas 2D** | 本方案 |

⭐ 一句话区分：**v1.8 想把别人的网站塞进电视；E52 是把它的代码搬进我们自己的渲染器里。**
⚠️ 注意这个差别：v1.8 是"嵌一个黑盒"，E52 是"移植源码" —— 后者**能逐行对账**，前者不能。

---

## 一、许可已裁定；产物形态决定了「怎么搬」

> 事实卡来源：对 `github.com/RobertTBS/OLD-OLD-OLD-slowroads-OLD-OLD-OLD`（`main`，只读归档）
> 全量 180 个 blob / 15,846,768 B 逐项核对。
> ⚠️ 本节 v1.0 的标题是「为什么不能照抄移植」—— 那个结论**已被所有者裁定推翻**（§1.1）。

### 1.1 许可：**已由所有者裁定，不再是约束**

- 仓库里确实**没有 `LICENSE` 文件**（GitHub API `license: null`），CC BY-NC-ND 4.0 只在 `README.md` 的一句散文里。
- **所有者 2026-10-29 裁定**：**自用、不发布** ⇒ **无版权问题**，只要能拿到代码就**尽可能逐行移植**。
- ⇒ 本节以下不再是"能不能搬"的**法律**问题，而是"搬过来能不能**跑**"的**技术**问题。
  ⚠️ 唯一保留的纪律：与 E51 同形 —— 移植件**⛔ 不 push / 不 tag / 不发 release**，
  因为它会随仓库一起被推到远端（AGENTS.md 已记录 E51 那次护栏被外部进程突破的教训）。
  这条纪律**不限制移植深度**，只限制发布动作。

### 1.2 它交付的是 **CRA 生产构建产物**，不是源码（⇒ 影响"怎么搬"，不影响"能不能搬"）

| 项 | 实测 |
|---|---|
| 仓库内容 | `index.html` + `static/js/*.chunk.js` + `static/css/*.chunk.css` + `static/media/*`。**无 `src/`、无 `package.json`、无构建配置** |
| `static/js/2.f5fa8177.chunk.js` | **1,152,675 B / 31,038 行**（vendor：three.js + 依赖） |
| `static/js/main.e7a33c55.chunk.js` | **1,094,294 B / 22,218 行**（应用代码） |
| 形态 | **美化过**：**一语句一行**、最长行 10,882 字符但仅 36 行 >500 字符 ⇒ **结构完整、可直接转写** |
| 名字 | **类名被 webpack 毁掉**（`class l` / `class C extends r.G` / `class et` / `class Ji` / `class Ds extends vs`）；**方法名与字符串完整保留**（`processCurve`、`stitchNormals`、`lookupHeight2`、`saveProgress`…）；局部变量是 `e,t,i,s,a,n,h,r,o` |
| source map | **无** |
| `.glsl/.vert/.frag` 文件 | **0 个** —— 所有着色器是 `main.e7a33c55.chunk.js` 里的**内联模板字符串** |

⇒ **"逐行移植"是可行的**：每一行都是一条可转写的语句，方法名给了锚点。
⇒ 唯一无法照搬的是**命名**：`et`/`Ji`/`Ds`/`vs` 这些类名必须**按行为重新命名**（已识别的见 §3.2 表）。
⇒ ⚠️ 这带来一个**必须承认的代价**：类名不可考 ⇒ 重命名错了会让"逐行"变成"看起来逐行"。
   缓解 = **`ShanShuiPortGateTest` 同款做法**：对可哈希的纯函数（`processCurve` / `qe` / `lerpHeight` /
   `Noise` 金字塔）做**逐位对账**，用数字而不是命名来证明"照搬了"。

### 1.3 它的"好看"几乎全在片元着色器里 —— 这是**唯一真正的移植障碍**

| 画面元素 | 实现 | 逐行搬到 Canvas 2D？ |
|---|---|---|
| 地形**高度/坡度/路基** | CPU 高度场（`et.generate` `:1630`）、`lerpHeight` `:1501`、`hockeyLerp` `:1461`、Bézier 路基 `processCurve` `:13003` | ✅ **逐行可搬**（纯 `Float32Array` + `{x,y,z}`） |
| 地形**颜色** | **逐片元**：材质 `vi = new r.F(...)` `:3344`；顶点自定义段 `:3418`/`:3422`；片元自定义段 `:3426`/`:3430`（`terrainBlend` 五行 + `texture2D(grassMap, vUv)`） | ⚠️ **函数可搬，采样率必须降**（见下） |
| 天空 + 云 | 材质 `Pe = new r.C(...)` `:1050`；顶点 `:1064`/`:1068`；片元 `:1080`（`mix(farCol,nearCol,dist*dist)` + 云双采样取 `max`） | ⚠️ 函数可搬，改成**低频**重算或预烘焙 |
| 雾 | 线性 `new r.p(16777215, 0.1*pe, pe)` `:1019`；天空自己改写了 `vFogDepth` `:1070-1073` | ✅ **可搬**（就是个距离函数） |
| 植被 | 实例属性 `heightOffset`/`roadProximity`/`treeMask`（`:3418` 声明），交叉 quad 索引 `:7438`（4 三角） | ✅ 位置逐行可搬；quad 换成降采样贴图 |
| 后处理 | **无**（`bloom`/`SSAO`/`EffectComposer`/`RenderTarget`/`postprocess` 七项关键词全 0 命中） | ✅ 无负担 |

⭐ **关键判断（本方案的立足点）**：
「GLSL 跑不了」≠「着色函数不能搬」。地形色本质是一个**纯函数**
`color = f(height, steepness, roadProx, uv)` —— 逐行搬到 Kotlin **完全可以**，
唯一的差别是**采样率**：上游每像素算一次，我们**每三角形算一次**（或每行算一次）。
⇒ 这不是"重新设计一套配色"，而是**同一个函数、更低的采样率**。
⇒ 视觉结果 = **低多边形平涂**（flat-shaded），而不是"另一种画风"。

⚠️ **代价已经量出来了（v1.1，见 §3.1）**：上游地形 **≈442,000 三角/帧**。
逐像素着色时三角形多少只影响顶点处理；逐三角形平涂时**每个三角形都是一次 fill**
⇒ 与 §4.2 的"≤18 图元/帧"直接对撞。**这是 E52 的头号技术问题（R1）**，
结论是：**数学核 1:1 搬，密度必须降约 100×**（远景 245,000 三角改预烘焙位图）。

⛔ **不搬**：GLSL 源码本身、`three.js` 的 `BufferGeometry`/`Material`/`ShaderChunk` 猴补丁机制、
`surfaceMap` 的 GPU 纹理采样（改为 CPU 查表）、PBR 光照链（stock `#include <lights_fragment_*>`）。

⇒ 结论修正（v1.1）：**Slow Roads 的 CPU 核可以当代码底座**；
**着色层必须重写成 CPU 版**，而重写的依据就是它的 GLSL 原文（逐行转写，只换采样率）。

### 1.4 顺带记下的事实（以后要引用时不用再查）

- three.js 版本**未定**，只能括到 **r139–r151**（`ColorManagement.legacyMode` 存在 ⇒ ≥r139；
  `outputColorSpace`/`useLegacyLights`/`ColorManagement.enabled` 全 0 命中 ⇒ <r152）。
- 相机 **68° FOV**（`ce = 68` `:1011`）、远平面 = `farSize × 2.5`（`:1012`）、雾近端 = 远平面 **10%**（`:1019`）。
- LOD 预设 `farSize` = **200 / 400 / 700 / 1000 / 1000**（`:890/901/912/923/933`）；
  **缺省档 = `H[2]`**：`farSize 700`、`viewDist 1400`、`fwdHorizon 180`、`rearHorizon 16`、`nearFwdHorizon 30`（`:909-919`）。
- **缺省分辨率档 = `Q[1]`**：`medRes 8`、`nearRes 16`（`:940-945`）；cell 世界尺寸 `we = 10`（`:1024`）；
  远景 cell = `le = 700` 单位、`res = de = 70`（`:1010`/`:15010`）。
- 调色板：`waterSkyReflection: 10143703`、`skyCol: 6859256`、`skyColFar: 13368830`、`fogNear: 0.25`、`fogFar: 1`（`:2897-2910`）。
- 浮点原点每 **100 个节点**重置一次（`:4435-4441`）；道具池 `:4395-4423`；每几何 **2 个实例**（`:6316`）。
- 路基半宽余量 `Se = 0.4`（`:1044`）；路面 ribbon 近 4×100 点 / 远 10×10 点（`:13877-13898`）。
- 流式半径 `mo = min((farSize/10)*2.5, 990)`、`xo = max(10, mo/4)`（`:14549`）——⚠️ 语义**未验证**（单字母变量）。
- ⚠️ 两个**必须改掉**的上游机制：`drawRange` 只增不减（`hideTile` 用 `heightOffset=-5`，`:14085-14087`）、
  `drawCount` 只在 `tileReady` 里 `++`（`:14076`）⇒ 见 §3.5。

---

## 二、为什么 WebView 路线彻底作废（⛔ 不要再提案）

| 证据 | 内容 |
|---|---|
| **E41 WORLD 真机** | 创维 9R54_G8S（Android 5.1.1 / WebView = Chrome 39）实测「**只画第一帧，之后永不更新**」⇒ E41 已降为 `Tier.ULTRA` |
| **deskworlds 裁定** | WebView 原样移植在低端设备上**三重阻断**，其中闸门③「与 WebGL 无关」的结论已被裁决 |
| **v1.8 实测** | 页面**校验 `isTrusted`** —— JS 合成 `KeyboardEvent` 恒为 `false`，游戏直接忽略。`isTrusted` 是只读安全属性，**无法伪造** ⇒ 没有第三条路 |
| **本仓纪律** | E47/E48/E49/E51 均明写「纯 Canvas 2D，⛔ WebView/three.js」 |

⇒ **E52 只能是 Canvas 2D。** 这不是取舍，是既成事实的推论。

---

## 三、移植范围：逐行搬什么、必须改什么（⭐ v1.1 核心）

> 一句话结论：**数学核 100% 可逐语句转写；网格密度必须降约 100×。**
> 这不是"重新设计"，是"同一套函数、更低的采样率" —— 但必须承认，密度降下来之后
> 画面会从"连续地形"变成"低多边形地形"。这是 Canvas 2D 的物理上限，不是实现取舍。

### 3.1 判定总表

| 层 | 上游每帧三角形 | 能否原样搬 | 处理 |
|---|---|---|---|
| **纯数学核**（`qe` / `lerpHeight` / `processCurve` / `lookupHeight2` / noise 金字塔） | — | ✅ **逐语句转写** | 1:1，见 §3.2 |
| **路面 ribbon** | **972** | ✅ **原样搬** | ⭐ 唯一能保原密度的部分（§4.3） |
| 近景 `lod1` cell（`res=16`，`nearRes`） | 70,656 | ❌ | **→ 近层** `res 16 → 4`（512 → **32** 三角/cell） |
| 远景 `lod0` cell（`res=8`，`medRes`） | 125,440 | ❌ | **→ 中层** `res 8 → 2`（128 → **8** 三角/cell） |
| **远景 tile**（`res=70`，25 块） | **245,000** | ❌ | ⭐ **改预烘焙剪影位图**（0 三角，2 blit） |
| 植被 | 80,000+ | ❌ | 实例 20,000 → ~200（§4.4） |
| **合计** | **≈442,000** | | **目标 ≈3,000 三角 + ~12 ops** |

⚠️ **442,000 → 3,000 是 147×**。这个数字必须写在最前面，因为任何"照搬网格"的计划都会在
真机上直接撞死，而失败点看起来会像"性能问题"，实际是**架构选错**。

### 3.2 可逐行搬的纯数学核（已逐条验证：零 three.js 类型依赖）

| 函数 | 行 | 行数 | 依赖 | 剥离后 |
|---|---|---|---|---|
| **`qe`** 路面距离查询 | `:1246-1367` | **122** | 节点链表 + 标量（`Xe` 仅 scratch） | ✅ 完整 |
| **`et.lerpHeight`** 路基融合 | `:1501-1529` | 29 | `getHeight(x,z)` 回调 | ✅ 完整（注入回调即可） |
| `et.hockeyLerp` | `:1461-1467` | 7 | 无 | ✅ 纯标量 |
| `et.sharpLerpAB` | `:1458-1460` | 3 | 无 | ✅ 纯标量 |
| **`processCurve`** 二次 Bézier 采样 | `:13003-13037` | **35** | 只读 `node.p` / 控制点 `{x,y,z}` | ✅ 完整 |
| `stitchNormals` | `:13038+` | — | 节点链表 | ✅ 完整 |
| **`lookupHeight2`** 16 角双线性 | `:1553-1629` | **77** | `preHeights[16]` 纯数组 | ✅ 完整 |
| `getCurvature` | `:1753-1761` | 9 | `heightmap.getXZLayer/getXZ`（纯函数对象） | ✅ 完整 |
| **`generate`** 逐行生成 | `:1630-1673` | 44 | `qe` + `lerpHeight` + `tileset.origin` | ⚠️ 把 `origin` 换成参数后完整 |
| `computeSquareNormalsRow` | `:1676-1732` | 57 | 用 `r.U` 的 `set/cross/normalize/negate` | ⚠️ 换 12 行手写向量运算即完整 |

⭐ **三处「一语句一行」的原文证据**（证明"逐行"不是形容词）：

```js
// qe:1249 —— 侧向判定，一行一个赋值
Xe.side = i.n.x * (t - i.p.z) - i.n.z * (e - i.p.x),

// lerpHeight:1506-1510 —— 路基宽度/坡度/融合半径，全是标量链
i.ga = Math.max(Math.abs(i.g), Math.abs(i.h)) / 3.6,
i.w += Se + i.ga,
i.y -= 0.015 + 0.025 * i.gfa,
i.ga = 1 - i.ga * i.ga,
$e.rm = 4 + 16 * Math.min(0.75, Math.max(0.4, i.ga)),

// processCurve:13007-13014 —— 二次 Bézier，两个分量各三行
ho.x = ho.ti*ho.ti*e.p.x + 2*ho.ti*ho.t* t.x + ho.t*ho.t*i.p.x
```

⇒ 这类代码**转写成 Kotlin 是机械劳动**，不需要"理解设计意图"。
⇒ 对账手段：`HighwayPortGateTest` 对 `qe`/`lerpHeight`/`processCurve`/`lookupHeight2` 做**逐位对账**
（与 E50 的 `ShanShuiPortGateTest` 同形制），用数字证明"照搬了"，而不是靠类名。

### 3.3 必须重写的绑定层（不是算法，是"把数组喂给 GPU"）

| 上游 | 行 | 绑定的 three.js 类型 |
|---|---|---|
| `et.display` → `uo.tileReady` → `po` | `:1762` / `:14064` / `:13929` | `r.g`(BufferGeometry)、`r.o`(BufferAttribute)、`r.l`(DynamicDrawUsage) |
| `uo` tileset 容器 | `:14005-14027` | `r.B`(Mesh)、`r.U`(Vector3) |
| `po.drawCount` | `:13933-13940` | `setDrawRange` + 6 个 `needsUpdate` |
| `it` 远景几何 | `:1786-1818` | `r.g`、`r.o`、`r.l` |
| `Ds.getGeo` 路面几何 | `:3968-3977` | `r.g`、`r.o`、`r.l` |
| `Za.buildLibrary` / `getGrassInstance` | `:7422` / `:7456-7474` | `r.u`(InstancedMesh)、`r.t`(InstancedBufferAttribute) |
| `vi.onBeforeCompile` 地形材质 | `:3404` | `r.F`(MeshStandardMaterial) + `ShaderMaterial` 猴补丁 |
| `Pe` 天空材质 / 天空网格 | `:1050` / `:15266-15272` | `r.C`、`r.B`、`r.I`(PlaneGeometry) |

⭐ **但这些函数的价值不在实现，在契约**：`tileReady(tileIndex, verts[3N], norms[3N], curves[N],
roadProx[N], treeMask[N], centroid, immediate)`（`:14064-14077`）—— **照抄这个签名**，
我们自己的烘焙层就能直接接上，无需重新设计数据流。

### 3.4 着色：**自定义部分可搬，PBR 链不可搬**

**顶点段**（`:3422`，上游自己写的，可逐行转写）：

```glsl
5:   height = wPos.y + heightOffset;
16:  vUv.x = wPos.x / 10.0;    17: vUv.y = wPos.z / 10.0;
21:  steepness = 1.0 - dot(normal, upvec);
22:  steepness = clamp(steepness * 2.0, 0.0, 1.0);
41-45: vCurvature = curvature>0 ? max(-1, curvature*-4) : min(1, curvature*-4);
60:  vHeather  = smoothstep(0.47, 0.53, min(1,height/170.0) * fade1 * fade2);
69-74: vLightGrass / vHeather 受 roadProximity 调制
100: transformed += vec3(0.0, heightOffset, 0.0);
103: transformed += normalize(objectNormal) * (texture2D(displacementMap, vUv).x * steepness * 1.25);
```

**片元段**（`:3430` / `:3426`）—— ⭐ **核心混合数学原文只有 5 行**：

```glsl
// :3426-36  terrainBlend —— 按「权重+alpha」做深度混合，可直接转写
float ma = max(tx1.a + w1, tx2.a + w2) - depth;
float b1 = max(tx1.a + w1 - ma, 0.0);
float b2 = max(tx2.a + w2 - ma, 0.0);
return ((tx1 * b1) + (tx2 * b2)) / (b1 + b2);

// :3426-38
vec4 roadCol = vec4(0.45, 0.45, 0.474, 1.0);

// :3430-105 —— 岩石按坡度混合
texelColor = terrainBlend(texelColor, 0.7, rockTex, steepness, 0.06);
```

⛔ **不可搬的部分**：`:110` `diffuseColor *= texelColor;` 之后交给 three.js 的
stock `#include <lights_fragment_*>` 与 `#include <fog_fragment>` ——
**PBR 光照链不在应用代码里**，我们拿不到，也不需要。

**光照**（`:1004-1008`，三个灯，全部可复现）：

| 灯 | 构造 | CPU 版怎么算 |
|---|---|---|
| `AmbientLight` | `new r.a(16777215, 0.1)` | `c += 0.1` |
| `HemisphereLight` | `new r.s(16777215, 16777215, 0.2)` | `c += 0.2 * (0.5 + 0.5*n.y)` |
| `DirectionalLight` | `new r.j(16777215, 0.6)`，`sunOffset = (0,100,0)` | `c += 0.6 * max(0, dot(n, sunDir))` |

⛔ **不做阴影贴图**（上游 `castShadow` + `mapSize 1024`，`:18860-18880`）——
逐三角形平涂时阴影只会变成脏斑；且地形本身 `receiveShadow` 但 `castShadow` 只在车体（`:14032`）。

**雾**（`:984-988` / `:1012` / `:1019`）：默认档走**线性雾** `near = 0.1·pe = 140`、`far = pe = 1400`
（`pe = farSize × 2 = 1400`）。⛔ 不是指数雾 —— 指数雾只在 `detailLodIndex > 1` 时启用。

### 3.5 ⚠️ 两个会让移植**严重低估成本**的上游机制（必须改掉，不是照搬）

1. **`drawRange` 只增不减**：退休 tile 不缩 range，而是把 `heightOffset` 置 `-5` 隐藏
   （`hideTile` `:14085-14087`，`hideHeight` 全 `-5` `:14029-14030`）⇒ **退化三角形照付光栅化开销**。
2. **`drawCount` 只在 `tileReady` 里 `++`**（`:14076`），只有 tileset `reset()` 才归零（`:14044-14047`）。

⇒ 在 GPU 上这只是"多画了些退化三角形"，可以接受；**在 Canvas 2D 上是纯亏**。
⇒ 移植时**必须真的把死 tile 从路径里剔掉**。这是本方案与上游的**第一处有意偏离**。

### 3.6 资产：**自用不发布 ⇒ 上游 PNG 可以直接用**（v1.0 的「全程序化烘焙」作废）

| 资产 | 来源 |
|---|---|
| 地形 surface | `grassMap` / `sandMap` / `rockMap` / `rockBumpMap` / `gravelMap` / `heatherMap` / `forestMap` / `roadBaseMap`（`:3393-3402`） |
| 混合遮罩 | `fadeRockMap`(`pi`) / `fadeFiner`(`ui`) / `map`(`ut`) / `displacementMap`(`pi`) |
| 云 | `clouds_02.png` **213,702 B** / `clouds_03.png` **192,131 B** |
| 路面 | `road_*` 系列 |
| 植被 | 树/草/灌木图集 |

⚠️ **但仍要降分辨率与改名**：1080p 的电视用不到 4K 纹理，且原图是"近景贴图"，
按我们的低多边形密度会糊成一片 ⇒ **统一降到 512² 后重采样**（**D22**），体积账见 §5.2。
⚠️ 体积是真代价：上游 `static/media/` 总量 **15.8 MB**（含音频与 OBJ，我们只要纹理子集）。

---

## 四、降采样策略（⭐ 成败全在这里）

### 4.1 三层 LOD 重构

| 层 | 距离 | 形式 | 三角/帧 |
|---|---|---|---|
| **近** | 0–60 世界单位 | 三角网格，`res=4`（32 三角/cell） | ≈1,000 |
| **中** | 60–200 | 三角网格，`res=2`（8 三角/cell） | ≈600 |
| **远** | >200 | ⭐ **预烘焙剪影位图**，随弯道做 x 偏移 | **0**（2 blit） |
| 路面 | 全程 | 原密度 ribbon（§4.3） | 972 |

⚠️ **远处的三角网格必须换成位图**，理由不是"省钱"，而是：
上游远景是 `res=70` 的 **700 世界单位**大 tile（`:1907`/`:1910`），25 块 = 245,000 三角，
**占总量 55%**。而它在画面上只占一条窄带（雾把它压到 near=140 之外几乎不可见）
⇒ **用位图替它是无损的**（视觉上等价，成本上归零）。

### 4.2 合批：**同色一条 `Path`**

平面着色量化成 **K 条色带**（K=6~8，由 `steepness` × `height` × `roadProx` 量化而来）
⇒ 每条色带一条 `Path`，把所有三角形塞进去，`fill()` 一次。

| 做法 | ops | 评价 |
|---|---|---|
| 逐三角 `fill`（≈3,000 次） | 3,000 | ❌ 不可用 |
| **同色一条 Path**（K=6~8） | **6–8** | ✅ |
| 三角形进 `Path` 但每三角单独 `fill` | 3,000 | ❌ |

⚠️ **R1 仍是未测量项**：一条 Path 塞 3,000/8 ≈ 375 个三角形（≈1,500 顶点）在 API 22 上的
填充成本**未知**。0.41–0.47 ms/图元那个模型按**图元个数**拟合，对顶点数不敏感
（E16 踩过：模型 27 fps、实测 44.1 fps）⇒ **必须重测，⛔ 不许拿旧模型当结论**。

### 4.3 路面：**唯一原密度照搬**的部分

上游路面（`:13877-13898`）：**远段 10 条 × 10 点** + **近段 4 条 × 100 点**，
索引公式每相邻两点 2 三角（`:13830-13844`）⇒ **972 三角**。

⇒ 972 三角在 §4.2 的合批下只占 **1–2 条 Path**，完全负担得起。
⇒ **路面必须保原密度** —— 它是画面的视觉焦点，且成本占比仅 **0.2%**。
⇒ 这也是"尽可能移植"最实在的落点：**用户看到的绝大部分是路面，那部分逐行照搬。**

### 4.4 植被降档

- 基础几何 = 两个交叉 quad，`setIndex([0,1,2,0,2,3,4,5,6,4,6,7])`（`:7438`）= **4 三角/实例**。
- 实例容量：草 `20,000`（`:3346-3352` 的 `ki=zi[2]=20000`）、树 `3,500`、灌木 `400`。
- ⇒ 20,000 × 4 = **80,000 三角**，比路面多 82×。
- **降档**：按 §4.1 的距离分层，只保留近层实例，总量 **≈200**（800 三角）；
  或进一步改成**单 quad 精灵**（2 三角）⇒ 400 三角。
- ⚠️ 密度必须**种子化**（上游是 `Math.random()`）⇒ 见 §六。

### 4.5 着色 CPU 化：逐行转写 `terrainBlend` + 三灯简化

**输入**：每个三角形算一次 `height`（顶点均值）、`steepness`（面法线）、`roadProx`（`qe` 给出）、`uv`。
**然后**：

```
c = grassMap(uv)                              // :3430-14
c += colouration * vLightGrass                // :18，colouration = (0.18, 0.08, 0.1, 0)
c  = mix(c, darkGrass, vHeather * smoothstep(0.3,0.7,fade1))   // :22
c  = mix(c, forestMap(uv), vTreeMask)         // :26
c *= vDarkGrass                               // :41
c  = roadProx < -0.2 ? roadCol : ...          // :59-61
c  = terrainBlend(c, 0.7, rockTex, steepness, 0.06)  // :105
c *= ambient(0.1) + hemi(0.2) + sun(0.6)      // :1004-1008
```

⇒ 这是**同一个函数**，只是从"每像素一次"变成"每三角形一次"。
⚠️ **代价**：纹理从"每像素采样"变成"每三角形取一个点" ⇒ 近处会明显块状。
**缓解**：近层（§4.1 的 0–60 单位）可以**每三角形取 uv 的 3 个顶点各算一次再平均**，
或对近层改用**每三角形 2 级细分**（32 → 128 三角），成本仍在预算内。

### 4.6 音乐映射（只动观感参数，⛔ 不动几何）

| 量 | 映射 | 约束 |
|---|---|---|
| 车速 | `0.6 + 0.7 · energy`（保底 0.35×、上限 1.30×） | 静音态仍在开（**G10**） |
| 雾浓度 | `near/far` 按 `mid` 反向调制（`mid` 高 ⇒ 雾退远） | ⛔ 改 `far` 不得让远景位图露出硬边（**G8**） |
| 天色 | `nearCol/farCol`（`:1080` 的 `mix(farCol, nearCol, dist²)`）按 `section` 换暖/冷 | 单帧变化 ≤6%（**G11**） |
| 地平线呼吸 | `pulse` 驱动相机 y 微幅正弦 | 幅度 ≤0.5% 屏高 |
| 云相位 | `uniform float time`（`:1064-1070`）改由 `treble` 累加 | 累加而非跳变，保证确定性（§六） |

⛔ **不改**：路面中心线（`processCurve`）、地形高度（`generate`）、LOD 分层 ——
一旦让音乐改几何，`HighwayPortGateTest` 的逐位对账基线就失效了。

### 4.7 ⛔ 红线（与 E50 同形制）

- ⛔ 逐帧 `createLinearGradient`（API 22 每次 ≈1.2 ms）—— 雾/天空一律预烘焙。
- ⛔ `Shader.setLocalMatrix` 做位移（E43 真机静默失效）—— 一律用 `drawBitmap` 的 x/y 参数。
- ⛔ `clipPath(`（API 22 `Region::createTJunctionFreeRegion` SIGSEGV）—— 遮挡靠绘制顺序 + 矩形裁剪。
- ⛔ `concat` × 非凸/自交填充路径（E46 真机 80 帧 0 像素）—— 三角形天然凸，安全；但**路径合批时不得混入非凸形状**。
- ⛔ 非凸 Path 逐帧画 —— 一律烘焙期。

### 4.8 环境集：**照抄上游，⛔ 不自造**（所有者 2026-10-29 裁定）

> **所有者原话**：「环境不要自己造，上游没有就不添加，但已有的都移植过来」。
> ⇒ **沙丘 / 沙漠 ❌ 不做**（上游根本没有这个 preset，见下表）；凡上游有的，一个都不许漏。

#### 4.8.1 上游实有清单（逐条核实，⛔ 不凭印象）

环境是**三个正交维度相乘**，不是一个平铺列表 —— 上游 `config` 的结构就是
`{ sceneName, topography, skin, weatherIndex, viewLodIndex, detailLodIndex, … }`（`:11894-11904`）。

**① 场景**（注册表 `ar = { Hills: Nn, Planet: sr }`，`:11890`；UI 列表 `nr = ["Hills","Planet"]`，`:11891`）

| 场景 | 植被 | 地形生成 |
|---|---|---|
| `Hills`（默认，`lr.sceneName = "Hills"` `:11907`） | `hasTrees/hasGrass = !0`（`:2119`） | 分形 heightmap，`resolutions: [3,12,24]` |
| `Planet` | `hasTrees/hasGrass = !1`（`:11888-11889`） | **陨石坑** heightmap：`craterLayers` / `craterDepth` / `heightInitial` / `compound: !0`（`:10518-10535`） |

⚠️ 源码里另有 `Mn.sceneName = "Peaks"`（`:10028`）与 `ht.sceneName = "Scene"`（`:2119`），
但**都没有注册进 `ar`** ⇒ 属于死代码，**不移植**（"已有"指 UI 可选的，不是源码里能 grep 到的）。

**② 地形 `topography`**

| 场景 | 档 | heightScale | roadWidth | 备注 |
|---|---|---|---|---|
| Hills（`:2841-2889`） | `normal` | 160 | 3.0 | 默认，缓丘 |
| | `hard` | 165 | 2.6 | 路窄、坡陡 |
| | `easy` | 180 | 3.2 | 更缓、路宽 |
| | `flat` | **0** | **20** | 纯平原（heightScale=0 ⇒ 高度场关掉） |
| Planet（`:10518-10580`） | `normal` / `hard` / `easy` / **`flat`** | 50 / 40 / 45 / **0** | 3.0 / 2.6 / 3.2 / **20** | `heightOffset: 500`、坑深 0.75 / 1.5；**`flat`（`:10570-10580`）= heightScale 0 / heightOffset 50 / resolution 1 / depth 1 / roadWidth 20 / smoothWindow 7 ⇒ 纯平原**（v1.4 补：v1.3 的范围标注 `:10518-10561` 恰好截在 `flat` 之前，漏了一档） |

**③ 季节 `skin` × 天气 `weather`**

| 场景 | skin | 显示名 | weathers | 关键色值（上游原值，⛔ 不许自己调） |
|---|---|---|---|---|
| Hills | `default` `:3232` | **summer** | sunrise / clear / rain / sunset / night | `grassColouration = [52,20,36,0]/255`、`treeColouration = [-0.05,0.1,0.1,0]`、`water = 0xA8B8BF` |
| Hills | `autumn` `:3259` | autumn | autumnSunrise / clearAutumn / autumnRain / twilight / night | `grassColouration = [60,25,45,0]/255`、`treeColouration = [0.1,-0.05,0,0]` |
| Hills | `winter` `:3284` | winter | winterSunrise / clearSnow / snow / winterSunset / night | `grassColouration = [0,0,0,0]`、`treeColouration = [0.15,0.15,0.15,0]`、`water = 0xB8C8CF` |
| Planet | `default` `:10696` | **mars** | mars / marsRise | 走**另一套片元链**（surface/subSurface/shale/rock/cliff/rubble），无草/林/石南/沙 |
| Planet | `venus` `:10715` | venus | venus / venusSet | 同上，`water: null` |
| Planet | `moon` `:10731` | moon | moon | 同上，`rubble: null`、`water: null` |

时间维度只有 `time: { noon: {} }`（`:3325`）一档 ⇒ **不做**（没有第二档可移植）。

⇒ 合计 **2 场景 / 8 地形档 / 6 skin / 10 天气**（v1.4 修正：Planet 的 `flat` 档原先漏计 ⇒ 环境配置总数 **80 种**，不是 75）。

#### 4.8.2 成本：为什么这个规模在 Canvas 2D 上是可接受的

| 切换维度 | Canvas 2D 侧要改什么 | 三角/fill 增量 |
|---|---|---|
| Hills skin（季节） | 换 **9 条色带色值** + 天空渐变 + 雾色 | **0** |
| Hills topography | 换 `heightScale`/`roadWidth`/`smoothWindow` 三个参数 | **0**（同一条管线） |
| 天气（晴/雨/雪/黄昏/夜） | 换天色 + 雾色；雨/雪要粒子 | 粒子单独记账（≤ N 图元） |
| **Planet** | ①陨石坑高度场（新增噪声项）②**第二套着色链**（surface/shale/rock/cliff/rubble，按 `vCurvature` 混）③植被清零 | 0 增量，但**多一套色带表** |

⚠️ **Planet 不是"换个色"**：它的片元链和 Hills 是两条不同的 `terrainBlend` 组合
（`:10785-10791` vs `:3426-3430`），混合权重来自 `vCurvature` 而不是草/林/石南。
⇒ 落地时是**第二份着色函数**，必须单列任务（K9），且**不阻塞 R1**。

#### 4.8.3 切换契机：**换歌即换环境**（对齐 E51）

> **所有者原话**：「切换环境的契机，与 e51 一样，换歌时进行切换」。

E51 的既定机制（`visualizer/e51/engine/LofiCityTimeline.kt:41-43`，所有者 2026-10-24 改判）：

- 渲染器**每帧**比较 `RenderContext.songId`，一变就切；
- `intervalSec` 生产路径恒为 `Double.POSITIVE_INFINITY`（`:102`/`:188`）⇒ **⛔ 不按时间切**；
- 触发源只有 `requestSwitch()`（换歌 / 遥控器 / 竖滑），过场进行中由时间轴丢弃（`:277`）。

E52 逐条对齐：

1. **触发**：每帧比 `songId`，`songId != lastSongId` 即起一轮切换。⛔ 不引入定时器、⛔ 不按 `section` 切。
2. **目标**：**游标前进**（`next environment in tour order`），⛔ **不是** `hash(songId)` 派生 ——
   后者会让「同一首歌永远同一个景」，与 E51「换歌即换城」的观感不一致。
   顺序 = `Hills×{summer,autumn,winter} → Planet×{mars,venus,moon}` 循环，地形档与天气档各按固定步长前进。
3. **过场必须是三阶段**（⛔ 不许硬切）：`淡出到雾白(≈0.5s)` → `重建世界` → `淡入(≈0.5s)`。
   原因：换环境要换**几何**（新 seed / 新 heightScale / 新高度场），不存在形变过渡的可能；
   雾色已是纯白 `0xFFFFFF`（D21），淡出到雾白是唯一不需要额外资产的过场。
4. **过场期间冻结切换**：切换中再来一次 `songId` 变化，只记 pending，过场结束再处理（同 E51 的丢弃语义）。
5. ⛔ **不许在过场里重建纹理**：6 套色带表 + 天空渐变全部**启动时一次性建好**，切换只换引用。

---

## 五、性能与内存预算（v1.1 重算）

### 5.1 三角形与 ops 账（⭐ v1.3 起是**实测**，由脚本再生，⛔ 不许手抄）

来源：`e52_highway_harness.js --all-tiers`（`vm` 沙箱跑**页面原文**，桩 canvas 1280×720，
30 帧预热后取单帧）与 `e52_highway_driver.mjs tiers 200`（真浏览器，画布 1566×803，
`reset(1234)` → 确定性重放第 200 帧）。

| 档 | 近层 res | 中层 res | 远层 | 植被实例 | 路面 | 三角合计（harness / driver） | fill / beginPath（harness → driver） |
|---|---|---|---|---|---|---|---|
| **BASIC** | 2 | — | 位图 | 60 | 972 | **1,332 / 1,332** | 10 / 9 → 10 / 9 |
| **ADV**（默认） | 4 | 2 | 位图 | 200 | 972 | **2,892 / 2,892** | 13 / 12 → 13 / 12 |
| **ULTRA** | 4 | 2 | 位图 | 400 | 972 | **3,292 / 3,292** | 13 / 12 → 13 / 12 |

- **三档三角数严格递增**；ADV **2,892 ≤ 3,200**（G2 上限，离 4,000 红线上限还有 27%）；
  600 帧内逐帧恒定（G4：无增长 ⇒ 死 tile 真剔除生效）。
- **两套口径的三角数逐档相等**（沙箱 vs 真浏览器）⇒ 渲染路径与画布尺寸无关；只有 fill 计数
  在采样帧不同时差 1（fix-1 自检报 `fills=14`，它取第 200 帧；本表 harness 取 30 帧预热后单帧）。
- **ops 构成（ADV/ULTRA 实测 13）**：天空 1 + 远山剪影 2 + 地形色带 ≤8 + 植被 2 + 路面 1 + 雾 1。
  K = **11 条色带**（9 地形 + 2 植被）⇒ `K+4 = 15`，**尚有 2 条余量**。
- 顶点数（driver，全画布合计）：BASIC **3,432** / ADV **6,408** / ULTRA **7,116**，分在 9–12 条 Path 里。
- ⚠️ **ULTRA 允许 <60 fps**（与 E41 同政策）。
- ⛔ 本表只能由 `--all-tiers` / `tiers 200` 再生，⛔ 不许抄进代码注释或测试当实测（D24）。

#### R1 的阶段性答案（v1.3）

R1 原本问两件事，原型只能回答一半：

| R1 的两半 | 原型能否回答 | 数字 / 结论 |
|---|---|---|
| **图元数**是否在 API 22 成本模型内（0.41–0.47 ms/图元 + ≈24.6 ms 固定 ⇒ 60 fps 需 ≤18 图元/帧） | ✅ 能 | ADV 实测 **13 fill / 12 beginPath**，**低于 18** 的门槛 ⇒ 预算侧成立 |
| **1,500+ 顶点/条 Path 的顶点数敏感性**（成本模型声称"对顶点数不敏感"，但从未在 API 22 上验证过） | ❌ **不能** | 原型跑在桌面 Chrome 上，`STATS.ms` 与创维设备无可比性 ⇒ **R1 仍未关闭** |

⇒ **R1 的关闭条件是 K8 真机 `dumpsys gfxinfo`**，不是原型。若真机掉帧，退路仍是"按距离再分层"
（每条 Path 更短），而不是降三角密度。

### 5.2 位图 / 纹理内存账（几何算得，⛔ 不是实测）

| 项 | 尺寸 | MiB |
|---|---|---|
| 半分辨率缓冲（960×540 ARGB） | 2,073,600 B | **1.98** |
| 天空（960×320） | 1,228,800 B | **1.17** |
| 远山剪影 2 层（960×240 ×2） | 1,843,200 B | **1.76** |
| 地形纹理 8 张 × 512×512 | 8 × 1,048,576 B | **8.00** ⚠️ |
| 混合遮罩 4 张 × 256×256 | 4 × 262,144 B | **1.00** |
| 云 2 张 × 512×512 | 2 × 1,048,576 B | **2.00** |
| 植被图集（512×512） | 1,048,576 B | **1.00** |
| 雾/暗角渐变 | ~26 KB | 0.02 |
| **合计** | | **≈16.9 MiB** |

⚠️ **纹理是新的内存大头**（原方案"全程序化"是 5.9 MiB，现在 16.9 MiB）。
仍在 46 MiB 容差内，但**8 张 512² 地形纹理是最大的可削减项** ⇒
若吃紧，降到 256²（8 → 2 MiB，总量 ≈10.9 MiB）。
⚠️ **API 22–25 上位图走 native heap**，`onExit()` 必须逐个 `recycle()`。

### 5.3 风险登记

| ID | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| **R1** ⭐ | 合批 Path 的**顶点数**成本未知（≈1,500 顶点/条 × 6–8 条） | 真机掉帧 | 原型先量；退路 = 按距离再分层（每条 Path 更短） |
| **R2** | 低多边形地形"太块" | 所有者判读 | 近层 res 4→6 / 加 `displacementMap` 扰动（`:103` 原样搬） |
| **R3** | 远景改位图后**弯道处与三角层接不上** | 弯道 | 位图按弯道做 x 偏移 + 中间层加宽重叠带 |
| **R4** | 纹理内存 16.9 MiB | LOW 档设备 | 256² 降级（→10.9 MiB） |
| **R5** | 竖屏下 68° FOV 拉伸（**D18** 定死 68°） | 手机 | 按 `minDim` 反算 FOV（**O5**） |
| **R6** | 上游 `drawRange` 机制被"照搬"进来 | 移植时偷懒 | §3.5 已写明必须真剔除；`HighwayCullGateTest` 守 |
| **R7** | 类名不可考 ⇒ 重命名错了 | 命名靠猜 | `HighwayPortGateTest` 逐位对账（§3.2） |

---

---

## 六、确定性与 PRNG

| 对象 | 上游行为 | 本方案 |
|---|---|---|
| **路面中心线** | **无 RNG** —— 节点链由 `processCurve`（`:13003`）的二次 Bézier 逐节点推进，`qe`（`:1246`）解析求距离 | ✅ **照搬 ⇒ 完全确定**，可直接哈希对账（**G1/G6**） |
| **地形高度** | **无 RNG** —— `generate`（`:1630`）逐格点调 `qe` + `lerpHeight`，`lookupHeight2`（`:1553`）做 16 角双线性 | ✅ **照搬 ⇒ 完全确定** |
| **法线 / 曲率** | **无 RNG** —— `computeSquareNormalsRow`（`:1676`）、`getCurvature`（`:1753`） | ✅ 照搬 |
| **植被 / 道具摆放** | `Math.random()` | ❌ **必须换种子化 PRNG**：`seed(base ⊕ cellX ⊕ cellZ)` |
| **云 / 天空相位** | `uniform float time`（`:1064-1070`） | 改由音乐驱动（§4.6），天然确定 |
| **交通车** | `Math.random()` | **0 辆**（**D20**）；上游有但本方案不做 |
| **步进** | 上游 `requestAnimationFrame` 变步长 | ❌ 改为**固定 60 Hz 累加器**（与 E50 同形制） |

### 6.1 PRNG 选型：⛔ 不要发明第三个

- **实现直接复用 E50 已验证的 `Prng`**（BBS 式 `p=999979`/`q=999983`，**逐位对账已 PASS**：
  `seed(1234)` 的 `sAfterSeed = 334869857393`，前 4 输出 `0.5179214018993742 / 0.19101042199684515 /
  0.1481747547047804 / 0.8586372206878526`）。
- **但必须分开命名空间**（两套效果互不影响）：`HighwayPrng(seed)` 与 `ShanShuiPrng(seed)` 是两个入口，
  内部共用同一个 `SeededPrng` 内核。
- ⛔ **不要在 E52 里改用 `mulberry32` / `Random` / `Math.random` 兜底** ——
  前者与 E50 的对账基线不一致，后者直接毁掉确定性。

### 6.2 种子怎么派生（⚠️ 不能只用一个全局种子）

- **地形是纯确定性的**，不需要种子 ⇒ 地形**不受 PRNG 影响**，这是好消息：
  降密度时改动 `res` 不会连带改变地形形状（同一 `res` 下逐格点值相同）。
- **植被必须按格派生**：`seed = base ⊕ hash(cellX, cellZ)`，这样
  ① 流式加载/卸载 cell 不会让远处的植被"跳变"；② 单格可独立复现。
- **浮点原点重置**（上游每 100 节点，`:4435-4441`）⛔ 不影响种子 —— 种子必须用**世界格坐标**，
  不能用重置后的局部坐标。

---

## 七、原型阶段（先原型 → 后 Kotlin，本仓固定流程）

### 7.1 交付物

- `docs/archive/e52-highway-preview.html` —— Canvas 2D 原型（活动期放 `docs/` 根，验收后 `git mv` 进 `docs/archive/`）。
  必须支持：三档切换、`?res=` 近层分辨率滑条（2/4/6）、`?half=1` 半分辨率开关、
  `?far=bake|mesh` 远景位图/三角网格 A-B 对照、`?fog=ramp|quant` 两种雾实现、
  注入模拟频谱（`bass/mid/treble/energy/section/pulse`）、静音态、内嵌自检面板。
  ⭐ **必须能实时显示 `三角形数 / fill 次数 / 顶点数 / 单帧 ms`** —— 这是本方案唯一的成败判据。
  ⭐ `?far=mesh` 是**对照组**：用来量"照搬远景网格"到底多贵，用数字堵住"为什么不照搬"的争论。
- `docs/archive/verification/scripts/e52_highway_harness.js` —— `vm` 沙箱跑**页面原文**（桩 DOM/canvas/rAF），
  驱动真实代码。权威输出归档 `docs/archive/verification/results/e52_highway_harness_final.txt`。
  ⭐ 必须打印：**上游 JS 数学核的参考向量**（`qe`/`lerpHeight`/`processCurve`/`lookupHeight2` 各 32 例）、
  逐档三角数、逐档 fill 次数、剔除后三角数随前进步数的曲线。
  ⚠️ 这份参考向量就是 **`HighwayPortGateTest` 的黄金向量来源** —— ⛔ 不许手抄（D24）。
- `docs/archive/verification/scripts/e52_highway_driver.mjs` —— 真浏览器 CDP 驱动
  （`gates [档] [帧]` | `shot` | `tiers` | `probe` | `vectors`）。栅格化类判据（G7 遮挡、G8 雾、G13 色带）只能由它出数。
- ⚠️ **探针必须挂在目标变量赋值之后**；沙箱里全局遮蔽问题要在脚本头自证（本仓踩过两次）。

### 7.2 原型门禁（v1.1 重写：从"几何正确"转向"移植保真 + 密度可控"）

| 门禁 | 判据 | 抓的是哪类失败 |
|---|---|---|
| **G1 数学核逐位对账** ⭐ | 页面里**原封不动的上游 JS 函数** vs harness 从**上游原件**抽取的参考向量：`qe` / `lerpHeight` / `hockeyLerp` / `sharpLerpAB` / `lookupHeight2` 各 32 例 + `processCurve` 8 个采样点，**逐位相等**（严格 `==`，不设 ε）。⚠️ **G1 只证"函数体没漂"**，**不证"生成出来的世界与上游一致"** —— 上游地形是流式随机生成（`Math.random()` 摆位 + 相机步进驱动加载），同一份上游代码跑两次都不一定得到同一个世界，**逐像素对账在原理上做不到**；世界一致性由 G14（worldHash 复现）与 §六 的种子化改造负责 | 移植"看起来像"但数值漂移 ⇒ 后续全部对账失效 |
| **G2 三角预算** ⭐ | 每帧三角形数 ≤ §5.1 表；**ADV ≤ 3,200**；⛔ 出现 >4,000 即红 | 照搬密度 ⇒ 147× 超预算 |
| **G3 合批生效** ⭐ | 每帧 `fill()` 调用数 **≤ K+4**（K = 色带数，**实测 K=11** ⇒ 上限 15）；`beginPath` ≤ K+4；⛔ 出现"逐三角 fill"即红。**实测 13 / 12**（ADV/ULTRA） | 退回 3,000 次 fill ⇒ 直接不可用 |
| **G4 死 tile 真剔除** ⭐ | 连续前进 600 帧，**活跃三角形数保持有界**（不随帧数单调增长）；⛔ 不得用上游 `heightOffset=-5` 隐藏法（§3.5） | 照搬 `drawRange` 只增不减 ⇒ 内存/耗时双涨 |
| **G5 LOD 接缝** ⭐ | 近(res4)/中(res2)/远(位图)三层交界处，**共享边两端顶点坐标差 ≤ 1e-6**；位图层与三角层的接缝带**不出现可见断崖**（相邻 8px 高度跳变 p90 ≤ 内部 p90） | 降密度后层间撕裂 ⇒ 看到"地形飞起来" |
| **G6 路面原密度** | ribbon 段数 = 近 **4×99 quad** + 远 **10×9 quad** = **972 三角**；逐段顶点哈希与归档值相等 | 误把路面也降密度 ⇒ 视觉焦点崩掉 |
| **G7 遮挡（画家序）** | **路面掩膜 = 「有路面」−「无路面」两帧同帧相减**（⛔ 不能按颜色分类：`BAND_COLORS[0]` 与 `[ROAD_BAND]` 同为 `#737379`，最近邻分不出路基与路面）。**G7a**：掩膜内部（1px 腐蚀掉抗锯齿）不得落在天空区（雾顶以上 + 该行天空渐变）⇒ 实测 **0 px**（y=312，长上坡时路面确实高过地平线 30 px，那是上坡不是泄漏）；**G7b**：掩膜内部必须 4-邻接连到画布底边 ⇒ 孤立 **116 / 164,309 = 0.07%**（上限 0.20%）。⛔ 不得用 `clipPath(` | 画家序错 ⇒ 路"穿"过山 |
| **G8 雾一致性** | 线性雾 `near=140 / far=1400`（`:1012`/`:1019`，`pe=farSize×2=1400`）；**G8a**：地平线 ±3 行平均 ΔRGB ⇒ 实测 **0.00**（上限 8）；**G8b**：轮廓上方 1–3 行命中色带像素 ⇒ 实测 **0 / 783 列**（必须 0）。⚠️ 地平线用**页面几何值**（`debugInfo().horizonY`），⛔ 不用"各列轮廓中位数"——长上坡时列间天然差几像素，那个替代品会把正确渲染判成 FAIL（实测 7 px 假红） | 雾当遮罩用 ⇒ 远山浮空 |
| **G9 着色对账** | CPU 版 `terrainBlend`（`:3426-36` 五行的逐行转写）+ 三灯（`0.1/0.2/0.6`）算出的三角形色，与 **CPU 参考实现（GLSL 逐行转写）**的 ΔE ≤ 3 | 配色"重设计"而不是"移植" |
| **G10 帧间稳定** | 静音态 600 帧：车速不为 0（保底 0.35×）、地平线 y 漂移 ≤0.5%、画面逐格在变 | 静音死屏 |
| **G11 平滑** | 任一映射量单帧变化 ≤ 动态范围 6% | 频闪 |
| **G12 构图一致** | `?half=1` 与 `?half=0`、横屏与竖屏的**构图锚点**（地平线/路面半宽/车占屏高）偏差 ≤0.5% | 上采样/竖屏把构图改了 |
| **G13 色带可分辨** | 画面直方图里 **≥4 条色带各占 ≥0.1%**（实测 **10 条**可见：`[0,1,2,3,4,5,6,7,9,10]`）；⚠️ 该判据只证"分得出多条色带"，**不是** G9 的着色对账。⚠️ 统计前必须把**同色不同带**折叠（0 与 8 都是 `#737379`），否则 8 恒为 0.00% | 着色被压成单一色块 ⇒ 看起来像一片灰 |
| **G14 确定性** | 同 seed 两次烘焙 `worldHash` 相等（实测 seed 1234 → `1ffef410`、seed 999 → `e8dd5a78`）；600 帧内三角数恒定 | 每次进画面地形都不一样 |
| **G15 换歌即换环境**（D24） | `RenderContext.songId` 一变即起切换：三阶段（淡出到雾白 → 重建 → 淡入）**不得硬切**；⛔ 不按时间切（`intervalSec = ∞`）；目标**游标前进**、⛔ 不是 `hash(songId)` | 换歌不换景 / 同一首歌永远是同一景 |
| **G16 同歌不切** | `songId` 不变时**永不**触发切换（连续 600 帧零切换）；过场中再来的 `songId` 变化只记 pending，过场结束才处理 | 画面自己乱切 / 切换打架 |
| **G17 环境枚举完整**（D23） | 环境表 = `2 场景 / 8 地形档 / 6 skin / 10 天气`，与 §4.8.1 逐项相等；⛔ 多一项（自造沙丘）或少一项都红 | 少移植了上游已有的环境 / 自己造了上游没有的 |

### 7.3 ⛔ 原型阶段禁止事项

- ⛔ 不用 PowerShell 对 UTF-8 文件做文本往返（CP936 读 + UTF-8 写 = 中文注释乱码 + 吃掉邻近标点）。
- ⛔ 不把本文的 target 数字抄进代码注释/测试当实测（D24）。
- ⛔ 不为了"跑得动"而悄悄改数学核（`qe` 的 `Math.sqrt` 换成近似、`lerpHeight` 少一项）——
  任何偏离必须先让 G1 变红，再显式记录在 §3.2 的"有意偏离"表里。

---

## 八、Kotlin 落地任务（K1–K10）

| ID | 任务 | 完成判据 |
|---|---|---|
| **K1** | `AppSettings.kt` 新增 `HIGHWAY(R.string.visualizer_theme_highway, "旷野公路", Tier.ADV, "52", needsParticleBudget = false)` | 枚举项数 **+1**（当前 27；若 E50 先落地则为 28 → 29）；`values-en` 补 key（`VisualizerThemeStringsGateTest` 守） |
| **K2** | `visualizer/highway/port/HighwayMath.kt` —— **逐语句转写**的纯数学核：`qe` / `lerpHeight` / `hockeyLerp` / `sharpLerpAB` / `processCurve` / `stitchNormals` / `lookupHeight2` / `getCurvature` / `computeSquareNormalsRow`。⚠️ **保留上游行号注释**（`// slow-roads main.e7a33c55:1246`） | `HighwayPortGateTest`：与 harness 黄金向量**逐位相等**（G1） |
| **K3** | `visualizer/highway/HighwayTerrain.kt` —— 节点链 + 高度场 + 三层 LOD 调度（`generate` 逐行搬，`res` 按档降） | `HighwayTerrainGateTest` + `HighwayCullGateTest` + `HighwayLodSeamGateTest` |
| **K4** | `visualizer/highway/HighwayBake.kt` —— 天空 / 远山剪影 2 层 / 上游纹理降采样到 512² / 雾渐变，**全部一次性** | `HighwayMemoryGateTest`：字节账 ≤ §5.2；`onExit()` 逐个 `recycle()`（**API 22–25 native heap**） |
| **K5** | `visualizer/highway/HighwayRaster.kt` —— 三角形按色带进 `Path` 合批、路面 ribbon 原密度、植被实例 | `HighwayBatchGateTest`：单帧 `fill()` ≤ K+4、`beginPath` ≤ K+4 |
| **K6** | 音乐映射 + 三档降级 | 静音态保底车速；`energy=1` 时车速 ≤1.30× |
| **K7** | `VisualizerRendererFactory.createEnabled` 加分支 | ⚠️ **必须加在 `when` 里**，⛔ 不要学 E51 的双变体 `if`（`VisualizerRendererFactory.kt:50` 已留注） |
| **K8** | 真机验收 → 文档同步：`technical-overview.md` 新增 §10.N、`CHANGELOG.md` 一行、`docs/visualizer-effects-list.md` 数量 +1；`FxCoverageScanTest.kt:271` 的 `decls.size >= 26` 抬到 **落地时的实际项数 + 1**（若 E50 已落地则 27 → 28，否则 26 → 27）并补一行注释 | 验收单 U1–U10 由所有者打勾；归档行写进 `docs/archive/README.md`（含未决 O5） |
| **K9** | **`Planet` 场景**（**D25**）：①陨石坑高度场（`craterLayers` / `craterDepth` / `heightInitial` / `compound`，`:10518-10580`）；②**第二套着色链**（surface / subSurface / shale / rock / cliff / rubble 按 `vCurvature` 混，`:10785-10791`）；③`hasTrees/hasGrass = !1` ⇒ 植被清零。⚠️ 这是**第二份 `terrainBlend`**，不是"换个色" | `HighwayPlanetGateTest`：**四档** topography 参数与 `:10518-10580` 逐项相等（含 `flat`）；三个 skin 的色带表项数与上游一致；植被三角恒为 0。**⛔ 不阻塞 R1**（K3–K5 可先只做 Hills） |
| **K10** | **环境集 + 换歌切换**（**D23** / **D24**）：①启动时一次性烘好 6 套 skin × 天气的色带表 + 天空渐变（⛔ 不在过场里建纹理）；②地形档只换 `heightScale`/`roadWidth`/`smoothWindow` 三个参数；③每帧比 `RenderContext.songId`，变了就起三阶段雾白过场（淡出 ≈0.5s → 重建世界 → 淡入 ≈0.5s），目标**游标前进**，过场中的变化只记 pending | `HighwayEnvCatalogGateTest`（G17）+ `HighwaySongSwitchGateTest`（G15/G16）；⛔ `intervalSec` 生产恒为 `∞` |

⚠️ **`FxCoverageScanTest` 的坑**：断言是 `>= 26`，新增渲染器后**它自己不会红**
⇒ 不手动抬阈值，这条门禁对 E52 **完全没有约束力**。

---

## 九、测试门禁清单

| 用例 | 判据 |
|---|---|
| `HighwayPortGateTest` ⭐ | `qe` / `lerpHeight` / `hockeyLerp` / `sharpLerpAB` / `processCurve` / `lookupHeight2` / `computeSquareNormalsRow` 各 32 例与 harness 黄金向量**逐位相等**；黄金向量由脚本再生（D24） |
| `HighwayRoadGateTest` | 路面 **972 三角**（近 4×99 + 远 10×9 quad）；逐段顶点哈希与归档值一致 |
| `HighwayTerrainGateTest` | 各档 `res` 与 §5.1 表一致；单 cell 三角数 = `2·res²`；格点值在同 `res` 下逐点复现 |
| `HighwayCullGateTest` ⭐ | 前进 600 帧后活跃三角数**有界**（不单调增长）；⛔ 不得出现 `heightOffset=-5` 式隐藏 |
| `HighwayLodSeamGateTest` | 三层交界共享边顶点差 ≤ 1e-6 |
| `HighwayBatchGateTest` | 单帧 `fill()` ≤ K+4；`beginPath` ≤ K+4（⛔ 逐三角 fill 即红） |
| `HighwayShadeGateTest` | CPU 着色 vs GLSL 逐行转写的参考实现：ΔE ≤ 3；`terrainBlend` 五行公式逐行等价 |
| `HighwayTierGateTest` | BASIC/ADV/ULTRA 的 `res`/植被数/缓冲比与 §5.1 表一致 |
| `HighwayDeterminismGateTest` | 同 seed 两次烘焙的植被布局逐项相等；不同 cell 的种子互不干扰 |
| `HighwayMemoryGateTest` | 位图总字节 ≤ §5.2 账；`onExit()` 后引用计数归零 |
| `HighwayPlanetGateTest` | `Planet` **四档** topography 参数与上游 `:10518-10580` 逐项相等（含 `flat`）；三个 skin 的色带表项数一致；植被三角恒为 0（**D25**） |
| `HighwayEnvCatalogGateTest` ⭐ | 环境枚举 = `2 场景 / 8 地形档 / 6 skin / 10 天气`，与 §4.8.1 **逐项相等**；⛔ 自造（沙丘/沙漠）或少移植都红（**D23** / G17） |
| `HighwaySongSwitchGateTest` ⭐ | `songId` 变化 ⇒ 起三阶段过场；`songId` 不变 ⇒ 600 帧零切换；过场中的变化只记 pending（**D24** / G15 / G16） |
| `VisualizerThemeStringsGateTest` | 既有门禁，`values-en` 缺 key 会红 |
| `FxCoverageScanTest` | 阈值抬到「落地时项数 + 1」 |

---

## 十、真机验收

### 10.1 设备矩阵

| 设备 | 角色 |
|---|---|
| 创维 9R54_G8S（Android 5.1.1 / API 22） | **基准设备**（最弱，`--no-daemon` 构建后 `adb install`） |
| 手机（1080×2400 竖屏） | 竖屏适配（**O5**） |

### 10.2 验收单

| # | 项 | 判据 |
|---|---|---|
| U1 | 60 fps（ADV） | `dumpsys gfxinfo` 掉帧 <5%；⚠️ 若实测达不到，记录真实值并**先降远层位图分辨率**，最后才降 `res` |
| U2 | 低多边形可接受 | 所有者判读"这是低多边形地形，不是坏掉的网格"；若判"太块" ⇒ 近层 `res 4 → 6`（**R2**） |
| U3 | 弯道 | 路面边缘连续，无折线拐点；远景位图与三角层接缝不可见 |
| U4 | 半分辨率观感 | 所有者判读"不糊"；否则 0.75× |
| U5 | 雾 | 地平线处无硬边；坡道上雾不压山丘 |
| U6 | 静音态 | 车仍在开（≥0.35× 速度），画面在变 |
| U7 | 内存 | 连续 30 min 无 OOM；`onExit` 后 native heap 回落 |
| U8 | 竖屏 | 构图不被拉伸（O5） |
| U9 | 三档切换 | 切档后无残留位图、无闪黑 |
| U10 | 与既有 27 套不撞车 | 所有者判读：与 E41「寰球」（3D 球）与 E50「千里江山」（横向长卷）**读起来是三种东西** |

---

## 十一、决策（D）与开放项（O）

### 决策 —— 落地时照此执行

- **D1** 载体 = **纯 Canvas 2D**。⛔ WebView / ⛔ WebGL / ⛔ three.js / ⛔ GLSL（§二）。
- **D2** 代码底座 = **Slow Roads 的 CPU 核，尽可能逐行移植**。
  ⛔ 不以 `javascript-racer` 为底座 —— 它降级为**投影/合批的参考实现**（"每色带一条 Path"的合批思路要用，
  但几何与构图以上游为准）。依据：所有者裁定**自用不发布 ⇒ 无版权问题**（§1.1）。
- **D3** **着色层 CPU 重写，但依据上游 GLSL 原文逐行转写**（⛔ 不是"重新设计一套配色"）：
  地形色 = `f(height, steepness, roadProx, uv)` 这个**函数原样搬**，只把**采样率**从每像素降到每三角形（§1.3）。
- **D4** **同色带一条 `Path`**，⛔ 逐三角 `fill`（§4.2）。这是本方案的第一硬约束。
- **D5** **三层 LOD 重构**：近 `res=4` / 中 `res=2` / 远 预烘焙位图（§4.1）。
  ⚠️ 这是本方案与上游的**第二处有意偏离**（上游 `res` = 8 / 16 / 70）。
- **D6** ⭐ **远景 tile 改预烘焙剪影位图**（245,000 三角 → 0）。理由不是省钱，而是
  「远景在画面上只占一条被雾压到 near=140 之外的窄带 ⇒ 位图在视觉上等价」（§4.1）。
- **D7** ⭐ **路面保原密度**（972 三角，占总量 0.2%）—— 它是视觉焦点，也是"尽可能移植"最实在的落点（§4.3）。
- **D8** **植被降档**：上游草 20,000 实例 × 4 三角 → 本方案 ≈200 实例（§4.4）。
- **D9** **三灯简化为 CPU 版**：`Ambient 0.1` + `Hemisphere 0.2` + `Directional 0.6`（`:1004-1008`），
  ⛔ **不做阴影贴图**（上游 `mapSize 1024`，`:18875-18877`；平涂下阴影只会变脏斑）。
- **D10** ⭐ **必须真剔除死 tile** —— ⛔ 不照搬上游 `drawRange` 只增不减 + `heightOffset=-5` 隐藏（§3.5）。
  这是与上游的**第三处有意偏离**。
- **D11** **半分辨率渲染缓冲 + 上采样**（§5.2 的缓冲账）。
- **D12** **雾 = 预烘焙纵向渐变位图 1 次 blit**。⛔ 逐帧 `createLinearGradient`、⛔ `setLocalMatrix`。
- **D13** 音乐只改**车速/雾浓度/天色/地平线呼吸/云相位**，⛔ 不改地形与路面几何（§4.6）。
- **D14** **PRNG 复用 E50 已验证的 `Prng` 内核**，但**独立命名空间**（§6.1）；植被按世界格坐标派生种子（§6.2）。
- **D15** 编号 `"52"`，落地为第 **28** 套；`Tier.ADV`。
- **D16** 遮挡**算矩形裁剪 + 绘制顺序**，⛔ `clipPath(`（API 22 SIGSEGV）。
- **D17** 上游 PNG **直接用**（自用不发布），但**降采样到 512²** 后入库（§3.6、§5.2）。
- **D18**（2026-10-29 所有者裁定）**FOV = 68°**，照上游（`ce = 68` `:1011`）。
  ⛔ 不用 jracer 的 100° 广角 —— 底座是 Slow Roads，FOV 跟它。
- **D19**（2026-10-29 所有者裁定）**显示名 = 「旷野公路」**。
  ⛔ 不叫「慢速公路」（与 Slow Roads 品牌过于贴脸，且我们也不"慢"）；
  ⛔ 不用「无尽公路」（v1.1 的暂定名，已弃）。
- **D20**（2026-10-29 所有者裁定）**交通车 = 0 辆**。上游有（`Math.random()` 摆位），本方案不做 ——
  频谱可视化没有"玩家"，第二个运动物体反而稀释"只有你在开"的观感。
- **D21**（2026-10-29 所有者裁定）**雾色 = 纯白 `0xFFFFFF`**，照上游（`:984-988` / `:1019`）。
  ⛔ 不取天空色 —— 与上游逐行一致优先，原型出图后若判"偏灰白"再单独提。
- **D22**（2026-10-29 所有者裁定）**纹理 = 512²**（§5.2 账 **16.9 MiB**），
  只在 LOW 档设备实测掉帧时才降到 256²（→ ≈10.9 MiB）。
- **D23**（2026-10-29 所有者裁定）**环境集 = 照抄上游全集，⛔ 一个都不自造**。
  上游实有 `2 场景 / 8 地形档 / 6 skin / 10 天气`（§4.8.1 逐条核实）。
  ⛔ **沙丘 / 沙漠不做** —— 上游没有这个 preset，不许拿 `sandMap` 拼一个出来。
  ⛔ 源码里未注册的 `Peaks`（`:10028`）/ `Scene`（`:2119`）也不做（不是 UI 可选项）。
- **D24**（2026-10-29 所有者裁定）**切换契机 = 换歌即换环境**，逐条对齐 E51 的
  「换歌曲即换城」（`LofiCityTimeline.kt:41-43`，所有者 2026-10-24 改判）：
  ①每帧比 `songId`、⛔ 不按时间切（`intervalSec = ∞`）；②目标**游标前进**、⛔ 不是 `hash(songId)`；
  ③三阶段雾白过场（淡出→重建→淡入），⛔ 不许硬切；④过场中再来的切换只记 pending（§4.8.3）。
- **D25**（2026-10-29 所有者裁定）**`Planet` 场景必须移植**（它是 UI 可选项，属"已有"）。
  ⚠️ 但它是**第二套地形生成（陨石坑）+ 第二套着色链**（surface/shale/rock/cliff/rubble，
  按 `vCurvature` 混），不是"换个色"⇒ 单列 **K9**，**⛔ 不阻塞 R1**（§4.8.2）。

### 开放项 —— 需所有者拍板（仅剩 1 项）

- ~~**O3** 是否要日/夜/天气切换~~ ✅ **已关闭**（2026-10-29）：所有者裁定「上游已有的都移植」
  +「换歌时切换」⇒ 并入 **D23** / **D24**，不再是开放项。
- **O5** **竖屏策略**：按 `minDim` 反算 FOV（保持横向视野）vs 保持纵向 FOV（竖屏看到更远的路）。
  **建议前者**（构图一致），但手机上的观感需判读（**R5**）。
  ⚠️ 这一项**不阻塞原型**（原型先做横屏），可以等 U8 时再定。

---

## 十二、当前不做的事（以及为什么）

1. ⛔ 不做 WebView / 嵌入 `slow-roads.com`（§二，已死）。
2. ⛔ **不移植 GLSL 源码本身、three.js 的 `BufferGeometry`/`Material`/`ShaderChunk` 猴补丁机制**
   —— 只移植它们的**数学**（§1.3、§3.2）。
3. ⛔ 不做真三维（无深度缓冲、无透视纹理映射）—— Canvas 2D 没有。
4. ⛔ 不做阴影贴图（D9）。
5. ⛔ 不做车辆物理 / 碰撞 / 圈速（频谱可视化没有"玩家"）。
6. ⛔ 不做新 App / 新 Activity / Manifest 变更。
7. ⛔ 不做设置页项（D15，与 E50 同纪律）。
8. ⛔ **不照搬上游的 `drawRange` 只增不减机制**（D10）—— 那是 GPU 上的免费项，在 Canvas 2D 上是纯亏。
9. ⛔ 不在本方案里写 Kotlin 代码、不动任何源文件（K1 起才动）。

---

## 十三、下一步

1. ~~所有者裁 O1/O2/O4/O6/O7~~ ✅ **已全部裁定**（2026-10-29）：
   显示名 **旷野公路**（D19）、FOV **68°**（D18）、交通车 **0 辆**（D20）、
   雾色 **纯白**（D21）、纹理 **512²**（D22）。
2. ~~裁环境集与切换契机~~ ✅ **已裁定**（2026-10-29，**D23/D24/D25**，§4.8）：
   照抄上游全集、⛔ 不自造、换歌即换环境、`Planet` 必移植但单列 **K9**。**O3 关闭，仅剩 O5**。
3. ~~建原型 + 跑 G1–G6~~ ✅ **已完成**（`docs/archive/e52-highway-preview.html`，2,002 行）：
   `--all-tiers` **21 PASS / 0 FAIL**（G1 六项各 32 例逐位相等、G2/G3/G4/G6/G14 全绿）。
4. ~~补远景位图 / 雾 / 植被并跑 G7–G13~~ ✅ **已完成**：真浏览器 1566×803 第 200 帧
   **16 PASS / 0 FAIL**（G7a 0 px、G7b 0.07%、G8a 0.00、G8b 0、G13 十条色带），
   三档 sha 互不相同（`da852ef5…` / `2b1ca66f…` / `5dd1a8b1…`）。
   ⚠️ **G5 LOD 接缝**仍未出数（原型只有位图远景，没有"三层交界"可量）⇒ 归到 K3 落地时量。
5. **下一步（按此顺序）**：
   a. **出三档截图交所有者判读**（U2 低多边形 / U4 半分辨率 / U5 雾 / U10 与既有 27 套不撞车），
      并顺带定 **O5**（竖屏策略，建议按 `minDim` 反算 FOV）。
   b. 原型闭环后按 **K1–K8** 进 Kotlin（**K9 `Planet`** 与 **K10 环境集+换歌切换** 可并行，
      但 ⛔ 都不得阻塞 R1）；**K8** 真机验收 ⇒ 本方案与原型 `git mv` 进 `docs/archive/`，登记归档行。

⚠️ **R1 仍是唯一的生死门，而且原型答不了它**：原型已把**图元数**量成 13 fill / 12 beginPath
（低于 18 的门槛），但"1,500+ 顶点/条 Path 在 API 22 上的成本"只有**真机 `dumpsys gfxinfo`** 能回答。
如果真机仍然昂贵，E52 的正确结论可能是「**这台设备上做不到**」，而不是"再调调参数"。
⛔ 到那一步要如实报告并重新选型（例如退到"固定视角斜俯视的公路"而非第一人称），
不要用降密度掩盖到看不出是公路。