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
    /** Last-modified time from the provider (0 when unknown): with [size], detects changes. */
    val modified: Long = 0,
    /** Raw bytes for binary formats (images); null for text documents. */
    val bytes: ByteArray? = null,
    /**
     * The parsed document for archive formats (EPUB book, DOCX result...), built by the loader off
     * the main thread; [text] then holds what the source view and "Copy all" show.
     */
    val model: Any? = null,
)

sealed interface LoadFailure {
    data object NotFound : LoadFailure
    /** The permission given by the other app is gone (typical for old "open with" recents). */
    data object PermissionLost : LoadFailure
    data object Binary : LoadFailure
    /** An e-book locked with DRM. */
    data object Encrypted : LoadFailure
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

    /**
     * Size and last-modified time, as cheaply as the provider allows. Used on return to the app
     * to notice that the file changed.
     */
    fun stamp(uri: Uri): Pair<Long, Long> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = java.io.File(uri.path ?: return -1L to 0L)
            return f.length() to f.lastModified()
        }
        var size = -1L
        var modified = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        } catch (_: Exception) {
        }
        try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) modified = c.getLong(0)
            }
        } catch (_: Exception) {
            // Not a documents provider: size alone has to do.
        }
        return size to modified
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

    /** [charset]: an encoding the user picked ("Encoding…"), or null to detect it. */
    fun load(uri: Uri, mimeHint: String?, charset: String? = null): Result<LoadedDocument> {
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
        val mimeEarly = meta.mimeType ?: mimeHint
        val earlyType = DocumentTypes.detect(meta.name, mimeEarly)
        if (earlyType.kind == DocumentKind.IMAGE) {
            // Images are kept as bytes and decoded by the image view (sampled to the screen).
            val read = try { stream.use { readUpTo(it, MAX_IMAGE_BYTES) } } catch (e: Exception) {
                return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
            }
            if (read.truncated) return failure(LoadFailure.Other("image larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"))
            return Result.success(
                LoadedDocument(
                    uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else read.length.toLong(),
                    text = "", charset = "", type = earlyType, truncated = false, location = meta.location,
                    modified = stamp(uri).second, bytes = read.bytes.copyOf(read.length),
                )
            )
        }
        if (earlyType.kind == DocumentKind.EPUB) {
            val read = try { stream.use { readUpTo(it, MAX_IMAGE_BYTES) } } catch (e: Exception) {
                return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
            }
            if (read.truncated) return failure(LoadFailure.Other("e-book larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"))
            val bytes = read.bytes.copyOf(read.length)
            val book = try {
                io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel.open(bytes)
            } catch (_: io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel.Encrypted) {
                return failure(LoadFailure.Encrypted)
            } catch (e: Exception) {
                return failure(LoadFailure.Other("EPUB: " + (e.message ?: e.javaClass.simpleName)))
            }
            return Result.success(
                LoadedDocument(
                    uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else bytes.size.toLong(),
                    text = io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel.sourceText(book), charset = "",
                    type = earlyType, truncated = false, location = meta.location, modified = stamp(uri).second,
                    bytes = bytes, model = book,
                )
            )
        }
        if (earlyType.kind == DocumentKind.PPTX || earlyType.kind == DocumentKind.ODP || earlyType.kind == DocumentKind.PPT) {
            val read = try { stream.use { readUpTo(it, MAX_IMAGE_BYTES) } } catch (e: Exception) {
                return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
            }
            if (read.truncated) return failure(LoadFailure.Other("presentation larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"))
            val bytes = read.bytes.copyOf(read.length)
            val pres = try {
                if (earlyType.kind == DocumentKind.ODP) io.github.guilhermefeitosa66.opendocumentsviewer.formats.OdpReader.read(bytes)
                else if (earlyType.kind == DocumentKind.PPT) io.github.guilhermefeitosa66.opendocumentsviewer.formats.PptReader.read(bytes)
                else io.github.guilhermefeitosa66.opendocumentsviewer.formats.PptxReader.read(bytes)
            } catch (e: Exception) {
                return failure(LoadFailure.Other(earlyType.label + ": " + (e.message ?: e.javaClass.simpleName)))
            }
            return Result.success(
                LoadedDocument(
                    uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else bytes.size.toLong(),
                    text = pres.plainText(), charset = "", type = earlyType, truncated = false, location = meta.location,
                    modified = stamp(uri).second, bytes = bytes, model = pres,
                )
            )
        }
        if (earlyType.kind == DocumentKind.XLSX || earlyType.kind == DocumentKind.ODS || earlyType.kind == DocumentKind.XLS) {
            val read = try { stream.use { readUpTo(it, MAX_IMAGE_BYTES) } } catch (e: Exception) {
                return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
            }
            if (read.truncated) return failure(LoadFailure.Other("workbook larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"))
            val bytes = read.bytes.copyOf(read.length)
            val wb = try {
                if (earlyType.kind == DocumentKind.ODS) io.github.guilhermefeitosa66.opendocumentsviewer.formats.OdsReader.read(bytes)
                else if (earlyType.kind == DocumentKind.XLS) io.github.guilhermefeitosa66.opendocumentsviewer.formats.XlsReader.read(bytes)
                else io.github.guilhermefeitosa66.opendocumentsviewer.formats.XlsxReader.read(bytes)
            } catch (e: Exception) {
                return failure(LoadFailure.Other(earlyType.label + ": " + (e.message ?: e.javaClass.simpleName)))
            } catch (e: OutOfMemoryError) {
                return failure(LoadFailure.Other(earlyType.label + ": too large for this device"))
            }
            return Result.success(
                LoadedDocument(
                    uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else bytes.size.toLong(),
                    text = wb.plainText(), charset = "", type = earlyType, truncated = false, location = meta.location,
                    modified = stamp(uri).second, bytes = bytes, model = wb,
                )
            )
        }
        if (earlyType.kind == DocumentKind.DOCX || earlyType.kind == DocumentKind.ODT || earlyType.kind == DocumentKind.DOC) {
            val read = try { stream.use { readUpTo(it, MAX_IMAGE_BYTES) } } catch (e: Exception) {
                return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
            }
            if (read.truncated) return failure(LoadFailure.Other("document larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB"))
            val bytes = read.bytes.copyOf(read.length)
            if (earlyType.kind == DocumentKind.DOC && !io.github.guilhermefeitosa66.opendocumentsviewer.formats.Cfb.isCfb(bytes)) {
                // Many ".doc" files are really RTF or HTML saved by Word: shown as what they are.
                val decoded = TextDecoder.decode(bytes, bytes.size) as? Decoded.Text ?: return failure(LoadFailure.Binary)
                val head = decoded.text.trimStart().take(400).lowercase()
                val type = when {
                    head.startsWith("{\\rtf") -> DocumentTypes.detect("x.rtf", null)
                    head.startsWith("<") && ("<html" in head || "<!doctype html" in head) -> DocumentTypes.detect("x.html", null)
                    else -> DocumentTypes.detect("x.txt", null)
                }
                return Result.success(
                    LoadedDocument(
                        uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else bytes.size.toLong(),
                        text = decoded.text, charset = decoded.charset, type = type, truncated = false, location = meta.location,
                        modified = stamp(uri).second,
                    )
                )
            }
            val parsed = try {
                if (earlyType.kind == DocumentKind.DOC) io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocModel.parse(bytes)
                else if (earlyType.kind == DocumentKind.ODT) io.github.guilhermefeitosa66.opendocumentsviewer.markdown.OdtModel.parse(bytes)
                else io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocxModel.parse(bytes)
            } catch (e: Exception) {
                return failure(LoadFailure.Other(earlyType.label + ": " + (e.message ?: e.javaClass.simpleName)))
            }
            return Result.success(
                LoadedDocument(
                    uri = uri, name = meta.name, mimeType = mimeEarly, size = if (meta.size >= 0) meta.size else bytes.size.toLong(),
                    text = parsed.plainText, charset = "", type = earlyType, truncated = false, location = meta.location,
                    modified = stamp(uri).second, bytes = bytes, model = parsed,
                )
            )
        }
        val (bytes, length, truncated) = try {
            stream.use { readUpTo(it, MAX_BYTES) }
        } catch (e: Exception) {
            return failure(LoadFailure.Other(e.message ?: e.javaClass.simpleName))
        }
        val decoded = if (charset != null) TextDecoder.decodeAs(bytes, length, charset) else TextDecoder.decode(bytes, length)
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
                type = DocumentTypes.refineByContent(DocumentTypes.detect(meta.name, mime), decoded.text),
                truncated = truncated,
                location = meta.location,
                modified = stamp(uri).second,
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
        const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
    }
}
