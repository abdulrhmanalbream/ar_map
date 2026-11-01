package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken turns reuse the recorded Sulafat direction clips. */
class TurnSpeechTest {

    private val corner = LatLng(24.48, 39.56)

    private fun cue(type: ManeuverType, stage: TurnCueStage, distance: Double, landmark: String? = null) =
        TurnCue(Maneuver(type, corner, 100.0, 100.0, 90.0, landmark), stage, distance)

    @Test
    fun `ahead of a turn the distance comes first, then the recorded direction`() {
        val plan = turnSpeechPlan(cue(ManeuverType.RIGHT, TurnCueStage.PREPARE, 41.0))
        assertEquals(NavigationSpeechSegment.Text("بعد 40 متراً"), plan[0])
        assertEquals("nav_ar_right", (plan[1] as NavigationSpeechSegment.Clip).id)
        assertEquals(2, plan.size)
    }

    @Test
    fun `at the corner only the direction is spoken`() {
        val plan = turnSpeechPlan(cue(ManeuverType.LEFT, TurnCueStage.NOW, 6.0))
        assertEquals(listOf("nav_ar_left"), plan.map { (it as NavigationSpeechSegment.Clip).id })
    }

    @Test
    fun `a named corner adds the landmark`() {
        val plan = turnSpeechPlan(cue(ManeuverType.SLIGHT_RIGHT, TurnCueStage.NOW, 6.0, "كلية الشريعة"))
        assertEquals("nav_ar_slight_right", (plan[0] as NavigationSpeechSegment.Clip).id)
        assertEquals(NavigationSpeechSegment.Text("عند كلية الشريعة"), plan[1])
    }

    @Test
    fun `every turn type maps to an existing recorded clip`() {
        ManeuverType.entries.filter { it.isTurn }.forEach { type ->
            val clip = turnSpeechPlan(cue(type, TurnCueStage.NOW, 5.0))[0] as NavigationSpeechSegment.Clip
            assertTrue("$type -> ${clip.id}", clip.id in NAVIGATION_CLIP_IDS)
        }
    }

    @Test
    fun `depart and arrive are not turn speech`() {
        assertTrue(turnSpeechPlan(cue(ManeuverType.DEPART, TurnCueStage.NOW, 5.0)).isEmpty())
        assertTrue(turnSpeechPlan(cue(ManeuverType.ARRIVE, TurnCueStage.NOW, 5.0)).isEmpty())
    }
}
