package io.github.guilhermefeitosa66.opendocumentsviewer.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** PDFium is not thread-safe, even across documents: every call runs on this one thread. */
object PdfiumThread {
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdfium").apply { priority = Thread.NORM_PRIORITY + 1 } }.asCoroutineDispatcher()
    }
}
