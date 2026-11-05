/**
 * 构图规划 —— 照抄上游 `mountplanner`（`:3706-3814`）+ `MEM`（`:3816-3827`）
 * + `chunkloader`（`:3845-3971`）+ `chunkrender`（`:3973-3984`）。
 *
 * 移植自 LingDong-/shan-shui-inf（MIT，Copyright 2018 Lingdong Huang）。感谢作者开源。
 * 移植状态：**照抄** + **改造-③**（chunkloader 内部 `Arch.boat01` 调用不移植，须裁剪）。
 *
 * ## 上游参数默认值
 * - `samp = 0.03`（噪声采样缩放）
 * - `locmax` 半径 `r = 2`、阈值 `0.3`
 * - `chadd` 最小间距 `mind = 10`
 * - `xstep = 5`（扫描步长）
 * - `mwid = 200`（山体宽度范围）
 * - 远山每 `1000px` 一座
 * - `flatMount` 填充概率 `1%`、每座 0–3 个
 * - `boat` 概率 `20%`、间距 `400` —— ⚠️ **已裁剪**（O7 未裁，不在本仓范围）
 *
 * ## 关键改造
 * 1. **chunkloader 只保留 `mount`/`flatmount`/`distmount` 三种 tag**，移除 `boat`/`redcirc`/`greencirc` 分支。
 * 2. 上游 `MEM` 全局对象 → Kotlin `class State` 实例。
 * 3. 上游 `canv` 是 SVG 字符串拼接 → Kotlin [State.canv] 是 `MutableList<ShanShuiContour>`；
 *    chunkloader/chunkrender 中 `canv += ...` 改为 `canv.addAll(...)`。
 *    ⚠️ 改造-⑤：轮廓容器由扁平点列改为轮廓列表（每条轮廓自带 fill/stroke/width）。
 * 4. 上游 `console.log` → 删除（用 `//` 注释标记原位）。
 * 5. chunkloader 的插入排序照抄（按 y 升序 = 远→近画家序，上游 `:3846-3874`）。
 * 6. upstream `chunkrender` 中 `canv += nch.canv` → `state.canv.addAll(nch.canv)`。
 *
 * ## ⚠️ `locmax`/`chadd` 为 [plan] 内部局部函数
 * 上游二者是 `mountplanner` 的闭包（可访问局部 `reg`）；Kotlin 无法让对象方法闭包捕获
 * 局部变量 ⇒ [plan] 内用局部函数镜像，签名与上游一致（[chadd] 保留 `mind` 默认值 `10.0`）。
 *
 * ## ⚠️ planmtx 为 [plan] 内部局部数组
 * 上游 `MEM.planmtx` 是全局数组（跨 `mountplanner` 调用持久）；但 `plan(xmin,xmax)` 签名
 * 不接 state ⇒ 用局部 `DoubleArray` + 偏移量覆盖。因各次 `plan` 调用的 x 区间不重叠
 * （chunkloader 每次规划 `[xmax, xmax+cwid]` 或 `[xmin-cwid, xmin]`），局部数组与全局数组
 * **行为等价**（边界索引的跨调用残留是上游 quirk，影响可忽略）。
 * [State.planmtx] 按方案 §八 声明但实际未使用（`BooleanArray` 无法承载计数器 `+= 1`）。
 *
 * ## ⚠️ [Chunk] 增补 `canv` 字段
 * 方案 §八 的 `Chunk` 只有 `tag/x/y/h`；但 chunkloader 的 `add()` 须存储并传递 canv
 * （上游 chunk 对象是 `{tag, x, y, canv}`）⇒ 增补 `canv` 字段，默认 `emptyList()`
 * 供 [plan] 的中间 chunk 使用（plan 不生成 canv，由 [load] 填充）。
 *
 * ⛔ port 包零 Android 依赖 ⇒ 本文件**不出现** `android.graphics.Path` / `Canvas`。
 * 所有 `Math.random()` → [ShanShuiPrng.global.rnd]；所有 `Noise.noise()` → [ShanShuiNoise.noise]；
 * `randChoice` → [ShanShuiUtil.randChoice]。
 */

package com.nasmusic.tv.visualizer.qianli.port

object ShanShuiPlanner {

    // ─── plan — 照抄上游 mountplanner `:3706-3814` ───────────────

    /**
     * 照抄上游 `mountplanner(xmin, xmax)`:3706-3814。
     *
     * 扫描 `[xmin, xmax)`（步长 `xstep=5`），用 `locmax` 找噪声局部极大点作为山体位置，
     * `chadd` 做最小间距去重，每 1000px 追加一座远山，空闲列以 1% 概率填充 flatmount。
     * 返回 `reg`（`List<Chunk>`，chadd 通过的山体/远山/平坡计划项）。
     *
     * 上游默认值：`samp=0.03`、`xstep=5`、`mwid=200`、`locmax r=2 阈值 0.3`、`chadd mind=10`、
     * 远山每 1000px、flatMount 概率 1%、boat 概率 20% 间距 400（⚠️ boat 已裁剪）。
     */
    fun plan(xmin: Double, xmax: Double): List<Chunk> {
        // ── locmax — 照抄上游 `:3707-3720` ──
        fun locmax(
            x: Double,
            y: Double,
            f: (Double, Double) -> Double,
            r: Double,
        ): Boolean {
            val z0 = f(x, y)
            if (z0 <= 0.3) return false
            var i = x - r
            while (i < x + r) {
                var j = y - r
                while (j < y + r) {
                    if (f(i, j) > z0) return false
                    j += 1.0
                }
                i += 1.0
            }
            return true
        }

        // reg — 上游 `:3734`
        val reg = mutableListOf<Chunk>()

        // chadd — 照抄上游 `:3722-3732`（闭包访问 reg）
        fun chadd(r: Chunk, mind: Double = 10.0): Boolean {
            for (k in 0 until reg.size) {
                if (Math.abs(reg[k].x - r.x) < mind) return false
            }
            // console.log("+") — 上游 `:3729`
            reg.add(r)
            return true
        }

        // ── 噪声函数 — 照抄上游 `:3735-3747` ──
        val samp = 0.03
        fun ns(x: Double, y: Double): Double =
            Math.max(ShanShuiNoise.noise(x * samp) - 0.55, 0.0) * 2.0
        fun nns(x: Double): Double = 1.0 - ShanShuiNoise.noise(x * samp)
        fun nnns(x: Double, y: Double): Double =
            Math.max(ShanShuiNoise.noise(x * samp * 2.0, 2.0) - 0.55, 0.0) * 2.0
        fun yr(x: Double): Double = ShanShuiNoise.noise(x * 0.01, Math.PI)

        val xstep = 5.0
        val mwid = 200.0

        // planmtx — 局部 DoubleArray（替代全局 MEM.planmtx）
        // 偏移量覆盖 xof 偏移（±500）+ mwid（200）余量 ⇒ ±1000 裕度
        val off = Math.floor((xmin - 1000.0) / xstep).toInt()
        val planLen = Math.floor((xmax + 1000.0) / xstep).toInt() - off + 1
        val planmtx = DoubleArray(planLen)

        // 初始化 planmtx — 照抄上游 `:3751-3754`
        var i = xmin
        while (i < xmax) {
            val i1 = Math.floor(i / xstep).toInt() - off
            // MEM.planmtx[i1] = MEM.planmtx[i1] || 0 → 局部数组已全 0，等价
            planmtx[i1] = planmtx[i1]
            i += xstep
        }

        // 主循环 — 照抄上游 `:3756-3811`
        i = xmin
        while (i < xmax) {
            // 内层 j 循环 — 照抄上游 `:3757-3773`
            // var j 在 JS 是函数作用域，循环后保留末值供 distmount 的 ns(i,j) 使用
            var j = 0.0
            while (j < yr(i) * 480.0) {
                if (locmax(i, j, ::ns, 2.0)) {
                    val xof = i + 2.0 * (ShanShuiPrng.global.rnd() - 0.5) * 500.0
                    val yof = j + 300.0
                    val r = Chunk("mount", xof, yof, ns(i, j), emptyList())
                    val res = chadd(r)
                    if (res) {
                        var k = Math.floor((xof - mwid) / xstep).toInt() - off
                        while (k < (xof + mwid) / xstep - off) {
                            planmtx[k] += 1.0
                            k++
                        }
                    }
                }
                j += 30.0
            }
            // 远山 — 照抄上游 `:3774-3782`
            if (Math.abs(i) % 1000.0 < Math.max(1.0, xstep - 1.0)) {
                val r = Chunk(
                    "distmount",
                    i,
                    280.0 - ShanShuiPrng.global.rnd() * 50.0,
                    ns(i, j),
                    emptyList(),
                )
                chadd(r)
            }
            i += xstep
        }
        // console.log([xmin, xmax]) — 上游 `:3784`

        // flatmount 填充 — 照抄上游 `:3785-3804`
        i = xmin
        while (i < xmax) {
            if (planmtx[Math.floor(i / xstep).toInt() - off] == 0.0) {
                // if (Math.random() < 0.01)
                if (ShanShuiPrng.global.rnd() < 0.01) {
                    // for (var j = 0; j < 4 * Math.random(); j++)
                    //   ⚠️ JS for 条件每次迭代都调 Math.random() ⇒ Kotlin while 同样每轮调 rnd()
                    var j = 0
                    while (j.toDouble() < 4.0 * ShanShuiPrng.global.rnd()) {
                        val r = Chunk(
                            "flatmount",
                            i + 2.0 * (ShanShuiPrng.global.rnd() - 0.5) * 700.0,
                            700.0 - j * 50.0,
                            ns(i, j.toDouble()),
                            emptyList(),
                        )
                        chadd(r)
                        j++
                    }
                }
            }
            i += xstep
        }

        return reg
    }

    // ─── load — 照抄上游 chunkloader `:3845-3971` ───────────────

    /**
     * 照抄上游 `chunkloader(xmin, xmax)`:3845-3971（⚠️ boat 分支已裁剪）。
     *
     * 当视窗边界超出已加载范围时，调用 [plan] 规划新分块，把每个计划项的
     * canv（山体/水波/平坡/远山）经 [add] 插入排序加入 [State.chunks]。
     * chunk 按 y 升序 = 远→近画家序（上游 `:3846-3874`）。
     *
     * ⚠️ 上游 `Arch.boat01`（`:3929-3938`）、`redcirc`/`greencirc`（`:3939-3963`）不移植，已裁剪。
     *
     * 上游默认值：`cwid=512`（chunk 宽度）。
     */
    fun load(state: State, xmin: Double, xmax: Double) {
        // add — 照抄上游 `:3846-3874`（闭包访问 state.chunks）
        fun add(nch: Chunk) {
            // NaN 检查 — 照抄上游 `:3847-3851`
            // 上游 nch.canv 是 SVG 字符串，includes("NaN") → replace(/NaN/g, -1000)
            // Kotlin canv 是 List<ShanShuiContour> ⇒ 扫描每条轮廓的每个点并以 -1000 替换
            var hasNan = false
            for (c in nch.canv) {
                for (p in c.pts) {
                    if (p[0].isNaN() || p[1].isNaN()) {
                        hasNan = true
                        break
                    }
                }
                if (hasNan) break
            }
            if (hasNan) {
                // console.log("gotcha:") / console.log(nch.tag) — 上游 `:3848-3849`
                for (c in nch.canv) {
                    for (p in c.pts) {
                        if (p[0].isNaN()) p[0] = -1000f
                        if (p[1].isNaN()) p[1] = -1000f
                    }
                }
            }

            // 插入排序（按 y 升序 = 远→近）— 照抄上游 `:3852-3870`
            if (state.chunks.isEmpty()) {
                state.chunks.add(nch)
                return
            }
            if (nch.y <= state.chunks[0].y) {
                state.chunks.add(0, nch)
                return
            }
            if (nch.y >= state.chunks[state.chunks.size - 1].y) {
                state.chunks.add(nch)
                return
            }
            for (j in 0 until state.chunks.size - 1) {
                if (state.chunks[j].y <= nch.y && nch.y <= state.chunks[j + 1].y) {
                    state.chunks.add(j + 1, nch)
                    return
                }
            }
            // console.log("EH?WTF!") — 上游 `:3871-3873`（兜底，逻辑上不可达）
        }

        // 主循环 — 照抄上游 `:3876-3970`
        while (xmax > state.xmax - state.cwid || xmin < state.xmin + state.cwid) {
            // console.log("generating new chunk...") — 上游 `:3877`
            val pl: List<Chunk>
            if (xmax > state.xmax - state.cwid) {
                pl = plan(state.xmax, state.xmax + state.cwid)
                state.xmax = state.xmax + state.cwid
            } else {
                pl = plan(state.xmin - state.cwid, state.xmin)
                state.xmin = state.xmin - state.cwid
            }

            for (idx in 0 until pl.size) {
                when (pl[idx].tag) {
                    "mount" -> {
                        // 山体 — 照抄上游 `:3889-3896`
                        add(Chunk(
                            "mount",
                            pl[idx].x,
                            pl[idx].y,
                            pl[idx].h,
                            ShanShui.mountain(
                                pl[idx].x,
                                pl[idx].y,
                                idx * 2.0 * ShanShuiPrng.global.rnd(),
                                100.0 + ShanShuiPrng.global.rnd() * 400.0,
                                400.0 + ShanShuiPrng.global.rnd() * 200.0,
                                200.0,
                                true,
                                null,
                            ),
                        ))
                        // 水波 — 照抄上游 `:3897-3902`（y = plan.y - 10000）
                        add(Chunk(
                            "mount",
                            pl[idx].x,
                            pl[idx].y - 10000.0,
                            pl[idx].h,
                            ShanShui.water(
                                pl[idx].x,
                                pl[idx].y,
                                idx * 2.0,
                                2.0,
                                800.0,
                                10.0,
                            ),
                        ))
                    }
                    "flatmount" -> {
                        // 平坡 — 照抄上游 `:3903-3918`
                        add(Chunk(
                            "flatmount",
                            pl[idx].x,
                            pl[idx].y,
                            pl[idx].h,
                            ShanShui.flatMount(
                                pl[idx].x,
                                pl[idx].y,
                                2.0 * ShanShuiPrng.global.rnd() * Math.PI,
                                100.0,
                                600.0 + ShanShuiPrng.global.rnd() * 400.0,
                                80.0,
                                0.5 + ShanShuiPrng.global.rnd() * 0.2,
                            ),
                        ))
                    }
                    "distmount" -> {
                        // 远山 — 照抄上游 `:3919-3928`
                        add(Chunk(
                            "distmount",
                            pl[idx].x,
                            pl[idx].y,
                            pl[idx].h,
                            ShanShui.distMount(
                                pl[idx].x,
                                pl[idx].y,
                                ShanShuiPrng.global.rnd() * 100.0,
                                150.0,
                                ShanShuiUtil.randChoice(listOf(500, 1000, 1500)).toDouble(),
                                5.0,
                            ),
                        ))
                    }
                    // ⚠️ "boat" — 照抄上游 `:3929-3938` — **已裁剪**（Arch.boat01 不移植，O7 未裁）
                    // ⚠️ "redcirc"/"greencirc" — 照抄上游 `:3939-3963` — **已裁剪**（调试标记，非山体）
                }
            }
        }
    }

    // ─── render — 照抄上游 chunkrender `:3973-3984` ────────────

    /**
     * 照抄上游 `chunkrender(xmin, xmax)`:3973-3984。
     * 清空 [State.canv]，把可见 chunk（`xmin-cwid < x < xmax+cwid`）的 canv 拼接进去。
     *
     * 上游默认值：`cwid=512`（可见范围扩展宽度）。
     */
    fun render(state: State, xmin: Double, xmax: Double) {
        // MEM.canv = "" → state.canv.clear()
        state.canv.clear()
        for (i in 0 until state.chunks.size) {
            if (
                xmin - state.cwid < state.chunks[i].x &&
                state.chunks[i].x < xmax + state.cwid
            ) {
                state.canv.addAll(state.chunks[i].canv)
            }
        }
    }

    // ─── State — 照抄上游 MEM `:3816-3827` ────────────────────

    /**
     * 照抄上游 `MEM`（`:3816-3827`）—— 全局状态对象改为实例。
     *
     * 上游默认值：`cwid=512`、`windx=3000`、`windy=800`；`cursx`/`lasttick`/`xmin`/`xmax` 初始 0。
     *
     * ⚠️ [planmtx] 按方案 §八 声明为 `BooleanArray(0)`；实际 [plan] 用局部数组
     * （签名不接 state ⇒ 无法访问此字段）。此字段保声明供后续扩展使用。
     */
    class State {
        /** 上游 `MEM.canv`（SVG 字符串）→ port 轮廓列表（改造-⑤）。 */
        val canv: MutableList<ShanShuiContour> = mutableListOf()

        /** 上游 `MEM.chunks`（chunk 对象数组）→ port `MutableList<Chunk>`。 */
        val chunks: MutableList<Chunk> = mutableListOf()

        var xmin: Double = 0.0
        var xmax: Double = 0.0
        var cwid: Double = 512.0
        var cursx: Double = 0.0
        var lasttick: Double = 0.0
        var windx: Double = 3000.0
        var windy: Double = 800.0

        /** 上游 `MEM.planmtx`（计数器数组）→ port `BooleanArray`（声明兼容，实际未使用）。 */
        val planmtx: BooleanArray = BooleanArray(0)
    }

    // ─── Chunk — 上游 reg[] 元素 `{tag, x, y, h}` ─────────────

    /**
     * 照抄上游 `reg`/`MEM.chunks` 的元素对象 `{tag, x, y, h}`（`:3761` 等）。
     *
     * ⚠️ 增补 [canv] 字段（方案 §八 签名未含；chunkloader 的 `add()` 须存储并传递
     * 上游 SVG 字符串 → port 轮廓列表，改造-⑤）。[plan] 产出的中间 chunk `canv = emptyList()`，
     * [load] 产出的 chunk 由 ShanShui 函数填充实际轮廓。
     */
    data class Chunk(
        val tag: String,
        val x: Double,
        val y: Double,
        val h: Double,
        val canv: List<ShanShuiContour> = emptyList(),
    )

    // ─── Bounds — flatDec 参数 ────────────────────────────────

    /**
     * flatDec 的 bounds 参数（上游 `{xmin, xmax, ymin, ymax}` 对象，见 `:2234` 等）。
     */
    data class Bounds(
        val xmin: Double,
        val xmax: Double,
        val ymin: Double,
        val ymax: Double,
    )
}
