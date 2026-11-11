package com.nasmusic.tv.visualizer.photo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nasmusic.tv.visualizer.VisualizerRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 照片墙转场的**预生成遮罩**种类（P2 档，§4.4 的 M3 降级近似族）
 *
 * ## 为什么是「遮罩位图」而不是逐像素计算
 *
 * §4.1 的结论：1080p 单帧 200 万像素，**逐像素运算会掉到个位数帧率**。
 * 所以所有「溶解 / 网点 / 结晶」类效果的形态都必须是**预生成的 256×256 灰度位图**，
 * 运行时只做一次 `BlendMode.DstIn` 合成 ⇒ 零逐像素成本。
 *
 * ## 每一张遮罩表达什么
 *
 * | 种类 | 形态 | 用在 |
 * |---|---|---|
 * | [GRAIN] | 细白噪点 | `GRAIN_DISSOLVE` / `SAND_DISSOLVE` / `PULSE_DISSOLVE` |
 * | [DOTS] | 45° 网点（半调屏） | `HALFTONE` |
 * | [RINGS] | 同心圆环（波纹） | `RIPPLE` |
 * | [SPIRAL] | 螺旋臂 | `SWIRL` |
 * | [BURN] | 参差燃烧前沿（**上方不透明**） | `BURN` / `COLOR_BURN` |
 * | [MELT] | 上密下疏的渐变 | `MELT` |
 * | [BLOBS] | 大块有机团块 | `INK_SPREAD` / `WATERCOLOR` |
 * | [CRYSTAL] | 胞晶内部（**晶界透明**） | `FROST` |
 *
 * ⚠️ 遮罩**只表达形态**（哪里透、哪里不透），**不表达进度** ——
 * 进度由调用方用 `alpha` 缩放遮罩、或把遮罩缩放 / 旋转 / 竖向拉长来实现。
 *
 * ⛔ 遮罩里带**空洞**的形态（[RINGS] / [SPIRAL] / [BLOBS] / [CRYSTAL]）在末帧仍会透出旧图
 * ⇒ 用到它们的转场必须开「收尾」（`MaskDissolveTransition.settleFrom`）。
 */
enum class PhotoMaskKind {
    GRAIN,
    DOTS,
    RINGS,
    SPIRAL,
    BURN,
    MELT,
    BLOBS,
    CRYSTAL,
}

/**
 * 预生成遮罩位图缓存（§14.3「遮罩资源」）
 *
 * ## 为什么是 `object`（全进程单例）
 *
 * 遮罩是**只读**的灰度图，与画布尺寸 / 画质 / 当前照片都无关 ——
 * 一张 256×256 的噪声图可以被 `NOISE_DISSOLVE`、以及 P2 档里所有 M3 降级项**共用**。
 * 文档要求「全进程只生成一次」，做成单例是最直接的实现：**每个 `PhotoRenderer` 实例
 * 各自持有一份就会变成「每次进效果重新生成」**（生成要逐像素算，256×256 有 6.5 万像素）。
 *
 * ⚠️ 因此**没有 `release()`**：单例不能因为某个渲染器退出就把共享位图回收掉
 * （其他实例可能正在用）。常驻内存 = 256×256×4 ≈ 256 KB，可以接受。
 * 需要清空缓存的只有单测（[clearForTest]）。
 *
 * ## 为什么用 `ARGB_8888` 而不是 `ALPHA_8`
 *
 * 遮罩要能直接喂给 `drawImage` 的 `BlendMode.DstIn` / `DstOut`，那需要**alpha 通道**。
 * `ALPHA_8` 更省（64 KB），但 `Bitmap.setPixels` 对它的取值约定在各版本实现里不一致，
 * 而 `ARGB_8888` 写 `alpha shl 24`（RGB 留 0）行为确定、内存也完全够用。
 *
 * ## P2 档新增的图案遮罩是**按需生成**的
 *
 * [noise] 是 P0 就有的（fBm 值噪声）；P2 档的 7 张图案遮罩各自只在**第一次被用到时**生成，
 * 之后进缓存 ⇒ 用户只用 3 种转场时，内存里就只有 3 张图案遮罩（而不是 7 张全在）。
 */
object MaskCache {

    /** 遮罩边长（2 的幂，便于按需采样；不要随意改 —— 视觉密度是按 256 调的） */
    const val SIZE = 256

    /** ⚠️ 只被主线程访问（`prepare` 在主线程），不加锁 */
    private var noiseMask: ImageBitmap? = null

    /** P2 档图案遮罩的缓存（键 = [PhotoMaskKind]） */
    private val patternMasks = HashMap<PhotoMaskKind, ImageBitmap>(PhotoMaskKind.entries.size)

    /** 生成次数（单测用：验证「只生成一次」） */
    internal var noiseGenerationCount = 0
        private set

    /** 图案遮罩累计生成次数（单测用：验证「按需生成」） */
    internal var patternGenerationCount = 0
        private set

    /**
     * 噪声遮罩（`NOISE_DISSOLVE` 及 P2 各 M3 降级项共用）。
     *
     * 形态：多倍频**值噪声**（fBm，3 个八度）—— 比白噪声更像「云雾溶解」，
     * 比真柏林噪声实现简单得多，而在 256×256 上肉眼看不出差别。
     */
    fun noise(): ImageBitmap {
        noiseMask?.let { return it }
        val created = generateNoise()
        noiseMask = created
        noiseGenerationCount++
        return created
    }

    /**
     * 取一张**图案遮罩**（P2 档用），首次调用时生成并缓存。
     *
     * ⚠️ 生成是逐像素的（256×256 = 6.5 万像素），**第一次切到该转场时会有一次可感知停顿**
     * —— 这与既有 [noise] 的行为一致（同样在 `prepare` 里首次生成）。
     */
    fun mask(kind: PhotoMaskKind): ImageBitmap {
        patternMasks[kind]?.let { return it }
        val created = buildPatternMask(kind)
        patternMasks[kind] = created
        patternGenerationCount++
        return created
    }

    /** 仅供单测：清空缓存，使下一次 [noise] / [mask] 重新生成 */
    internal fun clearForTest() {
        noiseMask = null
        noiseGenerationCount = 0
        patternMasks.clear()
        patternGenerationCount = 0
    }

    // ────────────────────────── 生成 ──────────────────────────

    private fun generateNoise(seed: UInt = NOISE_SEED): ImageBitmap {
        val rnd = VisualizerRandom(seed)
        // 三个八度：格点数越少越「大块」，越多越「细碎」
        val octaves = OCTAVE_CELLS
        val grids = Array(octaves.size) { i ->
            val n = octaves[i]
            FloatArray((n + 1) * (n + 1)) { rnd.next() }
        }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                var sum = 0f
                var norm = 0f
                var amp = 1f
                for (o in octaves.indices) {
                    sum += amp * sampleBilinear(grids[o], octaves[o], x, y)
                    norm += amp
                    amp *= 0.5f
                }
                val v = ((sum / norm) * 255f).toInt().coerceIn(0, 255)
                // 只用 alpha 通道表达遮罩强度（RGB 留 0）
                pixels[row + x] = v shl 24
            }
        }
        return toBitmap(pixels)
    }

    /**
     * 生成一张图案遮罩（[PhotoMaskKind] → 256×256 alpha 位图）。
     *
     * ⚠️ 每一种形态的公式都写在 [alphaAt] 里，**形态就是效果本身** ——
     * 改公式等于改视觉效果，不是「调参」。
     */
    private fun buildPatternMask(kind: PhotoMaskKind): ImageBitmap {
        val n = SIZE
        val pixels = IntArray(n * n)
        val noise = ValueNoise2D(VisualizerRandom(PATTERN_SEED + kind.ordinal.toUInt() * 7919u))

        for (y in 0 until n) {
            val row = y * n
            for (x in 0 until n) {
                pixels[row + x] = alphaAt(kind, x, y, noise).coerceIn(0, 255) shl 24
            }
        }
        return toBitmap(pixels)
    }

    /**
     * 图案遮罩在 `(x, y)` 处的 **alpha**（`0..255`）—— 纯计算，**不碰位图**。
     *
     * ## 为什么要单独抽出来
     *
     * ⚠️ Windows 上 Robolectric 的 **NATIVE 图形模式不可用**（`robolectric-nativeruntime.dll`
     * 只有 linux / mac 版本）⇒ 单测**不能做像素回读**。把形态公式抽成纯函数后，
     * 遮罩的形状（网点是斜排的、燃烧前沿在顶部、胞晶内部不透明…）就能用**纯 JVM** 断言。
     *
     * ⚠️ [noise] 必须是**同一个实例**（连续坐标的 fBm 依赖相邻采样点的连续性）——
     * 每次调用都新建会得到完全不同的图案。
     */
    internal fun alphaAt(kind: PhotoMaskKind, x: Int, y: Int, noise: ValueNoise2D): Int {
        val a = when (kind) {
            // 白噪点：用坐标散列（纯函数、可复现），不用随机序列（那样就依赖遍历顺序了）
            PhotoMaskKind.GRAIN -> (grainHash(x, y) * 255f).toInt()

            // 45° 网点：把像素坐标旋转 45° 后落到正交网格上
            PhotoMaskKind.DOTS -> {
                val u = (x + y) * INV_SQRT2
                val v = (y - x) * INV_SQRT2
                val cx = floor(u / DOT_CELL + 0.5f) * DOT_CELL
                val cy = floor(v / DOT_CELL + 0.5f) * DOT_CELL
                val d = hypot(u - cx, v - cy)
                (255f * smoothstep(DOT_RADIUS, DOT_RADIUS * 0.35f, d)).toInt()
            }

            // 同心圆环：环相位越靠外强度越低（中心先显现 ⇒ 波纹向外扩散）
            PhotoMaskKind.RINGS -> {
                val d = hypot(x - HALF, y - HALF)
                val ph = (d % RING_PERIOD) / RING_PERIOD
                (255f * bump(ph)).toInt()
            }

            // 螺旋臂：相位 = 角度 − 半径 × 螺距 ⇒ 一条从中心甩出的旋臂
            PhotoMaskKind.SPIRAL -> {
                val dx = x - HALF
                val dy = y - HALF
                val d = hypot(dx, dy)
                val ang = (atan2(dy, dx) + PI.toFloat()) / (2f * PI.toFloat())
                val ph = frac(ang - d * SPIRAL_PITCH)
                (255f * bump(ph)).toInt()
            }

            // 参差燃烧前沿：噪声扰动的水平线，**上方不透明**（烧掉的部分透出新图）
            PhotoMaskKind.BURN -> {
                val edge = BURN_BASE + noise.fbm(x * 0.18f, 3.7f) * BURN_DEPTH
                (255f * (1f - ((y - edge) / BURN_FEATHER).coerceIn(0f, 1f))).toInt()
            }

            // 融化：上密下疏的**前沿**（噪声频率更高 ⇒ 细长的下垂流痕）
            PhotoMaskKind.MELT -> {
                val edge = MELT_BASE + noise.fbm(x * 0.16f, 9.1f) * MELT_DEPTH
                (255f * (1f - ((y - edge) / MELT_FEATHER).coerceIn(0f, 1f))).toInt()
            }

            // 大块有机团块：低频 fBm 阈值化 ⇒ 墨 / 水彩的不规则边界
            PhotoMaskKind.BLOBS -> {
                val f = noise.fbm(x * 0.16f, y * 0.16f)
                (255f * smoothstep(BLOB_LO, BLOB_HI, f)).toInt()
            }

            // 胞晶：到最近 / 次近特征点的距离差接近 0 ⇒ 晶界。
            // alpha = 距离差 ⇒ **胞内不透明、晶界透明**（结冰时裂纹里仍是旧图）
            PhotoMaskKind.CRYSTAL -> {
                var d1 = Float.MAX_VALUE
                var d2 = Float.MAX_VALUE
                for (i in 0 until CRYSTAL_POINTS) {
                    val dx = x - crystalX[i]
                    val dy = y - crystalY[i]
                    val d = dx * dx + dy * dy
                    if (d < d1) {
                        d2 = d1
                        d1 = d
                    } else if (d < d2) {
                        d2 = d
                    }
                }
                val edge = (sqrt(d2) - sqrt(d1))
                (255f * smoothstep(0f, CRYSTAL_EDGE, edge)).toInt()
            }
        }
        return a.coerceIn(0, 255)
    }

    /**
     * 单点采样某张图案遮罩在 `(x, y)` 处的 alpha —— **纯 JVM 可用**（不建位图）。
     *
     * ⚠️ 每次调用都会重建一份与整幅生成**同种子**的噪声 ⇒ 采样值与位图里的值一致 ✓。
     */
    internal fun sampleAlpha(kind: PhotoMaskKind, x: Int, y: Int): Int {
        val noise = ValueNoise2D(VisualizerRandom(PATTERN_SEED + kind.ordinal.toUInt() * 7919u))
        return alphaAt(kind, x, y, noise)
    }

    /**
     * 白噪点用的**坐标散列**（`0..1`，确定性）。
     *
     * ⛔ 不能用随机序列：那会让 alpha 依赖**遍历顺序** ⇒ 单点采样（纯 JVM 单测）
     * 与整幅生成（位图）得到的图案不同 ⇒ 测的就不是同一张遮罩了。
     */
    private fun grainHash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263 + GRAIN_SALT
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun toBitmap(pixels: IntArray): ImageBitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp.asImageBitmap()
    }

    // ────────────────────────── 采样与数学 ──────────────────────────

    /** 在 `n×n` 格点上做双线性插值采样（值噪声的核心） */
    private fun sampleBilinear(grid: FloatArray, n: Int, x: Int, y: Int): Float {
        val fx = x.toFloat() / SIZE * n
        val fy = y.toFloat() / SIZE * n
        val x0 = fx.toInt().coerceIn(0, n - 1)
        val y0 = fy.toInt().coerceIn(0, n - 1)
        val tx = fx - x0
        val ty = fy - y0
        val stride = n + 1
        val v00 = grid[y0 * stride + x0]
        val v10 = grid[y0 * stride + x0 + 1]
        val v01 = grid[(y0 + 1) * stride + x0]
        val v11 = grid[(y0 + 1) * stride + x0 + 1]
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    /** 平滑阶跃：`x ≤ a` ⇒ 0，`x ≥ b` ⇒ 1，中间三次缓动（`a > b` 时为反向） */
    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (a == b) return if (x < b) 0f else 1f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 环 / 臂的「鼓包」：相位 `ph ∈ [0,1)`，离 0.5 越远越暗 */
    private fun bump(ph: Float): Float {
        val d = abs(ph - 0.5f) * 2f
        val v = (1f - d).coerceAtLeast(0f)
        return v * v
    }

    private fun frac(v: Float): Float = v - floor(v)

    private fun sqrt(v: Float): Float = kotlin.math.sqrt(v)

    /**
     * 二维值噪声（fBm）—— 供 [PhotoMaskKind.BURN] / [PhotoMaskKind.BLOBS] 的形态使用。
     *
     * 与 [generateNoise] 的差别：那个是**逐像素独立**的格子采样（每像素只查一个格子），
     * 这个是**连续坐标**的采样 ⇒ 同一张遮罩里不会出现格子状硬边。
     *
     * ⚠️ 是 `internal` 而不是 `private`：[alphaAt] 的签名里要出现它（纯 JVM 单测要用）。
     */
    internal class ValueNoise2D(rnd: VisualizerRandom) {

        private val cells = 16
        private val grid = FloatArray((cells + 1) * (cells + 1)) { rnd.next() }

        /** 坐标缩放（见 [fbm] 的说明：调用方的频率要按它折算） */
        private companion object {
            const val SCALE = 0.06f
        }

        /**
         * 二维值噪声采样（fBm）。
         *
         * ⚠️ **内部还会再乘 [SCALE]** ⇒ 调用方给的坐标频率要**按这个倍率折算**：
         * 传 `x × 0.16` ⇒ 实际周期 ≈ `1 / (0.16 × 0.06) ≈ 104 px`
         * （遮罩边长 256 ⇒ 约 2.5 个团块 / 一条波纹）。
         *
         * ⛔ 传**过小**的频率（如 `0.022`）⇒ 周期 758 px ≫ 遮罩边长 ⇒ 整幅近乎常量
         * （2026-11-11 实测：团块遮罩的 alpha 全幅不变 ⇒ 被 `PhotoTransitionP2Test` 当场判出）。
         */
        fun fbm(x: Float, y: Float): Float {
            val fx = x * SCALE
            val fy = y * SCALE
            val x0 = floor(fx).toInt().coerceIn(0, cells - 1)
            val y0 = floor(fy).toInt().coerceIn(0, cells - 1)
            val tx = fx - x0
            val ty = fy - y0
            val stride = cells + 1
            val v00 = grid[y0 * stride + x0]
            val v10 = grid[y0 * stride + x0 + 1]
            val v01 = grid[(y0 + 1) * stride + x0]
            val v11 = grid[(y0 + 1) * stride + x0 + 1]
            val top = v00 + (v10 - v00) * tx
            val bottom = v01 + (v11 - v01) * tx
            return (top + (bottom - top) * ty).coerceIn(0f, 1f)
        }
    }

    // ────────────────────────── 常量 ──────────────────────────

    /** 固定种子 ⇒ 每次生成的噪声图一致（可复现，便于单测与「同一张遮罩」的预期） */
    private const val NOISE_SEED = 0x5EED_1234u

    /** 图案遮罩的种子基址（每个 kind 偏移一个固定步长 ⇒ 彼此形态不同但都可复现） */
    private const val PATTERN_SEED = 0xA11C_5EEDu

    /** 白噪点的散列盐（固定 ⇒ 每次生成的颗粒图案完全一致） */
    private const val GRAIN_SALT = 0x1F357B9D

    private val OCTAVE_CELLS = intArrayOf(4, 8, 16)

    private const val HALF = SIZE / 2f

    private const val INV_SQRT2 = 0.70710678f

    /** 网点遮罩：格子边长与点半径（px） */
    private const val DOT_CELL = 15f
    private const val DOT_RADIUS = 6.5f

    /** 圆环遮罩：环间距（px） */
    private const val RING_PERIOD = 26f

    /** 螺旋遮罩：每像素的相位增量（越大 ⇒ 旋臂缠得越紧） */
    private const val SPIRAL_PITCH = 0.035f

    /**
     * 燃烧遮罩：前沿基准高度 / 噪声摆幅 / 羽化宽度
     *
     * ⚠️ 前沿落在遮罩**顶部**（基准 16px ≈ 6% 高度）—— 因为推进方式是
     * 「顶边锚定地竖向拉长」，前沿的**比例位置**会随拉长倍率一起放大。
     * 羽化取小值（6px）：拉长 9 倍后正好是屏高的 2% 左右，边界不会糊成渐变。
     */
    private const val BURN_BASE = 16f
    private const val BURN_DEPTH = 26f
    private const val BURN_FEATHER = 6f

    /**
     * 融化遮罩：前沿基准高度 / 噪声摆幅 / 羽化宽度
     *
     * ⚠️ 与燃烧同构，但**羽化更宽**（14px ⇒ 拉长后是一段渐变，像化开而不是烧穿）
     * 且噪声频率更高（细长流痕）。
     */
    private const val MELT_BASE = 10f
    private const val MELT_DEPTH = 22f
    private const val MELT_FEATHER = 14f

    /** 团块遮罩：fBm 的低 / 高阈值（越大 ⇒ 团块越少、越集中） */
    private const val BLOB_LO = 0.46f
    private const val BLOB_HI = 0.60f

    /** 胞晶遮罩：特征点数与晶界宽度（px） */
    private const val CRYSTAL_POINTS = 22
    private const val CRYSTAL_EDGE = 7f

    /** 胞晶特征点（固定布局 ⇒ 每次生成的遮罩完全一致） */
    private val crystalX = FloatArray(CRYSTAL_POINTS) { i -> (i * 37 % SIZE).toFloat() }
    private val crystalY = FloatArray(CRYSTAL_POINTS) { i -> (i * 61 % SIZE).toFloat() }
}
