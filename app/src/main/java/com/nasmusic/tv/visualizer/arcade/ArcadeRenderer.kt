package com.nasmusic.tv.visualizer.arcade

import android.content.Context
import android.view.View
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.arcade.render.ArcadeGLSurfaceView

/**
 * 街机合集渲染器（`VisualizerTheme.ARCADE_GAMES`，占位编号 `"55"`）。
 *
 * ## View 型旁路
 * 与 E52「旷野公路」同模式：本渲染器**不走 `DrawScope`**，而是返回一台
 * [ArcadeGLSurfaceView] 交给舞台用 `AndroidView` 承载 ⇒ [draw] 永不被调用。
 *
 * ## 为什么必须覆盖 [renderFps]
 * 舞台的 `FpsBadge` 量的是 **Compose 的 Choreographer 回调率**，而 GLSurfaceView 有
 * **自己独立的渲染循环**，两者完全解耦 ⇒ 不覆盖会出现「徽章 59 fps、实际 6.5 fps」
 * 的假象（`VisualizerRenderer.kt:45-58`，E52 已实测）。此处返回 GL 线程自测的帧率。
 *
 * ## 切走 / 切回
 * `onViewDetached` ⇒ `onPause()`（**暂停 + 保留**，方案 §4.9）；
 * 再 attach 从暂停处继续 —— ⛔ 不重置，否则 3840 帧的 demo 永远跑不完、看起来像卡死。
 */
class ArcadeRenderer(context: Context) : VisualizerRenderer {

    override val theme: VisualizerTheme = VisualizerTheme.ARCADE_GAMES

    // ⛔ 只持有 applicationContext（避免 Activity 泄漏，同 MOONLIT 的规矩）
    private val appContext: Context = context.applicationContext

    private val game: ContraArcadeGame = ContraArcadeGame()
    private val snapshot: ContraSnapshot = ContraSnapshot()
    private val queue: ArcadeInputQueue = ArcadeInputQueue(32)

    private var view: ArcadeGLSurfaceView? = null

    override val isViewBased: Boolean get() = true

    /** 家具沿用默认（方案 §4.6 / 门禁 G8：⛔ 不声明家具） */
    override fun onEnter(ctx: RenderContext) {
        // 每次进入换一个种子：同一次运行内可复现，跨次进入有变化（与 VisualizerRandom 的策略一致）
        game.reset(VisualizerRandom.defaultSeed().toLong())
        queue.clear()
    }

    override fun createView(context: Context): View {
        val v = ArcadeGLSurfaceView(context, game, snapshot, queue)
        view = v
        return v
    }

    override fun onViewAttached() {
        view?.onResume()
    }

    override fun onViewDetached() {
        // 暂停 + 保留游戏状态（⛔ 不 reset）
        view?.onPause()
    }

    override fun renderFps(): Float = view?.currentFps() ?: 0f

    /** View 型旁路：舞台不会调到这里（保留空实现以满足接口） */
    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) = Unit

    /** 确认键打断（由舞台或调试入口调用；本次交付未接线，见类注释与 §10 变更记录） */
    fun requestInterrupt() {
        queue.offer(ArcadeInputQueue.EVENT_START)
    }

    /** 只读：当前 demo 状态（0=TITLE / 1=DEMO / 2=CLEAR），供验收仪表读数 */
    fun currentMode(): Int = game.currentMode

    /** 只读：当前关卡号（1~8） */
    fun currentLevel(): Int = game.simulation.currentLevelNumber

    /** 只读：applicationContext（单测断言不持有 Activity 用） */
    internal fun heldContext(): Context = appContext
}
