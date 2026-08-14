package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline routing over the drawn path network.
 *
 * The cases that matter are the mode restrictions: a car must not be routed
 * down a footpath, a bike must be free to use both, and stairs must block
 * anything on wheels. Getting those wrong sends someone down a route they
 * physically cannot travel.
 */
class PathNetworkTest {

    private val origin = LatLng(24.500000, 39.500000)

    private fun at(northM: Double, eastM: Double): LatLng {
        val dLat = northM / 111_195.0
        val dLon = eastM / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return LatLng(origin.latitude + dLat, origin.longitude + dLon)
    }

    /**
     * A network shaped like a triangle:
     *
     *   A --- footpath (100m) --- B
     *    \                       /
     *     road (150m) -- C -- road (150m)
     *
     * Walking should take the short footpath; driving must detour via C.
     */
    private fun triangleNetwork(): PathNetwork {
        val nodes = listOf(
            PathNode("A", at(0.0, 0.0)),
            PathNode("B", at(0.0, 100.0)),
            PathNode("C", at(-150.0, 50.0))
        )
        val edges = listOf(
            PathEdge(
                "AB", "A", "B",
                allowsFoot = true, allowsBike = true, allowsCar = false,
                name = "ممشى"
            ),
            PathEdge(
                "AC", "A", "C",
                allowsFoot = true, allowsBike = true, allowsCar = true,
                name = "طريق"
            ),
            PathEdge(
                "CB", "C", "B",
                allowsFoot = true, allowsBike = true, allowsCar = true,
                name = "طريق"
            )
        )
        return PathNetwork(nodes, edges)
    }

    @Test
    fun `walking takes the direct footpath`() {
        val route = findRoute(triangleNetwork(), "A", "B", TravelMode.WALK)

        assertNotNull(route)
        assertEquals(listOf("A", "B"), route!!.nodeIds)
        assertEquals(100.0, route.distanceMeters, 3.0)
    }

    @Test
    fun `driving detours around the footpath`() {
        // The whole point of per-edge permissions: a car must not be sent
        // down a pedestrian-only route.
        val route = findRoute(triangleNetwork(), "A", "B", TravelMode.CAR)

        assertNotNull("a car route should exist via C", route)
        assertEquals(listOf("A", "C", "B"), route!!.nodeIds)
        assertTrue("driving should be longer", route.distanceMeters > 250.0)
    }

    @Test
    fun `cycling may use the footpath`() {
        val route = findRoute(triangleNetwork(), "A", "B", TravelMode.BIKE)

        assertNotNull(route)
        assertEquals("bikes should take the short footpath", listOf("A", "B"), route!!.nodeIds)
    }

    @Test
    fun `stairs block wheels but not feet`() {
        val nodes = listOf(PathNode("A", at(0.0, 0.0)), PathNode("B", at(0.0, 40.0)))
        val edges = listOf(
            PathEdge(
                "AB", "A", "B",
                allowsFoot = true, allowsBike = true, allowsCar = false,
                hasStairs = true
            )
        )
        val network = PathNetwork(nodes, edges)

        assertNotNull("walking up stairs is fine", findRoute(network, "A", "B", TravelMode.WALK))
        assertNull("a bike cannot ride stairs", findRoute(network, "A", "B", TravelMode.BIKE))
        assertNull("a car cannot drive stairs", findRoute(network, "A", "B", TravelMode.CAR))
    }

    @Test
    fun `no route is reported honestly rather than faked`() {
        val nodes = listOf(
            PathNode("A", at(0.0, 0.0)),
            PathNode("B", at(0.0, 100.0)),
            PathNode("island", at(500.0, 500.0))
        )
        val edges = listOf(PathEdge("AB", "A", "B"))
        val network = PathNetwork(nodes, edges)

        assertNull(findRoute(network, "A", "island", TravelMode.WALK))
    }

    @Test
    fun `A star finds the genuinely shortest path not just any path`() {
        // Diamond: a short top route and a long bottom route.
        val nodes = listOf(
            PathNode("S", at(0.0, 0.0)),
            PathNode("top", at(20.0, 50.0)),
            PathNode("bottom", at(-200.0, 50.0)),
            PathNode("E", at(0.0, 100.0))
        )
        val edges = listOf(
            PathEdge("s-top", "S", "top"),
            PathEdge("top-e", "top", "E"),
            PathEdge("s-bottom", "S", "bottom"),
            PathEdge("bottom-e", "bottom", "E")
        )
        val route = findRoute(PathNetwork(nodes, edges), "S", "E", TravelMode.WALK)

        assertNotNull(route)
        assertTrue("should take the top route", route!!.nodeIds.contains("top"))
        assertTrue("should not take the long detour", !route.nodeIds.contains("bottom"))
    }

    @Test
    fun `routing to itself is a zero-length route`() {
        val route = findRoute(triangleNetwork(), "A", "A", TravelMode.WALK)
        assertNotNull(route)
        assertEquals(0.0, route!!.distanceMeters, 0.001)
    }

    @Test
    fun `nearest node snaps the user onto the network`() {
        val network = triangleNetwork()
        // Standing 10m from A.
        val near = network.nearestNode(at(10.0, 0.0))
        assertEquals("A", near?.id)

        // Standing a kilometre away: nothing should snap.
        assertNull(network.nearestNode(at(1000.0, 1000.0)))
    }

    @Test
    fun `routeTo falls back to a straight line with no network`() {
        val empty = PathNetwork()
        val route = routeTo(empty, at(0.0, 0.0), at(0.0, 200.0), TravelMode.WALK)

        // A two-point route is the caller's signal that this is a fallback.
        assertEquals(2, route.points.size)
        assertEquals(200.0, route.distanceMeters, 3.0)
    }

    @Test
    fun `routeTo stitches the real endpoints onto the network path`() {
        val network = triangleNetwork()
        val start = at(-5.0, -5.0)   // just off node A
        val end = at(5.0, 105.0)     // just off node B

        val route = routeTo(network, start, end, TravelMode.WALK)

        assertEquals("route must begin at the user", start, route.points.first())
        assertEquals("route must end at the destination", end, route.points.last())
        assertTrue("should use the network, not a straight line", route.points.size > 2)
    }

    @Test
    fun `travel time varies by mode over the same route`() {
        val network = triangleNetwork()
        val walk = findRoute(network, "A", "B", TravelMode.WALK)!!
        assertTrue(walk.minutes >= 1)

        val bikeRoute = findRoute(network, "A", "B", TravelMode.BIKE)!!
        assertTrue("cycling should not be slower", bikeRoute.minutes <= walk.minutes)
    }

    @Test
    fun `route steps merge straight segments and end with arrival`() {
        // An L shape: straight, then a right turn, then straight.
        val points = listOf(
            at(0.0, 0.0),
            at(50.0, 0.0),
            at(100.0, 0.0),   // still straight -- must not create a step
            at(100.0, 80.0)   // right turn
        )
        val route = Route(points, 230.0, TravelMode.WALK, emptyList())
        val steps = routeSteps(route)

        assertTrue("expected a turn and an arrival, got ${steps.size}", steps.size == 2)
        assertTrue(steps[0].instructionAr.contains("يمين"))
        assertTrue(steps.last().instructionAr.contains("وصلت"))
    }

    @Test
    fun `route steps handle a degenerate route`() {
        assertTrue(routeSteps(Route(emptyList(), 0.0, TravelMode.WALK, emptyList())).isEmpty())
        assertTrue(
            routeSteps(Route(listOf(at(0.0, 0.0)), 0.0, TravelMode.WALK, emptyList())).isEmpty()
        )
    }
}
