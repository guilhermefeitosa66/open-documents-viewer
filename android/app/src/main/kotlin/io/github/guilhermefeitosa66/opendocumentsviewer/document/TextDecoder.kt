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
 * Order: byte order mark (UTF-8, UTF-16, UTF-32), then UTF-16 without a mark (recognized by its
 * zero bytes), then strict UTF-8, then windows-1252 (which maps every byte, so it never fails and is
 * what legacy Latin text most often is). [decodeAs] decodes with an encoding the user picked.
 */
object TextDecoder {
    private const val SNIFF_BYTES = 8192

    /** Encodings offered in "Encoding…", the ones this device supports. Names are Java charset names. */
    val CHOICES: List<String> by lazy {
        listOf(
            "UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE",
            "windows-1252", "ISO-8859-1", "ISO-8859-15", "x-MacRoman", "IBM437", "IBM850",
            "windows-1250", "ISO-8859-2", "windows-1251", "KOI8-R", "KOI8-U", "ISO-8859-5",
            "windows-1253", "ISO-8859-7", "windows-1254", "ISO-8859-9", "windows-1255", "ISO-8859-8",
            "windows-1256", "ISO-8859-6", "windows-1257", "ISO-8859-13", "windows-1258", "TIS-620",
            "Shift_JIS", "EUC-JP", "ISO-2022-JP", "GB18030", "GBK", "Big5", "EUC-KR",
        ).filter { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
    }

    /** Decodes with [charsetName] whatever the bytes look like; a byte order mark of that encoding is skipped. */
    fun decodeAs(bytes: ByteArray, length: Int, charsetName: String): Decoded {
        val cs = runCatching { Charset.forName(charsetName) }.getOrNull() ?: return decode(bytes, length)
        val skip = when (cs.name()) {
            "UTF-8" -> if (hasUtf8Bom(bytes, length)) 3 else 0
            "UTF-16LE" -> if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
            "UTF-16BE" -> if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) 2 else 0
            "UTF-32LE" -> if (length >= 4 && bom32(bytes, length) == "UTF-32LE") 4 else 0
            "UTF-32BE" -> if (length >= 4 && bom32(bytes, length) == "UTF-32BE") 4 else 0
            else -> 0
        }
        return Decoded.Text(String(bytes, skip, length - skip, cs), cs.name())
    }

    private fun hasUtf8Bom(bytes: ByteArray, length: Int) =
        length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    private fun bom32(bytes: ByteArray, length: Int): String? {
        if (length < 4) return null
        val b = IntArray(4) { bytes[it].toInt() and 0xFF }
        return when {
            b[0] == 0xFF && b[1] == 0xFE && b[2] == 0 && b[3] == 0 -> "UTF-32LE"
            b[0] == 0 && b[1] == 0 && b[2] == 0xFE && b[3] == 0xFF -> "UTF-32BE"
            else -> null
        }
    }

    /**
     * UTF-16 without a byte order mark: in Latin text every other byte is zero. Looks at the start
     * of the file; null when the zero bytes do not line up (then they mean binary).
     */
    internal fun utf16WithoutBom(bytes: ByteArray, length: Int): String? {
        val end = minOf(length, SNIFF_BYTES) and 1.inv()
        if (end < 4) return null
        var zeroEven = 0
        var zeroOdd = 0
        var i = 0
        while (i < end) {
            if (bytes[i] == 0.toByte()) zeroEven++
            if (bytes[i + 1] == 0.toByte()) zeroOdd++
            i += 2
        }
        val pairs = end / 2
        return when {
            zeroOdd * 10 >= pairs * 4 && zeroEven * 20 <= pairs -> "UTF-16LE"
            zeroEven * 10 >= pairs * 4 && zeroOdd * 20 <= pairs -> "UTF-16BE"
            else -> null
        }
    }

    fun decode(bytes: ByteArray, length: Int = bytes.size): Decoded {
        // Byte order marks (UTF-32 before UTF-16: FF FE 00 00 starts with the UTF-16LE mark).
        bom32(bytes, length)?.let { cs ->
            return Decoded.Text(String(bytes, 4, length - 4, Charset.forName(cs)), cs)
        }
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Decoded.Text(String(bytes, 3, length - 3, Charsets.UTF_8), "UTF-8")
        }
        if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded.Text(String(bytes, 2, length - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded.Text(String(bytes, 2, length - 2, Charsets.UTF_16BE), "UTF-16BE")
        }
        if (looksBinary(bytes, length)) {
            val utf16 = utf16WithoutBom(bytes, length) ?: return Decoded.Binary
            return Decoded.Text(String(bytes, 0, length and 1.inv(), Charset.forName(utf16)), utf16)
        }

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

    /** "LF", "CRLF", "CR", "Mixed" or "" (a single line), for the file information. */
    fun lineEndings(text: CharSequence): String {
        var lf = 0
        var crlf = 0
        var cr = 0
        var i = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> if (i + 1 < n && text[i + 1] == '\n') { crlf++; i++ } else cr++
                '\n' -> lf++
            }
            i++
        }
        val kinds = listOf("LF" to lf, "CRLF" to crlf, "CR" to cr).filter { it.second > 0 }
        return when (kinds.size) {
            0 -> ""
            1 -> kinds[0].first
            else -> "Mixed"
        }
    }
}
