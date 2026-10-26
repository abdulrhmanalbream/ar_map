package com.sarab.vision.phonecamera

import java.nio.ByteBuffer

internal data class CameraCrop(val left: Int, val top: Int, val width: Int, val height: Int)
internal data class CameraPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
internal data class CameraFrameSize(val width: Int, val height: Int)
internal data class ArgbCameraFrame(val width: Int, val height: Int, val pixels: IntArray)

/** Clockwise rotation of raw back-camera pixels, following CameraX CameraOrientationUtil. */
internal fun backCameraRotation(sensorDegrees: Int, displayDegrees: Int): Int {
    require(sensorDegrees in listOf(0, 90, 180, 270) && displayDegrees in listOf(0, 90, 180, 270))
    return (sensorDegrees - displayDegrees + 360) % 360
}

internal fun boundedCameraSize(width: Int, height: Int, clockwise: Int, maxLongEdge: Int = 960): CameraFrameSize {
    require(width > 0 && height > 0 && clockwise in listOf(0, 90, 180, 270) && maxLongEdge in 1..960)
    val longest = maxOf(width, height)
    val outputLong = minOf(maxLongEdge, longest)
    val scaledWidth = (width.toLong() * outputLong / longest).toInt().coerceAtLeast(1)
    val scaledHeight = (height.toLong() * outputLong / longest).toInt().coerceAtLeast(1)
    return if (clockwise % 180 == 0) CameraFrameSize(scaledWidth, scaledHeight) else CameraFrameSize(scaledHeight, scaledWidth)
}

/**
 * Crop coordinates refer to the full image, including odd crop origins. Plane positions
 * and limits are respected; neither shared UV buffers nor missing final-row padding are
 * treated as tightly packed. The returned scratch pixels are valid until the next call.
 */
internal class PhoneYuvConverter {
    private var pixels = IntArray(0)

    fun convert(imageWidth: Int, imageHeight: Int, crop: CameraCrop, y: CameraPlane, u: CameraPlane,
                v: CameraPlane, clockwise: Int, maxLongEdge: Int = 960): ArgbCameraFrame {
        require(imageWidth in 1..8192 && imageHeight in 1..8192)
        require(crop.left >= 0 && crop.top >= 0 && crop.width > 0 && crop.height > 0 &&
            crop.left.toLong() + crop.width <= imageWidth && crop.top.toLong() + crop.height <= imageHeight)
        validatePlane(y, imageWidth, imageHeight)
        validatePlane(u, (imageWidth + 1) / 2, (imageHeight + 1) / 2)
        validatePlane(v, (imageWidth + 1) / 2, (imageHeight + 1) / 2)
        val size = boundedCameraSize(crop.width, crop.height, clockwise, maxLongEdge)
        if (pixels.size != size.width * size.height) pixels = IntArray(size.width * size.height)
        val width = if (clockwise % 180 == 0) size.width else size.height
        val height = if (clockwise % 180 == 0) size.height else size.width
        val yBase = y.buffer.position()
        val uBase = u.buffer.position()
        val vBase = v.buffer.position()
        val sourceColumns = IntArray(width) { crop.left + ((2L * it + 1) * crop.width / (2L * width)).toInt() }
        for (row in 0 until height) {
            val sourceY = crop.top + ((2L * row + 1) * crop.height / (2L * height)).toInt()
            val yRow = yBase + sourceY * y.rowStride
            val uRow = uBase + sourceY / 2 * u.rowStride
            val vRow = vBase + sourceY / 2 * v.rowStride
            for (column in 0 until width) {
                val sourceX = sourceColumns[column]
                val luminance = y.buffer.get(yRow + sourceX * y.pixelStride).toInt() and 255
                val cb = (u.buffer.get(uRow + sourceX / 2 * u.pixelStride).toInt() and 255) - 128
                val cr = (v.buffer.get(vRow + sourceX / 2 * v.pixelStride).toInt() and 255) - 128
                // Camera2 defaults to the JFIF/BT.601 full-range YUV transform.
                val red = (luminance + ((359 * cr + 128) shr 8)).coerceIn(0, 255)
                val green = (luminance - ((88 * cb + 183 * cr + 128) shr 8)).coerceIn(0, 255)
                val blue = (luminance + ((454 * cb + 128) shr 8)).coerceIn(0, 255)
                val destination = when (clockwise) {
                    90 -> column * size.width + height - 1 - row
                    180 -> (height - 1 - row) * size.width + width - 1 - column
                    270 -> (width - 1 - column) * size.width + row
                    else -> row * size.width + column
                }
                pixels[destination] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return ArgbCameraFrame(size.width, size.height, pixels)
    }

    private fun validatePlane(plane: CameraPlane, width: Int, height: Int) {
        require(plane.rowStride > 0 && plane.pixelStride > 0)
        require(plane.rowStride.toLong() >= (width - 1L) * plane.pixelStride + 1)
        val last = plane.buffer.position().toLong() + (height - 1L) * plane.rowStride + (width - 1L) * plane.pixelStride
        require(last < plane.buffer.limit()) { "Incomplete camera plane" }
    }
}
