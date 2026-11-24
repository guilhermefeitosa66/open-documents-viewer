package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every MIME type the manifest registers maps to a kind (files often arrive without a name). */
class AssociationsTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun viewAndSendRegisterTheSameTypes() {
        val filters = Regex("<intent-filter[^>]*>(.*?)</intent-filter>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }.toList()
        fun mimes(action: String) = filters.first { "android.intent.action.$action\"" in it }.let { f -> Regex("mimeType=\"([^\"]+)\"").findAll(f).map { it.groupValues[1] }.toSet() }
        assertEquals(mimes("VIEW"), mimes("SEND"))
    }

    @Test fun registeredTypesAreRecognized() {
        val generic = setOf("application/octet-stream", "image/*", "text/*")
        val mimes = Regex("mimeType=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet() - generic
        for (m in mimes) {
            val kind = DocumentTypes.detect(null, m).kind
            // Plain text is the right answer only for text and code types.
            assertTrue("$m → $kind", kind != DocumentKind.PLAIN || m.startsWith("application/x-") || m in setOf("application/javascript", "application/typescript", "application/sql", "application/toml"))
        }
    }

    @Test fun officeAndBooks() {
        mapOf(
            "application/pdf" to DocumentKind.PDF, "application/epub+zip" to DocumentKind.EPUB, "application/msword" to DocumentKind.DOC,
            "application/vnd.ms-excel" to DocumentKind.XLS, "application/vnd.ms-powerpoint" to DocumentKind.PPT,
            "application/vnd.oasis.opendocument.graphics" to DocumentKind.ODP, "message/rfc822" to DocumentKind.EMAIL,
            "application/x-ipynb+json" to DocumentKind.NOTEBOOK, "application/vnd.apple.keynote" to DocumentKind.IWORK,
        ).forEach { (m, k) -> assertEquals(m, k, DocumentTypes.detect(null, m).kind) }
    }
}
