package com.nasmusic.tv.backend.weather

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §10.399：天气 CA 信任锚门禁。
 *
 * 背景：老电视（Android 5.1 / API 22）系统 CA 库缺 ISRG Root X1（Let's Encrypt 根证书），
 * Open-Meteo 的证书由它签发 ⇒ 校验失败 ⇒ 天气电台不可用。修复方式是内嵌 ISRG Root X1
 * 作为**附加**信任锚（系统默认 CA + 内嵌证书的并集，只增不放宽）。
 *
 * 本门禁锁三件事：
 * 1. 证书文件必须存在且是合法 PEM；
 * 2. 信任锚必须是「系统默认 + 追加」的并集，不是整体替换；
 * 3. 绝不出现信任降级（TrustAllCerts / 空 checkServerTrusted / HostnameVerifier 放行）。
 */
class WeatherTrustAnchorGateTest {

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

    private fun assetsFile(relativePath: String): File? {
        val candidates = listOf(
            File("src/main/assets/$relativePath"),
            File("app/src/main/assets/$relativePath"),
            File("../app/src/main/assets/$relativePath"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ─────────────────── 1. 证书必须内嵌 ───────────────────

    @Test
    fun `必须内嵌 ISRG Root X1 证书`() {
        val cert = assetsFile("isrg-root-x1.pem")
        assertTrue(
            "缺少 app/src/main/assets/isrg-root-x1.pem —— 老电视天气仍不可用",
            cert != null,
        )
        val pem = cert!!.readText(Charsets.UTF_8)
        assertTrue("证书文件不是 PEM 格式（缺 BEGIN CERTIFICATE）", pem.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue("证书文件不是 PEM 格式（缺 END CERTIFICATE）", pem.contains("-----END CERTIFICATE-----"))
        // 负向自证：空文件/占位符不能被当成合法证书
        assertTrue("证书文件内容为空", pem.length > 1000)
    }

    // ─────────────────── 2. 并集语义 ───────────────────

    @Test
    fun `信任锚必须是系统默认加追加的并集`() {
        val src = stripComments(mainSource("backend/weather/WeatherApi.kt"))
        assertTrue(
            "没有使用系统默认 TrustManager（TrustManagerFactory.getDefaultAlgorithm）—— 信任锚被整体替换了",
            src.contains("TrustManagerFactory.getDefaultAlgorithm()"),
        )
        assertTrue(
            "没有 catch CertificateException 后追加校验 ISRG Root X1 —— 不是并集语义",
            src.contains("catch (e: CertificateException)"),
        )
        assertTrue(
            "没有加载 isrg-root-x1.pem —— 内嵌证书没被使用",
            src.contains("isrg-root-x1.pem"),
        )
        assertTrue(
            "没有配置 sslSocketFactory —— 自定义信任管理器没接到 OkHttp 上",
            src.contains("sslSocketFactory("),
        )
    }

    // ─────────────────── 3. 禁止信任降级 ───────────────────

    @Test
    fun `不得出现信任降级写法`() {
        val src = stripComments(mainSource("backend/weather/WeatherApi.kt"))
        assertTrue(
            "出现 TrustAllCerts —— 这是信任降级，绝不允许",
            !src.contains("TrustAllCerts"),
        )
        assertTrue(
            "出现 checkServerTrusted 空实现（全放行）—— 绝不允许",
            !hasBlankCheckServerTrusted(src),
        )
        assertTrue(
            "出现 HostnameVerifier 放行（return true）—— 绝不允许放宽主机名校验",
            !hasPermissiveHostnameVerifier(src),
        )
    }

    /** 负向自证：全放行的 checkServerTrusted 必须被检出。 */
    @Test
    fun `负向自证 全放行的 TrustManager 必须判负`() {
        val sample = """
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            }
        """.trimIndent()
        assertTrue("护栏空转：全放行的 TrustManager 未被检出", hasBlankCheckServerTrusted(sample))
    }

    /** 负向自证：HostnameVerifier 返回 true 必须被检出。 */
    @Test
    fun `负向自证 放行主机名校验必须判负`() {
        val sample = "val v = HostnameVerifier { _, _ -> true }"
        assertTrue("护栏空转：HostnameVerifier 放行未被检出", hasPermissiveHostnameVerifier(sample))
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 检测 `checkServerTrusted` 方法体是否为空（全放行）。
     * 只认「方法体里既无 throw 也无任何调用」的空实现——真实实现里会调用
     * `defaultTm.checkServerTrusted(...)` 或 `isrgTm.checkServerTrusted(...)`。
     */
    private fun hasBlankCheckServerTrusted(src: String): Boolean {
        val regex = Regex("""checkServerTrusted\s*\([^)]*\)\s*\{([^{}]*)\}""")
        return regex.findAll(src).any { it.groupValues[1].isBlank() }
    }

    /** 检测是否存在「HostnameVerifier 返回 true」的放行写法。 */
    private fun hasPermissiveHostnameVerifier(src: String): Boolean {
        // 形如 `HostnameVerifier { ... true ... }` 或 `hostnameVerifier { _, _ -> true }`
        val regex = Regex("""[Hh]ostnameVerifier\s*(\{[^{}]*true[^{}]*\}|\(\s*\{[^{}]*true[^{}]*\}\))""")
        return regex.containsMatchIn(src)
    }

    /**
     * 剥离 // 与 /* */ 注释。门禁判据必须建立在「真代码」上，
     * 否则 KDoc 里的一句话（比如本测试的说明文字）就能把红变绿。
     */
    private fun stripComments(src: String): String =
        src.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
}
