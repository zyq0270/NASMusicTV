package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistM3uPortableTest {
    @Test
    fun `serializer escapes metadata and redacts credentials`() {
        val song = Song(
            id = "1",
            title = "标题,含逗号\n第二行",
            artist = "歌手\r\n名",
            album = "专辑",
            durationMs = 123_000L,
            streamUrl = "https://user:pass@example.com/a.mp3?token=top-secret&x=1&X-Amz-Signature=sig-secret",
        )
        val result = M3uPlaylistSerializer().serialize("我的歌单", listOf(song))
        assertTrue(result.containsUrlPlaceholder)
        assertTrue(result.text.startsWith("#EXTM3U\n"))
        assertTrue(result.text.contains("#EXTINF:123,歌手  名 - 标题,含逗号 第二行"))
        assertFalse(result.text.contains("top-secret"))
        assertFalse(result.text.contains("sig-secret"))
        assertFalse(result.text.contains("user:pass"))
        assertTrue(result.text.contains("__REDACTED__"))
    }

    @Test
    fun `encoded sensitive parameter names and fragment credentials are redacted`() {
        val song = Song(
            id = "encoded",
            title = "脱敏",
            streamUrl = "https://example.com/a.mp3?%74oken=token-secret&X-Amz%2DSignature=aws-secret&ok=1#access_token=fragment-secret",
        )
        val result = M3uPlaylistSerializer().serialize("脱敏", listOf(song)).text
        assertFalse(result.contains("token-secret"))
        assertFalse(result.contains("aws-secret"))
        assertFalse(result.contains("fragment-secret"))
        assertTrue(result.contains("ok=1"))
        assertTrue(result.contains("#__REDACTED__"))
    }

    @Test
    fun `fragment placeholder keeps playable url in chinese round trip`() {
        val song = Song(
            id = "fragment",
            title = "中文歌名",
            artist = "中文歌手",
            streamUrl = "https://example.com/音乐/歌.mp3#seg",
        )
        val serialized = M3uPlaylistSerializer().serialize("中文歌单", listOf(song))
        assertTrue(serialized.text.contains("#__REDACTED__"))

        val parsed = M3uPlaylistParser().parse(serialized.text, "roundtrip.m3u").single()
        assertEquals("中文歌名", parsed.title)
        assertEquals("中文歌手", parsed.artist)
        // fragment 脱敏不影响媒体请求，URL 必须保留而不是被静默丢弃。
        assertEquals("https://example.com/音乐/歌.mp3#__REDACTED__", parsed.directUrl)
        assertEquals(DirectUrlType.HTTP, parsed.directUrlType)
    }

    @Test
    fun `credential placeholder still drops url after round trip`() {
        val song = Song(id = "credential", title = "凭据", streamUrl = "https://example.com/a.mp3?token=secret")
        val serialized = M3uPlaylistSerializer().serialize("凭据", listOf(song))

        val parsed = M3uPlaylistParser().parse(serialized.text, "credential.m3u").single()
        assertEquals("凭据", parsed.title)
        assertEquals(null, parsed.directUrl)
    }

    @Test
    fun `placeholder substring in path is not treated as redacted`() {
        val text = """
            #EXTM3U
            #PLAYLIST:子串
            #EXTINF:12,歌手 - 标题
            #NASMUSIC:PORTABILITY=VOLATILE_URL
            https://example.com/__REDACTED__.mp3
        """.trimIndent()
        val parsed = M3uPlaylistParser().parse(text, "substring.m3u").single()
        assertEquals("https://example.com/__REDACTED__.mp3", parsed.directUrl)
    }

    @Test
    fun `empty and unresolved playlists remain parseable`() {
        val serializer = M3uPlaylistSerializer()
        val empty = serializer.serialize("空", emptyList())
        assertEquals(0, empty.unresolvedCount)
        assertTrue(M3uPlaylistParser().parse(empty.text, "empty.m3u").isEmpty())

        val unresolved = serializer.serialize("待解析", listOf(Song(id = "n", title = "歌", isNetworkSong = true)))
        assertEquals(1, unresolved.unresolvedCount)
        assertTrue(unresolved.text.contains("#NASMUSIC:UNRESOLVED"))
        val parsed = M3uPlaylistParser().parse(unresolved.text, "pending.m3u")
        assertEquals("歌", parsed.single().title)
    }
}
