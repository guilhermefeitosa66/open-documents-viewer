package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CellAlign
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.HtmlModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlModelTest {
    private val html = javaClass.getResource("/samples/sample.html")!!.readText()
    private val result = HtmlModel.parse(html)
    private val blocks = result.blocks

    @Test
    fun headingsParagraphsAndInlineStyles() {
        val h = blocks.filterIsInstance<MdBlock.Heading>()
        assertEquals(listOf("Welcome", "Install"), h.map { it.content.text })
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first()
        assertEquals("Some bold, italic and code with a link.", p.content.text)
        assertTrue(p.content.spans.any { it.style == InlineStyle.LINK && it.url == "https://example.org" })
        assertTrue(p.content.spans.any { it.style == InlineStyle.CODE })
    }

    @Test
    fun listsWithTasksAndNesting() {
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals("First", items[0].content.text)
        assertEquals(true, items[1].checked)
        assertEquals("Done task", items[1].content.text)
        assertEquals("Parent", items[2].content.text)
        assertEquals("3.", items[3].marker)
        assertEquals(1, items[3].indent)
    }

    @Test
    fun codeTableQuoteImageAndRule() {
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.info)
        assertEquals("val x = 1\nprintln(x)", code.code)
        val t = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Name", "Qty"), t.header.map { it.text })
        assertEquals(CellAlign.END, t.align[1])
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.quoteDepth == 1 && it.content.text == "Quoted text" })
        assertEquals(MdBlock.Image("img/logo.png", "Logo", 0, 0), blocks.filterIsInstance<MdBlock.Image>().single())
        assertTrue(blocks.any { it is MdBlock.Rule })
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "Loose text in a div\nsecond line" })
    }

    @Test
    fun scriptsStylesAndFramesAreDropped() {
        val all = blocks.joinToString("\n") { it.plainText }
        assertTrue("alert" !in all && "color:red" !in all && "evil" !in all)
    }

    @Test
    fun idsBecomeAnchors() {
        assertEquals(blocks.indexOfFirst { it is MdBlock.Heading && it.content.text == "Install" }, result.ids["install"])
    }
}
