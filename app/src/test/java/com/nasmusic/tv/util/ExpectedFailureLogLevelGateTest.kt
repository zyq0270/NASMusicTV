package com.nasmusic.tv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **门禁：两类「可预期降级」不得再以 E 级 + 长堆栈写进日志页**（2026-11-13 手机实测）
 *
 * ## 用户实测现象
 *
 * ```
 * E PhotoBuffer   decode failed for IMG_20190729_175731.jpg: null
 *                 java.lang.InterruptedException ...
 * E WeatherApi    getIpLocation failed: failed to connect to ip-api.com/208.95.112.1
 *                 (port 80) ... after 10000ms / java.net.SocketTimeoutException ...
 * ```
 *
 * ## 为什么是「假错误」
 *
 * | 现象 | 根因 | 是否影响用户 |
 * |---|---|---|
 * | `PhotoBuffer` `InterruptedException`（message 恒 null） | `close()` 的 `shutdownNow()` 中断在途解码线程；页面销毁或画质档变化触发 `PhotoWallController` 重建 buffer 时都会发生。结果本来就会按 `closed` / 世代丢弃 | ❌ 不影响，是生命周期取消 |
 * | `WeatherApi` 连 `ip-api.com:80` 超时 | 免费版 ip-api.com 只有 HTTP(80)，国内网络常年不可达；`fetchCurrentWeather` / `fetchForecast` 已有「回退北京坐标」兜底 | ❌ 不影响天气数据，只是白等 10 秒 |
 *
 * ⇒ 二者都不满足 [AppLog] 的 E 级准则（崩溃 / 数据丢失 / 用户操作失败）。
 * 而 §10.401 实测日志保留窗口只有约 2 分钟（400 条，约 180 条/分钟），
 * **假错误会把用户报障时真正该看的那条错误挤掉** ⇒ 必须从源头掐掉。
 *
 * ⛔ 断言一律**语句级 + 函数体级**：全文否定会被注释里的解释自伤（第五轮门禁踩过）。
 */
class ExpectedFailureLogLevelGateTest {

    // ────────────────── ① PhotoBuffer：取消 ≠ 解码失败 ──────────────────

    @Test
    fun `PhotoBuffer 取消分支用 D 记录且真失败仍保留 E`() {
        val body = bodyOf(
            mainSource("visualizer/photo/PhotoBuffer.kt"),
            "private fun decodeAndPublish(ref: PhotoRef, gen: Int)",
        )
        assertTrue("找不到 decodeAndPublish 方法体", body != null)

        val b = body!!
        val branchAt = b.indexOf("if (isDecodeCancelled(t))")
        val debugAt = b.indexOf("AppLog.d(TAG, \"decode cancelled")
        val elseAt = b.indexOf("} else {")
        val errorAt = b.indexOf("AppLog.e(TAG, \"decode failed")

        assertTrue("catch 必须先判定是否为取消", branchAt >= 0)
        assertTrue("取消分支必须走 D（Debug 级，release 不产生）", debugAt >= 0)
        assertTrue("真失败分支仍要保留", elseAt >= 0)
        assertTrue("真解码失败仍必须记 E（不得一刀切降级）", errorAt >= 0)
        assertTrue(
            "顺序错了：必须先判取消 → D → else → E，实际 branch@$branchAt d@$debugAt else@$elseAt e@$errorAt",
            branchAt < debugAt && debugAt < elseAt && elseAt < errorAt,
        )
    }

    @Test
    fun `取消判定只看异常链且世代作废不得吞掉真失败`() {
        val body = bodyOf(
            mainSource("visualizer/photo/PhotoBuffer.kt"),
            "private fun isDecodeCancelled(t: Throwable)",
        )
        assertTrue("找不到 isDecodeCancelled 方法体", body != null)
        val b = body!!
        assertTrue("必须识别 shutdownNow 抛出的 InterruptedException", b.contains("InterruptedException"))
        assertTrue("必须识别协程取消", b.contains("CancellationException"))
        assertTrue("必须沿 cause 链判定（runBlocking 会包一层）", b.contains("cur = cur.cause"))
        // S11-04：closed / generation 只用于丢弃结果，不得作为「降为 D」的充分条件，
        // 否则世代作废期间抛出的 OOM / 坏图 / IO 真失败会在 release 里零痕迹。
        assertFalse("取消判定不得读 closed 状态", b.contains("closed.get()"))
        assertFalse("取消判定不得读世代状态", b.contains("generation.get()"))
    }

    // ────────────────── ② WeatherApi：IP 定位不可达 ──────────────────

    @Test
    fun `IP 定位失败只记 W 且不携带 throwable`() {
        val body = bodyOf(
            mainSource("backend/weather/WeatherApi.kt"),
            "private fun requestIpLocation(): IpLocation",
        )
        assertTrue("找不到 requestIpLocation 方法体", body != null)
        val b = body!!

        val catchAt = b.indexOf("} catch (e: Exception) {")
        assertTrue("找不到网络异常 catch", catchAt >= 0)
        val catch = b.substring(catchAt)

        assertFalse(
            "IP 定位不可达是预期降级（已有北京坐标兜底），不得记 E",
            catch.contains("AppLog.e("),
        )
        assertTrue("降级信息仍要留痕，必须记 W", catch.contains("AppLog.w("))
        assertFalse(
            "不得把 Throwable 传进日志：SocketTimeoutException 的 20 行堆栈会占掉日志窗口",
            catch.contains(", e)"),
        )
    }

    @Test
    fun `IP 定位有独立短超时与结果缓存失败冷却`() {
        val src = mainSource("backend/weather/WeatherApi.kt")

        assertTrue("IP 定位必须有独立的 3 秒短超时", src.contains("private const val IP_CONNECT_TIMEOUT_SECONDS = 3L"))
        assertTrue("成功结果必须有 TTL 缓存", src.contains("IP_LOCATION_TTL_MS"))
        assertTrue("失败必须有冷却窗口", src.contains("IP_LOCATION_COOLDOWN_MS"))

        val getBody = bodyOf(src, "suspend fun getIpLocation(): IpLocation")
        assertTrue("找不到 getIpLocation 方法体", getBody != null)
        assertTrue(
            "S05-08：缓存读/请求/写回必须用 Mutex 单飞串行化",
            getBody!!.contains("locationLock.withLock {"),
        )
        assertTrue(
            "拿到锁后必须二次判缓存，否则并发仍会各发一次 3s 请求",
            getBody.contains("if (cached != null) return@withLock cached"),
        )
        assertTrue("必须走真正发请求的方法", getBody.contains("requestIpLocation()"))

        val reqBody = bodyOf(src, "private fun requestIpLocation(): IpLocation")
        assertTrue("找不到 requestIpLocation 方法体", reqBody != null)
        assertTrue("请求必须走短超时 client，而不是主 client 的 10 秒", reqBody!!.contains("ipLocationClient.newCall(request)"))
        assertTrue("响应必须 use{} 关流（M9 修复不得回退）", reqBody.contains(".execute().use { response ->"))
    }

    @Test
    fun `S05-04 Open-Meteo 失败不得跳过 OpenWeatherMap 兜底`() {
        val src = mainSource("backend/weather/WeatherApi.kt")
        val body = bodyOf(src, "suspend fun getWeather(lat: Double, lon: Double")
        assertTrue("找不到 getWeather 方法体", body != null)
        val b = body!!
        assertTrue("Open-Meteo 必须抽成独立函数（失败只折算为 null）", b.contains("getWeatherOpenMeteo(lat, lon)"))
        assertTrue("Open-Meteo 成功后可直接返回", b.contains("if (openMeteo != null) return@withContext openMeteo"))
        assertTrue("失败路径必须继续走 OpenWeatherMap", b.contains("getWeatherOpenWeatherMap(lat, lon, openWeatherMapApiKey)"))
        // 标签返回只能出现在 getWeather 自己的收尾分支，不能在抽出去的子函数里直接跳出整个 getWeather
        val omBody = bodyOf(src, "private fun getWeatherOpenMeteo(")
        assertTrue("找不到 getWeatherOpenMeteo 方法体", omBody != null)
        assertFalse("子函数里不得出现 return@withContext null（会跳过 fallback）", omBody!!.contains("return@withContext null"))
    }

    // ────────────────── ③ 负向自证 ──────────────────

    /** 护栏空转自证：`bodyOf` 提取的是**有边界**的函数体，不是整篇源码。 */
    @Test
    fun `negative proof - function body extraction is scoped`() {
        val src = mainSource("backend/weather/WeatherApi.kt")
        val req = bodyOf(src, "private fun requestIpLocation(): IpLocation")!!
        assertTrue("IP 定位函数体应包含 IP_API_BASE", req.contains("IP_API_BASE"))
        assertFalse(
            "IP 定位函数体不应包含天气端点（否则 bodyOf 把别的方法也吞进来了）",
            req.contains("OPEN_METEO_BASE"),
        )

        val badCatch = "} catch (e: Exception) { AppLog.e(TAG, \"boom\", e); return IpLocation() }"
        assertTrue("含 AppLog.e( 的 catch 必须能被判负谓词识别", badCatch.contains("AppLog.e("))
        assertTrue("含 , e) 的 catch 必须能被判负谓词识别", badCatch.contains(", e)"))
    }

    // ────────────────── 工具 ──────────────────

    /** user.dir 可能是仓库根，也可能是 app/ ⇒ 多候选（与 SourceGatingGateTest 同款）。 */
    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到源码文件：$relativePath；已尝试 ${candidates.joinToString { it.absolutePath }}",
            file != null,
        )
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("源码读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    /**
     * 取 `signature` 起第一个 `{` 到配对 `}` 的正文。
     *
     * ⚠️ 简单大括号计数：Kotlin 字符串模板 `${...}` 的花括号成对出现，净影响为 0，
     * 与 `LogsTabWiringGateTest.bodyOf` 同一范式；找不到返回 null。
     */
    private fun bodyOf(src: String, signature: String): String? {
        val start = src.indexOf(signature)
        if (start < 0) return null
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }
}
