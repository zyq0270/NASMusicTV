# -*- coding: utf-8 -*-
"""第四轮②修正：贴脸清敌禁止起跳 + 回退有害的威胁纵窗。

E54Diag5 明细实锤：40 次第 5 关死亡里 11 次死于跳跃中（J=TRUE）。
机制 = 弹幕触发 jumpHold → 空中 py 抬高 ⇒ muzzleY 抬高 ⇒ 水平弹从贴地敌人
（y≈162..176）头顶 10px 飞过 ⇒ hp 敌人永远打不掉 ⇒ 站桩 → 又被弹幕打死 → 循环。
同时 [-10,26]→[-14,44] 的威胁扩窗被证伪（contact 307→697）：空中触发停步只会空打。
"""
from pathlib import Path

p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/bot/ContraBot.kt")
s = p.read_text(encoding="utf-8")

# ① threat 提前判定 + 有贴脸威胁不跳
old = """        // ── 闪避：贴脸飞来的敌弹 ⇒ 起跳躲开（站桩输出时的主要死因） ──
        if (sim.playerIsGrounded && incomingBullet(sim)) jumpHold = 10

        // ── boss 战：站桩平射输出，只在贴太近时后退 ──"""
new = """        // ── 闪避：贴脸飞来的敌弹 ⇒ 起跳躲开（站桩输出时的主要死因） ──
        // ⚠️ 第四轮修正：**有贴脸威胁时不许跳**。跳起来 py 抬高 ⇒ muzzleY 抬高 ⇒
        //    水平弹从贴地敌人（y≈162..176）头顶 10px 飞过 ⇒ hp 敌人永远打不掉 ⇒
        //    站桩 → 又被它的弹幕罩死（离线明细：第 5 关 40 次死亡里 11 次死于跳跃中）。
        //    ⇒ 先站桩平射把贴脸的清掉；没有贴脸威胁时才起跳躲弹。
        val threat = nearestThreatAhead(sim)
        if (sim.playerIsGrounded && threat < 0 && incomingBullet(sim)) jumpHold = 10

        // ── boss 战：站桩平射输出，只在贴太近时后退 ──"""
assert s.count(old) == 1, "① 闪避/威胁顺序匹配失败"
s = s.replace(old, new)

# ② 删除后面重复的 threat 计算
old = """        // ── 贴脸敌人：先停步平射清掉再走（⛔ 这里不叠加瞄准，平射才是正解） ──
        val threat = nearestThreatAhead(sim)
        if (threat >= 0) {"""
new = """        // ── 贴脸敌人：先停步平射清掉再走（⛔ 这里不叠加瞄准，平射才是正解） ──
        if (threat >= 0) {"""
assert s.count(old) == 1, "② 重复 threat 计算匹配失败"
s = s.replace(old, new)

# ③ 回退有害的威胁纵窗
old = """            // 台阶 / 楼梯屏（第 5 关 'U'/'S'）玩家在坡上时，贴脸地堡的 dy 可达 40+；
            // 旧窗口 [−10,26] 会把它漏掉 ⇒ 不停步、直接撞上去（第四轮扩到 [−14,44]）。
            // 仍排除「明显更高」的目标：平射打不到的交给 [applyAim] 斜上射。
            val dy = sim.enemyPxY(i) - py
            if (dy > 44 || dy < -14) continue"""
new = """            // ⛔ 纵窗保持 [−10,26]（第四轮实测：扩到 [−14,44] 会在玩家跳起 / 上坡时把
            //    「平射根本打不到」的高处敌人也当成威胁 ⇒ 停步空打 90 帧 → 撞死。
            //    contact 死亡 307 → 697 就是它造成的，已回退）。
            //    明显更高/更低的目标交给 [applyAim] 与走位。
            val dy = sim.enemyPxY(i) - py
            if (dy > 26 || dy < -10) continue"""
assert s.count(old) == 1, "③ 威胁窗口回退匹配失败"
s = s.replace(old, new)

assert s.count("val threat = nearestThreatAhead(sim)") == 1
assert "threat < 0 && incomingBullet" in s
assert "dy > 44" not in s
p.write_text(s, encoding="utf-8")
print("OK bot 修正落盘：贴脸威胁不跳 + threat 提前判定去重 + 纵窗回退 [−10,26]")
