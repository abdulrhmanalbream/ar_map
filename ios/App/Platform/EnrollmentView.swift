import SwiftUI

/// Join the public group by name, or a private group with an invitation code.
/// Joining never enables snapshot sharing; that consent is separate.
struct EnrollmentView: View {
    @ObservedObject private var store = PlatformStore.shared
    @ObservedObject private var language = CompanionLanguage.shared
    @State private var name = ""
    @State private var code = ""
    @State private var busy = false
    @State private var message: String?

    var body: some View {
        let copy = VisionCopy(language: language.code)
        VStack(alignment: .leading, spacing: 12) {
            Text(copy.connect).font(.headline)
            if store.enrolled {
                Text("\(copy.joined): \(store.groupName)").font(.body)
                if !store.name.isEmpty { Text(store.name).font(.footnote).foregroundColor(Hedayah.muted) }
                Button(copy.leave) { store.leave() }
                    .buttonStyle(HedayahOutlineButtonStyle())
            } else {
                Text(copy.connectNote).font(.footnote).foregroundColor(Hedayah.muted)
                TextField(copy.name, text: $name)
                    .textFieldStyle(.roundedBorder)
                    .textContentType(.name)
                TextField(copy.privateCode, text: $code)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled(true)
                Button(action: join) {
                    Group {
                        if busy { ProgressView().tint(Hedayah.paper) }
                        else { Text(code.trimmingCharacters(in: .whitespaces).isEmpty ? copy.joinPublic : copy.joinPrivate) }
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(HedayahFilledButtonStyle())
                .disabled(busy)
            }
            if let message = message ?? (store.enrolled ? nil : store.message), !message.isEmpty {
                Text(message).font(.footnote).foregroundColor(Hedayah.error)
            }
        }
        .onAppear { if name.isEmpty { name = store.name } }
    }

    private func join() {
        let trimmedCode = code.trimmingCharacters(in: .whitespacesAndNewlines)
        busy = true
        message = nil
        let requestedName = name
        let requestedLanguage = language.code
        Task { @MainActor in
            do {
                try await PlatformClient.shared.enroll(name: requestedName, language: requestedLanguage,
                                                       code: trimmedCode.isEmpty ? nil : trimmedCode)
                code = ""
            } catch let error as LocalizedError {
                message = error.errorDescription
            } catch {
                message = platformErrorMessage(status: 0, response: [:])
            }
            busy = false
        }
    }
}
