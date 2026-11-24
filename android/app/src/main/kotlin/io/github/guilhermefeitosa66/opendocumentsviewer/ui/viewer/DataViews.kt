package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.NodeKind
import androidx.compose.ui.text.withStyle
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

/** CSV/TSV as a table: sticky header (tap to sort, drag its edge to resize), row numbers, sideways scroll. */
@Composable
fun TableView(
    table: DelimitedTable,
    session: DocumentSession,
    state: LazyListState,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
) {
    val colors = Odv.colors
    val density = LocalDensity.current
    val charWidth = rememberMonoCharWidth(fontSize)
    val auto: List<Dp> = remember(table, charWidth) {
        table.columnChars.map { with(density) { ((it + 1) * charWidth).toDp() } + 24.dp }
    }
    val widths = auto.mapIndexed { i, w -> session.columnWidths[i]?.dp ?: w }
    val numberWidth = with(density) { (table.rows.size.toString().length * charWidth).toDp() } + 20.dp
    val total = widths.fold(numberWidth) { a, b -> a + b } + 16.dp
    val rows = session.tableRows ?: table.rows
    val header = rows.firstOrNull()
    val sort = session.tableSort
    val hScroll = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxOf(maxWidth, total)
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            SelectionContainer {
                LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.requiredWidth(width).fillMaxHeight()) {
                    if (header != null) {
                        stickyHeader {
                            Column(Modifier.background(colors.paper2)) {
                                HeaderRow(header, widths, numberWidth, fontSize, sort, onSort = session::sortTable) { col, deltaPx ->
                                    val now = session.columnWidths[col] ?: with(density) { widths[col].toPx() / density.density }
                                    session.columnWidths[col] = (now + deltaPx / density.density).coerceIn(40f, 900f)
                                }
                                HorizontalDivider(color = colors.line, thickness = 2.dp)
                            }
                        }
                    }
                    itemsIndexed(rows.drop(1)) { i, row ->
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
private fun HeaderRow(
    cells: List<String>,
    widths: List<Dp>,
    numberWidth: Dp,
    fontSize: TextUnit,
    sort: DocumentSession.TableSort?,
    onSort: (Int) -> Unit,
    onResize: (Int, Float) -> Unit,
) {
    val colors = Odv.colors
    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.width(numberWidth))
        widths.forEachIndexed { c, w ->
            val arrow = when {
                sort?.column != c -> ""
                sort.ascending -> " ▲"
                else -> " ▼"
            }
            Box(Modifier.width(w)) {
                Text(
                    cells.getOrNull(c).orEmpty() + arrow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.sort_column)) { onSort(c) }
                        .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    fontFamily = MonoFamily, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
                    color = if (arrow.isEmpty()) colors.ink else colors.accentInk,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // Drag the right edge to change the column width.
                Box(
                    Modifier
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .width(14.dp)
                        .fillMaxHeight()
                        .pointerInput(c) {
                            detectHorizontalDragGestures { change, dx -> change.consume(); onResize(c, dx) }
                        },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Box(Modifier.width(2.dp).heightIn(min = 18.dp).background(colors.line))
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

/** JSON, YAML and XML as a collapsible tree. Tap a branch to open it; long press copies the node's path. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TreeView(
    session: DocumentSession,
    state: LazyListState,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
) {
    val colors = Odv.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val copied = stringResource(R.string.path_copied)
    val lines = session.treeLines
    val indentPx = 18.dp
    LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines, key = { _, l -> l.node.id }) { i, line ->
            val n = line.node
            val hit = matchesByItem.containsKey(i)
            val bg = when {
                current?.item == i -> colors.currentMatch.copy(alpha = 0.45f)
                hit -> colors.match
                else -> colors.paper.copy(alpha = 0f)
            }
            val open = n.id in session.treeExpanded
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .combinedClickable(
                        onClick = { if (n.children.isNotEmpty()) session.toggleNode(n.id) },
                        onLongClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(n.path))
                            android.widget.Toast.makeText(context, copied + "\n" + n.path, android.widget.Toast.LENGTH_SHORT).show()
                        },
                    )
                    .heightIn(min = 36.dp)
                    .padding(start = 8.dp + indentPx * line.depth, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Box(Modifier.width(22.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (n.children.isNotEmpty()) {
                        Text(if (open) "▾" else "▸", color = colors.ink2, fontSize = fontSize)
                    }
                }
                Text(
                    treeLabel(n, colors, open),
                    fontFamily = MonoFamily,
                    fontSize = fontSize,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun treeLabel(n: io.github.guilhermefeitosa66.opendocumentsviewer.formats.TreeNode, colors: io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.OdvColors, open: Boolean) =
    androidx.compose.ui.text.buildAnnotatedString {
        when (n.kind) {
            NodeKind.ELEMENT -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.syntax.tag, fontWeight = FontWeight.SemiBold)) { append("<${n.key}>") }
            NodeKind.TEXT -> {}
            else -> n.key?.let { key ->
                withStyle(androidx.compose.ui.text.SpanStyle(color = if (n.kind == NodeKind.ATTRIBUTE) colors.syntax.attribute else colors.syntax.key)) { append(key) }
                withStyle(androidx.compose.ui.text.SpanStyle(color = colors.ink2)) { append(if (n.kind == NodeKind.ATTRIBUTE) " = " else ": ") }
            }
        }
        val count = n.children.size
        when (n.kind) {
            NodeKind.OBJECT -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.ink2)) { append(if (open) "{ }" else "{ $count }") }
            NodeKind.ARRAY -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.ink2)) { append(if (open) "[ ]" else "[ $count ]") }
            NodeKind.ELEMENT -> if (!open && count > 0) withStyle(androidx.compose.ui.text.SpanStyle(color = colors.ink2)) { append("  ($count)") }
            NodeKind.STRING, NodeKind.ATTRIBUTE -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.syntax.string)) {
                append(if (n.kind == NodeKind.STRING) "\"${n.value}\"" else n.value.orEmpty())
            }
            NodeKind.NUMBER -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.syntax.number)) { append(n.value.orEmpty()) }
            NodeKind.BOOLEAN, NodeKind.NULL -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.syntax.literal)) { append(n.value.orEmpty()) }
            NodeKind.TEXT -> withStyle(androidx.compose.ui.text.SpanStyle(color = colors.ink)) { append(n.value.orEmpty()) }
        }
    }
