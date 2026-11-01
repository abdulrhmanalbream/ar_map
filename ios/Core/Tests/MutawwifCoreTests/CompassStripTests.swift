import XCTest
@testable import MutawwifCore

/// The compass ribbon. A sign error here sends every tick the wrong way while
/// still looking like a working compass.
final class CompassStripTests: XCTestCase {

    func testFacingNorthPutsNorthAtTheCentre() {
        let north = compassTicks(headingDeg: 0.0).first { $0.bearingDeg == 0.0 }!
        XCTAssertEqual(0, north.x, accuracy: 0.01)
        XCTAssertTrue(north.isCardinal)
        XCTAssertEqual("ش", north.label)
    }

    func testTurningRightSlidesTheTicksLeft() {
        let ticks = compassTicks(headingDeg: 45.0)
        XCTAssertLessThan(ticks.first { $0.bearingDeg == 0.0 }!.x, 0)
        XCTAssertGreaterThan(ticks.first { $0.bearingDeg == 90.0 }!.x, 0)
    }

    func testEveryVisibleTickStaysInsideTheStrip() {
        for heading in stride(from: 0, to: 360, by: 13) {
            for tick in compassTicks(headingDeg: Double(heading)) {
                XCTAssertTrue((-1...1).contains(tick.x), "x=\(tick.x) outside strip")
            }
        }
    }

    func testTheStripWrapsCleanlyAcrossNorth() {
        let north = compassTicks(headingDeg: 350.0).first { $0.bearingDeg == 0.0 }!
        XCTAssertGreaterThan(north.x, 0)
        XCTAssertLessThan(north.x, 0.3)
    }

    func testAllEightCardinalsAreLabelledSomewhereOnTheCircle() {
        var seen = Set<String>()
        for heading in stride(from: 0, to: 360, by: 20) {
            for t in compassTicks(headingDeg: Double(heading)) where t.isCardinal { seen.insert(t.label) }
        }
        XCTAssertEqual(8, seen.count)
        XCTAssertTrue(seen.isSuperset(of: ["ش", "ق", "ج", "غ"]))
    }

    func testTargetDeadAheadSitsAtTheCentre() {
        let t = compassTarget(headingDeg: 90.0, targetBearingDeg: 90.0, distanceMeters: 100.0)
        XCTAssertTrue(t.visible)
        XCTAssertEqual(0, t.x, accuracy: 0.01)
    }

    func testTargetToTheRightHasPositiveX() {
        let t = compassTarget(headingDeg: 0.0, targetBearingDeg: 40.0, distanceMeters: 100.0)
        XCTAssertTrue(t.visible)
        XCTAssertGreaterThan(t.x, 0)
    }

    func testTargetBehindTheUserIsOffScreenWithADirectionHint() {
        let right = compassTarget(headingDeg: 0.0, targetBearingDeg: 120.0, distanceMeters: 50.0)
        XCTAssertFalse(right.visible)
        XCTAssertEqual(1, right.offScreenDirection)
        XCTAssertEqual(1, right.x, accuracy: 0.001)
        let left = compassTarget(headingDeg: 0.0, targetBearingDeg: 240.0, distanceMeters: 50.0)
        XCTAssertFalse(left.visible)
        XCTAssertEqual(-1, left.offScreenDirection)
        XCTAssertEqual(-1, left.x, accuracy: 0.001)
    }

    func testBikesMayUseBothRoadsAndPedestrianPaths() {
        XCTAssertTrue(TravelMode.bike.canUse(true, true, false))
        XCTAssertTrue(TravelMode.bike.canUse(false, true, true))
    }

    func testCarsAreKeptOffPedestrianOnlyPaths() {
        XCTAssertFalse(TravelMode.car.canUse(true, true, false))
        XCTAssertTrue(TravelMode.car.canUse(false, false, true))
    }

    func testTravelTimeReflectsTheMode() {
        let walk = travelMinutes(1000.0, .walk)
        let bike = travelMinutes(1000.0, .bike)
        let car = travelMinutes(1000.0, .car)
        XCTAssertGreaterThan(walk, bike)
        XCTAssertGreaterThan(bike, car)
        XCTAssertGreaterThanOrEqual(walk, 12)
    }

    func testTravelTimeIsNeverZero() {
        XCTAssertEqual(1, travelMinutes(1.0, .car))
    }
}
