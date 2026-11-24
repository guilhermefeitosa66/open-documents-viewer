package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.DelimitedTable
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Dotenv
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.EnvLine
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv

/** CSV/TSV as a table: first row as a sticky header, row numbers, sideways scroll. */
@Composable
fun TableView(
    table: DelimitedTable,
    state: LazyListState,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
) {
    val colors = Odv.colors
    val density = LocalDensity.current
    val charWidth = rememberMonoCharWidth(fontSize)
    val widths: List<Dp> = remember(table, charWidth) {
        table.columnChars.map { with(density) { ((it + 1) * charWidth).toDp() } + 24.dp }
    }
    val numberWidth = with(density) { (table.rows.size.toString().length * charWidth).toDp() } + 20.dp
    val total = widths.fold(numberWidth) { a, b -> a + b } + 16.dp
    val header = table.rows.firstOrNull()
    val hScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxOf(maxWidth, total)
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            SelectionContainer {
                LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.requiredWidth(width).fillMaxHeight()) {
                    if (header != null) {
                        stickyHeader {
                            Column(Modifier.background(colors.paper2)) {
                                CsvRow(null, header, widths, numberWidth, fontSize, header = true, highlight = false)
                                HorizontalDivider(color = colors.line, thickness = 2.dp)
                            }
                        }
                    }
                    itemsIndexed(table.rows.drop(1)) { i, row ->
                        val item = i + 1
                        val hit = matchesByItem.containsKey(item)
                        CsvRow(item, row, widths, numberWidth, fontSize, header = false, highlight = hit, current = current?.item == item)
                        HorizontalDivider(color = colors.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun CsvRow(
    number: Int?,
    cells: List<String>,
    widths: List<Dp>,
    numberWidth: Dp,
    fontSize: TextUnit,
    header: Boolean,
    highlight: Boolean,
    current: Boolean = false,
) {
    val colors = Odv.colors
    val bg = when {
        current -> colors.currentMatch.copy(alpha = 0.45f)
        highlight -> colors.match
        else -> colors.paper.copy(alpha = 0f)
    }
    Row(Modifier.background(bg).heightIn(min = 36.dp).padding(horizontal = 8.dp)) {
        Text(
            number?.toString() ?: "",
            modifier = Modifier.width(numberWidth).padding(end = 10.dp, top = 8.dp),
            textAlign = TextAlign.End,
            fontFamily = MonoFamily, fontSize = fontSize, color = colors.gutter,
        )
        widths.forEachIndexed { c, w ->
            val v = cells.getOrNull(c).orEmpty()
            val numeric = v.isNotEmpty() && v.all { it.isDigit() || it in ".,-+%$ " } && v.any { it.isDigit() }
            Text(
                v,
                modifier = Modifier.width(w).padding(horizontal = 10.dp, vertical = 8.dp),
                fontFamily = MonoFamily,
                fontSize = fontSize,
                fontWeight = if (header) FontWeight.SemiBold else null,
                color = colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (numeric && !header) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

/** A .env file as a list of variables; values that look like secrets are hidden until tapped. */
@Composable
fun EnvView(
    lines: List<EnvLine>,
    state: LazyListState,
    fontSize: TextUnit,
    revealAll: Boolean,
    revealed: Map<Int, Boolean>,
    onToggle: (Int) -> Unit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
) {
    val colors = Odv.colors
    val anySecret = remember(lines) { lines.any { it is EnvLine.Variable && Dotenv.looksSecret(it.key, it.value) } }
    SelectionContainer {
        LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            if (anySecret && !revealAll) {
                item(key = "note") {
                    Text(
                        stringResource(R.string.env_hidden_note),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.paper2)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        fontSize = 13.sp,
                        color = colors.ink,
                    )
                }
            }
            itemsIndexed(lines) { i, line ->
                val hit = matchesByItem.containsKey(i)
                val isCurrent = current?.item == i
                val bg = when {
                    isCurrent -> colors.currentMatch.copy(alpha = 0.45f)
                    hit -> colors.match
                    else -> colors.paper.copy(alpha = 0f)
                }
                when (line) {
                    is EnvLine.Comment -> Text(
                        line.text,
                        modifier = Modifier.background(bg).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                        fontFamily = MonoFamily, fontSize = (fontSize.value * 0.92f).sp,
                        fontStyle = FontStyle.Italic, color = colors.syntax.comment,
                    )
                    is EnvLine.Variable -> {
                        val secret = Dotenv.looksSecret(line.key, line.value)
                        val shown = !secret || revealAll || revealed[i] == true
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (secret && shown && !revealAll) colors.paper2 else bg)
                                .then(if (secret) Modifier.clickable { onToggle(i) } else Modifier)
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                (if (line.exported) "export " else "") + line.key,
                                fontFamily = MonoFamily, fontSize = fontSize,
                                fontWeight = FontWeight.SemiBold, color = colors.syntax.key,
                            )
                            Text(
                                if (shown) line.value.ifEmpty { "\"\"" } else "•".repeat(12),
                                fontFamily = MonoFamily, fontSize = fontSize,
                                color = if (shown) colors.ink else colors.ink2,
                                letterSpacing = if (shown) 0.sp else 1.5.sp,
                            )
                        }
                    }
                    is EnvLine.Other -> Text(
                        line.text,
                        modifier = Modifier.background(bg).padding(horizontal = 20.dp, vertical = 4.dp),
                        fontFamily = MonoFamily, fontSize = fontSize, color = colors.ink2,
                    )
                    EnvLine.Blank -> Box(Modifier.heightIn(min = 8.dp))
                }
            }
        }
    }
}
