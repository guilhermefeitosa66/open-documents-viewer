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
    RTF(hasFormattedView = true, prose = true),
    NOTEBOOK(hasFormattedView = true, prose = true),
    /** YAML and XML: tree as the formatted view. */
    YAML(hasFormattedView = true, prose = false),
    XML(hasFormattedView = true, prose = false),
    /** EPUB e-book: kept as bytes (zip); chapters' XHTML is the source. */
    EPUB(hasFormattedView = true, prose = true),
    /** FictionBook 2: XML, formatted as a book. */
    FB2(hasFormattedView = true, prose = true),
}

data class DocumentType(val kind: DocumentKind, val language: Language?, val label: String) {
    /** Short badge shown in the recents list (MD, KT, JSON...). */
    val badge: String get() = label
}

object DocumentTypes {
    private val markdownExt = setOf("md", "markdown", "mdown", "mkd", "mkdn", "mdx")
    private val plainExt = setOf("txt", "text", "log", "out", "nfo", "rst", "adoc", "asciidoc", "org", "tex", "srt", "vtt")
    val imageExt = setOf("png", "jpg", "jpeg", "jpe", "jfif", "gif", "webp", "bmp", "dib", "ico", "cur", "heic", "heif", "avif", "wbmp")
    private val jsonExt = setOf("json", "jsonc", "json5", "geojson", "webmanifest", "har", "jsonl", "ndjson")

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
        if (ext == "epub") return DocumentType(DocumentKind.EPUB, Languages.HTML, "EPUB")
        if (ext == "fb2") return DocumentType(DocumentKind.FB2, Languages.XML, "FB2")
        if (ext == "ipynb") return DocumentType(DocumentKind.NOTEBOOK, Languages.JSON, "IPYNB")
        if (ext == "rtf") return DocumentType(DocumentKind.RTF, Languages.RTF, "RTF")
        if (ext == "svg") return DocumentType(DocumentKind.SVG, Languages.XML, "SVG")
        if (ext in imageExt) return DocumentType(DocumentKind.IMAGE, null, ext.uppercase().take(4))
        if (ext == "html" || ext == "htm" || ext == "xhtml" || ext == "shtml") return DocumentType(DocumentKind.HTML, Languages.HTML, "HTML")
        if (ext == "csv") return DocumentType(DocumentKind.CSV, null, "CSV")
        if (ext == "tsv" || ext == "tab") return DocumentType(DocumentKind.TSV, null, "TSV")

        Languages.byFileName(lower)?.let { return DocumentType(DocumentKind.CODE, it, it.badge) }
        if (ext.isNotEmpty()) Languages.byExtension(ext)?.let {
            if (it == Languages.YAML) return DocumentType(DocumentKind.YAML, it, it.badge)
            if (it == Languages.XML) return DocumentType(DocumentKind.XML, it, it.badge)
            // Prose formats (logs, subtitles, reST...) stay wrapped text, with their highlighting.
            return DocumentType(if (ext in plainExt) DocumentKind.PLAIN else DocumentKind.CODE, it, it.badge)
        }
        if (ext in plainExt) return DocumentType(DocumentKind.PLAIN, null, "TXT")

        val m = mimeType?.lowercase()?.substringBefore(';')?.trim()
        if (m == "application/epub+zip") return DocumentType(DocumentKind.EPUB, Languages.HTML, "EPUB")
        if (m == "application/x-fictionbook+xml" || m == "application/x-fictionbook") return DocumentType(DocumentKind.FB2, Languages.XML, "FB2")
        if (m == "application/rtf" || m == "text/rtf") return DocumentType(DocumentKind.RTF, Languages.RTF, "RTF")
        if (m == "image/svg+xml") return DocumentType(DocumentKind.SVG, Languages.XML, "SVG")
        if (m != null && m.startsWith("image/")) return DocumentType(DocumentKind.IMAGE, null, m.removePrefix("image/").uppercase().take(4))
        return when (m) {
            "text/markdown", "text/x-markdown" -> DocumentType(DocumentKind.MARKDOWN, Languages.MARKDOWN, "MD")
            "application/json" -> DocumentType(DocumentKind.JSON, Languages.JSON, "JSON")
            "text/csv", "text/comma-separated-values" -> DocumentType(DocumentKind.CSV, null, "CSV")
            "text/tab-separated-values" -> DocumentType(DocumentKind.TSV, null, "TSV")
            "text/xml", "application/xml" -> DocumentType(DocumentKind.XML, Languages.XML, "XML")
            "text/html", "application/xhtml+xml" -> DocumentType(DocumentKind.HTML, Languages.HTML, "HTML")
            "application/x-yaml", "application/yaml", "text/yaml", "text/x-yaml" -> DocumentType(DocumentKind.YAML, Languages.YAML, "YAML")
            "application/x-sh", "text/x-sh", "application/x-shellscript", "text/x-shellscript" -> DocumentType(DocumentKind.CODE, Languages.SHELL, "SH")
            else -> DocumentType(DocumentKind.PLAIN, null, if (ext.isNotEmpty() && ext.length <= 4) ext.uppercase() else "TXT")
        }
    }

    private val interpreters = mapOf(
        "sh" to Languages.SHELL, "bash" to Languages.SHELL, "zsh" to Languages.SHELL, "ksh" to Languages.SHELL, "dash" to Languages.SHELL,
        "fish" to Languages.SHELL, "python" to Languages.PYTHON, "python3" to Languages.PYTHON, "python2" to Languages.PYTHON,
        "node" to Languages.JAVASCRIPT, "nodejs" to Languages.JAVASCRIPT, "deno" to Languages.TYPESCRIPT, "bun" to Languages.TYPESCRIPT,
        "ruby" to Languages.RUBY, "perl" to Languages.PERL, "php" to Languages.PHP, "lua" to Languages.LUA, "Rscript" to Languages.R,
        "pwsh" to Languages.POWERSHELL, "julia" to Languages.JULIA, "elixir" to Languages.ELIXIR, "escript" to Languages.ERLANG,
        "kotlin" to Languages.KOTLIN, "groovy" to Languages.GROOVY, "make" to Languages.MAKEFILE, "awk" to Languages.SHELL,
    )

    /**
     * Files without a known extension that start with a shebang (`#!/usr/bin/env python3`) get the
     * interpreter's highlighting.
     */
    fun refineByContent(type: DocumentType, text: String): DocumentType {
        if (type.language != null || type.kind != DocumentKind.PLAIN || !text.startsWith("#!")) return type
        val first = text.substring(2, text.indexOf('\n').let { if (it < 0) minOf(text.length, 200) else it }).trim()
        val parts = first.split(Regex("\\s+"))
        var prog = parts.firstOrNull()?.substringAfterLast('/') ?: return type
        if (prog == "env") prog = parts.drop(1).firstOrNull { !it.startsWith("-") }?.substringAfterLast('/') ?: return type
        val lang = interpreters[prog] ?: interpreters[prog.trimEnd { it.isDigit() || it == '.' }] ?: return type
        return DocumentType(DocumentKind.CODE, lang, lang.badge)
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
