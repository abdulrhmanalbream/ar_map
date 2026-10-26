package com.sarab.vision

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.sarab.vision.core.GuidanceMode
import com.sarab.vision.core.NAVIGATION_CLIP_IDS
import com.sarab.vision.core.NavigationVoiceOption
import com.sarab.vision.core.NavigationSpeechSegment
import com.sarab.vision.core.navigationSelectionSpeech
import com.sarab.vision.core.navigationSpeechPlan
import com.sarab.vision.core.selectNavigationVoice
import com.sarab.vision.diagnostics.DiagnosticLog

/** Fixed metadata only; main-thread snapshot used by the guarded playback fixture. */
internal data class NavigationPlaybackSnapshot(
    val activeClipId: String?, val queuedSegments: Int, val generation: Long,
    val startedClips: Int, val completedClips: Int, val cancelledClips: Int, val failedClips: Int,
    val textActive: Boolean, val muted: Boolean, val closed: Boolean,
)

/** Bundled Arabic navigation recordings, with installed offline TTS for dynamic names/distances. */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioPrefs = appContext.getSharedPreferences("smart_mutawwif_audio", Context.MODE_PRIVATE)
    private var muted = audioPrefs.getBoolean("muted", false)
    private var ready = false
    private var closed = false
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var activeClip: NavigationSpeechSegment.Clip? = null
    private var activeTextId: String? = null
    private var deadline: Runnable? = null
    private val pending = ArrayDeque<NavigationSpeechSegment>()
    private var generation = 0L
    private var textSequence = 0L
    private var startedClips = 0
    private var completedClips = 0
    private var cancelledClips = 0
    private var failedClips = 0
    /** Read on the main thread. Includes preparation and every queued segment of one phrase. */
    val isSpeaking: Boolean
        get() = !closed && (player != null || activeTextId != null || pending.isNotEmpty())
    /** Current persisted mute, including a preference update awaiting its listener. */
    val isMuted: Boolean
        get() = readMute()
    private val muteListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && (key == "muted" || key == null)) {
            muted = audioPrefs.getBoolean("muted", false)
            if (muted) stop()
            // No pending cue is retained or replayed when unmuted.
        }
    }

    init {
        audioPrefs.registerOnSharedPreferenceChangeListener(muteListener)
        try {
            tts = TextToSpeech(appContext) { status -> main.post {
                if (closed) return@post
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    DiagnosticLog.record("NavigationVoice", "initialization failed code=$status")
                    return@post
                }
                runCatching {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { main.post { finishText(utteranceId, false) } }
                        @Deprecated("Legacy callback")
                        override fun onError(utteranceId: String?) { main.post { finishText(utteranceId, true) } }
                        override fun onError(utteranceId: String?, errorCode: Int) { main.post { finishText(utteranceId, true) } }
                    })
                    val voices = engine.voices.orEmpty()
                    val selected = selectNavigationVoice(voices.map {
                        NavigationVoiceOption(it.name, it.locale.language, it.locale.country, it.quality,
                            it.isNetworkConnectionRequired,
                            !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                    })
                    val voice = voices.firstOrNull { it.name == selected?.id }
                    ready = voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS &&
                        engine.setSpeechRate(1f) == TextToSpeech.SUCCESS
                }.onFailure { ready = false }
                DiagnosticLog.record("NavigationVoice", "installed offline Arabic ready=$ready muted=${readMute()}")
                if (muted) stop()
            } }
        } catch (_: Exception) {
            DiagnosticLog.record("NavigationVoice", "initialization unavailable")
        }
    }

    fun speakGuidance(mode: GuidanceMode, targetName: String, manualRepeat: Boolean = false) =
        play(navigationSpeechPlan(mode, targetName, manualRepeat))

    fun speakSelected(targetName: String) = play(navigationSelectionSpeech(targetName))

    fun speakName(name: String) = speak(name)

    fun speak(text: String) {
        if (text.isNotBlank()) play(listOf(NavigationSpeechSegment.Text(text)))
    }

    private fun play(segments: List<NavigationSpeechSegment>) = onMain {
        if (closed || segments.isEmpty()) return@onMain
        if (readMute()) {
            DiagnosticLog.record("NavigationVoice", "cue skipped reason=muted")
            stopInternal(); return@onMain
        }
        // No old direction, distance or name survives a newer accepted instruction.
        stopInternal()
        pending.addAll(segments.take(8))
        advance(generation)
    }

    private fun advance(token: Long) {
        if (closed || token != generation || readMute()) return
        when (val next = pending.removeFirstOrNull() ?: return) {
            is NavigationSpeechSegment.Clip -> startClip(next, token)
            is NavigationSpeechSegment.Text -> startText(next.text, token)
        }
    }

    private fun startClip(clip: NavigationSpeechSegment.Clip, token: Long) {
        if (clip.id !in NAVIGATION_CLIP_IDS) { startText(clip.fallbackText, token); return }
        val next = try { MediaPlayer() } catch (_: Exception) {
            failedClips = increment(failedClips)
            DiagnosticLog.record("NavigationVoice", "clip unavailable id=${clip.id}")
            startText(clip.fallbackText, token)
            return
        }
        player = next
        activeClip = clip
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setOnPreparedListener { prepared ->
                if (token != generation || player !== prepared || closed) return@setOnPreparedListener
                if (readMute()) { stopInternal(); return@setOnPreparedListener }
                try {
                    prepared.start()
                    startedClips = increment(startedClips)
                    DiagnosticLog.record("NavigationVoice", "clip started id=${clip.id}")
                    setDeadline((prepared.duration.toLong() + 2_000).coerceIn(4_000, 22_000)) {
                        finishClip(prepared, token, failed = true)
                    }
                } catch (_: Exception) { finishClip(prepared, token, failed = true) }
            }
            next.setOnCompletionListener { finishClip(it, token, failed = false) }
            next.setOnErrorListener { failed, what, extra ->
                DiagnosticLog.record("NavigationVoice", "clip error code=$what extra=$extra")
                finishClip(failed, token, failed = true)
                true
            }
            appContext.assets.openFd("navigation/ar/${clip.id}.wav").use {
                next.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            setDeadline(5_000) { finishClip(next, token, failed = true) }
            next.prepareAsync()
        } catch (_: Exception) { finishClip(next, token, failed = true) }
    }

    private fun finishClip(completed: MediaPlayer, token: Long, failed: Boolean) {
        if (token != generation || player !== completed) return
        clearDeadline()
        val clip = activeClip
        player = null
        activeClip = null
        runCatching { completed.release() }
        if (failed) {
            failedClips = increment(failedClips)
            DiagnosticLog.record("NavigationVoice", "clip failed id=${clip?.id.orEmpty()}")
            if (clip != null) startText(clip.fallbackText, token) else advance(token)
        } else {
            completedClips = increment(completedClips)
            DiagnosticLog.record("NavigationVoice", "clip completed id=${clip?.id.orEmpty()}")
            advance(token)
        }
    }

    private fun startText(text: String, token: Long) {
        if (closed || token != generation || readMute()) return
        if (!ready || text.isBlank()) { advance(token); return }
        val id = "navigation-$token-${++textSequence}"
        activeTextId = id
        try {
            setDeadline(15_000) {
                if (activeTextId == id) {
                    runCatching { tts?.stop() }
                    finishText(id, true)
                }
            }
            val status = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (status != TextToSpeech.SUCCESS) finishText(id, true)
        } catch (_: Exception) { finishText(id, true) }
    }

    private fun finishText(id: String?, failed: Boolean) {
        if (id == null || id != activeTextId) return
        clearDeadline()
        activeTextId = null
        DiagnosticLog.record("NavigationVoice", "dynamic speech completed failed=$failed")
        advance(generation)
    }

    private fun readMute() = muted || audioPrefs.getBoolean("muted", false)

    private fun setDeadline(milliseconds: Long, action: () -> Unit) {
        clearDeadline()
        deadline = Runnable(action).also { main.postDelayed(it, milliseconds) }
    }

    private fun clearDeadline() { deadline?.let(main::removeCallbacks); deadline = null }

    private fun stopInternal() {
        generation++
        clearDeadline()
        pending.clear()
        activeTextId = null
        player?.let {
            cancelledClips = increment(cancelledClips)
            DiagnosticLog.record("NavigationVoice", "clip cancelled id=${activeClip?.id.orEmpty()}")
            runCatching { it.release() }
        }
        player = null
        activeClip = null
        runCatching { tts?.stop() }
    }

    fun stop() = onMain { stopInternal() }

    fun shutdown() = onMain {
        if (closed) return@onMain
        closed = true
        ready = false
        audioPrefs.unregisterOnSharedPreferenceChangeListener(muteListener)
        stopInternal()
        main.removeCallbacksAndMessages(null)
        runCatching { tts?.shutdown() }
        tts = null
    }

    internal fun playbackSnapshot() = NavigationPlaybackSnapshot(activeClip?.id, pending.size, generation,
        startedClips, completedClips, cancelledClips, failedClips, activeTextId != null, readMute(), closed)

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun increment(value: Int) = if (value < Int.MAX_VALUE) value + 1 else value
}
