package com.nasmusic.tv.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ShapeDefaults
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * 主题调色板 —— 深浅两套 token 的唯一真源。
 *
 * 业务界面**不要**直接引用 [NasPalettes.Dark] / [NasPalettes.Light]：
 * 一律走 [NasMusicColors] 的 composable getter。允许直接引用的只有三类：
 * `ui/theme/` 内部、`ForceDarkPalette` 强制深色区（频谱舞台）、
 * 以及由调用方注入颜色的纯绘制类。
 */
@Immutable
data class NasPalette(
    val isLight: Boolean,
    // 背景
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    // 主色
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    /** 主按钮聚焦强调（原 PrimaryBright） */
    val primaryEmphasis: Color,
    // 文字
    val textPrimary: Color,
    val textSecondary: Color,
    // 歌词
    /** 逐字歌词的「已唱」高亮：深色保持纯黄，浅色用品牌次色的深阶藏蓝（与青绿同属冷色系） */
    val karaokeHighlight: Color,
    // 边框
    val border: Color,
    // 状态
    val danger: Color,
    val onDanger: Color,
    val warning: Color,
    val onWarning: Color,
    val success: Color,
    // 焦点
    val focusRing: Color,
    // glow / accent
    val accentGlow: Color,
    val accentGlowStrong: Color,
)

/**
 * 深色 = 现有线上值（**冻结**）；浅色 = 品牌青绿派生（对比度由 `ThemeContrastTest` 断言）。
 *
 * ⚠️ 深色 hex 除 `onPrimary` / `onDanger` / `onWarning`（新增的语义容器前景）外，
 * 一律不得改动 —— 深色回归是本次改造的硬约束。
 */
object NasPalettes {

    val Dark = NasPalette(
        isLight = false,
        background = Color(0xFF0C1222),
        surface = Color(0xFF162032),
        surfaceVariant = Color(0xFF1E2D42),
        primary = Color(0xFF2DD4BF),
        onPrimary = Color(0xFF0C1222),
        secondary = Color(0xFF60A5FA),
        primaryEmphasis = Color(0xFF5EEAD4),
        textPrimary = Color(0xFFE8EDF5),
        textSecondary = Color(0xFF8899B0),
        karaokeHighlight = Color(0xFFFFFF00),
        border = Color(0xFF2A3A52),
        danger = Color(0xFFF87171),
        onDanger = Color(0xFF0C1222),
        warning = Color(0xFFFBBF24),
        onWarning = Color(0xFF0C1222),
        success = Color(0xFF34D399),
        focusRing = Color(0xFF2DD4BF),
        accentGlow = Color(0x262DD4BF),
        accentGlowStrong = Color(0x4D2DD4BF),
    )

    val Light = NasPalette(
        isLight = true,
        background = Color(0xFFF3F5F9),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE7ECF3),
        primary = Color(0xFF0F766E),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF1D4ED8),
        primaryEmphasis = Color(0xFF115E59),
        textPrimary = Color(0xFF0F172A),
        textSecondary = Color(0xFF475569),
        karaokeHighlight = Color(0xFF1E3A8A),
        border = Color(0xFFCBD5E1),
        danger = Color(0xFFB91C1C),
        onDanger = Color(0xFFFFFFFF),
        warning = Color(0xFFB45309),
        onWarning = Color(0xFFFFFFFF),
        success = Color(0xFF047857),
        focusRing = Color(0xFF0F766E),
        accentGlow = Color(0x1F0F766E),
        accentGlowStrong = Color(0x330F766E),
    )
}

/** 当前调色板（默认深色，保证未包裹主题的预览/测试不会崩） */
val LocalNasPalette = staticCompositionLocalOf { NasPalettes.Dark }

/**
 * 系统栏（状态栏 / 底部导航栏）底色：与顶栏、底部导航的 `surface` 同色，
 * 让系统栏区域和前后条连成一片（`themes.xml` 继承的 Material 默认是纯黑，不写就永远黑）。
 *
 * ⚠️ 浅色底的前提是系统能把图标刷成深色：`FLAG_LIGHT_STATUS_BAR` 需 API 23、
 * `FLAG_LIGHT_NAVIGATION_BARS` 需 API 26。够不到门槛时**退回深色底** ——
 * 本项目 minSdk 22，白图标压白底等于看不见。
 */
fun systemBarSurfaceColor(darkTheme: Boolean, lightIconsSupported: Boolean): Color =
    if (darkTheme || !lightIconsSupported) NasPalettes.Dark.surface else NasPalettes.Light.surface

/** 状态栏底色（图标可否刷深由 API 等级决定） */
fun statusBarSurfaceColor(darkTheme: Boolean): Color =
    systemBarSurfaceColor(darkTheme, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)

/** 底部导航栏底色（图标可否刷深由 API 等级决定） */
fun navigationBarSurfaceColor(darkTheme: Boolean): Color =
    systemBarSurfaceColor(darkTheme, Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

/**
 * 项目级别的颜色系统（与 HTML index.html 中的 CSS 变量一一对应）。
 *
 * 全部属性为 **composable getter**：1228 处 `NasMusicColors.X` 调用点零改动，
 * 在组合期自动读当前调色板。代价是非组合上下文（`remember` / `DrawScope` /
 * 顶层 `val`）会**编译失败**，必须把读取上提到 `@Composable` 作用域 —— 这是特性，
 * 不是缺陷（见 `docs/light-theme-plan.md` §2.3）。
 */
object NasMusicColors {
    // 背景
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.background
    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.surface
    val SurfaceVariant: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.surfaceVariant
    // 主色
    val Primary: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.primary
    /** 主色实底上的前景（⛔ 不要再用 TextPrimary / 纯黑纯白） */
    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.onPrimary
    val Secondary: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.secondary
    /** 主按钮聚焦强调（原 PrimaryBright） */
    val PrimaryBright: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.primaryEmphasis
    // 文字
    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.textPrimary
    val TextSecondary: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.textSecondary
    // 歌词
    /** 逐字歌词的「已唱」高亮（⛔ 不要再用 Color.Yellow，浅色白底不可读） */
    val KaraokeHighlight: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.karaokeHighlight
    // 边框
    val Border: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.border
    // 状态
    val Danger: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.danger
    /** 危险色实底上的前景 */
    val OnDanger: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.onDanger
    val Warning: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.warning
    /** 警告色实底上的前景 */
    val OnWarning: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.onWarning
    val Success: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.success
    // 焦点
    val FocusRing: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.focusRing
    // glow / accent
    val AccentGlow: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.accentGlow
    val AccentGlowStrong: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.accentGlowStrong
}

/**
 * 常用渐变 — 例如进度条、背景遮罩、渐隐 mask。
 *
 * 颜色全部从**当前**调色板派生，浅色主题自动跟随。
 */
object NasMusicBrushes {
    val progressBar: Brush
        @Composable @ReadOnlyComposable get() = Brush.horizontalGradient(
            colors = listOf(NasMusicColors.Primary, NasMusicColors.Secondary)
        )
    val topFadeMask: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            colors = listOf(NasMusicColors.Background.copy(alpha = 0.8f), Color.Transparent)
        )
    val bottomFadeMask: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            colors = listOf(Color.Transparent, NasMusicColors.Background.copy(alpha = 0.8f))
        )
    val overlayGradient: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            colors = listOf(
                NasMusicColors.Background.copy(alpha = 0.85f),
                NasMusicColors.Background
            )
        )
    /** 播放页整页底：深色维持 `#0C1222 → #0A1020`，浅色用 Background → SurfaceVariant */
    val pageBackground: Brush
        @Composable @ReadOnlyComposable get() = Brush.verticalGradient(
            colors = listOf(
                NasMusicColors.Background,
                if (LocalNasPalette.current.isLight) NasMusicColors.SurfaceVariant
                else Color(0xFF0A1020)
            )
        )
    val coverGlow: Brush
        @Composable @ReadOnlyComposable get() = Brush.radialGradient(
            colors = listOf(NasMusicColors.AccentGlowStrong, Color.Transparent)
        )
}

/**
 * 内容遮罩色（**不随主题变**）。
 *
 * 封面 / 视频上的黑纱与白字必须恒为这套值：若跟随主题，浅色模式会把
 * 「白字压作品图」变成深字压图或白底白字，两向都不可读。
 * §6 光学走查时，业务文件里的 `Color.Black/White` 要么归类到这里，要么换成主题 token。
 */
object NasOverlayColors {
    /** 作品图上的强遮罩（底部文字区） */
    val ArtworkScrimStrong = Color(0xCC000000)
    /** 作品图上的弱遮罩（渐变起始） */
    val ArtworkScrimSoft = Color(0x66000000)
    /** 作品图上的主文字 */
    val OnArtwork = Color(0xFFFFFFFF)
    /** 作品图上的次要文字 */
    val OnArtworkSecondary = Color(0xB3FFFFFF)
}

/** 在指定调色板下强制渲染子树（频谱舞台 / 照片墙专用） */
@Composable
fun ForceDarkPalette(content: @Composable () -> Unit) {
    val scheme = remember { colorSchemeFor(NasPalettes.Dark) }
    val lyrics = remember { lyricsThemeData(NasPalettes.Dark) }
    CompositionLocalProvider(
        LocalNasPalette provides NasPalettes.Dark,
        LocalLyricsTheme provides lyrics
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content
        )
    }
}

object NasMusicDimens {
    val PaddingSmall = 8.dp
    val PaddingMedium = 16.dp
    val PaddingLarge = 24.dp
    val PaddingXLarge = 40.dp
    val CornerRadius = 12.dp
    val CoverSize = 200.dp
    val CoverSizeLarge = 360.dp
}

/**
 * 字号分级系统
 *
 * 所有字号统一归入 8 个档位，每个档位对应手机/TV 两套数值：
 * - 手机端保持设计稿原始值
 * - TV 端统一 +12sp（比手机端大 12 号，适配大屏）
 *
 * 使用方式：直接引用 FontSize.XX，例如 Text(fontSize = FontSize.Body)
 * 或通过 Composable 函数获取设备适配值：FontSize.body()
 */
/** 全局字体字号调整量（sp）。用户在设置中增减，正值放大、负值缩小。 */
val LocalFontAdjustment = androidx.compose.runtime.staticCompositionLocalOf { 0 }

object FontSize {
    // 档位 1: Caption（注释、徽章、最小文字）
    val Caption = 12.sp        // 手机
    val CaptionTv = 18.sp      // TV = 12 + 6

    // 档位 2: Small（次要文字、设置值、按钮小字）
    val Small = 14.sp
    val SmallTv = 20.sp        // TV = 14 + 6

    // 档位 3: Body（正文、列表项、主要文字）
    val Body = 17.sp
    val BodyTv = 23.sp         // TV = 17 + 6

    // 档位 4: Button（按钮文字、标签）
    val Button = 19.sp
    val ButtonTv = 25.sp       // TV = 19 + 6

    // 档位 5: Subtitle（副标题、卡片标题、对话框标题）
    val Subtitle = 23.sp
    val SubtitleTv = 29.sp     // TV = 23 + 6

    // 档位 6: Title（大标题、歌词普通行）
    val Title = 27.sp
    val TitleTv = 33.sp        // TV = 27 + 6

    // 档位 7: Display（展示文字、歌词当前行、大数字）
    val Display = 33.sp
    val DisplayTv = 39.sp      // TV = 33 + 6

    // 档位 8: DisplayLarge（超大展示）
    val DisplayLarge = 41.sp
    val DisplayLargeTv = 47.sp // TV = 41 + 6

    /** 根据当前设备返回对应档位的字号（含用户全局字体调整） */
    @Composable
    fun caption(): TextUnit = adjustedSize(Caption, CaptionTv)
    @Composable
    fun small(): TextUnit = adjustedSize(Small, SmallTv)
    @Composable
    fun body(): TextUnit = adjustedSize(Body, BodyTv)
    @Composable
    fun button(): TextUnit = adjustedSize(Button, ButtonTv)
    @Composable
    fun subtitle(): TextUnit = adjustedSize(Subtitle, SubtitleTv)
    @Composable
    fun title(): TextUnit = adjustedSize(Title, TitleTv)
    @Composable
    fun display(): TextUnit = adjustedSize(Display, DisplayTv)
    @Composable
    fun displayLarge(): TextUnit = adjustedSize(DisplayLarge, DisplayLargeTv)
}

/** 根据设备与用户全局字体调整量，计算最终字号 */
@Composable
private fun adjustedSize(phone: TextUnit, tv: TextUnit): TextUnit {
    val adjustment = LocalFontAdjustment.current
    val base = if (LocalPhoneCompact.current) phone else tv
    return if (adjustment == 0) base else (base.value + adjustment).sp
}

/**
 * 按钮文字颜色系统
 *
 * 统一管理按钮在不同状态下的文字颜色：
 * - 默认（未选中）：TextPrimary，确保可读性
 * - 聚焦：Primary，视觉反馈
 * - 按下：Primary，与聚焦一致
 * - 禁用：TextSecondary，降低视觉权重
 */
object ButtonColors {
    /** 默认（未选中）文字颜色 */
    val DefaultContent: Color
        @Composable @ReadOnlyComposable get() = NasMusicColors.TextPrimary

    /** 聚焦时文字颜色 */
    val FocusedContent: Color
        @Composable @ReadOnlyComposable get() = NasMusicColors.Primary

    /** 按下时文字颜色 */
    val PressedContent: Color
        @Composable @ReadOnlyComposable get() = NasMusicColors.Primary

    /** 禁用时文字颜色 */
    val DisabledContent: Color
        @Composable @ReadOnlyComposable get() = NasMusicColors.TextSecondary
}

/**
 * 类型系统
 */
private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle(
        fontSize = FontSize.Display,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = TextStyle(
        fontSize = FontSize.Title,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = FontSize.Subtitle,
        fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
        fontSize = FontSize.Subtitle,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = FontSize.Button,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = FontSize.Button,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Normal
    )
)

private val AppShapes = Shapes(
    extraSmall = ShapeDefaults.ExtraSmall,
    small = ShapeDefaults.Small,
    medium = ShapeDefaults.Medium,
    large = ShapeDefaults.Large,
    extraLarge = ShapeDefaults.ExtraLarge
)

/**
 * 调色板 → TV M3 ColorScheme。
 *
 * 业务代码不读 `MaterialTheme.colorScheme`（自定义 token 才是唯一真源），
 * 这里只为 TV M3 组件（Dialog / Surface 等）提供一套与 palette 一致的角色色。
 */
private fun colorSchemeFor(palette: NasPalette): ColorScheme {
    val scheme = if (palette.isLight) lightColorScheme() else darkColorScheme()
    return scheme.copy(
        primary = palette.primary,
        onPrimary = palette.onPrimary,
        secondary = palette.secondary,
        onSecondary = palette.onPrimary,
        background = palette.background,
        onBackground = palette.textPrimary,
        surface = palette.surface,
        onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceVariant,
        onSurfaceVariant = palette.textSecondary,
        error = palette.danger,
        onError = palette.onDanger,
    )
}

@Composable
fun NASMusicTVTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) NasPalettes.Dark else NasPalettes.Light
    val colorScheme = remember(palette) { colorSchemeFor(palette) }
    val lyricsTheme = remember(palette) { lyricsThemeData(palette) }
    // Task 12: 检测系统高对比度模式
    // L15 修复（2026-10-06，代码审查报告 §5）：旧实现用 `uiMode and 0x20000` 掩码判断，
    // 但 0x20000 不是任何 Android 高对比度标志位（UI_MODE_NIGHT_MASK=0x30）⇒ 恒为 false，
    // 4 个消费分支（AlbumGrid/ArtistList）整条从未生效；且 remember 包裹不随配置变化重组。
    // 修复：读 Settings.Secure 的 high_text_contrast_enabled（API 21+，minSdk 22 兼容；
    // AccessibilityManager.isHighTextContrastTextEnabled 是 API 36+ 不可用），
    // 并注册 ContentObserver 监听系统开关变化 ⇒ 状态变化时自动重组。
    val context = androidx.compose.ui.platform.LocalContext.current
    var isHighContrast by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        fun readFlag(): Boolean = android.provider.Settings.Secure.getInt(
            context.contentResolver,
            "high_text_contrast_enabled", 0
        ) == 1
        isHighContrast = readFlag()
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                isHighContrast = readFlag()
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor("high_text_contrast_enabled"),
            false, observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(
        LocalNasPalette provides palette,
        LocalLyricsTheme provides lyricsTheme,
        LocalHighContrast provides isHighContrast
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

/**
 * 歌词相关主题（composable getter，跟随当前调色板）。
 *
 * 实际消费走 [LocalLyricsTheme]（由主题在组合期构造）；本对象保留给
 * 需要「按当前主题取单个样式」的调用点。
 */
object LyricsTheme {
    val currentLine: TextStyle
        @Composable @ReadOnlyComposable get() = lyricsThemeData(LocalNasPalette.current).currentLine
    val normalLine: TextStyle
        @Composable @ReadOnlyComposable get() = lyricsThemeData(LocalNasPalette.current).normalLine
    val dimLine: TextStyle
        @Composable @ReadOnlyComposable get() = lyricsThemeData(LocalNasPalette.current).dimLine
}

/** 歌词主题容器（TV/手机自动切换） */
data class LyricsThemeData(
    val currentLine: TextStyle,
    val normalLine: TextStyle,
    val dimLine: TextStyle
)

/** 由调色板构造歌词样式（纯函数，可供 CompositionLocal 默认值使用） */
private fun lyricsThemeData(palette: NasPalette): LyricsThemeData = LyricsThemeData(
    currentLine = TextStyle(
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.SemiBold,
        color = palette.primary
    ),
    normalLine = TextStyle(
        fontSize = FontSize.Display,
        fontWeight = FontWeight.Normal,
        color = palette.textPrimary
    ),
    dimLine = TextStyle(
        fontSize = FontSize.Title,
        fontWeight = FontWeight.Normal,
        color = palette.textSecondary
    )
)

/** CompositionLocal：提供当前设备对应的歌词主题 */
val LocalLyricsTheme = staticCompositionLocalOf { lyricsThemeData(NasPalettes.Dark) }

/**
 * 手机端紧凑 UI 尺度
 *
 * 1080p 手机横屏下整体字号/组件占位过大。通过全局 LocalDensity 缩放实现"一键紧凑"，
 * TV（大屏）不应用。
 *
 * - [PHONE_UI_SCALE]：手机端密度缩放系数（字号 sp 与尺寸 dp 同步缩小）
 * - [LocalPhoneCompact]：标记当前是否为"手机紧凑模式"，供歌词等例外组件恢复原始密度
 */
object CompactSizes {
    const val PHONE_UI_SCALE = 0.82f

    /** 歌词恢复系数 = 1 / PHONE_UI_SCALE */
    const val LYRICS_RECOVER_SCALE = 1.0f / PHONE_UI_SCALE
}

/** 当前是否处于手机紧凑模式（MainActivity 按设备类型提供） */
val LocalPhoneCompact = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 页面级内边距（**唯一口径**，`docs/conventions-adaptive-ui.md` §3.1）。
 *
 * ⛔ 新增/改造页面**一律**走本函数，不要在自己文件里再抄一遍
 * `if (isPhoneLandscape) 16.dp else if (isPhonePortrait) 16.dp else 32.dp`
 * —— 这个三元表达式在 v2.39.18 前被手抄了 6 遍，Play 页与首页各漏改一次，
 * 是「同一页面在不同形态下边距不一致」的机制性来源。
 *
 * | 形态 | 横向 | 纵向 | 依据 |
 * |---|---|---|---|
 * | [UiMode.TV] | 32dp | 20dp | 大屏留白，1080p 下占屏高 ~4% |
 * | [UiMode.PhoneLandscape] | 16dp | 0dp | 可用高仅约 338 Compose dp，纵向不再吃 |
 * | [UiMode.PhonePortrait] | 16dp | 12dp | 窄屏 439 Compose dp，竖向靠滚动 |
 *
 * ⚠️ 纵向 0dp 是**刻意**的（2026-09-22 `d0d732b` 两轮真机反馈的结论：12dp 无感，
 * 要求干脆去掉），不是漏写。播放页是唯一例外（纵向 4/8dp），见 `NowPlayingScreen`。
 *
 * ⚠️ 返回 [PaddingValues] 而非 `Modifier`：调用方需要能对单边再叠加（如 `Row`
 * 里左卡片自己再 `.padding(end = 24.dp)`），包成 Modifier 会挡住后续 `.padding(...)`。
 *
 * @param horizontalScale 横向缩放（默认 1）；需要「比页面基线更宽/更窄」的页面传入
 * @param vertical 纵向覆盖（`null` = 按形态表取默认）；播放页那种定制纵向的页面用
 * @param uniform `true` = 纵向跟横向取同一个值（四边等距）。用于**整页就是一个带背景卡片行**
 *        的页面（队列 / 设置 / 我的）—— 它们的设计本就是四边等距，套用上下不同值的
 *        基线会让卡片贴顶。⛔ 只为兼容既有观感而留，新页面不要用。
 */
@Composable
fun pagePadding(
    horizontalScale: Float = 1f,
    vertical: androidx.compose.ui.unit.Dp? = null,
    uniform: Boolean = false,
): androidx.compose.foundation.layout.PaddingValues {
    val mode = LocalUiMode.current
    val base = when (mode) {
        UiMode.TV -> 32.dp
        UiMode.PhoneLandscape -> 16.dp
        UiMode.PhonePortrait -> 16.dp
    }
    val v = vertical ?: when {
        uniform -> base
        mode == UiMode.TV -> 20.dp
        mode == UiMode.PhoneLandscape -> 0.dp
        else -> 12.dp
    }
    return androidx.compose.foundation.layout.PaddingValues(
        start = base * horizontalScale,
        end = base * horizontalScale,
        top = v,
        bottom = v,
    )
}

/**
 * Task 12: 高对比度模式
 *
 * 读取 Settings.Secure.high_text_contrast_enabled（L15 修复，2026-10-06：
 * 旧注释引用的 `UI_MODE_MASK_HIGH_CONTRAST` 常量并不存在，原掩码实现恒为 false），
 * 提供 CompositionLocal 供组件使用。
 * 高对比度模式下：分割线加粗、焦点边框加宽、文字对比增强。
 */
// L18：用 `compositionLocalOf` 而不是 `staticCompositionLocalOf` ——
// 后者不跟踪读取者，值一变就要**整棵子树**重组（这里是整个主题 content）；
// 高对比度开关会随设置页切换，代价直接体现为「打开开关整屏闪一下」。
// 本值变化频率极低、读取者也不多，动态版本的下沉失效范围更划算。
val LocalHighContrast = androidx.compose.runtime.compositionLocalOf { false }

/**
 * 高对比度模式下的颜色增强（composable getter，随当前调色板）。
 */
object HighContrastColors {
    /** 加强的主色 — 取 primaryEmphasis */
    val PrimaryBright: Color
        @Composable @ReadOnlyComposable get() = NasMusicColors.PrimaryBright
    /** 加强的文字色 — 深色下纯白，浅色下纯黑 */
    val TextPrimary: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalNasPalette.current.isLight) Color(0xFF000000) else Color(0xFFFFFFFF)
    /** 加强的分割线色 — 比 Border 深一档 */
    val BorderStrong: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalNasPalette.current.isLight) Color(0xFF94A3B8) else Color(0xFF4A5A72)
}
