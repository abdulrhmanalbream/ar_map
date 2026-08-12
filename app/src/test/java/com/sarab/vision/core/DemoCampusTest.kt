package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo campus must reproduce the REAL distances the user described, or
 * it teaches the wrong lessons: a demo where buildings are 5m apart would
 * never show the ambiguity prompt that matters on site.
 */
class DemoCampusTest {

    private val here = LatLng(24.500000, 39.500000)

    @Test
    fun `generates all four landmarks`() {
        val demo = DemoCampus.generate(here)
        assertEquals(4, demo.size)
        assertTrue(demo.all { DemoCampus.isDemo(it) })
    }

    @Test
    fun `invalid centre produces nothing`() {
        assertTrue(DemoCampus.generate(LatLng(0.0, 0.0)).isEmpty())
    }

    @Test
    fun `far landmark really is about 250 metres away`() {
        val demo = DemoCampus.generate(here)
        val stadium = demo.first { it.name.contains("الملعب") }

        val d = distanceMeters(here, stadium.position)
        assertEquals("far landmark should be ~250m", 250.0, d, 5.0)
    }

    @Test
    fun `the close pair really is about 20 metres apart`() {
        val demo = DemoCampus.generate(here)
        val computing = demo.first { it.name.contains("الحاسب") }
        val sharia = demo.first { it.name.contains("الشريعة") }

        val gap = distanceMeters(computing.position, sharia.position)
        assertEquals("the pair must reproduce the real 20m gap", 20.0, gap, 2.0)
    }

    @Test
    fun `the close pair is detected as ambiguous`() {
        // The whole point of the demo: the user must be able to SEE the
        // ambiguity prompt without going to the campus.
        val demo = DemoCampus.generate(here)
        val pairs = findAmbiguousPairs(demo)

        assertTrue("demo must produce an ambiguous pair", pairs.isNotEmpty())
        val names = pairs.flatMap { listOf(it.first.name, it.second.name) }
        assertTrue(names.any { it.contains("الحاسب") })
        assertTrue(names.any { it.contains("الشريعة") })
    }

    @Test
    fun `far landmark sits ahead of the user's heading`() {
        // Facing east: the far landmark should be roughly east, so it is in
        // view immediately rather than behind the user.
        val demo = DemoCampus.generate(here, headingDeg = 90.0)
        val stadium = demo.first { it.name.contains("الملعب") }

        val bearing = bearingDegrees(here, stadium.position)
        assertEquals("should be ahead when facing east", 90.0, bearing, 5.0)
    }

    @Test
    fun `rotation preserves distances`() {
        val a = DemoCampus.generate(here, 0.0)
        val b = DemoCampus.generate(here, 137.0)

        for (i in a.indices) {
            assertEquals(
                distanceMeters(here, a[i].position),
                distanceMeters(here, b[i].position),
                1.0
            )
        }
    }

    @Test
    fun `demo produces every guidance mode`() {
        val demo = DemoCampus.generate(here)

        val far = demo.first { it.name.contains("الملعب") }
        val compass = guidanceFor(here, 0.0, far, demo)
        assertTrue("far landmark should be compass mode", compass is GuidanceMode.Compass)

        // Standing at the close pair should be ambiguous.
        val computing = demo.first { it.name.contains("الحاسب") }
        val atComputing = computing.position
        val ambiguous = guidanceFor(atComputing, 0.0, computing, demo)
        assertTrue(
            "standing at the pair should be ambiguous, was $ambiguous",
            ambiguous is GuidanceMode.Ambiguous
        )
    }

    @Test
    fun `stepping towards a target reduces the distance`() {
        val target = LatLng(24.502000, 39.500000) // ~222m north

        val start = distanceMeters(here, target)
        val moved = stepTowards(here, target, 50.0)
        val after = distanceMeters(moved, target)

        assertEquals("should have moved 50m", 50.0, start - after, 2.0)
    }

    @Test
    fun `stepping never overshoots the target`() {
        val target = LatLng(24.500050, 39.500000) // ~5.5m away
        val moved = stepTowards(here, target, 100.0)

        assertEquals(0.0, distanceMeters(moved, target), 0.6)
    }

    @Test
    fun `stepping along a bearing moves the right way`() {
        val north = stepAlongBearing(here, 0.0, 100.0)
        assertTrue("north should increase latitude", north.latitude > here.latitude)
        assertEquals(100.0, distanceMeters(here, north), 2.0)

        val east = stepAlongBearing(here, 90.0, 100.0)
        assertTrue("east should increase longitude", east.longitude > here.longitude)
        assertEquals(100.0, distanceMeters(here, east), 2.0)
    }

    @Test
    fun `demo landmarks are distinguishable from surveyed ones`() {
        val demo = DemoCampus.generate(here).first()
        val real = Landmark("lm-123", "حقيقي", LandmarkCategory.OTHER, here)

        assertTrue(DemoCampus.isDemo(demo))
        assertTrue(!DemoCampus.isDemo(real))
        assertNotNull(demo.detail)
    }
}
