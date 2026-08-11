package com.sarab.vision.core

/**
 * A point of interest shown at the end of the navigation path.
 *
 * The MVP ships a single hard-coded POI (offline, no backend), but the type
 * is a list-friendly value object so adding more later is a data change
 * rather than a rewrite.
 */
data class Poi(
    val id: String,
    val title: String,
    val category: String,
    val detail: String,
    val distanceMeters: Float
)

/** The offline POI catalogue. No network, no database -- see ADR-001. */
object PoiCatalogue {
    val DEFAULT = Poi(
        id = "poi-coffee-01",
        title = "Coffee Shop",
        category = "Cafe · Open now",
        detail = "Specialty espresso and pastries. Seating for 24, " +
                 "outdoor terrace, and step-free access from the main walkway.",
        distanceMeters = 4.0f
    )
}

/**
 * Builds the walkable path as a list of points on the detected floor plane.
 *
 * The path runs from just in front of the user to the POI. We generate
 * intermediate points (rather than a single segment) so the ribbon follows
 * the floor and stays visually stable if the plane estimate shifts.
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
