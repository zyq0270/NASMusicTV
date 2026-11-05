# AGENTS.md — NASMusicTV

Compact guide for OpenCode sessions. Every line is something an agent would likely miss without help.

## Project Overview

Android TV music player (Kotlin + Jetpack Compose for TV) that connects to **Jellyfin** or **Navidrome** backends, plus an independent **network music** layer (Meting-API search) and a **weather radio** feature. Single-module Gradle project.

- Package: `com.nasmusic.tv` — Min SDK 22, Target SDK 34, Java 17
- Kotlin 2.3.10; TV Compose `androidx.tv:tv-material` is **alpha** (`ExperimentalTvMaterial3Api` opt-in set in `app/build.gradle.kts`, used throughout)
- GPL v3

## Build (Windows)

JDK comes from Android Studio's bundled JetBrains Runtime. Gradle wrapper points at a **local file URL** (`file:///C:/Users/hxzha/Downloads/gradle-9.5.0-bin.zip`) — intentional for offline builds; do not "fix" it to a network URL.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

./gradlew.bat assembleRelease "-Plofi.city=true"   # release APK — **default: WITH E51** (owner ruling 2026-10-29)
./gradlew.bat assembleDebug   "-Plofi.city=true"   # debug APK   — **default: WITH E51**
./gradlew.bat test            # unit tests (Robolectric + JUnit4)
```

> ⭐ **Local builds default to WITH E51「像素城市」** (owner ruling, 2026-10-29) — always pass `-Plofi.city=true`.
> The E51 sources are **not in the repo** (⛔ they are upstream translations with no license grant — see the E51 ruling below); they come from the out-of-repo overlay `D:/hxzhang/MyGithubSoftware/NASMusicTV-e51-local/src`, mounted by `~/.gradle/init.d/10-nasmusic-e51-local.gradle` (this init script exists only on this machine).
> The guard in `app/build.gradle.kts` **fails the build** when the switch is on but neither the overlay nor the branch sources are present ⇒ a missing overlay fails loudly instead of silently producing a "theme present, effect is the generic fallback" fake package.
> CI / public builds keep `lofi.city` off (no E51 byte ever reaches GitHub).
> ⛔ A package built with E51 is **local-only**: never push / tag / release it.
> Local release needs two keys in the gitignored `keystore.properties`: `cryptoPassphrase=` (must match the historical value, else the `packageRelease` guard fails the build) and `storeFile=release-key.jks` (project-root relative; without it the release signing config is never created and the APK comes out **unsigned**).

**adb 不在 PATH** — 完整路径：`C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe`。安装到电视（无线连接）：

```powershell
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" connect 192.168.0.114:5555
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 install -r app\build\outputs\apk\release\app-release.apk
```

> 电视上若已装 debug 版（签名不同），`install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `uninstall com.nasmusic.tv` 再安装。Release 切到独立签名（`release-key.jks`）后，旧的 debug 签名 release 包同理也需先卸载。

CI (`.github/workflows/build.yml`) has three jobs: **`build`** runs `assembleRelease` (push to `main`/`develop`, tags `v*`; PR to `main`), **`test`** runs `testDebugUnitTest`, and **`lint`** runs `lintDebug` — lint is now **blocking** (the `continue-on-error` was removed on 2026-09-14 after errors were driven to 0; 256 warnings remain and do not fail it). So a green CI means "compiles + unit tests pass + no new lint errors". The CI `build` job's signing step is **dual-mode + fail-fast** (v2.36.0 dual-mode; **v2.38.3 起 push/tag fail-fast**): if the repo has GitHub Secrets `SIGNING_KEYSTORE_BASE64` + `SIGNING_STORE_PASSWORD` + `SIGNING_KEY_ALIAS` + `SIGNING_KEY_PASSWORD`, it base64-decodes `release-key.jks` and signs with the real key (same APK as local release); the throwaway `ci-keystore.jks` fallback **only applies to `pull_request`** (fork PRs can't access secrets) — on `push`/`tag` a missing `SIGNING_KEYSTORE_BASE64` or `CRYPTO_PASSPHRASE` makes the workflow `exit 1` immediately (D5, 权限与签名统一方案 §14.4; guarded by `ReleaseSigningGateTest`). 发版前须配齐 5 个 repo secrets（含最易漏的 `CRYPTO_PASSPHRASE`，否则 push/tag 直接被 fail-fast 挡下）. Either way the `keystore.properties` it writes must include a `cryptoPassphrase` line, otherwise the `packageRelease` guard in `app/build.gradle.kts` fails the build. Local release builds read `keystore.properties` from the project root — `storeFile` is now a project-root-relative path (`release-key.jks`), and `app/build.gradle.kts` resolves it via `rootProject.file(...)` (not `project.file(...)`).

**本地构建必须加 `--no-daemon`**（本机实测，2026-09-14）：Gradle 守护进程 fork 出的子进程会全部失败——AAPT2 报 `Daemon startup failed / Please check if you installed the Windows Universal C Runtime`（即使资源只改一个字符串也会触发）、测试 worker 立刻退出（exit `268435466` = `0x1000000A`，低 16 位是 Windows `ERROR_BAD_ENVIRONMENT`）、Kotlin 编译守护进程报 `AccessDeniedException`。用 `--no-daemon`（Kotlin 侧再叠 `-Pkotlin.compiler.execution.strategy=in-process`）即可全部绕过：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

`--no-daemon` 只影响当前进程，不需要改 `gradle.properties`。注意 `aapt2.exe` 本身能独立运行（`aapt2 version` 正常），问题只出在守护进程模式，所以别被 "Windows Universal C Runtime" 的提示误导。

**单测在本机可以跑**（2026-09-16 更正）：`./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process` 实测 **184 个 `*Test.kt` / 2035 例**（2026-10-11 `--rerun-tasks` 全量重跑，约 4~6 min；增量跑「up-to-date」时不会重执行测试，验数必须带 `--rerun-tasks`），`--tests "*XxxTest"` 单类隔离也正常（6 例约 30s）。此前记录的「worker JVM 一启动即死（exit `268435466` = `0x1000000A` = Windows `ERROR_BAD_ENVIRONMENT`）」**已不再复现**——根因更可能是**卡死的 Gradle 守护进程持锁**而非本机永久性限制（同日另一提交亦记录「本机首次完整跑通 testDebugUnitTest」）。若再次遇到 worker 秒死，**先跑 `./gradlew.bat --stop` 释放锁再重试**，不要直接判定为环境不可用。**因此本机验证手段是「编译 + lint + 单测」，不要再沿用「不能声称测试通过」的旧口径。**

✅ **原先"全量跑必红"的那条已于 2026-10-12 定位并修复，全量 2040 例 / 186 类零失败**：`visualizer/photo/PhotoWallControllerTest > empty pool never starts a swap and never crashes` 抛 `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`，真凶是 `NasMusicApp.kt:500` `applicationScope.launch { songDownloadManager.recoverAfterCrash() }`（**onCreate 里唯一没包 try/catch 的一处**，`:517` 包了）在单测里发起的 Room 查询落到了 Robolectric 已拆掉的 SQLite 连接池上，抛 `IllegalStateException: Illegal connection pointer`；经**无 handler** 的 `applicationScope`（`:270`，唯一 `cancel()` 在 `onTerminate()`，而 **Robolectric teardown 不调它**）→ kotlinx 全局收集器 → 被下一个 `runTest` 的类接盘（该类 10 条 `runTest`，全树最密集，最容易接盘）。修法：给 `applicationScope` 加 `CoroutineExceptionHandler` 转 `AppLog.e` —— 命中后 `handleCoroutineException` 不再回落 platform handlers，异常不再成为 unhandled。
⛔ **可复用的诊断手段**（下次遇到同类"接盘"红直接照用，别再从零推）：`UncaughtExceptionsBeforeTest` 的消息里没线索，**但原始异常就挂在它的 `suppressed` 上**（kotlinx `TestScope.kt:241-246`）——是 **Gradle 报告格式不渲染 suppressed**，不是异常被吞。用**仓库外**的 init script 挂 `TestListener`，在 `afterTest` 里递归 `dump(e.suppressed)` 打到 stdout，并开 `testLogging.showStandardStreams`，`./gradlew.bat testDebugUnitTest -I <脚本>` 跑一次全量即可拿到完整栈。⚠️ 该红是**跨类累积 + 时序**竞态：单独跑「触发类 + 接盘类」**不复现**（2026-10-12 实测 3 类组合全绿），只有全量才稳定踩中 ⇒ 二分探针**可能有假阴性**。另有陷阱——`ExceptionCollector.enabled` 只在首次 `runTest.enter()` 置位且**永不复位**，探针里**必须至少含一个 `runTest` 类**，只由 `runBlocking` 类组成的子集必然假阴性。

## Architecture (verified against source)

- **Manual DI container**: `NasMusicApp` constructs and holds the global instances — `backendRegistry`, `appPreferences` (`AppPreferences`), `playerManager` (`PlayerManager`), `networkMusicManager` (`NetworkMusicManager`). There is **no DI framework** and **no `getInstance()` singleton** — these are plain classes instantiated once in `NasMusicApp.onCreate()`. Access them by casting `Application` to `NasMusicApp` (e.g. `MainViewModel` does `app as NasMusicApp`).
- **Backend adapters**: `BackendAdapter` interface; live implementations in `backend/impl/` (`JellyfinAdapter`, `NavidromeAdapter`). Both use **raw OkHttp**, not Retrofit. `BackendRegistry` holds the active adapter in memory only (reconnect on restart reads `AppPreferences`). `testConnection()` builds a throwaway adapter and never mutates current state.
- **Playback**: `PlaybackService` (Media3 `MediaLibraryService`) creates the ExoPlayer and registers it with `PlayerManager`. Progress is driven by a 1000ms `Handler` polling loop in `PlayerManager`, **not** ExoPlayer callbacks. Network songs carry no resolved `streamUrl` until played — `PlayerManager.onNeedResolveStreamUrl` signals `MainViewModel` to resolve then replay.
- **Lyrics**: `LyricsManager` is constructed in `MainViewModel` (takes `context`, `backendRegistry`, `networkMusicManager`). It **does** call `BackendAdapter.getLyrics()` (Jellyfin lyric endpoint). Priority: local cache → backend API / `NetworkMusicManager` → network match (fuzzy title+artist). Older docs describing MP3-embedded / local-LRC-file scanning are stale.
- **Network music**: `backend/network/` — `MetingApiService` + `NetworkMusicManager` (multi-endpoint fallback, 302 redirect resolution, play-url cache LRU 500 — `PLAY_URL_CACHE_MAX`; favorites only exist in NAS adapters, NetworkMusicManager has no favorites capability). Independent of the NAS backend.
- **Weather radio**: `backend/weather/` — `WeatherApi` (OpenWeatherMap) + `WeatherRadioManager` matches songs by mood. `BackendAdapter` was made nullable so weather radio works with no NAS connection.
- **Visualizer**: `visualizer/` (fx/renderers/photo) — the old 2D `WorldRenderer` (E41, 2106 lines, zero instantiation points) was **deleted** on 2026-10-06 (code-review M11); E41 "World" is served by the 3D `WorldGlobeRenderer` (WebView + three-globe) only. Do not re-add a 2D world renderer; `FxCoverageScanTest` gate expects 27 renderer classes (E44「明月」`MoonlitRenderer` added 2026-10-08, theme count 21 → 22; E46「迷雾之战」`TripodFogRenderer` added 2026-10-13, theme count 22 → 23; E47「雪松」`SnowCedarRenderer` added 2026-10-14, theme count 23 → 24; E49「鸟群」`BirdsFlockRenderer` added 2026-10-15, theme count 24 → 25; E48「大漠」reserves ordinal `"48"` but has not landed; E50「千里江山」`QianliScrollRenderer` added 2026-11-05, theme count 26 → 27, renderer count 26 → 27). `VisualizerRendererFactory.create(theme, context)` now consumes `context` for a second reason: `MOONLIT` decodes `assets/globe/moon.jpg`, so its branch must pass `context.applicationContext` (never the Activity). Bilingual display names are guarded by `VisualizerThemeStringsGateTest` (a missing `values-en` key compiles fine and crashes at render time).
- **E41 资产分两处（2026-10-07 拆开，别合回去）**：`app/src/main/assets/globe/` 只放**运行时装载的 9 个文件**（index.html + polyfill/three/three-globe 的 `.es5.js` + globe.js + 4 张贴图）；`app/src/globe-upstream/` 放**上游原件与构建输入**（`three.min.js`、`three-globe.min.js`、`earth.jpg`、`cities.json`、`downlevel_libs.mjs`），在模块内但 `main` 源集之外，**Gradle 不打包**。
  ⛔ **两个 ES6 原版是 ES5 产物的唯一再生源**（`downlevel_libs.mjs` 从它们读源转译），也是「产物是否被手工改过」的逐字节 diff 基线 —— **永不删除、也永不移回 assets**（移回即白占 0.62 MiB，删掉则永久丧失可再生性）。
  ⛔ `downlevel_libs.mjs` 装依赖也必须装在 `globe-upstream/`：**装在 assets 下会被 `mergeDebugAssets` 打进 APK**（2026-10-06 实测一次多 23 MB）。护栏是 `GlobeAssetsHygieneTest`（5 例），`.gitignore` 挡不住打包。
  ⛔ `cities.json` 看着像运行时数据，其实**从不加载**——城市坐标由 Kotlin 经 `evalJs("WorldGlobe.initCities(...)")` 注入。
- **Navigation**: single `com.nasmusic.tv.ui.MainActivity` (entry class is `.ui.MainActivity`, **not** `com.nasmusic.tv.MainActivity` — `am start -n com.nasmusic.tv/.ui.MainActivity`) with a manual `when(currentScreen)` switch (`Screen` enum in `data/model/Screen.kt`) — no Jetpack Navigation despite the `navigation-compose` dependency. Five-level BACK (`MainActivity.kt`): immersive-mode exit fullscreen (Level 0) → close dialog (Level 1) → list scroll-to-top (Level 1.5) → navigate back to NowPlaying (Level 2) → exit confirm (Level 3).

## Non-obvious constraints

- **GBK encoding gotcha** (`util/EncodingUtils.kt`): Jellyfin may store GBK ID3 bytes as if UTF-8. `utf8Body()`/`fixEncoding()` attempt GBK fallback when U+FFFD / Greek / Cyrillic chars appear. Some cases are unrecoverable client-side (already-encoded Unicode codepoints) — don't assume you can fully fix artist-name mojibake.
- **Pinyin search** uses TinyPinyin (`com.github.promeg:tinypinyin`), chosen specifically because the min SDK is API 22 and `android.icu.Transliterator` needs API 26+. Do not swap back to ICU-based pinyin.
- **Cleartext traffic** is enabled (`usesCleartextTraffic=true`) for local NAS HTTP.
- **设备支持：电视 + 手机都支持**（2026-09-16 更正，此前写作「Leanback required — 只能装电视、锁定横屏」，与 Manifest 不符）。`AndroidManifest.xml:18-27` 三个 feature 全部 `required="false"`：`android.software.leanback`、`android.hardware.touchscreen`、`android.hardware.screen.portrait`（注释即写着 "both TV and phone supported"）；`MainActivity` 是 `screenOrientation="fullSensor"`，**方向不锁定**。核对手段：`aapt2 dump badging <apk> | grep -i "sdkversion\|native-code"`（会打印 `uses-feature-not-required`）。相关设计见 `docs/archive/phone-support-plan.md`、`docs/archive/phone-media-display-plan.md`。
- **ProGuard**: release build minifies + shrinks. Rules in `proguard-rules.pro` keep `data.model`, `data.prefs`, `backend`, Gson, ExoPlayer. A prior release crash (v2.5.1) came from Gson type erasure under R8 — keep those `-keep` rules when adding serialized models. `Log.d/v` are stripped in release via `-assumenosideeffects`; **注意 `AppLog.d/i/w` 另有 `if (BuildConfig.DEBUG)` 守卫，release 下 R8 会连字符串常量一起折掉（`AppLog.e` 无守卫、必然保留）**——用 dex 字符串验 R8 存活时别拿 d/i/w 文案当证据。
- **Multi-ABI**: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- **Demucs 人声分离的输出契约**（`player/DemucsSeparator.kt`，2026-09-14 起）：`decodeAudioToTempFile()` 保证**输出恒为 44100Hz 立体声**——单声道源复制成 L/R，非 44100Hz 源由私有类 `LinearResampler` 线性插值归一化。因此 `writeWavHeader()` / `patchWavDataSize()` 无条件用 `SAMPLE_RATE`/`CHANNEL_COUNT` 是安全的；**若日后放开该保证，这两处必须改为接收实际参数**。`totalSamples` 取 `writeFrame()` 的调用次数，不要用「float 数 / 声道数」反推。
- **Demucs 模型完整性**（`player/ModelDownloadManager.kt`）：`EXPECTED_SHA256` 取自 HuggingFace LFS 的 `oid`（对 LFS 对象而言 `oid` 就是 SHA-256）。下载完成后必须通过 SHA-256 才落盘，加载模型前再由 `verifyModelIntegrity()` 校验一次。**该校验对自定义 URL 同样生效**——自定义源定位是「自建镜像/NAS」，要提供字节一致的文件；换权重必须同步改 `EXPECTED_SHA256`。注意代码下载的是 **fp16** 权重（165,612,636 字节），上游另有 316MB 的非 fp16 版本，两者 SHA 不同。`isModelDownloaded()` 保持「快速判定」定位（可能被主线程调用，166MB 哈希会 ANR），**不要在里加哈希**；其大小阈值已收紧到 **±1%**（`163,956,509 ~ 167,268,762` 字节，原来是 `> size * 0.8`，过宽且无上界）。
- **ONNX Runtime Java API 坑**：`OnnxValue.getInfo()` 返回的 `ValueInfo` 是**空接口**，shape 只在 `TensorInfo` 上——`value.info.shape` 编译不过，必须 `(value.info as? TensorInfo)?.shape`。另外 `OrtEnvironment.getEnvironment()` 是**进程级单例，绝不能 close**；`OnnxTensor` / `OrtSession.Result` 持有 native 内存（GC 回收不到），必须显式 close。
- **`onnxruntime-android` 的版本被「minSdk 22」与「16KB 页对齐」双重锁定，不要随手升级**（2026-09-14 实测）：lint 的 `Aligned16KB` 会报这个依赖（1.17.1 的 native 库 `p_align = 4096`）。实测矩阵（`p_align`，AAR 内 4 个 ABI × 2 个库）：**1.17.1** 全 4096 / minSdk 21；**1.20.0** 主库 16384 但 `libonnxruntime4j_jni.so` 仍 4096 / minSdk 21；**1.21.1** 仅 arm64 全对齐 / minSdk 24；**只有 1.29.0 全对齐（16384）/ minSdk 24**。两个必须知道的机制：① lint 的 `PageAlignmentDetector.getIncidentsFromAndroidLibrary` 遍历 AAR 解包目录下**全部 ABI**（不受 `abiFilters` 影响），命中第一个未对齐的库即 `return`，所以**每个依赖只报 1 条**，报告里的「3」是聚合重复；② 1.29.0 的 AAR manifest 要求 **`minSdkVersion 24`**，而本项目是 22——直接改会让 manifest merger 失败，改 minSdk 则砍掉 Android 5.0/5.1/6.0（含创维 5.1.1 开发机，真机回归的基准设备）。**当前有意保持 1.17.1**（是 Warning 非 Error，`targetSdk 34` 下 Play 的 16KB 强制要求尚未触发）。**触发条件**：升 `targetSdk 35` 或上架前必须处理。另外 lint 里那份硬编码的「已知安全依赖」白名单（`isDependencyKnownSafe`）**不含 `com.microsoft.onnxruntime`**，没有绕过路径。完整矩阵与决策记录见 `docs/technical-overview.md` §10.146 的 §七-4 遗留项，依赖声明处的注释在 `app/build.gradle.kts`。
- **lint 输出里的内部异常栈是既有的**：构建输出中会出现 `LintCliClient.analyzeOnly` → UAST visitor 的异常栈（`docs/archive/verification/verify4.log` 中同样存在）。它不影响报告生成与构建结果，不要当成新引入的问题去排查。
- **构建前若报 `fileHashes.lock (拒绝访问)`**：`--no-daemon` 并不保证不起守护进程 —— `gradle.properties` 里的 `org.gradle.jvmargs=-Xmx2048m` 会强制 fork 一个**单次守护进程**。该守护进程若卡在退出序列（`PersistentDaemonRegistry.remove` 的锁竞争），会一直持有 `.gradle/<版本>/fileHashes/fileHashes.lock`，后续所有构建都在 `Could not create service of type FileHasher` 处秒失败（约 26s）。**解法：`./gradlew.bat --stop`**（会打印 `1 Daemon stopped`），再重跑。**不要 `rm` 锁文件** —— 在带 safe-delete 包装的环境里 `rm` 失败会把文件留在 Windows「删除挂起」状态，之后任何 open 都返回 `Permission denied`（权限位显示 666 可写也没用），**反而让后续构建全部失败**；`rm` 报 `Device or resource busy` 就已说明有进程持有，去 `--stop` 而不是硬删。
- **飞牛音乐（fnOS）后端协议**（`backend/impl/FeiniuAdapter.kt` + `FeiniuUrl.kt`，2026-09-14 重写）：唯一权威依据是参考项目 `fn-music-tv`（本地副本 `D:\hxzhang\MyGithubSoftware\NasAudio\fn-music-tv-v1-1-2`），完整提取结果与缺陷对照表见 `docs/archive/feiniu-backend-improvement-plan.md`。**不要再回到第三方逆向文章的猜测端点**。易错点：① 认证头是 **`Authorization: <userToken>`（原始值，无 Bearer）**，不是 `Cookie: music-token=`（后者只在 relay 模式用）；② 登录响应字段是 **`data.userToken`**；③ 分页是 **`page` + `size`**，不是 `limit`；④ ID 全是 **GUID**，参数名是 `albumGUID`/`artistGUID`/`trackGUID`/`playlistGUID`；⑤ 流地址 `track/stream?guid=`（查询参数）、封面 `static/cover?coverId=`（按 **coverId** 而非曲目 ID）；⑥ `duration` **已是毫秒**；⑦ 信封 `{code,msg,data}`，`data` 可为 null；⑧ 默认端口 **5666**（HTTPS 5667），不是 80；⑨ **服务端没有搜索端点**，`searchSongs` 是客户端本地过滤；⑩ 适配器必须在**解析期填充 `Song.streamUrl`**（`FeiniuUrl.streamUrl`）—— 全 app 的 NAS 播放解析只认 `getSongsByIds()` 返回的 streamUrl，置空会让点播「解析失败」（2026-09-15 修复）。
- **`BackendAdapter.streamHeaders` 的注入链路**（2026-09-14 补齐）：此前该属性**定义了但全仓库无消费方**——Jellyfin/Navidrome 把凭据拼在 URL query 上，所以历史上没暴露；飞牛是第一个必须走请求头的后端。现在由 `BackendRegistry` 在连接成功时向 `BackendAuthHeaders` 绑定 **provider**（每次请求实时读取 `adapter.streamHeaders`；快照形态会漏掉静默重登换新令牌 —— 2026-09-15 改为 provider），再由 `BaiduHttpDataSourceFactory` 的拦截器按 **host 精确匹配**注入（同一客户端同时服务 ExoPlayer 播放与 Coil 封面）。**新增后端若要走请求头，只需覆写 `streamHeaders`，不要另起炉灶**；也不要放宽 host 匹配，否则令牌会随 302 泄漏到 CDN。
- **构建环境**：本机 Gradle fork 出的子进程普遍起不来（AAPT2 守护进程、Kotlin 编译守护进程），因此构建必须加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`；**但测试 worker 例外——它已可用**（见上文「单测在本机可以跑」）。遇到 worker 秒死时先 `./gradlew.bat --stop` 释放卡死的守护进程再重试，而不是直接绕道。`kotlin-standalone-verify` 技能仍适用于「只想验证一个纯逻辑类、不想付整轮 Gradle 代价」的场景，但不再是**必需**的绕行手段。

## Conventions

- Git/branch/commit rules live in `.opencode/rules.md` (conventional-commit prefixes, `main`/`dev`/`feat/*`, and the rule to update `CHANGELOG.md` + `docs/technical-overview.md` §10 after verified changes). Follow that file; don't duplicate here.
- Implementation/change history is recorded in `docs/technical-overview.md` §10 — only verified changes. For change context, `docs/code-review-*.md` and `CHANGELOG.md` are more current than prose elsewhere.
- Tests: `app/src/test/`, Robolectric JUnit4. Run targeted: `./gradlew.bat test --tests "*PinyinUtilsTest"`.
- **浏览器原型（`docs/*.html`）的验证脚本**在 `docs/archive/verification/scripts/`，与本目录的审计脚本同处。E43 海边那套（2026-10-03 归档）：
  `seaside_wave_harness.js`（**帧驱动 harness**：`vm` 沙箱桩掉 DOM/canvas/`Path2D`，打桩 `requestAnimationFrame` 驱动**真实页面代码**，并注入探针读浪队列闭包状态 —— 几何/时序类断言全靠它）、
  `seaside_hole_continuity_check.js`、`seaside_caustic_geom_check.py`、`seaside_visual_driver.mjs`（真浏览器 CDP 驱动：`snap`/`series`/`waterline`/`cost`，rAF 打桩、确定性推进）、
  `html_syntax_check.js`。
  ⭐ **Kotlin 端的单测可以直接照抄 harness 的断言口径**（它测的都是纯函数：位置映射、噪声连续性、cull 阈值、每帧位移上限），不需要图形设备。
  ⚠️ 这类 harness 的探针**必须挂在目标变量赋值之后**：挂错作用域会读到上一帧的残留值，量出「间距恒等于 0」这类假结论（真实发生过）。
- **E44「明月」云场基线：数字只能由脚本再生，⛔ 不许手抄**（`docs/archive/verification/scripts/moonlit_cloud_golden.js` → `app/src/test/…/MoonlitCloudBaseline.kt`）。
  该脚本在 `vm` 沙箱里跑 `docs/archive/moonlit-preview.html` 的**原文**（播种 / `layoutPuffs` / `stepClouds` / `occlusion`），再生成 G3 逐位对账用的基线与 `occl` **分布**五项。
  ⚠️ 改云场数学（rng 调用次序、包络式、`OCCL_PAD`）必须重跑它并一起提交再生结果；把数**抄进 Kotlin 注释或测试**就是偏差 **D24** 的重演 ——
  那次的成因是一个 `const minDim` 遮蔽了沙箱全局，缕尺寸按 0 算，得到的假峰值（`occl ≤ 0.33`）在文档里活了整个定稿流程。
  脚本自带两条防退化自检（`minDim` 没进上下文全局就抛、包络半轴全 0 就抛），⚠️ 探针若跨作用域读变量，一律**赋在全局**再读。
- ⛔ **绝不用 PowerShell 对 UTF-8 源文件做文本往返**（`(Get-Content -Raw) -replace … | Set-Content`）。它按控制台代码页（CP936）读、按 UTF-8 写回，会把中文注释全变成乱码，并**吞掉紧邻的 ASCII 标点与换行** ⇒ 引号/括号/换行被破坏、语法直接崩。批量替换一律用 `edit`/`write` 工具，或 `python -c` 配 `encoding='utf-8'`。（2026-10-03：这条规则当时已写进脚本文件头，我仍然违反它，毁掉了一个 2400 行、未提交、无备份的原型文件。）
- ⛔ **Kotlin 的块注释是「可嵌套」的**：注释里写 `assets/globe/**` 会被词法器当成**嵌套注释的开始** ⇒ 外层那个 `*/` 只关掉内层 ⇒ `Syntax error: Unclosed comment`（2026-11-04 写门禁 KDoc 时真踩了）。注释里要表达「某目录下全部」就写 `assets/globe/`（**不带 `**`**）。
  - ⚠️ **2026-11-06 又踩一次**（在 `VisualizerRendererFactory.kt` 的注释里写了 `` `qianli/port/**` `` ⇒ 整个 main 源集编译失败 ⇒ **同时打断 4 条并行 lane 的构建**）。报错位置还指着「第 1 行」，**极具误导性**。
  - ✅ **现在有编译前的检查器**：`python scripts/kotlin_comment_balance.py`（从仓库根跑）⇒ 模拟 Kotlin 的可嵌套语义逐字符扫描，**不配对就退出码 1**。改完 Kotlin 源码后**先跑它**再跑 Gradle。
- **Doc writing style (user-mandated, 2026-09-20)**: `README.md` lists **features only** — user-facing, concise, drop minor/implementation-level items. `CHANGELOG.md` entries state **what was done only** — one line each, **no root cause, no implementation detail, no source line numbers, no verification narrative**; all of that belongs in `docs/technical-overview.md` §10.N. Touch **only the current version's section** — never rewrite historical sections.
- ⛔ **Never `reject()` in `MediaSession.Callback.onConnect`** (root cause of "car Bluetooth media buttons dead", §10.165). System-side controllers (Bluetooth AVRCP / SystemUI / car) arrive via the framework `MediaController` and Media3 routes them through `MediaSessionLegacyStub.tryGetController() → onConnect`; rejecting makes Media3 drop the command silently. Put the security boundary in `setAvailableSessionCommands` instead — and since that is **strict set membership** (not additive), only ever **add to** `DEFAULT_SESSION_AND_LIBRARY_COMMANDS`. Gate: `MediaSessionAccessPolicyTest`. ⚠️ Only reproducible in **release** builds.

- ⛔ **E51「像素城市」+ E52「旷野公路」：文档公开、代码私有**（所有者 2026-10-22 / 10-30 / **2026-11-04** 三次裁决）
  - **文档公开**（两个分支都可访问）：`main` 上在 `docs/archive/`（`e51-lofi-city-plan.md` / `e52-highway-opengl-plan.md` /
    `e52-progress.md`，2026-11-04 双双定稿后归档）；私有分支上另有两份 E52 原型 HTML
    （`docs/archive/e52-highway-preview.html` / `docs/archive/e52-highway-webgl-preview.html`）——
    它们**内含上游移植件 ⇒ 只在私有分支**，⛔ 不要为了消死链就把它们拷进 `main`。
  - **代码 / 测试 / 资产私有**：⛔ **只存在于本地分支 `feat/e51-private`**（**本地专用分支：只做本地提交，永不推送**）：
    E51 源码 `app/src/main/java/com/nasmusic/tv/visualizer/e51/`（`engine/` = 纯 JVM 引擎层，
    `scenes/` = 场景与位图接缝）+ 测试 `app/src/test/java/com/nasmusic/tv/visualizer/e51/`；
    E52 源码 `app/src/main/java/com/nasmusic/tv/visualizer/highway/` + 资产
    `app/src/main/assets/e52_highway/`（上游 Slow Roads 贴图/模型）+ 测试
    `app/src/test/java/com/nasmusic/tv/visualizer/highway/`。
    ⛔ **`main` 上不存在 E51/E52 的任何源码、测试、资产** —— 这是剥离后的既定事实，不是"待处理"状态。
    ⛔ **移植件路径清单**（loficities.com 上游代码的翻译件/派生物，**上游无任何开源授权**）：
    `visualizer/pixel/**`、`visualizer/scenes/lofi/LofiCity*.kt`、生成表 `CityRegistry.kt`、
    `visualizer/renderers/LofiCityRenderer.kt`、`ui/components/LofiCityCard.kt`、`test/**/Lofi*Test.kt`、
    `scripts/lofi_city_registry_sync.py`、`docs/archive/e51-lofi-city-plan.md`、
    `docs/archive/verification/results/lofi_registry_*.json`。
  - ⛔ **不 push、不 tag、不发 release、不上传任何远端**。`feat/e51-private` **永远不随 `main` 推送**：
    ① 日常开发只 `git commit` 到该分支；② 该分支**不设 upstream**；③ 需要备份用 `git bundle` 落盘，
    ⛔ 不用推送代替备份；④ 仓库已是 **PUBLIC**，任何一次误推都会立刻公开。
  - `BuildConfig.LOFI_CITY_ENABLED` / `HIGHWAY_ENABLED` 双变体开关**只是第二道锁**，
    ⛔ 不能因为有它就认为可以 push。
  - ⛔ **任何会话不得自行 rebase / reset / force-push**，⛔ 也不得为了"能 push"而删掉移植件。

  ✅ **2026-10-23 历史剥离已完成**（取代此前"护栏被突破"的记录）：仓库已转 private；
  `git filter-repo` 从全部历史删除 56 个移植件路径（被跟踪文件 1113 → 1057），改写后主干
  `d744c5c` → `6a6af1d`（移除对被删类的 7 处引用、补 `LOFI_CITY` 的 `when` 穷尽性兜底），
  已 `--force-with-lease` 推送到 `origin`；`main` 现为干净的公开基线。E51 的全部提交与源码
  转移到本地分支 `feat/e51-private`（无 upstream）。2026-10-23 两次含移植件的 CI 构建
  （`03:42` / `04:07`）已连同 artifact 删除。
  ⚠️ **残留（已知并接受）**：force-push 只改了 ref，旧 commit 对象仍在 GitHub 对象库中，
  知道 hash 的人仍可通过 API 拉取；`forks: 0`（无人复制出去），最新 Release 早于移植件提交。
  所有者 2026-10-23 决定**不**提交 GitHub 清缓存工单，此项按"接受残留"处理，**勿再主动重提**。

- ⛔ **私有分支只保护「代码」；文档与其它文件一律公开**（所有者 2026-11-04 明示：「私有分支保护的只有代码，其他文档和其他文件都应该公开」）
  - ⭐ **判据只有一条：是否含上游代码 / 是上游代码的翻译件或派生物。**
    含 ⇒ **私有**；不含 ⇒ **公开**（两个分支保持一致）。
    ⚠️ **「含上游代码」的前提是「上游授权不允许再发布」**（E51/E52 的 loficities.com /
    Slow Roads 均**无任何开源授权** ⇒ 外发即侵权 ⇒ 必须私有）。
    ⭐ **上游有可再发布授权（MIT 等）时不在此列** —— 授权允许再发布，只需保留署名与许可声明
    （本仓先例：E44「明月」的 three-globe（MIT）原件就在 `app/src/globe-upstream/`，公开在 main 上）。
    **E50「千里江山」**（上游 `LingDong-/shan-shui-inf`，**MIT**，Copyright 2018 Lingdong Huang）
    属后者 ⇒ **公开**（所有者 2026-11-05 裁定），
    `app/src/main/java/com/nasmusic/tv/visualizer/qianli/**` 每个移植文件都带
    「移植自 …（MIT，Copyright 2018 Lingdong Huang）」署名。
    ⚠️ 但**「公开」≠ 只在 main**：E50 的工厂注册必须**同时**在 `feat/e51-private` 上（见下方
    「覆盖层会掩蔽仓库同名文件」）⇒ **E50 代码在两个分支上都有且逐字节一致**（ cherry-pick，不是复制粘贴）。
  - **私有**（只在 `feat/e51-private`，⛔ 不 push）：
    · E51 / E52 的 **Kotlin 源码与测试**（`app/src/main/java/com/nasmusic/tv/visualizer/{e51,highway}/`、
      `app/src/test/java/com/nasmusic/tv/visualizer/{e51,highway}/`）
    · **资产** `app/src/main/assets/e52_highway/`（上游 Slow Roads 贴图与模型）
    · **debug 探针** `app/src/debug/**`（GL probe / E52 的 P0 探针）
    · **移植件与其工具**（`scripts/lofi_*`、`scripts/e52/**`、`docs/archive/e51-lofi-city-plan.md`、
      `docs/archive/verification/results/lofi_registry_*.json`）
    · **两份 E52 原型 HTML**（`docs/archive/e52-highway-preview.html` / `docs/archive/e52-highway-webgl-preview.html`）
      —— 它们**是文档**，但**内含上游移植件** ⇒ 按「代码」处理 ⇒ 私有。
      同理 `docs/archive/verification/results/e52_golden_vectors.json`（上游数值向量）与
      `docs/archive/verification/scripts/e52_highway_*.{mjs,js}`（依赖私有原型）。
  - **公开**（两个分支保持一致）：`CHANGELOG.md`、`AGENTS.md`、`CLAUDE.md`、`app/build.gradle.kts`
    （含 `highway.enabled` / `lofi.city` 双变体开关，**CI 与公开构建缺省关闭**，故公开无害）、
    `docs/**` 里除上述以外的全部、`scripts/` 里项目级通用工具、`.gitignore` 等、
    以及 **E50「千里江山」的移植层与渲染器**（`app/src/main/java/com/nasmusic/tv/visualizer/qianli/**`、
    `app/src/main/java/com/nasmusic/tv/visualizer/renderers/QianliScrollRenderer.kt`、
    `app/src/test/java/com/nasmusic/tv/visualizer/qianli/**`；上游 MIT ⇒ 可再发布，见上一条的授权判据）。
  - ⛔ **不 push、不 tag、不发 release、不上传任何远端**；`feat/e51-private` **永远不随 `main` 推送**
    （① 只做本地提交；② 不设 upstream；③ 备份用 `git bundle`，⛔ 不用推送代替备份）。

  ⭐ **本地构建携带隐藏分支效果 / GitHub 构建不含**（所有者 2026-11-04 明示）
    「**在 `main` 分支上本地编译时，也要携带隐藏分支的效果；推送到 GitHub 上编译时，不包含隐藏分支效果。**」
    实现 = **仓库外覆盖层** + **仅本机的 Gradle 初始化脚本**（两者都不在版本库里，⛔ 不外泄）：
    · 覆盖层：`D:/hxzhang/MyGithubSoftware/NASMusicTV-e51-local/src/ovl/app/src/{main/java,main/assets}/...`
      （E51 源码 + E52 源码 + `e52_highway/` 资产），由 `python scripts/sync_private_overlay.py`
      从本地分支 `feat/e51-private`（⛔ 从不推送）同步而来 ⇒ 覆盖层天然不外泄。
    · 初始化脚本：`~/.gradle/init.d/10-nasmusic-e51-local.gradle`（**仅本机**）把覆盖层挂成
      app 模块 main 源集的额外源目录（Kotlin 走 java 源目录、资产走 assets 源目录），
      并在 **Kotlin 编译任务层**排除仓库里那几份同名文件（否则 Redeclaration）。
    ⛔ **CI / 公开构建不含**：GitHub runner 上没有 init 脚本 ⇒ 没有覆盖层 ⇒ 一行隐藏分支代码都
    不会被编译进公开包；两个开关（`lofi.city` / `highway.enabled`）在 CI 上缺省 false。
    ⚠️ **改了隐藏分支的 E51/E52 代码 ⇒ 必须先同步覆盖层**：
    `python scripts/sync_private_overlay.py`（在私有分支上跑，或在其它分支上由脚本从本地 ref 抽取），
    否则本地包不含新代码而 CI 却能编过 ⇒ 本地与 CI 行为分叉；
    `python scripts/sync_private_overlay.py --check` 退出码 1 = 有漂移。
    ⚠️ **两处清单必须一一对应**：init 脚本的 `E51_TRACKED` ⇔ `scripts/sync_private_overlay.py`
    的 `SHADOWED_JAVA`（⛔ 改一处就要改另一处）。
    ⚠️ **排除只作用于 main 变体的编译任务**（`compileDebugKotlin` / `compileReleaseKotlin`）：
    单测编译任务的源根是 `app/src/test/java`，同样排除会把 E51/E52 的**单测源码**也排掉
    ⇒ 本地单测会静默少跑一批用例。

  ⚠️ **覆盖层会掩蔽仓库同名文件**（`VisualizerRendererFactory.kt` / `VisualizerStage.kt` /
  `RenderContext.kt`）：本地构建实际用**覆盖层那份** ⇒ 改这三份**必须同时改仓库与覆盖层副本**
  （`python scripts/sync_private_overlay.py` 同步；`--check` 必须 0 漂移），否则本地等于没改
  （CI / 公开构建没有覆盖层，反而能编过 ⇒ 本地与 CI 行为分叉）。
  ⚠️ `app/build.gradle.kts` 里两道护栏：E51 源码存在性 + E52 源码/资产存在性与清单校验
  （开关打开却没有真源码 ⇒ 直接 `GradleException`，不编「主题可选、效果是兜底」的假包）。

### Doc lifecycle (archive rule — user-mandated, 2026-09-20)

`docs/` root holds **only active docs** — after the third pass it is down to **2 living docs**
(`technical-overview.md`, `conventions-adaptive-ui.md`). Completed ones move to `docs/archive/`;
external-facing articles (zhihu etc.) live in `docs/articles/`.

A doc is **archivable** when **both** hold:

1. **It is not a living doc.** Only the continuously-maintained index/convention docs stay
   forever: `technical-overview.md` (§10.N keeps growing) and `conventions-adaptive-ui.md`.
   ⚠️ A doc being the designated recovery source for a shipped algorithm (the vocal-removal
   DSP doc, §10.152) does **not** keep it in root — the role survives the move, since archiving
   is a pure `git mv` with references rewritten.
2. **Its fate is settled** — either ✅ shipped, or ❌ permanently abandoned.

   **(a) Shipped** — judged by `CHANGELOG.md` / `docs/technical-overview.md`, and by whether the
   implementation exists in source (`SubsonicAdapter.kt`, `KaraokePlaybackScreen.kt`, …).
   ⚠️ **Never** trust the doc's own status header — headers go stale. The third pass archived 13
   docs whose headers read "待评审 / 可开发状态 / 方案提案 / Phase 7 未开始 / 含待所有者决策项"
   while the work had already shipped and the doc was simply never back-filled.

   **(b) Abandoned** — the owner has ruled the goal dead (upstream closed third-party access,
   no public API endpoint exists, …). Archiving is **not** a success badge: a dead plan left in
   `docs/` root keeps misreading as "still doable". A merely *unscheduled* active plan is **not**
   this case and stays put.

   ⚠️ **If the doc still lists open owner-decision items at archive time, record them explicitly**
   in the `docs/archive/README.md` row (the 2026-09-20 refactoring plan carried two: the R-5
   four-phase split and F-8 download keep-alive). Archiving means "no longer maintained as an
   active plan" — silently burying open decisions is the failure mode to avoid.

> ⛔ **A `CHANGELOG` grep alone is NOT sufficient** — it necessarily misses two classes:
> **changed-approach** docs (the feature shipped in a different form, so the original keywords
> never appear) and **abandoned** docs (nothing ever shipped, so there is nothing to find).
> For those, **ask the owner** — never conclude "not shipped" from a zero-hit grep. All 13 docs
> the second pass kept (as either "unshipped" or "living") were overturned by exactly this mistake.
> ⚠️ Write examples **without** the `docs/…md` path form, otherwise this very section
> becomes a reference and pollutes the reference graph.

> ⛔ **Being referenced is NOT a blocker.** Source KDoc and `AGENTS.md` cite plan docs heavily,
> but archiving does **not** break those citations — the move rewrites every inbound reference
> in the same pass (`docs/<name>` → `docs/archive/<name>`), including `app/src/**` KDoc,
> `AGENTS.md`, `CHANGELOG.md` and cross-doc links. Treating "referenced" as a veto is
> **over-conservative** and leaves finished docs cluttering `docs/` root. The 2026-09-20
> second pass archived 6 such docs and synced 57 references across 41 files with zero
> dead links.

When archiving:

- Use `git mv` (history preserved, no content deletion) — never `rm`.
- **Rewrite every inbound reference** `docs/<name>` → `docs/archive/<name>`.
  ⚠️ The sweep is **`docs/`-prefixed only** — a **bare filename** reference
  (e.g. a name listed in this very section) is **not** matched and must be swept by hand.
  Grep the bare basename after the move. Then re-run the dead-link check — a zero-hit
  check confirms nothing broke.
- Add a row to `docs/archive/README.md` (doc / reason / superseding record, **plus any open
  owner-decision items**).
- Rollback is a single `git checkout -- docs`.

See `docs/archive/README.md` for all three 2026-09-20 passes (60 files moved out of `docs/` root,
177 references synced).

### Repo-root hygiene — temp / log files (user-mandated, 2026-09-20)

⛔ **Every log / temp / debug artifact goes in its designated directory — never in the repo root.**

| Kind of file | Belongs in |
|---|---|
| Build / test / adb logs, debug dumps, one-off scratch (`*.log`, `diag.txt`, `defex.txt`, …) | **`logs_temp/`** |
| Agent-produced images / previews (screenshots, icon renders, verify shots) | **`output/`** |
| Gradle build output | `build/` (Gradle-managed) |
| Agent runtime internal state (task registry, scratch) | `.box-agent/` |
| **Reusable** audit / gate / measurement scripts (cited by living docs) | **`scripts/`** — run them from the **repo root** (`python scripts/xxx.py`), because their default paths are CWD-relative |

- The repo root holds **only** tracked project files: build config (`build.gradle.kts`,
  `settings.gradle.kts`, `gradle.properties`, `gradlew*`), docs (`README.md`, `CHANGELOG.md`,
  `AGENTS.md`), `.gitignore` / `.gitattributes`, signing files (`keystore.properties*`,
  `release-key.jks`, `local.properties`). Helper scripts do **not** belong in the root any more —
  `audit_small_touch_target.py` and `check_chinese.py` moved to `scripts/` on 2026-10-16, and the
  one-shot `patch_artists.py` went to `docs/archive/`.
- When redirecting build output, **target `logs_temp/`** — e.g.
  `./gradlew.bat assembleRelease *> logs_temp/release_build.log` — never the bare root.
- ⚠️ `.gitignore` has a `# CI / debug leftovers` block (`build_*.log`, `diag.txt`, `defex.txt`,
  `release_build.log`, …). It exists **because this kept happening** — treat it as a safety net,
  not a licence. Gitignored ≠ acceptable; put the file in `logs_temp/` in the first place.
- ⚠️ Agent session dirs named `20??-??-??-????????/` (`<date>-<session-id prefix>`) are created by
  the agent runtime in the CWD and are gitignored. They are **not source** — clean them out when
  found (they hold throwaway screenshots).
- `logs_temp/` is gitignored and should be **emptied periodically**; it is a scratch directory,
  not a place to accumulate.

## Key directories

```
backend/impl/          active Jellyfin/Navidrome adapters (raw OkHttp)
backend/network/       Meting-API network music (independent of NAS)
backend/weather/       OpenWeatherMap + weather radio
lyrics/                LyricsManager, LrcParser, LyricsNetworkProvider
player/                PlayerManager, PlaybackService
ui/screens/            NowPlaying, Library, Queue, Settings, ServerConnect,
                       NetworkScreen, Equalizer, *Detail, network/ subpackage
util/                  AppLog, EncodingUtils, PinyinUtils, ArtistSplitter, ...
data/model/            all data classes (Song, Album, Artist, Playlist, Genre,
                       Lyrics*, UiState, Screen, Weather*, Network*)
```


## 2026-09-09 深度审查与修复纪要

- 全量审查报告（15 项发现 + 修复明细）由审查会话产出：NASMusicTV-代码审查报告.html。本文只补正最关键的滞后信息：
- 后端已不止 Jellyfin/Navidrome：共 5 个（+Subsonic/道理鱼/飞牛）；另有百度网盘源、离线下载、K 歌（ONNX 人声分离）、MTV、天气电台、手机遥控/扫码传输等大模块
- 安全基线：TLS 已恢复系统默认证书校验（2026-09-09 移除 6 处 trust-all，勿再引入）；凭据 AES-GCM 加密存储；本地 HTTP 服务无鉴权是所有者接受的取舍（家庭局域网场景）
- 已确认的设计取舍（勿"修复"）：gradle wrapper 指向本地 file://（离线构建）、全局 usesCleartextTraffic（用户自填任意 NAS http 地址所需）、连接页默认预填开发者局域网 IP
- ServerConnectScreen 的账号密码硬编码已清除；历史提交中的密码已泄露，必须轮换
