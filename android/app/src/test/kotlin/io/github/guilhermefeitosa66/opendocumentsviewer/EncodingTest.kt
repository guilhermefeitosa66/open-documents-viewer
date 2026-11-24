package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.Decoded
import io.github.guilhermefeitosa66.opendocumentsviewer.document.TextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EncodingTest {
    private fun text(d: Decoded) = d as Decoded.Text

    @Test fun utf16WithoutBomIsDetected() {
        val s = "Olá, mundo\nsecond line"
        val le = text(TextDecoder.decode(s.toByteArray(Charsets.UTF_16LE)))
        assertEquals("UTF-16LE", le.charset); assertEquals(s, le.text)
        val be = text(TextDecoder.decode(s.toByteArray(Charsets.UTF_16BE)))
        assertEquals("UTF-16BE", be.charset); assertEquals(s, be.text)
    }

    @Test fun utf32WithBom() {
        val s = "﻿hello ✓"
        val le = text(TextDecoder.decode(s.toByteArray(Charset.forName("UTF-32LE"))))
        assertEquals("UTF-32LE", le.charset); assertEquals("hello ✓", le.text)
        val be = text(TextDecoder.decode(s.toByteArray(Charset.forName("UTF-32BE"))))
        assertEquals("UTF-32BE", be.charset); assertEquals("hello ✓", be.text)
    }

    @Test fun utf16BomStillWins() {
        val s = "﻿abc"
        assertEquals("abc", text(TextDecoder.decode(s.toByteArray(Charsets.UTF_16LE))).text)
    }

    @Test fun binaryStaysBinary() {
        val bytes = ByteArray(600) { if (it % 7 == 0) 0 else (it * 31).toByte() }
        assertTrue(TextDecoder.decode(bytes) is Decoded.Binary)
    }

    @Test fun latinFallsBackToWindows1252() {
        val d = text(TextDecoder.decode("ação".toByteArray(Charset.forName("windows-1252"))))
        assertEquals("windows-1252", d.charset); assertEquals("ação", d.text)
    }

    @Test fun decodeAsUsesThePickedEncoding() {
        val cyr = "Привет".toByteArray(Charset.forName("windows-1251"))
        assertEquals("windows-1252", text(TextDecoder.decode(cyr)).charset)
        val d = text(TextDecoder.decodeAs(cyr, cyr.size, "windows-1251"))
        assertEquals("Привет", d.text); assertEquals("windows-1251", d.charset)
        val sjis = "日本語".toByteArray(Charset.forName("Shift_JIS"))
        assertEquals("日本語", text(TextDecoder.decodeAs(sjis, sjis.size, "Shift_JIS")).text)
        // The picked encoding's byte order mark is skipped.
        val bom = "﻿ok".toByteArray(Charsets.UTF_8)
        assertEquals("ok", text(TextDecoder.decodeAs(bom, bom.size, "UTF-8")).text)
        // Unknown names fall back to detection.
        assertEquals("UTF-8", text(TextDecoder.decodeAs("x".toByteArray(), 1, "no-such-charset")).charset)
    }

    @Test fun choicesAreSupportedAndIncludeTheCommonOnes() {
        val c = TextDecoder.CHOICES
        assertTrue(c.containsAll(listOf("UTF-8", "UTF-16LE", "windows-1252", "ISO-8859-1", "windows-1251", "Shift_JIS")))
        c.forEach { Charset.forName(it) }
    }

    @Test fun lineEndings() {
        assertEquals("LF", TextDecoder.lineEndings("a\nb\n"))
        assertEquals("CRLF", TextDecoder.lineEndings("a\r\nb\r\n"))
        assertEquals("CR", TextDecoder.lineEndings("a\rb\r"))
        assertEquals("Mixed", TextDecoder.lineEndings("a\r\nb\nc"))
        assertEquals("", TextDecoder.lineEndings("one line"))
    }
}
