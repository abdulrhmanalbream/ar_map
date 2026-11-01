package com.sarab.vision.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.sarab.vision.core.WakeTurn
import com.sarab.vision.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Quick restart after silence: the recognizer ends every few seconds by design. */
private const val RESTART_AFTER_SILENCE_MS = 250L

/** A busy or just-cancelled recognizer service needs a moment before it accepts again. */
private const val RESTART_AFTER_BUSY_MS = 1_000L

/**
 * Network/server failures back off to half a minute: a phone with no signal
 * must not spin the microphone and the radio in a tight loop.
 */
private const val BACKOFF_START_MS = 2_000L
private const val BACKOFF_MAX_MS = 30_000L

/** How often the self-hearing gate is re-checked while paused or listening. */
private const val GATE_POLL_MS = 400L

enum class HandsFreeStatus {
    OFF,
    /** Microphone open, waiting for «يا مطوف»; nothing heard leaves the phone. */
    WAITING_FOR_WAKE,
    /** Woken; the next utterance is the question. */
    LISTENING_FOR_QUESTION,
    /** Held while the app itself is speaking or another microphone turn is active. */
    PAUSED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
}

/**
 * Always-on, wake-phrase assistant microphone for glasses use.
 *
 * Android's SpeechRecognizer is utterance-based, so "always on" is a loop of
 * short sessions restarted on every result or error. Transcripts are matched
 * against the wake phrase on the phone (core/WakePhrase.kt); only the words
 * after it are ever handed to [onQuestion]. Foreground only: the owning
 * activity starts this in onResume and stops it in onPause, so there is no
 * background microphone and no foreground-service microphone type to declare.
 *
 * Many devices play a short start tone for each recognizer session; the
 * on-device recognizer (API 31+) is preferred partly because it usually does
 * not. The tone cannot be suppressed reliably without muting system streams,
 * which this deliberately does not do.
 */
class HandsFreeListener(
    context: Context,
    /** Receives only the question that followed the wake phrase. */
    private val onQuestion: (String) -> Unit,
    /** True while the app is talking or a tap-to-talk turn owns the microphone. */
    private val shouldHold: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val turn = WakeTurn()
    private val mutable = MutableStateFlow(HandsFreeStatus.OFF)
    val status: StateFlow<HandsFreeStatus> = mutable.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var listening = false
    private var language = "ar"
    private var backoffMs = BACKOFF_START_MS
    private var generation = 0L
    private var pendingRestart: Runnable? = null
    private val gate = object : Runnable {
        override fun run() {
            if (!running) return
            val hold = held()
            if (hold && listening) {
                // The app started talking mid-session: stop before it hears itself.
                cancelSession()
                mutable.value = HandsFreeStatus.PAUSED
            } else if (!hold && !listening && pendingRestart == null) {
                begin()
            }
            main.postDelayed(this, GATE_POLL_MS)
        }
    }

    val active: Boolean get() = running

    /** Music/TTS/navigation clips all play on the media stream; listening then would transcribe ourselves. */
    private fun held() = shouldHold() || audio?.isMusicActive == true

    fun start(languageCode: String) {
        if (running && language == languageCode) return
        language = languageCode
        if (running) stop()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext) && !onDeviceAvailable()) {
            mutable.value = HandsFreeStatus.UNAVAILABLE
            DiagnosticLog.record("HandsFree", "recognizer unavailable")
            return
        }
        running = true
        backoffMs = BACKOFF_START_MS
        turn.reset()
        DiagnosticLog.record("HandsFree", "start language=$languageCode onDevice=${onDeviceAvailable()}")
        main.post(gate)
    }

    fun stop() {
        if (!running && mutable.value != HandsFreeStatus.PERMISSION_REQUIRED && mutable.value != HandsFreeStatus.UNAVAILABLE) return
        running = false
        main.removeCallbacks(gate)
        pendingRestart?.let(main::removeCallbacks)
        pendingRestart = null
        cancelSession()
        recognizer?.destroy()
        recognizer = null
        turn.reset()
        mutable.value = HandsFreeStatus.OFF
        DiagnosticLog.record("HandsFree", "stop")
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        if (onDeviceAvailable() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else SpeechRecognizer.createSpeechRecognizer(appContext)
    }.getOrNull()

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        pendingRestart?.let(main::removeCallbacks)
        val task = Runnable { pendingRestart = null; if (running && !held()) begin() }
        pendingRestart = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelSession() {
        if (listening) runCatching { recognizer?.cancel() }
        listening = false
        generation++
    }

    private fun begin() {
        if (!running || listening) return
        if (held()) { mutable.value = HandsFreeStatus.PAUSED; return }
        val engine = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (engine == null) {
            mutable.value = HandsFreeStatus.UNAVAILABLE
            running = false
            return
        }
        val session = ++generation
        engine.setRecognitionListener(object : RecognitionListener {
            private fun current() = running && session == generation
            override fun onReadyForSpeech(params: Bundle?) {
                if (current()) mutable.value = if (turn.isAwake(SystemClock.elapsedRealtime()))
                    HandsFreeStatus.LISTENING_FOR_QUESTION else HandsFreeStatus.WAITING_FOR_WAKE
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                if (!current()) return
                listening = false
                backoffMs = BACKOFF_START_MS
                // Transcripts stay in memory only: DiagnosticLog never receives them.
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val question = if (text.isBlank()) null else turn.onTranscript(text, SystemClock.elapsedRealtime())
                if (question != null) {
                    DiagnosticLog.record("HandsFree", "wake question chars=${question.length}")
                    onQuestion(question.take(2_000))
                } else if (turn.isAwake(SystemClock.elapsedRealtime())) {
                    DiagnosticLog.record("HandsFree", "wake without question")
                }
                scheduleRestart(RESTART_AFTER_SILENCE_MS)
            }

            override fun onError(error: Int) {
                if (!current()) return
                listening = false
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        scheduleRestart(RESTART_AFTER_SILENCE_MS)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        // A wedged service is recreated rather than retried forever.
                        recognizer?.destroy(); recognizer = null
                        scheduleRestart(RESTART_AFTER_BUSY_MS)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        mutable.value = HandsFreeStatus.PERMISSION_REQUIRED
                        running = false
                        main.removeCallbacks(gate)
                    }
                    ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> {
                        mutable.value = HandsFreeStatus.UNAVAILABLE
                        running = false
                        main.removeCallbacks(gate)
                        DiagnosticLog.record("HandsFree", "language unavailable code=$error language=$language")
                    }
                    else -> {
                        DiagnosticLog.record("HandsFree", "error code=$error backoffMs=$backoffMs")
                        scheduleRestart(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguage(language))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            recognizer?.destroy(); recognizer = null
            scheduleRestart(RESTART_AFTER_BUSY_MS)
        }
    }

    private companion object {
        // SpeechRecognizer constants added in API 31; literal values keep minSdk 24 compiling.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun recognizerLanguage(code: String) = when (code) {
            "ar" -> "ar-SA"; "ur" -> "ur-PK"; "id" -> "id-ID"; "tr" -> "tr-TR"; else -> "en-US"
        }
    }
}
