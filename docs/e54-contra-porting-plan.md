# E54 魂斗罗（UEFI_Contra）第一阶段移植开发文档

> **版本**: v1.0（2026-11-08 从调研文档拆出）
> **状态**: **第一阶段待开发**（选定上游已定；Phase 0 / Phase 1 排期成立，Phase 2~6 暂缓）
> **关联**:
> - `docs/e54-run-and-gun-survey.md` —— **U 表示「是什么、为什么」的唯一来源**（§5.8 选定上游、§六~§八 素材/许可/快照、§十八 里程碑、附录 C.12.1 取证留档）。本文档只摘录第一阶段，⛔ 不复述已被裁决移出正文的弃用候选
> - `scripts/e54_uefi_contra_probe.py` —— UEFI_Contra 体量与分离度的**取证脚本**（`VERDICT = ✅ 采用`；本文档引用它的实测数字）
> - `scripts/e54_body_clean_check.py` —— 弃用候选禁词闸（禁词表从该探测脚本的 `REPO` + `VERDICT` 反推，⛔ 不落地）
> - `docs/visualizer-effects-list.md` —— 效果清单索引（⚠️ 编号占用情况见下方注记）

> ⚠️ **编号注记**：调研文档把「自动玩街机游戏合集」自编号为 E54；而 `docs/visualizer-effects-list.md` 里 **E54 当前是 `ECO_GLOBE`「生态地球」**。本文档沿用调研文档口径（E54 = 街机合集 / 魂斗罗第一阶段），正式登记到效果清单时须由所有者裁定编号归属后再改。

---

## 一、目标与定位

**一句话目标**：第一阶段＝**只建立效果 + 只移植魂斗罗这一个游戏 + 实现自动演示**（无需用户任何操作，画面自行推进）。

| 维度 | 第一阶段口径（所有者 2026-11-08 裁决） |
|---|---|
| 交付 | `VisualizerTheme` 一项（街机合集）+ 魂斗罗一个游戏 + 自动玩 bot |
| 上游 | 池外候选 `MikeWuPing/UEFI_Contra`（UEFI 固件上的 NES《魂斗罗》复刻，取证见 §二） |
| 排期 | Phase 0（基础设施 + 取证）**4-7 人日** + Phase 1（魂斗罗移植）**12-18 人日** ⇒ 合计 **16-25 人日**（12-18 为人日估计，须读源后收窄） |
| 终局方向 | 多游戏合集（「一个效果内嵌多个游戏，上下键 / 手势上下滑动切换」）——**排期后置** |

### 1.1 硬约束（沿用调研文档 §〇，第一阶段相关项）

| # | 约束 | 对第一阶段的含义 |
|---|---|---|
| C1/C2 | **WebView + WebGL 路线已否决**；必须**原生渲染**（GLSurfaceView + OpenGL ES） | 与 E52 HighwayRenderer 同一载体，创维电视（PowerVR G6110 / GLES 3.1）已验证可用 |
| C3 | **游戏逻辑可翻译到 Kotlin，渲染层必须原生重写** | UEFI_Contra 恰好满足这一条（sim/view 分离极干净，见 §二） |
| C4 | **许可证必须是 OSI 开源许可** | UEFI_Contra **无 LICENSE 文件**（README 自称 MIT）⇒ §8.7 第①道不通过，**由所有者 2026-11-08 裁决豁免**（「完全可以继续移植后开源」），事实如实留档（§3.3） |
| C6 | **音乐链接完全独立** | 游戏 bot 自玩、音乐自播；音乐帧推入控制器但**不驱动游戏逻辑** |
| C7 | **多游戏合集** | 约束**不删除**。第一阶段交付形态退化为「单游戏自动演示」，合集目标后置（Phase 4 暂缓） |
| C8 | **自动玩** | 每个游戏必须能无需用户操作独立运行 ⇒ Phase 1 必须交付 `ContraBot` |
| C5 | minSdk 22 / Android 5.1.1 电视 | 创维 9R54_G8S 是回归基准设备；**光栅化能力有限** ⇒ 纹理必须降档 + 合批（§4.3） |

### 1.2 第一阶段范围外（⏸ 暂缓、不排工作，清单保留在调研文档 §十八 Phase 2~6）

Breakout / 平台跳跃 / 横版跑轰 / 纵版射击 / agent-arcade 6 场景，以及调研文档 §5.8 列出的其余一切候选。**本文档不展开它们的设计。**

---

## 二、选定上游：UEFI_Contra 的事实表

> 以下各行数字**全部由 `scripts/e54_uefi_contra_probe.py` 实测再生**（快照 `logs_temp/e54_upstream_verify.json`），⛔ 不许手抄改动。逐条复算链路与「为什么它当初被判 ❌ 留档、v5.0 又如何转 ✅」的完整证据见调研文档附录 C.12.1。

| 项 | 实测事实 |
|---|---|
| 上游 | **`MikeWuPing/UEFI_Contra`**（池外候选，所有者 2026-11-04 在 122 条快照之外指定复看；2026-11-08 裁定采纳） |
| 形态 | 把 NES《魂斗罗》移植成 **UEFI 应用程序（`.efi`）**，在 QEMU + OVMF 的 UEFI Shell 里经 GOP 直接渲染、**无操作系统**；纯 C |
| 体量 | `src/` 29 个文件 / 389,299 B；**逻辑侧 7 个 `.c` 合计 2,252 行**（enemy 793 / game_state 458 / level 290 / player 287 / bullet 240 / weapon 109 / nes_palette 75）；**UEFI 侧 5 个 `.c` 合计 971 行**（main 419 / sprites 288 / render 174 / input 72 / entry 18） |
| 移植最有利的性质 | 逻辑侧 7 个 `.c` 的**真协议命中全部为 0**（框架耦合收口在 UEFI 6 文件）；`player.c` 是 **INT32 8.8 定点**（15 处 `>>8`/`<<8`），全仓 `float|double` 仅 enemy.c 一处 ⇒ **逻辑层可整段照搬，无浮点回归** |
| 素材（⛔ 一条不取） | 素材头 6 个 = **277,085 B / 1,430 行**，全部是 **ROM 提取**的图形/关卡数据；其中 3 个**从未被 include 的死头文件**占全仓 52.3%（`tiles_c.h` 183,756 + `intro_tiles.h` 22,232 + `intro_nametable.h` 5,161） |
| 自动玩 | 上游 attract mode 是**假自玩**（§4.2），能借的只有状态机形状，自玩本体必须自写 |
| 输入 | **仅键盘**（`gST->ConIn->ReadKeyStroke`）；NES 的 A/B/Start 原是键盘键，须重挂到电视遥控（§4.4） |
| 许可（如实留档） | ⚠️ 仓库内**无 LICENSE 文件**（README「开源许可」章节自称 MIT）⇒ 法律口径仍是 all-rights-reserved；《Contra》为 Konami 商业 IP；精灵素材上游亦无许可。**所有者 2026-11-08 裁决：「完全可以继续移植后开源」⇒ 不阻塞**（裁决原文在调研文档附录 C.12.1，⛔ 不再复议） |

### 2.1 为什么选它（而不是池内其它候选）

- 它是本池所有候选里**「工程上最接近可直接机械翻译」的一档**（sim/view 分离、定点数、静态定长数组、256×240 + 整数缩放的呈现层形状）；
- 它就是**用户指定的那一个**（2026-11-08 第一阶段口径）；
- 池内候选清单、方案 A/B/C 的取舍过程 **全部留在调研文档**，本文档不再复述。

---

## 三、素材来源与许可（两条线，分开登记）

### 3.1 精灵素材：`nes-contra-us` 精灵库（第一阶段唯一素材来源）

UEFI_Contra 运行期从磁盘读 `sprites/sprite_%02x.spr`（**仓库内 `.spr` / `.png` / `tools/` 全为 0** ⇒ 素材管线必须自建，§3.2）。精灵基座是上游 **`nes-contra-us`**（NES 反汇编项目，被 UEFI_Contra README 承认为素材来源）的 `docs/sprite_library/contra_sprites/`。本轮本地实测：

| 项 | 实测值 |
|---|---|
| 数量 | **232 张 PNG + 1 张 GIF**（该仓库共 483 个文件） |
| 单张体积 | **89-644 B**（8×8 ~ 16×16 级像素帧图；合计 **48.9 KB**） |
| 命名 | 与 `nes-contra-us` `bank1.asm` 的 `sprite_ptr_tbl_0/1` 精灵表标签一致（`sprite_02..sprite_5f`、`_p2` 双人变体、`player_{1,2}_game_over`、`player_{1,2}_lives_medal`） |
| 性质 | 该仓 README 自述「extracted from the ROM for ease of viewing」、**不参与游戏代码运行**；另有 `probotector_sprites/`（168 PNG）属姊妹作 Probotector，**第一阶段不排它** |
| 归属 | ↑ 原图「版权归原始权利人所有」⇒ **不随 UEFI_Contra 的 MIT 声明转移授权**；采用前提是所有者裁决的「继续移植后开源」，**素材出处与归属须按 NOTICE 如实登记**（§3.3） |

### 3.2 `.spr` 格式与第一阶段素材管线

- **`.spr` 格式**：4 字节头（宽 / 高各 UINT16 **LE**）+ `w×h×4` **RGBA32** 像素 —— 这是 UEFI_Contra `sprites.c` 装载时认的格式；
- **第一阶段管线**（调研文档 §5.8 指定）：从 `nes-contra-us` 精灵库取图 → 清理 → 转 `.spr` 或直接进 GLES 纹理 → 走**图集合批 + RGB_565 邻近缩放**路线（§4.3）；
- ⛔ **关卡 / 背景 / HUD 等 ROM 素材一律不取、一律自绘**（UEFI_Contra 那 277 KB 素材头一条都不许进 APK）。

### 3.3 许可与归属登记（禁止事项照抄）

| 项 | 第一阶段要求 |
|---|---|
| 快照 vendoring | `app/src/e54_upstream/uefi_contra/`（仓库内、`main` 源集之外，Gradle 不打包）+ `UPSTREAM.md`（repo / path / commit / date / license）+ 许可事实记录；**⛔ 只要上传了就不许运行时依赖在线 URL**（R9：池内 44% 已 404） |
| NOTICE | 精灵库按**素材**登记（出处 `nes-contraus` SP 精灵库、归属「版权归原始权利人所有」）；代码侧保留上游版权声明（上游无 LICENSE，登记「README 自称 MIT、仓库内无 LICENSE 文件」这一事实 + 所有者裁决记录） |
| 移植文件版权头 | 每个从上游逻辑侧翻译的 `.kt` 文件顶部保留「移植自 `MikeWuPing/UEFI_Contra`，仓库内无 LICENSE 文件（README 自称 MIT）；渲染层为本项目独立重写」的如实标注 |
| ⛔ 不许做的 | 不改写上游许可结论、不删除版权事实、不把 ROM 素材头写进快照以外的任何位置、不在文档里把「裁决豁免」写成「许可合规」 |

---

## 四、开发任务分解（第一阶段 = Phase 0 + Phase 1）

### 4.1 Phase 0：基础设施 + 上游取证（4-7 人日）

| # | 任务 | 产出 |
|---|---|---|
| 0.1 | `VisualizerTheme.ARCADE_GAMES` 枚举 + `VisualizerRendererFactory` 新 `when` 分支（第一阶段只装魂斗罗一个） | 枚举条目 + 工厂分支 |
| 0.2 | `ArcadeGame` 接口 + `GameInput` / `GameSnapshot` 数据类 | 接口定义 |
| 0.3 | `ArcadeGLSurfaceView` + `ArcadePrimitiveRenderer` | GL 渲染基座 |
| 0.4 | `ArcadeGameController` + `ArcadeGameManager`（合集状态机；第一阶段只有一个游戏实例，形态照终局搭） | 状态机 |
| 0.5 | `ArcadeGameRenderer`（实现 `VisualizerRenderer`，`isViewBased = true`，与 E52 `HighwayRenderer` 同模式） | 合集入口 |
| 0.6 | 单元测试：接口契约 + 控制器状态机（sim 层纯 Kotlin、无 GL 依赖） | 测试类 |
| 0.7 | **上游快照 vendoring**（D8，§3.3）：`app/src/e54_upstream/uefi_contra/`（UEFI_Contra 逻辑侧 7 个 `.c` + `nes-contra-us` 精灵库**文件清单**）+ `UPSTREAM.md`（commit SHA）+ 许可事实记录 + 一条「快照目录不进 assets / 不进 APK」护栏（对齐 `GlobeAssetsHygieneTest` 口径） | 可复核的移植依据 |
| 0.8 | **采用前许可三道核对**（调研文档 §8.7）逐条签字并落档：第①道（LICENSE 文件存在）**按所有者裁决豁免**且如实记录；第②道族别（无 LICENSE ⇒ 记录事实）；第③道素材目录（`nes-contra-us` 精灵归属）人工读 + 写进 `NOTICE.md` | 核对记录 |

> ⚠️ 0.7 / 0.8 **不是可选**：没有快照就没有「翻译对照」的对账口径（§6 测试策略依赖它），而快照里的文件从此与 E51/E52 移植件同性质（§5）。

### 4.2 Phase 1：魂斗罗 —— UEFI_Contra 移植（12-18 人日）

| # | 任务 | 产出 |
|---|---|---|
| 1.1 | **`ContraGame` sim 层**：按 UEFI_Contra 逻辑侧 **7 个 `.c` / 2,252 行**逐行翻译成 Kotlin（enemy / game_state / level / player / bullet / weapon / nes_palette）。sim 层**不 include 任何框架头**（照上游结构）；**素材头全剔**（6 个头文件一条不译）；浮点点位全仓仅 enemy.c 一处，8.8 定点用 `Int`/`Long` 复现 | 纯 Kotlin sim |
| 1.2 | **`ContraBot`**（自动演示本体）：状态机形状取上游三常量（空闲 **360 帧**进 demo → `gIntroTimer > 3840` 回标题 → **START 打断**），demo 行为**自写**（向右推进 + 按相位跳/射 + 卡住检测 + 死亡重试；上游 demo 是假自玩，无录像无 AI 不会过关，能借的只有形状） | bot 决策 |
| 1.3 | **`ContraRenderer`**（OpenGL ES）：逻辑分辨率 **256×240** → 最大**整数倍缩放**（上游 `MAX_SCALE 4`）→ 居中偏移 → 精灵位图上传 GLES；精灵走**图集合批 + RGB_565 邻近缩放**；**关卡 / 背景 / HUD 一律自绘**（⛔ 不取 277 KB ROM 素材头） | GL 渲染 |
| 1.4 | **精灵素材管线**：`nes-contra-us` 精灵库 **232 PNG + 1 GIF** 取图 → 清理 → 转 `.spr`（或直接纹理）+ **素材清单**（文件名 / 来源 / 归属 / 体积，合计 48.9 KB） | 素材包 + 清单 |
| 1.5 | **输入适配**：NES 的 A/B/Start → **电视遥控器**键位映射 + 单元测试 + 真机自动验证（帧率 + 无卡死 + 遥控能打断 demo 回标题） | 测试类 + 报告 |

> **人日估计**：1.1（2,252 行翻译）≈ 5-7；1.2 ≈ 2-3；1.3 ≈ 3-4；1.4 ≈ 1-2；1.5 ≈ 1-2 ⇒ 合计 12-18。UEFI 侧 971 行（main / sprites / render / input / entry）本来就是 E54 里要**重写或丢弃**的部分（渲染换 GLES、装载换纹理上传、输入换遥控端口），**不计入移植量**。

### 4.3 渲染与资产的技术要点

| 主题 | 第一阶段口径 |
|---|---|
| 绘制方式 | 矩形 / 圆形 / 线条 / 粒子走**图元渲染**（`glDrawArrays` + 顶点颜色，与 E47/E48/E49/E51 一致）；**精灵**走 `GL_TEXTURE_2D` + **图集（atlas）合批** |
| 纹理降档 | ⚠️ minSdk 22 创维电视（G6110）光栅化能力有限 ⇒ 大贴图必须降到 **RGB_565 量级**并按瓦片表合批，⛔ 不是「能画就行」 |
| APK 增量 | 精灵素材 48.9 KB + 自绘批次预判 ⇒ 第一阶段**应远低于 E52 数 MB 量级**；关卡 / 背景自绘不增加外部资产 |
| 音频 | **⛔ 上游音效一律不搬**（游戏不发声，音乐走现有播放器） |

### 4.4 输入与自动玩的行为口径

| 项 | 口径 |
|---|---|
| 遥控键位映射 | 方向键 = 移动 / 瞄准方向；确认键 = 跳跃或射击（分割 A/B 语义时取一种并记录）；返回键 = 上游 START 语义（**打断 demo、回标题**）；其他键位冲突验证留到合集期 D5，第一阶段只验遥控本身可用 |
| 自动演示循环 | 标题（`gIntroTimer > 360` 帧自动进 demo）→ demo（向右推进 + 按相位跳/射）→ 死亡重试 → 回标题（`> 3840` 帧或进度自然回环）；START 遥控随时打断回标题 |

---

## 五、分支与分发纪律（与 E51 / E52 同性质）

| 项 | 纪律 |
|---|---|
| 代码 / 测试 / 资产落点 | **只进本地分支 `feat/e51-private`**（不设 upstream），**永不 push / tag / 发 release / 上传任何远端**（仓库已是 PUBLIC，一次误推即公开） |
| 本地构建携带 | 仓库树内 gitignored 覆盖层 + 本机 Gradle init 脚本挂载隐藏分支源集；**CI / 公开构建不含**（见 AGENTS.md 的 E51/E52 覆盖层规则） |
| 若覆盖层掩蔽了仓库同名文件 | `VisualizerRendererFactory.kt` / `VisualizerStage.kt` / `RenderContext.kt` 等需改两处（仓库 + 覆盖层），`python scripts/sync_private_overlay.py --check` 必须 0 漂移 |
| 构建前检查 | 改完 Kotlin 源码先跑 `python scripts/kotlin_comment_balance.py`（可嵌套块注释的语法闸，v5.0 曾因注释里写 `**` 打断 4 条并行 lane） |

---

## 六、测试与验收（第一阶段口径）

| 层 | 手段 | 验收 |
|---|---|---|
| sim 层单测 | 纯 Kotlin、无 Android / GL | 翻译对账（对照 Phase 0.7 快照逐函数），定点数值断言，无浮点回归 |
| bot 单测 | 确定性 RNG（`ArcadeRandom` = xoshiro128\*\*）；固定时间步（`ArcadeClock`） | 同帧输入 ⇒ 同帧状态；演示循环不断、不卡死 |
| 控制器 / 输入 | Robolectric（JUnit4） | 状态机：标题 → 打断 → demo → 回标题；遥控键位映射全键命中 |
| 真机验证 | 创维电视回归基准设备（API 22 / G6110） | **帧率 + 无卡死 + 遥控打断**；**画面观感由所有者本人确认**（代理不操作电视） |

> ⚠️ 与 E52/E50 的既有约定一致：**不在电视上之外的动作**；需要电视上的画面 / 帧率 / 交互证据 ⇒ 请所有者本人去看 / 操作 / 截图。

---

## 七、里程碑（第一阶段）

```
Phase 0 基础设施 + 取证        ██████████░░░░░░░░░░░░░░░░  4-7 人日
Phase 1 魂斗罗 (UEFI_Contra)   ████████████████████████░░  12-18 人日（估计）
─────────────────────────────────────────────────────────
第一阶段合计（Phase 0 + Phase 1）          ≈ 16-25 人日
Phase 2 平台跳跃    ⏸ 暂缓（v5.0，清单在调研文档保留）
Phase 3 横版跑轰    ⏸ 暂缓（v5.0）
Phase 4 合集集成    ⏸ 暂缓（v5.0，C7 约束不删除）
Phase 5/6           ⏸ 暂缓（v5.0）
```

---

## 八、相关脚本与再生成纪律

| 脚本 | 用途 | 何时跑 |
|---|---|---|
| `scripts/e54_uefi_contra_probe.py` | UEFI_Contra 体量 / 分离度 / 素材头度量取证（`VERDICT = ✅ 采用`）；加 `--offline` 复用 `logs_temp/uefi_contra_probe/` 镜像 | 数字变更 / 上游更新后 |
| `scripts/e54_body_clean_check.py` | **只扫调研文档正文**（§〇~§二十）：弃用候选禁词闸 + 附录阳性对照 | 改调研正文后必须复跑，0 违规才成立 |
| `scripts/kotlin_comment_balance.py` | Kotlin 可嵌套块注释语法闸 | 每次改 Kotlin 源码后、跑 Gradle 前 |
| `scripts/sync_private_overlay.py --check` | 私有分支 ↔ 覆盖层来源漂移检查 | 改 E51/E52 相关文件后 |

> 本文档与调研文档的分工：调研文档是**选型与调研证据的唯一来源**（含附录 A/B/C 的全量留档）；本文档是**第一阶段开发的排期与实现口径**。两处数字不一致时 ⇒ **以调研文档 §5.8 / 附录 C.12.1 的脚本产物为准**并回来更正本文档。
