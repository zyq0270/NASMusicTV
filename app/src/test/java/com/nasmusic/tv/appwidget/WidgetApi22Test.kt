package com.nasmusic.tv.appwidget

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class WidgetApi22Test {
    @Test
    fun `remote views inflate and update on API 22`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val views = RemoteViews(context.packageName, R.layout.widget_remote_4x2).apply {
            setTextViewText(R.id.widget_title, "歌曲")
            setTextViewText(R.id.widget_subtitle, "歌手 · 专辑")
            setBoolean(R.id.widget_previous, "setEnabled", false)
            setBoolean(R.id.widget_play, "setEnabled", false)
            setBoolean(R.id.widget_next, "setEnabled", false)
            setBoolean(R.id.widget_favorite, "setEnabled", false)
        }

        val root = views.apply(context, FrameLayout(context))

        assertEquals("歌曲", root.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("歌手 · 专辑", root.findViewById<TextView>(R.id.widget_subtitle).text.toString())
        assertFalse(root.findViewById<ImageButton>(R.id.widget_previous).isEnabled)
        assertFalse(root.findViewById<ImageButton>(R.id.widget_play).isEnabled)
        assertFalse(root.findViewById<ImageButton>(R.id.widget_next).isEnabled)
        assertFalse(root.findViewById<ImageButton>(R.id.widget_favorite).isEnabled)
        assertNotNull(root.findViewById<TextView>(R.id.widget_mode))
        assertNotNull(root.findViewById<ImageButton>(R.id.widget_sleep))
    }
}
