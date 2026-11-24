package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.luminance
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineStyle
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineText
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.TokenType
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Tokens
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvColors

private val boldTypes = setOf(TokenType.KEYWORD, TokenType.HEADING, TokenType.SECTION)

fun highlighted(
    line: String,
    tokens: Tokens?,
    colors: OdvColors,
    matches: List<Match>?,
    current: Match?,
): AnnotatedString = buildAnnotatedString {
    append(line)
    if (tokens != null) {
        for (i in 0 until tokens.size) {
            val type = tokens.type(i)
            val end = minOf(tokens.end(i), line.length)
            val start = minOf(tokens.start(i), end)
            if (start >= end) continue
            addStyle(
                SpanStyle(
                    color = colors.syntax.of(type),
                    fontWeight = if (type in boldTypes) FontWeight.SemiBold else null,
                    fontStyle = if (type == TokenType.COMMENT) FontStyle.Italic else null,
                ),
                start, end,
            )
        }
    }
    addMatches(this, line.length, colors, matches, current)
}

internal fun addMatches(b: AnnotatedString.Builder, length: Int, colors: OdvColors, matches: List<Match>?, current: Match?) {
    if (matches == null) return
    for (m in matches) {
        val s = m.start.coerceIn(0, length)
        val e = m.end.coerceIn(0, length)
        if (s >= e) continue
        if (m == current) {
            b.addStyle(SpanStyle(background = colors.currentMatch, color = colors.onCurrentMatch), s, e)
        } else {
            b.addStyle(SpanStyle(background = colors.match), s, e)
        }
    }
}

fun inlineAnnotated(
    content: InlineText,
    colors: OdvColors,
    codeSize: TextUnit,
    matches: List<Match>?,
    current: Match?,
    linkListener: LinkInteractionListener,
): AnnotatedString = buildAnnotatedString {
    append(content.text)
    val len = content.text.length
    for (span in content.spans) {
        val s = span.start.coerceIn(0, len)
        val e = span.end.coerceIn(0, len)
        if (s >= e) continue
        when (span.style) {
            InlineStyle.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s, e)
            InlineStyle.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), s, e)
            InlineStyle.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.ink2), s, e)
            InlineStyle.CODE -> addStyle(SpanStyle(fontFamily = MonoFamily, fontSize = codeSize, background = colors.inlineCode), s, e)
            InlineStyle.UNDERLINE -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
            InlineStyle.COLOR -> addStyle(SpanStyle(color = readableColor(span.color, colors)), s, e)
            InlineStyle.SIZE -> addStyle(SpanStyle(fontSize = codeSize * (span.scale.coerceIn(0.6f, 2.4f) / 0.86f)), s, e)
            InlineStyle.LINK -> {
                val url = span.url.orEmpty()
                addLink(
                    LinkAnnotation.Url(
                        url,
                        TextLinkStyles(SpanStyle(color = colors.accentInk, textDecoration = TextDecoration.Underline)),
                        linkListener,
                    ),
                    s, e,
                )
            }
        }
    }
    addMatches(this, len, colors, matches, current)
}

/**
 * A document's own text color, unless it would vanish on the current theme (black text in a dark
 * theme, white in a light one): then the theme's text color wins.
 */
fun readableColor(argb: Int, colors: OdvColors): androidx.compose.ui.graphics.Color {
    val c = androidx.compose.ui.graphics.Color(argb)
    val l = c.luminance()
    val bg = colors.paper.luminance()
    val contrast = (maxOf(l, bg) + 0.05f) / (minOf(l, bg) + 0.05f)
    return if (contrast < 3f) colors.ink else c
}
