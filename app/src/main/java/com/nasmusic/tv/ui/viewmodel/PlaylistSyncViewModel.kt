package com.nasmusic.tv.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.BackendResult
import com.nasmusic.tv.backend.playlist.BackendPlaylistSync
import com.nasmusic.tv.backend.playlist.PlaylistExporter
import com.nasmusic.tv.backend.playlist.PersistentPlaylistPushCheckpointStore
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** 文案由 UI 按 operation / error / reason 映射到字符串资源。 */
data class PlaylistSyncUiState(
    val operation: PlaylistSyncViewModel.Operation? = null,
    val busy: Boolean = false,
    val progress: Int = 0,
    val error: BackendError? = null,
    val reason: BackendPlaylistSync.FailureReason? = null,
    val completed: Boolean = false,
    val itemCount: Int = 0,
    val addedCount: Int = 0,
    val skippedCount: Int = 0,
    val skippedLocalCount: Int = 0,
    val skippedNetworkCount: Int = 0,
    val skippedLegacyCount: Int = 0,
    val failedIndex: Int? = null,
    val pendingCount: Int = 0,
    val unresolvedCount: Int = 0,
    val containsUrlPlaceholder: Boolean = false,
    val canResume: Boolean = false,
    /** 当前歌单名的检查点查询已完成（即使无检查点），供 UI 决定弹续传二选一还是改名。 */
    val resumeCheckDoneFor: String? = null,
    val remotePlaylistId: String? = null,
    val playlistName: String? = null,
    val remotePlaylists: List<Playlist> = emptyList(),
    val remoteLoading: Boolean = false,
    val remoteError: BackendError? = null,
)

class PlaylistSyncViewModel(
    private val exporter: PlaylistExporter,
    private val sync: BackendPlaylistSync,
    private val sourceIdentityProvider: (Song) -> SongSourceIdentity? = { null },
) : ViewModel() {
    enum class Operation { EXPORT_JSON, EXPORT_M3U, PULL, PUSH }

    private val _state = MutableStateFlow(PlaylistSyncUiState())
    val state: StateFlow<PlaylistSyncUiState> = _state.asStateFlow()
    private val operationMutex = Mutex()

    /** 最近一次推送使用的歌单名；prepare() 只按这个名字回填检查点，避免误恢复无关终态。 */
    private var pendingCheckpointName: String? = null

    fun exportJson(uri: Uri, name: String, songs: List<Song>, sourceResolver: (Song) -> PlaylistSongRef?) {
        launchOperation(Operation.EXPORT_JSON) { exporter.exportJson(uri, name, songs, sourceResolver) }
    }

    fun exportM3u(uri: Uri, name: String, songs: List<Song>, sourceResolver: (Song) -> PlaylistSongRef?) {
        launchOperation(Operation.EXPORT_M3U) { exporter.exportM3u(uri, name, songs, sourceResolver) }
    }

    fun pull(remotePlaylistId: String, localName: String) {
        launchOperation(Operation.PULL) {
            sync.pull(remotePlaylistId, localName) { progress ->
                _state.value = _state.value.copy(progress = progress)
            }
        }
    }

    /** 存量 NAS 条目推送时按当前连接补绑来源身份，避免降级为 NO_STABLE_ID / 裸 ID 去重。 */
    fun refForSong(song: Song): PlaylistSongRef? =
        PlaylistSongRef.fromSong(song, sourceIdentityProvider(song))

    fun push(name: String, refs: List<PlaylistSongRef?>) {
        pendingCheckpointName = name
        launchOperation(Operation.PUSH) {
            sync.push(name, refs) { progress -> _state.value = _state.value.copy(progress = progress) }
        }
    }

    fun resumePush(name: String, refs: List<PlaylistSongRef?>) {
        pendingCheckpointName = name
        launchOperation(Operation.PUSH) {
            sync.resumePush(name, refs) { progress -> _state.value = _state.value.copy(progress = progress) }
        }
    }

    fun loadRemotePlaylists() {
        if (_state.value.busy || _state.value.remoteLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(remoteLoading = true, remoteError = null)
            when (val result = sync.loadRemotePlaylists()) {
                is BackendResult.Success -> _state.value = _state.value.copy(
                    remotePlaylists = result.value,
                    remoteLoading = false,
                )
                is BackendResult.Failure -> _state.value = _state.value.copy(
                    remoteLoading = false,
                    remoteError = result.error,
                )
            }
        }
    }

    /**
     * 打开新选择器时清理上一次终态，但保留已加载的远端列表。
     * 若上次推送留下了检查点，只按该歌单名回填续传态（关闭重开对话框后仍可续）；
     * 没有推送历史时不接触 canResume，避免把无关终态错误恢复成可续。
     */
    fun prepare() {
        if (_state.value.busy) return
        val pendingName = pendingCheckpointName
        if (pendingName.isNullOrBlank()) {
            resetForNewSelection()
        } else {
            prepareForPush(pendingName)
        }
    }

    /** 选中歌单/准备推送时按名称与当前连接回填可续状态；不恢复任何终态错误。 */
    fun prepareForPush(name: String) {
        if (_state.value.busy) return
        pendingCheckpointName = name
        resetForNewSelection()
        viewModelScope.launch {
            val checkpoint = runCatching { sync.checkpointFor(name) }.getOrNull()
            if (_state.value.busy || pendingCheckpointName != name) return@launch
            // 即使没有检查点也要标记查询完成，UI 才能从「等待」切到「改名重建」。
            _state.value = if (checkpoint == null) {
                _state.value.copy(canResume = false, resumeCheckDoneFor = name)
            } else {
                _state.value.copy(
                    canResume = true,
                    resumeCheckDoneFor = name,
                    remotePlaylistId = checkpoint.remotePlaylistId,
                    playlistName = name,
                )
            }
        }
    }

    private fun resetForNewSelection() {
        _state.value = _state.value.copy(
            operation = null,
            progress = 0,
            error = null,
            reason = null,
            completed = false,
            itemCount = 0,
            addedCount = 0,
            skippedCount = 0,
            skippedLocalCount = 0,
            skippedNetworkCount = 0,
            skippedLegacyCount = 0,
            failedIndex = null,
            pendingCount = 0,
            unresolvedCount = 0,
            containsUrlPlaceholder = false,
            canResume = false,
            resumeCheckDoneFor = null,
            remotePlaylistId = null,
            playlistName = null,
            remoteError = null,
        )
    }

    fun consumeTerminalEvent() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(error = null, reason = null, completed = false)
    }

    private fun launchOperation(operation: Operation, block: suspend () -> Any?) {
        viewModelScope.launch {
            if (!operationMutex.tryLock()) return@launch
            _state.value = PlaylistSyncUiState(
                operation = operation,
                busy = true,
                remotePlaylists = _state.value.remotePlaylists,
            )
            try {
                when (val result = block()) {
                    is BackendPlaylistSync.Result.Failure -> _state.value = _state.value.copy(
                        busy = false,
                        error = result.error,
                        reason = result.reason,
                        canResume = result.checkpoint != null && result.reason == BackendPlaylistSync.FailureReason.REMOTE_FAILURE,
                        remotePlaylistId = result.checkpoint?.remotePlaylistId,
                        skippedCount = result.itemResults.count { it.status == BackendPlaylistSync.PushItemStatus.SKIPPED },
                        skippedLocalCount = result.itemResults.skipCount(BackendPlaylistSync.SkipReason.LOCAL_FILE),
                        skippedNetworkCount = result.itemResults.skipCount(BackendPlaylistSync.SkipReason.NETWORK_SONG),
                        skippedLegacyCount = result.itemResults.skipCount(BackendPlaylistSync.SkipReason.LEGACY_UNBOUND),
                        failedIndex = result.itemResults.firstOrNull {
                            it.status == BackendPlaylistSync.PushItemStatus.FAILED
                        }?.index?.plus(1),
                        pendingCount = result.itemResults.count {
                            it.status == BackendPlaylistSync.PushItemStatus.PENDING
                        },
                        addedCount = result.itemResults.count { it.status == BackendPlaylistSync.PushItemStatus.ADDED },
                        itemCount = result.itemResults.size,
                    )
                    is BackendPlaylistSync.Result.Success<*> -> when (val value = result.value) {
                        is BackendPlaylistSync.PullReport -> _state.value = _state.value.copy(
                            busy = false,
                            completed = true,
                            progress = 100,
                            itemCount = value.boundCount,
                            skippedCount = value.skipped.size,
                            playlistName = value.localPlaylist.name,
                        )
                        is BackendPlaylistSync.PushReport -> _state.value = _state.value.copy(
                            busy = false,
                            completed = true,
                            progress = 100,
                            itemCount = value.itemResults.size,
                            addedCount = value.itemResults.count {
                                it.status == BackendPlaylistSync.PushItemStatus.ADDED
                            },
                            skippedCount = value.skipped.size,
                            skippedLocalCount = value.itemResults.skipCount(BackendPlaylistSync.SkipReason.LOCAL_FILE),
                            skippedNetworkCount = value.itemResults.skipCount(BackendPlaylistSync.SkipReason.NETWORK_SONG),
                            skippedLegacyCount = value.itemResults.skipCount(BackendPlaylistSync.SkipReason.LEGACY_UNBOUND),
                            remotePlaylistId = value.remotePlaylistId,
                        )
                        else -> _state.value = _state.value.copy(busy = false, completed = true, progress = 100)
                    }
                    is BackendResult.Failure -> _state.value = _state.value.copy(
                        busy = false,
                        error = result.error,
                    )
                    is PlaylistExporter.Report -> _state.value = _state.value.copy(
                        busy = false,
                        completed = true,
                        progress = 100,
                        itemCount = result.itemCount,
                        unresolvedCount = result.unresolvedCount,
                        containsUrlPlaceholder = result.containsUrlPlaceholder,
                    )
                    is BackendResult.Success<*> -> _state.value = _state.value.copy(
                        busy = false,
                        completed = true,
                        progress = 100,
                    )
                    else -> _state.value = _state.value.copy(busy = false, completed = true, progress = 100)
                }
            } catch (e: CancellationException) {
                // 取消不等于推送完成：若检查点已落盘，保留可续状态供下次打开对话框使用。
                val name = pendingCheckpointName
                val checkpoint = if (operation == Operation.PUSH && !name.isNullOrBlank()) {
                    runCatching { sync.checkpointFor(name) }.getOrNull()
                } else {
                    null
                }
                _state.value = _state.value.copy(
                    busy = false,
                    canResume = checkpoint != null,
                    remotePlaylistId = checkpoint?.remotePlaylistId ?: _state.value.remotePlaylistId,
                    playlistName = checkpoint?.let { name } ?: _state.value.playlistName,
                )
                throw e
            } catch (e: Exception) {
                AppLog.e("PlaylistSyncViewModel", "playlist operation failed", e)
                _state.value = _state.value.copy(busy = false, error = BackendError.UNKNOWN)
            } finally {
                operationMutex.unlock()
            }
        }
    }

    private fun List<BackendPlaylistSync.PushItemResult>.skipCount(
        reason: BackendPlaylistSync.SkipReason,
    ): Int = count { it.status == BackendPlaylistSync.PushItemStatus.SKIPPED && it.skipReason == reason }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val app = appContext as NasMusicApp
                    PlaylistSyncViewModel(
                        exporter = PlaylistExporter(app.contentResolver),
                        sync = BackendPlaylistSync(
                            prefs = app.appPreferences,
                            adapterProvider = app.backendRegistry::getAdapter,
                            configProvider = app.backendRegistry::getConfig,
                            connectionProvider = app.backendRegistry::getActiveConnection,
                            checkpointStore = PersistentPlaylistPushCheckpointStore(app.roadmapPreferences),
                        ),
                        sourceIdentityProvider = app.sourceIdentityProvider,
                    )
                }
            }
        }
    }
}
