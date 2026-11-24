package io.github.guilhermefeitosa66.opendocumentsviewer.formats

/** A CSV/TSV file split into rows and cells. */
class DelimitedTable(
    val rows: List<List<String>>,
    val delimiter: Char,
    /** Width of each column in chars (capped), from the rows looked at. */
    val columnChars: IntArray,
) {
    val columnCount: Int get() = columnChars.size
}

object Delimited {
    private const val MAX_COLUMN_CHARS = 40
    private const val MIN_COLUMN_CHARS = 3

    /** Picks the delimiter that splits the first lines most consistently. */
    fun detectDelimiter(text: String, fallback: Char = ','): Char {
        val sample = text.lineSequence().take(20).filter { it.isNotBlank() }.toList()
        if (sample.isEmpty()) return fallback
        var best = fallback
        var bestScore = 0
        for (d in charArrayOf(',', ';', '\t', '|')) {
            val counts = sample.map { line -> line.count { it == d } }
            val first = counts[0]
            if (first == 0) continue
            val consistent = counts.count { it == first }
            val score = consistent * 100 + first
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    /** RFC 4180 style: quoted cells may hold delimiters, doubled quotes and line breaks. */
    fun parse(text: String, delimiter: Char): DelimitedTable {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(c)
            } else when (c) {
                '"' -> if (cell.isEmpty()) inQuotes = true else cell.append(c)
                delimiter -> { row += cell.toString(); cell.setLength(0) }
                '\r' -> Unit
                '\n' -> {
                    row += cell.toString(); cell.setLength(0)
                    rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        val columns = rows.maxOfOrNull { it.size } ?: 0
        val widths = IntArray(columns) { MIN_COLUMN_CHARS }
        for (r in rows.asSequence().take(500)) {
            r.forEachIndexed { k, v ->
                val len = v.lineSequence().maxOf { it.length }
                if (len > widths[k]) widths[k] = minOf(len, MAX_COLUMN_CHARS)
            }
        }
        return DelimitedTable(rows, delimiter, widths)
    }
}

/** Sorting table rows for display only: numbers by value (1.234,56 or 1,234.56), text naturally, blanks last. */
object CellSort {
    fun comparator(column: Int, ascending: Boolean): Comparator<List<String>> {
        val base = Comparator<List<String>> { a, b -> compareCells(a.getOrNull(column).orEmpty(), b.getOrNull(column).orEmpty()) }
        return if (ascending) base else Comparator { a, b ->
            val x = a.getOrNull(column).orEmpty().isBlank()
            val y = b.getOrNull(column).orEmpty().isBlank()
            // Blanks stay last in both directions.
            if (x != y) (if (x) 1 else -1) else base.compare(b, a)
        }
    }

    fun compareCells(a: String, b: String): Int {
        val ta = a.trim()
        val tb = b.trim()
        if (ta.isEmpty() != tb.isEmpty()) return if (ta.isEmpty()) 1 else -1
        val na = number(ta)
        val nb = number(tb)
        if (na != null && nb != null) return na.compareTo(nb)
        if (na != null) return -1
        if (nb != null) return 1
        return natural(ta, tb)
    }

    /** "R$ 1.234,56", "1,234.56", "12%", "-3e2" → a number; null for text. */
    fun number(s: String): Double? {
        var t = s.replace(Regex("[\\s\\u00A0%$€£¥]|R\\$"), "")
        if (t.isEmpty() || t.count { it.isDigit() } == 0) return null
        if (!t.all { it.isDigit() || it in ".,-+eE" }) return null
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        t = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastComma > lastDot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lastComma >= 0 -> if (t.count { it == ',' } == 1 && t.length - lastComma - 1 != 3) t.replace(',', '.') else t.replace(",", "")
            else -> t
        }
        return t.toDoubleOrNull()
    }

    /** Text with digit runs compared by value ("item 2" before "item 10"), ignoring case. */
    fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val da = a.substring(i, ei).trimStart('0')
                val db = b.substring(j, ej).trimStart('0')
                if (da.length != db.length) return da.length - db.length
                val c = da.compareTo(db)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
