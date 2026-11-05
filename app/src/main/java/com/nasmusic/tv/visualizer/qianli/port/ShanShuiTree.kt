/**
 * 场景单元层 —— 树。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄**；[kind1]/[kind2]/[kind3] 另含 **改造-⑤**（返回轮廓列表），
 * [kind2] 另含 **改造-⑥**（簇心点 → blob 轮廓）。
 *
 * [branch] 照抄上游 `:919-989`（共享递归骨架，被多个 treeNN 复用）。
 *   上游默认值：hei=300, wid=6, ang=0, det=10, ben=PI*0.2。
 *   返回 [List<List<Point>>]（trlist1, trlist2）。
 *
 * [kind1] = 上游 tree01（`:744-802`）：细枝树。
 *   上游默认值：hei=50, wid=3, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *   ⚠️ 上游在 `i >= reso/4` 时逐点发射**叶簇 blob**（`:772-794`）：`for (j = 0; j < (reso - i) / 5; j++)`
 *   —— 上界是**浮点** ⇒ 每档 i 至少 1 个 ⇒ 实测 **9 个 blob/棵**（2+2+1+1+1+1+1），
 *   每个 blob 抽 **7** 次随机 ⇒ **63 次/棵**（node 实跑上游 JS 核对：tree01 = 63 draws / 11 polys）。
 *   ⚠️ 上游发**两条**独立 poly（line1、line2，`:798-800`）⇒ port 也发两条独立轮廓。
 *
 * [kind2] = 上游 tree02（`:803-841`）：阔叶簇。
 *   上游默认值：hei=16, wid=8, clu=5, col="rgba(100,100,100,0.5)", noi=0.5。
 *   每簇一次 blob 发射 ⇒ 每簇一条轮廓（fill=col / stroke=col / width=0）。
 *
 * [kind3] = 上游 tree03（`:842-917`）：塔形树。
 *   上游默认值：hei=50, wid=5, ben=默认0, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *   ⚠️ 上游在 `i >= reso/5` 时逐点发射**叶簇 blob**（`:877-902`）：`for (j = 0; j < (reso - i) * 2; j++)`
 *   ⇒ i = 2..9 ⇒ 16+14+12+10+8+6+4+2 = **72 个 blob/棵**，每个抽 7 次 ⇒ **504 次/棵**
 *   （node 实跑上游 JS 核对：tree03 = 504 draws / 73 polys）。
 *   ⚠️ 上游的发射顺序是 **trunk 在前、blobs 在后**（`:914-915`）。
 *
 * ⚠️ tree04–tree08 不移植（O8 开放项）；`flatDec` 内部调用 tree04/05/08 须替换或降级。
 *
 * ## 已知偏差（接受，不修）
 * - **D14：坐标精度** —— port 的 `Point = FloatArray` ⇒ 坐标是 **Float32**，
 *   上游是 Float64；几何一致，坐标有 ~1e-7 相对误差（渲染分辨率下不可见）。
 * - **tree 的 `noi` 是上游的死参数**（`:749` / `:809` / `:853` 声明后从未使用）：
 *   上游的 blob 调用**不传** `noi` ⇒ blob 恒用默认值 **0.5**；port 的三个 kind 也恒传 0.5。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本对象**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd()]；所有 `Noise.noise()` → [ShanShuiNoise.noise]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiTree {

    /**
     * 照抄上游 `:919-989`：分支骨架。
     * 先生成 g=3 段折线（每段随机偏转 ben），再沿法线扩展为双线 ribbon。
     * 返回 [trlist1, trlist2]（左右两侧点列）。
     */
    fun branch(
        hei: Double,
        wid: Double,
        ang: Double,
        det: Double,
        ben: Double,
    ): List<List<ShanShuiPath.Point>> {
        val tlist = ArrayList<ShanShuiPath.Point>()
        var nx = 0.0
        var ny = 0.0
        tlist.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
        var a0 = 0.0
        val g = 3
        for (i in 0 until g) {
            a0 += (ben / 2 + (ShanShuiPrng.global.rnd() * ben) / 2) *
                ShanShuiUtil.randChoice(listOf(-1, 1))
            nx += (Math.cos(a0) * hei) / g
            ny -= (Math.sin(a0) * hei) / g
            tlist.add(floatArrayOf(nx.toFloat(), ny.toFloat()))
        }
        val ta = Math.atan2(
            tlist[tlist.size - 1][1].toDouble(),
            tlist[tlist.size - 1][0].toDouble(),
        )
        // 旋转整条折线到目标角度
        for (i in 0 until tlist.size) {
            val a = Math.atan2(
                tlist[i][1].toDouble(),
                tlist[i][0].toDouble(),
            )
            val d = Math.sqrt(
                tlist[i][0].toDouble() * tlist[i][0] +
                    tlist[i][1].toDouble() * tlist[i][1],
            )
            tlist[i][0] = (d * Math.cos(a - ta + ang)).toFloat()
            tlist[i][1] = (d * Math.sin(a - ta + ang)).toFloat()
        }

        val trlist1 = ArrayList<ShanShuiPath.Point>()
        val trlist2 = ArrayList<ShanShuiPath.Point>()
        val span = det
        val tl = (tlist.size - 1) * span
        var lx = 0.0
        var ly = 0.0

        for (i in 0 until tl.toInt()) {
            val lastp = tlist[Math.floor(i.toDouble() / span).toInt()]
            val nextp = tlist[Math.ceil(i.toDouble() / span).toInt()]
            val p = (i % span).toDouble() / span
            val nx = lastp[0].toDouble() * (1 - p) + nextp[0].toDouble() * p
            val ny = lastp[1].toDouble() * (1 - p) + nextp[1].toDouble() * p

            val ang2 = Math.atan2(ny - ly, nx - lx)
            val woff = (ShanShuiNoise.noise(i * 0.3) - 0.5) * wid * hei / 80

            var b = 0.0
            if (p == 0.0) {
                b = ShanShuiPrng.global.rnd() * wid
            }

            val nw = wid * (((tl - i).toDouble() / tl) * 0.5 + 0.5)
            trlist1.add(floatArrayOf(
                (nx + Math.cos(ang2 + Math.PI / 2) * (nw + woff + b)).toFloat(),
                (ny + Math.sin(ang2 + Math.PI / 2) * (nw + woff + b)).toFloat(),
            ))
            trlist2.add(floatArrayOf(
                (nx + Math.cos(ang2 - Math.PI / 2) * (nw - woff + b)).toFloat(),
                (ny + Math.sin(ang2 - Math.PI / 2) * (nw - woff + b)).toFloat(),
            ))
            lx = nx
            ly = ny
        }

        return listOf(trlist1, trlist2)
    }

    /**
     * 照抄上游 tree01（`:744-802`）：细枝树。
     * reso=10，用 Noise.noise 生成左右偏移，构建 trunk 轮廓。
     * ⚠️ 上游还发**叶簇 blob**（`:772-794`）与**两条**独立 poly（`:798-800`）——
     * port 早先把两条 poly 并成一条闭合轮廓（多一条树顶水平连接线）⇒ 现按上游分开发射。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 发射顺序（与上游一致）
     * 1. 叶簇 blob（按 i = 3..9 递增，i 内的 j 递增）—— 每条 fill=str=叶色 / width=0；
     * 2. `poly(line1, {fil:"none", str:col, wid:1.5})` ⇒ fill=null / stroke=col / width=1.5；
     * 3. `poly(line2, {fil:"none", str:col, wid:1.5})` ⇒ 同上。
     *
     * ## PRNG 抽取次数（上游逐行核对，node 实跑上游 JS：tree01 = 63 draws / 11 polys）
     * - 叶簇：`i >= reso/4`（= 2.5 ⇒ i = 3..9）时 `for (j = 0; j < (reso - i) / 5; j++)`
     *   —— 上界是**浮点** ⇒ 每档 i 至少 1 个 ⇒ 共 **9 个 blob**；
     *   每个 blob 抽 **7** 次（x / y / len / wid / ang / alpha / blob 内部的 n0）
     *   ⇒ **63 次/棵**（与随机值无关，稳定）。
     */
    fun kind1(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val reso = 10
        val nslist = ArrayList<Pair<Double, Double>>(reso)
        for (i in 0 until reso) {
            nslist.add(
                ShanShuiNoise.noise(i * 0.5) to ShanShuiNoise.noise(i * 0.5, 0.5),
            )
        }

        val contours = ArrayList<ShanShuiContour>()
        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x
            val ny = y - (i * hei) / reso
            // 上游 :772-794：叶簇（`i >= reso/4`）
            // ⚠️ 上游 `reso / 4` 是**浮点**除法（10/4 = 2.5 ⇒ i = 3..9）；
            //    Int 除法会得 2 ⇒ 多出 i=2 那一档（2 个 blob）⇒ 抽取次数会多 14 次。
            if (i >= reso / 4.0) {
                var j = 0
                // ⚠️ 上界 `(reso - i) / 5` 是**浮点**（JS）⇒ `j < 0.2` 也成立 ⇒ 每档至少 1 个 blob
                while (j < (reso - i) / 5.0) {
                    // ① 上游 :775 —— blob 的 x 实参（第 1 次抽取）
                    val bx = nx + (ShanShuiPrng.global.rnd() - 0.5) * wid * 1.2 * (reso - i)
                    // ② 上游 :776 —— blob 的 y 实参（第 2 次抽取）
                    val by = ny + (ShanShuiPrng.global.rnd() - 0.5) * wid
                    // ③ 上游 :778 —— `len`（第 3 次抽取；对象字面量按源码顺序求值）
                    val blen = ShanShuiPrng.global.rnd() * 20 * (reso - i) * 0.2 + 10
                    // ④ 上游 :779 —— `wid`（第 4 次抽取）
                    val bwid = ShanShuiPrng.global.rnd() * 6 + 3
                    // ⑤ 上游 :780 —— `ang`（第 5 次抽取）
                    val bang = ((ShanShuiPrng.global.rnd() - 0.5) * Math.PI) / 6
                    // ⑥ 上游 :789 —— `col` 的 alpha（第 6 次抽取；上游 `.toFixed(1)` ⇒ 1 位小数）
                    val bcol = ShanShuiColor(
                        col.r,
                        col.g,
                        col.b,
                        ShanShuiUtil.toFixed(ShanShuiPrng.global.rnd() * 0.2 + col.a, 1),
                    )
                    // ⑦ 上游 blob 内部 `:606` 的 `n0`（第 7 次抽取，在 dab 内部）
                    //    ⚠️ 上游的 blob 调用不传 `noi` ⇒ 恒为 blob 默认 0.5
                    val plist = ShanShuiBrush.dab(bx, by, blen, bwid, bang, bcol, 0.5)
                    contours.add(ShanShuiContour(plist, bcol, bcol, 0.0))
                    j++
                }
            }
            line1.add(floatArrayOf(
                (nx + (nslist[i].first - 0.5) * wid - wid / 2).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (nslist[i].second - 0.5) * wid + wid / 2).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 :798-800：poly(line1,...) + poly(line2,...) ⇒ **两条独立**轮廓（不是一条闭合轮廓）
        contours.add(ShanShuiContour(line1, null, col, 1.5))
        contours.add(ShanShuiContour(line2, null, col, 1.5))
        return contours
    }

    /**
     * 照抄上游 tree02（`:803-841`）：阔叶簇。
     * 无 trunk 线，每簇一次 `blob(...)` 发射。
     *
     * 移植状态：**照抄** + **改造-⑥**（上游一次 blob 发射 = 一条轮廓）。
     *
     * ⚠️ 改造-⑥：port 早期版本只返回簇**中心点**（无法成轮廓）⇒ 改为每个簇发一条 blob 轮廓。
     * 上游 `:822-838` 的求值次序（JS 对象字面量按源码顺序求值）：
     * ① `x + randGaussian()*clu*4` ② `y + randGaussian()*clu*4`
     * ③ `wid = Math.random()*wid*0.75 + wid*0.5` ④ `len = Math.random()*hei*0.75 + hei*0.5`
     * ⑤ `blob` 内部再抽一次 `n0`（见 [ShanShuiBrush.dab]）。
     *
     * 轮廓样式：上游 `blob` 内部等价 `poly(plist, {fil:col, str:col, wid:0})`
     * ⇒ fill=col / stroke=col / width=0。
     *
     * ⚠️ 上游的 blob 调用**不传** `noi` ⇒ 恒为 blob 默认 0.5（tree 的 `noi` 是上游死参数 `:809`）。
     */
    fun kind2(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        clu: Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val contours = ArrayList<ShanShuiContour>(clu.toInt())
        for (i in 0 until clu.toInt()) {
            // 上游 `:824-825`：blob 的前两个实参
            val cx = x + ShanShuiUtil.randGaussian() * clu * 4
            val cy = y + ShanShuiUtil.randGaussian() * clu * 4
            // 上游 `:834-835`：对象字面量里的 wid 先于 len 求值（源码顺序）
            val blobWid = ShanShuiPrng.global.rnd() * wid * 0.75 + wid * 0.5
            val blobLen = ShanShuiPrng.global.rnd() * hei * 0.75 + hei * 0.5
            val plist = ShanShuiBrush.dab(
                cx, cy, blobLen, blobWid, Math.PI / 2, col, 0.5, ::tree02Fun,
            )
            contours.add(ShanShuiContour(plist, col, col, 0.0))
        }
        return contours
    }

    /**
     * 照抄上游 tree03（`:842-917`）：塔形树。
     * reso=10，ben 为函数参数（控制树干弯曲），构建 trunk 轮廓。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ## 发射顺序（与上游一致）
     * 1. `poly(lc, {fil:"white", str:col, wid:1.5})` ⇒ fill=白 / stroke=col / width=1.5（**trunk 在前**）；
     * 2. 叶簇 blob（上游 `:915` 的 `canv += blobs` ⇒ **blobs 在后**）——
     *    每条 fill=str=叶色 / width=0。
     *
     * ## PRNG 抽取次数（上游逐行核对，node 实跑上游 JS：tree03 = 504 draws / 73 polys）
     * - 叶簇：`i >= reso/5`（= 2 ⇒ i = 2..9）时 `for (j = 0; j < (reso - i) * 2; j++)`
     *   ⇒ 16+14+12+10+8+6+4+2 = **72 个 blob**；
     *   每个 blob 抽 **7** 次（ox / randChoice([-1,1]) / y / wid / ang / alpha / blob 内部的 n0）
     *   ⇒ **504 次/棵**（与随机值无关，稳定）。
     */
    fun kind3(
        x: Double,
        y: Double,
        hei: Double,
        wid: Double,
        ben: (Double) -> Double,
        col: ShanShuiColor,
        noi: Double,
    ): List<ShanShuiContour> {
        val reso = 10
        val nslist = ArrayList<Pair<Double, Double>>(reso)
        for (i in 0 until reso) {
            nslist.add(
                ShanShuiNoise.noise(i * 0.5) to ShanShuiNoise.noise(i * 0.5, 0.5),
            )
        }

        val contours = ArrayList<ShanShuiContour>()
        val blobContours = ArrayList<ShanShuiContour>()
        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x + ben(i.toDouble() / reso) * 100
            val ny = y - (i * hei) / reso
            // 上游 :877-902：叶簇（`i >= reso/5` ⇒ i = 2..9）
            // ⚠️ 上游 `reso / 5` 是浮点除法（10/5 = 2.0）；写成 5.0 以免误读成整数语义
            if (i >= reso / 5.0) {
                var j = 0
                while (j < (reso - i) * 2) {
                    // ① 上游 :882 —— `ox`（第 1 次抽取）
                    val ox = ShanShuiPrng.global.rnd() * wid * 2 * tree03Shape((reso - i) / reso.toDouble())
                    // ② 上游 :884 —— `randChoice([-1, 1])`（第 2 次抽取）
                    val sgn = ShanShuiUtil.randChoice(listOf(-1, 1))
                    val bx = nx + ox * sgn
                    // ③ 上游 :885 —— blob 的 y 实参（第 3 次抽取）
                    val by = ny + (ShanShuiPrng.global.rnd() - 0.5) * wid * 2
                    // ④ 上游 :888 —— `wid`（第 4 次抽取；`len: ox * 2` 不抽随机）
                    val bwid = ShanShuiPrng.global.rnd() * 6 + 3
                    // ⑤ 上游 :889 —— `ang`（第 5 次抽取）
                    val bang = ((ShanShuiPrng.global.rnd() - 0.5) * Math.PI) / 6
                    // ⑥ 上游 :898 —— `col` 的 alpha（第 6 次抽取；上游 `.toFixed(3)` ⇒ 3 位小数）
                    val bcol = ShanShuiColor(
                        col.r,
                        col.g,
                        col.b,
                        ShanShuiUtil.toFixed(ShanShuiPrng.global.rnd() * 0.2 + col.a, 3),
                    )
                    // ⑦ 上游 blob 内部 `:606` 的 `n0`（第 7 次抽取，在 dab 内部）
                    //    ⚠️ 上游的 blob 调用不传 `noi` ⇒ 恒为 blob 默认 0.5
                    val plist = ShanShuiBrush.dab(bx, by, ox * 2, bwid, bang, bcol, 0.5)
                    blobContours.add(ShanShuiContour(plist, bcol, bcol, 0.0))
                    j++
                }
            }
            line1.add(floatArrayOf(
                (nx + (((nslist[i].first - 0.5) * wid - wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (((nslist[i].second - 0.5) * wid + wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 :913-914：lc = line1.concat(line2.reverse()) → poly(lc, {fil:"white", str:col, wid:1.5})
        val lc = line1 + line2.reversed()
        contours.add(ShanShuiContour(lc, whiteFill, col, 1.5))
        // 上游 :915：canv += blobs（**在 trunk 之后**）
        contours.addAll(blobContours)
        return contours
    }

    // ─── private 辅助 ──────────────────────────────────────────

    /**
     * 上游 tree02 自带的 blob fun（`:829-833`）。
     *
     * 移植来源：上游 `:829-833`。移植状态：**照抄**。
     */
    private fun tree02Fun(x: Double): Double =
        if (x <= 1) {
            Math.pow(Math.sin(x * Math.PI) * x, 0.5)
        } else {
            -Math.pow(Math.sin((x - 2) * Math.PI * (x - 2)), 0.5)
        }

    /**
     * 上游 tree03 叶簇的 `shape` 函数（`:879-881`）：
     * `function(x){ return Math.log(50 * x + 1) / 3.95; }`
     *
     * 移植来源：上游 `:879-881`。移植状态：**照抄**。
     */
    private fun tree03Shape(x: Double): Double = Math.log(50 * x + 1) / 3.95

    /**
     * 上游 `fil: "white"` 对应的白色填充（上游 tree03 `:914` 用）。
     *
     * 移植来源：上游 `:914`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)
}
