package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.caverock.androidsvg.SVG
import java.nio.ByteBuffer
import kotlin.math.max

object ImageLoading {
    /** Longest side drawn; bigger images are decoded smaller (GPU texture limits, memory). */
    const val MAX_SIDE = 4096

    class Decoded(val drawable: Drawable, val size: IntSize)

    /** Decodes a raster image (animated GIF/WebP stay animated on Android 9+). Call off the main thread. */
    fun decode(bytes: ByteArray, resources: Resources): Decoded? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var w = 0
            var h = 0
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                w = info.size.width
                h = info.size.height
                val m = max(w, h)
                if (m > MAX_SIDE) {
                    val f = MAX_SIDE.toFloat() / m
                    decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }
            }
            Decoded(d, IntSize(w, h))
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.let { Decoded(BitmapDrawable(resources, it), IntSize(bounds.outWidth, bounds.outHeight)) }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Draws an SVG as a picture at its own size (or its viewBox, or 512 px). */
    fun svgDrawable(svg: SVG): Decoded {
        var w = svg.documentWidth
        var h = svg.documentHeight
        val box = svg.documentViewBox
        if ((w <= 0 || h <= 0) && box != null) { w = box.width(); h = box.height() }
        if (w <= 0 || h <= 0) { w = 512f; h = 512f }
        // Small icons are drawn bigger so they stay crisp when fitted to the screen.
        val scale = if (max(w, h) < 1024) 1024 / max(w, h) else 1f
        val pw = (w * scale).toInt().coerceAtLeast(1)
        val ph = (h * scale).toInt().coerceAtLeast(1)
        if (box == null) svg.setDocumentViewBox(0f, 0f, w, h)
        svg.setDocumentWidth(pw.toFloat())
        svg.setDocumentHeight(ph.toFloat())
        return Decoded(PictureDrawable(svg.renderToPicture(pw, ph)), IntSize(w.toInt(), h.toInt()))
    }
}

/**
 * A drawable fitted to the screen with pinch zoom, pan and double tap (fit / 2.5x). Animated
 * drawables run.
 */
@Composable
fun ZoomableDrawable(drawable: Drawable, modifier: Modifier = Modifier) {
    var scale by remember(drawable) { mutableFloatStateOf(1f) }
    var offset by remember(drawable) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1) / 2
        val maxY = box.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(drawable) {
                box = size
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.05f) {
                        scale = 1f; offset = Offset.Zero
                    } else {
                        scale = 2.5f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - p) * 1.5f, scale)
                    }
                })
            }
            .pointerInput(drawable) {
                box = size
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 10f)
                    offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan, scale)
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = false
                }
            },
            update = { view ->
                if (view.drawable !== drawable) {
                    view.setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
