package io.github.guilhermefeitosa66.opendocumentsviewer.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** The result of turning a file's bytes into text. */
sealed interface Decoded {
    data class Text(val text: String, val charset: String) : Decoded
    /** The bytes do not look like text (NUL bytes outside a UTF-16 file). */
    data object Binary : Decoded
}

/**
 * Detects the encoding of a text file and decodes it.
 *
 * Order: byte order mark, then strict UTF-8, then windows-1252 (which maps every byte, so it
 * never fails and is what legacy Latin text most often is).
 */
object TextDecoder {
    private const val SNIFF_BYTES = 8192

    fun decode(bytes: ByteArray, length: Int = bytes.size): Decoded {
        // Byte order marks.
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded.Text(String(bytes, 3, length - 3, Charsets.UTF_8), "UTF-8")
        }
        if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded.Text(String(bytes, 2, length - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded.Text(String(bytes, 2, length - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        if (looksBinary(bytes, length)) return Decoded.Binary

        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Decoded.Text(utf8.decode(ByteBuffer.wrap(bytes, 0, length)).toString(), "UTF-8")
        } catch (_: CharacterCodingException) {
            // A file cut in the middle of a multi-byte sequence is still UTF-8.
            val trimmed = trimIncompleteUtf8Tail(bytes, length)
            if (trimmed < length) {
                try {
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    return Decoded.Text(decoder.decode(ByteBuffer.wrap(bytes, 0, trimmed)).toString(), "UTF-8")
                } catch (_: CharacterCodingException) {
                    // Fall through.
                }
            }
            Decoded.Text(String(bytes, 0, length, Charset.forName("windows-1252")), "windows-1252")
        }
    }

    internal fun looksBinary(bytes: ByteArray, length: Int): Boolean {
        val end = minOf(length, SNIFF_BYTES)
        for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** Index where an incomplete trailing UTF-8 sequence starts, or [length] if there is none. */
    internal fun trimIncompleteUtf8Tail(bytes: ByteArray, length: Int): Int {
        var i = length - 1
        var continuation = 0
        while (i >= 0 && continuation < 3 && (bytes[i].toInt() and 0xC0) == 0x80) {
            i--; continuation++
        }
        if (i < 0) return length
        val lead = bytes[i].toInt() and 0xFF
        val needed = when {
            lead and 0xE0 == 0xC0 -> 1
            lead and 0xF0 == 0xE0 -> 2
            lead and 0xF8 == 0xF0 -> 3
            else -> return length
        }
        return if (continuation < needed) i else length
    }
}
