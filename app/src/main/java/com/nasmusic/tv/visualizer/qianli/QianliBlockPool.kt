package com.nasmusic.tv.visualizer.qianli

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 层规格 —— 描述一层分块带的几何与运动参数。
 *
 * 字段来源：方案 §一图层表 + §7.5 分层速度表 + §7.5 α 下限表。
 *
 * @param id         层序号 L0–L7（§一）
 * @param bandY0     带顶（0..1 屏高比例）
 * @param bandY1     带底（0..1）
 * @param speed      基准速度 px/s（§7.5 分层速度表）
 * @param alphaFloor 静音帧 α 下限（§7.5 α 下限表）
 */
data class LayerSpec(
    val id: Int,
    val bandY0: Double,
    val bandY1: Double,
    val speed: Double,
    val alphaFloor: Double,
)

/**
 * 分块 ring buffer —— 每层 N 个唯一位图块，滚动到边界时惰性再生成。
 *
 * ## 两段式重生（R1 解法）
 *
 * - [advance] 只置 [pendingRegen]，⛔ 不同步生成（单块 2,400 行 JS 等价工作不可能塞进 16ms）。
 * - 生成在后续帧的 [draw] 预算片里推进（每帧最多 [BLOCK_BUDGET_MS]）。
 * - 生成完成前该块画上一轮的旧块（旧块内容不同但风格一致 ⇒ 读作"山在变"而非"空白"）。
 *
 * ## 位移纪律
 *
 * 位移走 [Canvas.drawBitmap] 的 x 参数，⛔ 不碰 [android.graphics.Shader.setLocalMatrix]
 * （E43 真机静默失效）。
 *
 * ## 块宽约束
 *
 * 块宽 [blockWidth] `≥ 1.05 × 屏宽`（保证"覆盖整屏时另一块可在后台重生"）。
 *
 * ## 坐标空间
 *
 * - 世界坐标：每块有一个固定的 `x0`（左缘），不随帧变化（除非重生时推进）。
 * - 屏幕坐标 = 世界坐标 − [offset]。
 * - 块高 = 层带高 [blockHeight]，⛔ 不是全屏。
 * - 块池在 y=0 落笔；带顶定位由调用方（`QianliScrollRenderer`）通过 `canvas.translate` 承担。
 *
 * ## 逐帧零分配
 *
 * [Paint] 字段复用；Bitmap 由 [gen] 产出，旧块在换入新块时 `recycle`。
 *
 * @param layer 层规格
 * @param n     分块数（§一的 ops 账，默认 2）
 * @param w     屏宽（px）
 * @param h     层带高（px），⛔ 不是全屏
 * @param gen   块生成器：给定世界坐标 x0，产出一张 Bitmap
 */
class QianliBlockPool(
    layer: LayerSpec,
    n: Int,
    w: Int,
    h: Int,
    gen: (x0: Double) -> Bitmap,
) {
    // ─────────────────────── 构造参数 ───────────────────────

    private val layerSpec = layer
    private val blockCount = n.coerceAtLeast(1)
    private val screenW = w
    private val gen = gen

    /** 块宽 `Wb ≥ 1.05 × 屏宽`。 */
    val blockWidth: Int = (w * 1.05).toInt().coerceAtLeast(w)

    /** 层带高（px），⛔ 不是全屏。 */
    val blockHeight: Int = h

    // ─────────────────────── ring buffer ───────────────────────

    /** N 个位图块（`null` = 尚未生成）。 */
    private val blocks = arrayOfNulls<Bitmap>(blockCount)

    /** 每块的世界坐标左缘 `x0`。初始 `i * Wb`；重生时 `+= N · Wb`。 */
    private val blockX0 = DoubleArray(blockCount)

    /** 该块是否需要重生（`true` = 待生成或生成中）。 */
    private val blockRegen = BooleanArray(blockCount)

    // ─────────────────────── 重生状态 ───────────────────────

    /** 是否有块正在等待/进行重生（公开只读，由 [advance]/[draw] 维护）。 */
    var pendingRegen: Boolean = false
        private set

    // ─────────────────────── 逐帧复用 ───────────────────────

    private val paint = Paint()

    // ─────────────────────── init ───────────────────────

    init {
        require(w > 0) { "screen width must be > 0" }
        require(h > 0) { "band height must be > 0" }
        for (i in 0 until blockCount) {
            blockX0[i] = i * blockWidth.toDouble()
            blockRegen[i] = true  // 初始全部待生成
        }
        pendingRegen = true
    }

    // ─────────────────────── 帧头：判定哪些块需要重生 ───────────────────────

    /**
     * 根据当前 [offset]（世界坐标滚动位置）判定哪些块已滚出视口左缘，
     * 标记为待重生，并推进其世界坐标到最右侧（`x0 += N · Wb`）。
     *
     * ⛔ 只标记、不同步生成（R1 解法）。生成在 [draw] 的预算片里推进。
     *
     * 初始状态下所有块为 `null`，此处也会全部标记为待生成。
     */
    fun advance(offset: Double) {
        for (i in 0 until blockCount) {
            if (blocks[i] == null || blockX0[i] + blockWidth < offset) {
                if (blocks[i] != null) {
                    // 重生：推进到最右侧（保持与相邻块连续、无空白）
                    blockX0[i] += blockCount * blockWidth.toDouble()
                }
                blockRegen[i] = true
                pendingRegen = true
            }
        }
    }

    // ─────────────────────── 帧体：预算片重生 + 绘制 ───────────────────────

    /**
     * 绘制当前可见的块。
     *
     * ① 若 [pendingRegen]，在 [BLOCK_BUDGET_MS] 预算内尝试生成待重生块；
     *    生成完成前该块继续画旧位图（无空白）。
     * ② 绘制所有与屏幕 `[0, screenW]` 重叠的块，x 走 `blockX0[i] − offset`，y 恒 `0`
     *    （带顶定位由调用方 `canvas.translate` 承担）。
     *
     * @param offset 世界坐标滚动位置（px）
     * @param alpha  本层整体 α（0..1）
     */
    fun draw(canvas: Canvas, offset: Double, alpha: Float) {
        // ① 预算片重生
        if (pendingRegen) {
            regenerateBudgeted()
        }

        // ② 绘制可见块
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        for (i in 0 until blockCount) {
            val bmp = blocks[i] ?: continue
            val screenX = (blockX0[i] - offset).toFloat()
            // 与屏幕 [0, screenW) 重叠才画
            if (screenX < screenW.toFloat() && screenX + blockWidth > 0f) {
                canvas.drawBitmap(bmp, screenX, 0f, paint)
            }
        }
    }

    /**
     * 在固定时间预算内推进重生队列。每帧最多 [BLOCK_BUDGET_MS] 毫秒。
     *
     * 生成完成即换入新位图并 `recycle` 旧位图；预算耗尽则停止，
     * 该块继续用旧位图绘制（G-POOL-3：⛔ 不画空白）。
     *
     * ⚠️ `gen` 是同步函数：若单块生成耗时超过预算，帧会等待至其返回。
     *    预算判定在**块间**生效（N=2 时最多 1 块待重生 ⇒ 实际为单块上界）。
     */
    private fun regenerateBudgeted() {
        val t0 = System.nanoTime()
        val budgetNs = BLOCK_BUDGET_MS * 1_000_000L

        var i = 0
        while (i < blockCount) {
            if (blockRegen[i]) {
                val x0 = blockX0[i]
                val newBmp = gen(x0)

                // 换入新位图，recycle 旧位图
                val old = blocks[i]
                blocks[i] = newBmp
                blockRegen[i] = false
                if (old !== null && old !== newBmp && !old.isRecycled) {
                    old.recycle()
                }
            }
            i++

            // 预算耗尽则停止（⛔ 不砍块宽、不画空白）
            if (System.nanoTime() - t0 >= budgetNs) break
        }

        pendingRegen = blockRegen.any { it }
    }

    /**
     * 强制重生**全部**块（染色版本切换时用）。
     *
     * ⚠️ **不释放旧块** ⇒ 旧块在预算片把新块换进来之前继续被画 ⇒ **不闪白**
     * （⛔ G-POOL-3「不许画空白」）；新块换入时旧块才被 `recycle`。
     */
    fun forceRegenerate() {
        for (i in 0 until blockCount) blockRegen[i] = true
        pendingRegen = true
    }

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 释放全部位图。`onExit` 时调用。
     *
     * 逐张 `recycle`（API 22–25 的 `ARGB_8888` 像素在 native 堆、不受 GC 自愈）。
     */
    fun release() {
        for (i in 0 until blockCount) {
            blocks[i]?.let { if (!it.isRecycled) it.recycle() }
            blocks[i] = null
            blockRegen[i] = false
        }
        pendingRegen = false
    }

    companion object {
        /** 每帧重生预算（ms）。 */
        const val BLOCK_BUDGET_MS = 12
    }
}
