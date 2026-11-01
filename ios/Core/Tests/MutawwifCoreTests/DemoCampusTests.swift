import XCTest
@testable import MutawwifCore

/// The demo campus must reproduce the REAL distances, or it teaches the wrong
/// lessons: buildings 5m apart would never show the ambiguity prompt.
final class DemoCampusTests: XCTestCase {

    private let here = LatLng(24.500000, 39.500000)

    private func named(_ demo: [Landmark], _ fragment: String) -> Landmark {
        demo.first { $0.name.contains(fragment) }!
    }

    func testGeneratesAllFourLandmarks() {
        let demo = DemoCampus.generate(here)
        XCTAssertEqual(4, demo.count)
        XCTAssertTrue(demo.allSatisfy { DemoCampus.isDemo($0) })
    }

    func testInvalidCentreProducesNothing() {
        XCTAssertTrue(DemoCampus.generate(LatLng(0.0, 0.0)).isEmpty)
    }

    func testFarLandmarkReallyIsAbout250MetresAway() {
        XCTAssertEqual(250.0, distanceMeters(here, named(DemoCampus.generate(here), "الملعب").position), accuracy: 5.0)
    }

    func testTheClosePairReallyIsAbout20MetresApart() {
        let demo = DemoCampus.generate(here)
        let gap = distanceMeters(named(demo, "الحاسب").position, named(demo, "الشريعة").position)
        XCTAssertEqual(20.0, gap, accuracy: 2.0)
    }

    func testTheClosePairIsDetectedAsAmbiguous() {
        let pairs = findAmbiguousPairs(DemoCampus.generate(here))
        XCTAssertFalse(pairs.isEmpty)
        let names = pairs.flatMap { [$0.0.name, $0.1.name] }
        XCTAssertTrue(names.contains { $0.contains("الحاسب") })
        XCTAssertTrue(names.contains { $0.contains("الشريعة") })
    }

    func testFarLandmarkSitsAheadOfTheUsersHeading() {
        let stadium = named(DemoCampus.generate(here, headingDeg: 90.0), "الملعب")
        XCTAssertEqual(90.0, bearingDegrees(here, stadium.position), accuracy: 5.0)
    }

    func testRotationPreservesDistances() {
        let a = DemoCampus.generate(here, headingDeg: 0.0)
        let b = DemoCampus.generate(here, headingDeg: 137.0)
        for i in a.indices {
            XCTAssertEqual(distanceMeters(here, a[i].position), distanceMeters(here, b[i].position), accuracy: 1.0)
        }
    }

    func testDemoProducesEveryGuidanceMode() {
        let demo = DemoCampus.generate(here)
        let compass = guidanceFor(userPosition: here, userHeadingDeg: 0.0, target: named(demo, "الملعب"), others: demo)
        XCTAssertTrue(compass.isCompass)
        let computing = named(demo, "الحاسب")
        let ambiguous = guidanceFor(userPosition: computing.position, userHeadingDeg: 0.0, target: computing, others: demo)
        XCTAssertTrue(ambiguous.isAmbiguous, "standing at the pair should be ambiguous, was \(ambiguous)")
    }

    func testSteppingTowardsATargetReducesTheDistance() {
        let target = LatLng(24.502000, 39.500000)
        let start = distanceMeters(here, target)
        let after = distanceMeters(stepTowards(here, target, 50.0), target)
        XCTAssertEqual(50.0, start - after, accuracy: 2.0)
    }

    func testSteppingNeverOvershootsTheTarget() {
        let target = LatLng(24.500050, 39.500000)
        XCTAssertEqual(0.0, distanceMeters(stepTowards(here, target, 100.0), target), accuracy: 0.6)
    }

    func testSteppingAlongABearingMovesTheRightWay() {
        let north = stepAlongBearing(here, 0.0, 100.0)
        XCTAssertGreaterThan(north.latitude, here.latitude)
        XCTAssertEqual(100.0, distanceMeters(here, north), accuracy: 2.0)
        let east = stepAlongBearing(here, 90.0, 100.0)
        XCTAssertGreaterThan(east.longitude, here.longitude)
        XCTAssertEqual(100.0, distanceMeters(here, east), accuracy: 2.0)
    }

    func testDemoLandmarksAreDistinguishableFromSurveyedOnes() {
        let demo = DemoCampus.generate(here)[0]
        let real = Landmark(id: "lm-123", name: "حقيقي", category: .other, position: here)
        XCTAssertTrue(DemoCampus.isDemo(demo))
        XCTAssertFalse(DemoCampus.isDemo(real))
    }
}
