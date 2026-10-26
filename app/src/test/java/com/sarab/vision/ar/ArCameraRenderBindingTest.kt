package com.sarab.vision.ar

import org.junit.Assert.*
import org.junit.Test

class ArCameraRenderBindingTest {
    @Test fun `late session gets stored viewport before first update without another surface callback`() {
        val binding = ArCameraRenderBinding()
        val events = mutableListOf<String>()
        val session = Any()
        assertTrue(binding.apply(session, 7, 1080, 2520, 0,
            { events += "texture" }, { events += "geometry:${it.rotation}:${it.width}:${it.height}" }))
        events += "update"
        assertEquals(listOf("texture", "geometry:0:1080:2520", "update"), events)
    }

    @Test fun `repeated frames do not invalidate the same camera texture`() {
        val binding = ArCameraRenderBinding()
        val session = Any()
        var textureCalls = 0
        var geometryCalls = 0
        repeat(100) { frame ->
            assertEquals(frame == 0, binding.apply(session, 8, 1080, 2520, 0,
                { textureCalls++ }, { geometryCalls++ }))
        }
        assertEquals(1, textureCalls)
        assertEquals(1, geometryCalls)
    }

    @Test fun `new session at identical size still receives texture and geometry`() {
        val binding = ArCameraRenderBinding()
        var textureCalls = 0
        var geometryCalls = 0
        repeat(2) {
            assertTrue(binding.apply(Any(), 9, 1080, 2520, 0, { textureCalls++ }, { geometryCalls++ }))
        }
        assertEquals(2, textureCalls)
        assertEquals(2, geometryCalls)
    }

    @Test fun `recreated context rebinds even when GL reuses the integer texture name`() {
        val binding = ArCameraRenderBinding()
        val session = Any()
        var textureCalls = 0
        repeat(2) {
            binding.reset()
            assertTrue(binding.apply(session, 3, 1080, 2520, 0, { textureCalls++ }, {}))
        }
        assertEquals(2, textureCalls)
    }

    @Test fun `all rotations and fold size changes update projection geometry without rebinding texture`() {
        val binding = ArCameraRenderBinding()
        val session = Any()
        var textureCalls = 0
        val geometries = mutableListOf<ArDisplayGeometry>()
        for (degrees in listOf(0, 90, 180, 270)) {
            assertTrue(binding.apply(session, 2, 1080, 2520, degrees, { textureCalls++ }, geometries::add))
        }
        assertTrue(binding.apply(session, 2, 1968, 2184, 270, { textureCalls++ }, geometries::add))
        assertEquals(listOf(0, 1, 2, 3, 3), geometries.map { it.rotation })
        assertEquals(ArDisplayGeometry(3, 1968, 2184), geometries.last())
        assertEquals(1, textureCalls)
    }

    @Test fun `failed geometry binding retries without invalidating a successful texture assignment`() {
        val binding = ArCameraRenderBinding()
        val session = Any()
        var textureCalls = 0
        assertThrows(IllegalStateException::class.java) {
            binding.apply(session, 2, 1080, 2520, 0, { textureCalls++ }, { error("temporary") })
        }
        assertTrue(binding.apply(session, 2, 1080, 2520, 0, { textureCalls++ }, {}))
        assertEquals(1, textureCalls)
    }

    @Test fun `invalid geometry is rejected before touching the session`() {
        val binding = ArCameraRenderBinding()
        var touched = false
        for ((width, degrees) in listOf(0 to 0, 1080 to 45)) {
            assertThrows(IllegalArgumentException::class.java) {
                binding.apply(Any(), 2, width, 2520, degrees, { touched = true }, { touched = true })
            }
        }
        assertFalse(touched)
    }
}
