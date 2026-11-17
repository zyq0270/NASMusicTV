package com.nasmusic.tv.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * R-4 天气域子 Prefs（含 F-7 加密键；键不迁移）。
 */
class WeatherPrefs internal constructor(private val prefs: AppPreferences) {

    val weatherApiKey: Flow<String> = prefs.weatherApiKey

    suspend fun setWeatherApiKey(key: String) = prefs.setWeatherApiKey(key)
    fun getWeatherApiKeySync(): String = prefs.getWeatherApiKeySync()

    /** 腾讯位置服务 key（§10.409）：留空 ⇒ IP 定位直接走 ip.sb */
    val tencentMapKey: Flow<String> = prefs.tencentMapKey

    suspend fun setTencentMapKey(key: String) = prefs.setTencentMapKey(key)
    fun getTencentMapKeySync(): String = prefs.getTencentMapKeySync()
}
