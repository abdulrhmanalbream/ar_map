package com.sarab.vision.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sarab.vision.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

internal class VisionSpeech {
    var listening by mutableStateOf(false)
    var processing by mutableStateOf(false)
    var speaking by mutableStateOf(false)
    var permissionPending by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var listen: () -> Unit = {}
    var read: (String) -> Unit = {}
    var readArabic: (String) -> Unit = {}
    var readReply: (String, Long) -> Unit = { _, _ -> }
    var stop: () -> Unit = {}
}

/** One stable HUD owner serves both the live camera and the selected-item panel. */
private class VisionSpeechRuntime(private val context: Context, private val lifecycle: Lifecycle,
    private val voice: VisionSpeech, initialReplyRevision: Long) {
    private val main = Handler(Looper.getMainLooper())
    private val replies = VisionReplyReadGate(initialReplyRevision)
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cloud = GeminiVoiceClient(context)
    private val pcmPlayer = VisionPcmPlayer()
    private var cloudJob: Job? = null
    private var muted = false
    private var cloudEnabled = false
    private var composed = false
    private var speechGeneration = 0L
    private var ttsGeneration = 0L
    private var utteranceGeneration = 0L
    private var desiredListen = false
    private var listenStarted = false
    private var permissionInFlight = false
    private var resumeAfterGrant = false
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingRead: String? = null
    private var readLanguage = "ar"
    private var pendingBegin: Runnable? = null
    private var recognitionTimeout: Runnable? = null
    private var ttsTimeout: Runnable? = null
    private var language = "ar"
    var onText: (String) -> Unit = {}
    var onBeforeListen: () -> Boolean = { true }
    var onTurnFinished: () -> Unit = {}
    var onPermissionPendingChange: (Boolean) -> Unit = {}
    var requestPermission: () -> Unit = {}
    private fun resumed() = composed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private fun copy() = VisionCopy(language)
    private fun unavailable(message: String = copy().speechUnavailable) { if (composed) voice.notice = message }

    fun attach() {
        composed = true
        val generation = ++ttsGeneration
        try {
            speaker = TextToSpeech(context) { status -> main.post {
                if (!composed || ttsGeneration != generation) return@post
                ttsReady = status == TextToSpeech.SUCCESS
                ttsFailed = !ttsReady
                DiagnosticLog.record("VisionSpeech", "tts initialized ready=$ttsReady status=$status")
                if (pendingRead != null) {
                    if (ttsReady) speakPending() else finishSpeech(copy().speechPlaybackUnavailable)
                }
            } }
        } catch (_: Exception) { ttsFailed = true }
    }

    fun setLanguage(value: String) {
        if (language == value) return
        stop()
        language = value
        voice.notice = null
    }

    fun setAudioOptions(mute: Boolean, useCloud: Boolean) {
        val stopPlayback = (mute && !muted) || (!useCloud && cloudEnabled && cloudJob != null)
        muted = mute
        cloudEnabled = useCloud
        if (stopPlayback) {
            val wasSpeaking = voice.speaking
            cancelAudio() // Output only: the user's microphone turn stays available while muted.
            if (wasSpeaking) onTurnFinished()
        }
    }

    fun listen() {
        if (!resumed()) return
        if (desiredListen) {
            if (voice.listening) {
                voice.listening = false
                voice.processing = true
                runCatching { recognizer?.stopListening() }.onFailure { failRecognition(copy().speechUnavailable) }
                armRecognitionTimeout(8_000)
            } else stop()
            return
        }
        voice.notice = null
        cancelAudio()
        desiredListen = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginWhenReady()
        else if (!permissionInFlight) {
            permissionInFlight = true
            setPermissionPending(true) // Before the OS permission sheet can pause the activity.
            DiagnosticLog.record("VisionSpeech", "microphone permission requested")
            try { requestPermission() }
            catch (_: Exception) { permissionInFlight = false; failRecognition(copy().speechPermission) }
        }
    }

    fun permissionResult(granted: Boolean) {
        if (!permissionInFlight) return
        permissionInFlight = false
        DiagnosticLog.record("VisionSpeech", "microphone permission granted=$granted")
        if (!composed || !desiredListen) { setPermissionPending(false); return }
        if (!granted) { failRecognition(copy().speechPermission); return }
        resumeAfterGrant = true
        resume()
    }
    fun resume() { if (resumed() && desiredListen && resumeAfterGrant) beginWhenReady() }
    fun pause() {
        // Preserve only a first-use permission request, never background microphone capture.
        if (desiredListen && (permissionInFlight || resumeAfterGrant)) {
            cancelRecognizer(); cancelAudio()
        } else stop()
    }
    fun stop() {
        desiredListen = false
        resumeAfterGrant = false
        pendingBegin?.let(main::removeCallbacks); pendingBegin = null
        cancelRecognizer(); cancelAudio()
        setPermissionPending(false)
        onTurnFinished()
    }
    private fun setPermissionPending(value: Boolean) {
        if (voice.permissionPending == value) return
        voice.permissionPending = value
        onPermissionPendingChange(value)
    }
    private fun cancelRecognizer() {
        speechGeneration++
        if (listenStarted) DiagnosticLog.record("VisionSpeech", "listen ended")
        listenStarted = false
        recognitionTimeout?.let(main::removeCallbacks); recognitionTimeout = null
        val previous = recognizer; recognizer = null
        voice.listening = false; voice.processing = false
        runCatching { previous?.cancel() }; runCatching { previous?.destroy() }
    }

    private fun beginWhenReady() {
        if (!resumed() || !desiredListen) return
        pendingBegin?.let(main::removeCallbacks)
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        DiagnosticLog.record("VisionSpeech", "recognition available=$available")
        if (!available) { failRecognition(copy().speechUnavailable); return }
        val deadline = SystemClock.elapsedRealtime() + 2_000
        val attempt = object : Runnable {
            override fun run() {
                if (!resumed() || !desiredListen) return
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    failRecognition(copy().speechPermission); return
                }
                if (onBeforeListen()) {
                    pendingBegin = null; resumeAfterGrant = false
                    setPermissionPending(false)
                    beginRecognizer()
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    voice.processing = true
                    main.postDelayed(this, 150)
                } else failRecognition(copy().voiceFrameUnavailable)
            }
        }
        pendingBegin = attempt
        attempt.run()
    }
    private fun beginRecognizer() {
        cancelRecognizer()
        val generation = speechGeneration
        fun accepts() = resumed() && desiredListen && generation == speechGeneration
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (!accepts()) return
                        voice.listening = false; voice.processing = true
                        armRecognitionTimeout(8_000)
                    }
                    override fun onError(error: Int) {
                        if (!accepts()) return
                        DiagnosticLog.record("VisionSpeech", "recognition error code=$error")
                        failRecognition(when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> copy().speechPermission
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> copy().speechNotHeard
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> copy().speechNetwork
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> copy().speechBusy
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> copy().speechLanguageUnavailable
                            else -> copy().speechUnavailable
                        })
                    }
                    override fun onResults(results: Bundle?) {
                        if (!accepts()) return
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.take(1000)
                        desiredListen = false
                        cancelRecognizer()
                        if (text.isNullOrBlank()) failRecognition(copy().speechNotHeard)
                        else {
                            DiagnosticLog.record("VisionSpeech", "recognition result chars=${text.length}")
                            onText(text) // The controller holds the turn until reply speech finishes.
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
            voice.listening = true
            listenStarted = true
            DiagnosticLog.record("VisionSpeech", "listen started language=$language")
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLocale().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
            armRecognitionTimeout(25_000)
        } catch (_: Exception) { failRecognition(copy().speechUnavailable) }
    }
    private fun armRecognitionTimeout(delay: Long) {
        recognitionTimeout?.let(main::removeCallbacks)
        val generation = speechGeneration
        recognitionTimeout = Runnable {
            if (generation == speechGeneration && desiredListen) {
                DiagnosticLog.record("VisionSpeech", "recognition timeout")
                failRecognition(copy().speechNotHeard)
            }
        }.also { main.postDelayed(it, delay) }
    }
    private fun failRecognition(message: String) { stop(); unavailable(message) }

    fun readReply(text: String, revision: Long) {
        if (!replies.consume(revision, resumed())) return
        if (muted || text.isBlank()) onTurnFinished() else read(text)
    }
    fun read(text: String, overrideLanguage: String? = null) {
        if (!resumed() || muted || text.isBlank()) return
        desiredListen = false
        cancelRecognizer(); cancelAudio()
        voice.notice = null
        readLanguage = overrideLanguage ?: language
        voice.speaking = true
        if (cloudEnabled) speakCloud(text, readLanguage) else speakNative(text)
    }
    private fun speakCloud(text: String, spokenLanguage: String) {
        val generation = utteranceGeneration
        val chunks = visionSpeechChunks(text, 600)
        cloudJob = audioScope.launch {
            var completed = 0
            try {
                for (chunk in chunks) {
                    val pcm = withTimeout(40_000) { cloud.synthesize(chunk, spokenLanguage) }
                    if (!resumed() || muted || generation != utteranceGeneration || !cloudEnabled) return@launch
                    withTimeout((pcm.size / 48L + 10_000).coerceIn(15_000, 190_000)) { pcmPlayer.play(pcm) }
                    completed++
                }
                if (generation == utteranceGeneration) finishSpeech()
            } catch (error: Exception) {
                // A deadline can use the device voice; deliberate cancellation cannot.
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation != utteranceGeneration || !resumed() || muted || !cloudEnabled) return@launch
                DiagnosticLog.record("VisionSpeech", "cloud playback unavailable; using device voice")
                cloudJob = null
                speakNative(chunks.drop(completed).joinToString(" "))
            }
        }
    }
    private fun speakNative(text: String) {
        pendingRead = text
        if (ttsReady) speakPending()
        else if (ttsFailed) finishSpeech(copy().speechPlaybackUnavailable)
        else ttsTimeout = Runnable { if (pendingRead != null) {
            DiagnosticLog.record("VisionSpeech", "read timeout phase=initialization")
            finishSpeech(copy().speechPlaybackUnavailable)
        } }
            .also { main.postDelayed(it, 5_000) }
    }
    private fun speakPending() {
        val text = pendingRead ?: return
        if (!resumed() || muted) { cancelAudio(); onTurnFinished(); return }
        pendingRead = null
        ttsTimeout?.let(main::removeCallbacks); ttsTimeout = null
        val generation = ++utteranceGeneration
        try {
            val engine = speaker ?: return finishSpeech(copy().speechPlaybackUnavailable)
            val support = engine.setLanguage(voiceLocale(readLanguage))
            DiagnosticLog.record("VisionSpeech", "tts support language=$readLanguage result=$support")
            if (support < 0) { finishSpeech(copy().speechLanguageUnavailable); return }
            val preferred = runCatching { engine.voices?.filter {
                it.locale.language == voiceLocale(readLanguage).language &&
                    !it.isNetworkConnectionRequired &&
                    !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                .thenByDescending { it.locale == voiceLocale(readLanguage) }
                .thenBy { it.name })?.firstOrNull() }.getOrNull()
            if (preferred == null || engine.setVoice(preferred) != TextToSpeech.SUCCESS) {
                finishSpeech(copy().speechLanguageUnavailable); return
            }
            val chunks = visionSpeechChunks(text, TextToSpeech.getMaxSpeechInputLength())
            DiagnosticLog.record("VisionSpeech", "read started language=$readLanguage chunks=${chunks.size} chars=${text.length}")
            val lastId = "vision-$generation-${chunks.lastIndex}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post { if (generation == utteranceGeneration && utteranceId == lastId) finishSpeech() }
                }
                @Deprecated("Framework callback")
                override fun onError(utteranceId: String?) = failed(utteranceId, -1)
                override fun onError(utteranceId: String?, errorCode: Int) = failed(utteranceId, errorCode)
                private fun failed(utteranceId: String?, code: Int) {
                    main.post { if (generation == utteranceGeneration && visionUtteranceMatches(generation, utteranceId)) {
                        DiagnosticLog.record("VisionSpeech", "tts error code=$code")
                        finishSpeech(copy().speechPlaybackUnavailable)
                    } }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    main.post { if (generation == utteranceGeneration && visionUtteranceMatches(generation, utteranceId)) finishSpeech() }
                }
            })
            ttsTimeout = Runnable {
                if (generation == utteranceGeneration) {
                    DiagnosticLog.record("VisionSpeech", "read timeout phase=playback")
                    finishSpeech(copy().speechPlaybackUnavailable)
                }
            }.also { main.postDelayed(it, (text.length * 100L + 15_000).coerceIn(30_000, 240_000)) }
            chunks.forEachIndexed { index, chunk ->
                val result = engine.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        null, "vision-$generation-$index")
                if (result != TextToSpeech.SUCCESS) {
                    DiagnosticLog.record("VisionSpeech", "tts enqueue error code=$result")
                    finishSpeech(copy().speechPlaybackUnavailable); return
                }
            }
        } catch (_: Exception) { finishSpeech(copy().speechPlaybackUnavailable) }
    }
    private fun cancelAudio() {
        utteranceGeneration++
        cloudJob?.cancel(); cloudJob = null
        pcmPlayer.stop()
        if (voice.speaking) DiagnosticLog.record("VisionSpeech", "read stopped")
        pendingRead = null
        ttsTimeout?.let(main::removeCallbacks); ttsTimeout = null
        voice.speaking = false
        runCatching { speaker?.stop() }
    }
    private fun finishSpeech(error: String? = null) {
        cancelAudio()
        DiagnosticLog.record("VisionSpeech", "read finished error=${error != null}")
        if (error != null) unavailable(error)
        onTurnFinished()
    }
    fun detach() {
        stop(); composed = false; ttsGeneration++
        permissionInFlight = false; ttsReady = false
        val previous = speaker; speaker = null
        runCatching { previous?.shutdown() }
        audioScope.cancel(); cloud.close()
        onText = {}; onBeforeListen = { false }; onTurnFinished = {}; onPermissionPendingChange = {}; requestPermission = {}
    }
    private fun voiceLocale(value: String = language) = Locale.forLanguageTag(when (value) {
        "ar" -> "ar-SA"; "ur" -> "ur-PK"; "id" -> "id-ID"; "tr" -> "tr-TR"; else -> "en-US"
    })
}

@Composable
internal fun rememberVisionSpeech(language: String, initialReplyRevision: Long = 0L,
    muted: Boolean = false, cloudEnabled: Boolean = false,
    onBeforeListen: () -> Boolean = { true }, onPermissionPendingChange: (Boolean) -> Unit = {},
    onTurnFinished: () -> Unit = {}, onText: (String) -> Unit): VisionSpeech {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val voice = remember { VisionSpeech() }
    val runtime = remember(context, lifecycle) { VisionSpeechRuntime(context, lifecycle, voice, initialReplyRevision) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), runtime::permissionResult)
    SideEffect {
        runtime.setLanguage(language)
        runtime.onText = onText; runtime.onBeforeListen = onBeforeListen
        runtime.onTurnFinished = onTurnFinished; runtime.onPermissionPendingChange = onPermissionPendingChange
        runtime.setAudioOptions(muted, cloudEnabled)
        runtime.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
        voice.listen = runtime::listen; voice.read = { runtime.read(it) }; voice.readArabic = { runtime.read(it, "ar") }
        voice.readReply = runtime::readReply; voice.stop = runtime::stop
    }
    DisposableEffect(runtime, lifecycle) {
        runtime.attach()
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_PAUSE -> runtime.pause()
            Lifecycle.Event.ON_RESUME -> runtime.resume()
            Lifecycle.Event.ON_STOP -> runtime.stop()
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runtime.detach() }
    }
    return voice
}
