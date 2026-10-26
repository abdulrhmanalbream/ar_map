package com.sarab.vision.vision

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class VisionTransportDiagnosticsTest {
    @Test fun localSocketAndProviderAndUploadTimeoutsAreDistinct() {
        assertEquals(VisionTransportOutcome.LOCAL_SOCKET_TIMEOUT, classifyVisionTransportFailure(SocketTimeoutException()))
        assertEquals(VisionTransportOutcome.SERVER_TIMEOUT,
            classifyVisionTransportFailure(VisionFailurePayloads.parse(504, """{"code":"VISION_TIMEOUT"}""", null)))
        assertEquals(VisionTransportOutcome.SERVER_UPLOAD_TIMEOUT,
            classifyVisionTransportFailure(VisionFailurePayloads.parse(408, """{"code":"VISION_UPLOAD_TIMEOUT"}""", null)))
    }

    @Test fun diagnosticCountsAreBoundedAndRawExceptionTextCannotEnterOutput() {
        val secret = "https://private.example/image?token=secret"
        val text = visionTransportMetadata(false, VisionTransportPhase.UPLOAD,
            classifyVisionTransportFailure(IllegalStateException(secret)), 999, -1, Int.MAX_VALUE, -2, Int.MAX_VALUE)
        assertTrue(text.contains("kind=analyze phase=upload outcome=network_failure status=0 elapsedMs=0"))
        assertTrue(text.contains("payloadBytes=600000 uploadedBytes=0 receivedBytes=1048576 error=NONE"))
        assertFalse(text.contains(secret))
        assertFalse(text.contains("https"))
    }

    @Test fun uploadCooldownHonorsServerAdviceWithoutProviderTimeoutBackoff() {
        val failure = VisionFailurePayloads.parse(408,
            """{"code":"VISION_UPLOAD_TIMEOUT","retryable":true,"retryAfterSeconds":2}""", null)
        assertEquals(VisionError.UPLOAD_TIMEOUT, failure.error)
        assertTrue(failure.retryable)
        val policy = VisionRetryPolicy()
        policy.failure(failure, 1_000)
        assertEquals(3_000L, policy.retryAtElapsedMs)
        assertEquals(VisionError.UPLOAD_TIMEOUT, policy.blocked(2_999, false))
        assertNull(policy.blocked(3_000, false))
        policy.failure(VisionFailurePayloads.parse(408,
            """{"code":"VISION_UPLOAD_TIMEOUT","retryAfterSeconds":2}""", "8"), 4_000)
        assertEquals(12_000L, policy.retryAtElapsedMs)
        assertEquals(VisionError.TIMEOUT, VisionFailurePayloads.parse(408, null, null).error)
    }
}
