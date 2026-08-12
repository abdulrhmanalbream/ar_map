package com.sarab.vision.data

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SarabPhoto"

/** Longest edge of a saved photo, in pixels. */
private const val MAX_DIMENSION = 1280

/** JPEG quality for saved photos. */
private const val JPEG_QUALITY = 85

/**
 * Saves a frame from the ARCore camera as a JPEG.
 *
 * ARCore hands out camera frames as YUV_420_888 [Image]s, which nothing on
 * Android will encode directly. We convert to NV21, let the platform's
 * [YuvImage] encoder produce a JPEG, then downscale.
 *
 * Downscaling matters more than it looks: a survey of a campus can easily
 * reach a hundred photos, and full-resolution frames would consume hundreds
 * of megabytes on a device we already know is memory-constrained. 1280px is
 * ample for recognising a building on screen.
 */
object PhotoCapture {

    /**
     * @return the saved file name, or null if the frame could not be encoded
     */
    fun saveFrame(image: Image, dir: File, baseName: String, rotationDegrees: Int = 0): String? {
        return try {
            val jpegBytes = yuvToJpeg(image) ?: return null

            var bitmap = android.graphics.BitmapFactory
                .decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            bitmap = downscale(bitmap)
            if (rotationDegrees != 0) {
                bitmap = rotate(bitmap, rotationDegrees)
            }

            if (!dir.exists()) dir.mkdirs()
            val fileName = "$baseName.jpg"
            FileOutputStream(File(dir, fileName)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()
            Log.i(TAG, "Saved photo $fileName")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save frame", e)
            null
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unexpected image format ${image.format}")
            return null
        }

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 expects Y followed by interleaved VU. Copying V before U is
        // what produces the correct colour order here.
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        return if (yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)) {
            out.toByteArray()
        } else {
            null
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
