package com.nasmusic.tv.backend.network.lx

/**
 * 容器魔数判定（方案 §5.4.1.3 的表）。
 *
 * 只看前 8 个字节，够用且不下载整首（§9.3 带宽预算）。
 * `Content-Type` 在这批源上**不可靠**（酷狗 CDN 有时给 `application/octet-stream`），
 * 所以 L3 的「能播」有一半要落在魔数上。
 */
internal object LxMagic {

    /** @return 判定到的容器；样本太短或对不上任何签名 ⇒ null（⛔ 不猜） */
    fun detect(head: ByteArray): LxContainer? {
        if (head.size >= 4) {
            when {
                head.asciiAt(0, "fLaC") -> return LxContainer.FLAC
                head.asciiAt(0, "OggS") -> return LxContainer.OGG
                head.asciiAt(0, "ID3") -> return LxContainer.MP3
            }
        }
        if (head.size >= 2) {
            val b0 = head[0].toInt() and 0xFF
            val b1 = head[1].toInt() and 0xFF
            // MPEG 帧同步：11 位全 1，且 layer 不是保留值、版本不是保留值
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return LxContainer.MP3
        }
        // M4A / AAC：`ftyp` 在偏移 4（前 4 字节是 box 长度）
        if (head.size >= 8 && head.asciiAt(4, "ftyp")) return LxContainer.M4A
        return null
    }

    /** [LxL3.Ok] 里存的是容器名（`String`），报告层要还原成枚举（方案 §5.4.1.6） */
    fun containerOf(magic: String?): LxContainer? =
        magic?.let { name -> LxContainer.values().firstOrNull { it.name == name } }

    private fun ByteArray.asciiAt(offset: Int, text: String): Boolean {
        if (size < offset + text.length) return false
        for (i in text.indices) {
            if (this[offset + i].toInt() and 0xFF != text[i].code) return false
        }
        return true
    }
}
