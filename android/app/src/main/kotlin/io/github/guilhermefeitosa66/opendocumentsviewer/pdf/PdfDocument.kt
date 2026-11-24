package io.github.guilhermefeitosa66.opendocumentsviewer.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.withContext

class PdfOpenException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { FILE, FORMAT, PASSWORD, SECURITY, UNKNOWN }
}

/** One entry of the document outline, in reading order. */
data class PdfOutlineItem(val title: String, val level: Int, val page: Int, val offset: Float)

/** What a tap on a link leads to. */
sealed interface PdfLink {
    /** A place in this document: page index and normalized vertical offset. */
    data class Page(val page: Int, val offset: Float) : PdfLink
    data class Web(val uri: String) : PdfLink
}

/** Size of a page in points, with the page rotation applied. */
data class PdfPageSize(val width: Float, val height: Float)

/**
 * An open PDF, read only. Copied from Open PDF Viewer (same author) and trimmed to viewing. All work
 * runs on [PdfiumThread]; loaded pages and their text are kept in a small LRU.
 */
class PdfDocument private constructor(private val handle: Long) {
    val pageCount: Int
    val pageSizes: List<PdfPageSize>

    init {
        val raw = PdfiumNative.pageSizes(handle)
        pageCount = raw.size / 2
        pageSizes = List(pageCount) { PdfPageSize(raw[it * 2], raw[it * 2 + 1]) }
    }

    private class LoadedPage(val doc: Long, val page: Long) {
        var text: Long = 0L
        fun close() {
            if (text != 0L) PdfiumNative.closeText(text)
            PdfiumNative.closePage(doc, page)
        }
    }

    private val pages = object : LinkedHashMap<Int, LoadedPage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LoadedPage>): Boolean {
            if (size > MAX_LOADED_PAGES) { eldest.value.close(); return true }
            return false
        }
    }
    private val textCache = HashMap<Int, String>()
    @Volatile var closed = false
        private set

    private fun page(index: Int): LoadedPage? {
        if (closed) return null
        pages[index]?.let { return it }
        val p = PdfiumNative.loadPage(handle, index)
        if (p == 0L) return null
        return LoadedPage(handle, p).also { pages[index] = it }
    }

    private fun textOf(lp: LoadedPage): Long {
        if (lp.text == 0L) lp.text = PdfiumNative.loadText(lp.page)
        return lp.text
    }

    /** Renders page [index] scaled to [pageWidthPx] x [pageHeightPx], top-left at (-offsetX, -offsetY) of [bitmap]. */
    suspend fun render(index: Int, bitmap: Bitmap, pageWidthPx: Int, pageHeightPx: Int, offsetX: Int = 0, offsetY: Int = 0): Boolean =
        withContext(PdfiumThread.dispatcher) {
            val lp = page(index) ?: return@withContext false
            PdfiumNative.renderPage(handle, lp.page, bitmap, -offsetX, -offsetY, pageWidthPx, pageHeightPx)
        }

    /** Text of the page; string index i is character i of the page. */
    suspend fun text(index: Int): String = withContext(PdfiumThread.dispatcher) {
        textCache[index]?.let { return@withContext it }
        val lp = page(index) ?: return@withContext ""
        val t = textOf(lp)
        (if (t == 0L) "" else PdfiumNative.pageText(t)).also { textCache[index] = it }
    }

    /** Normalized (0..1, top-left origin) rectangles covering [start, start + count). */
    suspend fun textRects(index: Int, start: Int, count: Int): List<RectF> = withContext(PdfiumThread.dispatcher) {
        if (count <= 0) return@withContext emptyList()
        val lp = page(index) ?: return@withContext emptyList()
        val t = textOf(lp)
        if (t == 0L) return@withContext emptyList()
        val raw = PdfiumNative.textRects(lp.page, t, start, count)
        List(raw.size / 4) { RectF(raw[it * 4], raw[it * 4 + 1], raw[it * 4 + 2], raw[it * 4 + 3]) }
    }

    /** Character index at a normalized point of the page, or -1. */
    suspend fun charIndexAt(index: Int, x: Float, y: Float, tolerancePt: Float = 8f): Int = withContext(PdfiumThread.dispatcher) {
        val lp = page(index) ?: return@withContext -1
        val t = textOf(lp)
        if (t == 0L) -1 else PdfiumNative.charIndexAt(lp.page, t, x, y, tolerancePt)
    }

    suspend fun outline(): List<PdfOutlineItem> = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext emptyList()
        val raw = PdfiumNative.outline(handle)
        @Suppress("UNCHECKED_CAST") val titles = raw[0] as Array<String>
        val levels = raw[1] as IntArray
        val pages = raw[2] as IntArray
        val ys = raw[3] as FloatArray
        List(titles.size) { i -> PdfOutlineItem(titles[i].trim(), levels[i], pages[i], offsetFor(pages[i], ys[i])) }
    }

    /** Title and author stored in the document (empty when absent). */
    suspend fun titleAndAuthor(): Pair<String, String> = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext "" to ""
        @Suppress("UNCHECKED_CAST") val m = (PdfiumNative.documentInfo(handle)[0] as Array<String>).map { it.trim() }
        m.getOrElse(0) { "" } to m.getOrElse(1) { "" }
    }

    suspend fun linkAt(index: Int, x: Float, y: Float): PdfLink? = withContext(PdfiumThread.dispatcher) {
        val lp = page(index) ?: return@withContext null
        val s = PdfiumNative.linkAt(handle, lp.page, x, y) ?: return@withContext null
        when {
            s.startsWith("u:") -> PdfLink.Web(s.substring(2))
            s.startsWith("p:") -> {
                val parts = s.split(':')
                val target = parts.getOrNull(1)?.toIntOrNull() ?: return@withContext null
                if (target !in 0 until pageCount) return@withContext null
                PdfLink.Page(target, offsetFor(target, parts.getOrNull(2)?.toFloatOrNull() ?: Float.NaN))
            }
            else -> null
        }
    }

    private fun offsetFor(page: Int, y: Float): Float {
        if (y.isNaN() || page !in 0 until pageCount) return 0f
        return (1f - y / pageSizes[page].height).coerceIn(0f, 1f)
    }

    /** Closes on the PDFium thread; queued work for this document finishes first. */
    suspend fun close() = withContext(PdfiumThread.dispatcher) {
        if (closed) return@withContext
        closed = true
        pages.values.forEach { it.close() }
        pages.clear()
        textCache.clear()
        PdfiumNative.closeDocument(handle)
    }

    companion object {
        private const val MAX_LOADED_PAGES = 8

        private fun check(h: Long): PdfDocument {
            // A handle is an opaque pointer: on arm64 it may be negative (tagged pointers); only 0 fails.
            if (h == 0L) throw PdfOpenException(
                when (PdfiumNative.lastOpenError()) {
                    2 -> PdfOpenException.Reason.FILE
                    3 -> PdfOpenException.Reason.FORMAT
                    4 -> PdfOpenException.Reason.PASSWORD
                    5 -> PdfOpenException.Reason.SECURITY
                    else -> PdfOpenException.Reason.UNKNOWN
                }
            )
            return PdfDocument(h)
        }

        /** From a seekable descriptor (duplicated: the caller may close [pfd] afterwards). */
        suspend fun open(pfd: ParcelFileDescriptor, password: String?): PdfDocument =
            withContext(PdfiumThread.dispatcher) { check(PdfiumNative.openDocument(pfd.fd, password)) }

        /** From bytes in memory (providers that only give a pipe). */
        suspend fun open(bytes: ByteArray, password: String?): PdfDocument =
            withContext(PdfiumThread.dispatcher) { check(PdfiumNative.openMemory(bytes, password)) }
    }
}
