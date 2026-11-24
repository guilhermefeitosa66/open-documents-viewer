package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * The spreadsheet model shared by XLSX, ODS and XLS: values as saved in the file (formulas are
 * shown, never recalculated), styles, merges, sizes, frozen panes. Number formatting follows
 * Excel's format codes; ported from the Linux app.
 */
class CellStyle(
    val format: String = "General",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** ARGB or 0. */
    val color: Int = 0,
    val fill: Int = 0,
    /** 'l', 'c', 'r' or 0 (by type: numbers right, text left). */
    val align: Char = 0.toChar(),
    val wrap: Boolean = false,
    val border: Boolean = false,
)

class Cell(
    /** String, Double, Boolean or null (as saved). */
    val value: Any?,
    val formula: String? = null,
    val style: Int = 0,
    val error: String? = null,
    /** The shown text when the file stores it (ODS). */
    val text: String? = null,
)

class Sheet(val name: String, val hidden: Boolean = false) {
    /** Row → column → cell, both 0-based. */
    val rows = HashMap<Int, HashMap<Int, Cell>>()
    var maxRow = 0
    var maxCol = 0
    /** Column → width in characters. */
    val colWidths = HashMap<Int, Float>()
    /** Row → height in points. */
    val rowHeights = HashMap<Int, Float>()
    val hiddenCols = HashSet<Int>()
    val hiddenRows = HashSet<Int>()
    /** (r1, c1, r2, c2), inclusive, 0-based. */
    val merges = ArrayList<IntArray>()
    var frozenRows = 0
    var frozenCols = 0
    /** Chart titles (charts are not drawn). */
    val charts = ArrayList<String>()
    /** Rows beyond the limit were not read. */
    var truncated = false

    fun cell(r: Int, c: Int): Cell? = rows[r]?.get(c)

    fun put(r: Int, c: Int, cell: Cell) {
        rows.getOrPut(r) { HashMap() }[c] = cell
        if (r + 1 > maxRow) maxRow = r + 1
        if (c + 1 > maxCol) maxCol = c + 1
    }
}

class Workbook(val sheets: List<Sheet>, val styles: List<CellStyle>, val date1904: Boolean = false) {
    fun style(i: Int) = styles.getOrNull(i) ?: styles.firstOrNull() ?: CellStyle()

    /** What the spreadsheet shows in the cell. */
    fun display(cell: Cell): String {
        cell.error?.let { return it }
        cell.text?.let { return it }
        return NumberFormat.format(cell.value, style(cell.style).format, date1904)
    }

    /** Plain text of every visible sheet (source view, Copy all). */
    fun plainText(): String = buildString {
        for (s in sheets) {
            if (length > 8_000_000) { append("…\n"); break }
            if (s.hidden) continue
            append("# ").append(s.name).append('\n')
            for (r in 0 until s.maxRow) {
                val row = s.rows[r] ?: continue
                if (r in s.hiddenRows) continue
                if (length > 8_000_000) break
                append((0 until s.maxCol).filter { it !in s.hiddenCols }.joinToString("\t") { c -> row[c]?.let(::display).orEmpty() }.trimEnd())
                append('\n')
            }
            append('\n')
        }
    }

    companion object {
        const val MAX_ROWS = 1_000_000
        const val MAX_COLS = 16_384

        fun colName(c: Int): String {
            var n = c + 1
            val sb = StringBuilder()
            while (n > 0) {
                val rem = (n - 1) % 26
                sb.insert(0, ('A' + rem))
                n = (n - 1) / 26
            }
            return sb.toString()
        }

        /** "B12" → (11, 1); "$AA$3" works too. */
        fun parseRef(ref: String): Pair<Int, Int> {
            var i = 0
            var col = 0
            while (i < ref.length && (ref[i] == '$')) i++
            while (i < ref.length && ref[i].isLetter()) { col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
            while (i < ref.length && ref[i] == '$') i++
            var row = 0
            while (i < ref.length && ref[i].isDigit()) { row = row * 10 + (ref[i] - '0'); i++ }
            return ((if (row == 0) 1 else row) - 1) to (col - 1)
        }
    }
}

object NumberFormat {
    val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        12 to "# ?/?", 13 to "# ??/??", 14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "yyyy-mm-dd h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;(#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
    )
    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun serialToDateTime(serial: Double, date1904: Boolean): LocalDateTime {
        var s = serial
        val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
        if (!date1904 && s < 61) s += 1 // Excel's phantom 1900-02-29
        val days = floor(s).toLong()
        val nanos = Math.round((s - days) * 86_400_000.0) * 1_000_000L
        return base.plusDays(days).plusNanos(nanos)
    }

    fun general(n: Double): String {
        if (n == floor(n) && abs(n) < 1e15) return n.toLong().toString()
        if (abs(n) >= 1e11 || abs(n) < 1e-9) {
            return String.format(Locale.ROOT, "%.5E", n).replace("E+0", "E+").replace("E-0", "E-")
        }
        val s = java.math.BigDecimal(n).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
        return s
    }

    fun splitSections(code: String): List<String> {
        val parts = ArrayList<String>()
        val buf = StringBuilder()
        var quote = false
        var bracket = false
        for (ch in code) {
            if (ch == '"') quote = !quote
            else if (ch == '[' && !quote) bracket = true
            else if (ch == ']' && !quote) bracket = false
            if (ch == ';' && !quote && !bracket) { parts += buf.toString(); buf.clear() } else buf.append(ch)
        }
        parts += buf.toString()
        return parts
    }

    private val DATE_TOKENS = Regex(""""[^"]*"|\\.|\[[^\]]*]|yyyy|yy|mmmmm|mmmm|mmm|mm|m|dddd|ddd|dd|d|hh|h|ss|s|AM/PM|A/P|\.0+""", RegexOption.IGNORE_CASE)

    fun isDate(code: String): Boolean {
        val stripped = Regex(""""[^"]*"|\\.|\[(?![hms]+])[^\]]*]""", RegexOption.IGNORE_CASE).replace(code, "")
        return Regex("[ymdhs]", RegexOption.IGNORE_CASE).containsMatchIn(stripped)
    }

    private fun formatDate(code: String, dt: LocalDateTime, serial: Double): String {
        val out = StringBuilder()
        val tokens = DATE_TOKENS.findAll(code).toList()
        val ampm = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE).containsMatchIn(code)
        var last = 0
        var prevKind: Char? = null
        for ((i, m) in tokens.withIndex()) {
            out.append(code.substring(last, m.range.first).replace("\\", ""))
            val tok = m.value
            val low = tok.lowercase()
            when {
                tok.startsWith("\"") -> out.append(tok.substring(1, tok.length - 1))
                tok.startsWith("\\") -> out.append(tok.substring(1))
                tok.startsWith("[") -> when (low.substring(1, low.length - 1)) {
                    "h", "hh" -> out.append((serial * 24).toLong())
                    "m", "mm" -> out.append((serial * 1440).toLong())
                    "s", "ss" -> out.append(Math.round(serial * 86400))
                }
                low == "yyyy" -> out.append(String.format(Locale.ROOT, "%04d", dt.year))
                low == "yy" -> out.append(String.format(Locale.ROOT, "%02d", dt.year % 100))
                low == "mmm" || low == "mmmm" || low == "mmmmm" -> {
                    val name = MONTHS[dt.monthValue - 1]
                    out.append(if (low == "mmmm") name else if (low == "mmmmm") name.take(1) else name.take(3))
                }
                low == "mm" || low == "m" -> {
                    val nxt = tokens.drop(i + 1).map { it.value.lowercase() }.firstOrNull { it.isNotEmpty() && it[0] in "ymdhs" }.orEmpty()
                    val minute = prevKind == 'h' || nxt.startsWith("s")
                    val v = if (minute) dt.minute else dt.monthValue
                    out.append(if (low == "mm") String.format(Locale.ROOT, "%02d", v) else v.toString())
                }
                low == "dddd" || low == "ddd" -> { val n = DAYS[dt.dayOfWeek.value - 1]; out.append(if (low == "dddd") n else n.take(3)) }
                low == "dd" || low == "d" -> out.append(if (low == "dd") String.format(Locale.ROOT, "%02d", dt.dayOfMonth) else dt.dayOfMonth.toString())
                low == "hh" || low == "h" -> {
                    val hour = if (ampm) (dt.hour % 12).let { if (it == 0) 12 else it } else dt.hour
                    out.append(if (low == "hh") String.format(Locale.ROOT, "%02d", hour) else hour.toString())
                }
                low == "ss" || low == "s" -> out.append(if (low == "ss") String.format(Locale.ROOT, "%02d", dt.second) else dt.second.toString())
                low == "am/pm" -> out.append(if (dt.hour >= 12) "PM" else "AM")
                low == "a/p" -> out.append(if (dt.hour >= 12) "P" else "A")
                low.startsWith(".") -> {
                    val digits = low.length - 1
                    val frac = dt.nano / 1e9
                    out.append(".").append(String.format(Locale.ROOT, "%.${digits}f", frac).substringAfter('.'))
                }
            }
            if (low.isNotEmpty() && low[0] in "ymdhs") prevKind = low[0]
            else if (low.startsWith("[") && low.length > 1 && low[1] in "hms") prevKind = low[1]
            last = m.range.last + 1
        }
        out.append(code.substring(last).replace("\\", ""))
        return out.toString()
    }

    private fun formatNumberCode(code0: String, n0: Double): String {
        var n = n0
        var prefix = ""
        var suffix = ""
        // Currency like [$R$-416] or [$€-2]: keep the symbol.
        var code = Regex("""\[\$([^\]-]*)(-[^\]]*)?]""").replace(code0) { "\"" + it.groupValues[1] + "\"" }
        code = Regex("""\[[^\]]*]""").replace(code, "")
        code = Regex("""_.|\*.""").replace(code, "")
        val parts = Regex(""""[^"]*"|\\.""").split(code).let { plain ->
            // Re-interleave the literals the split removed.
            val lits = Regex(""""[^"]*"|\\.""").findAll(code).map { it.value }.toList()
            val list = ArrayList<String>()
            plain.forEachIndexed { i, p -> list += p; if (i < lits.size) list += lits[i] }
            list
        }
        var numberPart = ""
        var seenDigit = false
        for (part in parts) {
            if (part.startsWith("\"") || part.startsWith("\\")) {
                val text = if (part.startsWith("\"")) part.substring(1, part.length - 1) else part.substring(1)
                if (seenDigit) suffix += text else prefix += text
                continue
            }
            val m = Regex("""[0#?.,%Ee+-]+[0#?.,%]*""").find(part)
            if (m != null && Regex("[0#?]").containsMatchIn(part)) {
                if (!seenDigit) prefix += part.substring(0, m.range.first)
                numberPart += m.value
                suffix += part.substring(m.range.last + 1)
                seenDigit = true
            } else if (seenDigit) suffix += part else prefix += part
        }
        if (numberPart.isEmpty()) return if (prefix.isNotEmpty() || suffix.isNotEmpty()) prefix + suffix else general(n)
        val percent = '%' in numberPart || '%' in suffix
        if (percent) { n *= 100; numberPart = numberPart.replace("%", "") }
        val text: String
        if (Regex("E[+-]", RegexOption.IGNORE_CASE).containsMatchIn(numberPart)) {
            val mant = numberPart.split('E', 'e')[0]
            val decimals = if ('.' in mant) mant.substringAfter('.').length else 0
            val t = String.format(Locale.ROOT, "%.${decimals}E", n)
            val mantissa = t.substringBefore('E')
            val exp = t.substringAfter('E').toInt()
            text = mantissa + "E" + (if (exp >= 0) "+" else "-") + String.format(Locale.ROOT, "%02d", abs(exp))
        } else {
            val intPart = numberPart.substringBefore('.')
            val decPart = if ('.' in numberPart) numberPart.substringAfter('.') else ""
            val decimals = decPart.count { it == '0' || it == '#' || it == '?' }
            val thousands = ',' in intPart.trimEnd(',')
            val scaleCommas = intPart.length - intPart.trimEnd(',').length
            repeat(scaleCommas) { n /= 1000 }
            val minInt = intPart.count { it == '0' }
            val abs = abs(n)
            var t = if (thousands) String.format(Locale.US, "%,.${decimals}f", abs) else String.format(Locale.ROOT, "%.${decimals}f", abs)
            var whole = t.substringBefore('.')
            var frac = if ('.' in t) t.substringAfter('.') else ""
            if (decPart.isNotEmpty() && decPart.all { it == '#' } && frac.isNotEmpty()) frac = frac.trimEnd('0')
            val digits = whole.replace(",", "")
            if (digits == "0" && minInt == 0) whole = ""
            else if (digits.length < minInt) {
                val padded = digits.padStart(minInt, '0')
                whole = padded
            }
            t = whole + (if (frac.isNotEmpty()) ".$frac" else "")
            text = if (n < 0 && t.any { it in '1'..'9' }) "-$t" else t
        }
        if (percent && '%' !in suffix) suffix = "%$suffix"
        return prefix + text + suffix
    }

    fun format(value: Any?, code: String, date1904: Boolean = false): String {
        if (value == null) return ""
        if (value is Boolean) return if (value) "TRUE" else "FALSE"
        if (value is String) {
            val sections = splitSections(code)
            if (sections.size >= 4 && '@' in sections[3]) return sections[3].replace("\"", "").replace("@", value)
            return value
        }
        val v = (value as? Number)?.toDouble() ?: return value.toString()
        if (v.isNaN() || v.isInfinite()) return v.toString()
        if (code.isEmpty() || code.equals("general", true) || code == "@") return general(v)
        val sections = splitSections(code)
        var section = sections[0]
        var n = v
        if (sections.size >= 2 && v < 0) { section = sections[1]; n = -v }
        else if (sections.size >= 3 && v == 0.0) section = sections[2]
        if (section.equals("general", true)) return general(n)
        return try {
            if (isDate(section)) {
                if (n < 0 || n > 2958465) general(v) else formatDate(section, serialToDateTime(n, date1904), n)
            } else formatNumberCode(section, n)
        } catch (_: Exception) {
            general(v)
        }
    }
}
