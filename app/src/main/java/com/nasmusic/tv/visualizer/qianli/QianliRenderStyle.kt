package com.nasmusic.tv.visualizer.qianli

/**
 * E50「千里江山」的**呈现路径**开关 —— **单一真源**。
 *
 * ## 为什么需要它
 *
 * E50 有两条**互斥**的呈现路径：
 *
 * | 路径 | 渲染器 | 画面 | 状态 |
 * |---|---|---|---|
 * | **忠于上游** | `renderers.QianliUpstreamRenderer` | 上游 `shan-shui-inf` 的**线描 + 绢白纸底**，单一世界空间、均匀缩放、恒速、无自创装饰 | ⭐ **默认**（所有者裁定 2026-11-06） |
 * | **设色（INK）** | `renderers.QianliScrollRenderer` | 四条层带 + 三版设色色表（V-A/V-B/V-C）+ 自创装饰（印章/光尘/做旧/近景雾/轮廓光/水高光/呼吸缩放） | 保留，等效果定案后决定去留 |
 *
 * 两条路径共用**同一套移植层**（`qianli/port/`，忠于上游），**分叉只在渲染器**。
 *
 * ## 为什么要「单一真源」
 *
 * 三处都要知道「当前走哪条路」：
 *
 * ① `VisualizerRendererFactory` —— 决定实例化哪个渲染器；
 * ② `VisualizerStage` —— 「忠于上游」路径**没有设色版**（上游只有灰阶）
 *    ⇒ 必须**关闭** V-A/V-B/V-C 的切换，否则按上下键会弹 Toast 却没有任何画面变化；
 * ③ 文档 / 门禁 —— 断言「默认走哪条」。
 *
 * ⇒ 三处都读**这一个** [UPSTREAM_DEFAULT]，不许各自硬编码。
 *
 * ## ⛔ 改这里之前先看
 *
 * - 「忠于上游」路径的**取舍**与**量化基线**：`docs/technical-overview.md` §10.281 的 §7.11–§7.14。
 * - 上游**没有**的东西（飞鸟/日月/倒影/雾/云/题跋）**不要**往上游路径里加
 *   ⇒ 那是「新增」而不是「补移植」。
 */
object QianliRenderStyle {

    /**
     * 是否默认走「忠于上游」路径。
     *
     * - `true`  ⇒ `QianliUpstreamRenderer`（绢白纸底 + 线描 + 无染色 + 无自创装饰）
     * - `false` ⇒ `QianliScrollRenderer`（分层带 + 三版设色 + 自创装饰）
     *
     * ⚠️ **刻意不是 `const val`** —— 用了 `const` 的话，编译器会把下面的分支当常量折叠，
     * 另一条路径的代码会被判成「不可达」并刷一堆警告。
     *
     * @see docs/technical-overview.md §10.281 §7.12（所有者 2026-11-06 的两条裁定）
     */
    val UPSTREAM_DEFAULT: Boolean = true
}
