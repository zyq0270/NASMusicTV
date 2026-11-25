package com.nasmusic.tv.lyrics

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.nasmusic.tv.backend.network.baidu.Id3v2Parser
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 本地歌词提供器
 *
 * 针对本地音乐（isLocalSong=true），按优先级获取歌词：
 * 1. 同目录同名 .lrc 文件（侧车歌词）
 * 2. 音乐文件内嵌歌词（ID3v2 USLT 帧）
 *
 * 编码处理：优先 UTF-8，若出现乱码回退 GBK（中文歌曲 ID3 常用 GBK 编码）。
 */
object LocalLyricsProvider {

    private const val TAG = "LocalLyrics"
    /** ID3v2 头部读取字节数（256KB 足够覆盖 USLT 帧） */
    private const val ID3_HEADER_SIZE = 256 * 1024
    private const val MAX_SIDECAR_BYTES = 2 * 1024 * 1024

    internal fun classifyAudioUri(raw: String?): LocalAudioLocation {
        if (raw.isNullOrBlank()) return LocalAudioLocation.Unsupported
        val uri = Uri.parse(raw)
        return when (uri.scheme?.lowercase()) {
            null -> raw.let { path ->
                File(path).takeIf { it.path.isNotBlank() }?.let { LocalAudioLocation.FileLocation(it) }
                    ?: LocalAudioLocation.Unsupported
            }
            "file" -> uri.path?.takeIf { it.isNotBlank() }
                ?.let { LocalAudioLocation.FileLocation(File(it)) }
                ?: LocalAudioLocation.Unsupported
            "content" -> LocalAudioLocation.ContentLocation(
                uri = uri,
                kind = when {
                    uri.pathSegments.firstOrNull() == "tree" -> LocalContentKind.SAF_TREE
                    uri.authority == MediaStore.AUTHORITY -> LocalContentKind.MEDIA_STORE
                    else -> LocalContentKind.SINGLE_DOCUMENT
                },
            )
            else -> LocalAudioLocation.Unsupported
        }
    }

    /**
     * 查找歌曲同目录下的同名 .lrc 文件
     *
     * @param song 本地歌曲（path 为文件绝对路径或 file:// URI）
     * @return 找到的 .lrc 文件，未找到返回 null
     */
    fun findLrcFile(song: Song): File? {
        val location = classifyAudioUri(song.streamUrl ?: song.path) as? LocalAudioLocation.FileLocation
            ?: return null
        val audioFile = location.file
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension
        return File(parent, "$baseName.lrc").takeIf { it.exists() && it.isFile }
    }

    /**
     * 读取 .lrc 文件内容（UTF-8 优先，GBK 回退）
     */
    fun readLrc(lrcFile: File): String? {
        val bytes = try {
            lrcFile.inputStream().use { readBoundedBytes(it, MAX_SIDECAR_BYTES) }
        } catch (e: Exception) {
            AppLog.w(TAG, "read bytes failed: ${e.message}")
            return null
        }
        return bytes?.let(::decodeLrcBytes)
    }

    private fun decodeLrcBytes(bytes: ByteArray): String {
        // 先按 UTF-8 解码，检查是否含替换字符（乱码特征）
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) {
            // 有乱码 → 尝试 GBK
            try {
                bytes.toString(Charset.forName("GBK"))
            } catch (e: Exception) {
                utf8
            }
        } else {
            utf8
        }
    }

    /**
     * 获取本地歌曲的侧车 LRC 歌词（同目录同名 .lrc 文件）
     *
     * @param song 本地歌曲
     * @return LRC 文本，未找到或无效返回 null
     */
    fun getSidecarLyrics(song: Song, resolver: ContentResolver? = null): String? {
        if (!song.isLocalSong) return null
        return when (val location = classifyAudioUri(song.streamUrl ?: song.path)) {
            is LocalAudioLocation.FileLocation -> {
                val lrcFile = findLrcFile(song) ?: return null
                val text = readLrc(lrcFile) ?: return null
                if (!LrcParser.isValidLrc(text)) {
                    AppLog.d(TAG, "sidecar LRC invalid: ${lrcFile.absolutePath}")
                    return null
                }
                text
            }
            is LocalAudioLocation.ContentLocation -> when (location.kind) {
                LocalContentKind.MEDIA_STORE -> resolver?.let { readMediaStoreSidecar(location.uri, it) }
                LocalContentKind.SAF_TREE -> resolver?.let { readSafSidecar(location.uri, it) }
                LocalContentKind.SINGLE_DOCUMENT -> null
            }
            LocalAudioLocation.Unsupported -> null
        }
    }

    private fun readMediaStoreSidecar(uri: Uri, resolver: ContentResolver): String? {
        return try {
            val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val projection = if (modern) {
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA)
            }
            val metadata = resolver.query(uri, projection, null, null, null) ?: return null
            metadata.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val displayName = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return null
                val directory = if (modern) {
                    cursor.getString(1)?.takeIf { it.isNotBlank() }
                        ?.replace('\\', '/')?.trimEnd('/')?.plus('/')
                } else {
                    cursor.getString(1)?.takeIf { it.isNotBlank() }?.let { File(it).parent }
                } ?: return null
                val lrcName = displayName.substringBeforeLast('.', displayName) + ".lrc"
                val volume = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: "external"
                val filesUri = if (modern) {
                    MediaStore.Files.getContentUri(volume)
                } else {
                    Uri.Builder().scheme("content").authority("media")
                        .appendPath(volume).appendPath("file").build()
                }
                val selection = if (modern) {
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                } else {
                    "${MediaStore.MediaColumns.DATA}=?"
                }
                val args = if (modern) arrayOf(directory, lrcName) else arrayOf(File(directory, lrcName).path)
                val rows = resolver.query(filesUri, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                    ?: return null
                rows.use { row ->
                    if (!row.moveToFirst()) return null
                    val lrcUri = Uri.withAppendedPath(filesUri, row.getLong(0).toString())
                    readContentLrc(lrcUri, resolver)
                }
            }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "MediaStore sidecar permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "MediaStore sidecar lookup failed: ${e.message}")
            null
        }
    }

    private fun readSafSidecar(uri: Uri, resolver: ContentResolver): String? {
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
            val parentDocumentId = documentId.substringBeforeLast('/', treeDocumentId)
            val metadata = resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: return null
            val displayName = metadata.use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return null
            }
            val lrcName = displayName.substringBeforeLast('.', displayName) + ".lrc"
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentDocumentId)
            val children = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                ),
                null,
                null,
                null,
            ) ?: return null
            children.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) != lrcName) continue
                    val childId = cursor.getString(idColumn)?.takeIf { it.isNotBlank() } ?: continue
                    val lrcUri = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                    return readContentLrc(lrcUri, resolver)
                }
                null
            }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "SAF sidecar permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "SAF sidecar lookup failed: ${e.message}")
            null
        }
    }

    private fun readContentLrc(uri: Uri, resolver: ContentResolver): String? {
        val input = resolver.openInputStream(uri) ?: return null
        val bytes = input.use { readBoundedBytes(it, MAX_SIDECAR_BYTES) } ?: return null
        return decodeLrcBytes(bytes).takeIf { LrcParser.isValidLrc(it) }
    }

    private fun readBoundedBytes(
        input: InputStream,
        maxBytes: Int,
        truncateAtLimit: Boolean = false,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            total += count
        }
        if (!truncateAtLimit && input.read() != -1) return null
        return output.toByteArray()
    }

    /**
     * 获取本地歌曲的内嵌歌词（ID3v2 USLT 帧）
     *
     * @param song 本地歌曲
     * @return 歌词文本，无内嵌歌词返回 null
     */
    fun getEmbeddedLyrics(song: Song, resolver: ContentResolver? = null): String? {
        if (!song.isLocalSong) return null
        return extractEmbeddedLyrics(song, resolver)
    }

    /**
     * 获取本地歌曲的歌词（侧车 LRC 优先，fallback 内嵌）
     *
     * @param song 本地歌曲
     * @return 歌词文本，未找到返回 null
     */
    fun getLocalLyrics(song: Song, resolver: ContentResolver? = null): String? {
        return getSidecarLyrics(song, resolver) ?: getEmbeddedLyrics(song, resolver)
    }

    /**
     * 从音频文件路径获取歌词（不依赖 Song 对象，供已下载的非本地歌曲使用）
     *
     * 优先级：1. 内嵌 ID3v2 USLT 帧 → 2. 本地 sidecar .lrc 文件
     *
     * @param audioPath 音频文件绝对路径
     * @param lyricPath 显式的 .lrc 文件路径（旁路歌词），若已知可直接传入
     * @return LRC 文本，未找到返回 null
     */
    fun getLyricsFromPath(
        audioPath: String,
        lyricPath: String? = null,
        resolver: ContentResolver? = null,
    ): String? {
        val location = classifyAudioUri(audioPath)
        if (location is LocalAudioLocation.Unsupported) return null
        val song = Song(
            id = "local_path",
            title = "",
            streamUrl = audioPath,
            isLocalSong = true,
        )

        // 1. 内嵌 ID3v2 USLT 帧（最高优先级）
        try {
            val lyrics = extractEmbeddedLyrics(song, resolver)
            if (!lyrics.isNullOrBlank() && LrcParser.isValidLrc(lyrics)) {
                AppLog.d(TAG, "getLyricsFromPath: embedded USLT hit")
                return lyrics
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "getLyricsFromPath embedded failed: ${e.message}")
        }

        // 2. 显式旁路歌词；content URI 同样只能经 ContentResolver 打开。
        if (!lyricPath.isNullOrBlank()) {
            val explicitLyrics = runCatching {
                when (val lyricLocation = classifyAudioUri(lyricPath)) {
                    is LocalAudioLocation.FileLocation -> readLrc(lyricLocation.file)
                    is LocalAudioLocation.ContentLocation ->
                        resolver?.let { readContentLrc(lyricLocation.uri, it) }
                    LocalAudioLocation.Unsupported -> null
                }
            }.getOrElse { error ->
                AppLog.w(TAG, "explicit lyric read failed: ${error.message}")
                null
            }
            if (explicitLyrics != null && LrcParser.isValidLrc(explicitLyrics)) return explicitLyrics
        }

        // 3. 同目录同名 .lrc：File 查兄弟；MediaStore / SAF 走各自授权查询。
        return when (location) {
            is LocalAudioLocation.FileLocation -> {
                val parent = location.file.parentFile
                val baseName = location.file.nameWithoutExtension
                parent?.let {
                    val sidecar = File(it, "$baseName.lrc")
                    if (sidecar.exists() && sidecar.isFile) {
                        readLrc(sidecar)?.let { text -> if (LrcParser.isValidLrc(text)) return text }
                    }
                }
                null
            }
            is LocalAudioLocation.ContentLocation -> getSidecarLyrics(song, resolver)
            LocalAudioLocation.Unsupported -> null
        }
    }

    /**
     * 从音频文件内嵌 ID3v2 元数据提取歌词（USLT 帧）
     *
     * 读取文件头部前 256KB 解析 ID3v2 帧，与百度网盘的 Id3v2Parser.findUslt() 复用同一逻辑。
     */
    private fun extractEmbeddedLyrics(song: Song, resolver: ContentResolver?): String? {
        val headerBytes = when (val location = classifyAudioUri(song.streamUrl ?: song.path)) {
            is LocalAudioLocation.FileLocation -> {
                val file = location.file
                if (!file.exists() || !file.isFile) return null
                Id3v2Parser.readLocalHeaderSmart(file) ?: ByteArray(0)
            }
            is LocalAudioLocation.ContentLocation -> {
                val contentResolver = resolver ?: return null
                readContentHeaderSmart(location.uri, contentResolver) ?: ByteArray(0)
            }
            LocalAudioLocation.Unsupported -> return null
        }

        return try {
            val lyrics = Id3v2Parser.findUslt(headerBytes)
            if (!lyrics.isNullOrBlank()) {
                AppLog.d(TAG, "extracted embedded lyrics from: ${song.streamUrl ?: song.path}")
                lyrics
            } else {
                null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "extract embedded lyrics failed: ${e.message}")
            null
        }
    }

    private fun readContentHeaderSmart(uri: Uri, resolver: ContentResolver): ByteArray? {
        return try {
            val probe = resolver.openInputStream(uri)?.use {
                readBoundedBytes(it, ID3_HEADER_SIZE, truncateAtLimit = true)
            } ?: return null
            val tagTotal = Id3v2Parser.tagTotalSize(probe) ?: return null
            if (tagTotal <= probe.size) return probe
            val want = minOf(tagTotal, Id3v2Parser.DEFAULT_MAX_TAG_BYTES)
            resolver.openInputStream(uri)?.use {
                readBoundedBytes(it, want, truncateAtLimit = true)
            }
        } catch (e: SecurityException) {
            AppLog.w(TAG, "embedded lyrics permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            AppLog.w(TAG, "read content ID3 header failed: ${e.message}")
            null
        }
    }
}

internal sealed interface LocalAudioLocation {
    data class FileLocation(val file: File) : LocalAudioLocation
    data class ContentLocation(
        val uri: Uri,
        val kind: LocalContentKind,
    ) : LocalAudioLocation
    data object Unsupported : LocalAudioLocation
}

internal enum class LocalContentKind {
    MEDIA_STORE,
    SAF_TREE,
    SINGLE_DOCUMENT,
}
