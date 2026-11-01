import XCTest
@testable import MutawwifCore

/// Covers the campus-scale geo maths. These are the calculations that decide
/// which way the arrow points and how far away a building is, and a sign error
/// in a bearing is almost impossible to spot by eye on a phone.
final class GeoTests: XCTestCase {

    // ~24.5N is a plausible Saudi latitude.
    private let origin = LatLng(24.500000, 39.500000)

    func testDistanceToItselfIsZero() {
        XCTAssertEqual(0.0, distanceMeters(origin, origin), accuracy: 1e-6)
    }

    func testOneDegreeOfLatitudeIsAbout111Km() {
        let north = LatLng(origin.latitude + 1.0, origin.longitude)
        XCTAssertEqual(111_195.0, distanceMeters(origin, north), accuracy: 200.0)
    }

    func testDistanceIsSymmetric() {
        let other = LatLng(24.502000, 39.503000)
        XCTAssertEqual(distanceMeters(origin, other), distanceMeters(other, origin), accuracy: 1e-6)
    }

    func testCampusScaleDistanceIsPlausible() {
        let north = LatLng(origin.latitude + 0.001, origin.longitude)
        XCTAssertEqual(111.0, distanceMeters(origin, north), accuracy: 2.0)
    }

    func testBearingCardinals() {
        XCTAssertEqual(0.0, bearingDegrees(origin, LatLng(origin.latitude + 0.01, origin.longitude)), accuracy: 0.5)
        XCTAssertEqual(90.0, bearingDegrees(origin, LatLng(origin.latitude, origin.longitude + 0.01)), accuracy: 0.5)
        XCTAssertEqual(180.0, bearingDegrees(origin, LatLng(origin.latitude - 0.01, origin.longitude)), accuracy: 0.5)
        XCTAssertEqual(270.0, bearingDegrees(origin, LatLng(origin.latitude, origin.longitude - 0.01)), accuracy: 0.5)
    }

    func testBearingIsAlwaysNormalised() {
        let points = [LatLng(24.51, 39.51), LatLng(24.49, 39.51), LatLng(24.49, 39.49), LatLng(24.51, 39.49)]
        for p in points {
            let b = bearingDegrees(origin, p)
            XCTAssertTrue(b >= 0.0 && b < 360.0, "bearing \(b) out of range")
        }
    }

    func testRelativeBearingTakesTheShortWayRound() {
        XCTAssertEqual(20.0, relativeBearing(350.0, 10.0), accuracy: 1e-6)
        XCTAssertEqual(-20.0, relativeBearing(10.0, 350.0), accuracy: 1e-6)
    }

    func testRelativeBearingStaysWithinHalfTurn() {
        for from in stride(from: 0, to: 360, by: 17) {
            for to in stride(from: 0, to: 360, by: 23) {
                let r = relativeBearing(Double(from), Double(to))
                XCTAssertTrue(r > -180.0 && r <= 180.0, "relative \(r) out of range")
            }
        }
    }

    func testLocalMetresHaveTheRightSigns() {
        let (east, north) = toLocalMeters(origin, LatLng(origin.latitude + 0.001, origin.longitude + 0.001))
        XCTAssertGreaterThan(east, 0)
        XCTAssertGreaterThan(north, 0)
        let (e2, n2) = toLocalMeters(origin, LatLng(origin.latitude - 0.001, origin.longitude - 0.001))
        XCTAssertLessThan(e2, 0)
        XCTAssertLessThan(n2, 0)
    }

    func testLocalMetresRoughlyAgreeWithGreatCircle() {
        let p = LatLng(origin.latitude + 0.0012, origin.longitude + 0.0009)
        let (east, north) = toLocalMeters(origin, p)
        XCTAssertEqual(distanceMeters(origin, p), (east * east + north * north).squareRoot(), accuracy: 1.0)
    }

    func testNullIslandIsNotAValidFix() {
        XCTAssertFalse(LatLng(0.0, 0.0).isValid)
        XCTAssertTrue(origin.isValid)
        XCTAssertFalse(LatLng(91.0, 0.0).isValid)
        XCTAssertFalse(LatLng(0.0, 181.0).isValid)
    }

    func testAveragingFavoursTheMoreAccurateFix() {
        let precise = GpsFix(position: LatLng(24.500000, 39.500000), accuracyMeters: 2, timestampMs: 0)
        let sloppy = GpsFix(position: LatLng(24.501000, 39.501000), accuracyMeters: 20, timestampMs: 0)
        let avg = averageFixes([precise, sloppy])!
        XCTAssertEqual(24.500000, avg.latitude, accuracy: 0.00005)
        XCTAssertEqual(39.500000, avg.longitude, accuracy: 0.00005)
    }

    func testAveragingRejectsUnusableInput() {
        XCTAssertNil(averageFixes([]))
        XCTAssertNil(averageFixes([GpsFix(position: LatLng(0.0, 0.0), accuracyMeters: 5, timestampMs: 0)]))
        XCTAssertNil(averageFixes([GpsFix(position: LatLng(24.5, 39.5), accuracyMeters: 0, timestampMs: 0)]))
    }

    func testDistanceFormattingSwitchesToKm() {
        XCTAssertEqual("12 m", formatDistance(12.4))
        XCTAssertEqual("450 m", formatDistance(450.0))
        XCTAssertEqual("1.2 km", formatDistance(1200.0))
    }

    func testWalkTimeIsNeverZero() {
        XCTAssertEqual(1, walkMinutes(1.0))
        XCTAssertGreaterThanOrEqual(walkMinutes(600.0), 7)
    }

    func testCompassPointsMapCorrectly() {
        XCTAssertEqual("N", compassPoint(0.0))
        XCTAssertEqual("E", compassPoint(90.0))
        XCTAssertEqual("S", compassPoint(180.0))
        XCTAssertEqual("W", compassPoint(270.0))
        XCTAssertEqual("N", compassPoint(359.0))
        XCTAssertEqual("NE", compassPoint(45.0))
    }

    func testTurnInstructionsUseAWideDeadAheadBand() {
        XCTAssertEqual("Straight ahead", turnInstruction(0.0))
        XCTAssertEqual("Straight ahead", turnInstruction(14.0))
        XCTAssertEqual("Slightly right", turnInstruction(30.0))
        XCTAssertEqual("Slightly left", turnInstruction(-30.0))
        XCTAssertEqual("Turn right", turnInstruction(90.0))
        XCTAssertEqual("Turn left", turnInstruction(-90.0))
        XCTAssertEqual("Turn around", turnInstruction(170.0))
    }

    func testRankingSortsNearestFirstAndSurvivesNoFix() {
        let near = Landmark(id: "a", name: "Near", category: .other, position: LatLng(24.5005, 39.5000))
        let far = Landmark(id: "b", name: "Far", category: .other, position: LatLng(24.5100, 39.5000))

        let ranked = rankByDistance([far, near], origin)
        XCTAssertEqual("Near", ranked[0].landmark.name)
        XCTAssertLessThan(ranked[0].distanceMeters, ranked[1].distanceMeters)

        XCTAssertEqual(2, rankByDistance([far, near], LatLng(0.0, 0.0)).count)
    }
}
