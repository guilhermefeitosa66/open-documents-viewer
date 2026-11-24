package io.github.guilhermefeitosa66.opendocumentsviewer.syntax

private fun skipSpaces(line: String, from: Int): Int {
    var i = from
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
    return i
}

/** End (after the closing quote) of a quoted string starting at [start], or the line end. */
private fun quotedEnd(line: String, start: Int, escapes: Boolean = true): Int {
    val q = line[start]
    var j = start + 1
    while (j < line.length) {
        val c = line[j]
        if (escapes && c == '\\' && q != '\'') { j += 2; continue }
        if (c == q) return j + 1
        j++
    }
    return line.length
}

private val commonLiterals = setOf("true", "false", "null", "True", "False", "Null", "NULL", "TRUE", "FALSE", "~", "yes", "no", "on", "off")

private fun isNumberLike(s: String): Boolean {
    if (s.isEmpty()) return false
    val t = s.removePrefix("-").removePrefix("+")
    return t.isNotEmpty() && (t[0].isDigit() || (t[0] == '.' && t.length > 1 && t[1].isDigit())) &&
        t.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' || it == '-' || it == '+' }
}

/** JSON, JSONC and JSON5-ish: keys, strings, numbers, literals and comments. */
object JsonLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        if (state == 1) {
            val at = line.indexOf("*/")
            if (at < 0) { out.add(0, n, TokenType.COMMENT); return 1 }
            out.add(0, at + 2, TokenType.COMMENT)
            i = at + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                c == '"' || c == '\'' -> {
                    val end = quotedEnd(line, i)
                    val next = skipSpaces(line, end)
                    out.add(i, end, if (next < n && line[next] == ':') TokenType.KEY else TokenType.STRING)
                    i = end
                }
                c == '/' && i + 1 < n && line[i + 1] == '/' -> { out.add(i, n, TokenType.COMMENT); return 0 }
                c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    val at = line.indexOf("*/", i + 2)
                    if (at < 0) { out.add(i, n, TokenType.COMMENT); return 1 }
                    out.add(i, at + 2, TokenType.COMMENT)
                    i = at + 2
                }
                c == '-' || c.isDigit() -> {
                    val start = i
                    if (c == '-') i++
                    if (i < n && line[i].isDigit()) {
                        val end = scanNumber(line, i)
                        out.add(start, end, TokenType.NUMBER)
                        i = end
                    }
                }
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentPart(line[j])) j++
                    val word = line.substring(i, j)
                    val next = skipSpaces(line, j)
                    when {
                        next < n && line[next] == ':' -> out.add(i, j, TokenType.KEY)
                        word == "true" || word == "false" || word == "null" -> out.add(i, j, TokenType.LITERAL)
                    }
                    i = j
                }
                c == '{' || c == '}' || c == '[' || c == ']' -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++ }
                else -> i++
            }
        }
        return 0
    }
}

/** YAML, line by line: keys, list markers, comments, anchors, tags and scalars. */
object YamlLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = skipSpaces(line, 0)
        if (i >= n) return 0
        if (line.startsWith("---") || line.startsWith("...")) {
            out.add(0, 3, TokenType.MARKUP)
            i = 3
        }
        if (i < n && line[i] == '#') { out.add(i, n, TokenType.COMMENT); return 0 }
        while (i + 1 < n && line[i] == '-' && line[i + 1] == ' ') {
            out.add(i, i + 1, TokenType.PUNCTUATION)
            i = skipSpaces(line, i + 1)
        }
        // A key: up to ": " or a final ":", outside quotes.
        if (i < n && line[i] != '"' && line[i] != '\'' && line[i] != '{' && line[i] != '[') {
            var j = i
            var keyEnd = -1
            while (j < n) {
                if (line[j] == ':' && (j + 1 == n || line[j + 1] == ' ' || line[j + 1] == '\t')) { keyEnd = j; break }
                if (line[j] == ' ' && j + 1 < n && line[j + 1] == '#') break
                j++
            }
            if (keyEnd > i) {
                out.add(i, keyEnd, TokenType.KEY)
                i = keyEnd + 1
            }
        } else if (i < n && (line[i] == '"' || line[i] == '\'')) {
            val end = quotedEnd(line, i)
            val next = skipSpaces(line, end)
            if (next < n && line[next] == ':') {
                out.add(i, end, TokenType.KEY)
                i = next + 1
            }
        }
        scalars(line, i, out)
        return 0
    }

    private fun scalars(line: String, from: Int, out: Tokens) {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> i++
                c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') -> { out.add(i, n, TokenType.COMMENT); return }
                c == '"' || c == '\'' -> { val e = quotedEnd(line, i); out.add(i, e, TokenType.STRING); i = e }
                c == '&' || c == '*' -> { var j = i + 1; while (j < n && !line[j].isWhitespace() && line[j] != ',') j++; out.add(i, j, TokenType.VARIABLE); i = j }
                c == '!' -> { var j = i + 1; while (j < n && !line[j].isWhitespace()) j++; out.add(i, j, TokenType.ANNOTATION); i = j }
                c == '|' || c == '>' -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++ }
                c in "{}[],:" -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++ }
                else -> {
                    var j = i
                    while (j < n && !(line[j] == ' ' && j + 1 < n && line[j + 1] == '#') && line[j] !in ",]}") j++
                    val word = line.substring(i, j).trimEnd()
                    when {
                        word in commonLiterals -> out.add(i, i + word.length, TokenType.LITERAL)
                        isNumberLike(word) -> out.add(i, i + word.length, TokenType.NUMBER)
                    }
                    i = maxOf(j, i + 1)
                }
            }
        }
    }
}

/** INI, TOML, .properties, .conf, .editorconfig: sections, keys, values, comments. */
object IniLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        if (state != 0) {
            val close = if (state == 1) "\"\"\"" else "'''"
            val at = line.indexOf(close)
            if (at < 0) { out.add(0, n, TokenType.STRING); return state }
            out.add(0, at + 3, TokenType.STRING)
            i = at + 3
            return value(line, i, out)
        }
        i = skipSpaces(line, 0)
        if (i >= n) return 0
        val c = line[i]
        if (c == '#' || c == ';' || c == '!') { out.add(i, n, TokenType.COMMENT); return 0 }
        if (c == '[') {
            val close = line.lastIndexOf(']')
            if (close > i) {
                out.add(i, close + 1, TokenType.SECTION)
                return value(line, close + 1, out)
            }
        }
        var j = i
        while (j < n && line[j] != '=' && line[j] != ':') j++
        if (j < n) {
            var keyEnd = j
            while (keyEnd > i && (line[keyEnd - 1] == ' ' || line[keyEnd - 1] == '\t')) keyEnd--
            out.add(i, keyEnd, TokenType.KEY)
            out.add(j, j + 1, TokenType.PUNCTUATION)
            return value(line, j + 1, out)
        }
        return 0
    }

    private fun value(line: String, from: Int, out: Tokens): Int {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> i++
                (c == '#' || c == ';') && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') -> { out.add(i, n, TokenType.COMMENT); return 0 }
                line.startsWith("\"\"\"", i) || line.startsWith("'''", i) -> {
                    val close = line.substring(i, i + 3)
                    val at = line.indexOf(close, i + 3)
                    if (at < 0) { out.add(i, n, TokenType.STRING); return if (close == "\"\"\"") 1 else 2 }
                    out.add(i, at + 3, TokenType.STRING)
                    i = at + 3
                }
                c == '"' || c == '\'' -> { val e = quotedEnd(line, i); out.add(i, e, TokenType.STRING); i = e }
                c in "[]{},=" -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++ }
                else -> {
                    var j = i
                    while (j < n && !line[j].isWhitespace() && line[j] !in ",]}") j++
                    val word = line.substring(i, j)
                    when {
                        word in commonLiterals -> out.add(i, j, TokenType.LITERAL)
                        isNumberLike(word) -> out.add(i, j, TokenType.NUMBER)
                    }
                    i = maxOf(j, i + 1)
                }
            }
        }
        return 0
    }
}

/** .env files: `export`, keys, values (with `${VAR}` interpolation) and comments. */
object DotenvLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = skipSpaces(line, 0)
        if (i >= n) return 0
        if (line[i] == '#') { out.add(i, n, TokenType.COMMENT); return 0 }
        if (line.startsWith("export ", i)) {
            out.add(i, i + 6, TokenType.KEYWORD)
            i = skipSpaces(line, i + 6)
        }
        val eq = line.indexOf('=', i)
        if (eq < 0) return 0
        out.add(i, eq, TokenType.KEY)
        out.add(eq, eq + 1, TokenType.PUNCTUATION)
        var v = eq + 1
        if (v >= n) return 0
        if (line[v] == '"' || line[v] == '\'') {
            val end = quotedEnd(line, v)
            stringWithVars(line, v, end, out, line[v] == '"')
            v = skipSpaces(line, end)
            if (v < n && line[v] == '#') out.add(v, n, TokenType.COMMENT)
        } else {
            var end = n
            val hash = line.indexOf(" #", v)
            if (hash >= 0) end = hash
            stringWithVars(line, v, end, out, true)
            if (hash >= 0) out.add(hash + 1, n, TokenType.COMMENT)
        }
        return 0
    }

    private fun stringWithVars(line: String, start: Int, end: Int, out: Tokens, interpolate: Boolean) {
        if (!interpolate) { out.add(start, end, TokenType.STRING); return }
        var seg = start
        var j = start
        while (j < end) {
            if (line[j] == '$' && j + 1 < end && (line[j + 1] == '{' || isIdentStart(line[j + 1]))) {
                var k = j + 1
                if (line[k] == '{') {
                    val close = line.indexOf('}', k)
                    k = if (close < 0 || close >= end) end else close + 1
                } else {
                    while (k < end && isIdentPart(line[k])) k++
                }
                out.add(seg, j, TokenType.STRING)
                out.add(j, k, TokenType.VARIABLE)
                seg = k
                j = k
            } else j++
        }
        out.add(seg, end, TokenType.STRING)
    }
}

/** XML, HTML, SVG: tags, attributes, strings, comments and entities. */
object MarkupLexer : Lexer {
    private const val COMMENT = 1
    private const val IN_TAG = 2
    private const val CDATA = 3

    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        var st = state
        while (i < n) {
            when (st) {
                COMMENT -> {
                    val at = line.indexOf("-->", i)
                    if (at < 0) { out.add(i, n, TokenType.COMMENT); return COMMENT }
                    out.add(i, at + 3, TokenType.COMMENT)
                    i = at + 3
                    st = 0
                }
                CDATA -> {
                    val at = line.indexOf("]]>", i)
                    if (at < 0) { out.add(i, n, TokenType.STRING); return CDATA }
                    out.add(i, at + 3, TokenType.STRING)
                    i = at + 3
                    st = 0
                }
                IN_TAG -> {
                    val c = line[i]
                    when {
                        c == ' ' || c == '\t' -> i++
                        c == '>' -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++; st = 0 }
                        (c == '/' || c == '?') && i + 1 < n && line[i + 1] == '>' -> { out.add(i, i + 2, TokenType.PUNCTUATION); i += 2; st = 0 }
                        c == '"' || c == '\'' -> { val e = quotedEnd(line, i, escapes = false); out.add(i, e, TokenType.STRING); i = e }
                        c == '=' -> i++
                        else -> {
                            var j = i
                            while (j < n && !line[j].isWhitespace() && line[j] !in "=>/\"'") j++
                            if (j == i) j++ else out.add(i, j, TokenType.ATTRIBUTE)
                            i = j
                        }
                    }
                }
                else -> {
                    val c = line[i]
                    when {
                        line.startsWith("<!--", i) -> { out.add(i, i + 4, TokenType.COMMENT); i += 4; st = COMMENT }
                        line.startsWith("<![CDATA[", i) -> { out.add(i, i + 9, TokenType.PUNCTUATION); i += 9; st = CDATA }
                        c == '<' && i + 1 < n && (line[i + 1] == '/' || line[i + 1] == '!' || line[i + 1] == '?' || line[i + 1].isLetter()) -> {
                            var j = i + 1
                            if (line[j] == '/' || line[j] == '!' || line[j] == '?') j++
                            out.add(i, j, TokenType.PUNCTUATION)
                            val nameStart = j
                            while (j < n && !line[j].isWhitespace() && line[j] != '>' && line[j] != '/') j++
                            out.add(nameStart, j, TokenType.TAG)
                            i = j
                            st = IN_TAG
                        }
                        c == '&' -> {
                            val semi = line.indexOf(';', i)
                            if (semi in (i + 2)..(i + 10)) { out.add(i, semi + 1, TokenType.LITERAL); i = semi + 1 } else i++
                        }
                        else -> i++
                    }
                }
            }
        }
        return st
    }
}

/** Markdown source: headings, emphasis markers, code, links, lists, quotes and fences. */
object MarkdownLexer : Lexer {
    private const val FENCE_BACKTICK = 1
    private const val FENCE_TILDE = 2

    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        val lead = skipSpaces(line, 0)
        val trimmedStart = if (lead <= 3) lead else -1
        if (state != 0) {
            val marker = if (state == FENCE_BACKTICK) "```" else "~~~"
            if (trimmedStart >= 0 && line.startsWith(marker, trimmedStart)) {
                out.add(0, n, TokenType.COMMENT)
                return 0
            }
            out.add(0, n, TokenType.CODE)
            return state
        }
        if (trimmedStart >= 0) {
            if (line.startsWith("```", trimmedStart)) { out.add(0, n, TokenType.COMMENT); return FENCE_BACKTICK }
            if (line.startsWith("~~~", trimmedStart)) { out.add(0, n, TokenType.COMMENT); return FENCE_TILDE }
            if (trimmedStart < n && line[trimmedStart] == '#') {
                var j = trimmedStart
                while (j < n && line[j] == '#') j++
                if (j - trimmedStart <= 6 && (j == n || line[j] == ' ')) {
                    out.add(trimmedStart, j, TokenType.MARKUP)
                    out.add(j, n, TokenType.HEADING)
                    return 0
                }
            }
        }
        if (lead >= 4) { out.add(lead, n, TokenType.CODE); return 0 }
        val t = line.trim()
        if (t.length >= 3 && (t.all { it == '-' || it == ' ' } || t.all { it == '*' || it == ' ' } || t.all { it == '_' || it == ' ' })) {
            out.add(0, n, TokenType.MARKUP)
            return 0
        }
        if (t.startsWith("|") && t.all { it in "|-: " }) { out.add(0, n, TokenType.COMMENT); return 0 }

        var i = lead
        while (i < n && line[i] == '>') {
            out.add(i, i + 1, TokenType.MARKUP)
            i = skipSpaces(line, i + 1)
        }
        // List markers and task boxes.
        if (i + 1 < n && line[i] in "-*+" && line[i + 1] == ' ') {
            out.add(i, i + 1, TokenType.MARKUP)
            i += 2
        } else {
            var j = i
            while (j < n && line[j].isDigit()) j++
            if (j > i && j + 1 < n && (line[j] == '.' || line[j] == ')') && line[j + 1] == ' ') {
                out.add(i, j + 1, TokenType.MARKUP)
                i = j + 2
            }
        }
        if (i + 3 < n && line[i] == '[' && line[i + 2] == ']' && line[i + 1] in " xX" && line[i + 3] == ' ') {
            out.add(i, i + 3, TokenType.MARKUP)
            i += 4
        }
        inline(line, i, out)
        return 0
    }

    private fun inline(line: String, from: Int, out: Tokens) {
        val n = line.length
        var i = from
        while (i < n) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < n -> i += 2
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < n && line[i + ticks] == '`') ticks++
                    val close = line.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) { i += ticks } else { out.add(i, close + ticks, TokenType.CODE); i = close + ticks }
                }
                c == '*' || c == '_' || c == '~' -> {
                    var j = i
                    while (j < n && line[j] == c) j++
                    // Only runs that touch a word are emphasis markers.
                    val touches = (j < n && !line[j].isWhitespace()) || (i > 0 && !line[i - 1].isWhitespace())
                    if (touches && (c != '~' || j - i >= 2)) out.add(i, j, TokenType.MARKUP)
                    i = j
                }
                c == '!' && i + 1 < n && line[i + 1] == '[' -> { out.add(i, i + 1, TokenType.MARKUP); i++ }
                c == '[' -> {
                    val close = line.indexOf(']', i + 1)
                    if (close < 0) { i++; continue }
                    out.add(i, close + 1, TokenType.LINK)
                    i = close + 1
                    if (i < n && line[i] == '(') {
                        val paren = line.indexOf(')', i)
                        val end = if (paren < 0) n else paren + 1
                        out.add(i, end, TokenType.STRING)
                        i = end
                    }
                }
                c == '<' && (line.startsWith("<http", i) || line.startsWith("<mailto:", i)) -> {
                    val close = line.indexOf('>', i)
                    val end = if (close < 0) n else close + 1
                    out.add(i, end, TokenType.LINK)
                    i = end
                }
                else -> i++
            }
        }
    }
}

/** CSV/TSV source: every other column tinted, so columns can be followed by eye. */
class DelimitedLexer(private val delimiter: Char) : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        var column = 0
        var inQuotes = state == 1
        var cellStart = 0
        while (i < n) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && line[i + 1] == '"') i++ else inQuotes = false
                }
            } else if (c == '"') {
                inQuotes = true
            } else if (c == delimiter) {
                if (column % 2 == 1) out.add(cellStart, i, TokenType.KEY)
                out.add(i, i + 1, TokenType.PUNCTUATION)
                column++
                cellStart = i + 1
            }
            i++
        }
        if (column % 2 == 1) out.add(cellStart, n, TokenType.KEY)
        return if (inQuotes) 1 else 0
    }
}

/** RTF source: control words, braces and escapes stand out from the text. */
object RtfLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        while (i < n) {
            val c = line[i]
            when {
                c == '{' || c == '}' -> { out.add(i, i + 1, TokenType.PUNCTUATION); i++ }
                c == '\\' && i + 1 < n && line[i + 1].isLetter() -> {
                    var j = i + 1
                    while (j < n && line[j].isLetter()) j++
                    if (j < n && (line[j] == '-' || line[j].isDigit())) { j++; while (j < n && line[j].isDigit()) j++ }
                    out.add(i, j, TokenType.KEYWORD)
                    i = j
                }
                c == '\\' && i + 1 < n -> {
                    val end = if (line[i + 1] == '\'' ) minOf(i + 4, n) else i + 2
                    out.add(i, end, TokenType.LITERAL)
                    i = end
                }
                else -> i++
            }
        }
        return 0
    }
}

/** Unified diffs and patches: added and removed lines, hunks, file headers. */
object DiffLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        when {
            line.startsWith("+++") || line.startsWith("---") -> out.add(0, n, TokenType.HEADING)
            line.startsWith("@@") -> out.add(0, n, TokenType.SECTION)
            line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("new file") ||
                line.startsWith("deleted file") || line.startsWith("similarity") || line.startsWith("rename ") -> out.add(0, n, TokenType.COMMENT)
            line.startsWith("+") -> out.add(0, n, TokenType.STRING)
            line.startsWith("-") -> out.add(0, n, TokenType.TAG)
            line.startsWith("\\") -> out.add(0, n, TokenType.COMMENT)
        }
        return 0
    }
}

/** Logs: severity words colored, timestamps dimmed. */
object LogLexer : Lexer {
    private val time = Regex("^\\[?\\d{2,4}[-/.]\\d{1,2}[-/.]\\d{1,4}[T ]?[\\d:.,]*(Z|[+-]\\d{2}:?\\d{2})?\\]?|^\\[?\\d{1,2}:\\d{2}:\\d{2}[.,]?\\d*\\]?")
    private val level = Regex("\\b(FATAL|CRITICAL|SEVERE|ERROR|ERR|E|WARNING|WARN|W|INFO|I|NOTICE|DEBUG|D|TRACE|VERBOSE|V)\\b")

    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        time.find(line)?.let { out.add(it.range.first, it.range.last + 1, TokenType.COMMENT) }
        // Only the first level word counts, and single letters only as Android logcat tags ("E/Tag", " E ").
        val m = level.find(line) ?: return 0
        val w = m.value
        if (w.length == 1) {
            val next = line.getOrNull(m.range.last + 1)
            val prev = line.getOrNull(m.range.first - 1)
            val logcat = next == '/' || (prev == ' ' && next == ' ')
            if (!logcat) return 0
        }
        val type = when (w) {
            "FATAL", "CRITICAL", "SEVERE", "ERROR", "ERR", "E" -> TokenType.TAG
            "WARNING", "WARN", "W" -> TokenType.NUMBER
            "INFO", "I", "NOTICE" -> TokenType.TYPE
            else -> TokenType.COMMENT
        }
        out.add(m.range.first, m.range.last + 1, type)
        if (type == TokenType.TAG) out.add(m.range.last + 1, line.length, TokenType.TAG)
        return 0
    }
}

/** gettext .po/.pot catalogs. */
object PoLexer : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val t = line.trimStart()
        val lead = line.length - t.length
        when {
            t.startsWith("#") -> out.add(lead, line.length, TokenType.COMMENT)
            t.startsWith("msg") -> {
                val sp = t.indexOfFirst { it == ' ' || it == '\t' }.let { if (it < 0) t.length else it }
                out.add(lead, lead + sp, TokenType.KEYWORD)
                val q = line.indexOf('"', lead + sp)
                if (q >= 0) out.add(q, line.length, TokenType.STRING)
            }
            t.startsWith("\"") -> out.add(lead, line.length, TokenType.STRING)
        }
        return 0
    }
}

/** SubRip and WebVTT subtitles: cue numbers and timings stand out from the spoken text. */
object SubtitleLexer : Lexer {
    private val timing = Regex("^\\s*[\\d:.,]+\\s*-->\\s*[\\d:.,]+.*$")
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        when {
            timing.matches(line) -> out.add(0, line.length, TokenType.NUMBER)
            line.isNotBlank() && line.all { it.isDigit() || it.isWhitespace() } -> out.add(0, line.length, TokenType.COMMENT)
            line.startsWith("WEBVTT") || line.startsWith("NOTE") -> out.add(0, line.length, TokenType.HEADING)
            else -> {
                var i = line.indexOf('<')
                while (i >= 0) {
                    val e = line.indexOf('>', i)
                    if (e < 0) break
                    out.add(i, e + 1, TokenType.TAG)
                    i = line.indexOf('<', e)
                }
            }
        }
        return 0
    }
}

/**
 * Light markup (reStructuredText, AsciiDoc, Org): headings by their line markers, comments,
 * directives and inline literals.
 */
class LightMarkupLexer(
    private val headingChars: String,
    private val commentPrefixes: List<String>,
    private val directivePrefixes: List<String>,
) : Lexer {
    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        if (n == 0) return 0
        for (c in commentPrefixes) if (line.startsWith(c)) { out.add(0, n, TokenType.COMMENT); return 0 }
        for (d in directivePrefixes) if (line.startsWith(d)) { out.add(0, n, TokenType.ANNOTATION); return 0 }
        val first = line[0]
        if (first in headingChars) {
            var j = 0
            while (j < n && line[j] == first) j++
            if (j == n && n >= 3) { out.add(0, n, TokenType.MARKUP); return 0 } // reST underline
            if (j < n && line[j] == ' ') { out.add(0, j, TokenType.MARKUP); out.add(j, n, TokenType.HEADING); return 0 }
        }
        var i = 0
        while (i < n) {
            val c = line[i]
            if (c == '`') {
                val e = line.indexOf('`', i + 1)
                if (e > i) { out.add(i, e + 1, TokenType.CODE); i = e + 1; continue }
            }
            if ((c == '*' || c == '_' || c == '=' || c == '~' || c == '+' || c == '/') && i + 1 < n && line[i + 1] != ' ' && (i == 0 || line[i - 1] == ' ')) {
                val e = line.indexOf(c, i + 1)
                if (e > i + 1 && line[e - 1] != ' ') { out.add(i, i + 1, TokenType.MARKUP); out.add(e, e + 1, TokenType.MARKUP); i = e + 1; continue }
            }
            if (c == '[' ) {
                val e = line.indexOf(']', i + 1)
                if (e > i) { out.add(i, e + 1, TokenType.LINK); i = e + 1; continue }
            }
            i++
        }
        return 0
    }
}
