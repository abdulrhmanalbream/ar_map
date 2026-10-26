package com.sarab.vision.vision

import org.json.JSONObject

/** Fixed codes only: a provider's response body must never become diagnostics or UI copy. */
internal data class VisionRequestFailure(
    val error: VisionError,
    val retryable: Boolean,
    val retryAfterMs: Long = 0L,
) : Exception(error.name)

internal object VisionFailurePayloads {
    private val codes = mapOf(
        "VISION_BUSY" to VisionError.SERVICE_BUSY,
        "VISION_RATE_LIMITED" to VisionError.RATE_LIMIT,
        "VISION_TIMEOUT" to VisionError.TIMEOUT,
        "VISION_UPLOAD_TIMEOUT" to VisionError.UPLOAD_TIMEOUT,
        "VISION_UNAVAILABLE" to VisionError.SERVICE_UNAVAILABLE,
        "VISION_CONFIGURATION" to VisionError.SERVICE_CONFIGURATION,
        "VISION_AUTHENTICATION" to VisionError.SERVICE_AUTHENTICATION,
        "VISION_MODEL_UNAVAILABLE" to VisionError.MODEL_UNAVAILABLE,
        "VISION_BLOCKED" to VisionError.RESPONSE_BLOCKED,
        "VISION_INVALID_RESPONSE" to VisionError.INVALID_RESPONSE,
    )
    private val permanent = setOf(VisionError.AUTHENTICATION, VisionError.SERVICE_CONFIGURATION,
        VisionError.SERVICE_AUTHENTICATION, VisionError.MODEL_UNAVAILABLE, VisionError.RESPONSE_BLOCKED)

    fun parse(status: Int, body: String?, retryAfter: String?): VisionRequestFailure {
        val json = body?.takeIf { it.length <= 16_384 }?.let { runCatching { JSONObject(it) }.getOrNull() }
        // HTTP authentication belongs to our enrollment; upstream authentication has its
        // own 503 code and must not sign the user out of the application.
        val error = if (status == 401 || status == 403) VisionError.AUTHENTICATION else
            codes[json?.opt("code") as? String] ?: when (status) {
                429 -> VisionError.RATE_LIMIT
                408, 504 -> VisionError.TIMEOUT
                in 500..599 -> VisionError.SERVICE_UNAVAILABLE
                else -> VisionError.INVALID_RESPONSE
            }
        val jsonSeconds = (json?.opt("retryAfterSeconds") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it >= 1 && it % 1.0 == 0.0 }?.coerceAtMost(300.0)?.toLong()
        val headerSeconds = retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(300)
        val transientStatus = status == 408 || status == 429 || status >= 500
        val retryable = transientStatus && error !in permanent && (json?.opt("retryable") as? Boolean != false)
        return VisionRequestFailure(error, retryable, maxOf(jsonSeconds ?: 0L, headerSeconds ?: 0L) * 1000)
    }
}

/** One retry budget for analyze and ask. Context switches cannot bypass server advice. */
internal class VisionRetryPolicy {
    var failures: Int = 0; private set
    var retryAtElapsedMs: Long = 0; private set
    var automaticBlocked: Boolean = false; private set
    var lastFailure: VisionRequestFailure? = null; private set

    fun failure(failure: VisionRequestFailure, now: Long) {
        failures = minOf(failures + 1, 8)
        lastFailure = failure
        automaticBlocked = !failure.retryable
        val minimum = when (failure.error) {
            VisionError.UPLOAD_TIMEOUT -> 2_000L
            VisionError.SERVICE_BUSY -> 5_000L
            VisionError.RATE_LIMIT -> 60_000L
            VisionError.AUTHENTICATION, VisionError.SERVICE_AUTHENTICATION,
            VisionError.SERVICE_CONFIGURATION, VisionError.MODEL_UNAVAILABLE -> 60_000L
            VisionError.RESPONSE_BLOCKED, VisionError.IMAGE_ENCODING -> 1_000L
            else -> VisionPolicy.retryDelayMs(failures)
        }
        val delay = maxOf(minimum, failure.retryAfterMs.coerceIn(0L, 300_000L))
        retryAtElapsedMs = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }

    fun blocked(now: Long, automatic: Boolean): VisionError? = lastFailure?.error?.takeIf {
        now < retryAtElapsedMs || (automatic && automaticBlocked)
    }

    fun success() {
        failures = 0; retryAtElapsedMs = 0; automaticBlocked = false; lastFailure = null
    }

    /** A different image/question can resolve a content refusal; it cannot repair credentials. */
    fun newImage() { if (lastFailure?.error == VisionError.RESPONSE_BLOCKED) success() }
}

/** Imported photographs are local snapshots, never fresh camera/world-coordinate samples. */
internal object VisionInputPolicy {
    fun available(foreground: Boolean, cameraAvailable: Boolean, imported: Boolean, retained: Boolean) =
        foreground && (cameraAvailable || imported || retained)

    fun frameUsable(imported: Boolean, capturedAt: Long, now: Long) = imported ||
        VisionPolicy.freshTimestamp(capturedAt, now, VisionPolicy.FRAME_FRESH_MS)

    fun automaticAllowed(imported: Boolean, importing: Boolean, paused: Boolean) =
        !imported && !importing && !paused
}
