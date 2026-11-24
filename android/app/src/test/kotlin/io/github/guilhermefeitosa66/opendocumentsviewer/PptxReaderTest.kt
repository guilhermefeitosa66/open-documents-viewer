package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.PptxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxReaderTest {
    private val pres = PptxReader.read(javaClass.getResource("/samples/sample.pptx")!!.readBytes())

    @Test fun slidesTitlesNotes() {
        assertEquals(12192000L, pres.width)
        assertEquals(listOf("Garden Plan", "Beds"), pres.slides.map { it.title })
        assertEquals("Say that the soil test came back fine.", pres.slides[0].notes)
        assertTrue(pres.plainText().contains("Water every 2 days"))
    }

    @Test fun placeholdersInheritPositionSizeAndBullets() {
        val s = pres.slides[0]
        val title = s.shapes.first { it.paragraphs.firstOrNull()?.text == "Garden Plan" }
        assertEquals(838200L, title.x); assertEquals(10515600L, title.w)
        assertEquals(44f, title.paragraphs[0].runs[0].size)
        val body = s.shapes.first { it.paragraphs.any { p -> p.text.startsWith("Tomatoes") } }
        val p0 = body.paragraphs[0]
        assertEquals("•", p0.bullet); assertTrue(p0.runs[0].bold); assertEquals(28f, p0.runs[0].size)
        assertEquals(1, body.paragraphs[1].level); assertEquals(24f, body.paragraphs[1].runs[0].size)
        assertEquals(0xFFC00000.toInt(), body.paragraphs[2].runs[0].color)
    }

    @Test fun shapesPicturesTables() {
        val box = pres.slides[0].shapes.first { it.kind == "roundRect" }
        assertEquals(0xFF2E7D32.toInt(), box.fill)
        assertEquals(0xFF000000.toInt(), box.line); assertEquals(2f, box.lineWidth)
        assertEquals('m', box.anchor); assertEquals('c', box.paragraphs[0].align)
        assertEquals("ppt/media/leaf.png", pres.slides[0].shapes.first { it.kind == "image" }.image)
        assertEquals(0xFFFFFFFF.toInt(), pres.slides[0].background)
        assertEquals(0xFFFFF8E1.toInt(), pres.slides[1].background)
        val t = pres.slides[1].shapes.first { it.kind == "table" }
        assertEquals(listOf(listOf("Bed", "Plant"), listOf("North", "Tomato")), t.rows.map { r -> r.map { c -> c.joinToString { it.text } } })
    }

    @Test fun colorModifiers() {
        // Luminance offset on black and luminance modulation on white both land on mid gray.
        assertEquals(0xFF808080.toInt(), PptxReader.lumMod(0xFF000000.toInt(), null, 0.5f, null, null))
        assertEquals(0xFF808080.toInt(), PptxReader.lumMod(0xFFFFFFFF.toInt(), 0.5f, null, null, null))
        assertEquals(0xFFFF0000.toInt(), PptxReader.lumMod(0xFFFF0000.toInt(), null, null, null, null))
    }

    @Test fun detection() {
        assertEquals(DocumentKind.PPTX, DocumentTypes.detect("a.pptx", null).kind)
        assertEquals(DocumentKind.PPTX, DocumentTypes.detect("a.ppsx", null).kind)
    }
}
