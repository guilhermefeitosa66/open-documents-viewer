package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.pdf.PdfDocument
import io.github.guilhermefeitosa66.opendocumentsviewer.pdf.PdfLink
import io.github.guilhermefeitosa66.opendocumentsviewer.search.Match
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rendered pages, by page and width, sized by bytes (a share of the heap). */
object PdfBitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(doc: PdfDocument, page: Int, width: Int): Bitmap? = cache.get(key(doc, page, width))

    /** The best bitmap of the page already rendered at any width (shown while a sharper one renders). */
    fun any(doc: PdfDocument, page: Int): Bitmap? {
        val prefix = "${System.identityHashCode(doc)}:$page:"
        return cache.snapshot().entries.filter { it.key.startsWith(prefix) }.maxByOrNull { it.value.width }?.value
    }

    suspend fun render(doc: PdfDocument, page: Int, width: Int): Bitmap? {
        get(doc, page, width)?.let { return it }
        val size = doc.pageSizes.getOrNull(page) ?: return null
        val height = (width * size.height / size.width).roundToInt().coerceAtLeast(1)
        val bmp = try { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
        bmp.eraseColor(android.graphics.Color.WHITE)
        if (!doc.render(page, bmp, width, height)) return null
        cache.put(key(doc, page, width), bmp)
        return bmp
    }

    private fun key(doc: PdfDocument, page: Int, width: Int) = "${System.identityHashCode(doc)}:$page:$width"
}

/** A text selection on one page: character range, as the user drags. */
data class PdfSelection(val page: Int, val anchor: Int, val focus: Int) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)
}

/** PDF: continuous scrolling, pinch and double-tap zoom, search highlights, selection, links, thumbnails. */
@Composable
fun PdfView(
    f: Formatted.Pdf,
    session: DocumentSession,
    state: LazyListState,
    matchesByItem: Map<Int, List<Match>>,
    current: Match?,
    contentPadding: PaddingValues,
    onLink: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = Odv.colors
    val doc = f.doc
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val hScroll = rememberScrollState()

    if (session.pdfGrid) {
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp), Modifier.fillMaxSize().background(colors.paper2),
            contentPadding = PaddingValues(12.dp, contentPadding.calculateTopPadding() + 8.dp, 12.dp, contentPadding.calculateBottomPadding() + 96.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(doc.pageCount) { i ->
                Column(Modifier.clip(RoundedCornerShape(6.dp)).clickable { session.pdfGrid = false; scope.launch { state.scrollToItem(i) } }) {
                    val s = doc.pageSizes[i]
                    val bmp by produceState<Bitmap?>(PdfBitmaps.get(doc, i, THUMB), doc, i) { value = PdfBitmaps.render(doc, i, THUMB) }
                    Box(Modifier.fillMaxWidth().aspectRatio(s.width / s.height).background(Color.White).border(1.dp, colors.line)) {
                        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    }
                    Text("${i + 1}", Modifier.fillMaxWidth().padding(top = 2.dp), color = colors.ink2, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
        return
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.paper2)
            // Two fingers zoom; one finger is left to the list (scrolling).
            .pointerInput(doc) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                val before = session.pdfZoom
                                val after = (before * z).coerceIn(1f, MAX_ZOOM)
                                if (after != before) {
                                    session.pdfZoom = after
                                    val focusX = event.calculateCentroid().x
                                    scope.launch { hScroll.scrollTo(((hScroll.value + focusX) * after / before - focusX).roundToInt().coerceAtLeast(0)) }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewport = maxWidth
        val margin = 8.dp
        val pageWidth: Dp = (viewport - margin * 2) * session.pdfZoom
        val pageWidthPx = with(density) { pageWidth.toPx() }.roundToInt()
        // Render at the settled width (bucketed), not on every pinch frame.
        val renderWidth by produceState(bucket(pageWidthPx), pageWidthPx) { delay(250); value = bucket(pageWidthPx) }
        session.pdfViewport = with(density) { maxHeight.toPx() } to with(density) { (viewport - margin * 2).toPx() }

        Box(Modifier.fillMaxSize().horizontalScroll(hScroll, enabled = session.pdfZoom > 1.01f)) {
            LazyColumn(
                state = state,
                modifier = Modifier.width(maxOf(viewport, pageWidth + margin * 2)).fillMaxSize(),
                contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + margin, bottom = contentPadding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(margin),
            ) {
                items(doc.pageCount, key = { it }) { i ->
                    PdfPage(doc, i, pageWidth, renderWidth, matchesByItem[i].orEmpty(), current?.takeIf { it.item == i }, session, onLink, Modifier.padding(horizontal = margin))
                }
            }
        }

        // Page number while scrolling.
        val page by remember(state) { androidx.compose.runtime.derivedStateOf { state.firstVisibleItemIndex } }
        Surface(Modifier.align(Alignment.TopEnd).padding(top = contentPadding.calculateTopPadding() + 8.dp, end = 12.dp), color = colors.ink.copy(alpha = 0.7f), shape = RoundedCornerShape(50)) {
            Text("${page + 1} / ${doc.pageCount}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = colors.paper, fontSize = 12.sp)
        }

        session.pdfSelection?.let { sel ->
            Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 84.dp), color = colors.card, shape = RoundedCornerShape(50), shadowElevation = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            val text = PdfText.clean(doc.text(sel.page).let { t -> t.substring(sel.start.coerceIn(0, t.length), (sel.end + 1).coerceIn(0, t.length)) })
                            onCopy(text)
                            session.pdfSelection = null
                        }
                    }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { session.pdfSelection = null }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }

    // Keep the current search match on screen.
    LaunchedEffect(current) {
        val m = current ?: return@LaunchedEffect
        if (m.item !in 0 until doc.pageCount) return@LaunchedEffect
        // Scroll to the match itself, a third down the screen.
        val top = doc.textRects(m.item, m.start, m.end - m.start).minOfOrNull { it.top } ?: 0f
        val (viewH, w) = session.pdfViewport ?: (0f to 0f)
        val s = doc.pageSizes[m.item]
        val pageH = w * session.pdfZoom * s.height / s.width
        state.animateScrollToItem(m.item, (top * pageH - viewH / 3).roundToInt().coerceAtLeast(0))
    }
    LaunchedEffect(session.pdfJump) {
        session.pdfJump?.let { (page, offset) ->
            val h = with(density) { ((viewportWidthPx(session)) * doc.pageSizes[page].height / doc.pageSizes[page].width * session.pdfZoom).toInt() }
            state.scrollToItem(page, (h * offset).roundToInt())
            session.pdfJump = null
        }
    }
    // Remember where the reader is (the page) for the info line.
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.collectLatest { session.pdfPage = it } }
}

private fun viewportWidthPx(session: DocumentSession) = session.pdfViewport?.second ?: 1000f

private const val MAX_ZOOM = 5f
private const val THUMB = 240

/** Rendering widths in steps, so a pinch does not re-render every frame and the cache gets hits. */
private fun bucket(px: Int): Int {
    val step = 160
    return ((px + step - 1) / step * step).coerceIn(step, 4096)
}

@Composable
private fun PdfPage(
    doc: PdfDocument,
    index: Int,
    width: Dp,
    renderWidth: Int,
    matches: List<Match>,
    current: Match?,
    session: DocumentSession,
    onLink: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = Odv.colors
    val scope = rememberCoroutineScope()
    val pageSize = doc.pageSizes[index]
    val bmp by produceState(PdfBitmaps.get(doc, index, renderWidth) ?: PdfBitmaps.any(doc, index), doc, index, renderWidth) {
        PdfBitmaps.render(doc, index, renderWidth)?.let { value = it }
    }
    val highlights by produceState(emptyList<Pair<RectF, Boolean>>(), matches, current) {
        value = matches.flatMap { m -> doc.textRects(index, m.start, m.end - m.start).map { it to (m == current) } }
    }
    val selection = session.pdfSelection?.takeIf { it.page == index }
    val selRects by produceState(emptyList<RectF>(), selection) {
        value = selection?.let { doc.textRects(index, it.start, it.end - it.start + 1) }.orEmpty()
    }
    Box(
        modifier
            .width(width)
            .height(width * (pageSize.height / pageSize.width))
            .background(Color.White)
            .pointerInput(doc, index) {
                detectTapGestures(
                    onDoubleTap = { session.pdfZoom = if (session.pdfZoom > 1.2f) 1f else 2.5f },
                    onTap = { o ->
                        if (session.pdfSelection != null) { session.pdfSelection = null; return@detectTapGestures }
                        scope.launch {
                            when (val l = doc.linkAt(index, o.x / this@pointerInput.size.width, o.y / this@pointerInput.size.height)) {
                                is PdfLink.Page -> session.pdfJump = l.page to l.offset
                                is PdfLink.Web -> onLink(l.uri)
                                null -> Unit
                            }
                        }
                    },
                )
            }
            .pointerInput(doc, index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        scope.launch {
                            val c = doc.charIndexAt(index, o.x / size.width, o.y / size.height)
                            if (c >= 0) {
                                val (a, b) = PdfText.wordAt(doc.text(index), c)
                                session.pdfSelection = PdfSelection(index, a, b)
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        val p = change.position
                        scope.launch {
                            val c = doc.charIndexAt(index, p.x / size.width, p.y / size.height, 16f)
                            val s = session.pdfSelection
                            if (c >= 0 && s != null && s.page == index) session.pdfSelection = s.copy(focus = c)
                        }
                    },
                )
            },
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (highlights.isNotEmpty() || selRects.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                fun rect(r: RectF, c: Color) = drawRect(c, Offset(r.left * this.size.width, r.top * this.size.height), Size(r.width() * this.size.width, r.height() * this.size.height))
                for ((r, cur) in highlights) rect(r, if (cur) Color(0x88FF8C00) else Color(0x66FFD54F))
                for (r in selRects) rect(r, colors.accent.copy(alpha = 0.3f))
            }
        }
    }
}

/** PDFium's page text, made clean for copying and searching. */
object PdfText {
    /** PDFium marks a line-end hyphen as U+0002 and ends lines with \r\n. */
    fun clean(s: String): String = s.replace("\u0002\r\n", "").replace("\u0002", "").replace("\r\n", "\n").replace('\r', '\n')

    /** The word (non-space run) around character [c]: (first, last) indexes. */
    fun wordAt(s: String, c: Int): Pair<Int, Int> {
        if (c !in s.indices) return c to c
        var a = c
        var b = c
        while (a > 0 && !s[a - 1].isWhitespace()) a--
        while (b < s.length - 1 && !s[b + 1].isWhitespace()) b++
        return a to b
    }
}
