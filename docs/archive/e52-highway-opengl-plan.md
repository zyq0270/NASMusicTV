# E52「旷野公路」原生 OpenGL ES 路线 —— 架构与移植方案（v1.0）

> 📌 **文档公开，代码私有**：本文是开发分析与计划文档，已移至 `main` 分支公开。**E52 的 Kotlin 源码、测试、资产与 HTML 原型仍只在本地分支 `feat/e51-private`（⛔ 不 push、不 tag、不发 release）**。
> **状态**：v1.9 / 2026-10-30 / **Kotlin 侧地形+天空+雾+路面+植被全部落地（门禁全绿），待真机首帧对号** ⇒ **门禁口径定案（硬 A + 硬 B + 软 C）** + **n040 原型根因确认（远景格覆盖屏幕下部 80%，原型独有，Kotlin 侧无此缺陷）** + layer 诊断工具链补齐；**⭐ WebGL 原型 M1：渲染管线已跑通，但 CPU 核范围从「6 个纯函数」扩到「世界生成器」（侵蚀高度图 + PRNG 道路节点链）⇒ G18a 暂不可达**；同时更正 5 处上游取值（雾色 0xF0FFFF / near 700 / rockMap / rockMapBump / 天空与相机模型）（0.4 档纯糊 SSIM 0.9152）⇒ G18 拆成 G18a/G18b；方案 α 维持** + O8 裁决（方案 α：保逐行移植 + 降分辨率）**（填充率是硬墙：15 采样/像素 @1080p = 87.2 ms ⇒ `renderScale ≤ 0.5` 才 60 fps ⇒ **O8 待拍板**）；（创维 9R54_G8S = **PowerVR Rogue G6110 / GLES 3.1**，442,000 三角 @1080p 跑 **39.4 fps** ⇒ 几何不是瓶颈）；**D-GL-01 改判**（该机拿不到 ES2 上下文 ⇒ 上下文优先要 GLES 3.0，着色器仍用 ES 1.00 零改写）
> **取代**：`docs/archive/e52-slow-roads-canvas-plan.md`（v1.3，Canvas 2D 路线）的 **D1「载体 = 纯 Canvas 2D / ⛔ GLSL」**，
> 以及该文档的 §1.3 / §3.4 / §4.1 / §4.2 / §4.5。
> **继承**（该文档的事实层仍然有效）：§1.4 参数表、§3.2 数学核清单、§3.3 `tileReady` 契约、§4.6 音乐映射、
> §4.8 环境集与 **D19–D25** 裁决、§六 PRNG、§七 门禁体例、§八 **K1–K10** 任务编号（K9→P7，K10→P6）。
> **阅读顺序**：先读本文的 §二（为什么必须是 GPU），再读 Canvas 文档的事实层。

---

## 📋 E52 方案体系

| 文档 | 角色 |
|---|---|
| `docs/archive/e52-highway-opengl-plan.md`（本文） | ⭐ **主方案** — 当前路线：原生 OpenGL ES |
| `docs/archive/e52-progress.md` | 开发进度跟踪 |
| `docs/archive/e52-highway-webgl-preview.html` | WebGL 原型 M1（验证渲染管线用） |
| `docs/archive/e52-highway-preview.html` | Canvas 2D 原型（历史参考，本文 §4.6.3 仍引用其 cell 分配算法） |
| `docs/archive/e52-slow-roads-canvas-plan.md` | ⛔ 已作废：Canvas 2D 路线（事实层被本文继承） |

> ⚠️ 表中两个 `.html` 原型文件含上游源码（GLSL 原文 / Slow Roads CPU 核逐行照搬），⛔ **留在私有分支 `feat/e51-private`，不在 main 上**。

---

## 〇、为什么这条路的形态变了

### 0.1 Canvas 2D 路线为什么封死（不是"没做好"，是表达能力上限）

Canvas 2D 的 `fill()` 语义是「**一条路径 → 一个纯色**」：没有片元着色器、没有纹理采样、没有法线贴图、没有逐像素光照。

上游实测（`logs_temp/slowroads/shaders/`）：

| 量 | 上游 | Canvas 2D |
|---|---|---|
| 地形片元纹理采样 | **15 次 `texture2D` / 8 张贴图**（`fadeRockMap` 在 10m/80m/1200m 三尺度各采一次） | **0** |
| Planet 片元 | **16 次**、6 层贴图 + 6 张 bump，按 `vCurvature` 用 `terrainBlend()` 混合 | 0 |
| 光照 | three.js `meshphysical` 完整 PBR chunk 链（`RE_Direct` / `RE_IndirectDiffuse` / `RE_IndirectSpecular` + 法线贴图 + 阴影贴图） | 0 |
| 每帧纹理采样总量 @1080p | 约 **3,000 万次** | 0 |

⇒ 原版观感的**全部**来自 GPU 端。Canvas 2D 路线把 442,000 三角 + 每像素 15 次采样压成 3,292 三角 + 13 次 fill，
代价就是低多边形平涂。**这不是实现取舍，是物理上限。**

### 0.2 红线澄清（2026-10-29 所有者裁决）

红线原文「⛔ WebView / WebGL / GLSL / AGSL」指的是**浏览器那一套**：WebView 是宿主、WebGL 是浏览器里的 GL、
GLSL 是 WebGL 的着色语言、AGSL 是 API 33+ 的运行时着色器。**原生 OpenGL ES 不在红线内** ——
它把着色器**编译进 APK**，既不依赖浏览器，也不依赖运行时编译，恰好是那条红线的反面。

### 0.3 路线裁决

| 方案 | 状态 | 理由 |
|---|---|---|
| **A 离线预烘焙位图** | ❌ 已否决（所有者 2026-10-29） | 上游环境集 = **80 种视觉配置**（见 §一 3），逐套烘焙 = APK 不可行；且失去「频谱驱动几何」 |
| **B 放弃 E52** | 备选（仅在 §三 吞吐实测证伪时启用） | — |
| **C 原生 OpenGL ES** | ✅ **进行中** | 本文 |

---

## 一、现状核实（file:line，全部本轮实测）

### 1.1 上游渲染栈

| 事实 | 出处 | 结论 |
|---|---|---|
| three.js **r144** | `logs_temp/slowroads/2.f5fa8177.chunk.js:155` → `const r = "144"` | WebGL1 / WebGL2 双路径（`:7603` `t.isWebGL2`） |
| 上游 13 个自定义 shader **全部 GLSL ES 1.00** | `logs_temp/slowroads/shaders/*.glsl` 全扫：`#version` **0 条**、`#extension` **0 条**、`dFdx/dFdy/fwidth` **0 条**、`gl_FragDepth` **0 条**、整数/位运算 **0 条** | ⭐ **零 GLSL 改写即可编译** |
| three.js chunk 指令只有 4 条 | `D1064:1 <uv_pars_vertex>`、`D1068:1 <uv_vertex>`、`D1076:1 <uv_pars_fragment>`、`D1080:1 <aomap_fragment>` | 全部可手工展开（`<aomap_fragment>` 无 aoMap ⇒ 空） |
| 顶点着色器采样数 | `D3422` 内 **4 次 `texture2D`**（3× `fadeRockMap` + 1× `displacementMap`） | ⚠️ **顶点纹理取样** —— GLES2 的 `MAX_VERTEX_TEXTURE_IMAGE_UNITS` **规范最小值 = 0**，必须特性检测（见 §2.2 V-02） |
| 片元采样数 | `D3430` **15**、`D10791` **16**、`D1080` **5**、`D10783` **2** | 填充率是唯一瓶颈（§三） |
| 地形材质 | `main.e7a33c55.chunk.js:3344` `vi = new r.F({ map: F(ut,0), displacementMap: F(pi,4) })` ⇒ `MeshStandardMaterial`，**roughness 默认 1.0 / metalness 默认 0.0** | 纯介质、全粗糙 ⇒ PBR 化简有严格依据 |
| 三盏灯 | `main:1004-1007`：`Ambient(white,0.1)` / `Hemisphere(white,white,0.2)` / `Directional(white,0.6)`，`sunOffset=(0,100,0)` | Directional `position=(0,10,0)`（`main:18867`）、`target` 在原点 ⇒ **太阳正上方** |
| 雾 | ⚠️ **v1.5 更正**：`main:1019` 的 `new r.p(16777215, …)` 是**颜色被拆成两半**的读法；**实测上游真值 = `Fog(0xF0FFFF, 700, 1400)`**（`main:2924` / `:9992`）⇒ **雾色 `0xF0FFFF`（淡青白，不是纯白）**、**near = `pe·0.5 = 700`**、far = `pe = 1400`（`pe = farSize·floor(2.5)`） | three.js r144 的线性雾用 **`smoothstep(near,far,vFogDepth)`**，⛔ **不是** `mix(c,fog,(d-near)/(far-near))`；⇒ ⛔ **D21「雾色纯白 0xFFFFFF」是 Canvas 路线的自选值，GLES 路线必须用上游的 `0xF0FFFF`** |
| 相机 | `main:1084` `new r.H(ce=68, 1, 0.25, ue)` ⇒ `PerspectiveCamera(fov=68°, aspect=1, near=0.25, far=ue)`；`main:15496` `camera.aspect = e/t` | ⭐ **`68°` 是「垂直 FOV」**（three.js 约定），`aspect` 在 resize 时改成 W/H ⇒ 16:9 上**水平 FOV = 101.4°**。⚠️ **v1.5 补**：上游相机模型 = **`Mo[0] Chase`**（`posOffset (0,0,1.4)`、`yOffset 0.8`、**`range 3.5`**、`pitch 0.3`） |
| far 平面 | `main:1013-1018` `ue = pe / cos((68°/2 in rad) · aspect)`；`pe=1400` ⇒ 16:9 上 `ue ≈ 2834` | 雾 far=1400 ⇒ **1400 之外恒为纯雾色** |
| 色彩管线 | main chunk 对 `outputEncoding` / `toneMapping` / `sRGB` **0 命中**；`2.f5fa8177.chunk.js:14968` `toneMapping = 0`（NoToneMapping） | ⭐ 走 three.js r144 默认 `LinearEncoding` ⇒ **线性直通、无 tone map、无 sRGB 编码** |
| 阴影贴图 | `main:18859` `shadowMap.enabled = !0`；`:18868` `i.castShadow = !0`；`:18870-18879` 正交相机 **left/right/top/bottom = ±4**、near 0.5 / far 300、`mapSize = 512*(detailLodIndex+1)` | ⭐ **阴影贴图只覆盖车体周围 8×8 世界单位**，地形只有 `receiveShadow` 没有 `castShadow` ⇒ **阴影对地形观感贡献 ≈ 0** ⇒ **直接砍掉**（D-GL-08） |
| 植被 instancing | `main:7422-7474`：`buildLibrary()` 造 **两个交叉 quad**，`setIndex([0,1,2,0,2,3,4,5,6,4,6,7])` = **4 三角/实例**；`getGrassInstance(e)` **每 cell 一个 `InstancedMesh`**，per-instance 属性 `variant`、`shadow` + `instanceMatrix` | 我们的实现要合并成 **单个大 VBO + 一次 draw** |
| 贴图助手 | `main:807-810` `G = (e, t=1, i=r.N) => { s.wrapS=i; s.wrapT=i; s.anisotropy=t; }` | ⭐ **第 2 个参数是各向异性，不是 repeat**（`F(pi,4)` = aniso 4）；wrap 恒为 `r.N` = RepeatWrapping |
| 贴图来源 | `main:2773` `ut=grass_summer_01.jpg`；`:2826` `pi=rock_06_bump.jpg`；`:2827` `ui=crossfade_fine.jpg`；`:2828` `gi=crossfade_finest.jpg` | `rockMap` 与 `displacementMap` **是同一张图**，逐行照搬即可 |
| renderScale | `main:11893` `rr = [0.5, 0.75, 1, 1.5]`；`main:18997` `renderer.setSize(e*renderScale, t*renderScale)` | ⭐ 上游自带 4 档渲染缩放 ⇒ 我们的降级策略与上游同形 |
| LOD 预设 | `main:886-933`（H[0..4]），缺省 **H[2]**：`farSize 700 / viewDist 1400 / fwdHorizon 180 / rearHorizon 16 / nearFwdHorizon 30`；`main:940-945` `Q = [{medRes:5,nearRes:10},{medRes:8,nearRes:16},{medRes:10,nearRes:20},{medRes:12,nearRes:24}]`；缺省 `viewLodIndex=2` ⇒ **`medRes 10 / nearRes 20`**（`Q[2]`，不是 `Q[1]` 的 8/16）；`main:1024` `we=10`；`main:1010/15010` `le=700, res=de=70` | 继承 Canvas 文档 §1.4 参数表 |
| 水面 | `main:3460-3472`：`MeshStandardMaterial.onBeforeCompile` 注入，`WATER_WAVES` / `WATER_DEPTH` 宏，`skyCol`/`sunCol` uniform | 需要深度信息 ⇒ GL 路线可**在顶点着色器里用 CPU 高度场求水深**，不必上深度纹理 |

### 1.2 创维真机实测（2026-10-29，`logs_temp/slowroads/gl_probe.txt`）

```
model        Skyworth 9R54_G8S   (rtd299o_tv001_2GB)
android      5.1.1  (API 22)
cpu          ARMv7 rev4 × 4      abi: armeabi-v7a
ram          1,850,052 kB ≈ 1.76 GB
display      1920×1080 @ 60.000004 Hz, density 240
GLES         Imagination Technologies, PowerVR Rogue G6110, OpenGL ES 3.1
ro.opengles.version = 196609 = 0x00030001  ⇒  GLES 3.1
```

**扩展表关键项（全部命中）**：

| 扩展 | 用途 |
|---|---|
| `GL_OES_depth_texture` | 阴影贴图（深度纹理） |
| `GL_OES_standard_derivatives` | three.js PBR chunk 链需要 `dFdx/fwidth` |
| `GL_EXT_shader_texture_lod` | 显式 LOD 采样 |
| `GL_EXT_texture_filter_anisotropic` | 各向异性过滤 |
| `GL_OES_element_index_uint` | 32 位索引 |
| `GL_EXT_texture_rg` / `GL_EXT_color_buffer_float` / `GL_EXT_draw_buffers` | 高级格式 / MRT |
| `GL_IMG_program_binary` / `GL_IMG_shader_binary` | 着色器二进制缓存（启动加速） |
| `GL_KHR_debug` | 调试 |

> **关键推论**：GLES 3.x **向后兼容 ESSL 1.00** —— 不带 `#version` 指令的着色器在 ES3 上下文里按 ESSL 1.00 语义编译。
> ⇒ **上游那套 GLSL（游戏自研 13 个 + three.js 的 PBR chunk 链）可以原样编译，一行不改。**
> ⚠️ 但 GLES 2.0 仍是**兼容基线**（API 22 强制支持 2.0）⇒ 着色器按 **ES 1.00** 写，两种上下文都能跑。

#### 1.2.1 ⭐ 真机吞吐与限制实测（探针 APK，`app/src/debug/`，2026-10-29）

探针源码：`app/src/debug/java/com/nasmusic/tv/glprobe/`（**仅 debug 源集**，release 不含）。
结果文件：`logs_temp/slowroads/gl_probe_result.json`（`adb shell run-as com.nasmusic.tv cat files/gl_probe_result.json`）。

**GL 上下文**

| 项 | 实测 |
|---|---|
| `clientVersion=3` | `OpenGL ES 3.1 build 1.4@3632227` / PowerVR Rogue G6110 / GLSL ES 3.10 |
| `clientVersion=2` | **同样返回 ES 3.1**（驱动忽略 clientVersion）⇒ 拿不到真 ES2 上下文 |
| EGL | 1.4（`Android META-EGL`），`es3ConfigFound: true`，`es3ContextCreated: true` |
| `GL_MAX_TEXTURE_SIZE` | **8192** |
| `GL_MAX_VIEWPORT_DIMS` | 8192×8192 |
| `GL_MAX_TEXTURE_IMAGE_UNITS` | 16（我们需要约 9） |
| `GL_MAX_VERTEX_ATTRIBS` | 16（我们约 8） |
| `GL_MAX_VERTEX_UNIFORM_VECTORS` / `FRAGMENT_UNIFORM_VECTORS` | 256 / 224 |
| FBO 1920×1080 | `complete: true`（`status 36053`） |
| `GL_OES_mapbuffer` / `GL_OES_vertex_array_object` | ✅ ⇒ 环形 VBO + VAO 方案可行 |
| `GL_OES_element_index_uint` | ✅ ⇒ 32 位索引可用（`uintIndicesSupported: true`） |
| `GL_EXT_instanced_arrays` | ❌ **不在扩展表** ⇒ GLES2 的 instancing 不可用；**ES3 核心 instancing 可用**（`es3InstancedDrawGlError: 0`） |
| `GL_EXT_frag_depth` / `GL_OES_draw_elements_index_uint` | ❌ / ❌（但 `GL_OES_element_index_uint` ✅） |

**着色器编译实测**

| 测试 | 结果 | 含义 |
|---|---|---|
| `es100_source_in_es3`（ESSL 1.00 源码 @ ES3 上下文） | ✅ **编译成功** | ⭐ **上游 GLSL 原文零改写可用** |
| `es300_source_in_es3`（`#version 300 es`） | ✅ 编译成功 | ES3 变体也可行 |
| `es100_basic_in_es2` | ✅ 编译成功 | ES2 变体也可行 |
| `pbr_stress`（15 次 `texture2D` + PBR ALU） | ⚠️ 首次**失败**：探针 shader 误用了 ESSL 3.00 的浮点后缀 `1.0f` ⇒ 已修（`GlProbeShaders.kt`） | ⚠️ **移植约束**：ESSL 1.00 **不接受 `f` 后缀**；上游 three.js chunk 是给 WebGL1 写的，不会踩这个坑 |

**吞吐实测（1920×1080，单 draw call）**

| 基准 | wall fps | wall 帧时 | GPU 纯耗时 | 折算 |
|---|---|---|---|---|
| 1,000 三角 | 61.97 | 16.14 ms | 1.75 ms | 0.57 Mtri/s |
| 10,000 三角 | 61.51 | 16.26 ms | 1.89 ms | 5.28 Mtri/s |
| 100,000 三角 | 59.59 | 16.78 ms | 4.04 ms | 24.7 Mtri/s |
| **442,000 三角（上游全量）** | **39.40** | **25.38 ms** | **18.05 ms** | **24.5 Mtri/s** |
| **PBR 全屏（15 次 `texture2D`/像素）@1080p** | **11.47** | **87.21 ms** | 9.90 ms（⛔ `glFinish` 测的，不信） | **≈ 357 Mtex/s** |
| **instanced 5,000 实例（ES3 核心）** | **63.19** | 15.83 ms | 3.74 ms，`glError=0` | 1.34 M 实例/s |

⇒ **结论（v1.1）**：

1. ⭐ **几何完全不是瓶颈** —— 上游**全量 442,000 三角**在 1080p 下能跑 **39.4 fps**，
   而本方案的设计点是 **1–2 万三角**（雾 far 主动剔除后）⇒ **60 fps 余量巨大，且不需要为此降级**。
2. ⭐ **这台机拿不到 ES2 上下文 ⇒ instancing 只能走 ES3 核心路径**（`GL_EXT_instanced_arrays` 不在扩展表）
   ⇒ **D-GL-01 改判**（见 §2.1）：着色器仍用 **ES 1.00**（上游原文零改写，实测在 ES3 上下文可编译），
   但**上下文优先要 GLES 3.0**。
3. ⭐ **填充率是这台机的硬墙**（第二轮重测，shader 修好后）：
   **15 次采样/像素 @1080p 全屏 = 87.2 ms/帧 = 11.47 fps** ⇒ 纹理吞吐 **≈ 357 Mtex/s**。
   ⇒ 60 fps（16.7 ms）的采样预算 = **5.96 M 次/帧**。
   ⇒ 我们的地形在 1080p 下占 ~65% 像素 × 15 次 = **20.2 M 次/帧 ⇒ 超预算 3.4 倍**；
   `renderScale 0.75` ⇒ 12.4 M（超 2 倍）；**`renderScale 0.5` ⇒ 5.1 M（刚好 60 fps）**；
   `renderScale 0.4` ⇒ 3.2 M（71 fps）。
   ⚠️ 探针的压力 shader 是**故意最坏**的（15 次采样用不同纹理/不同缩放/不同偏移，杜绝纹理缓存合并）；
   真实上游 `D3430` 的 15 次里有不少是**动态分支**（砂/砾石/峭壁），只在部分像素发生
   ⇒ 实际平均值应低于 87 ms，但**不能按乐观值设计**。
4. ✅ **instancing 可用**（ES3 核心路径，5,000 实例 63 fps，`glError=0`）⇒ 草/树 billboard 无障碍。
5. ⚠️ `glFinish` 包夹的"GPU 纯耗时"在本机不可信（曾报 0.0006 ms）⇒ **以 wall 帧时为准**。

### 1.3 本仓架构

| 事实 | 出处 | 影响 |
|---|---|---|
| ⭐ **View 型渲染器旁路已经存在** | `visualizer/VisualizerRenderer.kt:15-19` KDoc + `:34-43`（`isViewBased` / `createView` / `onViewAttached` / `onViewDetached`） | ⇒ **不需要给 GL 主题开「特殊分支」**，也不改 `VisualizerRenderer` 的形状。E52 只是这条平行路径的**第二个使用者**（第一个是 `WorldGlobeRenderer.kt:62-73`） |
| 舞台已实现 View 旁路 | `ui/components/VisualizerStage.kt:335-352`（`if (active != null && active.isViewBased)` → `key(active){ AndroidView(factory = { active.createView(ctx)!! }); DisposableEffect { onViewAttached / onViewDetached } }`） | GL 主题挂 `GLSurfaceView` 走同一段代码 |
| ⛔ **View 旁路下 `RenderContext` 不逐帧更新** | `RenderContext.update(...)` 只在 Canvas 的 draw 块里调（`VisualizerStage.kt:388`）；`RenderContext.songId`（`visualizer/RenderContext.kt:25`）因此**在 View 路径下永远是旧的** | ⇒ **D24「每帧比 songId」在 View 路径下直接失效**，必须补通路（R-04 / R-06） |
| ⛔ **舞台有不透明背景** | `VisualizerStage.kt:267` `.background(Color.Black)` + `:327-331` 背景层 `Box(palette.background.copy(alpha=0.72f))` | ⇒ `GLSurfaceView`（SurfaceView，**在窗口后面打洞**）会被这两层不透明色**完全遮住** ⇒ 必须改（R-07） |
| ⛔ **`MainActivity` 没有 `onPause()`** | `ui/MainActivity.kt:617` 只有 `onResume()` | ⇒ `GLSurfaceView` 的 GL 线程**不会暂停** ⇒ 退到后台仍满速跑、耗电、EGL 上下文可能在后台丢失 ⇒ 必须补（R-08） |
| 音频源 | `visualizer/SpectrumRepository.kt:80-154`：双缓冲单例，写端 `SpectrumAnalyzer`（PCM 通道，`PcmSpectrumTap` HandlerThread，FFT 1024 / 40 ms），`:148` 翻转 `writeIndex`，`:150-153` 按 `EMIT_INTERVAL_MS` 节流后发布 `frameSeq` | GL 线程**不能直接持有 `AudioFrame` 引用**（`AudioFrame.kt:9` 契约）⇒ 必须拷进快照 |
| `VisualQuality` 三档 | `data/model/AppSettings.kt:540-552`：`HIGH(bar=64,glow=3,trail=T,particles=350,32×18,fb=T)` / `MEDIUM(64,2,T,150,24×14,F)` / `LOW(32,1,F,0,16×10,F)`；`:554-563` `supports()` | ⇒ **`HIGHWAY` 标 `needsParticleBudget=false` ⇒ 三档全可选**（`LOW` 的 `maxParticles=0` 也不挡） |
| 枚举 | `AppSettings.kt:442` `LOFI_CITY(..., Tier.ADV, "51", ...)` ⇒ 最大编号 `"51"`，共 **27 项** | E52 取 `"52"`（D-new-01） |
| 工厂 | `visualizer/VisualizerRendererFactory.kt:64-100` `createEnabled` 的穷举 `when` | 加一个分支（`:100` 之后），⛔ **必须在 `when` 里**（`:97-99` 的 E51 注释已明写这条纪律） |
| 门禁 | `test/.../data/model/VisualizerThemeStringsGateTest.kt`（`values/strings.xml` + `values-en/strings.xml` 双份 key）；`test/.../visualizer/fx/FxCoverageScanTest.kt:271` `decls.size >= 26` | 前者会红；后者**不会**（阈值 `>=`，加了也不红 ⇒ 对 E52 无约束力，需人工抬阈值） |

### 1.4 上游环境集（D23 照抄全集）= **80 种视觉配置**

| 场景 | 地形档 | skin × 天气 | 小计 |
|---|---|---|---|
| Hills | normal / hard / easy / **flat** = 4 | summer 5 + autumn 5 + winter 5 = 15 | **60** |
| Planet | normal / hard / easy / **flat** = 4（`flat` 在 `:10570-10580`：`heightScale 0 / heightOffset 50 / roadWidth 20`） | mars 2 + venus 2 + moon 1 = 5 | **20** |

出处：`main:2841-2889`（Hills topography）、`:3232/3259/3284`（Hills skins）、`:10518`（Planet topography）、
`:10698/10716/10732`（Planet skins）、`:3325`（`time: { noon: {} }`）、`:3234/10699/10717/10733`（weathers 列表）。

> ⛔ **自造（沙丘/沙漠）不做**（所有者 2026-10-29）；`Peaks`（`:10028`）/ `Scene`（`:2119`）未注册 ⇒ 不做。

### 1.5 ⭐⭐ WebGL 原型 M1 的结论（2026-10-29，`docs/archive/e52-highway-webgl-preview.html`）

> **这份结论推翻了本文 v1.4 之前的一句断言**：v1.4 写「CPU 核已移植完成、逐位对账通过」。
> **那句话夸大了** —— 门禁只覆盖 **6 个纯函数**，且跑在**合成的节点链 + 合成的 `preHeights` ctx** 上
> （harness `:318-458`），**不覆盖世界生成器**。原型把这个缺口暴露出来了。

**已跑通（可以放心进 P2）**：

| 项 | 证据 |
|---|---|
| **上游 GLSL 原文在 three.js r144 下直接跑通** | 注入点逐条对齐：`#include <displacementmap_pars_vertex>` / `<displacementmap_vertex>` / `<map_pars_fragment>` / `<map_fragment>`（地形）、`<uv_pars_vertex>` / `<uv_vertex>` / `<fog_vertex>` / `<uv_pars_fragment>` / `<aomap_fragment>`（天空）；`terrainBlend` 逐字照搬；`rockMapBump` 两分支符号相反原样保留；`vWWv` 三次覆写原样保留 |
| **ESSL 1.00 合规** | 产物里浮点后缀 **0** 处、`texture2D` 27 处、ES3 语法 0 处 |
| **色彩管线线性直通** | r144 默认 `outputEncoding=LinearEncoding` / `NoToneMapping` / `ColorManagement.legacyMode=true` ⇒ 无 tone map、无 sRGB |
| **上游材质/光照绑定逐条还原** | `grassMap=grass_summer_01`(aniso 2)、`rockMap=rock_06.jpg`、`rockMapBump=null`(黑纹理)、`displacementMap=rock_06_bump`、`fadeRockMap=crossfade_fine`、`fadeFiner=crossfade_finest`、`colouration=[52,20,36,0]/255`；clear 天气 `amb 0.2 / hem 0.2 / dir 0.8 / hemTop 0xD0DDDD / hemGround 0x606666 / col 0xF0FFFF / skyCol 0x68A9F8 / skyColFar 0xCBFDEE`；雾 = `Fog(0xF0FFFF, 700, 1400)` |
| **可复现性** | 同 URL 连跑两次 ⇒ 4/4 PNG **逐位相同**（SHA-256 一致） |

**未达标：G18a 的"移植误差"**。M1 ↔ 黄金帧：SSIM 均值 **0.6761**、MAE **35.14**；分辨率基线均值 SSIM **0.8953** ⇒
**移植误差 = −0.2192** ⇒ FAIL。

**根因已定位到「世界」而不是「渲染管线」**：

| | 我们的（替身） | 上游（真值） |
|---|---|---|
| **地形高度场** | 4 八度值噪声 `fbm`（原型自己注释就写了"高度场本体简化"） | **侵蚀高度图** `heightmap.getXZ`（`main:2184-2770`，resolution 5 / depth 3 / upscale 2，normal 地形 heightScale 160 / heightOffset 84） |
| **道路中线** | 有界航向正弦叠加 | **PRNG 驱动 + 反馈**（gradLat / gradLon / antiWeight / feelL / R / A / D）的节点链（`main:13440-13550`） |

⇒ 我们的「node N」与上游的「node N」**在世界里不是同一处** ⇒ 相机位姿不可能对齐 ⇒ 黄金帧**构造上**不可复现。

#### ⭐ 由此产生的**范围变更**（v1.5）

> **CPU 核的移植范围从「6 个纯函数」扩大到「世界生成器」**：
> 必须再移植 **① 上游侵蚀高度图**（`main:2184-2770`）与 **② PRNG 道路节点链生成器**（`main:13440-13550`）。
> ⇒ 这两项**直接决定 G18a 能不能达标**，且**在着色器层面再怎么调都补不回来**。
> ⇒ 对应的门禁也要扩：harness 的 6 函数对账**不足以证明世界一致**，必须新增
> **「同一 seed + 同一 node ⇒ 高度场与道路几何逐位相等」** 的对账（高度场抽样 + 节点坐标 + 切线）。
> ⇒ ⚠️ **这会改变工作量估算**（见 §六 P1/P2 的依赖）：**P1 的前置从「移植 6 个函数」变成「移植世界生成器」**。

#### M1 未做的部分（即 M2）

路面 ribbon（972 三角 + 标线 + 护栏）、instanced 草/树（2 万）、`treeMask` 林带混合、海面、墙、车辆模型、阴影。

---

## 二、架构决策

### 2.1 ⭐ D-GL-01：GLES 版本目标 = **GLES 3.0 优先**（`#version 300 es`），**GLES 2.0 保留为 fallback**

> ⚠️ **v1.1 修正（2026-10-29 真机实测推翻了 v1.0 的「GLES 2.0 基线 + ES 1.00 着色器」判定）**：
>
> - 创维 9R54_G8S 上 **`setEGLContextClientVersion(2)` 也返回 `OpenGL ES 3.1`**（PowerVR 驱动给最高版本，忽略 clientVersion）
>   ⇒ **这台机拿不到真 ES2 上下文**；
> - `GL_EXT_instanced_arrays` **不在 51 个扩展里** ⇒ GLES2 的 instancing 路径不可用；
>   但 **ES3 的 instancing 是核心特性**（探针 `es3InstancedDrawGlError = 0` ⇒ 正常）
>   ⇒ 草/树 billboard 的 instancing **只能走 ES3 核心路径**；
> - 实测 **ESSL 1.00 源码在 ES3 上下文里编译成功**（`es100_source_in_es3` ✅）
>   ⇒ **上游 GLSL 原文仍可直接在 ES3 上下文跑**（这是"零改写"的兜底）；
>   但若要用 `#version 300 es`，就须把 `texture2D`→`texture`、`varying`→`in|out`、`gl_FragColor`→`out vec4` 改掉。
>
> ⇒ **两套 shader 变体都要准备**：**ES3 变体（`#version 300 es`，主路径）** + **ES2 变体（`#version 100`，fallback 给只有 GLES2 的老设备）**。

**决策**：**默认走 GLES 3.0 上下文 + `#version 300 es` 着色器**；运行时读 `GL_VERSION`，
< 3.0 则退回 GLES 2.0 上下文 + `#version 100` 变体（该路径下草/树 instancing 降级为 CPU 展开，见 V-03）。

| 维度 | GLES 2.0 | GLES 3.0 | 裁决（**按实测改**） |
|---|---|---|---|
| 创维机实际可得上下文 | ❌ 给不了（驱动返回 3.1） | ✅ **3.1** | **3.0 胜**（实测） |
| instancing | ❌ `GL_EXT_instanced_arrays` 不在扩展表 | ✅ **核心特性**（实测 `glError=0`） | **3.0 胜（决定性）** |
| GLSL 改写量 | 0 行 | 需 `#version 300 es` + 4 处机械替换 | 3.0 多花 4 处；**换来 instancing + 深度纹理** |
| `dFdx`/`fwidth` | 需 `OES_standard_derivatives`（实测**有**） | 内建 | 平手（上游 **0 处使用**） |
| 深度纹理 / MRT / `gl_FragDepth` | 无 | 有 | 平手：本方案**砍掉阴影贴图** ⇒ 深度纹理无用；单目标 ⇒ 无需 MRT |
| 顶点纹理单元 | 规范最小值 0 ⚠️ | 最小 16 | 仍需特性检测 + 降级变体（V-02） |
| NPOT + mipmap | 不支持（必须 POT） | 支持 | 仍统一 **POT（256/512）** ⇒ 与 D22「纹理 512²」一致 ⇒ **零额外代价** |

> ⚠️ **代价**：ES3 变体意味着上游 GLSL 要做 4 处**机械**替换（`texture2D`/`texture`、`varying`/`in|out`、`gl_FragColor`/`out vec4`），
> 这就**不是"一行不改"了**。⇒ 两条路可选：
> **(a) ES3 上下文 + ES 1.00 源码**（实测可行，零改写）—— **推荐**；
> **(b) ES3 上下文 + `#version 300 es`**（多 4 处机械替换，换取 ESSL 3.00 的现代特性）。
> ⇒ **默认选 (a)**：既然 ES3 上下文**接受** ES 1.00 源码，那就不必升级方言；
> instancing 的核心可用性取决于**上下文版本**，与着色器方言无关 ⇒ **(a) 一样能用 ES3 核心 instancing**。
> ⇒ **D-GL-01 定稿：着色器一律 ES 1.00（上游原文，零改写）；上下文优先要 GLES 3.0（为了核心 instancing），
> 拿不到就退 GLES 2.0 + `ANGLE_instanced_arrays`（该扩展在本机缺失 ⇒ 只能 CPU 展开）。**

**GLES2 下 three.js PBR chunk 链需要什么改动？** —— 几乎不用改，因为我们**裁剪**而非**移植** chunk 链。
从 `2.f5fa8177.chunk.js` **逐字摘取**，只删我们永远走不到的分支：

| 摘取 | 删除 |
|---|---|
| `common` 的 `saturate` / `pow2` / `PI` / `RECIPROCAL_PI` / `EPSILON` | — |
| `bsdfs` 的 `BRDF_Lambert` / `BRDF_GGX` / `D_GGX` / `V_GGX_SmithCorrelated` / `F_Schlick` | `BRDF_Sheen` / `BRDF_Clearcoat` |
| `lights_physical_pars_fragment` 的 `PhysicalMaterial` / `PhysicalLightingModel` | `clearcoat` / `iridescence` / `transmission` / `specularColor` 的 IBL 部分 |
| `lights_pars_begin` 的 `IncidentLight` / `GeometricContext` / `ReflectedLight` / `getAmbientLightIrradiance` / `getHemisphereLightIrradiance` / `getDirectionalLightInfo` / `RE_Direct` | `PointLight` / `SpotLight` / `RectAreaLight` / `LightProbe` |
| `lights_fragment_begin` 的 `NUM_DIR_LIGHTS` / `NUM_HEMI_LIGHTS` / `RE_IndirectDiffuse` | `NUM_POINT_LIGHTS` / `NUM_SPOT_LIGHTS` / `NUM_RECT_AREA_LIGHTS` / `USE_SHADOWMAP` / `RE_IndirectSpecular` |

⇒ 得到的最终光照块**约 45 行 GLSL**，全部 ES 1.00 语法、零扩展、零整数运算 ⇒ **在 GLES 2.0 上直接编译**。
⛔ **不要自己重推 BRDF**（那是"重新发明"）；⛔ **不要用 Lambert 近似糊弄**（那是"看起来像"而不是"照搬"）。

**「three.js 符号 → 我们的符号」替换表**（所有 shader 通用）：

| three.js | 我们的 |
|---|---|
| `attribute vec3 position` / `normal` / `vec2 uv` | `aPos` / `aNormal` / `aUv` |
| `varying vec3 normal`（three.js 内建） | `varying vec3 vNormal;`（自己写） |
| `modelMatrix` | **删掉**（§2.6 绝对坐标） |
| `objectNormal` | `aNormal` |
| `transformed` | `vec3 transformed = aPos;` |
| `cameraPosition` / `camPos` | `uniform vec3 uCamPos;` |
| `projectionMatrix * modelViewMatrix` | `uniform mat4 uViewProj;` |
| `vViewPosition` | `varying vec3 vViewPos;`（自己算 = `-(uView * vec4(aPos,1)).xyz`） |
| `vFogDepth` | `varying float vFogDepth;`（自己算 = `-(uView * vec4(aPos,1)).z`） |
| `diffuseColor` | `vec4 diffuseColor = vec4(1.0);` |
| `#include <uv_pars_vertex>` / `<uv_vertex>` / `<uv_pars_fragment>` | `attribute vec2 uv; varying vec2 vUv;` / `vUv = uv;` / `varying vec2 vUv;` |
| `#include <aomap_fragment>` | **空** |
| `#include <lights_fragment_begin>` 等 6 条 | §2.1 的 45 行裁剪块 |
| `#include <fog_fragment>` | `gl_FragColor.rgb = mix(gl_FragColor.rgb, uFogColor, smoothstep(uFogNear, uFogFar, vFogDepth));` ⛔ **`smoothstep` 不是线性** |

### 2.2 ⭐ D-GL-02：渲染器骨架 = **复用已有的 View 型旁路**，不开特殊分支

| # | 位置 | 改动 | 理由 |
|---|---|---|---|
| **R-01** | `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt:442` 之后 | 加 `HIGHWAY(R.string.visualizer_theme_highway, "旷野公路", Tier.ADV, "52", needsParticleBudget = false)` | 编号 `"52"`（D-new-01）；`needsParticleBudget=false` ⇒ `supports()` 恒真 ⇒ 三档全可选 |
| **R-02** | `app/src/main/res/values/strings.xml` + `values-en/strings.xml` | 加 `visualizer_theme_highway` = 「旷野公路」/ "Highway" | `VisualizerThemeStringsGateTest` 守 |
| **R-03** | `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt:100` 之后 | 加 `VisualizerTheme.HIGHWAY -> HighwayRenderer(context.applicationContext)` | ⛔ **必须加在 `when` 里** |
| **R-04** | `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRenderer.kt:38` 附近 | 新增 `fun onFrame(frame: AudioFrame, ctx: RenderContext) {}`（默认空实现） | ⭐ **View 路径缺 `songId` 通路**。Canvas 路径的 27 个渲染器**一行都不用改** |
| **R-05** | `app/src/main/java/com/nasmusic/tv/ui/components/VisualizerStage.kt:231-249`（帧循环） | 在 `withFrameNanos` 块里加 `swapper.current?.takeIf { it.isViewBased }?.onFrame(frame(), renderCtx)` | ⚠️ **必须复用同一帧的 `frame()`**，不要第二次调用 |
| **R-06** | 同上，`VisualizerStage.kt:341-352`（`key(active)` 块内） | 加 `LaunchedEffect(song?.id) { renderCtx.songId = song?.id }` | ⭐ 补 View 路径的 `songId`。`RenderContext.songId`（`RenderContext.kt:25`）是 public var |
| **R-07** | `VisualizerStage.kt:267`（`.background(Color.Black)`）+ `:327-331`（背景层 Box） | 两处背景改为**条件化**：`active?.isViewBased == true` 时**不画不透明底** | ⭐⭐ **挂 SurfaceView 的硬前提**：SurfaceView 的 surface 在窗口**后面**，Compose 在这块区域画不透明色就把洞填死、GL 画面全黑 |
| **R-08** | `app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt` | 新增 `onPause()`；`onResume()`（`:617`）同样转发 | ⛔ `GLSurfaceView` 不会自动跟随 Activity 生命周期 |
| **R-09** | 新文件 `visualizer/highway/HighwayGlHolder.kt` | `object` 持有当前活动的 `GLSurfaceView?`，`onViewAttached` 登记 / `onViewDetached` 注销 | 避免 `MainActivity` 反向依赖 Compose 内部状态 |

**`HighwayRenderer` 骨架**：

```kotlin
internal class HighwayRenderer(private val appContext: Context) : VisualizerRenderer {
    override val theme = VisualizerTheme.HIGHWAY
    override val isViewBased: Boolean get() = true
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {}   // View 路径不调用

    private val bridge = HighwayBridge()          // 主线程 → GL 线程的单向数据通道
    private var surface: GLSurfaceView? = null

    override fun createView(context: Context): View =
        HighwayGLSurfaceView(context, bridge).also {
            surface = it
            HighwayGlHolder.register(it)
        }

    override fun onViewAttached() { surface?.onResume() }
    override fun onViewDetached() {
        surface?.onPause()
        HighwayGlHolder.unregister(surface)
        surface = null
    }
    override fun onFrame(frame: AudioFrame, ctx: RenderContext) {
        bridge.publishSongId(ctx.songId)   // D24：GL 线程每帧比
        bridge.publishAudio(frame)
    }
    override fun onExit() { bridge.close() }
}
```

**⚠️ `GLSurfaceView` 在 Compose `AndroidView` 里的两个已知坑**：

1. **`AndroidView` 的 `factory` 在组合期调用**（主线程），`GLSurfaceView` 的 EGL 上下文在 `onAttachedToWindow` 后才创建
   ⇒ `onSurfaceCreated` **晚于**第一次组合。⇒ renderer 必须容忍「GL 还没准备好」。
   ⇒ **bridge 必须是纯数据通道，不能持有 GL 句柄。**
2. **SurfaceView 打洞 vs Compose 绘制顺序**（R-07 已处理）。

**备选 S2**（若真机上 SurfaceView 打洞不可靠）：`TextureView` + 手写 EGL（`EGL14` + `SurfaceTexture`）。
代价：每帧多一次 GPU→GPU 拷贝；收益：合成顺序与 WebView 一致（Compose 内容在其上方 ⇒ 歌词/歌名一定可见）。
**决策：先做 S1，S1 在创维机上验不过再上 S2。**

### 2.3 ⭐ D-GL-03：与播放器的同步（跨线程）

```
PcmSpectrumTap(HandlerThread, 40ms) ──► SpectrumAnalyzer ──► SpectrumRepository.onFrame()
                                        （写端，唯一写者）      双缓冲单例 + frameSeq
                                              │
                    ┌─────────────────────────┘
                    ▼
   Compose 帧循环 (主线程, ~60Hz) ──► HighwayBridge.publishAudio()   ←── 只读拷贝
                    │
                    ▼
   GL 线程 (GLSurfaceView.Renderer) ──► bridge.consume() ──► uniform
```

**为什么必须拷贝而不是直接读 `AudioFrame`**：`AudioFrame.kt:9` 的契约明写「渲染层不得持有引用跨帧使用」；
`SpectrumRepository.kt:148` 会在写端翻转 `writeIndex` ⇒ GL 线程直接读有**撕裂读**风险。
拷贝成本 ≈ **288 B/帧**（8 标量 + 64 float），可忽略。

**频率**：音频用 `frameSeq`（`SpectrumRepository.kt:150-153`）判断是否需要真正拷贝（省 60→25 Hz 的无效拷贝）；
**songId 每帧发布**（一个 volatile 写，零成本）⇒ GL 线程**每帧比**（D24 字面要求）。

**⛔ 不用 `PlayerManager` 的 1000 ms 轮询循环** —— 那是 `PlaybackService` 用来推播放进度的，
频谱数据走的是完全不同的通路（PCM tap → `SpectrumAnalyzer` → `SpectrumRepository`，40 ms 一帧）。
（`WorldGlobeRenderer.kt:380-384` 的 `spectrumFrame()` 就是正确用法。）

**换歌判据**：⚠️ 有些歌没有 id（本地扫描）⇒ `songId` 可能为 `null` 或不变 ⇒ D24 对无 id 的歌失效。
⇒ **对策（D-new-02）**：同时发布 `song?.id` 与 `song?.title`（`RenderContext.kt:27` 有 `caption`），
用 **`id ?: title`** 作为切换判据。

### 2.4 ⭐ D-GL-04：换歌换环境（D24）在 GL 线程上的实现

**⭐ 关键架构发现（大幅降低 D24 的实现成本）**：

| 切换维度 | 换的是什么 | 需要重建几何吗？ | 证据 |
|---|---|---|---|
| **skin**（夏/秋/冬） | 贴图绑定 + `colouration` + `treeColouration` | ❌ **不需** | `main:3393-3403` 的 `userData.*` 全是 **uniform 引用** |
| **weather** | 天空色 `nearCol/farCol` + 雾色 + 云贴图 | ❌ **不需** | `main:2897-2910` + `D1076` 的 uniform |
| **topography** | `heightScale` / `roadWidth` / `smoothWindow` | ❌ **不需** | 三个**标量**；把 `heightScale` 放到**顶点着色器** ⇒ 切换 = 改一个 uniform |
| **scene**（Hills ↔ Planet） | 高度场生成器 + 第二套着色链 + 植被清零 | ✅ **需要** | `main:10518-10580` 陨石坑 heightmap；`main:10785-10791` 第二套 `terrainBlend` 链 |

⇒ ⭐ **只有 1/6 的切换（scene）需要重建**。

**三阶段雾白过场（对齐 D24 ①③④ 与 Canvas 文档 §4.8.3）**：

```
阶段 0（常态）      : 正常渲染
阶段 1（淡出 0.50s）: 叠加全屏白色 quad，alpha 0 → 1
                     ⛔ alpha > 0.995 时【跳过整个场景渲染】，只 glClear(白) ⇒ 重建期间零成本
阶段 2（重建）      : 在白色帧里执行环境切换
                     · 仅 uniform 切换 → 立即完成（< 1 ms）
                     · scene 切换 → 重建高度场 + 重新上传 VBO
阶段 3（淡入 0.50s）: 白色 quad alpha 1 → 0
```

**实现要点**：

1. **白色 quad**：覆盖全屏的 triangle strip，`glDisable(GL_DEPTH_TEST)`，
   `glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)`，颜色 = `uFogColor`（`0xFFFFFF`，D21）。**1 个 draw call**。
2. **过场计时**：用 GL 线程自己的 `System.nanoTime()`（⛔ 不要用主线程的 `frame.timeMs` —— 那是音频时钟，暂停时会停）
   ⇒ 固定步长累加器（上游 `requestAnimationFrame` 是变步长 ⇒ 我们用 **60 Hz 固定累加器**）。
3. **pending 语义**（D24 ④）：过场进行中 `songId` 再变 ⇒ **只记 pending**。
4. **目标 = 游标前进**（D24 ②）：`envCursor` 沿 `Hills×{summer,autumn,winter} → Planet×{mars,venus,moon}` **前进一格**（6 格一循环）；
   地形档与天气档各按固定步长前进（地形 8 档 ⇒ 24 次一轮；天气 10 档 ⇒ 10 次一轮）。⛔ **不是 `hash(songId)`**。
5. **不按时间切**：`intervalSec = ∞`（对齐 E51 `LofiCityTimeline.kt:102/188`）。
6. **过场期间不许建纹理**：所有 6 skin × 10 天气需要的贴图**启动时全部解码并上传**（见 O6 的裁决）。

**scene 切换（Planet）的重建耗时**：陨石坑 heightmap 与 Hills 分形 heightmap 是两套生成器 ⇒ 可见 cell 全部重算。
我们的 LOD 单元数 ≈ 138(L1) + ~500(L2) ⇒ 每 cell 重算 9×9 = 81 个格点 ⇒ **≈ 52,000 个格点 ×（噪声 8 八度 + 16 角双线性）**
⇒ 按 Canvas 文档 §3.1 量级估 **50–150 ms** ⇒ 过场总时长 ≈ **1.15 s**，且重建期间画面是纯白 ⇒ **用户看不到卡顿**。
⇒ **保持单线程，不引入线程池**（白色帧期间 GL 线程卡 150 ms 无视觉影响，YAGNI）。

**⭐ "自动开始自动驾驶演示"**：上游 `Hills` 场景本身就是自动驾驶 ⇒ 我们的实现**永远走自动驾驶路径**：
相机沿道路中心线前进，速度 = `0.6 + 0.7·energy`（保底 0.35×）。
⛔ 不做车辆物理 / 碰撞 / 圈速；⛔ **交通车 = 0 辆**（D20）。

### 2.5 ⭐ D-GL-05：色彩管线 —— **线性直通，禁止"顺手改对"**

| 项 | 上游 | 我们必须 | ⛔ 常见错误 |
|---|---|---|---|
| 色调映射 | `NoToneMapping` | 无 | 加 ACES/Reinhard ⇒ 整体色调偏移 |
| 输出编码 | `LinearEncoding` | **线性直通** | 用 sRGB framebuffer / `GL_FRAMEBUFFER_SRGB` ⇒ 变亮变灰 |
| 色彩空间 | 全程线性 | 全程线性 | 在片元里做 `pow(c, 1/2.2)` ⇒ 与上游不符 |
| `diffuseColor` 起点 | `vec4(1.0)` | `vec4(1.0)` | 用贴图色当 albedo 又乘一次 ⇒ 双重变暗 |
| BRDF 里的 `1/π` | three.js `BRDF_Lambert = RECIPROCAL_PI * diffuseColor` ⇒ **有 π** | 有 | 漏掉 π ⇒ 画面亮 3.14× |
| 雾 | `mix(color, fogColor, smoothstep(near, far, vFogDepth))` | 同 | 用线性 mix ⇒ 雾的过渡曲线不同 |

**⇒ 最终约束**：

1. **EGL config 不要 sRGB**：`setEGLConfigChooser(8,8,8,0,24,0)`（**无 alpha**，GL 主题要盖住背景层；**要 24 位深度**）。
2. ⛔ 绝不 `glEnable(GL_FRAMEBUFFER_SRGB)`。
3. 片元输出 `gl_FragColor = vec4(outgoingLight, 1.0)`，**不做任何编码**。
4. **雾色 = `0xFFFFFF`**（D21），与 `renderer.setClearColor(16777215, 1)`（`main:18841/18858`）一致。

⇒ 建议加门禁 **G18**：与上游参考帧逐像素对账，判据 **ΔE ≤ 3**，且**必须包含一片被雾压白的区域**。

### 2.6 ⭐ D-GL-06：世界坐标与精度 —— **绝对坐标，不做浮动原点**

**上游**：`main:4435-4441` 每 100 个节点重置浮点原点 ⇒ 顶点坐标是局部的，需要 `modelMatrix` 平移。

**我们的决策**：用**绝对世界坐标**，`modelMatrix` 一律删掉。

| | 上游（局部 + 浮动原点） | 我们（绝对坐标） |
|---|---|---|
| 顶点着色器 | 需要 `uModel`（每 tile 一个）⇒ **一个 tile 一个 draw call** ⇒ draw call 爆炸 | 无 `uModel` ⇒ **全部 cell 可合并成一个 VBO、一次 draw** |
| 精度 | 恒定 | float32 在 \|x\|=20,000 处 ε≈0.002；上游路基融合最小量级 `i.y -= 0.015 + 0.025 * i.gfa`（**`main:1508`**；`:1507` 是它的上一行 `i.w += Se + i.ga`，`Se` = `se`（`:1000` `se = 7` / `:1002` `se = e`））⇒ **0.002 < 0.015，可用** |
| 极限 | — | \|x\|=100,000 时 ε≈0.008；200,000 时 ε≈0.016（开始劣化） |

⇒ 代价：世界坐标绝对值到 ~50,000 单位前不需要处理；按 30 单位/秒算是 **28 分钟**。
⇒ **对策（D-new-03）**：超过 `REBASE_THRESHOLD = 50,000` 时做**一次性 rebase**（把世界原点挪到最近的 1,000 倍数，重建 VBO）。
触发频率 ≈ 每 28 分钟一次，耗时 = §2.4 的重建量，**在雾白帧里做 ⇒ 不可见** ⇒ **复用 §2.4 阶段 2 的重建路径，零新增机制**。

**这是与上游的第一处有意偏离**，必须写进方案。

### 2.7 ⭐ D-GL-07：投影矩阵 —— **`68°` 是垂直 FOV**

```
fovy = 68° * π/180                    // 垂直 FOV（照上游 three.js 约定）
t = tan(fovy / 2)
near = 0.25,  far = ue
ue = pe / cos(34° * aspect)            // pe = farSize * floor(2.5) = 1400（main:1013-1018）
                                        // ⚠️ 上游 `ue` 的 aspect 取 **window.screen.availWidth/availHeight**（屏幕比）
                                        //    而 `camera.aspect`（main:15496）是**渲染画布**宽高比 —— 两者比例一致，但不是同一个变量

proj = [ 1/(aspect*t), 0,          0,                    0,
              0,      1/t,        0,                    0,
              0,      0,   (near+far)/(near-far),  -1,
              0,      0,   2*near*far/(near-far),   0 ]
```

⚠️ **三个坑**：

1. **`68°` 是垂直 FOV**（`main:1084`）。16:9 上**水平 FOV = 101.4°**。⛔ 当水平用 ⇒ 画面横向被放大 1.78×。
2. **`aspect` 必须是 W/H**（`main:15496`），⛔ 不是 1。
3. **竖屏（O5）**：aspect ≈ 0.45 ⇒ 水平 FOV = 2·atan(tan(34°)·0.45) ≈ **34.7°** ⇒ 画面横向极窄。
   - **O5-a（建议）**：**保持水平 FOV = 68°**，垂直 FOV = 2·atan(tan(34°)/aspect)。竖屏 ⇒ 垂直 FOV ≈ 111°，构图与横屏一致。
   - **O5-b（照上游字面）**：保持垂直 FOV = 68° ⇒ 竖屏水平只有 34.7°，路面占满屏宽。
   - **O5-c（折中）**：垂直 FOV 夹在 `[40°, 95°]`。

---

## 三、移植清单

### 3.1 着色器程序表（7 个 program）

| Program | 上游来源 | 顶点 | 片元 | 采样数 | 备注 |
|---|---|---|---|---|---|
| `terrain` | `D3418`+`D3422`（vert）、`D3426`+`D3430`（frag） | 104 + 31 行 decl | 111 + 40 行 decl | **v:4 / f:15** | Hills 地形 |
| `terrainPlanet` | `D10779`+`D10783`+`D10787`（vert/pars）、`D10791`（frag） | — | 86 行 | **f:16** | Planet 地形（D25 / P7） |
| `sky` | `D1064`+`D1068`（vert）、`D1072`+`D1076`（pars）、`D1080`（frag） | — | 35 行 | **f:5** | 天空 + 云 |
| `road` | `MeshStandardMaterial`（`main:877`）+ `onBeforeCompile` | 通用 | 通用 | f:1–2 | 路面 ribbon |
| `veg` | `main:805` 的 `T` 块（`#define GRASS/BUSH/TREE`） | 通用 | 通用 | f:1–2 | 植被 billboard（instanced） |
| `model` | `MeshStandardMaterial` + `.obj` | 通用 | 通用 | f:1–2 | 车 / 护栏 / 标志 |
| `water` | `main:3460-3472`（`WATER_WAVES` / `WATER_DEPTH`） | 通用 | 通用 | f:2 | 水面 |

「通用」= 共用一个模板：`aPos/aNormal/aUv` + `uViewProj/uCamPos/uSunDir/uFogNear/uFogFar/uFogColor` + 45 行 PBR 块 + 雾块。

### 3.2 ⭐ GLSL 逐行改写清单（GLES 2.0）

#### V-01 `terrain` 顶点着色器（源：`D3418` 31 行 + `D3422` 104 行）

| 上游行 | 原文 | 改成 | 说明 |
|---|---|---|---|
| `D3418:3-7` | `attribute float heightOffset; roadProximity; treeMask; curvature; grass;` | `aHeightOffset` / `aRoadProx` / `aTreeMask` / `aCurvature` / `aGrass` | 加 `a` 前缀 |
| `D3418:21` | `float wuvSize = 800.0;` | 保留（`const float`） | |
| `D3418:23-24` | `uniform sampler2D displacementMap; uniform sampler2D fadeRockMap;` | `uDisplacementMap` / `uFadeRockMap` | |
| `D3418:26-28` | `float map(float value, …)` | 保留或改名 `remap` | D3422 里实际未调用（`:28-32` 全被注释）⇒ 可整段删，但保留更"逐行" |
| `D3418:30` | `vec3 upvec = vec3(0.0,1.0,0.0);` | 保留 | |
| `D3422:3` | `vec4 wPos = modelMatrix * vec4( position, 1.0 );` | `vec4 wPos = vec4( aPos, 1.0 );` | ⭐ **删掉 `modelMatrix`** |
| `D3422:5` | `height = wPos.y + heightOffset;` | `height = wPos.y + aHeightOffset;` | |
| `D3422:16-17` | `vUv.x = wPos.x / 10.0; vUv.y = wPos.z / 10.0;` | **原样** | |
| `D3422:21` | `steepness = 1.0 - dot(normal, upvec);` | `steepness = 1.0 - dot(normalize(vNormal), upvec);` | ⚠️ three.js 的 `normal` varying 是已归一化的插值法线 ⇒ 必须 `normalize` |
| `D3422:22` | `steepness = clamp(steepness * 2.0, 0.0, 1.0);` | **原样** | |
| `D3422:34-38` | `vWv`/`vWWv` 的三次缩放 | **原样** | ⚠️ `vWWv` 在 `:37-38`、`:53-54`、`:97-98` **被覆写三次** ⇒ 逐行照搬这个"覆写"语义 |
| `D3422:41-45` | `vCurvature` 三分支 | `vCurvature = (curvature > 0.0) ? max(-1.0, aCurvature * -4.0) : min(1.0, aCurvature * -4.0);` | 合并成三元（等价） |
| `D3422:49-56` | 3 次 `texture2D(fadeRockMap, …)` | 换 uniform 名，**采样坐标逐字照搬** | ⚠️ **顶点纹理取样** ⇒ V-02 |
| `D3422:60` | `vHeather = smoothstep(0.47, 0.53, min(1.0,height/170.0) * fade1 * fade2);` | **原样** | |
| `D3422:66-70` | `fade0/fade1` 重映射 + `vLightGrass` | **原样** | |
| `D3422:71-74` | `roadProximity` 调制 | `if (aRoadProx > 0.0 && aRoadProx < 2.0) { … }` | |
| `D3422:75` | `vDarkGrass = 1.0;` | **原样** | |
| `D3422:80` | `roadProx = roadProximity;` | `roadProx = aRoadProx;` | |
| `D3422:84` | `vTreeMask = treeMask;` | `vTreeMask = aTreeMask;` | |
| `D3422:95` | `vGrass = grass;` | `vGrass = aGrass;` | |
| `D3422:100` | `transformed += vec3(0.0, heightOffset, 0.0);` | `aPos.y += aHeightOffset;` | ⚠️ 上游在**求完 `height` 之后**才加 `heightOffset`（`height` 用未偏移的 `wPos.y`）⇒ **顺序必须一致** |
| `D3422:103` | `transformed += normalize( objectNormal ) * ( texture2D( displacementMap, vUv ).x * steepness * 1.25 );` | `aPos += normalize( aNormal ) * ( texture2D( uDisplacementMap, vUv ).x * steepness * 1.25 );` | ⚠️ 上游在对象空间做位移；我们无 model matrix ⇒ 世界空间等价 |
| **新增** | — | `vNormal = aNormal;` `vViewPos = -( uView * vec4(aPos,1.0) ).xyz;` `vFogDepth = -( uView * vec4(aPos,1.0) ).z;` `gl_Position = uViewProj * vec4(aPos, 1.0);` | three.js 内建 varying 的等价物 |

#### V-02 ⭐ 顶点纹理取样的降级变体（GLES2 特有）

| 探测结果 | 变体 | 做法 |
|---|---|---|
| `MAX_VERTEX_TEXTURE_IMAGE_UNITS ≥ 2` | **V-A（首选）** | `D3422` **逐字照搬**，零改动 |
| `= 1` | V-B | `displacementMap` 移到片元 ⇒ 位移效果丢失（可接受：`main:3422` 注释写着 "Experimenting with displacement map"） |
| `= 0` | **V-C** | 顶点着色器零纹理：`fade0/fade1/fade2` 改在片元重算，`vLightGrass`/`vHeather` 整段**下移到片元**，`displacementMap` 位移丢弃 |

⚠️ **V-C 是"结构上偏离逐行移植"** ⇒ 必须显式标注为「有意偏离」（与 Canvas 文档 §3.2 的偏离表同体例）。
探测：`GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS, 1, buf, 0)`。

#### F-01 `terrain` 片元着色器（源：`D3426` 40 行 + `D3430` 111 行）

| 上游行 | 原文 | 改成 | 说明 |
|---|---|---|---|
| `D3426:3-13` | 11 个 `varying` | 保留（与 V-01 一致） | |
| `D3426:15-23` | 9 个 `uniform sampler2D` | `uGrassMap/uSandMap/uRockMap/uRockMapBump/uGravelMap/uForestMap/uHeatherMap/uFadeRockMap/uFadeFiner` + `uBaseMap` | ⚠️ **`uBaseMap` 是 `ut` = `grass_summer_01.jpg`**（`main:2773`）—— **但见 §十 遗留核验项**：上游的 `T` 块**替换了** `#include <map_fragment>`（`main:826-837`）⇒ 可能**根本没乘进 `diffuseColor`** ⇒ **落地时以 `main:836` 的 `T` 块原文为准**，别盲目补 |
| `D3426:25` | `uniform vec4 colouration;` | `uColouration` | 皮肤相关（`main:3403` 默认 `[0.18,0.08,0.1,0]`） |
| `D3426:29-36` | `terrainBlend()` **5 行** | **逐字照搬** | ⭐ 核心混合数学，只有 4 行有效代码 |
| `D3426:38` | `vec4 roadCol = vec4(0.45, 0.45, 0.474, 1.0);` | **原样** | |
| `D3430:3-5` | 3 次 `fadeRockMap` | **原样**（换 uniform 名） | ⚠️ 与**顶点**的 3 次是不同尺度 ⇒ 不能合并 |
| `D3430:9-10` | `darkGrass = texture2D(heatherMap, vUv); darkGrass.a = darkGrass.g;` | **原样** | |
| `D3430:14` | `vec4 texelColor = texture2D( grassMap, vUv );` | **原样** | |
| `D3430:18` | `texelColor += colouration * vLightGrass;` | **原样** | |
| `D3430:22` | `texelColor = mix(texelColor, darkGrass, vHeather * smoothstep(0.3, 0.7, fade1));` | **原样** | |
| `D3430:26` | `texelColor = mix(texelColor, texture2D(forestMap, vUv), vTreeMask);` | **原样** | |
| `D3430:41` | `texelColor *= vDarkGrass;` | **原样** | |
| `D3430:45-55` | SAND BLEND 三层分支 | **原样** | ⚠️ `texture2D(sandMap, vUv)` 出现 2 次 ⇒ 动态分支最坏 +2 采样 |
| `D3430:59-91` | ROADSIDE GRAVEL 分支 | **原样**（含**反向边界** `smoothstep(rp+0.15, rp-0.15, fade)`） | |
| `D3430:96-102` | STEEPNESS CLIFF | **原样**；⚠️ `rockMapBump` 在 `roadProx==0.0` 分支是 `1.0 - x`、else 分支是 `x` ⇒ **两个分支符号相反**，逐字照搬 | |
| `D3430:104-106` | `texelColor.a = 0.2 + fade1*0.2; … terrainBlend(…); texelColor.a = 1.0;` | **原样** | |
| `D3430:110` | `diffuseColor *= texelColor;` | **原样** | |
| **新增** | — | **PBR 块（§2.1 的 45 行）** → `vec3 outgoing = reflectedLight.directDiffuse + reflectedLight.directSpecular + reflectedLight.indirectDiffuse + reflectedLight.indirectSpecular;` `gl_FragColor = vec4(outgoing, 1.0);` | |
| **新增** | — | **雾块**：`mix(rgb, uFogColor, smoothstep(uFogNear, uFogFar, vFogDepth))` | ⭐ `smoothstep` |

**⭐ 采样数核算**：`D3430` 静态 15 次；动态分支最多 +2 ⇒ **最坏 17**。§四 的填充率预算按 **17** 算（保守）。

#### F-02 `sky` 片元（源：`D1076` 14 行 + `D1080` 35 行）

| 上游行 | 原文 | 改成 |
|---|---|---|
| `D1076:1` | `#include <uv_pars_fragment>` | `varying vec2 vUv;` |
| `D1076:3-5` | `uniform vec3 nearCol; uniform vec3 farCol; uniform bool hasClouds;` | `uNearCol` / `uFarCol` / **⚠️ 改 `uniform float uHasClouds;` 并用 `if (uHasClouds > 0.5)`**（部分老驱动对 bool uniform 支持差） |
| `D1076:7-8` | `varying vec2 vUUv; varying vec2 vUUUv;` | 保留 |
| `D1076:10-14` | `NOISE_GRANULARITY` + `random()` | **保留**（`random` 未被 `D1080` 使用） |
| `D1080:1` | `#include <aomap_fragment>` | **删（空）** |
| `D1080:3-6` | `vX/vY/dist` | **原样** |
| `D1080:11` | `vec4 skyCol = vec4(mix(farCol, nearCol, dist*dist), 1.0);` | **原样** |
| `D1080:13-31` | 云混合 `max(texture2D(map, vUUv), texture2D(map, vUUUv))` → `mix(skyCol, cloudCol, cloudCol.a)` | **原样** |
| `D1080:32-34` | `diffuseColor = skyCol;` | **原样** |

⚠️ **`skyCol` 命名冲突**：`D3465`（水面）也用 `uniform vec4 skyCol;` ⇒ 我们的水面 shader 用 `uWaterSkyCol`。

#### F-03 `terrainPlanet` 片元（源：`D10791` 86 行）

**逐行照搬** `D10791:3-85`（surface/subSurface/shale/rock/cliff/rubble 六张贴图 + bump，`vCurvature` 驱动的 4 次 `terrainBlend`）。
它依赖 `terrainBlend`（`D3426:29-36`，同一份）与 `vCurvature`/`roadProx`/`steepness`/`fade0/1/2`
⇒ **顶点着色器复用 `terrain` 的 V-01，只换片元** ⇒ 编译两个 program，共享同一套 VBO ⇒ draw call 不增加。

### 3.3 子系统移植清单

| ID | 子系统 | 上游位置 | 我们要写什么 | 难度 | 依赖 |
|---|---|---|---|---|---|
| **G-01** | **投影/相机数学** | `main:1011-1018`、`:1084`、`:15496` | `HighwayProjection.kt`：`perspective(fovy=68°, aspect=W/H, near=0.25, far=ue)` + `lookAt` + `uViewProj` | 🟢 低 | — |
| **G-02** | **CPU 核（复用）** | `main:1246/1461/1458/1501/1553/1630/1676/1753/13003` | **不重写** ⇒ 复用 Canvas 文档 K2 的 `visualizer/highway/port/HighwayMath.kt`（逐语句转写 + `HighwayPortGateTest` 黄金向量） | 🟢 已完成 | K2 |
| **G-03** | **地形高度场 + LOD 调度** | `main:1630`、`main:14064-14077` | `HighwayTerrain.kt`：按 `H[2]` 预设 + 我们的 `res` 降档生成 cell；**照抄 `tileReady` 的签名** | 🟡 中 | G-02 |
| **G-04** | **GPU 顶点缓冲（LOD 合批）** ⭐ | 上游用 `drawRange`（`main:13933-13940`） | `HighwayTileStreamer.kt`：**环形 VBO** —— 每 LOD 层一个持久 VBO（cell 槽位固定）+ 一个索引缓冲（每帧只更新索引） | 🟡 中 | G-03 |
| **G-05** | **LOD 分层与距离剔除** | `main:886-933`、`main:1010/15010` | 3 层：L1 `res=R1`（10u cell）/ L2 `res=R2` / **L3 = 剔除** | 🟡 中 | G-03 |
| **V-01** | **地形顶点着色器** | `D3418`+`D3422` | §3.2 V-01 逐行表 | 🟡 中 | G-04 |
| **V-02** | **顶点纹理降级变体** | — | V-A / V-B / V-C 自动选 | 🟡 中 | V-01 |
| **F-01** | **地形片元（Hills）** | `D3426`+`D3430` | §3.2 F-01 逐行表 | 🔴 高（最长） | V-01 |
| **F-03** | **地形片元（Planet）** | `D10791` | §3.2 F-03 | 🔴 高 | V-01, P7 |
| **P-01** | **PBR 光照块** | `2.f5fa8177.chunk.js` 的 `bsdfs` / `lights_physical_pars_fragment` / `lights_pars_begin`；`main:840` 的内联 `lights_fragment_begin` | **逐字摘取 + 删分支** ⇒ 一个 45 行的 GLSL 字符串常量 | 🔴 高（但**机械**） | — |
| **P-02** | **雾块** | three.js `fog_fragment` | `mix(rgb, uFogColor, smoothstep(near, far, depth))` | 🟢 低 | — |
| **S-01** | **天空** | `D1064`+`D1068`+`D1072`+`D1076`+`D1080` | 天空 = 一个**跟随相机的全屏 quad**（不是天空盒）⇒ 顶点着色器把 quad 顶点投到 NDC，片元用 `vUv` 算 `dist` ⇒ 天然"无限远"，无需深度写入 | 🟢 低 | G-01 |
| **S-02** | **云** | `D1080:24` + `main:1068` | 顶点着色器逐行照搬 `D1068:3-20`（含 `h0/h1/lowerScale/motionScale`）⇒ 云随相机运动（视差） | 🟡 中 | S-01 |
| **R-01** | **路面 ribbon** | `main:13877-13898`、`:13830-13844`、`:3968-3977` | **原密度 972 三角**（D7）⇒ 静态 VBO，随相机滚动更新；材质 `roughness=1` + `road_*` 贴图 + `alphaTest` | 🟡 中 | G-01, G-02 |
| **R-02** | **路基 / 地形与路面的融合** | `main:1501-1529`、`:1044`、`:1461` | 属于 CPU 核（G-02）⇒ GL 侧只是把 `roadProximity` 作为顶点属性喂给着色器 | 🟢 低 | G-02 |
| **V-03** | **植被 instancing** ⭐ | `main:7422-7474` | **合并成单次 draw**：可见 cell 的草/灌木/树实例矩阵烘进一个动态 VBO（4 顶点/实例 + per-instance `variant`/`shadow`）；`ANGLE_instanced_arrays` 不可用时**降级为 CPU 展开** | 🔴 高 | G-03, V-02 |
| **V-04** | **billboard 变体** | `main:7440-7454`（`trees.far` / `trees.near` / `brush.grass` / `brush.bush`，uv 矩形不同、scale 8/8/1.6/1.6） | 4 套 uv 矩形 + 2 套缩放 ⇒ 一个 program + 一个 `uAtlasRect` uniform + 一个 scale | 🟡 中 | V-03 |
| **T-01** | **纹理加载与 skin 绑定** | `main:3393-3403`、`:2773-2828`、`:807-810` | `HighwayTextures.kt`：`glGenTexture` + `glTexImage2D`（**POT**）+ `mipmap` + `GL_REPEAT` + 各向异性（上限 4） | 🟡 中 | — |
| **T-02** | **6 skin × 10 天气 的贴图表** | `main:3232/3259/3284`、`:10696/10715/10731` | 启动时全部解码并上传（换环境时只换绑定） | 🔴 高（内存） | T-01 |
| **W-01** | **水面** | `main:3460-3472` | ⭐ **深度用 CPU 高度场求**（顶点着色器算 `vDepth = waterY - terrainHeight`）⇒ **不需要深度纹理**；`WATER_DEPTH` 的 `discard`（`main:3469`）用 `vDepth > 10.0` | 🟡 中 | G-02, G-03 |
| **M-01** | **`.obj` 解析** | `mirror/robert/.../static/media/*.obj`（14 个） | 最小 OBJ 解析器（`v` / `vn` / `vt` / `f`，支持 `v/vt/vn` 与 `v//vn`） | 🟡 中 | — |
| **M-02** | **模型（车/护栏/标志）** | `roadster-07` / `roadster-07-int` / `roadster_wheel_02` / `wall_barrier_02` / `sign` | 只移植护栏 + 标志；⛔ 交通车不做（D20）；⛔ 玩家座驾见 O7 | 🟡 中 | M-01 |
| **R-09** | **阴影贴图** | `main:18843/18859/18868/18870-18879` | ⛔ **不做**（只覆盖车体 8×8 单位，地形不投影 ⇒ 贡献≈0）⇒ 省掉 FBO + 深度纹理 + 第二遍渲染 | 🟢 **删** | — |
| **X-01** | **换歌换环境（D24）** | `main:2897-2910`、`:3232-3325`、`:10518-10580`、`:10696-10731` | `HighwayEnv.kt`：环境巡游表、三阶段雾白过场、pending 语义 | 🔴 高 | T-02, G-03 |
| **X-02** | **音乐映射** | Canvas 文档 §4.6 | `uSpeed/uFogFar/uSkyTint/uHorizon/uCloudPhase` 五个 uniform；**不改几何**（保 G1 对账基线） | 🟢 低 | G-01 |

### 3.4 资产清单（已实测尺寸）

`mirror/robert/.../static/media/` 下 **64 png + 53 jpg**。**全部 ≤ 1024²**，多数 512² / 256² ⇒ **D22「512²」天然满足大半，只需把 4 张 1024² 降到 512²**。

| 组 | 关键文件 | 数量 | 备注 |
|---|---|---|---|
| 地形 albedo | `grass_spring_01` / `grass_autumn_01` / `grass_summer_01`、`rock_06`、`rock_06_winter`、`sand_01`、`gravel_01`、`heather_01` / `heather_spring_01`、`forest_summer_01` / `forest_autumn_01` / `forest_winter` | 12 | Hills 3 skin |
| 混合遮罩 | `crossfade_fine`（= `fadeRockMap`）、`crossfade_finest`（= `fadeFiner`） | 2 | ⭐ **恒定，不随 skin 变** |
| bump | `rock_06_bump`（= `rockMap` **且** = `displacementMap`） | 2–3 | |
| 云 | `clouds_01/01b/02/03/04`、`clouds_autumn_sunset`、`clouds_sunrise_b`、`clouds_winter_sunrise` | 8 | |
| 植被 | `foliage_grass_*`（256²）、`foliage_bush_*`、`foliage_brush_winter`、`trees_{spring,summer,autumn,winter}_{far,near}_01(+b)` | 20 | 4 变体 × 6 skin |
| 路面 | `road_03` / `road_04`（1024²⇒**降到 512²**）、`mars_road_base`、`venus_road_base`、`road_moon`、`road_venus`、`road_offworld` | 7 | |
| 护栏 | `wall_barrier_02` / `_n` / `wall_barrier_wood` / `_snow`、`concrete_01`、`drystone_04` / `_snow`、`mars_barrier(_wide)`、`venus_barrier_wide` | 10 | |
| Planet | `mars_{surface,subsurface,shale,rock,cliff,rubble}`（各含 bump）= 11、`venus_*` = 7、`moon_*` = 4 | 22 | D25 / P7 |
| 水 | `sea_waves`（512²） | 1 | |
| 雪 | `snow_01`（1024²⇒降到 512²） | 1 | |

**贴图内存预算**：

| 项 | LOW (256²) | MEDIUM/HIGH (512²) |
|---|---|---|
| 地形 albedo（4 张 × RGB888） | 0.75 MB | 3.0 MB |
| 植被图集（8 张 × RGBA8888） | 8.0 MB | 8.0 MB |
| 遮罩 2 + bump 1（RGB888） | 0.38 MB | 1.5 MB |
| 云 2–3 张 | 0.4 MB | 1.6 MB |
| 路面 1–2 张 | 0.13 MB | 1.0 MB |
| **贴图小计（当前 skin）** | **~9.7 MB** | **~15.1 MB** |
| 顶点缓冲（L1+L2 环形 + 路面 + 植被） | ~1.2 MB | ~1.2–1.5 MB |
| **合计** | **~11 MB** | **~16–17 MB** |

⚠️ 与 API 22 的差异：Canvas 路线用 `Bitmap`，**API 22–25 走 native heap，必须 `recycle()`**；
GL 路线用 `glDeleteTextures` / `glDeleteBuffers` ⇒ **堆外，GC 不管** ⇒ ⛔ **`onExit()` / `onViewDetached()` 必须显式释放**（门禁 G20）。

### 3.5 ⭐ 一个必须自己查清的问题：上游 442,000 三角里有多少是**真被提交的**

Canvas 文档 §3.1 把「442,000 三角/帧」当作必须正面解决的量。但按本轮核实：

- far 平面 `ue ≈ 2834`（16:9），而远景 tile 是 **700 世界单位的大 tile**（`main:1010/15010`）⇒ **绝大多数远景 tile 的顶点距离 > 2834 ⇒ 被 three.js 的视锥剔除直接丢掉**。
- 雾 far = 1400 ⇒ **1400 之外的任何东西都是纯白色**（雾色 `0xFFFFFF`）。

⇒ ⭐ **两条独立的剔除依据**：
1. **视锥剔除**（three.js 自动）：> `ue` 的 tile 不提交。
2. **雾 far 剔除**（我们主动加）：> 1400 的地形必然是纯雾色 ⇒ **不画它，画面完全相同**。

⇒ **决策（D-new-04）**：**主动实现"雾 far 剔除"** —— 任何 cell 的**最近角点**距相机 > `uFogFar` ⇒ 整个 cell 不生成、不上传、不绘制。
⇒ **可验证性**：门禁 **G19「雾 far 剔除正确性」** —— 剔除边界处，屏幕上该区域的像素值 **必须等于雾色**（ΔRGB = 0）。
⇒ **这条剔除把「远景 245,000 三角 + 它的填充」整块消掉** ⇒ 我们的三角预算直接落在 **1–2 万**量级。
⚠️ **「442,000 是理论值还是实测值」仍需确认** ⇒ 列 **R-11**：用上游自己的 `renderer.info.render.triangles`（`main:18990` 同源）在浏览器里跑 600 帧，取实测分布。**这条必须在 P2 之前做完**。

---

## 四、性能预算与降级策略

### 4.1 ⭐ 瓶颈判定：**填充率，不是三角数**

| 量 | 上游 @1080p | 我们估算 @1080p（MEDIUM, scale 0.75） |
|---|---|---|
| 像素 | 2.07 M | 1.17 M（1440×810） |
| 地形覆盖比 | ~65% | ~65% |
| 地形片元采样 | 15–17 | 15–17 |
| **地形纹理采样/帧** | **2.07M × 0.65 × 16 ≈ 21.5 M** | **1.17M × 0.65 × 16 ≈ 12.2 M** |
| 天空采样/帧 | 0.42 M × 5 = 2.1 M | 2.1 M |
| **合计纹理采样/帧** | **≈ 23.6 M** | **≈ 14.3 M** |
| 三角/帧 | 442,000（理论）⇒ 实测待定（R-11） | **≈ 10,000–14,000** |
| draw call/帧 | ~1,000（每 cell 一个 InstancedMesh） | **6–10** |

⇒ **实测对照（创维 PowerVR Rogue G6110）**：
纹理吞吐实测 **≈ 357 Mtex/s** ⇒ 上游 @1080p 的 708 Mtex/s **这台机给不了**（差 2 倍）；
我们 @0.75 scale 的 429 Mtex/s 也给不了。
⇒ **本机可行的档位**：1080p 下必须 `renderScale ≤ 0.5`（5.1 M 次/帧，60 fps）；
`0.4` 有 71 fps 余量。⇒ **`renderScale` 不只是"降级旋钮"，在本机是"能不能到 60 fps"的开关**。
⇒ ⭐ **这条把"逐像素逐行移植"推向一个岔路**（见 §九 的 O8）：
**要么** 接受 `renderScale 0.4~0.5`（画面变软，1080p 屏上放大 768×432~960×540），
**要么** 把 `D3430` 的 15 次采样**离线合并**成更少的纹理（每像素 15 → 3~4 次）⇒ 填充率降 4 倍、`renderScale 0.75~1.0` 可行，
但**那就不是"逐行移植着色器"了**，观感偏差必须用黄金帧逐像素对账来量化。

### 4.2 三角 / draw call / CPU 预算表

| 档 | `VisualQuality` | renderScale | 视口 @1920×1080 | L1 `res` | L2 `res` | 植被实例 | 路面三角 | 地形三角（估） | **合计三角** | **draw call** | **纹理采样/帧** | CPU/帧 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **LOW** | `LOW` | **0.5** | 960×540 | 2 | 1 | 60 | 972 | ~3,000 | **~4,000** | **6** | **~3.6 M** | ≤ 2.0 ms |
| **MEDIUM** | `MEDIUM` | **0.75** | 1440×810 | 4 | 2 | 200 | 972 | ~9,000 | **~10,200** | **8** | **~14.3 M** | ≤ 4.0 ms |
| **HIGH** | `HIGH` | **1.0** | 1920×1080 | 4 | 2 | 400 | 972 | ~9,000 | **~10,400** | **10** | **~23.6 M** | ≤ 5.0 ms |
| （对照）上游 | — | 1.0 | 1920×1080 | 16 | 8 | 20,000 | 972 | 441,028 | **442,000** | ~1,000 | **23.6 M** | — |

**CPU/帧构成（MEDIUM）**：相机推进 + 道路查询（`qe`）0.3 ms；LOD cell 可见集计算（视锥 + 雾 far 剔除）0.5 ms；
新进入 cell 的高度场生成（摊到 5–10 帧）0.3 ms/帧；VBO 索引更新 0.05 ms；植被实例矩阵 0.4 ms；bridge 拷贝 0.01 ms
⇒ **合计 ~1.6 ms**（留 2.4 ms 余量给 Compose + 歌词）。
⚠️ G-02 的数学核（`lookupHeight2` 16 角双线性 + 8 八度噪声）逐位照搬 ⇒ 单次调用 ~1–3 µs
⇒ **每帧的新 cell 生成量必须 ≤ 30 个** ⇒ 这是 `res` 降档与「相机跨 cell 才生成」的直接理由。

### 4.3 ⭐ 分辨率代价基线（实测，2026-10-29）

**方法**：不重新渲染 —— 把**原版黄金帧**（16 张，1280×720）降到各 `renderScale` 对应的分辨率，
再用**双线性放大**回 1280×720，用**同一个** `diff.mjs` 与原帧对账。
⇒ 这是"纯放大糊"造成的差异 = **方案 α 的代价下界**（真实渲染会因 mipmap 而略好于此）。

| renderScale | 渲染分辨率 @1080p 屏 | SSIM 中位 | SSIM 最低 | MAE 中位 |
|---|---|---|---|---|
| 1.00 | 1920×1080 | 1.0000 | 1.0000 | 0.000 |
| 0.75 | 1440×810 | **0.9528** | 0.9081 | 1.750 |
| 0.50 | 960×540 | 0.9369 | **0.8787** | 2.128 |
| **0.40** | **768×432** | **0.9152** | 0.8416 | 2.555 |
| 0.35 | 672×378 | 0.8987 | 0.8130 | 2.913 |
| 0.30 | 576×324 | 0.8885 | 0.7948 | 3.117 |
| 0.25 | 480×270 | 0.8724 | 0.7683 | 3.425 |

（产物：`output/slowroads_parity/scale_cost/` —— 8 张对比图 + `scale_cost.txt/json`。）

#### ⭐⭐ 由它逼出来的**门禁拆分**（v1.4）

G18 原判据是"全帧 SSIM ≥ 0.92 且 MAE ≤ 2.0"，而 **0.92 是按「同一分辨率下上游自己跑两遍」的噪声底标定的**
（实测 0.9240 / 1.744 / 零差 91.12%）。
我们在 0.4 档是**故意**用更低分辨率渲染 ⇒ 那个差异是**已知的设计选择，不是移植误差**
⇒ **混成一个阈值是错的**。⇒ 拆成两条：

| 门禁 | 判据 | 隔离什么 |
|---|---|---|
| **G18a 移植误差** | 我们的渲染在**原生分辨率**（与原版同尺寸）下对账，**SSIM ≥ 0.92 / MAE ≤ 2.0** | 移植准不准（数学、GLSL、材质绑定、相机） |
| **G18b 分辨率代价** | 我们的**最终输出**（低分辨率放大后）对账，阈值按 §4.3 的**基线表**取 | 降分辨率单独值多少（可接受则接受，不可接受则改采样数） |

⇒ **G18a 不受 renderScale 影响**（始终在原生分辨率比）⇒ 移植误差永远有可测的预算。
⇒ **G18b 是"观感取舍"的记录**，不是"合格/不合格"的开关 —— 阈值由所有者按 §4.3 的基线表与实拍对比图判读。

#### 👁 人工判读（2026-10-29）

对比图（`output/slowroads_parity/scale_cost/full_*.png`，左=原版 1280×720，右=0.4 降分辨率放大）：

- **全屏观看距离：差异小** —— 路面走向、护栏、树、山脊、天空层次、雾都读得一样；右侧只是整体略软。
- **真实损失集中在两处**：① **路面/岩石的细颗粒纹理**（原版有明显的沥青颗粒，降采样后被抹平）；
  ② **高对比边缘**（中线虚线、护栏、树冠轮廓）变软。
- 1:1 像素级检视（`crop_*.png`）差异明显 ⇒ **SSIM 0.9152 是偏严的指标**，它低估了感知相似度。

⇒ **裁决（2026-10-29）：方案 α 维持**，接受 0.4 档的软化；G18 按上面拆成 a/b 两条。
⇒ ⛔ **但 60 fps 仍未证**：0.4 档的 71 fps 是按探针**最坏情况** shader 推算的
（15 次采样刻意用不同纹理/缩放/偏移）；真实上游 `D3430` 有不少采样落在**动态分支**里 ⇒ 实际更快。
⇒ **真机真 shader 的帧时**是 P2 的验收项之一。

### 4.4 ⭐ 两条真机专属的坑（2026-10-29 探针实测）

1. **⚠️ PowerVR 的 shader 编译极慢**：PVRShaderCompiler 编译上面那个 PBR 片元着色器要 **~5 秒**
   （warmup 60 帧实测 85.7 ms/帧）⇒ **shader 变体必须在加载期预热**（进主题时一次性编译好全部变体），
   否则**第一次进新地形会冻屏数秒**。⇒ 新增门禁 **G31：进主题到首帧 < 1.5 s**（预热在进主题时同步做）。
2. **⚠️ E51 覆盖层会遮蔽仓库文件**（`~/.gradle/init.d/10-nasmusic-e51-local.gradle` 挂入
   `app/src/e51-overlay/ovl`）：
   覆盖层里含 **仓库同名文件的副本** ⇒ **Kotlin 任务层以覆盖层那份为准** ⇒ 改了仓库那份而没改覆盖层那份，
   **本地构建等于没改**（CI/公开构建没有覆盖层，反而能编过 ⇒ 本地与 CI 行为分叉）。
   ⇒ 本轮被遮蔽的三份：`VisualizerRendererFactory.kt` / `VisualizerStage.kt` / `RenderContext.kt`
   ⇒ **改动这三份时必须同时改两处**（仓库 + 覆盖层副本）。

### 4.5 ⭐ 降级策略（三级旋钮 + 自适应回退）

**旋钮 1：`renderScale`**（主旋钮，线性缩放填充率）—— 上游同款取值 `[0.5, 0.75, 1, 1.5]`（`main:11893`）。

⭐ **本机实测推算**（基准：15 次采样/像素 @1080p 全屏 = 87.2 ms ⇒ 填充率与像素数成正比）：

| renderScale | 渲染分辨率（1080p 屏） | 帧时（推算） | fps（推算） | 判定 |
|---|---|---|---|---|
| 1.00 | 1920×1080 | 87.2 ms | 11.5 | ❌ 不可用 |
| 0.75 | 1440×810 | 49.1 ms | 20.4 | ❌ 不可用 |
| **0.50** | **960×540** | **21.8 ms** | **46** | ⚠️ 勉强（自适应回退会再降一档） |
| **0.40** | **768×432** | **14.0 ms** | **71** | ✅ **目标档**（有 18% 余量） |
| 0.30 | 576×324 | 7.8 ms | 128 | ✅ 兜底 |
| 0.25 | 480×270 | 5.5 ms | 183 | ✅ 最低档 |

⇒ **本机档位定为：HIGH = 0.40 / MEDIUM = 0.35 / LOW = 0.30**，自适应回退可再降到 0.25。
⚠️ 上表的"推算"基于**探针的最坏情况 shader**（15 次采样刻意用不同纹理/缩放/偏移，杜绝缓存合并）；
真实上游 `D3430` 有不少采样落在**动态分支**里（砂/砾石/峭壁），只在部分像素发生 ⇒ 实际平均值应低于 87 ms。
⇒ **实测为准**：P2 出图后用真机帧时替换本表的推算值。
⇒ **实现方式**：`GLSurfaceView.holder.setFixedSize(w, h)` 让 Surface 本身是 960×540，由合成器做硬件缩放放大
（⛔ 不要自己渲到 FBO 再 blit —— 多一次全屏拷贝，且拿不到硬件缩放器）。

**旋钮 2：`res`（LOD 密度）** —— 改 `res` 改变地形形状，但 **G1 对账的是纯函数**（`qe`/`lerpHeight`/`lookupHeight2`），不是生成出来的网格
⇒ `res` 可自由降档 ✅（Canvas 文档 §六已明确"改 `res` 不会连带改变地形形状"）。

**旋钮 3：贴图尺寸**（256² ⇒ 512²）—— ⚠️ 会改变采样结果 ⇒ **破坏 G18 逐像素对账** ⇒ 只在 LOW 档用 256²，且**归档 LOW 档的独立参考帧**。

**⚡ 自适应回退（运行时）**：

```
每 120 帧取第 95 百分位帧时：
  > 22 ms（< 45 fps）持续 2 秒 ⇒ renderScale 降一档（1.0 → 0.75 → 0.5 → 0.4）
  < 12 ms（> 83 fps）持续 8 秒 ⇒ 升一档（带滞回，单次会话最多升 1 次）
⛔ 单次会话最多自动降 2 档（防振荡）
⛔ 降档必须记 logcat（release 也可见，tag = "Highway"）
```

**档位合成规则**：

```
effectiveScale = min( userQualityScale , deviceTierScale )
deviceTier = f( GLES version, GL_RENDERER, MAX_TEXTURE_SIZE, MAX_VERTEX_TEXTURE_IMAGE_UNITS )
  · GLES ≥ 3.0 且 renderer 不在已知弱名单 → 1.0
  · GLES 2.0 且 MAX_TEXTURE_SIZE ≥ 4096 且 顶点纹理单元 ≥ 2 → 0.75
  · 否则 → 0.5
```

⚠️ 弱名单不能只靠字符串匹配 ⇒ 设备档位是**初值**，**自适应回退才是终值**。

---

### 4.6 ★ Kotlin 侧落地状态（P0 / 世界核 / P1 / P3 / P4，2026-10-30）

| 阶段 | 内容 | 状态 | 门禁 |
|---|---|---|---|
| **P0** | 骨架：bridge / 能力探测 / GLSurfaceView / 投影数学 / renderer | ✅ | 编译通过 |
| **世界核** | 侵蚀高度图 + 道路链 + 树图 + alea PRNG（**2653 行**，逐行照抄上游 chunk） | ✅ | **高度场 800/800、道路全链 1501/1501、树图 400/400 逐位相等（零容差）**；Node 交叉校验零差异；3 颗外 seed 全 PASS |
| **P1** | 地形网格 + 天空 + 雾 + 相机 | ✅ | GLSL 逐字 9/9；远景格 5041 顶点/29400 索引独立重算相等；投影矩阵 16 数相等；相机朝向 vs 上游抓包 4/4 对齐 |
| **P3** | 路面 ribbon | ✅ | **路面几何与上游 chunk 原文抽取版逐位相等（14 段 / 5916 个 float32 原始位，0 处不等）** |
| **P4** | 植被 instancing | ✅ | 放置规则独立重算核对；6 program 预热（源码级门禁） |

#### 4.6.1 ⚠️ 对账工具链的现状（**卡点**）

- 现有对账工具（`scripts/e52/` 的 diff / 分区域 MAE / 轮廓对账）**量的是 WebGL 原型页**（`docs/archive/e52-highway-webgl-preview.html`），**不是 Kotlin GL 场景**。
- ⇒ Kotlin 侧的改动不落到原型页，**SSIM 数字必然等于 P1 基线（0.674481）⇒ 零信息量**。
- ⇒ **真机出图才能对号**。原型页的抓帧在 headless Edge + swiftshader 下 **6 分钟未出帧**（软渲染太慢）。

#### 4.6.2 三个新上游事实（影响后续规划）

1. **上游 `getTreesAt` 的 LOD 0 格子本来就没有独立的树**（`this.t ? … : null`）。本 seed 沿路 200 节点的 LOD 分布 = **0:172 / 1:9 / 2:19 / 3:0**
   ⇒ **约 86% 的路段上游自己也没树** ⇒ **「补树后 SSIM 不变」是上游行为，不是移植 bug**
   ⇒ 后续计划里不应再把「补树」当作中景差异的主因。
2. **上游 `stitchNormals` 依赖 JS 数组自动扩容**（越界写入不报错）⇒ Kotlin `ArrayList` 会 `IndexOutOfBounds`
   ⇒ 已在 `terrain/` 下本地复刻 `processCurve`+`stitchNormals` 绕开，**`world/` 6 个已验收文件未动**。
3. **上游路面材质是 `FrontSide`**（`Oe`），**不是** `DoubleSide`（`DoubleSide` 是**植被**材质的属性）。

#### 4.6.3 ⛔ 「地形色偏」是假象 —— banded ΔRGB 的分解（2026-10-30）

> **一句话结论**：**不存在地形 albedo 色偏**。中段 ΔR/ΔG = +20.2/+20.0 是**内容不符**造成的
> 统计假象；在「两边看着是同一类表面」的像素上，真实色偏是 **−0.1/+0.3/−1.9**（已是 0 级）。
> ⇒ 任何调光强 / 雾 / 曝光 / 贴图亮度都修不了它（那是把内容差摊平，属红线）。

**① 方法（可复现）**：`scripts/e52/e52_color_bias.mjs` 增加了 Δ 分解 —— 逐像素算亮度差
`|Δlum|`，`|Δlum| ≤ 12` 的像素视为「同内容」（同一类表面），**只在这批像素上**求带符号 ΔRGB
⇒ 这就是**真实色偏**；`|Δlum| > 12` 的像素单列为「内容不符」，不计入色偏。
⛔ 该判据不自证：若整帧是均匀 +20 亮度，则**没有任何像素**落在 `|Δlum| ≤ 12` 内
（`同内容占比` 会掉到 0）；实测占比 46.8%~64.1% ⇒ 确实存在大批亮度吻合的像素。

**② 实测（4 帧平均，`output/slowroads_parity/x_default/`）**：

| 带 | 带均值 ΔR/ΔG/ΔB | 同内容像素占比 | **真实色偏 ΔR/ΔG/ΔB** | 内容不符那批的 ΔR/ΔG/ΔB |
|---|---|---|---|---|
| sky（**固定 0–180 带，⛔ 误命名，见下**） | +8.0 / +9.0 / +3.5 | 64.1% | +0.8 / +0.7 / +0.3 | 见逐帧（含山丘压顶） |
| mid | **+20.2 / +20.0 / +6.9** | 46.8% | **−0.1 / +0.3 / −1.9** | n090: +44.3/+37.7/+31.8 |
| near | +18.6 / +16.4 / −10.3 | 57.9% | **−1.0 / −2.3 / −4.4** | n040: +66.6/+58.9/−20.6 |

> ⛔ **「sky = y 0–180」是误命名，必须按掩膜口径重测。** 那只是"画面上部 180 行"，不是天空：
> 黄金帧 n140 的 0–180 带均值 = (200.7, 210.1, **159.5**) —— B 远低于 R/G ⇒ 是**地形/植被**（山丘压到画面顶部）。
> ⇒ 把 n140 的 "sky MAE" 与 n000 的平均，等于把**地形差**当成天空色偏去调天空。
> 已加**真天空掩膜**（`B ≥ 180 && B ≥ G−8 && G ≥ R−4`，覆盖上游 `SKY_NEAR 0x68a9f8` → `SKY_FAR 0xcbfdfe` 的整条渐变），
> 只在「两帧同时是天空」的像素上统计，并以**带内天空占比**判定该带是否可当天空用：

| 帧 | 0–180 带内天空占比（我们 / 黄金） | 交集像素 | **真天空 ΔR/ΔG/ΔB** | 判定 |
|---|---|---|---|---|
| n000 | 90% / 74% | 169,318 | −1.4 / −1.8 / −0.9 | ✅ 可用 |
| n040 | 60% / **46%** | 104,981 | +1.8 / −0.9 / −0.8 | ⛔ 黄金帧该带近半是岩石切坡，剔除 |
| n090 | 86% / 84% | 199,784 | −3.3 / −1.4 / −1.2 | ✅ 可用 |
| n140 | — | **0** | — | ⛔ 交集为 0：**我方该帧顶部几乎没有天空** |

⇒ **真天空色偏 = ΔR −2.3 / ΔG −1.6 / ΔB −1.0**（n000+n090 平均；n140/n040 已剔除）。
⛔ **不要把 sky / mid / near 的 MAE 平均后当"天空色偏"去调天空**：n140 的 55.5 与 n000 的 33.7 不是同一类差异。

⇒ **同一块柏油**：n000 y=630 我们 (129,137,144) vs 黄金 (131,141,146)，Δ = −2/−4/−2；
n140 y=630 Δ = −3/−4/−2。**同一块草地**：n140 y=90 x=107 两边**逐位相同** (183,195,113)。
⇒ 色偏符号还会**逐帧翻转**（n000 near = −8.6/−8.9/−4.7，n040 near = +56.3/+49.4/−18.5）
—— 真色偏不可能翻号，**只有内容差会**。

**②-b 为什么 n040 前景是"黄绿"而不是"中性灰" —— 反照率链上唯一真正出问题的一环**

`D3430.glsl:57-61`（上游原文，逐字移植）：
```glsl
//// ROADSIDE GRAVEL
if(roadProx < -0.2) {
  texelColor = roadCol;                       // ← 路廊内：地形本身被涂成路面色
} else if(roadProx != 0.0 && roadProx < 0.7) {
  float rp = roadProx / 0.7;
  texelColor = mix(texture2D(gravelMap, vUv), texelColor, smoothstep(rp + 0.15, rp - 0.15, fade));
}
```
`roadCol` 是**着色器内常量** `vec4(0.45, 0.45, 0.474, 1.0)`（`D3426.glsl:38`）—— **中性灰**。
⇒ 上游的**地形在路廊内就被涂成路面色**，路面 ribbon 只是叠在它上面的那一层。
这就解释了黄金帧 n040 near 带 **(142.2, 150.9, 146.8) 的 R≈G≈B 中性灰**：
那不是"碎石/岩石贴图"，**主要是 `roadCol`**。

用 `?diag=albedo`（`gl_FragColor = vec4(texelColor.rgb, 1.0)`，即**光照前的地形反照率**）逐帧实测，
`roadCol` 的字节值 = (0.45, 0.45, 0.474)×255 ≈ **(115, 115, 121)**：

| 帧 | 我们 near 带反照率均值 | 前景实际内容 | 判定 |
|---|---|---|---|
| n000 | **(115.9, 116.0, 120.2)** | `roadCol`（115,115,121） | ✅ 与上游一致 |
| n040 | **(175.8, 167.1, 108.4)** | 草地（185,176,106） | ⛔ 路廊没被涂成路面色 |

**根因（已用 `?diag=roadprox` 定位，不是猜）**：`roadProx` 的编码是 `clamp(roadProx*0.25+0.5,0,1)`，
128 = `roadProx == 0.0`。n040 逐点实测：

| 帧 | 前景 roadProx（`?rpmode=signed`） | 前景 roadProx（默认 `far0`） | 前景是**哪一层** |
|---|---|---|---|
| n000 | near 带均值 21.5 ⇒ roadProx ≈ **−1.66** | — | 近景格（正确） |
| n040 | near 带均值 **255** ⇒ roadProx ≥ **+2** | 全帧下半 **恰好 128** ⇒ roadProx **== 0.0** | **远景格（far）** |

⇒ **n040 画面下半部分整个是远景大网格**（`res=1`、`roadProx` 被 `far0` 强制 0 / 在 `signed` 下是巨大正值），
近/中景格只露出一条贴着地平线的窄带（roadProx ≈ −0.4~−0.55）。
⇒ 远景格既进不了 `roadProx < -0.2`（涂 `roadCol`），也进不了 `0 < roadProx < 0.7`（混碎石），
只能落进 `roadProx == 0.0` 的岩石分支 ⇒ **前景必然显示草地/岩石，不可能显示路面灰**。
⇒ 这就是 n040 全帧 SSIM 只有 0.6456（4 帧最低）的**唯一主导原因**，且它是**几何/分层覆盖**问题，
**不是** `roadCol`、`gravelMap`、`roadProx` 语义或任何颜色参数的问题 —— 三者都已逐字核对无误。

**②-c 「草→岩混合」目前是**死的**（实测）**
`D3430.glsl:105` 的 `terrainBlend(texelColor, 0.7, rockTex, steepness, 0.06)` 由 `steepness` 驱动。
`?diag=steepness` / `?diag=rockfrac` 实测：**`steepness` 全帧最大仅 39/255 ≈ 0.15、near 带均值 0.0**；
`rockfrac` **最大仅 5/255 ≈ 0.02、lower-mid 与 near 带均值 0.0**。
⇒ 岩石分支**从不生效**，而黄金帧右侧有明显岩石切坡。
⇒ **调 `steepness`/岩石混合系数不会有任何效果**，因为输入恒为 0。
`steepness` 来自顶点法线：`webgl_proto.js:357-363` 用的是**10 单位中心差分**（远景格口径，
上游近景格是**本格内**差分，`chunk:1676-1731`）⇒ 在 10 单位网格上把坡度平均掉了。
⚠️ 这与 §4.6.3 ③ 表里 `nearres=20&midres=10` 仍为负的结论**并不矛盾**：加密网格只是把同一个
被平滑的坡度算得更细，没改变"路基没有切坡"这个事实。

**③ 逐个证伪的候选（都在 `output/slowroads_parity/` 有产物，SSIM 为 4 帧平均）**：

| 候选 | 开关 | SSIM | 结论 |
|---|---|---|---|
| 基线 A_base（`road=0&veg_*=0`） | — | **0.671123** | 参照 |
| 网格加密 `nearres=20&midres=10`（27,730 → 39,210 tri） | `x_res20` | 0.668844 | ❌ 负 ⇒ 分辨率不是杠杆 |
| 顶点 y 直取高度图（跳过路基 cut/fill） | `?terrhm=1` | 0.671334 | ❌ 噪声级 ⇒ 高度场不是杠杆 |
| 关掉 `displacementMap` | `?nodisp=1` | 0.654859 | ❌ 负 |
| 路面 ribbon | `?road=1` | 0.655378 | ❌ **净负 −0.0157** |
| 植被 | `?veg_grass=1` | 0.642922 | ❌ **净负 −0.0282** |
| 远景格 `roadProx` 写 0 | `?rpmode=far0` | **0.671142** | ✅ 保真修正（见 ④） |

同时用 `?diag=` 把单个 varying 打成灰度逐帧核对（产物 `diag_albedo|diag_steepness|diag_rockfrac|diag_light|diag_heather`）：
- **`steepness` 整帧≈0 ⇒ `rockfrac≈0` ⇒ 岩石分支从不触发**（详细实测见 ②-c）
  ⇒ 差异在**几何/路基**，不在岩石混合系数。
- `treeMask`：上游近景格 `chunk:1643-1651` 里 `treeMask[t]` **从未被填充**（`postProcessRow()` 是空操作）
  ⇒ 我们恒 0 是**保真**的，`?treemask=1` 才是偏离（这也解释了它为什么掉分）。
- `vCurvature` / `vGrass`：**Hills 片元着色器一次都没读** ⇒ 我们 0 vs 上游 `0.02·hAvg` **可证明无视觉影响**。
- 色彩管线：上游 r144 **legacy**（`LinearEncoding` / `NoToneMapping` / `ColorManagement.legacyMode=true`），
  与原型一致 ⇒ 不存在 sRGB / tone map 差异（§2.5 的前提成立）。
- 贴图与 uniform：`map = grass_summer_01.jpg`、`displacementMap = rock_06_bump.jpg`、
  `rockMapBump = null`、`sandMap`/`gravelMap` 由 `loadGroundMaterialSkin` 赋非 null ⇒ 与上游同。

**④ 唯一被采纳的保真修正：远景格 `roadProx = 0`（已设为默认）**
上游远景大网格（`class it`，`chunk:1786-1819`）**只** `setAttribute` 了
`position` / `heightOffset` / `normal` / `curvature` —— **没有 `roadProximity`、没有 `treeMask`、没有 `uv`、没有 `grass`**
⇒ WebGL 通用顶点属性默认 `(0,0,0,1)` ⇒ `roadProx == 0.0` ⇒ 片元走 `roadProx == 0.0` 分支
（岩石，`vWv = wPos/800`）。我们此前对**全部**格子写 `|lat| − ROAD_HALF` ⇒ 与上游语义相反。
⇒ 已改默认 `RPMODE = 'far0'`（`?rpmode=signed` 可退回旧行为做 A/B）。
⚠️ **诚实说明**：它让 SSIM 只从 0.671123 → 0.671142（Δ=+0.000019）、MAE 24.4433 → 24.4431
—— 指标上等于没动。**采纳理由是源码事实，不是分数**（T-2：保真优先于凑分）。

**⑤ 默认值收口（按实测最优，不按"看起来像原版"）**
`ROAD_ON = Q.get('road') === '1'`、`VEG.{grass,bush,tree} = Q.get('veg_*') === '1'`
⇒ 默认 = A_base 形态 + `far0`。路面/植被要开就显式 `?road=1` / `?veg_grass=1`。
（依据即 ③ 表：两者都是净负。⛔ 不要为了"更像游戏"把默认翻回去而不重测。）

**⑥ 顺手修好一个**坏指标**：`parity.mjs` 的轮廓偏差**
旧实现把天空写成**精确匹配 `0xF0FFFF`（容差 2）**，但天空是 `clouds_01b` 贴图 + 雾，
实际像素在 228~255 ⇒ **黄金帧 1280 列里只有 25 列**能测出地平线，其余被 `gs[x] < 0` 跳过
⇒ 报出来的 `silMean = 0.073px` 是「25 列的平均」，**不是吻合**。
已改为**逐帧自测天空参考色**（顶部 6 行里三通道都 ≥200 的亮像素取中位数，容差 24），
并输出 `可测列数`；可测列 < 40% 时报告显式标 `⛔不可用`。自检：黄金帧自比 = SSIM 1.000000、轮廓 0。
⇒ **修正后的真实值：轮廓 mean = 56.17px、max = 220px、可测列 1280/1280**
—— 这才是「地平线/地形形体差几十像素」的真面目，此前被坏指标完全掩盖。

**⑦ 剩余差距（诚实结论）**
- **SSIM 0.671 不能读成"接近"**：黄金帧**自己**换一个 node（n000 vs n040，同 seed 同场景）
  就只有 **SSIM 0.598 / MAE 27.0** ⇒ 我们 0.671 落在"另一张画"的量级里。
  门禁 `golden/meta.json` 的 `ssimMin 0.92 / maeMax 2` 是**上游自比**的噪声底（0.924~0.993），
  与"跨实现对比"不是同一把尺子 ⇒ **在把世界生成器换成真侵蚀高度图 + 真道路链之前，
  0.92 不可能达到**（§1.5 已定此结论，本次只是从"色偏"这个错误方向上排除了 7 个候选）。
- 主导误差是**内容**：黄金帧 n040 下半屏是**柏油路面**（133,143,148），我们是**草地**（205,206,128）
  ⇒ 路面没铺到相机脚下（ribbon 仅 14 段且在地形抬升处被埋）。
- **下一步**（不在本次范围，两条都已定位到具体代码位置）：
  1. **让近/中景 LOD 罩住相机脚下** —— ②-b 已证明 n040 前景整片是**远景格**（`roadProx == 0.0`），
     于是既没有 `roadCol`、也没有碎石带，只剩草地/岩石。这是 `ensureCells()` 的行分配问题
     （`docs/archive/e52-highway-preview.html:1158-1213` 的 `k = 0..5` 近层 / `k = 6..19` 中层），
     **不是**着色器问题，⛔ 不要用调色或调 `roadProx` 语义去凑。
  2. **让 `steepness` 非 0** —— ②-c 已证明它恒 0（`webgl_proto.js:357-363` 用 10 单位中心差分，
     上游近景格是本格内差分 `chunk:1676-1731`）；只有坡度回来了，`D3430.glsl:105` 的草→岩混合
     与 `?diag=rockfrac` 才有意义。
  两项都属于**世界核/路基几何**。做完这两项后再重测 `road=1` 才有意义（现在它是净负，是因为
  路基切坡不存在、ribbon 被埋）。
- **未验证**：无真机访问（本轮全部是 headless WebGL 原型页），
  ⛔ 未跑任何 adb 命令、未改 `app/src/main/java/**`、未改 `app/build.gradle.kts`。

#### 4.6.4 ⭐ layer 诊断 + 门禁口径定案 + n040 原型根因确认（2026-10-30）

> **一句话结论**：n040 屏幕下部 80% 被远景格覆盖是**原型独有缺陷**（CPU 原型的
> `ensureCells()` 构建时机与 WebGL 原型相机位置不同步），**Kotlin 侧无此缺陷**；
> 门禁口径定为**硬 A（世界核逐位）+ 硬 B（自确定性）+ 软 C（相对上游自噪声比值 ≥ 0.95）**。

**① `?diag=layer` 诊断**

新增诊断模式：把 `layerId`（近 0.2 / 中 0.5 / 远 1.0）写进 `roadProximity` 通道，
经 `DIAG_EXPR.layer` 原样输出 ⇒ 灰度 51 / 128 / 255。
`DIAG === 'layer'` 必须优先于 `RPMODE === 'far0' && c.isFar` 判断（否则远景格永远返回 0）。

新增采样器 `scripts/e52/e52_diag_sample.mjs`（零依赖 PNG）：16×9 粗网格 + 8 级直方图 +
精确值计数 + 顶/底 6 行均值。

**② n040 实测（`diag=layer`）**

| 行带 | 覆盖 | 判定 |
|---|---|---|
| y 0–80 | 247–255（天空）→ 51（近景） | 地平线附近过渡 |
| y 80–160 | 250→51 | 近景格在右侧 |
| y 160–240 | 51→255 | 中景格过渡 |
| **y 240–720** | **255（远景格）** | **屏幕下部 80% 全被远景格覆盖** |

⇒ 近景格（51）只出现在右侧窄带，中景格（128）只出现在过渡带，**屏幕下部整片是远景格**。

**③ 根因（原型独有）**

原型的 `collectCells()` 从 `K.activeCells()` 拿近景/中景格，但 CPU 原型的 `ensureCells()`
是用它自己的 `camS` / `view` 构建的，与 WebGL 原型根据 `?node=N` 设的 `camS` 不同步。
`K.setView()` + `K.ensureCells()` 同步尝试未改变输出（CPU cells 在 WebGL 几何构建时已在正确 `camS`）。

**④ Kotlin 侧对照（无此缺陷）**

`HighwayGlScene.kt:717-754` 每帧以相机为中心铺格（`maybeRebuildMesh()`），
近景格用 `glPolygonOffset(-1f, -2f)` 压过远景格 ⇒ 近景格永远在最上层。

**⑤ 门禁口径（所有者 2026-10-30 裁决「按你建议处理」）**

| 门禁 | 判据 | 当前状态 |
|---|---|---|
| **硬 A** | 世界核逐位相等（高度场 800/800、道路 1501/1501、树图 400/400，零容差） | ✅ PASS |
| **硬 B** | 自确定性：同 node 两遍输出完全一致 | ⏳ 待验 |
| **软 C** | 相对上游自噪声比值 = SSIM(实现/上游) / SSIM(上游/上游) ≥ 0.95 | 基线 0.671142 / 上游自比 0.895 ⇒ 比值 ≈ 0.750 |

> ⛔ 软门禁 C 的比值当前 ≈ 0.750，未达 0.95。但上游自比 0.895 说明**跨实现对比下
> 0.92 的绝对值不可能达到**（植被用 `Math.random()`，上游自身两遍也有差异）。
> ⇒ 软门禁 C 的意义是「不比上游自身差异更大」，不是「绝对 SSIM 达到某个值」。

---

## 五、风险清单

| ID | 风险 | 触发条件 | 缓解 | 验证方法 |
|---|---|---|---|---|
| **R-01** ⭐ | **GLES2 vs GLES3 着色器不兼容** | 设备只给 GLES3 上下文 | 统一按 **ES 1.00** 写 ⇒ GLES3 上下文也接受 | 真机 `dumpsys SurfaceFlinger` 看 GLES 版本；创维机上跑一次即验证 |
| **R-02** ⭐ | **顶点纹理单元 = 0** | 低端 GPU | 三档变体 V-A/V-B/V-C 自动选择 | `glGetIntegerv(GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS)`；变体选择记 logcat |
| **R-03** ⭐ | **SurfaceView 打洞被 Compose 背景填死 ⇒ 全黑** | R-07 改动没做或做错 | 条件化背景（R-07）；备选 S2（TextureView） | 真机截图非黑 ⇒ 通过；**E41「只画第一帧」的同类坑，必须真机验** |
| **R-04** | **GL 线程未随 Activity 暂停 ⇒ 后台耗电 / EGL 上下文丢失** | 退到后台 | R-08 + `HighwayGlHolder` | `dumpsys gfxinfo com.nasmusic.tv` 看后台是否还有 GPU 活动；`dumpsys meminfo` 看 EGL 计数 |
| **R-05** | **创维 GPU 弱 ⇒ 填充率不够** | 真机掉帧 | 三级旋钮 + 自适应回退 | `dumpsys gfxinfo` 的 janky frames %；`glGetString(GL_RENDERER)` 归档 |
| **R-06** | **贴图内存超限 ⇒ OOM** | 高档 + 双 skin 预取 | 16 MiB 预算 + RGB 而非 RGBA + 只驻留 2 个 skin | `dumpsys meminfo` 的 native heap 曲线；30 min 不 OOM |
| **R-07** | **`.obj` 解析出错（法线/UV 索引）** | 复杂 obj | 只解析需要的 5 个文件 + 最小解析器 + **法线朝向人工核对一次** | 门禁 G21：顶点/索引数与上游 `BufferGeometry` 一致 |
| **R-08** | **`minSdk 22` 的 EGL 坑**：24 位深度拿不到 | 设备只给 16 位深度 | 自定义 `EGLConfigChooser`：优先 24 位深度，回退 16 位；16 位时靠 `glPolygonOffset` 抗 z-fighting | `glGetIntegerv(GL_DEPTH_BITS)`；路面与地形重叠处的 z-fighting 目视 |
| **R-09** | **色彩管线被"顺手改对"** | 有人加了 sRGB/tone map | §2.5 的硬约束 + G18 逐像素对账 | **G18 是唯一防线** |
| **R-10** | **与现有 27 个 Canvas 主题并存** | 新主题引入黑屏/崩溃影响其它主题 | E52 是**第一个 GL 主题** ⇒ 它的失败不能拖垮其它主题 ⇒ 独立 `GLSurfaceView` + 独立 EGL 上下文 ⇒ 崩溃只影响自己 | 逐个切过 28 个主题，30 min 稳定性 |
| **R-11** ⭐ | **「442,000 三角」是理论值还是实测值未确认** | 预算表建立在错误基数上 | P2 之前用上游 `renderer.info.render.triangles` 实测 600 帧 | 上游浏览器实测 + 归档 |
| **R-12** | **竖屏构图崩**（O5） | 手机竖屏 | §2.7 的 O5-a/b/c 三选一；`uFovMode` uniform 可切 | G12（构图锚点偏差 ≤0.5%） |
| **R-13** | **GPU 崩溃（driver bug）⇒ 全 App 挂** | 弱 GPU + 复杂 shader | GL 线程崩溃不会带崩主线程，但会杀进程 ⇒ 加 `eglException` 兜底 + 首次渲染失败 ⇒ **自动回落 Canvas 渲染器** | 压力测试 30 min；logcat 的 `Highway` tag |
| **R-14** | **`GLSurfaceView` 与 Compose 重组的交互**（主题切换时旧 View 未销毁 ⇒ EGL 上下文泄漏） | 快速左右切主题 | `key(active)` 已保证重建；`onViewDetached` 里 `queueEvent { release() }`（⛔ 不能在主线程直接 `glDelete*`） | 快速连切 50 次主题，`dumpsys meminfo` 的 EGL 计数不增长 |

---

## 六、落地顺序（分阶段可验证）

> **答「先做哪块能最快看到原版观感」**：**P0 → P1 → P2**。P1（地形 + 天空 + 雾）给出"形体"，P2（上游 GLSL + 三灯 PBR + 雾色）给出"材质与光"。
> **这两步做完就有约 85% 的原版观感**；P3（路面 ribbon）再补视觉焦点 ⇒ **约 95%**。
> ⛔ **不要先做 P5（模型/水面）** —— 占 4–6 天，只贡献 5% 观感。

| 阶段 | 内容 | 验收判据（门禁） | 依赖 | 规模 |
|---|---|---|---|---|
| **P0** | **探针 + 骨架**：GL 能力探测（GLES 版本/顶点纹理单元/实例化扩展/深度位）、`GLSurfaceView` 挂载、**全屏渐变 quad**、生命周期（R-08）、`HighwayBridge` | **G01** 探针输出记 logcat 且含 6 项能力；**G02** 主题可进可出 50 次无泄漏；**G03** 真机出图非黑；**G04** 帧率 ≥ 30 fps（纯色 quad） | — | **2–3 天** |
| **P1** | **形体**：投影矩阵（§2.7）、地形 LOD（G-03/G-04）、天空（S-01）、雾（P-02）。片元用**纯色 + 深度雾**（暂不上 GLSL） | **G05** 三角形数落在 §4.2 表；**G06** 与归档参考帧的地平线/路宽锚点偏差 ≤0.5%；**G07** 死 cell 真剔除（前进 600 帧三角数有界）；**G08** 雾 far 剔除边界像素 = 雾色（ΔRGB = 0） | P0 | **5–7 天** |
| **P2** ⭐ | **材质与光**：上游 GLSL 逐行移植（F-01/V-01）+ 45 行 PBR 块（P-01）+ 色彩管线（§2.5） | **G18** ⭐ 与上游归档参考帧 **ΔE ≤ 3**（含雾白区）；**G09** `terrainBlend` 五行逐行等价；**G10** 色彩管线断言（无 sRGB/tone map）；**G11** `dFdx`/`#version` 等 **0 处**（源码扫描门禁） | P1 + **R-11 实测完成** | **5–7 天** |
| **P3** | **路面**：原密度 972 三角 ribbon + 路基 | **G12** 路面 972 三角（近 4×99 + 远 10×9 quad），逐段顶点哈希与归档值一致；**G13** 路面掩膜不落在天空区 | P1（G-02 的 `qe`/`lerpHeight`） | **3–4 天** |
| **P4** | **植被**：instancing + 4 个 billboard 变体 | **G14** 实例数落在 §4.2 表；**G15** 无 `ANGLE_instanced_arrays` 时降级路径产出同画面（截图差 ≤1%） | P1, P3 | **3–4 天** |
| **P5** | **水面 + 模型** | **G16** 水面 `vDepth` 剔除边界无硬边；**G17** `.obj` 顶点数与上游一致 | P2, P3 | **4–6 天** |
| **P6** | **环境集 + 换歌切换（D24）**（对应 Canvas 文档 K10） | **G19** 环境表 = `2 场景 / 8 地形档 / 6 skin / 10 天气` 逐项相等；**G20** `songId` 变 ⇒ 起三阶段雾白过场（不硬切）；**G21** `songId` 不变 ⇒ 600 帧零切换；**G22** 过场中的变化只记 pending；**G23** 目标 = 游标前进（不是 hash） | P2, P4 | **4–5 天** |
| **P7** | **Planet 场景（D25 / K9）** | **G24** **四档** topography 参数与 `main:10518-10580` 逐项相等（含 `flat`）；**G25** 三个 skin 的贴图绑定与上游一致；**G26** 植被三角恒为 0 | P6 | **4–6 天** |
| **P8** | **降级 + 真机调优 + 验收** | **G27** 三档切换无残留无闪黑；**G28** 30 min 无 OOM / 无 EGL 泄漏；**G29** 竖屏构图（O5 定案）；**G30** 与既有 27 套不撞车（所有者判读） | 全部 | **4–6 天** |

**总计 ≈ 34–48 人天。**

---

## 七、门禁用例清单（可写进 `app/src/test/`）

| 用例 | 判据 | 守住什么 |
|---|---|---|
| `HighwayGlCapabilityGateTest` | 探针返回 6 项能力且都有默认值；GLES2 分支的 `variant` 必为 V-A/V-B/V-C 之一 | R-01/R-02 |
| `HighwayShaderLintGateTest` ⭐ | 源码扫描：我们的 GLSL 里 `#version`/`dFdx`/`gl_FragDepth`/`uint`/`<<` **0 处**；`texture2D` 存在；`terrainBlend` 的 5 行逐字等于 `D3426:29-36` | 「零改写」承诺 |
| `HighwayPbrChunkGateTest` | 45 行 PBR 块 vs vendor chunk 摘出的参考实现：随机 1,000 组 (N,V,L) 下 **ΔE ≤ 0.5** | P-01 不跑偏 |
| `HighwayFogGateTest` | 雾因子 = `smoothstep(near, far, depth)`（⛔ **不是**线性 mix），且 `near = 0.1·far` | P-02 |
| `HighwayColorPipelineGateTest` | 源码扫描：无 `pow(…, 1.0/2.2)`、无 `sRGB`、无 `toneMapping`；EGL chooser 不请求 sRGB | §2.5 / R-09 |
| `HighwayEnvCatalogGateTest` ⭐ | 环境表 = `2 场景 / 8 地形档 / 6 skin / 10 天气`，与 `main:2897-3325 / 10518-10780` **逐项相等**；⛔ 自造（沙丘/沙漠）或少移植都红 | D23 / G17 |
| `HighwaySongSwitchGateTest` ⭐ | `songId` 变 ⇒ 起三阶段（淡出→重建→淡入）、不硬切；不变 ⇒ 600 帧零切换；过场中的变化只记 pending；目标 = 游标前进 | D24 / G15 / G16 |
| `HighwayTileStreamGateTest` ⭐ | 前进 600 帧 ⇒ 活跃 cell 数**有界**；⛔ 不得出现 `heightOffset=-5` 式隐藏（上游 `main:14085-14087`） | D10 / G04 |
| `HighwayFogCullGateTest` ⭐ | 雾 far 剔除边界处的像素 **ΔRGB = 0**（= 雾色） | G19 / R-11 |
| `HighwayRoadGateTest` | 路面 972 三角；逐段顶点哈希与归档值一致 | D7 / G6 |
| `HighwayTierGateTest` | 三档的 `res` / 植被数 / renderScale 与 §4.2 表一致 | §4.2 |
| `HighwayObjParseGateTest` | 解析出的顶点/法线/UV/索引数与上游 `BufferGeometry` 一致（基线由脚本从上游 chunk 再生） | R-07 |
| `HighwayGlReleaseGateTest` | `onViewDetached()` 后：所有 `glDeleteTextures`/`glDeleteBuffers` 被调、纹理句柄计数归零 | R-06 / R-14 |
| `HighwayFrameParityGateTest` ⭐ | **拆成两条**：**G18a** 移植误差 = 原生分辨率下 SSIM ≥ 0.92 且 MAE ≤ 2.0（不受 renderScale 影响）；**G18b** 分辨率代价 = 最终输出对账，阈值按 §4.3 基线表取（记录用，非合格判据） | P2 的总验收；⛔ 别把"设计选择"当成"移植缺陷" |
| `VisualizerThemeStringsGateTest`（既有） | `values-en` 缺 key 会红 | R-02 |
| `FxCoverageScanTest`（既有） | 阈值 `>= 26` ⇒ **加了也不红，对 E52 无约束力** | ⚠️ 需人工抬阈值 |

---

## 八、决策（D）与开放项（O）

### 决策

- **D-GL-01** GLES 版本 = **GLES 2.0 为编译基准**（着色器按 ES 1.00 写；GLES3 上下文也跑同一份）。
  ⚠️ **v1.7 修订（P4 实测）**：**植被 program 必须用 ESSL 3.00**（`#version 300 es`）——
  ESSL 1.00 没有 instancing 的**语言**支持（只有 `ANGLE_instanced_arrays` 扩展可用），而创维机**没有** `GL_EXT_instanced_arrays`
  ⇒ 只能走 **ES3 核心 instancing**（`GLES30.glDrawElementsInstanced`）。
  ⇒ 因此采用**混合方言**：地形 / 天空 / 路面 = **ESSL 1.00**（逐字对账不变）；植被 = **ESSL 3.00**（仅把 `varying`→`in|out`、`attribute`→`in`、`texture2D`→`texture`、`gl_FragColor`→`out vec4` 四处机械替换）。
  ⚠️ 同一 program 不能混方言 ⇒ 分开编译即可；ES3 上下文**接受** ES 1.00 源码（实测 `es100_source_in_es3` 编译成功）。
- **D-GL-02** 渲染器走**已有的 View 型旁路**（`isViewBased` + `createView`），**不开特殊分支**、不改 `VisualizerRenderer` 的形状（只加一个默认空实现的 `onFrame` 钩子）。
- **D-GL-03** 音频/歌曲切换经 **`HighwayBridge`**（主线程写、GL 线程读，双缓冲拷贝）；**不接 `PlayerManager` 的 1000 ms 轮询**。
- **D-GL-04** 换歌换环境 = **三阶段雾白过场**（0.5 s → 重建 → 0.5 s），目标**游标前进**，过场中的变化只记 pending；`intervalSec = ∞`。
- **D-GL-05** 色彩管线 = **线性直通**（无 tone map、无 sRGB 编码），雾色 `0xFFFFFF`（D21），雾用 **`smoothstep`**。
- **D-GL-06** 世界坐标用**绝对坐标**，删掉 `modelMatrix`；超过 50,000 单位做一次 rebase（在雾白帧里）。
- **D-GL-07** 投影矩阵：**`68°` 是垂直 FOV**，`aspect = W/H`，`near=0.25`，`far = ue = pe/cos(34°·aspect)`。
- **D-GL-08** ⭐ **不做阴影贴图**（上游的阴影只覆盖车体 8×8 单位，地形不投影 ⇒ 贡献≈0）。
- **D-GL-09** ⭐ **雾 far 主动剔除**（> `uFogFar` 的 cell 不生成不绘制 ⇒ 视觉等价、成本归零）。
- **D-GL-10** PBR 光照 = **逐字摘 three.js r144 的 chunk + 删不可达分支**（45 行），⛔ 不自己重推。
- **D-GL-11** 贴图驻留 = **当前 skin 全量 + 下一 skin 预取**，单 skin ≈ 8 MiB；albedo 用 RGB888。
- **D-GL-12** 降级 = `renderScale`（主）+ `res`（次）+ 贴图尺寸（末）+ **运行时自适应回退**。
- **D-new-01** 编号 `"52"`，显示名「旷野公路」（D19），`Tier.ADV`，`needsParticleBudget = false` ⇒ 三档全可选。
- **D-new-02** 换歌判据用 **`id ?: title`**（本地扫描的歌没有 id ⇒ 否则永远不切）。
- **D-new-03** 世界坐标 rebase 阈值 = 50,000 单位。
- **D-new-04** ⭐ **雾 far 主动剔除**（见 D-GL-09 的实现细则）。
- **D-new-05** ⭐ **渲染分辨率策略（O8 裁决）**：**保逐行移植，不改写着色器**；用 `renderScale`
  + mipmap + 4×MSAA FBO + 硬件缩放放大换帧率。
  **真机实测后修订（2026-10-29）**：PBR 层 @1080p = **87.7 ms/帧（11.4 fps）**（CPU 提交仅 0.52 ms ⇒ 纯 GPU 瓶颈），
  442,000 三角 @1080p = 28.7 ms/帧 ⇒ **瓶颈是每像素 15 次采样，不是三角数**。
  ⇒ 档位 **HIGH 0.40 / MEDIUM 0.35 / LOW 0.30**（自适应可再降）⇒ **实测帧率目标 40–50 fps（不追 60）**。
  ⇒ ⛔ **任何"为了提速而合并/改写上游采样"的提案都需所有者单独批准**（那会破坏"逐行移植"的前提与 G18 对账基线）。
- **D-new-06** **竖屏不做**（O5 裁决）⇒ 只做横屏，`uFovMode` 固定 O5-a（保持水平 FOV = 68°）。

### 开放项（需所有者拍板）

- ~~**O5** 竖屏 FOV 策略~~ ⇒ ✅ **已裁决（2026-10-29）：不考虑竖屏**，本效果与其它效果一样**只做横屏** ⇒ O5 关闭。
  ⇒ §2.7 的 `uFovMode` 不再需要三分支，固定按 **O5-a（保持水平 FOV = 68°）** 实现。
- ~~**O6** 贴图驻留策略~~ ⇒ ✅ **已裁决（2026-10-29）：按建议 A** ——
  允许在**纯白帧内**上传下一 skin 的贴图（视觉上完全不可见）⇒ 常驻 ~16 MiB（而非 27 MiB）。
  ⇒ D24 ④ 的措辞补充说明：D24 的原意是"不许在过场里做**会卡顿的重建**"，白帧内的上传不违背该意图。
- ~~**O7** 玩家座驾是否移植~~ ⇒ ✅ **已裁决（2026-10-29）：先不做**，以后有需要再说。
- ~~**O8** 填充率 vs 逐行移植~~ ⇒ ✅ **已裁决（2026-10-29，两次）**：
  第一次选**方案 α**（保逐行移植 + 降分辨率）；真机实测出「PBR 层 87.7 ms/帧」后复核，**再次确认方案 α** ——
  **接受 40–50 fps，不追求 60 fps**（E52 单独定目标帧率，不与其它主题的 60 fps 齐平）。
  ⇒ ⛔ **不砍每像素采样数** ⇒ **上游 GLSL 的 15 次采样逐行保留** ⇒ "逐行移植着色器"这个前提完整保住。
  实测依据：15 次采样/像素 @1080p 全屏 = **87.2 ms/帧（11.47 fps）**，纹理吞吐 **≈ 357 Mtex/s**
  ⇒ 60 fps 的采样预算 = **5.96 M 次/帧**，我们 1080p 下需要 **20.2 M 次/帧**（超 3.4 倍）。
  ⇒ **不合并采样、不改写着色器**；改用 `renderScale = 0.4~0.5`（+ mipmap + 4×MSAA FBO + 硬件缩放放大）。
  ⇒ 真实损失只有"放大发糊"：地面是**大片贴图**（纹素密度反而高于原版），几何边缘由 MSAA 抗锯齿。
  ⇒ ⛔ **观感偏差必须用黄金帧逐像素对账量化**（G18：SSIM ≥ 0.92），不合格再议改写采样。

---

## 九、当前不做的事

1. ⛔ **不做阴影贴图**（D-GL-08）。
2. ⛔ **不做交通车**（D20）。
3. ⛔ **不做玩家座驾**（待 O7）。
4. ⛔ **不做车辆物理 / 碰撞 / 圈速**。
5. ⛔ **不做后期处理**（上游 `bloom`/`SSAO`/`EffectComposer`/`RenderTarget` **全 0 命中** ⇒ 没有可移植的）。
6. ⛔ **不做深度纹理 / MRT / `gl_FragDepth`**。
7. ⛔ **不移植 three.js**（600 KB JS，WebGL 专用）⇒ 只移植它的**光照 chunk 文本**。
8. ⛔ **不做新 Activity / Manifest 变更**（`GLSurfaceView` 不需要 `uses-feature`；三个 feature 已是 `required="false"`）。
9. ⛔ **不新增 Gradle 依赖** ⇒ 纯 `android.opengl.GLES20` + `javax.microedition.khronos.egl.*`（API 8+ 有）。

---

## 十、遗留的核验项（必须在 P2 之前补齐）

| 项 | 怎么查 | 影响 |
|---|---|---|
| **R-11** 上游实际每帧三角数 | 浏览器里读 `renderer.info.render.triangles`（`main:18990` 同源），跑 600 帧取分布 | §4.2 的预算基数；D-GL-09 的收益量化 |
| **three.js r144 的 `outputEncoding` 默认值** | 读 `2.f5fa8177.chunk.js` 的 `WebGLRenderer` 构造（`:14929-14966`）里 `z` 的默认值 | §2.5 的色彩管线（G18 的前提） |
| **`ut`（= `grass_summer_01.jpg`）作为 `map` 时是否真的乘进 `diffuseColor`** | three.js r144 的 `<map_fragment>` 只在 `USE_MAP` 时生效 ⇒ 材质有 `map` ⇒ 生效；但上游的 `T` 块**替换了** `#include <map_fragment>`（`main:826-837`）⇒ ⛔ **可能根本没乘** | ⭐ **高优先级**：§3.2 F-01 里标注"必须补"的 `uBaseMap` 可能是**多乘一次** ⇒ 画面偏绿。**落地时以 `main:836` 的 `T` 块原文为准** |
| `fadeFiner`（`gi`）在 Hills 链里是否真的被采样 | `D3426:23` 声明了，但 `D3430` 里 **0 次使用** ⇒ 可能只被 Planet 用 | 贴图驻留清单 |
| `roadProx` 的取值域与 `qe` 的输出对应 | CPU 核侧核对 | 路面/路基过渡 |

---

## 十一、版本跟踪

| 版本 | 日期 | 内容 |
|---|---|---|
| **v1.9** | **2026-10-30** | **⭐ layer 诊断 + 门禁口径定案 + n040 原型根因确认**：新增 `?diag=layer` 诊断（把 `layerId`（近 0.2 / 中 0.5 / 远 1.0）写进 `roadProximity` 通道，经 `DIAG_EXPR.layer` 原样输出 ⇒ 灰度 51/128/255）；新增 `scripts/e52/e52_diag_sample.mjs`（零依赖 PNG 采样器：16×9 粗网格 + 8 级直方图 + 精确值计数 + 顶/底 6 行均值）；**n040 根因确认**：屏幕下部 80% 像素 = 远景格（255），近景格（51）只出现在右侧 ⇒ 原型 `collectCells()` 从 `K.activeCells()` 拿近景/中景格，但 CPU 原型的 `ensureCells()` 构建时机与 WebGL 原型相机位置不同步（`K.setView()` + `K.ensureCells()` 同步尝试未改变输出）；**Kotlin 侧无此缺陷**（逐行核对：`HighwayGlScene.kt:717-754` 每帧以相机为中心铺格 + `glPolygonOffset(-1f,-2f)` 压过远景格）；**门禁口径定案**（所有者裁决「按你建议处理」）：硬门禁 A（世界核逐位相等，已 PASS：高度场 800/800、道路 1501/1501、树图 400/400）+ 硬门禁 B（自确定性：同 node 两遍输出一致）+ 软门禁 C（相对上游自噪声比值 ≥ 0.95 = SSIM(实现/上游)/SSIM(上游/上游)，当前基线 SSIM 0.671142 / MAE 24.4431） | 当前 |
| **v1.8** | **2026-10-30** | **⭐ 排除「地形色偏」方向 + 修好一个坏指标**：新增 §4.6.3 —— `e52_color_bias.mjs` 增加 **Δ 分解**（按逐像素 `\|Δlum\| ≤ 12` 分出「同内容」像素），证明中段 ΔR/ΔG **+20.2/+20.0 是内容不符的统计假象**，同内容上的**真实色偏 = −0.1/+0.3/−1.9（near −1.0/−2.3/−4.4）**，且色偏符号逐帧翻转（真色偏不会翻号）；逐项证伪 7 个候选（网格加密 / 高度场 / 关 displacement / 路面 / 植被 / 远景 roadProx / 色彩管线），**唯一采纳的保真修正 = 远景格 `roadProx` 写 0**（上游 `class it` `chunk:1786-1819` 无该属性 ⇒ 通用属性默认 0）并设为默认（SSIM 0.671123 → **0.671142**，指标上等于没动，采纳理由是源码事实）；**默认值收口** `road` / `veg_*` 改 opt-in（实测分别 −0.0157 / −0.0282）；**修好 `parity.mjs` 的轮廓指标**（旧实现精确匹配 `0xF0FFFF` 容差 2 ⇒ 1280 列只有 25 列可测、误报 0.073px；改为逐帧自测天空参考色 + 输出可测列数）⇒ 真实轮廓偏差 **mean 56.17px / max 220px / 1280 列全可测**；新增诊断脚本 `pixprobe.mjs`、`?diag=` 灰度导出；**追加三项实测定位**：① ⛔ **「sky = y 0–180」是误命名** —— 黄金帧 n140 该带均值 (200.7,210.1,**159.5**) 是地形/植被（山丘压顶），⇒ 新增**真天空掩膜**（`B ≥ 180 && B ≥ G−8 && G ≥ R−4`，按**带内占比 ≥50%** 判可用性）⇒ **真天空色偏 = ΔR −2.3 / ΔG −1.6 / ΔB −1.0**（n000+n090；n040 带内黄金帧仅 46% 天空、n140 交集 0 像素 ⇒ 剔除）；② **n040 前景"黄绿 vs 中性灰"的机制已定位** —— 上游 `D3430.glsl:57-61` 在 `roadProx < −0.2` 时把**地形本身**涂成 `roadCol`（`D3426.glsl:38` = `(0.45,0.45,0.474)` 中性灰，`?diag=albedo` 实测字节 (115,115,121)；我们 n000 = 115.9/116.0/120.2 ✅、n040 = 175.8/167.1/108.4 ✗），根因是 **n040 画面下半部分整片是远景格**（`?diag=roadprox` 实测恰好 128 = `roadProx == 0.0`；`?rpmode=signed` 下为 255 = ≥+2）⇒ 远景格进不了 `roadCol` 分支 ⇒ **几何/分层覆盖问题，不是颜色问题**；③ **「草→岩混合」实测是死的** —— `?diag=steepness` 全帧最大 39/255≈0.15、near 均值 0.0，`?diag=rockfrac` 最大 5/255≈0.02 ⇒ 岩石分支从不触发；`steepness` 由 `webgl_proto.js:357-363` 的 **10 单位中心差分**法线算出（上游近景格是本格内差分 `chunk:1676-1731`）⇒ 坡度被平均掉，**调混合系数无效** | |
| **v1.7** | **2026-10-30** | **Kotlin 侧落地 + 5 处上游取值更正 + 对账卡点**：新增 §4.6（P0/世界核/P1/P3/P4 的门禁与产物）与 §4.7（对账工具链量的是 WebGL 原型页而非 Kotlin 场景 → 真机出图才能对号）；**D-GL-01 修订**（植被 program 必须 ESSL 3.00；地形/天空/路面仍 ESSL 1.00）；更正 far=`pe`=1400 / 雾色 `0xF0FFFF` / 雾 near=700 / `skyColFar` 笔误 / 相机欧拉顺序必须 YXZ；否决「相机地形碰撞修正」（抓包证实偏离上游）；记录 3 个新上游事实（**LOD 0 格子无树 → 沿路 86% 路段上游也无树**；`stitchNormals` 依赖 JS 数组扩容；路面材质是 `FrontSide`） | |
| **v1.6** | **2026-10-29** | **O8 复核 + 两条真机专属坑 + 覆盖层遮蔽**：O8 **二次裁决**（真机 PBR 层 87.7 ms/帧出来后再确认方案 α）⇒ **接受 40–50 fps，不追 60 fps**，⛔ 不砍每像素采样数 ⇒ "逐行移植着色器"前提完整保住；D-new-05 按实测修订（档位 HIGH 0.40 / MEDIUM 0.35 / LOW 0.30，**目标帧率 40–50 fps**）；新增 §4.4 两条坑：**① PowerVR shader 编译 ~5 秒 ⇒ 变体必须在加载期预热**（新增门禁 G31：进主题到首帧 < 1.5 s）、**② E51 覆盖层会遮蔽仓库同名文件 ⇒ 改 `VisualizerRendererFactory.kt`/`VisualizerStage.kt`/`RenderContext.kt` 必须同时改两处**；另：`HighwayRenderer` 已接线进工厂（`when` 分支 + `BuildConfig.HIGHWAY_ENABLED` 回落| |
| **v1.5** | **2026-10-29** | **⭐⭐ WebGL 原型 M1 的结论（范围变更 + 5 处事实更正）**：新增 §1.5 —— 上游 GLSL + 上游贴图 + three.js r144 + 线性色彩管线**已跑通**（可复现性 4/4 逐位相同）；但 **G18a 的移植误差 = −0.2192**，根因是**世界生成器未移植**（我们的地形高度场是 4 八度 fbm 替身、道路是航向正弦替身；上游是**侵蚀高度图** `main:2184-2770` + **PRNG 节点链** `main:13440-13550`）⇒ ⭐ **CPU 核移植范围从「6 个纯函数」扩到「世界生成器」**，P1 的前置随之改变，门禁要加「同 seed 同 node ⇒ 高度场/道路几何逐位相等」。**更正 5 处上游取值**：雾色 **`0xF0FFFF`**（⛔ D21「纯白」是 Canvas 路线自选值）、雾 near = **700**、`rockMap` = **`rock_06.jpg`**、`rockMapBump` = **null 绑黑纹理**、天空是**停在世界原点的全屏 quad**（平移 (0,200,0)、scale 2·ue）、上游相机 = **`Mo[0] Chase`**（range 3.5 / pitch 0.3| |
| **v1.4** | **2026-10-29** | **分辨率代价基线实测 + 门禁拆分**：新增 §4.3 —— 把 16 张黄金帧按各 `renderScale` 降分辨率再放大回来对账（**纯放大糊**的下界）：0.75 → SSIM 0.9528 / 0.5 → 0.9369 / **0.4 → 0.9152** / 0.25 → 0.8724；⇒ **G18 拆成 G18a（原生分辨率、隔离移植误差、SSIM ≥ 0.92）与 G18b（最终输出、记录分辨率代价）** —— 0.92 是"同分辨率下上游自比的噪声底"，用在不同分辨率上是错的；人工判读：**全屏观看距离差异小**，真实损失集中在**路面/岩石细颗粒纹理**与**高对比边缘**；**方案 α 维持**，0.4 档的 60 fps 仍待 P2 真机实| |
| **v1.3** | **2026-10-29** | **填充率实测 + O8 裁决**：§四 4.3 按实测（87.2 ms @1080p / 357 Mtex/s）给出**本机 renderScale 档位表**（HIGH 0.40 / MEDIUM 0.35 / LOW 0.30，自适应可再降）与**实现方式**（`setFixedSize` 让 Surface 本身缩小 + 合成器硬件缩放，不自己 FBO→blit）；新增 **D-new-05（保逐行移植，不改写着色器；任何改写采样需单独批准）**、**D-new-06（竖屏不做）**；**O8 关闭**（所有者裁决方案 α| |
| **v1.2** | **2026-10-29** | **真机吞吐实测回填 + 所有者三项裁决**：§1.2.1 补**填充率实测**（15 采样/像素 @1080p = **87.2 ms / 11.47 fps**，纹理吞吐 **≈ 357 Mtex/s** ⇒ **填充率是硬墙**，几何不是）+ **instancing 走 ES3 核心可用**（5,000 实例 63 fps）；§四 4.1 按实测重算 ⇒ **本机 `renderScale ≤ 0.5` 才到 60 fps** ⇒ 新增开放项 **O8（保逐行移植 vs 保分辨率）**；§八 记录所有者裁决：**O5 关闭（只做横屏）**、**O6 = 建议 A（白帧内上传下一 skin，常驻 16 MiB）**、**O7 座驾先不做**；另修 `selectable()` 的双变体过滤（`BuildConfig.HIGHWAY_ENABLED`，照 E51 的 `LOFI_CITY_ENABLED` 模式）+ 接线（枚举 `"52"` / 双语字符串 / 舞台背景条件化 + View 路径 `songId` 通路| |
| **v1.1** | **2026-10-29** | **真机实测回填 + D-GL-01 改判**（新增 §1.2.1）：探针 APK 实测创维 9R54_G8S = **PowerVR Rogue G6110 / OpenGL ES 3.1**；**上游全量 442,000 三角 @1080p 跑 39.4 fps**（GPU 18.05 ms）⇒ **几何不是瓶颈**，本方案 1–2 万三角余量巨大；`GL_MAX_TEXTURE_SIZE 8192` / `MAX_TEXTURE_IMAGE_UNITS 16` / `MAX_VERTEX_ATTRIBS 16` / FBO 1920×1080 complete / `GL_OES_mapbuffer` ✅；**ESSL 1.00 源码在 ES3 上下文编译成功 ⇒ 上游 GLSL 零改写可用**；⚠️ `GL_EXT_instanced_arrays` **不在扩展表**且 `clientVersion=2` 也返回 3.1 ⇒ **拿不到 ES2 上下文、instancing 只能走 ES3 核心** ⇒ **D-GL-01 改判**（着色器仍 ES 1.00 零改写，上下文优先 GLES 3.0）；⚠️ 填充率压测首轮因探针 shader 误用 ESSL 3.00 浮点后缀（`1.0f`）而编译失败 ⇒ 该行作废、重测中；另修正上游引用：`medRes/nearRes` 缺省为 **`10/20`**（`Q[2]`，非 8/16）、路基融合在 `:1508`、`ue` 的 aspect 取 `window.screen.availWidth/availHeight| |
| **v1.0** | **2026-10-29** | **初版**：路线改为原生 OpenGL ES（所有者裁决「原生 GLES 不在红线内」）；补创维真机实测（**PowerVR Rogue G6110 / GLES 3.1**）；Canvas 2D 路线定性封死（逐像素着色的表达上限）；D-GL-01~12 + D-new-01~04；移植清单与 P0–P8 阶段；门禁 G01–G30 |

> ⛔ **E52 移植件与 E51 同纪律**：⛔ 不 push / 不 tag / 不发 release。
> ✅ **Canvas 2D 路线已归档**：`docs/archive/e52-slow-roads-canvas-plan.md` → `docs/archive/e52-slow-roads-canvas-plan.md`（2026-10-31，第十七轮）。原文档 §八 的 O5/O6/O7 均已在本方案 §八 裁决关闭，无挂起项。
