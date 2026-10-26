package com.sarab.vision.vision

import android.graphics.Bitmap

data class VisionPose(val yawDegrees: Double, val pitchDegrees: Double, val sessionId: Long = 0L,
    val rollDegrees: Double = 0.0)
data class VisionBox(val yMin: Double, val xMin: Double, val yMax: Double, val xMax: Double)
data class VisionSource(val id: String, val title: String, val url: String)
data class VisionDevotional(val id: String, val title: String, val arabicText: String,
    val translation: String, val note: String, val sourceIds: List<String>)
data class VisionItem(val id: String, val kind: String, val label: String, val description: String,
    val box: VisionBox, val sourceIds: List<String> = emptyList(), val landmarkId: String? = null,
    val devotionals: List<VisionDevotional> = emptyList())
data class VisionAnalysis(val sceneId: String, val summary: String, val items: List<VisionItem>,
    val sources: List<VisionSource>, val provider: String)
data class VisionHistoryEntry(val role: String, val text: String)
data class VisionAnswer(val reply: String, val sources: List<VisionSource>, val provider: String)

/** This preview belongs to one captured scene; it is never the changing Eye frame. */
data class VisionScene(val sceneId: String, val summary: String, val items: List<VisionItem>,
    val sources: List<VisionSource>, val provider: String, val previewBitmap: Bitmap,
    val capturedAtElapsedMs: Long, val pose: VisionPose?) {
    val imageWidth get() = previewBitmap.width
    val imageHeight get() = previewBitmap.height
}

enum class VisionError {
    ENROLLMENT_REQUIRED, CAMERA_UNAVAILABLE, FRAME_STALE, CONSENT_REQUIRED, EMPTY_QUESTION,
    SCENE_REQUIRED, NETWORK, RATE_LIMIT, AUTHENTICATION, INVALID_RESPONSE, IMAGE_ENCODING,
    SERVICE_BUSY, TIMEOUT, SERVICE_UNAVAILABLE, SERVICE_CONFIGURATION,
    SERVICE_AUTHENTICATION, MODEL_UNAVAILABLE, RESPONSE_BLOCKED, UPLOAD_TIMEOUT
}

data class EyeVisionState(
    val enabled: Boolean = false, val paused: Boolean = false,
    val language: String = "ar", val translate: Boolean = true,
    val analyzing: Boolean = false, val asking: Boolean = false, val requestBusy: Boolean = false,
    val error: VisionError? = null, val scene: VisionScene? = null,
    val selectedItemId: String? = null, val scenePinned: Boolean = false, val reply: String? = null,
    val replySources: List<VisionSource> = emptyList(), val replyProvider: String? = null,
    val history: List<VisionHistoryEntry> = emptyList(), val markersVisible: Boolean = false,
    val trackedBoxes: List<VisionTrackedBox> = emptyList(),
    val conversationActive: Boolean = false, val conversationRevision: Long = 0,
    val voiceBusy: Boolean = false,
    /** Local, memory-only photo. Its presence never grants permission to upload it. */
    val importedImage: Bitmap? = null,
    val importingImage: Boolean = false,
    val retryAtElapsedMs: Long = 0L,
    val automaticRetryBlocked: Boolean = false,
) {
    val isImportedImage: Boolean get() = importedImage != null
    val selectedItem get() = scene?.items?.firstOrNull { it.id == selectedItemId }
}
