package com.sarab.vision.ritual

import com.sarab.vision.wear.shared.LapCounter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RitualCounterStateTest {
    private fun state() = RitualCounterState(
        tawaf = LapCounter("tawaf", sessionId = "phone-tawaf", startedAt = 100),
        sai = LapCounter("sai", sessionId = "phone-sai", startedAt = 200),
    )

    @Test fun phoneIsDefaultAndRitualsKeepIndependentCountsAndSessions() {
        val start = state()
        val result = start.increment().increment().selectMode("sai").increment()
        assertEquals(RitualCounterSource.PHONE, result.source)
        assertEquals(2, result.tawaf.count)
        assertEquals(1, result.sai.count)
        assertEquals(start.tawaf.sessionId, result.tawaf.sessionId)
        assertEquals(start.sai.sessionId, result.sai.sessionId)
    }

    @Test fun manualCountNeverLeavesZeroToSeven() {
        var result = state()
        repeat(20) { result = result.increment() }
        assertEquals(7, result.localCounter.count)
        assertEquals(7L, result.localCounter.revision)
        repeat(20) { result = result.undo() }
        assertEquals(0, result.localCounter.count)
        assertEquals(14L, result.localCounter.revision)
    }

    @Test fun resetOnlyStartsANewSelectedPhoneSessionEvenWhenClockMovesBack() {
        val start = state().increment().selectMode("sai").increment()
        val reset = start.reset("new-sai-session", 10)
        assertEquals(start.tawaf, reset.tawaf)
        assertEquals(0, reset.sai.count)
        assertEquals("new-sai-session", reset.sai.sessionId)
        assertTrue(reset.sai.startedAt > start.sai.startedAt)
    }

    @Test fun watchSelectionCannotMutateLocalCounts() {
        val selected = state().increment().copy(source = RitualCounterSource.WATCH)
        assertEquals(selected, selected.increment())
        assertEquals(selected, selected.undo())
        assertEquals(selected, selected.reset("must-not-apply", 500))
        assertEquals(1, selected.copy(source = RitualCounterSource.PHONE).localCounter.count)
    }

    @Test fun watchCountIsDisplayedWithoutOverwritingPhoneState() {
        val selected = state().increment().copy(source = RitualCounterSource.WATCH)
        val watch = RitualWatchState(LapCounter("tawaf", count = 5), connected = true)
        val display = ritualCounterDisplay(selected, watch)
        assertEquals(5, display.count)
        assertFalse(display.editable)
        assertEquals(1, selected.tawaf.count)
        assertTrue(display.note.contains("قد يتأخر"))
    }

    @Test fun missingOrOtherRitualWatchDataNeverFallsBackToPhone() {
        val selected = state().increment().copy(source = RitualCounterSource.WATCH)
        assertNull(ritualCounterDisplay(selected, RitualWatchState()).count)
        assertNull(ritualCounterDisplay(selected, RitualWatchState(LapCounter("sai", count = 7), true)).count)
    }

    @Test fun disconnectedWatchCountStaysExplicitlyCachedAndReadOnly() {
        val display = ritualCounterDisplay(state().copy(source = RitualCounterSource.WATCH),
            RitualWatchState(LapCounter("tawaf", count = 3), connected = false))
        assertEquals(3, display.count)
        assertTrue(display.note.contains("غير متصلة"))
        assertTrue(display.note.contains("محفوظ"))
        assertFalse(display.editable)
    }

    @Test fun persistenceRoundTripRetainsBothSessionsAndExplicitSource() {
        val original = state().increment().selectMode("sai").increment().copy(source = RitualCounterSource.WATCH)
        assertEquals(original, decodeRitualCounter(encodeRitualCounter(original)))
    }

    @Test fun corruptedRitualRecordDoesNotDiscardTheOtherRitual() {
        val original = state().increment().selectMode("sai").increment()
        val json = JSONObject(encodeRitualCounter(original))
        json.getJSONObject("tawaf").put("count", 99)
        val recovered = decodeRitualCounter(json.toString())
        assertEquals(original.sai, recovered.sai)
        assertEquals(0, recovered.tawaf.count)
        assertNotNull(recovered.storageError)
    }

    @Test fun invalidStateAndModeFailSafelyWithVisibleRecoveryNotice() {
        assertEquals("tawaf", state().selectMode("other").selectedMode)
        assertNotNull(decodeRitualCounter("not-json").storageError)
        assertNull(decodeRitualCounter(null).storageError)
    }
}
