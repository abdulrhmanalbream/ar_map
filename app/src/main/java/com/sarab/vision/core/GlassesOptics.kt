package com.sarab.vision.core

import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * XREAL One Pro is sold as a 57-degree (diagonal) display. At its 16:9 panel
 * that is about 50.7 degrees horizontally: tan(28.5 deg) * 16 / sqrt(16^2 + 9^2)
 * = tan(25.3 deg). This is a published figure, not a measurement of this pair,
 * which is why the setting stays adjustable.
 */
const val GLASSES_DISPLAY_HORIZONTAL_FOV_DEG = 50.7

/** 1920x1080 per eye; the Presentation window reports the same aspect. */
const val GLASSES_DISPLAY_ASPECT = 16.0 / 9.0

/** Beyond these the calibration is almost certainly a mis-tap, not a fit. */
const val GLASSES_CALIBRATION_MAX_OFFSET_DEG = 15.0
const val GLASSES_CALIBRATION_MIN_SCALE = 0.7
const val GLASSES_CALIBRATION_MAX_SCALE = 1.4

/**
 * How the Eye camera's view lines up with what the wearer sees through the
 * optics. Offsets move the camera's image in degrees (X right, Y down); scale
 * stretches angles about the camera centre. The camera sits above the bridge
 * and is not exactly boresighted with the display, so a per-wearer fit is the
 * only honest source of these numbers.
 */
data class GlassesOpticsCalibration(
    val offsetXDegrees: Double = 0.0,
    val offsetYDegrees: Double = 0.0,
    val scale: Double = 1.0,
) {
    fun bounded() = GlassesOpticsCalibration(
        offsetXDegrees.takeIf { it.isFinite() }?.coerceIn(-GLASSES_CALIBRATION_MAX_OFFSET_DEG, GLASSES_CALIBRATION_MAX_OFFSET_DEG) ?: 0.0,
        offsetYDegrees.takeIf { it.isFinite() }?.coerceIn(-GLASSES_CALIBRATION_MAX_OFFSET_DEG, GLASSES_CALIBRATION_MAX_OFFSET_DEG) ?: 0.0,
        scale.takeIf { it.isFinite() }?.coerceIn(GLASSES_CALIBRATION_MIN_SCALE, GLASSES_CALIBRATION_MAX_SCALE) ?: 1.0,
    )
}

/** Display-space rectangle; -1..+1 at the display edges, Y down. */
data class GlassesDisplayRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
    /** Any part on the display: a half-visible object still deserves its outline. */
    val onDisplay: Boolean get() = right > -1.0 && left < 1.0 && bottom > -1.0 && top < 1.0
}

/** Viewing angle of a camera-image point, in degrees (X right, Y down). */
data class GlassesAngles(val xDegrees: Double, val yDegrees: Double)

private fun validFov(value: Double) = value.isFinite() && value in 10.0..160.0
private fun validAspect(value: Double) = value.isFinite() && value > 0.0

/** Camera image NDC (-1..+1, Y down) to the angle that pixel looks along. */
fun cameraPointAngles(
    x: Double, y: Double,
    cameraHorizontalFovDegrees: Double,
    cameraAspect: Double,
): GlassesAngles? {
    if (!x.isFinite() || !y.isFinite() || !validFov(cameraHorizontalFovDegrees) || !validAspect(cameraAspect)) return null
    val halfX = tan(Math.toRadians(cameraHorizontalFovDegrees / 2.0))
    val halfY = halfX / cameraAspect
    return GlassesAngles(Math.toDegrees(atan(x * halfX)), Math.toDegrees(atan(y * halfY)))
}

/**
 * Where something seen at [angles] by the camera appears on the display, after
 * the wearer turned by [deltaYawDegrees] (right positive) and
 * [deltaPitchDegrees] (up positive) since the frame was captured.
 *
 * Turning right moves the world left in view; looking up moves it down. This
 * is rotation only: the Eye has no depth, so parallax between the camera and
 * the eye is folded into the calibration offsets and is exact only at the
 * distance the wearer calibrated at.
 */
fun displayPointForAngles(
    angles: GlassesAngles,
    calibration: GlassesOpticsCalibration,
    displayHorizontalFovDegrees: Double = GLASSES_DISPLAY_HORIZONTAL_FOV_DEG,
    displayAspect: Double = GLASSES_DISPLAY_ASPECT,
    deltaYawDegrees: Double = 0.0,
    deltaPitchDegrees: Double = 0.0,
): Pair<Double, Double>? {
    if (!validFov(displayHorizontalFovDegrees) || !validAspect(displayAspect) ||
        !deltaYawDegrees.isFinite() || !deltaPitchDegrees.isFinite()) return null
    val fit = calibration.bounded()
    val ax = angles.xDegrees * fit.scale + fit.offsetXDegrees - deltaYawDegrees
    val ay = angles.yDegrees * fit.scale + fit.offsetYDegrees + deltaPitchDegrees
    // Past 80 degrees tan() explodes; such a point is far off any glasses display.
    if (kotlin.math.abs(ax) >= 80.0 || kotlin.math.abs(ay) >= 80.0) return null
    val halfX = tan(Math.toRadians(displayHorizontalFovDegrees / 2.0))
    val halfY = halfX / displayAspect
    val x = tan(Math.toRadians(ax)) / halfX
    val y = tan(Math.toRadians(ay)) / halfY
    return if (x.isFinite() && y.isFinite()) x to y else null
}

/**
 * A recognition box (Gemini order, 0..1000 of the camera image) mapped onto the
 * glasses display. Corners are mapped separately so the outline keeps the
 * perspective the camera saw, then re-boxed.
 */
fun visionBoxOnGlasses(
    yMin: Double, xMin: Double, yMax: Double, xMax: Double,
    cameraHorizontalFovDegrees: Double,
    cameraAspect: Double,
    calibration: GlassesOpticsCalibration,
    displayHorizontalFovDegrees: Double = GLASSES_DISPLAY_HORIZONTAL_FOV_DEG,
    displayAspect: Double = GLASSES_DISPLAY_ASPECT,
    deltaYawDegrees: Double = 0.0,
    deltaPitchDegrees: Double = 0.0,
): GlassesDisplayRect? {
    if (listOf(yMin, xMin, yMax, xMax).any { !it.isFinite() } || xMax <= xMin || yMax <= yMin) return null
    val corners = listOf(xMin to yMin, xMax to yMin, xMin to yMax, xMax to yMax).map { (bx, by) ->
        val angles = cameraPointAngles(bx / 500.0 - 1.0, by / 500.0 - 1.0, cameraHorizontalFovDegrees, cameraAspect)
            ?: return null
        displayPointForAngles(angles, calibration, displayHorizontalFovDegrees, displayAspect,
            deltaYawDegrees, deltaPitchDegrees) ?: return null
    }
    return GlassesDisplayRect(corners.minOf { it.first }, corners.minOf { it.second },
        corners.maxOf { it.first }, corners.maxOf { it.second })
}

/** Signed smallest difference a - b, so 359 -> 1 is +2 degrees, not -358. */
fun angleDeltaDegrees(a: Double, b: Double): Double {
    if (!a.isFinite() || !b.isFinite()) return Double.NaN
    return (((a - b) % 360.0) + 540.0) % 360.0 - 180.0
}

/** Head pose sample on Android's monotonic clock. */
data class HeadPoseSample(val atElapsedMs: Long, val yawDegrees: Double, val pitchDegrees: Double, val sessionId: Long)

/**
 * The last couple of seconds of glasses IMU poses, so a box measured on an
 * older camera frame can be moved by exactly how far the head has turned since.
 * Tracker boxes arrive at about 4 Hz while the IMU runs far faster; without
 * this, outlines visibly lag every head turn and then jump.
 */
class HeadPoseHistory(private val retainMs: Long = 2_000L, private val capacity: Int = 240) {
    private val samples = ArrayDeque<HeadPoseSample>()

    fun add(sample: HeadPoseSample) {
        if (!sample.yawDegrees.isFinite() || !sample.pitchDegrees.isFinite()) return
        // A new IMU session restarts yaw at an arbitrary zero: old samples would lie.
        if (samples.isNotEmpty() && samples.last().sessionId != sample.sessionId) samples.clear()
        if (samples.isNotEmpty() && sample.atElapsedMs < samples.last().atElapsedMs) return
        samples.addLast(sample)
        while (samples.size > capacity || (samples.size > 1 &&
                sample.atElapsedMs - samples.first().atElapsedMs > retainMs)) samples.removeFirst()
    }

    fun latest(): HeadPoseSample? = samples.lastOrNull()

    /** Pose at [atElapsedMs], interpolated; null outside the retained window. */
    fun at(atElapsedMs: Long, sessionId: Long): HeadPoseSample? {
        if (samples.isEmpty() || samples.last().sessionId != sessionId) return null
        val first = samples.first()
        val last = samples.last()
        if (atElapsedMs < first.atElapsedMs - 50 || atElapsedMs > last.atElapsedMs + 250) return null
        if (atElapsedMs <= first.atElapsedMs) return first
        if (atElapsedMs >= last.atElapsedMs) return last
        var before = first
        for (sample in samples) {
            if (sample.atElapsedMs >= atElapsedMs) {
                val span = (sample.atElapsedMs - before.atElapsedMs).toDouble()
                if (span <= 0.0) return sample
                val t = (atElapsedMs - before.atElapsedMs) / span
                return HeadPoseSample(atElapsedMs,
                    before.yawDegrees + angleDeltaDegrees(sample.yawDegrees, before.yawDegrees) * t,
                    before.pitchDegrees + (sample.pitchDegrees - before.pitchDegrees) * t, sessionId)
            }
            before = sample
        }
        return last
    }

    fun clear() = samples.clear()
}

/** Camera image aspect from its pixel size; the Eye RGB stream is not 16:9. */
fun imageAspect(width: Int, height: Int): Double? =
    if (width > 0 && height > 0) width.toDouble() / height else null

/** Diagonal FOV for documentation and UI; inverse of the derivation above. */
fun diagonalFovDegrees(horizontalFovDegrees: Double, aspect: Double): Double {
    val halfX = tan(Math.toRadians(horizontalFovDegrees / 2.0))
    val halfDiag = halfX * sqrt(1.0 + 1.0 / (aspect * aspect))
    return Math.toDegrees(atan(halfDiag)) * 2.0
}
