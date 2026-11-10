package com.nasmusic.tv.visualizer.arcade.sim

import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.arcade.ContraSnapshot
import com.nasmusic.tv.visualizer.arcade.GameInput
import com.nasmusic.tv.visualizer.arcade.sim.ContraConst.fp

/**
 * 魂斗罗 sim 层（方案 §4.4 W3）。
 *
 * ## 移植关系（⛔ 必须如实区分，见方案 §0.1 / §3.4）
 * - **移植件**：状态机形状与固定步 / 8.8 定点 / 三类碰撞码的**组织方式**参照上游
 *   `MikeWuPing/UEFI_Contra`（无 LICENSE 文件，README 自称 MIT）的逻辑侧
 *   （`player.c` / `enemy.c` / `bullet.c` / `weapon.c` / `level.c` / `game_state.c`）。
 * - **自建件**：第 1~8 关的地形数据、敌人布点、boss、过关推进、全部渲染层
 *   —— 上游**只有第 1 关有地形**且那套数据是 ROM 提取物（⛔ 不搬字节，方案 §2.3）。
 *
 * ## 与上游的三处**有意偏离**（逐条写清，便于日后审计）
 * 1. 上游**没有**过关推进（`LevelInit` 两处调用都是 `LevelInit(0)`，无 `NextLevel`）
 *    ⇒ 本层的 [advanceLevel] 完全自建。
 * 2. 上游 `enemy.c` 有一处浮点；本层**全部改为定点**（⛔ 不引入 `Float`）。
 * 3. 上游 demo 是 `phase = gIntroTimer % 180` 的**假自玩**（不会过关）
 *    ⇒ 自动玩由 [com.nasmusic.tv.visualizer.arcade.bot.ContraBot] 自建。
 *
 * ## 零分配
 * 全部实体是**定长数组 + 槽位**；[step] 内不构造对象、不用迭代器、不拆箱。
 * 唯一的例外是换关时的 `BooleanArray(waves.size)`（每关一次，不在帧循环里）。
 */
class ContraSim(seed: Long = 0L) {

    // ── 随机源：复用 `VisualizerRandom`（LCG，零分配；方案 §4.9）───────────────
    private var rnd: VisualizerRandom = VisualizerRandom(normalizeSeed(seed))

    // ── 关卡 ──
    var levelIndex: Int = 0
        private set
    private var level: ContraLevel = ContraLevels.all[0]
    private var grid: ByteArray = ContraTerrain.build(level)
    private var cols: Int = ContraTerrain.cols(level)

    val levelWidthPx: Int get() = level.widthPx
    val currentLevelNumber: Int get() = level.number
    val currentLevelName: String get() = level.name
    val currentPalette: ContraPalette get() = level.palette

    // ── 玩家 ──
    private var px: Int = 0
    private var py: Int = 0
    private var vx: Int = 0
    private var vy: Int = 0
    private var onGround: Boolean = false
    private var facing: Int = 1
    private var aimUp: Boolean = false
    private var crouch: Boolean = false
    private var animTick: Int = 0
    private var firing: Int = 0
    private var fireCooldown: Int = 0
    private var invuln: Int = 0
    private var alive: Boolean = true
    private var deathTimer: Int = 0
    private var respawnX: Int = 24
    private var maxScreen: Int = 0

    var lives: Int = ContraConst.START_LIVES
        private set
    var weapon: Int = ContraConst.WPN_R
        private set
    var score: Int = 0
        private set

    // ── 我方子弹 ──
    private val bX = IntArray(ContraConst.MAX_BULLETS)
    private val bY = IntArray(ContraConst.MAX_BULLETS)
    private val bVx = IntArray(ContraConst.MAX_BULLETS)
    private val bVy = IntArray(ContraConst.MAX_BULLETS)
    private val bKind = IntArray(ContraConst.MAX_BULLETS)
    private val bLife = IntArray(ContraConst.MAX_BULLETS)
    private val bPhase = IntArray(ContraConst.MAX_BULLETS)

    // ── 敌方子弹 ──
    private val ebX = IntArray(ContraConst.MAX_BULLETS)
    private val ebY = IntArray(ContraConst.MAX_BULLETS)
    private val ebVx = IntArray(ContraConst.MAX_BULLETS)
    private val ebVy = IntArray(ContraConst.MAX_BULLETS)
    private val ebLife = IntArray(ContraConst.MAX_BULLETS)

    // ── 敌兵 / 道具 ──
    private val eActive = BooleanArray(ContraConst.MAX_ENEMIES)
    private val eType = IntArray(ContraConst.MAX_ENEMIES)
    private val eX = IntArray(ContraConst.MAX_ENEMIES)
    private val eY = IntArray(ContraConst.MAX_ENEMIES)
    private val eVx = IntArray(ContraConst.MAX_ENEMIES)
    private val eVy = IntArray(ContraConst.MAX_ENEMIES)
    private val eHp = IntArray(ContraConst.MAX_ENEMIES)
    private val eTimer = IntArray(ContraConst.MAX_ENEMIES)
    private val ePhase = IntArray(ContraConst.MAX_ENEMIES)

    private var waveSpawned: BooleanArray = BooleanArray(level.waves.size)

    // ── boss ──
    private var bossActive: Boolean = false
    private var bossDead: Boolean = false
    private var bossX: Int = 0
    private var bossY: Int = 0
    private var bossHp: Int = 0
    private var bossMaxHp: Int = 1
    private var bossTimer: Int = 0
    private var bossHurt: Int = 0
    private var bossBaseY: Int = 0

    // ── 循环控制 ──
    private var camX: Int = 0
    private var levelTick: Int = 0
    private var clearTimer: Int = 0

    /** 过关动画中（bot 与渲染层据此显示 `LEVEL CLEAR`） */
    val clearPending: Boolean get() = clearTimer > 0

    // ── 只读视图（bot / 渲染层） ────────────────────────────────────────────
    val playerPxX: Int get() = px shr ContraConst.FP_SHIFT
    val playerPxY: Int get() = py shr ContraConst.FP_SHIFT
    val playerIsGrounded: Boolean get() = onGround
    val playerIsAlive: Boolean get() = alive
    val playerFacing: Int get() = facing
    val playerAimUp: Boolean get() = aimUp
    val playerCrouching: Boolean get() = crouch
    val cameraX: Int get() = camX
    val bossIsActive: Boolean get() = bossActive
    val bossPxX: Int get() = bossX
    val bossPxY: Int get() = bossY
    val bossHealth: Int get() = bossHp
    val bossHealthMax: Int get() = bossMaxHp
    val enemySlots: Int get() = ContraConst.MAX_ENEMIES
    val terrainCols: Int get() = cols

    fun enemyActive(i: Int): Boolean = eActive[i]

    fun enemyTypeOf(i: Int): Int = eType[i]

    fun enemyPxX(i: Int): Int = eX[i] shr ContraConst.FP_SHIFT

    fun enemyPxY(i: Int): Int = eY[i] shr ContraConst.FP_SHIFT

    fun enemyHealth(i: Int): Int = eHp[i]

    // ── 只读视图：敌方子弹（bot 的闪避判据用） ──
    val enemyBulletSlots: Int get() = ContraConst.MAX_BULLETS

    fun enemyBulletActive(i: Int): Boolean = ebLife[i] > 0

    fun enemyBulletPxX(i: Int): Int = ebX[i] shr ContraConst.FP_SHIFT

    fun enemyBulletPxY(i: Int): Int = ebY[i] shr ContraConst.FP_SHIFT

    fun terrainCode(col: Int, row: Int): Int = ContraTerrain.codeAt(grid, cols, col, row)

    fun terrainGroundRow(col: Int): Int = ContraTerrain.groundRowAt(grid, cols, col)

    /** 当前碰撞码表（渲染层共享读，⛔ 不得写） */
    fun terrainGrid(): ByteArray = grid

    // ── 生命周期 ────────────────────────────────────────────────────────────

    fun reset(seed: Long) {
        rnd = VisualizerRandom(normalizeSeed(seed))
        lives = ContraConst.START_LIVES
        score = 0
        weapon = ContraConst.WPN_R
        loadLevel(0)
    }

    private fun normalizeSeed(seed: Long): UInt {
        val v = (seed xor (seed ushr 32)).toInt().toUInt()
        return if (v == 0u) 1u else v
    }

    private fun loadLevel(index: Int) {
        levelIndex = index
        level = ContraLevels.all[index]
        grid = ContraTerrain.build(level)
        cols = ContraTerrain.cols(level)
        waveSpawned = BooleanArray(level.waves.size)
        levelTick = 0
        clearTimer = 0
        maxScreen = 0
        bossActive = false
        bossDead = false
        bossHp = 0
        bossMaxHp = level.bossHp
        bossTimer = 0
        bossHurt = 0
        bossX = level.bossX
        bossBaseY = 0
        bossY = 0
        for (i in 0 until ContraConst.MAX_ENEMIES) eActive[i] = false
        for (i in 0 until ContraConst.MAX_BULLETS) {
            bLife[i] = 0
            ebLife[i] = 0
        }
        spawnPlayer(24)
        camX = 0
    }

    private fun advanceLevel() {
        val next = (levelIndex + 1) % ContraLevels.all.size
        lives = ContraConst.START_LIVES
        loadLevel(next)
    }

    /**
     * 重置**本关**（bot 的「卡住超时」兜底 + 生命耗尽兜底）。
     * ⛔ 不重置全局进度（关卡序号不变），符合方案 §4.5「死亡重试」口径。
     */
    fun restartLevel() {
        lives = ContraConst.START_LIVES
        loadLevel(levelIndex)
    }

    // ── 玩家 ────────────────────────────────────────────────────────────────

    private fun spawnPlayer(xPx: Int) {
        val row = groundRowNear(xPx)
        px = fp(xPx)
        py = fp(row * ContraConst.TILE - ContraConst.PLAYER_H)
        vx = 0
        vy = 0
        onGround = true
        alive = true
        invuln = ContraConst.INVULN_FRAMES
        deathTimer = 0
        facing = 1
        aimUp = false
        crouch = false
        respawnX = xPx
    }

    /** 找 xPx 附近可站立的列（水面/空洞处向左退），返回地面**行** */
    private fun groundRowNear(xPx: Int): Int {
        var col = (xPx / ContraConst.TILE).coerceIn(0, cols - 1)
        var guard = 0
        while (guard < 40) {
            val row = ContraTerrain.groundRowAt(grid, cols, col)
            if (row in 0 until ContraConst.ROWS - 1) return row
            col = (col - 1).coerceAtLeast(0)
            guard++
        }
        return level.groundRow
    }

    private fun updatePlayer(input: GameInput) {
        animTick++
        if (invuln > 0) invuln--
        if (firing > 0) firing--
        if (fireCooldown > 0) fireCooldown--

        crouch = input.down && onGround
        aimUp = input.up && !crouch

        // ⚠️ 蹲射半速推进：原实现 `crouch ⇒ speed = 0`，第三轮 bot 一旦发现低位敌人
        //    就会持续按 down 蹲在原地，形成永久死锁（离线诊断 60 万步过不了第 7 关）。
        //    ⛔ 不许改回 0；半速既保留蹲姿/蹲射语义，又不会让看门狗救不回来。
        val speed = if (crouch) ContraConst.RUN_SPEED / 2 else ContraConst.RUN_SPEED
        if (input.left && !input.right) {
            vx = -speed
            facing = -1
        } else if (input.right && !input.left) {
            vx = speed
            facing = 1
        } else {
            vx = 0
        }

        if (input.jump && onGround && !crouch) {
            vy = ContraConst.JUMP_VY
            onGround = false
        }

        vy += ContraConst.GRAVITY
        if (vy > ContraConst.MAX_FALL) vy = ContraConst.MAX_FALL

        onGround = false
        moveX(vx)
        moveY(vy)

        // 掉进水面 / 掉出画面 ⇒ 死亡
        if (boxWater(px shr ContraConst.FP_SHIFT, py shr ContraConst.FP_SHIFT,
                ContraConst.PLAYER_W, ContraConst.PLAYER_H) ||
            (py shr ContraConst.FP_SHIFT) > ContraConst.LOGICAL_H + 32
        ) {
            die()
            return
        }

        // 检查点：进入新的一屏 ⇒ 重生位置前移（⛔ 不重置全局进度，方案 §4.5）
        val screen = (px shr ContraConst.FP_SHIFT) / ContraConst.LOGICAL_W
        if (screen > maxScreen) {
            maxScreen = screen
            val cand = screen * ContraConst.LOGICAL_W + 32
            if (cand < level.widthPx - 64) respawnX = cand
        }

        if (input.shoot && fireCooldown <= 0) fire()
    }

    private fun die() {
        if (!alive || invuln > 0) return
        alive = false
        deathTimer = 54
        lives--
    }

    private fun updateDeath() {
        deathTimer--
        if (deathTimer > 0) return
        if (lives <= 0) {
            // 生命耗尽 ⇒ 重置本关（⛔ 不重置全局进度）
            lives = ContraConst.START_LIVES
            val keep = levelIndex
            loadLevel(keep)
            return
        }
        spawnPlayer(respawnX)
    }

    // ── 发射 ────────────────────────────────────────────────────────────────

    private fun fire() {
        // 斜上射：按住上瞄且仍在横向移动 ⇒ 45° 打头顶的飞行胶囊 / 高台炮台。
        // 站定只按上瞄 ⇒ 纯垂直（原著口径）。⛔ 没有这一路的话 y=96 的胶囊永远打不中。
        val diagonalUp = aimUp && vx != 0
        val muzzleX = (px shr ContraConst.FP_SHIFT) + if (facing > 0) ContraConst.PLAYER_W else -2
        val muzzleY = (py shr ContraConst.FP_SHIFT) + if (aimUp) 2 else 7
        val dirX = if (aimUp && !diagonalUp) 0 else facing
        val dirY = if (aimUp) -1 else 0

        when (weapon) {
            ContraConst.WPN_M -> {
                spawnBullet(muzzleX, muzzleY, dirX * 6 * ContraConst.FP_ONE, dirY * 6 * ContraConst.FP_ONE,
                    ContraConst.WPN_M)
                fireCooldown = 5
            }
            ContraConst.WPN_F -> {
                spawnBullet(muzzleX, muzzleY, dirX * 4 * ContraConst.FP_ONE, dirY * 4 * ContraConst.FP_ONE,
                    ContraConst.WPN_F)
                fireCooldown = 12
            }
            ContraConst.WPN_S -> {
                // 5 向散弹：站定上瞄时正上扩散；斜上瞄时把扩散整体抬 2 px/帧
                if (aimUp && !diagonalUp) {
                    spawnBullet(muzzleX, muzzleY, -3 * ContraConst.FP_ONE, -5 * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, -1 * ContraConst.FP_ONE, -6 * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, 0, -6 * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, 1 * ContraConst.FP_ONE, -6 * ContraConst.FP_ONE, ContraConst.WPN_S)
                    spawnBullet(muzzleX, muzzleY, 3 * ContraConst.FP_ONE, -5 * ContraConst.FP_ONE, ContraConst.WPN_S)
                } else {
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
                fireCooldown = 12
            }
            ContraConst.WPN_L -> {
                spawnBullet(muzzleX, muzzleY, dirX * 9 * ContraConst.FP_ONE, dirY * 9 * ContraConst.FP_ONE,
                    ContraConst.WPN_L)
                fireCooldown = 11
            }
            ContraConst.WPN_B -> {
                spawnBullet(muzzleX, muzzleY, dirX * 4 * ContraConst.FP_ONE, dirY * 4 * ContraConst.FP_ONE,
                    ContraConst.WPN_B)
                fireCooldown = 18
            }
            else -> {
                spawnBullet(muzzleX, muzzleY, dirX * 6 * ContraConst.FP_ONE, dirY * 6 * ContraConst.FP_ONE,
                    ContraConst.WPN_R)
                fireCooldown = 9
            }
        }
        // ⚠️ 上瞄是「追加一路弹道」，不是「替代平射」：单发武器（普通/机枪/激光）
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

    private fun spawnBullet(xPx: Int, yPx: Int, vxFp: Int, vyFp: Int, kind: Int) {
        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (bLife[i] > 0) continue
            bX[i] = fp(xPx)
            bY[i] = fp(yPx)
            bVx[i] = vxFp
            bVy[i] = vyFp
            bKind[i] = kind
            bLife[i] = 150
            bPhase[i] = 0
            return
        }
    }

    private fun spawnEnemyBullet(xPx: Int, yPx: Int, vxFp: Int, vyFp: Int) {
        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (ebLife[i] > 0) continue
            ebX[i] = fp(xPx)
            ebY[i] = fp(yPx)
            ebVx[i] = vxFp
            ebVy[i] = vyFp
            ebLife[i] = 210
            return
        }
    }

    private fun updateBullets() {
        val pxLeft = px shr ContraConst.FP_SHIFT
        val pxTop = py shr ContraConst.FP_SHIFT

        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (bLife[i] <= 0) continue
            bLife[i]--
            // F 弹道摆动（整数三角波，⛔ 不用浮点）
            if (bKind[i] == ContraConst.WPN_F) {
                bPhase[i] = (bPhase[i] + 3) and 0xFF
                val quad = (bPhase[i] shr 4) and 3
                bVy[i] = when (quad) {
                    0 -> -ContraConst.FP_ONE
                    1 -> 0
                    2 -> ContraConst.FP_ONE
                    else -> 0
                }
            }
            bX[i] += bVx[i]
            bY[i] += bVy[i]
            val bx = bX[i] shr ContraConst.FP_SHIFT
            val by = bY[i] shr ContraConst.FP_SHIFT
            if (bx < -16 || bx > level.widthPx + 16 || by < -16 || by > ContraConst.LOGICAL_H + 16) {
                bLife[i] = 0
                continue
            }
            // 撞地形
            val code = ContraTerrain.codeAt(grid, cols, bx shr 4, by shr 4)
            if (code == ContraConst.C_SOLID || code == ContraConst.C_FLOOR) {
                bLife[i] = 0
                continue
            }
            // 撞 boss
            if (bossActive && overlaps(bx, by, 4, 4, bossX, bossY, BOSS_W, BOSS_H)) {
                bossHp -= damageOf(bKind[i])
                bossHurt = 6
                bLife[i] = 0
                if (bossHp <= 0) killBoss()
                continue
            }
            // 撞敌兵（道具不可击毁）
            var hit = false
            for (e in 0 until ContraConst.MAX_ENEMIES) {
                if (!eActive[e] || eType[e] == ContraConst.E_ITEM) continue
                val ex = eX[e] shr ContraConst.FP_SHIFT
                val ey = eY[e] shr ContraConst.FP_SHIFT
                val w = enemyWidth(eType[e])
                val h = enemyHeight(eType[e])
                if (!overlaps(bx, by, 4, 4, ex, ey, w, h)) continue
                eHp[e] -= damageOf(bKind[i])
                if (eHp[e] <= 0) killEnemy(e)
                bLife[i] = 0
                hit = true
                break
            }
            if (hit) continue
        }

        // 敌方子弹：移动 + 命中玩家
        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (ebLife[i] <= 0) continue
            ebLife[i]--
            ebX[i] += ebVx[i]
            ebY[i] += ebVy[i]
            val bx = ebX[i] shr ContraConst.FP_SHIFT
            val by = ebY[i] shr ContraConst.FP_SHIFT
            if (bx < camX - 32 || bx > camX + ContraConst.LOGICAL_W + 32 ||
                by < -16 || by > ContraConst.LOGICAL_H + 16
            ) {
                ebLife[i] = 0
                continue
            }
            val code = ContraTerrain.codeAt(grid, cols, bx shr 4, by shr 4)
            if (code == ContraConst.C_SOLID || code == ContraConst.C_FLOOR) {
                ebLife[i] = 0
                continue
            }
            if (alive && invuln <= 0 &&
                overlaps(bx, by, 4, 4, pxLeft, pxTop, ContraConst.PLAYER_W, ContraConst.PLAYER_H)
            ) {
                ebLife[i] = 0
                die()
            }
        }
    }

    private fun damageOf(kind: Int): Int = when (kind) {
        ContraConst.WPN_B -> 3
        ContraConst.WPN_L -> 2
        ContraConst.WPN_S -> 1
        else -> 1
    }

    // ── 敌兵 ────────────────────────────────────────────────────────────────

    private fun enemyWidth(type: Int): Int = when (type) {
        ContraConst.E_BOSS -> BOSS_W
        ContraConst.E_CAPSULE -> 14
        ContraConst.E_TURRET -> 14
        ContraConst.E_PILLBOX -> 14
        ContraConst.E_ITEM -> 12
        else -> 12
    }

    private fun enemyHeight(type: Int): Int = when (type) {
        ContraConst.E_BOSS -> BOSS_H
        ContraConst.E_CAPSULE -> 12
        ContraConst.E_PILLBOX -> 14
        ContraConst.E_ITEM -> 12
        else -> 16
    }

    private fun spawnEnemy(type: Int, xPx: Int, yPx: Int) {
        for (i in 0 until ContraConst.MAX_ENEMIES) {
            if (eActive[i]) continue
            eActive[i] = true
            eType[i] = type
            eX[i] = fp(xPx)
            eY[i] = fp(yPx)
            eVx[i] = 0
            eVy[i] = 0
            eHp[i] = when (type) {
                ContraConst.E_SOLDIER -> 1
                ContraConst.E_SNIPER -> 1
                ContraConst.E_TURRET -> 3
                ContraConst.E_CAPSULE -> 3
                ContraConst.E_PILLBOX -> 3
                ContraConst.E_ITEM -> 1
                else -> 1
            }
            eTimer[i] = 60 + rnd.nextIndex(40)
            ePhase[i] = rnd.nextIndex(64)
            if (type == ContraConst.E_SOLDIER) eVx[i] = -ContraConst.RUN_SPEED / 3
            return
        }
    }

    private fun killEnemy(i: Int) {
        val type = eType[i]
        score += when (type) {
            ContraConst.E_SOLDIER -> 100
            ContraConst.E_SNIPER -> 150
            ContraConst.E_TURRET -> 300
            ContraConst.E_CAPSULE -> 200
            ContraConst.E_PILLBOX -> 400
            else -> 50
        }
        // 飞行舱被打掉 ⇒ 掉一个武器道具
        if (type == ContraConst.E_CAPSULE) {
            spawnEnemy(ContraConst.E_ITEM, eX[i] shr ContraConst.FP_SHIFT, eY[i] shr ContraConst.FP_SHIFT)
        }
        eActive[i] = false
    }

    private fun updateEnemies() {
        val playerLeft = px shr ContraConst.FP_SHIFT
        val playerTop = py shr ContraConst.FP_SHIFT

        for (i in 0 until ContraConst.MAX_ENEMIES) {
            if (!eActive[i]) continue
            val type = eType[i]
            val ex = eX[i] shr ContraConst.FP_SHIFT
            val ey = eY[i] shr ContraConst.FP_SHIFT
            eTimer[i]--

            // 离屏清理（道具与 boss 不参与）
            if (type != ContraConst.E_ITEM && ex < camX - 120) {
                eActive[i] = false
                continue
            }

            when (type) {
                ContraConst.E_SOLDIER -> {
                    val w = enemyWidth(type)
                    val h = enemyHeight(type)
                    // 撞墙掉头
                    val probeX = if (eVx[i] > 0) ex + w + 1 else ex - 1
                    val blocked = isSolidPx(probeX, ey + h - 6) || isSolidPx(probeX, ey + 4)
                    if (blocked) eVx[i] = -eVx[i]
                    eX[i] += eVx[i]
                    eVy[i] += ContraConst.GRAVITY
                    if (eVy[i] > ContraConst.MAX_FALL) eVy[i] = ContraConst.MAX_FALL
                    val ny = eY[i] + eVy[i]
                    if (boxSolidPx(eX[i] shr ContraConst.FP_SHIFT, ny shr ContraConst.FP_SHIFT, w, h)) {
                        eVy[i] = 0
                    } else {
                        eY[i] = ny
                    }
                }
                ContraConst.E_SNIPER -> {
                    if (eTimer[i] <= 0 && ex < camX + ContraConst.LOGICAL_W && ex > camX - 8) {
                        eTimer[i] = 110
                        val mx = ex + 6
                        val my = ey + 4
                        val ax = playerLeft - mx
                        val ay = playerTop - my
                        // 归一化到「约 2 px/帧」的定点速度（整数近似，⛔ 不用浮点）
                        val scale = maxOf(1, (absInt(ax) + absInt(ay)) / 2)
                        spawnEnemyBullet(mx, my, ax * 2 * ContraConst.FP_ONE / scale,
                            ay * 2 * ContraConst.FP_ONE / scale)
                    }
                }
                ContraConst.E_TURRET -> {
                    if (eTimer[i] <= 0 && absInt(ex - playerLeft) < 220) {
                        eTimer[i] = 150
                        // 两发而不是四发：四向连射在近距离是"无解"伤害
                        //（实测：贴脸炮台 4 向齐发 ⇒ bot 站桩清敌必死，方案 §4.5 的固定死点）
                        spawnEnemyBullet(ex + 6, ey + 4, -2 * ContraConst.FP_ONE, 0)
                        spawnEnemyBullet(ex + 6, ey + 4, -2 * ContraConst.FP_ONE, -2 * ContraConst.FP_ONE)
                    }
                }
                ContraConst.E_CAPSULE -> {
                    ePhase[i] = (ePhase[i] + 2) and 0xFF
                    eY[i] += if ((ePhase[i] shr 5) and 1 == 0) -16 else 16
                    eX[i] += if (ex > playerLeft) -ContraConst.FP_ONE / 2 else ContraConst.FP_ONE / 2
                }
                ContraConst.E_PILLBOX -> {
                    if (eTimer[i] <= 0 && absInt(ex - playerLeft) < 240) {
                        // ⚠️ 第四轮：3 发抛物雨在贴脸时是「无解伤害」—— 弹幕落下正好罩住
                        //    站桩清敌的玩家（离线诊断：平均存活仅 7.8 s，第 5 关 60 万步死 1005 次）。
                        //    砍成 2 发侧向 + 冷却 150，与第一轮「炮台四向 → 两发」同一调参先例。
                        eTimer[i] = 150
                        spawnEnemyBullet(ex + 6, ey + 2, -ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                        spawnEnemyBullet(ex + 6, ey + 2, ContraConst.FP_ONE, -3 * ContraConst.FP_ONE)
                    }
                }
                ContraConst.E_ITEM -> {
                    ePhase[i] = (ePhase[i] + 1) and 0xFF
                    // ⚠️ 道具必须落地：它在胶囊被击破处（y≈96/64）生成，早期版本没有重力
                    //    ⇒ 悬在半空、玩家在地面永远碰不到（真机反馈「有奖励也不会打下来吃掉」）。
                    val floorY = groundRowNear(ex + 6) * ContraConst.TILE - enemyHeight(type)
                    if (ey < floorY) {
                        eY[i] += 2 * ContraConst.FP_ONE
                        if ((eY[i] shr ContraConst.FP_SHIFT) > floorY) eY[i] = fp(floorY)
                    } else {
                        // 落地后轻微上下浮动（原著道具的闪烁感），⛔ 不再往画面外掉
                        eY[i] = fp(floorY) + if (((ePhase[i] shr 4) and 1) == 0) 0 else -ContraConst.FP_ONE
                    }
                    val ly = eY[i] shr ContraConst.FP_SHIFT
                    if (alive && overlaps(ex, ly, enemyWidth(type), enemyHeight(type),
                            playerLeft, playerTop, ContraConst.PLAYER_W, ContraConst.PLAYER_H)
                    ) {
                        // 吃到奖励 ⇒ 升到散弹；已是散弹不降级（原「循环轮换」会越吃越差）
                        if (weapon != ContraConst.WPN_S) weapon = ContraConst.WPN_S
                        score += 100
                        eActive[i] = false
                    }
                }
            }

            // 接触伤害（道具除外）
            if (alive && invuln <= 0 && eActive[i] && type != ContraConst.E_ITEM) {
                if (overlaps(eX[i] shr ContraConst.FP_SHIFT, eY[i] shr ContraConst.FP_SHIFT,
                        enemyWidth(type), enemyHeight(type),
                        playerLeft, playerTop, ContraConst.PLAYER_W, ContraConst.PLAYER_H)
                ) {
                    die()
                }
            }
        }
    }

    private fun updateSpawner() {
        val waves = level.waves
        val spawnAhead = camX + ContraConst.LOGICAL_W + 24
        for (i in 0 until waves.size) {
            if (waveSpawned[i]) continue
            val w = waves[i]
            if (w.x > spawnAhead) continue
            if (w.x < camX - 64) {
                waveSpawned[i] = true // 已经过去，不再补
                continue
            }
            waveSpawned[i] = true
            val y = if (w.y < 0) {
                val row = groundRowNear(w.x)
                row * ContraConst.TILE - enemyHeight(w.type)
            } else {
                w.y
            }
            spawnEnemy(w.type, w.x, y)
        }
    }

    // ── boss ────────────────────────────────────────────────────────────────

    private fun spawnBoss() {
        bossActive = true
        bossHp = bossMaxHp
        bossX = level.bossX
        bossTimer = 0
        bossHurt = 0
        bossBaseY = level.groundRow * ContraConst.TILE - BOSS_H
        bossY = bossBaseY
    }

    private fun updateBoss() {
        if (!bossActive) {
            if (!bossDead && playerPxX > level.bossX - 200) spawnBoss()
            return
        }
        bossTimer++
        if (bossHurt > 0) bossHurt--
        // 上下浮动（整数三角波）
        val wave = (bossTimer shr 3) and 15
        val off = if (wave < 8) wave * 2 else (15 - wave) * 2
        bossY = bossBaseY - off

        if (bossTimer % 80 == 0) {
            val mx = bossX - 4
            val my = bossY + 10
            val ax = playerPxX - mx
            val ay = playerPxY - my
            val scale = maxOf(1, (absInt(ax) + absInt(ay)) / 2)
            spawnEnemyBullet(mx, my, ax * 2 * ContraConst.FP_ONE / scale, ay * 2 * ContraConst.FP_ONE / scale)
            spawnEnemyBullet(mx, my, -3 * ContraConst.FP_ONE, -2 * ContraConst.FP_ONE)
            spawnEnemyBullet(mx, my, -3 * ContraConst.FP_ONE, 2 * ContraConst.FP_ONE)
        }

        // 玩家贴近 boss 也会受伤
        if (alive && invuln <= 0 && overlaps(playerPxX, playerPxY,
                ContraConst.PLAYER_W, ContraConst.PLAYER_H, bossX, bossY, BOSS_W, BOSS_H)
        ) {
            die()
        }
    }

    private fun killBoss() {
        bossActive = false
        bossDead = true
        score += 1000
        clearTimer = ContraConst.CLEAR_FRAMES
        // 清掉残余敌方子弹，避免过关动画里被追死
        for (i in 0 until ContraConst.MAX_BULLETS) ebLife[i] = 0
    }

    // ── 主循环 ──────────────────────────────────────────────────────────────

    fun step(input: GameInput) {
        levelTick++
        if (clearTimer > 0) {
            clearTimer--
            if (clearTimer == 0) advanceLevel()
            return
        }
        if (!alive) {
            updateDeath()
            return
        }
        updatePlayer(input)
        if (alive) {
            updateBullets()
            updateEnemies()
            updateBoss()
            updateSpawner()
        }
        // 相机跟随（只跟随前进方向，避免镜头抖动）
        val target = playerPxX - 96
        camX = target.coerceIn(0, maxOf(0, level.widthPx - ContraConst.LOGICAL_W))
    }

    // ── 碰撞工具 ────────────────────────────────────────────────────────────

    private fun isSolidPx(xPx: Int, yPx: Int): Boolean {
        val code = ContraTerrain.codeAt(grid, cols, xPx shr 4, yPx shr 4)
        return code == ContraConst.C_FLOOR || code == ContraConst.C_SOLID
    }

    private fun boxSolidPx(left: Int, top: Int, w: Int, h: Int): Boolean =
        isSolidPx(left, top) ||
            isSolidPx(left + w - 1, top) ||
            isSolidPx(left, top + h - 1) ||
            isSolidPx(left + w - 1, top + h - 1) ||
            isSolidPx(left + w / 2, top) ||
            isSolidPx(left + w / 2, top + h - 1) ||
            isSolidPx(left, top + h / 2) ||
            isSolidPx(left + w - 1, top + h / 2)

    private fun boxWater(left: Int, top: Int, w: Int, h: Int): Boolean {
        val cxm = left + w / 2
        val codeFeet = ContraTerrain.codeAt(grid, cols, cxm shr 4, (top + h - 2) shr 4)
        if (codeFeet == ContraConst.C_WATER) return true
        val codeMid = ContraTerrain.codeAt(grid, cols, cxm shr 4, (top + h / 2) shr 4)
        return codeMid == ContraConst.C_WATER
    }

    private fun moveX(dxFp: Int) {
        if (dxFp == 0) return
        val dir = if (dxFp > 0) 1 else -1
        var rem = if (dxFp > 0) dxFp else -dxFp
        while (rem > 0) {
            val s = if (rem > ContraConst.FP_ONE) ContraConst.FP_ONE else rem
            val np = px + dir * s
            if (boxSolidPx(np shr ContraConst.FP_SHIFT, py shr ContraConst.FP_SHIFT,
                    ContraConst.PLAYER_W, ContraConst.PLAYER_H)
            ) {
                vx = 0
                return
            }
            px = np
            rem -= s
        }
    }

    private fun moveY(dyFp: Int) {
        if (dyFp == 0) return
        val dir = if (dyFp > 0) 1 else -1
        var rem = if (dyFp > 0) dyFp else -dyFp
        while (rem > 0) {
            val s = if (rem > ContraConst.FP_ONE) ContraConst.FP_ONE else rem
            val np = py + dir * s
            if (boxSolidPx(px shr ContraConst.FP_SHIFT, np shr ContraConst.FP_SHIFT,
                    ContraConst.PLAYER_W, ContraConst.PLAYER_H)
            ) {
                if (dir > 0) onGround = true
                vy = 0
                return
            }
            py = np
            rem -= s
        }
    }

    private fun overlaps(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int,
    ): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

    private fun absInt(v: Int): Int = if (v < 0) -v else v

    // ── 快照 ────────────────────────────────────────────────────────────────

    /** 把当前状态写入**复用**快照（原地写字段，⛔ 不新建对象） */
    fun writeTo(s: ContraSnapshot) {
        s.level = level.number
        s.lives = lives
        s.score = score
        s.weapon = weapon
        s.camX = camX
        s.levelWidth = level.widthPx
        s.levelTick = levelTick

        s.bossActive = bossActive
        s.bossHp = bossHp
        s.bossMaxHp = bossMaxHp
        s.bossX = bossX
        s.bossY = bossY
        s.bossHurt = bossHurt

        s.playerX = playerPxX
        s.playerY = playerPxY
        s.playerFacing = facing
        s.playerAimUp = aimUp
        s.playerCrouch = crouch
        s.playerAlive = alive
        s.playerInvuln = invuln
        s.playerFiring = firing
        s.playerFrame = playerSpriteFrame()

        var bc = 0
        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (bLife[i] <= 0) continue
            if (bc >= ContraConst.MAX_BULLETS) break
            s.bulletX[bc] = bX[i] shr ContraConst.FP_SHIFT
            s.bulletY[bc] = bY[i] shr ContraConst.FP_SHIFT
            s.bulletKind[bc] = bKind[i]
            bc++
        }
        s.bulletCount = bc

        var ebc = 0
        for (i in 0 until ContraConst.MAX_BULLETS) {
            if (ebLife[i] <= 0) continue
            if (ebc >= ContraConst.MAX_BULLETS) break
            s.enemyBulletX[ebc] = ebX[i] shr ContraConst.FP_SHIFT
            s.enemyBulletY[ebc] = ebY[i] shr ContraConst.FP_SHIFT
            ebc++
        }
        s.enemyBulletCount = ebc

        var ec = 0
        for (i in 0 until ContraConst.MAX_ENEMIES) {
            if (!eActive[i]) continue
            if (ec >= ContraConst.MAX_ENEMIES) break
            s.enemyX[ec] = eX[i] shr ContraConst.FP_SHIFT
            s.enemyY[ec] = eY[i] shr ContraConst.FP_SHIFT
            s.enemyType[ec] = eType[i]
            s.enemyHp[ec] = eHp[i]
            s.enemyFrame[ec] = ((levelTick shr 3) + ePhase[i]) and 1
            ec++
        }
        s.enemyCount = ec

        s.terrain = grid
        s.terrainCols = cols

        val p = level.palette
        s.skyTop = p.skyTop
        s.skyBottom = p.skyBottom
        s.ground = p.ground
        s.groundTop = p.groundTop
        s.block = p.block
        s.water = p.water
        s.accent = p.accent
    }

    /**
     * 玩家贴图帧下标：
     * 0 站 / 1 跑一 / 2 跑二 / 3 跳 / 4 蹲 / 5 瞄上 / 6 死亡
     */
    private fun playerSpriteFrame(): Int {
        if (!alive) return 6
        if (crouch) return 4
        if (aimUp) return 5
        if (!onGround) return 3
        if (vx != 0) return if ((animTick shr 3) and 1 == 0) 1 else 2
        return 0
    }

    companion object {
        const val BOSS_W = 32
        const val BOSS_H = 24
    }
}
