package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.EpubModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.Fb2Model
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.HtmlModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookTest {
    private fun bytes(name: String) = javaClass.getResource("/samples/$name")!!.readBytes()

    @Test fun epub3ChaptersTocImagesAndLinks() {
        val raw = bytes("book3.epub")
        val book = EpubModel.open(raw)
        assertEquals("The Garden Book", book.title)
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch2.xhtml"), book.chapters.map { it.path })
        val p = EpubModel.parse(book)
        val headings = p.blocks.filterIsInstance<MdBlock.Heading>().map { it.content.text }
        assertEquals(listOf("Seeds", "Water", "Morning"), headings)
        assertEquals(listOf("Seeds", "Water", "Morning"), p.toc.map { it.first.title })
        assertEquals(listOf(1, 1, 2), p.toc.map { it.first.level })
        // The TOC points at the right blocks.
        p.toc.forEach { (e, i) -> assertEquals(e.title, (p.blocks[i] as MdBlock.Heading).content.text) }
        // Images point into the book and can be read from it.
        val img = p.blocks.filterIsInstance<MdBlock.Image>().single()
        assertEquals("zip:OEBPS/images/leaf.png", img.src)
        val png = EpubModel.resource(raw, img.src.removePrefix(EpubModel.IMAGE_SCHEME))!!
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte()), png.copyOf(2))
        // A link to another chapter's id becomes an anchor of this document.
        val link = p.blocks.filterIsInstance<MdBlock.Paragraph>().first { it.content.text.contains("watering") }
        val target = link.content.spans.mapNotNull { it.url }.single()
        assertEquals("#OEBPS/text/ch2.xhtml#morning", target)
        assertEquals("Morning", (p.blocks[p.anchors[target.removePrefix("#")]!!] as MdBlock.Heading).content.text)
        // Source: the chapters' XHTML.
        assertTrue(EpubModel.sourceText(book).contains("<h2 id=\"morning\">"))
    }

    @Test fun epub2WithNcx() {
        val p = EpubModel.parse(EpubModel.open(bytes("book2.epub")))
        assertEquals(listOf("First" to 1, "Second" to 1, "Inner" to 2), p.toc.map { it.first.title to it.first.level })
        val inner = p.toc.first { it.first.title == "Inner" }.second
        assertEquals("Inner part.", (p.blocks[inner] as MdBlock.Paragraph).content.text)
    }

    @Test(expected = EpubModel.Encrypted::class) fun drmIsRefused() {
        EpubModel.open(bytes("drm.epub"))
    }

    @Test(expected = EpubModel.NotEpub::class) fun notAZip() {
        EpubModel.open("hello".toByteArray())
    }

    @Test fun resolvePaths() {
        assertEquals("OEBPS/images/a b.png", EpubModel.resolve("OEBPS/text", "../images/a%20b.png"))
        assertEquals("x.html", EpubModel.resolve("", "./x.html"))
    }

    @Test fun fb2() {
        val html = Fb2Model.toHtml(String(bytes("book.fb2"), Charsets.UTF_8))
        val r = HtmlModel.parse(org.jsoup.Jsoup.parse(html), useTitle = false)
        val headings = r.blocks.filterIsInstance<MdBlock.Heading>().map { it.level to it.content.text }
        assertEquals(listOf(1 to "Small Plots", 2 to "Chapter 1", 3 to "Part A"), headings)
        assertTrue(r.blocks.any { it is MdBlock.Image && it.src.startsWith("data:image/png;base64,") })
        assertTrue(r.blocks.any { it is MdBlock.Paragraph && it.content.text == "Ada Gardener" })
        assertTrue(r.blocks.any { it is MdBlock.Paragraph && it.content.text == "Line one" })
    }

    @Test fun detection() {
        assertEquals(DocumentKind.EPUB, DocumentTypes.detect("x.epub", null).kind)
        assertEquals(DocumentKind.EPUB, DocumentTypes.detect("x", "application/epub+zip").kind)
        assertEquals(DocumentKind.FB2, DocumentTypes.detect("x.fb2", null).kind)
    }
}
