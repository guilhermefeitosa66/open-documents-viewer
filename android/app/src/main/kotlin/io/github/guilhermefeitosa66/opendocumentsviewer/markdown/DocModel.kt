package io.github.guilhermefeitosa66.opendocumentsviewer.markdown

import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Cfb
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Word 97–2003 (.doc, .dot): the text, read from the piece table (CLX) the FIB points to, each
 * piece in windows-1252 or UTF-16. Field instructions are dropped (their result kept), tables are
 * rebuilt from the cell marks, page breaks become rules. Formatting is not read: the result is
 * marked partial so the viewer says so. Ported from the Linux app's reader.
 */
object DocModel {
    class NotDoc(message: String) : Exception(message)
    class Protected : Exception("password protected")

    fun parse(bytes: ByteArray): DocxModel.Result {
        val cfb = try { Cfb.open(bytes) } catch (e: Cfb.NotCfb) { throw NotDoc(e.message ?: "not a Word file") }
        val word = cfb.read("WordDocument") ?: throw NotDoc("not a Word 97–2003 document")
        val flags = if (word.size > 12) u16(word, 0x0A) else 0
        val table = cfb.read(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw NotDoc("the table stream is missing")
        return blocks(extractText(word, table))
    }

    internal fun extractText(word: ByteArray, table: ByteArray): String {
        if (word.size < 0x200 || u16(word, 0) != 0xA5EC) throw NotDoc("not a Word 97–2003 document")
        if (u16(word, 0x0A) and 0x0100 != 0) throw Protected()
        var pos = 32
        val csw = u16(word, pos)
        pos += 2 + csw * 2
        val cslw = u16(word, pos)
        val lw = pos + 2
        val ccpText = i32(word, lw + 3 * 4)
        pos = lw + cslw * 4 + 2
        val fcClx = i32(word, pos + 33 * 8)
        val lcbClx = i32(word, pos + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx + lcbClx > table.size) throw NotDoc("the piece table is missing")
        val clx = table.copyOfRange(fcClx, fcClx + lcbClx)
        var i = 0
        while (i < clx.size && clx[i] == 0x01.toByte()) i += 3 + u16(clx, i + 1)
        if (i >= clx.size || clx[i] != 0x02.toByte()) throw NotDoc("the piece table is missing")
        val lcb = i32(clx, i + 1)
        val plcStart = i + 5
        val n = (lcb - 4) / 12
        val cp = IntArray(n + 1) { i32(clx, plcStart + it * 4) }
        val sb = StringBuilder()
        var remaining = if (ccpText > 0) ccpText else cp[n]
        val cp1252 = Charset.forName("windows-1252")
        for (k in 0 until n) {
            if (remaining <= 0) break
            val count = minOf(cp[k + 1] - cp[k], remaining)
            val fcRaw = i32(clx, plcStart + (n + 1) * 4 + k * 8 + 2)
            if (fcRaw and 0x40000000 != 0) {
                val off = (fcRaw and 0x3FFFFFFF) / 2
                if (off + count <= word.size) sb.append(String(word, off, count, cp1252))
            } else {
                val off = fcRaw and 0x3FFFFFFF
                if (off + count * 2 <= word.size) sb.append(String(word, off, count * 2, Charsets.UTF_16LE))
            }
            remaining -= count
        }
        return sb.toString()
    }

    /** \u0013 instruction \u0014 result \u0015 → result (nested fields too). */
    internal fun stripFields(text: String): String {
        val sb = StringBuilder(text.length)
        val stack = ArrayList<Boolean>()
        for (ch in text) {
            when {
                ch == '\u0013' -> stack += false
                ch == '\u0014' && stack.isNotEmpty() -> stack[stack.size - 1] = true
                ch == '\u0015' && stack.isNotEmpty() -> stack.removeAt(stack.size - 1)
                stack.isEmpty() || stack.all { it } -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    internal fun blocks(raw: String): DocxModel.Result {
        var text = stripFields(raw)
        for (c in "\u0001\u0002\u0005\u0008") text = text.replace(c.toString(), "")
        text = text.replace('\u000b', '\n').replace('\u001e', '-').replace("\u001f", "").replace(' ', ' ')
        // The row-end mark plays the paragraph mark's part.
        text = Regex("\u0007\u0007(?=[^\u0007\r])").replace(text, "\u0007\u0007\r")
        val out = ArrayList<MdBlock>()
        var rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        fun flushTable() {
            if (rows.isNotEmpty()) {
                val width = rows.maxOf { it.size }
                val padded = rows.map { r -> (r + List(width - r.size) { "" }).map { InlineText(it) } }
                out += MdBlock.Table(padded.first(), padded.drop(1), List(width) { CellAlign.START }, 0, 0)
            }
            rows = ArrayList()
        }
        // Text after the last cell mark of a paragraph starts the next cell (cells with several paragraphs).
        var carry: String? = null
        for (raw0 in text.split('\r')) {
            val paragraph = carry?.let { it + "\n" + raw0 } ?: raw0
            carry = null
            if ('\u0007' in paragraph) {
                val cell = StringBuilder()
                var afterMark = false
                for (ch in paragraph) {
                    if (ch == '\u0007') {
                        if (afterMark && cell.isEmpty()) { rows += row; row = ArrayList(); afterMark = false }
                        else { row += cell.toString().trim(); cell.clear(); afterMark = true }
                    } else {
                        cell.append(ch)
                        afterMark = afterMark && ch.isWhitespace()
                    }
                }
                if (cell.isNotEmpty()) carry = cell.toString()
                continue
            }
            if (row.isNotEmpty()) { rows += row; row = ArrayList() }
            flushTable()
            val parts = paragraph.split('\u000c')
            parts.forEachIndexed { j, part ->
                if (j > 0 && out.isNotEmpty() && out.last() !is MdBlock.Rule) out += MdBlock.Rule(0, 0)
                val t = part.trim()
                if (t.isNotEmpty()) out += MdBlock.Paragraph(InlineText(t), 0, 0)
            }
        }
        carry?.trim()?.takeIf { it.isNotEmpty() }?.let { row += it }
        if (row.isNotEmpty()) rows += row
        flushTable()
        return DocxModel.Result(out, emptyMap(), partial = true)
    }

    private fun u16(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    private fun i32(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
