package com.nasmusic.tv.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.cancel

/**
 * 子 ViewModel 的统一收尾。
 *
 * ## 为什么需要
 *
 * `MainViewModel` 里的 15 个子 ViewModel 是**直接 new 出来的普通属性**，没有经过
 * `ViewModelProvider` ⇒ 不在任何 `ViewModelStore` 里 ⇒ 框架**永远不会调用它们的
 * `onCleared()`**，`viewModelScope` 也就永不取消。后果是每个子 VM 在 `init` / `stateIn`
 * 里起的无限 collect（下载进度、播放状态、设置流、搜索状态…）在 Activity 结束后继续挂着，
 * 每开一次界面就多一份。
 *
 * ## 为什么在 MainViewModel 里驱动
 *
 * MainViewModel 自己是**唯一**被框架托管的那个（`MainActivity` 只把它放进 store），
 * 所以它的 `onCleared()` 是唯一可靠的收尾时机。
 *
 * ⚠️ 只取消协程作用域，不动业务对象（下载任务、播放器都在 app 级管理器里，
 * 与 UI 生命周期无关，取消 UI 侧的收集器不会中断它们）。
 */
internal fun disposeChildViewModels(vararg viewModels: ViewModel) {
    for (vm in viewModels) {
        val name = vm.javaClass.simpleName
        // 子 VM 若自己实现了 dispose()（如 VisualizerViewModel 要放 ORT session），
        // 由调用方先行调用；这里只兜底取消作用域。
        runCatching { vm.viewModelScope.cancel() }
            .onFailure { AppLog.w("MainViewModel", "取消 $name 的 viewModelScope 失败", it) }
    }
}
