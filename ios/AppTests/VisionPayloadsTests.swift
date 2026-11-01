import XCTest
@testable import SmartMutawwif

final class VisionPayloadsTests: XCTestCase {
    private func item(_ id: String = "sign") -> [String: Any] {
        ["id": id, "kind": "text", "label": "مدخل", "description": "Entrance",
         "box": [100, 200, 400, 600], "sourceIds": ["official", "invented"]]
    }
    private func source(_ id: String = "official", url: String = "https://example.org/guide") -> [String: Any] {
        ["id": id, "title": "Official guide", "url": url]
    }
    private func response(items: [[String: Any]]? = nil, sources: [[String: Any]]? = nil) -> [String: Any] {
        ["sceneId": "scene-1", "summary": "Entrance sign", "provider": "gemini",
         "items": items ?? [item()], "sources": sources ?? [source()]]
    }
    private func devotional() -> [String: Any] {
        ["id": "reminder", "title": "Reference", "arabicText": "نص عربي للاختبار",
         "translation": "Test meaning", "note": "Test context", "sourceIds": ["official"]]
    }

    func testParsesLabelsAndOnlyKnownSourceReferences() throws {
        let analysis = try VisionPayloads.analysis(response())
        XCTAssertEqual(analysis.items.count, 1)
        XCTAssertEqual(analysis.items[0].label, "مدخل")
        XCTAssertEqual(analysis.items[0].box, VisionBox(yMin: 100, xMin: 200, yMax: 400, xMax: 600))
        XCTAssertEqual(analysis.items[0].sourceIds, ["official"])
    }

    func testDuplicateIdsAndInvalidBoxesAreDropped() throws {
        var bad = item("bad")
        bad["box"] = [900, 200, 100, 600]
        XCTAssertEqual(try VisionPayloads.analysis(response(items: [item(), item(), bad])).items.count, 1)
    }

    func testBooleanCoordinatesAreNotNumbers() throws {
        var bad = item("bool")
        bad["box"] = [true, 200, 400, 600] as [Any]
        XCTAssertTrue(try VisionPayloads.analysis(response(items: [bad])).items.isEmpty)
    }

    func testUnsupportedItemsAndUnsafeSourcesDoNotBecomeActions() throws {
        var navigate = item()
        navigate["kind"] = "navigate"
        let parsed = try VisionPayloads.analysis(response(items: [navigate], sources: [
            source("script", url: "javascript:alert(1)"),
            source("credentials", url: "https://username:password@example.org/private"),
        ]))
        XCTAssertTrue(parsed.items.isEmpty)
        XCTAssertTrue(parsed.sources.isEmpty)
    }

    func testOversizedSummaryRejected() {
        var json = response()
        json["summary"] = String(repeating: "x", count: 4001)
        XCTAssertThrowsError(try VisionPayloads.analysis(json))
    }

    func testQuestionPayloadKeepsOnlyBoundedRecentHistory() {
        let history = (0...20).map { VisionHistoryEntry(role: $0 % 2 == 0 ? "user" : "assistant", text: String(repeating: "x", count: 3000)) }
        let payload = VisionPayloads.history(history)
        XCTAssertEqual(payload.count, 6)
        XCTAssertEqual((payload[0]["text"] as? String)?.count, 1000)
    }

    func testAnswerKeepsSourceAttribution() throws {
        let answer = try VisionPayloads.answer(["reply": "This is an entrance.", "provider": "gemini", "sources": [source()]])
        XCTAssertEqual(answer.sources.first?.url, "https://example.org/guide")
    }

    func testRecognizedLandmarkRetainsOnlySourcedDevotionals() throws {
        var landmark = item()
        landmark["kind"] = "landmark"
        landmark["landmarkId"] = "maqam_ibrahim"
        landmark["devotionals"] = [devotional()]
        let parsed = try XCTUnwrap(try VisionPayloads.analysis(response(items: [landmark])).items.first)
        XCTAssertEqual(parsed.landmarkId, "maqam_ibrahim")
        XCTAssertEqual(parsed.devotionals.first?.translation, "Test meaning")
        let request = VisionPayloads.selected(parsed)
        XCTAssertEqual(request["landmarkId"] as? String, "maqam_ibrahim")
        XCTAssertNil(request["devotionals"])
    }

    func testUnknownDevotionalSourcesAndNonLandmarkAttachmentsAreIgnored() throws {
        var unknown = devotional()
        unknown["sourceIds"] = ["invented"]
        var landmark = item()
        landmark["kind"] = "landmark"
        landmark["landmarkId"] = "maqam_ibrahim"
        landmark["devotionals"] = [unknown]
        XCTAssertTrue(try VisionPayloads.analysis(response(items: [landmark])).items.first?.devotionals.isEmpty ?? false)
        landmark["kind"] = "object"
        landmark["devotionals"] = [devotional()]
        let objectItem = try XCTUnwrap(try VisionPayloads.analysis(response(items: [landmark])).items.first)
        XCTAssertNil(objectItem.landmarkId)
        XCTAssertTrue(objectItem.devotionals.isEmpty)
    }

    func testMalformedDevotionalDoesNotDiscardRecognizedLandmark() throws {
        var bad = devotional()
        bad["arabicText"] = String(repeating: "x", count: 601)
        var landmark = item()
        landmark["kind"] = "landmark"
        landmark["landmarkId"] = "kaaba"
        landmark["devotionals"] = [bad]
        let parsed = try XCTUnwrap(try VisionPayloads.analysis(response(items: [landmark])).items.first)
        XCTAssertEqual(parsed.landmarkId, "kaaba")
        XCTAssertTrue(parsed.devotionals.isEmpty)
    }
}
