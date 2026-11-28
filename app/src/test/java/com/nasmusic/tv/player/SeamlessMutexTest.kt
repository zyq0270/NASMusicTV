package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.SeamlessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessMutexTest {
    @Test fun `legacy setting migrates only when new mode is absent`() {
        assertEquals(SeamlessMode.CROSSFADE, SeamlessPolicy.migrate(null, true))
        assertEquals(SeamlessMode.OFF, SeamlessPolicy.migrate(null, false))
        assertEquals(SeamlessMode.OFF, SeamlessPolicy.migrate(SeamlessMode.OFF, true))
        assertEquals(SeamlessMode.GAPLESS, SeamlessPolicy.migrate(SeamlessMode.GAPLESS, true))
    }

    @Test fun `gapless and crossfade are mutually exclusive`() {
        assertFalse(SeamlessPolicy.crossfadeEnabled(SeamlessMode.GAPLESS, true, 4))
        assertTrue(SeamlessPolicy.crossfadeEnabled(SeamlessMode.CROSSFADE, false, 4))
        assertTrue(SeamlessPolicy.crossfadeEnabled(SeamlessMode.OFF, true, 4))
        assertFalse(SeamlessPolicy.crossfadeEnabled(SeamlessMode.CROSSFADE, false, 0))
    }

    @Test fun `gapless probe gate stays mode based`() {
        assertTrue(SeamlessPolicy.shouldProbeGapless(SeamlessMode.GAPLESS))
        assertFalse(SeamlessPolicy.shouldProbeGapless(SeamlessMode.CROSSFADE))
        assertFalse(SeamlessPolicy.shouldProbeGapless(SeamlessMode.OFF))
        assertEquals(200L, SeamlessPolicy.SHORT_FADE_MS)
    }
}
