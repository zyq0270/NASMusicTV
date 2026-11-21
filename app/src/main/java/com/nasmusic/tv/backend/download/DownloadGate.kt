package com.nasmusic.tv.backend.download

/**
 * 下载总开关的进程级快照（供歌曲行的 ⬇ 入口同步读取）。
 *
 * 单一写入者：`NasMusicApp` 的设置收集器（`AppSettings.downloadEnabled`）。
 * 读方（`UnifiedSongRow` 等）只读，⛔ 不要回写 —— 真相仍在 `AppPreferences`。
 *
 * 为什么需要它：歌曲行分散在曲库 / 专辑 / 艺人 / 我的 / 天气电台等多个分支，
 * 把 `downloadEnabled` 逐层透传到每个 `UnifiedSongRow` 会改动 20+ 个调用点；
 * 而这里要表达的只是「入口要不要渲染」，与 FocusVisualPreference 同类。
 */
object DownloadGate {
    @Volatile
    var enabled: Boolean = true
}