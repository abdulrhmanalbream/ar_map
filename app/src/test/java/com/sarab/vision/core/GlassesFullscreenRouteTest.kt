package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlassesFullscreenRouteTest {
    private val origin = LatLng(24.5, 39.5)

    @Test fun `wide full screen crops vertically with the same horizontal camera angle`() {
        val native = GlassesScreenPoint(0.4, 0.3, 10.0)
        val wide = cropGlassesPoint(native, 4.0 / 3.0, 16.0 / 9.0)!!
        assertEquals(0.4, wide.x, 0.000001)
        assertEquals(0.4, wide.y, 0.000001)
        assertEquals(10.0, wide.depthMeters, 0.000001)
    }

    @Test fun `narrow full screen crops sides without stretching route vertically`() {
        val cropped = cropGlassesPoint(GlassesScreenPoint(0.4, 0.3, 10.0), 4.0 / 3.0, 0.5)!!
        assertEquals(0.4 * (4.0 / 3.0) / 0.5, cropped.x, 0.000001)
        assertEquals(0.3, cropped.y, 0.000001)
        assertFalse(cropped.visible)
        assertNull(cropGlassesPoint(cropped, Double.NaN, 1.0))
        assertNull(cropGlassesPoint(cropped, 1.0, 0.0))
    }

    @Test fun `walking past old route start keeps the real next corner`() {
        val corner = stepAlongBearing(origin, 0.0, 20.0)
        val finish = stepAlongBearing(corner, 90.0, 30.0)
        val walker = stepAlongBearing(origin, 0.0, 10.0)
        val preview = glassesRoutePreview(walker, listOf(origin, corner, finish), 0.0)
        assertTrue(preview.size >= 3)
        assertTrue(preview.first().length() < 0.01f)
        assertTrue(preview.any { abs(it.x) < 0.01f && abs(it.z + 10f) < 0.01f })
        assertEquals(12.0, preview.last().x.toDouble(), 0.02)
        assertEquals(-10.0, preview.last().z.toDouble(), 0.02)
        assertNull(pointAlongGlassesRoute(preview, 23.0))
    }

    @Test fun `route preview never invents a connector from user to snapped path`() {
        val start = stepAlongBearing(origin, 90.0, 5.0)
        val end = stepAlongBearing(start, 0.0, 30.0)
        val preview = glassesRoutePreview(origin, listOf(start, end), 0.0)
        assertTrue(preview.isNotEmpty())
        assertEquals(5.0, preview.first().x.toDouble(), 0.01)
        assertTrue(preview.all { abs(it.x - 5f) < 0.01f })
    }

    @Test fun `missing broken or distant path has no floor arrows`() {
        val farStart = stepAlongBearing(origin, 90.0, 30.0)
        val farEnd = stepAlongBearing(farStart, 0.0, 30.0)
        assertTrue(glassesRoutePreview(origin, emptyList(), 0.0).isEmpty())
        assertTrue(glassesRoutePreview(origin, listOf(farStart, farEnd), 0.0).isEmpty())
        assertTrue(glassesRoutePreview(origin, listOf(origin, LatLng(Double.NaN, 0.0), farEnd), 0.0).isEmpty())
        assertNotNull(glassesTargetDirection(origin, farEnd, 0.0))
    }

    @Test fun `distant chevrons are rejected instead of stacking at horizon`() {
        val width = 1600.0
        val height = 900.0
        val minimumGap = 70.0
        val arrows = glassesRouteChevrons(listOf(Vec3.ZERO, Vec3(0f, 0f, -22f)),
            0.0, 0.0, 70.0, 4.0 / 3.0, width, height, minimumGap)
        assertTrue(arrows.size in 1..2)
        fun bounds(arrow: GlassesRouteChevron): DoubleArray {
            val points = listOf(arrow.tip, arrow.left, arrow.right)
            assertTrue(points.all { it.visible })
            return doubleArrayOf(points.minOf { (it.x + 1) * width / 2 }, points.minOf { (it.y + 1) * height / 2 },
                points.maxOf { (it.x + 1) * width / 2 }, points.maxOf { (it.y + 1) * height / 2 })
        }
        for (i in arrows.indices) for (j in i + 1 until arrows.size) {
            val a = bounds(arrows[i]); val b = bounds(arrows[j])
            val overlaps = a[0] < b[2] + minimumGap && a[2] > b[0] - minimumGap &&
                a[1] < b[3] + minimumGap && a[3] > b[1] - minimumGap
            assertFalse("projected footprints must leave breathing room", overlaps)
        }
    }

    @Test fun `chevron after a right turn points along the actual segment`() {
        val path = listOf(Vec3.ZERO, Vec3(0f, 0f, -8f), Vec3(14f, 0f, -8f))
        val arrows = glassesRouteChevrons(path, 0.0, 0.0, 90.0, 4.0 / 3.0, 1600.0, 900.0, 0.0)
        assertTrue(arrows.any { it.tip.x > it.left.x && it.tip.x > it.right.x })
    }

    @Test fun `behind or invalid viewport never produces a projected chevron`() {
        val behind = listOf(Vec3.ZERO, Vec3(0f, 0f, 22f))
        assertTrue(glassesRouteChevrons(behind, 0.0, 0.0, 70.0, 4.0 / 3.0, 1600.0, 900.0).isEmpty())
        assertTrue(glassesRouteChevrons(behind, 0.0, 0.0, 70.0, 4.0 / 3.0, Double.NaN, 900.0).isEmpty())
    }

    @Test fun `road arrows leave the bottom quarter clear on wide and square displays`() {
        val paths = listOf(
            listOf(Vec3.ZERO, Vec3(0f, 0f, -22f)),
            listOf(Vec3.ZERO, Vec3(0f, 0f, -11f), Vec3(11f, 0f, -11f)),
        )
        for ((width, height) in listOf(1600.0 to 900.0, 900.0 to 900.0)) {
            var renderedArrowCount = 0
            for (imageAspect in listOf(4.0 / 3.0, 16.0 / 9.0)) {
                for (pitch in listOf(-20.0, -10.0, 0.0, 5.0, 15.0)) {
                    for (roll in listOf(-20.0, 0.0, 20.0)) {
                        for (path in paths) {
                            val arrows = glassesRouteChevrons(path, pitch, roll, 70.0, imageAspect,
                                width, height, minimumGapPixels = 28.0, maxArrows = 10)
                            assertTrue("the public limit stays at two", arrows.size <= 2)
                            renderedArrowCount += arrows.size
                            for (arrow in arrows) for (point in listOf(arrow.tip, arrow.left, arrow.right)) {
                                val fractionDown = (point.y + 1.0) / 2.0
                                assertTrue("arrow enters bottom quarter: ${width}x$height pitch=$pitch roll=$roll y=$fractionDown",
                                    fractionDown <= 0.73 + 0.0000001)
                                assertTrue(point.visible)
                            }
                        }
                    }
                }
            }
            // An implementation that hides all route arrows would trivially pass the bounds.
            assertTrue("road ahead still renders on ${width}x$height", renderedArrowCount > 0)
            assertTrue(glassesRouteChevrons(paths.first(), 0.0, 0.0, 70.0, 4.0 / 3.0,
                width, height).isNotEmpty())
        }
    }

    @Test fun `short route never falls back to a footprint at the wearers feet`() {
        val shortRoute = listOf(Vec3.ZERO, Vec3(0f, 0f, -8f))
        for ((width, height) in listOf(1600.0 to 900.0, 900.0 to 900.0)) {
            assertTrue(glassesRouteChevrons(shortRoute, 0.0, 0.0, 70.0, 4.0 / 3.0,
                width, height).isEmpty())
        }
    }

    @Test fun `explicit horizon alignment removes looking down during startup from floor projection`() {
        // The IMU zeroed while looking down 10 degrees. After raising the head to
        // the horizon, it reports +10 until an explicit camera-horizon alignment.
        val reportedAtHorizon = 10.0
        val floorAhead = Vec3(0f, 0f, -9f)
        fun viewportPoint(pitch: Double, roll: Double = 0.0): GlassesScreenPoint =
            cropGlassesPoint(projectGlassesPoint(floorAhead, pitch, roll, 70.0, 4.0 / 3.0),
                4.0 / 3.0, 16.0 / 9.0)!!
        val withoutHorizon = viewportPoint(reportedAtHorizon)
        val corrected = viewportPoint(alignedGlassesTilt(reportedAtHorizon, reportedAtHorizon)!!)
        val actuallyLevel = viewportPoint(0.0)
        assertTrue("old startup reference pushes ground underfoot", (withoutHorizon.y + 1.0) / 2.0 > 0.95)
        assertEquals(actuallyLevel.x, corrected.x, 0.0000001)
        assertEquals(actuallyLevel.y, corrected.y, 0.0000001)
        assertTrue("9m floor belongs on the road ahead", (corrected.y + 1.0) / 2.0 in 0.70..0.74)
    }

    @Test fun `horizon recentering preserves head pitch and roll changes after alignment`() {
        val neutralPitch = 10.0
        val neutralRoll = -7.0
        fun corrected(rawPitch: Double, rawRoll: Double) = projectGlassesPoint(
            Vec3(2f, 0f, -12f), alignedGlassesTilt(rawPitch, neutralPitch)!!,
            alignedGlassesTilt(rawRoll, neutralRoll)!!, 70.0, 4.0 / 3.0,
        )!!
        val level = corrected(10.0, -7.0)
        val headDown = corrected(0.0, -7.0)
        val headUp = corrected(20.0, -7.0)
        assertTrue(headDown.y < level.y)
        assertTrue(headUp.y > level.y)
        val expectedRolled = projectGlassesPoint(Vec3(2f, 0f, -12f), 0.0, 12.0, 70.0, 4.0 / 3.0)!!
        val rolled = corrected(10.0, 5.0)
        assertEquals(expectedRolled.x, rolled.x, 0.0000001)
        assertEquals(expectedRolled.y, rolled.y, 0.0000001)
    }

    @Test fun `horizon alignment crosses angle wrap continuously and rejects missing references`() {
        assertEquals(2.0, alignedGlassesTilt(-179.0, 179.0)!!, 0.0000001)
        assertEquals(-2.0, alignedGlassesTilt(179.0, -179.0)!!, 0.0000001)
        assertEquals(0.0, alignedGlassesTilt(370.0, 10.0)!!, 0.0000001)
        assertEquals(5.0, alignedGlassesTilt(-715.0, 720.0)!!, 0.0000001)
        assertNull(alignedGlassesTilt(null, 10.0))
        assertNull(alignedGlassesTilt(10.0, null))
        assertNull(alignedGlassesTilt(Double.NaN, 10.0))
        assertNull(alignedGlassesTilt(10.0, Double.POSITIVE_INFINITY))
        assertNull(alignedGlassesTilt(Double.NEGATIVE_INFINITY, 0.0))
    }
}
