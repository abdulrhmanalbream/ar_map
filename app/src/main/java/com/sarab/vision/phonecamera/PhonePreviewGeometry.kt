package com.sarab.vision.phonecamera

/**
 * TextureView first rotates a camera buffer into the sensor's natural orientation,
 * then stretches it to the view. Undo that stretch and center-crop the image before compensating
 * display rotation. ImageReader pixels need the full sensor-minus-display rotation.
 * See developer.android.com/codelabs/android-camera2-preview (TextureView transform).
 */
internal data class PhonePreviewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Int,
    val uprightWidth: Int,
    val uprightHeight: Int,
    val imageScale: Float,
    val imageLeft: Float,
    val imageTop: Float,
)

internal fun phonePreviewTransform(
    bufferWidth: Int,
    bufferHeight: Int,
    sensorDegrees: Int,
    displayDegrees: Int,
    viewWidth: Int,
    viewHeight: Int,
): PhonePreviewTransform {
    require(bufferWidth > 0 && bufferHeight > 0 && viewWidth > 0 && viewHeight > 0)
    val clockwise = backCameraRotation(sensorDegrees, displayDegrees)
    val uprightWidth = if (clockwise % 180 == 0) bufferWidth else bufferHeight
    val uprightHeight = if (clockwise % 180 == 0) bufferHeight else bufferWidth
    val nativeWidth = if (sensorDegrees % 180 == 0) bufferWidth else bufferHeight
    val nativeHeight = if (sensorDegrees % 180 == 0) bufferHeight else bufferWidth
    val scale = maxOf(viewWidth.toFloat() / uprightWidth, viewHeight.toFloat() / uprightHeight)
    return PhonePreviewTransform(
        scaleX = nativeWidth * scale / viewWidth,
        scaleY = nativeHeight * scale / viewHeight,
        rotationDegrees = -displayDegrees,
        uprightWidth = uprightWidth,
        uprightHeight = uprightHeight,
        imageScale = scale,
        imageLeft = (viewWidth - uprightWidth * scale) / 2f,
        imageTop = (viewHeight - uprightHeight * scale) / 2f,
    )
}

/** Matching aspect ratios keep the preview and the analysis stream on the same crop. */
internal fun sameCameraAspect(first: CameraFrameSize, second: CameraFrameSize): Boolean =
    first.width.toLong() * second.height == second.width.toLong() * first.height
