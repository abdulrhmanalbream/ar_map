import SwiftUI

/// The pinned snapshot: its items, the selected landmark's curated devotionals with their
/// qualifying note and sources, and follow-up questions about this snapshot.
struct SceneDetailView: View {
    @ObservedObject private var session = CompanionSession.shared
    @ObservedObject private var speech = CompanionSpeech.shared
    @ObservedObject private var platform = PlatformStore.shared
    @ObservedObject private var language = CompanionLanguage.shared
    @Environment(\.dismiss) private var dismiss
    @State private var question = ""

    var body: some View {
        let copy = VisionCopy(language: language.code, importedImage: session.isImportedImage)
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let scene = session.scene {
                        Image(uiImage: scene.preview)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        Text(copy.snapshot).font(.caption).foregroundColor(Hedayah.muted)
                        Text(scene.summary).font(.body)
                        Text(copy.estimate).font(.footnote).foregroundColor(Hedayah.darkGold)
                        if scene.items.isEmpty {
                            Text(copy.noItems).font(.callout)
                        }
                        ForEach(scene.items) { item in
                            itemCard(item, scene: scene, copy: copy)
                        }
                        askSection(copy)
                    } else {
                        Text(copy.noItems)
                    }
                }
                .padding(20)
            }
            .foregroundColor(Hedayah.ink)
            .background(Hedayah.ivory.ignoresSafeArea())
            .navigationTitle(copy.resultTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(copy.live) { speech.stopSpeaking(); dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
        .environment(\.layoutDirection, language.layoutDirection)
    }

    @ViewBuilder private func itemCard(_ item: VisionItem, scene: VisionScene, copy: VisionCopy) -> some View {
        let selected = session.selectedItemId == item.id
        VStack(alignment: .leading, spacing: 8) {
            Button(action: { session.selectItem(selected ? nil : item.id) }) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.label).font(.headline)
                    if !item.description.isEmpty { Text(item.description).font(.callout).foregroundColor(Hedayah.muted) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if selected {
                if !item.devotionals.isEmpty {
                    Text(copy.devotionalTitle).font(.subheadline.weight(.semibold)).foregroundColor(Hedayah.forest)
                }
                ForEach(item.devotionals) { devotional in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(devotional.title).font(.subheadline.weight(.semibold))
                        // Arabic scripture/dhikr is shown unchanged, right-to-left, in every language.
                        Text(devotional.arabicText)
                            .font(.title3)
                            .multilineTextAlignment(.trailing)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .environment(\.layoutDirection, .rightToLeft)
                        if language.code != "ar" { Text(devotional.translation).font(.callout) }
                        // The note qualifies when the text applies; it must always accompany it.
                        Text(devotional.note).font(.footnote).foregroundColor(Hedayah.muted)
                        Button(copy.readArabic) {
                            speech.speak(devotional.arabicText, language: "ar", cloud: session.enabled && platform.enrolled)
                        }
                        .font(.footnote)
                        SourceLinks(sources: scene.sources.filter { devotional.sourceIds.contains($0.id) }, title: copy.sources)
                    }
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Hedayah.soft))
                }
                SourceLinks(sources: scene.sources.filter { item.sourceIds.contains($0.id) }, title: copy.sources)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).fill(Hedayah.paper))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Hedayah.forest : Hedayah.outline, lineWidth: selected ? 2 : 1))
    }

    @ViewBuilder private func askSection(_ copy: VisionCopy) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(copy.ask).font(.headline)
            ForEach(Array(session.history.enumerated()), id: \.offset) { _, entry in
                Text(entry.text)
                    .font(entry.role == "user" ? .callout.italic() : .body)
                    .foregroundColor(entry.role == "user" ? Hedayah.muted : Hedayah.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            SourceLinks(sources: session.replySources, title: copy.sources)
            if session.asking { ProgressView(copy.answering) }
            if let error = session.error { Text(copy.message(for: error)).font(.footnote).foregroundColor(Hedayah.error) }
            if let notice = speech.notice { Text(notice).font(.footnote).foregroundColor(Hedayah.muted) }
            HStack(spacing: 8) {
                TextField(copy.question, text: $question)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.send)
                    .onSubmit(send)
                Button(action: send) { Image(systemName: "paperplane.fill") }
                    .disabled(question.trimmingCharacters(in: .whitespaces).isEmpty || session.asking)
                    .accessibilityLabel(copy.send)
            }
            HStack(spacing: 8) {
                Button(action: micTapped) {
                    Label(speech.listening ? copy.finishSpeaking : copy.talkShort,
                          systemImage: speech.listening ? "stop.circle.fill" : "mic.fill")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(HedayahOutlineButtonStyle())
                if let reply = session.reply {
                    Button(action: {
                        if speech.speaking { speech.stopSpeaking() }
                        else { speech.speak(reply, language: language.code, cloud: session.enabled && platform.enrolled) }
                    }) {
                        Text(speech.speaking ? copy.stop : copy.read).frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(HedayahOutlineButtonStyle())
                }
            }
        }
    }

    private func send() {
        let text = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        speech.stopSpeaking()
        session.ask(text)
        question = ""
    }

    private func micTapped() {
        if speech.listening { speech.finishListening(); return }
        guard session.beginVoiceTurn() else { return }
        speech.startListening(language: language.code, onQuestion: { text in
            CompanionSession.shared.ask(text)
        }, onFinished: {
            CompanionSession.shared.endVoiceTurn()
        })
    }
}

/// Language, translation, snapshot consent and group enrollment.
struct CompanionSettingsView: View {
    @ObservedObject private var session = CompanionSession.shared
    @ObservedObject private var platform = PlatformStore.shared
    @ObservedObject private var language = CompanionLanguage.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let copy = VisionCopy(language: language.code, importedImage: session.isImportedImage)
        NavigationView {
            Form {
                Section(header: Text(copy.languageLabel)) {
                    Picker(copy.languageLabel, selection: Binding(get: { language.code }, set: { language.confirm($0) })) {
                        ForEach(companionLanguages, id: \.code) { entry in Text(entry.label).tag(entry.code) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section(header: Text(copy.translate), footer: Text(copy.translateNote)) {
                    Toggle(copy.translate, isOn: Binding(get: { session.translate }, set: { session.setTranslate($0) }))
                }
                Section(header: Text(copy.connect)) {
                    EnrollmentView()
                        .padding(.vertical, 4)
                }
                Section(header: Text(copy.sharing), footer: Text(copy.privacy)) {
                    Toggle(copy.sharing, isOn: Binding(get: { session.enabled }, set: { session.setEnabled($0) }))
                        .disabled(!platform.enrolled)
                    if session.enabled {
                        Toggle(copy.pause, isOn: Binding(get: { session.paused }, set: { session.setPaused($0) }))
                    }
                }
            }
            .navigationTitle(copy.settings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(copy.close) { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .environment(\.layoutDirection, language.layoutDirection)
    }
}

/// Rendered on the XREAL glasses (USB-C external display). Pure black is transparent in
/// the optics, so there is no camera video: the wearer already sees the real world.
/// Shows recognized landmark names, the latest answer, listening/thinking state and the
/// ritual count while the counter screen is open. Large, high-contrast mint/ivory text.
struct CompanionGlassesOverlay: View {
    @ObservedObject private var session = CompanionSession.shared
    @ObservedObject private var ritual = RitualCounterStore.shared
    @ObservedObject private var language = CompanionLanguage.shared

    init() {}

    var body: some View {
        let copy = VisionCopy(language: language.code, importedImage: session.isImportedImage)
        let ritualCopy = RitualCopy(language.code)
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .top) {
                    if let badge = activityBadge(copy) {
                        Label(badge.text, systemImage: badge.symbol)
                            .font(.system(size: 28, weight: .semibold))
                            .foregroundColor(Hedayah.arAccent)
                    }
                    Spacer()
                    if ritual.screenActive {
                        let counter = ritual.state.localCounter
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(ritualCopy.name(ritual.state.selectedMode))
                                .font(.system(size: 26, weight: .medium))
                                .foregroundColor(Hedayah.arMuted)
                            Text(ritualCopy.count(counter.count))
                                .font(.system(size: 54, weight: .bold, design: .rounded))
                                .foregroundColor(Hedayah.hudGold)
                        }
                    }
                }
                if !landmarkLabels.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(landmarkLabels, id: \.self) { label in
                            Text(label)
                                .font(.system(size: 38, weight: .bold))
                                .foregroundColor(Hedayah.arAccent)
                                .lineLimit(2)
                        }
                    }
                }
                Spacer(minLength: 0)
                if let reply = session.reply, !reply.isEmpty {
                    Text(reply)
                        .font(.system(size: 30, weight: .regular))
                        .foregroundColor(Hedayah.ivory)
                        .lineSpacing(6)
                        .lineLimit(9)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(48)
        }
        .environment(\.layoutDirection, language.layoutDirection)
    }

    /// Landmarks only while they are current: live matched markers, a pinned snapshot,
    /// or an imported photo. Stale results never float in the wearer's view.
    private var landmarkLabels: [String] {
        guard let scene = session.scene, session.markersVisible || session.scenePinned || session.isImportedImage else { return [] }
        if let selected = session.selectedItem { return [selected.label] }
        return Array(scene.items.prefix(3).map { $0.label })
    }

    private func activityBadge(_ copy: VisionCopy) -> (text: String, symbol: String)? {
        if session.listening { return (copy.listening, "mic.fill") }
        if session.asking { return (copy.answering, "ellipsis.bubble.fill") }
        if session.analyzing { return (copy.examining, "viewfinder") }
        if session.speaking { return (copy.speakingReply, "speaker.wave.2.fill") }
        return nil
    }
}
