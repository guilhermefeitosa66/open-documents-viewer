package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import java.nio.charset.Charset

/**
 * Excel 97–2003 (.xls, BIFF8; Excel 5/95 BIFF5 best effort) into the workbook model, with no
 * library: the records of the "Workbook" stream of the OLE2 container. Values saved in the file
 * (formula results, never recalculated; the formula text itself is not rebuilt), number formats,
 * fonts, fills, alignment, merged cells, column widths, hidden rows, columns and sheets, frozen panes.
 */
object XlsReader {
    class NotXls(message: String) : Exception(message)
    class Protected : Exception("the workbook is password protected")

    private val PALETTE = intArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0x800000, 0x008000, 0x000080, 0x808000,
        0x800080, 0x008080, 0xC0C0C0, 0x808080, 0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF, 0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99,
        0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99, 0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    )

    private class Rec(val type: Int, val data: ByteArray, val pos: Int)

    private class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int)
    private class Xf(val font: Int, val format: Int, val align: Char, val wrap: Boolean, val fillColor: Int, val border: Boolean)

    fun read(bytes: ByteArray): Workbook {
        val cfb = try { Cfb.open(bytes) } catch (e: Cfb.NotCfb) { throw NotXls(e.message ?: "not an Excel file") }
        val biff8 = cfb.has("Workbook")
        val stream = cfb.read("Workbook") ?: cfb.read("Book") ?: throw NotXls("not an Excel 97–2003 workbook")
        val recs = records(stream)
        if (recs.any { it.type == 0x002F }) throw Protected()

        var codepage: Charset = Charset.forName("windows-1252")
        var date1904 = false
        val formats = HashMap<Int, String>(NumberFormat.BUILTIN)
        val fonts = ArrayList<Font>()
        val xfs = ArrayList<Xf>()
        val palette = PALETTE.copyOfRange(0, 8) + PALETTE // 0–7 fixed, then the 56 default colors from index 8
        val sst = ArrayList<String>()
        class SheetInfo(val name: String, val offset: Int, val hidden: Boolean, val type: Int)
        val infos = ArrayList<SheetInfo>()

        // Globals: up to the first EOF.
        var i = 0
        while (i < recs.size) {
            val r = recs[i]
            val d = r.data
            when (r.type) {
                0x000A -> break
                0x0042 -> if (d.size >= 2) u16(d, 0).let { cp -> if (cp != 1200) runCatching { codepage = Charset.forName(if (cp == 0x8000) "x-MacRoman" else "windows-$cp") } }
                0x0022 -> date1904 = d.size >= 2 && u16(d, 0) == 1
                0x041E, 0x001E -> if (d.size >= 3) {
                    val idx = u16(d, 0)
                    formats[idx] = if (biff8) xlString(d, 2, codepage).first else String(d, 3, (d[2].toInt() and 0xFF).coerceAtMost(d.size - 3), codepage)
                }
                0x0031 -> if (d.size >= 14) {
                    val grbit = u16(d, 2)
                    fonts += Font(u16(d, 6) >= 700, grbit and 0x02 != 0, (d[10].toInt() and 0xFF) != 0, grbit and 0x08 != 0, u16(d, 4))
                }
                0x00E0 -> if (d.size >= 20) {
                    val align = d[6].toInt() and 0x07
                    val wrap = d[6].toInt() and 0x08 != 0
                    // BIFF8: pattern colors in bits of the last dword.
                    val fill = if (biff8) {
                        val pattern = (u32(d, 14) ushr 26) and 0x3F
                        val fore = u16(d, 18) and 0x7F
                        if (pattern != 0) fore else -1
                    } else -1
                    val border = biff8 && (u32(d, 10) and 0x0000FFFF) != 0
                    xfs += Xf(u16(d, 0), u16(d, 2), when (align) { 1 -> 'l'; 2, 6 -> 'c'; 3 -> 'r'; else -> 0.toChar() }, wrap, fill, border)
                }
                0x0092 -> if (d.size >= 2) {
                    val n = u16(d, 0)
                    for (k in 0 until n) if (2 + k * 4 + 3 <= d.size) {
                        palette.getOrNull(8 + k)?.let { palette[8 + k] = ((d[2 + k * 4].toInt() and 0xFF) shl 16) or ((d[3 + k * 4].toInt() and 0xFF) shl 8) or (d[4 + k * 4].toInt() and 0xFF) }
                    }
                }
                0x0085 -> if (d.size >= 8) {
                    val off = u32(d, 0)
                    val vis = d[4].toInt() and 0xFF
                    val type = d[5].toInt() and 0xFF
                    val name = if (biff8) shortXlString(d, 6, codepage) else String(d, 7, (d[6].toInt() and 0xFF).coerceAtMost(d.size - 7), codepage)
                    infos += SheetInfo(name, off, vis != 0, type)
                }
                0x00FC -> {
                    // SST spans CONTINUE records: gather them.
                    val parts = arrayListOf(d)
                    var j = i + 1
                    while (j < recs.size && recs[j].type == 0x003C) { parts += recs[j].data; j++ }
                    sst += readSst(parts, codepage)
                    i = j - 1
                }
            }
            i++
        }

        fun color(idx: Int): Int = when {
            idx in 0 until palette.size -> 0xFF000000.toInt() or palette[idx]
            else -> 0
        }
        val styles = xfs.map { xf ->
            val font = fonts.getOrNull(if (xf.font >= 4) xf.font - 1 else xf.font)
            CellStyle(
                format = formats[xf.format] ?: "General",
                bold = font?.bold == true, italic = font?.italic == true, underline = font?.underline == true, strike = font?.strike == true,
                color = font?.color?.let { if (it == 0x7FFF || it == 8) 0 else color(it) }?.takeIf { it != 0xFF000000.toInt() } ?: 0,
                fill = if (xf.fillColor in 0..63) color(xf.fillColor).takeIf { it != 0xFFFFFFFF.toInt() } ?: 0 else 0,
                align = xf.align, wrap = xf.wrap, border = xf.border,
            )
        }.ifEmpty { listOf(CellStyle()) }

        // Sheets: each starts at its BOF offset in the stream.
        val byPos = HashMap<Int, Int>()
        recs.forEachIndexed { k, r -> byPos[r.pos] = k }
        val sheets = ArrayList<Sheet>()
        var budget = XlsxReader.MAX_CELLS
        for (info in infos) {
            if (info.type != 0) continue // charts and macro sheets
            val sheet = Sheet(info.name, info.hidden)
            sheets += sheet
            var k = (byPos[info.offset] ?: continue) + 1
            var pendingString: Pair<Int, Int>? = null
            var pendingStyle = 0
            var frozen = false
            while (k < recs.size) {
                val r = recs[k]
                val d = r.data
                if (r.type == 0x000A) break
                if (budget <= 0) { sheet.truncated = true; break }
                when (r.type) {
                    0x0203 -> if (d.size >= 14) { sheet.put(u16(d, 0), u16(d, 2), Cell(f64(d, 6), style = u16(d, 4))); budget-- }
                    0x027E -> if (d.size >= 10) { sheet.put(u16(d, 0), u16(d, 2), Cell(rk(u32(d, 6)), style = u16(d, 4))); budget-- }
                    0x00BD -> if (d.size >= 6) {
                        val row = u16(d, 0)
                        var col = u16(d, 2)
                        var p = 4
                        while (p + 6 <= d.size - 2) { sheet.put(row, col, Cell(rk(u32(d, p + 2)), style = u16(d, p))); col++; p += 6; budget-- }
                    }
                    0x00FD -> if (d.size >= 10) { sheet.put(u16(d, 0), u16(d, 2), Cell(sst.getOrNull(u32(d, 6)) ?: "", style = u16(d, 4))); budget-- }
                    0x0204, 0x00D6 -> if (d.size >= 8) {
                        val text = if (biff8) xlString(d, 6, codepage).first else String(d, 8, u16(d, 6).coerceAtMost(d.size - 8), codepage)
                        sheet.put(u16(d, 0), u16(d, 2), Cell(text, style = u16(d, 4))); budget--
                    }
                    0x0205 -> if (d.size >= 8) {
                        val v = d[6].toInt() and 0xFF
                        val cell = if (d[7].toInt() == 0) Cell(v != 0, style = u16(d, 4)) else Cell(errorText(v), style = u16(d, 4), error = errorText(v))
                        sheet.put(u16(d, 0), u16(d, 2), cell); budget--
                    }
                    0x0006 -> if (d.size >= 20) {
                        val row = u16(d, 0); val col = u16(d, 2); val st = u16(d, 4)
                        val special = (d[12].toInt() and 0xFF) == 0xFF && (d[13].toInt() and 0xFF) == 0xFF
                        if (!special) { sheet.put(row, col, Cell(f64(d, 6), style = st)); budget-- }
                        else when (d[6].toInt() and 0xFF) {
                            0 -> { pendingString = row to col; pendingStyle = st }
                            1 -> { sheet.put(row, col, Cell((d[8].toInt() and 0xFF) != 0, style = st)); budget-- }
                            2 -> { val e = errorText(d[8].toInt() and 0xFF); sheet.put(row, col, Cell(e, style = st, error = e)); budget-- }
                        }
                    }
                    0x0207 -> pendingString?.let { (row, col) ->
                        val text = if (biff8) xlString(d, 0, codepage).first else String(d, 2, u16(d, 0).coerceAtMost(d.size - 2), codepage)
                        sheet.put(row, col, Cell(text, style = pendingStyle)); budget--
                        pendingString = null
                    }
                    0x0208 -> if (d.size >= 16) {
                        val row = u16(d, 0)
                        val grbit = u16(d, 12)
                        if (grbit and 0x20 != 0) sheet.hiddenRows += row
                        if (grbit and 0x40 != 0) sheet.rowHeights[row] = (u16(d, 6) and 0x7FFF) / 20f
                    }
                    0x007D -> if (d.size >= 10) {
                        val first = u16(d, 0); val last = minOf(u16(d, 2), 1023)
                        val width = u16(d, 4) / 256f
                        val hidden = u16(d, 8) and 0x01 != 0
                        for (c in first..last) { sheet.colWidths[c] = width; if (hidden) sheet.hiddenCols += c }
                    }
                    0x00E5 -> if (d.size >= 2) {
                        val n = u16(d, 0)
                        for (m in 0 until n) if (2 + m * 8 + 8 <= d.size) {
                            val o = 2 + m * 8
                            sheet.merges += intArrayOf(u16(d, o), u16(d, o + 4), u16(d, o + 2), u16(d, o + 6))
                        }
                    }
                    0x023E -> if (d.size >= 2) frozen = u16(d, 0) and 0x0008 != 0
                    0x0041 -> if (frozen && d.size >= 4) { sheet.frozenCols = u16(d, 0); sheet.frozenRows = u16(d, 2) }
                    0x0809 -> { // an embedded substream (chart): skip to its EOF
                        var depth = 1
                        while (++k < recs.size && depth > 0) { if (recs[k].type == 0x0809) depth++ else if (recs[k].type == 0x000A) depth-- }
                        continue
                    }
                }
                k++
            }
        }
        if (sheets.isEmpty()) throw NotXls("no worksheets")
        return Workbook(sheets, styles, date1904)
    }

    private fun records(s: ByteArray): List<Rec> {
        val out = ArrayList<Rec>()
        var p = 0
        while (p + 4 <= s.size) {
            val type = u16(s, p)
            val len = u16(s, p + 2)
            if (p + 4 + len > s.size) break
            out += Rec(type, s.copyOfRange(p + 4, p + 4 + len), p)
            p += 4 + len
        }
        return out
    }

    /** RK numbers: 30 bits of an IEEE double's high part or an integer, optionally ÷ 100. */
    internal fun rk(v: Int): Double {
        val div100 = v and 0x01 != 0
        val n = if (v and 0x02 != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (div100) n / 100 else n
    }

    private fun errorText(code: Int) = when (code) {
        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"
        else -> "#ERR"
    }

    /** XLUnicodeString: cch u16, flags u8, chars. Returns the text and the bytes used. */
    private fun xlString(d: ByteArray, off: Int, cp: Charset): Pair<String, Int> {
        if (off + 3 > d.size) return "" to 0
        val cch = u16(d, off)
        val flags = d[off + 2].toInt()
        return chars(d, off + 3, cch, flags and 0x01 != 0, cp)
    }

    private fun shortXlString(d: ByteArray, off: Int, cp: Charset): String {
        if (off + 2 > d.size) return ""
        val cch = d[off].toInt() and 0xFF
        return chars(d, off + 2, cch, d[off + 1].toInt() and 0x01 != 0, cp).first
    }

    private fun chars(d: ByteArray, off: Int, cch: Int, wide: Boolean, cp: Charset): Pair<String, Int> {
        val n = if (wide) minOf(cch * 2, d.size - off) else minOf(cch, d.size - off)
        if (n <= 0) return "" to 0
        return (if (wide) String(d, off, n, Charsets.UTF_16LE) else String(d, off, n, Charsets.ISO_8859_1)) to n
    }

    /**
     * The shared string table. Strings may cross CONTINUE records; the first byte of a
     * continued record repeats the "wide" flag for the rest of the characters.
     */
    private fun readSst(parts: List<ByteArray>, cp: Charset): List<String> {
        val out = ArrayList<String>()
        var part = 0
        var p = 8
        fun avail() = parts[part].size - p
        fun next(): Boolean { part++; p = 0; return part < parts.size }
        fun u8(): Int { if (avail() < 1 && !next()) return 0; return parts[part][p++].toInt() and 0xFF }
        fun u16r(): Int = u8() or (u8() shl 8)
        fun u32r(): Int = u16r() or (u16r() shl 16)
        fun skip(n0: Int) { var n = n0; while (n > 0) { if (avail() <= 0 && !next()) return; val k = minOf(n, avail()); p += k; n -= k } }
        val total = if (parts[0].size >= 8) u32(parts[0], 4) else 0
        while (out.size < total && part < parts.size) {
            if (avail() <= 0 && !next()) break
            val cch = u16r()
            val flags = u8()
            var wide = flags and 0x01 != 0
            val runs = if (flags and 0x08 != 0) u16r() else 0
            val ext = if (flags and 0x04 != 0) u32r() else 0
            val sb = StringBuilder(cch)
            var left = cch
            while (left > 0) {
                if (avail() <= 0) {
                    if (!next()) break
                    wide = (parts[part][p++].toInt() and 0x01) != 0
                }
                val take = minOf(left, if (wide) avail() / 2 else avail())
                if (take <= 0) { if (!next()) break; wide = (parts[part][p++].toInt() and 0x01) != 0; continue }
                val len = if (wide) take * 2 else take
                sb.append(if (wide) String(parts[part], p, len, Charsets.UTF_16LE) else String(parts[part], p, len, Charsets.ISO_8859_1))
                p += len
                left -= take
            }
            skip(runs * 4 + ext)
            out += sb.toString()
        }
        return out
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)
    private fun f64(d: ByteArray, o: Int): Double {
        var bits = 0L
        for (k in 7 downTo 0) bits = (bits shl 8) or (d[o + k].toLong() and 0xFF)
        return java.lang.Double.longBitsToDouble(bits)
    }
}
