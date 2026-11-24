package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Workbook
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.XlsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsReaderTest {
    private val wb = XlsReader.read(javaClass.getResource("/samples/sample.xls")!!.readBytes())
    private val s = wb.sheets[0]
    private fun shown(ref: String): String { val (r, c) = Workbook.parseRef(ref); return s.cell(r, c)?.let(wb::display).orEmpty() }

    @Test fun sheetsAndValues() {
        assertEquals(listOf("Harvest", "Secret", "Notes"), wb.sheets.map { it.name })
        assertTrue(wb.sheets[1].hidden)
        assertEquals("note <b>", wb.sheets[2].cell(1, 1)!!.value)
        assertEquals("Crop", shown("A1"))
        assertEquals("Tomato & co", shown("A2"))
        assertEquals("12.5", shown("B2"))
        assertEquals("R$ 1,234.50", shown("C2"))
        assertEquals("24/11/2026", shown("E2"))
        assertEquals("25.60%", shown("C4"))
        assertEquals("12.5", shown("B4")) // formula result
        assertEquals("TRUE", shown("E4"))
        assertEquals("#DIV/0!", shown("B5"))
        assertEquals("xy", shown("A6")) // string formula result
    }

    @Test fun layoutAndStyles() {
        assertTrue(3 in s.hiddenCols)
        assertTrue(2 in s.hiddenRows)
        assertTrue(s.merges.any { it.toList() == listOf(4, 0, 5, 0) })
        val st = wb.style(s.cell(0, 0)!!.style)
        assertTrue(st.bold)
        assertEquals(0xFFFF0000.toInt(), st.color)
        assertEquals(0xFFFFFF00.toInt(), st.fill)
        assertEquals('c', st.align)
    }

    @Test fun rkNumbers() {
        assertEquals(1.0, XlsReader.rk(0x3FF00000), 0.0)
        assertEquals(12.34, XlsReader.rk((1234 shl 2) or 0x03), 1e-9)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.XLS, DocumentTypes.detect("a.xls", null).kind)
    }
}
