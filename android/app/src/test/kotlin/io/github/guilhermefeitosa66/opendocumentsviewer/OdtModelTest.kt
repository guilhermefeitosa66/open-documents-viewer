package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.BlockAlign
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.OdtModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdtModelTest {
    private val r = OdtModel.parse(javaClass.getResource("/samples/sample.odt")!!.readBytes())
    private val blocks = r.blocks

    @Test fun structure() {
        assertEquals("Header line", blocks.first().plainText)
        assertEquals("Footer line", blocks.last().plainText)
        val h = blocks.filterIsInstance<MdBlock.Heading>().map { it.level to it.content.text }
        assertEquals(listOf(1 to "Overview", 2 to "Beds"), h)
        val title = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text == "Garden Notes" }
        assertEquals(BlockAlign.CENTER, title.align)
        assertFalse(r.plainText.contains("removed text"))
    }

    @Test fun formatting() {
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.startsWith("Bold") }
        assertEquals("Bold blue italic under big gone mono(), two  spaces\ttab.", p.content.text)
        fun styled(s: InlineStyle) = p.content.spans.filter { it.style == s }.map { p.content.text.substring(it.start, it.end) }
        assertEquals(listOf("Bold"), styled(InlineStyle.BOLD))
        assertEquals(listOf("blue italic"), styled(InlineStyle.ITALIC))
        assertEquals(listOf("blue italic"), styled(InlineStyle.COLOR))
        assertEquals(listOf("under"), styled(InlineStyle.UNDERLINE))
        assertEquals(listOf("big gone"), styled(InlineStyle.STRIKE))
        assertEquals(listOf("mono()"), styled(InlineStyle.CODE))
        assertEquals(1.5f, p.content.spans.single { it.style == InlineStyle.SIZE }.scale, 0.01f)
    }

    @Test fun linksNotesBookmarks() {
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.startsWith("Right side") }
        assertEquals(BlockAlign.END, p.align)
        assertEquals(listOf("https://example.org", "#Beds", "#fn-1"), p.content.spans.mapNotNull { it.url })
        assertEquals("Beds", (blocks[r.anchors["Beds"]!!] as MdBlock.Heading).content.text)
        assertEquals("1. Since 2020.", blocks[r.anchors["fn-1"]!!].plainText)
    }

    @Test fun listsPictureTableBreak() {
        val items = blocks.filterIsInstance<MdBlock.ListItem>().map { Triple(it.marker, it.content.text, it.indent) }
        assertEquals(listOf(Triple("1.", "One", 0), Triple("a)", "Sub", 1), Triple("2.", "Two", 0), Triple("•", "Dot", 0)), items)
        val img = blocks.filterIsInstance<MdBlock.Image>().single()
        assertEquals("zip:Pictures/leaf.png", img.src)
        assertEquals("Leaf", img.alt)
        val t = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Bed", "Plant", "Sun"), t.header.map { it.text })
        assertEquals(listOf("North wide", "", "Full"), t.rows[0].map { it.text })
        val beds = blocks.indexOfFirst { it is MdBlock.Heading && it.content.text == "Beds" }
        assertTrue(blocks[beds - 1] is MdBlock.Rule)
    }

    @Test fun detection() {
        assertEquals(DocumentKind.ODT, DocumentTypes.detect("a.odt", null).kind)
        assertEquals(DocumentKind.ODT, DocumentTypes.detect("a.fodt", null).kind)
    }
}
