package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import io.github.guilhermefeitosa66.opendocumentsviewer.formats.ZipFiles
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Word 2007+ documents (.docx, .docm, .dotx) read with no library: the WordprocessingML of
 * word/document.xml with its styles, numbering, relationships, notes, header and footer, flattened
 * into the markdown block list. The layout reflows to the screen (no pages): page breaks become
 * rules, the header goes first and the footer last. Pictures stay in the archive (`zip:` sources).
 */
object DocxModel {
    class NotDocx(message: String) : Exception(message)

    class Result(val blocks: List<MdBlock>, val anchors: Map<String, Int>, val partial: Boolean) {
        /** One line per block: the "source" view and "Copy all". */
        val plainText: String get() = blocks.joinToString("\n") { it.plainText }
    }

    fun parse(bytes: ByteArray): Result {
        if (!ZipFiles.isZip(bytes)) throw NotDocx("not a zip file (an encrypted or older Word file?)")
        val files = ZipFiles.entries(bytes) { it.endsWith(".xml") || it.endsWith(".rels") }
        val main = mainPart(files) ?: throw NotDocx("no word/document.xml")
        return Reader(files, main).read()
    }

    private fun mainPart(files: Map<String, ByteArray>): String? {
        files["[Content_Types].xml"]?.let { ct ->
            val x = xml(ct)
            x.getElementsByTag("Override").firstOrNull { it.attr("ContentType").contains("wordprocessingml.document.main") || it.attr("ContentType").contains("wordprocessingml.template.main") }
                ?.attr("PartName")?.trimStart('/')?.let { if (files.containsKey(it)) return it }
        }
        return "word/document.xml".takeIf { files.containsKey(it) }
    }

    internal fun xml(b: ByteArray): org.jsoup.nodes.Document = Jsoup.parse(String(b, Charsets.UTF_8), "", Parser.xmlParser())

    private fun attr(e: Element?, name: String): String? = e?.attr("w:$name")?.takeIf { it.isNotEmpty() } ?: e?.attr(name)?.takeIf { it.isNotEmpty() }
    private fun child(e: Element?, tag: String): Element? = e?.children()?.firstOrNull { it.tagName() == tag }

    /** <w:b/>, <w:b w:val="1"/> → true; <w:b w:val="0|false|none"/> → false; absent → null. */
    private fun toggle(rPr: Element?, tag: String): Boolean? {
        val e = child(rPr, tag) ?: return null
        val v = attr(e, "val") ?: return true
        return v !in setOf("0", "false", "off", "none")
    }

    /** Character formatting, merged from document defaults, styles and the run itself. */
    private data class RunProps(
        val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
        val color: Int = 0, val halfPoints: Int = 0, val mono: Boolean = false, val hidden: Boolean = false,
    ) {
        fun with(rPr: Element?): RunProps {
            if (rPr == null) return this
            var p = this
            toggle(rPr, "w:b")?.let { p = p.copy(bold = it) }
            toggle(rPr, "w:i")?.let { p = p.copy(italic = it) }
            toggle(rPr, "w:strike")?.let { p = p.copy(strike = it) }
            toggle(rPr, "w:dstrike")?.let { if (it) p = p.copy(strike = true) }
            toggle(rPr, "w:vanish")?.let { p = p.copy(hidden = it) }
            child(rPr, "w:u")?.let { p = p.copy(underline = attr(it, "val") != "none") }
            child(rPr, "w:color")?.let { c ->
                val v = attr(c, "val")
                p = p.copy(color = if (v == null || v == "auto") 0 else (v.toIntOrNull(16)?.let { 0xFF000000.toInt() or it } ?: 0))
            }
            child(rPr, "w:sz")?.let { s -> attr(s, "val")?.toIntOrNull()?.let { p = p.copy(halfPoints = it) } }
            child(rPr, "w:rFonts")?.let { f ->
                val name = (attr(f, "ascii") ?: attr(f, "hAnsi") ?: "").lowercase()
                if (name.isNotEmpty()) p = p.copy(mono = listOf("courier", "consolas", "mono", "menlo", "lucida console").any { it in name })
            }
            return p
        }
    }

    private class Style(val id: String, val name: String, val basedOn: String?, val pPr: Element?, val rPr: Element?, val type: String)

    private class Level(val start: Int, val fmt: String, val text: String)

    private class Reader(val files: Map<String, ByteArray>, val mainPath: String) {
        val dir = mainPath.substringBeforeLast('/', "")
        val styles = HashMap<String, Style>()
        var defaults = RunProps()
        var defaultParagraphStyle: String? = null
        val abstractNums = HashMap<String, Map<Int, Level>>()
        val nums = HashMap<String, String>() // numId → abstractNumId
        val levelStarts = HashMap<String, MutableMap<Int, Int>>() // numId → overrides
        val counters = HashMap<String, IntArray>()
        val out = ArrayList<MdBlock>()
        val anchors = HashMap<String, Int>()
        var partial = false
        val footnotes = LinkedHashMap<String, Int>() // footnote id → number shown
        val sizes = HashMap<Int, Int>() // half points → characters, to find the body size

        fun read(): Result {
            loadStyles()
            loadNumbering()
            val rels = rels(mainPath)
            val doc = xml(files[mainPath]!!)
            val body = doc.getElementsByTag("w:body").firstOrNull() ?: throw NotDocx("no body")

            // Header (default, first section) on top, footer at the end.
            val sect = body.children().lastOrNull { it.tagName() == "w:sectPr" }
                ?: body.getElementsByTag("w:sectPr").lastOrNull()
            fun part(kind: String): String? = sect?.children()?.firstOrNull { it.tagName() == kind && (attr(it, "type") ?: "default") == "default" }
                ?.attr("r:id")?.let { rels[it]?.first }
            part("w:headerReference")?.let { p ->
                val before = out.size
                files[p]?.let { blocksOf(xml(it).children().firstOrNull() ?: return@let, rels(p)) }
                if (out.size > before) out += MdBlock.Rule(0, 0)
            }
            blocksOf(body, rels)
            notes(rels)
            part("w:footerReference")?.let { p ->
                val before = out.size
                out += MdBlock.Rule(0, 0)
                files[p]?.let { blocksOf(xml(it).children().firstOrNull() ?: return@let, rels(p)) }
                if (out.size == before + 1) out.removeAt(out.size - 1)
            }
            return Result(scaleSizes(out), anchors, partial)
        }

        // ---- Parts -------------------------------------------------------------------

        /** Relationship id → (path or URL, external). */
        fun rels(part: String): Map<String, Pair<String, Boolean>> {
            val base = part.substringBeforeLast('/', "")
            val relsPath = (if (base.isEmpty()) "" else "$base/") + "_rels/" + part.substringAfterLast('/') + ".rels"
            val x = files[relsPath]?.let(::xml) ?: return emptyMap()
            return x.getElementsByTag("Relationship").associate { r ->
                val external = r.attr("TargetMode") == "External"
                r.attr("Id") to ((if (external) r.attr("Target") else ZipFiles.resolve(base, r.attr("Target"))) to external)
            }
        }

        fun loadStyles() {
            val x = files["$dir/styles.xml"]?.let(::xml) ?: return
            x.getElementsByTag("w:docDefaults").firstOrNull()?.getElementsByTag("w:rPr")?.firstOrNull()?.let { defaults = defaults.with(it) }
            for (s in x.getElementsByTag("w:style")) {
                val id = attr(s, "styleId") ?: continue
                val style = Style(
                    id, attr(child(s, "w:name"), "val").orEmpty().lowercase(), attr(child(s, "w:basedOn"), "val"),
                    child(s, "w:pPr"), child(s, "w:rPr"), attr(s, "type").orEmpty(),
                )
                styles[id] = style
                if (style.type == "paragraph" && attr(s, "default") in setOf("1", "true")) defaultParagraphStyle = id
            }
        }

        fun chain(id: String?): List<Style> {
            val list = ArrayList<Style>()
            var cur = id
            while (cur != null && list.size < 20) {
                val s = styles[cur] ?: break
                list.add(0, s)
                cur = s.basedOn
            }
            return list
        }

        fun headingLevel(styleId: String?, pPr: Element?): Int {
            attr(child(pPr, "w:outlineLvl"), "val")?.toIntOrNull()?.let { if (it in 0..5) return it + 1 }
            for (s in chain(styleId).reversed()) {
                val n = s.name
                when {
                    n == "title" -> return 1
                    n.startsWith("heading ") -> n.removePrefix("heading ").trim().toIntOrNull()?.let { return it.coerceIn(1, 6) }
                }
                attr(child(s.pPr, "w:outlineLvl"), "val")?.toIntOrNull()?.let { if (it in 0..5) return it + 1 }
            }
            return 0
        }

        fun loadNumbering() {
            val x = files["$dir/numbering.xml"]?.let(::xml) ?: return
            for (a in x.getElementsByTag("w:abstractNum")) {
                val levels = HashMap<Int, Level>()
                for (l in a.children().filter { it.tagName() == "w:lvl" }) {
                    val ilvl = attr(l, "ilvl")?.toIntOrNull() ?: continue
                    levels[ilvl] = Level(
                        attr(child(l, "w:start"), "val")?.toIntOrNull() ?: 1,
                        attr(child(l, "w:numFmt"), "val") ?: "decimal",
                        attr(child(l, "w:lvlText"), "val") ?: "%${ilvl + 1}.",
                    )
                }
                attr(a, "abstractNumId")?.let { abstractNums[it] = levels }
            }
            for (n in x.getElementsByTag("w:num")) {
                val id = attr(n, "numId") ?: continue
                attr(child(n, "w:abstractNumId"), "val")?.let { nums[id] = it }
                for (o in n.children().filter { it.tagName() == "w:lvlOverride" }) {
                    val ilvl = attr(o, "ilvl")?.toIntOrNull() ?: continue
                    attr(child(o, "w:startOverride"), "val")?.toIntOrNull()?.let { levelStarts.getOrPut(id) { HashMap() }[ilvl] = it }
                }
            }
        }

        /** The marker of a list item ("1.", "a)", "•") and advances the counters. */
        fun marker(numId: String, ilvl: Int): String? {
            val levels = abstractNums[nums[numId] ?: return null] ?: return null
            val level = levels[ilvl] ?: return "•"
            if (level.fmt == "none") return ""
            if (level.fmt == "bullet") return when (ilvl % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
            val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
            fun startOf(l: Int) = levelStarts[numId]?.get(l) ?: levels[l]?.start ?: 1
            c[ilvl] = if (c[ilvl] < 0) startOf(ilvl) else c[ilvl] + 1
            for (deeper in ilvl + 1 until 9) c[deeper] = -1
            return Regex("%([1-9])").replace(level.text) { m ->
                val l = m.groupValues[1].toInt() - 1
                val v = if (c[l] < 0) startOf(l) else c[l]
                format(v, levels[l]?.fmt ?: "decimal")
            }
        }

        // ---- Blocks ------------------------------------------------------------------

        fun blocksOf(container: Element, rels: Map<String, Pair<String, Boolean>>) {
            for (e in container.children()) {
                when (e.tagName()) {
                    "w:p" -> paragraph(e, rels)
                    "w:tbl" -> table(e, rels)
                    "w:sdt" -> child(e, "w:sdtContent")?.let { blocksOf(it, rels) }
                    "w:customXml", "w:smartTag", "w:ins" -> blocksOf(e, rels)
                    "w:altChunk" -> partial = true
                }
            }
        }

        fun paragraph(p: Element, rels: Map<String, Pair<String, Boolean>>) {
            val pPr = child(p, "w:pPr")
            val styleId = attr(child(pPr, "w:pStyle"), "val") ?: defaultParagraphStyle
            if (toggle(pPr, "w:pageBreakBefore") == true && out.isNotEmpty() && out.last() !is MdBlock.Rule) out += MdBlock.Rule(0, 0)
            var base = defaults
            for (s in chain(styleId)) base = base.with(s.rPr)
            val styleChain = chain(styleId)
            val align = (attr(child(pPr, "w:jc"), "val") ?: styleChain.reversed().firstNotNullOfOrNull { attr(child(it.pPr, "w:jc"), "val") }).let {
                when (it) {
                    "center" -> BlockAlign.CENTER
                    "right", "end" -> BlockAlign.END
                    "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> BlockAlign.JUSTIFY
                    else -> BlockAlign.START
                }
            }
            val numPr = child(pPr, "w:numPr") ?: styleChain.reversed().firstNotNullOfOrNull { child(it.pPr, "w:numPr") }
            val numId = attr(child(numPr, "w:numId"), "val")?.takeIf { it != "0" }
            val ilvl = attr(child(numPr, "w:ilvl"), "val")?.toIntOrNull() ?: 0
            val level = headingLevel(styleId, pPr)

            val inline = Inline()
            val runs = RunWalker(this, rels, inline, base)
            for (c in p.children()) runs.node(c)
            for ((name) in runs.bookmarks) anchors.putIfAbsent(name, out.size)

            // Pictures and page breaks split the paragraph.
            val pieces = runs.pieces()
            var first = true
            for (piece in pieces) {
                when (piece) {
                    is Piece.Text -> {
                        val t = piece.text
                        if (t.text.isBlank() && numId == null) continue
                        if (level > 0 && first) {
                            out += MdBlock.Heading(level, t.trimmed(), 0, 0, if (align == BlockAlign.JUSTIFY) BlockAlign.START else align)
                        } else if (numId != null && first) {
                            val m = marker(numId, ilvl)
                            if (m.isNullOrEmpty()) out += MdBlock.Paragraph(t.trimmed(), ilvl + 1, 0, align)
                            else out += MdBlock.ListItem(m, null, t.trimmed(), ilvl, 0)
                        } else out += MdBlock.Paragraph(t.trimmed(), if (numId != null) ilvl + 1 else 0, 0, align)
                        first = false
                    }
                    is Piece.Picture -> { out += MdBlock.Image(piece.src, piece.alt, 0, 0); first = false }
                    Piece.PageBreak -> if (out.isNotEmpty() && out.last() !is MdBlock.Rule) out += MdBlock.Rule(0, 0)
                }
            }
        }

        fun table(tbl: Element, rels: Map<String, Pair<String, Boolean>>) {
            val rows = ArrayList<List<InlineText>>()
            for (tr in tbl.children().filter { it.tagName() == "w:tr" }) {
                val cells = ArrayList<InlineText>()
                for (tc in tr.children().filter { it.tagName() == "w:tc" || it.tagName() == "w:sdt" }) {
                    val cell = if (tc.tagName() == "w:sdt") child(child(tc, "w:sdtContent"), "w:tc") ?: continue else tc
                    val tcPr = child(cell, "w:tcPr")
                    val span = attr(child(tcPr, "w:gridSpan"), "val")?.toIntOrNull() ?: 1
                    val vMerge = child(tcPr, "w:vMerge")
                    // A cell continuing a vertical merge is shown empty (the text is in the first one).
                    val text = if (vMerge != null && attr(vMerge, "val") != "restart") InlineText("") else cellText(cell, rels)
                    cells += text
                    repeat(span - 1) { cells += InlineText("") }
                }
                if (cells.isNotEmpty()) rows += cells
            }
            if (rows.isEmpty()) return
            val width = rows.maxOf { it.size }
            val padded = rows.map { r -> r + List(width - r.size) { InlineText("") } }
            out += MdBlock.Table(padded.first(), padded.drop(1), List(width) { CellAlign.START }, 0, 0)
        }

        fun cellText(tc: Element, rels: Map<String, Pair<String, Boolean>>): InlineText {
            val inline = Inline()
            var firstPara = true
            fun walk(container: Element) {
                for (e in container.children()) {
                    when (e.tagName()) {
                        "w:p" -> {
                            if (!firstPara) inline.append("\n", null)
                            firstPara = false
                            val pPr = child(e, "w:pPr")
                            var base = defaults
                            for (s in chain(attr(child(pPr, "w:pStyle"), "val") ?: defaultParagraphStyle)) base = base.with(s.rPr)
                            val w = RunWalker(this, rels, inline, base, picturesAsText = true)
                            for (c in e.children()) w.node(c)
                        }
                        "w:tbl" -> { partial = true; for (r in e.getElementsByTag("w:p")) { inline.append("\n", null); inline.append(r.text(), null) } }
                        "w:sdt" -> child(e, "w:sdtContent")?.let(::walk)
                    }
                }
            }
            walk(tc)
            return inline.build().trimmed()
        }

        fun notes(rels: Map<String, Pair<String, Boolean>>) {
            if (footnotes.isEmpty()) return
            val path = rels.values.firstOrNull { it.first.endsWith("footnotes.xml") }?.first ?: "$dir/footnotes.xml"
            val x = files[path]?.let(::xml) ?: return
            val byId = x.getElementsByTag("w:footnote").associateBy { attr(it, "id") }
            val notesRels = rels(path)
            out += MdBlock.Rule(0, 0)
            for ((id, n) in footnotes) {
                val note = byId[id] ?: continue
                anchors["fn-$n"] = out.size
                val inline = Inline()
                inline.append("$n. ", null)
                var firstPara = true
                for (p in note.getElementsByTag("w:p")) {
                    if (!firstPara) inline.append(" ", null)
                    firstPara = false
                    val w = RunWalker(this, notesRels, inline, defaults, picturesAsText = true)
                    for (c in p.children()) w.node(c)
                }
                out += MdBlock.Paragraph(inline.build().trimmed(), 0, 0)
            }
        }

        fun footnoteNumber(id: String): Int = footnotes.getOrPut(id) { footnotes.size + 1 }

        fun countSize(halfPoints: Int, chars: Int) {
            if (halfPoints > 0) sizes[halfPoints] = (sizes[halfPoints] ?: 0) + chars
        }

        /** Sizes become relative to the size most text uses (like RTF). */
        fun scaleSizes(blocks: List<MdBlock>): List<MdBlock> {
            val body = sizes.maxByOrNull { it.value }?.key ?: return blocks
            fun fix(t: InlineText): InlineText {
                if (t.spans.none { it.style == InlineStyle.SIZE }) return t
                return t.copy(spans = t.spans.mapNotNull { s ->
                    if (s.style != InlineStyle.SIZE) s else {
                        val r = (s.scale / body).coerceIn(0.6f, 2.4f)
                        if (r in 0.92f..1.08f) null else s.copy(scale = r)
                    }
                })
            }
            return blocks.map { b ->
                when (b) {
                    is MdBlock.Paragraph -> b.copy(content = fix(b.content))
                    is MdBlock.ListItem -> b.copy(content = fix(b.content))
                    // Headings have their own size.
                    is MdBlock.Heading -> b.copy(content = b.content.copy(spans = b.content.spans.filter { it.style != InlineStyle.SIZE }))
                    is MdBlock.Table -> b.copy(header = b.header.map(::fix), rows = b.rows.map { r -> r.map(::fix) })
                    else -> b
                }
            }
        }
    }

    private sealed interface Piece {
        class Text(val text: InlineText) : Piece
        class Picture(val src: String, val alt: String) : Piece
        data object PageBreak : Piece
    }

    /** Walks the runs of one paragraph (hyperlinks, fields, insertions, pictures, notes). */
    private class RunWalker(
        val r: Reader,
        val rels: Map<String, Pair<String, Boolean>>,
        var inline: Inline,
        val base: RunProps,
        val picturesAsText: Boolean = false,
    ) {
        val bookmarks = ArrayList<Pair<String, Int>>()
        private val pieces = ArrayList<Piece>()
        private var link: String? = null
        // Complex fields: HYPERLINK "url" between begin/separate/end.
        private var fieldDepth = 0
        private var fieldCode = StringBuilder()
        private var inFieldCode = false
        private val fieldLinks = ArrayList<String?>()

        fun pieces(): List<Piece> {
            flush()
            return pieces
        }

        private fun flush() {
            val t = inline.build()
            if (t.text.isNotEmpty()) pieces += Piece.Text(t)
            inline = Inline()
        }

        fun node(e: Element) {
            when (e.tagName()) {
                "w:r" -> run(e)
                "w:hyperlink" -> {
                    val target = e.attr("r:id").takeIf { it.isNotEmpty() }?.let { rels[it]?.first }
                        ?: attr(e, "anchor")?.let { "#$it" }
                    val saved = link
                    link = target ?: link
                    for (c in e.children()) node(c)
                    link = saved
                }
                "w:ins", "w:smartTag", "w:customXml", "w:fldSimple", "w:dir", "w:bdo" -> {
                    val instr = attr(e, "instr")
                    val saved = link
                    if (instr != null) hyperlinkOf(instr)?.let { link = it }
                    for (c in e.children()) node(c)
                    link = saved
                }
                "w:sdt" -> child(e, "w:sdtContent")?.children()?.forEach(::node)
                "w:bookmarkStart" -> attr(e, "name")?.takeIf { !it.startsWith("_GoBack") }?.let { bookmarks += it to 0 }
                "mc:AlternateContent" -> child(e, "mc:Choice")?.children()?.forEach(::node)
                "w:del", "w:moveFrom", "w:pPr", "w:proofErr", "w:permStart", "w:permEnd" -> Unit
            }
        }

        private fun hyperlinkOf(instr: String): String? {
            val m = Regex("""HYPERLINK\s+(?:\\l\s+)?"([^"]+)"""").find(instr) ?: return null
            return if (instr.contains("\\l")) "#" + m.groupValues[1] else m.groupValues[1]
        }

        private fun currentLink(): String? = link ?: fieldLinks.lastOrNull { it != null }

        private fun run(run: Element) {
            val rPr = child(run, "w:rPr")
            var props = base
            attr(child(rPr, "w:rStyle"), "val")?.let { id -> for (s in r.chain(id)) props = props.with(s.rPr) }
            props = props.with(rPr)
            for (c in run.children()) {
                when (c.tagName()) {
                    "w:t" -> if (!inFieldCode && !props.hidden) text(c.wholeText(), props)
                    "w:instrText" -> if (inFieldCode) fieldCode.append(c.wholeText())
                    "w:fldChar" -> when (attr(c, "fldCharType")) {
                        "begin" -> { fieldDepth++; inFieldCode = true; fieldCode = StringBuilder() }
                        "separate" -> { inFieldCode = false; fieldLinks += hyperlinkOf(fieldCode.toString()) }
                        "end" -> { if (fieldDepth > 0) fieldDepth--; inFieldCode = false; if (fieldLinks.isNotEmpty()) fieldLinks.removeAt(fieldLinks.size - 1) }
                    }
                    "w:tab", "w:ptab" -> if (!inFieldCode) text("\t", props)
                    "w:br", "w:cr" -> if (attr(c, "type") == "page") { if (!picturesAsText) { flush(); pieces += Piece.PageBreak } } else text("\n", props)
                    "w:noBreakHyphen" -> text("‑", props)
                    "w:softHyphen" -> Unit
                    "w:sym" -> attr(c, "char")?.toIntOrNull(16)?.let { code ->
                        // Symbol fonts map to the private use area; keep the common bullet look.
                        text(if (code in 0xF000..0xF0FF) "•" else String(Character.toChars(code)), props)
                    }
                    "w:footnoteReference" -> attr(c, "id")?.let { id ->
                        val n = r.footnoteNumber(id)
                        inline.append("[$n]", InlineSpan(0, 0, InlineStyle.LINK, url = "#fn-$n"))
                    }
                    "w:endnoteReference" -> r.partial = true
                    "w:drawing", "w:pict", "w:object" -> picture(c)
                    "mc:AlternateContent" -> child(c, "mc:Choice")?.children()?.forEach { picture(it) }
                }
            }
        }

        private fun text(s: String, p: RunProps) {
            if (s.isEmpty()) return
            r.countSize(p.halfPoints, s.length)
            val styles = ArrayList<InlineSpan>()
            if (p.bold) styles += InlineSpan(0, 0, InlineStyle.BOLD)
            if (p.italic) styles += InlineSpan(0, 0, InlineStyle.ITALIC)
            if (p.underline && currentLink() == null) styles += InlineSpan(0, 0, InlineStyle.UNDERLINE)
            if (p.strike) styles += InlineSpan(0, 0, InlineStyle.STRIKE)
            if (p.mono) styles += InlineSpan(0, 0, InlineStyle.CODE)
            if (p.color != 0 && p.color != 0xFF000000.toInt()) styles += InlineSpan(0, 0, InlineStyle.COLOR, color = p.color)
            if (p.halfPoints > 0) styles += InlineSpan(0, 0, InlineStyle.SIZE, scale = p.halfPoints.toFloat())
            currentLink()?.let { styles += InlineSpan(0, 0, InlineStyle.LINK, url = it) }
            inline.appendStyled(s, styles)
        }

        private fun picture(e: Element) {
            val blip = e.getElementsByTag("a:blip").firstOrNull()?.attr("r:embed")?.takeIf { it.isNotEmpty() }
                ?: e.getElementsByTag("v:imagedata").firstOrNull()?.attr("r:id")?.takeIf { it.isNotEmpty() }
            val alt = e.getElementsByTag("wp:docPr").firstOrNull()?.let { it.attr("descr").ifEmpty { it.attr("title") } }.orEmpty()
            if (blip == null) {
                // Text boxes: their text joins the paragraph.
                e.getElementsByTag("w:txbxContent").firstOrNull()?.let { box ->
                    for (p in box.getElementsByTag("w:p")) { text("\n", base); for (c in p.children()) node(c) }
                } ?: run { r.partial = true }
                return
            }
            val (target, external) = rels[blip] ?: return
            if (external) return
            if (picturesAsText) { if (alt.isNotEmpty()) text("[$alt]", base); return }
            flush()
            pieces += Piece.Picture(ZipFiles.IMAGE_SCHEME + target, alt)
        }
    }

    /** Builds an [InlineText] piece by piece. */
    private class Inline {
        private val sb = StringBuilder()
        private val spans = ArrayList<InlineSpan>()

        fun append(s: String, style: InlineSpan?) {
            val start = sb.length
            sb.append(s)
            style?.let { spans += it.copy(start = start, end = sb.length) }
        }

        fun appendStyled(s: String, styles: List<InlineSpan>) {
            val start = sb.length
            sb.append(s)
            for (st in styles) {
                // Merge with the previous span of the same look (runs split by spell checking...).
                val prev = spans.lastOrNull { it.style == st.style && it.end == start && it.url == st.url && it.color == st.color && it.scale == st.scale }
                if (prev != null) spans[spans.lastIndexOf(prev)] = prev.copy(end = sb.length)
                else spans += st.copy(start = start, end = sb.length)
            }
        }

        fun build() = InlineText(sb.toString(), spans.toList())
    }

    private fun InlineText.trimmed(): InlineText {
        val lead = text.length - text.trimStart().length
        val end = text.trimEnd().length
        if (lead == 0 && end == text.length) return this
        if (end <= lead) return InlineText("")
        return InlineText(
            text.substring(lead, end),
            spans.mapNotNull { s ->
                val a = (s.start - lead).coerceIn(0, end - lead)
                val b = (s.end - lead).coerceIn(0, end - lead)
                if (b > a) s.copy(start = a, end = b) else null
            },
        )
    }

    internal fun format(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else "$n"
        "ordinal" -> "$n."
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val c = ('A' + (n - 1) % 26)
        return c.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        var x = n
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }
}
