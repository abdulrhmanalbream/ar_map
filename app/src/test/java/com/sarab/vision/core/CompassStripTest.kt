package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compass ribbon.
 *
 * A sign error here sends every tick the wrong way while still looking like a
 * working compass, so the directions are pinned down explicitly.
 */
class CompassStripTest {

    @Test
    fun `facing north puts north at the centre`() {
        val ticks = compassTicks(headingDeg = 0.0)
        val north = ticks.first { it.bearingDeg == 0.0 }

        assertEquals(0f, north.x, 0.01f)
        assertTrue(north.isCardinal)
        assertEquals("ش", north.label)
    }

    @Test
    fun `turning right slides the ticks left`() {
        // Face north-east: north is now 45 degrees to the LEFT, so it should
        // sit at negative x. Getting this backwards is the classic bug.
        val ticks = compassTicks(headingDeg = 45.0)
        val north = ticks.first { it.bearingDeg == 0.0 }

        assertTrue("north should be left of centre, was ${north.x}", north.x < 0f)

        val east = ticks.first { it.bearingDeg == 90.0 }
        assertTrue("east should be right of centre, was ${east.x}", east.x > 0f)
    }

    @Test
    fun `every visible tick stays inside the strip`() {
        for (heading in 0 until 360 step 13) {
            for (tick in compassTicks(heading.toDouble())) {
                assertTrue("x=${tick.x} outside strip", tick.x in -1f..1f)
            }
        }
    }

    @Test
    fun `the strip wraps cleanly across north`() {
        // Facing 350: north (0) is 10 degrees to the right, and west-north-west
        // is to the left. Naive subtraction breaks exactly here.
        val ticks = compassTicks(headingDeg = 350.0)
        val north = ticks.first { it.bearingDeg == 0.0 }

        assertTrue("north should be slightly right, was ${north.x}", north.x > 0f)
        assertTrue("north should be near centre, was ${north.x}", north.x < 0.3f)
    }

    @Test
    fun `all eight cardinals are labelled somewhere on the circle`() {
        val seen = mutableSetOf<String>()
        for (heading in 0 until 360 step 20) {
            compassTicks(heading.toDouble())
                .filter { it.isCardinal }
                .forEach { seen.add(it.label) }
        }
        assertEquals(8, seen.size)
        assertTrue(seen.containsAll(listOf("ش", "ق", "ج", "غ")))
    }

    @Test
    fun `target dead ahead sits at the centre`() {
        val t = compassTarget(headingDeg = 90.0, targetBearingDeg = 90.0, distanceMeters = 100.0)
        assertTrue(t.visible)
        assertEquals(0f, t.x, 0.01f)
    }

    @Test
    fun `target to the right has positive x`() {
        val t = compassTarget(headingDeg = 0.0, targetBearingDeg = 40.0, distanceMeters = 100.0)
        assertTrue(t.visible)
        assertTrue("expected positive x, was ${t.x}", t.x > 0f)
    }

    @Test
    fun `target behind the user is off screen with a direction hint`() {
        val right = compassTarget(headingDeg = 0.0, targetBearingDeg = 120.0, distanceMeters = 50.0)
        assertFalse(right.visible)
        assertEquals(1, right.offScreenDirection)
        assertEquals("should park at the edge", 1f, right.x, 0.001f)

        val left = compassTarget(headingDeg = 0.0, targetBearingDeg = 240.0, distanceMeters = 50.0)
        assertFalse(left.visible)
        assertEquals(-1, left.offScreenDirection)
        assertEquals(-1f, left.x, 0.001f)
    }

    @Test
    fun `bikes may use both roads and pedestrian paths`() {
        // The behaviour the user called out explicitly.
        assertTrue(
            "bike on a pedestrian path",
            TravelMode.BIKE.canUse(allowsFoot = true, allowsBike = true, allowsCar = false)
        )
        assertTrue(
            "bike on a road",
            TravelMode.BIKE.canUse(allowsFoot = false, allowsBike = true, allowsCar = true)
        )
    }

    @Test
    fun `cars are kept off pedestrian-only paths`() {
        assertFalse(
            TravelMode.CAR.canUse(allowsFoot = true, allowsBike = true, allowsCar = false)
        )
        assertTrue(
            TravelMode.CAR.canUse(allowsFoot = false, allowsBike = false, allowsCar = true)
        )
    }

    @Test
    fun `travel time reflects the mode`() {
        val distance = 1000.0
        val walk = travelMinutes(distance, TravelMode.WALK)
        val bike = travelMinutes(distance, TravelMode.BIKE)
        val car = travelMinutes(distance, TravelMode.CAR)

        assertTrue("walking should be slowest", walk > bike)
        assertTrue("cycling should be slower than driving", bike > car)
        assertTrue("walking 1km should be over 10 minutes", walk >= 12)
    }

    @Test
    fun `travel time is never zero`() {
        assertEquals(1, travelMinutes(1.0, TravelMode.CAR))
    }
}
