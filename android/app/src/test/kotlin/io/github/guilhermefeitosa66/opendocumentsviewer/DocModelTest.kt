package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Cfb
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocModelTest {
    private val bytes = javaClass.getResource("/samples/sample.doc")!!.readBytes()

    @Test fun containerStreams() {
        val cfb = Cfb.open(bytes)
        assertTrue(cfb.has("WordDocument"))
        assertTrue(cfb.has("1Table") || cfb.has("0Table"))
    }

    @Test fun textTableAndBreaks() {
        val r = DocModel.parse(bytes)
        assertTrue(r.partial)
        val text = r.plainText
        for (s in listOf("Garden Report", "Summary", "Bold and italic", "First", "Second", "Quoted line")) assertTrue(s, text.contains(s))
        // Field instructions are dropped, their result kept.
        assertFalse(text.contains("HYPERLINK"))
        assertTrue(text.contains("link"))
        val t = r.blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Bed", "Plants", "Sun"), t.header.map { it.text })
        assertEquals(listOf("North", "Tomato\nBasil", "Full"), t.rows[0].map { it.text })
    }

    @Test fun fieldsAreStripped() {
        assertEquals("before link after", DocModel.stripFields("before \u0013 HYPERLINK \"x\" \u0014link\u0015 after"))
        assertEquals("a b", DocModel.stripFields("a \u0013outer \u0013inner\u0014x\u0015\u0014b\u0015"))
    }

    @Test(expected = DocModel.NotDoc::class) fun notOle() {
        DocModel.parse(ByteArray(600))
    }

    @Test fun detection() {
        assertEquals(DocumentKind.DOC, DocumentTypes.detect("a.doc", null).kind)
        assertEquals(DocumentKind.DOC, DocumentTypes.detect("x", "application/msword").kind)
    }
}
