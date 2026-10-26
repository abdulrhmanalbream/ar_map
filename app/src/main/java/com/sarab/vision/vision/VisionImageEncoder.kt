package com.sarab.vision.vision

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

internal data class EncodedVisionImage(val preview: Bitmap, val base64: String, val signature: VisionFrameSignature)

/** Samples without allocating another bitmap. Called on a background dispatcher. */
internal fun visionFrameSignature(source: Bitmap): VisionFrameSignature {
    require(!source.isRecycled && source.width > 0 && source.height > 0)
    return VisionFrameSignature(List(16 * 12) { index ->
        val x = (((index % 16) + 0.5) * source.width / 16).toInt().coerceIn(0, source.width - 1)
        val y = (((index / 16) + 0.5) * source.height / 12).toInt().coerceIn(0, source.height - 1)
        val color = source.getPixel(x, y)
        (((color shr 16 and 255) * 77 + (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8)
    })
}

/** Only creates memory-resident, bounded JPEGs. Never mutates or recycles an Eye-owned bitmap. */
internal suspend fun encodeVisionImage(source: Bitmap): EncodedVisionImage = withContext(Dispatchers.Default) {
    require(!source.isRecycled && source.width > 0 && source.height > 0)
    var longEdge = minOf(960, maxOf(source.width, source.height))
    while (longEdge >= 1) {
        coroutineContext.ensureActive()
        val scale = longEdge.toDouble() / maxOf(source.width, source.height)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        // createScaledBitmap may return the original object when no resize is needed.
        val owned = scaled.copy(Bitmap.Config.ARGB_8888, false) ?: error("image copy")
        if (scaled !== source) scaled.recycle()
        try {
            for (quality in listOf(78, 65, 50, 38)) {
                coroutineContext.ensureActive()
                val bytes = ByteArrayOutputStream().use { output ->
                    check(owned.compress(Bitmap.CompressFormat.JPEG, quality, output))
                    output.toByteArray()
                }
                if (bytes.size <= 400 * 1024) {
                    return@withContext EncodedVisionImage(owned, Base64.encodeToString(bytes, Base64.NO_WRAP), visionFrameSignature(source))
                }
            }
        } catch (e: Exception) {
            owned.recycle()
            throw e
        }
        owned.recycle()
        if (longEdge <= 320) error("image too large")
        longEdge = (longEdge * 0.75).roundToInt().coerceAtLeast(320)
    }
    error("image encoding")
}

