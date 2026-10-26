package com.sarab.vision.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformErrorMessageTest {
    @Test fun `flat API error gives public group recovery without displaying server text`() {
        val response = JSONObject().put("code", "DEFAULT_GROUP_UNAVAILABLE").put("error", "Server detail")
        val message = platformErrorMessage(503, response)
        assertTrue(message.contains("المجموعة العامة"))
        assertTrue(message.contains("رمز مجموعة خاصة"))
        assertFalse(message.contains("Server detail"))
    }

    @Test fun `unrelated failures retain their status specific recovery`() {
        assertEquals("طلبات كثيرة؛ حاول بعد قليل", platformErrorMessage(429, JSONObject()))
        assertEquals("تعذّر الاتصال بالخدمة (503)", platformErrorMessage(503, JSONObject().put("code", "OTHER_ERROR")))
        assertEquals("تعذّر التحقق من الربط أو رمز المجموعة",
            platformErrorMessage(401, JSONObject().put("code", "DEFAULT_GROUP_UNAVAILABLE")))
    }
}
