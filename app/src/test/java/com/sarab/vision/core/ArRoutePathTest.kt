package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Projecting a real route onto the ground in front of the camera.
 *
 * The AR view previously drew a straight ribbon along the bearing to the
 * destination, which is a pointer rather than a path -- it went through walls
 * and ignored every turn. These tests pin down the thing that replaced it.
 */
class ArRoutePathTest {

    private val origin = LatLng(24.4808, 39.5596)

    /** A point [metres] away from [from] at [bearing] degrees. */
    private fun at(from: LatLng, bearing: Double, metres: Double) =
        stepAlongBearing(from, bearing, metres)

    @Test
    fun `a route straight ahead lands straight ahead`() {
        // Facing north, route runs north: everything should sit on -Z with
        // essentially no sideways offset.
        val route = listOf(origin, at(origin, 0.0, 20.0))
        val path = routeGroundPath(origin, route, headingDeg = 0.0)

        assertTrue("expected a path", path.size >= 2)
        path.forEach {
            assertEquals("drifted sideways", 0.0, it.x.toDouble(), 0.6)
            assertTrue("should be in front of the camera", it.z < 0.1f)
        }
    }

    @Test
    fun `a route to the right appears on the right`() {
        // Facing north, route runs east. ARCore's +X is to the camera's right.
        val route = listOf(origin, at(origin, 90.0, 20.0))
        val path = routeGroundPath(origin, route, headingDeg = 0.0)

        assertTrue("expected a path", path.size >= 2)
        assertTrue("should bend right, got x=${path.last().x}", path.last().x > 5f)
    }

    @Test
    fun `turning the phone turns the path the other way`() {
        // Route runs north. Face north, then face east. The path must swing to
        // the LEFT of the screen, because the user turned right past it.
        val route = listOf(origin, at(origin, 0.0, 20.0))

        val facingNorth = routeGroundPath(origin, route, headingDeg = 0.0)
        val facingEast = routeGroundPath(origin, route, headingDeg = 90.0)

        assertTrue(facingNorth.last().x < 3f)
        assertTrue("should swing left, got x=${facingEast.last().x}", facingEast.last().x < -5f)
    }

    @Test
    fun `a corner in the route survives into the ground path`() {
        // The whole point: 15m north, then 15m east. The drawn path has to
        // bend, not cut the corner or straighten out.
        val corner = at(origin, 0.0, 15.0)
        val route = listOf(origin, corner, at(corner, 90.0, 15.0))

        val path = routeGroundPath(origin, route, headingDeg = 0.0, maxVisibleMeters = 40.0)
        val turn = groundPathTurnDegrees(path)

        assertTrue("the corner was lost; turn was $turn degrees", turn > 60.0)
    }

    @Test
    fun `the path is clipped to the visible horizon`() {
        // ARCore only knows the ground it can see. A 250m ribbon would pass
        // through buildings and float over dips.
        val route = listOf(origin, at(origin, 0.0, 250.0))
        val path = routeGroundPath(origin, route, headingDeg = 0.0, maxVisibleMeters = 25.0)

        val furthest = path.maxOf { kotlin.math.hypot(it.x.toDouble(), it.z.toDouble()) }
        assertTrue("path ran to ${furthest}m", furthest <= 26.0)
    }

    @Test
    fun `the path does not start underfoot`() {
        val route = listOf(origin, at(origin, 0.0, 30.0))
        val path = routeGroundPath(origin, route, headingDeg = 0.0, startOffsetMeters = 0.8)

        val nearest = path.minOf { kotlin.math.hypot(it.x.toDouble(), it.z.toDouble()) }
        assertTrue("path started at ${nearest}m, on top of the user", nearest >= 0.7)
    }

    @Test
    fun `a node behind the user does not make the path double back`() {
        // Routers snap onto the nearest node, which is often slightly behind.
        // Drawn literally that reads as "turn around".
        val behind = at(origin, 180.0, 6.0)
        val ahead = at(origin, 0.0, 30.0)
        val route = listOf(behind, ahead)

        val path = routeGroundPath(origin, route, headingDeg = 0.0, maxVisibleMeters = 30.0)
        assertTrue("expected a path", path.size >= 2)
        // Nothing should be drawn well behind the camera.
        assertTrue(
            "path went behind the user: ${path.map { it.z }}",
            path.none { it.z > 2f }
        )
    }

    @Test
    fun `degenerate input yields nothing rather than crashing`() {
        assertTrue(routeGroundPath(origin, emptyList(), 0.0).isEmpty())
        assertTrue(routeGroundPath(origin, listOf(origin), 0.0).isEmpty())
        assertTrue(routeGroundPath(LatLng(0.0, 0.0), listOf(origin, origin), 0.0).isEmpty())
    }

    // ---- Resampling --------------------------------------------------------

    @Test
    fun `resampling produces evenly spaced points`() {
        val path = listOf(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -10f))
        val dense = resamplePath(path, spacingMeters = 1f)

        assertTrue("expected roughly 11 points, got ${dense.size}", dense.size >= 10)
        for (i in 1 until dense.size - 1) {
            val gap = (dense[i] - dense[i - 1]).length()
            assertEquals("uneven spacing at $i", 1.0, gap.toDouble(), 0.15)
        }
    }

    @Test
    fun `resampling keeps the endpoints`() {
        val path = listOf(Vec3(0f, 0f, 0f), Vec3(3f, 0f, -7f))
        val dense = resamplePath(path, spacingMeters = 1f)

        assertEquals(0.0, dense.first().length().toDouble(), 0.001)
        assertTrue((dense.last() - path.last()).length() < 0.06f)
    }

    @Test
    fun `resampling a degenerate path is safe`() {
        assertEquals(1, resamplePath(listOf(Vec3(1f, 0f, 1f))).size)
        assertTrue(resamplePath(emptyList()).isEmpty())
    }
}
