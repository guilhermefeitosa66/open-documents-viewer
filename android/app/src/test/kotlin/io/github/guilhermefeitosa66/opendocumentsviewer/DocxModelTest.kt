package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.BlockAlign
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocxModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxModelTest {
    private val r = DocxModel.parse(javaClass.getResource("/samples/sample.docx")!!.readBytes())
    private val blocks = r.blocks

    @Test fun headerBodyFooterOrder() {
        assertEquals("Header text", blocks.first().plainText)
        assertEquals("Page footer", blocks.last().plainText)
    }

    @Test fun headings() {
        val h = blocks.filterIsInstance<MdBlock.Heading>().map { it.level to it.content.text }
        assertEquals(listOf(1 to "Garden Report", 1 to "Summary", 2 to "Beds"), h)
    }

    @Test fun runFormatting() {
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.startsWith("Bold") }
        assertEquals(BlockAlign.CENTER, p.align)
        assertEquals("Bold and italic, under gone big red code()", p.content.text)
        fun styled(style: InlineStyle) = p.content.spans.filter { it.style == style }.map { p.content.text.substring(it.start, it.end) }
        assertEquals(listOf("Bold"), styled(InlineStyle.BOLD))
        assertEquals(listOf("italic"), styled(InlineStyle.ITALIC))
        assertEquals(listOf("under"), styled(InlineStyle.UNDERLINE))
        assertEquals(listOf("gone"), styled(InlineStyle.STRIKE))
        assertEquals(listOf("big red"), styled(InlineStyle.COLOR))
        assertEquals(listOf("code()"), styled(InlineStyle.CODE))
        // 18 pt against an 11 pt body.
        val size = p.content.spans.single { it.style == InlineStyle.SIZE }
        assertEquals(36f / 22f, size.scale, 0.01f)
        assertFalse(p.content.text.contains("deleted"))
    }

    @Test fun linksBookmarksAndNotes() {
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.startsWith("Visit") }
        val urls = p.content.spans.mapNotNull { it.url }
        assertEquals(listOf("https://example.org/garden", "#beds", "https://example.org/f", "#fn-1"), urls)
        assertEquals("Beds", (blocks[r.anchors["beds"]!!] as MdBlock.Heading).content.text)
        assertTrue(blocks[r.anchors["fn-1"]!!].plainText == "1. Grown since 2020.")
    }

    @Test fun lists() {
        val items = blocks.filterIsInstance<MdBlock.ListItem>().map { Triple(it.marker, it.content.text, it.indent) }
        assertEquals(
            listOf(Triple("1.", "First", 0), Triple("a)", "Sub a", 1), Triple("b)", "Sub b", 1), Triple("2.", "Second", 0), Triple("•", "Dot", 0)),
            items,
        )
    }

    @Test fun pictureTableAndBreak() {
        val img = blocks.filterIsInstance<MdBlock.Image>().single()
        assertEquals("zip:word/media/leaf.png", img.src)
        assertEquals("A leaf", img.alt)
        val t = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Bed", "Plants", "Sun"), t.header.map { it.text })
        assertEquals(listOf("North", "Tomato\nBasil", "Full"), t.rows[0].map { it.text })
        assertEquals(listOf("", "Pepper (both)", ""), t.rows[1].map { it.text })
        // The page break became a rule just before "Beds".
        val beds = blocks.indexOfFirst { it is MdBlock.Heading && it.content.text == "Beds" }
        assertTrue(blocks[beds - 1] is MdBlock.Rule)
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "Quoted line" && it.content.spans.any { s -> s.style == InlineStyle.ITALIC } })
    }

    @Test fun plainTextAndDetection() {
        assertTrue(r.plainText.contains("Garden Report"))
        assertEquals(DocumentKind.DOCX, DocumentTypes.detect("a.docx", null).kind)
    }

    @Test(expected = DocxModel.NotDocx::class) fun notAZip() {
        DocxModel.parse("hello".toByteArray())
    }
}
