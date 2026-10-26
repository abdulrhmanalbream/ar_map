package com.sarab.vision.ui

import com.sarab.vision.vision.VisionBox
import com.sarab.vision.vision.VisionDevotional
import com.sarab.vision.vision.VisionItem
import org.junit.Assert.*
import org.junit.Test

class VisionNarrationPolicyTest {
    @Test fun sameLandmarkIsNotRepeatedEveryAnalysisCycle() {
        val gate = VisionNarrationGate()
        assertTrue(gate.shouldSpeak("a", "eye:ar:kaaba", 100, 100, 0, false, false))
        assertFalse(gate.shouldSpeak("a", "eye:ar:kaaba", 100, 200, 0, false, false))
        assertFalse(gate.shouldSpeak("b", "eye:ar:kaaba", 6100, 6100, 0, false, false))
        assertTrue(gate.shouldSpeak("c", "eye:ar:kaaba", 120_100, 120_100, 0, false, false))
    }

    @Test fun languageAndNewPhotoMayHaveTheirOwnNarration() {
        val gate = VisionNarrationGate()
        assertTrue(gate.shouldSpeak("a", "eye:ar:kaaba", 100, 100, 0, false, false))
        assertTrue(gate.shouldSpeak("b", "eye:en:kaaba", 200, 200, 0, false, false))
        assertTrue(gate.shouldSpeak("c", "photo:2:ar:kaaba", 300, 300, 0, false, false))
    }

    @Test fun mutedOrPreResumeScenesNeverReplayOnUnmute() {
        val gate = VisionNarrationGate()
        assertFalse(gate.shouldSpeak("a", "x", 100, 100, 0, true, false))
        assertFalse(gate.shouldSpeak("a", "x", 100, 110, 0, false, false))
        assertFalse(gate.shouldSpeak("b", "x", 100, 300, 200, false, false))
        assertFalse(gate.shouldSpeak("c", "x", 100, 60_101, 0, false, false))
    }

    @Test fun aNewResultCanNarrateAfterProviderDelayWithinOneMinute() {
        val gate = VisionNarrationGate()
        assertTrue(gate.shouldSpeak("delayed", "photo:ar:kaaba", 100, 20_100, 0, false, false))
        assertFalse(gate.shouldSpeak("expired", "photo:ar:maqam", 100, 60_101, 0, false, false))
        assertFalse(gate.shouldSpeak("before-resume", "photo:ar:maqam", 100, 20_100, 200, false, false))
    }

    @Test fun microphoneCanDeferButNotReplayAConsumedNarration() {
        val gate = VisionNarrationGate()
        assertFalse(gate.shouldSpeak("a", "x", 100, 200, 0, false, true))
        assertTrue(gate.shouldSpeak("a", "x", 100, 400, 0, false, false))
        assertFalse(gate.shouldSpeak("a", "x", 100, 500, 0, false, false))
    }

    @Test fun curatedQualificationAndLanguageArePreserved() {
        val item = VisionItem("x", "landmark", "Maqam", "Description", VisionBox(0.0, 0.0, 1000.0, 1000.0),
            devotionals = listOf(VisionDevotional("q", "Reminder", "النص العربي", "Translated text", "Not a prescribed prayer", emptyList())))
        assertEquals("Description\n\nReminder\n\nNot a prescribed prayer\n\nالنص العربي", visionLandmarkNarration(item, "ar"))
        assertEquals("Description\n\nReminder\n\nNot a prescribed prayer\n\nTranslated text", visionLandmarkNarration(item, "en"))
        assertFalse(visionLandmarkNarration(item.copy(devotionals = emptyList()), "ar").contains("prayer"))
    }
}
