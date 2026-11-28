package com.nasmusic.tv.backend.playlist

import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendError
import com.nasmusic.tv.backend.backendErrorFromHttpStatus
import com.nasmusic.tv.backend.subsonicBackendError
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendPlaylistErrorTest {
    @Test
    fun `http statuses map to distinct errors`() {
        assertEquals(BackendError.UNAUTHORIZED, backendErrorFromHttpStatus(401))
        assertEquals(BackendError.FORBIDDEN, backendErrorFromHttpStatus(403))
        assertEquals(BackendError.NOT_FOUND, backendErrorFromHttpStatus(404))
        assertEquals(BackendError.CONFLICT, backendErrorFromHttpStatus(409))
        assertEquals(BackendError.SERVER, backendErrorFromHttpStatus(500))
    }

    @Test
    fun `network timeout and subsonic protocol errors remain distinguishable`() {
        val json = JsonParser.parseString(
            """{"subsonic-response":{"status":"failed","code":40,"message":"auth"}}"""
        ).asJsonObject
        assertEquals(BackendError.UNAUTHORIZED, json.subsonicBackendError())
        assertEquals(BackendError.TIMEOUT, com.nasmusic.tv.backend.backendErrorFromThrowable(SocketTimeoutException()))
        assertEquals(BackendError.NETWORK, com.nasmusic.tv.backend.backendErrorFromThrowable(IOException()))
    }
}
