package com.nasmusic.tv.backend

data class BackendCapabilities(
    val playlistRead: Boolean = false,
    val playlistCreate: Boolean = false,
    val playlistDelete: Boolean = false,
    val playlistAdd: Boolean = false,
    val playlistRemove: Boolean = false,
)

sealed interface BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>
    data class Failure(val error: BackendError) : BackendResult<Nothing>
}

enum class BackendError {
    UNSUPPORTED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT,
    NETWORK, TIMEOUT, SERVER, PROTOCOL, UNKNOWN
}

inline fun <T> BackendResult<T>.getOrNull(): T? = (this as? BackendResult.Success)?.value
