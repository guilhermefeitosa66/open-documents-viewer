package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * HTML turned into the same flat block list the markdown view renders. Nothing runs and nothing
 * is fetched: scripts, styles, frames and forms are dropped, images are just references (the
 * view loads local ones and never remote ones).
 */
object HtmlModel {
    class Result(val blocks: List<MdBlock>, val ids: Map<String, Int>)

    fun parse(html: String): Result = parse(Jsoup.parse(html), useTitle = true)

    /** [useTitle]: the page title becomes a heading when the body has none (off for book chapters). */
    fun parse(doc: org.jsoup.nodes.Document, useTitle: Boolean): Result {
        val out = ArrayList<MdBlock>()
        val ids = HashMap<String, Int>()
        Walker(out, ids).children(doc.body(), 0, 0)
        val title = doc.title().trim()
        if (useTitle && title.isNotEmpty() && out.none { it is MdBlock.Heading && it.level == 1 }) {
            out.add(0, MdBlock.Heading(1, InlineText(title), 0, 0))
            val shifted = ids.mapValues { it.value + 1 }
            ids.clear(); ids.putAll(shifted)
        }
        return Result(out, ids)
    }

    private val skipped = setOf(
        "script", "style", "noscript", "template", "head", "iframe", "object", "embed", "frame", "frameset",
        "canvas", "audio", "video", "source", "track", "map", "input", "select", "textarea", "option", "meta", "link", "svg", "math",
    )
    private val inline = setOf(
        "a", "b", "strong", "i", "em", "code", "kbd", "samp", "tt", "var", "s", "del", "strike", "u", "ins", "mark",
        "small", "sub", "sup", "span", "abbr", "cite", "q", "time", "label", "font", "big", "br", "wbr", "img", "button", "dfn", "bdi", "bdo",
    )

    private class Walker(val out: MutableList<MdBlock>, val ids: MutableMap<String, Int>) {
        fun children(parent: Element, indent: Int, quote: Int) {
            val run = Inline()
            fun flush() {
                val t = run.build()
                if (t.text.isNotBlank()) out += MdBlock.Paragraph(t, indent, quote)
                run.reset()
            }
            for (node in parent.childNodes()) {
                when {
                    node is TextNode -> run.text(node)
                    node !is Element -> Unit
                    node.normalName() in skipped -> Unit
                    node.normalName() == "img" && run.isBlank() -> { flush(); image(node, indent, quote) }
                    node.normalName() in inline -> run.node(node)
                    else -> { flush(); block(node, indent, quote) }
                }
            }
            flush()
        }

        private fun mark(e: Element) {
            val id = e.id().ifEmpty { e.attr("name") }
            if (id.isNotEmpty()) ids.putIfAbsent(id.lowercase(), out.size)
        }

        private fun image(e: Element, indent: Int, quote: Int) {
            out += MdBlock.Image(e.attr("src"), e.attr("alt").ifEmpty { e.attr("title") }, indent, quote)
        }

        fun block(e: Element, indent: Int, quote: Int) {
            when (val tag = e.normalName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    mark(e)
                    out += MdBlock.Heading(tag[1] - '0', inlineOf(e), indent, quote)
                }
                "p" -> {
                    mark(e)
                    val imgs = e.select("> img")
                    if (imgs.size == 1 && e.text().isBlank()) image(imgs.first()!!, indent, quote)
                    else inlineOf(e).takeIf { it.text.isNotBlank() }?.let { out += MdBlock.Paragraph(it, indent, quote) }
                }
                "pre" -> {
                    mark(e)
                    val code = e.selectFirst("> code")
                    val classes = (code?.className().orEmpty() + " " + e.className())
                    val info = Regex("(?:language|lang)-([\\w+#-]+)").find(classes)?.groupValues?.get(1).orEmpty()
                    out += MdBlock.CodeBlock(info, (code ?: e).wholeText().trimEnd('\n'), indent, quote)
                }
                "blockquote" -> { mark(e); children(e, indent, quote + 1) }
                "ul", "ol", "menu" -> list(e, tag == "ol", indent, quote)
                "table" -> table(e, indent, quote)
                "hr" -> out += MdBlock.Rule(indent, quote)
                "dl" -> for (c in e.children()) when (c.normalName()) {
                    "dt" -> out += MdBlock.Paragraph(bold(inlineOf(c)), indent, quote)
                    "dd" -> children(c, indent + 1, quote)
                }
                "summary" -> out += MdBlock.Paragraph(bold(inlineOf(e)), indent, quote)
                "figcaption", "caption" -> out += MdBlock.Paragraph(italic(inlineOf(e)), indent, quote)
                "li" -> listItem(e, "•", indent, quote)
                else -> { mark(e); children(e, indent, quote) }
            }
        }

        private fun list(e: Element, ordered: Boolean, indent: Int, quote: Int) {
            var n = e.attr("start").toIntOrNull() ?: 1
            for (li in e.children()) {
                if (li.normalName() != "li") { block(li, indent, quote); continue }
                listItem(li, if (ordered) "${n++}." else "•", indent, quote)
            }
        }

        private fun listItem(li: Element, marker: String, indent: Int, quote: Int) {
            mark(li)
            val box = li.selectFirst("> input[type=checkbox], > p > input[type=checkbox]")
            val checked = box?.let { it.hasAttr("checked") }
            // The leading inline run is the item's text; nested blocks follow, indented.
            val head = Inline()
            val rest = ArrayList<Node>()
            var inHead = true
            for (node in li.childNodes()) {
                val el = node as? Element
                when {
                    inHead && node is TextNode -> head.text(node)
                    inHead && el != null && el.normalName() in inline -> head.node(el)
                    inHead && el != null && el.normalName() == "p" && head.isBlank() -> { head.node(el); inHead = false }
                    el != null && el.normalName() in skipped -> Unit
                    else -> { inHead = false; rest += node }
                }
            }
            out += MdBlock.ListItem(marker, checked, head.build(), indent, quote)
            if (rest.isNotEmpty()) {
                val holder = Element("div")
                rest.forEach { holder.appendChild(it.clone()) }
                children(holder, indent + 1, quote)
            }
        }

        private fun table(e: Element, indent: Int, quote: Int) {
            val rows = e.select("tr").filter { it.closest("table") === e }
            if (rows.isEmpty()) return
            val first = rows.first()
            val hasHeader = first.parent()?.normalName() == "thead" || first.children().all { it.normalName() == "th" }
            fun cells(r: Element) = r.children().filter { it.normalName() == "td" || it.normalName() == "th" }
            val header = if (hasHeader) cells(first).map { inlineOf(it) } else emptyList()
            val body = (if (hasHeader) rows.drop(1) else rows).map { r -> cells(r).map { inlineOf(it) } }
            val align = cells(first).map { c ->
                val a = (c.attr("align") + " " + c.attr("style")).lowercase()
                when {
                    "center" in a -> CellAlign.CENTER
                    "right" in a -> CellAlign.END
                    else -> CellAlign.START
                }
            }
            out += MdBlock.Table(header, body, align, indent, quote)
        }
    }

    private fun bold(t: InlineText) = t.copy(spans = t.spans + InlineSpan(0, t.text.length, InlineStyle.BOLD))
    private fun italic(t: InlineText) = t.copy(spans = t.spans + InlineSpan(0, t.text.length, InlineStyle.ITALIC))

    private fun inlineOf(e: Element): InlineText = Inline().apply { e.childNodes().forEach { child(it) } }.build()

    /** Collects inline content with HTML whitespace rules (runs of spaces collapse). */
    private class Inline {
        private val sb = StringBuilder()
        private val spans = ArrayList<InlineSpan>()

        fun isBlank() = sb.isBlank()
        fun reset() { sb.setLength(0); spans.clear() }

        fun text(t: TextNode) = append(t.wholeText)

        private fun append(raw: String) {
            for (c in raw) {
                val ws = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C'
                if (ws) {
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' ')
                } else sb.append(c)
            }
        }

        fun node(e: Element) = child(e)

        fun child(n: Node) {
            when (n) {
                is TextNode -> text(n)
                is Element -> {
                    val tag = n.normalName()
                    if (tag in skipped) return
                    if (tag == "br") {
                        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                        sb.append('\n'); return
                    }
                    if (tag == "img") {
                        val alt = n.attr("alt").ifEmpty { n.attr("title") }
                        if (alt.isNotEmpty()) {
                            val s = sb.length
                            sb.append('[').append(alt).append(']')
                            spans += InlineSpan(s, sb.length, InlineStyle.ITALIC)
                        }
                        return
                    }
                    val start = sb.length
                    n.childNodes().forEach { child(it) }
                    val style = when (tag) {
                        "b", "strong" -> InlineStyle.BOLD
                        "i", "em", "cite", "dfn", "var" -> InlineStyle.ITALIC
                        "code", "kbd", "samp", "tt" -> InlineStyle.CODE
                        "s", "del", "strike" -> InlineStyle.STRIKE
                        "a" -> if (n.hasAttr("href")) InlineStyle.LINK else null
                        else -> null
                    }
                    if (style != null && sb.length > start) spans += InlineSpan(start, sb.length, style, if (style == InlineStyle.LINK) n.attr("href") else null)
                }
            }
        }

        fun build(): InlineText {
            // Trim, shifting spans.
            var lead = 0
            while (lead < sb.length && (sb[lead] == ' ' || sb[lead] == '\n')) lead++
            var end = sb.length
            while (end > lead && (sb[end - 1] == ' ' || sb[end - 1] == '\n')) end--
            val text = sb.substring(lead, end)
            val shifted = spans.mapNotNull { s ->
                val a = (s.start - lead).coerceIn(0, text.length)
                val b = (s.end - lead).coerceIn(0, text.length)
                if (b > a) s.copy(start = a, end = b) else null
            }
            return InlineText(text, shifted)
        }
    }
}
