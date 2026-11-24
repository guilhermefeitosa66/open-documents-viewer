package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import java.nio.charset.Charset

/**
 * A small RTF reader: text with bold, italic, underline, strike, sizes, colors, line breaks,
 * list text and simple tables, turned into the block model the markdown view renders.
 * Pictures, objects, fields' instructions and metadata are skipped. Not a full RTF engine:
 * whatever it does not understand is dropped, never shown as control words.
 */
object RtfModel {
    fun parse(rtf: String): List<MdBlock> = Parser(rtf).run()

    /** Blocks plus whether something (pictures, embedded objects) was left out. */
    fun parseWithInfo(rtf: String): Pair<List<MdBlock>, Boolean> = Parser(rtf).let { it.run() to it.partial }

    private val skippedDestinations = setOf(
        "fonttbl", "stylesheet", "info", "fldinst", "themedata", "datastore",
        "latentstyles", "rsidtbl", "listtable", "listoverridetable", "generator", "xmlnstbl", "mmathPr", "pgdsctbl",
        "filetbl", "revtbl", "userprops", "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr",
        "footerf", "footnote", "annotation", "atnid", "atnauthor", "bkmkstart", "bkmkend", "colorschememapping",
        "wgrffmtfilter", "nonshppict", "shpinst", "sp", "sn", "sv", "xe", "tc", "template", "operator", "author",
    )

    private data class Style(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var halfPoints: Int = 24,
        var color: Int = 0,
        var skip: Boolean = false,
        var uc: Int = 1,
    )

    private class Parser(val src: String) {
        val out = ArrayList<MdBlock>()
        var partial = false
        var charset: Charset = Charset.forName("windows-1252")
        val colors = ArrayList<Int>()
        val stack = ArrayList<Style>()
        var st = Style()
        var defaultHalfPoints = 24
        val charsBySize = HashMap<Int, Int>()

        // Current paragraph: text plus styled runs.
        val sb = StringBuilder()
        val spans = ArrayList<InlineSpan>()
        var runStart = 0
        var runStyle = Style()

        // Tables.
        var inTable = false
        val cell = ArrayList<InlineText>()
        val rows = ArrayList<List<InlineText>>()

        var pendingSkipBytes = 0
        var ignorableNext = false

        fun run(): List<MdBlock> {
            var i = 0
            val n = src.length
            while (i < n) {
                val c = src[i]
                when (c) {
                    '{' -> {
                        closeRun()
                        stack += st.copy()
                        i++
                    }
                    '}' -> {
                        closeRun()
                        st = if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else Style()
                        i++
                    }
                    '\\' -> i = control(i + 1)
                    '\r', '\n' -> i++
                    else -> {
                        if (pendingSkipBytes > 0) pendingSkipBytes-- else emit(c.toString())
                        i++
                    }
                }
            }
            endParagraph()
            flushTable()
            return finish()
        }

        private fun control(start: Int): Int {
            val n = src.length
            if (start >= n) return n
            val c = src[start]
            if (!c.isLetter()) {
                when (c) {
                    '\'' -> {
                        if (start + 2 < n) {
                            val hex = src.substring(start + 1, minOf(start + 3, n))
                            val b = hex.toIntOrNull(16)
                            if (b != null) {
                                if (pendingSkipBytes > 0) pendingSkipBytes-- else emit(String(byteArrayOf(b.toByte()), charset))
                            }
                        }
                        return start + 3
                    }
                    '*' -> { ignorableNext = true; return start + 1 }
                    '~' -> { emit(" "); return start + 1 }
                    '_' -> { emit("-"); return start + 1 }
                    '-' -> return start + 1
                    '\n', '\r' -> { endParagraph(); return start + 1 }
                    else -> { emit(c.toString()); return start + 1 }
                }
            }
            var j = start
            while (j < n && src[j].isLetter()) j++
            val word = src.substring(start, j)
            var param: Int? = null
            if (j < n && (src[j] == '-' || src[j].isDigit())) {
                val ps = j
                j++
                while (j < n && src[j].isDigit()) j++
                param = src.substring(ps, j).toIntOrNull()
            }
            if (j < n && src[j] == ' ') j++
            word(word, param)
            return j
        }

        private fun word(w: String, p: Int?) {
            if (ignorableNext) {
                ignorableNext = false
                if (w !in known) { st.skip = true; return }
            }
            if (w in skippedDestinations) { st.skip = true; return }
            when (w) {
                "colortbl" -> { st.skip = true; parsingColors = true; colorR = 0; colorG = 0; colorB = 0 }
                "red" -> colorR = p ?: 0
                "green" -> colorG = p ?: 0
                "blue" -> colorB = p ?: 0
                "pict", "object" -> { closeRun(); partial = true; st.skip = true }
                "ansicpg" -> p?.let { cp -> runCatching { charset = Charset.forName("windows-$cp") }.recoverCatching { charset = Charset.forName("cp$cp") } }
                "deff", "deflang" -> Unit
                "plain" -> { closeRun(); st.bold = false; st.italic = false; st.underline = false; st.strike = false; st.halfPoints = defaultHalfPoints; st.color = 0 }
                "b" -> style { st.bold = p != 0 }
                "i" -> style { st.italic = p != 0 }
                "ul", "uld", "uldb", "ulw", "ulth" -> style { st.underline = p != 0 }
                "ulnone" -> style { st.underline = false }
                "strike", "striked" -> style { st.strike = p != 0 }
                "fs" -> style { st.halfPoints = p ?: defaultHalfPoints }
                "cf" -> style { st.color = p ?: 0 }
                "uc" -> st.uc = p ?: 1
                "u" -> {
                    val code = (p ?: 0).let { if (it < 0) it + 65536 else it }
                    emit(String(Character.toChars(code)))
                    pendingSkipBytes = st.uc
                }
                "par", "sect" -> if (inTable) emit("\n") else endParagraph()
                "line" -> emit("\n")
                "tab" -> emit("\t")
                "bullet" -> emit("•")
                "emdash" -> emit("—")
                "endash" -> emit("–")
                "lquote" -> emit("‘")
                "rquote" -> emit("’")
                "ldblquote" -> emit("“")
                "rdblquote" -> emit("”")
                "page" -> { endParagraph(); out += MdBlock.Rule(0, 0) }
                "pard" -> if (!inTable) Unit
                // A new row right after a row continues the same table.
                "trowd" -> { pendingTableEnd = false; endParagraph(); inTable = true }
                "intbl" -> if (!inTable) { pendingTableEnd = false; endParagraph(); inTable = true }
                "cell" -> { cell += takeParagraph(); }
                "row" -> { rows += cell.toList(); cell.clear(); inTable = false; pendingTableEnd = true }
                else -> Unit
            }
        }

        private val known = setOf("u", "uc", "b", "i", "ul", "par", "line", "tab")
        private var parsingColors = false
        private var colorR = 0
        private var colorG = 0
        private var colorB = 0
        private var pendingTableEnd = false

        private inline fun style(change: () -> Unit) {
            closeRun()
            change()
        }

        private fun emit(text: String) {
            if (parsingColors && st.skip) {
                // Inside the color table: ';' closes an entry (the first one is "auto").
                for (ch in text) if (ch == ';') {
                    colors += (0xFF shl 24) or (colorR shl 16) or (colorG shl 8) or colorB
                    colorR = 0; colorG = 0; colorB = 0
                }
                return
            }
            if (st.skip) return
            if (pendingTableEnd && !inTable && text.isNotBlank()) flushTable()
            if (sb.length == runStart) runStyle = st.copy()
            else if (!sameLook(runStyle, st)) { closeRun(); runStyle = st.copy() }
            sb.append(text)
        }

        private fun sameLook(a: Style, b: Style) =
            a.bold == b.bold && a.italic == b.italic && a.underline == b.underline && a.strike == b.strike &&
                a.halfPoints == b.halfPoints && a.color == b.color

        private fun closeRun() {
            val end = sb.length
            if (end > runStart) {
                val r = runStyle
                if (r.bold) spans += InlineSpan(runStart, end, InlineStyle.BOLD)
                if (r.italic) spans += InlineSpan(runStart, end, InlineStyle.ITALIC)
                if (r.underline) spans += InlineSpan(runStart, end, InlineStyle.UNDERLINE)
                if (r.strike) spans += InlineSpan(runStart, end, InlineStyle.STRIKE)
                // Raw half-points for now: the body size is only known at the end (see finish()).
                spans += InlineSpan(runStart, end, InlineStyle.SIZE, scale = r.halfPoints.toFloat())
                charsBySize[r.halfPoints] = (charsBySize[r.halfPoints] ?: 0) + (end - runStart)
                if (r.color > 0) colors.getOrNull(r.color - 1 + firstColorOffset())?.let {
                    spans += InlineSpan(runStart, end, InlineStyle.COLOR, color = it)
                }
            }
            runStart = end
        }

        // \colortbl starts with an empty "auto" entry (";"), which we also stored: index 0 = auto.
        private fun firstColorOffset() = 1

        private fun takeParagraph(): InlineText {
            closeRun()
            val text = sb.toString()
            var lead = 0
            while (lead < text.length && text[lead].isWhitespace()) lead++
            var end = text.length
            while (end > lead && text[end - 1].isWhitespace()) end--
            val t = text.substring(lead, end)
            val sp = spans.mapNotNull { s ->
                val a = (s.start - lead).coerceIn(0, t.length)
                val b = (s.end - lead).coerceIn(0, t.length)
                if (b > a) s.copy(start = a, end = b) else null
            }
            sb.setLength(0); spans.clear(); runStart = 0
            parsingColors = false
            return InlineText(t, sp)
        }

        private fun endParagraph() {
            if (pendingTableEnd) flushTable()
            val t = takeParagraph()
            if (t.text.isEmpty()) return
            out += MdBlock.Paragraph(t, 0, 0)
        }

        /** Sizes become relative to the body size (the one most text uses); then headings and lists are recognized. */
        fun finish(): List<MdBlock> {
            val body = charsBySize.maxByOrNull { it.value }?.key?.toFloat() ?: 24f
            fun norm(t: InlineText) = t.copy(spans = t.spans.mapNotNull { sp ->
                if (sp.style != InlineStyle.SIZE) sp
                else (sp.scale / body).takeIf { kotlin.math.abs(it - 1f) > 0.05f }?.let { sp.copy(scale = it) }
            })
            return out.map { b ->
                when (b) {
                    is MdBlock.Paragraph -> classify(norm(b.content))
                    is MdBlock.Table -> b.copy(rows = b.rows.map { r -> r.map(::norm) })
                    else -> b
                }
            }
        }

        private fun classify(t: InlineText): MdBlock {
            // Big or bold-and-larger short lines read as headings (word processors' Title/Heading styles).
            val scale = t.spans.filter { it.style == InlineStyle.SIZE && it.start == 0 && it.end == t.text.length }.maxOfOrNull { it.scale } ?: 1f
            val allBold = t.spans.any { it.style == InlineStyle.BOLD && it.start == 0 && it.end == t.text.length }
            val short = t.text.length < 120 && '\n' !in t.text
            val heading = when {
                short && scale >= 1.6f -> 1
                short && scale >= 1.25f -> 2
                short && allBold && scale >= 1.1f -> 3
                else -> 0
            }
            val bullet = Regex("^([•·\\-–*]|\\d+[.)])\\s+").find(t.text)
            return when {
                heading > 0 -> MdBlock.Heading(heading, t, 0, 0)
                bullet != null -> {
                    val cut = bullet.value.length
                    MdBlock.ListItem(
                        bullet.groupValues[1].let { if (it.first().isDigit()) it else "•" }, null,
                        InlineText(t.text.substring(cut), t.spans.mapNotNull { s ->
                            val a = (s.start - cut).coerceAtLeast(0); val e = s.end - cut
                            if (e > a) s.copy(start = a, end = e) else null
                        }), 0, 0,
                    )
                }
                else -> MdBlock.Paragraph(t, 0, 0)
            }
        }

        private fun flushTable() {
            pendingTableEnd = false
            if (rows.isEmpty()) return
            val width = rows.maxOf { it.size }
            out += MdBlock.Table(emptyList(), rows.map { r -> r + List(width - r.size) { InlineText.EMPTY } }, List(width) { CellAlign.START }, 0, 0)
            rows.clear()
        }
    }
}
