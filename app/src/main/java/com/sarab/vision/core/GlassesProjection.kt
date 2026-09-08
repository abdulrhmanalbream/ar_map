package com.sarab.vision.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * A direction projected into the actual camera image rectangle. X is right,
 * Y is down; both run from -1 to +1 at the image edges. This is a compass/IMU
 * estimate, with no detected floor, obstacle occlusion or positional tracking.
 */
data class GlassesScreenPoint(val x: Double, val y: Double, val depthMeters: Double) {
    val visible: Boolean get() = x in -1.0..1.0 && y in -1.0..1.0
}

data class GlassesScreenSegment(val start: GlassesScreenPoint, val end: GlassesScreenPoint)

data class GlassesTargetDirection(val relativeDegrees: Double, val distanceMeters: Double)

/** User-facing horizon alignment must supersede the pose held during USB startup. */
fun alignedGlassesTilt(angle: Double?, neutral: Double?): Double? {
    if (angle == null || neutral == null || !angle.isFinite() || !neutral.isFinite()) return null
    return (((angle % 360.0 - neutral % 360.0) + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

/**
 * [point] uses routeGroundPath's yaw-relative frame: X right, Y up, -Z
 * forward. Pitch is positive when looking up; roll is positive when the
 * wearer tilts clockwise. Both rotations precede the perspective division.
 *
 * The 70-degree horizontal FOV is an adjustable approximation, NOT a measured
 * XREAL Eye intrinsic. Eye height is an estimate above an assumed level route.
 * Aspect ratio must come from the displayed image rectangle, not its container.
 */
fun projectGlassesPoint(
    point: Vec3,
    pitchDegrees: Double,
    rollDegrees: Double,
    horizontalFovDegrees: Double = 70.0,
    aspectRatio: Double,
    eyeHeightMeters: Double = 1.6
): GlassesScreenPoint? {
    if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite() ||
        !pitchDegrees.isFinite() || !rollDegrees.isFinite() ||
        !horizontalFovDegrees.isFinite() || horizontalFovDegrees !in 10.0..160.0 ||
        !aspectRatio.isFinite() || aspectRatio <= 0.0 ||
        !eyeHeightMeters.isFinite() || eyeHeightMeters < 0.0
    ) return null

    val pitch = Math.toRadians(pitchDegrees % 360.0)
    val roll = Math.toRadians(rollDegrees % 360.0)
    val right = point.x.toDouble()
    val up = point.y.toDouble() - eyeHeightMeters
    val forward = -point.z.toDouble()
    val pitchedUp = up * cos(pitch) - forward * sin(pitch)
    val depth = forward * cos(pitch) + up * sin(pitch)
    // A near plane also prevents tiny positive depths from producing enormous
    // screen coordinates. Never project a point through the back of the head.
    if (depth <= 0.1) return null

    val cameraRight = right * cos(roll) - pitchedUp * sin(roll)
    val cameraUp = right * sin(roll) + pitchedUp * cos(roll)
    val halfWidth = tan(Math.toRadians(horizontalFovDegrees / 2.0))
    val x = cameraRight / (depth * halfWidth)
    val y = -cameraUp * aspectRatio / (depth * halfWidth)
    if (!x.isFinite() || !y.isFinite()) return null
    return GlassesScreenPoint(x, y, depth)
}

/** Clips a visible-front segment before Canvas sees it; missing/behind points break the path. */
fun clipGlassesSegment(
    start: GlassesScreenPoint?,
    end: GlassesScreenPoint?
): GlassesScreenSegment? {
    if (start == null || end == null) return null
    if (listOf(start.x, start.y, end.x, end.y, start.depthMeters, end.depthMeters)
            .any { !it.isFinite() } || start.depthMeters <= 0.0 || end.depthMeters <= 0.0
    ) return null
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (!dx.isFinite() || !dy.isFinite()) return null
    var enter = 0.0
    var leave = 1.0
    val p = doubleArrayOf(-dx, dx, -dy, dy)
    val q = doubleArrayOf(start.x + 1.0, 1.0 - start.x, start.y + 1.0, 1.0 - start.y)
    for (i in p.indices) {
        if (p[i] == 0.0) {
            if (q[i] < 0.0) return null
        } else {
            val ratio = q[i] / p[i]
            if (p[i] < 0.0) enter = maxOf(enter, ratio) else leave = minOf(leave, ratio)
            if (enter > leave) return null
        }
    }
    fun at(t: Double) = GlassesScreenPoint(
        (start.x + dx * t).coerceIn(-1.0, 1.0),
        (start.y + dy * t).coerceIn(-1.0, 1.0),
        start.depthMeters + (end.depthMeters - start.depthMeters) * t
    )
    return GlassesScreenSegment(at(enter), at(leave))
}

fun glassesTargetDirection(
    userPosition: LatLng,
    targetPosition: LatLng,
    headingDegrees: Double
): GlassesTargetDirection? {
    if (!userPosition.isValid || !targetPosition.isValid || !headingDegrees.isFinite()) return null
    val heading = ((headingDegrees % 360.0) + 360.0) % 360.0
    return GlassesTargetDirection(
        relativeBearing(heading, bearingDegrees(userPosition, targetPosition)),
        distanceMeters(userPosition, targetPosition)
    )
}

/** The same centred crop used by Image(ContentScale.Crop), expressed in image NDC. */
fun cropGlassesPoint(
    point: GlassesScreenPoint?,
    imageAspectRatio: Double,
    viewportAspectRatio: Double,
): GlassesScreenPoint? {
    if (point == null || !imageAspectRatio.isFinite() || imageAspectRatio <= 0.0 ||
        !viewportAspectRatio.isFinite() || viewportAspectRatio <= 0.0) return null
    val x = point.x * maxOf(1.0, imageAspectRatio / viewportAspectRatio)
    val y = point.y * maxOf(1.0, viewportAspectRatio / imageAspectRatio)
    return if (x.isFinite() && y.isFinite()) point.copy(x = x, y = y) else null
}

/**
 * Only the remaining, nearby portion of the real route. A stale route may start
 * behind the walker; snapping onto its nearest SEGMENT preserves the next turn
 * without inventing a connector from the head to an old route node.
 * An off-route GPS fix gets a single bearing cue instead of a fabricated floor.
 */
fun glassesRoutePreview(
    userPosition: LatLng,
    routePoints: List<LatLng>,
    headingDegrees: Double,
    lookaheadMeters: Double = 22.0,
    maximumRouteOffsetMeters: Double = 8.0,
): List<Vec3> {
    if (!userPosition.isValid || !headingDegrees.isFinite() || routePoints.size < 2 ||
        routePoints.any { !it.isValid } || !lookaheadMeters.isFinite() || lookaheadMeters <= 0 ||
        !maximumRouteOffsetMeters.isFinite() || maximumRouteOffsetMeters < 0) return emptyList()
    val radians = Math.toRadians(headingDegrees % 360.0)
    val points = routePoints.map { point ->
        val distance = distanceMeters(userPosition, point)
        val relative = Math.toRadians(bearingDegrees(userPosition, point)) - radians
        Vec3((distance * sin(relative)).toFloat(), 0f, (-distance * cos(relative)).toFloat())
    }
    if (points.any { !it.x.isFinite() || !it.z.isFinite() }) return emptyList()
    var bestDistance = Double.POSITIVE_INFINITY
    var nearestSegment = -1
    var nearestPoint = Vec3.ZERO
    for (index in 0 until points.lastIndex) {
        val a = points[index]
        val delta = points[index + 1] - a
        val squared = delta.x.toDouble() * delta.x + delta.z.toDouble() * delta.z
        if (squared < 0.0001) continue
        val fraction = (-(a.x.toDouble() * delta.x + a.z.toDouble() * delta.z) / squared).coerceIn(0.0, 1.0)
        val candidate = a + delta * fraction.toFloat()
        val distance = hypot(candidate.x.toDouble(), candidate.z.toDouble())
        if (distance < bestDistance) {
            bestDistance = distance
            nearestSegment = index
            nearestPoint = candidate
        }
    }
    if (nearestSegment < 0 || bestDistance > maximumRouteOffsetMeters) return emptyList()
    val remaining = listOf(nearestPoint) + points.drop(nearestSegment + 1)
    val output = arrayListOf(nearestPoint)
    var walked = 0.0
    for (index in 1..remaining.lastIndex) {
        val a = remaining[index - 1]
        val b = remaining[index]
        val length = (b - a).length().toDouble()
        if (length < 0.01) continue
        if (walked + length >= lookaheadMeters) {
            output += a + (b - a) * ((lookaheadMeters - walked) / length).toFloat()
            break
        }
        output += b
        walked += length
    }
    return output.takeIf { it.size >= 2 } ?: emptyList()
}

fun pointAlongGlassesRoute(points: List<Vec3>, meters: Double): Vec3? {
    if (points.size < 2 || !meters.isFinite() || meters < 0.0) return null
    var remaining = meters
    for (index in 1..points.lastIndex) {
        val delta = points[index] - points[index - 1]
        val length = delta.length().toDouble()
        if (!length.isFinite()) return null
        if (length < 0.001) continue
        if (remaining <= length) return points[index - 1] + delta * (remaining / length).toFloat()
        remaining -= length
    }
    return null
}

data class GlassesRouteChevron(
    val tip: GlassesScreenPoint,
    val left: GlassesScreenPoint,
    val right: GlassesScreenPoint,
)

/**
 * Only paint the road ahead. The old 4.5 m footprint grew into the wearer's
 * feet on square displays. Try farther route positions and omit any footprint
 * occupying the bottom quarter; never shift its projected position by hand.
 */
fun glassesRouteChevrons(
    points: List<Vec3>,
    pitchDegrees: Double,
    rollDegrees: Double,
    horizontalFovDegrees: Double,
    imageAspectRatio: Double,
    viewportWidth: Double,
    viewportHeight: Double,
    minimumGapPixels: Double = 28.0,
    maxArrows: Int = 2,
): List<GlassesRouteChevron> {
    if (points.size < 2 || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        viewportWidth <= 0 || viewportHeight <= 0 || !minimumGapPixels.isFinite() ||
        minimumGapPixels < 0 || maxArrows <= 0) return emptyList()
    fun project(point: Vec3) = cropGlassesPoint(
        projectGlassesPoint(point, pitchDegrees, rollDegrees, horizontalFovDegrees, imageAspectRatio),
        imageAspectRatio, viewportWidth / viewportHeight,
    )
    data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
    val accepted = mutableListOf<Pair<GlassesRouteChevron, Bounds>>()
    for (distance in listOf(9.0, 12.5, 16.5, 20.0)) {
        val center = pointAlongGlassesRoute(points, distance) ?: continue
        val behind = pointAlongGlassesRoute(points, distance - 0.5) ?: continue
        val ahead = pointAlongGlassesRoute(points, distance + 0.5) ?: continue
        val tangent = (ahead - behind).normalized()
        if (tangent.length() < 0.5f) continue
        val side = Vec3(-tangent.z, 0f, tangent.x)
        val tip = project(center + tangent * 0.70f) ?: continue
        val left = project(center - tangent * 0.70f - side * 0.60f) ?: continue
        val right = project(center - tangent * 0.70f + side * 0.60f) ?: continue
        val corners = listOf(tip, left, right)
        if (corners.any { !it.visible || it.y > 0.46 }) continue
        val bounds = Bounds(
            corners.minOf { (it.x + 1.0) * viewportWidth / 2.0 },
            corners.minOf { (it.y + 1.0) * viewportHeight / 2.0 },
            corners.maxOf { (it.x + 1.0) * viewportWidth / 2.0 },
            corners.maxOf { (it.y + 1.0) * viewportHeight / 2.0 },
        )
        // A footprint below six pixels has become texture, not useful guidance.
        if (bounds.right - bounds.left < 12.0 || bounds.bottom - bounds.top < 6.0) continue
        val collides = accepted.any { (_, other) ->
            bounds.left < other.right + minimumGapPixels && bounds.right > other.left - minimumGapPixels &&
                bounds.top < other.bottom + minimumGapPixels && bounds.bottom > other.top - minimumGapPixels
        }
        if (collides) continue
        accepted += GlassesRouteChevron(tip, left, right) to bounds
        if (accepted.size >= maxArrows.coerceAtMost(2)) break
    }
    return accepted.map { it.first }
}
