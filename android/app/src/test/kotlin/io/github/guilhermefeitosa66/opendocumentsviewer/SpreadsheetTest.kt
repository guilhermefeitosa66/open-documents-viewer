package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.NumberFormat
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Workbook
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.XlsxReader
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.XmlScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetTest {
    private val wb = XlsxReader.read(javaClass.getResource("/samples/sample.xlsx")!!.readBytes())
    private val s = wb.sheets[0]
    private fun shown(ref: String): String { val (r, c) = Workbook.parseRef(ref); return s.cell(r, c)?.let(wb::display).orEmpty() }

    @Test fun sheetsAndLayout() {
        assertEquals(listOf("Harvest", "Secret", "Notes"), wb.sheets.map { it.name })
        assertTrue(wb.sheets[1].hidden)
        assertEquals("note <b>", wb.sheets[2].cell(1, 1)!!.value)
        assertEquals(1, s.frozenRows); assertEquals(1, s.frozenCols)
        assertEquals(20f, s.colWidths[0]); assertTrue(3 in s.hiddenCols); assertTrue(2 in s.hiddenRows)
        assertEquals(listOf(4, 0, 5, 0), s.merges.single().toList())
        assertEquals(listOf("Kilos per crop"), s.charts)
        assertEquals(5, s.maxCol)
    }

    @Test fun valuesAndFormats() {
        assertEquals("Crop", shown("A1"))
        assertEquals("Tomato & co", shown("A2"))
        assertEquals("12.5", shown("B2"))
        assertEquals("R$ 1,234.50", shown("C2"))
        assertEquals("24/11/2026", shown("E2"))
        assertEquals("25.60%", shown("C4"))
        assertEquals("TRUE", shown("E4"))
        assertEquals("#DIV/0!", shown("B5"))
        assertEquals("xy", shown("A6"))
        assertEquals("2023-03-15", shown("B6"))
        assertEquals("=SUM(B2:B3)", s.cell(3, 1)!!.formula)
    }

    @Test fun styles() {
        val st = wb.style(s.cell(0, 0)!!.style)
        assertTrue(st.bold); assertEquals(0xFFFF0000.toInt(), st.color); assertEquals(0xFFFFFF00.toInt(), st.fill)
        assertEquals('c', st.align); assertTrue(st.border)
    }

    @Test fun numberFormats() {
        assertEquals("1,234,568", NumberFormat.format(1234567.89, "#,##0"))
        assertEquals("007", NumberFormat.format(7.0, "000"))
        assertEquals("(12.00)", NumberFormat.format(-12.0, "#,##0.00;(#,##0.00)"))
        assertEquals("1.50E+03", NumberFormat.format(1500.0, "0.00E+00"))
        assertEquals("0.3333333333", NumberFormat.format(1.0 / 3, "General"))
        assertEquals("15:30", NumberFormat.format(0.6458333333, "h:mm"))
        assertEquals("Mar 2023", NumberFormat.format(45000.0, "mmm yyyy"))
        assertEquals("€ 3", NumberFormat.format(3.0, "[\$€-2] 0"))
        assertEquals("text", NumberFormat.format("text", "0.00"))
    }

    @Test fun plainTextSkipsHidden() {
        val t = wb.plainText()
        assertFalse(t.contains("hidden row"))
        assertFalse(t.contains("# Secret"))
        assertTrue(t.contains("# Notes"))
    }

    @Test fun scanner() {
        val x = XmlScan("<?xml?><a x=\"1 &amp; 2\"><b/>t&#233;xt<![CDATA[<raw>]]></a>")
        assertEquals(XmlScan.Event.START, x.next()); assertEquals("1 & 2", x.attr("x"))
        assertEquals(XmlScan.Event.START, x.next()); assertTrue(x.empty)
        assertEquals(XmlScan.Event.END, x.next())
        assertEquals(XmlScan.Event.TEXT, x.next()); assertEquals("téxt", x.text)
        assertEquals(XmlScan.Event.TEXT, x.next()); assertEquals("<raw>", x.text)
        assertEquals(XmlScan.Event.END, x.next())
    }

    @Test fun detection() {
        assertEquals(DocumentKind.XLSX, DocumentTypes.detect("a.xlsx", null).kind)
        assertEquals("AA", Workbook.colName(26))
        assertEquals(27 to 26, Workbook.parseRef("\$AA\$28"))
    }
}
