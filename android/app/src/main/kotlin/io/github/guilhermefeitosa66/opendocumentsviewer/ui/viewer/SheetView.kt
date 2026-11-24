package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Cell
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Sheet
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Workbook
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv

/** How a sheet is laid out on screen: visible rows and columns, frozen ones apart. */
class SheetLayout(val sheet: Sheet) {
    private val minCols = 6
    private val minRows = 30
    val colCount = maxOf(sheet.maxCol, minCols).coerceAtMost(Workbook.MAX_COLS)
    val rowCount = maxOf(sheet.maxRow, minRows)
    val frozenRows = (0 until sheet.frozenRows.coerceAtMost(rowCount)).filter { it !in sheet.hiddenRows }
    val frozenCols = (0 until sheet.frozenCols.coerceAtMost(colCount)).filter { it !in sheet.hiddenCols }
    /** Rows below the frozen ones; list item i + 1 is bodyRows[i] (item 0 is the sticky header). */
    val bodyRows: List<Int> = (sheet.frozenRows.coerceAtMost(rowCount) until rowCount).filter { it !in sheet.hiddenRows }
    val scrollCols: List<Int> = (sheet.frozenCols.coerceAtMost(colCount) until colCount).filter { it !in sheet.hiddenCols }

    /** For merged areas: top-left cell → last column of the area; covered cells → true. */
    val mergeEnd = HashMap<Long, Int>()
    val covered = HashSet<Long>()

    init {
        for (m in sheet.merges) {
            mergeEnd[key(m[0], m[1])] = m[3]
            for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) covered += key(r, c)
        }
    }

    /** Text of each list item, for search: item 0 is the frozen rows. */
    fun searchLines(wb: Workbook): List<String> {
        fun line(r: Int) = sheet.rows[r]?.let { row -> (0 until colCount).mapNotNull { c -> row[c]?.let(wb::display) }.joinToString("  ") }.orEmpty()
        return listOf(frozenRows.joinToString("  ") { line(it) }) + bodyRows.map(::line)
    }

    companion object {
        fun key(r: Int, c: Int): Long = (r.toLong() shl 20) or c.toLong()
    }
}

/** A spreadsheet: sheet tabs, a grid virtualized both ways, frozen panes, tap a cell for its value and formula. */
@Composable
fun SheetView(
    wb: Workbook,
    session: DocumentSession,
    state: LazyListState,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
) {
    val colors = Odv.colors
    val density = LocalDensity.current
    val visibleSheets = remember(wb) { wb.sheets.indices.filter { !wb.sheets[it].hidden } }
    val sheet = wb.sheets.getOrNull(session.sheetIndex) ?: wb.sheets.first()
    val layout = session.sheetLayout ?: remember(sheet) { SheetLayout(sheet) }
    val cellSize = (fontSize.value * 0.9f).sp
    val measurer = rememberTextMeasurer()
    val zeroWidth = remember(cellSize) { measurer.measure("0000000000", TextStyle(fontSize = cellSize)).size.width / 10f }
    fun colWidth(c: Int): Dp = with(density) { ((sheet.colWidths[c] ?: 8.43f) * zeroWidth + 10f).toDp() }.coerceIn(24.dp, 600.dp)
    val baseHeight = (fontSize.value * 2.1f).dp
    fun rowHeight(r: Int): Dp = sheet.rowHeights[r]?.let { (it * 1.33f * fontSize.value / 14f).dp }?.coerceIn(baseHeight * 0.6f, baseHeight * 6) ?: baseHeight
    val numberWidth = with(density) { (layout.rowCount.toString().length * zeroWidth + 16f).toDp() }
    val hScroll = rememberScrollState()
    val scrollWidths = remember(layout, zeroWidth) { layout.scrollCols.map(::colWidth) }
    val starts = remember(scrollWidths) { scrollWidths.runningFold(0.dp) { a, b -> a + b } }
    val frozenWidth = layout.frozenCols.fold(0.dp) { a, c -> a + colWidth(c) }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (visibleSheets.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in visibleSheets) {
                    val selected = wb.sheets[i] === sheet
                    Text(
                        wb.sheets[i].name,
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) colors.accent else colors.paper2)
                            .clickable { session.selectSheet(i) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        color = if (selected) colors.onAccent else colors.ink,
                        fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else null,
                    )
                }
            }
        }
        if (sheet.charts.isNotEmpty() || sheet.truncated) {
            val notes = buildList {
                if (sheet.charts.isNotEmpty()) add(stringResource(R.string.sheet_charts, sheet.charts.joinToString(", ") { it.ifBlank { "…" } }))
                if (sheet.truncated) add(stringResource(R.string.sheet_truncated))
            }
            Text(notes.joinToString("\n"), Modifier.fillMaxWidth().background(colors.paper2).padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp, color = colors.ink2)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxWidth - numberWidth - frozenWidth
            val scrolled = with(density) { hScroll.value.toDp() }
            // Columns on screen (with one spare each side).
            var first = starts.indexOfLast { it <= scrolled }.coerceAtLeast(0)
            var last = starts.indexOfFirst { it >= scrolled + viewport }.let { if (it < 0) layout.scrollCols.size else it }
            first = (first - 1).coerceAtLeast(0)
            last = (last + 1).coerceAtMost(layout.scrollCols.size)
            val totalScroll = starts.lastOrNull() ?: 0.dp

            @Composable
            fun GridRow(r: Int?, item: Int?) {
                val hit = item != null && matchesByItem.containsKey(item)
                val isCurrent = item != null && current?.item == item
                val h = if (r == null) baseHeight else rowHeight(r)
                val bg = when { isCurrent -> colors.currentMatch.copy(alpha = 0.45f); hit -> colors.match; else -> Color.Transparent }
                Row(Modifier.background(bg).height(h)) {
                    // Row number / corner.
                    Box(Modifier.width(numberWidth).height(h).background(colors.paper2).border(0.5.dp, colors.line), contentAlignment = Alignment.CenterEnd) {
                        Text(r?.let { (it + 1).toString() }.orEmpty(), Modifier.padding(end = 6.dp), fontFamily = MonoFamily, fontSize = (fontSize.value * 0.75f).sp, color = colors.gutter)
                    }
                    for (c in layout.frozenCols) CellBox(wb, layout, r, c, colWidth(c), h, cellSize, session)
                    Box(Modifier.width(viewport.coerceAtLeast(0.dp)).horizontalScroll(hScroll)) {
                        Row {
                            Spacer(Modifier.width(starts.getOrElse(first) { 0.dp }))
                            for (k in first until last) CellBox(wb, layout, r, layout.scrollCols[k], scrollWidths[k], h, cellSize, session)
                            Spacer(Modifier.width((totalScroll - starts.getOrElse(last) { totalScroll }).coerceAtLeast(0.dp)))
                        }
                    }
                }
            }

            SelectionContainer {
                LazyColumn(state = state, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 96.dp), modifier = Modifier.fillMaxSize()) {
                    stickyHeader(key = "head") {
                        Column(Modifier.background(colors.paper)) {
                            GridRow(null, null)
                            for (r in layout.frozenRows) GridRow(r, 0)
                            if (layout.frozenRows.isNotEmpty()) HorizontalDivider(color = colors.accent.copy(alpha = 0.5f), thickness = 2.dp)
                        }
                    }
                    items(layout.bodyRows.size, key = { layout.bodyRows[it] }) { i -> GridRow(layout.bodyRows[i], i + 1) }
                }
            }
            session.selectedCell?.let { (r, c) -> CellInfo(wb, sheet, r, c, Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 84.dp)) { session.selectedCell = null } }
        }
    }
}

@Composable
private fun CellBox(wb: Workbook, layout: SheetLayout, r: Int?, c: Int, width: Dp, height: Dp, size: TextUnit, session: DocumentSession) {
    val colors = Odv.colors
    if (r == null) {
        // Column letter.
        Box(Modifier.width(width).height(height).background(colors.paper2).border(0.5.dp, colors.line), contentAlignment = Alignment.Center) {
            Text(Workbook.colName(c), fontFamily = MonoFamily, fontSize = (size.value * 0.85f).sp, color = colors.gutter)
        }
        return
    }
    val key = SheetLayout.key(r, c)
    val sheet = layout.sheet
    val cell: Cell? = if (key in layout.covered) null else sheet.cell(r, c)
    val style = cell?.let { wb.style(it.style) }
    // A merged area: the top-left cell spans its columns (drawn over the covered ones).
    val spanTo = layout.mergeEnd[key]
    val selected = session.selectedCell == (r to c)
    val fill = style?.fill?.takeIf { it != 0 }?.let { Color(it) }
    Box(
        Modifier
            .width(width)
            .height(height)
            .background(fill ?: Color.Transparent)
            .border(if (style?.border == true) 0.8.dp else 0.4.dp, if (style?.border == true) colors.ink2.copy(alpha = 0.6f) else colors.line.copy(alpha = 0.6f))
            .then(if (selected) Modifier.border(2.dp, colors.accent) else Modifier)
            .clickable { session.selectedCell = if (selected) null else r to c },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (cell != null) {
            val text = wb.display(cell)
            val numeric = cell.value is Double || cell.value is Boolean
            val align = when (style?.align) { 'l' -> TextAlign.Start; 'c' -> TextAlign.Center; 'r' -> TextAlign.End; else -> if (numeric) TextAlign.End else TextAlign.Start }
            val spanWidth = if (spanTo != null && spanTo > c) width * (spanTo - c + 1).toFloat() else width
            Text(
                text,
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(if (spanTo != null) spanWidth else width).padding(horizontal = 4.dp),
                fontSize = size,
                fontWeight = if (style?.bold == true) FontWeight.Bold else null,
                fontStyle = if (style?.italic == true) FontStyle.Italic else null,
                textDecoration = when {
                    style?.underline == true && style.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    style?.underline == true -> TextDecoration.Underline
                    style?.strike == true -> TextDecoration.LineThrough
                    else -> null
                },
                color = style?.color?.takeIf { it != 0 }?.let { if (fill != null) Color(it) else readableColor(it, colors) }
                    ?: if (fill != null && fill.luminance() > 0.5f) Color.Black else if (fill != null) Color.White else colors.ink,
                textAlign = align,
                maxLines = if (style?.wrap == true) 6 else 1,
                softWrap = style?.wrap == true,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The tapped cell: its reference, the shown value, the value saved and the formula. */
@Composable
private fun CellInfo(wb: Workbook, sheet: Sheet, r: Int, c: Int, modifier: Modifier, onClose: () -> Unit) {
    val colors = Odv.colors
    val cell = sheet.cell(r, c)
    Surface(modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(max = 220.dp), color = colors.card, shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp) {
        Row(verticalAlignment = Alignment.Top) {
            SelectionContainer(Modifier.weight(1f)) {
                Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Workbook.colName(c) + (r + 1), fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, color = colors.accentInk, fontSize = 14.sp)
                    val shown = cell?.let(wb::display).orEmpty()
                    Text(shown.ifEmpty { "—" }, color = colors.ink, fontSize = 15.sp)
                    val raw = when (val v = cell?.value) {
                        is Double -> io.github.guilhermefeitosa66.opendocumentsviewer.formats.NumberFormat.general(v)
                        null -> null
                        else -> v.toString()
                    }
                    if (raw != null && raw != shown) Text(raw, color = colors.ink2, fontSize = 13.sp, fontFamily = MonoFamily)
                    cell?.formula?.let { Text(it, color = colors.ink2, fontSize = 13.sp, fontFamily = MonoFamily) }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
        }
    }
}
