/**
 * p5.js Perlin 移植 —— 照抄上游 [Noise]（`index.html:90-203`，出处 `:89` 标注）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * 常量：`PERLIN_SIZE = 4095`、4 octaves、falloff 0.5。
 *
 * ⚠️ [noiseSeed] 在上游是**死代码**（全文件零调用）；Noise 的 perlin 表实际用 `Math.random()` 初始化
 * ⇒ 与 [ShanShuiPrng] **共用同一种子流**，不是两套独立种子。
 * `noiseSeed` 内部用 LCG（`m=4294967296, a=1664525, c=1013904223`）填充 perlin 数组 —— 照搬但不加调用。
 *
 * ⚠️ perlin 表用 [ShanShuiPrng.global.rnd()] 初始化（替代上游 `Math.random()` 全局覆写）。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiNoise {

    private const val PERLIN_YWRAPB = 4
    private val PERLIN_YWRAP = 1 shl PERLIN_YWRAPB
    private const val PERLIN_ZWRAPB = 8
    private val PERLIN_ZWRAP = 1 shl PERLIN_ZWRAPB
    private const val PERLIN_SIZE = 4095

    private var perlinOctaves = 4
    private var perlinAmpFalloff = 0.5
    private var perlin: DoubleArray? = null

    private fun scaledCosine(i: Double): Double = 0.5 * (1.0 - Math.cos(i * Math.PI))

    /**
     * 照抄上游 `:102-168`。
     * 标准 p5.js Perlin noise，多 octave 叠加。
     */
    fun noise(x: Double, y: Double = 0.0, z: Double = 0.0): Double {
        var x = x
        var y = y
        var z = z

        if (perlin == null) {
            perlin = DoubleArray(PERLIN_SIZE + 1)
            for (i in 0..PERLIN_SIZE) {
                perlin!![i] = ShanShuiPrng.global.rnd()
            }
        }

        if (x < 0) x = -x
        if (y < 0) y = -y
        if (z < 0) z = -z

        var xi = Math.floor(x)
        var yi = Math.floor(y)
        var zi = Math.floor(z)
        var xf = x - xi
        var yf = y - yi
        var zf = z - zi
        var rxf: Double
        var ryf: Double
        var r = 0.0
        var ampl = 0.5
        var n1: Double
        var n2: Double
        var n3: Double

        for (o in 0 until perlinOctaves) {
            var of = xi + (yi.toInt() shl PERLIN_YWRAPB) + (zi.toInt() shl PERLIN_ZWRAPB)
            rxf = scaledCosine(xf)
            ryf = scaledCosine(yf)
            n1 = perlin!![of.toInt() and PERLIN_SIZE]
            n1 += rxf * (perlin!![(of + 1).toInt() and PERLIN_SIZE] - n1)
            n2 = perlin!![(of + PERLIN_YWRAP).toInt() and PERLIN_SIZE]
            n2 += rxf * (perlin!![(of + PERLIN_YWRAP + 1).toInt() and PERLIN_SIZE] - n2)
            n1 += ryf * (n2 - n1)
            of += PERLIN_ZWRAP
            n2 = perlin!![of.toInt() and PERLIN_SIZE]
            n2 += rxf * (perlin!![(of + 1).toInt() and PERLIN_SIZE] - n2)
            n3 = perlin!![(of + PERLIN_YWRAP).toInt() and PERLIN_SIZE]
            n3 += rxf * (perlin!![(of + PERLIN_YWRAP + 1).toInt() and PERLIN_SIZE] - n3)
            n2 += ryf * (n3 - n2)
            n1 += scaledCosine(zf) * (n2 - n1)
            r += n1 * ampl
            ampl *= perlinAmpFalloff
            xi = (xi.toInt() shl 1).toDouble()
            xf *= 2.0
            yi = (yi.toInt() shl 1).toDouble()
            yf *= 2.0
            zi = (zi.toInt() shl 1).toDouble()
            zf *= 2.0
            if (xf >= 1.0) { xi++; xf -= 1.0 }
            if (yf >= 1.0) { yi++; yf -= 1.0 }
            if (zf >= 1.0) { zi++; zf -= 1.0 }
        }
        return r
    }

    /** 照抄上游 `:169-176`。 */
    fun noiseDetail(lod: Double, falloff: Double) {
        if (lod > 0) perlinOctaves = lod.toInt()
        if (falloff > 0) perlinAmpFalloff = falloff
    }

    /**
     * 照抄上游 `:177-202`。
     * ⚠️ 死代码（上游零调用），但照搬。
     * 内部用 LCG 填充 perlin 数组（与 `Math.random()` 初始化不同）。
     */
    fun noiseSeed(seed: Double) {
        var z = (seed.toULong() and 0xFFFFFFFFUL).toDouble()
        val m = 4294967296.0
        val a = 1664525.0
        val c = 1013904223.0
        perlin = DoubleArray(PERLIN_SIZE + 1)
        for (i in 0..PERLIN_SIZE) {
            z = (a * z + c) % m
            perlin!![i] = z / m
        }
    }
}
