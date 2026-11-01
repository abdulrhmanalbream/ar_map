package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn-by-turn over a routed polyline. The cases are the ones a walker would
 * notice: a real corner must become one instruction, a kerb jog must not, and
 * progress must not jump around on a route that passes near itself.
 */
class TurnByTurnTest {

    private val origin = LatLng(24.480000, 39.560000)

    private fun at(northM: Double, eastM: Double): LatLng {
        val dLat = northM / 111_195.0
        val dLon = eastM / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return LatLng(origin.latitude + dLat, origin.longitude + dLon)
    }

    private val lShape = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 80.0))

    private fun types(points: List<LatLng>) = routeManeuvers(points).map { it.type }

    @Test
    fun `an L-shaped route is one right turn between depart and arrive`() {
        val m = routeManeuvers(lShape)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.RIGHT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals(90.0, m[1].turnDegrees, 1.0)
        assertEquals(100.0, m[1].legMeters, 1.0)
        assertEquals(80.0, m[2].legMeters, 1.0)
        assertEquals(180.0, m[2].alongMeters, 1.5)
    }

    @Test
    fun `turning the other way is a left turn`() {
        assertEquals(ManeuverType.LEFT, types(listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, -80.0)))[1])
    }

    @Test
    fun `turn strength is classified by angle`() {
        assertNull(classifyTurn(10.0))
        assertNull(classifyTurn(-19.9))
        assertEquals(ManeuverType.SLIGHT_RIGHT, classifyTurn(30.0))
        assertEquals(ManeuverType.SLIGHT_LEFT, classifyTurn(-30.0))
        assertEquals(ManeuverType.RIGHT, classifyTurn(90.0))
        assertEquals(ManeuverType.LEFT, classifyTurn(-135.0))
        assertEquals(ManeuverType.SHARP_RIGHT, classifyTurn(150.0))
        assertEquals(ManeuverType.SHARP_LEFT, classifyTurn(-150.0))
        assertEquals(ManeuverType.U_TURN, classifyTurn(175.0))
    }

    @Test
    fun `a slight bend is a slight turn`() {
        // Due north, then 30 degrees east of north.
        val bend = listOf(at(0.0, 0.0), at(60.0, 0.0), at(60.0 + 50.0 * 0.866, 25.0))
        assertEquals(ManeuverType.SLIGHT_RIGHT, types(bend)[1])
    }

    @Test
    fun `a kerb jog of a few metres cancels out instead of right-then-left`() {
        val jog = listOf(at(0.0, 0.0), at(50.0, 0.0), at(50.0, 3.0), at(100.0, 3.0))
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(jog))
    }

    @Test
    fun `a corner drawn as several short segments is one turn`() {
        val rounded = listOf(at(0.0, 0.0), at(50.0, 0.0), at(53.0, 1.2), at(55.0, 4.0), at(56.0, 7.0), at(56.0, 60.0))
        val m = routeManeuvers(rounded)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.RIGHT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals(90.0, m[1].turnDegrees, 3.0)
    }

    @Test
    fun `small wiggles along a straight path give no instructions`() {
        val wiggly = (0..10).map { at(it * 20.0, if (it % 2 == 0) 0.0 else 0.4) }
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), types(wiggly))
    }

    @Test
    fun `doubling back is a U-turn`() {
        val back = listOf(at(0.0, 0.0), at(50.0, 0.0), at(20.0, 0.0))
        assertEquals(ManeuverType.U_TURN, types(back)[1])
    }

    @Test
    fun `degenerate routes produce no instructions`() {
        assertTrue(routeManeuvers(emptyList()).isEmpty())
        assertTrue(routeManeuvers(listOf(at(0.0, 0.0))).isEmpty())
        assertTrue(routeManeuvers(listOf(at(0.0, 0.0), at(0.0, 0.0))).isEmpty())
    }

    @Test
    fun `a turn beside a landmark is named after it`() {
        val shariah = Landmark("lm", "كلية الشريعة", LandmarkCategory.FACULTY, at(110.0, 5.0))
        val far = Landmark("far", "بعيد", LandmarkCategory.FACULTY, at(300.0, 300.0))
        val m = routeManeuvers(lShape, listOf(far, shariah))
        assertEquals("كلية الشريعة", m[1].landmarkName)
        assertNull(m.first().landmarkName)
    }

    @Test
    fun `progress projects the user onto the route`() {
        val p = routeProgress(lShape, at(50.0, 2.0))!!
        assertEquals(50.0, p.alongMeters, 1.0)
        assertEquals(2.0, p.offRouteMeters, 0.5)
        assertEquals(130.0, p.remainingMeters, 1.5)

        val later = routeProgress(lShape, at(102.0, 40.0))!!
        assertEquals(140.0, later.alongMeters, 1.5)
        assertEquals(40.0, later.remainingMeters, 1.5)
    }

    @Test
    fun `progress does not snap back to an earlier leg that passes nearby`() {
        // Out and back along two parallel legs 10 m apart.
        val hairpin = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 10.0), at(0.0, 10.0))
        val midway = at(50.0, 5.0)
        // Equidistant from both legs; having already walked 150 m, the user is on the way back.
        val p = routeProgress(hairpin, midway, previousAlongMeters = 150.0)!!
        assertEquals(160.0, p.alongMeters, 2.0)
    }

    @Test
    fun `the next manoeuvre and its distance follow progress`() {
        val m = routeManeuvers(lShape)
        val next = upcomingManeuver(m, 60.0)!!
        assertEquals(ManeuverType.RIGHT, next.maneuver.type)
        assertEquals(40.0, next.distanceMeters, 1.0)
        assertEquals(ManeuverType.ARRIVE, next.then?.type)

        val afterCorner = upcomingManeuver(m, 120.0)!!
        assertEquals(ManeuverType.ARRIVE, afterCorner.maneuver.type)
        assertEquals(60.0, afterCorner.distanceMeters, 1.5)
    }

    @Test
    fun `standing on the corner still shows that turn`() {
        val m = routeManeuvers(lShape)
        assertEquals(ManeuverType.RIGHT, upcomingManeuver(m, m[1].alongMeters - 0.5)!!.maneuver.type)
    }

    @Test
    fun `one stray fix does not reroute but several do`() {
        var state = OffRouteState()
        var reroute: Boolean
        for (i in 1 until OFF_ROUTE_FIXES) {
            offRouteStep(state, 30.0).let { state = it.first; reroute = it.second }
            assertFalse(reroute)
        }
        offRouteStep(state, 30.0).let { state = it.first; reroute = it.second }
        assertTrue(reroute)
        assertEquals(0, state.strikes)
    }

    @Test
    fun `coming back to the route clears the strikes`() {
        var state = offRouteStep(OffRouteState(), 30.0).first
        state = offRouteStep(state, 30.0).first
        state = offRouteStep(state, 5.0).first
        assertEquals(0, state.strikes)
    }

    @Test
    fun `a poor fix widens the off-route limit`() {
        val (state, reroute) = offRouteStep(OffRouteState(), 25.0, accuracyMeters = 25.0)
        assertFalse(reroute)
        assertEquals(0, state.strikes)
        assertEquals(1, offRouteStep(OffRouteState(), 25.0, accuracyMeters = 5.0).first.strikes)
    }

    @Test
    fun `instructions read naturally in Arabic`() {
        val m = routeManeuvers(lShape)
        assertEquals("بعد ٤٠ م انعطف يميناً", maneuverInstructionAr(m[1], 40.0))
        assertEquals("انعطف يميناً", maneuverInstructionAr(m[1], 6.0))
        assertEquals("امشِ مستقيماً ١٠٠ م", maneuverInstructionAr(m[0], 100.0))
        assertEquals("بعد ٦٠ م تصل إلى وجهتك", maneuverInstructionAr(m[2], 60.0))
        assertEquals("وصلت إلى وجهتك", maneuverInstructionAr(m[2], 3.0))
        val named = m[1].copy(landmarkName = "كلية الشريعة")
        assertEquals("بعد ٤٠ م انعطف يميناً عند كلية الشريعة", maneuverInstructionAr(named, 40.0))
    }

    @Test
    fun `distances round the way walkers judge them`() {
        assertEquals(45, roundedWalkMeters(43.0))
        assertEquals(5, roundedWalkMeters(1.0))
        assertEquals(120, roundedWalkMeters(123.0))
        assertEquals("١٢٠ م", formatTurnDistanceAr(123.0))
        assertEquals("١٫٢ كم", formatTurnDistanceAr(1234.0))
    }

    @Test
    fun `the steps list pairs each instruction with the walk after it`() {
        val steps = routeStepList(routeManeuvers(lShape))
        assertEquals(3, steps.size)
        assertEquals("امشِ مستقيماً", steps[0].instructionAr)
        assertEquals(100.0, steps[0].distanceMeters, 1.0)
        assertEquals("انعطف يميناً", steps[1].instructionAr)
        assertEquals(80.0, steps[1].distanceMeters, 1.0)
        assertEquals("وصلت إلى وجهتك", steps[2].instructionAr)
    }

    @Test
    fun `turns are announced ahead and again at the corner, once each`() {
        val m = routeManeuvers(lShape)
        assertNull(turnCueFor(upcomingManeuver(m, 20.0)))
        val prepare = turnCueFor(upcomingManeuver(m, 60.0))!!
        assertEquals(TurnCueStage.PREPARE, prepare.stage)
        val now = turnCueFor(upcomingManeuver(m, 92.0))!!
        assertEquals(TurnCueStage.NOW, now.stage)
        assertTrue(prepare.key != now.key)

        var state = TurnVoiceState()
        assertTrue(turnCueIsNew(state, prepare))
        state = state.after(prepare)
        assertFalse(turnCueIsNew(state, prepare))
        // The same stage a few metres later is still the same announcement.
        assertFalse(turnCueIsNew(state, turnCueFor(upcomingManeuver(m, 70.0))))
        assertTrue(turnCueIsNew(state, now))
    }

    @Test
    fun `arrival is never a turn cue`() {
        val m = routeManeuvers(lShape)
        assertNull(turnCueFor(upcomingManeuver(m, 175.0)))
    }

    @Test
    fun `the compass follows the route instead of pointing through buildings`() {
        // The door is north-east; the route goes north first.
        val door = Compass(distanceMeters = 128.0, bearingDegrees = bearingDegrees(at(0.0, 0.0), at(100.0, 80.0)))
        val progress = routeProgress(lShape, at(0.0, 0.0))
        val routed = routedGuidance(door, at(0.0, 0.0), 0.0, lShape, progress) as GuidanceMode.Compass
        assertEquals(0.0, routed.bearingDegrees, 1.0)
        assertEquals(0.0, routed.relativeDegrees, 1.0)
        assertEquals(180.0, routed.distanceMeters, 1.5)
    }

    @Test
    fun `other guidance modes pass through unchanged`() {
        val arrived = GuidanceMode.Arrived(3.0)
        assertEquals(arrived, routedGuidance(arrived, at(0.0, 0.0), 0.0, lShape, routeProgress(lShape, at(0.0, 0.0))))
    }

    @Test
    fun `the remaining route starts where the user is`() {
        val ahead = remainingRoute(lShape, 50.0)
        assertEquals(3, ahead.size)
        assertEquals(0.0, distanceMeters(ahead.first(), at(50.0, 0.0)), 0.5)
        assertEquals(lShape.last(), ahead.last())

        val pastCorner = remainingRoute(lShape, 140.0)
        assertEquals(2, pastCorner.size)
    }

    @Test
    fun `glasses wording is short`() {
        assertEquals("بعد ٤٠ م يميناً", glassesTurnTextAr(ManeuverType.RIGHT, 40.0))
        assertEquals("انعطف يساراً قليلاً", glassesTurnTextAr(ManeuverType.SLIGHT_LEFT, 5.0))
        assertEquals("استدر للخلف", glassesTurnTextAr(ManeuverType.U_TURN, 30.0))
    }

    private fun Compass(distanceMeters: Double, bearingDegrees: Double) =
        GuidanceMode.Compass(distanceMeters, bearingDegrees, relativeBearing(0.0, bearingDegrees))
}
