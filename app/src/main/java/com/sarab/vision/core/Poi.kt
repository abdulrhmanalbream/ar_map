package com.sarab.vision.core

/**
 * Straight-line path helper.
 *
 * V1 used this to lay a single straight path in front of the user. V2 routes
 * come from [CampusMap] waypoints instead, but this is kept because it is
 * still the simplest way to generate an evenly-spaced run of points along a
 * direction, and it is covered by unit tests.
 *
 * The V1 `Poi` / `PoiCatalogue` types were removed when [Destination]
 * replaced them; see Waypoints.kt.
 *
 * @param start   where the path begins, on the floor
 * @param forward unit direction along the floor pointing away from the user
 * @param length  path length in metres
 * @param segments number of segments; more looks smoother but costs vertices
 */
fun buildPathPoints(
    start: Vec3,
    forward: Vec3,
    length: Float,
    segments: Int = 24
): List<Vec3> {
    val dir = forward.normalized()
    if (dir.length() < 1e-6f || segments < 1) return emptyList()

    return (0..segments).map { i ->
        val t = i.toFloat() / segments
        start + dir * (length * t)
    }
}
