import AVFoundation
import Speech
import Foundation

/// Tap-to-talk questions (SFSpeechRecognizer) and spoken answers. Answers use the
/// project's cloud voice (`/vision/speak`, Sulafat) when the companion is enrolled and
/// consented, falling back to the on-device AVSpeechSynthesizer voice.
final class CompanionSpeech: NSObject, ObservableObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    static let shared = CompanionSpeech()

    @Published private(set) var listening = false
    @Published private(set) var processing = false
    @Published private(set) var speaking = false
    @Published private(set) var partialText = ""
    @Published private(set) var notice: String?
    @Published private(set) var muted: Bool

    private let synthesizer = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private let engine = AVAudioEngine()
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var onQuestion: ((String) -> Void)?
    private var onFinished: (() -> Void)?
    private var generation: Int64 = 0
    private var speakTask: Task<Void, Never>?
    private let replyGate = VisionReplyReadGate(initialRevision: 0)

    override init() {
        muted = UserDefaults.standard.bool(forKey: "smart_mutawwif_audio.muted")
        super.init()
        synthesizer.delegate = self
    }

    static func locale(_ language: String) -> Locale {
        switch language {
        case "ar": return Locale(identifier: "ar-SA")
        case "ur": return Locale(identifier: "ur-PK")
        case "id": return Locale(identifier: "id-ID")
        case "tr": return Locale(identifier: "tr-TR")
        default: return Locale(identifier: "en-US")
        }
    }

    func setMuted(_ value: Bool) {
        muted = value
        UserDefaults.standard.set(value, forKey: "smart_mutawwif_audio.muted")
        if value { stopSpeaking() }
    }

    func clearNotice() { notice = nil }

    // MARK: - Listening

    /// `onQuestion` receives the recognized text; `onFinished` runs when the turn ends
    /// without a question (cancel, silence, permission or recognition failure).
    func startListening(language: String, onQuestion: @escaping (String) -> Void, onFinished: @escaping () -> Void) {
        stopSpeaking()
        cancelListening()
        let copy = VisionCopy(language: language)
        self.onQuestion = onQuestion
        self.onFinished = onFinished
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else { self.finishWithNotice(copy.speechPermission); return }
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    DispatchQueue.main.async {
                        guard granted else { self.finishWithNotice(copy.speechPermission); return }
                        self.beginRecognition(language: language, copy: copy)
                    }
                }
            }
        }
    }

    private func beginRecognition(language: String, copy: VisionCopy) {
        guard let recognizer = SFSpeechRecognizer(locale: CompanionSpeech.locale(language)), recognizer.isAvailable else {
            finishWithNotice(copy.speechLanguageUnavailable)
            return
        }
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetooth, .duckOthers])
            try audio.setActive(true, options: .notifyOthersOnDeactivation)
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            recognitionRequest = request
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
                request.append(buffer)
            }
            engine.prepare()
            try engine.start()
        } catch {
            finishWithNotice(copy.speechLanguageUnavailable)
            return
        }
        listening = true
        CompanionSession.shared.listening = true
        partialText = ""
        notice = nil
        armSilenceTimer(seconds: 6)
        recognitionTask = recognizer.recognitionTask(with: recognitionRequest!) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self = self, self.listening || self.processing else { return }
                if let result = result {
                    self.partialText = result.bestTranscription.formattedString
                    // Automatic submission: a pause after speech ends the question.
                    self.armSilenceTimer(seconds: 1.6)
                    if result.isFinal { self.deliver() }
                } else if error != nil {
                    self.deliver()
                }
            }
        }
    }

    private func armSilenceTimer(seconds: TimeInterval) {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.finishListening()
        }
    }

    /// The user tapped "finish speaking", or silence was detected.
    func finishListening() {
        guard listening else { return }
        listening = false
        processing = true
        stopAudioInput()
        recognitionRequest?.endAudio()
        // Give the recognizer a moment to return its final transcription.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.deliver() }
    }

    private func deliver() {
        guard listening || processing else { return }
        let text = partialText.trimmingCharacters(in: .whitespacesAndNewlines)
        let question = onQuestion
        let finished = onFinished
        cleanupRecognition()
        if text.isEmpty {
            notice = VisionCopy(language: CompanionLanguage.shared.code).speechNotHeard
            finished?()
        } else {
            question?(text)
        }
    }

    func cancelListening() {
        let finished = onFinished
        let wasActive = listening || processing
        cleanupRecognition()
        if wasActive { finished?() }
    }

    private func finishWithNotice(_ text: String) {
        let finished = onFinished
        cleanupRecognition()
        notice = text
        finished?()
    }

    private func stopAudioInput() {
        silenceTimer?.invalidate()
        silenceTimer = nil
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
    }

    private func cleanupRecognition() {
        stopAudioInput()
        recognitionTask?.cancel()
        recognitionTask = nil
        recognitionRequest = nil
        listening = false
        processing = false
        onQuestion = nil
        onFinished = nil
        CompanionSession.shared.listening = false
    }

    // MARK: - Speaking

    /// New replies are spoken once, and only while the screen is in the foreground.
    func readReply(_ text: String, revision: Int64, foreground: Bool, language: String, cloud: Bool) {
        guard replyGate.consume(revision, foreground: foreground) else { return }
        speak(text, language: language, cloud: cloud)
    }

    func speak(_ text: String, language: String, cloud: Bool) {
        stopSpeaking()
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !muted, !value.isEmpty else { return }
        generation += 1
        let current = generation
        speaking = true
        CompanionSession.shared.speaking = true
        let token = PlatformStore.shared.token
        let server = PlatformStore.shared.server
        speakTask = Task { @MainActor in
            if cloud && !token.isEmpty, let pcm = try? await VisionAPI.speak(text: value, language: language, server: server, token: token),
               current == self.generation, self.playPcm(pcm) {
                return
            }
            guard current == self.generation, !Task.isCancelled else { return }
            self.speakLocally(value, language: language)
        }
    }

    private func speakLocally(_ text: String, language: String) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        for chunk in visionSpeechChunks(text, maxLength: 1000) {
            let utterance = AVSpeechUtterance(string: chunk)
            utterance.voice = AVSpeechSynthesisVoice(language: CompanionSpeech.locale(language).identifier)
                ?? AVSpeechSynthesisVoice(language: language)
            synthesizer.speak(utterance)
        }
    }

    private func playPcm(_ pcm: Data) -> Bool {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            let audio = try AVAudioPlayer(data: wavData(pcm: pcm, sampleRate: 24_000))
            audio.delegate = self
            player = audio
            return audio.play()
        } catch {
            return false
        }
    }

    func stopSpeaking() {
        generation += 1
        speakTask?.cancel()
        speakTask = nil
        player?.stop()
        player = nil
        if synthesizer.isSpeaking { synthesizer.stopSpeaking(at: .immediate) }
        speaking = false
        CompanionSession.shared.speaking = false
    }

    private func finishedSpeaking() {
        guard !synthesizer.isSpeaking, player?.isPlaying != true else { return }
        speaking = false
        CompanionSession.shared.speaking = false
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async { self.finishedSpeaking() }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async {
            self.player = nil
            self.finishedSpeaking()
        }
    }
}

/// Wraps headerless mono s16le PCM in a RIFF/WAVE container for AVAudioPlayer.
func wavData(pcm: Data, sampleRate: Int) -> Data {
    func le32(_ value: Int) -> [UInt8] { (0..<4).map { UInt8((value >> ($0 * 8)) & 0xFF) } }
    func le16(_ value: Int) -> [UInt8] { (0..<2).map { UInt8((value >> ($0 * 8)) & 0xFF) } }
    // Appended piecewise: one long `+` chain of generic array initialisers is a
    // classic "expression too complex to type-check" failure.
    var header: [UInt8] = Array("RIFF".utf8)
    header += le32(36 + pcm.count)
    header += Array("WAVE".utf8)
    header += Array("fmt ".utf8)
    header += le32(16)
    header += le16(1)
    header += le16(1)
    header += le32(sampleRate)
    header += le32(sampleRate * 2)
    header += le16(2)
    header += le16(16)
    header += Array("data".utf8)
    header += le32(pcm.count)
    var data = Data(header)
    data.append(pcm)
    return data
}
