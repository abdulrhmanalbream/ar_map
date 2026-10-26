package com.sarab.vision.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val VISION_PHOTO_EDGE = 1280

/** Never writes a photo, retains a URI permission, or logs image/provider metadata. */
internal suspend fun decodeVisionPhoto(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    var owned: Bitmap? = null
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        if (mime != null && !mime.startsWith("image/")) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return@withContext null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        // decodeStream returns null when reading bounds, so inspect those bounds separately.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        ensureActive()
        val options = BitmapFactory.Options().apply {
            inSampleSize = visionPhotoSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext null
        owned = bitmap
        ensureActive()
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val values = visionPhotoOrientation(orientation)
        if (orientation in 2..8) {
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { setValues(floatArrayOf(values[0], values[1], 0f, values[2], values[3], 0f, 0f, 0f, 1f)) }, true)
            if (transformed !== bitmap) bitmap.recycle()
            bitmap = transformed
            owned = bitmap
        }
        ensureActive()
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > VISION_PHOTO_EDGE) {
            val scale = VISION_PHOTO_EDGE.toDouble() / longest
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
            owned = bitmap
        }
        if (bitmap.isMutable) {
            val immutable = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            bitmap.recycle()
            bitmap = immutable
            owned = bitmap
        }
        ensureActive()
        owned = null // Published bitmap belongs to the UI; only private intermediates may be recycled.
        bitmap
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } finally {
        owned?.takeUnless { it.isRecycled }?.recycle()
    }
}

/** Bound both tall and very wide pictures before pixel allocation. */
internal fun visionPhotoSampleSize(width: Int, height: Int, edge: Int = VISION_PHOTO_EDGE): Int {
    require(width > 0 && height > 0 && edge > 0)
    var sample = 1
    while (maxOf(width, height).toLong() / sample > edge.toLong() * 2) sample *= 2
    return sample
}

/** Row-major linear transform; createBitmap normalizes the transformed rectangle's origin. */
internal fun visionPhotoOrientation(exif: Int): FloatArray = when (exif) {
    2 -> floatArrayOf(-1f, 0f, 0f, 1f)
    3 -> floatArrayOf(-1f, 0f, 0f, -1f)
    4 -> floatArrayOf(1f, 0f, 0f, -1f)
    5 -> floatArrayOf(0f, 1f, 1f, 0f)
    6 -> floatArrayOf(0f, -1f, 1f, 0f)
    7 -> floatArrayOf(0f, -1f, -1f, 0f)
    8 -> floatArrayOf(0f, 1f, -1f, 0f)
    else -> floatArrayOf(1f, 0f, 0f, 1f)
}
