package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the campus-scale geo maths. These are the calculations that decide
 * which way the arrow points and how far away a building is, and a sign error
 * in a bearing is almost impossible to spot by eye on a phone.
 */
class GeoTest {

    // A rough campus-sized area for the tests. Values are arbitrary but
    // realistic: ~24.5N is a plausible Saudi latitude.
    private val origin = LatLng(24.500000, 39.500000)

    @Test
    fun `distance to itself is zero`() {
        assertEquals(0.0, distanceMeters(origin, origin), 1e-6)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val north = LatLng(origin.latitude + 1.0, origin.longitude)
        val d = distanceMeters(origin, north)
        // 111.19 km per degree of latitude; allow a small tolerance.
        assertEquals(111_195.0, d, 200.0)
    }

    @Test
    fun `distance is symmetric`() {
        val other = LatLng(24.502000, 39.503000)
        assertEquals(
            distanceMeters(origin, other),
            distanceMeters(other, origin),
            1e-6
        )
    }

    @Test
    fun `campus scale distance is plausible`() {
        // ~0.001 degrees of latitude is roughly 111 m.
        val north = LatLng(origin.latitude + 0.001, origin.longitude)
        assertEquals(111.0, distanceMeters(origin, north), 2.0)
    }

    @Test
    fun `bearing due north is zero`() {
        val north = LatLng(origin.latitude + 0.01, origin.longitude)
        assertEquals(0.0, bearingDegrees(origin, north), 0.5)
    }

    @Test
    fun `bearing due east is ninety`() {
        val east = LatLng(origin.latitude, origin.longitude + 0.01)
        assertEquals(90.0, bearingDegrees(origin, east), 0.5)
    }

    @Test
    fun `bearing due south is one eighty`() {
        val south = LatLng(origin.latitude - 0.01, origin.longitude)
        assertEquals(180.0, bearingDegrees(origin, south), 0.5)
    }

    @Test
    fun `bearing due west is two seventy`() {
        val west = LatLng(origin.latitude, origin.longitude - 0.01)
        assertEquals(270.0, bearingDegrees(origin, west), 0.5)
    }

    @Test
    fun `bearing is always normalised`() {
        val points = listOf(
            LatLng(24.51, 39.51),
            LatLng(24.49, 39.51),
            LatLng(24.49, 39.49),
            LatLng(24.51, 39.49)
        )
        for (p in points) {
            val b = bearingDegrees(origin, p)
            assertTrue("bearing $b out of range", b >= 0.0 && b < 360.0)
        }
    }

    @Test
    fun `relative bearing takes the short way round`() {
        // Facing 350, target 10 -> 20 degrees RIGHT, not -340.
        assertEquals(20.0, relativeBearing(350.0, 10.0), 1e-6)
        // Facing 10, target 350 -> 20 degrees LEFT.
        assertEquals(-20.0, relativeBearing(10.0, 350.0), 1e-6)
    }

    @Test
    fun `relative bearing stays within half turn`() {
        for (from in 0 until 360 step 17) {
            for (to in 0 until 360 step 23) {
                val r = relativeBearing(from.toDouble(), to.toDouble())
                assertTrue("relative $r out of range", r > -180.0 && r <= 180.0)
            }
        }
    }

    @Test
    fun `local metres east and north have the right signs`() {
        val northEast = LatLng(origin.latitude + 0.001, origin.longitude + 0.001)
        val (east, north) = toLocalMeters(origin, northEast)
        assertTrue("east should be positive", east > 0)
        assertTrue("north should be positive", north > 0)

        val southWest = LatLng(origin.latitude - 0.001, origin.longitude - 0.001)
        val (e2, n2) = toLocalMeters(origin, southWest)
        assertTrue("east should be negative", e2 < 0)
        assertTrue("north should be negative", n2 < 0)
    }

    @Test
    fun `local metres roughly agree with great-circle distance`() {
        val p = LatLng(origin.latitude + 0.0012, origin.longitude + 0.0009)
        val (east, north) = toLocalMeters(origin, p)
        val planar = kotlin.math.sqrt(east * east + north * north)
        assertEquals(distanceMeters(origin, p), planar, 1.0)
    }

    @Test
    fun `null island is not a valid fix`() {
        // A common failure mode: the provider returns 0,0 before a real fix.
        assertFalse(LatLng(0.0, 0.0).isValid)
        assertTrue(origin.isValid)
        assertFalse(LatLng(91.0, 0.0).isValid)
        assertFalse(LatLng(0.0, 181.0).isValid)
    }

    @Test
    fun `averaging favours the more accurate fix`() {
        val precise = GpsFix(LatLng(24.500000, 39.500000), 2f, 0L)
        val sloppy = GpsFix(LatLng(24.501000, 39.501000), 20f, 0L)

        val avg = averageFixes(listOf(precise, sloppy))!!
        // Weighting is 1/acc^2, so the 2m fix dominates by 100x.
        assertEquals(24.500000, avg.latitude, 0.00005)
        assertEquals(39.500000, avg.longitude, 0.00005)
    }

    @Test
    fun `averaging rejects unusable input`() {
        assertNull(averageFixes(emptyList()))
        assertNull(averageFixes(listOf(GpsFix(LatLng(0.0, 0.0), 5f, 0L))))
        assertNull(averageFixes(listOf(GpsFix(LatLng(24.5, 39.5), 0f, 0L))))
    }

    @Test
    fun `distance formatting switches to km`() {
        assertEquals("12 m", formatDistance(12.4))
        assertEquals("450 m", formatDistance(450.0))
        assertEquals("1.2 km", formatDistance(1200.0))
    }

    @Test
    fun `walk time is never zero`() {
        assertEquals(1, walkMinutes(1.0))
        assertTrue(walkMinutes(600.0) >= 7)
    }

    @Test
    fun `compass points map correctly`() {
        assertEquals("N", compassPoint(0.0))
        assertEquals("E", compassPoint(90.0))
        assertEquals("S", compassPoint(180.0))
        assertEquals("W", compassPoint(270.0))
        assertEquals("N", compassPoint(359.0))
        assertEquals("NE", compassPoint(45.0))
    }

    @Test
    fun `turn instructions use a wide dead-ahead band`() {
        // Wide band matters: GPS heading is noisy and a narrow one makes the
        // instruction flicker while walking straight.
        assertEquals("Straight ahead", turnInstruction(0.0))
        assertEquals("Straight ahead", turnInstruction(14.0))
        assertEquals("Slightly right", turnInstruction(30.0))
        assertEquals("Slightly left", turnInstruction(-30.0))
        assertEquals("Turn right", turnInstruction(90.0))
        assertEquals("Turn left", turnInstruction(-90.0))
        assertEquals("Turn around", turnInstruction(170.0))
    }

    @Test
    fun `ranking sorts nearest first and survives no fix`() {
        val near = Landmark("a", "Near", LandmarkCategory.OTHER, LatLng(24.5005, 39.5000))
        val far = Landmark("b", "Far", LandmarkCategory.OTHER, LatLng(24.5100, 39.5000))

        val ranked = rankByDistance(listOf(far, near), origin)
        assertEquals("Near", ranked.first().landmark.name)
        assertTrue(ranked[0].distanceMeters < ranked[1].distanceMeters)

        // With no fix the list must still be browsable, not empty.
        val unranked = rankByDistance(listOf(far, near), LatLng(0.0, 0.0))
        assertEquals(2, unranked.size)
    }
}
