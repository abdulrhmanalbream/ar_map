import XCTest
@testable import SmartMutawwif

final class VisionReliabilityTests: XCTestCase {
    func testUnchangedLiveImageRefreshesAtSceneExpiry() {
        XCTAssertTrue(VisionPolicy.staticRefreshDue(nil, now: 1000))
        XCTAssertFalse(VisionPolicy.staticRefreshDue(1000, now: 999))
        XCTAssertFalse(VisionPolicy.staticRefreshDue(1000, now: 7000))
        XCTAssertFalse(VisionPolicy.staticRefreshDue(1000, now: 30_999))
        XCTAssertTrue(VisionPolicy.staticRefreshDue(1000, now: 31_000))
        XCTAssertFalse(VisionInputPolicy.automaticAllowed(imported: true, importing: false, paused: false))
    }

    func testProviderAuthenticationDoesNotBecomeUserAuthentication() {
        let failure = VisionFailurePayloads.parse(status: 503, body: #"{"code":"VISION_AUTHENTICATION","retryable":false}"#, retryAfter: nil)
        XCTAssertEqual(failure.error, .serviceAuthentication)
        XCTAssertFalse(failure.retryable)
        XCTAssertEqual(VisionFailurePayloads.parse(status: 401, body: #"{"code":"VISION_AUTHENTICATION"}"#, retryAfter: nil).error, .authentication)
    }

    func testTimeoutModelAndContentFailuresRemainDistinct() {
        XCTAssertEqual(VisionFailurePayloads.parse(status: 504, body: nil, retryAfter: nil).error, .timeout)
        XCTAssertEqual(VisionFailurePayloads.parse(status: 503, body: #"{"code":"VISION_MODEL_UNAVAILABLE"}"#, retryAfter: nil).error, .modelUnavailable)
        let refusal = VisionFailurePayloads.parse(status: 422, body: #"{"code":"VISION_BLOCKED"}"#, retryAfter: nil)
        XCTAssertEqual(refusal.error, .responseBlocked)
        XCTAssertFalse(refusal.retryable)
    }

    func testBusyAndQuotaAdviceUsesLongerBoundedDelay() {
        let busy = VisionFailurePayloads.parse(status: 429, body: #"{"code":"VISION_BUSY","retryAfterSeconds":5,"retryable":true}"#, retryAfter: "8")
        XCTAssertEqual(busy.error, .serviceBusy)
        XCTAssertEqual(busy.retryAfterMs, 8_000)
        let quota = VisionFailurePayloads.parse(status: 429, body: #"{"code":"VISION_RATE_LIMITED","retryAfterSeconds":999999}"#, retryAfter: "900")
        XCTAssertEqual(quota.error, .rateLimit)
        XCTAssertEqual(quota.retryAfterMs, 300_000)
    }

    func testMalformedRetryAdviceCannotShortenOrOverflowCooldown() {
        for invalid in ["-5", "0", "1.5", "\"999\"", "null", "{}", "true"] {
            let parsed = VisionFailurePayloads.parse(status: 429, body: "{\"retryAfterSeconds\":\(invalid)}", retryAfter: "invalid")
            XCTAssertEqual(parsed.retryAfterMs, 0, invalid)
            let policy = VisionRetryPolicy()
            policy.failure(parsed, now: 100)
            XCTAssertEqual(policy.retryAtElapsedMs, 60_100, invalid)
        }
    }

    func testUnknownOrHtmlErrorCannotExposeServerContent() {
        let secret = "private upstream diagnostic"
        XCTAssertEqual(VisionFailurePayloads.parse(status: 503, body: "<html>\(secret)</html>", retryAfter: nil).error, .serviceUnavailable)
        XCTAssertEqual(VisionFailurePayloads.parse(status: 503, body: "{\"code\":\"\(secret)\",\"error\":\"\(secret)\"}", retryAfter: nil).error, .serviceUnavailable)
        XCTAssertFalse(VisionFailurePayloads.parse(status: 400, body: nil, retryAfter: nil).retryable)
    }

    func testErrorBodiesAreBoundedAndTyped() {
        XCTAssertEqual(VisionFailurePayloads.parse(status: 503, body: String(repeating: " ", count: 16_385), retryAfter: nil).error, .serviceUnavailable)
        XCTAssertTrue(VisionFailurePayloads.parse(status: 503, body: #"{"retryable":"false"}"#, retryAfter: nil).retryable)
        XCTAssertFalse(VisionFailurePayloads.parse(status: 503, body: #"{"retryable":false}"#, retryAfter: nil).retryable)
        // A numeric 0 is not the JSON boolean false.
        XCTAssertTrue(VisionFailurePayloads.parse(status: 503, body: #"{"retryable":0}"#, retryAfter: nil).retryable)
    }

    func testSharedDeadlineBlocksManualAndAutomaticUntilAdviceExpires() {
        let policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(error: .serviceBusy, retryable: true, retryAfterMs: 8_000), now: 1_000)
        XCTAssertEqual(policy.blocked(8_999, automatic: false), .serviceBusy)
        XCTAssertEqual(policy.blocked(8_999, automatic: true), .serviceBusy)
        XCTAssertNil(policy.blocked(9_000, automatic: false))
        XCTAssertNil(policy.blocked(9_000, automatic: true))
    }

    func testPermanentFailureStopsAutomaticButAllowsExplicitRecovery() {
        let policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(error: .serviceConfiguration, retryable: false), now: 100)
        XCTAssertEqual(policy.blocked(60_099, automatic: false), .serviceConfiguration)
        XCTAssertNil(policy.blocked(60_100, automatic: false))
        XCTAssertEqual(policy.blocked(10_000_000, automatic: true), .serviceConfiguration)
        policy.success()
        XCTAssertFalse(policy.automaticBlocked)
        XCTAssertEqual(policy.failures, 0)
        XCTAssertNil(policy.blocked(10_000_000, automatic: true))
    }

    func testRepeatedTransientFailuresBackOffAndSuccessResetsBudget() {
        let policy = VisionRetryPolicy()
        let timeout = VisionRequestFailure(error: .timeout, retryable: true)
        policy.failure(timeout, now: 0)
        XCTAssertEqual(policy.retryAtElapsedMs, 12_000)
        policy.failure(timeout, now: 12_000)
        XCTAssertEqual(policy.retryAtElapsedMs, 36_000)
        for _ in 0..<10 { policy.failure(timeout, now: 100_000) }
        XCTAssertEqual(policy.retryAtElapsedMs, 160_000)
        policy.success()
        policy.failure(timeout, now: 200_000)
        XCTAssertEqual(policy.retryAtElapsedMs, 212_000)
    }

    func testReplacingPhotoCanResolveRefusalButCannotBypassServiceWait() {
        let policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(error: .responseBlocked, retryable: false), now: 0)
        policy.newImage()
        XCTAssertNil(policy.blocked(0, automatic: false))
        policy.failure(VisionRequestFailure(error: .rateLimit, retryable: true, retryAfterMs: 90_000), now: 0)
        policy.newImage()
        XCTAssertEqual(policy.blocked(89_999, automatic: false), .rateLimit)
        policy.failure(VisionRequestFailure(error: .serviceAuthentication, retryable: false), now: 0)
        policy.newImage()
        XCTAssertEqual(policy.blocked(90_000, automatic: true), .serviceAuthentication)
    }

    func testDeadlineArithmeticNeverOverflows() {
        let policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(error: .rateLimit, retryable: true, retryAfterMs: Int64.max), now: Int64.max - 1)
        XCTAssertEqual(policy.retryAtElapsedMs, Int64.max)
    }

    func testImportedPhotoNeedsForegroundButNotCameraOrFreshPose() {
        XCTAssertTrue(VisionInputPolicy.available(foreground: true, cameraAvailable: false, imported: true, retained: false))
        XCTAssertFalse(VisionInputPolicy.available(foreground: false, cameraAvailable: true, imported: true, retained: true))
        XCTAssertFalse(VisionInputPolicy.available(foreground: true, cameraAvailable: false, imported: false, retained: false))
        XCTAssertTrue(VisionInputPolicy.frameUsable(imported: true, capturedAt: 0, now: 1_000_000))
        XCTAssertFalse(VisionInputPolicy.frameUsable(imported: false, capturedAt: 0, now: 1_000_000))
    }

    func testChoosingOrHoldingPhotoNeverAutomaticallyUploadsIt() {
        XCTAssertFalse(VisionInputPolicy.automaticAllowed(imported: true, importing: false, paused: false))
        XCTAssertFalse(VisionInputPolicy.automaticAllowed(imported: false, importing: true, paused: false))
        XCTAssertFalse(VisionInputPolicy.automaticAllowed(imported: false, importing: false, paused: true))
        XCTAssertTrue(VisionInputPolicy.automaticAllowed(imported: false, importing: false, paused: false))
    }
}
