package com.nasmusic.tv.visualizer.arcade

import com.nasmusic.tv.visualizer.arcade.sim.ContraConst

/**
 * 魂斗罗的一帧状态快照（**复用单例**，原地写字段）。
 *
 * ⛔ **零分配**：所有实体用**定长数组 + count**表示，⛔ 不用 `List<Enemy>` /
 * `data class`（方案 §4.3 / §4.4）。渲染层直接按下标读，不构造中间对象。
 *
 * ⚠️ [terrain] 是**引用共享**（直接指向 sim 的碰撞码表），不每帧拷贝：
 * 表只在换关时重建，渲染层在一帧内只读 ⇒ 安全且零消耗。
 */
class ContraSnapshot : GameSnapshot {

    // ── 合集状态 ──
    var mode: Int = ContraConst.MODE_TITLE
    var tick: Long = 0L

    /** 过关动画进行中（渲染层画 `LEVEL CLEAR`） */
    var clearPending: Boolean = false

    // ── 关卡 ──
    var level: Int = 1
    var lives: Int = ContraConst.START_LIVES
    var score: Int = 0
    var weapon: Int = ContraConst.WPN_R
    var camX: Int = 0
    var levelWidth: Int = 0

    /** 关卡内帧计数（换关清零，用于关内动画相位） */
    var levelTick: Int = 0

    // ── boss ──
    var bossActive: Boolean = false
    var bossHp: Int = 0
    var bossMaxHp: Int = 1
    var bossX: Int = 0
    var bossY: Int = 0
    var bossHurt: Int = 0

    // ── 玩家 ──
    var playerX: Int = 0
    var playerY: Int = 0
    /** 贴图帧下标（见 `ArcadeAtlas` 的玩家帧表） */
    var playerFrame: Int = 0
    var playerFacing: Int = 1
    var playerAimUp: Boolean = false
    var playerCrouch: Boolean = false
    var playerAlive: Boolean = true
    var playerInvuln: Int = 0
    var playerFiring: Int = 0

    // ── 我方可视弹 ──
    var bulletCount: Int = 0
    val bulletX: IntArray = IntArray(ContraConst.MAX_BULLETS)
    val bulletY: IntArray = IntArray(ContraConst.MAX_BULLETS)
    val bulletKind: IntArray = IntArray(ContraConst.MAX_BULLETS)

    // ── 敌方可视弹（只画，不参与 sim 判定以外的用途） ──
    var enemyBulletCount: Int = 0
    val enemyBulletX: IntArray = IntArray(ContraConst.MAX_BULLETS)
    val enemyBulletY: IntArray = IntArray(ContraConst.MAX_BULLETS)

    // ── 敌兵 / 道具 ──
    var enemyCount: Int = 0
    val enemyX: IntArray = IntArray(ContraConst.MAX_ENEMIES)
    val enemyY: IntArray = IntArray(ContraConst.MAX_ENEMIES)
    val enemyType: IntArray = IntArray(ContraConst.MAX_ENEMIES)
    val enemyHp: IntArray = IntArray(ContraConst.MAX_ENEMIES)
    val enemyFrame: IntArray = IntArray(ContraConst.MAX_ENEMIES)

    // ── 地形（共享引用） ──
    var terrain: ByteArray = EMPTY_TERRAIN
    var terrainCols: Int = 0

    // ── 调色板（由关卡定义抄入；渲染层只读） ──
    var skyTop: Int = 0xFF101820.toInt()
    var skyBottom: Int = 0xFF2E4A3A.toInt()
    var ground: Int = 0xFF6B4A2F.toInt()
    var groundTop: Int = 0xFF3F7A34.toInt()
    var block: Int = 0xFF7A7A6A.toInt()
    var water: Int = 0xFF2A6BD0.toInt()
    var accent: Int = 0xFFE8D24A.toInt()

    companion object {
        val EMPTY_TERRAIN: ByteArray = ByteArray(0)
    }
}
