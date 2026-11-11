package com.nasmusic.tv.util

import android.content.Context
import android.widget.Toast
import com.nasmusic.tv.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「本机放不了 FLAC ⇒ 音质档位已自动降级」的**一次性**提示（2026-11-11）
 *
 * ## 为什么需要它
 *
 * FLAC 在部分老电视上有**两条**独立的失败路径，各自需要不同的告知：
 *
 * | 路径 | 现象 | 告知方式 |
 * |---|---|---|
 * | **① 直链是 FLAC**（NAS 直连源库） | 播放时命中解封装熔断 ⇒ **整曲跳过** | `PlayerManager` 的 FLAC 分支弹 Toast（说清「为什么 + 怎么办」+ 跳下一首） |
 * | **② 档位选了「无损」**（网络音乐源） | 档位在**请求直链前**就被降级成 320kbps ⇒ **不报错、也不跳过** | ⛔ **原来完全静默** ⇒ 用户只看到「音质怎么变差了」而不知道原因 |
 *
 * ⇒ 本类负责路径 ② 的告知：**只在「用户确实选了无损档、而本机放不了」时**弹一次
 * （用户没选无损 ⇒ 不弹；本机能放 ⇒ 不弹；已弹过 ⇒ 不再弹）。
 *
 * ⛔ **只弹一次**（进程级 `AtomicBoolean`）：这个 provider 每次解析直链都会调用，
 * 不做一次性保护会变成刷屏。
 *
 * ## ⚠️ 单测为什么不能直接调 `showIfDowngraded`
 *
 * 本仓**没开** `testOptions.unitTests.isIncludeAndroidResources`
 * ⇒ 单测环境里 `context.getString(R.string.x)` 会抛 `Resources$NotFoundException`
 * （仓内既有说明见 `BackupLocalImportRecordTest` / `DeviceDiagnosticsTest` 的注释）。
 * ⇒ 文案通过 [text] 参数**注入**：生产路径不传（走资源），单测传字面量。
 */
object FlacQualityNotice {

    /** 本进程是否已提示过（线程安全） */
    private val shown = AtomicBoolean(false)

    /** 实际弹出的次数（**单测用**：断言「只弹一次」） */
    internal var showCount: Int = 0
        private set

    /**
     * 若「请求的档位」因设备能力被降级 ⇒ 弹一次提示。
     *
     * @param requestedTier 用户请求的档位（原始值）
     * @param clampedTier 实际使用的档位（降级后）
     * @param text 提示文案；**`null` = 从资源取**（生产路径）。单测传字面量，
     *   绕开「单测环境没有 app 资源」的限制（见类注释）。
     */
    fun showIfDowngraded(
        context: Context,
        requestedTier: Int,
        clampedTier: Int,
        text: CharSequence? = null,
    ) {
        if (requestedTier == clampedTier) return
        if (!shown.compareAndSet(false, true)) return
        showCount++
        val msg = text ?: context.getString(R.string.player_flac_quality_downgraded)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /** 仅供单测：重置「已提示」标记与计数 */
    internal fun resetForTest() {
        shown.set(false)
        showCount = 0
    }
}
