package com.sarab.vision

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

private const val TAG = "SarabVoice"

/**
 * Speaks Arabic guidance through whatever audio output is active -- with
 * display glasses on the USB-C port that is their built-in speakers, which
 * turns the glasses into a complete guide even when the wearer never glances
 * at the HUD.
 *
 * Uses the phone's installed TTS engine (Google or Samsung, both ship
 * on-device Arabic voices), so nothing here touches the network. If no
 * Arabic voice is installed this stays silent rather than crashing or
 * falling back to English mispronouncing Arabic text: a wrong voice is
 * worse than none, and the visual HUD carries the same information.
 *
 * WHAT to say and WHEN is decided by the pure policy in core/VoiceCue.kt;
 * this class only performs it.
 */
class VoiceGuide(context: Context) {

    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS engine failed to initialise (status=$status); voice guidance off")
            return@TextToSpeech
        }
        val result = tts.setLanguage(Locale("ar"))
        ready = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            Log.w(TAG, "No Arabic TTS voice installed; voice guidance off")
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        // FLUSH, not ADD: guidance describes the present, and a queue of
        // stale instructions read out one after another actively misleads.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarab-guidance")
    }

    fun shutdown() {
        ready = false
        tts.shutdown()
    }
}
