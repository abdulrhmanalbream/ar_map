package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guidance mode selection, covering the campus's two distinct problems:
 * landmarks hundreds of metres away, and landmarks close enough together
 * that GPS cannot tell them apart.
 */
class GuidanceTest {

    private val origin = LatLng(24.500000, 39.500000)

    /** Builds a landmark [northMeters] north and [eastMeters] east of origin. */
    private fun at(name: String, northMeters: Double, eastMeters: Double): Landmark {
        // ~111,195 m per degree of latitude; longitude scaled by cos(lat).
        val dLat = northMeters / 111_195.0
        val dLon = eastMeters / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return Landmark(
            id = name,
            name = name,
            category = LandmarkCategory.FACULTY,
            position = LatLng(origin.latitude + dLat, origin.longitude + dLon)
        )
    }

    @Test
    fun `no fix yields NoFix`() {
        val target = at("A", 100.0, 0.0)
        val mode = guidanceFor(LatLng(0.0, 0.0), 0.0, target, emptyList())
        assertTrue(mode is GuidanceMode.NoFix)
    }

    @Test
    fun `far landmark uses compass mode`() {
        val target = at("Stadium", 250.0, 0.0)
        val mode = guidanceFor(origin, 0.0, target, emptyList())

        assertTrue("expected Compass, got $mode", mode is GuidanceMode.Compass)
        mode as GuidanceMode.Compass
        assertEquals(250.0, mode.distanceMeters, 3.0)
        // Due north.
        assertEquals(0.0, mode.bearingDegrees, 1.0)
    }

    @Test
    fun `compass relative bearing reflects where the user faces`() {
        val target = at("East building", 0.0, 200.0)

        // Facing north, target is due east -> 90 degrees to the right.
        val facingNorth = guidanceFor(origin, 0.0, target, emptyList())
        assertTrue(facingNorth is GuidanceMode.Compass)
        assertEquals(90.0, (facingNorth as GuidanceMode.Compass).relativeDegrees, 1.5)

        // Facing east, the target is straight ahead.
        val facingEast = guidanceFor(origin, 90.0, target, emptyList())
        assertEquals(0.0, (facingEast as GuidanceMode.Compass).relativeDegrees, 1.5)
    }

    @Test
    fun `mid range switches to AR approach`() {
        val target = at("Nearby", 20.0, 0.0)
        val mode = guidanceFor(origin, 0.0, target, emptyList())
        assertTrue("expected ArApproach, got $mode", mode is GuidanceMode.ArApproach)
    }

    @Test
    fun `isolated landmark reports arrival`() {
        val target = at("Alone", 5.0, 0.0)
        val far = at("Far away", 300.0, 0.0)

        val mode = guidanceFor(origin, 0.0, target, listOf(far))
        assertTrue("expected Arrived, got $mode", mode is GuidanceMode.Arrived)
    }

    @Test
    fun `two close buildings are reported as ambiguous`() {
        // The dangerous case: two faculties 15m apart, user standing between
        // them. GPS error is comparable to the gap, so the app must NOT
        // confidently claim which one this is.
        val computing = at("كلية الحاسب الآلي", 5.0, 0.0)
        val sharia = at("كلية الشريعة", 5.0, 15.0)

        val mode = guidanceFor(origin, 0.0, computing, listOf(sharia))

        assertTrue("expected Ambiguous, got $mode", mode is GuidanceMode.Ambiguous)
        mode as GuidanceMode.Ambiguous
        assertEquals(2, mode.candidates.size)
        assertTrue(mode.candidates.any { it.name.contains("الحاسب") })
        assertTrue(mode.candidates.any { it.name.contains("الشريعة") })
    }

    @Test
    fun `well separated buildings are not ambiguous`() {
        val a = at("A", 5.0, 0.0)
        val b = at("B", 5.0, 60.0) // 60m apart: comfortably distinguishable

        val mode = guidanceFor(origin, 0.0, a, listOf(b))
        assertTrue("expected Arrived, got $mode", mode is GuidanceMode.Arrived)
    }

    @Test
    fun `a nearby building the user is not close to does not cause ambiguity`() {
        // B is close to A, but the user is nowhere near either -- this should
        // be compass guidance, not an ambiguity warning.
        val a = at("A", 300.0, 0.0)
        val b = at("B", 310.0, 0.0)

        val mode = guidanceFor(origin, 0.0, a, listOf(b))
        assertTrue("expected Compass, got $mode", mode is GuidanceMode.Compass)
    }

    @Test
    fun `ambiguous pairs are found for surveying`() {
        val a = at("A", 0.0, 0.0)
        val b = at("B", 0.0, 10.0)   // 10m from A -> ambiguous
        val c = at("C", 0.0, 200.0)  // far from both

        val pairs = findAmbiguousPairs(listOf(a, b, c))
        assertEquals(1, pairs.size)
        assertTrue(pairs[0].first.name == "A" && pairs[0].second.name == "B")
    }

    @Test
    fun `no ambiguous pairs when everything is spread out`() {
        val landmarks = listOf(
            at("A", 0.0, 0.0),
            at("B", 0.0, 100.0),
            at("C", 200.0, 0.0),
            at("D", 300.0, 300.0)
        )
        assertTrue(findAmbiguousPairs(landmarks).isEmpty())
    }

    @Test
    fun `arabic instructions match the mode`() {
        val target = at("الملعب", 250.0, 0.0)

        // Facing away from the target -> should say turn around.
        val behind = guidanceFor(origin, 180.0, target, emptyList())
        assertTrue(instructionAr(behind, "الملعب").contains("استدر"))

        // Facing the target -> walk forward.
        val ahead = guidanceFor(origin, 0.0, target, emptyList())
        assertTrue(instructionAr(ahead, "الملعب").contains("امشِ"))

        assertTrue(
            instructionAr(GuidanceMode.NoFix, "الملعب").contains("موقعك")
        )
        assertTrue(
            instructionAr(GuidanceMode.Arrived(3.0), "الملعب").contains("وصلت")
        )
        assertTrue(
            instructionAr(GuidanceMode.Ambiguous(3.0, emptyList()), "x").contains("تأكد")
        )
    }

    @Test
    fun `arabic distance switches to kilometres`() {
        assertEquals("250 م", formatDistanceAr(250.4))
        assertEquals("1.5 كم", formatDistanceAr(1500.0))
    }
}
