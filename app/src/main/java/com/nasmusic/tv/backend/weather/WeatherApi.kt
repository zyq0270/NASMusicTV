package com.nasmusic.tv.backend.weather

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.IpLocation
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 天气 API 封装
 *
 * 数据来源（自动 fallback）：
 * 1. Open-Meteo（免费公开天气 API，无需 API Key）
 * 2. OpenWeatherMap（需 API Key，国内网络更稳定，免费注册：https://openweathermap.org/api）
 * 3. ip-api.com（免费 IP 定位，无需 API Key）
 *
 * Open-Meteo API 文档：https://open-meteo.com/
 * OpenWeatherMap 文档：https://openweathermap.org/current
 * ip-api.com 文档：https://ip-api.com/docs
 */
class WeatherApi(private val context: android.content.Context) {

    /**
     * §10.399：天气请求专用 OkHttpClient。
     *
     * 老电视（Android 5.1 / API 22）的系统 CA 库缺 ISRG Root X1（Let's Encrypt 根证书，
     * 2021 年才进入主流系统信任库），Open-Meteo 的证书由 ISRG Root X1 签发 ⇒ 校验失败。
     * 这里内嵌 ISRG Root X1 作为**附加**信任锚（与系统 CA 并集，只增不放宽）。
     *
     * ⚠️ 用 lazy：证书从 assets 读取，避免类初始化期做 I/O。
     */
    private val client: OkHttpClient by lazy {
        val trustManager = createTrustManagerWithIsrgRootX1()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private val gson = Gson()

    /**
     * IP 定位专用短超时客户端（复用主 client 的连接池 / Dispatcher）。
     *
     * `ip-api.com` 免费版只提供 HTTP(80)，国内网络常年连不上；它是**可选增强**，
     * 失败已有「回退北京坐标」兜底 ⇒ 不值得让每次天气刷新先白等 10 秒。
     */
    private val ipLocationClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(IP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(IP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 构造一个「系统默认 CA + 内嵌 ISRG Root X1」的**并集**信任管理器。
     *
     * 校验逻辑：先用系统默认 TrustManager 校验；失败时**追加**用 ISRG Root X1 再校验一次。
     * ⛔ 绝不做「信任所有证书」：系统 CA 仍然参与校验，只是多了一个老系统缺的根证书。
     * ⛔ 不放宽主机名校验（`sslSocketFactory` 不配 `hostnameVerifier`，OkHttp 默认严格校验）。
     */
    private fun createTrustManagerWithIsrgRootX1(): X509TrustManager {
        // 1) 系统默认 TrustManager
        val defaultFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        defaultFactory.init(null as KeyStore?)
        val defaultTm = defaultFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("no default X509TrustManager")

        // 2) 只含 ISRG Root X1 的 TrustManager
        val isrgCert = loadIsrgRootX1()
        val isrgKeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg-root-x1", isrgCert)
        }
        val isrgFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        isrgFactory.init(isrgKeyStore)
        val isrgTm = isrgFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw IllegalStateException("no ISRG X509TrustManager")

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                defaultTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    // 先走系统默认校验：正常设备（Android 7+）第一条就通过，
                    // 老电视（缺 ISRG Root X1）抛 CertificateException 后落入下面的追加校验。
                    defaultTm.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    isrgTm.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                defaultTm.acceptedIssuers + isrgTm.acceptedIssuers
        }
    }

    /** 从 assets 读取内嵌的 ISRG Root X1 证书。 */
    private fun loadIsrgRootX1(): X509Certificate {
        val pem = context.assets.open("isrg-root-x1.pem").use { it.readBytes().toString(Charsets.UTF_8) }
        val base64 = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val der = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }

    // IP 定位结果缓存 + 失败冷却：成功缓存 30 分钟，失败冷却 10 分钟。
    // 避免 fetchCurrentWeather / fetchForecast 各自发一次必然超时的请求、并刷两条长堆栈。
    // Mutex 把「读缓存 → 请求 → 写回」整段串行化实现单飞（锁内二次判缓存）。
    private val locationLock = Mutex()
    private var locationCached: IpLocation? = null
    private var locationCachedAt = 0L
    private var locationFailedAt = 0L

    companion object {
        private const val TAG = "WeatherApi"
        // Open-Meteo API 端点（免费，无需 Key）
        private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"
        // OpenWeatherMap API 端点（需 API Key，作 Open-Meteo 不可用时的备选）
        private const val OPEN_WEATHER_MAP_BASE = "https://api.openweathermap.org/data/2.5/weather"
        private const val OPEN_WEATHER_MAP_FORECAST_BASE = "https://api.openweathermap.org/data/2.5/forecast"
        // IP 定位端点（免费版，不支持 HTTPS）
        private const val IP_API_BASE = "http://ip-api.com/json/"
        // IP 定位是可选增强：3 秒连不上就走兜底坐标，不再等主 client 的 10 秒
        private const val IP_CONNECT_TIMEOUT_SECONDS = 3L
        private const val IP_LOCATION_TTL_MS = 30 * 60 * 1000L
        private const val IP_LOCATION_COOLDOWN_MS = 10 * 60 * 1000L
    }

    /**
     * 获取当前 IP 的地理位置（带结果缓存 / 失败冷却）。
     *
     * ⚠️ 定位失败**不是错误**：上层 `fetchCurrentWeather` / `fetchForecast` 会回退到北京坐标，
     * 天气仍然能取到（只是城市不准）。国内网络下 ip-api.com 的 HTTP(80) 常年不可达，
     * 因此这里要同时做到「快失败」与「不重复重试」，见各常量注释。
     */
    suspend fun getIpLocation(): IpLocation = withContext(Dispatchers.IO) {
        // 整段「读缓存 → 请求 → 写回」用同一把 Mutex 串行化：并发调用只有第一个真正发请求，
        // 其余拿锁后二次判缓存直接命中，避免 fetchCurrentWeather / fetchForecast 各发一次 3s 请求。
        locationLock.withLock {
            val now = System.currentTimeMillis()
            // 锁内二次判缓存：等待锁期间若已有协程写回，这里直接命中，不再请求
            val hit = locationCached
            val cached: IpLocation? = when {
                hit != null && now - locationCachedAt < IP_LOCATION_TTL_MS -> hit
                hit == null && locationFailedAt > 0L &&
                    now - locationFailedAt < IP_LOCATION_COOLDOWN_MS -> IpLocation()
                else -> null
            }
            if (cached != null) return@withLock cached

            val result = requestIpLocation()
            if (result.success) {
                locationCached = result
                locationCachedAt = System.currentTimeMillis()
                locationFailedAt = 0L
            } else {
                locationCached = null
                locationFailedAt = System.currentTimeMillis()
            }
            result
        }
    }

    /**
     * 真正发一次 IP 定位请求（在 `Dispatchers.IO` 上调用）。
     *
     * ⚠️ 失败按 AppLog 分级准则记 **W**，且**不带 throwable**：
     * `SocketTimeoutException` 的 20 行堆栈在手机上既不是崩溃也不是数据丢失，
     * 却会占掉日志页那 400 条窗口里真错误的位置（§10.401）。
     * M9 修复（2026-10-06）的 `isSuccessful` 检查与 `use{}` 关流在此保留。
     */
    private fun requestIpLocation(): IpLocation {
        return try {
            val request = Request.Builder()
                .url(IP_API_BASE)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            // 必须 use{} 关闭 Response，否则连接/连接池资源泄漏
            val body = ipLocationClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "getIpLocation: HTTP ${response.code}")
                    return IpLocation()
                }
                response.body?.string()
            } ?: return IpLocation()
            val json = gson.fromJson(body, JsonObject::class.java)
            if (json.get("status")?.asString == "success") {
                IpLocation(
                    city = json.get("city")?.asString ?: "",
                    lat = json.get("lat")?.asDouble ?: 0.0,
                    lon = json.get("lon")?.asDouble ?: 0.0,
                    success = true
                )
            } else {
                AppLog.w(TAG, "IP location failed: ${json.get("message")?.asString ?: "unknown"}")
                IpLocation()
            }
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "getIpLocation unavailable: ${e.javaClass.simpleName} " +
                    "(${e.message?.take(80) ?: "no message"}); using fallback location",
            )
            IpLocation()
        }
    }

    /**
     * 根据经纬度获取当前天气
     *
     * 尝试顺序：
     * 1. Open-Meteo（免费，无需 Key，国际网络环境可用）
     * 2. OpenWeatherMap（需要 API key，提供者传参）
     *
     * @param lat 纬度
     * @param lon 经度
     * @param openWeatherMapApiKey 可选 OpenWeatherMap API Key
     */
    suspend fun getWeather(lat: Double, lon: Double, openWeatherMapApiKey: String? = null): WeatherData? = withContext(Dispatchers.IO) {
        // 1. 先试 Open-Meteo（失败只折算为 null，继续走第 2 步 fallback）
        val openMeteo = getWeatherOpenMeteo(lat, lon)
        if (openMeteo != null) return@withContext openMeteo

        // 2. Open-Meteo 不可用，尝试 OpenWeatherMap（需要 API Key）
        if (openWeatherMapApiKey.isNullOrBlank()) {
            AppLog.w(TAG, "Open-Meteo failed and no OpenWeatherMap API key configured")
            return@withContext null
        }
        return@withContext getWeatherOpenWeatherMap(lat, lon, openWeatherMapApiKey)
    }

    /**
     * Open-Meteo 单次请求 + 解析（独立私有函数，内部用普通 `return null`）。
     *
     * ⚠️ 必须抽成独立函数：原实现把失败分支写成 `return@withContext null`，而
     * `withContext` 是 inline ⇒ 标签返回会**直接结束整个 getWeather**，跳过
     * OpenWeatherMap fallback（S05-04）。这里对齐 getWeatherOpenWeatherMap 的写法，
     * 任何失败都只折算为 `openMeteo = null`，由 getWeather 继续兜底。
     */
    private fun getWeatherOpenMeteo(lat: Double, lon: Double): WeatherData? {
        return try {
            val url = buildString {
                append("$OPEN_METEO_BASE?latitude=$lat&longitude=$lon")
                append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day")
                append("&timezone=auto")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            // M9 修复（2026-10-06）：先查 isSuccessful（Open-Meteo 的 4xx/5xx 是合法 JSON
            // 错误体，旧实现解析后取 "current" 为 null 静默返回、丢失诊断信息）
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "Open-Meteo: HTTP ${response.code}")
                    return null
                }
                response.body?.string()
            } ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val current = json.getAsJsonObject("current") ?: return null

            WeatherData(
                temperature = current.get("temperature_2m")?.asDouble ?: 0.0,
                feelsLike = current.get("apparent_temperature")?.asDouble,
                humidity = current.get("relative_humidity_2m")?.asDouble ?: 0.0,
                windSpeed = current.get("wind_speed_10m")?.asDouble ?: 0.0,
                weatherCode = current.get("weather_code")?.asInt ?: 0,
                isDay = current.get("is_day")?.asInt == 1,
                description = describeWeatherCode(current.get("weather_code")?.asInt ?: 0)
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Open-Meteo failed: ${e.message}")
            null
        }
    }

    /**
     * 通过 OpenWeatherMap API 获取天气
     * 当 Open-Meteo 不可用时的备选方案
     */
    private suspend fun getWeatherOpenWeatherMap(lat: Double, lon: Double, apiKey: String): WeatherData? {
        return try {
            val url = "$OPEN_WEATHER_MAP_BASE?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            // M9 修复（2026-10-06）：先查 isSuccessful——OWM key 失效返回 401（合法 JSON
            // 错误体），旧实现静默返回 null，用户只看到「检查网络」而非「key 无效」
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "OpenWeatherMap: HTTP ${response.code} (key invalid?)")
                    return null
                }
                response.body?.string()
            } ?: return null
            val json = gson.fromJson(body, JsonObject::class.java)
            val main = json.getAsJsonObject("main") ?: return null
            val wind = json.getAsJsonObject("wind")
            val weatherArr = json.getAsJsonArray("weather")
            val weatherObj = weatherArr?.firstOrNull()?.asJsonObject
            val sys = json.getAsJsonObject("sys")

            val isDay = sys?.let {
                val sunrise = it.get("sunrise")?.asLong ?: 0L
                val sunset = it.get("sunset")?.asLong ?: 0L
                if (sunrise > 0 && sunset > 0) {
                    val now = System.currentTimeMillis() / 1000
                    now in sunrise..sunset
                } else true
            } ?: true

            WeatherData(
                temperature = main.get("temp")?.asDouble ?: 0.0,
                feelsLike = main.get("feels_like")?.asDouble,
                humidity = main.get("humidity")?.asDouble ?: 0.0,
                windSpeed = wind?.get("speed")?.asDouble ?: 0.0,
                weatherCode = mapOpenWeatherMapCode(weatherObj?.get("id")?.asInt ?: 0),
                isDay = isDay,
                description = weatherObj?.get("description")?.asString ?: context.getString(R.string.weather_desc_unknown)
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "OpenWeatherMap failed: ${e.message}", e)
            null
        }
    }

    /**
     * 获取天气预报（未来 5 天）
     *
     * 需要 OpenWeatherMap API Key
     */
    suspend fun getForecast(lat: Double, lon: Double, apiKey: String): List<WeatherForecast> = withContext(Dispatchers.IO) {
        try {
            // cnt 修正：5 天/3 小时接口按 3 小时一条返回；cnt=5 只覆盖约 15 小时
            // （最多 2 个不同日期），拿不到 5 天预报。取满 40 条（5 天 × 8 条）再按天去重。
            val url = "$OPEN_WEATHER_MAP_FORECAST_BASE?lat=$lat&lon=$lon&appid=$apiKey&units=metric&lang=zh_cn&cnt=40"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NASMusicTV/2.6")
                .build()
            val body = client.newCall(request).execute().use { response ->
                // M9 修复（2026-10-06）：同 getWeatherOpenWeatherMap——先查 isSuccessful
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "getForecast: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                response.body?.string()
            } ?: return@withContext emptyList()
            val json = gson.fromJson(body, JsonObject::class.java)
            val list = json.getAsJsonArray("list") ?: return@withContext emptyList()

            val forecasts = mutableListOf<WeatherForecast>()
            // 按天去重，每天取一条代表
            val seenDates = mutableSetOf<String>()
            for (i in 0 until list.size()) {
                val item = list[i].asJsonObject
                val dtTxt = item.get("dt_txt")?.asString ?: continue
                val date = dtTxt.take(10) // "2024-01-15"
                if (date in seenDates) continue
                seenDates.add(date)

                val main = item.getAsJsonObject("main")
                val weatherArr = item.getAsJsonArray("weather")
                val weatherObj = weatherArr?.firstOrNull()?.asJsonObject

                forecasts.add(WeatherForecast(
                    date = date,
                    temperatureHigh = main?.get("temp_max")?.asDouble ?: 0.0,
                    temperatureLow = main?.get("temp_min")?.asDouble ?: 0.0,
                    humidity = main?.get("humidity")?.asDouble ?: 0.0,
                    weatherCode = mapOpenWeatherMapCode(weatherObj?.get("id")?.asInt ?: 0),
                    description = weatherObj?.get("description")?.asString ?: context.getString(R.string.weather_desc_unknown),
                    iconCode = weatherObj?.get("icon")?.asString ?: "01d"
                ))

                if (forecasts.size >= 5) break
            }
            forecasts
        } catch (e: Exception) {
            AppLog.e(TAG, "getForecast failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * OpenWeatherMap weather condition code → **WMO** weather code 映射
     * OpenWeatherMap codes: https://openweathermap.org/weather-conditions
     *
     * P3-5 修复（2026-09-16）：原实现返回 20/50/60/70 这些**非 WMO 代码**，
     * 而下游 `WeatherMood.matchingWeatherCodes` 全部按 WMO 区间判定
     * （RAINY = 45..48/51..57/61..67/80..82，SNOWY = 71..77/85..86 …）。
     * 于是走 OpenWeatherMap 这条路时：毛毛雨 50、雨 60、雪 70、雾霾 20 **全部落不进任何区间**，
     * `WeatherMood.fromWeather` 一律回退成 CLOUDY——下雨天放"多云 · 民谣"，且无任何报错。
     * 现在改为返回真正的 WMO 代码（51 / 61 / 71 / 45），与 Open-Meteo 路径（直接给 WMO）统一。
     */
    private fun mapOpenWeatherMapCode(owmCode: Int): Int = when (owmCode) {
        in 200..232 -> 95  // 雷暴 → WMO 95 Thunderstorm
        in 300..321 -> 51  // 毛毛雨 → WMO 51 Light drizzle
        in 500..531 -> 61  // 雨 → WMO 61 Slight rain
        in 600..622 -> 71  // 雪 → WMO 71 Slight snow fall
        in 701..781 -> 45  // 雾/霾/沙尘 → WMO 45 Fog
        800 -> 0           // 晴天
        801 -> 1           // 少云
        802 -> 2           // 多云
        803, 804 -> 3      // 阴天
        else -> 0
    }

    /**
     * 一键获取当前位置天气
     */
    suspend fun fetchCurrentWeather(manualCity: String? = null, openWeatherMapApiKey: String? = null): WeatherData? {
        var location = getIpLocation()
        if (!location.success) {
            AppLog.w(TAG, "IP location failed, falling back to Beijing (39.9042, 116.4074)")
            location = IpLocation(city = "北京", lat = 39.9042, lon = 116.4074, success = true)
        }

        val weather = getWeather(location.lat, location.lon, openWeatherMapApiKey) ?: return null
        return weather.copy(
            cityName = manualCity ?: location.city
        )
    }

    /**
     * 获取当前位置的天气预报
     */
    suspend fun fetchForecast(openWeatherMapApiKey: String?): List<WeatherForecast> {
        if (openWeatherMapApiKey.isNullOrBlank()) return emptyList()

        var location = getIpLocation()
        if (!location.success) {
            location = IpLocation(city = "北京", lat = 39.9042, lon = 116.4074, success = true)
        }
        return getForecast(location.lat, location.lon, openWeatherMapApiKey)
    }

    /**
     * WMO 天气代码 → 中文描述。
     *
     * P3-5 修复（2026-09-16）：原实现是一张**自造区间表**（4/5/6 大风、7 扬沙、8 风暴、
     * 9 沙尘、10-12 雾、13-18 雷电、19-25 霾、30-35 沙尘暴、36-39 雪…），与 WMO 标准并不对应：
     * - 51~77 段被拉平成「50..57 毛毛雨 / 60..69 雨 / 70..77 雪」，把 WMO 里不存在的
     *   偶数代码（52/54/62/64/70/72/74/76…）也纳入，而 WMO 真正的分段
     *   （51/53/55 毛毛雨、56/57 冻毛毛雨、61/63/65 雨、66/67 冻雨、71/73/75 雪、77 雪粒）
     *   全被糊在一起——"小雨/中雨/大雨"无法区分；
     * - 4~44 段那些分支在 WMO 表里根本不存在（Open-Meteo 永不会返回），属死分支。
     * 现改为严格对照 WMO 表（https://open-meteo.com/en/docs#weathervariables），
     * 未列入的代码一律回 "未知"，不再落进某个臆造的桶里。
     *
     * 注：本函数只产出展示文本（HomeScreen / WeatherRadioScreen 直接显示）；
     * 情绪匹配走 `WeatherData.weatherCode` + `WeatherMood.matchingWeatherCodes`，不受影响。
     */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> context.getString(R.string.weather_desc_0)
        1 -> context.getString(R.string.weather_desc_1)
        2 -> context.getString(R.string.weather_desc_2)
        3 -> context.getString(R.string.weather_desc_3)
        45 -> context.getString(R.string.weather_desc_45)
        48 -> context.getString(R.string.weather_desc_48)
        51 -> context.getString(R.string.weather_desc_51)
        53 -> context.getString(R.string.weather_desc_53)
        55 -> context.getString(R.string.weather_desc_55)
        56, 57 -> context.getString(R.string.weather_desc_56)
        61 -> context.getString(R.string.weather_desc_61)
        63 -> context.getString(R.string.weather_desc_63)
        65 -> context.getString(R.string.weather_desc_65)
        66, 67 -> context.getString(R.string.weather_desc_66)
        71 -> context.getString(R.string.weather_desc_71)
        73 -> context.getString(R.string.weather_desc_73)
        75 -> context.getString(R.string.weather_desc_75)
        77 -> context.getString(R.string.weather_desc_77)
        80 -> context.getString(R.string.weather_desc_80)
        81 -> context.getString(R.string.weather_desc_81)
        82 -> context.getString(R.string.weather_desc_82)
        85 -> context.getString(R.string.weather_desc_85)
        86 -> context.getString(R.string.weather_desc_86)
        95 -> context.getString(R.string.weather_desc_95)
        96 -> context.getString(R.string.weather_desc_96)
        99 -> context.getString(R.string.weather_desc_99)
        else -> context.getString(R.string.weather_desc_unknown)
    }
}
