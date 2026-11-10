package com.nasmusic.tv.visualizer.arcade.sim

/**
 * 一关的调色板（上游 `gLevelHeaders[n]` 的 bg / sprite 四组调色板的**自建等价物**）。
 *
 * ⚠️ 归属：上游 `level_data.h` 里的调色板是 **ROM 提取数据**，⛔ 不搬字节（方案 §2.3 / §3.4）。
 * 本类里的 8 套色值全部是**本项目自绘**的主题配色，只借用「每关一套、相邻关可见区分」这一形态。
 */
class ContraPalette(
    /** 天空上缘 */
    val skyTop: Int,
    /** 天空下缘（地平线附近） */
    val skyBottom: Int,
    /** 地面填充 */
    val ground: Int,
    /** 地面顶缘（草地 / 雪坡 / 熔岩壳） */
    val groundTop: Int,
    /** 实心块（砖 / 钢 / 岩） */
    val block: Int,
    /** 水面 / 熔岩面 */
    val water: Int,
    /** 强调色（HUD、boss 弱点） */
    val accent: Int,
)

/** 一波敌人：`y < 0` 表示「按地形贴地生成」 */
class ContraWave(val x: Int, val y: Int, val type: Int)

/**
 * 一关的静态定义（**全部自建**，第 1 关只对齐上游布局形态、不搬像素）。
 *
 * @param chunkCode 每屏一个字符的区块序列（长度必须 == [screens]）：
 *  `F` 平地 / `G` 水坑 / `U` 台阶上 / `D` 台阶下 / `P` 石柱 / `B` 断桥 /
 *  `L` 空中平台 / `S` 楼梯 / `C` 有顶走廊
 */
class ContraLevel(
    val number: Int,
    val name: String,
    val screens: Int,
    val palette: ContraPalette,
    val groundRow: Int,
    chunkCode: String,
    val bossHp: Int,
    val bossX: Int,
) {

    val chunks: IntArray = IntArray(chunkCode.length) { i ->
        when (chunkCode[i]) {
            'F' -> ContraChunk.FLAT
            'G' -> ContraChunk.GAP
            'U' -> ContraChunk.STEP_UP
            'D' -> ContraChunk.STEP_DOWN
            'P' -> ContraChunk.PILLAR
            'B' -> ContraChunk.BRIDGE
            'L' -> ContraChunk.PLATFORM
            'S' -> ContraChunk.STAIRS
            'C' -> ContraChunk.CORRIDOR
            else -> ContraChunk.FLAT
        }
    }

    /** 关卡像素宽（13 屏 = 3328 px；第 2~8 关各 11 屏 = 2816 px） */
    val widthPx: Int = screens * ContraConst.LOGICAL_W

    init {
        require(chunks.size == screens) {
            "第 $number 关的 chunkCode 长度 ${chunks.size} ≠ screens $screens"
        }
    }

    /**
     * 波次表（**自建**）：按屏轮换兵种组合，并**跳过水坑 / 断桥屏**
     * （实测：水坑起跳途中被炮台击落 = bot 的固定死点，方案 §4.5「卡住检测」兜不住）。
     *
     * ⚠️ 声明在 [chunks] 之后：属性初始化按声明顺序执行，提前引用会拿到未初始化值。
     */
    val waves: Array<ContraWave> = ContraLevels.wavesFor(number, screens, chunks)
}

/** 区块类型（与 [ContraLevel.chunkCode] 的字符一一对应） */
object ContraChunk {
    const val FLAT = 0
    const val GAP = 1
    const val STEP_UP = 2
    const val STEP_DOWN = 3
    const val PILLAR = 4
    const val BRIDGE = 5
    const val PLATFORM = 6
    const val STAIRS = 7
    const val CORRIDOR = 8
}

/**
 * 地形烘焙：把「区块序列」展开成**列主序碰撞码表**。
 *
 * 索引口径：`grid[col * ROWS + row]` ⇒ 按列取的局部性更好（滚动时逐列查询）。
 *
 * ⚠️ 第 1 关与上游的关系：上游 `gLevel1Screens[13][56]` 是 **13 屏**，
 * 本项目沿用「13 屏」这一**布局量级**（方案 §2.3），但地形数据是本项目自建的
 * 区块序列产物 —— ⛔ 不含 ROM 提取数据的任何字节。
 */
object ContraTerrain {

    /** 每关的列数（= 屏数 × 16） */
    fun cols(level: ContraLevel): Int = level.screens * ContraConst.COLS_PER_SCREEN

    /**
     * 生成碰撞码表。**纯函数**：同一 [level] 恒得同一张表
     * （无随机、无时间依赖 ⇒ 单测可断言「同关同表」）。
     */
    fun build(level: ContraLevel): ByteArray {
        val cols = cols(level)
        val rows = ContraConst.ROWS
        val grid = ByteArray(cols * rows)

        // ① 先算每列的地面行（台阶 / 楼梯会改它）
        val baseRows = IntArray(cols)
        for (c in 0 until cols) {
            val chunk = level.chunks[c / ContraConst.COLS_PER_SCREEN]
            val cc = c % ContraConst.COLS_PER_SCREEN
            var base = level.groundRow
            when (chunk) {
                ContraChunk.STEP_UP -> if (cc >= 4) base -= 2
                ContraChunk.STEP_DOWN -> if (cc >= 4) base += 1
                ContraChunk.STAIRS -> if (cc >= 8) base -= ((cc - 8) / 3) + 1
            }
            baseRows[c] = base.coerceIn(4, rows - 2)
        }

        // ② 铺地形
        for (c in 0 until cols) {
            val chunk = level.chunks[c / ContraConst.COLS_PER_SCREEN]
            val cc = c % ContraConst.COLS_PER_SCREEN
            val base = baseRows[c]
            when (chunk) {
                ContraChunk.GAP -> {
                    // 水坑：2 格宽（32 px）—— 玩家起跳后水平覆盖 ≈ 73 px，留足余量
                    if (cc in 6..7) {
                        for (r in base until rows) grid[c * rows + r] = ContraConst.C_WATER.toByte()
                    } else {
                        fillGround(grid, cols, c, base, rows)
                    }
                }
                ContraChunk.BRIDGE -> {
                    if (cc in 3..12) {
                        // 断桥：桥面只有 1 格厚，其下全是水
                        grid[c * rows + base] = ContraConst.C_FLOOR.toByte()
                        for (r in base + 1 until rows) grid[c * rows + r] = ContraConst.C_WATER.toByte()
                    } else {
                        fillGround(grid, cols, c, base, rows)
                    }
                }
                ContraChunk.PILLAR -> {
                    fillGround(grid, cols, c, base, rows)
                    // 2 格高（32 px）—— 玩家跳跃高度 ≈ 53 px，⛔ 不要做成 3 格（48 px 以上会卡死 bot）
                    if (cc in 6..7) {
                        for (r in base - 2 until base) {
                            if (r >= 0) grid[c * rows + r] = ContraConst.C_SOLID.toByte()
                        }
                    }
                }
                ContraChunk.PLATFORM -> {
                    fillGround(grid, cols, c, base, rows)
                    if (cc in 3..8 && base - 4 >= 0) grid[c * rows + (base - 4)] = ContraConst.C_FLOOR.toByte()
                }
                ContraChunk.CORRIDOR -> {
                    fillGround(grid, cols, c, base, rows)
                    if (cc in 2..13) {
                        for (r in 0 until base - 5) grid[c * rows + r] = ContraConst.C_SOLID.toByte()
                    }
                }
                else -> fillGround(grid, cols, c, base, rows)
            }
        }

        // ③ boss 房：右侧一道实心墙，把「抵达右端」变成「必须打死 boss」
        val wallCol = cols - 2
        val wallBase = baseRows[wallCol]
        for (r in (wallBase - 8) until wallBase) {
            if (r >= 0) grid[wallCol * rows + r] = ContraConst.C_SOLID.toByte()
        }
        return grid
    }

    private fun fillGround(grid: ByteArray, cols: Int, c: Int, base: Int, rows: Int) {
        // 顶行是可站立地面（C_FLOOR），其下是土体（C_SOLID）
        // ⇒ 三类碰撞码在同一张表里都会命中（单测 §6.3 有断言）
        grid[c * rows + base] = ContraConst.C_FLOOR.toByte()
        for (r in base + 1 until rows) grid[c * rows + r] = ContraConst.C_SOLID.toByte()
    }

    /** 查询碰撞码（越界：左右/上为实心，下为空 = 掉出画面） */
    fun codeAt(grid: ByteArray, cols: Int, col: Int, row: Int): Int {
        if (col < 0 || col >= cols) return ContraConst.C_SOLID
        if (row < 0) return ContraConst.C_EMPTY
        if (row >= ContraConst.ROWS) return ContraConst.C_EMPTY
        return grid[col * ContraConst.ROWS + row].toInt()
    }

    /** 该列可站立的地面顶行（自上而下第一个 FLOOR/SOLID），找不到返回 -1 */
    fun groundRowAt(grid: ByteArray, cols: Int, col: Int): Int {
        for (r in 0 until ContraConst.ROWS) {
            val code = codeAt(grid, cols, col, r)
            if (code == ContraConst.C_FLOOR || code == ContraConst.C_SOLID) return r
        }
        return -1
    }
}

/**
 * 8 关的静态数据（第 1 关布局量级对齐上游 13 屏；第 2~8 关全自建）。
 *
 * ⚠️ **波次与 boss 参数是「自建决策」，不是上游给定的事实**（方案 §7.1 尾注）。
 */
object ContraLevels {

    /** 「贴地生成」哨兵值 */
    const val GROUND = -1

    private const val AIR_LOW = 96
    private const val AIR_HIGH = 64

    val all: Array<ContraLevel> by lazy { buildAll() }

    fun level(number: Int): ContraLevel = all[(number - 1).coerceIn(0, all.size - 1)]

    private fun buildAll(): Array<ContraLevel> {
        val list = ArrayList<ContraLevel>(8)
        list.add(
            ContraLevel(
                number = 1, name = "丛林", screens = 13,
                palette = ContraPalette(
                    skyTop = 0xFF101820.toInt(), skyBottom = 0xFF2E4A3A.toInt(),
                    ground = 0xFF6B4A2F.toInt(), groundTop = 0xFF3F7A34.toInt(),
                    block = 0xFF7A7A6A.toInt(), water = 0xFF2A6BD0.toInt(),
                    accent = 0xFFE8D24A.toInt(),
                ),
                groundRow = 11, chunkCode = "FFGFPFFLFGFFF",
                bossHp = 12, bossX = 12 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 2, name = "基地", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF0A0E18.toInt(), skyBottom = 0xFF1A2438.toInt(),
                    ground = 0xFF4A4A52.toInt(), groundTop = 0xFF8A8A92.toInt(),
                    block = 0xFF6A6A78.toInt(), water = 0xFF204A80.toInt(),
                    accent = 0xFFE04040.toInt(),
                ),
                groundRow = 11, chunkCode = "FGFFUFPFFGF",
                bossHp = 14, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 3, name = "瀑布", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF0C2030.toInt(), skyBottom = 0xFF2B6E8F.toInt(),
                    ground = 0xFF3A4A3F.toInt(), groundTop = 0xFF4E8A5A.toInt(),
                    block = 0xFF5E6E6A.toInt(), water = 0xFF3FB0E0.toInt(),
                    accent = 0xFF8FE0FF.toInt(),
                ),
                groundRow = 10, chunkCode = "FFPFFGFUFFD",
                bossHp = 16, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 4, name = "雪原", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF7FA8C8.toInt(), skyBottom = 0xFFD8E8F4.toInt(),
                    ground = 0xFF9AA6B0.toInt(), groundTop = 0xFFE8F4FF.toInt(),
                    block = 0xFFB8C4CE.toInt(), water = 0xFF2A5A8A.toInt(),
                    accent = 0xFF3A6FD8.toInt(),
                ),
                groundRow = 11, chunkCode = "FGFFLFPFFGF",
                bossHp = 18, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 5, name = "熔岩", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF200808.toInt(), skyBottom = 0xFF7A1A0A.toInt(),
                    ground = 0xFF3A1A12.toInt(), groundTop = 0xFFB44A20.toInt(),
                    block = 0xFF6A2A1A.toInt(), water = 0xFFFF7A1A.toInt(),
                    accent = 0xFFFFD04A.toInt(),
                ),
                groundRow = 11, chunkCode = "FFUFFGPFFSF",
                bossHp = 20, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 6, name = "夜林", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF080A16.toInt(), skyBottom = 0xFF1A1430.toInt(),
                    ground = 0xFF2A3326.toInt(), groundTop = 0xFF3F5A34.toInt(),
                    block = 0xFF4A4A58.toInt(), water = 0xFF3A2A7A.toInt(),
                    accent = 0xFFB04AE0.toInt(),
                ),
                groundRow = 11, chunkCode = "FGFFPFCFGFF",
                bossHp = 22, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 7, name = "走廊", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF101010.toInt(), skyBottom = 0xFF242424.toInt(),
                    ground = 0xFF3A3A44.toInt(), groundTop = 0xFF8A8A9A.toInt(),
                    block = 0xFF5A5A6A.toInt(), water = 0xFF2A5A8A.toInt(),
                    accent = 0xFFE0E040.toInt(),
                ),
                groundRow = 11, chunkCode = "FFGFUFFPFFG",
                bossHp = 24, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        list.add(
            ContraLevel(
                number = 8, name = "要塞", screens = 11,
                palette = ContraPalette(
                    skyTop = 0xFF140808.toInt(), skyBottom = 0xFF3A0A0A.toInt(),
                    ground = 0xFF2A2222.toInt(), groundTop = 0xFF8A2A2A.toInt(),
                    block = 0xFF5A3A3A.toInt(), water = 0xFFFF3A2A.toInt(),
                    accent = 0xFFFFE04A.toInt(),
                ),
                groundRow = 11, chunkCode = "FFPFFGFUFFG",
                bossHp = 26, bossX = 10 * ContraConst.LOGICAL_W + 190,
            )
        )
        return list.toTypedArray()
    }

    /**
     * 波次表（**自建**）：每屏至少一波，按 `(屏号 + 关号)` 轮换兵种组合
     * ⇒ 每关波次 ≥ 10（方案 §6.3 要求 ≥ 6）。
     */
    internal fun wavesFor(level: Int, screens: Int, chunks: IntArray): Array<ContraWave> {
        val list = ArrayList<ContraWave>(screens * 2 + 4)
        var s = 1
        while (s < screens - 1) {
            // 水坑 / 断桥屏不放兵：玩家在滞空途中无法闪避，会形成固定死点
            if (s < chunks.size &&
                (chunks[s] == ContraChunk.GAP || chunks[s] == ContraChunk.BRIDGE)
            ) {
                s++
                continue
            }
            val x = s * ContraConst.LOGICAL_W + 150
            when ((s + level) % 4) {
                0 -> {
                    list.add(ContraWave(x, GROUND, ContraConst.E_SOLDIER))
                    list.add(ContraWave(x + 44, GROUND, ContraConst.E_SOLDIER))
                }
                1 -> {
                    list.add(ContraWave(x, GROUND, ContraConst.E_SNIPER))
                    list.add(ContraWave(x + 56, GROUND, ContraConst.E_SOLDIER))
                }
                2 -> {
                    list.add(ContraWave(x, GROUND, ContraConst.E_TURRET))
                    list.add(ContraWave(x + 30, AIR_LOW, ContraConst.E_CAPSULE))
                }
                else -> {
                    list.add(ContraWave(x, GROUND, ContraConst.E_PILLBOX))
                    list.add(ContraWave(x + 48, GROUND, ContraConst.E_TURRET))
                }
            }
            if ((s + level) % 5 == 0) list.add(ContraWave(x + 18, AIR_HIGH, ContraConst.E_ITEM))
            s++
        }
        return list.toTypedArray()
    }
}
