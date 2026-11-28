package com.nasmusic.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Architecture gate: main source may write ExoPlayer.volume only in ReplayGainController. */
class ReplayGainGateTest {
    @Test fun `all main volume writes are owned by controller`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("repository root not found")
        val violations = File(root, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line ->
                val normalized = line.substringBefore("//")
                val write = Regex("""\.volume\s*=|\.setVolume\s*\(""").containsMatchIn(normalized)
                if (write && file.name != "ReplayGainController.kt") "${file.path}:${index + 1}:${line.trim()}" else null
            } }
            .toList()
        assertTrue(violations.toString(), violations.isEmpty())
        val ownWrites = File(root, "app/src/main/java/com/nasmusic/tv/player/ReplayGainController.kt").readText()
        assertEquals(1, Regex("""\.volume\s*=""").findAll(ownWrites).count())
    }
}
