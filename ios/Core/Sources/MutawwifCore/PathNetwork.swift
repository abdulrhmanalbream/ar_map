import Foundation

// The campus path network and offline routing over it.
//
// ## Why route locally instead of calling a service
//
// The reference web map proxies OSRM's public demo server, which only exposes
// a **driving** profile -- no walking, no cycling -- and needs the network for
// every request. Neither is acceptable here: this app must work with no
// connection, and a campus is mostly walked.
//
// A campus graph is small (hundreds of nodes, not millions), so A* over a
// hand-drawn network runs in microseconds on a phone and needs no server at
// all. The trade is that someone has to draw the paths once, which is what
// admin mode is for.

/// A junction or waypoint in the network.
public struct PathNode: Equatable, Hashable, Codable, Identifiable {
    public var id: String
    public var position: LatLng

    public init(id: String, position: LatLng) {
        self.id = id
        self.position = position
    }
}

/// A walkable/rideable/drivable segment between two nodes.
///
/// Permissions are three independent flags rather than a single enum because
/// a segment is frequently usable by more than one mode -- a campus service
/// road takes cars and bikes, a plaza takes feet and bikes. Bicycles in
/// particular may use both roads and pedestrian paths.
public struct PathEdge: Equatable, Hashable, Codable, Identifiable {
    public var id: String
    public var fromNodeId: String
    public var toNodeId: String
    public var allowsFoot: Bool
    public var allowsBike: Bool
    public var allowsCar: Bool
    /// Stairs block wheels entirely and matter for accessibility.
    public var hasStairs: Bool
    /// Optional name, e.g. "الممشى الرئيسي".
    public var name: String

    public init(
        id: String,
        fromNodeId: String,
        toNodeId: String,
        allowsFoot: Bool = true,
        allowsBike: Bool = true,
        allowsCar: Bool = false,
        hasStairs: Bool = false,
        name: String = ""
    ) {
        self.id = id
        self.fromNodeId = fromNodeId
        self.toNodeId = toNodeId
        self.allowsFoot = allowsFoot
        self.allowsBike = allowsBike
        self.allowsCar = allowsCar
        self.hasStairs = hasStairs
        self.name = name
    }
}

/// The whole drawn network.
///
/// Immutable value: the lookup tables Kotlin built lazily are built once in
/// `init` here, because a lazy property on a Swift struct would make every
/// read `mutating`. Modify by constructing a new network (see `copy`).
public struct PathNetwork: Equatable, Codable {
    public let nodes: [PathNode]
    public let edges: [PathEdge]

    private let nodesById: [String: PathNode]
    /// Adjacency, built once and reused across routing calls.
    private let adjacency: [String: [PathEdge]]

    public init(nodes: [PathNode] = [], edges: [PathEdge] = []) {
        self.nodes = nodes
        self.edges = edges
        var byId: [String: PathNode] = [:]
        // Kotlin associateBy keeps the LAST entry for a duplicate key.
        for n in nodes { byId[n.id] = n }
        self.nodesById = byId
        var map: [String: [PathEdge]] = [:]
        for e in edges {
            map[e.fromNodeId, default: []].append(e)
            // Edges are bidirectional: a campus footpath walks both ways, and
            // modelling one-way streets is not worth the complexity here.
            map[e.toNodeId, default: []].append(e)
        }
        self.adjacency = map
    }

    public func copy(nodes: [PathNode]? = nil, edges: [PathEdge]? = nil) -> PathNetwork {
        return PathNetwork(nodes: nodes ?? self.nodes, edges: edges ?? self.edges)
    }

    public static func == (a: PathNetwork, b: PathNetwork) -> Bool {
        return a.nodes == b.nodes && a.edges == b.edges
    }

    private enum CodingKeys: String, CodingKey { case nodes, edges }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            nodes: try c.decodeIfPresent([PathNode].self, forKey: .nodes) ?? [],
            edges: try c.decodeIfPresent([PathEdge].self, forKey: .edges) ?? []
        )
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(nodes, forKey: .nodes)
        try c.encode(edges, forKey: .edges)
    }

    public func node(_ id: String) -> PathNode? { nodesById[id] }

    public func edgesFrom(_ nodeId: String) -> [PathEdge] { adjacency[nodeId] ?? [] }

    /// The other end of `edge` relative to `nodeId`.
    public func otherEnd(_ edge: PathEdge, _ nodeId: String) -> String {
        return edge.fromNodeId == nodeId ? edge.toNodeId : edge.fromNodeId
    }

    public func edgeLength(_ edge: PathEdge) -> Double {
        guard let a = nodesById[edge.fromNodeId], let b = nodesById[edge.toNodeId] else {
            return Double.greatestFiniteMagnitude
        }
        return distanceMeters(a.position, b.position)
    }

    /// Nearest node to a position, for snapping the user onto the network.
    public func nearestNode(_ position: LatLng, maxDistanceM: Double = 60.0) -> PathNode? {
        if !position.isValid || nodes.isEmpty { return nil }
        var best: PathNode? = nil
        var bestD = Double.infinity
        for n in nodes {
            let d = distanceMeters(position, n.position)
            // Strict < keeps the first of equals, like minByOrNull.
            if d <= maxDistanceM && d < bestD {
                best = n
                bestD = d
            }
        }
        return best
    }

    public var isEmpty: Bool { nodes.isEmpty || edges.isEmpty }
}

/// A computed route.
public struct Route: Equatable {
    public var points: [LatLng]
    public var distanceMeters: Double
    public var mode: TravelMode
    /// Node ids in order, useful for debugging and turn generation.
    public var nodeIds: [String]

    public init(points: [LatLng], distanceMeters: Double, mode: TravelMode, nodeIds: [String]) {
        self.points = points
        self.distanceMeters = distanceMeters
        self.mode = mode
        self.nodeIds = nodeIds
    }

    public var minutes: Int { travelMinutes(distanceMeters, mode) }
}

/// Minimal binary min-heap for A*'s open set (Swift has no PriorityQueue).
struct MinHeap<T> {
    private var items: [(T, Double)] = []

    var isEmpty: Bool { items.isEmpty }

    mutating func push(_ value: T, _ priority: Double) {
        items.append((value, priority))
        var i = items.count - 1
        while i > 0 {
            let parent = (i - 1) / 2
            if items[parent].1 <= items[i].1 { break }
            items.swapAt(parent, i)
            i = parent
        }
    }

    mutating func pop() -> (T, Double)? {
        if items.isEmpty { return nil }
        items.swapAt(0, items.count - 1)
        let top = items.removeLast()
        var i = 0
        while true {
            let l = 2 * i + 1
            let r = l + 1
            var smallest = i
            if l < items.count && items[l].1 < items[smallest].1 { smallest = l }
            if r < items.count && items[r].1 < items[smallest].1 { smallest = r }
            if smallest == i { break }
            items.swapAt(i, smallest)
            i = smallest
        }
        return top
    }
}

/// Finds the shortest route between two nodes for the given travel mode.
///
/// Plain A* with great-circle distance as the heuristic. That heuristic is
/// admissible (it never overestimates, since no path can be shorter than the
/// straight line), which is what guarantees the result is genuinely the
/// shortest and not merely a good guess.
///
/// - Returns: nil when no route exists for this mode, which is a real outcome
///   worth surfacing -- a car cannot reach a building served only by stairs.
public func findRoute(
    _ network: PathNetwork,
    _ fromNodeId: String,
    _ toNodeId: String,
    _ mode: TravelMode
) -> Route? {
    if network.isEmpty { return nil }
    guard let start = network.node(fromNodeId), let goal = network.node(toNodeId) else { return nil }
    if fromNodeId == toNodeId {
        return Route(points: [start.position], distanceMeters: 0.0, mode: mode, nodeIds: [fromNodeId])
    }

    var cameFrom: [String: String] = [:]
    var gScore: [String: Double] = [fromNodeId: 0.0]

    // Ordered by f = g + h. Ties broken arbitrarily; any admissible order works.
    var open = MinHeap<String>()
    open.push(fromNodeId, distanceMeters(start.position, goal.position))
    var closed = Set<String>()

    while let popped = open.pop() {
        let current = popped.0
        if current == toNodeId {
            return buildRoute(network, cameFrom, fromNodeId, toNodeId, mode)
        }
        if !closed.insert(current).inserted { continue }

        guard let currentG = gScore[current] else { continue }

        for edge in network.edgesFrom(current) {
            if !mode.canUse(edge.allowsFoot, edge.allowsBike, edge.allowsCar) { continue }
            // Stairs are impassable on wheels regardless of the other flags.
            if edge.hasStairs && mode != .walk { continue }

            let neighbour = network.otherEnd(edge, current)
            if closed.contains(neighbour) { continue }

            let tentative = currentG + network.edgeLength(edge)
            if tentative < (gScore[neighbour] ?? Double.greatestFiniteMagnitude) {
                cameFrom[neighbour] = current
                gScore[neighbour] = tentative
                let h = network.node(neighbour).map { distanceMeters($0.position, goal.position) } ?? 0.0
                open.push(neighbour, tentative + h)
            }
        }
    }
    return nil
}

public func findRoute(network: PathNetwork, fromNodeId: String, toNodeId: String, mode: TravelMode) -> Route? {
    findRoute(network, fromNodeId, toNodeId, mode)
}

private func buildRoute(
    _ network: PathNetwork,
    _ cameFrom: [String: String],
    _ fromNodeId: String,
    _ toNodeId: String,
    _ mode: TravelMode
) -> Route {
    var ids: [String] = []
    var cursor: String? = toNodeId
    while let c = cursor {
        ids.append(c)
        if c == fromNodeId { break }
        cursor = cameFrom[c]
    }
    ids.reverse()

    let points = ids.compactMap { network.node($0)?.position }
    var distance = 0.0
    if points.count > 1 {
        for i in 0..<(points.count - 1) {
            distance += distanceMeters(points[i], points[i + 1])
        }
    }
    return Route(points: points, distanceMeters: distance, mode: mode, nodeIds: ids)
}

/// Routes from a raw position to a landmark, snapping both onto the network.
///
/// Falls back to a straight line when the network cannot serve the request, so
/// navigation still works before any paths have been drawn. The caller can
/// tell the difference: a fallback route has exactly two points.
public func routeTo(
    _ network: PathNetwork,
    _ from: LatLng,
    _ to: LatLng,
    _ mode: TravelMode
) -> Route {
    let straight = Route(
        points: [from, to],
        distanceMeters: distanceMeters(from, to),
        mode: mode,
        nodeIds: []
    )
    if network.isEmpty || !from.isValid || !to.isValid { return straight }

    guard let startNode = network.nearestNode(from),
          let endNode = network.nearestNode(to),
          let route = findRoute(network, startNode.id, endNode.id, mode) else { return straight }

    // Stitch the real endpoints on, so the line starts at the user's actual
    // position rather than jumping to the nearest junction.
    let points = [from] + route.points + [to]
    var distance = 0.0
    for i in 0..<(points.count - 1) {
        distance += distanceMeters(points[i], points[i + 1])
    }
    var result = route
    result.points = points
    result.distanceMeters = distance
    return result
}

public func routeTo(network: PathNetwork, from: LatLng, to: LatLng, mode: TravelMode) -> Route {
    routeTo(network, from, to, mode)
}

/// A single turn instruction along a route.
public struct RouteStep: Equatable {
    public var instructionAr: String
    public var distanceMeters: Double
    public var position: LatLng

    public init(instructionAr: String, distanceMeters: Double, position: LatLng) {
        self.instructionAr = instructionAr
        self.distanceMeters = distanceMeters
        self.position = position
    }
}

/// Turns a route's geometry into spoken-style steps.
///
/// Consecutive segments that continue roughly straight are merged, otherwise a
/// gently curving path produces a stream of "keep straight" instructions that
/// are noise rather than guidance.
public func routeSteps(_ route: Route) -> [RouteStep] {
    let pts = route.points
    if pts.count < 2 { return [] }

    var steps: [RouteStep] = []
    var legStart = 0

    var i = 1
    while i < pts.count - 1 {
        defer { i += 1 }
        let incoming = bearingDegrees(pts[i - 1], pts[i])
        let outgoing = bearingDegrees(pts[i], pts[i + 1])
        let turn = relativeBearing(incoming, outgoing)

        // Below this the path is effectively straight; merging avoids a
        // torrent of meaningless micro-instructions.
        if abs(turn) < 25.0 { continue }

        var legDistance = 0.0
        for j in legStart..<i { legDistance += distanceMeters(pts[j], pts[j + 1]) }

        let text: String
        if turn > 100 { text = "انعطف يميناً بزاوية حادة" }
        else if turn > 25 { text = "انعطف يميناً" }
        else if turn < -100 { text = "انعطف يساراً بزاوية حادة" }
        else { text = "انعطف يساراً" }

        steps.append(RouteStep(instructionAr: text, distanceMeters: legDistance, position: pts[i]))
        legStart = i
    }

    var finalLeg = 0.0
    for j in legStart..<(pts.count - 1) { finalLeg += distanceMeters(pts[j], pts[j + 1]) }
    steps.append(RouteStep(instructionAr: "وصلت إلى وجهتك", distanceMeters: finalLeg, position: pts[pts.count - 1]))

    return steps
}
