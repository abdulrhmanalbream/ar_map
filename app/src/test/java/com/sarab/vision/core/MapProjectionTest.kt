package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map projection.
 *
 * The y-axis inversion is the classic silent bug here: canvas y grows
 * downwards while north grows upwards, so getting it wrong flips the whole
 * campus vertically and everything still *looks* like a map.
 */
class MapProjectionTest {

    private val origin = LatLng(24.500000, 39.500000)

    private fun at(northMeters: Double, eastMeters: Double): LatLng {
        val dLat = northMeters / 111_195.0
        val dLon = eastMeters / (111_195.0 * kotlin.math.cos(Math.toRadians(origin.latitude)))
        return LatLng(origin.latitude + dLat, origin.longitude + dLon)
    }

    @Test
    fun `bounds cover every point`() {
        val points = listOf(at(0.0, 0.0), at(100.0, 50.0), at(-30.0, -20.0))
        val b = GeoBounds.of(points)!!

        assertTrue(b.minLat <= points.minOf { it.latitude })
        assertTrue(b.maxLat >= points.maxOf { it.latitude })
        assertTrue(b.minLon <= points.minOf { it.longitude })
        assertTrue(b.maxLon >= points.maxOf { it.longitude })
    }

    @Test
    fun `bounds ignore invalid points`() {
        val b = GeoBounds.of(listOf(LatLng(0.0, 0.0), origin))
        assertNotNull(b)
        assertEquals(origin.latitude, b!!.centre.latitude, 1e-9)
    }

    @Test
    fun `bounds of nothing is null`() {
        assertNull(GeoBounds.of(emptyList()))
        assertNull(GeoBounds.of(listOf(LatLng(0.0, 0.0))))
    }

    @Test
    fun `north appears above south on the canvas`() {
        val south = at(0.0, 0.0)
        val north = at(200.0, 0.0)
        val bounds = GeoBounds.of(listOf(south, north))!!
        val t = MapTransform(bounds, 800f, 800f)

        val sPt = t.toCanvas(south)
        val nPt = t.toCanvas(north)

        // Smaller y == higher on screen.
        assertTrue(
            "north (y=${nPt.y}) must be above south (y=${sPt.y})",
            nPt.y < sPt.y
        )
    }

    @Test
    fun `east appears right of west on the canvas`() {
        val west = at(0.0, 0.0)
        val east = at(0.0, 200.0)
        val bounds = GeoBounds.of(listOf(west, east))!!
        val t = MapTransform(bounds, 800f, 800f)

        assertTrue(t.toCanvas(east).x > t.toCanvas(west).x)
    }

    @Test
    fun `all points land inside the canvas`() {
        val points = listOf(
            at(0.0, 0.0),
            at(250.0, 120.0),
            at(-80.0, -200.0),
            at(140.0, -60.0)
        )
        val bounds = GeoBounds.of(points)!!
        val t = MapTransform(bounds, 900f, 1200f)

        for (p in points) {
            val c = t.toCanvas(p)
            assertTrue("x=${c.x} off canvas", c.x >= 0f && c.x <= 900f)
            assertTrue("y=${c.y} off canvas", c.y >= 0f && c.y <= 1200f)
        }
    }

    @Test
    fun `aspect ratio is preserved so the campus is not stretched`() {
        // A square 200m x 200m arrangement must stay square on a non-square
        // canvas, otherwise distances read off the map are wrong.
        val sw = at(0.0, 0.0)
        val se = at(0.0, 200.0)
        val nw = at(200.0, 0.0)
        val bounds = GeoBounds.of(listOf(sw, se, nw))!!
        val t = MapTransform(bounds, 600f, 1000f)

        val horizontal = t.toCanvas(se).x - t.toCanvas(sw).x
        val vertical = t.toCanvas(sw).y - t.toCanvas(nw).y

        assertEquals(
            "equal real distances must map to equal pixel distances",
            horizontal.toDouble(),
            vertical.toDouble(),
            2.0
        )
    }

    @Test
    fun `a single landmark still projects without dividing by zero`() {
        val bounds = GeoBounds.of(listOf(origin))!!
        val t = MapTransform(bounds, 800f, 800f)
        val c = t.toCanvas(origin)

        assertTrue(c.x.isFinite() && c.y.isFinite())
        assertTrue(c.x in 0f..800f && c.y in 0f..800f)
    }

    @Test
    fun `scale bar picks a round number`() {
        val bounds = GeoBounds.of(listOf(at(0.0, 0.0), at(300.0, 300.0)))!!
        val t = MapTransform(bounds, 800f, 800f)

        val bar = t.scaleBarMeters()
        assertTrue("scale bar $bar not a round value", bar in listOf(10, 20, 50, 100, 200, 500, 1000))
        assertTrue(t.metersPerPixel() > 0)
    }

    @Test
    fun `distances measured on the map match reality`() {
        val a = at(0.0, 0.0)
        val b = at(0.0, 250.0) // 250m apart, matching the real campus spread
        val bounds = GeoBounds.of(listOf(a, b))!!
        val t = MapTransform(bounds, 1000f, 1000f)

        val pxA = t.toCanvas(a)
        val pxB = t.toCanvas(b)
        val pixelDistance = kotlin.math.hypot(
            (pxB.x - pxA.x).toDouble(),
            (pxB.y - pxA.y).toDouble()
        )
        val measured = pixelDistance * t.metersPerPixel()

        assertEquals(250.0, measured, 5.0)
    }
}
