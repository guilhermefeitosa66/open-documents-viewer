package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.NotebookModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookModelTest {
    private val nb = NotebookModel.parse(javaClass.getResource("/samples/sample.ipynb")!!.readText())
    private val blocks = nb.blocks

    @Test
    fun markdownAndCodeCells() {
        assertEquals("Analysis", (blocks[0] as MdBlock.Heading).content.text)
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>()
        assertEquals("python", code[0].info)
        assertEquals("import math\nprint(math.pi)", code[0].code)
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "In [1]:" })
        assertTrue(blocks.any { it is MdBlock.Paragraph && it.content.text == "In [ ]:" })
    }

    @Test
    fun outputs() {
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>()
        assertTrue(code.any { it.info == "output" && it.code == "3.141592653589793" })
        assertTrue(code.any { it.info == "error" && it.code == "ZeroDivisionError: division by zero" })
        assertTrue(blocks.any { it is MdBlock.Table })
        val img = blocks.filterIsInstance<MdBlock.Image>().single()
        assertTrue(img.src.startsWith("data:image/png;base64,iVBOR"))
        // The widget output cannot be shown.
        assertTrue(nb.partial)
    }
}
