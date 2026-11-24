package io.github.guilhermefeitosa66.opendocumentsviewer.formats

sealed interface EnvLine {
    data class Comment(val text: String) : EnvLine
    data class Variable(val key: String, val value: String, val exported: Boolean) : EnvLine
    data object Blank : EnvLine
    /** Anything else, shown as is. */
    data class Other(val text: String) : EnvLine
}

object Dotenv {
    fun parse(text: String): List<EnvLine> {
        val out = ArrayList<EnvLine>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()
            i++
            when {
                line.isEmpty() -> out += EnvLine.Blank
                line.startsWith("#") -> out += EnvLine.Comment(line)
                else -> {
                    var body = line
                    val exported = body.startsWith("export ")
                    if (exported) body = body.removePrefix("export ").trimStart()
                    val eq = body.indexOf('=')
                    if (eq <= 0) {
                        out += EnvLine.Other(raw)
                        continue
                    }
                    val key = body.substring(0, eq).trim()
                    var value = body.substring(eq + 1).trim()
                    val quote = value.firstOrNull()
                    if (quote == '"' || quote == '\'' || quote == '`') {
                        // Quoted values may span several lines.
                        val sb = StringBuilder(value.substring(1))
                        var closed = closingQuote(sb, quote)
                        while (closed < 0 && i < lines.size) {
                            sb.append('\n').append(lines[i])
                            i++
                            closed = closingQuote(sb, quote)
                        }
                        value = if (closed >= 0) sb.substring(0, closed) else sb.toString()
                        if (quote == '"') value = value.replace("\\n", "\n").replace("\\\"", "\"")
                    } else {
                        val hash = value.indexOf(" #")
                        if (hash >= 0) value = value.substring(0, hash).trimEnd()
                    }
                    out += EnvLine.Variable(key, value, exported)
                }
            }
        }
        // Trailing blank lines carry nothing.
        while (out.lastOrNull() == EnvLine.Blank) out.removeAt(out.size - 1)
        return out
    }

    private fun closingQuote(sb: CharSequence, quote: Char): Int {
        var j = 0
        while (j < sb.length) {
            if (sb[j] == '\\' && quote == '"') { j += 2; continue }
            if (sb[j] == quote) return j
            j++
        }
        return -1
    }

    private val secretHints = listOf("SECRET", "TOKEN", "PASSWORD", "PASSWD", "PWD", "KEY", "PRIVATE", "CREDENTIAL", "AUTH", "SALT", "DSN", "CERT", "SIGNATURE", "SESSION", "COOKIE")
    private val urlWithPassword = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@")

    /**
     * Whether a value is hidden by default. Hiding everything would make the file useless to
     * read, so plain settings (ports, flags, environments) stay visible and anything that looks
     * like a credential is hidden.
     */
    fun looksSecret(key: String, value: String): Boolean {
        if (value.isEmpty()) return false
        val k = key.uppercase()
        if (secretHints.any { it in k }) return true
        if (urlWithPassword.containsMatchIn(value)) return true
        // Long random-looking strings (API keys pasted under an innocent name).
        if (value.length >= 24 && !value.contains(' ') && value.count { it.isDigit() } >= 3 && value.count { it.isLetter() } >= 8) return true
        return false
    }
}
