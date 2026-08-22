package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Colouring the drawn path by what is actually on it.
 *
 * Yellow to continue, orange through a turn, red for stairs -- the vocabulary
 * road signage already taught everyone, so it needs no legend on screen.
 */
class PathHazardTest {

    private fun straight() = listOf(
        Vec3(0f, 0f, 0f), Vec3(0f, 0f, -5f), Vec3(0f, 0f, -10f)
    )

    /** Ninety degrees to the right, halfway along. */
    private fun corner() = listOf(
        Vec3(0f, 0f, 0f), Vec3(0f, 0f, -5f), Vec3(5f, 0f, -5f), Vec3(10f, 0f, -5f)
    )

    @Test
    fun `a straight stretch is clear`() {
        assertEquals(PathHazard.CLEAR, pathHazardFor(straight(), hasStairsAhead = false))
    }

    @Test
    fun `a corner is a turn`() {
        assertEquals(PathHazard.TURN, pathHazardFor(corner(), hasStairsAhead = false))
    }

    @Test
    fun `a gentle bend is not a turn`() {
        // Colouring every slight curve orange would make orange mean nothing.
        val bend = listOf(
            Vec3(0f, 0f, 0f), Vec3(0f, 0f, -5f), Vec3(1f, 0f, -10f)
        )
        assertEquals(PathHazard.CLEAR, pathHazardFor(bend, hasStairsAhead = false))
    }

    @Test
    fun `stairs win over a turn`() {
        // A turn is a manoeuvre; stairs are a barrier. Someone pushing a
        // wheelchair needs the barrier, not the manoeuvre.
        assertEquals(PathHazard.STAIRS, pathHazardFor(corner(), hasStairsAhead = true))
        assertEquals(PathHazard.STAIRS, pathHazardFor(straight(), hasStairsAhead = true))
    }

    @Test
    fun `only stairs carry a written warning`() {
        assertNull(PathHazard.CLEAR.warningAr)
        assertNull(PathHazard.TURN.warningAr)
        assertNotNull(PathHazard.STAIRS.warningAr)
        assertTrue(PathHazard.STAIRS.warningAr!!.contains("درج"))
    }

    @Test
    fun `every hazard has a distinct colour`() {
        val colours = PathHazard.entries.map { it.ribbonColour.toList() }
        assertEquals("two hazards share a colour", colours.size, colours.toSet().size)
    }

    @Test
    fun `an empty path is clear rather than crashing`() {
        assertEquals(PathHazard.CLEAR, pathHazardFor(emptyList(), hasStairsAhead = false))
        assertEquals(PathHazard.CLEAR, pathHazardFor(listOf(Vec3(0f, 0f, 0f)), false))
    }

    // ---- Detecting stairs on the route ------------------------------------

    private val a = LatLng(24.4808, 39.5596)
    private val b = stepAlongBearing(a, 0.0, 10.0)
    private val c = stepAlongBearing(b, 0.0, 10.0)

    private fun network(stairsOnSecond: Boolean) = PathNetwork(
        nodes = listOf(PathNode("a", a), PathNode("b", b), PathNode("c", c)),
        edges = listOf(
            PathEdge("e1", "a", "b"),
            PathEdge("e2", "b", "c", hasStairs = stairsOnSecond)
        )
    )

    private fun route() = Route(
        points = listOf(a, b, c),
        distanceMeters = 20.0,
        mode = TravelMode.WALK,
        nodeIds = listOf("a", "b", "c")
    )

    @Test
    fun `stairs on the route ahead are found`() {
        assertTrue(stairsAhead(route(), network(stairsOnSecond = true), a))
    }

    @Test
    fun `a route with no stairs reports none`() {
        assertFalse(stairsAhead(route(), network(stairsOnSecond = false), a))
    }

    @Test
    fun `distant stairs are not warned about yet`() {
        // Colouring the path red for stairs 200m away makes the warning
        // meaningless long before anyone reaches them.
        assertFalse(
            "stairs beyond the horizon should not colour the path",
            stairsAhead(route(), network(stairsOnSecond = true), a, withinMeters = 5.0)
        )
    }

    @Test
    fun `no route means no warning`() {
        assertFalse(stairsAhead(null, network(true), a))
    }

    @Test
    fun `an invalid position is handled`() {
        assertFalse(stairsAhead(route(), network(true), LatLng(0.0, 0.0)))
    }
}
