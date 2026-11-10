package com.nasmusic.tv.visualizer.arcade.render

/**
 * 语义槽 → **上游精灵帧名**的映射（⛔ 本项目不自绘任何精灵/瓦片）。
 *
 * 帧名即 `vermiceli/nes-contra-us` 的 `src/bank1.asm` 里 `sprite_ptr_tbl_0/1` 的标签；
 * 括号内语义照抄该表的注释：
 *
 * | 帧 | 上游注释 |
 * |---|---|
 * | `sprite_02..06` | player walking (frame 1..5) |
 * | `sprite_08/09` | player curled up (跳跃翻滚) |
 * | `sprite_0c` | player lying on ground |
 * | `sprite_16` | player aiming straight up |
 * | `sprite_17` | player prone |
 * | `sprite_1e/1f/20/21/22/23..25` | default / M / S / turret / F / L 子弹 |
 * | `sprite_26..2d` | 士兵（蹲射 / 跑 / 上瞄 / 平射 / 下瞄） |
 * | `sprite_2f..34` | S/B/F/L/R/M 武器道具 |
 * | `sprite_35..3a` | 爆炸（大 / 中 / 小 / 圆 / 环） |
 * | `sprite_40..43` | 士兵射击 / 步枪兵 |
 * | `sprite_5d..62` | boss eye |
 * | `sprite_68..6c` | base 2 boss 金属头盔 |
 *
 * 瓦片帧名 `tile_00..tile_ff` 来自 `MikeWuPing/UEFI_Contra` 的 `tiles_c.h`
 * （`gNesTiles[256][64]`，level 1 pattern table），8×8 原图按 2× 铺到 16×16 地形格。
 *
 * ⚠️ 全部是 NES ROM 提取素材，版权归原始权利人所有 ⇒ 仅私有分支本地使用。
 */
object ContraArt {

    /**
     * [ContraSnapshot.playerFrame] 0..6 对应的玩家姿态。
     * ⚠️ 上游帧默认**朝右**（`sprite_0d/0e/0f` 平射帧枪口与子弹明确在右侧，
     * `sprite_02..04` 的跑动腿也朝右）⇒ 只有 `facing < 0`（朝左）时才需要 `flipX`。
     * 2026-11-10 第二轮曾误判为朝左，把判据反写成 `> 0` ⇒ 真机上「倒着跑、人脸向后」。
     */

    val PLAYER_FRAMES: Array<String> = arrayOf(
        "sprite_02", // 0 站立（借用 walking frame 1）
        "sprite_03", // 1 跑 1
        "sprite_04", // 2 跑 2
        "sprite_08", // 3 跳跃翻滚
        "sprite_17", // 4 卧倒
        "sprite_16", // 5 竖直上瞄
        "sprite_0c", // 6 阵亡
    )

    const val SOLDIER_A = "sprite_27"   // soldier running 1
    const val SOLDIER_B = "sprite_28"   // soldier running 2
    const val SNIPER = "sprite_43"      // rifle man shooting
    const val TURRET = "sprite_40"      // soldier shooting
    const val CAPSULE = "sprite_31"     // F 武器道具（飞行胶囊）
    const val PILLBOX = "sprite_26"     // soldier crouched shooting
    const val ITEM = "sprite_33"        // R 武器道具
    const val BOSS = "sprite_6b"        // base 2 boss 金属头盔

    const val BULLET_R = "sprite_1e"    // default bullet
    const val BULLET_F = "sprite_22"    // F bullet
    const val BULLET_L = "sprite_24"    // L bullet
    const val BULLET_B = "sprite_20"    // S bullet / mortar
    const val BULLET_E = "sprite_21"    // boss turret bullet
    const val EXPLOSION = "sprite_36"   // explosion

    const val TILE_TOP = "tile_6b"      // 浓密丛林叶（地表，真图自带绿/浅绿层次）
    const val TILE_DIRT = "tile_d0"     // 深色斑驳（地下填充；⛔ 不用 tile_90，纯黑竖纹太脏）
    const val TILE_BLOCK = "tile_b8"    // 绿白网格（实心块）
    const val TILE_WATER = "tile_a1"    // 横向浅纹（顶点染蓝 ⇒ 水波）
    const val TILE_PLATFORM = "tile_b1" // 绿格砖（桥 / 平台）

    // ── 远景背景槽位（同样是上游 256 块瓦片，⛔ 不新画一张像素） ──
    const val BG_HILL = "tile_e0"     // 细碎绿色噪点 ⇒ 远山（染暗、慢视差）
    const val BG_CANOPY = "tile_6b"   // 浓密丛林叶 ⇒ 树冠
    const val BG_CANOPY_ALT = "tile_94" // 网格叶纹 ⇒ 树冠换色
    const val BG_TRUNK = "tile_64"    // 棕色竖纹 ⇒ 树干
    const val BG_BRUSH = "tile_6a"    // 竖向叶纹 ⇒ 近景灌木
    const val BG_FALL = "tile_a0"     // 白色竖条 ⇒ 瀑布（染蓝）
    const val BG_BASE = "tile_b8"     // 绿白网格 ⇒ 基地金属墙
    const val BG_BASE_ALT = "tile_b1" // 绿格砖 ⇒ 基地窗格
}
