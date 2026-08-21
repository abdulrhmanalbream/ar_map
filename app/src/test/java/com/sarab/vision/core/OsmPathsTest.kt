package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading OpenStreetMap tags into routing permissions.
 *
 * Tested closely because the failure mode is invisible: a mis-read tag does
 * not crash, it quietly routes a car down a staircase or refuses to let a
 * cyclist use a road, and nobody notices until someone follows the route.
 */
class OsmPathsTest {

    private fun way(
        highway: String,
        foot: String? = null,
        bicycle: String? = null,
        motorVehicle: String? = null,
        access: String? = null
    ) = permissionsFor(OsmWayTags(highway, foot, bicycle, motorVehicle, access))

    @Test
    fun `a footway is for feet and bikes but not cars`() {
        val p = way("footway")!!
        assertTrue(p.allowsFoot)
        assertTrue("bikes must be able to use pedestrian paths", p.allowsBike)
        assertFalse(p.allowsCar)
    }

    @Test
    fun `a residential road takes all three`() {
        val p = way("residential")!!
        assertTrue(p.allowsFoot)
        assertTrue(p.allowsBike)
        assertTrue(p.allowsCar)
    }

    @Test
    fun `a service road takes cars`() {
        // Campus service roads are how deliveries and drop-offs reach
        // buildings, so dropping them would lose most car routing.
        assertTrue(way("service")!!.allowsCar)
    }

    @Test
    fun `steps block wheels entirely`() {
        val p = way("steps")!!
        assertTrue(p.allowsFoot)
        assertFalse(p.allowsBike)
        assertFalse(p.allowsCar)
        assertTrue(p.hasStairs)
    }

    @Test
    fun `steps stay closed to bikes even when tagged otherwise`() {
        // Mis-tagged data exists. A staircase is a staircase.
        val p = way("steps", bicycle = "yes")!!
        assertFalse(p.allowsBike)
    }

    @Test
    fun `a motorway is not walkable`() {
        val p = way("motorway")!!
        assertFalse(p.allowsFoot)
        assertFalse(p.allowsBike)
        assertTrue(p.allowsCar)
    }

    @Test
    fun `explicit tags override the defaults in both directions`() {
        assertFalse("foot=no must close a footway", way("footway", foot = "no")!!.allowsFoot)
        assertFalse("bicycle=no must close a path", way("path", bicycle = "no")!!.allowsBike)
        assertFalse(
            "motor_vehicle=no must close a road",
            way("residential", motorVehicle = "no")!!.allowsCar
        )
        assertTrue(
            "foot=designated must open a cycleway",
            way("cycleway", foot = "designated")!!.allowsFoot
        )
    }

    @Test
    fun `a pedestrian plaza keeps cars out`() {
        val p = way("pedestrian")!!
        assertTrue(p.allowsFoot)
        assertTrue(p.allowsBike)
        assertFalse(p.allowsCar)
    }

    @Test
    fun `private access drops the way`() {
        assertNull(way("service", access = "private"))
        assertNull(way("footway", access = "no"))
    }

    @Test
    fun `private access can be reopened for one mode`() {
        // Gated campus roads are commonly access=private plus foot=yes.
        val p = way("service", foot = "yes", access = "private")
        assertNotNull(p)
        assertTrue(p!!.allowsFoot)
        assertFalse(p.allowsCar)
    }

    @Test
    fun `things that are not thoroughfares are dropped`() {
        assertNull(permissionsFor(OsmWayTags("")))
        assertNull(permissionsFor(OsmWayTags("bus_stop")))
        assertNull(permissionsFor(OsmWayTags("street_lamp")))
    }

    @Test
    fun `the query names the bounding box in the order overpass expects`() {
        val q = overpassQuery(24.4, 39.6, 24.5, 39.7)
        assertTrue(q.contains("[out:json]"))
        assertTrue(q.contains("way[\"highway\"]"))
        assertTrue(q.contains("24.400000,39.600000,24.500000,39.700000"))
        assertTrue(q.contains("out geom"))
    }

    // ---- Building the graph ----------------------------------------------

    private fun geomWay(id: Long, highway: String, vararg points: Pair<Double, Double>) =
        OsmWay(id, OsmWayTags(highway), points.map { LatLng(it.first, it.second) })

    @Test
    fun `a way becomes one edge per segment`() {
        val network = buildNetworkFromWays(
            listOf(
                geomWay(1, "footway", 24.0 to 39.0, 24.001 to 39.0, 24.002 to 39.0)
            )
        )
        assertEquals(3, network.nodes.size)
        assertEquals(2, network.edges.size)
    }

    @Test
    fun `two ways meeting at a point share the node`() {
        // The whole point of the import. If the junction does not become one
        // shared node, A* can never cross from one way to the other and every
        // route fails while the map still looks correct.
        val junction = 24.001 to 39.0
        val network = buildNetworkFromWays(
            listOf(
                geomWay(1, "footway", 24.0 to 39.0, junction),
                geomWay(2, "footway", junction, 24.002 to 39.0)
            )
        )

        assertEquals("the junction must not be duplicated", 3, network.nodes.size)

        // And it must actually be traversable end to end.
        val start = network.nodes.first { it.position.latitude == 24.0 }
        val end = network.nodes.first { it.position.latitude == 24.002 }
        val route = findRoute(network, start.id, end.id, TravelMode.WALK)
        assertNotNull("the two ways did not connect", route)
    }

    @Test
    fun `unusable ways never reach the graph`() {
        val network = buildNetworkFromWays(
            listOf(
                OsmWay(
                    1,
                    OsmWayTags("service", access = "private"),
                    listOf(LatLng(24.0, 39.0), LatLng(24.001, 39.0))
                )
            )
        )
        assertTrue(network.edges.isEmpty())
        assertTrue(network.nodes.isEmpty())
    }

    @Test
    fun `a way with one point is skipped`() {
        val network = buildNetworkFromWays(listOf(geomWay(1, "footway", 24.0 to 39.0)))
        assertTrue(network.edges.isEmpty())
    }

    @Test
    fun `permissions carry through to the edges`() {
        val network = buildNetworkFromWays(
            listOf(geomWay(1, "steps", 24.0 to 39.0, 24.001 to 39.0))
        )
        val edge = network.edges.single()
        assertTrue(edge.hasStairs)
        assertFalse(edge.allowsBike)
        assertTrue(edge.allowsFoot)
    }

    // ---- Merging ----------------------------------------------------------

    @Test
    fun `merging into an empty network keeps the import whole`() {
        val imported = buildNetworkFromWays(
            listOf(geomWay(1, "footway", 24.0 to 39.0, 24.001 to 39.0))
        )
        val merged = mergeNetworks(PathNetwork(), imported)
        assertEquals(imported.edges.size, merged.edges.size)
    }

    @Test
    fun `hand drawn edges survive an import`() {
        val existing = PathNetwork(
            nodes = listOf(
                PathNode("a", LatLng(24.0, 39.0)),
                PathNode("b", LatLng(24.001, 39.0))
            ),
            edges = listOf(PathEdge("hand", "a", "b"))
        )
        val imported = buildNetworkFromWays(
            listOf(geomWay(1, "footway", 24.010 to 39.0, 24.011 to 39.0))
        )

        val merged = mergeNetworks(existing, imported)
        assertTrue(merged.edges.any { it.id == "hand" })
        assertEquals(2, merged.edges.size)
    }

    @Test
    fun `imported nodes snap onto nearby hand drawn ones`() {
        val existing = PathNetwork(
            nodes = listOf(PathNode("a", LatLng(24.0, 39.0))),
            edges = emptyList()
        )
        // ~2m away: the same junction, traced slightly differently.
        val imported = buildNetworkFromWays(
            listOf(geomWay(1, "footway", 24.000018 to 39.0, 24.001 to 39.0))
        )

        val merged = mergeNetworks(existing, imported)
        assertTrue(
            "the near-coincident node should have been reused",
            merged.edges.single().let { it.fromNodeId == "a" || it.toNodeId == "a" }
        )
    }

    @Test
    fun `importing the same area twice does not stack duplicates`() {
        val imported = buildNetworkFromWays(
            listOf(geomWay(1, "footway", 24.0 to 39.0, 24.001 to 39.0))
        )
        val once = mergeNetworks(PathNetwork(), imported)
        val twice = mergeNetworks(once, imported)
        assertEquals(once.edges.size, twice.edges.size)
    }
}
