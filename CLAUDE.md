# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**NAS Music TV** — Android TV music player (also works on phone/tablet) that aggregates multiple music sources:
- NAS backends: Jellyfin, Navidrome, Subsonic, 道理鱼, 飞牛 (with CUE sheet + HLS support)
- Online music: 5 quality tiers with auto-fallback
- Local music: Auto-scan with Room persistence
- 百度网盘: OAuth device code, directory browsing, direct streaming
- Jamendo (CC-licensed): Charts + genre filter
- Radio: Public directory with tag filtering
- Cross-source search: Parallel search with dedup

Key features: Per-character karaoke lyrics, vocal separation (ONNX Spleeter), MTV videos (Bilibili), weather radio, 30+ visualizer effects (including three-globe 3D earth via WebView+WebGL), photo wall with 43 transitions, EQ presets, queue persistence, Pinyin search.

**Package**: `com.nasmusic.tv`  
**Min SDK**: 22 (Android 5.1)  
**Target SDK**: 34  
**Architecture**: Single-module MVVM, no DI framework (manual singleton management via `NasMusicApp` as DI container)  
**UI**: Jetpack Compose for TV (`androidx.tv:tv-material`)  
**Player**: Media3 ExoPlayer 1.2.1  
**Orientation**: Locked landscape  
**Input**: D-Pad + OK (TV remote), HDMI-CEC media keys

---

## Common Development Commands

### Build

```bash
# Set JAVA_HOME (Windows example)
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Debug APK
./gradlew assembleDebug

# Release APK (requires keystore.properties with signing config)
./gradlew assembleRelease

# Build with E51 pixel city effect (local only, see §E51 Branch Context)
./gradlew assembleRelease -Plofi.city=true
# or add `lofi.city=true` to local.properties
```

### Test

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Run single test class
./gradlew testDebugUnitTest --tests "com.nasmusic.tv.player.PcmRingBufferTest"

# Run tests with pattern
./gradlew testDebugUnitTest --tests "*Visualizer*Test"

# Run lint (blocking in CI)
./gradlew lintDebug
```

### Deploy to TV

```bash
adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
# or release:
adb -s <TV_IP>:5555 install -r app/build/outputs/apk/release/NASMusicTV-release-v*.apk
```

### Local Configuration Files (gitignored)

- `keystore.properties` — signing config + `baiduAppId`/`baiduAppSecret` + `cryptoPassphrase` (AES-256 key for credential encryption)
- `local.properties` — SDK path + optional `lofi.city=true` for E51 effect

---

## High-Level Architecture

### Layer Structure

```
UI Layer (ui/)
  MainActivity → AppRoot (when currentScreen) → Screens + Components
  ViewModels: MainViewModel, PlaylistViewModel
  Theme: NASMusicTVTheme (dark/light), NasMusicColors, Type

Business Layer
  PlayerManager (singleton) — ExoPlayer wrapper, queue, playback modes, EQ
  PlaybackService (MediaLibraryService) — Foreground service, notification
  LyricsManager — Priority: MP3 embedded → local cache → local LRC → network (Kugou/NetEase)
  NetworkMusicManager — Multi-source router (MetingAPI default)
  BackendRegistry — Factory for BackendAdapter implementations

Backend Layer (backend/)
  BackendAdapter (interface) — initialize, testConnection, getAlbums/Songs/Artists, search, streamUrl, coverUrl, lyrics
  JellyfinAdapter — OkHttp, X-Emby-Token auth, /Items endpoints
  NavidromeAdapter — OkHttp, Subsonic token+salt (MD5), /rest/* endpoints
  Network music: MetingApiService (网易云源), JamendoService, RadioBrowserClient, Baidu Netdisk
  Local music: LocalMusicDatabase (Room), ItunesCoverSearcher

Data Layer (data/)
  Models: Song, Album, Artist, Lyrics, PlayMode, AppSettings, ServerConfig, etc.
  Prefs: AppPreferences (DataStore Preferences), various *Prefs.kt files
  Room: LocalMusicDatabase (exportSchema=true → app/schemas/)

Visualizer (visualizer/)
  AudioFrame, BeatDetector, SpectrumRepository, SpectrumContract
  Renderers: 30+ effects (hypnotic, world map, particle, formula, etc.)
  E51 Pixel City: visualizer/e51/engine/ + visualizer/e51/scenes/ (private branch only)

Utils (util/)
  PinyinUtils, TimeUtils, ArtistSplitter, AppLog, CryptoUtils, EncodingUtils, RetryUtil, NetworkMonitor, MediaKeyHandler
```

### Key Design Decisions

- **No DI framework**: `NasMusicApp` holds singleton instances; `getInstance()` removed in v2.2.0
- **State ownership**: `PlayerManager` is source of truth for playback state; `MainViewModel` mirrors via `StateFlow`
- **Manual navigation**: `when(currentScreen)` in `AppRoot`, no Navigation Component
- **Backend decoupling**: `BackendAdapter` interface + `BackendRegistry` factory
- **Async state**: `UiState<T>` sealed class (Loading/Success/Error) replaces ad-hoc `isLoading`/`errorMessage`
- **Progress update**: Single path via `Handler` (1000ms) in `PlayerManager`; ViewModel coroutine path removed
- **Encryption**: `CryptoUtils` — AES-256-GCM + Android Keystore for `password`/`apiToken` in DataStore
- **Daemon threads**: All OkHttp clients use `isDaemon=true` thread pools to allow clean process exit
- **16KB page alignment**: ONNX Runtime pinned at 1.17.1 (minSdk 22 constraint); upgrading requires minSdk 24

---

## Key Patterns & Conventions

### Code Organization
- Many small files > few large files (200-400 lines typical, 800 max)
- Organized by feature/domain, not by type
- Extensive use of `data class` for immutable models

### Error Handling
- Backend adapters: `try/catch (e: Exception) {}` returning empty list/null (no error typing)
- Network calls: `RetryUtil.withRetry(config)` with exponential backoff
- UI: `UiState<T>` for async operations

### Testing
- Unit tests: JUnit 4 + Mockito + Robolectric + MockWebServer
- Test logging: `exceptionFormat = FULL` in `build.gradle.kts` for readable failures
- Coverage target: Not explicitly enforced but tests exist for utilities, parsers, visualizer math, network logic
- Run single test: `./gradlew testDebugUnitTest --tests "com.nasmusic.tv.util.PinyinUtilsTest"`

### Visualizer Effects
- Each effect implements renderer interface via `VisualizerRendererFactory`
- Themes defined in `VisualizerTheme` enum (controls available effects in UI)
- Quality tiers: HIGH/MEDIUM/LOW map to effect-specific performance settings
- Three-globe effects use WebView + WebGL (offline three.js bundle)

### Settings Persistence
- DataStore Preferences (`nas_music_tv.preferences_pb`)
- Encrypted fields: `apiToken`, `password` via `CryptoUtils`
- Schema export: Room schemas in `app/schemas/` (commit with version bumps)

---

## Git Workflow

### Branches
- `main` — Default branch, protected
- `develop` — Development branch
- Feature branches: `feat/<name>` (e.g., `feat/e51-private` for pixel city)
- Tags: `v*` for releases

### Commit Format
```
<type>: <description>

<optional body>
```
Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`

### Version Management
Update **both** files in sync:
1. `app/build.gradle.kts` — `versionCode` / `versionName`
2. `app/src/main/java/com/nasmusic/tv/NasMusicVersion.kt` — `VERSION_CODE` / `VERSION_NAME`

Update `CHANGELOG.md` (Added/Changed/Fixed/Removed) and `docs/technical-overview.md` for architectural changes.

### CI Pipeline (`.github/workflows/build.yml`)
- **build** job: Release APK, upload artifact, create GitHub Release on tag push
- **test** job: Unit tests with Gradle/Robolectric caching, failure messages dumped + XML artifact on failure
- **lint** job: Blocking (errors=0), warnings allowed (~256)

**Signing**: 
- Push/tag → requires 5 GitHub Secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`, `CRYPTO_PASSPHRASE`) — fail-fast if missing
- PR only → throwaway CI keystore (APK not installable over release-signed)

---

## E51 Branch Context (feat/e51-private)

> 📌 **2026-11-09 更新（分支使用模式）**：主目录 `NASMusicTV` 在 `main` ⇄ `feat/e51-private` 之间直接 `git checkout` 切换（单目录双分支，所有者确认）；独立 worktree `NASMusicTV-private` 已于 2026-11-09 删除（删除前全量备份在 `logs_temp/backup_private_dirs_2026-11-09/`），`git worktree list` 只剩主目录一条。两个分支的本地构建都带真 E51/E52 效果；改完私有分支源码必须跑 `scripts/sync_private_overlay.py` 并用 `--check` 验收 0 漂移。完整机制与纪律见 `AGENTS.md`「分支布局：单目录双分支」一节。下方 Implementation Status 是 2026-10-22 历史快照，数字已过时。

**Current branch（2026-10-22 快照）**: `feat/e51-private` — Pixel City (LoFi Cities) visualizer effect port

### Critical Constraints (Owner Decision 2026-10-22)
1. **Porting allowed locally only** — Line-by-line translation of upstream JS permitted
2. **NO PUSH TO REMOTE** — Source code must never enter GitHub (public repo, GPL v3 would claim rights over unlicensed code)
3. **No release/tag with E51 code** — Applies to commits, tags, releases, any remote upload
4. **Public APK must not contain effect** — Controlled by `BuildConfig.LOFI_CITY_ENABLED` (default `false` in CI)

### Implementation Status (as of 2026-10-22)
- ✅ P1 Engine + first city + dual-variant switch (`494a25a`)
- ✅ P2 Globe transition + P3 three cities (`2b6d105`)
- ⏸️ Batch porting of 57 cities paused after 12 cities (owner decision)
- 📁 Files in `visualizer/e51/` (engine: 22 files, scenes: 23 city files + registry)
- 🔒 Source code isolated in `feat/e51-private` branch (no upstream), NOT in `main`

### Dual-Variant Build
- **Local**: `local.properties` add `lofi.city=true` → effect included in APK
- **CI**: No `-Plofi.city=true` → `BuildConfig.LOFI_CITY_ENABLED=false` → effect excluded from public APK
- Two gate points (both required):
  1. `VisualizerTheme.selectable()` filters out `LOFI_CITY` when disabled
  2. `VisualizerRendererFactory.create()` falls back to `Default` renderer when disabled
- Test gate: `LofiCityVariantGateTest` asserts CI default is `false`

### Files That Are E51 Porting Artifacts (Do Not Push)
```
visualizer/e51/engine/**           // Pixel engine translation
visualizer/e51/scenes/LofiCity*.kt // City scene translations
visualizer/e51/engine/CityRegistry.kt // Upstream registry data
visualizer/renderers/LofiCityRenderer.kt // Wiring
ui/components/LofiCityCard.kt           // UI card
VisualizerTheme.LOFI_CITY               // Enum entry
app/src/test/**/Lofi*Test.kt            // Tests
docs/archive/e51-lofi-city-plan.md              // Plan doc (contains upstream details)
scripts/lofi_city_registry_sync.py      // Sync script (parses upstream bundle)
```

### If You Need to Push Other Changes from This Branch
**Do NOT push directly** — E51 commits are interleaved with other features in history. Owner must explicitly authorize history rewrite (rebase/cherry-pick) to extract non-E51 commits. See `docs/archive/e51-lofi-city-plan.md §10 D-m` and `§2.6`.

---

## Important Files to Know

| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Build config, dependencies, version, signing, schema export, test logging |
| `docs/technical-overview.md` | Complete architecture reference (this file's source of truth) |
| `app/src/main/java/com/nasmusic/tv/NasMusicVersion.kt` | Version constants for UI |
| `app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt` | Playback state & control (singleton) |
| `app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt` | Entry point, navigation, BACK handling |
| `app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt` | Screen dispatcher |
| `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt` | Backend interface |
| `app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt` | Backend factory |
| `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt` | DataStore wrapper (singleton) |
| `app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt` | AES-256-GCM + Keystore encryption |
| `app/src/main/java/com/nasmusic/tv/visualizer/VisualizerRendererFactory.kt` | Effect renderer factory |
| `docs/visualizer-effects-list.md` | Live effects registry (must be updated when a theme is added/removed) |

---

## Common Tasks

### Add New Visualizer Effect
1. Create renderer in `visualizer/renderers/`
2. Add to `VisualizerRendererFactory.create()`
3. Add enum entry in `VisualizerTheme` (with `selectable()` logic if conditional)
4. Add strings in `strings.xml` (both zh/en)
5. Update `VisualizerThemeStringsGateTest` key count
6. **Update `docs/visualizer-effects-list.md` in the same change** (owner rule, 2026-11-10) — that file is the
   **live effects registry**: add the row to its tier table, update the counts (total / per-tier /
   "public build selectable"), and mark compile-time-gated effects with ⚠️ + the switch name.
   ⛔ It is **hand-maintained** — do **not** run `scripts/gen_effects_doc.py` (its template is stale and
   would wipe the ⚠️ notes and the gating wording).

### Add New Backend
1. Implement `BackendAdapter` interface
2. Register in `BackendRegistry.initialize()`
3. Add backend type to `BackendType` enum
4. Add UI in `ServerConnectScreen.kt`

### Modify Data Model
1. Update `data/model/*.kt`
2. Bump Room version if entity changed → new schema JSON in `app/schemas/`
3. Update `FILE_FORMAT_VERSION` if DataStore format breaks backward compatibility

### Debug Build Issues
- Check `keystore.properties` exists for release builds
- `cryptoPassphrase` required for release (guard in `build.gradle.kts:163-175`)
- ONNX Runtime version locked — don't upgrade without minSdk 24 analysis

---

## References

- **Technical Overview**: `docs/technical-overview.md` (authoritative architecture doc)
- **E51 Plan**: `docs/archive/e51-lofi-city-plan.md` (private branch context, copyright constraints)
- **Regression Tests**: `docs/archive/regression-test.md` (248 test scenarios)
- **Upstream LoFi Cities**: https://loficities.com/ (Safa Elmali, commercial, no open license)