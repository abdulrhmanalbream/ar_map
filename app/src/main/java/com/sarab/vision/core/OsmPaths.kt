package com.sarab.vision.core

/**
 * Turning OpenStreetMap ways into a routable campus network.
 *
 * ## Why OpenStreetMap and not Google
 *
 * Google Maps has no public API that returns a road *graph*. The Directions
 * API answers "route me from A to B" one request at a time, online, and its
 * terms forbid storing the result -- which is the exact opposite of what this
 * app needs, since it routes offline over a network it holds locally.
 *
 * Overpass (the OSM query API) returns the raw ways and nodes for a bounding
 * box, free, with no key, and under ODbL which permits keeping and shipping
 * the data with attribution. It also carries the access tags this app already
 * models: `foot`, `bicycle` and `motor_vehicle` map straight onto
 * [PathEdge.allowsFoot], [PathEdge.allowsBike] and [PathEdge.allowsCar].
 *
 * The catch, and it must be said plainly: OSM coverage of a private campus is
 * whatever volunteers have mapped. Perimeter roads are usually there; interior
 * footpaths between buildings frequently are not. Import is a large head start
 * on drawing the network by hand, not a replacement for checking it.
 *
 * ## Why the parsing is split
 *
 * Everything in this file is pure Kotlin so the tag rules -- the part that is
 * genuinely easy to get subtly wrong -- can be unit tested. Fetching and JSON
 * live in the data layer.
 */

/** The subset of an OSM way's tags that affects routing. */
data class OsmWayTags(
    val highway: String,
    val foot: String? = null,
    val bicycle: String? = null,
    val motorVehicle: String? = null,
    val access: String? = null,
    val name: String = ""
)

/** What a way permits, once its tags have been read. */
data class WayPermissions(
    val allowsFoot: Boolean,
    val allowsBike: Boolean,
    val allowsCar: Boolean,
    val hasStairs: Boolean
) {
    /** A way nobody can use is not worth adding to the graph. */
    val isUseless: Boolean get() = !allowsFoot && !allowsBike && !allowsCar
}

/** Tag values that mean "you may not come through here". */
private val DENIED = setOf("no", "private", "destination_only")

/** Tag values that mean "yes", including the designated/official variants. */
private val ALLOWED = setOf("yes", "designated", "permissive", "official", "destination")

/**
 * Ways that carry motor traffic, and therefore bikes, and usually feet.
 *
 * `service` covers campus service roads and car parks, which are exactly the
 * links that connect buildings to the perimeter.
 */
private val ROAD_TYPES = setOf(
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "service", "living_street", "road",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
)

/** Roads where walking is prohibited or suicidal unless tagged otherwise. */
private val MOTOR_ONLY = setOf("motorway", "trunk", "motorway_link", "trunk_link")

/** Ways built for people on foot. */
private val FOOT_TYPES = setOf("footway", "path", "pedestrian", "steps", "track", "corridor")

/**
 * Reads a way's tags into routing permissions.
 *
 * @return null when the way should be dropped entirely -- either it is not a
 *   thoroughfare at all, or access is denied outright.
 */
fun permissionsFor(tags: OsmWayTags): WayPermissions? {
    val highway = tags.highway.trim().lowercase()
    if (highway.isEmpty()) return null

    // A blanket access=private/no closes the way to everyone, unless a
    // specific mode is re-permitted below.
    val blanketDenied = tags.access?.lowercase() in DENIED

    val isRoad = highway in ROAD_TYPES
    val isFootway = highway in FOOT_TYPES
    if (!isRoad && !isFootway && highway != "cycleway") return null

    val steps = highway == "steps"

    // Defaults from the way type.
    var foot = when {
        steps -> true
        isFootway -> true
        highway in MOTOR_ONLY -> false
        isRoad -> true
        highway == "cycleway" -> false
        else -> false
    }

    // Bikes get the generous default the campus actually needs: they may use
    // car roads AND pedestrian paths. Only stairs and an explicit bicycle=no
    // stop them.
    var bike = when {
        steps -> false
        highway == "pedestrian" -> true
        isFootway -> true
        highway in MOTOR_ONLY -> false
        isRoad -> true
        highway == "cycleway" -> true
        else -> false
    }

    var car = when {
        highway == "pedestrian" -> false
        isFootway || highway == "cycleway" -> false
        isRoad -> true
        else -> false
    }

    if (blanketDenied) {
        foot = false
        bike = false
        car = false
    }

    // Explicit per-mode tags always win over the defaults, in both directions.
    tags.foot?.lowercase()?.let { v ->
        if (v in DENIED) foot = false else if (v in ALLOWED) foot = true
    }
    tags.bicycle?.lowercase()?.let { v ->
        if (v in DENIED) bike = false else if (v in ALLOWED) bike = true
    }
    tags.motorVehicle?.lowercase()?.let { v ->
        if (v in DENIED) car = false else if (v in ALLOWED) car = true
    }

    // Stairs are not negotiable regardless of tagging.
    if (steps) {
        bike = false
        car = false
    }

    val result = WayPermissions(foot, bike, car, hasStairs = steps)
    return if (result.isUseless) null else result
}

/**
 * Builds the Overpass QL query for a bounding box.
 *
 * Asks for ways plus their node geometry in one round trip (`out geom`), which
 * avoids a second request to resolve node ids. The filter is deliberately
 * broad -- everything with a `highway` tag -- because narrowing it here would
 * silently drop the odd `living_street` or `corridor` that a campus does use.
 */
fun overpassQuery(south: Double, west: Double, north: Double, east: Double, timeoutSeconds: Int = 30): String {
    val bbox = "%.6f,%.6f,%.6f,%.6f".format(south, west, north, east)
    return """
        [out:json][timeout:$timeoutSeconds];
        way["highway"]($bbox);
        out geom;
    """.trimIndent()
}

/**
 * A way as it comes back from Overpass: its tags and its shape.
 */
data class OsmWay(
    val id: Long,
    val tags: OsmWayTags,
    val geometry: List<LatLng>
)

/**
 * Assembles ways into a connected [PathNetwork].
 *
 * ## Why coordinates are the node identity
 *
 * Two OSM ways that meet at a junction share the node there, but after
 * fetching geometry the shared node arrives twice as two identical
 * coordinates. Keying nodes by rounded position rather than by array index is
 * what makes those two ways actually connect -- without it the import produces
 * a pile of disjoint lines that A* can never route across, which looks correct
 * on the map and fails on every single route.
 *
 * Six decimal places is about 0.1m, far below GPS error and far above the
 * float noise that would otherwise split a junction in two.
 */
fun buildNetworkFromWays(ways: List<OsmWay>): PathNetwork {
    val nodesByKey = LinkedHashMap<String, PathNode>()
    val edges = mutableListOf<PathEdge>()

    fun keyFor(p: LatLng) = "%.6f,%.6f".format(p.latitude, p.longitude)

    for (way in ways) {
        val permissions = permissionsFor(way.tags) ?: continue
        val points = way.geometry.filter { it.isValid }
        if (points.size < 2) continue

        var previousId: String? = null
        for (point in points) {
            val key = keyFor(point)
            val node = nodesByKey.getOrPut(key) {
                PathNode(id = "osm-$key", position = point)
            }
            val currentId = node.id

            if (previousId != null && previousId != currentId) {
                edges.add(
                    PathEdge(
                        id = "osmw-${way.id}-${edges.size}",
                        fromNodeId = previousId,
                        toNodeId = currentId,
                        allowsFoot = permissions.allowsFoot,
                        allowsBike = permissions.allowsBike,
                        allowsCar = permissions.allowsCar,
                        hasStairs = permissions.hasStairs,
                        name = way.tags.name
                    )
                )
            }
            previousId = currentId
        }
    }

    // Nodes no edge referenced would only bloat nearestNode's search.
    val used = edges.flatMapTo(HashSet()) { listOf(it.fromNodeId, it.toNodeId) }
    return PathNetwork(
        nodes = nodesByKey.values.filter { it.id in used },
        edges = edges
    )
}

/**
 * Merges imported paths into whatever is already drawn.
 *
 * Hand-drawn edges are kept as-is and win any conflict: someone stood on the
 * campus and drew them, which beats a remote volunteer's tracing. Imported
 * nodes that land within [snapMetres] of an existing node reuse it, so the
 * two networks join up instead of sitting on top of each other unconnected.
 */
fun mergeNetworks(existing: PathNetwork, imported: PathNetwork, snapMetres: Double = 6.0): PathNetwork {
    if (existing.nodes.isEmpty()) return imported
    if (imported.nodes.isEmpty()) return existing

    val nodes = existing.nodes.toMutableList()
    val remap = HashMap<String, String>()

    for (node in imported.nodes) {
        val nearby = nodes
            .map { it to distanceMeters(node.position, it.position) }
            .filter { it.second <= snapMetres }
            .minByOrNull { it.second }
            ?.first

        if (nearby != null) {
            remap[node.id] = nearby.id
        } else {
            nodes.add(node)
            remap[node.id] = node.id
        }
    }

    val edges = existing.edges.toMutableList()
    // Existing edges, keyed by their endpoints, so re-importing the same area
    // twice does not stack duplicate lines on the map.
    val seen = existing.edges.mapTo(HashSet()) { edgeKey(it.fromNodeId, it.toNodeId) }

    for (edge in imported.edges) {
        val from = remap[edge.fromNodeId] ?: continue
        val to = remap[edge.toNodeId] ?: continue
        if (from == to) continue
        val key = edgeKey(from, to)
        if (!seen.add(key)) continue
        edges.add(edge.copy(fromNodeId = from, toNodeId = to))
    }

    return PathNetwork(nodes = nodes, edges = edges)
}

/** Undirected key: an edge is the same segment whichever way it is stored. */
private fun edgeKey(a: String, b: String): String =
    if (a <= b) "$a|$b" else "$b|$a"
