package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test
    fun `ordinary speech is never sent`() {
        assertEquals(WakeResult.Ignored, matchWakePhrase("وين باب الملك فهد"))
        assertEquals(WakeResult.Ignored, matchWakePhrase(""))
        // Similar-looking words must not wake it.
        assertEquals(WakeResult.Ignored, matchWakePhrase("المطار بعيد"))
    }

    @Test
    fun `wake phrase with a question returns only the question`() {
        assertEquals(WakeResult.Question("وش هذا المبنى"), matchWakePhrase("يا مطوف وش هذا المبنى"))
    }

    @Test
    fun `words before the wake phrase never leave the phone`() {
        val result = matchWakePhrase("قلت له لا تتأخر يا مطوف كم باقي")
        assertEquals(WakeResult.Question("كم باقي"), result)
    }

    @Test
    fun `spelling variants wake it`() {
        listOf("يامطوف ما هذا", "يا مطوّف ما هذا", "يا المطوف ما هذا", "Hey Mutawwif ما هذا",
            "ya mutawif ما هذا", "یا مطوف ما هذا").forEach {
            assertTrue(it, matchWakePhrase(it) is WakeResult.Question)
        }
    }

    @Test
    fun `bare wake phrase waits for the follow up`() {
        assertEquals(WakeResult.Awake, matchWakePhrase("يا مطوف"))
        assertEquals(WakeResult.Awake, matchWakePhrase("يا مطوف؟"))
    }

    @Test
    fun `follow up within the window becomes the question`() {
        val turn = WakeTurn()
        assertNull(turn.onTranscript("يا مطوف", 1_000))
        assertTrue(turn.isAwake(2_000))
        assertEquals("وين الصفا", turn.onTranscript("وين الصفا", 3_000))
        assertNull(turn.onTranscript("وين المروة", 4_000))
    }

    @Test
    fun `follow up after the window is ignored`() {
        val turn = WakeTurn()
        turn.onTranscript("يا مطوف", 1_000)
        assertNull(turn.onTranscript("وين الصفا", 1_000 + WAKE_FOLLOW_UP_MS + 1))
    }

    @Test
    fun `question keeps its original diacritics`() {
        assertEquals(WakeResult.Question("ما فَضْلُ الطواف"), matchWakePhrase("يا مطوف ما فَضْلُ الطواف"))
    }
}
