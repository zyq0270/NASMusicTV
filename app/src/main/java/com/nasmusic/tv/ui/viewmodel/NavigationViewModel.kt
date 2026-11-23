package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.ui.screens.settings.SettingsSection

/**
 * 导航域 ViewModel（R-1 拆分自 MainViewModel）：
 * 当前屏幕状态与导航。
 *
 * 注意：详情页导航（AlbumDetail/ArtistDetail）携带选中数据，仍经 LibraryEvent
 * 路由后由 MainViewModel 联动设置——本类只持有纯导航状态。
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    // --- 导航状态 ---
    private val _currentScreen = MutableStateFlow(Screen.Home)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    /**
     * 2026-11-22 队列页修复 P2：记录进入队列页的来源，BACK 时回到来源页。
     *
     * 放在 navigateTo 内部拦截而不是改 3 个 Branch 调用点：顶部导航也走同一个
     * navigateTo，改调用点会漏掉 TV 顶栏 / 手机底栏。仅「从非 Queue 进入 Queue」
     * 才覆盖来源，页面内重复点队列 Tab 不会把来源改成自己。
     */
    private val _queueOrigin = MutableStateFlow<Screen?>(null)
    val queueOrigin: StateFlow<Screen?> = _queueOrigin.asStateFlow()

    /**
     * G7-01：详情页来源。专辑/艺人详情有两个**非曲库**入口（首页「最近添加」卡、
     * 播放页点歌手名），BACK 写死回曲库会把这两处的用户弹到曲库。
     * 与 [queueOrigin] 同款：在 navigateTo 内部拦截，覆盖所有调用点；
     * 详情页之间互跳不覆盖来源。
     */
    private val _detailOrigin = MutableStateFlow<Screen?>(null)
    val detailOrigin: StateFlow<Screen?> = _detailOrigin.asStateFlow()

    fun navigateTo(screen: Screen) {
        if (screen == Screen.Queue && _currentScreen.value != Screen.Queue) {
            _queueOrigin.value = _currentScreen.value
        }
        val fromDetail = _currentScreen.value == Screen.AlbumDetail ||
            _currentScreen.value == Screen.ArtistDetail
        if ((screen == Screen.AlbumDetail || screen == Screen.ArtistDetail) && !fromDetail) {
            _detailOrigin.value = _currentScreen.value
        }
        _currentScreen.value = screen
    }

    /** BACK 消费完来源后清空，避免残留影响下一次进入 */
    fun clearDetailOrigin() {
        _detailOrigin.value = null
    }

    /** 队列页 BACK 消费完来源后清空，避免残留影响下一次进入 */
    fun clearQueueOrigin() {
        _queueOrigin.value = null
    }

    // =====================================================================
    // v2.36.0 竖屏设置两级页（方案 §6.2 / §8.7 / K2）
    //
    // ⚠️ 为什么放在这里而不是 SettingsScreen 的局部 remember：
    // `AppRoot` 的 BACK handler 需要读它才能实现「二级页 BACK → 回设置列表」。
    // 页面级 BACK 状态必须归 navVM（与 currentScreen 同构），且要加进 AppRoot
    // 那个 LaunchedEffect 的 key 列表，否则 handler 不会随分区切换刷新。
    // =====================================================================

    /** 竖屏设置页当前进入的分区；`null` = 停留在设置主页（一级） */
    private val _settingsSection = MutableStateFlow<SettingsSection?>(null)
    val settingsSection: StateFlow<SettingsSection?> = _settingsSection.asStateFlow()

    /** 进入某个设置分区（竖屏二级页） */
    fun openSettingsSection(section: SettingsSection) {
        _settingsSection.value = section
    }

    /** 关闭设置分区，回到设置主页 */
    fun closeSettingsSection() {
        _settingsSection.value = null
    }
}
