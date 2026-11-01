import XCTest
@testable import MutawwifCore

/// The V2 route maths: a wrong resample or distance calculation is very hard to
/// spot by eye through a camera feed.
final class WaypointsTests: XCTestCase {

    private func pairs(_ v: [Vec3]) -> [(Vec3, Vec3)] {
        v.count < 2 ? [] : (1..<v.count).map { (v[$0 - 1], v[$0]) }
    }

    func testEveryDestinationHasAUsableRoute() {
        XCTAssertEqual(3, CampusMap.ALL.count)
        for d in CampusMap.ALL {
            XCTAssertGreaterThanOrEqual(d.waypoints.count, 2)
            XCTAssertGreaterThan(d.routeLengthMeters, 0)
            XCTAssertFalse(d.name.isEmpty)
        }
    }

    func testDestinationIdsAreUniqueAndResolvable() {
        let ids = CampusMap.ALL.map { $0.id }
        XCTAssertEqual(ids.count, Set(ids).count)
        for id in ids { XCTAssertEqual(id, CampusMap.byId(id)?.id) }
        XCTAssertNil(CampusMap.byId("does-not-exist"))
    }

    func testRouteLengthSumsTheSegments() {
        let d = Destination(id: "t", name: "T", category: "", detail: "",
                            waypoints: [Vec3(0, 0, 0), Vec3(0, 3, 0), Vec3(4, 3, 0)])
        XCTAssertEqual(7, d.routeLengthMeters, accuracy: 1e-4)
    }

    func testResamplingKeepsEveryOriginalCorner() {
        let route = [Vec3(0, 0, 0), Vec3(0, 4, 0), Vec3(3, 4, 0)]
        let dense = resampleRoute(route, maxSegment: 0.5)
        for corner in route {
            XCTAssertTrue(dense.contains { ($0 - corner).length() < 1e-4 }, "corner \(corner) must survive")
        }
    }

    func testResamplingRespectsTheMaxSegmentLength() {
        let dense = resampleRoute([Vec3(0, 0, 0), Vec3(0, 10, 0)], maxSegment: 0.5)
        for (a, b) in pairs(dense) {
            XCTAssertLessThanOrEqual((b - a).length(), 0.5 + 1e-4)
        }
    }

    func testResamplingPreservesTotalRouteLength() {
        let route = [Vec3(0, 0, 0), Vec3(0, 4, 0), Vec3(3, 4, 0)]
        let original = pairs(route).reduce(0.0) { $0 + Double(($1.1 - $1.0).length()) }
        let densified = pairs(resampleRoute(route, maxSegment: 0.3)).reduce(0.0) { $0 + Double(($1.1 - $1.0).length()) }
        XCTAssertEqual(original, densified, accuracy: 1e-3)
    }

    func testResamplingDegenerateInputDoesNotCrashOrLoseData() {
        XCTAssertEqual(0, resampleRoute([]).count)
        XCTAssertEqual(1, resampleRoute([Vec3.ZERO]).count)
        let dupes = resampleRoute([Vec3.ZERO, Vec3.ZERO, Vec3(0, 1, 0)], maxSegment: 0.5)
        XCTAssertTrue(dupes.allSatisfy { !$0.x.isNaN && !$0.y.isNaN && !$0.z.isNaN })
    }

    func testRemainingDistanceAtTheStartIsTheWholeRoute() {
        let route = [Vec3(0, 0, 0), Vec3(0, 3, 0), Vec3(4, 3, 0)]
        XCTAssertEqual(7, remainingRouteDistance(route, Vec3(0, 0, 0)), accuracy: 1e-3)
    }

    func testRemainingDistanceShrinksAsTheUserAdvances() {
        let route = [Vec3(0, 0, 0), Vec3(0, 10, 0)]
        let atStart = remainingRouteDistance(route, Vec3(0, 0, 0))
        let midway = remainingRouteDistance(route, Vec3(0, 6, 0))
        XCTAssertEqual(10, atStart, accuracy: 1e-3)
        XCTAssertEqual(4, midway, accuracy: 1e-3)
        XCTAssertEqual(0, remainingRouteDistance(route, Vec3(0, 10, 0)), accuracy: 1e-3)
        XCTAssertLessThan(midway, atStart)
    }

    func testRemainingDistanceHandlesAUserStandingOffThePath() {
        XCTAssertEqual(5, remainingRouteDistance([Vec3(0, 0, 0), Vec3(0, 10, 0)], Vec3(3, 5, 0)), accuracy: 1e-3)
    }

    func testVerticalMountingPutsTheRouteOnTheFloorBelowTheBoard() {
        let local = toImageLocal(Vec3(2, 5, 0), .vertical, 1.5)
        XCTAssertEqual(2, local.x, accuracy: 1e-4)
        XCTAssertEqual(5, local.y, accuracy: 1e-4)
        XCTAssertEqual(1.5, local.z, accuracy: 1e-4)
    }

    func testFlatMountingKeepsTheRouteOnTheImagePlane() {
        let local = toImageLocal(Vec3(2, 5, 0), .flat, 1.5)
        XCTAssertEqual(2, local.x, accuracy: 1e-4)
        XCTAssertEqual(0, local.y, accuracy: 1e-4)
        XCTAssertEqual(-5, local.z, accuracy: 1e-4)
    }
}
