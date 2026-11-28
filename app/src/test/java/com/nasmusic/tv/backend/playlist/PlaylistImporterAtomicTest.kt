package com.nasmusic.tv.backend.playlist

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.prefs.AppPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImporterAtomicTest {
    private lateinit var prefs: AppPreferences
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext
        prefs = AppPreferences(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `validated entries are created in one batch call`() = runBlocking {
        val calls = AtomicInteger()
        var created: LocalPlaylist? = null
        val store = object : LocalPlaylistStore {
            override suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist {
                calls.incrementAndGet()
                created = LocalPlaylist("id", name, songs)
                return created!!
            }
        }
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as Application
        val importer = PlaylistImporter(app, prefs, scope, store)
        val result = importer.importBytes(
            "A - a\nA - a\nB - b".toByteArray(),
            "batch.txt",
        )
        assertEquals(1, calls.get())
        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(2, created!!.songs.size)
    }

    @Test
    fun `duplicate Song ids are removed before the batch write`() = runBlocking {
        var batch: List<Song>? = null
        val store = object : LocalPlaylistStore {
            override suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist {
                batch = songs
                return LocalPlaylist("id", name, songs)
            }
        }
        val json = """{"formatVersion":"nasmusic-playlist-v3","playlistName":"去重","songs":[
            {"id":"dup","title":"A","binding":"BOUND","stableKey":"v1:nas:s:jellyfin:dup",
             "source":{"kind":"NAS","nativeId":"dup","serverId":"s","backendType":"jellyfin"}},
            {"id":"dup","title":"B","binding":"BOUND","stableKey":"v1:nas:s:jellyfin:dup",
             "source":{"kind":"NAS","nativeId":"dup","serverId":"s","backendType":"jellyfin"}}
        ]}"""
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as Application
        val result = PlaylistImporter(app, prefs, scope, store).importBytes(
            json.toByteArray(),
            "duplicates.json",
        )
        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(listOf("A"), batch!!.map { it.title })
    }

    @Test
    fun `production store persists the complete song list atomically`() = runBlocking {
        val songs = listOf(Song(id = "a", title = "A"), Song(id = "b", title = "B"))
        val created = AppPreferencesLocalPlaylistStore(prefs).createWithSongs("atomic", songs)
        assertEquals(songs, created.songs)
        assertEquals(created, prefs.getLocalPlaylists().single())
    }

    @Test
    fun `concurrent same name imports resolve names inside the data store edit`() = runBlocking {
        val store = AppPreferencesLocalPlaylistStore(prefs)
        val created = coroutineScope {
            listOf(
                async { store.createWithSongs("same", listOf(Song(id = "a", title = "A"))) },
                async { store.createWithSongs("same", listOf(Song(id = "b", title = "B"))) },
            ).awaitAll()
        }
        assertEquals(2, prefs.getLocalPlaylists().size)
        assertNotEquals(created[0].name, created[1].name)
        assertTrue(created.all { it.songs.size == 1 })
    }

    @Test
    fun `same local name is renamed instead of overwritten`() = runBlocking {
        prefs.createLocalPlaylist("同名")
        val store = object : LocalPlaylistStore {
            override suspend fun createWithSongs(name: String, songs: List<Song>) =
                LocalPlaylist("new", name, songs)
        }
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as Application
        val result = PlaylistImporter(app, prefs, scope, store).importBytes(
            "A - a".toByteArray(),
            "x.txt",
            userConfirmedName = "同名",
        )
        assertTrue(result.playlistName.startsWith("同名 (导入 "))
        assertEquals("同名", prefs.getLocalPlaylists().single().name)
    }
}
