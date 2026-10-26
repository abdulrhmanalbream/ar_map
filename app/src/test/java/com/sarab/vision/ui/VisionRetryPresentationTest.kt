package com.sarab.vision.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionRetryPresentationTest {
    @Test fun finalPartialSecondStillDisablesSubmissionUntilExactDeadline() {
        assertEquals(4L, visionRetrySeconds(4_500, 1_000))
        assertEquals(1L, visionRetrySeconds(4_500, 3_500))
        assertEquals(1L, visionRetrySeconds(4_500, 4_499))
        assertEquals(0L, visionRetrySeconds(4_500, 4_500))
        assertEquals(0L, visionRetrySeconds(4_500, 4_501))
    }

    @Test fun absentOrInvalidDeadlineNeedsNoTimer() {
        assertEquals(0L, visionRetrySeconds(0, 1_000))
        assertEquals(0L, visionRetrySeconds(-1, 1_000))
        assertEquals(0L, visionRetrySeconds(1_000, -1))
    }

    @Test fun veryLargeValidElapsedValuesDoNotOverflowRounding() {
        assertEquals(2L, visionRetrySeconds(Long.MAX_VALUE, Long.MAX_VALUE - 1_001))
        assertEquals(9_223_372_036_854_776L, visionRetrySeconds(Long.MAX_VALUE, 0))
    }
}
