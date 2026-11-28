package com.nasmusic.tv.appwidget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetSecurityGateTest {
    @Test
    fun `manifest exposes only widget update and receiver is not exported`() {
        val manifest = File("app/src/main/AndroidManifest.xml").let {
            if (it.isFile) it else File("../app/src/main/AndroidManifest.xml")
        }
        assertTrue(manifest.isFile)
        val source = manifest.readText()
        val receiver = source.substringAfter("android:name=\".appwidget.RemotePlaybackWidgetProvider\"")
            .substringBefore("</receiver>")

        assertTrue(receiver.contains("android:exported=\"false\""))
        assertTrue(receiver.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertFalse(receiver.contains("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(Regex("android:name=\"(?!android\\.appwidget\\.provider\"|android\\.appwidget\\.action\\.APPWIDGET_UPDATE\")[^\"]+\"").containsMatchIn(receiver))
    }

    @Test
    fun `pending intent flags preserve API 22 compatibility`() {
        val source = File("app/src/main/java/com/nasmusic/tv/appwidget/RemotePlaybackWidget.kt").let {
            if (it.isFile) it else File("../app/src/main/java/com/nasmusic/tv/appwidget/RemotePlaybackWidget.kt")
        }.readText()

        assertTrue(source.contains("PendingIntent.FLAG_UPDATE_CURRENT"))
        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.M"))
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("Intent(context, RemotePlaybackWidgetProvider::class.java)"))
    }

    @Test
    fun `layout excludes editable and web views`() {
        val layout = File("app/src/main/res/layout/widget_remote_4x2.xml").let {
            if (it.isFile) it else File("../app/src/main/res/layout/widget_remote_4x2.xml")
        }.readText()

        assertFalse(layout.contains("EditText"))
        assertFalse(layout.contains("WebView"))
        assertTrue(layout.contains("widget_remote_4x2") || layout.contains("LinearLayout"))
    }
}
