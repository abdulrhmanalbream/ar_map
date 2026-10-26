package com.sarab.vision.platform

import org.junit.Assert.*
import org.junit.Test

class EnrollmentRequestTest {
    @Test fun `public enrollment needs only name and language and never grants sharing`() {
        val request = enrollmentRequest("  زائر  ", "ur", null)
        assertEquals("/devices/enroll-default", request.path)
        assertEquals("زائر", request.name)
        assertEquals("ur", request.language)
        assertEquals(setOf("name", "language"), request.body.keys().asSequence().toSet())
        assertEquals("زائر", request.body.getString("name"))
        assertEquals("ur", request.body.getString("language"))
    }

    @Test fun `explicit invitation retains the private enrollment endpoint`() {
        val request = enrollmentRequest("Visitor", "en", "  ABC12345  ")
        assertEquals("/devices/enroll", request.path)
        assertEquals("ABC12345", request.body.getString("code"))
        assertEquals(setOf("name", "language", "code"), request.body.keys().asSequence().toSet())
    }

    @Test fun `missing private code is rejected instead of silently joining the public group`() {
        try {
            enrollmentRequest("Visitor", "en", "  ")
            fail("Private enrollment requires a code")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("رمز"))
        }
    }

    @Test fun `blank or overlong names are rejected before a network request`() {
        listOf("  ", "a".repeat(81)).forEach { name ->
            try {
                enrollmentRequest(name, "ar", null)
                fail("Invalid display name must not reach enrollment")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }
}
