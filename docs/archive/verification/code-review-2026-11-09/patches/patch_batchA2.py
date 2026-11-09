# -*- coding: utf-8 -*-
"""Batch A 补丁：常量 / 导入 / 小工具函数。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = ""
CRLF = False


def load(rel):
    global S, CRLF
    p = os.path.join(ROOT, rel)
    S = io.open(p, "r", encoding="utf-8", newline="").read()
    CRLF = "\r\n" in S
    return p


def rep(a, b, tag, expect=1):
    global S
    a2 = a.replace("\n", "\r\n") if CRLF else a
    b2 = b.replace("\n", "\r\n") if CRLF else b
    n = S.count(a2)
    if n != expect:
        print("FAIL [%s] count=%d (expect %d)" % (tag, n, expect))
        sys.exit(1)
    S = S.replace(a2, b2)


def save(p):
    io.open(p, "w", encoding="utf-8", newline="").write(S)


# ── 1) BilibiliMvService：import + 常量 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/network/mv/BilibiliMvService.kt")
rep(
    """import okhttp3.OkHttpClient
import okhttp3.Request
""",
    """import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
""",
    "bili-import",
)
rep(
    """    companion object {
""",
    """    companion object {
        /**
         * M16：B 站自有 CDN 的可信 host 后缀（用于 [isTrustedMediaUrl]）。
         *
         * 用**后缀**匹配以覆盖 regional / 镜像子域：`upos-sz-mirrorcos.bilivideo.com`、
         * `upos-hz-mirrorakam.akamaized.net`、`i0.hdslb.com` 等。⛔ 不要放通用 CDN
         * 根域（如裸 `akamaized.net`）之外的宽泛项 —— 白名单越短越好维护。
         */
        private val TRUSTED_MEDIA_HOST_SUFFIXES = arrayOf(
            ".bilivideo.com",
            ".bilivideo.cn",
            ".hdslb.com",
            ".biliapi.net",
            ".akamaized.net",
        )

""",
    "bili-const",
)
save(p)
print("OK BilibiliMvService 补常量/导入")

# ── 2) MetingApiService：import + enc()/jsonStr() 工具 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt")
rep(
    """import com.nasmusic.tv.util.EncodingUtils
""",
    """import com.nasmusic.tv.util.EncodingUtils
import com.nasmusic.tv.util.NetAddressGuard
""",
    "meting-import",
)
rep(
    """    companion object {
""",
    """    /** M19：`server` 参数单点编码（值来自用户配置，含 & / = 会破坏 query） */
    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    /**
     * M22：安全取字符串字段。
     *
     * ⛔ `obj.get(k)?.asString` 对 **JsonNull** 会抛 UnsupportedOperationException
     * （JsonNull 是 JsonElement 但不是 JsonPrimitive），异常被外层 try 吞掉后
     * 整首单曲/整段响应作废。与 `JamendoModels.kt:17` 的既有范式保持一致。
     */
    private fun jsonStr(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    companion object {
""",
    "meting-helpers",
)
save(p)
print("OK MetingApiService 补工具函数")

# ── 3) BaiduLyricsProvider：上限常量 ──
p = load("app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduLyricsProvider.kt")
rep(
    """    /** M5 两段式补读的标签总长上限（16MB，与 BaiduCoverProvider 同口径） */
    private const val MAX_ID3_TAG_BYTES = 16L * 1024 * 1024
""",
    """    /** M5 两段式补读的标签总长上限（16MB，与 BaiduCoverProvider 同口径） */
    private const val MAX_ID3_TAG_BYTES = 16L * 1024 * 1024

        /** M15：侧车 LRC 文本下载的体积上限（1MB ≈ 5 万行，远超任何真实歌词） */
        private const val MAX_SIDECAR_LRC_BYTES = 1L * 1024 * 1024
""",
    "bd-const",
)
save(p)
print("OK BaiduLyricsProvider 补常量")

# ── 4) LocalFilePicker：READ_CHUNK_BYTES ──
p = load("app/src/main/java/com/nasmusic/tv/util/LocalFilePicker.kt")
rep(
    """    private const val EXTERNAL_STORAGE_DOC_AUTHORITY = "com.android.externalstorage.documents"
""",
    """    private const val EXTERNAL_STORAGE_DOC_AUTHORITY = "com.android.externalstorage.documents"

    /** C02：有界读取的分块大小（也是 ByteArrayOutputStream 的初始容量上限） */
    private const val READ_CHUNK_BYTES = 64 * 1024
""",
    "picker-const",
)
save(p)
print("OK LocalFilePicker 补常量")

# ── 5) LyricsNetworkProvider：toHttpUrlOrNull 导入 ──
p = load("app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt")
rep(
    """import okhttp3.Dispatcher
""",
    """import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
""",
    "lyr-import",
)
save(p)
print("OK LyricsNetworkProvider 补导入")

print("ALL DONE")
