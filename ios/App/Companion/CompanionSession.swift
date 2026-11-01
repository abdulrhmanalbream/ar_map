import Foundation
import Combine
import UIKit

/// This preview belongs to one captured scene; it is never the changing camera frame.
struct VisionScene {
    let sceneId: String
    let summary: String
    let items: [VisionItem]
    let sources: [VisionSource]
    let provider: String
    let preview: UIImage
    let capturedAtElapsedMs: Int64
    let pose: VisionPose?
    var imageWidth: Int { Int(preview.size.width * preview.scale) }
    var imageHeight: Int { Int(preview.size.height * preview.scale) }
}

/// Foreground-only analysis of the phone camera or an imported photo — the iOS port of
/// EyeVisionController. One shared instance so the phone screen and the glasses overlay
/// show the same scene/answer. All entry points run on the main thread.
///
/// Differences from Android, deliberately conservative:
/// - No local object tracker: live markers show only while the phone stays within 3° of
///   the captured pose and the low-resolution scene signature still matches; otherwise
///   they hide (the snapshot result stays reviewable).
/// - Consent is memory-only, as on Android: every launch starts with cloud sharing off,
///   and a group binding change clears it, so uploads always follow a fresh opt-in.
final class CompanionSession: ObservableObject {
    static let shared = CompanionSession()

    // Published UI state (mirrors EyeVisionState).
    @Published private(set) var enabled: Bool
    @Published private(set) var paused = false
    @Published private(set) var translate: Bool
    @Published private(set) var analyzing = false
    @Published private(set) var asking = false
    @Published private(set) var requestBusy = false
    @Published private(set) var error: VisionError?
    @Published private(set) var scene: VisionScene?
    @Published private(set) var selectedItemId: String?
    @Published private(set) var scenePinned = false
    @Published private(set) var reply: String?
    @Published private(set) var replySources: [VisionSource] = []
    @Published private(set) var history: [VisionHistoryEntry] = []
    @Published private(set) var markersVisible = false
    @Published private(set) var conversationActive = false
    @Published private(set) var conversationRevision: Int64 = 0
    @Published private(set) var voiceBusy = false
    /// Local, memory-only photo. Its presence never grants permission to upload it.
    @Published private(set) var importedImage: UIImage?
    @Published private(set) var importingImage = false
    @Published private(set) var retryAtElapsedMs: Int64 = 0
    @Published private(set) var automaticRetryBlocked = false
    /// Set by the speech layer so the glasses overlay can show listening/speaking.
    @Published var listening = false
    @Published var speaking = false

    var isImportedImage: Bool { importedImage != nil }
    var selectedItem: VisionItem? { scene?.items.first { $0.id == selectedItemId } }
    var language: String { CompanionLanguage.shared.code }

    private let store = PlatformStore.shared
    private let defaults = UserDefaults.standard
    private var foreground = false
    private var cameraAvailable = false
    private var epoch: Int64 = 0
    private var analysisRevision: Int64 = 0
    private var enrollmentToken: String
    private var enrollmentServer: String
    private var latestFrame: CapturedFrame?
    private var currentPose: VisionPose?
    private let requestGate = VisionRequestGate()
    private var requestTask: Task<Void, Never>?
    private var retainedImage: EncodedVisionImage?
    private var importedEncoded: EncodedVisionImage?
    private var importGeneration: Int64 = 0
    private var pausedBeforePin = false
    private var steadyPose: VisionPose?
    private var steadyPoseSince: Int64?
    private var visualSteadySince: Int64?
    private var signature: VisionFrameSignature?
    private var signatureAt: Int64 = -1
    private var signatureBusy = false
    private var voiceContext: VoiceContext?
    private var queuedVoice: PendingVoice?
    private var replyRevision: Int64 = 0
    private let retryPolicy = VisionRetryPolicy()
    private var lastAnalyzedSignature: VisionFrameSignature?
    private var lastAnalyzedAt: Int64?
    private var timer: Timer?
    private var languageObserver: AnyCancellable?

    private struct CapturedFrame {
        let image: UIImage
        let pose: VisionPose?
        let at: Int64
    }
    private struct VoiceContext {
        var frame: CapturedFrame?
        var encoded: EncodedVisionImage?
        var sceneId: String?
        var selected: VisionItem?
        var pinned: Bool
    }
    private struct PendingVoice {
        let question: String
        let context: VoiceContext
        let epoch: Int64
        let token: String
        let server: String
        let language: String
        let history: [VisionHistoryEntry]
    }

    init() {
        enrollmentToken = PlatformStore.shared.token
        enrollmentServer = PlatformStore.shared.server
        enabled = false
        translate = (UserDefaults.standard.object(forKey: "smart_mutawwif_vision.translate") as? Bool) ?? true
        var lastLanguage = CompanionLanguage.shared.code
        languageObserver = CompanionLanguage.shared.$code.sink { [weak self] code in
            guard code != lastLanguage else { return }
            lastLanguage = code
            DispatchQueue.main.async { self?.languageChanged() }
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.75, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.checkEnrollment()
            self.refreshMarkers()
            self.analyze(manual: false)
        }
    }

    // MARK: - Settings and lifecycle

    func setEnabled(_ value: Bool) {
        guard value != enabled else { return }
        invalidate(clearScene: true)
        latestFrame = nil
        enabled = value
        paused = false
        error = nil
    }

    func setTranslate(_ value: Bool) {
        guard value != translate else { return }
        invalidate(clearScene: true)
        defaults.set(value, forKey: "smart_mutawwif_vision.translate")
        translate = value
    }

    private func languageChanged() {
        invalidate(clearScene: true)
    }

    func setPaused(_ value: Bool) {
        if scenePinned { pausedBeforePin = value }
        if value && analyzing { invalidate(clearScene: false) }
        paused = value || scenePinned
        if !value { analyze(manual: false) }
    }

    func setForeground(_ active: Bool) {
        guard foreground != active else { return }
        foreground = active
        if !active {
            invalidate(clearScene: true)
            latestFrame = nil
        }
    }

    func setCameraAvailable(_ available: Bool) {
        guard cameraAvailable != available else { return }
        cameraAvailable = available
        if !available {
            latestFrame = nil
            currentPose = nil
            if importedEncoded != nil || importingImage { return }
            invalidate(clearScene: true)
        }
    }

    func clearError() { error = nil }

    // MARK: - Inputs

    func onFrame(_ image: UIImage, pose: VisionPose?, capturedAt: Int64) {
        checkEnrollment()
        onPose(pose)
        guard enabled, foreground, cameraAvailable, importedEncoded == nil, !importingImage else { return }
        latestFrame = CapturedFrame(image: image, pose: currentPose, at: capturedAt)
        sampleSignature(image, capturedAt: capturedAt)
        refreshMarkers()
        analyze(manual: false)
    }

    func onPose(_ pose: VisionPose?) {
        currentPose = VisionPolicy.validPose(pose) ? pose : nil
        if !VisionPolicy.nearPose(steadyPose, currentPose) {
            steadyPose = currentPose
            steadyPoseSince = currentPose == nil ? nil : visionElapsedMs()
        }
        refreshMarkers()
    }

    /// Imports a bounded copy locally; selecting a file never sends a request.
    func selectImportedImage(_ image: UIImage) {
        checkEnrollment()
        invalidate(clearScene: true)
        importGeneration += 1
        let generation = importGeneration
        let capturedEpoch = epoch
        latestFrame = nil
        importingImage = true
        error = nil
        Task { @MainActor in
            let result = await Task.detached(priority: .userInitiated) { () -> EncodedVisionImage? in
                try? encodeVisionImage(image)
            }.value
            guard generation == self.importGeneration else { return }
            self.importingImage = false
            guard capturedEpoch == self.epoch else { return }
            guard let encoded = result else { self.fail(.imageEncoding); return }
            self.importedEncoded = encoded
            self.retryPolicy.newImage()
            self.importedImage = encoded.preview
            self.retryAtElapsedMs = self.retryPolicy.retryAtElapsedMs
            self.automaticRetryBlocked = self.retryPolicy.automaticBlocked
        }
    }

    func returnToLive() {
        importGeneration += 1
        invalidate(clearScene: true)
        importedEncoded = nil
        latestFrame = nil
        currentPose = nil
        importedImage = nil
        importingImage = false
    }

    // MARK: - Scene selection

    func analyzeNow() {
        if conversationActive && !requestGate.busy { clearConversation() }
        analyze(manual: true)
    }

    func pinScene() {
        guard let _ = scene, !scenePinned else { return }
        pausedBeforePin = paused
        let wasConversation = conversationActive
        invalidate(clearScene: false)
        scenePinned = true
        paused = true
        markersVisible = false
        conversationActive = false
        if wasConversation { reply = nil; replySources = []; history = [] }
    }

    func unpinScene() {
        guard scenePinned else { return }
        invalidate(clearScene: false)
        scenePinned = false
        selectedItemId = nil
        paused = pausedBeforePin
        reply = nil; replySources = []; history = []; error = nil
        refreshMarkers()
    }

    func selectItem(_ id: String?) {
        if let id = id, scene?.items.contains(where: { $0.id == id }) != true { return }
        if id != nil { pinScene() }
        if id == selectedItemId { return }
        invalidate(clearScene: false)
        selectedItemId = id
        reply = nil; replySources = []; history = []; error = nil
        refreshMarkers()
    }

    // MARK: - Questions

    /// The mic tap captures the question's visual context before speech recognition starts.
    func beginVoiceTurn() -> Bool {
        checkEnrollment()
        let context = voiceCapture(pinned: scenePinned && !conversationActive)
        if let problem = consentProblem(allowRetained: context?.pinned == true) { fail(problem); return false }
        if rejectDuringRetry(visionElapsedMs()) { return false }
        guard let captured = context else { fail(.frameStale); return false }
        if asking { return false }
        voiceContext = captured
        voiceBusy = true
        conversationActive = !captured.pinned
        error = nil
        return true
    }

    func endVoiceTurn() {
        voiceContext = nil
        voiceBusy = false
    }

    func clearConversation() {
        invalidate(clearScene: false)
        conversationActive = false
        voiceBusy = false
        reply = nil; replySources = []; history = []; error = nil
    }

    /// A question about whatever the camera (or imported photo) shows right now.
    func askLive(_ question: String) {
        checkEnrollment()
        var context: VoiceContext? = voiceContext
        if context?.pinned == true { context = nil }
        if context == nil { context = voiceCapture(pinned: false) }
        guard var live = context else { fail(.frameStale); endVoiceTurn(); return }
        live.pinned = false
        live.selected = nil
        live.sceneId = nil
        submitQuestion(question, context: live)
    }

    /// A follow-up about the pinned snapshot and its selected item.
    func ask(_ question: String) {
        checkEnrollment()
        pinScene()
        var context: VoiceContext? = voiceContext
        if context?.pinned != true { context = voiceCapture(pinned: true) }
        guard let pinned = context, pinned.pinned else { fail(.sceneRequired); endVoiceTurn(); return }
        submitQuestion(question, context: pinned)
    }

    private func voiceCapture(pinned: Bool) -> VoiceContext? {
        if importingImage { return nil }
        if pinned, let scene = scene, let retained = retainedImage {
            return VoiceContext(frame: nil, encoded: retained, sceneId: scene.sceneId, selected: selectedItem, pinned: true)
        }
        if let imported = importedEncoded {
            return VoiceContext(frame: nil, encoded: imported, sceneId: nil, selected: nil, pinned: false)
        }
        guard let frame = latestFrame,
              VisionPolicy.freshTimestamp(frame.at, now: visionElapsedMs(), maximumAge: VisionPolicy.frameFreshMs) else { return nil }
        return VoiceContext(frame: frame, encoded: nil, sceneId: nil, selected: nil, pinned: false)
    }

    private func submitQuestion(_ question: String, context: VoiceContext) {
        if importingImage { fail(.frameStale); endVoiceTurn(); return }
        if let problem = consentProblem(allowRetained: context.pinned) { fail(problem); endVoiceTurn(); return }
        if rejectDuringRetry(visionElapsedMs()) { endVoiceTurn(); return }
        let text = utf16Prefix(question.trimmingCharacters(in: .whitespacesAndNewlines), 2000)
        if text.isEmpty { fail(.emptyQuestion); endVoiceTurn(); return }
        if asking && queuedVoice == nil { return }
        let pending = PendingVoice(question: text, context: context, epoch: epoch, token: enrollmentToken,
                                   server: enrollmentServer, language: language, history: history)
        voiceContext = nil
        conversationActive = !context.pinned
        asking = true
        requestBusy = true
        error = nil
        reply = nil
        replySources = []
        if requestGate.busy {
            // One spoken turn gets the next slot. The photograph was taken at the mic tap;
            // it must not silently become the next background analysis photograph.
            queuedVoice = pending
        } else {
            runQuestion(pending)
        }
    }

    private func drainVoiceQuestion() {
        guard let pending = queuedVoice, !requestGate.busy else { return }
        queuedVoice = nil
        if accepts(pending.epoch, token: pending.token, server: pending.server, allowRetained: pending.context.pinned) {
            runQuestion(pending)
        } else {
            asking = false; requestBusy = false; voiceBusy = false
        }
    }

    private func runQuestion(_ pending: PendingVoice) {
        let context = pending.context
        let startedAt = visionElapsedMs()
        if rejectDuringRetry(startedAt) {
            asking = false; requestBusy = false; voiceBusy = false
            return
        }
        guard let ticket = requestGate.start(startedAt, automatic: false) else { return }
        asking = true
        requestBusy = true
        requestTask = Task { @MainActor in
            defer {
                self.requestGate.finish(ticket)
                self.requestTask = nil
                self.asking = false
                self.requestBusy = false
                self.drainVoiceQuestion()
            }
            do {
                let encoded: EncodedVisionImage
                if let existing = context.encoded {
                    encoded = existing
                } else if let frame = context.frame {
                    let image = frame.image
                    guard let result = await Task.detached(priority: .userInitiated, operation: { () -> EncodedVisionImage? in
                        try? encodeVisionImage(image)
                    }).value else { throw VisionRequestFailure(error: .imageEncoding, retryable: false) }
                    encoded = result
                } else {
                    throw VisionRequestFailure(error: .frameStale, retryable: false)
                }
                try Task.checkCancellation()
                guard self.requestGate.accepts(ticket),
                      self.accepts(pending.epoch, token: pending.token, server: pending.server, allowRetained: context.pinned) else { return }
                var body: [String: Any] = ["image": VisionPayloads.image(encoded.base64), "language": pending.language,
                                           "question": pending.question, "history": VisionPayloads.history(pending.history)]
                if let selected = context.selected { body["selectedItem"] = VisionPayloads.selected(selected) }
                let raw = try await VisionAPI.request("/vision/ask", body: body, server: pending.server, token: pending.token)
                try Task.checkCancellation()
                guard self.requestGate.accepts(ticket),
                      self.accepts(pending.epoch, token: pending.token, server: pending.server, allowRetained: context.pinned),
                      !(context.pinned && self.scene?.sceneId != context.sceneId) else { return }
                guard let answer = try? VisionPayloads.answer(raw) else {
                    throw VisionRequestFailure(error: .invalidResponse, retryable: true)
                }
                self.requestSucceeded()
                self.replyRevision += 1
                self.reply = answer.reply
                self.replySources = answer.sources
                self.conversationRevision = self.replyRevision
                self.history = Array((pending.history + [VisionHistoryEntry(role: "user", text: pending.question),
                                                         VisionHistoryEntry(role: "assistant", text: answer.reply)]).suffix(6))
            } catch is CancellationError {
                // A lifecycle/consent/language/selection change deliberately discards the result.
            } catch {
                if self.requestGate.accepts(ticket),
                   self.accepts(pending.epoch, token: pending.token, server: pending.server, allowRetained: context.pinned) {
                    self.requestFailed(error)
                    self.voiceBusy = false
                }
            }
        }
    }

    // MARK: - Analysis

    private func analyze(manual: Bool) {
        checkEnrollment()
        if let problem = consentProblem() { if manual { fail(problem) }; return }
        if requestGate.busy || scenePinned || voiceBusy || (!manual && paused) { return }
        let now = visionElapsedMs()
        if manual && rejectDuringRetry(now) { return }
        if !manual && retryPolicy.blocked(now, automatic: true) != nil { return }
        let imported = importedEncoded
        if importingImage || (!manual && !VisionInputPolicy.automaticAllowed(imported: imported != nil, importing: false, paused: paused)) { return }
        if !manual && visionSignaturesMatch(lastAnalyzedSignature, signature) && !VisionPolicy.staticRefreshDue(lastAnalyzedAt, now: now) { return }
        if !manual, markersVisible, let current = scene, now - current.capturedAtElapsedMs < VisionPolicy.matchedRefreshMs { return }
        if !manual {
            guard VisionPolicy.validPose(currentPose),
                  let poseSince = steadyPoseSince, now - poseSince >= VisionPolicy.steadyMs,
                  let visualSince = visualSteadySince, now - visualSince >= VisionPolicy.steadyMs else { return }
        }
        let frame: CapturedFrame?
        if let imported = imported {
            frame = CapturedFrame(image: imported.preview, pose: nil, at: now)
        } else {
            frame = latestFrame
        }
        guard let captured = frame, VisionInputPolicy.frameUsable(imported: imported != nil, capturedAt: captured.at, now: now) else {
            if manual { fail(.frameStale) }
            return
        }
        let capturedEpoch = epoch
        let revision = analysisRevision
        let token = enrollmentToken
        let server = enrollmentServer
        let requestLanguage = language
        let requestTranslate = translate
        guard let ticket = requestGate.start(now, automatic: !manual) else { return }
        let pausedBefore = paused
        analyzing = true
        requestBusy = true
        error = nil
        requestTask = Task { @MainActor in
            defer {
                self.requestGate.finish(ticket)
                self.requestTask = nil
                self.analyzing = false
                self.requestBusy = false
                self.drainVoiceQuestion()
            }
            do {
                let encoded: EncodedVisionImage
                if let imported = imported {
                    encoded = imported
                } else {
                    let image = captured.image
                    guard let result = await Task.detached(priority: .userInitiated, operation: { () -> EncodedVisionImage? in
                        try? encodeVisionImage(image)
                    }).value else { throw VisionRequestFailure(error: .imageEncoding, retryable: false) }
                    encoded = result
                }
                try Task.checkCancellation()
                guard self.requestGate.accepts(ticket), self.accepts(capturedEpoch, token: token, server: server),
                      revision == self.analysisRevision else { return }
                let raw = try await VisionAPI.request("/vision/analyze", body: [
                    "image": VisionPayloads.image(encoded.base64), "language": requestLanguage, "translate": requestTranslate,
                ], server: server, token: token)
                try Task.checkCancellation()
                guard self.requestGate.accepts(ticket), self.accepts(capturedEpoch, token: token, server: server),
                      revision == self.analysisRevision else { return }
                guard let analysis = try? VisionPayloads.analysis(raw) else {
                    throw VisionRequestFailure(error: .invalidResponse, retryable: true)
                }
                self.requestSucceeded()
                self.lastAnalyzedSignature = encoded.signature
                self.lastAnalyzedAt = captured.at
                self.retainedImage = encoded
                // A manual capture is a photograph the user asked about, even if they move
                // before the reply. Automatic results stay reviewable; only fresh matching
                // samples may project their markers over live video.
                let keepConversation = self.conversationActive
                let pinResult = manual && !keepConversation
                if pinResult { self.pausedBeforePin = pausedBefore }
                self.scene = VisionScene(sceneId: analysis.sceneId, summary: analysis.summary, items: analysis.items,
                                         sources: analysis.sources, provider: analysis.provider, preview: encoded.preview,
                                         capturedAtElapsedMs: captured.at, pose: captured.pose)
                self.selectedItemId = nil
                self.scenePinned = pinResult
                self.paused = pinResult || self.paused
                if !keepConversation { self.reply = nil; self.replySources = []; self.history = [] }
                self.refreshMarkers()
            } catch is CancellationError {
                // Never publish a result from an invalidated capture.
            } catch {
                if self.requestGate.accepts(ticket), self.accepts(capturedEpoch, token: token, server: server),
                   revision == self.analysisRevision {
                    self.requestFailed(error)
                }
            }
        }
    }

    // MARK: - Gates

    private func consentProblem(allowRetained: Bool = false) -> VisionError? {
        if !enabled { return .consentRequired }
        if !VisionInputPolicy.available(foreground: foreground, cameraAvailable: cameraAvailable,
                                        imported: importedEncoded != nil, retained: allowRetained) { return .cameraUnavailable }
        if !store.enrolled || enrollmentToken.isEmpty { return .enrollmentRequired }
        return nil
    }

    private func accepts(_ capturedEpoch: Int64, token: String, server: String, allowRetained: Bool = false) -> Bool {
        capturedEpoch == epoch &&
            VisionInputPolicy.available(foreground: foreground, cameraAvailable: cameraAvailable,
                                        imported: importedEncoded != nil, retained: allowRetained) &&
            enabled && token == store.token && server == store.server && store.enrolled
    }

    private func refreshMarkers() {
        if importedEncoded != nil || importingImage {
            if markersVisible { markersVisible = false }
            return
        }
        let now = visionElapsedMs()
        var stationary = false
        if let current = scene {
            stationary = VisionPolicy.freshTimestamp(signatureAt, now: now, maximumAge: VisionPolicy.frameFreshMs) &&
                visionSignaturesMatch(retainedImage?.signature, signature) &&
                VisionPolicy.markersFresh(current.capturedAtElapsedMs, now: now, captured: current.pose, current: currentPose)
        }
        var frameFresh = false
        if let frame = latestFrame {
            frameFresh = VisionPolicy.freshTimestamp(frame.at, now: now, maximumAge: VisionPolicy.frameFreshMs)
        }
        let visible = enabled && foreground && cameraAvailable && !scenePinned && frameFresh && stationary &&
            !(scene?.items.isEmpty ?? true)
        if markersVisible != visible { markersVisible = visible }
    }

    private func sampleSignature(_ image: UIImage, capturedAt: Int64) {
        guard !signatureBusy, capturedAt - signatureAt >= VisionPolicy.signatureIntervalMs else { return }
        signatureBusy = true
        let capturedEpoch = epoch
        Task { @MainActor in
            let sampled = await Task.detached(priority: .utility) { visionFrameSignature(image) }.value
            self.signatureBusy = false
            guard self.epoch == capturedEpoch, self.foreground, self.enabled, self.cameraAvailable else { return }
            guard let sampled = sampled else {
                self.signature = nil
                self.visualSteadySince = nil
                self.refreshMarkers()
                return
            }
            let difference = visionSignatureDifference(self.signature, sampled)
            if difference == nil || difference! > 8.0 / 255.0 { self.visualSteadySince = visionElapsedMs() }
            self.signature = sampled
            self.signatureAt = capturedAt
            self.refreshMarkers()
        }
    }

    private func checkEnrollment() {
        guard enrollmentToken != store.token || enrollmentServer != store.server else { return }
        enrollmentToken = store.token
        enrollmentServer = store.server
        retryPolicy.success()
        invalidate(clearScene: true)
        latestFrame = nil
        // A different cloud binding needs a new opt-in.
        enabled = false
        paused = false
    }

    private func invalidate(clearScene: Bool) {
        epoch += 1
        requestGate.invalidate()
        analysisRevision += 1
        requestTask?.cancel()
        voiceContext = nil
        queuedVoice = nil
        voiceBusy = false
        asking = false
        guard clearScene else { return }
        let restorePaused = scenePinned ? pausedBeforePin : paused
        retainedImage = nil
        pausedBeforePin = false
        steadyPose = nil
        steadyPoseSince = nil
        visualSteadySince = nil
        signature = nil
        signatureAt = -1
        lastAnalyzedSignature = nil
        lastAnalyzedAt = nil
        scene = nil
        selectedItemId = nil
        scenePinned = false
        paused = restorePaused
        reply = nil
        replySources = []
        history = []
        markersVisible = false
        analyzing = false
        conversationActive = false
        error = retryPolicy.lastFailure?.error
        retryAtElapsedMs = retryPolicy.retryAtElapsedMs
        automaticRetryBlocked = retryPolicy.automaticBlocked
    }

    private func fail(_ value: VisionError) { error = value }

    /// The UI presents the countdown instead of claiming this action made a failed request.
    private func rejectDuringRetry(_ now: Int64) -> Bool {
        retryPolicy.blocked(now, automatic: false) != nil
    }

    private func requestSucceeded() {
        retryPolicy.success()
        error = nil
        retryAtElapsedMs = 0
        automaticRetryBlocked = false
    }

    private func requestFailed(_ failure: Error) {
        let mapped: VisionRequestFailure
        if let typed = failure as? VisionRequestFailure {
            mapped = typed
        } else if let platform = failure as? PlatformError {
            mapped = VisionFailurePayloads.parse(status: platform.status, body: nil, retryAfter: nil)
        } else {
            mapped = VisionRequestFailure(error: .network, retryable: true)
        }
        retryPolicy.failure(mapped, now: visionElapsedMs())
        retryAtElapsedMs = retryPolicy.retryAtElapsedMs
        automaticRetryBlocked = retryPolicy.automaticBlocked
        fail(mapped.error)
    }
}
