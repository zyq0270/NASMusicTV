package com.nasmusic.tv.backend.search

import com.nasmusic.tv.backend.FilterMode
import com.nasmusic.tv.backend.SearchType
import com.nasmusic.tv.backend.SourceVisibility
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.SearchAggregatorResult
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedSearchEquivalenceTest {

    @Test
    fun `all entry points use normalization precise visibility dedup and history`() = runBlocking {
        var request: SharedSearchService.SharedSearchRequest? = null
        val history = mutableListOf<String>()
        val service = service(
            visibility = visibility(network = false),
            engine = { req ->
                request = req
                SearchAggregatorResult(
                    allResults = listOf(
                        ranked(MusicSourceType.NETWORK_MUSIC, "network", "稻香", "周杰伦"),
                        ranked(MusicSourceType.NAS, "nas-2", "稻香", "周杰伦"),
                        ranked(MusicSourceType.NAS, "nas-1", "稻香", "周杰伦"),
                    ),
                    sourceBreakdown = emptyMap(),
                )
            },
            recordHistory = { history += it },
        )

        val result = service.search("播放周杰伦的稻香")

        assertEquals("周杰伦 稻香", result.normalizedQuery)
        assertEquals(listOf("周杰伦 稻香"), history)
        assertEquals(FilterMode.PRECISE, request?.filterMode)
        assertEquals(SearchType.SONG_NAME_OR_ARTIST, request?.searchType)
        assertTrue(request?.sources?.contains(MusicSourceType.NETWORK_MUSIC) == false)
        assertEquals(listOf("nas-2"), result.results.map { it.song.id })
        assertEquals(mapOf(MusicSourceType.NAS to 1), result.sourceBreakdown)
    }

    @Test
    fun `pinyin query receives caches and normal forms converge`() = runBlocking {
        val requests = mutableListOf<SharedSearchService.SharedSearchRequest>()
        var baiduQuery: String? = null
        val service = service(
            engine = { req ->
                requests += req
                SearchAggregatorResult(
                    allResults = listOf(ranked(MusicSourceType.NAS, "nas", "晴天", "周杰伦")),
                    sourceBreakdown = mapOf(MusicSourceType.NAS to 1),
                )
            },
            nasCache = { listOf(song("nas-cache")) },
            localCache = { listOf(song("local-cache")) },
            baiduCache = { query ->
                baiduQuery = query
                listOf(song("baidu-cache"))
            },
        )

        val voice = service.search("我想听晴天")
        val typed = service.search("晴天")
        val pinyin = service.search("zjl qt")

        assertEquals(voice.results, typed.results)
        assertEquals(voice.results, pinyin.results)
        assertEquals("zjl qt", pinyin.normalizedQuery)
        assertEquals("zjl qt", baiduQuery)
        val pinyinRequest = requests.single { it.query == "zjl qt" }
        assertTrue(pinyinRequest.nasLocalSongs.single().id == "nas-cache")
        assertTrue(pinyinRequest.localDeviceSongs.single().id == "local-cache")
        assertTrue(pinyinRequest.baiduLocalSongs.single().id == "baidu-cache")
    }

    @Test
    fun `blank or command-only input never calls engine or history`() = runBlocking {
        var engineCalls = 0
        var historyCalls = 0
        val service = service(
            engine = {
                engineCalls++
                SearchAggregatorResult(emptyList(), emptyMap())
            },
            recordHistory = { historyCalls++ },
        )

        listOf("", "   ", "！？", "播放").forEach { query ->
            val result = service.search(query)
            assertTrue(result.results.isEmpty())
        }
        assertEquals(0, engineCalls)
        assertEquals(0, historyCalls)
    }

    @Test
    fun `disabled search sources are removed before engine invocation`() = runBlocking {
        var request: SharedSearchService.SharedSearchRequest? = null
        val service = service(
            visibility = visibility(nas = false, network = false, baidu = false, jamendo = false),
            engine = { req ->
                request = req
                SearchAggregatorResult(emptyList(), emptyMap())
            },
        )

        service.search("稻香", availableSources = MusicSourceType.DEFAULT_SEARCH_SOURCES)

        assertEquals(setOf(MusicSourceType.LOCAL), request?.sources)
    }

    private fun service(
        visibility: SourceVisibility = visibility(),
        engine: SharedSearchService.SharedSearchEngine,
        nasCache: () -> List<Song> = { emptyList() },
        localCache: () -> List<Song> = { emptyList() },
        baiduCache: suspend (String) -> List<Song> = { emptyList() },
        recordHistory: suspend (String) -> Unit = {},
    ): SharedSearchService = SharedSearchService(
        engine = engine,
        sourceVisibility = visibility,
        nasLocalSongsProvider = nasCache,
        localDeviceSongsProvider = localCache,
        baiduLocalSongsProvider = baiduCache,
        recordSearchHistory = recordHistory,
    )

    private fun visibility(
        nas: Boolean = true,
        network: Boolean = true,
        baidu: Boolean = true,
        jamendo: Boolean = true,
        local: Boolean = true,
    ) = SourceVisibility(
        nasEnabled = { nas },
        localEnabled = { local },
        metingEnabled = { network },
        baiduActive = { baidu },
        jamendoEnabled = { jamendo },
        jamendoClientId = { "client" },
    )

    private fun ranked(source: MusicSourceType, id: String, title: String, artist: String) =
        RankedSong(
            song = song(id).copy(title = title, artist = artist),
            source = source,
        )

    private fun song(id: String) = Song(id = id, title = id, artist = "artist")
}
