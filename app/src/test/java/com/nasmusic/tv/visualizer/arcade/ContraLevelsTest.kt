package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.sim.ContraConst
import com.nasmusic.tv.visualizer.arcade.sim.ContraLevels
import com.nasmusic.tv.visualizer.arcade.sim.ContraTerrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关卡数据单测（方案 §6.3「逐关验收清单」的可自动化部分）。
 *
 * ⛔ 不验「像不像魂斗罗」（无基准，需所有者本人看），只验**结构完整性**：
 * 地形非空、屏数达标、三类碰撞码齐全、出生点可站、boss 在关卡内、波次够数。
 */
class ContraLevelsTest {

    @Test
    fun `一共 8 关 编号连续`() {
        assertEquals(8, ContraLevels.all.size)
        ContraLevels.all.forEachIndexed { i, level ->
            assertEquals(i + 1, level.number)
        }
    }

    @Test
    fun `每关屏数达标 第 1 关 13 屏 其余至少 8 屏`() {
        assertEquals("第 1 关对齐上游 13 屏的布局量级", 13, ContraLevels.level(1).screens)
        for (i in 2..8) {
            val l = ContraLevels.level(i)
            assertTrue("第 $i 关屏数 ${l.screens} 少于 8", l.screens >= 8)
        }
    }

    @Test
    fun `每关地形表非空且三类碰撞码齐全`() {
        for (i in 1..8) {
            val level = ContraLevels.level(i)
            val grid = ContraTerrain.build(level)
            val cols = ContraTerrain.cols(level)
            assertEquals(cols * ContraConst.ROWS, grid.size)
            val seen = IntArray(4)
            for (b in grid) seen[b.toInt()]++
            assertTrue("第 $i 关没有地面码", seen[ContraConst.C_FLOOR] > 0)
            assertTrue("第 $i 关没有水面码", seen[ContraConst.C_WATER] > 0)
            assertTrue("第 $i 关没有实心码", seen[ContraConst.C_SOLID] > 0)
        }
    }

    @Test
    fun `出生点与每个检查点所在列都可站立`() {
        for (i in 1..8) {
            val level = ContraLevels.level(i)
            val grid = ContraTerrain.build(level)
            val cols = ContraTerrain.cols(level)
            val row = ContraTerrain.groundRowAt(grid, cols, 1)
            assertTrue("第 $i 关出生列（col=1）不可站立", row in 0 until ContraConst.ROWS - 1)
        }
    }

    @Test
    fun `boss 位置在关卡内且在右侧墙之前`() {
        for (i in 1..8) {
            val level = ContraLevels.level(i)
            val wallX = (level.widthPx - 2 * ContraConst.TILE)
            assertTrue("第 $i 关 boss x=${level.bossX} 越界", level.bossX in 128 until level.widthPx)
            assertTrue("第 $i 关 boss 应站在右墙之前", level.bossX < wallX)
            assertTrue("第 $i 关 boss 血量应为正", level.bossHp > 0)
        }
    }

    @Test
    fun `每关波次不少于 6 且兵种合法`() {
        for (i in 1..8) {
            val level = ContraLevels.level(i)
            assertTrue("第 $i 关波次 ${level.waves.size} 少于 6（方案 §6.3）", level.waves.size >= 6)
            level.waves.forEach { w ->
                assertTrue("兵种越界：${w.type}", w.type in 0 until ContraConst.E_TYPE_COUNT)
                assertTrue("波次 x 越界：${w.x}", w.x in 0..level.widthPx)
            }
        }
    }

    @Test
    fun `地形生成是纯函数 同关两次结果逐字节一致`() {
        val level = ContraLevels.level(3)
        val a = ContraTerrain.build(level)
        val b = ContraTerrain.build(level)
        assertTrue("同关地形必须可复现（无随机）", a.contentEquals(b))
    }

    @Test
    fun `8 关调色板彼此可区分`() {
        val skies = ContraLevels.all.map { it.palette.skyTop }
        assertEquals("每关天空顶色应互不相同", 8, skies.distinct().size)
        val grounds = ContraLevels.all.map { it.palette.groundTop }
        assertEquals("每关地面顶色应互不相同", 8, grounds.distinct().size)
        // 相邻关必须有可见差异（方案 §6.3 最后一行）
        for (i in 0 until 7) {
            assertNotEquals(
                "第 ${i + 1} 关与第 ${i + 2} 关调色板太接近",
                ContraLevels.all[i].palette.groundTop,
                ContraLevels.all[i + 1].palette.groundTop,
            )
        }
    }

    @Test
    fun `关卡区块序列长度与屏数一致`() {
        for (i in 1..8) {
            val level = ContraLevels.level(i)
            assertEquals("第 $i 关 chunk 长度不符", level.screens, level.chunks.size)
        }
    }
}
