package com.nasmusic.tv.visualizer.arcade.sim

/**
 * 魂斗罗 sim 层的**数值与编码常量**（方案 §4.4）。
 *
 * ⛔ **本包不得 import 任何 `android.*` / `javax.microedition.khronos.*`**
 * —— 照上游逻辑侧「零框架头」的形状，纯 Kotlin 才能在 JVM 单测里直跑。
 *
 * ## 定点数口径
 * 上游 `player.c` 是 **INT32 8.8 定点**（15 处 `>>8` / `<<8`），本层一一对应：
 * 位置 / 速度都用 8.8，`>> 8` 取像素、`<< 8` 回定点。⛔ 不引入 `Float`
 * （上游只有 `enemy.c` 一处浮点，本项目在该处改造为定点，见 [ContraSim] 注释）。
 */
object ContraConst {

    /** 内部逻辑分辨率（上游 256×240；⛔ 不是屏幕像素） */
    const val LOGICAL_W = 256
    const val LOGICAL_H = 240

    /** 地形格 16×16 px ⇒ 每屏 16 列 × 15 行 */
    const val TILE = 16
    const val ROWS = LOGICAL_H / TILE           // 15
    const val COLS_PER_SCREEN = LOGICAL_W / TILE // 16

    /** 固定步 60 Hz（§4.9：单帧最多追 3 步） */
    const val STEP_HZ = 60

    // ── 碰撞码（上游 `level.h` 的四类；本层只用地形，不碰实体） ──────────────
    const val C_EMPTY = 0
    const val C_FLOOR = 1
    const val C_WATER = 2
    const val C_SOLID = 3

    // ── 8.8 定点 ────────────────────────────────────────────────────────────
    const val FP_SHIFT = 8
    const val FP_ONE = 1 shl FP_SHIFT

    fun fp(px: Int): Int = px shl FP_SHIFT

    fun px(v: Int): Int = v shr FP_SHIFT

    // ── 玩家物理（px/帧，8.8） ───────────────────────────────────────────────
    /** 1.75 px/帧 ⇒ 105 px/s，与上游 1.5~2.0 的手感区间一致 */
    const val RUN_SPEED = 448
    /** -5 px/帧：约 42 帧滞空 ⇒ 水平覆盖 ≈ 73 px（可越过 2 格 = 32 px 的水坑，留 2 倍余量） */
    const val JUMP_VY = -1280
    /** 0.234 px/帧² ⇒ 落回同一高度约 38 帧 */
    const val GRAVITY = 60
    const val MAX_FALL = 1536           // 6 px/帧

    /** 玩家碰撞盒（贴图 16×20，盒略窄 ⇒ 擦边不死） */
    const val PLAYER_W = 10
    const val PLAYER_H = 22

    /** 无敌帧（重生后 90 帧 ≈ 1.5 s） */
    // ⚠️ 第四轮 90→120：旧口径下「死亡动画 54 帧 + 无敌 90 帧 = 144 帧」正好是实测的死亡循环间隔
    //    —— 重生点前方的地堡/炮台在无敌一过时立刻要人命（真机观感 = 自动玩一直在死、像弱智）。
    const val INVULN_FRAMES = 120

    /** 初始生命 */
    const val START_LIVES = 3

    /** 敌方/我方子弹槽位上限（定长，⛔ 不用 List） */
    const val MAX_BULLETS = 24
    const val MAX_ENEMIES = 16

    // ── 武器编号（上游 `weapon.c` 的 `R,M,F,S,L,B` 六种） ────────────────────
    const val WPN_R = 0     // 普通
    const val WPN_M = 1     // 机枪（连发）
    const val WPN_F = 2     // 火焰（慢、摆动）
    const val WPN_S = 3     // 散弹（5 向）
    const val WPN_L = 4     // 激光（快、细长）
    const val WPN_B = 5     // 爆弹（慢、高伤）
    const val WPN_COUNT = 6

    // ── 敌兵种（上游 `enemy.h` 的 11 种里本层用到的 7 种 + boss） ─────────────
    const val E_NONE = -1
    const val E_SOLDIER = 0
    const val E_SNIPER = 1
    const val E_TURRET = 2
    const val E_CAPSULE = 3
    const val E_PILLBOX = 4
    const val E_ITEM = 5
    const val E_BOSS = 6
    const val E_TYPE_COUNT = 7

    // ── 合集状态（[com.nasmusic.tv.visualizer.arcade.ContraSnapshot.mode]） ──
    const val MODE_TITLE = 0
    const val MODE_DEMO = 1
    const val MODE_CLEAR = 2

    /** 标题页空闲帧数（2.5 s；原 360 帧 = 6 s，真机进游戏等太久） */
    const val TITLE_FRAMES = 150

    /**
     * ⛔ **已停用**（2026-11-10 真机反馈「过不了关」的直接原因）：原「demo 3840 帧
     * ≈ 64 s 到点回标题」会强制 `sim.reset(seed)` **从第 1 关重来**；而 8 关按
     * 105 px/s 推进需要约 4 min ⇒ 电视上永远看不到过关。
     * 现在 DEMO 持续运行、在 8 关之间无限循环，只有遥控器确认键才回标题。
     * 常量保留作历史口径，⛔ 不要再拿它做超时判定。
     */
    const val DEMO_FRAMES = 3840

    /** 过关后停留帧数 */
    const val CLEAR_FRAMES = 100
}
