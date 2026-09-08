package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesProjectionTest {
    private val origin = LatLng(24.5, 39.5)
    private val north = stepAlongBearing(origin, 0.0, 30.0)

    private fun project(point: Vec3, pitch: Double = 0.0, roll: Double = 0.0, aspect: Double = 1.0) =
        projectGlassesPoint(point, pitch, roll, horizontalFovDegrees = 70.0, aspectRatio = aspect)

    @Test
    fun `head turn changes route placement independently of any phone pose`() {
        val route = listOf(origin, north)
        val facing = routeGroundPath(origin, route, headingDeg = 0.0).last()
        val turned = routeGroundPath(origin, route, headingDeg = 25.0).last()
        assertEquals(0.0, project(facing)!!.x, 0.0001)
        assertTrue(project(turned)!!.x < -0.5)
    }

    @Test
    fun `head yaw wraps north without a jump`() {
        val left = glassesTargetDirection(origin, north, 359.0)!!
        val right = glassesTargetDirection(origin, north, 1.0)!!
        assertEquals(1.0, left.relativeDegrees, 0.001)
        assertEquals(-1.0, right.relativeDegrees, 0.001)
        assertEquals(left, glassesTargetDirection(origin, north, -1.0))
        assertEquals(right, glassesTargetDirection(origin, north, 721.0))
    }

    @Test
    fun `looking up moves a level target down and looking down raises ground`() {
        val level = Vec3(0f, 1.6f, -10f)
        assertEquals(0.0, project(level)!!.y, 0.0001)
        assertTrue(project(level, pitch = 20.0)!!.y > 0.0)
        val ground = Vec3(0f, 0f, -10f)
        assertTrue(project(ground, pitch = -10.0)!!.y < project(ground)!!.y)
    }

    @Test
    fun `clockwise head roll rotates the world counterclockwise`() {
        val right = Vec3(2f, 1.6f, -10f)
        val rolled = project(right, roll = 90.0)!!
        assertEquals(0.0, rolled.x, 0.0001)
        assertTrue(rolled.y < 0.0)
        val up = project(Vec3(0f, 3.6f, -10f), roll = 90.0)!!
        assertTrue(up.x < 0.0)
        assertEquals(0.0, up.y, 0.0001)
    }

    @Test
    fun `behind and near plane points never reappear from tangent periodicity`() {
        assertNull(project(Vec3(0f, 1.6f, 10f)))
        assertNull(project(Vec3(0f, 1.6f, 0f)))
        assertNull(project(Vec3(0f, 1.6f, -0.01f)))
        assertNull(project(Vec3(0f, 1.6f, -10f), pitch = 180.0))
        val direction = glassesTargetDirection(origin, stepAlongBearing(origin, 180.0, 20.0), 0.0)!!
        assertEquals(180.0, kotlin.math.abs(direction.relativeDegrees), 0.001)
    }

    @Test
    fun `aspect ratio gives equal pixel displacement for equal horizontal and vertical angles`() {
        val point = Vec3(2f, 3.6f, -10f)
        val wide = project(point, aspect = 2.0)!!
        val portrait = project(point, aspect = 0.5)!!
        assertEquals(wide.x, portrait.x, 0.0001)
        assertEquals(wide.x * 2000.0, -wide.y * 1000.0, 0.001)
        assertEquals(portrait.x * 500.0, -portrait.y * 1000.0, 0.001)
    }

    @Test
    fun `invalid coordinates sensors and camera inputs are rejected`() {
        val point = Vec3(0f, 0f, -10f)
        assertNull(project(Vec3(Float.NaN, 0f, -10f)))
        assertNull(project(point, pitch = Double.NaN))
        assertNull(project(point, roll = Double.POSITIVE_INFINITY))
        assertNull(project(point, aspect = 0.0))
        assertNull(project(point, aspect = Double.NaN))
        assertNull(projectGlassesPoint(point, 0.0, 0.0, 180.0, 1.0))
        assertNull(glassesTargetDirection(origin, north, Double.NaN))
        assertNull(glassesTargetDirection(LatLng(0.0, 0.0), north, 0.0))
    }

    @Test
    fun `screen segments clip to image edges and break across missing points`() {
        val clipped = clipGlassesSegment(GlassesScreenPoint(-10000.0, 0.0, 5.0), GlassesScreenPoint(10000.0, 0.0, 5.0))!!
        assertEquals(-1.0, clipped.start.x, 0.0001)
        assertEquals(1.0, clipped.end.x, 0.0001)
        assertTrue(clipped.start.visible)
        assertNull(clipGlassesSegment(null, clipped.end))
        assertNull(clipGlassesSegment(GlassesScreenPoint(2.0, 0.0, 2.0), GlassesScreenPoint(3.0, 0.0, 2.0)))
        assertNull(clipGlassesSegment(GlassesScreenPoint(Double.NaN, 0.0, 2.0), clipped.end))
        assertFalse(GlassesScreenPoint(0.0, 1.1, 5.0).visible)
    }
}
