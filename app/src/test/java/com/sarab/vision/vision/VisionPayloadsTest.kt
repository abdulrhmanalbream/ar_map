package com.sarab.vision.vision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionPayloadsTest {
    private fun item(id: String = "sign") = JSONObject().put("id", id).put("kind", "text")
        .put("label", "مدخل").put("description", "Entrance")
        .put("box", JSONArray(listOf(100, 200, 400, 600))).put("sourceIds", JSONArray(listOf("official", "invented")))
    private fun source(id: String = "official", url: String = "https://example.org/guide") =
        JSONObject().put("id", id).put("title", "Official guide").put("url", url)
    private fun response() = JSONObject().put("sceneId", "scene-1").put("summary", "Entrance sign")
        .put("provider", "gemini").put("items", JSONArray().put(item()))
        .put("sources", JSONArray().put(source()))

    @Test fun parsesLabelsAndOnlyKnownSourceReferences() {
        val analysis = VisionPayloads.analysis(response())
        assertEquals("مدخل", analysis.items.single().label)
        assertEquals(VisionBox(100.0, 200.0, 400.0, 600.0), analysis.items.single().box)
        assertEquals(listOf("official"), analysis.items.single().sourceIds)
    }
    @Test fun duplicateIdsAndInvalidBoxesAreDropped() {
        val json = response().put("items", JSONArray().put(item()).put(item())
            .put(item("bad").put("box", JSONArray(listOf(900, 200, 100, 600)))))
        assertEquals(1, VisionPayloads.analysis(json).items.size)
    }
    @Test fun unsupportedItemsAndHtmlLikeSourcesDoNotBecomeActions() {
        val json = response().put("items", JSONArray().put(item().put("kind", "navigate")))
            .put("sources", JSONArray().put(source("script", "javascript:alert(1)"))
                .put(source("credentials", "https://username:password@example.org/private")))
        val parsed = VisionPayloads.analysis(json)
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.sources.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedSummaryRejected() {
        VisionPayloads.analysis(response().put("summary", "x".repeat(4001)))
    }
    @Test fun questionPayloadKeepsOnlyBoundedRecentHistory() {
        val history = (0..20).map { VisionHistoryEntry(if (it % 2 == 0) "user" else "assistant", "x".repeat(3000)) }
        val payload = VisionPayloads.history(history)
        assertEquals(6, payload.length())
        assertEquals(1000, payload.getJSONObject(0).getString("text").length)
    }
    @Test fun answerKeepsSourceAttribution() {
        val answer = VisionPayloads.answer(JSONObject().put("reply", "This is an entrance.").put("provider", "gemini")
            .put("sources", JSONArray().put(source())))
        assertEquals("https://example.org/guide", answer.sources.single().url)
    }
    private fun devotional() = JSONObject().put("id", "reminder").put("title", "Reference")
        .put("arabicText", "نص عربي للاختبار").put("translation", "Test meaning")
        .put("note", "Test context").put("sourceIds", JSONArray(listOf("official")))

    @Test fun recognizedLandmarkRetainsOnlySourcedDevotionals() {
        val landmark = item().put("kind", "landmark").put("landmarkId", "maqam_ibrahim")
            .put("devotionals", JSONArray().put(devotional()))
        val parsed = VisionPayloads.analysis(response().put("items", JSONArray().put(landmark))).items.single()
        assertEquals("maqam_ibrahim", parsed.landmarkId)
        assertEquals("Test meaning", parsed.devotionals.single().translation)
        val request = VisionPayloads.selected(parsed)
        assertEquals("maqam_ibrahim", request.getString("landmarkId"))
        assertFalse(request.has("devotionals"))
    }
    @Test fun unknownDevotionalSourcesAndNonLandmarkAttachmentsAreIgnored() {
        val unknown = devotional().put("sourceIds", JSONArray(listOf("invented")))
        val landmark = item().put("kind", "landmark").put("landmarkId", "maqam_ibrahim")
            .put("devotionals", JSONArray().put(unknown))
        assertTrue(VisionPayloads.analysis(response().put("items", JSONArray().put(landmark))).items.single().devotionals.isEmpty())
        landmark.put("kind", "object").put("devotionals", JSONArray().put(devotional()))
        val objectItem = VisionPayloads.analysis(response().put("items", JSONArray().put(landmark))).items.single()
        assertNull(objectItem.landmarkId)
        assertTrue(objectItem.devotionals.isEmpty())
    }
    @Test fun malformedDevotionalDoesNotDiscardRecognizedLandmark() {
        val landmark = item().put("kind", "landmark").put("landmarkId", "kaaba")
            .put("devotionals", JSONArray().put(devotional().put("arabicText", "x".repeat(601))))
        val parsed = VisionPayloads.analysis(response().put("items", JSONArray().put(landmark))).items.single()
        assertEquals("kaaba", parsed.landmarkId)
        assertTrue(parsed.devotionals.isEmpty())
    }
}
