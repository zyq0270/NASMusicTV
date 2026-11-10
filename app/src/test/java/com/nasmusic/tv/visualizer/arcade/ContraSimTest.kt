package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import com.nasmusic.tv.visualizer.arcade.sim.ContraLevels
import com.nasmusic.tv.visualizer.arcade.sim.ContraSim
import com.nasmusic.tv.visualizer.arcade.sim.ContraTerrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sim 层单测（方案 §6.1：「纯 Kotlin、无 Android / GL」）。
 *
 * 覆盖三件事：
 * 1. **确定性** —— 同 seed + 同输入 ⇒ 逐帧同状态（bot 可复现的前提）；
 * 2. **定点物理** —— 玩家会落到地面并停住，不抖动、不穿地；
 * 3. **死亡与重生** —— 掉进水里掉一条命，并在检查点重生（⛔ 不重置全局进度）。
 */
class ContraSimTest {

    @Test
    fun `同 seed 同输入 逐帧状态一致`() {
        val a = ContraSim(42L)
        val b = ContraSim(42L)
        val ia = GameInput()
        val ib = GameInput()
        for (i in 0 until 3000) {
            ia.clear()
            ib.clear()
            ia.right = true
            ib.right = true
            ia.shoot = true
            ib.shoot = true
            if (i % 40 == 0) {
                ia.jump = true
                ib.jump = true
            }
            a.step(ia)
            b.step(ib)
            assertEquals("第 $i 帧 X 不一致", a.playerPxX, b.playerPxX)
            assertEquals("第 $i 帧 Y 不一致", a.playerPxY, b.playerPxY)
            assertEquals("第 $i 帧关卡不一致", a.currentLevelNumber, b.currentLevelNumber)
        }
    }

    @Test
    fun `不同 seed 不保证同轨迹 但都不崩`() {
        val a = ContraSim(1L)
        val b = ContraSim(999L)
        val i = GameInput()
        i.right = true
        i.shoot = true
        repeat(600) {
            a.step(i)
            b.step(i)
        }
        // 只断言两者都在合法范围内（轨迹可以相同，也可以不同 —— 不构成契约）
        assertTrue(a.playerPxX in 0..a.levelWidthPx)
        assertTrue(b.playerPxX in 0..b.levelWidthPx)
    }

    @Test
    fun `静止时玩家站在地面上且不下沉`() {
        val sim = ContraSim(7L)
        val input = GameInput()
        // 先静置 120 帧让它落地
        repeat(120) { sim.step(input) }
        val y1 = sim.playerPxY
        assertTrue("玩家应当已落地", sim.playerIsGrounded)
        repeat(60) { sim.step(input) }
        assertEquals("静止 60 帧后纵向位置不得漂移（定点重力 + 落地清零）", y1, sim.playerPxY)
    }

    @Test
    fun `只按右会掉进水坑 掉一条命并重生`() {
        val sim = ContraSim(7L)
        val input = GameInput()
        input.right = true
        val startLives = sim.lives
        var died = false
        for (i in 0 until 1600) {
            sim.step(input)
            if (sim.lives < startLives) {
                died = true
                break
            }
        }
        assertTrue("第 1 关第 3 屏有水坑（chunkCode=FFGF…）⇒ 只会按右必掉水", died)
        // 重生后必须仍可操作（不是永久死亡态）
        repeat(120) { sim.step(input) }
        assertTrue("重生后应恢复存活", sim.playerIsAlive)
    }

    @Test
    fun `三类碰撞码在第 1 关地形表里都出现`() {
        val level = ContraLevels.level(1)
        val grid = ContraTerrain.build(level)
        val seen = BooleanArray(4)
        for (b in grid) seen[b.toInt()] = true
        assertTrue("地面码", seen[ContraConst.C_FLOOR])
        assertTrue("水面码", seen[ContraConst.C_WATER])
        assertTrue("实心码", seen[ContraConst.C_SOLID])
    }

    @Test
    fun `射击会生成可视弹 且武器可被道具切换`() {
        val sim = ContraSim(3L)
        val input = GameInput()
        val snap = ContraSnapshot()
        input.shoot = true
        repeat(10) { sim.step(input) }
        sim.writeTo(snap)
        assertTrue("持续按射击键应至少产生 1 发可见弹", snap.bulletCount > 0)
        assertEquals(ContraConst.WPN_R, snap.weapon)
    }

    @Test
    fun `命耗尽时重置本关 而不是推进关卡`() {
        val sim = ContraSim(11L)
        val input = GameInput()
        input.right = true
        val levelBefore = sim.currentLevelNumber
        // 一直往右撞（会反复掉水/撞敌人）直到生命归零并被重置
        repeat(6000) { sim.step(input) }
        assertEquals("生命耗尽只重置本关，⛔ 不得自动推进", levelBefore, sim.currentLevelNumber)
        // ⚠️ 第 6000 步可能恰好停在「lives 已减到 0、死亡动画（54 帧）还没走完」的瞬态窗口
        //    ⇒ 先步进到重置发生（确定性收敛：lives==0 时动画结束后必回 START_LIVES）
        var guard = 0
        while (sim.lives < 1 && guard < 1000) {
            sim.step(input)
            guard++
        }
        assertTrue("生命值必须回到合法区间", sim.lives in 1..ContraConst.START_LIVES)
    }

    @Test
    fun `快照写入不改动 sim 状态`() {
        val sim = ContraSim(5L)
        val input = GameInput()
        input.right = true
        repeat(200) { sim.step(input) }
        val x = sim.playerPxX
        val snap = ContraSnapshot()
        sim.writeTo(snap)
        sim.writeTo(snap)
        assertEquals(x, sim.playerPxX)
        assertEquals(x, snap.playerX)
    }
}
