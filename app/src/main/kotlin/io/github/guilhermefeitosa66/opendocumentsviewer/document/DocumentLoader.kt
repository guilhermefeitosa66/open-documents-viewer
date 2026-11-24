package io.github.guilhermefeitosa66.opendocumentsviewer.document

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/** A document read into memory, ready to be shown. */
class LoadedDocument(
    val uri: Uri?,
    val name: String,
    val mimeType: String?,
    /** Size of the file in bytes, or -1 when the provider does not say. */
    val size: Long,
    val text: String,
    val charset: String,
    val type: DocumentType,
    /** The file was larger than [DocumentLoader.MAX_BYTES]: only the start is shown. */
    val truncated: Boolean,
    val location: String?,
)

sealed interface LoadFailure {
    data object NotFound : LoadFailure
    /** The permission given by the other app is gone (typical for old "open with" recents). */
    data object PermissionLost : LoadFailure
    data object Binary : LoadFailure
    data class Other(val message: String) : LoadFailure
}

class DocumentLoader(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    data class Meta(val name: String, val size: Long, val mimeType: String?, val location: String?)

    fun meta(uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
            // Some providers do not answer queries; the file can still be read.
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "document"
        val mime = try { resolver.getType(uri) } catch (_: Exception) { null }
        return Meta(name!!, size, mime, location(uri))
    }

    /** A short, human name for where the file lives: its folder, or the app that shared it. */
    fun location(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.substringBeforeLast('/')?.substringAfterLast('/')
        val authority = uri.authority ?: return null
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                when (authority) {
                    "com.android.externalstorage.documents" -> {
                        val path = id.substringAfter(':', "")
                        val folder = path.substringBeforeLast('/', "")
                        return folder.substringAfterLast('/').ifEmpty { "Storage" }
                    }
                    "com.android.providers.downloads.documents" -> return "Downloads"
                    // Picked from "Recent", "Documents"...: the folder is not exposed.
                    "com.android.providers.media.documents" -> return null
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val info = context.packageManager.resolveContentProvider(authority, 0)
            info?.applicationInfo?.loadLabel(context.packageManager)?.toString()
        } catch (_: Exception) {
            null
        }
    }

    fun load(uri: Uri, mimeHint: String?): Result<LoadedDocument> {
        val meta = meta(uri)
        fun failure(f: LoadFailure): Result<LoadedDocument> = Result.failure(LoadException(f, meta.name))
        val stream: InputStream = try {
            resolver.openInputStream(uri) ?: return failure(LoadFailure.NotFound)
        } catch (_: SecurityException) {
            return failure(LoadFailure.PermissionLost)
        } catch (_: FileNotFoundException) {
            return failure(LoadFailure.NotFound)
        } catch (e: Exception) {
            return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
        }
        val (bytes, length, truncated) = try {
            stream.use { readUpTo(it, MAX_BYTES) }
        } catch (e: Exception) {
            return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
        }
        val decoded = TextDecoder.decode(bytes, length)
        if (decoded !is Decoded.Text) return failure(LoadFailure.Binary)
        val mime = meta.mimeType ?: mimeHint
        return Result.success(
            LoadedDocument(
                uri = uri,
                name = meta.name,
                mimeType = mime,
                size = if (meta.size >= 0) meta.size else length.toLong(),
                text = decoded.text,
                charset = decoded.charset,
                type = DocumentTypes.detect(meta.name, mime),
                truncated = truncated,
                location = meta.location,
            )
        )
    }

    class LoadException(val failure: LoadFailure, val name: String? = null) : Exception(failure.toString())

    private fun failure(f: LoadFailure): Result<LoadedDocument> = Result.failure(LoadException(f))

    private data class Read(val bytes: ByteArray, val length: Int, val truncated: Boolean)

    private fun readUpTo(input: InputStream, max: Int): Read {
        var buf = ByteArray(64 * 1024)
        var len = 0
        while (true) {
            if (len == buf.size) {
                if (buf.size >= max) {
                    // One byte more tells whether the file goes on.
                    return Read(buf, len, input.read() != -1)
                }
                buf = buf.copyOf(minOf(buf.size * 2, max))
            }
            val r = input.read(buf, len, buf.size - len)
            if (r < 0) break
            len += r
        }
        return Read(buf, len, false)
    }

    companion object {
        /** Files above this are shown up to here, with a banner. */
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
