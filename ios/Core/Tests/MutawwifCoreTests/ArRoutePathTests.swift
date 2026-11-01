import XCTest
@testable import MutawwifCore

/// Projecting a real route onto the ground in front of the camera.
final class ArRoutePathTests: XCTestCase {

    private let origin = LatLng(24.4808, 39.5596)

    private func at(_ from: LatLng, _ bearing: Double, _ metres: Double) -> LatLng {
        stepAlongBearing(from, bearing, metres)
    }

    private func flat(_ v: Vec3) -> Double { hypot(Double(v.x), Double(v.z)) }

    func testARouteStraightAheadLandsStraightAhead() {
        let path = routeGroundPath(userPosition: origin, routePoints: [origin, at(origin, 0, 20)], headingDeg: 0)
        XCTAssertGreaterThanOrEqual(path.count, 2)
        for p in path {
            XCTAssertEqual(0.0, Double(p.x), accuracy: 0.6)
            XCTAssertLessThan(p.z, 0.1)
        }
    }

    func testARouteToTheRightAppearsOnTheRight() {
        let path = routeGroundPath(userPosition: origin, routePoints: [origin, at(origin, 90, 20)], headingDeg: 0)
        XCTAssertGreaterThanOrEqual(path.count, 2)
        XCTAssertGreaterThan(path.last!.x, 5)
    }

    func testTurningThePhoneTurnsThePathTheOtherWay() {
        let route = [origin, at(origin, 0, 20)]
        let facingNorth = routeGroundPath(userPosition: origin, routePoints: route, headingDeg: 0)
        let facingEast = routeGroundPath(userPosition: origin, routePoints: route, headingDeg: 90)
        XCTAssertLessThan(facingNorth.last!.x, 3)
        XCTAssertLessThan(facingEast.last!.x, -5)
    }

    func testACornerInTheRouteSurvivesIntoTheGroundPath() {
        let corner = at(origin, 0, 15)
        let route = [origin, corner, at(corner, 90, 15)]
        let path = routeGroundPath(userPosition: origin, routePoints: route, headingDeg: 0, maxVisibleMeters: 40)
        XCTAssertGreaterThan(groundPathTurnDegrees(path), 60.0)
    }

    func testThePathIsClippedToTheVisibleHorizon() {
        let path = routeGroundPath(userPosition: origin, routePoints: [origin, at(origin, 0, 250)], headingDeg: 0, maxVisibleMeters: 25)
        XCTAssertLessThanOrEqual(path.map(flat).max()!, 26.0)
    }

    func testThePathDoesNotStartUnderfoot() {
        let path = routeGroundPath(userPosition: origin, routePoints: [origin, at(origin, 0, 30)], headingDeg: 0, startOffsetMeters: 0.8)
        XCTAssertGreaterThanOrEqual(path.map(flat).min()!, 0.7)
    }

    func testANodeBehindTheUserDoesNotMakeThePathDoubleBack() {
        let route = [at(origin, 180, 6), at(origin, 0, 30)]
        let path = routeGroundPath(userPosition: origin, routePoints: route, headingDeg: 0, maxVisibleMeters: 30)
        XCTAssertGreaterThanOrEqual(path.count, 2)
        XCTAssertFalse(path.contains { $0.z > 2 }, "path went behind the user: \(path.map { $0.z })")
    }

    func testDegenerateInputYieldsNothing() {
        XCTAssertTrue(routeGroundPath(userPosition: origin, routePoints: [], headingDeg: 0).isEmpty)
        XCTAssertTrue(routeGroundPath(userPosition: origin, routePoints: [origin], headingDeg: 0).isEmpty)
        XCTAssertTrue(routeGroundPath(userPosition: LatLng(0.0, 0.0), routePoints: [origin, origin], headingDeg: 0).isEmpty)
    }

    func testResamplingProducesEvenlySpacedPoints() {
        let dense = resamplePath([Vec3(0, 0, 0), Vec3(0, 0, -10)], spacingMeters: 1)
        XCTAssertGreaterThanOrEqual(dense.count, 10)
        for i in 1..<(dense.count - 1) {
            XCTAssertEqual(1.0, Double((dense[i] - dense[i - 1]).length()), accuracy: 0.15, "uneven spacing at \(i)")
        }
    }

    func testResamplingKeepsTheEndpoints() {
        let path = [Vec3(0, 0, 0), Vec3(3, 0, -7)]
        let dense = resamplePath(path, spacingMeters: 1)
        XCTAssertEqual(0.0, Double(dense.first!.length()), accuracy: 0.001)
        XCTAssertLessThan((dense.last! - path.last!).length(), 0.06)
    }

    func testResamplingADegeneratePathIsSafe() {
        XCTAssertEqual(1, resamplePath([Vec3(1, 0, 1)]).count)
        XCTAssertTrue(resamplePath([]).isEmpty)
    }
}
