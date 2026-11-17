package com.nasmusic.tv.backend.weather

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GCJ-02（火星坐标）→ WGS84 的近似逆变换。
 *
 * 腾讯位置服务的 IP 定位返回 GCJ-02，而 Open-Meteo 只认 WGS84。
 * 这里用标准的「正算偏移一次反减」近似：城市级天气查询的误差在米级，
 * 远小于 IP 定位本身的公里级误差，不值得做多轮迭代。
 *
 * ⚠️ 境外坐标原样返回（GCJ 变换只对中国境内定义）。
 */
internal object Gcj02Converter {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun toWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outsideChina(lat, lng)) return lat to lng

        val dLat = transformLat(lng - 105.0, lat - 35.0)
        val dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val wgsLat = lat - (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val wgsLng = lng - (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return wgsLat to wgsLng
    }

    private fun outsideChina(lat: Double, lng: Double): Boolean =
        lng !in 73.66..135.05 || lat !in 3.86..53.55

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
