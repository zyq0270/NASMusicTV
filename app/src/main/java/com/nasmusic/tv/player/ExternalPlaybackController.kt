package com.nasmusic.tv.player

import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExternalPlaybackController(
    private val app: NasMusicApp,
    private val scope: CoroutineScope = app.applicationScope,
) {
    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _nasFavoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val nasFavoriteIds: StateFlow<Set<String>> = _nasFavoriteIds.asStateFlow()
    @Volatile
    private var nasFavoritesLoaded = false

    private val _networkFavoriteIds = MutableStateFlow<Set<String>>(emptySet())

    private val _favoriteChanges = MutableSharedFlow<Pair<Song, Boolean>>(extraBufferCapacity = 8)
    val favoriteChanges: SharedFlow<Pair<Song, Boolean>> = _favoriteChanges.asSharedFlow()

    private val favoriteMutex = Mutex()
    private var playModeInitialized = false
    var onPlayModeChanged: ((PlayMode) -> Unit)? = null

    private val widgetThrottle = com.nasmusic.tv.appwidget.WidgetUpdateThrottle(app, scope)

    init {
        scope.launch {
            app.appPreferences.networkFavorites.collect { favorites ->
                _networkFavoriteIds.value = favorites.mapTo(mutableSetOf()) { it.songId }
                requestWidgetUpdate()
            }
        }
        scope.launch {
            val adapter = app.backendRegistry.getAdapter() ?: return@launch
            val favorites = runCatching { adapter.getFavorites() }.getOrNull() ?: return@launch
            _nasFavoriteIds.value = favorites.mapTo(mutableSetOf()) { it.id }
            nasFavoritesLoaded = true
        }
    }

    fun initializePlayMode(defaultMode: PlayMode) {
        if (playModeInitialized) return
        playModeInitialized = true
        _playMode.value = defaultMode
        app.playerManager.applyPlayMode(defaultMode)
        onPlayModeChanged?.invoke(defaultMode)
        requestWidgetUpdate()
    }

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        app.playerManager.applyPlayMode(mode)
        onPlayModeChanged?.invoke(mode)
        scope.launch { app.appPreferences.setDefaultPlayMode(mode) }
        requestWidgetUpdate()
    }

    fun togglePlayMode() {
        val modes = PlayMode.entries
        setPlayMode(modes[(modes.indexOf(_playMode.value) + 1) % modes.size])
    }

    fun togglePlay() {
        app.playerManager.playPause()
        requestWidgetUpdate()
    }

    fun next() {
        app.playerManager.next(_playMode.value)
        requestWidgetUpdate()
    }

    fun previous() {
        app.playerManager.previous(_playMode.value)
        requestWidgetUpdate()
    }

    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) app.playerManager.sleepTimer.cancel() else app.playerManager.sleepTimer.start(minutes)
    }

    fun cycleSleepTimer() {
        val timer = app.playerManager.sleepTimer
        if (timer.isRunning()) timer.cancel() else timer.start(15)
        requestWidgetUpdate()
    }

    fun setNasFavoriteIds(ids: Set<String>) {
        _nasFavoriteIds.value = ids
        nasFavoritesLoaded = true
    }

    fun clearNasFavoriteIds() {
        _nasFavoriteIds.value = emptySet()
        nasFavoritesLoaded = false
    }

    fun updateNasFavorite(song: Song, favorite: Boolean) {
        _nasFavoriteIds.value = if (favorite) _nasFavoriteIds.value + song.id else _nasFavoriteIds.value - song.id
        requestWidgetUpdate()
    }

    suspend fun isFavorite(song: Song): Boolean = isFavoriteSync(song)

    fun isFavoriteSync(song: Song): Boolean = if (song.isNetworkSong || song.isLocalSong) {
        song.id in _networkFavoriteIds.value
    } else {
        song.id in _nasFavoriteIds.value
    }

    suspend fun toggleFavorite(song: Song): Boolean? {
        val favorite = favoriteMutex.withLock {
            try {
                if (song.isNetworkSong || song.isLocalSong) {
                    app.appPreferences.toggleNetworkFavorite(NetworkFavoriteItem(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        coverUrl = song.coverUrl,
                        networkSource = song.networkSource ?: "local",
                        networkId = song.networkId ?: song.id,
                        networkPlatform = song.networkPlatform,
                        storageType = song.storageType,
                        addedAtMs = System.currentTimeMillis(),
                    ))
                } else {
                    val adapter = app.backendRegistry.getAdapter() ?: return@withLock null
                    val current = if (nasFavoritesLoaded) {
                        _nasFavoriteIds.value.contains(song.id)
                    } else {
                        adapter.getFavorites().any { it.id == song.id }.also { found ->
                            if (found) _nasFavoriteIds.value = _nasFavoriteIds.value + song.id
                            nasFavoritesLoaded = true
                        }
                    }
                    if (!adapter.toggleFavorite(song.id, current)) return@withLock null
                    updateNasFavorite(song, !current)
                    !current
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("ExternalControl", "toggleFavorite failed", e)
                null
            }
        }
        // 单一真源：widget / 通知栏 / 系统媒体卡片三条入口都经这里广播，
        // 否则「我的收藏」列表只在 widget 路径下同步。
        if (favorite != null) _favoriteChanges.emit(song to favorite)
        requestWidgetUpdate()
        return favorite
    }

    fun handleExternalAction(action: String) {
        when (action) {
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_TOGGLE_PLAY -> togglePlay()
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_PREVIOUS -> previous()
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_NEXT -> next()
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_TOGGLE_MODE -> togglePlayMode()
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_CYCLE_SLEEP_TIMER -> cycleSleepTimer()
            com.nasmusic.tv.appwidget.RemotePlaybackWidget.ACTION_TOGGLE_FAVORITE -> {
                val song = app.playerManager.playerState.value.currentSong ?: return
                scope.launch { toggleFavorite(song) }
            }
        }
    }

    fun requestWidgetUpdate() {
        widgetThrottle.requestUpdate()
    }
}
