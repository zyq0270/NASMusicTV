package com.nasmusic.tv.backend.photo

/**
 * Jellyfin 照片库的**相册级查询**策略（§10.393）
 *
 * ## 为什么需要它（2026-11-12 电视实测 + 服务器直查取证）
 *
 * 症状：照片墙的 NAS（Jellyfin）来源「重新扫描」永远是 0 张。
 *
 * ⇒ 直查服务器（Jellyfin 10.11.5 / Windows 主机）才发现**不是服务器没照片，是查询形状够不着**：
 *
 * | 查询 | 结果 |
 * |---|---|
 * | `/Items?IncludeItemTypes=Photo&Recursive=true`（应用原样） | **0** |
 * | `/Items?IncludeItemTypes=Photo&ParentId=<相册 id>&Recursive=true` | **27**（单个相册） |
 * | `/Items?IncludeItemTypes=PhotoAlbum&Recursive=true` | **0** |
 * | `/Items?MediaTypes=Photo&Recursive=true` | **0** |
 *
 * ⇒ 全局递归集合按类型**求和**（51592 条逐类型统计）：
 * `Audio 31389 + MusicAlbum 13354 + MusicArtist 4462 + MusicVideo 1041 + Episode 811
 *  + Movie 305 + Folder 168 + Series 15 + Season 45 = 51590`，与总数 51592 差 2
 * ⇒ **Photo = 0、PhotoAlbum = 0** —— 照片**根本不在全局递归索引里**。
 *
 * ⛔ 结论：`homevideos`（家庭视频和照片）库里的照片挂在一层 `PhotoAlbum` 之下，
 * 而 Jellyfin 的全局递归查询**不下钻到这些相册** ⇒ 只有带 `ParentId=<相册 id>`
 * 的相册级查询才能取到它们。**应用侧的问题，不是服务器的问题。**
 *
 * ## 策略
 *
 * 1. **先走原来的全局递归查询** —— `photos` / `mixed` 库走这条（一次请求拿全）。
 * 2. **全局查询没拿到照片 ⇒ 走相册级回退**：列媒体库根 → 列根下的相册 → 逐个相册取照片。
 *
 * ## ⛔ 为什么不用 `/Library/VirtualFolders` / `/Library/MediaFolders`
 *
 * - `/Library/MediaFolders` 在 OpenAPI 里标的是 `RequiresElevation` ⇒ **需要管理员**，
 *   普通用户会 403 ⇒ 照片墙不能依赖它。
 * - `/Library/VirtualFolders` 虽然 `DefaultAuthorization` 可用，但它是**管理端**接口，
 *   语义上是「服务端怎么配的」，不属于「用户能看到什么」。
 *
 * ⇒ 改用 `/Items?Recursive=false` 列顶层媒体库根（`DefaultAuthorization`，普通用户即可，
 * 且响应里就带 `CollectionType`）⇒ 全程只用 `/Items`。
 *
 * ⚠️ 本类**纯逻辑、无 Android 依赖** ⇒ 可纯 JVM 单测（门禁见 `JellyfinPhotoLibraryTest`）。
 */
object JellyfinPhotoLibrary {

    // ── 条目类型 ────────────────────────────────────────────────────────

    /** 顶层媒体库根（`CollectionFolder`） */
    const val TYPE_COLLECTION_FOLDER = "CollectionFolder"

    /** 相册（`PhotoAlbum`）—— 照片墙要逐个下去查的容器 */
    const val TYPE_PHOTO_ALBUM = "PhotoAlbum"

    /**
     * 普通文件夹（`Folder`）
     *
     * ⛔ **不能只认 `PhotoAlbum`** —— 实测 `G:\photo\照片\宠物` 是普通 `Folder`，
     * 里面有 **92 张**照片 ⇒ 漏掉 `Folder` 就漏掉一批照片。
     */
    const val TYPE_FOLDER = "Folder"

    // ── 扫描参数 ────────────────────────────────────────────────────────

    /** 相册级查询单页条数 */
    const val ALBUM_PAGE_SIZE = 500

    /**
     * 一次扫描最多探测多少个「相册级容器」
     *
     * ⚠️ 防病态服务器：相册数可以是几千个 ⇒ 封顶，避免一次扫描打出上万请求。
     */
    const val MAX_ALBUM_QUERIES = 600

    /** 并发抓取相册的并行度 */
    const val ALBUM_CONCURRENCY = 8

    // ── 判定（纯逻辑，门禁对象）────────────────────────────────────────

    /** 该条目是不是「顶层媒体库根」 */
    fun isLibraryRoot(itemType: String?): Boolean =
        itemType != null && itemType.equals(TYPE_COLLECTION_FOLDER, ignoreCase = true)

    /**
     * 该条目是不是「相册级容器」（照片墙要逐个下去查的）
     *
     * - `PhotoAlbum` —— Jellyfin 给照片目录建的相册
     * - `Folder` —— 普通文件夹（**实测里面有照片**）
     */
    fun isAlbumContainer(itemType: String?): Boolean =
        itemType != null &&
            (itemType.equals(TYPE_PHOTO_ALBUM, ignoreCase = true) ||
                itemType.equals(TYPE_FOLDER, ignoreCase = true))

    /**
     * 全局递归查询之后，要不要走相册级回退。
     *
     * @param globalCount 全局查询拿到的照片数
     *
     * ⛔ **查询失败也要回退** —— 失败只说明「这条形状没拿到」，不代表「相册形状也拿不到」。
     * 回退本身是**自限**的：第一步（列媒体库根）失败就立刻收手，不会打出 600 个请求。
     */
    fun shouldFallbackToAlbums(globalCount: Int): Boolean = globalCount <= 0
}
