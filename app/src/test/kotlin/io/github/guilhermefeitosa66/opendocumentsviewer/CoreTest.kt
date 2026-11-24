package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.Decoded
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentKind
import io.github.guilhermefeitosa66.opendocumentsviewer.document.DocumentTypes
import io.github.guilhermefeitosa66.opendocumentsviewer.document.LineIndex
import io.github.guilhermefeitosa66.opendocumentsviewer.document.TextDecoder
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Delimited
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Dotenv
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.EnvLine
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.JsonFormatter
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentEntry
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.RecentsCodec
import io.github.guilhermefeitosa66.opendocumentsviewer.recents.ViewPosition
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.search.SearchOptions
import io.github.guilhermefeitosa66.opendocumentsviewer.search.SearchResult
import io.github.guilhermefeitosa66.opendocumentsviewer.search.TextSearch
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecoderTest {
    @Test
    fun utf8() {
        val d = TextDecoder.decode("olá, ação".toByteArray()) as Decoded.Text
        assertEquals("olá, ação", d.text)
        assertEquals("UTF-8", d.charset)
    }

    @Test
    fun bomIsDropped() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        assertEquals("hi", (TextDecoder.decode(bytes) as Decoded.Text).text)
    }

    @Test
    fun latin1FallsBackToWindows1252() {
        val bytes = "ação".toByteArray(Charsets.ISO_8859_1)
        val d = TextDecoder.decode(bytes) as Decoded.Text
        assertEquals("ação", d.text)
        assertEquals("windows-1252", d.charset)
    }

    @Test
    fun utf16WithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ok".toByteArray(Charsets.UTF_16LE)
        assertEquals("ok", (TextDecoder.decode(bytes) as Decoded.Text).text)
    }

    @Test
    fun nulBytesAreBinary() {
        assertEquals(Decoded.Binary, TextDecoder.decode(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)))
    }

    @Test
    fun truncatedMultibyteTailStaysUtf8() {
        val full = "aé".toByteArray()
        val d = TextDecoder.decode(full, full.size - 1) as Decoded.Text
        assertEquals("UTF-8", d.charset)
        assertEquals("a", d.text)
    }
}

class LineIndexTest {
    @Test
    fun splitsLinesWithAnyBreak() {
        val text = "a\nbb\r\nccc\rd"
        val idx = LineIndex.build(text)
        assertEquals(4, idx.rowCount)
        assertEquals(listOf("a", "bb", "ccc", "d"), (0 until idx.rowCount).map { idx.text(text, it) })
        assertEquals(4, idx.lineCount)
        assertEquals(3, idx.longestRow)
    }

    @Test
    fun trailingBreakGivesEmptyLastRow() {
        val idx = LineIndex.build("a\n")
        assertEquals(2, idx.rowCount)
    }

    @Test
    fun emptyTextHasOneRow() {
        assertEquals(1, LineIndex.build("").rowCount)
    }

    @Test
    fun longLinesAreSplitIntoRows() {
        val text = "x".repeat(25) + "\nend"
        val idx = LineIndex.build(text, maxRowLength = 10)
        assertEquals(4, idx.rowCount)
        assertEquals(1, idx.line(2))
        assertTrue(idx.isContinuation(1))
        assertFalse(idx.isContinuation(3))
        assertEquals(2, idx.line(3))
        assertEquals(2, idx.rowOf(text.indexOf("end") + 1).let { idx.line(it) })
    }
}

class DocumentTypesTest {
    @Test
    fun detectsByName() {
        assertEquals(DocumentKind.MARKDOWN, DocumentTypes.detect("README.md", null).kind)
        assertEquals(DocumentKind.DOTENV, DocumentTypes.detect(".env", null).kind)
        assertEquals(DocumentKind.DOTENV, DocumentTypes.detect(".env.production", null).kind)
        assertEquals(DocumentKind.JSON, DocumentTypes.detect("package.json", "application/octet-stream").kind)
        assertEquals(DocumentKind.CSV, DocumentTypes.detect("data.csv", null).kind)
        assertEquals(Languages.KOTLIN, DocumentTypes.detect("Main.kt", null).language)
        assertEquals(Languages.KOTLIN, DocumentTypes.detect("build.gradle.kts", null).language)
        assertEquals(Languages.DOCKERFILE, DocumentTypes.detect("Dockerfile", null).language)
        assertEquals(Languages.MAKEFILE, DocumentTypes.detect("Makefile", null).language)
        assertEquals(Languages.YAML, DocumentTypes.detect("ci.yml", null).language)
        assertEquals(DocumentKind.PLAIN, DocumentTypes.detect("notes.txt", null).kind)
    }

    @Test
    fun imagesAndSvg() {
        assertEquals(DocumentKind.IMAGE, DocumentTypes.detect("photo.JPG", null).kind)
        assertEquals(DocumentKind.IMAGE, DocumentTypes.detect("anim.gif", null).kind)
        assertEquals(DocumentKind.IMAGE, DocumentTypes.detect("x", "image/heic").kind)
        assertEquals(DocumentKind.SVG, DocumentTypes.detect("icon.svg", null).kind)
        assertEquals(DocumentKind.SVG, DocumentTypes.detect("x", "image/svg+xml").kind)
        assertEquals(DocumentKind.HTML, DocumentTypes.detect("index.html", null).kind)
    }

    @Test
    fun fallsBackToMime() {
        assertEquals(DocumentKind.MARKDOWN, DocumentTypes.detect("document", "text/markdown").kind)
        assertEquals(DocumentKind.PLAIN, DocumentTypes.detect("document", "text/plain").kind)
    }

    @Test
    fun sharedTextThatLooksLikeMarkdown() {
        assertEquals(DocumentKind.MARKDOWN, DocumentTypes.detectSharedText("# Title\n\nSome **bold** text").kind)
        assertEquals(DocumentKind.PLAIN, DocumentTypes.detectSharedText("just a sentence").kind)
    }
}

class JsonFormatterTest {
    @Test
    fun indentsMinifiedJson() {
        val out = JsonFormatter.format("""{"a":1,"b":[true,null],"c":{},"d":"x,{y}"}""")
        assertEquals(
            """
            {
              "a": 1,
              "b": [
                true,
                null
              ],
              "c": {},
              "d": "x,{y}"
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun keepsEscapedQuotes() {
        assertEquals("[\n  \"a\\\"b\"\n]", JsonFormatter.format("""["a\"b"]"""))
    }

    @Test
    fun unbalancedIsNull() {
        assertNull(JsonFormatter.format("""{"a": [1, 2}"""))
        assertNull(JsonFormatter.format("""{"a": 1"""))
    }
}

class DelimitedTest {
    @Test
    fun detectsSemicolon() {
        assertEquals(';', Delimited.detectDelimiter("a;b;c\n1;2;3\n"))
        assertEquals(',', Delimited.detectDelimiter("a,b\n1,2"))
        assertEquals('\t', Delimited.detectDelimiter("a\tb\n1\t2"))
    }

    @Test
    fun parsesQuotedCells() {
        val t = Delimited.parse("name,note\n\"Silva, Ana\",\"said \"\"hi\"\"\nthen left\"\n", ',')
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Silva, Ana", "said \"hi\"\nthen left"), t.rows[1])
        assertEquals(2, t.columnCount)
    }
}

class DotenvTest {
    @Test
    fun parsesVariables() {
        val lines = Dotenv.parse("# db\nexport HOST=localhost\nPASSWORD=\"a b\" # c\nPORT=5432 # comment\nMULTI=\"one\ntwo\"\n")
        assertEquals(EnvLine.Comment("# db"), lines[0])
        assertEquals(EnvLine.Variable("HOST", "localhost", true), lines[1])
        assertEquals(EnvLine.Variable("PASSWORD", "a b", false), lines[2])
        assertEquals(EnvLine.Variable("PORT", "5432", false), lines[3])
        assertEquals(EnvLine.Variable("MULTI", "one\ntwo", false), lines[4])
    }

    @Test
    fun secrets() {
        assertTrue(Dotenv.looksSecret("JWT_SECRET", "x"))
        assertTrue(Dotenv.looksSecret("DATABASE_URL", "postgres://app:pw@db:5432/app"))
        assertTrue(Dotenv.looksSecret("SERVICE", "zq7Hk29PmXr4Lw8Vt3Nb6Yc1Df5"))
        assertFalse(Dotenv.looksSecret("PORT", "8080"))
        assertFalse(Dotenv.looksSecret("NODE_ENV", "production"))
        assertFalse(Dotenv.looksSecret("API_KEY", ""))
    }
}

class TextSearchTest {
    @Test
    fun plainIgnoresCaseByDefault() {
        val r = TextSearch.search(listOf("Pitch and pitch", "none"), "pitch", SearchOptions()) as SearchResult.Found
        assertEquals(listOf(Match(0, 0, 5), Match(0, 10, 15)), r.matches)
    }

    @Test
    fun wholeWordAndCase() {
        val items = listOf("onPitch pitch Pitch")
        val word = TextSearch.search(items, "pitch", SearchOptions(wholeWord = true)) as SearchResult.Found
        assertEquals(2, word.matches.size)
        val case = TextSearch.search(items, "Pitch", SearchOptions(matchCase = true)) as SearchResult.Found
        assertEquals(2, case.matches.size)
    }

    @Test
    fun badRegex() {
        assertTrue(TextSearch.search(listOf("x"), "(", SearchOptions(regex = true)) is SearchResult.BadPattern)
    }

    @Test
    fun rowsMapping() {
        val text = "alpha\nbeta alpha\ngamma"
        val idx = LineIndex.build(text)
        val r = TextSearch.searchRows(text, idx.rowCount, idx::rowOf, idx::start, idx::end, "alpha", SearchOptions()) as SearchResult.Found
        assertEquals(listOf(Match(0, 0, 5), Match(1, 5, 10)), r.matches)
        assertEquals(1, TextSearch.firstAtOrAfter(r.matches, 1))
        assertEquals(0, TextSearch.firstAtOrAfter(r.matches, 2))
    }
}

class RecentsCodecTest {
    @Test
    fun roundTrip() {
        val e = RecentEntry("content://x/1", "a.md", null, 12, 99, true, "Downloads", false, true, ViewPosition(3, 40))
        val f = RecentEntry("content://x/2", "b.kt", "text/plain", -1, 1, false, null)
        assertEquals(listOf(e, f), RecentsCodec.decode(RecentsCodec.encode(listOf(e, f))))
    }

    @Test
    fun garbageIsEmpty() {
        assertEquals(emptyList<RecentEntry>(), RecentsCodec.decode("not json"))
    }
}
