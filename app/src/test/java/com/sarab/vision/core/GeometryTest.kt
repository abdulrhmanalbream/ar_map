package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cover the picking and path maths, which are the parts most likely to
 * be subtly wrong and the hardest to debug on a phone: a bad ray silently
 * means "tapping the cube does nothing" with no error anywhere.
 */
class GeometryTest {

    @Test
    fun `normalizing a zero vector does not produce NaN`() {
        val n = Vec3.ZERO.normalized()
        assertTrue("zero-length normalize must not yield NaN", !n.x.isNaN())
        assertEquals(0f, n.length(), 1e-5f)
    }

    @Test
    fun `ray pointing at box hits it`() {
        val ray = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))
        val hit = intersectAabb(ray, center = Vec3(0f, 0f, -5f), halfExtent = 0.5f)
        assertNotNull("ray down -Z should hit a box on -Z", hit)
        assertEquals(4.5f, hit!!, 1e-4f)
    }

    @Test
    fun `ray pointing away from box misses it`() {
        val ray = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 1f))
        val hit = intersectAabb(ray, center = Vec3(0f, 0f, -5f), halfExtent = 0.5f)
        assertNull("ray facing +Z must not hit a box on -Z", hit)
    }

    @Test
    fun `ray offset to the side misses a small box`() {
        val ray = Ray(Vec3(3f, 0f, 0f), Vec3(0f, 0f, -1f))
        val hit = intersectAabb(ray, center = Vec3(0f, 0f, -5f), halfExtent = 0.5f)
        assertNull("ray 3m to the side must miss a 0.5m box", hit)
    }

    @Test
    fun `ray parallel to a slab but outside it misses`() {
        // Travelling along X, well above the box in Y.
        val ray = Ray(Vec3(-10f, 5f, -5f), Vec3(1f, 0f, 0f))
        val hit = intersectAabb(ray, center = Vec3(0f, 0f, -5f), halfExtent = 0.5f)
        assertNull("parallel ray outside the slab must miss", hit)
    }

    @Test
    fun `camera inside the box still registers a hit`() {
        val ray = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))
        val hit = intersectAabb(ray, center = Vec3(0f, 0f, 0f), halfExtent = 1f)
        assertNotNull("standing inside the marker should count as a hit", hit)
        assertEquals(0f, hit!!, 1e-5f)
    }

    @Test
    fun `path runs the requested length in the requested direction`() {
        val start = Vec3(1f, 0f, 2f)
        val points = buildPathPoints(start, Vec3(0f, 0f, -1f), length = 4f, segments = 8)

        assertEquals(9, points.size)
        assertEquals(start, points.first())

        val end = points.last()
        assertEquals(1f, end.x, 1e-4f)
        assertEquals(2f - 4f, end.z, 1e-4f)
        assertEquals("path must stay flat on the floor", 0f, end.y - start.y, 1e-4f)
        assertEquals(4f, (end - start).length(), 1e-4f)
    }

    @Test
    fun `path direction is normalized so length is respected`() {
        // A deliberately non-unit direction must not stretch the path.
        val points = buildPathPoints(Vec3.ZERO, Vec3(0f, 0f, -7f), length = 3f, segments = 4)
        assertEquals(3f, (points.last() - points.first()).length(), 1e-4f)
    }

    @Test
    fun `degenerate path inputs return empty rather than crashing`() {
        assertTrue(buildPathPoints(Vec3.ZERO, Vec3.ZERO, 4f).isEmpty())
        assertTrue(buildPathPoints(Vec3.ZERO, Vec3(0f, 0f, -1f), 4f, segments = 0).isEmpty())
    }
}
