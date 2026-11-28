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
    val remotePlaylistId: String? = null,
    val playlistName: String? = null,
    val remotePlaylists: List<Playlist> = emptyList(),
    val remoteLoading: Boolean = false,
    val remoteError: BackendError? = null,
)

class PlaylistSyncViewModel(
    private val exporter: PlaylistExporter,
    private val sync: BackendPlaylistSync,
) : ViewModel() {
    enum class Operation { EXPORT_JSON, EXPORT_M3U, PULL, PUSH }

    private val _state = MutableStateFlow(PlaylistSyncUiState())
    val state: StateFlow<PlaylistSyncUiState> = _state.asStateFlow()
    private val operationMutex = Mutex()

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

    fun push(name: String, refs: List<PlaylistSongRef?>) {
        launchOperation(Operation.PUSH) {
            sync.push(name, refs) { progress -> _state.value = _state.value.copy(progress = progress) }
        }
    }

    fun resumePush(name: String, refs: List<PlaylistSongRef?>) {
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

    /** 打开新选择器时清理上一次终态，但保留已加载的远端列表。 */
    fun prepare() {
        if (_state.value.busy) return
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
                _state.value = _state.value.copy(busy = false)
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
                    )
                }
            }
        }
    }
}
