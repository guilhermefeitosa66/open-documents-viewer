package io.github.guilhermefeitosa66.opendocumentsviewer.pdf

import android.graphics.Bitmap

/** JNI entry points (src/main/cpp). Loaded the first time a PDF opens. Call only from [PdfiumThread]. */
internal object PdfiumNative {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("pdfium_jni")
        init()
    }

    external fun init()
    external fun openDocument(fd: Int, password: String?): Long
    external fun openMemory(bytes: ByteArray, password: String?): Long
    external fun lastOpenError(): Int
    external fun closeDocument(handle: Long)
    external fun pageCount(handle: Long): Int
    external fun pageSizes(handle: Long): FloatArray
    external fun loadPage(handle: Long, index: Int): Long
    external fun closePage(handle: Long, page: Long)
    external fun renderPage(handle: Long, page: Long, bitmap: Bitmap, startX: Int, startY: Int, sizeX: Int, sizeY: Int): Boolean
    external fun loadText(page: Long): Long
    external fun closeText(text: Long)
    external fun pageText(text: Long): String
    external fun textRects(page: Long, text: Long, start: Int, count: Int): FloatArray
    external fun charIndexAt(page: Long, text: Long, x: Float, y: Float, tolerance: Float): Int
    external fun outline(handle: Long): Array<Any>
    external fun linkAt(handle: Long, page: Long, x: Float, y: Float): String?
    external fun documentInfo(handle: Long): Array<Any>
}
