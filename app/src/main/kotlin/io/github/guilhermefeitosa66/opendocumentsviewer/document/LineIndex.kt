package io.github.guilhermefeitosa66.opendocumentsviewer.document

/**
 * The display rows of a text: one per line, except that a line longer than [maxRowLength] is
 * split into several rows, so a minified file never becomes one gigantic list item.
 *
 * Rows are kept as parallel int arrays (start, end, 1-based line number) to stay small for
 * files with hundreds of thousands of lines.
 */
class LineIndex private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val lines: IntArray,
    val rowCount: Int,
    val lineCount: Int,
    /** Length of the longest row, in chars: sizes the horizontal scroll when wrapping is off. */
    val longestRow: Int,
) {
    fun start(row: Int) = starts[row]
    fun end(row: Int) = ends[row]
    fun line(row: Int) = lines[row]

    /** True when the row continues the line of the previous row. */
    fun isContinuation(row: Int) = row > 0 && lines[row] == lines[row - 1]

    fun text(source: CharSequence, row: Int): String = source.subSequence(starts[row], ends[row]).toString()

    /** The row that holds [offset] (offsets on a line break belong to the row before it). */
    fun rowOf(offset: Int): Int {
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val DEFAULT_MAX_ROW = 2000

        fun build(text: CharSequence, maxRowLength: Int = DEFAULT_MAX_ROW): LineIndex {
            var capacity = 1024
            var starts = IntArray(capacity)
            var ends = IntArray(capacity)
            var lines = IntArray(capacity)
            var count = 0
            var line = 1
            var longest = 0

            fun add(s: Int, e: Int) {
                if (count == capacity) {
                    capacity *= 2
                    starts = starts.copyOf(capacity)
                    ends = ends.copyOf(capacity)
                    lines = lines.copyOf(capacity)
                }
                starts[count] = s; ends[count] = e; lines[count] = line
                if (e - s > longest) longest = e - s
                count++
            }

            fun addLine(s: Int, e: Int) {
                if (e - s <= maxRowLength) {
                    add(s, e)
                } else {
                    var p = s
                    while (p < e) {
                        var q = minOf(p + maxRowLength, e)
                        // Never split a surrogate pair.
                        if (q < e && Character.isHighSurrogate(text[q - 1])) q--
                        add(p, q)
                        p = q
                    }
                }
            }

            val n = text.length
            var lineStart = 0
            var i = 0
            while (i < n) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    addLine(lineStart, i)
                    line++
                    if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    lineStart = i + 1
                }
                i++
            }
            // The last line (empty when the text ends with a line break: show it like editors do,
            // except for an empty file, which has exactly one empty row).
            addLine(lineStart, n)
            return LineIndex(starts, ends, lines, count, line, longest)
        }
    }
}
