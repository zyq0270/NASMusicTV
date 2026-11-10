# E55「夜城天际」移植开发方案

> ⚠️ **已归档**（2026-11-08，第二十二轮）— 所有者裁决「算了，这个不做了，不好看」⇒ 方案永远不再维护，仅作历史追溯。
> 原路径：`docs/e55-cityscape-port-plan.md` → `docs/archive/e55-cityscape-port-plan.md`
> 上游（MIT）：`@marianmeres/cityscape`，Copyright © 2026 Marian Meres。
> ⭐ **放弃原因是视觉效果**（「不好看」），非可行性/成本/架构问题。可行性结论、架构映射、工作量估算（3–4 人日 5 阶段）仍具参考价值 —— 但⚠️ **本文件占用的 E55 编号已被释放**：`docs/archive/tendrils-visualizer-plan.md`（2026-11-09 亦归档）此前也已建议使用 `"55"` 编号。
> ⚠️ **2026-11-09 追记**：所有者选择方案 A 再弃 E55 藤蔓（GPGPU 路线被实测证伪，见该文头部及附录 A）⇒ **E55 编号现无任何主题归属**。今后不要再用 E55 指代 night-city-skyline，也不要指代藤蔓。
> ⭐ **2026-11-10 追记（编号已定）**：**E55 现归「街机合集」**（`VisualizerTheme.ARCADE_GAMES` 的
> `ordinalLabel` = `"55"`，2026-11-10 所有者按代码现实定案）⇒ 本文的「夜城天际」与藤蔓**都不是 E55**，
> 上文「E55 编号无归属」是 2026-11-09 的历史状态。

> **版本**: v1.0 · 2026-11-08 初版（归档版未修订正文）
> **上游**: `D:\hxzhang\MyGithubSoftware\NasAudio\cityscape-master`（`@marianmeres/cityscape`，作者 Marian Meres）
> **上游许可**: **MIT**（LICENSE 文件确认 Copyright © 2026 Marian Meres）⇒ 代码可合法移植并公开发布，仅需保留版权与许可声明
> **下游**: NASMusicTV `com.nasmusic.tv.visualizer`，新增 `VisualizerTheme` 枚举值 + `RendererFx` 子类
> **关联文档**: `docs/visualizer-effects-list.md`、`AGENTS.md`、`/memory/workflows/doc-skill-metadata.md`

---

## 〇、可行性结论（先看这里）

**结论：可行。** cityscape 是一个纯前端（Deno/TypeScript）的确定性视差夜景引擎，架构上是「DOM-free 模拟核心 + 可替换渲染器」的分层设计，与 NASMusicTV 的「`VisualizerRenderer` 可插拔渲染器 + `AudioFrame` 音频输入」契约天然对齐。

| 维度 | 评估 |
|---|---|
| 许可 | ✅ MIT，可公开移植，仅需保留版权声明 |
| 架构对齐 | ✅ 模拟核心（mood/palette/buildings/camera）纯逻辑无依赖，渲染层接受 `DrawCommand` 列表 ⇒ 等价替换为 NASMusicTV 的 `DrawScope` + `RendererFx` |
| 渲染技术 | ✅ 纯 Canvas 2D（API 22 兼容），无 WebGL / shader / RenderEffect 依赖 ⇒ 走 Canvas 路径（非 View 型），与 27/28 套既有渲染器相同 |
| 性能风险 | ⚠️ 中等。城市天际线每帧建筑数量可能上百，需用**视口裁剪 + 对象池**控制 SIM 规模；window-grid 用预烘焙位图避免逐窗户 Path 分配 |
| 复杂度 | ⭐⭐⭐ 中高（3-4 人日）。核心数学可参考但需 Kotlin 重写（非 TS→JS 桥接），视觉层是全新的 Canvas 绘制代码 |
| 创新点 | 把「静态氛围动画」改造为「音频驱动的频谱效果」——建筑高度 / 窗户亮度 / 雾气浓度 / 相机速度全部映射 `AudioFrame` 通道 |

---

## 一、上游源码分析摘要

### 1.1 项目定位

cityscape 是作者用 Claude Code 生成的**研究性项目**（README 自述），核心卖点不是「漂亮的夜景」而是架构——一个 **renderer-agnostic 的确定性场景模拟器**：

```
┌─────────────────────────┐     ┌──────────────────────┐
│ engine/  (通用核心)      │     │ render/ (渲染器)      │
│  math:  rng/noise/ease  │     │  canvas / ascii      │
│        + color          │     │        /pixelart     │
│  scene: camera/world    │     └──────────▲───────────┘
│        /layer/entity    │                │
│  render: draw-command   │   DisplayList   │
│          /renderer seam │   (DrawCommand) │
│  config: schema-driven  │                │
└──────────┬──────────────┘                │
           │ 同一份 DisplayList → 三种渲染器 │
           ▼                                │
├─────────────────────────┐                │
│ cityscape/ (城市领域)    │                │
│  config/palette/mood     │                │
│  buildings/ (kinds,      │                │
│    building, window-grid)│                │
│  sky/ (backdrop,starfield│                │
│    ,moon,cloud,bird,     │                │
│    flyer,water,shore)    │                │
│  generation/ (biome,     │                │
│    district,spawner,     │                │
│    skyline)              │                │
└─────────────────────────┘
```

### 1.2 关键模块与移植映射

| cityscape TS 模块 | 功能 | Kotlin 移植目标 |
|---|---|---|
| `engine/math/rng.ts` | `createRng(seed)` · `normalizeSeed` · `fork(name)` — 确定性种子 RNG | `CityscapeRng`（`LinearCongruentialGenerator` 或 xorshift，零分配接口） |
| `engine/math/noise.ts` | `createNoise1D(seed, octaves)` — 值噪声用于 mood wander | `CityscapeNoise1D`（简化版，1-2 octaves 足够） |
| `engine/math/ease.ts` | `clamp` `lerp` `smoothstep` `cosinePulse` | ⚠️ **复用** `NASMusicTV.visualizer.VisualizerMath` 已有的同名函数 |
| `engine/math/color.ts` | `Color` struct · `fromHex` `mix` `darken` `lighten` `withAlpha` | ⚠️ 复用 `androidx.compose.ui.graphics.Color`（`Color(rgb)` / `lerp` / `darken` / `copy(alpha=)`） |
| `engine/scene/camera.ts` | `Camera` — 水平滚动 + 缩放 + 垂直偏移 + sway | `CityscapeCamera`（滚动方向 ↔ `frame.timeMs` 驱动） |
| `engine/scene/world.ts` | `World` — layer 容器 + `updateEntities` + `collect` | `CityscapeWorld`（持有 layers + 每帧遍历） |
| `engine/scene/layer.ts` | `Layer` — depth band + spawner 代理 + entities 数组 | `CityscapeLayer`（对象池：`Array<Building?>` 预分配 + 回收） |
| `engine/render/draw-command.ts` | `DrawCommand` union（rect/polygon/circle/gradient/line/glow/text）+ `DisplayListBuilder` | ⚠️ **不移植**——直接调用 `DrawScope` primitive，不需要中间 DSL |
| `cityscape/palette.ts` | 4 套调色板（navy/vaporwave/ink/dawn），每套 warm+cool 两端 | `CityscapePalettes`（`data class` + `enum`，无外部依赖） |
| `cityscape/mood.ts` | `MoodEngine` — 单相位时钟驱动全部颜色插值 | `CityscapeMood`（`update(timeMs, config)` → 填充 `CityscapeMoodSnapshot`） |
| `cityscape/buildings/building.ts` | 建筑实体（kind/bounds/window-grid/flash） | `CityscapeBuilding` |
| `cityscape/buildings/window-grid.ts` | 窗户网格生成 + 逐窗亮灭 | ⚠️ **预烘焙**——不逐帧遍历窗户，改为烘焙 `Bitmap` 缓存 |
| `cityscape/sky/*.ts` | backdrop/starfield/moon/cloud/bird/flyer/water/shore/fog | `CityscapeSky*` 系列类 |
| `cityscape/generation/skyline.ts` | 组装 layers + spawners | `CityscapeSkyline`（`build(world, config, rng)` 工厂） |
| `cityscape/generation/spawner.ts` | `LayerSpawner` — 视口外回收 + 入口补生 | `CityscapeSpawner`（每 layer 一个） |
| `cityscape/generation/district.ts` | 区域状态机（downtown→midrise→houses→factories→parks） | `CityscapeDistrict`（**可选**，Phase 2） |
| `cityscape/config.ts` | `CONFIG_SCHEMA` + `DEFAULT_CONFIG` + `normalizeConfig` | `CityscapeConfig` + 常量表 |

### 1.3 不移植的部分

| 不移植 | 原因 |
|---|---|
| `engine/render/pixel/`（Bayer dither / median-cut / Oklab LUT） | 过于复杂，NASMusicTV 不需要 pixel-art 渲染器 |
| `render/ascii/` `render/pixelart/` | NASMusicTV 只走 Canvas 路径 |
| `render/shared/draw2d.ts`（TS 版 Canvas2D 栅格器） | Kotlin 层直接用 `DrawScope`，无中间层 |
| `runtime/` `audio/` `ui/`（DOM 代码） | NASMusicTV 有自己的 `VisualizerStage` / `RendererFx` / `RenderContext` |
| `naturescape/` 领域 | 完全不同的视觉风格，单独立项 |
| `engine/config/schema.ts`（TS schema 反射） | Kotlin 不需要 schema 反射，静态定义即可 |

---

## 二、E55 效果设计

### 2.1 主题命名

| 字段 | 值 |
|---|---|
| 枚举键 | `NIGHT_CITY_SKYLINE`（清晰描述主题，避免与 E51「像素城市」LOFI_CITY 撞名） |
| 中文显示名 | `夜城天际` |
| 英文显示名 | `Night City Skyline` |
| `ordinalLabel` | `"55"` |
| tier | `ADV` |
| `needsParticleBudget` | `false`（非粒子系效果） |
| 字符串键 | `visualizer_theme_night_city_skyline`（zh + en 双语） |

### 2.2 视觉构成（自上而下绘制顺序）

```
Frame 0 (远)                                    Frame N (近)
┌─────────────────────────────────────────────┐
│ ① 天空渐变 (skyTop → skyMid → skyBottom)     │
│   mood 驱动：warmth + darkness 插值           │
├─────────────────────────────────────────────┤
│ ② 星场 (starfield)         treble → 亮度     │
│ ③ 月亮 (moon disc + halo)   beat → flare     │
│ ④ 云层 (clouds drift)       —                │
├─────────────────────────────────────────────┤
│ ⑤ 远景建筑 (depth 0.1-0.3) bass→雾色         │
│ ⑥ 中景建筑 (depth 0.3-0.6)                  │
│ ⑦ 近景建筑 (depth 0.6-1.0)                  │
│   每层：silhouette rect + window-grid        │
│   音频：spectrum band → 窗户亮度; bass → 建筑高度调制 │
├─────────────────────────────────────────────┤
│ ⑧ 地平线光污染 glow band    beat → 脉冲      │
├─────────────────────────────────────────────┤
│ ⑨ 水面 (water reflection)  sectionEnergy → 波浪幅度 │
│ ⑩ 岸线 (shore band)                       │
│ ⑪ 雾 (fog at horizon)          energy → 浓度 │
└─────────────────────────────────────────────┘
```

### 2.3 音频映射（核心：把氛围动画变频谱效果）

cityscape 原始是**纯静态氛围动画**（无音频输入），E55 的关键改造是让每个视觉层响应 `AudioFrame`：

| 视觉层 | AudioFrame 通道 | 映射方式 |
|---|---|---|
| 建筑高度 | `spectrum[lowBands]` + `bassRaw` | 每层建筑的 `baseHeight` 乘以 `1 + bassRaw * 0.3`（低频越强楼越高） |
| 窗户亮度 | `spectrum[barIndex]` | 按 spectrum bar 索引分带，带内窗户亮度 = `spec[band] * windowLightStrength` |
| 窗户闪烁 | `frame.beat` | beat 时随机点亮一波窗户（strong burst），衰减至正常 |
| 月亮 flare | `frame.beat` | beat 时月亮 halo 半径 ×1.5 + 亮度 ×1.3，衰减回落 |
| 星场 | `frame.treble` | treble 越高星星越亮（`starAlpha = lerp(0.3, 1.0, treble)`） |
| 天际线 glow | `frame.beat` + `frame.energy` | glow alpha = `0.3 + energy * 0.4 + beat * 0.3` |
| 水面反射 | `frame.sectionEnergy` | 反射波纹幅度 = `sectionEnergy * waveAmplitude` |
| 雾浓度 | `frame.energy` | 雾 alpha = `0.1 + energy * 0.25` |
| 相机滚动速度 | `frame.sectionEnergy` | `baseCameraSpeed * (0.5 + sectionEnergy * 1.0)`——节拍驱动推进感 |
| 天空色温 | `frame.bass` × `frame.sectionEnergy` | bass 强时偏 warm，安静时偏 cool |

### 2.4 Mood 系统（保留 cityscape 的核心美学）

保留 `MoodEngine` 的「单相位时钟驱动全套颜色」设计：
- `moodCycleSeconds`（默认 90s）：warm ↔ cool 的呼吸周期
- `darkness`（默认 0.42）：整体亮度
- `colorTemperature`（默认 0.5）：暖↔冷偏置
- 4 套调色板（navy/vaporwave/ink/dawn），每套 warm+cool 两端

**音频驱动 mood**：`warmth` 偏置叠加 `frame.bass * 0.3`——低音越重越偏暖色（nightclub 感），安静时偏冷色（月夜感）。

### 2.5 视觉效果参数（参考 cityscape DEFAULT_CONFIG）

| 参数 | cityscape 默认 | E55 建议 | 说明 |
|---|---|---|---|
| `parallaxLayers` | 4 | 3（默认） | TV 端减少一层降低 SIM 量 |
| `spawnDensity` | 0.45 | 0.35 | 建筑间距稍大，性能更安全 |
| `cameraSpeed` | 20 px/s | `0.08 * height / s` | 以屏高为单位（cityscape 单位制） |
| `waterLevel` | 0.33 | 0.30 | 水面略低，给建筑更多展示区 |
| `moodCycleSeconds` | 90 | 120 | 拉长呼吸周期，更适合品味音乐 |
| `darkness` | 0.42 | 0.55 | 电视端偏暗更护眼 |
| `windowLightChance` | 0.08 | 0.12 | 更多窗户点亮，频谱反馈更强 |
| `starDensity` | 0.85 | 0.7 | 减少星点数量，降低绘制负载 |
| `cloudChance` | 0.8 | 0.6 | 云层稀疏些 |

---

## 三、架构设计

### 3.1 文件结构

```
app/src/main/java/com/nasmusic/tv/visualizer/
├── nightcity/                          ← 新增包（cityscape 移植层）
│   ├── CityscapeConfig.kt              ← 配置 data class + 常量
│   ├── CityscapePalettes.kt            ← 4 套调色板 (navy/vaporwave/ink/dawn)
│   ├── CityscapeMood.kt                ← MoodEngine + MoodSnapshot
│   ├── CityscapeRng.kt                 ← 确定性种子 RNG (简化移植)
│   ├── CityscapeNoise1D.kt             ← 值噪声 (简化移植)
│   ├── CityscapeCamera.kt              ← Camera: 滚动/缩放/偏移
│   ├── CityscapeBuilding.kt            ← Building 实体
│   ├── CityscapeWindowGrid.kt          ← 窗户网格生成器 (烘焙用)
│   ├── CityscapeLayer.kt               ← Layer: depth + object pool
│   ├── CityscapeWorld.kt               ← World: layers 容器 + update/collect
│   ├── CityscapeSkyBackdrop.kt         ← 天空渐变
│   ├── CityscapeStarfield.kt           ← 星场
│   ├── CityscapeMoon.kt                ← 月亮 + halo
│   ├── CityscapeClouds.kt              ← 云层
│   ├── CityscapeWater.kt               ← 水面反射
│   ├── CityscapeShore.kt               ← 岸线
│   ├── CityscapeFog.kt                 ← 雾
│   ├── CityscapeSpawner.kt             ← 视口外回收 + 补生
│   ├── CityscapeSkyline.kt             ← 组装入口
│   ├── CityscapeBitmapCache.kt         ← 预烘焙位图缓存 (windows/stars/glow)
│   └── NightCityRenderer.kt            ← RendererFx 子类 (入口 + drawContent)
├── VisualizerRendererFactory.kt        ← 改: +1 个 when-branch
├── VisualizerTheme.kt (在 AppSettings.kt) ← 改: +1 个枚举值
└── VisualizerMath.kt / Easing.kt        ← 不改 (已有等价函数)
```

> ⚠️ **不新建 `DisplayList` 抽象层**：cityscape 的 `DrawCommand` union 是为多渲染器（Canvas/ASCII/PixelArt）服务的。NASMusicTV 只需要 Canvas 路径，直接在 `DrawScope` 上画即可，省一层 DSL 降低复杂度与分配。

### 3.2 NightCityRenderer 生命周期

```
onEnterContent(ctx):
  1. 创建 CityscapeRng(seed = 固定值或 songId hash)
  2. 创建 CityscapePalettes + CityscapeMood
  3. CityscapeSkyline.build(world, config, rng)  → 初始建筑
  4. CityscapeBitmapCache.ensure(size, dpi)    → 烘焙窗户网格位图

drawContent(frame, ctx, fx):   ← 每帧调用，零分配约束
  1. citiesMood.update(frame.timeMs, config, audioOverrides)
  2. camera.step(dtMs, baseSpeed * audioModulatedSpeed)
  3. spawner.sync(camera, width, env)            ← 视口外回收
  4. world.updateEntities(dt, time, w, h, env)
  5. world.collect(w, h) → draw Sequentially:
      a. drawSkyGradient (mood.sky stops)
      b. drawStarfield (cached bitmap or dots)
      c. drawMoon (circle + halo, beat flare)
      d. drawClouds
      e. for each layer (far→near):
         drawBuildingSilhouette + drawWindowBitmap
         ← 窗户亮度按 spectrum band 调制
      f. drawHorizonGlow (beat pulse)
      g. drawWater (sectionEnergy waves)
      h. drawShore
      i. drawFog (energy alpha)

onExitContent():
  1. releaseResources() → 回收位图
```

### 3.3 关键性能策略

| 策略 | 说明 |
|---|---|
| **视口裁剪** | spawner 只保留 `camera.x ± viewWidth × 1.2` 范围内的建筑，超出即回收 |
| **建筑对象池** | `Array<Building>(maxPerLayer * maxLayers)` 预分配，spawn 时从 free-list 取，回收时归还 |
| **窗户网格烘焙** | 每个 `windowKind`（如「办公楼 8×12」）烘焙一张 `Bitmap`，建筑实例只存 `windowKind` + 亮度系数，绘制时 `drawImage(bitmap, x, y, w, h, alpha)`——避免逐窗 `drawRect` |
| **天空/星场烘焙** | 星场位置随机但确定（seed 固定），烘焙一张低分辨率位图（如 512×192），每帧 `drawImage` + 平移 + BlendMode.Plus |
| **mood 零分配** | `CityscapeMoodSnapshot` 持有 `Color` 数组，`update()` 只写不创建 |
| **camera.sway 单值** | 相机 sway 用单个 `Float` 字段（非 x/y 两个），减少 math |
| **FxFrame level 分级** | 沿用 `RendererFx` 的 `FxLevel` 概念：LITE 模式跳过云层/星场（节省 30% 绘制量） |

### 3.4 FxCoverageScanTest 注册

新渲染器 `NightCityRenderer` 必须加入 `FxCoverageScanTest` 的 `covered` 列表（因为它有 `postFx` + `OverlayFx` 调用），typo-level 断言数量从 ≥27 → ≥28。

---

## 四、开发阶段

### Phase 1 — 核心数学与领域层（1 人日）

| 任务 | 验收 |
|---|---|
| `CityscapeRng` — 移植 `createRng(seed) + fork(name)` 接口 | 单元测试：同 seed 同序列 |
| `CityscapeNoise1D` — 简化值噪声（1-2 octaves） | 单元测试：输出范围 [0,1]，确定性 |
| `CityscapeConfig` — 配置 data class + 常量表 | 编译通过 |
| `CityscapePalettes` — 4 套调色板 data class | 编译通过，色值与上游一致 |
| `CityscapeMood` — MoodEngine + MoodSnapshot | 单元测试：warmth 0→1 循环，颜色插值正确 |
| 复用现有 `VisualizerMath` / `Easing` 中已有函数 | grep确认无遗漏 |

**产出**: 纯 Kotlin 领域层，无 Android 依赖，可单测。

### Phase 2 — 相机与场景组装（1 人日）

| 任务 | 验收 |
|---|---|
| `CityscapeCamera` — 水平滚动 + 缩放 + 垂直偏移 + sway | 单元测试：project 函数正确性 |
| `CityscapeLayer` — depth band + 对象池 | 单元测试：pool 容量不越界 |
| `CityscapeBuilding` — 建筑实体（bounds/kind/windowKind/flash） | 单元测试：bounds 计算 |
| `CityscapeWindowGrid` — 窗户网格生成器 | 单元测试：格子坐标计算 |
| `CityscapeSpawner` — 视口外回收 + 补生 | 单元测试：spawn 数量在范围内 |
| `CityscapeWorld` — layers 容器 + update + collect | 集成测试：1000 帧不崩溃 |
| `CityscapeSkyline` — 组装入口 | 编译通过 |

**产出**: SIM 层完整，可 headless 跑（无渲染）1000 帧验证稳定性。

### Phase 3 — 渲染层（1.5 人日）

| 任务 | 验收 |
|---|---|
| `CityscapeBitmapCache` — 预烘焙窗户网格/星场位图 | 编译通过 |
| `CityscapeSkyBackdrop` — 天空渐变 | drawContent 画一帧，存档截图 |
| `CityscapeStarfield` — 星场 | 同上 |
| `cityscapeMoon` — 月亮 + halo | 同上 |
| `CityscapeClouds` — 云层 | 同上 |
| `CityscapeWater` — 水面反射 + 波纹 | 同上 |
| `CityscapeShore` — 岸线 | 同上 |
| `CityscapeFog` — 雾 | 同上 |
| `NightCityRenderer` — 主渲染器 | 视频 2min 无崩溃 |

**产出**: Canvas 渲染层，可在手机 preview 中查看静态帧。

### Phase 4 — 音频映射 + 注册（0.5 人日）

| 任务 | 验收 |
|---|---|
| 音频映射表实现（§2.3 表格全部接入） | 播放音乐，肉眼观察各层响应 |
| `VisualizerTheme` 枚举 + `ordinalLabel "55"` | `VisualizerThemeTest` 更新（28→29） |
| `visualizer_theme_night_city_skyline` 双语字符串 | `VisualizerThemeStringsGateTest` 通过 |
| `VisualizerRendererFactory` when-branch | 编译通过 |
| `FxCoverageScanTest` covered + 数量断言 | 测试通过 |
| `visualizer-effects-list.md` + E55 行 | 更新 |
| CHANGELOG.md 新条目 | 一行 |

**产出**: 完整可切换效果，所有门控绿灯。

### Phase 5 — 真机验证与调参（0.5 人日）

| 任务 | 验收 |
|---|---|
| 创维 9R54_G8S 真机跑通（业主操作） | 业主反馈 |
| 性能调参：建筑密度 / 窗户数量 / 星场密度 | FpsMeter 稳定帧率 |
| `--rerun-tasks` 全量单测 | 全绿 |
| lintDebug | 无新 error |

**产出**: 满足发布条件。

### 隐含并行性

- Phase 1 与 Phase 3 的「渲染层」在接口约定后可并行开发
- Phase 4 与 Phase 5 串行（注册完才能真机跑）

---

## 五、风险与缓解

| # | 风险 | 严重度 | 缓解 |
|---|---|---|---|
| R1 | 建筑数量爆炸（spawn 太密，SIM 卡顿） | 🔴 高 | spawnDensity 默认 0.35（比上游 0.45 更保守）；视口裁剪 ±1.2×viewWidth；每层上限 20 栋建筑 |
| R2 | 窗户网格逐帧绘制导致 ANR | 🔴 高 | **必须预烘焙位图**，用 `drawImage` 而非逐窗 `drawRect`；TV API 22 无 BitmapShader ⇒ 用 `BlendMode.Plus` + alpha |
| R3 | Kotlin 可嵌套块注释陷阱（AGENTS.md 已记录） | 🟡 中 | 改完每个 `.kt` 后跑 `python scripts/kotlin_comment_balance.py` |
| R4 | 续行符 `+` 开头被当独立语句（AGENTS.md 已记录） | 🟡 中 | 跨行算式一律用括号包裹，`+` 留在行尾 |
| R5 | mood 色值在 Android 与 TS 间精度差异 | 🟡 中 | Python 端的 golden 基线不必逐位对账（这是移植非逐行翻译）；只对色值做数值断言 |
| R6 | 建筑 silhouette 颜色用 `Color` 对象 ⇒ 每帧创建 | 🟡 中 | 每个 mood snapshot 的颜色在 `update()` 时算好存字段，`drawContent` 只读不建 |
| R7 | `--no-daemon` 构建慢，调试迭代体验差 | 🟢 低 | Phase 1-2 (纯 Kotlin 无 Android 依赖) 可用 `kotlin-standalone-verify` 验证逻辑，不等 Gradle |
| R8 | E55 的「氛围气」与 E42「星空星轨」/ E44「明月」在使用场景上重叠 | 🟢 低 | 三个效果视觉风格完全不同（星轨=极光+星座；明月=单月光幕+海面；夜城天际=城市天际线+水面），无功能重叠 |

---

## 六、验收清单（Definition of Done）

- [ ] `NightCityRenderer` 在创维电视上以 30fps+ 稳定运行
- [ ] 播放音乐时肉眼可见建筑高度 / 窗户 / 月亮 / 雾随节拍变化
- [ ] `VisualizerThemeTest` 全部通过（29 个主题）
- [ ] `FxCoverageScanTest` 通过（≥28 渲染器类，含 `NightCityRenderer`）
- [ ] `VisualizerThemeStringsGateTest` 通过
- [ ] 全量单测 `--rerun-tasks` 通过
- [ ] `lintDebug` 无新 error
- [ ] `docs/visualizer-effects-list.md` 新增 E55 行
- [ ] `CHANGELOG.md` 新增条目
- [ ] `docs/technical-overview.md` §10 新增 N 段（遵循 code-change-logging.md 规则）
- [ ] 管理层通过 startCoroutine / Kotlin 显式返回

## 七、版权与致谢

E55「夜城天际」移植自 [`@marianmeres/cityscape`](https://github.com/marianmeres/cityscape)（作者 Marian Meres，MIT License）。

- 上游版权保留在源文件头部注释中
- MIT 许可文本复制到 `app/src/main/java/com/nasmusic/tv/visualizer/nightcity/README_UPSTREAM.txt`
- 核心数学与架构设计参考 `cityscape/docs/SPEC.md` 与 `engine/render/draw-command.ts` 的 DrawCommand 设计

## 八、备选方案对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 纯 Kotlin 重写（本方案）** | 零外部依赖；完全可控；性能最优；single source of truth | 工作量 3-4 人日；需重新调参 | ✅ 推荐 |
| B. WebView 嵌入 cityscape JS | 复用全部 TS 代码；视觉保真度 100% | WebView 内存占用大（+30MB）；API 22 TV 兼容性风险（AGENTS.md 已记录 16KB 页对齐问题）；需维护两套独立 JS/CSS bundle（cityscape 原生 + TV 适配） | ❌ 否决 |
| C. TS→JS bridge via 把 TS 编译成 WASM | 跨语言复用数学层 | Android WASM 支持不稳定（API 22）；编译链复杂；调试困难 | ❌ 否决 |

---

## V1.0 变更记录

- **变更类型**: 新建
- **变更内容**: 基于 `cityscape-master`（@marianmeres/cityscape，MIT）可行性分析，编制 E55「夜城天际」可视化效果移植开发方案，含可行性结论、上游源码分析摘要、效果设计、架构设计、5 阶段开发计划、风险与缓解、验收清单
- **生成方式**: OpenCode 直接分析 + explorer 调研 NASMusicTV 可视化器架构
- **项目目录**: `D:\hxzhang\MyGithubSoftware\NasAudio\NASMusicTV`
