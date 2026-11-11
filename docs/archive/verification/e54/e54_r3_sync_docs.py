# -*- coding: utf-8 -*-
"""第三轮收尾：口径（38→40 用例）+「过不了关」根因记录，一次性落盘并逐项 assert。"""
from pathlib import Path

root = Path(".")

# ── 1. CHANGELOG.md ──────────────────────────────────────────────────────────
p = root / "CHANGELOG.md"
s = p.read_text(encoding="utf-8")

old = "- 新增 `ContraBotIntelligenceTest`（8 条：上瞄 / 吃奖励 / 演示不回标题 / 斜上弹道 / 朝向 / 背景 / 歌词背板）\n"
new = """- 新增 `ContraBotIntelligenceTest`（10 条：上瞄 / 吃奖励 / 演示不回标题 / 斜上弹道 /
  boss 平射纪律 / 奖励不能越吃越弱 / 朝向 / 背景 / 歌词背板）
- ⭐ **「过不了关」的真实根因与三处修复**（离线诊断 `logs_temp/E54Diag3.java`，20 组对照）：
  `WPN_S` 散弹平射是 ±3 px/帧 大角度 5 向 + 16 帧冷却 ⇒ 实际平射 DPS 只有普通枪一半，
  叠加「奖励不降级」后 bot 吃到第一个道具就永久停在这把弱枪上；上瞄又吃掉单发武器唯一的
  平射弹；`crouch ⇒ speed = 0` 让蹲射原地锁死。修复：散弹收敛为近水平分层扇面 + 冷却 12、
  `aimUp` 给单发武器**追加**一发水平弹、蹲射半速推进。⇒ 同一诊断从「60 万步卡第 7 关 /
  934 次死亡」变为「**52,641 步走完 8 关 / 92 次死亡**」，且上瞄 2,517 帧、蹲射 2,989 帧
  全部保留（没有靠撤掉智能来换通关）
"""
assert s.count(old) == 1, "CHANGELOG 门禁条目匹配失败"
s = s.replace(old, new)
assert "（10 条：" in s and "52,641 步" in s
p.write_text(s, encoding="utf-8")

# ── 2. docs/e54-contra-porting-plan.md ──────────────────────────────────────
p = root / "docs/archive/e54-contra-porting-plan.md"
s = p.read_text(encoding="utf-8")

old = "第三轮修复后 `visualizer/arcade/` 为 **14 文件 / 约 3.2k 行 + 6 个测试文件 / 38 个用例**；"
new = "第三轮修复后 `visualizer/arcade/` 为 **14 文件 / 3,205 行 + 6 个测试文件 / 40 个用例**；"
assert s.count(old) == 1, "plan 规模口径匹配失败"
s = s.replace(old, new)

old = "> 详见 `docs/technical-overview.md` §10.376。⛔ 版本号按同效果多轮修复口径**不升**，仍是 v2.39.17 / 202。\n"
new = old + """>
> ⭐ **第三轮收尾（离线诊断定位「过不了关」的真实根因）**：散弹平射是大角度 5 向 + 16 帧冷却，
> 实际平射 DPS 只有普通枪一半；「奖励不降级」让 bot 永久停在这把弱枪上，上瞄又吃掉单发武器
> 唯一的平射弹、蹲射 `crouch ⇒ speed = 0` 还会原地锁死。三处修复（散弹收敛为近水平分层 +
> 冷却 12、上瞄补一发水平弹、蹲射半速）后，离线诊断从「60 万步卡第 7 关 / 934 死」变为
> 「52,641 步走完 8 关 / 92 死」，且上瞄与蹲射照常生效。详见 §10.376 ⑤。
"""
assert s.count(old) == 1, "plan 第三轮段落匹配失败"
s = s.replace(old, new)
assert "40 个用例" in s and "§10.376 ⑤" in s
p.write_text(s, encoding="utf-8")

# ── 3. docs/technical-overview.md §10.376 ───────────────────────────────────
p = root / "docs/technical-overview.md"
s = p.read_text(encoding="utf-8")

section5 = """#### ⑤ ⭐ 收尾：「过不了关」的真实根因不是 bot 不够聪明

第三轮给 bot 加上瞄 / 蹲射 / 追奖励后，`ContraBotTest > bot 能连续推进全部 8 关` 反而红了：
`第 8 关从未抵达（步数=600000 关卡序列=[1..7]）`。为避开每轮 8 分钟 Gradle，用已编译的
Kotlin class 直接在 JVM 上跑参数化诊断 `logs_temp/E54Diag3.java`（反射改 `private set` 的
`weapon` 字段、对 `decide()` 输出做后处理，一次跑 20 组开关组合）：

| 组合 | 结果 |
|---|---|
| 现状 | fail，卡第 7 关，934 次死亡 |
| 禁用 up/down（等于退回增强前的 bot） | 仍 fail，1027 次死亡 ⇒ 退化不全在 bot |
| 强制普通枪 | 仍 fail，1091 次死亡 ⇒ 不是「永久散弹」单因 |
| 强制机枪 + 禁用智能 | **PASS 60,410 步 / 99 死** ⇒ 必须同时满足两个条件 |
| 只做其中一件（强散弹或撤智能） | 全部 fail |

三条耦合根因：

1. `WPN_S` 平射是 `vy = ±3/±1 px/帧` 的 5 向大角度 + `fireCooldown = 16`（普通枪 9）
   ⇒ 斜弹飞出 60 px 就脱离敌兵 12~16 px 高的碰撞盒，**散弹实际平射 DPS 只有普通枪一半**；
   叠加「奖励只升不降」后 bot 吃到第一个道具就永久停在这把弱枪上，越吃越弱。
2. 单发武器（普通 / 机枪 / 激光）在 `aimUp` 时**唯一**那颗子弹变成斜上飞 ⇒ bot 一路上瞄、
   地面小兵一个都清不掉。
3. `crouch ⇒ speed = 0` ⇒ bot 一旦发现低位敌人就持续按 `down` 蹲在原地，卡住看门狗救不回来。

修复（`ContraSim.kt` 三处）：散弹收敛为近水平分层扇面 + 冷却 16→12；`aimUp` 给单发武器
**追加**一发水平弹（上瞄从「替代平射」变成「追加一路弹道」）；蹲射改为半速推进。
修复后**保留全部智能行为**（up 2,517 帧 / down 2,989 帧），同一诊断从「60 万步卡第 7 关 /
934 死」变为「**52,641 步走完 8 关 / 92 死**」。新增
`奖励不能越吃越弱 散弹是净增强且上瞄不吞掉平射` 门禁守住这三条数值，防止被「顺手优化」回去。

#### 门禁与验收

"""
old = "#### 门禁与验收\n\n"
assert s.count(old) == 1, "§10.376 门禁标题匹配失败"
s = s.replace(old, section5)

old = """新增 `ContraBotIntelligenceTest`（8 条）：上瞄行为帧数、跑 60000 步能吃到奖励并升散弹、
道具重力与奖励不降级、DEMO 跑过 6000 帧不回标题、斜上弹道语句、朝向判据、
背景全走 `ContraArt.BG_*` 且帧表命中、歌词背板与 HUD 让位。既有 `ContraBotTest` 的
「8 关全推进 / 同 seed 逐帧可复现 / 108000 步长跑」继续保留。

现行规模：`visualizer/arcade/` **14 文件 / 约 3.2k 行 + 6 个测试文件 / 38 用例**。"""
new = """新增 `ContraBotIntelligenceTest`（10 条）：上瞄行为帧数、跑 60000 步能吃到奖励并升散弹、
道具重力与奖励不降级、DEMO 跑过 6000 帧不回标题、斜上弹道语句、boss 平射纪律、
奖励不能越吃越弱（散弹净增强 / 上瞄补平射 / 蹲射半速）、朝向判据、
背景全走 `ContraArt.BG_*` 且帧表命中、歌词背板与 HUD 让位。既有 `ContraBotTest` 的
「8 关全推进 / 同 seed 逐帧可复现 / 108000 步长跑」继续保留。

现行规模：`visualizer/arcade/` **14 文件 / 3,205 行 + 6 个测试文件 / 40 用例**。"""
assert s.count(old) == 1, "§10.376 门禁段落匹配失败"
s = s.replace(old, new)
assert "40 用例" in s and "#### ⑤" in s
p.write_text(s, encoding="utf-8")

print("OK 三份文档同步完成：38→40 用例、3.2k→3,205 行、§10.376 追加 ⑤ 根因节、CHANGELOG 追加修复条目")
