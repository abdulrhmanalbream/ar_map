import XCTest
@testable import MutawwifCore

/// Compass heading for every way a phone gets held. Matrices are built by hand
/// from the device axes: row-major, device -> world (X east, Y north, Z up);
/// columns are the world images of device X, Y, Z.
final class HeadingMathTests: XCTestCase {

    typealias V = (Float, Float, Float)

    private func matrix(_ x: V, _ y: V, _ z: V) -> [Float] {
        return [x.0, y.0, z.0, x.1, y.1, z.1, x.2, y.2, z.2]
    }

    private let east: V = (1, 0, 0)
    private let west: V = (-1, 0, 0)
    private let north: V = (0, 1, 0)
    private let south: V = (0, -1, 0)
    private let up: V = (0, 0, 1)

    /// Phone held upright in portrait, camera looking north.
    private func uprightFacingNorth() -> [Float] { matrix(east, up, south) }

    /// Phone flat on a table, screen up, top edge pointing north.
    private func flatTopNorth() -> [Float] { matrix(east, north, up) }

    func testUprightPhoneReadsTheDirectionTheCameraLooks() {
        let r = computeHeading(rotationMatrix: uprightFacingNorth(), displayRotationDeg: 0)
        XCTAssertEqual(.camera, r.source)
        XCTAssertEqual(0.0, r.degrees!, accuracy: 0.5)
    }

    func testUprightPhoneFacingEastSouthWest() {
        let e = computeHeading(rotationMatrix: matrix(south, up, west), displayRotationDeg: 0)
        XCTAssertEqual(.camera, e.source)
        XCTAssertEqual(90.0, e.degrees!, accuracy: 0.5)
        XCTAssertEqual(180.0, computeHeading(rotationMatrix: matrix(west, up, north), displayRotationDeg: 0).degrees!, accuracy: 0.5)
        XCTAssertEqual(270.0, computeHeading(rotationMatrix: matrix(north, up, east), displayRotationDeg: 0).degrees!, accuracy: 0.5)
    }

    func testCameraHeadingIgnoresScreenRotation() {
        for rotation in [0, 90, 180, 270] {
            XCTAssertEqual(0.0, computeHeading(rotationMatrix: uprightFacingNorth(), displayRotationDeg: rotation).degrees!,
                           accuracy: 0.5, "rotation \(rotation)")
        }
    }

    func testFlatPhoneFallsBackToTheTopOfTheScreen() {
        let r = computeHeading(rotationMatrix: flatTopNorth(), displayRotationDeg: 0)
        XCTAssertEqual(.screenUp, r.source)
        XCTAssertEqual(0.0, r.degrees!, accuracy: 0.5)
    }

    func testFlatPhoneTurnedToFaceEastReadsNinety() {
        let r = computeHeading(rotationMatrix: matrix(south, east, up), displayRotationDeg: 0)
        XCTAssertEqual(.screenUp, r.source)
        XCTAssertEqual(90.0, r.degrees!, accuracy: 0.5)
    }

    func testFlatPhoneInLandscapeAccountsForScreenRotation() {
        let portrait = computeHeading(rotationMatrix: flatTopNorth(), displayRotationDeg: 0).degrees!
        let landscape = computeHeading(rotationMatrix: flatTopNorth(), displayRotationDeg: 90).degrees!
        XCTAssertEqual(0.0, portrait, accuracy: 0.5)
        XCTAssertEqual(90.0, abs(relativeBearing(portrait, landscape)), accuracy: 1.0)
    }

    func testRaisingThePhoneKeepsTheHeadingCorrect() {
        let flat = computeHeading(rotationMatrix: flatTopNorth(), displayRotationDeg: 0)
        let upright = computeHeading(rotationMatrix: uprightFacingNorth(), displayRotationDeg: 0)
        XCTAssertEqual(0.0, flat.degrees!, accuracy: 0.5)
        XCTAssertEqual(0.0, upright.degrees!, accuracy: 0.5)
        XCTAssertNotEqual(flat.source, upright.source)
    }

    func testTiltIsReported() {
        XCTAssertEqual(0, computeHeading(rotationMatrix: uprightFacingNorth(), displayRotationDeg: 0).tilt, accuracy: 0.02)
        XCTAssertEqual(1, computeHeading(rotationMatrix: flatTopNorth(), displayRotationDeg: 0).tilt, accuracy: 0.02)
    }

    func testHysteresisStopsTheSourceFlapping() {
        let tilted = matrix(east, (0, 0.71, 0.70), (0, -0.70, 0.71))
        let fromUpright = computeHeading(rotationMatrix: tilted, displayRotationDeg: 0, wasFlat: false)
        let fromFlat = computeHeading(rotationMatrix: tilted, displayRotationDeg: 0, wasFlat: true)
        XCTAssertNotEqual(fromUpright.source, fromFlat.source)
    }

    func testAMalformedMatrixYieldsNoHeading() {
        XCTAssertNil(computeHeading(rotationMatrix: [1, 0, 0], displayRotationDeg: 0).degrees)
    }

    func testAVerticalDirectionHasNoMeaningfulBearing() {
        XCTAssertNil(bearingOf((0, 0, 1)))
        XCTAssertNil(bearingOf((0, 0, -1)))
        XCTAssertNotNil(bearingOf((0, 1, 0)))
    }

    func testSmoothingDoesNotSwingAHalfTurnAcrossNorth() {
        let smoother = CircularSmoother(alpha: 0.5)
        _ = smoother.next(359.0)
        let result = smoother.next(1.0)
        XCTAssertLessThan(abs(relativeBearing(0.0, result)), 5.0)
    }

    func testSmoothingConvergesOnASteadyReading() {
        let smoother = CircularSmoother(alpha: 0.3)
        for _ in 0..<60 { _ = smoother.next(123.0) }
        XCTAssertEqual(123.0, smoother.next(123.0), accuracy: 0.5)
    }

    func testSmoothingDampsASingleSpike() {
        let smoother = CircularSmoother(alpha: 0.15)
        for _ in 0..<30 { _ = smoother.next(90.0) }
        let afterSpike = smoother.next(270.0)
        XCTAssertLessThan(abs(relativeBearing(90.0, afterSpike)), 40.0)
    }
}
