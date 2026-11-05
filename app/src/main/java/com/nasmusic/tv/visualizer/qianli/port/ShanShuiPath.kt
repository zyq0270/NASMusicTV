/**
 * 路径工具 —— `Point = FloatArray(2)`（`[x, y]`，纯数据，零分配）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * [bezmh]/[divide] 移植状态：**照抄**；[fillPath]/[strokePath]/[polyToPath] 移植状态：**改造-①**（SVG 字符串 → List<Point>）。
 *
 * [bezmh] 照抄上游 `:445-483`（过中点二次贝塞尔，`pl=20`）。
 * [divide] 照抄上游 `:627-651`（折线等分）。
 * [fillPath] / [strokePath] / [polyToPath] 是 `poly()` 的 Kotlin 化改造
 * （上游 `:485-510` 返回 SVG 字符串 → port 包返回 `List<Point>`；渲染器侧再转 `android.graphics.Path`）。
 * ⚠️ **这三个函数是死代码**（改造-⑤ 之后轮廓自带 fill/stroke/width ⇒ 渲染器直接用
 * [ShanShuiContour] 的点列画路径，不再经过它们）⇒ 保留只为留档，本轮不改其行为。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path`。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiPath {
    /** `[x, y]` 纯数据点列。 */
    typealias Point = FloatArray

    /**
     * 照抄上游 `:445-483`（过中点二次贝塞尔）。
     * `w` 为张力参数（上游默认 1）。`pl=20` 段精度。
     */
    fun bezmh(P: List<Point>, w: Double = 1.0): List<Point> {
        val pl = 20
        val plist = ArrayList<Point>()
        val p = if (P.size == 2) {
            listOf(P[0], midPt(P[0], P[1]), P[1])
        } else P
        for (j in 0 until p.size - 2) {
            val p0 = if (j == 0) p[j] else midPt(p[j], p[j + 1])
            val p1 = p[j + 1]
            val p2 = if (j == p.size - 3) p[j + 2] else midPt(p[j + 1], p[j + 2])
            for (i in 0 until pl + if (j == p.size - 3) 1 else 0) {
                val t = i.toDouble() / pl
                val u = Math.pow(1 - t, 2.0) + 2 * t * (1 - t) * w + t * t
                plist.add(
                    floatArrayOf(
                        ((Math.pow(1 - t, 2.0) * p0[0] +
                            2 * t * (1 - t) * p1[0] * w +
                            t * t * p2[0]) / u).toFloat(),
                        ((Math.pow(1 - t, 2.0) * p0[1] +
                            2 * t * (1 - t) * p1[1] * w +
                            t * t * p2[1]) / u).toFloat(),
                    )
                )
            }
        }
        return plist
    }

    /**
     * 照抄上游 `:627-651`（折线等分）。
     * 在相邻两点间插入 `reso-1` 个等分点。
     */
    fun divide(plist: List<Point>, reso: Int): List<Point> {
        if (plist.isEmpty()) return emptyList()
        val tl = (plist.size - 1) * reso
        val rlist = ArrayList<Point>(tl + 1)
        var lx = 0.0
        var ly = 0.0
        for (i in 0 until tl) {
            val lastp = plist[Math.floor(i.toDouble() / reso).toInt()]
            val nextp = plist[Math.ceil(i.toDouble() / reso).toInt()]
            val p = (i % reso).toDouble() / reso
            val nx = lastp[0].toDouble() * (1 - p) + nextp[0].toDouble() * p
            val ny = lastp[1].toDouble() * (1 - p) + nextp[1].toDouble() * p
            rlist.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
            lx = nx
            ly = ny
        }
        rlist.add(plist[plist.size - 1])
        return rlist
    }

    /**
     * 改造-①：`poly()` 的填充变体。
     * 上游 `:485-510` 返回 SVG `<polyline>` 字符串 → 此处返回偏移后的 `List<Point>`。
     * 渲染器侧用 `android.graphics.Path` 画闭合填充多边形。
     */
    fun fillPath(
        plist: List<Point>,
        xof: Double = 0.0,
        yof: Double = 0.0,
        col: ShanShuiColor,
    ): List<Point> = plist.map {
        floatArrayOf((it[0] + xof).toFloat(), (it[1] + yof).toFloat())
    }

    /**
     * 改造-①：`poly()` 的描边变体。
     * 返回偏移后的点列，渲染器侧画为描边折线。
     */
    fun strokePath(
        plist: List<Point>,
        xof: Double = 0.0,
        yof: Double = 0.0,
        col: ShanShuiColor,
        wid: Double,
    ): List<Point> = plist.map {
        floatArrayOf((it[0] + xof).toFloat(), (it[1] + yof).toFloat())
    }

    /**
     * 改造-①：`poly()` 的通用变体（填充 + 描边 + 线宽）。
     * 返回偏移后的点列；渲染器侧决定如何绘制（fill/stroke/both）。
     */
    fun polyToPath(
        plist: List<Point>,
        xof: Double = 0.0,
        yof: Double = 0.0,
        fil: ShanShuiColor,
        str: ShanShuiColor,
        wid: Double,
    ): List<Point> = plist.map {
        floatArrayOf((it[0] + xof).toFloat(), (it[1] + yof).toFloat())
    }

    /**
     * 中点（上游 `PolyTools.midPt` `:208-222` 的**两点**内联版）。
     *
     * ⚠️ **只服务本对象内部的 `bezmh`**（上游 `:448`/`:458`/`:464` 的 `midPt(P[0], P[1])`
     *   —— 传两个实参 ⇒ 上游 `Array.apply(null, arguments)` ⇒ 长度 2 ⇒ 除以 2 ⇒ 与本函数等价）。
     * ⛔ **不得**改成本文件的 n 点版：上游 `PolyTools.triangulate` 的 `midPt(T[k])` 是 **3 点**，
     *   两点版不够用 ⇒ n 点版已单独移植到 [ShanShuiPoly.midPt] / [ShanShuiPoly.midPtExact]。
     */
    private fun midPt(a: Point, b: Point): Point =
        floatArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
}
