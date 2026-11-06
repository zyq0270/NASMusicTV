package com.nasmusic.tv.visualizer.renderers.lyricsStage

/**
 * 移植自 Folia（AGPL-3.0）:
 *  - src/components/visualizer/glowWordTiming.ts
 *  - src/components/visualizer/GlowWord.tsx（词状态机）
 * Folia 版本: 0.5.27-a16525c
 *
 * classic / partita 共用的逐词时序。
 */
enum class GlowWordStatus { WAITING, ACTIVE, PASSED }

/** 移植 glowWordTiming.ts:8-14 `GlowWordRenderProfile` */
data class GlowWordProfile(
    val renderHints: LineRenderHints?,
    val lineRenderEndTime: Double,
    /** "normal" | "fast" | "none" */
    val lineTransitionMode: String,
    /** "normal" | "fast" | "instant" */
    val wordRevealMode: String,
    val wordLookahead: Double,
)

/** 移植 glowWordTiming.ts:58-91 `getGlowWordLineContainerMotion` 的三段状态 */
data class GlowWordLineContainerMotion(
    val initialOpacity: Float,
    val initialScale: Float,
    val initialBlurPx: Float,
    val exitOpacity: Float,
    val exitScale: Float,
    val exitBlurPx: Float,
    val exitDurationSec: Double,
    /** 进入动画时长（秒） */
    val enterDurationSec: Double,
)

object GlowWordCore {

    /** 移植 glowWordTiming.ts:16-33 `resolveGlowWordRenderProfile` */
    fun resolveProfile(line: LyricLine?): GlowWordProfile? {
        if (line == null) return null

        val renderHints = LyricRenderHints.getLineRenderHints(line)
        val wordRevealMode = renderHints?.wordRevealMode ?: "normal"

        return GlowWordProfile(
            renderHints = renderHints,
            lineRenderEndTime = LyricRenderHints.getLineRenderEndTime(line),
            lineTransitionMode = renderHints?.lineTransitionMode ?: "normal",
            wordRevealMode = wordRevealMode,
            wordLookahead = when (wordRevealMode) {
                "instant" -> 0.03
                "fast" -> 0.08
                else -> 0.15
            },
        )
    }

    /** 移植 glowWordTiming.ts:35-45 `getGlowWordActiveEndTime` */
    fun activeEndTime(word: LyricWord, profile: GlowWordProfile): Double {
        if (profile.wordRevealMode == "instant") {
            return profile.lineRenderEndTime
        }
        if (profile.wordRevealMode == "fast") {
            return Math.min(
                profile.lineRenderEndTime,
                Math.max(word.endTime, word.startTime + 0.12),
            )
        }
        return word.endTime
    }

    /** 移植 glowWordTiming.ts:47-56 `getGlowWordDisplayDuration` */
    fun displayDuration(word: LyricWord, profile: GlowWordProfile): Double {
        val activeEnd = activeEndTime(word, profile)
        val minDuration = when (profile.wordRevealMode) {
            "instant" -> 0.08
            "fast" -> 0.12
            else -> 0.1
        }
        return Math.max(activeEnd - word.startTime, minDuration)
    }

    /** 移植 GlowWord.tsx:53-61 — 词状态判定 */
    fun statusOf(word: LyricWord, time: Double, profile: GlowWordProfile): GlowWordStatus {
        val activeEnd = activeEndTime(word, profile)
        return when {
            time >= word.startTime - profile.wordLookahead && time <= activeEnd -> GlowWordStatus.ACTIVE
            time > activeEnd -> GlowWordStatus.PASSED
            else -> GlowWordStatus.WAITING
        }
    }

    /** 移植 glowWordTiming.ts:58-91 `getGlowWordLineContainerMotion` */
    fun lineContainerMotion(profile: GlowWordProfile?): GlowWordLineContainerMotion {
        if (profile?.lineTransitionMode == "none") {
            return GlowWordLineContainerMotion(
                initialOpacity = 1f,
                initialScale = 1f,
                initialBlurPx = 0f,
                exitOpacity = 0f,
                exitScale = 1.02f,
                exitBlurPx = 6f,
                exitDurationSec = 0.12,
                enterDurationSec = 0.0,
            )
        }

        if (profile?.lineTransitionMode == "fast") {
            return GlowWordLineContainerMotion(
                initialOpacity = 0.35f,
                initialScale = 0.96f,
                initialBlurPx = 4f,
                exitOpacity = 0f,
                exitScale = 1.04f,
                exitBlurPx = 10f,
                exitDurationSec = 0.16,
                enterDurationSec = 0.16,
            )
        }

        return GlowWordLineContainerMotion(
            initialOpacity = 0f,
            initialScale = 0.9f,
            initialBlurPx = 10f,
            exitOpacity = 0f,
            exitScale = 1.1f,
            exitBlurPx = 20f,
            exitDurationSec = 0.3,
            enterDurationSec = 0.0,
        )
    }
}
