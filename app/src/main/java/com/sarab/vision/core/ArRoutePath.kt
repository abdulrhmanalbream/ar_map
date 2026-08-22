package com.sarab.vision.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Projects a real route onto the ground in front of the camera.
 *
 * ## What this replaces
 *
 * The AR view used to draw a straight ribbon along the compass bearing to the
 * destination. That is a pointer, not a path: it goes through walls, ignores
 * every turn, and tells the user to walk into whatever is in the way. What
 * people expect from a navigation view -- and what was actually asked for --
 * is the route itself lying on the ground, bending where the route bends.
 *
 * ## The frame conversion
 *
 * ARCore's world frame has -Z as the direction the camera faced when the
 * session began, which has no fixed relationship to true north. Every route
 * point is therefore converted through the device's current heading: the
 * angle between where the phone points now and where the point lies gives its
 * direction in ARCore's own frame.
 *
 * @param maxVisibleMeters how far ahead to draw. ARCore only knows the ground
 *   it can see; a 250m ribbon would pass through buildings and float over
 *   dips, so the path is a visible stub and distance is carried by the marker.
 */
fun routeGroundPath(
    userPosition: LatLng,
    routePoints: List<LatLng>,
    headingDeg: Double,
    maxVisibleMeters: Double = 25.0,
    startOffsetMeters: Double = 0.8
): List<Vec3> {
    if (!userPosition.isValid || routePoints.size < 2) return emptyList()

    // Local (east, north) metres for every route vertex, plus the user at the
    // origin so the path starts underfoot rather than at the first node.
    val local = ArrayList<Pair<Double, Double>>(routePoints.size + 1)
    local.add(0.0 to 0.0)
    for (point in routePoints) {
        if (!point.isValid) continue
        val distance = distanceMeters(userPosition, point)
        val bearing = bearingDegrees(userPosition, point)
        val rad = Math.toRadians(bearing)
        local.add(distance * sin(rad) to distance * cos(rad))
    }
    if (local.size < 2) return emptyList()

    // The first route vertex is often the node the user snapped onto, which
    // can be behind them. Dropping it stops the ribbon doubling back.
    val trimmed = dropBacktrack(local)

    // Walk forward, clipping at the visible horizon.
    val clipped = clipToLength(trimmed, maxVisibleMeters, startOffsetMeters)
    if (clipped.size < 2) return emptyList()

    // Rotate from world (east/north) into the camera's frame. Camera forward
    // is -Z, so a point directly ahead must land on negative Z.
    val headingRad = Math.toRadians(headingDeg)
    val cosH = cos(headingRad)
    val sinH = sin(headingRad)

    return clipped.map { (east, north) ->
        // Rotate by -heading so "north" becomes "whatever the phone faces".
        val forward = north * cosH + east * sinH
        val right = east * cosH - north * sinH
        Vec3(right.toFloat(), 0f, (-forward).toFloat())
    }
}

/**
 * Removes leading vertices that point away from the route's general direction.
 *
 * A router snaps the user onto the nearest node, and that node is frequently
 * slightly behind them. Drawn literally, the path starts by going backwards
 * past the user's feet, which reads as an instruction to turn around.
 */
private fun dropBacktrack(local: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (local.size < 3) return local

    // Direction of the route as a whole, from the user to its far end.
    val (endEast, endNorth) = local.last()
    val endLength = kotlin.math.hypot(endEast, endNorth)
    if (endLength < 1e-6) return local

    val dirEast = endEast / endLength
    val dirNorth = endNorth / endLength

    var start = 0
    for (i in 1 until local.size - 1) {
        val (e, n) = local[i]
        // Projection onto the overall direction: negative means behind.
        if (e * dirEast + n * dirNorth >= -0.5) break
        start = i
    }
    return if (start == 0) local else listOf(0.0 to 0.0) + local.subList(start + 1, local.size)
}

/**
 * Trims the polyline to the stretch between [startOffset] and [maxLength].
 *
 * Both ends are interpolated rather than snapped to a vertex, so the ribbon
 * begins and ends at a consistent distance regardless of where the route
 * happens to have nodes.
 */
private fun clipToLength(
    local: List<Pair<Double, Double>>,
    maxLength: Double,
    startOffset: Double
): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>(local.size)
    var travelled = 0.0
    var started = false

    for (i in 1 until local.size) {
        val (ax, ay) = local[i - 1]
        val (bx, by) = local[i]
        val segment = kotlin.math.hypot(bx - ax, by - ay)
        if (segment < 1e-9) continue

        val segmentStart = travelled
        val segmentEnd = travelled + segment

        // Where this segment begins to be visible.
        if (!started && segmentEnd > startOffset) {
            val t = ((startOffset - segmentStart) / segment).coerceIn(0.0, 1.0)
            out.add(ax + (bx - ax) * t to ay + (by - ay) * t)
            started = true
        }

        if (segmentEnd >= maxLength) {
            val t = ((maxLength - segmentStart) / segment).coerceIn(0.0, 1.0)
            out.add(ax + (bx - ax) * t to ay + (by - ay) * t)
            return out
        }

        if (started) out.add(bx to by)
        travelled = segmentEnd
    }
    return out
}

/**
 * Resamples a path so points are evenly spaced.
 *
 * The ribbon mitres its corners from the vertices it is given, and a route can
 * run tens of metres between nodes. Even spacing keeps the animated pulse
 * moving at a constant speed and gives the chevrons regular ground to sit on.
 */
fun resamplePath(points: List<Vec3>, spacingMeters: Float = 1.0f): List<Vec3> {
    if (points.size < 2 || spacingMeters <= 0f) return points

    val out = ArrayList<Vec3>()
    out.add(points.first())

    var carry = 0f
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val segment = (b - a).length()
        if (segment < 1e-6f) continue

        var position = spacingMeters - carry
        while (position < segment) {
            val t = position / segment
            out.add(Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t))
            position += spacingMeters
        }
        carry = (segment - (position - spacingMeters)).let { if (it < 0f) 0f else it }
    }

    val last = points.last()
    if ((out.last() - last).length() > 0.05f) out.add(last)
    return out
}

/** Absolute turn between the first and last heading of a ground path. */
fun groundPathTurnDegrees(points: List<Vec3>): Double {
    if (points.size < 3) return 0.0
    val start = points[1] - points[0]
    val end = points[points.size - 1] - points[points.size - 2]
    val a = Math.toDegrees(kotlin.math.atan2(start.x.toDouble(), -start.z.toDouble()))
    val b = Math.toDegrees(kotlin.math.atan2(end.x.toDouble(), -end.z.toDouble()))
    return abs(relativeBearing((a + 360.0) % 360.0, (b + 360.0) % 360.0))
}
