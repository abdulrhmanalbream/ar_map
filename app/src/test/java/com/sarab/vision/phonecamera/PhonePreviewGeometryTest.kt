package com.sarab.vision.phonecamera

import com.sarab.vision.vision.VisionBox
import com.sarab.vision.vision.cropVisionBox
import org.junit.Assert.*
import org.junit.Test

class PhonePreviewGeometryTest {
    private data class Point(val x: Float, val y: Float)

    private fun rotate(point: Point, degrees: Int): Point = when ((degrees + 360) % 360) {
        90 -> Point(-point.y, point.x)
        180 -> Point(-point.x, -point.y)
        270 -> Point(point.y, -point.x)
        else -> point
    }

    @Test fun `texture native transform matches upright analysis for every sensor and display orientation`() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) {
            for ((viewWidth, viewHeight) in listOf(1080 to 2520, 2520 to 1080, 1968 to 2184, 2184 to 1968)) {
                val transform = phonePreviewTransform(960, 720, sensor, display, viewWidth, viewHeight)
                val nativeWidth = if (sensor % 180 == 0) 960 else 720
                val nativeHeight = if (sensor % 180 == 0) 720 else 960
                // Asymmetric interior points catch reversed rotation or unwanted mirroring.
                for (raw in listOf(Point(-211f, -97f), Point(130f, -53f), Point(-71f, 199f))) {
                    val natural = rotate(raw, sensor)
                    val stretched = Point(natural.x * viewWidth / nativeWidth, natural.y * viewHeight / nativeHeight)
                    val corrected = rotate(Point(stretched.x * transform.scaleX, stretched.y * transform.scaleY),
                        transform.rotationDegrees)
                    val upright = rotate(raw, backCameraRotation(sensor, display))
                    assertEquals("sensor=$sensor display=$display x", upright.x * transform.imageScale, corrected.x, 0.001f)
                    assertEquals("sensor=$sensor display=$display y", upright.y * transform.imageScale, corrected.y, 0.001f)
                }
            }
        }
    }

    @Test fun `portrait center crop agrees with marker mapping and fills the viewport`() {
        val transform = phonePreviewTransform(1440, 1080, 90, 0, 1080, 2400)
        val box = VisionBox(250.0, 400.0, 750.0, 600.0)
        // Analysis is 960x720 raw, therefore 720x960 upright. Both streams share 4:3 FOV.
        val actual = cropVisionBox(box, 720, 960, 1080f, 2400f)!!
        assertEquals(transform.imageLeft + 0.4f * transform.uprightWidth * transform.imageScale, actual.left, 0.001f)
        assertEquals(transform.imageTop + 0.25f * transform.uprightHeight * transform.imageScale, actual.top, 0.001f)
        assertEquals(transform.imageLeft + 0.6f * transform.uprightWidth * transform.imageScale, actual.right, 0.001f)
        assertEquals(transform.imageTop + 0.75f * transform.uprightHeight * transform.imageScale, actual.bottom, 0.001f)
        assertEquals(-360f, transform.imageLeft, 0.001f)
        assertEquals(0f, transform.imageTop, 0.001f)
        assertEquals(2400f, transform.uprightHeight * transform.imageScale, 0.001f)
    }

    @Test fun `reverse landscape center crops without stretching or mirroring`() {
        val transform = phonePreviewTransform(960, 720, 90, 270, 2400, 1080)
        assertEquals(960, transform.uprightWidth)
        assertEquals(720, transform.uprightHeight)
        assertEquals(2.5f, transform.imageScale, 0.001f)
        assertEquals(0f, transform.imageLeft, 0.001f)
        assertEquals(-360f, transform.imageTop, 0.001f)
        assertTrue(transform.scaleX > 0f && transform.scaleY > 0f)
        assertEquals(-270, transform.rotationDegrees)
    }

    @Test fun `stream aspect matching rejects equally large but differently cropped streams`() {
        assertTrue(sameCameraAspect(CameraFrameSize(960, 720), CameraFrameSize(1440, 1080)))
        assertTrue(sameCameraAspect(CameraFrameSize(640, 360), CameraFrameSize(1920, 1080)))
        assertFalse(sameCameraAspect(CameraFrameSize(960, 720), CameraFrameSize(1280, 720)))
        assertFalse(sameCameraAspect(CameraFrameSize(960, 720), CameraFrameSize(720, 960)))
    }

    @Test fun `zero viewport and unsupported angles cannot produce nonfinite matrices`() {
        assertThrows(IllegalArgumentException::class.java) { phonePreviewTransform(960, 720, 90, 0, 0, 2400) }
        assertThrows(IllegalArgumentException::class.java) { phonePreviewTransform(960, 720, 45, 0, 1080, 2400) }
    }
}
