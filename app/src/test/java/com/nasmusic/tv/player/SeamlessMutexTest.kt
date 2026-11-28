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

    @Test fun `unsupported gapless only requests 200ms short fade`() {
        assertTrue(SeamlessPolicy.shortFadeNeeded(SeamlessMode.GAPLESS, false))
        assertFalse(SeamlessPolicy.shortFadeNeeded(SeamlessMode.GAPLESS, true))
        assertFalse(SeamlessPolicy.shortFadeNeeded(SeamlessMode.CROSSFADE, false))
        assertEquals(200L, SeamlessPolicy.SHORT_FADE_MS)
        assertTrue(SeamlessPolicy.nativeGaplessReady(SeamlessMode.GAPLESS, true, true))
        assertFalse(SeamlessPolicy.nativeGaplessReady(SeamlessMode.GAPLESS, true, false))
    }
}
