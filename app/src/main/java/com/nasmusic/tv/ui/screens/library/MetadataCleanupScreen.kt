package com.nasmusic.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.local.DuplicateMatch
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.pagePadding
import com.nasmusic.tv.ui.viewmodel.MetadataCleanupEntry
import com.nasmusic.tv.ui.viewmodel.MetadataCleanupUiState
import com.nasmusic.tv.ui.viewmodel.MetadataCleanupViewModel

private enum class MetadataField { TITLE, ARTIST, ALBUM, YEAR, GENRE }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetadataCleanupScreen(
    state: MetadataCleanupUiState,
    viewModel: MetadataCleanupViewModel,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var selectedTab by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<MetadataCleanupEntry?>(null) }
    var editField by remember { mutableStateOf<MetadataField?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NasMusicColors.Background)
            .padding(pagePadding()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.metadata_cleanup_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.display(),
                modifier = Modifier.weight(1f),
            )
            CleanupButton(stringResource(R.string.metadata_cleanup_close), onClose)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CleanupButton(
                stringResource(R.string.metadata_cleanup_tab_edit),
                { selectedTab = 0 },
                selected = selectedTab == 0,
            )
            CleanupButton(
                stringResource(R.string.metadata_cleanup_tab_duplicates),
                { selectedTab = 1 },
                selected = selectedTab == 1,
            )
        }
        Spacer(Modifier.height(16.dp))
        when {
            state.loading -> Text(
                text = stringResource(R.string.metadata_cleanup_loading),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
            )
            selectedTab == 0 -> EditList(
                state = state,
                onEdit = { entry, field ->
                    editTarget = entry
                    editField = field
                },
                onReset = viewModel::clearOverride,
            )
            else -> DuplicateList(
                state = state,
                onKeep = viewModel::keepDuplicate,
                onRestore = viewModel::restoreDuplicate,
            )
        }
    }

    val target = editTarget
    val field = editField
    if (target != null && field != null) {
        val override = state.overrides[target.stableKey]
        val initial = when (field) {
            MetadataField.TITLE -> override?.title ?: target.song.title
            MetadataField.ARTIST -> override?.artist ?: target.song.artist
            MetadataField.ALBUM -> override?.album ?: target.song.album
            MetadataField.YEAR -> (override?.year ?: target.song.year)?.toString().orEmpty()
            MetadataField.GENRE -> override?.genre ?: target.song.genre.orEmpty()
        }
        val title = stringResource(field.labelRes)
        TextInputDialog(
            title = title,
            hint = title,
            initialValue = initial,
            showQrCode = false,
            onConfirm = { value ->
                val currentOverride = override
                viewModel.saveOverride(
                    stableKey = target.stableKey,
                    title = if (field == MetadataField.TITLE) value else currentOverride?.title,
                    artist = if (field == MetadataField.ARTIST) value else currentOverride?.artist,
                    album = if (field == MetadataField.ALBUM) value else currentOverride?.album,
                    year = if (field == MetadataField.YEAR) value else currentOverride?.year?.toString(),
                    genre = if (field == MetadataField.GENRE) value else currentOverride?.genre,
                )
                editTarget = null
                editField = null
            },
            onDismiss = {
                editTarget = null
                editField = null
            },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EditList(
    state: MetadataCleanupUiState,
    onEdit: (MetadataCleanupEntry, MetadataField) -> Unit,
    onReset: (String) -> Unit,
) {
    if (state.songs.isEmpty()) {
        EmptyText(stringResource(R.string.metadata_cleanup_empty))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.songs, key = { it.stableKey }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NasMusicColors.Surface)
                    .padding(14.dp),
            ) {
                Text(
                    text = state.overrides[entry.stableKey]?.title ?: entry.song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                )
                Text(
                    text = state.overrides[entry.stableKey]?.artist ?: entry.song.artist,
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MetadataField.entries.forEach { field ->
                        CleanupButton(stringResource(field.labelRes), { onEdit(entry, field) })
                    }
                    CleanupButton(stringResource(R.string.metadata_cleanup_reset), { onReset(entry.stableKey) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DuplicateList(
    state: MetadataCleanupUiState,
    onKeep: (DuplicateMatch, Boolean) -> Unit,
    onRestore: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.duplicateMatches.isEmpty()) {
            item(key = "duplicates_empty") { EmptyText(stringResource(R.string.metadata_cleanup_no_duplicates)) }
        }
        items(state.duplicateMatches, key = { it.first.stableKey + "|" + it.second.stableKey }) { match ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NasMusicColors.Surface)
                    .padding(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.metadata_cleanup_score, match.score),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.body(),
                )
                Text(match.first.song.title, color = NasMusicColors.TextPrimary, fontSize = FontSize.subtitle())
                Text(match.second.song.title, color = NasMusicColors.TextSecondary, fontSize = FontSize.subtitle())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CleanupButton(stringResource(R.string.metadata_cleanup_keep_first), onClick = { onKeep(match, true) })
                    CleanupButton(stringResource(R.string.metadata_cleanup_keep_second), onClick = { onKeep(match, false) })
                }
            }
        }
        if (state.resolutions.isNotEmpty()) {
            item(key = "resolved_header") {
                Text(
                    text = stringResource(R.string.metadata_cleanup_resolved),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                )
            }
            items(state.resolutions.toList(), key = { it.first }) { (keepKey, resolution) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(NasMusicColors.Surface).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = keepKey,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                        modifier = Modifier.weight(1f),
                    )
                    CleanupButton(stringResource(R.string.metadata_cleanup_restore), onClick = { onRestore(keepKey) })
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = resolution.hideKeys.size.toString(),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, color = NasMusicColors.TextSecondary, fontSize = FontSize.body())
    }
}

@Composable
private fun CleanupButton(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.05f,
        containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
        contentColor = if (selected) NasMusicColors.OnPrimary else NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        focusedContentColor = NasMusicColors.Primary,
    ) {
        Text(
            text = text,
            color = LocalFocusableContentColor.current,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

private val MetadataField.labelRes: Int
    get() = when (this) {
        MetadataField.TITLE -> R.string.metadata_cleanup_field_title
        MetadataField.ARTIST -> R.string.metadata_cleanup_field_artist
        MetadataField.ALBUM -> R.string.metadata_cleanup_field_album
        MetadataField.YEAR -> R.string.metadata_cleanup_field_year
        MetadataField.GENRE -> R.string.metadata_cleanup_field_genre
    }
