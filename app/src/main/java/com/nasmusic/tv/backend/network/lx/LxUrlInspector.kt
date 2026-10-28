package com.nasmusic.tv.backend.network.lx

import com.nasmusic.tv.backend.network.HttpPlayabilityProbe
import com.nasmusic.tv.backend.network.Playability
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * L3 探测：真的去要一次字节，判「这个直链到底能不能出声」（方案 §5.4.1.3）。
 *
 * ⚠️ **不改 [`HttpPlayabilityProbe`]，也不与它合并**：那条链路服务于**播放决策**
 * （只有 DEAD 才算失败、UNKNOWN 保守放行、失败会被负面缓存 5 分钟）；
 * 这里是**给人看的验证**，判严了用户再点一次就行，代价不对称。
 * 但 `Content-Type` 的语义仍复用它的 `classify()` —— 让两种判据只有「更严」这一处差异。
 */
internal open class LxUrlInspector(
    private val client: OkHttpClient = defaultClient(),
) {

    /** `open` 只为单测注入假 L3（T21 要求「不打网络」），线上只有一个实现 */
    open fun inspect(url: String): LxL3 = try {
        val request = Request.Builder().url(url).header("Range", "bytes=0-511").build()
        client.newCall(request).execute().use { response ->
            // 只要前 8 个字节就能判魔数；⛔ 绝不把整首拉下来（§9.3 带宽预算）
            val bytes = ByteArray(MAGIC_BYTES.toInt())
            val read = runCatching {
                response.body?.source()?.read(bytes, 0, MAGIC_BYTES.toInt()) ?: -1
            }.getOrDefault(-1)
            val magic = if (read > 0) LxMagic.detect(bytes.copyOf(read)) else null
            judgeL3(response.code, response.header("Content-Type"), magic)
        }
    } catch (e: Exception) {
        LxL3.Error(LxFailReason.TIMEOUT, "${e.javaClass.simpleName}: ${e.message}")
    }

    private companion object {
        const val MAGIC_BYTES = 8L
        const val TIMEOUT_MS = 3_000L
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 必须跟到 CDN 才量得到真实响应（端点自己往往只回 302）
            .followRedirects(true)
            .build()
    }
}

/**
 * L3 纯判据（无 IO，单测直打）。
 *
 * **与 `HttpPlayabilityProbe.classify()` 的差异（⛔ 不要「统一」掉）**：
 * 播放链路把 401/403/404/410/429/5xx 之外的不确定归 `UNKNOWN` 并放行，因为
 * 「UNKNOWN 误判成 DEAD 会把好歌跳过并负面缓存 5 分钟」，代价不可逆。
 * 验证按钮不写任何缓存，所以这四类**必须显式判失败** —— 否则 UI 会显示「存疑」，
 * 让用户以为「还能用，只是我没测准」。
 * 404/410 单独成 `HTTP_404` 而不是落到 `NOT_AUDIO`：前者是「直链失效」（脚本模板过期），
 * 后者是「返回的不是音频」，两件事的修复动作不同。
 */
internal fun judgeL3(code: Int, contentType: String?, magic: LxContainer?): LxL3 = when {
    code == 401 || code == 403 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_403)
    code == 404 || code == 410 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_404)
    code == 429 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_429)
    code in 500..599 -> LxL3.Dead(code, contentType, LxFailReason.HTTP_5XX)
    else -> when (HttpPlayabilityProbe.classify(code, contentType)) {
        Playability.DEAD -> LxL3.Dead(code, contentType, LxFailReason.NOT_AUDIO)
        Playability.PLAYABLE -> LxL3.Ok(code, contentType, magic?.name)
        // Content-Type 不可靠时用魔数兜底：对上是真音频，对不上就「存疑」，不冒充失败
        Playability.UNKNOWN ->
            if (magic != null) LxL3.Ok(code, contentType, magic.name)
            else LxL3.Doubtful(code, contentType)
    }
}
