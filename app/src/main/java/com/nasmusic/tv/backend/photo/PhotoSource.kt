package com.nasmusic.tv.backend.photo

import java.io.InputStream

/**
 * 照片来源种类
 *
 * 三个来源**互相独立**（各有自己的开关），合并成一个池随机展示（§6.8）。
 */
enum class PhotoSourceKind {
    /** 手机系统图库（MediaStore 索引） */
    GALLERY,

    /** 外接存储：U 盘 / SD 卡（File 遍历主 + SAF 兜底，§6.3） */
    EXTERNAL,

    /** Jellyfin 照片库（`IncludeItemTypes=Photo`，§6.4） */
    JELLYFIN,
}

/**
 * 来源可用性
 *
 * ⚠️ 用枚举而不是 String：**数据层不产出面向用户的文案**
 * （文案统一在 `strings.xml`，项目硬约定）。设置页拿到枚举后自己映射文案。
 */
enum class PhotoSourceStatus {
    /** 可用 */
    OK,

    /** 开关关着 */
    DISABLED,

    /** 权限被拒（仅图库） */
    PERMISSION_DENIED,

    /** Android 14+「仅选择照片」的部分授权（仅图库，§9.6） */
    PARTIAL_PERMISSION,

    /** 手机外接存储尚未指定目录 */
    NO_DIRECTORY,

    /** Jellyfin：未连接 NAS */
    NOT_CONNECTED,

    /** Jellyfin：已连接但没有照片库 */
    NO_PHOTO_LIBRARY,

    /** 其他（盘已拔 / 目录不可达 / SAF 授权失效） */
    UNAVAILABLE,
}

/**
 * 一张照片的**元数据**（不含位图）
 *
 * ⛔ **宽高契约**（§14.2.1）：`width` / `height` 是**可选**元数据，`0` = 未探测。
 * - `MediaStore` / `Jellyfin` 查询时**免费**拿到宽高 ⇒ 必须填真实值
 * - `ExternalFilePhotoSource` 要拿宽高得**每个文件开一次流**（`inJustDecodeBounds`）
 *   ⇒ 填 `0`，由 `PhotoBuffer` 在解码那一刻现算（反正已经拿到流了）
 *
 * ⛔ **时间契约**：`lastModified` / `dateAdded` 一律**秒**。
 * 归一化责任在**各 PhotoSource 实现内**（`MediaStore` 与 `File` 原生就是秒；
 * Jellyfin 返回 ISO 8601 或 tick，必须转换）—— 因为跨来源去重依赖它（§6.1）。
 *
 * ⛔ **`capturedAt` 与上面两个不是一回事**（2026-11-13 照片墙日期水印）：
 * - `lastModified` / `dateAdded` 是**文件系统时间**（拷贝 / 重命名 / 同步都会改）
 * - `capturedAt` 是**拍摄时间**（EXIF `DateTimeOriginal`，没有才回落到文件时间）
 *
 * 它**只用于展示**（照片墙右下角的日期水印），**绝不参与去重 / 排序 / 人脸键** ——
 * 那些依赖的是稳定且各来源口径一致的字段，而 EXIF 只在解码那一刻才读得到。
 */
data class PhotoRef(
    /** `"<kind>:<payload>"` —— 全局唯一（见 [PhotoIds]） */
    val id: String,
    val displayName: String,
    /** ⚠️ 0 = 未探测（见上方「宽高契约」） */
    val width: Int,
    val height: Int,
    /** 字节数；未知填 0 */
    val size: Long,
    /** ⚠️ **秒** */
    val lastModified: Long,
    /** ⚠️ **秒** */
    val dateAdded: Long,
    val source: PhotoSourceKind,
    /**
     * **拍摄时间（epoch 秒，本地时区已折算）**；`0` = 未知（连回落的文件时间都没有）。
     *
     * 取值顺序（由 `PhotoBuffer` 在解码后用 [PhotoCaptureDate.resolve] 决定）：
     * 1. EXIF `DateTimeOriginal`（相机原生记录；`DateTimeDigitized` / `DateTime` 兜底）
     * 2. 来源给的近似值（`MediaStore.DATE_TAKEN`；Jellyfin `PremiereDate` / `DateCreated`）
     * 3. `lastModified` → `dateAdded`（都没有就留 0 ⇒ 不显示水印）
     *
     * ⚠️ **默认 `0`**：单测 / 旧调用点不传也编得过，且语义是「不知道」而不是「1970 年」。
     */
    val capturedAt: Long = 0L,
)

/**
 * EXIF / 拍摄日期字符串 → **epoch 秒**（纯函数，无 Android 依赖 ⇒ 可直接单测）
 *
 * ## 支持的写法
 *
 * | 来源 | 样例 |
 * |---|---|
 * | EXIF `DateTimeOriginal` / `DateTimeDigitized` / `DateTime` | `2021:09:13 12:26:40` |
 * | EXIF（部分机型带毫秒 / 时区） | `2021:09:13 12:26:40.123`、`2021:09:13 12:26:40+08:00` |
 * | ISO 8601（Jellyfin `PremiereDate`） | `2021-09-13T12:26:40.1234567Z` |
 * | 纯数字 | `1631536000` |
 *
 * ## ⛔ 为什么手写而不用 `SimpleDateFormat`
 *
 * EXIF 的格式是**非标准**的（日期段用 `:` 而不是 `-`），而机型差异又多（毫秒 / 时区 / 末尾空格）
 * ⇒ `SimpleDateFormat` 要么写一堆 `parse` 兜底、要么每张照片都抛异常（异常 = 分配 = 解码线程负担）。
 * 这里直接按位解析：**零异常、零分配**（除了极少数需要拼串的分支）。
 *
 * ## ⚠️ 时区口径
 *
 * EXIF 记录的是**相机本地时间**、且**不带时区** ⇒ 按**系统当前时区**解释
 * （相机与电视通常在同一时区，用户看到的日期才与「当时拍的那天」一致）。
 * 带显式 `Z` / `±hh:mm` 的写法按给定时区解释。
 */
object PhotoCaptureDate {

    /** 年份下限：早于它的值一律判为「脏数据」（相机没设时间时会写出 1970 / 1980 / 0000） */
    const val MIN_VALID_YEAR = 1900

    /** 年份上限：晚于它的值判为脏数据（时钟错乱） */
    const val MAX_VALID_YEAR = 2200

    /**
     * 解析一个日期字符串。
     *
     * @param text 原始字符串；`null` / 空 ⇒ 返回 `0`
     * @param zone 无时区信息时按哪个时区解释（单测注入固定时区 ⇒ 不随机器时区变化）
     * @return epoch 秒；无法解析或落在 [MIN_VALID_YEAR]..[MAX_VALID_YEAR] 之外 ⇒ `0`
     */
    fun parse(text: String?, zoneIn: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        var zone = zoneIn
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        // 纯数字 = 已经是 epoch 秒（Jellyfin 少数版本 / 部分机型会这么写）
        if (s.all { it.isDigit() }) {
            val v = s.toLongOrNull() ?: return 0L
            return if (isPlausibleEpochSeconds(v)) v else 0L
        }

        var i = 0
        // ⛔ 尾部带 `Z` ⇒ 整串按 **UTC** 解释（不能先按本地时区算完再减偏移：
        //    那样 `Z` 会被重复补偿一次，东八区机器上整整差 8 小时 —— 单测抓到过）
        val hasZuluSuffix = s.endsWith("Z", ignoreCase = true)
        if (hasZuluSuffix) zone = java.util.TimeZone.getTimeZone("UTC")
        val y = readNumber(s, i, 4) ?: return 0L
        i = y.second
        if (y.first < MIN_VALID_YEAR || y.first > MAX_VALID_YEAR) return 0L
        i = skipSeparator(s, i) ?: return 0L
        val mo = readNumber(s, i, 2) ?: return 0L
        i = skipSeparator(s, mo.second) ?: return 0L
        val d = readNumber(s, i, 2) ?: return 0L
        i = d.second
        // 日期后面允许直接结束（只给了日期），也允许接时间
        if (i >= s.length) return toEpochSeconds(y.first, mo.first, d.first, 0, 0, 0, zone) ?: 0L
        i = skipSeparator(s, i) ?: return 0L
        val h = readNumber(s, i, 2) ?: return 0L
        i = skipSeparator(s, h.second) ?: return 0L
        val mi = readNumber(s, i, 2) ?: return 0L
        i = mi.second
        var sec = 0
        if (i < s.length && s[i] == ':' && i + 1 < s.length && s[i + 1].isDigit()) {
            val se = readNumber(s, i + 1, 2) ?: return 0L
            sec = se.first
            i = se.second
        }

        // ── 尾部：毫秒 / 时区 ──
        var zoneOffsetSec: Int? = null
        while (i < s.length) {
            when (s[i]) {
                '.', ',' -> {
                    // 跳过小数部分（毫秒 / 亚秒，展示只用秒）
                    i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                'Z', 'z' -> { zoneOffsetSec = 0; i = s.length }
                '+', '-' -> {
                    val off = readOffset(s, i)
                    if (off != null) {
                        zoneOffsetSec = off.first
                        i = off.second
                    } else {
                        i = s.length
                    }
                }
                ' ', '\t' -> i++
                else -> i = s.length
            }
        }

        // 带 `Z` 时 `zone` 已经是 UTC，`zoneOffsetSec` 就是 0 ⇒ 再减一次也无副作用
        val local = toEpochSeconds(y.first, mo.first, d.first, h.first, mi.first, sec, zone) ?: return 0L
        return local - (zoneOffsetSec ?: 0)
    }

    /**
     * **拍摄时间的最终取值**（照片墙水印的唯一口径）。
     *
     * @param exif EXIF 解析结果（`0` = 该图没有 / 读不出）
     * @param fromSource 来源给的近似拍摄时间（`MediaStore.DATE_TAKEN` 等；`0` = 未知）
     * @param lastModified 文件修改时间（秒）
     * @param dateAdded 入库时间（秒）
     */
    fun resolve(exif: Long, fromSource: Long, lastModified: Long, dateAdded: Long): Long = when {
        exif > 0L -> exif
        fromSource > 0L -> fromSource
        lastModified > 0L -> lastModified
        dateAdded > 0L -> dateAdded
        else -> 0L
    }

    /** epoch 秒是否落在 **1980–2200** 区间（挡住 0 / 1 / 毫秒误传这类脏值） */
    fun isPlausibleEpochSeconds(seconds: Long): Boolean =
        seconds >= LOWER_EPOCH_SECONDS && seconds <= UPPER_EPOCH_SECONDS

    /**
     * 下限 = **1980-01-01T00:00:00Z**。
     *
     * ⚠️ 它**比 `MIN_VALID_YEAR`（1900）更严**，这是刻意的：
     * 纯数字分支没有「年份」可看，只能靠区间挡脏值；而 1980 早于数字相机与手机的普及，
     * 落在它之前的值必然是脏数据（相机断电后回到 1970 / 0 / 1 这类）。**两种上限**：
     * `MIN_VALID_YEAR` 管带分隔符的日期串（1900–2200），本区间管纯数字与最终采用值。
     */
    private const val LOWER_EPOCH_SECONDS = 315_532_800L

    /** 上限 = **2200-01-01T00:00:00Z** */
    private const val UPPER_EPOCH_SECONDS = 7_258_118_400L

    /** 读完 [count] 位数字；返回 (值, 结束下标) */
    private fun readNumber(s: String, start: Int, count: Int): Pair<Int, Int>? {
        var v = 0
        var i = start
        val end = minOf(start + count, s.length)
        if (start >= end) return null
        var digits = 0
        while (i < end && s[i].isDigit()) {
            v = v * 10 + (s[i] - '0')
            digits++
            i++
        }
        return if (digits == 0) null else v to i
    }

    /** 跳一个分隔符（`:` `-` `/` `.` `T` 空格均可），返回下一个位置；已到末尾返回 [Int.MAX_VALUE] */
    private fun skipSeparator(s: String, i: Int): Int? {
        if (i >= s.length) return Int.MAX_VALUE
        val c = s[i]
        if (c == ':' || c == '-' || c == '/' || c == 'T' || c == ' ' || c == '.') return i + 1
        return null
    }

    /** 读 `±hh:mm` / `±hhmm` / `±hh`，返回 (偏移秒, 结束下标) */
    private fun readOffset(s: String, start: Int): Pair<Int, Int>? {
        val sign = if (s[start] == '-') -1 else 1
        var i = start + 1
        val hh = readNumber(s, i, 2) ?: return null
        i = hh.second
        var mm = 0
        if (i < s.length && (s[i] == ':' || s[i].isDigit())) {
            if (s[i] == ':') i++
            val r = readNumber(s, i, 2)
            if (r != null) {
                mm = r.first
                i = r.second
            }
        }
        return sign * (hh.first * 3600 + mm * 60) to i
    }

    /**
     * 本地时区的 `(y, mo, d, h, mi, s)` → epoch 秒。
     *
     * ⛔ 不用 `java.time`（需要 API 26，本项目 minSdk 22 / 电视是 5.1.1）
     * ⇒ 走 `Calendar`，它会把不合法日期（如 `2021-02-30`）自动规范化 ——
     * 相机写错的日子不会让整张图没有日期，只是显示成相邻的合法日。
     */
    private fun toEpochSeconds(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        zone: java.util.TimeZone,
    ): Long? {
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) {
            return null
        }
        val cal = java.util.Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second.coerceAtMost(59))
        return cal.timeInMillis / 1000L
    }
}

/**
 * 照片来源抽象
 *
 * 实现类必须是**可重复调用**的：`listPhotos()` 不缓存（缓存由上层
 * `PhotoWallController` / `PhotoBuffer` 负责），`openStream()` 每次都开新流。
 */
interface PhotoSource {

    val kind: PhotoSourceKind

    /**
     * 当前状态（供设置页显示「为什么不可用」）。
     *
     * ⛔ **不申请权限、不扫描** —— 只做轻量判定，可被设置页高频调用。
     *
     * ⚠️ **返回值可能被缓存**（如 Jellyfin 来源会记住「上次扫到 0 张」）⇒
     * 缓存一旦过期就会**挡住下一次扫描** ⇒ 见 [invalidateStatus]。
     */
    suspend fun status(): PhotoSourceStatus

    /**
     * **作废缓存的可用性判定**（每轮扫描开始前调用）。
     *
     * ## 为什么必须有这个钩子（2026-11-12 电视实测修复）
     *
     * `JellyfinPhotoSource` 为了让设置页能高频调用 `status()`（不能每次发网络请求），
     * 把「上次扫描到的总数」缓存了下来 ⇒ 「上次是 0 张」被翻译成
     * [PhotoSourceStatus.NO_PHOTO_LIBRARY]。
     *
     * ⛔ 而聚合器是**先问 status、再决定要不要 listPhotos** ⇒
     * 一旦缓存成「无照片库」，**之后每一轮扫描都被挡掉、连 `listPhotos()` 都不会被调用**
     * ⇒ 用户点「重新扫描」永远扫不出东西（电视实测：NAS 已连接、点重新扫描、找不到照片）。
     * ⇒ 而且**一次网络抖动就足以触发**（查询失败时 `total` 停在 -1，被 `coerceAtLeast(0)`
     * 变成 0 ⇒ 等同于「扫到 0 张」）⇒ 来源被**永久判死**。
     *
     * ⇒ 修法：**每轮扫描前先作废缓存**，让状态重新由「当前事实」推导。
     * 默认实现是**空操作** —— 只有「会缓存状态」的来源才需要覆写。
     */
    fun invalidateStatus() {}

    /**
     * 只读元数据，**不加载位图**。
     *
     * 不可用时返回**空表**（不抛异常）—— 调用方用 [status] 区分「空」与「不可用」。
     */
    suspend fun listPhotos(): List<PhotoRef>

    /**
     * 打开原始字节流（供 `PhotoBuffer` 降采样解码）。
     *
     * @return 失败返回 null（不抛异常）
     */
    suspend fun openStream(ref: PhotoRef): InputStream?
}

/**
 * `PhotoRef.id` 的构造 / 解析工具
 *
 * 格式 `"<kind>:<payload>"`，`kind` 用小写枚举名。
 * ⛔ 全局唯一性靠 **payload 本身带来源内唯一标识**（MediaStore 行 id / 绝对路径 /
 * SAF document URI / Jellyfin item id）保证 —— 不同来源的**同名文件**不会冲突。
 */
object PhotoIds {

    private const val SEPARATOR = ':'

    fun of(kind: PhotoSourceKind, payload: String): String =
        kind.name.lowercase() + SEPARATOR + payload

    /** 解析来源；格式非法返回 null */
    fun kindOf(id: String): PhotoSourceKind? {
        val head = id.substringBefore(SEPARATOR, missingDelimiterValue = "")
        if (head.isEmpty() || !id.contains(SEPARATOR)) return null
        return PhotoSourceKind.entries.firstOrNull { it.name.equals(head, ignoreCase = true) }
    }

    /** 解析 payload（`:` 之后**全部**内容 —— 绝对路径 / URI 里都可能有 `:`） */
    fun payloadOf(id: String): String? {
        val idx = id.indexOf(SEPARATOR)
        if (idx <= 0 || idx == id.length - 1) return null
        return id.substring(idx + 1)
    }
}

/**
 * 支持的图片扩展名**白名单**（小写，不含点）
 *
 * ⛔ **用白名单不用黑名单** —— 而且要**显式排除 `heic` / `heif`**：
 * 电视是 Android 5.1.1（API 22），系统解码器不认 HEIC，放进去只会得到一堆解码失败。
 */
val PHOTO_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")

/**
 * 文件名是否属于支持的图片格式（大小写不敏感）。
 *
 * ⛔ **这是「这个文件名算不算照片」的唯一定义处** —— 三条来源（MediaStore / File 遍历 /
 * SAF 遍历）都必须过这里，否则会出现「同一种文件在一条路上被排除、在另一条路上被纳入」
 * 的不一致（实现期实测：SAF 路线单独写了隐藏文件过滤，File 路线漏了）。
 *
 * ⛔ **以点开头一律拒绝**（Unix 隐藏文件惯例）：
 * - `.nomedia` —— 这个目录的标记文件本身不是照片
 * - `.thumb.jpg` / `.a.jpg` —— 隐藏文件不该展示
 * - `.jpg` —— 点开头且**没有基名**，`lastIndexOf('.') == 0` 是同一个坑的极端形态
 *   （实现期单测抓到的边界：只判 `dot < 0` 会让它通过）
 */
fun isSupportedPhotoName(fileName: String): Boolean {
    if (fileName.startsWith(".")) return false
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.length - 1) return false
    return fileName.substring(dot + 1).lowercase() in PHOTO_EXTENSIONS
}

/**
 * 路径（如 Jellyfin `Path` 字段返回的**实际文件路径**）是否指向受支持的照片格式。
 *
 * 先剥掉目录部分（兼容 Windows `\` 与 POSIX `/` 两种分隔符）取文件名，
 * 再过 [isSupportedPhotoName] 白名单。
 *
 * ⛔ 为什么需要它：Jellyfin 照片条目的 `Name` 字段**不带扩展名**（如 `0001`、`图`），
 * 直接拿 Name 过白名单会把**所有**照片滤掉（v2.37.4 修复，见 technical-overview §10.188）。
 * 扩展名只存在于 `Path` 字段（如 `G:\photo\照片\0001.jpg`），必须从那里取。
 */
fun isSupportedPhotoPath(path: String): Boolean {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return isSupportedPhotoName(fileName)
}
