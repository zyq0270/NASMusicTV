// 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCameraStep.ts — Project Folia version 0.5.27-a16525c
package com.nasmusic.tv.visualizer.renderers.lyricsStage.fume

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * fume 相机的逐帧运动：选目标、加空闲浮动、飞桥或弹簧追过去。
 *
 * 移植自 Folia (AGPL-3.0): src/components/visualizer/fume/fumeCameraStep.ts — Project Folia version 0.5.27-a16525c
 *
 * ## 浏览器 API → Android 等价物
 *  - Folia 的 `stepFumeCamera` 每次调用都新建一个 `FumeCameraStepInput` 对象和一个返回对象；
 *    Android 改成**调用方持有的可变输入/输出对象**（逐帧复用 ⇒ 零分配）。
 *  - Folia 的 `state.retarget = {...}` / `state.camera = {...}` 整体替换 ⇒
 *    Android 改成**就地改字段**（`CameraRetargetState` / `CameraTarget` 是可变类）。
 *  - Folia 的 `Math.exp` / `Math.hypot` / `Math.sqrt` / `Math.pow` ⇒ `kotlin.math` 同名函数。
 */
object FumeCameraStep {

    /**
     * 移植 fumeCameraStep.ts:13-17 `FumeCameraState`
     *
     * Android：相机与重定向状态都是可变类（逐帧就地改，不整体替换）。
     * 逐帧的临时对象（焦点点、飞行桥）也放在这里 ⇒ 每个相机状态一份，不共享。
     */
    class FumeCameraState {
        val camera = FumeTypes.CameraTarget()
        val retarget = FumeTypes.CameraRetargetState()

        /** 复用对象：当前焦点点 */
        val focusPoint = FumeTypes.FumePoint()

        /** 复用对象：块的入场焦点点 */
        val entryPoint = FumeTypes.FumePoint()

        /** 复用对象：全景飞行桥 */
        val flightBridge = FumeTypes.FumeFlightBridge()

        var initialized: Boolean = false
    }

    /** 移植 fumeCameraStep.ts:19-45 `createFumeCameraState` */
    fun createFumeCameraState(): FumeCameraState = FumeCameraState()

    /**
     * 移植 fumeCameraStep.ts:48-72 `syncFumeCameraToArticle`
     *
     * 新的版面 ⇒ 相机放到版面中心；已有的相机 ⇒ 夹在版面范围内。
     */
    fun syncFumeCameraToArticle(state: FumeCameraState, article: FumeTypes.FumeArticleLayout?) {
        if (article != null && !state.initialized) {
            state.camera.x = article.width * 0.5f
            state.camera.y = article.height * 0.5f
            state.camera.velocityX = 0f
            state.camera.velocityY = 0f
            state.camera.focusX = article.width * 0.5f
            state.camera.focusY = article.height * 0.5f
            state.camera.scale = 1.18f
            state.camera.velocityScale = 0f
            state.camera.focusScale = 1.18f
            state.initialized = true
        } else if (article != null) {
            state.camera.x = FumeMath.clamp(state.camera.x, 0f, article.width)
            state.camera.y = FumeMath.clamp(state.camera.y, 0f, article.height)
            state.camera.focusX = FumeMath.clamp(state.camera.focusX, 0f, article.width)
            state.camera.focusY = FumeMath.clamp(state.camera.focusY, 0f, article.height)
            state.camera.scale = FumeMath.clamp(
                state.camera.scale,
                FumeCamera.CAMERA_SCALE_MIN,
                FumeCamera.CAMERA_SCALE_MAX,
            )
            state.camera.focusScale = FumeMath.clamp(
                state.camera.focusScale,
                FumeCamera.CAMERA_SCALE_MIN,
                FumeCamera.CAMERA_SCALE_MAX,
            )
        } else {
            state.initialized = false
        }
    }

    /**
     * 移植 fumeCameraStep.ts:74-90 `FumeCameraStepInput`
     *
     * Android：可变对象，逐帧复用（避免每帧 new）。
     */
    class FumeCameraStepInput {
        var article: FumeTypes.FumeArticleLayout? = null
        /** 播放时间（秒） */
        var time: Double = 0.0
        /** 墙上时钟（毫秒）—— 驱动空闲浮动 */
        var now: Double = 0.0
        /** 帧时间（秒） */
        var dt: Double = 1.0 / 60.0
        var lineIndex: Int = -1
        var viewport: FumeTypes.ViewportSize = FumeTypes.ViewportSize(0f, 0f)
        var overviewCamera: FumeTypes.CameraViewTarget? = null
        var overviewStartTime: Double = 0.0
        var cameraSpeed: Float = 1f
        /** `stepped` 或 `smooth` */
        var cameraTrackingMode: String = "smooth"
        var staticMode: Boolean = false
        /** `calm` / `normal` / `chaotic` */
        var animationIntensity: String = "normal"
    }

    /**
     * 移植 fumeCameraStep.ts:92-96 `FumeCameraStepResult`
     *
     * Android：可变对象，逐帧复用。
     */
    class FumeCameraStepResult {
        var shouldShowOverview: Boolean = false
        /** 0..1，全景镜头飞入时：唱过的文字恢复标准样式 */
        var overviewTextRestoreProgress: Float = 0f
    }

    /**
     * 移植 fumeCameraStep.ts:98-375 `stepFumeCamera`
     *
     * 逐帧调用一次，就地改 [state]。
     */
    fun stepFumeCamera(
        state: FumeCameraState,
        input: FumeCameraStepInput,
        out: FumeCameraStepResult,
    ): FumeCameraStepResult {
        val article = input.article
        val time = input.time
        val now = input.now
        val dt = input.dt
        val lineIndex = input.lineIndex
        val viewport = input.viewport
        val overviewCamera = input.overviewCamera
        val overviewStartTime = input.overviewStartTime
        val cameraSpeed = input.cameraSpeed
        val cameraTrackingMode = input.cameraTrackingMode
        val staticMode = input.staticMode
        val animationIntensity = input.animationIntensity

        val focusBlock = article?.let { FumeCamera.resolveFocusBlock(it, lineIndex, time) }
        val focusPoint = state.focusPoint
        val entryPoint = state.entryPoint
        val flightBridge = state.flightBridge
        val shouldShowOverview = overviewCamera != null && time >= overviewStartTime
        var targetCameraX = (article?.width ?: 0f) * 0.5f
        var targetCameraY = (article?.height ?: 0f) * 0.5f
        var targetCameraScale = 1.18f
        var hasEntryFocusPoint = false
        var didRetargetThisFrame = false

        // ⚠️ Folia 原文是 `if (shouldShowOverview && overviewCamera)`（fumeCameraStep.ts:111）。
        //    这里把两个**纯判断**的顺序对调：Kotlin 的数据流分析会从 `shouldShowOverview`
        //    推出 `overviewCamera != null` 恒真，再判一次会报「Condition is always 'true'」；
        //    对调后语义完全等价（两个操作数都无副作用），且保住 `overviewCamera` 的智能转换。
        if (overviewCamera != null && shouldShowOverview) {
            targetCameraX = overviewCamera.x
            targetCameraY = overviewCamera.y
            targetCameraScale = overviewCamera.scale

            if (state.retarget.sourceLineIndex != FumeCamera.OVERVIEW_CAMERA_SOURCE) {
                state.retarget.sourceLineIndex = FumeCamera.OVERVIEW_CAMERA_SOURCE
                state.retarget.startedAt = time
                state.retarget.duration = FumeMath.clamp(
                    FumeCamera.resolveOverviewRetargetDuration(viewport) / cameraSpeed,
                    0.12f,
                    1.2f,
                ).toDouble()
                state.retarget.fromX = state.camera.x
                state.retarget.fromY = state.camera.y
                state.retarget.fromScale = state.camera.scale
                state.retarget.bridgeMode = "none"
                state.retarget.bridgeWaypointX = 0f
                state.retarget.bridgeWaypointY = 0f
                state.retarget.bridgeWaypointScale = 1f
                state.retarget.bridgeWaypointPhase = 0.36
                didRetargetThisFrame = true
            }
        } else if (focusBlock != null) {
            if (cameraTrackingMode == "stepped") {
                FumeCamera.resolveSteppedBlockFocusPoint(
                    focusBlock,
                    FumeReveal.resolvePrintedGraphemeCount(
                        focusBlock.line,
                        focusBlock.wordRanges,
                        focusBlock.graphemes.size,
                        time,
                    ),
                    focusPoint,
                )
            } else {
                FumeCamera.resolveSmoothBlockFocusPoint(
                    focusBlock,
                    FumeReveal.resolvePrintedGraphemeProgress(
                        focusBlock.line,
                        focusBlock.wordRanges,
                        focusBlock.graphemes.size,
                        time,
                    ),
                    focusPoint,
                )
            }
            FumeCamera.resolveBlockEntryFocusPoint(focusBlock, entryPoint)
            hasEntryFocusPoint = true
            targetCameraX = focusPoint.x
            targetCameraY = focusPoint.y
            targetCameraScale = FumeCamera.resolveCameraScaleForBlock(focusBlock, viewport)

            if (state.retarget.sourceLineIndex != focusBlock.sourceLineIndex) {
                state.retarget.sourceLineIndex = focusBlock.sourceLineIndex
                state.retarget.startedAt = time
                state.retarget.duration = FumeMath.clamp(
                    FumeCamera.resolveCameraRetargetDuration(focusBlock.line) / cameraSpeed,
                    0.03f,
                    0.3f,
                ).toDouble()
                state.retarget.fromX = state.camera.x
                state.retarget.fromY = state.camera.y
                state.retarget.fromScale = state.camera.scale
                state.retarget.bridgeMode = "none"
                state.retarget.bridgeWaypointX = 0f
                state.retarget.bridgeWaypointY = 0f
                state.retarget.bridgeWaypointScale = 1f
                state.retarget.bridgeWaypointPhase = 0.36
                didRetargetThisFrame = true
            }
        } else if (state.retarget.sourceLineIndex != -1) {
            state.retarget.sourceLineIndex = -1
            state.retarget.startedAt = time
            state.retarget.duration = FumeMath.clamp(
                0.18f / cameraSpeed,
                0.05f,
                0.4f,
            ).toDouble()
            state.retarget.fromX = state.camera.x
            state.retarget.fromY = state.camera.y
            state.retarget.fromScale = state.camera.scale
            state.retarget.bridgeMode = "none"
            state.retarget.bridgeWaypointX = 0f
            state.retarget.bridgeWaypointY = 0f
            state.retarget.bridgeWaypointScale = 1f
            state.retarget.bridgeWaypointPhase = 0.36
            didRetargetThisFrame = true
        }

        val retargetElapsed = max(time - state.retarget.startedAt, 0.0)
        out.overviewTextRestoreProgress =
            if (shouldShowOverview && state.retarget.sourceLineIndex == FumeCamera.OVERVIEW_CAMERA_SOURCE) {
                FumeMath.easeInOutCubic(
                    FumeMath.clamp(
                        (retargetElapsed / max(state.retarget.duration, 0.001)).toFloat(),
                        0f,
                        1f,
                    ),
                )
            } else {
                0f
            }
        val retargetPhase = FumeMath.clamp(
            (retargetElapsed / max(state.retarget.duration, 0.001)).toFloat(),
            0f,
            1f,
        )
        val retargetBoost = 1f - FumeMath.easeOutCubic(retargetPhase)
        val entryFocusBias = retargetBoost.toDouble().pow(0.58).toFloat()

        if (hasEntryFocusPoint) {
            targetCameraX = FumeMath.mix(targetCameraX, entryPoint.x, entryFocusBias)
            targetCameraY = FumeMath.mix(targetCameraY, entryPoint.y, entryFocusBias)
        }

        if (!staticMode) {
            // 移植 fumeCameraStep.ts:212-216 —— 三档空闲浮动的参数
            val floatDistance: Float
            val floatDuration: Float
            val floatScaleAmplitude: Float
            when (animationIntensity) {
                "chaotic" -> {
                    floatDistance = 24f
                    floatDuration = 5.8f
                    floatScaleAmplitude = 0.014f
                }
                "calm" -> {
                    floatDistance = 14f
                    floatDuration = 8.5f
                    floatScaleAmplitude = 0.008f
                }
                else -> {
                    floatDistance = 18f
                    floatDuration = 7f
                    floatScaleAmplitude = 0.011f
                }
            }
            val floatPhase = ((now / 1000.0 / floatDuration) * (Math.PI * 2.0)).toFloat()
            val overviewAttenuation = if (shouldShowOverview) 0.36f else 1f
            val screenFloatX = kotlin.math.sin(floatPhase * 0.74f + 0.8f) * floatDistance * 0.34f
            val screenFloatY = (
                kotlin.math.sin(floatPhase) * floatDistance +
                    kotlin.math.sin(floatPhase * 0.5f + 1.1f) * floatDistance * 0.22f
                ) * overviewAttenuation
            val worldFloatDivisor = max(targetCameraScale, 0.001f)

            targetCameraX -= screenFloatX / worldFloatDivisor
            targetCameraY -= screenFloatY / worldFloatDivisor
            targetCameraScale = FumeMath.clamp(
                targetCameraScale *
                    (1f + kotlin.math.sin(floatPhase + 0.9f) * floatScaleAmplitude * overviewAttenuation),
                FumeCamera.CAMERA_SCALE_MIN,
                FumeCamera.CAMERA_SCALE_MAX,
            )
        }

        if (didRetargetThisFrame) {
            val bridgeScale = max(max(state.camera.scale, targetCameraScale), 0.001f)
            val screenDeltaX = kotlin.math.abs(targetCameraX - state.retarget.fromX) * bridgeScale
            val screenDeltaY = kotlin.math.abs(targetCameraY - state.retarget.fromY) * bridgeScale
            val screenDistance = hypot(screenDeltaX.toDouble(), screenDeltaY.toDouble()).toFloat()
            state.retarget.bridgeMode =
                if (screenDistance >= min(viewport.width, viewport.height) * 0.42f) "direct" else "none"
            state.retarget.bridgeWaypointX = targetCameraX
            state.retarget.bridgeWaypointY = targetCameraY
            state.retarget.bridgeWaypointScale = targetCameraScale
            state.retarget.bridgeWaypointPhase = 0.5

            if (state.retarget.sourceLineIndex >= 0) {
                val hasFlightBridge = FumeCamera.resolveOverviewFlightBridge(
                    fromX = state.retarget.fromX,
                    fromY = state.retarget.fromY,
                    fromScale = state.retarget.fromScale,
                    targetX = targetCameraX,
                    targetY = targetCameraY,
                    targetScale = targetCameraScale,
                    overviewCamera = overviewCamera,
                    viewport = viewport,
                    out = flightBridge,
                )

                if (hasFlightBridge) {
                    state.retarget.bridgeMode = "overview"
                    state.retarget.bridgeWaypointX = flightBridge.waypointX
                    state.retarget.bridgeWaypointY = flightBridge.waypointY
                    state.retarget.bridgeWaypointScale = flightBridge.waypointScale
                    state.retarget.bridgeWaypointPhase = flightBridge.waypointPhase
                    state.retarget.duration = max(
                        state.retarget.duration,
                        FumeMath.clamp(
                            (flightBridge.duration / cameraSpeed).toFloat(),
                            0.16f,
                            0.9f,
                        ).toDouble(),
                    )
                }
            }
        }

        val cameraDistance = hypot(
            (targetCameraX - state.camera.x).toDouble(),
            (targetCameraY - state.camera.y).toDouble(),
        )
        val shouldUseBridge = state.retarget.bridgeMode != "none" && retargetPhase < 1f

        if (shouldUseBridge) {
            var bridgedCameraX = targetCameraX
            var bridgedCameraY = targetCameraY
            var bridgedCameraScale = targetCameraScale

            if (state.retarget.bridgeMode == "overview") {
                val bridgePhase = FumeMath.easeOutCubic(retargetPhase)
                bridgedCameraX = FumeMath.quadraticBezier(
                    state.retarget.fromX,
                    state.retarget.bridgeWaypointX,
                    targetCameraX,
                    bridgePhase,
                )
                bridgedCameraY = FumeMath.quadraticBezier(
                    state.retarget.fromY,
                    state.retarget.bridgeWaypointY,
                    targetCameraY,
                    bridgePhase,
                )
                bridgedCameraScale = FumeMath.quadraticBezier(
                    state.retarget.fromScale,
                    state.retarget.bridgeWaypointScale,
                    targetCameraScale,
                    bridgePhase,
                )
            } else {
                val bridgePhase = FumeMath.easeInOutCubic(retargetPhase)
                bridgedCameraX = FumeMath.mix(state.retarget.fromX, targetCameraX, bridgePhase)
                bridgedCameraY = FumeMath.mix(state.retarget.fromY, targetCameraY, bridgePhase)
                bridgedCameraScale = FumeMath.mix(state.retarget.fromScale, targetCameraScale, bridgePhase)
            }

            val bridgeCatchUp = 1f - exp(
                -dt * FumeMath.mix(
                    if (state.retarget.bridgeMode == "overview") 12.5f else 10.5f,
                    if (state.retarget.bridgeMode == "overview") 22f else 17.5f,
                    1f - retargetPhase,
                ),
            ).toFloat()

            state.camera.focusX = bridgedCameraX
            state.camera.focusY = bridgedCameraY
            state.camera.focusScale = bridgedCameraScale
            state.camera.x += (bridgedCameraX - state.camera.x) * bridgeCatchUp
            state.camera.y += (bridgedCameraY - state.camera.y) * bridgeCatchUp
            state.camera.scale += (bridgedCameraScale - state.camera.scale) * bridgeCatchUp
            state.camera.scale = FumeMath.clamp(
                state.camera.scale,
                FumeCamera.CAMERA_SCALE_MIN,
                FumeCamera.CAMERA_SCALE_MAX,
            )
            state.camera.velocityX *= 0.72f
            state.camera.velocityY *= 0.72f
            state.camera.velocityScale *= 0.68f
        } else {
            val boostedCatchUpRate = FumeMath.clamp(
                4.8f / max(state.retarget.duration.toFloat(), 0.05f),
                20f,
                54f,
            )
            val targetCatchUp = 1f - exp(
                -dt * FumeMath.mix(11.2f, boostedCatchUpRate, retargetBoost),
            ).toFloat()
            state.camera.focusX += (targetCameraX - state.camera.focusX) * targetCatchUp
            state.camera.focusY += (targetCameraY - state.camera.focusY) * targetCatchUp
            state.camera.focusScale += (targetCameraScale - state.camera.focusScale) *
                (1f - exp(-dt * FumeMath.mix(5.4f, 12.8f, retargetBoost)).toFloat())

            val springStrength = FumeMath.mix(
                208f,
                FumeMath.clamp(
                    (15.8f / max(state.retarget.duration * state.retarget.duration, 0.0064)).toFloat(),
                    260f,
                    780f,
                ),
                retargetBoost,
            )
            val damping = FumeMath.mix(
                24f,
                FumeMath.clamp(
                    (sqrt(springStrength.toDouble()) * 1.36).toFloat(),
                    24f,
                    40f,
                ),
                retargetBoost,
            )
            val accelX = (state.camera.focusX - state.camera.x) * springStrength -
                state.camera.velocityX * damping
            val accelY = (state.camera.focusY - state.camera.y) * springStrength -
                state.camera.velocityY * damping
            state.camera.velocityX += accelX * dt.toFloat()
            state.camera.velocityY += accelY * dt.toFloat()
            val maxVelocity = FumeMath.mix(
                1320f,
                FumeMath.clamp(
                    (cameraDistance / max((state.retarget.duration * 0.28).toFloat(), 0.028f)).toFloat(),
                    2600f,
                    8800f,
                ),
                retargetBoost,
            )
            state.camera.velocityX = FumeMath.clamp(state.camera.velocityX, -maxVelocity, maxVelocity)
            state.camera.velocityY = FumeMath.clamp(state.camera.velocityY, -maxVelocity, maxVelocity)
            state.camera.x += state.camera.velocityX * dt.toFloat()
            state.camera.y += state.camera.velocityY * dt.toFloat()

            val scaleSpringStrength = FumeMath.mix(54f, 108f, retargetBoost)
            val scaleDamping = FumeMath.mix(13.5f, 21f, retargetBoost)
            val accelScale = (state.camera.focusScale - state.camera.scale) * scaleSpringStrength -
                state.camera.velocityScale * scaleDamping
            state.camera.velocityScale += accelScale * dt.toFloat()
            state.camera.velocityScale = FumeMath.clamp(state.camera.velocityScale, -1.6f, 1.6f)
            state.camera.scale += state.camera.velocityScale * dt.toFloat()
            state.camera.scale = FumeMath.clamp(
                state.camera.scale,
                FumeCamera.CAMERA_SCALE_MIN,
                FumeCamera.CAMERA_SCALE_MAX,
            )
        }

        out.shouldShowOverview = shouldShowOverview
        return out
    }

    /** 移植 fumeCameraStep.ts:377-380 —— 后台视差常量 */
    private const val FUME_BACKGROUND_PARALLAX_X = 0.9f
    private const val FUME_BACKGROUND_PARALLAX_Y = 0.74f
    private const val FUME_BACKGROUND_SCALE_FACTOR = 0.94f
    private const val FUME_BACKGROUND_VERTICAL_OFFSET_RATIO = 0.22f

    /**
     * 移植 fumeCameraStep.ts:383-412 `resolveFumeBackgroundView` 的**完整**返回值。
     *
     * Folia 返回 `{ backgroundCenterX, backgroundCenterY, backgroundCameraX, backgroundCameraY, backgroundScale }`。
     * Android：写入调用方持有的可变对象（零分配）。
     */
    class FumeBackgroundView {
        var backgroundCenterX: Float = 0f
        var backgroundCenterY: Float = 0f
        var backgroundCameraX: Float = 0f
        var backgroundCameraY: Float = 0f
        var backgroundScale: Float = 1f
    }

    /**
     * 移植 fumeCameraStep.ts:383-412 `resolveFumeBackgroundView`（完整版本）。
     */
    fun resolveFumeBackgroundView(
        backgroundScene: FumeTypes.FumeBackgroundScene,
        camera: FumeTypes.CameraTarget,
        viewport: FumeTypes.ViewportSize,
        out: FumeBackgroundView,
    ): FumeBackgroundView {
        val screenScale = camera.scale
        out.backgroundCenterX = backgroundScene.width * 0.5f
        out.backgroundCenterY = backgroundScene.height * 0.5f
        val backgroundVerticalOffset = FumeMath.clamp(
            viewport.height * FUME_BACKGROUND_VERTICAL_OFFSET_RATIO / max(screenScale, 0.001f),
            48f,
            180f,
        )
        out.backgroundCameraX = FumeMath.mix(out.backgroundCenterX, camera.x, FUME_BACKGROUND_PARALLAX_X)
        out.backgroundCameraY = FumeMath.mix(out.backgroundCenterY, camera.y, FUME_BACKGROUND_PARALLAX_Y) -
            backgroundVerticalOffset
        out.backgroundScale = FumeMath.clamp(
            screenScale * FUME_BACKGROUND_SCALE_FACTOR,
            FumeCamera.CAMERA_SCALE_MIN,
            FumeCamera.CAMERA_SCALE_MAX,
        )
        return out
    }
}
