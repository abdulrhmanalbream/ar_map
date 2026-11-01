import XCTest
@testable import MutawwifCore

/// The scripted demo: it must not fail in front of an audience, so its timing
/// and sequencing must be provable rather than hoped for.
final class PresentationTourTests: XCTestCase {

    private func start() -> TourState { TourState(stepIndex: 0, elapsedInStepMs: 0, running: true) }

    private func index(where predicate: (TourAction) -> Bool) -> Int {
        PresentationTour.STEPS.firstIndex { predicate($0.action) } ?? -1
    }

    private func isWalk(_ a: TourAction) -> Bool { if case .walk = a { return true }; return false }
    private func isHold(_ a: TourAction) -> Bool { if case .hold = a { return true }; return false }
    private func isTurn(_ a: TourAction) -> Bool { if case .turn = a { return true }; return false }

    func testTheTourHasAScriptAndASaneLength() {
        XCTAssertGreaterThanOrEqual(PresentationTour.STEPS.count, 8)
        let total = PresentationTour.totalDurationMs
        XCTAssertGreaterThanOrEqual(total, 40_000)
        XCTAssertLessThanOrEqual(total, 90_000)
    }

    func testEveryBeatHasCopyAndAPositiveDuration() {
        for step in PresentationTour.STEPS {
            XCTAssertFalse(step.titleAr.isEmpty)
            XCTAssertFalse(step.captionAr.isEmpty)
            XCTAssertGreaterThan(step.durationMs, 0)
        }
    }

    func testAPausedTourDoesNotAdvance() {
        var paused = start()
        paused.running = false
        XCTAssertEqual(paused, advanceTour(paused, 5_000))
    }

    func testTimeWithinABeatAccumulates() {
        let s = advanceTour(start(), 1_000)
        XCTAssertEqual(0, s.stepIndex)
        XCTAssertEqual(1_000, s.elapsedInStepMs)
    }

    func testTheTourMovesToTheNextBeatWhenOneEnds() {
        let s = advanceTour(start(), PresentationTour.STEPS[0].durationMs + 10)
        XCTAssertEqual(1, s.stepIndex)
        XCTAssertEqual(0, s.elapsedInStepMs)
        XCTAssertTrue(s.running)
    }

    func testTheTourStopsCleanlyAtTheEnd() {
        var s = start()
        for _ in 0..<400 { s = advanceTour(s, 500) }
        XCTAssertTrue(s.finished)
        XCTAssertFalse(s.running)
        XCTAssertEqual(s, advanceTour(s, 1_000))
    }

    func testProgressRunsFromZeroToOne() {
        XCTAssertEqual(0, start().overallProgress, accuracy: 0.001)
        var s = start()
        for _ in 0..<400 { s = advanceTour(s, 500) }
        XCTAssertEqual(1, s.overallProgress, accuracy: 0.001)
    }

    func testProgressOnlyEverIncreases() {
        var s = start()
        var previous: Float = 0
        for _ in 0..<200 {
            s = advanceTour(s, 400)
            XCTAssertGreaterThanOrEqual(s.overallProgress, previous - 0.0001)
            previous = s.overallProgress
        }
    }

    func testWalkingOnlyHappensDuringAWalkBeat() {
        let walkIndex = index(where: isWalk)
        XCTAssertGreaterThanOrEqual(walkIndex, 0)
        XCTAssertGreaterThan(tourWalkDistance(TourState(stepIndex: walkIndex, elapsedInStepMs: 0, running: true), 1_000), 0)
        let holdIndex = index(where: isHold)
        XCTAssertEqual(0.0, tourWalkDistance(TourState(stepIndex: holdIndex, elapsedInStepMs: 0, running: true), 1_000), accuracy: 0.001)
    }

    func testWalkingSpeedIsProportionalToElapsedTime() {
        let s = TourState(stepIndex: index(where: isWalk), elapsedInStepMs: 0, running: true)
        XCTAssertEqual(tourWalkDistance(s, 1_000) * 2, tourWalkDistance(s, 2_000), accuracy: 0.001)
    }

    func testTurningOnlyHappensDuringATurnBeat() {
        let turnIndex = index(where: isTurn)
        XCTAssertGreaterThanOrEqual(turnIndex, 0)
        XCTAssertGreaterThan(tourTurnDegrees(TourState(stepIndex: turnIndex, elapsedInStepMs: 0, running: true), 1_000), 0)
        XCTAssertEqual(0.0, tourTurnDegrees(TourState(stepIndex: index(where: isHold), elapsedInStepMs: 0, running: true), 1_000), accuracy: 0.001)
    }

    func testTheScriptBuildsTheArgumentInTheRightOrder() {
        let actions = PresentationTour.STEPS.map { $0.action }
        let select = actions.firstIndex { if case .selectTarget = $0 { return true }; return false } ?? -1
        let walk = actions.firstIndex(where: isWalk) ?? -1
        let arrive = actions.lastIndex { if case .jumpNear = $0 { return true }; return false } ?? -1
        let bike = actions.firstIndex(of: .setMode(mode: .bike)) ?? -1
        let car = actions.firstIndex(of: .setMode(mode: .car)) ?? -1
        XCTAssertTrue(select >= 0 && select < walk)
        XCTAssertLessThan(walk, arrive)
        XCTAssertLessThan(arrive, bike)
        XCTAssertLessThan(bike, car)
    }

    func testTheAmbiguityMomentIsInTheScript() {
        XCTAssertTrue(PresentationTour.STEPS.contains { $0.captionAr.contains("دقة GPS") || $0.titleAr.contains("نسأل") })
    }

    func testTheOfflineClaimIsMadeExplicitly() {
        XCTAssertTrue(PresentationTour.STEPS.contains { $0.titleAr.contains("إنترنت") || $0.captionAr.contains("إنترنت") })
    }

    func testAnOutOfRangeStepIndexIsHandled() {
        let past = TourState(stepIndex: 999, elapsedInStepMs: 0, running: true)
        XCTAssertTrue(past.finished)
        XCTAssertNil(past.step)
        _ = advanceTour(past, 100)
    }
}
