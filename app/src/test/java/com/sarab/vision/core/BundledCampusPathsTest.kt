package com.sarab.vision.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Routes over the OpenStreetMap network actually shipped in assets, between
 * the surveyed college doors. Synthetic L-shapes prove the maths; this proves
 * the bundled data is connected and reaches every door.
 *
 * The asset is read with plain regexes because org.json is only a stub in JVM
 * unit tests; the file is generated with a fixed key order.
 */
class BundledCampusPathsTest {

    private fun asset(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readText()
    }

    private fun network(): PathNetwork {
        val json = asset("campus_paths.json")
        val nodes = Regex("""\{"id":"([^"]+)","lat":([-0-9.]+),"lon":([-0-9.]+)\}""").findAll(json).map {
            PathNode(it.groupValues[1], LatLng(it.groupValues[2].toDouble(), it.groupValues[3].toDouble()))
        }.toList()
        val edges = Regex(""""id":"(osmw-[^"]+)","from":"([^"]+)","to":"([^"]+)","foot":(true|false),"bike":(true|false),"car":(true|false),"stairs":(true|false)""")
            .findAll(json).map {
                val g = it.groupValues
                PathEdge(g[1], g[2], g[3], g[4] == "true", g[5] == "true", g[6] == "true", g[7] == "true")
            }.toList()
        return PathNetwork(nodes, edges)
    }

    /** The surveyed doors, from landmarks.json. */
    private fun doors(): List<Pair<String, LatLng>> {
        val json = asset("landmarks.json")
        return Regex(""""id":\s*"(en-[^"]+)",\s*"name":\s*"[^"]*",\s*"lat":\s*([-0-9.]+),\s*"lon":\s*([-0-9.]+)""")
            .findAll(json).map { it.groupValues[1] to LatLng(it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
            .toList()
    }

    @Test
    fun `the bundled network loads`() {
        val n = network()
        assertTrue("nodes ${n.nodes.size}", n.nodes.size > 250)
        assertTrue("edges ${n.edges.size}", n.edges.size > 300)
    }

    @Test
    fun `every door is routable from every other door over the network`() {
        val n = network()
        val doors = doors()
        assertTrue("doors ${doors.size}", doors.size >= 5)
        for ((fromId, from) in doors) for ((toId, to) in doors) {
            if (fromId == toId) continue
            val straight = distanceMeters(from, to)
            if (straight < 30.0) continue
            val route = routeTo(n, from, to, TravelMode.WALK)
            assertFalse("$fromId -> $toId fell back to a straight line", route.isStraightFallback)
            // A campus detour longer than this means the graph is broken, not the walk.
            assertTrue("$fromId -> $toId ${route.distanceMeters.toInt()} m vs ${straight.toInt()} m",
                route.distanceMeters <= straight * 4.0 + 150.0)
            val maneuvers = routeManeuvers(route.points)
            assertTrue(maneuvers.first().type == ManeuverType.DEPART && maneuvers.last().type == ManeuverType.ARRIVE)
        }
    }

    @Test
    fun `crossing from the Shariah college to the Quran college gives turn instructions`() {
        val n = network()
        val doors = doors().toMap()
        val route = routeTo(n, doors.getValue("en-shariah-main"), doors.getValue("en-quran-main"), TravelMode.WALK)
        val turns = routeManeuvers(route.points).filter { it.type.isTurn }
        assertTrue("expected at least one turn, got ${turns.map { it.type }}", turns.isNotEmpty())
    }
}
