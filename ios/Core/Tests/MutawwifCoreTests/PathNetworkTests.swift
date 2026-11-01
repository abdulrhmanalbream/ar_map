import XCTest
@testable import MutawwifCore

/// Offline routing over the drawn path network. The cases that matter are the
/// mode restrictions: a car must not be routed down a footpath, a bike must be
/// free to use both, and stairs must block anything on wheels.
final class PathNetworkTests: XCTestCase {

    private func at(_ n: Double, _ e: Double) -> LatLng { landmarkAt("x", n, e).position }

    ///   A --- footpath (100m) --- B
    ///    \                       /
    ///     road (150m) -- C -- road (150m)
    private func triangleNetwork() -> PathNetwork {
        let nodes = [
            PathNode(id: "A", position: at(0, 0)),
            PathNode(id: "B", position: at(0, 100)),
            PathNode(id: "C", position: at(-150, 50)),
        ]
        let edges = [
            PathEdge(id: "AB", fromNodeId: "A", toNodeId: "B", allowsFoot: true, allowsBike: true, allowsCar: false, name: "ممشى"),
            PathEdge(id: "AC", fromNodeId: "A", toNodeId: "C", allowsFoot: true, allowsBike: true, allowsCar: true, name: "طريق"),
            PathEdge(id: "CB", fromNodeId: "C", toNodeId: "B", allowsFoot: true, allowsBike: true, allowsCar: true, name: "طريق"),
        ]
        return PathNetwork(nodes: nodes, edges: edges)
    }

    func testWalkingTakesTheDirectFootpath() {
        let route = findRoute(triangleNetwork(), "A", "B", .walk)!
        XCTAssertEqual(["A", "B"], route.nodeIds)
        XCTAssertEqual(100.0, route.distanceMeters, accuracy: 3.0)
    }

    func testDrivingDetoursAroundTheFootpath() {
        let route = findRoute(triangleNetwork(), "A", "B", .car)!
        XCTAssertEqual(["A", "C", "B"], route.nodeIds)
        XCTAssertGreaterThan(route.distanceMeters, 250.0)
    }

    func testCyclingMayUseTheFootpath() {
        XCTAssertEqual(["A", "B"], findRoute(triangleNetwork(), "A", "B", .bike)!.nodeIds)
    }

    func testStairsBlockWheelsButNotFeet() {
        let network = PathNetwork(
            nodes: [PathNode(id: "A", position: at(0, 0)), PathNode(id: "B", position: at(0, 40))],
            edges: [PathEdge(id: "AB", fromNodeId: "A", toNodeId: "B", allowsFoot: true, allowsBike: true, allowsCar: false, hasStairs: true)]
        )
        XCTAssertNotNil(findRoute(network, "A", "B", .walk))
        XCTAssertNil(findRoute(network, "A", "B", .bike))
        XCTAssertNil(findRoute(network, "A", "B", .car))
    }

    func testNoRouteIsReportedHonestly() {
        let network = PathNetwork(
            nodes: [PathNode(id: "A", position: at(0, 0)), PathNode(id: "B", position: at(0, 100)), PathNode(id: "island", position: at(500, 500))],
            edges: [PathEdge(id: "AB", fromNodeId: "A", toNodeId: "B")]
        )
        XCTAssertNil(findRoute(network, "A", "island", .walk))
    }

    func testAStarFindsTheGenuinelyShortestPath() {
        let nodes = [
            PathNode(id: "S", position: at(0, 0)),
            PathNode(id: "top", position: at(20, 50)),
            PathNode(id: "bottom", position: at(-200, 50)),
            PathNode(id: "E", position: at(0, 100)),
        ]
        let edges = [
            PathEdge(id: "s-top", fromNodeId: "S", toNodeId: "top"),
            PathEdge(id: "top-e", fromNodeId: "top", toNodeId: "E"),
            PathEdge(id: "s-bottom", fromNodeId: "S", toNodeId: "bottom"),
            PathEdge(id: "bottom-e", fromNodeId: "bottom", toNodeId: "E"),
        ]
        let route = findRoute(PathNetwork(nodes: nodes, edges: edges), "S", "E", .walk)!
        XCTAssertTrue(route.nodeIds.contains("top"))
        XCTAssertFalse(route.nodeIds.contains("bottom"))
    }

    func testRoutingToItselfIsAZeroLengthRoute() {
        XCTAssertEqual(0.0, findRoute(triangleNetwork(), "A", "A", .walk)!.distanceMeters, accuracy: 0.001)
    }

    func testNearestNodeSnapsTheUserOntoTheNetwork() {
        let network = triangleNetwork()
        XCTAssertEqual("A", network.nearestNode(at(10, 0))?.id)
        XCTAssertNil(network.nearestNode(at(1000, 1000)))
    }

    func testRouteToFallsBackToAStraightLineWithNoNetwork() {
        let route = routeTo(PathNetwork(), at(0, 0), at(0, 200), .walk)
        XCTAssertEqual(2, route.points.count)
        XCTAssertEqual(200.0, route.distanceMeters, accuracy: 3.0)
    }

    func testRouteToStitchesTheRealEndpointsOntoTheNetworkPath() {
        let start = at(-5, -5)
        let end = at(5, 105)
        let route = routeTo(triangleNetwork(), start, end, .walk)
        XCTAssertEqual(start, route.points.first)
        XCTAssertEqual(end, route.points.last)
        XCTAssertGreaterThan(route.points.count, 2)
    }

    func testTravelTimeVariesByModeOverTheSameRoute() {
        let walk = findRoute(triangleNetwork(), "A", "B", .walk)!
        XCTAssertGreaterThanOrEqual(walk.minutes, 1)
        XCTAssertLessThanOrEqual(findRoute(triangleNetwork(), "A", "B", .bike)!.minutes, walk.minutes)
    }

    func testRouteStepsMergeStraightSegmentsAndEndWithArrival() {
        let points = [at(0, 0), at(50, 0), at(100, 0), at(100, 80)]
        let steps = routeSteps(Route(points: points, distanceMeters: 230.0, mode: .walk, nodeIds: []))
        XCTAssertEqual(2, steps.count)
        XCTAssertTrue(steps[0].instructionAr.contains("يمين"))
        XCTAssertTrue(steps.last!.instructionAr.contains("وصلت"))
    }

    func testRouteStepsHandleADegenerateRoute() {
        XCTAssertTrue(routeSteps(Route(points: [], distanceMeters: 0, mode: .walk, nodeIds: [])).isEmpty)
        XCTAssertTrue(routeSteps(Route(points: [at(0, 0)], distanceMeters: 0, mode: .walk, nodeIds: [])).isEmpty)
    }

    func testNetworkJsonRoundTrips() throws {
        let network = triangleNetwork()
        let data = try JSONEncoder().encode(network)
        let decoded = try JSONDecoder().decode(PathNetwork.self, from: data)
        XCTAssertEqual(network, decoded)
        XCTAssertEqual(["A", "B"], findRoute(decoded, "A", "B", .walk)!.nodeIds)
    }
}
