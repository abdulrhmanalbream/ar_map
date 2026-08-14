package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.tan

/**
 * The horizontal compass ribbon across the top of the camera view.
 *
 * Shows the cardinal points at their true angular positions and slides as the
 * user turns, with the destination pinned to its real bearing. It is the
 * cheapest way to answer "which way am I facing and where is my target"
 * without covering the camera with a map.
 *
 * Pure Kotlin so the angular maths is unit-tested rather than eyeballed on a
 * phone -- a sign error here points every tick the wrong way while still
 * looking like a plausible compass.
 */

/** One mark on the ribbon. */
data class CompassTick(
    /** Bearing this tick represents, degrees from true north. */
    val bearingDeg: Double,
    /** Position across the strip: -1 = left edge, 0 = centre, +1 = right. */
    val x: Float,
    val label: String,
    val isCardinal: Boolean
)

/** The destination marker on the ribbon. */
data class CompassTarget(
    val x: Float,
    /** True when the target lies within the strip's span. */
    val visible: Boolean,
    /** -1 = off to the left, +1 = off to the right. Only when not visible. */
    val offScreenDirection: Int,
    val distanceMeters: Double
)

/** Arabic cardinal labels, in the order the ribbon walks clockwise. */
private val CARDINALS = listOf(
    0.0 to "ش",     // شمال
    45.0 to "ش‌ق",  // شمال شرق
    90.0 to "ق",    // شرق
    135.0 to "ج‌ق", // جنوب شرق
    180.0 to "ج",   // جنوب
    225.0 to "ج‌غ", // جنوب غرب
    270.0 to "غ",   // غرب
    315.0 to "ش‌غ"  // شمال غرب
)

/**
 * How many degrees the strip spans end to end.
 *
 * Deliberately wider than the camera's ~65 degree field of view: a strip that
 * matched the lens exactly would show barely two cardinal points, making it
 * useless for orienting yourself. 140 degrees keeps three or four in view
 * while still moving in step with the world.
 */
const val COMPASS_STRIP_SPAN_DEG = 140.0

/**
 * Builds the visible ticks for the current heading.
 *
 * Ticks are emitted every [stepDeg] degrees, with cardinal points labelled.
 */
fun compassTicks(
    headingDeg: Double,
    stepDeg: Double = 15.0,
    spanDeg: Double = COMPASS_STRIP_SPAN_DEG
): List<CompassTick> {
    if (stepDeg <= 0 || spanDeg <= 0) return emptyList()

    val half = spanDeg / 2.0
    val ticks = mutableListOf<CompassTick>()

    // Walk the whole circle and keep whatever falls inside the span. Cheap
    // (24 iterations at the default step) and avoids wrap-around bugs that
    // creep in when trying to iterate only the visible arc.
    var bearing = 0.0
    while (bearing < 360.0) {
        val delta = relativeBearing(headingDeg, bearing)
        if (abs(delta) <= half) {
            val cardinal = CARDINALS.firstOrNull { abs(relativeBearing(it.first, bearing)) < 0.01 }
            ticks.add(
                CompassTick(
                    bearingDeg = bearing,
                    x = (delta / half).toFloat(),
                    label = cardinal?.second ?: "",
                    isCardinal = cardinal != null
                )
            )
        }
        bearing += stepDeg
    }
    return ticks.sortedBy { it.x }
}

/**
 * Places the destination on the ribbon.
 *
 * Returns an off-screen direction when the target is behind the span, so the
 * UI can show an arrow at the edge rather than silently dropping the marker.
 */
fun compassTarget(
    headingDeg: Double,
    targetBearingDeg: Double,
    distanceMeters: Double,
    spanDeg: Double = COMPASS_STRIP_SPAN_DEG
): CompassTarget {
    val half = spanDeg / 2.0
    val delta = relativeBearing(headingDeg, targetBearingDeg)
    val within = abs(delta) <= half

    return CompassTarget(
        // Clamp so an off-screen target parks at the edge instead of being
        // drawn far outside the strip.
        x = (delta / half).toFloat().coerceIn(-1f, 1f),
        visible = within,
        offScreenDirection = if (delta >= 0) 1 else -1,
        distanceMeters = distanceMeters
    )
}

/**
 * How the user is travelling. Affects which paths are routable and the
 * speed used for time estimates.
 */
enum class TravelMode(
    val labelAr: String,
    /** Typical speed in metres per second, for time estimates. */
    val speedMps: Double
) {
    WALK("مشي", 1.3),
    BIKE("دراجة", 4.2),
    CAR("سيارة", 8.3);

    /**
     * Whether this mode may use a path with the given permissions.
     *
     * Bicycles are the interesting case and the reason this is not a simple
     * equality check: they are allowed on both roads and pedestrian paths,
     * which the user called out explicitly.
     */
    fun canUse(allowsFoot: Boolean, allowsBike: Boolean, allowsCar: Boolean): Boolean =
        when (this) {
            WALK -> allowsFoot
            BIKE -> allowsBike
            CAR -> allowsCar
        }
}

/** Estimated travel time in minutes, never below one. */
fun travelMinutes(distanceMeters: Double, mode: TravelMode): Int =
    kotlin.math.max(1, kotlin.math.ceil(distanceMeters / mode.speedMps / 60.0).toInt())
