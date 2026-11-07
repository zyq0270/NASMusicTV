package com.nasmusic.tv.visualizer

import android.content.Context
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.renderers.LyricsStageRenderer
import com.nasmusic.tv.visualizer.renderers.VintageTvRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * 「效果自己声明舞台叠加层家具」的门禁（[EffectFurniture]）。
 *
 * ## 背景
 *
 * 舞台（`ui/components/VisualizerStage.kt`）本来**硬编码**了逐主题的特例
 * （「怀旧」要 4:3 黑边 + 反色文字、「歌词舞台」不要顶部歌词条）。
 * ⇒ **新增一套效果就得改舞台** —— 而舞台文件被本机私有分支的覆盖层遮蔽
 * （`app/src/e51-overlay/…`）⇒ 改它会让本地构建与 CI 行为分叉、还要重新同步覆盖层。
 *
 * ⇒ 改成效果自己声明（[VisualizerRenderer.furniture]），默认「全显示」。
 *
 * ## 本门禁钉住什么
 *
 * 1. 「怀旧」声明 4:3 内容区 + 浅底 + 不显示顶部歌词条；
 * 2. 「歌词舞台」不显示顶部歌词条；
 * 3. ⭐ **其它效果一律是 [EffectFurniture.DEFAULT]** ⇒
 *    新增效果**不能悄悄 opt-out**（想改就显式声明 ⇒ 门禁会红）。
 *
 * ## 负向自证
 *
 * ③ 的判据必须真的能区分「默认」与「自定义」⇒ 见「负向」用例。
 */
class EffectFurnitureGateTest {

    /**
     * 工厂里有两个主题要 [Context]（WORLD 的 WebView / MOONLIT 的资产）。
     * 这里给一个**最小假 Context**：只桩掉 `applicationContext`（工厂会调它）。
     */
    private fun fakeContext(): Context {
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        return ctx
    }

    // ── ① 怀旧 ──────────────────────────────────────────────────

    @Test
    fun `① 怀旧声明 4x3 内容区 浅底 不显示顶部歌词条`() {
        val f = VintageTvRenderer().furniture.value
        assertFalse("怀旧自己画歌词 ⇒ 顶部歌词提示条必须关掉", f.lyricTopBar)
        assertTrue("怀旧要显示左下歌曲信息", f.songInfo)
        val aspect = f.contentAspect
        assertTrue("怀旧必须有内容区比例（4:3）", aspect != null)
        assertEquals("怀旧的内容区比例必须是 4:3", 4f / 3f, aspect!!, 1e-6f)
        assertTrue("怀旧是牛皮纸浅底 ⇒ 内容区要用深色墨", f.contentIsLight)
    }

    // ── ② 歌词舞台 ──────────────────────────────────────────────

    @Test
    fun `② 歌词舞台不显示顶部歌词条`() {
        val f = LyricsStageRenderer().furniture.value
        assertFalse("歌词舞台的 5 个子模式都自己画歌词 ⇒ 顶部歌词条必须关掉", f.lyricTopBar)
        assertTrue("歌词舞台要显示左下歌曲信息", f.songInfo)
        assertNull("歌词舞台没有内容区比例（满屏）", f.contentAspect)
        assertFalse("歌词舞台不是浅底", f.contentIsLight)
    }

    // ── ③ 其它效果一律默认 ──────────────────────────────────────

    @Test
    fun `③ 其它效果一律用默认家具 全显示`() {
        val ctx = fakeContext()
        var checked = 0
        for (theme in VisualizerTheme.entries) {
            if (theme == VisualizerTheme.VINTAGE_TV || theme == VisualizerTheme.LYRICS_STAGE) continue
            val renderer = VisualizerRendererFactory.create(theme, ctx)
            assertEquals(
                "$theme 必须用默认家具（想改就显式声明 ⇒ 这条会红）",
                EffectFurniture.DEFAULT,
                renderer.furniture.value,
            )
            checked++
        }
        // 空转自证：循环体至少跑过一次（否则上面的断言恒真）
        assertTrue("应至少检查 20 个效果（空转自证），实测 $checked", checked >= 20)
    }

    // ── ④ 默认 State 必须真的是「只读且共享」 ────────────────────

    @Test
    fun `④ 默认 State 是全局只读的那一份`() {
        val defaultRenderer = object : VisualizerRenderer {
            override val theme = VisualizerTheme.CIRCULAR_RING
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(
                frame: AudioFrame,
                ctx: RenderContext,
            ) {
            }
        }
        // 默认实现必须**就是**那一份全局只读 State（不是每个实现类各造一份）
        assertSame(
            "默认实现必须复用 EffectFurniture.DEFAULT_STATE（全局只读）",
            EffectFurniture.DEFAULT_STATE,
            defaultRenderer.furniture,
        )
        assertSame(
            "DEFAULT_STATE 的值必须是那一个 DEFAULT 实例",
            EffectFurniture.DEFAULT,
            EffectFurniture.DEFAULT_STATE.value,
        )
        // 只读 ⇒ 反复读不会漂移，也不会被某个实现类改坏
        repeat(3) {
            assertSame(EffectFurniture.DEFAULT, EffectFurniture.DEFAULT_STATE.value)
        }
    }

    // ── 负向自证 ────────────────────────────────────────────────

    @Test
    fun `负向 默认家具与自定义家具必须可区分 证明③的断言会咬住`() {
        val custom = EffectFurniture(lyricTopBar = false)
        assertNotEquals("自定义家具必须与默认家具不相等", EffectFurniture.DEFAULT, custom)
        assertFalse("③ 的判据（相等）必须能区分自定义家具", custom == EffectFurniture.DEFAULT)

        // 一个**显式声明**了家具的渲染器 ⇒ ③ 的断言对它必须挂
        val customRenderer = object : VisualizerRenderer {
            override val theme = VisualizerTheme.SEASIDE
            override val furniture: androidx.compose.runtime.State<EffectFurniture> =
                androidx.compose.runtime.mutableStateOf(EffectFurniture(lyricTopBar = false))
            override fun androidx.compose.ui.graphics.drawscope.DrawScope.draw(
                frame: AudioFrame,
                ctx: RenderContext,
            ) {
            }
        }
        assertFalse(
            "自定义了家具的渲染器必须被 ③ 判为「不是默认」",
            customRenderer.furniture.value == EffectFurniture.DEFAULT,
        )
    }

    @Test
    fun `负向 只改 contentAspect 也必须与默认家具可区分`() {
        val onlyAspect = EffectFurniture(contentAspect = 4f / 3f)
        assertNotEquals(EffectFurniture.DEFAULT, onlyAspect)
        val onlyLight = EffectFurniture(contentIsLight = true)
        assertNotEquals(EffectFurniture.DEFAULT, onlyLight)
    }
}
