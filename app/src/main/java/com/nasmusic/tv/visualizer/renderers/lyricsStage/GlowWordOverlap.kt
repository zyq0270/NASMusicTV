package com.nasmusic.tv.visualizer.renderers.lyricsStage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 移植自 Folia（AGPL-3.0）: src/components/visualizer/glowWordOverlap.ts
 * Folia 版本: 0.5.27-a16525c
 *
 * 「防止文字重叠」：GlowWord 的放大、旋转、偏移都走 transform，不占排版位置，
 * 所以放大的词会压到旁边。这里算一个词画出来时伸出排版框多少，各模式把它补成 margin。
 */

/** 移植 glowWordOverlap.ts:8 `GLOW_WORD_LINE_HEIGHT` */
const val GLOW_WORD_LINE_HEIGHT = 1.22f

/** 移植 glowWordOverlap.ts:11 `GLYPH_HEIGHT_EM` */
private const val GLYPH_HEIGHT_EM = 1f

/** 移植 glowWordOverlap.ts:14 `PASSED_TURN_SAMPLES` */
private const val PASSED_TURN_SAMPLES = 8

/** 移植 glowWordVariants.ts:8 `GLOW_WORD_ACTIVE_SCALE` */
const val GLOW_WORD_ACTIVE_SCALE = 1.4f

/** 移植 glowWordOverlap.ts:36-41 `GlowWordPose` */
data class GlowWordPose(
    val scale: Float,
    val rotate: Float,
    val passedRotate: Float,
)

/** 移植 glowWordOverlap.ts:43-48 `GlowWordOverflow` */
data class GlowWordOverflow(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

/** 移植 glowWordOverlap.ts:80-84 `GlowWordPlacement` */
data class GlowWordPlacement(
    val width: Float,
    val pose: GlowWordPose,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

object GlowWordOverlap {

    /** 移植 glowWordOverlap.ts:51-59 `reachAt` */
    private fun reachAt(
        width: Float,
        glyphHeight: Float,
        scale: Float,
        rotateDeg: Float,
    ): Pair<Float, Float> {
        val radians = (rotateDeg * Math.PI / 180.0)
        val cosV = abs(cos(radians)).toFloat()
        val sinV = abs(sin(radians)).toFloat()
        return (scale * (width * cosV + glyphHeight * sinV) / 2f) to
            (scale * (glyphHeight * cosV + width * sinV) / 2f)
    }

    /** 移植 glowWordOverlap.ts:66-77 `getGlowWordReach`
     *
     *  词在「可见期间」画得最宽的时刻：active（config.scale × active scale，按其 rotate）
     *  与 passed（config.scale，从 rotate 转到 rotate + passedRotate）。
     */
    fun getReach(width: Float, fontSize: Float, pose: GlowWordPose): Pair<Float, Float> {
        val glyphHeight = fontSize * GLYPH_HEIGHT_EM
        val active = reachAt(width, glyphHeight, pose.scale * GLOW_WORD_ACTIVE_SCALE, pose.rotate)
        var halfWidth = active.first
        var halfHeight = active.second
        for (step in 0..PASSED_TURN_SAMPLES) {
            val passed = reachAt(
                width, glyphHeight, pose.scale,
                pose.rotate + (pose.passedRotate * step) / PASSED_TURN_SAMPLES,
            )
            halfWidth = max(halfWidth, passed.first)
            halfHeight = max(halfHeight, passed.second)
        }
        return halfWidth to halfHeight
    }

    /** 移植 glowWordOverlap.ts:96-109 `getGlowWordPairGap`
     *
     *  两个相邻词之间需要的空间：第一个朝第二个伸出的量 + 第二个朝回伸出的量（带各自实际偏移）。
     */
    fun getPairGap(
        first: GlowWordPlacement,
        second: GlowWordPlacement,
        fontSize: Float,
        direction: String = "ltr",
    ): Float {
        val firstReach = getReach(first.width, fontSize, first.pose)
        val secondReach = getReach(second.width, fontSize, second.pose)
        // 正的 x 把绘制往右移：LTR 时朝下一个词，RTL 时背离它。
        val towardEachOther =
            if (direction == "rtl") second.offsetX - first.offsetX else first.offsetX - second.offsetX
        return (firstReach.first - first.width / 2f) +
            (secondReach.first - second.width / 2f) +
            towardEachOther
    }

    /** 移植 glowWordOverlap.ts:115-129 `getGlowWordOverflow` */
    fun getOverflow(
        width: Float,
        fontSize: Float,
        pose: GlowWordPose,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): GlowWordOverflow {
        val (halfWidth, halfHeight) = getReach(width, fontSize, pose)
        val overWidth = halfWidth - width / 2f
        val overHeight = halfHeight - (fontSize * GLOW_WORD_LINE_HEIGHT) / 2f
        return GlowWordOverflow(
            left = max(0f, overWidth - offsetX),
            right = max(0f, overWidth + offsetX),
            top = max(0f, overHeight - offsetY),
            bottom = max(0f, overHeight + offsetY),
        )
    }
}
