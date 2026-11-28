package com.nasmusic.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.nasmusic.tv.R
import com.nasmusic.tv.data.cache.CacheLimitKind
import com.nasmusic.tv.data.cache.CacheLimits
import com.nasmusic.tv.ui.screens.TextInputDialog
import kotlinx.coroutines.launch

/**
 * 缓存数量上限的输入行。
 *
 * 独立文件：本行由 `CacheSettingsSection` 在 `SettingsGroupCard` 内调用，
 * 但 `SettingsGroupCoverageTest` 只按「调用点是否在同文件卡片大括号内」判 ——
 * 分区文件里的私有行渲染器会被误判，故与分区文件分开存放。
 */
@Composable
internal fun CacheLimitInputRow(
    kind: CacheLimitKind,
    current: Int,
    onSave: suspend (CacheLimitKind, Int) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val labelRes = when (kind) {
        CacheLimitKind.LYRICS_ENTRIES -> R.string.settings_cache_limit_lyrics
        CacheLimitKind.COVER_URL_ENTRIES -> R.string.settings_cache_limit_cover_urls
        CacheLimitKind.PLAY_URL_ENTRIES -> R.string.settings_cache_limit_play_urls
    }
    val label = stringResource(labelRes)
    SettingActionButton(
        label = label,
        description = stringResource(R.string.settings_cache_limit_value, current),
        onClick = {
            error = null
            showDialog = true
        }
    )
    if (showDialog) {
        val range = CacheLimits.RANGES.getValue(kind)
        val invalidMessage = stringResource(R.string.settings_cache_limit_invalid, range.first, range.last)
        val saveFailedMessage = stringResource(R.string.settings_cache_limit_save_failed)
        TextInputDialog(
            title = label,
            hint = label,
            initialValue = current.toString(),
            onConfirm = { raw ->
                val parsed = parseCacheLimitInput(kind, raw)
                parsed.fold(
                    onSuccess = { value ->
                        scope.launch {
                            if (onSave(kind, value)) showDialog = false
                            else error = saveFailedMessage
                        }
                    },
                    onFailure = {
                        error = invalidMessage
                    }
                )
            },
            onDismiss = { showDialog = false },
            keyboardType = KeyboardType.Number,
            errorText = error,
            showQrCode = false,
        )
    }
}