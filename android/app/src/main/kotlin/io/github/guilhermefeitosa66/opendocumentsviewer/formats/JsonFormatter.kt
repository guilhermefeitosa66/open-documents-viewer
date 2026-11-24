package io.github.guilhermefeitosa66.opendocumentsviewer.formats

/**
 * Re-indents JSON without building a tree: one pass over the chars, so a 10 MB file costs one
 * copy. Comments (JSONC) are kept on their own line. If the brackets do not balance the input
 * is not JSON we understand, and null is returned so the caller shows the original.
 */
object JsonFormatter {
    fun format(input: String, indent: String = "  "): String? {
        val out = StringBuilder(input.length + input.length / 4)
        var depth = 0
        var i = 0
        val n = input.length
        var lineHasContent = false

        fun newline() {
            out.append('\n')
            repeat(depth) { out.append(indent) }
            lineHasContent = false
        }

        while (i < n) {
            val c = input[i]
            when {
                c == '"' || c == '\'' -> {
                    val start = i
                    i++
                    while (i < n && input[i] != c) {
                        if (input[i] == '\\') i++
                        i++
                    }
                    out.append(input, start, minOf(i + 1, n))
                    lineHasContent = true
                }
                c == '{' || c == '[' -> {
                    // Keep empty containers on one line.
                    var j = i + 1
                    while (j < n && input[j].isWhitespace()) j++
                    val close = if (c == '{') '}' else ']'
                    if (j < n && input[j] == close) {
                        out.append(c).append(close)
                        i = j
                    } else {
                        out.append(c)
                        depth++
                        newline()
                    }
                    lineHasContent = true
                }
                c == '}' || c == ']' -> {
                    depth--
                    if (depth < 0) return null
                    newline()
                    out.append(c)
                    lineHasContent = true
                }
                c == ',' -> {
                    out.append(',')
                    newline()
                }
                c == ':' -> out.append(": ")
                c == '/' && i + 1 < n && input[i + 1] == '/' -> {
                    var j = input.indexOf('\n', i)
                    if (j < 0) j = n
                    if (lineHasContent) out.append(' ')
                    out.append(input, i, j)
                    i = j - 1
                    newline()
                }
                c == '/' && i + 1 < n && input[i + 1] == '*' -> {
                    var j = input.indexOf("*/", i + 2)
                    j = if (j < 0) n else j + 2
                    out.append(input, i, j)
                    i = j - 1
                }
                c.isWhitespace() -> Unit
                else -> {
                    out.append(c)
                    lineHasContent = true
                }
            }
            i++
        }
        if (depth != 0) return null
        // Trim indentation left behind by a trailing comment.
        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\n')) out.setLength(out.length - 1)
        return out.toString()
    }

    /** JSON Lines: one document per line, each formatted and separated by a blank line. */
    fun formatLines(input: String): String? {
        val parts = ArrayList<String>()
        for (line in input.lineSequence()) {
            if (line.isBlank()) continue
            parts += format(line) ?: return null
        }
        return parts.joinToString("\n\n")
    }
}
