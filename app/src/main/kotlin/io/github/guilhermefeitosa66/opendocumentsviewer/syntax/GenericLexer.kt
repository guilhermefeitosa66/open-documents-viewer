package io.github.guilhermefeitosa66.opendocumentsviewer.syntax

data class StringRule(
    val open: String,
    val close: String = open,
    val multiline: Boolean = false,
    val escape: Char? = '\\',
    /** When set, the rule is a char literal: one char or escape, at most this long (so Rust lifetimes are not strings). */
    val maxLength: Int = 0,
    /** Highlight `${...}` / `$name` inside the string as variables (shell, Kotlin, Dart...). */
    val interpolation: Boolean = false,
)

/** What a C-like language looks like to the [GenericLexer]. */
data class LanguageSpec(
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val literals: Set<String> = emptySet(),
    val caseInsensitive: Boolean = false,
    val lineComments: List<String> = emptyList(),
    val blockComments: List<Pair<String, String>> = emptyList(),
    val strings: List<StringRule> = emptyList(),
    val annotationPrefix: Char? = null,
    val variablePrefix: Char? = null,
    /** Words starting with an uppercase letter are types (Kotlin, Java, Swift...). */
    val capitalizedTypes: Boolean = false,
    /** Extra chars allowed inside identifiers ('-' in CSS, '$' in JS, '?' and '!' in Ruby). */
    val identExtra: String = "",
    /** Line comments only count at the start of a word (shell `#` inside `a#b` is not a comment). */
    val commentNeedsBoundary: Boolean = false,
)

/**
 * A small state-machine lexer driven by a [LanguageSpec]. It is not a parser: it knows comments,
 * strings, numbers, keywords, types, annotations and calls, which is what reading code needs.
 *
 * States: 0 = normal, 1..99 = inside block comment (index + 1), 100.. = inside a multi-line
 * string (index + 100).
 */
class GenericLexer(private val spec: LanguageSpec) : Lexer {
    private val keywords = if (spec.caseInsensitive) spec.keywords.map { it.lowercase() }.toSet() else spec.keywords
    private val literals = if (spec.caseInsensitive) spec.literals.map { it.lowercase() }.toSet() else spec.literals
    private val types = if (spec.caseInsensitive) spec.types.map { it.lowercase() }.toSet() else spec.types
    // Longest openings first, so """ wins over ".
    private val strings = spec.strings.withIndex().sortedByDescending { it.value.open.length }

    override fun tokenize(line: String, state: Int, out: Tokens): Int {
        val n = line.length
        var i = 0
        when {
            state in 1..99 -> {
                val close = spec.blockComments[state - 1].second
                val at = line.indexOf(close)
                if (at < 0) {
                    out.add(0, n, TokenType.COMMENT)
                    return state
                }
                out.add(0, at + close.length, TokenType.COMMENT)
                i = at + close.length
            }
            state >= 100 -> {
                val rule = spec.strings[state - 100]
                val end = findStringEnd(line, 0, rule)
                if (end < 0) {
                    addString(line, 0, n, rule, out)
                    return state
                }
                addString(line, 0, end, rule, out)
                i = end
            }
        }

        outer@ while (i < n) {
            val c = line[i]
            if (c == ' ' || c == '\t') { i++; continue }

            for (lc in spec.lineComments) {
                if (line.startsWithAt(lc, i) && (!spec.commentNeedsBoundary || i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t' || line[i - 1] == ';')) {
                    out.add(i, n, TokenType.COMMENT)
                    return 0
                }
            }
            for ((k, bc) in spec.blockComments.withIndex()) {
                if (line.startsWithAt(bc.first, i)) {
                    val at = line.indexOf(bc.second, i + bc.first.length)
                    if (at < 0) {
                        out.add(i, n, TokenType.COMMENT)
                        return k + 1
                    }
                    out.add(i, at + bc.second.length, TokenType.COMMENT)
                    i = at + bc.second.length
                    continue@outer
                }
            }
            for ((k, rule) in strings) {
                if (line.startsWithAt(rule.open, i)) {
                    val end = findStringEnd(line, i + rule.open.length, rule)
                    if (end < 0) {
                        if (rule.maxLength > 0) break // Not a string after all (a Rust lifetime).
                        addString(line, i, n, rule, out)
                        return if (rule.multiline) k + 100 else 0
                    }
                    if (rule.maxLength > 0 && !isCharLiteral(line, i + rule.open.length, end - rule.close.length, rule.maxLength)) break
                    addString(line, i, end, rule, out)
                    i = end
                    continue@outer
                }
            }
            if (spec.annotationPrefix == c && i + 1 < n && isIdentStart(line[i + 1])) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] == '.')) j++
                out.add(i, j, TokenType.ANNOTATION)
                i = j
                continue
            }
            if (spec.variablePrefix == c && i + 1 < n) {
                val j = scanVariable(line, i)
                if (j > i + 1) {
                    out.add(i, j, TokenType.VARIABLE)
                    i = j
                    continue
                }
            }
            if (c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit() && (i == 0 || !isIdentPart(line[i - 1])))) {
                if (i > 0 && isIdentPart(line[i - 1])) { i++; continue }
                val j = scanNumber(line, i)
                out.add(i, j, TokenType.NUMBER)
                i = j
                continue
            }
            if (isIdentStart(c) || (c in spec.identExtra && c != '-')) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] in spec.identExtra)) j++
                val word = line.substring(i, j)
                val key = if (spec.caseInsensitive) word.lowercase() else word
                val type = when {
                    key in keywords -> TokenType.KEYWORD
                    key in literals -> TokenType.LITERAL
                    key in types -> TokenType.TYPE
                    spec.capitalizedTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } -> TokenType.TYPE
                    nextNonSpace(line, j) == '(' -> TokenType.FUNCTION
                    else -> null
                }
                if (type != null) out.add(i, j, type)
                i = j
                continue
            }
            i++
        }
        return 0
    }

    /** A char literal holds one character or one escape sequence ('a', '\n', '\u{1F600}'). */
    private fun isCharLiteral(line: String, from: Int, to: Int, max: Int): Boolean {
        val len = to - from
        if (len <= 0 || len > max) return false
        if (line[from] == '\\') return true
        return line.codePointCount(from, to) == 1
    }

    private fun nextNonSpace(line: String, from: Int): Char? {
        var j = from
        while (j < line.length && line[j] == ' ') j++
        return if (j < line.length) line[j] else null
    }

    /** End index (exclusive, after the close) of a string whose content starts at [from], or -1. */
    private fun findStringEnd(line: String, from: Int, rule: StringRule): Int {
        var j = from
        val n = line.length
        while (j < n) {
            val ch = line[j]
            if (rule.escape != null && ch == rule.escape && j + 1 < n) { j += 2; continue }
            if (line.startsWithAt(rule.close, j)) return j + rule.close.length
            j++
        }
        return -1
    }

    private fun addString(line: String, start: Int, end: Int, rule: StringRule, out: Tokens) {
        if (!rule.interpolation) {
            out.add(start, end, TokenType.STRING)
            return
        }
        var segment = start
        var j = start
        while (j < end) {
            if (line[j] == '$' && j + 1 < end && (line[j + 1] == '{' || isIdentStart(line[j + 1]))) {
                val v = minOf(scanVariable(line, j), end)
                out.add(segment, j, TokenType.STRING)
                out.add(j, v, TokenType.VARIABLE)
                j = v
                segment = v
            } else j++
        }
        out.add(segment, end, TokenType.STRING)
    }

    private fun scanVariable(line: String, at: Int): Int {
        val n = line.length
        var j = at + 1
        if (j < n && line[j] == '{') {
            val close = line.indexOf('}', j)
            return if (close < 0) n else close + 1
        }
        if (j < n && (line[j].isDigit() || line[j] in "@#?*!$-")) return j + 1
        while (j < n && isIdentPart(line[j])) j++
        return j
    }
}
