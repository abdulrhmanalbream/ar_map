package com.sarab.vision.ui

import com.sarab.vision.vision.VisionBox
import org.junit.Assert.*
import org.junit.Test

class VisionPhotoPolicyTest {
    @Test fun veryWideAndTallPhotosAreSampledBeforeAllocation() {
        assertEquals(8, visionPhotoSampleSize(20_000, 100))
        assertEquals(8, visionPhotoSampleSize(100, 20_000))
        assertEquals(1, visionPhotoSampleSize(1280, 960))
        val sample = visionPhotoSampleSize(Int.MAX_VALUE, 1)
        assertTrue(Int.MAX_VALUE.toLong() / sample <= VISION_PHOTO_EDGE * 2L)
    }

    @Test fun invalidBoundsNeverReachDecode() {
        assertThrows(IllegalArgumentException::class.java) { visionPhotoSampleSize(0, 200) }
        assertThrows(IllegalArgumentException::class.java) { visionPhotoSampleSize(100, -2) }
    }

    @Test fun allExifOrientationsPlaceTopLeftAndTopRightCorrectly() {
        val expected = listOf(
            listOf(0f, 0f, 1f, 0f), listOf(1f, 0f, 0f, 0f), listOf(1f, 1f, 0f, 1f), listOf(0f, 1f, 1f, 1f),
            listOf(0f, 0f, 0f, 1f), listOf(1f, 0f, 1f, 1f), listOf(1f, 1f, 1f, 0f), listOf(0f, 1f, 0f, 0f),
        )
        for (orientation in 1..8) {
            val m = visionPhotoOrientation(orientation)
            val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
                .map { (x, y) -> m[0] * x + m[1] * y to m[2] * x + m[3] * y }
            val left = corners.minOf { it.first }; val top = corners.minOf { it.second }
            val first = corners.take(2).flatMap { listOf(it.first - left, it.second - top) }
            assertEquals("EXIF $orientation", expected[orientation - 1], first)
        }
    }

    @Test fun fitMappingKeepsEntireWidePhotoAndItsBoxesInsideLetterbox() {
        val full = fitVisionPhotoBox(VisionBox(0.0, 0.0, 1000.0, 1000.0), 400, 200, 300f, 400f)!!
        assertEquals(0f, full.left, .01f); assertEquals(125f, full.top, .01f)
        assertEquals(300f, full.right, .01f); assertEquals(275f, full.bottom, .01f)
        val middle = fitVisionPhotoBox(VisionBox(250.0, 250.0, 750.0, 750.0), 400, 200, 300f, 400f)!!
        assertEquals(75f, middle.left, .01f); assertEquals(162.5f, middle.top, .01f)
    }

    @Test fun fitMappingHandlesPortraitPhotoAndRejectsInvalidBoxes() {
        val full = fitVisionPhotoBox(VisionBox(0.0, 0.0, 1000.0, 1000.0), 100, 200, 400f, 300f)!!
        assertEquals(125f, full.left, .01f); assertEquals(275f, full.right, .01f)
        assertEquals(0f, full.top, .01f); assertEquals(300f, full.bottom, .01f)
        assertNull(fitVisionPhotoBox(VisionBox(0.0, 0.0, 1001.0, 1000.0), 100, 200, 400f, 300f))
    }
}
