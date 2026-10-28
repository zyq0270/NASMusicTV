package com.nasmusic.tv.backend.network.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10：两级健康状态（方案 §5.5）。
 *
 * 核心断言是**粒度**：一个平台连败不能牵连同一脚本的其它平台；
 * 脚本级 `Broken` 与平台级 `Degraded` 互不覆盖。
 */
class LxSourceRegistryTest {

    private var now = 0L
    private val registry = LxSourceRegistry { now }

    private val cooldown = 5 * 60 * 1000L

    @Test
    fun thirdConsecutiveFailureDegradesOnlyThatSource() {
        repeat(2) { registry.markFailure("s1", "wy") }
        assertTrue("两次失败不该降级", registry.isHealthy("s1", "wy"))
        assertEquals(2, registry.currentStreak("s1", "wy"))
        assertTrue("还没到阈值，必须仍可取链", registry.tryAcquire("s1", "wy"))

        registry.markFailure("s1", "wy")
        assertTrue("三次失败必须降级", !registry.isHealthy("s1", "wy"))
        // ⛔ 同一脚本的酷狗不能跟着停
        assertTrue("其它平台必须保持健康", registry.isHealthy("s1", "kg"))
        assertTrue(registry.tryAcquire("s1", "kg"))
    }

    @Test
    fun successResetsTheStreak() {
        registry.markFailure("s1", "wy")
        registry.markFailure("s1", "wy")
        registry.markSuccess("s1", "wy")
        assertEquals(0, registry.currentStreak("s1", "wy"))
        // 重新从 1 开始累计，不该直接降级
        registry.markFailure("s1", "wy")
        assertTrue(registry.isHealthy("s1", "wy"))
    }

    @Test
    fun degradationExpiresAndRecoversExactlyOnce() {
        repeat(3) { registry.markFailure("s1", "wy") }
        assertFalse(registry.isHealthy("s1", "wy"))

        now += cooldown - 1
        assertFalse("冷却未满必须继续停用", registry.isHealthy("s1", "wy"))

        now += 1
        assertTrue("冷却到期恢复一次尝试", registry.isHealthy("s1", "wy"))
        assertEquals("恢复时计数必须清零", 0, registry.currentStreak("s1", "wy"))

        // 恢复后单次失败不该立刻再降级（要重新攒够 3 次）
        registry.markFailure("s1", "wy")
        assertTrue(registry.isHealthy("s1", "wy"))
    }

    @Test
    fun brokenScriptBlocksEverySource() {
        registry.markBroken("s1")
        assertTrue(registry.isBroken("s1"))
        for (source in LxPlatformMap.lxSources) {
            assertFalse("脚本级 Broken 必须停用 $source", registry.tryAcquire("s1", source))
        }
        // 其它脚本不受影响
        assertTrue(registry.tryAcquire("s2", "wy"))
    }

    @Test
    fun brokenExpiresByItself() {
        registry.markBroken("s1")
        now += cooldown
        assertFalse(registry.isBroken("s1"))
        assertTrue(registry.tryAcquire("s1", "wy"))
    }

    @Test
    fun scriptOkDoesNotClearPlatformDegradation() {
        repeat(3) { registry.markFailure("s1", "wy") }
        registry.markScriptOk("s1")
        assertFalse("markScriptOk 只清脚本级", registry.isHealthy("s1", "wy"))
    }

    @Test
    fun platformSuccessDoesNotClearBrokenScript() {
        registry.markBroken("s1")
        registry.markSuccess("s1", "wy")
        assertTrue("markSuccess 只清平台级", registry.isBroken("s1"))
        assertFalse(registry.tryAcquire("s1", "wy"))
    }

    @Test
    fun probingBlocksEverythingWithoutTouchingHealth() {
        registry.setProbing("s1", true)
        assertFalse("验证进行中必须让播放立刻回退", registry.tryAcquire("s1", "wy"))
        assertFalse(registry.tryAcquire("s1", "kg"))
        // 验证失败不等于源坏了
        assertTrue(registry.isHealthy("s1", "wy"))
        assertEquals(0, registry.currentStreak("s1", "wy"))

        registry.setProbing("s1", false)
        assertTrue(registry.tryAcquire("s1", "wy"))
    }

    @Test
    fun probingDoesNotOverrideBroken() {
        registry.markBroken("s1")
        registry.setProbing("s1", false)
        assertFalse(registry.tryAcquire("s1", "wy"))
    }

    @Test
    fun sourcesOfDifferentScriptsAreIsolated() {
        repeat(3) { registry.markFailure("s1", "wy") }
        assertTrue("同平台不同脚本互不相干", registry.isHealthy("s2", "wy"))
        assertTrue(registry.tryAcquire("s2", "wy"))
    }
}
