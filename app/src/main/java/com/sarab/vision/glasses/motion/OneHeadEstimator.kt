/*
 * Complementary-filter equations and sensor mapping adapted from Skarian/one-xr
 * (MIT, Copyright (c) 2026 Neil Skaria). See third_party/one-xr/LICENSE.
 */
package com.sarab.vision.glasses.motion

import kotlin.math.atan2
import kotlin.math.sqrt

internal data class HeadRotation(val yaw: Double, val pitch: Double, val roll: Double)

/**
 * Preserves the tested one-xr tracker mapping: gyro XYZ is pitch/yaw/roll;
 * accelerometer is reordered Z,Y,X before gravity correction. Report gyro is
 * rad/s. We do not apply guessed factory coefficients or magnetic-north fusion.
 * Startup averages residual gyro bias while still and zeroes the neutral pose.
 */
internal class OneHeadEstimator {
    private var previousTime = 0L
    private var stillSince = 0L
    private var calibrationCount = 0
    private val sums = DoubleArray(3)
    private val bias = DoubleArray(3)
    private var pitch = 0.0
    private var yaw = 0.0
    private var roll = 0.0
    private var neutralPitch = 0.0
    private var neutralRoll = 0.0

    var calibrated = false
        private set
    var calibrationProgress = 0f
        private set

    /** False for duplicate/out-of-order timestamps; they must not keep tracking alive. */
    fun acceptsTimestamp(report: OneImuReport): Boolean = report.deviceTimeNanos > previousTime

    fun update(report: OneImuReport): HeadRotation? {
        if (!acceptsTimestamp(report)) return null
        val previous = previousTime
        previousTime = report.deviceTimeNanos
        // This permutation is the upstream tracker convention, not a USB decode change.
        val ax = report.az
        val ay = report.ay
        val az = report.ax
        val gravity = sqrt(ax * ax + ay * ay + az * az)
        val pitchGravity = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
        val rollGravity = Math.toDegrees(atan2(ay, az))
        if (!calibrated) {
            val spin = sqrt(report.gx * report.gx + report.gy * report.gy + report.gz * report.gz)
            if (spin > 0.10 || gravity !in 7.0..12.5) {
                stillSince = 0L
                calibrationCount = 0
                sums.fill(0.0)
                calibrationProgress = 0f
                return null
            }
            if (stillSince == 0L) stillSince = report.deviceTimeNanos
            sums[0] += report.gx
            sums[1] += report.gy
            sums[2] += report.gz
            calibrationCount++
            val duration = report.deviceTimeNanos - stillSince
            calibrationProgress = minOf(
                calibrationCount / 250f,
                duration / CALIBRATION_NANOS.toFloat(),
                1f,
            )
            if (calibrationProgress < 1f) return null
            for (axis in 0..2) bias[axis] = sums[axis] / calibrationCount
            pitch = pitchGravity
            roll = rollGravity
            neutralPitch = pitch
            neutralRoll = roll
            yaw = 0.0
            calibrated = true
            return rotation()
        }
        val delta = (report.deviceTimeNanos - previous) / 1_000_000_000.0
        // A discontinuity cannot be integrated as if the head kept spinning during it.
        if (delta <= 0.0 || delta > 0.1) throw IllegalStateException("IMU timestamp gap")
        pitch += Math.toDegrees(report.gx - bias[0]) * delta
        yaw += Math.toDegrees(report.gy - bias[1]) * delta
        roll += Math.toDegrees(report.gz - bias[2]) * delta
        if (gravity in 7.0..12.5) {
            // Upstream's 0.96 alpha at 1000 Hz, made rate independent.
            val alpha = Math.pow(0.96, delta * 1000.0)
            pitch += (1.0 - alpha) * wrap(pitchGravity - pitch)
            roll += (1.0 - alpha) * wrap(rollGravity - roll)
        }
        pitch = wrap(pitch)
        yaw = wrap(yaw)
        roll = wrap(roll)
        return rotation()
    }

    private fun rotation() = HeadRotation(
        yaw = yaw,
        pitch = -wrap(pitch - neutralPitch),
        roll = -wrap(roll - neutralRoll),
    )

    companion object {
        private const val CALIBRATION_NANOS = 750_000_000L
        private fun wrap(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}
