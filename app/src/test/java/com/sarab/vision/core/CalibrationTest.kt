package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start-up sequence.
 *
 * Tested rather than discovered by waving a phone at a wall: the failure mode
 * is either dismissing the hint while the app is still blind, or trapping the
 * user on a step that can never complete.
 */
class CalibrationTest {

    private fun input(
        tracking: Boolean = false,
        compassReliable: Boolean = true,
        tilt: Float = 0.9f,
        movedDegrees: Double = 0.0,
        elapsedMs: Long = 0L
    ) = CalibrationInput(tracking, compassReliable, tilt, movedDegrees, elapsedMs)

    @Test
    fun `starts by asking for a wave`() {
        val state = nextCalibrationState(CalibrationStep.WAVE, input())

        assertEquals(CalibrationStep.WAVE, state.step)
        assertTrue(state.titleAr.contains("حرّك"))
        assertTrue(state.bodyAr.isNotBlank())
    }

    @Test
    fun `does not advance while the phone is still`() {
        // The exact failure seen on device: a stationary phone never gets
        // ARCore tracking, so advancing would hide the hint while blind.
        val state = nextCalibrationState(
            CalibrationStep.WAVE,
            input(tracking = false, movedDegrees = 0.0, elapsedMs = 4_000)
        )
        assertEquals(CalibrationStep.WAVE, state.step)
    }

    @Test
    fun `does not advance on sweep alone if tracking has not started`() {
        val state = nextCalibrationState(
            CalibrationStep.WAVE,
            input(tracking = false, movedDegrees = 200.0, elapsedMs = 5_000)
        )
        assertEquals(CalibrationStep.WAVE, state.step)
    }

    @Test
    fun `advances once swept enough and tracking is established`() {
        val state = nextCalibrationState(
            CalibrationStep.WAVE,
            input(tracking = true, movedDegrees = 80.0, elapsedMs = 3_000)
        )
        assertEquals(CalibrationStep.RAISE, state.step)
        assertTrue(state.titleAr.contains("ارفع"))
    }

    @Test
    fun `never flashes past before the user can read it`() {
        // Everything ready instantly must still hold the step briefly.
        val state = nextCalibrationState(
            CalibrationStep.WAVE,
            input(tracking = true, movedDegrees = 200.0, elapsedMs = 200)
        )
        assertEquals(CalibrationStep.WAVE, state.step)
    }

    @Test
    fun `gives up rather than trapping the user`() {
        // Some rooms lack the texture ARCore needs. Blocking forever is worse
        // than proceeding.
        val state = nextCalibrationState(
            CalibrationStep.WAVE,
            input(tracking = false, compassReliable = false, elapsedMs = 13_000)
        )
        assertEquals(CalibrationStep.RAISE, state.step)
    }

    @Test
    fun `progress reflects the sweep`() {
        val none = nextCalibrationState(CalibrationStep.WAVE, input(movedDegrees = 0.0))
        val half = nextCalibrationState(CalibrationStep.WAVE, input(movedDegrees = 27.0))

        assertTrue("progress should grow with the sweep", half.progress > none.progress)
        assertTrue(half.progress <= 1f)
    }

    @Test
    fun `raise step completes when the phone is lifted`() {
        val stillFlat = nextCalibrationState(CalibrationStep.RAISE, input(tilt = 0.95f))
        assertEquals(CalibrationStep.RAISE, stillFlat.step)

        val raised = nextCalibrationState(CalibrationStep.RAISE, input(tilt = 0.2f))
        assertEquals(CalibrationStep.DONE, raised.step)
    }

    @Test
    fun `raise step also times out`() {
        val state = nextCalibrationState(
            CalibrationStep.RAISE,
            input(tilt = 0.99f, elapsedMs = 7_000)
        )
        assertEquals(CalibrationStep.DONE, state.step)
    }

    @Test
    fun `done stays done`() {
        val state = nextCalibrationState(CalibrationStep.DONE, input())
        assertEquals(CalibrationStep.DONE, state.step)
        assertEquals(1f, state.progress, 0.001f)
    }

    // ---- Sweep tracking --------------------------------------------------

    @Test
    fun `sweeping out and back still counts`() {
        // Comparing start to end would report zero for exactly the motion we
        // are asking the user to perform.
        val tracker = SweepTracker()
        listOf(0.0, 10.0, 20.0, 30.0, 20.0, 10.0, 0.0).forEach { tracker.update(it) }

        assertEquals(60.0, tracker.totalDegrees, 1.0)
    }

    @Test
    fun `sweep tracking handles the wrap across north`() {
        val tracker = SweepTracker()
        listOf(350.0, 355.0, 0.0, 5.0).forEach { tracker.update(it) }

        assertEquals("should be 15 degrees, not ~345", 15.0, tracker.totalDegrees, 1.0)
    }

    @Test
    fun `sweep tracking ignores sensor glitches`() {
        val tracker = SweepTracker()
        tracker.update(0.0)
        tracker.update(170.0) // implausible jump between samples
        assertEquals(0.0, tracker.totalDegrees, 0.001)
    }

    @Test
    fun `reset clears the sweep`() {
        val tracker = SweepTracker()
        tracker.update(0.0)
        tracker.update(30.0)
        tracker.reset()
        assertEquals(0.0, tracker.totalDegrees, 0.001)
    }
}
