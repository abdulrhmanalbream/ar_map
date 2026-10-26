package com.sarab.vision.phonecamera

import com.sarab.vision.vision.VisionPose
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Rotation-matrix columns are device right (+X), top (+Y), and screen normal (+Z),
 * expressed in a common reference frame. The rear camera looks along -Z. Using its
 * optical axis keeps ordinary upright phone use away from device-Euler gimbal lock.
 * The reference need not be magnetic north: only angles within one session matter.
 */
internal fun phoneCameraPose(rotation: FloatArray, sessionId: Long): VisionPose? {
    if (rotation.size != 9 || rotation.any { !it.isFinite() }) return null
    // Reject malformed matrices rather than publishing a plausible angle from invalid data.
    for (column in 0..2) {
        val lengthSquared = (0..2).sumOf { row ->
            rotation[row * 3 + column].toDouble().let { it * it }
        }
        if (abs(lengthSquared - 1.0) > 0.02) return null
        for (other in column + 1..2) {
            val dot = (0..2).sumOf { row ->
                rotation[row * 3 + column].toDouble() * rotation[row * 3 + other]
            }
            if (abs(dot) > 0.02) return null
        }
    }
    val determinant = rotation[0].toDouble() * (rotation[4] * rotation[8] - rotation[5] * rotation[7]) -
        rotation[1].toDouble() * (rotation[3] * rotation[8] - rotation[5] * rotation[6]) +
        rotation[2].toDouble() * (rotation[3] * rotation[7] - rotation[4] * rotation[6])
    if (abs(determinant - 1.0) > 0.02) return null

    val forwardEast = -rotation[2].toDouble()
    val forwardNorth = -rotation[5].toDouble()
    val forwardUp = -rotation[8].toDouble()
    // Looking almost straight up/down has no dependable azimuth or optical roll.
    if (hypot(forwardEast, forwardNorth) < 0.05 || abs(forwardUp) > 1.001) return null
    return VisionPose(
        yawDegrees = Math.toDegrees(atan2(forwardEast, forwardNorth)),
        pitchDegrees = Math.toDegrees(asin(forwardUp.coerceIn(-1.0, 1.0))),
        sessionId = sessionId,
        rollDegrees = Math.toDegrees(atan2(rotation[6].toDouble(), rotation[7].toDouble())),
    )
}
