package com.nasmusic.tv.visualizer

import android.content.Context
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.renderers.BeatFireworkRenderer
import com.nasmusic.tv.visualizer.renderers.BirdsFlockRenderer
import com.nasmusic.tv.visualizer.renderers.CircularRingRenderer
import com.nasmusic.tv.visualizer.renderers.ConstellationRenderer
import com.nasmusic.tv.visualizer.renderers.DesertSmokeRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidGridRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidRippleRenderer
import com.nasmusic.tv.visualizer.renderers.LofiCityRenderer
import com.nasmusic.tv.visualizer.renderers.MatrixRainRenderer
import com.nasmusic.tv.visualizer.renderers.MilkdropRenderer
import com.nasmusic.tv.visualizer.renderers.MoonlitRenderer
import com.nasmusic.tv.visualizer.renderers.LyricsDotMatrixRenderer
import com.nasmusic.tv.visualizer.renderers.EcgWaveRenderer
import com.nasmusic.tv.visualizer.renderers.HypnoticFunctionRenderer
import com.nasmusic.tv.visualizer.renderers.OrbitalRingsRenderer
import com.nasmusic.tv.visualizer.renderers.RadarGridRenderer
import com.nasmusic.tv.visualizer.renderers.SeasideRenderer
import com.nasmusic.tv.visualizer.renderers.SnowCedarRenderer
import com.nasmusic.tv.visualizer.renderers.ConcentricGearsRenderer
import com.nasmusic.tv.visualizer.renderers.LightBeamsRenderer
import com.nasmusic.tv.visualizer.renderers.MoleculeRenderer
import com.nasmusic.tv.visualizer.renderers.VintageTvRenderer
import com.nasmusic.tv.visualizer.renderers.DnaRenderer
import com.nasmusic.tv.visualizer.renderers.StarrySkyRenderer
import com.nasmusic.tv.visualizer.renderers.TripodFogRenderer
import com.nasmusic.tv.visualizer.renderers.WorldGlobeRenderer
import com.nasmusic.tv.visualizer.photo.PhotoRenderer

/**
 * 渲染器工厂 —— 主题枚举 → 渲染器实现。
 *
 * 新增一套效果只需：① 新增 Renderer 实现；② 在此处加一个 when 分支。
 * 音频分析层零改动。
 */
object VisualizerRendererFactory {

    /**
     * 主题枚举 → 渲染器实现。
     *
     * [context] 供需要宿主侧资源的渲染器使用：View 型（如 three-globe 的 WebView）创建 View，
     * E44「明月」解码 `assets/globe/moon.jpg`。其余 Canvas 渲染器都忽略它，保持原实现。WORLD 分支已切到
     * 3D 版 [WorldGlobeRenderer]（WebView + three-globe）；旧 2D 版
     * WorldRenderer 已整文件删除归档（M11 修复，2026-10-06，原 2106 行零实例化死代码）。
     *
     * ⚠️ 外层那个 `if` 是 E51 双变体收口 B，**不要"顺手"合并进 when**：
     * 公开 release（CI 缺省 `lofi.city` 未设 ⇒ `false`）里备份 JSON 的
     * `"visualizerTheme": "LOFI_CITY"` 仍会被 [VisualizerTheme.fromKey] 按枚举名解析成功
     * （开关只作用于运行期列表，不影响枚举声明），必须在真正 new 渲染器的这一刻回落，
     * 否则公开版会渲染出未获上游许可的效果。`VisualizerTheme.selectable()` 是收口 A，两处都要在。
     */
    fun create(theme: VisualizerTheme, context: Context): VisualizerRenderer =
        if (!BuildConfig.LOFI_CITY_ENABLED && theme == VisualizerTheme.LOFI_CITY) {
            // 回落 = 枚举声明的默认效果 [VisualizerTheme.Default] 对应的那个渲染器
            CircularRingRenderer()
        } else {
            createEnabled(theme, context)
        }

    private fun createEnabled(theme: VisualizerTheme, context: Context): VisualizerRenderer = when (theme) {
        VisualizerTheme.CIRCULAR_RING -> CircularRingRenderer()
        VisualizerTheme.LIQUID_GRID -> LiquidGridRenderer()
        VisualizerTheme.BEAT_FIREWORK -> BeatFireworkRenderer()
        VisualizerTheme.LIQUID_RIPPLE -> LiquidRippleRenderer()
        VisualizerTheme.MATRIX_RAIN -> MatrixRainRenderer()
        VisualizerTheme.CONSTELLATION -> ConstellationRenderer()
        VisualizerTheme.MILKDROP_FEEDBACK -> MilkdropRenderer()
        VisualizerTheme.LYRICS_DOT_MATRIX -> LyricsDotMatrixRenderer()
        VisualizerTheme.ECG_WAVE -> EcgWaveRenderer()
        VisualizerTheme.HYPNOTIC_FUNCTION -> HypnoticFunctionRenderer()
        VisualizerTheme.ORBITAL_RINGS -> OrbitalRingsRenderer()
        VisualizerTheme.RADAR_GRID -> RadarGridRenderer()
        VisualizerTheme.CONCENTRIC_GEARS -> ConcentricGearsRenderer()
        VisualizerTheme.LIGHT_BEAMS -> LightBeamsRenderer()
        VisualizerTheme.MOLECULE -> MoleculeRenderer()
        VisualizerTheme.VINTAGE_TV -> VintageTvRenderer()
        VisualizerTheme.PHOTO_WALL -> PhotoRenderer()
        VisualizerTheme.DNA -> DnaRenderer()
        VisualizerTheme.WORLD -> WorldGlobeRenderer(context)
        VisualizerTheme.STAR_TRAILS -> StarrySkyRenderer()
        VisualizerTheme.SEASIDE -> SeasideRenderer()
        // ⚠️ 传 applicationContext 而非 Activity：E44 要把这个 Context 存成字段用于
        // `assets/globe/moon.jpg` 的解码，存 Activity 会泄漏（`MoonlitContextLeakTest` 扫这两条）。
        VisualizerTheme.MOONLIT -> MoonlitRenderer(context.applicationContext)
        VisualizerTheme.TRIPOD_FOG -> TripodFogRenderer()
        // E47「雪松」零外部资产（8 类位图全在 onEnter 后按画幅烘焙）⇒ ⛔ 不收 Context。
        VisualizerTheme.SNOW_CEDAR -> SnowCedarRenderer()
        // E49「鸟群」零外部资产（6 类位图全在首帧 boot 程序化烘焙）⇒ ⛔ 不收 Context。
        VisualizerTheme.BIRDS_FLOCK -> BirdsFlockRenderer()
        // E48「大漠孤烟」零外部资产（14 类位图全在首帧 boot 程序化烘焙）⇒ ⛔ 不收 Context。
        VisualizerTheme.DESERT_SMOKE -> DesertSmokeRenderer()
        // E51「像素城市」零外部资产（场景位图全在 onEnter 后程序化烘焙）⇒ ⛔ 不收 Context。
        // ⚠️ 本行的字面形态 `-> LofiCityRenderer(` 被 ParticleBudgetGateTest 的工厂映射正则消费，
        // 双变体的开关在 create() 外层，⛔ 不要写成 `-> if (...) ... else ...`。
        VisualizerTheme.LOFI_CITY -> LofiCityRenderer()
    }

    /**
     * 在当前画质下实际可用的主题列表。
     * 用于指示器渲染与左右切换时的跳过逻辑。
     *
     * @param photoWallAvailable 三来源开关之「或」（§7.4）。三关 ⇒ [VisualizerTheme.PHOTO_WALL] 不出现。
     */
    fun availableThemes(quality: VisualQuality, photoWallAvailable: Boolean): List<VisualizerTheme> =
        VisualizerTheme.selectable(photoWallAvailable).filter { quality.supports(it) }
}
