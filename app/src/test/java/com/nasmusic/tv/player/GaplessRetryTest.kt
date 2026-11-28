package com.nasmusic.tv.player

import android.content.Context
import com.nasmusic.tv.backend.local.GaplessProbeResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-P06：只有可恢复的 probe_failed 允许在 network restored 后重探；
 * unconfirmed_source 等确定性结论整场会话保持缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GaplessRetryTest {
    private fun newManager(): PlayerManager {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        return PlayerManager(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun gaplessResults(pm: PlayerManager): MutableMap<String, GaplessProbeResult> {
        val field = PlayerManager::class.java.getDeclaredField("gaplessResults")
        field.isAccessible = true
        return field.get(pm) as MutableMap<String, GaplessProbeResult>
    }

    @Test
    fun `network restore clears only retryable probe failures`() {
        val pm = newManager()
        val results = gaplessResults(pm)
        results["retryable"] = GaplessProbeResult(false, reason = "probe_failed")
        results["unconfirmed"] = GaplessProbeResult(false, reason = "unconfirmed_source")
        results["supported"] = GaplessProbeResult(true)
        results["deterministic"] = GaplessProbeResult(false, reason = "no_xing")

        pm.onNetworkRestored()

        assertFalse(results.containsKey("retryable"))
        assertTrue(results.containsKey("unconfirmed"))
        assertTrue(results.containsKey("supported"))
        assertTrue(results.containsKey("deterministic"))
    }

    @Test
    fun `network loss alone keeps cached failures until restore`() {
        val pm = newManager()
        val results = gaplessResults(pm)
        results["retryable"] = GaplessProbeResult(false, reason = "probe_failed")

        pm.onNetworkGone()
        assertTrue(results.containsKey("retryable"))

        pm.onNetworkRestored()
        assertFalse(results.containsKey("retryable"))
    }
}
