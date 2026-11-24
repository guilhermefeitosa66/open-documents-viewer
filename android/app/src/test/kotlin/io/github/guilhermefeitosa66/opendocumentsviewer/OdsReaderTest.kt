package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.CellStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.OdsReader
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Sheet
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdsReaderTest {
    private val wb = OdsReader.read(javaClass.getResource("/samples/sample.ods")!!.readBytes())
    private val s = wb.sheets[0]
    private fun shown(ref: String): String { val (r, c) = Workbook.parseRef(ref); return s.cell(r, c)?.let(wb::display).orEmpty() }

    @Test fun sheets() {
        assertEquals(listOf("Harvest", "Secret", "Notes"), wb.sheets.map { it.name })
        assertTrue(wb.sheets[1].hidden)
        assertEquals("note <b>", wb.sheets[2].cell(1, 1)!!.value)
    }

    @Test fun valuesAsShown() {
        assertEquals("Crop", shown("A1"))
        assertEquals("Tomato & co", shown("A2"))
        assertEquals(12.5, s.cell(1, 1)!!.value)
        assertTrue(shown("C2"), shown("C2").contains("1,234.50") || shown("C2").contains("1.234,50"))
        assertEquals("=SUM(B2:B3)", s.cell(3, 1)!!.formula)
        assertEquals("TRUE", shown("E4").uppercase())
        assertTrue(3 in s.hiddenCols)
        assertTrue(2 in s.hiddenRows)
        assertTrue(s.merges.any { it.toList() == listOf(4, 0, 5, 0) })
        // Padding rows/columns are not expanded.
        assertTrue(s.maxRow < 100 && s.maxCol < 100)
    }

    @Test fun styles() {
        val st = wb.style(s.cell(0, 0)!!.style)
        assertTrue(st.bold)
        assertEquals(0xFFFFFF00.toInt(), st.fill)
    }

    @Test fun frozenPanesFromSettings() {
        val w = Workbook(listOf(Sheet("Data")), listOf(CellStyle()))
        OdsReader.freeze(
            """<office:document-settings><office:settings><config:config-item-set config:name="ooo:view-settings"><config:config-item-map-indexed config:name="Views"><config:config-item-map-entry><config:config-item-map-named config:name="Tables"><config:config-item-map-entry config:name="Data"><config:config-item config:name="HorizontalSplitMode" config:type="short">2</config:config-item><config:config-item config:name="HorizontalSplitPosition" config:type="int">1</config:config-item><config:config-item config:name="VerticalSplitMode" config:type="short">2</config:config-item><config:config-item config:name="VerticalSplitPosition" config:type="int">3</config:config-item></config:config-item-map-entry></config:config-item-map-named></config:config-item-map-entry></config:config-item-map-indexed></config:config-item-set></office:settings></office:document-settings>""",
            w,
        )
        assertEquals(3, w.sheets[0].frozenRows)
        assertEquals(1, w.sheets[0].frozenCols)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.ODS, DocumentTypes.detect("a.ods", null).kind)
        assertEquals(5.43f, OdsReader.widthChars("0.5in")!!, 0.01f)
    }
}
