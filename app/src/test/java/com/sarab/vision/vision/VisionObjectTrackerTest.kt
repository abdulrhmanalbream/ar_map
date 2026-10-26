package com.sarab.vision.vision

import org.junit.Assert.*
import org.junit.Test

class VisionObjectTrackerTest {
    @Test fun followsMeasuredTranslationAcrossSuccessiveFrames() {
        val captured = frame()
        val tracker = tracker(captured)
        assertBox(tracker.update(captured).single(), ObjectInView())
        for (step in 1..4) {
            val objectInView = ObjectInView(cx = 48.0 + step * 3, cy = 42.0 + step * 2)
            val result = tracker.update(frame(at = 100L + step * 250, objects = listOf(objectInView))).single()
            assertBox(result, objectInView)
            assertEquals(100L + step * 250, result.atElapsedMs)
            assertEquals(5L, result.sessionId)
            assertTrue(result.confidence >= .90)
        }
    }

    @Test fun followsModestUniformScaleWithoutTemplateDrift() {
        val captured = frame()
        val tracker = tracker(captured)
        tracker.update(captured)
        for (step in 1..3) {
            val objectInView = ObjectInView(scale = 1.0 + step * .05)
            val result = tracker.update(frame(at = 100L + step * 250, objects = listOf(objectInView))).single()
            assertBox(result, objectInView, tolerancePixels = 2.5)
        }
    }

    @Test fun normalizedCorrelationToleratesSmallExposureChange() {
        val captured = frame()
        val tracker = tracker(captured)
        tracker.update(captured)
        val target = ObjectInView(cx = 51.0, cy = 44.0)
        assertBox(tracker.update(frame(at = 350, objects = listOf(target), brightness = 14)).single(), target)
    }

    @Test fun disappearanceLosesTargetAndNeverReusesStationaryBox() {
        val captured = frame()
        val tracker = tracker(captured)
        assertEquals(1, tracker.update(captured).size)
        assertTrue(tracker.update(frame(at = 350, objects = emptyList())).isEmpty())
        assertTrue(tracker.update(frame(at = 600)).isEmpty())
    }

    @Test fun substantialOcclusionRejectsMatch() {
        val captured = frame()
        val tracker = tracker(captured)
        tracker.update(captured)
        assertTrue(tracker.update(frame(at = 350, occluded = true)).isEmpty())
    }

    @Test fun lowTextureCannotAnchorLabel() {
        val blank = frame(objects = emptyList())
        val tracker = tracker(blank)
        assertTrue(tracker.eligibleItemIds.isEmpty())
        assertTrue(tracker.update(blank).isEmpty())
    }

    @Test fun smallTargetsAreReportedIneligibleForPixelTracking() {
        val tracker = VisionObjectTracker(frame(), listOf(
            VisionTrackingTarget("landmark", box(ObjectInView())),
            VisionTrackingTarget("distant", VisionBox(400.0, 400.0, 450.0, 450.0)),
        ))
        assertEquals(setOf("landmark"), tracker.eligibleItemIds)
    }

    @Test fun LostEligibleTargetCannotBecomeAnUntrackedFallback() {
        val tracker = tracker(frame())
        tracker.update(frame())
        assertTrue(tracker.update(frame(at = 350, objects = emptyList())).isEmpty())
        assertEquals(setOf("landmark"), tracker.eligibleItemIds)
        tracker.clear()
        assertEquals(setOf("landmark"), tracker.eligibleItemIds)
    }

    @Test fun repeatingObjectsAreAmbiguousEvenWithStrongCorrelation() {
        val repeated = frame(objects = listOf(ObjectInView(), ObjectInView(cx = 96.0)))
        val tracker = tracker(repeated)
        assertTrue(tracker.update(repeated).isEmpty())
    }

    @Test fun largeJumpCannotAttachLabelToDistantObject() {
        val captured = frame()
        val tracker = tracker(captured)
        tracker.update(captured)
        assertTrue(tracker.update(frame(at = 350, objects = listOf(ObjectInView(cx = 108.0)))).isEmpty())
    }

    @Test fun directDelayedMatchNeedsTwoFreshStrongFrames() {
        val tracker = tracker(frame())
        val moved = ObjectInView(cx = 54.0, cy = 46.0)
        assertTrue(tracker.update(frame(at = 3_100, objects = listOf(moved))).isEmpty())
        assertBox(tracker.update(frame(at = 3_350, objects = listOf(moved))).single(), moved)
    }

    @Test fun delayedAmbiguousSceneNeverGetsConfirmed() {
        val tracker = tracker(frame())
        val ambiguous = listOf(ObjectInView(), ObjectInView(cx = 96.0))
        assertTrue(tracker.update(frame(at = 3_100, objects = ambiguous)).isEmpty())
        assertTrue(tracker.update(frame(at = 3_350, objects = ambiguous)).isEmpty())
    }

    @Test fun originalFrameHistoryCanBridgeDelayedResultChronologically() {
        val tracker = tracker(frame())
        var last = emptyList<VisionTrackedBox>()
        for (step in 1..12) {
            val objectInView = ObjectInView(cx = 48.0 + step, cy = 42.0)
            last = tracker.update(frame(at = 100L + step * 250, objects = listOf(objectInView)))
        }
        assertBox(last.single(), ObjectInView(cx = 60.0))
        assertEquals(3_100L, last.single().atElapsedMs)
    }

    @Test fun sessionChangeAndExplicitClearInvalidateAllTracking() {
        val first = frame()
        val tracker = tracker(first)
        tracker.update(first)
        assertTrue(tracker.update(frame(at = 350).copy(sessionId = 6)).isEmpty())
        assertTrue(tracker.update(frame(at = 600)).isEmpty())
        val another = tracker(first)
        another.clear()
        assertTrue(another.update(frame(at = 350)).isEmpty())
    }

    @Test fun longGapCannotReusePreviousTrackedPosition() {
        val first = frame()
        val tracker = tracker(first)
        tracker.update(first)
        assertTrue(tracker.update(frame(at = 1_601)).isEmpty())
        assertTrue(tracker.update(frame(at = 1_850)).isEmpty())
    }

    @Test fun overlyDelayedFirstResponseRequiresNewRecognition() {
        assertTrue(tracker(frame()).update(frame(at = 8_101)).isEmpty())
    }

    @Test fun duplicateTimestampDoesNotRenewTrackingFreshness() {
        val captured = frame()
        val tracker = tracker(captured)
        assertEquals(1, tracker.update(captured).size)
        assertTrue(tracker.update(frame()).isEmpty())
        assertEquals(350L, tracker.update(frame(at = 350)).single().atElapsedMs)
    }

    @Test fun backwardsTimestampAndShapeChangeFailClosed() {
        val tracker = tracker(frame())
        tracker.update(frame(at = 350))
        assertTrue(tracker.update(frame(at = 200)).isEmpty())
        val second = tracker(frame())
        assertTrue(second.update(VisionTrackingFrame(96, 128, ByteArray(96 * 128), 350, 5)).isEmpty())
    }

    @Test fun untrustedFrameAndBoxShapesCannotReachMatching() {
        val invalidFrame = frame().copy(width = 161)
        assertTrue(tracker(invalidFrame).update(invalidFrame).isEmpty())
        assertTrue(tracker(frame().copy(sessionId = 0)).update(frame()).isEmpty())
        val invalid = VisionObjectTracker(frame(), listOf(
            VisionTrackingTarget("nan", VisionBox(0.0, Double.NaN, 500.0, 500.0)),
            VisionTrackingTarget("tiny", VisionBox(1.0, 1.0, 2.0, 2.0)),
            VisionTrackingTarget("outside", VisionBox(0.0, -1.0, 500.0, 500.0)),
        ))
        assertTrue(invalid.update(frame()).isEmpty())
    }

    @Test fun constructorOwnsReferenceSamplesRatherThanCallerByteArray() {
        val original = frame()
        val tracker = tracker(original)
        original.luma.fill(0)
        assertBox(tracker.update(frame(at = 350)).single(), ObjectInView())
    }

    @Test fun recognitionAgeIsBoundedEvenWhileFramesKeepArriving() {
        val tracker = tracker(frame())
        tracker.update(frame())
        for (second in 1..30) assertEquals(1, tracker.update(frame(at = 100L + second * 1_000)).size)
        assertTrue(tracker.update(frame(at = 31_100)).isEmpty())
    }

    private fun tracker(captured: VisionTrackingFrame) = VisionObjectTracker(captured,
        listOf(VisionTrackingTarget("landmark", box(ObjectInView()))))

    private fun assertBox(result: VisionTrackedBox, expected: ObjectInView, tolerancePixels: Double = 1.6) {
        val reference = box(expected)
        assertEquals("landmark", result.itemId)
        assertEquals(reference.xMin, result.box.xMin, tolerancePixels / WIDTH * 1000)
        assertEquals(reference.xMax, result.box.xMax, tolerancePixels / WIDTH * 1000)
        assertEquals(reference.yMin, result.box.yMin, tolerancePixels / HEIGHT * 1000)
        assertEquals(reference.yMax, result.box.yMax, tolerancePixels / HEIGHT * 1000)
    }

    private fun box(target: ObjectInView) = VisionBox(
        (target.cy - 14 * target.scale) / HEIGHT * 1000,
        (target.cx - 16 * target.scale) / WIDTH * 1000,
        (target.cy + 14 * target.scale) / HEIGHT * 1000,
        (target.cx + 16 * target.scale) / WIDTH * 1000,
    )

    private data class ObjectInView(val cx: Double = 48.0, val cy: Double = 42.0, val scale: Double = 1.0)

    /** Seeded 4px texture blocks simulate distinguishable details; no image/file dependencies. */
    private fun frame(at: Long = 100, objects: List<ObjectInView> = listOf(ObjectInView()),
                      brightness: Int = 0, occluded: Boolean = false): VisionTrackingFrame {
        val pixels = ByteArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            var color = 24
            objects.forEach { target ->
                val localX = (x - target.cx) / target.scale + 16
                val localY = (y - target.cy) / target.scale + 14
                if (localX >= 0 && localX < 32 && localY >= 0 && localY < 28) {
                    val column = localX.toInt() / 4
                    val row = localY.toInt() / 4
                    val hash = (column * 73856093) xor (row * 19349663) xor 83492791
                    color = 40 + (hash ushr 3) % 180
                    if (occluded && localX < 16) color = 24
                }
            }
            (color + brightness).coerceIn(0, 255).toByte()
        }
        return VisionTrackingFrame(WIDTH, HEIGHT, pixels, at, 5)
    }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 96
    }
}
