# E52「旷野公路」开发进度

> ⚠️ **本文引用的上游源码与工具不随本文公开** —— 它们含上游（loficities.com / Slow Roads）材料，
> 而上游**无任何开源授权** ⇒ 外发即侵权 ⇒ 只留在私有分支 `feat/e51-private`
> （以及本机一次性的 `logs_temp/` 目录）上：`scripts/e51/tools/**`、`scripts/e52/**`、
> `logs_temp/lofi_modules/**`、`docs/archive/e52-highway-preview.html`、
> `docs/archive/e52-highway-webgl-preview.html`、
> `docs/archive/verification/results/e52_golden_vectors.json`、
> `docs/archive/verification/results/lofi_registry_*.json`。
> ⛔ 本文本身只是**我们自己的调研与研究**（不含上游代码本体）⇒ 按所有者 2026-11-07 裁决
> 「文档类资料是全局可公开的」与 `main` 一并公开，两个分支内容一致。

> 最后更新：2026-11-07
> 📋 **E52 方案体系**：主方案 `docs/archive/e52-highway-opengl-plan.md`（OpenGL ES 路线）| 进度跟踪本文 | WebGL 原型 `docs/archive/e52-highway-webgl-preview.html`（⚠️ 私有分支） | Canvas 2D 原型 `docs/archive/e52-highway-preview.html`（⚠️ 私有分支） | 已作废 Canvas 2D 路线 `docs/archive/e52-slow-roads-canvas-plan.md`
> 📌 **HTML 原型不在 main**：两个 `.html` 原型含上游源码，⛔ 留在私有分支 `feat/e51-private` —— ⛔ **文档搬家不改变这一点**。

## 状态总览

| 项 | 状态 |
|---|---|
| 世界核 Kotlin 逐行转写 | ✅ 完成（6 文件 2653 行，逐位相等零容差） |
| P1 地形+天空+雾+相机 | ✅ 完成（GLSL 逐字 9/9 零差异） |
| P3 路面 ribbon | ✅ 完成（几何逐位相等 5916 float32） |
| P4 植被 instancing | ✅ 完成（6 program 预热） |
| 资产层 | ✅ 完成（94 贴图/模型 + manifest + 护栏测试） |
| **P5 水面（G16）** | ✅ **渲染接线完成（第十一轮末）**：上游 `onBeforeCompile` 注入的**四段**水面对照 GLSL 逐字抽出 ⇒ 进生成器（⛔ 未手改生成物）⇒ `WATER_*_SHADER`；网格 `PlaneGeometry(1400,1400,28,28)` + per-vertex `depth`（绝对地形高度，分帧刷新 4 行/帧）+ 上游的 `vDepth > 10` 丢弃 + 天气的 `water` 三态可见性。⛔ **上游的水面在实践中恒被丢弃**（地形永远在海平面之上 10 以上）⇒ 照抄 ⇒ **画面零变化** |
| **P5 `.obj` 模型（G17）** | ✅ **渲染接线完成**：解析口径逐条对齐 three.js r144 `OBJLoader` + 独立基线脚本交叉对账；路边模型有**摆放规则** + **渲染路径**（自写 `MODEL_*_SHADER` + 每实例 `uModel`）。⛔ 交通车按 D20 不做；⚠️ **2026-11-02 第十七轮**：**路牌已换成上游 `signController`（`Ma`）的算法**（`HighwaySignPlacer`，门禁 `HighwaySignPlacerGateTest` 11/11）；**其余种类**（护墙 / 路肩标 / 火星道具）**仍是确定性替代规则**（每 24 节点 / 右侧 8 m / 面向来车）—— ⚠️ 上游的道具位置是**四个控制器沿道路链算法生成**的，**不在场景图里** |
| **P6-3b-2 环境常量** | ✅ **完成**：天空 / 雾 / 三灯 / colouration 全改由 `AppliedEnv` 供值（开屏值与旧硬编码**逐项相等**）；colouration 换成**上游真值**（草/灌木现在会被染色）；**贴图按 skin 重组**（Hills 地形 albedo / 路面 ribbon / 植被 / 云的三态） |
| **P7-2b-2 Planet GL 接线** | ✅ **完成**：双地形 program（Hills 路径逐字冻结）+ 按场景选高度场 + `?topo=` 覆盖环境集档位 + 白帧里重建 world 与地形 VBO；Planet skin 贴图驻留表（bumps 不随 skin 变 / 标识符共用 / `uGravelBump` 上游未绑定 ⇒ 绑黑） |
| **P8 降级 + 自适应回退** | ⚠️ **离线部分完成**：三旋钮统一到 `HighwayQualityTier`（`renderScale` / `res` / 贴图尺寸；后两个**只在 LOW 档**生效以免破 G18）+ **自适应回退**（p95/120 帧 / 降 2 s / 升 8 s / 最多降 2 升 1）。**G27–G30 需真机** |
| **手机实测修复（第十三轮）** | ✅ ① **白线全无** + ② **一棵树都没有**：根因 = LOW 档把 `res` 降了（近景格顶点间距 0.5 m → 10 m ⇒ **路面切槽**粗 20 倍 ⇒ 地形盖住路面；同一份粗糙化让**树的净空判定**把每棵都判成「长在路上」）⇒ 三档 `res` **全部保真**。③ 贴图上限 256² 压掉了白线 ⇒ 上限提到 512² 且**豁免路面/树图集/云/波纹**；顺带修掉 `onSurfaceCreated` **漏传**上限的 bug |
| **景色组合标签** | ✅ 右下角显示 `丘陵 · 夏季 · 晴朗 · 普通`（换歌换环境**看得见**）；`HighwayEnvLabel`（纯逻辑）+ `HighwayGlHolder.lastEnvLabel`（`@Volatile`）+ 舞台 2 Hz 读的独立 State |
| **手动切换景色** | ✅ **上下滑 / 遥控器上下键** ⇒ **随机**换一个组合（必然与当前不同），仍走三阶段雾白过场（不硬切）；链路 4 层（舞台 → 渲染器 → View → 场景 → 状态机） |
| 白屏修复 | ✅ 完成（sky shader `#include` 剥离 + uniform 去重，commit `416ab4d`） |
| 植被 shader 修复 | ✅ 完成（VEG_ATTRS 删 `aVariant`/`aShadow` + fragment shader 加 `out vec4 fragColor;`，commit `66e6d9d`） |
| 自动驾驶 | ✅ **已实现并提交**（commit `9ce33fc`） |
| 运行时道路链（冻屏 / 节点表过期） | ✅ **已修**：`maintain()` 与 `advanceVehicle()` 分离，门禁 `HighwayRoadChainRuntimeTest` 3 例 |
| 车速与相位 | ✅ **已改**：20 m/s（上游口径）、按 dt 累加、相机段内插值 |
| 贴图上色（反照率纯白） | ⚠️ **已修**：材质初始化移到反照率之后（three.js r144 顺序）+ 光照补 π（`LEGACY_LIGHT_SCALE`），真机反馈「颜色大部分都对」 |
| 远处露白（天空下方的白带） | ⚠️ **已修**：清屏色 `0xFFFFFF` → 天空远色 `SKY_FAR`，待真机复验 |
| 山坡盖住公路 | ⚠️ **已修**：新增沿路 **med 走廊**（`HighwayCorridor`，补上游 `Ao.lods[0]` 那一层），待真机复验 |
| 植物到近处才加载 | ⚠️ **已修**：植被改成沿路条带（40 节点 × ±1 格）+ 按档重铺，待真机复验 |
| 偶发镜头穿树 | 🔁 **已更正（第十轮）**：本轮代码里 `TREE_ROAD_CLEARANCE = 2`（第八轮为把树量还回来，从 3 改回 2）⇒ **净空不是 3 m**。旧文「2 m → 3 m」与代码不符。树不压路现在由「按**真实树冠半径**缩小」承担（见下面「树落在路面中间 / 树冠延申」两行的更正） |
| 帧率（每帧整块重传） | ⚠️ **已改**：槽位 VBO + 合并索引 ⇒ 3 次 draw（远 / 走廊 / 近）、按槽 `glBufferSubData`，待真机量 fps |
| 左上角贴图色卡 | ✅ **已移除**（连同 `loadPlain` 双纹理自检一并删掉，无残留调用） |
| 电视首跑：整屏黑/白碎片 | ✅ **已修**：槽位 VBO 改为**零初始化**——`glBufferData(size, null)` 内容未定义，走廊 480 槽里约 170 槽从没被上传，PowerVR 给的是脏内存 ⇒ 横跨全屏的垃圾三角形（`glGetError` 仍为 0）。门禁 `HighwayGlStateGateTest` 4 例 |
| 电视 CPU 建格代价 | ✅ **已修（第五轮）**：单格 ≈ 24 ms ⇒ 每跨一格 300–500 ms 冻结（`fps` 冷启动 4.3 / 稳态 12.4，绘制相位仅 0.2–0.6 ms ⇒ 瓶颈在 CPU 建格）；改为**每帧预建 1 格** + 植被矩阵缓存 + 绝对环形槽位，待真机量 `newlyBuilt near=0` |
| 植被贴图上下倒置（冠在下、根在上） | ✅ **已修（第五轮）**：three.js `Texture.flipY` 默认 `true`，而 `GLUtils.texImage2D` 不翻 ⇒ 整条管线 V 轴反向；在 `HighwayTextures.upload()` **统一** `flipY`（不是只翻植被），待真机复验 |
| 「多了一层土坡」：远景盖住切槽层 | ⚠️ **已修（第五轮）**：远景层（10 米格）没有切槽 ⇒ 在路堑/山坡处从上方盖住走廊（`glPolygonOffset` 只在共面时有效，压不住 1~5 米高差）；新建 `HighwayFarHole` 按洞裁剪**远景索引**（几何一字不改），洞 = 近景方窗 ∪ 整条走廊，待真机复验 |
| 电视「60 帧但观感卡顿」 | ⚠️ **根因 A 已修 / 根因 B 待测**：A = 跨格 300 ms 冻结（已拆成 11 个 23 ms 小峰）；B = 稳态 33–52 ms/帧而 `phasesMs` 只有 ~2 ms ⇒ GPU/呈现侧，候选为植被 `discard` 关掉 TBDR 早期深度剔除、`renderScale 0.4` 合成器放大、面板 60 Hz 长尾（`frameMsP95 362 ms`），需电视 AB 实测 |
| 道路白线整段消失 | ✅ **已修（第六轮）**：路面 ribbon **没有** polygon offset，而地形近景/走廊带 `-1/-2`（往前顶）⇒ 切槽过渡带共面时地形赢；地形在该处被涂成 `roadCol` 灰（与路面底色几乎一样）⇒ 「路面在、白线没了」。路面改 `-4/-8`。**第六轮真机截图确认白线回来了** |
| 白线「到近处断线/缺损」 | ✅ **已修（第七轮）**：**不是**上一个 bug —— `segments()` 只从锚点往前铺，而 eye 在车辆节点**后方 2.2 m** ⇒ 屏幕最下方一截路面**根本没有 ribbon**，露出的地形被涂成 `roadCol` 同色 ⇒ 看着像「路面在、白线没了」。锚点沿 `prev` 回退 1 节点（`RIBBON_ANCHOR_BACK`） |
| 树落在路面中间 | ✅ **已修（第六轮）**，⛔ **半径口径第十轮更正**：当时按 billboard quad 的 x 半宽 `0.5` 算 ⇒ 树冠半宽 `4·hb`；**真实水平半径是 `hypot(0.5, 1) = 1.118`**（第二片**斜 quad** 的角点 `(0.5, 0, 1)`）⇒ 旧式**漏判 2.24 倍**。`placeTrees` 按实际缩放再判一次（在 `nn.next()` 之后 ⇒ PRNG 序列不变）。离线探针（第十轮）：按 0.5 判 **0/14 越界**（所以旧单测 10/10 全绿是假通过）、按 1.118 判 **14/14 越界、最差 +6.38 m** ⇒ 这才是 R9「树仍在路上」的根因 |
| 树冠「偶尔延申到道路上」 | ✅ **已修（第七/八轮），第十轮更正分母**：第六轮留了「允许悬挑 1 m」，但水平投影是**穿过树根**、长 `2·canopyHalf` 的线段，相机沿路看时正好横穿路面 ⇒ 悬挑 1 m = 进路面 1 m。改成「径向全宽、任意朝向都安全」；第七轮用 `continue` 丢弃，**第八轮改为「缩小到刚好放下」**（丢弃会把路边整格砍空）。⛔ **分母由 `4` 更正为 `TREE_SCALE × 1.118 ≈ 8.944`**：`scale = min(hb, (roadGap − 0.5) / (8 × HORIZONTAL_RADIUS_UNITS))`，`TREE_ROAD_CLEARANCE` 保持 2（上游口径），`MIN_TREE_SCALE` 0.4 → **0.2**（跟着新半径定，保证「刚过净空就能放下」）。修复后同一探针：**越界 0/11、最差 0.0 m**；代价是路边树变小（scale 0.26~0.58 ⇒ 树高 2.1~4.6 m，**观感待真机确认**） |
| 「看不出哪里该有树」 | ✅ **已定性：上游正常行为，不是 bug**。真机 `cell stats` 实测 `treeLod[0]=117 [1]=4 [2]=0 [3]=0` ⇒ 121 个近景格里 **117 格是无树区**（LOD 0 ⇒ 上游 `getTreesAt` 返回 null），所以 `vegTree=9` 是 4 个有树格里的合理结果 |
| 第一次进入黑屏 ~9 s | ✅ **已修（第六轮）**：首帧一次建 121+307+9 格（7718 ms）+ shader 编译 600 ms + 贴图解码 435 ms，期间**零 swap**。改为 4 阶段引导（每帧 swap）+ 分帧建格 + 世界只建一次 + `rebuildGlResources` 不再清几何缓存。**真机：首帧 7718 → 2068 ms** |
| 「先出天空色，然后一次性都全部出现」 | ✅ **已改（第七轮）**：首帧 2068 ms 里 `veg=1479 ms`（71%）—— 植被序列化没分帧。`uploadVegetation()` 改按**时间**预算分帧（`VEG_PACK_BUDGET_MS = 80`）⇒ 植被逐块长出来。⛔ 不能按格数分帧（单格成本差 20 倍） |
| 「显示帧率还是 59，实际卡顿」 | ❌ **未修（第十轮真机实测推翻第七轮结论）**：第七轮加了 `VisualizerRenderer.renderFps()`、让舞台优先用渲染器上报的真实帧率，但 **R9/R10 真机实测徽章仍 50.5~59.4 fps**，而同刻 `stats {"fps":8.697,"frameMsAvg":114.98}` ⇒ **上报路径没生效**（"徽章口径错"这个定性是对的，但接线没通）。第十轮已改：舞台取 `currentRenderer ?: swapper.current`、`HighwayGlHolder.lastFps` 进程级兜底、每秒 `fpsdiag` 日志；**待出包验证** |
| 电视 CPU 侧「AOT 是否生效」 | ✅ **已排除**：`/data/app/*/oat` 不存在曾疑似解释执行，但对照发现这台 ROM 把 oat 放 `/data/dalvik-cache/arm/`（62 MB，安装时生成）⇒ **确实 AOT 过**，`27 ms/格` 是真实 CPU 速度 |
| 卡顿归因（量化） | ⏳ **已拆成两个独立成因**：**A** 每 0.6~0.7 s 一次 300~400 ms 硬卡（`PHASE_MESH` 占该帧 97~99%，CPU 同步建格）；**B** 基线 83 ms/帧 ≈ 12 fps（无建格帧相位合计仅 1.8 ms ⇒ GPU 填充率）。下一步：A 搬后台线程；B 做 AB 实测（`renderScale` 0.30 / 关植被 / 关地形），首要嫌疑是 18000 个 grass billboard 的 overdraw |
| 卡顿 A（300 ms 硬卡） | ✅ **已改（第七轮续）**：真机数据证明 **CPU 建格被 GPU 等待免费吸收**（`frame=84.3 mesh=23.2` 与 `frame=84.8 mesh=0.2` 帧时相同）⇒ 病根是**预取供给不足**（1 格/帧 × 6 帧 = 6 格 < 跨格需要的 11 格）。预取改 **2 格/帧 + 前瞻 25 m + 按距离排序 + 续建帧跳过**；兜底把单帧预算 8/24 → 4/12（峰值 280 → ~165 ms） |
| 卡顿 B（GPU 83 ms） | ⏳ **待 AB 实测**：加了 `nasmusic_hw_mask`（图层开关）与 `nasmusic_hw_scale`（分辨率覆盖）两个 `Settings.Global` 开关，**不用重编译**就能逐个关掉量 fps。首要嫌疑 = 18000 个 grass billboard 无距离剔除 + alpha test 关掉 TBDR 早期深度剔除 |

| APK 构建 | ✅ **第十四轮出包（最终，已装手机）**：release v2.39.10 / 195，**40,526,564 B**，md5 `fbd3e42f`（`adb install -r` → `Success`）。`apksigner verify` 通过（v1+v2，单一签名者）。⛔ **电视仍未装机**（失联）。⚠️ APK **非逐字节可复现**（zip 时间戳）⇒ 校验用 `versionCode` + 证书 + 大小 |
| 触摸拦截问题 | ⚠️ AndroidView 全屏覆盖可能拦截触摸，待优化 |
| 真机验证 | ⏳ **电视 `192.168.0.114` 全程失联**（ping 不通、ARP 无记录、adb 连不上）⇒ 真机门禁**全部待补**：G18 黄金帧 / G27–G30 / Planet 场景首跑 / 水面与路边模型首跑 / 旧的②③④问题复验。离线部分（P0–P7 全部门禁 + P8 的旋钮与自适应逻辑）**已全部通过** |

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

## 2026-10-31 第二轮：上色顺序 + 槽位 VBO

### ① 反照率纯白 —— 材质初始化顺序（已修）

上游 three.js r144 的 chunk 顺序是 `map_fragment` / `color_fragment` **在前**、
`lights_physical_fragment` **在后**：材质构造时必须已经拿到反照率，否则
`material.diffuseColor` 恒为 `vec4(1.0)` ⇒ 光照后是纯白。

- `scripts/e52/e52_gen_glsl_kotlin.mjs` 三个 fragment 模板（地形 / 路面 / 植被）都已把
  `PhysicalMaterial material; … ReflectedLight reflectedLight = …` 移到反照率写入 `diffuseColor` 之后；
- 诊断用的 `gl_FragColor` 覆写（R/G=uv、B=固定采样）已撤除；
- `HighwayGlsl.kt` 由脚本重新生成（⛔ 不手改）。
- 校验：`HighwayGlslParityTest` 5 例通过（只比对 13 个上游 chunk，模板改动不影响基线）。

### ② 帧率 —— 槽位 VBO + 合并索引（已改）

实测旧实现每次跨格：把 130 格拼成 98,730 顶点（3.9 MB）整块 `glBufferData`，
再 130 次 `glDrawElements` ⇒ 约 6 fps。

- 近景 121 槽 × 441 顶点、远景 9 槽 × 5041 顶点，**每格固定槽位**；
- 只在格键变化时对该槽做 `glBufferSubData`（近景窗口平移一格 ≈ 11 槽 ≈ 176 KB）；
- 合并索引建一次（近景最大索引 53,360 / 远景 45,368，均在 `GL_UNSIGNED_SHORT` 内）⇒ 2 次 draw；
- 日志新增分项：`terrain rebuilt in N ms [nearBuild=… farBuild=… veg=…], newlyBuilt near=… far=…`。
- 顺带去掉 `rebuildGlResources()` 里重复的 `buildRibbon()`。

### ③ 版本号整理

`app/build.gradle.kts` 里历史遗留的三组 `versionCode/versionName`（192/2.39.7、194/2.39.9、195/2.39.10）
合并为一处：**195 / "2.39.10"**（`aapt2 dump badging` 已核对）。

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

## 2026-10-31 第三轮：真机五项反馈

真机复验反馈：① 速度正常；② 颜色大部分对，但**远处天空只盖住上方约 1/4，下方露白**；
③ **很多路段两侧山坡盖住公路**；④ 偶尔撞树（已不多）；⑤ **植物加载慢**，到近处才出现、先露出后面的山坡。
本轮同时按所有者要求**去掉左上角那堆诊断色卡**。

### ⓪ 诊断色卡 —— 已移除

`onDrawFrame` 里的 `drawTexProbe` / `probePixel` / `probeRegion` / `probeSwatchCentres`
以及配套的探针 shader、`TEX_PROBE_*` 常量、`TERRAIN_SAMPLER_NAMES` 全部删除；
`HighwayTextures.loadPlain()`（专门给色块用的「不带 mipmap 的第二份纹理」）成了死代码，一并删除，
`upload()` 因此回到单一 mipmap 路径。设备侧回读日志（`tex <name> … minFilter/aniso/avg`）保留。

### ① 远处露白 —— 清屏色改成天空远色（不是天空太小）

天空平面在 `y = 200`、不跟相机 ⇒ 相对眼睛只高出约 0.73°，它的**远端边界**与远景地形轮廓线之间
必然留一条缝；上游 `renderer.setClearColor(16777215)` 是**纯白**，缝里就露白。
上游之所以看不见，是因为它的天空平面铺满整个视野。

- `HighwayGlScene.onSurfaceCreated()` 与 `HighwayGLSurfaceView` 两处 `glClearColor`
  改为 `HighwayEnvironment.rgb(SKY_FAR)`（`0xCBFDFE`，天空边缘色）⇒ 缝隙与天际线同色。
- ⛔ 不动天空几何（改几何会牵动 `HighwayTerrainCameraGateTest` 的抓包对账）。

### ② 山坡盖路 —— 补上游 med 走廊（`HighwayCorridor`）

**根因**：切槽（`roadProximity`）过去只存在于相机 ±5 格（±50 米）的**方形**窗口里，
50 米之外直接是没切槽的远景格 ⇒ 看得见的每一段路（几十~几百米）都压在未切槽地形之下。

**上游结构**（`class Ao`）本来就有两档：med `lods[0]`（`me=10`，前沿 `xe=180` 个节点，`tiles=2` ⇒ 5 格宽，**带切槽**）
+ near `lods[1]`（`be=20`，30 节点，3 格宽）；far（`class nt`）**有意没有切槽**。
我们此前只做了 near 方窗 + far，缺的就是 med 那一层。

- 新增 `terrain/HighwayCorridor.kt`（纯 JVM，单一真源）：条带取格 = 从车辆节点沿 `next` 走 `ROWS_AHEAD = 75` 行、
  沿 `prev` 走 `ROWS_BEHIND = 15` 行，每行按**横向法线**取 ±`HALF_WIDTH = 2` 格，并 `skip` 掉近景方窗（互斥 ⇒ 不 z-fight）。
- 为什么是「沿路条带」而不是「更大的方窗」：方窗以**相机**为中心，条带以**公路**为中心 ⇒
  公路拐弯时只有条带保证「看得见的每一段路」都在切槽范围内；同样覆盖 750 米，方窗要 ≈5,776 格，条带只要 450 格。
- `HighwayGlScene`：走廊独占一套**槽位 VBO**（`CORRIDOR_SLOT_COUNT = 480` × 121 顶点 = 58,080 < 65,536，16 位索引仍可用），
  未用到的槽写入全 0 的退化三角形（不产生片元 ⇒ 不必按槽裁剪索引）；
  绘制顺序 远 → **走廊** → 近，走廊与近景同样用 `glPolygonOffset(-1, -2)` 压住远景；
  跨格时只做「park 掉离开条带的槽 + 填新槽」，每次约 5 个 med 格（≈0.45 ms/格）。
- 冷启动首次填满 ≈450 个 med 格（约 200 ms），发生在第一次跨格时 —— 由 `terrain rebuilt … corridor=…ms` 直接可观测。

### ③ 植被「到近处才有」—— 沿路植被条带 + 分档重铺

- 旧取格是「车辆 ±2 方窗 + 每个节点只取中线 1 格」⇒ 路两侧各 5 米是空的，驶近才补上。
- 现在植被键集 = ±2 方窗 ∪ `bandKeys(VEG_ROWS = 40, VEG_HALF_WIDTH = 1)`（400 米、左右各 10 米）。
- ⭐ 条带**复用走廊已建好的 med 格**（`corridorCellCache`），不额外建格；
  `vegCellTier` 记录每格当初用的档，格子进入近景方窗后**升档重铺**（med res10 的草量约为 near res20 的一半）。

### ④ 偶发撞树 —— 净空 2 → 3 米

`HighwayVegetation` 的树过滤条件 `q.d - (q.w + q.e) > 2` 改成 `> TREE_ROAD_CLEARANCE = 3`。
⚠️ **有意偏离上游**：相机尾距 3.5 单位 + 树冠半径会盖过 2 米的余量。

### 新增日志 / 门禁

- `diag#` 行的 `cells=` 与 `verts=` 计入走廊；`stats` JSON 新增 `corridorSlots`、`corridorIndexCount`；
  `terrain rebuilt` 行新增 `corridor=…ms` 与 `newlyBuilt … corridor=…`。
- `HighwayCorridorGateTest`（6 例）：前方每段路必在「近景窗口 ∪ 走廊」内、槽数与 16 位索引预算、
  走廊与方窗互斥、植被条带不额外建格、med 档确实带切槽（far 档确实不带）、长跑 800 节点后判据仍成立。
- 相关回归：`HighwayRoadChainRuntimeTest` 3 例、`HighwayTerrainCameraGateTest` 5 例、`HighwayVegetationGateTest` 7 例、
  `HighwayGlStateGateTest` 3 例。

## 2026-10-31 第四轮：电视首跑「画面崩了」—— 槽位 VBO 是脏内存

### 现象与取证

装机（`adb install -r`，全新安装未卸载）后所有者报「跑起来了，但画面崩了」。

- 进程活着（`pidof` = 27588）、日志里**没有** `SIGSEGV` / `AndroidRuntime` / `tombstone` ⇒ 不是崩溃；
- `output/e52_tv_195_broken.png` 逐色统计：**纯白 37.2% + 纯黑 28.6% + 雾色 (240,255,255) 27.3%**，
  而本轮刚改的清屏色 (203,253,254) **一个像素都没有** ⇒ 屏幕上全是"被画出来的东西"，不是背景；
- `diag glError after all draws = 0`、16 张贴图 `errUpload=0 errMip=0` ⇒ 不是 API 误用、不是丢纹理；
- 24×14 的 NEAREST 色块图显示这些白/黑块是**横跨全屏的巨型三角形碎片**（边界全是直线），不是高度场。

### 根因：`glBufferData(size, null, …)` 的内容未定义

三块**槽位 VBO**（近景 / 远景 / 走廊）都是 `glBufferData(bytes, null, DYNAMIC_DRAW)` 分配的，
而合并索引**覆盖全部槽位**——空槽的设计前提是"全 0 ⇒ 121 个顶点重合 ⇒ 退化三角形 ⇒ 不出片元"。

问题在于「全 0」这个前提**从没被写进缓冲**：

| 层 | 槽数 | 每轮实际填到的槽 | 结果 |
|---|---|---|---|
| 近景 | 121 | 121（方窗铺满） | 从未暴露 |
| 远景 | 9 | 9（3×3 铺满） | 从未暴露 |
| **走廊** | **480** | **~310**（滚动列表，日志 `slots=437/610`） | **~170 槽是脏内存** |

`syncCorridorSlots` 只在「槽从占用变空」时上传 parked 全 0（`if (key == EMPTY_SLOT) continue`
直接跳过从没填过的槽）⇒ 那 170 槽的顶点位置、法线、`roadProx`/`treeMask`/`curvature` **全是随机比特**
⇒ 巨型三角形 + 随机光照（黑片和白片同时出现）+ 深度把真实地形盖掉。

⚠️ **这是驱动差异，不是"这轮改坏了"**：Adreno（手机）把这块内存给成 0 或未提交页，看着就正常；
PowerVR（创维电视）给的是脏内存。走廊层是本轮新加的，所以第一次上电视就踩中。

### 修法

`HighwayGlScene.kt`：`allocateBufferBytes(bytes)` → **`allocateSlotBuffer(slots, vertsPerSlot)`**，
用 `ByteBuffer.allocateDirect`（JVM 规范保证初值全 0）整块 `glBufferData` 上去；
近景 / 远景 / 走廊三处一律走这条路径。parked 上传逻辑不动——它管"用过之后又空出来"的槽，
这次坏的是"从来没写过"的槽。

### 门禁

`HighwayGlStateGateTest` 新增 `slotBuffersAreZeroInitialised`（3 → 4 例）：
`allocateSlotBuffer` 里出现 `glBufferData(…, null, …)`、或 `ensureSlotBuffers` 里
`allocateSlotBuffer(` 调用数 ≠ 3，直接判红。

### 同一份日志量出的电视侧性能底（待办，不在本轮改）

- **单个近景格 ≈ 24 ms**（`nearBuild=290ms` / `newlyBuilt near=11`）⇒ 每跨一格 300–500 ms 卡顿；
  `stats` 冷启动 `fps=4.34`、稳态 `12.43`，`frameMsAvg` 230 → 80 ms，`hitches` 几乎每帧。
- 瓶颈是 **CPU 建格，不是 GPU**：`phasesMs` 的绘制相位只有 0.2–0.6 ms。
- 冷启动全量重建跑了**两次** 7.7 s（`nearBuild 2.9s + corridor 1.7s + far 3.0s`），
  因为 `onSurfaceCreated` 先按 1920×1080 建了一遍，紧接着 `onSurfaceChanged`
  打印 `surface resized -> rebuild GL resources` 把缓冲全销毁重建。
- ⇒ 电视档需要单独降载（近景 `res` / 走廊格数 / 每帧跨格预算），并让 GL 资源重建不在首次 resize 时全量重跑。

## 2026-11-01 第五轮：真机三项（贴图倒置 / 多一层土坡 / 60 帧仍卡）

真机复验后的三条反馈，逐条定位到根因（不是猜的，每条都有判据）。

### ① 树和草的贴图上下倒置（冠在下、根在上）—— 已修

**根因：整条管线的 V 轴与上游反向。**

- 上游 three.js 的 `Texture.flipY` **默认 `true`** ⇒ 在 GL 里 `v = 0` 对应**图像最下一行**；
- 我方 `GLUtils.texImage2D` 不做翻转，而 GLES 又没有 `UNPACK_FLIP_Y` 这类可用的开关；
- ⇒ `v` 方向整体反了。**可平铺噪声看不出来**（上下翻转后还是同一张噪声），
  只有**有方向的图集**才显形 —— 也就是树/草/灌木那张。
- 佐证：`HighwayVegetationGeometry.UV_BASE / UV_NEAR` 的 `v ∈ [0, 0.49]`，`v = 0` 绑定在**地面顶点**上
  ⇒ 图集里「根」所在的那一行必须落到 `v = 0`。

**修法**：`HighwayTextures.upload()` 首行统一 `flipY(decode(name))`。
⛔ **不是只翻植被** —— 只翻植被会让植被与地形/路面的 UV 基准分家，后续任何共用图集都会错。

`flipY` 用 `Matrix().setScale(1f, -1f)` + `Bitmap.createBitmap(..., filter = false)`：
180° 翻转是整数像素对齐的，**开双线性只会把树冠的 alpha 边缘糊掉**（`alphaTest 0.75` 的轮廓会毛糙）。

### ② 左右土坡盖到路上，穿模时能看到下面还有一层带植物的正常地形 —— 已修

**根因：远景层（10 米格）没有切槽，在「原始地形高于路面」的路段从上方盖住了切槽层。**

三层地形的切槽能力本来就不一样：

| 层 | 分辨率 | 切槽（`roadProximity`） |
|---|---|---|
| `near`（相机 ±5 格方窗） | 0.5 米 | ✅ |
| `corridor`（沿路条带 ±2 格） | 1 米 | ✅ |
| `far`（上游 `class nt`，700 单位 / 70） | 10 米 | ❌（上游也没有） |

远景格高度 = 纯高度图 ⇒ 在路堑/山坡处它**高于**已切槽的走廊。
绘制顺序是「远 → 走廊 → 近」，走廊还带 `glPolygonOffset(-1f, -2f)` 往前顶，
但**多边形偏移只在共面时有效**：远景面比切槽面高 1~5 米，深度差远大于 2 个 unit ⇒ 远景照样赢。
用户看到的「土坡下面还有一层正常地形 + 正常植物」正是被盖住的走廊/近景层。

**修法：让远景层「不画」被切槽层覆盖的那些 10 米子格**（新建 `terrain/HighwayFarHole.kt`）。

- 远景格格距 = `farSize / farRes` = 700 / 70 = **10 米**，与 `cellSize` 完全一致，
  且远景格原点恒为 10 的整数倍 ⇒ **远景的每个子格 = 一个近景格**，键直接复用 `HighwayCorridor.cellKey`；
- 索引布局与 `gridIndices` **逐字相同**（`a,n,h / n,o,h`），只跳过落在洞里的子格；
- ⛔ **远景几何一个字不改** —— 仍是上游 `class nt` 的逐行移植（`roadProximity` 恒 0、高度 = 纯高度图），打洞只发生在**索引**上；
- 洞 = 近景方窗 ∪ 走廊格集；`rebuildFarIndices` 每次跨格用 `glBufferSubData` 覆盖前 `count` 个索引，
  复用直接缓冲（**不重建 VBO、顶点一个都不动**，避免 0.5 MB/次的新分配）。

#### ⚠️ 洞宽：第一版算错了，本轮改回来了

第一版按「洞比走廊**窄 1 格** ⇒ 最外 10 米两层重叠 ⇒ 高度一致，只是多画一点」实现，
理由是「洞开满会因不同分辨率采样同一高度场而露细缝」。**两个判据都错了**：

1. **切槽的消失半径不是 ±20 米，而是距路缘 `rm/2` ≈ 5~8 米。**
   `lerpHeight` 里 `lt = 2*(d-w)/rm`、`rm = 4 + 16*clamp(ga, 0.4, 0.75)` ∈ `[10.4, 16]`，
   `lt` 到 1 就是纯地形 ⇒ 消失点 `d = w + rm/2`，`w` 约 4~6 ⇒ **11~14 米**。
2. **`bandKeys` 用 `floor` 取格，最多有 10 米取整偏差。**
   路中线在 9.9 米时 `k=+1` 取到的是格 `[10, 20)` ⇒ 相对中线只有 `[+0.1, +10.1]`。
   ⇒ 半宽 1 格在**最坏情况下只覆盖到中线外 10 米**，漏掉 1~4 米**带切槽**的走廊条带
   ⇒ 那圈远景（无切槽、更高）继续盖住走廊。

改成 `HighwayFarHole.HALF_WIDTH = HighwayCorridor.HALF_WIDTH`（洞 = 走廊）后远景与切槽层**零重叠**。
边界不会因此露缝：走廊边界顶点高度就是 `terrainHeight`（`lt = 1`，与远景同一高度函数），
且走廊带 `glPolygonOffset(-1f, -2f)` 前移 ⇒ 走廊边缘压住远景边缘。

`rebuildFarIndices` 里加了不变式 `check(subCells == res)`（`farSize/cellSize` 必须等于 `farRes`，
否则远景子格 ≠ 近景格、洞会整体错位）。纯函数那边**不做 `require`** —— 门禁要用 `res = 4` 缩小规模。

### ③ 电视上 60 帧但观感卡顿 —— 根因 A 已修，根因 B 待电视实测

先分清两件事：**「帧率」和「流畅度」不是一回事**。

#### 根因 A（已修）：每跨一格冻结 ≈ 300 ms

`logs_temp/e52_tv_logcat_195.log` 的判据：

- `terrain rebuilt in 314 ms [nearBuild=247ms corridor=34ms farBuild=0ms veg=32ms], newlyBuilt near=11`
  ⇒ 跨一格要建 **11 格**，单格 ≈ 23 ms；
- 跨格帧 `hitch frameMs=324.5 phasesMs=0.8/314.3/0.2/0.3/0.1/0.4`
  ⇒ 324 ms 里有 314 ms 花在**建格相位**；
- 非跨格帧 `frameMs ≈ 33–52 ms` 而 `phasesMs` 合计只有 **~2 ms**
  ⇒ CPU 大量空闲，那 30–50 ms 在等 GPU/呈现。

三处修法（全部落在 `HighwayGlScene`）：

| 改点 | 做法 | 为什么 |
|---|---|---|
| `prefetchNearCell` | 每帧最多**预建 1 格**，跨格那帧 `builtNear = 0` | 11 格摊到 11 帧（0.5 秒内正好摊完）⇒ 300 ms 冻结拆成 11 个 23 ms 小峰 |
| `vegPackedCache` | 缓存**已序列化的实例矩阵**，跨格只做 `System.arraycopy` | 原来跨格要重算 ~19,000 个矩阵（`veg=32ms`） |
| `nearSlotIndex` | 槽位从「相对 dx/dz」改成**绝对格坐标环形映射** | 原来跨格要把 121 槽全部重传，现在只传新进入的 11 槽 |

⛔ 预取**必须放在 `maybeRebuildMesh` 的提前返回之外** —— 那里「本帧没跨格」就直接 `return`，
放进去预取就永远只在跨格那一帧执行，而那一帧正是要避免卡的那一帧。
⛔ `keep` 集合必须并入预取窗口，否则 `retainAll` 会把预取的格立刻清掉（**预取静默失效**）。
⛔ `NEAR_PREFETCH_PER_FRAME` 不要调到 2 以上 —— 只是把冻结拆成更大的单帧尖峰。

#### 根因 B（未定位）：稳态 33–52 ms/帧，CPU 只占 2 ms

这部分**不是** CPU 建格，`phasesMs` 已经排除了它。候选（按可能性排序，都还没测）：

1. **植被 `discard` + `texture()` 隐式 LOD**：`alphaTest 0.75` 的 `discard` 会关掉 PowerVR 的
   早期深度剔除（TBDR 上 `discard` 会强制延迟深度测试），而隐式 LOD 在 `discard` 之后也算不进去；
2. **`glGenerateMipmap` 的 16 张图**：每张贴图一次，但 16 张加起来在 768×432 上未必是大头；
3. **`renderScale = 0.4` + `setFixedSize` 的合成器放大**：Surface 768×432 放大到 1920×1080 由硬件合成器做，
   多一遍全屏采样；
4. **面板 60 Hz 与 `frameMsP95 362 ms` 的长尾**：平均帧率好看、尖峰难看 ⇒ 感知上就是「卡」。

⇒ **需要电视侧 AB 实测**（下面「下一步」列了具体做法），不要凭猜测改。

### 门禁

- 新增 `HighwayFarHoleGateTest` **5 例**（4 + 1）：
  `emptyHoleKeepsTheFullGrid`（洞为空 ⇒ 与 `gridIndices` 逐位相同）、
  `holeRemovesExactlyOneSubCell`（一个子格 = 6 索引，绕序正确）、
  `emptySlotsProduceNoIndices`（空槽整槽跳过）、
  `everythingHoleGivesZeroIndices`（洞覆盖全部 ⇒ 0 索引）、
  **`holeMustBeAtLeastAsWideAsTheCorridor`**（`HighwayFarHole.HALF_WIDTH >= HighwayCorridor.HALF_WIDTH`
  —— 这条是问题 ② 不复发的唯一保证；洞与走廊共用 `bandKeys`、同样的 rows、同样的 `skip`，
  唯一变量就是 `halfWidth`，所以断言常量即可，不必跑整条路链）。

### 待验证（真机）

1. 树/草的冠是否在上（①）；
2. 两侧是否还有「一层土坡盖路 + 下面一层正常地形」（②）—— 重点看**路堑段**（原始地形明显高于路面）；
3. 跨格时那一下冻结是否消失（③ 根因 A）—— 读 `terrain rebuilt` 的 `newlyBuilt near=` 是否为 **0**，
   以及 `hitch frameMs` 是否从 324 ms 降到 ~30 ms 量级。

## 2026-11-01 第六轮：真机四项（树在路上 / 白线消失 / 无树区 / 首帧黑屏）

### ① 白线「整段缺失」—— 路面被地形压住

**现象**：路面还在，但车道线整段没有（不是远处模糊，是整段没有）。

**取证**：`road_03.jpg` 是 1024² 的正方形，白线在**最外圈 u≈0/1**（几个像素宽）+ 中间一条短虚线。
`HighwayRoadRibbon` 的 UV 是 `u ∈ {1, 0}`（左右两条边）、`v = (s/e)×16`，wrap 是 `GL_REPEAT`、aniso 8
⇒ UV / mipmap / 贴图参数**都没有问题**。

**根因**：绘制顺序里的深度偏置不对等。
- 地形近景与走廊都带 `glPolygonOffset(-1f, -2f)`（往前顶，为了压住远景格）；
- 路面 ribbon **一个偏移都没有**，y 只比路中线高 **0.01**；
- ⇒ 在**切槽过渡带**（`roadProx` 从 -1 渐变到 0，地形高度从路面以下渐变到地形高度）
  两者几乎共面 ⇒ **地形赢**。

地形 shader 在 `roadProx < -0.2` 时把地形涂成 `roadCol`（`0.45,0.45,0.474`，灰色），
与路面贴图底色几乎一样 ⇒ 眼睛看到的是「路面在、白线没了」。
白线只有几个像素宽，一旦被地形盖住就是**整条消失**，而不是斑驳。

**修法**：`drawRoad` 加 `glPolygonOffset(-4f, -8f)`（比地形的 -1/-2 更强 ⇒ 路面恒赢）。
⛔ 不要靠抬高 `y`（`+0.01` 是上游 `main:13801` 的数值，改了会偏离黄金帧）；
⛔ 偏移再大（-8/-16）会让路面在靠近的植被/护栏上「浮起来」。

### ② 真有树落在路面中间 —— 只判了树干净空，没判树冠

**现象**：树不是「压到路边」，是**树冠横跨到路中间**。

**根因**：`treeCandidates` 只判了 `q.d - (q.w + q.e) > TREE_ROAD_CLEARANCE`（= 3 米，到路缘）。
但树是 billboard：几何 quad 的 `x ∈ [-0.5, 0.5]` ⇒ **半宽 0.5 单位**，
再乘 `TREE_SCALE = 8` 与实例缩放 `hb` ⇒ **树冠半宽 = 4·hb 米**。

`hb = tms + (nn - 0.2)·tms`、`tms = 0.15(3·treeDensity - 1) + 1`、
`nn = Cs(101, 0.4)` ⇒ `nn ∈ [0, 0.4)` ⇒ **`hb ∈ [0.8, 1.2)·tms`**
（⚠️ 原文这里写成 `[0.8, 1.8)·tms`，把树冠高估了 50%，见第八轮更正）：
- 疏林（treeDensity = 0）⇒ `hb ∈ [0.68, 1.02)` ⇒ 树冠半宽 **2.7~4.1 米**
- 密林（treeDensity = 3）⇒ `hb ∈ [1.76, 2.64)` ⇒ 树冠半宽 **7.0~10.6 米**

净空只有 2~3 米 ⇒ 密林区的树冠必然横跨整条路面（路半宽 `w` 约 4~6 米）。

**修法**：`TreeCandidate` 记下 `roadGap`（免得 `placeTrees` 再跑一次 O(n) 的 `qe`），
`placeTrees` 里按**实际缩放**处理：`scale = min(hb, (roadGap − 余量) / 4)`
（第六/七轮实现成 `continue` 丢弃，**第八轮已改**，见 §2026-11-01 第八轮 ⑧）。

⛔ **判定必须在 `nn.next()` 之后**：每个候选都要消费一次随机数，
提前 `continue` 会让后续所有树的缩放错位（整片森林变形）。算出来再丢弃 ⇒ PRNG 序列不变。

### ③ 「无树区」无法判断 —— 先加可分辨的日志

**现象**：`vegTree` 从 68 一路降到 **2** 并稳定，`grass` 仍有 15000+。

**为什么没直接改**：两种可能，修法完全相反，现有日志分不开：
- **树图真的把这片判成无树区**（`getXZ` 返回 0）⇒ 上游正常行为，不该改；
- **我们的路距 / 树冠过滤砍太狠** ⇒ 要调阈值。

**修法**：`logCellStats` 增加 `treeLod[0..3]` 的**格数分布**（`treeDensity` 取整归档）。
判读口径：
- `[0]` 占绝大多数 ⇒ 无树区，正常；
- `[1..3]` 不少但 `vegTree` 仍是 0 ⇒ 过滤太狠，下一轮调阈值。

⛔ 统计必须等**近景全部建完**再打（分帧之后第一帧只有 4 格，会得出「全是有树区」的错误结论）。

### ④ 第一次进入有一段黑屏 —— 首帧 9 秒里一帧都没 swap

**取证**（`logs_temp/e52_tv_logcat_195.log`）：

```
23:13:13.975 caps …                                    ← onSurfaceCreated 开始
23:13:14.778 world ready in 200 ms, programs=6         ← shader 编译 + buildWorld = 803 ms
23:13:14.778 surface resized -> rebuild GL resources   ← 立刻又重建一次
23:13:22.728 terrain rebuilt in 7718 ms                ← 7.7 秒建 121+307+9 格
23:13:22.746…23:13:23.181 tex … 16 张贴图             ← 再 435 ms 解码
23:13:23.929 surface buffer 768x432                    ← 降分辨率**10 秒后**才生效
```

⇒ **首帧从进场景到出图约 9 秒，期间零 swap ⇒ 全黑。**
而且 `setFixedSize` 被这 7.7 秒阻塞，前两帧都是 **1920×1080 全分辨率**在跑。

**四处根因，四处修法**：

| # | 根因 | 修法 |
|---|---|---|
| 1 | `onSurfaceCreated` 里编译 6 个 PBR program（≈600 ms）+ `buildWorld`（200 ms），期间不 swap | 拆成 [BOOT_CLEAR]→[BOOT_PROGRAMS]→[BOOT_WORLD] 三帧，**每帧都 return ⇒ 每帧都 swap**；第一帧只 `glClear` ⇒ 立刻显示天空远色（不是黑） |
| 2 | 首帧一次建 121 近景 + 307 走廊 + 9 远景（7.7 s） | 分帧预算 `NEAR_BUILD_PER_FRAME = 8` / `CORRIDOR_BUILD_PER_FRAME = 24` / `FAR_BUILD_PER_FRAME = 1`；**首帧**更小（`FIRST_NEAR_BUILD = 4`、走廊 0）⇒ ~430 ms 出图 |
| 3 | `setFixedSize` 重建 Surface ⇒ `onSurfaceCreated` 再跑一次 ⇒ `buildWorld` 再来 200 ms，且 `rebuildGlResources` 清空几何缓存 ⇒ **又一次 7.7 s** | 世界只建一次（`road != null` 就跳过）；`rebuildGlResources` **不再清** `nearCellCache` / `farCellCache` / `corridorCellCache` / `vegPackedCache`（纯 CPU 数据，与 GL 无关）⇒ 只重传缓冲（~100 ms） |
| 4 | 建格顺序是 `dz/dx` 自然序（从左上角开始）⇒ 分帧后相机脚下可能是空的 | `nearOrder` 按到中心的距离排序；远景用 `FAR_SLOT_ORDER`（中心 → 四边 → 四角） |

**⛔ 必须同时改的配套逻辑**（漏一条就会静默卡住）：

- **续建标志**：分帧之后「没跨格」≠「已建完」⇒ `maybeRebuildMesh` 的提前返回必须带上
  `nearPending / corridorPending / farPending`，否则续建永远不发生；
- **近景旧槽必须擦**：合并索引是**固定槽基址**（不按 key 过滤），预算用完而槽里还留着
  上一格的顶点 ⇒ 会继续画旧格（跨格时错位 10 米）。用 `nearParkedStaging` 写退化三角形；
- **远景跨格先作废槽键**：否则 `rebuildFarIndices` 会把上一格的槽当有效格画出来（错位 700 米）。
  作废后 `farSlotsReady` 用「已填槽数」⇒ 远景逐格出现，而不是闪一下；
- **`farSlotsReady` 语义改了**：原来要求 9 格全满才画，现在按已填数画
  （`HighwayFarHole` 本来就跳过 `EMPTY_SLOT`）⇒ 部分填充安全。

**代价（必须写清）**：**总 CPU 工作量一分不少**。7.7 秒的建格时间照付，
分帧换来的是「**期间有画面**」（地形从相机脚下逐块长出来），不是「更快建完」。
真要更快只能减少格数或降低 `farRes`（画质取舍，所有者口径是保画质）。

## 2026-11-01 第七轮：真机四项（近处白线断线 / 树冠仍上路面 / 加载先天空色 / 帧率显示 59）

第六轮上机后的四条反馈。取证材料 = `logs_temp/e52_tv_logcat_r6.log`（417 行）
+ `logs_temp/e52_r6_shot1.png` / `e52_r6_shot2.png`（1920×1080）。

### ① 白线「到了近处断线和缺损」—— ribbon 只从车辆节点往前铺

⚠️ **这和第六轮的「整段缺失」不是同一个 bug**：那次是 polygon offset 被地形抢深度（已修），
这次是**几何上根本没有 ribbon**。

**取证**（像素级）：把 `e52_r6_shot2.png` 底部 400 行放大（`logs_temp/crop_near.png`）⇒
**左侧边线到画面底部以上约 200 px 处戛然而止**，往下只剩一片灰面、没有白线；
右侧边线同理。灰面不是路面，是**被涂成 `roadCol` 的地形**。

**根因**：`HighwayRoadRibbonBuilder.segments(anchor)` 只从锚点**往前**铺
（10 个远景段 + 4 个近景段），**锚点之后一格都没有**。而追随相机的 eye 在
`nodeFrac ≈ 0` 时位于车辆节点**后方约 2.2 米**：

```
diag#0 eye=(36.65,83.24,25.25) fwd=(0.731,-0.296,0.615) vehicle=(37.96,81.41,26.35)
       (eye − vehicle)·fwd = −2.18  ⇒ eye 在车辆节点**后方** 2.18 m
diag#4 eye=(42.23,83.24,30.04) vehicle=(37.96,…)  ⇒ (eye − vehicle)·fwd = +4.85（相位走到 0.7）
```

⇒ 屏幕最下方那一截路面**没有 ribbon**，露出的是地形；地形在 `roadProx < −0.2` 时
被着色成 `roadCol = vec4(0.45,0.45,0.474)`，与沥青底色几乎同色 ⇒
**看上去「路面在、白线没了」**。

**修法**：`buildRibbon()` 的锚点沿 `prev` 回退 `RIBBON_ANCHOR_BACK = 1` 个节点（10 米），
见 `HighwayGlScene.ribbonAnchor()`。10 米对 2.25 米有 4 倍余量；
`trimBehind()` 保留 `TRIM_BEHIND = 60` 个车后节点 ⇒ `prev` 一定非空，`?:` 兜底退化不崩。

**代价**：近景段覆盖从「车前 40 节点」→ 30 节点（300 m），远景段 100 → 90 节点（900 m，仍远超 `FOG_FAR`）。

### ② 树冠仍「偶尔延申到道路上」—— billboard 的朝向使悬挑 1 米 = 进路面 1 米

第六轮加了树冠判定，但留了 `TREE_CANOPY_OVERHANG = 1.0`（允许树冠压到路缘**外侧** 1 米）。
这个「悬挑」在 billboard 上不成立：

树是 billboard，quad 每帧**绕 Y 轴朝相机旋转** ⇒ 它在世界水平面上的投影是一条
**穿过树根、垂直于视线水平方向**、长度 `2·canopyHalf` 的线段。
相机沿路看过去时，「垂直于视线」恰好就是**横穿路面**的方向 ⇒
树根离路缘还有 `canopyHalf − ε` 时，树冠尖端**已经越过白线**。
这个最坏朝向与相机位置无关地**一定会出现**（车往前开，视线方向一直在变）。

**修法**：判定改成**径向全宽 + 余量**，不再允许悬挑：

```kotlin
if (c.roadGap < canopyHalf + TREE_CANOPY_MARGIN) continue   // TREE_CANOPY_MARGIN = 0.5
```

`roadGap` 是到 `q.w + q.e`（路缘 + 路肩）的净距，比白线还靠外 ⇒ 满足上式时
树冠最多压到路肩，**绝不会进沥青面**。判定位置仍在 `nn.next()` 之后（PRNG 序列不变）。

### ③ 「加载先出天空色，然后一次性都全部出现」—— 首帧 veg 1479 ms 是最大单笔

第六轮分帧后首帧 `terrain rebuilt in 2068 ms`，拆开看：

```
nearBuild=110ms  corridor=8ms  farBuild=421ms  veg=1479ms
```

**`veg` 一个人占了 71%** —— 冷启动时 `lastVegKeys`（≈145 格）全部未命中，
`uploadVegetation()` 一次性序列化完（每株草 2 次三角函数 + 18 个 float）。
地形已经分帧了，植被没有 ⇒ 用户看到的就是「天空色 →（2 秒）→ 全部一起出现」。

**修法**：`uploadVegetation()` 也按**时间**分帧（`VEG_PACK_BUDGET_MS = 80`）。
⚠️ **不能按格数分帧**：单格成本差 20 倍（空草原格 vs 密林格），按格数会出现
「这帧 10 ms、下帧 300 ms」。配套：

- 拼装阶段只拼 `vegPackedCache` 里**已序列化**的格（未轮到的这一帧先不出 ⇒ 植被逐块长出来）；
- `vegUploadedKeys = lastVegKeys` **只在 `!morePending` 时**才记（否则下一帧直接早退，
  永远补齐不了、静默卡在残缺状态）；
- 「植被 树=/草=/灌木=」日志也只在补齐完成时打一次（否则刷屏）。

### ④ 「显示帧率还是 59，实际卡顿」—— 徽章测的不是 GL

**右上角那个 `52.5 fps` 是我们自己画的 `FpsBadge`**（`VisualizerStage.kt:820`，
由 `Settings.Global` 的 `nasmusic_fps` 开关控制），**不是电视 OSD**。
它累计在 `withFrameNanos` 里 ⇒ 量的是 **Compose 的 Choreographer 回调率**；
而 E52 走独立 `GLSurfaceView` + `RENDERMODE_CONTINUOUSLY` ⇒ **两者完全解耦**。
主线程很闲（`phasesMs` 合计只有 ~2 ms）⇒ 徽章稳稳跑满面板刷新率，
而 GL 侧真实帧率是 `1000 / 154.3 ms ≈ 6.5 fps`。

⇒ **不是测量 bug，是测量对象错了**。修法：

- `VisualizerRenderer` 新增 `fun renderFps(): Float = 0f`（默认 0 = 未知）；
- `HighwayGLSurfaceView.currentFps()` → `HighwayGlRenderer.lastFps`（`publishStats()` 里写）；
- `HighwayRenderer` 覆盖 `renderFps()`；舞台 `renderFps() > 0` 就优先用它，
  否则退回 Choreographer 口径。

### ⑤ 卡顿的量化归因（本轮新增，为下一步定位）

**电视侧排除项**：`/data/app/com.nasmusic.tv-2/oat/` 不存在曾让人怀疑「没 AOT ⇒ 解释执行」，
但对照检查发现**这台 ROM 把 oat 放在 `/data/dalvik-cache/arm/`**
（`data@app@com.nasmusic.tv-2@base.apk@classes.dex`，62 MB，安装时生成）
⇒ **确实是 AOT 编译过的**，`27 ms/格` 就是这台 CPU 的真实速度，不是配置问题。

`logs_temp/e52_tv_logcat_r6.log` 的 `frame stats`：

```
n=240 avg=154.3 max=823.5 ms maxPhasesMs=0.8/303.0/0.2/0.3/0.1/0.4 hitches=240
                          ↑TICK ↑MESH   ↑SKY  ↑TERR ↑ROAD ↑VEG
```

两个**独立**成因，都被相位数据钉死：

| 成因 | 证据 | 结论 |
|---|---|---|
| **A. 每 0.6~0.7 秒一次 300~400 ms 硬卡** | 所有 >200 ms 的帧里 `PHASE_MESH` 占 **97~99%**（`frame=823.5 phases=2.9/813.9`）；`terrain rebuilt` 在 249 帧里出现 70 次，`nearBuild` 稳定 160~220 ms | CPU 侧建格，**在 GL 线程同步做** |
| **B. 基线 ~83 ms/帧（≈12 fps）** | 无建格的帧：`frame=84.8 phases=0.8/0.2/…` ⇒ 相位合计 **1.8 ms**，剩下 83 ms 全是 swap 等 GPU | **GPU 填充率瓶颈**，与 CPU 计算无关 |

⇒ 「帧率显示 59」解释见 ④；真实体感卡顿 = A（周期性硬卡）+ B（持续低帧率）。

**A 的下一步**：把建格搬出 GL 线程（真后台线程）。总 CPU 量 ≈ 594 ms/s
（11 近景格/0.5 s × 27 ms），而 GPU 每帧要等 83 ms ⇒ **CPU 与 GPU 可以重叠**，装得下。

**B 的下一步**：AB 实测定位（每次只改一个变量）：`renderScale` 0.40→0.30 / 关植被 / 关地形。
首要嫌疑是植被 overdraw —— `grass ≈ 18000` 个 alpha-test billboard 无距离剔除。

**为定位新增的剖面**（`terrain/HighwayMeshProfile.kt`，临时诊断，验收后整文件删）：
把「建格」拆成 `curv / node / treemask / gen / norm` 五段（**每格**平均 µs），
并把 `writeCellFloats + uploadSlot` 单独计时（`upload=N slots/M ms(us/slot)`）——
因为 `glBufferSubData` 在 VBO 正被上一帧使用时会被驱动**隐式同步**，
那段时间也算在 GL 线程的 CPU 时间里，会**伪装成「建格很慢」**。

## 2026-11-01 第七轮（续）：卡顿的两条解法

### 关键量化事实：**预取的开销是免费的**

把 249 个 hitch 帧按「有没有建格峰」分成两组（`logs_temp/e52_tv_logcat_r6.log`）：

| 帧类 | 帧数 | `frameMs` 中位 | `PHASE_MESH` 中位 |
|---|---|---|---|
| 无建格峰 | 180 | **83.6 ms** | 24.0 ms |
| 有建格峰 | 69 | **296.0 ms** | 279.8 ms |

逐帧对照更能说明问题：

```
frame=84.3  mesh=23.2   ← 预取建了 1 格（≈25 ms CPU）
frame=84.8  mesh= 0.2   ← 没建格
```

**两帧的 `frameMs` 一模一样** ⇒ `frameMs = max(CPU, GPU)`（不是相加），
25 ms 的 CPU 建格被 **GPU 的 83 ms 等待完全吸收**。而 280 ms 的跨格峰值超过
GPU 预算 ⇒ 吸收不了 ⇒ 硬卡。

⇒ **结论：多预取免费，少预取才卡。** 之前 `NEAR_PREFETCH_PER_FRAME = 1` +
`PREFETCH_LOOKAHEAD_M = 8` 是供给不足：

- 跨一格要 11 格、周期 0.5 s、12 fps ⇒ 只有 6 帧 ⇒ 1 格/帧 = 6 格 < 11 格 ⇒ **必差 5 格**；
- 8 米提前量 ≈ 5 帧，比「建满 11 格需要的 6 帧」还短 ⇒ 预取等于没提前。

### A 类（300 ms 硬卡）—— 修法：让预取跟得上

| 改动 | 从 | 到 | 依据 |
|---|---|---|---|
| `NEAR_PREFETCH_PER_FRAME` | 1 | **2** | 2×25 = 50 ms，仍 < 83 ms 的 GPU 基线 ⇒ 免费；6 帧 × 2 = 12 格 ≥ 11 格 |
| `PREFETCH_LOOKAHEAD_M` | 8 m | **25 m** | 12 fps 下一帧 1.6 m ⇒ 25 m ≈ **15 帧**提前量，富余到 30 格 |
| 预取顺序 | `dz/dx` 自然序 | **`nearOrder`（由近到远）** | 自然序先建左上角，而先进入视野的是中心那几列 |
| 续建帧跳过预取 | — | `if (!nearPending)` | 那一帧 CPU 已被 8 格占满，再叠 2 格只把峰值推高 |

**兜底**（预取万一漏掉时把「一顿」变成「慢一下」）：
`NEAR_BUILD_PER_FRAME` 8 → **4**、`CORRIDOR_BUILD_PER_FRAME` 24 → **12**
⇒ 单帧峰值从 ~280 ms 降到 ~96+67 = **~165 ms**，且摊到 3 帧。

⛔ 上限：`NEAR_PREFETCH_PER_FRAME` 不要超过 3。3 格 ≈ 75 ms 已贴近 GPU 基线，
GPU 一旦变快（或降到 0.30）CPU 会变成新瓶颈，帧率反而不升。

### B 类（GPU 83 ms）—— 修法：先量化再动

`frameMs` 83 ms 而 `phasesMs` 合计 ~2 ms ⇒ 全在 GPU。**归因不能靠猜**，
所以加了两个 `Settings.Global` 开关，**不用重新编译**就能 AB 实测：

```
# 图层开关（1=天空 2=地形 4=路面 8=植被，默认 15）
adb shell settings put global nasmusic_hw_mask 7     # 关植被
adb shell settings put global nasmusic_hw_mask 13    # 关地形
adb shell settings put global nasmusic_hw_mask 11    # 关路面
adb shell settings put global nasmusic_hw_mask 15    # 全开

# 渲染分辨率覆盖（百分比，0 = 取消）
adb shell settings put global nasmusic_hw_scale 30   # renderScale 0.30
```

每次改完**退出再进效果**（只在 `onSurfaceCreated` / 构造时读一次），
然后看 `stats {"fps":..}` 的 `fps`。

**首要嫌疑**：植被 —— `grass ≈ 18000` 个 alpha-test billboard、**没有距离剔除**，
而 alpha test 的 `discard` 会关掉 PowerVR 的早期深度剔除 ⇒ 全屏 overdraw。
若 `mask=7`（关植被）让 fps 明显跳上去，下一步就是给草加距离剔除（保留树）。

## 2026-11-01 第八轮：第七轮真机复验（白线仍在 / 多边形缝隙 / 远景空窗 / 帧率仍 59）

第七轮装包后真机反馈四条，逐条取证。**结论：前三条各有独立根因，且都不是上一轮修的那个。**

### ① 白线「到了近处有断线和缺损」—— 地形从**上方**盖住路面（polygon offset 压不住）

上一轮修的是「ribbon 只从锚点往前铺 ⇒ 相机后方没路面」，已生效（截图确认底部有路面）。
这一轮看到的是**另一种**：`logs_temp/e52_r7_shot5.png` 左侧一段棕色土坡**直接压在路面上**，
白线整段消失；`shot3` 左侧也有同款缺口。

根因：`lerpHeight` 的切槽在**山坡路段**是线性插值 `terrainHeight*lt + q.y*(1-lt)`，
`lt` 中段（0.2~0.8）时地形高度仍可能**高出路面 1~5 米**。
`glPolygonOffset` 只在**共面**时有效（`-4/-8` 顶不住米级高差）⇒ 地形赢。

修法（`HighwayGlsl.TERRAIN_VERTEX_SHADER`）：顶点阶段按 `aRoadProx` 再压一次：

```glsl
float roadSink = clamp(-aRoadProx, 0.0, 2.0) * 0.5;   // 路面内最多压 1 米
transformed.y -= roadSink;
height -= roadSink;
```

⛔ **只在 `aRoadProx < 0` 时压**：远景层 `roadProximity` 恒 0（无切槽），
对 0 也压会让远景整体下沉、与走廊接缝错位露缝。
⛔ 不用片元 `discard`：路面 ribbon 万一有缺口会直接露出天空色，压顶点则不会。

### ② 「远处有多边形拼接的缝隙，露出后面的底色，到了近处会补全」

根因：**走廊层最外圈仍在切槽过渡带里**。

- 切槽消失点 = `q.w + rm`；平坦路段 `q.ga → 1` ⇒ `clamp(ga, .4, .75) = .75` ⇒ `rm = 16`，
  而 `q.w = node.w + 0.4 + ga ≈ 5` ⇒ **消失点 ≈ 21 米**。
- 走廊半宽只有 **20 米**（`HighwayCorridor.HALF_WIDTH = 2` 格）。
- ⇒ 走廊最外一圈高度**低于**远景层（远景无切槽、纯地形）⇒ 洞的边界处高度不连续 ⇒ 三角缝。
- 近景方窗（near，±50 米）把缝盖住 ⇒ **「远处有缝、近处补全」**。

修法（`HighwayRoadQuery.lerpHeight`）：把 `rm` 上限压进走廊内。

```kotlin
val rm = minOf(4 + 16 * minOf(0.75, maxOf(0.4, q.ga)),
               maxOf(0.0, CUT_MAX_RADIUS - q.w))   // CUT_MAX_RADIUS = 19.0
```

⇒ 切槽最远到 19 米 < 20 米 ⇒ 走廊最外圈一定是**纯地形高度**，与远景逐位相同。
代价只是路肩切槽略窄（13~19 米），观感无变化。

### ③ 「远处有缝隙露出底色」的第二个来源：远景跨格时整片清空

`maybeRebuildMesh` 里跨格时 `Arrays.fill(farSlotKeys, EMPTY_SLOT)` 把 9 格**全部作废**，
而远景预算是 **1 格/帧** ⇒ 接下来 9 帧远景只剩 1~2 格 ⇒ 大片远景空掉。

修法：**跨格时不清空槽键**。旧几何继续画（洞是按「近景方窗 ∪ 走廊」算的，
与 far 窗口原点无关 ⇒ 旧几何照样被正确打洞），新格建好后逐格替换。
旧格位置差 700 米，但远景全在雾里，过渡期看不出来。

### ④ 「帧率还是 60」—— `lastFps` 24 秒才刷新一次

链路（`VisualizerRenderer.renderFps()` → `HighwayRenderer` → `HighwayGLSurfaceView.currentFps()`）
代码都在，但 `lastFps` 只在 `publishStats()` 里赋值，而那是 **每 120 帧**一次。
真机 ~5 fps ⇒ **24 秒**才刷新 ⇒ 前 24 秒 `renderFps()` 恒 0 ⇒ 舞台退回 Choreographer
口径 ⇒ 徽章照旧显示 59。

修法：
- `recordFrameTime` 里**逐帧**更新指数平滑 `emaFrameMs`（系数 0.85/0.15）⇒ 1 秒内收敛；
- `HighwayRenderer.renderFps()` 兜底走 `HighwayGlHolder.current()`（`onViewDetached`
  之后 `surface` 为 null 的那段也不会返回 0）。

### ⑥ 「树漂浮在路面上方」—— 树的高度取了最近顶点，不是插值

`placeTrees` 里落点是格内**连续**坐标（`c.px` / `c.pz` 是 0..1 归一化），
但高度直接取 `cell.heightOffsets[row * res1 + col]`（**最近的顶点**）。
顶点间距 0.5 米，在**切槽边界（路缘）**与陡坡上相邻顶点高差可达 1~3 米
⇒ 取到偏高的那个顶点，树就浮在草地/路面上方。

修法：**只改落地高度** —— `posY` 用 `bilinear()` 插值出的高度，**过滤判据仍用顶点值**。
⛔ 草（`placeFoliage`）**不用改** —— 它是按顶点逐个生成的，天然落在顶点上。

**⛔⛔ 过滤判据（`grad` / `height`）不能用插值**：上游判据就是顶点网格下标 `i` 上的
`grads[i] < 0.2 && heights[i] > 1`，换口径等于改算法；而且 `nn`（树形 PRNG）是
`HighwayVegetationPlacer` 的成员、**跨格共享**，只在**通过过滤之后**才被 `nn.next()` 消费
⇒ 改过滤口径会改变 `nn` 的消费次数 ⇒ 后续所有格的缩放错位。

#### ⚠️ 更正（第八轮复盘）：3 例全红的真正根因**不是** PRNG 错位

上一轮把「3 例全红」归因成「插值过滤 ⇒ `nn` 错位」，**这条归因是错的**（改回顶点值后仍然全红）。
真正原因在本轮新加的**树冠判定用了 `continue` 丢弃**：

`hb` 的真实值域是 **`[0.8, 1.2) · tms`**（`nn = Cs(101, 0.4)` ⇒ `nn.next() ∈ [0, 0.4)`，
再 `-0.2` ⇒ `[-0.2, 0.2)`），**不是** `[0.8, 1.8) · tms`。上一轮按 1.8 倍算，
把 `canopyHalf` 高估了 50%。

| `treeDensity` | `tms` | `hb` 实际 | `canopyHalf = 4·hb` | 允许它的 `roadGap` |
|---|---|---|---|---|
| 0（疏林） | 0.85 | [0.68, 1.02) | 2.7 ~ 4.1 m | ≥ 3.2 m |
| 1.25（实测） | 1.4125 | [1.13, 1.695) | 4.5 ~ 6.8 m | ≥ 5.0 m |
| 3（密林） | 2.20 | [1.76, 2.64) | 7.0 ~ 10.6 m | ≥ 7.5 m |

而近景层（相机 ±5 格）**全在路边**，`roadGap` 普遍只有 3~6 米 ⇒
`roadGap ≤ 5` 米就要求 `hb ≤ 1.125`，而 `td = 1.25` 时 `hb` **下界正好 1.13**
⇒ **路边一格一棵都过不去**，整片森林被砍光。

**正确修法 = 缩小，不是丢弃**：
`hbFit = (roadGap − 0.5) / 4`，`scale = min(hb, hbFit)`；
`hbFit < MIN_TREE_SCALE(0.4)` 时才丢弃（即 `roadGap < 2.1` 米，几乎不触发）。
树干位置不变、数量不变、尺寸随路距平滑变大。
`nn.next()` 仍在判定之前消费 ⇒ PRNG 序列与上游一致。

诊断口径（真机/单测都适用）：把「有候选但全被过滤」那一格的 `hVtx / gVtx / hInterp / gap /
treeDensity` 打进断言消息，一眼就能算出 `tms`、`hb ∈ [0.8, 1.2)·tms`、
`canopyHalf = 4·hb` 与 `gap` 的关系。

门禁的期望值改为**直接调用生产实现**（`bilinear` / `canopyFitScale` / `roadGapAt`，
全部 `internal` 可见），不在测试里复制算法 —— 否则改生产公式门禁不会红。

### ⑦ 卡顿：`getXZ` 19.6 µs/次 —— 剖面已定位到函数，但**还没定到行**

`mesh prof` 真机数据（第七轮）：

```
cells=25 built=4 reused=7 curv=391us/cell node=568us/cell treemask=109us/cell
gen=13211us/cell norm=3058us/cell sum=17339us/cell upload=11slots/4ms(409us/slot)
```

- **`gen` 占 76%**（13.2 ms/格，441 顶点 ⇒ 30 µs/顶点）。
- **`upload` 只有 4 ms** ⇒ **不是 `glBufferSubData` 被 GPU 隐式同步**，上一轮的怀疑排除。
- `curv` 段是 20 次 `getXZ` = 391 µs ⇒ **19.6 µs/次**（正常应 ~0.2 µs）。
  `gen` 的 441 顶点 × ~1.5 次 ≈ 660 次 × 19.6 µs ≈ 13 ms ⇒ **完全对上**。

⇒ **瓶颈就是 `HighwayHeightMap.getXZ` 单次 19.6 µs。**

剩下两种可能，本轮加了计数器一次定性（`mesh prof ... | cells lookups=N generates=M`）：

| 现象 | 结论 | 下一步 |
|---|---|---|
| `generates` 与 `lookups` 同量级 | **缓存 thrashing**（`maxCached = 9` 不够 / `clearCache` 误删） | 改缓存策略 |
| `generates` 远小于 `lookups` | 查表本身慢（双重 `ArrayList.get` + `Long` 装箱） | 扁平化 `HighwayCell` 存储 |

⛔ 不要在拿到这行之前动 `getXZ` —— 上一轮就是因为没数据把「上传阻塞」当成了根因。

### ⑧ 门禁 3 例全红：树冠判定用 `continue` 把路边整格砍空

**现象**：`run_e52_r8.bat` 连跑三轮，`HighwayVegetationGateTest` 的
`treeLandingHeightUsesCellHeightNotHeightmap` / `treeSlopeFilterUsesSmoothedGrads` /
`treeWorldPositionFollowsCellOffset` 三例同时红，断言都是「应该至少校验到一棵树」。

**取证**（把一格的数据打进断言消息）：

```
cell(-24,125) res=20 cands=1 px=0.92 pz=0.59 hVtx=126.53 gVtx=0.1875 hInterp=126.70
              gap=5.6596 treeDensity=1.25
```

⇒ `tms = 1.4125`、`hb ∈ [1.13, 1.695)`、`canopyHalf ∈ [4.5, 6.8)`，
而判定式要求 `roadGap ≥ canopyHalf + 0.5` ⇒ 需要 `hb ≤ 1.29`。

**根因**：`hb` 的真实值域是 **`[0.8, 1.2)·tms`**（上一轮按 1.8 倍算，树冠高估 50%）。
`roadGap ≤ 5` 米就要求 `hb ≤ 1.125`，而 `td = 1.25` 时 `hb` **下界正好 1.13** ⇒
**路边一格一棵都过不去**。而近景层是相机 ±5 格、**全在路边** ⇒ 整片森林被砍光。

**修法**（`HighwayVegetation.kt`）：

```kotlin
val hb = tms + (nn.next() - 0.2) * tms        // 上游原值，PRNG 消费次序不变
val hbFit = canopyFitScale(c.roadGap)          // (roadGap - 0.5) / 4
if (hbFit < MIN_TREE_SCALE) continue           // 只有 roadGap < 2.1 米才真的丢弃
val scale = min(hb, hbFit)                     // ← 缩小到刚好放下
```

- 树干位置不变、**数量不变**，尺寸随路距平滑变大（路边 3~6 m、远处 13 m），观感更自然；
- `TREE_ROAD_CLEARANCE` 从 3 回到 **2**（= 上游 `> 2`），把树的数量还回来；
- `canopyFitScale` 放进 **companion object**（无状态纯函数，门禁不用建世界就能扫）。

**门禁新增 3 例**（都直接调生产实现，⛔ 不复制公式）：

| 用例 | 判据 |
|---|---|
| `treeCanopyNeverOverhangsRoad` | 端到端：把每棵已放下的树用 `roadGapAt()` 反查净空，验 `4·scale + 0.5 ≤ roadGap` |
| `everyRoadsideCandidateFitsAtLeastMinTree` | 纯函数：`canopyFitScale(TREE_ROAD_CLEARANCE + 0.3) ≥ MIN_TREE_SCALE` ⇒ **「缩小」这条路永远走得通**，禁止再靠丢弃过判据 |
| `canopyFitScaleIsMonotonicAndSafe` | 0.1~60 m 扫描：单调不减 + 不越界不变式 |

**验证（2026-11-01 14:20）**：`HighwayVegetationGateTest` **10 例 / 0 失败 / 0 跳过**
（XML 时间戳 14:20:12，晚于源码 14:15:13 ⇒ 确实是本轮代码）；
`assembleRelease` BUILD SUCCESSFUL（14:33，40,472,802 B，md5 `ebe07ff555e64887706b6fb276f702b6`）。
⛔ 判据必须看 **XML 的 mtime 晚于源码 mtime** —— 只看 `EXIT=0` 会被 UP-TO-DATE 骗。

## 2026-11-01 第九轮（R9 真机复验）：白线已修，其余三项未过

包：`NASMusicTV-release-v2-39-10.apk`（40,472,802 B，md5 `ebe07ff555e64887706b6fb276f702b6`），
14:42 已 `install -r` 到电视（机上 `lastUpdateTime=2026-11-01 14:42:23`、`base.apk` 字节数一致），`nasmusic_fps=1` 已开、`logcat -c` 已清。
复验结论（用户原话）：**① 白线问题解决了；② 树在路上的情况还有；③ 显示帧率还是 59，电视卡顿还在，手机很流畅；④ 远处多边形缝隙偶尔露底色还在。**

### ① 白线 —— ✅ 已修（本轮确认）
顶点着色器 `roadSink = clamp(-aRoadProx,0,2)*0.5` 把路面内的地形压下去，左右白线**连续到画面底部**，不再有缺损段。第八轮的修法生效。

### ② 树还在路上 —— ❌ 未过（最高优先，下一步主攻）
缩小不丢弃的修法（`scale = min(hb, canopyFitScale(roadGap))`）在单测里 10/10 通过，
但真机仍能看到树落在路面上。矛盾点：**R9 的 `hitch` 日志里 `vegTree=0`**（每帧重建的树实例数 = 0），
而用户却看到树在路面上 ⇒ 怀疑两类来源之一：
- (a) 树来自**近景/走廊层**某条 LOD 路径，没走 `placeTrees` 的缩小判据；
- (b) 树确实被放下，但 `vegTree` 计的是「当前重建格内」的树，树在别的格、hitch 只采到无树格。
⛔ 下一步：先抓一张「树在路面上」的截图 + 该帧 `cell stats … treeLod[…]`（若日志缺这行要先补），
确认树是从哪条路径放出来的，再决定改 `placeTrees` 还是补另一层 LOD 的净空。

### ③ 帧率徽章显示 59，实际卡 —— ❌ 未过（已定位：徽章没接 GL 帧率）
**真机 `stats` 实测 GL 真实帧率 = 7~8 fps**（EMA 修复在 renderer 层生效了）：
```
stats {"frames":1080,"fps":6.574484,"frameMsAvg":152.10,"frameMsP95":363.88,"viewport":768x432}
stats {"frames":1200,"fps":7.879989,"frameMsAvg":126.90,"frameMsP95":245.37,"viewport":768x432}
stats {"frames":1320,"fps":7.787396,"frameMsAvg":128.41,"frameMsP95":242.49,"viewport":768x432}
```
但徽章仍显示 **59** ⇒ 徽章读的是 Compose **Choreographer**（UI 线程节奏，电视/手机都 ~60），
根本没接 `HighwayRenderer.renderFps()` 返回的真值。手机流畅是因为手机 GL 帧率高（徽章碰巧对），
电视 GL 只有 7fps 但 UI 线程仍 60 ⇒ 徽章假绿。
**修法**：`FpsBadge` / `VisualizerStage` 改成读 `VisualizerRenderer.renderFps()`（GL 线程口径），不是 Choreographer。
⛔ 这不解决卡顿本身，只让徽章显示真实数字（用户厌烦「假 59」）。

### ④ 远处多边形缝隙偶尔露底色 —— ❌ 未过（部分缓解，仍有偶发）
第八轮两处修法（切槽半径加 19m 上限 + 远景跨格不再整片清槽键）**减少了**缝隙频率，
但用户仍观察到「远处偶尔露出底色、近处补全」。定性：接缝露底色 = 相邻两层/两格在边界处
**高度不连续 1~数米**，远观看到底色（天空/远地形）。`roadSink` 只压路面内、不压两层交界。
⛔ 下一步：量一下缝隙出现时相机到缝隙的距离、是 terrain↔terrain 还是 terrain↔far 交界，
再决定是补一层软过渡（z-fighting/stitch）还是把交界格高度强制对齐到相邻层。

### ⑤ 卡顿根因（本轮量化，首次拿到完整证据链）
**`mesh prof … | cells lookups=12.2M generates=14`** —— 高度图缓存**只有 14 个格**，
却被查了 **1200 万次**。两种旧假设中：
- ❌ 「缓存 thrashing」不成立：`generates=14` 说明 14 格全程稳定驻留，没被反复淘汰；
- ✅ 「查表本身慢」成立：`curv=263~319 µs/cell` = 20 次 `getXZ` ⇒ **~15 µs/getXZ**，
  是正常应 ~0.2 µs 的 **~75 倍**。

`hitch` 日志佐证 GL 绘制相位是瓶颈：
```
hitch frameMs=261.3 phasesMs=0.8/251.4/0.2/0.6/0.1/0.2 … vegTree=0 grass=11047 bush=19
```
（`phasesMs` 第二项 30~251 ms = GL draw 相位，占整帧 90%+；`grass=11047` 是另一大嫌疑 —— 11000 个草 billboard 的 overdraw）。

**卡顿 = CPU 侧 `getXZ` 慢（15µs×海量调用）+ GPU 侧草/树 billboard overdraw 双重压力**，二者叠加把 GL 帧率压到 7fps。
⛔ 在动手前必须再拆一层：
1. `getCell` 单次为什么 75× 慢 —— 嫌疑是 `HighwayHeightMap.getCell` 里
   `cacheAccess.getOrPut(ox){LinkedHashMap()}[oz]=true` 每查一次都做一次 HashMap 写 + 双 map 维护；
   另有嫌疑是 `getXZ` 给的 `ox/oz` 取整方式与缓存键不一致导致**重路径**查不到缓存。
2. 草 `grass=11047` 是否 overdraw 主导 —— 需按约定做 GPU 侧 AB（`nasmusic_hw_mask` 关植被对比 `frameMsAvg`）。

### 本轮诊断遗留（未清理，继续用）
`mesh prof` 的 `cells lookups/generates`、`HighwayMeshProfile.kt`、`hitch` 的 `vegTree/grass/bush` 计数、
EMA 逐帧 `lastFps` 都还留在代码里 —— 按约定留到「四项全验收」之后再删。
⛔ 不要因为 `vegTree=0` 就以为树没了：那是重建采样计数，与用户肉眼看到的「树在路上」矛盾，必须先截图定位。

## 2026-11-01 第十轮（离线）：树冠越界根因定位 + `getXZ` 查表加速

> 本轮**无真机**（`adb devices` 为空，电视 192.168.0.114:5555 未连接），
> 只做**纯 JVM 可验证**的工作：把 R9 遗留的 ② / ⑤ 两项在离线侧定性、修复并回归。

### ① 树仍在路上的根因：树冠横向半径取小了 **2.24 倍**（本轮定性并修复）

R9 遗留的矛盾（`vegTree=0` 却肉眼见树在路上）本轮用**离线探针**定论 = **不是 LOD 路径问题**：
`placeTrees` 是唯一的放树路径（近景 `nearCell` 与走廊 `corridorCell` 都走它）。
真正的原因是 `canopyFitScale` 用的**横向半径是 0.5**，而植被 mesh 的真实水平半径是
`hypot(0.5, 1) = 1.118`（`POSITIONS` 第二片**斜 quad** 的角点 `(0.5, 0, 1)`）。

探针（临时 JVM 用例，跑完即删；沿路 600 节点、只取 `LOD != 0` 的格）：

| 半径口径 | 越界棵数 | 最差超出路缘 |
|---|---|---|
| 0.5（旧） | **0 / 14** | 0.0 m（所以旧单测 10/10 全绿） |
| **1.118（真实）** | **14 / 14** | **+6.38 m** |

⇒ 旧公式**漏判 2.24 倍**，路边每一棵树的树底斜 quad 其实都压在路上。

**修法**（`vegetation/HighwayVegetation.kt`）：
- 新增 `HighwayVegetationGeometry.HORIZONTAL_RADIUS_UNITS`：由 `POSITIONS` **现算**
  `max hypot(x, z)`（几何一改判据自动跟着改，不会「改了顶点忘了改阈值」）；
- `canopyFitScale(gap) = (gap − 0.5) / (TREE_SCALE × HORIZONTAL_RADIUS_UNITS)`；
- `MIN_TREE_SCALE` 0.4 → **0.2**（跟着新半径定：`canopyFitScale(2.3) = 0.201`，
  保证「刚过树干净空的候选一定放得下」，继续走「**缩小**」而不是「丢弃」）；
- `TREE_ROAD_CLEARANCE` 保持 2（上游口径）。

修复后同一探针：**越界 0 / 11，最差 0.0 m**（贴约束上界）；树 14 → 11 棵
（3 棵 `roadGap < 2.29` 的被丢弃），尺寸区间由 0.40~1.29 收窄到 **0.26~0.58**
（树高 2.1~4.6 m）。

⚠️ **观感代价**：路边树明显变小（原来最高 ~10 m）。保大只能放宽 `TREE_ROAD_CLEARANCE`，
代价是路边更稀疏 ⇒ **需真机复验观感是否可接受**。

门禁：`HighwayVegetationGateTest` 的树冠判据全部改用 `HORIZONTAL_RADIUS_UNITS`
（⛔ 不再写死 0.5），并新增一条 `HORIZONTAL_RADIUS_UNITS > 1.1` 钉死「不许写回 0.5」。

### ② 帧率徽章（R9 的 ③）——**代码已修，缺的只是重出包**（本轮用 git 定论）

R9 报告「徽章仍 59」的真正原因是**时序**：徽章修复（`VisualizerStage` 优先读 `renderFps()`）
与逐帧 EMA（`HighwayGLSurfaceView.recordFrameTime`）都落在 commit `618d090`（**14:57**），
而 R9 装的是 **14:33** 出的包 ⇒ **那版包里根本没有这两处改动**。

```
$ git log -S "renderFps()" --oneline -- .../ui/components/VisualizerStage.kt
618d090 fix(e52): 白线修复+树冠缩放门禁+帧率EMA；R9现状留档
$ git log -S "emaFrameMs" --oneline -- .../highway/HighwayGLSurfaceView.kt
618d090 fix(e52): 白线修复+树冠缩放门禁+帧率EMA；R9现状留档
```

⇒ **重新出包**再上机，徽章就会显示 GL 真实帧率。**本轮无需再改代码。**

### ③ 卡顿（R9 的 ⑤）：`getXZ` 单次 ~15 µs —— 加 8 路 memo，绕过双层 map

R9 证据：`mesh prof … cells lookups=12.2M generates=14` ⇒ **不是** thrashing，
是**查表本身慢**；建一格的 `gen`（441 顶点 × ~1.5 次 `getXZ` ≈ 660 次）就吃掉 **13 ms**
（`gen=13211us/cell`，占 76%）。

**修法**（`world/HighwayHeightMap.kt`）：`getCell` 加 **8 路直接映射 memo**
（命中时**完全跳过**两层 `LinkedHashMap` 的 `getOrPut` 与每次 2 次 `Long` 装箱）。
一格内 441 顶点几乎全落同一 cell ⇒ 命中率 ≈ 100%。

⛔ **纯加速、不改数值**：`cacheCell` 的噪声只由 `seed+ox+oz` 决定（与淘汰次序无关），
memo 返回的就是同一个 cell 对象。判据：`HighwayWorldGateTest` **5/5 逐位相等**
（baseline 不变）⇒ 已证值不变。`cellLookups` 语义改为「只计慢路径」，
正好用来继续观测 memo 生效（真机应从 12.2M 掉几个数量级）。

⚠️ **仍未做**：草 `grass=11047` 的 overdraw 需真机 `nasmusic_hw_mask` AB
（关植被对比 `frameMsAvg`），离线无法定论。

### ④ 远处多边形缝（R9 的 ④）：本轮**不改**，先补测量（守住「先量再改」的约定）

R9 仍偶发露底色。离线只到「假设」级，不足以动手：
- 走廊最外圈（≈20 m）与远景的缝已被 `CUT_MAX_RADIUS=19` 压掉（第八轮）；
- 剩下最可能是**近景方窗（res20 / lod1）与走廊（res10 / lod0）交界的 T-junction**：
  两种分辨率对同一高度函数采样点不同 ⇒ 共用边存在米级裂缝（「近处补全」正是方窗盖上去之后）。
- ⛔ 按约定**先量交界类型再改**（离线无法量）。

### 本轮验证

- `./gradlew :app:testDebugUnitTest --tests "com.nasmusic.tv.visualizer.highway.*"`
  ⇒ **14 个类全绿、0 失败**（`HighwayWorldGateTest` 5、`HighwayVegetationGateTest` 10、
  `HighwayGlStateGateTest` 4、`HighwayCorridorGateTest` 6、`HighwayFarHoleGateTest` 5、
  `HighwayGlslParityTest` 5、`HighwayRoadChainRuntimeTest` 3 …）；`compileDebugKotlin` 无新增告警。
- 版本号仍 **195 / 2.39.10**；诊断探针（mesh prof / hitch / EMA）**保留**。

### 本轮未做（受限于无真机）

| 项 | 为什么没做 | 需要什么 |
|---|---|---|
| ② 树在路上的**真机确认** | 离线已证「按真实半径 14/14 越界」并修到 0，但**观感**只能肉眼 | 出包上机看路边一排树 |
| ③ 徽章真值 | 代码已修，缺的只是重出包 | 出包上机看右上角 fps ≈ 7~8 |
| ④ 远处缝 | 协议要求先量交界类型 | 出包上机 + 抓缝的截图与相机距离 |
| ⑤ 草 overdraw | 需 GPU AB | 出机 `nasmusic_hw_mask` 关植被量 `frameMsAvg` |

## 第十轮现状清单（2026-11-01 17:37）

> 来源：本轮真机取证（`output/e52_device/r10/` 的 4 张截图 + `highway_log.txt`）+ 代码逐处核对。
> 分四块：**A 已复现问题 / B 代码·文档不一致 / C 风险疑点 / D 未开发**。
> ⛔ 本清单**不替代**上面的「状态总览」；状态总览里与真机相反/与代码不符的行已在 §B 逐条更正。

### A. 现存问题（有真机证据）

| # | 问题 | 证据 | 状态 |
|---|---|---|---|
| A1 | 帧率徽章显示 59，真实 8.7 | 截图 50.5 / 59.4 fps；同刻 `stats {"fps":8.697,"frameMsAvg":114.98,"frameMsP95":218.6}` | 根因＝口径错。本轮已改代码，**未出包验证** |
| A2 | 性能不达标（8.7 fps） | `hitch` 488 帧：帧时 **p50=100 / p90=202 / max=385 ms**；相位均值 **MESH=69.4 ms**、draw 四相位合计 **1.1 ms**、未计入 46.2 ms；**GPU 地板帧 70% / CPU 建格帧 26%**；GLThread **62% 单核**（RenderThread 2%） | 归因已量化，真机 AB 未跑完 |
| A3 | 远处露底色缝 | 用户复现 | 未定位（疑 20 m 近景方窗 ↔ 10 m 走廊的 T-junction） |
| A4 | 跨格重建卡顿 | `terrain rebuilt in 40~199 ms`，143 次 / 840 帧 | 部分缓解，未根除 |
| A5 | 草 `grass=11976~12548`、无距离剔除 | `hitch` 行计数 | overdraw 首要嫌疑，未 AB |

### B. 代码 / 文档不一致（本轮已更正）

| # | 位置 | 更正 |
|---|---|---|
| B1 | 状态总览「显示帧率还是 59」原写 ✅ 已修（第七轮） | 改为 ❌ **未修** —— R9/R10 真机实测徽章 59 而 `stats` 8.7 |
| B2 | 状态总览「偶发镜头穿树」原写「净空 2 m → 3 m」 | 代码里 `TREE_ROAD_CLEARANCE = 2`（第八轮回退）⇒ **净空不是 3 m**；不压路改由「按真实半径缩小」承担 |
| B3 | 「树落在路面中间」「树冠偶尔延申」两行的半径 `0.5`（⇒ `4·hb`） | **更正为 `HORIZONTAL_RADIUS_UNITS = hypot(0.5,1) = 1.118`**（第二片斜 quad 的角点）⇒ 旧式漏判 **2.24 倍**（离线探针 0/14 → **14/14 越界、最差 +6.38 m**；修后 **0/11**） |
| B4 | 日志 `frame stats: maxPhasesMs=` 名不副实 | `HighwayGlScene` 该处调的是 `phaseBreakdown()`（**当前帧** `phaseNs`），**不是** `statMaxPhaseMs`（最大帧相位）⇒ 本轮分析曾被它误导过。**待随下次出包一并改**（改标签或改取值） |
| B5 | 诊断开关只在建面时读一次 | `nasmusic_hw_mask` / `nasmusic_hw_scale` 只在 `onSurfaceCreated` 读 ⇒ 每次 AB 都要退出重进；本轮新增**自驱 AB**（`nasmusic_e52_ab`）绕开它 |

### C. 风险 / 疑点（未验证）

- `MIN_TREE_SCALE` 0.4 → **0.2** ⇒ 最小树高 **1.6 m**，路缘树可能看起来像灌木（观感未确认）。
- `getXZ` 仍 **~11.5 µs/次**：8 路 memo 只解决 map 开销（`cells lookups` **12.2M → 13**，但 `curv` 只降 ~25%、`gen` 13.2 → **10.0 ms/格**）⇒ 残余在**每顶点 `qe` 沿链游走 + 嵌套 `ArrayList<CellPoint>` 指针追逐**。
- 8 路 memo 会短时持有已淘汰 cell（无正确性问题，轻微滞留）。
- 构建依赖本机 `D:/hxzhang/MyGithubSoftware/NASMusicTV-e51-local/src` 外部注入 E51 源码 ⇒ 换机 / CI 不可复现。
- 「树在路面」离线已证并修到 **0/11**，但**真机多时段复现率未确认**（本轮 4 张截图未复现）。
- 首帧已从 7718 ms 降到 2068 ms，仍偏长。

### D. 未开发（对照 `docs/archive/e52-highway-opengl-plan.md` §六 路线）

| 阶段 | 未开发内容 | 现状 |
|---|---|---|
| **P2 验收** | 黄金帧对账 G18a（SSIM ≥ 0.92）/ G18b；§十 四项遗留核验（R-11 上游三角数实测、three.js `outputEncoding`、`uBaseMap` 是否多乘一次、`fadeFiner` / `roadProx` 取值域） | P2 主体代码（PBR 变体 / 光照 π / 反照率顺序）已落地，**对账未做** |
| **P5** | 水面 + 模型（护栏 / 标志）；车与玩家座驾**不做**（D20 / O7） | 资产已在 manifest 94 项内（`roadster-07.obj`、`wall_barrier_*.png` 等），但**无加载 / 绘制代码** |
| **P6** | 环境集（2 场景 / 8 地形档 / 6 skin / 10 天气）+ 换歌三阶段雾白过场（D24） | `songId` 只在 `HighwayBridge` 里流转，**环境集与过场未实现** |
| **P7** | Planet 场景（D25 / K9） | 只有 `?topo=` 参数名（`HighwaySceneParams`），**无实现** |
| **P8** | 降级三级旋钮 + 自适应回退（§4.5）；三档切换无残留、30 min 无 OOM / EGL 泄漏、与既有 27 套效果不撞车 | 未开始 |
| **收尾** | 删诊断探针（mesh prof / hitch / EMA / 图层掩码 / 自驱 AB） | 按约定留到四项全验收后删 |

**已裁决不做**：阴影贴图（D-GL-08）、交通车（D20）、玩家座驾（O7）、车辆物理 / 碰撞、后期处理（上游全 0 命中）、深度纹理 / MRT、竖屏（O5 已裁决）。

---

## 2026-11-01 P7-2a：Planet 高度场（陨石坑）落地 + parity 门禁

### ① 为什么 Planet 必须**单开一个类**

Planet 的高度场是上游**另一个类**（`var zn = class extends rt {`，`main:10032-10482`），
与 Hills 的 `pt`（`main:2184-2772`）**只有基类相同**：

| | Hills `pt` | Planet `zn` |
|---|---|---|
| 生成器 | 中心点 + 半径衰减（`lerpHeight`） | **梯度噪声**（`dotGridGradient` + `smootherLerpAB`，四角双线性） |
| 格内算子 | `{x, y, z, r, r2}` | `{x: cos n, y: sin n}` 单位圆梯度 |
| 陨石坑 | 无 | `craterLayers` / `craterDepth` / `craterCache` + `ch`/`cl` 两项 |
| 硬件尺度 | `cellScaleMetres 3000` / `World 3000` | **1500 / 1500** |
| 缓存上限 | 9 | **15** |
| 深度分辨率 | `resolutions[]` 预排表 | `round(sq * upscaleFactor)` 逐层现算 |
| 额外后处理 | `temper` 衰减 | 坑壁改写 `cl`（把 `cl` 压到坑底半径比） |

⇒ 不是继承关系、**不能揉成一个类**（算子完全不同，合并后 parity 无法定位）。
⇒ ⛔ **不动 Hills 高度场**：原世界核 parity（`HighwayWorldGateTest`）本轮复验仍绿。

### ② 三步落地

1. **上游原文抽取**（与 Hills `world_gate` 同口径）：
   `scripts/e52/e52_planet_height_gate.mjs --dump-src <path>` 从 chunk 按**括号配对**抽
   `rt` + `zn`，落成 `scripts/e52/e52_upstream_planet.js`（纯文本 / 全 LF，供逐行对照）。
   ⚠️ 第一次组装直接炸 `ReferenceError: jn is not defined` —— 漏了 `main:10030` 的
   **模块级 scratch** `const jn = {}, Cn = 2 * Math.PI;`（`zn.getXZ` 全程靠这个复用对象承载状态）。
2. **基准**：同一脚本用 `new Function('window','Math','Vi','Ze', …)` 把上游类**原样跑起来**，
   四档 × 12 点 → `app/src/test/resources/highway/planet_height_baseline.txt`（54 行）。
   ⛔ 存的是 **IEEE-754 原始 64 位**（x / z / h 三个都是位），不是十进制文本 ——
   JS 与 Kotlin 的 `Double.toString()` 规则不同（指数写法 / 最短表示），比文本会冒出**假红**；
   把输入 X/Z 也存成位 ⇒ 两侧**输入也逐位一致**。
3. **移植 + 门禁**：`HighwayPlanetHeightMap`（`world/`，含 `HighwayPlanetHeightMapConfig`
   与四档 `HighwayPlanetTopography`）+ `HighwayPlanetHeightGateTest` 4 例。

### ③ 本轮钉住的三条语义（"看着像废话，抄错就整片偏"）

* **`||` 回退**：`flat` 档 `heightScale: 0` 会被上游 `t.heightScale || 1` 变成 **1**（不是 0）
  ⇒ 该档是「高度 50 的平场」，不是绝对平面。已单独出一条用例把原因钉住。
* **陨石坑的 PRNG 次序**：每坑 **6 次**，顺序固定 `c → p → u → x → y → z`
  （`y` 那次写成 `-(1 + rand()) * craterDepth`）；次序错一个 ⇒ 整片地形都不是上游那片。
  坑数上界 `A = craterProb * s * s` 是**浮点**，循环 `n < A` ⇒ 实际是 `ceil(A)` 个。
* **`cl` 的传染**：坑口项用 `cl = 1`，坑底项用**被 `cl = min(cl, d)` 压小后**的值
  ⇒ 两项不能并列算，必须按上游次序串行。

### ④ 验证

* `HighwayPlanetHeightGateTest` **4/4 绿**：逐位相等（四档 × 12 点）/ 四档覆盖不漏档 /
  `||` 回退语义 / **乱序查询下确定性**（倒序先把 15 格缓存撞满再回来算同一点）；
* `HighwayWorldGateTest` **5/5 仍绿**（⛔ Hills 未动，原世界核 parity 不变）；
* `com.nasmusic.tv.visualizer.highway.*` 单测 **19 类 98 例零失败**。

### ⑤ 下一步（P7-2b）—— 动手前核出方案里一处**事实错误**

原方案 F-03 写的是「顶点着色器**复用** `terrain` 的 V-01，只换片元 ⇒ 编译两个 program」。
本轮逐行核 `D10779`/`D10783`（Planet 顶点）对 `D3418`/`D3422`（Hills 顶点），**六处实质差异**：

| 项 | Hills | Planet |
|---|---|---|
| `wuvSize` | 800.0 | **150.0**（`vWv`/`vWWv` 整体尺度不同） |
| `vWWv` 除数 | `vWv/8.0`，末段又覆写成 `wPos/400.0` | `vWv/10.0` |
| `steepness` | `clamp(*2.0)` | `clamp(*4.0)` |
| `vCurvature` | `curvature * -4`（取负） | `curvature * 5`（**不取负**，限幅 ±1） |
| 顶点贴图 | `displacementMap`（`steepness*1.25`，无条件） | **`fadeFiner`**（仅 `steepness>0.3`，`(steepness-0.3)*0.5`） |
| 额外 varying/属性 | `vTreeMask`/`vGrass`/`vLightGrass`/`vDarkGrass`/`vHeather` + `treeMask`/`grass` | **无**；多一个声明未用的 `wuv` |
| `roadProx` 语义 | `= roadProximity` | `= -roadProximity*2`（负值放大），否则 0 |

⇒ **Planet 必须自带一份顶点着色器**（两个 program、各自一对 vert/frag）。
「共享同一套 VBO、draw call 不增加」仍成立（Planet 顶点只用
`aPos`/`aNormal`/`aHeightOffset`/`aRoadProx`/`aCurvature` 的**子集**），但**不是**"只换片元"。
已把该更正写进 `docs/archive/e52-highway-opengl-plan.md` §3.2 F-03。

**落地拆成两半**：`P7-2b-1`（Planet 一对 vert/frag 的 GLSL 落地 + parity 门禁）**已完成**（见下节）；
`P7-2b-2`（渲染器接线 + 场景互斥 + VBO 重建）待做：

* 在 `HighwayGlScene.buildProgram`（`:2840` 一带，现在只建 `terrainProgram`）旁再加一个
  `terrainPlanetProgram`，按场景选用；
* **Hills / Planet 场景互斥**：`?topo=` 选档 → 选高度场类（`HighwayHeightMap` vs
  [HighwayPlanetHeightMap]）→ **重建地形 VBO**（两者 `cellScaleWorld` 3000 vs 1500 ⇒ cell 划分不同）；
* 门禁：场景互斥与重建触发（离线）。
* ⚠️ 上机项：Planet 片元**声明 14 个采样器、实际采样 12 个**（`roadBaseMap` / `fadeFiner` 声明未用），
  顶点侧**只剩 1 个**（`fadeFiner`；`displacementMap` 声明未用已在替换表里删掉）⇒ 需确认真机
  `GL_MAX_TEXTURE_IMAGE_UNITS`（GLES2 只保证 8）与 `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS`
  （老 GPU 可能为 0；Hills 顶点已在做 VTF，故上限大概率够）。

---

## 2026-11-01 P7-2b-1：Planet 一对 vert/frag 落地（并修掉「生成物 / 生成脚本」漂移）

### ① 先修一条**结构性隐患**：`HighwayGlsl.kt` 是生成物，但它已经被手改过

`HighwayGlsl.kt` 头部写着「⛔ 本文件由 `scripts/e52/e52_gen_glsl_kotlin.mjs` 生成 ⇒ 不要手改」，
但历史上有两处**只存在于生成物里**：
`TERRAIN_VERTEX_SHADER` 里的 `roadSink` 真机修复块，以及 D24 过场的白 quad 两个着色器。

⇒ 本轮第一次跑生成器时它们**被静默冲掉**（`git diff` 出现 −44 行）⇒ 及时发现并回退。
现在**两处都搬进生成器**（`ROAD_SINK_BLOCK` + `WHITE_QUAD_KOTLIN`），
生成器重新成为唯一改写点；`injectAfter()` 专门承载"自己的补丁"这类非上游派生物。
顺手把被手改搞错位的 `/** 路面顶点着色器（ESSL 1.00） */` 注释归位。

### ② Planet 一对 vert/frag 的落地

* 原文四份（`D10779` 顶点声明 / `D10783` 顶点主体 / `D10787` 片元声明 / `D10791` 片元主体）
  本来就在 `scripts/e52/shaders/` 里 ⇒ 直接进生成器的 `V` 映射；
* 新增两张替换表（**不与 Hills 共表**：共表会让"某条命中失败"两个着色器互相掩盖）；
* 装配出 `PLANET_TERRAIN_VERTEX_SHADER` / `PLANET_TERRAIN_FRAGMENT_SHADER`
  （外框照抄 Hills 那份：`precision` / 我们的属性 / `uViewProj` / 灯光与雾 uniform）；
* 两处**有意删除**（上游"声明但函数体从未使用"）：顶点侧的 `attribute vec2 wuv`
  与 `uniform sampler2D displacementMap` —— 省一个属性槽 + 一个顶点采样器槽，
  对老 GPU 的 `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS` 更友好；语义无变化。

### ③ 自检抓出的两个**真·漏替换**（都会在真机上编译失败）

1. `modelMatrix`：Planet 顶点体的 `vec4 wPos = modelMatrix * vec4( position, 1.0 );` 漏映射；
2. `roadProximity`：Planet 的 `if(roadProximity < 0.0)` / `roadProx = -roadProximity * 2.0;` 漏映射
   （这条是 Planet 独有语义：Hills 是 `roadProx = roadProximity;` 直传）。

⇒ 教训：**替换表漏一条不会报错**，只会在真机上黑屏/编译失败（本轮两次都是人工比对才发现）。
现在生成器新增**生成后自检** `assertNoBareNames()`：装配出的 8 个着色器（地形 / Planet / 天空 / 路面）
剔掉整行注释后，不许出现未加 `u`/`a` 前缀的上游名（用 lookbehind 避免 `uGrassMap` 误命中 `grassMap`）。
VEG_* 不列入（ESSL 3.00 + 条件编译，替换表里本就有"有意删掉"的条目）。

### ④ 门禁

* `HighwayGlslParityTest` **5 → 6 例**：
  * `upstreamGlslMatchesByteForByte` 覆盖片段数 **13 → 17**（含 Planet 四份，逐字节）；
  * 新增 `planetPairIsNotHillsPair`：**六处差异逐条钉住**（`wuvSize` 800/150、
    `vWWv` 除数 8/10、`steepness` ×2/×4、`vCurvature` 取负/不取负、位移采样器
    `displacementMap`/`fadeFiner`、`roadProx` 直传/放大），并钉住 Hills 侧不能反向丢失
    （`aTreeMask`/`aGrass`/`roadSink` 必须在）；
  * `shadersAreEssl100Compliant` 的扫描表加入 Planet 两个（不让新着色器成"监管真空"）；
  * `fogUsesSmoothstep` 加入 Planet 片元。
* **回归证据**：逐常量"字面量内容级"比对 HEAD vs 重生成
  —— 既有 14 个着色器常量**逐字未动**（含 `TERRAIN_VERTEX_SHADER` 的 `roadSink`、
  两个白 quad、VEG 三套）；仅新增 10 个常量（4 上游原文 + 4 替换版 + 2 装配）。
* 全量 `com.nasmusic.tv.visualizer.highway.*`：**19 类 107 例零失败**
  （`--rerun-tasks` 强制重跑，非缓存判定）。
* 规模：`HighwayGlsl.kt` **+814 / −3 行**（生成物），生成器 `+约 200 行`，行尾全 LF（索引 blob 已核 CR=0）。

### ⑤ P7-2b-2 进度：**纯逻辑选择器 + 门禁已落地**；GL 接线待做

**已完成**（本轮）：`world/HighwaySceneChoice.kt` + `HighwaySceneChoiceGateTest` **5/5 绿**。

输入用的是环境应用层**已经选好的档**（`HighwayEnvApply.Selection` 的 `sceneKey` /
`topographyName`），输出三件事：

1. `kindOf(sceneKey)`：`hills` → `HILLS`、`planet` → `PLANET`；未知键回退 Hills
   （**不抛异常** —— 这条路径在 GL 线程上，抛异常等于黑屏；场景键集合由门禁对着环境集钉住）；
2. `shadersOf(kind)`：两场景**各自一对** vert/frag（Hills 一对 / Planet 一对），
   门禁用两边的独有串反向确认取到的是各自那份（`wuvSize 800/150`、`uSurfaceMap`/`uGrassMap`）；
3. `requiresTerrainRebuild(prev, next)`：首次建世界 / 换场景 / 换档位 ⇒ **重建**；
   同场景同档位（只换天气或 skin）⇒ **不重建**（这正是换歌过场能"只闪白不卡"的前提）。

另一条门禁把枚举里的 `cellScaleWorld` 与两款高度场的**实现值**对齐（Hills `3e3`、Planet `1500`），
并确认同名档位在两场景给出**不同**配置（Hills `normal` `heightScale=160` vs Planet `50`）。

#### 已完成的**前置改造：高度源抽象**（`HighwayTerrainHeightSource`）

下游原本把 `HighwayHeightMap` 写死，Planet 接不进去。本轮抽出**最小契约**
（只放两款实现都有的成员，不造"为了统一"的伪成员）：

| 契约成员 | Hills | Planet |
|---|---|---|
| `getXZ(x, z, depthOverride)` | 侵蚀 `pt` | 陨石坑 `zn` |
| `getXZLayer(x, z, layer)` | **转调** `terrain/HighwayHeightLayer`（零数值变化） | `zn.getXZLayer` 本体 |
| `midlineDepth` | `null` | `null` |

* Hills 独有的一律**不进**契约：`resolutions` / `getResolution(层表)` / `smoothLerpIndex` /
  `cellLookups`，以及 `getCell`（**cell 类型都不同**：`HighwayCellPoint` vs `HighwayPlanetGradient`）
  —— 它们只在各自 `getXZ`/`getXZLayer` 内部用；
* 下游改造：`HighwayRoadChain.heightmap`、`highwayGetOriginPose(heightmap)`、
  `HighwayTerrainMeshBuilder.heightmap`、`HighwayGlScene.heightmap` 全部改为接口类型；
  地形网格里 8 处 `HighwayHeightLayer.getXZLayer(heightmap, …)` 改为 `heightmap.getXZLayer(…)`
  （**对 Hills 等价**：接口实现就是转调同一个函数，门禁逐位比对钉住）；
* 新增 `HighwayTerrainHeightSourceGateTest` **3/3**：两款都能按接口用且不是同一个世界 /
  Hills 的转调逐位等于原函数 / 同坐标上两款层查询**必须不同**（防"接口分派失灵"）。

顺带修正一处 **parity 口径**：`HighwayPlanetHeightGateTest` 原先按配置传第三参 `midlineDepth=5`，
而**上游运行时传的是 `undefined`**（`this.heightmap = new zn(...)` 的实例不带该字段，`main:11613`；
配置树里的 `midlineDepth: 5` 高度查询读不到）⇒ 已改为传 `hm.midlineDepth`（`null`），
并新增 `thirdArgumentIsImmaterial` 用 4 档 × 24 点钉住"5 ≡ null 是**碰巧**（四档 `depth` 全 ≤ 5），
不是"可以随便传"。

**待做（GL 接线，下一步）**：

* `HighwayGlScene` 里加 `terrainPlanetProgram`（现在单一个 `terrainProgram`，`:2840` 一带
  按名取 `aPos`/`aHeightOffset`/`aRoadProx`/`aTreeMask`/… 全体位置），按场景选用；
* `buildWorld()` 按场景种类选高度场类（`HighwaySceneChoice.kindOf(appliedEnv.selection.sceneKey)`
  ⇒ `HighwayHeightMap` / `HighwayPlanetHeightMap`），并处理「`?topo=` 显式覆盖 vs 环境集档位」
  的优先级（上游口径：QS **覆盖** 正常选择）；
* 场景/档位变化时重建 world 与地形 VBO（复用 `Selection.sceneChanged` 与
  `requiresTerrainRebuild`），并补"接线存在性"的源码扫描门禁；
* ⚠️ 上机项：见上一节列出的 `GL_MAX_TEXTURE_IMAGE_UNITS` / `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS` 核验。

---

## 2026-11-02 第十一轮：P7-2b-2 GL 接线 + P6-3b-2 环境常量 + P8 自适应回退

> 本节是「当前状态快照」的**上一版**；快照已更新到 2026-11-02，见下。

### ① P7-2b-2 GL 接线（三件一起落地，缺一件就是半成品）

| 件 | 落点 | 说明 |
|---|---|---|
| ① 双 program | `HighwayGlScene.TerrainProg` ×2（`terrainHills` / `terrainPlanet`） | Hills 路径**逐字冻结**（原函数改名 `drawTerrainHills`，函数体一个字没动）；`applyTerrainProg()` 只换「字段里装的是谁的位置」 |
| ② 场景选高度场 + 档位优先级 | `buildWorld()` 按 `sceneKind` 选 `HighwayHeightMap` / `HighwayPlanetHeightMap`；`HighwaySceneChoice.resolveTopography(explicit, env)` | 优先级 = **显式 `?topo=` 覆盖环境集档位**（上游 `"Overwriting topography from QS"`）；`?topo=` **不决定场景**，场景仍由环境集 `sceneKey` 决定 |
| ③ 重建触发 | `rebuildWorldForScene()`，在 D24 **白帧**里调 | 删地形 VBO/索引 + 清**纯 CPU** 几何缓存（与 `rebuildGlResources` 相反：换场景后旧格的高度/法线/切槽全作废） |

* 新增 `HighwaySceneChoice.roadParamsOf(kind, name)`：Hills 走手写表，Planet 走**环境集文本**（`"3"` / `"7"`）解析。
* `HighwaySceneParams.topo` 改成 **`String? = null`**（null = 未指定 ⇒ 跟随环境集）。
* ⛔ **program 数从 6 改成 8**：旧值 **漏算了 D24 的白 quad program**（它也走 `buildProgram`，真机上 `compiled=7`）；
  P7-2b-2 加 Planet ⇒ 7 + 1（补回白 quad）+ 1（Planet）= **8**。

### ② Planet skin 贴图驻留表（从上游 bundle 逐条抠出来的）

`world/HighwayPlanetSkinTextures.kt`：14 个采样器 → 资产文件。

* ⛔ **bumps 不随 skin 变**：上游在**材质构造时**就把 `surfaceBump=Rn` / `shaleBump=Gn` / `rockBump=Wn` /
  `cliffBump=Tn`（全是 mars 的）写死，只有 7 个 albedo 走 `setSkin` 逐 skin 换 ⇒ venus / moon 也用 mars 的 bump。
* ⛔ **标识符共用**：mars `gravel`=`roadBase`=`Sn`；venus `gravel`=`roadBase`=`subSurface`=`Yn`；
  moon `rock`=`subSurface`=`$n`、`gravel`=`roadBase`=`eh`。
* ⚠️ **venus 的 `surface` 指向 `venus_road_base.jpg`**（上游 `surface=Qn`）—— 不是笔误，照抄。
* ⛔ **`uGravelBump` 上游从未绑定**（片元采样它，但 `onBeforeCompile` 的 uniform 清单里没有）
  ⇒ 上游采的是未绑定采样器（未定义行为）⇒ 我们取**确定的 1×1 黑**并写进 KDoc。
* 缺图不抛：`HighwayTextures.loadOr(name)`（新增）⇒ 解码失败/资源不存在 ⇒ 退回黑。
* 门禁：`HighwayPlanetSkinTexturesGateTest` **6 例**（采样器集合逐字对齐 GLSL 声明 / 每个 skin 都能解析 /
  三处共用回归 / 文件已登记且存在）。

### ③ P6-3b-2：环境常量换 `AppliedEnv` 值（**这轮改了画面**）

改之前，天空色 / 雾 / 三灯 / colouration 是**写死**的一组常数（= 上游 Hills「clear」天气的值）。
改之后全部来自 `HighwayEnvApply.AppliedEnv`。

* 上游口径（`main:544282` / `main:621124` 的 `setWeather` 原文）：
  `amb`→环境光、`hem`+`hemTop`/`hemGround`→半球光、`dir`+`col`→平行光、`col`→雾色、
  `pe·fogNear`/`pe·fogFar`→雾、天空 `skyCol`/`skyColFar`。
* ⛔ **平行光方向仍是正上方**：`sunPos` 只喂天空 shader 的太阳位置（`re.sunOffset`），
  平行光本体位置固定 `(0,10,0)`、target 在原点 ⇒ **别拿 `sunPos` 去改光照方向**。
* ✅ **开屏观感不变**：`HighwayEnvApply.defaultApply()`（Hills / summer / clear）解析值与旧硬编码**逐项相等**
  （门禁 `HighwayEnvUniformGateTest` 5 例钉住）。
* ⚠️ **colouration 换成了上游真值**（旧值是自造近似）：
  地形/草/灌木 = `foliage.grassColouration` = `(0.2039, 0.0784, 0.1412, 0)`（旧值 `(0.18, 0.08, 0.1, 0)`）；
  树 = `foliage.treeColouration` = `(-0.05, 0.1, 0.1, 0)`（旧值 `(0.15, 0.15, 0.15, 0)`）。
  ⛔ 上游草与灌木用的是**同一个** `grassColouration`（旧实现给了全 0）⇒ 草/灌木现在**会**被染色。

### ④ 贴图按 skin 重组（`world/HighwaySkinTextures.kt`）

| 组 | 上游字段 | 落点 |
|---|---|---|
| Hills 地形 albedo | `grass` / `forest` / `heather` / `gravel` / `rock` / `sand` | `drawTerrainHills` |
| 路面 ribbon | `skin.road` | `drawRoad`（Hills `road_03/04`，Planet `road_offworld/venus/moon`） |
| 植被 | `foliage.grass` / `foliage.bush` / `foliage.trees` / `foliage.treesb` | `drawVegetation` |
| 云 | `weather.skyMap`（**三态**：没写 ⇒ 默认天气的；写 `null` ⇒ 无云；写标识符 ⇒ 那张） | `drawSky` |

* ⛔ **逐字段回落**（上游 `loadGroundMaterialSkin` 的 else 分支）：autumn 没写 `heather` ⇒ 用 summer 的那张。
* ⛔ winter 的 `forest`/`gravel`/`heather`/`sand` **全是同一张** `forest_winter.jpg`（上游标识符 `Ht`）；
  winter 的 `grass` 是 `snow_01.jpg`（`Ft`）、`rock` 是 `rock_06_winter.jpg`（`Jt`）；
  winter 的 `grass` 写的是 `null` ⇒ 回落 summer 的草皮，而 `bush` 是 `foliage_brush_winter.png`。
* ⛔ **Planet 的两张云图没移植**（清单 `excluded` 写明：只取 hills 场景那 7 张）⇒ **Planet 恒无云**。
* 门禁：`HighwaySkinTexturesGateTest` **7 例**（逐字段回落 / 共用标识符 / 路面按场景分 / 文件已登记且存在）。

### ⑤ P8：自适应回退（方案 §4.5）

* `HighwayAdaptiveQuality.kt`（**纯逻辑**）：每 **120 帧**取 **p95** 帧时；
  `> 22 ms` 持续 **2 s** ⇒ 降一档；`< 12 ms` 持续 **8 s** ⇒ 升一档；
  ⛔ 单次会话最多自动降 **2** 档、最多自动升 **1** 档。
* 阶梯 `0.40 → 0.35 → 0.30 → 0.25`（本机档位 + 兜底）。
* 接线：GL 线程判定 → `onAdaptiveRenderScale` 回调 → 主线程 `setFixedSize`（window manager IPC 必须在主线程）。
  ⛔ 自适应**自己**触发的降档**不重置**统计（否则「最多降 2 档」会被自己清零 ⇒ 能一路降到最低档）。
* 降档必记 logcat（tag `Highway`）。
* 门禁：`HighwayAdaptiveQualityGateTest` **7 例**（窗口没满不下结论 / 持续慢 2 s 才降 / 单帧尖峰不降 /
  最多降 2 档 / 升档要 8 s 且最多一次 / 中间地带清两个计时器 / `resetTo` 钉起点）。

### ⑦ P8 剩余两个旋钮（②`res` / ③贴图尺寸）+ 画质档统一

`HighwayQualityTier.kt`（**纯逻辑**）：一档画质 ⇒ 三个旋钮的值。

| 档 | ① `renderScale` | ② `res`（near/med/far） | ③ 贴图上限 |
|---|---|---|---|
| HIGH | 0.40 | 20 / 10 / 70（上游保真） | 不限 |
| MEDIUM | 0.35 | 20 / 10 / 70（上游保真） | 不限 |
| LOW | 0.30 | **12 / 6 / 35** | **256²** |

* ⛔ **②③ 只在 LOW 档生效**：它们改几何 / 改采样 ⇒ **会破 G18 的逐像素对账**
  （方案 §4.5 对 ③ 写得很直白：「只在 LOW 档用 256²，且**归档 LOW 档的独立参考帧**」）
  ⇒ HIGH / MEDIUM 保持上游保真值。
* `HighwayGlScene.setQualityTier(tier)`：**运行时**可切 ⇒ ② 要重建槽位缓冲 / 合并索引 /
  暂存数组 / 几何缓存 + 重建**网格构建器**（它持有 `config`）；③ 要换掉 `HighwayTextures` 实例
  （它内部按名字缓存句柄 ⇒ 必须换实例才会重传）。
  ⛔ **道路链与高度场不动** ⇒ 切档时车辆位置不跳。
* `HighwayGLSurfaceView.setQualityTier()` 用 `queueEvent` **切到 GL 线程**（要动 GL 对象）。
* 门禁：`HighwayQualityTierGateTest` **5 例**（三档 `renderScale` / HIGH·MEDIUM 保真 / LOW 才降 /
  `terrainConfig` 一致 / 未知档回落 MEDIUM）。

### ⑧ P5 的离线可验部分（`.obj` 解析 + 水面深度规则）

方案 §六 的 P5 行写着「⛔ **不要先做 P5（模型/水面）** —— 占 4–6 天，只贡献 5% 观感」。
本轮把 P5 里**能离线验的部分**做掉，渲染接线留到有真机时：

* **G17（`.obj` 顶点数与上游一致）** ✅
  * `model/HighwayObjParser.kt`：口径**逐条对齐 three.js r144 的 `OBJLoader`**
    （扇形三角化 n-2 / 展开成非索引几何 ×3 / 法线永远 3 个·缺则算面法线 /
    UV 永远 3 个·缺则填 `[0,0]` / 负索引相对原始 `v` 表末尾）；
  * `scripts/e52/e52_obj_baseline.mjs`：**独立**实现（逐行数）⇒ 生成
    `app/src/test/resources/highway/obj_baseline.json`（8 个模型）；
  * 门禁 `HighwayObjParserGateTest` **5 例**（逐项对账 + 扇形三角化 / 负索引 / 缺 vn·vt / 空文本）。
* **G16（水面 `vDepth` 剔除边界无硬边）** 的**规则**部分 ✅
  * `world/HighwayWaterDepth.kt`：把上游那套 `vDepth` 驱动的算式**逐条抄成 Kotlin**
    （`> 10.0` 丢弃 / `darkReflect` 三段 / 亮边修正两支 / 深水变暗 / `seaDot` 钳位与太阳高光）；
  * 门禁 `HighwayWaterDepthGateTest` **6 例**（**边界值全是严格不等号** ⇒ 抄成 `<=` 就会在岸边出硬边）。
  * ⛔ **水面平面本身（网格 + 着色器 + 新 program）未接** —— 那要新增一个 program
    （`EXPECTED_PROGRAM_COUNT` 会变），在没有真机可验的环境里风险不划算。**这是本方案唯一未接的渲染块。**

### ⑨ P5：水面（G16）+ 路边模型（G17）—— **渲染侧接线完成**

#### ⑨-1 水面（G16）

* **上游 GLSL**：水面是 three.js 的 `MeshStandardMaterial` + `onBeforeCompile` 注入
  ⇒ 上游的 GLSL 是**四段字符串字面量**（顶点声明 / 顶点主体 / 片元声明 / 片元主体），
  从 `main.e7a33c55.chunk.js` 的 `main:157000` 附近**逐字抽出** ⇒
  `scripts/e52/shaders/WATER_{VS_PARS,VS_BODY,FS_PARS,FS_BODY}.glsl`；
  由 `scripts/e52/e52_gen_glsl_kotlin.mjs` 做**机械替换**后生成
  `HighwayGlsl.WATER_{VERTEX,FRAGMENT}_SHADER`（⛔ 全程没手改生成物）。
* **网格**：上游 `PlaneGeometry(size, size, Ti, Ti)` + per-vertex `depth` 属性
  （`getSeaMesh()`，`main:502673`）；`size = le = farSize = 1400`、`Ti = floor(le/50) = 28`
  ⇒ 我们照抄：**29×29 = 841 顶点 / 1568 三角**，覆盖 **1400×1400** 世界单位。
* **深度属性** = 该顶点处的**绝对地形高度**（上游 `lookupHeight`）
  ⇒ 分帧刷新（**4 行/帧**，一行 = 29 次高度场查表 ≈ 0.44 ms）
  ⇒ 锚点按 50 m 吸附 ⇒ 锚点不变时顶点世界坐标不变 ⇒ 深度不用重算。
* **可见性**（上游三态）：天气**没有** `water` 键 ⇒ 用默认天气的水色；
  `water: null` ⇒ **不可见**（venus / moon 的天气就是这样）；有值 ⇒ 用它。
  ⇒ `AppliedEnv` 新增 `waterSkyReflection`（上游 `||` 回落默认天气）。
* ⛔ **上游的水面在实践中是"哑"的**：地形绝对高度（Hills 84/100/50、Planet 500）**永远 > 10**
  ⇒ `if (vDepth > 10.0) discard;` 把水面**整片丢掉** ⇒ 上游看不到水面。
  **我们照抄** ⇒ 这一轮**画面零变化**（也就零风险）。
* 门禁 `HighwayWaterGateTest` **8 例**（用 Kotlin 常量拼出着色器里该出现的字符串再断言
  ⇒ 着色器与 `HighwayWaterDepth` 两处**逐条一致**；改一边不改另一边会立刻红）。

#### ⑨-2 路边模型（G17）

* **摆放**（`world/HighwayModelPlacement.kt`，**纯逻辑**）：每 **24** 个道路节点放一个，
  横向 **8 m**（路的右侧），朝向 = 道路朝向 + 180°（面向来车）。
  ⛔ **不是上游的逐位复刻** —— 上游的道具位置藏在**场景图**里（我们没有那份图），
  这是一条**确定性、看得见的**替代规则（作用是给路一个尺度参照）。
  ⛔ **交通车不做**（D20）：`roadster-07.obj` / `roadster_wheel_02.obj` **不在**种类表里。
* **渲染**：自写的 `MODEL_{VERTEX,FRAGMENT}_SHADER`（生成器里，纯色 + three.js r144 裁剪 PBR 块）
  + 每个实例一个 `uModel` 变换矩阵（⛔ 我们的着色器不用 `modelMatrix` ⇒ 模型自身坐标是局部的）。
* ✅ **模型的单位就是米**（实测 `.obj` 包围盒：路牌 2.47 m 高、火星石 0.75 m、路肩标 0.94 m）
  ⇒ 与我们的世界单位（路宽 6 m、相机高 1.5 m）**一致** ⇒ **不需要缩放系数**。
* 门禁 `HighwayModelPlacementGateTest` **6 例**（间隔 / 偏移在右侧且不落路面 / 朝向 / 确定性 /
  两场景各自的种类 / 交通车不在表里）。

#### ⑨-3 ⛔ 一个**踩过的坑**：Kotlin 的块注释**可以嵌套**

KDoc 里写 `` `assets/e52_highway/*.obj` `` ⇒ 那个 `/*` 在 Kotlin 里会**开启一层嵌套注释**，
而紧跟的 `*/` 只闭合内层 ⇒ 外层一直没闭合 ⇒ **后面整个文件都被当注释**
⇒ 编译报「Unclosed comment」+ 一堆「Unresolved reference」（class 体看起来是空的）。
⛔ 与 Java/C 不同（那边不嵌套）⇒ 很容易中招。
⇒ 检查脚本：`logs_temp/check_nested_comments.py`（按栈扫，标出嵌套处）。

### ⑪ 第十三轮：**手机实测打脸**（2026-11-02）—— LOW 档的 `res` 降档是**功能级回归**

> 手机上跑第十二轮的包，用户报了 5 条：
> ① 道路上的白线**全都没有了**；② 画面中**一棵树都没有**；③ 远处有时草地没加载出来露出底色；
> ④ 帧率 121；⑤ 换歌时**看不出景色有变化**。

#### ⑪-1 根因：①② 都出在**旋钮 ②（`res` 降档）**

手机跑的是 **LOW 档** ⇒ `res` 从保真的 `20/10/70` 降到了 `12/6/35`：

| 现象 | 机制 |
|---|---|
| ① **白线全无** | 近景格顶点间距从 **0.5 m 变成 10 m** ⇒ 地形里那道**路面切槽**（`HighwayTerrainMeshBuilder` 按顶点插值切出来的）粗了 20 倍 ⇒ **地形从上方盖住路面** ⇒ 白线被埋 |
| ② **一棵树都没有** | 同一份粗糙化让「**离路距离**」失去分辨率 ⇒ **树的净空判定**（`placeTrees` 按真实树冠半径 vs 到路距离）把每一棵都判成「长在路上」⇒ 全部丢弃 |

⛔ ⇒ **方案 §4.5 说「`res` 可自由降档」是错的**（它只核对了纯函数 `qe`/`lerpHeight`，
**没核对依赖网格分辨率的切槽与树的净空判定**）⇒ 这是一条**方案更正**（同 F-03 那一类）。

⇒ **修法**：三档的 `res` **全部保真**（`20/10/70`）；旋钮 ② 仍可配（`Tier` 里有那三个字段），
但**本效果暂不启用** —— 要启用得先让「切槽」与「树的净空判定」**与 `res` 解耦**。

#### ⑪-2 根因（另一半）：旋钮 ③ 的贴图上限**压到了白线**

`TEXTURE_LOW` 原本是 **256²** ⇒ 路面的白线在 1024² 的图里只有几像素宽 ⇒ 压到 256² 直接**看不见**。

⇒ **修法**：
* ① 豁免「**路面 / 树图集 / 云 / 波纹**」这四类（判据：关键信息是不是**高频/细线条**）
  ⇒ `HighwayTextures.isSizeCapProtected`；
* ② 上限从 **256² 提到 512²**（256² 连地形反照率都开始明显糊）；
* ③ 顺带修了一个**真 bug**：`onSurfaceCreated` 建贴图时**漏传**了上限
  ⇒ 首帧与 Surface 重建后的贴图尺寸**不一致** ⇒ 已统一。

#### ⑪-3 ③④：③ 是**既有**问题，④ 说明手机性能绰绰有余

* ③ 「远处露底色缝」是进度表里一直挂着的 **④**（真机问题 4）⇒ **不是本轮回归**；
  ⛔ 但 `farRes` 降档会让它**更明显** ⇒ 这也是 `farRes` 一起保真的理由之一。
* ④ 121 fps ⇒ 手机远快于电视（创维 9R54_G8S 实测 8~60 fps）⇒ 帧率不是瓶颈。

#### ⑪-4 ⑤「换歌看不出景色变化」⇒ 右下角加**景色组合标签**

换歌会换掉**整套**环境（场景 / skin / 天气 / 地形档），但画面上只有**颜色**的变化 ⇒ 看不出换没换。

⇒ 新增 `world/HighwayEnvLabel.kt`（纯逻辑）+ 右下角徽章（**仅 E52**）：
`丘陵 · 夏季 · 晴朗 · 普通`

* ⛔ `default` **分场景**翻译（Hills = 夏季，Planet = 火星）—— 混译会让两个场景显示同一串字；
* ⛔ 查不到翻译时**回落到上游原名**（绝不显示空串）；
* 通路：GL 线程换环境时写 `HighwayGlHolder.lastEnvLabel`（`@Volatile`）⇒ 主线程 2 Hz 读
  ⇒ 单独一个 Compose State ⇒ 不带着整个前景层重组。

#### ⑪-5 手动切换景色（上下滑 / 遥控器上下键 ⇒ **随机**换一个组合）

> 用户追加要求：「类似像素城市效果的切换方式，用手势上下滑动或遥控器上下按键进行切换，
> 能够随机循环切换效果的景色」。

* **入口**（与 E51「像素城市」同一套交互）：
  * 遥控器 **上 / 下键**（`VisualizerStage` 的 `onPreviewKeyEvent`）；
  * 手机 **上下滑动**（`detectVerticalDragGestures`，阈值 80dp，与左右滑切主题一致）。
* **行为**：**随机**挑一个「场景 · skin · 天气 · 地形档」组合（⛔ 不是顺序 +1），
  且**必然与当前不同**（否则用户看到的是"没换"）。
* ⛔ **仍然走同一条三阶段雾白过场**（淡出 0.5 s → 白帧里换 → 淡入 0.5 s）⇒ **不会硬切**。
* **链路**（4 个文件，跨层）：
  `VisualizerStage`（键 / 滑）→ `VisualizerRenderer.requestEnvSwitch()`（基类默认空实现）
  → `HighwayRenderer` → `HighwayGLSurfaceView` → `HighwayGlScene`（置 `@Volatile` 标记）
  → GL 线程下一帧消费 → `HighwayEnvTransition.requestManualSwitch()`
  → 在**白色帧里**随机重挑游标 ⇒ 场景侧照常走「换环境」那条路（uniform 全换 / 需要时重建 world）。
* **随机源可注入**（`HighwayEnvTransition(random = …)`）⇒ 门禁能喂确定性序列。
* 门禁 `HighwayEnvManualSwitchGateTest` **5 例**（同一条过场 / 随机而非顺序 / 不会挑到同一个 skin /
  过场中只记 pending / **链路源码扫描**）。

### ⑬ 手机复验清单（第十四轮修完后请按这个顺序看）

> ⚠️ **第 0 步：先把手机横过来**（横屏）。`68°` 是**垂直** FOV ⇒ 竖屏下水平只有 33°
> ⇒ 近处路面与路边树**必然**被挤出画面（几何必然，不是 bug；方案 D-new-06 也写了「竖屏不做」）。

| 步 | 操作 | 期望 |
|---|---|---|
| 0 | **横屏** | 视野变宽 ⇒ 路面与路旁的草都进得来 |
| 1 | 进「旷野公路」，**等 10 秒** | ① 路面**有白线**（两侧边线连续）；② 路旁**能看到草**（乔木稀少是上游 LOD 行为，见 §⑭-4）；③ 远处草地不再露底色 |
| 2 | 看**右下角** | 有一行 `丘陵 · 夏季 · 晴朗 · 普通` 之类的**景色组合** |
| 3 | **上下滑一下**（手机）或按**遥控器上/下键**（电视） | 画面**白闪过场** ⇒ 景色**变了**；右下角那行字也**跟着变**；⛔ 不是硬切（应看到 0.5 s 白场） |
| 4 | 连按 3 次 | 每次都是**不同的**组合（不会按同一个） |
| 5 | 换歌 | 景色**自动**推进一格（顺序推进，与手动随机的结果不同） |
| 6 | 看 logcat | ⛔ **不应再有** `draw failed` / `ArrayIndexOutOfBounds`；<br>应有 `env label = …`（场景侧）与 `env label badge = "…"`（舞台侧） |

⛔ 若仍无白线 ⇒ 查 `logcat` 里 `diag#N … aspect=` 与 `cell stats … roadProx=`：
前者确认横竖屏，后者看路面距离场的分布。

### ⑭ 第十四轮：手机日志取证 ⇒ **真正的根因不是 `res`，是我写的水面网格越界**

> 第十三轮修完 `res` 后，手机上**白线 / 树 / 换歌白闪**仍然全无，
> 且**右下角也没有字** ⇒ 说明**新功能根本没生效**。
> ⇒ 这次先**读日志**再动手（而不是继续猜）。

#### ⑭-1 手机日志（`adb logcat -s Highway:V`，小米 22081212C / Android 15）

```
E/Highway: draw failed
E/Highway: java.lang.ArrayIndexOutOfBoundsException: length=3136; index=3139
    at HighwayGlScene.updateWaterMesh(...:160)
    at HighwayGlScene.drawWater(...:46)
    at HighwayGlScene.onDrawFrame(...:1220)
```

⇒ **`drawWater` 每帧抛异常** ⇒ `onDrawFrame` 在那里**中断** ⇒
**它后面的路面、植被、白色过场 quad 一个都没画** ⇒
「白线没有」「没有树」「切换看不到白闪」**三个现象一个根因** ✓。

#### ⑭-2 越界的来由：水面网格的**暂存数组开小了**

| 量 | 正确值 | 我写成了 |
|---|---|---|
| 顶点数（上游 `PlaneGeometry(size,size,Ti,Ti)` 的**格点**） | `(28+1)² = 841` | 以为 `28×28 = 784` |
| 暂存数组 float 数（stride 4） | `841×4 = 3364` | `28×28×4 = 3136` ← **越界就在这** |

⇒ ⛔ 索引缓冲用的是 841 个顶点（对的）⇒ 写第 842 个顶点时越界。

⇒ **修法**：
* ① 暂存数组长度改由**纯函数** `HighwayWaterMesh.stagingFloatCount()` 给出（`(res+1)²×stride`）；
* ② `RES` / `SIZE` / `STEP` / `STRIDE` 四个常量**全部搬进** `HighwayWaterMesh`
  （单一出处 ⇒ 不会再有"两处各算一遍"的分歧）；
* ③ 新增门禁 `HighwayWaterMeshGateTest` **4 例**（顶点数 / 索引数 / **暂存数组装得下最大顶点下标** / 上游尺寸口径）。

#### ⑭-3 ⭐ 结构性教训：**`onDrawFrame` 里一处抛异常 ⇒ 后面所有绘制都被跳过**

⇒ 光修数组不够 ⇒ 同样的事再来一次还是全灭。
⇒ **把水面的 draw 挪到所有其它 draw 之后**（白场 quad 之前）⇒
它就算崩，也**只影响水面自己**（水面本来就恒被丢弃 ⇒ 视觉零影响）。
⇒ 门禁 `HighwaySceneWiringGateTest.waterDrawComesAfterEveryOtherDraw`（源码扫描：水面必须在
天空 / 地形 / 路面 / 植被之后、白场之前）。

#### ⑭-4 树的稀少：**上游行为**，不是 bug

日志 `cell stats near window x121 ... treeLod[0]=120 [1]=1` ⇒
**121 个近景格里 120 格是 LOD 0** ⇒ 上游 `getTreesAt` 在 LOD 0 **返回 null**（本来就无树）。
⇒ 本 seed（`85de00b5`）的路边**只有 1 棵**乔木候选，而它又被净空判定滤掉 ⇒ `树=0`。
⇒ ⚠️ 同一份日志里 **`草=12571`** ⇒ **草是有的**（`灌木=31`）。
⇒ ⇒ 结论：乔木稀少是**上游的树图 LOD 行为**（进度表里早已定性：「121 个近景格里 117 格是无树区」），
**不是**本轮回归 ⇒ 要看乔木得换 seed（`?seed=`，调试探针 `HighwayP0Activity` 支持）。

#### ⑭-5 竖屏：**用户明确「只考虑横屏」** ⇒ 不做竖屏适配

⚠️ 但**记录一条客观事实**（供以后判断）：方案 D-GL-07 说「`68°` 是**垂直** FOV」
⇒ 16:9 上水平 FOV = 101.4°；而**竖屏**（手机 `aspect ≈ 1220/2712 = 0.45`）下
水平 FOV 只有 `2·atan(tan(34°)·0.45)` = **33°** ⇒ **视野极窄**
⇒ 近处路面被挤出画面、路边树也落在视野外 ⇒ 竖屏下「白线/树看不到」是**几何必然**，
横屏即恢复 ⇒ 与方案 D-new-06「竖屏不做」一致 ⇒ **本轮不改投影口径**。

#### ⑭-6 标签为什么也没字：同源

右下角标签是 **Compose 层**的，它本该正常显示；但 ⛔ **过场的白场 quad 也没画**
（被 `drawWater` 的异常掐掉）⇒ 用户看到的是「**什么都没发生**」，
标签是「有没有变化」的证据，缺了它就分不清"没换"与"换了但看不出" ⇒
⇒ 现在加了日志（场景侧 `env label = …` / 舞台侧 `env label badge = "…" (empty=…)`），
下次复验一眼就能确认标签通路。

### ⑮ 第十五轮：手机复验 ⇒ 白线与树**已修好**，竖滑切换定位到**命中顺序**

> 第十四轮装包后手机复验：① **白线解决了**；② **树也出现了，但很少**；③ **上下滑动仍无法切换**。

#### ⑮-1 ① 白线 / ② 树：第十四轮的越界修复生效 ✓

⇒ 日志里不再有 `draw failed` / `ArrayIndexOutOfBounds`（水面数组尺寸由纯函数给出，门禁钉死）。
⇒ 「树很少」**不是回归**：`cell stats near window x121 … treeLod[0]=120 [1]=1`
⇒ 121 个近景格里 **120 格是 LOD 0**，上游 `getTreesAt` 在 LOD 0 **返回 null**（本来就无树）
⇒ 本 seed 的路边只有 1 棵乔木候选，还被净空判定滤掉 ⇒ `树=0`；
而**草有 12571 株、灌木 31 株** ⇒ 用户看到的"树"是草，乔木本就是稀有的。

#### ⑮-2 ③ 竖滑切换：**先别猜，先看日志**（并记一次被证伪的假设）

⚠️ **本轮先证伪了一个我自己写下的假设**（重要，避免以后重蹈）：

> ❌ 我一度断定「竖滑的 `pointerInput` 挂在**外层 Box**（= `AndroidView` 的**父级**）⇒
> Android/Compose 的命中测试**先**把 touch 给**子**（`AndroidView` 那个真实 View）
> ⇒ **父级收不到事件** ⇒ 所以把竖滑层挪到 `AndroidView` 之后」。
>
> ⛔ **所有者实测：E51「像素城市」的上下滑动切换是成功的** —— 而它的竖滑**同样**挂在外层 Box 上
> ⇒ ⇒ **那个推理是错的**（父级的 `pointerInput` 照样能收到 touch）⇒ **改动已撤回**。
> ⇒ ⚠️ 教训：**"看起来必然"的 Compose 命中顺序也要用真机证**；而且**已有可用功能**（E51 的竖滑）
>    就是最好的对照实验 —— 先看对照，再动代码。

⇒ 于是本轮改成**四处无条件日志**，一次试完就能定位断点：

| # | 位置 | 日志 | 含义 |
|---|---|---|---|
| 1 | 舞台（竖滑 `onDragEnd`，**无条件**） | `vertical swipe: theme=… drag=… threshold=… dragged=… renderer=…` | 手势**到没到**舞台？`dragged` 是不是 true？`theme` 对不对？ |
| 2 | 渲染器 | `requestEnvSwitch: surface=true/false` | 请求**到没到**渲染器？`surface` 是不是 null？ |
| 3 | View | `requestEnvSwitch -> scene` | 请求**到没到**场景？ |
| 4 | 场景（消费标记） | `手动切换景色：请求换环境（随机一个组合）` | 场景**消费**了没？ |

⇒ ⇒ 哪一条**不出现** ⇒ 断点就在它前面 ⇒ 不用再猜。
⇒ 门禁 `HighwayEnvManualSwitchGateTest.竖滑块与 E51 共用同一个（不得拆成两块）`
（⛔ 全文**只能有一个** `detectVerticalDragGestures` —— 两个 detector 挂同一层会互相吞事件）。

#### ⑮-3 「右下角没字」很可能是**另一处**独立问题（顺手一起修）

⛔ 标签写进 `HighwayGlHolder.lastEnvLabel` 的时机原本只有两处：`onSurfaceCreated` 与换环境时。
⛔ 而 `HighwayGlHolder.unregister(view)`（退出效果时）会**清空**它，
而 GL 上下文**可能复用** ⇒ `onSurfaceCreated` 不再跑 ⇒ 标签**永久为空** ⇒ 「右下角没有字」。

⇒ **修法**：场景**每帧**比对一次（引用比较，极快），不等才回写 ⇒ 任何时候标签都是当前值。
⇒ 门禁 `HighwaySceneWiringGateTest.景色标签必须每帧被确保是最新的`（源码扫描）。

#### ⑮-4 加诊断日志（下次复验一眼就能判定）

| 位置 | 日志 |
|---|---|
| 舞台（竖滑触发时） | `vertical swipe: theme=… renderer=… drag=… threshold=…` |
| 场景（消费请求时） | `手动切换景色：请求换环境（随机一个组合）` |
| 场景（换环境后） | `env label = 丘陵 · 夏季 · 晴朗 · 普通` |
| 舞台（标签取值） | `env label badge = "…" (empty=…)` |

⇒ 新增门禁 `HighwayEnvManualSwitchGateTest.竖滑层必须在 AndroidView 之后`（源码扫描固定这个顺序）。

### ⑯ 第十六轮：**真凶 = E51 覆盖层漂移**（本地包**根本没含** E52 的舞台代码）

#### ⑯-1 决定性证据：**整个 `VisualizerStage` tag 一条日志都没有**

手机实测日志（`adb logcat -d -t 60000`，全量 tag 直方图）：

| 日志 | 代码在包里时应当 | 实测 |
|---|---|---|
| 场景侧 `Highway`：`env label = 丘陵 · 夏季 · 晴朗 · 普通` | 有 | ✅ **2 条**（15:14:27 / 15:14:35） |
| 舞台侧 `VisualizerStage`：`vertical swipe: theme=…` | 有 | ❌ **0 条** |
| 舞台侧 `VisualizerStage`：`env label badge = "…"` | 有 | ❌ **0 条** |
| 舞台侧 `VisualizerStage`：`fpsdiag …` | 有（showFps 开时） | ❌ **0 条** |

⇒ ⛔ **不是"事件没到"、也不是"值读成空"** ⇒ **那段代码根本不在 APK 里**。
⇒ ⚠️ 判读要点：**"某处一条日志都没有"比"某处报错"更有信息量** ——
断点排查的第一步应当是**确认那一段代码在不在包里**。

#### ⑯-2 机制：E51 本地覆盖层**遮蔽**了仓库同名文件

本机（**仅本机**）有一个**仓库之外**的 E51 覆盖层：

```
D:/hxzhang/MyGithubSoftware/NASMusicTV-e51-local/src/ovl/app/src/main/java/com/nasmusic/tv/...
```

`~/.gradle/init.d/10-nasmusic-e51-local.gradle` 把它加成 app 模块 main 源集的**额外源目录**，
并在 **Kotlin 编译任务层**排除仓库里那 6 条路径（否则 `Redeclaration`）：

```
com/nasmusic/tv/visualizer/e51/**
com/nasmusic/tv/ui/components/LofiCityCard.kt
com/nasmusic/tv/visualizer/renderers/LofiCityRenderer.kt
com/nasmusic/tv/visualizer/RenderContext.kt
com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt
com/nasmusic/tv/ui/components/VisualizerStage.kt      ← ⛔ 就是这一份
```

⇒ **本地构建实际编译的是覆盖层那份** ⇒ 改仓库里的 `VisualizerStage.kt` 而不同步覆盖层
⇒ **本地包 = 旧版** ⇒ 而 **CI（无覆盖层）能编过** ⇒ **本地与 CI 行为分叉**。

实测漂移：**只有 `VisualizerStage.kt` 漂移**（其余 4 份逐字节一致）。
覆盖层那份是**旧快照**（45,791 B / 906 行），仓库那份已经 **53,415 B / 1,035 行**，
差的内容恰好就是这几轮加的：

* `envLabelState` + `EnvLabelBadge` ⇒ **右下角景色标签**
* 竖滑的 **E52 分支**（`theme == VisualizerTheme.HIGHWAY → requestEnvSwitch()`）
* 遥控器上下键的 **E52 分支**
* 帧率徽章改走渲染器真实帧率（`renderFps()`）+ `fpsdiag`

⇒ ⇒ **"上下滑动不能切景色"与"右下角没字"两条都不是运行时 bug，
是我这几轮的代码压根没进包。**

#### ⑯-3 修法（三层）

| 层 | 做法 |
|---|---|
| ① **同步** | 新增 `scripts/sync_e51_overlay.py`（仓库根跑）：把 5 份被遮蔽文件 + `e51/**` 从仓库复制到覆盖层；`--check` 只查不写 |
| ② **门禁** | 新增 `E51OverlayDriftGateTest`：覆盖层存在 **且** 仓库有 E51 源码时，被遮蔽文件必须**逐字节一致**，否则**构建失败**（不是"本地悄悄用旧版"）。CI / 别人的机器无覆盖层 ⇒ 跳过；`main` 分支仓库无 E51 源码 ⇒ 跳过 |
| ③ **兜底** | 竖滑改在 **View 层**（`HighwayGLSurfaceView.onTouchEvent`）也能识别 ⇒ **不依赖** Compose 命中测试是否收得到事件；两条路并存 ⇒ 用 `lastTouchSwipeNs`（500 ms 窗口）**去重**，避免一次手势连切两次 |

⛔ **兜底那条为什么必要**：E52 走 `AndroidView` + `GLSurfaceView`（**真实 View**），
而 E51「像素城市」走 **Compose Canvas** 路径（`LofiCityRenderer` 明确"不走 WebView"）
⇒ 两者**不是同一条触摸通路** ⇒ 不能拿 E51 的成功与否推断 E52。

#### ⑯-4 本轮保留的两处加固（本身是对的）

* **景色标签每帧回写** `HighwayGlHolder.lastEnvLabel`
  （`unregister(view)` 会清空它 + GL 上下文**可能复用** ⇒ `onSurfaceCreated` 不再跑 ⇒ 标签会永久为空）。
* **四处无条件诊断日志**（舞台 / 渲染器 / View / 场景）—— 本轮正是靠
  "某处**一条日志都没有**"一眼定位到"代码不在包里"。

#### ⑯-6 真机复验结果：**竖滑 ✓ / 右下角标签 ✓ / 帧率徽章 ✓**（且证伪了"命中顺序"）

装包后手机实测（小米 22081212C / Android 15，logcat）：

```
vertical swipe: theme=HIGHWAY drag=-438.76172 threshold=196.8 dragged=true renderer=HighwayRenderer   ← 47 次
手动切换景色：请求换环境（随机一个组合）                                                            ← 47 次
env label badge = "丘陵 · 夏季 · 日出 · 平坦" (empty=false)                                          ← 48 次
fpsdiag renderer=HighwayRenderer viewBased=true renderFps=130.08 badge=130.08 choreoFps=0.0
```

⇒ **竖滑切景色生效**、**右下角标签有值了**、**帧率徽章改走 GL 真实帧率**（130 fps，不是 59）。

ⓘ **顺带证伪了我上一轮那条"命中顺序"推理**（真机证据，不是推理）：

> ❌ 上一轮我断定「`pointerInput` 挂在 `AndroidView` 的**父级** ⇒ 命中测试先给子
> ⇒ 父级收不到 touch ⇒ 竖滑失效」。
> ⛔ **被证伪**：Compose 的 `pointerInput` 在真实 View（`AndroidView` / `GLSurfaceView`）
> **之上照样收得到触摸** —— logcat 里 `vertical swipe: theme=HIGHWAY dragged=true` 就在那儿。
>
> ⇒ **真正的关键不是"挂哪儿"，而是「GL View 会不会把触摸吃掉」**：
> AOSP 的 `GLSurfaceView.onTouchEvent` 返回 **true** ⇒ **吃掉**触摸 ⇒ Compose 收不到；
> ⇒ 本实现覆写成 `return false`（**穿透**给 Compose）⇒ 手势才通。
> ⇒ 门禁 `HighwayEnvManualSwitchGateTest.GL View 不得消费触摸` 钉住这一行。

⚠️ **推论**：⛔ **不要在 GL View 里自己实现竖滑** —— 返回 false 意味着本 View **不是**触摸目标
⇒ 只收得到 `ACTION_DOWN`、收不到 `ACTION_UP` ⇒ 判不了"滑了多远" ⇒ 逻辑不可达
（这也是我一度加的"View 层兜底"为什么是**死代码**、已删除）。

#### ⑯-8 「为什么刷不到树多的景色？」⇒ **上游也没有这种景色**（种子才是关键）

所有者反馈：「为什么我没有刷到树比较多的景色？」⇒ 查**上游 chunk 原文**（本机存着
`logs_temp/slowroads/main.e7a33c55.chunk.js`）得到决定性答案：

⇒ 上游的随机种子 = `Date.now()` 的十六进制截断（即**时间戳**）—— 所以上游每次打开页面都是新世界；
⇒ 种子会被**记进 `localStorage`**（键名 `seed`）⇒ 下次打开沿用上次的种子；
⇒ URL 上带 `?seed=<值>` 可**覆盖**种子；
⇒ **树图**由这个**全局种子**派生（上游建图时拿的就是这个种子）；
⇒ Planet 场景在上游零植被（`hasTrees` / `hasGrass` 恒为 false）。

⇒ **树图 / 地形 / 路线全部由种子派生** ⇒ 上游**每次打开页面都是一个新世界**；
⇒ 而上游的**换景色**（sky / fog / skin / weather / topography）**不换种子**
⇒ **「树多的景色」在上游不存在这个概念** —— 决定树量的是**种子**，不是景色。

⚠️ **而我们把种子钉死成 `85de00b5`**（早期为 parity 对账**可复现**才挑的常量）
⇒ 所有景色共用**同一张树图 + 同一条路线** ⇒ 刷 47 次看到的树量都一样。

⇒ 顺带查实：我们的 `getXZ` 与上游 `Ea.getXZ` **逐字一致**
（`Ga.h += heightOffset` → 三分支判定），⇒ **LOD 判定本身没有偏差**；
LOD 0 ⇒ 上游 `getTreesAt` 返回 null ⇒ 那里本来就**没有独立乔木**（只有地形贴图上的森林色块）。

#### ⑯-9 所有者裁决：手动切景色 ⇒ **顺带换世界（换种子）**

| | 上游 | 我们（裁决后） |
|---|---|---|
| 种子默认值 | 时间戳 ⇒ 每次打开都不同 | 启动时 `85de00b5`；**手动切景色时换成新时间戳** |
| 换景色 | 只换 sky/fog/skin/weather/topography | **换外观 + 换世界**（新地形 + 新树图 + 新路线） |
| 换世界 | 独立的『新种子』按钮 + 页面重载 | 并进"手动切景色"（我们是常驻进程，没有页面重载这一说） |

**实现要点**（三个坑都要踩对）：

| 要点 | 做法 | 为什么 |
|---|---|---|
| ⛔ 换种子**不能**在切色的瞬间改 | 只置 `seedRotationPending`，**在 [buildWorld] 里**换 | 换种子与世界重建**原子** ⇒ 不会出现"新地形 + 旧树图"的半新半旧世界 |
| ⛔ 换种子必须**强制重建世界** | 重建条件加 `seedChanged \|\| requiresTerrainRebuild(...)` | 否则场景种类/档位没变时**不重建** ⇒ 种子换了地形还是旧的 |
| ⛔ 种子必须**打进日志** | `换世界：新种子 seed=…` + `world ready … seed=…` | 所有者要求：日志里能复现看到的那片世界 |

**种子生成**（上游同款）：`Date.now().toString(16).substring(3)`
⇒ 同毫秒连按会撞 ⇒ 与上一个相同时改用 `nanoTime` 兜底（保证**一定**换掉世界）。

**真机验证（2026-11-02 17:08，小米 / Android 15）**：

```
17:08:02  换世界：新种子 seed=9b840284  →  world ready  scene=HILLS  topo=normal  seed=9b840284  (23 ms)
17:08:04  换世界：新种子 seed=9b840c76  →  world ready  scene=PLANET topo=normal  seed=9b840c76  (40 ms)
17:08:13  换世界：新种子 seed=9b842efb  →  world ready  scene=PLANET topo=flat    seed=9b842efb  (14 ms)
17:08:30  换世界：新种子 seed=9b8470fb  →  world ready  scene=HILLS  topo=normal  seed=9b8470fb  (20 ms)
（启动时是钉死的 85de00b5）
```

⇒ 4 次滑动 = **4 个不同种子** + 4 次世界重建（14~40 ms），**0 报错**。

**树的实测变化**（本轮的核心目的）：

| | 乔木 | 草 | 灌木 | 近景格树图 LOD 0 占比 |
|---|---|---|---|---|
| 旧世界（`85de00b5`） | **19** | 10,775 | 228 | **120 / 121 格**（几乎整片"无独立乔木"） |
| 新世界（`9b8470fb`） | **79 → 111** | 14,607 → 11,290 | **1,208 → 1,398** | **8 / 121 格** |

⇒ 乔木 **19 → 79~111 棵（4~6 倍）**、灌木 **228 → 1,300+**
⇒ **实证**：之前"全无乔木"只是**那一个种子恰好落在一片无乔木区**
（旧种子近景 121 格里 120 格是树图 LOD 0 ⇒ 上游 `getTreesAt` 返回 null ⇒ 那里本来就没有独立乔木）。
⇒ ⇒ **"树多的世界"现在刷得到了**（每次滑动换一张树图）。

⇒ 新增门禁 `HighwayWorldSeedGateTest`（5 例）：新种子是十六进制且长度合理 /
连按两次一定不同 / 换种子只发生在 `buildWorld` 里（原子）/ 换种子强制重建世界 / 种子进日志。

#### ⑯-7 「平坦」档为什么全屏是路面、视角贴地 —— **上游设计，不改**

所有者实测反馈：「丘陵-秋季-雨-秋-平坦」里基本全屏都是路面、很宽、视角贴得很低；
「所有平坦视角都很低」。

⇒ 核对结论：**是上游 Slow Roads 的设计，不是本实现的 bug**（按所有者裁决"是设计就不用改"）。

| 量 | 上游值 | 本实现 | 出处 |
|---|---|---|---|
| `flat` 档 `heightScale` | **0**（高度场关掉 ⇒ 纯平原） | 同 | 上游 `main:2878-2888`（Hills）/ `:10570-10580`（Planet） |
| `flat` 档 `roadWidth` | **20** ⇒ 路面**半宽 20** ⇒ **全宽 40 m** | 同（`HighwayTopo.FLAT`） | 同上 |
| 追尾机位 | 俯角 `0.3` rad、`yOffset 0.8`、`range 3.5` ⇒ **eye ≈ 路面 + 1.83 m** | 同（`HighwayCamera`） | 上游 `main:15357-15367` |

⇒ **相机高度与地形档无关**（永远贴着路面 1.8 m）：
* 丘陵 / 困难档之所以"看起来高"，只是**周围地形起伏把视线垫起来了**；
* `flat` 档 `heightScale = 0` ⇒ **一马平川** ⇒ 视线没有任何东西可垫
  ⇒ 只剩那条 **40 m 宽**的直路 ⇒ **全屏是路 + 贴地**。

真机日志对账（同一包）：

```
world ready … scene=HILLS topo=flat roadWidth=20.0 smoothWindow=7          ← 路宽确实是上游的 20
diag#49920 eye=(52.75,94.16,38.37)  vehicle.y=92.37  ⇒  eye − vehicle ≈ 1.79 m  ← 贴地 1.8 m ✓
```

⇒ **结论：不改**（保真上游）。⚠️ 已知遗留：乔木稀少是**上游树图 LOD 行为**（`treeLod[0]=120`
⇒ 近景 120/121 格是 LOD 0，本来就无树），不是本实现的回归。

#### ⑯-5 教训

1. ⛔ **仓库外覆盖层 = 本地构建 ≠ 仓库代码** ⇒ 凡有"仓库外注入"的机制，**必须有漂移门禁**。
2. ⛔ 上一轮写的「命中顺序」推理（父级 `pointerInput` 收不到 touch）**仍成立与否待验**
   （代码没进包 ⇒ 当时无从检验）⇒ 但**已不重要**：View 层兜底让竖滑不依赖那条推理。
3. ⛔ 之前那些"根因分析"（白场 quad / 越界 / 命中顺序）要逐条复核：
   **日志为空的那些结论都是猜的**，只有"日志里确实有那条记录"的结论才成立。

### ⑰ 本轮门禁与验证（累计）

* `com.nasmusic.tv.visualizer.highway.*`：**34 类 201 例零失败**（`--rerun-tasks` 强制重跑）。

---

## 2026-11-02 第十七轮：路牌逐位还原 = **移植上游 `signController`（`Ma`）**

> 本轮**只碰模型摆放**（⛔ 不动水面 / 种子 / 换世界那几块 —— 并行会话在改）。
> 详细记录见 `docs/technical-overview.md` **§10.329**。

### ① 起因：原 KDoc 那句「上游的摆放规则藏在场景图里」**是错的**

上游 chunk 原文（`logs_temp/slowroads/main.e7a33c55.chunk.js`）查实：**没有场景图**。
路边道具的位置是**四个控制器沿道路链算出来的**（`Ma` 路牌 / `za` 桥 / `La` 反光片 / `Ia` 护墙）。
⇒ **路牌可以逐位还原** ⇒ 本轮把 `Ma` 移植成纯逻辑模块。

### ② 做了什么

| 项 | 内容 |
|---|---|
| 新增 | `app/src/main/java/com/nasmusic/tv/visualizer/highway/world/HighwaySignPlacer.kt`（**纯逻辑**：不碰 GL / 不碰 Android） |
| 新增门禁 | `app/src/test/java/com/nasmusic/tv/visualizer/highway/world/HighwaySignPlacerGateTest.kt`（**11 例**，普通 JUnit4） |
| 接线 | `HighwayGlScene.buildModelPlacements()`：**只有 `Kind.SIGN`** 改走 `HighwaySignPlacer`（root 起点 = `车辆节点 + 3`，照抄上游 `Ma.initialise()`）；⛔ 其余种类保持不动 |
| 更正 | `HighwayModelPlacement` 的 KDoc（那句错误说法已改）；`HighwayModelPlacementGateTest` 只加头部说明，断言未动 |

### ③ 上游算法出处（chunk 偏移）

`Ma.initialise()` 375266 / `Ma.update()` 376504 / `checkPlaceTurnSign()` 376705 /
`combineWithLastGradSign()` 379823 / `checkPlaceGradientSign()` 380190 / `checkPlaceChevron()` 383805 /
`const Da = 1.5 * Math.PI` 374221 / 牌面变体常量 167928。

### ④ 牌面变体（**逐张抠出上游 12 张贴图核对**）

| 上游 | 值 | 贴图 | 我们的枚举 |
|---|---|---|---|
| `$i` | 0 | 左转箭头 | `TURN_LEFT` |
| `es` | 1 | 右转箭头 | `TURN_RIGHT` |
| `ts` | 2 | 左转加剧 | `TURN_LEFT_UPDATED` |
| `is` | 3 | 右转加剧 | `TURN_RIGHT_UPDATED` |
| `ss` | 5 | 上坡 15% | `GRADIENT_UP` |
| `as` | 6 | 上坡 20% | `GRADIENT_UP_STEEP` |
| `ns` | 8 | 下坡 15% | `GRADIENT_DOWN` |
| `hs` | 9 | 下坡 20% | `GRADIENT_DOWN_STEEP` |
| `rs` | 10 | 人字箭头（朝左） | `CHEVRON_LEFT` |
| `os` | 11 | 人字箭头（朝右） | `CHEVRON_RIGHT` |

⛔ 变体 4 / 7 上游存在但 `Ma` 从不使用。
⛔ **变体只影响数值输出**（渲染侧仍用同一个 `sign.obj`，纯色）—— 我们没有上游那 12 张贴图。

### ⑤ ⭐ 静态遍历 ≡ 上游逐点决策（等价性）

上游是「**车辆相对**的滚动控制器」，我们是「整条链静态跑一遍」，两者逐点等价：

1. `signController.update()` **只在车辆节点前进时调用一次** ⇒ **root 恒定领先车辆节点 3 个节点**
   ⇒ 上游的 root 就是沿链按序号递增走一遍 ⇒ 静态遍历同序、同点；
2. **三个前瞻窗都是相对 `root` 的局部窗口**（转向 `root+1…root+6` / 坡度 `root…root+9` /
   人字箭头 `root+1…root+5`）⇒ 决策**不含任何车辆相关的量**；
3. 控制器状态只被上一次的 `update()` 修改 ⇒ 同一段节点序列 ⇒ 同一串决策。

⇒ 这条论证被写成**可执行断言**（门禁的「决策窗口是相对 root 的局部窗口」、
「链尾延长不影响已放好的牌」两条用例）。

### ⑥ 门禁（`HighwaySignPlacerGateTest`，11 例，全部**手算可核对**）

| # | 用例 | 断言 |
|---|---|---|
| 1 | `直路不放任何牌` | `da ≡ 0` ⇒ 累计转角永不超 0.9 ⇒ **0 块** |
| 2 | `急弯在预期节点放一块转向牌且位置在路肩外侧一米` | 锚点 = 节点 1；位置 `(10, 0, −4)`（= `w + 1` = 4 m ⇒ 路缘 3 m 外 1 m）；朝向 `1.5π`；变体 `TURN_RIGHT` |
| 3 | `两次转向之间至少隔十个节点` | 两块牌锚点 1 与 12 ⇒ 相隔 **11 > 10**；第二块走 `da < −0.1` 分支 ⇒ `+n` 侧、向 `1.5π − 0.8` |
| 4 | `桥上节点不放牌` | 锚点候选节点 1..4 全是桥 ⇒ **0 块** |
| 5 | `陡坡放坡度牌且变体是上坡` | `dH = 13.5`（> 12 不 > 16）⇒ `steepness = 1` ⇒ `GRADIENT_UP`；锚点 = 节点 2 |
| 6 | `下坡放坡度牌且变体是下坡` | 镜像 ⇒ `GRADIENT_DOWN` |
| 7 | `转向方向翻转时牌换成加剧变体` | 10 节点内反向 ⇒ 已放的牌变 `TURN_LEFT_UPDATED`（上游 `ts`） |
| 8 | `决策窗口是相对root的局部窗口` | 窗口 `Σ da[root+1…root+6]`：`root=3` ⇒ 0.8（不触发）、`root=4` ⇒ 1.0（触发）⇒ 首块锚在 `4−3 = 1` |
| 9 | `链尾延长不影响已放好的牌` | 链长 40 与 90 ⇒ 前若干块**逐位相同** |
| 10 | `变体表与上游常量一一对应` | 10 个枚举的「上游常量名 + 变体号」逐个钉死 |
| 11 | `摆放是确定性的` | 两次跑同一输入 ⇒ 完全一致 |

**验证方式**：

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
./gradlew.bat testDebugUnitTest --tests "*Highway*" --console=plain --no-daemon -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks
```

**结果**：**11 / 11 通过**（`HighwayModelPlacementGateTest` 6 例仍绿）。

### ⑦ ⛔ 与上游仍有的差异（6 处，详见 §10.329 第五节）

1. 没有牌面几何（变体只影响数值输出）；
2. `nodeIndex` 是我们的量（上游那个位置参数是**退役索引**）；
3. 合并坡度牌的 `nodeIndex` 用「被合并的转向牌的锚点」而非上游的**陈旧值**；
4. 人字箭头的「精细点」只覆盖链头 120 个节点（更远的只出节点级那一块）；
5. 链被重建时状态不重置（上游 `handleMidlineReset()` 什么都不做 ⇒ 状态跨链保留）；
6. `w` 取节点自带值（上游同源，登记备查）。

### ⑧ ⛔ 本轮**没做**的

* 其余三个控制器（`za` 桥 / `La` 反光片 / `Ia` 护墙）⇒ **护墙 / 桥 / 反光片 / 星球道具仍未移植**；
* 牌面贴图（12 张 PNG）⇒ ⛔ 不新造资产，渲染侧仍是同一个 `sign.obj`；
* 变体的渲染区分（现在所有牌都是纯色）；
* 真机复验（本轮只跑离线门禁）。

---

## 2026-11-02 第十八轮：**水面看不见 = 上游行为**（定案）+ 加诊断开关 + 修掉 E51 遗留 lint

> 本轮三件事：① 给「水面为什么看不见」**定性**（结论：**上游行为，不是我们的 bug**，三条证据）；
> ② 因为**水面渲染链路从来没被显示过 ⇒ 从来没机会暴露 bug** ⇒ 加**诊断开关**把链路逼出来；
> ③ 顺手把**存留的 E51 lint 错误**清掉（另一个会话留下的 `NewApi` 24 条 + debug manifest 3 条）。

### ① 水在哪里出现 —— **三条证据指向「上游行为」**（真机结论见 ②.5：水面**看得见**，在低洼盆地成片出现）

| # | 证据 | 内容 |
|---|---|---|
| 1 | **上游的海平面 = 世界 y = 0** | 上游 `mesh.position.y = originPos.y` 与 `sea.position.y = -mesh.position.y` **相消** ⇒ 海平面落在世界原点高度。**我们抄的正是这个**（`seaLevelY` 默认 0） |
| 2 | **上游「海」的判据 ≈ y 2.5** | SeaWall 生成器 `wa.waterHeight = 2.5` / `waterThreshold = 3`，判据 `e.p.y <= 2.5` ⇒ 上游的海也在 **y ≈ 2.5**，与我们只差 2.5 m |
| 3 | **数值实测：路恒在海平面之上** | 探针（`logs_temp/slowroads/parity/SeedWaterProbe.kt`，与 `HighwayWaterMesh` **同口径**模拟 29×29 / 步长 50 m）跑多个种子：`85de00b5` 水面网格 **609/841** 顶点在水下、但**路最低海拔 +5.1**；`9b8470fb` 647/841、路 +3.6；`w014` 436/841、路 +2.0 ⇒ **所有种子的路都 > 0** |

⇒ ⇒ **路恒在海平面之上 ⇒ 水面永远在路下方几十米 ⇒ 近处看不到、远处低地才是「水」**。
⇒ ⇒ **不改口径**（保真上游）。地形档的 heightOffset 也都 ≥ 50（Hills 84/100/100/50，Planet 500/500/500/50）⇒ 同样抬高不了水面。

### ② 但**渲染链路至今没被验证过** ⇒ 加诊断开关把它逼出来

| 项 | 内容 |
|---|---|
| 新增设置 | `Settings.Global` 的 **`nasmusic_sealevel`**（抬海平面，米）与 **`nasmusic_seed`**（钉住种子，复现用） |
| 实现 | `HighwayGlScene.readSeaLevel(context)` 读设置；水面网格的**深度同步相对化**（`hm.getXZ(...) − seaLevelY`）⇒ 抬海平面时 `discard` 规则**仍成立** |
| 默认行为 | **0 = 上游口径** ⇒ 与改动前**逐位相同**（诊断开关默认不改变任何输出） |
| 日志 | `water: 海平面以下顶点 N/M ⇒ 有水 / 无水`（每世界构建一次） |
| 门禁 | `app/src/test/java/com/nasmusic/tv/visualizer/highway/world/HighwayWaterVisibilityGateTest.kt`（3 例） |

```bash
adb shell settings put global nasmusic_sealevel 60   # 抬海平面 ⇒ 水出现在路边
adb shell settings delete global nasmusic_sealevel   # 复原
```

#### ②.5 ⭐ 真机取证（2026-11-02，手机 22081212C / Adreno 730）—— **要更正一条结论**

| # | 操作 | 观测（logcat + 截图） |
|---|---|---|
| 1 | 默认口径（`nasmusic_sealevel` 未设置 = 上游 y=0），丘陵·冬季·日落·冬·困难 | ⭐ **画面里能看见水**：道路正前方一片**湖面**（横贯中景），后面是丘陵剪影（`output/water_sea0.png`）。日志 `water: 海平面以下顶点 195/696 minDepth=-27.2 ⇒ **有水**`。同期左侧还能看到**上游算法摆的路牌** |
| 2 | `nasmusic_sealevel 60` + 钉种子 `9b8470fb`（丘陵·夏季·晴朗·普通） | 日志 `海平面诊断：seaLevel=60.0` / `世界种子覆盖：nasmusic_seed=9b8470fb` / `water: 204/232` 与 `516/696 ⇒ 有水` ⇒ **诊断开关与钉种子都生效**。但这个世界的路在**山脊**上 ⇒ 海平面抬到 60 仍**够不到路面** |
| 3 | `nasmusic_sealevel 120` | 用户确认**看到水**（水面铺到低处、贴近路基） |
| 4 | 删除两个设置 + 重启 app | 无「海平面诊断」「世界种子覆盖」日志 ⇒ **已回上游口径**；默认世界 `water: 16/116 minDepth=-36.7 ⇒ 有水` |

⇒ ⭐⭐ **更正上一轮的结论**：「水面看不见」**不准确** ——
**水面是看得见的**（成片出现在**道路两侧/前方的低洼盆地**，日落场景里就是那片湖面）；
真正的现象是**水不贴着路边** —— 因为**路恒在海平面之上**（路面 +2~5 m，且常是台地/山脊），
⇒ 水在**下方几十米**的盆地里，从路面看过去是**远处的成片水面**。
⇒ 「水面渲染链路有问题」这个假设被证伪：着色器 / 贴图 / 混合 / 深度**全部正常**。

### ③ E51 遗留 lint：24 条 `NewApi` + 1 条 `MissingTvBanner` —— 已清零

**⛔ 先更正根因（上一轮的判断是错的）**：24 条 `NewApi` 的根因**不是**「`decodeLand5` 读了正则分组」——
`groupValues[n]`（**索引**访问）走 `Matcher.group(int)` ⇒ **API 1**；只有命名组 `groups["名"]` 才走 API 26。

**⇒ 字节码取证**：全 app **12838** 个 class 扫常量池，
`java/util/regex/Matcher` = **0 命中**、`kotlin/text/MatchGroupCollection` = **0 命中**
⇒ **那条 API-26 路径结构上不可达** ⇒ ⛔ **不是「电视上会 NoSuchMethodError 崩溃」**。

**真正的根因 = lint 的解析缺陷**：lint 把**下标访问**误解析成 `MatchGroupCollection#get(String)`。

| 证据 | 内容 |
|---|---|
| 报的位置**串位** | 行号差 1~2、列号指向 `==` 而不是 `[` |
| 12 处（×仓库/覆盖层两份 = 24 条）**同构** | 「**跨文件顶层 `FONT3`**（`e51/engine/PixelFont.kt`）取值 → 下标访问」 |
| **同形态的同文件私有表完全干净** | `LofiCityToronto.FONTS`（`val row = g[j]`）、`LofiCityVenice.BIG4`（`val row2 = rows[j]`）**均未被报** |
| `Map.get` / `List.get` / `String.get` 都正常 | `FONT3[ch]`、`BAT_GROUPS[0]`、`row[i]` 均未被报 |

**处置**：

| 项 | 做法 |
|---|---|
| 7 个被误报的文件 | `@file:Suppress("NewApi")` + **在文件里写清取证**（不是无理由压制） |
| **真正的防护** | `E51NoApi26GateTest`（源码门禁）：E51 全树**禁读正则分组** ⇒ 将来谁真写了 API-26 调用，门禁会红 |
| `MissingTvBanner`（**实打实的**） | 上一轮给 `GlProbeActivity` 补 `LEANBACK_LAUNCHER` 引入的 ⇒ 补 `android:banner="@drawable/banner"`（与主 manifest 的 `MainActivity` 同一套写法） |
| 保留 `decodeLand5` 的无正则扫描器 | ⛔ 虽基于错误根因，但它**确实**去掉了该文件里唯一的正则（等价性有逐位回归兜底）⇒ 保留，不回退 |

⚠️ **覆盖层同步**：7 个文件都在 E51 覆盖层内 ⇒ 改完必须先跑 `python scripts/sync_e51_overlay.py`
（否则本地编译的还是覆盖层旧副本，`E51OverlayDriftGateTest` 会红）。

---

## 当前状态快照（2026-11-02）

> 本节取代 `第十轮现状清单（17:37）` 里的**进度部分**；
> 那份清单的 **A/B/C/D 问题清单仍然有效**（真机问题与未开发项），两处不冲突。

### 一、已完成（本轮会话；均已本地提交、**未 push**）

| 里程碑 | 内容 | 门禁 | 提交 |
|---|---|---|---|
| **P7-1** | Planet 四档 topography / 三 skin 绑定 / 植被恒 0 | G24–G26 3/3 | `e4d5cd5` |
| **P7-2a** | Planet 高度场（陨石坑 `zn`）独立类 + node **位级**基准 | `HighwayPlanetHeightGateTest` 4/4；`HighwayWorldGateTest` 5/5 仍绿 | `4a6d64f` |
| **文档更正** | 方案 F-03「顶点复用 V-01」结论被推翻（Planet 顶点六处差异） | — | `a041145` |
| **P7-2b-1** | Planet 一对 vert/frag（`D10779+D10783` / `D10787+D10791`）；生成器扩容；**生成物/脚本漂移修复** | `HighwayGlslParityTest` 6/6（逐字节 17 片段） | `3bf71e9` |
| **P7-2b-2（纯逻辑）** | Hills/Planet 互斥 + 着色器对 + 地形重建触发 | `HighwaySceneChoiceGateTest` 5/5 | `dad6801` |
| **P7-2b-2（前置改造）** | **地形高度源契约** `HighwayTerrainHeightSource` + 下游（路面链/地形网格/场景）改接口 + Hills 转调等价 | `HighwayTerrainHeightSourceGateTest` 3/3；世界核 5/5 仍绿 | `e6bc9c8` |
| **P7-2b-2（GL 接线）** | ① 双 program（`terrainHills`/`terrainPlanet`，Hills 路径逐字冻结）② `buildWorld()` 按场景选高度场 + `?topo=` 覆盖环境集 ③ 白帧里 `rebuildWorldForScene()` 重建 world + 地形 VBO | `HighwaySceneWiringGateTest` 6/6；`HighwaySceneChoiceGateTest` 8/8 | 本轮 |
| **Planet 贴图驻留** | `HighwayPlanetSkinTextures`（14 采样器 → 文件）+ `HighwayTextures.loadOr`（缺图退黑） | `HighwayPlanetSkinTexturesGateTest` 6/6 | 本轮 |
| **P6-3b-2** | 环境常量（天空 / 雾 / 三灯 / colouration）全部改由 `AppliedEnv` 供值；开屏值与旧硬编码**逐项相等** | `HighwayEnvUniformGateTest` 5/5 | 本轮 |
| **贴图按 skin 重组** | `HighwaySkinTextures`：Hills 地形 albedo / 路面 ribbon / 植被 / 云（`skyMap` 三态） | `HighwaySkinTexturesGateTest` 7/7 | 本轮 |
| **P8 自适应回退** | `HighwayAdaptiveQuality`（p95 / 120 帧 / 降 2 s / 升 8 s / 最多降 2 升 1）+ 接线到 `setFixedSize` | `HighwayAdaptiveQualityGateTest` 7/7 | 本轮 |
| **P8 旋钮 ②③** | `HighwayQualityTier`（三档 ⇒ `renderScale` / `res` / 贴图上限）+ `setQualityTier()` 运行时切换；②③ 只在 LOW 档生效（不破 G18） | `HighwayQualityTierGateTest` 5/5 | 本轮 |
| **P5（离线可验部分）** | `.obj` 解析器（口径对齐 three.js r144 `OBJLoader`）+ 独立基线脚本 + 水面 `vDepth` 规则纯逻辑 | `HighwayObjParserGateTest` 5/5；`HighwayWaterDepthGateTest` 6/6 | 本轮 |
| **P5 / G16 水面（渲染侧）** | 上游四段水面对照 GLSL 抽出 ⇒ 生成器 ⇒ `WATER_*_SHADER`；水面 program + 1400×1400 / Ti=28 的网格 + per-vertex 深度（分帧刷新）+ `vDepth > 10` 丢弃 | `HighwayWaterGateTest` 8/8 | 本轮 |
| **P5 / G17 路边模型（渲染侧）** | `HighwayModelPlacement`（纯逻辑：每 24 节点、右侧 8 m、面向来车）+ 自写 `MODEL_*_SHADER` + 每实例 `uModel` 矩阵；⛔ 交通车按 D20 不做 | `HighwayModelPlacementGateTest` 6/6 | 本轮 |

* 验证口径：`com.nasmusic.tv.visualizer.highway.*` **34 类 201 例零失败**（`--rerun-tasks` 强制重跑）。
* 行尾：索引 blob **全 LF**（CR=0）；
* 版本号 **未升**：`versionCode 195` / `versionName "2.39.10"`。

### 二、未完成（⚠️ **2026-11-04 按第二十一~二十三轮进展重排**）

> ⭐ **2026-11-04 的结论：E52 的技术实现基本收口；真正卡住发版的是 A5（创维电视整场首跑）——
> 效果一次都没在目标电视（PowerVR Rogue G6110 / GLES 3.1 / Android 5.1.1）上跑过。**
> 下面按「**影响什么**」分组，而不是按历史顺序。

#### A. 🔴 发版阻断

| 项 | 是什么 | 影响 | 阻塞点 |
|---|---|---|---|
| **A5 创维电视整场首跑** | 效果**一次都没在电视上跑过**（R-01/R-02/R-05/R-08 全待验） | ⭐ **最大风险**：电视是 PowerVR Rogue G6110 / **GLES 3.1** / Android 5.1.1 ⇒ 手机（Adreno 730 / GLES 3.2）验过的一切在电视上**未验证**：12 个 PBR program 的 PVRShaderCompiler 开销、显存、TBDR 早期深度剔除、shader 兼容性 | ⛔ 电视 `192.168.0.114` 失联（11-03 掉线）⇒ **需所有者开机并放行** |
| **C3 `deviceTier` 标定** | 只在小米标过（GLES 3.2 / Adreno 730 / 顶点纹理单元 16） | 创维机档位判定错 ⇒ 自适应降档挑错档 | 同上（需电视） |

#### B. 🟠 验收完备性（手机全可做，不影响"能不能发"）

| 项 | 是什么 | 影响 |
|---|---|---|
| **A4** 三档切换 / 30 min soak / 竖屏构图 / 不撞车（G27–G30） | 真机门禁 | 自适应降档 + 三个旋钮在真机是否成立 |
| **A6** Planet 场景首跑 | `nasmusic_env 4,0,0` | 第二个场景是否可用（Planet **恒无云** = 已知取舍，云图未移植） |
| **A7** LOW 档独立参考帧 | 低档基准帧 | ⭐ **卡住 C2**（LOW 档贴图 256² 一直等它） |
| **A8** 远处露底色缝 | 根因已定性（第八轮）：相邻两层/两格边界高度不连续 1~数米 | 远景观感 |
| **R1′ / R4′** | 牌面显示图案 / 牌面随天气变亮 | 第二十二轮牌面修复的验收 |
| **R5 ~ R8** | 海墙棱线硬边 / 桥面护栏浅蓝 `0xBBCCFF` / 桥影叠压 / 墙不整段消失 | 第二十一~二十二轮那批渲染改动的验收 |
| **D1** 探针收尾 | mesh prof / hitch / EMA / 图层掩码 / 自驱 AB | 按约定**四项全验收后**才能删诊断日志 ⇒ 依赖 A4 的四项 |

#### C. 🟡 只影响 G18 指标（不影响真机画面）

| 项 | 是什么 | 影响 |
|---|---|---|
| **n140 整帧看不到天空** | **原型专属**的远景几何覆盖差异（上游 700 m 方形瓦片 + overlap 下沉 vs 原型扇形 `farEdges`）；已排除相机 / 高度源 / overlap 下沉 | 只拉低 G18 的 SSIM/ΔE；**Kotlin 侧远景格用原始 `getXZ` ⇒ 真机画面不受影响** |
| **G18 指标本身** | 当前 SSIM **0.6896** / ΔE **2.629** | 硬断言只查「不回退」；0.92 / ΔE ≤ 3 是软目标 ⇒ **不阻塞**。⭐ **第二十三轮的天空修复不影响 G18** —— G18 比的是「JS 原型 vs 上游」，而原型的天空材质本来就带 `fog: true`（`docs/archive/e52-highway-webgl-preview.html:3697-3699`） |

#### D. 🟢 已知取舍 / 明确不做

| 项 | 影响 |
|---|---|
| 护栏 / 桥面护栏的**法线贴图** | three.js `normalMap` 需片元导数求切线帧 ⇒ ESSL 1.00 做不到 ⇒ 细节保真 |
| `Program.SOLID`（`MODEL_FS`）**没有逐面翻法线** | 上游同样 `flatShading` 的 `Ds`/`wa`/`xa`（混凝土海墙 / 桥墙段）背面偏暗（`MODEL_FS` 同时服务路边道具，逐个材质核对上游 patch 才能翻） |
| **墙面背面剔除** / **砖面剔除** / **Planet 护栏几何**（上游 4 顶点 vs 我们 3 顶点） | 开剔除有「墙整段消失」风险 ⇒ 明确不做 |
| **干砌石 `Gs` / 栅栏 `la`** | 上游 `la` 需 `detailLodIndex > 1`（默认 1 ⇒ **默认永不出现**）⇒ 不做也对 |
| **交通车** | 按 D20 明确不做 |
| **卡顿 B（GPU 83 ms/帧 ≈ 12 fps）** | 手机上 114 fps ⇒ 只影响电视端；`nasmusic_hw_mask` / `nasmusic_hw_scale` 两个 `Settings.Global` 开关可离线 AB（不用重编） |
| **触摸拦截**（`AndroidView` 全屏覆盖可能拦截触摸） | 上下滑切换景色在真机的可靠性 |
| **天空平面 Y 固定 200** | 上游是 `vehicle.y + 225`（`skyOffset`）⇒ 相机高于 200 m 时天空掉到下方变「地板」（长坡 / 高山场景的潜在雷）⇒ 独立议题，改它会动 sky 指标 |
| **跨水面的「没有桥」** | ⭐ **owner 裁决：保持上游行为，不建桥**（上游在开阔水面永不触发建桥）⇒ 已结案，见 `docs/technical-overview.md` §10.335 §九 |

#### 已完成（不必再做）

| 项 | 状态 |
|---|---|
| A2+A3 对账管线 + 上游黄金帧 | ✅ 第十九轮（硬件 ANGLE D3D11 ⇒ 12 帧黄金帧均值 600 ms；同会话复抓 12/12 字节相同） |
| A1 G18 门禁 | ✅ `HighwayFrameParityGateTest`（6 例；硬断言=回归护栏 / 软目标=差距可见不阻塞） |
| B1 三个控制器移植 | ✅ `Ia`(Hills) / `Fh`(Planet) / `za`(桥) / `La`(反光片) |
| B2 路牌牌面 + B3 道具贴图 | ✅ 12 张牌面逐字节取自基准 chunk |
| C1 旋钮 2（`res`）解耦 | ✅ 切槽改解析式 + 植被走保真分辨率参考网格 |
| B4 / B5 | Planet 恒无云（已知取舍）· 交通车（D20 不做） |
| E1 文档刷新 | ✅ 本节 + §10.330~§10.336 |

### 三、风险与上机必查

* ⛔ **本轮改了画面**（P6-3b-2 + 贴图按 skin 重组）：开屏观感应与之前一致（门禁钉住了 uniform 值），
  但 **colouration 换成了上游真值** ⇒ 草/灌木现在会被染色、树的色调也变了 ⇒ 与旧黄金帧的差异**预期变大**。
  ⇒ 真机第一件事：**看开屏是不是还认得出是同一张图**。
* **Planet 片元采样器**：声明 14 个、实际采样 **12 个**（`roadBaseMap` / `fadeFiner` 声明未用）——
  GLES2 只保证 8 ⇒ 上机读 `GL_MAX_TEXTURE_IMAGE_UNITS`（`logGlStrings` 已打 `fragTexUnits` / `vertTexUnits`）；
* **Planet 顶点采样器**：只剩 1 个（`fadeFiner`）⇒ 同时确认 `GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS`；
* ⚠️ **自适应回退会改 Surface 尺寸** ⇒ 触发一次 `onSurfaceCreated` ⇒ 走 `rebuildGlResources()`（几百 ms 卡顿）。
  这是**预期行为**（方案 §4.5），但真机上第一次降档要能接受那一顿。
* ⚠️ **Planet 恒无云**（云图没移植）—— 已知取舍，非缺陷。
* **生成物禁止手改**：`HighwayGlsl.kt` 由 `scripts/e52/e52_gen_glsl_kotlin.mjs` 生成；
  历史上曾有"只存在于生成物里"的手改被重跑静默冲掉（本轮已修，并新增生成后**裸名自检**）。

---

## 其他

- **版本号**：versionCode = 195 / versionName = "2.39.10"（`app/build.gradle.kts` 已合并为单处赋值）
- **APK 路径**：`app\build\outputs\apk\release\NASMusicTV-release-v2-39-10.apk`
  · 第三轮 40,462,662 B，md5 `45c58b18486aaae6437aca589144bd24`（22:34，已装电视 ⇒ 踩出脏内存碎片）
  · 第四轮（槽位零初始化）40,462,759 B，md5 `e94387bd005d133a5d8a9fc37aea77ee`（23:34，已 `install -r` 到电视）
  · 第五轮（三项修复）40,466,091 B，md5 `ffa2ad0406c8150af18656d595ba73d1`（11-01 00:54，已 `install -r` 到电视）
  · 第八轮（白线 sink / 切槽半径上限 / 远景不清槽 / 帧率 EMA / 树落地插值 / 树冠缩小）40,472,802 B，
    md5 `ebe07ff555e64887706b6fb276f702b6`（11-01 14:33）
  · 第十轮（树冠真实半径 1.118 + `getXZ` 8 路 memo）40,473,151 B，md5 `5e039b3c5b704bfd86b75c5132ca002f`（11-01 15:24）
  · ⭐ **第十一轮（Planet 接线 + 环境常量 + 贴图重组 + 自适应回退 + P8 旋钮②③ + P5 离线部分）
    40,513,665 B，md5 `ee14c5afe564c9a98a752eda10d70841`（11-02 02:30，`assembleRelease` BUILD SUCCESSFUL）**
  · ⭐ **第十六轮（覆盖层同步 + 触摸穿透 + 换世界 + 标签每帧回写）** —— **当前 release 包（已装手机 17:06:53）**
    `app\build\outputs\apk\release\NASMusicTV-release-v2-39-10.apk`
    **40,527,981 B，md5 `845529b88a25f7a6e24792c0bd9ac1e7`**（11-02 17:06，`assembleRelease` BUILD SUCCESSFUL；
    `testDebugUnitTest` 2845 例 / 294 类零失败；`lintDebug` 27 error 全在 E51 与 `src/debug/AndroidManifest.xml` ⇒ E52 零 error）
    · 提交 `e123529`（本地，未 push）
  · ⭐ **第十四轮（水面越界修复 + 绘制顺序加固）** —— **最终 release 包（已装手机）**
    `app\build\outputs\apk\release\NASMusicTV-release-v2-39-10.apk`
    **40,526,564 B，md5 `fbd3e42f64f5cdc38b36f4f5552cc0f7`**（11-02，`assembleRelease` BUILD SUCCESSFUL）
    · ⛔ 与手机上那份（12:37 装的 12:15 版）**同签名** ⇒ `adb install -r` 直接覆盖成功（`Success`）
    · `versionCode 195 / versionName 2.39.10`；三 ABI（arm64-v8a / armeabi-v7a / x86_64）；
      `apksigner verify` 通过（v1 + v2 签名方案，单一签名者 = `release-key.jks`）
    · 同批 debug APK：`app\build\outputs\apk\debug\NASMusicTV-debug-v2-39-10.apk`
      65,711,771 B，md5 `f7abad24929beb3091d9a5425f05b545`（11-02 12:05）
    · ⚠️ **APK 不是逐字节可复现的**：同一份源码重新 `assembleRelease` 会因 **zip 时间戳**
      产生**几字节**的差异（03:31 那次 40,521,545 B / md5 `eb7c6e84…`；08:12 那次
      40,521,542 B / md5 `7c5e4efa…`；12:15 这次 40,525,680 B / md5 `d99b4616…`，内容相同）。
      ⇒ **校验用 `versionCode` + 证书 + 大小，不要拿 md5 当不变量**。
    · ⛔ **未装机**：电视 `192.168.0.114` **全程失联**（ping 不通、ARP 表里没有、adb 连不上）⇒ 待电视开机后补装
    · ⛔ **手机需复验**：第十三轮修的是手机上报的 ①②⑤（白线 / 树 / 景色变化）⇒ 见「手机复验清单」
- ⚠️ **`packageRelease` 需要更大的堆**：默认 `-Xmx4096m` 时 `packageRelease` 会在
  `PackageAndroidArtifact$IncrementalSplitterRunnable` 里挂掉（无异常信息，典型 OOM）⇒
  本轮用 `-Dorg.gradle.jvmargs="-Xmx8g -Dfile.encoding=UTF-8"` 一次过。
  ⛔ 不要为此改 `gradle.properties`（那是仓库级配置），只在命令行临时加大。
- ⚠️ **`lintDebug` 的 27 个 error 是既有的**：全部落在 **E51 源码**（`visualizer/e51/**` 的仓库副本 + 覆盖层副本）
  与 `src/debug/AndroidManifest.xml`（`MissingLeanbackLauncher`）；**E52（`visualizer/highway/`）零 error**。
  ⇒ CI 的 blocking lint 在本分支上仍会红，但**不是本轮引入的**。
- **验证口径**（2026-11-02 12:15）：`com.nasmusic.tv.visualizer.highway.*` 单测
  **34 类 201 例零失败**（`--rerun-tasks` 强制重跑；本轮新增 2 类 12 例：
  `HighwayEnvLabelGateTest` 6 + `HighwayEnvManualSwitchGateTest` 5，
  另 `HighwayQualityTierGateTest` 改写 3 例）；
  **全仓**单测 `testDebugUnitTest --rerun-tasks` **292 类 2836 例零失败**；
  `assembleDebug` / `assembleRelease` 均 BUILD SUCCESSFUL。
- **测试设备**：创维电视 9R54_G8S（Android 5.1.1，PowerVR Rogue G6110，GLES 3.1），`192.168.0.114:5555`
  ⛔ **本轮失联**（电视关机 / 未连网）⇒ 所有真机项（G18 / G27–G30 / Planet 首跑）**本轮无法执行**。
- **分支**：`feat/e51-private`（本地专用，永不 push / tag / release）
- **最近提交**：本轮（第十一轮）已本地提交，**未 push**。

## 下一步

> ⛔ 本节是**早期排期**（第五 ~ 十轮留下的现场处置步骤，真机复验与卡顿 AB 的操作细节仍然有效）。
> **现行进度与建议顺序以「当前状态快照（2026-11-01 22:32）」为准**，两处口径冲突时以快照为准。

### 0. ⏳ 真机复验状态（R9 = 最新，详见上方「第九轮」章节）

| 项 | 状态 | 结论 |
|---|---|---|
| ① 白线 | ✅ 已修 | 左右白线连续到画面底部（顶点着色器 `roadSink`） |
| ② 树在路上 | 🔬 **根因已定位并修复（离线）**，待真机确认观感 | 第十轮：半径 0.5→1.118，探针「14/14 越界 → 0/11」；代价是路边树变小，需上机看 |
| ③ 帧率徽章 59 | ✅ **代码已修**（`618d090`），待重出包 | 徽章修复晚于 R9 的包（14:57 vs 14:33）；`HighwayWorldGateTest` 已证 EMA 生效 |
| ④ 远处多边形缝 | ❌ 未过（部分缓解） | 第十轮补假设：疑近景方窗(res20/lod1)↔走廊(res10/lod0) 的 T-junction；**先量交界类型再改** |
| ⑤ 卡顿根因 | 🔬 **`getXZ` 已修（memo）**，草 overdraw 待 AB | 第十轮：8 路 memo 绕过双层 map（值不变，`HighwayWorldGateTest` 5/5）；`grass=11047` 需真机 `nasmusic_hw_mask` AB |

（下方 1~3 为历史复验清单与根因 B 的 AB 排除法，仍有效，主攻项见「第九轮」）

日志（TAG = `Highway`）：

```
"C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 logcat -c
…在电视上让 E52 跑 15 秒…
"C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 logcat -d -s Highway > e52_r6.txt
```

### 1. ⏳ 第五轮真机复验（三项，第六轮一并看）

| 看什么 | 判据 |
|---|---|
| ① 树/草朝向 | 树冠在上、根在下（对着路边一排树看，别只看远景小点） |
| ② 多一层土坡 | **路堑段**（原始地形明显高于路面）两侧不再有「土坡盖路 + 下面一层带植物的地形」；`stats` 里 `farIndexCount` 应比 `9 × 70² × 6 = 264,600` **明显小**（洞挖掉了走廊条带） |
| ③ 跨格冻结 | `terrain rebuilt … newlyBuilt near=` 必须是 **0**（预取生效）；`hitch frameMs` 从 324 ms 降到 ~30 ms 量级 |

### 2. ⚠️ 根因 B（60 帧仍卡）—— 电视侧实测，**不要凭猜改代码**

现有 `stats` 三件套已经能定性，不需要加探针：

```bash
# 抓一轮（等 stats 输出 3~5 次，约 10 秒）
adb -s 192.168.0.114:5555 logcat -c
# …在电视上让 E52 跑 15 秒…
adb -s 192.168.0.114:5555 logcat -d -s Highway | grep -E "stats|terrain rebuilt|hitch"
```

判读口径：

- **`fps ≈ 60` 且 `frameMsP95 ≈ 17`** ⇒ 帧节奏其实是稳的，那「卡」来自**呈现侧**
  （重复帧 / 合成器放大 / 面板响应），下一步查 `adb shell dumpsys gfxinfo <pkg>`；
- **`fps ≈ 60` 但 `frameMsP95` 明显大（> 50 ms）** ⇒ **长尾卡顿**，平均被长尾拉高前先看分布，
  再按下面的 AB 逐项排除；
- **`fps` 本身就低（< 45）** ⇒ 真·算力不够，直接进 AB。

AB 逐项排除（每次只改一个变量，改完出包对比 `frameMsAvg`）：

1. **`renderScale`**：`HighwayRenderer.RENDER_SCALE_BY_QUALITY` 的 HIGH `0.40 → 0.30`。
   `frameMsAvg` **近似线性下降** ⇒ 填充率瓶颈（像素侧）；**几乎不变** ⇒ 不是填充率，
   转查 draw call / `discard` / 呈现。
2. **关植被**：`drawVegetation` 直接 `return`。
   `frameMsAvg` 大幅下降 ⇒ 命中「植被 `discard` 关掉 TBDR 早期深度剔除」这条候选
   （PowerVR 上 `alphaTest` 的 `discard` 会强制延迟深度测试，是最可能的一项）。
3. **关地形**：`drawTerrain` 直接 `return`（只留天空 + 路面）。
   用来把「地形 3 次 draw」和「植被 instanced draw」的账分开。

⛔ **不要用调 `frameMsP95` 窗口、加 `glFinish`、堆 `testTimeout` 这类办法「绕过去」** ——
那只是把慢帧合法化，不解决观感。

### 3. 其余

4. ⏳ **验证后清理诊断日志**：`diag …` / `stats` 那批 logcat 探针按约定留到本轮真机验收之后再删，然后才本地提交
5. ⚠️ **触摸拦截**：处理 AndroidView 全屏覆盖拦截触摸问题（降低覆盖或加退出按钮）
6. ⏳ **P5/P6**：水面 + 模型 + 环境集切换（未开始）
7. ✅ **树数量为 0**（已解决）：`vegTree > 0`，真机已能看到树
8. ✅ **电视档降载**（第五轮已做）：预取 + 植被矩阵缓存 + 绝对环形槽位

---

## 2026-11-04 第二十一轮：**牌面光照修复** + **道路高度平滑阶段补齐** + **渲染剩余 5 项** + **G18 判据实测**

### 一、真机复测（2026-11-04，用户手机）

| # | 用户反馈 | 判定 | 处置 |
|---|---|---|---|
| ① | 视角固定不动了，已恢复 | ✅ | 上轮回退 `vehiclePitch` 的修复生效 |
| ② | 路牌有图案，不过**并未根据天气做亮度改变**，**远处看是黑的，近处看有图案但比较暗** | ⛔ 未达标 | **根因 = 牌面法线没按 `gl_FrontFacing` 翻** ⇒ 远处看到的是牌的**背面** ⇒ 法线背对光源 ⇒ 直接光为负 ⇒ 只剩环境光（对天气不敏感）⇒ 「远处全黑 / 近处偏暗 / 不随天气变亮」三条症状**同一个根因**。已在生成器 `MODEL_SIGN_FS` 加 `normal * ( gl_FrontFacing ? 1.0 : -1.0 )`（three.js r144 `normal_fragment_begin` 的 `faceDirection`）并重跑生成 |

### 二、⭐ 世界保真度：真因**不是**高度图偏移，而是**道路高度平滑阶段缺失**

- 上游 `oo.loader()` 是**三阶段**：① `extendMidline` → ② **`smoothHeight`** → ③ `discretiseNext`；
  运行期 `main:13686-13689` = `Ke.tail.i < Ke.vehicleIndex + $ ? (this.extendMidline(), this.smoothHeight()) : …`
  ⇒ **每新建一个节点就紧跟一次平滑**（窗口 7 节点，把窗口头节点的 `p.y` 拉向窗口均值）。
- ⛔ **我们只做了 ①③** ⇒ 新建节点的路面高度停留在**未平滑的原始地形高度**上。
- 修法：`HighwayRoadChain.maintain()` 每 `extendMidline()` 后跟一次 `smoothHeight()`；
  `smoothHeight()` 在**窗口不完整**时（`prev`/`next` 不足）**跳过而不崩**（上游会抛 TypeError，移植件不能崩）。
- 逐点实测：节点高度与上游的差从 **+0.504 / +0.378 / −0.920 / −1.134 m**（散布 1.64 m）收敛到
  **−0.149 / −0.152 / −0.160 / −0.135 m**（= 上游车辆「后轴 + 悬挂 travel/2」的常量偏置，残差带仅 0.025 m）。
- 指标：SSIM **0.6729 → 0.6896**、ΔE2000 **2.816 → 2.629**（≤3 达标）。
- 门禁：`HighwayRoadChainSmoothingGateTest`（2 例，钉住「新建节点必须成对平滑」+「窗口不完整时不崩」）。

### 三、⭐ G18 判据口径：⭐ **实测复测，结论 = 维持原样，不下调阈值**

用**当前帧锁定方法**（3 个独立浏览器进程 × 12 组对比，脚本 `logs_temp/e52_g18_threshold.py`）：

| 口径 | SSIM | ΔE2000（同内容） |
|---|---|---|
| **上游自己**跨会话 | min **0.8366** / mean **0.9162** / max 0.9987 | 0.002 / 0.175 / 0.380 |
| **我们**跨会话（原型） | **1.0000**（**逐像素一致**，零噪声） | **0.000** |
| 我们 vs 上游 | **0.6896** | **2.629** |

⇒ ① 阈值 0.92 **贴着上游自身的复现地板**（mean 0.9162）⇒ 跨会话比较时上游对自己都可能红 ⇒
判据的**硬断言仍是「不回退」护栏**（SSIM ≥ 基线 − 0.005、ΔE ≤ 基线 + 0.1），
**软目标（0.92 / ΔE ≤ 3）只作方向指示、不阻塞**；
⇒ ② 我们的原型**零噪声** ⇒ SSIM 的差距**全部归因于保真度**，不是测量噪声；
⇒ ③ **我们低于上游自身的地板 0.8366** ⇒ 差距是真的 ⇒ **下调阈值只会掩盖真差距 ⇒ 不下调**。

### 四、n140「整帧看不到天空」的定位（**未解决，已定性**）

| 对照实验 | skyRatio | 结论 |
|---|---|---|
| 上游黄金帧 | **0.1487** | 基准 |
| 我们（当前基线） | 0.0000 | 缺 73 px 天空带 |
| 我们 + **上游同帧 viewMatrix**（`--camurl` 金标准机位） | 0.0000 | ⛔ **不是相机** |
| 我们 + `terrhm=1`（顶点高度直接取**上游原始侵蚀高度图**） | 0.0000 | ⛔ **不是高度源** |

⇒ 剩下唯一变量 = **原型的远景地形几何覆盖**（上游是 **700 m 方形瓦片** `class nt` + 4 m 的
overlap 下沉；原型是**随距离张开的扇形** `farEdges()`）⇒ 属**原型专属**的远景保真度缺口，
**Kotlin 侧远景格用的是原始 `getXZ`**（与上游远景瓦片同口径）⇒ 不影响真机画面。
⛔ **overlap 下沉（3~4 m）已被排除**：在 1400 m 距离上只折合约 2 px，解释不了 73 px。

### 五、渲染剩余 5 项（执行车道）

| # | 项 | 结论 |
|---|---|---|
| ① | **护栏贴图**（`wall_barrier_02.png` + 法线图，`alphaTest 0.75`） | 上游有贴图、我们只画纯色梯形带 ⇒ 接入（复用 `modelSignProgram`，**不新写 program**：创维 PVRShaderCompiler 编译一个 PBR 变体 ~5 s）。⭐ UV 与上游 `getUV()` **逐位同口径**（u = 采样点序号，v = 0/0.88/1）⇒ 可直接喂。⛔ **法线贴图不接**（three.js `normalMap` 要片元导数求切线帧 ⇒ ESSL 1.00 做不到） |
| ② | **墙的逐面（flat）法线** | 上游 `flatShading`（片元导数）⇒ ESSL 1.00 做不到 ⇒ 走**三角形汤**（每面 3 顶点 + 面法线，`cross(B−A, C−A)` 与上游 `cross(dFdx,dFdy)` 同向）。实测倍率 **×4.50**（不是 ×3）、**三角形数不变** ⇒ 填充率零变化、只有顶点调用 ×4.5；加了 `toFlatShadedChunks` 自动按 ushort 上限切块 |
| ③ | **桥影按距离排序** | 上游 `ja` 是半透明（`opacity 0.33`）⇒ 落 three.js 透明趟 ⇒ `painterSortStable`（远的先画）。已按视深降序 + 稳定排序落地 |
| ④ | **桥面护栏浅蓝 `0xBBCCFF`** | ⭐ **与「等色彩管线落地」的初判相反**：上游 r144 `ColorManagement.legacyMode=true` + `outputEncoding=LinearEncoding`（chunk 全文 grep 三个关键字 **0 命中**）⇒ **纯线性直通** ⇒ 我们也是 ⇒ `0xBBCCFF` 的三个分量**原样送进 GL 才是逐位还原**；再做一次 linear→sRGB 反而抬亮红/绿 ⇒ 更不像上游 |
| ⑤ | **干砌石 `Gs` / 栅栏 `la`** | ⛔ **判定不做**：`la` 只在 `detailLodIndex > 1` 用（上游默认 1 ⇒ **默认永不出现**）；`Gs` 需要节点的 `dsel/dswl/dser/dswr`（上游地形生成器写的，我们没有）**且**其抖动来自**模块级全局 PRNG 流** ⇒ 静态渲染无法逐位复现。⭐ 两者在 G18 的 4 个黄金机位上**上游根本没画** ⇒ 做了是**凭空多出几何 = 减分** |

### 六、⚠️ 附带发现并修复：**桥面护栏 / 桥影画在路面下方 90 m**

`buildBridgeWall` / `buildBridgeShadow` 的网格原点写成 `originY = 0.0`，而顶点是相对桥首节点的
⇒ 世界 Y ≈ **−90 m** ⇒ **桥面护栏与桥影根本看不见**。
证据：权威 harness（跑真的 `za`）记录到 `CUSTOM wall 100.0 10.0 0.0` ⇒ 上游 mesh 世界位置 = **桥首节点位置**；
而旧的 wallmesh harness 把 y **硬编码成 0** ⇒ 旧金标准里的 `origin=12.0 0.0 -1.0` 是 **harness 产物、不是上游真相**。
⇒ 已改 + 同步改 harness + 重生成基准 + 更新门禁期望值。

### 七、⭐ 设备端逐项验收清单（**照着测，每项给判据 + 截图文件名**）

> ⛔ 电视（创维 192.168.0.114）**全程不操作** ⇒ 以下全部在**手机**上做。
> ⛔ 助手**不发 keyevent** ⇒ 每次切景色/切档位请**手动**操作。

**0. 先清掉遗留的诊断开关**（`Settings.Global` 是系统级，重装 app 不会清 ⇒ 换景色像坏了就是它）

```bash
adb shell settings delete global nasmusic_env
adb shell settings delete global nasmusic_tier
adb shell settings delete global nasmusic_res
adb shell settings delete global nasmusic_seed
adb shell settings delete global nasmusic_hw_mask
adb shell settings delete global nasmusic_e52_feat
```

| # | 验收项 | 怎么测 | 判据 | 截图文件名 |
|---|---|---|---|---|
| **R1** | **牌面随天气变亮** | 同一段路，切**晴天 / 日出 / 日落 / 夜间**四种天气看同一块路牌 | 四种天气下牌面亮度**明显不同**（夜间最暗）；**不再出现「远处全黑」** | `e52_r1_sign_<weather>.png` |
| **R2** | **牌面近处不偏暗** | 近距离看一块牌面 | 牌面亮度与同场景的护栏/地面**同量级**（不再「灰蒙蒙偏暗」） | `e52_r2_sign_near.png` |
| **R3** | **护栏有贴图** | 沿路看两侧金属护栏 | 护栏表面出现**沿路重复的金属带纹理**（不是纯白/纯色块）；近处清晰、远处按 mipmap 变糊 | `e52_r3_barrier.png` |
| **R4** | **护栏背面不黑** | 弯道处从**外侧**看护栏背面 | 背面**不是纯黑**，亮度接近正面 | `e52_r4_barrier_back.png` |
| **R5** | **混凝土墙棱线变硬** | 找一段海墙/桥墙，看明暗交界的**竖直棱** | 棱线是**硬边**（面与面之间不再渐变） | `e52_r5_wall_edge.png` |
| **R6** | **桥面护栏可见且是浅蓝** | 上桥，看桥两侧护栏 | ⭐ 护栏**出现在桥上**（修前在路面下 90 m ⇒ 看不见）；颜色是**很淡的蓝白**（`0xBBCCFF`）而非纯白 | `e52_r6_bridge_rail.png` |
| **R7** | **桥影可见且叠压正确** | 上桥，看桥下的水影 | 水影**在桥下**（修前在路面下 90 m）；同时看得见**前后两座桥**时，**近**的影叠在**远**的上面 | `e52_r7_bridge_shadow.png` |
| **R8** | **墙不整段消失** | 在长段海墙处慢速行驶 | 墙**连续可见**，不出现「整段墙不见了」 | `e52_r8_wall_continuous.png` |
| **A4-1** | **三档切换无残留/无闪黑** | `nasmusic_tier` 轮换 high/medium/low，每档停 10 s | 切换瞬间**不闪黑**；上一档的墙/植被**不残留** | `e52_a4_tier_<tier>.png` |
| **A4-2** | **30 min soak** | 任一档连跑 30 min | 不 OOM、不崩；`logcat` 里 `Highway` 无 `E` 级别日志；帧率不持续下滑 | — |
| **A6** | **Planet 场景首跑** | `adb shell settings put global nasmusic_env 4,0,0` ⇒ 重启 app 进 E52 | 能进 Planet、**不白屏/不黑屏**；地形与护栏正常 | `e52_a6_planet.png` |
| **A7** | **LOW 档参考帧** | `adb shell settings put global nasmusic_tier low` ⇒ 冻结机位截一张 | 存档为 LOW 档基准帧；`renderScale 0.30` 下**不糊成马赛克** | `e52_a7_low.png` |
| **A8** | **远处露底色缝** | 看远处地形交界 | 看不到「露底色」的缝；若仍有 ⇒ 记下**缝到相机的距离**与**是 terrain↔terrain 还是 terrain↔far** | `e52_a8_seam.png` |

⭐ **测完把 `nasmusic_*` 开关清掉**（否则下次换景色会像坏了）。



---

## 2026-11-04 第二十二轮：**牌面 / 护栏全黑**的两个根因已定位并修（**待真机复测**）

### 一、真机复测结果（用户）+ 助手自取的证据

- 用户：景色可切 ✅；**护栏又变回黑条带**；**路牌是黑块**。
- 助手（**不操作设备**，只读日志 / 截图）：
  - `adb logcat -d -s Highway` ⇒ ⭐ **release 下 `tex …` 诊断行是可见的**
    （`android.util.Log.i` 没被 R8 剥；只有 `AppLog.d/i/w` 有 `BuildConfig.DEBUG` 守卫）
    ⇒ 上一轮「贴图日志不可见」的判断**是错的**（当时 grep 用了 `-Last N`，被日志缓冲区尾部截断了）。
  - 截图 `output/e52_now.png`（2712×1220）逐像素分析：
    - 「黑块」是**牌面 quad 的整块填充**（**不是**牌面图案的三角形剪影）⇒ 采样值 = **(0,0,0,1)**；
    - 右侧那条是 **UI 信息条**的圆角底板（不是 3D 物体）；左侧那条沿路走向 ⇒ 是**护栏**。
  - 贴图本体正常：`wall_barrier_02.png` 512×256、alpha≥191 占 44.2%、平均 RGB (162,166,170)；
    `sign_face_06.png` 128×128、平均 RGB (174,106,107) ⇒ **贴图不是黑的**。
  - 日志里的 `avg=563434` = **预乘 alpha 后**的均值（按非预乘算是 206 ⇒ 差 2.4× ⇒ 设备侧
    `BitmapFactory` 默认预乘）⇒ **解码链正常**。

### 二、根因 1（**上游口径**，上一轮误读）：上游把 `normal_fragment_begin` **整段替换** ⇒ **不翻法线**

- 上游 chunk（`logs_temp/slowroads/main.e7a33c55.chunk.js` **line 832 / 字符偏移 36520**，工厂 `B`）：
  `float faceDirection = -1.0; vec3 normal = normalize(vNormal); vec3 geometryNormal = normal;`
  ⇒ **没有** `normal = normal * faceDirection;` ⇒ **法线不按朝向翻**。
- ⛔ 上一轮把那句 `faceDirection = -1.0` 当成「会按朝向乘上去」⇒ 给 `MODEL_SIGN_FS` 加了**无条件**的
  `normalize(vNormal) * (gl_FrontFacing ? 1 : -1)`。
- ⇒ 后果：我们**左护栏的绕序与法线属性相反**（dot = **−0.990**）⇒ 相机看到背面 ⇒ 法线被翻到
  **背离路面**的一侧 ⇒ 更暗；牌面法线水平 + 平行光从正上方 ⇒ 法线一翻，半球光权重也变 ⇒ 更暗。
- ⭐ **JS 原型早就写对了**（`docs/archive/e52-highway-webgl-preview.html:3368` 用的是上游原文）⇒ **原型对、Kotlin 错**。
- 修法：`MODEL_SIGN_FS` 加 **`uFaceFlip`** 模式开关（⛔ **不新增 program** ⇒ 创维启动 +0 s）：
  `0` = 不翻（上游 `Z`/`W` 工厂：牌面 / Hills 护栏 / 栅栏，**被 patch**）；
  `1` = 按 `gl_FrontFacing` 翻（上游**不被 patch** 的 `flatShading` 材质：`Ph` Planet 护栏 / `Ca` 桥面护栏）。

### 三、根因 2（**我们的 bug**）：**贴图句柄悬空** ⇒ 采样到「没有 level 的空纹理」⇒ 纯黑方块

- `HighwayTextures.release()` 会 `glDeleteTextures` 掉自己全部句柄；场景层另有一份
  `signFaceTextures` / `wallTextures` 缓存**同一个 id** ⇒ 换实例的**三处**
  （`onSurfaceCreated` / `rebuildGlResources` / `setQualityTier`）**都没清** ⇒ id 变**悬空**。
- ⛔ 悬空 id 再 `glBindTexture` ⇒ GL **新建一个没有任何 level 的空纹理对象**（默认
  `MIN_FILTER = NEAREST_MIPMAP_LINEAR`、却没有 mip 链）⇒ **纹理不完整** ⇒ `texture2D` 恒返回
  **(0,0,0,1)** ⇒ alpha = 1 ⇒ **不触发** `alphaTest 0.75` 的 discard ⇒ **整个牌面 / 护栏被涂成纯黑方块**。
- ⭐ **完全对上症状**：牌面 = **方块**（不是三角形剪影）；护栏 = 纯黑带；
  **牌子杆 = 正常灰**（无贴图）；地形 / 路面 / 植被正常（每次都走 `textures.load(...)`，换实例后会重新上传）。
- ⭐ 触发条件**必然发生**：进效果后 `applySurfaceSize()` → `holder.setFixedSize()` → Surface 重建
  → `onSurfaceChanged(新尺寸)` → `rebuildGlResources()`。
- 修法：新增 `invalidateTextureCaches()`（三处换实例都调）+ `signFaceTexture()` / `wallTexture()` 加
  **`glIsTexture` 守卫**；牌面另从 `loadOr`（缺资源静默回落 1×1 **纯黑**）改成 `load` + catch
  ⇒ 缺资源就**不画牌面 + 打告警**（而不是黑方块）。

### 四、顺带查清的三件「**不是我们 bug**」的事

- **护栏的「扁带」形状**：几何**是竖直 0.78 m 薄片**（底 `pj.y` / 中 +0.6864 / 顶 +0.78，
  底边再下沉 `originY = −0.15`）⇒ 扁带来自**贴图的 alpha**：不透明区只在 **v ∈ [0.53, 0.94]**
  （= 离地 0.41~0.73 m 的一条**横梁**），梁下的立柱只有 5% 宽、远处被 mipmap 平均掉 ⇒
  `alphaTest 0.75` 丢掉 ⇒ **上游同款**。
- **平行光从正上方来**（上游 `Directional(white, 0.6)` 的 `position=(0,10,0)`）⇒ 所有竖直面都没有
  直接光 ⇒ 比地面暗 4~5 倍 ⇒ **上游同款**。
- **牌面法线不是全零**（OBJ `vn 0 0 1`，`modelMatrix` 只绕 Y 旋转无缩放 ⇒ 无 NaN）⇒ 「法线全零 ⇒ 全黑」排除。

### 五、本轮改动清单

- 改：`scripts/e52/e52_gen_glsl_kotlin.mjs`（`MODEL_SIGN_FS` 加 `uFaceFlip`）＋ **生成物** `glsl/HighwayGlsl.kt`
- 改：`HighwayGlScene.kt`（`uFaceFlip` 接线 / `invalidateTextureCaches()` / `glIsTexture` 守卫 / 牌面改用 `load`）
- 改：`HighwayTextures.kt`、`render/HighwayWallMaterial.kt`（`flipsNormalByFacing`）、`render/HighwayWallPlan.kt`
- 新增门禁：`render/HighwayFaceDirectionGateTest.kt`（6 例）
- 文档：`docs/technical-overview.md` §10.335

### 六、⭐ 设备端复测清单（**本轮只测这 4 项**）

> ⛔ 电视（创维 192.168.0.114）**全程不操作** ⇒ 全部在**手机**上做。
> ⛔ 助手**不发 keyevent** ⇒ 切天气请**手动**操作。

**0. 先清掉遗留的诊断开关**（`Settings.Global` 是系统级，重装 app 不会清 ⇒ 换景色像坏了就是它）

```bash
adb shell settings delete global nasmusic_env
adb shell settings delete global nasmusic_tier
adb shell settings delete global nasmusic_res
adb shell settings delete global nasmusic_seed
adb shell settings delete global nasmusic_hw_mask
adb shell settings delete global nasmusic_e52_feat
```

（`nasmusic_fps=1` **保留**：只显帧率徽章，soak 测试要用。）

| # | 验收项 | 怎么测 | 判据 | 截图文件名 |
|---|---|---|---|---|
| **R1′** | **牌面不再是黑方块** | 进入 E52，看到路牌时靠近看 | 牌面**显示出牌面图案**（红边三角形 / 黄底箭头，按变体）；**不是纯黑方块** | `e52_r1_sign.png` |
| **R2′** | **护栏不再是黑带** | 沿路看两侧金属护栏 | 护栏表面出现**沿路重复的金属带纹理**（灰色横梁 + 细立柱）；**不是纯黑** | `e52_r2_barrier.png` |
| **R3′** | **护栏背面不是纯黑** | 弯道外侧看护栏背面 | 背面亮度接近正面（不再是纯黑） | `e52_r3_barrier_back.png` |
| **R4′** | **牌面随天气变亮** | 同一块牌面切 晴天 / 日落 / 夜间 | 三种天气下亮度**明显不同**（夜间最暗） | `e52_r4_sign_<weather>.png` |

⭐ **测完把 `nasmusic_*` 开关清掉**（否则下次换景色会像坏了）。

⭐ 上一轮的完整清单（R1~R8 / A4 / A6 / A7 / A8）**仍然有效**，只是 R1/R2/R3 与本轮的 R1′/R2′/R3′ 重合。


### 七、⭐ owner 裁决：**跨水面的「桥」保持上游行为，不建桥**（2026-11-04）

用户反馈「路面在跨水面时，没有桥，直接路铺设在水面上了」。全链路核对结论：**这不是缺口，是上游行为**。

- 上游**没有独立的桥面网格** —— 上游的「桥面」**就是路面带本身**（`bridging` 一开，节点 `p.y = bridge.height`）。
  我们**这一整套已全部移植**：`byWater` → `testForBridge` → `checkBridgeQuality` → `shouldBridge` → `bridging`
  → 节点抬升 + `node.bridge` → `za` 摆放 → 护栏 `Ca` / 桥影 `ja` / 桥墩 → 桥段地形切槽。
- ⭐ **上游在「路横穿一大片开阔水面」时本来就不建桥**：入口要求路正在朝地形更低的一侧拐
  （`gradLat` 符号门槛 ⇒ 开阔水面左右都是水 ⇒ `gradLat ≈ 0` ⇒ 永不触发），
  且 250 m 前瞻要在对岸找到陆地、桥下中点地形还要 ≤ −2（水深 > 2 m）。
- 我们跨水段路面高度 = `max(地形, AO = 2.0)` ⇒ 2 m 堤道 ⇒ 几百米外亚像素 ⇒ 视觉上「路铺在海面上」
  —— **与上游逐位一致**。

⇒ ⭐ **owner 裁决：保持上游行为，不建桥。** ⇒ ⛔ 不改建桥触发条件、不改高度链、不改 parity baseline。
⇒ 📌 下轮再看到「跨水面没有桥」**不是 bug**，按本节结案。


### 八、构建与验证记录（2026-11-04）

| 项 | 结果 |
|---|---|
| 构建 | `assembleRelease lintDebug testDebugUnitTest "-Plofi.city=true" "-Pkotlin.compiler.execution.strategy=in-process" "-Dorg.gradle.jvmargs=-Xmx8g" "-Dfile.encoding=UTF-8" --no-daemon` ⇒ **BUILD SUCCESSFUL** |
| 单测 | **3045 例 / 0 失败 / 0 错误**（317 个测试类） |
| lint | **error 0**，warning 187 |
| GLSL 逐字门禁 | `node scripts/e52/e52_glsl_check.mjs` ⇒ **13 文件零差异** |
| APK（黑修复，已装机验证） | 40,667,016 B @ 08:41 · SHA256 `3BB5A903D3E8EA591DB37BD919D102ECA794533D2DFB158699EF1845E228D179` |
| APK（黑修复 + 云带修复） | **40,667,472 B @ 10:02** · SHA256 `6C1A1794431C0F24E1BEDFBDC4E72F9DBC99EEFEEFD29A62D79AB9916834FCE1` |
| 设备侧核对 | 装机后设备 `base.apk` 的 SHA256 与本地 APK **逐字节一致** |
| 本地提交 | `6887f64`（分支 `feat/e51-private`，⛔ **未 push**） |

#### 设备侧日志证据（黑修复）

```
08:43:10.228  surface resized -> rebuild GL resources        ← Surface 重建确实发生
08:43:11.163  tex signs/sign_face_01.png -> id=13 … avg=654243
08:43:11.212  tex wall_barrier_02.png   -> id=21 … aniso=8 avg=464849
```

⇒ **重建之后牌面与护栏贴图全部重新上传**（旧代码这里会直接命中缓存、返回已被
`glDeleteTextures` 删掉的 id ⇒ 绑定出「没有 level 的空纹理对象」⇒ 采样恒 (0,0,0,1)
⇒ 整个 quad 纯黑）。`errUpload=0 / errMip=0` ⇒ 纹理完整。

#### ⭐ 一条方法论（本轮第三次撞上）

**「Kotlin 端与上游不一致」时，先去看 JS 原型 —— 原型往往是对的。**
原型是照抄上游 three.js 的行为（材质参数 / shader chunk 原文），Kotlin 端是手写移植 ⇒ 容易读错。
本轮三例：① 牌面 `faceDirection = -1.0`（原型抄的是上游原文，Kotlin 端误读成「会参与运算」）；
② 天空 `fog: !0`（原型抄了材质参数，Kotlin 端自己写了「雾不作用到天空色」）；
③ 清屏色取天空远色「填缝」（原型没这问题，因为它有雾）。


### 九、设备端验收结果（2026-11-04）

| # | 验收项 | 结果 |
|---|---|---|
| **R1′** | 牌面不再是黑方块 | ⏳ **待确认**（owner 已确认云带与护栏；牌面需再看一眼路牌） |
| **R2′** | 护栏不再是黑带 | ✅ **通过** —— 新包护栏正常显示（灰金属横梁 + 竖立柱），日志证实重建后贴图全部重传 |
| **R3′** | 山矮处的地平线不再有浅青板 | ✅ **通过** —— owner 确认「云带问题解决了」（云带溶进地平线白霾） |
| **R4′** | 牌面随天气变亮 | ⏳ 待确认（切 晴天/日落/夜间 各看一眼） |

⭐ **诊断开关**：`nasmusic_*` 已在上一轮清掉（只保留 `nasmusic_fps=1` 用于 soak 看帧率），
但 `Settings.Global` 是**系统级**、重装 app 不会清 ⇒ 下轮若又出现「换景色像坏了」，
先核对这批开关（手机未接上时无法用 adb 核对）。


### 十、⭐ owner 裁决：**E52 暂时定稿**（2026-11-04）

> 「那这个效果暂时定稿吧，其他的开发内容不影响真机画面，只影响指标，就暂时不做吧。」

#### 1. 裁决内容

| 分类 | 处置 |
|---|---|
| **E52 效果本体** | ⭐ **暂时定稿** —— 第二十三轮（云带 + 黑渲染修复）之后**不再开新开发项** |
| **只影响 G18 指标、不影响真机画面**（§二 C 组：n140 天空 / G18 指标本身） | ⛔ **暂不做** |
| **已知取舍 / 明确不做**（§二 D 组：法线贴图、逐面法线、背面剔除、干砌石 / 栅栏、交通车、卡顿 B、触摸拦截、天空平面 Y 跟随相机、跨水面无桥） | ⛔ **暂不做**（已逐条记录在案，需要时可直接查） |
| **手机端验收**（§二 B 组：A4 / A6 / A7 / A8、R1′~R8、D1 探针收尾） | ⏳ **未做** —— 随定稿一并冻结；**可随时按 §二 B 组的清单恢复**（每一项都有判据与截图文件名） |
| **创维电视整场首跑 + `deviceTier` 标定**（A5 / C3） | ⏳ **未做** —— 见下「这不是发版阻断」 |

#### 2. ⭐ 为什么「A5 电视首跑」不再是发版阻断

⭐ **E52 的全部源码与资产一直只在私有分支 `feat/e51-private` 上**（2026-11-04 实测）：

```
main 里的 E52 highway 源码：0 个文件      E52 资产：0 个
私有分支里的 E52 highway 源码：56 个文件  E52 资产：108 个
两者差异：113 files changed, 41,967 insertions(+)
```

⇒ 与 **E51「像素城市」完全同构**：⛔ **不 push、不 tag、不发 release、不上传任何远端**；
`main` 上**不存在** E52 的任何源码/资产（这是既定事实，不是"待处理"状态）。
⇒ ⇒ 「E52 不在公开版里」 ⇒ **「在创维电视上没跑过」就不再是"发布前必须清的门禁"**，
而是「**装到那台电视之前，建议做的一次确认**」（首次上该设备仍是未知数：
PowerVR Rogue G6110 / GLES 3.1 / Android 5.1.1 与手机 Adreno 730 / GLES 3.2 不同）。

#### 3. 定稿时的状态快照

| 项 | 值 |
|---|---|
| 分支 / upstream | `feat/e51-private` / **无**（⛔ 永不推送） |
| 本地提交（最近 5 个） | `cf46d2d` 刷新未完成清单 · `fe25ea7` 设备端验收结果 · `e374653` 构建与验证记录 · `6887f64` 牌面/护栏纯黑 + 天空参与雾 + 清屏色回上游纯白 · `c7ed49d` 牌面光照 + 道路高度平滑 + 渲染剩余 5 项 + 桥面护栏 Y 偏移 |
| 单测 | **3045 例 / 0 失败 / 0 错误**（317 个测试类） |
| lint | **error 0**（warning 187） |
| GLSL 逐字门禁 | `node scripts/e52/e52_glsl_check.mjs` ⇒ **13 文件零差异** |
| APK（定稿包） | 40,667,472 B @ 2026-11-04 10:02 · SHA256 `6C1A1794431C0F24E1BEDFBDC4E72F9DBC99EEFEEFD29A62D79AB9916834FCE1` · release v2.39.10 / versionCode 195（**未升版本号**） |
| 设备侧验收 | ✅ 护栏（灰金属横梁 + 竖立柱）· ✅ 云带溶进地平线白霾（owner 确认）· ⏳ 牌面图案 / 牌面随天气变亮（R1′ / R4′）· ⛔ 创维电视从未跑过 |
| 文档 | `docs/technical-overview.md` §10.330~§10.336 · 本文件 |

#### 4. ⛔ 定稿后仍然有效的红线（复现时勿踩）

- ⛔ **着色器只能由 `scripts/e52/e52_gen_glsl_kotlin.mjs` 生成** ⇒ 禁止手改
  `app/src/main/java/com/nasmusic/tv/visualizer/highway/glsl/HighwayGlsl.kt`。
- ⛔ **E51/E52 只在本地分支 `feat/e51-private`** ⇒ ⛔ 不 push、不 tag、不发 release、不上传远端；
  ⛔ 不碰 `main`。
- ⛔ **改上游语义前先看 JS 原型**（`docs/archive/e52-highway-webgl-preview.html`）——
  原型是照抄上游 three.js 的行为，往往是对的；第二十二~二十三轮三例都栽在"读上游源码读错"上。
- ⛔ **⛔ 禁止 PowerShell 对 UTF-8 源文件做文本往返**（`Get-Content -Raw` / `Set-Content`）⇒ 用 `edit`/`write` 或 `python` + `encoding='utf-8'`。
- ⛔ 替换 `textures` 实例（`onSurfaceCreated` / `rebuildGlResources` / `setQualityTier`）必须调
  `invalidateTextureCaches()` ⇒ 否则场景层的贴图句柄悬空 ⇒ 牌面 / 护栏变纯黑方块
  （第二十二轮事故，见 §10.335 §三）。


---

## 当前状态（2026-11-07）

> ⭐ 本节是**本文的现状指针**，覆盖上面各轮的快照（「当前状态快照（2026-11-01 22:32）」与
> 「定稿时的状态快照（2026-11-04）」）；口径冲突时以本节为准。

### 本次变更（2026-11-07）

- ⭐ **本文与 `docs/archive/e51-lofi-city-plan.md` 已从私有分支搬到 `main` 公开** ——
  所有者 2026-11-07 裁决：「文档类的资料，是全局可公开的，要保证 main 分支和私有分支的内容一致」
  ⇒ 两个分支的文档内容**逐字节一致**（私有分支那份用 `git checkout main -- <路径>` 覆盖）。
- ⛔ **代码 / 资产 / 原型仍只在私有分支**：`visualizer/e51/**`、`visualizer/highway/**`、
  `app/src/main/assets/e52_highway/`、两份 E52 原型 HTML、`scripts/e52/**`、`scripts/lofi_*`。
  ⇒ 「**E52 不在公开版里**」这一点**没有因为文档搬家而改变**。

### 版本号与包

| 项 | 值 |
|---|---|
| 版本号 | `versionCode 195` / `versionName "2.39.10"`（`app/build.gradle.kts` 单处赋值） |
| release APK | `app\build\outputs\apk\release\NASMusicTV-release-v2-39-10.apk` —— 定稿包 **40,667,472 B @ 2026-11-04 10:02**，SHA256 `6C1A1794431C0F24E1BEDFBDC4E72F9DBC99EEFEEFD29A62D79AB9916834FCE1` |
| debug APK | `app\build\outputs\apk\debug\NASMusicTV-debug-v2-39-10.apk` |
| ⚠️ 可复现性 | APK **非逐字节可复现**（zip 时间戳）⇒ 校验用 `versionCode` + 证书 + **大小**，⛔ 不要拿 md5 当不变量 |
| 离线门禁 | 单测 **3045 例 / 0 失败 / 0 错误**（317 个测试类）· lint **error 0**（warning 187）· GLSL 逐字门禁 `node scripts/e52/e52_glsl_check.mjs` ⇒ **13 文件零差异** |

### 测试机

| 设备 | 规格 | 状态 |
|---|---|---|
| 手机（**唯一跑过真机**的设备） | 小米 **22081212C**（adb 序列号 **`91846823`**）/ Android 15 / Adreno 730 / GLES 3.2 | ✅ 第十三轮 ~ 第十六轮的真机取证都在它上面 |
| 电视（**目标机**） | 创维 **9R54_G8S** / Android 5.1.1 / PowerVR Rogue G6110 / GLES 3.1 / `192.168.0.114:5555` | ⛔ **一次都没在它上面跑过**（全程失联：ping 不通、ARP 无记录、adb 连不上） |

⚠️ `adb` **不在 PATH** ⇒ 用完整路径 `C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe`。

### 未完成项（定稿后仍然有效）

- ⛔ **A5 创维电视整场首跑** + **C3 `deviceTier` 标定** —— 只在小米标过（GLES 3.2 / Adreno 730 / 顶点纹理单元 16）；创维机（PowerVR / GLES 3.1）**未验证** ⇒ 12 个 PBR program 的 `PVRShaderCompiler` 开销、显存、TBDR 早期深度剔除、`alphaTest` 的 `discard` 全部未验。
- ⏳ **手机端验收**（§二 B 组：A4 / A6 / A7 / A8、R1′~R8、D1 探针收尾）—— 随 2026-11-04「暂时定稿」一并冻结，需要时按清单恢复（每一项都有判据与截图文件名）。
- ⏳ **牌面随天气变亮（R4′）** —— 待确认（晴天 / 日落 / 夜间 各看一眼）。
- ⏳ **真机门禁**：G18 黄金帧 / G27–G30 / Planet 场景首跑 / 水面与路边模型首跑 —— 全部需真机。
- ⛔ **已知取舍 / 明确不做**：法线贴图、逐面法线、背面剔除、干砌石 / 栅栏、交通车、卡顿 B、触摸拦截、天空平面 Y 跟随相机、跨水面无桥。

⛔ 复现时的红线见上文「**定稿后仍然有效的红线**」一节（着色器只能由脚本生成、E51/E52 永不 push、
改上游语义前先看私有分支上的 JS 原型、⛔ 禁止 PowerShell 对 UTF-8 源文件做文本往返）。
