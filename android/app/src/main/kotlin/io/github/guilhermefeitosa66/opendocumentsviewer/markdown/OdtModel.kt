package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import io.github.guilhermefeitosa66.opendocumentsviewer.formats.ZipFiles
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * OpenDocument text (.odt, .ott, and flat .fodt) read with no library: content.xml with the styles
 * of styles.xml, flattened into the same block list as Word documents ([DocxModel.Result]).
 */
object OdtModel {
    class NotOdt(message: String) : Exception(message)

    fun parse(bytes: ByteArray): DocxModel.Result {
        if (!ZipFiles.isZip(bytes)) {
            // Flat ODF: one XML file with styles and body.
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("office:document")) throw NotOdt("not an OpenDocument file")
            val doc = xml(text)
            return Reader(doc, doc).read()
        }
        val files = ZipFiles.entries(bytes) { it.endsWith(".xml") }
        if (files["META-INF/manifest.xml"]?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) {
            throw NotOdt("the document is password protected")
        }
        val content = files["content.xml"] ?: throw NotOdt("no content.xml")
        val styles = files["styles.xml"]?.let { xml(String(it, Charsets.UTF_8)) }
        return Reader(xml(String(content, Charsets.UTF_8)), styles).read()
    }

    private fun xml(s: String): org.jsoup.nodes.Document = Jsoup.parse(s, "", Parser.xmlParser())

    private class TextStyle(
        val parent: String?,
        val bold: Boolean?, val italic: Boolean?, val underline: Boolean?, val strike: Boolean?,
        val color: Int?, val points: Float?, val mono: Boolean?,
        val align: BlockAlign?, val breakBefore: Boolean, val outline: Int?,
    )

    private class ListLevel(val bullet: Boolean, val format: String, val prefix: String, val suffix: String, val start: Int, val display: Int)

    private class Reader(val content: org.jsoup.nodes.Document, val stylesDoc: org.jsoup.nodes.Document?) {
        val styles = HashMap<String, TextStyle>()
        val defaults = HashMap<String, TextStyle>() // family → default style
        val fonts = HashMap<String, String>() // font name → family
        val listStyles = HashMap<String, Map<Int, ListLevel>>()
        val out = ArrayList<MdBlock>()
        val anchors = HashMap<String, Int>()
        var partial = false
        val sizes = HashMap<Int, Int>()
        val notes = ArrayList<InlineText>()

        fun read(): DocxModel.Result {
            for (d in listOfNotNull(stylesDoc, content).distinct()) {
                d.getElementsByTag("style:font-face").forEach { f -> fonts[f.attr("style:name")] = f.attr("svg:font-family").trim('\'', '"') }
                d.getElementsByTag("style:default-style").forEach { s -> defaults[s.attr("style:family")] = style(s) }
                d.getElementsByTag("style:style").forEach { s -> styles[s.attr("style:name")] = style(s) }
                d.getElementsByTag("text:list-style").forEach { ls -> listStyles[ls.attr("style:name")] = listLevels(ls) }
            }
            val text = content.getElementsByTag("office:text").firstOrNull() ?: throw NotOdt("no text body")
            val master = stylesDoc?.getElementsByTag("style:master-page")?.firstOrNull()
            master?.children()?.firstOrNull { it.tagName() == "style:header" }?.let { h ->
                val before = out.size
                blocks(h, 0, null)
                if (out.size > before) out += MdBlock.Rule(0, 0)
            }
            blocks(text, 0, null)
            if (notes.isNotEmpty()) {
                out += MdBlock.Rule(0, 0)
                notes.forEachIndexed { i, n ->
                    anchors["fn-${i + 1}"] = out.size
                    out += MdBlock.Paragraph(n, 0, 0)
                }
            }
            master?.children()?.firstOrNull { it.tagName() == "style:footer" }?.let { f ->
                val before = out.size
                out += MdBlock.Rule(0, 0)
                blocks(f, 0, null)
                if (out.size == before + 1) out.removeAt(out.size - 1)
            }
            return DocxModel.Result(OfficeText.scaleSizes(out, sizes), anchors, partial)
        }

        fun style(s: Element): TextStyle {
            val t = s.children().firstOrNull { it.tagName() == "style:text-properties" }
            val p = s.children().firstOrNull { it.tagName() == "style:paragraph-properties" }
            fun a(e: Element?, n: String) = e?.attr(n)?.takeIf { it.isNotEmpty() }
            val weight = a(t, "fo:font-weight")
            val fontName = a(t, "style:font-name")
            val family = fontName?.let { fonts[it] ?: it } ?: a(t, "fo:font-family")
            return TextStyle(
                parent = a(s, "style:parent-style-name"),
                bold = weight?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
                italic = a(t, "fo:font-style")?.let { it == "italic" || it == "oblique" },
                underline = a(t, "style:text-underline-style")?.let { it != "none" },
                strike = a(t, "style:text-line-through-style")?.let { it != "none" },
                color = a(t, "fo:color")?.removePrefix("#")?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it },
                points = a(t, "fo:font-size")?.let(::points),
                mono = family?.lowercase()?.let { f -> listOf("courier", "mono", "consolas", "menlo").any { it in f } },
                align = when (a(p, "fo:text-align")) {
                    "center" -> BlockAlign.CENTER
                    "end", "right" -> BlockAlign.END
                    "justify" -> BlockAlign.JUSTIFY
                    "start", "left" -> BlockAlign.START
                    else -> null
                },
                breakBefore = a(p, "fo:break-before") == "page",
                outline = a(s, "style:default-outline-level")?.toIntOrNull(),
            )
        }

        fun points(v: String): Float? {
            val n = v.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: return null
            return when {
                v.endsWith("pt") -> n
                v.endsWith("cm") -> n * 28.35f
                v.endsWith("mm") -> n * 2.835f
                v.endsWith("in") -> n * 72f
                v.endsWith("px") -> n * 0.75f
                else -> null
            }
        }

        fun chain(name: String?, family: String): List<TextStyle> {
            val list = ArrayList<TextStyle>()
            var cur = name
            while (cur != null && list.size < 20) {
                val s = styles[cur] ?: break
                list.add(0, s)
                cur = s.parent
            }
            defaults[family]?.let { list.add(0, it) }
            return list
        }

        data class Props(
            val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
            val color: Int = 0, val points: Float = 0f, val mono: Boolean = false,
        )

        fun props(base: Props, chain: List<TextStyle>): Props {
            var p = base
            for (s in chain) {
                s.bold?.let { p = p.copy(bold = it) }
                s.italic?.let { p = p.copy(italic = it) }
                s.underline?.let { p = p.copy(underline = it) }
                s.strike?.let { p = p.copy(strike = it) }
                s.color?.let { p = p.copy(color = it) }
                s.points?.let { p = p.copy(points = it) }
                s.mono?.let { p = p.copy(mono = it) }
            }
            return p
        }

        fun listLevels(ls: Element): Map<Int, ListLevel> {
            val m = HashMap<Int, ListLevel>()
            for (l in ls.children()) {
                val level = l.attr("text:level").toIntOrNull() ?: continue
                m[level] = when (l.tagName()) {
                    "text:list-level-style-number" -> ListLevel(
                        false, l.attr("style:num-format").ifEmpty { "" }, l.attr("style:num-prefix"), l.attr("style:num-suffix"),
                        l.attr("text:start-value").toIntOrNull() ?: 1, l.attr("text:display-levels").toIntOrNull() ?: 1,
                    )
                    else -> ListLevel(true, "", "", "", 1, 1)
                }
            }
            return m
        }

        /** Counters per list style, for continued numbering. */
        val counters = HashMap<String, IntArray>()

        fun blocks(parent: Element, depth: Int, list: ListContext?) {
            for (e in parent.children()) {
                when (e.tagName()) {
                    "text:p" -> paragraph(e, 0, list)
                    "text:h" -> paragraph(e, (e.attr("text:outline-level").toIntOrNull() ?: 1).coerceIn(1, 6), list)
                    "text:list" -> list(e, list)
                    "table:table" -> table(e)
                    "text:section", "text:index-body", "office:forms", "text:table-of-content" -> blocks(e, depth, list)
                    "text:tracked-changes", "office:annotation", "text:sequence-decls", "office:forms-hidden" -> Unit
                }
            }
        }

        class ListContext(val style: String?, val level: Int, var marker: String?)

        fun list(e: Element, outer: ListContext?) {
            val style = e.attr("text:style-name").ifEmpty { null } ?: outer?.style
            val level = (outer?.level ?: 0) + 1
            val levels = style?.let { listStyles[it] }
            val def = levels?.get(level)
            val c = counters.getOrPut(style ?: "") { IntArray(11) }
            if (e.attr("text:continue-numbering") != "true" && outer == null && e.attr("text:continue-list").isEmpty()) {
                // A new top-level list starts again, unless it says it continues.
                for (i in c.indices) c[i] = 0
            }
            for (item in e.children().filter { it.tagName() == "text:list-item" || it.tagName() == "text:list-header" }) {
                val marker = when {
                    item.tagName() == "text:list-header" -> ""
                    def == null || def.bullet -> when ((level - 1) % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
                    def.format.isEmpty() -> ""
                    else -> {
                        item.attr("text:start-value").toIntOrNull()?.let { c[level] = it - 1 }
                        if (c[level] == 0) c[level] = def.start - 1
                        c[level]++
                        for (d in level + 1 until c.size) c[d] = 0
                        val parts = (level - def.display + 1..level).filter { it >= 1 }.map { l ->
                            val f = levels[l]?.format?.ifEmpty { "1" } ?: "1"
                            OfficeText.number(c[l].coerceAtLeast(1), odfFormat(f))
                        }
                        def.prefix + parts.joinToString(".") + def.suffix
                    }
                }
                val ctx = ListContext(style, level, marker)
                for (child in item.children()) {
                    when (child.tagName()) {
                        "text:p", "text:h" -> { paragraph(child, 0, ctx); ctx.marker = null }
                        "text:list" -> list(child, ctx)
                        else -> blocks(child, 0, ctx)
                    }
                }
            }
        }

        fun odfFormat(f: String) = when (f) { "a" -> "lowerLetter"; "A" -> "upperLetter"; "i" -> "lowerRoman"; "I" -> "upperRoman"; else -> "decimal" }

        fun paragraph(p: Element, headingLevel: Int, list: ListContext?) {
            val styleName = p.attr("text:style-name").ifEmpty { null }
            val chain = chain(styleName, "paragraph")
            if (chain.any { it.breakBefore } && out.isNotEmpty() && out.last() !is MdBlock.Rule) out += MdBlock.Rule(0, 0)
            val level = if (headingLevel > 0) headingLevel else chain.lastOrNull { it.outline != null }?.outline?.coerceIn(1, 6)
                ?.takeIf { styleName?.startsWith("Heading") == true } ?: 0
            val align = chain.lastOrNull { it.align != null }?.align ?: BlockAlign.START
            val base = props(Props(), chain)
            val builder = InlineBuilder()
            val pieces = ArrayList<Any>() // InlineText or MdBlock.Image
            var current = builder
            val bookmarks = ArrayList<String>()
            fun inline(n: Node, props: Props, link: String?) {
                if (n is TextNode) {
                    val s = n.wholeText.replace(Regex("[\\n\\r\\t ]+"), " ")
                    if (s.isNotEmpty()) emit(current, s, props, link)
                    return
                }
                val e = n as? Element ?: return
                when (e.tagName()) {
                    "text:span" -> {
                        val st = e.attr("text:style-name").ifEmpty { null }
                        val p2 = props(props, chain(st, "text").drop(if (defaults["text"] != null) 1 else 0))
                        e.childNodes().forEach { inline(it, p2, link) }
                    }
                    "text:a" -> e.childNodes().forEach { inline(it, props, e.attr("xlink:href").ifEmpty { null }) }
                    "text:s" -> emit(current, " ".repeat((e.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 200)), props, link)
                    "text:tab" -> emit(current, "\t", props, link)
                    "text:line-break" -> emit(current, "\n", props, link)
                    "text:note" -> {
                        val body = e.children().firstOrNull { it.tagName() == "text:note-body" }
                        if (body != null) {
                            val n2 = notes.size + 1
                            current.append("[$n2]", InlineSpan(0, 0, InlineStyle.LINK, url = "#fn-$n2"))
                            notes += InlineText("$n2. " + body.text().trim())
                        }
                    }
                    "text:bookmark", "text:bookmark-start" -> bookmarks += e.attr("text:name")
                    "draw:frame" -> frame(e)?.let { img ->
                        val t = current.build()
                        if (t.text.isNotBlank()) pieces += t
                        pieces += img
                        current = InlineBuilder()
                    }
                    "draw:a" -> e.childNodes().forEach { inline(it, props, link) }
                    "text:soft-page-break", "office:annotation", "office:annotation-end", "text:change", "text:change-start", "text:change-end" -> Unit
                    else -> e.childNodes().forEach { inline(it, props, link) }
                }
            }
            p.childNodes().forEach { inline(it, base, null) }
            val tail = current.build()
            if (tail.text.isNotBlank()) pieces += tail
            bookmarks.forEach { anchors.putIfAbsent(it, out.size) }
            var first = true
            for (piece in pieces) {
                if (piece is MdBlock) { out += piece; first = false; continue }
                val t = (piece as InlineText).trimmed()
                when {
                    level > 0 -> out += MdBlock.Heading(level, t, 0, 0, if (align == BlockAlign.JUSTIFY) BlockAlign.START else align)
                    list != null && first && list.marker != null ->
                        if (list.marker!!.isEmpty()) out += MdBlock.Paragraph(t, list.level, 0, align)
                        else out += MdBlock.ListItem(list.marker!!, null, t, list.level - 1, 0)
                    list != null -> out += MdBlock.Paragraph(t, list.level, 0, align)
                    else -> out += MdBlock.Paragraph(t, 0, 0, align)
                }
                first = false
            }
        }

        fun emit(b: InlineBuilder, s: String, p: Props, link: String?) {
            if (p.points > 0) sizes[(p.points * 2).toInt()] = (sizes[(p.points * 2).toInt()] ?: 0) + s.length
            val st = ArrayList<InlineSpan>()
            if (p.bold) st += InlineSpan(0, 0, InlineStyle.BOLD)
            if (p.italic) st += InlineSpan(0, 0, InlineStyle.ITALIC)
            if (p.underline && link == null) st += InlineSpan(0, 0, InlineStyle.UNDERLINE)
            if (p.strike) st += InlineSpan(0, 0, InlineStyle.STRIKE)
            if (p.mono) st += InlineSpan(0, 0, InlineStyle.CODE)
            if (p.color != 0 && p.color != 0xFF000000.toInt()) st += InlineSpan(0, 0, InlineStyle.COLOR, color = p.color)
            if (p.points > 0) st += InlineSpan(0, 0, InlineStyle.SIZE, scale = p.points * 2)
            link?.let { st += InlineSpan(0, 0, InlineStyle.LINK, url = it) }
            b.appendStyled(s, st)
        }

        fun frame(f: Element): MdBlock? {
            val img = f.children().firstOrNull { it.tagName() == "draw:image" }
            if (img != null) {
                val href = img.attr("xlink:href")
                val alt = f.children().firstOrNull { it.tagName() == "svg:title" || it.tagName() == "svg:desc" }?.text().orEmpty()
                if (href.isEmpty() || href.contains("://")) { partial = true; return null }
                return MdBlock.Image(ZipFiles.IMAGE_SCHEME + ZipFiles.resolve("", href), alt, 0, 0)
            }
            f.children().firstOrNull { it.tagName() == "draw:text-box" }?.let { box -> blocks(box, 0, null) }
                ?: run { partial = true }
            return null
        }

        fun table(t: Element) {
            val rows = ArrayList<List<InlineText>>()
            fun rowsOf(e: Element) {
                for (c in e.children()) {
                    when (c.tagName()) {
                        "table:table-row" -> {
                            val cells = ArrayList<InlineText>()
                            for (cell in c.children()) {
                                val repeat = (cell.attr("table:number-columns-repeated").toIntOrNull() ?: 1).coerceAtMost(50)
                                val text = if (cell.tagName() == "table:covered-table-cell") InlineText("") else cellText(cell)
                                repeat(repeat) { cells += text }
                            }
                            val rep = (c.attr("table:number-rows-repeated").toIntOrNull() ?: 1).coerceAtMost(50)
                            repeat(rep) { rows += cells }
                        }
                        "table:table-header-rows", "table:table-rows", "table:table-row-group" -> rowsOf(c)
                    }
                }
            }
            rowsOf(t)
            // Trailing empty cells (spreadsheet-like tables) are dropped.
            val width = rows.maxOfOrNull { r -> r.indexOfLast { it.text.isNotEmpty() } + 1 } ?: 0
            if (rows.isEmpty() || width == 0) return
            val padded = rows.map { r -> (r.take(width) + List((width - r.size).coerceAtLeast(0)) { InlineText("") }) }
            out += MdBlock.Table(padded.first(), padded.drop(1), List(width) { CellAlign.START }, 0, 0)
        }

        fun cellText(cell: Element): InlineText {
            val b = InlineBuilder()
            var first = true
            for (p in cell.children()) {
                if (p.tagName() != "text:p" && p.tagName() != "text:h" && p.tagName() != "text:list") continue
                if (!first) b.append("\n", null)
                first = false
                val chain = chain(p.attr("text:style-name").ifEmpty { null }, "paragraph")
                val base = props(Props(), chain)
                fun walk(n: Node, props: Props, link: String?) {
                    if (n is TextNode) { emit(b, n.wholeText.replace(Regex("\\s+"), " "), props, link); return }
                    val e = n as? Element ?: return
                    when (e.tagName()) {
                        "text:span" -> {
                            val p2 = props(props, chain(e.attr("text:style-name").ifEmpty { null }, "text").drop(if (defaults["text"] != null) 1 else 0))
                            e.childNodes().forEach { walk(it, p2, link) }
                        }
                        "text:a" -> e.childNodes().forEach { walk(it, props, e.attr("xlink:href").ifEmpty { null }) }
                        "text:s" -> emit(b, " ".repeat((e.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 200)), props, link)
                        "text:line-break" -> b.append("\n", null)
                        "text:note", "office:annotation", "draw:frame" -> Unit
                        else -> e.childNodes().forEach { walk(it, props, link) }
                    }
                }
                p.childNodes().forEach { walk(it, base, null) }
            }
            return b.build().trimmed()
        }
    }
}
