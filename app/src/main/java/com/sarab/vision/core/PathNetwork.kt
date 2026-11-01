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

    /** No network was used: the line goes through whatever is in the way. */
    val isStraightFallback: Boolean get() = nodeIds.isEmpty()
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
 * How far the user may be from every road and still be routed over the
 * network. Beyond this the nearest road is not plausibly where they will walk
 * first, and a straight line is the honest answer.
 */
const val START_SNAP_MAX_M = 250.0

/**
 * How far a door may sit from the network. The bundled OSM ways stop at the
 * kerb; the surveyed doors are 14-42 m back from it, across forecourts that
 * nobody has mapped, so the last leg is a short straight walk from the road.
 */
const val GOAL_SNAP_MAX_M = 150.0

/**
 * Douglas-Peucker tolerance. OSM traces wobble by a metre or two between
 * nodes; anything under this is drawing noise, not a turn a walker makes.
 */
const val ROUTE_SIMPLIFY_TOLERANCE_M = 2.5

/** A projection this close to an edge end reuses the existing junction. */
private const val SNAP_ENDPOINT_EPSILON_M = 0.5

private const val VIRTUAL_START_ID = "route-start"
private const val VIRTUAL_GOAL_ID = "route-goal"

/** The closest point on the network to a position, along a usable edge. */
data class EdgeSnap(
    val edge: PathEdge,
    val point: LatLng,
    /** 0 at [PathEdge.fromNodeId], 1 at [PathEdge.toNodeId]. */
    val fraction: Double,
    val distanceMeters: Double
)

/**
 * Projects [position] onto the nearest edge [mode] may use.
 *
 * Snapping to an edge rather than to the nearest *node* matters on real OSM
 * data: a long straight road has nodes only at its ends, so node snapping
 * sends someone standing mid-road 150 m back to a junction behind them before
 * the route even starts.
 */
fun snapToNetwork(
    network: PathNetwork,
    position: LatLng,
    mode: TravelMode,
    maxDistanceM: Double
): EdgeSnap? {
    if (!position.isValid || network.isEmpty) return null
    var best: EdgeSnap? = null
    for (edge in network.edges) {
        if (!mode.canUse(edge.allowsFoot, edge.allowsBike, edge.allowsCar)) continue
        if (edge.hasStairs && mode != TravelMode.WALK) continue
        val a = network.node(edge.fromNodeId)?.position ?: continue
        val b = network.node(edge.toNodeId)?.position ?: continue
        val (ax, ay) = toLocalMeters(position, a)
        val (bx, by) = toLocalMeters(position, b)
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 1e-9) 0.0
        else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val distance = kotlin.math.hypot(ax + t * dx, ay + t * dy)
        if (distance <= maxDistanceM && (best == null || distance < best.distanceMeters)) {
            best = EdgeSnap(edge, interpolate(a, b, t), t, distance)
        }
    }
    return best
}

private fun interpolate(a: LatLng, b: LatLng, t: Double) = LatLng(
    a.latitude + (b.latitude - a.latitude) * t,
    a.longitude + (b.longitude - a.longitude) * t
)

/**
 * Inserts a node at [snap] by splitting its edge in two, so A* can start or
 * end mid-edge. Returns the network to route on and the node id to use.
 */
private fun splitAt(network: PathNetwork, snap: EdgeSnap, nodeId: String): Pair<PathNetwork, String> {
    val from = network.node(snap.edge.fromNodeId) ?: return network to snap.edge.fromNodeId
    val to = network.node(snap.edge.toNodeId) ?: return network to snap.edge.toNodeId
    if (distanceMeters(snap.point, from.position) <= SNAP_ENDPOINT_EPSILON_M) return network to from.id
    if (distanceMeters(snap.point, to.position) <= SNAP_ENDPOINT_EPSILON_M) return network to to.id

    val virtual = PathNode(nodeId, snap.point)
    val edges = network.edges.filter { it.id != snap.edge.id } + listOf(
        snap.edge.copy(id = "${snap.edge.id}#$nodeId-a", toNodeId = nodeId),
        snap.edge.copy(id = "${snap.edge.id}#$nodeId-b", fromNodeId = nodeId)
    )
    return PathNetwork(network.nodes + virtual, edges) to nodeId
}

/**
 * Drops vertices that deviate from the line between their neighbours by less
 * than [toleranceM] (Douglas-Peucker), so turn detection sees the corners a
 * walker actually takes and not every kink in the OSM trace.
 */
fun simplifyPolyline(points: List<LatLng>, toleranceM: Double = ROUTE_SIMPLIFY_TOLERANCE_M): List<LatLng> {
    if (points.size <= 2) return points
    val origin = points.first()
    val xy = points.map { toLocalMeters(origin, it) }
    val keep = BooleanArray(points.size).apply { this[0] = true; this[lastIndex] = true }

    fun mark(first: Int, last: Int) {
        if (last <= first + 1) return
        val (x1, y1) = xy[first]
        val (x2, y2) = xy[last]
        val dx = x2 - x1
        val dy = y2 - y1
        val length = kotlin.math.hypot(dx, dy)
        var worst = -1
        var worstDistance = 0.0
        for (i in first + 1 until last) {
            val (x, y) = xy[i]
            val d = if (length < 1e-9) kotlin.math.hypot(x - x1, y - y1)
            else kotlin.math.abs(dy * (x - x1) - dx * (y - y1)) / length
            if (d > worstDistance) { worstDistance = d; worst = i }
        }
        if (worst >= 0 && worstDistance > toleranceM) {
            keep[worst] = true
            mark(first, worst)
            mark(worst, last)
        }
    }
    mark(0, points.lastIndex)
    return points.filterIndexed { i, _ -> keep[i] }
}

/**
 * Routes from a raw position to a landmark door over the network.
 *
 * Both ends are projected onto the nearest usable edge, the route runs A*
 * between those projections, and the real endpoints are stitched on so the
 * line starts at the user and ends at the door. The polyline is then
 * simplified so its corners are real turns.
 *
 * Falls back to a straight line when the network cannot serve the request, so
 * navigation still works before any paths exist. The caller can tell the
 * difference with [Route.isStraightFallback].
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

    val startSnap = snapToNetwork(network, from, mode, START_SNAP_MAX_M) ?: return straight
    val (withStart, startId) = splitAt(network, startSnap, VIRTUAL_START_ID)
    // Snapped against the already-split network, so a start and goal on the
    // same edge land on different halves instead of one overwriting the other.
    val goalSnap = snapToNetwork(withStart, to, mode, GOAL_SNAP_MAX_M) ?: return straight
    val (withBoth, goalId) = splitAt(withStart, goalSnap, VIRTUAL_GOAL_ID)

    val found = findRoute(withBoth, startId, goalId, mode) ?: return straight

    val stitched = buildList {
        add(from)
        found.points.forEach { p -> if (distanceMeters(last(), p) > SNAP_ENDPOINT_EPSILON_M) add(p) }
        if (distanceMeters(last(), to) > SNAP_ENDPOINT_EPSILON_M) add(to) else if (size > 1) set(lastIndex, to)
    }
    val points = simplifyPolyline(stitched)
    var distance = 0.0
    for (i in 0 until points.size - 1) {
        distance += distanceMeters(points[i], points[i + 1])
    }
    return Route(points, distance, mode, found.nodeIds)
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
