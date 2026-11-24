package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.PdfSelection
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer.PdfText
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextTest {
    @Test fun cleanJoinsHyphenatedLinesAndNormalizesBreaks() {
        assertEquals("garden\nbeds", PdfText.clean("gar\u0002\r\nden\r\nbeds"))
        assertEquals("a\nb", PdfText.clean("a\rb"))
    }

    @Test fun wordAround() {
        val s = "Plant the tomatoes early"
        assertEquals(10 to 17, PdfText.wordAt(s, 12))
        assertEquals(0 to 4, PdfText.wordAt(s, 0))
    }

    @Test fun selectionOrder() {
        val sel = PdfSelection(2, anchor = 20, focus = 5)
        assertEquals(5, sel.start); assertEquals(20, sel.end)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.PDF, DocumentTypes.detect("a.pdf", null).kind)
        assertEquals(DocumentKind.PDF, DocumentTypes.detect("x", "application/pdf").kind)
    }
}
