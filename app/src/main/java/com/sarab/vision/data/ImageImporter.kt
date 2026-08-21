package com.sarab.vision.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SarabImages"

/** Longest edge of a stored photo, in pixels. */
private const val MAX_DIMENSION = 1440

private const val JPEG_QUALITY = 85

/**
 * Imports photos from the device gallery into app storage.
 *
 * Copying rather than storing the URI is deliberate: a gallery URI is a
 * revocable permission grant to someone else's file. It can be deleted,
 * moved, or lose permission on reboot, and the landmark would then show a
 * broken image with nothing to fall back on. A private copy is the only way
 * the survey stays intact offline.
 *
 * Decoding is done in two passes with `inSampleSize`. A modern phone photo is
 * 12MP, which decodes to roughly 48MB as ARGB_8888 -- on a device already
 * being killed under memory pressure, decoding one at full size is enough to
 * end the process. Subsampling keeps peak memory around a tenth of that.
 */
object ImageImporter {

    /**
     * @return the saved file name, or null if the image could not be read
     */
    fun importFromGallery(context: Context, uri: Uri, dir: File, baseName: String): String? {
        return try {
            // The elvis must NOT be attached to this `use` block. With
            // inJustDecodeBounds set, decodeStream ALWAYS returns null by
            // design -- it fills `bounds` instead -- so
            // `openInputStream(uri)?.use { decodeStream(...) } ?: return null`
            // bailed out on every single image ever picked. The stream's
            // nullability has to be checked on its own.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri)
            if (boundsStream == null) {
                Log.w(TAG, "Could not open $uri")
                return null
            }
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Could not read image bounds for $uri")
                return null
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
                // 565 halves the memory of a decode and is indistinguishable
                // for a photograph shown at thumbnail or card size.
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // Phone cameras store orientation in EXIF rather than rotating the
            // pixels, so a portrait shot arrives sideways without this.
            bitmap = applyExifRotation(context, uri, bitmap)
            bitmap = downscale(bitmap)

            if (!dir.exists()) dir.mkdirs()
            val fileName = "$baseName.jpg"
            FileOutputStream(File(dir, fileName)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()

            Log.i(TAG, "Imported $fileName from gallery")
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import image", e)
            null
        } catch (e: OutOfMemoryError) {
            // Worth catching explicitly: it is the realistic failure on this
            // device, and losing one photo beats losing the app.
            Log.e(TAG, "Out of memory importing image", e)
            null
        }
    }

    /** Loads a stored photo for display, subsampled to the requested width. */
    fun loadThumbnail(dir: File, fileName: String, targetWidth: Int = 360): Bitmap? {
        return try {
            val file = File(dir, fileName)
            if (!file.exists()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            val options = BitmapFactory.Options().apply {
                inSampleSize = if (bounds.outWidth > targetWidth) {
                    maxOf(1, bounds.outWidth / targetWidth)
                } else {
                    1
                }
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load thumbnail $fileName", e)
            null
        }
    }

    fun delete(dir: File, fileName: String) {
        runCatching { File(dir, fileName).delete() }
    }

    /** Largest power-of-two subsample that still exceeds the target size. */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_DIMENSION && h / 2 >= MAX_DIMENSION) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f

            if (degrees == 0f) return bitmap

            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(degrees) }, true
            )
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "Could not read EXIF orientation", e)
            bitmap
        }
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
