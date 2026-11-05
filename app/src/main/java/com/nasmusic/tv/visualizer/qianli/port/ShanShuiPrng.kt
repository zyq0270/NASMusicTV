/**
 * BBS 式乘方取模 PRNG —— 照抄上游 [Prng]（`index.html:2-56`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**。
 *
 * ⚠️ **Double 全程**：`hash()` 的累加结果与 `next()` 的 `s²` 都溢出 2^53
 * ⇒ ⛔ 用 `Long`/`BigInteger`（值不同）、⛔ 改写成 Horner 形式（舍入不同）。
 *
 * `hash(x)` = `Base64(JSON.stringify(x))` 逐字符 `charCode·128^i` **升序累加**
 * （上游 `:7-14`：`for i in 0..y.length` ⇒ `z += charCode(i) * 128^i`）。
 *
 * `seed(x)` 末尾**预热 10 次 `next()`**（上游 `:30-32`）⇒ 漏掉则整条序列错位。
 *
 * 上游 `:54-56` 全局覆写 `Math.random = () => Prng.next()` ⇒ 全文 155 处
 * `Math.random()` 调用点全部走种子流 —— 我们只需提供 [rnd]。
 *
 * 黄金向量（seed=1234，数值型）：
 * `sAfterSeed = 334869857393`，
 * 前 4 输出 `0.5179214018993742 / 0.19101042199684515 / 0.1481747547047804 / 0.8586372206878526`。
 */

package com.nasmusic.tv.visualizer.qianli.port

class ShanShuiPrng {

    var s: Double = 1234.0

    private val p: Double = 999979.0
    private val q: Double = 999983.0
    private val m: Double = p * q

    /** 全局实例 —— 上游 `Prng` 是全局变量，`Math.random` 覆写后全文共用。 */
    companion object {
        val global: ShanShuiPrng = ShanShuiPrng()
    }

    /**
     * 照抄上游 `:7-14`。
     * `hash(x)` = `Base64(JSON.stringify(x))` 逐字符 `charCode·128^i` 升序累加。
     *
     * ⚠️ 累加顺序必须是**升序**（i=0 先加），⛔ 不能用 Horner 形式（舍入不同）。
     */
    fun hash(x: Any?): Double {
        val jsonStr = jsonStringify(x)
        val b64 = btoa(jsonStr)
        var z = 0.0
        for (i in 0 until b64.length) {
            z += b64[i].toInt().toDouble() * Math.pow(128.0, i.toDouble())
        }
        return z
    }

    /**
     * 照抄上游 `:15-33`。
     * `seed(x)`：hash → 调整 y（避免 0/1/p/q 的倍数）→ 预热 10 次 next()。
     */
    fun seed(x: Any?) {
        val seedVal = x ?: System.currentTimeMillis()
        var y = 0.0
        var z = 0.0
        fun redo() {
            y = (hash(seedVal) + z) % m
            z += 1.0
        }
        while (y % p == 0.0 || y % q == 0.0 || y == 0.0 || y == 1.0) {
            redo()
        }
        s = y
        for (i in 0 until 10) {
            next()
        }
    }

    /**
     * 照抄上游 `:34-37`。
     * `next()` = `s = s² mod m; return s/m`。
     * ⚠️ `s²` 可达 1e24，溢出 2^53 ⇒ 必须用 Double。
     */
    fun next(): Double {
        s = (s * s) % m
        return s / m
    }

    /** 上游 `Math.random()` 的替代 —— 全局覆写后的调用点全部走这里。 */
    fun rnd(): Double = next()

    // ─── 内部辅助 ──────────────────────────────────────────────

    /**
     * 模拟 `JSON.stringify(x)` —— 仅处理 Number / String / Boolean / null。
     * 上游用 `JSON.stringify` 把 seed 转为字符串再 Base64 编码。
     */
    private fun jsonStringify(x: Any?): String {
        return when (x) {
            null -> "null"
            is String -> "\"$x\""
            is Number -> x.toString()
            is Boolean -> x.toString()
            else -> x.toString()
        }
    }

    /**
     * 模拟 `window.btoa(input)` —— ASCII 字符串 → Base64。
     * 上游 `btoa` 操作 UTF-16 字符串的低 16 位；对 ASCII 输入（seed 场景）等价于逐字节编码。
     */
    private fun btoa(input: String): String {
        val bytes = input.toByteArray(Charsets.ISO_8859_1)
        return base64Encode(bytes)
    }

    private fun base64Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(bytes.size / 3 * 4 + 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(alphabet[b0 ushr 2])
            sb.append(alphabet[((b0 and 3) shl 4) or (b1 ushr 4)])
            if (i + 1 < bytes.size) {
                sb.append(alphabet[((b1 and 15) shl 2) or (b2 ushr 6)])
            } else {
                sb.append('=')
            }
            if (i + 2 < bytes.size) {
                sb.append(alphabet[b2 and 63])
            } else {
                sb.append('=')
            }
            i += 3
        }
        return sb.toString()
    }
}
