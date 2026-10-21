package com.nasmusic.tv.data.model

/**
 * 主题模式（三态）：跟随系统 / 深色 / 浅色。
 *
 * 存储用 [key]（`settings_theme_mode`），备份 JSON 用枚举名（Gson 默认行为）。
 *
 * ⚠️ **声明顺序不可随意调整**：`backupGson` 的容错枚举适配器在遇到无法识别的名字时
 * 回落到「首个常量」，这里首个常量必须是 [SYSTEM]（新装默认值）。详见 `BackupGson.kt`。
 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light"),
    ;

    companion object {
        val Default: ThemeMode = SYSTEM

        /** 未知/缺失一律回落 [Default]（与各枚举 `fromKey` 的既有约定一致） */
        fun fromKey(key: String?): ThemeMode =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: Default
    }
}

/**
 * 存储值 + 旧布尔值 → 有效模式（**迁移与备份导入共用的唯一真源**）。
 *
 * - 新键存在 → 以新键为准（未知名字已由 [ThemeMode.fromKey] 回落）
 * - 新键缺失 → 旧布尔 `true` = 深色、`false` = 浅色（老用户显式选择必须保留）
 * - 两者都缺失 → 跟随系统
 *
 * ⚠️ 调用方**不能**先 `fromKey(...)` 再传进来：`fromKey(null)` 会回落 `SYSTEM`，
 * 从而把「旧键是 false（用户选过浅色）」误判成「跟随系统」。必须先判 null。
 */
fun resolveThemeMode(stored: ThemeMode?, legacyDark: Boolean?): ThemeMode =
    stored ?: when (legacyDark) {
        true -> ThemeMode.DARK
        false -> ThemeMode.LIGHT
        null -> ThemeMode.SYSTEM
    }

/**
 * 模式 + 系统 + 设备 → 是否深色。
 *
 * TV 的 [ThemeMode.SYSTEM] **固定解析为深色**：电视的 night-mode 上报不可靠，
 * 且历史行为恒为深色（所有者裁决 D5）。
 */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean, isTv: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> if (isTv) true else systemDark
}
