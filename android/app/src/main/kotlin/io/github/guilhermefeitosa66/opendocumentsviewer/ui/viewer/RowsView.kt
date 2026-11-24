package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.MonoFamily
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of one char of the monospace font at [fontSize], in px. */
@Composable
fun rememberMonoCharWidth(fontSize: TextUnit): Float {
    val measurer = rememberTextMeasurer()
    return remember(fontSize) {
        measurer.measure("0000000000", TextStyle(fontFamily = MonoFamily, fontSize = fontSize)).size.width / 10f
    }
}

/**
 * Text, code and formatted JSON: a lazy list of rows. Only visible rows are highlighted. With
 * wrapping off the whole list scrolls sideways, sized from the longest row so the width never
 * jumps while scrolling.
 */
@Composable
fun RowsView(
    content: TextContent,
    state: LazyListState,
    wrap: Boolean,
    lineNumbers: Boolean,
    fontSize: TextUnit,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    revealRequest: Int,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** With wrapping on, rows are at most this wide and centered (prose on large screens). */
    maxTextWidth: Dp? = null,
) {
    val colors = Odv.colors
    val index = content.index
    val highlighter = content.highlighter
    val statesReady = content.statesReady
    val charWidth = rememberMonoCharWidth(fontSize)
    val density = LocalDensity.current
    val digits = index.lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth: Dp = with(density) { (charWidth * digits).toDp() } + 14.dp
    val lineHeight = (fontSize.value * 1.55f).sp
    val hScroll = rememberScrollState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxWidth
        val contentWidth: Dp = if (wrap) viewport else {
            val text = with(density) { (charWidth * index.longestRow).toDp() }
            maxOf(viewport, (if (lineNumbers) gutterWidth else 0.dp) + text + 32.dp)
        }

        // Bring a search match into view sideways too.
        LaunchedEffect(revealRequest, wrap) {
            if (!wrap && current != null) {
                val gutter = if (lineNumbers) with(density) { gutterWidth.roundToPx() } else 0
                val start = gutter + (charWidth * current.start).roundToInt()
                val end = gutter + (charWidth * current.end).roundToInt()
                val width = with(density) { viewport.roundToPx() }
                val margin = with(density) { 24.dp.roundToPx() }
                if (start < hScroll.value + gutter || end > hScroll.value + width - margin) {
                    val x = start - width / 3
                    hScroll.animateScrollTo(x.coerceIn(0, hScroll.maxValue))
                }
            }
        }

        val narrowed = wrap && maxTextWidth != null && viewport > maxTextWidth
        val listModifier = when {
            narrowed -> Modifier.width(maxTextWidth!!).fillMaxHeight()
            wrap -> Modifier.fillMaxSize()
            else -> Modifier.requiredWidth(contentWidth).fillMaxHeight()
        }
        val outer = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)
        Box(outer, contentAlignment = Alignment.TopCenter) {
            SelectionContainer {
                LazyColumn(state = state, modifier = listModifier, contentPadding = contentPadding) {
                    items(count = index.rowCount) { row ->
                        val line = remember(content, row) { index.text(content.text, row) }
                        val tokens = remember(content, row, statesReady) { highlighter?.tokenize(row, line) }
                        val rowMatches = matchesByItem[row]
                        val currentHere = current?.takeIf { it.item == row }
                        val annotated = remember(line, tokens, rowMatches, currentHere, colors) {
                            highlighted(line, tokens, colors, rowMatches, currentHere)
                        }
                        Row(Modifier.fillMaxWidth().padding(end = 16.dp)) {
                            if (lineNumbers) {
                                DisableSelection {
                                    Text(
                                        text = if (index.isContinuation(row)) "" else index.line(row).toString(),
                                        modifier = Modifier.width(gutterWidth).padding(end = 12.dp),
                                        textAlign = TextAlign.End,
                                        color = colors.gutter,
                                        fontFamily = MonoFamily,
                                        fontSize = fontSize,
                                        lineHeight = lineHeight,
                                        softWrap = false,
                                    )
                                }
                            } else {
                                Box(Modifier.width(16.dp))
                            }
                            Text(
                                text = annotated,
                                color = colors.ink,
                                fontFamily = MonoFamily,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                softWrap = wrap,
                                modifier = if (wrap) Modifier.weight(1f) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Two fingers change the text size; one finger keeps scrolling the list underneath. */
fun Modifier.pinchToZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * A draggable thumb for long lists (a 200 000 line log cannot be flung through), with the
 * search matches marked along the track.
 */
@Composable
fun FastScroller(
    state: LazyListState,
    itemCount: Int,
    matches: List<Match>,
    current: Match?,
    modifier: Modifier = Modifier,
) {
    if (itemCount < 60) return
    val colors = Odv.colors
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(itemCount) {
        derivedStateOf {
            val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val maxFirst = (itemCount - visible).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
        }
    }
    val showTicks = matches.isNotEmpty()
    val tickColor = colors.accent.copy(alpha = 0.55f)
    val currentColor = colors.currentMatch
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(itemCount) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    val f = (change.position.y / size.height).coerceIn(0f, 1f)
                    scope.launch { state.scrollToItem((f * (itemCount - 1)).roundToInt()) }
                }
            }
            .drawBehind {
                if (!showTicks) return@drawBehind
                val h = size.height
                val w = 6.dp.toPx()
                val x = size.width - w - 2.dp.toPx()
                var lastY = -10f
                for (m in matches) {
                    val y = m.item.toFloat() / itemCount * h
                    if (y - lastY < 2f) continue
                    lastY = y
                    drawRect(tickColor, Offset(x, y), Size(w, 3.dp.toPx()))
                }
                if (current != null) {
                    drawRect(currentColor, Offset(x - 2.dp.toPx(), current.item.toFloat() / itemCount * h), Size(w + 2.dp.toPx(), 4.dp.toPx()))
                }
            },
    ) {
        val thumbHeight = 44.dp
        val travel = maxHeight - thumbHeight
        val active = dragging || state.isScrollInProgress
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (travel.toPx() * fraction).roundToInt()) }
                .padding(end = 3.dp)
                .size(width = if (dragging) 8.dp else 5.dp, height = thumbHeight)
                .clip(RoundedCornerShape(4.dp))
                .drawBehind { drawRect(if (active) colors.ink2 else colors.line) },
        )
    }
}
