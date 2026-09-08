package com.sarab.vision.wear.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudBindingBoundaryTest {
    @Test fun `old help ack and delivery cannot reach a new group`() {
        for ((type, delivery) in listOf("help" to false, "ack" to false, "status" to true)) {
            assertFalse(allowedAfterCloudBindingChange(type, delivery, 999, 1000))
            assertFalse(allowedAfterCloudBindingChange(type, delivery, null, 1000))
            assertTrue(allowedAfterCloudBindingChange(type, delivery, 1000, 1000))
            assertTrue(allowedAfterCloudBindingChange(type, delivery, 1001, 1000))
        }
    }

    @Test fun `lap counts and plain battery status are independent of group rebinding`() {
        assertTrue(allowedAfterCloudBindingChange("lap", false, null, 1000))
        assertTrue(allowedAfterCloudBindingChange("lap", false, 100, 1000))
        assertTrue(allowedAfterCloudBindingChange("status", false, null, 1000))
    }

    @Test fun `legacy commands accepted only before first binding cutoff`() {
        assertTrue(allowedAfterCloudBindingChange("help", false, null, 0))
        assertFalse(allowedAfterCloudBindingChange("help", false, null, 1))
    }

    @Test fun `new event creation includes timestamp while malformed timestamps stay unknown`() {
        val before = System.currentTimeMillis()
        val event = WearProtocol.newEvent("help", JSONObject().put("message", "مساعدة"))
        assertTrue(WearProtocol.createdAtMillis(event)!! >= before)
        assertTrue(WearProtocol.createdAtMillis(event)!! <= System.currentTimeMillis())
        for (value in listOf<Any>("1234", 1234.5, -1, JSONObject.NULL)) {
            assertNull(WearProtocol.createdAtMillis(JSONObject().put("createdAtMillis", value)))
        }
        assertNull(WearProtocol.createdAtMillis(JSONObject()))
    }
}
