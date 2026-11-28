package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.util.SongKeys
import java.text.Normalizer
import java.util.Locale

data class DuplicateSong(
    val song: Song,
    val stableKey: String,
    val source: SongSourceIdentity,
)

data class DuplicateMatch(
    val first: DuplicateSong,
    val second: DuplicateSong,
    val score: Int,
) {
    init {
        require(score >= MIN_SCORE) { "duplicate score must be at least $MIN_SCORE" }
    }

    companion object {
        const val MIN_SCORE = 7
        const val MAX_SCORE = 8
    }
}

/** 重复候选只做评分与稳定排序；是否隐藏必须由用户确认。 */
object DuplicateDetector {
    private val whitespace = Regex("\\s+")

    fun detect(
        songs: List<Song>,
        sourceFor: (Song) -> SongSourceIdentity? = { SongKeys.bindSource(it) },
    ): List<DuplicateMatch> = detect(songs.mapNotNull { song ->
        val source = sourceFor(song) ?: return@mapNotNull null
        val key = runCatching { SongKeys.stableKey(song, source) }.getOrNull() ?: return@mapNotNull null
        DuplicateSong(song, key, source)
    })

    fun detect(entries: List<DuplicateSong>): List<DuplicateMatch> {
        val byTitle = entries.groupBy { normalize(it.song.title) }
        val matches = mutableListOf<DuplicateMatch>()
        for (group in byTitle.values) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val a = group[i]
                    val b = group[j]
                    if (a.stableKey == b.stableKey) continue
                    val score = score(a, b)
                    if (score >= DuplicateMatch.MIN_SCORE) {
                        val (first, second) = if (a.stableKey <= b.stableKey) a to b else b to a
                        matches += DuplicateMatch(first, second, score)
                    }
                }
            }
        }
        return matches.sortedWith(
            compareByDescending<DuplicateMatch> { it.score }
                .thenBy { it.first.stableKey }
                .thenBy { it.second.stableKey }
        )
    }

    fun score(first: DuplicateSong, second: DuplicateSong): Int {
        var total = 0
        if (normalize(first.song.title) == normalize(second.song.title) &&
            normalize(first.song.artist) == normalize(second.song.artist)
        ) {
            total += 4
        }
        val firstDuration = first.song.durationMs
        val secondDuration = second.song.durationMs
        if (firstDuration > 0L && secondDuration > 0L &&
            kotlin.math.abs(firstDuration - secondDuration) <= 2_000L
        ) {
            total += 2
        }
        val firstAlbum = normalize(first.song.album)
        if (firstAlbum.isNotEmpty() && firstAlbum == normalize(second.song.album)) total += 1
        if (sourcePlatform(first.source, first.song) == sourcePlatform(second.source, second.song)) total += 1
        return total
    }

    private fun sourcePlatform(source: SongSourceIdentity, song: Song): String = when (source.kind) {
        com.nasmusic.tv.data.model.SongSourceKind.LOCAL ->
            source.storageIdentity ?: song.storageType ?: "local"
        com.nasmusic.tv.data.model.SongSourceKind.NAS -> source.backendType ?: "nas"
        com.nasmusic.tv.data.model.SongSourceKind.NETWORK ->
            source.platform ?: song.networkPlatform ?: song.networkSource ?: "network"
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}
