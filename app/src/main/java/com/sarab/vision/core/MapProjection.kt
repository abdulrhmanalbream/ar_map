package com.sarab.vision.core

import kotlin.math.max
import kotlin.math.min

/**
 * Projects campus coordinates onto a 2D canvas.
 *
 * The map is drawn from the surveyed landmarks themselves rather than from a
 * pre-drawn campus image. That matters practically: there is no campus map to
 * work from, and requiring one would block the whole feature. Auto-fitting the
 * bounds means the map works the moment the first two landmarks are captured
 * and keeps working as more are added.
 */

/** Geographic bounding box of a set of points. */
data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    val centre: LatLng
        get() = LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)

    companion object {
        /**
         * Bounds containing every supplied point, or null if there are none.
         *
         * A single point yields a degenerate box; [MapTransform] pads it so a
         * lone landmark still renders sensibly instead of dividing by zero.
         */
        fun of(points: List<LatLng>): GeoBounds? {
            val valid = points.filter { it.isValid }
            if (valid.isEmpty()) return null

            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE

            for (p in valid) {
                minLat = min(minLat, p.latitude)
                maxLat = max(maxLat, p.latitude)
                minLon = min(minLon, p.longitude)
                maxLon = max(maxLon, p.longitude)
            }
            return GeoBounds(minLat, maxLat, minLon, maxLon)
        }
    }
}

/** A point on the canvas, in pixels. */
data class CanvasPoint(val x: Float, val y: Float)

/**
 * Maps geographic coordinates to canvas pixels.
 *
 * Longitude is scaled by cos(latitude) so the campus keeps its true shape;
 * without that correction a north-south street would look wider or narrower
 * than it really is, and distances read off the map would be wrong.
 */
class MapTransform(
    private val bounds: GeoBounds,
    private val canvasWidth: Float,
    private val canvasHeight: Float,
    private val paddingPx: Float = 60f,
    /** Extra margin around the data, as a fraction of its span. */
    marginFraction: Double = 0.15
) {
    private val metresPerDegLat = 111_195.0
    private val metresPerDegLon: Double

    private val minEast: Double
    private val minNorth: Double
    private val scale: Double

    init {
        val centreLat = bounds.centre.latitude
        metresPerDegLon = 111_195.0 * kotlin.math.cos(Math.toRadians(centreLat))

        // Convert the bounding box into metres so the aspect ratio is honest.
        val spanNorth = (bounds.maxLat - bounds.minLat) * metresPerDegLat
        val spanEast = (bounds.maxLon - bounds.minLon) * metresPerDegLon

        // A single landmark (or a tight cluster) has near-zero span; give it a
        // sane default so the map is still usable rather than infinite.
        val paddedNorth = max(spanNorth * (1 + marginFraction * 2), 60.0)
        val paddedEast = max(spanEast * (1 + marginFraction * 2), 60.0)

        val usableW = max(1f, canvasWidth - paddingPx * 2)
        val usableH = max(1f, canvasHeight - paddingPx * 2)

        // One scale for both axes, so the campus is never stretched.
        scale = min(usableW / paddedEast, usableH / paddedNorth)

        val centreEast = (bounds.centre.longitude - bounds.minLon) * metresPerDegLon
        val centreNorth = (bounds.centre.latitude - bounds.minLat) * metresPerDegLat

        minEast = centreEast - (usableW / 2.0) / scale
        minNorth = centreNorth - (usableH / 2.0) / scale
    }

    /** Projects a coordinate onto the canvas. */
    fun toCanvas(point: LatLng): CanvasPoint {
        val east = (point.longitude - bounds.minLon) * metresPerDegLon
        val north = (point.latitude - bounds.minLat) * metresPerDegLat

        val x = paddingPx + ((east - minEast) * scale).toFloat()
        // Canvas y grows downwards, but north grows upwards, so this is
        // inverted. Forgetting that flips the whole map vertically.
        val y = canvasHeight - paddingPx - ((north - minNorth) * scale).toFloat()
        return CanvasPoint(x, y)
    }

    /** Metres per pixel, for drawing a scale bar. */
    fun metersPerPixel(): Double = if (scale > 0) 1.0 / scale else 0.0

    /** A round scale-bar length that fits comfortably on screen. */
    fun scaleBarMeters(): Int {
        val target = metersPerPixel() * (canvasWidth * 0.25)
        val candidates = listOf(10, 20, 50, 100, 200, 500, 1000)
        return candidates.firstOrNull { it >= target } ?: 1000
    }
}
