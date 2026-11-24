package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Presentation
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.Slide
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.SlideParagraph
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.SlideShape
import io.github.guilhermefeitosa66.opendocumentsviewer.formats.ZipFiles
import io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme.Odv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pictures of a presentation, decoded once from the archive, sampled to at most [MAX] px. */
class SlideImages(private val archive: ByteArray?) {
    private val cache = mutableStateMapOf<String, ImageBitmap?>()
    private val pending = HashSet<String>()

    fun get(path: String): ImageBitmap? = cache[path]

    suspend fun load(path: String) {
        if (cache.containsKey(path) || !pending.add(path)) return
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val data = archive?.let { ZipFiles.resource(it, path) } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX) sample *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
        cache[path] = bmp
    }

    companion object { const val MAX = 1600 }
}

/** One slide drawn to fit its box: background, shapes, pictures, tables and text. */
@Composable
fun SlideCanvas(pres: Presentation, slide: Slide, images: SlideImages, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val paths = remember(slide) { listOfNotNull(slide.backgroundImage) + slide.shapes.mapNotNull { it.image } }
    LaunchedEffect(slide) { for (p in paths) images.load(p) }
    Canvas(modifier.aspectRatio(pres.width.toFloat() / pres.height).clipToBounds()) {
        val scale = size.width / pres.width
        drawRect(Color(slide.background.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt()))
        slide.backgroundImage?.let { images.get(it) }?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
        for (s in slide.shapes) drawShape(s, scale, images, measurer, density)
    }
}

private fun DrawScope.drawShape(s: SlideShape, scale: Float, images: SlideImages, measurer: TextMeasurer, density: Density) {
    val x = s.x * scale; val y = s.y * scale; val w = s.w * scale; val h = s.h * scale
    rotate(s.rotation, pivot = Offset(x + w / 2, y + h / 2)) {
        val stroke = (s.lineWidth * 12700 * scale).coerceAtLeast(1f)
        when (s.kind) {
            "line" -> if (s.line != 0) drawLine(Color(s.line), Offset(x, y), Offset(x + w, y + h), strokeWidth = stroke)
            "ellipse" -> {
                if (s.fill != 0) drawOval(Color(s.fill), Offset(x, y), Size(w, h))
                if (s.line != 0) drawOval(Color(s.line), Offset(x, y), Size(w, h), style = Stroke(stroke))
            }
            "roundRect" -> {
                val r = CornerRadius(minOf(w, h) * 0.16f)
                if (s.fill != 0) drawRoundRect(Color(s.fill), Offset(x, y), Size(w, h), r)
                if (s.line != 0) drawRoundRect(Color(s.line), Offset(x, y), Size(w, h), r, style = Stroke(stroke))
            }
            "image" -> s.image?.let { images.get(it) }?.let { img ->
                drawImage(img, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)))
            }
            "table" -> drawTable(s, x, y, w, scale, measurer, density)
            else -> {
                if (s.fill != 0) drawRect(Color(s.fill), Offset(x, y), Size(w, h))
                if (s.line != 0) drawRect(Color(s.line), Offset(x, y), Size(w, h), style = Stroke(stroke))
            }
        }
        if (s.paragraphs.isNotEmpty() && s.kind != "table") {
            val left = x + s.insets[0] * scale; val top = y + s.insets[1] * scale
            val innerW = (w - (s.insets[0] + s.insets[2]) * scale).coerceAtLeast(1f)
            val innerH = (h - (s.insets[1] + s.insets[3]) * scale).coerceAtLeast(1f)
            val layout = layoutText(s.paragraphs, innerW, scale, measurer, density, s.fill)
            val dy = when (s.anchor) { 'm' -> (innerH - layout.size.height) / 2; 'b' -> innerH - layout.size.height; else -> 0f }
            clipRect(x - w, y - h, x + 2 * w, y + 2 * h) { drawText(layout, topLeft = Offset(left, top + dy)) }
        }
    }
}

private fun DrawScope.drawTable(s: SlideShape, x: Float, y: Float, w: Float, scale: Float, measurer: TextMeasurer, density: Density) {
    val cols = s.colWidths.ifEmpty { List(s.rows.maxOfOrNull { it.size } ?: 1) { s.w / maxOf(1, s.rows.maxOfOrNull { it.size } ?: 1) } }
    val pad = 45720 * scale
    var cy = y
    for (row in s.rows) {
        var cx = x
        val layouts = row.mapIndexed { i, cell -> layoutText(cell, ((cols.getOrNull(i) ?: 0) * scale - 2 * pad).coerceAtLeast(1f), scale, measurer, density, 0) }
        val rowH = (layouts.maxOfOrNull { it.size.height }?.toFloat() ?: 0f) + 2 * pad
        row.indices.forEach { i ->
            val cw = (cols.getOrNull(i) ?: 0) * scale
            drawRect(Color(0xFF808080), Offset(cx, cy), Size(cw, rowH), style = Stroke(1f))
            drawText(layouts[i], topLeft = Offset(cx + pad, cy + pad))
            cx += cw
        }
        cy += rowH
    }
}

private fun layoutText(paras: List<SlideParagraph>, width: Float, scale: Float, measurer: TextMeasurer, density: Density, fill: Int): TextLayoutResult {
    val dark = fill != 0 && luminance(fill) < 0.4f
    val text = buildAnnotatedString {
        paras.forEachIndexed { i, p ->
            val indent = with(density) { (p.level * 457200 * scale * 0.6f).toSp() }
            withStyle(ParagraphStyle(
                textAlign = when (p.align) { 'c' -> TextAlign.Center; 'r' -> TextAlign.End; 'j' -> TextAlign.Justify; else -> TextAlign.Start },
                textIndent = androidx.compose.ui.text.style.TextIndent(firstLine = indent, restLine = indent),
            )) {
                val firstSize = p.runs.firstOrNull()?.size ?: 18f
                p.bullet?.let { b ->
                    withStyle(SpanStyle(fontSize = with(density) { (firstSize * 12700 * scale).toSp() }, color = runColor(p.runs.firstOrNull()?.color ?: 0, dark))) { append("$b ") }
                }
                for (r in p.runs) {
                    withStyle(
                        SpanStyle(
                            fontSize = with(density) { (r.size * 12700 * scale).coerceAtLeast(1f).toSp() },
                            fontWeight = if (r.bold) FontWeight.Bold else null,
                            fontStyle = if (r.italic) FontStyle.Italic else null,
                            fontFamily = if (r.mono) FontFamily.Monospace else null,
                            textDecoration = when {
                                r.underline && r.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                                r.underline -> TextDecoration.Underline
                                r.strike -> TextDecoration.LineThrough
                                else -> null
                            },
                            color = runColor(r.color, dark),
                        )
                    ) { append(r.text) }
                }
                if (p.runs.all { it.text.isEmpty() }) withStyle(SpanStyle(fontSize = with(density) { (firstSize * 12700 * scale).toSp() })) { append(" ") }
            }
            if (i < paras.size - 1) Unit
        }
    }
    return measurer.measure(text, constraints = Constraints.fixedWidth(width.toInt().coerceAtLeast(1)), softWrap = true)
}

private fun runColor(c: Int, darkFill: Boolean): Color = if (c != 0) Color(c) else if (darkFill) Color.White else Color.Black

private fun luminance(argb: Int): Float = (0.2126f * ((argb shr 16) and 0xFF) + 0.7152f * ((argb shr 8) and 0xFF) + 0.0722f * (argb and 0xFF)) / 255f

/** A presentation: swipe between slides, the notes below, a grid of thumbnails, full-screen presenting. */
@Composable
fun SlideView(pres: Presentation, session: DocumentSession, archive: ByteArray?, current: io.github.guilhermefeitosa66.opendocumentsviewer.search.Match?, contentPadding: PaddingValues, reading: ReadingPrefs) {
    val colors = Odv.colors
    val images = remember(archive) { SlideImages(archive) }
    val pager = rememberPagerState(initialPage = session.slideIndex.coerceIn(0, maxOf(0, pres.slides.size - 1))) { pres.slides.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { session.slideIndex = pager.currentPage }
    LaunchedEffect(current) { current?.let { if (it.item in pres.slides.indices) pager.animateScrollToPage(it.item) } }
    LaunchedEffect(session.slideJump) { session.slideJump?.let { pager.scrollToPage(it); session.slideJump = null } }

    if (session.presenting) {
        Presenting(pres, images, pager.currentPage, onPage = { scope.launch { pager.scrollToPage(it) } }, onExit = { session.presenting = false; reading.onFullScreen(false) })
        return
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val slide = pres.slides.getOrNull(pager.currentPage)
            Text(
                "${pager.currentPage + 1} / ${pres.slides.size}" + (slide?.title?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "") +
                    (if (slide?.hidden == true) "  ·  " + stringResource(R.string.slide_hidden) else ""),
                Modifier.weight(1f).padding(start = 8.dp), color = colors.ink2, fontSize = 14.sp, maxLines = 1,
            )
            IconButton(onClick = { session.slideGrid = !session.slideGrid }) { Icon(painterResource(R.drawable.ic_outline), stringResource(R.string.slide_grid)) }
            IconButton(onClick = { session.presenting = true; reading.onFullScreen(true) }) { Icon(painterResource(R.drawable.ic_present), stringResource(R.string.slide_present)) }
        }
        if (session.slideGrid) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, contentPadding.calculateBottomPadding() + 96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(pres.slides.size) { i ->
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { pager.scrollToPage(i) }; session.slideGrid = false }) {
                        SlideCanvas(pres, pres.slides[i], images, Modifier.fillMaxWidth().border(if (i == pager.currentPage) 3.dp else 1.dp, if (i == pager.currentPage) colors.accent else colors.line))
                        Text("${i + 1}", Modifier.padding(top = 2.dp).fillMaxWidth(), color = colors.ink2, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            return@Column
        }
        HorizontalPager(pager, Modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                SlideCanvas(pres, pres.slides[page], images, Modifier.fillMaxWidth().border(1.dp, colors.line))
            }
        }
        val notes = pres.slides.getOrNull(pager.currentPage)?.notes.orEmpty()
        if (notes.isNotBlank()) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 96.dp)) {
                    Text(stringResource(R.string.slide_notes), color = colors.accentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.padding(2.dp))
                    Text(notes, color = colors.ink, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Full screen, landscape: one slide on black; tap the right side for the next one, the left for the previous one. */
@Composable
private fun Presenting(pres: Presentation, images: SlideImages, page: Int, onPage: (Int) -> Unit, onExit: () -> Unit) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = before ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(onBack = onExit)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(page) {
            detectTapGestures { o -> if (o.x > size.width / 2) onPage((page + 1).coerceAtMost(pres.slides.size - 1)) else onPage((page - 1).coerceAtLeast(0)) }
        },
        contentAlignment = Alignment.Center,
    ) {
        val ratio = pres.width.toFloat() / pres.height
        val m = if (maxWidth / maxHeight > ratio) Modifier.heightIn(max = maxHeight).width(maxHeight * ratio) else Modifier.fillMaxWidth()
        pres.slides.getOrNull(page)?.let { SlideCanvas(pres, it, images, m) }
    }
}
