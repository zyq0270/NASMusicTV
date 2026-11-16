# NAS Music TV

[简体中文](README.md) | English

An open-source Android music player (works on TV / phone / tablet alike): aggregates NAS (Jellyfin / Navidrome / Subsonic / Daoliyu / FNOS), online music, Baidu Netdisk, and local music into one library, with per-character karaoke lyrics, K-song backing tracks, MTV music videos, weather radio, and other TV-first experiences. A single APK adapts to both TV (remote control) and phone (touchscreen).

[![Release](https://img.shields.io/github/v/release/hxzhang2000/NASMusicTV?color=blue&label=Release)](https://github.com/hxzhang2000/NASMusicTV/releases)

Product homepage: https://zd7gxll02-nasmusictv-10j3m3sd.maozi.io/

> ⚠️ **Baidu Netdisk directory restriction**: Per Baidu Open Platform's 2026 sandbox policy, third-party apps can only access the `/apps/NASMusicTV` directory and its subdirectories — **no files outside it can be read**. Please place your music files under `/apps/NASMusicTV/` in your Baidu Netdisk (e.g. `/apps/NASMusicTV/Music/`). The app creates this directory automatically on first use. Files outside `/apps/NASMusicTV/` are inaccessible to this app.

## Features

### Multiple music sources

- **NAS**: Jellyfin / Navidrome / Subsonic / Daoliyu / FNOS backends, with CUE single-file albums and HLS streaming
- **Online music**: search and play, 5 quality tiers (auto / 128 / 192 / 320 / lossless FLAC) with automatic fallback when a tier is unavailable; artist hits can be paginated to fetch the artist's full discography
- **Local music**: automatic scanning of device and USB / SD storage, Room-indexed persistence, browsable in the same view as NAS music
- **Baidu Netdisk**: device-code OAuth authorization, directory browsing, search, direct-link streaming
- **Independent music (Jamendo)**: CC-licensed library, top charts + genre filters
- **Radio**: public radio directory, tag filtering, instant live-stream playback
- **Cross-source search**: NAS / online / Baidu / Jamendo independently toggleable, parallel search with merged deduplication

### Playback experience

- **Playback control**: sequential / repeat-one / repeat-all / shuffle modes, queue add-remove-reorder with sorting, queue persistence (auto-restored on restart)
- **Audio quality**: current tier or real bitrate shown on the player page, switchable at any time for online songs, per-song tier override
- **In-app volume**: independent of system volume (0–100%), adjustable from both settings and player pages, auto-adapted to cross-fade
- **Equalizer & visualizer**: equalizer presets and band adjustment; full-screen immersive visualizer with 30+ effects (including the three-globe 3D Earth edition "World", rendered fully offline via WebView + WebGL, referencing [three-globe](https://github.com/vasturiano/three-globe) and [three.js](https://github.com/mrdoob/three.js)); visualizer quality selectable in low/medium/high tiers by device performance
- **Photo wall visualizer**: full-screen slideshow from gallery / external storage (USB, SD card) / Jellyfin, 43 random transitions, optional "portraits only" filter
- **K-song**: vocal-elimination backing track, original / accompaniment switching, pitch shift, playback speed
- **MTV music videos**: one tap to full-screen MV (Bilibili search) from the player page, auto pre-search on track change
- **Weather radio**: mood radio matched from your library by real-time weather (sunny / rain / snow / windy / cloudy / night), works without a NAS
- **Playback statistics**: play counts and last-played timestamps drive the "Recently played" view

### Lyrics

- Line-by-line scrolling with **per-character highlighting** (karaoke effect), large-screen optimized
- Multiple sources: embedded / local LRC / network match / cache, switchable
- Font scaling and highlight mode memory

### Library browsing

- Search: pinyin initials (`zjl` → 周杰伦) + substring matching
- Discover page: six-dimension filters — language / instrumental / decade / nostalgia / genre / theme
- Album / artist / genre / decade grid browsing with detail pages
- Favorites, recently played, custom playlists
- Playlist import: M3u / NetEase / JSON / TXT, with in-playlist HTTP direct links playable directly

### TV experience

- Complete D-Pad focus system and remote optimizations, HDMI-CEC media key support
- **Phone remote**: QR code shown on K-song / MTV full-screen pages; scan with your phone to view the queue and search-and-add songs
- Foreground media notification and lock-screen controls

### Phone experience

- One APK, both form factors: runtime device detection — TV uses top navigation + focus system, phone uses touch interaction
- All pages adapt to portrait / landscape: bottom navigation + mini player bar + cover ⟷ lyrics swipe on the player page
- Touch-drag seek on progress bar, responsive library grid, press feedback
- Lock-screen and notification media controls, Android Auto / Wear OS media browsing

## Screenshots

| Home | Player |
|:---:|:---:|
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/01-dashboard.jpg" width="440" alt="Home"> | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/02-player.jpg" width="440" alt="Player"> |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/03-karaoke.jpg" width="440" alt="Per-character karaoke lyrics"><br>**Per-character karaoke lyrics** | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/04-mtv.jpg" width="440" alt="MTV music video"><br>**MTV full-screen music video** |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/05-visualizer.jpg" width="440" alt="Full-screen visualizer"><br>**Full-screen visualizer**: 30+ effects | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/06-library.jpg" width="440" alt="Library"><br>**Library**: albums / artists / songs / genres / decades |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/07-discover.jpg" width="440" alt="Discover"><br>**Discover**: six-dimension filters | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/08-search.jpg" width="440" alt="Search"><br>**Search**: pinyin initials + substring |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/09-weather-radio.jpg" width="440" alt="Weather radio"><br>**Weather radio**: mood matched to real-time weather | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/10-detail.jpg" width="440" alt="Album detail"><br>**Album detail** |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/11-queue.jpg" width="440" alt="Play queue"><br>**Play queue**: add / remove / reorder | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/12-mine.jpg" width="440" alt="Mine"><br>**Mine**: favorites / recent / local playlists |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/13-settings.jpg" width="440" alt="Settings"><br>**Settings**: theme / equalizer / lyrics scaling / source endpoints | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/14-connection.jpg" width="440" alt="Connection"><br>**Connection**: five NAS backends |
| <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/15-about.jpg" width="440" alt="About"><br>**About**: backend type + API version + connection status | <img src="https://cdn.jsdelivr.net/gh/hxzhang2000/NASMusicTV@main/screenshots/16-remote.jpg" width="220" alt="Phone remote"><br>**Phone remote**: scan the QR code on K-song / MTV pages to view the queue and add songs from your phone |

## Download & install

**Requirements**: Android 5.1 (API 22) or later; one APK for TV / phone / tablet. The app detects the device type at runtime — TV uses the remote focus system, phone uses touch interaction. Older ARMv7 TVs are supported too, with high-load effects automatically downgraded.

**Download**: `app-release.apk` from the [Releases](https://github.com/hxzhang2000/NASMusicTV/releases) page.

**Install on TV**: copy the APK to a USB drive and install with a file manager; or install wirelessly via adb:

```bash
adb connect <TV_IP>:5555
adb -s <TV_IP>:5555 install -r app-release.apk
```

**Install on phone / tablet**: install the APK directly (allow "install unknown apps" in system settings).

**First run**: go to the "Connection" page and add your NAS backend (Jellyfin / Navidrome / Subsonic / Daoliyu / FNOS). Without a NAS you can still use online music, Baidu Netdisk, public radio, and weather radio.

## Build & run

```bash
# Set JAVA_HOME
export JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# Run tests
./gradlew test

# Push to TV (replace 192.168.0.116 with your TV's IP)
adb -s 192.168.0.116:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

## Known limitations

- **TV photo wall cannot read internal-storage photos** (both platforms uniformly read only external volumes / Jellyfin / gallery): to show photos from your phone or PC on TV, copy them to a USB drive / SD card and select that volume under "Settings → Photo wall → External storage"
- **"Portraits only" is unavailable on low quality tier** (a performance trade-off on old ARMv7 devices; the setting is auto-disabled)
- The face-detection model and result database are independent of the music library (`photo_face.db`); "clear face results" only deletes detection results, not photos

## Star History

<a href="https://www.star-history.com/?repos=hxzhang2000%2Fnasmusictv&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=hxzhang2000/nasmusictv&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=hxzhang2000/nasmusictv&type=date&theme=light&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=hxzhang2000/nasmusictv&type=date&legend=top-left" />
 </picture>
</a>

## License

GPL v3
