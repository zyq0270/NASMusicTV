package com.nasmusic.tv.lyrics

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.nasmusic.tv.data.model.Song
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class LocalLyricsProviderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `URI 分类区分文件 MediaStore SAF 树与单文档`() {
        val file = LocalLyricsProvider.classifyAudioUri("file:///storage/music/song.mp3")
        assertTrue(file is LocalAudioLocation.FileLocation)
        assertEquals(File("/storage/music/song.mp3"), (file as LocalAudioLocation.FileLocation).file)

        val mediaStore = LocalLyricsProvider.classifyAudioUri(
            "content://media/external/audio/media/42",
        ) as LocalAudioLocation.ContentLocation
        assertEquals(LocalContentKind.MEDIA_STORE, mediaStore.kind)
        assertEquals(Uri.parse("content://media/external/audio/media/42"), mediaStore.uri)

        val tree = LocalLyricsProvider.classifyAudioUri(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.mp3",
        ) as LocalAudioLocation.ContentLocation
        assertEquals(LocalContentKind.SAF_TREE, tree.kind)

        val document = LocalLyricsProvider.classifyAudioUri(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3",
        ) as LocalAudioLocation.ContentLocation
        assertEquals(LocalContentKind.SINGLE_DOCUMENT, document.kind)

        assertEquals(
            LocalAudioLocation.Unsupported,
            LocalLyricsProvider.classifyAudioUri("https://example.com/song.mp3"),
        )
    }

    @Test
    fun `文件侧车优先使用 streamUrl 为空时才回退 path`() {
        val pathDir = temp.newFolder("path")
        val streamDir = temp.newFolder("stream")
        val pathAudio = File(pathDir, "song.mp3").apply { writeText("audio") }
        val streamAudio = File(streamDir, "song.flac").apply { writeText("audio") }
        val expected = "[00:01.00]来自 streamUrl\n"
        File(streamDir, "song.lrc").writeText(expected, Charsets.UTF_8)
        val song = Song(
            id = "local_1",
            title = "song",
            path = pathAudio.toURI().toString(),
            streamUrl = streamAudio.toURI().toString(),
            isLocalSong = true,
        )

        assertEquals(expected, LocalLyricsProvider.getSidecarLyrics(song))
        assertNull(
            "streamUrl 为空时回退 path；path 目录没有 LRC 就返回 null",
            LocalLyricsProvider.getSidecarLyrics(song.copy(streamUrl = null)),
        )
    }

    @Test
    fun `侧车歌词 UTF8 优先并在乱码时回退 GBK`() {
        val utf8Dir = temp.newFolder("utf8")
        val gbkDir = temp.newFolder("gbk")
        File(utf8Dir, "song.mp3").writeText("audio")
        File(gbkDir, "song.mp3").writeText("audio")
        val utf8Text = "[00:01.00]中文 UTF-8\n"
        val gbkText = "[00:02.00]中文 GBK\n"
        File(utf8Dir, "song.lrc").writeText(utf8Text, Charsets.UTF_8)
        File(gbkDir, "song.lrc").writeBytes(gbkText.toByteArray(Charset.forName("GBK")))

        fun song(dir: File) = Song(
            id = "local_${dir.name}",
            title = "song",
            path = File(dir, "song.mp3").toURI().toString(),
            isLocalSong = true,
        )

        assertEquals(utf8Text, LocalLyricsProvider.getSidecarLyrics(song(utf8Dir)))
        assertEquals(gbkText, LocalLyricsProvider.getSidecarLyrics(song(gbkDir)))
    }

    @Test
    fun `文件侧车超过两MiB直接拒绝`() {
        val dir = temp.newFolder("oversized")
        val lrc = File(dir, "song.lrc")
        java.io.RandomAccessFile(lrc, "rw").use { output ->
            output.setLength(2L * 1024 * 1024 + 1)
            output.seek(0)
            output.write("[00:01.00]超限歌词\n".toByteArray(Charsets.UTF_8))
        }

        assertNull("超过两 MiB 的 File 侧车不得读入内存", LocalLyricsProvider.readLrc(lrc))
    }

    @Test
    @Config(sdk = [34])
    fun `MediaStore 按同目录精确查找 base lrc 并经 ContentResolver 读取 GBK`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val filesUri = MediaStore.Files.getContentUri("external")
        val lrcUri = Uri.withAppendedPath(filesUri, "99")
        val metadata = android.database.MatrixCursor(
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
        ).apply { addRow(arrayOf("song.flac", "Music/Album/")) }
        val lrcRow = android.database.MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
            .apply { addRow(arrayOf(99)) }
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenReturn(metadata)
        `when`(resolver.query(eq(filesUri), any<Array<String>>(), anyString(), any<Array<String>>(), isNull()))
            .thenReturn(lrcRow)
        val expected = "[00:03.00]MediaStore 同目录歌词\n"
        `when`(resolver.openInputStream(lrcUri))
            .thenReturn(expected.toByteArray(Charset.forName("GBK")).inputStream())
        val song = Song(
            id = "local_42",
            title = "song",
            streamUrl = audioUri.toString(),
            path = audioUri.toString(),
            isLocalSong = true,
        )

        assertNull("content URI 不能伪装成 File 路径", LocalLyricsProvider.findLrcFile(song))
        assertEquals(expected, LocalLyricsProvider.getSidecarLyrics(song, resolver))
        verify(resolver).query(
            eq(filesUri),
            any<Array<String>>(),
            eq("${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"),
            aryEq(arrayOf("Music/Album/", "song.lrc")),
            isNull(),
        )
    }

    @Test
    @Config(sdk = [22])
    fun `API22 MediaStore 使用 DATA 定位同目录 base lrc`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val filesUri = Uri.parse("content://media/external/file")
        val audioPath = "/storage/emulated/0/Music/Album/song.flac"
        val lrcPath = File(File(audioPath).parent, "song.lrc").path
        val lrcUri = Uri.withAppendedPath(filesUri, "99")
        val metadata = android.database.MatrixCursor(
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
        ).apply { addRow(arrayOf("song.flac", audioPath)) }
        val lrcRow = android.database.MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
            .apply { addRow(arrayOf(99)) }
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenReturn(metadata)
        `when`(resolver.query(eq(filesUri), any<Array<String>>(), anyString(), any<Array<String>>(), isNull()))
            .thenReturn(lrcRow)
        val expected = "[00:04.00]API22 DATA 歌词\n"
        `when`(resolver.openInputStream(lrcUri))
            .thenReturn(expected.toByteArray(Charsets.UTF_8).inputStream())
        val song = Song(
            id = "local_42",
            title = "song",
            streamUrl = audioUri.toString(),
            isLocalSong = true,
        )

        assertEquals(expected, LocalLyricsProvider.getSidecarLyrics(song, resolver))
        verify(resolver).query(
            eq(filesUri),
            any<Array<String>>(),
            eq("${MediaStore.MediaColumns.DATA}=?"),
            aryEq(arrayOf(lrcPath)),
            isNull(),
        )
    }

    @Test
    fun `MediaStore 授权被拒绝时侧车查询降级为无结果`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenThrow(SecurityException("revoked"))
        val song = Song(
            id = "local_42",
            title = "song",
            streamUrl = audioUri.toString(),
            isLocalSong = true,
        )

        assertNull(LocalLyricsProvider.getSidecarLyrics(song, resolver))
    }

    @Test
    fun `SAF 树授权仅枚举父目录并精确读取 base lrc`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Music/Album/song.flac")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "primary:Music/Album")
        val lrcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Music/Album/song.lrc")
        val metadata = android.database.MatrixCursor(
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        ).apply { addRow(arrayOf("song.flac")) }
        val children = android.database.MatrixCursor(
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
        ).apply {
            addRow(arrayOf("other.lrc", "primary:Music/Album/other.lrc"))
            addRow(arrayOf("song.lrc", "primary:Music/Album/song.lrc"))
        }
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenReturn(metadata)
        `when`(resolver.query(eq(childrenUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenReturn(children)
        val expected = "[00:07.00]SAF 同目录歌词\n"
        `when`(resolver.openInputStream(lrcUri))
            .thenReturn(expected.toByteArray(Charsets.UTF_8).inputStream())
        val song = Song(
            id = "local_saf",
            title = "song",
            streamUrl = audioUri.toString(),
            isLocalSong = true,
        )

        assertEquals(expected, LocalLyricsProvider.getSidecarLyrics(song, resolver))
        verify(resolver).query(eq(childrenUri), any<Array<String>>(), isNull(), isNull(), isNull())
        verify(resolver).openInputStream(lrcUri)
    }

    @Test
    fun `SAF 授权失效与单文档 URI 都不伪造兄弟歌词`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Music/song.flac")
        val revoked = mock(ContentResolver::class.java)
        `when`(revoked.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenThrow(SecurityException("revoked"))
        assertNull(
            LocalLyricsProvider.getSidecarLyrics(
                Song(id = "saf", title = "song", streamUrl = audioUri.toString(), isLocalSong = true),
                revoked,
            ),
        )

        val single = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.flac")
        val singleResolver = mock(ContentResolver::class.java)
        assertNull(
            LocalLyricsProvider.getSidecarLyrics(
                Song(id = "single", title = "song", streamUrl = single.toString(), isLocalSong = true),
                singleResolver,
            ),
        )
        verify(singleResolver, times(0)).query(any(), any<Array<String>>(), isNull(), isNull(), isNull())
        verify(singleResolver, times(0)).openInputStream(single)
    }

    @Test
    fun `侧车权限失败后仍可回退读取 content 内嵌歌词`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val expected = "[00:08.00]侧车失败后的内嵌歌词\n"
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.query(eq(audioUri), any<Array<String>>(), isNull(), isNull(), isNull()))
            .thenThrow(SecurityException("query denied"))
        `when`(resolver.openInputStream(audioUri))
            .thenReturn(ByteArrayInputStream(id3WithUslt(expected)))
        val song = Song(
            id = "local_42",
            title = "song",
            streamUrl = audioUri.toString(),
            isLocalSong = true,
        )

        assertEquals(expected, LocalLyricsProvider.getLocalLyrics(song, resolver))
    }

    @Test
    fun `content 音频按 tagTotalSize 两段有界读取内嵌 USLT`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val expected = "[00:05.00]" + "长歌词".repeat(50_000)
        val audioBytes = id3WithUslt(expected)
        val opens = AtomicInteger()
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.openInputStream(audioUri)).thenAnswer {
            opens.incrementAndGet()
            ByteArrayInputStream(audioBytes)
        }
        val song = Song(
            id = "local_42",
            title = "song",
            streamUrl = audioUri.toString(),
            isLocalSong = true,
        )

        assertEquals(expected, LocalLyricsProvider.getEmbeddedLyrics(song, resolver))
        assertEquals("超过探测窗口的 ID3 标签应补读一次", 2, opens.get())
        verify(resolver, times(2)).openInputStream(audioUri)
    }

    @Test
    fun `按路径读取 content 音频时也走 ContentResolver 内嵌歌词`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val expected = "[00:06.00]content 路径内嵌歌词"
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.openInputStream(audioUri))
            .thenReturn(ByteArrayInputStream(id3WithUslt(expected)))

        assertEquals(
            expected,
            LocalLyricsProvider.getLyricsFromPath(audioUri.toString(), null, resolver),
        )
    }

    @Test
    fun `显式 content 歌词读取被拒绝时继续降级`() {
        val audioUri = Uri.parse("content://media/external/audio/media/42")
        val lyricUri = Uri.parse("content://media/external/file/99")
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.openInputStream(audioUri))
            .thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        `when`(resolver.openInputStream(lyricUri))
            .thenThrow(SecurityException("lyric revoked"))

        assertNull(
            LocalLyricsProvider.getLyricsFromPath(audioUri.toString(), lyricUri.toString(), resolver),
        )
    }

    private fun id3WithUslt(lyrics: String): ByteArray {
        val text = lyrics.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(3) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text
        val frame = "USLT".toByteArray(Charsets.ISO_8859_1) + synchsafe(payload.size) + byteArrayOf(0, 0) + payload
        val header = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(4, 0, 0) + synchsafe(frame.size)
        return header + frame + byteArrayOf(1, 2, 3)
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
