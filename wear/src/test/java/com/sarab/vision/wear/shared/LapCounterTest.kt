package com.sarab.vision.wear.shared

import org.junit.Assert.*
import org.junit.Test

class LapCounterTest {
    @Test fun `manual counter cannot exceed seven or underflow undo`() {
        val start = LapCounter("tawaf", sessionId = "first", startedAt = 100)
        assertEquals(start, start.undo())
        val seven = (1..20).fold(start) { current, _ -> current.increment() }
        assertEquals(7, seven.count)
        assertEquals(7L, seven.revision)
        assertEquals(seven, seven.increment())
        assertEquals(6, seven.undo().count)
        assertEquals(8L, seven.undo().revision)
    }

    @Test fun `reset establishes a new session while preserving the selected mode`() {
        val previous = LapCounter("sai", 4, "old", 4, 100)
        val reset = previous.reset("new", 200)
        assertEquals("sai", reset.mode)
        assertEquals(0, reset.count)
        assertEquals(0L, reset.revision)
        assertEquals("new", reset.sessionId)
        assertTrue(isNewerLap(reset, previous))
        assertFalse(isNewerLap(previous, reset))
    }

    @Test fun `late increment cannot reverse a newer undo or cross modes`() {
        val increment = LapCounter("tawaf", 5, "same", 5, 100)
        val undo = increment.undo()
        assertTrue(isNewerLap(undo, increment))
        assertFalse(isNewerLap(increment, undo))
        assertFalse(isNewerLap(undo, undo))
        assertFalse(isNewerLap(LapCounter("sai", 2, "another", 2, 200), undo))
        assertTrue(isNewerLap(increment, null))
    }

    @Test fun `clock correction cannot make an explicitly reset session older`() {
        val previous = LapCounter("tawaf", 4, "before", 4, 1000)
        val reset = previous.reset("after", 900)
        assertTrue(reset.startedAt > previous.startedAt)
        assertTrue(isNewerLap(reset, previous))
        assertFalse(isNewerLap(previous, reset))
    }

    @Test fun `corrupt persisted counter is rejected`() {
        for (count in listOf(-1, 8)) assertThrows(IllegalArgumentException::class.java) {
            LapCounter("tawaf", count, "valid")
        }
        assertThrows(IllegalArgumentException::class.java) { LapCounter("unknown") }
        assertThrows(IllegalArgumentException::class.java) { LapCounter("sai", sessionId = "") }
    }
}
