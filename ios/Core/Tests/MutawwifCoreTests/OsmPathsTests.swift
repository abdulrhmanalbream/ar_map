import XCTest
@testable import MutawwifCore

/// Reading OpenStreetMap tags into routing permissions. A mis-read tag does not
/// crash, it quietly routes a car down a staircase.
final class OsmPathsTests: XCTestCase {

    private func way(_ highway: String, foot: String? = nil, bicycle: String? = nil,
                     motorVehicle: String? = nil, access: String? = nil) -> WayPermissions? {
        permissionsFor(OsmWayTags(highway: highway, foot: foot, bicycle: bicycle, motorVehicle: motorVehicle, access: access))
    }

    func testAFootwayIsForFeetAndBikesButNotCars() {
        let p = way("footway")!
        XCTAssertTrue(p.allowsFoot)
        XCTAssertTrue(p.allowsBike)
        XCTAssertFalse(p.allowsCar)
    }

    func testAResidentialRoadTakesAllThree() {
        let p = way("residential")!
        XCTAssertTrue(p.allowsFoot && p.allowsBike && p.allowsCar)
    }

    func testAServiceRoadTakesCars() {
        XCTAssertTrue(way("service")!.allowsCar)
    }

    func testStepsBlockWheelsEntirely() {
        let p = way("steps")!
        XCTAssertTrue(p.allowsFoot)
        XCTAssertFalse(p.allowsBike)
        XCTAssertFalse(p.allowsCar)
        XCTAssertTrue(p.hasStairs)
    }

    func testStepsStayClosedToBikesEvenWhenTaggedOtherwise() {
        XCTAssertFalse(way("steps", bicycle: "yes")!.allowsBike)
    }

    func testAMotorwayIsNotWalkable() {
        let p = way("motorway")!
        XCTAssertFalse(p.allowsFoot)
        XCTAssertFalse(p.allowsBike)
        XCTAssertTrue(p.allowsCar)
    }

    func testExplicitTagsOverrideTheDefaultsInBothDirections() {
        XCTAssertFalse(way("footway", foot: "no")!.allowsFoot)
        XCTAssertFalse(way("path", bicycle: "no")!.allowsBike)
        XCTAssertFalse(way("residential", motorVehicle: "no")!.allowsCar)
        XCTAssertTrue(way("cycleway", foot: "designated")!.allowsFoot)
    }

    func testAPedestrianPlazaKeepsCarsOut() {
        let p = way("pedestrian")!
        XCTAssertTrue(p.allowsFoot)
        XCTAssertTrue(p.allowsBike)
        XCTAssertFalse(p.allowsCar)
    }

    func testPrivateAccessDropsTheWay() {
        XCTAssertNil(way("service", access: "private"))
        XCTAssertNil(way("footway", access: "no"))
    }

    func testPrivateAccessCanBeReopenedForOneMode() {
        let p = way("service", foot: "yes", access: "private")
        XCTAssertNotNil(p)
        XCTAssertTrue(p!.allowsFoot)
        XCTAssertFalse(p!.allowsCar)
    }

    func testThingsThatAreNotThoroughfaresAreDropped() {
        XCTAssertNil(permissionsFor(OsmWayTags(highway: "")))
        XCTAssertNil(permissionsFor(OsmWayTags(highway: "bus_stop")))
        XCTAssertNil(permissionsFor(OsmWayTags(highway: "street_lamp")))
    }

    func testTheQueryNamesTheBoundingBoxInTheOrderOverpassExpects() {
        let q = overpassQuery(south: 24.4, west: 39.6, north: 24.5, east: 39.7)
        XCTAssertTrue(q.contains("[out:json]"))
        XCTAssertTrue(q.contains("way[\"highway\"]"))
        XCTAssertTrue(q.contains("24.400000,39.600000,24.500000,39.700000"))
        XCTAssertTrue(q.contains("out geom"))
    }

    private func geomWay(_ id: Int64, _ highway: String, _ points: (Double, Double)...) -> OsmWay {
        OsmWay(id: id, tags: OsmWayTags(highway: highway), geometry: points.map { LatLng($0.0, $0.1) })
    }

    func testAWayBecomesOneEdgePerSegment() {
        let network = buildNetworkFromWays([geomWay(1, "footway", (24.0, 39.0), (24.001, 39.0), (24.002, 39.0))])
        XCTAssertEqual(3, network.nodes.count)
        XCTAssertEqual(2, network.edges.count)
    }

    func testTwoWaysMeetingAtAPointShareTheNode() {
        let junction = (24.001, 39.0)
        let network = buildNetworkFromWays([
            geomWay(1, "footway", (24.0, 39.0), junction),
            geomWay(2, "footway", junction, (24.002, 39.0)),
        ])
        XCTAssertEqual(3, network.nodes.count)
        let start = network.nodes.first { $0.position.latitude == 24.0 }!
        let end = network.nodes.first { $0.position.latitude == 24.002 }!
        XCTAssertNotNil(findRoute(network, start.id, end.id, .walk))
    }

    func testUnusableWaysNeverReachTheGraph() {
        let network = buildNetworkFromWays([
            OsmWay(id: 1, tags: OsmWayTags(highway: "service", access: "private"),
                   geometry: [LatLng(24.0, 39.0), LatLng(24.001, 39.0)]),
        ])
        XCTAssertTrue(network.edges.isEmpty)
        XCTAssertTrue(network.nodes.isEmpty)
    }

    func testAWayWithOnePointIsSkipped() {
        XCTAssertTrue(buildNetworkFromWays([geomWay(1, "footway", (24.0, 39.0))]).edges.isEmpty)
    }

    func testPermissionsCarryThroughToTheEdges() {
        let network = buildNetworkFromWays([geomWay(1, "steps", (24.0, 39.0), (24.001, 39.0))])
        XCTAssertEqual(1, network.edges.count)
        let edge = network.edges[0]
        XCTAssertTrue(edge.hasStairs)
        XCTAssertFalse(edge.allowsBike)
        XCTAssertTrue(edge.allowsFoot)
    }

    func testMergingIntoAnEmptyNetworkKeepsTheImportWhole() {
        let imported = buildNetworkFromWays([geomWay(1, "footway", (24.0, 39.0), (24.001, 39.0))])
        XCTAssertEqual(imported.edges.count, mergeNetworks(PathNetwork(), imported).edges.count)
    }

    func testHandDrawnEdgesSurviveAnImport() {
        let existing = PathNetwork(
            nodes: [PathNode(id: "a", position: LatLng(24.0, 39.0)), PathNode(id: "b", position: LatLng(24.001, 39.0))],
            edges: [PathEdge(id: "hand", fromNodeId: "a", toNodeId: "b")]
        )
        let imported = buildNetworkFromWays([geomWay(1, "footway", (24.010, 39.0), (24.011, 39.0))])
        let merged = mergeNetworks(existing, imported)
        XCTAssertTrue(merged.edges.contains { $0.id == "hand" })
        XCTAssertEqual(2, merged.edges.count)
    }

    func testImportedNodesSnapOntoNearbyHandDrawnOnes() {
        let existing = PathNetwork(nodes: [PathNode(id: "a", position: LatLng(24.0, 39.0))], edges: [])
        let imported = buildNetworkFromWays([geomWay(1, "footway", (24.000018, 39.0), (24.001, 39.0))])
        let merged = mergeNetworks(existing, imported)
        XCTAssertEqual(1, merged.edges.count)
        XCTAssertTrue(merged.edges[0].fromNodeId == "a" || merged.edges[0].toNodeId == "a")
    }

    func testImportingTheSameAreaTwiceDoesNotStackDuplicates() {
        let imported = buildNetworkFromWays([geomWay(1, "footway", (24.0, 39.0), (24.001, 39.0))])
        let once = mergeNetworks(PathNetwork(), imported)
        XCTAssertEqual(once.edges.count, mergeNetworks(once, imported).edges.count)
    }
}
