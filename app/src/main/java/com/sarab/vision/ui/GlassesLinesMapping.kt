package com.sarab.vision.ui

import com.sarab.vision.core.GLASSES_DISPLAY_ASPECT
import com.sarab.vision.core.GlassesAngles
import com.sarab.vision.core.GlassesDisplayRect
import com.sarab.vision.core.GlassesOpticsCalibration
import com.sarab.vision.core.HeadPoseHistory
import com.sarab.vision.core.HeadPoseSample
import com.sarab.vision.core.angleDeltaDegrees
import com.sarab.vision.core.displayPointForAngles
import com.sarab.vision.core.imageAspect
import com.sarab.vision.core.visionBoxOnGlasses
import com.sarab.vision.glasses.motion.GlassesMotionState
import com.sarab.vision.vision.EyeVisionState

/** Labels on the optics must be a glance, not a sentence. */
private const val GLASSES_LABEL_MAX_CHARS = 28

/**
 * Recognised items as outlines on the glasses display, moved by the head turn
 * since their camera frame. Same visibility rule as the phone markers
 * (EyeVisionState.markersVisible, tracked boxes preferred), so the glasses can
 * never show a box the phone policy has withdrawn.
 */
internal fun glassesOutlines(
    state: EyeVisionState,
    motion: GlassesMotionState,
    poses: HeadPoseHistory,
    cameraHorizontalFovDegrees: Double,
    displayHorizontalFovDegrees: Double,
    calibration: GlassesOpticsCalibration,
): List<GlassesOutline> {
    val scene = state.scene ?: return emptyList()
    if (!state.markersVisible || state.isImportedImage || state.scenePinned) return emptyList()
    val yaw = motion.yawDegrees
    val pitch = motion.pitchDegrees
    if (!motion.tracking || yaw == null || pitch == null || !yaw.isFinite() || !pitch.isFinite()) return emptyList()
    val aspect = imageAspect(scene.imageWidth, scene.imageHeight) ?: return emptyList()
    val tracked = state.trackedBoxes.associateBy { it.itemId }
    return scene.items.mapNotNull { item ->
        if (tracked.isNotEmpty() && item.id !in tracked) return@mapNotNull null
        val trackedBox = tracked[item.id]
        val box = trackedBox?.box ?: item.box
        val then: HeadPoseSample? = if (trackedBox != null) poses.at(trackedBox.atElapsedMs, motion.sessionId)
            else scene.pose?.takeIf { it.sessionId == motion.sessionId }?.let {
                HeadPoseSample(scene.capturedAtElapsedMs, it.yawDegrees, it.pitchDegrees, it.sessionId)
            }
        // Without a pose for that frame the box is drawn where it was measured:
        // tracked boxes are at most 850 ms old, so the error is one small lag.
        val deltaYaw = then?.let { angleDeltaDegrees(yaw, it.yawDegrees) }?.takeIf { it.isFinite() } ?: 0.0
        val deltaPitch = then?.let { pitch - it.pitchDegrees }?.takeIf { it.isFinite() } ?: 0.0
        val rect = visionBoxOnGlasses(box.yMin, box.xMin, box.yMax, box.xMax,
            cameraHorizontalFovDegrees, aspect, calibration, displayHorizontalFovDegrees,
            GLASSES_DISPLAY_ASPECT, deltaYaw, deltaPitch)?.takeIf { it.onDisplay } ?: return@mapNotNull null
        val label = (if (item.kind == "text") item.description.ifBlank { item.label } else item.label).trim()
        GlassesOutline(item.id, if (label.length > GLASSES_LABEL_MAX_CHARS)
            label.take(GLASSES_LABEL_MAX_CHARS - 1) + "…" else label, rect)
    }
}

/** Where the Eye camera's centre appears through the optics with the current fit. */
internal fun glassesCalibrationTarget(
    calibration: GlassesOpticsCalibration,
    displayHorizontalFovDegrees: Double,
): GlassesDisplayRect? {
    val (x, y) = displayPointForAngles(GlassesAngles(0.0, 0.0), calibration, displayHorizontalFovDegrees)
        ?: return null
    return GlassesDisplayRect(x, y, x, y)
}
