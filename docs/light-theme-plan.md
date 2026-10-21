# 浅色主题开发计划（跟随系统 / 深色 / 浅色 三态）

> 状态：已实施（2026-10-21，代码与门禁完成，待真机验收）
> 所有者裁决：见 §10，共 6 条；两项评审项已拍板（§3.6 全部接受 / §3.1 取 `#F3F5F9`）
> 实施偏差记录：见 §12
> 关联源码：[Theme.kt](../app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt)、[AppPreferences.kt](../app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt)、[GeneralSettingsSection.kt](../app/src/main/java/com/nasmusic/tv/ui/screens/settings/GeneralSettingsSection.kt)、[MainActivity.kt](../app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt)、[AppRoot.kt](../app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt)

## 0. 结论

- **机制**：新增 `NasPalette` + `LocalNasPalette`；`NasMusicColors` 的全部属性改成 **@Composable getter 垫片**，1228 处业务调用点**一行不改**即自动跟随主题。
- **开关**：布尔 `settings_dark_theme` 升级为三态 `ThemeMode`（SYSTEM / DARK / LIGHT），默认 `SYSTEM`；旧值无损迁移，备份 JSON 双向兼容。
- **配色**：以品牌青绿 `#2DD4BF` 为基准派生浅色档；所有文字对（含新增 `onPrimary` 等语义容器色）满足 WCAG AA，数值由单测再生。
- **例外**：`VisualizerStage`（频谱舞台，**含其内的照片墙模式**）用 `ForceDarkPalette` 强制深色；其余页面全部支持浅色。
- **深色回归面**：深色 token 全部保留现有 hex。唯一有意的深色可见变化是 §3.6 的「语义容器前景修正」（约 12 处），需评审拍板。

## 1. 现状核查（均已对源码验证）

| # | 事实 | 证据 | 影响 |
|---|---|---|---|
| F1 | 已有 `LightColorScheme` 草稿，且 `NASMusicTVTheme` 会按 `darkTheme` 选中它 | `Theme.kt:206-213`、`Theme.kt:272`、`MainActivity.kt:298` | 切换链路已存在，缺的是消费方 |
| F2 | **`MaterialTheme.colorScheme` 只有 Theme.kt 自己引用，业务界面 0 消费** | 全仓 grep：除 `Theme.kt:272/310` 外无命中 | 现有「暗色主题」开关对界面**视觉上等于空转** |
| F3 | 业务界面直接读静态常量 `NasMusicColors.*` | **1228 处 / 85 个文件**（不含 `ui/theme/`）；含 theme 包共 1247 处 / 87 文件 | 不改读写方式，浅色永远无法生效 |
| F4 | `ui/` 下另有大量硬编码颜色 | **129 处 / 31 个文件**（不含 `ui/theme/`）：`Color(0xFF…)`、`Color.White/Black` | 需分类：主题色 / 内容遮罩色，不能一刀切 |
| F5 | 主色作实底时前景不统一 | 28 处 `containerColor = NasMusicColors.Primary`，其中 **9 处实底**；8 处配 `TextPrimary`（深色下仅 1.58:1） | 浅色下会变成「近黑字压深青底」≈3.26:1，必须引入 `onPrimary` |
| F6 | 语义色也有作实底的用法 | `SettingsScreen.kt:1119`（Warning 底 + TextPrimary）；`ServerConnectScreen.kt:326`（Danger 底 + White） | 需要 `onWarning` / `onDanger` |
| F7 | 高对比度模式把 `TextPrimary` 硬编码成纯白 | `Theme.kt:391` | 浅色下高对比度会把文字变成「白字压白底」 |
| F8 | 冷启动窗口背景是深色 | `themes.xml:4` → `@color/bg_primary` = `#0c1222`（`colors.xml:4`） | 浅色主题冷启动会闪一次深底 |
| F9 | 照片墙的「主题色过渡」直接引用静态品牌色 | `FadeP1Transitions.kt:54/67`，且在 `DrawScope` 里 | 改垫片后这里编译不过；照片墙已裁决为强制深色，可直接读 `NasPalettes.Dark` |
| F10 | `AppSettings` 会进备份 JSON，Gson **绕过 Kotlin 非空检查** | `AppPreferences.kt:2043`（导入）、`BackupGson.kt:35`（坑的完整说明） | 新增 `themeMode` 字段必须可空处理，否则老备份导入 NPE |
| F11 | `FocusableSurface` 的默认色来自 `ButtonColors` / `NasMusicColors` | `FocusableSurface.kt:115-116` 及同函数默认参数 | 它是 `@Composable fun`，默认参数里调用 composable getter 合法（与 `MaterialTheme.colorScheme` 同模式） |
| F12 | 频谱舞台是独立全屏层，仅 `AppRoot` 一处入口 | `AppRoot.kt:611` | 强制深色只需包一层，改动面小 |

## 2. 机制选型

### 2.1 候选对比

| 方案 | 做法 | 改动面 | 结论 |
|---|---|---|---|
| **A. 垫片（选定）** | `NasMusicColors` 属性改为 composable getter，内部读 `LocalNasPalette` | 主题包 ~250 行 + 编译器枚举出的非组合读取点（预估 20~40 处） | 选中：1228 处业务代码零改动；深色可保持逐字节一致（§3.6 除外） |
| B. 显式改名 | 机械替换 `NasMusicColors.X` → `NasTheme.colors.x` | 85 文件 / 1228 处 + 同样的非组合修点 | 否决：diff 不可评审、合并冲突大，且**不能减少**最难的 DrawScope/remember 上提工作 |
| C. 改用 `MaterialTheme.colorScheme` | 全部映射到 M3 角色 | — | 否决：`FocusRing`/`AccentGlow`/`PrimaryBright` 等自定义 token 在 TV M3 角色里没有对应项；语义会被强行扭曲 |

**选 A**。它与 Compose 官方 `MaterialTheme.colorScheme` 是同一模式（composable getter），不是 hack；主题切换时 `staticCompositionLocalOf` 触发整棵子树重组一次，仅在用户改设置时发生。

### 2.2 核心骨架

```kotlin
@Immutable
data class NasPalette(
    val isLight: Boolean,
    val background: Color, val surface: Color, val surfaceVariant: Color,
    val primary: Color, val onPrimary: Color, val secondary: Color,
    val textPrimary: Color, val textSecondary: Color, val border: Color,
    val danger: Color, val onDanger: Color,
    val warning: Color, val onWarning: Color,
    val success: Color,
    val focusRing: Color, val accentGlow: Color, val accentGlowStrong: Color,
    val primaryEmphasis: Color,   // 现 PrimaryBright
)

object NasPalettes { val Dark = NasPalette(…); val Light = NasPalette(…) }

val LocalNasPalette = staticCompositionLocalOf { NasPalettes.Dark }

object NasMusicColors {                       // ← 名字与属性名全部保留
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalNasPalette.current.background
    // …其余 14 项同形（17 项中删除 PrimaryVariant / TextBrightHighlight）
}
```

`NASMusicTVTheme` 负责：选 palette → 提供 `LocalNasPalette` → 由 palette 生成**完整** `darkColorScheme` / `lightColorScheme`（不只现有 7 个角色，补齐 `border`、`error`、`onError`、`scrim` 等；`onSecondary` 等没有独立浅/深语义的角色由 `TextPrimary` 映射）→ 继续提供 `LocalLyricsTheme` / `LocalHighContrast`。

注意：`LocalLyricsTheme` 的 `staticCompositionLocalOf` 默认值必须是一个**固定的深色** `LyricsThemeData`（该默认值在非组合上下文求值，不能调用 composable getter）；实际值仍由 `NASMusicTVTheme` 在组合期按当前 palette 构造。

### 2.3 非组合上下文的处理规则（编译器兜底）

改成 composable getter 后，以下位置会**编译失败**，必须按规则处理：

| 位置 | 规则 | 已知点 |
|---|---|---|
| `remember { … }`、`derivedStateOf` | 先在组合作用域读出 `val c = NasMusicColors.X`，把 `c` 作为 key/输入 | `PlayHeatmapChart.kt:65`（颜色列表被 `remember` 无 key 缓存） |
| `Canvas {}` / `drawBehind {}` / `drawWithContent {}` | 把颜色上提到 `@Composable` 作用域再进 lambda | `UnifiedSongRow.kt:574`；`VisualizerStage` 内 1 处 |
| `DrawScope` 类 / 非 Composable 类 | 强制深色区直接用 `NasPalettes.Dark.primary`；其余把颜色作构造参数传入 | `FadeP1Transitions.kt:67`（照片墙，强制深色） |
| 顶层 `val` / `object` 初始化 | 改为 composable getter 或改由组合期构造 | `NasMusicBrushes`、`LyricsTheme`、`ButtonColors`、`HighContrastColors` |

**硬规则**：`NasPalettes.Dark/Light` 只允许在 `ui/theme/` 内、强制深色包装内、以及纯绘制类（由调用方注入）出现；业务界面一律走 `NasMusicColors.*` / `LocalNasPalette`。这条规则由代码评审保证——编译器已经把「在非组合上下文偷偷读主题」变成硬错误。

### 2.4 明确不做（YAGNI）

- 不做动态取色（Monet / `dynamicColorScheme`）：TV 与 API 22 均不成立。
- 不做单页主题覆盖设置：唯一例外是代码内的 `ForceDarkPalette`（频谱舞台），不暴露给用户。
- 不做主题自定义色板 / 强调色选择。
- 不把 `MaterialTheme.colorScheme` 作为业务读色入口：自定义 token 才是唯一真源。

## 3. 配色方案

### 3.1 Token 表（深色 = 现有值冻结）

| Token | 深色（保留） | 浅色（新增） | 用途 |
|---|---|---|---|
| Background | `#0C1222` | `#F3F5F9` | 页面底色 |
| Surface | `#162032` | `#FFFFFF` | 卡片 / 对话框 / 输入框 |
| SurfaceVariant | `#1E2D42` | `#E7ECF3` | hover / 选中 / 次级填充 |
| Primary | `#2DD4BF` | `#0F766E` | 主色文字、描边、进度、焦点 |
| PrimaryBright → primaryEmphasis | `#5EEAD4` | `#115E59` | 主按钮聚焦强调（浅色下取更深一档） |
| Secondary | `#60A5FA` | `#1D4ED8` | 渐变次色、次级强调 |
| TextPrimary | `#E8EDF5` | `#0F172A` | 主文字 |
| TextSecondary | `#8899B0` | `#475569` | 次要文字 |
| Border | `#2A3A52` | `#CBD5E1` | 分隔线 / 卡片描边 |
| Danger | `#F87171` | `#B91C1C` | 错误 / 删除（文字或容器） |
| Warning | `#FBBF24` | `#B45309` | 警告 |
| Success | `#34D399` | `#047857` | 成功 / 已连接 |
| FocusRing | `#2DD4BF` | `#0F766E` | 焦点环 |
| AccentGlow | `0x262DD4BF` | `0x1F0F766E` | 弱辉光 |
| AccentGlowStrong | `0x4D2DD4BF` | `0x330F766E` | 强辉光 |
| **onPrimary**（新） | `#0C1222` | `#FFFFFF` | 主色实底上的前景 |
| **onDanger**（新） | `#0C1222` | `#FFFFFF` | 危险色实底上的前景 |
| **onWarning**（新） | `#0C1222` | `#FFFFFF` | 警告色实底上的前景 |

> `PrimaryVariant` 与 `TextBrightHighlight` 当前全仓 0 消费（仅声明），本次一并删除，不留死 token。

### 3.2 语义容器规则（浅色下最容易翻车的一类）

**任何 `containerColor = 语义色` 的 `FocusableSurface`，其 `contentColor` / `focusedContentColor` / 内部 `Text.color` 必须用对应的 `onX`，不得用 `TextPrimary` 或 `Color.White/Black`。**

必改清单（已核对）：

| 文件 | 行 | 现状 | 改法 |
|---|---|---|---|
| `ExportDeviceDialog.kt` | 122 | Primary 底 + `Color.Black` | `onPrimary` |
| `BackupTransferDialog.kt` | 200 | Primary 底 + TextPrimary | `onPrimary` |
| `ModelTransferDialog.kt` | 224 | Primary 底 + TextPrimary | `onPrimary` |
| `BaiduAuthDialog.kt` | 197 | Primary 底 + TextPrimary | `onPrimary` |
| `PlaylistImportUploadDialog.kt` | 198 | Primary 底 + TextPrimary | `onPrimary` |
| `BrowseComponents.kt` | 240 | Primary 底 + TextPrimary | `onPrimary` |
| `KaraokePlaybackScreen.kt` | 931 | Primary 底 + TextPrimary | `onPrimary` |
| `ServerConnectScreen.kt` | 523 / 738 | Primary 底 + TextPrimary | `onPrimary` |
| `ServerConnectScreen.kt` | 326 | Danger 底 + White | `onDanger` |
| `QueueScreen.kt` | 566 | 聚焦态 Danger 底 + White | `onDanger` |
| `SettingsScreen.kt` | 1119 | Warning 底 + TextPrimary | `onWarning` |

`Primary.copy(alpha = 0.2f/0.3f)` 这类**淡洗底**不在此列：前景继续用 `Primary`/`TextPrimary` 即可；但 `alpha ≥ 0.8` 的聚焦底在浅色下需要实测（见 §6 走查项 G-03）。

### 3.3 对比度门槛与实测值（浅色）

门槛：正文 ≥ 4.5:1；大字号（≥18pt / 14pt 粗）与非文本控件 ≥ 3:1。

| 组合 | 浅色实测 | 结果 |
|---|---|---|
| TextPrimary / Background | 16.36:1 | 通过 |
| TextPrimary / Surface | 17.85:1 | 通过 |
| TextSecondary / Background | 6.94:1 | 通过 |
| TextSecondary / Surface | 7.58:1 | 通过 |
| TextSecondary / SurfaceVariant | 6.38:1 | 通过 |
| Primary / Background | 5.01:1 | 通过 |
| Primary / Surface | 5.47:1 | 通过 |
| Primary / SurfaceVariant | 4.61:1 | 通过 |
| onPrimary / Primary | 5.47:1 | 通过 |
| Secondary / Surface | 6.70:1 | 通过 |
| Danger / Surface | 6.47:1 | 通过 |
| Warning / Surface | 5.02:1 | 通过 |
| Success / Surface | 5.48:1 | 通过 |
| FocusRing / Background（非文本） | 5.01:1 | 通过 |
| Border / Background（装饰线，不设门槛） | 1.36:1 | — 与深色 1.62 同量级 |

深色侧不在 §3.6 清单内的其它既有低对比问题**不在本次范围**（避免范围蔓延）；清单内的 12 处借 `onX` 一并修正。

### 3.4 渐变与遮罩

- `NasMusicBrushes` 全部改为 composable getter，颜色从当前 palette 派生；`progressBar`（Primary→Secondary）、`coverGlow`、`overlayGradient` 自动跟随。
- `topFadeMask` / `bottomFadeMask` 目前硬编码 `0xCC0C1222`，改为「当前 Background + 0.8 alpha」派生 ⇒ 浅色下是浅色渐隐，深色下与现状逐字节一致。
- **新增 `NasOverlayColors`（不随主题变）**：内容遮罩层（封面/视频上的黑纱与白字）不跟主题走，否则浅色主题会把「白字压作品图」变成不可读。清单收敛为 4 个常量：`ArtworkScrimStrong`、`ArtworkScrimSoft`、`OnArtwork`（白）、`OnArtworkSecondary`（白 70%）。§5 的光学走查把 `Color.Black/White` 逐处归类到「主题 token」或「Overlay 常量」。

### 3.5 高对比度模式

`HighContrastColors` 改为 composable getter：

- `TextPrimary`：深色 `#FFFFFF` 不变；浅色 `#000000`（不能再用纯白）。
- `PrimaryBright`：直接取 `primaryEmphasis`。
- `BorderStrong`：深色 `#4A5A72` 不变；浅色 `#94A3B8`（比 Border 深一档）。

### 3.6 需要评审的深色可见变化（唯一非零回归面）

深色 hex 全部冻结，但语义容器前景必须统一到 `onX`，否则浅色不可用。收敛后深色会出现 12 处**颜色变化**（对比度全部提升）：

| 类别 | 处数 | 变化 | 深色对比 |
|---|---|---|---|
| Primary 实底按钮前景 | 9 | `#E8EDF5` / `#000000` → `#0C1222` | 1.58:1 → **10.03:1** |
| Warning 实底按钮前景 | 1 | `#E8EDF5` → `#0C1222` | 1.42:1 → **11.18:1** |
| Danger 实底按钮前景 | 2 | `#FFFFFF` → `#0C1222` | 2.77:1 → **6.75:1** |

替代方案（若要求深色零视觉变化）：`onX` 的深色值改为「各处现状值」，浅色单列。副作用是 token 语义分裂、且深色原有低对比被永久固化。**推荐接受上表修正**，在评审时拍板。

## 4. 三态开关

### 4.1 模型与解析（纯函数，可单测）

```kotlin
enum class ThemeMode { SYSTEM, DARK, LIGHT }   // 存储 key: "system" / "dark" / "light"

/** 存储值 + 旧布尔值 → 有效模式（迁移与备份导入共用同一真源） */
fun resolveThemeMode(stored: ThemeMode?, legacyDark: Boolean?): ThemeMode =
    stored ?: when (legacyDark) { true -> DARK; false -> LIGHT; null -> SYSTEM }

/** 模式 + 系统 + 设备 → 是否深色；TV 的 SYSTEM 固定解析为深色 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean, isTv: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> if (isTv) true else systemDark
}
```

| mode | 手机 | 电视 |
|---|---|---|
| SYSTEM | 跟随系统 `isSystemInDarkTheme()` | **深色**（系统 night-mode 上报不可靠） |
| DARK | 深色 | 深色 |
| LIGHT | 浅色 | 浅色 |

### 4.2 偏好迁移

| 项 | 值 |
|---|---|
| 新键 | `settings_theme_mode`（String） |
| 旧键 | `settings_dark_theme`（Boolean；保留用于迁移，写入时同步维护以兼容降级） |
| 读取 | `resolveThemeMode(ThemeMode.fromKey(prefs[新键]), prefs[旧键])` |
| 写入 | 写新键；**同时**写旧键 `mode != LIGHT`（SYSTEM 落 true），保证降级安装旧 APK 时不突变 |
| 默认 | 新装与从未改过的用户 → `SYSTEM`（所有者裁决） |
| 副作用 | 手机系统为浅色时，老用户升级后会从深色变浅色；显式选过深/浅色的用户保持原选择 |

### 4.3 备份 JSON 兼容

`AppSettings`（备份 DTO）改动：

- 保留字段 `darkTheme: Boolean`（Gson 字段名不能变），导出时写**当前解析结果** `!= LIGHT`。
- 新增 `themeMode: ThemeMode = SYSTEM`。
- **导入**：`themeMode` 缺失/为 null 时回落 `resolveThemeMode(null, settings.darkTheme)`；严禁直接 `.name`（`BackupGson.kt:35` 记录过整份备份导入失败的 NPE）。
- 容错枚举适配器已认不出旧名时回落首个常量；`ThemeMode` 声明顺序定为 `SYSTEM, DARK, LIGHT`，兜底即 SYSTEM。
- 新增单测：旧格式 JSON（只有 `darkTheme`）→ 新模式正确；新格式 → 旧布尔同步；未知 `themeMode` 字符串 → SYSTEM。

### 4.4 设置 UI

- 位置不变：设置 → 通用 →「外观与动效」卡片。
- 控件：沿用同卡内「语言」「屏幕方向」的三枚 `SettingsChip` 模式，替换现有 `SettingSwitch`（`GeneralSettingsSection.kt:76`）。
- 文案（中英镜像，`LanguageMirrorTest` 会强制）：
  - `settings_theme_mode` = 主题模式
  - `settings_theme_desc` = 跟随系统时手机自动切换，电视固定深色（20 字，≤26 字上限）
  - `settings_theme_system` = 跟随系统 / `settings_theme_dark` = 深色 / `settings_theme_light` = 浅色
- 删除 `settings_dark_theme` / `settings_dark_theme_desc`（两语言文件同步删）。

### 4.5 系统栏与启动窗口

- 手机竖屏显示系统栏时（`MainActivity.kt:269-280`）：补 `isAppearanceLightStatusBars = !dark`，API 26+ 再补 `isAppearanceLightNavigationBars = !dark`（minSdk 22，需版本守卫；`WindowInsetsControllerCompat` 在低版本安全降级）。该 `LaunchedEffect` 的 key 必须加上 `dark`，否则在系统栏可见时切换主题，图标颜色不会更新。
- 主题解析在组合期完成：`val systemDark = isSystemInDarkTheme()`，再 `resolveDarkTheme(mode, systemDark, isTv)`；`isTv` 已在 `MainActivity.kt:178` 计算，不要重复探测。
- 冷启动：给 `AppPreferences` 增加 `getThemeModeSync()`（复用 `cachedScreenOrientation` 同款 `@Volatile` 镜像），`onCreate` 里按 `resolveDarkTheme(mode, systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES, isTv)` 设置 `window.setBackgroundDrawable`，避免浅色主题闪深底（`themes.xml:4`）。
- 启动窗口无法做到「零闪」的兜底：保持 XML 深色底不变，仅覆盖已解析为浅色的场景。

## 5. 分批实施

> 原则：每批都能编译 + 跑单测；P0 结束后先做一次**深色截图对比**，确认零回归再进入浅色走查。

### P0 主题地基（代码量约 300 行 + 偏好/设置改动）

1. `Theme.kt`：新增 `NasPalette` / `NasPalettes` / `LocalNasPalette` / `ForceDarkPalette`；`NasMusicColors`、`NasMusicBrushes`、`LyricsTheme`、`ButtonColors`、`HighContrastColors` 改 composable getter；palette → 完整 `ColorScheme` 映射。
2. 新增 `ThemeMode.kt`（枚举 + 两个纯函数）；`AppSettings` 加字段；`AppPreferences` 加新键、迁移读取、`setThemeMode`、`getThemeModeSync`、备份导入导出兼容。
3. `MainActivity`：`resolveDarkTheme` 接线 + 系统栏图标 + 启动窗背景。
4. `GeneralSettingsSection`：开关 → 三 chips；`MainViewModel` 动作改名（`onToggleDarkTheme` → `onChangeThemeMode`）；strings 中英。
5. `AppRoot.kt:611` 用 `ForceDarkPalette { VisualizerStage(…) }` 包裹。
6. 删除 `Color.kt` 的 `Accent`（`EqualizerScreen` 3 处改用 `NasMusicColors.Primary`），删除 `PrimaryVariant` / `TextBrightHighlight`。

**出口条件**：`assembleDebug` + `lintDebug` 通过；深色下 Home / 曲库 / 设置 / NowPlaying 四屏截图与基线一致（§3.6 的 12 处除外）。

### P1 编译收敛（非组合上下文）

- 按 §2.3 规则清掉所有编译错误；预估 20~40 处。
- 重点文件：`PlayHeatmapChart.kt:65`、`UnifiedSongRow.kt:574`、`FadeP1Transitions.kt:67`、`VisualizerStage.kt`。
- `remember` 缓存颜色的地方把 palette 作为 key。

**出口条件**：全量编译通过；`FadeP1Transitions` 在强制深色下视觉不变。

### P2 光学走查 A：导航 / 列表 / 设置 / 对话框（浅色）

- Home、Library（含 browse / 三个 tab）、Mine、Queue、Settings 全套、所有 Dialog。
- 修 §3.2 的 12 处语义容器；处理 `Surface` 白卡在浅底上的层级（必要时加 Border）。
- 空态 / 错误态 / 加载态（`ListStateIndicators`、`Shimmer`）。

### P3 光学走查 B：播放 / 歌词 / 统计 / 媒体

- Now Playing（含 immersive 模式）、`PlayerControls`、`LyricsView`、`VolumeControl`、`SongInfoPanel`。
- `NowPlayingScreen.kt` 39 处硬编码色逐处归类：模糊封面背景改用 `overlayGradient`（主题底），封面上的文字遮罩改用 `NasOverlayColors`。
- 统计页热力图（`remember` 颜色列表）、专辑/艺术家/歌单详情、网盘、百度授权、天气电台、MV。

### P4 沉浸页与强制深色

- K 歌：浅色主题下的歌词/控件对比，背景层按 `NasOverlayColors` 处理。
- MV：**视频区域恒为黑**，仅外框/控件跟随主题。
- 频谱舞台 + 照片墙：`ForceDarkPalette` 生效验证；照片墙过渡色改用 `NasPalettes.Dark.primary`。

### P5 真机验收与文档

- 手机 + TV 双端双主题截图套件（§8）。
- `docs/technical-overview.md` 新增 §10.N；`CHANGELOG.md` 当前版本段加一行；`README.md` 功能列表加「浅色 / 深色 / 跟随系统主题」。
- 按归档规则评估本文件去向（发布后 `git mv` 到 `docs/archive/` 并同步引用）。

## 6. 逐页光学走查清单（浅色）

| 组 | 页面 | 重点风险 |
|---|---|---|
| G-01 导航 | Home / Library / Mine / PhoneTopBar / PhoneNavBar / MiniPlayer | 白卡贴浅底发平；焦点环可见性 |
| G-02 曲库 | BrowseComponents / SongList / AlbumGrid / ArtistList / GenreYearLists / 三个 library tab | 选中态 `Primary.copy(alpha≥0.8)` 上的白字 |
| G-03 详情 | Album / Artist / Playlist / PlayStats | 头部大图压字（Overlay 常量）；热力图色阶 |
| G-04 播放 | NowPlaying / PlayerControls / LyricsView / Queue / VolumeControl / SongInfoPanel | 模糊封面背景的 scrim；immersive 黑底；歌词渐隐 mask |
| G-05 设置 | SettingsScreen + 9 个 section + 所有 Dialog | Warning/Danger 实底；输入框边框；开关/芯片 |
| G-06 媒体 | Netdisk / BaiduAuth / WeatherRadio / MV / TextInputDialog / ServerConnect | 高密度文字页（ServerConnect 50 处引用） |
| G-07 沉浸 | Karaoke（浅色）/ Visualizer + 照片墙（强制深色） | 强制深色包裹是否完整；K 歌浅色歌词对比 |

逐屏检查项（每屏都过）：

1. 任一文字与背景 ≥ 4.5:1；大字号/图标 ≥ 3:1（可用脚本对截图取样验证，禁止目测“差不多”）。
2. 焦点环在静止底与聚焦底上都可见。
3. 无「白字压浅底」「深字压深青底」。
4. 卡片/对话框与页面底有可见边界（Border 或 SurfaceVariant 差异）。
5. 封面/视频上的白字仍由 `NasOverlayColors` 提供，未被主题清洗。
6. 空态、错误态、加载骨架三态均有配色。

## 7. 门禁与单测

| 门禁 | 位置 | 内容 |
|---|---|---|
| `ThemeContrastTest` | `app/src/test/java/com/nasmusic/tv/ui/theme/` | 深/浅两套 palette 的全部「文字对 + 语义容器对」按 WCAG 阈值断言；失败时打印完整对比度表（数值再生源） |
| `ThemeModeTest` | 同上 | `resolveThemeMode` 与 `resolveDarkTheme` 全矩阵：3 模式 × 手机/电视 × 系统深/浅；旧布尔迁移 3 态 |
| `ThemeBackupCompatTest` | `data/prefs` | 旧 JSON（仅 `darkTheme`）导入 → 正确模式；新 JSON → 旧布尔同步；未知枚举名 → SYSTEM；字段缺失不 NPE |
| `LanguageMirrorTest` | 已有 | 新增 5 条文案必须在 `values-en` 同步存在（自动生效） |
| 构建 | — | `assembleDebug` + `lintDebug` + `testDebugUnitTest`（`--no-daemon -Pkotlin.compiler.execution.strategy=in-process`） |

不做截图基线自动化：本项目的真机验收按既有约定由所有者执行、由代理判读截图；不引入像素 diff 工具。

## 8. 真机验收

设备：手机（Redmi）+ 电视（创维，API 22）。

| 场景 | 步骤 | 通过标准 |
|---|---|---|
| 三态切换 | 设置里依次选 跟随系统 / 深色 / 浅色 | 即时生效、无需重启；重进设置保持选择 |
| 系统跟随（手机） | 选 SYSTEM，切换系统深/浅色 | 应用跟随；TV 不受影响（恒深色） |
| TV 跟随 | TV 选 SYSTEM | 解析为深色 |
| 冷启动 | 浅色模式杀进程重开 | 无深色闪底（或仅一帧且不可察觉） |
| 状态栏 | 手机竖屏浅色 | 状态栏/导航栏图标为深色 |
| 频谱例外 | 浅色模式下打开频谱/照片墙 | 舞台与照片墙保持深色，无浅色漏底 |
| 全页走查 | §6 的 G-01~G-07 逐页截图 | 全部条目通过 |
| 深色回归 | 深色下四屏截图 | 与改造前一致（§3.6 的 12 处除外） |
| 备份 | 导出 → 清数据 → 导入 | 主题模式恢复；旧备份文件导入不失败 |

## 9. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| 一次性全量变浅，边角页出现不可读元素 | 高 | P2/P3 逐页走查 + §6 固定检查项；不接受“整体看起来还行” |
| 非组合上下文的颜色读取被遗漏 | 中 | 垫片让编译器强制暴露，不会静默失效 |
| `remember` 缓存了旧主题颜色 | 中 | 颜色列表的 remember 必须把 palette 作为 key；P1 专项检查 |
| 深色主题被无意改动 | 中 | 深色 hex 冻结 + 四屏截图对比；唯一允许的变化清单见 §3.6 |
| 老备份导入失败 | 中 | 专用兼容单测 + `themeMode` 可空处理；复用 `nameOrDefault` 模式 |
| 浅色下高对比度模式把白字压白底 | 低 | `HighContrastColors` 随 palette（§3.5） |
| 焦点环在浅色主色底上不可见 | 低 | G-02/G-05 走查项；必要时给实底容器改用 `onPrimary` 焦点环 |
| 性能回退 | 低 | `staticCompositionLocalOf` + 仅在切换时整树重组；无逐帧读取 |

## 10. 所有者裁决记录（2026-10-21）

| # | 决策 | 取值 |
|---|---|---|
| D1 | 覆盖范围 | 除频谱效果页外，全部页面支持浅色 |
| D2 | 开关形态 | 三态：跟随系统 / 深色 / 浅色 |
| D3 | 配色来源 | 品牌青绿派生 + 脚本算对比度 |
| D4 | 默认值与迁移 | 默认跟随系统；显式选过深/浅色的用户保持原选择 |
| D5 | TV 的“跟随系统” | 回退为深色 |
| D6 | 照片墙 | 属于频谱舞台，随其保持深色 |

评审待拍板：

1. §3.6 的深色语义容器前景修正（12 处，推荐接受）。
2. §3.1 浅色 Background 取 `#F3F5F9`（与 Surface `#FFFFFF` 形成层级）。

## 11. 交付物

| 文件 | 变化 |
|---|---|
| `app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt` | palette、垫片、brush、高对比度、ColorScheme 映射 |
| `app/src/main/java/com/nasmusic/tv/data/model/ThemeMode.kt`（新） | 枚举 + 两个纯函数 |
| `app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` | 新增 `themeMode`，保留 `darkTheme` |
| `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt` | 新键、迁移、同步镜像、备份兼容 |
| `app/src/main/java/com/nasmusic/tv/ui/screens/settings/GeneralSettingsSection.kt` | 开关 → 三 chips |
| `app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt` | 模式解析、系统栏、启动窗背景 |
| `app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt` | 频谱舞台强制深色 |
| `app/src/main/res/values/strings.xml` + `values-en/strings.xml` | 5 条新增、2 条删除 |
| §6 走查涉及的业务文件 | 语义容器前景、遮罩色归类、remember key |
| `app/src/test/java/com/nasmusic/tv/ui/theme/ThemeContrastTest.kt`（新） | 对比度门禁 |
| `app/src/test/java/com/nasmusic/tv/ui/theme/ThemeModeTest.kt`（新） | 解析矩阵 |
| `app/src/test/java/com/nasmusic/tv/data/prefs/ThemeBackupCompatTest.kt`（新） | 备份兼容 |
| `docs/technical-overview.md` / `CHANGELOG.md` / `README.md` | 验收后同步 |

## 12. 实施记录与偏差（2026-10-21）

代码与门禁已落地；§8 的真机双端验收仍由所有者执行。以下只记录**与方案不同**或方案未覆盖的事实。

| # | 事项 | 实施结果 |
|---|---|---|
| I1 | `AppSettings.themeMode` 必须可空 | 声明为 `ThemeMode? = null`。老备份缺字段时 Gson 走无参构造器留 null —— 这是区分「老备份只有 `darkTheme`」与「新备份显式选了 SYSTEM」的唯一手段；生产链路 `AppPreferences.appSettings` 恒填非空值，备份导入走 `resolveThemeMode(settings.themeMode, settings.darkTheme)` |
| I2 | 语义容器前景修正范围扩大 | §3.2 的 12 处之外，同类「Primary / Danger / Warning 实底或 α≥0.8 聚焦底 + `TextPrimary` / `White`」全部收敛：**31 个文件、93 行、94 处** `onPrimary` / `onDanger` / `onWarning` |
| I3 | 浅色白卡层级 | `FocusableSurface` 新增规则：浅色且目标容器为 `Surface` 时补 1dp `Border` 描边；焦点环仍保持 modifier 链条最末。深色不添加（回归面为零） |
| I4 | 强制深色范围扩大 | 除频谱舞台 / 照片墙外，**沉浸播放页（横屏 + 竖屏）与 K 歌页**同样包 `ForceDarkPalette`：前者是纯黑画布（封面渐黑 + 歌词区纯黑），后者是封面 + 固定暗纱。浅色 token 落入这些区域会直接不可读 |
| I5 | 页面底色渐变 | 新增 `NasMusicBrushes.pageBackground`：深色 `Background → #0A1020`（与历史逐字节一致），浅色 `Background → SurfaceVariant` |
| I6 | 内容遮罩色归类 | 新增 `NasOverlayColors`（4 常量，不随主题）；MV 播放页白色提示文字改 `OnArtwork`；视频画布 / 黑纱与所有对话框的 `0xB3000000` scrim 保持固定 |
| I7 | 删除 `Color.kt` | 唯一剩余 `Accent` 迁至 `NasMusicColors.Primary` 后整文件删除；计划要求的 `PrimaryVariant` / `TextBrightHighlight` 已删 |
| I8 | 冷启动 | `AppPreferences.getThemeModeSync()`（`@Volatile` 镜像）供 `onCreate` 同步读取；镜像迁移必须走 `resolveThemeMode`，直接 `fromKey(null)` 会把「旧键 false（显式浅色）」误判为 SYSTEM |
| I9 | 门禁数量 | 新增 3 类 23 例（对比度 3 / 解析矩阵 10 / 备份兼容 10）；全量 **235 类 / 2423 例**全绿（基线 232 类 / 2400 例） |
| I10 | 未完成 | 真机 §8 验收（所有者执行）；G-03「α=0.85 聚焦底 + 白字」需截图实测 —— 1.0 实底已由 `onPrimary / Primary` 门禁覆盖，混色后的实际对比只能看真机 |
| I11 | 浅色真机缺陷修复（所有者反馈） | ① `PlayerControls` 主按钮图标写死 `tint = TextPrimary`，浅色下近黑压深青实底；改为不写 `tint`，由 `FocusableSurface` 按状态注入（`OnPrimary` / 按下 `TextPrimary`），队列页同型主按钮改 `OnPrimary`。② `KaraokeLineText` 默认 `Color.Yellow` 在白底不可读；新增第 19 个 token `karaokeHighlight`（深色 `#FFFF00` 不变、浅色终值 `#1E3A8A` 藏蓝；取色两轮打回：琥珀 `#92400E`「偏红」→ 蓝 `#1D4ED8` →「还要再压深一档」），门禁加 2 组（浅色 **9.49:1 / 8.73:1**）。③ 品牌色实底徽标（专辑卡歌曲数角标 / 歌单角标 / 首页与顶栏「♪」块）文字由 `TextPrimary`（≈3.26:1）统一改 `OnPrimary`（5.47:1）。全量 235 类 / 2423 例全绿（`--rerun-tasks`）；release md5 `a3d05ac5…`、25,857,251 字节、已覆盖安装手机（20:33:35） |
