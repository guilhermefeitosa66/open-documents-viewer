package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenDocument presentations (.odp, .otp, flat .fodp) and drawings (.odg, pages shown as slides)
 * into the presentation model. Frames (text boxes, pictures, tables), custom shapes, rectangles,
 * ellipses, lines and groups, with fill, stroke and text properties from the styles (with
 * inheritance). Placeholders without a position take it from their master page; the master's own
 * drawings are drawn behind. Ported from the Linux app's reader.
 */
object OdpReader {
    class NotOdp(message: String) : Exception(message)

    private const val EMU_PER_PT = 12700
    private val SKIPPED = setOf("header", "footer", "date-time", "page-number", "handout")
    /** Shapes kept from PowerPoint ("ooxml-roundRect"). */
    private val PRESET_LIKE = mapOf("roundRect" to "roundRect", "rect" to "rect", "ellipse" to "ellipse")
    private val SHAPE_TYPES = mapOf(
        "rectangle" to "rect", "round-rectangle" to "roundRect", "ellipse" to "ellipse", "circle" to "ellipse",
        "flowchart-process" to "rect", "flowchart-alternate-process" to "roundRect", "flowchart-connector" to "ellipse",
    )

    fun read(bytes: ByteArray): Presentation {
        if (!ZipFiles.isZip(bytes)) {
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains("office:document")) throw NotOdp("not an OpenDocument file")
            val doc = Jsoup.parse(text, "", Parser.xmlParser())
            return Reader(doc, null).build()
        }
        val files = ZipFiles.entries(bytes) { it.endsWith(".xml") }
        if (files["META-INF/manifest.xml"]?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) {
            throw NotOdp("the presentation is password protected")
        }
        val content = files["content.xml"] ?: throw NotOdp("content.xml is missing")
        val styles = files["styles.xml"]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) }
        return Reader(Jsoup.parse(String(content, Charsets.UTF_8), "", Parser.xmlParser()), styles).build()
    }

    internal fun pt(v: String?): Float? {
        v ?: return null
        val m = Regex("""(-?[\d.]+)\s*(cm|mm|in|pt|px|pc)?""").find(v) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return when (m.groupValues[2]) { "cm" -> n * 28.3465f; "mm" -> n * 2.83465f; "in" -> n * 72f; "px" -> n * 0.75f; "pc" -> n * 12f; else -> n }
    }

    private fun emu(v: String?): Long = pt(v)?.let { (it * EMU_PER_PT).toLong() } ?: 0L

    private fun color(v: String?): Int? =
        v?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.substring(1)?.toInt(16)?.let { 0xFF000000.toInt() or it }

    private class Reader(val content: org.jsoup.nodes.Document, val stylesDoc: org.jsoup.nodes.Document?) {
        val styles = HashMap<String, HashMap<String, Any?>>()
        val listStyles = HashMap<String, Map<Int, List<String>>>()
        val fillImages = HashMap<String, String>()
        val layouts = HashMap<String, Pair<Long, Long>>()
        val masters = HashMap<String, Element>()
        var defaults = HashMap<String, Any?>()

        init {
            for (root in listOfNotNull(stylesDoc, content)) load(root)
        }

        fun load(root: org.jsoup.nodes.Document) {
            for (img in root.getElementsByTag("draw:fill-image")) {
                val href = img.attr("xlink:href")
                if (href.isNotEmpty()) fillImages[img.attr("draw:name")] = href
                else img.getElementsByTag("office:binary-data").firstOrNull()?.text()?.let { fillImages[img.attr("draw:name")] = "data:image/png;base64," + it.filterNot(Char::isWhitespace) }
            }
            for (pl in root.getElementsByTag("style:page-layout")) {
                val p = pl.getElementsByTag("style:page-layout-properties").firstOrNull() ?: continue
                if (p.attr("fo:page-width").isNotEmpty()) layouts[pl.attr("style:name")] = emu(p.attr("fo:page-width")) to emu(p.attr("fo:page-height"))
            }
            for (m in root.getElementsByTag("style:master-page")) masters[m.attr("style:name")] = m
            for (st in root.getElementsByTag("style:default-style")) if (st.attr("style:family") == "graphic") defaults = props(st)
            for (st in root.getElementsByTag("style:style")) {
                val p = props(st)
                p["parent"] = st.attr("style:parent-style-name").ifEmpty { null }
                styles[st.attr("style:name")] = p
            }
            for (ls in root.getElementsByTag("text:list-style")) listStyles[ls.attr("style:name")] = levels(ls)
        }

        fun levels(ls: Element): Map<Int, List<String>> {
            val out = HashMap<Int, List<String>>()
            for (l in ls.children()) {
                val level = l.attr("text:level").toIntOrNull() ?: 1
                when (l.tagName()) {
                    "text:list-level-style-number" -> out[level] = listOf("number", l.attr("style:num-format").ifEmpty { "1" }, l.attr("style:num-suffix").ifEmpty { "." })
                    "text:list-level-style-bullet" -> out[level] = listOf("bullet", l.attr("text:bullet-char").ifEmpty { "•" })
                }
            }
            return out
        }

        fun props(st: Element): HashMap<String, Any?> {
            val p = HashMap<String, Any?>()
            fun child(t: String) = st.children().firstOrNull { it.tagName() == t }
            fun a(e: Element?, n: String) = e?.attr(n)?.ifEmpty { null }
            child("style:graphic-properties")?.let { g ->
                a(g, "draw:fill")?.let { p["fill"] = it }
                color(a(g, "draw:fill-color"))?.let { p["fill_color"] = it }
                a(g, "draw:stroke")?.let { p["stroke"] = it }
                color(a(g, "svg:stroke-color"))?.let { p["stroke_color"] = it }
                pt(a(g, "svg:stroke-width"))?.let { p["stroke_width"] = it }
                a(g, "draw:textarea-vertical-align")?.let { p["valign"] = it }
                for ((k, attr) in listOf("pad_l" to "fo:padding-left", "pad_t" to "fo:padding-top", "pad_r" to "fo:padding-right", "pad_b" to "fo:padding-bottom")) {
                    a(g, attr)?.let { p[k] = emu(it) }
                }
                g.children().firstOrNull { it.tagName() == "text:list-style" }?.let { p["list"] = levels(it) }
            }
            child("style:drawing-page-properties")?.let { d ->
                a(d, "draw:fill")?.let { p["fill"] = it }
                color(a(d, "draw:fill-color"))?.let { p["fill_color"] = it }
                a(d, "draw:fill-image-name")?.let { p["fill_image"] = it }
                a(d, "presentation:visibility")?.let { p["visibility"] = it }
            }
            child("style:text-properties")?.let { t ->
                a(t, "fo:font-weight")?.let { p["bold"] = it in setOf("bold", "600", "700", "800", "900") }
                a(t, "fo:font-style")?.let { p["italic"] = it in setOf("italic", "oblique") }
                a(t, "style:text-underline-style")?.let { p["underline"] = it != "none" }
                a(t, "style:text-line-through-style")?.let { p["strike"] = it != "none" }
                color(a(t, "fo:color"))?.let { p["color"] = it }
                a(t, "fo:font-size")?.takeIf { !it.endsWith("%") }?.let { pt(it)?.let { s -> p["size"] = s } }
                (a(t, "style:font-name") ?: a(t, "fo:font-family"))?.lowercase()?.let { f -> p["mono"] = listOf("courier", "mono", "consolas").any { it in f } }
            }
            child("style:paragraph-properties")?.let { pp ->
                a(pp, "fo:text-align")?.let { p["align"] = when (it) { "center" -> 'c'; "end", "right" -> 'r'; "justify" -> 'j'; else -> 'l' } }
            }
            return p
        }

        /** The first value of [key] along the styles (most specific first) and their parents. */
        fun prop(names: List<String?>, key: String, default: Any? = null): Any? {
            for (n0 in names) {
                var name = n0
                var seen = 0
                while (name != null && styles.containsKey(name) && seen++ < 16) {
                    val s = styles[name]!!
                    if (s.containsKey(key)) return s[key]
                    name = s["parent"] as String?
                }
            }
            return defaults[key] ?: default
        }

        fun build(): Presentation {
            var pres = content.getElementsByTag("office:presentation").firstOrNull()
            val drawing = pres == null
            if (drawing) pres = content.getElementsByTag("office:drawing").firstOrNull()
            pres ?: throw NotOdp("this file has no presentation body")
            var width = 9144000L
            var height = 6858000L
            var firstMaster: Element? = null
            val slides = ArrayList<Slide>()
            for (page in pres.children().filter { it.tagName() == "draw:page" }) {
                val master = masters[page.attr("draw:master-page-name")]
                if (firstMaster == null && master != null) {
                    firstMaster = master
                    layouts[master.attr("style:page-layout-name")]?.let { (w, h) -> if (w > 0 && h > 0) { width = w; height = h } }
                }
                val slide = page(page, master)
                slides += if (drawing && slide.title.isEmpty()) Slide(slide.shapes, slide.background, slide.backgroundImage, slide.notes, page.attr("draw:name"), slide.hidden) else slide
            }
            if (slides.isEmpty()) throw NotOdp("no slides")
            return Presentation(width, height, slides)
        }

        fun page(page: Element, master: Element?): Slide {
            val pageStyles = listOf(page.attr("draw:style-name").ifEmpty { null }, master?.attr("draw:style-name")?.ifEmpty { null })
            val hidden = prop(pageStyles.take(1), "visibility") == "hidden"
            var bg = 0xFFFFFFFF.toInt()
            var bgImage: String? = null
            when (prop(pageStyles, "fill")) {
                "solid" -> bg = (prop(pageStyles, "fill_color") as Int?) ?: bg
                "bitmap" -> bgImage = fillImages[prop(pageStyles, "fill_image") as String?]?.let { normalize(it) }
            }
            val shapes = ArrayList<SlideShape>()
            master?.children()?.forEach { el ->
                if (el.attr("presentation:class").isNotEmpty() || el.attr("presentation:placeholder") == "true") return@forEach
                shapes += shapes(el, master)
            }
            var notes = ""
            var title = ""
            for (el in page.children()) {
                if (el.tagName() == "presentation:notes") {
                    notes = el.children().filter { it.tagName() == "draw:frame" && it.attr("presentation:class") == "notes" }
                        .joinToString("\n") { f -> f.getElementsByTag("text:p").joinToString("\n") { it.text().trim() } }.trim()
                    continue
                }
                val cls = el.attr("presentation:class")
                if (cls in SKIPPED) continue
                if (cls == "title" && title.isEmpty()) title = el.text().trim().split(Regex("\\s+")).joinToString(" ")
                shapes += shapes(el, master)
            }
            return Slide(shapes, bg, bgImage, notes, title, hidden)
        }

        fun normalize(href: String) = if (href.startsWith("data:")) href else ZipFiles.resolve("", href.removePrefix("./"))

        fun masterFrame(master: Element?, cls: String?): Element? {
            if (master == null || cls.isNullOrEmpty()) return null
            master.children().firstOrNull { it.attr("presentation:class") == cls }?.let { return it }
            return if (cls == "subtitle") masterFrame(master, "outline") else null
        }

        /** (x, y, w, h, rotation degrees), or null when the shape has no place. */
        fun geometry(el: Element, master: Element?): DoubleArray? {
            val w = emu(el.attr("svg:width")).toDouble()
            val h = emu(el.attr("svg:height")).toDouble()
            val transform = el.attr("draw:transform")
            if (transform.isNotEmpty()) {
                val rot = Regex("""rotate\s*\(\s*([-\d.eE]+)\s*\)""").find(transform)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                val tr = Regex("""translate\s*\(\s*([-\d.]+\w*)\s*[ ,]\s*([-\d.]+\w*)\s*\)""").find(transform)
                val tx = tr?.let { emu(it.groupValues[1]).toDouble() } ?: 0.0
                val ty = tr?.let { emu(it.groupValues[2]).toDouble() } ?: 0.0
                // Rotated about its top-left corner, then moved: find its center.
                val cx = w / 2 * cos(rot) + h / 2 * sin(rot) + tx
                val cy = -w / 2 * sin(rot) + h / 2 * cos(rot) + ty
                return doubleArrayOf(cx - w / 2, cy - h / 2, w, h, -Math.toDegrees(rot))
            }
            if (el.attr("svg:x").isEmpty() && (w == 0.0 || h == 0.0)) {
                return masterFrame(master, el.attr("presentation:class"))?.let { geometry(it, null) }
            }
            return doubleArrayOf(emu(el.attr("svg:x")).toDouble(), emu(el.attr("svg:y")).toDouble(), w, h, 0.0)
        }

        fun shapes(el: Element, master: Element?): List<SlideShape> {
            when (el.tagName()) {
                "draw:g" -> return el.children().flatMap { shapes(it, master) }
                "draw:line", "draw:connector" -> {
                    val x1 = emu(el.attr("svg:x1")); val y1 = emu(el.attr("svg:y1"))
                    val x2 = emu(el.attr("svg:x2")); val y2 = emu(el.attr("svg:y2"))
                    val names = listOf(el.attr("draw:style-name").ifEmpty { null })
                    return listOf(SlideShape("line", x1, y1, x2 - x1, y2 - y1, line = (prop(names, "stroke_color") as Int?) ?: 0xFF404040.toInt(), lineWidth = (prop(names, "stroke_width") as Float?) ?: 1f))
                }
                "draw:frame", "draw:custom-shape", "draw:rect", "draw:ellipse", "draw:circle" -> Unit
                else -> return emptyList()
            }
            val g = geometry(el, master) ?: return emptyList()
            val x = g[0].toLong(); val y = g[1].toLong(); val w = g[2].toLong(); val h = g[3].toLong(); val rot = g[4].toFloat()
            val cls = el.attr("presentation:class").ifEmpty { null }
            val names = arrayListOf(el.attr("presentation:style-name").ifEmpty { null }, el.attr("draw:style-name").ifEmpty { null })
            masterFrame(master, cls)?.let { f -> names += f.attr("presentation:style-name").ifEmpty { null }; names += f.attr("draw:style-name").ifEmpty { null } }
            val body: Element?
            val kind: String
            if (el.tagName() == "draw:frame") {
                // A table comes with a preview picture: the table wins.
                el.children().firstOrNull { it.tagName() == "table:table" }?.let { t ->
                    val rows = t.getElementsByTag("table:table-row").map { r ->
                        r.children().filter { it.tagName() == "table:table-cell" || it.tagName() == "table:covered-table-cell" }.map { paragraphs(it, emptyList(), cls, false) }
                    }
                    return listOf(SlideShape("table", x, y, w, h, rows = rows, line = 0xFF808080.toInt()))
                }
                val image = el.children().firstOrNull { it.tagName() == "draw:image" }
                if (image != null && el.children().none { it.tagName() == "draw:text-box" }) {
                    val href = image.attr("xlink:href")
                    val src = if (href.isNotEmpty()) normalize(href) else image.getElementsByTag("office:binary-data").firstOrNull()?.text()?.let { "data:image/png;base64," + it.filterNot(Char::isWhitespace) }
                    return listOf(SlideShape("image", x, y, w, h, image = src, rotation = rot))
                }
                if (el.attr("presentation:placeholder") == "true" && el.text().isBlank()) return emptyList()
                body = el.children().firstOrNull { it.tagName() == "draw:text-box" }
                kind = "text"
            } else {
                body = el
                kind = when (el.tagName()) {
                    "draw:rect" -> "rect"
                    "draw:ellipse", "draw:circle" -> "ellipse"
                    else -> {
                        val type = el.children().firstOrNull { it.tagName() == "draw:enhanced-geometry" }?.attr("draw:type").orEmpty()
                        val t = type.removePrefix("ooxml-")
                        SHAPE_TYPES[t] ?: PRESET_LIKE[t] ?: if ("ellipse" in t) "ellipse" else "rect"
                    }
                }
            }
            val frame = el.tagName() == "draw:frame"
            val fillKind = prop(names, "fill", if (frame) "none" else "solid")
            val fill = if (fillKind == "solid" || fillKind == "gradient") (prop(names, "fill_color", 0xFF729FCF.toInt()) as Int) else 0
            val line = if (prop(names, "stroke", if (frame) "none" else "solid") != "none") (prop(names, "stroke_color") as Int?) ?: 0xFF3465A4.toInt() else 0
            val valign = prop(names, "valign", if (kind != "text") "middle" else "top")
            val insets = longArrayOf(
                (prop(names, "pad_l") as Long?) ?: 91440, (prop(names, "pad_t") as Long?) ?: 45720,
                (prop(names, "pad_r") as Long?) ?: 91440, (prop(names, "pad_b") as Long?) ?: 45720,
            )
            return listOf(
                SlideShape(
                    if (kind == "text") "rect" else kind, x, y, w, h, fill, line, (prop(names, "stroke_width") as Float?) ?: 1f,
                    paragraphs = body?.let { paragraphs(it, names, cls, kind != "text") }.orEmpty(),
                    anchor = when (valign) { "middle" -> 'm'; "bottom" -> 'b'; else -> 't' },
                    rotation = rot, insets = insets,
                )
            )
        }

        fun paragraphs(body: Element, shapeStyles: List<String?>, cls: String?, centered: Boolean): List<SlideParagraph> {
            val out = ArrayList<SlideParagraph>()
            walk(body, shapeStyles, cls, centered, out, null, 0, HashMap())
            return out
        }

        fun walk(parent: Element, shapeStyles: List<String?>, cls: String?, centered: Boolean, out: MutableList<SlideParagraph>, listStyle: String?, level: Int, counters: HashMap<Pair<String?, Int>, Int>) {
            for (el in parent.children()) {
                when (el.tagName()) {
                    "text:p", "text:h" -> paragraph(el, shapeStyles, cls, centered, out, null, counters, level)
                    "text:list" -> {
                        val style = el.attr("text:style-name").ifEmpty { null } ?: listStyle
                        for (item in el.children().filter { it.tagName() == "text:list-item" || it.tagName() == "text:list-header" }) {
                            var first = true
                            for (child in item.children()) {
                                when (child.tagName()) {
                                    "text:list" -> walk(Element("x").appendChild(child.clone()), shapeStyles, cls, centered, out, style, level + 1, counters)
                                    "text:p", "text:h" -> {
                                        val marker = if (first && item.tagName() == "text:list-item") style to (level + 1) else null
                                        paragraph(child, shapeStyles, cls, centered, out, marker, counters, level)
                                        first = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        fun paragraph(el: Element, shapeStyles: List<String?>, cls: String?, centered: Boolean, out: MutableList<SlideParagraph>, marker: Pair<String?, Int>?, counters: HashMap<Pair<String?, Int>, Int>, level: Int) {
            val pstyle = el.attr("text:style-name").ifEmpty { null }
            val names = listOfNotNull(pstyle) + shapeStyles
            val align = (prop(names, "align") as Char?) ?: if (centered) 'c' else 'l'
            var bullet: String? = null
            if (marker == null && cls == "outline") {
                // Outline text without a list: the bullets of the presentation style.
                @Suppress("UNCHECKED_CAST")
                (prop(shapeStyles, "list") as Map<Int, List<String>>?)?.get(level + 1)?.let { k -> if (k[0] == "bullet") bullet = k[1].ifBlank { "•" } }
            }
            if (marker != null) {
                val (style, depth) = marker
                @Suppress("UNCHECKED_CAST")
                val levels = listStyles[style] ?: (prop(shapeStyles, "list") as Map<Int, List<String>>?) ?: emptyMap()
                val k = levels[depth] ?: listOf("bullet", "•")
                if (k[0] == "number") {
                    val key = style to depth
                    counters[key] = (counters[key] ?: 0) + 1
                    counters.keys.filter { it.first == style && it.second > depth }.forEach { counters.remove(it) }
                    bullet = "${counters[key]}${k.getOrElse(2) { "." }}"
                } else bullet = k[1].ifBlank { "•" }
            }
            val base = (prop(names, "size") as Float?) ?: when (cls) { "title" -> 44f; "subtitle" -> 32f; "outline" -> maxOf(16f, 32f - 4 * level); else -> 18f }
            val runs = ArrayList<SlideRun>()
            fun add(text: String, n: List<String?>) {
                if (text.isEmpty()) return
                runs += SlideRun(
                    text.replace(Regex("[ \\t\\n]+"), " "),
                    bold = prop(n, "bold") == true, italic = prop(n, "italic") == true,
                    underline = prop(n, "underline") == true, strike = prop(n, "strike") == true,
                    size = (prop(n, "size") as Float?) ?: base, color = (prop(n, "color") as Int?) ?: 0, mono = prop(n, "mono") == true,
                )
            }
            fun walkRuns(node: Element, n: List<String?>) {
                for (c in node.childNodes()) {
                    when {
                        c is TextNode -> add(c.wholeText, n)
                        c !is Element -> Unit
                        c.tagName() == "text:span" -> walkRuns(c, listOfNotNull(c.attr("text:style-name").ifEmpty { null }) + n)
                        c.tagName() == "text:s" -> add(" ".repeat((c.attr("text:c").toIntOrNull() ?: 1).coerceIn(1, 100)), n)
                        c.tagName() == "text:tab" -> add("\t", n)
                        c.tagName() == "text:line-break" -> runs += SlideRun("\n", size = (prop(n, "size") as Float?) ?: base)
                        c.tagName() == "text:a" -> walkRuns(c, n)
                    }
                }
            }
            walkRuns(el, names)
            if (runs.isEmpty() || runs.all { it.text.isBlank() }) {
                out += SlideParagraph(listOf(SlideRun("", size = base)), align, level, null)
                return
            }
            out += SlideParagraph(runs, align, level, bullet)
        }
    }
}
