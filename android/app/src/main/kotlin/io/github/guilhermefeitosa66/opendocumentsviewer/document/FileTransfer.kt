package io.github.guilhermefeitosa66.opendocumentsviewer.document

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.io.OutputStream

/** What can be shared or saved: a file from a provider, or text shared into the app. */
sealed interface Shareable {
    val name: String

    data class File(val uri: Uri, override val name: String, val mimeType: String?) : Shareable
    data class Text(val text: String, override val name: String) : Shareable
    /** Bytes held by the viewer (an e-mail attachment): only saved, never shared. */
    class Bytes(val bytes: ByteArray, override val name: String, val mimeType: String?) : Shareable
}

object FileTransfer {
    /** Up to this size a text file can also be shared as text (message body). */
    const val SHARE_AS_TEXT_MAX_CHARS = 100_000

    /**
     * The real type of a file for other apps: what the provider says, unless that is missing or
     * the generic octet-stream, in which case the extension decides (WhatsApp, Gmail and Drive
     * show and open the file by this type).
     */
    fun mimeFor(name: String, given: String?): String {
        val g = given?.substringBefore(';')?.trim()?.lowercase()
        if (!g.isNullOrEmpty() && g != "application/octet-stream") return g
        val ext = name.substringAfterLast('.', "").lowercase()
        knownTextTypes[ext]?.let { return it }
        val fromSystem = if (ext.isNotEmpty()) MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) else null
        if (fromSystem != null) return fromSystem
        return if (DocumentTypes.detect(name, null).kind == DocumentKind.PLAIN && ext.isNotEmpty() && ext !in DocumentTypesText.plainExtensions) {
            "application/octet-stream"
        } else "text/plain"
    }

    private val knownTextTypes = mapOf(
        "md" to "text/markdown", "markdown" to "text/markdown",
        "json" to "application/json", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "xml" to "text/xml", "yaml" to "application/yaml", "yml" to "application/yaml",
        "html" to "text/html", "htm" to "text/html", "txt" to "text/plain", "log" to "text/plain",
        "env" to "text/plain",
    )

    fun shareIntent(item: Shareable, asText: Boolean = false, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).apply {
            when {
                asText || item is Shareable.Text -> {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text ?: (item as? Shareable.Text)?.text.orEmpty())
                }
                item is Shareable.File -> {
                    type = mimeFor(item.name, item.mimeType)
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    // ClipData carries the read grant through the chooser to the target app.
                    clipData = ClipData.newRawUri(item.name, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            putExtra(Intent.EXTRA_SUBJECT, item.name)
            putExtra(Intent.EXTRA_TITLE, item.name)
        }

    /** The picker where the user chooses the folder for a copy, with the original name. */
    fun createDocumentIntent(item: Shareable): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = when (item) {
            is Shareable.File -> mimeFor(item.name, item.mimeType)
            is Shareable.Text -> "text/plain"
            is Shareable.Bytes -> item.mimeType?.takeIf { it.contains('/') } ?: mimeFor(item.name, null)
        }
        putExtra(Intent.EXTRA_TITLE, item.name)
    }

    /**
     * Streams [item] into [target]. [onProgress] gets the fraction done, or -1 when the size is
     * unknown. Throws on failure; the caller reports it.
     */
    fun copy(resolver: ContentResolver, item: Shareable, target: Uri, totalBytes: Long, onProgress: (Float) -> Unit) {
        val out: OutputStream = resolver.openOutputStream(target, "wt") ?: error("cannot write")
        out.use { o ->
            when (item) {
                is Shareable.Text -> o.write(item.text.toByteArray())
                is Shareable.Bytes -> copyStream(item.bytes.inputStream(), o, item.bytes.size.toLong(), onProgress)
                is Shareable.File -> {
                    val input: InputStream = resolver.openInputStream(item.uri) ?: error("cannot read")
                    input.use { copyStream(it, o, totalBytes, onProgress) }
                }
            }
        }
        onProgress(1f)
    }

    internal fun copyStream(input: InputStream, out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastReported = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
                val pct = (done * 100 / total).toInt()
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress((done.toFloat() / total).coerceAtMost(1f))
                }
            } else onProgress(-1f)
        }
    }
}

private object DocumentTypesText {
    val plainExtensions = setOf("txt", "text", "log", "out", "nfo", "rst", "adoc", "org", "srt", "vtt", "tex")
}
