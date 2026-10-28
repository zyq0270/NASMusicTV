package com.nasmusic.tv.backend.network.lx

import java.io.Closeable

/**
 * 一个已安装脚本的取链能力（[LxScriptHost] 的抽象）。
 *
 * 存在的唯一理由：**播放增强（[LxPlayUrlEnhancer]）与验证（`LxProbeRunner`）都要能注入假 host**。
 * 真实实现里装着 QuickJS runtime，两者若直接依赖具体类，所有相关单测都会变成「必须有真机」。
 */
internal interface LxHost : Closeable {

    /** `load()` 之后才有值；null = 未加载 */
    val info: LxScriptInfo?

    suspend fun load(): LxScriptInfo

    /** @return 直链；脚本报错 / 超时 / 空串一律 null */
    suspend fun getMusicUrl(source: String, musicInfo: LxMusicInfo, quality: String): String?

    /** 播放抢占：取消在途请求，等待中的取链立刻返回 null */
    fun cancelPending()
}
