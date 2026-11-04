# E52「旷野公路」开发进度

> 最后更新：2026-10-31
> 📋 **E52 方案体系**：主方案 `docs/archive/e52-highway-opengl-plan.md`（OpenGL ES 路线）| 进度跟踪本文 | WebGL 原型 `docs/archive/e52-highway-webgl-preview.html`（⚠️ 私有分支）| Canvas 2D 原型 `docs/archive/e52-highway-preview.html`（⚠️ 私有分支）| 已作废 Canvas 2D 路线 `docs/archive/e52-slow-roads-canvas-plan.md`

> 📌 **HTML 原型不在 main**：两个 `.html` 原型含上游源码，⛔ 留在私有分支 `feat/e51-private`。

## 状态总览

| 项 | 状态 |
|---|---|
| 世界核 Kotlin 逐行转写 | ✅ 完成（6 文件 2653 行，逐位相等零容差） |
| P1 地形+天空+雾+相机 | ✅ 完成（GLSL 逐字 9/9 零差异） |
| P3 路面 ribbon | ✅ 完成（几何逐位相等 5916 float32） |
| P4 植被 instancing | ✅ 完成（6 program 预热） |
| 资产层 | ✅ 完成（94 贴图/模型 + manifest + 护栏测试） |
| 白屏修复 | ✅ 完成（sky shader `#include` 剥离 + uniform 去重，commit `416ab4d`） |
| 植被 shader 修复 | ✅ 完成（VEG_ATTRS 删 `aVariant`/`aShadow` + fragment shader 加 `out vec4 fragColor;`，commit `66e6d9d`） |
| 自动驾驶 | ✅ **已实现并提交**（commit `9ce33fc`） |
| APK 构建 | ✅ 成功（release v2.39.10，40.5 MB） |
| 触摸拦截问题 | ⚠️ AndroidView 全屏覆盖可能拦截触摸，待优化 |
| 真机验证 | ⏳ 等手机连上后安装 APK 测试自动驾驶 + 抓 `adb logcat -s Highway` |

## 自动驾驶（已完成）

### 实现内容

1. **`HighwayRoadChain.tick()`**：每帧调 `extendMidline()` 延伸前方道路、当链过长时前进 head+vehicle、调 `discretiseNext()` 离散化前方、返回 vehicle 是否前进
2. **`HighwayGlScene.onDrawFrame` 调 `chain.tick()`**：vehicle 前进时调 `buildRibbon()` 重建路面
3. **`HighwayGLSurfaceView`**：energy 消费前移到 `onDrawFrame` 之前

### 自动驾驶速度

`0.6 + 0.7·energy`（保底 0.35×），上游 Hills 场景本身就是自动驾驶 ⇒ 实现永远走自动驾驶路径。

### 根因回顾

`HighwayRoadChain.loader()` 里的 autopilot 逻辑只在**构建阶段**（`!hasInitGeneration`）执行。`buildWorld()` 跑完后三个 flag 全 true ⇒ `loader()` 返回 NaN 不再做事 ⇒ `onDrawFrame` 从不调任何前进方法 ⇒ `vehicleNode` 停在 `finalise()` 设的位置不动 ⇒ 相机固定 ⇒ 画面不走。

### 关键代码位置

- `HighwayRoadChain.kt`：`tick()`（新增）、`extendMidline:672`、`discretiseNext:1187`、`finalise:499`、`MIDLINE_HORIZON = 1500`
- `HighwayGlScene.kt`：`onDrawFrame:266`（调 `chain.tick()`）、`buildRibbon:401`
- `HighwayCamera.kt`：`compute:162`（相机从 vehicle node 位置+朝向计算）

## 植被 shader 修复

### 已改代码

- `scripts/e52/e52_gen_glsl_kotlin.mjs`：
  - `VEG_ATTRS` 删 `aVariant`/`aShadow`（D799 经 `VEG_ES3_VS_DECL` 替换后已声明）
  - fragment shader 加 `out vec4 fragColor;`（ESSL 3.00 要求显式声明输出变量）
- `HighwayGlsl.kt` 已重新生成

### 构建错误原文

```
ERROR: 0:32: 'aVariant' : redefinition
ERROR: 0:34: 'aShadow' : redefinition
ERROR: 0:201: 'fragColor' : undeclared identifier
```
（4 个 program × 2 类错误，共 8 次）

## 其他

- **版本号**：versionCode = 195 / versionName = "2.39.10"
- **APK 路径**：`app\build\outputs\apk\release\NASMusicTV-release-v2-39-10.apk`
- **手机**：91846823（小米 Adreno 730，OpenGL ES 3.2），adb 不在 PATH
- **分支**：`feat/e51-private`（本地专用，永不 push）
- **最近提交**：`9ce33fc`（自动驾驶）→ `66e6d9d`（植被 shader 修复）→ `416ab4d`（白屏修复）

## 下一步

1. ⏳ **真机验证**：手机连上后安装 APK，测试自动驾驶 + 抓 `adb logcat -s Highway`
2. ⚠️ **触摸拦截**：处理 AndroidView 全屏覆盖拦截触摸问题（降低覆盖或加退出按钮）
3. ⏳ **对账**：真机出图后与上游黄金帧对账（软门禁 C：SSIM 比值 ≥ 0.95）
4. ⏳ **P5/P6**：水面 + 模型 + 环境集切换（未开始）
