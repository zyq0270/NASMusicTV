# -*- coding: utf-8 -*-
"""第四轮②：bot「弱智/一直在死」的调参优化。一次落盘 + 逐项 assert。

死亡画像（logs_temp/E54Diag4.java / E54Diag5.java，seed=2026 / 60 万步长跑）：
  1152 死 / 平均存活 465 帧（7.8 s）；第 5 关独占 1005 死。
  死亡间隔恰好 = 54(死亡动画) + 90(无敌) = 144 帧 ⇒ 重生点前方的地堡/炮台「无敌一过就要人命」。
  贴脸 t4(E_PILLBOX, hp=5) 站桩清不掉 + 它的 3 发抛物弹雨落下正好罩住玩家 = 无解伤害。
"""
from pathlib import Path

# ── 1. ContraSim.kt ─────────────────────────────────────────────────────────
p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraSim.kt")
s = p.read_text(encoding="utf-8")

old = "                ContraConst.E_TURRET -> 4"
new = "                ContraConst.E_TURRET -> 3"
assert s.count(old) == 1, "TURRET hp 匹配失败"
s = s.replace(old, new)

old = "                ContraConst.E_PILLBOX -> 5"
new = "                ContraConst.E_PILLBOX -> 3"
assert s.count(old) == 1, "PILLBOX hp 匹配失败"
s = s.replace(old, new)

old = """                ContraConst.E_PILLBOX -> {
                    if (eTimer[i] <= 0 && absInt(ex - playerLeft) < 240) {
                        eTimer[i] = 120
                        spawnEnemyBullet(ex + 6, ey + 2, -ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                        spawnEnemyBullet(ex + 6, ey + 2, 0, -4 * ContraConst.FP_ONE)
                        spawnEnemyBullet(ex + 6, ey + 2, ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                    }
                }"""
new = """                ContraConst.E_PILLBOX -> {
                    if (eTimer[i] <= 0 && absInt(ex - playerLeft) < 240) {
                        // ⚠️ 第四轮：3 发抛物雨在贴脸时是「无解伤害」—— 弹幕落下正好罩住
                        //    站桩清敌的玩家（离线诊断：平均存活仅 7.8 s，第 5 关 60 万步死 1005 次）。
                        //    砍成 2 发侧向 + 冷却 150，与第一轮「炮台四向 → 两发」同一调参先例。
                        eTimer[i] = 150
                        spawnEnemyBullet(ex + 6, ey + 2, -ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                        spawnEnemyBullet(ex + 6, ey + 2, ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                    }
                }"""
assert s.count(old) == 1, "PILLBOX 弹幕匹配失败"
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

# ── 2. ContraConst.kt ───────────────────────────────────────────────────────
p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraConst.kt")
s = p.read_text(encoding="utf-8")
old = "    const val INVULN_FRAMES = 90"
new = """    // ⚠️ 第四轮 90→120：旧口径下「死亡动画 54 帧 + 无敌 90 帧 = 144 帧」正好是实测的死亡循环间隔
    //    —— 重生点前方的地堡/炮台在无敌一过时立刻要人命（真机观感 = 自动玩一直在死、像弱智）。
    const val INVULN_FRAMES = 120"""
assert s.count(old) == 1, "INVULN_FRAMES 匹配失败"
s = s.replace(old, new)
p.write_text(s, encoding="utf-8")

# ── 3. ContraBot.kt：贴脸威胁的纵向窗口 ─────────────────────────────────────
p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/bot/ContraBot.kt")
s = p.read_text(encoding="utf-8")
old = """            // 只把「大致同高」的敌人当成需要停步清的目标：
            // 明显更高的敌人平射打不到 ⇒ 交给 [applyAim] 的斜上射，停步只会死锁
            val dy = sim.enemyPxY(i) - py
            if (dy > 26 || dy < -10) continue"""
new = """            // 台阶 / 楼梯屏（第 5 关 'U'/'S'）玩家在坡上时，贴脸地堡的 dy 可达 40+；
            // 旧窗口 [−10,26] 会把它漏掉 ⇒ 不停步、直接撞上去（第四轮扩到 [−14,44]）。
            // 仍排除「明显更高」的目标：平射打不到的交给 [applyAim] 斜上射。
            val dy = sim.enemyPxY(i) - py
            if (dy > 44 || dy < -14) continue"""
assert s.count(old) == 1, "threat 窗口匹配失败"
s = s.replace(old, new)
assert s.count("dy > 26 || dy < -10") == 0
p.write_text(s, encoding="utf-8")

print("OK 第四轮调参落盘：TURRET hp 4→3、PILLBOX hp 5→3、PILLBOX 弹雨 3→2 发/冷却 150、INVULN 90→120、threat 纵窗 [−10,26]→[−14,44]")
