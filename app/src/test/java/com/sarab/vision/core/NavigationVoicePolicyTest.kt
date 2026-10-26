package com.sarab.vision.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationVoicePolicyTest {
    @Test fun phoneNavigationSpeaksWithoutGlassesButRecognitionDoesNot() {
        assertTrue(navigationVoiceEligible(true, false, true, false, false, false))
        assertFalse(navigationVoiceEligible(true, true, true, false, false, false))
        assertFalse(navigationVoiceEligible(true, true, true, true, true, false))
    }

    @Test fun backgroundMutedAndClearedDestinationsStaySilentForBothSources() {
        for (eye in listOf(false, true)) {
            assertFalse(navigationVoiceEligible(false, false, true, eye, true, false))
            assertFalse(navigationVoiceEligible(true, false, true, eye, true, true))
            assertFalse(navigationVoiceEligible(true, false, false, eye, true, false))
        }
    }

    @Test fun eyeNavigationStillRequiresItsDisplayConnection() {
        assertTrue(navigationVoiceEligible(true, false, true, true, true, false))
        assertFalse(navigationVoiceEligible(true, false, true, true, false, false))
    }

    private fun voice(id: String, quality: Int = 300, country: String = "SA", language: String = "ar",
        network: Boolean = false, installed: Boolean = true) =
        NavigationVoiceOption(id, language, country, quality, network, installed)

    @Test fun unavailableOrNetworkArabicNeverReplacesInstalledOfflineSpeech() {
        assertEquals("local", selectNavigationVoice(listOf(
            voice("cloud", quality = 500, network = true), voice("missing", quality = 500, installed = false),
            voice("local", quality = 200)))?.id)
    }

    @Test fun qualityWinsAndSaudiArabicWinsAnEqualQualityTie() {
        assertEquals("quality", selectNavigationVoice(listOf(
            voice("saudi", quality = 300), voice("quality", quality = 400, country = "EG")))?.id)
        assertEquals("saudi", selectNavigationVoice(listOf(
            voice("egyptian", country = "EG"), voice("saudi")))?.id)
    }

    @Test fun noInstalledOfflineArabicStaysSilent() {
        assertNull(selectNavigationVoice(listOf(voice("english", language = "en"),
            voice("network", network = true), voice("not-installed", installed = false))))
        assertNull(selectNavigationVoice(emptyList()))
    }
}
