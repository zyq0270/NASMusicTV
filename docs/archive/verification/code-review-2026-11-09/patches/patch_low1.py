#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Low 批次 1：L01 / C01 / L02 / L03 / L04 / L05"""
import io, sys

FAILS = []

def expect(tag, cond, extra=''):
    if not cond:
        FAILS.append(tag)
        print('FAIL', tag, extra)

def load(rel):
    raw = io.open(rel, 'r', encoding='utf-8', newline='').read()
    crlf = raw.count('\r\n')
    eol = '\r\n' if crlf > (raw.count('\n') - crlf) else '\n'
    return raw.replace('\r\n', '\n'), eol

def save(rel, S, eol):
    out = S.replace('\n', eol) if eol == '\r\n' else S
    io.open(rel, 'w', encoding='utf-8', newline='').write(out)
    print('OK', rel, 'crlf' if eol == '\r\n' else 'lf')

# ================= L01 =================
REL = '.github/workflows/build.yml'
S, eol = load(REL)
old = """          echo "=== keystore.properties (passwords redacted) ==="
          sed 's/\\(storePassword\\|keyPassword\\)=.*/\\1=<redacted>/' keystore.properties"""
new = """          echo "=== keystore.properties (secrets redacted) ==="
          # L01：cryptoPassphrase 也是密钥（备份加解密的口令），原实现只打码两个 password，
          # 注释却写 "passwords redacted" ⇒ CI 日志里会明文出现 passphrase。
          sed -E 's/(storePassword|keyPassword|cryptoPassphrase)=.*/\\1=<redacted>/' keystore.properties"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L01-body', False, 'anchor not found')
expect('L01-redact', 'cryptoPassphrase)=.*/' in S)
if FAILS:
    print('NOT WRITTEN(1)'); sys.exit(1)
save(REL, S, eol)

# ================= C01 / L02 =================
REL = 'app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt'
S, eol = load(REL)

old = """        dataStore.edit { prefs ->
            prefs[keyNetworkFavorites] = gson.toJson(data.networkFavorites)
            prefs[keyLocalPlaylists] = gson.toJson(data.localPlaylists)
"""
new = """        dataStore.edit { prefs ->
            // L02：口径统一为「备份里没有的就别动本机」。
            // 同一个函数里 playlistImportHistory / songReachability / playRecords / searchHistory
            // 都带 isNotEmpty() 保护，而收藏 / 歌单 / 最近播放 / 播放次数是无条件回写 ⇒
            // 一份没带这些字段（或用户清空后导出）的备份会把本机数据整片抹掉，
            // 且 UI 全程没有任何「将替换全部数据」的提示。
            if (data.networkFavorites.isNotEmpty()) {
                prefs[keyNetworkFavorites] = gson.toJson(data.networkFavorites)
            }
            if (data.localPlaylists.isNotEmpty()) {
                prefs[keyLocalPlaylists] = gson.toJson(data.localPlaylists)
            }
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L02-fav', False, 'anchor not found')

old = """            prefs[keyRecentSongs] = gson.toJson(data.recentSongIds)
            prefs[keyRecentSongObjects] = gson.toJson(
                RecentSongObjectsData(data.recentSongObjects.map { it.stripVolatileStreamUrl() })
            )
            prefs[keyPlayCounts] = gson.toJson(data.playCounts)
"""
new = """            if (data.recentSongIds.isNotEmpty()) {
                prefs[keyRecentSongs] = gson.toJson(data.recentSongIds)
            }
            if (data.recentSongObjects.isNotEmpty()) {
                prefs[keyRecentSongObjects] = gson.toJson(
                    RecentSongObjectsData(data.recentSongObjects.map { it.stripVolatileStreamUrl() })
                )
            }
            if (data.playCounts.isNotEmpty()) {
                prefs[keyPlayCounts] = gson.toJson(data.playCounts)
            }
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L02-recent', False, 'anchor not found')

old = """            data.equalizerPreset?.let { prefs[keyEqualizerPreset] = it.ordinal }
            if (data.equalizerBands.isNotEmpty()) {
                prefs[keyEqualizerBands] = gson.toJson(data.equalizerBands)
            }
"""
new = """            data.equalizerPreset?.let { prefs[keyEqualizerPreset] = it.ordinal }
            // C01：均衡器频段必须逐元素校验 —— 备份 JSON 可以手改，长度/取值都不受控；
            // 而 `setEqualizerBands` 本身不做校验就直写 DataStore。
            // NaN 会直接让 gson.toJson 抛异常，越界值会让 PlayerEqualizer 的 EQ 打到失真。
            // 约定：丢弃非有限值、钳到 [-10, 10] dB（与 EqualizerScreen 的滑杆范围一致）、
            // 最多保留 10 个频段。
            val cleanBands = data.equalizerBands
                .filter { it.isFinite() }
                .map { it.coerceIn(-10f, 10f) }
                .take(MAX_EQUALIZER_BANDS)
            if (cleanBands.isNotEmpty()) {
                prefs[keyEqualizerBands] = gson.toJson(cleanBands)
            }
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C01-bands', False, 'anchor not found')

old = """            prefs[keyCoverFilterEnabled] = data.coverFilterEnabled
            prefs[keyCoverFilterBlurRadius] = data.coverFilterBlurRadius
            prefs[keyCoverFilterDarkOverlay] = data.coverFilterDarkOverlay
            if (data.musicSource.isNotBlank()) prefs[keyMusicSource] = data.musicSource
            prefs[keyLyricsFontScale] = data.lyricsFontScale"""
new = """            prefs[keyCoverFilterEnabled] = data.coverFilterEnabled
            // C01：这四个数值字段在正常路径上都由 UI 约束，但**恢复路径绕过了 UI**。
            // NaN/Inf 一旦落进 DataStore，消费端（blur 半径、alpha、字号）会画出整屏空白；
            // 越界值则会让「点两次 + 就恢复不了」的怪现象出现。统一 isFinite + coerceIn。
            prefs[keyCoverFilterBlurRadius] =
                finiteIn(data.coverFilterBlurRadius, 0f, COVER_BLUR_MAX, 0f)
            prefs[keyCoverFilterDarkOverlay] =
                finiteIn(data.coverFilterDarkOverlay, 0f, 1f, 0.3f)
            if (data.musicSource.isNotBlank()) prefs[keyMusicSource] = data.musicSource
            prefs[keyLyricsFontScale] = finiteIn(data.lyricsFontScale, 0.7f, 1.6f, 1.0f)"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C01-scalars', False, 'anchor not found')

# 辅助常量与函数：放在 nameOrDefault 那几个扩展函数之前
old = """/** 可视化主题：null → [VisualizerTheme.Default]（`CIRCULAR_RING`） */"""
new = """/** 均衡器最多保留的频段数（C01：与 EqualizerScreen 的 10 段滑杆一致） */
private const val MAX_EQUALIZER_BANDS = 10

/** 封面滤镜模糊半径上限（C01：与设置页「+」按钮的 40px 封顶一致） */
private const val COVER_BLUR_MAX = 40f

/**
 * C01：恢复路径专用的数值兜底 —— 非有限值（NaN / ±Inf）落到 [fallback]，其余钳到区间。
 *
 * ⚠️ 不能只写 `coerceIn`：`Float.NaN.coerceIn(a, b)` 返回 NaN（比较恒 false ⇒ 原样返回），
 * 而 NaN 会让 `gson.toJson` 的 `doubleToLongBits` 路径直接抛 IllegalArgumentException，
 * 从而让整份备份导入失败。
 */
private fun finiteIn(value: Float, min: Float, max: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(min, max) else fallback

/** 可视化主题：null → [VisualizerTheme.Default]（`CIRCULAR_RING`） */"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('C01-helpers', False, 'anchor not found')

expect('C01-use', S.count('finiteIn(') == 4, 'n=%d' % S.count('finiteIn('))

if FAILS:
    print('NOT WRITTEN(2)'); sys.exit(1)
save(REL, S, eol)

# ================= L03 =================
REL = 'app/src/main/java/com/nasmusic/tv/player/ModelDownloadManager.kt'
S, eol = load(REL)

old = """        return try {
            AppLog.d(TAG, "tryDownloadUrl: starting download from $urlStr")

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "NASMusicTV/2.22")
            connection.connect()
"""
new = """        // L03：HttpURLConnection 必须在 finally 里 disconnect —— 原实现只是在用完后让
        // inputStream 关掉，连接仍留在 keep-alive 池里；连续换镜像重试时会累积到超时。
        var connection: HttpURLConnection? = null
        return try {
            AppLog.d(TAG, "tryDownloadUrl: starting download from $urlStr")

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "NASMusicTV/2.22")
            conn.connect()
"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L03-head', False, 'anchor not found')

old = """            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.e(TAG, "tryDownloadUrl: HTTP ${connection.responseCode}")
                return context.getString(R.string.model_download_http_error, connection.responseCode)
            }

            val totalBytes = connection.contentLength.toLong()
            AppLog.d(TAG, "tryDownloadUrl: total size = ${totalBytes / (1024 * 1024)}MB")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, true).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // 回调进度（每 512KB 或完成时）
                        if (downloadedBytes % (512 * 1024) < bytesRead || downloadedBytes == totalBytes) {
                            onProgress(downloadedBytes, if (totalBytes > 0) totalBytes else EXPECTED_SIZE_BYTES)
                        }
                    }
                }
            }
            null"""
new = """            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.e(TAG, "tryDownloadUrl: HTTP ${conn.responseCode}")
                return context.getString(R.string.model_download_http_error, conn.responseCode)
            }

            val totalBytes = conn.contentLength.toLong()
            AppLog.d(TAG, "tryDownloadUrl: total size = ${totalBytes / (1024 * 1024)}MB")

            var downloadedBytes = 0L
            var lastReportedBytes = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tempFile, true).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // 回调进度（每 512KB 一次）
                        // L03：原判据里还有 `|| downloadedBytes == totalBytes`，本意是「收尾补报」，
                        // 但当服务端不给 Content-Length（chunked / 镜像站）时 totalBytes = -1，
                        // 这个等式永远不成立 ⇒ 最后一段不足 512KB 的数据不会报，
                        // 进度条停在半路直到 sha256 校验完才被强行拉满。
                        if (downloadedBytes - lastReportedBytes >= 512 * 1024) {
                            lastReportedBytes = downloadedBytes
                            onProgress(downloadedBytes, if (totalBytes > 0) totalBytes else EXPECTED_SIZE_BYTES)
                        }
                    }
                }
            }
            // 收尾补报：total 已知则等于 total（100%），未知则用已下载量（同样 100%）。
            onProgress(downloadedBytes, if (totalBytes > 0) totalBytes else downloadedBytes)
            null"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L03-body', False, 'anchor not found')

old = """            AppLog.e(TAG, "tryDownloadUrl: failed from $urlStr", e)
            msg
        }
    }"""
new = """            AppLog.e(TAG, "tryDownloadUrl: failed from $urlStr", e)
            msg
        } finally {
            runCatching { connection?.disconnect() }
        }
    }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L03-finally', False, 'anchor not found')

expect('L03-noleak', 'connection?.disconnect()' in S)

if FAILS:
    print('NOT WRITTEN(3)'); sys.exit(1)
save(REL, S, eol)

# ================= L04 =================
REL = 'app/src/main/java/com/nasmusic/tv/player/SpectrumAnalyzer.kt'
S, eol = load(REL)
old = """    /**
     * 释放资源：停用 PCM 通道并清空缓冲。
     */
    fun release() {
        pcmFallback?.deactivate()"""
new = """    /**
     * 释放资源：停用 PCM 通道并清空缓冲。
     *
     * L04：本方法与 [analyze] 会并发执行（FFT 线程在 [PcmSpectrumTap] 上跑，release 走主线程），
     * 而 [analyze] 是 `@Synchronized`、本方法原先**没有加锁** ⇒ 清空 barBuf/displayBuf 的同时
     * analyze 正在写同一批数组，会读到半清零数据（柱子偶尔全 0 闪一下），
     * 更糟的是 `repository?.reset()` 与 analyze 的 `repository?.push(...)` 竞态。
     * 两处都用实例监视器即可（`emitSilence` 与 `onPlaybackChanged` 已在同一把锁上）。
     */
    @Synchronized
    fun release() {
        pcmFallback?.deactivate()"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L04', False, 'anchor not found')
if FAILS:
    print('NOT WRITTEN(4)'); sys.exit(1)
save(REL, S, eol)

# ================= L05 =================
REL = 'app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt'
S, eol = load(REL)
old = """                if (bitmap != null && isActive &&
                    mediaLibrarySession?.player?.currentMediaItem?.mediaId == originMediaId
                ) {
                    cachedArtworkBitmap = bitmap
                    // 重置去重状态，强制下次 updateNotification 重建通知（含新封面）
                    lastNotificationState = null
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotification()
                    }
                }"""
new = """                if (bitmap != null && isActive &&
                    mediaLibrarySession?.player?.currentMediaItem?.mediaId == originMediaId
                ) {
                    // L05：旧封面 bitmap 不回收会随切歌一直累积（256×256 ARGB_8888 ≈ 256KB/首，
                    // `allowHardware(false)` 的位图不参与 GC 的 native 内存管理，长时间连续播放
                    // 会稳定涨到几十 MB）。
                    // ⚠️ 但**不能**在赋值前就 recycle：当前正在展示的通知仍持有它，
                    //    中间若插进一次 updateNotification，就会拿到已回收对象。
                    // 顺序固定为：先换引用 → 重建通知（用的是新 bitmap）→ 再回收旧的。
                    val previous = cachedArtworkBitmap
                    cachedArtworkBitmap = bitmap
                    // 重置去重状态，强制下次 updateNotification 重建通知（含新封面）
                    lastNotificationState = null
                    serviceScope.launch(Dispatchers.Main) {
                        updateNotification()
                        if (previous != null && previous !== bitmap && !previous.isRecycled) {
                            previous.recycle()
                        }
                    }
                }"""
if old in S:
    S = S.replace(old, new, 1)
else:
    expect('L05', False, 'anchor not found')
expect('L05-recycle', 'previous.recycle()' in S)
if FAILS:
    print('NOT WRITTEN(5)'); sys.exit(1)
save(REL, S, eol)
print('ALL OK')
