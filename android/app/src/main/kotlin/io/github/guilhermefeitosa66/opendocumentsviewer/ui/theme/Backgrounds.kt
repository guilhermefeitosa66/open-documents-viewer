package io.github.guilhermefeitosa66.opendocumentsviewer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Theme background images, decoded off the main thread, after the first frame, at the size
 * they are shown, and cached. Nothing is loaded for a theme without an image.
 */
object Backgrounds {
    // Full-screen images are ~5 MB each in RGB_565; thumbnails are tiny. Keep a few.
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }

    fun cached(asset: String, maxWidth: Int): ImageBitmap? = cache.get("$asset@${bucket(maxWidth)}")

    suspend fun load(context: Context, asset: String, maxWidth: Int): ImageBitmap? {
        val key = "$asset@${bucket(maxWidth)}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= bucket(maxWidth)) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = context.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) } ?: return@withContext null
                bitmap.asImageBitmap().also { cache.put(key, it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Few distinct sizes, so the cache is reused between screens. */
    private fun bucket(width: Int): Int = when {
        width <= 200 -> 200
        width <= 540 -> 540
        else -> 1080
    }
}

/**
 * The background of [asset] for a box [maxWidth] px wide, or null until it is ready (and when
 * [asset] is null). Waits one frame before loading, so it never delays what the user came for.
 */
@Composable
fun rememberBackground(asset: String?, maxWidth: Int): State<ImageBitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = asset?.let { Backgrounds.cached(it, maxWidth) }, asset, maxWidth) {
        if (asset == null) {
            value = null
            return@produceState
        }
        if (value == null) {
            withFrameNanos { }
            value = Backgrounds.load(context, asset, maxWidth)
        }
    }
}
