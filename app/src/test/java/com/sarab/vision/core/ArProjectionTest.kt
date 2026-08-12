package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screen placement for distant landmarks.
 *
 * A sign error here points the user at the wrong side of the campus while
 * looking completely plausible, so the directions are pinned down explicitly.
 */
class ArProjectionTest {

    private val origin = LatLng(24.500000, 39.500000)

    private fun at(northMeters: Double, eastMeters: Double): LatLng {
        val dLat = northMeters / 111_195.0
        val dLon = eastMeters / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return LatLng(origin.latitude + dLat, origin.longitude + dLon)
    }

    @Test
    fun `landmark straight ahead lands in the centre`() {
        val north = at(250.0, 0.0)
        val p = projectLandmark(origin, userHeadingDeg = 0.0, pitchDeg = 0.0, target = north)!!

        assertTrue(p.visible)
        assertEquals(0f, p.x, 0.02f)
        assertEquals(250.0, p.distanceMeters, 3.0)
    }

    @Test
    fun `landmark to the right has positive x`() {
        // 20 degrees right of north, well within a 65 degree field of view.
        val target = at(250.0, 91.0)
        val p = projectLandmark(origin, 0.0, 0.0, target)!!

        assertTrue("should be visible", p.visible)
        assertTrue("x should be positive, was ${p.x}", p.x > 0.1f)
    }

    @Test
    fun `landmark to the left has negative x`() {
        val target = at(250.0, -91.0)
        val p = projectLandmark(origin, 0.0, 0.0, target)!!

        assertTrue(p.visible)
        assertTrue("x should be negative, was ${p.x}", p.x < -0.1f)
    }

    @Test
    fun `landmark behind the user is not visible`() {
        val south = at(-250.0, 0.0)
        val p = projectLandmark(origin, userHeadingDeg = 0.0, pitchDeg = 0.0, target = south)!!
        assertFalse("a landmark behind you must not be drawn", p.visible)
    }

    @Test
    fun `off screen direction points the shorter way round`() {
        // Target due east while facing north: turn right.
        val east = at(0.0, 250.0)
        val right = projectLandmark(origin, 0.0, 0.0, east)!!
        assertFalse(right.visible)
        assertEquals(1, right.offScreenDirection)

        // Target due west while facing north: turn left.
        val west = at(0.0, -250.0)
        val left = projectLandmark(origin, 0.0, 0.0, west)!!
        assertFalse(left.visible)
        assertEquals(-1, left.offScreenDirection)
    }

    @Test
    fun `turning to face the landmark brings it into view`() {
        val east = at(0.0, 250.0)

        val facingNorth = projectLandmark(origin, 0.0, 0.0, east)!!
        assertFalse(facingNorth.visible)

        val facingEast = projectLandmark(origin, 90.0, 0.0, east)!!
        assertTrue("should be visible once facing it", facingEast.visible)
        assertEquals(0f, facingEast.x, 0.02f)
    }

    @Test
    fun `invalid positions are rejected`() {
        assertNull(projectLandmark(LatLng(0.0, 0.0), 0.0, 0.0, at(100.0, 0.0)))
        assertNull(projectLandmark(origin, 0.0, 0.0, LatLng(0.0, 0.0)))
    }

    @Test
    fun `tilting the device moves the marker vertically`() {
        val north = at(250.0, 0.0)

        val level = projectLandmark(origin, 0.0, 0.0, north)!!
        val tiltedUp = projectLandmark(origin, 0.0, 20.0, north)!!

        assertEquals(0f, level.y, 0.02f)
        // Tilting up should push a level landmark DOWN the screen.
        assertTrue("tilting up should lower the marker", tiltedUp.y < level.y)
    }

    @Test
    fun `marker shrinks with distance but stays legible`() {
        val near = markerScaleFor(10.0)
        val mid = markerScaleFor(120.0)
        val far = markerScaleFor(250.0)

        assertTrue("nearer must be larger", near > mid)
        assertTrue("mid must be larger than far", mid > far)
        // A 250m landmark must not collapse into an invisible dot.
        assertTrue("far marker too small: $far", far >= 0.45f)
    }

    @Test
    fun `ground path is capped for distant targets`() {
        // Close: draw the whole way.
        assertEquals(10.0, groundPathLength(10.0), 0.01)
        // Far: draw a short stub rather than 250m of invented terrain.
        assertEquals(12.0, groundPathLength(250.0), 0.01)
        assertEquals(12.0, groundPathLength(30.0), 0.01)
        assertEquals(0.0, groundPathLength(0.0), 0.01)
    }
}
