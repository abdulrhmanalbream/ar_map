package com.sarab.vision.glasses.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneHeadEstimatorTest {
    private fun still(time: Long, yawRate: Double = 0.0) = OneImuReport(
        deviceTimeNanos = time,
        gx = 0.0, gy = yawRate, gz = 0.0,
        ax = 9.81, ay = 0.0, az = 0.0,
    )

    private fun calibrated(yawBias: Double = 0.0): OneHeadEstimator {
        val estimator = OneHeadEstimator()
        for (sample in 1L..751L) estimator.update(still(sample * 1_000_000L, yawBias))
        assertTrue(estimator.calibrated)
        return estimator
    }

    @Test fun waitsForStillnessAndNeverTreatsMotionAsGyroBias() {
        val estimator = OneHeadEstimator()
        for (sample in 1L..1000L) {
            assertNull(estimator.update(still(sample * 1_000_000L, 0.8)))
        }
        assertFalse(estimator.calibrated)
        assertEquals(0f, estimator.calibrationProgress, 0f)
    }

    @Test fun subtractsStillBiasAndIntegratesDeviceTimeIntoClockwiseYaw() {
        val estimator = calibrated(0.01)
        var result: HeadRotation? = null
        for (sample in 752L..1751L) {
            result = estimator.update(still(sample * 1_000_000L, 0.01 + Math.PI / 2))
        }
        assertEquals(90.0, checkNotNull(result).yaw, 0.000001)
        assertEquals(0.0, result.pitch, 0.000001)
        assertEquals(0.0, result.roll, 0.000001)
    }

    @Test fun duplicateAndReversedDeviceTimeCannotKeepStreamAlive() {
        val estimator = calibrated()
        assertFalse(estimator.acceptsTimestamp(still(751_000_000L)))
        assertFalse(estimator.acceptsTimestamp(still(1_000_000L)))
        assertNull(estimator.update(still(751_000_000L)))
    }

    @Test(expected = IllegalStateException::class)
    fun deviceClockGapRequiresNewSessionCalibration() {
        calibrated().update(still(2_000_000_000L))
    }
}
