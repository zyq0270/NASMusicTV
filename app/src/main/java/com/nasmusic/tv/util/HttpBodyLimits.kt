package com.nasmusic.tv.util

import okio.BufferedSource

/**
 * 有上限地读取 HTTP 响应正文。
 *
 * ⚠️ 必须**循环**读：okio 的 [BufferedSource.read] 每次至多从底层搬一个 segment（8 KiB）
 * 就返回，只调用一次再拿 `read > maxBytes` 判超限是**恒假**的 —— 得到的只是被截断的前缀
 * （2026-11-23 审查 G3-01：搜索结果被截成半个 JSON、洛雪脚本被截成半截却仍过 `@name` 校验）。
 *
 * @return 完整正文；超过 [maxBytes] 或读取抛错时返回 null（调用方按「本次失败」处理）。
 */
internal fun readBodyLimited(source: BufferedSource, maxBytes: Long): String? {
    if (maxBytes <= 0L) return null
    return try {
        val buffer = okio.Buffer()
        // 多读 1 字节即可判定「是否超限」，不需要把超大正文全读进内存。
        var remaining = maxBytes + 1
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        if (buffer.size > maxBytes) {
            buffer.clear()
            null
        } else {
            buffer.readUtf8()
        }
    } catch (e: Exception) {
        null
    }
}
