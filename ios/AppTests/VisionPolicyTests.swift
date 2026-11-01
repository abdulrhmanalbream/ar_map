import XCTest
@testable import SmartMutawwif

final class VisionPolicyTests: XCTestCase {
    func testYawWrapAcrossNorthIsNear() {
        XCTAssertTrue(VisionPolicy.nearPose(VisionPose(yawDegrees: 359, pitchDegrees: 2, sessionId: 4),
                                            VisionPose(yawDegrees: 1, pitchDegrees: 3, sessionId: 4)))
    }

    func testPoseRequiresValidSameSessionAndSmallRotation() {
        XCTAssertFalse(VisionPolicy.nearPose(VisionPose(yawDegrees: 0, pitchDegrees: 0, sessionId: 1),
                                             VisionPose(yawDegrees: 0, pitchDegrees: 0, sessionId: 2)))
        XCTAssertFalse(VisionPolicy.nearPose(VisionPose(yawDegrees: 0, pitchDegrees: 0), VisionPose(yawDegrees: 4, pitchDegrees: 0)))
        XCTAssertFalse(VisionPolicy.nearPose(VisionPose(yawDegrees: 0, pitchDegrees: 0), VisionPose(yawDegrees: 0, pitchDegrees: 4)))
        XCTAssertFalse(VisionPolicy.nearPose(VisionPose(yawDegrees: .nan, pitchDegrees: 0), VisionPose(yawDegrees: 0, pitchDegrees: 0)))
        XCTAssertFalse(VisionPolicy.nearPose(nil, VisionPose(yawDegrees: 0, pitchDegrees: 0)))
        XCTAssertFalse(VisionPolicy.nearPose(VisionPose(yawDegrees: 0, pitchDegrees: 0),
                                             VisionPose(yawDegrees: 0, pitchDegrees: 0, rollDegrees: 4)))
    }

    func testStationaryMarkersExpireWithoutMotionEvents() {
        let pose = VisionPose(yawDegrees: 0, pitchDegrees: 0)
        XCTAssertTrue(VisionPolicy.markersFresh(1000, now: 31_000, captured: pose, current: pose))
        XCTAssertFalse(VisionPolicy.markersFresh(1000, now: 31_001, captured: pose, current: pose))
    }

    func testFutureAndStaleCameraFramesAreRejected() {
        XCTAssertFalse(VisionPolicy.freshTimestamp(1001, now: 1000, maximumAge: VisionPolicy.frameFreshMs))
        XCTAssertFalse(VisionPolicy.freshTimestamp(1000, now: 2501, maximumAge: VisionPolicy.frameFreshMs))
        XCTAssertTrue(VisionPolicy.freshTimestamp(1000, now: 2500, maximumAge: VisionPolicy.frameFreshMs))
    }

    func testCenterCropClipsWideImageToPortraitViewport() throws {
        let full = try XCTUnwrap(cropVisionBox(VisionBox(yMin: 0, xMin: 0, yMax: 1000, xMax: 1000),
                                               imageWidth: 1920, imageHeight: 1080, viewportWidth: 360, viewportHeight: 640))
        XCTAssertEqual(full.left, 0, accuracy: 0.01)
        XCTAssertEqual(full.top, 0, accuracy: 0.01)
        XCTAssertEqual(full.right, 360, accuracy: 0.01)
        XCTAssertEqual(full.bottom, 640, accuracy: 0.01)
        XCTAssertNil(cropVisionBox(VisionBox(yMin: 100, xMin: 0, yMax: 200, xMax: 100),
                                   imageWidth: 1920, imageHeight: 1080, viewportWidth: 360, viewportHeight: 640))
    }

    func testNormalizedCoordinatesUseYXOrder() {
        let mapped = cropVisionBox(VisionBox(yMin: 100, xMin: 200, yMax: 400, xMax: 600),
                                   imageWidth: 1000, imageHeight: 1000, viewportWidth: 500, viewportHeight: 500)
        XCTAssertEqual(mapped, VisionScreenBox(left: 100, top: 50, right: 300, bottom: 200))
    }

    func testFitKeepsWholeImageInsideViewport() throws {
        let full = try XCTUnwrap(fitVisionBox(VisionBox(yMin: 0, xMin: 0, yMax: 1000, xMax: 1000),
                                              imageWidth: 1920, imageHeight: 1080, viewportWidth: 360, viewportHeight: 640))
        XCTAssertEqual(full.left, 0, accuracy: 0.01)
        XCTAssertEqual(full.right, 360, accuracy: 0.01)
        XCTAssertEqual(full.top, (640 - 202.5) / 2, accuracy: 0.01)
    }

    func testMalformedBoxesNeverReachOverlay() {
        XCTAssertNil(cropVisionBox(VisionBox(yMin: 500, xMin: 0, yMax: 100, xMax: 100), imageWidth: 100, imageHeight: 100, viewportWidth: 100, viewportHeight: 100))
        XCTAssertNil(cropVisionBox(VisionBox(yMin: 0, xMin: -1, yMax: 100, xMax: 100), imageWidth: 100, imageHeight: 100, viewportWidth: 100, viewportHeight: 100))
        XCTAssertNil(cropVisionBox(VisionBox(yMin: 0, xMin: 0, yMax: 100, xMax: 100), imageWidth: 100, imageHeight: 100, viewportWidth: .nan, viewportHeight: 100))
    }

    func testCancellationKeepsSingleSlotUntilWorkerExits() throws {
        let gate = VisionRequestGate()
        let first = try XCTUnwrap(gate.start(0, automatic: true))
        gate.invalidate()
        XCTAssertFalse(gate.accepts(first))
        XCTAssertTrue(gate.busy)
        XCTAssertNil(gate.start(13_000, automatic: false))
        gate.finish(first)
        XCTAssertNotNil(gate.start(13_000, automatic: true))
    }

    func testOldCompletionCannotReleaseANewerRequest() throws {
        let gate = VisionRequestGate()
        let first = try XCTUnwrap(gate.start(0, automatic: false))
        gate.finish(first)
        let second = try XCTUnwrap(gate.start(1, automatic: false))
        gate.finish(first)
        XCTAssertTrue(gate.busy)
        XCTAssertTrue(gate.accepts(second))
    }

    func testAutomaticRequestsWaitSixSecondsAfterAnyCaptureAttempt() throws {
        let gate = VisionRequestGate()
        gate.finish(try XCTUnwrap(gate.start(1000, automatic: false)))
        XCTAssertNil(gate.start(6_999, automatic: true))
        XCTAssertNil(gate.start(999, automatic: true))
        XCTAssertNotNil(gate.start(7_000, automatic: true))
    }

    func testProviderFailureBackoffIsBounded() {
        XCTAssertEqual(VisionPolicy.retryDelayMs(1), 12_000)
        XCTAssertEqual(VisionPolicy.retryDelayMs(2), 24_000)
        XCTAssertEqual(VisionPolicy.retryDelayMs(12), 60_000)
    }

    func testIdenticalSceneSignaturePasses() {
        let signature = VisionFrameSignature(luma: (0..<192).map { $0 % 255 })
        XCTAssertEqual(visionSignatureDifference(signature, signature) ?? -1, 0, accuracy: 0.00001)
        XCTAssertTrue(visionSignaturesMatch(signature, signature))
    }

    func testSceneChangeHidesBoxesEvenWithSamePose() {
        let before = VisionFrameSignature(luma: (0..<192).map { $0 % 16 < 8 ? 20 : 220 })
        let after = VisionFrameSignature(luma: (0..<192).map { $0 % 16 < 6 ? 20 : 220 })
        XCTAssertFalse(visionSignaturesMatch(before, after))
    }

    func testLocalObjectChangeAndInvalidSamplesAreRejected() {
        let before = VisionFrameSignature(luma: Array(repeating: 80, count: 192))
        let after = VisionFrameSignature(luma: (0..<192).map { $0 < 24 ? 140 : 80 })
        XCTAssertFalse(visionSignaturesMatch(before, after))
        XCTAssertNil(visionSignatureDifference(before, VisionFrameSignature(luma: [80])))
        XCTAssertFalse(visionSignaturesMatch(before, nil))
    }
}
