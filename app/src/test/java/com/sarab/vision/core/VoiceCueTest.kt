package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice policy: the value of spoken guidance is set by how rarely it speaks.
 */
class VoiceCueTest {

    // ---- Distance bucketing ------------------------------------------------

    @Test
    fun `buckets are coarse far away and finer close in`() {
        assertEquals(500, voiceDistanceBucket(480.0))
        assertEquals(400, voiceDistanceBucket(420.0))
        assertEquals(250, voiceDistanceBucket(240.0))
        assertEquals(150, voiceDistanceBucket(160.0))
        assertEquals(75, voiceDistanceBucket(80.0))
        assertEquals(50, voiceDistanceBucket(45.0))
    }

    @Test
    fun `bucket never reaches zero`() {
        assertTrue(voiceDistanceBucket(3.0) > 0)
    }

    // ---- Phrases -----------------------------------------------------------

    @Test
    fun `no fix stays silent`() {
        assertNull(voiceCueAr(GuidanceMode.NoFix, "X"))
    }

    @Test
    fun `compass cue carries direction and bucketed distance`() {
        val cue = voiceCueAr(GuidanceMode.Compass(163.0, 90.0, 90.0), "X")!!
        assertTrue(cue.startsWith("اتجه يميناً"))
        assertTrue("expected bucketed 150 in: $cue", cue.contains("150"))
    }

    @Test
    fun `small distance changes do not change the phrase`() {
        val a = voiceCueAr(GuidanceMode.Compass(163.0, 0.0, 5.0), "X")
        val b = voiceCueAr(GuidanceMode.Compass(157.0, 0.0, 5.0), "X")
        assertEquals(a, b)
    }

    @Test
    fun `arrival names the destination`() {
        val cue = voiceCueAr(GuidanceMode.Arrived(4.0), "كلية الشريعة")!!
        assertTrue(cue.contains("كلية الشريعة"))
    }

    // ---- Speaking policy ---------------------------------------------------

    @Test
    fun `a new phrase is spoken and recorded`() {
        val (state, spoken) = nextVoiceCue(VoiceState(), "امشِ للأمام", urgent = false, nowMs = 10_000)
        assertEquals("امشِ للأمام", spoken)
        assertEquals("امشِ للأمام", state.lastText)
    }

    @Test
    fun `the same phrase is never repeated`() {
        val state = VoiceState("امشِ للأمام", 10_000)
        val (_, spoken) = nextVoiceCue(state, "امشِ للأمام", urgent = false, nowMs = 60_000)
        assertNull(spoken)
    }

    @Test
    fun `routine phrases keep the minimum gap`() {
        val state = VoiceState("قديم", 10_000)
        val (unchanged, spoken) =
            nextVoiceCue(state, "جديد", urgent = false, nowMs = 10_000 + VOICE_MIN_GAP_MS - 1)
        assertNull(spoken)
        assertEquals(state, unchanged)
    }

    @Test
    fun `urgent phrases cut through the gap`() {
        val state = VoiceState("قديم", 10_000)
        val (_, spoken) = nextVoiceCue(state, "وصلت", urgent = true, nowMs = 10_500)
        assertNotNull(spoken)
    }
}
