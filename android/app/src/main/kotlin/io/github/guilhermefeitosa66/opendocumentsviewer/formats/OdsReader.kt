package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * OpenDocument spreadsheets (.ods, .ots, flat .fods) into the workbook model. Each cell keeps the
 * text the file shows (text:p) and its typed value; formulas are kept, never recalculated. Repeated
 * cells and rows are expanded, but the empty padding spreadsheets add (up to a million rows) is cut.
 * Frozen panes come from settings.xml. Ported from the Linux app's reader; content.xml is streamed.
 */
object OdsReader {
    class NotOds(message: String) : Exception(message)

    private const val MAX_REPEAT = 10_000
    private const val MAX_COLS = 1024

    fun read(bytes: ByteArray): Workbook {
        val content: String
        var settings: String? = null
        if (ZipFiles.isZip(bytes)) {
            val files = ZipFiles.entries(bytes) { it == "content.xml" || it == "settings.xml" || it == "META-INF/manifest.xml" }
            if (files["META-INF/manifest.xml"]?.let { String(it, Charsets.UTF_8).contains("encryption-data") } == true) {
                throw NotOds("the spreadsheet is password protected")
            }
            content = files["content.xml"]?.let { String(it, Charsets.UTF_8) } ?: throw NotOds("content.xml is missing")
            settings = files["settings.xml"]?.let { String(it, Charsets.UTF_8) }
        } else {
            content = String(bytes, Charsets.UTF_8)
            if (!content.contains("office:spreadsheet")) throw NotOds("not an OpenDocument spreadsheet")
            settings = content
        }
        val styles = Styles(content)
        val wb = Workbook(readTables(content, styles), styles.list)
        settings?.let { freeze(it, wb) }
        if (wb.sheets.isEmpty()) throw NotOds("no sheets")
        return wb
    }

    /** "2.258cm" → characters, like XLSX widths. */
    internal fun widthChars(v: String?): Float? {
        val m = Regex("""([\d.]+)\s*(cm|mm|in|pt)?""").find(v ?: return null) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        val pt = when (m.groupValues[2]) { "cm" -> n * 28.35f; "mm" -> n * 2.835f; "in" -> n * 72f; else -> n }
        return maxOf(1f, (pt / 0.75f - 10f) / 7f)
    }

    private class Styles(content: String) {
        val list = arrayListOf(CellStyle())
        val cell = HashMap<String, Int>()
        val colWidth = HashMap<String, Float?>()
        val hiddenTables = HashSet<String>()

        init {
            val end = content.indexOf("<office:body").let { if (it < 0) content.length else it }
            val a = content.indexOf("<office:automatic-styles")
            val b = content.indexOf("</office:automatic-styles>")
            if (a in 0 until end && b > a) {
                val x = Jsoup.parse(content.substring(a, b + 26), "", Parser.xmlParser())
                val fmts = numberFormats(x)
                for (st in x.getElementsByTag("style:style")) {
                    val name = st.attr("style:name")
                    fun prop(tag: String) = st.children().firstOrNull { it.tagName() == tag }
                    when (st.attr("style:family")) {
                        "table-column" -> colWidth[name] = widthChars(prop("style:table-column-properties")?.attr("style:column-width"))
                        "table" -> if (prop("style:table-properties")?.attr("table:display") == "false") hiddenTables += name
                        "table-cell" -> {
                            val t = prop("style:text-properties")
                            val c = prop("style:table-cell-properties")
                            val p = prop("style:paragraph-properties")
                            fun hex(v: String?) = v?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { 0xFF000000.toInt() or it } ?: 0
                            val color = hex(t?.attr("fo:color")).takeIf { it != 0xFF000000.toInt() } ?: 0
                            val fill = hex(c?.attr("fo:background-color")).takeIf { it != 0xFFFFFFFF.toInt() } ?: 0
                            val border = listOf("fo:border", "fo:border-top", "fo:border-bottom", "fo:border-left", "fo:border-right")
                                .any { k -> c?.attr(k)?.let { it.isNotEmpty() && it != "none" } == true }
                            cell[name] = list.size
                            list += CellStyle(
                                format = fmts[st.attr("style:data-style-name")] ?: "General",
                                bold = t?.attr("fo:font-weight") in setOf("bold", "600", "700", "800", "900"),
                                italic = t?.attr("fo:font-style") == "italic",
                                underline = t?.attr("style:text-underline-style")?.let { it.isNotEmpty() && it != "none" } == true,
                                strike = t?.attr("style:text-line-through-style")?.let { it.isNotEmpty() && it != "none" } == true,
                                color = color, fill = fill,
                                align = when (p?.attr("fo:text-align")) { "start", "left" -> 'l'; "end", "right" -> 'r'; "center" -> 'c'; else -> 0.toChar() },
                                wrap = c?.attr("fo:wrap-option") == "wrap",
                                border = border,
                            )
                        }
                    }
                }
            }
        }

        /** The shown text is stored per cell; formats only matter for the "saved value" line. */
        fun numberFormats(x: org.jsoup.nodes.Document): Map<String, String> = emptyMap()
    }

    private fun readTables(content: String, styles: Styles): List<Sheet> {
        val sheets = ArrayList<Sheet>()
        val x = XmlScan(content)
        var sheet: Sheet? = null
        var row = 0
        var col = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.EOF -> return sheets
                XmlScan.Event.START -> when (x.name) {
                    "table:table" -> {
                        sheet = Sheet(x.attr("table:name") ?: "?", x.attr("table:style-name") in styles.hiddenTables)
                        sheets += sheet
                        row = 0; col = 0
                    }
                    "table:table-column" -> sheet?.let { s ->
                        val repeat = (x.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(MAX_COLS)
                        val width = styles.colWidth[x.attr("table:style-name")]
                        val hidden = x.attr("table:visibility") in setOf("collapse", "filter")
                        for (c in col until minOf(col + repeat, MAX_COLS)) {
                            width?.let { s.colWidths[c] = it }
                            if (hidden) s.hiddenCols += c
                        }
                        col += repeat
                    }
                    "table:table-row" -> sheet?.let { s ->
                        val repeat = x.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1
                        val hidden = x.attr("table:visibility") in setOf("collapse", "filter")
                        val cells = rowCells(x, row, s, styles)
                        if (cells.isEmpty() && repeat > 1) {
                            row += repeat // empty padding: nothing stored
                        } else {
                            for (k in 0 until minOf(repeat, MAX_REPEAT)) {
                                if (cells.isNotEmpty()) for ((c, cell) in cells) s.put(row + k, c, cell)
                                if (hidden) s.hiddenRows += row + k
                            }
                            row += repeat
                        }
                    }
                    "office:annotation", "table:shapes", "office:forms" -> x.skip()
                }
                XmlScan.Event.END -> if (x.name == "table:table") {
                    sheet?.let { s ->
                        s.hiddenRows.retainAll { it < s.maxRow }
                        s.hiddenCols.retainAll { it < maxOf(s.maxCol, 1) }
                    }
                    sheet = null
                    col = 0
                }
                else -> Unit
            }
        }
    }

    /** Reads the cells of the row the scanner is on (up to its end tag). */
    private fun rowCells(x: XmlScan, row: Int, sheet: Sheet, styles: Styles): Map<Int, Cell> {
        val cells = HashMap<Int, Cell>()
        if (x.empty) { x.next(); return cells }
        var col = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.EOF -> return cells
                XmlScan.Event.END -> if (x.name == "table:table-row") return cells
                XmlScan.Event.START -> when (x.name) {
                    "table:table-cell", "table:covered-table-cell" -> {
                        val covered = x.name == "table:covered-table-cell"
                        val repeat = x.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1
                        val type = x.attr("office:value-type")
                        val value = x.attr("office:value")
                        val boolValue = x.attr("office:boolean-value")
                        val date = x.attr("office:date-value")
                        val time = x.attr("office:time-value")
                        val formula = x.attr("table:formula")?.let {
                            "=" + it.removePrefix("of:=").removePrefix("of:").removePrefix("=")
                                .replace("[.", "").replace("]", "").replace(":.", ":")
                        }
                        val style = styles.cell[x.attr("table:style-name")] ?: 0
                        val spanC = x.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1
                        val spanR = x.attr("table:number-rows-spanned")?.toIntOrNull() ?: 1
                        val text = cellText(x)
                        if (!covered && (type != null || text.isNotEmpty())) {
                            val v: Any? = when (type) {
                                "float", "percentage", "currency" -> value?.toDoubleOrNull() ?: text
                                "boolean" -> boolValue == "true"
                                "date" -> date ?: text
                                "time" -> time ?: text
                                else -> text
                            }
                            val error = if (formula != null && text.startsWith("#") && (text.endsWith("!") || text.endsWith("?") || text.endsWith("A")) && type in setOf(null, "string")) text else null
                            val cell = Cell(v, formula, style, error = error, text = text)
                            for (k in 0 until minOf(repeat, MAX_COLS - col)) cells[col + k] = cell
                            if (spanC > 1 || spanR > 1) sheet.merges += intArrayOf(row, col, row + spanR - 1, col + spanC - 1)
                        }
                        col += repeat
                        if (col >= MAX_COLS) { skipRow(x); return cells }
                    }
                    else -> x.skip()
                }
                else -> Unit
            }
        }
    }

    private fun skipRow(x: XmlScan) {
        var depth = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.START -> if (!x.empty) depth++
                XmlScan.Event.END -> { if (depth == 0 && x.name == "table:table-row") return; if (depth > 0) depth-- }
                XmlScan.Event.EOF -> return
                else -> Unit
            }
        }
    }

    /** The shown text of a cell: its paragraphs, joined by line breaks (notes left out). */
    private fun cellText(x: XmlScan): String {
        if (x.empty) { x.next(); return "" }
        val sb = StringBuilder()
        var paragraphs = 0
        var depth = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.EOF -> return sb.toString()
                XmlScan.Event.TEXT -> if (depth > 0) sb.append(x.text)
                XmlScan.Event.START -> when (x.name) {
                    "office:annotation" -> x.skip()
                    "text:p", "text:h" -> { if (paragraphs++ > 0) sb.append('\n'); if (!x.empty) depth++ }
                    "text:s" -> sb.append(" ".repeat((x.attr("text:c")?.toIntOrNull() ?: 1).coerceIn(1, 100)))
                    "text:tab" -> sb.append('\t')
                    "text:line-break" -> sb.append('\n')
                    else -> if (!x.empty) depth++
                }
                XmlScan.Event.END -> {
                    if (x.name == "table:table-cell" || x.name == "table:covered-table-cell") return sb.toString()
                    if (depth > 0) depth--
                }
            }
        }
    }

    /** settings.xml: per table, HorizontalSplitMode/VerticalSplitMode 2 = frozen at the positions. */
    internal fun freeze(settings: String, wb: Workbook) {
        val i = settings.indexOf("config:name=\"Tables\"")
        if (i < 0) return
        val x = Jsoup.parse(settings.substring(settings.lastIndexOf('<', i)), "", Parser.xmlParser())
        val map = x.children().firstOrNull() ?: return
        for (entry in map.getElementsByTag("config:config-item-map-entry")) {
            val sheet = wb.sheets.firstOrNull { it.name == entry.attr("config:name") } ?: continue
            fun item(n: String) = entry.children().firstOrNull { it.attr("config:name") == n }?.text()?.toIntOrNull() ?: 0
            if (item("HorizontalSplitMode") == 2) sheet.frozenCols = item("HorizontalSplitPosition")
            if (item("VerticalSplitMode") == 2) sheet.frozenRows = item("VerticalSplitPosition")
        }
    }
}
