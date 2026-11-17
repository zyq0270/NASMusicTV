package com.nasmusic.tv.backend.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10.409：GCJ-02 → WGS84 转换的正确性测试（纯 Kotlin，不需要 Android 上下文）。
 *
 * 腾讯位置服务返回 GCJ-02，Open-Meteo 只认 WGS84。城市级天气对米级误差不敏感，
 * 但**方向不能反**（反了会把城市推到几百米外，边界城市可能跨到邻市）。
 */
class Gcj02ConverterTest {

    @Test
    fun `北京 GCJ02 转 WGS84 落在预期位置`() {
        val (lat, lon) = Gcj02Converter.toWgs84(39.9042, 116.4074)
        assertEquals(39.9028, lat, 0.001)
        assertEquals(116.4012, lon, 0.001)
        // 方向：GCJ 的纬经度都比 WGS 偏北偏东，转换后必须都变小
        assertTrue(lat < 39.9042)
        assertTrue(lon < 116.4074)
    }

    @Test
    fun `上海广州转换量与北京同量级`() {
        val (shLat, shLon) = Gcj02Converter.toWgs84(31.2304, 121.4737)
        assertEquals(31.2323, shLat, 0.001)
        assertEquals(121.4692, shLon, 0.001)

        val (gzLat, gzLon) = Gcj02Converter.toWgs84(23.1291, 113.2644)
        assertEquals(23.1318, gzLat, 0.001)
        assertEquals(113.2591, gzLon, 0.001)
    }

    @Test
    fun `境外坐标原样返回`() {
        val tokyo = Gcj02Converter.toWgs84(35.6895, 139.6917)
        assertEquals(35.6895, tokyo.first, 0.0)
        assertEquals(139.6917, tokyo.second, 0.0)

        val newYork = Gcj02Converter.toWgs84(40.7128, -74.0060)
        assertEquals(40.7128, newYork.first, 0.0)
        assertEquals(-74.0060, newYork.second, 0.0)
    }

    @Test
    fun `边界内坐标转换不产生 NaN 或零坐标`() {
        for (lat in listOf(4.0, 18.0, 39.9, 53.0)) {
            for (lon in listOf(74.0, 104.0, 116.4, 134.0)) {
                val (outLat, outLon) = Gcj02Converter.toWgs84(lat, lon)
                assertTrue("lat=$lat lon=$lon => NaN", !outLat.isNaN() && !outLon.isNaN())
                assertTrue("lat=$lat lon=$lon => 零坐标", outLat != 0.0 && outLon != 0.0)
            }
        }
    }
}
