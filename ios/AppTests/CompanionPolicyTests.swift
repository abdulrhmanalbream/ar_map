import XCTest
@testable import SmartMutawwif

final class CompanionPolicyTests: XCTestCase {
    // MARK: Narration

    func testSameLandmarkIsNotRepeatedEveryAnalysisCycle() {
        let gate = VisionNarrationGate()
        XCTAssertTrue(gate.shouldSpeak(sceneId: "a", key: "k", capturedAt: 100, now: 100, resumedAt: 0, muted: false, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "a", key: "k", capturedAt: 100, now: 200, resumedAt: 0, muted: false, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "b", key: "k", capturedAt: 6100, now: 6100, resumedAt: 0, muted: false, busy: false))
        XCTAssertTrue(gate.shouldSpeak(sceneId: "c", key: "k", capturedAt: 120_100, now: 120_100, resumedAt: 0, muted: false, busy: false))
    }

    func testMutedOrPreResumeScenesNeverReplayOnUnmute() {
        let gate = VisionNarrationGate()
        XCTAssertFalse(gate.shouldSpeak(sceneId: "a", key: "x", capturedAt: 100, now: 100, resumedAt: 0, muted: true, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "a", key: "x", capturedAt: 100, now: 110, resumedAt: 0, muted: false, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "b", key: "x", capturedAt: 100, now: 300, resumedAt: 200, muted: false, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "c", key: "x", capturedAt: 100, now: 60_101, resumedAt: 0, muted: false, busy: false))
    }

    func testMicrophoneCanDeferButNotReplayAConsumedNarration() {
        let gate = VisionNarrationGate()
        XCTAssertFalse(gate.shouldSpeak(sceneId: "a", key: "x", capturedAt: 100, now: 200, resumedAt: 0, muted: false, busy: true))
        XCTAssertTrue(gate.shouldSpeak(sceneId: "a", key: "x", capturedAt: 100, now: 400, resumedAt: 0, muted: false, busy: false))
        XCTAssertFalse(gate.shouldSpeak(sceneId: "a", key: "x", capturedAt: 100, now: 500, resumedAt: 0, muted: false, busy: false))
    }

    func testCuratedQualificationAndLanguageArePreserved() {
        let devotional = VisionDevotional(id: "q", title: "Reminder", arabicText: "النص العربي", translation: "Translated text",
                                          note: "Not a prescribed prayer", sourceIds: [])
        var item = VisionItem(id: "x", kind: "landmark", label: "Maqam", description: "Description",
                              box: VisionBox(yMin: 0, xMin: 0, yMax: 1000, xMax: 1000), devotionals: [devotional])
        XCTAssertEqual(visionLandmarkNarration(item, language: "ar"), "Description\n\nReminder\n\nNot a prescribed prayer\n\nالنص العربي")
        XCTAssertEqual(visionLandmarkNarration(item, language: "en"), "Description\n\nReminder\n\nNot a prescribed prayer\n\nTranslated text")
        item.devotionals = []
        XCTAssertFalse(visionLandmarkNarration(item, language: "ar").contains("prayer"))
    }

    // MARK: Speech

    func testExistingReplyIsSilentAndEachNewReplyIsSpokenOnce() {
        let gate = VisionReplyReadGate(initialRevision: 8)
        XCTAssertFalse(gate.consume(8, foreground: true))
        XCTAssertTrue(gate.consume(9, foreground: true))
        XCTAssertFalse(gate.consume(9, foreground: true))
        XCTAssertFalse(gate.consume(7, foreground: true))
        XCTAssertTrue(gate.consume(10, foreground: true))
    }

    func testBackgroundReplyIsConsumedWithoutReplay() {
        let gate = VisionReplyReadGate(initialRevision: 0)
        XCTAssertFalse(gate.consume(1, foreground: false))
        XCTAssertFalse(gate.consume(1, foreground: true))
        XCTAssertTrue(gate.consume(2, foreground: true))
    }

    func testUtteranceGenerationMatching() {
        XCTAssertTrue(visionUtteranceMatches(12, "vision-12-0"))
        XCTAssertFalse(visionUtteranceMatches(12, "vision-1-2"))
        XCTAssertFalse(visionUtteranceMatches(12, nil))
    }

    func testLongArabicAnswerRemainsCompleteAcrossChunks() {
        let text = Array(repeating: "هذا وصف واضح للمعلم ويمكنك أن تسأل عن تفاصيله", count: 90).joined(separator: " ")
        let chunks = visionSpeechChunks(text, maxLength: 180)
        XCTAssertTrue(chunks.count > 1)
        XCTAssertTrue(chunks.allSatisfy { !$0.isEmpty && $0.utf16.count <= 180 })
        XCTAssertEqual(chunks.joined(separator: " "), text)
    }

    func testEmojiAreNeverSplit() {
        let text = "مرحبا" + String(repeating: "🙂", count: 12) + "نهاية"
        let chunks = visionSpeechChunks(text, maxLength: 7)
        XCTAssertTrue(chunks.allSatisfy { $0.utf16.count <= 7 })
        XCTAssertEqual(chunks.joined(), text)
    }

    func testEmptySpeechIsIgnored() {
        XCTAssertTrue(visionSpeechChunks("  ", maxLength: 100).isEmpty)
    }

    // MARK: Retry presentation

    func testFinalPartialSecondStillDisablesSubmission() {
        XCTAssertEqual(visionRetrySeconds(4_500, now: 1_000), 4)
        XCTAssertEqual(visionRetrySeconds(4_500, now: 3_500), 1)
        XCTAssertEqual(visionRetrySeconds(4_500, now: 4_499), 1)
        XCTAssertEqual(visionRetrySeconds(4_500, now: 4_500), 0)
        XCTAssertEqual(visionRetrySeconds(0, now: 1_000), 0)
        XCTAssertEqual(visionRetrySeconds(1_000, now: -1), 0)
        XCTAssertEqual(visionRetrySeconds(Int64.max, now: Int64.max - 1_001), 2)
    }

    // MARK: Enrollment

    func testPublicEnrollmentNeedsOnlyNameAndLanguage() throws {
        let request = try enrollmentRequest(name: "  زائر  ", language: "ur", code: nil)
        XCTAssertEqual(request.path, "/devices/enroll-default")
        XCTAssertEqual(request.name, "زائر")
        XCTAssertEqual(Set(request.body.keys), ["name", "language"])
    }

    func testInvitationUsesPrivateEndpoint() throws {
        let request = try enrollmentRequest(name: "Visitor", language: "en", code: "  ABC12345  ")
        XCTAssertEqual(request.path, "/devices/enroll")
        XCTAssertEqual(request.body["code"], "ABC12345")
    }

    func testBlankCodeAndBadNamesAreRejected() {
        XCTAssertThrowsError(try enrollmentRequest(name: "Visitor", language: "en", code: "  "))
        XCTAssertThrowsError(try enrollmentRequest(name: "  ", language: "ar", code: nil))
        XCTAssertThrowsError(try enrollmentRequest(name: String(repeating: "a", count: 81), language: "ar", code: nil))
    }

    func testPlatformErrorsNeverShowServerText() {
        let message = platformErrorMessage(status: 503, response: ["code": "DEFAULT_GROUP_UNAVAILABLE", "error": "Server detail"])
        XCTAssertTrue(message.contains("المجموعة العامة"))
        XCTAssertFalse(message.contains("Server detail"))
        XCTAssertEqual(platformErrorMessage(status: 429, response: [:]), "طلبات كثيرة؛ حاول بعد قليل")
        XCTAssertEqual(platformErrorMessage(status: 503, response: ["code": "OTHER"]), "تعذّر الاتصال بالخدمة (503)")
    }

    func testServerValidationRejectsPathsAndCredentials() {
        XCTAssertEqual(try? validatedServer("https://sm.hsaie.com/"), "https://sm.hsaie.com")
        XCTAssertThrowsError(try validatedServer("http://sm.hsaie.com"))
        XCTAssertThrowsError(try validatedServer("https://user:pass@sm.hsaie.com"))
        XCTAssertThrowsError(try validatedServer("https://sm.hsaie.com/api"))
    }

    // MARK: Ritual

    private func ritual() -> RitualCounterState {
        RitualCounterState(tawaf: LapCounter(mode: "tawaf", sessionId: "phone-tawaf", startedAt: 100)!,
                           sai: LapCounter(mode: "sai", sessionId: "phone-sai", startedAt: 200)!)
    }

    func testRitualsKeepIndependentCountsAndSessions() {
        let start = ritual()
        let result = start.increment().increment().selectMode("sai").increment()
        XCTAssertEqual(result.tawaf.count, 2)
        XCTAssertEqual(result.sai.count, 1)
        XCTAssertEqual(result.tawaf.sessionId, start.tawaf.sessionId)
    }

    func testManualCountNeverLeavesZeroToSeven() {
        var result = ritual()
        for _ in 0..<20 { result = result.increment() }
        XCTAssertEqual(result.localCounter.count, 7)
        XCTAssertEqual(result.localCounter.revision, 7)
        for _ in 0..<20 { result = result.undo() }
        XCTAssertEqual(result.localCounter.count, 0)
        XCTAssertEqual(result.localCounter.revision, 14)
    }

    func testResetStartsNewSessionEvenWhenClockMovesBack() {
        let start = ritual().increment().selectMode("sai").increment()
        let reset = start.reset("new-sai-session", now: 10)
        XCTAssertEqual(reset.tawaf, start.tawaf)
        XCTAssertEqual(reset.sai.count, 0)
        XCTAssertEqual(reset.sai.sessionId, "new-sai-session")
        XCTAssertTrue(reset.sai.startedAt > start.sai.startedAt)
    }

    func testPersistenceRoundTrip() {
        let original = ritual().increment().selectMode("sai").increment()
        XCTAssertEqual(decodeRitualCounter(encodeRitualCounter(original)), original)
    }

    func testCorruptedRitualRecordDoesNotDiscardTheOther() throws {
        let original = ritual().increment().selectMode("sai").increment()
        let data = try XCTUnwrap(encodeRitualCounter(original).data(using: .utf8))
        var json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        var tawaf = try XCTUnwrap(json["tawaf"] as? [String: Any])
        tawaf["count"] = 99
        json["tawaf"] = tawaf
        let text = String(data: try JSONSerialization.data(withJSONObject: json), encoding: .utf8)
        let recovered = decodeRitualCounter(text)
        XCTAssertEqual(recovered.sai, original.sai)
        XCTAssertEqual(recovered.tawaf.count, 0)
        XCTAssertNotNil(recovered.storageError)
    }

    func testInvalidStateFailsSafely() {
        XCTAssertEqual(ritual().selectMode("other").selectedMode, "tawaf")
        XCTAssertNotNil(decodeRitualCounter("not-json").storageError)
        XCTAssertNil(decodeRitualCounter(nil).storageError)
    }
}
