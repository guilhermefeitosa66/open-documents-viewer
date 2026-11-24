package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * EPUB 2 and 3 (no DRM): the chapters of the spine, in order, turned into the markdown block list
 * by [HtmlModel]. Images stay inside the book: their sources become `epub:<path in the zip>` and
 * the image loader reads them from the file's bytes. Links between chapters become anchors
 * (`#<path>` and `#<path>#<id>`). The table of contents comes from nav.xhtml (EPUB 3) or toc.ncx
 * (EPUB 2), falling back to the chapter headings.
 */
object EpubModel {
    const val IMAGE_SCHEME = "epub:"

    class Chapter(val path: String, val html: String)
    class TocEntry(val level: Int, val title: String, val target: String)
    class Book(val title: String?, val chapters: List<Chapter>, val toc: List<TocEntry>)

    class Parsed(val blocks: List<MdBlock>, val anchors: Map<String, Int>, val toc: List<Pair<TocEntry, Int>>)

    class NotEpub(message: String) : Exception(message)
    class Encrypted : Exception("encrypted (DRM)")

    /** Fonts obfuscated this way are not DRM: the text is readable. */
    private val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /** Every entry of the zip, by path. A book is at most a few tens of MB (the loader caps it). */
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

    /** One file of the book (an image), or null. */
    fun resource(bytes: ByteArray, path: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name.trimStart('/') == path) return zip.readBytes()
            }
        }
    }

    fun open(bytes: ByteArray): Book {
        val files = try { entries(bytes) } catch (e: Exception) { throw NotEpub("not a zip file") }
        files["META-INF/encryption.xml"]?.let { enc ->
            val x = Jsoup.parse(String(enc, Charsets.UTF_8), "", Parser.xmlParser())
            val algorithms = x.select("EncryptionMethod, enc|EncryptionMethod").map { it.attr("Algorithm") }
            if (algorithms.any { it !in fontObfuscation }) throw Encrypted()
        }
        val container = files["META-INF/container.xml"] ?: throw NotEpub("no META-INF/container.xml")
        val opfPath = Jsoup.parse(String(container, Charsets.UTF_8), "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")?.trimStart('/') ?: throw NotEpub("no rootfile")
        val opfBytes = files[opfPath] ?: throw NotEpub("missing $opfPath")
        val opf = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", Parser.xmlParser())
        val base = opfPath.substringBeforeLast('/', "")

        class Item(val href: String, val type: String, val props: String)
        val manifest = HashMap<String, Item>()
        for (it in opf.select("manifest > item")) {
            manifest[it.attr("id")] = Item(resolve(base, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val title = opf.selectFirst("metadata > dc|title, metadata > title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val chapters = ArrayList<Chapter>()
        for (ref in opf.select("spine > itemref")) {
            val item = manifest[ref.attr("idref")] ?: continue
            if (!item.type.contains("html") && !item.href.endsWith("htm") && !item.href.endsWith("html")) continue
            val data = files[item.href] ?: continue
            chapters += Chapter(item.href, decode(data))
        }
        if (chapters.isEmpty()) throw NotEpub("empty spine")

        // EPUB 3 nav document, else the EPUB 2 NCX.
        val toc = ArrayList<TocEntry>()
        manifest.values.firstOrNull { "nav" in it.props.split(' ') }?.let { nav ->
            files[nav.href]?.let { navToc(decode(it), nav.href, toc) }
        }
        if (toc.isEmpty()) {
            val ncxId = opf.selectFirst("spine")?.attr("toc")
            val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
            ncx?.let { n -> files[n.href]?.let { ncxToc(String(it, Charsets.UTF_8), n.href, toc) } }
        }
        return Book(title, chapters, toc)
    }

    private fun navToc(html: String, path: String, out: MutableList<TocEntry>) {
        val doc = Jsoup.parse(html)
        val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav") ?: return
        fun walk(ol: org.jsoup.nodes.Element, level: Int) {
            for (li in ol.children().filter { it.normalName() == "li" }) {
                val a = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
                val href = a?.attr("href").orEmpty()
                val text = a?.text()?.trim().orEmpty()
                if (text.isNotEmpty() && href.isNotEmpty()) out += TocEntry(level, text, target(path, href))
                li.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, level + 1) }
            }
        }
        nav.children().firstOrNull { it.normalName() == "ol" || it.normalName() == "ul" }?.let { walk(it, 1) }
    }

    private fun ncxToc(xml: String, path: String, out: MutableList<TocEntry>) {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        fun walk(parent: org.jsoup.nodes.Element, level: Int) {
            for (np in parent.children().filter { it.tagName() == "navPoint" }) {
                val text = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                val src = np.selectFirst("content")?.attr("src").orEmpty()
                if (text.isNotEmpty() && src.isNotEmpty()) out += TocEntry(level, text, target(path, src))
                walk(np, level + 1)
            }
        }
        doc.selectFirst("navMap")?.let { walk(it, 1) }
    }

    /** "chapter.xhtml#id" relative to [from] → "OEBPS/chapter.xhtml#id". */
    private fun target(from: String, href: String): String {
        val path = href.substringBefore('#')
        val frag = href.substringAfter('#', "")
        val abs = if (path.isEmpty()) from else resolve(from.substringBeforeLast('/', ""), path)
        return if (frag.isEmpty()) abs else "$abs#$frag"
    }

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

    private fun decode(data: ByteArray): String {
        val head = String(data, 0, minOf(data.size, 200), Charsets.ISO_8859_1)
        val cs = Regex("encoding=[\"']([A-Za-z0-9_-]+)").find(head)?.groupValues?.get(1)
        return try { String(data, cs?.let { charset(it) } ?: Charsets.UTF_8) } catch (_: Exception) { String(data, Charsets.UTF_8) }
    }

    /** The XHTML of every chapter, one after the other: the "source" view of the book. */
    fun sourceText(book: Book): String = buildString {
        for (c in book.chapters) {
            append("<!-- ").append(c.path).append(" -->\n")
            append(c.html.trimEnd()).append("\n\n")
        }
    }

    fun parse(book: Book): Parsed {
        val blocks = ArrayList<MdBlock>()
        val anchors = HashMap<String, Int>()
        for (c in book.chapters) {
            val doc = Jsoup.parse(c.html)
            rewrite(doc, c.path)
            val r = HtmlModel.parse(doc, useTitle = false)
            if (r.blocks.isNotEmpty() && blocks.isNotEmpty()) blocks += MdBlock.Rule(0, 0)
            val start = blocks.size
            anchors.putIfAbsent(c.path, start)
            r.ids.forEach { (id, i) -> anchors.putIfAbsent("${c.path}#$id", start + i) }
            blocks += r.blocks
        }
        if (book.title != null && blocks.none { it is MdBlock.Heading }) {
            blocks.add(0, MdBlock.Heading(1, InlineText(book.title), 0, 0))
            val shifted = anchors.mapValues { it.value + 1 }
            anchors.clear(); anchors.putAll(shifted)
        }
        if (blocks.isNotEmpty()) anchors.replaceAll { _, i -> i.coerceAtMost(blocks.size - 1) }
        val toc = book.toc.mapNotNull { e -> (anchors[e.target] ?: anchors[e.target.substringBefore('#')])?.let { e to it } }
        return Parsed(blocks, anchors, toc)
    }

    /** Image sources point into the book, links between chapters become anchors, SVG covers become images. */
    private fun rewrite(doc: Document, path: String) {
        val dir = path.substringBeforeLast('/', "")
        for (svg in doc.select("svg")) {
            val img = svg.selectFirst("image")
            val href = img?.attr("xlink:href")?.ifEmpty { img.attr("href") }.orEmpty()
            if (href.isNotEmpty()) svg.replaceWith(org.jsoup.nodes.Element("img").attr("src", href).attr("alt", ""))
        }
        for (img in doc.select("img[src]")) {
            val src = img.attr("src")
            if (src.startsWith("data:") || src.contains("://")) continue
            img.attr("src", IMAGE_SCHEME + resolve(dir, src.substringBefore('#')))
        }
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.contains("://") || href.startsWith("mailto:")) continue
            a.attr("href", "#" + target(path, href))
        }
    }
}
