package com.sarab.vision.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.MainThread
import com.sarab.vision.diagnostics.DiagnosticLog
import com.sarab.vision.platform.PlatformException
import com.sarab.vision.platform.PlatformStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * Activity-owned, foreground-only analysis of the selected Eye or phone camera.
 * All entry points run on Main; the Activity resets consent when sources change.
 * Consent is deliberately memory-only; a new controller always starts disabled.
 * A selected item freezes its captured image and pauses automatic replacement.
 */
@MainThread
class EyeVisionController internal constructor(
    context: Context,
    parentScope: CoroutineScope,
    private val requestOverride: (suspend (String, JSONObject, String, String) -> JSONObject)? = null,
) {
    private val store = PlatformStore.get(context)
    private val preferences = context.applicationContext.getSharedPreferences("smart_mutawwif_vision", Context.MODE_PRIVATE)
    private val api = VisionApi(store)
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(EyeVisionState(
        language = preferences.getString("language", store.snapshot.language)?.takeIf { it in VisionPolicy.languages } ?: "ar",
        translate = preferences.getBoolean("translate", true),
    ))
    val state = mutable.asStateFlow()
    private var foreground = false
    private var cameraAvailable = false
    private var closed = false
    private var epoch = 0L
    private var analysisRevision = 0L
    private var enrollmentToken = store.token
    private var enrollmentServer = store.server
    private var latestFrame: CapturedFrame? = null
    private var currentPose: VisionPose? = null
    private val requestGate = VisionRequestGate()
    private var requestJob: Job? = null
    private var retainedImage: EncodedVisionImage? = null
    private var importedImage: EncodedVisionImage? = null
    private var importGeneration = 0L
    private var pausedBeforePin = false
    private var steadyPose: VisionPose? = null
    private var steadyPoseSince: Long? = null
    private var visualSteadySince: Long? = null
    private var signature: VisionFrameSignature? = null
    private var signatureAt = -1L
    private var signatureJob: Job? = null
    private var microphonePermissionPending = false
    private var voiceContext: VoiceContext? = null
    private var queuedVoice: PendingVoice? = null
    private var replyRevision = 0L
    private val retryPolicy = VisionRetryPolicy()
    private var lastAnalyzedSignature: VisionFrameSignature? = null
    private var lastAnalyzedAt: Long? = null
    private val trackingLock = Any()
    @Volatile private var trackingGeneration = 0L
    private var trackingJob: Job? = null
    private var trackingSceneId: String? = null
    private var lastTrackedBoxes: List<VisionTrackedBox> = emptyList()
    private var trackingEligibleItemIds: Set<String> = emptySet()
    private var sampledAt = -1L
    // These are accessed only while holding trackingLock on Dispatchers.Default.
    private var tracker: VisionObjectTracker? = null
    private var workerSceneId: String? = null
    private val trackingHistory = ArrayDeque<VisionTrackingFrame>()

    private data class CapturedFrame(val bitmap: Bitmap, val pose: VisionPose?, val at: Long)
    private data class VoiceContext(val frame: CapturedFrame?, val encoded: EncodedVisionImage?,
        val sceneId: String?, val selected: VisionItem?, val pinned: Boolean)
    private data class PendingVoice(val question: String, val context: VoiceContext, val epoch: Long,
        val token: String, val server: String, val language: String, val history: List<VisionHistoryEntry>)

    init {
        scope.launch {
            while (isActive) {
                delay(750)
                checkEnrollment()
                refreshMarkers()
                analyze(manual = false)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (closed || enabled == mutable.value.enabled) return
        record("enabled=$enabled")
        invalidate(clearScene = true, reason = "consent")
        latestFrame = null
        mutable.value = mutable.value.copy(enabled = enabled, paused = false, error = null)
    }

    /** Imports a bounded immutable copy locally; selecting a file never sends a request. */
    suspend fun selectImportedImage(bitmap: Bitmap): Boolean {
        if (closed || bitmap.isRecycled) return false
        checkEnrollment()
        invalidate(clearScene = true, reason = "imported_image")
        val generation = ++importGeneration
        val capturedEpoch = epoch
        latestFrame = null
        mutable.value = mutable.value.copy(importingImage = true, error = null)
        var prepared: EncodedVisionImage? = null
        var published = false
        try {
            val encoded = encodeVisionImage(bitmap)
            prepared = encoded
            coroutineContext.ensureActive()
            if (closed || generation != importGeneration || capturedEpoch != epoch) return false
            importedImage = encoded
            retryPolicy.newImage()
            mutable.value = mutable.value.copy(importedImage = encoded.preview, importingImage = false,
                retryAtElapsedMs = retryPolicy.retryAtElapsedMs, automaticRetryBlocked = retryPolicy.automaticBlocked)
            published = true
            record("input source=imported")
            return true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (!closed && generation == importGeneration && capturedEpoch == epoch) error(VisionError.IMAGE_ENCODING)
            return false
        } finally {
            // Compose may still draw previously published Bitmaps after replacement.
            if (!published) prepared?.preview?.recycle()
            if (generation == importGeneration) mutable.value = mutable.value.copy(importingImage = false)
        }
    }

    fun returnToLive() {
        if (closed) return
        ++importGeneration
        invalidate(clearScene = true, reason = "live_input")
        importedImage = null
        latestFrame = null
        currentPose = null
        mutable.value = mutable.value.copy(importedImage = null, importingImage = false)
        record("input source=live")
    }

    fun setPaused(paused: Boolean) {
        if (closed) return
        val wasPaused = mutable.value.paused
        if (mutable.value.scenePinned) pausedBeforePin = paused
        if (paused && mutable.value.analyzing) invalidate(clearScene = false, reason = "pause")
        mutable.value = mutable.value.copy(paused = paused || mutable.value.scenePinned)
        if (wasPaused != mutable.value.paused) record("paused=${mutable.value.paused}")
        if (!paused) analyze(manual = false)
    }

    fun setForeground(active: Boolean) {
        if (closed || foreground == active) return
        foreground = active
        if (!active) {
            invalidate(clearScene = !microphonePermissionPending, reason = "background")
            latestFrame = null
        }
    }

    /** An OS microphone permission sheet can pause us without leaving the visible app. */
    fun setMicrophonePermissionPending(pending: Boolean) { microphonePermissionPending = pending && !closed }

    fun onStopped() {
        if (closed || foreground) return
        microphonePermissionPending = false
        invalidate(clearScene = true, reason = "background_stop")
        latestFrame = null
    }

    fun setCameraAvailable(available: Boolean) {
        if (closed || cameraAvailable == available) return
        cameraAvailable = available
        if (!available) {
            if (importedImage != null || mutable.value.importingImage) {
                latestFrame = null
                currentPose = null
                return
            }
            invalidate(clearScene = !microphonePermissionPending, reason = "camera_unavailable")
            latestFrame = null
            currentPose = null
        }
    }

    fun setLanguage(language: String) {
        if (closed || language !in VisionPolicy.languages || language == mutable.value.language) return
        invalidate(clearScene = true, reason = "language")
        preferences.edit().putString("language", language).apply()
        store.language(language)
        mutable.value = mutable.value.copy(language = language)
    }

    fun setTranslate(translate: Boolean) {
        if (closed || translate == mutable.value.translate) return
        invalidate(clearScene = true, reason = "translation")
        preferences.edit().putBoolean("translate", translate).apply()
        mutable.value = mutable.value.copy(translate = translate)
    }

    fun onFrame(bitmap: Bitmap, pose: VisionPose? = null, capturedAtElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (closed) return
        checkEnrollment()
        onPose(pose)
        if (!mutable.value.enabled || !foreground || !cameraAvailable || bitmap.isRecycled ||
            importedImage != null || mutable.value.importingImage) return
        // The same immutable object is not evidence of a fresh camera frame.
        if (latestFrame?.bitmap !== bitmap) latestFrame = CapturedFrame(bitmap, currentPose, capturedAtElapsedMs)
        sampleTracking(latestFrame!!)
        sampleSignature(bitmap, capturedAtElapsedMs)
        refreshMarkers()
        analyze(manual = false)
    }

    fun onPose(pose: VisionPose?) {
        if (closed) return
        currentPose = pose?.takeIf(VisionPolicy::validPose)
        if (!VisionPolicy.nearPose(steadyPose, currentPose)) {
            steadyPose = currentPose
            steadyPoseSince = if (currentPose == null) null else SystemClock.elapsedRealtime()
        }
        // Motion controls live overlays, not whether a captured photograph can be answered.
        refreshMarkers()
    }

    fun analyzeNow() {
        if (mutable.value.conversationActive && !requestGate.busy) clearConversation()
        analyze(manual = true)
    }

    /** The first tap captures the question's visual context, before speech recognition starts. */
    fun beginVoiceTurn(): Boolean {
        if (closed) return false
        checkEnrollment()
        val context = voiceCapture()
        val problem = consentProblem(allowRetained = context?.pinned == true)
        if (problem != null) { error(problem); return false }
        if (rejectDuringRetry("voice", SystemClock.elapsedRealtime())) return false
        if (context == null) { error(VisionError.FRAME_STALE); return false }
        if (mutable.value.asking) return false
        voiceContext = context
        mutable.value = mutable.value.copy(voiceBusy = true,
            conversationActive = !context.pinned, error = null)
        record("voice begin context=${if (context.pinned) "selected" else "live"}")
        return true
    }

    fun endVoiceTurn() {
        voiceContext = null
        if (!closed) mutable.value = mutable.value.copy(voiceBusy = false)
    }

    fun clearConversation() {
        if (closed) return
        invalidate(clearScene = false, reason = "conversation_close")
        mutable.value = mutable.value.copy(conversationActive = false, voiceBusy = false,
            reply = null, replySources = emptyList(), replyProvider = null, history = emptyList(), error = null)
    }

    fun askLive(question: String) {
        if (closed) return
        checkEnrollment()
        val context = voiceContext?.takeUnless { it.pinned } ?: voiceCapture(pinned = false)
        if (context == null) { error(VisionError.FRAME_STALE); endVoiceTurn(); return }
        submitQuestion(question, context.copy(pinned = false, selected = null, sceneId = null))
    }

    private fun voiceCapture(pinned: Boolean = mutable.value.scenePinned && !mutable.value.conversationActive): VoiceContext? {
        val before = mutable.value
        if (before.importingImage) return null
        if (pinned && before.scene != null && retainedImage != null) {
            return VoiceContext(null, retainedImage, before.scene.sceneId, before.selectedItem, true)
        }
        importedImage?.let { return VoiceContext(null, it, null, null, false) }
        val frame = latestFrame?.takeIf {
            VisionPolicy.freshTimestamp(it.at, SystemClock.elapsedRealtime(), VisionPolicy.FRAME_FRESH_MS)
        } ?: return null
        return VoiceContext(frame, null, null, null, false)
    }

    fun pinScene() {
        val before = mutable.value
        if (closed || before.scene == null || before.scenePinned) return
        pausedBeforePin = before.paused
        invalidate(clearScene = false, reason = "scene_pin")
        mutable.value = mutable.value.copy(scenePinned = true, paused = true, markersVisible = false,
            conversationActive = false,
            reply = if (before.conversationActive) null else before.reply,
            replySources = if (before.conversationActive) emptyList() else before.replySources,
            replyProvider = if (before.conversationActive) null else before.replyProvider,
            history = if (before.conversationActive) emptyList() else before.history)
    }

    fun unpinScene() {
        if (closed || !mutable.value.scenePinned) return
        invalidate(clearScene = false, reason = "scene_unpin")
        mutable.value = mutable.value.copy(scenePinned = false, selectedItemId = null, paused = pausedBeforePin,
            reply = null, replySources = emptyList(), replyProvider = null, history = emptyList(), error = null)
        refreshMarkers()
    }

    fun selectItem(id: String?) {
        if (closed) return
        val before = mutable.value
        if (id != null && before.scene?.items?.none { it.id == id } != false) return
        if (id != null) pinScene()
        if (id == mutable.value.selectedItemId) return
        invalidate(clearScene = false, reason = "selection")
        mutable.value = mutable.value.copy(selectedItemId = id,
            reply = null, replySources = emptyList(), replyProvider = null, history = emptyList(), error = null)
        refreshMarkers()
    }

    fun ask(question: String) {
        if (closed) return
        checkEnrollment()
        pinScene()
        val context = voiceContext?.takeIf { it.pinned } ?: voiceCapture(pinned = true)
        if (context == null || !context.pinned) { error(VisionError.SCENE_REQUIRED); endVoiceTurn(); return }
        submitQuestion(question, context)
    }

    private fun submitQuestion(question: String, context: VoiceContext) {
        if (mutable.value.importingImage) { error(VisionError.FRAME_STALE); endVoiceTurn(); return }
        val problem = consentProblem(allowRetained = context.pinned)
        if (problem != null) { error(problem); endVoiceTurn(); return }
        if (rejectDuringRetry("ask", SystemClock.elapsedRealtime())) { endVoiceTurn(); return }
        val text = question.trim().take(2000)
        if (text.isBlank()) { error(VisionError.EMPTY_QUESTION); endVoiceTurn(); return }
        if (mutable.value.asking && queuedVoice == null) return
        val before = mutable.value
        val pending = PendingVoice(text, context, epoch, enrollmentToken, enrollmentServer,
            before.language, before.history)
        voiceContext = null
        mutable.value = before.copy(conversationActive = !context.pinned, asking = true,
            requestBusy = true, error = null, reply = null, replySources = emptyList())
        if (requestGate.busy) {
            // One spoken turn gets the next slot. The photograph was taken at the mic tap;
            // it must not silently become the next background analysis photograph.
            queuedVoice = pending
            record("voice queued")
        } else runQuestion(pending)
    }

    private fun drainVoiceQuestion() {
        val pending = queuedVoice ?: return
        if (requestGate.busy) return
        queuedVoice = null
        if (accepts(pending.epoch, pending.token, pending.server, pending.context.pinned)) runQuestion(pending)
        else mutable.value = mutable.value.copy(asking = false, requestBusy = false, voiceBusy = false)
    }

    private fun runQuestion(pending: PendingVoice) {
        val context = pending.context
        val startedAt = SystemClock.elapsedRealtime()
        if (rejectDuringRetry("ask_queued", startedAt)) {
            mutable.value = mutable.value.copy(asking = false, requestBusy = false, voiceBusy = false)
            return
        }
        val ticket = requestGate.start(startedAt, automatic = false) ?: return
        record("request_start kind=ask context=${if (context.pinned) "selected" else "live"}")
        mutable.value = mutable.value.copy(asking = true, requestBusy = true)
        requestJob = scope.launch {
            try {
                val encoded = context.encoded ?: try { encodeVisionImage(context.frame!!.bitmap) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { throw VisionEncodingFailure() }
                coroutineContext.ensureActive()
                if (!requestGate.accepts(ticket) || !accepts(pending.epoch, pending.token, pending.server, context.pinned)) return@launch
                val body = JSONObject().put("image", VisionPayloads.image(encoded.base64))
                    .put("language", pending.language).put("question", pending.question)
                    .put("history", VisionPayloads.history(pending.history))
                context.selected?.let { body.put("selectedItem", VisionPayloads.selected(it)) }
                val raw = requestVision("/vision/ask", body, pending.server, pending.token)
                coroutineContext.ensureActive()
                if (!requestGate.accepts(ticket) || !accepts(pending.epoch, pending.token, pending.server, context.pinned) ||
                    (context.pinned && mutable.value.scene?.sceneId != context.sceneId)) return@launch
                val answer = runCatching { VisionPayloads.answer(raw) }.getOrElse { throw InvalidVisionResponse() }
                requestSucceeded()
                record("request_ok kind=ask elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
                mutable.value = mutable.value.copy(reply = answer.reply, replySources = answer.sources,
                    replyProvider = answer.provider, conversationRevision = ++replyRevision,
                    history = (pending.history + VisionHistoryEntry("user", pending.question) +
                        VisionHistoryEntry("assistant", answer.reply)).takeLast(6))
            } catch (_: CancellationException) {
                // A lifecycle/consent/language/selection change deliberately discards the result.
            } catch (failure: Exception) {
                if (requestGate.accepts(ticket) && accepts(pending.epoch, pending.token, pending.server, context.pinned)) {
                    requestFailed(failure)
                    mutable.value = mutable.value.copy(voiceBusy = false)
                }
            } finally {
                requestGate.finish(ticket)
                requestJob = null
                mutable.value = mutable.value.copy(asking = false, requestBusy = false)
                drainVoiceQuestion()
            }
        }
    }

    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    fun close() {
        if (closed) return
        closed = true
        foreground = false
        invalidate(clearScene = true, reason = "close")
        latestFrame = null
        ++importGeneration
        importedImage = null
        mutable.value = mutable.value.copy(enabled = false, importedImage = null, importingImage = false)
        scope.cancel()
    }

    private fun analyze(manual: Boolean) {
        if (closed) return
        checkEnrollment()
        val before = mutable.value
        val problem = consentProblem()
        if (problem != null) { if (manual) error(problem); return }
        if (requestGate.busy || before.scenePinned || before.voiceBusy || (!manual && before.paused)) return
        val now = SystemClock.elapsedRealtime()
        if (manual && rejectDuringRetry("analyze", now)) return
        if (!manual && retryPolicy.blocked(now, automatic = true) != null) return
        val imported = importedImage
        if (before.importingImage || (!manual && !VisionInputPolicy.automaticAllowed(imported != null, false, before.paused))) return
        if (!manual && visionSignaturesMatch(lastAnalyzedSignature, signature) &&
            !VisionPolicy.staticRefreshDue(lastAnalyzedAt, now)) return
        if (!manual && before.markersVisible && before.scene?.let { now - it.capturedAtElapsedMs < 18_000 } == true) return
        if (!manual && (!VisionPolicy.validPose(currentPose) ||
                steadyPoseSince?.let { now - it >= VisionPolicy.STEADY_MS } != true ||
                visualSteadySince?.let { now - it >= VisionPolicy.STEADY_MS } != true)) return
        val frame = imported?.let { CapturedFrame(it.preview, null, now) } ?: latestFrame
        if (frame == null || !VisionInputPolicy.frameUsable(imported != null, frame.at, now)) {
            if (manual) error(VisionError.FRAME_STALE)
            return
        }
        val capturedEpoch = epoch
        val revision = analysisRevision
        val token = enrollmentToken
        val server = enrollmentServer
        val ticket = requestGate.start(now, automatic = !manual) ?: return
        record("request_start kind=analyze mode=${if (manual) "manual" else "auto"}")
        mutable.value = before.copy(analyzing = true, requestBusy = true, error = null)
        requestJob = scope.launch {
            try {
                val encoded = imported ?: try { encodeVisionImage(frame.bitmap) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { throw VisionEncodingFailure() }
                coroutineContext.ensureActive()
                if (!requestGate.accepts(ticket) || !accepts(capturedEpoch, token, server) || revision != analysisRevision) return@launch
                val raw = requestVision("/vision/analyze", JSONObject()
                    .put("image", VisionPayloads.image(encoded.base64))
                    .put("language", before.language).put("translate", before.translate), server, token)
                coroutineContext.ensureActive()
                if (!requestGate.accepts(ticket) || !accepts(capturedEpoch, token, server) || revision != analysisRevision) return@launch
                val analysis = runCatching { VisionPayloads.analysis(raw) }.getOrElse { throw InvalidVisionResponse() }
                requestSucceeded()
                lastAnalyzedSignature = encoded.signature
                lastAnalyzedAt = frame.at
                record("request_ok kind=analyze items=${analysis.items.size} elapsedMs=${SystemClock.elapsedRealtime() - now}")
                val scene = VisionScene(analysis.sceneId, analysis.summary, analysis.items, analysis.sources,
                    analysis.provider, encoded.preview, frame.at, frame.pose)
                retainedImage = encoded
                // A manual capture is a photograph the user asked about, even if they move
                // before Gemini replies. Automatic results remain reviewable; only fresh,
                // matching camera/pose samples may project their markers over live video.
                val keepConversation = mutable.value.conversationActive
                val pinResult = manual && !keepConversation
                if (pinResult) pausedBeforePin = before.paused
                mutable.value = mutable.value.copy(scene = scene, selectedItemId = null,
                    scenePinned = pinResult, paused = pinResult || mutable.value.paused,
                    reply = if (keepConversation) mutable.value.reply else null,
                    replySources = if (keepConversation) mutable.value.replySources else emptyList(),
                    replyProvider = if (keepConversation) mutable.value.replyProvider else null,
                    history = if (keepConversation) mutable.value.history else emptyList())
                if (imported == null) seedTracking(scene, frame)
                refreshMarkers()
            } catch (_: CancellationException) {
                // Never publish a result from an invalidated capture.
            } catch (failure: Exception) {
                if (requestGate.accepts(ticket) && accepts(capturedEpoch, token, server) && revision == analysisRevision) {
                    requestFailed(failure)
                }
            } finally {
                requestGate.finish(ticket)
                requestJob = null
                mutable.value = mutable.value.copy(analyzing = false, requestBusy = false)
                drainVoiceQuestion()
            }
        }
    }

    private suspend fun requestVision(path: String, body: JSONObject, server: String, token: String): JSONObject =
        requestOverride?.invoke(path, body, server, token) ?: api.request(path, body, server, token)

    private fun consentProblem(allowRetained: Boolean = false): VisionError? = when {
        !mutable.value.enabled -> VisionError.CONSENT_REQUIRED
        !VisionInputPolicy.available(foreground, cameraAvailable, importedImage != null, allowRetained) -> VisionError.CAMERA_UNAVAILABLE
        !store.snapshot.enrolled || enrollmentToken.isBlank() -> VisionError.ENROLLMENT_REQUIRED
        else -> null
    }

    private fun accepts(capturedEpoch: Long, token: String, server: String, allowRetained: Boolean = false) = !closed &&
        capturedEpoch == epoch && VisionInputPolicy.available(foreground, cameraAvailable, importedImage != null, allowRetained) && mutable.value.enabled &&
        token == store.token && server == store.server && store.snapshot.enrolled

    private fun refreshMarkers() {
        val before = mutable.value
        if (importedImage != null || before.importingImage) {
            if (before.markersVisible || before.trackedBoxes.isNotEmpty())
                mutable.value = before.copy(markersVisible = false, trackedBoxes = emptyList())
            return
        }
        val now = SystemClock.elapsedRealtime()
        val scene = before.scene
        val freshTracked = lastTrackedBoxes.filter {
            VisionPolicy.freshTimestamp(it.atElapsedMs, now, 850) &&
                (currentPose == null || it.sessionId == currentPose?.sessionId)
        }
        val tracked = scene != null && trackingSceneId == scene.sceneId
        val stationary = VisionPolicy.freshTimestamp(signatureAt, now, VisionPolicy.FRAME_FRESH_MS) &&
            visionSignaturesMatch(retainedImage?.signature, signature) && scene != null &&
            VisionPolicy.markersFresh(scene.capturedAtElapsedMs, now, scene.pose, currentPose)
        // Tiny/textureless recognitions can still display at their verified stationary box.
        // A trackable target that has been lost must never regain an old box this way.
        val displayBoxes = freshTracked + if (tracked && stationary) scene!!.items
            .filter { it.id !in trackingEligibleItemIds }.map {
                VisionTrackedBox(it.id, it.box, 0.0, signatureAt, currentPose?.sessionId ?: -epoch)
            } else emptyList()
        val visible = before.enabled && foreground && cameraAvailable && !before.scenePinned &&
            latestFrame?.let { VisionPolicy.freshTimestamp(it.at, now, VisionPolicy.FRAME_FRESH_MS) } == true &&
            (if (tracked) displayBoxes.isNotEmpty() else stationary && scene!!.items.isNotEmpty())
        if (before.markersVisible != visible || displayBoxes != before.trackedBoxes)
            mutable.value = before.copy(markersVisible = visible, trackedBoxes = displayBoxes)
    }

    private fun sampleTracking(frame: CapturedFrame) {
        if (trackingJob?.isActive == true || frame.at - sampledAt < 200) return
        sampledAt = frame.at
        val generation = trackingGeneration
        val capturedEpoch = epoch
        val sessionId = frame.pose?.sessionId ?: -capturedEpoch
        trackingJob = scope.launch {
            try {
                val result: Triple<String?, List<VisionTrackedBox>, Set<String>>? = withContext(Dispatchers.Default) {
                    val sampled = sampleVisionTrackingFrame(frame.bitmap, frame.at, sessionId)
                    synchronized<Triple<String?, List<VisionTrackedBox>, Set<String>>?>(trackingLock) {
                        if (trackingGeneration != generation) return@synchronized null
                        trackingHistory.addLast(sampled)
                        while (trackingHistory.size > 40 ||
                            trackingHistory.firstOrNull()?.let { sampled.atElapsedMs - it.atElapsedMs > 8_000 } == true)
                            trackingHistory.removeFirst()
                        Triple(workerSceneId, tracker?.update(sampled) ?: emptyList(), tracker?.eligibleItemIds ?: emptySet())
                    }
                }
                if (result != null && !closed && epoch == capturedEpoch && generation == trackingGeneration &&
                    result.first != null && mutable.value.scene?.sceneId == result.first) {
                    trackingSceneId = result.first
                    lastTrackedBoxes = result.second
                    trackingEligibleItemIds = result.third
                    refreshMarkers()
                }
            } catch (_: CancellationException) {
                // Source/lifecycle changes invalidate queued sampling as well.
            } catch (_: Exception) {
                if (generation == trackingGeneration) {
                    lastTrackedBoxes = emptyList()
                    mutable.value = mutable.value.copy(trackedBoxes = emptyList(), markersVisible = false)
                }
            }
        }
    }

    private fun seedTracking(scene: VisionScene, frame: CapturedFrame) {
        val generation = ++trackingGeneration
        val capturedEpoch = epoch
        trackingSceneId = null
        lastTrackedBoxes = emptyList()
        trackingEligibleItemIds = emptySet()
        mutable.value = mutable.value.copy(trackedBoxes = emptyList())
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val captured = sampleVisionTrackingFrame(frame.bitmap, frame.at, frame.pose?.sessionId ?: -capturedEpoch)
                    synchronized(trackingLock) {
                        if (generation != trackingGeneration) return@synchronized (emptyList<VisionTrackedBox>() to emptySet<String>())
                        val next = VisionObjectTracker(captured, scene.items.map { VisionTrackingTarget(it.id, it.box) })
                        tracker = next
                        workerSceneId = scene.sceneId
                        val newer = trackingHistory.filter { it.atElapsedMs > captured.atElapsedMs }
                        var boxes = if (newer.isEmpty()) next.update(captured) else emptyList()
                        newer.forEach { boxes = next.update(it) }
                        boxes to next.eligibleItemIds
                    }
                }
                if (!closed && capturedEpoch == epoch && generation == trackingGeneration && mutable.value.scene?.sceneId == scene.sceneId) {
                    trackingSceneId = scene.sceneId
                    lastTrackedBoxes = result.first
                    trackingEligibleItemIds = result.second
                    record("tracking seeded items=${scene.items.size} visible=${result.first.size}")
                    refreshMarkers()
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) {
                if (generation == trackingGeneration) {
                    trackingSceneId = scene.sceneId
                    lastTrackedBoxes = emptyList()
                    mutable.value = mutable.value.copy(trackedBoxes = emptyList(), markersVisible = false)
                }
            }
        }
    }

    private fun sampleSignature(bitmap: Bitmap, capturedAt: Long) {
        if (signatureJob?.isActive == true || capturedAt - signatureAt < VisionPolicy.SIGNATURE_INTERVAL_MS) return
        val capturedEpoch = epoch
        signatureJob = scope.launch {
            try {
                val sampled = withContext(Dispatchers.Default) { visionFrameSignature(bitmap) }
                if (closed || epoch != capturedEpoch || !foreground || !mutable.value.enabled || !cameraAvailable) return@launch
                val difference = visionSignatureDifference(signature, sampled)
                if (difference == null || difference > 8.0 / 255.0) visualSteadySince = SystemClock.elapsedRealtime()
                signature = sampled
                signatureAt = capturedAt
                // Exposure, handheld motion and late signature samples must not cancel a
                // captured-scene answer. refreshMarkers independently hides stale overlays.
                refreshMarkers()
            } catch (_: CancellationException) {
                // A changed consent epoch discards the low-resolution sample as well.
            } catch (_: Exception) {
                signature = null
                visualSteadySince = null
                refreshMarkers()
            }
        }
    }

    private fun checkEnrollment() {
        if (enrollmentToken == store.token && enrollmentServer == store.server) return
        enrollmentToken = store.token
        enrollmentServer = store.server
        retryPolicy.success()
        invalidate(clearScene = true, reason = "binding")
        latestFrame = null
        // A different cloud binding needs a new opt-in, even within the same Activity.
        mutable.value = mutable.value.copy(enabled = false, paused = false)
    }

    private fun invalidate(clearScene: Boolean, reason: String = "context") {
        if (requestGate.busy) record("discard reason=$reason")
        epoch++
        requestGate.invalidate()
        analysisRevision++
        requestJob?.cancel()
        voiceContext = null
        queuedVoice = null
        mutable.value = mutable.value.copy(voiceBusy = false, asking = false)
        if (clearScene) {
            val generation = ++trackingGeneration
            trackingJob?.cancel()
            trackingJob = null
            trackingSceneId = null
            lastTrackedBoxes = emptyList()
            trackingEligibleItemIds = emptySet()
            sampledAt = -1L
            scope.launch(Dispatchers.Default) { synchronized(trackingLock) {
                if (generation == trackingGeneration) {
                    tracker?.clear(); tracker = null; workerSceneId = null; trackingHistory.clear()
                }
            } }
            val restorePaused = if (mutable.value.scenePinned) pausedBeforePin else mutable.value.paused
            retainedImage = null
            pausedBeforePin = false
            steadyPose = null
            steadyPoseSince = null
            visualSteadySince = null
            signature = null
            signatureAt = -1L
            signatureJob?.cancel()
            signatureJob = null
            lastAnalyzedSignature = null
            lastAnalyzedAt = null
            mutable.value = mutable.value.copy(scene = null, selectedItemId = null, scenePinned = false, paused = restorePaused, reply = null,
                replySources = emptyList(), replyProvider = null, history = emptyList(), markersVisible = false,
                analyzing = false, asking = false, error = retryPolicy.lastFailure?.error, conversationActive = false,
                retryAtElapsedMs = retryPolicy.retryAtElapsedMs, automaticRetryBlocked = retryPolicy.automaticBlocked,
                trackedBoxes = emptyList())
        }
    }

    private fun error(error: VisionError) {
        record("error code=${error.name}")
        mutable.value = mutable.value.copy(error = error)
    }
    private fun rejectDuringRetry(kind: String, now: Long): Boolean {
        val previous = retryPolicy.blocked(now, automatic = false) ?: return false
        record("request_not_sent kind=$kind reason=retry_wait remainingMs=${(retryPolicy.retryAtElapsedMs - now).coerceAtLeast(0)} previousError=${previous.name}")
        // Retain the captured result and prior failure; the UI presents its countdown,
        // rather than claiming this explicit action made a new failed network request.
        return true
    }
    /** Only fixed event codes, booleans, counts, and elapsed durations may enter diagnostics. */
    private fun record(metadata: String) { DiagnosticLog.record("Vision", metadata) }
    private fun requestSucceeded() {
        retryPolicy.success()
        mutable.value = mutable.value.copy(error = null, retryAtElapsedMs = 0, automaticRetryBlocked = false)
    }
    private fun requestFailed(failure: Exception) {
        val mapped = when (failure) {
            is VisionRequestFailure -> failure
            is InvalidVisionResponse -> VisionRequestFailure(VisionError.INVALID_RESPONSE, true)
            is VisionEncodingFailure -> VisionRequestFailure(VisionError.IMAGE_ENCODING, false)
            is SocketTimeoutException -> VisionRequestFailure(VisionError.TIMEOUT, true)
            is PlatformException -> VisionFailurePayloads.parse(failure.status, null, null)
            else -> VisionRequestFailure(VisionError.NETWORK, true)
        }
        retryPolicy.failure(mapped, SystemClock.elapsedRealtime())
        mutable.value = mutable.value.copy(retryAtElapsedMs = retryPolicy.retryAtElapsedMs,
            automaticRetryBlocked = retryPolicy.automaticBlocked)
        error(mapped.error)
    }
    private class InvalidVisionResponse : Exception()
    private class VisionEncodingFailure : Exception()
}
