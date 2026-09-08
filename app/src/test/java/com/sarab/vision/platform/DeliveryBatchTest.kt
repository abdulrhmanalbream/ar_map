package com.sarab.vision.platform

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeliveryBatchTest {
    @Test fun partialRateLimitedBatchDoesNotReplaySuccessfulRequests() = runBlocking {
        val pending = (1..13).toMutableList()
        val calls = mutableListOf<Int>()
        deliverBatch(pending.toList(), send = { calls.add(it); if (it == 13) throw PlatformException(429, "limited") },
            complete = { pending.remove(it) }, discard = { fail("not permanent") }, retryLater = {})
        assertEquals(listOf(13), pending)
        deliverBatch(pending.toList(), send = { calls.add(it) }, complete = { pending.remove(it) }, discard = {}, retryLater = {})
        assertTrue(pending.isEmpty())
        assertEquals(1, calls.count { it == 1 })
        assertEquals(2, calls.count { it == 13 })
    }
    @Test fun invalidReceiptDoesNotBlockNextValidEvent() = runBlocking {
        val completed = mutableListOf<Int>()
        val rejected = mutableListOf<Int>()
        deliverBatch(listOf(1, 2), send = { if (it == 1) throw PlatformException(404, "unknown receipt") },
            complete = { completed.add(it) }, discard = { rejected.add(it) }, retryLater = {})
        assertEquals(listOf(2), completed)
        assertEquals(listOf(1), rejected)
    }
    @Test fun invalidAuthenticationStopsWithoutAcknowledgingEvents() = runBlocking {
        var completed = false
        try {
            deliverBatch(listOf(1), send = { throw PlatformException(401, "expired") }, complete = { completed = true }, discard = {}, retryLater = {})
            fail("expected expired authentication")
        } catch (expected: PlatformException) { assertEquals(401, expected.status) }
        assertFalse(completed)
    }
}
