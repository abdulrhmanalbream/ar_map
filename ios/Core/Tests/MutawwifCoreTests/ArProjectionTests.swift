import XCTest
@testable import MutawwifCore

/// Screen placement for distant landmarks. A sign error here points the user at
/// the wrong side of the campus while looking completely plausible.
final class ArProjectionTests: XCTestCase {

    private let origin = LatLng(24.500000, 39.500000)

    private func at(_ n: Double, _ e: Double) -> LatLng { landmarkAt("x", n, e).position }

    private func project(_ heading: Double, _ pitch: Double, _ target: LatLng) -> ScreenPlacement? {
        projectLandmark(userPosition: origin, userHeadingDeg: heading, pitchDeg: pitch, target: target)
    }

    func testLandmarkStraightAheadLandsInTheCentre() {
        let p = project(0, 0, at(250, 0))!
        XCTAssertTrue(p.visible)
        XCTAssertEqual(0, p.x, accuracy: 0.02)
        XCTAssertEqual(250.0, p.distanceMeters, accuracy: 3.0)
    }

    func testLandmarkToTheRightHasPositiveX() {
        let p = project(0, 0, at(250, 91))!
        XCTAssertTrue(p.visible)
        XCTAssertGreaterThan(p.x, 0.1)
    }

    func testLandmarkToTheLeftHasNegativeX() {
        let p = project(0, 0, at(250, -91))!
        XCTAssertTrue(p.visible)
        XCTAssertLessThan(p.x, -0.1)
    }

    func testLandmarkBehindTheUserIsNotVisible() {
        XCTAssertFalse(project(0, 0, at(-250, 0))!.visible)
    }

    func testOffScreenDirectionPointsTheShorterWayRound() {
        let right = project(0, 0, at(0, 250))!
        XCTAssertFalse(right.visible)
        XCTAssertEqual(1, right.offScreenDirection)
        let left = project(0, 0, at(0, -250))!
        XCTAssertFalse(left.visible)
        XCTAssertEqual(-1, left.offScreenDirection)
    }

    func testTurningToFaceTheLandmarkBringsItIntoView() {
        let east = at(0, 250)
        XCTAssertFalse(project(0, 0, east)!.visible)
        let facing = project(90, 0, east)!
        XCTAssertTrue(facing.visible)
        XCTAssertEqual(0, facing.x, accuracy: 0.02)
    }

    func testInvalidPositionsAreRejected() {
        XCTAssertNil(projectLandmark(userPosition: LatLng(0.0, 0.0), userHeadingDeg: 0, pitchDeg: 0, target: at(100, 0)))
        XCTAssertNil(project(0, 0, LatLng(0.0, 0.0)))
    }

    func testTiltingTheDeviceMovesTheMarkerVertically() {
        let level = project(0, 0, at(250, 0))!
        let tiltedUp = project(0, 20, at(250, 0))!
        XCTAssertEqual(0, level.y, accuracy: 0.02)
        XCTAssertLessThan(tiltedUp.y, level.y)
    }

    func testMarkerShrinksWithDistanceButStaysLegible() {
        let near = markerScaleFor(10.0)
        let mid = markerScaleFor(120.0)
        let far = markerScaleFor(250.0)
        XCTAssertGreaterThan(near, mid)
        XCTAssertGreaterThan(mid, far)
        XCTAssertGreaterThanOrEqual(far, 0.45)
    }

    func testGroundPathIsCappedForDistantTargets() {
        XCTAssertEqual(10.0, groundPathLength(10.0), accuracy: 0.01)
        XCTAssertEqual(12.0, groundPathLength(250.0), accuracy: 0.01)
        XCTAssertEqual(12.0, groundPathLength(30.0), accuracy: 0.01)
        XCTAssertEqual(0.0, groundPathLength(0.0), accuracy: 0.01)
    }
}
