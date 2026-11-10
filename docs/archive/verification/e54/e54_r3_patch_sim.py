# -*- coding: utf-8 -*-
"""第三轮 bot 过不了关的根因修复（一次性落盘 + 逐项 assert）。

离线诊断 logs_temp/E54Diag3.java 的结论（seed=2026 / 600000 步）：
  mask=0   现状             fail topLv=7  934 死
  mask=3   禁用 up/down     fail topLv=7 1027 死   ⇒ 退化不全在 bot
  mask=128 强制普通枪       fail topLv=7 1091 死
  mask=256 强制机枪         fail topLv=5 1075 死
  mask=259 机枪 + 禁智能    PASS 60410 步 / 99 死 ⇒ 需要「平射 DPS 净增强」+「上瞄不吃掉平射」

三处修复：
  ① 蹲射半速（crouch 原本 speed=0 会让 bot 永久蹲住）
  ② WPN_S 平射收敛为近水平分层扇面 + 冷却 16→12（散弹原本是「越吃越弱」）
  ③ 上瞄给单发武器追加一发水平弹（上瞄原本会吃掉唯一的平射弹）
"""
from pathlib import Path

p = Path("app/src/main/java/com/nasmusic/tv/visualizer/arcade/sim/ContraSim.kt")
s = p.read_text(encoding="utf-8")
assert "val diagonalUp = aimUp && vx != 0" in s, "源码不是第三轮版本，停止打补丁"

# ① 蹲射半速 ────────────────────────────────────────────────────────────────
old1 = "        val speed = if (crouch) 0 else ContraConst.RUN_SPEED"
new1 = """        // ⚠️ 蹲射半速推进：原实现 `crouch ⇒ speed = 0`，第三轮 bot 一旦发现低位敌人
        //    就会持续按 down 蹲在原地，形成永久死锁（离线诊断 60 万步过不了第 7 关）。
        //    ⛔ 不许改回 0；半速既保留蹲姿/蹲射语义，又不会让看门狗救不回来。
        val speed = if (crouch) ContraConst.RUN_SPEED / 2 else ContraConst.RUN_SPEED"""
assert s.count(old1) == 1, "① 匹配失败"
s = s.replace(old1, new1)

# ② 散弹平射收敛 ────────────────────────────────────────────────────────────
old2 = """                } else {
                    val bias = if (aimUp) -2 else 0
                    spawnBullet(muzzleX, muzzleY, dirX * 5 * ContraConst.FP_ONE, (-3 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, dirX * 5 * ContraConst.FP_ONE, (-1 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, dirX * 5 * ContraConst.FP_ONE, bias * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, dirX * 5 * ContraConst.FP_ONE, (1 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, dirX * 5 * ContraConst.FP_ONE, (3 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                }
                fireCooldown = 16"""
new2 = """                } else {
                    // ⚠️ 平射/斜上：5 向收敛成「近水平分层扇面」。
                    //    原实现 vy = ±3/±1 px/帧，飞出 60 px 就脱离敌兵 12~16 px 高的碰撞盒，
                    //    再叠加 16 帧冷却（普通枪 9 帧）⇒ 散弹实际平射 DPS 只有普通枪的一半。
                    //    第三轮「奖励只升不降」让玩家永久停在这把弱枪上，bot 清不掉小兵、
                    //    第 7 关反复撞死（离线诊断 60 万步连 boss 都没激活）。
                    //    ⇒ 收敛角度 + 冷却 12 帧，散弹才是名副其实的升级（约普通枪 3.7 倍）。
                    //    冷却不低于 12：5 发齐射 × 约 4 轮在槽内，避免 MAX_BULLETS=24 溢出丢弹。
                    val bias = if (aimUp) -2 else 0
                    val vxFp = dirX * 5 * ContraConst.FP_ONE
                    spawnBullet(muzzleX, muzzleY - 2, vxFp, (-1 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY - 1, vxFp, bias * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, vxFp, bias * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY + 1, vxFp, bias * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY + 2, vxFp, (1 + bias) * ContraConst.FP_ONE, ContraConst.WPN_S)
                }
                fireCooldown = 12"""
assert s.count(old2) == 1, "② 匹配失败"
s = s.replace(old2, new2)

# ③ 上瞄追加平射弹 ──────────────────────────────────────────────────────────
old3 = """        firing = 4
    }

    private fun spawnBullet"""
new3 = """        // ⚠️ 上瞄是「追加一路弹道」，不是「替代平射」：单发武器（普通/机枪/激光）
        //    在 aimUp 时唯一那颗子弹会变成斜上飞，地面小兵完全清不掉 ⇒ bot 一路
        //    上瞄一路撞死。这里补一发水平弹，让「会瞄头顶」不再牺牲推进能力。
        //    散弹已是分层近水平扇面，自带平射输出，不必再追加。
        if (aimUp && weapon != ContraConst.WPN_S) {
            spawnBullet(
                muzzleX, muzzleY,
                facing * 6 * ContraConst.FP_ONE, 0,
                ContraConst.WPN_R,
            )
        }
        firing = 4
    }

    private fun spawnBullet"""
assert s.count(old3) == 1, "③ 匹配失败"
s = s.replace(old3, new3)

# ── 落盘前复核：门禁依赖的字符串一条都不能少 ────────────────────────────────
gates = [
    "val floorY = groundRowNear(ex + 6)",
    "eY[i] += 2 * ContraConst.FP_ONE",
    "if (weapon != ContraConst.WPN_S) weapon = ContraConst.WPN_S",
    "val diagonalUp = aimUp && vx != 0",
    "val dirX = if (aimUp && !diagonalUp) 0 else facing",
    "val bias = if (aimUp) -2 else 0",
]
for g in gates:
    assert g in s, f"门禁字符串丢失：{g}"
assert "weapon = (weapon + 1) % ContraConst.WPN_COUNT" not in s
assert "val speed = if (crouch) 0 else" not in s
assert "fireCooldown = 16" not in s
assert s.count("fun spawnEnemyBullet") == 1 and s.count("fun updateBullets") == 1
assert s.rstrip().endswith("}"), "文件尾部结构异常"

p.write_text(s, encoding="utf-8")
print("OK 三处修复全部落盘；门禁字符串 6/6 保留；文件尾部完整")
print(f"  行数 {len(orig_lines := s.splitlines())}，字节 {len(s.encode('utf-8'))}")
