/**
 * 数值 helper —— 照抄上游 Util 段（`:386-443`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * ⚠️ [randChoice]/[normRand]/[wtrand]/[randGaussian] 在上游使用 `Math.random()`
 * （全局覆写后走 [ShanShuiPrng]）；此处统一用 [ShanShuiPrng.global.rnd()]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiUtil {

    /** 照抄上游 `:386-392`。递归去 NaN/undefined。 */
    fun unNan(plist: Any?): Any? {
        if (plist == null || !plist.javaClass.isArray()) return plist ?: 0
        // JS typeof check 等价于检查是否为数组/对象
        if (plist is Array<*>) {
            return plist.map { unNan(it) }
        }
        if (plist is List<*>) {
            return plist.map { unNan(it) }
        }
        return plist ?: 0
    }

    /** 照抄上游 `:395-397`。 */
    fun distance(p0: DoubleArray, p1: DoubleArray): Double {
        return Math.sqrt(Math.pow(p0[0] - p1[0], 2.0) + Math.pow(p0[1] - p1[1], 2.0))
    }

    /** 照抄上游 `:399-403`。线性映射。 */
    fun mapval(value: Double, istart: Double, istop: Double, ostart: Double, ostop: Double): Double {
        return ostart + (ostop - ostart) * (((value - istart) * 1.0) / (istop - istart))
    }

    /**
     * 照抄上游 `:404-415`。
     * 循环噪声归一化：首尾差值线性补偿后映射到 [0,1]。
     */
    fun loopNoise(nslist: MutableList<Double>) {
        val n = nslist.size
        if (n == 0) return
        val dif = nslist[n - 1] - nslist[0]
        var bdsMin = 100.0
        var bdsMax = -100.0
        for (i in 0 until n) {
            nslist[i] += (dif * (n - 1 - i)) / (n - 1)
            if (nslist[i] < bdsMin) bdsMin = nslist[i]
            if (nslist[i] > bdsMax) bdsMax = nslist[i]
        }
        for (i in 0 until n) {
            nslist[i] = mapval(nslist[i], bdsMin, bdsMax, 0.0, 1.0)
        }
    }

    /** 照抄上游 `:417-419`。随机选元素（泛型）。 */
    fun <T> randChoice(arr: List<T>): T {
        return arr[Math.floor(arr.size * ShanShuiPrng.global.rnd()).toInt()]
    }

    /** 照抄上游 `:421-423`。 */
    fun normRand(m: Double, M: Double): Double {
        return mapval(ShanShuiPrng.global.rnd(), 0.0, 1.0, m, M)
    }

    /**
     * 照抄上游 `:425-433`。
     * 拒绝采样：`y < func(x)` 则返回 x，否则递归。
     */
    fun wtrand(func: (Double) -> Double): Double {
        val x = ShanShuiPrng.global.rnd()
        val y = ShanShuiPrng.global.rnd()
        if (y < func(x)) {
            return x
        } else {
            return wtrand(func)
        }
    }

    /** 照抄上游 `:435-443`。近似高斯分布。 */
    fun randGaussian(): Double {
        return wtrand { x -> Math.pow(Math.E, -24.0 * Math.pow(x - 0.5, 2.0)) } * 2.0 - 1.0
    }
}
