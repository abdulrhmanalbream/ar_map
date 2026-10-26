package com.sarab.vision.vision

import java.util.Locale
import java.net.SocketTimeoutException

internal enum class VisionTransportPhase { CONNECT, UPLOAD, RESPONSE, READ }
internal enum class VisionTransportOutcome {
    PHASE, SUCCESS, LOCAL_SOCKET_TIMEOUT, SERVER_TIMEOUT, SERVER_UPLOAD_TIMEOUT,
    HTTP_FAILURE, INVALID_RESPONSE, NETWORK_FAILURE, CANCELLED,
}

internal fun classifyVisionTransportFailure(failure: Exception): VisionTransportOutcome = when {
    failure is SocketTimeoutException -> VisionTransportOutcome.LOCAL_SOCKET_TIMEOUT
    failure is VisionRequestFailure && failure.error == VisionError.UPLOAD_TIMEOUT -> VisionTransportOutcome.SERVER_UPLOAD_TIMEOUT
    failure is VisionRequestFailure && failure.error == VisionError.TIMEOUT -> VisionTransportOutcome.SERVER_TIMEOUT
    failure is VisionRequestFailure && failure.error == VisionError.INVALID_RESPONSE -> VisionTransportOutcome.INVALID_RESPONSE
    failure is VisionRequestFailure -> VisionTransportOutcome.HTTP_FAILURE
    else -> VisionTransportOutcome.NETWORK_FAILURE
}

/** Only bounded counts and enums can enter this formatter; never payloads or exception messages. */
internal fun visionTransportMetadata(ask: Boolean, phase: VisionTransportPhase, outcome: VisionTransportOutcome,
    status: Int, elapsedMs: Long, payloadBytes: Int, uploadedBytes: Int, receivedBytes: Int,
    error: VisionError? = null): String =
    "kind=${if (ask) "ask" else "analyze"} phase=${phase.name.lowercase(Locale.ROOT)} " +
        "outcome=${outcome.name.lowercase(Locale.ROOT)} status=${status.takeIf { it in 100..599 } ?: 0} " +
        "elapsedMs=${elapsedMs.coerceAtLeast(0)} payloadBytes=${payloadBytes.coerceIn(0, 600_000)} " +
        "uploadedBytes=${uploadedBytes.coerceIn(0, 600_000)} receivedBytes=${receivedBytes.coerceIn(0, 1_048_576)} " +
        "error=${error?.name ?: "NONE"}"
