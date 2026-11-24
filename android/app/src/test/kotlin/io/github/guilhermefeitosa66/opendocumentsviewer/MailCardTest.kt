package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.document.Decoded
import io.github.guilhermefeitosa66.opendocumentsviewer.document.TextDecoder
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CardModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MailModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class MailCardTest {
    private fun text(name: String): Decoded.Text = TextDecoder.decode(javaClass.getResource("/samples/$name")!!.readBytes()) as Decoded.Text
    private val mailLabels = MailModel.Labels("From", "To", "Cc", "Date", "Attachments")

    @Test fun multipartMail() {
        val d = text("sample.eml")
        val m = MailModel.parse(d.text, d.charset)
        assertEquals("Receitas da horta and more", m.header("Subject"))
        assertEquals("João Horta <joao@example.org>", m.header("From"))
        assertTrue(m.html!!.contains("Olá <b>Ana</b>"))
        // The inline picture is embedded; the script never reaches the blocks.
        assertTrue(m.html!!.contains("data:image/png;base64,"))
        assertEquals(1, m.attachments.size)
        assertEquals("horta notas.pdf", m.attachments[0].name)
        assertTrue(String(m.attachments[0].bytes).startsWith("%PDF-1.4"))
        val blocks = MailModel.blocks(m, mailLabels)
        assertEquals("Receitas da horta and more", (blocks[0] as MdBlock.Heading).content.text)
        assertTrue(blocks.any { it is MdBlock.Image && it.src.startsWith("data:image/png") })
        assertFalse(blocks.any { it.plainText.contains("alert") })
        val att = blocks.filterIsInstance<MdBlock.ListItem>().single()
        assertEquals("attachment:0", att.content.spans.single { it.url != null }.url)
    }

    @Test fun plainLatin1Mail() {
        val d = text("plain.eml")
        val m = MailModel.parse(d.text, d.charset)
        assertEquals(null, m.html)
        val blocks = MailModel.blocks(m, mailLabels)
        val paras = blocks.filterIsInstance<MdBlock.Paragraph>()
        assertTrue(paras.any { it.content.text == "Olá, first paragraph." })
        val quote = paras.single { it.quoteDepth == 1 }
        assertEquals("quoted line\nanother", quote.content.text)
    }

    @Test fun encodedWordsAndParams() {
        assertEquals("Olá mundo", MailModel.decodeWords("=?ISO-8859-1?Q?Ol=E1?= =?UTF-8?Q?_mundo?="))
        assertEquals("a b.txt", MailModel.params("attachment; filename=\"a b.txt\"")["filename"])
        assertEquals("ç.txt", MailModel.params("attachment; filename*=UTF-8''%C3%A7.txt")["filename"])
    }

    @Test fun vcards() {
        val blocks = CardModel.vcardBlocks(text("sample.vcf").text, CardModel.CardLabels("Phone", "Email", "Address", "Org", "Title", "Birthday", "Web", "Note", "No name"))
        val names = blocks.filterIsInstance<MdBlock.Heading>().map { it.content.text }
        assertEquals(listOf("Ana Souza", "José Araújo"), names)
        val all = blocks.joinToString("\n") { it.plainText }
        assertTrue(all, all.contains("Phone (cell)  +55 11 91234-5678"))
        assertTrue(all.contains("Rua das Flores, 10, São Paulo SP 01000-000, Brazil"))
        assertTrue(all.contains("Org  Garden Club · Seeds"))
        assertTrue(all.contains("Likes tomatoes\nand basil"))
        assertTrue(blocks.any { it is MdBlock.Image })
        val tel = blocks.filterIsInstance<MdBlock.Paragraph>().first { it.plainText.startsWith("Phone") }
        assertEquals("tel:+5511912345678", tel.content.spans.single { it.url != null }.url)
    }

    @Test fun calendar() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val events = CardModel.events(text("sample.ics").text, zone)
        assertEquals(listOf("Call", "Planting day", "Buy compost", "Harvest week"), events.map { it.summary })
        val call = events[0]
        assertEquals(12, call.start!!.hour) // 15:00 UTC = 12:00 in São Paulo
        assertTrue(events.single { it.summary == "Harvest week" }.allDay)
        val blocks = CardModel.icsBlocks(text("sample.ics").text, CardModel.CalLabels("Where", "Organizer", "Repeats", "task", "(no title)", "all day"), zone, Locale.US)
        val all = blocks.joinToString("\n") { it.plainText }
        assertEquals("Garden", (blocks[0] as MdBlock.Heading).content.text)
        assertTrue(all, all.contains("Where  North bed, back yard"))
        assertTrue(all.contains("Organizer  Ana"))
        assertTrue(all.contains("Buy compost  · task"))
        assertTrue(all.contains("Saturday, November 28, 2026, 9:00"))
        assertTrue(all.contains("December 5, 2026 · all day"))
        assertFalse(all.contains("Reminder")) // VALARM is not an event
    }

    @Test fun detection() {
        assertEquals(DocumentKind.EMAIL, DocumentTypes.detect("a.eml", null).kind)
        assertEquals(DocumentKind.VCARD, DocumentTypes.detect("a.vcf", null).kind)
        assertEquals(DocumentKind.ICALENDAR, DocumentTypes.detect("x", "text/calendar").kind)
    }
}
