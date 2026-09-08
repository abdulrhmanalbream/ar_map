package com.sarab.vision.glasses.camera

import android.graphics.Bitmap
import android.media.Image

/** CPU-readable MediaCodec YUV planes, including padded row/pixel strides and crop rectangles. */
internal class YuvBitmapConverter {
    private var pixels = IntArray(0)

    fun convert(image: Image): Bitmap {
        val crop = image.cropRect
        require(image.planes.size >= 3 && crop.width() > 0 && crop.height() > 0)
        val width = crop.width().coerceAtMost(960)
        val height = (crop.height().toLong() * width / crop.width()).toInt().coerceAtLeast(1)
        if (pixels.size != width * height) pixels = IntArray(width * height)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val yBase = yBuffer.position()
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        var out = 0
        for (row in 0 until height) {
            val sourceY = crop.top + row * crop.height() / height
            val yRow = yBase + sourceY * y.rowStride
            val uRow = uBase + sourceY / 2 * u.rowStride
            val vRow = vBase + sourceY / 2 * v.rowStride
            for (col in 0 until width) {
                val sourceX = crop.left + col * crop.width() / width
                val luminance = ((yBuffer.get(yRow + sourceX * y.pixelStride).toInt() and 255) - 16).coerceAtLeast(0)
                val cb = (uBuffer.get(uRow + sourceX / 2 * u.pixelStride).toInt() and 255) - 128
                val cr = (vBuffer.get(vRow + sourceX / 2 * v.pixelStride).toInt() and 255) - 128
                val scaledY = 298 * luminance
                val red = ((scaledY + 409 * cr + 128) shr 8).coerceIn(0, 255)
                val green = ((scaledY - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 255)
                val blue = ((scaledY + 516 * cb + 128) shr 8).coerceIn(0, 255)
                pixels[out++] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        // createBitmap copies pixels. The working array can be reused while UI keeps this image.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
