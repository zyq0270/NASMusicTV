package com.nasmusic.tv.visualizer.arcade.bot

import com.nasmusic.tv.visualizer.arcade.GameInput
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim
import kotlin.math.abs

/**
 * 自动玩 bot（方案 §4.5，J1 的**验收硬条件**；2026-11-10 真机反馈后第三轮增强）。
 *
 * ## 为什么要自建
 * 上游的 attract mode 是**假自玩**：`phase = gIntroTimer % 180` 的五段脚本，
 * 无录像、无 AI、不会过关 ⇒ 能借的只有状态机形状（`TITLE / DEMO / INTERRUPTED`）。
 *
 * ## 能力（逐条对应方案 §4.5 的表 + 第三轮新增）
 * | 能力 | 实现 |
 * |---|---|
 * | 向右推进 | 恒速右移 + 遇障跳跃 |
 * | 地形感知 | 读 sim 的碰撞码表：前方无地面 / 地面抬高 / 前方实心 ⇒ 起跳 |
 * | 卡住检测 | 45 帧窗口位移 < 6 px ⇒ 强制跳；多次 ⇒ 反向脱离；六次 ⇒ 重置本关 |
 * | 射击 | 恒按住；**贴脸敌人先停步平射清掉**（接触伤害是主要死因） |
 * | ⭐ 上方瞄准 | 头顶的飞行胶囊 / 高台炮台 ⇒ `up`：站定垂直射、跑动 45° 斜上射 |
 * | ⭐ 低位瞄准 | 明显低于枪口的敌人 ⇒ `down` 蹲射 |
 * | ⭐ 追奖励 | 道具落地前放慢、落地后停一拍 / 短距回头，跳起来够空中道具 |
 * | ⭐ boss 平射纪律 | boss 是水平目标 ⇒ 站桩平射，⛔ 不按 up（上瞄会把子弹全打飞） |
 * | 死亡重试 | sim 自己重生在同屏检查点；生命耗尽 ⇒ sim 重置本关 |
 * | 跨 8 关 | boss 死 ⇒ sim 推进关卡（上游**没有**过关判定，这套完全自建） |
 *
 * ## 确定性（⛔ 硬约束）
 * 只读 sim 状态，⛔ 不读 `System.currentTimeMillis()` / 不建对象 / 不用随机
 * ⇒ 同 seed 同一帧序列（`ContraBotTest` 有断言）。
 */
class ContraBot {

    // ── 卡住检测窗口 ──
    private var windowX: Int = -1
    private var windowFrames: Int = 0
    private var stuckWindows: Int = 0

    private var jumpHold: Int = 0
    private var retreatFrames: Int = 0

    /** 连续「停步清敌」帧数（看门狗：清不掉就强行走，避免死锁） */
    private var stopFrames: Int = 0

    fun reset() {
        windowX = -1
        windowFrames = 0
        stuckWindows = 0
        jumpHold = 0
        retreatFrames = 0
        stopFrames = 0
    }

    /** 每帧决策：把结果写进**复用**的 [input]（原地写字段） */
    fun decide(sim: ContraSim, input: GameInput) {
        input.clear()
        if (!sim.playerIsAlive) {
            reset()
            return
        }
        if (sim.clearPending) return

        input.shoot = true

        if (jumpHold > 0) {
            input.jump = true
            jumpHold--
        }

        // ── 闪避：贴脸飞来的敌弹 ⇒ 起跳躲开（站桩输出时的主要死因） ──
        // ⚠️ 第四轮修正：**有贴脸威胁时不许跳**。跳起来 py 抬高 ⇒ muzzleY 抬高 ⇒
        //    水平弹从贴地敌人（y≈162..176）头顶 10px 飞过 ⇒ hp 敌人永远打不掉 ⇒
        //    站桩 → 又被它的弹幕罩死（离线明细：第 5 关 40 次死亡里 11 次死于跳跃中）。
        //    ⇒ 先站桩平射把贴脸的清掉；没有贴脸威胁时才起跳躲弹。
        val threat = nearestThreatAhead(sim)
        if (sim.playerIsGrounded && threat < 0 && incomingBullet(sim)) jumpHold = 10

        // ── boss 战：站桩平射输出，只在贴太近时后退 ──
        // ⚠️ ⛔ 不许在这里按 `up`：boss 是**水平**目标（dist 60..92），上瞄会让
        //    子弹变成垂直/斜上全打飞；实测第 7 关 boss 打不死、60 万步都过不去。
        //    boss 上下浮动 0..14 px，平射 y = py+7 始终落在 boss 24px 高的碰撞盒内。
        if (sim.bossIsActive) {
            val dist = sim.bossPxX - sim.playerPxX
            if (dist < 60) input.left = true else if (dist > 92) input.right = true
            updateStuck(sim, input, advancing = input.right)
            return
        }

        // ── 强制脱离（连续两次卡住） ──
        if (retreatFrames > 0) {
            retreatFrames--
            input.left = true
            updateStuck(sim, input, advancing = false)
            return
        }

        // ── 贴脸敌人：先停步平射清掉再走（⛔ 这里不叠加瞄准，平射才是正解） ──
        if (threat >= 0) {
            stopFrames++
            // ⚠️ 看门狗：清敌超过 90 帧还没清掉（典型是台阶挡射界）⇒ 强制推进，
            //    否则会永久死锁（实测：第 5 关楼梯上的炮台，平射被台阶吃掉）
            if (stopFrames < 90) {
                updateStuck(sim, input, advancing = false)
                return
            }
        } else {
            stopFrames = 0
        }

        input.right = true

        // ── ⭐ 智能瞄准（斜上 / 垂直 / 蹲射） ──
        applyAim(sim, input)

        // ── ⭐ 追奖励（⛔ 不改变推进方向，见 chasePickup） ──
        chasePickup(sim)

        // ── 地形感知（仅在地面时判，空中不改向） ──
        if (sim.playerIsGrounded && needJump(sim)) jumpHold = 9

        updateStuck(sim, input, advancing = input.right)
    }

    /**
     * 目标优先级：头顶的飞行胶囊 / 高台敌人 > 脚下低位敌人。
     *
     * `up` 在 sim 里的语义：`aimUp && vx == 0` ⇒ 纯垂直；`aimUp && vx != 0` ⇒ 45° 斜上。
     * 所以「保留 `right`」就是斜上射，「清掉 `right`」就是站定垂直射。
     */
    private fun applyAim(sim: ContraSim, input: GameInput) {
        val px = sim.playerPxX
        val py = sim.playerPxY
        var bestDx = Int.MAX_VALUE
        var found = false
        for (i in 0 until sim.enemySlots) {
            if (!sim.enemyActive(i)) continue
            val type = sim.enemyTypeOf(i)
            if (type == ContraConst.E_ITEM) continue
            val dx = sim.enemyPxX(i) - px
            if (dx < -18 || dx > 120) continue
            val dy = sim.enemyPxY(i) - py
            // 只接手「明显在头顶」的目标（≥18px）；略高于枪口的交给平射/贴脸清敌，
            // 否则斜上弹会从它头顶飞过去、bot 还不停步 ⇒ 反复撞死
            if (dy >= -18) continue
            if (dx < bestDx) {
                bestDx = dx
                found = true
            }
        }
        if (found) {
            // ⚠️ 始终保持横向推进：sim 的斜上弹道正好由 `up + right` 触发。
            //    早期版本在「目标几乎在正上方」时清掉 right 站定垂直打，而
            //    `updateStuck(advancing = false)` 不计卡住 ⇒ 会永久停步
            //    （实测 seed 2026 第 7 关跑满 60 万步都过不去）。
            input.up = true
            return
        }

        // 没有上方目标 ⇒ 找明显低于枪口的敌人，蹲下平射
        for (i in 0 until sim.enemySlots) {
            if (!sim.enemyActive(i)) continue
            if (sim.enemyTypeOf(i) == ContraConst.E_ITEM) continue
            val dx = sim.enemyPxX(i) - px
            val dy = sim.enemyPxY(i) - py
            if (dx in 6..70 && dy > 18) {
                input.down = true
                return
            }
        }
    }

    /**
     * 追可吃奖励 —— ⛔ **绝不改变水平推进方向**。
     *
     * `E_ITEM` 现在有重力（2 px/帧）会落到「地面行 - 12」，玩家正常跑过去就会重叠吃到；
     * 早期版本的「回头捡 / 停一拍」会让 `updateStuck` 收不到 `advancing = true`，
     * 加上看门狗失效就会原地磨（真机表现为过不了关）。
     * ⇒ 这里只补一件事：道具还在明显高空、且就在前方时跳起来够一下。
     */
    private fun chasePickup(sim: ContraSim) {
        val px = sim.playerPxX
        val py = sim.playerPxY
        var found = false
        for (i in 0 until sim.enemySlots) {
            if (!sim.enemyActive(i)) continue
            if (sim.enemyTypeOf(i) != ContraConst.E_ITEM) continue
            val dx = sim.enemyPxX(i) - px
            if (dx < 8 || dx > 110) continue
            val dy = sim.enemyPxY(i) - py
            if (dy > -24) continue          // 已经接近地面 ⇒ 走过去自然吃到
            found = true
            break
        }
        if (found && sim.playerIsGrounded) jumpHold = maxOf(jumpHold, 11)
    }

    /** 是否有即将命中玩家躯干的敌弹（前方 40 px 内、上下 14 px 内） */
    private fun incomingBullet(sim: ContraSim): Boolean {
        val cx = sim.playerPxX + 5
        val cy = sim.playerPxY + 11
        for (i in 0 until sim.enemyBulletSlots) {
            if (!sim.enemyBulletActive(i)) continue
            val dx = sim.enemyBulletPxX(i) - cx
            if (dx < -16 || dx > 40) continue
            if (abs(sim.enemyBulletPxY(i) - cy) <= 14) return true
        }
        return false
    }

    /**
     * 前方 `[-6, 40]` px 内是否有会杀人的敌人（道具不算）。
     * 返回槽位下标，没有则 -1。
     */
    private fun nearestThreatAhead(sim: ContraSim): Int {
        val px = sim.playerPxX
        val py = sim.playerPxY
        var best = -1
        var bestDx = Int.MAX_VALUE
        for (i in 0 until sim.enemySlots) {
            if (!sim.enemyActive(i)) continue
            if (sim.enemyTypeOf(i) == ContraConst.E_ITEM) continue
            val dx = sim.enemyPxX(i) - px
            if (dx < -6 || dx > 40) continue
            // ⛔ 纵窗保持 [−10,26]（第四轮实测：扩到 [−14,44] 会在玩家跳起 / 上坡时把
            //    「平射根本打不到」的高处敌人也当成威胁 ⇒ 停步空打 90 帧 → 撞死。
            //    contact 死亡 307 → 697 就是它造成的，已回退）。
            //    明显更高/更低的目标交给 [applyAim] 与走位。
            val dy = sim.enemyPxY(i) - py
            if (dy > 26 || dy < -10) continue
            if (dx < bestDx) {
                bestDx = dx
                best = i
            }
        }
        return best
    }

    /** 前方是否需要起跳：无地面 / 地面抬高 / 有实心墙 */
    private fun needJump(sim: ContraSim): Boolean {
        val feetRow = (sim.playerPxY + ContraConst.PLAYER_H) / ContraConst.TILE
        // 只探「紧邻的下一格」：探两格会提前将近一格起跳（实测会跳进水坑）
        val bodyCol = (sim.playerPxX + ContraConst.PLAYER_W + 4) / ContraConst.TILE
        for (col in intArrayOf(bodyCol, bodyCol + 1)) {
            val ground = sim.terrainGroundRow(col)
            // 没有地面（水坑 / 空洞），或地面比当前脚高（台阶 / 石柱）
            if (ground < 0) return true
            if (ground < feetRow) return true
            if (ground > feetRow + 1) return true
            // 实心墙（石柱 / 走廊天花板下沿 / boss 房墙）
            if (sim.terrainCode(col, feetRow - 1) == ContraConst.C_SOLID) return true
            if (sim.terrainCode(col, feetRow - 2) == ContraConst.C_SOLID) return true
        }
        return false
    }

    private fun updateStuck(sim: ContraSim, input: GameInput, advancing: Boolean) {
        if (windowX < 0) {
            windowX = sim.playerPxX
            windowFrames = 0
        }
        windowFrames++
        if (windowFrames < 45) return

        val moved = sim.playerPxX - windowX
        windowX = sim.playerPxX
        windowFrames = 0

        if (!advancing) {
            // 后退 / 停步清敌 / 等奖励期不计卡住
            if (moved < -8) stuckWindows = 0
            return
        }
        if (moved >= 6) {
            stuckWindows = 0
            return
        }
        stuckWindows++
        jumpHold = 12
        input.jump = true
        when {
            // ⚠️ 第三轮放宽：原 4 次就 reset 本关，真机上表现为「一直从头打、过不了关」。
            //    先给足机动机会，确实六次还不动才重置。
            stuckWindows >= 6 -> {
                stuckWindows = 0
                sim.restartLevel()
            }
            stuckWindows >= 2 -> retreatFrames = 40
            else -> Unit
        }
    }
}
