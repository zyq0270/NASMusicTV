package com.nasmusic.tv.backend.network.lx

import java.util.concurrent.ConcurrentHashMap

/**
 * 洛雪路由健康状态（方案 §5.5）。
 *
 * ⛔ **两级状态，粒度不能合并**：
 * - 脚本级 `Broken`（键 = `scriptId`）：加载失败 / JS 抛错 / runtime 崩 ⇒ 该脚本**所有平台**停用。
 * - 平台级 `Degraded`（键 = `(scriptId, source)`）：该平台连败 3 次 ⇒ **只停该平台**。
 *
 * 只按 `scriptId` 记是这里最容易犯的错：用户连播三首网易失败，就会把整份脚本打成
 * `Degraded`，而酷狗（同一份脚本最强的平台）也跟着停 5 分钟。用一个平台的表现否定整份脚本，
 * 直接违背「洛雪优先」。
 *
 * 两个状态**互不覆盖**：`markScriptOk` 不清平台降级，`markSuccess` 不清脚本级 Broken。
 */
internal class LxSourceRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val brokenUntil = ConcurrentHashMap<String, Long>()
    private val degradedUntil = ConcurrentHashMap<Key, Long>()
    private val failureStreak = ConcurrentHashMap<Key, Int>()
    private val probing = ConcurrentHashMap<String, Boolean>()

    private data class Key(val scriptId: String, val source: String)

    // ─────────────────────────── 脚本级 ───────────────────────────

    fun isBroken(scriptId: String): Boolean {
        val until = brokenUntil[scriptId] ?: return false
        if (clock() < until) return true
        brokenUntil.remove(scriptId, until)
        return false
    }

    /** 脚本挂了（load 失败 / JS 异常 / runtime 崩）：整份脚本停 [COOLDOWN_MS] */
    fun markBroken(scriptId: String) {
        brokenUntil[scriptId] = clock() + COOLDOWN_MS
    }

    /** 脚本恢复了（重新 load 成功）：只清脚本级，⛔ 不动平台级 */
    fun markScriptOk(scriptId: String) {
        brokenUntil.remove(scriptId)
    }

    // ─────────────────────────── 平台级 ───────────────────────────

    fun isHealthy(scriptId: String, source: String): Boolean {
        val key = Key(scriptId, source)
        val until = degradedUntil[key] ?: return true
        if (clock() < until) return false
        // 到期：自动恢复**一次**尝试；失败会重新累计到 3 次才再降级
        degradedUntil.remove(key, until)
        failureStreak.remove(key)
        return true
    }

    /** 该平台取链失败：连续第 [FAIL_THRESHOLD] 次进 `Degraded` */
    fun markFailure(scriptId: String, source: String) {
        val key = Key(scriptId, source)
        val streak = (failureStreak[key] ?: 0) + 1
        failureStreak[key] = streak
        if (streak >= FAIL_THRESHOLD) {
            degradedUntil[key] = clock() + COOLDOWN_MS
        }
    }

    /** 该平台取链成功：清计数与降级（只影响这一个平台） */
    fun markSuccess(scriptId: String, source: String) {
        val key = Key(scriptId, source)
        failureStreak.remove(key)
        degradedUntil.remove(key)
    }

    fun currentStreak(scriptId: String, source: String): Int =
        failureStreak[Key(scriptId, source)] ?: 0

    // ─────────────────────────── 验证标记 ───────────────────────────

    /**
     * 验证进行中：该脚本的所有取链**立刻返回 null**，让 enhancer 马上回退 Meting。
     *
     * 理由：验证与播放共用同一个 host（内部单线程串行），不这样处理就是
     * 「用户按了播放却不出声」（方案 §5.4.1.7 第 3 条）。
     * ⛔ 这个标记**不改** `Degraded`——验证失败不等于源坏了。
     */
    fun setProbing(scriptId: String, value: Boolean) {
        if (value) probing[scriptId] = true else probing.remove(scriptId)
    }

    fun isProbing(scriptId: String): Boolean = probing[scriptId] == true

    // ─────────────────────────── 统一门禁 ───────────────────────────

    /**
     * 能否用 `(scriptId, source)` 取链。
     *
     * ⚠️ 这里**不得**出现「某平台不走洛雪」之类的分支：路由规则必须与平台无关，
     * 否则等于把「当前这批源的质量」固化成硬编码（方案 §7.9）。
     */
    fun tryAcquire(scriptId: String, source: String): Boolean {
        if (isProbing(scriptId)) return false
        if (isBroken(scriptId)) return false
        return isHealthy(scriptId, source)
    }

    private companion object {
        /** 连败阈值（方案 §5.5：连续 3 次） */
        const val FAIL_THRESHOLD = 3

        /** 降级冷却（方案 §5.5：5 分钟） */
        const val COOLDOWN_MS = 5 * 60 * 1000L
    }
}
