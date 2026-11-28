package com.nasmusic.tv.backend.local

import android.content.Context
import com.nasmusic.tv.backend.network.baidu.BaiduHttpDataSourceFactory
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongSourceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only bounded header reads are allowed through this interface. */
data class AudioHeaderRequest(
    val song: Song,
    val source: SongSourceIdentity,
    val uri: String,
    val mimeOrExt: String?,
)

data class AudioHeaderResponse(
    val stream: InputStream,
    val statusCode: Int?,
    val supportsRange: Boolean,
) : Closeable {
    override fun close() = stream.close()
}

interface AudioHeaderSource {
    suspend fun open(request: AudioHeaderRequest): AudioHeaderResponse
}

/**
 * Header source on the existing authenticated HTTP chain (same OkHttp interceptor/client as
 * playback), or ContentResolver for local content URIs. No unauthenticated URL.openStream().
 * HTTP servers must answer Range with 206; callers enforce the total byte budget as well.
 */
class Media3AudioHeaderSource(context: Context) : AudioHeaderSource {
    private val appContext = context.applicationContext
    private val httpClient = BaiduHttpDataSourceFactory.createOkHttpClientForCoil()
        .newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun open(request: AudioHeaderRequest): AudioHeaderResponse = withContext(Dispatchers.IO) {
        val uri = android.net.Uri.parse(request.uri)
        if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            openHttp(request)
        } else {
            val stream = when (uri.scheme?.lowercase()) {
                "file" -> java.io.File(uri.path ?: throw IOException("Missing file path")).inputStream()
                else -> appContext.contentResolver.openInputStream(uri)
            } ?: throw IOException("Cannot open local audio")
            AudioHeaderResponse(stream, null, supportsRange = true)
        }
    }

    private suspend fun openHttp(request: AudioHeaderRequest): AudioHeaderResponse =
        suspendCancellableCoroutine { continuation ->
            val httpRequest = Request.Builder()
                .url(request.uri)
                .header("Range", "bytes=0-${DEFAULT_HEADER_BUDGET - 1}")
                .header("Accept-Encoding", "identity")
                .build()
            val call = httpClient.newCall(httpRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body
                    if (!response.isSuccessful || response.code != 206 || body == null) {
                        response.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IOException("Header requires HTTP 206, got ${response.code}"))
                        }
                        return
                    }
                    val bounded = object : InputStream() {
                        private var remaining = DEFAULT_HEADER_BUDGET
                        override fun read(): Int {
                            if (remaining <= 0) return -1
                            return body.byteStream().read().also { if (it >= 0) remaining-- }
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (remaining <= 0 || len == 0) return if (len == 0) 0 else -1
                            val n = body.byteStream().read(b, off, minOf(len, remaining))
                            if (n > 0) remaining -= n
                            return n
                        }
                        override fun close() = response.close()
                    }
                    if (continuation.isActive) {
                        continuation.resume(AudioHeaderResponse(bounded, response.code, supportsRange = true))
                    } else {
                        response.close()
                    }
                }
            })
        }

    companion object {
        const val DEFAULT_HEADER_BUDGET = 1024 * 1024
    }
}
