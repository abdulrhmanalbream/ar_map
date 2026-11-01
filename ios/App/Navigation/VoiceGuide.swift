import AVFoundation
import Foundation
import MutawwifCore

/// Bundled Arabic navigation recordings, with on-device Arabic synthesis only
/// for destination names and distances no clip covers.
///
/// Port of Android's VoiceGuide. The semantics that matter are kept: a newer
/// accepted instruction cancels everything older (no stale direction or
/// distance survives), mute stops playback immediately, and each segment has
/// a deadline so a stuck player can never wedge the queue.
final class VoiceGuide: NSObject, ObservableObject, AVAudioPlayerDelegate, AVSpeechSynthesizerDelegate {

    private static let muteKey = "smart_mutawwif.navigation_muted"
    /// Android caps a phrase at eight segments; a longer plan is a bug, not
    /// something worth reading out while someone walks.
    private static let maxSegments = 8

    @Published private(set) var muted: Bool = UserDefaults.standard.bool(forKey: VoiceGuide.muteKey)

    private var player: AVAudioPlayer?
    private var activeClip: (id: String, fallback: String)?
    private let synthesizer = AVSpeechSynthesizer()
    private var activeUtterance: AVSpeechUtterance?
    private var pending: [CoreBridge.Segment] = []
    private var generation = 0
    private var deadline: DispatchWorkItem?
    private lazy var arabicVoice: AVSpeechSynthesisVoice? = VoiceGuide.selectArabicVoice()

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Includes every queued segment of the current phrase, so the policy can
    /// wait for a complete direction + distance before the next routine cue.
    var isSpeaking: Bool {
        player != nil || activeUtterance != nil || !pending.isEmpty
    }

    func setMuted(_ value: Bool) {
        muted = value
        UserDefaults.standard.set(value, forKey: VoiceGuide.muteKey)
        if value { stop() }
    }

    func speakGuidance(_ mode: GuidanceMode, _ targetName: String, manualRepeat: Bool = false) {
        play(CoreBridge.guidancePlan(mode, targetName, manualRepeat: manualRepeat))
    }

    func speakSelected(_ targetName: String) {
        play(CoreBridge.selectionPlan(targetName))
    }

    func stop() {
        generation += 1
        clearDeadline()
        pending.removeAll()
        if let p = player {
            p.delegate = nil
            p.stop()
        }
        player = nil
        activeClip = nil
        activeUtterance = nil
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        deactivateSession()
    }

    // MARK: Queue

    private func play(_ segments: [CoreBridge.Segment]) {
        guard !segments.isEmpty else { return }
        if muted { stop(); return }
        stop()
        pending = Array(segments.prefix(VoiceGuide.maxSegments))
        activateSession()
        advance(generation)
    }

    private func advance(_ token: Int) {
        guard token == generation, !muted else { return }
        guard !pending.isEmpty else {
            deactivateSession()
            return
        }
        let next = pending.removeFirst()
        switch next {
        case let .clip(id, fallbackText):
            startClip(id: id, fallback: fallbackText, token: token)
        case let .text(text):
            startText(text, token: token)
        }
    }

    private func startClip(id: String, fallback: String, token: Int) {
        guard CoreBridge.isKnownClip(id),
              let url = Bundle.main.url(forResource: id, withExtension: "wav", subdirectory: "navigation/ar")
        else {
            startText(fallback, token: token)
            return
        }
        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            player = p
            activeClip = (id, fallback)
            guard p.play() else {
                finishClip(failed: true, token: token)
                return
            }
            // Clip length + 2 s, clamped like Android's 4-22 s window.
            let seconds = min(max(p.duration + 2.0, 4.0), 22.0)
            setDeadline(seconds) { [weak self] in self?.finishClip(failed: true, token: token) }
        } catch {
            startText(fallback, token: token)
        }
    }

    private func finishClip(failed: Bool, token: Int) {
        guard token == generation, player != nil else { return }
        clearDeadline()
        let clip = activeClip
        player?.delegate = nil
        player = nil
        activeClip = nil
        if failed, let clip = clip {
            startText(clip.fallback, token: token)
        } else {
            advance(token)
        }
    }

    private func startText(_ text: String, token: Int) {
        guard token == generation, !muted else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let voice = arabicVoice else {
            advance(token)
            return
        }
        let utterance = AVSpeechUtterance(string: trimmed)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        activeUtterance = utterance
        setDeadline(15) { [weak self] in
            guard let self = self, self.activeUtterance === utterance else { return }
            self.synthesizer.stopSpeaking(at: .immediate)
            self.finishText(utterance)
        }
        synthesizer.speak(utterance)
    }

    private func finishText(_ utterance: AVSpeechUtterance) {
        guard activeUtterance === utterance else { return }
        clearDeadline()
        activeUtterance = nil
        advance(generation)
    }

    // MARK: Delegates (delivered on the main thread)

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let token = generation
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.player === player else { return }
            self.finishClip(failed: !flag, token: token)
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        let token = generation
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.player === player else { return }
            self.finishClip(failed: true, token: token)
        }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in self?.finishText(utterance) }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { [weak self] in self?.finishText(utterance) }
    }

    // MARK: Helpers

    private func setDeadline(_ seconds: Double, _ action: @escaping () -> Void) {
        clearDeadline()
        let item = DispatchWorkItem(block: action)
        deadline = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func clearDeadline() {
        deadline?.cancel()
        deadline = nil
    }

    /// Spoken guidance ducks music rather than stopping it, and follows the
    /// current route, so connected glasses' speakers play it automatically.
    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .mixWithOthers])
        try? session.setActive(true)
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Never route a time-sensitive Arabic cue through a foreign voice: prefer
    /// enhanced quality, then Saudi Arabic, mirroring selectNavigationVoice.
    private static func selectArabicVoice() -> AVSpeechSynthesisVoice? {
        let arabic = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("ar") }
        let sorted = arabic.sorted { a, b in
            if a.quality.rawValue != b.quality.rawValue { return a.quality.rawValue > b.quality.rawValue }
            let aSA = a.language == "ar-SA"
            let bSA = b.language == "ar-SA"
            if aSA != bSA { return aSA }
            return a.identifier < b.identifier
        }
        return sorted.first ?? AVSpeechSynthesisVoice(language: "ar-SA")
    }
}
