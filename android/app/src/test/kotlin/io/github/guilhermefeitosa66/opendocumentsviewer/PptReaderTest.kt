package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.PptReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PptReaderTest {
    private val pres = PptReader.read(javaClass.getResource("/samples/sample.ppt")!!.readBytes())

    @Test fun slidesTextAndNotes() {
        assertTrue(pres.partial)
        assertEquals(2, pres.slides.size)
        assertEquals("Garden Plan", pres.slides[0].title)
        assertEquals("Beds", pres.slides[1].title)
        val text = pres.plainText()
        for (s in listOf("Tomatoes in the north bed", "Water every 2 days", "Basil", "Start in May")) assertTrue(s, text.contains(s))
        assertEquals("Say that the soil test came back fine.", pres.slides[0].notes)
        assertEquals(12192000.0 / 6858000.0, pres.width.toDouble() / pres.height, 0.01)
    }

    @Test fun boxesArePlaced() {
        val title = pres.slides[0].shapes.first { it.paragraphs.any { p -> p.text == "Garden Plan" } }
        assertTrue(title.w > 0 && title.h > 0)
        val box = pres.slides[0].shapes.first { it.paragraphs.any { p -> p.text == "Start in May" } }
        assertTrue(box.x > pres.width / 2)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.PPT, DocumentTypes.detect("a.ppt", null).kind)
        assertEquals(DocumentKind.PPT, DocumentTypes.detect("a.pps", null).kind)
    }
}
