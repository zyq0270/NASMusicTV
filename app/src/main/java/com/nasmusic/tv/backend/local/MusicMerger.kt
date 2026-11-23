package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.PinyinUtils

/**
 * 本地音乐与 NAS 数据合并器
 *
 * 同名艺术家 / 专辑合并管理：若 NAS 后端和本地都有同名的艺术家或专辑，
 * 将其合并为一个条目。
 *
 * 说明：Album / Artist 是不含歌曲列表的纯描述类（歌曲在详情页按 id 分别查询），
 * 因此此处合并是"条目去重 + 计数累加"；本地条目用 local_ 前缀 ID 参与合并，
 * 歌曲明细由详情页分别查询 NAS 与本地后合并展示。
 *
 * 去重键：`ArtistSplitter.normalizeKey(name)`（NFKC 全角转半角 + 忽略首尾空白 +
 * 折叠内部空白 + 大小写不敏感）。
 */
object MusicMerger {

    /**
     * 合并多源专辑（NAS + 本地 + 百度）。
     *
     * 去重键 = [ArtistSplitter.albumKey]（专辑名 + 艺术家）：同名不同歌手的专辑必须是两张卡。
     * ⚠️ 百度侧经常没有 artist，若直接按「名+艺术家」分组会把 NAS + 百度同名专辑拆开（B20 回归）；
     * 因此先按专辑名归堆，再把 artist 为空的条目并入同堆中曲目数最多的具名分组。
     */
    fun mergeAlbums(
        nasAlbums: List<Album>,
        localAlbums: List<Album>,
        baiduAlbums: List<Album> = emptyList()
    ): List<Album> {
        val byName = linkedMapOf<String, MutableList<Album>>()
        fun collect(list: List<Album>) {
            list.forEach { album ->
                val key = ArtistSplitter.normalizeKey(album.name)
                if (key.isBlank()) return@forEach
                byName.getOrPut(key) { mutableListOf() }.add(album)
            }
        }
        collect(nasAlbums)
        collect(localAlbums)
        collect(baiduAlbums)

        val merged = mutableListOf<Album>()
        byName.values.forEach { sameName ->
            val byArtist = linkedMapOf<String, MutableList<Album>>()
            val unknownArtist = mutableListOf<Album>()
            sameName.forEach { album ->
                if (album.artist.isBlank()) {
                    unknownArtist.add(album)
                } else {
                    val key = ArtistSplitter.normalizeKey(album.artist)
                    byArtist.getOrPut(key) { mutableListOf() }.add(album)
                }
            }
            if (unknownArtist.isNotEmpty()) {
                val target = byArtist.values.maxByOrNull { group -> group.sumOf { it.songCount } }
                if (target != null) target.addAll(unknownArtist) else byArtist[""] = unknownArtist
            }
            byArtist.values.forEach { group -> merged.add(mergeAlbumGroup(group)) }
        }
        return sortByPinyin(merged) { it.name }
    }

    /**
     * 把一个「同名同艺术家」的多源条目合成一张卡：计数累加、封面/艺术家取首个非空值，
     * 并回填全部来源 id（详情页 loadAlbumSongs 据此跨源取歌，修复 B20 丢歌）。
     */
    private fun mergeAlbumGroup(group: List<Album>): Album {
        val first = group.first()
        var songCount = 0
        var durationMs = 0L
        var coverUrl: String? = null
        var artist = ""
        var year: Int? = null
        val sourceIds = linkedSetOf<String>()
        group.forEach { album ->
            songCount += album.songCount
            durationMs += album.durationMs
            if (coverUrl == null) coverUrl = album.coverUrl
            if (artist.isBlank() && album.artist.isNotBlank()) artist = album.artist
            if (year == null) year = album.year
            sourceIds.add(album.id)
        }
        return first.copy(
            artist = artist.ifBlank { first.artist },
            coverUrl = coverUrl,
            year = year,
            songCount = songCount,
            durationMs = durationMs,
            sourceIds = sourceIds.toList()
        )
    }

    /** 本地/百度派生的专辑 id 也要带艺术家，避免同名不同歌手塌到同一个缓存键。 */
    private fun generatedAlbumId(prefix: String, name: String, artist: String): String =
        prefix + ArtistSplitter.albumKey(name, artist).replace(Regex("[\\s:]+"), "_")

    /**
     * 合并多源艺术家（NAS + 本地 + 百度），按 artistName 去重
     */
    fun mergeArtists(
        nasArtists: List<Artist>,
        localArtists: List<Artist>,
        baiduArtists: List<Artist> = emptyList()
    ): List<Artist> {
        val artistMap = linkedMapOf<String, Artist>()

        nasArtists.forEach { artist ->
            val key = ArtistSplitter.normalizeKey(artist.name)
            if (key.isNotBlank()) {
                // 存储的 name 做 trim，避免带首尾空格的原始 NAS 数据进入 UI 显示
                artistMap[key] = artist.copy(name = artist.name.trim())
            }
        }

        localArtists.forEach { artist ->
            val key = ArtistSplitter.normalizeKey(artist.name)
            if (key.isBlank()) return@forEach
            if (key in artistMap) {
                val existing = artistMap[key]!!
                artistMap[key] = existing.copy(
                    songCount = existing.songCount + artist.songCount,
                    albumCount = existing.albumCount + artist.albumCount,
                    coverUrl = existing.coverUrl ?: artist.coverUrl
                )
            } else {
                artistMap[key] = artist
            }
        }

        baiduArtists.forEach { artist ->
            val key = ArtistSplitter.normalizeKey(artist.name)
            if (key.isBlank()) return@forEach
            if (key in artistMap) {
                val existing = artistMap[key]!!
                artistMap[key] = existing.copy(
                    songCount = existing.songCount + artist.songCount,
                    albumCount = existing.albumCount + artist.albumCount,
                    coverUrl = existing.coverUrl ?: artist.coverUrl
                )
            } else {
                artistMap[key] = artist
            }
        }

        return sortByPinyin(artistMap.values.toList()) { it.name }
    }

    /**
     * 从本地歌曲生成本地专辑列表（按「专辑名 + 艺术家」去重分组）。
     *
     * 封面优先级：专辑内第一首有 coverUrl 的歌曲 → null（留给上层异步解析）
     */
    fun buildLocalAlbums(localSongs: List<Song>): List<Album> =
        localSongs
            .filter { it.album.isNotBlank() }
            // 先按专辑名归堆（G2-05）：⛔ 不能直接用曲目级 artist 分组 ——
            // 合辑/群星/`A feat. B` 的每首 track artist 都不同，会把同一张专辑
            // 拆成 N 张同名卡、songCount 被拆散，而同一专辑来自 NAS 时仍是一张。
            .groupBy { ArtistSplitter.normalizeKey(it.album) }
            .values
            .flatMap { sameName ->
                // 堆内出现次数最多的 artist 视作 albumartist（Song 没有 albumArtist 字段）。
                // 判「合辑」需同时满足：主 artist 至少 2 首、严格多于次多者、且过半。
                // ⛔ 只看「过半」不够：两张同名专辑各 1 首（不同歌手）会被错并成一张
                //（MusicMergerTest 已钉住该场景）。
                val counts = sameName.groupingBy { it.artist }.eachCount()
                val sorted = counts.entries.sortedByDescending { it.value }
                val topCount = sorted.firstOrNull()?.value ?: 0
                val secondCount = sorted.getOrNull(1)?.value ?: 0
                val albumArtist = sorted.firstOrNull()?.key ?: sameName.first().artist
                val isCompilation = sameName.size >= 3 &&
                    topCount >= 2 &&
                    topCount > secondCount &&
                    topCount * 2 >= sameName.size
                val groups = if (isCompilation) {
                    listOf(albumArtist to sameName)
                } else {
                    sameName.groupBy { it.artist }.map { (artist, songs) -> artist to songs }
                }
                groups.map { (artist, songs) ->
                    val first = songs.first()
                    Album(
                        id = generatedAlbumId("local_album_", first.album, artist),
                        name = first.album,
                        artist = artist.ifBlank { first.artist },
                        coverUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl,
                        songCount = songs.size,
                        durationMs = songs.sumOf { it.durationMs }
                    )
                }
            }

    /**
     * 从本地歌曲生成本地艺术家列表（按 artistName 去重分组）
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出（songCount 累加）。
     */
    fun buildLocalArtists(localSongs: List<Song>): List<Artist> =
        // 本地艺术家无独立封面，用其名下第一首有封面的歌曲封面兜底
        // （本地歌封面来自 LocalCoverExtractor 提取的侧车 cover.jpg / 内嵌 ID3 APIC）
        buildArtistsFromSongs(localSongs, "local_artist_", useSongCover = true)

    /**
     * 从百度网盘歌曲列表构建专辑列表
     *
     * 百度网盘 API 不返回 album 字段，只能从 path 目录结构推断：
     * - path = `/音乐/周杰伦/范特西/01.爱在西元前.mp3` → 专辑 = "范特西"
     * - 取 path 倒数第二段目录名作为专辑名
     * - 过滤泛化目录名（"音乐"、"Music"、"收藏"等不太可能是专辑名的词）
     * - 过滤与 artist 同名的目录（如 `/周杰伦/周杰伦/...` 第二层"周杰伦"是 artist 不是专辑）
     * - 通过的目录才认为是专辑；否则该歌曲不归属任何专辑
     */
    fun buildBaiduAlbums(baiduSongs: List<Song>): List<Album> {
        // 泛化目录名黑名单（小写匹配）——这些词不太可能是专辑名
        val genericDirs = setOf(
            "音乐", "music", "歌曲", "songs", "audio", "音频",
            "收藏", "收藏夹", "favorites", "download", "下载",
            "我的音乐", "my music", "全部歌曲", "全部", "all",
            "未分类", "misc", "other", "其他", "tmp", "temp"
        )

        return baiduSongs
            .mapNotNull { song ->
                val segments = song.path?.trim('/')?.split("/") ?: return@mapNotNull null
                // 至少要有 2 层：目录/文件名，否则无法推断专辑
                if (segments.size < 2) return@mapNotNull null
                val dirName = segments.getOrNull(segments.size - 2) ?: return@mapNotNull null
                if (dirName.isBlank()) return@mapNotNull null
                // 过滤泛化目录名
                if (dirName.lowercase().trim() in genericDirs) return@mapNotNull null
                // 过滤与歌手同名的目录（那是艺术家目录，不是专辑）
                if (song.artist.isNotBlank() && dirName.lowercase().trim() == song.artist.lowercase().trim()) return@mapNotNull null
                dirName to song
            }
            // 与 mergeAlbums 同口径：同名不同歌手分开；百度 artist 常为空时按名归并，
            // 由 mergeAlbums 再并入 NAS 的具名分组（保留 B20 跨源合并行为）。
            .groupBy { (albumName, song) -> ArtistSplitter.albumKey(albumName, song.artist) }
            .map { (_, entries) ->
                val albumName = entries.first().first
                val songs = entries.map { it.second }
                val first = songs.first()
                Album(
                    id = generatedAlbumId("baidu_album_", albumName, first.artist),
                    name = albumName,
                    artist = first.artist,
                    coverUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl,
                    songCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs }
                )
            }
    }

    /**
     * 从百度网盘歌曲列表构建艺术家列表
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出。
     */
    fun buildBaiduArtists(baiduSongs: List<Song>): List<Artist> =
        // 百度艺术家走在线补全（网易云→酷狗→iTunes→歌曲封面兜底，见 ArtistCoverResolver）。
        // 不直接用歌曲封面兜底，保证能优先拿到歌手本人照片。
        buildArtistsFromSongs(baiduSongs, "baidu_artist_", useSongCover = false)

    /**
     * 从任意歌曲列表构建艺术家列表（按拆分后的艺术家名去重分组）。
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出（songCount 累加），
     * 因此搜索结果、本地歌曲、百度歌曲都不会再出现 "张三/李四" 这样的合唱块。
     *
     * 去重键为 [ArtistSplitter.normalizeKey]（NFKC + trim + 折叠空白 + 小写），
     * 保证 "古天乐" 与 "古天乐 " 不会被当成两个艺术家。
     *
     * @param songs 歌曲列表（NAS 全量 / 本地设备 / 百度网盘 / 多源搜索结果均可）
     * @param idPrefix 生成 Artist.id 的前缀，便于区分来源
     * @param useSongCover 为 true 时用该艺术家名下第一首有封面的歌曲封面作为 coverUrl
     *                     （本地艺术家用；百度艺术家留 null 走在线 iTunes/百度补全）
     */
    fun buildArtistsFromSongs(songs: List<Song>, idPrefix: String, useSongCover: Boolean = false): List<Artist> {
        // key = 归一化艺术家名，value = (展示名, 歌曲列表)
        val artistMap = linkedMapOf<String, Pair<String, MutableList<Song>>>()
        for (song in songs) {
            if (song.artist.isBlank()) continue
            for (name in ArtistSplitter.split(song.artist)) {
                val key = ArtistSplitter.normalizeKey(name)
                if (key.isBlank()) continue
                val pair = artistMap.getOrPut(key) { name to mutableListOf() }
                pair.second.add(song)
            }
        }
        return artistMap.map { (key, pair) ->
            val (displayName, artistSongs) = pair
            Artist(
                id = "$idPrefix$key",
                name = displayName,
                songCount = artistSongs.size,
                albumCount = artistSongs.map { it.album }.filter { it.isNotBlank() }.distinct().size,
                coverUrl = if (useSongCover) artistSongs.firstOrNull { it.coverUrl != null }?.coverUrl else null
            )
        }
    }

    /**
     * 合并曲库「歌曲」Tab 的多源歌曲（NAS 分页 + 本地 + 百度索引）。
     *
     * 顺序固定 NAS → 本地 → 百度（各源内部保持传入顺序），按 [Song.id] 去重；
     * 不同源的同一首歌 id 不同，会分别保留（与本地歌曲既有展示口径一致）。
     * 无本地/百度数据时直接返回原列表，避免无谓复制。
     */
    fun mergeLibrarySongs(
        nasSongs: List<Song>,
        localSongs: List<Song>,
        baiduSongs: List<Song> = emptyList()
    ): List<Song> {
        if (localSongs.isEmpty() && baiduSongs.isEmpty()) return nasSongs
        val seen = HashSet<String>(nasSongs.size + localSongs.size + baiduSongs.size)
        return (nasSongs + localSongs + baiduSongs).filter { seen.add(it.id) }
    }

    /**
     * 双键拼音排序：主键 groupLetter（A→Z, #排最后），副键 fullPinyin（同组内全拼排序）
     *
     * # 组使用 "{" 作为 sort key（ASCII 码在 'Z' 之后），保证 # 排在所有字母组之后。
     */
    private fun <T> sortByPinyin(items: List<T>, nameSelector: (T) -> String): List<T> {
        return items.sortedWith(compareBy(
            { val letter = PinyinUtils.getGroupLetter(nameSelector(it)); if (letter == '#') '{' else letter },
            { PinyinUtils.toPinyin(nameSelector(it)) }
        ))
    }
}