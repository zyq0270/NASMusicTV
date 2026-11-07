package com.nasmusic.tv.visualizer

import android.content.Context
import android.view.View
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme

/**
 * 可视化效果渲染器（可插拔）。
 *
 * 新增一套效果 = 新增一个实现类 + 一个枚举值，音频分析层零改动。
 *
 * **性能红线**：[draw] 内禁止任何对象分配（Paint / Path / Color 提到成员变量）。
 *
 * **View 型渲染器旁路**：渲染器可以不走 [draw]（DrawScope，主线程每帧重绘），
 * 而是返回一个 [View]（如 three-globe 的 WebView）由舞台用 `AndroidView` 承载。
 * 旁路开启后 [draw] **不再被调用**，舞台改为托管 [createView] 的返回值，
 * 并用 [onViewAttached] / [onViewDetached] 驱动 View 的启动 / 停止生命周期。
 */
interface VisualizerRenderer {

    val theme: VisualizerTheme

    /** 进入效果：分配缓冲、重置状态 */
    fun onEnter(ctx: RenderContext) {}

    /** 每帧绘制。[frame] 为复用单例，不得跨帧持有 */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext)

    /** 退出：释放 ImageBitmap 等重资源 */
    fun onExit() {}

    /** View 型渲染器旁路：true 时舞台用 AndroidView 承载，draw 不再被调用 */
    val isViewBased: Boolean get() = false

    /** isViewBased=true 时返回要嵌入的 View；Compose 首次组合时在主线程调用一次 */
    fun createView(context: Context): View? = null

    /** View 已 attach 到舞台（可启动定时器/加载数据） */
    fun onViewAttached() {}

    /** View 即将 detach（停止定时器/释放） */
    fun onViewDetached() {}

    /**
     * 渲染器自己上报的**实际**帧率（≤0 = 未知，舞台退回 Choreographer 口径）。
     *
     * ⛔ **View 型渲染器必须覆盖它**：舞台的 `FpsBadge` 是在 `withFrameNanos` 里
     * 累计的，量的是 **Compose 的 Choreographer 回调率**；而 View 型渲染器
     * （`GLSurfaceView` / `RENDERMODE_CONTINUOUSLY` 等）有**自己独立的渲染循环**，
     * 两者完全解耦。主线程越闲，徽章就越接近面板刷新率 ——
     * 于是出现「显示 59 fps 但画面很卡」的假象（真机问题，E52 实测徽章 52~59
     * 而 `frame stats avg=154 ms` ⇒ 真实 6.5 fps）。
     */
    fun renderFps(): Float = 0f

    /**
     * 当前**景色组合标签**（如 `丘陵 · 夏季 · 晴朗 · 普通`；空串 = 本效果没有这个概念）。
     *
     * ⛔ 为什么要它：E52 的换歌过场会**换掉整套环境**（场景 / skin / 天气 / 地形档），
     *    但画面上只有**颜色**的变化 ⇒ 用户与验收者看不出「到底换没换」。
     *    ⇒ 舞台把这一行显示在**右下角**（仅 E52 用）。
     *
     * ⚠️ 默认空实现 ⇒ Canvas 路径的既有 27 套渲染器**一行都不用改**。
     */
    fun currentEnvLabel(): String = ""

    /**
     * 请求**手动**切换景色（E52 用；默认空实现）。
     *
     * ⛔ 为什么要它：E52 的换歌过场是**自动**的（游标顺序推进），
     *    但用户/验收者常常想**当场**看看别的景色 ⇒ 上下滑 / 上下键手动切一个**随机**组合。
     *    ⛔ 手动切**仍然走三阶段雾白过场**（不会硬切）。
     *
     * ⚠️ 默认空实现 ⇒ Canvas 路径的既有 27 套渲染器**一行都不用改**。
     */
    fun requestEnvSwitch() {}

    /**
     * 每帧数据推送（**View 型渲染器专用**）。
     *
     * ⚠️ **为什么需要它**：[RenderContext.update(...)] 只在 Canvas 的 draw 块里被调用
     * （`VisualizerStage` 的帧循环），⇒ **View 旁路下 [RenderContext.songId] 永远是旧值**
     * ⇒ E52「每帧比 songId ⇒ 换歌即换环境」在 View 路径下会**直接失效**。
     *
     * ⇒ 舞台在**同一帧**里把 [frame] 与 [ctx] 推给 View 型渲染器；
     * Canvas 路径的既有 27 套渲染器**一行都不用改**（默认空实现）。
     */
    fun onFrame(frame: AudioFrame, ctx: RenderContext) {}

    /**
     * 本效果对**舞台叠加层**的诉求（默认「全显示」）。
     *
     * ⚠️ 类型是 [State]：效果可以在**运行期**改它（子模式切换等），舞台读了 `value`
     *    ⇒ 自动重组。实现类要改就自己持一份 `mutableStateOf` 并覆写本属性；
     *    ⛔ 不要写 [EffectFurniture.DEFAULT_STATE]（全局只读）。
     *
     * ⚠️ 默认实现 ⇒ 既有渲染器**一行都不用改**；新增效果也**不需要改舞台**
     *    （舞台按本声明决定要不要画顶部歌词条 / 左下歌曲信息、内容区比例与墨色）。
     */
    val furniture: State<EffectFurniture> get() = EffectFurniture.DEFAULT_STATE
}
