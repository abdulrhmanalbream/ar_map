import XCTest
@testable import MutawwifCore

/// Colouring the drawn path by what is actually on it: yellow to continue,
/// orange through a turn, red for stairs.
final class PathHazardTests: XCTestCase {

    private func straight() -> [Vec3] { [Vec3(0, 0, 0), Vec3(0, 0, -5), Vec3(0, 0, -10)] }

    /// Ninety degrees to the right, halfway along.
    private func corner() -> [Vec3] { [Vec3(0, 0, 0), Vec3(0, 0, -5), Vec3(5, 0, -5), Vec3(10, 0, -5)] }

    func testAStraightStretchIsClear() {
        XCTAssertEqual(.clear, pathHazardFor(straight(), false))
    }

    func testACornerIsATurn() {
        XCTAssertEqual(.turn, pathHazardFor(corner(), false))
    }

    func testAGentleBendIsNotATurn() {
        XCTAssertEqual(.clear, pathHazardFor([Vec3(0, 0, 0), Vec3(0, 0, -5), Vec3(1, 0, -10)], false))
    }

    func testStairsWinOverATurn() {
        XCTAssertEqual(.stairs, pathHazardFor(corner(), true))
        XCTAssertEqual(.stairs, pathHazardFor(straight(), true))
    }

    func testOnlyStairsCarryAWrittenWarning() {
        XCTAssertNil(PathHazard.clear.warningAr)
        XCTAssertNil(PathHazard.turn.warningAr)
        XCTAssertTrue(PathHazard.stairs.warningAr!.contains("درج"))
    }

    func testEveryHazardHasADistinctColour() {
        let colours = PathHazard.allCases.map { $0.ribbonColour }
        XCTAssertEqual(colours.count, Set(colours).count)
    }

    func testAnEmptyPathIsClearRatherThanCrashing() {
        XCTAssertEqual(.clear, pathHazardFor([], false))
        XCTAssertEqual(.clear, pathHazardFor([Vec3(0, 0, 0)], false))
    }

    private let a = LatLng(24.4808, 39.5596)
    private var b: LatLng { stepAlongBearing(a, 0.0, 10.0) }
    private var c: LatLng { stepAlongBearing(b, 0.0, 10.0) }

    private func network(_ stairsOnSecond: Bool) -> PathNetwork {
        PathNetwork(
            nodes: [PathNode(id: "a", position: a), PathNode(id: "b", position: b), PathNode(id: "c", position: c)],
            edges: [
                PathEdge(id: "e1", fromNodeId: "a", toNodeId: "b"),
                PathEdge(id: "e2", fromNodeId: "b", toNodeId: "c", hasStairs: stairsOnSecond),
            ]
        )
    }

    private func route() -> Route {
        Route(points: [a, b, c], distanceMeters: 20.0, mode: .walk, nodeIds: ["a", "b", "c"])
    }

    func testStairsOnTheRouteAheadAreFound() {
        XCTAssertTrue(stairsAhead(route(), network(true), a))
    }

    func testARouteWithNoStairsReportsNone() {
        XCTAssertFalse(stairsAhead(route(), network(false), a))
    }

    func testDistantStairsAreNotWarnedAboutYet() {
        XCTAssertFalse(stairsAhead(route(), network(true), a, withinMeters: 5.0))
    }

    func testNoRouteMeansNoWarning() {
        XCTAssertFalse(stairsAhead(nil, network(true), a))
    }

    func testAnInvalidPositionIsHandled() {
        XCTAssertFalse(stairsAhead(route(), network(true), LatLng(0.0, 0.0)))
    }
}
