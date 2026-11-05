/**
 * 图元层 —— ⭐ 这是"像"的全部来源。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [ribbon]/[dab]/[foot] 移植状态：**照抄**；[cun] 移植状态：**改造-①**（ret≠0 时返回 List<List<Point>> 而非 SVG 字符串）。
 *
 * [ribbon] 照抄上游 [stroke]（`:516-576`）：变宽笔触，逐点法线偏移 + 噪声干湿变化。
 *   上游默认值：wid=2, col="rgba(200,200,200,0.9)", noi=0.5, out=1, fun=Math.sin(x*PI)。
 *   ⚠️ 上游参数名 `fun` 是 Kotlin 关键字 ⇒ port 改名为 [strokeFun]。
 *   ⚠️ 上游的 `out` 形参（poly 的线宽，缺省 **1**）**已删** ⇒ port 恒为上游默认值 1
 *   （由调用方写进轮廓的 `width`）。
 *
 * [dab] 照抄上游 [blob]（`:578-625`）：纺锤形笔触点/叶簇。
 *   上游默认值：len=20, wid=5, ang=0, col="rgba(200,200,200,0.9)", noi=0.5, ret=0, fun=分段 sqrt(sin)。reso=20。
 *   ⚠️ 上游的 `ret` 形参（缺省 **0** ⇒ 返回 poly 字符串）**已删** ⇒ port 恒返回点列，
 *   语义恒为上游默认值（几何与上游 ret=0 的 poly 完全一致）。
 *
 * [cun] 照抄上游 [texture]（`:653-741`）：皴。
 *   上游默认值：tex=400, wid=1.5, len=0.2, sha=0, ret=0, noi=30/x, col=rgba(100,100,100,rand*0.3), dis=三分分布。
 *   ⚠️ ret≠0 时返回 texlist（List<List<Point>>）而不是 Path 列表。
 *   ⚠️ port 包内 [cun] 恒返回 texlist（纯数据点列），渲染器侧画 stroke。
 *   ⚠️ 上游的 `col` 是**函数**（每条皴线求值一次 ⇒ 每条抽 1 次随机）与 `dis` 也是函数；
 *   port 把 `dis` 收进 [cun]（它决定点的横向位置），`col` 留给**调用方**逐条求值
 *   （上游也是在 `texture` 的 TEXTURE 循环里求值，见 [defaultCunCol]）。
 *
 * [foot] 照抄上游 [foot]（`:1728-1802`）：山脚收笔。
 *   上游默认值：xof=0, yof=0, ret=0。ret≠0 时返回 ftlist。
 *   ⚠️ port 包内 [foot] 恒返回 ftlist（List<List<Point>>），渲染器侧画 poly + stroke。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiBrush {

    /**
     * 照抄上游 [stroke]（`:516-576`）：变宽笔触。
     * 逐点法线偏移 + 噪声干湿变化，构建闭合 ribbon 多边形。
     * 返回未偏移的 vtxlist，渲染器侧再加 xof/yof。
     */
    fun ribbon(
        pts: List<ShanShuiPath.Point>,
        wid: Double,
        strokeFun: (Double) -> Double,
        noi: Double,
        col: ShanShuiColor,
    ): List<ShanShuiPath.Point> {
        if (pts.isEmpty()) return emptyList()

        val vtxlist0 = ArrayList<ShanShuiPath.Point>()
        val vtxlist1 = ArrayList<ShanShuiPath.Point>()
        val n0 = ShanShuiPrng.global.rnd() * 10

        for (i in 1 until pts.size - 1) {
            val w = wid * strokeFun(i.toDouble() / pts.size)
            val wn = w * (1 - noi) + w * noi * ShanShuiNoise.noise(i * 0.5, n0)
            val a1 = Math.atan2(
                pts[i][1].toDouble() - pts[i - 1][1],
                pts[i][0].toDouble() - pts[i - 1][0],
            )
            val a2 = Math.atan2(
                pts[i][1].toDouble() - pts[i + 1][1],
                pts[i][0].toDouble() - pts[i + 1][0],
            )
            var a = (a1 + a2) / 2
            if (a < a2) a += Math.PI
            vtxlist0.add(floatArrayOf((pts[i][0] + wn * Math.cos(a)).toFloat(), (pts[i][1] + wn * Math.sin(a)).toFloat()))
            vtxlist1.add(floatArrayOf((pts[i][0] - wn * Math.cos(a)).toFloat(), (pts[i][1] - wn * Math.sin(a)).toFloat()))
        }

        // vtxlist = [pts[0]] + vtxlist0 + [pts[end]] + reverse(vtxlist1) + [pts[0]]
        val vtxlist = ArrayList<ShanShuiPath.Point>(pts.size * 2 + 2)
        vtxlist.add(pts[0])
        vtxlist.addAll(vtxlist0)
        vtxlist.add(pts[pts.size - 1])
        vtxlist.addAll(vtxlist1.asReversed())
        vtxlist.add(pts[0])
        return vtxlist
    }

    /**
     * 照抄上游 [blob]（`:578-625`）：纺锤形笔触点。
     * reso=20，用 loopNoise 归一化噪声后偏移每个顶点。
     * 返回未偏移的 plist，渲染器侧再加偏移。
     */
    fun dab(
        x: Double,
        y: Double,
        len: Double,
        wid: Double,
        ang: Double,
        col: ShanShuiColor,
        noi: Double,
        strokeFun: (Double) -> Double = { dabFun(it) },
    ): List<ShanShuiPath.Point> {
        val reso = 20.0
        val lalist = ArrayList<Pair<Double, Double>>(reso.toInt() + 1) // [l, a]

        for (i in 0..reso.toInt()) {
            val p = (i.toDouble() / reso) * 2
            val xo = len / 2 - Math.abs(p - 1) * len
            val yo = (strokeFun(p) * wid) / 2
            val a = Math.atan2(yo, xo)
            val l = Math.sqrt(xo * xo + yo * yo)
            lalist.add(l to a)
        }

        val nslist = ArrayList<Double>(lalist.size)
        val n0 = ShanShuiPrng.global.rnd() * 10
        for (i in 0 until lalist.size) {
            nslist.add(ShanShuiNoise.noise(i * 0.05, n0))
        }
        ShanShuiUtil.loopNoise(nslist)

        val plist = ArrayList<ShanShuiPath.Point>(lalist.size)
        for (i in 0 until lalist.size) {
            val ns = nslist[i] * noi + (1 - noi)
            val nx = x + Math.cos(lalist[i].second + ang) * lalist[i].first * ns
            val ny = y + Math.sin(lalist[i].second + ang) * lalist[i].first * ns
            plist.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
        }
        return plist
    }

    /**
     * 照抄上游 [texture]（`:653-741`）：皴。
     * 在山体点阵上采样 tex 条水平线，每条线在噪声偏移后的坐标上等距插值。
     * port 包内恒返回 texlist（List<List<Point>>），渲染器侧画 stroke。
     *
     * @param dis 上游 `texture` 的 `dis` 参数（`:674-683`）—— 逐条皴线求值一次，
     *            返回该条皴线**横向位置比例**（0..1）。
     *            上游默认是三分分布（[cunDis]）；`flatMount` 传 0.1/0.9 两端分布、
     *            `rock` 传 0.15/0.85 两端分布（见上游 `:2148-2154` / `:2509-2515`）。
     * @param sha 上游 `texture` 的 `sha` 形参（缺省 0）—— **port 内未使用**
     *            （上游的 SHADE / TEXTURE 两轮发射由调用方做，见 [ShanShui]）。
     */
    fun cun(
        ptlist: List<List<ShanShuiPath.Point>>,
        tex: Double,
        sha: Double,
        dis: () -> Double = { cunDis() },
    ): List<List<ShanShuiPath.Point>> {
        val texi = tex.toInt()
        val reso0 = ptlist.size
        val reso1 = if (ptlist.isNotEmpty()) ptlist[0].size else 0
        val texlist = ArrayList<List<ShanShuiPath.Point>>(texi)

        for (i in 0 until texi) {
            val mid = (dis() * reso1).toInt()
            val hlen = Math.floor(ShanShuiPrng.global.rnd() * (reso1 * 0.2)).toInt()
            var start = mid - hlen
            var end = mid + hlen
            start = Math.min(Math.max(start, 0), reso1)
            end = Math.min(Math.max(end, 0), reso1)
            val layer = (i.toDouble() / texi) * (reso0 - 1)

            val row = ArrayList<ShanShuiPath.Point>(end - start)
            for (j in start until end) {
                val p = layer - Math.floor(layer)
                val x = ptlist[Math.floor(layer).toInt()][j][0].toDouble() * p +
                    ptlist[Math.ceil(layer).toInt()][j][0].toDouble() * (1 - p)
                val y = ptlist[Math.floor(layer).toInt()][j][1].toDouble() * p +
                    ptlist[Math.ceil(layer).toInt()][j][1].toDouble() * (1 - p)
                val ns0 = cunNoi(layer + 1) * (ShanShuiNoise.noise(x, j * 0.5) - 0.5)
                val ns1 = cunNoi(layer + 1) * (ShanShuiNoise.noise(y, j * 0.5) - 0.5)
                row.add(floatArrayOf((x + ns0).toFloat(), (y + ns1).toFloat()))
            }
            texlist.add(row)
        }
        return texlist
    }

    /**
     * 照抄上游 [foot]（`:1728-1802`）：山脚收笔。
     * 在山体底部构建收笔多边形（ftlist），每段山脚一对左右多边形。
     * port 包内恒返回 ftlist（List<List<Point>>），渲染器侧画 poly + stroke。
     *
     * ⚠️ 方案签名写 `List<Point>` 系笔误，实际返回 `List<List<Point>>`（ftlist）。
     */
    fun foot(
        ptlist: List<List<ShanShuiPath.Point>>,
        xof: Double,
        yof: Double,
    ): List<List<ShanShuiPath.Point>> {
        val ftlist = ArrayList<MutableList<ShanShuiPath.Point>>()
        val span = 10
        var ni = 0

        for (i in 0 until ptlist.size - 2) {
            if (i == ni) {
                ni = Math.min(ni + ShanShuiUtil.randChoice(listOf(1, 2)), ptlist.size - 1)

                ftlist.add(ArrayList<ShanShuiPath.Point>())
                ftlist.add(ArrayList<ShanShuiPath.Point>())
                // 上游 :1743：`Math.min(ptlist[i].length / 8, 10)` 是**浮点**除法
                //   （50/8 = 6.25 ⇒ `j < 6.25` ⇒ j = 0..6 ⇒ **7 次**）。
                // ⛔ Int 除法会得 6 ⇒ 每段山脚左右各少 1 个点（与上游不符）。
                val subLen = Math.min(ptlist[i].size / 8.0, 10.0)
                val subCount = Math.ceil(subLen).toInt()
                for (j in 0 until subCount) {
                    ftlist[ftlist.size - 2].add(
                        floatArrayOf(
                            (ptlist[i][j][0] + ShanShuiNoise.noise(j * 0.1, i.toDouble()) * 10).toFloat(),
                            ptlist[i][j][1],
                        )
                    )
                    ftlist[ftlist.size - 1].add(
                        floatArrayOf(
                            (ptlist[i][ptlist[i].size - 1 - j][0] - ShanShuiNoise.noise(j * 0.1, i.toDouble()) * 10).toFloat(),
                            ptlist[i][ptlist[i].size - 1 - j][1],
                        )
                    )
                }
                ftlist[ftlist.size - 2].reverse()
                ftlist[ftlist.size - 1].reverse()

                for (j in 0 until span) {
                    val p = j.toDouble() / span
                    val x1 = ptlist[i][0][0].toDouble() * (1 - p) + ptlist[ni][0][0].toDouble() * p
                    val y1 = ptlist[i][0][1].toDouble() * (1 - p) + ptlist[ni][0][1].toDouble() * p
                    val x2 = ptlist[i][ptlist[i].size - 1][0].toDouble() * (1 - p) +
                        ptlist[ni][ptlist[i].size - 1][0].toDouble() * p
                    val y2 = ptlist[i][ptlist[i].size - 1][1].toDouble() * (1 - p) +
                        ptlist[ni][ptlist[i].size - 1][1].toDouble() * p
                    val vib = -1.7 * (p - 1) * Math.pow(p, 1.0 / 5.0)
                    val noiseOff = ShanShuiNoise.noise(xof * 0.05, i.toDouble()) * 5
                    ftlist[ftlist.size - 2].add(floatArrayOf(x1.toFloat(), (y1 + vib * 5 + noiseOff).toFloat()))
                    ftlist[ftlist.size - 1].add(floatArrayOf(x2.toFloat(), (y2 + vib * 5 + noiseOff).toFloat()))
                }
            }
        }
        return ftlist
    }

    // ─── 内部辅助 ──────────────────────────────────────────────

    /**
     * 上游 [texture] 的**默认 `col` 函数**（`:668-673`）：
     * `function(x){ return "rgba(100,100,100," + (Math.random() * 0.3).toFixed(3) + ")"; }`
     *
     * ⚠️ **每调用一次抽 1 次随机**（上游在 TEXTURE 循环里逐条求值 ⇒ 每条皴线抽 1 次）。
     * ⚠️ 上游用 `.toFixed(3)` 定点串 ⇒ port 用 [ShanShuiUtil.toFixed] 复现同一次舍入。
     *
     * @param x 上游传入的是 `j / texlist.length`（比例）；上游的默认实现**忽略**该参数。
     */
    fun defaultCunCol(x: Double = 0.0): ShanShuiColor =
        ShanShuiColor.rgba(100, 100, 100, ShanShuiUtil.toFixed(ShanShuiPrng.global.rnd() * 0.3, 3))

    /** 上游 [blob] 默认 fun：分段 sqrt(sin)（`:589-593`）。 */
    private fun dabFun(x: Double): Double =
        if (x <= 1) Math.pow(Math.sin(x * Math.PI), 0.5)
        else -Math.pow(Math.sin((x + 1) * Math.PI), 0.5)

    /** 上游 [texture] 默认 noi：30/x（`:665-667`）。 */
    private fun cunNoi(x: Double): Double = 30.0 / x

    /** 上游 [texture] 默认 dis：三分分布（`:677-683`）。 */
    private fun cunDis(): Double {
        return if (ShanShuiPrng.global.rnd() > 0.5) {
            (1.0 / 3.0) * ShanShuiPrng.global.rnd()
        } else {
            2.0 / 3.0 + (1.0 / 3.0) * ShanShuiPrng.global.rnd()
        }
    }
}
