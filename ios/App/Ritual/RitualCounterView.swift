import SwiftUI

/// Manual Tawaf/Sa'i counter. Works offline and without group enrollment.
struct RitualCounterView: View {
    @ObservedObject private var store = RitualCounterStore.shared
    @ObservedObject private var language = CompanionLanguage.shared
    @State private var confirmReset = false

    init() {}

    var body: some View {
        let copy = RitualCopy(language.code)
        let state = store.state
        let counter = state.localCounter
        ScrollView {
            VStack(spacing: 20) {
                Text(copy.text("title"))
                    .font(.title2.weight(.semibold))
                    .foregroundColor(Hedayah.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Picker("", selection: Binding(get: { state.selectedMode }, set: { store.selectMode($0) })) {
                    Text(copy.name("tawaf")).tag("tawaf")
                    Text(copy.name("sai")).tag("sai")
                }
                .pickerStyle(.segmented)

                VStack(spacing: 8) {
                    Text(copy.number(counter.count))
                        .font(.system(size: 96, weight: .bold, design: .rounded))
                        .foregroundColor(Hedayah.forest)
                        .accessibilityLabel(copy.count(counter.count))
                    Text(counter.count == 7 ? copy.text("recorded") : copy.count(counter.count))
                        .font(.headline)
                        .foregroundColor(Hedayah.muted)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
                .background(RoundedRectangle(cornerRadius: 24).fill(Hedayah.paper))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Hedayah.outline, lineWidth: 1))

                Button(action: { store.increment() }) {
                    Text(copy.text("increment"))
                        .font(.title3.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 64)
                }
                .buttonStyle(HedayahFilledButtonStyle())
                .disabled(counter.count == 7)

                HStack(spacing: 12) {
                    Button(action: { store.undo() }) {
                        Text(copy.text("undo")).frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(HedayahOutlineButtonStyle())
                    .disabled(counter.count == 0)
                    Button(action: { confirmReset = true }) {
                        Text(copy.text("new")).frame(maxWidth: .infinity, minHeight: 52)
                    }
                    .buttonStyle(HedayahOutlineButtonStyle())
                }

                Text(copy.manualNote(state.selectedMode))
                    .font(.footnote)
                    .foregroundColor(Hedayah.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(copy.text("phoneNote"))
                    .font(.footnote)
                    .foregroundColor(Hedayah.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if let error = state.storageError {
                    Text(copy.storageError(error))
                        .font(.callout)
                        .foregroundColor(Hedayah.error)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(20)
        }
        .background(Hedayah.ivory.ignoresSafeArea())
        .environment(\.layoutDirection, copy.rtl ? .rightToLeft : .leftToRight)
        .alert(copy.resetTitle(state.selectedMode), isPresented: $confirmReset) {
            Button(copy.text("reset"), role: .destructive) { store.reset() }
            Button(copy.text("cancel"), role: .cancel) {}
        } message: {
            Text(copy.text("resetBody"))
        }
        .onAppear { store.screenActive = true }
        .onDisappear { store.screenActive = false }
    }
}
