package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import java.nio.charset.Charset
import java.util.Base64

/**
 * E-mail messages (.eml, RFC 822 + MIME), parsed with no library. The body shown is the HTML part
 * (through [HtmlModel], so nothing runs and nothing remote loads; inline `cid:` pictures become
 * data: URIs) or else the plain text part. Attachments are listed with links `attachment:<n>` that
 * the viewer turns into "save a copy".
 *
 * [raw] is the file decoded as text; [rawCharset] turns it back into the original bytes for
 * 8-bit parts.
 */
object MailModel {
    const val ATTACHMENT_SCHEME = "attachment:"

    class Part(val headers: List<Pair<String, String>>, val body: String, val children: List<Part>) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val contentType: String get() = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
        fun param(header: String, name: String): String? = params(header(header).orEmpty())[name.lowercase()]
    }

    class Attachment(val name: String, val mimeType: String, val bytes: ByteArray)

    class Message(
        val headers: List<Pair<String, String>>,
        val html: String?,
        val text: String?,
        val attachments: List<Attachment>,
    ) {
        fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    // ---- Parsing -----------------------------------------------------------------------

    fun parse(raw: String, rawCharset: String = "UTF-8"): Message {
        val root = parsePart(raw.replace("\r\n", "\n"))
        val cs = runCatching { Charset.forName(rawCharset) }.getOrDefault(Charsets.UTF_8)
        var html: String? = null
        var text: String? = null
        val attachments = ArrayList<Attachment>()
        val inline = HashMap<String, String>() // cid → data: URI

        fun walk(p: Part) {
            val type = p.contentType
            if (type.startsWith("multipart/")) {
                if (type == "multipart/alternative") {
                    // Richest alternative this viewer can show: html, else text.
                    val pick = p.children.lastOrNull { it.contentType == "text/html" }
                        ?: p.children.lastOrNull { it.contentType.startsWith("multipart/") }
                        ?: p.children.lastOrNull { it.contentType == "text/plain" }
                    p.children.forEach { c -> if (c === pick || c.contentType.startsWith("multipart/")) walk(c) }
                } else p.children.forEach(::walk)
                return
            }
            if (type == "message/rfc822" && p.children.isNotEmpty()) {
                // Forwarded message: saved as an attachment (.eml).
                attachments += Attachment(filename(p) ?: "message.eml", type, p.body.toByteArray(cs))
                return
            }
            val disposition = p.header("Content-Disposition")?.substringBefore(';')?.trim()?.lowercase()
            val name = filename(p)
            val cid = p.header("Content-ID")?.trim()?.removePrefix("<")?.removeSuffix(">")
            val isText = type == "text/plain" || type == "text/html"
            if (isText && disposition != "attachment" && name == null) {
                val decoded = decodeText(p, cs)
                if (type == "text/html") { if (html == null) html = decoded } else if (text == null) text = decoded
                return
            }
            val bytes = decodeBytes(p, cs)
            if (cid != null && type.startsWith("image/")) {
                inline[cid] = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
                if (disposition != "attachment") return
            }
            attachments += Attachment(name ?: "attachment-${attachments.size + 1}", type, bytes)
        }
        walk(root)
        val withImages = html?.let { h ->
            Regex("(?i)cid:([^\"'\\s)>]+)").replace(h) { m -> inline[m.groupValues[1]] ?: m.value }
        }
        return Message(root.headers, withImages, text, attachments)
    }

    private fun parsePart(s: String): Part {
        val split = s.indexOf("\n\n").let { if (it < 0) s.length else it }
        val headers = headers(s.substring(0, split))
        val body = if (split + 2 <= s.length) s.substring(minOf(split + 2, s.length)) else ""
        val type = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second.orEmpty()
        val mime = type.substringBefore(';').trim().lowercase()
        val children = when {
            mime.startsWith("multipart/") -> params(type)["boundary"]?.let { b -> splitMultipart(body, b).map(::parsePart) }.orEmpty()
            mime == "message/rfc822" -> listOf(parsePart(body))
            else -> emptyList()
        }
        return Part(headers, body, children)
    }

    private fun headers(block: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in block.split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                val (k, v) = out.removeAt(out.size - 1)
                out += k to (v + " " + line.trim())
            } else {
                val colon = line.indexOf(':')
                if (colon > 0) out += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
        }
        return out.map { it.first to decodeWords(it.second) }
    }

    private fun splitMultipart(body: String, boundary: String): List<String> {
        val parts = ArrayList<String>()
        val delim = "--$boundary"
        var current: StringBuilder? = null
        for (line in body.split('\n')) {
            val t = line.trimEnd()
            when {
                t == "$delim--" -> { current?.let { parts += it.toString() }; current = null; break }
                t == delim -> { current?.let { parts += it.toString() }; current = StringBuilder() }
                current != null -> current!!.append(line).append('\n')
            }
        }
        current?.let { parts += it.toString() }
        return parts.map { it.removeSuffix("\n") }
    }

    /** `type/sub; a=1; name="x y"` → {a=1, name=x y}; RFC 2231 `name*=utf-8''...` too. */
    internal fun params(header: String): Map<String, String> {
        val out = HashMap<String, String>()
        val re = Regex("""(?i);\s*([a-z0-9_.*-]+)\s*=\s*("((?:[^"\\]|\\.)*)"|[^;]*)""")
        for (m in re.findAll(header)) {
            val key = m.groupValues[1].lowercase()
            val value = if (m.groupValues[3].isNotEmpty() || m.groupValues[2].startsWith("\"")) m.groupValues[3].replace("\\\"", "\"") else m.groupValues[2].trim()
            if (key.endsWith("*")) {
                val k = key.removeSuffix("*").substringBefore('*')
                val v = value.split("'", limit = 3)
                val decoded = if (v.size == 3) runCatching { java.net.URLDecoder.decode(v[2].replace("+", "%2B"), v[0].ifEmpty { "UTF-8" }) }.getOrDefault(v[2]) else value
                out[k] = (out[k].takeIf { key.count { it == '*' } > 1 } ?: "") + decoded
            } else out.putIfAbsent(key, value)
        }
        return out
    }

    private fun filename(p: Part): String? =
        (p.param("Content-Disposition", "filename") ?: p.param("Content-Type", "name"))?.takeIf { it.isNotBlank() }?.let(::decodeWords)

    private fun charsetOf(p: Part): Charset =
        p.param("Content-Type", "charset")?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() } ?: Charsets.UTF_8

    private fun decodeBytes(p: Part, raw: Charset): ByteArray = when (p.header("Content-Transfer-Encoding")?.trim()?.lowercase()) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(p.body.trim()) }.getOrDefault(ByteArray(0))
        "quoted-printable" -> quotedPrintable(p.body, raw)
        else -> p.body.toByteArray(raw)
    }

    private fun decodeText(p: Part, raw: Charset): String = String(decodeBytes(p, raw), charsetOf(p))

    internal fun quotedPrintable(s: String, raw: Charset): ByteArray {
        val out = java.io.ByteArrayOutputStream(s.length)
        val bytes = s.toByteArray(raw)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '='.code.toByte()) {
                if (i + 1 < bytes.size && bytes[i + 1] == '\n'.code.toByte()) { i += 2; continue }
                if (i + 2 < bytes.size && bytes[i + 1] == '\r'.code.toByte() && bytes[i + 2] == '\n'.code.toByte()) { i += 3; continue }
                if (i + 2 < bytes.size) {
                    val hex = String(byteArrayOf(bytes[i + 1], bytes[i + 2]), Charsets.US_ASCII).toIntOrNull(16)
                    if (hex != null) { out.write(hex); i += 3; continue }
                }
            }
            out.write(b.toInt()); i++
        }
        return out.toByteArray()
    }

    /** RFC 2047 encoded words: `=?UTF-8?B?...?=` and `=?ISO-8859-1?Q?...?=`. */
    internal fun decodeWords(s: String): String {
        if (!s.contains("=?")) return s
        val re = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")
        // Whitespace between two encoded words is dropped.
        val joined = Regex("""(\?=)\s+(=\?)""").replace(s, "$1$2")
        return re.replace(joined) { m ->
            val cs = runCatching { Charset.forName(m.groupValues[1].substringBefore('*')) }.getOrDefault(Charsets.UTF_8)
            val data = m.groupValues[3]
            runCatching {
                if (m.groupValues[2].equals("B", true)) String(Base64.getDecoder().decode(data), cs)
                else String(quotedPrintable(data.replace('_', ' '), Charsets.ISO_8859_1), cs)
            }.getOrDefault(m.value)
        }
    }

    // ---- Blocks ------------------------------------------------------------------------

    class Labels(val from: String, val to: String, val cc: String, val date: String, val attachments: String)

    fun blocks(m: Message, labels: Labels): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        out += MdBlock.Heading(1, InlineText(m.header("Subject")?.ifBlank { null } ?: "(no subject)"), 0, 0)
        fun field(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            val t = "$label  $value"
            out += MdBlock.Paragraph(InlineText(t, listOf(InlineSpan(0, label.length, InlineStyle.BOLD))), 0, 0)
        }
        field(labels.from, m.header("From"))
        field(labels.to, m.header("To"))
        field(labels.cc, m.header("Cc"))
        field(labels.date, m.header("Date"))
        out += MdBlock.Rule(0, 0)
        when {
            m.html != null -> out += HtmlModel.parse(org.jsoup.Jsoup.parse(m.html), useTitle = false).blocks
            m.text != null -> m.text.trimEnd().split(Regex("\n{2,}")).forEach { para ->
                if (para.isNotBlank()) {
                    val quoted = para.lines().all { it.startsWith(">") }
                    val body = if (quoted) para.lines().joinToString("\n") { it.removePrefix(">").removePrefix(" ") } else para
                    out += MdBlock.Paragraph(InlineText(body.trimEnd()), 0, if (quoted) 1 else 0)
                }
            }
        }
        if (m.attachments.isNotEmpty()) {
            out += MdBlock.Heading(2, InlineText(labels.attachments), 0, 0)
            m.attachments.forEachIndexed { i, a ->
                val t = "${a.name}  (${size(a.bytes.size)})"
                out += MdBlock.ListItem(
                    "•", null,
                    InlineText(t, listOf(InlineSpan(0, a.name.length, InlineStyle.LINK, url = ATTACHMENT_SCHEME + i))),
                    0, 0,
                )
            }
        }
        return out
    }

    private fun size(n: Int) = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0))
    }
}
