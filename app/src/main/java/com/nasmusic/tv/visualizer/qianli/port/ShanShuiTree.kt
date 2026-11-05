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
 *   返回 trunk 轮廓点列（line1 + line2.reverse()）—— 包装为一条轮廓（fill=null / stroke=col / width=1.5）。
 *
 * [kind2] = 上游 tree02（`:803-841`）：阔叶簇。
 *   上游默认值：hei=16, wid=8, clu=5, col="rgba(100,100,100,0.5)", noi=0.5。
 *   每簇一次 blob 发射 ⇒ 每簇一条轮廓（fill=col / stroke=col / width=0）。
 *
 * [kind3] = 上游 tree03（`:842-917`）：塔形树。
 *   上游默认值：hei=50, wid=5, ben=默认0, col="rgba(100,100,100,0.5)", noi=0.5。reso=10。
 *   返回 trunk 轮廓点列（line1 + line2.reverse()，含 ben 曲线偏移）—— 包装为一条轮廓
 *   （fill=白 / stroke=col / width=1.5）。
 *
 * ⚠️ tree04–tree08 不移植（O8 开放项）；`flatDec` 内部调用 tree04/05/08 须替换或降级。
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
     * 返回 line1 + line2.reverse()（闭合多边形点列）。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ⚠️ 上游 `:798-800` 发射**两条**独立 poly：`poly(line1, {fil:"none", str:col, wid:1.5})`
     * 与 `poly(line2, {fil:"none", str:col, wid:1.5})`；port 早先把两条并成一条闭合轮廓
     * （几何不变）⇒ 轮廓样式取上游的 fill=null / str=col / wid=1.5。
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

        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x
            val ny = y - (i * hei) / reso
            line1.add(floatArrayOf(
                (nx + (nslist[i].first - 0.5) * wid - wid / 2).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (nslist[i].second - 0.5) * wid + wid / 2).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 `:798-801`：poly(line1,...) + poly(line2,...) → port 返回闭合轮廓
        val lc = line1 + line2.reversed()
        return listOf(ShanShuiContour(lc, null, col, 1.5))
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
                cx, cy, blobLen, blobWid, Math.PI / 2, col, noi, ::tree02Fun,
            )
            contours.add(ShanShuiContour(plist, col, col, 0.0))
        }
        return contours
    }

    /**
     * 照抄上游 tree03（`:842-917`）：塔形树。
     * reso=10，ben 为函数参数（控制树干弯曲），构建 trunk 轮廓。
     * 返回 line1 + line2.reverse()（闭合多边形点列）。
     *
     * 移植状态：**照抄**（改造-⑤：返回轮廓列表）。
     *
     * ⚠️ 上游 `:914`：poly(lc, {fil:"white", str:col, wid:1.5}) ⇒ fill=白 / stroke=col / width=1.5。
     * ⚠️ 上游 `:877-902` 的叶簇 blob 未移植（改造-③）⇒ 本函数只发树干一条轮廓。
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

        val line1 = ArrayList<ShanShuiPath.Point>(reso)
        val line2 = ArrayList<ShanShuiPath.Point>(reso)
        for (i in 0 until reso) {
            val nx = x + ben(i.toDouble() / reso) * 100
            val ny = y - (i * hei) / reso
            line1.add(floatArrayOf(
                (nx + (((nslist[i].first - 0.5) * wid - wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
            line2.add(floatArrayOf(
                (nx + (((nslist[i].second - 0.5) * wid + wid / 2) * (reso - i)) / reso).toFloat(),
                ny.toFloat(),
            ))
        }
        // 上游 `:913-914`：lc = line1.concat(line2.reverse()) → port 返回闭合轮廓
        val lc = line1 + line2.reversed()
        return listOf(ShanShuiContour(lc, whiteFill, col, 1.5))
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
     * 上游 `fil: "white"` 对应的白色填充（上游 tree03 `:914` 用）。
     *
     * 移植来源：上游 `:914`。移植状态：**照抄**（改造-④：字符串 → 纯数据）。
     */
    private val whiteFill: ShanShuiColor = ShanShuiColor(255, 255, 255, 1.0)
}
