package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.bot.ContraBot
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim

/**
 * 「魂斗罗」在街机合集里的实现（`ArcadeGame` 的唯一注册项）。
 *
 * ## 状态机（方案 §4.5，形状借自上游三常量）
 * | 状态 | 进入 | 行为 |
 * |---|---|---|
 * | `TITLE` | 启动 / demo 结束 / 确认键 | 空闲 360 帧后进 DEMO |
 * | `DEMO` | 360 帧到期 | bot 自动玩；3840 帧（≈64 s）到期回 TITLE |
 * | `CLEAR` | sim 判定过关动画中 | 仍推进 sim（sim 自己冻结战斗），只影响渲染 |
 *
 * ⛔ 上游的 attract mode 是 `phase = gIntroTimer % 180` 的**假自玩**（不会过关）
 * ⇒ 这里的 DEMO 是真 bot，跨 8 关、会打 boss、会死亡重试。
 *
 * ## 确定性
 * 同 seed + 同帧输入序列 ⇒ 同状态序列（⛔ 不读墙钟、不用系统随机）。
 */
class ContraArcadeGame : ArcadeGame {

    override val id: String = "contra"

    /** 显示名用中性词（公开仓库里不出现游戏名，方案 §5.2） */
    override val title: String = "街机合集"

    override val logicalWidth: Int = ContraConst.LOGICAL_W
    override val logicalHeight: Int = ContraConst.LOGICAL_H

    private val sim = ContraSim(1L)
    private val bot = ContraBot()
    private val botInput = GameInput()

    private var mode: Int = ContraConst.MODE_TITLE
    private var modeTimer: Int = 0
    private var tick: Long = 0L
    private var seed: Long = 1L

    init {
        sim.reset(1L)
    }

    /** 供单测直接驱动 sim（⛔ 渲染层不要用） */
    val simulation: ContraSim get() = sim

    val currentMode: Int get() = mode

    override fun reset(seed: Long) {
        this.seed = seed
        sim.reset(seed)
        bot.reset()
        botInput.clear()
        mode = ContraConst.MODE_TITLE
        modeTimer = 0
        tick = 0L
    }

    override fun step(input: GameInput, snapshot: GameSnapshot) {
        val snap = snapshot as? ContraSnapshot ?: return
        tick++

        if (input.start) {
            toTitle()
        }

        when (mode) {
            ContraConst.MODE_TITLE -> {
                modeTimer++
                if (modeTimer >= ContraConst.TITLE_FRAMES) {
                    // 进 DEMO：从第 1 关重新开始（同 seed ⇒ 同轨迹）
                    sim.reset(seed)
                    bot.reset()
                    botInput.clear()
                    mode = ContraConst.MODE_DEMO
                    modeTimer = 0
                }
            }
            else -> {
                if (sim.clearPending || !sim.playerIsAlive) {
                    // 死亡期间 bot 不发指令；过关动画期间 sim 自己冻结战斗
                    botInput.clear()
                    sim.step(botInput)
                } else {
                    bot.decide(sim, botInput)
                    sim.step(botInput)
                }
                mode = if (sim.clearPending) ContraConst.MODE_CLEAR else ContraConst.MODE_DEMO
                modeTimer++
                // ⛔ 不再按 DEMO_FRAMES 超时回标题：那会让 8 关演示永远从第 1 关重来。
                //    自动演示持续跑、过关后由 sim 自己从第 8 关循环回第 1 关；
                //    只有遥控器确认键（input.start）才会回到标题页。
            }
        }

        sim.writeTo(snap)
        snap.mode = mode
        snap.tick = tick
        snap.clearPending = sim.clearPending
    }

    private fun toTitle() {
        mode = ContraConst.MODE_TITLE
        modeTimer = 0
        sim.reset(seed)
        bot.reset()
        botInput.clear()
    }
}
