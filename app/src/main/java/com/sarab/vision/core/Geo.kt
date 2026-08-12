package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline geographic maths for campus-scale navigation.
 *
 * No Android imports, so this is unit-testable on the JVM and portable to
 * iOS -- same rule as the rest of `core`.
 *
 * ## Why GPS at all?
 *
 * ARCore's motion tracking drifts roughly a metre every 10-20m walked. On a
 * campus with 100-300m between buildings that error compounds until the path
 * is drawn in the wrong place entirely. GPS gives an absolute fix that never
 * drifts, so we use GPS for the long haul and ARCore only for the last few
 * metres, where its precision actually beats GPS.
 *
 * GPS itself needs no internet: the satellites broadcast everything required.
 * Only the *time to first fix* improves with a network (assisted GPS).
 */

/** Mean Earth radius in metres (WGS-84 authalic radius). */
private const val EARTH_RADIUS_M = 6_371_008.8

/** A WGS-84 coordinate. */
data class LatLng(val latitude: Double, val longitude: Double) {

    /** True when the value is a plausible coordinate (guards bad input). */
    val isValid: Boolean
        get() = latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0) // null island == no fix
}

private fun Double.toRad() = this * (Math.PI / 180.0)
private fun Double.toDeg() = this * (180.0 / Math.PI)

/**
 * Great-circle distance between two points, in metres (haversine).
 *
 * Accurate to well under a metre at campus scale, which is far below GPS's
 * own error, so the simpler formula is the right choice over Vincenty.
 */
fun distanceMeters(from: LatLng, to: LatLng): Double {
    val dLat = (to.latitude - from.latitude).toRad()
    val dLon = (to.longitude - from.longitude).toRad()
    val lat1 = from.latitude.toRad()
    val lat2 = to.latitude.toRad()

    val a = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/**
 * Initial bearing from [from] to [to], in degrees clockwise from true north
 * (0 = north, 90 = east). Always normalised to [0, 360).
 */
fun bearingDegrees(from: LatLng, to: LatLng): Double {
    val lat1 = from.latitude.toRad()
    val lat2 = to.latitude.toRad()
    val dLon = (to.longitude - from.longitude).toRad()

    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x).toDeg() + 360.0) % 360.0
}

/**
 * Smallest signed angle from [fromDeg] to [toDeg], in (-180, 180].
 *
 * This is what drives the on-screen arrow: negative means turn left,
 * positive means turn right. Naively subtracting the two would make a
 * 350 deg -> 10 deg turn look like a 340 deg spin instead of 20 deg right.
 */
fun relativeBearing(fromDeg: Double, toDeg: Double): Double {
    var diff = (toDeg - fromDeg) % 360.0
    if (diff > 180.0) diff -= 360.0
    if (diff <= -180.0) diff += 360.0
    return diff
}

/**
 * Converts a geographic offset into local metres east/north of [origin].
 *
 * Uses an equirectangular approximation, which is entirely adequate over a
 * few kilometres and is what lets us place AR content and draw the 2D map
 * with plain 2D maths.
 */
fun toLocalMeters(origin: LatLng, point: LatLng): Pair<Double, Double> {
    val meanLat = ((origin.latitude + point.latitude) / 2.0).toRad()
    val east = (point.longitude - origin.longitude).toRad() * cos(meanLat) * EARTH_RADIUS_M
    val north = (point.latitude - origin.latitude).toRad() * EARTH_RADIUS_M
    return east to north
}

/** Human-friendly distance: "12 m", "450 m", "1.2 km". */
fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${meters.toInt()} m"
    else -> String.format("%.1f km", meters / 1000.0)
}

/** Rough walking time at ~1.3 m/s, never less than one minute. */
fun walkMinutes(meters: Double): Int =
    kotlin.math.max(1, kotlin.math.ceil(meters / 1.3 / 60.0).toInt())

/** The eight compass points, for spoken-style guidance. */
fun compassPoint(bearingDeg: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((bearingDeg % 360.0) + 360.0) % 360.0 / 45.0).let {
        Math.round(it).toInt() % 8
    }
    return names[idx]
}

/**
 * Turn instruction from a relative bearing, e.g. "Turn right", "Ahead".
 *
 * The dead-ahead band is deliberately wide (30 deg): GPS heading is noisy,
 * and a narrow band makes the instruction flicker between "ahead" and
 * "slight left" while the user walks in a straight line.
 */
fun turnInstruction(relativeDeg: Double): String {
    val a = relativeDeg
    return when {
        abs(a) <= 15 -> "Straight ahead"
        abs(a) <= 55 -> if (a > 0) "Slightly right" else "Slightly left"
        abs(a) <= 125 -> if (a > 0) "Turn right" else "Turn left"
        else -> "Turn around"
    }
}

/**
 * Averages several GPS fixes into one, weighting by accuracy.
 *
 * Used when capturing a landmark: a single fix can be several metres out,
 * but the error is largely random, so a weighted mean of a handful of
 * samples is markedly better. Returns null if nothing usable was supplied.
 */
fun averageFixes(fixes: List<GpsFix>): LatLng? {
    val usable = fixes.filter { it.position.isValid && it.accuracyMeters > 0f }
    if (usable.isEmpty()) return null

    // Weight by 1/accuracy^2: a 3m fix should count far more than a 15m one.
    var sumW = 0.0
    var sumLat = 0.0
    var sumLon = 0.0
    for (f in usable) {
        val w = 1.0 / (f.accuracyMeters.toDouble() * f.accuracyMeters.toDouble())
        sumW += w
        sumLat += f.position.latitude * w
        sumLon += f.position.longitude * w
    }
    if (sumW <= 0.0) return null
    return LatLng(sumLat / sumW, sumLon / sumW)
}

/** A single GPS reading with its reported accuracy. */
data class GpsFix(
    val position: LatLng,
    val accuracyMeters: Float,
    val timestampMs: Long
)
