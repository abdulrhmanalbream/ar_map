import Foundation

// Turning OpenStreetMap ways into a routable campus network.
//
// ## Why OpenStreetMap and not Google
//
// Google Maps has no public API that returns a road *graph*. The Directions
// API answers "route me from A to B" one request at a time, online, and its
// terms forbid storing the result -- the opposite of what this app needs,
// since it routes offline over a network it holds locally.
//
// Overpass returns the raw ways and nodes for a bounding box, free, with no
// key, and under ODbL which permits keeping and shipping the data with
// attribution. Its `foot`, `bicycle` and `motor_vehicle` tags map straight onto
// `PathEdge.allowsFoot`, `allowsBike` and `allowsCar`.
//
// The catch: OSM coverage of a private campus is whatever volunteers have
// mapped. Perimeter roads are usually there; interior footpaths frequently are
// not. Import is a head start on drawing the network, not a replacement.
//
// Everything here is pure so the tag rules -- the part that is genuinely easy
// to get subtly wrong -- can be unit tested. Fetching and JSON live in the app.

/// The subset of an OSM way's tags that affects routing.
public struct OsmWayTags: Equatable {
    public var highway: String
    public var foot: String?
    public var bicycle: String?
    public var motorVehicle: String?
    public var access: String?
    public var name: String

    public init(
        highway: String,
        foot: String? = nil,
        bicycle: String? = nil,
        motorVehicle: String? = nil,
        access: String? = nil,
        name: String = ""
    ) {
        self.highway = highway
        self.foot = foot
        self.bicycle = bicycle
        self.motorVehicle = motorVehicle
        self.access = access
        self.name = name
    }
}

/// What a way permits, once its tags have been read.
public struct WayPermissions: Equatable {
    public var allowsFoot: Bool
    public var allowsBike: Bool
    public var allowsCar: Bool
    public var hasStairs: Bool

    public init(allowsFoot: Bool, allowsBike: Bool, allowsCar: Bool, hasStairs: Bool) {
        self.allowsFoot = allowsFoot
        self.allowsBike = allowsBike
        self.allowsCar = allowsCar
        self.hasStairs = hasStairs
    }

    /// A way nobody can use is not worth adding to the graph.
    public var isUseless: Bool { !allowsFoot && !allowsBike && !allowsCar }
}

/// Tag values that mean "you may not come through here".
let DENIED: Set<String> = ["no", "private", "destination_only"]

/// Tag values that mean "yes", including the designated/official variants.
let ALLOWED: Set<String> = ["yes", "designated", "permissive", "official", "destination"]

/// Ways that carry motor traffic, and therefore bikes, and usually feet.
///
/// `service` covers campus service roads and car parks, which are exactly the
/// links that connect buildings to the perimeter.
let ROAD_TYPES: Set<String> = [
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "unclassified", "residential", "service", "living_street", "road",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
]

/// Roads where walking is prohibited or suicidal unless tagged otherwise.
let MOTOR_ONLY: Set<String> = ["motorway", "trunk", "motorway_link", "trunk_link"]

/// Ways built for people on foot.
let FOOT_TYPES: Set<String> = ["footway", "path", "pedestrian", "steps", "track", "corridor"]

/// Reads a way's tags into routing permissions.
///
/// - Returns: nil when the way should be dropped entirely -- either it is not
///   a thoroughfare at all, or access is denied outright.
public func permissionsFor(_ tags: OsmWayTags) -> WayPermissions? {
    let highway = tags.highway.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if highway.isEmpty { return nil }

    // A blanket access=private/no closes the way to everyone, unless a
    // specific mode is re-permitted below.
    let blanketDenied = tags.access.map { DENIED.contains($0.lowercased()) } ?? false

    let isRoad = ROAD_TYPES.contains(highway)
    let isFootway = FOOT_TYPES.contains(highway)
    if !isRoad && !isFootway && highway != "cycleway" { return nil }

    let steps = highway == "steps"

    // Defaults from the way type.
    var foot: Bool
    if steps { foot = true }
    else if isFootway { foot = true }
    else if MOTOR_ONLY.contains(highway) { foot = false }
    else if isRoad { foot = true }
    else { foot = false } // cycleway and anything else

    // Bikes get the generous default the campus actually needs: they may use
    // car roads AND pedestrian paths. Only stairs and an explicit bicycle=no
    // stop them.
    var bike: Bool
    if steps { bike = false }
    else if highway == "pedestrian" { bike = true }
    else if isFootway { bike = true }
    else if MOTOR_ONLY.contains(highway) { bike = false }
    else if isRoad { bike = true }
    else if highway == "cycleway" { bike = true }
    else { bike = false }

    var car: Bool
    if highway == "pedestrian" { car = false }
    else if isFootway || highway == "cycleway" { car = false }
    else if isRoad { car = true }
    else { car = false }

    if blanketDenied {
        foot = false
        bike = false
        car = false
    }

    // Explicit per-mode tags always win over the defaults, in both directions.
    if let v = tags.foot?.lowercased() {
        if DENIED.contains(v) { foot = false } else if ALLOWED.contains(v) { foot = true }
    }
    if let v = tags.bicycle?.lowercased() {
        if DENIED.contains(v) { bike = false } else if ALLOWED.contains(v) { bike = true }
    }
    if let v = tags.motorVehicle?.lowercased() {
        if DENIED.contains(v) { car = false } else if ALLOWED.contains(v) { car = true }
    }

    // Stairs are not negotiable regardless of tagging.
    if steps {
        bike = false
        car = false
    }

    let result = WayPermissions(allowsFoot: foot, allowsBike: bike, allowsCar: car, hasStairs: steps)
    return result.isUseless ? nil : result
}

/// Builds the Overpass QL query for a bounding box.
///
/// Asks for ways plus their node geometry in one round trip (`out geom`), which
/// avoids a second request to resolve node ids. The filter is deliberately
/// broad -- everything with a `highway` tag -- because narrowing it here would
/// silently drop the odd `living_street` or `corridor` that a campus does use.
public func overpassQuery(south: Double, west: Double, north: Double, east: Double, timeoutSeconds: Int = 30) -> String {
    let bbox = formatFixed("%.6f,%.6f,%.6f,%.6f", south, west, north, east)
    return "[out:json][timeout:\(timeoutSeconds)];\nway[\"highway\"](\(bbox));\nout geom;"
}

/// A way as it comes back from Overpass: its tags and its shape.
public struct OsmWay: Equatable {
    public var id: Int64
    public var tags: OsmWayTags
    public var geometry: [LatLng]

    public init(id: Int64, tags: OsmWayTags, geometry: [LatLng]) {
        self.id = id
        self.tags = tags
        self.geometry = geometry
    }
}

/// Assembles ways into a connected `PathNetwork`.
///
/// ## Why coordinates are the node identity
///
/// Two OSM ways that meet at a junction share the node there, but after
/// fetching geometry the shared node arrives twice as two identical
/// coordinates. Keying nodes by rounded position rather than by array index is
/// what makes those two ways actually connect -- without it the import produces
/// a pile of disjoint lines that A* can never route across.
///
/// Six decimal places is about 0.1m, far below GPS error and far above the
/// float noise that would otherwise split a junction in two.
public func buildNetworkFromWays(_ ways: [OsmWay]) -> PathNetwork {
    var nodeOrder: [String] = []
    var nodesByKey: [String: PathNode] = [:]
    var edges: [PathEdge] = []

    func keyFor(_ p: LatLng) -> String { formatFixed("%.6f,%.6f", p.latitude, p.longitude) }

    for way in ways {
        guard let permissions = permissionsFor(way.tags) else { continue }
        let points = way.geometry.filter { $0.isValid }
        if points.count < 2 { continue }

        var previousId: String? = nil
        for point in points {
            let key = keyFor(point)
            let node: PathNode
            if let existing = nodesByKey[key] {
                node = existing
            } else {
                node = PathNode(id: "osm-\(key)", position: point)
                nodesByKey[key] = node
                nodeOrder.append(key)
            }
            let currentId = node.id

            if let prev = previousId, prev != currentId {
                edges.append(
                    PathEdge(
                        id: "osmw-\(way.id)-\(edges.count)",
                        fromNodeId: prev,
                        toNodeId: currentId,
                        allowsFoot: permissions.allowsFoot,
                        allowsBike: permissions.allowsBike,
                        allowsCar: permissions.allowsCar,
                        hasStairs: permissions.hasStairs,
                        name: way.tags.name
                    )
                )
            }
            previousId = currentId
        }
    }

    // Nodes no edge referenced would only bloat nearestNode's search.
    var used = Set<String>()
    for e in edges {
        used.insert(e.fromNodeId)
        used.insert(e.toNodeId)
    }
    let nodes = nodeOrder.compactMap { nodesByKey[$0] }.filter { used.contains($0.id) }
    return PathNetwork(nodes: nodes, edges: edges)
}

/// Merges imported paths into whatever is already drawn.
///
/// Hand-drawn edges are kept as-is and win any conflict: someone stood on the
/// campus and drew them, which beats a remote volunteer's tracing. Imported
/// nodes that land within `snapMetres` of an existing node reuse it, so the
/// two networks join up instead of sitting on top of each other unconnected.
public func mergeNetworks(_ existing: PathNetwork, _ imported: PathNetwork, snapMetres: Double = 6.0) -> PathNetwork {
    if existing.nodes.isEmpty { return imported }
    if imported.nodes.isEmpty { return existing }

    var nodes = existing.nodes
    var remap: [String: String] = [:]

    for node in imported.nodes {
        var nearby: PathNode? = nil
        var nearbyD = Double.infinity
        for candidate in nodes {
            let d = distanceMeters(node.position, candidate.position)
            if d <= snapMetres && d < nearbyD {
                nearby = candidate
                nearbyD = d
            }
        }

        if let n = nearby {
            remap[node.id] = n.id
        } else {
            nodes.append(node)
            remap[node.id] = node.id
        }
    }

    var edges = existing.edges
    // Existing edges, keyed by their endpoints, so re-importing the same area
    // twice does not stack duplicate lines on the map.
    var seen = Set(existing.edges.map { edgeKey($0.fromNodeId, $0.toNodeId) })

    for edge in imported.edges {
        guard let from = remap[edge.fromNodeId], let to = remap[edge.toNodeId] else { continue }
        if from == to { continue }
        let key = edgeKey(from, to)
        if !seen.insert(key).inserted { continue }
        var copy = edge
        copy.fromNodeId = from
        copy.toNodeId = to
        edges.append(copy)
    }

    return PathNetwork(nodes: nodes, edges: edges)
}

/// Undirected key: an edge is the same segment whichever way it is stored.
private func edgeKey(_ a: String, _ b: String) -> String {
    return a <= b ? "\(a)|\(b)" : "\(b)|\(a)"
}
