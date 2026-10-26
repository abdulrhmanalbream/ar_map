package com.sarab.vision.core

import org.junit.Assert.*
import org.junit.Test

class VoiceCuePlaybackTest {
    @Test fun routineUpdateAfterSixSecondsWaitsForTheWholeDirectionAndDistancePhrase() {
        val original = "اتجه قليلاً إلى اليمين، على بعد 1300 متر"
        val state = nextVoiceCue(VoiceState(), original, false, 10_000).first
        val changed = "اتجه يميناً، على بعد 1300 متر"
        val deferred = nextVoiceCue(state, changed, false, 16_100, playbackBusy = true)
        assertEquals(state, deferred.first)
        assertNull(deferred.second)
        val afterCompletion = nextVoiceCue(deferred.first, changed, false, 17_900, playbackBusy = false)
        assertEquals(changed, afterCompletion.second)
        assertEquals(17_900L, afterCompletion.first.lastAtMs)
    }

    @Test fun manyBusyTicksDoNotConsumeLatestInstructionOrAdvanceItsTimestamp() {
        val state = VoiceState("الأمام", 10_000)
        var deferred = state
        for (time in 16_000L..20_000L step 250) {
            val result = nextVoiceCue(deferred, "يمين $time", false, time, playbackBusy = true)
            assertEquals(state, result.first)
            assertNull(result.second)
            deferred = result.first
        }
        assertEquals("آخر اتجاه", nextVoiceCue(deferred, "آخر اتجاه", false, 20_001).second)
    }

    @Test fun urgentArrivalInterruptsBusyPlaybackBeforeTheSixSecondGap() {
        val state = VoiceState("اتجه يميناً", 10_000)
        val result = nextVoiceCue(state, "وصلت إلى وجهتك", true, 10_500, playbackBusy = true)
        assertEquals("وصلت إلى وجهتك", result.second)
        assertEquals(10_500L, result.first.lastAtMs)
    }

    @Test fun completionStillPreservesDedupAndMinimumSpacingForShortPhrases() {
        val state = VoiceState("الأمام", 10_000)
        assertNull(nextVoiceCue(state, "الأمام", false, 30_000, playbackBusy = false).second)
        assertNull(nextVoiceCue(state, "يمين", false, 15_999, playbackBusy = false).second)
        assertEquals("يمين", nextVoiceCue(state, "يمين", false, 16_000, playbackBusy = false).second)
    }
}
