package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import java.nio.charset.Charset

/**
 * PowerPoint 97–2003 (.ppt, .pps, .pot): the slides' text, placed where the file puts it, from
 * the records of the "PowerPoint Document" stream of the OLE2 container. The current edit (Current
 * User → UserEditAtom) gives the persist directory, which maps the document and each slide to their
 * offsets; each slide's drawing gives the text boxes and their anchors; placeholder text lives in
 * the document's SlideListWithText. Pictures, colors and fonts are not read: the presentation is
 * marked partial. Ported from the Linux app's reader.
 */
object PptReader {
    class NotPpt(message: String) : Exception(message)
    class Protected : Exception("the presentation is password protected")

    private const val EMU_PER_MASTER_UNIT = 914400.0 / 576
    private const val ENCRYPTED_TOKEN = 0xF3D1C4DF.toInt()
    private val TITLE_TYPES = setOf(0, 6)
    private val BODY_TYPES = setOf(1, 5, 7, 8)
    private val CENTERED_TYPES = setOf(5, 6)
    private const val NOTES_TYPE = 2

    fun read(bytes: ByteArray): Presentation {
        val cfb = try { Cfb.open(bytes) } catch (e: Cfb.NotCfb) { throw NotPpt(e.message ?: "not a PowerPoint file") }
        val doc = cfb.read("PowerPoint Document") ?: throw NotPpt("not a PowerPoint 97–2003 presentation")
        if (cfb.has("EncryptedSummary")) throw Protected()
        return Reader(doc, cfb.read("Current User")).build()
    }

    private class Rec(val ver: Int, val inst: Int, val type: Int, val body: Int, val len: Int)

    private class Entry(val ref: Int, val id: Int, val texts: MutableList<Pair<Int, String>> = ArrayList())

    private class Reader(val doc: ByteArray, currentUser: ByteArray?) {
        val persist = HashMap<Int, Int>()
        var docRef: Int? = null
        val cp1252: Charset = Charset.forName("windows-1252")

        init {
            var offset: Int? = null
            if (currentUser != null && currentUser.size >= 20) {
                if (i32(currentUser, 12) == ENCRYPTED_TOKEN) throw Protected()
                offset = i32(currentUser, 16)
            }
            val seen = HashSet<Int>()
            while (offset != null && seen.add(offset) && offset >= 0 && offset + 8 <= doc.size) {
                val type = u16(doc, offset + 2)
                val len = i32(doc, offset + 4)
                if (type != 0x0FF5 || len < 20 || offset + 28 > doc.size) break
                val lastEdit = i32(doc, offset + 16)
                val dirOffset = i32(doc, offset + 20)
                if (docRef == null) docRef = i32(doc, offset + 24)
                directory(dirOffset)
                offset = lastEdit.takeIf { it != 0 }
            }
            if (docRef == null || docRef !in persist) throw NotPpt("the presentation's edit records are missing or damaged")
        }

        fun records(start: Int, end0: Int): List<Rec> {
            val out = ArrayList<Rec>()
            var pos = start
            val end = minOf(end0, doc.size)
            while (pos >= 0 && pos + 8 <= end) {
                val verinst = u16(doc, pos)
                val type = u16(doc, pos + 2)
                var len = i32(doc, pos + 4)
                val body = pos + 8
                if (len < 0 || body + len > end) len = end - body
                out += Rec(verinst and 0xF, verinst ushr 4, type, body, len)
                pos = body + len
            }
            return out
        }

        fun directory(offset: Int) {
            if (offset < 0 || offset + 8 > doc.size) return
            val len = i32(doc, offset + 4)
            var pos = offset + 8
            val end = minOf(doc.size, offset + 8 + len)
            while (pos + 4 <= end) {
                val entry = i32(doc, pos)
                val first = entry and 0xFFFFF
                val count = entry ushr 20
                pos += 4
                for (k in 0 until count) {
                    if (pos + 4 > end) break
                    persist.putIfAbsent(first + k, i32(doc, pos))
                    pos += 4
                }
            }
        }

        /** (type, body, length) of the record a persist reference points to. */
        fun container(ref: Int): Triple<Int, Int, Int>? {
            val offset = persist[ref] ?: return null
            if (offset < 0 || offset + 8 > doc.size) return null
            return Triple(u16(doc, offset + 2), offset + 8, i32(doc, offset + 4))
        }

        fun text(r: Rec): String = if (r.type == 0x0FA0) String(doc, r.body, r.len, Charsets.UTF_16LE) else String(doc, r.body, r.len, cp1252)

        fun build(): Presentation {
            val (type, body, len) = container(docRef!!) ?: throw NotPpt("the document record is missing")
            if (type != 0x03E8) throw NotPpt("the document record is missing")
            var width = 9144000L
            var height = 6858000L
            val lists = HashMap<Int, List<Entry>>()
            for (r in records(body, body + len)) {
                if (r.type == 0x03E9 && r.len >= 8) {
                    val x = i32(doc, r.body); val y = i32(doc, r.body + 4)
                    if (x > 0 && y > 0) { width = (x * EMU_PER_MASTER_UNIT).toLong(); height = (y * EMU_PER_MASTER_UNIT).toLong() }
                } else if (r.type == 0x0FF0) lists[r.inst] = slideList(r.body, r.len)
            }
            val notes = HashMap<Int, String>()
            for (e in lists[2].orEmpty()) {
                val c = container(e.ref) ?: continue
                if (c.first == 0x03F0) notesOf(c.second, c.third)?.let { (id, text) -> notes[id] = text }
            }
            val slides = lists[0].orEmpty().map { slide(it, width, height, notes[it.id].orEmpty()) }
            if (slides.isEmpty()) throw NotPpt("no slides")
            return Presentation(width, height, slides, partial = true)
        }

        fun slideList(body: Int, len: Int): List<Entry> {
            val out = ArrayList<Entry>()
            var currentType = 4
            for (r in records(body, body + len)) {
                when {
                    r.type == 0x03F3 && r.len >= 16 -> out += Entry(i32(doc, r.body), i32(doc, r.body + 12))
                    r.type == 0x0F9F && r.len >= 4 -> currentType = i32(doc, r.body)
                    (r.type == 0x0FA0 || r.type == 0x0FA8) && out.isNotEmpty() -> out.last().texts += currentType to text(r)
                }
            }
            return out
        }

        fun notesOf(body: Int, len: Int): Pair<Int, String>? {
            var id: Int? = null
            for (r in records(body, body + len)) if (r.type == 0x03F1 && r.len >= 4) id = i32(doc, r.body)
            val texts = boxes(body, len, emptyList()).filter { it.first == NOTES_TYPE }.map { it.second }
            return id?.let { it to texts.joinToString("\n") { t -> t.replace('\r', '\n').replace('\u000b', '\n') }.trim() }
        }

        fun slide(e: Entry, width: Long, height: Long, notes: String): Slide {
            var hidden = false
            var boxes: List<Triple<Int, String, LongArray?>> = emptyList()
            container(e.ref)?.takeIf { it.first == 0x03EE }?.let { (_, body, len) ->
                for (r in records(body, body + len)) if (r.type == 0x03F9 && r.len >= 12) hidden = (u16(doc, r.body + 10) and 0x4) != 0
                boxes = boxes(body, len, e.texts)
            }
            var placed = boxes.filter { it.third != null }
            if (placed.isEmpty()) {
                // No drawing: stack the slide list's text.
                var top = (height * 0.05).toLong()
                placed = e.texts.map { (kind, text) ->
                    val h = (height * (if (kind in TITLE_TYPES) 0.18 else 0.7)).toLong()
                    val box = longArrayOf((width * 0.06).toLong(), top, (width * 0.88).toLong(), h)
                    top += h
                    Triple(kind, text, box)
                }
            }
            var title = ""
            val shapes = placed.map { (kind, text, a) ->
                if (kind in TITLE_TYPES && title.isEmpty()) title = text.split(Regex("\\s+")).joinToString(" ").trim()
                SlideShape("rect", a!![0], a[1], a[2], a[3], paragraphs = paragraphs(kind, text), anchor = if (kind in TITLE_TYPES) 'm' else 't')
            }
            return Slide(shapes, notes = notes, title = title, hidden = hidden)
        }

        fun paragraphs(kind: Int, text: String): List<SlideParagraph> {
            val size = if (kind in TITLE_TYPES) 40f else if (kind in BODY_TYPES) 28f else 18f
            val align = if (kind in CENTERED_TYPES || kind in TITLE_TYPES) 'c' else 'l'
            return text.split('\r').map { line ->
                SlideParagraph(listOf(SlideRun(line.replace('\u000b', '\n'), size = size)), align, 0, if (kind in setOf(1, 7, 8) && line.isNotBlank()) "•" else null)
            }
        }

        /** (text type, text, anchor in EMU or null) for each text box of the drawing in the record. */
        fun boxes(body: Int, len: Int, slideTexts: List<Pair<Int, String>>): List<Triple<Int, String, LongArray?>> {
            val out = ArrayList<Triple<Int, String, LongArray?>>()
            walk(body, len, slideTexts, out, null, 0)
            return out
        }

        fun walk(body: Int, len: Int, slideTexts: List<Pair<Int, String>>, out: MutableList<Triple<Int, String, LongArray?>>, t: DoubleArray?, depth: Int) {
            if (depth > 32) return
            for (r in records(body, body + len)) {
                when {
                    r.type == 0xF003 -> {
                        var inner = t
                        val first = records(r.body, r.body + r.len).firstOrNull()
                        if (first != null && first.type == 0xF004) group(first.body, first.len, t)?.let { inner = it }
                        walk(r.body, r.len, slideTexts, out, inner, depth + 1)
                    }
                    r.type == 0xF004 -> shape(r.body, r.len, slideTexts, out, t)
                    r.ver == 0xF -> walk(r.body, r.len, slideTexts, out, t, depth + 1)
                }
            }
        }

        fun anchor(body: Int, len: Int, t: DoubleArray?): LongArray? {
            for (r in records(body, body + len)) {
                if (r.type == 0xF010) {
                    val (top, left, right, bottom) = when {
                        r.len == 8 -> listOf(s16(doc, r.body), s16(doc, r.body + 2), s16(doc, r.body + 4), s16(doc, r.body + 6))
                        r.len >= 16 -> listOf(i32(doc, r.body), i32(doc, r.body + 4), i32(doc, r.body + 8), i32(doc, r.body + 12))
                        else -> continue
                    }
                    val k = EMU_PER_MASTER_UNIT
                    return longArrayOf((left * k).toLong(), (top * k).toLong(), ((right - left) * k).toLong(), ((bottom - top) * k).toLong())
                }
                if (r.type == 0xF00F && r.len >= 16 && t != null) {
                    val left = i32(doc, r.body); val top = i32(doc, r.body + 4); val right = i32(doc, r.body + 8); val bottom = i32(doc, r.body + 12)
                    return longArrayOf(
                        (t[0] + (left - t[4]) * t[2]).toLong(), (t[1] + (top - t[5]) * t[3]).toLong(),
                        ((right - left) * t[2]).toLong(), ((bottom - top) * t[3]).toLong(),
                    )
                }
            }
            return null
        }

        fun group(body: Int, len: Int, t: DoubleArray?): DoubleArray? {
            var frame: IntArray? = null
            for (r in records(body, body + len)) if (r.type == 0xF009 && r.len >= 16) frame = intArrayOf(i32(doc, r.body), i32(doc, r.body + 4), i32(doc, r.body + 8), i32(doc, r.body + 12))
            val a = anchor(body, len, t) ?: return null
            val f = frame ?: return null
            return doubleArrayOf(a[0].toDouble(), a[1].toDouble(), a[2].toDouble() / maxOf(1, f[2] - f[0]), a[3].toDouble() / maxOf(1, f[3] - f[1]), f[0].toDouble(), f[1].toDouble())
        }

        fun shape(body: Int, len: Int, slideTexts: List<Pair<Int, String>>, out: MutableList<Triple<Int, String, LongArray?>>, t: DoubleArray?) {
            val box = records(body, body + len).lastOrNull { it.type == 0xF00D } ?: return
            val a = anchor(body, len, t)
            var kind = 4
            val chunks = ArrayList<String>()
            for (r in records(box.body, box.body + box.len)) {
                when {
                    r.type == 0x0F9E && r.len >= 4 -> {
                        val index = i32(doc, r.body)
                        if (index in slideTexts.indices) { kind = slideTexts[index].first; chunks += slideTexts[index].second }
                    }
                    r.type == 0x0F9F && r.len >= 4 -> kind = i32(doc, r.body)
                    r.type == 0x0FA0 || r.type == 0x0FA8 -> chunks += text(r)
                }
            }
            val text = chunks.joinToString("\r")
            if (text.isNotBlank()) out += Triple(kind, text, a)
        }
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun s16(d: ByteArray, o: Int) = u16(d, o).toShort().toInt()
    private fun i32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)
}
