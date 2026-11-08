# 频谱效果开发文档 —— 星际四季频谱（OpenGL ES 版）

> **版本**: v2.2（2026-11-08，**粒子与星空改为逐位移植 `globe-present.html` 的真实参数**；v2.1 的"微弱化"数字系自拟、已作废；渲染栈为原生 OpenGL ES）
> **状态**: 开发方案评审
> **关联**:
> - `docs/globe-present.html`（**视觉与参数的唯一来源**：星空 / 粒子 / 季节 / 流星）
> - `docs/e41-globe-opengl-plan.md`（**同批方案**：E41 地球原生 GL 重写，含共享层设计）
> - `docs/technical-overview.md` §10

---

## 〇、决策记录（2026-11-08 所有者裁决）

### 〇.1 本轮决策（v2.1 的 D5/D6 + v2.2 重写的 D7）

| # | 决策点 | 裁决 | 落地要点 |
|---|---|---|---|
| **D5** | **季节节奏** | ✅ **一年 = 5 分钟**，四季各 **75 秒** | ⇒ **一首歌（3~5 分钟）基本看完四季**。季节由 GL 线程自算 dt 累加的播放时长推进，周期 300s。详见 §2.3 / §5.1 |
| **D6** | **频谱条** | ✅ **整层去掉** | ⛔ 不显示任何频谱条（柱子）。画面主体回到**氛围**：星空 / 大气 / 季节 / 昼夜 / 流星。详见 §2.2 / §4 |
| **D7** | **粒子系统** | ✅ **形态与运动逐位移植 HTML；音频只做微弱变化** | ⛔ **不发明参数**：四类粒子的**数量 / 尺寸 / 颜色 / 运动公式 / 混合模式 / 贴图 / 种子 / 季节强度**全部取 `globe-present.html` 的真实值（见 §2.5）。「微弱变化」指 **HTML 里粒子完全不受音频驱动，我们接音频时只做极小幅度的亮度呼应**（×(1+energy×0.15)），⛔ 不改数量 / 形态 / 运动 / 分布。详见 §2.5 / §4.3 |

**v2.1 的一句话**：这个效果从「频谱分析器」变成「**氛围可视化器**」—— 音乐只做**轻微呼应**，画面由季节与星空主导。

> ⚠️ **D6 带来的连带变化**（少一层 ⇒ 少一堆东西）：
> - 少一个 program（`bar` program 取消）、少一次 draw call、少了 `SpectrumBarController` 与它的几何打包代码；
> - `frame.spectrum`（64 柱）**不再被使用** ⇒ 数据桥**不再搬运它**（每帧拷贝 832B → **528B**）；
> - 季节色的「双色插值」不再用于柱子 ⇒ 改用于**粒子 / 波形 / 大气**的着色；
> - `SpectrumContract` 的柱数契约对本效果**不再适用**（但仍必须遵守「不自行指定柱数」这条通则 —— 本效果压根不碰柱数）。

### 〇.2 前两轮决策（v2.0）

| # | 决策点 | 裁决 | 本方案的影响 |
|---|---|---|---|
| **D1** | E41 档位下放 | ✅ 下放 | 与本方案无直接耦合（E41 自己的枚举改动）。但**同一条规则适用于本效果**：不读 `ctx.quality.maxParticles` ⇒ `needsParticleBudget = false` ⇒ 三档全可选（§5.2 / §10.2） |
| **D2** | `globe-upstream/` 的 three.js / three-globe ES6 原版 | ✅ **保留** | 本方案**不碰** `assets/globe/` ⇒ 无影响 |
| **D3** | GL 基础层收敛 | ✅ **收敛到 `visualizer/gl/`** | ⇒ 本方案的 `GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader` **不另起炉灶**，直接落在共享层；E52 的同类件改为委托共享层。拆分规则见 `docs/e41-globe-opengl-plan.md` §4.1 |

⚠️ **⛔ 共享层纪律**：通用层**不得**包含任何上游常量或上游 GLSL —— 一旦混进去就变成「公开的上游衍生件」⇒ 泄漏（origin 是 public 仓库）。

### 〇.3 v2.1 的粒子参数更正（**作废**）

⚠️ **v2.1 §2.5 写的「微弱化六条硬约束」全部作废** —— 那些数字（数量 10/5/0 与 14/7/0 与 36/18/0、
尺寸 1.0~2.4 dp、alpha ≤ 0.42、触发阈值 `treble > 0.88`）**是我自己编的，与 HTML 无关**，
且与本仓「逐位移植 / 不降低效果美观」的既定纪律**直接冲突**。

**正解**：粒子的**形态与运动逐位移植 `globe-present.html`**（数量 / 尺寸 / 颜色 / 运动公式 /
混合模式 / 贴图 / 种子 / 季节强度全部取 HTML 原值，见 §2.5 / §4.3）；
「微弱变化」只指**音频对粒子的调制幅度小**（≤ ±15% 亮度）。

同理，v2.0/v2.1 的星空规格（「1880 点 / 三档亮度 / 球面半径 900 / 逐颗闪烁」）也作废 ——
HTML 的星空是 **2D canvas 静态绘制**（399 点 + 亮星十字光芒 + **不闪烁**），已按 §4.2 逐位移植。

---

## 一、设计目标

### 1.1 定位

创建一个**独立的音乐氛围可视化效果**，在 NowPlaying 界面中作为播放背景呈现。视觉语言源自 `docs/globe-present.html`（生态地球演示页）的美学体系——星空、大气光晕、四季色调、流星——用**原生 OpenGL ES（`GLSurfaceView` + `GLES20`）**渲染。

⭐ **v2.1 定位修正**：本效果**不显示频谱条**。它是一个**氛围型**可视化：
- **画面主体**：星空（缓慢闪烁）+ 大气光晕（随季节变色）+ 四季色调循环 + 昼夜交替 + 间歇流星；
- **音乐的作用**：**轻微呼应**（粒子的生成率、整体亮度、波形起伏），⛔ **不做视觉主导**。

> 这条定位是 D6/D7 的根因：**任何抢视觉的元素都会"打乱效果美观"**。

### 1.2 与既有渲染路径的边界

> ⚠️ 归因澄清：`docs/globe-present.html` 是「生态地球」**演示页**（星空 / 150 棵树 / 19 只动物 / 龙卷风 / 山脉）。
> 它**不是** E41「世界」（`VisualizerTheme.WORLD`）——后者是航空地球：Robinson 陆地 + 城市灯火 + 动态航线弧
> （`data/model/AppSettings.kt:261-294`）。本方案只借鉴演示页的**氛围语言**。

| E41「世界」（当前 WebView 路线） | 本频谱效果 |
|---|---|
| WebView + three-globe 3D 球体 | **原生 OpenGL ES**（无 WebView） |
| 球体 + 卫星贴图 + 航线管 | 星空 + 大气 + 粒子 + 流星（**平面氛围**） |
| 独立 JS 运行时（Chrome 39 ES5 降级） | 自写 ESSL + Kotlin 场景 |
| 地球自转 | 季节循环（300s 一轮） |

**与 E41 GL 重写的关系**：两者**共享** `visualizer/gl/` 基础层（§3.2），但**各自独立**：不同 renderer、不同 `GLSurfaceView` 实例、不同 program 集、不同几何。

### 1.3 质量要求

- 逐行移植视觉要素（星空、大气、流星、季节色、昼夜），不降低效果与美观
- ⭐ **美观优先于音频响应强度**：任何元素的加入/增强都必须先问「会不会破坏画面美感」
- ⛔ **不显示频谱条**；⛔ **粒子只做微弱变化**
- 帧率预算：**≥ 45 FPS**（低端设备），**≥ 60 FPS**（主流设备）
- **零新增依赖**：不引入第三方库（FFT 已由既有 `SpectrumAnalyzer` 提供）
- **渲染器形态**：View 型旁路（`isViewBased = true`），⛔ **不继承 `RendererFx`**（那是 Canvas 路径的模板方法基类）
- **零分配红线**：`onDrawFrame` 内禁止任何对象分配（矩阵 / 数组 / 顶点缓冲全部预分配复用）
- **分辨率适配**：一切像素尺寸按画幅短边比例化，⛔ 不用绝对 px

### 1.4 为什么用 OpenGL ES

| 维度 | Canvas 2D（v1.x） | OpenGL ES（v2.x） |
|---|---|---|
| 载体 | Compose `DrawScope` | `GLSurfaceView` + `GLES20` |
| 渐变（大气） | ⛔ 逐帧重建 `Brush` 会分配；连续插值下无法避免 | ✅ 片元着色器算，零分配 |
| 星点闪烁 | ⛔ 逐点 alpha ⇒ 只能逐颗 `drawCircle` | ✅ per-point alpha attribute ⇒ **一次 `GL_POINTS` 画完** |
| 粒子 | 逐颗绘制 | ✅ `GL_POINTS` + 动态 VBO |
| 抗锯齿 | 依赖 Canvas | ✅ MSAA（`antialias: true` 的 EGL config） |
| TV 端 | ✅ 无宿主问题 | ⚠️ 需自建 EGL/GLSurfaceView 生命周期（E52 已趟平） |
| 帧率 | 与 Compose Choreographer 同步 | ⚠️ 独立渲染循环 ⇒ ⛔ 必须覆写 `renderFps()` |

> ⚠️ **Canvas 2D 路线的物理上限**（`docs/archive/e52-highway-opengl-plan.md:29-43`）：
> *"Canvas 2D 的 `fill()` 语义是「一条路径 → 一个纯色」：没有片元着色器、没有纹理采样、没有法线贴图、没有逐像素光照"*
> ⇒ 渐变与逐点 alpha 这两样在 Canvas 下都要硬凑（帧数与内存双涨），在 GL 里都是免费的。

---

## 二、视觉设计

### 2.1 整体画面构图

```
┌───────────────────────────────────┐
│  ★  *   ✦    ★         *    ✦   │ ← Starfield (y: 0~30%)
│     ★      ✦    *                 │
│  ★     ★  ✦      ★    ╎    *    │        ╎ = 粒子（花朵/雪/落叶，HTML 原值）
│        ·  ·      ·               │
│                                  │
│                                  │
│                                  │
│       · · ══════════════ ·       │ ← 波形（很淡，alpha≈0.22）
│                                  │
│    ♠ ───────流星────────→  ♠     │ ← Meteor（间歇，≤1 颗）
│       大气光晕渐变 ↓                │
│   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░│ ← Atmosphere glow (y: 85~100%)
└───────────────────────────────────┘
```

⭐ **画面是空的**：没有柱子、没有密集图形。中央与上半部留给**星空与大气**，音乐只在细节处轻微呼应。

### 2.2 视觉层面 → GL 实现（从底到顶）

| 层 | 名称 | 位置 | 内容 | **GL 实现** |
|---|---|---|---|---|
| L0 | **背景渐变** | 全屏 | 深空蓝 → 午夜蓝 → 近黑（随季节微调） | 全屏三角 + 片元径向渐变（`uBgTop`/`uBgBottom`） |
| L1 | **星空** | 全屏 | 2D 随机星点（半径 0.35~1.60px）+ 亮星十字光芒 + 对角冷蓝渐变带 | `GL_POINTS` × **399**（1080p，`w×h/5200`），亮星十字光芒另用 `GL_LINES` ⇒ **2 次 draw** |
| L2 | **大气光晕** | y: 85~100% | 底部径向渐变暖色雾（随季节变色） | 与 L0 **同一 program**（uniform 分支），片元算底部径向 |
| L3 | **粒子** | 全屏 | HTML 的**四类粒子**（漂浮光点 / 雪花 / 落叶 / 花朵），形态与运动**逐位移植** | 每类 1 次 `GL_POINTS`（动态 VBO）⇒ **4 次 draw** |
| L4 | **波形线** | y: 78~88% | 实时波形，**很淡**（alpha≈0.22） | `GL_LINE_STRIP` × 128 点，per-vertex color ⇒ **1 次 draw** |
| L5 | **流星** | 任意 | 间歇性流星划过（**同屏 ≤1 颗**） | 2 个 quad（头 + 渐变尾），与粒子同 program ⇒ **≤2 次 draw** |
| L6 | **昼夜** | 全屏 | 日间偏暖 / 夜间偏冷 | uniform 混合系数，作用于 L0/L2（⛔ 不作用于粒子，见 §5.3） |

**⛔ 已删除**：频谱条层（v2.0 的 L3）—— D6 裁决。

**每帧 draw call 预算：≤ 10**（背景+大气 1 / 星空 2 / 粒子 4 / 波形 1 / 流星 ≤2）

### 2.3 色彩系统

#### 四季调色板（改编自演示页 `SEASON_PARAMS`）

每季一组 RGB 三元组，作用于**背景渐变与大气雾色**。季节按**播放时长**推进（规则见 §5.1）。

> ⚠️ **粒子的颜色不跟季节走**（D7 逐位移植）：HTML 里四类粒子各有**固定色**
> （光点 `0xfff0b8` / 雪 `0xffffff` / 叶 `0xffa44a` / 花 8 色轮转），
> **季节只决定"哪类可见 + 透明度"**（`flowersK` / `snowFallK` / `leafFallK`，见 §2.5）⇒ ⛔ 不要给粒子套季节 tint。

| 季节 | 背景 (top→bottom) | 主色（波形） | 次色 | 大气雾色 |
|---|---|---|---|---|
| **春** (0) | `#1a2f4a → #0d1b2e → #040810` | `#7acc6e` (嫩绿) | `#b4e0a0` | `#2a5a4a` |
| **夏** (1) | `#1e3a5a → #0f2035 → #050815` | `#4f9fd4` (海洋蓝) | `#8fd0f0` | `#1a3a6a` |
| **秋** (2) | `#2a1f10 → #1a140a → #0a0604` | `#e8a030` (橙金) | `#f0c060` | `#5a3a1a` |
| **冬** (3) | `#0a1f35 → #051028 → #020410` | `#6b8ec4` (冷蓝) | `#a0c0e0` | `#1a2a5a` |

#### 季节过渡（D5：一年 = 5 分钟）

⭐ **一年 = 300 秒 = 5 分钟，四季各 75 秒** ⇒ **一首歌（3~5 分钟）基本看完四季**。

移植演示页的 **`seasonW` S 曲线**（平顶 S 曲线 ⇒ 每季读得清楚，混合集中在交界）：

| 参数 | 值 |
|---|---|
| 一年（一轮四季） | **300 s（5 分钟）** |
| 每季 | **75 s** |
| 季内：保持本季 | 前 **70%** = **52.5 s** |
| 季内：过渡到下一季 | 末 **30%** = **22.5 s** |
| 曲线 | `seasonW = smooth01(smooth01(fract(sf)))`（双重 smooth01）⇒ 有意**平顶**：稳段几乎不变，交界处才快速混合 |
| 同步 | 背景 / 大气 / 粒子 / 波形四层用**同一** transition 因子 |

> ⛔ 季节由**本效果已播放时长**推进（GL 线程自算 dt 累加），**不读音轨进度**（切歌/拖动进度会让相位跳变）。

**一首歌里看到的四季**（以 4 分钟歌为例）：
`0s 春 → 75s 夏 → 150s 秋 → 225s 冬 → 240s 回到春` ⇒ 四季全部可见，各占约 1/4。

### 2.4 几何规格

#### ⛔ 频谱条 —— 已删除（D6）

无柱、无柱宽、无圆角、无柱数。`SpectrumBarController` 与它的几何打包代码**不实现**。

#### 波形线（很淡）

```
采样点数: 128 —— 恒取 frame.waveform.size（SpectrumContract.WAVE_POINTS = 128）
渲染: GL_LINE_STRIP，per-vertex color（季节主色，alpha≈0.22）
位置: y = viewportH × 0.82
振幅: ±(viewportH × 0.022)     ← 比 v2.0 再压一半
```

⚠️ **波形线是本效果里唯一还带"频谱感"的元素**。若实测下来仍觉得抢视觉 ⇒ ⛔ **整层去掉**（改法：删除 §4.4 的 draw 调用 + 桥接不再搬 `waveform`，两处改完即可，不影响其它层）。

### 2.5 粒子系统（**形态与运动逐位移植 HTML**，D7）

> ⛔ **不发明参数**。以下四类粒子的**数量 / 尺寸 / 颜色 / 运动公式 / 混合模式 / 贴图 / 种子 / 季节强度**，
> 全部取自 `docs/globe-present.html` 的真实实现（行号见表内「出处」列）⇒ **逐位移植，不降低效果**。

#### 四类粒子（HTML 原值）

| 类型 | 数量 | 尺寸 | 颜色 | 混合 / 贴图 | 种子 | 出处 |
|---|---|---|---|---|---|---|
| **漂浮光点**（森林上方 motes） | **150** | `0.018` | `0xfff0b8` | Additive / `glowTex()`（128×128 径向渐变） | 4421 | `globe-present.html:2436-2457` |
| **雪花** | **900** | `0.014` | `0xffffff` | Additive / `snowTex()`（48×48：白 → `rgba(226,240,255,0.75)`@0.55 → `rgba(190,220,255,0)`） | 8137 | `:2464-2489` |
| **落叶** | **150** | `0.030` | `0xffa44a` | Additive / `leafTex()`（48×48 水滴形 path，`rgba(230,135,45,0.95)`） | 8267 | `:2492-2516` |
| **花朵** | **900** | `0.042` | **8 色轮转**（见下） | Normal / `flowerTex()`（32×32：中心实心@0.72 → 边缘羽化），`toneMapped:false` | 1863 | `:2528-2618` |

**花朵 8 色**（线性空间，**按序号轮转**而非纯随机 ⇒ 均匀分布不扎堆，`:2536-2545`）：
`[1.00,0.05,0.12]` 玫红 · `[1.00,0.62,0.04]` 橙 · `[0.80,0.10,0.90]` 紫 · `[1.00,0.18,0.55]` 粉 ·
`[1.00,0.80,0.06]` 金黄 · `[0.85,0.04,0.02]` 朱红 · `[1.00,1.00,1.00]` 白（点缀）· `[1.00,0.36,0.02]` 橙黄

**季节强度**（沿用 `SEASON_PARAMS`，`:478-491`）—— **季节决定"哪类可见 + 透明度"，不是"粒子被调亮"**：

| | 春 | 夏 | 秋 | 冬 | 透明度公式（HTML 原值） |
|---|---|---|---|---|---|
| `flowersK` | 0.06 | **1.00** | 0.55 | 0 | `opacity = min(1, flowersK × 1.2)`（`:3371`） |
| `snowFallK` | 0 | 0 | 0 | **1.00** | `opacity = min(1, snowFallK × 1.4)`（`:3319`） |
| `leafFallK` | 0 | 0 | **1.00** | 0 | `opacity = min(1, leafFallK × 1.5)`（`:3341`） |
| 漂浮光点 | — | — | — | — | **常驻**，无季节门控（HTML 无 `visible` 开关） |

#### 运动公式（HTML 原值 → 平面对应）

| 类型 | HTML（球面） | 平面等价（保持运动性格） |
|---|---|---|
| 光点 | `amp = 0.004 + pr()×0.010`，`sp = 0.5 + pr()×1.2` | 同值：沿 y 缓慢上浮/摆动，`amp`/`sp` 逐点随机 |
| 雪花 | `radius -= dt×speed×0.12`（speed `0.35+nr()×0.70`），`< 1.015` 重置；`bob = sin(now×0.001×0.6 + phase)×0.006` | `y -= dt×speed×0.12`，出下边界从上方绕回；`x` 方向摆动 `sin(now×0.001×0.6+phase)×0.006`（**速度与摆幅原值**） |
| 落叶 | `radius -= dt×speed×0.20`（speed `0.80+lr()×1.30`），`< 1.015` 重置；沿切向 `bob = sin(now×0.002×speed+phase)×0.020` | `y -= dt×speed×0.20`，出下边界绕回；`x` 摆动 `sin(now×0.002×speed+phase)×0.020`（**原值**） |
| 花朵 | 沿法线 `sin(now×0.002×speed + phase)×0.004`（speed `0.40+fr()×1.0`），**⛔ 不加 Y 分量**（否则被推离球面） | `y` 方向 `sin(now×0.002×speed+phase)×0.004`（**原值**）；⛔ 不加横向漂移 |

#### 花朵的昼夜调制（HTML 原值，颜色数学逐位照搬）

HTML 用「世界法线 · 太阳方向」求昼夜系数（`:3377-3412`）：

```
nightF = 1 - smooth01((d + 0.20) / 0.36)        // d = 世界法线·SUN_DIR
lum    = 0.299R + 0.587G + 0.114B
R' = lerp(R, lum, nightF×0.80) × lerp(1.0, 0.28, nightF)
G' = lerp(G, lum, nightF×0.80) × lerp(1.0, 0.32, nightF)
B' = lerp(B, lum×1.15, nightF×0.80) × lerp(1.0, 0.48, nightF)
```
⇒ **去饱和 80% + 亮度压到 28%(R)/32%(G)/48%(B)**（夜面明显变暗变淡且偏冷蓝）。

**平面对应**：本效果没有球面法线 ⇒ 用 §5.3 的**昼夜相位** `dayPhase` 代替 `d`（`nightF` 由 dayPhase 插值），
⛔ **颜色数学（去饱和 80% + 28/32/48）一字不改** ⇒ 夜面观感与 HTML 一致。

#### ⛔ 数量可以直接照搬（附换算依据）

HTML 的粒子贴在**球面**上 ⇒ 只有朝向相机的**一半**可见；本效果是**平面** ⇒ 全部可见。
按 HTML 的相机参数（`fov 45`、`position (0,0.55,3.2)` ⇒ 距球心 3.247）算：地球占屏高 **74%** ⇒ 可见圆盘面积 `0.430 H²`（16:9 全屏 `1.778 H²`）。

| | 同屏可见数 | 分布面积 | 密度 |
|---|---|---|---|
| HTML（花朵，夏） | 900 × ½ = **450** | 0.430 H² | **1047 / H²** |
| 本效果（平面） | **900** | 1.778 H² | **506 / H²**（= HTML 的 **0.48×**） |

⇒ **直接照搬 HTML 的数量 ⇒ 同屏密度约为 HTML 的一半 ⇒ 更稀，不会抢视觉**，且形态/颜色/运动与 HTML 完全一致。
⇒ ⛔ **不要**再乘 1/3、也不要乘任何"压低"系数（那是 v2.1 的错误，见 §0.3）。

#### 「微弱变化」的准确含义（D7 正解）

HTML 的粒子**完全不受音频驱动**。本效果要接音频 ⇒ 只允许**加法式的小幅呼应**：

```
粒子整体亮度 × (1 + frame.energy × 0.15)      // ⛔ 幅度 ≤ ±15%
```

⛔ **不得**用音频改变：粒子数量、尺寸、颜色、分布、运动速度、生命周期、生成节奏。
⛔ **不得**新增 HTML 没有的粒子效果（拖尾 / 闪烁 / 弹跳 / 聚散）。
⇒ 这样既"有音乐呼应"，又**完全不破坏 HTML 的观感**。

---

## 三、技术架构

### 3.1 数据流

```
ExoPlayer AudioSink
  ↓
PcmTapProcessor / PcmSpectrumTap（PCM 透传采样，后台 FFT，无需 RECORD_AUDIO）
  ↓
SpectrumAnalyzer（player/SpectrumAnalyzer.kt：64 柱感知加权映射 + gamma + AGC）
  ↓ 零分配回调
SpectrumRepository（AudioFrame 唯一写入方，双缓冲）
  ↓ frame（只读单例）
┌──────────────────────────────────────────────┐
│ 主线程                                        │
│  StellarSpectrumRenderer.onFrame(frame, ctx) │
│      ↓ publish（标量 + 数组，逐个拷进缓冲）      │
│  GlBridge（双缓冲 + volatile 索引 + 版本号）    │
└──────────────────────────────────────────────┘
      ↓ consume（单读者）
┌──────────────────────────────────────────────┐
│ GL 线程                                       │
│  StellarGlScene.onDrawFrame()                 │
│   ├─ 场景状态更新（季节 / 昼夜 / 粒子 / 流星）    │
│   ├─ 顶点数据打包（预分配 FloatArray）           │
│   ├─ VBO 上传（glBufferSubData / glBufferData） │
│   └─ 绘制：背景+大气 / 星空 / 粒子 / 波形 / 流星  │
└──────────────────────────────────────────────┘
```

**关键**：渲染器**只读** `SpectrumRepository.frame`，绝不自己算 FFT、绝不新增录音/权限。

> ⛔ **D6 连带**：`frame.spectrum`（64 柱）**不再被本效果使用** ⇒ 数据桥**不搬运它**（见 §7.3）。

### 3.2 共享 GL 基础层（`visualizer/gl/`）

> 与 E41 地球 GL 重写**共用**（见 `docs/e41-globe-opengl-plan.md` §4.1）。设计要点：

| 件 | 职责 | 关键约束 |
|---|---|---|
| `GlHost` | `GLSurfaceView` 子类：EGL 配置 / 焦点 / 触摸 / 尺寸 / 释放 | ⛔ `onTouchEvent` 必须返回 **false**（否则吃掉 Compose 手势）；⛔ `isFocusable=false`（否则吃遥控方向键） |
| `GlBridge` | 主线程 → GL 线程数据桥 | ⛔ 纯数据、不持 GL 句柄；⛔ 必须拷贝（`AudioFrame` 不得跨帧持有）；双缓冲 + volatile 索引 + **每槽版本号**（奇=写中/偶=稳定） |
| `GlCaps` | 能力探测 | ⛔ 任何一项拿不到就给默认值，**绝不抛异常**把 GL 线程搞挂 |
| `GlProjection` | 透视 / lookAt / 乘法 | column-major（下标 `m[col*4+row]`），零分配变体 `xxxInto` |
| `GlShader` | program 编译链接 / uniform & attribute location 缓存 | 编译失败打 logcat 并返回 0（⛔ 不抛） |

✅ **D3 已裁决（2026-11-08 所有者）：收敛到共享层，不写第三套。** 拆分与落地动作详见
`docs/e41-globe-opengl-plan.md` §4.1，摘要：
- **通用层**（`GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader`）⇒ **main 源集**，公开、两个分支一致；
- **上游派生层**（E52 的逐字搬运 GLSL、上游常量）⇒ 留私有分支；
- 改覆盖层副本后跑 `python scripts/sync_private_overlay.py` 同步，`--check` 必须零漂移；
- ⛔ 共享层**不得**含任何上游常量或上游 GLSL（混进去就变成公开的上游衍生件 ⇒ 泄漏）。

### 3.3 频谱侧专用件

| 件 | 职责 |
|---|---|
| `StellarGlBridge` | `GlBridge` 的本效果特化：带 **`waveform[128]`**（⚠️ E52 的 `HighwayBridge` **没有** waveform 字段）；⛔ **不带 `spectrum`**（D6 后用不到） |
| `StellarGlScene` | 场景状态 + 顶点打包 + 绘制顺序 |
| `StellarGlsl` | ESSL 常量（背景/大气、精灵点、四边形/线） |
| `StellarSeason` | 季节相位（S 曲线 + 调色板插值，**300s 周期**） |
| `StellarParticles` | 四类粒子（漂浮光点 150 / 雪花 900 / 落叶 150 / 花朵 900，**数量照搬 HTML**）；**固定数量循环复用**，⛔ 不是对象池生成-消亡 |
| `StellarParticleTextures` | 四张程序化贴图（`glowTex`/`snowTex`/`leafTex`/`flowerTex`）用 Kotlin `Canvas` 复刻 HTML 的绘制指令 |
| `StellarStarfield` | HTML 的 2D `drawStarfield` 移植（399 点 + 十字光芒，**静态**） |
| `StellarMeteors` | 流星调度（时间驱动，**同屏 ≤1 颗**） |

> ⛔ **没有** `SpectrumBarController`（D6 已删除）。

### 3.4 渲染器（View 型）

```kotlin
class StellarSpectrumRenderer : VisualizerRenderer {

    override val theme: VisualizerTheme = VisualizerTheme.STELLAR_SPECTRUM

    // ⛔ View 型 ⇒ draw 根本不被调用
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}

    override val isViewBased: Boolean get() = true

    private val bridge = StellarGlBridge()

    override fun createView(context: Context): View =
        StellarGLSurfaceView(context.applicationContext, bridge, quality)

    override fun onViewAttached() { bridge.start() }
    override fun onViewDetached() { bridge.stop() }

    // ⛔ 必须覆写：View 型有独立渲染循环，舞台徽章量的是 Compose Choreographer 回调率
    //    ⇒ 不覆写就会出现「显示 59 fps 但画面很卡」（E52 真机：徽章 52~59 而实际 6.5 fps）
    override fun renderFps(): Float = bridge.lastFps

    // ⛔ 必须覆写：View 旁路下 RenderContext.update(...) 不在 draw 块里被调用
    //    ⇒ ctx.songId 永远是旧值（VisualizerRenderer.kt:80-90）
    override fun onFrame(frame: AudioFrame, ctx: RenderContext) {
        bridge.publishAudio(frame)
        bridge.publishSongId(ctx.songId)
    }
}
```

**⚠️ main 源集当前没有 `onFrame` / `renderFps()` 的生产调用点**（只有私有覆盖层的 `VisualizerStage.kt:294-314, 351` 调它们）。
⇒ 两条路：
- **① 照 E41 现状**：自建 `Handler` tick（100 ms），**直接读** `spectrumRepository.frame`（绕过舞台）—— 与 `WorldGlobeRenderer.kt:380-384` 同款，**main 源集零改动**；
- **② 补 main 的舞台通道**：在 `VisualizerStage.kt` 里加 `onFrame` 推送 + `renderFps()` 读取（改 main 源集 ⇒ 与覆盖层同名文件冲突 ⇒ 需同步覆盖层副本）。

**建议 ②**（顺带把 E52 的缺口一起补上），但要按覆盖层规则操作。

### 3.5 EGL 与线程配置

```kotlin
setEGLContextClientVersion(pickClientVersion(context))   // 3，不支持退 2
setEGLConfigChooser(8, 8, 8, 0, 0, 0)                   // ⛔ 不要 alpha / ⛔ 不要 sRGB / 2D 不需要深度
setRenderer(glRenderer)
renderMode = RENDERMODE_CONTINENSYLY
isFocusable = false; isFocusableInTouchMode = false
override fun onTouchEvent(event: MotionEvent): Boolean = false
```

- **EGL 三条硬约束**（E52 `HighwayGLSurfaceView.kt:31-41` 原文）：⛔ 不要 alpha（第 4 参 = 0，否则与下层混合出脏色）；⛔ 不要 sRGB（会让画面变亮变灰）；深度按需。
  ⇒ 本效果是纯 2D 叠层 ⇒ **深度位设 0**（省显存与带宽）；若未来要混 3D，共享层参数化即可。
- ⚠️ **创维机 `clientVersion = 2` 也返回 3.1** ⇒ 拿不到真 ES2 上下文 ⇒ 着色器仍写 **ESSL 1.00**（ES3 上下文也接受），但**顶点数据必须走 VBO**（ES3 核心删除了客户端顶点数组）。

### 3.6 帧率与 dt

- **dt 来源**：GL 线程**自己**按 `System.nanoTime()` 差分算（⛔ 不要用 `frame.timeMs` —— 那是音频帧时钟，30 Hz 量化 ⇒ 动画会抖）。
- **上报**（照 E52 `HighwayGLSurfaceView.kt:697-706`）：
  ```kotlin
  emaFrameMs = if (emaFrameMs <= 0f) ms else emaFrameMs * 0.85f + ms * 0.15f
  lastFps = 1000f / emaFrameMs
  ```
  ⛔ **不能用 120 帧窗口**：真机 ~5 fps 时 24 秒才刷新一次 ⇒ 前 24 秒 `renderFps()` 恒 0。
- **相位推进**：⛔ **绝不用 `nowMs × 系数`**（`photo/PhotoTransitionClock.kt:17`：1.7e12 量级乘系数会把每帧抖动放大千万倍 ⇒ 画面闪现跳档）⇒ 一律 `phase += dt` 累加。

### 3.7 音频帧契约（对齐 `AudioFrame`，⛔ 不自定义帧结构）

```kotlin
class AudioFrame(barCount: Int, wavePoints: Int) {
    val spectrum: FloatArray    // 64 —— ⛔ 本效果不使用（D6）
    val waveform: FloatArray    // 128，-1..1 时域波形 —— ✅ 用于 L4 波形线
    var bass/mid/treble/energy/sectionEnergy/bassRaw: Float   // 线性律动通道
    var beat: Boolean; var pulse: Float; var bpm: Float
    var timeMs: Long; var seq: Long
}
```

| 用途 | 用哪个通道 | 原因 |
|---|---|---|
| 粒子亮度呼应 | `energy`（线性） | ⛔ **只改亮度，幅度 ≤ ±15%**（§4.3）；⛔ 不用 `treble`/`bass` 做触发 —— HTML 的粒子完全不受音频驱动 |
| 波形线 | `waveform` | 已是 -1..1 时域 |
| 整体亮度 | `energy` | 线性 |
| ~~柱高~~ | ~~`spectrum`~~ | ⛔ **D6 已取消** |

**FFT 参数不由本方案决定**（既有 `SpectrumAnalyzer`：44100Hz、64 柱感知加权、`DISPLAY_GAMMA=0.75`、`AGC_DECAY=0.995`）⇒ **⛔ 方案内不出现 FFT size / window / overlap**。

### 3.8 平滑处理

- **默认不平滑**：`frame.waveform` 已由分析层稳定输出；再叠加大窗口平滑会造成滞后感。
- 粒子的音频耦合**只有整体亮度一项**（`energy × 0.15`）⇒ 亮度本身无需再平滑（幅度本来就小，⛔ 再平滑会让音乐呼应彻底消失）。
- ⛔ **不做**粒子触发阈值（v2.1 的 `treble > 0.88` 已作废 —— HTML 的粒子完全不受音频驱动）。

---

## 四、各组件 GL 规格

### 4.1 背景 + 大气（合并为 1 个 program）

```
几何: 全屏三角（(-1,-1) (3,-1) (-1,3)）—— 1 个三角形覆盖整屏，比 quad 少一次光栅化边界
顶点属性: aPos(vec2)
uniform: uResolution(vec2), uBgTop(vec3), uBgBottom(vec3), uAtmoColor(vec3), uAtmoStrength(float)
片元:
  vec2 uv = aPos * 0.5 + 0.5;                                  // 0..1
  vec3 bg = mix(uBgTop, uBgBottom, uv.y);
  float d = length((uv - vec2(0.5, 0.0)) * vec2(uResolution.x/uResolution.y, 1.0));
  float glow = smoothstep(0.62, 0.0, d) * uAtmoStrength;
  gl_FragColor = vec4(bg + uAtmoColor * glow, 1.0);
```

⚠️ 昼夜（§2.2 L6）通过在 CPU 侧插值 `uBgTop/uBgBottom/uAtmoColor` ⇒ **着色器零分支**。

### 4.2 星空（**逐位移植 HTML 的 2D `drawStarfield`**）

> ⛔ **不用 three.js 的球面星空**（我 v2.0/v2.1 写的「1880 点 / 三档亮度 / 球面半径 900 / 6~10s 闪烁」是错的）。
> HTML 的星空是 **2D canvas 静态绘制**（`drawStarfield`，`globe-present.html:957-986`）⇒ 这才是本效果该移植的对象。

```
种子: 9137（独立 LCG：seed = (imul(seed,1664525)+1013904223) >>> 0，seed/2^32）
星点数: n = round(w × h / 5200)        ⇒ 1920×1080 = 399 个
每颗星: x = sr()*w, y = sr()*h
       r = 0.35 + sr()*1.25            （半径 0.35~1.60 px）
       a = 0.22 + sr()*0.70            （alpha 0.22~0.92）
亮星十字光芒（r > 1.30）:
       颜色 rgba(205,228,255, a×0.32)，lineWidth 0.6
       横线 (x−r×3, y)→(x+r×3, y)，竖线 (x, y−r×3)→(x, y+r×3)
背景对角渐变带（叠在背景层）:
       0.00 rgba(120,170,255,0) → 0.45 rgba(150,190,255,0.09)
       → 0.62 rgba(190,210,255,0.05) → 1.00 rgba(120,170,255,0)
```

**⭐ 关键：HTML 的星空**不闪烁**（2D canvas 静态图，只在 resize 时重画）⇒ 本效果也**不闪烁**：
- ⛔ 不做逐帧 alpha 动画 ⇒ 星空层**零每帧开销**（VBO 只在 resize 时重写一次）；
- 也顺带消除「闪烁会不会抢视觉」的争议（HTML 没有闪烁，观感就更好）。

**GL 实现**：
| 图元 | 画法 | draw calls |
|---|---|---|
| 星点 | `GL_POINTS`，per-point `aPos(vec2)` + `aSize(float)` + `aAlpha(float)` | 1 |
| 十字光芒 | `GL_LINES`，每个亮星 4 顶点（横线 2 + 竖线 2） | 1 |

⚠️ **亚像素星点**：`gl_PointSize` 在部分老驱动只支持整数（ES2 规范要求支持 1.0）⇒ HTML 的 `r = 0.35` 亚像素星可能被向上取整成 1.0px（偏大一点点）。
⇒ 退路：把 `r` 映射为 `max(r, 0.5)` 并用 alpha 补偿（`a *= r / max(r, 0.5)`），⛔ 不要为了省事把星点整体放大。
⇒ ⛔ **点精灵尺寸仍需 clamp**（E52 教训：`glsl/HighwayGlsl.kt:3733-3740` —— 不 clamp 会爆成黑方块糊屏）：
```glsl
gl_PointSize = min(aSize * uPointScale, 64.0);
```
（`uPointScale` = 视口高度/1080 ⇒ 跨分辨率一致）

### 4.3 粒子（4 类 × 各 1 次 draw）★ D7 的实现落点（**逐位移植 HTML**）

> ⛔ **不用 `aShape` 分支画形状** —— HTML 用的是**程序化贴图**（`glowTex` / `snowTex` / `leafTex` / `flowerTex`）
> ⇒ 本效果**同样生成这四张贴图**（Kotlin 侧用 `Canvas`/`Bitmap` 复刻同样的绘制指令）并上传为 GL 纹理 ⇒ 形态**逐位一致**。
> ⚠️ v2.1 的「1.0~2.4 dp / alpha ≤ 0.42 / 数量 ×1/3」是**我编的**，与 HTML 无关 ⇒ 已作废（见 §0.3）。

#### 四类粒子一览（HTML 原值）

| 类 | 数量 | 贴图 | 贴图画法（HTML 出处） | 混合 | 颜色 | 屏幕尺寸 |
|---|---|---|---|---|---|---|
| 漂浮光点 | **150** | `glowTex()` 128×128 | 径向渐变 白 → 0.70@0.25 → 0.18@0.60 → 透明@1（`:872-885`） | **Additive** | `0xfff0b8` | quad **7.2 px** |
| 雪花 | **900** | `snowTex()` 48×48 | 白 → `rgba(226,240,255,0.75)`@0.55 → `rgba(190,220,255,0)`（`:901-916`） | **Additive** | `0xffffff` | quad **5.6 px** |
| 落叶 | **150** | `leafTex()` 48×48 | 水滴形 path，`rgba(230,135,45,0.95)`（`:918-934`） | **Additive** | `0xffa44a` | quad **12.0 px** |
| 花朵 | **900** | `flowerTex()` 32×32 | 中心实心@0.72 → 边缘羽化（`:937-955`） | **Normal** | **8 色轮转**（§2.5） | quad **16.8 px**（可见实心约 12 px） |

**屏幕尺寸的换算依据**（HTML 的 `sizeAttenuation` ⇒ 表观大小随距离变化；平面版取地球附近的表观值）：
HTML 相机 `fov 45`、`position (0, 0.55, 3.2)` ⇒ 距球心 3.247 ⇒ 屏高对应 **2.69 世界单位**
⇒ **1 世界单位 ≈ 401 px @1080p** ⇒ `size × 401`：
`0.018 → 7.2` / `0.014 → 5.6` / `0.030 → 12.0` / `0.042 → 16.8`
⇒ 跨分辨率按 `× viewportHeight / 1080` 缩放。

#### 顶点属性与 VBO

```
属性: aPos(vec2) + aSize(float) + aColor(vec4)
VBO: 每类一个动态缓冲，每帧 glBufferSubData 写全量
     ⛔ 不需要对象池 —— HTML 的粒子是**固定数量循环复用**（落地/出界后重置），不是生成-消亡
程序: 4 个 program（每类一个，各绑自己的贴图与 uniform）
     ⚠️ HTML 的混合模式**逐类不同**（花朵 Normal、其余三类 Additive）⇒ 不可合并成一种
```

#### 每帧更新（运动公式见 §2.5 的「平面等价」列）

```
光点: 沿 y 缓慢浮动，amp / sp 逐点随机（HTML 原值 0.004+pr()*0.010 / 0.5+pr()*1.2）
雪花: y -= dt × speed × 0.12；出下边界从上方绕回；x 方向 sin 摆动（振幅 0.006 世界单位）
落叶: y -= dt × speed × 0.20；同上；x 方向 sin 摆动（振幅 0.020 世界单位）
花朵: y 方向 sin 起伏（振幅 0.004 世界单位）；⛔ 不加横向漂移（HTML 同此）
```

#### 可见性与透明度（HTML 原值，逐字照搬）

```
flowersK  : opacity = min(1, flowersK × 1.2)   春 0.06 / 夏 1.00 / 秋 0.55 / 冬 0
snowFallK : opacity = min(1, snowFallK × 1.4)   冬 1.00（其余 0）
leafFallK : opacity = min(1, leafFallK × 1.5)   秋 1.00（其余 0）
漂浮光点  : 常驻，无季节门控（HTML 无 visible 开关）
```
⇒ 季节强度按 §2.3 的 `SEASON_PARAMS` **逐季线性插值**，与背景/大气共用**同一个** `seasonW` 因子。

#### 花朵的昼夜调制（HTML 的颜色数学逐字照搬）

见 §2.5 的公式：`nightF` 由本效果的 `dayPhase` 代替 HTML 的「世界法线 · 太阳方向」，
⛔ **去饱和 80% + 亮度压到 28%(R)/32%(G)/48%(B) 一字不改** ⇒ 夜面观感与 HTML 一致。

#### 音频耦合（⛔ 只做亮度呼应，幅度 ≤ ±15%）

```
粒子整体亮度 × (1 + frame.energy × 0.15)
```

⛔ **不得**用音频改变：粒子数量 / 尺寸 / 颜色 / 分布 / 速度 / 生命周期 / 生成节奏。
⛔ **不得**新增 HTML 没有的粒子效果（拖尾 / 闪烁 / 弹跳 / 聚散）。
### 4.4 波形线（1 个 program，1 次 draw）★ 唯一带频谱感的元素

```
GL_LINE_STRIP，128 顶点，aPos(vec2) + aColor(vec4)
颜色: 季节主色，alpha ≈ 0.22（v2.0 是 0.45）
振幅: ±(viewportH × 0.022)（v2.0 是 0.04）
线宽: ⛔ WebGL/GLES 的 lineWidth 恒为 1 ⇒ 细线正好（不需要粗线）
```

⚠️ 若实测仍抢视觉 ⇒ ⛔ **整层去掉**（删本节 draw 调用 + 桥接不再搬 `waveform`，两处改完）。

### 4.5 流星（≤2 次 draw，自带小 program）

```
形状: 头（实心点）+ 尾（沿轨迹的渐变四边形）
生命周期: 2.5s（淡入 0.3 / 持续 2.0 / 淡出 0.2）
触发: 6~12 s 随机间隔（v2.0 是 4~9 s ⇒ 更稀疏）
轨迹: 从 (0.8W, 0.1H) 到 (0.2W, 0.4H)，长度 ≈ 0.35W
同屏: ⛔ **≤ 1 颗**（v2.0 是 ≤2）
季节色: 春偏绿 / 夏偏白 / 秋偏橙 / 冬偏蓝，alpha ≤ 0.5
```

### 4.6 Program 清单（**7 个**）

| program | 用途 | attribute | uniform |
|---|---|---|---|
| `bg` | 背景 + 大气 + 昼夜 + **星空的对角渐变带** | `aPos` | `uResolution`, `uBgTop`, `uBgBottom`, `uAtmoColor`, `uAtmoStrength` |
| `star` | 星点 | `aPos`, `aSize`, `aAlpha` | `uPointScale` |
| `starFlare` | 亮星十字光芒 | `aPos` | `uPointScale` |
| `motes` | 漂浮光点（`glowTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `snow` | 雪花（`snowTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `leaf` | 落叶（`leafTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `flower` | 花朵（`flowerTex` + Normal + vertexColors） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `line` | 波形 | `aPos`, `aColor` | `uViewProj` |

> ⛔ **没有 `bar` program**（D6 删除）。
> ⚠️ **program 数量是 TV 端首屏体验的关键**：创维的 PVRShaderCompiler 编译 PBR 变体要 **~5 秒**（E52 `HighwayGLSurfaceView.kt:83-88`）⇒ 必须
> ① **尽量合并**（本方案的 8 个 program 都是**极简片元着色器**，无 PBR/无多贴图 ⇒ 编译应远快于 PBR 变体，但仍要实测）
> ② **分帧预热**：首帧只 `glClear` 并 swap 出清屏色，program 逐帧建（E52 的 `bootStage` 模式）③ ⛔ 绝不能等到第一次绘制时才编译。
> ⚠️ 四类粒子的 program **只差「贴图 + 混合模式 + 颜色来源」** ⇒ 若实测编译慢，可合并成 **1 个 program + `uTex`/`uBlendMode` uniform**（用 `glBlendFunc` 在 draw 之间切换），program 数降到 4 个。

### 4.7 顶点数据与上传

| 缓冲 | 内容 | 大小 | 更新频率 |
|---|---|---|---|
| `bgVbo` | 全屏三角 | 6 float | 静态（一次） |
| `starVbo` | 399 点 × 4 float（pos2 + size + alpha） | ~6 KB | **静态**（HTML 的星空不闪烁 ⇒ 只在 resize 时重写） |
| `starFlareVbo` | 亮星十字 × 4 顶点 | ~1 KB | 静态 |
| `motesVbo` | 150 点 × 7 float | ~4 KB | 每帧 |
| `snowVbo` | 900 点 × 7 float | ~25 KB | 每帧 |
| `leafVbo` | 150 点 × 7 float | ~4 KB | 每帧 |
| `flowerVbo` | 900 点 × 7 float | ~25 KB | 每帧 |
| `waveVbo` | 128 点 × 6 float | ~3 KB | 每帧 |
| `meteorVbo` | 2 quad | ~0.4 KB | 每帧 |

**合计每帧上传 ≈ 62 KB**（粒子 2100 点为主）⇒ 60 fps 下 ≈ 3.7 MB/s，**可忽略**。

⚠️ ES2/ES3 都支持 `glBufferSubData`；⛔ **必须用 VBO**（ES3 上下文里 `glVertexAttribPointer` 不绑 buffer 是 `GL_INVALID_OPERATION`）。
⚠️ ⛔ **不用 instancing**：创维机没有 `GL_EXT_instanced_arrays`（E52 实测）⇒ 只能靠 ES3 核心 `glVertexAttribDivisor`；且它是**按属性索引的全局状态**，少复位一次 ⇒ 后续非实例化绘制顶点全读第 0 个元素 ⇒ 三角形退化（E52 真机：除植被外全白）。⇒ **坚持用动态 VBO**。

---

## 五、动画系统

### 5.1 季节推进（D5：一年 = 5 分钟）

| 参数 | 值 |
|---|---|
| 驱动 | GL 线程自算 `dt` 累加 ⇒ `seasonElapsedSec += dt` |
| **一年** | **300 s（5 分钟）** |
| **每季** | **75 s** |
| 季内占比 | 前 **70%**（52.5 s）保持本季，末 **30%**（22.5 s）过渡 |
| 过渡曲线 | 双重 `smoothstep`（平顶 S 曲线） |
| 颜色插值 | 所有颜色参数同一因子 |
| 切歌 | ⛔ **不重置**季节相位（连续氛围不因换歌断裂）；若需按歌重置，用 `ctx.songId` 变化时归零（`ctx.songId` 未被契约门禁禁止） |

**一首歌（3~5 min）基本看完四季** ⇒ 播放一遍就能感到"季节在走"，而不需要等十几分钟。

### 5.2 画质分档

- **View 型渲染器拿不到 `fx.level`**（那是 `RendererFx` 的概念）⇒ 按 `ctx.quality` 自己分档
  （`VisualQuality.HIGH/MEDIUM/LOW`，`AppSettings.kt:626-637`）。
- 数量表见 §4.3。
- **枚举标注**：本效果**不读** `ctx.quality.maxParticles` ⇒ 枚举必须标 `needsParticleBudget = false`
  ⇒ `VisualQuality.supports()` 对 ADV 的门槛是 `!needsParticleBudget || maxParticles > 0`（`AppSettings.kt:648`），
  而 `LOW` 档 `maxParticles = 0`（`:637`）⇒ 标 `true` 会让本效果**在 LOW 档不可选**
  （"能渲染却不可选、切走即永久回不来"那类缺陷）⇒ **标 `false`**，与 E42/E43/E47/E48/E49/E50 的既有做法一致。

### 5.3 昼夜交替

```
驱动: dayPhase += dt / 8f（⛔ 不用 nowMs × 系数）
白天: 背景顶部偏暖，大气雾色偏暖
夜晚: 背景顶部加深，大气雾色偏冷
过渡: 约 8 秒的正弦过渡
```

⚠️ 昼夜**作用于背景与大气**（uniform 插值）。
⚠️ **花朵另有自己的昼夜调制**（HTML 原样：去饱和 80% + 亮度 28/32/48，由 `dayPhase` 驱动）⇒ ⛔ **不要**再给花朵套一层昼夜 tint（会二次调制）。
⚠️ ⛔ **雪花 / 落叶 / 漂浮光点不受昼夜影响**（HTML 里它们没有这段逻辑）⇒ ⛔ 不要给它们加昼夜调制。
⚠️ 波形线 ⛔ 不受昼夜影响。

### 5.4 运行时自适应（可选）

若连续多帧 `dt` 偏大（帧率不足）⇒ 在场景内部再降一档数量（自维护 `degraded` 标志，⛔ 不改 `ctx.quality`）。

---

## 六、性能预算

### 6.1 每帧成本估算（1080p，中端设备）

| 操作 | draw calls | 估算时间 |
|---|---|---|
| 背景 + 大气（全屏三角，1 次渐变） | 1 | ~0.6ms（填充 2 Mpx） |
| 星空 399 点 + 亮星十字 | 2 | ~0.1ms（⭐ **静态**，无每帧上传） |
| 漂浮光点 150 | 1 | ~0.05ms |
| 雪花 900 | 1 | ~0.3ms |
| 落叶 150 | 1 | ~0.05ms |
| 花朵 900 | 1 | ~0.3ms |
| 波形 128 点 | 1 | ~0.1ms |
| 流星 ≤2 | 2 | ~0.1ms |
| **合计** | **≤ 10** | **≈ 1.6ms** |

⇒ **60 fps 余量充足**，且 **renderScale 可保持 1.0**（全分辨率）——
与 E52 需降到 0.30~0.40 形成鲜明对比（E52 的瓶颈是「15 次采样/像素 @1080p = 87.21 ms」的填充率，本效果每像素只算一次渐变）。

### 6.2 内存预估

| 对象 | 内存 |
|---|---|
| 顶点缓冲（合计 ≈ 62 KB） | ~62 KB |
| 四张粒子贴图（128² + 48² + 48² + 32² RGBA） | ~60 KB |
| 场景状态 / 调色板 / 粒子参数（2100 点 × ~40 B） | ~90 KB |
| **合计** | **< 220 KB** |

### 6.3 优化策略

1. **program 合并**：四类粒子的 program 只差「贴图 + 混合 + 颜色来源」⇒ 若实测编译慢，合并为 1 个 + `uTex`/`uBlendMode` uniform（§4.6）。
2. **单 draw 合批**：每类粒子 1 draw；⭐ **星空静态** ⇒ 零每帧开销。
3. **零分配**：`onDrawFrame` 内不 new 矩阵 / 数组 / 顶点缓冲；打包缓冲全部预分配。
4. **分帧预热**：首帧只 `glClear`（swap 出清屏色），program 逐帧建。
5. **降级方案**（按 `ctx.quality`）：LOW 档关粒子与流星，星空减半。
   ⚠️ ⛔ **降级只减数量、不改形态与运动**（形态永远逐位移植 HTML）。

---

## 七、数据来源与桥接

### 7.1 数据链路（已存在，⛔ 不改）

```
ExoPlayer AudioSink → PcmTapProcessor → SpectrumAnalyzer → SpectrumRepository → AudioFrame
```

渲染器**只读** `SpectrumRepository.frame`（`val frame: AudioFrame`，`SpectrumRepository.kt:40`）。

### 7.2 数据源优先级（⛔ 只有一条）

1. **`SpectrumRepository.frame`**（唯一）
2. ~~ExoPlayer AudioFrameCallback~~ —— ⛔ 不存在该通道，不要新增
3. ~~Visualizer API~~ —— ⛔ 已随 v2.37.6 移除（`SpectrumAnalyzer.kt:14`）；项目明文「不用 AudioRecord/Visualizer」（`AppSettings.kt:290-292`）
4. ~~自建 FFT~~ —— ⛔ 违反「复用现有分析层」

### 7.3 桥接内容（D6 连带：⛔ 不再搬 `spectrum`）

```kotlin
class StellarGlBridge {
    class Snap {
        @JvmField var songId: String? = null
        @JvmField var energy = 0f; var bass = 0f; var mid = 0f; var treble = 0f
        @JvmField var pulse = 0f; var beat = false
        @JvmField var waveform = FloatArray(SpectrumContract.WAVE_POINTS)   // 128
        @JvmField var seq = 0L; var timeMs = 0L
        // ⛔ 无 spectrum 字段（D6：本效果不显示频谱条 ⇒ 64 柱用不到）
    }
    fun publishAudio(frame: AudioFrame)
    fun publishSongId(id: String?)
    fun consume(dst: Snap): Boolean
    fun close()
}
```

**并发契约**（照 `HighwayBridge.kt:6-40`）：
① ⛔ 纯数据、不持 GL 句柄；② ⛔ 必须拷贝（`AudioFrame` 不得跨帧持有，`SpectrumRepository` 写端会翻转 `writeIndex` ⇒ 透传引用有**撕裂读**风险）；
③ ⛔ 不得跨帧持有引用；④ 双缓冲 + volatile 索引 + **每槽版本号**（奇=写中 / 偶=稳定）⇒ 读者能**发现**撕裂并丢弃该帧；
⑤ 发布顺序：**先槽号、后序号**（读者按「先读序号、再读槽号」看 ⇒ 不会读到半个状态）。

**零分配**：每帧只拷标量 + 128 个 float（`System.arraycopy`），≈ 528 B ⇒ 可忽略。

---

## 八、兼容性与约束

### 8.1 架构约束

- **不引入 WebView / three.js**（E41 的宿主问题是 TV 端黑屏，本效果不碰 WebView）
- **不引入新第三方依赖**
- **View 型渲染器**：直接实现 `VisualizerRenderer` + `isViewBased = true`，⛔ **不继承 `RendererFx`**（那是 Canvas 路径的模板方法基类，其 `draw` 是 `final`）
- ⛔ **不得覆写 `draw` 的实现逻辑**（View 旁路下它不被调用；写空实现即可）
- ⛔ **不得读 `ctx.nowMs`**（三个调用点语义不一致：墙钟 / 单调毫秒）⇒ 用自算 `dt`
- ⛔ **不读 `ctx.quality.maxParticles`**（数量按 `ctx.quality` 自己分档 ⇒ 枚举 `needsParticleBudget = false`）
- ⛔ **不触碰 `frame.spectrum`**（D6）⇒ 桥接不搬它

### 8.2 设备兼容

| API Level | 能力 | 适配 |
|---|---|---|
| 22 (minSdk) | `GLSurfaceView` / GLES2 / ESSL 1.00 | 可用（E52 已验证创维 5.1.1 可跑） |
| 24+ | 更稳的批量绘制 | 优化路径 |

### 8.3 无障碍

- 效果为纯视觉（非交互），无需 `contentDescription`
- ⭐ **无频闪**：星空**不闪烁**（逐位移植 HTML 的 2D 静态星空）；粒子无闪烁、无明暗脉冲；昼夜过渡是 8 秒级的缓慢插值 ⇒ 天然满足「闪烁频率 < 3Hz」（防晕动症）
- 帧率自适应降级为自动行为

### 8.4 美观红线（D6/D7 的执行清单）

| # | 红线 |
|---|---|
| M1 | ⛔ **画面里不得出现柱子 / 柱状图形** |
| M2 | ⛔ **粒子与星空的形态、颜色、运动、数量必须逐位移植 HTML**（§2.5 / §4.2 / §4.3）⇒ ⛔ 不许"为了美观"自行改小 / 改淡 / 改稀 |
| M3 | ⛔ **音频只能改亮度**（幅度 ≤ ±15%）；⛔ 不得改数量 / 尺寸 / 颜色 / 分布 / 速度 / 生命周期 |
| M4 | ⛔ 任何元素的亮度不得盖过**大气光晕 + 星空**（主体） |
| M5 | ⛔ 昼夜只作用于背景与大气（⛔ 粒子按 HTML 的"夜面变暗"机制单独处理，见 §4.3） |
| M6 | ⛔ 音效变化不得造成**可见的跳变**（阈值要迟钝、幅度要小） |

---

## 九、开发里程碑

### Phase 0: 共享 GL 层 + 门禁接入（5~8 天）
- [ ] `visualizer/gl/`：`GlHost` / `GlBridge` / `GlCaps` / `GlProjection` / `GlShader`
- [ ] 与私有覆盖层的 E52 同类件收敛（改覆盖层副本 + `sync_private_overlay.py` 同步 + `--check` 零漂移）
- [ ] 枚举新增 `STELLAR_SPECTRUM`（**五参数** + 两份 `strings.xml` key + `needsParticleBudget = false`）
- [ ] `VisualizerRendererFactory.createEnabled` 加 `when` 分支
- [ ] **登记 §10.2 的门禁**
- [ ] 补 main 源集的 `onFrame` / `renderFps()` 舞台通道（与 E41 一起做）

### Phase 1: 骨架 + 背景 + 星空（3~4 天）
- [ ] `StellarSpectrumRenderer`（View 型）+ `StellarGLSurfaceView` + `StellarGlScene`
- [ ] `bg` program：全屏三角 + 背景渐变 + 大气 + **星空的对角渐变带**
- [ ] 星空 = **逐位移植 HTML 的 2D `drawStarfield`**（399 点 + 亮星十字光芒，**静态不闪**）+ `gl_PointSize` clamp
- [ ] `GlBridge` 本效果特化（带 waveform、⛔ 不带 spectrum）
- [ ] 验证：编译通过 + 不 crash + 星空与大气观感

### Phase 2: 季节 + 昼夜（2~3 天）
- [ ] `StellarSeason`（**300s 周期** + 双重 smoothstep + 调色板插值）
- [ ] 昼夜相位（`dt` 累加，⛔ 不用 `nowMs × 系数`）
- [ ] 验证：**一首歌内能看完四季**（用 3~5 分钟音乐实测）

### Phase 3: 粒子 + 波形 + 流星（4~6 天）
- [ ] **四张程序化贴图**（`glowTex` / `snowTex` / `leafTex` / `flowerTex`）用 Kotlin `Canvas` **复刻 HTML 的绘制指令**
- [ ] 四类粒子各 1 个 program + 各自 VBO（数量 150/900/150/900，**照搬**）
- [ ] 运动公式逐位移植（§2.5 的平面等价列：速度 0.12/0.20、摆幅 0.006/0.020/0.004）
- [ ] 季节可见性与透明度（`flowersK`/`snowFallK`/`leafFallK` × 1.2/1.4/1.5）
- [ ] 花朵昼夜调制（去饱和 80% + 亮度 28/32/48，**一字不改**）
- [ ] 波形线（alpha 0.22）+ 流星（同屏 ≤1）
- [ ] 验证：**与 HTML 并排截图比对**（形态/颜色/密度）⇒ §8.4 的 M1~M6 逐条走查

### Phase 4: 性能 + 收尾（3~5 天）
- [ ] 分帧预热（首帧黑屏时长）
- [ ] 帧率上报（EMA）+ 画质分档验证
- [ ] 单测（见 §10.1）
- [ ] lint + 门禁全绿
- [ ] 真机验证（需所有者执行）
- [ ] 技术文档 §10 归档

**总计**: 约 **17~27 个工作日**（不含 Phase 0 的共享层；共享层与 E41 方案合并计算）

---

## 十、测试计划

### 10.1 单元测试

| 测试类 | 测什么 |
|---|---|
| `StellarSeasonTest` | **300s 周期**切分正确、四季各 75s、`seasonW` 双重 smoothstep 边界、`dt` 累加语义 |
| `StellarGlBridgeTest` | 双缓冲 + 版本号的撕裂检测（负向：版本号奇数时 `consume` 必须返回 false） |
| `StellarStarfieldTest` | **逐位对账 HTML 的 `drawStarfield`**：星点数 = `round(w*h/5200)`、半径 `0.35+sr()*1.25`、alpha `0.22+sr()*0.70`、亮星阈值 `r>1.30`、种子 9137 ⇒ **同种子必须产出同一组星点** |
| `StellarParticleParamsTest` | **四类粒子参数与 HTML 一致**：数量 150/900/150/900、尺寸 0.018/0.014/0.030/0.042、颜色 `0xfff0b8`/`0xffffff`/`0xffa44a`/8 色轮转、种子 4421/8137/8267/1863、运动系数 0.12/0.20/0.006/0.020/0.004 |
| `StellarParticleSeasonTest` | 透明度公式 `min(1, K×1.2/1.4/1.5)` 与季节强度映射（春 0.06 / 夏 1.00 / 秋 0.55 / 冬 0） |
| `StellarParticleNightTest` | 花朵昼夜颜色数学：去饱和 80% + 亮度 28/32/48（负向：改动任一系数必须被拒） |
| `StellarPointSizeTest` | `gl_PointSize` clamp 到 64（负向：超限必须被夹住） |

> ⚠️ 不单测 `dt`（GL 线程自算，无 Canvas 基类可测）。
> ⚠️ 门禁 `RendererBaseContractTest`（G12）**不适用**（只扫 `RendererFx` 子类；本效果是 View 型）。
> ⚠️ ⛔ **没有** `StellarBarPackingTest`（D6 已删频谱条 ⇒ 无柱几何可测）。

### 10.2 既有门禁（⛔ 新增主题必须全部通过）

| 门禁 | 文件 | 本效果要做什么 |
|---|---|---|
| **G7 `FxCoverageScanTest`** | `app/src/test/.../visualizer/fx/FxCoverageScanTest.kt` | ⛔ **View 型必须登记进 `exempt`**（照 `WorldGlobeRenderer` 的条目：「View 型（`isViewBased = true`）：draw 是空实现且根本不被调用」） |
| **`ParticleBudgetGateTest`** | `app/src/test/.../data/model/ParticleBudgetGateTest.kt` | 扫描渲染器是否读 `ctx.quality.maxParticles`，与枚举标注对比。本方案**不读** ⇒ 枚举必须 `needsParticleBudget = false` |
| **G1 `VisualizerThemeStringsGateTest`** | `app/src/test/.../data/model/VisualizerThemeStringsGateTest.kt` | `R.string.visualizer_theme_stellar_spectrum` 必须**同时**存在于 `values/strings.xml` 与 `values-en/strings.xml` |
| **G12 `RendererBaseContractTest`** | `app/src/test/.../renderers/RendererBaseContractTest.kt` | ⚠️ **不适用**（只扫 `RendererFx` 子类）。但若日后把它迁到 `RendererFx` 基类 ⇒ ① 不得覆写 `draw`/`onEnter`/`onExit` ② 不得用 `ctx.nowMs` ③ 不得自建 `private val rng = VisualizerRandom()` |

### 10.3 集成验证

- [ ] Release 包中能跑（APK 无异常增量）
- [ ] API 22 设备（创维 5.1.1）有帧率
- [ ] 波形与播放同步（无延迟 > 100ms）
- [ ] 后台 → 前台切换时恢复（GL 线程 / EGL 上下文重建）
- [ ] 暂停 → 恢复时动画不跳变
- [ ] 遥控方向键与滑动手势不被吃掉
- [ ] 切后台后显存释放（`GLSurfaceView` 不随 Activity ⇒ 需 `GlHolder` + `MainActivity` 接线，⚠️ **当前 main 与私有分支都未接线**）

### 10.4 性能基线

- 中端机 (API 24)：> 60fps
- 低端机 (API 22)：> 45fps
- draw call ≤ 10
- `renderScale = 1.0`（全分辨率）
- 每帧顶点上传 ≈ 62 KB

### 10.5 观感验收（★ 与性能同等重要）

- [ ] **画面里看不到柱子**（M1）
- [ ] **粒子与 HTML 并排截图比对**：形态 / 颜色 / 密度 / 运动 一致（M2）
- [ ] **音频只改亮度**：对比"静音"与"重低音"两帧，粒子数量/位置/速度**完全相同**，只有亮度差 ≤15%（M3）
- [ ] 主体是**星空 + 大气**（M4）
- [ ] 一首歌（3~5 min）内**四季都能看到**（D5）
- [ ] 音乐变化不产生可见跳变（M6）
- [ ] 波形线不抢视觉（若抢 ⇒ 按 §4.4 的说明整层去掉）

---

## 十一、验收标准

### 11.1 视觉验收 ✅

- [ ] **无频谱条**（D6）
- [ ] **粒子只做微弱变化**（D7）：第一眼注意不到
- [ ] 星空 / 大气 / 流星构成完整的「星际四季」氛围
- [ ] 四季色调变化一眼可见（色相差异 ±20%），且**一首歌内看完四季**
- [ ] 整体美观度不输 `globe-present.html`（比例缩放后）

### 11.2 性能验收 ✅

- [ ] 60fps 低端机 / 中端机
- [ ] 无 ANR、无跳变（`onDrawFrame` 内零分配）
- [ ] APK 无新增依赖
- [ ] 切后台再回来相位不跳

### 11.3 架构验收 ✅

- [ ] **不使用 WebView / three.js**
- [ ] 复用现有 `SpectrumAnalyzer`（⛔ 不另起 FFT、不用 Visualizer、不新增权限）
- [ ] View 型渲染器 + 覆写 `renderFps()` / `onFrame()`
- [ ] 通过 §10.2 门禁
- [ ] 与 E41 地球 GL 重写共用 `visualizer/gl/` 共享层
- [ ] 技术文档 §10 记录

---

## 十二、参考资料

- `docs/e41-globe-opengl-plan.md` —— **同批方案**（共享 `visualizer/gl/` 层、EGL/线程/生命周期的全部硬约束）
- `docs/globe-present.html` —— 视觉参考源（星空 / 大气 / 季节 / 流星 / 昼夜）
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRenderer.kt` —— 渲染器接口（`isViewBased` / `createView` / `renderFps` / `onFrame` 的必要性注释）
- `app/src/main/java/com/nasmusic/tv/visualizer/AudioFrame.kt` —— 音频帧契约（显示/律动双通道）
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumContract.kt` —— 波形点数常量（柱数常量本效果不再使用）
- `app/src/main/java/com/nasmusic/tv/visualizer/SpectrumRepository.kt` —— 唯一写入方
- `app/src/main/java/com/nasmusic/tv/player/SpectrumAnalyzer.kt` —— 既有 FFT 分析器
- `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt` —— 工厂注册
- `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` —— `VisualizerTheme` 枚举 + `VisualQuality`
- `app/src/main/java/com/nasmusic/tv/visualizer/photo/PhotoTransitionClock.kt` —— 相位推进的禁忌（`nowMs × 系数`）
- `app/src/e51-overlay/ovl/app/src/main/java/com/nasmusic/tv/visualizer/highway/` —— **E52 原生 GL 基础设施（主要参考件）**
- `docs/technical-overview.md` §10 —— 项目开发史
