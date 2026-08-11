package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the V2 route maths. As with the V1 geometry tests, these run on the
 * JVM with no device: a wrong resample or distance calculation is very hard
 * to spot by eye through a camera feed.
 */
class WaypointsTest {

    @Test
    fun `every destination has a usable route`() {
        assertEquals(3, CampusMap.ALL.size)
        for (d in CampusMap.ALL) {
            assertTrue("${d.name} needs at least 2 waypoints", d.waypoints.size >= 2)
            assertTrue("${d.name} should have a non-zero route", d.routeLengthMeters > 0f)
            assertTrue("${d.name} needs a display name", d.name.isNotBlank())
        }
    }

    @Test
    fun `destination ids are unique and resolvable`() {
        val ids = CampusMap.ALL.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        for (id in ids) {
            assertEquals(id, CampusMap.byId(id)?.id)
        }
        assertEquals(null, CampusMap.byId("does-not-exist"))
    }

    @Test
    fun `route length sums the segments`() {
        val d = Destination(
            id = "t", name = "T", category = "", detail = "",
            waypoints = listOf(
                Vec3(0f, 0f, 0f),
                Vec3(0f, 3f, 0f),
                Vec3(4f, 3f, 0f)
            )
        )
        assertEquals(7f, d.routeLengthMeters, 1e-4f)
    }

    @Test
    fun `resampling keeps every original corner`() {
        val route = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(0f, 4f, 0f),
            Vec3(3f, 4f, 0f)
        )
        val dense = resampleRoute(route, maxSegment = 0.5f)

        for (corner in route) {
            val kept = dense.any { (it - corner).length() < 1e-4f }
            assertTrue("corner $corner must survive resampling", kept)
        }
    }

    @Test
    fun `resampling respects the max segment length`() {
        val route = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 10f, 0f))
        val dense = resampleRoute(route, maxSegment = 0.5f)

        for ((a, b) in dense.zipWithNext()) {
            assertTrue(
                "segment ${(b - a).length()} exceeds max",
                (b - a).length() <= 0.5f + 1e-4f
            )
        }
    }

    @Test
    fun `resampling preserves total route length`() {
        val route = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(0f, 4f, 0f),
            Vec3(3f, 4f, 0f)
        )
        val original = route.zipWithNext().sumOf { (a, b) -> (b - a).length().toDouble() }
        val dense = resampleRoute(route, 0.3f)
        val densified = dense.zipWithNext().sumOf { (a, b) -> (b - a).length().toDouble() }
        assertEquals(original, densified, 1e-3)
    }

    @Test
    fun `resampling degenerate input does not crash or lose data`() {
        assertEquals(0, resampleRoute(emptyList()).size)
        assertEquals(1, resampleRoute(listOf(Vec3.ZERO)).size)
        // Duplicate points must not produce NaN or an infinite loop.
        val dupes = resampleRoute(listOf(Vec3.ZERO, Vec3.ZERO, Vec3(0f, 1f, 0f)), 0.5f)
        assertTrue(dupes.all { !it.x.isNaN() && !it.y.isNaN() && !it.z.isNaN() })
    }

    @Test
    fun `remaining distance at the start is the whole route`() {
        val route = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(0f, 3f, 0f),
            Vec3(4f, 3f, 0f)
        )
        assertEquals(7f, remainingRouteDistance(route, Vec3(0f, 0f, 0f)), 1e-3f)
    }

    @Test
    fun `remaining distance shrinks as the user advances`() {
        val route = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(0f, 10f, 0f)
        )
        val atStart = remainingRouteDistance(route, Vec3(0f, 0f, 0f))
        val midway = remainingRouteDistance(route, Vec3(0f, 6f, 0f))
        val atEnd = remainingRouteDistance(route, Vec3(0f, 10f, 0f))

        assertEquals(10f, atStart, 1e-3f)
        assertEquals(4f, midway, 1e-3f)
        assertEquals(0f, atEnd, 1e-3f)
        assertTrue(midway < atStart)
    }

    @Test
    fun `remaining distance handles a user standing off the path`() {
        val route = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 10f, 0f))
        // 3m to the side, halfway along: distance should still be ~5m.
        assertEquals(5f, remainingRouteDistance(route, Vec3(3f, 5f, 0f)), 1e-3f)
    }

    @Test
    fun `vertical mounting puts the route on the floor below the board`() {
        val local = toImageLocal(Vec3(2f, 5f, 0f), ImageMounting.VERTICAL, boardHeight = 1.5f)
        // X unchanged, forwards preserved in Y, and +Z is "down the image",
        // i.e. the floor 1.5m below the board centre.
        assertEquals(2f, local.x, 1e-4f)
        assertEquals(5f, local.y, 1e-4f)
        assertEquals(1.5f, local.z, 1e-4f)
    }

    @Test
    fun `flat mounting keeps the route on the image plane`() {
        val local = toImageLocal(Vec3(2f, 5f, 0f), ImageMounting.FLAT, boardHeight = 1.5f)
        assertEquals(2f, local.x, 1e-4f)
        // Nothing lifts off a floor marker...
        assertEquals(0f, local.y, 1e-4f)
        // ...and "forwards" runs down the image face.
        assertEquals(-5f, local.z, 1e-4f)
    }
}
