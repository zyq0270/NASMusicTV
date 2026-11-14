package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * **门禁：拍摄日期解析**（2026-11-13 照片墙日期水印）
 *
 * ## 它防的是什么
 *
 * 日期水印的格式是所有者指定的 `YYYY/MM/DD hh:mm:ss`，而**原始字符串的写法有三种以上**
 * （EXIF 用 `:` 分隔日期、ISO 8601 用 `-` 和 `T`、Jellyfin 带 7 位小数与 `Z`）。
 * 解析写错不会崩、也不会有日志 —— 只会让照片右下角显示一个**错的日期**（或干脆不显示），
 * 只有真机上看才会发现。⇒ 用纯 JVM 单测把每一种写法钉死。
 *
 * ⚠️ 本测试**不需要 Robolectric**：`PhotoCaptureDate` 刻意不碰 `android.*`。
 *
 * ⚠️ 时区一律**显式注入**：默认时区随机器变，断言会在一台机器绿、另一台红。
 */
class PhotoCaptureDateTest {

    /** UTC+8（相机与国内用户的常见时区） */
    private val shanghai: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    /** UTC（对照用：验证「偏移量真的被减掉了」） */
    private val utc: TimeZone = TimeZone.getTimeZone("GMT+00:00")

    // ────────────────────── ① EXIF 原生写法 ──────────────────────

    @Test
    fun `EXIF DateTimeOriginal 冒号分隔的写法必须解析成本地时间`() {
        // 2021-09-13 12:26:40 +08:00 = 1631507200
        assertEquals(
            "EXIF 的日期段用冒号（非标准 ISO），按注入时区解释",
            1631507200L,
            PhotoCaptureDate.parse("2021:09:13 12:26:40", shanghai),
        )
        assertEquals(
            "同一串按 UTC 解释必须差 8 小时（证明时区真的参与了换算）",
            1631536000L,
            PhotoCaptureDate.parse("2021:09:13 12:26:40", utc),
        )
    }

    @Test
    fun `EXIF 带毫秒与末尾空格也必须解析`() {
        assertEquals(1631507200L, PhotoCaptureDate.parse("2021:09:13 12:26:40.123", shanghai))
        assertEquals(1631507200L, PhotoCaptureDate.parse("  2021:09:13 12:26:40  ", shanghai))
    }

    @Test
    fun `EXIF 带显式时区偏移时必须按该偏移解释`() {
        // 12:26:40 +08:00 ⇒ UTC 04:26:40 ⇒ 与「按 UTC 解析 04:26:40」相同
        val withOffset = PhotoCaptureDate.parse("2021:09:13 12:26:40+08:00", utc)
        val asUtcSameInstant = PhotoCaptureDate.parse("2021:09:13 04:26:40", utc)
        assertEquals(
            "带 +08:00 的写法必须被折算成同一时刻（与本机时区无关）",
            asUtcSameInstant,
            withOffset,
        )
    }

    // ────────────────────── ② ISO 8601（Jellyfin）──────────────────────

    @Test
    fun `ISO 8601 带 7 位小数与 Z 的写法必须解析`() {
        // Jellyfin 实测格式：2026-09-23T10:11:12.3456789Z
        val fromIso = PhotoCaptureDate.parse("2026-09-23T10:11:12.3456789Z", shanghai)
        val asUtc = PhotoCaptureDate.parse("2026-09-23 10:11:12", utc)
        assertEquals("带 Z 的写法按 UTC 解释，不受注入时区影响", asUtc, fromIso)
    }

    @Test
    fun `只给日期的写法按当天零点解析`() {
        val d = PhotoCaptureDate.parse("2021-09-13", shanghai)
        assertEquals(
            "只给日期 ⇒ 时刻部分是 00:00:00（不能退化成「解析失败」）",
            PhotoCaptureDate.parse("2021-09-13 00:00:00", shanghai),
            d,
        )
    }

    // ────────────────────── ③ 纯数字（少数后端）──────────────────────

    @Test
    fun `纯数字必须当作 epoch 秒`() {
        assertEquals(1631536000L, PhotoCaptureDate.parse("1631536000"))
    }

    @Test
    fun `纯数字但不是合理时刻的必须判 0`() {
        assertEquals("1970 年附近的小值 = 脏值（相机断电后常见）", 0L, PhotoCaptureDate.parse("1"))
        assertEquals("0 = 明确表示「未知」", 0L, PhotoCaptureDate.parse("0"))
        assertEquals("1979 年早于 1980 门槛 ⇒ 判脏值", 0L, PhotoCaptureDate.parse("300000000"))
    }

    // ────────────────────── ④ 脏值 / 非法输入 ──────────────────────

    @Test
    fun `空值与无法解析的字符串必须返回 0`() {
        assertEquals(0L, PhotoCaptureDate.parse(null))
        assertEquals(0L, PhotoCaptureDate.parse(""))
        assertEquals(0L, PhotoCaptureDate.parse("   "))
        assertEquals(0L, PhotoCaptureDate.parse("unknown"))
        assertEquals(0L, PhotoCaptureDate.parse("0000:00:00 00:00:00"))
        assertEquals(0L, PhotoCaptureDate.parse("abc2021:09:13"))
    }

    @Test
    fun `相机没设时间的 1970 与 1980 必须判 0`() {
        // 相机断电后常见写法：日期回到出厂值
        assertEquals("1970-01-01 ⇒ 早于 1980 门槛", 0L, PhotoCaptureDate.parse("1970:01:01 00:00:00", utc))
        assertTrue("1980 是合理年份，必须保留", PhotoCaptureDate.parse("1980:01:01 00:00:00", utc) > 0L)
    }

    @Test
    fun `月份或小时越界必须返回 0`() {
        assertEquals(0L, PhotoCaptureDate.parse("2021:13:13 12:00:00", shanghai))
        assertEquals(0L, PhotoCaptureDate.parse("2021:09:13 25:00:00", shanghai))
    }

    @Test
    fun `epoch 秒的合理性判定`() {
        assertTrue(PhotoCaptureDate.isPlausibleEpochSeconds(1631536000L))
        assertFalse(PhotoCaptureDate.isPlausibleEpochSeconds(0L))
        assertFalse("1970 附近的小值 = 脏值", PhotoCaptureDate.isPlausibleEpochSeconds(1L))
        assertFalse("负值 = 脏值", PhotoCaptureDate.isPlausibleEpochSeconds(-1L))
        assertFalse("1979 年早于 1980 门槛", PhotoCaptureDate.isPlausibleEpochSeconds(300_000_000L))
        assertTrue("1980-01-01 正好是下限，必须放行", PhotoCaptureDate.isPlausibleEpochSeconds(315_532_800L))
        assertFalse("毫秒误传（4e11）必须挡住", PhotoCaptureDate.isPlausibleEpochSeconds(400_000_000_000L))
    }

    // ────────────────────── ⑤ 取值顺序（水印的唯一口径）──────────────────────

    @Test
    fun `拍摄时间按 EXIF 大于来源大于文件时间的优先级取值`() {
        assertEquals("EXIF 有值 ⇒ 用 EXIF", 111L, PhotoCaptureDate.resolve(111L, 222L, 333L, 444L))
        assertEquals("EXIF 无 ⇒ 用来源", 222L, PhotoCaptureDate.resolve(0L, 222L, 333L, 444L))
        assertEquals("来源也无 ⇒ 用修改时间", 333L, PhotoCaptureDate.resolve(0L, 0L, 333L, 444L))
        assertEquals("只剩入库时间 ⇒ 用它", 444L, PhotoCaptureDate.resolve(0L, 0L, 0L, 444L))
        assertEquals("全都没有 ⇒ 0（不显示水印）", 0L, PhotoCaptureDate.resolve(0L, 0L, 0L, 0L))
    }
}
