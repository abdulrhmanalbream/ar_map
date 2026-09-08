package com.sarab.vision.platform

import com.sarab.vision.wear.shared.LapCounter
import com.sarab.vision.wear.shared.WearProtocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformPayloadsTest {
    @Test fun actualWatchEventDropsTransportEnvelope() {
        val source = WearProtocol.newEvent("lap", WearProtocol.lapJson(LapCounter("tawaf", 3, "session-1", 3L, 1000L)))
        val result = platformLap(source)!!
        assertFalse(result.has("id"))
        assertFalse(result.has("type"))
        assertEquals(setOf("mode", "count", "target", "confidence", "sessionId", "revision", "startedAt"), result.keys().asSequence().toSet())
        assertEquals(3, result.getInt("count"))
        assertTrue(source.has("id"))
    }
    @Test fun estimatesRemainEstimates() {
        val source = WearProtocol.lapJson(LapCounter("sai", 2, "session-2", 8L, 1000L)).put("confidence", "estimated")
        assertEquals("estimated", platformLap(source)!!.getString("confidence"))
    }
    @Test fun invalidOrMissingCountersAreOmitted() {
        assertNull(platformLap(null))
        assertNull(platformLap(JSONObject()))
        assertNull(platformLap(WearProtocol.lapJson(LapCounter("tawaf", 1, "s1", 1L, 1000L)).put("count", 8)))
    }
}
