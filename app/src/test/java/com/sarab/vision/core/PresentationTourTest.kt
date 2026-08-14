package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scripted investor demo.
 *
 * Verified here rather than by watching a phone for a minute: the whole point
 * of this feature is that it cannot fail in front of an audience, so its
 * timing and sequencing must be provable rather than hoped for.
 */
class PresentationTourTest {

    private fun start() = TourState(stepIndex = 0, elapsedInStepMs = 0, running = true)

    @Test
    fun `the tour has a script and a sane length`() {
        assertTrue("expected several beats", PresentationTour.STEPS.size >= 8)

        val total = PresentationTour.totalDurationMs
        // Long enough to explain, short enough to hold attention.
        assertTrue("tour too short: ${total}ms", total >= 40_000)
        assertTrue("tour too long: ${total}ms", total <= 90_000)
    }

    @Test
    fun `every beat has copy and a positive duration`() {
        PresentationTour.STEPS.forEach { step ->
            assertTrue("a beat has no title", step.titleAr.isNotBlank())
            assertTrue("a beat has no caption", step.captionAr.isNotBlank())
            assertTrue("a beat has no duration", step.durationMs > 0)
        }
    }

    @Test
    fun `a paused tour does not advance`() {
        val paused = start().copy(running = false)
        assertEquals(paused, advanceTour(paused, 5_000))
    }

    @Test
    fun `time within a beat accumulates`() {
        val state = advanceTour(start(), 1_000)
        assertEquals(0, state.stepIndex)
        assertEquals(1_000, state.elapsedInStepMs)
    }

    @Test
    fun `the tour moves to the next beat when one ends`() {
        val first = PresentationTour.STEPS[0]
        val state = advanceTour(start(), first.durationMs + 10)

        assertEquals(1, state.stepIndex)
        assertEquals(0, state.elapsedInStepMs)
        assertTrue(state.running)
    }

    @Test
    fun `the tour stops cleanly at the end instead of running past it`() {
        var state = start()
        // Step generously through the whole script.
        repeat(400) { state = advanceTour(state, 500) }

        assertTrue("should have finished", state.finished)
        assertFalse("should not still be running", state.running)
        // A finished tour must be inert, not crash on further ticks.
        assertEquals(state, advanceTour(state, 1_000))
    }

    @Test
    fun `progress runs from zero to one`() {
        assertEquals(0f, start().overallProgress, 0.001f)

        var state = start()
        repeat(400) { state = advanceTour(state, 500) }
        assertEquals(1f, state.overallProgress, 0.001f)
    }

    @Test
    fun `progress only ever increases`() {
        var state = start()
        var previous = 0f
        repeat(200) {
            state = advanceTour(state, 400)
            assertTrue("progress went backwards", state.overallProgress >= previous - 0.0001f)
            previous = state.overallProgress
        }
    }

    @Test
    fun `walking only happens during a walk beat`() {
        val walkIndex = PresentationTour.STEPS.indexOfFirst { it.action is TourAction.Walk }
        assertTrue("the script needs a walking beat", walkIndex >= 0)

        val walking = TourState(walkIndex, 0, true)
        assertTrue(tourWalkDistance(walking, 1_000) > 0)

        val holdIndex = PresentationTour.STEPS.indexOfFirst { it.action is TourAction.Hold }
        val holding = TourState(holdIndex, 0, true)
        assertEquals(0.0, tourWalkDistance(holding, 1_000), 0.001)
    }

    @Test
    fun `walking speed is proportional to elapsed time`() {
        val walkIndex = PresentationTour.STEPS.indexOfFirst { it.action is TourAction.Walk }
        val state = TourState(walkIndex, 0, true)

        val oneSecond = tourWalkDistance(state, 1_000)
        val twoSeconds = tourWalkDistance(state, 2_000)
        assertEquals(oneSecond * 2, twoSeconds, 0.001)
    }

    @Test
    fun `turning only happens during a turn beat`() {
        val turnIndex = PresentationTour.STEPS.indexOfFirst { it.action is TourAction.Turn }
        assertTrue("the script needs a turning beat", turnIndex >= 0)
        assertTrue(tourTurnDegrees(TourState(turnIndex, 0, true), 1_000) > 0)

        val holdIndex = PresentationTour.STEPS.indexOfFirst { it.action is TourAction.Hold }
        assertEquals(0.0, tourTurnDegrees(TourState(holdIndex, 0, true), 1_000), 0.001)
    }

    @Test
    fun `the script builds the argument in the right order`() {
        val actions = PresentationTour.STEPS.map { it.action }

        val select = actions.indexOfFirst { it is TourAction.SelectTarget }
        val walk = actions.indexOfFirst { it is TourAction.Walk }
        val arrive = actions.indexOfLast { it is TourAction.JumpNear }
        val bike = actions.indexOfFirst { it is TourAction.SetMode && it.mode == TravelMode.BIKE }
        val car = actions.indexOfFirst { it is TourAction.SetMode && it.mode == TravelMode.CAR }

        assertTrue("must choose a destination first", select in 0 until walk)
        assertTrue("must walk before arriving", walk < arrive)
        assertTrue("modes are the closing argument", arrive < bike)
        assertTrue("bike before car", bike < car)
    }

    @Test
    fun `the ambiguity moment is in the script`() {
        // The differentiator: the app admitting GPS cannot separate two close
        // buildings. If this beat is ever dropped the demo loses its point.
        assertTrue(
            PresentationTour.STEPS.any {
                it.captionAr.contains("دقة GPS") || it.titleAr.contains("نسأل")
            }
        )
    }

    @Test
    fun `the offline claim is made explicitly`() {
        assertTrue(
            PresentationTour.STEPS.any {
                it.titleAr.contains("إنترنت") || it.captionAr.contains("إنترنت")
            }
        )
    }

    @Test
    fun `an out of range step index is handled`() {
        val past = TourState(999, 0, true)
        assertTrue(past.finished)
        assertEquals(null, past.step)
        assertNotNull(advanceTour(past, 100))
    }
}
