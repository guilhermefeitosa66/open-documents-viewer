package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Zip-based documents (EPUB, OOXML, ODF) are kept as bytes; pictures inside them are referenced as
 * `zip:<path>` and read from those bytes by the image loader.
 */
object ZipFiles {
    const val IMAGE_SCHEME = "zip:"

    /** Every file of the archive, by path. */
    fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) out[e.name.trimStart('/')] = zip.readBytes()
            }
        }
        return out
    }

    /** Only the files [wanted] says yes to (pictures stay compressed in the archive until shown). */
    fun entries(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.trimStart('/')
                if (!e.isDirectory && wanted(name)) out[name] = zip.readBytes()
            }
        }
        return out
    }

    fun resource(bytes: ByteArray, path: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name.trimStart('/') == path) return zip.readBytes()
            }
        }
    }

    /** Zip files start with "PK\u0003\u0004". */
    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** "../media/a b.png" against "word" → "media/a b.png". */
    fun resolve(base: String, href: String): String {
        val decoded = try { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { href }
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/")) base.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }
}
