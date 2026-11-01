import XCTest
@testable import MutawwifCore

/// The start-up sequence. The failure mode is either dismissing the hint while
/// the app is still blind, or trapping the user on a step that never completes.
final class CalibrationTests: XCTestCase {

    private func input(
        tracking: Bool = false,
        compassReliable: Bool = true,
        tilt: Float = 0.9,
        movedDegrees: Double = 0.0,
        elapsedMs: Int64 = 0
    ) -> CalibrationInput {
        CalibrationInput(tracking: tracking, compassReliable: compassReliable, tilt: tilt, movedDegrees: movedDegrees, elapsedMs: elapsedMs)
    }

    func testStartsByAskingForAWave() {
        let s = nextCalibrationState(.wave, input())
        XCTAssertEqual(.wave, s.step)
        XCTAssertTrue(s.titleAr.contains("حرّك"))
        XCTAssertFalse(s.bodyAr.isEmpty)
    }

    func testDoesNotAdvanceWhileThePhoneIsStill() {
        XCTAssertEqual(.wave, nextCalibrationState(.wave, input(tracking: false, movedDegrees: 0, elapsedMs: 4_000)).step)
    }

    func testDoesNotAdvanceOnSweepAloneIfTrackingHasNotStarted() {
        XCTAssertEqual(.wave, nextCalibrationState(.wave, input(tracking: false, movedDegrees: 200, elapsedMs: 5_000)).step)
    }

    func testAdvancesOnceSweptEnoughAndTrackingIsEstablished() {
        let s = nextCalibrationState(.wave, input(tracking: true, movedDegrees: 80, elapsedMs: 3_000))
        XCTAssertEqual(.raise, s.step)
        XCTAssertTrue(s.titleAr.contains("ارفع"))
    }

    func testNeverFlashesPastBeforeTheUserCanReadIt() {
        XCTAssertEqual(.wave, nextCalibrationState(.wave, input(tracking: true, movedDegrees: 200, elapsedMs: 200)).step)
    }

    func testGivesUpRatherThanTrappingTheUser() {
        XCTAssertEqual(.raise, nextCalibrationState(.wave, input(tracking: false, compassReliable: false, elapsedMs: 13_000)).step)
    }

    func testProgressReflectsTheSweep() {
        let none = nextCalibrationState(.wave, input(movedDegrees: 0))
        let half = nextCalibrationState(.wave, input(movedDegrees: 27))
        XCTAssertGreaterThan(half.progress, none.progress)
        XCTAssertLessThanOrEqual(half.progress, 1)
    }

    func testRaiseStepCompletesWhenThePhoneIsLifted() {
        XCTAssertEqual(.raise, nextCalibrationState(.raise, input(tilt: 0.95)).step)
        XCTAssertEqual(.done, nextCalibrationState(.raise, input(tilt: 0.2)).step)
    }

    func testRaiseStepAlsoTimesOut() {
        XCTAssertEqual(.done, nextCalibrationState(.raise, input(tilt: 0.99, elapsedMs: 7_000)).step)
    }

    func testDoneStaysDone() {
        let s = nextCalibrationState(.done, input())
        XCTAssertEqual(.done, s.step)
        XCTAssertEqual(1, s.progress, accuracy: 0.001)
    }

    func testSweepingOutAndBackStillCounts() {
        let tracker = SweepTracker()
        for h in [0.0, 10, 20, 30, 20, 10, 0] { tracker.update(h) }
        XCTAssertEqual(60.0, tracker.totalDegrees, accuracy: 1.0)
    }

    func testSweepTrackingHandlesTheWrapAcrossNorth() {
        let tracker = SweepTracker()
        for h in [350.0, 355, 0, 5] { tracker.update(h) }
        XCTAssertEqual(15.0, tracker.totalDegrees, accuracy: 1.0)
    }

    func testSweepTrackingIgnoresSensorGlitches() {
        let tracker = SweepTracker()
        tracker.update(0.0)
        tracker.update(170.0)
        XCTAssertEqual(0.0, tracker.totalDegrees, accuracy: 0.001)
    }

    func testResetClearsTheSweep() {
        let tracker = SweepTracker()
        tracker.update(0.0)
        tracker.update(30.0)
        tracker.reset()
        XCTAssertEqual(0.0, tracker.totalDegrees, accuracy: 0.001)
    }

    func testAPhoneThatIsAlreadyReadyIsNotAskedToWave() {
        XCTAssertEqual(.done, initialCalibrationStep(input(tracking: true, compassReliable: true, tilt: 0.3)))
    }

    func testGoodSensorsButAFlatPhoneOnlyAsksForTheRaise() {
        XCTAssertEqual(.raise, initialCalibrationStep(input(tracking: true, compassReliable: true, tilt: 0.95)))
    }

    func testAnUnreliableCompassStillGetsTheFullSequence() {
        XCTAssertEqual(.wave, initialCalibrationStep(input(tracking: true, compassReliable: false, tilt: 0.3)))
    }

    func testNoTrackingStillGetsTheFullSequence() {
        XCTAssertEqual(.wave, initialCalibrationStep(input(tracking: false, compassReliable: true, tilt: 0.3)))
    }

    func testTheWaveEndsAsSoonAsTheSensorsAreReady() {
        XCTAssertEqual(.raise, nextCalibrationState(.wave, input(tracking: true, compassReliable: true, tilt: 0.9, movedDegrees: 0, elapsedMs: 1_500)).step)
    }

    func testACompassThatNeverReportsReliableStillEscapesViaTheSweep() {
        XCTAssertEqual(.raise, nextCalibrationState(.wave, input(tracking: true, compassReliable: false, tilt: 0.9, movedDegrees: 90, elapsedMs: 1_500)).step)
    }
}
