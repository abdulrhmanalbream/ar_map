package com.sarab.vision.core

/**
 * Campus destinations and their waypoint routes.
 *
 * Everything here is pure Kotlin with no Android or ARCore imports, so the
 * route maths stays unit-testable on the JVM and portable to ARKit.
 *
 * ## Coordinate system
 *
 * Waypoints are expressed in **image-local space**, relative to the tracked
 * reference image (the map board), which acts as the world origin.
 *
 * ARCore's augmented image pose is defined with:
 *   +X = to the right along the image
 *   +Y = out of the image face (the direction the board is looking)
 *   +Z = down the image
 *
 * A map board is normally mounted **vertically on a wall**, so the direction
 * that means "walk forwards away from the board" is **+Y**, and the floor
 * drops away along **+Z**. That is why routes below grow mainly in Y, with Z
 * used for the small drop from board height down to the floor.
 *
 * If your board is instead lying **flat on the ground** (a floor decal), set
 * [ImageMounting.FLAT] in [CampusMap.mounting] and the same numbers are
 * reinterpreted so +Y still means "forwards along the floor".
 */

/** How the physical reference image is mounted in the real world. */
enum class ImageMounting {
    /** Board hangs vertically on a wall (the usual case for a map board). */
    VERTICAL,

    /** Marker lies flat on the floor, facing the ceiling. */
    FLAT
}

/**
 * A campus destination and the route to reach it.
 *
 * @param waypoints route in image-local metres, ordered from the board to the
 *   destination. The first point should start slightly in front of the board
 *   so the path is not drawn inside the wall.
 */
data class Destination(
    val id: String,
    val name: String,
    val category: String,
    val detail: String,
    val waypoints: List<Vec3>
) {
    /** Total walking distance along the route, in metres. */
    val routeLengthMeters: Float
        get() = waypoints.zipWithNext().fold(0f) { acc, (a, b) -> acc + (b - a).length() }
}

/**
 * The offline campus map.
 *
 * Hard-coded by design: V2 is still fully offline with no backend. Swapping
 * in a real survey later means replacing these lists, nothing else.
 */
object CampusMap {

    /**
     * Set this to match how you physically mount the reference image.
     * See the coordinate-system note above and V2_SETUP.md.
     */
    var mounting: ImageMounting = ImageMounting.VERTICAL

    /**
     * How high the board's centre sits above the floor, in metres.
     *
     * Used to drop the route from board height down to ground level so the
     * path visually lies on the floor rather than floating at chest height.
     * Only meaningful when [mounting] is [ImageMounting.VERTICAL].
     */
    var boardHeightMeters: Float = 1.5f

    val STADIUM = Destination(
        id = "dest-stadium",
        name = "Stadium",
        category = "Sports · 350 seats",
        detail = "Main athletics field and grandstand. Step-free access via " +
                 "the north gate. Open during scheduled events.",
        waypoints = listOf(
            Vec3(0.0f, 1.0f, 0f),
            Vec3(0.0f, 4.0f, 0f),
            Vec3(3.0f, 7.0f, 0f),
            Vec3(3.0f, 12.0f, 0f),
            Vec3(6.5f, 15.0f, 0f)
        )
    )

    val DORMS = Destination(
        id = "dest-dorms",
        name = "Dorms",
        category = "Residence · Blocks A–D",
        detail = "Student residence blocks with 24-hour reception in Block A. " +
                 "Laundry and common rooms on the ground floor.",
        waypoints = listOf(
            Vec3(0.0f, 1.0f, 0f),
            Vec3(-2.5f, 3.5f, 0f),
            Vec3(-5.0f, 6.0f, 0f),
            Vec3(-5.0f, 10.0f, 0f)
        )
    )

    val ENGINEERING = Destination(
        id = "dest-engineering",
        name = "Engineering College",
        category = "Faculty · Building E",
        detail = "Lecture halls, robotics lab and the fabrication workshop. " +
                 "Main entrance faces the central courtyard.",
        waypoints = listOf(
            Vec3(0.0f, 1.0f, 0f),
            Vec3(2.0f, 3.0f, 0f),
            Vec3(4.5f, 4.5f, 0f),
            Vec3(8.0f, 4.5f, 0f),
            Vec3(11.0f, 7.0f, 0f)
        )
    )

    val ALL: List<Destination> = listOf(STADIUM, DORMS, ENGINEERING)

    fun byId(id: String): Destination? = ALL.firstOrNull { it.id == id }
}

/**
 * Converts an image-local waypoint into the local space ARCore expects when
 * the pose of the tracked image is applied.
 *
 * ARCore image space is (+X right, +Y out of the face, +Z down the image).
 * We author routes as "X = sideways, Y = forwards", which this maps onto the
 * correct axes for the chosen [mounting].
 *
 * @param boardHeight height of the board centre above the floor, in metres
 */
fun toImageLocal(p: Vec3, mounting: ImageMounting, boardHeight: Float): Vec3 =
    when (mounting) {
        // Board on a wall: authored +Y (forwards) is the image's +Y (out of
        // the face). The floor is `boardHeight` below the board centre, which
        // in image space is +Z (down the image).
        ImageMounting.VERTICAL -> Vec3(p.x, p.y, boardHeight)

        // Marker flat on the floor facing up: the image plane already lies on
        // the ground, so authored +Y (forwards) maps to image +Z (down the
        // image, i.e. along the floor) and the route sits on the face itself.
        ImageMounting.FLAT -> Vec3(p.x, 0f, -p.y)
    }

/**
 * Resamples a waypoint route so segments are no longer than [maxSegment].
 *
 * The authored routes have long straight legs. Subdividing them keeps the
 * ribbon's animated pulse smooth and evenly paced along the whole path, and
 * avoids a single huge quad stretching across the scene.
 *
 * Corners are preserved exactly: every original waypoint stays in the output.
 */
fun resampleRoute(waypoints: List<Vec3>, maxSegment: Float = 0.5f): List<Vec3> {
    if (waypoints.size < 2 || maxSegment <= 0f) return waypoints

    val out = ArrayList<Vec3>(waypoints.size * 4)
    out.add(waypoints.first())

    for ((a, b) in waypoints.zipWithNext()) {
        val span = b - a
        val len = span.length()
        if (len < 1e-6f) continue

        // Number of sub-segments needed to keep each under maxSegment.
        val steps = kotlin.math.ceil(len / maxSegment).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            out.add(a + span * (i.toFloat() / steps))
        }
    }
    return out
}

/**
 * Distance from [from] to the end of the route, following the path.
 *
 * Used for the "x m" readout on the destination card. This is walking
 * distance along the waypoints, not straight-line distance, so it reflects
 * what the user will actually travel.
 */
fun remainingRouteDistance(route: List<Vec3>, from: Vec3): Float {
    if (route.size < 2) return 0f

    // Find the segment whose closest point is nearest to `from`, then sum the
    // remainder of the route from that point onward.
    var bestSegment = 0
    var bestT = 0f
    var bestDistSq = Float.MAX_VALUE

    for (i in 0 until route.size - 1) {
        val a = route[i]
        val span = route[i + 1] - a
        val lenSq = span.dot(span)
        val t = if (lenSq < 1e-9f) 0f else ((from - a).dot(span) / lenSq).coerceIn(0f, 1f)
        val closest = a + span * t
        val dSq = (from - closest).let { it.dot(it) }
        if (dSq < bestDistSq) {
            bestDistSq = dSq
            bestSegment = i
            bestT = t
        }
    }

    // Remainder of the segment the user is currently on...
    val a = route[bestSegment]
    val b = route[bestSegment + 1]
    var total = (b - (a + (b - a) * bestT)).length()

    // ...plus every segment after it.
    for (i in bestSegment + 1 until route.size - 1) {
        total += (route[i + 1] - route[i]).length()
    }
    return total
}
