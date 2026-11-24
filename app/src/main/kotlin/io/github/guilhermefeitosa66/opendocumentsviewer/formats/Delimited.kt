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
