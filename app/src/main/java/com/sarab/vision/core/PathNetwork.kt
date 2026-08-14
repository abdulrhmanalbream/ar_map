package com.sarab.vision.core

import java.util.PriorityQueue

/**
 * The campus path network and offline routing over it.
 *
 * ## Why route locally instead of calling a service
 *
 * The reference web map proxies OSRM's public demo server, which only exposes
 * a **driving** profile -- no walking, no cycling -- and needs the network for
 * every request. Neither is acceptable here: this app must work with no
 * connection, and a campus is mostly walked.
 *
 * A campus graph is small (hundreds of nodes, not millions), so A* over a
 * hand-drawn network runs in microseconds on a phone and needs no server at
 * all. The trade is that someone has to draw the paths once, which is what
 * admin mode is for.
 */

/** A junction or waypoint in the network. */
data class PathNode(
    val id: String,
    val position: LatLng
)

/**
 * A walkable/rideable/drivable segment between two nodes.
 *
 * Permissions are three independent flags rather than a single enum because
 * a segment is frequently usable by more than one mode -- a campus service
 * road takes cars and bikes, a plaza takes feet and bikes. Bicycles in
 * particular may use both roads and pedestrian paths.
 */
data class PathEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val allowsFoot: Boolean = true,
    val allowsBike: Boolean = true,
    val allowsCar: Boolean = false,
    /** Stairs block wheels entirely and matter for accessibility. */
    val hasStairs: Boolean = false,
    /** Optional name, e.g. "الممشى الرئيسي". */
    val name: String = ""
)

/** The whole drawn network. */
data class PathNetwork(
    val nodes: List<PathNode> = emptyList(),
    val edges: List<PathEdge> = emptyList()
) {
    private val nodesById: Map<String, PathNode> by lazy { nodes.associateBy { it.id } }

    /** Adjacency, built once and reused across routing calls. */
    private val adjacency: Map<String, List<PathEdge>> by lazy {
        val map = HashMap<String, MutableList<PathEdge>>()
        for (e in edges) {
            map.getOrPut(e.fromNodeId) { mutableListOf() }.add(e)
            // Edges are bidirectional: a campus footpath walks both ways, and
            // modelling one-way streets is not worth the complexity here.
            map.getOrPut(e.toNodeId) { mutableListOf() }.add(e)
        }
        map
    }

    fun node(id: String): PathNode? = nodesById[id]

    fun edgesFrom(nodeId: String): List<PathEdge> = adjacency[nodeId].orEmpty()

    /** The other end of [edge] relative to [nodeId]. */
    fun otherEnd(edge: PathEdge, nodeId: String): String =
        if (edge.fromNodeId == nodeId) edge.toNodeId else edge.fromNodeId

    fun edgeLength(edge: PathEdge): Double {
        val a = nodesById[edge.fromNodeId] ?: return Double.MAX_VALUE
        val b = nodesById[edge.toNodeId] ?: return Double.MAX_VALUE
        return distanceMeters(a.position, b.position)
    }

    /** Nearest node to a position, for snapping the user onto the network. */
    fun nearestNode(position: LatLng, maxDistanceM: Double = 60.0): PathNode? {
        if (!position.isValid || nodes.isEmpty()) return null
        return nodes
            .map { it to distanceMeters(position, it.position) }
            .filter { it.second <= maxDistanceM }
            .minByOrNull { it.second }
            ?.first
    }

    val isEmpty: Boolean get() = nodes.isEmpty() || edges.isEmpty()
}

/** A computed route. */
data class Route(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val mode: TravelMode,
    /** Node ids in order, useful for debugging and turn generation. */
    val nodeIds: List<String>
) {
    val minutes: Int get() = travelMinutes(distanceMeters, mode)
}

/**
 * Finds the shortest route between two nodes for the given travel mode.
 *
 * Plain A* with great-circle distance as the heuristic. That heuristic is
 * admissible (it never overestimates, since no path can be shorter than the
 * straight line), which is what guarantees the result is genuinely the
 * shortest and not merely a good guess.
 *
 * @return null when no route exists for this mode, which is a real outcome
 *   worth surfacing -- a car cannot reach a building served only by stairs.
 */
fun findRoute(
    network: PathNetwork,
    fromNodeId: String,
    toNodeId: String,
    mode: TravelMode
): Route? {
    if (network.isEmpty) return null
    val start = network.node(fromNodeId) ?: return null
    val goal = network.node(toNodeId) ?: return null
    if (fromNodeId == toNodeId) {
        return Route(listOf(start.position), 0.0, mode, listOf(fromNodeId))
    }

    val cameFrom = HashMap<String, String>()
    val gScore = HashMap<String, Double>().apply { put(fromNodeId, 0.0) }

    // Ordered by f = g + h. Ties broken arbitrarily; any admissible order works.
    val open = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
    open.add(fromNodeId to distanceMeters(start.position, goal.position))
    val closed = HashSet<String>()

    while (open.isNotEmpty()) {
        val (current, _) = open.poll() ?: break
        if (current == toNodeId) {
            return buildRoute(network, cameFrom, fromNodeId, toNodeId, mode)
        }
        if (!closed.add(current)) continue

        val currentG = gScore[current] ?: continue

        for (edge in network.edgesFrom(current)) {
            if (!mode.canUse(edge.allowsFoot, edge.allowsBike, edge.allowsCar)) continue
            // Stairs are impassable on wheels regardless of the other flags.
            if (edge.hasStairs && mode != TravelMode.WALK) continue

            val neighbour = network.otherEnd(edge, current)
            if (neighbour in closed) continue

            val tentative = currentG + network.edgeLength(edge)
            if (tentative < (gScore[neighbour] ?: Double.MAX_VALUE)) {
                cameFrom[neighbour] = current
                gScore[neighbour] = tentative
                val h = network.node(neighbour)
                    ?.let { distanceMeters(it.position, goal.position) }
                    ?: 0.0
                open.add(neighbour to tentative + h)
            }
        }
    }
    return null
}

private fun buildRoute(
    network: PathNetwork,
    cameFrom: Map<String, String>,
    fromNodeId: String,
    toNodeId: String,
    mode: TravelMode
): Route {
    val ids = ArrayList<String>()
    var cursor: String? = toNodeId
    while (cursor != null) {
        ids.add(cursor)
        if (cursor == fromNodeId) break
        cursor = cameFrom[cursor]
    }
    ids.reverse()

    val points = ids.mapNotNull { network.node(it)?.position }
    var distance = 0.0
    for (i in 0 until points.size - 1) {
        distance += distanceMeters(points[i], points[i + 1])
    }
    return Route(points, distance, mode, ids)
}

/**
 * Routes from a raw position to a landmark, snapping both onto the network.
 *
 * Falls back to a straight line when the network cannot serve the request, so
 * navigation still works before any paths have been drawn. The caller can
 * tell the difference: a fallback route has exactly two points.
 */
fun routeTo(
    network: PathNetwork,
    from: LatLng,
    to: LatLng,
    mode: TravelMode
): Route {
    val straight = Route(
        points = listOf(from, to),
        distanceMeters = distanceMeters(from, to),
        mode = mode,
        nodeIds = emptyList()
    )
    if (network.isEmpty || !from.isValid || !to.isValid) return straight

    val startNode = network.nearestNode(from) ?: return straight
    val endNode = network.nearestNode(to) ?: return straight

    val route = findRoute(network, startNode.id, endNode.id, mode) ?: return straight

    // Stitch the real endpoints on, so the line starts at the user's actual
    // position rather than jumping to the nearest junction.
    val points = buildList {
        add(from)
        addAll(route.points)
        add(to)
    }
    var distance = 0.0
    for (i in 0 until points.size - 1) {
        distance += distanceMeters(points[i], points[i + 1])
    }
    return route.copy(points = points, distanceMeters = distance)
}

/** A single turn instruction along a route. */
data class RouteStep(
    val instructionAr: String,
    val distanceMeters: Double,
    val position: LatLng
)

/**
 * Turns a route's geometry into spoken-style steps.
 *
 * Consecutive segments that continue roughly straight are merged, otherwise a
 * gently curving path produces a stream of "keep straight" instructions that
 * are noise rather than guidance.
 */
fun routeSteps(route: Route): List<RouteStep> {
    val pts = route.points
    if (pts.size < 2) return emptyList()

    val steps = mutableListOf<RouteStep>()
    var legStart = 0

    for (i in 1 until pts.size - 1) {
        val incoming = bearingDegrees(pts[i - 1], pts[i])
        val outgoing = bearingDegrees(pts[i], pts[i + 1])
        val turn = relativeBearing(incoming, outgoing)

        // Below this the path is effectively straight; merging avoids a
        // torrent of meaningless micro-instructions.
        if (kotlin.math.abs(turn) < 25.0) continue

        var legDistance = 0.0
        for (j in legStart until i) legDistance += distanceMeters(pts[j], pts[j + 1])

        steps.add(
            RouteStep(
                instructionAr = when {
                    turn > 100 -> "انعطف يميناً بزاوية حادة"
                    turn > 25 -> "انعطف يميناً"
                    turn < -100 -> "انعطف يساراً بزاوية حادة"
                    else -> "انعطف يساراً"
                },
                distanceMeters = legDistance,
                position = pts[i]
            )
        )
        legStart = i
    }

    var finalLeg = 0.0
    for (j in legStart until pts.size - 1) finalLeg += distanceMeters(pts[j], pts[j + 1])
    steps.add(RouteStep("وصلت إلى وجهتك", finalLeg, pts.last()))

    return steps
}
