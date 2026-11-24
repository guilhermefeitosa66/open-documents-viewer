package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Excel 2007+ (.xlsx, .xlsm, .xltx): workbook, styles, shared strings and every sheet, with the
 * values saved in the file. Sheets and shared strings are streamed with [XmlScan]. Ported from the
 * Linux app's reader.
 */
object XlsxReader {
    class NotXlsx(message: String) : Exception(message)

    /** Cells kept for the whole workbook; beyond it rows are dropped and the sheet says so. */
    const val MAX_CELLS = 3_000_000

    private val THEME = intArrayOf(0xFFFFFF, 0x000000, 0xE7E6E6, 0x44546A, 0x4472C4, 0xED7D31, 0xA5A5A5, 0xFFC000, 0x5B9BD5, 0x70AD47)
    private val INDEXED = mapOf(
        8 to 0x000000, 9 to 0xFFFFFF, 10 to 0xFF0000, 11 to 0x00FF00, 12 to 0x0000FF, 13 to 0xFFFF00, 14 to 0xFF00FF,
        15 to 0x00FFFF, 16 to 0x800000, 17 to 0x008000, 18 to 0x000080, 19 to 0x808000, 20 to 0x800080, 21 to 0x008080,
        22 to 0xC0C0C0, 23 to 0x808080,
    )

    fun read(bytes: ByteArray): Workbook {
        if (!ZipFiles.isZip(bytes)) throw NotXlsx("not a zip file (an encrypted or older Excel file?)")
        val files = ZipFiles.entries(bytes) { it.endsWith(".xml") || it.endsWith(".rels") }
        val wbXml = files["xl/workbook.xml"] ?: throw NotXlsx("xl/workbook.xml is missing")
        val wb = xml(wbXml)
        val rels = rels(files, "xl/workbook.xml")
        val date1904 = wb.getElementsByTag("workbookPr").firstOrNull()?.attr("date1904") in setOf("1", "true")
        val styles = styles(files["xl/styles.xml"])
        val shared = shared(files["xl/sharedStrings.xml"])
        val sheets = ArrayList<Sheet>()
        var cells = 0
        for (sh in wb.getElementsByTag("sheet")) {
            val sheet = Sheet(sh.attr("name").ifEmpty { "?" }, sh.attr("state") in setOf("hidden", "veryHidden"))
            val target = rels[sh.attr("r:id")]
            files[target]?.let { data ->
                cells += readSheet(String(data, Charsets.UTF_8), sheet, shared, MAX_CELLS - cells)
                target?.let { charts(files, it, sheet) }
            }
            sheets += sheet
        }
        if (sheets.isEmpty()) throw NotXlsx("no sheets")
        return Workbook(sheets, styles, date1904)
    }

    private fun xml(b: ByteArray): org.jsoup.nodes.Document = Jsoup.parse(String(b, Charsets.UTF_8), "", Parser.xmlParser())

    private fun rels(files: Map<String, ByteArray>, part: String): Map<String, String> {
        val base = part.substringBeforeLast('/', "")
        val path = (if (base.isEmpty()) "" else "$base/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val x = files[path]?.let(::xml) ?: return emptyMap()
        return x.getElementsByTag("Relationship").associate { r ->
            val t = r.attr("Target")
            r.attr("Id") to (if (t.startsWith("/")) t.trimStart('/') else ZipFiles.resolve(base, t))
        }
    }

    private fun color(e: Element?): Int {
        if (e == null) return 0
        e.attr("rgb").takeIf { it.length >= 6 }?.takeLast(6)?.toIntOrNull(16)?.let { return 0xFF000000.toInt() or it }
        e.attr("theme").toIntOrNull()?.let { i -> THEME.getOrNull(i)?.let { return 0xFF000000.toInt() or it } }
        e.attr("indexed").toIntOrNull()?.let { i -> INDEXED[i]?.let { return 0xFF000000.toInt() or it } }
        return 0
    }

    private fun on(e: Element?) = e != null && e.attr("val") !in setOf("0", "false")

    private fun styles(data: ByteArray?): List<CellStyle> {
        val x = data?.let(::xml) ?: return listOf(CellStyle())
        val fmts = HashMap(NumberFormat.BUILTIN)
        x.getElementsByTag("numFmt").forEach { f -> f.attr("numFmtId").toIntOrNull()?.let { fmts[it] = f.attr("formatCode").ifEmpty { "General" } } }
        class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int)
        val fonts = x.getElementsByTag("fonts").firstOrNull()?.children()?.map { f ->
            fun child(t: String) = f.children().firstOrNull { it.tagName() == t }
            Font(on(child("b")), on(child("i")), child("u")?.let { it.attr("val") != "none" } == true, on(child("strike")), color(child("color")))
        }.orEmpty()
        val fills = x.getElementsByTag("fills").firstOrNull()?.children()?.map { f ->
            val p = f.children().firstOrNull { it.tagName() == "patternFill" }
            if (p == null || p.attr("patternType").let { it.isEmpty() || it == "none" }) 0
            else color(p.children().firstOrNull { it.tagName() == "fgColor" })
        }.orEmpty()
        val borders = x.getElementsByTag("borders").firstOrNull()?.children()?.map { b -> b.children().any { it.attr("style").isNotEmpty() } }.orEmpty()
        val xfs = x.getElementsByTag("cellXfs").firstOrNull()?.children()?.filter { it.tagName() == "xf" } ?: return listOf(CellStyle())
        val out = xfs.map { xf ->
            val font = fonts.getOrNull(xf.attr("fontId").toIntOrNull() ?: 0)
            val fill = fills.getOrNull(xf.attr("fillId").toIntOrNull() ?: 0) ?: 0
            val al = xf.children().firstOrNull { it.tagName() == "alignment" }
            CellStyle(
                format = fmts[xf.attr("numFmtId").toIntOrNull() ?: 0] ?: "General",
                bold = font?.bold == true, italic = font?.italic == true, underline = font?.underline == true, strike = font?.strike == true,
                color = font?.color?.takeIf { it != 0xFF000000.toInt() } ?: 0,
                fill = fill.takeIf { it != 0xFFFFFFFF.toInt() } ?: 0,
                align = when (al?.attr("horizontal")) { "left" -> 'l'; "center", "centerContinuous" -> 'c'; "right" -> 'r'; else -> 0.toChar() },
                wrap = al?.attr("wrapText") in setOf("1", "true"),
                border = borders.getOrNull(xf.attr("borderId").toIntOrNull() ?: 0) == true,
            )
        }
        return out.ifEmpty { listOf(CellStyle()) }
    }

    private fun shared(data: ByteArray?): List<String> {
        data ?: return emptyList()
        val out = ArrayList<String>()
        val x = XmlScan(String(data, Charsets.UTF_8))
        val sb = StringBuilder()
        var inSi = false
        var skipDepth = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.START -> when (x.local) {
                    "si" -> { inSi = true; sb.clear() }
                    "rPh", "phoneticPr" -> if (!x.empty) skipDepth++ // phonetic guides are not shown
                    "t" -> if (inSi && skipDepth == 0) sb.append(x.readText())
                }
                XmlScan.Event.END -> when (x.local) {
                    "si" -> { out += sb.toString(); inSi = false }
                    "rPh" -> if (skipDepth > 0) skipDepth--
                }
                XmlScan.Event.EOF -> return out
                else -> Unit
            }
        }
    }

    /** Reads one worksheet into [sheet]; returns the number of cells kept. */
    internal fun readSheet(xml: String, sheet: Sheet, shared: List<String>, budget: Int): Int {
        val x = XmlScan(xml)
        var kept = 0
        var row = -1
        var nextCol = 0
        loop2@ while (true) {
            when (x.next()) {
                XmlScan.Event.EOF -> return kept
                XmlScan.Event.START -> when (x.local) {
                    "row" -> {
                        row = (x.attr("r")?.toIntOrNull() ?: (row + 2)) - 1
                        nextCol = 0
                        if (x.attr("hidden") in setOf("1", "true")) sheet.hiddenRows += row
                        x.attr("ht")?.toFloatOrNull()?.let { if (x.attr("customHeight") in setOf("1", "true")) sheet.rowHeights[row] = it }
                        if (row + 1 > sheet.maxRow) sheet.maxRow = row + 1
                    }
                    "c" -> {
                        val ref = x.attr("r")
                        val parsed = ref?.let { Workbook.parseRef(it) }
                        val col = parsed?.second ?: nextCol
                        nextCol = col + 1
                        val r = parsed?.first ?: row
                        val type = x.attr("t") ?: "n"
                        val style = x.attr("s")?.toIntOrNull() ?: 0
                        var v: String? = null
                        var f: String? = null
                        var inline: String? = null
                        if (!x.empty) {
                            loop@ while (true) {
                                when (x.next()) {
                                    XmlScan.Event.START -> when (x.local) {
                                        "v" -> v = x.readText()
                                        "f" -> f = x.readText().ifEmpty { null }
                                        "is" -> inline = inlineString(x)
                                        else -> x.skip()
                                    }
                                    XmlScan.Event.END -> if (x.local == "c") break@loop
                                    XmlScan.Event.EOF -> break@loop
                                    else -> Unit
                                }
                            }
                        } else x.next()
                        if (kept >= budget || r >= Workbook.MAX_ROWS || col >= Workbook.MAX_COLS) { sheet.truncated = sheet.truncated || kept >= budget; continue@loop2 }
                        val formula = f?.let { "=$it" }
                        val cell: Cell? = when {
                            type == "inlineStr" -> Cell(inline.orEmpty(), formula, style)
                            v == null -> if (formula == null && style == 0) null else Cell(null, formula, style)
                            type == "s" -> Cell(v.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: "", formula, style)
                            type == "b" -> Cell(v.trim() == "1" || v.trim() == "true", formula, style)
                            type == "e" -> Cell(v, formula, style, error = v)
                            type == "str" || type == "d" -> Cell(v, formula, style)
                            else -> Cell(v.trim().toDoubleOrNull() ?: v, formula, style)
                        }
                        if (cell != null) { sheet.put(r, col, cell); kept++ }
                    }
                    "col" -> {
                        val lo = (x.attr("min")?.toIntOrNull() ?: 1) - 1
                        val hi = minOf(x.attr("max")?.toIntOrNull() ?: 1, 1024) - 1
                        val w = x.attr("width")?.toFloatOrNull()
                        val hidden = x.attr("hidden") in setOf("1", "true")
                        for (c in lo..hi) { w?.let { sheet.colWidths[c] = it }; if (hidden) sheet.hiddenCols += c }
                    }
                    "pane" -> if (x.attr("state") in setOf("frozen", "frozenSplit")) {
                        sheet.frozenRows = x.attr("ySplit")?.toDoubleOrNull()?.toInt() ?: 0
                        sheet.frozenCols = x.attr("xSplit")?.toDoubleOrNull()?.toInt() ?: 0
                    }
                    "mergeCell" -> x.attr("ref")?.takeIf { ':' in it }?.let { ref ->
                        val (r1, c1) = Workbook.parseRef(ref.substringBefore(':'))
                        val (r2, c2) = Workbook.parseRef(ref.substringAfter(':'))
                        sheet.merges += intArrayOf(r1, c1, r2, c2)
                    }
                    "dimension" -> x.attr("ref")?.takeIf { ':' in it }?.let { ref ->
                        val (_, c2) = Workbook.parseRef(ref.substringAfter(':'))
                        if (c2 + 1 > sheet.maxCol) sheet.maxCol = minOf(c2 + 1, 1024)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun inlineString(x: XmlScan): String {
        val sb = StringBuilder()
        if (x.empty) { x.next(); return "" }
        var depth = 0
        while (true) {
            when (x.next()) {
                XmlScan.Event.START -> if (x.local == "t") sb.append(x.readText()) else if (x.local == "rPh") x.skip() else if (!x.empty) depth++
                XmlScan.Event.END -> { if (depth == 0) return sb.toString(); depth-- }
                XmlScan.Event.EOF -> return sb.toString()
                else -> Unit
            }
        }
    }

    private fun charts(files: Map<String, ByteArray>, sheetPath: String, sheet: Sheet) {
        for (drawing in rels(files, sheetPath).values.filter { it.contains("drawings/") && it.endsWith(".xml") }) {
            for (chart in rels(files, drawing).values.filter { it.contains("charts/") }) {
                val x = files[chart]?.let(::xml) ?: continue
                val title = x.getElementsByTag("c:title").firstOrNull()?.getElementsByTag("a:t")?.joinToString("") { it.text() }?.trim()
                sheet.charts += title.orEmpty()
            }
        }
    }
}
