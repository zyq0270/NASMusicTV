package com.nasmusic.tv.backend.download

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadRootResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `低空间外部卷仍参与扫描但不能成为新下载写入根`() {
        val internal = temp.newFolder("internal")
        val external = temp.newFolder("external")
        val candidate = DownloadRootResolver.ExternalRootCandidate(
            root = external,
            mounted = true,
            readable = true,
            writable = true,
            usableSpace = 512L * 1024 * 1024 - 1L,
        )

        assertEquals(
            "旧下载仍在低空间外部卷，扫描不能把它过滤掉",
            listOf(internal, external),
            DownloadRootResolver.resolveScanRoots(internal, "CUSTOM", candidate),
        )
        assertEquals(
            "新下载不得选择低空间外部卷，由 currentRoot 回退内置",
            null,
            DownloadRootResolver.resolveWriteRoot(internal, "CUSTOM", candidate),
        )
    }

    @Test
    fun `外部卷不可写时仍可扫描旧下载但不能写入`() {
        val internal = temp.newFolder("internal")
        val external = temp.newFolder("external")
        val candidate = DownloadRootResolver.ExternalRootCandidate(
            root = external,
            mounted = true,
            readable = true,
            writable = false,
            usableSpace = 1024L * 1024 * 1024,
        )

        assertEquals(
            listOf(internal, external),
            DownloadRootResolver.resolveScanRoots(internal, "CUSTOM", candidate),
        )
        assertEquals(null, DownloadRootResolver.resolveWriteRoot(internal, "CUSTOM", candidate))
    }

    @Test
    fun `未挂载或不可读外部卷既不扫描也不写入`() {
        val internal = temp.newFolder("internal")
        val external = temp.newFolder("external")
        val base = DownloadRootResolver.ExternalRootCandidate(
            root = external,
            mounted = true,
            readable = true,
            writable = true,
            usableSpace = 1024L * 1024 * 1024,
        )

        listOf(base.copy(mounted = false), base.copy(readable = false)).forEach { candidate ->
            assertEquals(listOf(internal), DownloadRootResolver.resolveScanRoots(internal, "CUSTOM", candidate))
            assertEquals(null, DownloadRootResolver.resolveWriteRoot(internal, "CUSTOM", candidate))
        }
    }
}
