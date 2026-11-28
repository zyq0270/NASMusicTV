package com.nasmusic.tv.player

import com.nasmusic.tv.backend.local.AudioHeaderRequest
import com.nasmusic.tv.backend.local.AudioHeaderSource
import com.nasmusic.tv.backend.local.GaplessProbe
import com.nasmusic.tv.backend.local.GaplessProbeResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 无缝探测的 IO 执行器。
 *
 * [PlayerManager] 内不许出现后台调度器（player 访问必须留在调用线程），
 * 而读文件头 / 解析帧信息是阻塞 IO ⇒ 把这段切分放在本类。
 */
internal class GaplessProber(private val headerSource: AudioHeaderSource) {

    suspend fun probe(song: Song, source: SongSourceIdentity): GaplessProbeResult =
        withContext(Dispatchers.IO) {
            runCatching {
                headerSource.open(AudioHeaderRequest(song, source, song.streamUrl.orEmpty(), null)).use {
                    GaplessProbe.probe(it.stream)
                }
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                GaplessProbeResult(false, reason = "probe_failed")
            }
        }
}