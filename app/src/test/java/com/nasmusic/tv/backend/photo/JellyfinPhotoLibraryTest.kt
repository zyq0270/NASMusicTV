package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **门禁：Jellyfin 相册级查询策略**（§10.393）
 *
 * ## 它防的是什么
 *
 * 2026-11-12 电视实测：照片墙的 NAS 来源扫不出照片。
 * ⇒ 根因是**查询形状**：应用原来只用全局递归查询
 * （`/Items?IncludeItemTypes=Photo&Recursive=true`），
 * 而 Jellyfin 的 `homevideos` 库照片挂在 `PhotoAlbum` 下，
 * **全局递归索引里一张照片都没有**（51592 条按类型求和 = 51590，Photo = 0）。
 *
 * ⇒ 这些判定把「哪些条目该继续往下查」固化成门禁：
 * - 顶层只认 `CollectionFolder`（媒体库根）
 * - 根下只认 `PhotoAlbum` **和** `Folder`（⚠️ 实测普通文件夹里也有照片：`宠物` 92 张）
 * - 全局查询拿到 0 张 ⇒ 必须回退
 */
class JellyfinPhotoLibraryTest {

    // ── 顶层媒体库根 ────────────────────────────────────────────────────

    @Test
    fun `CollectionFolder 是媒体库根`() {
        assertTrue(JellyfinPhotoLibrary.isLibraryRoot("CollectionFolder"))
    }

    @Test
    fun `非 CollectionFolder 不是媒体库根`() {
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot("PhotoAlbum"))
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot("Folder"))
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot("Movie"))
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot("Audio"))
    }

    @Test
    fun `null 与空串不是媒体库根`() {
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot(null))
        assertFalse(JellyfinPhotoLibrary.isLibraryRoot(""))
    }

    @Test
    fun `媒体库根判定大小写不敏感`() {
        assertTrue(JellyfinPhotoLibrary.isLibraryRoot("collectionfolder"))
    }

    // ── 相册级容器 ──────────────────────────────────────────────────────

    @Test
    fun `PhotoAlbum 是相册级容器`() {
        assertTrue(JellyfinPhotoLibrary.isAlbumContainer("PhotoAlbum"))
    }

    @Test
    fun `Folder 也是相册级容器`() {
        // ⛔ 这条是实测踩出来的：`G:\photo\照片\宠物` 是普通 Folder，里面有 92 张照片
        //    ⇒ 只认 PhotoAlbum 会漏掉一批照片
        assertTrue(JellyfinPhotoLibrary.isAlbumContainer("Folder"))
    }

    @Test
    fun `非相册类条目不是相册级容器`() {
        assertFalse(JellyfinPhotoLibrary.isAlbumContainer("Movie"))
        assertFalse(JellyfinPhotoLibrary.isAlbumContainer("Series"))
        assertFalse(JellyfinPhotoLibrary.isAlbumContainer("MusicAlbum"))
        assertFalse(JellyfinPhotoLibrary.isAlbumContainer("CollectionFolder"))
        assertFalse(JellyfinPhotoLibrary.isAlbumContainer(null))
    }

    // ── 回退判定 ────────────────────────────────────────────────────────

    @Test
    fun `全局查询 0 张必须回退到相册级`() {
        assertTrue(JellyfinPhotoLibrary.shouldFallbackToAlbums(0))
    }

    @Test
    fun `全局查询有照片就不回退`() {
        assertFalse(JellyfinPhotoLibrary.shouldFallbackToAlbums(1))
        assertFalse(JellyfinPhotoLibrary.shouldFallbackToAlbums(16700))
    }

    // ── 扫描参数自限 ────────────────────────────────────────────────────

    @Test
    fun `相册探测数有上限`() {
        // ⛔ 防病态服务器：相册数可以几千个 ⇒ 必须封顶，否则一次扫描打出上万请求
        assertTrue(JellyfinPhotoLibrary.MAX_ALBUM_QUERIES in 1..1000)
    }

    @Test
    fun `并发度大于 1 且不超过 16`() {
        // ⛔ 并发 1 = 没有回退的意义（400 个相册串行要等很久）；> 16 会压垮电视的连接池
        assertTrue(JellyfinPhotoLibrary.ALBUM_CONCURRENCY in 2..16)
    }
}
