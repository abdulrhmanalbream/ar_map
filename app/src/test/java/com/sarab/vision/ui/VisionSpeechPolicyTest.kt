package com.sarab.vision.ui

import org.junit.Assert.*
import org.junit.Test

class VisionSpeechPolicyTest {
    @Test fun `existing reply is silent and each new reply is spoken only once`() {
        val gate = VisionReplyReadGate(8)
        assertFalse(gate.consume(8, true))
        assertTrue(gate.consume(9, true))
        assertFalse(gate.consume(9, true))
        assertFalse(gate.consume(7, true))
        assertTrue(gate.consume(10, true))
    }

    @Test fun `a reply received in background is consumed without replay on resume`() {
        val gate = VisionReplyReadGate(0)
        assertFalse(gate.consume(1, false))
        assertFalse(gate.consume(1, true))
        assertTrue(gate.consume(2, true))
    }

    @Test fun `callbacks from a stopped utterance cannot finish its replacement`() {
        assertTrue(visionUtteranceMatches(12, "vision-12-0"))
        assertTrue(visionUtteranceMatches(12, "vision-12-3"))
        assertFalse(visionUtteranceMatches(12, "vision-1-2"))
        assertFalse(visionUtteranceMatches(12, "vision-11-0"))
        assertFalse(visionUtteranceMatches(12, null))
    }

    @Test fun `long Arabic answer remains complete across bounded utterances`() {
        val text = List(90) { "هذا وصف واضح للمعلم ويمكنك أن تسأل عن تفاصيله" }.joinToString(" ")
        val chunks = visionSpeechChunks(text, 180)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length in 1..180 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test fun `unbroken supplementary characters never split a surrogate pair`() {
        val text = "مرحبا" + "\uD83D\uDE42".repeat(12) + "نهاية"
        val chunks = visionSpeechChunks(text, 7)
        assertTrue(chunks.all { it.length <= 7 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun `empty speech is ignored and invalid engine limits are rejected`() {
        assertTrue(visionSpeechChunks("  ", 100).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { visionSpeechChunks("answer", 1) }
    }
}
