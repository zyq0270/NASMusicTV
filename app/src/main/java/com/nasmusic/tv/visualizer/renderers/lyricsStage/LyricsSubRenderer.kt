package com.nasmusic.tv.visualizer.renderers.lyricsStage

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext

/**
 * 歌词舞台的子渲染器接口。
 *
 * 每个子模式是一个独立的效果实现；主渲染器 `LyricsStageRenderer` 负责
 * 子模式的创建 / 销毁 / 切换过渡，并把歌词数据以 `LyricsStagePayload` 传进来。
 *
 * 零分配约束：Paint / Path / Color 等一律提到成员变量，不要在 draw() 里新建。
 */
interface LyricsSubRenderer {

    /**
     * 进入子模式：预分配缓冲、预计算时序。
     *
     * ⚠️ **可以（也常常需要）被再次调用** —— 见 [onLineChanged] 的默认实现。
     * 实现时应当**幂等**：重复进入时释放旧资源再重建，不要泄漏 Bitmap。
     *
     * ⚠️ `RendererSwapper.sync()` 在主题切换时会硬切（重建渲染器），
     * 因此子渲染器的所有状态都必须在 onEnter 里重新推导。
     */
    fun onEnter(ctx: RenderContext, lyrics: LyricsStagePayload)

    /**
     * **当前歌词行变化**时回调（默认实现 = 重新走一遍 [onEnter]）。
     *
     * 绝大多数子模式的布局是「按当前行」的（逐字行光的词布局、倾斜律动的分段、
     * 分栏引导的 chunk），所以默认重新进入即可。
     * ⛔ 若本子模式的布局是**整曲级**的（如全屏纵览的整版版面），
     *    应覆写本方法、只更新与当前行有关的部分 —— 否则每换一行就重建整曲版面。
     */
    fun onLineChanged(ctx: RenderContext, lyrics: LyricsStagePayload) {
        onEnter(ctx, lyrics)
    }

    /** 每帧绘制。 */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext, lyrics: LyricsStagePayload)

    /** 退出子模式：释放资源（Bitmap 回收等）。 */
    fun onExit()
}
