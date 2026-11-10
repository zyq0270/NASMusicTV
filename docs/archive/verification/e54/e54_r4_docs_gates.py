# -*- coding: utf-8 -*-
"""第四轮③：门禁（+2 条）与文档口径（+第四轮记录）一次落盘。"""
from pathlib import Path

# ── 1. ContraBotIntelligenceTest.kt：botSrc helper + 2 条新门禁 ─────────────
p = Path("app/src/test/java/com/nasmusic/tv/visualizer/arcade/ContraBotIntelligenceTest.kt")
s = p.read_text(encoding="utf-8")

old = """    private fun simSrc(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraSim.kt")"""
new = """    private fun simSrc(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraSim.kt")

    private fun botSrc(): String =
        src("app/src/main/java/com/nasmusic/tv/visualizer/arcade/bot/ContraBot.kt")"""
assert s.count(old) == 1
s = s.replace(old, new)

old = """    // ── 问题 2：朝向 ────────────────────────────────────────────────────"""
new = """    /**
     * 第四轮护栏：离线明细（logs_temp/E54Diag5.java）证明，弹幕触发起跳会让枪口抬高、
     * 水平弹从贴地敌人头顶飞过 ⇒ hp 敌人永远打不掉 ⇒ 「一直在跳、一直在死」。
     * 修正后：60 万步死亡 1235→512、平均存活 7.2s→18.6s、通关 5.2 万步 → 1.46 万步。
     */
    @Test
    fun `贴脸清敌不许起跳 威胁纵窗不得扩到高处`() {
        val b = botSrc()
        assertTrue(
            "有贴脸威胁时必须禁跳（跳起来枪口抬高，水平弹从贴地敌人头顶飞过 ⇒ hp 敌人永远打不掉）",
            b.contains("threat < 0 && incomingBullet(sim)"),
        )
        assertFalse("⛔ threat 纵窗扩到 44 已被实测证伪（contact 死亡 307→697）", b.contains("dy > 44"))
        assertTrue("威胁纵窗必须保持 [−10,26]", b.contains("if (dy > 26 || dy < -10) continue"))
    }

    @Test
    fun `画面横纵各自铺满 不许恢复 4:3 居中`() {
        val s = scene()
        assertTrue("横向必须铺满（真机左右黑边被裁定不要）",
            s.contains("scaleX = viewportW / (ContraConst.LOGICAL_W * 1.25f)"))
        assertTrue("纵向必须铺满", s.contains("scaleY = viewportH / ContraConst.LOGICAL_H.toFloat()"))
        assertFalse("⛔ 4:3 居中的 offsetX/offsetY 必须撤掉", s.contains("private var offsetX"))
        assertFalse("⛔ 不许恢复按屏高取整数倍的缩放",
            s.contains("viewportH / ContraConst.LOGICAL_H.toFloat()).toInt()"))
    }

    // ── 问题 2：朝向 ────────────────────────────────────────────────────"""
assert s.count(old) == 1
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

# ── 2. CHANGELOG.md：第四轮条目 ────────────────────────────────────────────
p = Path("CHANGELOG.md")
s = p.read_text(encoding="utf-8")

old = """### Notes

- 未改 `VisualizerStage.kt`（不动舞台）；遥控确认键打断 demo **仅有代码通路、暂无实际入口**（见 `docs/technical-overview.md` §10.374 验收缺口）"""
new = """- ⭐ **同版本第四轮（2026-11-10 电视复测 2 项反馈）**：仍为 v2.39.17 / 202
- **画面横向占满全屏**：撤销「整数倍 k + 4:3 居中」，横纵各自铺满
  （`scaleX = viewportW/320`、`scaleY = viewportH/240`），16:9 电视不再有左右黑边
  （精灵横向拉伸 ≈1.33 倍，GL_NEAREST 逐像素取点）
- **bot 再优化（离线死亡画像驱动）**：地堡 hp 5→3、炮台 hp 4→3、地堡 3 发抛物弹雨砍成
  2 发侧向 + 冷却 150、无敌 90→120 帧、**贴脸清敌禁跳**（跳起来枪口抬高、水平弹从贴地敌人
  头顶飞过 ⇒ hp 敌人永远打不掉的「一直在跳一直在死」死循环）。⇒ 通关 5.2 万步/92 死 →
  **1.46 万步/11 死**；60 万步长跑死亡 1235→512、平均存活 7.2s→18.6s、第 5 关死亡死角消除
- 新增门禁 2 条（贴脸禁跳 / 画面铺满）；arcade 定向 42 用例

### Notes

- 未改 `VisualizerStage.kt`（不动舞台）；遥控确认键打断 demo **仅有代码通路、暂无实际入口**（见 `docs/technical-overview.md` §10.374 验收缺口）"""
assert s.count(old) == 1
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

# ── 3. docs/technical-overview.md §10.376：⑥ 节 + 口径 ─────────────────────
p = Path("docs/technical-overview.md")
s = p.read_text(encoding="utf-8")

old = "#### 门禁与验收\n\n"
new = """#### ⑥ 同版本第四轮：画面横向铺满 + bot 死亡画像驱动的再优化

**① 画面横向占满全屏**（所有者复测裁定「地面和背景应该横向占满全屏」）：撤销
`onSurfaceChanged` 的「整数倍 k + 4:3 居中」，改为横纵各自铺满（`scaleX = viewportW/320`、
`scaleY = viewportH/240`），16:9 电视不再有左右黑边（精灵横向拉伸 ≈1.33 倍）。`AndroidView`
本来就是 `fillMaxSize`，黑边完全来自 GL 侧居中偏移。

**② bot 死亡画像**（`logs_temp/E54Diag4/E54Diag5.java`，60 万步长跑）：1152 死 / 平均存活
465 帧（7.8 s）；**第 5 关独占 1005 死**；死亡间隔恰好 = 54（死亡动画）+ 90（无敌）= 144 帧
⇒ 重生点前方的地堡/炮台「无敌一过就要人命」；40 次死亡明细里 11 次死于**跳跃中**。

两条死结：

1. 站桩清 hp=5 的地堡时，它的 3 发抛物弹雨正好罩住玩家 = 无解伤害（第一轮已把炮台
   四向砍成两发，这次同一先例：地堡 hp 5→3、弹雨 3→2 发 + 冷却 120→150、炮台 hp 4→3、
   无敌 90→120 帧）。
2. 弹幕触发 `jumpHold` → 空中 `py` 抬高 ⇒ `muzzleY` 抬高 ⇒ 水平弹从贴地敌人
   （y≈162..176）头顶 10px 飞过 ⇒ **hp 敌人永远打不掉** ⇒ 站桩 → 又被弹幕罩死。
   ⛔ 有贴脸威胁时禁跳（`threat < 0 && incomingBullet(sim)` 才允许起跳）。

⚠️ 一次被证伪的尝试要如实记录：threat 纵窗 [−10,26] 扩到 [−14,44] 反而让 contact 死亡
307→697 —— 玩家跳起 / 上坡时把「平射根本打不到」的高处敌人也当成威胁 ⇒ 停步空打 90 帧
→ 撞死。已回退，并加门禁防止再扩。

结果：**通关 52,641 步 / 92 死 → 14,600 步 / 11 死**；60 万步长跑死亡 1235→512、
平均存活 7.2 s→18.6 s、boss 击杀 12→319 次、第 5 关死亡死角消除（每关 29~118 均匀分布）。

#### 门禁与验收

"""
assert s.count(old) == 1
s = s.replace(old, new)

old = """新增 `ContraBotIntelligenceTest`（10 条）：上瞄行为帧数、跑 60000 步能吃到奖励并升散弹、
道具重力与奖励不降级、DEMO 跑过 6000 帧不回标题、斜上弹道语句、boss 平射纪律、
奖励不能越吃越弱（散弹净增强 / 上瞄补平射 / 蹲射半速）、朝向判据、
背景全走 `ContraArt.BG_*` 且帧表命中、歌词背板与 HUD 让位。既有 `ContraBotTest` 的
「8 关全推进 / 同 seed 逐帧可复现 / 108000 步长跑」继续保留。

现行规模：`visualizer/arcade/` **14 文件 / 3,205 行 + 6 个测试文件 / 40 用例**。"""
new = """新增 `ContraBotIntelligenceTest`（12 条）：上瞄行为帧数、跑 60000 步能吃到奖励并升散弹、
道具重力与奖励不降级、DEMO 跑过 6000 帧不回标题、斜上弹道语句、boss 平射纪律、
奖励不能越吃越弱（散弹净增强 / 上瞄补平射 / 蹲射半速）、**贴脸清敌禁跳 + 威胁纵窗**、
**画面横纵铺满不许恢复 4:3 居中**、朝向判据、背景全走 `ContraArt.BG_*` 且帧表命中、
歌词背板与 HUD 让位。既有 `ContraBotTest` 的
「8 关全推进 / 同 seed 逐帧可复现 / 108000 步长跑」继续保留。

现行规模：`visualizer/arcade/` **14 文件 / 3,211 行 + 6 个测试文件 / 42 用例**。"""
assert s.count(old) == 1
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

# ── 4. docs/e54-contra-porting-plan.md：第四轮说明 ──────────────────────────
p = Path("docs/e54-contra-porting-plan.md")
s = p.read_text(encoding="utf-8")

old = "> 第三轮修复后 `visualizer/arcade/` 为 **14 文件 / 3,205 行 + 6 个测试文件 / 40 个用例**；"
new = "> 第四轮修复后 `visualizer/arcade/` 为 **14 文件 / 3,211 行 + 6 个测试文件 / 42 个用例**；"
assert s.count(old) == 1
s = s.replace(old, new)

old = "> 「52,641 步走完 8 关 / 92 死」，且上瞄与蹲射照常生效。详见 §10.376 ⑤。\n"
new = old + """>
> ⭐ **2026-11-10 第四轮（电视复测 2 项反馈）**：① 画面横纵各自铺满，撤销 4:3 居中（16:9 不再有
> 左右黑边）；② bot 死亡画像驱动的再优化——地堡/炮台减血减弹、无敌 120 帧、**贴脸清敌禁跳**，
> 通关从 5.2 万步 / 92 死 → **1.46 万步 / 11 死**，60 万步长跑平均存活 7.2 s → 18.6 s。
> 详见 §10.376 ⑥。⛔ 仍为 v2.39.17 / 202。
"""
assert s.count(old) == 1
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

print("OK 门禁 +2 条、四份文档口径同步（42 用例 / 3,211 行 / 第四轮记录）")
