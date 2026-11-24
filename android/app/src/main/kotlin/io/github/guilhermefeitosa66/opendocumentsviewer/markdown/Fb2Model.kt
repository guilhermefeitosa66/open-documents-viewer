package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * FictionBook 2 (.fb2): the XML is turned into plain HTML (sections → headings by depth, poems,
 * epigraphs, notes) and handed to [HtmlModel]. Pictures are embedded in the file as base64
 * `<binary>` and become data: URIs.
 */
object Fb2Model {
    fun toHtml(xml: String): String {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val binaries = HashMap<String, String>()
        for (b in doc.getElementsByTag("binary")) {
            val type = b.attr("content-type").ifEmpty { "image/jpeg" }
            binaries[b.attr("id")] = "data:$type;base64," + b.text().filterNot { it.isWhitespace() }
        }
        val sb = StringBuilder("<html><body>")
        val info = doc.getElementsByTag("title-info").firstOrNull()
        val bookTitle = info?.getElementsByTag("book-title")?.firstOrNull()?.text()?.trim().orEmpty()
        val authors = info?.getElementsByTag("author")?.map { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.getElementsByTag(it).firstOrNull()?.text()?.trim() }
                .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { a.getElementsByTag("nickname").firstOrNull()?.text().orEmpty() }
        }?.filter { it.isNotBlank() }.orEmpty()
        info?.getElementsByTag("coverpage")?.firstOrNull()?.getElementsByTag("image")?.firstOrNull()?.let { img ->
            binaries[href(img).removePrefix("#")]?.let { sb.append("<p><img src=\"").append(it).append("\" alt=\"\"></p>") }
        }
        if (bookTitle.isNotEmpty()) sb.append("<h1>").append(esc(bookTitle)).append("</h1>")
        if (authors.isNotEmpty()) sb.append("<p><em>").append(esc(authors.joinToString(", "))).append("</em></p>")
        for (body in doc.getElementsByTag("body")) {
            if (body.parent()?.tagName() != "FictionBook" && body.parent()?.tagName()?.endsWith("FictionBook") != true) continue
            if (body.attr("name") == "notes") sb.append("<hr>")
            children(body, 1, sb, binaries)
        }
        return sb.append("</body></html>").toString()
    }

    private fun href(e: Element): String =
        e.attributes().firstOrNull { it.key == "href" || it.key.endsWith(":href") }?.value.orEmpty()

    private fun children(parent: Element, depth: Int, sb: StringBuilder, bin: Map<String, String>) {
        for (n in parent.childNodes()) node(n, depth, sb, bin)
    }

    private fun node(n: Node, depth: Int, sb: StringBuilder, bin: Map<String, String>) {
        if (n is TextNode) { sb.append(esc(n.text())); return }
        if (n !is Element) return
        val id = n.attr("id").takeIf { it.isNotEmpty() }?.let { " id=\"${esc(it)}\"" }.orEmpty()
        when (n.tagName().substringAfter(':')) {
            "section" -> { sb.append("<div$id>"); children(n, depth + 1, sb, bin); sb.append("</div>") }
            "title" -> {
                val level = depth.coerceIn(1, 6)
                sb.append("<h$level$id>")
                sb.append(n.children().joinToString(" ") { esc(it.text()) }.ifEmpty { esc(n.text()) })
                sb.append("</h$level>")
            }
            "subtitle" -> { sb.append("<h6$id>"); children(n, depth, sb, bin); sb.append("</h6>") }
            "p", "v", "text-author" -> { sb.append("<p$id>"); children(n, depth, sb, bin); sb.append("</p>") }
            "empty-line" -> sb.append("<p></p>")
            "emphasis" -> { sb.append("<em>"); children(n, depth, sb, bin); sb.append("</em>") }
            "strong" -> { sb.append("<strong>"); children(n, depth, sb, bin); sb.append("</strong>") }
            "strikethrough" -> { sb.append("<s>"); children(n, depth, sb, bin); sb.append("</s>") }
            "sub", "sup", "code" -> { val t = n.tagName(); sb.append("<$t>"); children(n, depth, sb, bin); sb.append("</$t>") }
            "a" -> { sb.append("<a href=\"").append(esc(href(n))).append("\">"); children(n, depth, sb, bin); sb.append("</a>") }
            "image" -> bin[href(n).removePrefix("#")]?.let { sb.append("<img src=\"").append(it).append("\" alt=\"\">") }
            "epigraph", "cite" -> { sb.append("<blockquote$id>"); children(n, depth, sb, bin); sb.append("</blockquote>") }
            "poem", "stanza" -> { sb.append("<div$id>"); children(n, depth, sb, bin); sb.append("</div>") }
            "table" -> { sb.append("<table>"); children(n, depth, sb, bin); sb.append("</table>") }
            "tr" -> { sb.append("<tr>"); children(n, depth, sb, bin); sb.append("</tr>") }
            "td", "th" -> { val t = n.tagName(); sb.append("<$t>"); children(n, depth, sb, bin); sb.append("</$t>") }
            "binary", "description" -> Unit
            else -> children(n, depth, sb, bin)
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
