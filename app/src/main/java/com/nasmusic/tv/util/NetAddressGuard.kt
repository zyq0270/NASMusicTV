package com.nasmusic.tv.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 出站地址守卫 —— 防止「由**不可信内容**拼出来的 URL」把请求打到本机 / 内网 / 云元数据。
 *
 * ## 为什么要它
 *
 * 洛雪音源脚本（`backend/network/lx`）与歌单导入的 URL 都来自**网络**，脚本里
 * `lx.request(url)` 的 url 完全由远端 JS 拼出。若不加限制，一段恶意脚本就能：
 * - 打 `127.0.0.1` / 局域网地址，把家庭 NAS、路由器管理页的响应原样读回来；
 * - 打云厂商元数据地址（`169.254.169.254`），读走实例凭据；
 * - 借 APP 的网络身份做端口探测（响应码 / 耗时差异足够推断内网拓扑）。
 *
 * ## 覆盖范围与已知残余风险
 *
 * 只做**字面量**判定（IP 字面量 + 内网主机名），**不做 DNS 解析**：
 * `startRequest` 跑在 JS 回调线程上，同步 DNS 会阻塞解释器；而 OkHttp 自己解析出的地址
 * 我们无法在连接层拦截（未引入自定义 Dns）。因此「公网域名解析到内网 IP」（DNS rebinding）
 * 仍可能穿透，属已知残余风险。要彻底封堵需给 OkHttp 注入 `Dns` 实现，在 resolve 后校验。
 */
object NetAddressGuard {

    /** 云厂商元数据服务地址（AWS/GCP/Azure/阿里云/腾讯云都用 169.254.169.254，部分用 100.100.100.200）。 */
    private val METADATA_HOSTS = setOf(
        "169.254.169.254",
        "100.100.100.200",
        "metadata.google.internal",
        "metadata.goog",
        "instance-data",
    )

    /** 明确指向本机的主机名（`localhost.localdomain` 之类一并覆盖）。 */
    private fun isLocalHostName(host: String): Boolean {
        val h = host.trimEnd('.').lowercase()
        return h == "localhost" ||
            h.endsWith(".localhost") ||
            h == "local" ||
            h.endsWith(".local") ||
            h == "ip6-localhost" ||
            h == "ip6-loopback"
    }

    /**
     * 主机是否**必须拒绝**。
     *
     * @param host 已由 [okhttp3.HttpUrl] 归一化（IPv6 会带方括号，这里自行剥离）
     */
    fun isBlockedHost(host: String): Boolean {
        val h = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        if (isLocalHostName(h)) return true
        if (h in METADATA_HOSTS) return true
        // IPv6
        if (h.contains(':')) return isBlockedIpv6(h)
        // 纯数字形式的 IPv4（含 127.1 这类省略写法由 OkHttp 归一化为 127.0.0.1）
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        return isBlockedIpv4(nums[0], nums[1])
    }

    private fun isBlockedIpv4(a: Int, b: Int): Boolean = when {
        a == 0 -> true                    // 0.0.0.0/8「本网络」
        a == 10 -> true                   // 10/8 私网
        a == 127 -> true                  // 127/8 回环
        a == 169 && b == 254 -> true      // 169.254/16 链路本地（含云元数据）
        a == 172 && b in 16..31 -> true   // 172.16/12 私网
        a == 192 && b == 168 -> true      // 192.168/16 私网
        a == 100 && b in 64..127 -> true  // 100.64/10 CGNAT（阿里云元数据落在 100.100/16）
        a == 192 && b == 0 -> true        // 192.0.0/24 IETF 保留
        a == 198 && b in 18..19 -> true   // 198.18/15 基准测试网段
        a >= 224 -> true                  // 224/4 组播 + 240/4 保留（含 255.255.255.255）
        else -> false
    }

    private fun isBlockedIpv6(h: String): Boolean {
        if (h == "::" || h == "::1") return true
        // 展开首段以判定前缀
        val head = h.substringBefore(':')
        if (head.isEmpty()) return true   // ::xxxx 这类压缩形式一律收紧拒绝（本地/未指定地址）
        val first = head.toIntOrNull(16) ?: return false
        return when {
            first and 0xFE00 == 0xFC00 -> true   // fc00::/7 唯一本地地址
            first and 0xFFC0 == 0xFE80 -> true   // fe80::/10 链路本地
            first and 0xFF00 == 0xFF00 -> true   // ff00::/8 组播
            else -> false
        }
    }

    /**
     * URL 是否允许作为出站目标：仅 `http` / `https`，且主机不在 [isBlockedHost] 命中集。
     *
     * @return 允许时返回 null；拒绝时返回**可直接展示给用户/写日志的中文原因**
     */
    fun rejectReason(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "URL 不合法"
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return "只允许 http/https（收到 ${parsed.scheme}）"
        }
        if (isBlockedHost(parsed.host)) {
            return "目标指向本机或内网地址，已拒绝：${parsed.host}"
        }
        return null
    }

    /**
     * 主机是否属于「**绝不可能是合法媒体来源**」的地址（C18）。
     *
     * 与 [isBlockedHost] 的区别：**放行 RFC1918 私网与 `.local` 主机名**。
     * 歌单里的直链经常指向用户自己的 NAS（`192.168.1.10` / `nas.local`），那是正常用法；
     * 但回环 / 链路本地（含云元数据）/ 未指定 / 组播**没有任何**"用户自己的媒体服务器"语义，
     * 出现即视为恶意歌单诱导的内网探测。
     */
    fun isNeverPlayableHost(host: String): Boolean {
        val h = host.removeSurrounding("[", "]").trimEnd('.').lowercase()
        if (h.isEmpty()) return true
        // ⚠️ 这里不能用 isLocalHostName：它把 `*.local`（mDNS）也算本机，
        //    而家庭 NAS 用 `nas.local` 是很常见的写法。
        if (h == "localhost" || h.endsWith(".localhost") ||
            h == "ip6-localhost" || h == "ip6-loopback"
        ) {
            return true
        }
        if (h in METADATA_HOSTS) return true
        if (h.contains(':')) return isBlockedIpv6(h)
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums[0] to nums[1]
        return when {
            a == 0 -> true                    // 0.0.0.0/8「本网络」
            a == 127 -> true                  // 127/8 回环
            a == 169 && b == 254 -> true      // 169.254/16 链路本地（含云元数据）
            a == 100 && b in 64..127 -> true  // 100.64/10 CGNAT（阿里云元数据在 100.100/16）
            a == 192 && b == 0 -> true        // 192.0.0/24 IETF 保留
            a == 198 && b in 18..19 -> true   // 198.18/15 基准测试网段
            a >= 224 -> true                  // 组播 + 保留
            else -> false                     // 10/8、172.16/12、192.168/16 放行（用户 NAS）
        }
    }

    /**
     * 播放 / 探测类目标校验（C18）：在 [isNeverPlayableHost] 的基础上要求 http(s)。
     *
     * @return 允许时 null；否则为中文原因
     */
    fun rejectReasonForPlayback(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return "URL 不合法"
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            return "只允许 http/https（收到 ${parsed.scheme}）"
        }
        if (isNeverPlayableHost(parsed.host)) {
            return "目标指向本机/链路本地/元数据地址，已拒绝：${parsed.host}"
        }
        return null
    }

    /**
     * 脚本侧**不允许**自行设置的请求头。
     *
     * `Host` 可以制造虚拟主机绕过；`Proxy-*` 与 `Connection` 会干扰连接管理；
     * `Content-Length` / `Transfer-Encoding` 由 OkHttp 按实际 body 计算，手改只会得到畸形请求。
     */
    val FORBIDDEN_REQUEST_HEADERS: Set<String> = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
        "proxy-authorization",
        "proxy-connection",
        "upgrade",
    )
}
