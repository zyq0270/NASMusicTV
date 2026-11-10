# E55 街机合集（魂斗罗 / UEFI_Contra）一次性移植开发文档

> ⛓ **2026-11-10 定稿归档**：第一阶段（Phase 0 + Phase 1）已交付并经四轮真机反馈修复，
> 文档移入 `docs/archive/` **不再维护**。
> - **本文件是 v1.3，两个分支同一份**（`main` 与 `feat/e51-private` 逐字节一致，2026-11-10 合并）：
>   正文 = 「一次性全量」口径；状态行「待开发」是**过期标记**（AGENTS.md「Never trust the doc's own status header」）。
> - **落地纪实**：变更记录见 `docs/technical-overview.md` §10.375–§10.377、§10.381；交付版本 v2.39.17，
>   **v2.39.18 起改为公开分发**。
> - ⭐ **实现落点（2026-11-10 第三次所有者裁决后已改）**：「魂斗罗应该是公开的、在 main 分支下的」
>   ⇒ 渲染器 / 图集 / 关卡内容 / 6 个测试**全部已在 `main`**（`visualizer/arcade/` +
>   `app/src/main/assets/e54_arcade/contra_atlas.png`），`arcade.enabled` **缺省 true**
>   ⇒ 公开 APK **默认带**该效果（公开版可选 26 → 27 套）。
>   ⛔ **仍然不可再分发的只有上游原件**（`app/src/e54-upstream/`，ROM 提取的精灵与瓦片）—— 保持 `.gitignore`。
>   素材出处与许可事实见 `docs/e54-sprite-attribution.md`；落点分工见本文件 §5.2 的 ⭐ 补记。
> - **离线诊断与补丁工具**留档 `docs/archive/verification/e54/`（两分支同一份，2026-11-10 同步）。
> - **状态**（v1.2 原行，已过期）：~~**待开发**~~ ⇒ ✅ **第一阶段已交付**（v2.39.17 / versionCode 202，2026-11-10）。
> - ⭐ **效果编号 = E55（已定，2026-11-10 所有者裁决「按已开发完成代码中既有的现实来确认」）**：枚举
>   `VisualizerTheme.ARCADE_GAMES` 的 `ordinalLabel` 就是 `"55"`，效果清单显示 `E55 街机合集`；
>   `"54"` 归 `ECO_GLOBE`「生态地球」（已随 v2.39.14 发布）⇒ ⛔ 不改回 `"54"`。
>   ⚠️ **文件名与脚本的 `e54_` 前缀保持原样**（`scripts/e54_*.py`、标记区 `E54-POOL10`、`app/src/e54-upstream/`）：
>   那是脚本 ↔ 文档的契约与历史文件名 ⇒ **效果编号改 E55，文件名不动**。
>
> ✅ **2026-11-10 第一轮落地**：Phase 0 + Phase 1 完成（当时 13 文件 / 3,429 行、5 个测试文件 / 29 用例）。
> 第二轮素材改造后为 **14 文件 / 2,998 行、5 个测试文件 / 30 用例**（删 521 行自绘字符画，
> 换成生成器产物对应的帧表与映射），详见 `docs/technical-overview.md` §10.376。
>
> ✅ **2026-11-10 落地裁定**：交付时取 `"55"`（`VisualizerTheme.ARCADE_GAMES`），效果清单里显示为 `E55 街机合集`。
> ⛔ **不要把它改回 `"54"`**（会与「生态地球」撞号）—— 同日所有者按代码现实正式定为 **E55**（见下方「编号」段）。
>
> ✅ **实施结果（2026-11-10，详见 §10.375 / §10.376 / §10.377）**：Phase 0 契约层 + Phase 1 魂斗罗移植**全部落地**，
> 第四轮修复后 `visualizer/arcade/` 为 **14 文件 / 3,211 行 + 6 个测试文件 / 42 个用例**；
> bot 可连续推进 8 关并回到第 1 关，且能打飞行胶囊、吃奖励、升散弹。
>
> ✅ **2026-11-10 第二轮（所有者裁定「不要自绘，全部用上游」）**：第一轮的「① 精灵全自绘」**已撤销**——
> 改为按本方案 §3.1 / §1.4 的**原定管线**取 `vermiceli/nes-contra-us` 的 **232 张精灵 PNG**
> 与 UEFI_Contra `tiles_c.h` 的 **256 块 level 1 瓦片**，由 `scripts/e54_pack_sprites.py`
> 打成 `assets/e54_arcade/contra_atlas.png`（1024×1024 / 489 帧 / 约 56 KB，⛔ 自绘帧已全部删除）。
> 详见 `docs/technical-overview.md` §10.376 与 `docs/e54-sprite-attribution.md`。
>
> **版本**: v1.3（2026-11-10 定稿：正文 = 一次性全量口径 + 四轮落地纪实；两分支同一份）
> **关联**:
> - `docs/e54-run-and-gun-survey.md` —— **「是什么、为什么」的唯一来源**（§5.8 选定上游、§六~§八 素材/许可/快照、§十八 里程碑、附录 C.12.1 取证留档）
> - `scripts/e54_uefi_contra_probe.py` —— UEFI_Contra 体量与分离度的**取证脚本**（`VERDICT = ✅ 采用`）
> - `scripts/e54_body_clean_check.py` —— 弃用候选禁词闸（禁词表从探测脚本的 `REPO` + `VERDICT` 反推，⛔ 不落地）
> - `docs/visualizer-effects-list.md` —— 效果清单索引（编号见 §0.0，已裁决）

> ⚠️ **编号**：调研文档把「自动玩街机游戏合集」自编号为 E54；而效果清单里 **E54 当前是 `ECO_GLOBE`「生态地球」**。**所有者 2026-11-09 裁决按建议 A 处理：本效果取 `"55"`**，枚举 KDoc 注明「占位号，待后续裁定 E54/E55 归属」。
> ⭐ **2026-11-10 补充裁定**：所有者裁决「**按已开发完成代码中既有的现实来确认**」⇒ 编号 **E55 已定**
> （枚举 `ARCADE_GAMES` 的 `ordinalLabel` = `"55"`，效果清单显示 `E55 街机合集`）⇒ 上面的「占位 / 待裁定」
> 作废，⛔ 不改回 `"54"`（会与「生态地球」撞号）。详见 §0.0。

---

## 〇、裁决与修订记录

### 0.0 编号：✅ 已裁决（`"55"`，2026-11-10 按代码现实定为 **E55**）

| 项 | 结论 |
|---|---|
| 定案 | 占位号 **`"55"`**（现枚举占用 `05,13~17,25,29,30,33,35,37~44,46~54`，共 28 项；`"55"` 是自然空号） |
| 理由 | `ordinalLabel` 是**稳定展示编号**（`VisualizerThemeTest.kt:123` 断言互异），且清单由 `gen_effects_doc.py` 按 `no` 升序生成 ⇒ 编号必须先落定才能写枚举 |
| 遗留 | ✅ **已闭合（2026-11-10）**：按代码现实定为 **E55**，编号未再改动；判定口径 = 枚举 `ARCADE_GAMES` 的 `ordinalLabel` = `"55"` |
| ⛔ 不可选 | 让 `ECO_GLOBE` 改号让位 —— 它已随 v2.39.14 发布，改号会让老用户看到编号跳变 |

### 0.1 所有者 2026-11-09 四项裁决（⛔ 生效口径，不再复议）

| # | 裁决原文 | 对本文件的影响 |
|---|---|---|
| **J1** | **「不分阶段一次性全部实施，要能够自动玩」** | 取消 v1.1 的 Phase 0 / Phase 1 分期与 Phase 2~6 暂缓写法 ⇒ 改为**单一交付批次**（§4 的 W1~W10 按依赖排序，不是阶段门）；自动玩从「必有」升级为**验收硬条件**（§4.10 / §6） |
| **J2** | **「移植全部关口」** | 由 §2.2 的实测决定实现口径：上游**只有第 1 关有地形数据**（8 个关卡头、1 套地形）⇒ **8 关 = 1 关忠实移植 + 7 关自建**（§2.3）。⛔ 不是「只做 1 关」，也不是「7 关都有上游数据可移植」 |
| **J3** | **「缺帧时可以自绘补」** | 精灵管线从「取图 → 转图集」升级为「取图 → **补帧** → 转图集」⇒ 新增 §3.3 自绘规则；1.4 的工作量随 `N2` 覆盖度浮动 |
| **J4** | **「其他按你建议处理」** | 逐条落定：Tier 取 **ADV**、缩放取 **顶点 ×1.25 → 等效 320×240**、**返回键语义不改**、随机源**复用 `VisualizerRandom`**、切走 **暂停+保留**、Phase 0 人日**接受上调**（并入 §7 重算）、**编号占位 55** |

> ⚠️ **J2 与 J3 的组合后果**：本项目的交付物**不再是「上游的移植」，而是「以上游引擎为骨架的自建 8 关魂斗罗」**。这一点必须在 `UPSTREAM.md`、`NOTICE.md` 与代码版权头里如实区分：
> - **移植件**（忠实翻译，标注上游 commit）：逻辑侧 7 个 `.c`、第 1 关地形（`gLevel1Supertiles` / `gLevel1Screens`）；
> - **自建件**（本项目原创，标注本项目 GPL v3）：第 2~8 关地形与敌人布点、缺失精灵帧、bot 决策、全部渲染层。
> ⛔ 不得把自建部分写成「移植自上游」，也不得把移植部分写成「本项目原创」。

### 0.2 v1.1 审阅修订项（R1~R18，历史留档）

保留以备复核。v1.2 未推翻其中任何一条，仅按 J1~J4 调整了范围与排期。

| # | 位置 | v1.0 的问题 | 处置 |
|---|---|---|---|
| R1 | §0.0 | 编号裁定只在文首注记，未列任务 | 提为阻塞项 → v1.2 已裁决 |
| R2 | 那两行「枚举 + 工厂」 | 实际牵动 12 处既有门禁 | 拆成 W1a~W1e + §4.2 门禁全表 |
| R3 | §4.2（新增） | 未提任何既有门禁 | G1~G12（含文件:行号） |
| R4 | 渲染器接入 | 只写 `isViewBased`，漏 `renderFps()` ⇒ 帧率验收不可测 | 明确要求 `override fun renderFps()` |
| R5 | 输入口径 | 方向键移动/瞄准与 C8「零操作」矛盾，且 ←/→ 已被舞台占用 | 收缩承诺；冲突提为显式决策 |
| R6 | §4.8.1（新增） | 未提 `VisualizerRenderer` **没有**按键钩子 | 写清「先建输入通路」 |
| R7 | §二 表头 | 快照归属错误（`e54_upstream_verify.json` 是 122 行池快照） | 改为探针 stdout |
| R8 | 快照护栏 | `UpstreamOriginalsHygieneTest` 只扫 `.js/.mjs` ⇒ `.c/.h` 漏 | 扩扩展名 + 白名单 |
| R9 | §二 体量行 | 「UEFI 侧 5 个 `.c`」与「6 文件」并列矛盾 | 6 = 5 个 `.c` + `types.h` |
| R10 | 缩放 | `MAX_SCALE 4` 是 GOP 产物的残留；「整数倍」与 4:3 不可兼得 | §4.6.1 顶点 ×1.25 |
| R11 | `.spr` | 中间格式留了「或」的口子 | 定死构建期烘焙 |
| R12 | 拼写/命名 | `nes-contraus` 少一横；`e54_upstream` 破坏 `-upstream` 约定 | `nes-contra-us` / `e54-upstream` |
| R13 | 分支纪律 | 「只进本地分支」**架构上做不到** | E51/E52 双变体 + 覆盖层登记（§五） |
| R14 | 验收/排期 | 无门禁清单；接线成本算成 0 | §6 + §7 |
| R15 | 脚本清单 | 漏了清单的唯一再生成入口 | §八 补 `gen_effects_doc.py` |
| R16 | 时序 | tick 线程、快照分配、随机源全未定 | §4.9 定死 |
| R17 | 人日分母 | 把「2252 行」当「完整魂斗罗」 | §2.2 已实测填实 |
| R18 | 切走/切回 | 游戏状态未定义 | 暂停 + 保留 |

### 0.3 v1.2 修订项（本次）

| # | 位置 | 改动 | 原因 |
|---|---|---|---|
| V1 | 全篇 | 标题与状态去掉「第一阶段」 | J1：不分阶段 |
| V2 | §0.1 | 新增四项裁决记录 + 「移植件 / 自建件」区分要求 | J1~J4 |
| V3 | §2.2 | 「两个未测项」→ **实测结论**（上游 1 关 / 11 敌种 / 6 武器 / 8 关卡头） | J2 的前置事实，本轮已对下载镜像逐文件核对（镜像位置与待办见 §0.4） |
| V4 | §2.3（新增） | 「全部关口」的实现口径：8 = 1 移植 + 7 自建 | J2 |
| V5 | §3.3（新增） | 缺帧自绘规则（调色板 / 尺寸 / 命名 / 标注） | J3 |
| V6 | §4 | Phase 0 / Phase 1 → **单一交付的 W1~W10**（WBS 按依赖排序，不是阶段门） | J1 |
| V7 | §4.10（新增） | 完成定义（DoD）：自动玩为硬条件 | J1 |
| V8 | §6 | 验收扩到 8 关；自动玩列为不可豁免项 | J1 / J2 |
| V9 | §7 | 里程碑从「两段」→「一批」，人日重算为 **39-58** | J1 / J2 / V4 |
| V10 | 附录 A | 补 v1.2 新增的 6 条实测事实 | 可复核性 |
| V11 | §1.3 / §4.1 W8 / §7 / §7.1 | 缺帧自绘由 **+3~4** 改 **+3~5**，总量 **39-57 → 39-58** | v1.2 复审（F1/F3）：上游源码为准 —— `player.c:39-41` 只定义 3 个动作帧、11 敌种无死亡序列、6 武器各 1 代表帧 ⇒ 取 §3.3 的 3~5。三口径（§1.3 增量 / §4.1 WBS / §7.1）重算后均为 39-58 |
| V12 | §7.1 | bot 行 `+1~2`→`+1`、W1/W2/W9/W10 行 `+1~2`→`+1`；尾注「79%」重算为「约 90%」 | v1.2 复审（F1/F2）：原表尾合计按上界少算，致 §7.1 加总 60≠57；79% 两口径都对不上（实为 J2+J3=19~29 占增量 21~31 的 90~94%） |
| V13 | 附录 A | 两条 grep 命令口径修正 + 补缺帧取证命令 | v1.2 复审（F4）：`ENEMY_[A-Z]` 会多计 include guard `ENEMY_H`（须 `grep -v ENEMY_H`）；`grep -c "gLevel1"` 返回 8 含 2 处调色板（须用 `gLevel1(Screens\|Supertiles)` 得 6 处地形） |

### 0.4 数字的唯一来源与复算入口

| 项 | 事实 |
|---|---|
| 唯一再生入口 | `python scripts/e54_uefi_contra_probe.py`，输出到 **stdout**（脚本只 `print`，**不写任何 JSON**） |
| ⛔ 不要引用 | `logs_temp/e54_upstream_verify.json` —— 那是 `scripts/e54_upstream_verify.py:474-516` 产出的 **122 行池快照**，字段为 `path/http/exists/license/stars/pushed/archived/desc/renamed_to/name`，**不含行数与字节数** |
| `--offline` 前置 | 需 `logs_temp/uefi_contra_probe/` 镜像目录。⚠️ **2026-11-09 的实际状态**：31 个文件已下载并与 GitHub HEAD 一致，落在 `app/src/e54-upstream/uefi_contra/`；该目录**已由 `.gitignore:` 忽略**（§5.3 条目，`git check-ignore` 退出码 0，不再出现在 `git status`）。**尚待**移到 `logs_temp/uefi_contra_probe/` ⇒ `--offline` 目前仍不可用；移动前 ⛔ 不要 `git add -f` 该目录 |
| ⚠️ v1.2 新增事实的来源 | §2.2 / §2.3 的关卡·敌种·武器数字**不是**探针输出，而是本轮对镜像源码的逐文件核对（`level_data.h` / `level.c` / `enemy.h` / `weapon.c`），复核命令见附录 A。⛔ 不要把它们写成「探针实测」 |

> ⚠️ `logs_temp/` 被 `.gitignore:106` 忽略 ⇒ 镜像与留档**只在本机**，作用是「同机复算」。跨机器复现靠 `UPSTREAM.md` 里的 commit SHA。

---

## 一、目标与定位

**一句话目标**：交付**一个**可视化效果——街机合集，内含**魂斗罗全 8 关**，**无需用户任何操作即可自动演示通关循环**。

| 维度 | 口径（所有者 2026-11-09） |
|---|---|
| 交付 | `VisualizerTheme` 一项（`ARCADE_GAMES`「街机合集」，占位号 `"55"`）+ 魂斗罗 8 关 + 自动玩 bot |
| 上游 | 池外候选 `MikeWuPing/UEFI_Contra`（UEFI 固件上的 NES《魂斗罗》复刻） |
| 实施方式 | **一次性全部实施**（J1）——⛔ 不分阶段、不设「先做 1 关再看」的中间交付 |
| 关卡范围 | **全 8 关**（J2）——口径与代价见 §2.3 |
| 素材 | 精灵取自 `nes-contra-us`；**缺帧自绘**（J3，规则见 §3.3） |
| 排期 | **39-58 人日**（估计，§7；分母已由 §2.2 的实测收窄） |

### 1.1 硬约束

| # | 约束 | 含义 |
|---|---|---|
| C1/C2 | **WebView + WebGL 路线已否决**；必须**原生渲染**（GLSurfaceView + OpenGL ES） | 与 E52 `HighwayGLSurfaceView` 同一载体，创维电视（PowerVR G6110 / GLES 3.1）已验证可用 |
| C3 | **游戏逻辑可翻译到 Kotlin，渲染层必须原生重写** | 逻辑侧 7 个 `.c` 的真协议命中为 0（§二） |
| C4 | **许可证必须是 OSI 开源许可** | 上游**无 LICENSE 文件**（README 自称 MIT）⇒ 第①道不通过，**所有者 2026-11-08 裁决豁免**，事实如实留档（§3.4） |
| C6 | **音乐链接完全独立** | bot 自玩、音乐自播；音乐帧推入控制器但**不驱动游戏逻辑** |
| C7 | **多游戏合集** | 本效果命名即「街机合集」，本次只装魂斗罗一个游戏；多游戏切换是同一枚举的后续扩展，**不另立效果** |
| C8 | **自动玩** | 无需用户操作独立运行 ⇒ `ContraBot` 是**验收硬条件**（§4.10 / §6） |
| C5 | minSdk 22 / Android 5.1.1 电视 | 创维 9R54_G8S 是回归基准；光栅化能力有限 ⇒ 纹理降档 + 合批（§4.6） |

### 1.2 范围（J1：一次性全量，不再有 Phase 2~6）

v1.1 把「多游戏合集」等列为 Phase 2~6 暂缓。按 J1，本文档只描述**一次交付**内的全部内容：

**包含**：接线（枚举/开关/工厂/门禁）、sim 层移植、8 关内容、`ContraBot`、GLES 渲染、素材与自绘、输入通路、单测与门禁。
**不包含**：第二个及以后的游戏（合集扩容）、多游戏上下键切换、联网/存档/成绩。这些**不是暂缓项**，而是本效果命名里就预留的后续扩展 —— 本次交付的 `ArcadeGame` 接口必须能让它们后加而不改状态机（§4.3）。

### 1.3 为什么人日从 18-27 涨到 39-58

| 变化 | 增量（人日） | 依据 |
|---|---|---|
| v1.1 基线（接线 + 1 关移植 + bot + 渲染 + 素材 + 输入） | 18-27 | v1.1 §7 |
| **+ 第 2~8 关自建**（上游无地形数据） | **+14~21** | §2.3：7 关 × 2~3 人日 |
| **+ 第 1 关瓦片自绘**（地形是 ROM 提取物，⛔ 不能搬字节） | **+2~3** | §2.3 / §4.7 |
| **+ 缺帧自绘**（J3 允许补，故工作量上浮） | **+3~5** | §3.3：上游 `player.c:39-41` 只定义 3 个动作帧（站立/蹲/瞄上），缺行走/跳跃/死亡；11 敌种无死亡序列；6 武器各只有 1 代表帧 |
| **+ bot 跨 8 关导航**（原按单关写的「向右推进」不再够用） | **+1** | §4.5 |
| **+ 上游快照与留档**（v1.1 隐含在 0.7 内，此处单列） | **+1** | §4.1 W2 |
| 合计 | **39-58** | 逐项相加：下界 18+14+2+3+1+1=**39**，上界 27+21+3+5+1+1=**58**；与 §4.1 十项工作包之和一致 |

> ⛔ 不要把「39-58」写进对外承诺。它是**估计**，最大不确定性来自第 2~8 关的内容密度（每关几个 boss、多少敌兵波次）—— 这是**自建决策**，不是上游给定的事实。

---

## 二、选定上游：事实表

> 行数与体量数字由 `scripts/e54_uefi_contra_probe.py` 的 stdout 再生（§0.4），⛔ 不许手抄。逐条复算链路见调研文档附录 C.12.1。**关卡/敌种/武器三行是 v1.2 新增的直接源码核对**（非探针输出）。

| 项 | 实测事实 |
|---|---|
| 上游 | **`MikeWuPing/UEFI_Contra`**（池外候选，所有者 2026-11-04 指定复看；2026-11-08 裁定采纳） |
| 形态 | 把 NES《魂斗罗》移植成 **UEFI 应用程序（`.efi`）**，在 QEMU + OVMF 的 UEFI Shell 里经 GOP 直接渲染、**无操作系统**；纯 C |
| 全仓 / `src/` | 31 文件 / 403,927 B；`src/` **29 文件 / 389,299 B** |
| 体量 | **逻辑侧 7 个 `.c` 合计 2,252 行**（enemy 793 / game_state 458 / level 290 / player 287 / bullet 240 / weapon 109 / nes_palette 75）；**UEFI 侧 5 个 `.c` 合计 971 行**（main 419 / sprites 288 / render 174 / input 72 / entry 18） |
| 非素材源码 | 22 文件 / 111,292 B / 3,696 行（含 16 个头文件 1,891 行） |
| 移植最有利的性质 | 逻辑侧 7 个 `.c` 的**真协议命中全部为 0**（框架耦合收口在 **6 个文件 = 上面 5 个 `.c` + `types.h`**）；`player.c` 是 **INT32 8.8 定点**（15 处 `>>8`/`<<8`），全仓 `float\|double` 仅 enemy.c 一处 ⇒ **逻辑层可整段照搬，无浮点回归** |
| **关卡（v1.2 实测）** | **8 个关卡头**（`gLevelHeaders[8][32]`，各含调色板/碰撞门限/滚动类型）；**但地形只有第 1 关**：`gLevel1Supertiles[1648]` + `gLevel1Screens[13][56]`（**13 屏**，约 3328 px 宽）。`gLevel2..8` 的地形表**一个都不存在**；`level.c` 的 6 处地形取址全部硬编码 `gLevel1*` |
| **敌种（v1.2 实测）** | **11 种**：`WEAPON_ITEM 0x00` / `BULLET_ENEMY 0x01` / `PILL_BOX 0x02` / `FLYING_CAPSULE 0x03` / `ROTATING_GUN 0x04` / `SOLDIER 0x05` / `SNIPER 0x06` / `RED_TURRET 0x07` / `BOMB_TURRET 0x10` / `PLATED_DOOR 0x11` / `EXPLODING_BRIDGE 0x12`；后 3 种注释直写「Level 1 boss / bridges」 |
| **武器（v1.2 实测）** | **6 种**（`gWeaponSprites[7]` 注释 `R,M,F,S,L,B`，末项为溢出兜底），弹体槽位 `MAX_WEAPON_ITEMS = 6` |
| **过关推进（v1.2 实测）** | ⛔ **不存在**：全仓 `LevelInit` 只有 2 处调用（`main.c:334`、`game_state.c:159`），**均为 `LevelInit(0)`**；无 `NextLevel` / 关卡完成判定 / `currentLevel++` |
| 素材（⛔ 一条不取） | 素材头 6 个 = **277,085 B / 1,430 行**，全部是 **ROM 提取**的图形/关卡数据；其中 3 个**从未被 include 的死头文件**占全仓 52.3%（`tiles_c.h` 183,756 + `intro_tiles.h` 22,232 + `intro_nametable.h` 5,161） |
| 自动玩 | 上游 attract mode 是**假自玩**（§4.5）：`phase = gIntroTimer % 180` 的五段脚本，无录像、无 AI、不会过关 ⇒ 能借的只有状态机形状 |
| 输入 | **仅键盘**（`gST->ConIn->ReadKeyStroke`）；NES 的 A/B/Start 原是键盘键，须重挂（§4.8） |
| 许可（如实留档） | ⚠️ 仓库内**无 LICENSE 文件**（README 自称 MIT）⇒ 法律口径仍是 all-rights-reserved；《Contra》为 Konami 商业 IP；精灵素材上游亦无许可。**所有者 2026-11-08 裁决：「完全可以继续移植后开源」⇒ 不阻塞**（原文见附录 C.12.1，⛔ 不再复议） |

### 2.1 为什么选它

- 它是本池所有候选里**「工程上最接近可直接机械翻译」的一档**（sim/view 分离、定点数、静态定长数组、256×240 + 整数缩放的呈现层形状）；
- 它是**用户指定的那一个**（2026-11-08 口径）；
- 池内候选清单与方案 A/B/C 的取舍过程**全部留在调研文档**。

### 2.2 ⛔ 关键实测：上游只有 1 关（这改变了「全部关口」的定义）

**证据链**（三条互相独立，全部指向同一结论）：

1. **数据表**：`level_data.h` 只有 3 张表 —— `gLevelHeaders[8][32]`（头）、`gLevel1Supertiles[1648]`、`gLevel1Screens[13][56]`。`gLevel2`…`gLevel8` 的地形表名**出现 0 次**。
2. **取址**：`level.c` 里 6 处地形访问（`:128 :177 :182 :245 :286 :290`）**全部**是 `gLevel1Supertiles` / `gLevel1Screens` 的字面量，**没有一处**用 `gLevelState.currentLevel` 去选表。
3. **推进**：`LevelInit(levelNum)` 只用 `levelNum` 去取 `gLevelHeaders[levelNum]` 的调色板与碰撞门限（`:213`），地形**仍然固定取第 1 关**。全仓 `LevelInit` 的两处调用都是 `LevelInit(0)`，且无任何关卡完成判定。

⇒ **上游的真实形态是「1 关（13 屏）+ 8 套调色板头 + 11 种敌人 + 6 种武器」的可玩原型**，第 2~8 关只有头、没有地形。

**这解释了 v1.1 的 N1**：`level.c` 只有 290 行，却能画完整个第 1 关 —— 因为它的职责是「查表 + 视差滚动 + 碰撞查询」，不是「装 8 关数据」。

### 2.3 「全 8 关」的实现口径（J2）

| 关 | 地形来源 | 工作量性质 | 说明 |
|---|---|---|---|
| **第 1 关（丛林）** | **移植**：`gLevel1Screens` 13 屏 + `gLevel1Supertiles` 103 个 super-tile | 机械翻译 | 唯一有上游数据的一关；⛔ 注意它是 **ROM 提取数据**，按 §3.4 只能进 gitignored 快照，⛔ 不得进 APK ⇒ **必须重绘为自建瓦片**（见下） |
| 第 2~8 关 | **自建** | 内容创作 | 上游只有调色板头可用（`gLevelHeaders[n]` 的 4 组 bg/sprite 调色板 + 碰撞门限 + 滚动类型）。地形、敌人布点、boss 全部自建 |

⚠️ **第 1 关的一个硬矛盾**：它的地形数据是 ROM 提取物，而 §3.4 禁止把 ROM 素材放进 APK ⇒ **第 1 关也不能直接搬字节**，只能「按同一份数据重绘为本项目自建瓦片」。落地方式：从快照读 `gLevel1Screens`（56 个 super-tile 索引/屏）作为**布局参考**，用 §4.7 的自绘瓦片集重新出图。**布局可对齐上游，像素不可。**

> 这是 J2 与既有素材红线的交叉点，必须写进 `UPSTREAM.md`：第 1 关是「布局移植 + 资产自建」，第 2~8 关是「全自建」。

**每关的最小内容清单**（自建 7 关时逐关交付，缺一项视为未完成）：

| 项 | 数量 | 备注 |
|---|---|---|
| 地形屏 | ≥ 8 屏/关（第 1 关 13 屏） | 16×16 super-tile 网格，`SCREEN_COLS 8 × SCREEN_ROWS 7 × 32 px` |
| 瓦片集 | 1 套/关（复用调色板则更少） | 自绘，RGB_565 图集 |
| 敌兵波次 | ≥ 6 波/关 | 从 11 种里选，位置自定 |
| 关底 boss | 1 个/关 | 第 1 关可参考 `BOMB_TURRET` + `PLATED_DOOR` 双件套形态 |
| 调色板 | 沿用 `gLevelHeaders[n]` | 这是上游唯一可直接复用的第 2~8 关数据 |

---

## 三、素材来源与许可

### 3.1 精灵素材：`nes-contra-us` 精灵库

UEFI_Contra 运行期从磁盘读 `sprites/sprite_%02x.spr`（**仓库内 `.spr` / `.png` / `tools/` 全为 0** ⇒ 素材管线必须自建）。精灵基座是上游 **`nes-contra-us`**（NES 反汇编项目，被 UEFI_Contra README 承认为素材来源）的 `docs/sprite_library/contra_sprites/`。

| 项 | 实测值 |
|---|---|
| 数量 | **232 张 PNG + 1 张 GIF**（该仓库共 483 个文件） |
| 单张体积 | **89-644 B**（8×8 ~ 16×16 级像素帧图；合计 **48.9 KB**） |
| 命名 | 与 `bank1.asm` 的 `sprite_ptr_tbl_0/1` 标签一致（`sprite_02..sprite_5f`、`_p2` 双人变体、`player_{1,2}_game_over`、`player_{1,2}_lives_medal`） |
| 性质 | 该仓 README 自述「extracted from the ROM for ease of viewing」、**不参与游戏代码运行**；另有 `probotector_sprites/`（168 PNG）属姊妹作，**不排它** |
| 归属 | 「版权归原始权利人所有」⇒ **不随 UEFI_Contra 的 MIT 声明转移授权**；采用前提是所有者裁决的「继续移植后开源」，出处与归属须按 NOTICE 如实登记（§3.4） |

### 3.2 素材管线（定死：构建期烘焙，⛔ `.spr` 不进管线）

| 环节 | 口径 |
|---|---|
| 输入 | `nes-contra-us` 的 232 PNG + 1 GIF（⛔ 不使用 `.spr`，那是 UEFI 侧 FAT 磁盘装载格式） |
| **补帧（J3）** | 先做覆盖度盘点 ⇒ 缺的帧按 §3.3 自绘 ⇒ 与取图结果合并后才烘焙 |
| 烘焙产物 | `app/src/main/assets/arcade/contra_atlas.png` + `contra_atlas.json`（帧名 → uv / 尺寸 / 锚点 / **来源标记**） |
| 图集约束 | 单张 ≤ **2048×2048**（GLES 2.0 的 `MAX_TEXTURE_SIZE` 下限，G6110 保守取此值）；放不下按「玩家+子弹 / 敌兵 / 道具与 UI / 自绘瓦片」拆 2~4 张，每张一次绑定、组内合批 |
| 像素格式 | 上传时按 **RGB_565 等效**量化（RGBA8888 在 G6110 上带宽不够；像素图无渐变，565 无色带可见） |
| 采样 | `GL_NEAREST` + 关闭 mipmap（像素图放大必须硬边） |
| 清单 | `contra_atlas_manifest.json`：帧名 / 来源（`nes-contra-us` 或 `本项目自绘`）/ 体积 / uv / 归属，与 §3.4 的 NOTICE 条目一一对应 |
| ⛔ 不做 | 关卡 / 背景 / HUD 一律**自绘**（不取 277 KB 素材头里的任何字节） |

### 3.3 缺帧自绘规则（J3 新增）

**触发条件**：某帧在 `nes-contra-us` 精灵库里不存在，或存在但尺寸/朝向/动作阶段不匹配（例如某敌兵的死亡序列、某武器的弹道中间帧、boss 的分段受击）。

**必须遵守的五条**：

| # | 规则 | 理由 |
|---|---|---|
| 1 | **用 NES 调色板取色**，⛔ 不自创颜色 | 自绘帧若用色偏离，接在图集里会一眼看出「补的」；调色板可直接从 `gLevelHeaders[n]` 的 bg/sprite 组与 `nes_palette.c` 取 |
| 2 | **尺寸对齐 8×8 网格**，锚点写进 manifest | 与既有帧共用同一套绘制路径；非 8 倍数会导致 k 倍缩放后出现半像素抖动 |
| 3 | **命名加 `gen_` 前缀**（如 `gen_boss_turret_hit2.png`） | 与取图帧在同一目录可区分，供 §4.7 的清单脚本自动标注来源 |
| 4 | **只补「动作用」，不补「美术升级」** | J3 授权的是「缺帧」不是「重做美术」；⛔ 不得借机替换既有帧 |
| 5 | **每帧进 manifest 的 `source` 字段**，并在 `NOTICE.md` 汇总数量 | 与「移植件 / 自建件」的区分（§0.1）保持一致；日后审计能一眼看出哪些是原创 |

**工作量**：至少涉及 boss 分段、敌兵死亡序列、6 种武器的弹道与拾取图标 ⇒ 预估 **3~5 人日**（已计入 §1.3）。

### 3.4 许可与归属登记

| 项 | 要求 |
|---|---|
| 快照 vendoring | `app/src/e54-upstream/uefi_contra/`（仓库树内、`main` 源集之外、**gitignored**，照 `app/src/lofi-upstream/` 的先例）+ `UPSTREAM.md`（repo / path / commit / date / license）+ 许可事实记录 |
| 目录命名 | `-upstream` 后缀，与 `lofi-upstream` / `globe-upstream` / `eco-upstream` 一致 |
| NOTICE | ① 精灵库按**素材**登记（出处 `nes-contra-us` 的 `docs/sprite_library/contra_sprites/`、归属「版权归原始权利人所有」）；② **本项目自绘件**单列一节（数量、用途），标注本项目 GPL v3；③ 代码侧保留上游版权声明（无 LICENSE，登记「README 自称 MIT、仓库内无 LICENSE 文件」这一事实 + 所有者裁决记录） |
| 移植文件版权头 | 每个从上游逻辑侧翻译的 `.kt` 顶部：「移植自 `MikeWuPing/UEFI_Contra`（无 LICENSE 文件，README 自称 MIT）；渲染层与第 2~8 关内容为本项目独立重写」 |
| ⛔ 不许做的 | 不改写上游许可结论、不删除版权事实、不把 ROM 素材头写进快照以外的任何位置、不在文档里把「裁决豁免」写成「许可合规」、不把自建件标成移植件 |

---

## 四、实施分解（一次性交付，W1~W10 按依赖排序）

> ⛔ **W1~W10 不是阶段门**（J1：不分阶段）。它们是**依赖顺序**：W1 不落地就无法编译，W3 不落地 W4~W6 无对象。全部完成才算交付（§4.10）。

### 4.1 工作包总表

| # | 工作包 | 产出 | 人日 |
|---|---|---|---|
| **W1** | **接线**（枚举 / 双语 / 开关 / 两份工厂 / 门禁同步） | 可编译的空效果 | 2.5-3.5 |
| **W2** | **上游快照与留档**（`e54-upstream` + `.gitignore` + `UPSTREAM.md` + 探针 stdout） | 可复核的移植依据 | 1.0-1.5 |
| **W3** | **sim 层移植**（逻辑侧 7 个 `.c` / 2,252 行 → Kotlin） | 纯 Kotlin sim | 5-7 |
| **W4** | **第 1 关**：布局对齐上游 + 瓦片**自绘** | 1 关可跑 | 2-3 |
| **W5** | **第 2~8 关自建**（地形 / 敌人布点 / boss / 调色板复用） | 7 关内容 | 14-21 |
| **W6** | **`ContraBot`**（跨 8 关自动玩 + 卡住检测 + 死亡重试） | 自动演示 | 3-4.5 |
| **W7** | **GLES 渲染**（320×240 等效 + 整数倍 k + 图集合批 + 家具声明） | 画面 | 3.5-4.5 |
| **W8** | **素材管线 + 缺帧自绘**（232 PNG 盘点 → 补帧 → 图集 + manifest） | 图集 + 清单 | 4-7.5 |
| **W9** | **输入通路**（main → GL 环形队列 + 确认键打断） | 可打断 | 1-1.5 |
| **W10** | **单测 / 门禁 / 文档**（sim、bot、控制器、G1~G12、§10 变更条目） | 全绿 | 3-4 |
| | **合计** | | **39-58**（= 上表十项区间下限之和 39、上限之和 58） |

> ⚠️ 本表与 §1.3 是**同一套口径**：逐项相加得 39-58，与 §1.3 的增量推导一致。**这是估计，不是承诺**（§1.3 尾注）。

### 4.2 新增一个主题会触发的既有门禁（G1~G12，实测逐条）

| # | 位置 | 现状 | 加 `ARCADE_GAMES` 后必做 |
|---|---|---|---|
| G1 | `VisualizerThemeTest.kt:77` | `assertEquals(28, entries.size)` | `29` |
| G2 | 同上 `:85-86` | `assertEquals(26 + lofiInList + highwayInList, selectable.size)` | 基线 `26 → 27`（两行） |
| G3 | 同上 `:118-119` | `25 / 26 + lofiInList + highwayInList` | `26 / 27` |
| G4 | 同上 `:128` | `assertEquals(28, labels.distinct().size)` | `29` |
| G5 | 同上 `:136` | `assertEquals(28, names.distinct().size)` | `29` |
| G6 | `VisualizerThemeStringsGateTest` | 枚举 key ⊆ 中英两份 `strings.xml`；`负向G1N2` 断言抽取条数 == `entries.size` | 必须补 W1b 的两条字符串 |
| G7 | `ParticleBudgetGateTest.kt:85` | `assertEquals(entries.size, mapping.size)` | 两份工厂都要有分支（W1d） |
| **G7b** | 同上 `budgetConsumingClasses()` | 只扫 `visualizer/renderers/` 与 `visualizer/photo/` ⇒ **`visualizer/arcade/` 不在扫描范围** | `needsParticleBudget = false` ⇒ ⛔ arcade 包内**不得**读 `ctx.quality.maxParticles`。若日后要读，必须把该目录纳入扫描并改标注 `true` |
| G8 | `EffectFurnitureGateTest` ③ | 遍历全部 `entries`，除 `VINTAGE_TV` / `LYRICS_STAGE` 外必须返回 `EffectFurniture.DEFAULT` | W7 **不声明**家具 ⇒ 零改动。若要让叠加层为游戏让位，须扩豁免名单 + 登记理由 |
| G9 | `VisualizerStageThemeAgnosticGateTest` | 舞台只允许 `PHOTO_WALL` / `LYRICS_STAGE` / `QIANLI_SCROLL` | 只要不在舞台写 `ARCADE_GAMES` 就无需改动（W9 采用路径 A） |
| G10 | `docs/visualizer-effects-list.md` | 由 `gen_effects_doc.py` 生成 | ⚠️ **先修模板再跑**：脚本头部硬编码 `v2.39.10` / `2026-11-02`，直接跑会把现文档降级 |
| G11 | `docs/technical-overview.md` §10 | 逐版本变更记录 | 补一条：占位编号 55 + 双变体门控 + 自建 7 关的范围说明 |
| G12 | `AppSettings.kt` `publishable()` | 现有两条 `filterNot` | 加第三条（W1c） |

> **教训来源**：`docs/technical-overview.md:13260` 记过同类事故 ——「删枚举会特影响至少三类东西……分散在不同测试包里的**效果总数硬计数**（本轮共 8 处，分布在 5 个文件）」。

### 4.3 `ArcadeGame` 接口契约（为后续游戏留口，不改状态机）

```kotlin
interface ArcadeGame {
    val id: String                  // "contra"
    val title: String               // "魂斗罗"
    val logicalWidth: Int           // 256（内部像素坐标）
    val logicalHeight: Int          // 240
    /** 固定步推进；input 为**复用单例**，调用方每步覆写字段，⛔ 不得跨步持有 */
    fun step(input: GameInput, snapshot: GameSnapshot)
    fun reset(seed: Long)
}
```

| 契约 | 要求 | 理由 |
|---|---|---|
| `GameInput` / `GameSnapshot` | **复用单例、原地写字段**，⛔ 不是每帧 `data class` 新建 | `VisualizerRenderer.kt:28`：「`frame` 为复用单例，不得跨帧持有」；`VisualizerRandom` 立了「绘制循环零分配」原则 |
| `step` 无返回值、不改容器 | 只写 `snapshot` | 便于单测断言「同帧输入 ⇒ 同帧状态」 |
| 多游戏扩展 | `ArcadeGameManager` 持 `Map<String, ArcadeGame>`，本次只注册 1 个 | C7：合集不另立效果；多游戏切换后续接入不改状态机 |

### 4.4 sim 层移植规则（W3）

| 规则 | 口径 |
|---|---|
| 忠实度 | 逐函数对照快照翻译，⛔ 不「顺手优化」算法；保留上游的魔数并注明出处行号 |
| 数值 | 8.8 定点一律 `Int`，`>>8` / `<<8` 一一对应；⛔ 不引入 `Float`（源里只有 `enemy.c` 一处浮点，须改造为定点并在注释里写明偏离） |
| 结构 | 静态定长数组 → `IntArray` / `ShortArray`；⛔ 不用 `List<Enemy>` 之类每帧分配的结构 |
| 框架耦合 | sim 层**不 import 任何 Android / GL**（照上游逻辑侧「零框架头」的形状） |
| 素材头 | 6 个头文件**一条不译**；第 1 关布局改由 §4.7 的自绘瓦片表承载 |
| 版权头 | 每个文件顶部按 §3.4 标注「移植自 …；渲染层与第 2~8 关内容为本项目独立重写」 |

### 4.5 `ContraBot`（W6，J1 的硬条件）

**状态机形状**（借自上游三常量）：

| 状态 | 进入条件 | 行为 |
|---|---|---|
| `TITLE` | 启动 / demo 结束 | 空闲 **360 帧**（上游 `gIntroTimer > 360`）后进 `DEMO` |
| `DEMO` | 360 帧到期 | 自动推进；`gIntroTimer > 3840`（约 64 秒）或进度自然回环 ⇒ 回 `TITLE` |
| `INTERRUPTED` | 确认键 | 立即回 `TITLE`（对齐上游 `INPUT_START` 语义） |

**demo 行为自写（上游是 `phase = gIntroTimer % 180` 的假自玩，无录像、无 AI、不会过关）**：

| 能力 | 做法 | 为什么必需（J1「要能够自动玩」） |
|---|---|---|
| 向右推进 | 恒速右移 + 遇障跳跃 | 上游 demo 只会「向右走 + 按相位跳射」，撞墙即卡住 |
| **地形感知** | 读 `GetBgCollision` 等价物（碰撞码 `0=空 / 1=floor / 2=water / 3=solid`），前方 1~2 格 solid ⇒ 起跳；water ⇒ 起跳 | 8 关地形是自建，纯定时跳必然卡死 |
| 卡住检测 | 连续 N 帧（建议 45）位移 < ε ⇒ 强制跳跃 / 反向脱离 / 超时重置本关 | 没有这条，「自动玩」在第 3 关就会静止 |
| 射击 | 按相位 + 前方有敌时补射 | 清掉 `PLATED_DOOR` 类目标需要持续火力 |
| 死亡重试 | 生命耗尽 ⇒ 重置本关（不重置全局进度） | 保证循环不断 |
| **跨 8 关** | 每关达成「抵达右端 / boss 死亡」⇒ 进下一关；第 8 关后回第 1 关 | ⛔ 上游**没有**过关判定（§2.2 第 3 条），这套推进必须自建 |

> ⚠️ bot 的**确定性**是验收前提：固定步 + 可注入种子（§4.9）⇒ 同一 seed 同一帧序列。⛔ 不得用 `System.currentTimeMillis()` 做决策。

### 4.6 渲染（W7）

| 主题 | 口径 |
|---|---|
| 绘制方式 | 图元（矩形/线/粒子）走 `glDrawArrays` + 顶点颜色；**精灵**走 `GL_TEXTURE_2D` + 图集合批 |
| 纹理 | 图集 ≤2048×2048、**RGB_565 等效**、`GL_NEAREST`、无 mipmap |
| `isViewBased` | `true`（与 E52 `HighwayRenderer` 同模式） |
| **`renderFps()`** | **必须覆盖**。舞台 `FpsBadge` 量的是 Compose 的 Choreographer 回调率，而 View 型渲染器有独立渲染循环 ⇒ 不覆盖会出现「显示 59 fps、实际 6.5 fps」（`VisualizerRenderer.kt:45-58`，E52 已实测） |
| 家具 | `furniture` 保持 `EffectFurniture.DEFAULT`（G8） |
| EGL | `setEGLContextClientVersion(3)`，失败退 2（照 `HighwayGLSurfaceView:155` 的 `pickClientVersion`） |

#### 4.6.1 缩放与宽高比：「整数倍」与「4:3」在 256×240 上不能同时成立

- 逻辑网格 256×240 ⇒ 宽高比 \(256/240 = 16/15 \approx 1.067\)
- 目标 4:3 ⇒ \(4/3 \approx 1.333\)
- 补齐系数 \(= \dfrac{4/3}{16/15} = \dfrac{5}{4} = 1.25\)

**采纳方案**：把 ×1.25 放进**顶点变换**，不放进缩放倍数。

- 逻辑坐标保持 **256×240**（与 sim 层、上游 `player.c` 的像素坐标一一对应）；
- 顶点 \(x \leftarrow 1.25x\) ⇒ 等效画幅 **320×240**，而 \(320/240 = 4/3\) **严格成立**；
- 纵向 \(k = \left\lfloor \dfrac{\text{屏高}_{\text{px}}}{240} \right\rfloor\)，横向同倍 \(320k\)。

| 屏 | k | 画幅 | 左右留边 |
|---|---|---|---|
| 1080p | 4 | 1280×960 | 各 320 px |
| 4K | 9 | 2880×2160 | 各 480 px |

> ⛔ 不沿用上游 `MAX_SCALE 4`（GOP 1024×768 的残留；4K 上照抄 4，游戏只占屏宽 1/3）。
> 备选 B（不做 1.25、按 1.067 显示）：像素是方的但与 4:3 不符，**仅在所有者观感确认后采用**。

### 4.7 图集与自绘瓦片（W8）

| 项 | 口径 |
|---|---|
| 精灵帧来源 | 232 PNG 盘点 ⇒ **缺帧按 §3.3 自绘** ⇒ 合并烘焙 |
| 瓦片集 | **每关 1 套自绘**（第 1 关按 `gLevel1Screens` 的 56 格/屏布局重绘；第 2~8 关全自建） |
| 图集拆分 | ① 玩家+子弹 ② 敌兵 ③ 道具/UI ④ 瓦片（每关）⇒ 每张一次绑定 |
| manifest | 逐帧记 `source`（`nes-contra-us` / `gen_` / `tile_`）⇒ 与 NOTICE 对账 |
| ⛔ | 不把 ROM 素材头的字节写进任何会打包的位置（第 1 关只借鉴**布局**，不搬像素） |

### 4.8 输入（W9，J4：返回键不改）

| 项 | 口径 |
|---|---|
| 承诺范围 | **只承诺「确认键打断 demo + 其余键不崩」**；⛔ 不承诺可玩（C8 要求零操作） |
| 确认键 | `Key.DirectionCenter` / `Key.Enter` ⇒ 打断回 `TITLE` |
| 返回键 | **⛔ 不改语义**。舞台没有 `BackHandler` ⇒ Back 冒泡是「退出可视化」；若改成「打断」用户要按两次才能退出（可见 UX 回归） |
| 方向键 | 舞台已占用（←/→ 切主题、↑/↓ 切子模式，`VisualizerStage.kt:515-550`）⇒ 本次不接 |
| 跨线程 | main（键回调）→ **有界环形队列**（容量 32，满丢最旧）→ GL 线程每 tick 取出清空 |

#### 4.8.1 为什么 W9 是「先建通路」而不是「键位映射」

三处硬事实：① `VisualizerRenderer.kt` **无任何按键钩子**（无 `onKey`/`KeyEvent`）；② `HighwayGLSurfaceView.kt:162-163` 刻意 `isFocusable = false`；③ `VisualizerStage.kt:512` 的 `onPreviewKeyEvent` 是**预览阶段**消费，先于子 View。

| 路径 | 做法 | 代价 |
|---|---|---|
| **A（本次采用）** | 不动舞台：确认键不被舞台 `when` 消费（只匹配四个方向键 + `else false`）⇒ 自然冒泡 | 0 改动 |
| B（后续） | 舞台对方向键返回 `false` + GLSurfaceView `isFocusable = true` | 必须扩 G9 白名单 + 改**仓库与覆盖层两份** `VisualizerStage.kt` + 跑漂移检查 |

### 4.9 时序、线程与分配纪律

| 项 | 口径 | 理由 |
|---|---|---|
| sim tick | **GL 线程**（`onDrawFrame`）固定步累加器（60 步/秒，单帧最多追 3 步后丢余量） | 同线程无锁；固定步 ⇒ bot 可复现 |
| 输入 | main → 环形队列（§4.8） | 键频远低于帧率 |
| 快照 | 复用单例、原地写字段 | §4.3 |
| 随机源 | **复用 `VisualizerRandom`**（LCG，零分配，已有 `VisualizerRandomTest`） | ⛔ 不新建 `ArcadeRandom`/xoshiro：bot 只需「可复现」，LCG 足够。若 demo 轨迹周期性肉眼可见，再单独立项并写证据 |
| 时钟 | `RenderContext.nowMs` 或 `System.nanoTime()` 差值 | ⛔ 不用随机源当时钟；sim 内不读 `currentTimeMillis()` |
| 切走/切回 | `onViewDetached` ⇒ **暂停 + 保留**；再 attach 从暂停处继续 | 交叉淡入与短暂切主题不该丢进度；⛔ 不重置（会让 3840 帧 demo 永远跑不完，像卡死） |

### 4.10 完成定义（DoD，J1）

全部满足才算交付：

| # | 条件 | 验证 |
|---|---|---|
| D1 | 枚举接线完成，`ARCADE_GAMES` 在三档画质下都可选 | `VisualQuality.supports` + 手测切档 |
| D2 | **8 关全部可跑通**（第 1 关布局对齐上游，第 2~8 关自建） | bot 连续跑完 8 关并回到第 1 关 |
| D3 | **自动演示无需任何按键即可持续运行 ≥ 30 分钟不卡死** | 真机 + 单测（长跑 108000 步） |
| D4 | 确认键可打断回标题；其余键不崩 | Robolectric + 真机 |
| D5 | 帧率达标（读 `renderFps()`，⛔ 不是徽章数字） | 真机，由所有者读数 |
| D6 | G1~G12 全绿 + 全量单测绿（`--rerun-tasks`） | 见 §6.2 |
| D7 | 覆盖层 0 漂移；`kotlin_comment_balance.py` 0 违规 | 见 §5.3 |
| D8 | `UPSTREAM.md` / `NOTICE.md` 如实区分「移植件 / 自建件 / 自绘帧」 | 人工核对 |

> ⛔ **D3 是不可豁免项**（J1 原话「要能够自动玩」）。若 bot 只能跑完前 2~3 关，视为未达 DoD，⛔ 不得以「画面已能显示」交付。

---

## 五、分支与分发纪律

### 5.1 为什么不能「只进本地分支」

链条：`VisualizerRendererFactory.kt` 在 `E51_TRACKED` 里 ⇒ `main` 上编译的是**覆盖层那份**（由 `sync_private_overlay.py` 从 `feat/e51-private` 拷来，含 `ARCADE_GAMES` 分支）；而 `AppSettings.kt` **不在** `E51_TRACKED` 里 ⇒ 若枚举只写在私有分支，`main` 上编译的是**仓库那份**（没有该常量）⇒ `when (theme)` 引用不存在的常量 ⇒ **编译失败**。

**既有先例**：`main` 里 `LOFI_CITY` / `HIGHWAY` 是有的（带 `BuildConfig` 开关，CI 缺省 false），`main` 的工厂里它们回落 `CircularRingRenderer()`，只有覆盖层那份指向真渲染器。E54 完全照此。

### 5.2 落点分工

| 内容 | 落点 | 进公开仓库 |
|---|---|---|
| 枚举项（`ARCADE_GAMES` + 显示名「街机合集」+ `"55"` + `Tier.ADV`） | `main` | ✅ |
| 双语字符串、`arcade.enabled` 开关、`publishable()` 过滤 | `main` | ✅ |
| 工厂**回落**分支 `-> CircularRingRenderer()` | `main` | ✅ |
| 计数门禁 G1~G5 更新 | `main` | ✅ |
| 工厂**真实**分支、`visualizer/arcade/**`、图集、sim、bot、8 关内容 | `feat/e51-private` + 覆盖层 | ⛔ |
| `app/src/e54-upstream/**`（上游快照） | 本机 gitignored | ⛔ |

> ⛔ 提交 `main` 前硬检查：该提交里**不得**出现任何上游 C 代码、`.spr`/`.png` 素材、`UEFI_Contra` 字样、「魂斗罗」字样（显示名用中性词「街机合集」）。仓库是 **PUBLIC**，漏检即不可撤回（`git push` 前须所有者确认）。

> ⭐⭐ **2026-11-10 第三次所有者裁决 —— 上表已过期，落点分工改判如下**
> （原话：「魂斗罗应该是公开的，在 main 分支下的」；落地版本 **v2.39.18 / versionCode 203**）：
>
> | 内容 | 落点（现行） | 进公开仓库 |
> |---|---|---|
> | 枚举项 + 双语字符串 + `arcade.enabled` 开关 + `publishable()` 过滤 | `main` | ✅ |
> | 工厂**真实**分支 `-> ArcadeRenderer(context)` | `main` | ✅ |
> | `visualizer/arcade/**`（14 个源文件）+ 6 个测试 | `main` | ✅ |
> | `app/src/main/assets/e54_arcade/contra_atlas.png`（489 帧烘焙图集） | `main` | ✅ |
> | `docs/e54-sprite-attribution.md`（NOTICE）+ `scripts/e54_pack_sprites.py` | `main` | ✅ |
> | `app/src/e54-upstream/**`（**上游 ROM 提取原件**） | 本机 gitignored（两分支皆是） | ⛔ |
> | E51 / E52 的实现与资产 | `feat/e51-private` + 覆盖层 | ⛔（**不受本次裁决影响**） |
>
> ⇒ `arcade.enabled` **缺省 true**（与 E51/E52 方向相反）⇒ 公开 APK **默认带**「街机合集」，
> 公开版可选效果 **26 → 27 套**（= 29 − E51 − E52）。
> ⛔ 上面那段「不得出现 `.png` 素材 / `UEFI_Contra` 字样」的硬检查**仅对上游原件与上游标识成立**：
> 烘焙图集与本项目自写的 sim / bot / 关卡内容**已获所有者裁定可以公开**；
> 但 `app/src/e54-upstream/` 里的 ROM 提取原件**仍然不入仓**。

### 5.3 覆盖层三处登记 + `.gitignore`

| 文件 | 要加的行 |
|---|---|
| `scripts/sync_private_overlay.py` → `SHADOWED_JAVA` | `('visualizer/arcade', 'dir')` |
| `scripts/local-e51-overlay.gradle` → `E51_TRACKED` | `'com/nasmusic/tv/visualizer/arcade/**'` |
| `.gitignore` | `app/src/e54-upstream/` ✅ **已加（2026-11-09）**，见该文件第 125-133 行的成组注释（125-132 注释、133 规则）；`git check-ignore` 退出码 0，目录已变为 `!!`（ignored），不再出现在普通 `git status` |

> 两个脚本头部都有明示「改 `E51_TRACKED` 必须同步改 `SHADOWED_JAVA`」。改完跑 `python scripts/sync_private_overlay.py` → `--check` 必须 **0 漂移**（`PrivateOverlayDriftGateTest` 判红）。
>
> ⭐ **2026-11-10 所有者要求「init 脚本放项目目录内」后**：挂载正文已从
> `~/.gradle/init.d/10-nasmusic-e51-local.gradle` 迁到**仓库内**的 `scripts/local-e51-overlay.gradle`
> （本机那枚 init 壳只剩「把正文 apply 进来」的职责）⇒ 上表第二行指向的是**新位置**；
> 两处清单的一致性改由 `OverlaySourceListGateTest` 守住（此前只靠注释里的口头约定）。

> ⭐ **2026-11-10 第三次裁决后：上表前两行已作废（arcade 不再走覆盖层）**
> 「街机合集」的实现与图集已入 `main` ⇒ 不再是「隐藏分支物化」⇒
> - `scripts/sync_private_overlay.py` 的 `SHADOWED_JAVA` **删掉** `('visualizer/arcade', 'dir')`，
>   `SHADOWED_ASSETS` **删掉** `('e54_arcade', 'dir')`；
> - `scripts/local-e51-overlay.gradle` 的 `E51_TRACKED` **删掉** `'com/nasmusic/tv/visualizer/arcade/**'`。
>
> ⚠️ 删掉的理由不是「省事」，而是**避免主源被影子源盖住**：挂载正文在「仓库里没有隐藏分支源码」（= 在 `main` 上）
> 时会**排除仓库里那几份同名文件**、改用覆盖层那份 ⇒ `main` 刚入仓的 arcade 源码会被旧副本顶掉。
> ⛔ 两处仍要**成对改**（`E51_TRACKED` ↔ `SHADOWED_JAVA`），否则漂移门禁判红。
> 第三行（`.gitignore` 的 `app/src/e54-upstream/`）**仍然有效** —— 上游原件依旧不入仓。

### 5.4 其余纪律

| 项 | 纪律 |
|---|---|
| 推送 | `feat/e51-private` 无 upstream、含不可再分发源码 ⇒ **永不 push / tag / release** |
| 构建前 | 跑 `python scripts/kotlin_comment_balance.py`（可嵌套块注释语法闸） |
| 本地构建 | `--no-daemon` + `-Pkotlin.compiler.execution.strategy=in-process` |
| 电视 | ⛔ 不主动 `adb connect` / 启动应用；安装类动作只在所有者明确要求时做，且**不启动** |

---

## 六、测试与验收

### 6.1 分层手段

| 层 | 手段 | 验收 |
|---|---|---|
| sim 层单测 | 纯 Kotlin、无 Android / GL | 翻译对账（对照快照逐函数）；定点数值断言；**零浮点**（源里只有 `enemy.c` 一处，改造处须注明） |
| bot 单测 | 固定步 + 可注入种子（`VisualizerRandom`） | 同帧输入 ⇒ 同帧状态；**长跑 108000 步（=30 分钟 @60fps）不卡死**；8 关全部推进到位 |
| 关卡单测 | 逐关地形装载 + 碰撞查询 | 每关地形非空；出生点可站；右端可抵达（⛔ 空表/断头关会在这里暴露） |
| 控制器/输入 | Robolectric（JUnit4） | 标题 → 打断 → demo → 回标题；确认键命中；切走/切回 = 暂停保留 |
| 真机 | 创维电视（API 22 / G6110） | 帧率（读 `renderFps()`）+ 无卡死 + 确认键打断；**画面观感由所有者本人确认** |

> ⚠️ 真机口径：⛔ 不主动 `adb connect` / 启动应用（电视在客厅、是共享的）。安装类动作只在所有者明确要求时做且**不启动**。画面/帧率/交互证据 ⇒ 请所有者本人操作。
> ⚠️ 帧率必须读 **`renderFps()`**，⛔ 不读 `FpsBadge` 数字（那是 Choreographer 回调率，E52 实测「徽章 52~59 而真实 6.5 fps」）。

### 6.2 回归准入（⛔ 一条都不能跳）

| # | 命令 / 测试 | 通过判据 |
|---|---|---|
| T1 | `VisualizerThemeTest` | G1~G5 五处计数改后全绿 |
| T2 | `VisualizerThemeStringsGateTest` | 中英双语子集 + 抽取条数对撞 |
| T3 | `ParticleBudgetGateTest` | 工厂映射覆盖全枚举；标注与源码读取一致（⚠️ G7b 的 arcade 盲区见 §4.2） |
| T4 | `EffectFurnitureGateTest` | ③ 遍历全枚举 ⇒ `ARCADE_GAMES` 返回 `DEFAULT` |
| T5 | `VisualizerStageThemeAgnosticGateTest` | 舞台里不出现 `ARCADE_GAMES`（§4.8 路径 A） |
| T6 | `UpstreamOriginalsHygieneTest` | 扩扩展名（`.c/.h/.s/.asm`）后仍需绿（W2） |
| T7 | `PrivateOverlayDriftGateTest` | `sync_private_overlay.py --check` = 0 漂移（§5.3） |
| T8 | `scripts/kotlin_comment_balance.py` | 0 违规 |
| T9 | `./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks` | 全量绿（⛔ 增量 up-to-date 不算验数） |
| **T10** | **本效果新增单测**（sim / bot / 关卡 / 输入） | 见 §6.1；**含 8 关全通关的 bot 长跑** |

> ⚠️ T9 必须带 `--rerun-tasks`：Gradle 增量 up-to-date 时**不重执行**测试，看到绿是缓存。本机全量约 4~6 min。

### 6.3 逐关验收清单（W4 / W5 的交付判据）

每关都必须独立通过；⛔ 不允许「前 3 关做完就宣称 8 关完成」。

| 检查 | 判据 |
|---|---|
| 地形装载 | `level(n)` 的地形表非空，屏数 ≥8（第 1 关 =13） |
| 可通行性 | bot 能从左端推进到右端（或 boss 房），不依赖「卡住重置」过关 |
| 碰撞 | 地面/水面/实心三类碰撞码在该关都至少命中一次 |
| 敌人 | ≥6 波；每波能正常生成、移动、被击杀 |
| boss | 存在且可被击杀；击杀后触发过关 |
| 视觉 | 瓦片集无缺图（manifest 逐项命中）；无紫色/纯白占位块 |
| 调色板 | 沿用 `gLevelHeaders[n]`，与相邻关有可见区分 |

### 6.4 验收缺口（如实标注，不冒充已通过）

| 项 | 状态 | 说明 |
|---|---|---|
| 画面「像不像魂斗罗」 | ⛔ 代理无法验收 | 需所有者本人看；本项目只保证「能画、不崩、帧率达标、8 关可达」 |
| 第 2~8 关的「还原度」 | ⛔ 无基准 | 上游没有这 7 关的数据 ⇒ 不存在可对照的「正确」；只能验收「可玩通」 |
| 4K 电视 | ⛔ 未验 | §4.6.1 的 \(k=9\) 只是算术成立，G6110 基准设备是 1080p |
| 30 分钟长跑 | ⛔ 需真机 | 单测可覆盖 108000 步的逻辑，但真机内存/温升只有实机能验 |

---

## 七、排期（一次性交付）

```
W1  接线（枚举/双语/开关/两份工厂/G1~G5）      ██░░░░░░░░░░░░░░░░░░  2.5-3.5
W2  上游快照 + 留档 + 卫生护栏                  █░░░░░░░░░░░░░░░░░░░  1.0-1.5
W3  sim 层移植（2,252 行 C → Kotlin）           ███████░░░░░░░░░░░░░  5-7
W4  第 1 关（布局对齐 + 瓦片自绘）              ███░░░░░░░░░░░░░░░░░  2-3
W5  第 2~8 关自建（地形/敌人/boss）             █████████████████░░░  14-21
W6  ContraBot（跨 8 关自动玩）                  ████░░░░░░░░░░░░░░░░  3-4.5
W7  GLES 渲染（320×240 + 整数倍 k + 合批）      ████░░░░░░░░░░░░░░░░  3.5-4.5
W8  素材管线 + 缺帧自绘                         █████░░░░░░░░░░░░░░░  4-7.5
W9  输入通路（环形队列 + 确认键打断）           █░░░░░░░░░░░░░░░░░░░  1-1.5
W10 单测 / 门禁 / 文档                          ████░░░░░░░░░░░░░░░░  3-4
──────────────────────────────────────────────────────────────────
合计                                                       39-58 人日
```

### 7.1 与 v1.1（18-27）的差异来源

| 来源 | 增量 | 说明 |
|---|---|---|
| **第 2~8 关自建**（W5） | +14~21 | 上游只有 1 关地形（§2.2）⇒ J2 的「全部关口」= 7 关内容创作 |
| **第 1 关瓦片自绘**（W4） | +2~3 | 地形数据是 ROM 提取物，⛔ 不能搬字节 ⇒ 按布局重绘 |
| **缺帧自绘**（W8，J3） | +3~5 | 上游 `player.c:39-41` 只定义 3 个动作帧（站立/蹲/瞄上）；11 敌种无死亡序列；6 武器各 1 代表帧 ⇒ 补 boss 分段 / 敌兵死亡 / 武器弹道 |
| **bot 跨 8 关导航**（W6） | +1 | 原「向右推进 + 按相位跳射」在自建地形上会卡死 |
| W1 / W2 / W9 / W10 相对 v1.1 的上浮 | +1 | 接线成本显式化 + 文档与门禁 |
| **合计** | **18-27 → 39-58** | |

> ⚠️ 约 90% 的增量来自 **J2（全部关口）** 与 **J3（自绘补帧）** 两条裁决（J2=W5+W4=16~24、J3=W8=3~5，合计 19~29，占增量下界 21 的 90%、上界 31 的 94%），不是原估算失准。
> ⛔ 不要把「39-58」写进对外承诺：最大不确定性是第 2~8 关的**内容密度**（每关几个 boss、几波敌人）—— 那是**自建决策**，不是上游给定的事实。若把每关压到「≥6 波 + 1 boss」的下限，可落在区间下界。

### 7.2 v1.1 的 Phase 划法已作废

J1 明确「不分阶段」⇒ v1.1 的「Phase 0 / Phase 1 排期成立、Phase 2~6 暂缓」写法作废。W1~W10 是**依赖顺序**，不是阶段门：任何一项未完成都算未交付（§4.10）。

---

## 八、相关脚本与再生成纪律

| 脚本 | 用途 | 何时跑 |
|---|---|---|
| `scripts/e54_uefi_contra_probe.py` | 体量 / 分离度 / 素材头度量取证（`VERDICT = ✅ 采用`）。输出 **stdout**（⛔ 不写 JSON）；`--offline` 复用 `logs_temp/uefi_contra_probe/` 镜像（⚠️ 镜像当前在 `app/src/e54-upstream/uefi_contra/`，**尚未**移入该路径，见 §0.4） | 数字变更 / 上游更新后 |
| `scripts/e54_body_clean_check.py` | **只扫调研文档正文**（`DOC` 在 `:38`）：弃用候选禁词闸 + 附录阳性对照 | 改调研正文后必须复跑，0 违规才成立 |
| **`scripts/gen_effects_doc.py`** | `docs/visualizer-effects-list.md` 的**唯一再生成入口**（从枚举 + `LEGACY_MAP` 生成，按 `no` 升序）。⚠️ **模板已过期**：硬编码 `v2.39.10（versionCode 195）`、`2026-11-02`，ADV 说明也停在「只有 E14 需粒子预算」⇒ 直接跑会把现文档**降级**。**先修模板再跑** | 加/删枚举后 |
| `scripts/check_docs_links.py` | 全仓 `docs/…` 死链检查（OK / PRIVATE / MISSING） | 本文档大改后 |
| `scripts/kotlin_comment_balance.py` | Kotlin 可嵌套块注释语法闸 | 每次改 Kotlin 后、跑 Gradle 前 |
| `scripts/sync_private_overlay.py --check` | 私有分支 ↔ 覆盖层漂移检查 | 改 E51/E52/**E54** 相关文件后（§5.3 起必须含 arcade） |

> **分工**：调研文档是「选型与调研证据的唯一来源」（含附录 A/B/C 全量留档）；本文档是「实施与验收口径」。两处数字不一致 ⇒ 以调研文档 §5.8 / 附录 C.12.1 的**脚本 stdout** 为准（⛔ 不是某个 JSON 快照）并回改本文档。

---

## 附录 A：硬事实索引（可逐条复核）

| 断言 | 复核方式 | 实测结果 |
|---|---|---|
| 枚举 28 项，占 `05,13~17,25,29,30,33,35,37~44,46~54` | 解析 `AppSettings.kt` 的 `enum class VisualizerTheme` | 28 项无重号；下一空号 `55` |
| `VisualizerThemeTest` 有 5 处硬计数 | `grep -n "assertEquals(28\|26 + lofiInList\|25 + lofiInList"` | `:77 :85 :86 :118 :119 :128 :136` |
| 工厂映射门禁要求条数相等 | `ParticleBudgetGateTest.kt:85` | `assertEquals(entries.size, mapping.size)` |
| 粒子预算扫描不含 arcade | 同文件 `for (sub in listOf("renderers","photo"))` | 只有两个目录 ⇒ G7b 盲区成立 |
| `VisualizerRenderer` 无按键钩子 | `grep -n "onKey\|KeyEvent" VisualizerRenderer.kt` | 0 命中 |
| View 型必须覆盖 `renderFps()` | `VisualizerRenderer.kt:45-58` + `HighwayRenderer.kt:45` | 契约明确 |
| `HighwayGLSurfaceView` 放弃焦点 | `HighwayGLSurfaceView.kt:162-163` | `isFocusable = false` |
| 舞台占用四个方向键 | `VisualizerStage.kt:515-550` | ←/→ 切主题；↑/↓ 切子模式 |
| 舞台无 `BackHandler` | `grep -n "BackHandler" VisualizerStage.kt` | 0 命中 |
| `AppSettings.kt` 不在覆盖层清单 | `sync_private_overlay.py` 的 `SHADOWED_JAVA`（6 项） | 不含 `data/model/AppSettings.kt` ⇒ §5.1 编译失败链成立 |
| `main` 的 E51/E52 是「枚举 + 回落」 | `git show main:.../VisualizerRendererFactory.kt` | `:104/:109` 均 `CircularRingRenderer()` |
| 卫生门禁只扫 `.js/.mjs` | `UpstreamOriginalsHygieneTest.kt:41` | `setOf("js","mjs")` |
| 探针不写 JSON | `grep -n "json.dump" scripts/e54_uefi_contra_probe.py` | 0 命中 |
| `e54_upstream_verify.json` 是池快照 | 读该文件 | `rows` 122 条，字段无行数/体积 |
| `VisualizerRandom` 是 LCG | `VisualizerRandom.kt:26` | `state * 1664525u + 1013904223u` |
| **关卡头 8 个、地形只有 1 套** | `grep -n "gLevelHeaders\[8\]\|gLevel1Supertiles\|gLevel1Screens" src/level_data.h` | 8 头 / 1 套地形；`gLevel2..8` 命中 **0** |
| **`level.c` 地形取址硬编码第 1 关** | `grep -cE "gLevel1(Screens\|Supertiles)" src/level.c`（地形取址）；`gLevel1` 全文命中 8 处，另 2 处是 `gLevel1PaletteData`（调色板，非地形） | **6 处地形**（`:128 :177 :182 :245 :286 :290`），无 `currentLevel` 选表 |
| **无过关推进** | `grep -n "LevelInit" src/*.c` | 2 处调用，**均 `LevelInit(0)`**；无 `NextLevel` |
| **第 1 关 13 屏** | `grep -n "LEVEL1_SCREEN_COUNT" src/level_data.h` | `#define LEVEL1_SCREEN_COUNT 13` |
| **11 种敌人** | `grep -E "^#define ENEMY_[A-Z]" src/enemy.h \| grep -v ENEMY_H`（须排除 include guard `ENEMY_H`，否则多计 1） | 11 条（`0x00~0x07,0x10~0x12`） |
| **6 种武器** | `sed -n '20p' src/weapon.c` | `gWeaponSprites[7] = {0x33,0x34,0x31,0x2f,0x32,0x30,0x33}`，注释 `R,M,F,S,L,B` |
| **上游只引用 18 个精灵、玩家仅 3 动作帧（缺帧量依据）** | `grep -n "SPRITE_\|SpriteCode *=" src/player.c src/enemy.c src/bullet.c` | 玩家仅 `player.c:39-41` 定义站立/蹲/瞄上；敌兵仅 `gSoldierWalkFrames[6]`；全 sim 引用 18 个 code（`0x0f~0x4d`）⇒ 行走/跳跃/死亡/枪口/敌死序列全缺 ⇒ 缺帧取 3~5（§3.3） |
| **`e54-upstream` 是否被忽略** | `git check-ignore -v app/src/e54-upstream/...` | **退出码 0**（`.gitignore:133` 命中）⇒ §5.3 该条已落地；目录不再出现在 `git status` |
| `gen_effects_doc.py` 模板停在 v2.39.10 | 脚本内 `doc = '''` 首段 | 硬编码版本/日期 |
