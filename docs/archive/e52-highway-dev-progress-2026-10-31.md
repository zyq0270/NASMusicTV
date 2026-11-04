# E52「旷野公路」开发进度记录 — 2026-10-31

## 背景
移植 Slow Roads (three.js / WebGL) 到原生 OpenGL ES 3.0，在 Android TV 上跑。
上游参考：`scripts/e52/webgl_proto.js`、`scripts/e52/shaders/*.glsl`（从 `main.e7a33c55.chunk.js` 抽取）。

## 已解决的核心问题（按定位顺序）

| # | 问题 | 根因 | 修复位置 | 证据 |
|---|------|------|----------|------|
| 1 | **整屏纯白 + 每帧 crash** | `stitchNormals` 往 `processCurve(a=false)` 留下的**空列表**写 `tNs[0]` ⇒ `IndexOutOfBoundsException` ⇒ `tick()` 抛异常 ⇒ 帧只剩清屏 | `HighwayRoadChain.kt:1307-1323` | logcat `draw failed` + 栈顶 `HighwayRoadChain.kt:1312` |
| 2 | **surface 缩放后天空/地形不画** | `setFixedSize` 重建 surface ⇒ **EGL 上下文重建**（program id 3/6 → 21/24）⇒ 旧 GL 对象失效，`GLSurfaceView` 不再回调 `onSurfaceCreated` | `HighwayGlScene.kt:306-363` `rebuildGlResources()` | `diag skyAttrib program=24`（与初始 6 不同） |
| 3 | **远景格随近景格边界消失** | `maybeRebuildMesh` 里 `cells` 是局部变量，远景格只在 `farX/farZ` 变化时加入 ⇒ 近格边界每跨越 10 单位，9 个远格就消失 ⇒ 日志 92 次重建里 88 次 `cells=25` | `HighwayGlScene.kt:904-980` 按格缓存 (`nearCellCache`/`farCellCache`/`vegCellCache`) | 重建日志 `cells=34 (far=9, near=25)` 稳定，不再有 `cells=25` 占主导 |
| 4 | **中间距离(25~700)无地形 ⇒ "颜色没有了"** | 我们只有 near(10单位/格) + far(700单位/格) 两层，**缺上游的 mid 层**；near 窗口半径 2 (25单位) ⇒ 25~700 单位整段空洞，只剩远处全白雾的远景格 | `HighwayGlScene.kt:1707` `NEAR_CELL_RADIUS = 5` (±50单位) + 代码中窗口生成逻辑 | 截图分析：近处有绿色、远处全白，中间过渡缺失 |
| 5 | **重建逻辑触发 SIGSEGV** | `rebuildGlResources()` 里先 `buildVegetationGeometry()` **再**把 `vegGeometryBuffer/vegIndexBuffer` 置 0 ⇒ 植被绘制绑到空元素缓冲 ⇒ PowerVR 驱动在 `glDrawElementsInstanced` 里直接 SIGSEGV | `HighwayGlScene.kt:329-346` 调整顺序：先置 0 再 build | 修改前崩溃栈 `#00 pc libGLESv2_POWERVR_ROGUE.so` `#01 glDrawElementsInstanced` |

## 当前遗留问题

| 问题 | 现状 | 推测原因 | 下一步 |
|------|------|----------|--------|
| **贴图给不出颜色**（地形/植被反照率是纯白，天空渐变正常） | 调试着色器显示：`vUv` 图案正常（R/G 有条纹），**恒定 uv 采样草地贴图 B 通道 ≈ 0** ⇒ 贴图**绑定/上传有问题** | `HighwayTextures.kt` 上传流程、`bindTexture` 绑定点、或 `drawTerrain` 调用顺序 | 1) 加贴图上传后的 `glGetError` 2) 逐个 sampler 打印 `glGetUniformLocation` 3) 对比上游 `three.js` 的 `map` 参数 |
| **卡顿 (~6 fps)** | 每帧重传整个 10 万顶点缓冲 + 9000+ 草实例；远景格几乎不变却每帧 `glBufferData` | 远景格 9×70²=44100 顶点占 45%，却每帧重传 | 1) 远景格拆独立 VBO（仅首次上传） 2) near/far 双 VBO + `glBufferSubData` 增量 3) 实例缓冲同理 |
| **近景格窗口半径 5 (121 格) 首帧重建 7.2 s** | 首次 `terrain rebuilt in 7230 ms, cells=130, verts=98730` | 121 个近格一次性生成太重 | 1) 分帧生成（每帧 ≤5 格）2) 保留缓存跨 surface 重建 |

## 关键代码变更摘要

### `HighwayRoadChain.kt`
- `stitchNormals`：返回新建的 `MutableList`（不再往外部空列表写），修正首帧 crash
- `discretiseNext`：调用新签名，用返回值填 `nextTail.ns`

### `HighwayGlScene.kt`
- `onSurfaceChanged`：检测尺寸变化 → `rebuildGlResources()`
- `rebuildGlResources()`：先释放纹理、置零所有 buffer handle、清缓存，**再**重建 program/skyQuad/vegetationGeometry/ribbon
- `maybeRebuildMesh`：引入 `nearCellCache`/`farCellCache`/`vegCellCache` 持久缓存，增量重建
- `NEAR_CELL_RADIUS = 5`：近景窗口 ±50 单位，填补 mid 层空洞
- 删除了所有临时诊断代码（像素探针、属性探针、双重天空绘制）

### `HighwayTextures.kt`
- `load()`：加入平均 RGB 打印（诊断用）

### `scripts/e52/e52_gen_glsl_kotlin.mjs`
- 生成器侧删除了所有 `DEBUG_E52_*` 临时输出，恢复真实着色器逻辑

## 验证清单

| 验收项 | 状态 | 备注 |
|--------|------|------|
| 进效果不崩溃 | ✅ | 无 `Fatal signal` / `draw failed` |
| surface 缩放后天空/地形可见 | ✅ | 日志 `GL resources rebuilt` + 探针有像素 |
| 远景格持久存在 | ✅ | `cells=34 (far=9, near=...)` 稳定 |
| 近处地形有颜色（草地绿/路面灰） | ❌ | 反照率全白，**贴图采样是核心阻塞** |
| FPS > 10 | ❌ | 当前 ~6 fps，需拆远景 VBO + 增量上传 |

## 相关文件
- 修改文件：`app/src/main/java/.../HighwayGlScene.kt` `HighwayTextures.kt` `HighwayRoadChain.kt` `scripts/e52/e52_gen_glsl_kotlin.mjs`
- 生成文件：`app/src/main/java/.../glsl/HighwayGlsl.kt`（由 generator 产出）
- 调试日志：`logs_temp/e52_probe_log*.txt`、`logs_temp/e52_build_dbg*.log`
- 截图：`logs_temp/s*.png`（1920×1080，surface 实际 672×378/768×432）

## 备注
- 所有 E52 代码在本地私有分支 `feat/e51-private`，**不 push / 不 tag / 不 release**（所有者 2026-10-30 裁决）
- 本地构建需 `-Plofi.city=true`；CI/公开构建默认关闭
- 真机：创维 9R54_G8S (Android 5.1.1, PowerVR Rogue G6110, OpenGL ES 3.1)