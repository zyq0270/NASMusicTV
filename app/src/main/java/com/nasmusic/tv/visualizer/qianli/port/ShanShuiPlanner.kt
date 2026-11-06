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
 * ## ✅ `MEM.planmtx` 是**模块级全局**（上游语义已对齐）
 * 上游 `MEM.planmtx`（`:3826`）是 `MEM` 上的**全局数组**，`mountplanner` 被反复调用时
 * **继续累积**（`:3753` 初始化 / `:3769` 计数 / `:3786` 读取）。
 * 本移植层此前用「每次调用新建的局部 `DoubleArray` + 相对下标」代替 ⇒ **偏离**（原 D15）。
 * 现改为 [planmtx]（companion 里的**全局**稀疏计数器，**绝对下标**）⇒ 与上游同构。
 *
 * ### 复现上游的三个 JS 语义细节（⛔ 不许简化）
 * 1. **下标是绝对的**：`MEM.planmtx[floor(i / xstep)]` ⇒ `xstep=5` 时下标 = `x / 5`。
 * 2. **初始化用 `||`（JS 宽松真值）**：`MEM.planmtx[i1] = MEM.planmtx[i1] || 0`
 *    ⇒ `undefined` / `0` / `-0` / `NaN` 都变成 `0`；其余（真值）**保持不变**。
 * 3. **计数用 `+= 1`，而未初始化的槽位是 `undefined`** ⇒ `undefined + 1 = NaN`！
 *    ⇒ 本移植层必须把「未初始化」的槽位记成 `NaN`（不是 0、不是 1），
 *    否则下一次初始化时 `NaN || 0 ⇒ 0`（被清零）与 `1 || 0 ⇒ 1`（保留）会**分道扬镳**。
 *
 * ### 释放（长驻进程）
 * 上游的 `MEM.planmtx` 是**永不回收**的 JS 数组（页面生命周期内）⇒ 移植到长驻进程
 * 必须设上限，否则场景滚动时无界增长。见 [trimPlanmtx]。
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

    // ─── planmtx — 上游 MEM.planmtx（`:3826`）的模块级全局 ──────────

    /**
     * 上游 `MEM.planmtx` 的**全局**计数器表 —— **绝对下标**（= `floor(x / xstep)`）。
     *
     * - 用 [TreeMap]（按下标排序）⇒ 可丢弃最左/最右的旧下标（见 [trimPlanmtx]）。
     * - 值语义**逐字复现上游 JS**：
     *   - 「槽位不存在」⇒ `null`（对应 JS 的 `undefined`）；
     *   - 「已计数但从未被初始化」⇒ `NaN`（对应 JS 的 `undefined + 1`）;
     *   - 「已初始化」⇒ `0.0` 或正整数。
     */
    private val planmtx = java.util.TreeMap<Int, Double>()

    /** 扫描步长（上游 `xstep`，`:3749`）—— 下标 = `floor(x / xstep)`。 */
    private const val XSTEP = 5.0

    /** 保留窗口（列数）：窗口外的下标一律丢弃（左右两侧都丢 ⇒ 与滚动方向无关）。 */
    private const val PLANMTX_KEEP_COLUMNS = 4096

    /** 计数器表硬上限（条目数）⇒ 防止无界增长（内存门禁会查）。 */
    private const val PLANMTX_MAX_ENTRIES = 8192

    /**
     * 复位全局 [planmtx] —— **仅供测试隔离**用。
     *
     * ⚠️ 上游无此函数（上游「刷新页面」等价于复位：`MEM` 每次页面加载都是新的）。
     * 本仓的门禁要断言「同种子两次调用逐位相等」，而 `planmtx` 是**跨调用累积**的全局状态
     * ⇒ 必须能复位，否则第二次调用看到的是第一次的残留。
     */
    @Synchronized
    fun resetPlanmtx() {
        planmtx.clear()
    }

    /**
     * 上游 `MEM.planmtx[idx] == 0`（**JS 宽松相等**）的等价判定。
     *
     * ⚠️ JS 语义：`undefined == 0` ⇒ **false**（槽位不存在 ⇒ 视为「已被占用」）；
     *   `NaN == 0` ⇒ **false**；`0 == 0` ⇒ true。
     */
    private fun planmtxIsZero(idx: Int): Boolean {
        val v = planmtx[idx] ?: return false
        return v == 0.0
    }

    /**
     * 释放远离当前扫描区间的下标（上游无此步 —— 上游是短命的网页）。
     *
     * ⚠️ 被丢弃的下标一定远在当前扫描区间之外 ⇒ 既不会被本次的初始化循环读到，
     *   也不会被本次的填充判定读到 ⇒ **与上游等价**。
     */
    private fun trimPlanmtx(xmin: Double, xmax: Double) {
        val lo = Math.floor(xmin / XSTEP).toInt() - PLANMTX_KEEP_COLUMNS
        val hi = Math.floor(xmax / XSTEP).toInt() + PLANMTX_KEEP_COLUMNS
        val it = planmtx.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k < lo || k > hi) {
                it.remove()
            }
        }
        // 仍超上限时丢弃最左（最旧）的下标。
        while (planmtx.size > PLANMTX_MAX_ENTRIES) {
            planmtx.remove(planmtx.firstKey())
        }
    }

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
     *
     * ⚠️ **本函数会读写全局 [planmtx]**（上游 `MEM.planmtx` 的等价物）⇒
     *   标 `@Synchronized`（对象监视器）⇒ 多线程调用不会交错。
     */
    @Synchronized
    fun plan(xmin: Double, xmax: Double, isolatePlanmtx: Boolean = false): List<Chunk> {
        // ⛔⛔ **不再无条件清空 planmtx**（2026-11-06 删除 `planmtx.clear() // TEMP-TEETH-EXPERIMENT`）。
        //
        // 上游 `MEM.planmtx`（`:3826`）是**模块级全局**，`mountplanner` 被反复调用时**继续累积**
        // （上游 `:3753` 初始化 / `:3769` 计数 / `:3786` 读取）⇒ 上一段落下的山体会把
        // ±200 世界单位（`mwid`）内的列标记为「已占位」⇒ **跨段生效**。
        //
        // ⚠️ 但**设色（INK）渲染器不能用这个口径**：它有**四个彼此独立的世界**（远/中/近/水），
        // 每个世界**各自**调 [plan] ⇒ 共用同一个全局 planmtx 时，中景世界先把列标记成「已有山」
        // ⇒ 近景世界再跑时 `planmtx[i] == 0` 恒假 ⇒ **一个 flatmount 都不产** ⇒ 近景层空
        // （2026-11-06 真踩到：`QianliContentRangeGateTest` 报「L4 必须有已规划 chunk」）。
        // ⇒ 该路径必须显式传 `isolatePlanmtx = true`（= 旧的「每次调用清零」口径）。
        //
        // ⛔ 「忠于上游」渲染路径（`QianliUpstreamRenderer`）**必须走默认值**（累积）：
        //   它直接用上游的 `chunkloader`/`chunkrender`，只有**一条**规划流 ⇒ 语义与上游一致。
        //
        // ⚠️ 注意上游的初始化是 `MEM.planmtx[i1] = MEM.planmtx[i1] || 0`（`:3753`）
        // ⇒ 已计数（>0）的槽位**保持不变**，只有未初始化/undefined/NaN 的才归 0。
        // 本移植层的对应实现见下面「初始化 planmtx」段。
        //
        // ⛔ **不要**在这里无条件加 `planmtx.clear()`：那会把「跨调用累积」这个上游语义整个抹掉。
        // 测试隔离请用 [resetPlanmtx]。
        if (isolatePlanmtx) {
            planmtx.clear()
        }
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

        val xstep = XSTEP
        val mwid = 200.0

        // 初始化 planmtx — 照抄上游 `:3751-3754`
        //   `MEM.planmtx[i1] = MEM.planmtx[i1] || 0`
        // ⚠️ 下标是**绝对下标**（`floor(i / xstep)`）—— 上游的 `MEM.planmtx` 是全局数组。
        // ⚠️ JS 的 `||` 对 `undefined` / `0` / `-0` / `NaN` 都取右值 `0`，其余（真值）保持不变。
        var i = xmin
        while (i < xmax) {
            val i1 = Math.floor(i / xstep).toInt()
            val v = planmtx[i1]
            planmtx[i1] = if (v == null || v == 0.0 || v.isNaN()) 0.0 else v
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
                        // 上游 `:3764-3770`：`MEM.planmtx[k] += 1`
                        // ⚠️ **绝对下标**；⚠️ 未初始化的槽位在 JS 里是 `undefined`
                        //    ⇒ `undefined + 1 = NaN`（必须原样复现，见文件头「三个 JS 语义细节」）。
                        var k = Math.floor((xof - mwid) / xstep).toInt()
                        while (k < (xof + mwid) / xstep) {
                            val v = planmtx[k]
                            planmtx[k] = (v ?: Double.NaN) + 1.0
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
            // 上游 `:3786`：`MEM.planmtx[Math.floor(i / xstep)] == 0`
            // ⚠️ 绝对下标；⚠️ JS 宽松相等（`undefined == 0` ⇒ false ⇒ 视为「已被占用」）。
            if (planmtxIsZero(Math.floor(i / xstep).toInt())) {
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

        // boat — 照抄上游 `:3806-3811`
        //   ⚠️ PRNG 顺序：先 `Math.random() < 0.2`（if 条件），再 `300 + Math.random() * 390`（块内）。
        //   ⚠️ `chadd(r, 400)` 的 mind = 400（⛔ 不是别处默认的 10）⇒ 舟间距 ≥ 400 世界单位。
        //   ⚠️ 上游的 boat 条目没有 `h` 字段 ⇒ 这里填 0.0（`h` 在上游从不被读）。
        i = xmin
        while (i < xmax) {
            if (ShanShuiPrng.global.rnd() < 0.2) {
                val r = Chunk(
                    "boat",
                    i,
                    300.0 + ShanShuiPrng.global.rnd() * 390.0,
                    0.0,
                    emptyList(),
                )
                chadd(r, 400.0)
            }
            i += xstep
        }

        // 释放（长驻进程）：丢弃远离本次扫描区间的旧下标 ⇒ 防止无界增长。
        trimPlanmtx(xmin, xmax)

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
                    "boat" -> {
                        // 扁舟 — 照抄上游 `:3929-3938`
                        //   ⚠️ PRNG 顺序：第 3 实参的 `Math.random()`（种子）先求值，
                        //      再对象字面量 `{sca, fli}` 的 `randChoice` 后求值。
                        //   ⚠️ `sca = plan.y / 800`（上游 `MEM.windy = 800`）。
                        add(Chunk(
                            "boat",
                            pl[idx].x,
                            pl[idx].y,
                            pl[idx].h,
                            ShanShuiBoat.boat01(
                                pl[idx].x,
                                pl[idx].y,
                                ShanShuiPrng.global.rnd(),
                                120.0,
                                pl[idx].y / 800.0,
                                ShanShuiUtil.randChoice(listOf(true, false)),
                            ),
                        ))
                    }
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
     * ⚠️ 上游 `MEM` 是**模块级全局**；本移植层把它拆成实例 [State]（`canv`/`chunks`/
     *   `xmin`/`xmax` 等按场景持有）。
     *   ⚠️ **例外**：`MEM.planmtx` 在上游也是全局的，且 `mountplanner` 跨调用累积它
     *   ⇒ 本移植层把它放在 [ShanShuiPlanner] 的 companion（全局 [planmtx]），
     *   **不**放在本类里（否则多实例会各自一份 ⇒ 偏离上游）。
     */
    /**
     * 丢弃已经滚远了的 chunk（**只裁，不重置**）。
     *
     * ## 为什么是「裁剪」而不是「整体重置」
     *
     * 上游 `MEM.chunks` 是**永不回收**的数组 ⇒ 世界理论上无限长，但内存无限增长。
     * 本仓必须设上限 ⇒ 早期做法是「世界走远就把整份 state 换成新的」（整体重置），
     * ⛔ 但那会**丢掉全部历史** ⇒ 新生成的块只剩自己那一小段区间的内容
     * ⇒ 与旧块（内容丰富）在边界处**内容密度突变** ⇒ 画面上出现「断」（真机截图证据）。
     *
     * ⇒ 改成**只裁掉滚远了的图元**（[trimChunks]）⇒
     * · 历史保留 ⇒ 新旧块**同源**（都来自同一份累积画布）⇒ **画面无限连续** ✓
     * · 内存有界（裁掉的图元不再参与绘制）✓
     *
     * ⚠️ [State.chunks] 是按 **y 升序**（画家序）排的 ⇒ 不能按 x 有序地切 ⇒ 只能整表扫一遍。
     * 扫的频率很低（世界走很远才裁一次）⇒ 可接受。
     *
     * @param keepFromX 小于该世界 x 的 chunk 会被丢弃。
     * @return 丢弃的 chunk 数。
     */
    fun trimChunks(state: State, keepFromX: Double): Int {
        var removed = 0
        val it = state.chunks.iterator()
        while (it.hasNext()) {
            if (it.next().x < keepFromX) {
                it.remove()
                removed++
            }
        }
        return removed
    }

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

        /**
         * 上游 `MEM.planmtx`（计数器数组）—— **占位声明，实际不使用**。
         *
         * ⚠️ 真正的 planmtx 是**全局**的（见 [ShanShuiPlanner.planmtx]）：上游 `MEM.planmtx`
         *   挂在全局 `MEM` 上 ⇒ `mountplanner` 跨调用累积。
         *   本字段按方案 §八 保留声明（`BooleanArray` 也无法承载计数器 `+= 1`）。
         */
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
