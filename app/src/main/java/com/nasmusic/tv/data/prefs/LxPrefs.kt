package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * 洛雪音源域子 Prefs（方案 §8）。
 *
 * 薄委托范式（参照 [NetworkMusicPrefs] / [PhotoWallPrefs]）：键与读写逻辑在 [AppPreferences]。
 *
 * ⚠️ 开关切换**必须清直链缓存**——洛雪不是 service，切它不会触发任何 `unregisterService`，
 * 而它取到的直链缓存 key 是 `meting:<networkId>:<quality>`（`Song.networkSource` 仍是 `meting`）。
 * 清缓存的动作在 `MainViewModel.setLxEnabled`，⛔ 不在这里做（data/prefs 不反向依赖 backend）。
 */
class LxPrefs internal constructor(private val prefs: AppPreferences) {

    val sourcesJsonFlow: Flow<String?> = prefs.lxSourcesJsonFlow

    suspend fun setEnabled(enabled: Boolean) = prefs.setLxEnabled(enabled)

    suspend fun setSourcesJson(json: String) = prefs.setLxSourcesJson(json)

    fun isEnabledSync(): Boolean = prefs.getLxEnabledSync()
}
