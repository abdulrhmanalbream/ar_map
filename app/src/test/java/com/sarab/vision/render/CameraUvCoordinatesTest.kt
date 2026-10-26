package com.sarab.vision.render

import org.junit.Assert.*
import org.junit.Test

class CameraUvCoordinatesTest {
    private val full = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    @Test fun `first real frame computes UVs even when display geometry flag is false`() {
        val uv = CameraUvCoordinates()
        var calls = 0
        assertTrue(uv.update(1, false) { positions, texture ->
            calls++
            assertEquals(0, positions.position())
            assertEquals(8, positions.remaining())
            texture.put(full)
        })
        assertEquals(1, calls)
        assertEquals(1f, uv.texture.get(6), 0f)
        assertEquals(0, uv.texture.position())
    }

    @Test fun `timestamp zero neither draws nor consumes initial coordinate refresh`() {
        val uv = CameraUvCoordinates()
        assertFalse(uv.update(0, true) { _, _ -> fail("undefined frame must not transform") })
        assertFalse(uv.ready)
        assertTrue(uv.update(1, false) { _, output -> output.put(full) })
    }

    @Test fun `cached frame preserves valid UVs and rewinds client buffers`() {
        val uv = CameraUvCoordinates()
        uv.update(2, false) { _, output -> output.put(full) }
        uv.positions.position(8)
        uv.texture.position(8)
        assertTrue(uv.update(2, false) { _, _ -> fail("same frame must not remap") })
        assertEquals(0, uv.positions.position())
        assertEquals(0, uv.texture.position())
    }

    @Test fun `session invalidation and rotation replace old coordinates`() {
        val uv = CameraUvCoordinates()
        uv.update(1, false) { _, output -> output.put(full) }
        uv.invalidate()
        val rotated = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        assertTrue(uv.update(2, false) { _, output -> output.put(rotated) })
        assertEquals(1f, uv.texture.get(1), 0f)
        assertTrue(uv.update(3, true) { _, output -> output.put(full) })
        assertEquals(0f, uv.texture.get(1), 0f)
    }

    @Test fun `zero stale partial and nonfinite transforms never stretch a single pixel across the screen`() {
        for (bad in listOf(FloatArray(8), floatArrayOf(.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f),
                full.copyOf().apply { this[4] = Float.NaN })) {
            val uv = CameraUvCoordinates()
            assertFalse(uv.update(1, false) { _, output -> output.put(bad) })
            assertTrue(uv.update(2, false) { _, output -> output.put(full) })
            assertFalse(uv.update(3, true) { _, _ -> /* native no-op must not reuse stale coordinates */ })
            assertFalse(uv.update(4, false) { _, output -> output.put(full.copyOf(4)) })
        }
    }

    @Test fun `valid center crop remains supplied by ARCore rather than replaced with a hand-made zoom`() {
        val uv = CameraUvCoordinates()
        val cropped = floatArrayOf(.2f, .1f, .8f, .1f, .2f, .9f, .8f, .9f)
        assertTrue(uv.update(1, false) { _, output -> output.put(cropped) })
        assertArrayEquals(cropped, FloatArray(8) { uv.texture.get(it) }, 0f)
    }
}
