package com.sarab.vision.wear.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WearProtocolTest {
    private fun bytes(json: JSONObject) = json.toString().toByteArray(Charsets.UTF_8)

    @Test fun `lap transport preserves session revision and manual provenance`() {
        val original = LapCounter("sai", 3, "session", 5, 1000)
        val json = WearProtocol.newEvent("lap", WearProtocol.lapJson(original))
        val event = WearProtocol.event(bytes(json))!!
        assertEquals("lap", event.type)
        assertEquals("manual", event.payload.getString("confidence"))
        assertEquals(original, WearProtocol.lap(event.payload))
    }

    @Test fun `fractional coerced and outside target lap counts are rejected`() {
        val base = WearProtocol.newEvent("lap", WearProtocol.lapJson(LapCounter("tawaf")))
        for (count in listOf<Any>(-1, 8, 1.5, "3", JSONObject.NULL)) {
            assertNull(WearProtocol.event(bytes(JSONObject(base.toString()).put("count", count))))
        }
        assertNull(WearProtocol.event(bytes(JSONObject(base.toString()).put("target", 8))))
        assertNull(WearProtocol.event(bytes(JSONObject(base.toString()).put("revision", -1))))
        assertNull(WearProtocol.event(bytes(JSONObject(base.toString()).put("confidence", "verified"))))
    }

    @Test fun `help must be explicit and bounded while acknowledgement requires alert id`() {
        assertNotNull(WearProtocol.event(bytes(WearProtocol.newEvent("help", JSONObject().put("message", "مساعدة")))))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("help", JSONObject().put("message", "")))))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("help", JSONObject().put("message", "a".repeat(501))))))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("ack"))))
        assertNotNull(WearProtocol.event(bytes(WearProtocol.newEvent("ack", JSONObject().put("alertId", "alert-123")))))
    }

    @Test fun `delivery receipt remains distinct from user acknowledgement`() {
        val delivered = WearProtocol.newEvent("status", JSONObject().put("deliveredAlertId", "alert-123"))
        val parsed = WearProtocol.event(bytes(delivered))!!
        assertEquals("status", parsed.type)
        assertFalse(parsed.payload.has("alertId"))
        assertFalse(parsed.payload.has("batteryPercent"))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("status"))))
        assertNull(WearProtocol.event(bytes(delivered.put("batteryPercent", 101))))
    }

    @Test fun `oversized malformed and unknown payloads never reach callbacks`() {
        assertNull(WearProtocol.event(ByteArray(WearProtocol.MAX_BYTES + 1) { 32 }))
        assertNull(WearProtocol.event("{".toByteArray()))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("navigate"))))
        assertNull(WearProtocol.event(bytes(WearProtocol.newEvent("ack", JSONObject().put("alertId", "../other")))))
        assertNull(WearProtocol.parse(null))
    }

    @Test fun `valid alert round trips while wrong kinds and empty messages fail`() {
        val alert = WatchAlert("alert-id", "regroup", "اجتمعوا عند نقطة اللقاء", "المشرف", "2026-09-08T12:00:00Z")
        assertEquals(alert, WearProtocol.alert(bytes(WearProtocol.alertJson(alert))))
        assertNull(WearProtocol.alert(bytes(WearProtocol.alertJson(alert.copy(kind = "command")))))
        assertNull(WearProtocol.alert(bytes(WearProtocol.alertJson(alert.copy(message = "")))))
        assertNull(WearProtocol.alert(bytes(WearProtocol.alertJson(alert.copy(id = "../unsafe")))))
    }
}
