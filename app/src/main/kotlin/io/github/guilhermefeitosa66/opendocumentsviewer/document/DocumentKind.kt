package io.github.guilhermefeitosa66.opendocumentsviewer.document

import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Language
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Languages

/** How a document is shown. Kinds with [hasFormattedView] get the Formatted/Source toggle. */
enum class DocumentKind(val hasFormattedView: Boolean, val prose: Boolean) {
    MARKDOWN(hasFormattedView = true, prose = true),
    PLAIN(hasFormattedView = false, prose = true),
    CODE(hasFormattedView = false, prose = false),
    JSON(hasFormattedView = true, prose = false),
    CSV(hasFormattedView = true, prose = false),
    TSV(hasFormattedView = true, prose = false),
    DOTENV(hasFormattedView = true, prose = false),
    HTML(hasFormattedView = true, prose = true),
    /** Raster images: shown, never decoded as text. */
    IMAGE(hasFormattedView = false, prose = false),
    /** SVG: rendered as the formatted view, XML as the source. */
    SVG(hasFormattedView = true, prose = false),
}

data class DocumentType(val kind: DocumentKind, val language: Language?, val label: String) {
    /** Short badge shown in the recents list (MD, KT, JSON...). */
    val badge: String get() = label
}

object DocumentTypes {
    private val markdownExt = setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdx")
    private val plainExt = setOf("txt", "text", "log", "out", "nfo", "rst", "adoc", "asciidoc", "org", "tex", "srt", "vtt")
    val imageExt = setOf("png", "jpg", "jpeg", "jpe", "jfif", "gif", "webp", "bmp", "dib", "ico", "cur", "heic", "heif", "avif", "wbmp")
    private val jsonExt = setOf("json", "jsonc", "json5", "geojson", "webmanifest", "har", "ipynb", "jsonl", "ndjson")

    fun detect(fileName: String?, mimeType: String?): DocumentType {
        val name = (fileName ?: "").substringAfterLast('/')
        val lower = name.lowercase()
        val ext = lower.substringAfterLast('.', missingDelimiterValue = "")

        if (lower == ".env" || lower.startsWith(".env.") || ext == "env" || lower == ".envrc" || lower.endsWith(".env.example")) {
            return DocumentType(DocumentKind.DOTENV, Languages.DOTENV, "ENV")
        }
        if (ext in markdownExt) return DocumentType(DocumentKind.MARKDOWN, Languages.MARKDOWN, "MD")
        if (ext in jsonExt || lower == ".babelrc" || lower == ".eslintrc" || lower == "composer.lock") {
            return DocumentType(DocumentKind.JSON, Languages.JSON, "JSON")
        }
        if (ext == "svg") return DocumentType(DocumentKind.SVG, Languages.XML, "SVG")
        if (ext in imageExt) return DocumentType(DocumentKind.IMAGE, null, ext.uppercase().take(4))
        if (ext == "html" || ext == "htm" || ext == "xhtml" || ext == "shtml") return DocumentType(DocumentKind.HTML, Languages.HTML, "HTML")
        if (ext == "csv") return DocumentType(DocumentKind.CSV, null, "CSV")
        if (ext == "tsv" || ext == "tab") return DocumentType(DocumentKind.TSV, null, "TSV")

        Languages.byFileName(lower)?.let { return DocumentType(DocumentKind.CODE, it, it.badge) }
        if (ext.isNotEmpty()) Languages.byExtension(ext)?.let { return DocumentType(DocumentKind.CODE, it, it.badge) }
        if (ext in plainExt) return DocumentType(DocumentKind.PLAIN, null, "TXT")

        val m = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (m == "image/svg+xml") return DocumentType(DocumentKind.SVG, Languages.XML, "SVG")
        if (m != null && m.startsWith("image/")) return DocumentType(DocumentKind.IMAGE, null, m.removePrefix("image/").uppercase().take(4))
        return when (m) {
            "text/markdown", "text/x-markdown" -> DocumentType(DocumentKind.MARKDOWN, Languages.MARKDOWN, "MD")
            "application/json" -> DocumentType(DocumentKind.JSON, Languages.JSON, "JSON")
            "text/csv", "text/comma-separated-values" -> DocumentType(DocumentKind.CSV, null, "CSV")
            "text/tab-separated-values" -> DocumentType(DocumentKind.TSV, null, "TSV")
            "text/xml", "application/xml" -> DocumentType(DocumentKind.CODE, Languages.XML, "XML")
            "text/html", "application/xhtml+xml" -> DocumentType(DocumentKind.HTML, Languages.HTML, "HTML")
            "application/x-yaml", "application/yaml", "text/yaml", "text/x-yaml" -> DocumentType(DocumentKind.CODE, Languages.YAML, "YAML")
            "application/x-sh", "text/x-sh", "application/x-shellscript", "text/x-shellscript" -> DocumentType(DocumentKind.CODE, Languages.SHELL, "SH")
            else -> DocumentType(DocumentKind.PLAIN, null, if (ext.isNotEmpty() && ext.length <= 4) ext.uppercase() else "TXT")
        }
    }

    /** Type for text shared from another app (no file name): markdown if it looks like it. */
    fun detectSharedText(text: String): DocumentType {
        val sample = text.take(4000)
        val looksMarkdown = Regex("(?m)^(#{1,6} |```|[-*] \\[[ xX]] |> |\\|.*\\|\\s*$)").containsMatchIn(sample) ||
            Regex("\\[[^\\]]+]\\([^)]+\\)|\\*\\*[^*]+\\*\\*").containsMatchIn(sample)
        return if (looksMarkdown) DocumentType(DocumentKind.MARKDOWN, Languages.MARKDOWN, "MD")
        else DocumentType(DocumentKind.PLAIN, null, "TXT")
    }
}
