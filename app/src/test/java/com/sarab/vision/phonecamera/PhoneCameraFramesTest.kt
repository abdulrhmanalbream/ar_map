package com.sarab.vision.phonecamera

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PhoneCameraFramesTest {
    private fun plane(values: IntArray, row: Int, pixel: Int = 1) =
        CameraPlane(ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() }), row, pixel)

    private fun grayPixels(rotation: Int): ArgbCameraFrame = PhoneYuvConverter().convert(3, 2,
        CameraCrop(0, 0, 3, 2), plane(intArrayOf(10, 20, 30, 40, 50, 60), 3),
        plane(intArrayOf(128, 128), 2), plane(intArrayOf(128, 128), 2), rotation)

    @Test fun `back sensor rotation follows display orientation including reverse landscape`() {
        assertEquals(90, backCameraRotation(90, 0))
        assertEquals(0, backCameraRotation(90, 90))
        assertEquals(270, backCameraRotation(90, 180))
        assertEquals(180, backCameraRotation(90, 270))
        assertEquals(0, backCameraRotation(270, 270))
        assertEquals(270, backCameraRotation(0, 90))
    }

    @Test fun `asymmetric raw image rotates clockwise without reflection or lost pixels`() {
        val expectations = mapOf(0 to intArrayOf(10, 20, 30, 40, 50, 60),
            90 to intArrayOf(40, 10, 50, 20, 60, 30),
            180 to intArrayOf(60, 50, 40, 30, 20, 10),
            270 to intArrayOf(30, 60, 20, 50, 10, 40))
        expectations.forEach { (rotation, expected) ->
            val frame = grayPixels(rotation)
            assertArrayEquals(expected, frame.pixels.map { it and 255 }.toIntArray())
            assertEquals(if (rotation % 180 == 0) 3 else 2, frame.width)
            assertEquals(if (rotation % 180 == 0) 2 else 3, frame.height)
            assertTrue(frame.pixels.all { it ushr 24 == 255 })
        }
    }

    @Test fun `camera YUV keeps full range black white and known primary colors`() {
        fun rgb(y: Int, u: Int, v: Int) = PhoneYuvConverter().convert(1, 1, CameraCrop(0, 0, 1, 1),
            plane(intArrayOf(y), 1), plane(intArrayOf(u), 1), plane(intArrayOf(v), 1), 0).pixels[0]
        assertEquals(0xff000000.toInt(), rgb(0, 128, 128))
        assertEquals(0xffffffff.toInt(), rgb(255, 128, 128))
        val red = rgb(76, 85, 255)
        assertTrue((red shr 16 and 255) >= 253 && (red shr 8 and 255) <= 2 && (red and 255) <= 2)
        val blue = rgb(29, 255, 107)
        assertTrue((blue shr 16 and 255) <= 2 && (blue shr 8 and 255) <= 2 && (blue and 255) >= 253)
    }

    @Test fun `odd crop origins select chroma from original image coordinates`() {
        val y = plane(intArrayOf(76,76,150,150, 76,76,150,150, 29,29,255,255, 29,29,255,255), 4)
        val frame = PhoneYuvConverter().convert(4, 4, CameraCrop(1, 1, 2, 2), y,
            plane(intArrayOf(85,44,255,128), 2), plane(intArrayOf(255,21,107,128), 2), 0)
        val (red, green, blue, white) = frame.pixels.toList()
        assertTrue((red shr 16 and 255) >= 253 && (red and 255) <= 2)
        assertTrue((green shr 8 and 255) >= 253 && (green shr 16 and 255) <= 2)
        assertTrue((blue and 255) >= 253 && (blue shr 16 and 255) <= 2)
        assertEquals(0xffffffff.toInt(), white)
    }

    @Test fun `shared interleaved chroma and padded luma respect positions strides and limits`() {
        val yBuffer = ByteBuffer.wrap(byteArrayOf(99, 10,88,20,88,30,88,77,77, 40,88,50,88,60))
        yBuffer.position(1)
        val uv = ByteBuffer.wrap(byteArrayOf(99, 128.toByte(),128.toByte(),128.toByte(),128.toByte()))
        val u = uv.duplicate().apply { position(1); limit(4) }
        val v = uv.duplicate().apply { position(2) }
        val frame = PhoneYuvConverter().convert(3, 2, CameraCrop(0, 0, 3, 2), CameraPlane(yBuffer, 8, 2),
            CameraPlane(u, 4, 2), CameraPlane(v, 4, 2), 0)
        assertArrayEquals(intArrayOf(10,20,30,40,50,60), frame.pixels.map { it and 255 }.toIntArray())
        assertEquals(1, yBuffer.position())
        assertEquals(1, u.position())
        assertEquals(2, v.position())
    }

    @Test fun `output bounds both portrait and landscape without upscaling`() {
        assertEquals(CameraFrameSize(960, 540), boundedCameraSize(1920, 1080, 0))
        assertEquals(CameraFrameSize(540, 960), boundedCameraSize(1920, 1080, 90))
        assertEquals(CameraFrameSize(540, 960), boundedCameraSize(1080, 1920, 0))
        assertEquals(CameraFrameSize(240, 320), boundedCameraSize(320, 240, 270))
        assertEquals(CameraFrameSize(640, 480), boundedCameraSize(1280, 960, 0, 640))
    }

    @Test fun `downsampling preserves crop and avoids padding pixels`() {
        val y = plane(IntArray(8 * 4) { it }, 8)
        val neutral = plane(IntArray(4 * 2) { 128 }, 4)
        val frame = PhoneYuvConverter().convert(8, 4, CameraCrop(2, 0, 4, 4), y, neutral, neutral, 0, 2)
        assertEquals(CameraFrameSize(2, 2), CameraFrameSize(frame.width, frame.height))
        assertArrayEquals(intArrayOf(11, 13, 27, 29), frame.pixels.map { it and 255 }.toIntArray())
    }

    @Test fun `truncated planes invalid crops and nonquadrant rotations are rejected`() {
        val gray = plane(IntArray(16) { 128 }, 4)
        val chroma = plane(IntArray(4) { 128 }, 2)
        val cases = listOf<() -> Unit>(
            { PhoneYuvConverter().convert(4,4,CameraCrop(0,0,4,4), plane(IntArray(15),4),chroma,chroma,0) },
            { PhoneYuvConverter().convert(4,4,CameraCrop(3,0,2,2),gray,chroma,chroma,0) },
            { boundedCameraSize(640,480,45) },
            { backCameraRotation(90,1) },
        )
        cases.forEach { call ->
            try { call(); fail("Invalid camera frame must not be read") } catch (_: IllegalArgumentException) { }
        }
    }
}
