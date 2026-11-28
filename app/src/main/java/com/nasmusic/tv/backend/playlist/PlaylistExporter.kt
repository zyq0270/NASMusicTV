package com.nasmusic.tv.backend.playlist

import android.content.ContentResolver
import android.net.Uri
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.backend.backendErrorFromThrowable
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF 歌单导出器。所有写入都在 IO 线程，错误统一映射为 [BackendError]。 */
class PlaylistExporter(
    private val contentResolver: ContentResolver,
    private val jsonParser: JsonPlaylistParser = JsonPlaylistParser(),
    private val m3uSerializer: M3uPlaylistSerializer = M3uPlaylistSerializer(),
) {
    data class Report(
        val itemCount: Int,
        val containsUrlPlaceholder: Boolean = false,
        val unresolvedCount: Int = 0,
    )

    suspend fun exportJson(
        uri: Uri,
        playlistName: String,
        songs: List<Song>,
        sourceResolver: (Song) -> PlaylistSongRef? = { PlaylistSongRef.fromSong(it) },
        createdAt: Long = System.currentTimeMillis(),
    ): BackendResult<Report> = serializeAndWrite(uri) {
        jsonParser.serializeSongs(playlistName, songs, sourceResolver, createdAt)
            .toByteArray(Charsets.UTF_8) to Report(songs.size)
    }

    suspend fun exportM3u(
        uri: Uri,
        playlistName: String,
        songs: List<Song>,
        sourceResolver: (Song) -> PlaylistSongRef? = { PlaylistSongRef.fromSong(it) },
    ): BackendResult<Report> = serializeAndWrite(uri) {
        m3uSerializer.serialize(playlistName, songs, sourceResolver).let { result ->
            result.text.toByteArray(Charsets.UTF_8) to
                Report(songs.size, result.containsUrlPlaceholder, result.unresolvedCount)
        }
    }

    private suspend fun serializeAndWrite(
        uri: Uri,
        serialize: suspend () -> Pair<ByteArray, Report>,
    ): BackendResult<Report> = try {
        val (bytes, report) = serialize()
        write(uri, bytes, report)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        BackendResult.Failure(backendErrorFromThrowable(e))
    }

    private suspend fun write(
        uri: Uri,
        bytes: ByteArray,
        report: Report,
    ): BackendResult<Report> = withContext(Dispatchers.IO) {
        try {
            val stream = contentResolver.openOutputStream(uri, "w")
                ?: return@withContext BackendResult.Failure(BackendError.NOT_FOUND)
            stream.use { it.write(bytes) }
            BackendResult.Success(report)
        } catch (e: SecurityException) {
            BackendResult.Failure(BackendError.FORBIDDEN)
        } catch (e: FileNotFoundException) {
            BackendResult.Failure(BackendError.NOT_FOUND)
        } catch (e: IOException) {
            BackendResult.Failure(backendErrorFromThrowable(e))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            BackendResult.Failure(backendErrorFromThrowable(e))
        }
    }
}
