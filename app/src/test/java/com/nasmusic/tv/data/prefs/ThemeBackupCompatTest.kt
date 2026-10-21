package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.ThemeMode
import com.nasmusic.tv.data.model.resolveThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三态主题的**备份兼容**门禁（浅色主题方案 §4.2 / §7）。
 *
 * 三条真实风险：
 * 1. 老备份只有布尔 `darkTheme` → 必须按「true=深色 / false=浅色」迁移，
 *    不能因为新字段缺失就回落 SYSTEM（会丢掉用户显式选过的浅色）。
 * 2. 新备份写出 `themeMode` 枚举名，同时**继续维护**旧布尔，降级安装旧 APK 时不突变。
 * 3. `themeMode` 在 [AppSettings] 里**必须可空**：Gson 对新字段缺失只会留 null，
 *    这是区分「老备份」与「新备份显式选 SYSTEM」的唯一手段。
 *
 * 同时用源码接线门钉住 `AppPreferences` 的两处写法（改备份/迁移时最容易只改一半）。
 */
class ThemeBackupCompatTest {

    // ─────────────────── 老备份：只有 darkTheme ───────────────────

    @Test
    fun `老备份 darkTheme true 迁移为深色`() {
        val settings = backupGson.fromJson("""{"darkTheme":true}""", AppSettings::class.java)
        assertNull("老备份没有 themeMode 字段，必须留 null 供迁移判定", settings.themeMode)
        assertEquals(ThemeMode.DARK, resolveThemeMode(settings.themeMode, settings.darkTheme))
    }

    @Test
    fun `老备份 darkTheme false 迁移为浅色`() {
        val settings = backupGson.fromJson("""{"darkTheme":false}""", AppSettings::class.java)
        assertNull(settings.themeMode)
        assertEquals(
            "旧布尔 false 是用户显式选过的浅色，不能被回落成 SYSTEM",
            ThemeMode.LIGHT,
            resolveThemeMode(settings.themeMode, settings.darkTheme)
        )
    }

    @Test
    fun `两者都缺失时迁移为跟随系统`() {
        val settings = backupGson.fromJson("{}", AppSettings::class.java)
        assertNull(settings.themeMode)
        // darkTheme 的 Kotlin 默认值是 true，但真实老备份总会带这个键；
        // 这里只锁「新键缺失 + 旧键缺失」在调用方传 null 时的解析结果。
        assertEquals(ThemeMode.SYSTEM, resolveThemeMode(null, null))
    }

    // ─────────────────── 新备份：themeMode 优先 ───────────────────

    @Test
    fun `新备份 themeMode 优先于旧布尔`() {
        val json = """{"darkTheme":true,"themeMode":"LIGHT"}"""
        val settings = backupGson.fromJson(json, AppSettings::class.java)
        assertEquals(ThemeMode.LIGHT, settings.themeMode)
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(settings.themeMode, settings.darkTheme))
    }

    @Test
    fun `未知 themeMode 名字回落 SYSTEM 且绝不为 null`() {
        val settings = backupGson.fromJson(
            """{"themeMode":"SEPIA"}""",
            AppSettings::class.java
        )
        assertNotNull("容错适配器绝不允许把枚举字段写成 null", settings.themeMode)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
    }

    @Test
    fun `缺 themeMode 字段不得抛异常`() {
        val data = backupGson.fromJson(
            """{"version":3,"appSettings":{"darkTheme":false,"animationsEnabled":true}}""",
            AppPreferences.BackupData::class.java
        )
        val settings = data.appSettings
        assertNotNull("appSettings 必须被解析出来", settings!!)
        assertNull(settings.themeMode)
        assertEquals(ThemeMode.LIGHT, resolveThemeMode(settings.themeMode, settings.darkTheme))
    }

    /**
     * 设计偏离的护栏：`AppSettings.themeMode` 的默认值**必须**是 null。
     *
     * 若改成 `SYSTEM`，老备份（字段缺失 → 走 Kotlin 默认值）会与「显式选 SYSTEM」不可区分，
     * 用户选过的深/浅色在导入后静默丢失。生产链路由 `AppPreferences.appSettings` 填非空值。
     */
    @Test
    fun `AppSettings 默认 themeMode 必须是 null`() {
        assertNull(AppSettings().themeMode)
        assertTrue("旧布尔字段必须保留默认值，供降级兼容", AppSettings().darkTheme)
    }

    // ─────────────────── 导出方向 ───────────────────

    @Test
    fun `新备份同时写出 themeMode 与旧布尔`() {
        val json = backupGson.toJson(
            AppSettings(themeMode = ThemeMode.LIGHT, darkTheme = false)
        )
        assertTrue("必须写出三态字段，json=$json", json.contains("\"themeMode\":\"LIGHT\""))
        assertTrue("必须继续写出旧布尔，降级安装旧 APK 时不突变，json=$json", json.contains("\"darkTheme\":false"))
    }

    @Test
    fun `整份备份往返后主题模式不变`() {
        for (mode in ThemeMode.entries) {
            val data = AppPreferences.BackupData(
                appSettings = AppSettings(
                    themeMode = mode,
                    darkTheme = mode != ThemeMode.LIGHT,
                )
            )
            val restored = backupGson.fromJson(
                backupGson.toJson(data),
                AppPreferences.BackupData::class.java
            ).appSettings!!
            assertEquals(mode, restored.themeMode)
            // 导入时真正执行的那一步
            assertEquals(mode, resolveThemeMode(restored.themeMode, restored.darkTheme))
        }
    }

    // ─────────────────── 源码接线门 ───────────────────

    /**
     * `AppPreferences` 里两处写法缺一不可：
     * - 读侧：`darkTheme = themeMode != ThemeMode.LIGHT` —— 导出/降级兼容的同步点
     * - 导入侧：`resolveThemeMode(settings.themeMode, settings.darkTheme)` —— 老备份迁移点
     */
    @Test
    fun `AppPreferences 的同步与迁移写法必须同时存在`() {
        val source = prefsSourceFile("AppPreferences.kt").readText()
        assertTrue("读到的确实是 AppPreferences", source.contains("class AppPreferences"))
        assertTrue(
            "缺少导出侧旧布尔同步：`darkTheme = themeMode != ThemeMode.LIGHT`",
            source.contains("darkTheme = themeMode != ThemeMode.LIGHT")
        )
        assertTrue(
            "缺少导入侧迁移：`resolveThemeMode(settings.themeMode, settings.darkTheme)`",
            source.contains("resolveThemeMode(settings.themeMode, settings.darkTheme)")
        )
        assertTrue(
            "主题模式镜像必须按 resolveThemeMode 派生（否则老用户冷启动底色会闪）",
            source.contains("resolveThemeMode(") &&
                source.contains("KEY_THEME_MODE_MIRROR")
        )
    }

    // ─────────────────── 工具 ───────────────────

    private fun prefsSourceFile(name: String): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/data/prefs/$name"),
            File("app/src/main/java/com/nasmusic/tv/data/prefs/$name"),
            File("../app/src/main/java/com/nasmusic/tv/data/prefs/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $name（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null
        )
        return found!!
    }
}
