package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.OdpReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdpReaderTest {
    private val pres = OdpReader.read(javaClass.getResource("/samples/sample.odp")!!.readBytes())

    @Test fun slides() {
        assertEquals(2, pres.slides.size)
        assertEquals(listOf("Garden Plan", "Beds"), pres.slides.map { it.title })
        assertEquals("Say that the soil test came back fine.", pres.slides[0].notes)
        // 16:9 like the original.
        assertEquals(12192000.0 / 6858000.0, pres.width.toDouble() / pres.height, 0.01)
    }

    @Test fun contentSurvivesTheRoundTrip() {
        val text = pres.plainText()
        for (s in listOf("Tomatoes", "in the north bed", "Water every 2 days", "Basil", "Start in May", "Bed", "Tomato")) assertTrue(s, text.contains(s))
        val body = pres.slides[0].shapes.first { it.paragraphs.any { p -> p.text.startsWith("Tomatoes") } }
        assertTrue(body.paragraphs[0].runs.first { it.text.startsWith("Tomatoes") }.bold)
        assertTrue(pres.slides[0].shapes.any { it.kind == "image" && it.image != null })
        assertTrue(pres.slides[1].shapes.any { it.kind == "table" && it.rows.size == 2 })
        val box = pres.slides[0].shapes.first { it.paragraphs.any { p -> p.text == "Start in May" } }
        assertEquals(0xFF2E7D32.toInt(), box.fill)
    }

    @Test fun lengths() {
        assertEquals(72f, OdpReader.pt("1in")!!, 0.01f)
        assertEquals(28.35f, OdpReader.pt("1cm")!!, 0.01f)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.ODP, DocumentTypes.detect("a.odp", null).kind)
    }
}
