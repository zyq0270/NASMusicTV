package com.nasmusic.tv.backend.photo

/**
 * 「上次扫描结果」的缓存与可用性判定（**纯逻辑**，2026-11-12 电视实测修复）
 *
 * ## 它解决什么
 *
 * 2026-11-11 电视实测：NAS 已连接，照片墙点「重新扫描」⇒ **找不到 NAS 里的照片**。
 *
 * ⇒ 根因链：
 * 1. [JellyfinPhotoSource] 为了让设置页能高频调用 `status()`（不能每次发网络请求），
 *    把「上次扫描到的总数」缓存了下来；
 * 2. 「上次是 0 张」被翻译成 [PhotoSourceStatus.NO_PHOTO_LIBRARY]；
 * 3. 而 [PhotoSourceAggregator] 是**先问 status、再决定要不要 listPhotos**
 *    ⇒ 缓存一旦成「无照片库」，之后每轮扫描**连 `listPhotos()` 都不会被调用**
 *    ⇒ 「重新扫描」永远扫不出东西。
 *
 * ⇒ 而且**一次网络抖动就足以触发**：查询失败时 `total` 停在 `-1`，
 * 被 `coerceAtLeast(0)` 变成 0 ⇒ 等同于「扫到 0 张」⇒ 来源被**永久判死**。
 *
 * ## 三条铁律
 *
 * | # | 规则 | 为什么 |
 * |---|---|---|
 * | 1 | **查询失败 ≠ 没有照片** | 失败时缓存 `UNKNOWN_TOTAL`，不缓存 0 |
 * | 2 | **扫描前必须作废缓存** | 否则「重新扫描」永远扫不出东西（见 [invalidate]） |
 * | 3 | **缓存只用于「两次扫描之间」的显示** | 不能用它决定「要不要扫」 |
 *
 * ⚠️ 本类**不碰网络、不碰 `android.*`** ⇒ 可纯 JVM 单测。
 */
internal class PhotoScanVerdict {

    /**
     * 上次扫描到的条目总数；[UNKNOWN_TOTAL] = 尚未扫描过 / 上次查询失败。
     *
     * ⛔ **它只是显示用的缓存**，绝不能用来挡住扫描 ⇒ 每轮扫描前先 [invalidate]。
     */
    @Volatile
    var lastKnownTotal: Int = UNKNOWN_TOTAL
        private set

    /** 扫描**成功**且服务器确实说 0 张 ⇒ 记 0（设置页据此显示「已连但无照片库」） */
    fun onScanSucceeded(total: Int) {
        lastKnownTotal = total.coerceAtLeast(0)
    }

    /** 扫描**失败**（网络 / HTTP / 解析）⇒ 记「未知」，**不**判成 0 张 */
    fun onScanFailed() {
        lastKnownTotal = UNKNOWN_TOTAL
    }

    /**
     * 作废缓存判定 ⇒ 让下一轮扫描**真的**去问服务器。
     *
     * ⛔ 不做这一步的话：一次失败（或一次真的 0 张）就会把来源永久判死，
     * 用户点「重新扫描」也没有用。
     */
    fun invalidate() {
        lastKnownTotal = UNKNOWN_TOTAL
    }

    /** 已连接时的状态：**只有「确实扫到 0 张」才算「无照片库」** */
    fun statusWhenConnected(): PhotoSourceStatus =
        if (lastKnownTotal == 0) PhotoSourceStatus.NO_PHOTO_LIBRARY else PhotoSourceStatus.OK

    companion object {
        /** 尚未扫描过 / 上次查询失败 */
        const val UNKNOWN_TOTAL = -1
    }
}
