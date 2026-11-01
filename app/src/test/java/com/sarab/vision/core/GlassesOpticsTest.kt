package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesOpticsTest {
    private val none = GlassesOpticsCalibration()

    @Test
    fun `published 57 degree diagonal matches the horizontal constant`() {
        assertEquals(57.0, diagonalFovDegrees(GLASSES_DISPLAY_HORIZONTAL_FOV_DEG, GLASSES_DISPLAY_ASPECT), 0.3)
    }

    @Test
    fun `camera centre lands on display centre without calibration`() {
        val point = displayPointForAngles(cameraPointAngles(0.0, 0.0, 70.0, 4.0 / 3.0)!!, none)!!
        assertEquals(0.0, point.first, 1e-9)
        assertEquals(0.0, point.second, 1e-9)
    }

    @Test
    fun `wider camera shrinks onto the narrower display only by angle`() {
        // The camera's edge is 35 degrees out; the display's edge is about 25.35.
        val edge = displayPointForAngles(cameraPointAngles(1.0, 0.0, 70.0, 4.0 / 3.0)!!, none)!!
        assertTrue("camera edge must fall outside the display", edge.first > 1.0)
        val inside = cameraPointAngles(0.5, 0.0, 70.0, 4.0 / 3.0)!!
        val mapped = displayPointForAngles(inside, none)!!
        assertTrue(mapped.first > 0.5 && mapped.first < 1.0)
    }

    @Test
    fun `turning right moves outlines left and looking up moves them down`() {
        val angles = GlassesAngles(0.0, 0.0)
        val right = displayPointForAngles(angles, none, deltaYawDegrees = 10.0)!!
        val up = displayPointForAngles(angles, none, deltaPitchDegrees = 5.0)!!
        assertTrue(right.first < 0.0)
        assertEquals(0.0, right.second, 1e-9)
        assertTrue(up.second > 0.0)
    }

    @Test
    fun `a head turn of the full half field pushes the centre to the display edge`() {
        val point = displayPointForAngles(GlassesAngles(0.0, 0.0), none,
            deltaYawDegrees = GLASSES_DISPLAY_HORIZONTAL_FOV_DEG / 2.0)!!
        assertEquals(-1.0, point.first, 1e-9)
    }

    @Test
    fun `calibration offset and scale apply in degrees`() {
        val shifted = displayPointForAngles(GlassesAngles(0.0, 0.0), GlassesOpticsCalibration(offsetXDegrees = 5.0))!!
        val reference = displayPointForAngles(GlassesAngles(5.0, 0.0), none)!!
        assertEquals(reference.first, shifted.first, 1e-9)
        val scaled = displayPointForAngles(GlassesAngles(10.0, 0.0), GlassesOpticsCalibration(scale = 1.2))!!
        val twelve = displayPointForAngles(GlassesAngles(12.0, 0.0), none)!!
        assertEquals(twelve.first, scaled.first, 1e-9)
    }

    @Test
    fun `absurd calibration is bounded rather than trusted`() {
        val fit = GlassesOpticsCalibration(offsetXDegrees = 90.0, offsetYDegrees = Double.NaN, scale = 9.0).bounded()
        assertEquals(GLASSES_CALIBRATION_MAX_OFFSET_DEG, fit.offsetXDegrees, 0.0)
        assertEquals(0.0, fit.offsetYDegrees, 0.0)
        assertEquals(GLASSES_CALIBRATION_MAX_SCALE, fit.scale, 0.0)
    }

    @Test
    fun `vision box maps every corner and keeps orientation`() {
        val rect = visionBoxOnGlasses(400.0, 400.0, 600.0, 600.0, 70.0, 4.0 / 3.0, none)!!
        assertTrue(rect.left < 0.0 && rect.right > 0.0 && rect.top < 0.0 && rect.bottom > 0.0)
        assertEquals(0.0, rect.centerX, 1e-9)
        assertTrue(rect.onDisplay)
    }

    @Test
    fun `box turned out of view is reported off display`() {
        val rect = visionBoxOnGlasses(450.0, 450.0, 550.0, 550.0, 70.0, 4.0 / 3.0, none, deltaYawDegrees = 40.0)!!
        assertFalse(rect.onDisplay)
    }

    @Test
    fun `degenerate inputs are rejected`() {
        assertNull(visionBoxOnGlasses(600.0, 400.0, 400.0, 600.0, 70.0, 4.0 / 3.0, none))
        assertNull(cameraPointAngles(0.0, 0.0, 5.0, 1.0))
        assertNull(displayPointForAngles(GlassesAngles(85.0, 0.0), none))
    }

    @Test
    fun `angle delta wraps through north`() {
        assertEquals(2.0, angleDeltaDegrees(1.0, 359.0), 1e-9)
        assertEquals(-2.0, angleDeltaDegrees(359.0, 1.0), 1e-9)
    }

    @Test
    fun `pose history interpolates across the wrap and rejects other sessions`() {
        val history = HeadPoseHistory()
        history.add(HeadPoseSample(1_000, 358.0, 0.0, 7))
        history.add(HeadPoseSample(1_100, 2.0, 10.0, 7))
        val mid = history.at(1_050, 7)!!
        assertEquals(0.0, angleDeltaDegrees(mid.yawDegrees, 0.0), 1e-9)
        assertEquals(5.0, mid.pitchDegrees, 1e-9)
        assertNull(history.at(1_050, 8))
        assertNull(history.at(5_000, 7))
    }

    @Test
    fun `new IMU session discards the old yaw origin`() {
        val history = HeadPoseHistory()
        history.add(HeadPoseSample(1_000, 90.0, 0.0, 1))
        history.add(HeadPoseSample(1_010, 0.0, 0.0, 2))
        assertNull(history.at(1_000, 1))
        assertNotNull(history.at(1_010, 2))
    }

    @Test
    fun `pose history forgets samples beyond its window`() {
        val history = HeadPoseHistory(retainMs = 500)
        history.add(HeadPoseSample(0, 0.0, 0.0, 1))
        history.add(HeadPoseSample(1_000, 10.0, 0.0, 1))
        assertEquals(10.0, history.at(1_000, 1)!!.yawDegrees, 1e-9)
        assertNull(history.at(0, 1))
    }
}
