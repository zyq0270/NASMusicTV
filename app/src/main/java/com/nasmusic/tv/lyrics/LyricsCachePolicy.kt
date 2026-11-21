package com.nasmusic.tv.lyrics

/**
 * 歌词持久缓存开关的进程级快照。
 *
 * 单一写入者：`NasMusicApp` 的设置收集器（`AppSettings.cacheLyrics`）。
 * 读写方：`LyricsPersistentCache` 在 `get` / `put` 前同步读一次。
 *
 * 为什么不在 `LyricsPersistentCache` 里注入 `AppPreferences`：
 * 该类是进程级单例、无 ViewModel 依赖，且被备份链路直接构造读取；
 * 注入 Flow 会把「缓存读」变成 suspend，进而污染 `LyricsManager.getLyrics`
 * 的同步调用点。用 volatile 快照与 `FocusVisualPreference` 同范式，开销为零。
 */
object LyricsCachePolicy {
    @Volatile
    var enabled: Boolean = true
}