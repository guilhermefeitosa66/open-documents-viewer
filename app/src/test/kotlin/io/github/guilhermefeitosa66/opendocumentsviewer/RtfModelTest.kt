package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.RtfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtfModelTest {
    private val blocks = RtfModel.parse(javaClass.getResource("/samples/sample.rtf")!!.readText())

    private fun spanText(t: io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineText, style: InlineStyle) =
        t.spans.filter { it.style == style }.map { t.text.substring(it.start, it.end) }

    @Test
    fun bigBoldLineIsAHeading() {
        val h = blocks.first() as MdBlock.Heading
        assertEquals("Report title", h.content.text)
    }

    @Test
    fun inlineStyles() {
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.startsWith("Plain") }.content
        assertEquals("Plain text with bold, italic, underline, strike and colored words.", p.text)
        assertEquals(listOf("bold"), spanText(p, InlineStyle.BOLD))
        assertEquals(listOf("italic"), spanText(p, InlineStyle.ITALIC))
        assertEquals(listOf("underline"), spanText(p, InlineStyle.UNDERLINE))
        assertEquals(listOf("strike"), spanText(p, InlineStyle.STRIKE))
        val color = p.spans.single { it.style == InlineStyle.COLOR }
        assertEquals("colored", p.text.substring(color.start, color.end))
        assertEquals(0xFFB5502B.toInt(), color.color)
    }

    @Test
    fun escapesAndUnicode() {
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "Accents: ação and €uro." })
    }

    @Test
    fun listTableAndSkippedGroups() {
        val item = blocks.filterIsInstance<MdBlock.ListItem>().single()
        assertEquals("First item", item.content.text)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf(listOf("Name", "Qty"), listOf("Apple", "3")), table.rows.map { r -> r.map { it.text } })
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "After the table." })
        val all = blocks.joinToString { it.plainText }
        assertTrue("secret" !in all && "Helvetica" !in all && "Test 1.0" !in all && "89504e47" !in all)
        assertTrue(RtfModel.parseWithInfo(javaClass.getResource("/samples/sample.rtf")!!.readText()).second)
    }
}
