package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.bot.ContraBot
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自动玩 bot 单测（方案 §4.10 D2 / D3，**不可豁免项**）。
 *
 * ⛔ 判据是「**8 关全部推进到位**」而不是「画面能画」：
 * 按 J1「要能够自动玩」，只跑完前 2~3 关一律视为未达 DoD。
 */
class ContraBotTest {

    private class RunResult(
        val steps: Int,
        val order: List<Int>,
        val finalX: Int,
    )

    private fun run(seed: Long, maxSteps: Int): RunResult {
        val sim = ContraSim(seed)
        val bot = ContraBot()
        val input = GameInput()
        val order = ArrayList<Int>(10)
        order.add(sim.currentLevelNumber)
        var steps = 0
        while (steps < maxSteps) {
            bot.decide(sim, input)
            sim.step(input)
            steps++
            val now = sim.currentLevelNumber
            if (now != order.last()) order.add(now)
            // 记录到第 9 项 ⇒ 已经走完 8 个关卡切换（第 8 关之后回到第 1 关）
            if (order.size >= 9) break
        }
        return RunResult(steps, order, sim.playerPxX)
    }

    @Test
    fun `bot 能连续推进全部 8 关并回到第 1 关`() {
        val r = run(2026L, 600_000)
        val detail = "步数=${r.steps} 关卡序列=${r.order}"
        for (i in 1..8) {
            assertTrue("第 $i 关从未抵达（$detail）", r.order.contains(i))
        }
        assertEquals("第 8 关之后应回到第 1 关（循环不断，$detail）", 1, r.order.last())
    }

    @Test
    fun `同 seed 的 bot 轨迹逐帧可复现`() {
        val a = run(77L, 20_000)
        val b = run(77L, 20_000)
        assertEquals(a.steps, b.steps)
        assertEquals(a.finalX, b.finalX)
        assertEquals(a.order, b.order)
    }

    /**
     * D3：自动演示「持续运行 ≥ 30 分钟不卡死」的逻辑等价物
     * = 108000 步（30 min @ 60 fps）内 bot 一直在动、关卡一直在推进。
     */
    @Test
    fun `长跑 108000 步不卡死 且进度持续推进`() {
        val sim = ContraSim(2026L)
        val bot = ContraBot()
        val input = GameInput()
        val levelHits = IntArray(9)
        var progressWindows = 0
        var windowStartX = sim.playerPxX
        var windowLevel = sim.currentLevelNumber
        var windowSteps = 0
        for (i in 0 until 108_000) {
            bot.decide(sim, input)
            sim.step(input)
            levelHits[sim.currentLevelNumber]++
            windowSteps++
            if (windowSteps >= 600) {
                val levelChanged = sim.currentLevelNumber != windowLevel
                if (levelChanged || abs(sim.playerPxX - windowStartX) > 40) progressWindows++
                windowStartX = sim.playerPxX
                windowLevel = sim.currentLevelNumber
                windowSteps = 0
            }
        }
        // 180 个窗口里至少一半有进展（死亡重生会短暂回退，所以不要求 100%）
        assertTrue("长跑期间进度推进窗口过少（$progressWindows/180）⇒ 疑似卡死", progressWindows >= 90)
        // 30 分钟里应至少走过 6 个不同关卡（剩下的关卡在下一个 30 分钟里继续）
        assertTrue(
            "长跑期间只走过 ${levelHits.count { it > 0 }} 个关卡 ⇒ 推进过慢",
            levelHits.count { it > 0 } >= 6,
        )
    }
}
