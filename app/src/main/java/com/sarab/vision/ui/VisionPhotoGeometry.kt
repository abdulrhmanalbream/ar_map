package com.sarab.vision.ui

import com.sarab.vision.vision.VisionBox
import com.sarab.vision.vision.VisionScreenBox
import kotlin.math.min

/** Selected photos use Fit so the whole chosen photo and its normalized boxes remain visible. */
internal fun fitVisionPhotoBox(box: VisionBox, imageWidth: Int, imageHeight: Int,
    viewportWidth: Float, viewportHeight: Float): VisionScreenBox? {
    if (imageWidth <= 0 || imageHeight <= 0 || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        viewportWidth <= 0 || viewportHeight <= 0 ||
        listOf(box.xMin, box.yMin, box.xMax, box.yMax).any { !it.isFinite() || it !in 0.0..1000.0 } ||
        box.xMin >= box.xMax || box.yMin >= box.yMax) return null
    val scale = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val left = (viewportWidth - imageWidth * scale) / 2f
    val top = (viewportHeight - imageHeight * scale) / 2f
    return VisionScreenBox(left + (box.xMin / 1000 * imageWidth * scale).toFloat(),
        top + (box.yMin / 1000 * imageHeight * scale).toFloat(),
        left + (box.xMax / 1000 * imageWidth * scale).toFloat(),
        top + (box.yMax / 1000 * imageHeight * scale).toFloat())
}
