package com.nasmusic.tv.visualizer.arcade.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import com.nasmusic.tv.visualizer.arcade.ArcadeInputQueue
import com.nasmusic.tv.visualizer.arcade.ContraArcadeGame
import com.nasmusic.tv.visualizer.arcade.ContraSnapshot

/**
 * 街机合集的 GL 载体（与 E52 `HighwayGLSurfaceView` 同一模式，方案 C1/C2）。
 *
 * ## 焦点口径（⛔ 与 E52 一致，方案 §4.8 路径 A）
 * `isFocusable = false` —— **方向键必须留给舞台的 Compose 焦点系统**
 * （舞台用 `onPreviewKeyEvent` 消费 ←/→ 切主题、↑/↓ 切子模式）。
 *
 * 代价（**如实记录为验收缺口**）：本 View 不持焦 ⇒ 遥控器按键不会派发到这里，
 * [dispatchKeyEvent] 里的确认键钩子**只在该 View 意外获得焦点时才生效**。
 * 本次交付因此**不承诺**确认键可打断 demo；demo 自己会在 3840 帧（≈64 s）后回标题。
 * 变更记录见 `docs/technical-overview.md` §10 的 E55 条目。
 *
 * ## 上下文版本
 * 着色器按 **GLES 2.0** 写（attribute / varying / texture2D）⇒ 请求 2.0 上下文即可，
 * 老机（创维 G6110 / API 22）无需回退逻辑。
 */
@SuppressLint("ViewConstructor")
class ArcadeGLSurfaceView(
    context: Context,
    game: ContraArcadeGame,
    snapshot: ContraSnapshot,
    queue: ArcadeInputQueue,
) : GLSurfaceView(context) {

    val scene: ArcadeGlScene = ArcadeGlScene(context.applicationContext, game, snapshot, queue)

    private val inputQueue: ArcadeInputQueue = queue

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        setRenderer(scene)
        renderMode = RENDERMODE_CONTINUOUSLY
        // ⛔ 让出焦点：方向键必须留给 Compose 的焦点系统（同 E52 / WorldGlobe）
        isFocusable = false
        isFocusableInTouchMode = false
        preserveEGLContextOnPause = true
    }

    /**
     * 确认键钩子：只认 DPAD_CENTER / ENTER，其余键原样交出（⛔ 不吞方向键与返回键）。
     * 语义 = 「打断 demo 回标题」（对齐上游 `INPUT_START`）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    inputQueue.offer(ArcadeInputQueue.EVENT_START)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun currentFps(): Float = scene.currentFps()

    /** 外部（舞台/调试）打断 demo：等价于按一次确认键 */
    fun requestInterrupt() {
        inputQueue.offer(ArcadeInputQueue.EVENT_START)
    }
}
