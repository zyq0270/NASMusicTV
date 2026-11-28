package com.nasmusic.tv.backend.playlist

import com.nasmusic.tv.data.prefs.RoadmapPreferences
import kotlinx.coroutines.flow.first

class PersistentPlaylistPushCheckpointStore(
    private val prefs: RoadmapPreferences,
) : PlaylistPushCheckpointStore {
    override suspend fun load(key: String): PlaylistPushCheckpoint? =
        prefs.playlistPushCheckpointsFlow().first()[key]

    override suspend fun save(key: String, checkpoint: PlaylistPushCheckpoint) {
        prefs.putPlaylistPushCheckpoint(key, checkpoint)
    }

    override suspend fun clear(key: String) {
        prefs.removePlaylistPushCheckpoint(key)
    }
}
