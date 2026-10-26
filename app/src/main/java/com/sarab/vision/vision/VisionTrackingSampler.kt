package com.sarab.vision.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Worker-only sampling; the full-resolution camera preview never waits for matching. */
internal fun sampleVisionTrackingFrame(bitmap: Bitmap, at: Long, sessionId: Long): VisionTrackingFrame {
    val ratio = 160.0 / maxOf(bitmap.width, bitmap.height)
    val width = (bitmap.width * ratio).roundToInt().coerceIn(16, 160)
    val height = (bitmap.height * ratio).roundToInt().coerceIn(16, 160)
    val small = Bitmap.createScaledBitmap(bitmap, width, height, false)
    try {
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = ByteArray(pixels.size) { index ->
            val color = pixels[index]
            ((77 * ((color shr 16) and 255) + 150 * ((color shr 8) and 255) +
                29 * (color and 255)) shr 8).toByte()
        }
        return VisionTrackingFrame(width, height, luma, at, sessionId)
    } finally {
        if (small !== bitmap) small.recycle()
    }
}
