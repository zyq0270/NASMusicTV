package com.nasmusic.tv.data.prefs

/**
 * R-4 历史记录域子 Prefs（搜索历史/播放记录/最近播放；键不迁移）。
 */
class HistoryPrefs internal constructor(private val prefs: AppPreferences) {

    val searchHistory = prefs.searchHistory
    val recentSongIds = prefs.recentSongIds
    val playCounts = prefs.playCounts

    suspend fun recordSearch(query: String) = prefs.recordSearch(query)
    suspend fun recordPlayWithSong(song: com.nasmusic.tv.data.model.Song) = prefs.recordPlayWithSong(song)
    suspend fun addPlayRecord(record: com.nasmusic.tv.data.model.PlayRecord) = prefs.addPlayRecord(record)
    suspend fun getPlayRecords(): List<com.nasmusic.tv.data.model.PlayRecord> = prefs.getPlayRecords()
    suspend fun clearPlayRecords() = prefs.clearPlayRecords()
    suspend fun getRecentSongObjects(): List<com.nasmusic.tv.data.model.Song> = prefs.getRecentSongObjects()
    suspend fun purgeExpiredSearchHistory() = prefs.purgeExpiredSearchHistory()

    // 快捷选择统计（Quick Dial，按类别分桶）
    val quickPickStats = prefs.quickPickStats
    suspend fun getQuickPickStats(): List<com.nasmusic.tv.data.model.QuickPickStat> = prefs.getQuickPickStats()
    suspend fun getQuickPickStats(category: String): List<com.nasmusic.tv.data.model.QuickPickStat> =
        prefs.getQuickPickStats(category)
    suspend fun recordQuickPick(category: String, value: String) = prefs.recordQuickPick(category, value)
    suspend fun clearQuickPickStats(category: String? = null) = prefs.clearQuickPickStats(category)

    // 网络收藏（LRU 500）
    val networkFavorites = prefs.networkFavorites
    suspend fun toggleNetworkFavorite(item: com.nasmusic.tv.data.model.NetworkFavoriteItem) =
        prefs.toggleNetworkFavorite(item)
}
