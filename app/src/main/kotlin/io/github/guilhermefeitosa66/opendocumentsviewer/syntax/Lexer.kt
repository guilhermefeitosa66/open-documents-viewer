package io.github.guilhermefeitosa66.opendocumentsviewer.syntax

enum class TokenType {
    KEYWORD, LITERAL, TYPE, STRING, NUMBER, COMMENT, FUNCTION, ANNOTATION, VARIABLE,
    KEY, SECTION, TAG, ATTRIBUTE, PUNCTUATION, HEADING, MARKUP, LINK, CODE,
}

/** Tokens of one line as packed (start, end, type) triples, offsets relative to the line. */
class Tokens {
    private var data = IntArray(48)
    var size = 0
        private set

    fun add(start: Int, end: Int, type: TokenType) {
        if (end <= start) return
        if (size * 3 + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size * 3] = start
        data[size * 3 + 1] = end
        data[size * 3 + 2] = type.ordinal
        size++
    }

    fun reset() {
        size = 0
    }

    fun start(i: Int) = data[i * 3]
    fun end(i: Int) = data[i * 3 + 1]
    fun type(i: Int): TokenType = TokenType.entries[data[i * 3 + 2]]

    fun toList(): List<Triple<Int, Int, TokenType>> = (0 until size).map { Triple(start(it), end(it), type(it)) }
}

/**
 * Tokenizes one line at a time. The int state carries what is still open at the end of a line
 * (a block comment, a multi-line string, a fenced code block...) so any line can be tokenized
 * on its own once the state at its start is known. State 0 means "nothing open".
 */
interface Lexer {
    fun tokenize(line: String, state: Int, out: Tokens): Int
}

internal fun isIdentStart(c: Char) = c == '_' || c.isLetter()
internal fun isIdentPart(c: Char) = c == '_' || c.isLetterOrDigit()

internal fun String.startsWithAt(prefix: String, index: Int): Boolean = regionMatches(index, prefix, 0, prefix.length)

/** Reads a number starting at [start]; returns its end. */
internal fun scanNumber(line: String, start: Int): Int {
    val n = line.length
    var i = start
    if (line[i] == '0' && i + 1 < n && (line[i + 1] == 'x' || line[i + 1] == 'X' || line[i + 1] == 'b' || line[i + 1] == 'B' || line[i + 1] == 'o')) {
        i += 2
        while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        return i
    }
    while (i < n && (line[i].isDigit() || line[i] == '_')) i++
    if (i + 1 < n && line[i] == '.' && line[i + 1].isDigit()) {
        i++
        while (i < n && (line[i].isDigit() || line[i] == '_')) i++
    }
    if (i < n && (line[i] == 'e' || line[i] == 'E')) {
        var j = i + 1
        if (j < n && (line[j] == '+' || line[j] == '-')) j++
        if (j < n && line[j].isDigit()) {
            i = j
            while (i < n && line[i].isDigit()) i++
        }
    }
    // Suffixes: 10L, 1.5f, 10u8, 3n...
    while (i < n && (line[i].isLetter() || line[i].isDigit())) i++
    return i
}
