package com.sarab.vision.phonecamera

import com.sarab.vision.vision.VisionPolicy
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMotionMathTest {
    @Test fun uprightRearCameraHasStableHorizontalPose() {
        // Device top is up, the rear camera faces the reference north, and screen faces south.
        val pose = phoneCameraPose(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f), -7L)!!
        assertEquals(0.0, pose.yawDegrees, 0.0001)
        assertEquals(0.0, pose.pitchDegrees, 0.0001)
        assertEquals(0.0, pose.rollDegrees, 0.0001)
        assertEquals(-7L, pose.sessionId)
    }

    @Test fun smallUprightMotionDoesNotJumpAtDevicePitchSingularity() {
        val center = phoneCameraPose(cameraRotation(), -1L)!!
        for (pitch in listOf(-0.1, 0.0, 0.1)) {
            val moved = phoneCameraPose(cameraRotation(yaw = 1.5, pitch = pitch, roll = 1.0), -1L)!!
            assertEquals(1.5, moved.yawDegrees, 0.0001)
            assertEquals(pitch, moved.pitchDegrees, 0.0001)
            assertEquals(1.0, moved.rollDegrees, 0.0001)
            assertTrue(VisionPolicy.nearPose(center, moved))
        }
    }

    @Test fun uprightMotionBeyondPolicyToleranceInvalidatesScene() {
        val captured = phoneCameraPose(cameraRotation(), -1L)
        assertFalse(VisionPolicy.nearPose(captured, phoneCameraPose(cameraRotation(yaw = 4.0), -1L)))
        assertFalse(VisionPolicy.nearPose(captured, phoneCameraPose(cameraRotation(pitch = 4.0), -1L)))
        assertFalse(VisionPolicy.nearPose(captured, phoneCameraPose(cameraRotation(roll = 4.0), -1L)))
    }

    @Test fun landscapeRollAndTiltStillProduceFiniteCameraAngles() {
        val pose = phoneCameraPose(cameraRotation(yaw = 47.0, pitch = 22.0, roll = 90.0), -3L)!!
        assertEquals(47.0, pose.yawDegrees, 0.0001)
        assertEquals(22.0, pose.pitchDegrees, 0.0001)
        assertEquals(90.0, pose.rollDegrees, 0.0001)
        assertTrue(VisionPolicy.validPose(pose))
    }

    @Test fun headingWrapPreservesSmallRelativeMotion() {
        val before = phoneCameraPose(cameraRotation(yaw = 179.0), -2L)
        val after = phoneCameraPose(cameraRotation(yaw = -179.0), -2L)
        assertTrue(VisionPolicy.nearPose(before, after))
    }

    @Test fun sensorRestartCannotReuseEarlierScenePose() {
        val before = phoneCameraPose(cameraRotation(), -4L)
        val after = phoneCameraPose(cameraRotation(), -5L)
        assertFalse(VisionPolicy.nearPose(before, after))
    }

    @Test fun flatPhoneOrNearlyVerticalOpticalAxisHasNoDependablePose() {
        assertNull(phoneCameraPose(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), -1L))
        for (pitch in listOf(-90.0, -89.0, 89.0, 90.0)) {
            assertNull(phoneCameraPose(cameraRotation(pitch = pitch), -1L))
        }
        assertNotNull(phoneCameraPose(cameraRotation(pitch = 80.0), -1L))
        assertNotNull(phoneCameraPose(cameraRotation(pitch = -80.0), -1L))
    }

    @Test fun malformedOrReflectedRotationNeverCreatesPose() {
        assertNull(phoneCameraPose(FloatArray(8), -1L))
        assertNull(phoneCameraPose(FloatArray(9), -1L))
        assertNull(phoneCameraPose(cameraRotation().also { it[0] = Float.NaN }, -1L))
        assertNull(phoneCameraPose(cameraRotation().also { it[0] = Float.POSITIVE_INFINITY }, -1L))
        assertNull(phoneCameraPose(cameraRotation().also { it[0] = 0.5f }, -1L))
        assertNull(phoneCameraPose(cameraRotation().also { it[0] = -1f }, -1L))
    }

    /** Construct an orthonormal rear-camera basis independently of the extraction formula. */
    private fun cameraRotation(yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0): FloatArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        val r = Math.toRadians(roll)
        val forward = doubleArrayOf(sin(y) * cos(p), cos(y) * cos(p), sin(p))
        val right = doubleArrayOf(cos(y), -sin(y), 0.0)
        val up = doubleArrayOf(-sin(y) * sin(p), -cos(y) * sin(p), cos(p))
        return FloatArray(9) { index ->
            val row = index / 3
            when (index % 3) {
                0 -> (right[row] * cos(r) + up[row] * sin(r)).toFloat()
                1 -> (up[row] * cos(r) - right[row] * sin(r)).toFloat()
                else -> -forward[row].toFloat()
            }
        }
    }
}
