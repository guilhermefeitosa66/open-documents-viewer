package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.OdpReader
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.OoxmlCrypto
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.ZipFiles
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.DocxModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherOfficeTest {
    private fun bytes(n: String) = javaClass.getResource("/samples/$n")!!.readBytes()

    @Test fun encryptedDocxOpensWithItsPassword() {
        val enc = bytes("encrypted.docx")
        assertTrue(OoxmlCrypto.isEncryptedPackage(enc))
        assertFalse(OoxmlCrypto.isEncryptedPackage(bytes("sample.docx")))
        val plain = OoxmlCrypto.decrypt(enc, "horta123")
        assertTrue(ZipFiles.isZip(plain))
        assertTrue(plain.contentEquals(bytes("sample.docx")))
        assertTrue(DocxModel.parse(plain).plainText.contains("Garden Report"))
    }

    @Test(expected = OoxmlCrypto.WrongPassword::class) fun wrongPassword() {
        OoxmlCrypto.decrypt(bytes("encrypted.docx"), "nope")
    }

    @Test fun drawingPagesAsSlides() {
        val pres = OdpReader.read(bytes("sample.odg"))
        assertEquals(1, pres.slides.size)
        assertEquals("Plan", pres.slides[0].title)
        val kinds = pres.slides[0].shapes.map { it.kind }
        assertTrue(kinds.toString(), kinds.containsAll(listOf("rect", "ellipse", "line")))
        assertEquals(0xFFFFC000.toInt(), pres.slides[0].shapes.first { it.kind == "rect" }.fill)
        assertTrue(pres.plainText().contains("North bed"))
    }

    @Test fun detection() {
        assertEquals(DocumentKind.ODP, DocumentTypes.detect("a.odg", null).kind)
        assertEquals(DocumentKind.IWORK, DocumentTypes.detect("a.pages", null).kind)
        assertEquals(DocumentKind.IWORK, DocumentTypes.detect("a.key", null).kind)
        // Templates and macro-enabled files open as their base format.
        assertEquals(DocumentKind.DOCX, DocumentTypes.detect("a.dotx", null).kind)
        assertEquals(DocumentKind.DOCX, DocumentTypes.detect("a.docm", null).kind)
        assertEquals(DocumentKind.XLSX, DocumentTypes.detect("a.xltx", null).kind)
        assertEquals(DocumentKind.XLSX, DocumentTypes.detect("a.xlsm", null).kind)
        assertEquals(DocumentKind.PPTX, DocumentTypes.detect("a.pptm", null).kind)
        assertEquals(DocumentKind.ODT, DocumentTypes.detect("a.ott", null).kind)
        assertEquals(DocumentKind.ODS, DocumentTypes.detect("a.ots", null).kind)
        assertEquals(DocumentKind.ODP, DocumentTypes.detect("a.otp", null).kind)
        assertEquals(DocumentKind.ODT, DocumentTypes.detect("a.fodt", null).kind)
        assertEquals(DocumentKind.ODS, DocumentTypes.detect("a.fods", null).kind)
    }
}
