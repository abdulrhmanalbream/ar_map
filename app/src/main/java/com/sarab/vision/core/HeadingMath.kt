package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Compass heading that survives however the user is holding the phone.
 *
 * ## The bug this replaces
 *
 * The obvious implementation calls `SensorManager.getOrientation()` and takes
 * `values[0]`. That returns the azimuth of the device's **+Y axis** -- the top
 * edge of the phone.
 *
 * It works when the phone lies flat like a paper map. It **breaks completely**
 * when the phone is held upright to look through the camera, because then the
 * top edge points at the sky: the azimuth becomes degenerate (gimbal lock at
 * pitch = 90 degrees) and the reading swings wildly with the smallest movement.
 * That is exactly the "compass is broken / will not sit still" symptom.
 *
 * ## The fix
 *
 * Read the direction vectors straight out of the rotation matrix and pick the
 * one that is actually horizontal:
 *
 *  - **Phone upright** (looking through the camera): use where the CAMERA
 *    points, the device -Z axis. Conveniently this is independent of screen
 *    rotation, so landscape needs no special case.
 *  - **Phone flat** (held like a map): the camera points at the floor and
 *    carries no heading, so use where the top of the SCREEN points instead.
 *
 * Both are computed from the same matrix, so switching between them is
 * seamless. A user can lower the phone to glance at the map and raise it back
 * to the camera and the heading stays correct the whole time.
 *
 * ### Matrix convention
 *
 * `getRotationMatrixFromVector` produces a row-major 3x3 that maps device
 * coordinates to world coordinates (X = east, Y = north, Z = up). Its columns
 * are therefore the world-space images of the device axes:
 *
 * ```
 * device X -> (R[0], R[3], R[6])
 * device Y -> (R[1], R[4], R[7])
 * device Z -> (R[2], R[5], R[8])
 * ```
 */

/**
 * Above this much tilt the camera is considered to be pointing too far up or
 * down to carry a usable heading, and the screen-up direction is used instead.
 *
 * 0.72 is roughly 46 degrees from horizontal: comfortably past normal AR
 * holding angles, but reached well before the phone is flat on a table.
 */
private const val FLAT_THRESHOLD = 0.72f

/** Hysteresis band, so a phone held near the boundary does not flap. */
private const val FLAT_HYSTERESIS = 0.08f

/** How far a direction must be from vertical before its bearing is trusted. */
private const val MIN_HORIZONTAL_COMPONENT = 0.08f

/** Which reference direction produced a heading. */
enum class HeadingSource {
    /** Phone upright: heading is where the camera looks. */
    CAMERA,

    /** Phone flat: heading is where the top of the screen points. */
    SCREEN_UP
}

data class HeadingResult(
    /** Degrees clockwise from north, or null when nothing is trustworthy. */
    val degrees: Double?,
    val source: HeadingSource,
    /** How far the camera is tilted from horizontal, 0 = level, 1 = straight up/down. */
    val tilt: Float
)

/**
 * World-space direction the camera points, as (east, north, up).
 *
 * The camera looks along the device's -Z axis on every Android phone, in every
 * screen rotation.
 */
fun cameraDirection(r: FloatArray): Triple<Float, Float, Float> =
    Triple(-r[2], -r[5], -r[8])

/**
 * World-space direction the top of the SCREEN points, as (east, north, up).
 *
 * Unlike the camera this does depend on screen rotation, so the device-space
 * "up the screen" vector is rotated before being transformed.
 */
fun screenUpDirection(r: FloatArray, displayRotationDeg: Int): Triple<Float, Float, Float> {
    // Up the screen, expressed in device coordinates, per display rotation.
    val (dx, dy) = when (((displayRotationDeg % 360) + 360) % 360) {
        90 -> -1f to 0f
        180 -> 0f to -1f
        270 -> 1f to 0f
        else -> 0f to 1f
    }
    return Triple(
        r[0] * dx + r[1] * dy,
        r[3] * dx + r[4] * dy,
        r[6] * dx + r[7] * dy
    )
}

/**
 * Compass bearing of a world-space direction, or null if it is too close to
 * vertical for its horizontal component to mean anything.
 */
fun bearingOf(direction: Triple<Float, Float, Float>): Double? {
    val (east, north, _) = direction
    if (hypot(east.toDouble(), north.toDouble()) < MIN_HORIZONTAL_COMPONENT) return null
    return (Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + 360.0) % 360.0
}

/**
 * Computes the heading, choosing the reference that is currently meaningful.
 *
 * @param wasFlat the previous decision, used for hysteresis so the source does
 *   not oscillate while the phone sits near the switching angle.
 */
fun computeHeading(
    rotationMatrix: FloatArray,
    displayRotationDeg: Int,
    wasFlat: Boolean = false
): HeadingResult {
    if (rotationMatrix.size < 9) {
        return HeadingResult(null, HeadingSource.CAMERA, 0f)
    }

    val camera = cameraDirection(rotationMatrix)
    // Vertical component of the camera direction: 0 when level, 1 when the
    // camera points straight up or down.
    val tilt = abs(camera.third)

    // Hysteresis: it takes a little more tilt to enter flat mode than to leave
    // it, which stops the source flickering at the boundary.
    val threshold = if (wasFlat) FLAT_THRESHOLD - FLAT_HYSTERESIS else FLAT_THRESHOLD
    val flat = tilt > threshold

    return if (flat) {
        val bearing = bearingOf(screenUpDirection(rotationMatrix, displayRotationDeg))
        // If the screen-up direction is ALSO vertical the phone is on edge; fall
        // back to the camera rather than reporting nothing.
        if (bearing != null) {
            HeadingResult(bearing, HeadingSource.SCREEN_UP, tilt)
        } else {
            HeadingResult(bearingOf(camera), HeadingSource.CAMERA, tilt)
        }
    } else {
        val bearing = bearingOf(camera)
        if (bearing != null) {
            HeadingResult(bearing, HeadingSource.CAMERA, tilt)
        } else {
            HeadingResult(
                bearingOf(screenUpDirection(rotationMatrix, displayRotationDeg)),
                HeadingSource.SCREEN_UP,
                tilt
            )
        }
    }
}

/**
 * Circular exponential smoothing.
 *
 * Averaging raw degrees is wrong: the mean of 359 and 1 is 180, so the needle
 * swings a half turn every time it crosses north. Smoothing the sine and
 * cosine instead keeps it continuous.
 *
 * @param alpha 0..1; lower is steadier but laggier.
 */
class CircularSmoother(private val alpha: Double = 0.18) {
    private var sin = 0.0
    private var cos = 0.0
    private var primed = false

    fun reset() {
        primed = false
    }

    fun next(degrees: Double): Double {
        val rad = Math.toRadians(degrees)
        val s = kotlin.math.sin(rad)
        val c = kotlin.math.cos(rad)

        if (!primed) {
            sin = s
            cos = c
            primed = true
        } else {
            sin = sin * (1 - alpha) + s * alpha
            cos = cos * (1 - alpha) + c * alpha
        }
        return (Math.toDegrees(atan2(sin, cos)) + 360.0) % 360.0
    }
}
