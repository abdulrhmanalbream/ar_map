package com.sarab.vision.vision

import org.junit.Assert.*
import org.junit.Test

class VisionLabelLayoutTest {
    private fun overlaps(a: VisionScreenBox, b: VisionScreenBox) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Test fun threeDetectionsAtSamePositionReceiveSeparatePortraitLabels() {
        val detections = List(3) { VisionScreenBox(110f, 280f, 190f, 330f) }
        val labels = layoutVisionLabels(detections, 360f, 800f, 220f, 80f, 76f, 130f, 8f)
        assertEquals(3, labels.size)
        for (i in labels.indices) for (j in i + 1 until labels.size) assertFalse(overlaps(labels[i], labels[j]))
        assertTrue(labels.all { it.left >= 0 && it.right <= 360 && it.top >= 76 && it.bottom <= 670 })
    }

    @Test fun nearbyChoiceReflowsWhenItWouldBlockThreeLabelsThatFit() {
        // Only three horizontal slots fit; a centered first choice obstructs two.
        val detections = List(3) { VisionScreenBox(225f, 90f, 245f, 110f) }
        val labels = layoutVisionLabels(detections, 680f, 286f, 220f, 80f, 76f, 130f, 8f)
        assertEquals(3, labels.size)
        for (i in labels.indices) for (j in i + 1 until labels.size) assertFalse(overlaps(labels[i], labels[j]))
        assertTrue(labels.all { it.top == 76f && it.bottom == 156f })
    }

    @Test fun separatedDetectionsKeepInputOrderAndNearbyPlacement() {
        val labels = layoutVisionLabels(listOf(VisionScreenBox(600f, 200f, 700f, 260f),
            VisionScreenBox(20f, 200f, 120f, 260f)), 900f, 700f, 220f, 80f, 76f, 130f, 8f)
        assertTrue(labels[0].left > labels[1].right)
        assertTrue(labels[0].bottom <= 200f && labels[1].bottom <= 200f)
    }

    @Test fun insufficientSpaceStillReturnsClampedRectanglesWithoutClaimingSeparation() {
        val labels = layoutVisionLabels(List(3) { VisionScreenBox(-100f, 600f, 900f, 800f) },
            160f, 180f, 220f, 80f, 76f, 130f, 8f)
        assertEquals(3, labels.size)
        assertTrue(labels.all { it.left >= 0 && it.right <= 160 && it.top >= 0 && it.bottom <= 180 })
    }
}
