package com.nasmusic.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayGainMathTest {
    @Test fun `negative gain is exact attenuation and no tag is unity`() {
        assertEquals(0.25118864f, ReplayGainController.replayGainLinear(-12f), 0.00001f)
        assertEquals(0.50118723f, ReplayGainController.replayGainLinear(-6f), 0.00001f)
        assertEquals(1f, ReplayGainController.replayGainLinear(0f), 0f)
        assertEquals(1f, ReplayGainController.replayGainLinear(Float.NaN), 0f)
        assertEquals(1f, ReplayGainController.replayGainLinear(30f), 0f)
    }

    @Test fun `positive gain stays unity instead of clamp pretending normalization`() {
        assertEquals(1f, ReplayGainController.replayGainLinear(6f), 0f)
        assertEquals(1f, ReplayGainController.replayGainLinear(24f), 0f)
    }

    @Test fun `app volume and fade multiply before output clamp`() {
        assertEquals(0f, ReplayGainController.volume(0.5f, 1f, 0f), 0f)
        assertEquals(0.25f, ReplayGainController.volume(0.5f, 0.5f, 1f), 0f)
        assertEquals(1f, ReplayGainController.volume(1f, 1f, 2f), 0f)
        assertFalse(ReplayGainController.volume(Float.NaN, 1f, 1f) > 1f)
    }
}
