package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.tan

/**
 * Places distant, GPS-anchored landmarks on screen.
 *
 * ## Why this is separate from the ARCore path renderer
 *
 * ARCore only understands the few metres of ground it can actually see. A
 * ribbon drawn 250m into the distance would pass through buildings, float
 * above dips and sink into rises, because ARCore has no idea what the terrain
 * does out there. Drawing it anyway looks broken and, worse, misleads.
 *
 * A floating marker is a different matter: it only needs a DIRECTION, which
 * GPS gives us exactly. So distant landmarks get a billboard placed by
 * bearing and elevation, and only the last stretch gets a ground path.
 *
 * This is the same split Google Maps Live View uses, and it is a deliberate
 * design choice rather than a limitation we failed to overcome.
 */

/** Where a landmark should be drawn on screen, in normalised coordinates. */
data class ScreenPlacement(
    /** -1 = left edge, 0 = centre, +1 = right edge. */
    val x: Float,
    /** -1 = bottom, 0 = centre, +1 = top. */
    val y: Float,
    /** True when the landmark is within the camera's field of view. */
    val visible: Boolean,
    /**
     * Which way to nudge the user when off screen: -1 left, +1 right.
     * Meaningless when [visible] is true.
     */
    val offScreenDirection: Int,
    val distanceMeters: Double
)

/**
 * Projects a landmark onto the screen from the user's position and heading.
 *
 * @param horizontalFovDeg camera horizontal field of view. Typical phone rear
 *   cameras are 60-70 degrees; ARCore can report the real value.
 * @param verticalFovDeg camera vertical field of view.
 * @param pitchDeg how far the device is tilted up (+) or down (-) from level.
 */
fun projectLandmark(
    userPosition: LatLng,
    userHeadingDeg: Double,
    pitchDeg: Double,
    target: LatLng,
    horizontalFovDeg: Double = 65.0,
    verticalFovDeg: Double = 50.0
): ScreenPlacement? {
    if (!userPosition.isValid || !target.isValid) return null

    val distance = distanceMeters(userPosition, target)
    val bearing = bearingDegrees(userPosition, target)

    // Horizontal offset from the centre of view, in degrees.
    val relative = relativeBearing(userHeadingDeg, bearing)

    val halfH = horizontalFovDeg / 2.0
    val withinH = abs(relative) <= halfH

    // Map the angle onto [-1, 1] using tan so the projection matches how a
    // real camera lens spreads angles across the sensor. A linear mapping
    // drifts noticeably towards the edges of a wide field of view.
    val x = if (halfH > 0) {
        (tan(Math.toRadians(relative)) / tan(Math.toRadians(halfH))).toFloat()
    } else {
        0f
    }

    // Vertical placement. We have no elevation data for landmarks, so treat
    // them as level with the user and let the device's pitch move them up and
    // down the screen. Being honest about this is better than inventing
    // heights that would be wrong on a campus built on a slope.
    val verticalAngle = -pitchDeg
    val halfV = verticalFovDeg / 2.0
    val y = if (halfV > 0) {
        (tan(Math.toRadians(verticalAngle)) / tan(Math.toRadians(halfV))).toFloat()
    } else {
        0f
    }

    return ScreenPlacement(
        x = x.coerceIn(-4f, 4f),
        y = y.coerceIn(-4f, 4f),
        visible = withinH && abs(x) <= 1f,
        offScreenDirection = if (relative >= 0) 1 else -1,
        distanceMeters = distance
    )
}

/**
 * How large a distant marker should appear, as a fraction of its base size.
 *
 * Real perspective scaling (1/distance) would make a 250m landmark a
 * sub-pixel dot. We instead shrink gently with distance and clamp, so far
 * landmarks stay legible while still reading as "further away".
 */
fun markerScaleFor(distanceMeters: Double): Float {
    if (distanceMeters <= 0) return 1f
    val scale = 1.0 / (1.0 + distanceMeters / 120.0)
    return scale.coerceIn(0.45, 1.0).toFloat()
}

/**
 * How far along the ground a path should actually be drawn, in metres.
 *
 * ARCore's plane knowledge extends only a short way, so we draw a stub that
 * points the right way and stop before the geometry becomes fiction. The
 * floating marker carries the rest of the message.
 */
fun groundPathLength(distanceToTargetMeters: Double): Double =
    when {
        distanceToTargetMeters <= 0 -> 0.0
        // Close enough to draw the whole way.
        distanceToTargetMeters <= 15.0 -> distanceToTargetMeters
        // Otherwise a fixed stub: far enough to show direction, short enough
        // to stay on ground ARCore has actually observed.
        else -> 12.0
    }
