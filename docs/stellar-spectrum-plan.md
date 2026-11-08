# 频谱效果开发文档 —— 星际四季频谱（OpenGL ES 版）

> **版本**: v2.3（2026-11-08，**全量数据来源审计** ⇒ 补回漏移植的**极光带**层、换掉自拟的四季配色/大气/流星参数、删除不可移植的昼夜层；逐项来源见 §0.4）
> **状态**: 开发方案评审
> **关联**:
> - `docs/globe-present.html`（**视觉与参数的唯一来源**：星空 / 极光 / 大气 / 粒子 / 流星 / 季节）
> - `docs/e41-globe-opengl-plan.md`（**同批方案**：E41 地球原生 GL 重写，含共享层设计）
> - `docs/technical-overview.md` §10

---

## 〇、决策记录（2026-11-08 所有者裁决）

### 〇.1 本轮决策（v2.1 的 D5/D6 + v2.2 重写的 D7）

| # | 决策点 | 裁决 | 落地要点 |
|---|---|---|---|
| **D5** | **季节节奏** | ✅ **一年 = 5 分钟**，四季各 **75 秒** | ⇒ **一首歌（3~5 分钟）基本看完四季**。季节由 GL 线程自算 dt 累加的播放时长推进，周期 300s。详见 §2.3 / §5.1 |
| **D6** | **频谱条** | ✅ **整层去掉** | ⛔ 不显示任何频谱条（柱子）。画面主体回到**氛围**：星空 / 极光 / 大气 / 季节 / 流星。详见 §2.2 / §4 |
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

### 〇.4 全量数据来源审计（v2.3）

对方案内**每一个数值/参数**做来源标注。分类：

| 来源 | 含义 |
|---|---|
| **【HTML】** | 逐位移植自 `docs/globe-present.html`，带行号 |
| **【仓库】** | 来自本仓代码（`SpectrumContract` / `AudioFrame` / `AppSettings` 等），带文件行号 |
| **【裁决】** | 所有者明确裁决（D5/D6/D7） |
| **【新增】** | 本效果新增（HTML 无对应物）⇒ 属工程选择，**不是移植值** |
| **【估算】** | 性能/内存的工程估算（不是视觉参数） |

| 元素 | 关键参数 | 来源 |
|---|---|---|
| **星空** | 399 点（`w*h/5200`）、`r=0.35+sr()*1.25`、`a=0.22+sr()*0.70`、亮星 `r>1.30` 加十字光芒、种子 9137、对角冷蓝带 | **【HTML】** `:957-986` |
| **极光带 ×3** | `yb=h*(0.05+r*0.042)`、波形 `sin(x*0.008+t*0.32+r*1.7)*h*0.026 + sin(x*0.021-t*0.19+r)*h*0.010`、x 步进 14px、三段 stop 色、`'lighter'` 混合 | **【HTML】** `:1157-1170` |
| **大气辉光** | `atmoGlowTex()` 512² 六段 stop + 34 个柔斑 + destination-in 遮罩、种子 7717、Additive、opacity 0.85/0.62/0.42、`rotation += dt*0.006`、季节染色 `lerp(1,tint,0.55)` | **【HTML】** `:1717-1765` |
| **背景渐变** | `radial-gradient(circle at 50% 30%, #4f9fd4 0%, #2a6fa8 55%, #103f66 100%)` | **【HTML】** `:10-12` |
| **漂浮光点** | 150 个、size 0.018、`0xfff0b8`、`glowTex()`、Additive、种子 4421、`amp=0.004+pr()*0.010`、`sp=0.5+pr()*1.2` | **【HTML】** `:2436-2457` |
| **雪花** | 900 个、size 0.014、`0xffffff`、`snowTex()`、Additive、种子 8137、`speed=0.35+nr()*0.70`、下落 `×0.12`、摆幅 0.006 | **【HTML】** `:2464-2489` |
| **落叶** | 150 个、size 0.030、`0xffa44a`、`leafTex()`、Additive、种子 8267、`speed=0.80+lr()*1.30`、下落 `×0.20`、摆幅 0.020 | **【HTML】** `:2492-2516` |
| **花朵** | 900 个、size 0.042、8 色轮转、`flowerTex()`、Normal、`toneMapped:false`、种子 1863、最小间距 0.030 rad、起伏 0.004 | **【HTML】** `:2528-2618` |
| **流星** | 间隔 `4.0+rand*5.0`、首次 3.2s、生成 `x=w*(0.15+rand*0.70)` / `y=-18`、`vx=-(90+rand*90)*0.70`、`vy=(150+rand*110)*0.70`、生命 `[0.85,1.35]s`、尾迹 0.17s、渐变 `rgba(255,255,255,0.82k)→rgba(180,220,255,0)`、线宽 1.6px、round | **【HTML】** `:1171-1190` |
| **季节 tint** | `ground` / `bark` 四组三元组、`LEAF_SEASON` 六色、`GRASS_YELLOW=[0.72,0.60,0.26]` | **【HTML】** `:477-506` |
| **季节强度** | `flowersK` 春0.06/夏1.0/秋0.55/冬0、`snowFallK` 冬1.0、`leafFallK` 秋1.0；透明度 `min(1,K×1.2/1.4/1.5)` | **【HTML】** `:478-491`、`:3319`、`:3341`、`:3371` |
| **季节过渡** | 前 70% 保持 + 末 30% `smooth01` 过渡（`SNOW_BLEND=0.30`） | **【HTML】** `:3182`、`:3186-3191` |
| **季节光照** | `intensity = 2.4*(0.88 + 0.12*cos(sf/4*2π))` ⇒ 归一化 `×(0.88+0.12*cos(...))` | **【HTML】** `:3306` |
| **屏幕尺寸换算** | 1 世界单位 ≈ 401px @1080p（`fov 45` + 距球心 3.247 ⇒ 屏高 2.69 世界单位） | **【HTML】** 相机参数 `:438`/`:1207-1209` 推导 |
| **同屏密度换算** | HTML 1047/H² vs 平面 506/H² ⇒ 0.48× | **【HTML】** 参数推导 |
| **柱数 / 波形点数** | 64 / 128（**本效果不用柱数**，仅波形点数） | **【仓库】** `SpectrumContract.kt:12,15` |
| **波形数据** | `frame.waveform`（-1..1） | **【仓库】** `AudioFrame.kt:22` |
| **律动通道** | `energy`（线性） | **【仓库】** `AudioFrame.kt:32` |
| **季节周期** | 300 s/轮（每季 75 s） | **【裁决】** D5 |
| **去掉频谱条** | 整层删除 | **【裁决】** D6 |
| **粒子形态逐位移植** | 数量/尺寸/颜色/运动/混合/贴图/种子全部照搬 | **【裁决】** D7 |
| **音频耦合幅度** | 粒子整体亮度 `×(1+energy×0.15)` | **【裁决】** D7（≤±15%） |
| **波形线** | alpha 0.22、振幅 0.022、y=0.82H | **【新增】**（HTML 无对应物） |
| **性能 / 内存估算** | 每帧 ≈2.4ms / draw call ≤11 / 上传 ≈62KB / 内存 <220KB | **【估算】** |

#### HTML 其余要素的处置（三分类，v2.3 订正）

⚠️ **订正**：本节原先把所有未移植项统称「球面场景专属」⇒ **不准确**。HTML 的核心数据
（`ELEV` 高程/类别 equirect 网格、昼面贴图、云贴图）**本质是 2D 平面图**（球面只是 three.js 把它包起来），
因此部分要素**技术上完全可移植**。准确分类如下：

| 类别 | 要素 | 能否在本效果正确显示 | 原因 |
|---|---|---|---|
| **A 数学不可移植** | 昼夜层（`nightLayer` / `nightShade`）、花朵昼夜调制（`nightF`） | ❌ **不能** | 输入是 `d = dot(球面法线, SUN_DIR)` ⇒ 平面无球面法线 ⇒ **不做，且不编造替代品** |
| **B 技术可移植、但需球面载体** | 山脉层 / 动物 / 树 / 脚印 / 龙卷风 | ⚠️ 摊平成地图后图案正确，但**「绕着地球转」的观感消失** | 它们靠 `surfaceDir(u,v)` 把经纬度映射到**球面方向** + 按高程抬升半径 ⇒ 本效果没有球面可贴 |
| **C 技术可移植且几乎原样** | 城市灯火（`makeCityTexture`）、太阳（`sunSpriteTex`）、月亮（`moonTex` + NDC 锚点）、云层（改滚动平铺）、地球贴图 / 海岸线 / 雪线 | ✅ 能 | 城市灯火是**纯 2D canvas 撒点** + `ELEV.cls` 掩膜；太阳/月亮本来就是**屏幕空间 Sprite**；其余是 equirect 平面图 |

**为什么 B/C 类在本效果里也不移植**（**这是效果定位问题，不是技术能力问题**）：
本效果是**平面氛围层**（星空 / 极光 / 大气 / 粒子），画面里**没有地球** ⇒
- B 类**无处可贴**（贴在一张世界地图上就变成另一个效果 —— 那正是旧 E41 2D 版
  「Robinson 平面地图」干过的事，2106 行，2026-10-06 作为零实例化死代码删除）；
- C 类技术上可行，但移植后画面主体会从「星空」变成「一张世界地图」，与「星际四季」定位不符。

⇒ **若目标是「要一颗地球」**，正确路线是 `docs/e41-globe-opengl-plan.md`（原生 OpenGL ES 做**真球面**），
在那里 **A/B/C 全部要素都能正确显示**（因为有真球面可贴）。

⚠️ 若日后要移植 C 类，所需资产已就位：`docs/assets/tex/terrain.png`（184 KB，R=高程 G=类别）、
`earth.png`（419 KB）、`cloud-a/b.png`、`bump.png`、`rough.png`（另见 `CONFIG.bakedDir = 'assets/tex/'`）。

---

## 一、设计目标

### 1.1 定位

创建一个**独立的音乐氛围可视化效果**，在 NowPlaying 界面中作为播放背景呈现。视觉语言源自 `docs/globe-present.html`（生态地球演示页）的美学体系——星空、大气光晕、四季色调、流星——用**原生 OpenGL ES（`GLSurfaceView` + `GLES20`）**渲染。

⭐ **v2.1 定位修正**：本效果**不显示频谱条**。它是一个**氛围型**可视化：
- **画面主体**：星空（静态）+ 极光带（缓慢波动）+ 中央大气辉光（随季节染色）+ 四季色调循环 + 季节光照呼吸 + 间歇流星；
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

- 逐行移植视觉要素（星空、极光、大气、粒子、流星、季节色、季节光照），不降低效果与美观
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
│ ≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈ │ ← 极光带 r=0（yb = h×0.050）
│  ≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈ │ ← 极光带 r=1（yb = h×0.092）
│   ≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈≈ ≈ │ ← 极光带 r=2（yb = h×0.134）
│  ★  *   ✦    ★         *    ╎    * │ ← Starfield（全屏 399 点）+ 粒子
│     ★      ╎    *                 │
│  ★     ★  ✦      ★    ╎    *    │
│        ·  ·      ·               │
│      ▒▒▒▒▒▒▒▒ 大气辉光 ▒▒▒▒▒▒▒▒   │ ← atmoGlow（中央，加色）
│                                  │
│       · · ══════════════ ·       │ ← 波形（**本效果新增**，alpha≈0.22）
│                                  │
│    ♠ ───────流星────────→  ♠     │ ← Meteor（从顶部外落下，间隔 4~9s）
└───────────────────────────────────┘
```

⭐ **画面是空的**：没有柱子、没有密集图形。主体是**星空 + 极光 + 中央大气辉光**，音乐只在细节处轻微呼应。

> ⚠️ 极光带在**顶部**、大气辉光在**中央** —— 这是 HTML 的真实布局（极光 `yb = h×(0.05+r×0.042)`；
> 大气辉光 `atmoGlow` 是围绕地球的 sprite ⇒ 平面版居中）。
> 我 v2.0~v2.2 写的「星空 y 0~30% / 大气在底部 y 85~100%」**是自拟的**，已作废。

### 2.2 视觉层面 → GL 实现（从底到顶）

| 层 | 名称 | 位置 | 内容（**HTML 原值**） | **GL 实现** |
|---|---|---|---|---|
| L0 | **背景径向渐变** | 全屏 | CSS `radial-gradient(circle at 50% 30%, #4f9fd4 0%, #2a6fa8 55%, #103f66 100%)` | 全屏三角 + 片元径向渐变 |
| L1 | **极光带 ×3** | y: **5.0% / 9.2% / 13.4%** | HTML `#fx` 的 3 层极光（`:1157-1170`） | 3 条三角形带，1 个 program |
| L2 | **星空** | 全屏 | 2D 星点 399 个 + 亮星十字光芒 + 对角冷蓝带 | `GL_POINTS` + `GL_LINES` ⇒ **2 次 draw** |
| L3 | **大气辉光** | **中央** | `atmoGlowTex()`（512² 贴图 + 34 个柔斑）+ Additive + 季节染色 | 全屏 quad + 贴图采样 ⇒ **1 次 draw** |
| L4 | **粒子 ×4** | 全屏 | HTML 的四类（漂浮光点 / 雪花 / 落叶 / 花朵） | 每类 1 次 `GL_POINTS` ⇒ **4 次 draw** |
| L5 | **波形线** | y: 82% | **本效果新增**（HTML 无对应物） | `GL_LINE_STRIP` × 128 点 ⇒ **1 次 draw** |
| L6 | **流星** | 从顶部外落下 | HTML `drawFx` 的流星（`:1171-1190`） | `GL_LINES` ⇒ **1 次 draw** |

**⛔ 已删除**：频谱条层（v2.0 的 L3，D6 裁决）；**昼夜层**（v2.3，HTML 的昼夜依赖球面法线点积，平面不可移植 ⇒ 见 §5.3）。

**每帧 draw call 预算：≤ 11**（背景 1 / 极光 1 / 星空 2 / 大气 1 / 粒子 4 / 波形 1 / 流星 1）

### 2.3 色彩系统

#### 底色（HTML 的 CSS，`:10-12`）

```
background: radial-gradient(circle at 50% 30%, #4f9fd4 0%, #2a6fa8 55%, #103f66 100%);
```

⭐ 圆心在 **(50%, 30%)**（不是正中心）—— 逐位照搬。

#### 四季调色板（**全部取自 HTML 的 `SEASON_PARAMS` / `LEAF_SEASON` / `GRASS_YELLOW`**）

| 季 | `ground` tint（乘法） | `bark` tint（乘法） | `LEAF_SEASON`（绝对色） | `grassYellowK` |
|---|---|---|---|---|
| **春** (0) | `[0.94, 1.14, 0.94]` | `[1.00, 1.02, 0.96]` | `0x9fd97a`（淡绿） | 0.00 |
| **夏** (1) | `[1.06, 1.05, 0.90]` | `[1.00, 1.00, 1.00]` | `0x4f7a2e`（深绿） | 0.00 |
| **秋** (2) | `[1.22, 0.88, 0.58]` | `[1.08, 0.94, 0.78]` | `[0xe8b53a, 0xdc8a2e, 0xc23b2a, 0x8f2318, 0xf0d05a]`（5 色） | **1.00** |
| **冬** (3) | `[0.76, 0.90, 1.18]` | `[0.78, 0.86, 0.99]` | `0x6f7f96`（冷蓝灰） | 0.00 |

- `GRASS_YELLOW = [0.72, 0.60, 0.26]`（`:477`）
- 季节强度 `flowersK` / `snowFallK` / `leafFallK` 见 §2.5（**已逐位照搬**）

**平面的落地映射**（HTML 的 tint 是乘在地球贴图上的；本效果没有贴图）：

- **背景渐变 × `ground` tint**（逐通道乘法）⇒ 季节感来自与 HTML 相同的 tint 曲线；
- **`LEAF_SEASON` 的绝对色**用作本效果的「季节主色」（波形线 / 极光染色）；
- ⛔ **不要给粒子套季节 tint**（HTML 里四类粒子各有固定色，季节只决定「哪类可见 + 透明度」，见 §2.5）。

> ⚠️ **v2.0~v2.2 写的四季背景三段色（`#1a2f4a → #0d1b2e → #040810` 等）与主色/次色是我自拟的** ⇒ 已作废。

#### 极光带配色（HTML `:1159-1162`）

| stop | 颜色 |
|---|---|
| 0.00 | `rgba(90,220,190,0)` |
| 0.45 | r=1 → `rgba(120,170,255,0.070)`；r=0/2 → `rgba(90,235,190,0.082)` |
| 1.00 | `rgba(160,120,255,0)` |

#### 大气辉光配色（`atmoGlowTex()`，`:1727-1733`）

| stop | 颜色 |
|---|---|
| 0.00 | `rgba(96,170,255,0)` |
| 0.05 | `rgba(120,195,255,0.42)` |
| 0.20 | `rgba(140,205,255,0.26)` |
| 0.48 | `rgba(160,215,255,0.11)` |
| 0.78 | `rgba(175,220,255,0.035)` |
| 1.00 | `rgba(175,220,255,0)` |

#### 季节过渡（D5：一年 = 5 分钟）
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

#### 花朵的昼夜调制（HTML 原值）—— ⛔ **平面版不做（不可移植）**

HTML 用「世界法线 · 太阳方向」求昼夜系数（`:3377-3412`）：

```
nightF = 1 - smooth01((d + 0.20) / 0.36)        // d = 世界法线·SUN_DIR
lum    = 0.299R + 0.587G + 0.114B
R' = lerp(R, lum, nightF×0.80) × lerp(1.0, 0.28, nightF)
G' = lerp(G, lum, nightF×0.80) × lerp(1.0, 0.32, nightF)
B' = lerp(B, lum×1.15, nightF×0.80) × lerp(1.0, 0.48, nightF)
```

⛔ **平面没有球面法线 ⇒ `d` 无从计算 ⇒ 这段不可移植** ⇒ **本效果不做花朵昼夜调制，花朵保持固定 8 色**。
（我 v2.0~v2.2 用「昼夜相位代替 `d`」的做法**是自拟的**，已作废。）
⇒ 场景层面的季节光照变化改用 HTML 的 `sunLight.intensity` 季节调制（§5.3，**可移植**）。

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
│   ├─ 场景状态更新（季节 / 季节光照 / 粒子 / 流星） │
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

### 4.1 背景 + 极光带 + 大气辉光（3 个 program）

> ⚠️ **极光带是 HTML `#fx` canvas 的一等视觉要素，我 v2.0~v2.2 整层漏移植** ⇒ v2.3 补上（`globe-present.html:1152-1170`）。

#### 4.1.1 背景径向渐变（HTML 的 CSS，`:10-12`）

```
background: radial-gradient(circle at 50% 30%, #4f9fd4 0%, #2a6fa8 55%, #103f66 100%);
```
- 全屏三角（`(-1,-1) (3,-1) (-1,3)`）+ 片元径向渐变；
- ⚠️ **圆心 (50%, 30%)**：CSS 的 y 向下、GL 的 NDC y 向上 ⇒ 片元里圆心取 `(0.5, 0.7)`；
- 渐变 stop：`0% #4f9fd4` / `55% #2a6fa8` / `100% #103f66`；
- **季节染色**：背景 × `ground` tint（逐通道乘法，§2.3）。

#### 4.1.2 极光带 ×3（HTML `#fx`，`:1157-1170`）

```
canvas: fxScale = 0.70（分辨率缩放）；混合 globalCompositeOperation = 'lighter'（加色）
层数: 3（r = 0, 1, 2）
基线: yb = h * (0.05 + r * 0.042)          ⇒ 0.050h / 0.092h / 0.134h
垂直渐变: 从 (0, yb - h*0.08) 到 (0, yb + h*0.14)
stops:  0.00 rgba(90,220,190,0)
        0.45 r===1 ? rgba(120,170,255,0.070) : rgba(90,235,190,0.082)
        1.00 rgba(160,120,255,0)
波形:  y = yb + sin(x*0.008 + t*0.32 + r*1.7) * h*0.026
            + sin(x*0.021 - t*0.19 + r)     * h*0.010
        （x 步进 14 px；t = now * 0.001）
封闭:  lineTo(w, yb + h*0.16); lineTo(0, yb + h*0.16); closePath(); fill()
```

**GL 实现**：每层一条**三角形带**（`GL_TRIANGLE_STRIP`），顶点 = 每个 x 采样点的上下两个边界点。
- 采样点数 `= w / 14`（1080p 宽 1920 ⇒ 137 点/层）⇒ 3 层 × 137 × 2 顶点 × 2 float ≈ **3.3 KB/帧**；
- 波形在**顶点着色器**里算（`uTime` / `uLayer` uniform）⇒ CPU 侧只更新 `uTime`；
- 渐变用 `vUv.y` 映射到三段 stop；
- 混合：`glBlendFunc(GL_SRC_ALPHA, GL_ONE)`（加色，对应 HTML 的 `'lighter'`）。

#### 4.1.3 大气辉光（`atmoGlow`，`:1717-1765`）

```
贴图 atmoGlowTex(): 512×512 canvas
  主径向 createRadialGradient(c, c, s*0.315, c, c, s*0.5)，c = 256
    0.00 rgba(96,170,255,0)      0.05 rgba(120,195,255,0.42)
    0.20 rgba(140,205,255,0.26)  0.48 rgba(160,215,255,0.11)
    0.78 rgba(175,220,255,0.035) 1.00 rgba(175,220,255,0)
  不匀大斑 34 个（'lighter'）：ang = rand*2π，rr = s*(0.32+rand*0.14)，
    rad = s*(0.035+rand*0.085)，色 rgba(205,232,255,0.13) → rgba(205,232,255,0)
  抗硬边遮罩（'destination-in'，径向 s*0.30 → s*0.5）：
    0.00 rgba(0,0,0,0)   0.12 rgba(0,0,0,1)   0.65 rgba(0,0,0,0.65)   1.00 rgba(0,0,0,0)
  独立 LCG 种子 7717
Sprite: AdditiveBlending，opacity 0.85（画质 lv0/lv1/lv2 = 0.85/0.62/0.42），
        scale 3.0（直径 3 个地球半径），renderOrder 0
动画: rotation += dt * 0.006
季节: color = lerp(1, tint, 0.55)   逐通道（tint = 当季 ground tint）
```

**平面的落地**：
- 辉光**居中**（HTML 里它罩着地球）⇒ 全屏 quad 采样该贴图；
- ⚠️ **贴图放大 1.11×**：HTML 里辉光直径 3.0 世界单位 ≈ **1.11 × 屏高**（地球占屏高 74% ⇒ 1 世界单位 ≈ 401px ⇒ 3.0 ≈ 1203px）
  ⇒ 采样贴图**中心 90%** 区域（`uv` 映射到 `0.05~0.95`）即可得到同样的相对大小；
- 季节染色 `lerp(1, tint, 0.55)` 逐通道 × 贴图采样值；
- 缓慢自转 `rotation += dt * 0.006`（对贴图做 UV 旋转）。
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

#### 花朵的昼夜调制 —— ⛔ **平面版不做**

HTML 的花朵昼夜依赖**球面法线 · 太阳方向**（`:3377-3412`）⇒ 平面不可移植 ⇒ **不做**，花朵保持固定 8 色。
（详见 §2.5 的同名小节与 §5.3。）
场景层面的季节光照变化走 §5.3 的 `sunLight.intensity` 季节调制。

#### 音频耦合（⛔ 只做亮度呼应，幅度 ≤ ±15%）

```
粒子整体亮度 × (1 + frame.energy × 0.15)
```

⛔ **不得**用音频改变：粒子数量 / 尺寸 / 颜色 / 分布 / 速度 / 生命周期 / 生成节奏。
⛔ **不得**新增 HTML 没有的粒子效果（拖尾 / 闪烁 / 弹跳 / 聚散）。
### 4.4 波形线（1 个 program，1 次 draw）

⚠️ **本效果新增 —— HTML 里没有波形线**（`globe-present.html` 无对应物）⇒ 以下参数是**工程选择**，不是移植值：
α / 振幅 / 位置若要调，按 §8.4 的美观红线自行斟酌，但**必须保持"很淡、不抢视觉"**。

```
GL_LINE_STRIP，128 顶点，aPos(vec2) + aColor(vec4)
颜色: 季节主色（LEAF_SEASON），alpha ≈ 0.22
振幅: ±(viewportH × 0.022)
位置: y = viewportH × 0.82
线宽: ⛔ WebGL/GLES 的 lineWidth 恒为 1 ⇒ 细线正好（不需要粗线）
```

⚠️ 若实测仍抢视觉 ⇒ ⛔ **整层去掉**（删本节 draw 调用 + 桥接不再搬 `waveform`，两处改完）。

### 4.5 流星（**逐位移植 HTML `#fx` 的 `drawFx`**，`:1171-1190`）

```
canvas: fxScale = 0.70（backing store = round(w*0.70) × round(h*0.70)），CSS 铺满全屏
       ⇒ 画布空间 → 屏幕空间要 ×(1/0.70)
数量: 无上限（数组 push/splice，无 cap）—— ⛔ 我 v2.0~v2.2 写的「同屏 ≤1」是自拟的
首次: nextMeteor = 3.2 s
间隔: CONFIG.meteorMin + rand*(CONFIG.meteorMax - CONFIG.meteorMin) = 4.0 + rand*5.0 ⇒ [4.0, 9.0) s
生成: x = w*(0.15 + rand*0.70)，y = -18（画布上方外）
速度: vx = -(90 + rand*90) * fxScale  ⇒ 画布 [-126, -63] px/s ⇒ 屏幕 [-180, -90] px/s
      vy = (150 + rand*110) * fxScale ⇒ 画布 [105, 182] px/s ⇒ 屏幕 [150, 260] px/s
生命: max = 0.85 + rand*0.5 ⇒ [0.85, 1.35] s
每帧: life += dt; x += vx*dt; y += vy*dt
销毁: k = 1 - life/max <= 0  或  y > h*1.1
尾迹: 终点 tx = x - vx*0.17，ty = y - vy*0.17（沿速度反方向 0.17 秒）
渐变: createLinearGradient(x, y, tx, ty)
      stop0 rgba(255,255,255, 0.82*k) → stop1 rgba(180,220,255, 0)
线宽: lineWidth = 1.6 * fxScale = 1.12（画布）⇒ 1.6 px（屏幕）；lineCap = 'round'
混合: globalCompositeOperation = 'lighter'（加色）
随机: Math.random()（刻意不消耗全局 rand）
```

**GL 实现**：`GL_LINES`（每颗流星 1 条线段：头 → 尾），顶点带 `aColor`（头 `rgba(255,255,255,0.82k)` → 尾透明）。
⚠️ 混合用 `glBlendFunc(GL_SRC_ALPHA, GL_ONE)`（加色，对应 `'lighter'`）。
⛔ **不随季节变色**（HTML 的流星没有季节色）—— 我 v2.0~v2.2 写的「季节色：春偏绿/夏偏白…」是自拟的 ⇒ 作废。

### 4.6 Program 清单（**8 个**）

| program | 用途 | attribute | uniform |
|---|---|---|---|
| `bg` | 背景径向渐变 + 星空的对角冷蓝带 + 季节 tint | `aPos` | `uResolution`, `uBgInner`, `uBgOuter`, `uTint` |
| `aurora` | 极光带 ×3（顶点着色器算波形） | `aPos` | `uTime`, `uLayer`, `uHeight` |
| `star` | 星点 | `aPos`, `aSize`, `aAlpha` | `uPointScale` |
| `starFlare` | 亮星十字光芒 | `aPos` | `uPointScale` |
| `atmo` | 大气辉光（`atmoGlowTex` + Additive + 季节染色） | `aPos`, `aUv` | `uTex`, `uTint`, `uOpacity`, `uRot` |
| `motes` | 漂浮光点（`glowTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `snow` | 雪花（`snowTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `leaf` | 落叶（`leafTex` + Additive） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `flower` | 花朵（`flowerTex` + Normal + vertexColors） | `aPos`, `aSize`, `aColor` | `uPointScale`, `uTex` |
| `line` | 波形 | `aPos`, `aColor` | `uViewProj` |
| `meteor` | 流星尾迹（加色线段） | `aPos`, `aColor` | — |

> ⛔ **没有 `bar` program**（D6 删除）；⛔ **没有昼夜 program**（v2.3 删除，见 §5.3）。
> ⚠️ **program 数量是 TV 端首屏体验的关键**：创维的 PVRShaderCompiler 编译 PBR 变体要 **~5 秒**（E52 `HighwayGLSurfaceView.kt:83-88`）⇒ 必须
> ① **尽量合并**（本方案的 program 都是**极简片元着色器**，无 PBR/无多贴图 ⇒ 编译应远快于 PBR 变体，但仍要实测）
> ② **分帧预热**：首帧只 `glClear` 并 swap 出清屏色，program 逐帧建（E52 的 `bootStage` 模式）③ ⛔ 绝不能等到第一次绘制时才编译。
> ⚠️ 四类粒子的 program **只差「贴图 + 混合模式 + 颜色来源」** ⇒ 若实测编译慢，可合并成 **1 个 program + `uTex`/`uBlendMode` uniform**（用 `glBlendFunc` 在 draw 之间切换）。

### 4.7 顶点数据与上传

| 缓冲 | 内容 | 大小 | 更新频率 |
|---|---|---|---|
| `bgVbo` | 全屏三角 | 6 float | 静态（一次） |
| `auroraVbo` | 3 层 × 137 点 × 2 顶点 × 2 float | ~3.3 KB | 静态（波形在顶点着色器算，只更新 `uTime`） |
| `starVbo` | 399 点 × 4 float（pos2 + size + alpha） | ~6 KB | **静态**（HTML 的星空不闪烁 ⇒ 只在 resize 时重写） |
| `starFlareVbo` | 亮星十字 × 4 顶点 | ~1 KB | 静态 |
| `atmoVbo` | 全屏三角 + UV | 8 float | 静态（一次） |
| `motesVbo` | 150 点 × 7 float | ~4 KB | 每帧 |
| `snowVbo` | 900 点 × 7 float | ~25 KB | 每帧 |
| `leafVbo` | 150 点 × 7 float | ~4 KB | 每帧 |
| `flowerVbo` | 900 点 × 7 float | ~25 KB | 每帧 |
| `waveVbo` | 128 点 × 6 float | ~3 KB | 每帧 |
| `meteorVbo` | ≤N 条线段 × 4 float | ~0.1 KB/颗 | 每帧 |

**合计每帧上传 ≈ 62 KB**（粒子 2100 点为主）⇒ 60 fps 下 ≈ 3.7 MB/s，**可忽略**。


⚠️ ES2/ES3 都支持 `glBufferSubData`；⛔ **必须用 VBO**（ES3 上下文里 `glVertexAttribPointer` 不绑 buffer 是 `GL_INVALID_OPERATION`）。
⚠️ ⛔ **不用 instancing**：创维机没有 `GL_EXT_instanced_arrays`（E52 实测）⇒ 只能靠 ES3 核心 `glVertexAttribDivisor`；且它是**按属性索引的全局状态**，少复位一次 ⇒ 后续非实例化绘制顶点全读第 0 个元素 ⇒ 三角形退化（E52 真机：除植被外全白）。⇒ **坚持用动态 VBO**。

---

## 五、动画系统

### 5.1 季节推进（D5：一年 = 5 分钟）

| 参数 | 值 | 出处 |
|---|---|---|
| 驱动 | GL 线程自算 `dt` 累加 ⇒ `seasonElapsedSec += dt` | — |
| **一年** | **300 s（5 分钟）** | ⛔ **所有者裁决 D5**（HTML 是 60 s/轮 ⇒ 本方案放慢 5×） |
| **每季** | **75 s** | 同上 |
| 季内占比 | 前 **70%**（52.5 s）保持本季，末 **30%**（22.5 s）过渡 | ✅ HTML 原值（`:3182` `SNOW_BLEND = 0.30`；`:3186-3191` `w = (st - 0.70)/0.30` → `smooth01`） |
| 过渡曲线 | 双重 `smoothstep`（平顶 S 曲线） | ✅ HTML 原值（`:3186-3191`） |
| 颜色插值 | 所有颜色参数同一因子 | ✅ HTML 原值（`seasonW`） |
| 切歌 | ⛔ **不重置**季节相位（连续氛围不因换歌断裂）；若需按歌重置，用 `ctx.songId` 变化时归零（`ctx.songId` 未被契约门禁禁止） | — |

**一首歌（3~5 min）基本看完四季** ⇒ 播放一遍就能感到"季节在走"，而不需要等十几分钟。

> ⚠️ **与 HTML 的唯一差异是周期长度**：HTML `CONFIG.seasonPeriod = 60`（秒/轮，4 季各 15 s，`:453`），
> 本方案按所有者裁决 D5 放慢到 **300 s/轮（每季 75 s）** ⇒ 其余（70/30 过渡带、双重 smoothstep、
> 各季 tint 与强度）**全部照搬**。

### 5.2 画质分档

- **View 型渲染器拿不到 `fx.level`**（那是 `RendererFx` 的概念）⇒ 按 `ctx.quality` 自己分档
  （`VisualQuality.HIGH/MEDIUM/LOW`，`AppSettings.kt:626-637`）。
- 数量表见 §4.3。
- **枚举标注**：本效果**不读** `ctx.quality.maxParticles` ⇒ 枚举必须标 `needsParticleBudget = false`
  ⇒ `VisualQuality.supports()` 对 ADV 的门槛是 `!needsParticleBudget || maxParticles > 0`（`AppSettings.kt:648`），
  而 `LOW` 档 `maxParticles = 0`（`:637`）⇒ 标 `true` 会让本效果**在 LOW 档不可选**
  （"能渲染却不可选、切走即永久回不来"那类缺陷）⇒ **标 `false`**，与 E42/E43/E47/E48/E49/E50 的既有做法一致。

### 5.3 季节光照强度（**HTML 的 `sunLight.intensity` 季节调制**，`:3306`）

> ⛔ **本效果没有昼夜层** —— HTML 的昼夜（`nightLayer` `:1552-1586` / `nightShade` `:1591-1616`）
> 的输入是**球面法线 · 太阳方向**的点积（`d = dot(normalize(vWN), SUN_DIR)`，
> `night = 1 - smoothstep(-0.20, 0.16, d)`）⇒ **平面没有球面法线 ⇒ 不可移植**。
> 我 v2.0~v2.2 写的「伪昼夜 `dayPhase`（8 秒正弦）」**是自拟的**，已作废。

**可移植的是 HTML 的季节光照调制**（只依赖季节相位 `sf`，与几何无关）：

```
sunLight = DirectionalLight(0xfff3d6, 2.4)                 // :1218
sunLight.position = SUN_DIR * 10                            // :1219
季节调制: intensity = 2.4 * (0.88 + 0.12 * cos(sf / 4 * 2π))   // :3306
          ⇒ 区间 [2.112, 2.400] ⇒ 相对基准 ±6%
```

**平面落地**：
```
全局亮度 × (0.88 + 0.12 * cos(sf / 4 * 2π))     // 逐位照搬 HTML，只把 2.4 归一化掉
```
- `sf` = 季节相位（§5.1）⇒ 一年 300 s 内亮度做**一个完整正弦周期**（每季 75 s 走 1/4 周期）；
- 幅度仅 ±6% ⇒ **极微弱**（符合 D7「音乐只轻微呼应」的基调），⛔ 不要放大。

**⛔ 同时删除**：
- 花朵的昼夜调制（HTML 的 `nightF = 1 - smooth01((d+0.20)/0.36)` + 去饱和 80% + 亮度 28/32/48）——
  同样依赖球面法线 `d` ⇒ 平面不可移植 ⇒ **花朵保持固定 8 色**；
- 雪花 / 落叶 / 漂浮光点 / 波形线：HTML 里它们本就没有昼夜逻辑 ⇒ ⛔ 不加。

### 5.4 运行时自适应（可选）

若连续多帧 `dt` 偏大（帧率不足）⇒ 在场景内部再降一档数量（自维护 `degraded` 标志，⛔ 不改 `ctx.quality`）。

---

## 六、性能预算

### 6.1 每帧成本估算（1080p，中端设备）

| 操作 | draw calls | 估算时间 |
|---|---|---|
| 背景径向渐变（全屏三角，1 次渐变） | 1 | ~0.6ms（填充 2 Mpx） |
| 极光带 ×3（3 条三角带，顶端区域） | 1 | ~0.3ms |
| 星空 399 点 + 亮星十字 | 2 | ~0.1ms（⭐ **静态**，无每帧上传） |
| 大气辉光（全屏 quad + 512² 贴图采样） | 1 | ~0.4ms |
| 漂浮光点 150 | 1 | ~0.05ms |
| 雪花 900 | 1 | ~0.3ms |
| 落叶 150 | 1 | ~0.05ms |
| 花朵 900 | 1 | ~0.3ms |
| 波形 128 点 | 1 | ~0.1ms |
| 流星（无上限，实测同屏 1~2 颗） | 1 | ~0.1ms |
| **合计** | **≤ 11** | **≈ 2.4ms** |

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
- ⭐ **无频闪**：星空**不闪烁**（逐位移植 HTML 的 2D 静态星空）；粒子无闪烁、无明暗脉冲；
  季节光照是 300 s 一轮的正弦（幅度 ±6%）⇒ 天然满足「闪烁频率 < 3Hz」（防晕动症）
- 帧率自适应降级为自动行为

### 8.4 美观红线（D6/D7 的执行清单）

| # | 红线 |
|---|---|
| M1 | ⛔ **画面里不得出现柱子 / 柱状图形** |
| M2 | ⛔ **粒子与星空的形态、颜色、运动、数量必须逐位移植 HTML**（§2.5 / §4.2 / §4.3）⇒ ⛔ 不许"为了美观"自行改小 / 改淡 / 改稀 |
| M3 | ⛔ **音频只能改亮度**（幅度 ≤ ±15%）；⛔ 不得改数量 / 尺寸 / 颜色 / 分布 / 速度 / 生命周期 |
| M4 | ⛔ 任何元素的亮度不得盖过**大气光晕 + 星空**（主体） |
| M5 | ⛔ **不做昼夜层**（HTML 依赖球面法线，平面不可移植）；只做 HTML 的季节光照调制（±6%） |
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

### Phase 1: 骨架 + 背景 + 极光 + 星空 + 大气辉光（4~5 天）
- [ ] `StellarSpectrumRenderer`（View 型）+ `StellarGLSurfaceView` + `StellarGlScene`
- [ ] `bg` program：全屏三角 + **HTML CSS 原值**的径向渐变（圆心 50%,30%）+ 星空的对角冷蓝带 + 季节 `ground` tint
- [ ] **极光带 ×3**（HTML `#fx` 逐位移植：3 条三角带 + 顶点着色器算波形 + `'lighter'` 加色混合）
- [ ] 星空 = **逐位移植 HTML 的 2D `drawStarfield`**（399 点 + 亮星十字光芒，**静态不闪**）+ `gl_PointSize` clamp
- [ ] **大气辉光** = 逐位移植 `atmoGlowTex()`（512² 六段 stop + 34 柔斑 + destination-in 遮罩）+ Additive + 季节染色 `lerp(1,tint,0.55)` + `rotation += dt*0.006`
- [ ] `GlBridge` 本效果特化（带 waveform、⛔ 不带 spectrum）
- [ ] 验证：编译通过 + 不 crash + 背景/极光/星空/大气观感

### Phase 2: 季节 + 季节光照（2~3 天）
- [ ] `StellarSeason`（**300s 周期** + 双重 smoothstep + `SEASON_PARAMS` 调色板插值）
- [ ] **季节光照强度**（`0.88 + 0.12*cos(sf/4*2π)`，**照搬 HTML `sunLight.intensity`**）
- [ ] 验证：**一首歌内能看完四季**（用 3~5 分钟音乐实测）

### Phase 3: 粒子 + 波形 + 流星（4~6 天）
- [ ] **四张程序化贴图**（`glowTex` / `snowTex` / `leafTex` / `flowerTex`）用 Kotlin `Canvas` **复刻 HTML 的绘制指令**
- [ ] 四类粒子各 1 个 program + 各自 VBO（数量 150/900/150/900，**照搬**）
- [ ] 运动公式逐位移植（§2.5 的平面等价列：速度 0.12/0.20、摆幅 0.006/0.020/0.004）
- [ ] 季节可见性与透明度（`flowersK`/`snowFallK`/`leafFallK` × 1.2/1.4/1.5）
- [ ] 波形线（alpha 0.22，**本效果新增**）+ 流星（**HTML `drawFx` 参数逐位移植**）
- [ ] 验证：**与 HTML 并排截图比对**（形态/颜色/密度/轨迹）⇒ §8.4 的 M1~M6 逐条走查

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
| `StellarAuroraTest` | **逐位对账 HTML 的 `#fx` 极光**：`yb = h*(0.05+r*0.042)`、波形 `sin(x*0.008+t*0.32+r*1.7)*h*0.026 + sin(x*0.021-t*0.19+r)*h*0.010`、x 步进 14px、三段 stop 色 |
| `StellarAtmoGlowTest` | **逐位对账 `atmoGlowTex()`**：六段 stop 色、34 个柔斑（`rr=s*(0.32+rand*0.14)`、`rad=s*(0.035+rand*0.085)`）、destination-in 遮罩四段 stop、种子 7717 |
| `StellarMeteorTest` | **逐位对账 `drawFx` 的流星**：间隔 `4.0+rand*5.0`、首次 3.2s、生成 `x=w*(0.15+rand*0.70)`/`y=-18`、`vx=-(90+rand*90)*0.70`、`vy=(150+rand*110)*0.70`、生命 `[0.85,1.35]s`、尾迹 0.17s、渐变两段 stop |
| `StellarParticleParamsTest` | **四类粒子参数与 HTML 一致**：数量 150/900/150/900、尺寸 0.018/0.014/0.030/0.042、颜色 `0xfff0b8`/`0xffffff`/`0xffa44a`/8 色轮转、种子 4421/8137/8267/1863、运动系数 0.12/0.20/0.006/0.020/0.004 |
| `StellarParticleSeasonTest` | 透明度公式 `min(1, K×1.2/1.4/1.5)` 与季节强度映射（春 0.06 / 夏 1.00 / 秋 0.55 / 冬 0） |
| `StellarSeasonLightTest` | 季节光照强度公式 `0.88 + 0.12*cos(sf/4*2π)` 与 HTML 的 `sunLight.intensity` 一致（区间 [0.88, 1.00]） |
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
- draw call ≤ 11
- `renderScale = 1.0`（全分辨率）
- 每帧顶点上传 ≈ 62 KB

### 10.5 观感验收（★ 与性能同等重要）

- [ ] **画面里看不到柱子**（M1）
- [ ] **粒子与 HTML 并排截图比对**：形态 / 颜色 / 密度 / 运动 一致（M2）
- [ ] **极光带与 HTML 并排比对**：3 层位置（5.0/9.2/13.4%）、波形起伏幅度、配色、加色混合一致
- [ ] **大气辉光与 HTML 并排比对**：居中、六段 stop 渐变、季节染色 `lerp(1,tint,0.55)` 一致
- [ ] **流星与 HTML 并排比对**：轨迹方向（右上→左下）、尾迹长度 0.17s、间隔 4~9s 一致
- [ ] **音频只改亮度**：对比"静音"与"重低音"两帧，粒子数量/位置/速度**完全相同**，只有亮度差 ≤15%（M3）
- [ ] 主体是**星空 + 极光 + 大气辉光**（M4）
- [ ] 一首歌（3~5 min）内**四季都能看到**（D5）
- [ ] 音乐变化不产生可见跳变（M6）
- [ ] 波形线不抢视觉（若抢 ⇒ 按 §4.4 的说明整层去掉）
- [ ] ⛔ **画面里没有昼夜明暗交界**（HTML 的球面昼夜不可移植 ⇒ 本效果不做）

---

## 十一、验收标准

### 11.1 视觉验收 ✅

- [ ] **无频谱条**（D6）
- [ ] **粒子形态与运动逐位移植 HTML**（D7）；音频**只改亮度**（≤±15%），不改数量/形态/分布
- [ ] 星空 / 极光 / 大气辉光 / 流星构成完整的「星际四季」氛围
- [ ] 四季 tint 按 HTML `SEASON_PARAMS` 逐季可辨（春偏绿 `[0.94,1.14,0.94]` / 夏偏亮 `[1.06,1.05,0.90]` / 秋偏橙 `[1.22,0.88,0.58]` / 冬偏蓝 `[0.76,0.90,1.18]`），且**一首歌内看完四季**
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
- `docs/globe-present.html` —— **视觉与参数的唯一来源**（星空 / 极光 / 大气 / 粒子 / 流星 / 季节 / 季节光照）
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
