package com.nasmusic.tv.backend.photo

/**
 * 「NAS 连上后要不要**自动**扫照片墙」的判定（**纯逻辑**，2026-11-12 用户裁定）
 *
 * ## 用户要求（原文）
 *
 * > 「应该增加自动扫描的功能，无需手动，也可以强制手动重新扫描。
 * > 1. 只要连接了 NAS Jellyfin 服务器，并且照片墙中 Jellyfin 的开关打开的情况下，
 * > 连接 NAS 成功后，应该自动扫描」
 *
 * ## 判定口径
 *
 * | 条件 | 说明 |
 * |---|---|
 * | 后端类型 = `jellyfin` | 照片墙的 NAS 来源**只支持 Jellyfin**（其它后端的 `buildAuthenticatedUrl` 返回 null ⇒ 照片通道不可用） |
 * | 照片墙的 Jellyfin 开关 = 开 | 关着就不扫（用户明确不要这个来源）⇒ **不产生任何网络请求** |
 *
 * ⚠️ 本类**纯函数**（不碰网络、不碰 `android.*`）⇒ 可纯 JVM 单测。
 */
object PhotoAutoScanPolicy {

    /**
     * Jellyfin 后端的类型标识（与 `JellyfinAdapter.backendType` 一致）。
     *
     * ⚠️ 写成常量而不是直接引用适配器 ⇒ 让判定器保持「不依赖实现类」的纯逻辑。
     */
    const val JELLYFIN_BACKEND_TYPE = "jellyfin"

    /**
     * 是否该在「NAS 连接成功」时自动扫描照片墙。
     *
     * @param backendType 当前连接的后端类型（`null` = 没连上）
     * @param jellyfinSourceEnabled 照片墙设置里 Jellyfin 来源的开关
     */
    fun shouldAutoScan(backendType: String?, jellyfinSourceEnabled: Boolean): Boolean =
        jellyfinSourceEnabled && backendType == JELLYFIN_BACKEND_TYPE
}
