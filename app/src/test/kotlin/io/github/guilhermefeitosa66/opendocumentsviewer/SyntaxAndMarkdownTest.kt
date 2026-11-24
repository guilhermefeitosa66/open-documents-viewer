package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.document.LineIndex
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MarkdownModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Languages
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Lexer
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.RowHighlighter
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.TokenType
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Tokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun Lexer.tokens(line: String, state: Int = 0): List<Pair<String, TokenType>> {
    val t = Tokens()
    tokenize(line, state, t)
    return t.toList().map { (s, e, type) -> line.substring(s, e) to type }
}

class LexerTest {
    @Test
    fun kotlinLine() {
        val toks = Languages.KOTLIN.lexer.tokens("""    val avg = window.average() // smooth "x"""")
        assertTrue("val" to TokenType.KEYWORD in toks)
        assertTrue("average" to TokenType.FUNCTION in toks)
        assertTrue("""// smooth "x"""" to TokenType.COMMENT in toks)
    }

    @Test
    fun kotlinStringTemplate() {
        val toks = Languages.KOTLIN.lexer.tokens("""log("pitch=${'$'}avg Hz")""")
        assertTrue("${'$'}avg" to TokenType.VARIABLE in toks)
        assertTrue("\"pitch=" to TokenType.STRING in toks)
    }

    @Test
    fun blockCommentSpansLines() {
        val lexer = Languages.JAVA.lexer
        val t = Tokens()
        val state = lexer.tokenize("int a; /* start", 0, t)
        assertTrue(state != 0)
        val next = Tokens()
        assertEquals(0, lexer.tokenize("still */ int b;", state, next))
        assertEquals(TokenType.COMMENT, next.type(0))
        assertEquals("still */".length, next.end(0))
    }

    @Test
    fun pythonTripleQuotedString() {
        val lexer = Languages.PYTHON.lexer
        val state = lexer.tokenize("x = \"\"\"doc", 0, Tokens())
        assertTrue(state >= 100)
        assertEquals(listOf("more\"\"\"" to TokenType.STRING), lexer.tokens("more\"\"\" + 1", state).take(1))
    }

    @Test
    fun rustLifetimeIsNotAString() {
        val toks = Languages.RUST.lexer.tokens("fn f<'a>(x: &'a str) -> char { 'x' }")
        assertTrue("'x'" to TokenType.STRING in toks)
        assertTrue(toks.none { it.second == TokenType.STRING && it.first.startsWith("'a") })
    }

    @Test
    fun shellHashInsideWordIsNotComment() {
        val toks = Languages.SHELL.lexer.tokens("echo a#b # real")
        assertTrue("# real" to TokenType.COMMENT in toks)
    }

    @Test
    fun jsonKeysAndValues() {
        val toks = Languages.JSON.lexer.tokens("""  "name": "odv", "n": -1.5e3, "ok": true""")
        assertEquals(
            listOf("\"name\"" to TokenType.KEY, "\"odv\"" to TokenType.STRING, "\"n\"" to TokenType.KEY, "-1.5e3" to TokenType.NUMBER, "\"ok\"" to TokenType.KEY, "true" to TokenType.LITERAL),
            toks,
        )
    }

    @Test
    fun yamlKeys() {
        val toks = Languages.YAML.lexer.tokens("  - name: build # step")
        assertTrue("name" to TokenType.KEY in toks)
        assertTrue("# step" to TokenType.COMMENT in toks)
    }

    @Test
    fun iniSectionAndKey() {
        assertEquals(TokenType.SECTION, Languages.INI.lexer.tokens("[server]")[0].second)
        val toks = Languages.INI.lexer.tokens("port = 8080 ; comment")
        assertTrue("port" to TokenType.KEY in toks)
        assertTrue("8080" to TokenType.NUMBER in toks)
    }

    @Test
    fun markupTagsAndAttributes() {
        val toks = Languages.XML.lexer.tokens("""<item name="x">text</item>""")
        assertTrue("item" to TokenType.TAG in toks)
        assertTrue("name" to TokenType.ATTRIBUTE in toks)
        assertTrue("\"x\"" to TokenType.STRING in toks)
    }

    @Test
    fun markdownSourceFence() {
        val lexer = Languages.MARKDOWN.lexer
        val s = lexer.tokenize("```kotlin", 0, Tokens())
        assertTrue(s != 0)
        assertEquals(listOf("val x = 1" to TokenType.CODE), lexer.tokens("val x = 1", s))
        assertEquals(0, lexer.tokenize("```", s, Tokens()))
        assertTrue("# " .trim() to TokenType.MARKUP in lexer.tokens("# Title"))
    }

    @Test
    fun everyLanguageSurvivesNastyInput() {
        val nasty = listOf("", "\"", "'", "/*", "\"\"\"", "${'$'}{", "<!--", "[", "a = \"unterminated", "\\", "0x", "1e", "@", "#")
        for (lang in Languages.all + listOf(Languages.JSON, Languages.MARKDOWN, Languages.DOTENV, Languages.CSV)) {
            var state = 0
            for (line in nasty) state = lang.lexer.tokenize(line, state, Tokens())
        }
    }

    @Test
    fun highlighterKnowsStatesAcrossRows() {
        val text = "/* a\nb */ val x = 1"
        val idx = LineIndex.build(text)
        val h = RowHighlighter(text, idx, Languages.KOTLIN.lexer)
        h.computeStates()
        val row1 = h.tokenize(1)
        assertEquals(TokenType.COMMENT, row1.type(0))
    }
}

class MarkdownModelTest {
    @Test
    fun flattensBlocks() {
        val md = """
            # Title

            Some **bold** and `code` with a [link](https://x.org).

            - [x] done
            - [ ] todo
              - nested

            > quoted

            ```kotlin
            val x = 1
            ```

            | a | b |
            |---|--:|
            | 1 | 2 |
        """.trimIndent()
        val blocks = MarkdownModel.parse(md)
        val h = blocks[0] as MdBlock.Heading
        assertEquals(1, h.level)
        assertEquals("Title", h.content.text)

        val p = blocks[1] as MdBlock.Paragraph
        assertEquals("Some bold and code with a link.", p.content.text)
        assertTrue(p.content.spans.any { it.style == InlineStyle.BOLD && p.content.text.substring(it.start, it.end) == "bold" })
        assertTrue(p.content.spans.any { it.style == InlineStyle.LINK && it.url == "https://x.org" })

        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(true, items[0].checked)
        assertEquals("done", items[0].content.text)
        assertEquals(false, items[1].checked)
        assertEquals(1, items[2].indent)

        assertTrue(blocks.any { it is MdBlock.Paragraph && it.quoteDepth == 1 && it.content.text == "quoted" })
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.info)
        assertEquals("val x = 1", code.code)
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.header.map { it.text })
        assertEquals(listOf("1", "2"), table.rows[0].map { it.text })
    }
}
