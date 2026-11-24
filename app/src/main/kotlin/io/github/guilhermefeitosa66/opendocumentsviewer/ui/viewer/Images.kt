package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.InputStream

sealed interface ImageResult {
    data class Loaded(val bitmap: ImageBitmap) : ImageResult
    /** On the web: never fetched (the app has no network access, by design). */
    data object Remote : ImageResult
    data object Unavailable : ImageResult
}

/** Loads an image referenced by the shown document (src, max width in px). */
val LocalImageLoader = staticCompositionLocalOf<suspend (String, Int) -> ImageResult> { { _, _ -> ImageResult.Unavailable } }

object ImageDecoding {
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun isRemote(src: String): Boolean {
        val s = src.trim().lowercase()
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") || s.startsWith("ftp://")
    }

    fun cached(key: String): ImageBitmap? = cache.get(key)

    /** Decodes at most [maxWidth] px wide (sampled), from a stream that can be opened twice. */
    fun decode(key: String, maxWidth: Int, open: () -> InputStream?): ImageBitmap? {
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // With inJustDecodeBounds the result is always null; only the bounds matter.
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth.coerceAtLeast(1)) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            bmp.asImageBitmap().also { cache.put(key, it) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** `data:image/png;base64,...` images embedded in the document. */
    fun dataUriBytes(src: String): ByteArray? {
        if (!src.startsWith("data:", ignoreCase = true)) return null
        val comma = src.indexOf(',')
        if (comma < 0 || !src.substring(0, comma).contains(";base64", ignoreCase = true)) return null
        return try { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) } catch (_: Exception) { null }
    }
}
