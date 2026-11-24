package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.FileTransfer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileTransferTest {
    @Test
    fun mimeFromExtensionWhenProviderIsGeneric() {
        assertEquals("text/markdown", FileTransfer.mimeFor("README.md", "application/octet-stream"))
        assertEquals("application/json", FileTransfer.mimeFor("a.json", null))
        assertEquals("text/plain", FileTransfer.mimeFor("Main.kt", null))
        assertEquals("text/plain", FileTransfer.mimeFor(".env", null))
        assertEquals("application/octet-stream", FileTransfer.mimeFor("data.bin", null))
    }

    @Test
    fun providerTypeWins() {
        assertEquals("text/x-kotlin", FileTransfer.mimeFor("Main.kt", "text/x-kotlin; charset=utf-8"))
    }

    @Test
    fun copyStreamsAllBytesAndReportsProgress() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val seen = ArrayList<Float>()
        FileTransfer.copyStream(ByteArrayInputStream(data), out, data.size.toLong()) { seen += it }
        assertArrayEquals(data, out.toByteArray())
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun unknownSizeIsIndeterminate() {
        val seen = ArrayList<Float>()
        FileTransfer.copyStream(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), -1) { seen += it }
        assertTrue(seen.all { it == -1f })
    }
}
