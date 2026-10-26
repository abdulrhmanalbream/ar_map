package com.sarab.vision.vision

import org.junit.Assert.*
import org.junit.Test

class VisionReliabilityTest {
    @Test fun unchangedLiveImageRefreshesAtSceneExpiryWithoutSixSecondPolling() {
        assertTrue(VisionPolicy.staticRefreshDue(null, 1000))
        assertFalse(VisionPolicy.staticRefreshDue(1000, 999))
        assertFalse(VisionPolicy.staticRefreshDue(1000, 7000))
        assertFalse(VisionPolicy.staticRefreshDue(1000, 30_999))
        assertTrue(VisionPolicy.staticRefreshDue(1000, 31_000))
        assertTrue(VisionPolicy.staticRefreshDue(1000, 40_000))
        assertFalse(VisionInputPolicy.automaticAllowed(imported = true, importing = false, paused = false))
    }
    @Test fun providerAuthenticationDoesNotBecomeUserAuthentication() {
        val failure = VisionFailurePayloads.parse(503,
            """{"code":"VISION_AUTHENTICATION","retryable":false}""", null)
        assertEquals(VisionError.SERVICE_AUTHENTICATION, failure.error)
        assertFalse(failure.retryable)
        assertEquals(VisionError.AUTHENTICATION,
            VisionFailurePayloads.parse(401, """{"code":"VISION_AUTHENTICATION"}""", null).error)
    }

    @Test fun timeoutModelAndContentFailuresRemainDistinct() {
        assertEquals(VisionError.TIMEOUT, VisionFailurePayloads.parse(504, null, null).error)
        assertEquals(VisionError.MODEL_UNAVAILABLE, VisionFailurePayloads.parse(503,
            """{"code":"VISION_MODEL_UNAVAILABLE"}""", null).error)
        val refusal = VisionFailurePayloads.parse(422, """{"code":"VISION_BLOCKED"}""", null)
        assertEquals(VisionError.RESPONSE_BLOCKED, refusal.error)
        assertFalse(refusal.retryable)
    }

    @Test fun busyAndQuotaAdviceUsesLongerBoundedDelay() {
        val busy = VisionFailurePayloads.parse(429,
            """{"code":"VISION_BUSY","retryAfterSeconds":5,"retryable":true}""", "8")
        assertEquals(VisionError.SERVICE_BUSY, busy.error)
        assertEquals(8_000L, busy.retryAfterMs)
        val quota = VisionFailurePayloads.parse(429,
            """{"code":"VISION_RATE_LIMITED","retryAfterSeconds":999999}""", "900")
        assertEquals(VisionError.RATE_LIMIT, quota.error)
        assertEquals(300_000L, quota.retryAfterMs)
    }

    @Test fun malformedRetryAdviceCannotShortenOrOverflowCooldown() {
        for (invalid in listOf("-5", "0", "1.5", "\"999\"", "null", "{}")) {
            val parsed = VisionFailurePayloads.parse(429, """{"retryAfterSeconds":$invalid}""", "invalid")
            assertEquals(0L, parsed.retryAfterMs)
            val policy = VisionRetryPolicy()
            policy.failure(parsed, 100)
            assertEquals(60_100L, policy.retryAtElapsedMs)
        }
    }

    @Test fun unknownOrHtmlErrorCannotExposeServerContent() {
        val secret = "private upstream diagnostic"
        val malformed = VisionFailurePayloads.parse(503, "<html>$secret</html>", null)
        val unknown = VisionFailurePayloads.parse(503, """{"code":"$secret","error":"$secret"}""", null)
        assertEquals(VisionError.SERVICE_UNAVAILABLE, malformed.error)
        assertEquals(VisionError.SERVICE_UNAVAILABLE, unknown.error)
        assertFalse(unknown.message!!.contains(secret))
        assertFalse(VisionFailurePayloads.parse(400, null, null).retryable)
    }

    @Test fun errorBodiesAreBoundedAndTyped() {
        assertEquals(VisionError.SERVICE_UNAVAILABLE,
            VisionFailurePayloads.parse(503, " ".repeat(16_385), null).error)
        assertTrue(VisionFailurePayloads.parse(503, """{"retryable":"false"}""", null).retryable)
        assertFalse(VisionFailurePayloads.parse(503, """{"retryable":false}""", null).retryable)
    }

    @Test fun sharedDeadlineBlocksManualAndAutomaticUntilAdviceExpires() {
        val policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(VisionError.SERVICE_BUSY, true, 8_000), 1_000)
        assertEquals(VisionError.SERVICE_BUSY, policy.blocked(8_999, automatic = false))
        assertEquals(VisionError.SERVICE_BUSY, policy.blocked(8_999, automatic = true))
        assertNull(policy.blocked(9_000, automatic = false))
        assertNull(policy.blocked(9_000, automatic = true))
    }

    @Test fun permanentFailureStopsAutomaticButAllowsExplicitRecovery() {
        val policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(VisionError.SERVICE_CONFIGURATION, false), 100)
        assertEquals(VisionError.SERVICE_CONFIGURATION, policy.blocked(60_099, automatic = false))
        assertNull(policy.blocked(60_100, automatic = false))
        assertEquals(VisionError.SERVICE_CONFIGURATION, policy.blocked(10_000_000, automatic = true))
        policy.success()
        assertFalse(policy.automaticBlocked)
        assertEquals(0, policy.failures)
        assertNull(policy.blocked(10_000_000, automatic = true))
    }

    @Test fun repeatedTransientFailuresBackOffAndSuccessResetsBudget() {
        val policy = VisionRetryPolicy()
        val timeout = VisionRequestFailure(VisionError.TIMEOUT, true)
        policy.failure(timeout, 0)
        assertEquals(12_000L, policy.retryAtElapsedMs)
        policy.failure(timeout, 12_000)
        assertEquals(36_000L, policy.retryAtElapsedMs)
        repeat(10) { policy.failure(timeout, 100_000) }
        assertEquals(160_000L, policy.retryAtElapsedMs)
        policy.success()
        policy.failure(timeout, 200_000)
        assertEquals(212_000L, policy.retryAtElapsedMs)
    }

    @Test fun replacingPhotoCanResolveRefusalButCannotBypassServiceWait() {
        val policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(VisionError.RESPONSE_BLOCKED, false), 0)
        policy.newImage()
        assertNull(policy.blocked(0, automatic = false))
        policy.failure(VisionRequestFailure(VisionError.RATE_LIMIT, true, 90_000), 0)
        policy.newImage()
        assertEquals(VisionError.RATE_LIMIT, policy.blocked(89_999, automatic = false))
        policy.failure(VisionRequestFailure(VisionError.SERVICE_AUTHENTICATION, false), 0)
        policy.newImage()
        assertEquals(VisionError.SERVICE_AUTHENTICATION, policy.blocked(90_000, automatic = true))
    }

    @Test fun deadlineArithmeticNeverOverflows() {
        val policy = VisionRetryPolicy()
        policy.failure(VisionRequestFailure(VisionError.RATE_LIMIT, true, Long.MAX_VALUE), Long.MAX_VALUE - 1)
        assertEquals(Long.MAX_VALUE, policy.retryAtElapsedMs)
    }

    @Test fun importedPhotoNeedsForegroundButDoesNotNeedCameraOrFreshPose() {
        assertTrue(VisionInputPolicy.available(true, false, imported = true, retained = false))
        assertFalse(VisionInputPolicy.available(false, true, imported = true, retained = true))
        assertFalse(VisionInputPolicy.available(true, false, imported = false, retained = false))
        assertTrue(VisionInputPolicy.frameUsable(true, 0, 1_000_000))
        assertFalse(VisionInputPolicy.frameUsable(false, 0, 1_000_000))
    }

    @Test fun choosingOrHoldingPhotoNeverAutomaticallyUploadsIt() {
        assertFalse(VisionInputPolicy.automaticAllowed(imported = true, importing = false, paused = false))
        assertFalse(VisionInputPolicy.automaticAllowed(imported = false, importing = true, paused = false))
        assertFalse(VisionInputPolicy.automaticAllowed(imported = false, importing = false, paused = true))
        assertTrue(VisionInputPolicy.automaticAllowed(imported = false, importing = false, paused = false))
    }

    @Test fun changingInputInvalidatesPendingTicketWithoutOpeningSecondSlot() {
        val gate = VisionRequestGate()
        val oldCamera = gate.start(0, automatic = false)!!
        gate.invalidate()
        assertFalse(gate.accepts(oldCamera))
        assertNull(gate.start(100, automatic = false))
        gate.finish(oldCamera)
        val imported = gate.start(100, automatic = false)!!
        gate.invalidate() // return to live, background, language or consent change
        assertFalse(gate.accepts(imported))
        gate.finish(oldCamera)
        assertTrue(gate.busy)
        gate.finish(imported)
        assertFalse(gate.busy)
    }
}
