package com.nasmusic.tv.backend.playlist

import android.content.ContentResolver
import android.net.Uri
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistExporterTest {
    @Test
    fun `saf permission and missing document errors are structured`() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val uri = Uri.parse("content://documents/playlist.json")
        doThrow(SecurityException()).`when`(resolver).openOutputStream(uri, "w")
        val exporter = PlaylistExporter(resolver)
        assertEquals(
            BackendError.FORBIDDEN,
            (exporter.exportJson(uri, "x", emptyList()) as BackendResult.Failure).error,
        )

        doThrow(FileNotFoundException()).`when`(resolver).openOutputStream(uri, "w")
        assertEquals(
            BackendError.NOT_FOUND,
            (exporter.exportM3u(uri, "x", emptyList()) as BackendResult.Failure).error,
        )
    }

    @Test
    fun `saf writes utf8 json through output stream`() = runBlocking {
        val resolver = mock(ContentResolver::class.java)
        val uri = Uri.parse("content://documents/playlist.json")
        val output = ByteArrayOutputStream()
        `when`(resolver.openOutputStream(uri, "w")).thenReturn(output)
        val result = PlaylistExporter(resolver).exportJson(uri, "中文", emptyList())
        assertTrue(result is BackendResult.Success)
        assertTrue(output.toString("UTF-8").contains("中文"))
    }
}
