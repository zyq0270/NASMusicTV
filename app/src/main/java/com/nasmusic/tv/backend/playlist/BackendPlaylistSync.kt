package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.backend.backendErrorFromThrowable
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceKind
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.SongKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** 本地批量写入边界；生产实现在一次 DataStore edit 内创建完整歌单。 */
interface LocalPlaylistStore {
    suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist
}

class AppPreferencesLocalPlaylistStore(private val prefs: AppPreferences) : LocalPlaylistStore {
    override suspend fun createWithSongs(name: String, songs: List<Song>): LocalPlaylist =
        prefs.createLocalPlaylistWithSongs(name, songs, renameOnConflict = true)
}

/** 部分推送检查点；可由调用方持久化后跨进程续传。 */
data class PlaylistPushCheckpoint(
    val remotePlaylistId: String,
    val nextIndex: Int,
    val playlistName: String,
    val serverId: String,
    val backendType: String,
    /** 推送列表指纹；续传时防止本地歌单已变更却从旧 index 继续。 */
    val sourceFingerprint: String = "",
)

interface PlaylistPushCheckpointStore {
    suspend fun load(key: String): PlaylistPushCheckpoint?
    suspend fun save(key: String, checkpoint: PlaylistPushCheckpoint)
    suspend fun clear(key: String)
}

/** 默认进程内实现；UI ViewModel 存活期间可续传，跨进程存储由后续接线。 */
class InMemoryPlaylistPushCheckpointStore : PlaylistPushCheckpointStore {
    private val values = ConcurrentHashMap<String, PlaylistPushCheckpoint>()
    override suspend fun load(key: String): PlaylistPushCheckpoint? = values[key]
    override suspend fun save(key: String, checkpoint: PlaylistPushCheckpoint) {
        values[key] = checkpoint
    }
    override suspend fun clear(key: String) {
        values.remove(key)
    }
}

class BackendPlaylistSync(
    private val prefs: AppPreferences,
    private val adapterProvider: () -> BackendAdapter?,
    private val configProvider: () -> ServerConfig?,
    private val localStore: LocalPlaylistStore = AppPreferencesLocalPlaylistStore(prefs),
    private val checkpointStore: PlaylistPushCheckpointStore = InMemoryPlaylistPushCheckpointStore(),
    private val connectionProvider: () -> Pair<BackendAdapter, ServerConfig>? = {
        adapterProvider()?.let { adapter -> configProvider()?.let { adapter to it } }
    },
) {
    enum class FailureReason {
        BUSY, NOT_CONNECTED, UNSUPPORTED, SOURCE_MISMATCH, LEGACY_UNBOUND,
        NO_STABLE_ID, INVALID_STABLE_KEY, NAME_CONFLICT, CHECKPOINT_MISMATCH, INVALID_NAME,
        REMOTE_FAILURE, LOCAL_WRITE_FAILED,
    }

    enum class SkipReason { LOCAL_FILE, NETWORK_SONG, LEGACY_UNBOUND, NO_STABLE_ID, INVALID_SONG, DUPLICATE }

    data class Skipped(val index: Int, val reason: SkipReason)

    data class PullReport(
        val localPlaylist: LocalPlaylist,
        val boundCount: Int,
        val skipped: List<Skipped>,
    )

    enum class PushItemStatus { ADDED, SKIPPED, FAILED, PENDING }

    /** 按原始歌单位置返回逐曲结果；FAILED/PENDING 可结合 checkpoint 续传。 */
    data class PushItemResult(
        val index: Int,
        val nativeId: String?,
        val stableKey: String?,
        val status: PushItemStatus,
        val skipReason: SkipReason? = null,
        val error: BackendError? = null,
        val reason: FailureReason? = null,
    )

    data class PushReport(
        val remotePlaylistId: String,
        val addedCount: Int,
        val skipped: List<Skipped>,
        val checkpoint: PlaylistPushCheckpoint,
        val itemResults: List<PushItemResult>,
    )

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>
        data class Failure(
            val error: BackendError,
            val reason: FailureReason,
            val checkpoint: PlaylistPushCheckpoint? = null,
            val itemResults: List<PushItemResult> = emptyList(),
        ) : Result<Nothing>
    }

    private data class IndexedRef(val index: Int, val ref: PlaylistSongRef)

    private val operationMutex = Mutex()
    private val memoryCheckpoints = ConcurrentHashMap<String, PlaylistPushCheckpoint>()

    private suspend fun saveCheckpoint(key: String, checkpoint: PlaylistPushCheckpoint) {
        memoryCheckpoints[key] = checkpoint
        try {
            checkpointStore.save(key, checkpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.nasmusic.tv.util.AppLog.w(
                "BackendPlaylistSync",
                "checkpoint persistence failed; keeping in-memory checkpoint for safe retry",
                e,
            )
        }
    }

    private suspend fun loadCheckpoint(key: String): PlaylistPushCheckpoint? =
        checkpointStore.load(key) ?: memoryCheckpoints[key]

    private suspend fun clearCheckpoint(key: String) {
        memoryCheckpoints.remove(key)
        // 与 saveCheckpoint 对齐：歌曲已全部落库，清理持久化失败不能把成功误报为失败。
        try {
            checkpointStore.clear(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.nasmusic.tv.util.AppLog.w(
                "BackendPlaylistSync",
                "checkpoint cleanup failed; push already succeeded",
                e,
            )
        }
    }

    /**
     * 按歌单名 + 当前连接读取可续检查点。供推送入口跨对话框/跨入口回填续传态；
     * 不恢复任何终态错误，也无法通过名称以外的条件匹配到别的歌单检查点。
     */
    suspend fun checkpointFor(playlistName: String): PlaylistPushCheckpoint? {
        if (playlistName.isBlank()) return null
        val (adapter, config) = connectionProvider() ?: return null
        if (!adapter.capabilities.playlistCreate || !adapter.capabilities.playlistAdd) return null
        val checkpoint = loadCheckpoint(checkpointKey(playlistName, adapter, config)) ?: return null
        return checkpoint.takeIf {
            it.playlistName == playlistName && it.serverId == config.id &&
                it.backendType == adapter.backendType && it.remotePlaylistId.isNotBlank()
        }
    }

    suspend fun loadRemotePlaylists(): BackendResult<List<com.nasmusic.tv.data.model.Playlist>> {
        if (!operationMutex.tryLock()) return BackendResult.Failure(BackendError.CONFLICT)
        return try {
            val (adapter, _) = connectionProvider()
                ?: return BackendResult.Failure(BackendError.UNSUPPORTED)
            if (!adapter.capabilities.playlistRead) return BackendResult.Failure(BackendError.UNSUPPORTED)
            adapter.getPlaylistsResult()
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun pull(
        remotePlaylistId: String,
        localName: String,
        onProgress: (Int) -> Unit = {},
    ): Result<PullReport> {
        onProgress(0)
        if (!operationMutex.tryLock()) return Result.Failure(BackendError.CONFLICT, FailureReason.BUSY)
        return try {
            val (adapter, config) = connectionProvider()
                ?: return Result.Failure(BackendError.UNSUPPORTED, FailureReason.NOT_CONNECTED)
            if (!adapter.capabilities.playlistRead) {
                return Result.Failure(BackendError.UNSUPPORTED, FailureReason.UNSUPPORTED)
            }
            when (val result = adapter.getPlaylistSongsResult(remotePlaylistId)) {
                is BackendResult.Failure -> Result.Failure(result.error, FailureReason.REMOTE_FAILURE)
                is BackendResult.Success -> {
                    val skipped = mutableListOf<Skipped>()
                    val bound = mutableListOf<Song>()
                    val seenKeys = HashSet<String>()
                    val seenIds = HashSet<String>()
                    result.value.forEachIndexed { index, song ->
                        onProgress((index + 1) * 75 / maxOf(result.value.size, 1))
                        val ref = PlaylistSongRef.fromSong(
                            song = song,
                            serverId = config.id,
                            backendType = adapter.backendType,
                        )
                        val reason = when {
                            song.title.isBlank() || song.id.isBlank() || ref == null || !ref.isPushableToNas ->
                                SkipReason.NO_STABLE_ID
                            !seenKeys.add(ref.stableKey) || !seenIds.add(song.id) -> SkipReason.DUPLICATE
                            else -> null
                        }
                        if (reason != null) {
                            skipped += Skipped(index, reason)
                        } else {
                            bound += PlaylistSongRef.bindToSong(song, ref!!)
                        }
                    }
                    val name = uniqueLocalName(localName)
                    val playlist = try {
                        localStore.createWithSongs(name, bound)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return Result.Failure(backendErrorFromThrowable(e), FailureReason.LOCAL_WRITE_FAILED)
                    }
                    onProgress(100)
                    Result.Success(PullReport(playlist, bound.size, skipped))
                }
            }
        } finally {
            operationMutex.unlock()
        }
    }

    suspend fun push(
        playlistName: String,
        refs: List<PlaylistSongRef?>,
        onProgress: (Int) -> Unit = {},
    ): Result<PushReport> = pushInternal(playlistName, refs, resumeRequested = false, onProgress)

    suspend fun resumePush(
        playlistName: String,
        refs: List<PlaylistSongRef?>,
        onProgress: (Int) -> Unit = {},
    ): Result<PushReport> = pushInternal(playlistName, refs, resumeRequested = true, onProgress)

    private suspend fun pushInternal(
        playlistName: String,
        refs: List<PlaylistSongRef?>,
        resumeRequested: Boolean,
        onProgress: (Int) -> Unit,
    ): Result<PushReport> {
        onProgress(0)
        if (!operationMutex.tryLock()) return Result.Failure(BackendError.CONFLICT, FailureReason.BUSY)
        return try {
            if (playlistName.isBlank()) {
                return Result.Failure(BackendError.CONFLICT, FailureReason.INVALID_NAME)
            }
            val (adapter, config) = connectionProvider()
                ?: return Result.Failure(BackendError.UNSUPPORTED, FailureReason.NOT_CONNECTED)
            if (!adapter.capabilities.playlistCreate || !adapter.capabilities.playlistAdd) {
                return Result.Failure(BackendError.UNSUPPORTED, FailureReason.UNSUPPORTED)
            }

            val outcomes = MutableList<PushItemResult?>(refs.size) { null }
            val pushable = mutableListOf<IndexedRef>()
            val seenKeys = HashSet<String>()
            refs.forEachIndexed { index, ref ->
                val item = when {
                    ref == null -> PushItemResult(
                        index, null, null, PushItemStatus.SKIPPED, SkipReason.NO_STABLE_ID,
                    )
                    ref.binding == PlaylistSongRef.Binding.LEGACY_UNBOUND -> PushItemResult(
                        index, ref.source.nativeId, ref.stableKey, PushItemStatus.SKIPPED,
                        SkipReason.LEGACY_UNBOUND,
                    )
                    ref.source.kind != SongSourceKind.NAS -> PushItemResult(
                        index, ref.source.nativeId, ref.stableKey, PushItemStatus.SKIPPED,
                        if (ref.source.kind == SongSourceKind.LOCAL) SkipReason.LOCAL_FILE else SkipReason.NETWORK_SONG,
                    )
                    !ref.isBoundTo(config.id, adapter.backendType) -> PushItemResult(
                        index, ref.source.nativeId, ref.stableKey, PushItemStatus.FAILED,
                        error = BackendError.CONFLICT, reason = FailureReason.SOURCE_MISMATCH,
                    )
                    !hasValidStableKey(ref) -> PushItemResult(
                        index, ref.source.nativeId, ref.stableKey, PushItemStatus.FAILED,
                        error = BackendError.CONFLICT, reason = FailureReason.INVALID_STABLE_KEY,
                    )
                    !seenKeys.add(ref.stableKey) -> PushItemResult(
                        index, ref.source.nativeId, ref.stableKey, PushItemStatus.SKIPPED,
                        SkipReason.DUPLICATE,
                    )
                    else -> {
                        pushable += IndexedRef(index, ref)
                        PushItemResult(index, ref.source.nativeId, ref.stableKey, PushItemStatus.PENDING)
                    }
                }
                outcomes[index] = item
                if (item.status == PushItemStatus.FAILED) {
                    return Result.Failure(
                        item.error ?: BackendError.CONFLICT,
                        item.reason ?: FailureReason.REMOTE_FAILURE,
                        itemResults = outcomes.filterNotNull(),
                    )
                }
            }

            val key = checkpointKey(playlistName, adapter, config)
            var checkpoint = if (resumeRequested) loadCheckpoint(key) else null
            val fingerprint = fingerprint(pushable.map { it.ref })
            if (checkpoint != null && !checkpoint.matches(
                    serverId = config.id,
                    backendType = adapter.backendType,
                    name = playlistName,
                    pushableCount = pushable.size,
                    fingerprint = fingerprint,
                )
            ) {
                return Result.Failure(
                    BackendError.CONFLICT,
                    FailureReason.CHECKPOINT_MISMATCH,
                    checkpoint,
                    outcomes.filterNotNull(),
                )
            }
            // 没有任何可推送条目时不创建远端歌单，避免重复操作累积孤儿空歌单。
            if (pushable.isEmpty()) {
                onProgress(100)
                val itemResults = outcomes.map { checkNotNull(it) }
                return Result.Success(
                    PushReport(
                        remotePlaylistId = checkpoint?.remotePlaylistId.orEmpty(),
                        addedCount = 0,
                        skipped = itemResults.mapNotNull { item ->
                            item.skipReason?.let { Skipped(item.index, it) }
                        },
                        checkpoint = checkpoint ?: PlaylistPushCheckpoint(
                            remotePlaylistId = "",
                            nextIndex = 0,
                            playlistName = playlistName,
                            serverId = config.id,
                            backendType = adapter.backendType,
                            sourceFingerprint = fingerprint,
                        ),
                        itemResults = itemResults,
                    ),
                )
            }
            if (checkpoint == null) {
                when (val existing = adapter.getPlaylistsResult()) {
                    is BackendResult.Failure -> return Result.Failure(
                        existing.error, FailureReason.REMOTE_FAILURE, itemResults = outcomes.filterNotNull(),
                    )
                    is BackendResult.Success -> {
                        if (existing.value.any { it.name.equals(playlistName, ignoreCase = true) }) {
                            return Result.Failure(
                                BackendError.CONFLICT,
                                FailureReason.NAME_CONFLICT,
                                itemResults = outcomes.filterNotNull(),
                            )
                        }
                    }
                }
                when (val created = adapter.createPlaylistResult(playlistName)) {
                    is BackendResult.Failure -> return Result.Failure(
                        created.error, FailureReason.REMOTE_FAILURE, itemResults = outcomes.filterNotNull(),
                    )
                    is BackendResult.Success -> {
                        if (created.value.id.isBlank()) {
                            return Result.Failure(
                                BackendError.PROTOCOL,
                                FailureReason.REMOTE_FAILURE,
                                itemResults = outcomes.filterNotNull(),
                            )
                        }
                        val createdCheckpoint = PlaylistPushCheckpoint(
                            remotePlaylistId = created.value.id,
                            nextIndex = 0,
                            playlistName = playlistName,
                            serverId = config.id,
                            backendType = adapter.backendType,
                            sourceFingerprint = fingerprint,
                        )
                        checkpoint = createdCheckpoint
                        saveCheckpoint(key, createdCheckpoint)
                    }
                }
            }

            val activeCheckpoint = checkpoint
                ?: return Result.Failure(
                    BackendError.PROTOCOL,
                    FailureReason.REMOTE_FAILURE,
                    itemResults = outcomes.filterNotNull(),
                )
            var currentCheckpoint = activeCheckpoint
            var added = 0
            var index = activeCheckpoint.nextIndex
            if (resumeRequested && adapter.capabilities.playlistRead) {
                when (val remote = adapter.getPlaylistSongsResult(activeCheckpoint.remotePlaylistId)) {
                    is BackendResult.Success -> {
                        val existingIds = remote.value.mapTo(HashSet()) { it.id }
                        // A prior add may have reached the server before checkpoint persistence failed.
                        while (index < pushable.size &&
                            pushable[index].ref.source.nativeId in existingIds
                        ) {
                            outcomes[pushable[index].index] =
                                outcomes[pushable[index].index]!!.copy(status = PushItemStatus.ADDED)
                            index++
                        }
                        if (index != activeCheckpoint.nextIndex) {
                            currentCheckpoint = currentCheckpoint.copy(nextIndex = index)
                            saveCheckpoint(key, currentCheckpoint)
                        }
                    }
                    is BackendResult.Failure -> {
                        return Result.Failure(
                            remote.error,
                            FailureReason.REMOTE_FAILURE,
                            activeCheckpoint,
                            outcomes.filterNotNull(),
                        )
                    }
                }
            }
            pushable.take(index).forEach { outcomes[it.index] = outcomes[it.index]!!.copy(status = PushItemStatus.ADDED) }
            while (index < pushable.size) {
                val entry = pushable[index]
                when (val addedResult = adapter.addToPlaylistResult(activeCheckpoint.remotePlaylistId, entry.ref.source.nativeId)) {
                    is BackendResult.Failure -> {
                        outcomes[entry.index] = outcomes[entry.index]!!.copy(
                            status = PushItemStatus.FAILED,
                            error = addedResult.error,
                            reason = FailureReason.REMOTE_FAILURE,
                        )
                        saveCheckpoint(key, currentCheckpoint)
                        return Result.Failure(
                            addedResult.error,
                            FailureReason.REMOTE_FAILURE,
                            currentCheckpoint,
                            outcomes.filterNotNull(),
                        )
                    }
                    is BackendResult.Success -> {
                        outcomes[entry.index] = outcomes[entry.index]!!.copy(status = PushItemStatus.ADDED)
                        added++
                        index++
                        currentCheckpoint = currentCheckpoint.copy(nextIndex = index)
                        saveCheckpoint(key, currentCheckpoint)
                        onProgress(index * 100 / maxOf(pushable.size, 1))
                    }
                }
            }
            clearCheckpoint(key)
            onProgress(100)
            val itemResults = outcomes.map { checkNotNull(it) }
            Result.Success(
                PushReport(
                    remotePlaylistId = activeCheckpoint.remotePlaylistId,
                    addedCount = added,
                    skipped = itemResults.mapNotNull { item ->
                        item.skipReason?.let { Skipped(item.index, it) }
                    },
                    checkpoint = currentCheckpoint,
                    itemResults = itemResults,
                ),
            )
        } finally {
            operationMutex.unlock()
        }
    }

    private fun hasValidStableKey(ref: PlaylistSongRef): Boolean = runCatching {
        SongKeys.stableKey(
            Song(id = ref.source.nativeId, title = ""),
            ref.source,
        ) == ref.stableKey
    }.getOrDefault(false)

    private fun fingerprint(refs: List<PlaylistSongRef>): String {
        val bytes = refs.joinToString("\u0000") { "${it.stableKey}\u0001${it.source.nativeId}" }
            .toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private suspend fun uniqueLocalName(base: String): String {
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val existing = prefs.getLocalPlaylists().map { it.name.lowercase(Locale.ROOT) }.toSet()
        if (base.lowercase(Locale.ROOT) !in existing) return base
        val first = "$base (导入 $date)"
        if (first.lowercase(Locale.ROOT) !in existing) return first
        var suffix = 2
        while ("$first $suffix".lowercase(Locale.ROOT) in existing) suffix++
        return "$first $suffix"
    }

    private fun checkpointKey(
        name: String,
        adapter: BackendAdapter?,
        config: ServerConfig?,
    ): String = "${config?.id.orEmpty()}|${adapter?.backendType.orEmpty()}|$name"

    private fun PlaylistPushCheckpoint.matches(
        serverId: String,
        backendType: String,
        name: String,
        pushableCount: Int,
        fingerprint: String,
    ): Boolean = this.serverId == serverId && this.backendType == backendType &&
        playlistName == name && remotePlaylistId.isNotBlank() && nextIndex in 0..pushableCount &&
        (sourceFingerprint.isEmpty() || sourceFingerprint == fingerprint)
}
