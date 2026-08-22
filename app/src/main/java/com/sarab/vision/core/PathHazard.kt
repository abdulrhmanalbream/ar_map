package com.sarab.vision.core

/**
 * What the drawn path should say about the stretch ahead.
 *
 * Colour is the fastest channel available while someone is walking and only
 * glancing at the screen, and road signage has already trained everyone what
 * these three mean. Using that vocabulary costs nothing and needs no legend.
 */
enum class PathHazard {
    /** Continue as you are. */
    CLEAR,

    /** A turn is coming up within the visible stretch. */
    TURN,

    /** Stairs. Blocks wheels entirely and matters for accessibility. */
    STAIRS;

    /** RGBA for the ribbon. */
    val ribbonColour: FloatArray
        get() = when (this) {
            CLEAR -> floatArrayOf(1.00f, 0.84f, 0.10f, 0.92f)   // yellow
            TURN -> floatArrayOf(1.00f, 0.55f, 0.05f, 0.94f)    // orange
            STAIRS -> floatArrayOf(0.95f, 0.24f, 0.20f, 0.95f)  // red
        }

    /**
     * RGBA for the chevrons.
     *
     * Kept brighter than the ribbon so the arrows stay legible on top of it in
     * direct sun, which is the condition this is actually used in.
     */
    val arrowColour: FloatArray
        get() = when (this) {
            CLEAR -> floatArrayOf(1.00f, 0.97f, 0.75f, 0.98f)
            TURN -> floatArrayOf(1.00f, 0.80f, 0.45f, 0.98f)
            STAIRS -> floatArrayOf(1.00f, 0.72f, 0.68f, 0.98f)
        }

    /** Warning to show on screen, or null when there is nothing to say. */
    val warningAr: String?
        get() = when (this) {
            CLEAR -> null
            TURN -> null
            STAIRS -> "درج في الطريق — غير مناسب للعجلات"
        }
}

/** Turn sharper than this counts as a turn worth colouring for. */
private const val TURN_THRESHOLD_DEG = 30.0

/**
 * Decides what the visible stretch of path should be coloured.
 *
 * Stairs win over everything: a turn is a manoeuvre, stairs are a barrier, and
 * someone pushing a wheelchair needs to know before they commit to the ramp.
 *
 * @param groundPath the path as drawn, in camera-relative metres
 * @param hasStairsAhead whether the route crosses a stepped edge in this stretch
 */
fun pathHazardFor(groundPath: List<Vec3>, hasStairsAhead: Boolean): PathHazard = when {
    hasStairsAhead -> PathHazard.STAIRS
    groundPathTurnDegrees(groundPath) >= TURN_THRESHOLD_DEG -> PathHazard.TURN
    else -> PathHazard.CLEAR
}

/**
 * Whether the route reaches a stepped edge within [withinMeters].
 *
 * Only the stretch actually being walked matters. Colouring the whole path red
 * because of stairs 200m away would make the warning meaningless long before
 * anyone reached them.
 */
fun stairsAhead(
    route: Route?,
    network: PathNetwork,
    from: LatLng,
    withinMeters: Double = 30.0
): Boolean {
    if (route == null || !from.isValid) return false

    var travelled = 0.0
    var previous: LatLng? = null

    for (nodeId in route.nodeIds) {
        val node = network.node(nodeId) ?: continue
        previous?.let { travelled += distanceMeters(it, node.position) }
        previous = node.position
        if (travelled > withinMeters) return false

        // Any stepped edge leaving this node counts: the route may take it.
        val stepped = network.edgesFrom(nodeId).any {
            it.hasStairs && route.nodeIds.contains(network.otherEnd(it, nodeId))
        }
        if (stepped) return true
    }
    return false
}
