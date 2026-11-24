package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.Locale

/**
 * vCard (.vcf: contacts, 2.1 to 4.0) and iCalendar (.ics: events and tasks) as readable blocks.
 * Both are "content lines" (`NAME;PARAM=x:value`, folded with a leading space).
 */
object CardModel {
    class Line(val name: String, val params: Map<String, String>, val value: String)

    /** Unfolds and splits the content lines; quoted-printable values (vCard 2.1) are decoded. */
    fun lines(text: String): List<Line> {
        val raw = ArrayList<String>()
        for (l in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            when {
                (l.startsWith(" ") || l.startsWith("\t")) && raw.isNotEmpty() -> raw[raw.size - 1] = raw.last() + l.substring(1)
                // Quoted-printable soft line breaks end with "=".
                raw.isNotEmpty() && raw.last().endsWith("=") && raw.last().contains("QUOTED-PRINTABLE", true) -> raw[raw.size - 1] = raw.last().dropLast(1) + l
                l.isNotBlank() -> raw += l
            }
        }
        return raw.mapNotNull { l ->
            val colon = colonIndex(l)
            if (colon < 0) return@mapNotNull null
            val head = l.substring(0, colon).split(';')
            val params = HashMap<String, String>()
            for (p in head.drop(1)) {
                val eq = p.indexOf('=')
                if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
                else params["TYPE"] = (params["TYPE"]?.let { "$it," } ?: "") + p // vCard 2.1: ;HOME;VOICE
            }
            var value = l.substring(colon + 1)
            if (params["ENCODING"].equals("QUOTED-PRINTABLE", true)) {
                val cs = runCatching { charset(params["CHARSET"] ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
                value = String(MailModel.quotedPrintable(value, Charsets.ISO_8859_1), cs)
            }
            Line(head[0].substringAfter('.').uppercase(), params, value)
        }
    }

    /** The colon that ends the name and parameters (colons inside quoted parameter values do not count). */
    private fun colonIndex(l: String): Int {
        var quoted = false
        for (i in l.indices) {
            when (l[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    fun unescape(v: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) { 'n', 'N' -> sb.append('\n'); else -> sb.append(n) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Splits on unescaped [sep] (structured values like N and ADR). */
    private fun components(v: String, sep: Char = ';'): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) { sb.append(c).append(v[i + 1]); i += 2; continue }
            if (c == sep) { out += unescape(sb.toString()); sb.clear() } else sb.append(c)
            i++
        }
        out += unescape(sb.toString())
        return out
    }

    // ---- vCard -------------------------------------------------------------------------

    class CardLabels(
        val phone: String, val email: String, val address: String, val organization: String, val title: String,
        val birthday: String, val website: String, val note: String, val unnamed: String,
    )

    fun vcardBlocks(text: String, l: CardLabels): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var card: MutableList<Line>? = null
        for (line in lines(text)) {
            when {
                line.name == "BEGIN" && line.value.equals("VCARD", true) -> card = ArrayList()
                line.name == "END" && line.value.equals("VCARD", true) -> { card?.let { if (out.isNotEmpty()) out += MdBlock.Rule(0, 0); out += contact(it, l) }; card = null }
                else -> card?.add(line)
            }
        }
        return out
    }

    private fun contact(lines: List<Line>, l: CardLabels): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        fun first(n: String) = lines.firstOrNull { it.name == n }
        val name = first("FN")?.let { unescape(it.value) }?.ifBlank { null }
            ?: first("N")?.let { n -> components(n.value).let { c -> listOf(c.getOrNull(3), c.getOrNull(1), c.getOrNull(2), c.getOrNull(0), c.getOrNull(4)) }.filterNotNull().filter { it.isNotBlank() }.joinToString(" ") }?.ifBlank { null }
            ?: first("ORG")?.let { components(it.value).first() }
            ?: l.unnamed
        first("PHOTO")?.let { photo(it) }?.let { out += MdBlock.Image(it, name, 0, 0) }
        out += MdBlock.Heading(2, InlineText(name), 0, 0)
        fun field(label: String, value: String, kind: String? = null, url: String? = null) {
            if (value.isBlank()) return
            val head = if (kind.isNullOrBlank()) label else "$label ($kind)"
            val text = "$head  $value"
            val spans = arrayListOf(InlineSpan(0, head.length, InlineStyle.BOLD))
            if (url != null) spans += InlineSpan(head.length + 2, text.length, InlineStyle.LINK, url = url)
            out += MdBlock.Paragraph(InlineText(text, spans), 0, 0)
        }
        fun kind(line: Line) = line.params["TYPE"]?.split(',')?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() && it !in setOf("pref", "internet", "voice", "x400") }?.joinToString(", ")
        first("ORG")?.let { field(l.organization, components(it.value).filter { c -> c.isNotBlank() }.joinToString(" · ")) }
        first("TITLE")?.let { field(l.title, unescape(it.value)) }
        lines.filter { it.name == "TEL" }.forEach { val v = unescape(it.value).removePrefix("tel:"); field(l.phone, v, kind(it), "tel:" + v.filter { c -> c.isDigit() || c == '+' }) }
        lines.filter { it.name == "EMAIL" }.forEach { val v = unescape(it.value); field(l.email, v, kind(it), "mailto:$v") }
        lines.filter { it.name == "ADR" }.forEach { a ->
            val c = components(a.value)
            val street = listOf(c.getOrNull(0), c.getOrNull(1), c.getOrNull(2)).filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
            val city = listOf(c.getOrNull(3), c.getOrNull(4), c.getOrNull(5)).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            field(l.address, listOf(street, city, c.getOrNull(6).orEmpty()).filter { it.isNotBlank() }.joinToString(", "), kind(a))
        }
        first("BDAY")?.let { field(l.birthday, date(it.value)) }
        lines.filter { it.name == "URL" }.forEach { val v = unescape(it.value); field(l.website, v, url = v) }
        first("NOTE")?.let { field(l.note, unescape(it.value)) }
        return out
    }

    private fun photo(line: Line): String? {
        val v = line.value
        if (v.startsWith("data:")) return v
        if (line.params["ENCODING"]?.uppercase() in setOf("B", "BASE64")) {
            val type = line.params["TYPE"]?.lowercase()?.split(',')?.firstOrNull { it in setOf("jpeg", "jpg", "png", "gif") } ?: "jpeg"
            return runCatching { Base64.getMimeDecoder().decode(v); "data:image/$type;base64," + v.filterNot { it.isWhitespace() } }.getOrNull()
        }
        return null
    }

    private fun date(v: String): String {
        val digits = v.filter { it.isDigit() }
        return runCatching {
            if (digits.length >= 8) LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.getDefault()))
            else v
        }.getOrDefault(v)
    }

    // ---- iCalendar ---------------------------------------------------------------------

    class CalLabels(val location: String, val organizer: String, val repeats: String, val task: String, val untitled: String, val allDay: String)

    class Event(val kind: String, val summary: String, val start: ZonedDateTime?, val end: ZonedDateTime?, val allDay: Boolean, val props: List<Line>)

    fun events(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Event> {
        val out = ArrayList<Event>()
        var current: MutableList<Line>? = null
        var kind = ""
        var depth = 0
        for (line in lines(text)) {
            when {
                line.name == "BEGIN" && line.value.uppercase() in setOf("VEVENT", "VTODO", "VJOURNAL") && current == null -> {
                    current = ArrayList(); kind = line.value.uppercase(); depth = 0
                }
                line.name == "BEGIN" && current != null -> depth++ // VALARM inside an event
                line.name == "END" && current != null && depth > 0 -> depth--
                line.name == "END" && current != null -> {
                    val props = current!!
                    fun p(n: String) = props.firstOrNull { it.name == n }
                    // A task with only a due date is placed (and shown) at that date.
                    val startLine = p("DTSTART") ?: p("DUE")
                    val start = startLine?.let { time(it, zone) }
                    val end = (if (p("DTSTART") != null) p("DTEND") ?: p("DUE") else null)?.let { time(it, zone) }
                    val allDay = startLine?.let { it.params["VALUE"] == "DATE" || it.value.length == 8 } ?: false
                    out += Event(kind, p("SUMMARY")?.let { unescape(it.value) }.orEmpty(), start, end, allDay, props)
                    current = null
                }
                current != null && depth == 0 -> current!!.add(line)
            }
        }
        return out.sortedWith(compareBy(nullsLast()) { it.start })
    }

    internal fun time(line: Line, zone: ZoneId): ZonedDateTime? = runCatching {
        val v = line.value.trim()
        val d = LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
        if (v.length < 15) return@runCatching d.atStartOfDay(zone)
        val t = LocalDateTime.of(d, java.time.LocalTime.of(v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt()))
        val src: ZoneId = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            line.params["TZID"] != null -> runCatching { ZoneId.of(line.params["TZID"]!!) }.getOrDefault(zone)
            else -> zone
        }
        t.atZone(src).withZoneSameInstant(zone)
    }.getOrNull()

    fun icsBlocks(text: String, l: CalLabels, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val dateF = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        val timeF = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        lines(text).firstOrNull { it.name == "X-WR-CALNAME" }?.let { out += MdBlock.Heading(1, InlineText(unescape(it.value)), 0, 0) }
        for (e in events(text, zone)) {
            if (out.isNotEmpty() && out.last() !is MdBlock.Heading) out += MdBlock.Rule(0, 0)
            val title = e.summary.ifBlank { l.untitled } + if (e.kind == "VTODO") "  · ${l.task}" else ""
            out += MdBlock.Heading(2, InlineText(title), 0, 0)
            e.start?.let { s ->
                val when_ = buildString {
                    append(s.format(dateF))
                    if (e.allDay) {
                        val lastDay = e.end?.minusDays(1)
                        if (lastDay != null && lastDay.toLocalDate().isAfter(s.toLocalDate())) append(" – ").append(lastDay.format(dateF))
                        append(" · ").append(l.allDay)
                    } else {
                        append(", ").append(s.format(timeF))
                        e.end?.let { en ->
                            append(" – ")
                            if (en.toLocalDate() != s.toLocalDate()) append(en.format(dateF)).append(", ")
                            append(en.format(timeF))
                        }
                    }
                }
                out += MdBlock.Paragraph(InlineText(when_, listOf(InlineSpan(0, when_.length, InlineStyle.BOLD))), 0, 0)
            }
            fun field(label: String, value: String?) {
                if (value.isNullOrBlank()) return
                val t = "$label  $value"
                out += MdBlock.Paragraph(InlineText(t, listOf(InlineSpan(0, label.length, InlineStyle.ITALIC))), 0, 0)
            }
            field(l.location, e.props.firstOrNull { it.name == "LOCATION" }?.let { unescape(it.value) })
            field(l.organizer, e.props.firstOrNull { it.name == "ORGANIZER" }?.let { it.params["CN"] ?: it.value.removePrefix("mailto:").removePrefix("MAILTO:") })
            field(l.repeats, e.props.firstOrNull { it.name == "RRULE" }?.value)
            e.props.firstOrNull { it.name == "DESCRIPTION" }?.let { unescape(it.value) }?.takeIf { it.isNotBlank() }?.let { d ->
                d.trim().split(Regex("\n{2,}")).forEach { out += MdBlock.Paragraph(InlineText(it.trim()), 0, 0) }
            }
        }
        return out
    }
}
