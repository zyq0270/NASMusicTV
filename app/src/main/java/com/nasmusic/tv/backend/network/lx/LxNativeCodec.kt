package com.nasmusic.tv.backend.network.lx

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `lx.utils.crypto` / `lx.utils.zlib` 的 Java 实现（方案 §5.2 T5）。
 *
 * 入参/出参一律用 base64 字符串：JS 侧已经有 Buffer 编解码，走二进制数组反而要给
 * 每种形态各写一遍 Java↔JS 转换（库只支持 String/Int/Double/Boolean/JSObject）。
 *
 * ⚠️ 失败一律抛异常，由调用方（[LxScriptHost]）转成 `__err:` 前缀字符串，
 * 再由 boot.js 抛成 JS 异常 —— ⛔ 不要让 Java 异常穿过 JNI 回到解释器。
 */
internal object LxNativeCodec {

    fun crypto(op: String, argsJson: String): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: throw IllegalArgumentException("参数不是 JSON")
        return when (op) {
            "md5" -> md5(obj.optString("data"))
            "randomBytes" -> randomBytes(obj.optInt("size", 16))
            "aesEncrypt" -> aes(obj, encrypt = true)
            "aesDecrypt" -> aes(obj, encrypt = false)
            "rsaEncrypt" -> rsaEncrypt(obj)
            else -> throw IllegalArgumentException("不支持的 crypto 操作：$op")
        }
    }

    fun zlib(op: String, base64: String): String {
        val input = Base64.decode(base64, Base64.DEFAULT)
        val output = when (op) {
            "inflate" -> InflaterInputStream(input.inputStream()).use { it.readBytes() }
            "deflate" -> {
                val out = ByteArrayOutputStream()
                DeflaterOutputStream(out).use { it.write(input) }
                out.toByteArray()
            }
            else -> throw IllegalArgumentException("不支持的 zlib 操作：$op")
        }
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun md5(data: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            sb.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    private fun randomBytes(size: Int): String {
        val bytes = ByteArray(size.coerceIn(0, 4096))
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 洛雪的 `mode` 形如 `aes-128-cbc` / `aes-256-ecb`；除 ecb 外都必须带 iv */
    private fun aes(obj: JSONObject, encrypt: Boolean): String {
        val mode = obj.optString("mode").lowercase()
        if (!mode.startsWith("aes-")) throw IllegalArgumentException("不支持的 AES 模式：$mode")
        val blockMode = when {
            mode.endsWith("-cbc") -> "CBC"
            mode.endsWith("-ecb") -> "ECB"
            else -> throw IllegalArgumentException("不支持的 AES 模式：$mode")
        }
        val cipher = Cipher.getInstance("AES/$blockMode/PKCS5Padding")
        val key = SecretKeySpec(Base64.decode(obj.optString("key"), Base64.DEFAULT), "AES")
        val iv = obj.optString("iv").takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
        val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (blockMode == "CBC" && iv != null) {
            cipher.init(opMode, key, IvParameterSpec(iv))
        } else {
            cipher.init(opMode, key)
        }
        val input = Base64.decode(obj.optString("data"), Base64.DEFAULT)
        return Base64.encodeToString(cipher.doFinal(input), Base64.NO_WRAP)
    }

    private fun rsaEncrypt(obj: JSONObject): String {
        val keyText = obj.optString("key")
        val body = keyText
            .replace(Regex("-----[^-]*-----"), "")
            .replace(Regex("\\s"), "")
        val encoded = Base64.decode(body, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val input = Base64.decode(obj.optString("data"), Base64.DEFAULT)
        return Base64.encodeToString(cipher.doFinal(input), Base64.NO_WRAP)
    }

    private const val HEX = "0123456789abcdef"
}
