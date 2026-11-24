package io.github.guilhermefeitosa66.opendocumentsviewer.formats

/**
 * A small pull scanner for well-formed XML (spreadsheet parts can be tens of MB: no DOM). Gives
 * start tags with attributes, end tags and text; namespaces are kept as prefixes ("c", "x:c").
 * Entities and CDATA are decoded. Runs the same on Android and in JVM tests.
 */
class XmlScan(private val s: String) {
    enum class Event { START, END, TEXT, EOF }

    var name: String = ""
        private set
    /** For START: self-closing (`<c/>`); an END event follows right away. */
    var empty = false
        private set
    var text: String = ""
        private set
    private val keys = ArrayList<String>()
    private val values = ArrayList<String>()
    private var pos = 0
    private var pendingEnd: String? = null

    fun attr(key: String): String? {
        for (i in keys.indices) if (keys[i] == key) return values[i]
        // Without prefix: "r:id" asked as "id" too.
        for (i in keys.indices) if (keys[i].substringAfter(':') == key) return values[i]
        return null
    }

    /** Local name (no prefix), computed once per tag. */
    var local: String = ""
        private set

    fun next(): Event {
        pendingEnd?.let { name = it; local = localOf(it); pendingEnd = null; return Event.END }
        while (true) {
            if (pos >= s.length) return Event.EOF
            if (s[pos] != '<') {
                val end = s.indexOf('<', pos).let { if (it < 0) s.length else it }
                text = decode(s, pos, end)
                pos = end
                return Event.TEXT
            }
            when {
                s.startsWith("<!--", pos) -> { pos = s.indexOf("-->", pos).let { if (it < 0) s.length else it + 3 } }
                s.startsWith("<![CDATA[", pos) -> {
                    val end = s.indexOf("]]>", pos).let { if (it < 0) s.length else it }
                    text = s.substring(pos + 9, end)
                    pos = minOf(s.length, end + 3)
                    return Event.TEXT
                }
                s.startsWith("<?", pos) -> { pos = s.indexOf("?>", pos).let { if (it < 0) s.length else it + 2 } }
                s.startsWith("<!", pos) -> { pos = s.indexOf('>', pos).let { if (it < 0) s.length else it + 1 } }
                s.startsWith("</", pos) -> {
                    val end = s.indexOf('>', pos).let { if (it < 0) s.length else it }
                    name = s.substring(pos + 2, end).trim()
                    local = localOf(name)
                    pos = end + 1
                    return Event.END
                }
                else -> return start()
            }
        }
    }

    private fun start(): Event {
        keys.clear(); values.clear()
        var i = pos + 1
        while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
        name = s.substring(pos + 1, i)
        local = localOf(name)
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == '>') { empty = false; pos = i + 1; return Event.START }
            if (s[i] == '/') {
                empty = true
                pos = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                pendingEnd = name
                return Event.START
            }
            val eq = s.indexOf('=', i)
            if (eq < 0) { pos = s.length; break }
            val key = s.substring(i, eq).trim()
            var q = eq + 1
            while (q < s.length && s[q].isWhitespace()) q++
            val quote = s.getOrNull(q) ?: break
            val close = s.indexOf(quote, q + 1).let { if (it < 0) s.length else it }
            keys += key
            values += decode(s, q + 1, close)
            i = close + 1
        }
        empty = false
        pos = s.length
        return Event.START
    }

    /** Text of the current element up to its end tag (for `<v>`, `<t>`, `<f>`). */
    fun readText(): String {
        if (empty) { next(); return "" }
        val sb = StringBuilder()
        var depth = 0
        while (true) {
            when (next()) {
                Event.TEXT -> sb.append(text)
                Event.START -> if (!empty) depth++
                Event.END -> { if (depth == 0) return sb.toString(); depth-- }
                Event.EOF -> return sb.toString()
            }
        }
    }

    /** Skips the current element and everything inside it. */
    fun skip() {
        if (empty) { next(); return }
        var depth = 0
        while (true) {
            when (next()) {
                Event.START -> if (!empty) depth++
                Event.END -> { if (depth == 0) return; depth-- }
                Event.EOF -> return
                else -> Unit
            }
        }
    }

    companion object {
        private fun localOf(n: String): String { val c = n.indexOf(':'); return if (c < 0) n else n.substring(c + 1) }

        fun decode(s: String, from: Int, to: Int): String {
            // Only look inside [from, to): searching the rest of a big document each time is quadratic.
            var amp = -1
            for (k in from until to) if (s[k] == '&') { amp = k; break }
            if (amp < 0) return s.substring(from, to)
            val sb = StringBuilder(to - from)
            var i = from
            while (i < to) {
                val c = s[i]
                if (c == '&') {
                    var semi = -1
                    for (k in i + 1 until minOf(to, i + 13)) if (s[k] == ';') { semi = k; break }
                    if (semi > i) {
                        val ent = s.substring(i + 1, semi)
                        val rep = when {
                            ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "quot" -> "\""; ent == "apos" -> "'"
                            ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                            ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
                            else -> null
                        }
                        if (rep != null) { sb.append(rep); i = semi + 1; continue }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
