package com.nasmusic.tv.backend.weather

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §10.409：IP 定位链路的源码门禁（2026-11-17）。
 *
 * 旧实现用 `http://ip-api.com/json/`：免费版只提供 HTTP(80)，国内常年不可达，
 * 每次天气刷新先白等 3 秒再回退北京。本门禁把新链路钉在源码文本上：
 *
 * | # | 断言 |
 * |---|---|
 * | L1 | 生产代码不得再出现 `ip-api.com`（语句/字面量级，全文扫描即可） |
 * | L2 | 必须有腾讯 + ip.sb 两级端点，且都走 HTTPS |
 * | L3 | 腾讯的 GCJ-02 结果必须经过 `Gcj02Converter.toWgs84` 才进 IpLocation |
 * | L4 | 30 分钟成功缓存 / 10 分钟失败冷却不变（防「改链路顺手破坏单飞」） |
 * | L5 | 设置链路贯穿：Prefs 加密键 → ViewModel 同步读 → fetchCurrentWeather 传参 |
 * | L6 | 中英 strings 都新增了腾讯 key 文案 |
 * | L7 | 负向自证：同一谓词喂旧代码文本必须判负 |
 *
 * ⚠️ 只读文本，不发网络请求。断言全部用 JUnit 的 Assert（Kotlin assert 是空操作）。
 */
class WeatherIpLocationGateTest {

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativePath（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    private fun resource(path: String): String {
        val f = File("../app/src/main/res/$path").takeIf { it.isFile }
            ?: File("app/src/main/res/$path")
        assertTrue("找不到资源 $path", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `旧ip-api端点必须彻底消失`() {
        val api = mainSource("backend/weather/WeatherApi.kt")
        assertFalse(
            "生产代码仍有 ip-api.com —— 免费版只有 HTTP(80)，国内不可达，会白等 3 秒",
            api.contains("ip-api.com"),
        )
    }

    @Test
    fun `两级HTTPS端点都在`() {
        val api = mainSource("backend/weather/WeatherApi.kt")
        assertTrue("缺少腾讯端点常量", api.contains("private const val TENCENT_IP_HOST = \"apis.map.qq.com\""))
        assertTrue("缺少 ip.sb 兜底端点", api.contains("private const val IP_SB_GEOIP_URL = \"https://api.ip.sb/geoip\""))
        assertTrue("腾讯 URL 必须用 https", api.contains(".scheme(\"https\")"))
        assertTrue("缺少链式调度", api.contains("if (tencent.success) return tencent"))
        assertTrue("ip.sb 必须是兜底而不是前置", api.contains("return requestIpSbLocation()"))
    }

    @Test
    fun `腾讯GCJ02结果必须转换后才写入定位结果`() {
        val api = mainSource("backend/weather/WeatherApi.kt")
        assertTrue(
            "腾讯返回的 lat/lng 是 GCJ-02，必须转 WGS84 才能喂 Open-Meteo",
            api.contains("val wgs = Gcj02Converter.toWgs84(lat, lon)"),
        )
        assertTrue(
            "IpLocation 必须用转换后的坐标",
            api.contains("IpLocation(city = city, lat = wgs.first, lon = wgs.second, success = true)"),
        )
        // ip.sb 给的是 WGS84：语句里不得误调转换器
        val ipSbBody = api.substring(api.indexOf("private fun requestIpSbLocation"))
        assertFalse("ip.sb 已是 WGS84，不要重复转换", ipSbBody.contains("Gcj02Converter"))
    }

    @Test
    fun `缓存与失败冷却口径不变`() {
        val api = mainSource("backend/weather/WeatherApi.kt")
        assertTrue("30 分钟成功缓存被改", api.contains("private const val IP_LOCATION_TTL_MS = 30 * 60 * 1000L"))
        assertTrue("10 分钟失败冷却被改", api.contains("private const val IP_LOCATION_COOLDOWN_MS = 10 * 60 * 1000L"))
        assertTrue("单飞锁丢失", api.contains("locationLock.withLock"))
        assertTrue("锁内二次判缓存丢失", api.contains("if (cached != null) return@withLock cached"))
    }

    @Test
    fun `key设置链路贯穿prefs到请求`() {
        val prefs = mainSource("data/prefs/AppPreferences.kt")
        assertTrue("缺加密键声明", prefs.contains("stringPreferencesKey(\"weather_tencent_map_key_enc\")"))
        assertTrue("缺同步读镜像", prefs.contains("fun getTencentMapKeySync(): String = cachedTencentMapKey"))

        val vm = mainSource("ui/viewmodel/WeatherRadioViewModel.kt")
        assertTrue("ViewModel 未读腾讯 key", vm.contains("val tencentKey = prefs.weather.getTencentMapKeySync().ifBlank { null }"))
        assertTrue("请求未带腾讯 key", vm.contains("tencentMapKey = tencentKey"))

        val api = mainSource("backend/weather/WeatherApi.kt")
        assertTrue("getIpLocation 不接收 key", api.contains("suspend fun getIpLocation(tencentMapKey: String? = null)"))
        assertTrue("fetchCurrentWeather 不透传 key", api.contains("var location = getIpLocation(tencentMapKey)"))
    }

    @Test
    fun `中英文设置文案齐全`() {
        for (locale in listOf("values", "values-en")) {
            val s = resource("$locale/strings.xml")
            assertTrue("$locale 缺 key 标题", s.contains("name=\"settings_tencent_map_key\""))
            assertTrue("$locale 缺 key 说明", s.contains("name=\"settings_tencent_map_key_desc\""))
            assertTrue("$locale 缺 key 输入提示", s.contains("name=\"settings_tencent_map_key_hint\""))
        }
    }

    @Test
    fun `负向自证旧实现必须被判红`() {
        val legacy = """
            private const val IP_API_BASE = "http://ip-api.com/json/"
            val request = Request.Builder().url(IP_API_BASE).build()
        """.trimIndent()
        assertTrue("门禁空转：旧实现竟然通过了「无 ip-api.com」断言", legacy.contains("ip-api.com"))
    }
}
