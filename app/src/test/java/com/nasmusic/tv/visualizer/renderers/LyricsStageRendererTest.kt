package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Size
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricLine
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricWord
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStageMode
import com.nasmusic.tv.visualizer.renderers.lyricsStage.LyricsStagePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E53「歌词舞台」—— 主渲染器的子模式管理门禁测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsStageRendererTest {

    private fun ctx(quality: VisualQuality): RenderContext {
        val c = RenderContext()
        c.quality = quality
        c.canvasSize = Size(1920f, 1080f)
        c.nowMs = 0L
        return c
    }

    private fun payload(): LyricsStagePayload = LyricsStagePayload(
        lines = listOf(
            LyricLine(
                fullText = "你好",
                startTime = 0.0,
                endTime = 3.0,
                words = listOf(
                    LyricWord("你", 0.0, 1.5),
                    LyricWord("好", 1.5, 3.0),
                ),
            )
        ),
        currentIndex = 0,
        timeSec = 0.5,
    )

    @Test
    fun `the renderer belongs to the lyrics stage theme`() {
        assertEquals(VisualizerTheme.LYRICS_STAGE, LyricsStageRenderer().theme)
    }

    @Test
    fun `entering the stage defaults to the dot matrix sub mode`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        try {
            assertEquals(LyricsStageMode.DOT_MATRIX, renderer.currentMode())
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `the mode can be set explicitly and survives a read back`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        try {
            for (mode in LyricsStageMode.entries) {
                renderer.setMode(mode)
                assertEquals(mode, renderer.currentMode())
            }
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `next and prev walk the sub mode cycle`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        try {
            renderer.setMode(LyricsStageMode.DOT_MATRIX)
            renderer.nextMode()
            assertEquals(LyricsStageMode.GLOW_LINE, renderer.currentMode())
            renderer.nextMode()
            assertEquals(LyricsStageMode.FULL_VIEW, renderer.currentMode())
            renderer.nextMode()
            assertEquals(LyricsStageMode.TILT_PULSE, renderer.currentMode())
            renderer.nextMode()
            assertEquals(LyricsStageMode.PARTITA, renderer.currentMode())
            renderer.nextMode()
            assertEquals(LyricsStageMode.DOT_MATRIX, renderer.currentMode())

            renderer.prevMode()
            assertEquals(LyricsStageMode.PARTITA, renderer.currentMode())
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `the full view sub mode degrades to the glow line one below the high quality`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.MEDIUM))
        try {
            renderer.setMode(LyricsStageMode.FULL_VIEW)
            assertEquals(LyricsStageMode.FULL_VIEW, renderer.currentMode())
            assertEquals(LyricsStageMode.GLOW_LINE, renderer.effectiveMode())
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `the full view sub mode is kept at the high quality`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        try {
            renderer.setMode(LyricsStageMode.FULL_VIEW)
            assertEquals(LyricsStageMode.FULL_VIEW, renderer.effectiveMode())
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `the lyrics payload is accepted`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        try {
            renderer.applyLyrics(payload())
            assertNotNull(renderer.currentMode())
        } finally {
            renderer.onExit()
        }
    }

    @Test
    fun `exiting the stage releases the sub renderer`() {
        val renderer = LyricsStageRenderer()
        renderer.onEnter(ctx(VisualQuality.HIGH))
        renderer.setMode(LyricsStageMode.GLOW_LINE)
        renderer.onExit()
        // 退出后再切模式不得抛异常（子渲染器已释放）
        renderer.setMode(LyricsStageMode.PARTITA)
        assertEquals(LyricsStageMode.PARTITA, renderer.currentMode())
    }

    @Test
    fun `the sub mode transition window is 300 milliseconds`() {
        assertEquals(300L, LyricsStageRenderer.TRANSITION_MS)
        assertTrue(LyricsStageRenderer.TRANSITION_MS > 0L)
    }
}
