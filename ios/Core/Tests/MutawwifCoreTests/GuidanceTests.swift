import XCTest
@testable import MutawwifCore

/// Guidance mode selection, covering the campus's two distinct problems:
/// landmarks hundreds of metres away, and landmarks close enough together
/// that GPS cannot tell them apart.
final class GuidanceTests: XCTestCase {

    private let origin = LatLng(24.500000, 39.500000)

    private func at(_ name: String, _ n: Double, _ e: Double) -> Landmark { landmarkAt(name, n, e) }

    func testNoFixYieldsNoFix() {
        let mode = guidanceFor(userPosition: LatLng(0.0, 0.0), userHeadingDeg: 0.0, target: at("A", 100, 0), others: [])
        XCTAssertTrue(mode.isNoFix)
    }

    func testFarLandmarkUsesCompassMode() {
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: at("Stadium", 250, 0), others: [])
        XCTAssertTrue(mode.isCompass, "expected Compass, got \(mode)")
        XCTAssertEqual(250.0, mode.distanceMeters!, accuracy: 3.0)
        XCTAssertEqual(0.0, mode.bearingDegrees!, accuracy: 1.0)
    }

    func testCompassRelativeBearingReflectsWhereTheUserFaces() {
        let target = at("East building", 0, 200)
        let facingNorth = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: target, others: [])
        XCTAssertEqual(90.0, facingNorth.relativeDegrees!, accuracy: 1.5)
        let facingEast = guidanceFor(userPosition: origin, userHeadingDeg: 90.0, target: target, others: [])
        XCTAssertEqual(0.0, facingEast.relativeDegrees!, accuracy: 1.5)
    }

    func testMidRangeSwitchesToArApproach() {
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: at("Nearby", 20, 0), others: [])
        XCTAssertTrue(mode.isArApproach, "expected ArApproach, got \(mode)")
    }

    func testIsolatedLandmarkReportsArrival() {
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: at("Alone", 5, 0), others: [at("Far away", 300, 0)])
        XCTAssertTrue(mode.isArrived, "expected Arrived, got \(mode)")
    }

    func testTwoCloseBuildingsAreReportedAsAmbiguous() {
        // Two faculties 15m apart, user standing between them. GPS error is
        // comparable to the gap, so the app must NOT claim which one this is.
        let computing = at("كلية الحاسب الآلي", 5, 0)
        let sharia = at("كلية الشريعة", 5, 15)
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: computing, others: [sharia])
        XCTAssertTrue(mode.isAmbiguous, "expected Ambiguous, got \(mode)")
        XCTAssertEqual(2, mode.candidates.count)
        XCTAssertTrue(mode.candidates.contains { $0.name.contains("الحاسب") })
        XCTAssertTrue(mode.candidates.contains { $0.name.contains("الشريعة") })
    }

    func testWellSeparatedBuildingsAreNotAmbiguous() {
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: at("A", 5, 0), others: [at("B", 5, 60)])
        XCTAssertTrue(mode.isArrived)
    }

    func testANearbyBuildingTheUserIsNotCloseToDoesNotCauseAmbiguity() {
        let mode = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: at("A", 300, 0), others: [at("B", 310, 0)])
        XCTAssertTrue(mode.isCompass)
    }

    func testAmbiguousPairsAreFoundForSurveying() {
        let pairs = findAmbiguousPairs([at("A", 0, 0), at("B", 0, 10), at("C", 0, 200)])
        XCTAssertEqual(1, pairs.count)
        XCTAssertEqual("A", pairs[0].0.name)
        XCTAssertEqual("B", pairs[0].1.name)
    }

    func testNoAmbiguousPairsWhenEverythingIsSpreadOut() {
        let landmarks = [at("A", 0, 0), at("B", 0, 100), at("C", 200, 0), at("D", 300, 300)]
        XCTAssertTrue(findAmbiguousPairs(landmarks).isEmpty)
    }

    func testArabicInstructionsMatchTheMode() {
        let target = at("الملعب", 250, 0)
        let behind = guidanceFor(userPosition: origin, userHeadingDeg: 180.0, target: target, others: [])
        XCTAssertTrue(instructionAr(behind, "الملعب").contains("استدر"))
        let ahead = guidanceFor(userPosition: origin, userHeadingDeg: 0.0, target: target, others: [])
        XCTAssertTrue(instructionAr(ahead, "الملعب").contains("امشِ"))
        XCTAssertTrue(instructionAr(.noFix, "الملعب").contains("موقعك"))
        XCTAssertTrue(instructionAr(.arrived(distanceMeters: 3.0), "الملعب").contains("وصلت"))
        XCTAssertTrue(instructionAr(.ambiguous(distanceMeters: 3.0, candidates: []), "x").contains("تأكد"))
    }

    func testArabicDistanceSwitchesToKilometres() {
        XCTAssertEqual("250 م", formatDistanceAr(250.4))
        XCTAssertEqual("1.5 كم", formatDistanceAr(1500.0))
    }
}
