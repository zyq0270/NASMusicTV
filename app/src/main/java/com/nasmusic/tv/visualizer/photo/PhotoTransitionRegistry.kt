package com.nasmusic.tv.visualizer.photo

import com.nasmusic.tv.visualizer.photo.transitions.BassBloomTransition
import com.nasmusic.tv.visualizer.photo.transitions.BeatCutTransition
import com.nasmusic.tv.visualizer.photo.transitions.BlindsTransition
import com.nasmusic.tv.visualizer.photo.transitions.BloomTransition
import com.nasmusic.tv.visualizer.photo.transitions.BlocksRandomTransition
import com.nasmusic.tv.visualizer.photo.transitions.BurnTransition
import com.nasmusic.tv.visualizer.photo.transitions.CheckerboardTransition
import com.nasmusic.tv.visualizer.photo.transitions.ChromaticSplitTransition
import com.nasmusic.tv.visualizer.photo.transitions.CinematicBarsTransition
import com.nasmusic.tv.visualizer.photo.transitions.ColorBurnTransition
import com.nasmusic.tv.visualizer.photo.transitions.ComicPanelTransition
import com.nasmusic.tv.visualizer.photo.transitions.CoverTransition
import com.nasmusic.tv.visualizer.photo.transitions.CrossfadeTransition
import com.nasmusic.tv.visualizer.photo.transitions.CrossZoomTransition
import com.nasmusic.tv.visualizer.photo.transitions.DepthBlurTransition
import com.nasmusic.tv.visualizer.photo.transitions.DiagonalSlideTransition
import com.nasmusic.tv.visualizer.photo.transitions.DollyZoomTransition
import com.nasmusic.tv.visualizer.photo.transitions.ExposureFlashTransition
import com.nasmusic.tv.visualizer.photo.transitions.FadeBlackTransition
import com.nasmusic.tv.visualizer.photo.transitions.FadeColorTransition
import com.nasmusic.tv.visualizer.photo.transitions.FadeWhiteTransition
import com.nasmusic.tv.visualizer.photo.transitions.FilmRollTransition
import com.nasmusic.tv.visualizer.photo.transitions.FrostTransition
import com.nasmusic.tv.visualizer.photo.transitions.GlitchTransition
import com.nasmusic.tv.visualizer.photo.transitions.GrainDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.GridFlipTransition
import com.nasmusic.tv.visualizer.photo.transitions.HalftoneTransition
import com.nasmusic.tv.visualizer.photo.transitions.InkSpreadTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisCircleTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisDiamondTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisHexagonTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisStarTransition
import com.nasmusic.tv.visualizer.photo.transitions.IrisTriangleTransition
import com.nasmusic.tv.visualizer.photo.transitions.KaleidoTransition
import com.nasmusic.tv.visualizer.photo.transitions.LightSweepTransition
import com.nasmusic.tv.visualizer.photo.transitions.LiquifyTransition
import com.nasmusic.tv.visualizer.photo.transitions.MatrixOverlayTransition
import com.nasmusic.tv.visualizer.photo.transitions.MeltTransition
import com.nasmusic.tv.visualizer.photo.transitions.MosaicTransition
import com.nasmusic.tv.visualizer.photo.transitions.NeonTraceTransition
import com.nasmusic.tv.visualizer.photo.transitions.NoiseDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.PageFlipTransition
import com.nasmusic.tv.visualizer.photo.transitions.ParallaxSlideTransition
import com.nasmusic.tv.visualizer.photo.transitions.PerspectivePushTransition
import com.nasmusic.tv.visualizer.photo.transitions.PixelateTransition
import com.nasmusic.tv.visualizer.photo.transitions.PuzzleTransition
import com.nasmusic.tv.visualizer.photo.transitions.PulseDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.PushTransition
import com.nasmusic.tv.visualizer.photo.transitions.RevealTransition
import com.nasmusic.tv.visualizer.photo.transitions.RgbSlideTransition
import com.nasmusic.tv.visualizer.photo.transitions.RippleTransition
import com.nasmusic.tv.visualizer.photo.transitions.SandDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.ScanlineDissolveTransition
import com.nasmusic.tv.visualizer.photo.transitions.ShatterTransition
import com.nasmusic.tv.visualizer.photo.transitions.ShapeRandomTransition
import com.nasmusic.tv.visualizer.photo.transitions.SlideTransition
import com.nasmusic.tv.visualizer.photo.transitions.SpectrumBarsTransition
import com.nasmusic.tv.visualizer.photo.transitions.SpectrumWipeTransition
import com.nasmusic.tv.visualizer.photo.transitions.SwirlTransition
import com.nasmusic.tv.visualizer.photo.transitions.ThresholdSweepTransition
import com.nasmusic.tv.visualizer.photo.transitions.TileCascadeTransition
import com.nasmusic.tv.visualizer.photo.transitions.VoronoiTransition
import com.nasmusic.tv.visualizer.photo.transitions.WatercolorTransition
import com.nasmusic.tv.visualizer.photo.transitions.WaveformWipeTransition
import com.nasmusic.tv.visualizer.photo.transitions.WaveWarpTransition
import com.nasmusic.tv.visualizer.photo.transitions.WipeClockTransition
import com.nasmusic.tv.visualizer.photo.transitions.WipeCrossTransition
import com.nasmusic.tv.visualizer.photo.transitions.WipeLinearTransition
import com.nasmusic.tv.visualizer.photo.transitions.WipeSpiralTransition
import com.nasmusic.tv.visualizer.photo.transitions.ZoomThroughTransition
import com.nasmusic.tv.visualizer.photo.transitions.ZoomTransition

/**
 * 转场注册表（id → 实现）
 *
 * ## 「单例」的含义
 *
 * 每个实现只创建**一个**实例并全进程复用 —— 所以实现类**必须无状态**，
 * 或者只持有可被 `prepare()` 按几何重建的缓冲（见 `PhotoTransition` 的约束 2）。
 * P0 的 15 个实现全部满足：需要的只有 `geom`（每帧传入）、`p`（每帧传入）
 * 与 `MaskCache`（全进程共享的只读遮罩）。
 *
 * ⚠️ 有状态的实现（例如「记住上一次的方向」）**不能**放进这里 ——
 * 主题级交叉淡入时新旧两层 `PhotoRenderer` 会同时用它，状态会互相踩。
 * 需要「每次切换重新决定」的东西请用 [PhotoTransition.onSwapStart]。
 *
 * ## 为什么要有「枚举 ↔ 注册表」门禁（G4）
 *
 * `PhotoTransitionId` 有 76 项（规划），但代码里只实现了一部分。
 * 若某个 id 被 `PhotoTransitionPicker` 抽中、`get()` 却返回 `null`，
 * 结果是**画面卡在上一张不动**（静默故障，最难查的一类）。
 * ⇒ 池必须由 [available] 构造，门禁断言「当前分期应实现的每一项都在 [available] 里」。
 */
object PhotoTransitionRegistry {

    /**
     * P0 + P1 + P2 共 **76** 种（§14.3 参数表；P2 档补齐后与
     * `PhotoTransitionId.implemented(Phase.P2)` 恰好一致 —— G4 钉住）。
     *
     * ⚠️ 新增实现时**必须**同时加进这里，否则抽不到（G4 会拦住「加了枚举没加注册」）。
     *
     * ⚠️ **追加顺序有意义**：随机池按「降级后的标识」去重时**保留先出现的那一个** ——
     * P2 的项一律追加在既有 43 项之后 ⇒ 老平台上 `CROSSFADE`（枚举第一个常量）
     * 仍然胜出、`NOISE_DISSOLVE` 仍然胜过 `LIQUIFY`，与枚举顺序的结论一致。
     */
    private val impls: Map<PhotoTransitionId, PhotoTransition> = mapOf(
        // A 淡化
        PhotoTransitionId.CROSSFADE to CrossfadeTransition(),
        PhotoTransitionId.FADE_BLACK to FadeBlackTransition(),

        // B 滑动（dirX/dirY = 新图入场方向）
        PhotoTransitionId.SLIDE_LEFT to SlideTransition(PhotoTransitionId.SLIDE_LEFT, 1f, 0f),
        PhotoTransitionId.SLIDE_RIGHT to SlideTransition(PhotoTransitionId.SLIDE_RIGHT, -1f, 0f),
        PhotoTransitionId.SLIDE_UP to SlideTransition(PhotoTransitionId.SLIDE_UP, 0f, 1f),
        PhotoTransitionId.SLIDE_DOWN to SlideTransition(PhotoTransitionId.SLIDE_DOWN, 0f, -1f),

        // C 缩放（aFrom, aTo, bFrom, bTo）
        PhotoTransitionId.ZOOM_IN to ZoomTransition(PhotoTransitionId.ZOOM_IN, 1.00f, 1.15f, 0.85f, 1.00f),
        PhotoTransitionId.ZOOM_OUT to ZoomTransition(PhotoTransitionId.ZOOM_OUT, 1.00f, 0.85f, 1.15f, 1.00f),

        // D 遮罩形状
        PhotoTransitionId.IRIS_CIRCLE to IrisCircleTransition(),
        PhotoTransitionId.WIPE_LINEAR to WipeLinearTransition(),

        // E 条纹分块
        PhotoTransitionId.BLINDS_H to BlindsTransition(PhotoTransitionId.BLINDS_H, horizontal = true),
        PhotoTransitionId.BLINDS_V to BlindsTransition(PhotoTransitionId.BLINDS_V, horizontal = false),

        // F 溶解
        PhotoTransitionId.NOISE_DISSOLVE to NoiseDissolveTransition(),

        // H 色彩光效
        PhotoTransitionId.LIGHT_SWEEP to LightSweepTransition(),
        PhotoTransitionId.SPECTRUM_WIPE to SpectrumWipeTransition(),

        // ───────── P1（阶段 12 补齐，共 28 种）─────────

        // A 淡化
        PhotoTransitionId.FADE_WHITE to FadeWhiteTransition(),
        PhotoTransitionId.FADE_COLOR to FadeColorTransition(),

        // B 滑动 / 位移
        PhotoTransitionId.PUSH to PushTransition(1f, 0f),
        PhotoTransitionId.COVER to CoverTransition(1f, 0f),
        PhotoTransitionId.REVEAL to RevealTransition(1f, 0f),
        PhotoTransitionId.SLIDE_DIAGONAL to DiagonalSlideTransition(),

        // C 缩放 / 深度
        PhotoTransitionId.CROSS_ZOOM to CrossZoomTransition(),
        PhotoTransitionId.ZOOM_THROUGH to ZoomThroughTransition(),
        PhotoTransitionId.DEPTH_BLUR to DepthBlurTransition(),

        // D 遮罩形状
        PhotoTransitionId.IRIS_DIAMOND to IrisDiamondTransition(),
        PhotoTransitionId.IRIS_STAR to IrisStarTransition(),
        PhotoTransitionId.IRIS_HEXAGON to IrisHexagonTransition(),
        PhotoTransitionId.SHAPE_RANDOM to ShapeRandomTransition(),
        PhotoTransitionId.WIPE_CLOCK to WipeClockTransition(),
        PhotoTransitionId.WIPE_CROSS to WipeCrossTransition(),

        // E 条纹 / 分块
        PhotoTransitionId.CHECKERBOARD to CheckerboardTransition(),
        PhotoTransitionId.BLOCKS_RANDOM to BlocksRandomTransition(),
        PhotoTransitionId.TILE_CASCADE to TileCascadeTransition(),

        // F 溶解 / 噪点
        PhotoTransitionId.THRESHOLD_SWEEP to ThresholdSweepTransition(),
        PhotoTransitionId.SCANLINE_DISSOLVE to ScanlineDissolveTransition(),

        // G 扭曲（P1 只含 KALEIDO）
        PhotoTransitionId.KALEIDO to KaleidoTransition(),

        // H 色彩 / 光效
        PhotoTransitionId.CHROMATIC_SPLIT to ChromaticSplitTransition(),
        PhotoTransitionId.RGB_SLIDE to RgbSlideTransition(),
        PhotoTransitionId.EXPOSURE_FLASH to ExposureFlashTransition(),

        // I 音频反应（★ 默认不进随机池，用户固定选中才生效）
        PhotoTransitionId.SPECTRUM_BARS to SpectrumBarsTransition(),
        PhotoTransitionId.BEAT_CUT to BeatCutTransition(),

        // J 风格化
        PhotoTransitionId.GLITCH to GlitchTransition(),
        PhotoTransitionId.CINEMATIC_BARS to CinematicBarsTransition(),

        // ───────── P2（照片墙转场 P2 档补齐，共 33 种）─────────

        // B 滑动 / 位移
        PhotoTransitionId.PARALLAX_SLIDE to ParallaxSlideTransition(),

        // C 缩放 / 深度
        PhotoTransitionId.PERSPECTIVE_PUSH to PerspectivePushTransition(),
        PhotoTransitionId.DOLLY_ZOOM to DollyZoomTransition(),

        // D 遮罩形状（走 clipPath ⇒ 已登记在 CLIP_BASED，API < 26 降级 CROSSFADE）
        PhotoTransitionId.IRIS_TRIANGLE to IrisTriangleTransition(),
        PhotoTransitionId.WIPE_SPIRAL to WipeSpiralTransition(),

        // E 条纹 / 分块（每项块数 ≤ 84，M4 红线）
        PhotoTransitionId.GRID_FLIP to GridFlipTransition(),
        PhotoTransitionId.MOSAIC to MosaicTransition(),
        PhotoTransitionId.PUZZLE to PuzzleTransition(),
        PhotoTransitionId.PIXELATE to PixelateTransition(),
        PhotoTransitionId.SHATTER to ShatterTransition(),
        PhotoTransitionId.VORONOI to VoronoiTransition(),
        PhotoTransitionId.FILM_ROLL to FilmRollTransition(),
        PhotoTransitionId.COMIC_PANEL to ComicPanelTransition(),

        // F 溶解 / 噪点
        PhotoTransitionId.GRAIN_DISSOLVE to GrainDissolveTransition(),
        PhotoTransitionId.HALFTONE to HalftoneTransition(),

        // G 扭曲（⚠️ 枚举标着 M5_SHADER，但本仓零 AGSL ⇒ 实现的全是 M3 近似）
        PhotoTransitionId.RIPPLE to RippleTransition(),
        PhotoTransitionId.WAVE_WARP to WaveWarpTransition(),
        PhotoTransitionId.SWIRL to SwirlTransition(),
        PhotoTransitionId.LIQUIFY to LiquifyTransition(),
        PhotoTransitionId.MELT to MeltTransition(),

        // H 色彩 / 光效
        PhotoTransitionId.BLOOM_TRANSITION to BloomTransition(),
        PhotoTransitionId.COLOR_BURN to ColorBurnTransition(),

        // I 音频反应（★ 默认不进随机池，用户固定选中才生效）
        PhotoTransitionId.BASS_BLOOM to BassBloomTransition(),
        PhotoTransitionId.WAVEFORM_WIPE to WaveformWipeTransition(),
        PhotoTransitionId.PULSE_DISSOLVE to PulseDissolveTransition(),

        // J 风格化
        PhotoTransitionId.PAGE_FLIP to PageFlipTransition(),
        PhotoTransitionId.MATRIX_OVERLAY to MatrixOverlayTransition(),
        PhotoTransitionId.NEON_TRACE to NeonTraceTransition(),

        // K 有机 / 模拟
        PhotoTransitionId.INK_SPREAD to InkSpreadTransition(),
        PhotoTransitionId.WATERCOLOR to WatercolorTransition(),
        PhotoTransitionId.SAND_DISSOLVE to SandDissolveTransition(),
        PhotoTransitionId.BURN to BurnTransition(),
        PhotoTransitionId.FROST to FrostTransition(),
    )

    /** 取实现；未实现的 id 返回 `null`（⚠️ 调用方必须能处理，抽池请用 [available]） */
    fun get(id: PhotoTransitionId): PhotoTransition? = impls[id]

    /** 当前**真有实现**的效果集合 —— 随机池只能由它构造（§5.9「池跟着分期走」） */
    fun available(): Set<PhotoTransitionId> = impls.keys

    /** 已实现数量（P0 + P1 + P2 = 76；观察 / 单测用） */
    val implementedCount: Int get() = impls.size
}
