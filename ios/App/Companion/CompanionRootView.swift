import SwiftUI
import PhotosUI
import AVFoundation

/// Entry point for the religious visual companion tab.
struct CompanionRootView: View {
    @ObservedObject private var language = CompanionLanguage.shared

    init() {}

    var body: some View {
        Group {
            if language.confirmed {
                CompanionCameraScreen()
            } else {
                LanguageWelcomeView(initial: language.code) { language.confirm($0) }
            }
        }
        .environment(\.layoutDirection, language.layoutDirection)
    }
}

/// First run asks for an explicit language before camera content or permissions.
struct LanguageWelcomeView: View {
    let initial: String
    let onConfirm: (String) -> Void
    @State private var selected: String

    init(initial: String, onConfirm: @escaping (String) -> Void) {
        self.initial = initial
        self.onConfirm = onConfirm
        _selected = State(initialValue: initial)
    }

    var body: some View {
        let copy = VisionCopy(language: selected)
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("المطوف الذكي").font(.title2.weight(.bold)).foregroundColor(Hedayah.paper)
                Text("هداية ثون · فريق سعي").font(.footnote.weight(.medium)).foregroundColor(Hedayah.hudGold)
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16).fill(Hedayah.forest))
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(copy.chooseLanguage).font(.title3.weight(.semibold)).foregroundColor(Hedayah.ink)
                    Text(copy.languageIntro).font(.callout).foregroundColor(Hedayah.muted)
                    ForEach(companionLanguages, id: \.code) { entry in
                        Button(action: { selected = entry.code }) {
                            HStack(spacing: 12) {
                                Image(systemName: selected == entry.code ? "largecircle.fill.circle" : "circle")
                                    .foregroundColor(Hedayah.forest)
                                Text(entry.label).font(.headline).foregroundColor(Hedayah.ink)
                                Spacer()
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: 56)
                            .background(RoundedRectangle(cornerRadius: 12).fill(selected == entry.code ? Hedayah.soft : Hedayah.paper))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected == entry.code ? .isSelected : [])
                    }
                }
            }
            Button(action: { onConfirm(selected) }) {
                Text(copy.continueLabel).font(.headline).frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(HedayahFilledButtonStyle())
        }
        .padding(20)
        .background(Hedayah.ivory.ignoresSafeArea())
        .environment(\.layoutDirection, companionIsRTL(selected) ? .rightToLeft : .leftToRight)
    }
}

/// Live phone camera with recognition markers, voice questions and result panels.
struct CompanionCameraScreen: View {
    @ObservedObject private var session = CompanionSession.shared
    @ObservedObject private var speech = CompanionSpeech.shared
    @ObservedObject private var platform = PlatformStore.shared
    @ObservedObject private var language = CompanionLanguage.shared
    @StateObject private var camera = PhoneCamera()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showSettings = false
    @State private var showDetail = false
    @State private var showConsent = false
    @State private var typedQuestion = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var photoError: String?
    @State private var resumedAt: Int64 = visionElapsedMs()
    @State private var narrationGate = VisionNarrationGate()
    @State private var visible = false

    private var copy: VisionCopy { VisionCopy(language: language.code, importedImage: session.isImportedImage) }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let imported = session.importedImage {
                Image(uiImage: imported).resizable().scaledToFit().ignoresSafeArea()
            } else if camera.authorization == .authorized {
                CameraPreview(session: camera.session).ignoresSafeArea()
            } else {
                cameraAccess
            }
            if (session.markersVisible || session.isImportedImage), let scene = session.scene, !showDetail {
                SceneMarkersView(scene: scene, fit: session.isImportedImage) { select($0) }
                    .ignoresSafeArea()
            }
            VStack(spacing: 8) {
                header
                Spacer()
                if session.conversationActive { conversationPanel }
                statusLine
                controls
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .environment(\.layoutDirection, language.layoutDirection)
        .sheet(isPresented: $showSettings) { CompanionSettingsView() }
        .sheet(isPresented: $showDetail, onDismiss: { session.unpinScene() }) { SceneDetailView() }
        .sheet(isPresented: $showConsent) { consentSheet }
        .onAppear(perform: appear)
        .onDisappear(perform: disappear)
        .onChange(of: scenePhase) { phase in
            if phase == .active && visible {
                resumedAt = visionElapsedMs()
                session.setForeground(true)
                camera.start()
            } else if phase != .active {
                session.setForeground(false)
                speech.stopSpeaking()
                speech.cancelListening()
            }
        }
        .onChange(of: camera.running) { running in
            session.setCameraAvailable(running)
        }
        .onChange(of: camera.authorization) { status in
            if status == .authorized && visible { camera.start() }
        }
        .onChange(of: session.scenePinned) { pinned in
            // An explicit capture returns a snapshot to review, even after the camera moves.
            if pinned && session.scene != nil && !session.conversationActive {
                showSettings = false
                showDetail = true
            }
        }
        .onChange(of: session.conversationRevision) { revision in
            if let reply = session.reply {
                typedQuestion = ""
                speech.readReply(reply, revision: revision, foreground: scenePhase == .active,
                                 language: language.code, cloud: session.enabled && platform.enrolled)
            }
        }
        .onChange(of: session.scene?.sceneId) { _ in narrate() }
        .onChange(of: session.markersVisible) { _ in narrate() }
        .onChange(of: session.enabled) { enabled in if !enabled { speech.stopSpeaking() } }
        .onChange(of: photoItem) { item in loadPhoto(item) }
    }

    // MARK: - Pieces

    private var cameraAccess: some View {
        VStack(spacing: 16) {
            Text(copy.cameraPermissionTitle).font(.title3.weight(.semibold))
            Text(copy.cameraPermissionNote).font(.callout).multilineTextAlignment(.center)
            Button(action: {
                if camera.authorization == .notDetermined { camera.requestAccess() }
                else if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }) {
                Text(camera.authorization == .notDetermined ? copy.allowCamera : copy.openAppSettings)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(HedayahFilledButtonStyle())
            PhotosPicker(selection: $photoItem, matching: .images) {
                Text(session.importingImage ? copy.imageLoading : copy.chooseImage).frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(HedayahOutlineButtonStyle())
            if let photoError = photoError { Text(photoError).foregroundColor(Hedayah.error) }
        }
        .padding(24)
        .background(RoundedRectangle(cornerRadius: 20).fill(Hedayah.ivory))
        .foregroundColor(Hedayah.ink)
        .padding(20)
    }

    private var header: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(copy.title).font(.headline).lineLimit(1)
                Text("\(copy.cameraSource) · \(statusLabel)")
                    .font(.caption.weight(.medium)).foregroundColor(Hedayah.arAccent).lineLimit(1)
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .hologramPanel()
            Button(action: { speech.setMuted(!speech.muted) }) {
                Image(systemName: speech.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
            }
            .buttonStyle(HologramButtonStyle())
            .accessibilityLabel(speech.muted ? copy.unmuteNarration : copy.muteNarration)
            Button(action: { speech.stopSpeaking(); showSettings = true }) { Image(systemName: "gearshape.fill") }
                .buttonStyle(HologramButtonStyle())
                .accessibilityLabel(copy.settings)
        }
    }

    private var statusLabel: String {
        if !session.enabled { return copy.off }
        if session.requestBusy { return copy.requestPending }
        if session.isImportedImage { return copy.importedSource }
        if session.paused { return copy.pause }
        if !camera.motionAvailable { return copy.manualRecognition }
        return copy.automatic
    }

    @ViewBuilder private var statusLine: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let seconds = visionRetrySeconds(session.retryAtElapsedMs, now: visionElapsedMs())
            let text: String? = {
                if speech.listening { return speech.partialText.isEmpty ? copy.listening : speech.partialText }
                if speech.processing { return copy.recognizingSpeech }
                if session.analyzing { return copy.examining }
                if session.asking { return copy.answering }
                if seconds > 0 { return copy.retryWaiting(seconds) }
                if let error = session.error { return copy.message(for: error) }
                if let notice = speech.notice { return notice }
                if speech.speaking { return copy.speakingReply }
                if let scene = session.scene, !session.scenePinned {
                    return scene.items.isEmpty ? copy.noItems : copy.resultReady
                }
                if session.isImportedImage { return copy.photoReady }
                return session.enabled ? copy.ready : copy.purpose
            }()
            if let text = text {
                Text(text)
                    .font(.callout)
                    .multilineTextAlignment(.leading)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .hologramPanel()
                    .onTapGesture { session.clearError(); speech.clearNotice() }
            }
        }
    }

    private var conversationPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(copy.conversation).font(.subheadline.weight(.semibold))
                Spacer()
                Button(copy.closeConversation) {
                    speech.stopSpeaking()
                    typedQuestion = ""
                    session.clearConversation()
                }
                .font(.caption)
                .foregroundColor(Hedayah.arAccent)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(session.history.enumerated()), id: \.offset) { _, entry in
                        Text(entry.text)
                            .font(entry.role == "user" ? .callout.italic() : .body)
                            .foregroundColor(entry.role == "user" ? Hedayah.arMuted : Hedayah.arText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    SourceLinks(sources: session.replySources, title: copy.sources)
                }
            }
            .frame(maxHeight: 220)
            if let reply = session.reply {
                Button(copy.read) { speech.speak(reply, language: language.code, cloud: session.enabled && platform.enrolled) }
                    .buttonStyle(HologramButtonStyle())
            }
        }
        .padding(12)
        .hologramPanel()
    }

    private var controls: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                TextField(copy.question, text: $typedQuestion)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .hologramPanel(cornerRadius: 12)
                    .submitLabel(.send)
                    .onSubmit(sendTyped)
                Button(action: sendTyped) { Image(systemName: "paperplane.fill") }
                    .buttonStyle(HologramButtonStyle())
                    .disabled(typedQuestion.trimmingCharacters(in: .whitespaces).isEmpty || session.asking)
                    .accessibilityLabel(copy.send)
            }
            HStack(spacing: 8) {
                Button(action: micTapped) {
                    Label(speech.listening ? copy.finishSpeaking : copy.talkShort,
                          systemImage: speech.listening ? "stop.circle.fill" : "mic.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(HologramButtonStyle())
                Button(action: identify) {
                    Label(copy.identify, systemImage: "viewfinder").frame(maxWidth: .infinity)
                }
                .buttonStyle(HologramButtonStyle())
                .disabled(session.requestBusy)
                if session.isImportedImage {
                    Button(action: { session.returnToLive() }) { Image(systemName: "camera.fill") }
                        .buttonStyle(HologramButtonStyle())
                        .accessibilityLabel(copy.returnToCamera)
                } else {
                    PhotosPicker(selection: $photoItem, matching: .images) { Image(systemName: "photo.on.rectangle") }
                        .buttonStyle(HologramButtonStyle())
                        .accessibilityLabel(copy.chooseImage)
                }
                if session.scene != nil && !session.scenePinned {
                    Button(action: { session.pinScene(); showDetail = true }) { Image(systemName: "rectangle.stack.fill") }
                        .buttonStyle(HologramButtonStyle())
                        .accessibilityLabel(copy.review)
                }
            }
        }
    }

    private var consentSheet: some View {
        let sheetCopy = copy
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(sheetCopy.sharing).font(.title3.weight(.semibold))
                Text(sheetCopy.privacy).font(.callout)
                Button(action: { session.setEnabled(true); showConsent = false }) {
                    Text(sheetCopy.agree).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(HedayahFilledButtonStyle())
                Button(sheetCopy.close) { showConsent = false }
                    .frame(maxWidth: .infinity)
            }
            .padding(20)
        }
        .foregroundColor(Hedayah.ink)
        .background(Hedayah.ivory.ignoresSafeArea())
        .environment(\.layoutDirection, language.layoutDirection)
    }

    // MARK: - Actions

    private func appear() {
        visible = true
        resumedAt = visionElapsedMs()
        camera.onFrame = { image, pose, at in CompanionSession.shared.onFrame(image, pose: pose, capturedAt: at) }
        camera.onPose = { pose in CompanionSession.shared.onPose(pose) }
        session.setForeground(scenePhase == .active)
        if camera.authorization == .authorized { camera.start() }
    }

    private func disappear() {
        visible = false
        speech.stopSpeaking()
        speech.cancelListening()
        session.setForeground(false)
        session.setCameraAvailable(false)
        camera.stop()
    }

    /// Recognition needs a group binding first, then explicit snapshot consent.
    private func ensureReady() -> Bool {
        if !platform.enrolled { showSettings = true; return false }
        if !session.enabled { showConsent = true; return false }
        return true
    }

    private func identify() {
        guard ensureReady() else { return }
        speech.stopSpeaking()
        session.analyzeNow()
    }

    private func sendTyped() {
        let question = typedQuestion.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty, ensureReady() else { return }
        speech.stopSpeaking()
        session.askLive(question)
    }

    private func micTapped() {
        if speech.listening { speech.finishListening(); return }
        guard ensureReady() else { return }
        guard session.beginVoiceTurn() else { return }
        speech.startListening(language: language.code, onQuestion: { question in
            typedQuestion = question
            CompanionSession.shared.askLive(question)
        }, onFinished: {
            CompanionSession.shared.endVoiceTurn()
        })
    }

    private func select(_ item: VisionItem) {
        speech.stopSpeaking()
        session.pinScene()
        session.selectItem(item.id)
        showDetail = true
    }

    private func narrate() {
        guard let scene = session.scene, let item = scene.items.first(where: { $0.kind == "landmark" }),
              scenePhase == .active, session.enabled, platform.enrolled,
              session.isImportedImage || session.markersVisible || session.scenePinned else { return }
        let source = session.isImportedImage ? "photo" : "phone"
        let key = "\(source):\(language.code):\(item.landmarkId ?? item.label)"
        let busy = session.voiceBusy || session.asking || session.conversationActive || speech.listening ||
            speech.processing || speech.speaking || showSettings || showConsent
        if narrationGate.shouldSpeak(sceneId: scene.sceneId, key: key, capturedAt: scene.capturedAtElapsedMs,
                                     now: visionElapsedMs(), resumedAt: resumedAt, muted: speech.muted, busy: busy) {
            let text = visionLandmarkNarration(item, language: language.code)
            if !text.isEmpty { speech.speak(text, language: language.code, cloud: true) }
        }
    }

    private func loadPhoto(_ item: PhotosPickerItem?) {
        guard let item = item else { return }
        photoError = nil
        Task { @MainActor in
            defer { photoItem = nil }
            guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else {
                photoError = copy.imageError
                return
            }
            speech.stopSpeaking()
            session.selectImportedImage(image)
        }
    }
}

/// Recognition boxes and nonoverlapping labels drawn over the preview or imported photo.
struct SceneMarkersView: View {
    let scene: VisionScene
    let fit: Bool
    let onSelect: (VisionItem) -> Void

    var body: some View {
        GeometryReader { geometry in
            let width = Double(geometry.size.width), height = Double(geometry.size.height)
            let mapped: [(VisionItem, VisionScreenBox)] = scene.items.compactMap { (item: VisionItem) -> (VisionItem, VisionScreenBox)? in
                let box: VisionScreenBox? = fit
                    ? fitVisionBox(item.box, imageWidth: scene.imageWidth, imageHeight: scene.imageHeight, viewportWidth: width, viewportHeight: height)
                    : cropVisionBox(item.box, imageWidth: scene.imageWidth, imageHeight: scene.imageHeight, viewportWidth: width, viewportHeight: height)
                return box.map { (item, $0) }
            }
            let labelWidth = min(220, width - 24)
            let labels = layoutVisionLabels(mapped.map { $0.1 }, viewportWidth: width, viewportHeight: height,
                                            labelWidth: labelWidth, labelHeight: 64,
                                            // Fixed reserves for the header and controls; the
                                            // overlay ignores safe areas so insets read as zero.
                                            topInset: 130, bottomInset: 240, gap: 8)
            ZStack(alignment: .topLeading) {
                ForEach(Array(mapped.enumerated()), id: \.offset) { index, entry in
                    let box = entry.1
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Hedayah.arAccent, lineWidth: 2)
                        .frame(width: CGFloat(box.right - box.left), height: CGFloat(box.bottom - box.top))
                        .position(x: CGFloat((box.left + box.right) / 2), y: CGFloat((box.top + box.bottom) / 2))
                        .allowsHitTesting(false)
                    if index < labels.count {
                        let label = labels[index]
                        Button(action: { onSelect(entry.0) }) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.0.label).font(.subheadline.weight(.semibold)).lineLimit(1)
                                if !entry.0.description.isEmpty {
                                    Text(entry.0.description).font(.caption).lineLimit(2).foregroundColor(Hedayah.arMuted)
                                }
                            }
                            .padding(8)
                            .frame(width: CGFloat(label.right - label.left), height: CGFloat(label.bottom - label.top), alignment: .leading)
                            .hologramPanel(cornerRadius: 10)
                        }
                        .buttonStyle(.plain)
                        .position(x: CGFloat((label.left + label.right) / 2), y: CGFloat((label.top + label.bottom) / 2))
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            // Overlay coordinates are physical screen positions; never mirror them for RTL.
            .environment(\.layoutDirection, .leftToRight)
        }
    }
}

struct SourceLinks: View {
    let sources: [VisionSource]
    let title: String

    var body: some View {
        if !sources.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.caption.weight(.semibold))
                ForEach(sources) { source in
                    if let url = URL(string: source.url) {
                        Link(source.title, destination: url).font(.caption)
                    }
                }
            }
        }
    }
}
