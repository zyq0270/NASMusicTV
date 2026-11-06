# shan-shui-inf 移植开发方案

> **来源**：从 `docs/archive/e50-qianli-jiangshan-plan.md` v2.0 中剥离并升级为**主文档**（可开发层次）。
> 本文收录移植工作的全部核心内容：裁定（§〇）、分块图层表（§一）、上游结构（§二）、逐行映射（§三）、红线（§四）、设色三版对照（§五）、分块轮转（§六）、接缝（§七）、频谱映射与内存账（§7.5）、Kotlin 文件结构与签名（§八）、K1–K8 实施任务（§九）、门禁规格（§十）、执行顺序（§十一）。
> 配色细节（E50 §3.3）、运动学细则（E50 §4.3–4.6）、分帧烘焙（E50 §7.3）、后处理三段式（E50 §7.4）、印章描摹记录（E50 §六）、原型 harness（E50 §八）、门禁执行细节（E50 §十）、真机验收单（E50 §十一）在补充文档 `docs/archive/e50-qianli-jiangshan-plan.md`，本文只引用其 §号，⛔ 不重复全文。
>
> **状态**：v2.0 · 2026-10-28 裁定变更（所有者：「E50 的自绘效果很差，照抄移植 shan-shui-inf」）。
> ⛔ **尚未开工任何 K 任务**（2026-11-02 核对：`app/src/main` 全树检索 `QIANLI`/千里江山 **零命中** ⇒ 枚举项、中英 strings、工厂 `when` 分支、`FxCoverageScanTest` 登记**均未动**）。
>
> **上游**：`LingDong-/shan-shui-inf`（MIT，Copyright 2018 Lingdong Huang）。
> 本机副本 `D:\hxzhang\MyGithubSoftware\NasAudio\shan-shui-inf-main\index.html`（**仓库外**，⛔ 不进仓、⛔ 不打包）。
> 线上站点 `https://shan-shui-inf.lingdong.works/` 与本机副本 **SHA-256 逐字节相同**（`858792DB…2158F`，126,559 B）⇒ 本机副本就是线上跑的那份。
>
> **行号口径**：本文所有 `index.html:LINE` 行号由脚本核对（`logs_temp/shan_shui_ranges.py`，花括号配对法），并抽样与 `read` 直读对账。
> ⛔ 移植时**先搬图元、再搬场景单元、最后接构图**，不要从 `mountplanner` 倒着做。

---

> **致谢**：本效果的核心视觉内容（山体笔触、皴法、植被、建筑、构图规划、无限滚动算法）移植自 [LingDong-/shan-shui-inf](https://github.com/LingDong-/shan-shui-inf)（MIT 许可，Copyright 2018 Lingdong Huang）。感谢作者开源了如此精美的水墨无限山水。本仓仅做 JS→Kotlin 的语言层移植与必要的渲染管线适配，不篡改上游算法逻辑。

---

## 〇、裁定：放弃自绘，改移植 `shan-shui-inf`

### 0.1 为什么 v1.x 自绘失败了（机制成因）

v1.x 前四轮把力气全花在**配色收敛**（§3.3 六族面积/中位、G8 色域门禁）。
但配色只回答「颜料对不对」，v1.x 从头到尾没有解决「**笔法**」：

| 项 | v1.x 实际做法 | 后果 |
|---|---|---|
| 山体维度 | **1 条脊线** `ridgeY(x)` + 竖向渐变 `fillBand()` | 山只有剪影，没有体积 |
| 皴 | **同一条脊线的下移副本**（`dn=(0.10+0.55·i/(cun−1))·amp·2.2`，α 0.05–0.10） | 皴必然是**与山脊平行的 6 条等高线** ⇒ 读成矢量等高线，不是皴 |
| 边缘 | 无描边，`fillBand` 的平滑二次曲线 | 边缘绝对光滑 ⇒ 塑料感 |
| 矾头 | `arc()` 正圆 + 径向渐变 | 「飘在山脊外的一串球」 |
| 植被 | **零**（原型全文 `texture` 关键词 **0 次**） | 山是裸的 |

⇒ **结论：v1.x 不是「颜色不对」，是「没有笔触层」。** 再调 100 轮配色也不会像。

### 0.2 上游事实卡（已逐项核过源码）

| 项 | 实测 | 出处 |
|---|---|---|
| 许可 | **MIT** | `LICENSE`（1,071 B） |
| 体量 | 单文件 `index.html`：**126,559 B / 4,361 行**（非空 4,035 行）；无构建、无依赖、**零外部资产** | 仓库树 |
| 输出形态 | **SVG 字符串**发射（`poly()` 返回 `<polyline points='…'>`） | `:485-515` |
| 「无限」的实现 | ⭐ **惰性分块流**：`MEM.chunks[]` + `chunkloader()` 按可视窗口 ±`MEM.cwid` **现场生成**，`chunkrender()` 拼字符串 | `:3845-3894`、`:3973-3984` |
| 滚动 | DOM `window.scrollX` → 重设 `viewBox`；`autoxcroll` 每 2 s 推进 `INC_STEP` | `:3995-4007`、`:4067-4074` |
| ⛔ **不是**周期平铺 | 每个 chunk **唯一、永不重复**；没有周期 `W`，**没有接缝问题** | 同上 |
| 构图规划 | `mountplanner(xmin,xmax)`：`xstep=5` 扫过，`locmax` 做局部极大抑制，`chadd` 保证最小间距 10 | `:3706-3800` |
| 噪声 | p5.js 的 Perlin 移植（`Noise`） | `:90-176` |

### 0.3 保留 / 作废清单

**✅ 保留（v1.x 的资产，⛔ 不要重做）**

- §3.3 六族实测色表 + G8 色域判据（移植后仍要用它把黑白线稿**染**成青绿）。
  ⚠️ 色表最终是否启用取决于 **D17 三版对照判读**（V-B/V-C 用它；若选 V-A 则作废）。
- §4.3 分层速度表 / §4.4 音乐只推 1.3× / §4.5 呼吸 / §4.6 时间单自变量与生命周期铁律。
- §六 印章全套（D6/D7/D8）。
- §7.3 分帧烘焙、§7.4 后处理三段式、§八 原型 harness 分工、§十 门禁纪律、§十一 验收单。

**⛔ 作废（v2.0 不再执行）**

| 作废项 | 原位置 | 取代者 |
|---|---|---|
| 「六层渐变带」图层表 | E50 §3.1 | **§3.1′ 分块图层表**（本文 §一） |
| `ImageShader(TileMode.Repeated)` 平铺 | E50 §4.1 | **§4.1′ 分块轮转**（本文 §五） |
| 有限傅里叶周期构造 / G1·G2 接缝门禁 | E50 §4.2、§8.2 | **整节作废**（分块流没有接缝） |
| `ridgeY` / `fillBand` / `cun` / `fanTou` 参数体系 | E50 §3.2、原型 | `mountain()` / `texture()` / `stroke()` / `blob()` |
| 11 ops/帧 的账 | E50 §7.1 | **§7.1′ 必须重算** |
| 10 张全周期带的内存账 | E50 §7.2 | **§7.2′ 分块缓冲账** |

### 0.4 架构变更（一句话）

> **v1.x**：5 个**周期位图**按不同速度 `ImageShader(Repeated)` 平铺 → 1 op/层，但**会看出重复**。
> **v2.0**：每层 **N 个唯一分块**（默认 2）按层速度轮转 + 惰性再生成 → 1 blit/块/层，**永不重复**。

⚠️ 这条变更同时解决三件事：① **观感**（不重复）；② **内存**（不再需要 10 张全周期带）；
③ **保真**（`mountplanner` 的构图逻辑可原样搬，⛔ 不用再发明一套"怎么摆山"）。

⚠️ **代价（必须写进预算，⛔ 不许含糊）**：分块是**运行时**生成的，所以它把 v1.x 的
「一次烘焙、永远免费」变成了「**滚动到边界才生成**」。⇒ 必须证明**单块生成耗时 < 一帧预算**，
否则滚动会周期性卡顿。这条是 v2.0 的**头号风险**（E50 §十二 R1），判据见 E50 §8.2 的 **G15**。

### 0.5 ⭐ 保真纪律：逐行移植为默认，改造最小化且须论证

> **核心原则（所有者 2026-11-02 明示）**：「这个效果要保证效果，必要时逐行移植上游代码。」

**默认模式 = 逐行照搬。** 上游 3,500 行 JS 中，除 §四红线和 §3.3 列出的必改点之外，
**其余全部逐行搬**，⛔ 不做任何"优化"、重构、命名习惯调整或"顺手改"。理由：

- 上游的随机序列（`Prng`）、噪声种子、点阵布局、笔触几何、构图规划是**互相耦合**的。
  任何一处改动都会导致后续所有依赖它的结构发生偏移 ⇒ 无法与上游对账，也无法证明"照抄了"。
- 门禁（§十 G-PORT-1~7）的**黄金向量**全部来自上游的逐位输出。逐行搬 ⇒ 黄金向量天然对齐；
  任何"优化"都可能破坏逐位一致 ⇒ 门禁必红。

**改造只允许在以下四类最小面发生，且每处改造必须在代码 KDoc 中写明「改了什么、为什么、上游对应行号」：**

| 改造面 | 上游形态 | Kotlin 落点 | 论证 |
|---|---|---|---|
| ① SVG 字符串 → `List<Point>` | `poly()` 返回 `<polyline points='…'>` 字符串 | port 包返回 `List<Point>`，渲染器侧转 `android.graphics.Path` | 上游的字符串发射是 SVG 输出机制的副产物，不是算法本身；点列数据完全等价 |
| ② 全局 `Math.random` → `rnd()` | `:54-56` 覆写 `Math.random = () => Prng.next()` | `ShanShuiPrng.rnd()` | 上游的覆写是全局副作用；Kotlin 无全局覆写能力，改为显式传参 ⇒ 调用次序天然一致 |
| ③ 不可移植的子图（`Man` / `triangulate` / UI） | 人物 / 三角化 / DOM | 裁剪 + 注释标记 | 见 §四红线；裁剪处必须在 KDoc 写明「上游 `:LINE` 此处调用 `X`，不移植，用 `Y` 替代」 |
| ④ JS `Array` → Kotlin `List`/`FloatArray` | `Array` / `JSON.stringify` | `List<T>` / `FloatArray(2)` / `Double` 累加 | 纯数据结构映射，不改变数值语义 |

**⛔ 禁止的"顺手改"清单：**

- ⛔ 不改变量名（上游叫 `wid` 就叫 `wid`，Kotlin 侧不加 `Width` 前缀）
- ⛔ 不合并重复代码（上游有 8 个 `treeNN` 就是 8 个，不抽 `treeGen(id)`）
- ⛔ 不"提前优化"（上游每次分配 `new Array` 就照搬，⛔ 不改成复用）
- ⛔ 不把 `for` 改成 `forEach` / 列表推导式（逐行搬就是逐行搬）
- ⛔ 不调整函数签名参数顺序（上游 `mountain(xoff,yoff,seed,hei,wid,tex,veg,ret,col)` 就照这个顺序）
- ⛔ 不改默认值的数值（上游 `wid=2` 就是 `2`，⛔ 不"调大一点"）

**每行移植代码的 KDoc 必须包含：**
1. 上游行号区间（如 `// 上游 index.html:1804-2081`）
2. 移植状态（`照抄` / `改造-①` / `改造-②` / `改造-③` / `改造-④`）
3. 若是改造，写明理由（如 `// 改造-①：SVG 字符串 → List<Point>，渲染器侧转 android.graphics.Path`）

**门禁验证保真（§十）：**
- **G-PORT-1**（Prng 黄金向量）逐位相等 ⇒ 证明 `Prng` 逐行搬对了
- **G-PORT-7**（负向自证：用 Long / 漏预热 / Horner 形式 → 必红）⇒ 证明门禁能咬住偏离
- 新增 **G-PORT-8**：对每个 port 函数做**结构性断言**（点列长度、坐标范围、NaN 检查），
  数值与上游同种子同参数的输出一致（由原型 harness 再生值，⛔ 不许手抄）

---

## 一、分块图层表（§3.1′）

> ⛔ 遮挡只由**绘制顺序**产生（API 22 创维 `Region::createTJunctionFreeRegion` 原生 SIGSEGV，全仓红线），
> ⛔ 不写任何裁切/遮罩代码。

每层从「1 张周期位图」变成「**N 张唯一分块位图**」，滚动到边界时惰性再生成（§五）。

| 序 | 层 | 载体（Kotlin） | 每帧 op | 分块数 | 频谱驱动 |
|---|---|---|---|---|---|
| L0 | **绢底天幕** | 全屏竖向渐变 + 绢纹 + 做旧（**v1.x 保留**，⛔ 不分块） | 1 | — | `sectionEnergy` 只改覆盖层 α |
| L1 | **远景** | `ShanShui.distMount()` 淡墨远山 + 远雾 | **N** | N | α / 速度 |
| L2 | **中景主山** | `ShanShui.mountain()`（青绿染色） | **N** | N | — |
| L2′ | **轮廓光** | `glint` 独立位图（⭐ 与 L2 **同种子同相位**） | **N** | N | ✅ `mid` → α（**主"有音乐"信号**） |
| L3 | **近景** | `flatMount()` + `rock()` + 树 | **N** | N | ✅ 轻微 α |
| L4 | **江水** | `water()` 底纹 | **N** | N | ✅ 波幅 α |
| L4′ | **江水高光** | `water()` 高光丝 | **N** | N | ✅（LOW 档可省） |
| L5 | **光尘** | 点阵**平铺**位图（v1.x 保留 —— 周期对细碎光点无害） | 1 | — | ✅ `treble` → α + 相位 |
| L6 | **grade** | 暗角 + 颗粒（三段式，同 E46–E49） | 1 | — | ❌ |
| L7 | **印章** | 朱砂印，屏幕固定坐标 | 1 | — | ❌ 恒定（E50 §六.3） |

**图元账：`ops = 4 + 6N`**

| N | ops/帧 | 判定 |
|---|---|---|
| **2** | **16** | ✅ 进创维 60fps 模型（≤18） |
| 3 | **22** | ⚠️ 只能保 30fps 线 |
| 4 | 28 | ⛔ 不采用 |

⇒ **默认 `N = 2`**，每块宽 ≥ `1.05 × 屏宽`（保证"覆盖整屏时另一块可在后台重生"）。
⚠️ 18 op 门槛来自 v1.x 真机线性模型，在"多小 op 合并成少大 blit"后会**过预测** ⇒ **必须按 §7.1′ 重测斜率**，⛔ 不许直接拿 16 当"够用"。

⚠️ L7 印章**必须画在 grade 之后**，否则暗角会把朱砂红一起压暗、四角不匀（D8）—— 这条**不变**。
⚠️ LOW 档省 L4′（−N op），但 α 照常计算（⛔ 不要连 α 一起省）。

> ⭐ 运动学细节（分层速度表、音乐只推 1.3×、呼吸幅度、时间与生命周期铁律）详见 E50 §4.3–4.6。

---

## 二、上游文件结构

**7 个 `<script>` 段（`id=` 实测）**

| 行 | `id` | 内容 | 移植？ |
|---|---|---|---|
| 1 | `PRNG` | `Prng`（`:2-53`）+ ⭐ **`:54-56` 全局覆写 `Math.random = () => Prng.next()`** | ✅ |
| 88 | `PerlinNoise` | `Noise`（`:90-176`），p5.js 的 Perlin 移植 | ✅ |
| 206 | `PolyTools` | 多边形谓词 + 三角化（`:207-382`） | ⛔ 不移植（见 §四红线） |
| 385 | `Util` | 数值 helper + `bezmh`/`poly` | ✅ |
| 513 | （匿名） | ⭐ `stroke`/`blob`/`div`/`texture`/`foot` + `Tree`/`Mount`/`Arch`/`Man` + `water`/`mountplanner`/`chunkloader` | ✅（除 `Man`） |
| 4043 | `downloader` | SVG 下载 | ⛔ 不移植 |
| 4058 | `UI` | viewBox / 滚动 / 演示模式 | ⛔ 不移植 |

---

## 三、逐行移植映射

### 3.1 图元层（必须原样搬，⭐ 这是"像"的全部来源）

| 上游 | 行 | 干什么（机制） | Kotlin 落点 | 难度 |
|---|---|---|---|---|
| ⭐ `Prng` | **2-53** | **BBS 式乘方取模 PRNG**：`p=999979`、`q=999983`、`m=p·q`；`next()` = `s = s² mod m; return s/m`；`seed(x)` 先 `hash(x)` 再**预热 10 次**（`:30-32`）。`:54-56` 把 `Math.random` 全局指向它 | `ShanShuiPrng`（`Double` 全程） | ⭐ 见 §3.2-② —— **必须逐位搬** |
| `stroke(ptlist,args)` | **516-576** | ⭐ **变宽笔触 ribbon**：逐点取前后段平均法线 `a=(a1+a2)/2`，向两侧偏移 `w=wid·fun(i/n)`，且 `w ← w·(1−noi)+w·noi·Noise.noise(i·0.5,n0)` ⇒ **干湿变化**；最后闭合成**填充多边形** | `ShanShuiBrush.ribbon(pts, wid, fun, noi): List<Point>` | ⭐ 纯多边形数学，逐行可搬 |
| `blob(x,y,args)` | **578-625** | **笔触点 / 叶簇**：纺锤形轮廓 `xo=len/2−|p−1|·len`、`yo=fun(p)·wid/2`，`reso=20` | `ShanShuiBrush.dab(...)` | ⭐ 同上 |
| `div(ptlist,n)` | **627-651** | 折线等分（`texture` 的前置） | `ShanShuiPath.divide(...)` | ⭐ 同上 |
| `texture(ptlist,args)` | **653-741** | ⭐ **皴**：沿坡面格点铺短笔触（`wid 1.5`），`sha` 控疏密；⚠️ `ret != 0` 时**返回 `texlist` 而不是字符串**（`:736`） | `ShanShuiBrush.cun(...)` | ⭐ 同上 |
| `foot(ptlist,args)` | **1728-1802** | 山脚收笔（把坡面收到一条淡墨线） | `ShanShuiBrush.foot(...)` | 同上 |
| `bezmh(P,w)` | **445-483** | 过中点的二次贝塞尔采样（`pl=20`） | `ShanShuiPath.bezmh(...)` | ⭐ 约 39 行 |
| `poly(plist,args)` | **485-510** | ⛔ **只借语义**：`fil/str/wid` → `Paint.style`；**返回字符串这件事不搬** | `ShanShuiPath.fillPath(...)` | 改造（见 §3.2-①） |
| `Noise` | **90-176**（`noise` 本体 `102-168`） | p5.js Perlin 移植：`PERLIN_SIZE=4095`、4 octaves、falloff 0.5 | `ShanShuiNoise`（对象） | ⭐ 约 87 行，逐行可搬 |

> ⭐ **关键结构性事实**：全文件**只有 3 个"发射"原语** —— `poly` / `stroke` / `texture`。
> 所有 100+ 个生成器都是"先算点列、再 `canv += poly(...)`"两段式 ⇒ **字符串→`Path` 的改造面是 3 个函数，不是 121 个**。
> 这是本移植工作量可控的根本原因。

### 3.2 场景单元层

| 上游 | 行 | 行数 | Kotlin | 备注 |
|---|---|---|---|---|
| `Tree` 命名空间 | **743-1724** | 982 | `ShanShuiTree` | 内含共享递归骨架 `branch(args)`（`:919` 起，被多个 `treeNN` 复用）、`twig`、`barkify` |
| `tree01…tree08` | **744/803/842/1191/1279/1374/1490/1582** | 59/39/76/87/94/115/91/143 | `ShanShuiTree.kind1…8()` | ⚠️ 8 种全搬会撑爆体量 ⇒ **先搬 `tree01/02/03`**（开放项 O8）；`branch()` 骨架共享，多搬一种边际成本远小于 59~143 行 |
| `Mount` 命名空间 | **1727-2522** | 796 | `ShanShui` | |
| `mountain()` | **1804-2081** | 278 | `ShanShui.mountain()` | ⭐ 主山：**10 行 × 50 列点阵**（`reso=[10,50]`），第 j 行按 `p=1−j/reso[0]` 缩放并各自乘 Perlin ⇒ **山有体积**（v1.x 缺的就是这个） |
| ⭐ `vegetate(tf,growth,proof)` | **1838-1852**（**嵌在 `mountain()` 内**） | 15 | `ShanShui.vegetate(...)` | ⭐ **植被放置规则**：扫自己的 `ptlist` 网格 → `growthRule(i,j)` 选点 → `proofRule(veglist,i)` 去重叠 → 逐个 `treeFunc(x,y)`。**在 `mountain()` 里被调 8 次**（`:1854/1900/1920/1961/1991/2022/2042/2060`）⇒ v1.x「植被为零」的正面解法 |
| `flatMount()` | **2083-2232** | 150 | `ShanShui.flatMount()` | 平缓坡 |
| `flatDec()` | **2234-2367** | 134 | `ShanShui.flatDec()` | 坡面装饰。⚠️ 内部调用 `Tree.tree04/05/08` —— 不在移植范围（O8），须替换或降级 |
| `distMount()` | **2369-2431** | 63 | `ShanShui.distMount()` | 远山（淡）。⚠️ 内部调用 `PolyTools.triangulate`（`:2419-2428`）—— 不移植（§四红线），须用绘制顺序替代三角化 |
| `rock()` | **2433-2522** | 90 | `ShanShui.rock()` | 矶石（不是 569 行 —— 那是把后面的 `Arch` 也算进去了） |
| `Arch` 命名空间 | **2525-3317** | 793 | `ShanShuiArch` | `flip`/`hut`/`box`/`deco`/`rail`/`roof`/`opf`/`pagroof` |
| `hut()` | **2542-2607** | 66 | `ShanShuiArch.hut()` | 茅屋 |
| `arch01/02` | **3002-3059** / **3056-3203** | 58/148 | `ShanShuiArch.kind1/2()` | 楼阁（含 `pagroof` 飞檐）。⚠️ `arch01` 内部调用 `Man.man`（`:3035-3047`）—— 不移植（§四红线），须移除人物调用 |
| `boat01()` | **3209-3248** | 40 | `ShanShuiArch.boat()` | ⚠️ **O7 未裁**（会引入第二个运动物体） |
| `Man` 命名空间 | **3324-3665** | 343 | ⛔ **不移植** | 人物。千里江山图里人物是"米粒点"，本仓用不上；且是全文件唯一带仿射变换栈（`gpar`/`grot`/`gpos`）的部分 |
| `water()` | **3667-3704** | 38 | `ShanShui.water()` | 江水波纹（**只有 38 行**，比预估小） |
| `mountplanner()` | **3706-3814** | 109 | `ShanShuiPlanner.plan(xmin,xmax)` | ⭐ 构图：`samp=0.03`、`xstep=5` 扫描、`locmax()` 半径 2 的局部极大抑制（阈值 0.3）、`chadd()` 最小间距 **10**、远山每 1000px 一座、`flatMount` 填充概率 1%、`boat` 概率 20% 且间距 **400** |
| `MEM` | **3816-3827** | 12 | `ShanShuiPlanner.State` | ⭐ `cwid: 512`（**块宽 512 px**）、`windx: 3000`、`windy: 800`（**上游视口 3000×800**）、`planmtx`（占位表） |
| `chunkloader()` | **3845-3971** | 127 | `ShanShuiPlanner.load(...)` | ⭐ 惰性双向生成；⚠️ 插入时**按 `y` 升序排序**（`:3846-3874`）⇒ `chunkrender` 拼接即**远→近**的画家序，这正是水墨层叠读对的原因。⚠️ 内部调用 `Arch.boat01` —— 不在移植范围，须裁剪 |
| `chunkrender()` | **3973-3984** | 12 | `ShanShuiPlanner.render(...)` | 取 `±cwid` 内的块拼字符串 |

### 3.3 必须**改造**而不能照搬的四处（移植的真实工作量）

1. **SVG 字符串 → `List<Point>`（port 包）→ `android.graphics.Path`（渲染器侧烘焙期）。** `poly()`(`:485-510`) 返回 `<polyline points='…'>` 字符串；
    port 包内直接累积**纯数据点列** `List<Point>`（`Point = FloatArray(2)`），⛔ 不做字符串中间层 —— 单块上千个点，字符串拼接是纯浪费。
    渲染器侧（烘焙期）拿到 `List<Point>` 后再转 `android.graphics.Path` + `Paint` 画进位图（见 §八 开头「`Path` 的处理」）。
    ⭐ 改造面**只有 3 个函数**（`poly`/`stroke`/`texture`），其余生成器原样搬。

2. ⭐ **PRNG：把 `Prng` 原样搬，⛔ 不要换 `mulberry32`。**
    上游在 `:54-56` **全局覆写** `Math.random = () => Prng.next()` ⇒ 全文 **155 处 `Math.random()` 调用点全部已走种子流**，
    调用次序天然与上游一致 —— 我们**一行调用点都不用改**，只提供 `rnd()`。
    ⛔ **换 `mulberry32` 的代价**：序列不同 ⇒ 山形不同 ⇒ **再也无法与浏览器对账**（G4/G13′ 只剩"自己跟自己一致"，
    证明不了"照抄了"）。⇒ **D15 已按此改判**。

    ⚠️ **三个必须踩准的坑**：
    - `hash(x)` = `btoa(JSON.stringify(x))` 的字符按 `charCode·128^i` 累加 ⇒ **溢出 2^53**（实测 `hash(1234) = 3.4610482573896268e16`）。
      ⇒ Kotlin 必须用 **`Double`** 累加、**按下标升序、逐项 `+`**；⛔ 用 `Long`/`BigInteger`（值不同）、⛔ 改写成 Horner 形式（舍入不同）。
    - `next()` 的 `s = s² mod m`：`s` 可达 1e12、`s²` 可达 1e24，**同样溢出 2^53** ⇒ 依然只能 `Double`。
    - `seed(x)` 末尾**预热 10 次 `next()`**（`:30-32`）⇒ 漏掉这 10 次，整条序列错位。

    ✅ **已实测可逐位复现**：`logs_temp/prng_check.js`（node v24.13.1）与 `logs_temp/prng_kotlin_equiv.py`（IEEE-754 double 等价实现）
    在 `num:1234` / `str:1234` / `num:7` 三例上 **hash、`sAfterSeed`、前 8 个输出全部逐位相等**（`RESULT: PASS`）。
    **黄金向量**（seed=`1234`，数值型）：`sAfterSeed = 334869857393`，
    前 4 个输出 `0.5179214018993742 / 0.19101042199684515 / 0.1481747547047804 / 0.8586372206878526`。
    ⚠️ **`seed(1234)` 与 `seed("1234")` 不同**（`JSON.stringify` 给出 `1234` vs `"1234"` ⇒ base64 不同 ⇒ 种子不同）。

3. **`Noise` → Kotlin**：约 87 行逐行搬。⚠️ `noiseSeed()` 在上游是**死代码**（全文件零调用），实际运行时 `Noise` 的 perlin 表用 `Math.random()` 初始化 ⇒ 与 `Prng` 共用同一种子流。`noiseSeed()` 照搬但不加调用。

4. **无限流 → 分块位图**：见 §五。⚠️ 上游 `MEM.cwid = 512`、视口 `3000×800`；我们的块宽是 `≥1.05×屏宽`（约 2016 px），
    比上游的 512 大得多 ⇒ **单块生成耗时也按比例更大**（R1 的直接来源）。

---

## 四、⛔ 三条移植红线

- **非凸 Path 只在烘焙期画。** `stroke()` 的 ribbon 与 `mountain()` 的坡面填充都是**非凸、可能自交**的多边形。
  E46 真机定位：`concat` × 非凸 ⇒ **80 帧 0 像素**（`TripodFillMechanismGateTest` 守）。
  ⇒ 全部在**烘焙期**画进位图，逐帧只 blit。⛔ 不要"顺手"改成逐帧画。

- **⛔ 不移植 `PolyTools` 的三角化**（`bestEar`/`shatter`，`:322-382`，约 60 行；整个 `PolyTools` 段 `:207-382`，176 行）。
  它存在的理由是 **SVG 里没有 clip**，必须把多边形拆成三角形来做"形状间遮挡"。
  我们靠**绘制顺序 + 烘焙期一次性合成**（§一）⇒ 不需要它。
  ⚠️ 但 `PolyTools` 里的 `distance`/`mapval`/`loopNoise`/`randChoice`/`normRand`/`wtrand`/`randGaussian`（`:386-444`）**要搬**，它们在 `Util` 段里。
  ⚠️ ⚠️ **`distMount()` 内部调用 `PolyTools.triangulate`（`:2419-2428`）—— 不移植三角化后，远山的渐变填充须改为直接 `poly()` 填充（不做三角化分解），或用多条 `stroke()` 叠加模拟渐变。这是移植时的必改点，⛔ 不要留 `triangulate` 调用。**

- **⛔ 不移植 UI / 滚轮 / 鼠标 / 下载**（`:4043-4361`，约 319 行）。
- **⛔ 不移植 `Man` 命名空间**（人物，`:3324-3665`，343 行）。千里江山图里人物是"米粒点"，本仓用不上；
  且它是全文件里唯一带仿射变换栈（`gpar`/`grot`/`gpos`）的部分，搬进来只会增加出错面。
  ⚠️ ⚠️ **`arch01()` 内部调用 `Man.man`（`:3035-3047`）—— 移植 `arch01` 时须移除人物调用（保留建筑主体）。**

⇒ **净移植量 ≈ 3,500 行 JS**（4,361 − 三角化 159 − UI/下载 319 − 人物 343）**→ Kotlin 约 3,000–3,300 行**。
⚠️ 这个数字比 v1.x 的"≈2,400"大 —— 因为**上游确实比我们原先估计的更能用**（`water` 只有 38 行、`rock` 只有 90 行）。
⛔ 不要拿 2,400 当排期依据。

---

## 五、设色：上游是灰阶线稿，青绿是我们染的 —— 三版对照后裁（D17）

### 事实卡（已全量扫过源码）

| 项 | 实测 |
|---|---|
| `rgba(` 字面量 | **81 处**；去重后几乎全是 `rgba(100,100,100,0.3~0.9)` 与 `rgba(0,0,0,0.1~0.4)` |
| 具名色 | `white` **17 次**（叶簇 / 水花留白）、`red` **3 次**（印章）、`none` 21 次 |
| 十六进制色 | **0 处** |

⇒ **上游是纯灰阶线稿引擎，它没有"青绿"这个概念。** 且它 README 自述灵感**同时**含
《富春山居图》与王希孟《千里江山图》—— 这恰好说明：**两者结构同源、设色不同**。
所以「全部采用 shan-shui-inf 的设定」= 得到一幅**水墨**，不是千里江山图（后果见 D17）。

### 三版对照（几何完全共享，只差染色层）

| 版本 | 染色规则 | 作用 | 主要风险 |
|---|---|---|---|
| **V-A 对照组** | ⛔ 一行不改：原样 `rgba(100,100,100,α)` | **排除"是几何的问题"** —— 证明上游几何本身读起来是对的。没有这一版，V-B/V-C 好看时分不清是几何的功劳还是染色的功劳 | 若最终选它 ⇒ §3.3 色表与 G8 作废、主题退化为「水墨长卷」须改名（D17） |
| **V-B 中道**（= 原 D16） | 按**坡面行号 j** 染 §3.3 六族色表，皴密度**不动** | 保留主题身份（矿物青绿 + 绢本暖底） | 上游是**密皴**（`texture()` 铺满短笔触）；重彩盖密皴容易读成**现代插画**，撞 §3.4 |
| **V-C 大青绿** | V-B **+** 分层平涂设色（上坡石青/石绿平涂、山脚赭石）**+** 皴密度下调 | 最接近千里江山图「色重皴轻」 | 工作量最大：平涂层与皴层的**遮挡顺序**要重排；皴下调幅度待 harness 再生 |

### 三版必须共享的东西（否则对照不成立）

同一 `Prng` 种子、同一 `Noise`、同一 `mountplanner` 输出、同一分块顺序与块宽。
⇒ 三张图的**像素差异只可能来自 `col` 与笔触密度** ⇒ 这是**单变量对照**，判读才有意义。
⚠️ 判读用**同帧、同 seed、同 crop**（并排 + 局部放大各一组）；⛔ 三张图各用各的种子。

### V-B / V-C 的染法硬约束

- **按坡段取色，⛔ 按 y 取色。** 颜色由该笔触在坡面点阵里的**行号 j** 决定（`j=0` 山脊 → `j=R` 山脚）。
  这正是 §3.3 ⚠️ 条「站表必须按渐变坐标排」的**根治**：v1.x 只能按 y 排，因为山只有一条脊线。
- **每个笔触一次取色，⛔ 不在位图上叠全帧色层。** 染色发生在 `stroke()/poly()` 的 `col` 参数上；
  叠一层色相覆盖层会把皴的干湿变化一起洗平（= 又退回 v1.x 的"一块色"）。
  ⚠️ **V-C 的"平涂"不违反这条**：它是**坡面多边形的 `fill`**，画在皴**之前**、仍在烘焙期，
  不是全帧覆盖层，也不洗掉任何笔触。
- **上游的 `white` 必须换掉。** 它用于叶簇与水花（17 次），在青绿底上会读成**荧光白点**（撞 §3.4-8）
  ⇒ 改**绢白 / 淡青**。⚠️ 这一条只对 V-B/V-C 成立，V-A 保持原样。

**判读后的收口**：三版并排交给所有者，**一次定案**。⛔ 不要出现"V-B 与 V-C 各取一半再调"——
那等于又回到 v1.x 那种"凭感觉改配色"的循环（§〇.1 的成因就是它）。

---

## 六、分块轮转（§4.1′）

> 上游 `shan-shui-inf` 的「无限」是 **惰性分块流**：`chunkloader()` 在可视窗口 ±`MEM.cwid` 内现场生成
> 唯一 chunk，`chunkrender()` 拼接（`index.html:3845-3894`、`:3973-3984`）。
> ⛔ **它没有周期 `W`，也没有接缝问题** —— 每个 chunk 都不一样。

Kotlin 侧不能拼 SVG 字符串，改拼**位图块**：

```
每层维护一个 ring buffer: blocks[0..N-1]，每块 = 一张 Bitmap + 世界坐标 x0
逐帧：offset += v_layer · dt
      当某块右缘 < 视口左缘 ⇒ 该块作废，重新生成到最右侧（x0 += N·Wb）
      绘制：for b in blocks: canvas.drawBitmap(b.bmp, b.x0 − offset, y0·H, paint)
```

- **块宽 `Wb ≥ 1.05 × 屏宽`** ⇒ 任一时刻「覆盖整屏」与「另一块在后台重生」可以并存，⛔ 不出现空白。
- **N = 2**（§一的 ops 账）⇒ 视口内永远只有 2 个唯一块，**重复周期 = 2 屏**，
  远大于 v1.x 的 `1.0–2.0 屏` ⇒ 观感上是"不重复"。
- ⛔⛔ **位移绝不靠 `Shader.setLocalMatrix`**：E43 真机实测在硬件画布上**静默失效**（画了个不偏移的）。
  ⇒ 一律走 `drawBitmap(bmp, x, y, paint)` 的 x 参数（或 `canvas.translate`），⛔ 不留 local matrix 代码。
- ⚠️ `drawIntoCanvas { }` 里成对 `save/restore`（⛔ 逐图元 `save/restore`，E48 红线）。
- ⚠️ **块高 = 层带高**，⛔ 不是全屏（远景只占 0.46H，见 E50 §3.2）。⇒ 每块内存 = `Wb × bandH × 4`。

### 重生必须与渲染解耦（本方案头号风险 R1）

- ⛔ 不许在 `draw()` 里同步生成一块（单块 2,400 行 JS 的等价 Kotlin 工作不可能塞进 16ms）。
- ✅ 采用 **`bootPending` 同族的两段式**（E50 §7.3 已有一套）：`draw()` 只置 `pending` 标志，
  生成在**后续帧的预算片**里推进（每帧最多 `BLOCK_BUDGET_MS`），生成完成前该块**画上一轮的旧块**
  （旧块内容不同但风格一致 ⇒ 读作"山在变"而不是"空白"）。
- ⚠️ 若预算内做不完，**降级顺序**：先砍 `rock`/`hut` 装饰 → 再砍 `tree` → 最后才降块宽。
  ⛔ 不许"生成失败就画空白"（用户读成黑屏）。

> ⭐ 块池的具体 Kotlin 签名与两段式重生机制见本文 §八 `QianliBlockPool`。

---

## 七、接缝：从"数学闭合"退化为"块间风格一致"（§4.2′）

⛔ **v1.x 的 G1/G2（周期闭合、相位对账）整节作废** —— 分块流没有周期，接缝根本不存在。

但换来了一个新的失败模式：**相邻两块画风不一致**（例如一块树多、一块树少、或山脊高度突变）。
⇒ 判据改为 **G1′ 块间连续性**（E50 §8.2）：

- 取任意相邻两块在接缝处的**同一行**，比较「该行不透明像素的左/右延伸长度」与「平均明度」，
  两者之差 ≤ 内部同类位置的 p90（⛔ 不设绝对阈值 —— 阈值必须由 harness 再生）。
- ⭐ 更本质的一条：`mountplanner` 是**连续扫描**的（`xstep=5`，`MEM.planmtx` 记录占位），
  只要 `xmin/xmax` 首尾相接，**构图本身天然连续** ⇒ 块间不一致只可能来自 **PRNG 被重置**。
  ⇒ **G4′ 判据**：块 k 的生成必须用 `seed(base + k)` 的**连续流**，⛔ 不许每块 `seed(固定值)`。

---

## §7.5 频谱 → 山水映射（权威表）

> 本节的通道映射表、平滑参数、α 下限均从 E50 §5 重列（因为本文是权威）；
> 判据（G-通道 / G-平滑）的执行细节见 E50 §十。

### §7.5.1 通道来源表

| 视觉量（层） | 音频源 | 变化区间 | 说明 |
|---|---|---|---|
| L1 雾带 α 与流速 | `bass`（20–250Hz，`BASS_END=39`） | α `0.10 → 0.30`；流速 `×1.0 → ×1.3` | 低频只推**雾和水**，⛔ 不推山 |
| L4 江水波幅 α | `bass` | `0.16 → 0.34` | 与雾**同源不同曲线**（水响应快于雾） |
| L2 轮廓淡金光晕 α | `mid`（250Hz–3kHz，`MID_END=55`） | **`0.10 → 0.34`** | ⭐ 本主题唯一"发光"通道，也是最主要的"有音乐"信号 |
| L2 青绿饱和偏移 | `mid` | `ΔS ≤ 0.06`、ΔH ≤ 4° | 靠一张**饱和覆盖层**的 α 实现，⛔ 不逐帧重烘 |
| L3 近景雾气 α | `mid` | `0.06 → 0.16` | 需求「山脚雾气亮度」的落点 |
| L5 光尘 α + 平铺相位 | `treble`（3k–20kHz） | α `0.05 → 0.22`；相位 ±40px | 需求「细碎光点」落点；⛔ 飞鸟（撞 E49，见 O2） |
| 各层速度 | `energy`（≤1.3×，E50 §4.4） | — | — |
| L0 冷暖 / 做旧强度 | `sectionEnergy`（8s 均值） | 色温 ≤ 2 单位；颗粒 α `0.026 → 0.034` | "段落响度决定画面情绪"，无节奏性 |
| 整体呼吸 | `sectionEnergy` + `pulse` | scale `1.000 → 1.030`（E50 §4.5） | — |

### §7.5.2 ⛔ 不读 `frame.spectrum`（D5 的技术面）

`AudioFrame.spectrum`（64 柱，已 gamma 压缩）是**显示通道**。本主题一条都不读：

- 拿它画任何东西 ⇒ 必然出现"64 个随频率排列的几何体" = 柱状频谱换了个方向（需求 §5.1 禁止项）；
- 拿它驱动律动 ⇒ 强弱差异被 `DISPLAY_GAMMA = 0.75` 压扁（历史 BUG ⑨-b，`AudioFrame.kt` 类头明写「二者不可混用」）。

⇒ 律动只用 `bass / mid / treble / energy / sectionEnergy / pulse`，
需要"鼓点强弱差异"时才用 `bassRaw`（本主题**不需要**，因为⛔ 不跟拍）。
⚠️ 这条要写进门禁（G-通道：源码扫描渲染器，出现 `frame.spectrum` / `.spectrum[` 即失败）。

### §7.5.3 平滑参数表（attack 慢、release 更慢）

既有 `AudioSmoother(attack, release)` 默认 `0.35 / 0.06`（"鼓点要快 attack"）。本主题**刻意改慢**：

| 通道 | attack | release | 说明 |
|---|---|---|---|
| 雾 / 水 / 速度 | 0.10 | 0.035 | 几帧才见得起势，落得更慢 |
| 淡金光晕 | 0.18 | 0.05 | 唯一允许稍快的一路（"有音乐"的主信号） |
| 光尘 | 0.24 | 0.07 | 最亮的一路，但仍⛔ 不用 0.35 以上 |
| 段落 / 呼吸 | 0.04 | 0.02 | 情绪级，几乎无感 |

判据（进 G-平滑）：**连续 60 帧内任一 α 的单帧变化量 ≤ 其总动态范围的 6%** ⇒ 物理上排除了频闪。

### §7.5.4 α 下限表（无音频时的优雅降级）

- 停止播放/无音轨时分析层仍在输出 `pulse`（快起慢落脉冲）与呼吸；本主题以它作**降级动画源**
  （与 E42 星空「静音不全黑」同一路子）。
- **α 下限表**（⛔ 任何通道不得把对应层降到 0）：
  `L1 雾 ≥ 0.10`、`L2 光晕 ≥ 0.10`、`L4 水 ≥ 0.16`、`L5 光尘 ≥ 0.05`、`L0/L2/L3 山体 ≡ 1.0`。
  ⇒ 静音画面 = 一条缓缓流动、带极淡金光的青绿长卷。
- ⛔ 不弹权限框、⛔ 不报错、⛔ 不黑屏 —— 因为本主题**根本没有音频输入这条路**（E50 裁定 5），
  需求 §八.4 的"降级"在本仓是**默认态**。

---

## §7.5.5 分块缓冲内存账

> 几何算得的预算，⛔ 不是实测。单位 MiB（`w·h·4 / 1048576`）。`Wb = 1.05 × 1920 = 2016`，`N = 2`，@1080p HIGH。

| 位图 | 尺寸 | 单块 MiB | ×N | 小计 |
|---|---|---|---|---|
| L1 far | `2016 × 497` | 3.82 | 2 | 7.64 |
| L2 mid | `2016 × 605` | 4.65 | 2 | 9.31 |
| L2′ glint | `2016 × 605` | 4.65 | 2 | 9.31 |
| L3 near | `2016 × 302` | 2.32 | 2 | 4.64 |
| L4 water | `2016 × 427` | 3.28 | 2 | 6.57 |
| L4′ waterHi | `2016 × 427` | 3.28 | 2 | 6.57 |
| L0 绢底 | `1920 × 1080` | 7.91 | 1 | 7.91 |
| L5 光尘 | `1920 × 302` | 2.21 | 1 | 2.21 |
| grade | `1920 × 1080` | 7.91 | 1 | 7.91 |
| 印章 | `194 × 194` | 0.14 | 1 | 0.14 |
| **合计** | | | | **62.21** ⛔ 破 46 MiB 容差 |

缓解项：

| 缓解项 | 做法 | 可省 | 余值 |
|---|---|---|---|
| ① | L0 / grade 改 `RGB_565` | −7.90 | 54.31 |
| ② | glint 按 `0.5×` 烘焙（低频发光，运行时放大） | −6.98 | 47.33 |
| ①+② | | −14.88 | **47.33** ⚠️ 仍差 1.33 |
| ③ | `waterHi` 仅 HIGH 档且 `0.75×` | −2.87 | **44.46** ✅ |
| ④ | `Wb` 收到 `1.00 ×` 屏宽（⛔ 会牺牲"另一块后台重生"的安全余量） | −2.96 | — |

⇒ **必须 ①+②+③ 同时上**才进容差。⛔ 不要靠"少一层"省内存（少一层就少一个视差层）。
⛔ 上述每一个数字都必须由 E50 §八 的 **H1′** 在真机上再生后才可写进 Kotlin 注释。

> 分帧烘焙（§7.3）与后处理三段式（§7.4）的执行细则见 E50 §7.3/§7.4。

---

## 八、Kotlin 文件结构与签名

> ⭐ **包根**：渲染器侧 `com.nasmusic.tv.visualizer.qianli` + 纯 JVM 移植层 `com.nasmusic.tv.visualizer.qianli.port`。
> **port 包零 Android 依赖** —— 目的是能用 `kotlin-standalone-verify` 验证纯逻辑（Prng/Noise/路径数学）。
>
> ⭐ **致谢**：port 包全部源码移植自 [LingDong-/shan-shui-inf](https://github.com/LingDong-/shan-shui-inf)（MIT，Copyright 2018 Lingdong Huang）。每个 port 文件的 KDoc 均标注上游行号区间。感谢作者开源。
>
> ⭐ **`Path` 的处理（定稿，消解「零 Android 依赖」与签名里 `android.graphics.Path` 的矛盾）**：
> **port 包内一律不出现 `android.graphics.Path`** —— 所有「图元」函数返回**纯数据** `List<Point>`（`Point = FloatArray(2)` = `[x, y]`）。
> 渲染器侧（`QianliScrollRenderer` / 烘焙期）拿到 `List<Point>` 后再转 `android.graphics.Path` 画进位图。
> ⛔ 下文签名里的 `: Path` / `: List<Path>` 均指**渲染器侧最终产物**，port 包内的实际返回类型是 `List<Point>`（见各函数注释）。
>
> ⚠️ **以下签名中的参数名与默认值照抄上游 JS**（`D:\hxzhang\MyGithubSoftware\NasAudio\shan-shui-inf-main\index.html`）。
> 渲染器侧签名已于 2026-11-02 对照本仓实际代码核对（`RendererFx.kt` / `PostFx` / `FxCoverageScanTest.postFxRe` / `VisualizerRendererFactory`），残留的「推断」标记已全部消解。

### 8.1 port 包（纯 JVM，零 Android 依赖）

| 文件 | 类/函数 | 行数 | 职责 |
|---|---|---|---|
| `port/ShanShuiPrng.kt` | `class ShanShuiPrng` | ~80 | BBS PRNG，Double 全程 |
| `port/ShanShuiNoise.kt` | `object ShanShuiNoise` | ~120 | p5.js Perlin 移植 |
| `port/ShanShuiUtil.kt` | 一组 top-level fun | ~100 | distance/mapval/loopNoise/randChoice/normRand/wtrand/randGaussian/unNan |
| `port/ShanShuiPath.kt` | `object ShanShuiPath` | ~80 | `Point`(=FloatArray(2))、`bezmh`、`divide`、`fillPath`、`strokePath`、`polyToPath` |
| `port/ShanShuiBrush.kt` | `object ShanShuiBrush` | ~220 | `ribbon`/`dab`/`cun`/`foot`，全部返回 `List<Point>`（纯数据点列） |
| `port/ShanShuiTree.kt` | `object ShanShuiTree` | ~400 | 共享骨架 `branch`/`twig` + `kind1/2/3`（先搬 3 种，O8 开放项） |
| `port/ShanShui.kt` | `object ShanShui` | ~700 | `mountain`（含 `vegetate`）/`flatMount`/`flatDec`/`distMount`/`rock`/`water` |
| `port/ShanShuiArch.kt` | `object ShanShuiArch` | ~450 | `hut`/`kind1`/`kind2`（boat 待 O7 裁） |
| `port/ShanShuiPlanner.kt` | `object ShanShuiPlanner` + `class ShanShuiPlanner.State` | ~250 | `plan`/`locmax`/`chadd`/`load`/`render` + 占位表 |

#### `ShanShuiPrng`（照抄上游 `:2-56`）

```kotlin
/**
 * BBS 式乘方取模 PRNG —— 照抄上游 [Prng]（`index.html:2-56`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * ⚠️ **Double 全程**：`hash()` 的累加结果与 `next()` 的 `s²` 都溢出 2^53
 * ⇒ ⛔ 用 `Long`/`BigInteger`（值不同）、⛔ 改写成 Horner 形式（舍入不同）。
 *
 * `hash(x)` = `Base64(JSON.stringify(x))` 逐字符 `charCode·128^i` **升序累加**
 * （上游 `:7-14`：`for i in 0..y.length` ⇒ `z += charCode(i) * 128^i`）。
 *
 * `seed(x)` 末尾**预热 10 次 `next()`**（上游 `:30-32`）⇒ 漏掉则整条序列错位。
 *
 * 上游 `:54-56` 全局覆写 `Math.random = () => Prng.next()` ⇒ 全文 155 处
 * `Math.random()` 调用点全部走种子流 —— 我们只需提供 [rnd]。
 *
 * 黄金向量（seed=1234，数值型）：
 * `sAfterSeed = 334869857393`，
 * 前 4 输出 `0.5179214018993742 / 0.19101042199684515 / 0.1481747547047804 / 0.8586372206878526`。
 */
class ShanShuiPrng {
    var s: Double = 1234.0
    private val p: Double = 999979.0
    private val q: Double = 999983.0
    private val m: Double = p * q

    fun hash(x: Any?): Double
    fun seed(x: Any?)
    fun next(): Double
    fun rnd(): Double = next()
}
```

#### `ShanShuiNoise`（照抄上游 `:90-203`）

```kotlin
/**
 * p5.js Perlin 移植 —— 照抄上游 [Noise]（`index.html:90-203`，出处 `:89` 标注）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * 常量：`PERLIN_SIZE = 4095`、4 octaves、falloff 0.5。
 *
 * ⚠️ [noiseSeed] 在上游是**死代码**（全文件零调用）；Noise 的 perlin 表实际用 `Math.random()` 初始化 ⇒ 与 [ShanShuiPrng] **共用同一种子流**，不是两套独立种子。
 * `noiseSeed` 内部用 LCG（`m=4294967296, a=1664525, c=1013904223`）填充 perlin 数组 —— 照搬但不加调用。
 */
object ShanShuiNoise {
    fun noise(x: Double, y: Double = 0.0, z: Double = 0.0): Double
    fun noiseSeed(s: Double)
}
```

#### `ShanShuiUtil`（照抄上游 `:385-483` 的非 Path 部分）

```kotlin
/** 数值 helper —— 照抄上游 Util 段（`:386-443`）。移植自 LingDong-/shan-shui-inf（MIT）。移植状态：**照抄**。 */
object ShanShuiUtil {
    fun unNan(plist: Any?): Any?
    fun distance(p0: DoubleArray, p1: DoubleArray): Double
    fun mapval(value: Double, istart: Double, istop: Double, ostart: Double, ostop: Double): Double
    fun loopNoise(nslist: MutableList<Double>)
    fun <T> randChoice(arr: List<T>): T  // 上游 `:417-419` 是泛型（`randChoice([-1,1])` / `randChoice([true,false])`）⇒ Kotlin 用泛型
    fun normRand(m: Double, M: Double): Double
    fun wtrand(func: (Double) -> Double): Double
    fun randGaussian(): Double
}
```

#### `ShanShuiPath`（照抄上游 `:445-510` + `:627-651`，改造为 `List<Point>` 输出，渲染器侧转 `android.graphics.Path`）

```kotlin
/**
 * 路径工具 —— `Point = FloatArray(2)`（`[x, y]`，纯数据，零分配）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [bezmh]/[divide] 移植状态：**照抄**；[fillPath]/[strokePath]/[polyToPath] 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
 *
 * [bezmh] 照抄上游 `:445-483`（过中点二次贝塞尔，`pl=20`）。
 * [divide] 照抄上游 `:627-651`（折线等分）。
 * [fillPath] / [strokePath] / [polyToPath] 是 `poly()` 的 Kotlin 化改造
 * （上游 `:485-510` 返回 SVG 字符串 → port 包返回 `List<Point>`；渲染器侧再转 `android.graphics.Path`）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path`。
 */
object ShanShuiPath {
    typealias Point = FloatArray  // FloatArray(2) = [x, y]

    fun bezmh(P: List<Point>, w: Double = 1.0): List<Point>
    fun divide(plist: List<Point>, reso: Int): List<Point>
    fun fillPath(plist: List<Point>, xof: Double = 0.0, yof: Double = 0.0, col: ShanShuiColor): List<Point>
    fun strokePath(plist: List<Point>, xof: Double = 0.0, yof: Double = 0.0, col: ShanShuiColor, wid: Double): List<Point>
    fun polyToPath(plist: List<Point>, xof: Double = 0.0, yof: Double = 0.0, fil: ShanShuiColor, str: ShanShuiColor, wid: Double): List<Point>
}
```

> ⚠️ **`ShanShuiColor` 类型（定稿）** —— 上游用 CSS 字符串（如 `"rgba(200,200,200,0.9)"`）。
> port 包内用**纯数据** `ShanShuiColor(r: Int, g: Int, b: Int, a: Double)`（自定义 value class / data class），
> 渲染器侧再映射到 `android.graphics.Color` / Compose `Color`。
> 染色三版（V-A/V-B/V-C）通过 `col` 参数区分（见 `QianliColorTable`）。

#### `ShanShuiBrush`（照抄上游 `:516-741` + `:1728-1802`，改造为 `List<Point>` 输出，渲染器侧转 `android.graphics.Path`）

```kotlin
/**
 * 图元层 —— ⭐ 这是"像"的全部来源。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [ribbon]/[dab]/[foot] 移植状态：**照抄**；[cun] 移植状态：**改造-①**（ret≠0 时返回 List<List<Point>> 而非 SVG 字符串）。
 *
 * [ribbon] 照抄上游 [stroke]（`:516-576`）：变宽笔触，逐点法线偏移 + 噪声干湿变化。
 *   上游默认值：wid=2, col="rgba(200,200,200,0.9)", noi=0.5, out=1,
 *   fun=Math.sin(x*PI)。
 *
 * [dab] 照抄上游 [blob]（`:578-625`）：纺锤形笔触点/叶簇。
 *   上游默认值：len=20, wid=5, ang=0, col="rgba(200,200,200,0.9)", noi=0.5, ret=0,
 *   fun=分段 sqrt(sin)。reso=20。
 *
 * [cun] 照抄上游 [texture]（`:653-741`）：皴。
 *   上游默认值：tex=400, wid=1.5, len=0.2, sha=0, ret=0,
 *   noi=30/x, col=rgba(100,100,100,rand*0.3), dis=三分分布。
 *   ⚠️ ret≠0 时返回 texlist（List<List<Point>>）而不是 Path 列表。
 *
 * [foot] 照抄上游 [foot]（`:1728-1802`）：山脚收笔。
 *   上游默认值：xof=0, yof=0, ret=0。ret≠0 时返回 ftlist。
 */
object ShanShuiBrush {
    fun ribbon(
        pts: List<ShanShuiPath.Point>,
        wid: Double,
        fun: (Double) -> Double,
        noi: Double,
        col: ShanShuiColor,
    ): List<ShanShuiPath.Point>  // port 包内返回纯数据点列；渲染器侧转 android.graphics.Path

    fun dab(
        x: Double,
        y: Double,
        len: Double,
        wid: Double,
        ang: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiPath.Point>

    fun cun(
        ptlist: List<List<ShanShuiPath.Point>>,
        tex: Double,
        sha: Double,
        col: ShanShuiColor,
    ): List<List<ShanShuiPath.Point>>  // ret≠0 时返回 texlist 语义（纯数据点列）

    fun foot(
        ptlist: List<List<ShanShuiPath.Point>>,
        xof: Double,
        yof: Double,
    ): List<ShanShuiPath.Point>
}
```

> ⭐ **`Path` 已定稿**：port 包内一律返回纯数据 `List<Point>`（见 §八 开头「`Path` 的处理」），
> 渲染器侧（`QianliScrollRenderer` / 烘焙期）再转 `android.graphics.Path` 画进位图。
> `ShanShuiColor` 用纯数据 `(r, g, b, a)`（见上）。

#### `ShanShuiTree`（照抄上游 `:743-990` + `:919-989`）

```kotlin
/**
 * 场景单元层 —— 树。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * [branch] 照抄上游 `:919-989`（共享递归骨架，被多个 treeNN 复用）。
 *   上游默认值：hei=300, wid=6, ang=0, det=10, ben=PI*0.2。
 *   返回 [List<List<Point>>]（trlist1, trlist2）。
 *
 * [kind1] = 上游 tree01（`:744-802`）：细枝树。
 *   上游默认值：hei=50, wid=3, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *
 * [kind2] = 上游 tree02（`:803-841`）：阔叶簇。
 *   上游默认值：hei=16, wid=8, clu=5, col="rgba(100,100,100,0.5)", noi=0.5。
 *
 * [kind3] = 上游 tree03（`:842-917`）：塔形树。
 *   上游默认值：hei=50, wid=5, ben=默认0, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *
 * ⚠️ tree04–tree08 不移植（O8 开放项）；`flatDec` 内部调用 tree04/05/08 须替换或降级。
 */
object ShanShuiTree {
    fun branch(
        hei: Double,
        wid: Double,
        ang: Double,
        det: Double,
        ben: Double,
    ): List<List<ShanShuiPath.Point>>

    fun kind1(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiPath.Point>

    fun kind2(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        clu: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiPath.Point>

    fun kind3(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        ben: (Double) -> Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiPath.Point>
}
```

#### `ShanShui`（照抄上游 `Mount` 命名空间 `:1727-2522` + `water` `:3667-3704`）

```kotlin
/**
 * 场景单元层 —— 山与水。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [mountain]/[flatMount]/[distMount]/[rock]/[water] 移植状态：**照抄**；[flatDec] 移植状态：**改造-③**（内部调用 tree04/05/08 不移植，须替换为 kind1/2/3）。
 *
 * [mountain] 照抄上游 `:1804-2081`：主山，10 行 × 50 列点阵（reso=[10,50]）。
 *   上游默认值：hei=100+rand*400, wid=400+rand*200, tex=200, veg=true, ret=0, col=undefined。
 *   ⚠️ 内部 [vegetate] 被调 8 次（RIM/TOP/MIDDLE/BOTTOM/BOTT_ARCH/TOP_ARCH/TRANSM/BOTT_ROCK）。
 *   ⚠️ vegetate 内部调用 Tree.tree01/02/03 + Arch.arch02/03/04/transmissionTower01
 *     —— arch03/04/transmissionTower01 不在移植范围，须裁剪或替换。
 *
 * [flatMount] 照抄上游 `:2083-2232`：平缓坡。
 *   上游默认值：hei=40+rand*400, wid=400+rand*200, tex=80, cho=0.5, ret=0。reso=[5,50]。
 *
 * [flatDec] 照抄上游 `:2234-2367`：坡面装饰。
 *   ⚠️ 内部调用 Tree.tree04/05/08 —— 不在移植范围（O8），须替换为 kind1/2/3 或降级。
 *
 * [distMount] 照抄上游 `:2369-2431`：远山（淡）。
 *   上游默认值：hei=300, len=2000, seg=5。
 *   ⚠️⚠️ 内部调用 PolyTools.triangulate（`:2419-2428`）—— 不移植三角化，
 *     须改为直接 poly() 填充（不做三角化分解）或用多条 stroke() 叠加模拟渐变。
 *
 * [rock] 照抄上游 `:2433-2522`：矶石。
 *   上游默认值：hei=80, wid=100, tex=40, ret=0, sha=10。reso=[10,50]。
 *
 * [water] 照抄上游 `:3667-3704`：江水波纹（只有 38 行）。
 *   上游默认值：hei=2, len=800, clu=10。
 */
object ShanShui {
    fun mountain(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        veg: Boolean,
        ret: Int,
        col: ShanShuiColor?,
    ): List<ShanShuiPath.Point>  // port 包内返回纯数据点列；渲染器侧转 android.graphics.Path

    // 内部 vegetate(treeFunc, growthRule, proofRule) 被调 8 次
    private fun vegetate(
        treeFunc: (Double, Double) -> List<ShanShuiPath.Point>,
        growthRule: (Int, Int) -> Boolean,
        proofRule: (List<FloatArray>, Int) -> Boolean,
    )

    fun flatMount(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        cho: Double,
        ret: Int,
    ): List<ShanShuiPath.Point>

    fun flatDec(
        xoff: Double,
        yoff: Double,
        grbd: ShanShuiPlanner.Bounds,  // 上游用 {xmin,xmax,ymin,ymax} 对象
    ): List<ShanShuiPath.Point>

    fun distMount(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        len: Double,
        seg: Double,
    ): List<ShanShuiPath.Point>  // ⚠️ 须移除 triangulate 调用

    fun rock(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        tex: Double,
        ret: Int,
        sha: Double,
    ): List<ShanShuiPath.Point>

    fun water(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        len: Double,
        clu: Double,
    ): List<ShanShuiPath.Point>
}
```

#### `ShanShuiArch`（照抄上游 `Arch` 命名空间 `:2525-3317`）

```kotlin
/**
 * 场景单元层 —— 建筑。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [hut]/[kind2] 移植状态：**照抄**；[kind1] 移植状态：**改造-③**（移除 Man.man 人物调用）。
 *
 * [hut] 照抄上游 `:2542-2607`：茅屋。
 *   上游默认值：hei=40, wid=180, tex=300。reso=[10,10]。
 *
 * [kind1] = 上游 arch01（`:3002-3059`）：楼阁。
 *   上游默认值：hei=70, wid=180, rot=0.7, per=5。
 *   ⚠️⚠️ 内部调用 Man.man（`:3035-3047`）—— 不移植人物，须移除 Man 调用。
 *
 * [kind2] = 上游 arch02（`:3061-3119`）：楼阁（含 pagroof 飞檐）。
 *   上游默认值：hei=10, wid=50, rot=0.3, per=5, sto=3, sty=1, rai=false。
 *
 * ⚠️ boat01 不移植（O7 未裁）；arch03/arch04/transmissionTower01 不在移植范围。
 */
object ShanShuiArch {
    fun hut(
        xoff: Double,
        yoff: Double,
        hei: Double,
        wid: Double,
        tex: Double,
    ): List<ShanShuiPath.Point>

    fun kind1(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        rot: Double,
        per: Double,
    ): List<ShanShuiPath.Point>  // ⚠️ 须移除 Man.man 调用

    fun kind2(
        xoff: Double,
        yoff: Double,
        seed: Double,
        hei: Double,
        wid: Double,
        rot: Double,
        per: Double,
        sto: Double,
        sty: Double,
        rai: Boolean,
    ): List<ShanShuiPath.Point>
}
```

#### `ShanShuiPlanner`（照抄上游 `mountplanner`/`MEM`/`chunkloader`/`chunkrender` `:3706-3984`）

```kotlin
/**
 * 构图规划 —— 照抄上游 mountplanner（`:3706-3814`）+ MEM（`:3816-3827`）
 * + chunkloader（`:3845-3971`）+ chunkrender（`:3973-3984`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄** + **改造-③**（chunkloader 内部 boat01 调用不移植，须裁剪）。
 *
 * ⚠️ chunk 按 y 升序 = 远→近画家序（上游 `:3846-3874` 的插入排序保证）。
 *
 * ⚠️ 上游 xstep=5、samp=0.03、locmax 半径 2 阈值 0.3、chadd 最小间距 10、
 *   远山每 1000px 一座、flatMount 填充概率 1%、boat 概率 20% 间距 400。
 *
 * ⚠️ chunkloader 内部调用 Arch.boat01 ——
 *   不在移植范围，须裁剪（只保留 mount/flatmount/distmount 三种 tag）。
 */
object ShanShuiPlanner {
    fun plan(xmin: Double, xmax: Double): List<Chunk>

    // 内部辅助（照抄上游 :3707-3732）
    private fun locmax(x: Double, y: Double, f: (Double, Double) -> Double, r: Double): Boolean
    private fun chadd(r: Chunk, mind: Double = 10.0): Boolean

    fun load(state: State, xmin: Double, xmax: Double)
    fun render(state: State, xmin: Double, xmax: Double)

    // 上游 MEM 是全局对象，Kotlin 改为 class 实例
    class State {
        // 上游 canv 是 SVG 字符串；Kotlin 侧改为纯数据点列（port 包零 Android 依赖）
        val canv: MutableList<ShanShuiPath.Point> = mutableListOf()
        val chunks: MutableList<Chunk> = mutableListOf()
        var xmin: Double = 0.0
        var xmax: Double = 0.0
        var cwid: Double = 512.0
        var cursx: Double = 0.0
        var lasttick: Double = 0.0
        var windx: Double = 3000.0
        var windy: Double = 800.0
        val planmtx: BooleanArray = BooleanArray(0)  // 上游是 array，Kotlin 用 BooleanArray
    }

    // 上游 reg[] 的元素是 {tag, x, y, h}
    data class Chunk(
        val tag: String,
        val x: Double,
        val y: Double,
        val h: Double,
    )

    // flatDec 的 bounds 参数
    data class Bounds(val xmin: Double, val xmax: Double, val ymin: Double, val ymax: Double)
}
```

### 8.2 qianli 包（渲染器侧）

| 文件 | 类 | 行数 | 职责 |
|---|---|---|---|
| `QianliBlockPool.kt` | `class QianliBlockPool` | ~150 | 分块 ring buffer + 两段式惰性重生 |
| `QianliColorTable.kt` | `object QianliColorTable` | ~80 | 三版染色（V-A/V-B/V-C）的坡面行号→色映射 |
| `QianliScrollRenderer.kt` | `class QianliScrollRenderer : RendererFx()` | ~400 | 8 层合成 + 印章 |
| `QianliSeal.kt` | `object QianliSeal` | ~120 | 四字缪篆顶点表 + 烘焙 + G6 判据 |

#### `QianliBlockPool`（基于 §五 + E50 §4.1′；`LayerSpec` 见下方 data class）

```kotlin
/**
 * 分块 ring buffer —— 每层 N 个唯一位图块，滚动到边界时惰性再生成。
 *
 * 两段式重生（R1 解法）：
 * - [draw] 只置 [pendingRegen]，⛔ 不同步生成（单块 2,400 行 JS 等价工作不可能塞进 16ms）。
 * - 生成在后续帧的预算片里推进（每帧最多 [BLOCK_BUDGET_MS]）。
 * - 生成完成前该块画上一轮的旧块（旧块内容不同但风格一致 ⇒ 读作"山在变"而非"空白"）。
 *
 * 位移走 [Canvas.drawBitmap] 的 x 参数，⛔ 不碰 [android.graphics.Shader.setLocalMatrix]
 * （E43 真机静默失效）。
 *
 * 块宽 Wb ≥ 1.05 × 屏宽（保证"覆盖整屏时另一块可在后台重生"）。
 */
class QianliBlockPool(
    layer: LayerSpec,  // 层规格（见下方 data class）
    n: Int,           // N = 2（§一的 ops 账）
    w: Int,           // 屏宽（px）
    h: Int,           // 层带高（px），⛔ 不是全屏
    gen: (x0: Double) -> Bitmap,  // 块生成器：给定世界坐标 x0，产出一张 Bitmap
) {
    fun advance(offset: Double)
    fun draw(canvas: Canvas, offset: Double, alpha: Float)
    var pendingRegen: Boolean

    companion object {
        const val BLOCK_BUDGET_MS = 12
    }
}
```

> ✅ `LayerSpec` 已定稿（渲染器侧 `qianli` 包，非 port 包；字段来源 §一图层表 + §7.5 分层速度表 + §7.5 α 下限表）：

```kotlin
data class LayerSpec(
    val id: Int,            // 层序号 L0–L7（§一）
    val bandY0: Double,     // 带顶（0..1 屏高比例）
    val bandY1: Double,     // 带底（0..1）
    val speed: Double,      // 基准速度 px/s（§7.5 分层速度表）
    val alphaFloor: Double, // 静音帧 α 下限（§7.5 α 下限表）
)
```

#### `QianliScrollRenderer`（基于 `RendererFx` 基类 + §一图层序；签名已对照 `RendererFx.kt` 核对）

```kotlin
/**
 * E50「千里江山」渲染器 —— 8 层合成 + 印章。
 *
 * 图层序 L0→L7（§一）：绢底天幕 → 远景 → 中景主山 → 轮廓光 → 近景 →
 * 江水 → 江水高光 → 光尘 → grade → 印章。
 *
 * ⚠️ 印章画在 grade 之后（D8），否则暗角把朱砂压暗。
 *
 * ⚠️ `onEnter`/`onExit` 在 RendererFx 基类是 final ⇒ 覆写 [onEnterContent]/[onExitContent]。
 * 首行必须 [releaseAll]（画质变化会重入 onEnter，不经 onExit）。
 *
 * ⚠️ 逐帧零分配：Paint/Path/Bitmap 全部复用。
 * ⚠️ ⛔ 不读 frame.spectrum（D5，G-通道门禁）。
 */
class QianliScrollRenderer : RendererFx() {
    override val theme: VisualizerTheme = VisualizerTheme.QIANLI_SCROLL

    // 三段式 grade（E50 §7.4）：暗角 + 颗粒由 grade 位图承担。
    // 形制 = **E46–E49 同款**（`DesertSmokeRenderer` / `SnowCedarRenderer` / `BirdsFlockRenderer`）：
    // `postFx` 留**裸字面量**当 `FxCoverageScanTest` 的门禁证据，`vignetteOverride` 恒 `0f`
    // 关断基类那一路的实际绘制（否则暗角叠两遍）。
    // ⚠️ ⛔ 字面量不可省 —— 它是 `FxCoverageScanTest.postFxRe` 认定"本效果走了基类后处理"
    // 的唯一依据，也是 `needsDamageCoalescer` 的输入（为 false ⇒ 每帧白补一次整画布 drawRect）。
    override val postFx = PostFx(vignette = 0.10f)

    override fun vignetteOverride(fx: FxFrame): Float = 0f

    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()  // 首行（画质变化会重入 onEnter，不经 onExit）
    }

    override fun onExitContent() {
        releaseAll()
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        // 8 层合成：L0→L7（§一）
        // 每层：QianliBlockPool.advance(offset) + .draw(canvas, offset, alpha)
        // 印章最后画（D8）
    }

    private fun releaseAll()
}
```

> ✅ `PostFx` 构造参数已核对（`RendererFx.kt:162-172`）：`PostFx(vignette, grain, scanline, vignetteEdge)`；
> `vignetteOverride` 是 `RendererFx` 的 `protected open fun`（`:65`），默认 `NaN` 哨兵 ⇒ 覆写为 `0f` 即关断。

#### `QianliColorTable`（基于 E50 §3.3 + §五；`ShanShuiColor` 见 §8.1）

```kotlin
/**
 * 三版染色表 —— V-A 灰阶 / V-B 行号染色 / V-C 大青绿。
 *
 * 按坡面行号 j 取色（V-B/V-C），⛔ 按 y 取色。
 * 颜色由该笔触在坡面点阵里的行号决定（j=0 山脊 → j=R 山脚）。
 *
 * V-A：原样 rgba(100,100,100,α)（对照组）。
 * V-B：按 §3.3 六族色表染色。
 * V-C：V-B + 分层平涂设色 + 皴密度下调。
 */
object QianliColorTable {
    enum class Version { V_A, V_B, V_C }

    fun colorFor(version: Version, rowJ: Int, totalRows: Int): ShanShuiColor
}
```

> ✅ `ShanShuiColor` 已定稿为纯数据 `(r, g, b, a)`（见 §8.1 `ShanShuiPath` 注释）。

#### `QianliSeal`（基于 E50 §六；`glyphs` 结构见下方注释）

```kotlin
/**
 * 右上角篆字印章「扫地老师」—— 四字缪篆顶点表 + 烘焙 + G6 判据。
 *
 * 字形来源：手工描摹四字轮廓（R-C），⛔ 不用字体/位图/生成式图像。
 * 印面：2×2 白文方印，朱砂红底 + 绢白字（D7）。
 * 位置：消费 safeAreaPx，s = 0.09·H（E50 §6.3）。
 * 最后一层画（D8）。
 *
 * G6 三条判据：
 * ① 坐标 300 帧零漂移 + 尺寸 ∈8–10%H；
 * ② 四字白文落墨比 ∈[20%,72%]；
 * ③ 残缺面积 ≤2%。
 */
object QianliSeal {
    // 四字缪篆顶点表（List<List<Point>>，每字一个轮廓；顶点数据须 K7 手工描摹，R-C 路线）
    private val glyphs: List<List<FloatArray>>

    fun bake(canvas: Canvas, centerX: Float, centerY: Float, size: Float): Bitmap
    fun releaseAll()
}
```

> ⚠️ `glyphs` 的**结构**已定稿（`List<List<FloatArray>>`，每字一个轮廓）；**顶点数据本身**须 K7 手工描摹（R-C 路线，⛔ 不用字体/位图/生成式图像）。

### 8.3 测试文件

| 文件 | 测试类 | 行数 | 职责 |
|---|---|---|---|
| `test/.../qianli/ShanShuiPortGateTest.kt` | `ShanShuiPortGateTest` | ~300 | K2–K5 共用：Prng 黄金向量 / Noise 自相关 / ribbon 对称 / cun list 语义 / mountain 点阵 / planner 间距 |
| `test/.../qianli/QianliScrollGateTest.kt` | `QianliScrollGateTest` | ~200 | K6：同层同速 / 通道隔离 / α 下限 / 速度上限 / 印章图层序 |
| `test/.../qianli/QianliBlockPoolTest.kt` | `QianliBlockPoolTest` | ~150 | K6：块宽下限 / 重生不阻塞绘制 / 预算耗尽降级 |
| `test/.../qianli/QianliSealGlyphGateTest.kt` | `QianliSealGlyphGateTest` | ~200 | K7：G6 三条判据 + 负向自证 |

---

## 九、K1–K8 实施任务

> 每个任务：**范围**（改哪些文件）+ **步骤** + **验收判据**（可核对的硬标准）。

### K1 枚举与字符串

**范围**：`AppSettings.kt` 的 `VisualizerTheme` 枚举 + `values/strings.xml` + `values-en/strings.xml`。

> ⚠️ **2026-11-02 核对**：`app/src/main` 全树检索 `QIANLI`/千里江山 **零命中** ⇒ 枚举项与两语字符串**均未加**。本任务是整个 E50 的第一块砖。

**步骤**：
1. 在 `AppSettings.kt:181` 的 `VisualizerTheme` 枚举加 `QIANLI_SCROLL(...)`（参数：`displayNameRes` / 中文显示名 `千里江山` / `tier` / `ordinalLabel`；`tier` 与 `ordinalLabel` 须所有者定）。
2. 在 `values/strings.xml` + `values-en/strings.xml` 各加 `visualizer_theme_qianli_scroll`（中文「千里江山」/ 英文 "Rivers and Mountains"，O4）。

**验收**：`VisualizerThemeStringsGateTest` 绿（两语显示名齐全）；枚举 `entries` 含 `QIANLI_SCROLL`。

> ⛔ **工厂 `when` 分支与 `FxCoverageScanTest` 登记不在本任务** —— 它们需要 `QianliScrollRenderer` 类存在（K6 才建），见 K6 步骤 4。

### K2 移植层地基

**范围**：`port/ShanShuiPrng.kt` + `port/ShanShuiNoise.kt` + `port/ShanShuiUtil.kt`。

**步骤**：
1. 搬 `Prng`（上游 `:2-56`）→ `ShanShuiPrng`，`Double` 全程，`hash` 升序累加，`seed` 预热 10 次。
2. 搬 `Noise`（上游 `:90-203`）→ `ShanShuiNoise`，`PERLIN_SIZE=4095`、4 octaves、falloff 0.5。
3. 搬 `Util` 段（上游 `:386-443`）→ `ShanShuiUtil`：`unNan`/`distance`/`mapval`/`loopNoise`/`randChoice`/`normRand`/`wtrand`/`randGaussian`。

**验收**：`ShanShuiPortGateTest` 的黄金向量逐位相等（G-PORT-1/2，见 §十）。

### K3 图元层

**范围**：`port/ShanShuiPath.kt` + `port/ShanShuiBrush.kt`（ribbon/dab/cun/foot）。

**步骤**：
1. 搬 `bezmh`（上游 `:445-483`）+ `div`（`:627-651`）→ `ShanShuiPath`。
2. 搬 `poly`（`:485-510`）改造为 `fillPath`/`strokePath`/`polyToPath`（**返回 `List<Point>`**，渲染器侧再转 `android.graphics.Path`）。
3. 搬 `stroke`（`:516-576`）→ `ribbon`；搬 `blob`（`:578-625`）→ `dab`；搬 `texture`（`:653-741`）→ `cun`；搬 `foot`（`:1728-1802`）→ `foot`。

**验收**：`ShanShuiPortGateTest` 的「ribbon 对称性 / cun 返回 list 语义」断言（G-PORT-3/4）。

### K4 场景单元

**范围**：`port/ShanShuiTree.kt`（kind1-3 + branch）+ `port/ShanShui.kt`（mountain/flatMount/flatDec/distMount/rock）。

**步骤**：
1. 搬 `Tree.tree01/02/03`（上游 `:744-917`）→ `kind1/2/3`；搬 `branch`（`:919-989`）→ 共享骨架。
2. 搬 `Mount.mountain`（`:1804-2081`）含 `vegetate`（被调 8 次）—— ⚠️ 须裁剪 vegetate 内部对 `Arch.arch03/04/transmissionTower01` 的调用（不在移植范围）。
3. 搬 `flatMount`（`:2083-2232`）+ `flatDec`（`:2234-2367`）—— ⚠️ flatDec 内部调用 tree04/05/08 须替换为 kind1/2/3。
4. 搬 `distMount`（`:2369-2431`）—— ⚠️ 须移除 `PolyTools.triangulate` 调用（§四红线）。
5. 搬 `rock`（`:2433-2522`）。

**验收**：`ShanShuiPortGateTest` 的「mountain 10×50 网格 / vegetate 被调 8 次 / 无 NaN 点」断言（G-PORT-5）。

### K5 建筑与水

**范围**：`port/ShanShuiArch.kt`（hut/kind1/kind2）+ `port/ShanShui.kt` 的 `water` + `port/ShanShuiPlanner.kt`（plan/load/render）。

**步骤**：
1. 搬 `Arch.hut`（`:2542-2607`）→ `hut`。
2. 搬 `Arch.arch01`（`:3002-3059`）→ `kind1` —— ⚠️ 须移除 `Man.man` 调用。
3. 搬 `Arch.arch02`（`:3061-3119`）→ `kind2`。
4. 搬 `water`（`:3667-3704`）→ `ShanShui.water`。
5. 搬 `mountplanner`（`:3706-3814`）+ `MEM`（`:3816-3827`）+ `chunkloader`（`:3845-3971`）+ `chunkrender`（`:3973-3984`）→ `ShanShuiPlanner` —— ⚠️ 须裁剪 chunkloader 内部对 `Arch.boat01` 的调用。

**验收**：`ShanShuiPortGateTest` 的「planner 最小间距 / chunk y 升序」断言（G-PORT-6）。

### K6 渲染器骨架

**范围**：`QianliBlockPool.kt` + `QianliScrollRenderer.kt`（8 层合成 + 两段式重生）。

**步骤**：
1. 实现 `QianliBlockPool`：ring buffer + 两段式惰性重生 + `BLOCK_BUDGET_MS` 预算片。
2. 实现 `QianliScrollRenderer`：8 层合成（L0→L7）+ 印章最后画 + `releaseAll`。
3. 接 `RendererFx` 基类：`postFx` 三段式 grade（裸字面量 + `vignetteOverride ≡ 0f`）、`onEnterContent` 首行 `releaseAll`、`drawContent` 8 层合成。
4. **注册**（K1 移来的两步，此时渲染器类已存在）：`VisualizerRendererFactory.kt` 的 `when` 分支加 `VisualizerTheme.QIANLI_SCROLL -> QianliScrollRenderer()`；`FxCoverageScanTest.kt` 的 `covered` 名单登记 `QianliScrollRenderer`。

**验收**：`QianliBlockPoolTest`（G-POOL-1~3）+ `QianliScrollGateTest`（G-SCROLL-1~5）+ `FxCoverageScanTest` + `QianliScrollRendererTest`（工厂能创建）全绿。

### K7 染色与印章

**范围**：`QianliColorTable.kt`（V-A/V-B/V-C 三版）+ `QianliSeal.kt`。

**步骤**：
1. 实现 `QianliColorTable`：三版染色表，按坡面行号 j 取色。
2. 实现 `QianliSeal`：四字缪篆顶点表 + 烘焙 + G6 判据。
3. 出三版对照图交所有者定案（D17）。

**验收**：`QianliSealGlyphGateTest`（G6 三条判据）绿；三版对照图交所有者定案。

### K8 真机验收与归档

**范围**：按 E50 §十一 设备矩阵跑 adb 截图判读；更新文档。

**步骤**：
1. 按 E50 §十一 跑真机验收（U1–U13）。
2. 更新 `docs/visualizer-effects-list.md`（27→28 套）。
3. 更新 `CHANGELOG.md` + `docs/technical-overview.md` §10。
4. 清 `logs_temp/`。

**验收**：E50 §十一 U1–U13 由所有者打勾；文档同步完毕。

---

## 十、门禁规格

> 四个测试类 + 判据。每条判据写清测什么、断言什么。
> ⚠️ 判据口径 = 原型侧 harness 再生值，⛔ 不许手抄。

### 10.1 `ShanShuiPortGateTest`（K2–K5 共用，纯 JVM）

| 判据 | 测什么 | 断言什么 |
|---|---|---|
| **G-PORT-1** | Prng 黄金向量 | seed=1234 的 `sAfterSeed = 334869857393` 与前 4 输出 `0.5179214018993742 / 0.19101042199684515 / 0.1481747547047804 / 0.8586372206878526` 逐位相等（容差 0） |
| **G-PORT-2** | Noise 自相关 | 固定种子后 `noise(0..1)` 落在 [0,1]；两次同种子调用逐位相等 |
| **G-PORT-3** | ribbon 对称性 | pts 为水平线时，ribbon 的上下缘关于该线对称 |
| **G-PORT-4** | cun 的 ret≠0 返回 `List<List<Point>>` | 非空、每个子点列点数 > 2 |
| **G-PORT-5** | mountain 点阵 | 无 NaN、10 行 × 50 列 |
| **G-PORT-6** | planner | `chadd` 最小间距 ≥10；chunks 按 y 升序 |
| **G-PORT-7** | 负向自证 | seed 用 Long 累加 / 漏预热 10 次 / 用 Horner 形式 → G-PORT-1 必红 |
| **G-PORT-8** | 结构性保真断言 | 每个 port 函数（ribbon/dab/cun/mountain/flatMount/distMount/rock/water/hut/kind1/kind2/plan/load/render）的点列输出：无 NaN、坐标范围合理、点列长度 > 2；数值与上游同种子同参数的输出一致（由原型 harness 再生值，⛔ 不许手抄） |

### 10.2 `QianliScrollGateTest`（K6）

| 判据 | 测什么 | 断言什么 |
|---|---|---|
| **G-SCROLL-1** | 同层同速 | L2 与 L2′ 的 offset 恒等 |
| **G-SCROLL-2** | 通道隔离 | 源码扫描 renderer，出现 `frame.spectrum` / `.spectrum[` 即失败 |
| **G-SCROLL-3** | α 下限 | 静音帧下 L1雾≥0.10 / L2光晕≥0.10 / L4水≥0.16 / L5尘≥0.05 / L0/L2/L3山≡1.0 |
| **G-SCROLL-4** | 速度上限 | `v_layer(eff) ≤ v_layer(base) × 1.30` |
| **G-SCROLL-5** | 印章图层序 | 印章在 grade 之后（图层序断言） |

### 10.3 `QianliBlockPoolTest`（K6）

| 判据 | 测什么 | 断言什么 |
|---|---|---|
| **G-POOL-1** | 块宽 | `Wb ≥ 1.05 × 屏宽` |
| **G-POOL-2** | 重生不阻塞绘制 | `advance` 后 `pendingRegen=true`，但 `draw` 仍画出旧块，无空白 |
| **G-POOL-3** | 预算耗尽降级 | budget 内做不完时先砍装饰，⛔ 不画空白 |

### 10.4 `QianliSealGlyphGateTest`（K7）

| 判据 | 测什么 | 断言什么 |
|---|---|---|
| **G6-①** | 坐标零漂移 + 尺寸 | 300 帧零漂移 + 尺寸 ∈8–10%H |
| **G6-②** | 白文落墨比 | 四字白文落墨比 ∈[20%,72%] |
| **G6-③** | 残缺面积 | 残缺面积 ≤2% |
| **负向自证** | FAKE 字形 | FAKE 字形（3 笔 / bbox 2.0×10.0）必须被 ①② 咬住 |

> ⚠️ **负向自证规矩**（本仓 §八 开头规矩）：每条新门禁必须有一条**故意破坏真实值**的负向用例，
> 且**咬在自己的 tag 上**。⛔ 不许通过**放宽阈值**让它变绿。
> 执行细节见 E50 §十。

---

## 十一、执行顺序

> 把移植顺序索引到 K1–K7 任务。⛔ **Prng 是所有后续步骤的前置**。

| 步骤 | 内容 | 对应 K 任务 | 前置 |
|---|---|---|---|
| 1 | 搬 `Prng`（上游 `:2-56`）→ `ShanShuiPrng` | K2 | 无 |
| 2 | 搬 `Noise`（`:90-203`）→ `ShanShuiNoise` | K2 | 步骤 1 |
| 3 | 搬 `Util` 段（`:386-443`）→ `ShanShuiUtil` | K2 | 步骤 1 |
| 4 | 搬图元层（`:445-741` + `:1728-1802`）→ `ShanShuiPath` + `ShanShuiBrush` | K3 | 步骤 1-3 |
| 5 | 搬 `Tree` 命名空间（`:743-990`）→ `ShanShuiTree`（kind1-3 + branch） | K4 | 步骤 4 |
| 6 | 搬 `Mount` 命名空间（`:1727-2522`）→ `ShanShui`（mountain/flatMount/flatDec/distMount/rock） | K4 | 步骤 4-5 |
| 7 | 搬 `Arch` 命名空间（`:2525-3317`）→ `ShanShuiArch`（hut/kind1/kind2） | K5 | 步骤 5-6 |
| 8 | 搬 `water`（`:3667-3704`）→ `ShanShui.water` | K5 | 步骤 4 |
| 9 | 搬 `mountplanner` + `MEM` + `chunkloader` + `chunkrender`（`:3706-3984`）→ `ShanShuiPlanner` | K5 | 步骤 1-8 |
| 10 | 接 `ShanShuiPlanner.State` 到分块轮转（§六）→ 实现 `QianliBlockPool` + `QianliScrollRenderer` | K6 | 步骤 9 |
| 11 | 染色（§五）→ `QianliColorTable`（V-A/V-B/V-C）→ 三版对照 → 所有者定案 | K7 | 步骤 10 |
| 12 | 印章（E50 §六）→ `QianliSeal` | K7 | 步骤 10 |
| 13 | 注册链（工厂 `when` + `FxCoverageScanTest`） | K1 | 步骤 10-12 |
| 14 | 真机验收 + 文档同步 + 归档 | K8 | 步骤 13 |

> ⚠️ **步骤 1 的 `Prng` 是所有后续步骤的前置**：任何一步的随机序列与上游不一致，山形就不同，G4/G13′ 就无法对账。
>
> ⚠️ 注册链（K1）中的枚举与 strings 已标记完成（据任务指示），但 `VisualizerRendererFactory` 的 `when` 分支与 `FxCoverageScanTest` 登记须在渲染器可用后补齐（步骤 13）。
