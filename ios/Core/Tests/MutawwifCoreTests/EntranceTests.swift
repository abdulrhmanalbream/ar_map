import XCTest
@testable import MutawwifCore

/// Routing to the door rather than the middle of the building.
///
/// Written from a real survey: the map pin on three of four colleges sat
/// 40-50m from the actual entrance, so the app announced arrival while the
/// user was still walking past a blank wall.
final class EntranceTests: XCTestCase {

    /// The real Hadith college: pin, and its gate 49m away.
    private let hadithCentre = LatLng(24.4821488, 39.5605851)
    private let hadithGate = LatLng(24.481710, 39.560565)

    private func college(_ doors: Entrance...) -> Landmark {
        return Landmark(
            id: "lm-hadith",
            name: "كلية الحديث الشريف والدراسات الإسلامية",
            category: .faculty,
            position: hadithCentre,
            entrances: doors
        )
    }

    private var north: Entrance { Entrance(id: "n", nameAr: "المدخل الشمالي", position: stepAlongBearing(hadithCentre, 0.0, 40.0)) }
    private var south: Entrance { Entrance(id: "s", nameAr: "المدخل الجنوبي", position: stepAlongBearing(hadithCentre, 180.0, 40.0)) }

    func testWithNoEntrancesTheCentreIsStillUsed() {
        let plain = college()
        XCTAssertEqual(hadithCentre, plain.approachPoint(LatLng(24.4810, 39.5600)))
        XCTAssertNil(plain.nearestEntrance(LatLng(24.4810, 39.5600)))
    }

    func testTheNearestDoorWins() {
        let building = college(north, south)
        XCTAssertEqual("n", building.nearestEntrance(stepAlongBearing(hadithCentre, 0.0, 120.0))!.id)
        XCTAssertEqual("s", building.nearestEntrance(stepAlongBearing(hadithCentre, 180.0, 120.0))!.id)
    }

    func testApproachingFromTheFarSideDoesNotRouteThroughTheBuilding() {
        let building = college(north, south)
        let behind = stepAlongBearing(hadithCentre, 180.0, 60.0)
        let approach = building.approachPoint(behind)
        XCTAssertLessThan(distanceMeters(behind, approach), distanceMeters(behind, north.position))
    }

    func testArrivalIsJudgedAtTheDoorNotThePin() {
        let building = college(Entrance(id: "main", nameAr: "المدخل الرئيسي", position: hadithGate))
        let atPin = guidanceFor(userPosition: hadithCentre, userHeadingDeg: 0.0, target: college(), others: [])
        XCTAssertTrue(atPin.isArrived)
        let atGate = guidanceFor(userPosition: hadithGate, userHeadingDeg: 0.0, target: building, others: [])
        XCTAssertTrue(atGate.isArrived, "standing at the gate should be arrival, got \(atGate)")
    }

    func testThePinNoLongerCountsAsArrivalOnceADoorIsKnown() {
        let building = college(Entrance(id: "main", nameAr: "المدخل الرئيسي", position: hadithGate))
        let guidance = guidanceFor(userPosition: hadithCentre, userHeadingDeg: 0.0, target: building, others: [])
        XCTAssertFalse(guidance.isArrived, "standing 49m from the door should not be arrival, got \(guidance)")
    }

    func testDistanceAndBearingAreMeasuredToTheDoor() {
        let building = college(Entrance(id: "main", nameAr: "المدخل الرئيسي", position: hadithGate))
        let from = stepAlongBearing(hadithGate, 180.0, 100.0)
        let ranked = rankByDistance([building], from)
        XCTAssertEqual(1, ranked.count)
        XCTAssertEqual(distanceMeters(from, hadithGate), ranked[0].distanceMeters, accuracy: 1.0)
        XCTAssertEqual(bearingDegrees(from, hadithGate), ranked[0].bearingDegrees, accuracy: 1.0)
    }

    func testAnInvalidPositionStillYieldsAUsableDoor() {
        XCTAssertEqual("n", college(north, south).nearestEntrance(LatLng(0.0, 0.0))!.id)
    }

    func testEntrancesWithBrokenCoordinatesAreIgnored() {
        let building = college(Entrance(id: "bad", nameAr: "معطوب", position: LatLng(Double.nan, Double.nan)), north)
        XCTAssertEqual("n", building.nearestEntrance(stepAlongBearing(hadithCentre, 0.0, 80.0))!.id)
    }

    func testTheFourRealGatesSitFarEnoughApartForGpsToSeparateThem() {
        let gates = [
            LatLng(24.480899, 39.559647),  // Shariah
            LatLng(24.481676, 39.560255),  // Dawah
            LatLng(24.481710, 39.560565),  // Hadith
            LatLng(24.481676, 39.560948),  // Quran
        ]
        for i in gates.indices {
            for j in (i + 1)..<gates.count {
                let d = distanceMeters(gates[i], gates[j])
                XCTAssertGreaterThan(d, GPS_DISTINGUISHABLE_M, "gates \(i) and \(j) are only \(d)m apart")
            }
        }
    }
}
