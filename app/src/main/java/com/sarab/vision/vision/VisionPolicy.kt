package com.sarab.vision.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object VisionPolicy {
    const val AUTOMATIC_INTERVAL_MS = 6_000L
    const val STATIC_REFRESH_MS = 30_000L
    const val FRAME_FRESH_MS = 1_500L
    const val SCENE_FRESH_MS = 30_000L
    const val STEADY_MS = 750L
    const val SIGNATURE_INTERVAL_MS = 500L
    val languages = setOf("ar", "en", "ur", "id", "tr")

    fun freshTimestamp(capturedAt: Long, now: Long, maximumAge: Long): Boolean =
        capturedAt >= 0 && now >= capturedAt && now - capturedAt <= maximumAge

    fun validPose(pose: VisionPose?): Boolean = pose != null && pose.yawDegrees.isFinite() &&
        pose.pitchDegrees.isFinite() && pose.pitchDegrees in -90.0..90.0 && pose.rollDegrees.isFinite()

    fun nearPose(captured: VisionPose?, current: VisionPose?): Boolean {
        if (!validPose(captured) || !validPose(current) || captured!!.sessionId != current!!.sessionId) return false
        val yawDelta = abs(((current.yawDegrees - captured.yawDegrees) % 360 + 540) % 360 - 180)
        val rollDelta = abs(((current.rollDegrees - captured.rollDegrees) % 360 + 540) % 360 - 180)
        return yawDelta <= 3.0 && abs(current.pitchDegrees - captured.pitchDegrees) <= 3.0 && rollDelta <= 3.0
    }

    fun markersFresh(capturedAt: Long, now: Long, captured: VisionPose?, current: VisionPose?): Boolean =
        freshTimestamp(capturedAt, now, SCENE_FRESH_MS) && nearPose(captured, current)

    fun automaticDue(lastAttemptAt: Long?, now: Long): Boolean = lastAttemptAt == null ||
        (now >= lastAttemptAt && now - lastAttemptAt >= AUTOMATIC_INTERVAL_MS)

    fun staticRefreshDue(lastCapturedAt: Long?, now: Long): Boolean = lastCapturedAt == null ||
        (now >= lastCapturedAt && now - lastCapturedAt >= STATIC_REFRESH_MS)

    fun retryDelayMs(consecutiveFailures: Int): Long =
        minOf(60_000L, AUTOMATIC_INTERVAL_MS * (1L shl consecutiveFailures.coerceIn(0, 4)))
}

internal data class VisionFrameSignature(val luma: List<Int>)

/** Mean normalized luminance difference; intentionally conservative about changed scenes. */
internal fun visionSignatureDifference(first: VisionFrameSignature?, second: VisionFrameSignature?): Double? {
    if (first == null || second == null || first.luma.size != 192 || second.luma.size != 192 ||
        first.luma.any { it !in 0..255 } || second.luma.any { it !in 0..255 }) return null
    return first.luma.zip(second.luma).sumOf { (a, b) -> abs(a - b) }.toDouble() / (192 * 255)
}

internal fun visionSignaturesMatch(first: VisionFrameSignature?, second: VisionFrameSignature?): Boolean {
    val difference = visionSignatureDifference(first, second) ?: return false
    val changedCells = first!!.luma.zip(second!!.luma).count { (a, b) -> abs(a - b) > 35 }
    return difference <= 12.0 / 255.0 && changedCells <= 19
}

data class VisionScreenBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** An invalidated HTTP request still owns the single slot until its worker exits. */
internal class VisionRequestGate {
    data class Ticket(val serial: Long, val epoch: Long)
    private var epoch = 0L
    private var serial = 0L
    private var active: Ticket? = null
    private var lastAttemptAt: Long? = null
    val busy get() = active != null
    fun start(now: Long, automatic: Boolean): Ticket? {
        if (busy || (automatic && !VisionPolicy.automaticDue(lastAttemptAt, now))) return null
        lastAttemptAt = now
        return Ticket(++serial, epoch).also { active = it }
    }
    fun invalidate() { epoch++ }
    fun accepts(ticket: Ticket) = active == ticket && ticket.epoch == epoch
    fun finish(ticket: Ticket) { if (active == ticket) active = null }
}

/** Maps normalized Gemini [y,x,y,x] boxes through centered ContentScale.Crop. */
fun cropVisionBox(box: VisionBox, imageWidth: Int, imageHeight: Int,
    viewportWidth: Float, viewportHeight: Float): VisionScreenBox? {
    if (imageWidth <= 0 || imageHeight <= 0 || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        viewportWidth <= 0f || viewportHeight <= 0f) return null
    if (listOf(box.yMin, box.xMin, box.yMax, box.xMax).any { !it.isFinite() || it !in 0.0..1000.0 } ||
        box.yMin >= box.yMax || box.xMin >= box.xMax) return null
    val scale = max(viewportWidth / imageWidth, viewportHeight / imageHeight)
    val offsetX = (viewportWidth - imageWidth * scale) / 2f
    val offsetY = (viewportHeight - imageHeight * scale) / 2f
    val left = max(0f, offsetX + (box.xMin / 1000 * imageWidth * scale).toFloat())
    val top = max(0f, offsetY + (box.yMin / 1000 * imageHeight * scale).toFloat())
    val right = min(viewportWidth, offsetX + (box.xMax / 1000 * imageWidth * scale).toFloat())
    val bottom = min(viewportHeight, offsetY + (box.yMax / 1000 * imageHeight * scale).toFloat())
    return if (right > left && bottom > top) VisionScreenBox(left, top, right, bottom) else null
}
