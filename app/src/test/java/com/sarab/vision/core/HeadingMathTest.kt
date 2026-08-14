package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compass heading for every way a phone gets held.
 *
 * These matrices are built by hand from the device axes, which is the only way
 * to prove the heading is right without standing in a field with a compass.
 *
 * Matrix convention: row-major, maps device -> world (X east, Y north, Z up).
 * Its columns are the world images of the device axes, so a matrix is built by
 * writing down where device X, Y and Z each end up.
 */
class HeadingMathTest {

    /**
     * @param xAxis where device X (right edge) points, in world coords
     * @param yAxis where device Y (top edge) points
     * @param zAxis where device Z (out of the screen) points
     */
    private fun matrix(
        xAxis: Triple<Float, Float, Float>,
        yAxis: Triple<Float, Float, Float>,
        zAxis: Triple<Float, Float, Float>
    ) = floatArrayOf(
        xAxis.first, yAxis.first, zAxis.first,
        xAxis.second, yAxis.second, zAxis.second,
        xAxis.third, yAxis.third, zAxis.third
    )

    private val east = Triple(1f, 0f, 0f)
    private val west = Triple(-1f, 0f, 0f)
    private val north = Triple(0f, 1f, 0f)
    private val south = Triple(0f, -1f, 0f)
    private val up = Triple(0f, 0f, 1f)
    private val down = Triple(0f, 0f, -1f)

    /** Phone held upright in portrait, camera looking north. */
    private fun uprightFacingNorth() = matrix(
        xAxis = east,   // right edge points east
        yAxis = up,     // top edge points at the sky
        zAxis = south   // screen faces the user, i.e. south; camera looks north
    )

    /** Phone flat on a table, screen up, top edge pointing north. */
    private fun flatTopNorth() = matrix(
        xAxis = east,
        yAxis = north,
        zAxis = up
    )

    // ---- Upright / camera mode ------------------------------------------

    @Test
    fun `upright phone reads the direction the camera looks`() {
        val result = computeHeading(uprightFacingNorth(), displayRotationDeg = 0)

        assertEquals(HeadingSource.CAMERA, result.source)
        assertNotNull(result.degrees)
        assertEquals(0.0, result.degrees!!, 0.5)
    }

    @Test
    fun `upright phone facing east reads ninety`() {
        val m = matrix(xAxis = south, yAxis = up, zAxis = west)
        val result = computeHeading(m, 0)

        assertEquals(HeadingSource.CAMERA, result.source)
        assertEquals(90.0, result.degrees!!, 0.5)
    }

    @Test
    fun `upright phone facing south reads one eighty`() {
        val m = matrix(xAxis = west, yAxis = up, zAxis = north)
        assertEquals(180.0, computeHeading(m, 0).degrees!!, 0.5)
    }

    @Test
    fun `upright phone facing west reads two seventy`() {
        val m = matrix(xAxis = north, yAxis = up, zAxis = east)
        assertEquals(270.0, computeHeading(m, 0).degrees!!, 0.5)
    }

    @Test
    fun `camera heading ignores screen rotation`() {
        // The camera looks along device -Z whatever the screen rotation, so a
        // landscape phone pointed north must still read north.
        val m = uprightFacingNorth()
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(
                "rotation $rotation should not change the camera heading",
                0.0,
                computeHeading(m, rotation).degrees!!,
                0.5
            )
        }
    }

    // ---- Flat / map mode -------------------------------------------------

    @Test
    fun `flat phone falls back to the top of the screen`() {
        // This is the case the old implementation got right and the camera
        // approach alone would get wrong: laid flat, the camera points at the
        // floor and carries no heading at all.
        val result = computeHeading(flatTopNorth(), displayRotationDeg = 0)

        assertEquals(HeadingSource.SCREEN_UP, result.source)
        assertEquals(0.0, result.degrees!!, 0.5)
    }

    @Test
    fun `flat phone turned to face east reads ninety`() {
        val m = matrix(xAxis = south, yAxis = east, zAxis = up)
        val result = computeHeading(m, 0)

        assertEquals(HeadingSource.SCREEN_UP, result.source)
        assertEquals(90.0, result.degrees!!, 0.5)
    }

    @Test
    fun `flat phone in landscape accounts for screen rotation`() {
        // Device top points north, but the screen is rotated 90 degrees, so
        // what the user sees as "up the screen" is a different direction.
        val m = flatTopNorth()

        val portrait = computeHeading(m, 0).degrees!!
        val landscape = computeHeading(m, 90).degrees!!

        assertEquals(0.0, portrait, 0.5)
        assertEquals(
            "landscape should differ by 90 degrees",
            90.0,
            kotlin.math.abs(relativeBearing(portrait, landscape)),
            1.0
        )
    }

    // ---- The transition --------------------------------------------------

    @Test
    fun `raising the phone from flat to upright keeps the heading correct`() {
        // The user's exact scenario: glance down at the map, then raise the
        // phone to look through the camera. Both must read north.
        val flat = computeHeading(flatTopNorth(), 0)
        val upright = computeHeading(uprightFacingNorth(), 0)

        assertEquals(0.0, flat.degrees!!, 0.5)
        assertEquals(0.0, upright.degrees!!, 0.5)
        assertTrue("the source should change", flat.source != upright.source)
    }

    @Test
    fun `tilt is reported so the UI can explain itself`() {
        assertEquals(0f, computeHeading(uprightFacingNorth(), 0).tilt, 0.02f)
        assertEquals(1f, computeHeading(flatTopNorth(), 0).tilt, 0.02f)
    }

    @Test
    fun `hysteresis stops the source flapping at the boundary`() {
        // A camera tilted just past the threshold: whether it counts as flat
        // should depend on which state it was already in.
        val tilted = matrix(
            xAxis = east,
            yAxis = Triple(0f, 0.71f, 0.70f),
            zAxis = Triple(0f, -0.70f, 0.71f)
        )
        val fromUpright = computeHeading(tilted, 0, wasFlat = false)
        val fromFlat = computeHeading(tilted, 0, wasFlat = true)

        assertTrue(
            "the same pose should resolve differently depending on history",
            fromUpright.source != fromFlat.source
        )
    }

    @Test
    fun `a malformed matrix yields no heading rather than nonsense`() {
        val result = computeHeading(floatArrayOf(1f, 0f, 0f), 0)
        assertNull(result.degrees)
    }

    @Test
    fun `a vertical direction has no meaningful bearing`() {
        assertNull(bearingOf(Triple(0f, 0f, 1f)))
        assertNull(bearingOf(Triple(0f, 0f, -1f)))
        assertNotNull(bearingOf(Triple(0f, 1f, 0f)))
    }

    // ---- Smoothing -------------------------------------------------------

    @Test
    fun `smoothing does not swing a half turn across north`() {
        // Averaging raw degrees would put the mean of 359 and 1 at 180.
        val smoother = CircularSmoother(alpha = 0.5)
        smoother.next(359.0)
        val result = smoother.next(1.0)

        val offNorth = kotlin.math.abs(relativeBearing(0.0, result))
        assertTrue("smoothed heading swung to $result", offNorth < 5.0)
    }

    @Test
    fun `smoothing converges on a steady reading`() {
        val smoother = CircularSmoother(alpha = 0.3)
        repeat(60) { smoother.next(123.0) }
        assertEquals(123.0, smoother.next(123.0), 0.5)
    }

    @Test
    fun `smoothing damps a single spike`() {
        val smoother = CircularSmoother(alpha = 0.15)
        repeat(30) { smoother.next(90.0) }

        val afterSpike = smoother.next(270.0)
        // One bad reading must not throw the needle across the dial.
        assertTrue(
            "a single spike moved the heading to $afterSpike",
            kotlin.math.abs(relativeBearing(90.0, afterSpike)) < 40.0
        )
    }
}
