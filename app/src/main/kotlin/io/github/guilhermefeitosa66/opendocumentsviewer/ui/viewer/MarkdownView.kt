package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CellAlign
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.InlineText
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MdBlock
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Language
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Languages
import io.github.guilhermefeitosa66.opendocumentsviewer.syntax.Tokens
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvColors

/** The language of a fenced code block, from its info string (```kotlin, ```py, ```sh...). */
fun languageForInfo(info: String): Language? {
    val word = info.trim().substringBefore(' ').substringBefore('{').lowercase()
    if (word.isEmpty()) return null
    return when (word) {
        "json", "jsonc", "json5" -> Languages.JSON
        "yaml", "yml" -> Languages.YAML
        "toml", "ini" -> Languages.INI
        "xml", "svg" -> Languages.XML
        "html" -> Languages.HTML
        "markdown", "md" -> Languages.MARKDOWN
        "env", "dotenv" -> Languages.DOTENV
        "csv" -> Languages.CSV
        "bash", "console", "shell", "shellsession", "terminal" -> Languages.SHELL
        "js", "node" -> Languages.JAVASCRIPT
        "ts" -> Languages.TYPESCRIPT
        "c++" -> Languages.CPP
        "c#", "dotnet" -> Languages.CSHARP
        "golang" -> Languages.GO
        "docker" -> Languages.DOCKERFILE
        "make" -> Languages.MAKEFILE
        "ps", "pwsh" -> Languages.POWERSHELL
        else -> Languages.all.firstOrNull { it.id == word } ?: Languages.byExtension(word)
    }
}

private fun tokenizeAll(code: String, language: Language?): List<Pair<String, Tokens?>> {
    var state = 0
    return code.split('\n').map { line ->
        if (language == null) line to null
        else {
            val t = Tokens()
            state = try { language.lexer.tokenize(line, state, t) } catch (_: RuntimeException) { 0 }
            line to t
        }
    }
}

@Composable
fun MarkdownView(
    content: Formatted.Markdown,
    state: LazyListState,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    linkListener: LinkInteractionListener,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = Odv.colors
    val limit = LocalReading.current.limitWidth
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    // On large screens, center a comfortable measure instead of 1000 dp long lines.
    val side = if (limit && maxWidth > MaxProseWidth) (maxWidth - MaxProseWidth) / 2 else 0.dp
    val padding = PaddingValues(start = side, end = side, top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(content.blocks) { i, block ->
                val itemMatches = matchesByItem[i]
                val currentHere = current?.takeIf { it.item == i }
                BlockFrame(block, colors) {
                    Block(block, colors, fontSize, itemMatches, currentHere, linkListener)
                }
            }
        }
    }
    }
}

@Composable
private fun BlockFrame(block: MdBlock, colors: OdvColors, content: @Composable () -> Unit) {
    val indent: Dp = 22.dp * block.indent
    val tight = block is MdBlock.ListItem
    var m = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
    if (block.quoteDepth > 0) {
        val bar = colors.quoteBar
        m = m.background(colors.quoteBackground)
        if (bar != null) {
            m = m.drawBehind {
                val w = 4.dp.toPx()
                for (d in 0 until block.quoteDepth) drawRect(bar, Offset(d * 14.dp.toPx(), 0f), Size(w, size.height))
            }
        }
        m = m.padding(start = 14.dp * block.quoteDepth, end = 14.dp)
    }
    Box(m.padding(start = indent, top = if (tight) 3.dp else 7.dp, bottom = if (tight) 3.dp else 7.dp)) {
        content()
    }
}

@Composable
private fun Block(
    block: MdBlock,
    colors: OdvColors,
    fontSize: TextUnit,
    matches: List<Match>?,
    current: Match?,
    linkListener: LinkInteractionListener,
) {
    val body = fontSize
    val codeSize = (fontSize.value * 0.86f).sp
    val lineHeight = (fontSize.value * 1.6f).sp
    when (block) {
        is MdBlock.Heading -> {
            val scale = when (block.level) { 1 -> 1.85f; 2 -> 1.38f; 3 -> 1.18f; 4 -> 1.06f; else -> 1f }
            val size = (body.value * scale).sp
            Column(Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp)) {
                Text(
                    text = rememberInline(block.content, colors, codeSize, matches, current, linkListener),
                    fontSize = size,
                    lineHeight = (size.value * 1.2f).sp,
                    fontWeight = if (block.level == 1) FontWeight.ExtraBold else FontWeight.Bold,
                    color = colors.headingColor ?: if (block.level >= 5) colors.ink2 else colors.ink,
                )
                if (block.level <= 2) HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.rule)
            }
        }
        is MdBlock.Paragraph -> Text(
            text = rememberInline(block.content, colors, codeSize, matches, current, linkListener),
            fontSize = body, lineHeight = lineHeight, color = colors.ink,
        )
        is MdBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val checked = block.checked
            if (checked != null) {
                CheckMark(checked, colors, (body.value * 1.1f).dp)
            } else {
                Text(
                    block.marker,
                    fontSize = body, lineHeight = lineHeight,
                    color = colors.accentInk,
                    modifier = Modifier.widthIn(min = 14.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = rememberInline(block.content, colors, codeSize, matches, current, linkListener),
                fontSize = body, lineHeight = lineHeight,
                color = if (checked == true) colors.ink2 else colors.ink,
            )
        }
        is MdBlock.CodeBlock -> CodeBlockView(block, colors, codeSize, matches, current)
        is MdBlock.Table -> TableBlockView(block, colors, body, codeSize, linkListener)
        is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 2.dp, color = colors.rule)
        is MdBlock.Html -> Text(block.raw, fontFamily = MonoFamily, fontSize = codeSize, color = colors.ink2)
        is MdBlock.Image -> ImageBlockView(block, colors)
    }
}

@Composable
private fun rememberInline(
    content: InlineText,
    colors: OdvColors,
    codeSize: TextUnit,
    matches: List<Match>?,
    current: Match?,
    linkListener: LinkInteractionListener,
) = remember(content, colors, codeSize, matches, current) {
    inlineAnnotated(content, colors, codeSize, matches, current, linkListener)
}

@Composable
private fun CheckMark(checked: Boolean, colors: OdvColors, size: Dp) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .padding(top = 3.dp)
            .size(size)
            .clip(shape)
            .then(if (checked) Modifier.background(colors.accent) else Modifier.border(BorderStroke(2.dp, colors.accent), shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(io.github.guilhermefeitosa66.opendocumentsviewer.R.drawable.ic_check),
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(size * 0.8f),
            )
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock, colors: OdvColors, codeSize: TextUnit, matches: List<Match>?, current: Match?) {
    val language = remember(block.info) { languageForInfo(block.info) }
    val annotated = remember(block, colors, matches, current) {
        val lines = tokenizeAll(block.code, language)
        // Rebuild the block as one string so search offsets (into the whole code) line up.
        androidx.compose.ui.text.buildAnnotatedString {
            var offset = 0
            lines.forEachIndexed { idx, (line, tokens) ->
                append(highlighted(line, tokens, colors, null, null))
                offset += line.length
                if (idx < lines.size - 1) { append('\n'); offset++ }
            }
            addMatches(this, block.code.length, colors, matches, current)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.codeBackground)
            .border(1.dp, colors.line, RoundedCornerShape(14.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            annotated,
            fontFamily = MonoFamily,
            fontSize = codeSize,
            lineHeight = (codeSize.value * 1.6f).sp,
            color = colors.ink,
            softWrap = false,
        )
    }
}

@Composable
private fun TableBlockView(block: MdBlock.Table, colors: OdvColors, body: TextUnit, codeSize: TextUnit, linkListener: LinkInteractionListener) {
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    // Column widths from the longest cell, in an approximate 0.55 em per char.
    val widths = remember(block, body) {
        (0 until columns).map { c ->
            val longest = (listOf(block.header) + block.rows).maxOf { r -> r.getOrNull(c)?.text?.length ?: 0 }
            (longest.coerceIn(3, 28) * body.value * 0.58f + 20).dp
        }
    }
    val smaller = (body.value * 0.92f).sp
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        if (block.header.isNotEmpty()) {
            TableRowView(block.header, widths, block.align, colors, smaller, codeSize, linkListener, header = true)
            HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, b -> a + b }), thickness = 2.dp, color = colors.line)
        }
        block.rows.forEachIndexed { r, row ->
            TableRowView(row, widths, block.align, colors, smaller, codeSize, linkListener, header = false)
            if (r < block.rows.size - 1) HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, b -> a + b }), color = colors.line)
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<InlineText>,
    widths: List<Dp>,
    align: List<CellAlign>,
    colors: OdvColors,
    size: TextUnit,
    codeSize: TextUnit,
    linkListener: LinkInteractionListener,
    header: Boolean,
) {
    Row {
        widths.forEachIndexed { c, w ->
            val cell = cells.getOrNull(c) ?: InlineText.EMPTY
            Text(
                text = rememberInline(cell, colors, codeSize, null, null, linkListener),
                modifier = Modifier.width(w).padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = size,
                fontWeight = if (header) FontWeight.SemiBold else null,
                color = colors.ink,
                textAlign = when (align.getOrNull(c)) {
                    CellAlign.CENTER -> TextAlign.Center
                    CellAlign.END -> TextAlign.End
                    else -> TextAlign.Start
                },
            )
        }
    }
}

@Composable
private fun ImageBlockView(block: MdBlock.Image, colors: OdvColors) {
    val loader = LocalImageLoader.current
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val result by androidx.compose.runtime.produceState<ImageResult?>(null, block.src) { value = loader(block.src, widthPx) }
    when (val r = result) {
        is ImageResult.Loaded -> androidx.compose.foundation.Image(
            r.bitmap, contentDescription = block.alt.ifEmpty { null },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
        )
        else -> {
            val label = when (r) {
                ImageResult.Remote -> androidx.compose.ui.res.stringResource(io.github.guilhermefeitosa66.opendocumentsviewer.R.string.image_remote)
                null -> "…"
                else -> androidx.compose.ui.res.stringResource(io.github.guilhermefeitosa66.opendocumentsviewer.R.string.image_unavailable)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.paper2).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (block.alt.isNotBlank()) Text(block.alt, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(label, color = colors.ink2, fontSize = 12.sp)
            }
        }
    }
}
