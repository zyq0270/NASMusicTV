package com.nasmusic.tv.data.model

enum class SongSourceKind { LOCAL, NAS, NETWORK }

data class SongSourceIdentity(
    val kind: SongSourceKind,
    val nativeId: String,
    val backendType: String? = null,
    val serverId: String? = null,
    val platform: String? = null,
    val canonicalUri: String? = null,
    val storageIdentity: String? = null,
)
