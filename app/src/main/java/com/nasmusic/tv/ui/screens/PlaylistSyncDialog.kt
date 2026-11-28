package com.nasmusic.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.playlist.BackendPlaylistSync
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.PlaylistSongRef
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.PlaylistSyncUiState
import com.nasmusic.tv.ui.viewmodel.PlaylistSyncViewModel

enum class PlaylistExportFormat { JSON, M3U }

/** 导出、拉取、推送共用一个 Dialog；真实 Dialog 内自行消费 BACK，不改变 MainActivity 导航层级。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistSyncDialog(
    mode: PlaylistSyncDialogMode,
    state: PlaylistSyncUiState,
    localPlaylists: List<LocalPlaylist>,
    onExport: (LocalPlaylist, PlaylistExportFormat) -> Unit,
    onPull: (Playlist) -> Unit,
    onPush: (String, List<PlaylistSongRef?>) -> Unit,
    onResume: (String, List<PlaylistSongRef?>) -> Unit,
    onRefreshRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedLocal by remember(mode) { mutableStateOf<LocalPlaylist?>(null) }
    var selectedRemote by remember(mode) { mutableStateOf<Playlist?>(null) }
    var format by remember(mode) { mutableStateOf(PlaylistExportFormat.JSON) }
    var pushTarget by remember(mode) { mutableStateOf<LocalPlaylist?>(null) }
    var pushName by remember(mode) { mutableStateOf("") }
    var showRemoteName by remember(mode) { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler(enabled = !state.busy && !showRemoteName) { onDismiss() }
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xB3000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .then(responsiveDialogSize(560.dp))
                    .fillMaxHeight(0.9f)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        when (mode) {
                            PlaylistSyncDialogMode.EXPORT -> R.string.playlist_sync_export_title
                            PlaylistSyncDialogMode.PULL -> R.string.playlist_sync_pull_title
                            PlaylistSyncDialogMode.PUSH -> R.string.playlist_sync_push_title
                        }
                    ),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                )
                Spacer(modifier = Modifier.height(12.dp))

                val showStatus = when (mode) {
                    PlaylistSyncDialogMode.EXPORT -> false
                    PlaylistSyncDialogMode.PULL -> state.operation == PlaylistSyncViewModel.Operation.PULL
                    PlaylistSyncDialogMode.PUSH -> state.operation == PlaylistSyncViewModel.Operation.PUSH
                } && (state.busy || state.completed || state.error != null)
                if (showStatus) {
                    PlaylistSyncStatus(state = state, mode = mode)
                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        when {
                            mode == PlaylistSyncDialogMode.PUSH &&
                                state.reason == BackendPlaylistSync.FailureReason.NAME_CONFLICT -> {
                                SyncDialogButton(
                                    label = stringResource(R.string.playlist_sync_rename_remote),
                                    onClick = { showRemoteName = true },
                                )
                            }
                            mode == PlaylistSyncDialogMode.PUSH && state.canResume && pushTarget != null -> {
                                SyncDialogButton(
                                    label = stringResource(R.string.playlist_sync_resume),
                                    onClick = {
                                        val target = pushTarget ?: return@SyncDialogButton
                                        onResume(pushName, target.songs.map { PlaylistSongRef.fromSong(it) })
                                    },
                                )
                            }
                            canRetry(state, mode) -> {
                                SyncDialogButton(
                                    label = stringResource(R.string.playlist_sync_retry),
                                    onClick = {
                                        when (mode) {
                                            PlaylistSyncDialogMode.PULL -> selectedRemote?.let(onPull)
                                            PlaylistSyncDialogMode.PUSH -> pushTarget?.let {
                                                onPush(pushName, it.songs.map { song -> PlaylistSongRef.fromSong(song) })
                                            }
                                            PlaylistSyncDialogMode.EXPORT -> Unit
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SyncDialogButton(
                        label = stringResource(R.string.playlist_sync_close),
                        onClick = onDismiss,
                        secondary = true,
                    )
                    return@Column
                }

                when (mode) {
                    PlaylistSyncDialogMode.EXPORT -> {
                        if (localPlaylists.isEmpty()) {
                            EmptySyncList(stringResource(R.string.playlist_sync_no_local))
                        } else {
                            Text(
                                text = stringResource(R.string.playlist_sync_choose_local),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.body(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(localPlaylists, key = { _, item -> item.id }) { index, item ->
                                    SyncListRow(
                                        title = item.name,
                                        subtitle = stringResource(R.string.mine_song_count, item.songs.size),
                                        selected = selectedLocal?.id == item.id,
                                        requestFocus = index == 0,
                                        onClick = { selectedLocal = item },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.playlist_sync_format),
                                color = NasMusicColors.TextPrimary,
                                fontSize = FontSize.button(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SyncDialogButton(
                                label = stringResource(R.string.playlist_sync_format_json),
                                onClick = { format = PlaylistExportFormat.JSON },
                                selected = format == PlaylistExportFormat.JSON,
                                secondary = true,
                            )
                            SyncDialogButton(
                                label = stringResource(R.string.playlist_sync_format_m3u),
                                onClick = { format = PlaylistExportFormat.M3U },
                                selected = format == PlaylistExportFormat.M3U,
                                secondary = true,
                            )
                            Text(
                                text = stringResource(
                                    if (format == PlaylistExportFormat.JSON) R.string.playlist_sync_format_json_desc
                                    else R.string.playlist_sync_format_m3u_desc
                                ),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            SyncDialogButton(
                                label = stringResource(R.string.playlist_sync_choose_save_location),
                                enabled = selectedLocal != null,
                                onClick = { selectedLocal?.let { onExport(it, format) } },
                            )
                        }
                    }
                    PlaylistSyncDialogMode.PULL -> {
                        val remote = selectedRemote
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.playlist_sync_remote_playlists),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.body(),
                                modifier = Modifier.weight(1f),
                            )
                            SyncDialogButton(
                                label = stringResource(R.string.playlist_sync_refresh),
                                onClick = onRefreshRemote,
                                enabled = !state.remoteLoading,
                                secondary = true,
                            )
                        }
                        when {
                            state.remoteLoading -> EmptySyncList(stringResource(R.string.playlist_sync_busy))
                            state.remoteError != null -> EmptySyncList(stringResource(R.string.playlist_sync_remote_load_failed))
                            state.remotePlaylists.isEmpty() -> EmptySyncList(stringResource(R.string.playlist_sync_no_remote))
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(state.remotePlaylists, key = { _, item -> item.id }) { index, item ->
                                    SyncListRow(
                                        title = item.name,
                                        subtitle = null,
                                        selected = selectedRemote?.id == item.id,
                                        requestFocus = index == 0,
                                        onClick = { selectedRemote = item },
                                    )
                                }
                            }
                        }
                        if (!state.remoteLoading && remote != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            SyncDialogButton(
                                label = stringResource(R.string.playlist_sync_pull_selected, remote.name),
                                onClick = { onPull(remote) },
                            )
                        }
                    }
                    PlaylistSyncDialogMode.PUSH -> {
                        if (localPlaylists.isEmpty()) {
                            EmptySyncList(stringResource(R.string.playlist_sync_no_local))
                        } else {
                            Text(
                                text = stringResource(R.string.playlist_sync_choose_local),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.body(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                itemsIndexed(localPlaylists, key = { _, item -> item.id }) { index, item ->
                                    SyncListRow(
                                        title = item.name,
                                        subtitle = stringResource(R.string.mine_song_count, item.songs.size),
                                        selected = selectedLocal?.id == item.id,
                                        requestFocus = index == 0,
                                        onClick = {
                                            selectedLocal = item
                                            pushTarget = item
                                            pushName = item.name
                                            showRemoteName = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SyncDialogButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    secondary = true,
                )
            }
        }
    }

    val target = pushTarget
    if (showRemoteName && target != null) {
        TextInputDialog(
            title = stringResource(R.string.playlist_sync_rename_remote),
            hint = stringResource(R.string.playlist_sync_remote_name_hint),
            initialValue = pushName,
            showQrCode = false,
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    pushName = name.trim()
                    onPush(pushName, target.songs.map { PlaylistSongRef.fromSong(it) })
                    showRemoteName = false
                }
            },
            onDismiss = { showRemoteName = false },
        )
    }
}

enum class PlaylistSyncDialogMode { EXPORT, PULL, PUSH }

/** SAF 已取得 URI 后的导出进度窗；操作未完成时 BACK 不关闭，避免误以为已取消写入。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistSyncProgressDialog(state: PlaylistSyncUiState) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        BackHandler {}
        Column(
            modifier = Modifier
                .then(responsiveDialogSize(420.dp))
                .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaylistSyncStatus(state = state, mode = PlaylistSyncDialogMode.EXPORT)
        }
    }
}

@Composable
private fun PlaylistSyncStatus(state: PlaylistSyncUiState, mode: PlaylistSyncDialogMode) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.busy -> Text(
                text = stringResource(R.string.playlist_sync_busy),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
            )
            state.completed -> Text(
                text = playlistSyncSuccessMessage(state),
                color = NasMusicColors.Primary,
                fontSize = FontSize.button(),
            )
            state.error != null -> Text(
                text = playlistSyncErrorMessage(state),
                color = NasMusicColors.Warning,
                fontSize = FontSize.button(),
            )
        }
        if (state.busy) {
            Text(
                text = stringResource(R.string.playlist_sync_progress, state.progress),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
            )
        } else if (state.completed || state.error != null) {
            if (state.error != null && state.addedCount > 0) {
                Text(
                    text = stringResource(R.string.playlist_sync_added_summary, state.addedCount),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                )
            }
            if (state.error != null && state.failedIndex != null) {
                Text(
                    text = stringResource(R.string.playlist_sync_failed_index, state.failedIndex),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                )
            }
            if (state.error != null && state.pendingCount > 0) {
                Text(
                    text = stringResource(R.string.playlist_sync_pending_count, state.pendingCount),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                )
            }
            if (state.skippedCount > 0) {
                Text(
                    text = stringResource(R.string.playlist_sync_skipped_summary, state.skippedCount),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                )
                if (mode == PlaylistSyncDialogMode.PUSH) {
                    Text(
                        text = stringResource(
                            R.string.playlist_sync_skipped_detail,
                            state.skippedLocalCount,
                            state.skippedNetworkCount,
                            state.skippedLegacyCount,
                        ),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                    )
                }
            }
            if (state.containsUrlPlaceholder) {
                Text(
                    text = stringResource(R.string.playlist_sync_export_redacted),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                )
            }
            if (state.unresolvedCount > 0) {
                Text(
                    text = stringResource(R.string.playlist_sync_export_unresolved, state.unresolvedCount),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                )
            }
        }
    }
}

@Composable
fun playlistSyncSuccessMessage(state: PlaylistSyncUiState): String = when (state.operation) {
    PlaylistSyncViewModel.Operation.EXPORT_JSON,
    PlaylistSyncViewModel.Operation.EXPORT_M3U -> listOfNotNull(
        stringResource(R.string.playlist_sync_export_success, state.itemCount),
        if (state.unresolvedCount > 0) {
            stringResource(R.string.playlist_sync_export_unresolved, state.unresolvedCount)
        } else null,
        if (state.containsUrlPlaceholder) stringResource(R.string.playlist_sync_export_redacted) else null,
    ).joinToString("\n")
    PlaylistSyncViewModel.Operation.PULL -> stringResource(
        R.string.playlist_sync_pull_success,
        state.playlistName.orEmpty(),
        state.itemCount,
        state.skippedCount,
    )
    PlaylistSyncViewModel.Operation.PUSH -> stringResource(R.string.playlist_sync_push_success, state.addedCount, state.skippedCount)
    null -> ""
}

@Composable
fun playlistSyncErrorMessage(state: PlaylistSyncUiState): String {
    val reasonText = when (state.reason) {
        BackendPlaylistSync.FailureReason.BUSY -> stringResource(R.string.playlist_sync_busy_reason)
        BackendPlaylistSync.FailureReason.NOT_CONNECTED -> stringResource(R.string.playlist_sync_not_connected)
        BackendPlaylistSync.FailureReason.UNSUPPORTED -> stringResource(R.string.playlist_sync_unsupported)
        BackendPlaylistSync.FailureReason.SOURCE_MISMATCH -> stringResource(R.string.playlist_sync_source_mismatch)
        BackendPlaylistSync.FailureReason.LEGACY_UNBOUND -> stringResource(R.string.playlist_sync_legacy_unbound)
        BackendPlaylistSync.FailureReason.NO_STABLE_ID -> stringResource(R.string.playlist_sync_no_stable_id)
        BackendPlaylistSync.FailureReason.INVALID_STABLE_KEY -> stringResource(R.string.playlist_sync_invalid_stable_key)
        BackendPlaylistSync.FailureReason.NAME_CONFLICT -> stringResource(R.string.playlist_sync_name_conflict)
        BackendPlaylistSync.FailureReason.CHECKPOINT_MISMATCH -> stringResource(R.string.playlist_sync_checkpoint_mismatch)
        BackendPlaylistSync.FailureReason.INVALID_NAME -> stringResource(R.string.playlist_sync_invalid_name)
        BackendPlaylistSync.FailureReason.REMOTE_FAILURE -> stringResource(R.string.playlist_sync_server_error)
        BackendPlaylistSync.FailureReason.LOCAL_WRITE_FAILED -> stringResource(R.string.playlist_sync_local_write_failed)
        null -> null
    }
    val errorText = when (state.error) {
        BackendError.UNAUTHORIZED -> stringResource(R.string.playlist_sync_unauthorized)
        BackendError.FORBIDDEN -> stringResource(R.string.playlist_sync_forbidden)
        BackendError.NOT_FOUND -> stringResource(R.string.playlist_sync_not_found)
        BackendError.NETWORK, BackendError.TIMEOUT -> stringResource(R.string.playlist_sync_network_error)
        BackendError.SERVER -> stringResource(R.string.playlist_sync_server_error)
        BackendError.PROTOCOL -> stringResource(R.string.playlist_sync_protocol_error)
        BackendError.CONFLICT -> stringResource(R.string.playlist_sync_name_conflict)
        BackendError.UNSUPPORTED -> stringResource(R.string.playlist_sync_unsupported)
        BackendError.UNKNOWN -> stringResource(R.string.playlist_sync_unknown_error)
        null -> null
    }
    val prefix = if (state.canResume) stringResource(R.string.playlist_sync_partial_push_kept) + "\n" else ""
    return prefix + (reasonText ?: errorText ?: stringResource(R.string.playlist_sync_unknown_error))
}

private fun canRetry(state: PlaylistSyncUiState, mode: PlaylistSyncDialogMode): Boolean =
    state.error != null && state.reason !in setOf(
        BackendPlaylistSync.FailureReason.BUSY,
        BackendPlaylistSync.FailureReason.NOT_CONNECTED,
        BackendPlaylistSync.FailureReason.UNSUPPORTED,
        BackendPlaylistSync.FailureReason.SOURCE_MISMATCH,
        BackendPlaylistSync.FailureReason.LEGACY_UNBOUND,
        BackendPlaylistSync.FailureReason.NO_STABLE_ID,
        BackendPlaylistSync.FailureReason.INVALID_STABLE_KEY,
        BackendPlaylistSync.FailureReason.CHECKPOINT_MISMATCH,
        BackendPlaylistSync.FailureReason.INVALID_NAME,
        BackendPlaylistSync.FailureReason.LOCAL_WRITE_FAILED,
    ) && mode != PlaylistSyncDialogMode.EXPORT

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ColumnScope.EmptySyncList(text: String) {
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text = text, color = NasMusicColors.TextSecondary, fontSize = FontSize.button())
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncListRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    requestFocus: Boolean,
    onClick: () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.02f,
        containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.25f) else NasMusicColors.SurfaceVariant,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        requestFocusOnLaunch = requestFocus,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(text = title, color = LocalFocusableContentColor.current, fontSize = FontSize.button(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, color = LocalFocusableContentColor.current, fontSize = FontSize.small())
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncDialogButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    secondary: Boolean = false,
) {
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.02f,
        containerColor = when {
            selected -> NasMusicColors.Primary.copy(alpha = 0.25f)
            secondary -> NasMusicColors.SurfaceVariant
            else -> NasMusicColors.Primary
        },
        contentColor = if (secondary || selected) NasMusicColors.TextPrimary else NasMusicColors.OnPrimary,
        focusedContainerColor = NasMusicColors.Primary,
        focusedContentColor = NasMusicColors.OnPrimary,
    ) {
        Text(
            text = label,
            color = LocalFocusableContentColor.current,
            fontSize = FontSize.body(),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
