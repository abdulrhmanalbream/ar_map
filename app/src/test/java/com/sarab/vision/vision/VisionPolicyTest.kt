package com.sarab.vision.vision

import org.junit.Assert.*
import org.junit.Test

class VisionPolicyTest {
    @Test fun yawWrapAcrossNorthIsNear() {
        assertTrue(VisionPolicy.nearPose(VisionPose(359.0, 2.0, 4), VisionPose(1.0, 3.0, 4)))
    }
    @Test fun poseRequiresValidSameSessionAndSmallRotation() {
        assertFalse(VisionPolicy.nearPose(VisionPose(0.0, 0.0, 1), VisionPose(0.0, 0.0, 2)))
        assertFalse(VisionPolicy.nearPose(VisionPose(0.0, 0.0), VisionPose(4.0, 0.0)))
        assertFalse(VisionPolicy.nearPose(VisionPose(0.0, 0.0), VisionPose(0.0, 4.0)))
        assertFalse(VisionPolicy.nearPose(VisionPose(Double.NaN, 0.0), VisionPose(0.0, 0.0)))
        assertFalse(VisionPolicy.nearPose(null, VisionPose(0.0, 0.0)))
        assertFalse(VisionPolicy.nearPose(VisionPose(0.0, 0.0), VisionPose(0.0, 0.0, rollDegrees = 4.0)))
    }
    @Test fun stationaryMarkersExpireWithoutMotionEvents() {
        val pose = VisionPose(0.0, 0.0)
        assertTrue(VisionPolicy.markersFresh(1000, 31_000, pose, pose))
        assertFalse(VisionPolicy.markersFresh(1000, 31_001, pose, pose))
    }
    @Test fun futureAndStaleCameraFramesAreRejected() {
        assertFalse(VisionPolicy.freshTimestamp(1001, 1000, VisionPolicy.FRAME_FRESH_MS))
        assertFalse(VisionPolicy.freshTimestamp(1000, 2501, VisionPolicy.FRAME_FRESH_MS))
        assertTrue(VisionPolicy.freshTimestamp(1000, 2500, VisionPolicy.FRAME_FRESH_MS))
    }
    @Test fun centerCropClipsWideImageToPortraitViewport() {
        val full = cropVisionBox(VisionBox(0.0, 0.0, 1000.0, 1000.0), 1920, 1080, 360f, 640f)!!
        assertEquals(0f, full.left, 0.01f)
        assertEquals(0f, full.top, 0.01f)
        assertEquals(360f, full.right, 0.01f)
        assertEquals(640f, full.bottom, 0.01f)
        assertNull(cropVisionBox(VisionBox(100.0, 0.0, 200.0, 100.0), 1920, 1080, 360f, 640f))
    }
    @Test fun normalizedCoordinatesUseYXOrder() {
        val mapped = cropVisionBox(VisionBox(100.0, 200.0, 400.0, 600.0), 1000, 1000, 500f, 500f)!!
        assertEquals(VisionScreenBox(100f, 50f, 300f, 200f), mapped)
    }
    @Test fun malformedBoxesNeverReachOverlay() {
        assertNull(cropVisionBox(VisionBox(500.0, 0.0, 100.0, 100.0), 100, 100, 100f, 100f))
        assertNull(cropVisionBox(VisionBox(0.0, -1.0, 100.0, 100.0), 100, 100, 100f, 100f))
        assertNull(cropVisionBox(VisionBox(0.0, 0.0, 100.0, 100.0), 100, 100, Float.NaN, 100f))
    }
    @Test fun cancellationKeepsSingleSlotUntilWorkerExits() {
        val gate = VisionRequestGate()
        val first = gate.start(0, automatic = true)!!
        gate.invalidate()
        assertFalse(gate.accepts(first))
        assertTrue(gate.busy)
        assertNull(gate.start(13_000, automatic = false))
        gate.finish(first)
        assertNotNull(gate.start(13_000, automatic = true))
    }
    @Test fun oldCompletionCannotReleaseANewerRequest() {
        val gate = VisionRequestGate()
        val first = gate.start(0, automatic = false)!!
        gate.finish(first)
        val second = gate.start(1, automatic = false)!!
        gate.finish(first)
        assertTrue(gate.busy)
        assertTrue(gate.accepts(second))
    }
    @Test fun automaticRequestsWaitSixSecondsAfterAnyCaptureAttempt() {
        val gate = VisionRequestGate()
        gate.finish(gate.start(1000, automatic = false)!!)
        assertNull(gate.start(6_999, automatic = true))
        assertNull(gate.start(999, automatic = true))
        assertNotNull(gate.start(7_000, automatic = true))
    }
    @Test fun providerFailureBackoffIsBounded() {
        assertEquals(12_000L, VisionPolicy.retryDelayMs(1))
        assertEquals(24_000L, VisionPolicy.retryDelayMs(2))
        assertEquals(60_000L, VisionPolicy.retryDelayMs(12))
    }
    @Test fun identicalSceneSignaturePassesWithoutHeadMovement() {
        val signature = VisionFrameSignature(List(192) { it % 255 })
        assertEquals(0.0, visionSignatureDifference(signature, signature)!!, 0.00001)
        assertTrue(visionSignaturesMatch(signature, signature))
    }
    @Test fun sceneChangeHidesBoxesEvenWithSameHeadPose() {
        val before = VisionFrameSignature(List(192) { if (it % 16 < 8) 20 else 220 })
        val after = VisionFrameSignature(List(192) { if (it % 16 < 6) 20 else 220 })
        assertFalse(visionSignaturesMatch(before, after))
    }
    @Test fun localObjectChangeAndInvalidSamplesAreRejected() {
        val before = VisionFrameSignature(List(192) { 80 })
        val after = VisionFrameSignature(List(192) { if (it < 24) 140 else 80 })
        assertFalse(visionSignaturesMatch(before, after))
        assertNull(visionSignatureDifference(before, VisionFrameSignature(listOf(80))))
        assertFalse(visionSignaturesMatch(before, null))
    }

}
