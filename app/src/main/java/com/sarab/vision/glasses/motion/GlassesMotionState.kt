package com.sarab.vision.glasses.motion

/**
 * Head rotation from the glasses only. This contains NO magnetic/true-north heading.
 * Yaw is clockwise/right, pitch is up, roll is clockwise, all in degrees relative
 * to the still, forward-facing calibration pose of this connection.
 *
 * This matches the camera convention in one-xr's OrientationGlSurfaceView:
 * forward = (sin(yaw) cos(pitch), sin(pitch), -cos(yaw) cos(pitch)).
 * Its source tracker pitch and roll are negated at our API boundary.
 * This is an estimated 3DoF orientation, not an onboard quaternion or position.
 */
data class GlassesMotionState(
    val status: String = "حساسات النظارة غير متصلة",
    val tracking: Boolean = false,
    val yawDegrees: Double? = null,
    val pitchDegrees: Double? = null,
    val rollDegrees: Double? = null,
    val sessionId: Long = 0L,
    /** Android elapsedRealtimeNanos, never the unrelated clock inside the glasses. */
    val lastSampleElapsedRealtimeNanos: Long = 0L,
    val calibrationProgress: Float = 0f,
)
